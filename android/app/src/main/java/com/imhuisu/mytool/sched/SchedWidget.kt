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
                try { if (Sched.isLinked(app)) Sched.fetch(app); render(app) }
                finally { pending?.finish() }
            }.start()
        }

        fun render(c: Context, loading: Boolean = false) {
            val m = AppWidgetManager.getInstance(c)
            val ids = m.getAppWidgetIds(ComponentName(c, SchedWidget::class.java))
            if (ids.isEmpty()) return
            val doc = Sched.cachedDoc(c)
            val today = LocalDate.now()
            val nEv = Sched.eventsOn(doc, today).size
            val nTk = Sched.openTasks(doc, today).size
            val sub = when {
                !Sched.isLinked(c) -> "눌러서 비밀번호 연결"
                loading -> "새로고침 중…"
                Sched.lastError(c).isNotEmpty() -> Sched.lastError(c)
                else -> "일정 $nEv · 할 일 $nTk"
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val openWeb = PendingIntent.getActivity(c, 1, Intent(Intent.ACTION_VIEW, Uri.parse(Sched.WEB)), flags)
            val openApp = PendingIntent.getActivity(c, 2, Intent(c, MainActivity::class.java), flags)
            val refresh = PendingIntent.getBroadcast(c, 3, Intent(c, SchedWidget::class.java).setAction(ACTION_REFRESH), flags)
            // 목록 줄을 누르면 웹 일정 화면 (fill-in 을 받으려면 MUTABLE)
            val template = PendingIntent.getActivity(c, 4, Intent(Intent.ACTION_VIEW, Uri.parse(Sched.WEB)),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)

            for (id in ids) {
                val v = RemoteViews(c.packageName, R.layout.widget)
                v.setTextViewText(R.id.date, Sched.dayLabel(today))
                v.setTextViewText(R.id.sub, sub)
                v.setOnClickPendingIntent(R.id.header, if (Sched.isLinked(c)) openWeb else openApp)
                v.setOnClickPendingIntent(R.id.refresh, refresh)
                v.setOnClickPendingIntent(R.id.add, openWeb)
                val svc = Intent(c, WidgetService::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
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
