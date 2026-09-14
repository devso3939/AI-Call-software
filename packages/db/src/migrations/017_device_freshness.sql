-- =====================================================================
-- Migration 017 — honest device freshness across every gateway surface.
--
-- Problem: the `online` flag on gateway_devices only flips false on a
-- clean unregister. If the app is killed / phone is off, the row stays
-- online=true FOREVER, and gateway_api_status + get_sms_gateway then
-- report "online" with a battery value that may be hours or days old.
-- The web app already computes freshness client-side (deviceFresh,
-- 2-minute cutoff) — this migration moves that truth into the SERVER
-- so every consumer (web tab, phone app, third-party services like
-- SmartBookly) sees the same, honest status.
--
-- Rules (same as the web app's deviceFresh):
--   • a device is ONLINE  ⇔ online flag AND last_seen_at ≤ 2 min old
--   • battery is reported only when the heartbeat is fresh — stale
--     battery would be a lie, so it becomes null with lastSeenAt shown
--   • sends only route to devices with a FRESH heartbeat
-- =====================================================================

-- ---------- helper: freshness predicate (single source of truth) ----------
create or replace function private.device_fresh(p_online boolean, p_last_seen timestamptz)
returns boolean
language sql
immutable
as $$
  select coalesce(p_online, false)
     and p_last_seen is not null
     and p_last_seen > now() - interval '2 minutes';
$$;

-- ---------- 1) gateway_pick_device: only route sends to FRESH devices ----------
create or replace function private.gateway_pick_device(p_acct private.gateway_accounts)
returns public.gateway_devices
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  v_dev public.gateway_devices;
begin
  -- bound device first (even if another is fresher) — but it MUST have
  -- a fresh heartbeat; a device that stopped heartbeating cannot send.
  if p_acct.device_id is not null then
    select * into v_dev from public.gateway_devices
    where id = p_acct.device_id
      and private.device_fresh(online, last_seen_at);
    if v_dev.id is not null then return v_dev; end if;
  end if;
  -- loose fallback: any FRESH online device of the owner (newest first)
  if p_acct.any_device then
    select * into v_dev from public.gateway_devices
    where user_id = p_acct.user_id
      and private.device_fresh(online, last_seen_at)
    order by last_seen_at desc nulls last limit 1;
  end if;
  return v_dev;
end;
$$;

-- ---------- 2) gateway_api_status: honest online + battery freshness ----------
create or replace function public.gateway_api_status(p_username text default null, p_password text default null, p_token text default null)
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
  if p_token is null and p_password is null and p_username is not null
     and p_username ~ '^ogt_[0-9a-f]{48}$' then
    p_token := p_username; p_username := null;
  end if;
  v_acct := private.gateway_api_preamble(p_username, p_password, p_token, 'status');
  v_dev := private.gateway_pick_device(v_acct);
  v_fresh := v_dev.id is not null; -- pick_device already applies freshness
  return jsonb_build_object(
    'service', 'opencall-sms-gateway',
    'username', v_acct.username,
    'enabled', v_acct.enabled,
    'device', case when v_dev.id is null then null else jsonb_build_object(
      'name', v_dev.name, 'number', v_dev.sim_number,
      -- FRESHNESS FIX (017): battery only when the heartbeat is fresh —
      -- a 2-day-old "23%" presented as live is worse than no value.
      'battery', case when private.device_fresh(v_dev.online, v_dev.last_seen_at)
                      then v_dev.battery else null end,
      'online', v_fresh,
      'lastSeenAt', v_dev.last_seen_at) end,
    'sms', jsonb_build_object('enabled', v_fresh, 'maxBodyChars', 1600),
    'stats', jsonb_build_object('requests', v_acct.request_count, 'sent', v_acct.sent_count,
                                'failed', v_acct.failed_count, 'lastUsedAt', v_acct.last_used_at),
    'rateLimit', jsonb_build_object('requests', 120, 'windowSeconds', 60)
  );
end;
$$;
grant execute on function public.gateway_api_status(text, text, text) to anon, authenticated;

-- ---------- 3) get_sms_gateway: web tab sees the same truth ----------
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
    'deviceId', a.device_id,
    'deviceName', d.name,
    'deviceNumber', d.sim_number,
    -- FRESHNESS FIX (017): same 2-minute rule as the API and the web
    -- Devices tab — one truth everywhere.
    'deviceOnline', private.device_fresh(d.online, d.last_seen_at),
    'deviceBattery', case when private.device_fresh(d.online, d.last_seen_at)
                          then d.battery else null end,
    'deviceLastSeenAt', d.last_seen_at,
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

-- ---------- 4) list_gateway_devices: devices tab + gateway card agree ----------
create or replace function public.list_gateway_devices()
returns setof public.gateway_devices
language sql
security definer
set search_path = public, pg_temp
as $$
  select d.*
  from public.gateway_devices d
  where d.user_id = auth.uid()
  order by d.last_seen_at desc nulls last;
$$;

-- ---------- 5) gateway_status_from_device: phone app sees the same truth ----------
create or replace function public.gateway_status_from_device(
  p_device_id     uuid,
  p_device_secret text
)
returns jsonb
language sql
security definer
set search_path = public, pg_temp
as $$
  select jsonb_build_object(
    'enabled', (a.id is not null),
    'username', a.username,
    'deviceId', a.device_id,
    'deviceName', d.name,
    -- FRESHNESS FIX (017): same 2-minute rule on every surface
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
