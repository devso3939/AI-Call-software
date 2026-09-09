-- ============================================================
-- Migration 9: web-side durable signaling (codify live schema)
--
-- The browser posts its WebRTC offer/answer/ICE as durable rows and
-- drains the phone's replies by polling — this makes signaling survive
-- reloads and work without realtime rooms. These objects existed in the
-- live database but were never captured in a migration file; this file
-- makes a fresh self-host install identical to the live deployment.
--
-- Sender conventions (echo prevention):
--   browser  posts with sender = auth.uid()            (participant)
--   gateway  posts with sender = zero-uuid             (its own echo)
--   api key  posts with sender = owner uid             (external app)
-- Readers skip their own sender id (phone skips zero-uuid, browser
-- skips its uid client-side, api_get_signals skips owner uid).
-- ============================================================

-- durable signaling events, per-call monotonic seq
create table if not exists public.calls_events (
  id bigint generated always as identity primary key,
  call_id uuid not null references public.calls(id) on delete cascade,
  seq integer not null default 0,
  sender uuid not null default '00000000-0000-0000-0000-000000000000'::uuid,
  kind text not null,
  payload jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now()
);
create index if not exists calls_events_call_idx on public.calls_events(call_id, seq);

alter table public.calls_events enable row level security;

drop policy if exists "calls_events participants" on public.calls_events;
create policy "calls_events participants" on public.calls_events
  for all using (
    exists (select 1 from public.calls c
            where c.id = calls_events.call_id
              and (c.created_by = auth.uid() or c.callee_id = auth.uid()))
  ) with check (
    exists (select 1 from public.calls c
            where c.id = calls_events.call_id
              and (c.created_by = auth.uid() or c.callee_id = auth.uid()))
  );

-- browser/guest posts a signaling event (offer|answer|ice|bye)
create or replace function public.post_call_event(p_call_id uuid, p_kind text, p_payload jsonb)
returns integer
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  v_seq int;
  v_call public.calls;
begin
  select * into v_call from public.calls where id = p_call_id;
  if v_call.id is null then raise exception 'call not found'; end if;

  -- participants: caller, callee; guest calls: guest posts before any row is visible to them,
  -- so we accept guest posts if a valid (unexpired) link exists for the host
  if v_call.caller_kind <> 'guest' then
    if v_call.created_by <> auth.uid() and v_call.callee_id <> auth.uid() then
      raise exception 'not a participant of this call';
    end if;
  else
    if not exists (
      select 1 from public.guest_links gl
      where gl.host_id = v_call.created_by and gl.expires_at > now()
    ) then
      raise exception 'no valid guest link for this call';
    end if;
  end if;

  if p_kind not in ('offer','answer','ice','bye') then
    raise exception 'invalid event kind';
  end if;

  select coalesce(max(seq), 0) + 1 into v_seq from public.calls_events where call_id = p_call_id;
  insert into public.calls_events (call_id, seq, sender, kind, payload)
  values (p_call_id, v_seq, coalesce(auth.uid(), '00000000-0000-0000-0000-000000000000'::uuid), p_kind, p_payload);
  return v_seq;
end;
$$;
grant execute on function public.post_call_event(uuid, text, jsonb) to anon, authenticated;

-- participant (or valid guest) drains the durable event stream
create or replace function public.get_call_events(p_call_id uuid, p_after_seq integer default 0)
returns table (seq integer, sender uuid, kind text, payload jsonb)
language sql
security definer
set search_path = public, pg_temp
as $$
  select e.seq, e.sender, e.kind, e.payload
  from public.calls_events e
  where e.call_id = p_call_id
    and e.seq > p_after_seq
    and exists (
      select 1 from public.calls c
      where c.id = p_call_id
        and (c.created_by = auth.uid() or c.callee_id = auth.uid()
             or (c.caller_kind = 'guest' and exists (
                  select 1 from public.guest_links gl
                  where gl.host_id = c.created_by
                    and gl.expires_at > now()
                ))
        )
    )
  order by e.seq;
$$;
grant execute on function public.get_call_events(uuid, integer) to anon, authenticated;

-- participant (or valid guest) reads a call's status row
create or replace function public.get_call_status(p_call_id uuid)
returns table (id uuid, status text, termination_reason text, duration_seconds integer)
language sql
security definer
set search_path = public, pg_temp
as $$
  select c.id, c.status, c.termination_reason, c.duration_seconds
  from public.calls c
  where c.id = p_call_id
    and (
      c.created_by = auth.uid() or c.callee_id = auth.uid()
      or (c.caller_kind = 'guest' and exists (
            select 1 from public.guest_links gl
            where gl.host_id = c.created_by and gl.expires_at > now()
          ))
    );
$$;
grant execute on function public.get_call_status(uuid) to anon, authenticated;
