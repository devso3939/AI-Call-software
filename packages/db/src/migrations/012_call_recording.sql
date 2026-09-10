-- ============================================================
-- Migration 12: call recording (MP3) + DTMF command
--
-- 1.5.7:
--   • The web call panel can record both voices during a call,
--     encode to MP3 in the browser and upload it to Storage. The
--     call row stores the object path; History plays it via a
--     short-lived signed URL (bucket is PRIVATE).
--   • 'dtmf_call' joins the in-call command allowlist — the web
--     keypad drives the phone's InCallService (playDtmfTone).
-- ============================================================

-- ── 1. DTMF joins the gateway command allowlist ──
alter table public.gateway_commands drop constraint if exists gateway_commands_kind_check;
alter table public.gateway_commands
  add constraint gateway_commands_kind_check
  check (kind in (
    'send_sms','dial_call','end_call','answer_call','ping',
    'mute_call','unmute_call','toggle_mute',
    'speaker_on','speaker_off','toggle_speaker',
    'hold_call','resume_call','dtmf_call',
    'call_state_probe'
  ));

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
    'hold_call','resume_call','dtmf_call',
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

-- ── 2. calls.recording_url (storage object path, NOT a public URL) ──
alter table public.calls add column if not exists recording_url text;

-- ── 3. private storage bucket; access only via signed URLs ──
do $$ begin
  insert into storage.buckets (id, name, public)
  values ('call-recordings', 'call-recordings', false)
  on conflict (id) do update set public = false;
exception when others then
  raise notice 'bucket setup skipped: %', sqlerrm;
end $$;

-- upload: only a call participant, only into their call's folder
drop policy if exists "call-recording upload" on storage.objects;
create policy "call-recording upload" on storage.objects for insert to authenticated
with check (
  bucket_id = 'call-recordings'
  and exists (
    select 1 from public.calls c
    where (storage.foldername(name))[1] = c.id::text
      and (c.created_by = auth.uid() or c.callee_id = auth.uid())
  )
);

-- read: only a call participant
drop policy if exists "call-recording read" on storage.objects;
create policy "call-recording read" on storage.objects for select to authenticated
using (
  bucket_id = 'call-recordings'
  and exists (
    select 1 from public.calls c
    where (storage.foldername(name))[1] = c.id::text
      and (c.created_by = auth.uid() or c.callee_id = auth.uid())
  )
);

-- ── 4. persist the path on the call row (direct calls-table updates
--      are blocked by RLS, so participants go through this definer) ──
create or replace function public.attach_call_recording(p_call_id uuid, p_path text)
returns void
language plpgsql
security definer
set search_path = public, pg_temp
as $$
begin
  if auth.uid() is null then raise exception 'sign in first'; end if;
  update public.calls
     set recording_url = p_path
   where id = p_call_id
     and (created_by = auth.uid() or callee_id = auth.uid());
  if not found then raise exception 'call not found or not yours'; end if;
end;
$$;
grant execute on function public.attach_call_recording(uuid, text) to authenticated;
