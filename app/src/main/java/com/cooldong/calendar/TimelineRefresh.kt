package com.cooldong.calendar

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.appwidget.AppWidgetManager

/**
 * A single battery-conscious, non-wakeup alarm keeps all home widgets' red
 * "now" markers reasonably fresh while the phone is active.
 *
 * Alarm delivery is inexact on Android 12+ and can pause in Doze. The manual
 * refresh action and regular 30-minute AppWidget updates remain available.
 */
object TimelineRefresh {
    const val ACTION_CLOCK_TICK = "com.cooldong.calendar.CLOCK_TICK"
    private const val FIVE_MINUTES_MS = 5L * 60L * 1000L

    private fun pending(ctx: Context): PendingIntent {
        return PendingIntent.getBroadcast(
            ctx, 9101,
            Intent(ctx, CalendarWidget::class.java).apply {
                action = ACTION_CLOCK_TICK
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun hasWidgets(ctx: Context): Boolean {
        val manager = AppWidgetManager.getInstance(ctx)
        return listOf(
            CalendarWidget::class.java,
            CompactCalendarWidget::class.java,
            ExpandedCalendarWidget::class.java
        ).any {
            manager.getAppWidgetIds(ComponentName(ctx, it)).isNotEmpty()
        }
    }

    fun schedule(ctx: Context) {
        val alarm = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            ?: return
        if (!hasWidgets(ctx)) {
            alarm.cancel(pending(ctx))
            return
        }
        // ELAPSED_REALTIME (not WAKEUP) avoids waking a sleeping device solely
        // to refresh the widget. When the phone is active, try for ~5 minutes.
        val next = SystemClock.elapsedRealtime() + FIVE_MINUTES_MS
        alarm.set(AlarmManager.ELAPSED_REALTIME, next, pending(ctx))
    }

    fun cancelIfUnused(ctx: Context) {
        if (hasWidgets(ctx)) return
        val alarm = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            ?: return
        alarm.cancel(pending(ctx))
    }
}
