package com.cooldong.calendar

import java.util.Calendar

/** Sunday and Korean public holidays share the same visual style. */
object HolidayRules {
    val SUNDAY_RED: Int = 0xFFD74954.toInt()
    val SATURDAY_BLUE: Int = 0xFF2773C5.toInt()

    fun dateKey(day: Calendar): Int =
        day.get(Calendar.YEAR) * 10000 +
            (day.get(Calendar.MONTH) + 1) * 100 + day.get(Calendar.DAY_OF_MONTH)

    fun fixedKoreanHoliday(day: Calendar): Boolean {
        // Lunar dates and substitute holidays are detected through holiday calendar events.
        val month = day.get(Calendar.MONTH) + 1
        val date = day.get(Calendar.DAY_OF_MONTH)
        return (month == 1 && date == 1) ||
            (month == 3 && date == 1) ||
            (month == 5 && date == 5) ||
            (month == 6 && date == 6) ||
            (month == 8 && date == 15) ||
            (month == 10 && (date == 3 || date == 9)) ||
            (month == 12 && date == 25)
    }

    fun isHolidayEvent(title: String, sourceName: String): Boolean {
        val t = title.trim().lowercase()
        val s = sourceName.lowercase()
        val holidayWords = listOf(
            "공휴일", "대체휴일", "대체공휴일", "설날", "설 연휴",
            "추석", "삼일절", "3.1절", "3·1절", "어린이날",
            "광복절", "현충일", "개천절", "한글날", "신정",
            "부처님오신날", "부처님 오신 날", "석가탄신일",
            "크리스마스", "성탄절", "임시공휴일", "선거일",
            "new year's day", "seollal", "chuseok",
            "independence movement day", "liberation day",
            "national foundation day", "hangul day",
            "buddha's birthday", "children's day",
            "memorial day", "christmas day", "substitute holiday"
        )
        if (holidayWords.any { t.contains(it) }) return true
        // Do not color all personal full-day events red just because they exist.
        val isKoreanHolidaySource = listOf(
            "대한민국의 휴일", "대한민국 공휴일", "한국 공휴일",
            "holidays in south korea", "korean holidays",
            "south korea holidays"
        ).any { s.contains(it) }
        return isKoreanHolidaySource && (
            t.contains("holiday") || t.contains("휴일") || t.contains("기념일")
        )
    }
}
