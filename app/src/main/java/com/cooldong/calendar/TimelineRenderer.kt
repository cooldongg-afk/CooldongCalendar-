package com.cooldong.calendar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class CalendarEvent(
    val title: String,
    val begin: Long,
    val end: Long,
    val allDay: Boolean,
    val color: Int
)

object TimelineRenderer {
    private val weekdays = arrayOf("일", "월", "화", "수", "목", "금", "토")
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.KOREA)

    private fun dateKey(c: Calendar): Int =
        c.get(Calendar.YEAR) * 10000 + (c.get(Calendar.MONTH) + 1) * 100 + c.get(Calendar.DAY_OF_MONTH)

    private fun utcKey(time: Long): Int {
        val c = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        c.timeInMillis = time
        return dateKey(c)
    }

    private fun stroke(canvas: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, color: Int, width: Float = 0.6f) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; strokeWidth = width }
        canvas.drawLine(x1, y1, x2, y2, p)
    }

    private fun text(
        canvas: Canvas, value: String, x: Float, y: Float, size: Float,
        color: Int, bold: Boolean = false
    ) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = size
            typeface = if (bold) Typeface.create(Typeface.DEFAULT, Typeface.BOLD) else Typeface.DEFAULT
        }
        canvas.drawText(value, x, y, p)
    }

    private fun rounded(canvas: Canvas, rect: RectF, color: Int, radius: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        canvas.drawRoundRect(rect, radius, radius, p)
    }

    private fun putEventText(
        canvas: Canvas, content: String, area: RectF, fontSize: Float, foreground: Int
    ) {
        val usable = area.width() - 5f
        if (usable < 10f || area.height() < fontSize + 3f) return
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = foreground
            textSize = fontSize
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        }
        val step = fontSize * 1.17f
        val lines = max(1, ((area.height() - 4f) / step).toInt())
        var remaining = content.replace('\n', ' ').trim()
        canvas.save()
        canvas.clipRect(area)
        for (line in 0 until lines) {
            if (remaining.isEmpty()) break
            val count = max(1, p.breakText(remaining, true, usable, null))
            val lineText = remaining.take(count)
            canvas.drawText(lineText, area.left + 2.5f, area.top + 2f + fontSize + line * step, p)
            remaining = remaining.drop(count).trimStart()
        }
        canvas.restore()
    }

    private data class Segment(
        val event: CalendarEvent, val top: Float, val bottom: Float,
        var lane: Int = 0, var laneCount: Int = 1
    )

    private fun arrange(segments: List<Segment>): List<Segment> {
        val sorted = segments.sortedWith(compareBy<Segment> { it.top }.thenByDescending { it.bottom })
        val group = mutableListOf<Segment>()
        var groupBottom = -1f
        fun flush() {
            if (group.isEmpty()) return
            val laneEnds = mutableListOf<Float>()
            for (item in group) {
                val free = laneEnds.indexOfFirst { it <= item.top + 0.01f }
                if (free >= 0) {
                    item.lane = free
                    laneEnds[free] = item.bottom
                } else {
                    item.lane = laneEnds.size
                    laneEnds.add(item.bottom)
                }
            }
            for (item in group) item.laneCount = max(1, laneEnds.size)
            group.clear()
        }
        for (segment in sorted) {
            if (group.isNotEmpty() && segment.top >= groupBottom) {
                flush()
                groupBottom = -1f
            }
            group.add(segment)
            groupBottom = max(groupBottom, segment.bottom)
        }
        flush()
        return sorted
    }

    fun render(
        ctx: Context,
        widthDp: Int, heightDp: Int,
        start: Calendar, days: Int,
        startHour: Int, endHour: Int,
        transparency: Int, showTimes: Boolean,
        events: List<CalendarEvent>
    ): Bitmap {
        val w = widthDp.coerceIn(135, 950).toFloat()
        val h = heightDp.coerceIn(140, 1150).toFloat()
        val rawScale = ctx.resources.displayMetrics.density
        val scale = min(rawScale, min(1000f / w, 1400f / h)).coerceAtLeast(0.65f)
        val bitmap = Bitmap.createBitmap(max(1, (w * scale).roundToInt()), max(1, (h * scale).roundToInt()), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.scale(bitmap.width / w, bitmap.height / h)
        val alpha = (255f * (100 - transparency.coerceIn(0, 100)) / 100f).roundToInt()
        canvas.drawColor(Color.TRANSPARENT)
        rounded(canvas, RectF(0f, 0f, w, h), Color.argb(alpha, 255, 255, 255), 7f)

        val small = h < 225f
        val topH = if (small) 27f else 34f
        val labelH = if (small) 23f else 29f
        val gutter = if (days >= 7 || w < 230f) 31f else 37f
        val contentW = w - gutter - 3f
        val dayW = contentW / days
        val beginDay = start.clone() as Calendar
        val finishDay = start.clone() as Calendar
        finishDay.add(Calendar.DATE, days - 1)
        val rangeText = (beginDay.get(Calendar.MONTH) + 1).toString() + "/" +
            beginDay.get(Calendar.DATE) + " ~ " + (finishDay.get(Calendar.MONTH) + 1) +
            "/" + finishDay.get(Calendar.DATE)
        val headerAlpha = max(65, alpha)
        rounded(canvas, RectF(0f, 0f, w, topH), Color.argb(headerAlpha, 22, 34, 51), 5f)
        text(canvas, rangeText, 9f, topH * 0.68f, if (w < 250f) 12.5f else 16f, Color.WHITE, true)
        val rightText = days.toString() + "일"
        text(canvas, rightText, w - 30f, topH * 0.68f, 10f, 0xFFDAE6F7.toInt())
        stroke(canvas, 0f, topH, w, topH, 0x4491A0B5, 0.5f)

        val dayDates = (0 until days).map { offset ->
            (start.clone() as Calendar).apply { add(Calendar.DATE, offset) }
        }
        val hasAllDay = events.any { it.allDay }
        val allDayH = if (!hasAllDay) 5f else if (small) 22f else 37f
        val gridTop = topH + labelH + allDayH
        val gridBottom = h - 4f
        val gridH = max(20f, gridBottom - gridTop)
        val rangeHours = max(1, endHour - startHour)
        val hourHeight = gridH / rangeHours.toFloat()
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        fill.color = Color.argb(max(0, alpha / 3), 225, 232, 240)

        for (d in 0 until days) {
            val day = dayDates[d]
            val x = gutter + d * dayW
            if (d % 2 == 1 && alpha > 0) canvas.drawRect(x, topH, x + dayW, gridBottom, fill)
            val today = Calendar.getInstance()
            val isToday = dateKey(day) == dateKey(today)
            val name = if (days == 1) {
                (day.get(Calendar.MONTH) + 1).toString() + "/" + day.get(Calendar.DATE) +
                    " (" + weekdays[day.get(Calendar.DAY_OF_WEEK) - 1] + ")"
            } else if (dayW < 48f) {
                weekdays[day.get(Calendar.DAY_OF_WEEK) - 1] + day.get(Calendar.DATE)
            } else {
                (day.get(Calendar.MONTH) + 1).toString() + "/" + day.get(Calendar.DATE) +
                    " " + weekdays[day.get(Calendar.DAY_OF_WEEK) - 1]
            }
            text(canvas, name, x + 3f, topH + labelH * 0.68f,
                min(12f, max(8.3f, dayW / 5.5f)),
                if (isToday) 0xFFD4433B.toInt() else 0xFF25374A.toInt(), isToday)
            stroke(canvas, x, topH, x, gridBottom, 0x668D9BAA, 0.75f)
        }
        stroke(canvas, gutter, topH + labelH, w, topH + labelH, 0x668D9BAA)

        for (d in 0 until days) {
            if (!hasAllDay) break
            val x = gutter + d * dayW
            val date = dateKey(dayDates[d])
            val blocks = events.filter { it.allDay && utcKey(it.begin) <= date && utcKey(it.end - 1L) >= date }
            val rows = if (small) 1 else 2
            for ((i, ev) in blocks.take(rows).withIndex()) {
                val y = topH + labelH + 1f + i * 17f
                val rect = RectF(x + 1f, y, x + dayW - 1f, y + 16f)
                rounded(canvas, rect, ev.color or -0x1000000, 2f)
                putEventText(canvas, ev.title, rect, min(10f, max(7.5f, dayW / 6f)), readableText(ev.color))
            }
            if (blocks.size > rows) {
                text(canvas, "+" + (blocks.size - rows), x + dayW - 15f, gridTop - 2f, 8f, 0xFF394C60.toInt())
            }
        }
        stroke(canvas, 0f, gridTop, w, gridTop, 0x8894A3B8, 0.9f)

        val step = if (hourHeight < 17f) 3 else if (hourHeight < 26f) 2 else 1
        for (hr in startHour..endHour) {
            val y = gridTop + (hr - startHour) * hourHeight
            if ((hr - startHour) % step == 0 || hr == endHour) {
                stroke(canvas, 0f, y, w, y, 0x778EA0B4, if (hr % 6 == 0) 0.8f else 0.5f)
                if (hr < endHour && y + 12f < gridBottom) {
                    text(canvas, "%02d".format(Locale.US, hr), 5f, y + 11f, if (gutter < 35f) 9.5f else 10.5f, 0xFF627489.toInt())
                }
            } else {
                stroke(canvas, gutter, y, w, y, 0x3395A1B1, 0.4f)
            }
        }

        val eventTimeStart = Calendar.getInstance().apply {
            timeInMillis = start.timeInMillis
            set(Calendar.HOUR_OF_DAY, startHour)
            set(Calendar.MINUTE, 0)
        }
        for (d in 0 until days) {
            val day = dayDates[d]
            val windowStart = (day.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, startHour)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            val windowEnd = (day.clone() as Calendar).apply {
                if (endHour == 24) {
                    add(Calendar.DATE, 1)
                    set(Calendar.HOUR_OF_DAY, 0)
                } else {
                    set(Calendar.HOUR_OF_DAY, endHour)
                }
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            val duration = max(1L, windowEnd - windowStart)
            val segments = events.asSequence().filter {
                !it.allDay && it.begin < windowEnd && it.end > windowStart
            }.map {
                val top = gridTop + (max(it.begin, windowStart) - windowStart).toFloat() / duration * gridH
                val bottom = gridTop + (min(it.end, windowEnd) - windowStart).toFloat() / duration * gridH
                Segment(it, top, max(top + 2f, bottom))
            }.toList()

            for (seg in arrange(segments)) {
                val laneW = dayW / seg.laneCount
                val x1 = gutter + d * dayW + laneW * seg.lane + 1f
                val x2 = min(w - 1f, x1 + laneW - 2f)
                val area = RectF(x1, seg.top + 0.5f, max(x1 + 1f, x2), min(gridBottom, seg.bottom - 0.5f))
                if (area.height() <= 1f) continue
                val bg = seg.event.color or -0x1000000
                rounded(canvas, area, bg, 2.2f)
                val label = (if (showTimes) timeFormat.format(Date(seg.event.begin)) + " " else "") +
                    seg.event.title
                val font = min(12f, max(7f, laneW / 5.7f))
                putEventText(canvas, label, area, font, readableText(bg))
            }
        }
        return bitmap
    }

    private fun readableText(color: Int): Int {
        val lum = (Color.red(color) * 0.299 + Color.green(color) * 0.587 + Color.blue(color) * 0.114)
        return if (lum >= 170) 0xFF172437.toInt() else Color.WHITE
    }
}
