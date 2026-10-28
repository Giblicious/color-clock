package com.giblicious.colorclock;

import android.app.Activity;
import android.app.TimePickerDialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import java.util.Calendar;
import java.util.Locale;

/**
 * Full-screen settings laid out for the 800x360dp landscape display, one tab per concern and
 * nothing that scrolls. Every change is a draft until Done; the close button discards it.
 */
final class SettingsScreen {
    private static final String[] PALETTE = {
        "#16a34a", "#22c55e", "#0ea5e9", "#2563eb", "#7c3aed", "#db2777", "#dc2626",
        "#ea580c", "#f59e0b", "#facc15", "#ffffff", "#64748b", "#000000",
    };

    interface Host {
        void save(Settings settings, boolean childLock);
        void closed();
        void previewBrightness(float brightness);
        void checkForUpdate();
        void setKiosk(boolean locked);
        void releaseDeviceOwner();
        String deviceFacts();
        String updateInfo();
        boolean childLock();
    }

    private final Activity activity;
    private final FrameLayout root;
    private final Host host;
    private final float density;
    private View view;
    private Settings draft;
    private boolean childLock;

    SettingsScreen(Activity activity, FrameLayout root, Host host) {
        this.activity = activity;
        this.root = root;
        this.host = host;
        density = activity.getResources().getDisplayMetrics().density;
    }

    boolean isOpen() { return view != null; }

    void open(Settings current) {
        if (isOpen()) return;
        draft = current.copy();
        childLock = host.childLock();

        view = activity.getLayoutInflater().inflate(R.layout.settings_screen, root, false);
        root.addView(view);
        view.setAlpha(0f);
        view.setScaleX(1.03f);
        view.setScaleY(1.03f);
        view.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220);

        view.findViewById(R.id.close).setOnClickListener(v -> close());
        view.findViewById(R.id.done).setOnClickListener(this::done);
        int[] tabs = {R.id.tab_schedule, R.id.tab_display, R.id.tab_lock, R.id.tab_device};
        for (int i = 0; i < tabs.length; i++) {
            int index = i;
            view.findViewById(tabs[i]).setOnClickListener(v -> showTab(index));
        }

