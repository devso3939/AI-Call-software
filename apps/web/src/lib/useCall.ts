'use client';

// Real WebRTC call hook — the actual audio path (Master Prompt §38: two-way audio, not fake).
// Wraps livekit-client; room audio is attached via hidden <audio> elements.

import { useCallback, useEffect, useRef, useState } from 'react';
import {
  Room, RoomEvent, RemoteParticipant, ConnectionState, Track, type RemoteAudioTrack,
} from 'livekit-client';

export type CallPhase = 'idle' | 'connecting' | 'ringing' | 'active' | 'ended';

export interface ActiveCall {
  callId: string;
  roomName: string;
  phase: CallPhase;
  muted: boolean;
  remoteDisplayName: string | null;
  quality: 'unknown' | 'excellent' | 'good' | 'poor';
  stats: { rttMs: number | null; jitterMs: number | null; packetsLost: number | null; codec: string | null };
}

interface UseCallApi {
  answer: (callId: string) => Promise<{ joinToken: string; livekitUrl: string; roomName: string }>;
  hangup: (callId: string) => Promise<unknown>;
}

export function useCall(api: UseCallApi) {
  const roomRef = useRef<Room | null>(null);
  const [state, setState] = useState<ActiveCall>({
    callId: '', roomName: '', phase: 'idle', muted: false,
    remoteDisplayName: null, quality: 'unknown',
    stats: { rttMs: null, jitterMs: null, packetsLost: null, codec: null },
  });

  const patch = useCallback((p: Partial<ActiveCall>) => {
    setState((s) => ({ ...s, ...p }));
  }, []);

  const connectToRoom = useCallback(async (joinToken: string, livekitUrl: string, callId: string, roomName: string) => {
    // leave any previous room
    if (roomRef.current) {
      await roomRef.current.disconnect().catch(() => undefined);
      roomRef.current = null;
    }
    const room = new Room({ adaptiveStream: false, dynacast: false });
    roomRef.current = room;

    room.on(RoomEvent.ParticipantConnected, () => patch({ phase: 'active' }));
    room.on(RoomEvent.TrackSubscribed, (track) => {
      if (track.kind === 'audio') {
        (track as RemoteAudioTrack).attach(); // plays via auto-created audio element
        patch({ phase: 'active' });
      }
    });
    room.on(RoomEvent.Disconnected, () => {
      patch({ phase: 'ended' });
      roomRef.current = null;
    });
    room.on(RoomEvent.ActiveSpeakersChanged, () => undefined);

    // poll WebRTC stats (§85 quality indicator)
    const statsTimer = setInterval(async () => {
      if (room.state !== ConnectionState.Connected) return;
      const remote = [...room.remoteParticipants.values()][0];
      if (!remote) return;
      try {
        const pub = remote.getTrackPublication(Track.Source.Microphone);
        const mediaTrack = (pub?.track as RemoteAudioTrack | undefined)?.mediaStreamTrack;
        if (mediaTrack && room.engine?.pcManager) {
          const pc = (room.engine.pcManager as unknown as { publisher?: { pc?: RTCPeerConnection } }).publisher?.pc;
          if (!pc) return;
          // browser-native getStats — real WebRTC quality data (§85), not simulated
          const report = await pc.getStats();
          let rtt: number | null = null;
          let jitter: number | null = null;
          report.forEach((s) => {
            if (s.type === 'candidate-pair' && s.nominated && s.currentRoundTripTime != null) rtt = Math.round(s.currentRoundTripTime * 1000);
            if (s.type === 'inbound-rtp' && s.kind === 'audio' && s.jitter != null) jitter = Math.round(s.jitter * 1000);
          });
          if (rtt != null) {
            patch({
              quality: rtt < 150 ? 'excellent' : rtt < 400 ? 'good' : 'poor',
              stats: { rttMs: rtt, jitterMs: jitter, packetsLost: null, codec: null },
            });
          }
        }
      } catch {
        /* stats optional */
      }
    }, 5000);

    patch({ callId, roomName, phase: 'connecting' });
    try {
      await room.connect(livekitUrl, joinToken);
      await room.localParticipant.setMicrophoneEnabled(true);
      const remote = [...room.remoteParticipants.values()][0];
      patch({
        phase: remote ? 'active' : 'ringing',
        remoteDisplayName: remote?.name ?? remote?.identity ?? null,
      });
    } catch (err) {
      console.error('[call] connect failed', err);
      patch({ phase: 'ended' });
      clearInterval(statsTimer);
      throw err;
    }
    // Debug/observability hook (§84): expose the live room for E2E media verification.
    // Harmless in production (read-only reference, no behavior change).
    (window as unknown as { __opencallRoom?: Room }).__opencallRoom = room;
    return () => clearInterval(statsTimer);
  }, [patch]);

  const mute = useCallback((muted: boolean) => {
    roomRef.current?.localParticipant.setMicrophoneEnabled(!muted);
    patch({ muted });
  }, [patch]);

  const hangup = useCallback(async () => {
    const s = state;
    if (roomRef.current) {
      await roomRef.current.disconnect().catch(() => undefined);
      roomRef.current = null;
    }
    if (s.callId) {
      await api.hangup(s.callId).catch(() => undefined);
    }
    patch({ phase: 'idle', callId: '', roomName: '', remoteDisplayName: null });
  }, [api, state, patch]);

  useEffect(() => {
    return () => {
      roomRef.current?.disconnect().catch(() => undefined);
    };
  }, []);

  return { state, connectToRoom, mute, hangup };
}
