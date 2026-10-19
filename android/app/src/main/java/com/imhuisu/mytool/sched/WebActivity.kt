package com.imhuisu.mytool.sched

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

/* 앱 안에서 웹 일정(sched/)을 여는 화면. 크롬으로 넘어가지 않음
 * - 앱에서 연결한 비밀번호의 키를 웹 저장소에 넣어 줘서 웹에서 비밀번호를 또 넣지 않게 함
 * - 웹이 동기화를 마치면 MytoolApp.synced() 를 불러 위젯을 새로고침
 * - 웹의 mytool://settings 링크 → 연결·버전 화면(MainActivity) */
class WebActivity : Activity() {
    private lateinit var web: WebView

    companion object {
        const val EXTRA_URL = "url"
        fun intent(c: android.content.Context, url: String = Sched.WEB) = Intent(c, WebActivity::class.java)
            .putExtra(EXTRA_URL, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }

    inner class Bridge {
        @JavascriptInterface fun synced() { SchedWidget.refresh(applicationContext) }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        web = WebView(this)
        web.fitsSystemWindows = true
        setContentView(web)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        // 웹이 앱 버전을 표시할 수 있게 UA 에 버전을 붙임 (예: MytoolApp/1.6)
        val ver = packageManager.getPackageInfo(packageName, 0).versionName
        web.settings.userAgentString = web.settings.userAgentString + " MytoolApp/" + ver
        web.addJavascriptInterface(Bridge(), "MytoolApp")
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                val u = r.url
                if (u.scheme == "mytool") { startActivity(Intent(this@WebActivity, MainActivity::class.java).putExtra("settings", true)); return true }
                if (u.host != Uri.parse(Sched.BASE).host) { runCatching { startActivity(Intent(Intent.ACTION_VIEW, u)) }; return true }
                return false
            }
            override fun onPageFinished(v: WebView, url: String) { injectKey() }
        }
        web.loadUrl(intent.getStringExtra(EXTRA_URL) ?: Sched.WEB)
    }

    override fun onNewIntent(i: Intent) {
        super.onNewIntent(i)
        i.getStringExtra(EXTRA_URL)?.let { web.loadUrl(it) }
    }

    /** 웹 저장소에 동기화 키가 없거나 다르면 넣고 새로고침 (한 번만) */
    private fun injectKey() {
        val k = Sched.syncKey(this) ?: return
        val js = """(function(){try{var d=JSON.parse(localStorage.getItem('sched-dev-v1')||'{}');
            if(d.sync&&d.sync.id==='${k.first}')return 'ok';
            d.sync={id:'${k.first}',jwk:{kty:'oct',k:'${k.second}',alg:'A256GCM',ext:true,key_ops:['encrypt','decrypt']},ver:0,at:0};
            localStorage.setItem('sched-dev-v1',JSON.stringify(d));location.reload();return 'set'}catch(e){return 'err'}})()"""
        web.evaluateJavascript(js, null)
    }

    override fun onPause() {
        super.onPause()
        // 웹에서 고친 게 서버에 올라갈 시간을 조금 주고 위젯 새로고침
        web.postDelayed({ SchedWidget.refresh(applicationContext) }, 2500)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }
}
