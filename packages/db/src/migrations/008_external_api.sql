-- =====================================================================
-- Migration 008 — OpenCall Connect: let OTHER web apps & services use
-- your connected SIM gateway (SMS + cellular calls) through a REST-ish
-- RPC surface authenticated by per-user API keys.
--
-- Model:
--   • The owner mints an API key in the web app (Connect tab).
--     Key format: ock_live_<48 hex> — shown ONCE, stored hashed (sha256).
--   • External apps call PostgREST RPCs with the project's anon key plus
--     their API key: api_status / api_send_sms / api_get_sms /
--     api_place_call / api_get_call / api_post_signal / api_get_signals.
--   • Every call is rate-limited (60 req / rolling 60 s per user) and
--     counted per key (request_count, last_used_at).
--   • api_place_call queues a dial_call to the phone exactly like the
--     web app does. The external service can stay headless (just poll
--     api_get_call) OR run its own WebRTC audio (AI voice agent!) via
--     api_post_signal / api_get_signals — the phone bridges audio over
--     its cellular call + speaker/mic path.
--   • API signal posts use the OWNER's uid as sender, so the web UI's
--     echo filter ignores them (web UI and API audio must not both join
--     the same call — documented honestly in the Connect tab).
-- =====================================================================

-- ============ storage (private schema — never exposed via PostgREST) ============
create table if not exists private.api_keys (
  id            uuid primary key default gen_random_uuid(),
  user_id       uuid not null references public.profiles(id) on delete cascade,
  name          text not null default 'My app',
  prefix        text not null,                      -- first 16 chars, display only
  key_hash      text not null,                      -- sha256(full key), never the key itself
  last_used_at  timestamptz,
  request_count bigint not null default 0,
  revoked       boolean not null default false,
  created_at    timestamptz not null default now()
);
create index if not exists api_keys_user_idx on private.api_keys (user_id, created_at desc);

create table if not exists private.api_rate_window (
  user_id      uuid primary key,
  window_start timestamptz not null default now(),
  req_count    int not null default 0
);

-- ============ private helpers ============

-- resolve an API key to its owner row (hash compare, revoked keys rejected)
create or replace function private.api_key_for_key(p_key text)
returns private.api_keys
language sql
security definer
set search_path = public, pg_temp
as $$
  select k.* from private.api_keys k
  where k.key_hash = encode(extensions.digest(convert_to(p_key,'utf8'),'sha256'),'hex')
    and not k.revoked
  limit 1;
$$;

-- sliding 60 s window, 60 requests per user across all their keys
create or replace function private.api_rate_ok(p_uid uuid, p_max int default 60)
returns boolean
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  v_count int;
begin
  insert into private.api_rate_window as r (user_id, window_start, req_count)
  values (p_uid, now(), 1)
  on conflict (user_id) do update
    set window_start = case when r.window_start < now() - interval '60 seconds'
                            then now() else r.window_start end,
        req_count    = case when r.window_start < now() - interval '60 seconds'
                            then 1 else r.req_count + 1 end
  returning req_count into v_count;
  return v_count <= p_max;
end;
$$;

-- shared auth+rate preamble per call; also bumps usage stats
create or replace function private.api_auth(p_key text)
returns private.api_keys
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  v_key private.api_keys;
begin
  if p_key is null or length(p_key) < 20 then raise exception 'invalid API key'; end if;
  select * into v_key from private.api_key_for_key(p_key);
  if v_key.id is null then raise exception 'invalid or revoked API key'; end if;
  if not private.api_rate_ok(v_key.user_id) then
    raise exception 'rate limit exceeded — 60 requests per minute';
  end if;
  update private.api_keys
    set last_used_at = now(), request_count = request_count + 1
    where id = v_key.id;
  return v_key;
end;
$$;

-- ============ owner-side management (called from the web app) ============

-- mint a new key. The plaintext is returned exactly once — only the hash is kept.
create or replace function public.create_api_key(p_name text default 'My app')
returns jsonb
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  v_uid  uuid := auth.uid();
  v_key  text;
  v_row  private.api_keys;
begin
  if v_uid is null then raise exception 'sign in first'; end if;
  if exists (select 1 from private.api_keys where user_id = v_uid and not revoked)
     and (select count(*) from private.api_keys where user_id = v_uid and not revoked) >= 10 then
    raise exception 'key limit reached (10 active keys) — revoke one first';
  end if;
  v_key := 'ock_live_' || encode(extensions.gen_random_bytes(24), 'hex');
  insert into private.api_keys (user_id, name, prefix, key_hash)
    values (v_uid, left(coalesce(nullif(trim(p_name), ''), 'My app'), 40),
            left(v_key, 16),
            encode(extensions.digest(convert_to(v_key, 'utf8'), 'sha256'), 'hex'))
    returning * into v_row;
  return jsonb_build_object('id', v_row.id, 'name', v_row.name, 'key', v_key, 'prefix', v_row.prefix);
