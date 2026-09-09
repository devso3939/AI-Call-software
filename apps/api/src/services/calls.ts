// Call service — server-side authoritative state machine (§44), LiveKit room lifecycle (§38).
// Every transition writes a call_events row and publishes realtime events (§45).

import { getDb, newId } from '@opencall/db';
import { getEventBus } from '@opencall/eventbus';
import { resolveDestination, NoRouteAvailableError } from '@opencall/routing';
import { mintJoinToken, livekitConfig } from '@opencall/livekit-tokens';
import { RoomServiceClient } from 'livekit-server-sdk';
import type { CallStatus } from '@opencall/types';
import { canTransition } from '@opencall/types';

let roomService: RoomServiceClient | null = null;
function getRoomService(): RoomServiceClient {
  if (!roomService) {
    const { url, apiKey, apiSecret } = livekitConfig();
    roomService = new RoomServiceClient(
      url.replace('ws://', 'http://').replace('wss://', 'https://'),
      apiKey,
      apiSecret,
    );
  }
  return roomService;
}

export class CallError extends Error {
  constructor(message: string, public statusCode: number, public code: string) {
    super(message);
  }
}

interface CallRow {
  id: string;
  workspace_id: string;
  created_by: string;
  direction: string;
  mode: string;
  requested_destination: string;
  normalized_destination: string;
  route_type: string | null;
  status: CallStatus;
  created_at: string;
  started_at: string | null;
  answered_at: string | null;
  ended_at: string | null;
  duration_seconds: number;
  termination_reason: string | null;
  room_name: string | null;
  agent_id: string | null;
}

function getCallRow(callId: string): CallRow {
  const row = getDb().prepare('SELECT * FROM calls WHERE id = ?').get(callId) as CallRow | undefined;
  if (!row) throw new CallError('Call not found', 404, 'call_not_found');
  return row;
}

function transition(callId: string, to: CallStatus, reason?: string): CallRow {
  const db = getDb();
  const call = getCallRow(callId);
  if (!canTransition(call.status, to)) {
    throw new CallError(
      `Illegal transition ${call.status} → ${to}`,
      409,
      'illegal_transition',
    );
  }
  const now = new Date().toISOString();
  const fields: Partial<Record<string, unknown>> = { status: to };
  if (to === 'ringing' && !call.started_at) fields.started_at = now;
  if (to === 'answered' || to === 'active') fields.answered_at = now;
  if (to === 'completed' || to === 'failed' || to === 'rejected' || to === 'cancelled' || to === 'busy' || to === 'no_answer' || to === 'blocked') {
    fields.ended_at = now;
    const base = call.answered_at ? new Date(call.answered_at).getTime() : null;
    fields.duration_seconds = base ? Math.max(0, Math.round((Date.now() - base) / 1000)) : 0;
    fields.termination_reason = reason ?? to;
  }
  const sets = Object.keys(fields).map((k) => `${snake(k)} = @${k}`);
  db.prepare(`UPDATE calls SET ${sets.join(', ')} WHERE id = @id`).run({ ...fields, id: callId });
  recordEvent(callId, `call.${to === 'active' ? 'active' : to}`, { reason });
  publish(call, `call.${to === 'completed' ? 'ended' : to === 'failed' ? 'failed' : to}`, { reason });
  return getCallRow(callId);
}

function snake(k: string): string {
  return k.replace(/[A-Z]/g, (m) => `_${m.toLowerCase()}`);
}

function publish(call: CallRow, type: string, payload?: Record<string, unknown>): void {
  getEventBus().publish({
    type,
    callId: call.id,
    workspaceId: call.workspace_id,
    timestamp: new Date().toISOString(),
    payload: { callId: call.id, roomName: call.room_name, ...payload },
  });
}

export function recordEvent(callId: string, type: string, payload?: Record<string, unknown>): void {
  getDb()
    .prepare('INSERT INTO call_events (id, call_id, type, payload) VALUES (?, ?, ?, ?)')
    .run(newId('evt'), callId, type, payload ? JSON.stringify(payload) : null);
}

export interface CreateCallInput {
  createdBy: { id: string; displayName: string; internetIdentity: string; workspaceId: string };
  destination: string;
  mode: 'human' | 'ai';
  agentId?: string;
}

export interface CreatedCall {
  callId: string;
  roomName: string;
  routeType: string;
  status: CallStatus;
  joinToken: string;
  livekitUrl: string;
  callee: { identity: string; displayName: string };
}

