// OpenCall API — Fastify HTTP + WebSocket server.
// REST surface per Master Prompt §67; health checks §94; dialer route resolution §37.

import './env.js';
import Fastify from 'fastify';
import cors from '@fastify/cors';
import websocket from '@fastify/websocket';
import { createHmac, timingSafeEqual } from 'node:crypto';
import { openDb } from '@opencall/db';
import {
  registerUser, verifyCredentials, createSession, resolveSession, destroySession,
  AuthError, getWorkspaceIdForUser, type SessionUser,
} from '@opencall/auth';
import { normalizeDestination, resolveDestination, NoRouteAvailableError } from '@opencall/routing';
import { ROUTE_LABELS, type RouteResolution } from '@opencall/types';
import {
  createCall, answerCall, rejectCall, hangupCall, holdCall, resumeCall,
  getCallDetail, listCalls, CallError,
} from './services/calls.js';
import { wsRoutes, onlineUserIds } from './ws.js';

const PORT = Number(process.env.API_PORT ?? 4000);

const app = Fastify({ logger: { level: 'info', transport: undefined } });
await app.register(cors, { origin: true, credentials: true });
await app.register(websocket, { options: { maxPayload: 1048576 } });

// Lenient JSON body parsing: POST endpoints like /hangup take no body, but many
// REST clients still send "Content-Type: application/json" with an empty payload.
// Treat an empty JSON body as {} instead of failing the request.
app.addContentTypeParser('application/json', { parseAs: 'string' }, (_req, body, done) => {
  if (body === '' || body == null) return done(null, {});
  try {
    done(null, JSON.parse(body as string));
  } catch (err) {
    done(err as Error, undefined);
  }
});

openDb();

// ---------- auth helpers ----------

function requireUser(req: { headers: Record<string, string | string[] | undefined> }): SessionUser {
  const auth = req.headers.authorization;
  const bearer = typeof auth === 'string' && auth.startsWith('Bearer ') ? auth.slice(7) : undefined;
  const user = resolveSession(bearer);
  if (!user) throw new AuthError('Authentication required', 'unauthorized');
  return user;
}

app.setErrorHandler((err, req, reply) => {
  if (err instanceof AuthError) {
    const code = err.code === 'unauthorized' ? 401 : 400;
    return reply.code(code).send({ error: err.message, code: err.code });
  }
  if (err instanceof CallError) {
    return reply.code(err.statusCode).send({ error: err.message, code: err.code });
  }
  if (err instanceof NoRouteAvailableError) {
    return reply.code(422).send({ error: err.message, code: 'no_route' });
  }
  // Structured Fastify errors (validation, 404, media type, empty JSON body, …)
  // already carry the correct statusCode — never mask them as 500.
  const status = typeof (err as { statusCode?: number }).statusCode === 'number'
    ? (err as { statusCode: number }).statusCode
    : 500;
  if (status >= 500) req.log.error(err);
  return reply.code(status).send({
    error: err.message,
    code: (err as { code?: string }).code ?? 'internal',
  });
});

// ---------- health (§94) ----------

app.get('/health', async () => ({ ok: true, service: 'api', time: new Date().toISOString() }));

app.get('/ready', async () => {
  const checks: Record<string, boolean | string> = {};
  try {
    const { getDb } = await import('@opencall/db');
    getDb().prepare('SELECT 1').get();
    checks.database = true;
  } catch (e) {
    checks.database = `fail: ${(e as Error).message}`;
  }
  const { livekitConfig } = await import('@opencall/livekit-tokens');
  try {
    const { RoomServiceClient } = await import('livekit-server-sdk');
    const { url, apiKey, apiSecret } = livekitConfig();
    const rs = new RoomServiceClient(url.replace('ws://', 'http://').replace('wss://', 'https://'), apiKey, apiSecret);
    await rs.listRooms();
    checks.realtime = true;
  } catch {
    checks.realtime = 'livekit unreachable — start livekit-server (see start-dev)';
  }
  checks.agentRuntime = process.env.LOCAL_AI === 'false' ? 'disabled' : 'provider-interface-ready';
  const ok = checks.database === true;
  return { ok, checks };
});

// ---------- auth routes ----------

app.post('/v1/auth/register', async (req, reply) => {
  const { username, password, displayName } = (req.body ?? {}) as Record<string, string>;
  const user = registerUser(username ?? '', password ?? '', displayName ?? '');
  const session = createSession(user.id);
  return reply.code(201).send({ user, token: session.token, expiresAt: session.expiresAt });
});

