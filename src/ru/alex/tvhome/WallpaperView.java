package ru.alex.tvhome;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Фон из фото природы под время суток и погоду: плавная смена и медленный наезд камеры. */
final class WallpaperView extends FrameLayout {
    interface Listener { void onWeather(Weather w); }

    private static final long WEATHER_TTL = 20 * 60_000L;
    private static final long FADE_MS = 1800L;
    /** Общая на лаунчер и заставку — чтобы не запрашивать погоду дважды. */
    static volatile Weather lastWeather;

    private final long interval;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private final Wallpapers walls;
    private ImageView front, back;
    private Listener listener;

    private List<String> urls = new ArrayList<>();
    private String sceneKey = "";
    private int urlIndex;
    private boolean running, busy;

    WallpaperView(Context ctx, long intervalMs) {
        super(ctx);
        interval = intervalMs;
        walls = new Wallpapers(ctx);
        setBackgroundColor(0xFF0B0F14);
        back = img();
        front = img();
        addView(back, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        addView(front, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private ImageView img() {
        ImageView v = new ImageView(getContext());
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        return v;
    }

    void setListener(Listener l) {
        listener = l;
        if (l != null && lastWeather != null) l.onWeather(lastWeather);
    }

    void start() {
        if (running) return;
        running = true;
        if (front.getDrawable() == null) showCached();
        ui.post(tick);
    }

    void stop() {
        running = false;
        ui.removeCallbacks(tick);
    }

    void release() {
        stop();
        bg.shutdownNow();
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            ui.removeCallbacks(this);
            if (!running) return;
            loadNext();
            ui.postDelayed(this, interval);
        }
    };

    private void showCached() {
        bg.execute(() -> {
            File f = walls.anyCached();
            Bitmap bm = f == null ? null : decode(f);
            if (bm != null) ui.post(() -> crossfade(bm));
        });
    }

    private void loadNext() {
        if (busy) return;
        busy = true;
        bg.execute(() -> {
            Bitmap bm = null;
            try {
                Weather w = lastWeather;
                if (w == null || System.currentTimeMillis() - w.fetchedAt > WEATHER_TTL) {
                    try {
                        w = Weather.fetch();
                        lastWeather = w;
                        final Weather fw = w;
                        ui.post(() -> { if (listener != null) listener.onWeather(fw); });
                        android.util.Log.i(Net.TAG, "weather ok: " + fw.describe());
                    } catch (Exception e) {
                        Net.log("weather", e);
                    }
                }
                Weather.Phase p = Weather.phase(lastWeather, System.currentTimeMillis());
                Weather.Sky s = Weather.sky(lastWeather);
                String key = Wallpapers.sceneKey(p, s);
                if (!key.equals(sceneKey) || urls.isEmpty()) {
                    List<String> fresh = walls.urlsFor(p, s);
                    android.util.Log.i(Net.TAG, "scene " + key + ": " + fresh.size() + " photos");
                    if (!fresh.isEmpty()) { urls = fresh; urlIndex = 0; sceneKey = key; }
                }
                for (int i = 0; i < 3 && bm == null && !urls.isEmpty(); i++) {
                    String u = urls.get(urlIndex++ % urls.size());
                    try { bm = decode(walls.fetch(u)); } catch (Exception e) { Net.log("image " + u, e); }
                }
                if (bm == null) {
                    File f = walls.anyCached();
                    if (f != null) bm = decode(f);
                }
                if (!urls.isEmpty()) { // заранее скачать следующую
                    try { walls.fetch(urls.get(urlIndex % urls.size())); } catch (Exception ignored) { }
                }
            } finally {
                final Bitmap res = bm;
                ui.post(() -> {
                    busy = false;
                    if (res != null && running) crossfade(res);
                });
            }
        });
    }

    private static Bitmap decode(File f) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), o);
            if (o.outWidth <= 0) { f.delete(); return null; }
            int sample = 1;
            while (o.outWidth / (sample * 2) >= 1920) sample *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            o2.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeFile(f.getPath(), o2);
        } catch (Throwable t) {
            f.delete();
            return null;
        }
    }

    private void crossfade(Bitmap bm) {
        final ImageView in = back, out = front;
        in.animate().cancel();
        in.setImageBitmap(bm);
        in.setAlpha(0f);
        in.setScaleX(1f);
        in.setScaleY(1f);
        in.bringToFront();
        in.animate().alpha(1f).setDuration(FADE_MS).setInterpolator(new DecelerateInterpolator())
                .setListener(new AnimatorListenerAdapter() {
                    @Override public void onAnimationEnd(Animator a) {
                        out.setImageDrawable(null);
                        in.animate().setListener(null).scaleX(1.07f).scaleY(1.07f)
                                .setDuration(interval).setInterpolator(new LinearInterpolator()).start();
                    }
                }).start();
        back = out;
        front = in;
    }
}
