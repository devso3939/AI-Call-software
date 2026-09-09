-- ============================================================
-- Migration 6d: phone-side pairing
--
-- pair_gateway_device() requires an authenticated user session, but the
-- Android gateway app has none. The phone registers itself with the
-- 6-digit code the browser minted (create_pairing_code). The code is the
-- proof of ownership: single-use, 15-minute expiry, 1-in-a-million guess.
-- ============================================================

create or replace function public.register_gateway_device(p_code text, p_name text default 'Android phone', p_sim_number text default null)
returns jsonb
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  v_uid uuid;
  v_secret text;
  v_dev public.gateway_devices;
begin
  if p_code !~ '^[0-9]{6}$' then raise exception 'pairing code must be 6 digits'; end if;
  select user_id into v_uid from public.gateway_pairing_codes
    where used = false and expires_at > now() and code = p_code
    order by created_at desc limit 1;
  if v_uid is null then raise exception 'invalid or expired pairing code'; end if;
  update public.gateway_pairing_codes set used = true where code = p_code and used = false;

  v_secret := 'oc_' || encode(extensions.gen_random_bytes(24),'hex');
  insert into public.gateway_devices (user_id, name, secret_hash, sim_number)
  values (v_uid, coalesce(nullif(p_name,''),'Android phone'),
          encode(extensions.digest(convert_to(v_secret,'utf8'),'sha256'),'hex'),
          nullif(p_sim_number,''))
  returning * into v_dev;
  return jsonb_build_object('deviceId', v_dev.id, 'deviceSecret', v_secret, 'userId', v_uid);
end;
$$;
grant execute on function public.register_gateway_device(text, text, text) to anon;
