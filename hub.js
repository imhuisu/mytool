/* 모든 페이지 공통: 오프라인 캐시 등록 + (앱으로 설치했을 때·마이툴 안드로이드 앱 안) 홈 버튼
 * 도구 페이지 <head>에 한 줄만 넣으면 됨:  <script src="../hub.js" defer></script>
 */
(function () {
  const self = document.currentScript || document.querySelector('script[src$="hub.js"]');
  const ROOT = new URL('./', self.src).href;          // 도구함 첫 화면 주소
  window.HUB_ROOT = ROOT;

  if ('serviceWorker' in navigator) {
    navigator.serviceWorker.register(new URL('sw.js', ROOT).href, { scope: ROOT }).catch(() => {});
  }

  /* 마이툴 안드로이드 앱 안: 사이트 app/version.json 이 앱 버전(UA 'MytoolApp/1.9')보다 새로우면
   * 오른쪽 아래에 "⬆ 앱 업데이트" 버튼 → 플레이스토어. 일정 화면은 머리에 자기 버튼(#updBtn)이 있어 건너뜀 */
  const appVer = (/MytoolApp\/([\d.]+)/.exec(navigator.userAgent) || [])[1];
  if (appVer) {
    const gt = (a, b) => { const x = a.split('.').map(Number), y = b.split('.').map(Number);
      for (let i = 0; i < Math.max(x.length, y.length); i++) { const d = (x[i] || 0) - (y[i] || 0); if (d) return d > 0; } return false; };
    const show = v => {
      if (document.getElementById('updBtn')) return;
      const b = document.createElement('a');
      b.href = 'market://details?id=com.imhuisu.mytool.sched';
      b.textContent = '⬆ 앱 업데이트 ' + v;
      b.style.cssText = 'position:fixed;z-index:2147483000;right:12px;bottom:calc(16px + env(safe-area-inset-bottom,0px));' +
        'padding:10px 14px;border-radius:999px;background:#d1493a;color:#fff;font:700 14px/1 system-ui,sans-serif;' +
        'text-decoration:none;box-shadow:0 2px 10px rgba(0,0,0,.25)';
      document.body.appendChild(b);
    };
    fetch(new URL('app/version.json', ROOT).href, { cache: 'no-store' }).then(r => r.json())
      .then(v => { if (v.versionName && gt(v.versionName, appVer)) {
        document.body ? show(v.versionName) : document.addEventListener('DOMContentLoaded', () => show(v.versionName)); } })
      .catch(() => {});
  }

  /* 마이툴 안드로이드 앱(WebView) 안: display-mode 가 standalone 이 아니라서 따로 알아본다(UA 'MytoolApp/버전') */
  if (/MytoolApp\//.test(navigator.userAgent) || window.MytoolApp) document.documentElement.classList.add('in-app');
  const appLike = () => document.documentElement.classList.contains('in-app') || matchMedia('(display-mode: standalone)').matches;

  /* 새로고침: 앱(설치 PWA·마이툴 앱) 안에서는 주소창이 없어서
   * ① 왼쪽 아래 ⟳ 버튼  ② 새 버전이 올라오면(화면에 돌아올 때·2분마다 페이지를 받아 비교) 위에 "새 버전" 띠 */
  const pageUrl = location.href.split('#')[0];
  let baseText = null, newShown = false;
  // Accept: text/html → 서비스 워커가 저장본 말고 네트워크에서 받음(sw.js networkFirst)
  const getPage = () => fetch(pageUrl, { cache: 'no-store', headers: { Accept: 'text/html' } }).then(r => r.ok ? r.text() : null).catch(() => null);
  const showNew = () => {
    if (newShown) return; newShown = true;
    const b = document.createElement('button');
    b.textContent = '⟳ 새 버전이 있어요 · 눌러서 새로고침';
    b.style.cssText = 'position:fixed;z-index:2147483001;left:50%;transform:translateX(-50%);top:calc(10px + env(safe-area-inset-top,0px));' +
      'padding:9px 16px;border:0;border-radius:999px;background:#1f3b33;color:#fff;font:700 14px/1 system-ui,sans-serif;box-shadow:0 2px 12px rgba(0,0,0,.3)';
    b.onclick = () => location.reload();
    document.body.appendChild(b);
  };
  const checkNew = () => { if (newShown || document.hidden || !appLike()) return;
    getPage().then(t => { if (!t) return; if (baseText == null) baseText = t; else if (t !== baseText) showNew(); }); };
  if (appLike()) {
    setTimeout(checkNew, 1500);                       // 처음 받은 내용을 기준으로
    document.addEventListener('visibilitychange', checkNew);
    setInterval(checkNew, 120000);
  }
  const rcss = document.createElement('style');
  rcss.textContent = `.hub-reload{display:none}
    @media (display-mode: standalone){.hub-reload{display:flex}} html.in-app .hub-reload{display:flex}
    .hub-reload{position:fixed;z-index:2147483000;left:12px;bottom:calc(62px + env(safe-area-inset-bottom,0px));width:34px;height:34px;
      border-radius:50%;border:0;align-items:center;justify-content:center;background:rgba(31,59,51,.62);color:#fff;font:700 17px/1 system-ui,sans-serif;
      box-shadow:0 2px 8px rgba(0,0,0,.2);-webkit-tap-highlight-color:transparent}
    .hub-reload:active{transform:scale(.92)}`;
  document.head.appendChild(rcss);
  const rb = document.createElement('button');
  rb.className = 'hub-reload'; rb.textContent = '⟳'; rb.setAttribute('aria-label', '새로고침');
  rb.onclick = () => location.reload();
  const addR = () => document.body.appendChild(rb);
  document.body ? addR() : document.addEventListener('DOMContentLoaded', addR);

  /* 첫 화면이 아닌 도구 페이지에서만 홈 버튼 */
  const here = location.href.split(/[?#]/)[0];
  const isHub = here === ROOT || here === ROOT + 'index.html';
  if (isHub) { rb.style.bottom = 'calc(12px + env(safe-area-inset-bottom,0px))'; return; }   // 첫 화면엔 홈 버튼이 없으니 ⟳ 를 아래로
  const on = p => `
      ${p}.hub-home{display:flex;position:fixed;z-index:2147483000;left:12px;
        bottom:calc(12px + env(safe-area-inset-bottom,0px));width:42px;height:42px;border-radius:50%;
        align-items:center;justify-content:center;background:rgba(31,59,51,.82);color:#fff;
        box-shadow:0 2px 10px rgba(0,0,0,.25);-webkit-tap-highlight-color:transparent;text-decoration:none}
      ${p}.hub-home:active{transform:scale(.94)}
      ${p}body{padding-bottom:calc(64px + env(safe-area-inset-bottom,0px)) !important}`;
  const css = document.createElement('style');
  css.textContent = `
    .hub-home{display:none}
    @media (display-mode: standalone){${on('')}
    }${on('html.in-app ')}`;
  document.head.appendChild(css);

  const a = document.createElement('a');
  a.className = 'hub-home';
  a.href = ROOT;
  a.setAttribute('aria-label', '도구함으로');
  a.innerHTML = '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><rect x="3" y="3" width="7.5" height="7.5" rx="1.6"/><rect x="13.5" y="3" width="7.5" height="7.5" rx="1.6"/><rect x="3" y="13.5" width="7.5" height="7.5" rx="1.6"/><rect x="13.5" y="13.5" width="7.5" height="7.5" rx="1.6"/></svg>';
  const add = () => document.body.appendChild(a);
  document.body ? add() : document.addEventListener('DOMContentLoaded', add);
})();
