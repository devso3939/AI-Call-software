const livekit = require('livekit-server-sdk');
const svc = new livekit.RoomServiceClient(
  'http://127.0.0.1:7880',
  'devkey',
  'opencalldevsecret_0123456789abcdef0123456789abcdef',
);

async function main() {
  const rooms = await svc.listRooms();
  console.log('ROOMS:', rooms.length);
  rooms.forEach((r) => console.log(' ', r.name, 'participants:', r.numParticipants));
  try {
    const ps = await svc.listParticipants('call_call_mttegdvf3eu8kqrd');
    console.log('PARTICIPANTS in active call room:');
    ps.forEach((p) => {
      const tracks = (p.tracks || []).map((t) => ({ sid: t.sid, type: t.type, muted: t.muted }));
      console.log(' -', p.identity, p.name, 'tracks:', JSON.stringify(tracks));
    });
  } catch (e) {
    console.log('listParticipants error:', e.message);
  }
}

main().catch((e) => { console.error('FATAL', e); process.exit(1); });