        bindPeriod(view.findViewById(R.id.editor_day), true);
        bindPeriod(view.findViewById(R.id.editor_night), false);
        bindDisplay();
        bindLock();
        bindDevice();
        showTab(0);
    }

    void close() {
        if (view == null) return;
        View closing = view;
        view = null;
        hideKeyboard(closing);
        closing.animate().alpha(0f).setDuration(180).withEndAction(() -> root.removeView(closing));
        host.closed();
    }

    /** Refreshes live status on the Device tab; called periodically while open. */
    void refreshStatus() {
        if (view == null) return;
        ((TextView) view.findViewById(R.id.device_facts)).setText(host.deviceFacts());
        ((TextView) view.findViewById(R.id.update_info)).setText(host.updateInfo());
        Button kiosk = view.findViewById(R.id.kiosk);
        kiosk.setOnLongClickListener(null);
        if (!Kiosk.isDeviceOwner(activity)) {
            kiosk.setText("Kiosk needs device owner");
            kiosk.setEnabled(false);
        } else if (Kiosk.isLocked(activity)) {
            kiosk.setText("Hold to unlock kiosk");
            kiosk.setOnClickListener(v -> toast("Press and hold to unlock kiosk"));
            kiosk.setOnLongClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                host.setKiosk(false);
                refreshStatus();
                return true;
            });
        } else {
            kiosk.setText("Lock kiosk");
            kiosk.setOnClickListener(v -> { host.setKiosk(true); refreshStatus(); });
        }
    }

    private void showTab(int index) {

        int[] tabs = {R.id.tab_schedule, R.id.tab_display, R.id.tab_lock, R.id.tab_device};
        int[] pages = {R.id.page_schedule, R.id.page_display, R.id.page_lock, R.id.page_device};
        for (int i = 0; i < tabs.length; i++) {
            TextView label = view.findViewById(tabs[i]);
            label.setSelected(i == index);
            label.setTextColor(activity.getColor(i == index ? R.color.text : R.color.text_muted));
            view.findViewById(pages[i]).setVisibility(i == index ? View.VISIBLE : View.GONE);
        }
        hideKeyboard(view);
        if (index == 1) refreshDisplay();
        if (index == 3) refreshStatus();
    }

    // --- Schedule: one colored card per period ---

    private void bindPeriod(View card, boolean day) {
        ((TextView) card.findViewById(R.id.period_name)).setText(day ? "DAY" : "NIGHT");
        card.findViewById(R.id.start_time).setOnClickListener(v -> pickTime(card, day));

        EditText label = card.findViewById(R.id.label);
        label.setText(day ? draft.dayLabel : draft.nightLabel);
        label.setHint(day ? "Add a label, like “OK to wake”" : "Add a label, like “Stay in bed”");
        label.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                if (day) draft.dayLabel = Settings.label(s.toString()); else draft.nightLabel = Settings.label(s.toString());
            }
        });
        label.setOnEditorActionListener((v, action, event) -> { hideKeyboard(v); v.clearFocus(); return true; });

        LinearLayout presets = card.findViewById(R.id.presets);
        for (int row = 0; row < 2; row++) {
            LinearLayout line = new LinearLayout(activity);
            LinearLayout.LayoutParams lineParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(34));
            if (row == 1) lineParams.topMargin = dp(8);
            presets.addView(line, lineParams);
            for (int col = 0; col < 7; col++) {
                int index = row * 7 + col;
                FrameLayout cell = new FrameLayout(activity);
                View swatch = new View(activity);
                if (index < PALETTE.length) {
                    String hex = PALETTE[index];
                    swatch.setTag(hex);
                    swatch.setContentDescription(hex);
                    swatch.setOnClickListener(v -> {
                        v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                        setColor(card, day, hex);
                    });
                } else {
                    swatch.setTag("custom");
                    swatch.setContentDescription("Custom color");
                    swatch.setOnClickListener(v -> showCustom(card, day, true));
                }
                cell.addView(swatch, new FrameLayout.LayoutParams(dp(34), dp(34), Gravity.CENTER));
                line.addView(cell, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
            }
        }

        card.findViewById(R.id.custom_back).setOnClickListener(v -> showCustom(card, day, false));
        SeekBar hue = card.findViewById(R.id.hue);
        SeekBar shade = card.findViewById(R.id.shade);
        int[] hues = new int[7];
        for (int i = 0; i < hues.length; i++) hues[i] = Color.HSVToColor(new float[] {i * 60f, 1f, 1f});
        hue.setProgressDrawable(track(hues));
        SeekBar.OnSeekBarChangeListener sliders = new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (!fromUser) return;
                if (bar == hue) updateShadeTrack(card);
                setColor(card, day, String.format("#%06x", colorFromSliders(card) & 0xffffff));
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        };
        hue.setOnSeekBarChangeListener(sliders);
        shade.setOnSeekBarChangeListener(sliders);

        SeekBar brightness = card.findViewById(R.id.brightness);
        brightness.setProgress(Math.round((day ? draft.dayBrightness : draft.nightBrightness) / 5f) - 1);
        brightness.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                int value = (progress + 1) * 5;
                if (day) draft.dayBrightness = value; else draft.nightBrightness = value;
                ((TextView) card.findViewById(R.id.brightness_value)).setText(value + "%");
                if (fromUser) host.previewBrightness(value / 100f);
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { host.previewBrightness((bar.getProgress() + 1) * 5 / 100f); }
            @Override public void onStopTrackingTouch(SeekBar bar) { host.previewBrightness(-1); }
        });
        ((TextView) card.findViewById(R.id.brightness_value)).setText((day ? draft.dayBrightness : draft.nightBrightness) + "%");

        paintPeriod(card, day);
    }

    private void setColor(View card, boolean day, String hex) {
        if (day) draft.dayColor = hex; else draft.nightColor = hex;
        paintPeriod(card, day);
    }

    /** Repaints a period card in its color, with ink that stays readable on it. */
    private void paintPeriod(View card, boolean day) {
        String hex = day ? draft.dayColor : draft.nightColor;
        int color = Color.parseColor(hex);
        boolean light = ScreenView.isLight(color);
        int ink = light ? Color.argb(230, 15, 23, 42) : Color.WHITE;
        int wash = light ? Color.argb(26, 0, 0, 0) : Color.argb(40, 255, 255, 255);

        GradientDrawable background = rounded(color, 20);
        background.setStroke(dp(1), Color.argb(40, 255, 255, 255));
        card.setBackground(background);

        TextView name = card.findViewById(R.id.period_name);
        name.setTextColor(ink);
        name.setAlpha(0.8f);
        TextView start = card.findViewById(R.id.start_time);
        start.setText(formatTime(day ? draft.dayStart : draft.nightStart) + "  ▾");
        start.setTextColor(ink);
        start.setBackground(rounded(wash, 999));

        EditText label = card.findViewById(R.id.label);
        label.setTextColor(ink);
        label.setHintTextColor(Color.argb(140, Color.red(ink), Color.green(ink), Color.blue(ink)));
        label.setBackground(rounded(wash, 14));

        LinearLayout presets = card.findViewById(R.id.presets);
        for (int row = 0; row < presets.getChildCount(); row++) {
            LinearLayout line = (LinearLayout) presets.getChildAt(row);
            for (int col = 0; col < line.getChildCount(); col++) {
                View swatch = ((FrameLayout) line.getChildAt(col)).getChildAt(0);
                String tag = (String) swatch.getTag();
                GradientDrawable shape;
                boolean selected;
                if (tag.equals("custom")) {
                    shape = new GradientDrawable();
                    shape.setShape(GradientDrawable.OVAL);
                    shape.setGradientType(GradientDrawable.SWEEP_GRADIENT);
                    shape.setColors(new int[] {Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN, Color.BLUE, Color.MAGENTA, Color.RED});
                    selected = !isPreset(hex);
                } else {
                    shape = oval(Color.parseColor(tag));
                    selected = tag.equalsIgnoreCase(hex);
                }
                if (selected) {
                    // A ring with a gap around the chosen color, so it reads even on its own background.
                    GradientDrawable ring = oval(Color.TRANSPARENT);
                    ring.setStroke(dp(3), ink);
                    LayerDrawable layered = new LayerDrawable(new Drawable[] {ring, shape});
                    layered.setLayerInset(1, dp(6), dp(6), dp(6), dp(6));
                    swatch.setBackground(layered);
                } else {
                    shape.setStroke(dp(1), Color.argb(60, Color.red(ink), Color.green(ink), Color.blue(ink)));
                    swatch.setBackground(shape);
                }
            }
        }

        ImageButton back = card.findViewById(R.id.custom_back);
        back.setColorFilter(ink);
        back.setBackground(oval(wash));
        GradientDrawable current = oval(color);
        current.setStroke(dp(2), ink);
        card.findViewById(R.id.custom_swatch).setBackground(current);

        ColorStateList inkTint = ColorStateList.valueOf(ink);
        for (int id : new int[] {R.id.hue, R.id.shade, R.id.brightness}) {
            SeekBar bar = card.findViewById(id);
            bar.setThumbTintList(inkTint);
            if (id == R.id.brightness) {
                bar.setProgressTintList(inkTint);
                bar.setProgressBackgroundTintList(ColorStateList.valueOf(Color.argb(80, Color.red(ink), Color.green(ink), Color.blue(ink))));
            }
        }
        ((ImageView) card.findViewById(R.id.sun)).setColorFilter(ink);
        ((TextView) card.findViewById(R.id.brightness_value)).setTextColor(ink);
    }

    private void showCustom(View card, boolean day, boolean custom) {
        card.findViewById(R.id.presets).setVisibility(custom ? View.GONE : View.VISIBLE);
        card.findViewById(R.id.custom).setVisibility(custom ? View.VISIBLE : View.GONE);
        if (!custom) return;
        float[] hsv = new float[3];
        Color.colorToHSV(Color.parseColor(day ? draft.dayColor : draft.nightColor), hsv);
        ((SeekBar) card.findViewById(R.id.hue)).setProgress(Math.round(hsv[0]));
        // Shade runs dark (0) → full color (50) → pale (100).
        int shade = hsv[1] >= 0.999f ? Math.round(hsv[2] * 50) : Math.round(50 + (1 - hsv[1]) * 50);
        ((SeekBar) card.findViewById(R.id.shade)).setProgress(shade);
        updateShadeTrack(card);
    }

    private void updateShadeTrack(View card) {
        float hue = ((SeekBar) card.findViewById(R.id.hue)).getProgress();
        ((SeekBar) card.findViewById(R.id.shade)).setProgressDrawable(track(new int[] {Color.BLACK, Color.HSVToColor(new float[] {hue, 1f, 1f}), Color.WHITE}));
    }

    private int colorFromSliders(View card) {
        float hue = ((SeekBar) card.findViewById(R.id.hue)).getProgress();
        int shade = ((SeekBar) card.findViewById(R.id.shade)).getProgress();
        float saturation = shade <= 50 ? 1f : 1f - (shade - 50) / 50f;
        float value = shade <= 50 ? Math.max(0.04f, shade / 50f) : 1f;
        return Color.HSVToColor(new float[] {hue, saturation, value});
    }

    private void pickTime(View card, boolean day) {
        int minutes = Settings.minutes(day ? draft.dayStart : draft.nightStart);
        new TimePickerDialog(activity, R.style.Theme_ColorClock_Dialog, (picker, hour, minute) -> {
            String value = String.format(Locale.US, "%02d:%02d", hour, minute);
            if (day) draft.dayStart = value; else draft.nightStart = value;
            paintPeriod(card, day);
        }, minutes / 60, minutes % 60, DateFormat.is24HourFormat(activity)).show();
    }

    // --- Display ---

    private void bindDisplay() {
        view.findViewById(R.id.tile_clock).setOnClickListener(v -> { draft.showTime = !draft.showTime; tap(v); refreshDisplay(); });
        view.findViewById(R.id.tile_status).setOnClickListener(v -> { draft.showStatus = !draft.showStatus; tap(v); refreshDisplay(); });
    }

    private void refreshDisplay() {
        int day = Color.parseColor(draft.dayColor);
        int night = Color.parseColor(draft.nightColor);
        View miniClock = view.findViewById(R.id.mini_clock);
        miniClock.setBackground(rounded(day, 14));
        TextView clock = view.findViewById(R.id.mini_clock_text);
        clock.setText(DateFormat.getTimeFormat(activity).format(Calendar.getInstance().getTime()));
        clock.setTextColor(ScreenView.isLight(day) ? Color.argb(70, 0, 0, 0) : Color.argb(110, 255, 255, 255));
        clock.setAlpha(draft.showTime ? 1f : 0.25f);

        View miniStatus = view.findViewById(R.id.mini_status);
        miniStatus.setBackground(rounded(night, 14));
        TextView status = view.findViewById(R.id.mini_status_text);
        status.setText("Night · changes " + formatTime(draft.dayStart));
        status.setTextColor(ScreenView.isLight(night) ? Color.BLACK : Color.WHITE);
        status.setBackground(rounded(ScreenView.isLight(night) ? Color.argb(28, 0, 0, 0) : Color.argb(64, 0, 0, 0), 999));
        status.setAlpha(draft.showStatus ? 1f : 0.25f);

        paintToggle(view.findViewById(R.id.tile_clock), view.findViewById(R.id.check_clock), draft.showTime);
        paintToggle(view.findViewById(R.id.tile_status), view.findViewById(R.id.check_status), draft.showStatus);
    }

    // --- Child lock ---

    private void bindLock() {
        view.findViewById(R.id.tile_lock).setOnClickListener(v -> { childLock = !childLock; tap(v); refreshLock(); });
        ((TextView) view.findViewById(R.id.sample_words)).setText(UnlockChallenge.spell("5280"));
        refreshLock();
    }

    private void refreshLock() {
        paintToggle(view.findViewById(R.id.tile_lock), view.findViewById(R.id.check_lock), childLock);
        ((ImageView) view.findViewById(R.id.lock_icon)).setColorFilter(activity.getColor(childLock ? R.color.accent : R.color.text_muted));
        int day = Color.parseColor(draft.dayColor);
        view.findViewById(R.id.mini_lock).setBackground(rounded(day, 14));
        view.findViewById(R.id.mini_lock).setAlpha(childLock ? 1f : 0.35f);
        GradientDrawable ring = oval(Color.argb(110, 0, 0, 0));
        ring.setStroke(dp(3), Color.WHITE);
        view.findViewById(R.id.mini_ring).setBackground(ring);
        ((TextView) view.findViewById(R.id.lock_summary)).setText(childLock
                ? "On. To open settings, hold the top-right corner for 3 seconds, then type the code shown in words."
                : "Off. Anyone can tap the screen to open settings.");
    }

    // --- Device ---

    private void bindDevice() {
        view.findViewById(R.id.check_update).setOnClickListener(v -> { host.checkForUpdate(); toast("Checking for updates…"); });
        Button release = view.findViewById(R.id.release_owner);
        release.setVisibility(Kiosk.isDeviceOwner(activity) ? View.VISIBLE : View.INVISIBLE);
        release.setOnClickListener(v -> toast("Press and hold to release"));
        release.setOnLongClickListener(v -> { host.releaseDeviceOwner(); return true; });
    }

    // --- Done ---

    private void done(View button) {
        button.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
        Settings saved = draft;
        boolean lock = childLock;
        close();
        host.save(saved, lock);
    }

    // --- helpers ---

    private void paintToggle(View tile, ImageView check, boolean on) {
        tile.setSelected(on);
        if (on) {
            check.setBackgroundResource(R.drawable.bg_check);
            check.setImageResource(R.drawable.ic_check);
        } else {
            GradientDrawable ring = oval(Color.TRANSPARENT);
            ring.setStroke(dp(2), activity.getColor(R.color.card_line));
            check.setBackground(ring);
            check.setImageDrawable(null);
        }
    }

    private void tap(View v) { v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); }

    private void toast(String message) { Toast.makeText(activity, message, Toast.LENGTH_SHORT).show(); }

    private boolean isPreset(String hex) {
        for (String preset : PALETTE) if (preset.equalsIgnoreCase(hex)) return true;
        return false;
    }

    private String formatTime(String time) {
        int minutes = Settings.minutes(time);
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.HOUR_OF_DAY, minutes / 60);
        calendar.set(Calendar.MINUTE, minutes % 60);
        return DateFormat.getTimeFormat(activity).format(calendar.getTime());
    }

    private Drawable track(int[] colors) {
        GradientDrawable track = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, colors);
        track.setCornerRadius(dp(6));
        track.setSize(0, dp(12));
        return track;
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radiusDp));
        return shape;
    }

    private GradientDrawable oval(int color) {
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.OVAL);
        shape.setColor(color);
        return shape;
    }

    private void hideKeyboard(View any) {
        activity.getSystemService(InputMethodManager.class).hideSoftInputFromWindow(any.getWindowToken(), 0);
    }

    private int dp(int value) { return Math.round(value * density); }
}
