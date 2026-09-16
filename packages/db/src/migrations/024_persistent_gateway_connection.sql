-- =====================================================================
-- Migration 024 — persistent gateway connections ("connect once, stays
-- connected until the user stops it").
--
-- User report: "when i generate key and conect it to platform, that
-- conection with that credentials shouldnot be ruined untill i stop it
-- manually ... now every time i need to reconect smartbookly with our
-- gateway thats wrong".
--
-- ROOT CAUSES FOUND (live DB evidence: gateway_tokens table EMPTY —
-- every token ever issued died after 24 h):
--
--  1. 24-hour token death: gateway_api_login (014) issued tokens with
--     expires_at = now() + 24 h. SmartBookly logged in once on
--     2026-09-14; the token was dead the next day → "invalid
--     credentials" → user re-pairs → which rotates the password (see
--     #2) → breaking the stored username+password too. A
--     self-reinforcing breakage loop.
--  2. Rotation on EVERY setup call: setup_sms_gateway (022) and
--     gateway_setup_from_device (022) ALWAYS generated a new password
--     AND deleted all outstanding tokens — even a plain reconnect.
--  3. Device re-pair could orphan the account: the partial-index
--     ON CONFLICT only fires for ENABLED rows, and the update branch
--     never set enabled/device rebinding for a disabled row.
--  4. Offline sends (018) raise an error indistinguishable from "bad
--     credentials" → third parties burn time re-authing / reconnecting
--     when the only problem is the phone not heartbeating for 2 min.
--
-- FIXES — the connection now breaks ONLY when the user stops it
-- (disable in the app) or rotates the password on purpose:
--
--  A. Tokens never expire: expires_at becomes nullable; null = never.
--     All existing tokens are made permanent. gateway_account_for_auth
--     now accepts null-expires tokens (CRITICAL companion fix —
--     otherwise "expires_at > now()" would reject every permanent
--     token). Tokens die only on explicit rotate or disable.
--  B. Rotate-only-when-asked: both setup paths gain a trailing
--     p_rotate boolean (default false = KEEP). KEEP mode re-enables
--     and re-binds the user's existing account WITHOUT touching
--     username, password_hash, password_enc, or tokens, and returns
--     { password: null, reused: true }. ROTATE mode (explicit) keeps
--     the old destructive behavior: new password + token purge.
--     Old 4-arg overloads are DROPPED so every existing caller
--     (4-arg RPCs from web + installed APKs) lands on the new
--     KEEP-by-default behavior with zero client changes.
--  C. Re-pair re-binds: KEEP mode sets device_id + enabled = true, so
--     re-pairing the phone or re-running setup can never orphan the
--     account (and revives a manually-disabled row on a deliberate
--     reconnect, preserving its credentials).
--  D. Honest offline errors: send functions keep their 018/022
--     lifecycle EXACTLY (same schema, same commands, no waiting — a
--     heartbeat can arrive in seconds once the phone wakes), but the
--     error text now says credentials are VALID, so integrations stop
--     misreading "phone asleep" as "connection broken".
-- =====================================================================

-- =====================================================================
-- A) tokens never expire
-- =====================================================================
alter table private.gateway_tokens
  alter column expires_at drop not null;
comment on column private.gateway_tokens.expires_at is
  'null = never expires (persistent connection, migration 024).';

-- every token issued so far becomes permanent (including the
-- already-expired ones — killing an integration over an arbitrary 24h
-- wall-clock was exactly the bug)
update private.gateway_tokens set expires_at = null where expires_at is not null;

