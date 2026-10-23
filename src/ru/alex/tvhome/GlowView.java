package ru.alex.tvhome;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * Свечение по контуру выбранной плитки — один цвет из иконки приложения.
 * Рисуется размытый скруглённый прямоугольник под плиткой: сама плитка закрывает середину,
 * видно только ровное сияние вокруг краёв. Плавно переезжает за фокусом, вспыхивает при нажатии.
 */
final class GlowView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ArgbEvaluator argb = new ArgbEvaluator();
    private final RectF rect = new RectF();
    private float x, y, alpha = 0f;
    private float halfW, halfH, corner;
    private float spread;          // насколько сияние выходит за край
    private float grow = 1f, boost = 1f;
    private int color = 0xFF5A8CFF;
    private ValueAnimator move, pulse;
    private float level = 0.9f;
    private BlurMaskFilter blur;
    private float blurFor = -1;

    GlowView(Context c) {
        super(c);
        spread = Ui.dp(c, 18);
        halfW = Ui.dp(c, 74);
        halfH = Ui.dp(c, 42);
        corner = Ui.dp(c, 14);
        paint.setStyle(Paint.Style.FILL);
        setLayerType(LAYER_TYPE_HARDWARE, null);
    }

    /** Размер плитки в фокусе (с учётом увеличения) и радиус её углов. */
    void setTile(int w, int h, float radius, float focusScale) {
        halfW = w * focusScale / 2f;
        halfH = h * focusScale / 2f;
        corner = radius * focusScale;
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
        move.setDuration(first ? 1 : 300);
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
        a.setDuration(200);
        a.addUpdateListener(v -> { alpha = (float) v.getAnimatedValue(); invalidate(); });
        a.start();
        move = a;
    }

    /** Вспышка при нажатии: сияние ярче и шире. */
    void pulse() {
        if (pulse != null) pulse.cancel();
        pulse = ValueAnimator.ofFloat(0f, 1f);
        pulse.setDuration(360);
        pulse.addUpdateListener(a -> {
            float k = (float) Math.sin(Math.PI * (float) a.getAnimatedValue());
            grow = 1f + 0.8f * k;
            boost = 1f + 0.4f * k;
            invalidate();
        });
        pulse.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) { grow = 1f; boost = 1f; invalidate(); }
        });
        pulse.start();
    }

    /** Насыщенный яркий вариант цвета иконки (тот же оттенок). */
    private static int vivid(int c) {
        float[] h = new float[3];
        Color.colorToHSV(c, h);
        if (h[1] < 0.15f) return 0xFFB8C6FF; // серые/белые иконки — светлый холодный
        h[1] = Math.min(1f, h[1] * 1.25f + 0.15f);
        h[2] = 1f;
        return Color.HSVToColor(h);
    }

    @Override
    protected void onDraw(Canvas c) {
        float a = Math.min(1f, alpha * level * boost);
        if (a <= 0.01f) return;
        float s = spread * grow;
        if (blur == null || Math.abs(blurFor - s) > 0.5f) {
            blur = new BlurMaskFilter(s, BlurMaskFilter.Blur.NORMAL);
            blurFor = s;
        }
        paint.setMaskFilter(blur);
        paint.setColor(Color.argb(Math.round(255 * a), Color.red(color), Color.green(color), Color.blue(color)));
        float e = s * 0.35f; // чуть шире плитки, чтобы сияние начиналось прямо от края
        rect.set(x - halfW - e, y - halfH - e, x + halfW + e, y + halfH + e);
        c.drawRoundRect(rect, corner + e, corner + e, paint);
    }
}
