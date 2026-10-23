package ru.alex.tvhome;

import android.graphics.drawable.GradientDrawable;
import android.service.dreams.DreamService;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

/** Заставка: те же фото природы, смена раз в 30 с, часы в левом нижнем углу. */
public class NatureDream extends DreamService {
    private WallpaperView wall;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        setScreenBright(true);

        FrameLayout root = new FrameLayout(this);
        wall = new WallpaperView(this, 30_000L);
        root.addView(wall, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        View scrim = new View(this);
        float d = getResources().getDisplayMetrics().density;
        GradientDrawable sg = new GradientDrawable();
        sg.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        sg.setColors(new int[]{0x8C000000, 0x33000000, 0x00000000});
        sg.setGradientCenter(0f, 1f);
        sg.setGradientRadius(400 * d);
        scrim.setBackground(sg);
        root.addView(scrim, new FrameLayout.LayoutParams(Math.round(640 * d), Math.round(420 * d), Gravity.BOTTOM | Gravity.START));

        ClockView clock = new ClockView(this, 56, Gravity.START);
        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START);
        cl.setMargins(Math.round(64 * d), 0, 0, Math.round(56 * d));
        root.addView(clock, cl);
        wall.setListener(clock);

        setContentView(root);
    }

    @Override public void onDreamingStarted() { super.onDreamingStarted(); if (wall != null) wall.start(); }
    @Override public void onDreamingStopped() { if (wall != null) wall.stop(); super.onDreamingStopped(); }
    @Override public void onDetachedFromWindow() { if (wall != null) wall.release(); super.onDetachedFromWindow(); }
}
