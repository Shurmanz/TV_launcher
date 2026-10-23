package ru.alex.tvhome;

import android.content.Context;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Часы, дата и погода. */
final class ClockView extends LinearLayout implements WallpaperView.Listener {
    private static final Locale RU = new Locale("ru");
    private final TextView time, date, weather;
    private final Handler ui = new Handler(Looper.getMainLooper());

    ClockView(Context ctx, float timeSp, int gravity) {
        super(ctx);
        setOrientation(VERTICAL);
        setGravity(gravity);
        time = text(timeSp, "sans-serif-light", 0xFFFFFFFF);
        date = text(timeSp * 0.28f, "sans-serif", 0xE6FFFFFF);
        weather = text(timeSp * 0.28f, "sans-serif", 0xCCFFFFFF);
        addView(time);
        addView(date);
        addView(weather);
    }

    private TextView text(float sp, String family, int color) {
        TextView t = new TextView(getContext());
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(Typeface.create(family, Typeface.NORMAL));
        t.setTextColor(color);
        float d = getResources().getDisplayMetrics().density;
        t.setShadowLayer(6 * d, 0, d, 0x99000000);
        t.setIncludeFontPadding(false);
        return t;
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            Date now = new Date();
            time.setText(new SimpleDateFormat("HH:mm", RU).format(now));
            String d = new SimpleDateFormat("EEEE, d MMMM", RU).format(now);
            date.setText(Character.toUpperCase(d.charAt(0)) + d.substring(1));
            ui.postDelayed(this, 1000 - (System.currentTimeMillis() % 1000) + 5);
        }
    };

    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); tick.run(); }
    @Override protected void onDetachedFromWindow() { ui.removeCallbacks(tick); super.onDetachedFromWindow(); }

    @Override public void onWeather(Weather w) {
        weather.setText(w == null ? "" : "Череповец  " + w.describe());
    }

    static int gravityEnd() { return Gravity.END; }
}
