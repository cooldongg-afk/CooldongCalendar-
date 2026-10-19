package com.cooldong.calendar

import android.Manifest
import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Bundle
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

class SettingsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val density = resources.displayMetrics.density
        fun px(dp: Int) = (dp * density).toInt()
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20), px(22), px(20), px(24))
            setBackgroundColor(0xFFF5F8FC.toInt())
        }
        fun heading(label: String) {
            page.addView(TextView(this).apply {
                text = label
                textSize = 16f
                setTextColor(0xFF22344C.toInt())
                setPadding(0, px(15), 0, px(7))
            })
        }
        fun spinner(values: List<String>, selection: Int): Spinner {
            val s = Spinner(this)
            s.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, values)
            s.setSelection(selection.coerceIn(0, values.size - 1))
            page.addView(s, ViewGroup.LayoutParams(-1, px(48)))
            return s
        }

        val title = TextView(this).apply {
            text = "Cooldong Calendar  ·  주간 타임라인"
            textSize = 21f
            setTextColor(0xFF14233C.toInt())
            setPadding(0, 0, 0, px(6))
        }
        page.addView(title)

        heading("주간 시작 기준")
        val basis = spinner(listOf("월요일부터", "오늘부터", "일요일부터"), prefs.getInt("basis", 0))

        heading("가로 표시 일수")
        val choices = listOf(1, 3, 5, 7)
        val days = spinner(choices.map { it.toString() + "일" },
            choices.indexOf(prefs.getInt("days", 7)).coerceAtLeast(0))

        heading("세로 시간축 시작")
        val beginChoices = (0..23).map { "%02d:00".format(it) }
        val startHour = spinner(beginChoices, prefs.getInt("startHour", 7))

        heading("세로 시간축 종료")
        val finishChoices = (1..24).map { "%02d:00".format(it) }
        val endHour = spinner(finishChoices, prefs.getInt("endHour", 22) - 1)

        heading("배경 투명도")
        val alphaLabel = TextView(this)
        page.addView(alphaLabel)
        val alpha = SeekBar(this).apply {
            max = 100
            progress = prefs.getInt("transparency", 0)
        }
        fun updateAlpha(value: Int) { alphaLabel.text = value.toString() + "% (0% = 불투명)" }
        updateAlpha(alpha.progress)
        alpha.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateAlpha(progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        page.addView(alpha)

        val times = CheckBox(this).apply {
            text = "일정 블록에 시작 시간 표시"
            isChecked = prefs.getBoolean("times", true)
        }
        page.addView(times)

        val save = Button(this).apply { text = "설정 저장 및 위젯 갱신" }
        page.addView(save, ViewGroup.LayoutParams(-1, px(52)))

        val note = TextView(this).apply {
            text = "위젯을 누르면 삼성 캘린더가 열립니다.\n\n" +
                "삼성 캘린더에 연결된 Google·삼성·기타 계정 일정 중 휴대폰 캘린더 저장소에 등록된 일정을 표시합니다.\n\n" +
                "위젯 크기: 홈 화면에서 위젯을 길게 눌러 가로·세로 크기를 조절하세요.\n\n" +
                "위젯을 추가하려면 홈 화면 빈 곳 길게 누르기 → 위젯 → Cooldong Calendar."
            textSize = 13f
            setTextColor(0xFF51637B.toInt())
            setPadding(0, px(17), 0, 0)
        }
        page.addView(note)
        setContentView(ScrollView(this).apply { addView(page) })

        save.setOnClickListener {
            val from = startHour.selectedItemPosition
            val to = endHour.selectedItemPosition + 1
            if (to <= from) {
                Toast.makeText(this, "종료 시간은 시작 시간보다 늦어야 합니다", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            prefs.edit()
                .putInt("basis", basis.selectedItemPosition)
                .putInt("days", choices[days.selectedItemPosition])
                .putInt("startHour", from)
                .putInt("endHour", to)
                .putInt("transparency", alpha.progress)
                .putBoolean("times", times.isChecked)
                .apply()
            if (checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR), 22)
            } else {
                refresh()
            }
        }
        if (checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR), 22)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 22 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            refresh()
        }
    }

    private fun refresh() {
        val manager = AppWidgetManager.getInstance(this)
        val ids = manager.getAppWidgetIds(ComponentName(this, CalendarWidget::class.java))
        CalendarWidget.updateAll(this, manager, ids)
        Toast.makeText(this, "설정 저장 및 위젯 갱신 완료", Toast.LENGTH_SHORT).show()
    }
}
