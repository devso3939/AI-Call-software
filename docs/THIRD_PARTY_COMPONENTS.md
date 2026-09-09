# THIRD-PARTY COMPONENTS

Master Prompt §5 registry. Versions are pinned at integration; licenses read before use (§17).

| Component | Repository | Version/Source | License | Purpose | Modifications |
|---|---|---|---|---|---|
| LiveKit server | `livekit/livekit` | official dev-mode binary (downloaded at setup, not vendored) | Apache-2.0 | WebRTC SFU, rooms, JWT | none — run as-is |
| livekit-client | `livekit/client-sdk-js` | npm `livekit-client` | Apache-2.0 | browser WebRTC SDK | none |
| server-sdk-js | `livekit/server-sdk-js` | npm `livekit-server-sdk` | Apache-2.0 | room service + access tokens | none |
| Next.js | `vercel/next.js` | npm | MIT | web app framework | none |
| React | `facebook/react` | npm | MIT | UI | none |
| Tailwind CSS | `tailwindlabs/tailwindcss` | npm | MIT | styling | none |
| Fastify | `fastify/fastify` | npm | MIT | HTTP API server | none |
| @fastify/websocket | `fastify/fastify-websocket` | npm | MIT | signaling WebSocket | none |
| better-sqlite3 | `WiseLibs/better-sqlite3` | npm | MIT | embedded SQL database (dev) | none |
| bcryptjs | `dcodeIO/bcrypt.js` | npm | BSD-3-Clause | password hashing | none |
| faster-whisper | `SYSTRAN/faster-whisper` | pip (agent-runtime) | MIT | local STT | none — provider interface |
| Silero VAD | `snakers4/silero-vad` | pip `silero-vad` (agent-runtime) | MIT | voice activity detection / barge-in | none |
| Kokoro | `hexgrad/kokoro` | pip `kokoro>=0.9` (agent-runtime) | Apache-2.0 | local TTS | none |
| livekit-agents (python) | `livekit/agents` | pip (agent-runtime) | Apache-2.0 | agent participant scaffold | none |
| LiveKit protocol | `livekit/protocol` | transitive | Apache-2.0 | protobuf signaling | transitive |

Notes:
- No code is copied from any repository into this codebase (§104); all upstreams are consumed as binaries/packages/subprocesses via documented interfaces.
- GPL components (Piper GPL variants, Asterisk, etc.) are NOT integrated in the current phase; if added later they will run as separate services behind network interfaces (§17).
- Model files (Whisper, Silero, Kokoro) are downloaded at runtime into `data/models/` (gitignored) with licenses linked from their model cards (§29).
