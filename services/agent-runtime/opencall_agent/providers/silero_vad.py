"""Silero VAD (MIT) — local voice activity detection, no cloud."""

from typing import Tuple

import numpy as np

from opencall_agent.providers.base import BaseProvider


class SileroVAD(BaseProvider):
    name = "silero-vad"

    def __init__(self) -> None:
        self._model = None
        self._th = 0.5

    def _probe(self) -> Tuple[bool, str]:
        import torch  # noqa: F401
        from silero_vad import load_silero_vad  # type: ignore

        self._model = load_silero_vad()
        return True, "silero loaded"

    def is_speech(self, frame: np.ndarray, sample_rate: int) -> bool:
        if self._model is None:
            raise RuntimeError("silero VAD not loaded (probe first)")
        import torch

        tensor = torch.from_numpy(frame)
        if sample_rate not in (16000, 8000):
            # resample crude to 16k for silero
            import numpy as _np

            x = _np.linspace(0, len(frame) - 1, int(len(frame) * 16000 / sample_rate))
            frame = _np.interp(x, _np.arange(len(frame)), frame)
            tensor = torch.from_numpy(frame.astype(np.float32))
        with torch.no_grad():
            return bool(self._model(tensor, 16000).item() > self._th)
