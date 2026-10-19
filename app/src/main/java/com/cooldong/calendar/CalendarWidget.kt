package com.cooldong.calendar

import android.Manifest
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.CalendarContract
import android.widget.RemoteViews
import java.util.Calendar

class CalendarWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        updateAll(context, manager, ids)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context, manager: AppWidgetManager,
        appWidgetId: Int, newOptions: Bundle
    ) {
        super.onAppWidgetOptionsChanged(context, manager, appWidgetId, newOptions)
        updateAll(context, manager, intArrayOf(appWidgetId))
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == Intent.ACTION_DATE_CHANGED ||
            intent.action == Intent.ACTION_TIME_CHANGED ||
            intent.action == Intent.ACTION_TIMEZONE_CHANGED
        ) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, CalendarWidget::class.java))
            updateAll(context, manager, ids)
        }
    }

    companion object {
        private const val SAMSUNG_CALENDAR = "com.samsung.android.calendar"

        fun openCalendarIntent(ctx: Context): Intent {
            val samsung = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_APP_CALENDAR)
                setPackage(SAMSUNG_CALENDAR)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (samsung.resolveActivity(ctx.packageManager) != null) return samsung
            return Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_APP_CALENDAR)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }

        fun updateAll(ctx: Context, manager: AppWidgetManager, ids: IntArray) {
            if (ids.isEmpty()) return
            val prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
            val days = prefs.getInt("days", 7).coerceIn(1, 7)
            val startHour = prefs.getInt("startHour", 7).coerceIn(0, 23)
            val endHour = prefs.getInt("endHour", 22).coerceIn(startHour + 1, 24)
            val transparency = prefs.getInt("transparency", 0)
            val showTimes = prefs.getBoolean("times", true)
            val start = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (prefs.getInt("basis", 0) == 0) {
                    val mondayOffset = (get(Calendar.DAY_OF_WEEK) + 5) % 7
                    add(Calendar.DAY_OF_MONTH, -mondayOffset)
                }
            }
            val rangeEnd = (start.clone() as Calendar).apply { add(Calendar.DATE, days + 2) }
            val rangeBegin = (start.clone() as Calendar).apply { add(Calendar.DATE, -2) }
            val events = readEvents(ctx, rangeBegin.timeInMillis, rangeEnd.timeInMillis)
            for (id in ids) {
                val options = manager.getAppWidgetOptions(id)
                val landscape = ctx.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                val width = options.getInt(
                    if (landscape) AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH
                    else AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 320
                ).coerceAtLeast(135)
                val height = options.getInt(
                    if (landscape) AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT
                    else AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 350
                ).coerceAtLeast(140)
                val bitmap = TimelineRenderer.render(
                    ctx, width, height, start, days, startHour, endHour,
                    transparency, showTimes, events
                )
                val views = RemoteViews(ctx.packageName, R.layout.widget)
                views.setImageViewBitmap(R.id.timeline_image, bitmap)
                val launch = openCalendarIntent(ctx)
                val click = PendingIntent.getActivity(
                    ctx, id, launch,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                views.setOnClickPendingIntent(R.id.timeline_image, click)
                manager.updateAppWidget(id, views)
                bitmap.recycle()
            }
        }

        private fun readEvents(ctx: Context, from: Long, to: Long): List<CalendarEvent> {
            if (ctx.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
                return emptyList()
            }
            val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().apply {
                ContentUris.appendId(this, from)
                ContentUris.appendId(this, to)
            }.build()
            val columns = arrayOf(
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END,
                CalendarContract.Instances.ALL_DAY,
                CalendarContract.Instances.DISPLAY_COLOR
            )
            val items = ArrayList<CalendarEvent>()
            try {
                ctx.contentResolver.query(
                    uri, columns, null, null,
                    CalendarContract.Instances.BEGIN + " ASC"
                )?.use { cursor ->
                    while (cursor.moveToNext() && items.size < 900) {
                        val title = cursor.getString(0) ?: "일정"
                        val begin = cursor.getLong(1)
                        val end = cursor.getLong(2).coerceAtLeast(begin + 60000L)
                        val allDay = cursor.getInt(3) == 1
                        val rawColor = if (cursor.isNull(4)) 0 else cursor.getInt(4)
                        val color = if (rawColor == Color.TRANSPARENT) 0xFF2874C8.toInt()
                            else Color.rgb(Color.red(rawColor), Color.green(rawColor), Color.blue(rawColor))
                        items.add(CalendarEvent(title, begin, end, allDay, color))
                    }
                }
            } catch (_: Exception) {
                // The widget remains usable if the calendar provider is temporarily unavailable.
            }
            return items
        }
    }
}
