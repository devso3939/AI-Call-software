-- ============================================================
-- Migration 6: SIM Gateway (SMS + real cellular calls via your Android)
--
-- Model: the user's Android phone runs the OpenCall Gateway app.
--   • Phone pairs with an account via 6-digit code → device_id + secret.
--   • Phone long-polls the command queue (same PostgREST endpoint style
--     the browser uses) and executes: send_sms, dial_call, end_call.
--   • Calls bridge audio acoustically: phone places the cellular call on
--     SPEAKER, and joins a WebRTC room (mic+speaker, hardware AEC) so the
--     browser user / AI agent talks with the real phone network, free,
--     over the SIM. (Android exposes no API for digital call-audio taps
--     without root — speaker+mic through hardware AEC is the honest
--     no-root path. Documented in the gateway README.)
--   • SMS: send via SmsManager, receive via broadcast receiver; rows and
--     events land here so the web app is the single pane.
-- ============================================================

-- ============ devices ============
create table if not exists public.gateway_devices (
  id            uuid primary key default gen_random_uuid(),
  user_id       uuid not null references public.profiles(id) on delete cascade,
  name          text not null default 'Android phone',
  secret_hash   text not null,                        -- sha256(device secret), never the secret itself
  sim_number    text,                                 -- E.164 of the SIM (self-reported, for display)
  platform      text not null default 'android',
  app_version   text,
  last_seen_at  timestamptz,
  online        boolean not null default false,
  battery       int,
  created_at    timestamptz not null default now()
);
revoke all on public.gateway_devices from anon, authenticated;
alter table public.gateway_devices enable row level security;

-- create policy "own device rows" on public.gateway_devices /* idempotent below */

-- ============ commands ============
create table if not exists public.gateway_commands (
  id            uuid primary key default gen_random_uuid(),
  device_id     uuid not null references public.gateway_devices(id) on delete cascade,
  kind          text not null check (kind in ('send_sms','dial_call','end_call','ping')),
  payload       jsonb not null default '{}'::jsonb,
  status        text not null default 'pending' check (status in ('pending','sent','done','failed')),
  result        jsonb,
  requested_by  uuid references public.profiles(id),
  created_at    timestamptz not null default now(),
  updated_at    timestamptz not null default now()
);
create index if not exists gwc_device_pending_idx on public.gateway_commands (device_id, status, created_at);
revoke all on public.gateway_commands from anon, authenticated;
alter table public.gateway_commands enable row level security;

-- create policy "own device commands" on public.gateway_commands /* idempotent below */

-- ============ SMS messages ============
create table if not exists public.sms_messages (
  id            uuid primary key default gen_random_uuid(),
  user_id       uuid not null references public.profiles(id) on delete cascade,
  device_id     uuid references public.gateway_devices(id) on delete set null,
  direction     text not null check (direction in ('outbound','inbound')),
  to_number     text,        -- outbound
  from_number   text,        -- inbound
  body          text not null,
  sim_slot      int,
  status        text not null default 'queued' check (status in ('queued','sent','delivered','failed','received')),
  error         text,
  provider_ref  text,        -- Android sentIntent ref for status mapping
  created_at    timestamptz not null default now(),
  updated_at    timestamptz not null default now()
);
create index if not exists sms_user_idx on public.sms_messages (user_id, created_at desc);
revoke all on public.sms_messages from anon, authenticated;
alter table public.sms_messages enable row level security;

-- create policy "own sms" on public.sms_messages /* idempotent below */

-- ============ helper: device auth (hash compare in constant-ish time via SQL) ============
create or replace function private.device_for_key(p_device_id uuid, p_secret text)
returns public.gateway_devices
language sql
security definer
set search_path = public, pg_temp
as $$
  select d.* from public.gateway_devices d
  where d.id = p_device_id
    and d.secret_hash = encode(extensions.digest(convert_to(p_secret,'utf8'),'sha256'),'hex')
  limit 1;
$$;

-- ============ pairing codes (6-digit, 15 min, single use) ============
create table if not exists public.gateway_pairing_codes (
  id          uuid primary key default gen_random_uuid(),
  user_id     uuid not null references public.profiles(id) on delete cascade,
  code        text not null,
  used        boolean not null default false,
  expires_at  timestamptz not null,
  created_at  timestamptz not null default now()
);
revoke all on public.gateway_pairing_codes from anon, authenticated;
alter table public.gateway_pairing_codes enable row level security;
-- create policy "own codes" on public.gateway_pairing_codes for select using (auth.uid() = user_id); /* idempotent below */

