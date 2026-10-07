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
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/* 월 달력 위젯. 칸마다 날짜 + 일정 제목 2개까지, ‹ › 로 달 이동, 날짜 누르면 웹 달력 그 날짜 */
class MonthWidget : AppWidgetProvider() {

    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) = SchedWidget.refresh(c, goAsync())

    override fun onReceive(c: Context, i: Intent) {
        super.onReceive(c, i)
        val p = c.getSharedPreferences("month", Context.MODE_PRIVATE)
        when (i.action) {
            ACTION_PREV -> { p.edit().putInt("off", p.getInt("off", 0) - 1).apply(); render(c) }
            ACTION_NEXT -> { p.edit().putInt("off", p.getInt("off", 0) + 1).apply(); render(c) }
            ACTION_TODAY -> { p.edit().putInt("off", 0).apply(); render(c) }
        }
    }

    companion object {
        const val ACTION_PREV = "com.imhuisu.mytool.sched.MONTH_PREV"
        const val ACTION_NEXT = "com.imhuisu.mytool.sched.MONTH_NEXT"
        const val ACTION_TODAY = "com.imhuisu.mytool.sched.MONTH_TODAY"

        private fun bcast(c: Context, action: String, code: Int) = PendingIntent.getBroadcast(c, code,
            Intent(c, MonthWidget::class.java).setAction(action), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        fun render(c: Context) {
            val m = AppWidgetManager.getInstance(c)
            val ids = m.getAppWidgetIds(ComponentName(c, MonthWidget::class.java))
            if (ids.isEmpty()) return
            val off = c.getSharedPreferences("month", Context.MODE_PRIVATE).getInt("off", 0)
            val today = LocalDate.now()
            val ym = YearMonth.from(today).plusMonths(off.toLong())
            val doc = Sched.cachedDoc(c)
            val linked = Sched.isLinked(c)
            val cats = intArrayOf(R.color.c0, R.color.c1, R.color.c2, R.color.c3)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

            // 일요일 시작, 5주 또는 6주
            val first = ym.atDay(1)
            val start = first.minusDays((first.dayOfWeek.value % 7).toLong())
            val weeks = if (start.plusDays(35).isAfter(ym.atEndOfMonth())) 5 else 6

            for (id in ids) {
                val v = RemoteViews(c.packageName, R.layout.month_widget)
                v.setTextViewText(R.id.title, "${ym.year}년 ${ym.monthValue}월")
                v.setOnClickPendingIntent(R.id.prev, bcast(c, ACTION_PREV, 11))
                v.setOnClickPendingIntent(R.id.next, bcast(c, ACTION_NEXT, 12))
                v.setOnClickPendingIntent(R.id.title, bcast(c, ACTION_TODAY, 13))
                v.setOnClickPendingIntent(R.id.add, PendingIntent.getActivity(c, 14,
                    if (linked) Intent(Intent.ACTION_VIEW, Uri.parse(Sched.WEB)) else Intent(c, MainActivity::class.java), flags))
                v.removeAllViews(R.id.weeks)
                for (w in 0 until weeks) {
                    val row = RemoteViews(c.packageName, R.layout.month_week)
                    for (d in 0 until 7) {
                        val day = start.plusDays((w * 7 + d).toLong())
                        val cell = RemoteViews(c.packageName, R.layout.month_cell)
                        val inMonth = day.monthValue == ym.monthValue
                        cell.setTextViewText(R.id.num, day.dayOfMonth.toString())
                        val numColor = when { day == today -> android.R.color.white; !inMonth -> R.color.faint
                            day.dayOfWeek == DayOfWeek.SUNDAY -> R.color.c2; day.dayOfWeek == DayOfWeek.SATURDAY -> R.color.c0; else -> R.color.ink }
                        cell.setTextColor(R.id.num, c.getColor(numColor))
                        cell.setInt(R.id.num, "setBackgroundResource", if (day == today) R.drawable.today_bg else android.R.color.transparent)
                        // 그날 일정 + 마감 할 일
                        val evs = if (linked) Sched.eventsOn(doc, day) else emptyList()
                        val tks = if (linked) Sched.openTasks(doc, today).filter { it.optString("due") == day.toString() } else emptyList()
                        val labels = evs.map { it.optString("t") to cats[it.optInt("c", 0).coerceIn(0, 3)] } + tks.map { "☐" + it.optString("t") to R.color.muted }
                        val slots = intArrayOf(R.id.e1, R.id.e2)
                        for (k in slots.indices) {
                            val l = labels.getOrNull(k)
                            if (l == null) cell.setViewVisibility(slots[k], View.GONE) else {
                                cell.setViewVisibility(slots[k], View.VISIBLE)
                                cell.setTextViewText(slots[k], l.first)
                                cell.setTextColor(slots[k], c.getColor(l.second))
                            }
                        }
                        if (labels.size > 2) { cell.setViewVisibility(R.id.more, View.VISIBLE); cell.setTextViewText(R.id.more, "+${labels.size - 2}") }
                        else cell.setViewVisibility(R.id.more, View.GONE)
                        val code = day.year * 10000 + day.monthValue * 100 + day.dayOfMonth
                        cell.setOnClickPendingIntent(R.id.cell, PendingIntent.getActivity(c, code,
                            Intent(Intent.ACTION_VIEW, Uri.parse(Sched.WEB + "?day=" + day)), flags))
                        row.addView(R.id.week, cell)
                    }
                    v.addView(R.id.weeks, row)
                }
                m.updateAppWidget(id, v)
            }
        }
    }
}
