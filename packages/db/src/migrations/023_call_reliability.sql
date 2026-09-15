-- 023: Make background calls as reliable as background SMS.
--
-- Context: outbound calls already run fully in the background —
-- web -> request_gateway_call -> gateway_commands(kind='dial_call')
--        -> phone's GatewayService polls and dials via TelecomManager (no app UI).
-- Gaps found by live-DB diagnosis:
--   1. gateway_reap_stale only failed stale 'send_sms' commands; stale
--      'dial_call' commands sat pending forever when the phone was offline.
--   2. A call row could stay 'ringing' forever if the phone died mid-ring
--      (seen live: call 08545156 stuck ringing since Sep 12).
-- Fix: reap ALL command kinds against non-fresh devices, and auto-fail
-- calls stuck in 'ringing' with no command activity.

create or replace function private.gateway_reap_stale()
returns void
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  v_ids uuid[];
begin
  -- ALL command kinds (send_sms, dial_call, end_call, answer_call, ...):
  -- pending > 10 min on a non-fresh device => the phone never picked it up.
  select coalesce(array_agg(c.id), '{}'::uuid[]) into v_ids
  from public.gateway_commands c
  join public.gateway_devices d on d.id = c.device_id
  where c.status = 'pending'
    and c.created_at < now() - interval '10 minutes'
    and not private.device_fresh(d.online, d.last_seen_at);

  if array_length(v_ids, 1) > 0 then
    update public.gateway_commands
      set status = 'failed',
          result = jsonb_build_object(
            'error',
            case
              when kind = 'send_sms' then 'phone offline — gateway app was not running; message never sent'
              when kind = 'dial_call' then 'phone offline — gateway app was not running; call never dialed'
              else 'phone offline — gateway app was not running; command never executed'
            end)
      where id = any(v_ids);

    -- SMS rows: mirror the failure
    update public.sms_messages m
      set status = 'failed',
          error = 'phone offline — gateway app was not running; message never sent',
          updated_at = now()
      where m.status = 'queued'
        and m.device_id in (select c.device_id from public.gateway_commands c where c.id = any(v_ids))
        and m.created_at < now() - interval '10 minutes';

    -- Call rows: fail calls whose dial command died while never picked up
    update public.calls
      set status = 'failed',
          termination_reason = 'phone offline — gateway app was not running; call never dialed',
          ended_at = now()
      where status in ('created', 'ringing')
        and id in (
          select (c.payload->>'callId')::uuid
          from public.gateway_commands c
          where c.id = any(v_ids) and c.kind = 'dial_call'
            and c.payload ? 'callId');
  end if;

  -- Safety net: a call stuck in 'ringing' for > 3 minutes with no live
  -- device (phone died mid-ring) => it will never be answered.
  update public.calls
    set status = 'failed',
        termination_reason = 'ringing timed out — phone stopped responding mid-ring',
        ended_at = now()
    where status = 'ringing'
      and created_at < now() - interval '3 minutes'
      and callee_id in (
        select user_id from public.gateway_devices
        where not private.device_fresh(online, last_seen_at));
end;
$$;

-- Data repair: fail the call stuck ringing since Sep 12.
update public.calls
  set status = 'failed',
      termination_reason = 'ringing timed out — phone stopped responding mid-ring',
      ended_at = now()
  where id = '08545156-4605-46e3-b9d5-e03484d0b74c'
    and status = 'ringing';

-- Keep stale pending commands from before this fix from lingering.
update public.gateway_commands c
  set status = 'failed',
      result = jsonb_build_object('error', 'phone offline — gateway app was not running; command never executed')
  where c.status = 'pending'
    and c.created_at < now() - interval '10 minutes'
    and not exists (
      select 1 from public.gateway_devices d
      where d.id = c.device_id and private.device_fresh(d.online, d.last_seen_at));
