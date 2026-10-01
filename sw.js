/* 오프라인 캐시 (서비스 워커)
 *
 * - 페이지(HTML): 온라인이면 항상 최신을 받아오고, 오프라인이면 저장해둔 걸 보여줌
 * - 그 외 파일(아이콘, 스크립트, 폰트): 저장본을 먼저 쓰고 뒤에서 새로 받아둠
 *
 * 보통은 건드릴 일 없음. hub.js나 아이콘을 바꿨는데 폰에 안 바뀌면 VERSION 숫자만 올리기.
 */
const VERSION = 'v2';
const CACHE = 'tools-' + VERSION;

const CORE = [
  './',
  'hub.js',
  'manifest.webmanifest',
  'icons/icon-192.png',
  'icons/icon-512.png',
  'icons/favicon.png',
];

self.addEventListener('install', e => {
  const base = self.registration.scope;
  e.waitUntil(
    caches.open(CACHE)
      .then(c => c.addAll(CORE.map(p => new URL(p, base).href)))
      .then(() => self.skipWaiting())
  );
});

self.addEventListener('activate', e => {
  e.waitUntil(
    caches.keys()
      .then(keys => Promise.all(keys.filter(k => k.startsWith('tools-') && k !== CACHE).map(k => caches.delete(k))))
      .then(() => self.clients.claim())
  );
});

function isFont(url) {
  return url.hostname === 'fonts.googleapis.com' || url.hostname === 'fonts.gstatic.com';
}

/* 네트워크 먼저, 3초 안에 응답 없거나 실패하면 저장본 */
async function networkFirst(req) {
  const cache = await caches.open(CACHE);
  try {
    const res = await Promise.race([
      fetch(req),
      new Promise((_, rej) => setTimeout(() => rej(new Error('timeout')), 3000)),
    ]);
    if (res && res.ok) cache.put(req.url, res.clone());
    return res;
  } catch (err) {
    const hit = await cache.match(req.url, { ignoreSearch: true });
    if (hit) return hit;
    const home = await cache.match(new URL('./', self.registration.scope).href);
    if (home) return home;
    throw err;
  }
}

/* 저장본 먼저, 뒤에서 새로 받아 갱신 */
async function staleWhileRevalidate(req) {
  const cache = await caches.open(CACHE);
  const hit = await cache.match(req, { ignoreSearch: !isFont(new URL(req.url)) });
  const fresh = fetch(req).then(res => {
    if (res && (res.ok || res.type === 'opaque')) cache.put(req, res.clone());
    return res;
  }).catch(() => hit || Response.error());
  return hit || fresh;
}

self.addEventListener('fetch', e => {
  const req = e.request;
  if (req.method !== 'GET') return;
  const url = new URL(req.url);
  const sameOrigin = url.origin === self.location.origin;
  if (sameOrigin && url.pathname.includes('/api/')) return;   // 서버 API(골프 함께하기)는 항상 네트워크

  if (req.mode === 'navigate' && sameOrigin) {
    e.respondWith(networkFirst(req));
  } else if (sameOrigin && req.headers.get('accept')?.includes('text/html')) {
    e.respondWith(networkFirst(req));           // 허브가 미리 받아두는 도구 페이지
  } else if (sameOrigin || isFont(url)) {
    e.respondWith(staleWhileRevalidate(req));
  }
});
