package com.giblicious.colorclock;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Brings the display back after a reboot or an over-the-air update. */
public class StartReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        context.startActivity(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }
}
