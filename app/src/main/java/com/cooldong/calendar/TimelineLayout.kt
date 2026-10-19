package com.cooldong.calendar

import android.graphics.RectF
import java.util.Calendar
import kotlin.math.max
import kotlin.math.min

/**
 * Single source of truth for rendered event cards and their hit-test coordinates.
 *
 * A card starts at the true event start and may extend down into unused vertical
 * space when an otherwise short appointment would render an unreadable label.
 * The original end time remains recorded separately for accurate editing.
 * The same geometry is used to draw and to route widget taps.
 */
data class EventCard(
    val dayIndex: Int,
    val event: CalendarEvent,
    val rect: RectF,
    val realEndY: Float,
    val lane: Int,
    val laneCount: Int
)

data class TimelineMetrics(
    val width: Float,
    val height: Float,
    val days: Int,
    val firstHour: Int,
    val lastHour: Int,
    val labelHeight: Float,
    val allDayHeight: Float,
    val gutter: Float = 31f
) {
    val gridTop: Float = labelHeight + allDayHeight
    val gridBottom: Float = height - 4f
    val gridHeight: Float = max(20f, gridBottom - gridTop)
    val dayWidth: Float = (width - gutter - 3f) / days
    val hourHeight: Float = gridHeight / max(1, lastHour - firstHour)
}

object TimelineLayout {
    fun metrics(
        widthDp: Int, heightDp: Int, days: Int,
        firstHour: Int, lastHour: Int
    ): TimelineMetrics {
        val width = widthDp.coerceIn(135, 950).toFloat()
        val height = heightDp.coerceIn(140, 1150).toFloat()
        val small = height < 225f
        return TimelineMetrics(
            width, height, days, firstHour, lastHour,
            if (small) 22f else 29f,
            if (small) 23f else 34f
        )
    }

    private data class MutableSegment(
        val event: CalendarEvent,
        val top: Float,
        val bottom: Float,
        var lane: Int = 0,
        var laneCount: Int = 1
    )

    private fun lanes(items: List<MutableSegment>): List<MutableSegment> {
        val sorted = items.sortedWith(
            compareBy<MutableSegment> { it.top }.thenByDescending { it.bottom }
        )
        val group = mutableListOf<MutableSegment>()
        var bottom = -1f
        fun finalizeGroup() {
            if (group.isEmpty()) return
            val occupiedUntil = mutableListOf<Float>()
            group.forEach { item ->
                val free = occupiedUntil.indexOfFirst { it <= item.top + 0.001f }
                if (free < 0) {
                    item.lane = occupiedUntil.size
                    occupiedUntil.add(item.bottom)
                } else {
                    item.lane = free
                    occupiedUntil[free] = item.bottom
                }
            }
            for (item in group) item.laneCount = max(1, occupiedUntil.size)
            group.clear()
        }
        for (item in sorted) {
            if (group.isNotEmpty() && item.top >= bottom) {
                finalizeGroup()
                bottom = -1f
            }
            group.add(item)
            bottom = max(bottom, item.bottom)
        }
        finalizeGroup()
        return sorted
    }

    fun cards(
        widthDp: Int, heightDp: Int,
        firstDay: Calendar, days: Int,
        firstHour: Int, lastHour: Int,
        events: List<CalendarEvent>
    ): List<EventCard> {
        val metrics = metrics(widthDp, heightDp, days, firstHour, lastHour)
        val result = ArrayList<EventCard>()
        for (d in 0 until days) {
            val date = (firstDay.clone() as Calendar).apply { add(Calendar.DATE, d) }
            val start = (date.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, firstHour)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            val end = (date.clone() as Calendar).apply {
                if (lastHour == 24) {
                    add(Calendar.DATE, 1)
                    set(Calendar.HOUR_OF_DAY, 0)
                } else {
                    set(Calendar.HOUR_OF_DAY, lastHour)
                }
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            val duration = max(1L, end - start).toFloat()
            val items = events.asSequence()
                .filter { !it.allDay && it.begin < end && it.end > start }
                .map { event ->
                    val y1 = metrics.gridTop +
                        (max(event.begin, start) - start).toFloat() / duration *
                        metrics.gridHeight
                    val y2 = metrics.gridTop +
                        (min(event.end, end) - start).toFloat() / duration *
                        metrics.gridHeight
                    MutableSegment(event, y1, max(y1 + 2f, y2))
                }.toList()
            if (items.isEmpty()) continue

            val arranged = lanes(items)
            val minCardHeight = when {
                arranged.size <= 2 -> 69f
                arranged.size <= 4 -> 57f
                arranged.size <= 7 -> 46f
                else -> 35f
            }
            val byLane = arranged.groupBy { it.lane }
            for (item in arranged) {
                val nextStart = byLane[item.lane].orEmpty()
                    .filter { it.top > item.top + 0.01f }
                    .minOfOrNull { it.top } ?: metrics.gridBottom
                val allowedEnd = min(metrics.gridBottom, nextStart - 1.3f)
                val expandedEnd = min(
                    allowedEnd,
                    max(item.bottom, item.top + minCardHeight)
                )
                val laneWidth = metrics.dayWidth / item.laneCount
                val left = metrics.gutter + d * metrics.dayWidth +
                    item.lane * laneWidth + 1f
                val right = max(left + 1.1f, min(metrics.width - 1f,
                    left + laneWidth - 2f))
                val top = item.top + 0.5f
                val bottom = max(top + 1.5f, expandedEnd - 0.5f)
                result.add(
                    EventCard(
                        d, item.event,
                        RectF(left, top, right, bottom),
                        item.bottom,
                        item.lane, item.laneCount
                    )
                )
            }
        }
        return result
    }
}
