-- ============================================================
-- Migration 15: Device-side SMS Gateway setup
--
-- Lets the Android gateway app itself create/rotate the SMS gateway
-- credentials for its owner — no web login needed on the phone.
-- Auth: device_id + device_secret (same pairing secret the app already
-- stores in EncryptedSharedPreferences), mirroring gateway_fetch_commands.
-- ============================================================

create or replace function public.gateway_setup_from_device(
  p_device_id     uuid,
  p_device_secret text,
  p_username      text,
  p_password      text default null
)
returns jsonb
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  v_dev  public.gateway_devices;
  v_uid  uuid;
  v_pw   text;
  v_acct private.gateway_accounts;
begin
  -- device auth: id + sha256(secret) must match the paired row
  select * into v_dev from public.gateway_devices
    where id = p_device_id
      and secret_hash = private.sha256_hex(coalesce(trim(p_device_secret), ''));
  if v_dev.id is null then
    raise exception 'invalid device credentials';
  end if;
  v_uid := v_dev.user_id;

  -- same username rules as setup_sms_gateway
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
    values (v_uid, lower(trim(p_username)), private.sha256_hex(v_pw), v_dev.id, false, true)
  on conflict (user_id) where enabled do update
    set username = excluded.username,
        password_hash = excluded.password_hash,
        device_id = excluded.device_id,
        enabled = true;
  -- invalidate any outstanding tokens (credentials changed)
  delete from private.gateway_tokens
    where account_id in (select id from private.gateway_accounts where user_id = v_uid and enabled);
  select * into v_acct from private.gateway_accounts where user_id = v_uid and enabled;

  insert into public.gateway_api_log (user_id, account_id, action, ok, detail)
    values (v_uid, v_acct.id, 'device_setup', true,
            jsonb_build_object('device', v_dev.name, 'username', v_acct.username));

  return jsonb_build_object('accountId', v_acct.id, 'username', v_acct.username,
                            'password', v_pw, 'deviceId', v_dev.id, 'deviceName', v_dev.name);
end;
$$;

-- the paired phone calls this with its anon key + device secret
grant execute on function public.gateway_setup_from_device(uuid, text, text, text) to anon, authenticated;

-- device-readable status of its owner's gateway account (no secrets)
create or replace function public.gateway_status_from_device(
  p_device_id     uuid,
  p_device_secret text
)
returns jsonb
language sql
security definer
set search_path = public, pg_temp
as $$
  select jsonb_build_object(
    'enabled', (a.id is not null),
    'username', a.username,
    'deviceId', a.device_id,
    'deviceName', d.name,
    'sentTotal', a.sent_count,
    'failedTotal', a.failed_count)
  from public.gateway_devices d
  left join private.gateway_accounts a
    on a.user_id = d.user_id and a.enabled and a.device_id = d.id
  where d.id = p_device_id
    and d.secret_hash = private.sha256_hex(coalesce(trim(p_device_secret), ''));
$$;

grant execute on function public.gateway_status_from_device(uuid, text) to anon, authenticated;
