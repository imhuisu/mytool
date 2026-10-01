/* 모든 페이지 공통: 오프라인 캐시 등록 + (앱으로 설치했을 때) 홈 버튼
 * 도구 페이지 <head>에 한 줄만 넣으면 됨:  <script src="../hub.js" defer></script>
 */
(function () {
  const self = document.currentScript || document.querySelector('script[src$="hub.js"]');
  const ROOT = new URL('./', self.src).href;          // 도구함 첫 화면 주소
  window.HUB_ROOT = ROOT;

  if ('serviceWorker' in navigator) {
    navigator.serviceWorker.register(new URL('sw.js', ROOT).href, { scope: ROOT }).catch(() => {});
  }

  /* 첫 화면이 아닌 도구 페이지에서만 홈 버튼 */
  const here = location.href.split(/[?#]/)[0];
  const isHub = here === ROOT || here === ROOT + 'index.html';
  if (isHub) return;

  const css = document.createElement('style');
  css.textContent = `
    .hub-home{display:none}
    @media (display-mode: standalone){
      .hub-home{display:flex;position:fixed;z-index:2147483000;left:12px;
        bottom:calc(12px + env(safe-area-inset-bottom,0px));width:42px;height:42px;border-radius:50%;
        align-items:center;justify-content:center;background:rgba(31,59,51,.82);color:#fff;
        box-shadow:0 2px 10px rgba(0,0,0,.25);-webkit-tap-highlight-color:transparent;text-decoration:none}
      .hub-home:active{transform:scale(.94)}
      body{padding-bottom:calc(64px + env(safe-area-inset-bottom,0px)) !important}
    }`;
  document.head.appendChild(css);

  const a = document.createElement('a');
  a.className = 'hub-home';
  a.href = ROOT;
  a.setAttribute('aria-label', '도구함으로');
  a.innerHTML = '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><rect x="3" y="3" width="7.5" height="7.5" rx="1.6"/><rect x="13.5" y="3" width="7.5" height="7.5" rx="1.6"/><rect x="3" y="13.5" width="7.5" height="7.5" rx="1.6"/><rect x="13.5" y="13.5" width="7.5" height="7.5" rx="1.6"/></svg>';
  const add = () => document.body.appendChild(a);
  document.body ? add() : document.addEventListener('DOMContentLoaded', add);
})();
