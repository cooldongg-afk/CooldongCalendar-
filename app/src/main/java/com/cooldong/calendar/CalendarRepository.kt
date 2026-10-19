package com.cooldong.calendar

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.provider.CalendarContract
import java.util.Calendar
import java.util.TimeZone

/**
 * Android's system CalendarProvider is the only supported shared interface for calendars
 * shown by Samsung Calendar. Samsung-only reminders and non-exported calendar data cannot
 * be read by third-party apps through this interface.
 */
data class CalendarSource(
    val id: Long,
    val label: String,
    val account: String,
    val visible: Boolean,
    val synchronized: Boolean
)

data class CalendarLoad(
    val events: List<CalendarEvent>,
    val calendars: List<CalendarSource>,
    val selectedCalendarCount: Int,
    val error: String? = null,
    val holidayKeys: Set<Int> = emptySet()
)

object CalendarRepository {
    private const val SETTINGS = "settings"
    private const val SELECTED = "selectedCalendars"

    fun hasPermission(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    fun calendars(ctx: Context): List<CalendarSource> {
        if (!hasPermission(ctx)) return emptyList()
        val result = mutableListOf<CalendarSource>()
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.VISIBLE,
            CalendarContract.Calendars.SYNC_EVENTS
        )
        try {
            ctx.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                projection, null, null,
                CalendarContract.Calendars.CALENDAR_DISPLAY_NAME + " COLLATE NOCASE ASC"
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    result.add(
                        CalendarSource(
                            cursor.getLong(0),
                            cursor.getString(1) ?: "이름 없는 캘린더",
                            cursor.getString(2) ?: "로컬",
                            cursor.getInt(3) != 0,
                            cursor.getInt(4) != 0
                        )
                    )
                }
            }
        } catch (_: Exception) {
            return emptyList()
        }
        return result
    }

    fun selectedIds(
        ctx: Context,
        sources: List<CalendarSource>
    ): Set<Long> {
        val stored = ctx.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE)
            .getStringSet(SELECTED, null)
        if (stored != null) return stored.mapNotNull { it.toLongOrNull() }.toSet()
        // Default to calendars enabled in Android's shared calendar provider.
        // Do not exclude local calendars simply because their sync flag is false.
        val visible = sources.filter { it.visible }.map { it.id }.toSet()
        return if (visible.isNotEmpty()) visible else sources.map { it.id }.toSet()
    }

    fun saveSelectedIds(ctx: Context, ids: Set<Long>) {
        ctx.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(SELECTED, ids.map { it.toString() }.toSet())
            .apply()
    }

    fun resetSelection(ctx: Context) {
        ctx.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE)
            .edit().remove(SELECTED).apply()
    }

    fun load(
        ctx: Context,
        from: Long,
        to: Long
    ): CalendarLoad {
        if (!hasPermission(ctx)) {
            return CalendarLoad(emptyList(), emptyList(), 0, "캘린더 접근 권한이 꺼져 있습니다.")
        }
        val sources = calendars(ctx)
        val ids = selectedIds(ctx, sources)
        if (ids.isEmpty()) {
            return CalendarLoad(emptyList(), sources, 0, "선택된 캘린더가 없습니다.")
        }
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().apply {
            ContentUris.appendId(this, from)
            ContentUris.appendId(this, to)
        }.build()
        val projection = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.DISPLAY_COLOR,
            CalendarContract.Instances.CALENDAR_ID,
            CalendarContract.Instances.EVENT_ID
        )
        val events = ArrayList<CalendarEvent>()
        val holidayDates = HashSet<Int>()
        val labels = sources.associate { it.id to it.label }
        return try {
            ctx.contentResolver.query(
                uri, projection, null, null,
                CalendarContract.Instances.BEGIN + " ASC"
            )?.use { cursor ->
                while (cursor.moveToNext() && events.size < 2500) {
                    val calendarId = cursor.getLong(5)
                    val begin = cursor.getLong(1)
                    val end = cursor.getLong(2).coerceAtLeast(begin + 60000L)
                    val title = cursor.getString(0) ?: "제목 없는 일정"
                    val allDay = cursor.getInt(3) != 0
                    if (allDay && HolidayRules.isHolidayEvent(
                            title, labels[calendarId] ?: ""
                        )
                    ) {
                        val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
                        utc.timeInMillis = begin
                        var count = 0
                        while (utc.timeInMillis < end && count < 15) {
                            holidayDates.add(HolidayRules.dateKey(utc))
                            utc.add(Calendar.DATE, 1)
                            count++
                        }
                    }
                    if (calendarId !in ids) continue
                    val rawColor = if (cursor.isNull(4)) 0 else cursor.getInt(4)
                    val color = if (rawColor == Color.TRANSPARENT) {
                        0xFF3676CC.toInt()
                    } else {
                        Color.rgb(
                            Color.red(rawColor),
                            Color.green(rawColor),
                            Color.blue(rawColor)
                        )
                    }
                    events.add(
                        CalendarEvent(
                            title,
                            begin,
                            end,
                            allDay,
                            color,
                            calendarId,
                            cursor.getLong(6)
                        )
                    )
                }
            }
            CalendarLoad(events, sources, sources.count { it.id in ids }, holidayKeys = holidayDates)
        } catch (e: Exception) {
            CalendarLoad(
                emptyList(), sources, sources.count { it.id in ids },
                "일정 조회 실패: " + (e.localizedMessage ?: e.javaClass.simpleName)
            )
        }
    }

    fun loadPeriod(ctx: Context, firstDay: Calendar, dayCount: Int): CalendarLoad {
        val begin = (firstDay.clone() as Calendar).apply { add(Calendar.DATE, -1) }
        val end = (firstDay.clone() as Calendar).apply { add(Calendar.DATE, dayCount + 1) }
        return load(ctx, begin.timeInMillis, end.timeInMillis)
    }
}