app.post('/v1/auth/login', async (req, reply) => {
  const { username, password } = (req.body ?? {}) as Record<string, string>;
  const user = verifyCredentials(username ?? '', password ?? '');
  const session = createSession(user.id);
  return reply.send({ user, token: session.token, expiresAt: session.expiresAt });
});

app.post('/v1/auth/logout', async (req) => {
  const auth = req.headers.authorization;
  destroySession(typeof auth === 'string' && auth.startsWith('Bearer ') ? auth.slice(7) : undefined);
  return { ok: true };
});

app.get('/v1/me', async (req) => {
  const user = requireUser(req);
  return { user, workspaceId: getWorkspaceIdForUser(user.id) };
});

// ---------- users / dialer (§37) ----------

app.get('/v1/users', async (req) => {
  requireUser(req);
  const { getDb } = await import('@opencall/db');
  const rows = getDb()
    .prepare('SELECT id, username, display_name, internet_identity, created_at FROM users')
    .all() as Array<{ id: string; username: string; display_name: string; internet_identity: string; created_at: string }>;
  return {
    users: rows.map((r) => ({
      id: r.id,
      username: r.username,
      displayName: r.display_name,
      internetIdentity: r.internet_identity,
      online: onlineUserIds().includes(r.id),
      createdAt: r.created_at,
    })),
  };
});

app.get('/v1/routes/estimate', async (req) => {
  requireUser(req);
  const q = (req.query as { destination?: string }).destination ?? '';
  try {
    const resolution: RouteResolution = await resolveDestination(q);
    return { input: q, label: ROUTE_LABELS[resolution.routeType], ...resolution };
  } catch (e) {
    if (e instanceof NoRouteAvailableError) {
      return { input: q, label: 'NO ROUTE', routeType: null, costClass: null, explanation: e.message };
    }
    throw e;
  }
});

app.get('/v1/routes/normalize', async (req) => {
  requireUser(req);
  const q = (req.query as { destination?: string }).destination ?? '';
  return normalizeDestination(q);
});

// ---------- calls (§67) ----------

app.post('/v1/calls', async (req, reply) => {
  const user = requireUser(req);
  const body = (req.body ?? {}) as { destination?: string; mode?: 'human' | 'ai'; agentId?: string };
  if (!body.destination) throw new CallError('destination is required', 422, 'missing_destination');
  const result = await createCall({
    createdBy: {
      id: user.id,
      displayName: user.displayName,
      internetIdentity: user.internetIdentity,
      workspaceId: getWorkspaceIdForUser(user.id),
    },
    destination: body.destination,
    mode: body.mode === 'ai' ? 'ai' : 'human',
    agentId: body.agentId,
  });
  return reply.code(201).send(result);
});

app.get('/v1/calls', async (req) => {
  const user = requireUser(req);
  const q = req.query as { mode?: string; routeType?: string; status?: string; limit?: string };
  const workspaceId = getWorkspaceIdForUser(user.id);
  return { calls: listCalls({ userId: user.id, workspaceId, mode: q.mode, routeType: q.routeType, status: q.status, limit: q.limit ? Number(q.limit) : undefined }) };
});

app.get('/v1/calls/:id', async (req) => {
  const user = requireUser(req);
  const { id } = req.params as { id: string };
  return getCallDetail(id, user);
});

app.post('/v1/calls/:id/answer', async (req) => {
  const user = requireUser(req);
  const { id } = req.params as { id: string };
  return answerCall(id, user);
});

app.post('/v1/calls/:id/reject', async (req) => {
  const user = requireUser(req);
  const { id } = req.params as { id: string };
  rejectCall(id, user);
  return { ok: true };
});

app.post('/v1/calls/:id/hangup', async (req) => {
  const user = requireUser(req);
  const { id } = req.params as { id: string };
  await hangupCall(id, user);
  return { ok: true };
});

app.post('/v1/calls/:id/hold', async (req) => {
  const user = requireUser(req);
  const { id } = req.params as { id: string };
  holdCall(id, user);
  return { ok: true };
});

app.post('/v1/calls/:id/resume', async (req) => {
  const user = requireUser(req);
  const { id } = req.params as { id: string };
  resumeCall(id, user);
  return { ok: true };
});

// DTMF / transfer are Phase 5+ (§67): return explicit not-implemented, never fake success (§116).
app.post('/v1/calls/:id/dtmf', async () => {
  throw new CallError('DTMF requires a SIP/telephony leg — not available in internet-only mode', 501, 'not_implemented');
});
app.post('/v1/calls/:id/transfer', async () => {
  throw new CallError('Transfer is scheduled for Phase 5 — not implemented yet', 501, 'not_implemented');
});

