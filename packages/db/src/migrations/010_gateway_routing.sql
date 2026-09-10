-- ============================================================
-- Migration 10: capability-aware device routing
--
-- Problem: with both flavors paired (Bridge lite + Gateway full),
-- request_gateway_call / send_sms picked "most recently seen" — a coin
-- flip between a device that CAN dial automatically (Gateway, full
-- permissions) and one that can only hand off via a notification tap
-- (Bridge, zero sensitive permissions).
--
-- Fix: prefer non-lite devices (app_version without the '-lite' suffix)
-- when at least one is online; fall back to Bridge only if it's the
-- only device online. Devices that never reported app_version are
-- presumed full gateways (pre-1.5 builds had no suffix).
-- ============================================================

-- outbound PSTN call routing
create or replace function public.request_gateway_call(p_to text)
returns public.calls
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  v_uid uuid := auth.uid();
  v_dev public.gateway_devices;
  v_call public.calls;
  v_room text;
begin
  if v_uid is null then raise exception 'sign in first'; end if;
  if p_to !~ '^\+[1-9][0-9]{5,15}$' then raise exception 'destination must be E.164 (+country...)'; end if;
  -- capability first: non-lite (automatic gateway) beats lite (tap handoff);
  -- freshness breaks ties within the same capability class
  select * into v_dev from public.gateway_devices
    where user_id = v_uid and online = true
    order by (app_version like '%-lite') asc, last_seen_at desc nulls last
    limit 1;
  if v_dev.id is null then raise exception 'no gateway device online — pair your phone in the Devices tab first'; end if;

  insert into public.calls (created_by, callee_id, requested_destination, normalized_destination,
                            route_type, mode, status, caller_kind, pstn_to, direction)
  values (v_uid, v_uid, p_to, p_to, 'PSTN', 'human', 'created', 'pstn', p_to, 'outbound-pstn')
  returning id into v_call.id;

  v_room := 'call-' || v_call.id::text;
  update public.calls set room = v_room where id = v_call.id returning * into v_call;

  insert into public.gateway_commands (device_id, kind, payload, requested_by)
    values (v_dev.id, 'dial_call', jsonb_build_object('callId', v_call.id, 'to', p_to, 'room', v_room, 'answerAfter', 2000), v_uid);

  perform pg_notify('opencall_events', json_build_object('type','gateway.dial','toUser', v_uid,
    'payload', json_build_object('callId', v_call.id, 'to', p_to, 'room', v_room))::text);
  return v_call;
end;
$$;

-- outbound SMS routing
create or replace function public.send_sms(p_to text, p_body text, p_device_id uuid default null)
returns public.sms_messages
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  v_uid uuid := auth.uid();
  v_sms public.sms_messages;
  v_dev public.gateway_devices;
begin
  if v_uid is null then raise exception 'sign in first'; end if;
  if p_to !~ '^\+[1-9][0-9]{3,15}$' then raise exception 'destination must be E.164 (+country...)'; end if;
  if length(p_body) = 0 or length(p_body) > 1600 then raise exception 'body must be 1..1600 chars'; end if;

  -- same capability-first rule as calls: explicit device choice still wins
  select * into v_dev from public.gateway_devices
    where user_id = v_uid and (p_device_id is null or id = p_device_id) and online = true
    order by (app_version like '%-lite') asc, last_seen_at desc nulls last
    limit 1;
  if v_dev.id is null then raise exception 'no gateway device online — pair your phone in the Devices tab first'; end if;

  insert into public.sms_messages (user_id, device_id, direction, to_number, body, status)
    values (v_uid, v_dev.id, 'outbound', p_to, p_body, 'queued')
    returning * into v_sms;

  insert into public.gateway_commands (device_id, kind, payload, requested_by)
    values (v_dev.id, 'send_sms', jsonb_build_object('smsId', v_sms.id, 'to', p_to, 'body', p_body), v_uid);

  return v_sms;
end;
$$;

-- keep the inbound answer path consistent (owner taps Accept in the web app)
create or replace function public.queue_answer_call(p_call_id uuid)
returns void
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  v_uid uuid := auth.uid();
  v_dev public.gateway_devices;
begin
  if v_uid is null then raise exception 'sign in first'; end if;
  select * into v_dev from public.gateway_devices
    where user_id = v_uid and online = true
    order by (app_version like '%-lite') asc, last_seen_at desc nulls last
    limit 1;
  if v_dev.id is null then raise exception 'no gateway device online'; end if;
  if not exists (select 1 from public.calls where id = p_call_id and callee_id = v_uid
                   and status = 'ringing' and direction = 'inbound-pstn') then
    raise exception 'no ringing call to answer';
  end if;
  insert into public.gateway_commands (device_id, kind, payload, requested_by)
    values (v_dev.id, 'answer_call', jsonb_build_object('callId', p_call_id), v_uid);
end;
$$;
grant execute on function public.queue_answer_call(uuid) to authenticated;
