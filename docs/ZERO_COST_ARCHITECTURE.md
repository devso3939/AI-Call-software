# OpenCall — Zero-Cost Architecture

**Goal:** keep the app fully functional at $0 or near-$0 running cost.

**Decision:** option 2 — the SIM gateway stays the default route for phone
numbers. Twilio is never on the critical path. This document defines the
tier structure, the per-tier cost, the two zero-cost upgrades available to
the gateway, and the limits that money cannot buy away.

---

## 1. What "$0" actually means

Per-minute call cost is not the only cost. The full bill for a
self-hosted OpenCall deployment:

| Component | Provider | Free allowance | Cost when exceeded | Risk |
|---|---|---|---|---|
| Web app hosting | GitHub Pages | unlimited static | — | none |
| Database + auth + realtime | Supabase Free | 500 MB DB, 50k MAU | $25/mo Pro | low |
| Recording storage | Supabase Free | 1 GB | $0.021/GB/mo | **needs retention policy** |
| Recording playback | Supabase Free | 5 GB egress/mo | $0.09/GB | **needs retention policy** |
| Signalling | Supabase Realtime | included | — | none |
| STUN | Google public STUN | unlimited | — | none |
| **TURN relay** | not yet configured | — | — | **real gap, see §6** |
| On-net call minutes | WebRTC peer-to-peer | unlimited | — | none |
| Phone call minutes | your own SIM | your existing plan | your plan's rate | none marginal |
| Android gateway | your own handset | — | — | none |

Two line items are the actual threats to $0, and neither is per-minute:
**recording storage/egress** and **TURN relay**. Both are addressed in §6.

Confidence note: the Supabase allowances above are from the Free plan as I
understand it; verify against your project's billing page before relying
on the headroom numbers.

---

## 2. The tier structure

```
┌─ TIER 0 ─ ON-NET ──────────────────────── $0.00/min ── unlimited ──┐
│  OpenCall user ↔ OpenCall user      (joinRoom, WebRTC peer)        │
│  OpenCall user ↔ anyone via link    (create_guest_link)            │
│                                                                    │
│  Browser mic + browser speaker. Web dialer, web mute, web hangup.  │
│  Two-way recording. ALREADY BUILT AND WORKING.                     │
└────────────────────────────────────────────────────────────────────┘
┌─ TIER 1 ─ SIM GATEWAY ─────────────────── $0.00/min marginal ──────┐
│  OpenCall user ↔ any phone number   (your Android + your SIM)      │
│                                                                    │
│  Today:  acoustic bridge — phone speaker → air → phone mic.        │
│  Upgrade 1a: claim the dialer role  → native dialer never appears. │
│  Upgrade 1b: Bluetooth HFP bridge   → digital audio on the laptop. │
└────────────────────────────────────────────────────────────────────┘
┌─ TIER 2 ─ BRING YOUR OWN CARRIER ──── $0.00/min TO THE OPERATOR ───┐
│  Dark by default. Each tenant supplies their own Twilio account    │
│  or SIP trunk and pays their own bill. Operator cost stays $0.     │
└────────────────────────────────────────────────────────────────────┘
```

The reframe that matters: **every one of your three original
requirements is already satisfied on Tier 0 today.** Browser-side audio,
browser-side control, two-way recording — all of it works when both ends
are on the internet. The bug you have been chasing for several iterations
exists only on Tier 1, because Tier 1 crosses into the cellular network,
and that crossing is where Android stops cooperating.

So the zero-cost strategy is not "make Tier 1 behave like Tier 0 by
buying something." It is: push as much traffic as possible onto Tier 0,
and close the Tier 1 gap with the two upgrades below, both of which cost
nothing.

---

## 3. Upgrade 1a — claim the dialer role (fixes the visible complaint)

**Cost: $0. This is the single highest-value change in the document.**

Your complaint: *"it opens in my phone my phone app and calls from my
phone app like i would clla."*

The code today is fighting the platform instead of asking it for
permission. `MainActivity.kt:236-243` already requests `ROLE_DIALER`:

```kotlin
val rm = getSystemService(android.app.role.RoleManager::class.java)
if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_DIALER) &&
    !rm.isRoleHeld(RoleManager.ROLE_DIALER)) {
    startActivity(rm.createRequestRoleIntent(RoleManager.ROLE_DIALER))
```