export async function createCall(input: CreateCallInput): Promise<CreatedCall> {
  const db = getDb();
  const resolution = await resolveDestination(input.destination).catch((err) => {
    if (err instanceof NoRouteAvailableError) {
      throw new CallError(err.message, 422, 'no_route');
    }
    throw err;
  });

  // Only ON_NET is actually placeable in internet-only MVP (§98). Honest errors otherwise (§116).
  if (resolution.routeType !== 'ON_NET' || !resolution.targetUserId) {
    throw new CallError(
      `Route "${resolution.routeType}" is classified but not placeable yet: ${resolution.explanation}`,
      422,
      'route_not_placeable',
    );
  }
  if (resolution.targetUserId === input.createdBy.id) {
    throw new CallError('You cannot call yourself', 422, 'self_call');
  }

  const callId = newId('call');
  const roomName = `call_${callId}`;
  const now = new Date().toISOString();

  const callee = db
    .prepare('SELECT id, username, display_name, internet_identity FROM users WHERE id = ?')
    .get(resolution.targetUserId) as
    | { id: string; username: string; display_name: string; internet_identity: string }
    | undefined;
  if (!callee) throw new CallError('Routed user no longer exists', 422, 'callee_missing');

  const tx = db.transaction(() => {
    db.prepare(
      `INSERT INTO calls (id, workspace_id, created_by, direction, mode, requested_destination,
        normalized_destination, route_type, status, room_name, agent_id)
       VALUES (?, ?, ?, 'outbound', ?, ?, ?, ?, 'created', ?, ?)`,
    ).run(callId, input.createdBy.workspaceId, input.createdBy.id, input.mode,
      input.destination, resolution.normalizedDestination, resolution.routeType, roomName, input.agentId ?? null);
    recordEvent(callId, 'call.created', { requestedDestination: input.destination });
    recordEvent(callId, 'call.validating', {});
    recordEvent(callId, 'call.route_selected', { routeType: resolution.routeType, explanation: resolution.explanation });
  });
  tx();

  let call = getCallRow(callId);
  transition(callId, 'validating');
  transition(callId, 'routing');
  transition(callId, 'originating');
  call = getCallRow(callId);

  // Ensure LiveKit room exists; if SFU is down the call fails honestly.
  try {
    await getRoomService().createRoom({ name: roomName, emptyTimeout: 60, maxParticipants: 8 });
  } catch (err) {
    transition(callId, 'failed', `livekit_unavailable: ${(err as Error).message}`);
    throw new CallError(
      'Realtime server (LiveKit) is unreachable — start it with start-dev or infra/livekit. Call failed, not faked.',
      503,
      'realtime_unavailable',
    );
  }

  db.prepare(
    `INSERT INTO call_legs (id, call_id, type, direction, endpoint_id, state) VALUES
     (?, ?, 'WEBRTC', 'outbound', ?, 'created')`,
  ).run(newId('leg'), callId, input.createdBy.id);
  db.prepare(
    `INSERT INTO call_legs (id, call_id, type, direction, endpoint_id, state) VALUES
     (?, ?, 'WEBRTC', 'inbound', ?, 'created')`,
  ).run(newId('leg'), callId, callee.id);

  const callerToken = await mintJoinToken({
    roomName,
    identity: input.createdBy.id,
    displayName: input.createdBy.displayName,
    canPublish: true,
    canSubscribe: true,
  });

  // Notify callee (§38 incoming-call event) — token minted only when they answer.
  getEventBus().publish({
    type: 'call.created',
    callId,
    toUserId: callee.id,
    timestamp: new Date().toISOString(),
    payload: {
      callId,
      roomName,
      from: { id: input.createdBy.id, displayName: input.createdBy.displayName, identity: input.createdBy.internetIdentity },
      mode: input.mode,
    },
  });

  transition(callId, 'ringing');

  return {
    callId,
    roomName,
    routeType: resolution.routeType,
    status: 'ringing',
    joinToken: callerToken,
    livekitUrl: livekitConfig().publicUrl,
    callee: { identity: callee.internet_identity, displayName: callee.display_name },
  };
}

