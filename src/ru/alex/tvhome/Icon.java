package ru.alex.tvhome;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/** Простые значки, нарисованные кодом (без ресурсов): вход, Wi‑Fi, Ethernet, шестерёнка, плюс. */
final class Icon extends Drawable {
    static final int INPUT = 0, WIFI = 1, ETHERNET = 2, GEAR = 3, PLUS = 4, NO_NET = 5, AV = 6, TV = 7;

    private final int kind;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean dot;

    Icon(int kind, int color) {
        this.kind = kind;
        p.setColor(color);
    }

    void setColor(int c) { p.setColor(c); invalidateSelf(); }
    void setDot(boolean d) { dot = d; invalidateSelf(); }

    @Override
    public void draw(Canvas c) {
        Rect b = getBounds();
        float s = Math.min(b.width(), b.height());
        float cx = b.exactCenterX(), cy = b.exactCenterY();
        float sw = s * 0.11f;
        p.setStrokeWidth(sw);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        switch (kind) {
            case INPUT: { // штекер HDMI: трапеция с контактами
                p.setStyle(Paint.Style.FILL);
                Path path = new Path();
                path.moveTo(cx - s * 0.32f, cy - s * 0.18f);
                path.lineTo(cx + s * 0.32f, cy - s * 0.18f);
                path.lineTo(cx + s * 0.32f, cy + s * 0.02f);
                path.lineTo(cx + s * 0.2f, cy + s * 0.16f);
                path.lineTo(cx - s * 0.2f, cy + s * 0.16f);
                path.lineTo(cx - s * 0.32f, cy + s * 0.02f);
                path.close();
                c.drawPath(path, p);
                c.drawRect(cx - s * 0.1f, cy + s * 0.16f, cx + s * 0.1f, cy + s * 0.42f, p);
                c.drawRect(cx - s * 0.22f, cy - s * 0.42f, cx + s * 0.22f, cy - s * 0.2f, p);
                break;
            }
            case WIFI:
            case NO_NET: {
                p.setStyle(Paint.Style.STROKE);
                float by = cy + s * 0.3f;
                for (int i = 1; i <= 3; i++) {
                    float r = s * 0.17f * i + s * 0.02f;
                    RectF o = new RectF(cx - r, by - r, cx + r, by + r);
                    c.drawArc(o, 225, 90, false, p);
                }
                p.setStyle(Paint.Style.FILL);
                c.drawCircle(cx, by - s * 0.02f, sw * 0.9f, p);
                if (kind == NO_NET) {
                    p.setStyle(Paint.Style.STROKE);
                    c.drawLine(cx - s * 0.4f, cy - s * 0.4f, cx + s * 0.4f, cy + s * 0.4f, p);
                }
                break;
            }
            case ETHERNET: { // три квадрата, соединённые линиями
                p.setStyle(Paint.Style.FILL);
                float q = s * 0.2f;
                c.drawRoundRect(new RectF(cx - q / 2, cy - s * 0.42f, cx + q / 2, cy - s * 0.42f + q), q * 0.2f, q * 0.2f, p);
                c.drawRoundRect(new RectF(cx - s * 0.42f, cy + s * 0.2f, cx - s * 0.42f + q, cy + s * 0.2f + q), q * 0.2f, q * 0.2f, p);
                c.drawRoundRect(new RectF(cx + s * 0.42f - q, cy + s * 0.2f, cx + s * 0.42f, cy + s * 0.2f + q), q * 0.2f, q * 0.2f, p);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(sw * 0.7f);
                c.drawLine(cx, cy - s * 0.22f, cx, cy, p);
                c.drawLine(cx - s * 0.32f, cy, cx + s * 0.32f, cy, p);
                c.drawLine(cx - s * 0.32f, cy, cx - s * 0.32f, cy + s * 0.2f, p);
                c.drawLine(cx + s * 0.32f, cy, cx + s * 0.32f, cy + s * 0.2f, p);
                break;
            }
            case GEAR: {
                p.setStyle(Paint.Style.FILL);
                c.save();
                for (int i = 0; i < 8; i++) {
                    c.drawRoundRect(new RectF(cx - s * 0.08f, cy - s * 0.44f, cx + s * 0.08f, cy - s * 0.26f), s * 0.03f, s * 0.03f, p);
                    c.rotate(45, cx, cy);
                }
                c.restore();
                // кольцо: «дырка» получается сама, без подбора цвета фона
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(s * 0.14f);
                c.drawCircle(cx, cy, s * 0.21f, p);
                break;
            }
            case AV: { // три «тюльпана»: стойки с колпачками
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(sw * 0.9f);
                for (int i = -1; i <= 1; i++) {
                    float lx = cx + i * s * 0.28f;
                    c.drawLine(lx, cy - s * 0.38f, lx, cy + s * 0.38f, p);
                }
                p.setStyle(Paint.Style.FILL);
                float[] at = {-0.05f, 0.12f, -0.12f};
                for (int i = -1; i <= 1; i++) {
                    float lx = cx + i * s * 0.28f, ly = cy + at[i + 1] * s;
                    c.drawRoundRect(new RectF(lx - s * 0.11f, ly - s * 0.11f, lx + s * 0.11f, ly + s * 0.11f), s * 0.04f, s * 0.04f, p);
                }
                break;
            }
            case TV: { // экран с «кнопкой воспроизведения» и антенной
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(sw * 0.85f);
                RectF scr = new RectF(cx - s * 0.42f, cy - s * 0.22f, cx + s * 0.42f, cy + s * 0.38f);
                c.drawRoundRect(scr, s * 0.07f, s * 0.07f, p);
                c.drawLine(cx - s * 0.16f, cy - s * 0.42f, cx, cy - s * 0.24f, p);
                c.drawLine(cx + s * 0.16f, cy - s * 0.42f, cx, cy - s * 0.24f, p);
                p.setStyle(Paint.Style.FILL);
                Path tri = new Path();
                tri.moveTo(cx - s * 0.08f, cy - s * 0.04f);
                tri.lineTo(cx + s * 0.12f, cy + s * 0.08f);
                tri.lineTo(cx - s * 0.08f, cy + s * 0.2f);
                tri.close();
                c.drawPath(tri, p);
                break;
            }
            case PLUS: {
                p.setStyle(Paint.Style.STROKE);
                c.drawLine(cx - s * 0.35f, cy, cx + s * 0.35f, cy, p);
                c.drawLine(cx, cy - s * 0.35f, cx, cy + s * 0.35f, p);
                break;
            }
        }
        if (dot) {
            Paint dp = new Paint(Paint.ANTI_ALIAS_FLAG);
            dp.setColor(0xFFFF9F0A);
            c.drawCircle(b.right - s * 0.08f, b.top + s * 0.08f, s * 0.16f, dp);
        }
    }

    @Override public void setAlpha(int a) { p.setAlpha(a); }
    @Override public void setColorFilter(ColorFilter cf) { p.setColorFilter(cf); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
