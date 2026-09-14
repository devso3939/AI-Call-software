-- =====================================================================
-- Migration 016 — fix "duplicate key value violates unique constraint
-- gateway_accounts_username_key" when enabling the SMS gateway.
--
-- Root cause: setup_sms_gateway only declared a conflict target on the
-- partial unique index (user_id) where enabled. The plain UNIQUE
-- constraint on username was NOT handled, so re-enabling the gateway
-- with a username that already exists (e.g. after the account was
-- disabled once, or created from the phone) raised 23505.
--
-- Fix: explicit ownership-aware upsert:
--   • username taken by ANOTHER user  → friendly "username already taken"
--   • username owned by THIS user (even a disabled row) → revive/update
--     that same row (keeps id, usage stats)
--   • otherwise behave as before (replace the user's enabled row)
-- =====================================================================

create or replace function public.setup_sms_gateway(p_device_id uuid, p_username text, p_password text default null, p_any_device boolean default false)
returns jsonb
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  v_uid  uuid := auth.uid();
  v_pw   text;
  v_un   text;
  v_acct private.gateway_accounts;
  v_dev  public.gateway_devices;
  v_owner uuid;
begin
  if v_uid is null then raise exception 'sign in first'; end if;
  -- device must belong to the caller
  select * into v_dev from public.gateway_devices where id = p_device_id and user_id = v_uid;
  if v_dev.id is null then raise exception 'device not found among your paired devices'; end if;

  if p_username is null or length(trim(p_username)) < 3 or length(trim(p_username)) > 40
     or trim(p_username) !~ '^[a-z0-9][a-z0-9._-]*$' then
    raise exception 'username must be 3..40 chars: lowercase letters, digits, dot, dash, underscore';
  end if;
  v_un := lower(trim(p_username));
  v_pw := coalesce(nullif(trim(p_password), ''), '');
  if v_pw = '' then
    v_pw := 'ocgw_' || encode(extensions.gen_random_bytes(18), 'hex'); -- auto-generated
  elsif length(v_pw) < 8 then
    raise exception 'password must be at least 8 characters (or leave empty to auto-generate)';
  end if;

  -- USERNAME-KEY FIX (016): who owns this username already?
  select user_id into v_owner from private.gateway_accounts
    where username = v_un limit 1;
  if v_owner is not null and v_owner <> v_uid then
    raise exception 'username "%" is already taken — pick another one', v_un;
  end if;

  if v_owner = v_uid then
    -- the username belongs to this user (possibly a disabled row):
    -- update THAT row in place — never a fresh insert, so the unique
    -- constraint on username can never be violated.
    update private.gateway_accounts
      set password_hash = private.sha256_hex(v_pw),
          device_id     = p_device_id,
          any_device    = coalesce(p_any_device, false),
          enabled       = true
      where username = v_un and user_id = v_uid
      returning * into v_acct;
  else
    -- one active account per user: replace in place (keeps usage stats)
    insert into private.gateway_accounts (user_id, username, password_hash, device_id, any_device, enabled)
      values (v_uid, v_un, private.sha256_hex(v_pw), p_device_id, coalesce(p_any_device, false), true)
    on conflict (user_id) where enabled do update
      set username = excluded.username,
          password_hash = excluded.password_hash,
          device_id = excluded.device_id,
          any_device = excluded.any_device,
          enabled = true
    returning * into v_acct;
  end if;

  -- invalidate any outstanding tokens (credentials changed)
  delete from private.gateway_tokens
    where account_id in (select id from private.gateway_accounts where user_id = v_uid and enabled);
  return jsonb_build_object('accountId', v_acct.id, 'username', v_acct.username,
                            'password', v_pw, 'deviceId', v_dev.id, 'deviceName', v_dev.name);
end;
$$;
grant execute on function public.setup_sms_gateway(uuid, text, text, boolean) to authenticated;