-- browser asks for a fresh pairing code
create or replace function public.create_pairing_code()
returns text
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  v_uid uuid := auth.uid();
  v_code text;
begin
  if v_uid is null then raise exception 'sign in first'; end if;
  update public.gateway_pairing_codes set used = true where user_id = v_uid and used = false and expires_at > now();
  v_code := lpad((floor(random() * 1000000))::int::text, 6, '0');
  insert into public.gateway_pairing_codes (user_id, code, expires_at)
    values (v_uid, v_code, now() + interval '15 minutes');
  return v_code;
end;
$$;

-- ============ pairing (browser side; user types the code into the app) ============
create or replace function public.pair_gateway_device(p_code text, p_name text default 'Android phone')
returns jsonb
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  v_uid uuid := auth.uid();
  v_dev public.gateway_devices;
  v_secret text;
begin
  if v_uid is null then raise exception 'sign in first'; end if;
  if p_code !~ '^[0-9]{6}$' then raise exception 'pairing code must be 6 digits'; end if;
  if not exists (
    select 1 from public.gateway_pairing_codes
    where user_id = v_uid and used = false and expires_at > now() and code = p_code
  ) then
    raise exception 'invalid or expired pairing code';
  end if;
  update public.gateway_pairing_codes set used = true where user_id = v_uid and code = p_code and used = false;

  select * into v_dev from public.gateway_devices where user_id = v_uid order by created_at desc limit 1;
  v_secret := 'oc_' || encode(extensions.gen_random_bytes(24),'hex');
  if v_dev.id is not null then
    update public.gateway_devices
      set secret_hash = encode(extensions.digest(convert_to(v_secret,'utf8'),'sha256'),'hex'),
          name = coalesce(p_name, name), last_seen_at = now(), online = false
      where id = v_dev.id returning * into v_dev;
  else
    insert into public.gateway_devices (user_id, name, secret_hash)
      values (v_uid, coalesce(p_name,'Android phone'),
              encode(extensions.digest(convert_to(v_secret,'utf8'),'sha256'),'hex'))
      returning * into v_dev;
  end if;
  return jsonb_build_object('deviceId', v_dev.id, 'deviceSecret', v_secret);
end;
$$;

-- ============ device-side RPCs (called with anon key + device id/secret) ============

-- hello/heartbeat: prove the secret, mark online, report sim + battery
create or replace function public.gateway_heartbeat(p_device_id uuid, p_secret text, p_sim_number text default null, p_battery int default null, p_app_version text default null)
returns void
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare v_dev public.gateway_devices;
begin
  select * into v_dev from private.device_for_key(p_device_id, p_secret);
  if v_dev.id is null then raise exception 'bad device credentials'; end if;
  update public.gateway_devices
    set last_seen_at = now(), online = true,
        sim_number = coalesce(p_sim_number, sim_number),
        battery = coalesce(p_battery, battery),
        app_version = coalesce(p_app_version, app_version)
    where id = v_dev.id;
end;
$$;

-- long-poll-ish fetch: claim pending commands (status pending → sent)
create or replace function public.gateway_fetch_commands(p_device_id uuid, p_secret text, p_limit int default 5)
returns jsonb
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  v_dev public.gateway_devices;
  v_rows jsonb;
begin
  select * into v_dev from private.device_for_key(p_device_id, p_secret);
  if v_dev.id is null then raise exception 'bad device credentials'; end if;
  update public.gateway_devices set last_seen_at = now(), online = true where id = v_dev.id;

  with claimed as (
    select id from public.gateway_commands
    where device_id = v_dev.id and status = 'pending'
    order by created_at asc
    limit least(greatest(p_limit,1),20)
    for update skip locked
  ), upd as (
    update public.gateway_commands c set status='sent', updated_at=now()
    from claimed where c.id = claimed.id
    returning jsonb_build_object('id', c.id, 'kind', c.kind, 'payload', c.payload) as j
  )
  select coalesce(jsonb_agg(j), '[]'::jsonb) into v_rows from upd;
  return v_rows;
end;
$$;

