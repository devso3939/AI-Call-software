-- =====================================================================
-- Migration 020 — THE root-cause fix: collapse gateway_heartbeat to ONE
-- canonical function.
--
-- Bug history: migrations 006 → 015 → 018 each created their own
-- overload of gateway_heartbeat with different parameter orders.
-- PostgREST resolves RPCs by NAMED arguments; with three overloads it
-- throws PGRST203 "Could not choose the best candidate function" for
-- EVERY heartbeat. Result: since 015 went live, no phone could ever
-- come online — no battery, "No gateway device online" everywhere,
-- re-paired devices stuck offline, SmartBookly sends undeliverable.
--
-- Fix: drop ALL overloads, create exactly ONE function whose parameter
-- names are the union every APK version sends (all callers use named
-- args, so order is irrelevant — only the COUNT of overloads matters).
-- =====================================================================

drop function if exists public.gateway_heartbeat(uuid, text, text, integer, text);
drop function if exists public.gateway_heartbeat(uuid, text, integer, text, text, jsonb);
drop function if exists public.gateway_heartbeat(uuid, text, text, integer, text, jsonb);

create or replace function public.gateway_heartbeat(
  p_device_id   uuid,
  p_secret      text,
  p_battery     int    default null,
  p_app_version text   default null,
  p_sim_number  text   default null,
  p_setup       jsonb  default null
)
returns jsonb
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  v_dev public.gateway_devices;
begin
  select * into v_dev from private.device_for_key(p_device_id, p_secret);
  if v_dev.id is null then raise exception 'bad device credentials'; end if;

  update public.gateway_devices
    set online       = true,
        last_seen_at = now(),
        battery      = coalesce(p_battery, battery),
        app_version  = coalesce(p_app_version, app_version),
        sim_number   = coalesce(nullif(trim(coalesce(p_sim_number, '')), ''), sim_number),
        setup        = coalesce(p_setup, setup)
    where id = v_dev.id;

  -- reap stale sends while we're here (same honesty rules as 018)
  perform private.gateway_reap_stale();

  return jsonb_build_object('ok', true,
    'serverTime', to_char(now(), 'YYYY-MM-DD"T"HH24:MI:SS"Z"'));
end;
$$;

grant execute on function public.gateway_heartbeat(uuid, text, int, text, text, jsonb) to anon, authenticated;
