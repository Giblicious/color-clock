package com.giblicious.colorclock;

import android.content.Context;
import android.content.SharedPreferences;

/** Everything the app remembers; settings live only on this phone. */
final class Prefs {
    private final SharedPreferences prefs;

    Prefs(Context context) {
        prefs = context.getSharedPreferences("color-clock", Context.MODE_PRIVATE);
    }

    Settings settings() { return Settings.fromJson(prefs.getString("settings", null)); }
    void saveSettings(Settings settings) { prefs.edit().putString("settings", settings.toJson()).apply(); }

    boolean kioskWanted() { return prefs.getBoolean("kiosk", true); }
    void setKioskWanted(boolean wanted) { prefs.edit().putBoolean("kiosk", wanted).apply(); }

    boolean childLock() { return prefs.getBoolean("childLock", false); }
    void setChildLock(boolean on) { prefs.edit().putBoolean("childLock", on).apply(); }

    long lastUpdateCheck() { return prefs.getLong("lastUpdateCheck", 0); }
    void setLastUpdateCheck(long time) { prefs.edit().putLong("lastUpdateCheck", time).apply(); }
}
