-- =====================================================================
-- Migration 027 — regression sweep fixes (1.5.37)
--
-- Found by the full-ecosystem regression audit:
--
-- FIX 1 (regression from 022/025): get_sms_gateway() dropped the
--   'deviceBattery' and 'deviceLastSeenAt' keys the web gateway card
--   reads (app.html battery % + "heartbeat X ago"). 017 had them; the
--   022/025 rewrites lost them → the card silently showed no battery
--   and no last-seen. Restored here.
--
-- FIX 2 (freshness alignment): private.device_fresh used a 2-minute
--   window while the server-side heartbeat timeout (gateway_heartbeat
--   comment) and the phone app both use 90 s. Aligned to 90 s so web,
--   APK and server agree exactly (the "web and app show different
--   pictures" bug, server half).
--
-- FIX 3 (bulk body-matching): gateway_api_send_bulk correlated each
--   sms_messages row to its body with `where pm.value->>'to' = ins.to_number
--   limit 1`. Two recipients in one batch with different bodies could
--   both get the FIRST body. Correlate by array ordinality instead.
-- =====================================================================

-- ---------- FIX 2: freshness = 90 s everywhere ----------
create or replace function private.device_fresh(p_online boolean, p_last_seen timestamptz)
returns boolean
language sql
immutable
as $$
  select coalesce(p_online, false)
     and p_last_seen is not null
     and p_last_seen > now() - interval '90 seconds';
$$;

-- ---------- FIX 1: restore deviceBattery / deviceLastSeenAt ----------
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
    'password', case when a.password_enc is null then null
                     else private.decrypt_pwhex(a.password_enc) end,
    'deviceId', a.device_id,
    'deviceName', d.name,
    'deviceNumber', d.sim_number,
    -- 027 REGRESSION FIX: battery + last-seen were dropped in the 022/025
    -- rewrite; the web gateway card reads them. Battery only when the
    -- heartbeat is fresh (same honesty rule as 017).
    'deviceOnline', private.device_fresh(d.online, d.last_seen_at),
    'deviceBattery', case when private.device_fresh(d.online, d.last_seen_at)
                          then d.battery else null end,
    'deviceLastSeenAt', d.last_seen_at,
    'anyDevice', a.any_device,
    'requestCount', a.request_count,
    'sentCount', a.sent_count,
    'failedCount', a.failed_count,
    'lastUsedAt', a.last_used_at,
    'createdAt', a.created_at,
    'tokenCount', (select count(*)::int from private.gateway_tokens t
                   where t.account_id = a.id),
    'lastTokenAt', (select max(t.created_at) from private.gateway_tokens t
                    where t.account_id = a.id),
    'sent24h', (select count(*)::int from public.sms_messages m
                where m.user_id = a.user_id
                  and m.direction = 'outbound'
                  and m.status = 'delivered'
                  and m.created_at > now() - interval '24 hours'),
    'failed24h', (select count(*)::int from public.sms_messages m
                  where m.user_id = a.user_id
                    and m.direction = 'outbound'
                    and m.status = 'failed'
                    and m.created_at > now() - interval '24 hours')
  )
  from private.gateway_accounts a
  left join public.gateway_devices d on d.id = a.device_id
  where a.user_id = auth.uid() and a.enabled;
$$;
grant execute on function public.get_sms_gateway() to authenticated;

-- ---------- FIX 3: bulk send body-matching ----------
-- 022/024 correlated each command's body back to the source array by
-- `where pm.value->>'to' = ins.to_number limit 1`. With two recipients in
-- one batch (or the same number twice with different bodies), every
-- duplicate matched the FIRST entry's body → wrong SMS delivered.
-- Fix: INSERT..RETURNING can return the `body` column we just stored, so
-- each command takes its body from its OWN sms row. No correlation, no
-- ordering assumptions, duplicates each keep their own body.
create or replace function public.gateway_api_send_bulk(
  p_username text default null,
  p_password text default null,
  p_token    text default null,
  p_messages jsonb default null
)
returns jsonb
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  v_acct  private.gateway_accounts;
  v_dev   public.gateway_devices;
  v_n     int;
  v_bad   int := -1;
  v_msg   record;
  v_ids   jsonb;
