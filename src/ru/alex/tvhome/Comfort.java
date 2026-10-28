package ru.alex.tvhome;

import android.app.ActivityManager;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.os.SystemClock;

/**
 * «Удобство»: таймер сна, освобождение памяти, старт с любимого приложения, возврат в прошлое приложение
 * (двойное «Домой»), подсказки по очереди и окно «Что нового» после обновления.
 */
final class Comfort {
    private Comfort() { }

    static SharedPreferences p(Context c) { return c.getSharedPreferences("comfort", Context.MODE_PRIVATE); }

    // ---------- таймер сна ----------

    static final String ACT_WARN = "ru.alex.tvhome.SLEEP_WARN", ACT_OFF = "ru.alex.tvhome.SLEEP_OFF";

    static long sleepAt(Context c) {
        long at = p(c).getLong("sleep_at", 0);
        return at > System.currentTimeMillis() ? at : 0;
    }

    /** Минут до выключения (с округлением вверх) или 0, если таймер не идёт. */
    static int sleepLeftMin(Context c) {
        long at = sleepAt(c);
        return at == 0 ? 0 : (int) Math.max(1, (at - System.currentTimeMillis() + 59_999) / 60_000);
    }

    private static PendingIntent pi(Context c, String action) {
        Intent i = new Intent(c, SleepReceiver.class).setAction(action);
        return PendingIntent.getBroadcast(c, action.hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** minutes <= 0 — отменить. */
    static void setSleep(Context c, int minutes) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        am.cancel(pi(c, ACT_WARN));
        am.cancel(pi(c, ACT_OFF));
        if (minutes <= 0) { p(c).edit().remove("sleep_at").apply(); return; }
        long at = System.currentTimeMillis() + minutes * 60_000L;
        p(c).edit().putLong("sleep_at", at).apply();
        if (minutes > 1) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at - 60_000L, pi(c, ACT_WARN));
        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi(c, ACT_OFF));
    }

    /** Будильники таймера: за минуту — карточка поверх фильма, в срок — «сон» через встроенный ADB. */
    public static class SleepReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            Context c = ctx.getApplicationContext();
            if (ACT_WARN.equals(intent.getAction())) {
                Overlay.show(c, "info", "Таймер сна", "Телевизор выключится через минуту");
                return;
            }
            if (!ACT_OFF.equals(intent.getAction())) return;
            p(c).edit().remove("sleep_at").apply();
            PendingResult pr = goAsync();
            new Thread(() -> {
                try {
                    Adb.sh(c, "input keyevent 223"); // KEYCODE_SLEEP; на ТВ — в дежурный режим
                } catch (Exception e) {
                    Net.log("sleep", e);
                    try { Adb.sh(c, "input keyevent 26"); } catch (Exception ignored) { }
                } finally { pr.finish(); }
            }).start();
        }
    }

    // ---------- память ----------

    /** {свободно до, свободно после, всего} в байтах. С «Оптимизацией» — am kill-all, без неё — фоновые процессы приложений. */
    static long[] freeMemory(Context c) {
        ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        long before = mi.availMem;
        boolean done = false;
        if (Adb.known(c)) {
            try { Adb.sh(c, "am kill-all"); done = true; } catch (Exception e) { Net.log("kill-all", e); }
        }
        if (!done) {
            for (ApplicationInfo ai : c.getPackageManager().getInstalledApplications(0)) {
                if (ai.packageName.equals(c.getPackageName())) continue;
                try { am.killBackgroundProcesses(ai.packageName); } catch (Exception ignored) { }
            }
        }
        SystemClock.sleep(1500);
        am.getMemoryInfo(mi);
        return new long[]{before, mi.availMem, mi.totalMem};
    }

    static long availMem(Context c) {
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        ((ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE)).getMemoryInfo(mi);
        return mi.availMem;
    }

    static long totalMem(Context c) {
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        ((ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE)).getMemoryInfo(mi);
        return mi.totalMem;
    }

    static String gb(long bytes) { return String.format(new java.util.Locale("ru"), "%.1f ГБ", bytes / 1073741824.0); }
    static String mb(long bytes) { return (bytes / 1048576) + " МБ"; }

    // ---------- старт с приложения и прошлое приложение ----------

    static String startPkg(Context c) { return p(c).getString("start_pkg", ""); }
    static void setStartPkg(Context c, String pkg) { p(c).edit().putString("start_pkg", pkg == null ? "" : pkg).apply(); }

    static volatile String lastApp;

    static String lastApp(Context c) {
        if (lastApp == null) lastApp = p(c).getString("last_app", null);
        return lastApp;
    }

    static void noteApp(Context c, String pkg) {
        if (pkg.equals(lastApp)) return;
        lastApp = pkg;
        p(c).edit().putString("last_app", pkg).apply();
    }

    static Intent launchIntent(Context c, String pkg) {
        Intent i = c.getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = c.getPackageManager().getLaunchIntentForPackage(pkg);
        if (i != null) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        return i;
    }

    static boolean open(Context c, String pkg) {
        Intent i = pkg == null || pkg.isEmpty() ? null : launchIntent(c, pkg);
        if (i == null) return false;
        try { c.startActivity(i); return true; } catch (Exception e) { return false; }
    }

    // ---------- подсказки по очереди ----------

    static final String[][] TIPS = {
            {"lib", "Добавить приложения и поменять порядок — долгое нажатие ОК на любой плитке"},
            {"sleep", "Таймер сна — долгое нажатие ОК на шестерёнке"},
            {"dbl", "Дважды «Домой» — вернуться в приложение, которое было открыто"},
    };
    private static final int TIP_SHOWS = 5;

    /** Текущая подсказка (или null). count — засчитать показ (при каждом возврате на главный экран). */
    static String tip(Context c, boolean count) {
        SharedPreferences ui = c.getSharedPreferences("ui", Context.MODE_PRIVATE);
        for (String[] t : TIPS) {
            if (t[0].equals("lib") && ui.getBoolean("lib_used", false)) continue;
            int n = ui.getInt("tip_" + t[0], 0);
            if (n >= TIP_SHOWS) continue;
            if (count) ui.edit().putInt("tip_" + t[0], n + 1).apply();
            return t[1];
        }
        return null;
    }

    static void tipDone(Context c, String key) {
        c.getSharedPreferences("ui", Context.MODE_PRIVATE).edit().putInt("tip_" + key, TIP_SHOWS).apply();
    }

    // ---------- что нового ----------

    static final String WHATS_NEW =
            "• Плашка внизу экрана, когда выходит новая версия.\n"
                    + "• Настройки Slauncher → «Удобство»: таймер сна, освобождение памяти, старт с любимого приложения, "
                    + "перенос настроек на другой телевизор (файлом или с телефона), «Вернуть как было».\n"
                    + "• Таймер сна быстро: долгое нажатие ОК на шестерёнке.\n"
                    + "• Дважды «Домой» — вернуться в приложение, которое было открыто.\n"
                    + "• Подсказки по очереди внизу главного экрана.";

    /** Показать «Что нового», если Slauncher обновился (а не установлен впервые). Один раз на версию. */
    static boolean shouldShowWhatsNew(Context c) {
        int cur = Updater.myVersion(c);
        int seen = p(c).getInt("seen_ver", 0);
        if (seen >= cur) return false;
        boolean upgrade = false;
        try {
            android.content.pm.PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            upgrade = pi.lastUpdateTime - pi.firstInstallTime > 60_000;
        } catch (Exception ignored) { }
        p(c).edit().putInt("seen_ver", cur).apply();
        return upgrade;
    }
}
