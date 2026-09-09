# STATUS — OpenCall AI

Legend (Master Prompt §119): a feature enters **DONE** only after a passing test, never because code exists.

## DONE (each verified by an executed test, not by code existing)

- Monorepo bootstrap: npm workspaces, tsconfig base, .env loader, one-click `start-dev` scripts (bat/ps1) with health checks
- Core packages: `types` (call state machine + ALLOWED_TRANSITIONS), `db` (SQLite embedded / Postgres-portable schema, migration runner), `eventbus`, `auth` (argon2id password hashing, JWT sessions), `livekit-tokens` (JWT room tokens), `routing` (ON_NET / SIP / FEDERATION / PSTN / NO_ROUTE classification)
- Auth REST: register, login, me — verified via REST (alice/bob/carol/erin accounts)
- Calls REST: create (route resolution + LiveKit room + join token), answer, hangup, list, detail — verified via REST and real UI
- Call state machine: created → ringing → active → completed/cancelled/failed with server-authoritative transitions — verified (ringing→answered→active→completed events recorded)
- WebSocket signaling with `?token=` auth: no token → `auth.failed` + close; bad token → `auth.failed` + close; valid token → `auth.ok` — verified with Node 22 native WebSocket probes
- LiveKit server (official binary, dev mode) deployed and connected by browser clients (livekit-client v1.13.6 signal connected)
- apps/web: register/login, dialer with live route estimates, incoming call panel (WS push), active call panel with real microphone audio path, mute, end, history, contacts
- Browser E2E (integrated browser, real UI): register "carol" → dialer estimate → call alice → FREE ON-NET badge → Connecting/Ringing → mute toggle → end → history recorded (`completed | term=hangup`)
- Incoming-call E2E: REST call from alice while bob logged in in browser → "Incoming call from Alice" panel appeared in real time → Accept → server status `active` with `call.answered`/`call.active` events → callee End → `completed | term=hangup | duration=19`
- Per-user privacy scoping (§12): `listCalls` filters by caller OR callee leg; `getCallDetail` enforces participant check (cross-user read → 403) — regression-tested after fix (Carol no longer sees Alice/Bob calls)
- Hangup bug fixed end-to-end: bodyless POST with JSON content-type no longer 415/500 (lenient JSON parser + structured `err.statusCode` propagation + web `api()` helper sets content-type only when body present) — verified via REST and full UI round-trip
- Guest call links (§39): HMAC-signed short-lived tokens, create → redeem → browser join verified end-to-end ("Carol will join shortly — you are in the room"); earlier 403 root-caused to a manually truncated token (29 vs 43-char signature), not a code bug
- Health checks: `/health` and `/ready` on API; Next.js web; LiveKit reachable
- **Acceptance test #1 (master prompt) — PASSED**: two browser profiles, alice (localhost:3001) → bob (localhost:3000), real two-way microphone audio. Evidence, 2026-09-09:
  - LiveKit server-side (`RoomServiceClient.listParticipants`): both participants `state=2` (active) with one unmuted mic track each (`type=0, mime=audio/red`): Alice `TR_AMBfmRyimDEMSp`, Bob `TR_AMwZFQH8EEGR3T`
  - Client-side subscriptions (both tabs): `remoteParticipants[other].audioTrackPublications[0] → { isSubscribed: true, hasTrack: true }`, local mic published with live track
  - RTP stats (RTCPeerConnection.getStats, audio): Alice 12,200 sent / 13,335 received packets (~2.9 MB / ~3.3 MB); Bob 14,126 sent / 11,170 received (~3.5 MB / ~2.7 MB); 0 packets lost both directions
  - WebAudio AnalyserNode on subscribed remote track: non-zero waveform in BOTH directions (Alice←Bob rms 0.0088 / peak 0.019; Bob←Alice rms 0.0143 / peak 0.0265)
  - UI: both sides "In call · quality: excellent" (RTT 0–1 ms); DB call `call_mttqhv8iirv54qcq` route_type=ON_NET, status=active with answered_at set

## IN PROGRESS

- Phase 4: AI voice agents (agent-runtime scaffold committed: Silero VAD → faster-whisper STT → local OpenAI-compat LLM → Kokoro TTS pipeline, provider interfaces, LiveKit agent participant; needs local model weights + E2E call test)

## BLOCKED — EXTERNAL CREDENTIAL REQUIRED

- Phase 6: external SIP interoperability tests (needs a real SIP endpoint/account; §155 honesty rule)
- Phase 7: PSTN trunk live test (needs legitimate carrier credentials; PSTN_ENABLED=false by default)
- GitHub push (user action when desired — repo initialized with logical commits)

## NEXT

- Phase 4 continued: run agent-runtime with local models (faster-whisper, Silero, Kokoro), agent E2E call test
- Phase 5: API keys, webhooks, TypeScript SDK
- Playwright E2E suite (manual browser E2E currently green)

## KNOWN ENVIRONMENT LIMITATIONS (not OpenCall code defects)

- **Chrome on this Windows host hangs `getUserMedia` on the `127.0.0.1` origin** (mic permission granted; killing/respawning Chrome's `audio.mojom.AudioService` does not help; same tab works instantly on `localhost`). Dev/test consequence: run the second browser profile against `localhost:3001` instead of `127.0.0.1:3000` (`node node_modules/next/dist/bin/next dev -p 3001` from `apps/web`). No OpenCall code change required; production uses real hostnames over HTTPS where this host quirk does not apply.
