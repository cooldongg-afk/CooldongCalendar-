package com.cooldong.calendar

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentUris
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.CalendarContract
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The widget itself does not modify or delete calendar data.
 * A slot tap opens Samsung's calendar editor or an existing event's detail screen,
 * preserving account selection, reminders, recurrence, sync and native delete confirmation.
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
        val directEventId = intent.getLongExtra(EXTRA_EVENT_ID, -1L)
        if (directEventId > 0L) {
            // The widget has already resolved the painted card to its exact
            // CalendarContract.Events ID. No second time-range query is needed.
            val eventBegin = intent.getLongExtra(EXTRA_EVENT_START, start)
            val eventEnd = intent.getLongExtra(EXTRA_EVENT_END, end)
            showExistingEvent(
                CalendarEvent(
                    "", eventBegin, eventEnd, allDay, 0,
                    eventId = directEventId
                )
            )
            finish()
            return
        }
        if (!CalendarRepository.hasPermission(this)) {
            AlertDialog.Builder(this)
                .setTitle("캘린더 접근 권한 필요")
                .setMessage("기존 일정을 확인하려면 Cooldong Calendar에 캘린더 접근을 허용해 주세요.")
                .setPositiveButton("설정 열기") { _, _ ->
                    startActivity(Intent(this, SettingsActivity::class.java))
                    finish()
                }
                .setNegativeButton("취소") { _, _ -> finish() }
                .setOnCancelListener { finish() }
                .show()
            return
        }

        Thread {
            val result = CalendarRepository.load(
                this,
                start - 24L * 60L * 60L * 1000L,
                end + 24L * 60L * 60L * 1000L
            )
            val overlaps = result.events.filter { event ->
                if (allDay) {
                    event.allDay && event.begin < end && event.end > start
                } else {
                    !event.allDay && event.begin < end && event.end > start
                }
            }.distinctBy { it.eventId to it.begin }
            runOnUiThread {
                if (!isFinishing) showChoices(start, end, allDay, overlaps)
            }
        }.start()
    }

    private fun showChoices(
        start: Long,
        end: Long,
        allDay: Boolean,
        overlaps: List<CalendarEvent>
    ) {
        if (overlaps.isEmpty()) {
            insertEvent(start, end, allDay)
            finish()
            return
        }

        val formatter = SimpleDateFormat("M/d (E) HH:mm", Locale.KOREA)
        val title = if (allDay) {
            SimpleDateFormat("M/d (E)", Locale.KOREA).format(Date(start)) + " · 종일"
        } else {
            formatter.format(Date(start)) + " · 일정 선택"
        }
        val choices = ArrayList<String>()
        choices.add("＋ 이 시간에 새 일정 추가")
        for (event in overlaps) {
            val time = if (event.allDay) "종일" else
                SimpleDateFormat("HH:mm", Locale.KOREA).format(Date(event.begin))
            choices.add(time + "  " + event.title + "  ›")
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(choices.toTypedArray()) { _, which ->
                if (which == 0) {
                    insertEvent(start, end, allDay)
                } else {
                    showExistingEvent(overlaps[which - 1])
                }
                finish()
            }
            .setNegativeButton("취소") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun insertEvent(start: Long, end: Long, allDay: Boolean) {
        val intent = Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start)
            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end)
            putExtra(CalendarContract.Events.ALL_DAY, allDay)
        }
        openSamsungFirst(intent)
    }

    private fun showExistingEvent(event: CalendarEvent) {
        if (event.eventId <= 0L) {
            Toast.makeText(this, "일정 ID를 확인할 수 없습니다", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = ContentUris.withAppendedId(
            CalendarContract.Events.CONTENT_URI, event.eventId
        )
        val editIntent = Intent(Intent.ACTION_EDIT, uri).apply {
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, event.begin)
            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, event.end)
        }
        // Prefer opening the native editor. Some Samsung Calendar versions only
        // expose a public VIEW activity; in that case open the detail screen
        // where the edit and delete actions are available.
        if (!tryOpenSamsungFirst(editIntent)) {
            val viewIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, event.begin)
                putExtra(CalendarContract.EXTRA_EVENT_END_TIME, event.end)
            }
            if (!tryOpenSamsungFirst(viewIntent)) {
                Toast.makeText(
                    this, "삼성 캘린더에서 일정을 열 수 없습니다",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun openSamsungFirst(intent: Intent) {
        if (!tryOpenSamsungFirst(intent)) {
            Toast.makeText(
                this, "캘린더 앱에서 이 동작을 지원하지 않습니다",
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
                // Continue with the standard Android calendar app fallback.
            }
        }
        return false
    }
}
