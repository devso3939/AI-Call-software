# OpenCall AI

Open-source, self-hostable, **Internet-first** calling platform: browser↔browser and human↔AI calls that are effectively free, with SIP and PSTN as explicit, optional, honestly-priced fallbacks.

> Governed by the master build prompt (see repo history / provided specification). Core rules: no fake calls, no fake buttons, no telecom bypass, PSTN disabled until legitimately configured, local AI by default.

## Quick start (Windows)

```powershell
# one-click: checks prerequisites, downloads LiveKit, migrates DB, starts everything
./start-dev.ps1
```

Or manually:

```powershell
npm install
npm run bootstrap:agent        # optional: python venv for AI agents
npm run dev:api                # terminal 1 — API on :4000
npm run dev:web                # terminal 2 — Web on :3000
infra/livekit/bin/livekit-server.exe --dev   # terminal 3 — SFU on :7880
```

Then open **http://localhost:3000** in two browser profiles, register two users, and call each other.

## Modes (Master Prompt §98–§99)

| Mode | Env | Includes |
|---|---|---|
| `internet-only` (default) | `OPENCALL_MODE=internet-only`, `PSTN_ENABLED=false` | browser calls, guest links, AI browser calls, internal identities |
| `hybrid` | `PSTN_ENABLED=true` + configured trunk | + PSTN with cost estimates & spend limits |

## Layout

```
apps/web            Next.js UI (dialer, calls, contacts, history, agents, diagnostics)
apps/api            Fastify API (auth, calls, routing, signaling WS, health)
packages/types      shared domain types (call state machine, route types, events)
packages/db         portable schema + migrations (SQLite dev / Postgres later)
packages/eventbus   realtime event bus (in-memory now, Redis adapter later)
packages/auth       sessions, password hashing, API keys (hashed at rest)
packages/livekit-tokens   scoped join token minting (server-side only)
services/agent-runtime    Python AI voice worker (VAD→STT→LLM→TTS, barge-in)
infra/livekit       LiveKit config + official binary loader
infra/docker        Docker Compose for future self-host profiles
docs                IMPLEMENTATION_PLAN, STATUS, THIRD_PARTY_COMPONENTS
tests               e2e / integration / sip
```

## Honesty rules baked in

- The dialer always shows route classification: `FREE ON-NET` / `SIP` / `FEDERATED` / `PSTN — COST MAY APPLY` / `NO ROUTE` (§37).
- No button succeeds without a real backend effect (§116).
- PSTN stays disabled until a legitimate trunk is configured (§53); missing credentials are marked BLOCKED, never faked (§155).

## License

Apache-2.0 for this repo's code. Third-party components and licenses: `docs/THIRD_PARTY_COMPONENTS.md`.
