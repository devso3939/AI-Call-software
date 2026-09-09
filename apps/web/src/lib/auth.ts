'use client';

// Auth/session storage + typed fetch client for the OpenCall API.

export interface Me {
  id: string;
  username: string;
  displayName: string;
  internetIdentity: string;
}

const TOKEN_KEY = 'opencall.token';
const ME_KEY = 'opencall.me';

export function getToken(): string | null {
  if (typeof window === 'undefined') return null;
  return localStorage.getItem(TOKEN_KEY);
}

export function setSession(token: string, me: Me): void {
  localStorage.setItem(TOKEN_KEY, token);
  localStorage.setItem(ME_KEY, JSON.stringify(me));
}

export function getMe(): Me | null {
  if (typeof window === 'undefined') return null;
  const raw = localStorage.getItem(ME_KEY);
  return raw ? (JSON.parse(raw) as Me) : null;
}

export function clearSession(): void {
  localStorage.removeItem(TOKEN_KEY);
  localStorage.removeItem(ME_KEY);
}

export async function api<T>(path: string, opts: RequestInit = {}): Promise<T> {
  const token = getToken();
  const headers: Record<string, string> = {
    // Only declare JSON when there is actually a body — a bodyless POST with a
    // JSON content-type makes the API reject it as an empty JSON body (415/400).
    ...('body' in opts && opts.body != null ? { 'content-type': 'application/json' } : {}),
    ...(token ? { authorization: `Bearer ${token}` } : {}),
    ...(opts.headers as Record<string, string> | undefined),
  };
  const res = await fetch(path, { ...opts, headers });
  const body = await res.json().catch(() => ({}));
  if (!res.ok) {
    const err = new Error((body as { error?: string }).error ?? `HTTP ${res.status}`);
    (err as Error & { code?: string; status?: number }).code = (body as { code?: string }).code;
    (err as Error & { code?: string; status?: number }).status = res.status;
    throw err;
  }
  return body as T;
}
