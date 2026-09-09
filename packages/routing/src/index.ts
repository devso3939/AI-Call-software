// Routing engine — Master Prompt §46 pipeline, §47 route types, §37 visible classification.
// Internet-first: internal users → SIP URI → ENUM → federation → configured peer → PSTN → NO ROUTE.

import { getDb } from '@opencall/db';
import type { NormalizedDestination, RouteResolution } from '@opencall/types';

export class NoRouteAvailableError extends Error {
  code = 'no_route';
  constructor(public rawInput: string) {
    super(`No route available for "${rawInput}"`);
  }
}

/** Detects whether a raw E.164 or pseudo-E.164 digit string looks like a real PSTN number. */
function isPhoneNumber(raw: string): boolean {
  const compact = raw.replace(/[\s().-]/g, '');
  return /^\+?[0-9]{8,15}$/.test(compact);
}

export function normalizeDestination(input: string): NormalizedDestination {
  const raw = input.trim();
  const lower = raw.toLowerCase();

  if (lower.startsWith('sip:') || lower.startsWith('sips:')) {
    return { raw, kind: 'sip', sipUri: lower };
  }

  // user@opencall or user@domain
  if (/^[a-z0-9_.-]+@[a-z0-9_.-]+$/.test(lower)) {
    const [, domain] = lower.split('@');
    if (domain === 'opencall') {
      return { raw, kind: 'internal', internetIdentity: lower };
    }
    return { raw, kind: 'sip', sipUri: `sip:${lower}` };
  }

  // bare internal username (no @): try internal first — e.g. "alice"
  if (/^[a-z0-9_.-]{2,32}$/i.test(raw)) {
    return { raw, kind: 'internal', internetIdentity: `${lower}@opencall` };
  }

  if (isPhoneNumber(raw)) {
    const digits = raw.replace(/[^0-9]/g, '');
    const e164 = raw.startsWith('+') ? `+${digits}` : `+${digits}`;
    return { raw, kind: 'e164', e164 };
  }

  return { raw, kind: 'unknown' };
}

async function resolveInternal(n: NormalizedDestination): Promise<RouteResolution | null> {
  if (n.kind !== 'internal' || !n.internetIdentity) return null;
  const row = getDb()
    .prepare('SELECT id FROM users WHERE internet_identity = ?')
    .get(n.internetIdentity) as { id: string } | undefined;
  if (!row) return null;
  return {
    routeType: 'ON_NET',
    costClass: 'FREE',
    normalizedDestination: n.internetIdentity,
    targetUserId: row.id,
    explanation: 'Recipient is an OpenCall user — free Internet call.',
  };
}

async function resolveSip(n: NormalizedDestination): Promise<RouteResolution | null> {
  if (n.kind !== 'sip' || !n.sipUri) return null;
  // MVP: SIP_DIRECT classification with honest "not yet federated" note.
  // LiveKit SIP / federation activation arrives in Phase 6 — never fake connectivity (§116).
  return {
    routeType: 'SIP_DIRECT',
    costClass: 'INFRASTRUCTURE_ONLY',
    normalizedDestination: n.sipUri,
    explanation:
      'SIP destination recognized. Routing via LiveKit SIP bridge is not yet configured — classification only.',
  };
}

async function resolveEnum(_n: NormalizedDestination): Promise<RouteResolution | null> {
  // §50: ENUM is optional and off by default. Hook point for e164.arpa lookups.
  return null;
}

async function resolveFederation(_n: NormalizedDestination): Promise<RouteResolution | null> {
  // §138: federation is future work; never trust arbitrary peers by default.
  return null;
}

async function resolvePeer(_n: NormalizedDestination): Promise<RouteResolution | null> {
  // §46: configured peers arrive with BYOC (Phase 7).
  return null;
}

async function resolvePstn(n: NormalizedDestination): Promise<RouteResolution | null> {
  if (n.kind !== 'e164' || !n.e164) return null;
  const pstnEnabled = process.env.PSTN_ENABLED === 'true';
  if (!pstnEnabled) {
    return {
      routeType: 'PSTN',
      costClass: 'PAID_TELECOM',
      normalizedDestination: n.e164,
      explanation:
        'Telephone number. PSTN termination is disabled in this deployment (PSTN_ENABLED=false). ' +
        'No legitimate telecom route is configured — the call would require a paid carrier.',
    };
  }
  // Phase 7 will consult configured providers + rate decks here.
  return null;
}

export async function resolveDestination(input: string): Promise<RouteResolution> {
  const destination = normalizeDestination(input);

  const internal = await resolveInternal(destination);
  if (internal) return internal;

  const sip = await resolveSip(destination);
  if (sip) return sip;

  const enumRoute = await resolveEnum(destination);
  if (enumRoute) return enumRoute;

  const federation = await resolveFederation(destination);
  if (federation) return federation;

  const peer = await resolvePeer(destination);
  if (peer) return peer;

  const pstn = await resolvePstn(destination);
  if (pstn) return pstn;

  throw new NoRouteAvailableError(input);
}
