const Database = require('better-sqlite3');
const db = new Database('data/opencall.db', { readonly: true });
const tables = db.prepare("SELECT name FROM sqlite_master WHERE type='table'").all().map(t => t.name);
console.log('TABLES:', tables.join(','));
const rows = db.prepare('SELECT id, created_by, direction, status, room_name, created_at FROM calls ORDER BY created_at DESC LIMIT 3').all();
rows.forEach(r => console.log(JSON.stringify(r)));