begin
  if p_token is null and p_password is null and p_username is not null
     and p_username ~ '^ogt_[0-9a-f]{48}$' then
    p_token := p_username; p_username := null;
  end if;
  -- ONE rate-limit hit + ONE audit row for the whole batch
  v_acct := private.gateway_api_preamble(p_username, p_password, p_token, 'send_bulk',
    p_detail => jsonb_build_object('count', coalesce(jsonb_array_length(p_messages), 0)));

  if p_messages is null or jsonb_typeof(p_messages) <> 'array'
     or jsonb_array_length(p_messages) = 0 then
    raise exception 'messages must be a non-empty JSON array of {to, body}';
  end if;
  v_n := jsonb_array_length(p_messages);
  if v_n > 500 then
    raise exception 'max 500 messages per batch (got %) — split into multiple requests', v_n;
  end if;

  -- device pick once for the whole batch
  v_dev := private.gateway_pick_device(v_acct);
  if v_dev.id is null then
    update private.gateway_accounts set failed_count = failed_count + v_n where id = v_acct.id;
    update public.gateway_api_log
      set ok = false, detail = detail || jsonb_build_object('error', 'no gateway device online — credentials are valid', 'reason', 'device_offline')
      where id = (select id from public.gateway_api_log
                  where account_id = v_acct.id and action = 'send_bulk'
                  order by id desc limit 1);
    raise exception 'no gateway device online — credentials are VALID, do not reconnect; open the gateway app on the phone and retry when it heartbeats';
  end if;

  -- validate ALL entries first (all-or-nothing)
  for v_msg in select value, ord - 1 as idx from jsonb_array_elements(p_messages) with ordinality as t(value, ord)
  loop
    if v_msg.value->>'to' !~ '^\+[1-9][0-9]{3,15}$'
       or coalesce(length(v_msg.value->>'body'), 0) = 0
       or length(v_msg.value->>'body') > 1600 then
      v_bad := v_msg.idx;
      exit;
    end if;
  end loop;
  if v_bad >= 0 then
    update public.gateway_api_log
      set ok = false, detail = detail || jsonb_build_object('error', 'invalid entry at index ' || v_bad)
      where id = (select id from public.gateway_api_log
                  where account_id = v_acct.id and action = 'send_bulk'
                  order by id desc limit 1);
    raise exception 'invalid message at index % — "to" must be E.164 (+country...) and body 1..1600 chars; NOTHING was sent', v_bad;
  end if;

  -- insert all sms rows in one statement; RETURNING body gives each row
  -- its own stored body (027 FIX: was correlated by `to` number → wrong
  -- body for duplicate recipients)
  with ins as (
    insert into public.sms_messages (user_id, device_id, direction, to_number, body, status)
    select v_acct.user_id, v_dev.id, 'outbound',
           m.value->>'to', m.value->>'body', 'queued'
    from jsonb_array_elements(p_messages) with ordinality as m(value, ord)
    returning id, to_number, body
  )
  -- ...and all device commands in one statement, linked to their sms rows
  , cmds as (
    insert into public.gateway_commands (device_id, kind, payload, requested_by)
    select v_dev.id, 'send_sms',
           jsonb_build_object('smsId', ins.id, 'to', ins.to_number,
                              'body', ins.body,
                              'via', 'sms-gateway:' || v_acct.username),
           v_acct.user_id
    from ins
    returning id
  )
  select jsonb_agg(jsonb_build_object('smsId', ins.id, 'to', ins.to_number) order by ins.id)
    into v_ids
  from ins;

  update private.gateway_accounts set sent_count = sent_count + v_n where id = v_acct.id;
  update public.gateway_api_log
    set ok = true, detail = detail || jsonb_build_object('queued', v_n, 'deviceId', v_dev.id)
    where id = (select id from public.gateway_api_log
                where account_id = v_acct.id and action = 'send_bulk'
                order by id desc limit 1);

  return jsonb_build_object('queued', v_n, 'device', v_dev.name,
                            'messages', v_ids);
end;
$$;
grant execute on function public.gateway_api_send_bulk(text, text, text, jsonb) to anon, authenticated;
