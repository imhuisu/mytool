package com.imhuisu.mytool.sched

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent

/* 할 일 목록 위젯: 남은 할 일만 (중요·기한 지난 것 먼저). 그리기는 SchedWidget.render 가 같이 함 */
class TaskWidget : AppWidgetProvider() {
    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) = SchedWidget.refresh(c, goAsync())

    override fun onReceive(c: Context, i: Intent) {
        super.onReceive(c, i)
        if (i.action == SchedWidget.ACTION_REFRESH) SchedWidget.refresh(c, goAsync())
    }
}
