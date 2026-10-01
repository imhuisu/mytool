/* 골프 뽑기 "함께하기" 서버 (Cloudflare Pages Functions + D1)
 *
 * 필요한 설정: Pages 프로젝트 → 설정 → 바인딩에 D1 데이터베이스를 변수 이름 GOLF_DB 로 연결.
 * 테이블은 첫 요청 때 자동으로 만든다. 연결이 없으면 503 {error:'no_db'}.
 *
 * GET  ?code=1234                         방 상태
 * POST {op:'create', names, fee, date, course, front, back}
 * POST {op:'draw',  code, hole, p}         p번 사람이 자기 카드 뒤집기
 * POST {op:'score', code, hole, p, score}  p번 사람이 자기 ± 확정. 4명 다 확정되면 홀 마감
 * POST {op:'unscore', code, hole, p}       확정 취소 (다른 사람이 다 넣기 전까지)
 * POST {op:'undo',  code, hole}            마지막으로 끝난 홀 되돌리기
 *
 * 카드: 홀마다 방 seed + 홀 번호로 섞은 5장 중 p번째가 p번 사람 카드 (누가 요청해도 같은 결과)
 */
const json = (o, status = 200) => new Response(JSON.stringify(o), {
  status, headers: { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store' },
});

const SCHEMA = [
  `CREATE TABLE IF NOT EXISTS golf_rooms (code TEXT PRIMARY KEY, seed TEXT NOT NULL, hole INTEGER NOT NULL DEFAULT 0,
     data TEXT NOT NULL, created INTEGER NOT NULL, updated INTEGER NOT NULL)`,
  `CREATE TABLE IF NOT EXISTS golf_plays (code TEXT NOT NULL, hole INTEGER NOT NULL, p INTEGER NOT NULL,
     drawn INTEGER NOT NULL DEFAULT 0, score INTEGER, done INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (code, hole, p))`,
];
let schemaReady = false;
async function ensureSchema(db) {
  if (schemaReady) return;
  for (const q of SCHEMA) await db.prepare(q).run();
  schemaReady = true;
}

/* 결정적 셔플 */
function hash(str) { let h = 2166136261; for (let i = 0; i < str.length; i++) { h ^= str.charCodeAt(i); h = Math.imul(h, 16777619); } return h >>> 0; }
function rng(seed) { let a = seed; return () => { a = (a + 0x6D2B79F5) | 0; let t = Math.imul(a ^ (a >>> 15), 1 | a); t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t; return ((t ^ (t >>> 14)) >>> 0) / 4294967296; }; }
function poolFor(seed, hole) {
  const r = rng(hash(seed + ':' + hole)), a = ['W', 'W', 'G', 'G', 'J'];
  for (let i = a.length - 1; i > 0; i--) { const j = Math.floor(r() * (i + 1)); [a[i], a[j]] = [a[j], a[i]]; }
  return a;
}
function jokerSide(colors) { return colors.filter(c => c === 'W').length < 2 ? 'W' : 'G'; }
function teamOf(colors, color) { const js = jokerSide(colors); return [0, 1, 2, 3].filter(p => colors[p] === color || (colors[p] === 'J' && js === color)); }

const clampStr = (s, n) => String(s ?? '').trim().slice(0, n);
const intIn = (v, lo, hi) => Number.isInteger(v) && v >= lo && v <= hi;

async function getRoom(db, code) {
  const r = await db.prepare('SELECT * FROM golf_rooms WHERE code=?').bind(code).first();
  if (!r) return null;
  return { code: r.code, seed: r.seed, hole: r.hole, data: JSON.parse(r.data), updated: r.updated };
}
async function getPlays(db, code, hole) {
  const { results } = await db.prepare('SELECT p, drawn, score, done FROM golf_plays WHERE code=? AND hole=?').bind(code, hole).all();
  const out = [0, 1, 2, 3].map(p => ({ p, drawn: false, score: null, done: false }));
  for (const r of results || []) if (r.p >= 0 && r.p < 4) out[r.p] = { p: r.p, drawn: !!r.drawn, score: r.score, done: !!r.done };
  return out;
}

async function state(db, code) {
  const room = await getRoom(db, code);
  if (!room) return json({ error: 'no_room' }, 404);
  const plays = await getPlays(db, code, room.hole);
  const pool = poolFor(room.seed, room.hole);
  const allDrawn = plays.every(x => x.drawn);
  return json({
    code, hole: room.hole, updated: room.updated, ...room.data,
    // 아직 안 뒤집은 사람 카드는 숨김
    plays: plays.map(x => ({ ...x, color: x.drawn ? pool[x.p] : null, score: allDrawn ? x.score : null })),
  });
}

/* 4명 모두 확정이면 홀 마감. hole 조건으로 한 번만 반영됨 */
async function tryFinish(db, code, hole) {
  const room = await getRoom(db, code);
  if (!room || room.hole !== hole) return;
  const plays = await getPlays(db, code, hole);
  if (!plays.every(x => x.drawn && x.done)) return;
  const colors = poolFor(room.seed, hole).slice(0, 4);
  const scores = plays.map(x => x.score | 0);
  const sum = c => teamOf(colors, c).reduce((a, p) => a + scores[p], 0);
  const w = sum('W'), g = sum('G');
  const jk = colors.indexOf('J');
  const data = room.data;
  data.history = (data.history || []).slice(0, hole);
  data.history.push({ colors, joker: jk >= 0 ? jk : null, result: w < g ? 'W' : g < w ? 'G' : 'T', scores });
  await db.prepare('UPDATE golf_rooms SET data=?, hole=hole+1, updated=? WHERE code=? AND hole=?')
    .bind(JSON.stringify(data), Date.now(), code, hole).run();
}

export async function onRequest({ request, env }) {
  const db = env.GOLF_DB;
  if (!db) return json({ error: 'no_db' }, 503);
  await ensureSchema(db);
  const url = new URL(request.url);

  if (request.method === 'GET') {
    const code = clampStr(url.searchParams.get('code'), 8);
    if (!/^\d{4}$/.test(code)) return json({ error: 'bad_code' }, 400);
    return state(db, code);
  }
  if (request.method !== 'POST') return json({ error: 'method' }, 405);

  let b;
  try { b = await request.json(); } catch { return json({ error: 'bad_json' }, 400); }
  const now = Date.now();

  if (b.op === 'create') {
    const names = Array.isArray(b.names) ? b.names.slice(0, 4).map((n, i) => clampStr(n, 8) || 'P' + (i + 1)) : [];
    if (names.length !== 4) return json({ error: 'bad_names' }, 400);
    const data = {
      names, fee: intIn(b.fee, 100, 10000000) ? b.fee : 5000,
      date: clampStr(b.date, 10), course: clampStr(b.course, 20), front: clampStr(b.front, 10), back: clampStr(b.back, 10),
      history: [],
    };
    // 오래된 방 정리 (2일)
    await db.prepare('DELETE FROM golf_plays WHERE code IN (SELECT code FROM golf_rooms WHERE updated < ?)').bind(now - 2 * 864e5).run();
    await db.prepare('DELETE FROM golf_rooms WHERE updated < ?').bind(now - 2 * 864e5).run();
    for (let tries = 0; tries < 20; tries++) {
      const code = String(1000 + Math.floor(Math.random() * 9000));
      const seed = crypto.randomUUID();
      const r = await db.prepare('INSERT OR IGNORE INTO golf_rooms (code, seed, hole, data, created, updated) VALUES (?,?,0,?,?,?)')
        .bind(code, seed, JSON.stringify(data), now, now).run();
      if (r.meta && r.meta.changes) return state(db, code);
    }
    return json({ error: 'busy' }, 503);
  }

  const code = clampStr(b.code, 8);
  if (!/^\d{4}$/.test(code)) return json({ error: 'bad_code' }, 400);
  const room = await getRoom(db, code);
  if (!room) return json({ error: 'no_room' }, 404);
  const hole = b.hole;
  if (!Number.isInteger(hole) || hole !== room.hole) return state(db, code);   // 이미 넘어간 홀이면 최신 상태만
  if (hole >= 18 && b.op !== 'undo') return state(db, code);

  if (b.op === 'draw' && intIn(b.p, 0, 3)) {
    await db.prepare(`INSERT INTO golf_plays (code, hole, p, drawn) VALUES (?,?,?,1)
      ON CONFLICT(code, hole, p) DO UPDATE SET drawn=1`).bind(code, hole, b.p).run();
  } else if (b.op === 'score' && intIn(b.p, 0, 3) && intIn(b.score, -3, 10)) {
    await db.prepare(`INSERT INTO golf_plays (code, hole, p, drawn, score, done) VALUES (?,?,?,1,?,1)
      ON CONFLICT(code, hole, p) DO UPDATE SET score=excluded.score, done=1`).bind(code, hole, b.p, b.score).run();
    await tryFinish(db, code, hole);
  } else if (b.op === 'unscore' && intIn(b.p, 0, 3)) {
    await db.prepare('UPDATE golf_plays SET done=0 WHERE code=? AND hole=? AND p=?').bind(code, hole, b.p).run();
  } else if (b.op === 'undo' && hole > 0) {
    // 직전 홀은 카드는 그대로 두고 ± 확정만 풀어서 다시 입력하게
    const data = room.data; data.history = (data.history || []).slice(0, hole - 1);
    await db.prepare('DELETE FROM golf_plays WHERE code=? AND hole>=?').bind(code, hole).run();
    await db.prepare('UPDATE golf_plays SET done=0 WHERE code=? AND hole=?').bind(code, hole - 1).run();
    await db.prepare('UPDATE golf_rooms SET data=?, hole=hole-1, updated=? WHERE code=? AND hole=?')
      .bind(JSON.stringify(data), now, code, hole).run();
  } else {
    return json({ error: 'bad_op' }, 400);
  }
  await db.prepare('UPDATE golf_rooms SET updated=? WHERE code=?').bind(now, code).run();
  return state(db, code);
}
