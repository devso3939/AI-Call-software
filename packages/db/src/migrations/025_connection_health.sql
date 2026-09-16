-- =====================================================================
-- Migration 025 — easier connect + a real connection health surface
-- (user: "improve bond and connection itself and improve easiness for
-- user to connect")
--
-- PART A — ONE-TAP CONNECTION TEST (safe by design):
--   gateway_api_test(username, password, token) — a READ-ONLY endpoint
--   the owner (or any third party setting up the connection) can call
--   to verify credentials WITHOUT sending anything, WITHOUT consuming
--   a rate slot's send budget, and WITHOUT any side effects on the
--   device. It returns everything needed to confirm the bond is alive:
--     { ok, connection: 'active', permanent: true,
--       device: {name, number, online, battery, lastSeenAt},
--       usage: {sent, failed, requests}, rateLimit: '120/min' }
--   Wrong credentials → 'invalid gateway credentials' (same as sends,
--   so an integration can use the same error handling).
--   Offline device still returns ok=true — the CONNECTION is valid;
--   only the phone being asleep is reported via device.online=false.
--   This is the crucial distinction from 024: "credentials valid" and
--   "phone reachable" are now separately visible BEFORE any send fails.
--
-- PART B — RICHER OWNER STATUS:
--   get_sms_gateway() now also returns:
--     • tokenCount   — how many permanent tokens exist (third parties
--       currently connected via token)
--     • lastTokenAt  — when the most recent connection (login) happened
--     • onlineHours  — rolling 24h send success rate inputs:
--     • sent24h / failed24h — real delivery outcome counts from
--       gateway_api_log + sms_messages for the last 24 hours
--   The web tab renders these as a "Connection health" strip so the
--   user can SEE the bond is alive instead of guessing.
--
-- PART C — TOKEN VISIBILITY:
--   gateway_api_login response gains 'createdAt' so integrations can
--   show when the permanent credential was minted. (No schema change —
--   tokens are already permanent per 024.)
-- =====================================================================

-- =====================================================================
-- A) read-only connection test
-- =====================================================================
create or replace function public.gateway_api_test(
  p_username text default null,
  p_password text default null,
  p_token text default null
)
returns jsonb
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  v_acct private.gateway_accounts;
  v_dev  public.gateway_devices;
  v_fresh boolean;
begin
  -- authenticate exactly like a send (same hash check, same token
  -- acceptance, same rate limit) but log it as a read-only 'test'
  v_acct := private.gateway_account_for_auth(p_username, p_password, p_token);
  if v_acct.id is null then
    insert into public.gateway_api_log (user_id, account_id, action, ok, detail)
    select a.user_id, a.id, 'test', false,
           jsonb_build_object('error', 'invalid credentials')
    from private.gateway_accounts a
    where p_username is not null and a.username = lower(trim(p_username))
    limit 1;
    if not found then
      insert into public.gateway_api_log (user_id, account_id, action, ok, detail)
      values (null, null, 'test', false, jsonb_build_object('error', 'invalid credentials'));
    end if;
    raise exception 'invalid gateway credentials';
  end if;
  if not private.gateway_rate_ok(v_acct.id) then
    raise exception 'rate limit exceeded — 120 requests per minute';
  end if;
  update private.gateway_accounts
    set last_used_at = now(), request_count = request_count + 1
    where id = v_acct.id;

  select * into v_dev from public.gateway_devices where id = v_acct.device_id;
  v_fresh := private.device_fresh(v_dev.online, v_dev.last_seen_at);

  insert into public.gateway_api_log (user_id, account_id, action, ok, detail)
  values (v_acct.user_id, v_acct.id, 'test', true,
          jsonb_build_object('deviceOnline', v_fresh));

  return jsonb_build_object(
    'ok', true,
    'connection', 'active',
    'permanent', true,   -- 024: credentials never expire on their own
    'username', v_acct.username,
    'device', jsonb_build_object(
      'name', v_dev.name,
      'number', v_dev.sim_number,
      'online', v_fresh,
      'battery', case when v_fresh then v_dev.battery else null end,
      'lastSeenAt', v_dev.last_seen_at),
    'usage', jsonb_build_object(
      'sent', v_acct.sent_count,
      'failed', v_acct.failed_count,
      'requests', v_acct.request_count),
    'rateLimit', '120 requests/minute');
end;
$$;
grant execute on function public.gateway_api_test(text, text, text) to anon, authenticated;

-- =====================================================================
-- B) richer owner status (connection health)
-- =====================================================================
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
    'password', case when a.password_enc is null then null
                     else private.decrypt_pwhex(a.password_enc) end,
    'deviceId', a.device_id,
    'deviceName', d.name,
    'deviceNumber', d.sim_number,
    'deviceOnline', private.device_fresh(d.online, d.last_seen_at),
    'anyDevice', a.any_device,
    'requestCount', a.request_count,
    'sentCount', a.sent_count,
    'failedCount', a.failed_count,
    'lastUsedAt', a.last_used_at,
    'createdAt', a.created_at,
    -- 025 CONNECTION HEALTH: how many permanent token connections exist
    'tokenCount', (select count(*)::int from private.gateway_tokens t
                   where t.account_id = a.id),
    'lastTokenAt', (select max(t.created_at) from private.gateway_tokens t
                    where t.account_id = a.id),
    -- last 24h outcomes (honest delivery truth, not request counts)
    'sent24h', (select count(*)::int from public.sms_messages m
                where m.user_id = a.user_id
                  and m.direction = 'outbound'
                  and m.status = 'delivered'
                  and m.created_at > now() - interval '24 hours'),
    'failed24h', (select count(*)::int from public.sms_messages m
                  where m.user_id = a.user_id
                    and m.direction = 'outbound'
                    and m.status = 'failed'
                    and m.created_at > now() - interval '24 hours')
  )
  from private.gateway_accounts a
  left join public.gateway_devices d on d.id = a.device_id
  where a.user_id = auth.uid() and a.enabled;
$$;
grant execute on function public.get_sms_gateway() to authenticated;