-- complete a command (device reports result)
create or replace function public.gateway_complete_command(p_device_id uuid, p_secret text, p_command_id uuid, p_ok boolean, p_result jsonb default null)
returns void
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare v_dev public.gateway_devices;
begin
  select * into v_dev from private.device_for_key(p_device_id, p_secret);
  if v_dev.id is null then raise exception 'bad device credentials'; end if;
  update public.gateway_commands
    set status = case when p_ok then 'done' else 'failed' end,
        result = coalesce(p_result, result),
        updated_at = now()
    where id = p_command_id and device_id = v_dev.id and status = 'sent';
end;
$$;

-- ============ web side: queue SMS ============
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

  select * into v_dev from public.gateway_devices
    where user_id = v_uid and (p_device_id is null or id = p_device_id) and online = true
    order by last_seen_at desc nulls last limit 1;
  if v_dev.id is null then raise exception 'no gateway device online — pair your phone in the Devices tab first'; end if;

  insert into public.sms_messages (user_id, device_id, direction, to_number, body, status)
    values (v_uid, v_dev.id, 'outbound', p_to, p_body, 'queued')
    returning * into v_sms;

  insert into public.gateway_commands (device_id, kind, payload, requested_by)
    values (v_dev.id, 'send_sms', jsonb_build_object('smsId', v_sms.id, 'to', p_to, 'body', p_body), v_uid);

  return v_sms;
end;
$$;

-- device reports SMS outcome (status: sent|delivered|failed)
create or replace function public.gateway_report_sms(p_device_id uuid, p_secret text, p_sms_id uuid, p_status text, p_error text default null, p_provider_ref text default null)
returns void
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare v_dev public.gateway_devices;
begin
  select * into v_dev from private.device_for_key(p_device_id, p_secret);
  if v_dev.id is null then raise exception 'bad device credentials'; end if;
  if p_status not in ('sent','delivered','failed') then raise exception 'bad status'; end if;
  update public.sms_messages
    set status = p_status, error = p_error, provider_ref = p_provider_ref, updated_at = now()
    where id = p_sms_id and user_id = v_dev.user_id;
end;
$$;

-- device pushes an inbound SMS
create or replace function public.gateway_receive_sms(p_device_id uuid, p_secret text, p_from text, p_body text, p_sim_slot int default null)
returns void
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare v_dev public.gateway_devices;
begin
  select * into v_dev from private.device_for_key(p_device_id, p_secret);
  if v_dev.id is null then raise exception 'bad device credentials'; end if;
  insert into public.sms_messages (user_id, device_id, direction, from_number, body, sim_slot, status)
    values (v_dev.user_id, v_dev.id, 'inbound', p_from, p_body, p_sim_slot, 'received');
  perform pg_notify('opencall_events', json_build_object(
    'type','sms.received','toUser', v_dev.user_id,
    'payload', json_build_object('from', p_from, 'body', p_body))::text);
end;
$$;

-- ============ calls via gateway ============
-- web side: request a real cellular call through my device
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
begin
  if v_uid is null then raise exception 'sign in first'; end if;
  if p_to !~ '^\+[1-9][0-9]{5,15}$' then raise exception 'destination must be E.164 (+country...)'; end if;
  select * into v_dev from public.gateway_devices where user_id = v_uid and online = true order by last_seen_at desc nulls last limit 1;
  if v_dev.id is null then raise exception 'no gateway device online — pair your phone in the Devices tab first'; end if;

  -- PSTN rows are reused for gateway calls (route_type PSTN, pstn_to = number)
  insert into public.calls (created_by, callee_id, requested_destination, normalized_destination,
                            route_type, mode, status, caller_kind, pstn_to, direction)
  values (v_uid, v_uid, p_to, p_to, 'PSTN', 'human', 'created', 'pstn', p_to, 'outbound-pstn')
  returning * into v_call;

  insert into public.gateway_commands (device_id, kind, payload, requested_by)
    values (v_dev.id, 'dial_call', jsonb_build_object('callId', v_call.id, 'to', p_to), v_uid);

  perform pg_notify('opencall_events', json_build_object('type','gateway.dial','toUser', v_uid,
    'payload', json_build_object('callId', v_call.id, 'to', p_to))::text);
  return v_call;
end;
$$;