end;
$$;
grant execute on function public.create_api_key(text) to authenticated;

-- list my keys (never returns the secret)
create or replace function public.list_api_keys()
returns table (id uuid, name text, prefix text, last_used_at timestamptz,
               request_count bigint, revoked boolean, created_at timestamptz)
language sql
security definer
set search_path = public, pg_temp
as $$
  select k.id, k.name, k.prefix, k.last_used_at, k.request_count, k.revoked, k.created_at
  from private.api_keys k
  where k.user_id = auth.uid()
  order by k.created_at desc;
$$;
grant execute on function public.list_api_keys() to authenticated;

create or replace function public.revoke_api_key(p_id uuid)
returns void
language plpgsql
security definer
set search_path = public, pg_temp
as $$
begin
  if auth.uid() is null then raise exception 'sign in first'; end if;
  update private.api_keys set revoked = true
    where id = p_id and user_id = auth.uid() and not revoked;
end;
$$;
grant execute on function public.revoke_api_key(uuid) to authenticated;

-- ============ service-side surface (external apps; anon key + API key) ============

-- capability probe: what can this key do right now?
create or replace function public.api_status(p_key text)
returns jsonb
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  v_key private.api_keys;
  v_dev public.gateway_devices;
  v_profile public.profiles;
begin
  select * into v_key from private.api_auth(p_key);
  select * into v_profile from public.profiles where id = v_key.user_id;
  select * into v_dev from public.gateway_devices
    where user_id = v_key.user_id and online = true
    order by last_seen_at desc nulls last limit 1;
  return jsonb_build_object(
    'service', 'opencall',
    'user', v_profile.internet_identity,
    'displayName', v_profile.display_name,
    'sms', jsonb_build_object('enabled', v_dev.id is not null),
    'calls', jsonb_build_object('enabled', v_dev.id is not null),
    'device', case when v_dev.id is null then null else jsonb_build_object(
      'name', v_dev.name, 'number', v_dev.sim_number,
      'battery', v_dev.battery, 'lastSeenAt', v_dev.last_seen_at) end,
    'rateLimit', jsonb_build_object('requests', 60, 'windowSeconds', 60)
  );
end;
$$;
grant execute on function public.api_status(text) to anon, authenticated;

-- send an SMS from the owner's SIM (queued to the paired phone)
create or replace function public.api_send_sms(p_key text, p_to text, p_body text)
returns public.sms_messages
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  v_key private.api_keys;
  v_dev public.gateway_devices;
  v_sms public.sms_messages;
begin
  select * into v_key from private.api_auth(p_key);
  if p_to !~ '^\+[1-9][0-9]{3,15}$' then raise exception 'destination must be E.164 (+country...)'; end if;
  if length(p_body) = 0 or length(p_body) > 1600 then raise exception 'body must be 1..1600 chars'; end if;

  select * into v_dev from public.gateway_devices
    where user_id = v_key.user_id and online = true
    order by last_seen_at desc nulls last limit 1;
  if v_dev.id is null then raise exception 'no gateway device online — the owner must open the app and pair/heartbeat their phone'; end if;

  insert into public.sms_messages (user_id, device_id, direction, to_number, body, status)
    values (v_key.user_id, v_dev.id, 'outbound', p_to, p_body, 'queued')
    returning * into v_sms;

  insert into public.gateway_commands (device_id, kind, payload, requested_by)
    values (v_dev.id, 'send_sms',
            jsonb_build_object('smsId', v_sms.id, 'to', p_to, 'body', p_body, 'via', v_key.name),
            v_key.user_id);
  return v_sms;
end;
$$;
grant execute on function public.api_send_sms(text, text, text) to anon, authenticated;

-- read recent SMS (outbound + inbound) — polling model for external apps
create or replace function public.api_get_sms(p_key text, p_limit int default 20, p_since timestamptz default null)
returns table (id uuid, direction text, to_number text, from_number text, body text,
               status text, error text, created_at timestamptz)
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  v_key private.api_keys;
begin
  select * into v_key from private.api_auth(p_key);
  return query
  select m.id, m.direction, m.to_number, m.from_number, m.body, m.status, m.error, m.created_at
  from public.sms_messages m
  where m.user_id = v_key.user_id
    and (p_since is null or m.created_at > p_since)
  order by m.created_at desc
  limit least(greatest(coalesce(p_limit, 20), 1), 100);
