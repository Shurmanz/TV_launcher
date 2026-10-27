package ru.alex.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

/**
 * Раз в сутки сверяет версию с releases/version.json в репозитории TV_launcher и показывает метку
 * «есть обновление». Кнопка «Обновить» в настройках Slauncher скачивает APK и отдаёт его системному
 * установщику — телевизор спрашивает «Установить?», одно нажатие пультом.
 */
final class Updater {
    static final String VERSION_URL = "https://raw.githubusercontent.com/Shurmanz/TV_launcher/main/releases/version.json";
    static final String APK_URL = "https://raw.githubusercontent.com/Shurmanz/TV_launcher/main/releases/tvhome.apk";

    interface Progress { void onState(String text, boolean done); }

    static String myName(Context c) {
        try { return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName; }
        catch (Exception e) { return "?"; }
    }

    /** Разрешено ли приложению ставить APK (Android 8+: «Установка неизвестных приложений»). */
    static boolean canInstall(Context c) {
        try { return c.getPackageManager().canRequestPackageInstalls(); } catch (Exception e) { return false; }
    }

    /**
     * Скачать свежий APK и передать системному установщику (PackageInstaller-сессия).
     * Подтверждение «Установить» показывает сам Android — его ловит InstallReceiver.
     */
    static void downloadAndInstall(Context ctx, Progress cb) {
        Context c = ctx.getApplicationContext();
        Handler ui = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            java.io.File f = new java.io.File(c.getCacheDir(), "update.apk");
            try {
                ui.post(() -> cb.onState("Скачиваю…", false));
                f.delete();
                Net.download(APK_URL + "?t=" + System.currentTimeMillis(), f);
                if (f.length() < 100_000) throw new Exception("файл слишком маленький: " + f.length());
                android.content.pm.PackageInstaller pi = c.getPackageManager().getPackageInstaller();
                android.content.pm.PackageInstaller.SessionParams sp = new android.content.pm.PackageInstaller.SessionParams(
                        android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                sp.setAppPackageName(c.getPackageName());
                int id = pi.createSession(sp);
                try (android.content.pm.PackageInstaller.Session s = pi.openSession(id);
                     java.io.InputStream in = new java.io.FileInputStream(f);
                     java.io.OutputStream out = s.openWrite("slauncher.apk", 0, f.length())) {
                    byte[] b = new byte[65536];
                    int n;
                    while ((n = in.read(b)) > 0) out.write(b, 0, n);
                    s.fsync(out);
                    android.content.Intent i = new android.content.Intent(c, InstallReceiver.class);
                    android.app.PendingIntent pend = android.app.PendingIntent.getBroadcast(c, id, i,
                            android.app.PendingIntent.FLAG_UPDATE_CURRENT);
                    s.commit(pend.getIntentSender());
                }
                ui.post(() -> cb.onState("Подтвердите установку на экране", true));
            } catch (Exception e) {
                Net.log("update install", e);
                ui.post(() -> cb.onState("Не получилось: " + e.getMessage(), true));
            }
        }).start();
    }
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
