package com.giblicious.colorclock;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.animation.LinearInterpolator;

/** A ring that fills while the child-lock corner is held, with a lock in the middle. */
final class HoldRing extends View {
    static final long HOLD_MS = 3_000;

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bounds = new RectF();
    private final Drawable lock;
    private ValueAnimator animator;
    private float progress;

    HoldRing(Context context) {
        super(context);
        float density = getResources().getDisplayMetrics().density;
        track.setStyle(Paint.Style.STROKE);
        track.setStrokeWidth(5 * density);
        track.setColor(Color.argb(70, 255, 255, 255));
        arc.setStyle(Paint.Style.STROKE);
        arc.setStrokeWidth(5 * density);
        arc.setStrokeCap(Paint.Cap.ROUND);
        arc.setColor(Color.WHITE);
        fill.setColor(Color.argb(110, 0, 0, 0));
        lock = context.getDrawable(R.drawable.ic_lock).mutate();
        setAlpha(0f);
    }

    void start(Runnable onComplete) {
        cancel();
        animate().alpha(1f).setDuration(150);
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(HOLD_MS);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(animation -> { progress = (float) animation.getAnimatedValue(); invalidate(); });
        animator.addListener(new android.animation.AnimatorListenerAdapter() {
            private boolean cancelled;
            @Override public void onAnimationCancel(android.animation.Animator animation) { cancelled = true; }
            @Override public void onAnimationEnd(android.animation.Animator animation) {
                if (cancelled) return;
                performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                animate().alpha(0f).setDuration(200);
                onComplete.run();
            }
        });
        animator.start();
    }

    void cancel() {
        if (animator != null) animator.cancel();
        animator = null;
        progress = 0;
        animate().alpha(0f).setDuration(150);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float inset = track.getStrokeWidth();
        bounds.set(inset, inset, getWidth() - inset, getHeight() - inset);
        canvas.drawOval(bounds, fill);
        canvas.drawOval(bounds, track);
        canvas.drawArc(bounds, -90, 360 * progress, false, arc);
        int size = getWidth() / 3;
        int left = (getWidth() - size) / 2;
        int top = (getHeight() - size) / 2;
        lock.setBounds(left, top, left + size, top + size);
        lock.draw(canvas);
    }
}
