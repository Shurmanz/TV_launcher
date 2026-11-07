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
    /** Запасной адрес — jsDelivr (CDN с копией репозитория): выручает, когда raw.githubusercontent.com режет провайдер. */
    static final String MIRROR_VERSION_URL = "https://cdn.jsdelivr.net/gh/Shurmanz/TV_launcher@main/releases/version.json";
    static final String MIRROR_APK_URL = "https://cdn.jsdelivr.net/gh/Shurmanz/TV_launcher@main/releases/tvhome.apk";

    /** Скачать APK: сначала GitHub, при ошибке или несовпадении контрольной суммы — зеркало. */
    private static void fetchApk(Context c, java.io.File f) throws Exception {
        String want = c.getSharedPreferences("update", Context.MODE_PRIVATE).getString("md5", "");
        Exception last = null;
        for (String url : new String[]{APK_URL + "?t=" + System.currentTimeMillis(), MIRROR_APK_URL}) {
            try {
                f.delete();
                Net.download(url, f);
                if (f.length() < 100_000) throw new Exception("файл слишком маленький: " + f.length());
                if (!want.isEmpty() && !want.equalsIgnoreCase(md5(f))) throw new Exception("файл повреждён (контрольная сумма не сошлась)");
                android.util.Log.i(Net.TAG, "update: скачан с " + url);
                return;
            } catch (Exception e) {
                Net.log("update download " + url, e);
                last = e;
            }
        }
        throw new Exception(last != null && last.getMessage() != null && last.getMessage().startsWith("файл") ? last.getMessage()
                : "нет связи с сервером обновлений");
    }

    static String md5(java.io.File f) throws Exception {
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
        try (java.io.InputStream in = new java.io.FileInputStream(f)) {
            byte[] b = new byte[65536];
            int n;
            while ((n = in.read(b)) > 0) md.update(b, 0, n);
        }
        StringBuilder sb = new StringBuilder();
        for (byte x : md.digest()) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    /** Последняя проверка не достучалась ни до GitHub, ни до зеркала. */
    static boolean checkFailed(Context c) {
        return c.getSharedPreferences("update", Context.MODE_PRIVATE).getBoolean("check_failed", false);
    }

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
                fetchApk(c, f);
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
    /** Через встроенный ADB: без экрана «Установить?»; при ошибке — обычный путь. */
    static void silentInstall(Context ctx, Progress cb) {
        Context c = ctx.getApplicationContext();
        Handler ui = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            try {
                ui.post(() -> cb.onState("Скачиваю обновление…", false));
                java.io.File f = new java.io.File(c.getExternalFilesDir(null), "update.apk");
                fetchApk(c, f);
                ui.post(() -> cb.onState("Устанавливаю — Slauncher перезапустится сам", false));
                Optimizer.silentInstall(c, f);
            } catch (Exception e) {
                Net.log("silent update", e);
                ui.post(() -> {
                    if (canInstall(c)) downloadAndInstall(c, cb);
                    else cb.onState("Не получилось: " + e.getMessage(), true);
                });
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

    static int remoteCode(Context c) {
        return c.getSharedPreferences("update", Context.MODE_PRIVATE).getInt("remote", 0);
    }

    /** Плашку «вышла новая версия» скрыли для этой версии? */
    static boolean bannerDismissed(Context c) {
        return c.getSharedPreferences("update", Context.MODE_PRIVATE).getInt("dismissed", 0) >= remoteCode(c);
    }

    static void dismissBanner(Context c) {
        c.getSharedPreferences("update", Context.MODE_PRIVATE).edit().putInt("dismissed", remoteCode(c)).apply();
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
            JSONObject o = null;
            for (String url : new String[]{VERSION_URL + "?t=" + System.currentTimeMillis(), MIRROR_VERSION_URL}) {
                try { o = new JSONObject(Net.getText(url)); break; }
                catch (Exception e) { Net.log("update check " + url, e); }
            }
            if (o != null) {
                p.edit().putInt("remote", o.optInt("versionCode", 0)).putString("name", o.optString("versionName", ""))
                        .putString("md5", o.optString("md5", "")).putBoolean("check_failed", false)
                        .putLong("checked", System.currentTimeMillis()).apply();
                android.util.Log.i(Net.TAG, "update check: remote " + o.optInt("versionCode") + ", mine " + myVersion(c));
            } else {
                // ни GitHub, ни зеркало не ответили — честно говорим, а не «установлена последняя версия»;
                // время проверки не запоминаем, чтобы при следующем открытии попробовать снова
                p.edit().putBoolean("check_failed", true).apply();
            }
            if (cb != null) ui.post(() -> cb.onResult(available(c), remoteName(c)));
        }).start();
    }
}
