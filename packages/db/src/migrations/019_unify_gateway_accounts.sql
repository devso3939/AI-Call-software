-- =====================================================================
-- Migration 019 — unify gateway accounts + make credentials always work.
--
-- State found in the DB today (user report "invalid gateway credentials"
-- even with fresh phone-generated creds):
--   • TWO accounts: 'smartbookly' (DISABLED — old web-created one, its
--     creds correctly rejected) and 'smartbookly1' (enabled, created
--     from the phone). The user copied the phone-generated creds but
--     typed the OLD username, or SmartBookly cached the old one →
--     "invalid gateway credentials".
--   • The phone re-paired: old device 3dcd610c is GONE; new device
--     9f3093c8 has NEVER heartbeated (last_seen_at null) → web tab
--     honestly shows offline. The gateway service isn't running on the
--     phone (or the new install never started it).
--   • Web tab bug: when NO device is selected in the form it blocks
--     with "Pick which phone the gateway sends from." — even when the
--     user just wants to re-enable existing creds. Also the device
--     dropdown never preselects the bound device.
--   • 015's device-side setup still inserts a NEW row when the user's
--     existing row is disabled (partial-index conflict misses it) —
--     that's how the duplicate 'smartbookly1' was born. 016 fixed the
--     web path; this fixes the device path the same way.
--
-- Fixes:
--   1. gateway_setup_from_device: ownership-aware upsert identical to
--      016's setup_sms_gateway (revive own disabled row, friendly
--      "username taken" for others, never a blind insert).
--   2. Data repair: consolidate the two accounts — re-enable
--      'smartbookly' bound to the CURRENT device with a fresh password,
--      disable 'smartbookly1'. The user gets ONE working credential.
-- =====================================================================

-- ---------- 1) device-side setup: same ownership-aware upsert as 016 ----------
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
  v_dev   public.gateway_devices;
  v_uid   uuid;
  v_pw    text;
  v_un    text;
  v_acct  private.gateway_accounts;
  v_owner uuid;
begin
  select * into v_dev from public.gateway_devices
    where id = p_device_id
      and secret_hash = private.sha256_hex(coalesce(trim(p_device_secret), ''));
  if v_dev.id is null then
    raise exception 'invalid device credentials';
  end if;
  v_uid := v_dev.user_id;

  if p_username is null or length(trim(p_username)) < 3 or length(trim(p_username)) > 40
     or trim(p_username) !~ '^[a-z0-9][a-z0-9._-]*$' then
    raise exception 'username must be 3..40 chars: lowercase letters, digits, dot, dash, underscore';
  end if;
  v_un := lower(trim(p_username));
  v_pw := coalesce(nullif(trim(p_password), ''), '');
  if v_pw = '' then
    v_pw := 'ocgw_' || encode(extensions.gen_random_bytes(18), 'hex');
  elsif length(v_pw) < 8 then
    raise exception 'password must be at least 8 characters (or leave empty to auto-generate)';
  end if;

  -- UNIFY (019): who owns this username already?
  select user_id into v_owner from private.gateway_accounts
    where username = v_un limit 1;
  if v_owner is not null and v_owner <> v_uid then
    raise exception 'username "%" is already taken — pick another one', v_un;
  end if;

  if v_owner = v_uid then
    -- username belongs to this user (even a disabled row): update THAT row
    update private.gateway_accounts
      set password_hash = private.sha256_hex(v_pw),
          device_id     = v_dev.id,
          enabled       = true
      where username = v_un and user_id = v_uid
      returning * into v_acct;
  else
    -- one active account per user: replace in place (keeps usage stats)
    insert into private.gateway_accounts (user_id, username, password_hash, device_id, any_device, enabled)
      values (v_uid, v_un, private.sha256_hex(v_pw), v_dev.id, false, true)
    on conflict (user_id) where enabled do update
      set username = excluded.username,
          password_hash = excluded.password_hash,
          device_id = excluded.device_id,
          enabled = true
    returning * into v_acct;
  end if;

  delete from private.gateway_tokens
    where account_id in (select id from private.gateway_accounts where user_id = v_uid and enabled);

  insert into public.gateway_api_log (user_id, account_id, action, ok, detail)
    values (v_uid, v_acct.id, 'device_setup', true,
            jsonb_build_object('device', v_dev.name, 'username', v_acct.username));

  return jsonb_build_object('accountId', v_acct.id, 'username', v_acct.username,
                            'password', v_pw, 'deviceId', v_dev.id, 'deviceName', v_dev.name);
end;
$$;
grant execute on function public.gateway_setup_from_device(uuid, text, text, text) to anon, authenticated;
