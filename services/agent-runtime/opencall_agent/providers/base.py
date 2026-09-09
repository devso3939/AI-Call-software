"""Provider interfaces (Master Prompt §33-§35): swappable local-first AI stages.

Every provider implements `probe() -> (ok, reason)` so /ready reports honest status.
Local implementations are default; cloud ones only via explicit env opt-in.
"""

from __future__ import annotations

import abc
from dataclasses import dataclass
from typing import Iterable, Optional, Protocol

import numpy as np


@dataclass
class Transcript:
    text: str
    language: str = "en"


@dataclass
class Speech:
    """TTS result — PCM 16-bit mono at 24 kHz (LiveKit source frame rate)."""

    pcm: bytes
    sample_rate: int = 24000


class VADProvider(Protocol):
    def probe(self) -> tuple[bool, str]: ...
    def is_speech(self, frame: np.ndarray, sample_rate: int) -> bool: ...


class STTProvider(Protocol):
    def probe(self) -> tuple[bool, str]: ...
    def transcribe(self, pcm16: bytes, sample_rate: int) -> Transcript: ...


class LLMProvider(Protocol):
    def probe(self) -> tuple[bool, str]: ...
    def complete(self, messages: list[dict], max_tokens: int = 256) -> str: ...


class TTSProvider(Protocol):
    def probe(self) -> tuple[bool, str]: ...
    def synthesize(self, text: str) -> Speech: ...


class BaseProvider(abc.ABC):
    """Convenience base implementing graceful 'not configured' probing."""

    name = "base"

    def probe(self) -> tuple[bool, str]:
        try:
            return self._probe()
        except ImportError as e:
            return False, f"dependency missing: {e.name or e}"
        except Exception as e:
            return False, f"{type(e).__name__}: {e}"

    @abc.abstractmethod
    def _probe(self) -> tuple[bool, str]: ...


def chunked_audio(pcm16: bytes, sample_rate: int, frame_ms: int = 30) -> Iterable[np.ndarray]:
    """Yield float32 mono frames of frame_ms for VAD."""
    frame_len = int(sample_rate * frame_ms / 1000)
    samples = np.frombuffer(pcm16, dtype=np.int16).astype(np.float32) / 32768.0
    for i in range(0, len(samples) - frame_len + 1, frame_len):
        yield samples[i : i + frame_len]


OptionalAny = Optional[object]
