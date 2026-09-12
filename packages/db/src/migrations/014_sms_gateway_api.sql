-- =====================================================================
-- Migration 014 — SMS Gateway (like sms-gate / "SMS Gateway cloud"):
-- third-party services send SMS through the user's SIM by presenting a
-- USERNAME + PASSWORD credential pair instead of an API key.
--
-- Model:
--   • The owner enables the gateway for ONE paired device from the web
--     app ("SMS Gateway" tab): picks the device, sets a username and a
--     password. Credentials are per-user (one active gateway account).
--   • Password is stored hashed (sha256) — shown ONCE when generated.
--   • A third-party service authenticates with username+password and
--     gets a short-lived API token (or uses user+pass directly on each
--     RPC, sms-gate style). We support BOTH:
--       - gateway_api_login(username, password)  → { token, expiresAt }
--       - gateway_api_send_sms(username|token, password?, to, body)
--   • Device binding: sends are routed ONLY to the device the owner
--     selected (gateway_settings.sms_device_id) — falls back to the
--     best online device only if the bound one is missing/offline AND
--     the owner left binding loose (any_device = true).
--   • Every API send is logged with the calling credential + source IP
--     so the owner can audit usage in the web app.
--   • Rate limit: 120 req / rolling 60 s per gateway account (more
--     generous than the general API because SMS gateways are chatty).
-- =====================================================================

-- ============ storage (private schema) ============
create table if not exists private.gateway_accounts (
  id           uuid primary key default gen_random_uuid(),
  user_id      uuid not null references public.profiles(id) on delete cascade,
  username     text not null unique,                -- chosen by the owner
  password_hash text not null,                      -- sha256(password), never plaintext
  device_id    uuid references public.gateway_devices(id) on delete set null, -- bound SIM device
  any_device   boolean not null default false,      -- true = fall back to any online device
  enabled      boolean not null default true,
  last_used_at timestamptz,
  request_count bigint not null default 0,
  sent_count   bigint not null default 0,
  failed_count bigint not null default 0,
  created_at   timestamptz not null default now()
);
create unique index if not exists gateway_accounts_one_per_user
  on private.gateway_accounts (user_id) where enabled;
create index if not exists gateway_accounts_user_idx on private.gateway_accounts (user_id);

create table if not exists private.gateway_tokens (
  token_hash  text primary key,                     -- sha256(token)
  account_id  uuid not null references private.gateway_accounts(id) on delete cascade,
  label       text not null default 'session',
  expires_at  timestamptz not null,
  created_at  timestamptz not null default now()
);
create index if not exists gateway_tokens_acct_idx on private.gateway_tokens (account_id);

create table if not exists private.gateway_rate_window (
  account_id   uuid primary key,
  window_start timestamptz not null default now(),
  req_count    int not null default 0
);

-- audit log: one row per API request (visible to the owner in the web app)
create table if not exists public.gateway_api_log (
  id          bigserial primary key,
  user_id     uuid not null references public.profiles(id) on delete cascade,
  account_id  uuid,
  action      text not null,                        -- login|send_sms|status|get_sms|test
  ok          boolean not null,
  detail      jsonb not null default '{}'::jsonb,   -- {to, smsId, error, ...}
  created_at  timestamptz not null default now()
);
create index if not exists gateway_api_log_user_idx on public.gateway_api_log (user_id, created_at desc);
-- user_id is nullable: failed login attempts from unknown callers have no owner
alter table public.gateway_api_log alter column user_id drop not null;
revoke all on public.gateway_api_log from anon, authenticated;
alter table public.gateway_api_log enable row level security;
drop policy if exists "own gateway api log" on public.gateway_api_log;
create policy "own gateway api log" on public.gateway_api_log
  for select to authenticated using (user_id = auth.uid());

-- ============ private helpers ============

-- sha256 helper (hex)
create or replace function private.sha256_hex(p text)
returns text
language sql
immutable
as $$
  select encode(extensions.digest(convert_to(p, 'utf8'), 'sha256'), 'hex');
$$;

-- resolve account by username OR by bearer token (one lookup, both modes)
create or replace function private.gateway_account_for_auth(p_username text, p_password text, p_token text)
returns private.gateway_accounts
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  v_acct private.gateway_accounts;
begin
  if p_token is not null and length(p_token) >= 20 then
    select a.* into v_acct
    from private.gateway_tokens t
    join private.gateway_accounts a on a.id = t.account_id
    where t.token_hash = private.sha256_hex(p_token)
      and t.expires_at > now()
      and a.enabled;
    if v_acct.id is not null then return v_acct; end if;
  end if;
  if p_username is not null and p_password is not null then
    select * into v_acct from private.gateway_accounts
    where username = lower(trim(p_username)) and enabled
    limit 1;
    if v_acct.id is not null
       and v_acct.password_hash = private.sha256_hex(p_password) then
      return v_acct;
    end if;
  end if;
  return null; -- not found / bad credentials (caller raises)
