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

## IN PROGRESS

- Phase 4: AI voice agents (agent-runtime scaffold committed: Silero VAD → faster-whisper STT → local OpenAI-compat LLM → Kokoro TTS pipeline, provider interfaces, LiveKit agent participant; needs local model weights + E2E call test)

## BLOCKED — EXTERNAL CREDENTIAL REQUIRED

- Phase 6: external SIP interoperability tests (needs a real SIP endpoint/account; §155 honesty rule)
- Phase 7: PSTN trunk live test (needs legitimate carrier credentials; PSTN_ENABLED=false by default)
- GitHub push (user action when desired — repo initialized with logical commits)

## NEXT

- Acceptance test #1 (master prompt): two browser profiles, alice → bob real two-way microphone audio verification (call signaling path already E2E-verified; remaining: audible-audio check with two browsers)
- Phase 4 continued: run agent-runtime with local models (faster-whisper, Silero, Kokoro), agent E2E call test
- Phase 5: API keys, webhooks, TypeScript SDK
- Playwright E2E suite (manual browser E2E currently green)
