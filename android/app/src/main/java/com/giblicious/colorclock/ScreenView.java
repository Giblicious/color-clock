package com.giblicious.colorclock;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * The full-screen color with an optional clock, period label, and status pill. Colors crossfade,
 * text ink adapts to light or dark backgrounds, and content drifts a few pixels each minute so a
 * static clock never burns into the panel.
 */
final class ScreenView extends View {
    private static final long FADE_MS = 1_200;

    private final Paint clockPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint statusPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF pill = new RectF();
    private final float density;
    private ValueAnimator fade;
    private int color = Color.BLACK;
    private int targetColor = Color.BLACK;
    private boolean hasColor;
    private String clock;
    private String label;
    private String status;
    private float shiftX;
    private float shiftY;

    ScreenView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        Typeface heavy = Typeface.create("sans-serif-black", Typeface.NORMAL);
        clockPaint.setTypeface(heavy);
        clockPaint.setTextAlign(Paint.Align.CENTER);
        clockPaint.setFontFeatureSettings("tnum");
        clockPaint.setLetterSpacing(-0.04f);
        labelPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        labelPaint.setTextAlign(Paint.Align.CENTER);
        statusPaint.setTextAlign(Paint.Align.CENTER);
        statusPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    }

    /** Updates what is shown; a new color fades in unless {@code instant} is set. */
    void show(int color, String clock, String label, String status, boolean instant) {
        this.clock = clock;
        this.label = label == null || label.isEmpty() ? null : label;
        this.status = status;
        if (!hasColor || instant) {
            if (fade != null) fade.cancel();
            this.color = this.targetColor = color;
            hasColor = true;
        } else if (color != targetColor) {
            targetColor = color;
            if (fade != null) fade.cancel();
            fade = ValueAnimator.ofObject(new ArgbEvaluator(), this.color, color);
            fade.setDuration(FADE_MS);
            fade.setInterpolator(new DecelerateInterpolator());
            fade.addUpdateListener(animation -> { this.color = (int) animation.getAnimatedValue(); invalidate(); });
            fade.start();
        }
        invalidate();
    }

    /** Moves the text by up to a few dp; called once a minute to protect against burn-in. */
    void shift(float dxDp, float dyDp) {
        shiftX = dxDp * density;
        shiftY = dyDp * density;
        invalidate();
    }

    static boolean isLight(int color) {
        double luminance = (0.2126 * Color.red(color) + 0.7152 * Color.green(color) + 0.0722 * Color.blue(color)) / 255;
        return luminance > 0.6;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawColor(color);
        boolean light = isLight(color);
        int ink = light ? Color.BLACK : Color.WHITE;
        clockPaint.setColor(ink);
        clockPaint.setAlpha(light ? 56 : 72);
        labelPaint.setColor(ink);
        labelPaint.setAlpha(light ? 150 : 200);
        statusPaint.setColor(light ? Color.BLACK : Color.WHITE);
        pillPaint.setColor(light ? Color.argb(28, 0, 0, 0) : Color.argb(64, 0, 0, 0));

        float width = getWidth();
        float height = getHeight();
        float centerX = width / 2 + shiftX;
        float centerY = height / 2 + shiftY;

        if (clock != null) {
            fitText(clockPaint, clock, Math.min(width * 0.26f, height * (label == null ? 0.42f : 0.34f)), width * 0.88f);
            // Digits sit on the baseline, so their cap height is the visual height of the clock.
            float clockCap = clockPaint.getTextSize() * 0.72f;
            float labelCap = 0;
            float gap = 0;
            if (label != null) {
                labelCap = fitText(labelPaint, label, clockPaint.getTextSize() * 0.26f, width * 0.8f) * 0.72f;
                gap = labelCap * 1.1f;
            }
            float clockBaseline = centerY - (clockCap + gap + labelCap) / 2 + clockCap;
            canvas.drawText(clock, centerX, clockBaseline, clockPaint);
            if (label != null) canvas.drawText(label, centerX, clockBaseline + gap + labelCap, labelPaint);
        } else if (label != null) {
            fitText(labelPaint, label, Math.min(width * 0.11f, height * 0.2f), width * 0.84f);
            labelPaint.setAlpha(light ? 120 : 170);
            Paint.FontMetrics metrics = labelPaint.getFontMetrics();
            canvas.drawText(label, centerX, centerY - (metrics.ascent + metrics.descent) / 2, labelPaint);
        }

        if (status != null) {
            statusPaint.setTextSize(15 * density);
            float textWidth = statusPaint.measureText(status);
            float baseline = height - 34 * density + shiftY / 2;
            pill.set(centerX - textWidth / 2 - 14 * density, baseline - 22 * density, centerX + textWidth / 2 + 14 * density, baseline + 10 * density);
            canvas.drawRoundRect(pill, pill.height() / 2, pill.height() / 2, pillPaint);
            canvas.drawText(status, centerX, baseline, statusPaint);
        }
    }

    /** Sets the paint to {@code size}, shrinking it so {@code text} fits in {@code maxWidth}. */
    private static float fitText(Paint paint, String text, float size, float maxWidth) {
        paint.setTextSize(size);
        float fit = maxWidth / Math.max(1, paint.measureText(text));
        if (fit < 1) paint.setTextSize(size * fit);
        return paint.getTextSize();
    }
}