But the manifest then declines the in-call UI:

```xml
<!-- src/full/AndroidManifest.xml:104 -->
<meta-data android:name="android.telecom.IN_CALL_SERVICE_UI" android:value="false" />
```

The comment above it says the stock dialer "is auto-minimized by
FullCallService itself." That auto-minimizing is `suppressLoop`: a thread
firing a Home intent every 100 ms, up to 300 times, plus a `CallMask`
overlay. It is a race against the system UI, and races get lost — which
is exactly why the dialer still flashes on your screen.

**The fix:** flip that flag to `true` and ship a real (invisible) in-call
Activity. When the app holds `ROLE_DIALER` *and* declares
`IN_CALL_SERVICE_UI = true`, Android hands it the in-call screen and
**never shows the stock dialer at all.** The suppression hack becomes
dead code and gets deleted.

| | Today | After 1a |
|---|---|---|
| Native dialer | flashes, sometimes stays | never appears |
| Suppression | 300 Home intents + overlay | deleted |
| `SYSTEM_ALERT_WINDOW` | required | no longer needed |
| Web-side control | works (InCallService) | unchanged |
| Reliability | racy under load | deterministic |
| Cost | $0 | $0 |

Scope: `AndroidManifest.xml` (1 line + new Activity), a new
`InCallActivity.kt` (~60 lines, no visible UI, finishes immediately),
`FullCallService.kt` (delete `suppressLoop` / `dismissInCallUi`, ~40
lines removed), `MainActivity.kt` (make the role request mandatory in
setup, not optional).

Caveat worth stating: holding `ROLE_DIALER` means OpenCall Gateway
becomes the phone's default phone app. Calls the user dials manually will
also route through it. Since the in-call Activity is invisible, that
handset needs to be a dedicated gateway device, not the user's daily
driver. This is a real behavioural change and needs your sign-off.

---

## 4. Upgrade 1b — Bluetooth HFP bridge (fixes audio and recording, $0)

**Cost: $0. This is the one that gets the far end's voice into the
browser without paying a carrier.**

Your complaints: *"calls happens on phoen side muicrophone and speaker"*
and *"on recorded call i hear only y voice not who i call."*

In my previous report I said browser-side cellular audio was impossible
without a paid carrier leg. That was too strong, and I want to correct
it. It is impossible *through Android's audio APIs* —
`CAPTURE_AUDIO_OUTPUT` and `AudioSource.VOICE_CALL` are privileged-only
since Android 9, that part stands. But there is a second path off the
handset that is not an Android API at all: **Bluetooth Hands-Free
Profile.**

HFP exists precisely to move cellular call audio off a phone. In HFP
terms the phone is the Audio Gateway and another device is the Hands-Free
unit. Car kits and headsets have used it for twenty years. Windows
implements the Hands-Free unit side, and Phone Link uses exactly this to
place and take cellular calls on a PC using the PC's mic and speakers.

The key property: when Windows is paired to a phone as a hands-free
device, it exposes **"Hands-Free AG Audio"** endpoints — a playback
device carrying the far end's voice, and a recording device that feeds
the cellular uplink. Both are ordinary Windows audio devices. Which means
`navigator.mediaDevices.enumerateDevices()` sees them, and
`getUserMedia({ deviceId })` can select them.

That is the whole trick. No Android API, no carrier, no per-minute fee.

### The routing

```
                      cellular network
                             │
                    ┌────────▼────────┐
                    │  Android phone  │  Audio Gateway (HFP)
                    │  (your SIM)     │  screen off, in pocket
                    └────────┬────────┘
                             │  Bluetooth SCO — DIGITAL
                    ┌────────▼──────────────────────┐
                    │  Laptop — Hands-Free unit     │
                    │  "Hands-Free AG Audio" in/out │
                    └────────┬──────────────────────┘
                             │  getUserMedia({ deviceId })
                    ┌────────▼──────────────────────┐
                    │  Browser                      │
                    │  far end → remoteAudio → rec  │
                    │  your mic → HFP → uplink      │
                    └───────────────────────────────┘
```

