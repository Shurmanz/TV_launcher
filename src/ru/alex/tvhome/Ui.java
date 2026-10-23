package ru.alex.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.TextView;

/** Общие мелочи оформления. */
final class Ui {
    private Ui() { }

    static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    static TextView text(Context c, float sp, int weight, int color) {
        TextView t = new TextView(c);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        Fonts.apply(t, weight);
        t.setTextColor(color);
        t.setShadowLayer(dp(c, 6), 0, dp(c, 1), 0x80000000);
        t.setIncludeFontPadding(false);
        return t;
    }

    static GradientDrawable round(int color, float radius, int strokeW, int strokeColor) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        if (strokeW > 0) g.setStroke(strokeW, strokeColor);
        return g;
    }

    /** Матовая «таблетка»: в фокусе — светлая, иначе — полупрозрачная. */
    static void pill(View v, boolean focused) {
        Context c = v.getContext();
        float r = dp(c, 100);
        v.setBackground(focused
                ? round(0xF2FFFFFF, r, 0, 0)
                : round(0x33FFFFFF, r, dp(c, 1), 0x33FFFFFF));
    }

    static void focusScale(View v, boolean has, float s) {
        v.animate().scaleX(has ? s : 1f).scaleY(has ? s : 1f).translationZ(has ? dp(v.getContext(), 16) : 0)
                .setInterpolator(new DecelerateInterpolator()).setDuration(170).start();
    }

    /** Характерный цвет иконки/баннера: среднее по насыщенным пикселям, слегка усиленное. */
    static int accent(Drawable d) {
        if (d == null) return 0xFF5A8CFF;
        try {
            Bitmap b = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(b);
            d.setBounds(0, 0, 24, 24);
            d.draw(cv);
            double r = 0, g = 0, bl = 0, wsum = 0;
            float[] hsv = new float[3];
            for (int y = 0; y < 24; y++) for (int x = 0; x < 24; x++) {
                int p = b.getPixel(x, y);
                int a = Color.alpha(p);
                if (a < 128) continue;
                Color.colorToHSV(p, hsv);
                double w = hsv[1] * hsv[1] * hsv[2] + 0.02;
                r += Color.red(p) * w; g += Color.green(p) * w; bl += Color.blue(p) * w; wsum += w;
            }
            b.recycle();
            if (wsum <= 0) return 0xFF5A8CFF;
            int c = Color.rgb((int) (r / wsum), (int) (g / wsum), (int) (bl / wsum));
            Color.colorToHSV(c, hsv);
            if (hsv[1] < 0.12f) return 0xFF8A9BB5; // почти серое — спокойный холодный оттенок
            hsv[1] = Math.min(1f, hsv[1] * 1.25f + 0.1f);
            hsv[2] = Math.max(0.75f, hsv[2]);
            return Color.HSVToColor(hsv);
        } catch (Throwable t) {
            return 0xFF5A8CFF;
        }
    }
}
