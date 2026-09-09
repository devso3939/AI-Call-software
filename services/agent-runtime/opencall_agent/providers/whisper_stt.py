"""faster-whisper STT (MIT) — local transcription, CTranslate2 backend."""

from typing import Tuple

from opencall_agent.config import settings
from opencall_agent.providers.base import BaseProvider, Transcript


class WhisperSTT(BaseProvider):
    name = "faster-whisper"

    def __init__(self) -> None:
        self._model = None

    def _probe(self) -> Tuple[bool, str]:
        from faster_whisper import WhisperModel  # type: ignore

        self._model = WhisperModel(settings.stt_model, device="auto", compute_type="auto")
        return True, f"model '{settings.stt_model}' loaded"

    def transcribe(self, pcm16: bytes, sample_rate: int) -> Transcript:
        if self._model is None:
            raise RuntimeError("STT not loaded (probe first)")
        import numpy as np

        audio = np.frombuffer(pcm16, dtype=np.int16).astype(np.float32) / 32768.0
        if sample_rate != 16000:
            import numpy as _np

            x = _np.linspace(0, len(audio) - 1, int(len(audio) * 16000 / sample_rate))
            audio = _np.interp(x, _np.arange(len(audio)), audio).astype(np.float32)
        segments, info = self._model.transcribe(audio, language="en", vad_filter=False)
        text = " ".join(s.text.strip() for s in segments).strip()
        return Transcript(text=text, language=info.language or "en")
