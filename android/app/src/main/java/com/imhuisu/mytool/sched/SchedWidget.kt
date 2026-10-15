package com.imhuisu.mytool.sched

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import java.time.LocalDate

/* 홈 화면 위젯. 30분마다(updatePeriodMillis) + 새로고침 버튼 + 앱에서 연결할 때 갱신 */
class SchedWidget : AppWidgetProvider() {

    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) {
        refresh(c, goAsync())
    }

    override fun onReceive(c: Context, i: Intent) {
        super.onReceive(c, i)
        when (i.action) {
            ACTION_REFRESH, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> refresh(c, goAsync())
        }
    }

    companion object {
        const val ACTION_REFRESH = "com.imhuisu.mytool.sched.REFRESH"

        /** 서버에서 받고 → 화면 갱신 (네트워크라 별도 스레드) */
        fun refresh(c: Context, pending: PendingResult? = null) {
            val app = c.applicationContext
            render(app, loading = true)
            Thread {
                try { if (Sched.isLinked(app)) Sched.fetch(app); render(app); MonthWidget.render(app) }
                finally { pending?.finish() }
            }.start()
        }

        /** 목록형 위젯 두 가지(일정 목록 / 할 일 목록)를 다시 그림 */
        fun render(c: Context, loading: Boolean = false) {
            renderList(c, SchedWidget::class.java, tasksOnly = false, loading = loading)
            renderList(c, TaskWidget::class.java, tasksOnly = true, loading = loading)
        }

        private fun renderList(c: Context, cls: Class<*>, tasksOnly: Boolean, loading: Boolean) {
            val m = AppWidgetManager.getInstance(c)
            val ids = m.getAppWidgetIds(ComponentName(c, cls))
            if (ids.isEmpty()) return
            val doc = Sched.cachedDoc(c)
            val today = LocalDate.now()
            val nEv = Sched.eventsOn(doc, today).size
            val tasks = Sched.openTasks(doc, today)
            val nTk = tasks.size
            val nOver = tasks.count { val d = it.optString("due"); d.isNotEmpty() && d < today.toString() }
            val sub = when {
                !Sched.isLinked(c) -> "눌러서 비밀번호 연결"
                loading -> "새로고침 중…"
                Sched.lastError(c).isNotEmpty() -> Sched.lastError(c)
                tasksOnly -> "남은 할 일 $nTk" + (if (nOver > 0) " · 기한 지남 $nOver" else "")
                else -> "일정 $nEv · 할 일 $nTk"
            }
            val base = if (tasksOnly) 20 else 0     // PendingIntent 요청 코드가 두 위젯끼리 겹치지 않게
            val web = if (tasksOnly) Sched.WEB + "?view=todo" else Sched.WEB
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val openWeb = PendingIntent.getActivity(c, base + 1, Intent(Intent.ACTION_VIEW, Uri.parse(web)), flags)
            val openApp = PendingIntent.getActivity(c, base + 2, Intent(c, MainActivity::class.java), flags)
            val refresh = PendingIntent.getBroadcast(c, base + 3, Intent(c, cls).setAction(ACTION_REFRESH), flags)
            // 목록 줄을 누르면 웹 일정 화면 (fill-in 을 받으려면 MUTABLE)
            val template = PendingIntent.getActivity(c, base + 4, Intent(Intent.ACTION_VIEW, Uri.parse(web)),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)

            for (id in ids) {
                val v = RemoteViews(c.packageName, R.layout.widget)
                v.setTextViewText(R.id.date, if (tasksOnly) "할 일" else Sched.dayLabel(today))
                v.setTextViewText(R.id.sub, sub)
                v.setOnClickPendingIntent(R.id.header, if (Sched.isLinked(c)) openWeb else openApp)
                v.setOnClickPendingIntent(R.id.refresh, refresh)
                v.setOnClickPendingIntent(R.id.add, openWeb)
                val svc = Intent(c, WidgetService::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                    .putExtra(WidgetService.EXTRA_TASKS, tasksOnly)
                svc.data = Uri.parse(svc.toUri(Intent.URI_INTENT_SCHEME))
                v.setRemoteAdapter(R.id.list, svc)
                v.setEmptyView(R.id.list, R.id.empty)
                v.setPendingIntentTemplate(R.id.list, template)
                v.setViewVisibility(R.id.empty, if (Sched.isLinked(c)) View.GONE else View.VISIBLE)
                if (!Sched.isLinked(c)) v.setOnClickPendingIntent(R.id.empty, openApp)
                m.updateAppWidget(id, v)
            }
            m.notifyAppWidgetViewDataChanged(ids, R.id.list)
        }
    }
}
