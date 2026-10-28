package com.giblicious.colorclock;

import android.app.AlarmManager;
import android.app.Application;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;

/** Restarts the display a moment after any crash. */
public class ColorClockApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            Log.e("ColorClock", "Crashed; restarting", error);
            restart(this);
        });
    }

    /** Kills the process and relaunches the display two seconds later. */
    static void restart(Context context) {
        Intent intent = new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent relaunch = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE);
        context.getSystemService(AlarmManager.class).set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + 2_000, relaunch);
        android.os.Process.killProcess(android.os.Process.myPid());
        System.exit(10);
    }
}