// ---------- contacts ----------

app.get('/v1/contacts', async (req) => {
  const user = requireUser(req);
  const { getDb } = await import('@opencall/db');
  const wsId = getWorkspaceIdForUser(user.id);
  const rows = getDb().prepare('SELECT * FROM contacts WHERE workspace_id = ? ORDER BY display_name').all(wsId);
  return { contacts: rows };
});

app.post('/v1/contacts', async (req, reply) => {
  const user = requireUser(req);
  const { displayName, email, phoneNumber, sipUri, internetIdentity, notes } = (req.body ?? {}) as Record<string, string>;
  if (!displayName) throw new CallError('displayName is required', 422, 'missing_display_name');
  const { getDb, newId } = await import('@opencall/db');
  const wsId = getWorkspaceIdForUser(user.id);
  const id = newId('cnt');
  const norm = phoneNumber ? normalizeDestination(phoneNumber) : null;
  getDb()
    .prepare(
      `INSERT INTO contacts (id, workspace_id, display_name, email, phone_number, e164_number, sip_uri, internet_identity, notes)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`,
    )
    .run(id, wsId, displayName, email ?? null, phoneNumber ?? null, norm?.e164 ?? null, sipUri ?? null, internetIdentity ?? null, notes ?? null);
  return reply.code(201).send({ id });
});

app.delete('/v1/contacts/:id', async (req) => {
  const user = requireUser(req);
  const { id } = req.params as { id: string };
  const { getDb } = await import('@opencall/db');
  const wsId = getWorkspaceIdForUser(user.id);
  getDb().prepare('DELETE FROM contacts WHERE id = ? AND workspace_id = ?').run(id, wsId);
  return { ok: true };
});

// ---------- guest links (§39) ----------

app.post('/v1/guest-links', async (req, reply) => {
  const user = requireUser(req);
  const body = (req.body ?? {}) as { expiresInMinutes?: number; singleUse?: boolean };
  const ttl = Math.min(Math.max(body.expiresInMinutes ?? 60, 5), 24 * 60);
  const payload = {
    sub: user.id,
    name: user.displayName,
    room: `guest_${Date.now().toString(36)}${Math.random().toString(36).slice(2, 8)}`,
    exp: Math.floor(Date.now() / 1000) + ttl * 60,
    su: body.singleUse ?? false,
  };
  const secret = process.env.SESSION_SECRET || 'opencall-dev-guest-secret';
  const data = Buffer.from(JSON.stringify(payload)).toString('base64url');
  const sig = createHmac('sha256', secret).update(data).digest('base64url');
  const token = `${data}.${sig}`;
  const webUrl = process.env.WEB_URL ?? 'http://localhost:3000';
  return reply.code(201).send({ token, url: `${webUrl}/call/join/${token}`, expiresAt: new Date(payload.exp * 1000).toISOString() });
});

export function verifyGuestToken(token: string): { sub: string; name: string; room: string; exp: number; su: boolean } | null {
  try {
    const [data, sig] = token.split('.');
    const secret = process.env.SESSION_SECRET || 'opencall-dev-guest-secret';
    const expected = createHmac('sha256', secret).update(data).digest('base64url');
    if (sig.length !== expected.length || !timingSafeEqual(Buffer.from(sig), Buffer.from(expected))) return null;
    const payload = JSON.parse(Buffer.from(data, 'base64url').toString());
    if (payload.exp * 1000 < Date.now()) return null;
    return payload;
  } catch {
    return null;
  }
}

app.post('/v1/guest-links/redeem', async (req) => {
  const { token } = (req.body ?? {}) as { token?: string };
  if (!token) throw new CallError('token required', 422, 'missing_token');
  const payload = verifyGuestToken(token);
  if (!payload) throw new CallError('Invalid or expired guest link', 403, 'invalid_guest_token');
  const { mintJoinToken, livekitConfig } = await import('@opencall/livekit-tokens');
  const joinToken = await mintJoinToken({
    roomName: payload.room,
    identity: `guest_${Math.random().toString(36).slice(2, 10)}`,
    displayName: 'Guest',
    canPublish: true,
    canSubscribe: true,
  });
  return { joinToken, livekitUrl: livekitConfig().publicUrl, roomName: payload.room, hostName: payload.name };
});

// ---------- signaling websocket ----------

await app.register(wsRoutes);

app.listen({ port: PORT, host: '127.0.0.1' }).then(() => {
  console.log(`[api] OpenCall API listening on http://127.0.0.1:${PORT}`);
}).catch((err) => {
  console.error('[api] failed to start', err);
  process.exit(1);
});
