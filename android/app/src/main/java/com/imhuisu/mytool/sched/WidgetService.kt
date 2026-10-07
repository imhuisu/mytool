package com.imhuisu.mytool.sched

import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService

/* 위젯 목록 줄 만들기 (저장된 문서로 계산, 네트워크 없음) */
class WidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = Factory(applicationContext)

    class Factory(private val c: Context) : RemoteViewsFactory {
        private var rows: List<Sched.Row> = emptyList()
        override fun onCreate() {}
        override fun onDataSetChanged() { rows = if (Sched.isLinked(c)) Sched.rows(Sched.cachedDoc(c)) else emptyList() }
        override fun onDestroy() {}
        override fun getCount() = rows.size
        override fun getViewTypeCount() = 4
        override fun getItemId(p: Int) = p.toLong()
        override fun hasStableIds() = false
        override fun getLoadingView(): RemoteViews? = null

        private fun col(id: Int) = c.getColor(id)
        private val cats = intArrayOf(R.color.c0, R.color.c1, R.color.c2, R.color.c3)

        override fun getViewAt(p: Int): RemoteViews {
            val r = rows.getOrNull(p) ?: return RemoteViews(c.packageName, R.layout.row_empty)
            val v: RemoteViews = when (r) {
                is Sched.Row.Head -> RemoteViews(c.packageName, R.layout.row_head).apply { setTextViewText(R.id.t, r.text) }
                is Sched.Row.Empty -> RemoteViews(c.packageName, R.layout.row_empty).apply { setTextViewText(R.id.t, r.text) }
                is Sched.Row.Ev -> RemoteViews(c.packageName, R.layout.row_ev).apply {
                    setTextViewText(R.id.tm, r.time)
                    setTextViewText(R.id.t, r.title)
                    setInt(R.id.bar, "setBackgroundColor", col(cats[r.cat.coerceIn(0, 3)]))
                    setTextColor(R.id.t, col(if (r.state == 2) R.color.muted else R.color.ink))
                    setInt(R.id.rowbg, "setBackgroundResource", if (r.state == 1) R.drawable.now_bg else android.R.color.transparent)
                }
                is Sched.Row.Tk -> RemoteViews(c.packageName, R.layout.row_tk).apply {
                    setTextViewText(R.id.t, (if (r.star) "★ " else "") + r.title)
                    if (r.due.isEmpty()) setViewVisibility(R.id.due, View.GONE) else {
                        setViewVisibility(R.id.due, View.VISIBLE)
                        setTextViewText(R.id.due, r.due)
                        setTextColor(R.id.due, col(when (r.dueKind) { 2 -> R.color.c2; 1 -> R.color.c0; else -> R.color.muted }))
                    }
                }
            }
            v.setOnClickFillInIntent(R.id.rowroot, Intent())
            return v
        }
    }
}
