/* 일정 동기화 서버 (Cloudflare Pages Functions + D1, 바인딩 GOLF_DB 공용)
 *
 * 내용은 브라우저에서 비밀번호로 암호화(AES-GCM)해서 올라온다. 서버는 암호문만 저장하고 열어볼 수 없다.
 * id = 비밀번호에서 만든 SHA-256 (64자리 hex). 같은 비밀번호면 같은 저장 칸.
 *
 * GET  ?id=…                    → {ver, data}   (없으면 ver 0, data null)
 * POST {id, base, data}         → {ver}         base = 마지막으로 받은 ver. 그 사이 다른 기기가 올렸으면 409 {conflict, ver, data}
 */
const json = (o, status = 200) => new Response(JSON.stringify(o), {
  status, headers: { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store' },
});

let schemaReady = false;
async function ensureSchema(db) {
  if (schemaReady) return;
  await db.prepare(`CREATE TABLE IF NOT EXISTS sched_store (id TEXT PRIMARY KEY, ver INTEGER NOT NULL,
    data TEXT NOT NULL, updated INTEGER NOT NULL)`).run();
  schemaReady = true;
}

const MAX = 3 * 1024 * 1024;
const okId = id => typeof id === 'string' && /^[0-9a-f]{64}$/.test(id);

export async function onRequest({ request, env }) {
  const db = env.GOLF_DB;
  if (!db) return json({ error: 'no_db' }, 503);
  await ensureSchema(db);

  if (request.method === 'GET') {
    const id = new URL(request.url).searchParams.get('id');
    if (!okId(id)) return json({ error: 'bad_id' }, 400);
    const r = await db.prepare('SELECT ver, data FROM sched_store WHERE id=?').bind(id).first();
    return json(r ? { ver: r.ver, data: r.data } : { ver: 0, data: null });
  }
  if (request.method !== 'POST') return json({ error: 'method' }, 405);

  let b;
  try { b = await request.json(); } catch { return json({ error: 'bad_json' }, 400); }
  if (!okId(b.id)) return json({ error: 'bad_id' }, 400);
  if (typeof b.data !== 'string' || b.data.length > MAX) return json({ error: 'bad_data' }, 400);
  const base = Number.isInteger(b.base) && b.base >= 0 ? b.base : 0;
  const now = Date.now();

  // base가 지금 버전과 같을 때만 반영 (아니면 다른 기기가 먼저 올린 것)
  const r = await db.prepare(`INSERT INTO sched_store (id, ver, data, updated) VALUES (?, 1, ?, ?)
    ON CONFLICT(id) DO UPDATE SET ver = sched_store.ver + 1, data = excluded.data, updated = excluded.updated
    WHERE sched_store.ver = ?`).bind(b.id, b.data, now, base).run();
  const cur = await db.prepare('SELECT ver, data FROM sched_store WHERE id=?').bind(b.id).first();
  if (r.meta && r.meta.changes) return json({ ver: cur.ver });
  return json({ error: 'conflict', ver: cur.ver, data: cur.data }, 409);
}
