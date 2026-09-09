import bcrypt from 'bcryptjs';
import crypto from 'node:crypto';
import { getDb, newId } from '@opencall/db';

// Session tokens are stored hashed (SHA-256) — plain token exists only at login time (§72).

const SESSION_TTL_MS = 1000 * 60 * 60 * 24 * 14; // 14 days

function hashToken(token: string): string {
  return crypto.createHash('sha256').update(token).digest('hex');
}

export interface SessionUser {
  id: string;
  username: string;
  displayName: string;
  internetIdentity: string;
}

export function registerUser(username: string, password: string, displayName: string): SessionUser {
  const db = getDb();
  const uname = username.trim().toLowerCase();
  if (!/^[a-z0-9_.-]{2,32}$/.test(uname)) {
    throw new AuthError('Username must be 2-32 chars: letters, digits, _ . -', 'invalid_username');
  }
  if (password.length < 8) {
    throw new AuthError('Password must be at least 8 characters', 'weak_password');
  }
  const exists = db.prepare('SELECT id FROM users WHERE username = ?').get(uname);
  if (exists) throw new AuthError('Username already taken', 'username_taken');

  const id = newId('usr');
  const identity = `${uname}@opencall`;
  const hash = bcrypt.hashSync(password, 10);

  // single default workspace for MVP
  let ws = db.prepare('SELECT id FROM workspaces LIMIT 1').get() as { id: string } | undefined;
  const tx = db.transaction(() => {
    if (!ws) {
      const wsId = newId('ws');
      db.prepare('INSERT INTO workspaces (id, name) VALUES (?, ?)').run(wsId, 'Default Workspace');
      ws = { id: wsId };
    }
    db.prepare(
      'INSERT INTO users (id, username, display_name, password_hash, internet_identity) VALUES (?, ?, ?, ?, ?)',
    ).run(id, uname, displayName.trim() || uname, hash, identity);
    db.prepare(
      'INSERT INTO workspace_members (workspace_id, user_id, role) VALUES (?, ?, ?)',
    ).run(ws.id, id, 'member');
  });
  tx();
  return { id, username: uname, displayName: displayName.trim() || uname, internetIdentity: identity };
}

export function verifyCredentials(username: string, password: string): SessionUser {
  const db = getDb();
  const uname = username.trim().toLowerCase();
  const row = db
    .prepare('SELECT id, username, display_name, password_hash, internet_identity FROM users WHERE username = ?')
    .get(uname) as
    | { id: string; username: string; display_name: string; password_hash: string; internet_identity: string }
    | undefined;
  if (!row || !bcrypt.compareSync(password, row.password_hash)) {
    throw new AuthError('Invalid username or password', 'invalid_credentials');
  }
  return {
    id: row.id,
    username: row.username,
    displayName: row.display_name,
    internetIdentity: row.internet_identity,
  };
}

export function createSession(userId: string): { token: string; expiresAt: string } {
  const db = getDb();
  const token = crypto.randomBytes(32).toString('base64url');
  const expiresAt = new Date(Date.now() + SESSION_TTL_MS).toISOString();
  db.prepare('INSERT INTO sessions (token_hash, user_id, expires_at) VALUES (?, ?, ?)').run(
    hashToken(token),
    userId,
    expiresAt,
  );
  return { token, expiresAt };
}

export function resolveSession(token: string | undefined | null): SessionUser | null {
  if (!token) return null;
  const db = getDb();
  const row = db
    .prepare(
      `SELECT s.expires_at, u.id, u.username, u.display_name, u.internet_identity
       FROM sessions s JOIN users u ON u.id = s.user_id
       WHERE s.token_hash = ?`,
    )
    .get(hashToken(token)) as
    | { expires_at: string; id: string; username: string; display_name: string; internet_identity: string }
    | undefined;
  if (!row) return null;
  if (new Date(row.expires_at).getTime() < Date.now()) {
    db.prepare('DELETE FROM sessions WHERE token_hash = ?').run(hashToken(token));
    return null;
  }
  return {
    id: row.id,
    username: row.username,
    displayName: row.display_name,
    internetIdentity: row.internet_identity,
  };
}

export function destroySession(token: string | undefined | null): void {
  if (!token) return;
  getDb().prepare('DELETE FROM sessions WHERE token_hash = ?').run(hashToken(token));
}

export function getWorkspaceIdForUser(userId: string): string {
  const db = getDb();
  const row = db
    .prepare('SELECT workspace_id FROM workspace_members WHERE user_id = ? LIMIT 1')
    .get(userId) as { workspace_id: string } | undefined;
  if (!row) throw new AuthError('User has no workspace', 'no_workspace');
  return row.workspace_id;
}

export class AuthError extends Error {
  code: string;
  constructor(message: string, code: string) {
    super(message);
    this.code = code;
  }
}