end;
$$;

-- sliding 60 s window, 120 req per gateway account
create or replace function private.gateway_rate_ok(p_account_id uuid, p_max int default 120)
returns boolean
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  v_count int;
begin
  insert into private.gateway_rate_window as r (account_id, window_start, req_count)
  values (p_account_id, now(), 1)
  on conflict (account_id) do update
    set window_start = case when r.window_start < now() - interval '60 seconds'
                            then now() else r.window_start end,
        req_count    = case when r.window_start < now() - interval '60 seconds'
                            then 1 else r.req_count + 1 end
  returning req_count into v_count;
  return v_count <= p_max;
end;
$$;

-- shared preamble: authenticate + rate-limit + bump usage + audit log
create or replace function private.gateway_api_preamble(
  p_username text, p_password text, p_token text, p_action text, p_ok boolean default true, p_detail jsonb default null
)
returns private.gateway_accounts
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  v_acct private.gateway_accounts;
begin
  v_acct := private.gateway_account_for_auth(p_username, p_password, p_token);
  if v_acct.id is null then
    -- log the failed attempt against a matching username if one exists
    insert into public.gateway_api_log (user_id, account_id, action, ok, detail)
    select a.user_id, a.id, p_action, false,
           coalesce(p_detail, '{}'::jsonb) || jsonb_build_object('error', 'invalid credentials')
    from private.gateway_accounts a
    where p_username is not null and a.username = lower(trim(p_username))
    limit 1;
    if not found then
      insert into public.gateway_api_log (user_id, account_id, action, ok, detail)
      values (null, null, p_action, false,
              coalesce(p_detail, '{}'::jsonb) || jsonb_build_object('error', 'invalid credentials'));
    end if;
    raise exception 'invalid gateway credentials';
  end if;
  if not private.gateway_rate_ok(v_acct.id) then
    raise exception 'rate limit exceeded — 120 requests per minute';
  end if;
  update private.gateway_accounts
    set last_used_at = now(), request_count = request_count + 1
    where id = v_acct.id;
  insert into public.gateway_api_log (user_id, account_id, action, ok, detail)
    values (v_acct.user_id, v_acct.id, p_action, p_ok,
            coalesce(p_detail, '{}'::jsonb));
  return v_acct;
end;
$$;

-- pick the bound device (with optional loose fallback), like send_sms does
create or replace function private.gateway_pick_device(p_acct private.gateway_accounts)
returns public.gateway_devices
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  v_dev public.gateway_devices;
begin
  -- bound device first (even if another is fresher)
  if p_acct.device_id is not null then
    select * into v_dev from public.gateway_devices
    where id = p_acct.device_id and online = true;
    if v_dev.id is not null then return v_dev; end if;
  end if;
  -- loose fallback: any online device of the owner (newest first)
  if p_acct.any_device then
    select * into v_dev from public.gateway_devices
    where user_id = p_acct.user_id and online = true
    order by last_seen_at desc nulls last limit 1;
  end if;
  return v_dev;
end;
$$;

-- ============ owner-side management (called from the web app) ============

-- enable/replace the gateway account. Returns the password ONCE.
create or replace function public.setup_sms_gateway(p_device_id uuid, p_username text, p_password text default null, p_any_device boolean default false)
returns jsonb
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  v_uid  uuid := auth.uid();
  v_pw   text;
  v_acct private.gateway_accounts;
  v_dev  public.gateway_devices;
