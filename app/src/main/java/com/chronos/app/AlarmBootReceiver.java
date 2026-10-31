package com.chronos.app;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
/** Restaura alarmes depois de reiniciar ou atualizar o Chronos. */
public final class AlarmBootReceiver extends BroadcastReceiver {
 @Override public void onReceive(Context c,Intent i){if(i==null)return;String a=i.getAction();if(Intent.ACTION_BOOT_COMPLETED.equals(a)||Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)||Intent.ACTION_TIME_CHANGED.equals(a)||Intent.ACTION_TIMEZONE_CHANGED.equals(a))AlarmReceiver.restore(c);}
}
