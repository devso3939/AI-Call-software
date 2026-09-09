const Database = require('better-sqlite3');
const db = new Database('data/opencall.db', { readonly: true });
const legs = db.prepare('SELECT * FROM call_legs ORDER BY rowid DESC LIMIT 6').all();
legs.forEach(r => console.log(JSON.stringify(r)));
