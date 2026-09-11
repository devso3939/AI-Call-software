-- ============================================================
-- Migration 13: phone setup-state reporting (silent-dialer readiness)
--
-- Problem 1 (v1.5.13): the user still saw the stock dialer UI on the
-- phone during gateway calls. Root cause: the silent-dialer machinery
-- needs optional grants ("Display over other apps" overlay and/or the
-- default-dialer role) that were OPTIONAL setup cards in the Android
-- app — easy to skip, and the web app had no way to know they were
-- missing.
--
-- Fix, phone side: the Android app now reports its setup state with
-- every heartbeat as p_setup jsonb:
--   {"flavor":"gateway"|"bridge","overlay":bool,"dialer":bool,
--    "manageCalls":bool,"agentMode":bool}
-- (managed → p_setup only on fail; all other users → on every beat)
--
-- Fix, web side: app.html renders a warning banner from setup_state
-- when overlay/dialer are missing, and recommends the full APK when
-- flavor = "bridge".
-- ============================================================

alter table public.gateway_devices add column if not exists setup_state jsonb;

-- heartbeat: accept p_setup and persist it (fail-soft on legacy clients
-- that still omit it)
create or replace function public.gateway_heartbeat(p_device_id uuid, p_secret text, p_sim_number text default null, p_battery int default null, p_app_version text default null, p_setup jsonb default null)
returns void
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare v_dev public.gateway_devices;
begin
  select * into v_dev from private.device_for_key(p_device_id, p_secret);
  if v_dev.id is null then raise exception 'bad device credentials'; end if;
  update public.gateway_devices
    set last_seen_at = now(), online = true,
        sim_number = coalesce(p_sim_number, sim_number),
        battery = coalesce(p_battery, battery),
        app_version = coalesce(p_app_version, app_version),
        setup_state = coalesce(p_setup, setup_state)
    where id = v_dev.id;
end;
$$;

-- keep grants aligned with the widened signature
grant execute on function public.gateway_heartbeat(uuid, text, text, int, text, jsonb) to anon;
