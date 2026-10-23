package ru.alex.tvhome;

import android.content.Context;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Шрифт Jost (геометрический, как Outfit, но с кириллицей; лицензия OFL).
 * Скачивается один раз из репозитория TV_launcher и хранится в filesDir.
 * Пока его нет — системный Roboto нужной насыщенности.
 */
final class Fonts {
    static final String URL = "https://raw.githubusercontent.com/Shurmanz/TV_launcher/main/releases/Jost.ttf";

    private static File file;
    private static boolean loaded, loading;
    private static final Map<Integer, Typeface> cache = new HashMap<>();
    private static final List<Object[]> views = new ArrayList<>(); // {WeakReference<TextView>, weight}
    private static final Handler ui = new Handler(Looper.getMainLooper());

    private Fonts() { }

    static void init(Context ctx) {
        if (file != null) return;
        file = new File(ctx.getFilesDir(), "Jost.ttf");
        loaded = file.length() > 50_000;
        if (!loaded && !loading) {
            loading = true;
            new Thread(() -> {
                try {
                    File tmp = new File(file.getPath() + ".dl");
                    Net.download(URL, tmp);
                    if (tmp.length() > 50_000 && tmp.renameTo(file)) {
                        ui.post(() -> { loaded = true; cache.clear(); refresh(); });
                    }
                } catch (Exception e) {
                    Net.log("font", e);
                } finally {
                    loading = false;
                }
            }).start();
        }
    }

    /** Шрифт заданной насыщенности (100…900). */
    static Typeface get(int weight) {
        Typeface t = cache.get(weight);
        if (t != null) return t;
        if (loaded) {
            try {
                t = new Typeface.Builder(file).setFontVariationSettings("'wght' " + weight).build();
            } catch (Exception e) {
                t = null;
            }
        }
        if (t == null) {
            String fam = weight <= 250 ? "sans-serif-thin" : weight <= 350 ? "sans-serif-light"
                    : weight >= 500 ? "sans-serif-medium" : "sans-serif";
            t = Typeface.create(fam, Typeface.NORMAL);
        }
        cache.put(weight, t);
        return t;
    }

    /** Назначить шрифт и запомнить вид, чтобы обновить его, когда Jost скачается. */
    static void apply(TextView v, int weight) {
        v.setTypeface(get(weight));
        if (!loaded) views.add(new Object[]{new WeakReference<>(v), weight});
    }

    private static void refresh() {
        for (Iterator<Object[]> it = views.iterator(); it.hasNext(); ) {
            Object[] o = it.next();
            @SuppressWarnings("unchecked") TextView v = ((WeakReference<TextView>) o[0]).get();
            if (v != null) v.setTypeface(get((Integer) o[1]));
            it.remove();
        }
    }
}