end;
$$;
grant execute on function public.api_get_sms(text, int, timestamptz) to anon, authenticated;

-- place a real cellular call from the owner's SIM (headless or WebRTC-bridge)
create or replace function public.api_place_call(p_key text, p_to text)
returns public.calls
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  v_key private.api_keys;
  v_dev public.gateway_devices;
  v_call public.calls;
begin
  select * into v_key from private.api_auth(p_key);
  if p_to !~ '^\+[1-9][0-9]{5,15}$' then raise exception 'destination must be E.164 (+country...)'; end if;

  select * into v_dev from public.gateway_devices
    where user_id = v_key.user_id and online = true
    order by last_seen_at desc nulls last limit 1;
  if v_dev.id is null then raise exception 'no gateway device online — the owner must open the app and pair/heartbeat their phone'; end if;

  insert into public.calls (created_by, callee_id, requested_destination, normalized_destination,
                            route_type, mode, status, caller_kind, pstn_to, direction)
    values (v_key.user_id, v_key.user_id, p_to, p_to, 'PSTN', 'agent', 'created', 'pstn', p_to, 'outbound-pstn')
    returning * into v_call;

  insert into public.gateway_commands (device_id, kind, payload, requested_by)
    values (v_dev.id, 'dial_call',
            jsonb_build_object('callId', v_call.id, 'to', p_to, 'room', 'call-' || v_call.id, 'via', v_key.name),
            v_key.user_id);

  perform pg_notify('opencall_events', json_build_object('type', 'gateway.dial', 'toUser', v_key.user_id,
    'payload', json_build_object('callId', v_call.id, 'to', p_to, 'via', v_key.name))::text);
  return v_call;
end;
$$;
grant execute on function public.api_place_call(text, text) to anon, authenticated;

-- poll one call's state (status / duration / termination reason)
create or replace function public.api_get_call(p_key text, p_call_id uuid)
returns public.calls
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  v_key private.api_keys;
  v_call public.calls;
begin
  select * into v_key from private.api_auth(p_key);
  select c.* into v_call from public.calls c
  where c.id = p_call_id and c.created_by = v_key.user_id
  limit 1;
  if v_call.id is null then raise exception 'call not found for this API key'; end if;
  return v_call;
end;
$$;
grant execute on function public.api_get_call(text, uuid) to anon, authenticated;

-- WebRTC signaling for external audio agents (AI voice bots etc.)
-- posts as the OWNER's uid: the phone treats it as the peer; the web UI's
-- echo filter ignores it (never join web audio + API audio on one call).
create or replace function public.api_post_signal(p_key text, p_call_id uuid, p_kind text, p_payload jsonb)
returns int
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  v_key private.api_keys;
  v_seq int;
begin
  select * into v_key from private.api_auth(p_key);
  if not exists (select 1 from public.calls c where c.id = p_call_id and c.created_by = v_key.user_id) then
    raise exception 'call not found for this API key';
  end if;
  if p_kind not in ('offer','answer','ice','bye') then raise exception 'invalid event kind'; end if;
  select coalesce(max(seq), 0) + 1 into v_seq from public.calls_events where call_id = p_call_id;
  insert into public.calls_events (call_id, seq, sender, kind, payload)
    values (p_call_id, v_seq, v_key.user_id, p_kind, p_payload);
  return v_seq;
end;
$$;
grant execute on function public.api_post_signal(text, uuid, text, jsonb) to anon, authenticated;

-- read signaling from the phone (skips own echo), for external agents
create or replace function public.api_get_signals(p_key text, p_call_id uuid, p_after_seq int default 0)
returns table (seq int, kind text, payload jsonb)
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  v_key private.api_keys;
begin
  select * into v_key from private.api_auth(p_key);
  if not exists (select 1 from public.calls c where c.id = p_call_id and c.created_by = v_key.user_id) then
    raise exception 'call not found for this API key';
  end if;
  return query
  select e.seq, e.kind, e.payload
  from public.calls_events e
  where e.call_id = p_call_id
    and e.seq > coalesce(p_after_seq, 0)
    and e.sender <> v_key.user_id
  order by e.seq;
end;
$$;
grant execute on function public.api_get_signals(text, uuid, int) to anon, authenticated;
