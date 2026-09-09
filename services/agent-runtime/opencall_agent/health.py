"""Health endpoints per Master Prompt §94 — honest component status, never fake green."""

import asyncio
from typing import Any, Dict

import uvicorn

from opencall_agent.providers import component_status


async def _status_snapshot() -> Dict[str, Any]:
    checks = await component_status()
    ok = all(v.get("ok") is True for k, v in checks.items() if k in ("livekit", "pipeline"))
    return {"ok": ok, "service": "agent-runtime", "checks": checks}


def create_app():
    from fastapi import FastAPI

    app = FastAPI(title="OpenCall agent-runtime", version="0.1.0")

    @app.get("/health")
    async def health() -> Dict[str, Any]:
        return {"ok": True, "service": "agent-runtime", "time": _now()}

    @app.get("/ready")
    async def ready() -> Dict[str, Any]:
        return await _status_snapshot()

    return app


def _now() -> str:
    import datetime

    return datetime.datetime.now(datetime.timezone.utc).isoformat()


def run_health_server(port: int) -> None:
    app = create_app()
    config = uvicorn.Config(app, host="127.0.0.1", port=port, log_level="warning")
    server = uvicorn.Server(config)
    threading = __import__("threading")
    t = threading.Thread(target=server.run, daemon=True)
    t.start()
