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
        const val EXTRA_START = "startMillis"
        const val EXTRA_END = "endMillis"
        const val EXTRA_ALL_DAY = "allDay"
        const val EXTRA_EVENT_ID = "sourceEventId"
        const val EXTRA_EVENT_START = "sourceEventStart"
        const val EXTRA_EVENT_END = "sourceEventEnd"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val start = intent.getLongExtra(EXTRA_START, -1L)
        val end = intent.getLongExtra(EXTRA_END, -1L)
        val allDay = intent.getBooleanExtra(EXTRA_ALL_DAY, false)
        if (start < 0L || end <= start) {
            finish()
            return
        }

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

        val directEventId = intent.getLongExtra(EXTRA_EVENT_ID, -1L)
        if (directEventId > 0L) {
            // A card with a known event ID always goes to that existing event.
            // Never run ACTION_EDIT directly on Samsung Calendar (One UI 8).
            val eventBegin = intent.getLongExtra(EXTRA_EVENT_START, start)
            val eventEnd = intent.getLongExtra(EXTRA_EVENT_END, end)
            showExistingEvent(directEventId, eventBegin, eventEnd)
            finish()
            return
        }

        // A hitbox may not exactly match an enlarged painted card because
        // launchers can independently resize and scale AppWidget RemoteViews.
        // Recover by scanning the entire tapped calendar day and OFFER the
        // existing events rather than silently launching "add new event".
        Thread {
            val targetDay = Calendar.getInstance().apply {
                timeInMillis = start
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
