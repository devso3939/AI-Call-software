-- ============================================================
-- Migration 6c: gateway signaling + answer/end commands + realtime
--
-- WebRTC audio path (no-root acoustic bridge):
--   browser  ⇄ (WebRTC, durable calls_events) ⇄ Android gateway phone
--              phone ⇄ cellular network via SPEAKER + mic + HW AEC
-- The phone polls signals as a device (secret-auth), the browser reads
-- them as the owner participant. Gateway posts with the zero-uuid sender
-- so the browser never mistakes them for its own echo.
-- ============================================================

-- answer_call joins the command kinds (inbound cellular call: owner taps Accept in the web app)
alter table public.gateway_commands drop constraint if exists gateway_commands_kind_check;
alter table public.gateway_commands
  add constraint gateway_commands_kind_check
  check (kind in ('send_sms','dial_call','end_call','answer_call','ping'));

-- gateway posts a signaling event for a call its owner participates in
create or replace function public.gateway_post_signal(p_device_id uuid, p_secret text, p_call_id uuid, p_kind text, p_payload jsonb)
returns int
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  v_dev public.gateway_devices;
  v_seq int;
begin
  select * into v_dev from private.device_for_key(p_device_id, p_secret);
  if v_dev.id is null then raise exception 'bad device credentials'; end if;
  if not exists (
    select 1 from public.calls c where c.id = p_call_id
      and (c.created_by = v_dev.user_id or c.callee_id = v_dev.user_id)
  ) then raise exception 'call not found for this device'; end if;
  if p_kind not in ('offer','answer','ice','bye') then raise exception 'invalid event kind'; end if;

  update public.gateway_devices set last_seen_at = now(), online = true where id = v_dev.id;
  select coalesce(max(seq), 0) + 1 into v_seq from public.calls_events where call_id = p_call_id;
  insert into public.calls_events (call_id, seq, sender, kind, payload)
  values (p_call_id, v_seq, '00000000-0000-0000-0000-000000000000'::uuid, p_kind, p_payload);
  return v_seq;
end;
$$;
grant execute on function public.gateway_post_signal(uuid, text, uuid, text, jsonb) to anon, authenticated;

-- gateway reads the browser's signaling events (offer/answer/ice/bye)
create or replace function public.gateway_get_signals(p_device_id uuid, p_secret text, p_call_id uuid, p_after_seq int default 0)
returns table (seq int, kind text, payload jsonb)
language sql
security definer
set search_path = public, pg_temp
as $$
  select e.seq, e.kind, e.payload
  from public.calls_events e
  where e.call_id = p_call_id and e.seq > p_after_seq
    and exists (
      select 1 from public.calls c where c.id = p_call_id
        and (c.created_by = (select user_id from public.gateway_devices where id = p_device_id)
             or c.callee_id = (select user_id from public.gateway_devices where id = p_device_id))
    )
    and e.sender <> '00000000-0000-0000-0000-000000000000'::uuid   -- skip gateway's own posts
  order by e.seq;
$$;
grant execute on function public.gateway_get_signals(uuid, text, uuid, int) to anon, authenticated;

-- web side: tell my phone to answer the currently-ringing inbound cellular call
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
  select * into v_dev from public.gateway_devices where user_id = v_uid and online = true
    order by last_seen_at desc nulls last limit 1;
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

-- web side: tell my phone to end/stop the cellular call (or abort dialing)
create or replace function public.queue_end_call(p_call_id uuid)
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
  select * into v_dev from public.gateway_devices where user_id = v_uid and online = true
    order by last_seen_at desc nulls last limit 1;
  if v_dev.id is null then raise exception 'no gateway device online'; end if;
  if not exists (select 1 from public.calls where id = p_call_id and (created_by = v_uid or callee_id = v_uid)) then
    raise exception 'call not found';
  end if;
  insert into public.gateway_commands (device_id, kind, payload, requested_by)
    values (v_dev.id, 'end_call', jsonb_build_object('callId', p_call_id), v_uid);
end;
$$;
grant execute on function public.queue_end_call(uuid) to authenticated;

-- web side: unpair a device
create or replace function public.remove_gateway_device(p_device_id uuid)
returns void
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare v_uid uuid := auth.uid();
begin
  if v_uid is null then raise exception 'sign in first'; end if;
  delete from public.gateway_devices where id = p_device_id and user_id = v_uid;
end;
$$;
grant execute on function public.remove_gateway_device(uuid) to authenticated;

-- realtime for SMS + device presence (idempotent)
do $$ begin
  alter publication supabase_realtime add table public.sms_messages;
exception when duplicate_object then null; end $$;
do $$ begin
  alter publication supabase_realtime add table public.gateway_devices;
exception when duplicate_object then null; end $$;

-- sweep guard: fresh inbound-pstn rows (reported by the phone) must not be
-- reaped by the generic 45 s ring timeout before the owner can answer.
-- started_at is null for rows that never left the inbound banner, so the
-- original condition (started_at is null) covers the sweep's intent.
create or replace function public.sweep_no_answer(p_older_than_seconds int default 45)
returns int
language plpgsql
security definer
set search_path = public
as $$
declare
  v_count int := 0;
begin
  with stale_ringing as (
    update public.calls
    set status = 'no_answer', ended_at = now(), termination_reason = 'ring_timeout'
    where status = 'ringing' and started_at is null
      and created_at < now() - make_interval(secs => p_older_than_seconds)
    returning 1
  ), stale_answered as (
    update public.calls
    set status = 'failed', ended_at = now(), termination_reason = 'stale_sweep'
    where status in ('answered','active')
      and coalesce(answered_at, created_at) < now() - interval '2 hours'
    returning 1
  )
  select (select count(*) from stale_ringing) + (select count(*) from stale_answered) into v_count;
  return v_count;
end;
$$;
grant execute on function public.sweep_no_answer(int) to authenticated;