-- CRITICAL companion fix: token auth must accept permanent tokens.
-- (014 line "and t.expires_at > now()" evaluates NULL for null
-- expires_at and would silently reject every permanent token.)
create or replace function private.gateway_account_for_auth(p_username text, p_password text, p_token text)
returns private.gateway_accounts
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  v_acct private.gateway_accounts;
begin
  if p_token is not null and length(p_token) >= 20 then
    select a.* into v_acct
    from private.gateway_tokens t
    join private.gateway_accounts a on a.id = t.account_id
    where t.token_hash = private.sha256_hex(p_token)
      and (t.expires_at is null or t.expires_at > now())
      and a.enabled;
    if v_acct.id is not null then return v_acct; end if;
  end if;
  if p_username is not null and p_password is not null then
    select * into v_acct from private.gateway_accounts
    where username = lower(trim(p_username)) and enabled
    limit 1;
    if v_acct.id is not null
       and v_acct.password_hash = private.sha256_hex(p_password) then
      return v_acct;
    end if;
  end if;
  return null; -- not found / bad credentials (caller raises)
end;
$$;

-- =====================================================================
-- A2) login issues PERMANENT tokens
-- =====================================================================
create or replace function public.gateway_api_login(p_username text, p_password text)
returns jsonb
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  v_acct private.gateway_accounts;
  v_tok  text;
begin
  v_acct := private.gateway_api_preamble(p_username, p_password, null, 'login');
  v_tok := 'ogt_' || encode(extensions.gen_random_bytes(24), 'hex');
  -- 024: permanent. The connection stays until the user rotates the
  -- password on purpose or disables the gateway. No 24h death.
  insert into private.gateway_tokens (token_hash, account_id, label, expires_at)
    values (private.sha256_hex(v_tok), v_acct.id, 'api session', null);
  -- opportunistic cleanup of legacy expired rows only (null never
  -- matches "< now()", so permanent tokens are never touched)
  delete from private.gateway_tokens where expires_at < now();
  return jsonb_build_object('token', v_tok, 'tokenType', 'Bearer',
                            'expiresAt', null,
                            'expiresIn', 'never',
                            'username', v_acct.username);
end;
$$;
grant execute on function public.gateway_api_login(text, text) to anon, authenticated;

-- =====================================================================
-- B+C) web setup: KEEP by default (reconnect), rotate only when asked
--      (022 body preserved for the rotate/creation path, including the
--      recoverable password_enc copy)
-- =====================================================================
create or replace function public.setup_sms_gateway(
  p_device_id  uuid,
  p_username   text,
  p_password   text default null,
  p_any_device boolean default null,
  p_rotate     boolean default false
)
returns jsonb
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  v_uid  uuid := auth.uid();
  v_dev  public.gateway_devices;
  v_acct private.gateway_accounts;
  v_pw   text;
  v_un   text;
  v_owner uuid;
begin
  if v_uid is null then raise exception 'sign in first'; end if;

  if not coalesce(p_rotate, false) then
    -- 024 KEEP mode: reconnect / re-open / re-pair — credentials stay
    -- exactly as they are. Only the binding is refreshed.
    if p_device_id is not null then
      select * into v_dev from public.gateway_devices
        where id = p_device_id and user_id = v_uid;
      if v_dev.id is null then raise exception 'device not found among your paired devices'; end if;
    end if;

    -- latest row, enabled first (reviving a disabled row here is a
    -- deliberate reconnect; an enabled row can never conflict with the
    -- one-enabled-row-per-user index because it is picked first)
    select * into v_acct from private.gateway_accounts
      where user_id = v_uid
      order by enabled desc, created_at desc
      limit 1;
    if v_acct.id is not null then
      update private.gateway_accounts
        set device_id  = coalesce(v_dev.id, v_acct.device_id),
            any_device = coalesce(p_any_device, v_acct.any_device),
            enabled    = true
        where id = v_acct.id
        returning * into v_acct;
      insert into public.gateway_api_log (user_id, account_id, action, ok, detail)
        values (v_uid, v_acct.id, 'setup', true,
                jsonb_build_object('kept', true, 'username', v_acct.username));
      return jsonb_build_object('accountId', v_acct.id, 'username', v_acct.username,
                                'password', null, 'reused', true,
                                'deviceId', v_acct.device_id,
                                'deviceName', (select d.name from public.gateway_devices d where d.id = v_acct.device_id));
    end if;
    -- no account yet → fall through to first-time creation below
  end if;

  -- ---------- ROTATE mode (explicit) / first-time creation (022) ----------
  if p_device_id is null then raise exception 'device not found among your paired devices'; end if;
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

  -- credentials changed ON PURPOSE → outstanding tokens must die
  delete from private.gateway_tokens
    where account_id in (select id from private.gateway_accounts where user_id = v_uid and enabled);

  insert into public.gateway_api_log (user_id, account_id, action, ok, detail)
    values (v_uid, v_acct.id, 'setup', true,
            jsonb_build_object('rotated', true, 'username', v_acct.username));

  return jsonb_build_object('accountId', v_acct.id, 'username', v_acct.username,
                            'password', v_pw, 'reused', false,
                            'deviceId', v_dev.id, 'deviceName', v_dev.name);
