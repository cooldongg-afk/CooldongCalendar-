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
    /**
     * Multiline labels preserve their time/title separator.
     * Empty neighbouring hours are used by TimelineLayout to grow sparse cards.
     */
    private fun putEventText(
        canvas: Canvas, content: String, area: RectF, fontSize: Float, foreground: Int
    ) {
        val maxWidth = area.width() - 7f
        val maxHeight = area.height() - 5f
        if (maxWidth < 7.5f || maxHeight < 8f) return
        val size = min(fontSize, maxHeight * .78f)
        if (size < 7f) return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = foreground
            textSize = size
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isSubpixelText = true
        }
        val lineStep = size * 1.14f
        val totalRows = max(1, min(7, (maxHeight / lineStep).toInt()))
        val paragraphs = ArrayDeque(
            content.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        )
        canvas.save()
        canvas.clipRect(area.left + 1f, area.top + 1f, area.right - 1f, area.bottom - 1f)
        var row = 0
        while (paragraphs.isNotEmpty() && row < totalRows) {
            val next = paragraphs.removeFirst()
            if (next.isEmpty()) continue
            val count = paint.breakText(next, true, maxWidth, null)
            if (count <= 0) break
            val remaining = next.drop(count).trimStart()
            val isLastLine = row + 1 == totalRows
            val text = if (isLastLine && (
                    remaining.isNotEmpty() || paragraphs.isNotEmpty()
                )
            ) {
                val ellipsisWidth = paint.measureText("…")
                val allowed = max(4f, maxWidth - ellipsisWidth)
                val fitted = paint.breakText(next, true, allowed, null).coerceAtLeast(1)
                next.take(fitted) + "…"
            } else {
                next.take(count)
            }
            canvas.drawText(
                text, area.left + 3.5f,
                area.top + 2.5f + size + row * lineStep, paint
            )
            if (!isLastLine && remaining.isNotEmpty()) paragraphs.addFirst(remaining)
            row++
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
            // e.g. "10/19 월" even in the seven-day view; auto-fit narrow cells.
            val name = (day.get(Calendar.MONTH) + 1).toString() + "/" +
                day.get(Calendar.DATE) + " " +
                weekdays[day.get(Calendar.DAY_OF_WEEK) - 1]
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            var dayFont = min(13f, max(9.5f, dayW / 4.0f))
            p.textSize = dayFont
            while (p.measureText(name) > dayW - 4f && dayFont > 8.0f) {
                dayFont -= 0.5f
                p.textSize = dayFont
            }
            text(canvas, name, x + 2f, topH + labelH * 0.68f,
                dayFont,
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

        val cards = TimelineLayout.cards(
            widthDp, heightDp, start, days, startHour, endHour, events
        )
        for (card in cards) {
            val rect = card.rect
            if (rect.height() < 1.5f) continue
            val bg = theme.cardFor(card.event.calendarId, card.event.color)
            rounded(canvas, rect, bg, 3.2f)
            // A compact accent tick shows where the real event duration ends
            // when the label card extends into unused time below it.
            if (card.realEndY < rect.bottom - 4f) {
                stroke(
                    canvas, rect.left + 2f, card.realEndY,
                    min(rect.right - 2f, rect.left + 12f),
                    card.realEndY, theme.inkFor(bg), 1f
                )
            }
            val label = if (showTimes) {
                timeFormat.format(Date(card.event.begin)) + "\n" + card.event.title
            } else {
                card.event.title
            }
            val width = rect.width()
            val font = when {
                width >= 90f -> 15.4f
                width >= 65f -> 13.7f
                width >= 52f -> 12.3f
                width >= 39f -> 11.1f
                width >= 29f -> 10.2f
                else -> 9.3f
            }
            putEventText(canvas, label, rect, font, theme.inkFor(bg))
        }
        return bitmap
    }

    private fun readableText(color: Int): Int {
        val lum = (Color.red(color) * 0.299 + Color.green(color) * 0.587 + Color.blue(color) * 0.114)
        return if (lum >= 170) 0xFF172437.toInt() else Color.WHITE
    }
}
