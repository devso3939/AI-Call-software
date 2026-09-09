"""Provider package — component status aggregation for /ready (§94)."""

import asyncio
from typing import Any, Dict

from opencall_agent.config import settings


async def component_status() -> Dict[str, Dict[str, Any]]:
    """Report each pipeline stage honestly: ok/unavailable + reason. Never fake green."""
    checks: Dict[str, Dict[str, Any]] = {}

    # LiveKit reachability
    checks["livekit"] = await asyncio.to_thread(_check_livekit)

    checks["pipeline"] = {
        "ok": True,
        "mode": "local" if settings.local_ai else "cloud-optin",
        "stages": {
            "vad": _stage("silero", "opencall_agent.providers.silero_vad", "SileroVAD"),
            "stt": _stage("faster-whisper", "opencall_agent.providers.whisper_stt", "WhisperSTT"),
            "llm": _stage(f"openai-compatible@{settings.llm_base_url}", "opencall_agent.providers.openai_llm", "OpenAICompatLLM"),
            "tts": _stage("kokoro", "opencall_agent.providers.kokoro_tts", "KokoroTTS"),
        },
    }
    return checks


def _stage(label: str, module: str, cls_name: str) -> Dict[str, Any]:
    try:
        import importlib

        mod = importlib.import_module(module)
        cls = getattr(mod, cls_name)
        inst = cls()
        ok, reason = inst.probe()
        return {"ok": ok, "provider": label, "reason": reason}
    except Exception as e:  # probe must never crash /ready
        return {"ok": False, "provider": label, "reason": f"probe error: {e}"}


def _check_livekit() -> Dict[str, Any]:
    try:
        from livekit import api as lk_api

        client = lk_api.RoomServiceClient(
            settings.livekit_url.replace("ws://", "http://").replace("wss://", "https://"),
            settings.livekit_api_key,
            settings.livekit_api_secret,
        )
        client.list_rooms()
        return {"ok": True}
    except ImportError:
        return {"ok": False, "reason": "livekit SDK not installed (pip install livekit)"}
    except Exception as e:
        return {"ok": False, "reason": f"livekit unreachable: {e}"}
