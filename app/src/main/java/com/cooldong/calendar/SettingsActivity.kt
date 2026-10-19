package com.cooldong.calendar
import android.Manifest
import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.ViewGroup
import android.widget.*
class SettingsActivity : Activity() {
 override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState)
  val prefs = getSharedPreferences("settings", MODE_PRIVATE)
  val page = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(32,36,32,20); setBackgroundColor(0xFFF5F8FC.toInt()) }
  fun title(s:String) { page.addView(TextView(this).apply { text=s; textSize=19f; setTextColor(0xFF14233C.toInt()); setPadding(0,14,0,12) }) }
  title("Cooldong Calendar  v1.0")
  title("표시 시작 기준")
  val basis = Spinner(this); basis.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,listOf("월요일 기준","오늘 기준")); basis.setSelection(prefs.getInt("basis",0)); page.addView(basis)
  title("표시 일수")
  val days=Spinner(this); val options=listOf(1,3,5,7); days.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,options.map{"${it}일"}); days.setSelection(options.indexOf(prefs.getInt("days",7)).coerceAtLeast(0)); page.addView(days)
  title("배경 투명도")
  val alpha=SeekBar(this).apply { max=100; progress=prefs.getInt("transparency",0) }; page.addView(alpha)
  val times=CheckBox(this).apply { text="일정 시간 표시"; isChecked=prefs.getBoolean("times",true) }; page.addView(times)
  val save=Button(this).apply { text="설정 저장 및 위젯 갱신" }; page.addView(save,ViewGroup.LayoutParams(-1,-2))
  val note=TextView(this).apply { text="홈 화면 빈 공간 길게 누르기 → 위젯 → Cooldong Calendar 추가\n\n휴대폰 설정에서 구글 계정의 캘린더 동기화를 활성화하세요."; setPadding(0,18,0,0) }; page.addView(note)
  setContentView(ScrollView(this).apply { addView(page) })
  save.setOnClickListener {
   prefs.edit().putInt("basis",basis.selectedItemPosition).putInt("days",options[days.selectedItemPosition]).putInt("transparency",alpha.progress).putBoolean("times",times.isChecked).apply()
   if(checkSelfPermission(Manifest.permission.READ_CALENDAR)!=PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR),22) else refresh()
  }
  if(checkSelfPermission(Manifest.permission.READ_CALENDAR)!=PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR),22)
 }
 override fun onRequestPermissionsResult(requestCode:Int, permissions:Array<out String>, grantResults:IntArray) { super.onRequestPermissionsResult(requestCode,permissions,grantResults); if(requestCode==22 && grantResults.firstOrNull()==PackageManager.PERMISSION_GRANTED) refresh() }
 private fun refresh() { val m=AppWidgetManager.getInstance(this); val ids=m.getAppWidgetIds(ComponentName(this,CalendarWidget::class.java)); CalendarWidget.updateAll(this,m,ids); Toast.makeText(this,"위젯 설정이 저장되었습니다",Toast.LENGTH_SHORT).show() }
}