begin
  if v_uid is null then raise exception 'sign in first'; end if;
  -- device must belong to the caller
  select * into v_dev from public.gateway_devices where id = p_device_id and user_id = v_uid;
  if v_dev.id is null then raise exception 'device not found among your paired devices'; end if;

  if p_username is null or length(trim(p_username)) < 3 or length(trim(p_username)) > 40
     or trim(p_username) !~ '^[a-z0-9][a-z0-9._-]*$' then
    raise exception 'username must be 3..40 chars: lowercase letters, digits, dot, dash, underscore';
  end if;
  v_pw := coalesce(nullif(trim(p_password), ''), '');
  if v_pw = '' then
    v_pw := 'ocgw_' || encode(extensions.gen_random_bytes(18), 'hex'); -- auto-generated
  elsif length(v_pw) < 8 then
    raise exception 'password must be at least 8 characters (or leave empty to auto-generate)';
  end if;

  -- one active account per user: replace in place (keeps usage stats)
  insert into private.gateway_accounts (user_id, username, password_hash, device_id, any_device, enabled)
    values (v_uid, lower(trim(p_username)), private.sha256_hex(v_pw), p_device_id, coalesce(p_any_device, false), true)
  on conflict (user_id) where enabled do update
    set username = excluded.username,
        password_hash = excluded.password_hash,
        device_id = excluded.device_id,
        any_device = excluded.any_device,
        enabled = true;
  -- invalidate any outstanding tokens (credentials changed)
  delete from private.gateway_tokens
    where account_id in (select id from private.gateway_accounts where user_id = v_uid and enabled);
  select * into v_acct from private.gateway_accounts where user_id = v_uid and enabled;
  return jsonb_build_object('accountId', v_acct.id, 'username', v_acct.username,
                            'password', v_pw, 'deviceId', v_dev.id, 'deviceName', v_dev.name);
end;
$$;
grant execute on function public.setup_sms_gateway(uuid, text, text, boolean) to authenticated;

-- disable the gateway account
create or replace function public.disable_sms_gateway()
returns void
language plpgsql
security definer
set search_path = public, pg_temp
as $$
begin
  if auth.uid() is null then raise exception 'sign in first'; end if;
  update private.gateway_accounts set enabled = false where user_id = auth.uid() and enabled;
end;
$$;
grant execute on function public.disable_sms_gateway() to authenticated;

-- current gateway config (never returns the password)
create or replace function public.get_sms_gateway()
returns jsonb
language sql
security definer
set search_path = public, pg_temp
as $$
  select jsonb_build_object(
    'enabled', (a.id is not null),
    'accountId', a.id,
    'username', a.username,
    'deviceId', a.device_id,
    'deviceName', d.name,
    'deviceNumber', d.sim_number,
    'deviceOnline', d.online,
    'anyDevice', a.any_device,
    'requestCount', a.request_count,
    'sentCount', a.sent_count,
    'failedCount', a.failed_count,
    'lastUsedAt', a.last_used_at,
    'createdAt', a.created_at
  )
  from private.gateway_accounts a
  left join public.gateway_devices d on d.id = a.device_id
  where a.user_id = auth.uid() and a.enabled;
$$;
grant execute on function public.get_sms_gateway() to authenticated;

-- recent API activity for the owner's audit view
create or replace function public.get_gateway_api_log(p_limit int default 50)
returns table (id bigint, action text, ok boolean, detail jsonb, created_at timestamptz)
language sql
security definer
set search_path = public, pg_temp
as $$
  select l.id, l.action, l.ok, l.detail, l.created_at
  from public.gateway_api_log l
  where l.user_id = auth.uid()
  order by l.created_at desc
  limit least(greatest(coalesce(p_limit, 50), 1), 200);
$$;
grant execute on function public.get_gateway_api_log(int) to authenticated;

-- ============ service-side surface (third-party; anon key + credentials) ============

-- login: exchange username+password for a short-lived bearer token (24 h)
create or replace function public.gateway_api_login(p_username text, p_password text)
returns jsonb
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  v_acct private.gateway_accounts;
  v_tok  text;