-- device reports call state (dialing|ringing|answered|ended|failed)
create or replace function public.update_gateway_call(p_device_id uuid, p_secret text, p_call_id uuid, p_state text, p_error text default null)
returns void
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare v_dev public.gateway_devices;
begin
  select * into v_dev from private.device_for_key(p_device_id, p_secret);
  if v_dev.id is null then raise exception 'bad device credentials'; end if;
  if p_state not in ('dialing','ringing','answered','ended','failed') then raise exception 'bad state'; end if;

  if p_state = 'dialing' then
    update public.calls set status='ringing', started_at=now() where id=p_call_id and created_by=v_dev.user_id and status='created';
  elsif p_state = 'ringing' then
    update public.calls set status='ringing', started_at=now() where id=p_call_id and created_by=v_dev.user_id and status in ('created','ringing');
  elsif p_state = 'answered' then
    update public.calls set status='answered', answered_at=now() where id=p_call_id and created_by=v_dev.user_id and status in ('created','ringing');
  elsif p_state = 'ended' then
    update public.calls set status='completed', ended_at=now(),
      duration_seconds = greatest(0, extract(epoch from (now() - coalesce(answered_at, started_at, now())))::int)
      where id=p_call_id and created_by=v_dev.user_id and status in ('created','ringing','answered');
  elsif p_state = 'failed' then
    update public.calls set status='failed', ended_at=now(), termination_reason = left(p_error,200) where id=p_call_id and created_by=v_dev.user_id and status in ('created','ringing','answered');
  end if;

  perform pg_notify('opencall_events', json_build_object('type','gateway.call_state','toUser', v_dev.user_id,
    'payload', json_build_object('callId', p_call_id, 'state', p_state))::text);
end;
$$;

-- inbound cellular call to the SIM → show an incoming-call banner in the web app (informational + joinable)
create or replace function public.report_incoming_call(p_device_id uuid, p_secret text, p_from text)
returns uuid
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  v_dev public.gateway_devices;
  v_call public.calls;
begin
  select * into v_dev from private.device_for_key(p_device_id, p_secret);
  if v_dev.id is null then raise exception 'bad device credentials'; end if;
  insert into public.calls (created_by, callee_id, requested_destination, normalized_destination, route_type, mode, status, caller_kind, pstn_to, direction)
  values (v_dev.user_id, v_dev.user_id, p_from, p_from, 'PSTN', 'human', 'ringing', 'pstn', p_from, 'inbound-pstn')
  returning * into v_call;
  perform pg_notify('opencall_events', json_build_object('type','gateway.incoming','toUser', v_dev.user_id,
    'payload', json_build_object('callId', v_call.id, 'from', p_from))::text);
  return v_call.id;
end;
$$;

-- ============ grants ============
grant execute on function public.create_pairing_code() to authenticated;
grant execute on function public.pair_gateway_device(text, text) to authenticated;
grant execute on function public.gateway_heartbeat(uuid, text, text, int, text) to anon;
grant execute on function public.gateway_fetch_commands(uuid, text, int) to anon;
grant execute on function public.gateway_complete_command(uuid, text, uuid, boolean, jsonb) to anon;
grant execute on function public.send_sms(text, text, uuid) to authenticated;
grant execute on function public.gateway_report_sms(uuid, text, uuid, text, text, text) to anon;
grant execute on function public.gateway_receive_sms(uuid, text, text, text, int) to anon;
grant execute on function public.request_gateway_call(text) to authenticated;
grant execute on function public.update_gateway_call(uuid, text, uuid, text, text) to anon;
grant execute on function public.report_incoming_call(uuid, text, text) to anon;

-- devices list readout (own rows only)
create or replace function public.list_gateway_devices()
returns setof public.gateway_devices
language sql
security definer
set search_path = public
as $$
  select * from public.gateway_devices where user_id = auth.uid() order by created_at asc;
$$;
grant execute on function public.list_gateway_devices() to authenticated;

-- ============ idempotent RLS policies (safe re-runs) ============
drop policy if exists "own device rows" on public.gateway_devices;
create policy "own device rows" on public.gateway_devices for select using (auth.uid() = user_id);

drop policy if exists "own device commands" on public.gateway_commands;
create policy "own device commands" on public.gateway_commands for select
  using (exists (select 1 from public.gateway_devices d where d.id = device_id and d.user_id = auth.uid()));

drop policy if exists "own sms" on public.sms_messages;
create policy "own sms" on public.sms_messages for select using (auth.uid() = user_id);

drop policy if exists "own codes" on public.gateway_pairing_codes;
create policy "own codes" on public.gateway_pairing_codes for select using (auth.uid() = user_id);
