package com.cooldong.calendar

import android.graphics.Color

/**
 * The original timeline is preserved as the default.
 * Six alternative palettes reuse the exact same layout and spacing.
 */
data class WidgetTheme(
    val id: Int,
    val name: String,
    val description: String,
    val background: Int,
    val toolbar: Int,
    val toolbarText: Int,
    val text: Int,
    val secondaryText: Int,
    val grid: Int,
    val alternating: Int,
    val cardColors: IntArray,
    val useCalendarColors: Boolean = false
) {
    fun cardFor(calendarId: Long, sourceColor: Int): Int {
        if (useCalendarColors) return sourceColor
        val index = ((calendarId xor (calendarId ushr 32)).toInt() and Int.MAX_VALUE) %
            cardColors.size
        return cardColors[index]
    }
    fun inkFor(color: Int): Int {
        val luminance = Color.red(color) * .299 +
            Color.green(color) * .587 + Color.blue(color) * .114
        return if (luminance > 165) Color.rgb(27, 41, 62) else Color.WHITE
    }
}

object WidgetThemes {
    private fun rgb(hex: String): Int = Color.parseColor(hex)

    val all: List<WidgetTheme> = listOf(
        WidgetTheme(
            0, "기본 · 클래식", "기존 디자인 그대로",
            rgb("#FFFFFF"), rgb("#192435"), Color.WHITE,
            rgb("#25374A"), rgb("#627489"), rgb("#8EA0B4"),
            rgb("#EEF2F7"),
            intArrayOf(rgb("#28B9D3"), rgb("#9488F0"), rgb("#EEC954")),
            true
        ),
        WidgetTheme(
            1, "토스 블루", "깨끗한 흰색 · 세련된 블루",
            rgb("#FFFFFF"), rgb("#F1F5FC"), rgb("#222F43"),
            rgb("#26374C"), rgb("#7B8BA0"), rgb("#D9E2ED"),
            rgb("#F7F9FD"),
            intArrayOf(rgb("#3378E8"), rgb("#568EE6"), rgb("#7387D8"), rgb("#407CB7"), rgb("#5274A8"))
        ),
        WidgetTheme(
            2, "딥 네이비", "차분한 남색 · 은은한 대비",
            rgb("#182332"), rgb("#101926"), rgb("#F5F8FF"),
            rgb("#EAF1FA"), rgb("#AEBBCD"), rgb("#3D5066"),
            rgb("#202D3D"),
            intArrayOf(rgb("#356FA4"), rgb("#486AAB"), rgb("#388A90"), rgb("#8968A4"), rgb("#467F98"))
        ),
        WidgetTheme(
            3, "민트 그린", "맑은 아이보리 · 딥그린",
            rgb("#F8FCFA"), rgb("#E8F5EF"), rgb("#21483C"),
            rgb("#234A3C"), rgb("#718E80"), rgb("#D2E6DD"),
            rgb("#F0F7F3"),
            intArrayOf(rgb("#28977F"), rgb("#408F90"), rgb("#5F9E7A"), rgb("#498C69"), rgb("#428EAC"))
        ),
        WidgetTheme(
            4, "라벤더", "부드러운 보랏빛 · 화이트",
            rgb("#FDFCFF"), rgb("#F2EEFB"), rgb("#403354"),
            rgb("#473B57"), rgb("#81778D"), rgb("#E0D9ED"),
            rgb("#F8F5FD"),
            intArrayOf(rgb("#8B76CA"), rgb("#A073BA"), rgb("#6F85C3"), rgb("#9D70AE"), rgb("#7284B5"))
        ),
        WidgetTheme(
            5, "웜 샌드", "따뜻한 크림 · 코코아",
            rgb("#FFFDF9"), rgb("#F6EFE4"), rgb("#5D4536"),
            rgb("#55473C"), rgb("#927E6E"), rgb("#E7DCCF"),
            rgb("#FBF7F0"),
            intArrayOf(rgb("#BC8357"), rgb("#B49A5C"), rgb("#A87768"), rgb("#8D9776"), rgb("#C18C6B"))
        ),
        WidgetTheme(
            6, "슬레이트 모노", "무채색 · 또렷한 타이포",
            rgb("#F9FAFC"), rgb("#343F50"), Color.WHITE,
            rgb("#263344"), rgb("#738093"), rgb("#D9E0E9"),
            rgb("#F0F3F7"),
            intArrayOf(rgb("#506781"), rgb("#637791"), rgb("#78859B"), rgb("#475F78"), rgb("#677C91"))
        )
    )

    fun get(id: Int): WidgetTheme = all.getOrElse(id) { all.first() }
}
