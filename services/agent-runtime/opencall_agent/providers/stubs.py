"""Honest stubs — used when a local model is not installed. They NEVER fake output."""

from typing import Any, Dict


class NotConfigured(Exception):
    pass


def unavailable(provider: str, stage: str) -> Dict[str, Any]:
    return {"ok": False, "provider": provider, "stage": stage, "reason": "not configured (local model not installed)"}
