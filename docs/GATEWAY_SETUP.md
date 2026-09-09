# OpenCall SIM Gateway — Setup Guide

Turn **your own Android phone + your own SIM** into a free SMS and calling gateway for OpenCall AI. No Twilio, no per-message fees, no per-minute fees. Your real phone number is the sender/caller ID, anywhere in the world, for the cost of your existing plan.

> This is the same architecture as [android-sms-gateway / sms-gate.app](https://github.com/capcom6/android-sms-gateway), extended with a **live audio bridge for calls** (theirs sends SMS only).

---

## How it works (1-minute version)

```
Browser (you / AI agent)          Supabase (Postgres)             Your Android phone
─────────────────────────         ───────────────────             ──────────────────
"send SMS" / "dial"  ───command──▶ gateway_commands  ──long-poll─▶ GatewayService
                                                                     │
SMS tab / call UI  ◀──realtime─── sms_messages / calls ◀──RPC─────── ├─ SmsManager (radio)
                                                                     ├─ TelecomManager (dial/answer/end)
live audio  ◀═══ WebRTC (calls_events signaling) ════════════════════┘─ speakerphone + mic
```

- **SMS**: browser queues a `send_sms` command → phone sends via the SIM → radio confirmation → status back. Incoming SMS on the SIM appear in the SMS tab in real time.
- **Calls**: browser queues a `dial_call` command → phone dials the real number → phone opens a WebRTC audio bridge → you (or the AI) talk **through the SIM call acoustically** (speakerphone + hardware echo cancellation). Incoming cellular calls show a banner; one click bridges them to the browser.
- **Free + international**: the SMS/call is placed by your SIM on your plan. The only server is Supabase (free tier), used purely as a message board.

## Honest limitations (read before relying on it)

| Limitation | Why | Workaround |
|---|---|---|
| Phone must stay online + charged | It *is* the gateway | Keep on charger, disable battery optimization for the app |
| OEM task killers (Xiaomi, Huawei…) | Aggressive Doze | "Lock" the app in recents / autostart whitelist |
| Audio bridge is acoustic | No-root phones can't inject audio into the GSM stream | Speakerphone + AEC works well in practice; keep phone near mic-free area |
| AI voice → PSTN caller quality | Speaker-to-mic path | Hardware AEC handles it; avoid loud environments |
| End call on Android 8/9 | `TelecomManager.endCall()` needs API 28 | Best-effort headset-hook; Android 10+ fully supported |
| One SIM per phone | SMS/call rides the default SIM | Pair multiple phones for multiple numbers |

---

## Part A — Database (already applied to the project's Supabase)

If you're self-hosting a fresh Supabase project, run these in order (SQL editor or `psql`):

1. `packages/db/src/migrations/006_gateway_devices.sql` — tables: `gateway_devices`, `gateway_commands`, `sms_messages`
2. `packages/db/src/migrations/006b_gateway_rpc.sql` — device RPCs + `send_sms` + `update_gateway_call`
3. `packages/db/src/migrations/006c_gateway_signaling.sql` — durable WebRTC signaling (`gateway_post_signal`, `gateway_get_signals`) + `answer_call` / `end_call` commands + `join_gateway_room`
4. `packages/db/src/migrations/006d_gateway_pair_rpc.sql` — `register_gateway_device` (phone-side pairing with a 6-digit code)

Then point the Android app at your project: edit `SUPABASE_URL` / `SUPABASE_ANON_KEY` in
`apps/android-gateway/app/src/main/java/app/opencall/gateway/Rpc.kt`.

## Part B — Web app (already live)

Open **https://devso3939.github.io/AI-Call-software/app.html**

- **Devices tab** → *Create pairing code* → shows a 6-digit code (valid ~10 min)
- **SMS tab** → pick a paired device → send SMS, watch replies live
- **Dialer** → pick the *Gateway (free, your SIM)* route when a device is online; otherwise it falls back to Twilio
- **Incoming calls** to your SIM → banner in the app → *Bridge to browser* / *answer on phone* / *reject*

## Part C — Android app

### Get the APK
Two ways:

1. **Download the built APK**: repo → **Actions** → *android-gateway-apk* → latest run → artifact `opencall-gateway-debug-apk`
2. **Build yourself**: Android Studio → open `apps/android-gateway/` → Run. Or `gradle :app:assembleDebug`.

### Pair the phone
1. Install the APK → open **OpenCall Gateway**
2. Grant **all** permissions (SMS ×3, phone ×3, mic, notifications) — the app requests them up front
3. Web app → **Devices** → *Create pairing code*
4. Type the 6-digit code in the app → **Pair this phone** → the gateway service starts automatically
5. The device shows **online** in the web app within ~5 seconds

### Keep it alive
- Disable battery optimization: Settings → Apps → OpenCall Gateway → Battery → **Unrestricted**
- Samsung/Xiaomi/Huawei: also enable *autostart* + *lock in recents*
- Keep the phone on Wi-Fi (data is tiny — a few KB/min) + on a charger

---

## Using it

### Send SMS (free, your number)
Web app → SMS tab → compose. Or from code:

```js
await sb.rpc('send_sms', { p_device_id: '<deviceId>', p_to: '+995...', p_body: 'Hello' });
// status: queued → sent (radio confirm) | failed (error string)
// incoming SMS land in sms_messages via realtime
```

### Make a call (free, your number)
Dialer → Gateway route. Under the hood: `dial_call` command `{callId, to, room}` → phone dials → WebRTC bridge opens → your browser mic goes through the SIM call.

### AI agent makes/takes calls
The AI connects to the same browser-side WebRTC role — no special path. `request_gateway_call` for outbound, bridge-in for inbound. AI talks, human caller hears the SIM call audio as if the phone speaker were the agent.

### Incoming SMS/calls
SMS: arrives in the SMS tab automatically (realtime).
Calls: banner appears → bridge (audio to browser/AI), answer on phone only, or reject (declines via `end_call`).

---

## RPC contract (for extending)

| RPC | Purpose |
|---|---|
| `register_gateway_device(p_code, p_name, p_sim_number)` | phone-side pairing → `{deviceId, deviceSecret}` |
| `list_gateway_devices()` / `remove_gateway_device(p_device_id)` | web-side fleet management |
| `gateway_heartbeat(p_device_id, p_secret, p_sim_number, p_battery, p_app_version)` | online status (90 s timeout) |
| `gateway_fetch_commands(p_device_id, p_secret, p_limit)` → **jsonb array** | claim commands (`FOR UPDATE SKIP LOCKED`) |
| `gateway_complete_command(p_device_id, p_secret, p_command_id, p_ok, p_result)` | ack |
| `send_sms(p_device_id, p_to, p_body)` | queue an SMS |
| `gateway_report_sms(...)` / `gateway_receive_sms(...)` | phone → server status / inbound |
| `request_gateway_call(p_device_id, p_to)` | queue a dial → `{callId, room}` |
| `update_gateway_call(..., p_state)` | dialing / ringing / answered / ended / failed |
| `report_incoming_call(p_device_id, p_secret, p_from)` | inbound ringing → `{callId, room}` |
| `gateway_post_signal` / `gateway_get_signals` | durable WebRTC signaling rows |
| `join_gateway_room(p_device_id, p_secret, p_code, p_room)` | anonymous browser joins a gateway room |

**WebRTC roles (glare-free by construction):**
- Outbound gateway call: **browser offers → phone answers**
- Inbound bridge: **phone offers → browser answers**

## Security model
- `deviceSecret` is generated server-side at pairing, lives **only** in EncryptedSharedPreferences on the phone, and authenticates every device RPC.
- Pairing codes are single-use + short-lived; the phone registers itself (no credentials in transit besides the code).
- Browser join for gateway rooms is scoped via a one-time code path (`join_gateway_room`).
- All browser↔Supabase access uses the publishable key + RLS; device RPCs verify the secret server-side.
