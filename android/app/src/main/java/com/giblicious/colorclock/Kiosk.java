package com.giblicious.colorclock;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.provider.Settings;

/** Device-owner policies that turn the phone into a dedicated display. */
final class Kiosk {
    private Kiosk() {}

    static ComponentName admin(Context context) { return new ComponentName(context, AdminReceiver.class); }

    static DevicePolicyManager dpm(Context context) { return context.getSystemService(DevicePolicyManager.class); }

    static boolean isDeviceOwner(Context context) { return dpm(context).isDeviceOwnerApp(context.getPackageName()); }

    static boolean isLocked(Context context) {
        return context.getSystemService(ActivityManager.class).getLockTaskModeState() != ActivityManager.LOCK_TASK_MODE_NONE;
    }

    /** Applies the standing device-owner policies. Safe to call repeatedly. */
    static void applyPolicies(Context context) {
        if (!isDeviceOwner(context)) return;
        DevicePolicyManager dpm = dpm(context);
        ComponentName admin = admin(context);
        dpm.setLockTaskPackages(admin, new String[] {context.getPackageName()});
        // Keep the power menu so the phone can be restarted by hand, except under child lock.
        boolean childLock = new Prefs(context).childLock();
        dpm.setLockTaskFeatures(admin, childLock ? DevicePolicyManager.LOCK_TASK_FEATURE_NONE : DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS);
        String stayOn = String.valueOf(android.os.BatteryManager.BATTERY_PLUGGED_AC
                | android.os.BatteryManager.BATTERY_PLUGGED_USB
                | android.os.BatteryManager.BATTERY_PLUGGED_WIRELESS);
        dpm.setGlobalSetting(admin, Settings.Global.STAY_ON_WHILE_PLUGGED_IN, stayOn);
    }

    static void lock(Activity activity) {
        if (!isDeviceOwner(activity)) return;
        DevicePolicyManager dpm = dpm(activity);
        ComponentName admin = admin(activity);
        applyPolicies(activity);
        IntentFilter home = new IntentFilter(Intent.ACTION_MAIN);
        home.addCategory(Intent.CATEGORY_HOME);
        home.addCategory(Intent.CATEGORY_DEFAULT);
        dpm.addPersistentPreferredActivity(admin, home, new ComponentName(activity, MainActivity.class));
        dpm.setKeyguardDisabled(admin, true);
        dpm.setStatusBarDisabled(admin, true);
        if (!isLocked(activity)) activity.startLockTask();
    }

    static void unlock(Activity activity) {
        if (isLocked(activity)) activity.stopLockTask();
        if (!isDeviceOwner(activity)) return;
        DevicePolicyManager dpm = dpm(activity);
        ComponentName admin = admin(activity);
        dpm.clearPackagePersistentPreferredActivities(admin, activity.getPackageName());
        dpm.setKeyguardDisabled(admin, false);
        dpm.setStatusBarDisabled(admin, false);
    }

    /** Hands the phone back to normal Android so the app can be uninstalled. */
    static void releaseDeviceOwner(Activity activity) {
        unlock(activity);
        if (isDeviceOwner(activity)) dpm(activity).clearDeviceOwnerApp(activity.getPackageName());
    }
}
