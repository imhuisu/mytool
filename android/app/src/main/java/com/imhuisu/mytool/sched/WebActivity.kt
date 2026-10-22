package com.imhuisu.mytool.sched

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

/* 앱 안에서 웹 일정(sched/)을 여는 화면. 크롬으로 넘어가지 않음
 * - 앱에서 연결한 비밀번호의 키를 웹 저장소에 넣어 줘서 웹에서 비밀번호를 또 넣지 않게 함
 * - 웹이 동기화를 마치면 MytoolApp.synced() 를 불러 위젯을 새로고침
 * - 웹의 mytool://settings 링크 → 연결·버전 화면(MainActivity)
 * - <input type=file> (여행 비용 영수증 등): capture 있으면 카메라, 없으면 갤러리. 카메라 사진은 갤러리(Pictures)에도 남음 */
class WebActivity : Activity() {
    private lateinit var web: WebView
    private var fileCb: ValueCallback<Array<Uri>>? = null
    private var camUri: Uri? = null
    private val REQ_FILE = 41

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
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(v: WebView, cb: ValueCallback<Array<Uri>>, p: WebChromeClient.FileChooserParams): Boolean {
                fileCb?.onReceiveValue(null)
                fileCb = cb; camUri = null
                val gallery = Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE)
                val i: Intent = if (p.isCaptureEnabled) {
                    // 카메라: 찍은 사진을 MediaStore 에 저장할 자리를 만들어 넘김 (FileProvider 없이)
                    val uri = runCatching {
                        contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                            put(MediaStore.Images.Media.DISPLAY_NAME, "receipt_" + System.currentTimeMillis() + ".jpg")
                            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                        })
                    }.getOrNull()
                    camUri = uri
                    if (uri != null) Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, uri)
                        .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    else Intent.createChooser(gallery, "영수증 사진")
                } else Intent.createChooser(gallery, "영수증 사진")
                return try { startActivityForResult(i, REQ_FILE); true }
                catch (e: Exception) { fileCb = null; cb.onReceiveValue(null); false }
            }
        }
        web.loadUrl(intent.getStringExtra(EXTRA_URL) ?: Sched.WEB)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req != REQ_FILE) return
        val cb = fileCb ?: return
        fileCb = null
        val cam = camUri; camUri = null
        if (res != RESULT_OK) {
            cam?.let { runCatching { contentResolver.delete(it, null, null) } }   // 취소한 카메라 자리 지우기
            cb.onReceiveValue(null); return
        }
        val picked = data?.data
        cb.onReceiveValue(when {
            picked != null -> arrayOf(picked)
            cam != null -> arrayOf(cam)
            else -> null
        })
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
