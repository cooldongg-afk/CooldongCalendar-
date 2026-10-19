package com.cooldong.calendar

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.util.Calendar

class SettingsActivity : Activity() {
    private val options = listOf(1, 3, 5, 7)
    private val calendarChecks = LinkedHashMap<Long, CheckBox>()
    private var sources: List<CalendarSource> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val d = resources.displayMetrics.density
        fun dp(value: Int) = (d * value).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFF4F7FB.toInt())
            setPadding(dp(18), dp(18), dp(18), dp(24))
        }
        fun label(textValue: String, large: Boolean = false): TextView {
            return TextView(this).apply {
                text = textValue
                textSize = if (large) 18f else 14f
                setTextColor(if (large) 0xFF15253B.toInt() else 0xFF36475D.toInt())
                if (large) setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(0, dp(if (large) 23 else 10), 0, dp(5))
            }
        }
        fun tip(value: String): TextView {
            return TextView(this).apply {
                text = value
                textSize = 12f
                setTextColor(0xFF62748A.toInt())
                setPadding(0, dp(5), 0, dp(8))
            }
        }
        fun combo(values: List<String>, pos: Int): Spinner {
            return Spinner(this).also {
                it.adapter = ArrayAdapter(
                    this, android.R.layout.simple_spinner_dropdown_item, values
                )
                it.setSelection(pos.coerceIn(0, values.lastIndex))
                root.addView(it, ViewGroup.LayoutParams(-1, dp(46)))
            }
        }
        fun button(name: String, click: () -> Unit): Button {
            return Button(this).apply {
                text = name
                setOnClickListener { click() }
            }.also { root.addView(it, ViewGroup.LayoutParams(-1, dp(49))) }
        }

        root.addView(TextView(this).apply {
            text = "Cooldong Calendar"
            textSize = 25f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(0xFF182B42.toInt())
        })
        root.addView(tip("삼성 캘린더와 함께 쓰는 주간 타임라인 위젯"))

        root.addView(label("① 캘린더 연결", true))
        val granted = CalendarRepository.hasPermission(this)
        root.addView(tip(
            if (granted) "✓ 캘린더 읽기 권한 허용됨"
            else "⚠ 권한이 없어 위젯에 일정이 보이지 않습니다."
        ))
        if (!granted) button("캘린더 접근 권한 허용") {
            requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR), 22)
        }

        sources = CalendarRepository.calendars(this)
        val selected = CalendarRepository.selectedIds(this, sources)
        root.addView(tip(
            "휴대폰 캘린더 저장소: " + sources.size + "개 · 선택: " +
                sources.count { it.id in selected } + "개"
        ))
        if (sources.isEmpty()) {
            root.addView(tip(
                if (granted) {
                    "조회되는 캘린더가 없습니다. 삼성 캘린더의 계정 동기화와 표시 설정을 확인하세요."
                } else {
                    "권한을 허용하면 연결 가능한 캘린더 목록이 표시됩니다."
                }
            ))
        } else {
            root.addView(tip(
                "삼성 캘린더에 연결된 계정의 일정도 아래에서 선택해야 표시됩니다."
            ))
            for (source in sources) {
                val check = CheckBox(this).apply {
                    isChecked = source.id in selected
                    text = source.label + "  ·  " + source.account +
                        if (source.visible) "" else " (기기에서 숨김)"
                    textSize = 13f
                    setTextColor(0xFF24374D.toInt())
                    minHeight = dp(42)
                }
                calendarChecks[source.id] = check
                root.addView(check)
            }
            button("모든 캘린더 선택") {
                calendarChecks.values.forEach { it.isChecked = true }
            }
            button("기기에 표시된 캘린더만 선택") {
                for (source in sources) {
                    calendarChecks[source.id]?.isChecked = source.visible
                }
            }
        }

        root.addView(label("② 주간 화면 설정", true))
        root.addView(label("주간 시작 날짜"))
        val basis = combo(
            listOf("월요일 기준", "오늘 기준", "일요일 기준"),
            prefs.getInt("basis", 0)
        )
        root.addView(label("가로 표시 일수"))
        val days = combo(
            options.map { it.toString() + "일" },
            options.indexOf(prefs.getInt("days", 7)).coerceAtLeast(0)
        )

        root.addView(label("세로 시간축 시작"))
        val fromHour = combo(
            (0..23).map { String.format("%02d:00", it) },
            prefs.getInt("startHour", 7)
        )
        root.addView(label("세로 시간축 종료"))
        val endHour = combo(
            (1..24).map { String.format("%02d:00", it) },
            prefs.getInt("endHour", 22) - 1
        )
        root.addView(tip("설정한 시간 밖의 일정은 시간표에 표시되지 않습니다."))

        root.addView(label("배경 투명도"))
        val transparency = SeekBar(this).apply {
            max = 100
            progress = prefs.getInt("transparency", 0).coerceIn(0, 100)
        }
        val transparencyText = tip("0% = 불투명")
        root.addView(transparencyText)
        root.addView(transparency)
        transparency.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                transparencyText.text = "배경 투명도: " + value + "%"
            }
            override fun onStartTrackingTouch(bar: SeekBar?) {}
            override fun onStopTrackingTouch(bar: SeekBar?) {}
        })
        transparencyText.text = "배경 투명도: " + transparency.progress + "%"
        val showTimes = CheckBox(this).apply {
            text = "일정 블록에 시작 시각 표시"
            isChecked = prefs.getBoolean("times", true)
        }
        root.addView(showTimes)

        fun persist(): Boolean {
            val from = fromHour.selectedItemPosition
            val to = endHour.selectedItemPosition + 1
            if (to <= from) {
                Toast.makeText(this, "종료 시각은 시작 시각보다 늦어야 합니다", Toast.LENGTH_LONG).show()
                return false
            }
            val chosen = calendarChecks.filter { it.value.isChecked }.keys
            if (sources.isNotEmpty() && chosen.isEmpty()) {
                Toast.makeText(this, "캘린더를 하나 이상 선택하세요", Toast.LENGTH_LONG).show()
                return false
            }
            prefs.edit()
                .putInt("basis", basis.selectedItemPosition)
                .putInt("days", options[days.selectedItemPosition])
                .putInt("startHour", from)
                .putInt("endHour", to)
                .putInt("transparency", transparency.progress)
                .putBoolean("times", showTimes.isChecked)
                .apply()
            if (sources.isNotEmpty()) CalendarRepository.saveSelectedIds(this, chosen)
            return true
        }

        button("설정 저장 · 위젯 새로고침") {
            if (!persist()) return@button
            if (!CalendarRepository.hasPermission(this)) {
                requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR), 22)
            } else {
                Thread {
                    CalendarWidget.refreshInstalledWidgets(this)
                    runOnUiThread {
                        Toast.makeText(this, "위젯을 갱신했습니다", Toast.LENGTH_SHORT).show()
                    }
                }.start()
            }
        }

        button("왜 일정이 안 보이나요? · 진단") {
            if (!persist()) return@button
            if (!CalendarRepository.hasPermission(this)) {
                requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR), 22)
                return@button
            }
            Thread {
                val start = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    add(Calendar.DATE, -7)
                }
                val end = (start.clone() as Calendar).apply { add(Calendar.DATE, 38) }
                val data = CalendarRepository.load(this, start.timeInMillis, end.timeInMillis)
                val grouped = data.events.groupBy { it.allDay }
                val details = "캘린더: " + data.calendars.size + "개 (선택 " +
                    data.selectedCalendarCount + "개)\n" +
                    "전후 약 1개월간 읽어온 일정: " + data.events.size + "건\n" +
                    "하루 종일 일정: " + (grouped[true]?.size ?: 0) + "건\n" +
                    "시간 지정 일정: " + (grouped[false]?.size ?: 0) + "건\n" +
                    "표시 시간: " + (fromHour.selectedItemPosition) + ":00 ~ " +
                    (endHour.selectedItemPosition + 1) + ":00\n\n" +
                    (data.error?.plus("\n\n") ?: "") +
                    "삼성 리마인더, 비공개 앱 내부 일정, 기기에 동기화되지 않은 계정 일정은 " +
                    "Android 캘린더 저장소에서 읽을 수 없습니다.\n" +
                    "삼성 캘린더 → 캘린더 관리 / 계정 동기화도 확인하세요."
                runOnUiThread {
                    if (!isFinishing) {
                        AlertDialog.Builder(this)
                            .setTitle("캘린더 연결 진단")
                            .setMessage(details)
                            .setPositiveButton("확인", null)
                            .show()
                    }
                }
            }.start()
        }

        root.addView(label("③ 위젯 크기 선택", true))
        root.addView(tip(
            "소형·표준·대형 중 골라 새 위젯을 추가할 수 있습니다. " +
            "이미 배치한 위젯은 홈 화면에서 길게 눌러 크기를 조정하세요."
        ))
        val presets = combo(
            listOf("소형 · 약 3×2칸", "표준 · 약 4×3칸", "대형 · 약 5×5칸"),
            1
        )
        button("선택한 크기로 홈 화면에 위젯 추가") {
            if (!persist()) return@button
            val manager = AppWidgetManager.getInstance(this)
            val widgetClass = when (presets.selectedItemPosition) {
                0 -> CompactCalendarWidget::class.java
                2 -> ExpandedCalendarWidget::class.java
                else -> CalendarWidget::class.java
            }
            if (manager.isRequestPinAppWidgetSupported) {
                val accepted = manager.requestPinAppWidget(
                    ComponentName(this, widgetClass), null, null
                )
                if (!accepted) {
                    Toast.makeText(this, "위젯 추가가 거절되었습니다", Toast.LENGTH_LONG).show()
                }
            } else {
                AlertDialog.Builder(this)
                    .setTitle("위젯 추가 방법")
                    .setMessage(
                        "현재 홈 화면에서는 앱의 직접 추가 기능을 지원하지 않습니다.\n\n" +
                        "홈 화면 빈 곳 길게 누르기 → 위젯 → Cooldong Calendar → " +
                        "소형·표준·대형 중 선택하세요."
                    )
                    .setPositiveButton("확인", null)
                    .show()
            }
        }
        root.addView(tip(
            "위젯의 ‹ / › 로 이전·다음 기간을 확인하고, 오늘 버튼으로 복귀합니다.\n" +
            "↻ 는 최신 일정 새로고침, ⚙ 는 이 설정, 날짜나 시간표 터치는 삼성 캘린더를 엽니다."
        ))
        setContentView(ScrollView(this).apply { addView(root) })

        if (!granted) {
            requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR), 22)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 22 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            Thread { CalendarWidget.refreshInstalledWidgets(this) }.start()
            recreate()
        }
    }
}
