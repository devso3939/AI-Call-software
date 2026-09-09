# STATUS — OpenCall AI

Legend (Master Prompt §119): a feature enters **DONE** only after a passing test, never because code exists.

## DONE

- (bootstrap in progress — nothing verified yet)

## IN PROGRESS

- Phase 0: monorepo bootstrap, core packages, API, auth, health checks
- Phase 1: LiveKit realtime calling (browser↔browser two-way audio)
- Phase 2: contacts, call records/history
- Phase 3: routing engine (on-net / SIP / no-route classification)
- Guest call links
- apps/web UI (login, dialer, incoming call, active call, history, contacts)
- start-dev one-click scripts

## BLOCKED — EXTERNAL CREDENTIAL REQUIRED

- Phase 6: external SIP interoperability tests (needs a real SIP endpoint/account; §155 honesty rule)
- Phase 7: PSTN trunk live test (needs legitimate carrier credentials; PSTN_ENABLED=false by default)
- GitHub push (gh CLI not installed; user action when desired)

## NEXT

- Phase 4: AI voice agents (agent-runtime: Silero VAD → faster-whisper STT → local LLM → Kokoro TTS, barge-in, transcripts)
- Phase 5: API keys, webhooks, TypeScript SDK
- Playwright E2E suite
