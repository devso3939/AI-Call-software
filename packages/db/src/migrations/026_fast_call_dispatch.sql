-- 026_fast_call_dispatch.sql
-- v1.5.34 FIX: calls from the web app often timed out because the phone
-- polled for commands only every 20 s while idle — a dial command could sit
-- pending for up to 20 s, and combined with the browser's 45 s ring timeout
-- the phone's answer sometimes arrived AFTER the browser had hung up
-- (observed live on 2026-09-18: browser bye at 12:49:30, phone answer at
-- 12:49:34 — four seconds too late).
--
-- Fix: gateway_fetch_commands now ALSO returns a "waitHint" — the number of
-- seconds the phone should sleep before its next poll:
--   • commands found        → 0  (poll again immediately; drain the batch)
--   • no commands           → 4  (fast poll; cheap heartbeat keeps NAT alive
--                                  and makes worst-case dial latency ~5 s)
-- The 20 s battery-saving cadence is no longer worth it: a 4 s poll is one
-- tiny RPC every 4 s (~0.4 MB/hour) and it cuts call-setup latency from up
-- to 20 s down to under 5 s. The phone uses this hint instead of its own
-- fixed idle sleep.

create or replace function public.gateway_fetch_commands(p_device_id uuid, p_secret text, p_limit int default 5)
returns jsonb
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  v_rows jsonb;
  v_count int;
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

  v_count := coalesce(jsonb_array_length(v_rows), 0);
  -- Wrap the raw command array in an envelope the phone understands:
  -- {"cmds": [...], "waitHint": <seconds to sleep before next poll>}
  return jsonb_build_object(
    'cmds', v_rows,
    'waitHint', case when v_count > 0 then 0 else 4 end
  );
end;
$$;

grant execute on function public.gateway_fetch_commands(uuid, text, int) to anon, authenticated;
