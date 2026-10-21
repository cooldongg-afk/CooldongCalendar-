package com.cooldong.calendar

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentUris
import android.content.Intent
import android.os.Bundle
import android.provider.CalendarContract
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * Samsung Calendar on some One UI versions treats ACTION_EDIT as creation
 * even when the intent includes an existing /events/{id} URI.
 *
 * Existing events therefore use ACTION_VIEW with the exact provider event ID;
 * Samsung's detail page supplies the native edit and delete buttons.
 * ACTION_INSERT is used ONLY if the user explicitly chooses to add an event,
 * or when the selected date has no events at all.
 */
class SlotActionActivity : Activity() {
    companion object {
        const val EXTRA_WIDGET_ID = "widgetId"
        const val EXTRA_PERIOD_START = "periodStartMillis"
        const val EXTRA_DAY_START = "clickedDayMillis"
        const val EXTRA_DAY_INDEX = "dayIndex"
        const val EXTRA_START = "startMillis"
        const val EXTRA_END = "endMillis"
        const val EXTRA_ALL_DAY = "allDay"
        const val EXTRA_EVENT_ID = "sourceEventId"
        const val EXTRA_EVENT_START = "sourceEventStart"
        const val EXTRA_EVENT_END = "sourceEventEnd"
    }

    private data class Tap(
        val widgetId: Int, val periodStart: Long, val dayStart: Long,
        val dayIndex: Int, val slot: Int, val lane: Int,
        val eventId: Long, val start: Long, val end: Long
    ) {
        val allDay: Boolean get() = slot == 999
    }

    private fun readImmutableTap(): Tap? {
        // The URI is the source of truth. PendingIntent extras are not part
        // of Android's PendingIntent identity and can be replaced by reapply.
        val uri = intent.data ?: return null
        if (uri.scheme != "cooldong" || uri.authority != "slot") return null
        val values = uri.pathSegments
        if (values.size != 9) return null
        val nums = values.map { it.toLongOrNull() ?: return null }
        val widgetId = nums[0].toInt()
        val period = nums[1]
        val day = nums[2]
        val index = nums[3].toInt()
        val slot = nums[4].toInt()
        val lane = nums[5].toInt()
        val eventId = nums[6]
        val start = nums[7]
        val end = nums[8]
        if (widgetId < 0 || index !in 0..6 || slot !in 0..999 ||
            lane !in 0..10 || start < 0L || end <= start) return null
        val expected = Calendar.getInstance().apply {
            timeInMillis = period
            add(Calendar.DATE, index)
        }
        val clicked = Calendar.getInstance().apply { timeInMillis = day }
        if (HolidayRules.dateKey(expected) != HolidayRules.dateKey(clicked)) return null
        val startDate = Calendar.getInstance().apply { timeInMillis = start }
        if (HolidayRules.dateKey(startDate) != HolidayRules.dateKey(clicked)) return null
        return Tap(widgetId, period, day, index, slot, lane, eventId, start, end)
    }

