-- ============================================================
-- Migration 6b: WebRTC bridge handoff for the SIM gateway
--
-- The Android gateway bridges call audio acoustically: it places the
-- cellular call on SPEAKER and joins the SAME WebRTC room as the browser
-- user (mic + speaker + hardware AEC). These additions:
--   1. join_gateway_room(p_device_id, p_secret, p_code)   — anon OK; the
--      device proves ownership with its secret, or pairs live with a
--      6-digit code shown on screen (phone has no browser session).
--   2. dial payload now carries the room so the phone can join it.
--   3. report_incoming_call now returns the room id so the phone can open
--      a listening bridge (owner can join from the web app).
-- ============================================================

-- room column on calls (idempotent)
alter table public.calls add column if not exists room text;
create index if not exists calls_room_idx on public.calls (room);

-- device joins a signaling room as a participant (anon-capable, secret/code proven)
create or replace function public.join_gateway_room(p_device_id uuid, p_secret text, p_code text default null, p_room text default null)
returns jsonb
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  v_dev public.gateway_devices;
  v_uid uuid;
  v_room text;
begin
  select * into v_dev from private.device_for_key(p_device_id, p_secret);
  if v_dev.id is null then raise exception 'bad device credentials'; end if;
  v_uid := v_dev.user_id;

  if v_dev.user_id is null and p_code is not null then
    -- live-pairing path (device stored without a user yet): claim by code
    if p_code !~ '^[0-9]{6}$' then raise exception 'pairing code must be 6 digits'; end if;
    select user_id into v_uid from public.gateway_pairing_codes
      where used = false and expires_at > now() and code = p_code limit 1;
    if v_uid is null then raise exception 'invalid or expired pairing code'; end if;
    update public.gateway_pairing_codes set used = true where code = p_code and used = false;
    update public.gateway_devices set user_id = v_uid where id = v_dev.id;
  elsif v_dev.user_id is null then
    raise exception 'device not paired — provide the 6-digit pairing code';
  end if;

  if p_room is null or length(p_room) = 0 then
    -- latest room for the owner's active PSTN call, else a fresh room
    select room into v_room from public.calls
      where created_by = v_uid and room is not null and status in ('created','ringing','answered')
      order by created_at desc limit 1;
    if v_room is null then v_room := 'gw-' || encode(extensions.gen_random_bytes(8),'hex'); end if;
  else
    v_room := left(p_room, 120);
  end if;

  update public.gateway_devices set last_seen_at = now(), online = true where id = v_dev.id;
  return jsonb_build_object('room', v_room, 'userId', v_uid, 'role', 'gateway');
end;
$$;
grant execute on function public.join_gateway_room(uuid, text, text, text) to anon, authenticated;

-- dial payload carries the room so the phone can join the same WebRTC room
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
  select * into v_dev from public.gateway_devices where user_id = v_uid and online = true order by last_seen_at desc nulls last limit 1;
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

-- inbound call returns the room so the phone can open a listening bridge
-- (return type changed uuid → jsonb; drop old signature first)
drop function if exists public.report_incoming_call(uuid, text, text);
create or replace function public.report_incoming_call(p_device_id uuid, p_secret text, p_from text)
returns jsonb
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  v_dev public.gateway_devices;
  v_call public.calls;
  v_room text;
begin
  select * into v_dev from private.device_for_key(p_device_id, p_secret);
  if v_dev.id is null then raise exception 'bad device credentials'; end if;
  insert into public.calls (created_by, callee_id, requested_destination, normalized_destination, route_type, mode, status, caller_kind, pstn_to, direction)
  values (v_dev.user_id, v_dev.user_id, p_from, p_from, 'PSTN', 'human', 'ringing', 'pstn', p_from, 'inbound-pstn')
  returning * into v_call;
  v_room := 'call-' || v_call.id::text;
  update public.calls set room = v_room where id = v_call.id;
  perform pg_notify('opencall_events', json_build_object('type','gateway.incoming','toUser', v_dev.user_id,
    'payload', json_build_object('callId', v_call.id, 'from', p_from, 'room', v_room))::text);
  return jsonb_build_object('callId', v_call.id, 'room', v_room);
end;
$$;
grant execute on function public.report_incoming_call(uuid, text, text) to anon;
