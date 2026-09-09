"""LiveKit worker — joins a room as a participant and runs the voice pipeline
on the remote subscriber track; publishes TTS output on its microphone track.

Requires the `livekit` package (pip install livekit) and a reachable LiveKit server.
If the SDK is missing, run.py keeps the process alive for health reporting only —
it never pretends the agent is in the room.
"""

from __future__ import annotations

import asyncio
import logging
from typing import Optional

from opencall_agent.config import settings

log = logging.getLogger("opencall.worker")


def available() -> bool:
    try:
        import livekit  # noqa: F401
        from livekit import rtc  # noqa: F401
        return True
    except ImportError:
        return False


def run_worker(room_name: Optional[str]) -> None:
    """Blocking entry: connect, subscribe to the first remote audio track, run pipeline."""
    asyncio.run(_main(room_name))


async def _main(room_name: Optional[str]) -> None:
    from livekit import api as lk_api, rtc

    from opencall_agent.pipeline import VoicePipeline
    from opencall_agent.providers.kokoro_tts import KokoroTTS
    from opencall_agent.providers.openai_llm import OpenAICompatLLM
    from opencall_agent.providers.silero_vad import SileroVAD
    from opencall_agent.providers.whisper_stt import WhisperSTT

    pipeline = VoicePipeline(vad=SileroVAD(), stt=WhisperSTT(), llm=OpenAICompatLLM(), tts=KokoroTTS())
    status = pipeline.probe()
    log.info("pipeline status: %s", status)

    token = (
        lk_api.AccessToken(settings.livekit_api_key, settings.livekit_api_secret)
        .with_identity(settings.agent_identity)
        .with_name("OpenCall AI Agent")
        .with_grants(lk_api.VideoGrants(room_join=True, room=room_name or "", agent=True))
        .to_jwt()
    )

    room = rtc.Room()
    source = rtc.AudioSource(24000, 1)

    @room.on("participant_connected")
    def _on_join(participant):  # type: ignore[no-untyped-def]
        log.info("participant joined: %s", participant.identity)

    @room.on("track_subscribed")
    def _on_track(track, publication, participant):  # type: ignore[no-untyped-def]
        if track.kind != rtc.TrackKind.KIND_AUDIO:
            return
        log.info("subscribed to audio from %s", participant.identity)
        asyncio.create_task(_consume(track, pipeline, source))

    if room_name:
        await room.connect(settings.livekit_url, token)
        await room.localParticipant.publish_track(source, rtc.TrackSource.SOURCE_MICROPHONE)
        log.info("agent connected to room '%s' as %s", room_name, settings.agent_identity)
        await asyncio.Future()  # run forever
    else:
        # auto mode: poll for any existing room and join the first one
        while True:
            try:
                client = lk_api.RoomServiceClient(
                    settings.livekit_url.replace("ws://", "http://").replace("wss://", "https://"),
                    settings.livekit_api_key,
                    settings.livekit_api_secret,
                )
                rooms = client.list_rooms()
                if rooms:
                    target = rooms[0].name
                    token = (
                        lk_api.AccessToken(settings.livekit_api_key, settings.livekit_api_secret)
                        .with_identity(settings.agent_identity)
                        .with_name("OpenCall AI Agent")
                        .with_grants(lk_api.VideoGrants(room_join=True, room=target, agent=True))
                        .to_jwt()
                    )
                    await room.connect(settings.livekit_url, token)
                    await room.localParticipant.publish_track(source, rtc.TrackSource.SOURCE_MICROPHONE)
                    log.info("agent auto-joined room '%s'", target)
                    await asyncio.Future()
            except Exception as e:
                log.debug("auto-join poll: %s", e)
            await asyncio.sleep(3)


async def _consume(track, pipeline: "VoicePipeline", source) -> None:  # type: ignore[no-untyped-def]
    """Pull remote audio frames → pipeline; publish TTS speech frames back."""
    import numpy as np
    from livekit import rtc

    stream = rtc.AudioStream(track)
    # NOTE: pipeline resamples internally; we feed raw int16 PCM.
    async def publish_speech(speech) -> None:  # type: ignore[no-untyped-def]
        samples = np.frombuffer(speech.pcm, dtype=np.int16)
        frame_ms = 20
        chunk = int(speech.sample_rate * frame_ms / 1000)
        for i in range(0, len(samples), chunk):
            block = samples[i : i + chunk]
            frame = rtc.AudioFrame(
                data=block.tobytes(),
                samples_per_channel=len(block),
                sample_rate=speech.sample_rate,
                num_channels=1,
            )
            await source.capture_frame(frame)

    async for event in stream:
        frame: rtc.AudioFrame = event.frame
        # convert to 16-bit mono int16 bytes
        data = np.frombuffer(frame.data, dtype=np.int16) if frame.data.dtype == np.int16 else None
        if data is None:
            # planar float conversion path
            interleaved = frame.to_ndarray().reshape(-1, frame.num_channels).mean(axis=1)
            pcm16 = np.clip(interleaved * 32767, -32768, 32767).astype(np.int16).tobytes()
        else:
            pcm16 = frame.data.tobytes()
        pipeline.feed_pcm(pcm16, frame.sample_rate, on_reply=lambda s: asyncio.create_task(publish_speech(s)))
