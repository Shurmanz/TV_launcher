package ru.alex.tvhome;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * Шапка: «ДОБРЫЙ ВЕЧЕР», крупные тонкие часы | «ПЯТНИЦА, 23 ОКТЯБРЯ».
 * Строку погоды (если нужна) показывает отдельный TextView — его передают снаружи.
 */
final class ClockView extends LinearLayout implements WallpaperView.Listener {
    interface NightListener { void onNight(boolean night); }

    private static final Locale RU = new Locale("ru");
    private final TextView greeting, time, date;
    private final TextView weather; // может быть null
    private final Handler ui = new Handler(Looper.getMainLooper());
    private NightListener nightListener;
    private Boolean night;
    private Weather lastWeather;

    ClockView(Context ctx, float timeSp, TextView weatherView) {
        super(ctx);
        setOrientation(VERTICAL);
        weather = weatherView;
        greeting = Ui.text(ctx, timeSp * 0.2f, 400, 0xD9FFFFFF);
        greeting.setLetterSpacing(0.28f);
        greeting.setSingleLine(true);
        addView(greeting, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));

        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        time = Ui.text(ctx, timeSp, 200, 0xFFFFFFFF);
        time.setLetterSpacing(-0.01f);
        row.addView(time);
        View bar = new View(ctx);
        bar.setBackgroundColor(0x40FFFFFF);
        LayoutParams bl = new LayoutParams(Ui.dp(ctx, 1), Math.round(timeSp * 0.5f * getResources().getDisplayMetrics().density));
        bl.setMargins(Ui.dp(ctx, 22), Ui.dp(ctx, 6), Ui.dp(ctx, 22), 0);
        row.addView(bar, bl);
        date = Ui.text(ctx, timeSp * 0.24f, 500, 0xE6FFFFFF);
        date.setLetterSpacing(0.18f);
        date.setSingleLine(true);
        LayoutParams dl = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        dl.topMargin = Ui.dp(ctx, 6);
        row.addView(date, dl);
        LayoutParams rl = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        rl.topMargin = Ui.dp(ctx, 4);
        addView(row, rl);
        Ui.strongShadow(greeting);
        Ui.strongShadow(time);
        Ui.strongShadow(date);
        if (weather != null) Ui.strongShadow(weather);
    }

    void setNightListener(NightListener l) { nightListener = l; }

    static boolean isNight() {
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        return h >= 23 || h < 7;
    }

    static String greeting() {
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        if (h >= 5 && h < 12) return "ДОБРОЕ УТРО";
        if (h >= 12 && h < 17) return "ДОБРЫЙ ДЕНЬ";
        if (h >= 17 && h < 23) return "ДОБРЫЙ ВЕЧЕР";
        return "ДОБРОЙ НОЧИ";
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            Date now = new Date();
            time.setText(new SimpleDateFormat("HH:mm", RU).format(now));
            date.setText(new SimpleDateFormat("EEEE, d MMMM", RU).format(now).toUpperCase(RU));
            greeting.setText(greeting());
            boolean n = isNight();
            if (night == null || night != n) {
                night = n;
                setAlpha(n ? 0.6f : 1f);
                if (weather != null) weather.setAlpha(n ? 0.6f : 1f);
                if (nightListener != null) nightListener.onNight(n);
            }
            if (lastWeather != null && weather != null) onWeather(lastWeather); // «к 21:00» сдвигается со временем
            ui.postDelayed(this, 60_000 - (System.currentTimeMillis() % 60_000) + 50);
        }
    };

    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); tick.run(); }
    @Override protected void onDetachedFromWindow() { ui.removeCallbacks(tick); super.onDetachedFromWindow(); }

    @Override public void onWeather(Weather w) {
        lastWeather = w;
        if (weather == null || w == null) return;
        String h = w.hint();
        weather.setText(w.describe() + (h.isEmpty() ? "" : "  ·  " + h));
    }
}
