# OpenCall Agent Runtime (Python)

AI voice worker for OpenCall AI — local-first pipeline: **VAD → STT → LLM → TTS**.

## Rules (from Master Prompt)
- Local models by default: faster-whisper (STT), Silero VAD, Kokoro (TTS), any local OpenAI-compatible LLM.
- Zero paid AI APIs in the default path. Cloud providers only via explicit env opt-in.
- The agent joins LiveKit rooms as a regular participant — same WebRTC media path as humans.
- No fake behavior: if a model is missing, the worker reports `degraded` honestly and refuses the pipeline stage.

## Layout
```
services/agent-runtime/
  requirements.txt
  .env.example
  run.py                # dev entry: python run.py --room <name>
  opencall_agent/
    __init__.py
    config.py           # env-driven settings
    health.py           # FastAPI /health /ready (§94)
    providers/
      __init__.py
      base.py           # STTProvider / LLMProvider / TTSProvider / VADProvider interfaces
      stubs.py          # honest not-configured providers (report unavailable, never fake output)
      whisper_stt.py    # faster-whisper STT
      silero_vad.py     # Silero VAD
      kokoro_tts.py     # Kokoro TTS
      openai_llm.py     # local OpenAI-compatible LLM (llama.cpp / Ollama / LM Studio / vLLM)
    pipeline.py         # turn-based voice pipeline wiring
    livekit_worker.py   # LiveKit participant: subscribes audio → pipeline → publishes TTS audio
```

## Quick start (dev)
```powershell
python -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install -r requirements.txt
copy .env.example .env
python run.py --room demo   # joins when the room is created by a real call
```

## Notes on dependencies
`livekit` (agents SDK), `faster-whisper`, `torch`/`torchaudio`, and `kokoro` are heavyweight and are
intentionally NOT auto-installed by start-dev. Install them inside the venv when you want the full
voice pipeline; the worker degrades honestly (health: `degraded`) until then.
