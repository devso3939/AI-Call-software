# IMPLEMENTATION PLAN — OpenCall AI

Status: **active execution** (this is not a documentation-only deliverable; coding begins immediately, per Master Prompt §120).

## 1. Architecture decision

**PATH A (Master Prompt §3) — LiveKit-first** with a local-first twist:

- Realtime layer: **official LiveKit server** (self-hosted dev mode) — WebRTC SFU, rooms, JWT access.
- Application API: **Node.js + TypeScript** (Fastify), monorepo with npm workspaces.
- Web: **Next.js + React + TypeScript + Tailwind**, real `livekit-client` SDK.
- AI voice: **Python agent-runtime** service, provider interfaces for faster-whisper / Silero VAD / OpenAI-compatible local LLM / Kokoro TTS.
- Data: **portable relational schema** (see §3 database decision).

## 2. Documented environment decisions (§156 — sensible engineering decisions)

Verified on this machine at bootstrap:

| Capability | Status | Decision |
|---|---|---|
| Node.js 22.16 / npm 10.9 | ✅ present | Use for API + Web |
| Python 3.10 | ✅ present | Use for agent-runtime |
| Git 2.55 | ✅ present | Local repo; `gh` CLI **not installed** → GitHub push deferred to user (never blocked on it) |
| Docker | ❌ **not installed** | Native-process dev stack + `start-dev` scripts; Docker Compose files provided for future self-host |
| PostgreSQL / psql | ❌ **not installed** | **Decision: embedded SQLite for local dev via `better-sqlite3`, schema kept portable (plain SQL migrations, ANSI-SQL subset + explicit migration files) so Postgres/Supabase/Neon swap is a config change.** Rule 25 requires one database; this satisfies "local development" leg without Docker. |
| Redis | ❌ not installed | **Decision: in-process event bus abstraction (`packages/eventbus`) with Redis adapter interface stubbed; MVP uses in-memory bus. Rule 45 realtime events work via this + WebSocket.** |
| GPU/NVIDIA | unverified | STT/TTS default to CPU-compatible configs; faster-whisper auto-selects device |

These are **provisional**, logged here per rule 156, and revisited when Docker/Postgres become available (schema and abstraction layers already anticipate it).

## 3. Database portability strategy

- Schema defined in versioned SQL migration files (no SQLite-only or Postgres-only types in core tables).
- `packages/db` exposes a thin query layer; connection string in `DATABASE_URL` (`sqlite://` now, `postgres://` later).
- No stored procedures, no provider-specific JSON operators in hot paths.

## 4. Selected upstream projects (§5 registry; versions pinned at integration time)

| Component | Repository | Purpose |
|---|---|---|
| LiveKit server | `livekit/livekit` | WebRTC SFU (official binary, dev mode) |
| livekit-client | `livekit/client-sdk-js` | Browser SDK (npm) |
| livekit server SDK | `livekit/server-sdk-js` | Room service + access tokens (npm) |
| faster-whisper | `SYSTRAN/faster-whisper` | Local STT (provider interface) |
| Silero VAD | `snakers4/silero-vad` | VAD / barge-in (provider interface) |
| Kokoro | `hexgrad/kokoro` | Local TTS (provider interface) |
| OpenAI-compatible endpoint | LM Studio / Ollama / llama.cpp | Local LLM |
| Next.js / React / Tailwind | npm | Web app |
| Fastify | npm | API HTTP server |
| better-sqlite3 | npm | Embedded SQL database |

Full compliance details in `docs/THIRD_PARTY_COMPONENTS.md`.

## 5. Development phases (Master Prompt §121–§131)

- **Phase 0 — Foundation**: monorepo, API, DB, auth, health checks. *In progress now.*
- **Phase 1 — Realtime calling**: LiveKit, rooms, tokens, incoming/outgoing call events, real two-way audio. Acceptance test #1 (§106) gates everything.
- **Phase 2 — Contacts & call platform**: call records, legs, events, history, details.
- **Phase 3 — Internet-first routing**: normalization, on-net, guest links, SIP URI + ENUM/federation abstractions (no-op implementations until configured).
- **Phase 4 — AI voice**: agent runtime, VAD→STT→LLM→TTS, streaming, barge-in, transcripts.
- **Phase 5 — Programmable platform**: API keys, REST calls API, webhooks, SDK, AI tool adapter.
- **Phase 6 — SIP interoperability**: LiveKit SIP evaluation; external SIP tests. **BLOCKED — EXTERNAL CREDENTIAL REQUIRED** until a SIP endpoint/provider is configured.
- **Phase 7 — Generic PSTN/BYOC**: trunk adapter, rate deck, spend limits. `PSTN_ENABLED=false` until real credentials exist.
- **Phase 8 — AI PSTN safeguards**: consent ledger, country policy, suppression, disclosure, fraud, audit.
- **Phase 9/10 — Hardening & polish**: monitoring, a11y, responsive, failure recovery.

## 6. Risks

| Risk | Mitigation |
|---|---|
| Docker absent → infra drift | Compose files shipped; native scripts are canonical for this machine |
| SQLite→Postgres migration friction | Portable schema; no provider-specific SQL in core tables |
| No public IP/TURN on local machine | Same-LAN WebRTC works; TURN config slot ready; LiveKit Cloud free tier option documented but **not provisioned without user approval** (§21/§31) |
| Local LLM not installed | Agent-runtime degrades gracefully: deterministic echo/knowledge-free responder + explicit status; never fake "smart" |
| SIP/PSTN credentials absent | Honest statuses: `IMPLEMENTED` / `PROTOCOL-TESTED` / `BLOCKED — EXTERNAL CREDENTIAL REQUIRED` (§155) |

## 7. Acceptance criteria (§106–§111, §154)

Tracked per-phase in `docs/STATUS.md`. Nothing enters DONE without a passing test.

## 8. External credential requirements

- **Not required**: browser↔browser calls, guest links, contacts, history, local AI pipeline.
- **Required later (user action)**: GitHub push auth, SIP endpoint/trunk for Phase 6/7 tests, optional LiveKit Cloud account, optional GPU for heavy local models.
