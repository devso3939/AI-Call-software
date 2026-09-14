-- =====================================================================
-- Migration 022 — bulk SMS + visible credentials (user request: "make
-- sure from smartbookly i will be able to sent bulk sms ... several at
-- once ... effectively without errors" and "don't hide existing
-- password and credentials — i handle security on my side").
--
-- PART A — RECOVERABLE PASSWORD:
--   Add password_enc column (AES-like via pgcrypto encrypt_iv with a
--   server-side secret stored in private.gateway_secret). get_sms_gateway()
--   (web) and gateway_status_from_device (phone) now return the password
--   so the owner can always look it up. gateway_api_preamble still
--   authenticates against the HASH — unchanged security for third parties.
--   Rotation re-encrypts the new password (both setup functions updated).
--
-- PART B — BULK SEND:
--   gateway_api_send_bulk(username|token, password?, messages jsonb)
--   • messages = [{to, body}, ...] 1..500 entries in ONE request
--   • one rate-limit hit, one audit row (with count) — 500 messages no
--     longer burn 500 of the 120/min request budget
--   • validates every entry FIRST (all-or-nothing: one bad row → nothing
--     inserted, error names the offending index)
--   • inserts all sms_messages + gateway_commands in single statements
--   • returns per-message ids so SmartBookly can track each one
-- =====================================================================

create extension if not exists pgcrypto;

-- server-side encryption secret for recoverable passwords
create schema if not exists private;
create table if not exists private.gateway_secret (k text primary key, v text not null);
insert into private.gateway_secret (k, v)
  values ('pw_key', encode(extensions.gen_random_bytes(32), 'hex'))
  on conflict (k) do nothing;

-- ---------- PART A: add encrypted password column ----------
alter table private.gateway_accounts
  add column if not exists password_enc bytea;

-- helper: encrypt with the server secret (composable in SQL).
-- pgp_sym_encrypt/pgp_sym_decrypt: key is a text passphrase — simplest
-- symmetric option that exists in Supabase's pgcrypto.
create or replace function private.encrypt_pwhex(p_plain text)
returns bytea
language sql
security definer
set search_path = public, pg_temp
as $$
  select extensions.pgp_sym_encrypt(
    p_plain,
    (select v from private.gateway_secret where k = 'pw_key'),
    'cipher-algo=aes256')
$$;

create or replace function private.decrypt_pwhex(p_enc bytea)
returns text
language sql
security definer
set search_path = public, pg_temp
as $$
  select extensions.pgp_sym_decrypt(
    p_enc,
    (select v from private.gateway_secret where k = 'pw_key'))
$$;

-- backfill: we cannot recover existing hashed passwords, so set a
-- placeholder that the next rotation replaces. The user's known
-- password (set directly during debugging) is seeded below.
update private.gateway_accounts
  set password_enc = private.encrypt_pwhex('ocgw_9f4c2b7a81d6e35f0a4c5d8b2e7f1a93')
  where username = 'smartbookly'
    and password_enc is null
    and password_hash = private.sha256_hex('ocgw_9f4c2b7a81d6e35f0a4c5d8b2e7f1a93');

-- ---------- PART A: rotation stores recoverable copy ----------
-- (setup_sms_gateway / gateway_setup_from_device re-defined below with
--  password_enc = private.encrypt_pwhex(v_pw) added to both branches)

create or replace function public.setup_sms_gateway(p_device_id uuid, p_username text, p_password text default null, p_any_device boolean default false)
returns jsonb
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  v_uid  uuid := auth.uid();
  v_pw   text;
  v_un   text;
  v_acct private.gateway_accounts;
  v_dev  public.gateway_devices;
  v_owner uuid;
begin
  if v_uid is null then raise exception 'sign in first'; end if;
  select * into v_dev from public.gateway_devices where id = p_device_id and user_id = v_uid;
  if v_dev.id is null then raise exception 'device not found among your paired devices'; end if;

  if p_username is null or length(trim(p_username)) < 3 or length(trim(p_username)) > 40
     or trim(p_username) !~ '^[a-z0-9][a-z0-9._-]*$' then
    raise exception 'username must be 3..40 chars: lowercase letters, digits, dot, dash, underscore';
  end if;
  v_un := lower(trim(p_username));
  v_pw := coalesce(nullif(trim(p_password), ''), '');
  if v_pw = '' then
    v_pw := 'ocgw_' || encode(extensions.gen_random_bytes(18), 'hex');
  elsif length(v_pw) < 8 then
    raise exception 'password must be at least 8 characters (or leave empty to auto-generate)';
  end if;

  select user_id into v_owner from private.gateway_accounts
    where username = v_un limit 1;
  if v_owner is not null and v_owner <> v_uid then
    raise exception 'username "%" is already taken — pick another one', v_un;
  end if;

  if v_owner = v_uid then
    update private.gateway_accounts
      set password_hash = private.sha256_hex(v_pw),
          password_enc  = private.encrypt_pwhex(v_pw),
          device_id     = p_device_id,
          any_device    = coalesce(p_any_device, false),
          enabled       = true
      where username = v_un and user_id = v_uid
      returning * into v_acct;
  else
    insert into private.gateway_accounts (user_id, username, password_hash, password_enc, device_id, any_device, enabled)
      values (v_uid, v_un, private.sha256_hex(v_pw), private.encrypt_pwhex(v_pw), p_device_id, coalesce(p_any_device, false), true)
    on conflict (user_id) where enabled do update
      set username = excluded.username,
          password_hash = excluded.password_hash,
          password_enc = excluded.password_enc,
          device_id = excluded.device_id,
          any_device = excluded.any_device,
          enabled = true
    returning * into v_acct;
  end if;

  delete from private.gateway_tokens
    where account_id in (select id from private.gateway_accounts where user_id = v_uid and enabled);
  return jsonb_build_object('accountId', v_acct.id, 'username', v_acct.username,
                            'password', v_pw, 'deviceId', v_dev.id, 'deviceName', v_dev.name);
end;
$$;
grant execute on function public.setup_sms_gateway(uuid, text, text, boolean) to authenticated;

create or replace function public.gateway_setup_from_device(
  p_device_id     uuid,
  p_device_secret text,
  p_username      text,
  p_password      text default null
)
returns jsonb
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  v_dev   public.gateway_devices;
  v_uid   uuid;
  v_pw    text;
  v_un    text;
  v_acct  private.gateway_accounts;
  v_owner uuid;
begin
  select * into v_dev from public.gateway_devices
    where id = p_device_id
      and secret_hash = private.sha256_hex(coalesce(trim(p_device_secret), ''));
  if v_dev.id is null then
    raise exception 'invalid device credentials';
  end if;
  v_uid := v_dev.user_id;

  if p_username is null or length(trim(p_username)) < 3 or length(trim(p_username)) > 40
     or trim(p_username) !~ '^[a-z0-9][a-z0-9._-]*$' then
    raise exception 'username must be 3..40 chars: lowercase letters, digits, dot, dash, underscore';
  end if;
  v_un := lower(trim(p_username));
  v_pw := coalesce(nullif(trim(p_password), ''), '');
  if v_pw = '' then
    v_pw := 'ocgw_' || encode(extensions.gen_random_bytes(18), 'hex');
  elsif length(v_pw) < 8 then
    raise exception 'password must be at least 8 characters (or leave empty to auto-generate)';
  end if;

  select user_id into v_owner from private.gateway_accounts
    where username = v_un limit 1;
  if v_owner is not null and v_owner <> v_uid then
    raise exception 'username "%" is already taken — pick another one', v_un;
  end if;

  if v_owner = v_uid then
    update private.gateway_accounts
      set password_hash = private.sha256_hex(v_pw),
          password_enc  = private.encrypt_pwhex(v_pw),
          device_id     = v_dev.id,
          enabled       = true
      where username = v_un and user_id = v_uid
      returning * into v_acct;
  else
    insert into private.gateway_accounts (user_id, username, password_hash, password_enc, device_id, any_device, enabled)
      values (v_uid, v_un, private.sha256_hex(v_pw), private.encrypt_pwhex(v_pw), v_dev.id, false, true)
    on conflict (user_id) where enabled do update
      set username = excluded.username,
          password_hash = excluded.password_hash,
          password_enc = excluded.password_enc,
          device_id = excluded.device_id,
          enabled = true
    returning * into v_acct;
  end if;

  delete from private.gateway_tokens
    where account_id in (select id from private.gateway_accounts where user_id = v_uid and enabled);

  insert into public.gateway_api_log (user_id, account_id, action, ok, detail)
    values (v_uid, v_acct.id, 'device_setup', true,
            jsonb_build_object('device', v_dev.name, 'username', v_acct.username));

  return jsonb_build_object('accountId', v_acct.id, 'username', v_acct.username,
                            'password', v_pw, 'deviceId', v_dev.id, 'deviceName', v_dev.name);
end;
$$;
grant execute on function public.gateway_setup_from_device(uuid, text, text, text) to anon, authenticated;

-- ---------- PART A: owner surfaces return the password ----------
create or replace function public.get_sms_gateway()
returns jsonb
language sql
security definer
set search_path = public, pg_temp
as $$
  select jsonb_build_object(
    'enabled', (a.id is not null),
    'accountId', a.id,
    'username', a.username,
    -- VISIBLE CREDENTIALS (022): owner handles security; password readable
    'password', case when a.password_enc is null then null
                     else private.decrypt_pwhex(a.password_enc) end,
    'deviceId', a.device_id,
    'deviceName', d.name,
    'deviceNumber', d.sim_number,
    'deviceOnline', d.online,
    'anyDevice', a.any_device,
    'requestCount', a.request_count,
    'sentCount', a.sent_count,
    'failedCount', a.failed_count,
    'lastUsedAt', a.last_used_at,
    'createdAt', a.created_at
  )
  from private.gateway_accounts a
  left join public.gateway_devices d on d.id = a.device_id
  where a.user_id = auth.uid() and a.enabled;
$$;
grant execute on function public.get_sms_gateway() to authenticated;

create or replace function public.gateway_status_from_device(p_device_id uuid, p_device_secret text)
returns jsonb
language sql
security definer
set search_path = public, pg_temp
as $$
  select jsonb_build_object(
    'enabled', (a.id is not null),
    'username', a.username,
    -- VISIBLE CREDENTIALS (022): the phone can always show them
    'password', case when a.password_enc is null then null
                     else private.decrypt_pwhex(a.password_enc) end,
    'deviceId', a.device_id,
    'deviceName', d.name,
    'online', private.device_fresh(d.online, d.last_seen_at),
    'battery', case when private.device_fresh(d.online, d.last_seen_at)
                    then d.battery else null end,
    'lastSeenAt', d.last_seen_at,
    'sentTotal', a.sent_count,
    'failedTotal', a.failed_count)
  from public.gateway_devices d
  left join private.gateway_accounts a
    on a.user_id = d.user_id and a.enabled and a.device_id = d.id
  where d.id = p_device_id
    and d.secret_hash = private.sha256_hex(coalesce(trim(p_device_secret), ''));
$$;
grant execute on function public.gateway_status_from_device(uuid, text) to anon, authenticated;

-- ---------- PART B: bulk send ----------
create or replace function public.gateway_api_send_bulk(
  p_username text default null,
  p_password text default null,
  p_token    text default null,
  p_messages jsonb default null
)
returns jsonb
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  v_acct  private.gateway_accounts;
  v_dev   public.gateway_devices;
  v_n     int;
  v_bad   int := -1;
  v_msg   record;
  v_ids   jsonb;
begin
  if p_token is null and p_password is null and p_username is not null
     and p_username ~ '^ogt_[0-9a-f]{48}$' then
    p_token := p_username; p_username := null;
  end if;
  -- ONE rate-limit hit + ONE audit row for the whole batch
  v_acct := private.gateway_api_preamble(p_username, p_password, p_token, 'send_bulk',
    p_detail => jsonb_build_object('count', coalesce(jsonb_array_length(p_messages), 0)));

  if p_messages is null or jsonb_typeof(p_messages) <> 'array'
     or jsonb_array_length(p_messages) = 0 then
    raise exception 'messages must be a non-empty JSON array of {to, body}';
  end if;
  v_n := jsonb_array_length(p_messages);
  if v_n > 500 then
    raise exception 'max 500 messages per batch (got %) — split into multiple requests', v_n;
  end if;

  -- device pick once for the whole batch
  v_dev := private.gateway_pick_device(v_acct);
  if v_dev.id is null then
    update private.gateway_accounts set failed_count = failed_count + v_n where id = v_acct.id;
    update public.gateway_api_log
      set ok = false, detail = detail || jsonb_build_object('error', 'no gateway device online')
      where id = (select id from public.gateway_api_log
                  where account_id = v_acct.id and action = 'send_bulk'
                  order by id desc limit 1);
    raise exception 'no gateway device online — the bound phone must heartbeat (open the gateway app on the phone)';
  end if;

  -- validate ALL entries first (all-or-nothing)
  for v_msg in select value, ord - 1 as idx from jsonb_array_elements(p_messages) with ordinality as t(value, ord)
  loop
    if v_msg.value->>'to' !~ '^\+[1-9][0-9]{3,15}$'
       or coalesce(length(v_msg.value->>'body'), 0) = 0
       or length(v_msg.value->>'body') > 1600 then
      v_bad := v_msg.idx;
      exit;
    end if;
  end loop;
  if v_bad >= 0 then
    update public.gateway_api_log
      set ok = false, detail = detail || jsonb_build_object('error', 'invalid entry at index ' || v_bad)
      where id = (select id from public.gateway_api_log
                  where account_id = v_acct.id and action = 'send_bulk'
                  order by id desc limit 1);
    raise exception 'invalid message at index % — "to" must be E.164 (+country...) and body 1..1600 chars; NOTHING was sent', v_bad;
  end if;

  -- insert all sms rows in one statement
  with ins as (
    insert into public.sms_messages (user_id, device_id, direction, to_number, body, status)
    select v_acct.user_id, v_dev.id, 'outbound',
           m.value->>'to', m.value->>'body', 'queued'
    from jsonb_array_elements(p_messages) with ordinality as m(value, ord)
    returning id, to_number
  )
  -- ...and all device commands in one statement, linked to their sms rows
  , cmds as (
    insert into public.gateway_commands (device_id, kind, payload, requested_by)
    select v_dev.id, 'send_sms',
           jsonb_build_object('smsId', ins.id, 'to', ins.to_number,
                              'body', (select pm.value->>'body'
                                       from jsonb_array_elements(p_messages) with ordinality pm
                                       where pm.value->>'to' = ins.to_number
                                       limit 1),
                              'via', 'sms-gateway:' || v_acct.username),
           v_acct.user_id
    from ins
    returning id
  )
  select jsonb_agg(jsonb_build_object('smsId', ins.id, 'to', ins.to_number) order by ins.id)
    into v_ids
  from ins;

  update private.gateway_accounts set sent_count = sent_count + v_n where id = v_acct.id;
  update public.gateway_api_log
    set ok = true, detail = detail || jsonb_build_object('queued', v_n, 'deviceId', v_dev.id)
    where id = (select id from public.gateway_api_log
                where account_id = v_acct.id and action = 'send_bulk'
                order by id desc limit 1);

  return jsonb_build_object('queued', v_n, 'device', v_dev.name,
                            'messages', v_ids);
end;
$$;
grant execute on function public.gateway_api_send_bulk(text, text, text, jsonb) to anon, authenticated;
