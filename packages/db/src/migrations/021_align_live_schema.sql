-- =====================================================================
-- Migration 021 — align gateway RPCs with the REAL live schema.
--
-- Found by full-pipeline E2E: the live tables are
--   gateway_devices:  ... setup_state jsonb   (NOT "setup")
--   gateway_commands: ... updated_at timestamptz (NOT "claimed_at")
-- Migrations 018/020 wrote functions referencing "setup" and
-- "claimed_at" → every heartbeat AND command fetch failed with 42703
-- "column does not exist". This migration rewrites both functions to
-- match reality. (Single-overload rule from 020 is preserved.)
-- =====================================================================

-- ---------- heartbeat: use setup_state ----------
drop function if exists public.gateway_heartbeat(uuid, text, int, text, text, jsonb);

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
        setup_state  = coalesce(p_setup, setup_state)
    where id = v_dev.id;

  perform private.gateway_reap_stale();

  return jsonb_build_object('ok', true,
    'serverTime', to_char(now(), 'YYYY-MM-DD"T"HH24:MI:SS"Z"'));
end;
$$;

grant execute on function public.gateway_heartbeat(uuid, text, int, text, text, jsonb) to anon, authenticated;

-- ---------- fetch_commands: updated_at instead of claimed_at ----------
drop function if exists public.gateway_fetch_commands(uuid, text, int);

create or replace function public.gateway_fetch_commands(p_device_id uuid, p_secret text, p_limit int default 5)
returns jsonb
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  v_rows jsonb;
begin
  if not exists (select 1 from public.gateway_devices
                 where id = p_device_id
                   and secret_hash = private.sha256_hex(coalesce(trim(p_secret), ''))) then
    raise exception 'bad device credentials';
  end if;
  perform private.gateway_reap_stale();
  with claimed as (
    update public.gateway_commands c
      set status = 'sent', updated_at = now()
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

grant execute on function public.gateway_fetch_commands(uuid, text, int) to anon, authenticated;