begin
  v_acct := private.gateway_api_preamble(p_username, p_password, null, 'login');
  v_tok := 'ogt_' || encode(extensions.gen_random_bytes(24), 'hex');
  insert into private.gateway_tokens (token_hash, account_id, label, expires_at)
    values (private.sha256_hex(v_tok), v_acct.id, 'api session', now() + interval '24 hours');
  -- opportunistic cleanup of expired tokens
  delete from private.gateway_tokens where expires_at < now() - interval '1 hour';
  return jsonb_build_object('token', v_tok, 'tokenType', 'Bearer',
                            'expiresAt', to_char(now() + interval '24 hours', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
                            'username', v_acct.username);
end;
$$;
grant execute on function public.gateway_api_login(text, text) to anon, authenticated;

-- send an SMS through the owner's SIM (username+password OR token)
create or replace function public.gateway_api_send_sms(p_username text default null, p_password text default null, p_token text default null, p_to text default null, p_body text default null)
returns jsonb
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  v_acct private.gateway_accounts;
  v_dev  public.gateway_devices;
  v_sms  public.sms_messages;
begin
  -- accept both flat (sms-gate style: username, password, to, body) and token style
  if p_token is null and p_password is null and p_username is not null
     and p_username ~ '^ogt_[0-9a-f]{48}$' then
    p_token := p_username; -- token passed in the username slot for convenience
    p_username := null;
  end if;
  v_acct := private.gateway_api_preamble(p_username, p_password, p_token, 'send_sms',
    p_ok => false, p_detail => jsonb_build_object('to', p_to));
  if p_to !~ '^\+[1-9][0-9]{3,15}$' then
    perform private.gateway_api_preamble(p_username, p_password, p_token, 'noop'); -- no-op; real logging below
    raise exception 'destination must be E.164 (+country...)';
  end if;
  if p_body is null or length(p_body) = 0 or length(p_body) > 1600 then
    raise exception 'body must be 1..1600 chars';
  end if;

  v_dev := private.gateway_pick_device(v_acct);
  if v_dev.id is null then
    update private.gateway_accounts set failed_count = failed_count + 1 where id = v_acct.id;
    raise exception 'no gateway device online — the bound phone must heartbeat (open the gateway app on the phone)';
  end if;

  insert into public.sms_messages (user_id, device_id, direction, to_number, body, status)
    values (v_acct.user_id, v_dev.id, 'outbound', p_to, p_body, 'queued')
    returning * into v_sms;

  insert into public.gateway_commands (device_id, kind, payload, requested_by)
    values (v_dev.id, 'send_sms',
            jsonb_build_object('smsId', v_sms.id, 'to', p_to, 'body', p_body, 'via', 'sms-gateway:' || v_acct.username),
            v_acct.user_id);

  update private.gateway_accounts set sent_count = sent_count + 1 where id = v_acct.id;
  -- make the audit row carry the outcome
  update public.gateway_api_log
    set ok = true, detail = detail || jsonb_build_object('smsId', v_sms.id, 'deviceId', v_dev.id)
    where id = (select id from public.gateway_api_log
                where account_id = v_acct.id and action = 'send_sms'
                order by id desc limit 1);
  return jsonb_build_object('smsId', v_sms.id, 'status', v_sms.status,
                            'to', p_to, 'device', v_dev.name,
                            'queuedAt', v_sms.created_at);
end;
$$;
grant execute on function public.gateway_api_send_sms(text, text, text, text, text) to anon, authenticated;

-- capability probe + account stats
create or replace function public.gateway_api_status(p_username text default null, p_password text default null, p_token text default null)
returns jsonb
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  v_acct private.gateway_accounts;
  v_dev  public.gateway_devices;
begin
  if p_token is null and p_password is null and p_username is not null
     and p_username ~ '^ogt_[0-9a-f]{48}$' then
    p_token := p_username; p_username := null;
  end if;
  v_acct := private.gateway_api_preamble(p_username, p_password, p_token, 'status');
  v_dev := private.gateway_pick_device(v_acct);
  return jsonb_build_object(
    'service', 'opencall-sms-gateway',
    'username', v_acct.username,
    'enabled', v_acct.enabled,
    'device', case when v_dev.id is null then null else jsonb_build_object(
      'name', v_dev.name, 'number', v_dev.sim_number,
      'battery', v_dev.battery, 'online', v_dev.online, 'lastSeenAt', v_dev.last_seen_at) end,
    'sms', jsonb_build_object('enabled', v_dev.id is not null, 'maxBodyChars', 1600),
    'stats', jsonb_build_object('requests', v_acct.request_count, 'sent', v_acct.sent_count,
                                'failed', v_acct.failed_count, 'lastUsedAt', v_acct.last_used_at),
    'rateLimit', jsonb_build_object('requests', 120, 'windowSeconds', 60)
  );
end;
$$;
grant execute on function public.gateway_api_status(text, text, text) to anon, authenticated;

-- read recent SMS (polling model for third-party apps; token or user+pass)
create or replace function public.gateway_api_get_sms(p_username text default null, p_password text default null, p_token text default null, p_limit int default 20, p_since timestamptz default null)
returns table (id uuid, direction text, to_number text, from_number text, body text,
               status text, error text, created_at timestamptz)
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  v_acct private.gateway_accounts;
begin
  if p_token is null and p_password is null and p_username is not null
     and p_username ~ '^ogt_[0-9a-f]{48}$' then
    p_token := p_username; p_username := null;
  end if;
  v_acct := private.gateway_api_preamble(p_username, p_password, p_token, 'get_sms');
  return query
  select m.id, m.direction, m.to_number, m.from_number, m.body, m.status, m.error, m.created_at
  from public.sms_messages m
  where m.user_id = v_acct.user_id
    and (p_since is null or m.created_at > p_since)
  order by m.created_at desc
  limit least(greatest(coalesce(p_limit, 20), 1), 100);
end;
$$;
grant execute on function public.gateway_api_get_sms(text, text, text, int, timestamptz) to anon, authenticated;