end;
$$;
grant execute on function public.setup_sms_gateway(uuid, text, text, boolean, boolean) to authenticated;
-- old 4-arg version MUST go: otherwise existing 4-arg RPCs keep hitting
-- the destructive always-rotate behavior
drop function if exists public.setup_sms_gateway(uuid, text, text, boolean);

-- =====================================================================
-- B+C) device setup: KEEP by default, rotate only when asked
--      (022 body preserved for rotate/creation, password_enc included)
-- =====================================================================
create or replace function public.gateway_setup_from_device(
  p_device_id     uuid,
  p_device_secret text,
  p_username      text,
  p_password      text default null,
  p_rotate        boolean default false
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

  if not coalesce(p_rotate, false) then
    -- 024 KEEP mode: the phone re-opened setup / re-paired / the
    -- platform reconnected — do NOT touch stored credentials. Fail
    -- fast on a username that belongs to someone else (honest error),
    -- otherwise refresh the binding on the user's existing account.
    if p_username is not null and length(trim(p_username)) >= 3
       and trim(p_username) ~ '^[a-z0-9][a-z0-9._-]*$' then
      select user_id into v_owner
        from private.gateway_accounts
        where username = lower(trim(p_username)) limit 1;
      if v_owner is not null and v_owner <> v_uid then
        raise exception 'username "%" is already taken — pick another one', lower(trim(p_username));
      end if;
    end if;

    select * into v_acct from private.gateway_accounts
      where user_id = v_uid
      order by enabled desc, created_at desc
      limit 1;
    if v_acct.id is not null then
      update private.gateway_accounts
        set device_id = v_dev.id,   -- re-pair re-binds (fix #3)
            enabled   = true
        where id = v_acct.id
        returning * into v_acct;
      insert into public.gateway_api_log (user_id, account_id, action, ok, detail)
        values (v_uid, v_acct.id, 'device_setup', true,
                jsonb_build_object('kept', true, 'device', v_dev.name, 'username', v_acct.username));
      return jsonb_build_object('accountId', v_acct.id, 'username', v_acct.username,
                                'password', null, 'reused', true,
                                'deviceId', v_dev.id, 'deviceName', v_dev.name);
    end if;
    -- no account yet → fall through to first-time creation below
  end if;

  -- ---------- ROTATE mode (explicit) / first-time creation (022) ----------
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

  -- credentials changed ON PURPOSE → outstanding tokens must die
  delete from private.gateway_tokens
    where account_id in (select id from private.gateway_accounts where user_id = v_uid and enabled);

  insert into public.gateway_api_log (user_id, account_id, action, ok, detail)
    values (v_uid, v_acct.id, 'device_setup', true,
            jsonb_build_object('rotated', true, 'device', v_dev.name, 'username', v_acct.username));

  return jsonb_build_object('accountId', v_acct.id, 'username', v_acct.username,
                            'password', v_pw, 'reused', false,
                            'deviceId', v_dev.id, 'deviceName', v_dev.name);
end;
$$;
grant execute on function public.gateway_setup_from_device(uuid, text, text, text, boolean) to anon, authenticated;
-- old 4-arg version MUST go: installed APKs calling with 4 args then
-- resolve to the new 5-arg default (KEEP) instead of the old rotator
drop function if exists public.gateway_setup_from_device(uuid, text, text, text);

-- =====================================================================
-- D) honest sends: same 018/022 lifecycle verbatim (direction/status,
--    gateway_commands, reaper, pick_device) — ONLY the offline error
--    text changes, so "phone asleep" is never mistaken for "connection
--    broken". No waiting inside SQL: once the phone wakes and
--    heartbeats, the very next send succeeds — with permanent tokens
--    there is nothing to re-auth.
-- =====================================================================
create or replace function public.gateway_api_send_sms(p_username text default null, p_password text default null, p_token text default null, p_to text default null, p_body text default null)
returns jsonb
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  v_acct private.gateway_accounts;
  v_dev  public.gateway_devices;
  v_sms  public.sms_messages;
begin
  -- accept both flat (sms-gate style: username, password, to, body) and token style
  if p_token is null and p_password is null and p_username is not null
     and p_username ~ '^ogt_[0-9a-f]{48}$' then
    p_token := p_username; p_username := null;
  end if;

  v_acct := private.gateway_api_preamble(p_username, p_password, p_token, 'send_sms');

  if p_to is null or length(trim(p_to)) < 4 then
    raise exception 'to must be a valid phone number';
  end if;
  if p_body is null or length(p_body) = 0 or length(p_body) > 1600 then
    raise exception 'body must be 1..1600 chars';
  end if;

  -- HONEST SEND (018): reap old stale sends first, then REQUIRE a fresh
  -- heartbeat before accepting a new one. 024: the error now makes
  -- clear the credentials themselves are fine — no reconnect needed.
  perform private.gateway_reap_stale();
  v_dev := private.gateway_pick_device(v_acct);
  if v_dev.id is null then
    update private.gateway_accounts set failed_count = failed_count + 1 where id = v_acct.id;
    update public.gateway_api_log
      set ok = false, detail = detail || jsonb_build_object('error', 'no gateway device online — credentials are valid, just bring the phone online (open the OpenCall Gateway app)', 'reason', 'device_offline')
      where id = (select id from public.gateway_api_log
                  where account_id = v_acct.id and action = 'send_sms'
                  order by id desc limit 1);
    raise exception 'no gateway device online — credentials are VALID, do not reconnect; open the OpenCall Gateway app on the phone and retry when it heartbeats';
  end if;

  insert into public.sms_messages (user_id, device_id, direction, to_number, body, status)
    values (v_acct.user_id, v_dev.id, 'outbound', p_to, p_body, 'queued')
    returning * into v_sms;

  insert into public.gateway_commands (device_id, kind, payload, requested_by)
    values (v_dev.id, 'send_sms',
            jsonb_build_object('smsId', v_sms.id, 'to', p_to, 'body', p_body, 'via', 'sms-gateway:' || v_acct.username),
            v_acct.user_id);

  update private.gateway_accounts set sent_count = sent_count + 1 where id = v_acct.id;
  update public.gateway_api_log
    set ok = true, detail = detail || jsonb_build_object('smsId', v_sms.id, 'deviceId', v_dev.id)
    where id = (select id from public.gateway_api_log
                where account_id = v_acct.id and action = 'send_sms'
                order by id desc limit 1);
  return jsonb_build_object('smsId', v_sms.id, 'status', v_sms.status,
                            'to', p_to, 'device', v_dev.name,
                            'queuedAt', v_sms.created_at);
end;
$$;
grant execute on function public.gateway_api_send_sms(text, text, text, text, text) to anon, authenticated;

-- bulk: 022 body verbatim, offline message clarified
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
      set ok = false, detail = detail || jsonb_build_object('error', 'no gateway device online — credentials are valid', 'reason', 'device_offline')
      where id = (select id from public.gateway_api_log
                  where account_id = v_acct.id and action = 'send_bulk'
                  order by id desc limit 1);
    raise exception 'no gateway device online — credentials are VALID, do not reconnect; open the gateway app on the phone and retry when it heartbeats';
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
