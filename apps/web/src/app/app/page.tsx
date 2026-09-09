'use client';

// Main app shell: dialer (§37), incoming call (§38), active call controls (§84),
// contacts, history (§86), guest links (§39). Server is authoritative; WS for events (§45).

import { useCallback, useEffect, useRef, useState } from 'react';
import { useRouter } from 'next/navigation';
import { api, getToken, getMe, clearSession, type Me } from '@/lib/auth';
import { useCall } from '@/lib/useCall';

interface UserRow {
  id: string; username: string; displayName: string; internetIdentity: string; online: boolean;
}
interface RouteEstimate {
  label: string; routeType: string | null; costClass: string | null;
  normalizedDestination?: string; explanation: string;
}
interface IncomingCall {
  callId: string; roomName: string;
  from: { id: string; displayName: string; identity: string };
}
interface CallRow {
  id: string; requested_destination: string; route_type: string | null; status: string;
  mode: string; duration_seconds: number; created_at: string;
}

const API_WS = `${window.location.protocol === 'https:' ? 'wss' : 'ws'}://${window.location.host}/ws`;

type Tab = 'dialer' | 'contacts' | 'history' | 'links';

export default function AppPage() {
  const router = useRouter();
  const [me, setMe] = useState<Me | null>(null);
  const [users, setUsers] = useState<UserRow[]>([]);
  const [tab, setTab] = useState<Tab>('dialer');
  const [dial, setDial] = useState('');
  const [estimate, setEstimate] = useState<RouteEstimate | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [incoming, setIncoming] = useState<IncomingCall | null>(null);
  const [history, setHistory] = useState<CallRow[]>([]);
  const [guestUrl, setGuestUrl] = useState<string | null>(null);
  const wsRef = useRef<WebSocket | null>(null);

  const callApi = {
    answer: (callId: string) => api<{ joinToken: string; livekitUrl: string; roomName: string }>(`/v1/calls/${callId}/answer`, { method: 'POST' }),
    hangup: (callId: string) => api(`/v1/calls/${callId}/hangup`, { method: 'POST' }),
  };
  const call = useCall(callApi);

  // ---- boot: session guard + user list ----
  useEffect(() => {
    const token = getToken();
    if (!token) { router.replace('/login'); return; }
    const m = getMe();
    if (m) setMe(m);
    api<{ user: Me }>('/v1/me')
      .then((r) => { setMe(r.user); localStorage.setItem('opencall.me', JSON.stringify(r.user)); })
      .catch(() => { clearSession(); router.replace('/login'); });
    api<{ users: UserRow[] }>('/v1/users').then((r) => setUsers(r.users)).catch(() => undefined);
  }, [router]);

  const refreshHistory = useCallback(() => {
    api<{ calls: CallRow[] }>('/v1/calls?limit=50')
      .then((r) => setHistory(r.calls))
      .catch(() => undefined);
  }, []);
  useEffect(refreshHistory, [refreshHistory]);

  // ---- realtime events (incoming call etc.) ----
  useEffect(() => {
    const token = getToken();
    if (!token) return;
    const ws = new WebSocket(`${API_WS}?token=${encodeURIComponent(token)}`);
    wsRef.current = ws;
    ws.onmessage = (msg) => {
      try {
        const ev = JSON.parse(msg.data);
        if (ev.type === 'call.created' && ev.payload?.from) {
          setIncoming({
            callId: ev.callId ?? ev.payload.callId,
            roomName: ev.payload.roomName,
            from: ev.payload.from,
          });
        }
        if (ev.type === 'call.ended' || ev.type === 'call.failed' || ev.type === 'call.rejected') {
          setIncoming(null);
          refreshHistory();
        }
      } catch { /* ignore */ }
    };
    return () => ws.close();
  }, [refreshHistory]);

  // ---- route estimate as you type (§37 — never hide classification) ----
  useEffect(() => {
    if (!dial.trim()) { setEstimate(null); return; }
    const t = setTimeout(() => {
      api<RouteEstimate>(`/v1/routes/estimate?destination=${encodeURIComponent(dial.trim())}`)
        .then(setEstimate)
        .catch(() => setEstimate(null));
    }, 250);
    return () => clearTimeout(t);
  }, [dial]);

  // ---- actions ----
  async function placeCall(destination: string) {
    setError(null);
    try {
      const res = await api<{
        callId: string; roomName: string; joinToken: string; livekitUrl: string;
      }>('/v1/calls', {
        method: 'POST',
        body: JSON.stringify({ destination, mode: 'human' }),
      });
      await call.connectToRoom(res.joinToken, res.livekitUrl, res.callId, res.roomName);
    } catch (err) {
      setError((err as Error).message);
    }
  }

  async function acceptIncoming() {
    if (!incoming) return;
    try {
      const res = await callApi.answer(incoming.callId);
      setIncoming(null);
      await call.connectToRoom(res.joinToken, res.livekitUrl, incoming.callId, res.roomName);
    } catch (err) {
      setError((err as Error).message);
      setIncoming(null);
    }
  }

  async function rejectIncoming() {
    if (!incoming) return;
    await api(`/v1/calls/${incoming.callId}/reject`, { method: 'POST' }).catch(() => undefined);
    setIncoming(null);
  }

  async function createGuestLink() {
    try {
      const res = await api<{ url: string }>('/v1/guest-links', {
        method: 'POST', body: JSON.stringify({ expiresInMinutes: 60 }),
      });
      setGuestUrl(res.url);
    } catch (err) {
      setError((err as Error).message);
    }
  }

  function logout() {
    api('/v1/auth/logout', { method: 'POST' }).catch(() => undefined);
    clearSession();
    router.replace('/login');
  }

  if (!me) {
    return <main className="flex min-h-screen items-center justify-center"><p className="text-slate-500">Loading…</p></main>;
  }

  const dialable = users.filter((u) => u.id !== me.id);

  return (
    <main className="mx-auto min-h-screen w-full max-w-3xl p-4 pb-24">
      {/* header */}
      <header className="mb-4 flex flex-wrap items-center justify-between gap-2">
        <div>
          <h1 className="text-xl font-bold text-white">OpenCall AI</h1>
          <p className="text-xs text-slate-400">
            {me.displayName} · {me.internetIdentity} · <span className="text-mint-400">online</span>
          </p>
        </div>
        <button className="btn-ghost" onClick={logout}>Sign out</button>
      </header>

      {error && <p className="mb-3 rounded-lg bg-red-950/60 px-3 py-2 text-sm text-red-300">{error}</p>}

      {/* incoming call banner */}
      {incoming && (
        <div className="mb-4 rounded-2xl border border-mint-500/40 bg-ink-800 p-4">
          <p className="text-sm text-slate-300">
            Incoming call from <span className="font-bold text-white">{incoming.from.displayName}</span>{' '}
            <span className="text-slate-500">({incoming.from.identity})</span>
          </p>
          <div className="mt-3 flex gap-2">
            <button className="btn-primary flex-1" onClick={acceptIncoming}>Accept</button>
            <button className="btn-danger flex-1" onClick={rejectIncoming}>Reject</button>
          </div>
        </div>
      )}

      {/* active call panel */}
      {call.state.phase !== 'idle' && (
        <div className="mb-4 rounded-2xl border border-ink-600 bg-ink-800 p-4">
          <div className="flex items-center justify-between">
            <div>
              <p className="font-semibold text-white">
                {call.state.phase === 'active' ? 'In call' :
                 call.state.phase === 'connecting' ? 'Connecting…' :
                 call.state.phase === 'ringing' ? 'Ringing…' : 'Call ended'}
              </p>
              <p className="text-xs text-slate-400">
                {call.state.remoteDisplayName ?? 'waiting for other party'} · quality:{' '}
                <span className={call.state.quality === 'poor' ? 'text-red-400' : 'text-mint-400'}>
                  {call.state.quality}
                </span>
                {call.state.stats.rttMs != null && <> · rtt {call.state.stats.rttMs}ms</>}
              </p>
            </div>
            <div className="flex gap-2">
              <button className="btn-ghost" onClick={() => call.mute(!call.state.muted)}>
                {call.state.muted ? 'Unmute' : 'Mute'}
              </button>
              <button className="btn-danger" onClick={() => call.hangup()}>End</button>
            </div>
          </div>
        </div>
      )}

      {/* tabs */}
      <nav className="mb-4 grid grid-cols-4 gap-1 rounded-xl bg-ink-800 p-1 text-sm font-medium">
        {(['dialer', 'contacts', 'history', 'links'] as Tab[]).map((t) => (
          <button key={t} onClick={() => setTab(t)}
            className={`rounded-lg px-2 py-1.5 capitalize ${tab === t ? 'bg-ink-600 text-white' : 'text-slate-400'}`}>
            {t}
          </button>
        ))}
      </nav>

      {tab === 'dialer' && (
        <section className="space-y-4">
          <div className="card">
            <label className="label">Search contact, SIP address or number</label>
            <div className="flex gap-2">
              <input className="input" value={dial} onChange={(e) => setDial(e.target.value)}
                placeholder="alice · alice@opencall · sip:bob@example.net · +995555123456" />
              <button
                className="btn-primary whitespace-nowrap"
                disabled={!estimate || estimate.routeType !== 'ON_NET' || call.state.phase !== 'idle'}
                onClick={() => placeCall(dial.trim())}
                title={estimate && estimate.routeType !== 'ON_NET' ? estimate.explanation : 'Place free on-net call'}
              >
                Call
              </button>
            </div>
            {estimate && (
              <div className="mt-3 rounded-xl bg-ink-800 p-3 text-sm">
                <p className={`font-semibold ${estimate.routeType === 'ON_NET' ? 'text-mint-400' : 'text-amber-400'}`}>
                  {estimate.label}
                </p>
                <p className="mt-1 text-xs text-slate-400">{estimate.explanation}</p>
              </div>
            )}
            {dial && estimate?.routeType === 'PSTN' && (
              <button className="btn-ghost mt-3 w-full" onClick={createGuestLink}>
                Send free browser call link instead (invite)
              </button>
            )}
            {guestUrl && tab === 'dialer' && (
              <div className="mt-3 rounded-xl bg-ink-800 p-3">
                <p className="text-xs text-slate-400">Free browser call link created — share it with the person you want to call:</p>
                <p className="mt-1 break-all text-sm text-mint-400">{guestUrl}</p>
                <button className="btn-ghost mt-2" onClick={() => navigator.clipboard.writeText(guestUrl)}>
                  Copy link
                </button>
              </div>
            )}
          </div>

          <div className="card">
            <p className="label">OpenCall users {dialable.length > 0 ? `(${dialable.length})` : ''}</p>
            {dialable.length === 0 && (
              <p className="text-sm text-slate-500">
                No other users yet. Register a second account in another browser profile to test calls.
              </p>
            )}
            <ul className="divide-y divide-ink-700">
              {dialable.map((u) => (
                <li key={u.id} className="flex items-center justify-between py-2">
                  <div>
                    <p className="text-sm font-medium text-white">{u.displayName}</p>
                    <p className="text-xs text-slate-500">{u.internetIdentity} · {u.online ? 'online' : 'offline'}</p>
                  </div>
                  <button className="btn-primary" disabled={call.state.phase !== 'idle'}
                    onClick={() => placeCall(u.internetIdentity)}>
                    Call
                  </button>
                </li>
              ))}
            </ul>
          </div>
        </section>
      )}

      {tab === 'contacts' && <ContactsTab />}

      {tab === 'history' && (
        <section className="card overflow-x-auto">
          <p className="label">Call history</p>
          {history.length === 0 && <p className="text-sm text-slate-500">No calls yet.</p>}
          <table className="w-full text-left text-sm">
            <thead>
              <tr className="text-xs uppercase text-slate-500">
                <th className="py-2 pr-3">Destination</th>
                <th className="py-2 pr-3">Route</th>
                <th className="py-2 pr-3">Status</th>
                <th className="py-2 pr-3">Duration</th>
                <th className="py-2">When</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-ink-700">
              {history.map((c) => (
                <tr key={c.id}>
                  <td className="py-2 pr-3 font-medium text-white">{c.requested_destination}</td>
                  <td className="py-2 pr-3 text-slate-400">{c.route_type ?? '—'}</td>
                  <td className="py-2 pr-3">
                    <span className={c.status === 'completed' ? 'text-mint-400' : 'text-amber-400'}>{c.status}</span>
                  </td>
                  <td className="py-2 pr-3">{c.duration_seconds}s</td>
                  <td className="py-2 text-slate-500">{new Date(c.created_at).toLocaleString()}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </section>
      )}

      {tab === 'links' && (
        <section className="card">
          <p className="label">Guest browser call links (§39 — converts PSTN calls into free Internet calls)</p>
          <p className="mb-3 text-sm text-slate-400">
            Send a short-lived link; the recipient joins from their browser with no account.
          </p>
          <button className="btn-primary" onClick={createGuestLink}>Create guest link</button>
          {guestUrl && (
            <div className="mt-3 rounded-xl bg-ink-800 p-3">
              <p className="break-all text-sm text-mint-400">{guestUrl}</p>
              <button className="btn-ghost mt-2" onClick={() => navigator.clipboard.writeText(guestUrl)}>
                Copy
              </button>
            </div>
          )}
        </section>
      )}
    </main>
  );
}

function ContactsTab() {
  const [contacts, setContacts] = useState<Array<{ id: string; displayName: string; e164Number: string | null; sipUri: string | null; internetIdentity: string | null }>>([]);
  const [displayName, setDisplayName] = useState('');
  const [dest, setDest] = useState('');

  const load = useCallback(() => {
    api<{ contacts: typeof contacts }>('/v1/contacts').then((r) => setContacts(r.contacts)).catch(() => undefined);
  }, []);
  useEffect(load, [load]);

  async function add(e: React.FormEvent) {
    e.preventDefault();
    const body = dest.startsWith('sip:') || dest.includes('@')
      ? { displayName, sipUri: dest.startsWith('sip:') ? dest : undefined, internetIdentity: dest.endsWith('@opencall') ? dest : undefined, phoneNumber: undefined }
      : { displayName, phoneNumber: dest };
    await api('/v1/contacts', { method: 'POST', body: JSON.stringify(body) }).catch(() => undefined);
    setDisplayName(''); setDest('');
    load();
  }

  return (
    <section className="space-y-4">
      <form className="card space-y-3" onSubmit={add}>
        <p className="label">Add contact</p>
        <input className="input" value={displayName} onChange={(e) => setDisplayName(e.target.value)} placeholder="Display name" required />
        <input className="input" value={dest} onChange={(e) => setDest(e.target.value)} placeholder="alice@opencall · sip:x@y.z · +995…" required />
        <button className="btn-primary">Add</button>
      </form>
      <div className="card">
        {contacts.length === 0 && <p className="text-sm text-slate-500">No contacts.</p>}
        <ul className="divide-y divide-ink-700">
          {contacts.map((c) => (
            <li key={c.id} className="py-2">
              <p className="text-sm font-medium text-white">{c.displayName}</p>
              <p className="text-xs text-slate-500">{c.internetIdentity ?? c.sipUri ?? c.e164Number ?? '—'}</p>
            </li>
          ))}
        </ul>
      </div>
    </section>
  );
}
