// Scoped LiveKit access tokens (server-side only; secrets never reach the browser — §34, §139).

import { AccessToken } from 'livekit-server-sdk';

export function livekitConfig() {
  const url = process.env.LIVEKIT_URL ?? 'ws://127.0.0.1:7880';
  const apiKey = process.env.LIVEKIT_API_KEY ?? 'devkey';
  const apiSecret =
    process.env.LIVEKIT_API_SECRET ??
    'opencalldevsecret_0123456789abcdef0123456789abcdef';
  // Browser connects to the same host in local dev; in production this is a public wss URL.
  const publicUrl = process.env.LIVEKIT_PUBLIC_URL ?? url.replace('ws://', 'ws://');
  return { url, apiKey, apiSecret, publicUrl };
}

export interface JoinGrant {
  roomName: string;
  identity: string;
  displayName: string;
  /** Allow publishing microphone audio. */
  canPublish: boolean;
  /** Allow subscribing to remote audio. */
  canSubscribe: boolean;
  ttlMinutes?: number;
  metadata?: Record<string, unknown>;
}

export async function mintJoinToken(grant: JoinGrant): Promise<string> {
  const { apiKey, apiSecret } = livekitConfig();
  const at = new AccessToken(apiKey, apiSecret, {
    identity: grant.identity,
    name: grant.displayName,
    ttl: (grant.ttlMinutes ?? 30) * 60, // AccessToken ttl is in seconds (server-sdk v2)
    metadata: grant.metadata ? JSON.stringify(grant.metadata) : undefined,
  });
  at.addGrant({
    room: grant.roomName,
    roomJoin: true,
    canPublish: grant.canPublish,
    canSubscribe: grant.canSubscribe,
    canPublishData: true,
  });
  return await at.toJwt();
}
