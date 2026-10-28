package com.giblicious.colorclock;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.security.SecureRandom;

/**
 * The child-lock gate: a fresh 4-digit code spelled out in words ("Five Two Eight Zero") that a
 * grown-up types on the keypad. Nothing to remember, but a child who can't read can't pass it.
 * Laid out for landscape: code on the left, keypad on the right.
 */
final class UnlockChallenge extends FrameLayout {
    interface Listener {
        void onUnlocked();
        void onCancel();
    }

    static final String[] WORDS = {"Zero", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine"};
    private static final int LENGTH = 4;
    private static final int MAX_ATTEMPTS = 3;
    private static final long LOCKOUT_MS = 30_000;
    private static long lockedUntil;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SecureRandom random = new SecureRandom();
    private final Listener listener;
    private final float density;
    private final TextView words;
    private final TextView error;
    private final View[] dots = new View[LENGTH];
    private final StringBuilder entry = new StringBuilder();
    private String code;
    private int failedAttempts;

    UnlockChallenge(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        density = getResources().getDisplayMetrics().density;
        setBackgroundColor(getResources().getColor(R.color.screen, null));
        setClickable(true);

        LinearLayout columns = new LinearLayout(context);
        addView(columns, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        LinearLayout prompt = new LinearLayout(context);
        prompt.setOrientation(LinearLayout.VERTICAL);
        prompt.setGravity(Gravity.CENTER);
        prompt.setPadding(dp(24), 0, dp(12), 0);
        columns.addView(prompt, new LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1.1f));

        ImageView lock = new ImageView(context);
        lock.setImageResource(R.drawable.ic_lock);
        lock.setColorFilter(getResources().getColor(R.color.text_muted, null));
        prompt.addView(lock, new LinearLayout.LayoutParams(dp(30), dp(30)));

        prompt.addView(text(context, "Type this code", 16, R.color.text_muted), margins(0, 10));

        words = text(context, "", 30, R.color.text);
        words.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        prompt.addView(words, margins(0, 6));

        LinearLayout dotRow = new LinearLayout(context);
        for (int i = 0; i < LENGTH; i++) {
            dots[i] = new View(context);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(16), dp(16));
            params.setMargins(dp(9), 0, dp(9), 0);
            dotRow.addView(dots[i], params);
        }
        prompt.addView(dotRow, margins(0, 22));

        error = text(context, "", 14, R.color.danger);
        prompt.addView(error, margins(0, 14));

        Button cancel = new Button(context, null, 0, R.style.Settings_Button);
        cancel.setText("Cancel");
        cancel.setBackgroundResource(R.drawable.bg_button_ghost);
        cancel.setTextColor(getResources().getColor(R.color.text_muted, null));
        cancel.setOnClickListener(v -> listener.onCancel());
        prompt.addView(cancel, margins(0, 6));

        GridLayout keys = new GridLayout(context);
        keys.setColumnCount(3);
        String[] labels = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "", "0", "⌫"};
        for (String label : labels) keys.addView(key(context, label));
        FrameLayout keypadHolder = new FrameLayout(context);
        keypadHolder.addView(keys, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        columns.addView(keypadHolder, new LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 0.9f));

        newCode();
        showLockout();
    }

    /** "5280" → "Five Two Eight Zero". */
    static String spell(String digits) {
        StringBuilder out = new StringBuilder();
        for (char c : digits.toCharArray()) {
            if (out.length() > 0) out.append(' ');
            out.append(WORDS[c - '0']);
        }
        return out.toString();
    }

    private void newCode() {
        StringBuilder next = new StringBuilder();
        for (int i = 0; i < LENGTH; i++) next.append(random.nextInt(10));
        code = next.toString();
        words.setText(spell(code));
        entry.setLength(0);
        updateDots();
    }

    private View key(Context context, String label) {
        GridLayout.LayoutParams params = new GridLayout.LayoutParams();
        params.width = dp(66);
        params.height = dp(66);
        params.setMargins(dp(10), dp(6), dp(10), dp(6));
        if (label.isEmpty()) {
            View spacer = new View(context);
            spacer.setLayoutParams(params);
            return spacer;
        }
        if (label.equals("⌫")) {
            ImageButton back = new ImageButton(context);
            back.setImageResource(R.drawable.ic_backspace);
            back.setColorFilter(getResources().getColor(R.color.text_muted, null));
            back.setBackgroundResource(R.drawable.bg_button_ghost);
            back.setContentDescription("Delete");
            back.setOnClickListener(v -> {
                if (entry.length() > 0) entry.setLength(entry.length() - 1);
                updateDots();
            });
            back.setLayoutParams(params);
            return back;
        }
        TextView key = new TextView(context);
        key.setText(label);
        key.setTextSize(26);
        key.setTextColor(Color.WHITE);
        key.setGravity(Gravity.CENTER);
        key.setBackgroundResource(R.drawable.bg_key);
        key.setOnClickListener(v -> press(label, v));
        key.setLayoutParams(params);
        return key;
    }

    private void press(String digit, View key) {
        if (SystemClock.elapsedRealtime() < lockedUntil || entry.length() >= LENGTH) return;
        key.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        entry.append(digit);
        error.setText("");
        updateDots();
        if (entry.length() == LENGTH) handler.postDelayed(this::check, 120);
    }

    private void check() {
        if (entry.toString().equals(code)) {
            listener.onUnlocked();
            return;
        }
        // A wrong guess always gets a new code, so random tapping can't creep up on it.
        if (++failedAttempts >= MAX_ATTEMPTS) {
            failedAttempts = 0;
            lockedUntil = SystemClock.elapsedRealtime() + LOCKOUT_MS;
            newCode();
            showLockout();
        } else {
            newCode();
            fail("Not quite · here's a new code");
        }
    }

    private void fail(String message) {
        error.setText(message);
        animate().translationX(dp(12)).setDuration(50).withEndAction(() ->
                animate().translationX(-dp(10)).setDuration(60).withEndAction(() ->
                        animate().translationX(0).setDuration(60)));
        performHapticFeedback(HapticFeedbackConstants.REJECT);
    }

    private void showLockout() {
        long remaining = lockedUntil - SystemClock.elapsedRealtime();
        if (remaining <= 0) {
            if (error.getText().toString().startsWith("Too many")) error.setText("");
            return;
        }
        error.setText("Too many tries · wait " + (remaining / 1000 + 1) + "s");
        handler.postDelayed(this::showLockout, 500);
    }

    private void updateDots() {
        for (int i = 0; i < LENGTH; i++) {
            GradientDrawable dot = new GradientDrawable();
            dot.setShape(GradientDrawable.OVAL);
            if (i < entry.length()) {
                dot.setColor(Color.WHITE);
            } else {
                dot.setColor(Color.TRANSPARENT);
                dot.setStroke(dp(2), Color.argb(120, 255, 255, 255));
            }
            dots[i].setBackground(dot);
        }
    }

    private TextView text(Context context, String value, int sp, int color) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(getResources().getColor(color, null));
        view.setGravity(Gravity.CENTER);
        return view;
    }

    private LinearLayout.LayoutParams margins(int left, int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        params.setMargins(dp(left), dp(top), 0, 0);
        return params;
    }

    private int dp(int value) { return Math.round(value * density); }

    @Override
    protected void onDetachedFromWindow() {
        handler.removeCallbacksAndMessages(null);
        super.onDetachedFromWindow();
    }
}
