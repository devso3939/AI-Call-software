"""Environment-driven settings (12-factor; no secrets in code)."""

import os
from dataclasses import dataclass, field

from dotenv import load_dotenv

load_dotenv()


def _bool(name: str, default: bool) -> bool:
    v = os.getenv(name)
    return default if v is None else v.strip().lower() in ("1", "true", "yes", "on")


@dataclass
class Settings:
    livekit_url: str = os.getenv("LIVEKIT_URL", "ws://127.0.0.1:7880")
    livekit_api_key: str = os.getenv("LIVEKIT_API_KEY", "devkey")
    livekit_api_secret: str = os.getenv("LIVEKIT_API_SECRET", "opencalldevsecret")

    local_ai: bool = field(default_factory=lambda: _bool("LOCAL_AI", True))
    stt_model: str = os.getenv("STT_MODEL", "base.en")
    tts_model: str = os.getenv("TTS_MODEL", "kokoro")
    llm_base_url: str = os.getenv("LLM_BASE_URL", "http://127.0.0.1:11434/v1")
    llm_model: str = os.getenv("LLM_MODEL", "qwen2.5:0.5b")

    agent_identity: str = os.getenv("AGENT_IDENTITY", "opencall-ai-agent")


settings = Settings()
