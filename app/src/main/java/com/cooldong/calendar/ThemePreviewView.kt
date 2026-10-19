package com.cooldong.calendar

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import kotlin.math.min

/** Live, touch-free preview of the selected widget palette. */
class ThemePreviewView(context: Context) : View(context) {
    var themeId: Int = 0
        set(value) {
            field = value
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private fun fill(c: Canvas, x: Float, y: Float, width: Float, height: Float,
                     color: Int, radius: Float = 0f) {
        paint.color = color
        paint.style = Paint.Style.FILL
        c.drawRoundRect(RectF(x, y, x + width, y + height), radius, radius, paint)
    }

    private fun label(c: Canvas, text: String, x: Float, y: Float, sp: Float,
                      color: Int, bold: Boolean = false) {
        paint.color = color
        paint.textSize = sp
        paint.style = Paint.Style.FILL
        paint.typeface = Typeface.create(
            Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL
        )
        c.drawText(text, x, y, paint)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val theme = WidgetThemes.get(themeId)
        val w = width.toFloat().coerceAtLeast(1f)
        val h = height.toFloat().coerceAtLeast(1f)
        val left = 4f
        val top = 4f
        val right = w - 4f
        val bottom = h - 4f
        val cardW = (right - left).coerceAtLeast(1f)
        val cardH = (bottom - top).coerceAtLeast(1f)
        val density = resources.displayMetrics.density
        val scale = min(1.4f, cardW / (312f * density))
            .coerceAtLeast(0.7f)
        canvas.save()
        canvas.translate(left, top)
        // Preview uses dp-like logical coordinates to match real widget typography.
        canvas.scale(scale, scale)
        val rw = cardW / scale
        val rh = cardH / scale
        fill(canvas, 0f, 0f, rw, rh, theme.background, 13f)
        val toolbarHeight = 36f
        fill(canvas, 0f, 0f, rw, toolbarHeight, theme.toolbar, 9f)
        label(canvas, "‹", 12f, 24f, 23f, theme.toolbarText)
        label(canvas, "10/19 – 10/25", 50f, 24f, 14f, theme.toolbarText, true)
        label(canvas, "›", rw - 27f, 24f, 23f, theme.toolbarText)

        val y0 = toolbarHeight + 6f
        val gapX = 28f
        val colWidth = (rw - gapX - 5f) / 5f
        val names = listOf("월 19", "화 20", "수 21", "목 22", "금 23")
        val holiday = 4
        for (day in 0 until 5) {
            val x = gapX + colWidth * day
            if (day % 2 == 1) fill(canvas, x, toolbarHeight, colWidth, rh - toolbarHeight,
                theme.alternating)
            label(canvas, names[day], x + 3f, y0 + 12f, 10.5f, theme.text)
            paint.color = theme.grid
            paint.strokeWidth = 0.6f
            canvas.drawLine(x, y0 + 18f, x, rh - 3f, paint)
        }
        val gridTop = y0 + 24f
        for (row in 0..4) {
            val y = gridTop + row * (rh - gridTop - 5f) / 4f
            paint.color = theme.grid
            paint.strokeWidth = 0.6f
            canvas.drawLine(1f, y, rw, y, paint)
            if (row < 4) label(canvas, (8 + row * 2).toString(), 5f, y + 10f,
                10f, theme.secondaryText)
        }
        val blocks = listOf(
            Triple(0, 0.45f, "회의"),
            Triple(1, 1.65f, "점심"),
            Triple(3, 0.9f, "운동"),
            Triple(4, 2.30f, "약속")
        )
        val gapY = (rh - gridTop - 6f) / 4f
        for ((index, spec) in blocks.withIndex()) {
            val x = gapX + spec.first * colWidth + 2f
            val y = gridTop + spec.second * gapY
            val color = theme.cardColors[index % theme.cardColors.size]
            fill(canvas, x, y, colWidth - 5f, gapY * 0.82f, color, 3f)
            label(canvas, spec.third, x + 3f, y + 13f, 11f, theme.inkFor(color), true)
        }
        paint.color = Color.argb(70, 100, 109, 122)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 0.8f
        canvas.drawRoundRect(RectF(0.5f, 0.5f, rw - .5f, rh - .5f), 13f, 13f, paint)
        paint.style = Paint.Style.FILL
        canvas.restore()
    }
}
