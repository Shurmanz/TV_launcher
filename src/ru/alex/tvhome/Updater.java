package ru.alex.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

/**
 * Раз в сутки сверяет версию с releases/version.json в репозитории TV_launcher.
 * Сам ничего не устанавливает — только показывает метку «есть обновление»;
 * установка — скриптом Home Assistant (скачать APK → загрузить на ТВ → pm install).
 */
final class Updater {
    static final String VERSION_URL = "https://raw.githubusercontent.com/Shurmanz/TV_launcher/main/releases/version.json";
    private static final long DAY = 24 * 3600_000L;

    interface Callback { void onResult(boolean available, String name); }

    private Updater() { }

    static int myVersion(Context c) {
        try { return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionCode; }
        catch (Exception e) { return 0; }
    }

    static boolean available(Context c) {
        SharedPreferences p = c.getSharedPreferences("update", Context.MODE_PRIVATE);
        return p.getInt("remote", 0) > myVersion(c);
    }

    static String remoteName(Context c) {
        return c.getSharedPreferences("update", Context.MODE_PRIVATE).getString("name", "");
    }

    /** Проверить, если с прошлой проверки прошли сутки (или force). */
    static void check(Context ctx, boolean force, Callback cb) {
        Context c = ctx.getApplicationContext();
        SharedPreferences p = c.getSharedPreferences("update", Context.MODE_PRIVATE);
        if (!force && System.currentTimeMillis() - p.getLong("checked", 0) < DAY) {
            if (cb != null) cb.onResult(available(c), remoteName(c));
            return;
        }
        Handler ui = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            try {
                JSONObject o = new JSONObject(Net.getText(VERSION_URL + "?t=" + System.currentTimeMillis()));
                p.edit().putInt("remote", o.optInt("versionCode", 0)).putString("name", o.optString("versionName", ""))
                        .putLong("checked", System.currentTimeMillis()).apply();
                android.util.Log.i(Net.TAG, "update check: remote " + o.optInt("versionCode") + ", mine " + myVersion(c));
            } catch (Exception e) {
                Net.log("update check", e);
            }
            if (cb != null) ui.post(() -> cb.onResult(available(c), remoteName(c)));
        }).start();
    }
}
