package com.cooldong.calendar

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import java.util.Calendar

/** Multiple widget sizes share navigation, actions and calendar data. */
open class CalendarWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        updateAll(context, manager, ids)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        manager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        super.onAppWidgetOptionsChanged(context, manager, appWidgetId, newOptions)
        updateAll(context, manager, intArrayOf(appWidgetId))
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        val editor = context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
        appWidgetIds.forEach { editor.remove("offsetDays_" + it) }
        editor.apply()
        super.onDeleted(context, appWidgetIds)
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_PREV, ACTION_NEXT, ACTION_TODAY, ACTION_REFRESH -> {
                val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
                if (id == -1) return
                val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                val stride = daysForWidget(context, AppWidgetManager.getInstance(context), id)
                val key = "offsetDays_" + id
                val current = prefs.getInt(key, 0)
                val next = when (intent.action) {
                    ACTION_PREV -> (current - stride).coerceIn(-3650, 3650)
                    ACTION_NEXT -> (current + stride).coerceIn(-3650, 3650)
                    ACTION_TODAY -> 0
                    else -> current
                }
                prefs.edit().putInt(key, next).apply()
                updateAll(context, AppWidgetManager.getInstance(context), intArrayOf(id))
                return
            }
            Intent.ACTION_DATE_CHANGED, Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED -> refreshInstalledWidgets(context)
        }
        super.onReceive(context, intent)
    }

    companion object {
        const val ACTION_PREV = "com.cooldong.calendar.PREV"
        const val ACTION_NEXT = "com.cooldong.calendar.NEXT"
        const val ACTION_TODAY = "com.cooldong.calendar.TODAY"
        const val ACTION_REFRESH = "com.cooldong.calendar.REFRESH"

        private fun daysForWidget(
            ctx: Context, manager: AppWidgetManager, id: Int
        ): Int {
            val requested = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getInt("days", 0)
            if (requested in listOf(1, 3, 5, 7)) return requested
            val width = manager.getAppWidgetOptions(id).getInt(
                AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 300
            )
            return when {
                width < 230 -> 3
                width < 335 -> 5
                else -> 7
            }
        }

        private val widgetClasses: List<Class<out AppWidgetProvider>> = listOf(
            CalendarWidget::class.java,
            CompactCalendarWidget::class.java,
            ExpandedCalendarWidget::class.java
        )

        fun refreshInstalledWidgets(ctx: Context) {
            val manager = AppWidgetManager.getInstance(ctx)
            val ids = widgetClasses.flatMap {
                manager.getAppWidgetIds(ComponentName(ctx, it)).toList()
            }.distinct().toIntArray()
            updateAll(ctx, manager, ids)
        }

        private fun broadcast(ctx: Context, id: Int, action: String, kind: Int): PendingIntent {
            val intent = Intent(ctx, CalendarWidget::class.java).apply {
                this.action = action
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            }
            return PendingIntent.getBroadcast(
                ctx, id * 10 + kind, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun slotActivity(
            ctx: Context, widgetId: Int, dayIndex: Int, slotIndex: Int,
            startMillis: Long, endMillis: Long, allDay: Boolean
        ): PendingIntent {
            // The data URI distinguishes every tap target to avoid PendingIntent collisions.
            val target = Intent(ctx, SlotActionActivity::class.java).apply {
                data = Uri.parse(
                    "cooldong://slot/" + widgetId + "/" + dayIndex + "/" + slotIndex +
                        "/" + startMillis
                )
                putExtra(SlotActionActivity.EXTRA_START, startMillis)
                putExtra(SlotActionActivity.EXTRA_END, endMillis)
                putExtra(SlotActionActivity.EXTRA_ALL_DAY, allDay)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            return PendingIntent.getActivity(
                ctx, 0, target,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun attachTouchGrid(
            ctx: Context, widget: RemoteViews, widgetId: Int,
            firstDay: Calendar, days: Int,
            firstHour: Int, lastHour: Int, viewHeight: Int
        ) {
            val small = viewHeight.coerceIn(140, 1150) < 225
            val label = if (small) 22f else 29f
            val allDayLane = if (small) 23f else 34f
            val usable = (viewHeight.coerceIn(140, 1150) - label - allDayLane - 4f)
                .coerceAtLeast(20f)
            val hourHeight = usable / (lastHour - firstHour)
            // When the widget is tall enough, half-hour touch targets allow finer entry.
            val slotMinutes = if (hourHeight >= 45f) 30 else 60
            val period = (lastHour - firstHour) * 60
            for (d in 0 until days) {
                val date = (firstDay.clone() as Calendar).apply {
                    add(Calendar.DATE, d)
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                val dayEnd = (date.clone() as Calendar).apply {
                    add(Calendar.DATE, 1)
                }
                val dayView = RemoteViews(
                    ctx.packageName,
                    if (small) R.layout.touch_day_small else R.layout.touch_day_regular
                )
                dayView.setOnClickPendingIntent(
                    R.id.touch_day_title, activity(ctx, widgetId, false)
                )
                dayView.setOnClickPendingIntent(
                    R.id.touch_all_day,
                    slotActivity(
                        ctx, widgetId, d, 999, date.timeInMillis,
                        dayEnd.timeInMillis, true
                    )
                )
                val startTime = (date.clone() as Calendar).apply {
                    set(Calendar.HOUR_OF_DAY, firstHour)
                }
                var offset = 0
                var slot = 0
                while (offset < period) {
                    val from = (startTime.clone() as Calendar).apply {
                        add(Calendar.MINUTE, offset)
                    }
                    val until = (startTime.clone() as Calendar).apply {
                        add(Calendar.MINUTE, (offset + slotMinutes).coerceAtMost(period))
                    }
                    val hitTarget = RemoteViews(ctx.packageName, R.layout.touch_hour)
                    hitTarget.setOnClickPendingIntent(
                        R.id.touch_hour,
                        slotActivity(
                            ctx, widgetId, d, slot,
                            from.timeInMillis, until.timeInMillis, false
                        )
                    )
                    hitTarget.setCharSequence(
                        R.id.touch_hour, "setContentDescription",
                        (date.get(Calendar.MONTH) + 1).toString() + "월 " +
                            date.get(Calendar.DATE) + "일 " +
                            from.get(Calendar.HOUR_OF_DAY) + "시 " +
                            from.get(Calendar.MINUTE) + "분 일정 확인 또는 추가"
                    )
                    dayView.addView(R.id.touch_hour_grid, hitTarget)
                    offset += slotMinutes
                    slot++
                }
                widget.addView(R.id.touch_columns, dayView)
            }
        }

        private fun activity(ctx: Context, id: Int, settings: Boolean): PendingIntent {
            val intent = if (settings) {
                Intent(ctx, SettingsActivity::class.java)
            } else {
                openSamsungCalendar(ctx)
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return PendingIntent.getActivity(
                ctx, id * 10 + if (settings) 8 else 9, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun openSamsungCalendar(ctx: Context): Intent {
            val samsung = ctx.packageManager.getLaunchIntentForPackage("com.samsung.android.calendar")
            if (samsung != null) return samsung
            return Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_APP_CALENDAR)
                setPackage("com.samsung.android.calendar")
            }.takeIf { it.resolveActivity(ctx.packageManager) != null }
                ?: Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_APP_CALENDAR)
                }
        }

        fun updateAll(ctx: Context, manager: AppWidgetManager, ids: IntArray) {
            if (ids.isEmpty()) return
            val prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
            val basis = prefs.getInt("basis", 0)
            val first = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                when (basis) {
                    0 -> add(Calendar.DATE, -((get(Calendar.DAY_OF_WEEK) + 5) % 7))
                    2 -> add(Calendar.DATE, -(get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY))
                }
            }
            val startHour = prefs.getInt("startHour", 7).coerceIn(0, 23)
            val endHour = prefs.getInt("endHour", 22).coerceIn(startHour + 1, 24)
            val alphaPercent = prefs.getInt("transparency", 0).coerceIn(0, 100)
            val theme = WidgetThemes.get(prefs.getInt("themeId", 0))
            val backgroundAlpha = (255 * (100 - alphaPercent) / 100f).toInt()
            val landscape = ctx.resources.configuration.orientation ==
                Configuration.ORIENTATION_LANDSCAPE

            for (id in ids) {
                try {
                    val days = daysForWidget(ctx, manager, id)
                    val day = (first.clone() as Calendar).apply {
                        add(Calendar.DATE, prefs.getInt("offsetDays_" + id, 0))
                    }
                    val finalDay = (day.clone() as Calendar).apply {
                        add(Calendar.DATE, days - 1)
                    }
                    val data = CalendarRepository.loadPeriod(ctx, day, days)
                    val options = manager.getAppWidgetOptions(id)
                    val width = options.getInt(
                        if (landscape) AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH
                        else AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 310
                    ).coerceAtLeast(140)
                    val height = options.getInt(
                        if (landscape) AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT
                        else AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 350
                    ).coerceAtLeast(160)

                    val view = RemoteViews(ctx.packageName, R.layout.widget)
                    view.setInt(
                        R.id.widget_root, "setBackgroundColor",
                        Color.argb(
                            backgroundAlpha,
                            Color.red(theme.background),
                            Color.green(theme.background),
                            Color.blue(theme.background)
                        )
                    )
                    view.setInt(
                        R.id.widget_toolbar, "setBackgroundColor", theme.toolbar
                    )
                    for (target in intArrayOf(
                        R.id.nav_prev, R.id.nav_next, R.id.nav_title,
                        R.id.nav_today, R.id.nav_refresh, R.id.nav_settings
                    )) {
                        view.setTextColor(target, theme.toolbarText)
                    }
                    view.setTextColor(R.id.empty_hint, theme.text)
                    view.setInt(R.id.empty_hint, "setBackgroundColor",
                        Color.argb(
                            237,
                            Color.red(theme.background),
                            Color.green(theme.background),
                            Color.blue(theme.background)
                        )
                    )
                    val head = if (days == 1) {
                        (day.get(Calendar.MONTH) + 1).toString() + "/" + day.get(Calendar.DATE)
                    } else {
                        (day.get(Calendar.MONTH) + 1).toString() + "/" + day.get(Calendar.DATE) +
                            "–" + (finalDay.get(Calendar.MONTH) + 1) + "/" + finalDay.get(Calendar.DATE)
                    }
                    view.setTextViewText(
                        R.id.nav_title, if (data.error != null) "⚠ " + head else head
                    )
                    view.setTextViewText(
                        R.id.nav_today, if (prefs.getInt("offsetDays_" + id, 0) == 0) "오늘" else "↩"
                    )
                    view.setOnClickPendingIntent(
                        R.id.nav_prev, broadcast(ctx, id, ACTION_PREV, 1)
                    )
                    view.setOnClickPendingIntent(
                        R.id.nav_next, broadcast(ctx, id, ACTION_NEXT, 2)
                    )
                    view.setOnClickPendingIntent(
                        R.id.nav_today, broadcast(ctx, id, ACTION_TODAY, 3)
                    )
                    view.setOnClickPendingIntent(
                        R.id.nav_refresh, broadcast(ctx, id, ACTION_REFRESH, 4)
                    )
                    view.setOnClickPendingIntent(R.id.nav_settings, activity(ctx, id, true))
                    view.setOnClickPendingIntent(R.id.nav_title, activity(ctx, id, false))
                    view.setOnClickPendingIntent(R.id.timeline_image, activity(ctx, id, false))
                    val missing = data.error != null || data.selectedCalendarCount == 0
                    view.setViewVisibility(
                        R.id.empty_hint, if (missing) View.VISIBLE else View.GONE
                    )
                    view.setTextViewText(
                        R.id.empty_hint,
                        data.error ?: "이 기간에 표시할 일정이 없습니다.\n⚙ 설정에서 캘린더를 선택하거나 ↻ 를 눌러 새로고침하세요."
                    )
                    view.setOnClickPendingIntent(
                        R.id.empty_hint, activity(ctx, id, true)
                    )
                    view.setViewVisibility(
                        R.id.nav_refresh,
                        if (width < 270) View.GONE else View.VISIBLE
                    )

                    val renderedHeight = (height - 39).coerceAtLeast(130)
                    val bitmap = TimelineRenderer.render(
                        ctx, width, renderedHeight, day, days,
                        startHour, endHour, alphaPercent,
                        prefs.getBoolean("times", true),
                        data.events, data.holidayKeys, theme.id
                    )
                    view.setImageViewBitmap(R.id.timeline_image, bitmap)
                    attachTouchGrid(
                        ctx, view, id, day, days, startHour, endHour, renderedHeight
                    )
                    manager.updateAppWidget(id, view)
                } catch (_: Exception) {
                    val fallback = RemoteViews(ctx.packageName, R.layout.widget)
                    fallback.setTextViewText(R.id.nav_title, "일정 표시 오류 · 설정 확인")
                    fallback.setOnClickPendingIntent(
                        R.id.nav_settings, activity(ctx, id, true)
                    )
                    fallback.setOnClickPendingIntent(
                        R.id.nav_refresh, broadcast(ctx, id, ACTION_REFRESH, 4)
                    )
                    manager.updateAppWidget(id, fallback)
                }
            }
        }
    }
}

class CompactCalendarWidget : CalendarWidget()
class ExpandedCalendarWidget : CalendarWidget()
