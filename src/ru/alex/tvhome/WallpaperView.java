package ru.alex.tvhome;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
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

/** Фон из фото природы под время суток, сезон и погоду: плавная смена и медленный наезд камеры. */
final class WallpaperView extends FrameLayout {
    interface Listener { void onWeather(Weather w); }

    private static final long WEATHER_TTL = 20 * 60_000L;
    private static final long FADE_MS = 1800L;
    /** Общая на лаунчер и заставку — чтобы не запрашивать погоду дважды. */
    static volatile Weather lastWeather;

    private long interval;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private final Wallpapers walls;
    private ImageView front, back;
    private final List<Listener> listeners = new ArrayList<>();

    private List<String> urls = new ArrayList<>();
    private String sceneKey = "";
    private int urlIndex;
    private boolean running, busy;
    private String current, pending;
    private long lastChange; // SystemClock.elapsedRealtime() последней смены

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

    void addListener(Listener l) {
        listeners.add(l);
        if (lastWeather != null) l.onWeather(lastWeather);
    }

    /** Новый интервал смены (например, ночью реже). */
    void setInterval(long ms) {
        if (ms == interval) return;
        interval = ms;
        if (running) schedule();
    }

    void start() {
        if (running) return;
        running = true;
        if (front.getDrawable() == null) {
            showCached();
            ui.post(tick);
        } else {
            schedule(); // вернулись на экран — не меняем фото раньше срока
        }
    }

    void stop() {
        running = false;
        ui.removeCallbacks(tick);
    }

    void release() {
        stop();
        bg.shutdownNow();
    }

    /** Сразу следующее фото. */
    void next() {
        if (!running) return;
        ui.removeCallbacks(tick);
        ui.post(tick);
    }

    /** Больше никогда не показывать текущее фото и сразу сменить. */
    void hideCurrent() {
        final String c = current;
        if (c == null) return;
        bg.execute(() -> {
            walls.hide(c);
            urls.remove(c);
        });
        next();
    }

    boolean hasPhoto() { return current != null; }

    private void schedule() {
        ui.removeCallbacks(tick);
        long left = interval - (SystemClock.elapsedRealtime() - lastChange);
        ui.postDelayed(tick, Math.max(1000, left));
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
            if (bm != null) ui.post(() -> { if (front.getDrawable() == null) crossfade(bm, null); });
        });
    }

    private void loadNext() {
        if (busy) return;
        busy = true;
        bg.execute(() -> {
            Bitmap bm = null;
            String used = null;
            try {
                Weather w = lastWeather;
                if (w == null || System.currentTimeMillis() - w.fetchedAt > WEATHER_TTL) {
                    try {
                        w = Weather.fetch();
                        lastWeather = w;
                        final Weather fw = w;
                        ui.post(() -> { for (Listener l : listeners) l.onWeather(fw); });
                        android.util.Log.i(Net.TAG, "weather ok: " + fw.describe() + " | " + fw.hint()
                                + " | hours " + fw.hours.size() + ", days " + fw.days.size());
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
                    if (!fresh.isEmpty()) { urls = new ArrayList<>(fresh); urlIndex = 0; sceneKey = key; }
                }
                for (int i = 0; i < 3 && bm == null && !urls.isEmpty(); i++) {
                    String u = urls.get(urlIndex++ % urls.size());
                    if (u.equals(current) && urls.size() > 1) u = urls.get(urlIndex++ % urls.size());
                    try { bm = decode(walls.fetch(u)); used = u; } catch (Exception e) { Net.log("image " + u, e); }
                }
                if (bm == null && current == null) {
                    File f = walls.anyCached();
                    if (f != null) bm = decode(f);
                }
                if (!urls.isEmpty()) { // заранее скачать следующую
                    try { walls.fetch(urls.get(urlIndex % urls.size())); } catch (Exception ignored) { }
                }
            } finally {
                final Bitmap res = bm;
                final String fu = used;
                ui.post(() -> {
                    busy = false;
                    if (res != null && running) crossfade(res, fu);
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

    private void crossfade(Bitmap bm, String entry) {
        android.util.Log.i(Net.TAG, "show " + bm.getWidth() + "x" + bm.getHeight() + " " + (entry == null ? "(cache)" : entry.split("\t")[0]));
        current = entry;
        lastChange = SystemClock.elapsedRealtime();
        final ImageView in = back, out = front;
        // прошлая смена могла ещё идти: без этого её конец стирал новое фото (чёрный фон)
        in.animate().setListener(null).cancel();
        out.animate().setListener(null).cancel();
        if (out.getDrawable() != null) out.setAlpha(1f);
        in.setImageBitmap(bm);
        in.setAlpha(0f);
        in.setScaleX(1f);
        in.setScaleY(1f);
        in.bringToFront();
        in.animate().alpha(1f).setDuration(FADE_MS).setInterpolator(new DecelerateInterpolator())
                .setListener(new AnimatorListenerAdapter() {
                    @Override public void onAnimationEnd(Animator a) {
                        if (front == in) out.setImageDrawable(null);
                        in.animate().setListener(null).scaleX(1.07f).scaleY(1.07f)
                                .setDuration(Math.min(interval, 120_000L)).setInterpolator(new LinearInterpolator()).start();
                    }
                }).start();
        back = out;
        front = in;
    }
}
