package ru.alex.tvhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.util.List;

/**
 * Диагностика без пересборки (результат — в logcat с тегом TvHome):
 *   am broadcast -a ru.alex.tvhome.DEBUG -n ru.alex.tvhome/.DebugReceiver --es q "autumn forest" --ei t 0
 *   am broadcast -a ru.alex.tvhome.DEBUG -n ru.alex.tvhome/.DebugReceiver --ez weather true
 *   am broadcast -a ru.alex.tvhome.DEBUG -n ru.alex.tvhome/.DebugReceiver --ez reset true   (сброс кеша списков фото)
 *   am broadcast ... --es tpl0 "%s incategory:Landscapes"   (свой шаблон поиска; %s — ключевые слова)
 * Принимает только от shell/root (adb).
 */
public class DebugReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        final PendingResult pr = goAsync();
        final Context app = ctx.getApplicationContext();
        new Thread(() -> {
            try {
                for (String k : new String[]{"tpl0", "tpl1"}) {
                    String v = intent.getStringExtra(k);
                    if (v != null) {
                        app.getSharedPreferences("walls", Context.MODE_PRIVATE).edit().putString(k, v).apply();
                        Log.i(Net.TAG, "debug: " + k + " = " + v);
                    }
                }
                if (intent.hasExtra("minw")) {
                    int mw = intent.getIntExtra("minw", 1280);
                    app.getSharedPreferences("walls", Context.MODE_PRIVATE).edit().putInt("minw", mw).apply();
                    Log.i(Net.TAG, "debug: minw = " + mw);
                }
                if (intent.getBooleanExtra("reset", false)) {
                    android.content.SharedPreferences sp = app.getSharedPreferences("walls", Context.MODE_PRIVATE);
                    android.content.SharedPreferences.Editor ed = sp.edit();
                    for (String k : sp.getAll().keySet()) if (!k.startsWith("tpl") && !k.equals("minw")) ed.remove(k);
                    ed.apply();
                    Log.i(Net.TAG, "debug: wallpaper lists reset");
                }
                if (intent.getBooleanExtra("weather", false)) {
                    try {
                        Weather w = Weather.fetch();
                        Log.i(Net.TAG, "debug weather: " + w.describe() + " code=" + w.code);
                    } catch (Exception e) {
                        Net.log("debug weather", e);
                    }
                    long[] sun = Weather.sunTimes(System.currentTimeMillis());
                    Log.i(Net.TAG, "debug sun: " + new java.util.Date(sun[0]) + " / " + new java.util.Date(sun[1])
                            + " phase=" + Weather.phase(null, System.currentTimeMillis()));
                }
                String q = intent.getStringExtra("q");
                if (q != null) {
                    int tier = intent.getIntExtra("t", 0);
                    try {
                        List<String> r = new Wallpapers(app).searchCommons(q, tier);
                        Log.i(Net.TAG, "debug commons t" + tier + " [" + q + "]: " + r.size()
                                + (r.isEmpty() ? "" : " first=" + r.get(0)));
                    } catch (Exception e) {
                        Net.log("debug commons", e);
                    }
                }
            } finally {
                pr.finish();
            }
        }).start();
    }
}