Compare against today's acoustic bridge, where the signal path is
`far end → phone loudspeaker → air → phone mic → WebRTC → browser`. The
HFP path deletes the air gap entirely.

### What this fixes

| Requirement | Acoustic (today) | HFP bridge |
|---|---|---|
| Far end audible in browser | via phone speaker + air | **digital, direct** |
| Your voice to far end | laptop speaker → air → phone mic | **digital, direct** |
| Phone speaker/mic used | yes, load-bearing | **no, phone can be silent** |
| Echo | AEC disabled on purpose | normal AEC works |
| Two-way recording | one-sided | **both sides** |
| Web dialer / mute / hangup | works | works |
| Cost | $0 | **$0** |

`CallRecorder.start()` bails on `if (!remoteAudio.srcObject) return
false;`. Under HFP, `remoteAudio.srcObject` is a real stream containing
the far end. The recorder graph in `app.html:440-584` is already correct
and needs no changes — it starts capturing both sides the moment it has a
genuine remote stream. The v1.5.11 watchdog stops firing on its own.

### Honest limits of 1b

- **Audio quality is telephone-grade.** HFP is mono, 8 kHz (or 16 kHz
  with mSBC). That is the same bandwidth the cellular call already has,
  so nothing is lost relative to the call itself — but do not expect it
  to sound like Tier 0's Opus.
- **Pairing is manual, once per laptop.** The user pairs phone to laptop
  and enables the hands-free/telephony service. This is OS setup, not
  something the app can do for them, so it needs a clear guided flow.
- **Device selection needs a UI.** Chrome will not guess the right
  endpoint. The app has to enumerate devices, find the HFP pair, and let
  the user confirm. Device labels require microphone permission to be
  granted first.
- **Windows and desktop Linux (PipeWire/PulseAudio) support this.**
  macOS does not expose HFP endpoints the same way; Macs would fall back
  to the acoustic bridge or Tier 0.
- **Bluetooth range.** Phone must stay near the laptop. Fine for a desk
  setup, which is the target.
- **I have not yet tested this on your hardware.** The mechanism is
  well-established and Phone Link proves the OS plumbing exists, but
  whether *your* phone and *your* laptop negotiate HFP cleanly and expose
  a working recording endpoint is an empirical question. This needs a
  10-minute probe before we commit code to it (see §8).

Because of that last point, 1b is designed as an **automatic upgrade with
fallback**: if the app detects usable HFP endpoints, it uses them; if
not, it drops to the acoustic bridge exactly as it works today. No
regression risk.

---

## 5. Tier 2 — bring your own carrier, and what the Twilio docs give us

You asked me to use the `<Dial><Application>` usage docs. Here is what
they actually contain and how it maps onto a zero-cost operator model.

### Twilio's real prices (per minute, USD)

| Leg type | Price | Notes |
|---|---|---|
| Browser/app Client leg | **$0.0040** | cheapest way into a browser |
| Intra-Twilio leg, conference, transfer | **~$0.002** | account-to-account |
| US outbound to a phone number | $0.0140 | the expensive part |
| US inbound to your number | $0.0085 | |
| Phone number rental | ~$1.15/mo | fixed, unavoidable |
| Trial account | free | **75 total voice minutes, then dead** |

A 10-minute call to a US number, browser to phone, costs roughly
$0.014 + $0.004 = $0.18. Not much per call — but it is not $0, it is a
recurring bill with a phone number rental floor, and it scales with
usage. That is why Tier 2 must never be operator-funded.

### What `<Dial><Application>` is genuinely useful for

The mechanics from the docs:

- `<Dial><Application><ApplicationSid>AP…</ApplicationSid></Application></Dial>`
  connects a call into another Twilio account's TwiML App **while
  preserving the original leg's context** — the callee still sees the
  original `From`, not a relay number.
- `<Parameter name="…" value="…"/>` passes custom data, arriving on the
  far side prefixed `Param_`.
- `<Hangup><Parameter>` / `<Reject><Parameter>` return values to the
  originating `<Dial>`'s `action` URL as `ReturnParam_*`.
- `copyParentTo` (default `false`) controls whether `To` is the parent
  call's `To` or the dialed TwiML App SID. `customerId` (≤256 chars,
  defaults to the Account SID) tags the caller.
