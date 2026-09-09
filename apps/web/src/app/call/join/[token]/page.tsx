'use client';

// Guest call link (§39): open signed link → mic permission → join room. No account needed.

import { useParams, useRouter } from 'next/navigation';
import { useEffect, useRef, useState } from 'react';
import { Room, RoomEvent, type RemoteAudioTrack } from 'livekit-client';

export default function GuestJoinPage() {
  const params = useParams<{ token: string }>();
  const router = useRouter();
  const [phase, setPhase] = useState<'idle' | 'joining' | 'waiting' | 'active' | 'ended'>('idle');
  const [hostName, setHostName] = useState<string>('');
  const [error, setError] = useState<string | null>(null);
  const roomRef = useRef<Room | null>(null);

  useEffect(() => {
    return () => {
      roomRef.current?.disconnect().catch(() => undefined);
    };
  }, []);

  async function join() {
    setError(null);
    setPhase('joining');
    try {
      const res = await fetch('/v1/guest-links/redeem', {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({ token: params.token }),
      });
      const body = await res.json();
      if (!res.ok) throw new Error(body.error ?? 'Link invalid or expired');
      setHostName(body.hostName ?? 'Host');

      const room = new Room();
      roomRef.current = room;
      room.on(RoomEvent.TrackSubscribed, (track) => {
        if (track.kind === 'audio') (track as RemoteAudioTrack).attach();
      });
      room.on(RoomEvent.ParticipantConnected, () => setPhase('active'));
      room.on(RoomEvent.Disconnected, () => setPhase('ended'));

      await room.connect(body.livekitUrl, body.joinToken);
      await room.localParticipant.setMicrophoneEnabled(true);
      const hasRemote = room.remoteParticipants.size > 0;
      setPhase(hasRemote ? 'active' : 'waiting');
    } catch (err) {
      setError((err as Error).message);
      setPhase('idle');
    }
  }

  return (
    <main className="flex min-h-screen flex-col items-center justify-center gap-4 p-4 text-center">
      <h1 className="text-2xl font-bold text-white">OpenCall guest link</h1>
      {phase === 'idle' && (
        <>
          <p className="max-w-sm text-sm text-slate-400">
            You have been invited to a free Internet audio call. No account required — your browser
            will ask for microphone access.
          </p>
          <button className="btn-primary" onClick={join}>Join call</button>
        </>
      )}
      {(phase === 'joining' || phase === 'waiting') && (
        <p className="text-slate-300">{hostName || 'Host'} will join shortly — you are in the room.</p>
      )}
      {phase === 'active' && <p className="text-mint-400">Connected. Talk away!</p>}
      {phase === 'ended' && <p className="text-slate-400">Call ended.</p>}
      {error && <p className="rounded-lg bg-red-950/60 px-3 py-2 text-sm text-red-300">{error}</p>}
      <button className="btn-ghost" onClick={() => router.replace('/')}>Home</button>
    </main>
  );
}
