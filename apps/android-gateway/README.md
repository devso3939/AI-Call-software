# OpenCall SIM Gateway (Android)

Turns any spare Android phone + its SIM into a **free, international SMS + voice gateway**
for your OpenCall web app. Same philosophy as android-sms-gateway / sms-gate.app —
but unified with calls, and wired into OpenCall's own backend.

- **SMS**: web app → `send_sms` RPC → command queue → this phone sends via `SmsManager`
  (your SIM, your number, your plan rates — free where you have free SMS).
- **Calls (outbound)**: web app → `request_gateway_call` → `dial_call` command → this phone
  dials the real number over the cellular network (your caller ID) and opens a WebRTC audio
  bridge so the browser/AI can talk through the call.
- **Calls (inbound)**: real cellular call arrives → this phone reports it → the web app
  shows an "Incoming call to your SIM" banner (answer on the phone, or open the audio bridge).
- **Audio bridge** is *acoustic*: the cellular call is put on SPEAKERPHONE and this app joins
  the same WebRTC room (mic + speaker, hardware AEC). No root, no extra SIM apps.

## Files

```
apps/android-gateway/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── gradle/wrapper/gradle-wrapper.properties
└── app/
    ├── build.gradle.kts          (okhttp, stream-webrtc-android, security-crypto)
    ├── proguard-rules.pro        (keeps org.webrtc.** for JNI)
    └── src/main/
        ├── AndroidManifest.xml   (SMS + phone + mic + FGS dataSync permissions)
        ├── java/app/opencall/gateway/
        │   ├── MainActivity.kt      pairing UI (6-digit code), status, start/stop
        │   ├── GatewayService.kt    foreground service: heartbeat + command poll
        │   ├── WebRtcBridge.kt      audio bridge (offer/answer/ice via calls_events)
        │   ├── CallControl.kt       dial / answer / end cellular + speakerphone
        │   ├── IncomingCallReceiver PHONE_STATE → report_incoming_call RPC
        │   ├── SmsSender.kt         SmsManager + gateway_report_sms
        │   ├── SmsReceiver.kt       SMS_RECEIVED → gateway_receive_sms
        │   ├── Rpc.kt               PostgREST RPC client (publishable key)
        │   └── DeviceStore.kt       EncryptedSharedPreferences (deviceId + secret)
        └── res/                     strings, theme, adaptive icon
```

## Build

**No local SDK needed** — GitHub Actions builds the APK on every push
(`.github/workflows/android-gateway.yml`) and uploads it as the artifact
`opencall-gateway-debug-apk`. Download from the repo's *Actions* tab, sideload,
allow "install unknown apps".

Local build: `gradle :app:assembleDebug` inside `apps/android-gateway/` (JDK 17, Android SDK 35).

## Pairing (one time)

1. Web app → **Devices** tab → *Create pairing code* → 6 digits appear.
2. Open this app on the Android phone → type the code → **Pair**.
   The app calls `register_gateway_device` (anon RPC) and stores
   `deviceId` + `deviceSecret` in EncryptedSharedPreferences. The secret
   never leaves the phone again.
3. Tap **Start gateway**. The foreground notification keeps it alive.

## How it works (backend contract)

All RPCs are PostgREST calls to `https://<project>.supabase.co/rest/v1/rpc/<fn>`
with the publishable key. Device identity = `(deviceId, deviceSecret)`.

| RPC | Used for |
|---|---|
| `register_gateway_device(code, name, sim)` | one-time pairing |
| `gateway_heartbeat(device, secret, battery, ver)` | mark online (90 s timeout) |
| `gateway_fetch_commands(device, secret, limit)` | claim queued commands (SKIP LOCKED) |
| `gateway_complete_command(device, secret, id, ok, result)` | report outcome |
| `send_sms` (web) → `send_sms` command `{smsId, to, body}` | outbound SMS |
| `gateway_report_sms(device, secret, smsId, status, err)` | SMS send result |
| `gateway_receive_sms(device, secret, from, body)` | inbound SMS → web tab |
| `request_gateway_call` (web) → `dial_call` `{callId, to, room, answerAfter}` | outbound call |
| `update_gateway_call(device, secret, callId, state)` | dialing/ringing/answered/ended/failed |
| `report_incoming_call(device, secret, from)` → `{callId, room}` | inbound cellular call |
| `queue_answer_call` / `queue_end_call` (web) → `answer_call`/`end_call` commands | accept/decline from web |
| `join_gateway_room(device, secret, code, room)` | reserve signaling room |
| `gateway_post_signal` / `gateway_get_signals` | WebRTC signaling (calls_events) |

Signaling directions: **browser posts `offer`**, phone posts `answer`; both post `ice`;
either posts `bye`. Phone's posts use the zero-uuid sender so the browser never sees
its own echo as a remote event.

## Limitations (honest)

- Battery optimizations: exempt the app (Settings → Battery → Unrestricted) or Android
  will kill the poll loop; the foreground notification helps but OEM task killers
  (Xiaomi, Huawei) need manual allowing.
- `TelecomManager.endCall()` requires API 28+; on API 26/27 the app falls back to a
  headset-hook key event (best effort).
- The audio bridge is acoustic (speaker↔mic). Expect the far side to hear a slight
  echo of themselves in loud environments; hardware AEC handles most of it.
- Dual-SIM: commands use the default data/SMS SIM; per-slot routing is a future feature.
- Some carriers block SmsManager for premium numbers; that's carrier policy, not a bug.
