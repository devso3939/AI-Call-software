-- ============================================================
-- Migration 11: web-app in-call controls for the gateway
--
-- 1.5.5: the Gateway app binds an InCallService (exact call state +
-- Call-object control). The web app can now drive mute / speaker /
-- hold on the live cellular call by queueing commands.
--
-- New command kinds:
--   mute_call / unmute_call / toggle_mute
--   speaker_on / speaker_off / toggle_speaker
--   hold_call  / resume_call
--   call_state_probe   (diagnostics — does the OS serve our InCallService?)
-- ============================================================

alter table public.gateway_commands drop constraint if exists gateway_commands_kind_check;
alter table public.gateway_commands
  add constraint gateway_commands_kind_check
  check (kind in (
    'send_sms','dial_call','end_call','answer_call','ping',
    'mute_call','unmute_call','toggle_mute',
    'speaker_on','speaker_off','toggle_speaker',
    'hold_call','resume_call',
    'call_state_probe'
  ));

-- Generic owner→device command queue. The web app uses this for every
-- in-call control; the device reports outcome via gateway_complete_command.
create or replace function public.queue_gateway_command(p_device_id uuid, p_kind text, p_payload jsonb default '{}'::jsonb)
returns uuid
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  v_uid uuid := auth.uid();
  v_dev public.gateway_devices;
  v_cmd uuid;
begin
  if v_uid is null then raise exception 'sign in first'; end if;
  if p_kind not in (
    'mute_call','unmute_call','toggle_mute',
    'speaker_on','speaker_off','toggle_speaker',
    'hold_call','resume_call',
    'call_state_probe'
  ) then raise exception 'unsupported command kind'; end if;

  select * into v_dev from public.gateway_devices
    where id = p_device_id and user_id = v_uid and online = true
    limit 1;
  if v_dev.id is null then raise exception 'gateway device not online'; end if;

  insert into public.gateway_commands (device_id, kind, payload, requested_by)
    values (v_dev.id, p_kind, coalesce(p_payload, '{}'::jsonb), v_uid)
    returning id into v_cmd;
  return v_cmd;
end;
$$;
grant execute on function public.queue_gateway_command(uuid, text, jsonb) to authenticated;
