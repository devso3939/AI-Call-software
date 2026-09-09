'use client';

// Login / Register (§36 screens). Real session tokens from the API — no fake auth.

import { useState } from 'react';
import { useRouter } from 'next/navigation';
import { api, setSession, type Me } from '@/lib/auth';

export default function LoginPage() {
  const router = useRouter();
  const [mode, setMode] = useState<'login' | 'register'>('login');
  const [username, setUsername] = useState('');
  const [displayName, setDisplayName] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    setBusy(true);
    try {
      const path = mode === 'login' ? '/v1/auth/login' : '/v1/auth/register';
      const body = mode === 'login' ? { username, password } : { username, password, displayName };
      const res = await api<{ user: Me; token: string }>(path, { method: 'POST', body: JSON.stringify(body) });
      setSession(res.token, res.user);
      router.replace('/app');
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <main className="flex min-h-screen items-center justify-center p-4">
      <div className="card w-full max-w-sm">
        <h1 className="mb-1 text-2xl font-bold text-white">OpenCall AI</h1>
        <p className="mb-6 text-sm text-slate-400">Free Internet calling. Local AI agents. Self-hostable.</p>

        <div className="mb-4 grid grid-cols-2 gap-1 rounded-xl bg-ink-800 p-1 text-sm font-medium">
          <button
            className={`rounded-lg px-3 py-1.5 ${mode === 'login' ? 'bg-ink-600 text-white' : 'text-slate-400'}`}
            onClick={() => setMode('login')}
          >
            Sign in
          </button>
          <button
            className={`rounded-lg px-3 py-1.5 ${mode === 'register' ? 'bg-ink-600 text-white' : 'text-slate-400'}`}
            onClick={() => setMode('register')}
          >
            Register
          </button>
        </div>

        <form onSubmit={submit} className="space-y-3">
          <div>
            <label className="label">Username</label>
            <input className="input" value={username} onChange={(e) => setUsername(e.target.value)}
              placeholder="alice" autoComplete="username" required />
            {mode === 'register' && (
              <p className="mt-1 text-xs text-slate-500">Your free Internet identity: {username || 'you'}@opencall</p>
            )}
          </div>
          {mode === 'register' && (
            <div>
              <label className="label">Display name</label>
              <input className="input" value={displayName} onChange={(e) => setDisplayName(e.target.value)}
                placeholder="Alice" />
            </div>
          )}
          <div>
            <label className="label">Password</label>
            <input className="input" type="password" value={password} onChange={(e) => setPassword(e.target.value)}
              placeholder="••••••••" autoComplete={mode === 'login' ? 'current-password' : 'new-password'} required />
          </div>
          {error && <p className="rounded-lg bg-red-950/60 px-3 py-2 text-sm text-red-300">{error}</p>}
          <button className="btn-primary w-full" disabled={busy}>
            {busy ? 'Working…' : mode === 'login' ? 'Sign in' : 'Create account'}
          </button>
        </form>
      </div>
    </main>
  );
}
