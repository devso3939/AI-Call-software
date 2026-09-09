"""Local OpenAI-compatible LLM (llama.cpp server / Ollama / LM Studio / vLLM).

Default LOCAL_AI=true → points at localhost. Cloud endpoints require explicit env change.
"""

import json
from typing import Tuple
from urllib import request as urlrequest

from opencall_agent.config import settings
from opencall_agent.providers.base import BaseProvider


class OpenAICompatLLM(BaseProvider):
    name = "openai-compatible"

    def _probe(self) -> Tuple[bool, str]:
        req = urlrequest.Request(
            f"{settings.llm_base_url.rstrip('/')}/models",
            headers={"Content-Type": "application/json"},
        )
        with urlrequest.urlopen(req, timeout=5) as resp:
            body = json.loads(resp.read().decode("utf-8"))
        ids = [m.get("id") for m in body.get("data", [])]
        return True, f"{len(ids)} model(s); using '{settings.llm_model}'"

    def complete(self, messages: list, max_tokens: int = 256) -> str:
        payload = json.dumps(
            {
                "model": settings.llm_model,
                "messages": messages,
                "max_tokens": max_tokens,
                "temperature": 0.7,
                "stream": False,
            }
        ).encode("utf-8")
        req = urlrequest.Request(
            f"{settings.llm_base_url.rstrip('/')}/chat/completions",
            data=payload,
            headers={"Content-Type": "application/json"},
        )
        with urlrequest.urlopen(req, timeout=60) as resp:
            body = json.loads(resp.read().decode("utf-8"))
        return body["choices"][0]["message"]["content"].strip()
