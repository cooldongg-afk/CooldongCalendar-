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
    val color: Int,
    val calendarId: Long = -1L,
    val eventId: Long = -1L
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

    /**
     * Readability-first text. Prefer a larger typeface for sparse appointments and
     * clip/ellipsize only when an event card is genuinely too narrow or short.
     */
    private fun putEventText(
        canvas: Canvas, content: String, area: RectF, fontSize: Float, foreground: Int
    ) {
        val maxWidth = area.width() - 7f
        val maxHeight = area.height() - 4f
        if (maxWidth < 9f || maxHeight < 8f) return

        // A short event still gets one readable line; labels can span two or more
        // rows only when the actual time slot is tall enough.
        val size = min(fontSize, maxHeight * 0.80f).coerceAtLeast(7f)
        if (size > maxHeight + 0.2f) return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = foreground
            textSize = size
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isSubpixelText = true
        }
        val step = size * 1.18f
        val lineCount = max(1, min(5, (maxHeight / step).toInt()))
        var remaining = content.replace('\n', ' ').trim()
        canvas.save()
        canvas.clipRect(area.left + 1f, area.top + 1f, area.right - 1f, area.bottom - 1f)
        for (row in 0 until lineCount) {
            if (remaining.isEmpty()) break
            val fit = paint.breakText(remaining, true, maxWidth, null)
            if (fit <= 0) break
            val hasMore = fit < remaining.length
            val last = row == lineCount - 1
            val used = if (hasMore && last) {
                val ellipsis = "…"
                val amount = paint.breakText(
                    remaining, true,
                    (maxWidth - paint.measureText(ellipsis)).coerceAtLeast(3f),
                    null
                ).coerceAtLeast(1)
                remaining.take(amount) + ellipsis
            } else {
                remaining.take(fit)
            }
            canvas.drawText(
                used, area.left + 3.5f,
                area.top + 2f + size + step * row, paint
            )
            if (last) break
            remaining = remaining.drop(fit).trimStart()
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
        events: List<CalendarEvent>,
        holidayKeys: Set<Int> = emptySet(),
        themeId: Int = 0
    ): Bitmap {
        val theme = WidgetThemes.get(themeId)
        val w = widthDp.coerceIn(135, 950).toFloat()
        val h = heightDp.coerceIn(140, 1150).toFloat()
        val rawScale = ctx.resources.displayMetrics.density
        val scale = min(rawScale, min(1000f / w, 1400f / h)).coerceAtLeast(0.65f)
        val bitmap = Bitmap.createBitmap(max(1, (w * scale).roundToInt()), max(1, (h * scale).roundToInt()), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.scale(bitmap.width / w, bitmap.height / h)
        val alpha = (255f * (100 - transparency.coerceIn(0, 100)) / 100f).roundToInt()
        canvas.drawColor(Color.TRANSPARENT)
        rounded(
            canvas, RectF(0f, 0f, w, h),
            Color.argb(
                alpha,
                Color.red(theme.background),
                Color.green(theme.background),
                Color.blue(theme.background)
            ),
            7f
        )

        val small = h < 225f
        // The native widget toolbar contains the date and navigation controls.
        // Drawing another header into the bitmap would waste valuable row space.
        val topH = 0f
        val labelH = if (small) 22f else 29f
        val gutter = 31f
        val contentW = w - gutter - 3f
        val dayW = contentW / days

        val dayDates = (0 until days).map { offset ->
            (start.clone() as Calendar).apply { add(Calendar.DATE, offset) }
        }
        val firstDate = dateKey(dayDates.first())
        val lastDate = dateKey(dayDates.last())
        val hasAllDay = events.any {
            it.allDay && utcKey(it.begin) <= lastDate && utcKey(it.end - 1L) >= firstDate
        }
        // Keep a tappable all-day lane even when no all-day event exists.
        val allDayH = if (small) 23f else 34f
        val gridTop = topH + labelH + allDayH
        val gridBottom = h - 4f
        val gridH = max(20f, gridBottom - gridTop)
        val rangeHours = max(1, endHour - startHour)
        val hourHeight = gridH / rangeHours.toFloat()
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        fill.color = theme.alternating

        for (d in 0 until days) {
            val day = dayDates[d]
            val x = gutter + d * dayW
            val publicHoliday = HolidayRules.fixedKoreanHoliday(day) ||
                holidayKeys.contains(HolidayRules.dateKey(day))
            val sunday = day.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY
            val saturday = day.get(Calendar.DAY_OF_WEEK) == Calendar.SATURDAY
            if (sunday || publicHoliday) {
                fill.color = Color.argb((alpha * 0.19f).toInt(), 245, 110, 123)
                canvas.drawRect(x, topH, x + dayW, gridBottom, fill)
            } else if (saturday) {
                fill.color = Color.argb((alpha * 0.16f).toInt(), 85, 152, 236)
                canvas.drawRect(x, topH, x + dayW, gridBottom, fill)
            } else if (d % 2 == 1 && alpha > 0) {
                fill.color = theme.alternating
                canvas.drawRect(x, topH, x + dayW, gridBottom, fill)
            }
            val today = Calendar.getInstance()
            val isToday = dateKey(day) == dateKey(today)
            val name = if (days == 1) {
                (day.get(Calendar.MONTH) + 1).toString() + "/" + day.get(Calendar.DATE) +
                    " (" + weekdays[day.get(Calendar.DAY_OF_WEEK) - 1] + ")"
            } else if (dayW < 67f) {
                weekdays[day.get(Calendar.DAY_OF_WEEK) - 1] + day.get(Calendar.DATE)
            } else {
                (day.get(Calendar.MONTH) + 1).toString() + "/" + day.get(Calendar.DATE) +
                    " " + weekdays[day.get(Calendar.DAY_OF_WEEK) - 1]
            }
            text(canvas, name, x + 3f, topH + labelH * 0.68f,
                min(13.3f, max(10.0f, dayW / 5.6f)),
                when {
                    sunday || publicHoliday -> HolidayRules.SUNDAY_RED
                    saturday -> HolidayRules.SATURDAY_BLUE
                    else -> theme.text
                }, isToday || sunday || saturday || publicHoliday)
            stroke(canvas, x, topH, x, gridBottom, theme.grid, 0.65f)
        }
        stroke(canvas, gutter, topH + labelH, w, topH + labelH, theme.grid)
        text(canvas, "종일", 3f, topH + labelH + 15f, 10.5f, theme.secondaryText)

        for (d in 0 until days) {
            if (!hasAllDay) break
            val x = gutter + d * dayW
            val date = dateKey(dayDates[d])
            val blocks = events.filter { it.allDay && utcKey(it.begin) <= date && utcKey(it.end - 1L) >= date }
            val rows = if (small) 1 else 2
            for ((i, ev) in blocks.take(rows).withIndex()) {
                val y = topH + labelH + 1f + i * 17f
                val rect = RectF(x + 1f, y, x + dayW - 1f, y + 16f)
                val cardColor = theme.cardFor(ev.calendarId, ev.color)
                rounded(canvas, rect, cardColor or -0x1000000, 3f)
                putEventText(
                    canvas, ev.title, rect,
                    min(11.7f, max(9.4f, dayW / 5.7f)),
                    theme.inkFor(cardColor)
                )
            }
            if (blocks.size > rows) {
                text(canvas, "+" + (blocks.size - rows), x + dayW - 15f, gridTop - 2f, 9f, theme.secondaryText)
            }
        }
        stroke(canvas, 0f, gridTop, w, gridTop, theme.grid, 0.9f)

        val step = if (hourHeight < 17f) 3 else if (hourHeight < 26f) 2 else 1
        for (hr in startHour..endHour) {
            val y = gridTop + (hr - startHour) * hourHeight
            if ((hr - startHour) % step == 0 || hr == endHour) {
                stroke(canvas, 0f, y, w, y, theme.grid, if (hr % 6 == 0) 0.8f else 0.5f)
                if (hr < endHour && y + 12f < gridBottom) {
                    text(canvas, "%02d".format(Locale.US, hr), 5f, y + 11.5f, 10.4f, theme.secondaryText)
                }
            } else {
                stroke(canvas, gutter, y, w, y, theme.grid, 0.35f)
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

            val arranged = arrange(segments)
            for (seg in arranged) {
                val laneW = dayW / seg.laneCount
                val x1 = gutter + d * dayW + laneW * seg.lane + 1f
                val x2 = min(w - 1f, x1 + laneW - 2f)
                val area = RectF(x1, seg.top + 0.5f, max(x1 + 1f, x2), min(gridBottom, seg.bottom - 0.5f))
                if (area.height() <= 1f) continue
                val bg = theme.cardFor(seg.event.calendarId, seg.event.color)
                rounded(canvas, area, bg, 3.1f)
                val label = (if (showTimes) timeFormat.format(Date(seg.event.begin)) + " " else "") +
                    seg.event.title
                // Prefer Toss-sized legible labels when neighbouring events leave
                // enough space. Overlaps gracefully return to smaller text.
                val nearby = arranged.count { other ->
                    other !== seg &&
                        other.top < seg.bottom + hourHeight * 0.5f &&
                        other.bottom > seg.top - hourHeight * 0.5f
                }
                val generous = nearby == 0 && area.height() >= 22f
                val baseFont = when {
                    laneW >= 90f -> 14.5f
                    laneW >= 65f -> 13.4f
                    laneW >= 51f -> 12.3f
                    laneW >= 39f -> 11.5f
                    laneW >= 27f -> 10.3f
                    else -> 9.1f
                }
                val font = min(
                    15f,
                    baseFont + if (generous) 0.6f else 0f
                )
                putEventText(canvas, label, area, font, theme.inkFor(bg))
            }
        }
        return bitmap
    }

    private fun readableText(color: Int): Int {
        val lum = (Color.red(color) * 0.299 + Color.green(color) * 0.587 + Color.blue(color) * 0.114)
        return if (lum >= 170) 0xFF172437.toInt() else Color.WHITE
    }
}
