-- =====================================================================
-- Migration 007 — regression fixes from the full-stack regression pass
--
--  R1: sms_messages + gateway_devices had RLS policies but NO table
--      grants for anon/authenticated → PostgREST returned 403.
--      Symptoms: SMS tab empty (select 403), realtime SMS + device
--      online/offline presence silently dead.
--      Fix: grant SELECT (RLS policies already restrict to owner rows).
--
--  R2: place_call on-net routing failed for '<user>@opencall.app'
--      destinations. profiles store '<user>@opencall' (internet_identity)
--      and '<user>' (username); the old lookup compared
--      internet_identity = 'bob@opencall.app' (no match) and
--      username = replace(dest,'@opencall','') = 'bob.app' (no match).
--      Fix: tolerant lookup accepting all three forms.
-- =====================================================================

-- ---------- R1: table grants ----------
grant select on public.sms_messages    to anon, authenticated;
grant select on public.gateway_devices to anon, authenticated;

-- ---------- R2: place_call tolerant on-net routing ----------
create or replace function public.place_call(p_destination text, p_mode text default 'human')
returns public.calls
language plpgsql
security definer
set search_path = public
as $fn$
declare
  v_caller public.profiles;
  v_callee public.profiles;
  v_call   public.calls;
  v_dest   text := trim(p_destination);
begin
  select * into v_caller from public.profiles where id = auth.uid();
  if v_caller.id is null then
    raise exception 'caller profile not found';
  end if;

  -- ---------- PSTN branch: +[1-9] followed by 5..15 digits ----------
  if v_dest ~ '^\+[1-9][0-9]{5,15}$' then
    if not exists (select 1 from private.twilio_config where id = 1) then
      raise exception 'PSTN is not configured yet — phone dialing is disabled in this deployment';
    end if;

    insert into public.calls (created_by, callee_id, requested_destination, normalized_destination,
                              route_type, mode, status, caller_kind, pstn_to, direction)
    values (v_caller.id, v_caller.id, v_dest, v_dest, 'PSTN', p_mode, 'created', 'pstn', v_dest, 'outbound-pstn')
    returning * into v_call;

    perform pg_notify('opencall_events', json_build_object(
      'type', 'call.created', 'callId', v_call.id, 'toUser', v_caller.id,
      'payload', json_build_object('callId', v_call.id, 'kind', 'pstn', 'to', v_dest))::text);

    return v_call;
  end if;

  -- ---------- ON-NET branch ----------
  -- Destination may be '<user>@opencall.app', '<user>@opencall' or plain '<user>'.
  select * into v_callee from public.profiles where lower(internet_identity) = lower(v_dest);
  if v_callee.id is null then
    select * into v_callee from public.profiles
    where lower(username) = lower(regexp_replace(v_dest, '@opencall(\.app)?$', ''));
  end if;
  if v_callee.id is null then
    raise exception 'no route: % is not an OpenCall user. Use +<country><number> for phone calls', v_dest;
  end if;
  if v_callee.id = v_caller.id then
    raise exception 'cannot call yourself';
  end if;

  if exists (
    select 1 from public.calls c
    where c.status in ('created','ringing')
      and ((c.created_by = v_caller.id and c.callee_id = v_callee.id)
        or (c.created_by = v_callee.id and c.callee_id = v_caller.id))
  ) then
    raise exception 'a call is already ringing between these users';
  end if;

  insert into public.calls (created_by, callee_id, requested_destination, normalized_destination, route_type, mode, status)
  values (v_caller.id, v_callee.id, v_dest, v_callee.internet_identity, 'ON_NET', p_mode, 'ringing')
  returning * into v_call;

  perform pg_notify('opencall_events', json_build_object(
    'type', 'call.created',
    'callId', v_call.id,
    'toUser', v_callee.id,
    'payload', json_build_object(
      'callId', v_call.id,
      'from', json_build_object('id', v_caller.id, 'displayName', v_caller.display_name, 'identity', v_caller.internet_identity),
      'mode', p_mode
    )
  )::text);

  return v_call;
end;
$fn$;
