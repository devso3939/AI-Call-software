-- =====================================================================
-- Migration 018 — honest send lifecycle: no message may sit "queued"
-- forever against a dead phone.
--
-- What happened today (user report): two sends at 09:13/09:14 were
-- accepted while the phone had not heartbeated for 39 hours, inserted
-- as status='queued' + gateway_commands rows status='pending', and the
-- caller (SmartBookly) was told "queued on your phone" — then nothing
-- ever happened, because the phone's gateway service was not running.
--
-- Fixes:
--   1. gateway_api_send_sms now REJECTS sends when no device has a
--      fresh heartbeat (≤ 2 min) — the caller gets an honest error
--      immediately instead of a fake "queued".
--   2. A reaper: stale pending commands (> 10 min) + their queued
--      sms_messages are marked failed with a clear error, so nothing
--      sits in limbo. Runs inside get_sms/heartbeat/fetch_commands.
-- =====================================================================

-- ---------- reaper: fail stale sends ----------
create or replace function private.gateway_reap_stale()
returns void
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  v_ids uuid[];
begin
  -- pending commands older than 10 minutes whose device has no fresh
  -- heartbeat → failed
  select coalesce(array_agg(c.id), '{}'::uuid[]) into v_ids
  from public.gateway_commands c
  join public.gateway_devices d on d.id = c.device_id
  where c.kind = 'send_sms'
    and c.status = 'pending'
    and c.created_at < now() - interval '10 minutes'
    and not private.device_fresh(d.online, d.last_seen_at);

  if array_length(v_ids, 1) > 0 then
    update public.gateway_commands
      set status = 'failed',
          result = jsonb_build_object('error', 'phone offline — gateway app was not running; message never sent')
      where id = any(v_ids);

    update public.sms_messages m
      set status = 'failed',
          error = 'phone offline — gateway app was not running; message never sent',
          updated_at = now()
      where m.status = 'queued'
        and m.device_id in (select c.device_id from public.gateway_commands c where c.id = any(v_ids))
        and m.created_at < now() - interval '10 minutes';
  end if;
end;
$$;

-- ---------- 1) send: refuse when no fresh device ----------
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
  -- heartbeat before accepting a new one. The caller learns "phone
  -- offline" NOW instead of discovering it hours later.
  perform private.gateway_reap_stale();
  v_dev := private.gateway_pick_device(v_acct);
  if v_dev.id is null then
    update private.gateway_accounts set failed_count = failed_count + 1 where id = v_acct.id;
    update public.gateway_api_log
      set ok = false, detail = detail || jsonb_build_object('error', 'no gateway device online — open the OpenCall Gateway app on the phone')
      where id = (select id from public.gateway_api_log
                  where account_id = v_acct.id and action = 'send_sms'
                  order by id desc limit 1);
    raise exception 'no gateway device online — the phone must be running the OpenCall Gateway app (open it once to start the gateway service)';
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

-- ---------- 2) heartbeat: reap stale sends on every phone heartbeat too ----------
create or replace function public.gateway_heartbeat(
  p_device_id uuid, p_secret text, p_battery int default null,
  p_app_version text default null, p_sim_number text default null, p_setup jsonb default null
)
returns jsonb
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  v_dev public.gateway_devices;
begin
  select * into v_dev from public.gateway_devices
  where id = p_device_id and secret_hash = private.sha256_hex(coalesce(trim(p_secret), ''));
  if v_dev.id is null then raise exception 'device not paired'; end if;

  update public.gateway_devices
    set online = true, battery = p_battery, app_version = p_app_version,
        sim_number = coalesce(nullif(trim(p_sim_number), ''), sim_number),
        setup = coalesce(p_setup, setup),
        last_seen_at = now()
  where id = p_device_id;

  perform private.gateway_reap_stale();

  return jsonb_build_object('ok', true, 'serverTime', to_char(now(), 'YYYY-MM-DD"T"HH24:MI:SS"Z"'));
end;
$$;

-- ---------- 3) fetch_commands: reap on every poll as well ----------
-- (returns jsonb like the original — the phone's Commands.parse handles
--  both array and object shapes, so the shape must not change)
create or replace function public.gateway_fetch_commands(p_device_id uuid, p_secret text, p_limit int default 5)
returns jsonb
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  v_rows jsonb;
begin
  -- auth
  if not exists (select 1 from public.gateway_devices
                 where id = p_device_id and secret_hash = private.sha256_hex(coalesce(trim(p_secret), ''))) then
    raise exception 'device not paired';
  end if;
  perform private.gateway_reap_stale();
  with claimed as (
    update public.gateway_commands c
      set status = 'sent', claimed_at = now()
    where c.id in (
      select c2.id from public.gateway_commands c2
      where c2.device_id = p_device_id and c2.status = 'pending'
      order by c2.created_at limit greatest(1, least(coalesce(p_limit, 5), 20))
    )
    returning *
  )
  select coalesce(jsonb_agg(to_jsonb(x) order by x.created_at), '[]'::jsonb)
    into v_rows
  from claimed x;
  return v_rows;
end;
$$;
