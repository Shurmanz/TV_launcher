package ru.alex.tvhome;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * Мягкое свечение цвета выбранного приложения под его плиткой.
 * Плавно «перетекает» за фокусом и вспыхивает волной при нажатии.
 */
final class GlowView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ArgbEvaluator argb = new ArgbEvaluator();
    private float x, y, radius, alpha = 0f;
    private int color = 0xFF5A8CFF;
    private ValueAnimator move, pulse;
    private float level = 0.55f;

    GlowView(Context c) {
        super(c);
        radius = Ui.dp(c, 260);
    }

    /** Общая яркость (ночью тусклее). */
    void setLevel(float l) { level = l; invalidate(); }

    void moveTo(float tx, float ty, int tc) {
        if (move != null) move.cancel();
        final float sx = x, sy = y, sa = alpha;
        final int sc = color;
        final boolean first = alpha == 0f;
        move = ValueAnimator.ofFloat(0f, 1f);
        move.setDuration(first ? 1 : 420);
        move.setInterpolator(new DecelerateInterpolator(1.6f));
        move.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            x = first ? tx : sx + (tx - sx) * f;
            y = first ? ty : sy + (ty - sy) * f;
            color = (int) argb.evaluate(f, sc, tc);
            alpha = sa + (1f - sa) * f;
            invalidate();
        });
        move.start();
    }

    void hide() {
        if (move != null) move.cancel();
        ValueAnimator a = ValueAnimator.ofFloat(alpha, 0f);
        a.setDuration(250);
        a.addUpdateListener(v -> { alpha = (float) v.getAnimatedValue(); invalidate(); });
        a.start();
        move = a;
    }

    /** Волна света при нажатии. */
    void pulse() {
        if (pulse != null) pulse.cancel();
        final float base = Ui.dp(getContext(), 260);
        pulse = ValueAnimator.ofFloat(0f, 1f);
        pulse.setDuration(420);
        pulse.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            float k = (float) Math.sin(Math.PI * f);
            radius = base * (1f + 0.6f * f);
            paint.setAlpha(255);
            boost = 1f + 0.8f * k;
            invalidate();
        });
        pulse.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) { radius = base; boost = 1f; invalidate(); }
        });
        pulse.start();
    }

    private float boost = 1f;

    @Override
    protected void onDraw(Canvas c) {
        float a = Math.min(1f, alpha * level * boost);
        if (a <= 0.01f) return;
        int core = Color.argb(Math.round(255 * a), Color.red(color), Color.green(color), Color.blue(color));
        int mid = Color.argb(Math.round(110 * a), Color.red(color), Color.green(color), Color.blue(color));
        paint.setShader(new RadialGradient(x, y, radius, new int[]{core, mid, 0}, new float[]{0f, 0.45f, 1f},
                Shader.TileMode.CLAMP));
        c.drawCircle(x, y, radius, paint);
    }
}