    private fun widgetPeriodMatches(tap: Tap): Boolean {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val first = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            when (prefs.getInt("basis", 0)) {
                0 -> add(Calendar.DATE, -((get(Calendar.DAY_OF_WEEK) + 5) % 7))
                2 -> add(Calendar.DATE, -(get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY))
            }
            add(Calendar.DATE, prefs.getInt("offsetDays_" + tap.widgetId, 0))
        }
        return HolidayRules.dateKey(first) == HolidayRules.dateKey(
            Calendar.getInstance().apply { timeInMillis = tap.periodStart }
        )
    }

    private fun staleTapMessage() {
        AlertDialog.Builder(this)
            .setTitle("위젯 날짜가 갱신되었습니다")
            .setMessage(
                "화면의 일정과 터치 날짜 정보가 일치하지 않았습니다. " +
                    "다른 날짜의 일정을 잘못 열지 않도록 동작을 중단하고 위젯을 새로고칩니다."
            )
            .setPositiveButton("새로고침") { _, _ ->
                Thread {
                    CalendarWidget.refreshInstalledWidgets(this)
                    runOnUiThread { finish() }
                }.start()
            }
            .setNegativeButton("닫기") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tap = readImmutableTap()
        if (tap == null || !widgetPeriodMatches(tap)) {
            staleTapMessage()
            return
        }
        val start = tap.start
        val end = tap.end
        val allDay = tap.allDay

        if (!CalendarRepository.hasPermission(this)) {
            AlertDialog.Builder(this)
                .setTitle("캘린더 접근 권한 필요")
                .setMessage("기존 일정을 확인하려면 캘린더 읽기 권한이 필요합니다.")
                .setPositiveButton("설정 열기") { _, _ ->
                    startActivity(Intent(this, SettingsActivity::class.java))
                    finish()
                }
                .setNegativeButton("취소") { _, _ -> finish() }
                .setOnCancelListener { finish() }
                .show()
            return
        }

        if (tap.eventId > 0L) {
            // Verify that the specific event really belongs to the tapped DAY.
            // Do not accidentally open a stale card from the previous week.
            val eventBegin = intent.getLongExtra(EXTRA_EVENT_START, start)
            val eventEnd = intent.getLongExtra(EXTRA_EVENT_END, end)
            val dayEnd = (Calendar.getInstance().apply {
                timeInMillis = tap.dayStart
                add(Calendar.DATE, 1)
            }).timeInMillis
            val belongsToDay = if (allDay) {
                val utc = java.util.TimeZone.getTimeZone("UTC")
                val firstUtc = Calendar.getInstance(utc).apply {
                    timeInMillis = eventBegin
                }
                val lastUtc = Calendar.getInstance(utc).apply {
                    timeInMillis = eventEnd - 1L
                }
                val clickedKey = HolidayRules.dateKey(
                    Calendar.getInstance().apply { timeInMillis = tap.dayStart }
                )
                clickedKey in HolidayRules.dateKey(firstUtc)..HolidayRules.dateKey(lastUtc)
            } else {
                eventBegin < dayEnd && eventEnd > tap.dayStart
            }
            if (!belongsToDay) {
                staleTapMessage()
                return
            }
            showExistingEvent(tap.eventId, eventBegin, eventEnd)
            finish()
            return
        }

        // A hitbox may not exactly match an enlarged painted card because
        // launchers can independently resize and scale AppWidget RemoteViews.
        // Recover by scanning the entire tapped calendar day and OFFER the
        // existing events rather than silently launching "add new event".
        Thread {
            val targetDay = Calendar.getInstance().apply {
                timeInMillis = tap.dayStart
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val targetEnd = (targetDay.clone() as Calendar).apply {
                add(Calendar.DATE, 1)
            }
            val result = CalendarRepository.load(
                this,
                targetDay.timeInMillis - 1000L,
                targetEnd.timeInMillis + 1000L
            )
            val sameDay = result.events.filter { ev ->
                ev.begin < targetEnd.timeInMillis &&
                    ev.end > targetDay.timeInMillis
            }.distinctBy { it.eventId to it.begin }

            val exact = sameDay.filter { ev ->
                if (allDay) ev.allDay && ev.begin < end && ev.end > start
                else !ev.allDay && ev.begin < end && ev.end > start
            }

            // Sort related events first. If this hour lies inside the part of a
            // short card expanded for readability, its source event is still
            // easily reached from the top of the chooser.
            val others = sameDay.filter { it !in exact }.sortedWith(
                compareBy<CalendarEvent>(
                    { if (it.allDay == allDay) 0 else 1 },
                    { abs(it.begin - start) }
                )
            )
            val nearby = exact + others
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                when {
                    result.error != null -> showLookupError(result.error, start, end, allDay)
                    exact.size == 1 -> {
                        val event = exact.first()
                        showExistingEvent(event.eventId, event.begin, event.end)
                        finish()
                    }
                    nearby.isNotEmpty() -> showChoices(start, end, allDay, nearby)
                    else -> {
                        // No calendar events in this entire day: genuinely empty.
                        insertEvent(start, end, allDay)
                        finish()
                    }
                }
            }
        }.start()
    }

    private fun showLookupError(
        message: String,
        start: Long, end: Long, allDay: Boolean
    ) {
        AlertDialog.Builder(this)
            .setTitle("일정을 확인할 수 없습니다")
            .setMessage(
                message + "\n\n기존 일정을 조회하지 못해 새 일정 화면을 자동으로 열지 않았습니다."
            )
            .setPositiveButton("새 일정 추가") { _, _ ->
                insertEvent(start, end, allDay)
                finish()
            }
            .setNegativeButton("취소") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun showChoices(
        start: Long, end: Long, allDay: Boolean,
        existing: List<CalendarEvent>
    ) {
        val format = SimpleDateFormat("M/d (E) HH:mm", Locale.KOREA)
        val title = if (allDay) {
            SimpleDateFormat("M/d (E)", Locale.KOREA).format(Date(start)) + " · 종일 일정"
        } else {
            format.format(Date(start)) + " · 기존 일정 선택"
        }
        val choices = existing.take(40).map { event ->
            val time = if (event.allDay) "종일" else
                SimpleDateFormat("HH:mm", Locale.KOREA).format(Date(event.begin))
            time + "  " + event.title
        }
        val items = choices + "＋ 새 일정 추가"
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(items.toTypedArray()) { _, which ->
                if (which < choices.size) {
                    val event = existing[which]
                    showExistingEvent(event.eventId, event.begin, event.end)
                } else {
                    insertEvent(start, end, allDay)
                }
                finish()
            }
            .setNegativeButton("취소") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun showExistingEvent(eventId: Long, begin: Long, end: Long) {
        if (eventId <= 0L) {
            Toast.makeText(this, "해당 일정의 ID를 확인하지 못했습니다.", Toast.LENGTH_LONG).show()
            return
        }
        val eventUri = ContentUris.withAppendedId(
            CalendarContract.Events.CONTENT_URI, eventId
        )
        val viewIntent = Intent(Intent.ACTION_VIEW, eventUri).apply {
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end)
            putExtra(CalendarContract.EXTRA_EVENT_ID, eventId)
        }
        if (!tryOpenSamsungFirst(viewIntent)) {
            Toast.makeText(
                this, "이 일정을 열 수 있는 캘린더 앱이 없습니다.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun insertEvent(start: Long, end: Long, allDay: Boolean) {
        val intent = Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start)
            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end)
            putExtra(CalendarContract.Events.ALL_DAY, allDay)
        }
        if (!tryOpenSamsungFirst(intent)) {
            Toast.makeText(
                this, "새 일정을 작성할 수 있는 캘린더 앱이 없습니다.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun tryOpenSamsungFirst(intent: Intent): Boolean {
        val samsung = Intent(intent).apply {
            setPackage("com.samsung.android.calendar")
        }
        for (candidate in listOf(samsung, intent)) {
            try {
                if (candidate.resolveActivity(packageManager) != null) {
                    startActivity(candidate)
                    return true
                }
            } catch (_: Exception) {
                // Retry with other calendar apps.
            }
        }
        return false
    }
}
