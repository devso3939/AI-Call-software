# OpenCall AI

Open-source, self-hostable, **Internet-first** calling platform: browser↔browser and human↔AI calls that are effectively free, with SIP and PSTN as explicit, optional, honestly-priced fallbacks.

**Live web app** → [devso3939.github.io/AI-Call-software/app.html](https://devso3939.github.io/AI-Call-software/app.html) · **Landing page** → [devso3939.github.io/AI-Call-software](https://devso3939.github.io/AI-Call-software/)

<!-- always-fresh links: the rolling 'latest' release is replaced on every build -->
[![Latest APK](https://img.shields.io/badge/⬇️_Android_APK-latest_release-10b981?style=for-the-badge)](https://github.com/devso3939/AI-Call-software/releases/latest/download/opencall-gateway-debug.apk)
[![Web app](https://img.shields.io/badge/🌐_web_app-live-22d3ee?style=for-the-badge)](https://devso3939.github.io/AI-Call-software/app.html)
![Tests](https://img.shields.io/badge/regression-52%2F52_pass-34d399) ![Backend](https://img.shields.io/badge/backend-Supabase_PostgREST-3ecf8e) ![License](https://img.shields.io/badge/license-Apache--2.0-blue)

> Governed by the master build prompt (see repo history / provided specification). Core rules: no fake calls, no fake buttons, no telecom bypass, PSTN disabled until legitimately configured, local AI by default.

## Android gateway app (SIM gateway)

A spare Android phone + its SIM = your **free cellular gateway** for real calls and SMS:

**[⬇️ Download the latest APK](https://github.com/devso3939/AI-Call-software/releases/latest/download/opencall-gateway-debug.apk)** — that link *never changes*: every app update replaces the `latest` release automatically, so it always serves the newest build. Full details in [`apps/android-gateway/README.md`](apps/android-gateway/README.md).

1. Install the APK (allow "install unknown apps")
2. Web app → **Devices** tab → create a 6-digit pairing code
3. App → enter code → **Pair** → **Start gateway**
4. Your number is now usable from the web app **and from any external app** via the Connect tab API

## OpenCall Connect — external API

Once your number is connected, **other web apps and services can use it**: mint an API key in the **Connect** tab and send SMS or place real cellular calls with a single POST (no SDK, works from Zapier/n8n/Make/any server). Migrations `008_external_api.sql` + `009_web_signaling.sql` provide the endpoints (`api_status`, `api_send_sms`, `api_get_sms`, `api_place_call`, `api_get_call`, `api_post_signal`, `api_get_signals`) with SHA-256-hashed show-once keys, a 60 req/min rate limit, and *failed requests never count*.

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
