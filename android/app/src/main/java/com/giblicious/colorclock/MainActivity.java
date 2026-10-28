package com.giblicious.colorclock;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;
import android.text.format.DateUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.Calendar;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final String TAG = "ColorClock";
    /** Burn-in drift positions in dp, stepped through once a minute. */
    private static final float[][] DRIFT = {{0, 0}, {6, -4}, {-5, 5}, {4, 7}, {-7, -3}, {3, -7}, {-3, 4}, {7, 2}};
    private static final long UPDATE_INTERVAL_MS = 6 * DateUtils.HOUR_IN_MILLIS;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            render();
            if (settingsScreen.isOpen() && System.currentTimeMillis() / 1000 % 3 == 0) settingsScreen.refreshStatus();
            handler.postDelayed(this, 1_000 - System.currentTimeMillis() % 1_000);
        }
    };
    private final Runnable scheduledUpdateCheck = new Runnable() {
        @Override
        public void run() {
            checkForUpdate(false);
            handler.postDelayed(this, UPDATE_INTERVAL_MS);
        }
    };
    /** The gear is removed from layout when hidden so it never catches touches while invisible. */
    private final Runnable hideGear = () -> this.gear.animate().alpha(0f).setDuration(500).withEndAction(() -> this.gear.setVisibility(View.GONE));
    private final Runnable hideLockHint = () -> this.lockHint.animate().alpha(0f).setDuration(400);

    private Prefs prefs;
    private Settings settings;
    private ScreenView screen;
    private View gear;
    private SettingsScreen settingsScreen;
    private HoldRing holdRing;
    private TextView lockHint;
    private float previewBrightness = -1;
    private float appliedBrightness = -1;
    private int appliedDrift = -1;
    private int lockedTaps;
    private long firstLockedTap;
    private volatile String updateStatus = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        settings = prefs.settings();

        FrameLayout root = new FrameLayout(this);
        screen = new ScreenView(this);
        screen.setOnTouchListener((v, event) -> onScreenTouch(event));
        root.addView(screen);

        ImageView gearButton = new ImageView(this);
        gearButton.setImageResource(R.drawable.ic_settings);
        gearButton.setBackgroundResource(R.drawable.bg_chip);
        gearButton.setScaleType(ImageView.ScaleType.CENTER);
        gearButton.setContentDescription("Open settings");
        gearButton.setOnClickListener(v -> openSettings());
        gearButton.setAlpha(0f);
        gearButton.setVisibility(View.GONE);
        FrameLayout.LayoutParams gearParams = new FrameLayout.LayoutParams(dp(52), dp(52), Gravity.TOP | Gravity.END);
        gearParams.setMargins(0, dp(16), dp(18), 0);
        root.addView(gearButton, gearParams);
        gear = gearButton;

        holdRing = new HoldRing(this);
        FrameLayout.LayoutParams ringParams = new FrameLayout.LayoutParams(dp(96), dp(96), Gravity.TOP | Gravity.END);
        ringParams.setMargins(0, dp(22), dp(22), 0);
        root.addView(holdRing, ringParams);

        lockHint = new TextView(this);
        lockHint.setText("Child lock is on · hold the top-right corner to open settings");
        lockHint.setTextColor(Color.WHITE);
        lockHint.setTextSize(14);
        lockHint.setBackgroundResource(R.drawable.bg_chip);
        lockHint.setPadding(dp(16), dp(9), dp(16), dp(9));
        lockHint.setAlpha(0f);
        FrameLayout.LayoutParams hintParams = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        hintParams.bottomMargin = dp(24);
        root.addView(lockHint, hintParams);

        setContentView(root);
        settingsScreen = new SettingsScreen(this, root, new ScreenHost());

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setShowWhenLocked(true);
        setTurnScreenOn(true);
        Kiosk.applyPolicies(this);

        // Check for an update shortly after launch (giving Wi-Fi time to connect), then every 6 hours.
        handler.postDelayed(scheduledUpdateCheck, 30_000);
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemBars();
        if (prefs.kioskWanted()) lockKiosk();
        handler.post(tick);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(tick);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    @Override
    public void onBackPressed() {
        if (settingsScreen.isOpen()) settingsScreen.close();
        else if (!Kiosk.isLocked(this)) super.onBackPressed();
    }

    private void render() {
        Calendar now = Calendar.getInstance();
        boolean day = settings.isDay(now);
        String clock = settings.showTime ? DateFormat.getTimeFormat(this).format(now.getTime()) : null;
        String status = null;
        if (settings.showStatus) {
            String pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), DateFormat.is24HourFormat(this) ? "EEEHHmm" : "EEEhmma");
            status = (day ? "Day" : "Night") + " · changes " + DateFormat.format(pattern, settings.nextChange(now, day));
        }
        String label = day ? settings.dayLabel : settings.nightLabel;
        screen.show(Color.parseColor(day ? settings.dayColor : settings.nightColor), clock, label, status, false);

        int drift = now.get(Calendar.MINUTE) % DRIFT.length;
        if (drift != appliedDrift) {
            screen.shift(DRIFT[drift][0], DRIFT[drift][1]);
            appliedDrift = drift;
        }

        float brightness = previewBrightness >= 0 ? previewBrightness : (day ? settings.dayBrightness : settings.nightBrightness) / 100f;
        if (brightness != appliedBrightness) {
            WindowManager.LayoutParams attributes = getWindow().getAttributes();
            attributes.screenBrightness = brightness;
            getWindow().setAttributes(attributes);
            appliedBrightness = brightness;
        }
    }

    /**
     * Normal mode: a tap reveals the gear. Child lock: taps do nothing (a few quick taps show a
     * hint), and only a 3-second hold on the top-right corner, then a code spelled out in words,
     * opens settings.
     */
    private boolean onScreenTouch(MotionEvent event) {
        if (settingsScreen.isOpen()) return true;
        if (!prefs.childLock()) {
            if (event.getAction() == MotionEvent.ACTION_UP) revealGear();
            return true;
        }
        boolean inCorner = event.getX() > screen.getWidth() - dp(160) && event.getY() < dp(160);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (inCorner) holdRing.start(this::unlockChildLock);
                else countLockedTap();
                break;
            case MotionEvent.ACTION_MOVE:
                if (!inCorner) holdRing.cancel();
                break;
            default:
                holdRing.cancel();
        }
        return true;
    }

    private void countLockedTap() {
        long now = System.currentTimeMillis();
        if (now - firstLockedTap > 2_000) { firstLockedTap = now; lockedTaps = 0; }
        if (++lockedTaps < 3) return;
        lockedTaps = 0;
        handler.removeCallbacks(hideLockHint);
        lockHint.animate().alpha(1f).setDuration(200);
        handler.postDelayed(hideLockHint, 3_500);
    }

    private void unlockChildLock() {
        FrameLayout root = (FrameLayout) screen.getParent();
        UnlockChallenge[] gate = new UnlockChallenge[1];
        gate[0] = new UnlockChallenge(this, new UnlockChallenge.Listener() {
            @Override public void onUnlocked() {
                root.removeView(gate[0]);
                settingsScreen.open(settings);
            }
            @Override public void onCancel() { root.removeView(gate[0]); hideSystemBars(); }
        });
        root.addView(gate[0], new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
    }

    private void revealGear() {
        if (settingsScreen.isOpen() || prefs.childLock()) return;
        handler.removeCallbacks(hideGear);
        gear.setVisibility(View.VISIBLE);
        gear.animate().alpha(1f).setDuration(180).withEndAction(null);
        handler.postDelayed(hideGear, 5_000);
    }

    private void openSettings() {
        if (gear.getAlpha() < 0.2f) return;
        handler.removeCallbacks(hideGear);
        gear.animate().alpha(0f).setDuration(150).withEndAction(() -> gear.setVisibility(View.GONE));
        settingsScreen.open(settings);
    }

    private void hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.systemBars());
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }

    private void lockKiosk() {
        try {
            Kiosk.lock(this);
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not enter kiosk mode", e);
        }
    }

    private void setKiosk(boolean locked) {
        prefs.setKioskWanted(locked);
        if (locked) lockKiosk(); else Kiosk.unlock(this);
    }

    /** Checks GitHub for a newer release; {@code fromUser} shows the result as a toast. */
    private void checkForUpdate(boolean fromUser) {
        Updater.checkInBackground(this, message -> {
            updateStatus = message;
            prefs.setLastUpdateCheck(System.currentTimeMillis());
            runOnUiThread(() -> {
                if (fromUser) Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
                settingsScreen.refreshStatus();
            });
        });
    }

    private Intent batteryState() { return registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED)); }

    private static int batteryLevel(Intent battery) {
        if (battery == null) return -1;
        return battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100 / Math.max(1, battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100));
    }

    private static boolean charging(Intent battery) {
        return battery != null && battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
    }

    // --- on-screen settings ---

    private final class ScreenHost implements SettingsScreen.Host {
        @Override
        public void save(Settings saved, boolean childLock) {
            prefs.setChildLock(childLock);
            Kiosk.applyPolicies(MainActivity.this);
            settings = saved;
            prefs.saveSettings(saved);
            render();
            Toast.makeText(MainActivity.this, "Saved", Toast.LENGTH_SHORT).show();
        }

        @Override
        public void closed() {
            previewBrightness = -1;
            render();
            hideSystemBars();
        }

        @Override
        public void previewBrightness(float brightness) {
            previewBrightness = brightness;
            render();
        }

        @Override
        public void checkForUpdate() { MainActivity.this.checkForUpdate(true); }

        @Override
        public void setKiosk(boolean locked) { MainActivity.this.setKiosk(locked); }

        @Override
        public void releaseDeviceOwner() {
            new AlertDialog.Builder(MainActivity.this, R.style.Theme_ColorClock_Dialog)
                    .setTitle("Release device owner?")
                    .setMessage("Kiosk lockdown and silent updates stop working until the app is set as device owner again over USB. Use this before uninstalling the app.")
                    .setPositiveButton("Release", (d, which) -> {
                        prefs.setKioskWanted(false);
                        Kiosk.releaseDeviceOwner(MainActivity.this);
                        settingsScreen.close();
                        Toast.makeText(MainActivity.this, "Device owner released", Toast.LENGTH_LONG).show();
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        }

        @Override
        public boolean childLock() { return prefs.childLock(); }

        @Override
        public String deviceFacts() {
            Intent battery = batteryState();
            String kiosk = Kiosk.isDeviceOwner(MainActivity.this) ? (Kiosk.isLocked(MainActivity.this) ? "Kiosk locked" : "Kiosk unlocked") : "Not device owner";
            return "Version " + BuildConfig.VERSION_NAME + "\n" + kiosk + "\n"
                    + "Battery " + batteryLevel(battery) + "%" + (charging(battery) ? " · charging" : "");
        }

        @Override
        public String updateInfo() {
            long last = prefs.lastUpdateCheck();
            String checked = last == 0 ? "Not checked yet"
                    : "Last checked " + DateUtils.getRelativeTimeSpanString(last, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS);
            return "Updates come from GitHub, checked every 6 hours.\n" + checked + (updateStatus.isEmpty() ? "" : " · " + updateStatus);
        }
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
