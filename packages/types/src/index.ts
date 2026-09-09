// OpenCall AI — shared domain types
// Call state machine per Master Prompt §44; route types §47; events §45.

export type CallStatus =
  | 'created'
  | 'validating'
  | 'routing'
  | 'originating'
  | 'ringing'
  | 'answered'
  | 'active'
  | 'held'
  | 'transferring'
  | 'ending'
  | 'completed'
  | 'failed'
  | 'rejected'
  | 'cancelled'
  | 'busy'
  | 'no_answer'
  | 'blocked';

export const TERMINAL_STATUSES: readonly CallStatus[] = [
  'completed', 'failed', 'rejected', 'cancelled', 'busy', 'no_answer', 'blocked',
];

export function isTerminal(status: CallStatus): boolean {
  return TERMINAL_STATUSES.includes(status);
}

/** Allowed transitions of the authoritative server-side state machine. */
export const ALLOWED_TRANSITIONS: Record<CallStatus, CallStatus[]> = {
  created: ['validating', 'failed', 'cancelled'],
  validating: ['routing', 'blocked', 'failed'],
  routing: ['originating', 'failed', 'no_answer'],
  originating: ['ringing', 'failed', 'busy', 'no_answer', 'cancelled'],
  ringing: ['answered', 'failed', 'cancelled', 'busy', 'no_answer', 'rejected'],
  answered: ['active', 'failed'],
  active: ['held', 'transferring', 'ending', 'completed', 'failed'],
  held: ['active', 'ending', 'completed', 'failed'],
  transferring: ['active', 'ending', 'completed', 'failed'],
  ending: ['completed', 'failed'],
  completed: [],
  failed: [],
  rejected: [],
  cancelled: [],
  busy: [],
  no_answer: [],
  blocked: [],
};

export function canTransition(from: CallStatus, to: CallStatus): boolean {
  return (ALLOWED_TRANSITIONS[from] ?? []).includes(to);
}

export type CallMode = 'human' | 'ai';

export type CallDirection = 'outbound' | 'inbound';

export type RouteType =
  | 'ON_NET'
  | 'WEBRTC_LINK'
  | 'SIP_DIRECT'
  | 'SIP_FEDERATED'
  | 'ENUM'
  | 'PEER'
  | 'PSTN';

export type CostClass = 'FREE' | 'INFRASTRUCTURE_ONLY' | 'PAID_TELECOM';

export const ROUTE_LABELS: Record<RouteType, string> = {
  ON_NET: 'FREE ON-NET',
  WEBRTC_LINK: 'FREE INTERNET',
  SIP_DIRECT: 'SIP',
  SIP_FEDERATED: 'FEDERATED',
  ENUM: 'SIP (ENUM)',
  PEER: 'CONFIGURED PEER',
  PSTN: 'PSTN — COST MAY APPLY',
};

export interface RouteResolution {
  routeType: RouteType;
  costClass: CostClass;
  /** Normalized E.164 or SIP URI the route targets. */
  normalizedDestination: string;
  /** For ON_NET: the user id resolved. */
  targetUserId?: string;
  /** Estimated cost per minute (only for PAID_TELECOM). */
  estimatedCostPerMinute?: number;
  currency?: string;
  /** Human-readable explanation shown in dialer (§37 never hide classification). */
  explanation: string;
}

export type CallEventName =
  | 'call.created'
  | 'call.validating'
  | 'call.route_selected'
  | 'call.ringing'
  | 'call.answered'
  | 'call.active'
  | 'call.held'
  | 'call.resumed'
  | 'call.transfer_started'
  | 'call.transferred'
  | 'call.ended'
  | 'call.failed'
  | 'participant.joined'
  | 'participant.left'
  | 'agent.listening'
  | 'agent.thinking'
  | 'agent.speaking'
  | 'agent.tool.started'
  | 'agent.tool.completed'
  | 'transcript.partial'
  | 'transcript.final';

export interface RealtimeEvent {
  type: CallEventName | string;
  callId?: string;
  workspaceId?: string;
  timestamp: string;
  payload?: Record<string, unknown>;
}

export interface UserPublic {
  id: string;
  username: string;
  displayName: string;
  internetIdentity: string; // e.g. alice@opencall
  createdAt: string;
}

export interface Contact {
  id: string;
  workspaceId: string;
  displayName: string;
  email?: string | null;
  phoneNumber?: string | null;
  e164Number?: string | null;
  sipUri?: string | null;
  internetIdentity?: string | null;
  notes?: string | null;
  blocked: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface Call {
  id: string;
  workspaceId: string;
  createdBy: string;
  direction: CallDirection;
  mode: CallMode;
  requestedDestination: string;
  normalizedDestination: string;
  routeType: RouteType | null;
  status: CallStatus;
  createdAt: string;
  startedAt?: string | null;
  answeredAt?: string | null;
  endedAt?: string | null;
  durationSeconds: number;
  terminationReason?: string | null;
  roomName?: string | null;
  agentId?: string | null;
}

export interface ApiError {
  error: string;
  code: string;
  details?: unknown;
}

/** E.164-ish normalization result (§46 normalize input). */
export interface NormalizedDestination {
  raw: string;
  kind: 'internal' | 'sip' | 'e164' | 'unknown';
  internetIdentity?: string;
  sipUri?: string;
  e164?: string;
}