- Cross-account dialing requires `PublicApplicationConnectEnabled = true`
  on the Application resource.
- **Not supported:** simultaneous dialing, and REFER via `referUrl`.

**The architectural insight for a $0 operator:** `<Dial><Application>`
is the mechanism for handing a call *between separate Twilio accounts
without losing call context, at intra-Twilio rates (~$0.002/min) rather
than re-originating a full PSTN leg.* That is exactly the primitive a
multi-tenant deployment needs if each tenant owns their own Twilio
account. OpenCall's TwiML App becomes a routing hop; the tenant's account
pays for the expensive PSTN leg on their own bill.

Twilio's own security warning, which I am passing on verbatim in
substance: enabling `PublicApplicationConnectEnabled` opens your TwiML
App to inbound calls from **any Twilio customer**. Twilio does not
authenticate them for you. If Tier 2 is ever built, every inbound
application call must be authenticated against a shared secret passed as
a `<Parameter>` and rejected with `<Reject><Parameter>` otherwise. Do not
enable that flag until that check exists.

### Tier 2 design rule

Tier 2 stays **dark by default and credential-less by design**:

- No OpenCall-owned Twilio account, ever. No shared pool of minutes.
- Each tenant pastes their own Account SID / API key into
  `private.twilio_config`, encrypted, scoped to their row.
- `place_call` already gates on this. `007_regression_fixes.sql:42`:
  ```sql
  if not exists (select 1 from private.twilio_config where id = 1) then
    raise exception 'PSTN is not configured yet — phone dialing is disabled in this deployment';
  ```
  That guard is correct and stays. Note `id = 1` is single-tenant; a
  multi-tenant version needs a per-account row.
- The five missing pieces (`private.twilio_config`, `pstn_configured`,
  `pstn_caller_id`, `twilio_voice_token`, `update_pstn_call`) get written
  **only if you ask for Tier 2.** Until then the client code in
  `app.html:750-869` stays dark, `setupPstn()` keeps swallowing its error,
  and the operator bill stays $0.

Given upgrade 1b, my recommendation is to **not build Tier 2 at all for
now.** It exists in this document as a documented escape hatch, not a
roadmap item. Build it when a tenant asks and pays.

---

## 6. The two things that actually threaten $0

Per-minute cost is solved. These two are not, and both are more urgent
than Tier 2.

### 6a. TURN relay — ✅ already fixed in v1.5.11

This section is retained for the cost analysis only. The functional gap
it described **no longer exists**: v1.5.11 (commit `c9bff61`) wired the
Open Relay TURN service into both WebRTC sides —

- Browser (`apps/web/public/app.html:1474-1501`): `buildRtcConfig()`
  derives coturn REST-style static-auth credentials locally
  (username = expiry epoch, credential = HMAC-SHA1 of the shared
  secret), and adds `turn:standard.relay.metered.ca:80`
  udp+tcp plus `turns:…:443` to the Google STUN pair.
- Android gateway (`WebRtcBridge.kt:63-88`): the same relay added to the
  native `PeerConnection` ICE server list.

Cost analysis of what is now live: the "Open Relay Project" shared
credentials are free, with metered.ca's published free relay allowance
(~20 GB/mo across their open credentials). Relay media is only used
when P2P fails, so typical consumption stays far below that. Watch two
residual risks:

- **Shared open credentials** mean anyone on the internet can relay
  through them; we consume nothing extra, but there is no per-deployment
  isolation. If abuse ever becomes a problem, the swap to a private
  metered.ca key or self-hosted coturn is a single constant per side
  (`OPENRELAY_SECRET` / `TURN_SECRET`).
- **Hard ceiling:** if usage ever outgrows the free allowance, the
  fallback ladder is the same one below — private free key, then coturn
  on Oracle Always Free, then a $4/mo VPS.

Reference ladder if the free tier is ever exhausted:

| Option | Cost | Notes |
|---|---|---|
| Open Relay shared creds (current) | $0 | live in v1.5.11 |
| Private metered.ca free key | $0 | 20 GB/mo, signup required |
| Self-host coturn on a free-tier VPS | ~$0 | Oracle Cloud Always Free; needs ops |
| coturn on a $4/mo VPS | $4/mo | predictable, full control |
| Twilio Network Traversal | $0.40/GB | expensive at volume |