export async function answerCall(callId: string, user: { id: string; displayName: string }): Promise<{ joinToken: string; livekitUrl: string; roomName: string }> {
  const call = getCallRow(callId);
  const calleeTarget = getDb()
    .prepare('SELECT endpoint_id FROM call_legs WHERE call_id = ? AND direction = ?')
    .get(callId, 'inbound') as { endpoint_id: string } | undefined;
  if (!calleeTarget || calleeTarget.endpoint_id !== user.id) {
    throw new CallError('Only the called user can answer this call', 403, 'not_callee');
  }
  transition(callId, 'answered');
  const roomName = call.room_name!;
  const joinToken = await mintJoinToken({
    roomName,
    identity: user.id,
    displayName: user.displayName,
    canPublish: true,
    canSubscribe: true,
  });
  transition(callId, 'active');
  return { joinToken, livekitUrl: livekitConfig().publicUrl, roomName };
}

export function rejectCall(callId: string, user: { id: string }): void {
  const call = getCallRow(callId);
  void user; // authorization: only callee may reject (checked below)
  const calleeTarget = getDb()
    .prepare('SELECT endpoint_id FROM call_legs WHERE call_id = ? AND direction = ?')
    .get(callId, 'inbound') as { endpoint_id: string } | undefined;
  if (!calleeTarget || calleeTarget.endpoint_id !== user.id) {
    throw new CallError('Only the called user can reject this call', 403, 'not_callee');
  }
  transition(callId, 'rejected', 'rejected_by_callee');
  void getRoomService().deleteRoom(call.room_name!).catch(() => undefined);
}

export async function hangupCall(callId: string, user: { id: string }, reason = 'hangup'): Promise<void> {
  const call = getCallRow(callId);
  const legs = getDb()
    .prepare('SELECT endpoint_id FROM call_legs WHERE call_id = ?')
    .all(callId) as { endpoint_id: string }[];
  const isParticipant = legs.some((l) => l.endpoint_id === user.id) || call.created_by === user.id;
  if (!isParticipant) throw new CallError('Not a participant of this call', 403, 'not_participant');
  const status = getCallRow(callId).status;
  if (['ringing'].includes(status)) {
    transition(callId, 'cancelled', reason);
  } else if (status === 'answered' || status === 'active' || status === 'held') {
    transition(callId, 'ending', reason);
    transition(callId, 'completed', reason);
  } else if (!['completed', 'failed', 'rejected', 'cancelled'].includes(status)) {
    transition(callId, 'failed', reason);
  }
  await getRoomService().deleteRoom(call.room_name!).catch(() => undefined);
}

export function holdCall(callId: string, user: { id: string }): void {
  const call = getCallRow(callId);
  if (call.created_by !== user.id) throw new CallError('Not allowed', 403, 'not_allowed');
  transition(callId, 'held', 'held_by_user');
}

export function resumeCall(callId: string, user: { id: string }): void {
  const call = getCallRow(callId);
  if (call.created_by !== user.id) throw new CallError('Not allowed', 403, 'not_allowed');
  transition(callId, 'active', 'resumed_by_user');
}

export function getCallDetail(callId: string, user: { id: string }) {
  const db = getDb();
  const call = getCallRow(callId);
  const legs = db.prepare('SELECT * FROM call_legs WHERE call_id = ?').all(callId);
  // Privacy (§12): call detail is only visible to actual participants.
  const isParticipant =
    call.created_by === user.id ||
    legs.some((l) => (l as { endpoint_id: string }).endpoint_id === user.id);
  if (!isParticipant) throw new CallError('Not a participant of this call', 403, 'not_participant');
  const events = db
    .prepare('SELECT type, payload, created_at FROM call_events WHERE call_id = ? ORDER BY created_at')
    .all(callId);
  return { call, legs, events };
}

export function listCalls(opts: { userId: string; workspaceId: string; limit?: number; mode?: string; routeType?: string; status?: string }) {
  const db = getDb();
  // Privacy (§12): a user sees only calls they participate in — as caller (created_by)
  // or as a leg endpoint (callee). Workspace scoping alone would leak other users' calls.
  const clauses: string[] = [
    'workspace_id = @workspaceId',
    `(created_by = @userId OR id IN (SELECT call_id FROM call_legs WHERE endpoint_id = @userId))`,
  ];
  if (opts.mode) clauses.push('mode = @mode');
  if (opts.routeType) clauses.push('route_type = @routeType');
  if (opts.status) clauses.push('status = @status');
  const rows = db
    .prepare(`SELECT * FROM calls WHERE ${clauses.join(' AND ')} ORDER BY created_at DESC LIMIT @limit`)
    .all({ userId: opts.userId, workspaceId: opts.workspaceId, mode: opts.mode ?? null, routeType: opts.routeType ?? null, status: opts.status ?? null, limit: opts.limit ?? 100 });
  return rows;
}
