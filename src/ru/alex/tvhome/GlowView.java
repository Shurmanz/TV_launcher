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
 * Свечение под выбранной плиткой — компактное и насыщенное, как разноцветный ореол на загрузке Google TV:
 * овал по форме плитки в цвете приложения плюс второй, сдвинутый по оттенку, — получается живой градиент.
 * Плавно «перетекает» за фокусом и вспыхивает при нажатии.
 */
final class GlowView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ArgbEvaluator argb = new ArgbEvaluator();
    private float x, y, alpha = 0f;
    private float rx, ry;          // полуоси овала (по размеру плитки)
    private float grow = 1f;       // волна при нажатии
    private float boost = 1f;
    private int color = 0xFF5A8CFF;
    private ValueAnimator move, pulse;
    private float level = 0.9f;

    GlowView(Context c) {
        super(c);
        rx = Ui.dp(c, 120);
        ry = Ui.dp(c, 72);
    }

    /** Размер ореола: чуть больше плитки. */
    void setTile(int w, int h) {
        rx = w * 0.5f + Ui.dp(getContext(), 46);
        ry = h * 0.5f + Ui.dp(getContext(), 34);
        invalidate();
    }

    /** Общая яркость (ночью тусклее). */
    void setLevel(float l) { level = l; invalidate(); }

    void moveTo(float tx, float ty, int tc) {
        if (move != null) move.cancel();
        final float sx = x, sy = y, sa = alpha;
        final int sc = color;
        final int target = vivid(tc);
        final boolean first = alpha == 0f;
        move = ValueAnimator.ofFloat(0f, 1f);
        move.setDuration(first ? 1 : 360);
        move.setInterpolator(new DecelerateInterpolator(1.6f));
        move.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            x = first ? tx : sx + (tx - sx) * f;
            y = first ? ty : sy + (ty - sy) * f;
            color = first ? target : (int) argb.evaluate(f, sc, target);
            alpha = sa + (1f - sa) * f;
            invalidate();
        });
        move.start();
    }

    void hide() {
        if (move != null) move.cancel();
        ValueAnimator a = ValueAnimator.ofFloat(alpha, 0f);
        a.setDuration(220);
        a.addUpdateListener(v -> { alpha = (float) v.getAnimatedValue(); invalidate(); });
        a.start();
        move = a;
    }

    /** Вспышка при нажатии. */
    void pulse() {
        if (pulse != null) pulse.cancel();
        pulse = ValueAnimator.ofFloat(0f, 1f);
        pulse.setDuration(380);
        pulse.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            float k = (float) Math.sin(Math.PI * f);
            grow = 1f + 0.35f * k;
            boost = 1f + 0.5f * k;
            invalidate();
        });
        pulse.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) { grow = 1f; boost = 1f; invalidate(); }
        });
        pulse.start();
    }

    /** Насыщенный и яркий вариант цвета: блеклые иконки тоже дают сочное свечение. */
    private static int vivid(int c) {
        float[] h = new float[3];
        Color.colorToHSV(c, h);
        if (h[1] < 0.15f) return 0xFF8FA8FF; // серые/белые — холодный синий
        h[1] = Math.min(1f, h[1] * 1.3f + 0.2f);
        h[2] = 1f;
        return Color.HSVToColor(h);
    }

    private static int shift(int c, float deg) {
        float[] h = new float[3];
        Color.colorToHSV(c, h);
        h[0] = (h[0] + deg + 360f) % 360f;
        return Color.HSVToColor(h);
    }

    private void blob(Canvas c, float cx, float cy, float a, int col, float sx, float sy) {
        int r = Color.red(col), g = Color.green(col), b = Color.blue(col);
        int core = Color.argb(Math.round(255 * a), r, g, b);
        int mid = Color.argb(Math.round(150 * a), r, g, b);
        int edge = Color.argb(Math.round(40 * a), r, g, b);
        float rad = rx * grow * sx;
        c.save();
        c.scale(1f, ry * sy / (rx * sx), cx, cy); // круг → овал по форме плитки
        paint.setShader(new RadialGradient(cx, cy, rad, new int[]{core, core, mid, edge, 0},
                new float[]{0f, 0.42f, 0.62f, 0.82f, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, rad, paint);
        c.restore();
    }

    @Override
    protected void onDraw(Canvas c) {
        float a = Math.min(1f, alpha * level * boost);
        if (a <= 0.01f) return;
        // второй оттенок слева сверху, основной цвет по центру — как переливающийся ореол
        blob(c, x - rx * 0.28f, y - ry * 0.18f, a * 0.75f, shift(color, -38f), 0.9f, 0.95f);
        blob(c, x + rx * 0.22f, y + ry * 0.14f, a * 0.65f, shift(color, 32f), 0.85f, 0.9f);
        blob(c, x, y, a, color, 1f, 1f);
    }
}
