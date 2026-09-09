"""Turn-based voice pipeline: VAD → (collect speech) → STT → LLM → TTS.

The pipeline is provider-agnostic; each stage degrades honestly when its model
is missing (see providers/base.BaseProvider.probe). No fake audio, no fake text.
"""

from __future__ import annotations

import logging
from dataclasses import dataclass, field
from typing import Callable, Optional

import numpy as np

from opencall_agent.providers.base import Speech, Transcript, chunked_audio

log = logging.getLogger("opencall.pipeline")


@dataclass
class PipelineStageStatus:
    vad: tuple[bool, str] = (False, "not probed")
    stt: tuple[bool, str] = (False, "not probed")
    llm: tuple[bool, str] = (False, "not probed")
    tts: tuple[bool, str] = (False, "not probed")


@dataclass
class TurnDetector:
    """Accumulates 16k float32 audio and detects end-of-speech via silence run."""

    sample_rate: int = 16000
    silence_frames_to_end_turn: int = 12  # ~360 ms of silence ends a turn
    max_speech_frames: int = 5000         # safety cap (~10 min)
    _speaking: bool = False
    _silence_run: int = 0
    _speech_frames: int = 0

    def feed(self, is_speech: bool) -> Optional[str]:
        """Returns 'end_turn' when a turn completes; None otherwise."""
        if is_speech:
            self._speaking = True
            self._silence_run = 0
            self._speech_frames += 1
            if self._speech_frames >= self.max_speech_frames:
                return "end_turn"
            return None
        if self._speaking:
            self._silence_run += 1
            if self._silence_run >= self.silence_frames_to_end_turn:
                return "end_turn"
        return None


@dataclass
class VoicePipeline:
    vad: object
    stt: object
    llm: object
    tts: object
    system_prompt: str = "You are OpenCall AI, a helpful voice assistant. Keep replies short and natural for phone-style conversation."
    status: PipelineStageStatus = field(default_factory=PipelineStageStatus)
    _buf: list = field(default_factory=list)
    _detector: TurnDetector = field(default_factory=TurnDetector)

    def probe(self) -> PipelineStageStatus:
        self.status = PipelineStageStatus(
            vad=self.vad.probe() if self.vad else (False, "missing"),
            stt=self.stt.probe() if self.stt else (False, "missing"),
            llm=self.llm.probe() if self.llm else (False, "missing"),
            tts=self.tts.probe() if self.tts else (False, "missing"),
        )
        return self.status

    @property
    def fully_available(self) -> bool:
        return all(ok for ok, _ in
                   (self.status.vad, self.status.stt, self.status.llm, self.status.tts))

    def feed_pcm(self, pcm16: bytes, sample_rate: int, on_reply: Callable[[Speech], None]) -> None:
        """Feed inbound 16-bit PCM. Calls on_reply with TTS speech when a turn completes."""
        if not self.fully_available:
            missing = [name for name, (ok, _) in
                       zip(("vad", "stt", "llm", "tts"),
                           (self.status.vad, self.status.stt, self.status.llm, self.status.tts))
                       if not ok]
            log.warning("pipeline degraded, missing stages: %s — refusing to fake a reply", missing)
            return

        # resample to 16k for VAD/STT if needed
        if sample_rate != self._detector.sample_rate:
            audio = self._resample(pcm16, sample_rate, self._detector.sample_rate)
        else:
            audio = pcm16

        self._buf.append(audio)
        frame = self._to_float32(audio, self._detector.sample_rate)
        for fr in chunked_audio(frame.tobytes(), self._detector.sample_rate, frame_ms=30):
            if self._detector.feed(self.vad.is_speech(fr, self._detector.sample_rate)) == "end_turn":
                self._process_turn(on_reply)
                return

    def _process_turn(self, on_reply: Callable[[Speech], None]) -> None:
        pcm16 = b"".join(self._buf)
        self._buf.clear()
        self._detector = TurnDetector()
        try:
            transcript: Transcript = self.stt.transcribe(pcm16, self._detector.sample_rate)
            if not transcript.text:
                log.info("turn contained no transcribable speech")
                return
            log.info("user said: %r", transcript.text)
            reply_text = self.llm.complete(
                [{"role": "system", "content": self.system_prompt},
                 {"role": "user", "content": transcript.text}],
            )
            log.info("assistant: %r", reply_text)
            speech: Speech = self.tts.synthesize(reply_text)
            on_reply(speech)
        except Exception as e:
            log.error("turn processing failed: %s", e)

    @staticmethod
    def _to_float32(pcm16: bytes, sample_rate: int) -> np.ndarray:
        return np.frombuffer(pcm16, dtype=np.int16).astype(np.float32) / 32768.0

    @staticmethod
    def _resample(pcm16: bytes, from_rate: int, to_rate: int) -> bytes:
        samples = np.frombuffer(pcm16, dtype=np.int16)
        n_out = int(len(samples) * to_rate / from_rate)
        x = np.linspace(0, len(samples) - 1, n_out)
        resampled = np.interp(x, np.arange(len(samples)), samples.astype(np.float32))
        return np.clip(resampled, -32768, 32767).astype(np.int16).tobytes()