### 6b. Recording storage and egress

Supabase Free gives 1 GB storage and 5 GB/mo egress. At the current
encoder setting — `new lamejs.Mp3Encoder(1, sampleRate, 64)`, mono
64 kbps — that is about **0.48 MB/min**, so roughly **35 hours** of
recordings before the storage cap. No retention policy exists. The
deployment will quietly hit the cap and start failing uploads.

Zero-cost fixes:

- Drop the encoder to 32 kbps mono. Speech at telephone bandwidth loses
  nothing meaningful and it **doubles capacity to ~70 hours**. One-line
  change.
- Add a retention job: auto-delete recordings older than N days unless
  flagged. Runs as a scheduled Postgres function, costs nothing.
- Show a storage-usage meter in the UI so the cap is never a surprise.
- Offer "download and delete" so users can keep archives locally.

---

## 7. Requirement scorecard, honestly

Your three original requirements, per tier, after the upgrades:

| Requirement | Tier 0 today | Tier 1 today | Tier 1 + 1a + 1b |
|---|---|---|---|
| Web dialer, no phone UI | ✅ | ❌ dialer flashes | ✅ **1a** |
| Browser mic + speaker | ✅ | ❌ phone's | ✅ **1b** (8/16 kHz) |
| Web mute / hangup | ✅ | ✅ already works | ✅ |
| Both voices recorded | ✅ | ❌ one-sided | ✅ **1b** |
| Cost | $0 | $0 | **$0** |

What remains permanently out of reach on the free path:

- **Cellular audio never becomes Opus-quality.** HFP is 8/16 kHz mono.
  The cellular call itself is narrowband anyway, so this is a ceiling
  imposed by the phone network, not by us.
- **The phone must be present, paired, and in range.** Tier 1 is a
  desk-bound architecture. A carrier leg (Tier 2) is the only way to make
  phone calls work with no hardware attached.
- **macOS gets no HFP path** and stays on the acoustic bridge.
- **`CAPTURE_AUDIO_OUTPUT` / `AudioSource.VOICE_CALL` remain closed.**
  1b routes *around* that restriction via Bluetooth; it does not defeat
  it. If HFP is unavailable, there is no third option.

---

## 8. Recommended order of work

1. **Probe HFP on your actual hardware** (~10 min, no code committed).
   Pair phone to laptop with the telephony/hands-free service enabled,
   place a call, then in the browser console check
   `enumerateDevices()` for a Hands-Free AG recording endpoint and verify
   `getUserMedia` on it carries the far end's voice. **Everything in 1b
   depends on this passing.** If it fails, we stop and rethink.
2. **Upgrade 1a — dialer role.** Independent of the probe, low risk, kills
   the most visible complaint. Needs your OK on the default-phone-app
   behavioural change.
3. **Upgrade 1b — HFP bridge** with automatic fallback to the acoustic
   bridge. Only if step 1 passes.
4. **Recording retention + 32 kbps.** Protects the $0 storage budget.
5. **Push users toward Tier 0.** Make guest links the prominent path —
   `create_guest_link` already exists and is genuinely free, unlimited,
   full quality, and fully browser-native. Every call that lands here is
   a call with none of the above problems.
6. **Tier 2.** Not now. Only on demand, tenant-funded.

Nothing in steps 1-6 adds a cent of running cost.

---

## 9. Decisions I need from you

- **Dedicated gateway handset?** 1a makes OpenCall the phone's default
  phone app. Acceptable, or does that phone need to stay usable normally?
- **Run the HFP probe?** It is the gate on the audio and recording fix.
  The probe page is ready — I just need you to pair the phone and run it
  once (link below).
- **Order:** 1a first (visible fix, certain to work) or 1b first (the
  audio fix you have been asking for, contingent on the probe)?
- **Tier 2:** confirm we leave it dark.

Nothing else blocks implementation. TURN is already live (v1.5.11),
recording retention is a safe standalone change, and both can proceed
in parallel with your answers to the two questions above.

