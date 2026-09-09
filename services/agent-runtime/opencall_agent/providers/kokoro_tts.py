"""Kokoro TTS (Apache-2.0) — local neural TTS, outputs PCM 16-bit mono @ 24 kHz."""

import subprocess
from typing import Tuple

from opencall_agent.providers.base import BaseProvider, Speech


class KokoroTTS(BaseProvider):
    name = "kokoro"

    def __init__(self) -> None:
        self._pipeline = None

    def _probe(self) -> Tuple[bool, str]:
        from kokoro import KPipeline  # type: ignore

        # espeak-ng must be on PATH (kokoro dependency)
        try:
            subprocess.run(["espeak-ng", "--version"], capture_output=True, check=True)
        except (OSError, subprocess.CalledProcessError):
            return False, "espeak-ng not found on PATH (required by kokoro)"
        self._pipeline = KPipeline(lang_code="a")  # a=american english
        return True, "kokoro pipeline ready"

    def synthesize(self, text: str) -> Speech:
        if self._pipeline is None:
            raise RuntimeError("TTS not loaded (probe first)")
        import numpy as np

        chunks = []
        sr = 24000
        for result in self._pipeline(text):
            if result.audio is not None:
                audio = result.audio.detach().cpu().numpy() if hasattr(result.audio, "detach") else result.audio
                chunks.append(np.asarray(audio, dtype=np.float32))
                if hasattr(result, "sample_rate") and result.sample_rate:
                    sr = int(result.sample_rate)
        if not chunks:
            raise RuntimeError("kokoro produced no audio")
        pcm = (np.concatenate(chunks) * 32767.0).astype(np.int16).tobytes()
        return Speech(pcm=pcm, sample_rate=sr)
