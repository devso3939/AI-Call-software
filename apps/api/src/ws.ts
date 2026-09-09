// WebSocket signaling hub (§45 realtime events).
// Clients authenticate with ?token=<session token>, then receive JSON events pushed from the event bus.

import type { FastifyInstance } from 'fastify';
import type { WebSocket } from 'ws';
import { resolveSession, type SessionUser } from '@opencall/auth';
import { getEventBus, type RealtimeEvent } from '@opencall/eventbus';

const socketsByUser = new Map<string, Set<WebSocket>>();

export function pushToUser(userId: string, event: RealtimeEvent): void {
  const set = socketsByUser.get(userId);
  if (!set) return;
  const data = JSON.stringify(event);
  for (const ws of set) {
    if (ws.readyState === ws.OPEN) ws.send(data);
  }
}

export function onlineUserIds(): string[] {
  return [...socketsByUser.keys()];
}

export async function wsRoutes(app: FastifyInstance): Promise<void> {
  app.get('/ws', { websocket: true }, (socket, req) => {
    const token = (req.query as { token?: string }).token;
    const user: SessionUser | null = resolveSession(token);
    if (!user) {
      socket.send(JSON.stringify({ type: 'auth.failed', payload: { reason: 'invalid token' } }));
      socket.close();
      return;
    }
    let set = socketsByUser.get(user.id);
    if (!set) {
      set = new Set();
      socketsByUser.set(user.id, set);
    }
    set.add(socket);
    socket.send(JSON.stringify({ type: 'auth.ok', payload: { userId: user.id, identity: user.internetIdentity } }));

    socket.on('message', (raw: Buffer) => {
      // Client→server messages are minimal for MVP; state changes go through REST (server is authoritative — §44).
      try {
        const msg = JSON.parse(raw.toString());
        if (msg?.type === 'ping') socket.send(JSON.stringify({ type: 'pong', timestamp: new Date().toISOString() }));
      } catch {
        // ignore malformed
      }
    });

    socket.on('close', () => {
      const s = socketsByUser.get(user.id);
      if (s) {
        s.delete(socket);
        if (s.size === 0) socketsByUser.delete(user.id);
      }
    });
  });

  // Bridge bus → user sockets
  getEventBus().subscribeAll((event) => {
    if (event.toUserId) {
      pushToUser(event.toUserId, event);
    } else if (['call.ended', 'call.failed', 'call.rejected', 'call.cancelled'].includes(event.type)) {
      // broadcast terminal call events to everyone in dev (fine at MVP scale)
      for (const userId of socketsByUser.keys()) pushToUser(userId, event);
    }
  });
}
