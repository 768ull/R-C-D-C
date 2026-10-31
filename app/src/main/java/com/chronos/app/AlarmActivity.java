package com.chronos.app;
import android.app.Activity;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.*;
import android.content.Context;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.*;
import android.graphics.Color;
import android.graphics.Typeface;
import androidx.core.app.NotificationManagerCompat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Tela nativa de alarme, compatível com tela bloqueada. */
public final class AlarmActivity extends Activity {
 private Ringtone ringtone; private Vibrator vibrator; private long id; private String label,tone; private boolean vibrate;
 @Override protected void onCreate(Bundle state){
  super.onCreate(state);
  getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON|WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED|WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON|WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
  if(Build.VERSION.SDK_INT>=27){setShowWhenLocked(true);setTurnScreenOn(true);}
  id=getIntent().getLongExtra(AlarmReceiver.ID,-1);label=getIntent().getStringExtra(AlarmReceiver.LABEL);tone=getIntent().getStringExtra(AlarmReceiver.TONE);vibrate=getIntent().getBooleanExtra(AlarmReceiver.VIBRATE,true);if(label==null)label="Alarme";
  LinearLayout root=new LinearLayout(this);root.setOrientation(1);root.setGravity(Gravity.CENTER);root.setPadding(28,32,28,32);root.setBackgroundColor(Color.rgb(15,23,42));
  TextView brand=new TextView(this);brand.setText("CHRONOS");brand.setTextColor(Color.rgb(56,189,248));brand.setTextSize(14);brand.setGravity(Gravity.CENTER);root.addView(brand,lp());
  TextView time=new TextView(this);time.setText(new SimpleDateFormat("HH:mm",Locale.getDefault()).format(new Date()));time.setTextColor(Color.WHITE);time.setTextSize(64);time.setTypeface(Typeface.DEFAULT,Typeface.BOLD);time.setGravity(Gravity.CENTER);LinearLayout.LayoutParams tp=lp();tp.topMargin=28;root.addView(time,tp);
  TextView name=new TextView(this);name.setText(label);name.setTextColor(Color.LTGRAY);name.setTextSize(20);name.setGravity(Gravity.CENTER);LinearLayout.LayoutParams np=lp();np.topMargin=10;root.addView(name,np);
  Button snooze=new Button(this);snooze.setText("Soneca · 10 minutos");LinearLayout.LayoutParams sp=lp();sp.topMargin=54;root.addView(snooze,sp);snooze.setOnClickListener(v->{AlarmReceiver.snooze(this,id,label,tone,vibrate);stopAndFinish();});
  Button dismiss=new Button(this);dismiss.setText("Desligar alarme");LinearLayout.LayoutParams dp=lp();dp.topMargin=12;root.addView(dismiss,dp);dismiss.setOnClickListener(v->stopAndFinish());
  setContentView(root);playTone();if(vibrate)startVibration();
 }
 private LinearLayout.LayoutParams lp(){return new LinearLayout.LayoutParams(-1,-2);}
 private void playTone(){try{Uri u=tone==null||tone.isEmpty()?RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM):Uri.parse(tone);if(u==null)u=RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);ringtone=RingtoneManager.getRingtone(this,u);if(ringtone!=null){ringtone.setStreamType(android.media.AudioManager.STREAM_ALARM);if(Build.VERSION.SDK_INT>=28)ringtone.setLooping(true);ringtone.play();}}catch(Exception ignored){}}
 private void startVibration(){try{vibrator=(Vibrator)getSystemService(Context.VIBRATOR_SERVICE);if(vibrator!=null&&vibrator.hasVibrator()){long[] p={0,700,500,700,500};if(Build.VERSION.SDK_INT>=26)vibrator.vibrate(VibrationEffect.createWaveform(p,0));else vibrator.vibrate(p,0);}}catch(Exception ignored){}}
 private void stopAndFinish(){NotificationManagerCompat.from(this).cancel(AlarmReceiver.code(id));if(ringtone!=null){try{ringtone.stop();}catch(Exception ignored){}}if(vibrator!=null)vibrator.cancel();finish();}
 @Override protected void onDestroy(){if(ringtone!=null)try{ringtone.stop();}catch(Exception ignored){}if(vibrator!=null)vibrator.cancel();super.onDestroy();}
}
