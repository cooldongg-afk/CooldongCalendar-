package com.cooldong.calendar
import android.Manifest
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.provider.CalendarContract
import android.widget.RemoteViews
import java.text.SimpleDateFormat
import java.util.*
class CalendarWidget : AppWidgetProvider() {
 override fun onUpdate(context:Context, manager:AppWidgetManager, ids:IntArray) = updateAll(context,manager,ids)
 companion object {
  fun updateAll(ctx:Context,m:AppWidgetManager,ids:IntArray) {
   val prefs=ctx.getSharedPreferences("settings",Context.MODE_PRIVATE)
   val days=prefs.getInt("days",7).coerceIn(1,7)
   val start=Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY,0); set(Calendar.MINUTE,0); set(Calendar.SECOND,0); set(Calendar.MILLISECOND,0); if(prefs.getInt("basis",0)==0) { val diff=(get(Calendar.DAY_OF_WEEK)+5)%7; add(Calendar.DAY_OF_MONTH,-diff) } }
   val fmt=SimpleDateFormat("M/d E",Locale.KOREAN)
   val tf=SimpleDateFormat("HH:mm",Locale.KOREAN)
   val showTimes=prefs.getBoolean("times",true)
   for(id in ids) {
    val widget=RemoteViews(ctx.packageName,R.layout.widget)
    val opacity=(255*(100-prefs.getInt("transparency",0).coerceIn(0,100))/100f).toInt()
    widget.setInt(R.id.root,"setBackgroundColor",Color.argb(opacity,255,255,255))
    widget.setTextViewText(R.id.header,"${start.get(Calendar.MONTH)+1}월  ·  Cooldong Calendar")
    widget.removeAllViews(R.id.columns)
    for(d in 0 until days) {
     val day=start.clone() as Calendar; day.add(Calendar.DATE,d)
     val col=RemoteViews(ctx.packageName,R.layout.day_column)
     col.setTextViewText(R.id.day_title,fmt.format(day.time))
     col.removeAllViews(R.id.events)
     val next=day.clone() as Calendar; next.add(Calendar.DATE,1)
     val events=readEvents(ctx,day.timeInMillis,next.timeInMillis)
     if(events.isEmpty()) { val e=RemoteViews(ctx.packageName,R.layout.event); e.setTextViewText(R.id.event_text," "); e.setInt(R.id.event_text,"setBackgroundColor",Color.TRANSPARENT); col.addView(R.id.events,e) }
     for(ev in events.take(9)) {
      val e=RemoteViews(ctx.packageName,R.layout.event)
      val time=if(showTimes) "\n${tf.format(Date(ev.start))}" else ""
      e.setTextViewText(R.id.event_text,ev.title+time)
      val color=if(ev.color!=0) ev.color else 0xFFB8D7FA.toInt()
      e.setInt(R.id.event_text,"setBackgroundColor",Color.argb(70,Color.red(color),Color.green(color),Color.blue(color)))
      e.setFloat(R.id.event_text,"setTextSize",when(days) { 1->14f; 3->12f; 5->10f; else->9f })
      col.addView(R.id.events,e)
     }
     widget.addView(R.id.columns,col)
    }
    val intent=Intent(ctx,SettingsActivity::class.java)
    widget.setOnClickPendingIntent(R.id.header,PendingIntent.getActivity(ctx,0,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
    m.updateAppWidget(id,widget)
   }
  }
  private data class Ev(val title:String,val start:Long,val color:Int)
  private fun readEvents(ctx:Context,start:Long,end:Long):List<Ev> {
   if(ctx.checkSelfPermission(Manifest.permission.READ_CALENDAR)!=PackageManager.PERMISSION_GRANTED) return emptyList()
   val result=mutableListOf<Ev>()
   val uri=CalendarContract.Instances.CONTENT_URI.buildUpon().also { android.content.ContentUris.appendId(it,start); android.content.ContentUris.appendId(it,end) }.build()
   val projection=arrayOf(CalendarContract.Instances.TITLE,CalendarContract.Instances.BEGIN,CalendarContract.Instances.DISPLAY_COLOR)
   try { ctx.contentResolver.query(uri,projection,null,null,CalendarContract.Instances.BEGIN+" ASC")?.use { c -> while(c.moveToNext()) { result.add(Ev(c.getString(0)?:"일정",c.getLong(1),c.getInt(2))) } } } catch (_:Exception) { }
   return result
  }
 }
}