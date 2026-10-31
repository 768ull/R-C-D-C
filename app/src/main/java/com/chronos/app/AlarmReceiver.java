package com.chronos.app;
import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.Build;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import org.json.*;
import java.util.Calendar;

/** Persistência e agendamento de alarmes Chronos pelo AlarmManager do Android. */
public final class AlarmReceiver extends BroadcastReceiver {
 static final String PREFS="chronos_alarm_store", KEY="alarms_json", CHANNEL="chronos_alarm_channel";
 static final String ID="alarm_id", LABEL="alarm_label", TONE="alarm_tone", VIBRATE="alarm_vibrate", SNOOZE="alarm_snooze";
 private static final long SNOOZE_OFFSET=900000000000L;
 @Override public void onReceive(Context c, Intent in) {
  if(in==null)return; long id=in.getLongExtra(ID,-1); if(id<0)return;
  boolean snooze=in.getBooleanExtra(SNOOZE,false);
  String label=in.getStringExtra(LABEL), tone=in.getStringExtra(TONE);
  boolean vibrate=in.getBooleanExtra(VIBRATE,true);
  if(!snooze){
   JSONObject a=find(c,id); if(a==null||!a.optBoolean("enabled",true))return;
   label=a.optString("label","Alarme"); tone=a.optString("tone",""); vibrate=a.optBoolean("vibrate",true);
   if(hasDays(a.optJSONArray("days"))) schedule(c,a);
   else { try { a.put("enabled",false); upsert(c,a,false); } catch (org.json.JSONException ignored) {} }
  }
  notifyAlarm(c,id,label,tone,vibrate,snooze);
 }
 static JSONArray alarms(Context c){try{return new JSONArray(c.getSharedPreferences(PREFS,0).getString(KEY,"[]"));}catch(Exception e){return new JSONArray();}}
 static String alarmsJson(Context c){return alarms(c).toString();}
 static void save(Context c,JSONObject a){if(a==null||a.optLong("id",-1)<0)return;try{a.put("enabled",a.optBoolean("enabled",true));upsert(c,a,true);}catch(org.json.JSONException ignored){}}
 static void enable(Context c,long id,boolean value){JSONObject a=find(c,id);if(a!=null){try{a.put("enabled",value);upsert(c,a,true);}catch(org.json.JSONException ignored){}}}
 static void delete(Context c,long id){cancel(c,id);JSONArray old=alarms(c),next=new JSONArray();for(int i=0;i<old.length();i++){JSONObject a=old.optJSONObject(i);if(a!=null&&a.optLong("id")!=id)next.put(a);}store(c,next);}
 static void restore(Context c){JSONArray all=alarms(c);for(int i=0;i<all.length();i++){JSONObject a=all.optJSONObject(i);if(a!=null&&a.optBoolean("enabled",true))schedule(c,a);}}
 private static void store(Context c,JSONArray a){c.getSharedPreferences(PREFS,0).edit().putString(KEY,a.toString()).apply();}
 private static JSONObject find(Context c,long id){JSONArray a=alarms(c);for(int i=0;i<a.length();i++){JSONObject x=a.optJSONObject(i);if(x!=null&&x.optLong("id")==id)return x;}return null;}
 private static void upsert(Context c,JSONObject a,boolean reschedule){
  long id=a.optLong("id");JSONArray old=alarms(c),next=new JSONArray();boolean found=false;
  for(int i=0;i<old.length();i++){JSONObject x=old.optJSONObject(i);if(x==null)continue;if(x.optLong("id")==id){next.put(a);found=true;}else next.put(x);}
  if(!found)next.put(a);store(c,next);
  if(reschedule){if(a.optBoolean("enabled",true))schedule(c,a);else cancel(c,id);}
 }
 private static boolean hasDays(JSONArray days){if(days==null)return false;for(int i=0;i<days.length();i++)if(days.optBoolean(i))return true;return false;}
 static void schedule(Context c,JSONObject a){
  if(a==null||!a.optBoolean("enabled",true))return;
  long id=a.optLong("id",-1);String t=a.optString("time","");
  if(id<0||!t.matches("\\d{2}:\\d{2}"))return;
  int h=Integer.parseInt(t.substring(0,2)),m=Integer.parseInt(t.substring(3,5));if(h>23||m>59)return;
  long when=next(h,m,a.optJSONArray("days"));
  Intent i=new Intent(c,AlarmReceiver.class).setAction("com.chronos.app.ALARM."+id).setData(Uri.parse("chronos-alarm://scheduled/"+id)).putExtra(ID,id);
  PendingIntent pi=PendingIntent.getBroadcast(c,code(id),i,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
  AlarmManager am=(AlarmManager)c.getSystemService(Context.ALARM_SERVICE);if(am==null)return;
  try{if(Build.VERSION.SDK_INT>=31&&!am.canScheduleExactAlarms())am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,when,pi);else am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,when,pi);}
  catch(SecurityException e){am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,when,pi);}
 }
 static void cancel(Context c,long id){
  Intent i=new Intent(c,AlarmReceiver.class).setAction("com.chronos.app.ALARM."+id).setData(Uri.parse("chronos-alarm://scheduled/"+id));
  PendingIntent pi=PendingIntent.getBroadcast(c,code(id),i,PendingIntent.FLAG_NO_CREATE|PendingIntent.FLAG_IMMUTABLE);
  if(pi!=null){AlarmManager am=(AlarmManager)c.getSystemService(Context.ALARM_SERVICE);if(am!=null)am.cancel(pi);pi.cancel();}
 }
 static void snooze(Context c,long original,String label,String tone,boolean vibrate){
  long id=original+SNOOZE_OFFSET;
  Intent i=new Intent(c,AlarmReceiver.class).setAction("com.chronos.app.SNOOZE."+id).setData(Uri.parse("chronos-alarm://snooze/"+id));
  i.putExtra(ID,id).putExtra(LABEL,label).putExtra(TONE,tone).putExtra(VIBRATE,vibrate).putExtra(SNOOZE,true);
  PendingIntent pi=PendingIntent.getBroadcast(c,code(id),i,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
  AlarmManager am=(AlarmManager)c.getSystemService(Context.ALARM_SERVICE);if(am==null)return;
  long when=System.currentTimeMillis()+600000L;
  try{if(Build.VERSION.SDK_INT>=31&&!am.canScheduleExactAlarms())am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,when,pi);else am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,when,pi);}
  catch(SecurityException e){am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,when,pi);}
 }
 private static long next(int h,int m,JSONArray days){
  Calendar now=Calendar.getInstance();long n=now.getTimeInMillis();boolean repeat=hasDays(days);
  for(int d=0;d<=(repeat?7:1);d++){Calendar x=(Calendar)now.clone();x.add(Calendar.DAY_OF_YEAR,d);x.set(Calendar.HOUR_OF_DAY,h);x.set(Calendar.MINUTE,m);x.set(Calendar.SECOND,0);x.set(Calendar.MILLISECOND,0);
   if(x.getTimeInMillis()<=n+1000)continue;
   if(repeat){int mondayFirst=(x.get(Calendar.DAY_OF_WEEK)+5)%7;if(!days.optBoolean(mondayFirst))continue;}
   return x.getTimeInMillis();
  }
  Calendar x=(Calendar)now.clone();x.add(Calendar.DAY_OF_YEAR,1);x.set(Calendar.HOUR_OF_DAY,h);x.set(Calendar.MINUTE,m);x.set(Calendar.SECOND,0);x.set(Calendar.MILLISECOND,0);return x.getTimeInMillis();
 }
 static int code(long id){return (int)(id^(id>>>32));}
 private static void notifyAlarm(Context c,long id,String label,String tone,boolean vibrate,boolean snooze){
  if(Build.VERSION.SDK_INT>=26){NotificationManager nm=c.getSystemService(NotificationManager.class);if(nm!=null&&nm.getNotificationChannel(CHANNEL)==null){NotificationChannel ch=new NotificationChannel(CHANNEL,"Despertador Chronos",NotificationManager.IMPORTANCE_HIGH);ch.setDescription("Alarmes do Chronos");ch.setSound(null,null);ch.enableVibration(false);nm.createNotificationChannel(ch);}}
  Intent open=new Intent(c,AlarmActivity.class).putExtra(ID,id).putExtra(LABEL,label==null||label.isEmpty()?"Alarme":label).putExtra(TONE,tone==null?"":tone).putExtra(VIBRATE,vibrate).putExtra(SNOOZE,snooze).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP);
  PendingIntent pi=PendingIntent.getActivity(c,code(id),open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
  Notification n=new NotificationCompat.Builder(c,CHANNEL).setSmallIcon(android.R.drawable.ic_lock_idle_alarm).setContentTitle("Chronos · Hora de acordar").setContentText(label==null||label.isEmpty()?"Seu alarme está tocando":label).setCategory(NotificationCompat.CATEGORY_ALARM).setPriority(NotificationCompat.PRIORITY_MAX).setVisibility(NotificationCompat.VISIBILITY_PUBLIC).setOngoing(true).setAutoCancel(false).setSound(null).setFullScreenIntent(pi,true).setContentIntent(pi).build();
  try{if(Build.VERSION.SDK_INT<33||c.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)==android.content.pm.PackageManager.PERMISSION_GRANTED)NotificationManagerCompat.from(c).notify(code(id),n);}catch(SecurityException ignored){}
 }
}
