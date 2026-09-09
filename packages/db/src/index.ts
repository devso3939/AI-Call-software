import Database from 'better-sqlite3';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

let db: Database.Database | null = null;

export function openDb(): Database.Database {
  if (db) return db;
  const url = process.env.DATABASE_URL ?? 'sqlite://data/opencall.db';
  if (!url.startsWith('sqlite://')) {
    throw new Error(
      `Unsupported DATABASE_URL "${url}". This build supports sqlite:// (dev). ` +
      'Postgres support arrives with the Postgres migration; schema is portable.',
    );
  }
  const file = url.slice('sqlite://'.length);
  const abs = path.resolve(process.cwd(), file);
  fs.mkdirSync(path.dirname(abs), { recursive: true });
  db = new Database(abs);
  db.pragma('journal_mode = WAL');
  db.pragma('foreign_keys = ON');
  migrate(db);
  return db;
}

function migrate(d: Database.Database): void {
  const migrationsDir = path.join(__dirname, 'migrations');
  d.exec('CREATE TABLE IF NOT EXISTS _migrations (name TEXT PRIMARY KEY, applied_at TEXT NOT NULL)');
  const applied = new Set(
    (d.prepare('SELECT name FROM _migrations').all() as { name: string }[]).map((r) => r.name),
  );
  const files = fs.readdirSync(migrationsDir).filter((f) => f.endsWith('.sql')).sort();
  for (const f of files) {
    if (applied.has(f)) continue;
    const sql = fs.readFileSync(path.join(migrationsDir, f), 'utf8');
    const tx = d.transaction(() => {
      d.exec(sql);
      d.prepare('INSERT INTO _migrations (name, applied_at) VALUES (?, ?)').run(
        f,
        new Date().toISOString(),
      );
    });
    tx();
    console.log(`[db] applied migration ${f}`);
  }
}

export function getDb(): Database.Database {
  if (!db) throw new Error('Database not opened — call openDb() first');
  return db;
}

export function closeDb(): void {
  db?.close();
  db = null;
}

/** Deterministic-ish unique id with a readable prefix (sortable enough for MVP). */
export function newId(prefix: string): string {
  const rand = Math.random().toString(36).slice(2, 10);
  return `${prefix}_${Date.now().toString(36)}${rand}`;
}
