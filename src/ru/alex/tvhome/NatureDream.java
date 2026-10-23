package ru.alex.tvhome;

import android.graphics.drawable.GradientDrawable;
import android.service.dreams.DreamService;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

/** Заставка: те же фото природы (раз в минуту, ночью реже и темнее), часы и погода слева внизу. */
public class NatureDream extends DreamService {
    private WallpaperView wall;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        setScreenBright(true);

        FrameLayout root = new FrameLayout(this);
        Fonts.init(this);
        wall = new WallpaperView(this, 60_000L);
        root.addView(wall, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        View scrim = new View(this);
        float d = getResources().getDisplayMetrics().density;
        GradientDrawable sg = new GradientDrawable();
        sg.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        sg.setColors(new int[]{0xB3000000, 0x59000000, 0x00000000});
        sg.setGradientCenter(0f, 1f);
        sg.setGradientRadius(540 * d);
        scrim.setBackground(sg);
        root.addView(scrim, new FrameLayout.LayoutParams(Math.round(560 * d), Math.round(540 * d), Gravity.BOTTOM | Gravity.START));

        View dim = new View(this);
        dim.setBackgroundColor(0xFF000000);
        dim.setAlpha(0f);
        root.addView(dim, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        android.widget.TextView weather = Ui.text(this, 17, 400, 0xE6FFFFFF);
        ClockView clock = new ClockView(this, 56, weather);
        clock.setNightListener(n -> {
            dim.animate().alpha(n ? 0.5f : 0f).setDuration(1500).start();
            wall.setInterval(n ? 5 * 60_000L : 60_000L);
        });
        box.addView(clock, new android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)); // иначе дата сжимается по строке погоды
        android.widget.LinearLayout.LayoutParams wl = new android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wl.topMargin = Math.round(12 * d);
        box.addView(weather, wl);
        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START);
        cl.setMargins(Math.round(64 * d), 0, 0, Math.round(56 * d));
        root.addView(box, cl);
        wall.addListener(clock);

        setContentView(root);
    }

    @Override public void onDreamingStarted() { super.onDreamingStarted(); if (wall != null) wall.start(); }
    @Override public void onDreamingStopped() { if (wall != null) wall.stop(); super.onDreamingStopped(); }
    @Override public void onDetachedFromWindow() { if (wall != null) wall.release(); super.onDetachedFromWindow(); }
}
