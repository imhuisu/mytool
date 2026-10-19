package com.imhuisu.mytool.sched

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/* 앱 첫 화면. 연결돼 있으면 바로 일정 화면(WebActivity)으로.
 * 연결 전이거나 웹 설정의 "앱 설정"(mytool://settings)으로 들어오면 연결·상태·업데이트 화면 */
class MainActivity : Activity() {
    private lateinit var box: LinearLayout
    private lateinit var status: TextView
    private lateinit var update: Button

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        if (Sched.isLinked(this) && !intent.getBooleanExtra("settings", false)) {
            startActivity(WebActivity.intent(this)); finish(); return
        }
        val dp = resources.displayMetrics.density
        box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding((20 * dp).toInt(), (28 * dp).toInt(), (20 * dp).toInt(), (20 * dp).toInt()) }
        setContentView(ScrollView(this).apply { fitsSystemWindows = true; addView(box) })   // API 35+ 전체 화면에서 상태바와 겹치지 않게
        build()
    }

    override fun onResume() { super.onResume(); showStatus(); checkUpdate() }

    private fun text(t: String, size: Float = 15f, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = size; if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, 0, 0, (10 * resources.displayMetrics.density).toInt())
    }
    private fun button(t: String, on: () -> Unit) = Button(this).apply { text = t; isAllCaps = false; setOnClickListener { on() } }

    private fun build() {
        box.removeAllViews()
        box.addView(text("일정 위젯", 24f, true))
        status = text("")
        box.addView(status)
        if (!Sched.isLinked(this)) {
            box.addView(text("웹 일정(⚙ 설정)에서 정한 동기화 비밀번호를 넣어 주세요. 이 폰 안에만 저장돼요."))
            val pw = EditText(this).apply { hint = "동기화 비밀번호"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
            box.addView(pw, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            box.addView(button("연결") {
                val p = pw.text.toString(); if (p.length < 8) { status.text = "8자 이상이에요"; return@button }
                status.text = "연결 중… (몇 초 걸려요)"
                Thread {
                    Sched.link(this, p)
                    val ok = Sched.fetch(this, requireData = true)
                    val err = Sched.lastError(this)
                    runOnUiThread {
                        if (!ok) Sched.unlink(this)
                        build(); if (!ok) status.text = "연결 실패: " + err.ifEmpty { "비밀번호와 인터넷을 확인해 주세요" }
                        SchedWidget.refresh(this)
                        if (ok) { startActivity(WebActivity.intent(this)); finish() }
                    }
                }.start()
            })
        } else {
            box.addView(button("지금 새로고침") { status.text = "새로고침 중…"; Thread { Sched.fetch(this); runOnUiThread { showStatus(); SchedWidget.render(this); MonthWidget.render(this) } }.start() })
            box.addView(button("일정 열기 (추가·수정)") { startActivity(WebActivity.intent(this)); finish() })
            box.addView(button("연결 끊기") {
                // 실수로 누르지 않게 확인
                android.app.AlertDialog.Builder(this)
                    .setTitle("연결을 끊을까요?")
                    .setMessage("이 폰의 위젯이 일정을 더 받지 않아요. 일정 자체는 서버에 그대로 있고, 같은 비밀번호로 다시 연결하면 돼요.")
                    .setPositiveButton("끊기") { _, _ -> Sched.unlink(this); build(); SchedWidget.render(this); MonthWidget.render(this) }
                    .setNegativeButton("취소", null)
                    .show()
            })
        }
        // 플레이스토어(내부 테스트)로 설치한 앱이라 업데이트는 플레이스토어에서
        update = button("") { runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName"))) } }.apply { visibility = android.view.View.GONE }
        box.addView(update)
        box.addView(text("\n홈 화면에 붙이기: 홈 화면 빈 곳을 길게 누르기 → 위젯 → '일정 달력'(월 달력), '일정 목록'(오늘·할 일), '할 일 목록'(할 일만) 중 → 끌어다 놓기.\n크기는 놓은 뒤 위젯을 길게 눌러 테두리를 끌면 바꿀 수 있어요.\n위젯은 30분마다, 그리고 ⟳ 를 누르면 새로 받아요. 위젯을 누르면 일정 화면이 열려요.", 13f))
        box.addView(text("버전 ${packageManager.getPackageInfo(packageName, 0).versionName}", 12f).apply { gravity = Gravity.END })
        showStatus()
    }

    private fun showStatus() {
        if (!::status.isInitialized) return
        status.text = if (!Sched.isLinked(this)) "아직 연결 안 됨" else {
            val at = Sched.lastSync(this)
            val err = Sched.lastError(this)
            (if (at > 0) "마지막 동기화 " + SimpleDateFormat("M/d HH:mm", Locale.KOREA).format(Date(at)) else "아직 받은 적 없음") +
                (if (err.isNotEmpty()) "\n$err" else "")
        }
    }

    private fun checkUpdate() {
        Thread {
            val v = Sched.latestVersion() ?: return@Thread
            val mine = packageManager.getPackageInfo(packageName, 0).longVersionCode
            if (v.first > mine) runOnUiThread { update.text = "새 버전이 있어요 → 플레이스토어에서 업데이트"; update.visibility = android.view.View.VISIBLE }
        }.start()
    }
}
