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

    // ---------- фон главного экрана ----------

    /** Фон: "photo" — фото природы, "sky" — «Живая погода», "space" — 4K-снимки космоса. */
    static String bg(Context c) {
        String v = p(c).getString("bg", "photo");
        return "sky".equals(v) || "space".equals(v) ? v : "photo";
    }
    static void setBg(Context c, String v) { p(c).edit().putString("bg", v).apply(); }
    /** true — «Живая погода» (абстрактный анимированный фон). */
    static boolean bgSky(Context c) { return "sky".equals(bg(c)); }
    static boolean bgSpace(Context c) { return "space".equals(bg(c)); }
    static void setBgSky(Context c, boolean on) { setBg(c, on ? "sky" : "photo"); }

    // ---------- оставаться на связи при выключенном экране ----------

    /** Xiaomi (MediaTek) в выключенном состоянии засыпает вместе с Wi‑Fi — HA и Алиса до него не достают. По умолчанию на Xiaomi включено. */
    static boolean stayOnline(Context c) {
        boolean xiaomi = android.os.Build.MANUFACTURER.toLowerCase(java.util.Locale.ROOT).contains("xiaomi");
        return p(c).getBoolean("stay_online", xiaomi);
    }
    static void setStayOnline(Context c, boolean on) { p(c).edit().putBoolean("stay_online", on).apply(); }

    private static android.os.PowerManager.WakeLock cpuLock;
    private static android.net.wifi.WifiManager.WifiLock wifiLock;

    /** Держать процессор и Wi‑Fi бодрыми (экран при этом гаснет как обычно). Вызывать при старте службы и при смене настройки. */
    static synchronized void applyStayOnline(Context ctx) {
        Context c = ctx.getApplicationContext();
        boolean on = stayOnline(c);
        try {
            if (on && cpuLock == null) {
                android.os.PowerManager pm = (android.os.PowerManager) c.getSystemService(Context.POWER_SERVICE);
                cpuLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "slauncher:online");
                cpuLock.setReferenceCounted(false);
                cpuLock.acquire();
                android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager) c.getSystemService(Context.WIFI_SERVICE);
                wifiLock = wm.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "slauncher:online");
                wifiLock.setReferenceCounted(false);
                wifiLock.acquire();
                android.util.Log.i(Net.TAG, "на связи при выключенном экране: включено");
                // Doze (глубокий сон Android) через час-другой простоя перестаёт уважать wakelock — телевизор засыпает целиком.
                // Приложения из списка исключений Doze он не трогает: добавляемся туда через встроенный ADB (сохраняется навсегда).
                android.os.PowerManager pm2 = (android.os.PowerManager) c.getSystemService(Context.POWER_SERVICE);
                if (!pm2.isIgnoringBatteryOptimizations(c.getPackageName()) && Adb.known(c)) {
                    new Thread(() -> {
                        try { android.util.Log.i(Net.TAG, "doze: " + Adb.sh(c, "cmd deviceidle whitelist +" + c.getPackageName()).trim()); }
                        catch (Exception e) { Net.log("doze whitelist", e); }
                    }).start();
                }
            } else if (!on && cpuLock != null) {
                cpuLock.release();
                cpuLock = null;
                if (wifiLock != null) { wifiLock.release(); wifiLock = null; }
                android.util.Log.i(Net.TAG, "на связи при выключенном экране: выключено");
            }
        } catch (Exception e) { Net.log("stay online", e); }
    }

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
            "• Кнопки пульта: своё действие на любую кнопку — Netflix, YouTube, цветные, цифры. Например, Netflix открывает Зону, "
                    + "а долгое нажатие — NUM («Удобство» → «Кнопки пульта» → нажмите кнопку на пульте).\n"
                    + "• Предупреждения о погоде под погодой — только когда есть повод: гололёд, сильный мороз или жара, ветер, сильный снег.\n"
                    + "• Долгое ОК на плитке приложения: открыть, закрыть зависшее, очистить кеш, переместить, убрать с главного.\n"
                    + "• Память телевизора: когда места мало, на главном экране появится напоминание; кеш приложений чистится одной кнопкой "
                    + "(«Удобство» → «Место и кеш»).\n"
                    + "• «Что посмотреть» (значок хлопушки рядом с ракетой): лучшие сериалы за этот и прошлый год (от 7,5) "
                    + "и фильмы, вышедшие в цифре (от 7), — русские и иностранные отдельно, с постерами и оценками КиноПоиска и IMDb. "
                    + "ОК — описание, «Смотреть в Зоне» или «Открыть в NUM». Есть поиск по названию.\n"
                    + "• Новые серии: в карточке сериала «Следить за сериями» — и в день выхода серии на главном экране "
                    + "появится «Вышла 5 серия: …». Список общий для всех телевизоров дома — они сверяются по Wi‑Fi.\n"
                    + "• «Удобство» → «Оставаться на связи при выключенном экране»: с погасшим экраном телевизор не засыпает целиком "
                    + "и остаётся в сети — пульт в телефоне и голосовые ассистенты могут его включить.{HA_ONLINE}\n"
                    + "• Мастер настройки: «Не засыпать целиком» и «Не сбрасывать разрешение отладки» — включаются вместе со всем остальным.\n"
                    + "• «Что посмотреть»: в карточке «✕ Не показывать» — сериал или фильм пропадает из подборки, на его место встаёт следующий. "
                    + "Вернуть — Настройки Slauncher → «Фильмы».\n"
                    + "• Обновление: если GitHub недоступен, Slauncher скачивает с запасного адреса и честно пишет, когда проверить не вышло. "
                    + "Новая версия — красная точка на значке настроек; при установке больше не запускается прошлое приложение.\n"
                    + "• Настройки Slauncher → «Фильмы»: пошаговая инструкция, как получить токен Кинопоиска и вставить его с телефона.\n"
                    + "• Новый фон «Космос»: снимки телескопов James Webb, Hubble и ESO в 4K, в случайном порядке "
                    + "(Настройки Slauncher → «Удобство» → «Фон главного экрана»).\n"
                    + "• Фото природы: в разы больше снимков (лучшие фото Wikimedia, больше сюжетов) и без повторов — "
                    + "сначала показываются те, что ещё не видели. В углу — подпись, где снято.\n"
                    + "• Новый фон «Живая погода»: дождь льётся, снег падает, туман плывёт — под погоду и время суток. "
                    + "Выбор «Фото / Погода» — в Настройки Slauncher → «Удобство».\n"
                    + "• Плитки как в Apple TV: в фокусе плавно крупнее, с цветной подсветкой.\n"
                    + "• Плашка внизу экрана, когда выходит новая версия.\n"
                    + "• Настройки Slauncher → «Удобство»: таймер сна, освобождение памяти, старт с любимого приложения, "
                    + "перенос настроек на другой телевизор (файлом или с телефона), «Вернуть как было».\n"
                    + "• Ракета рядом с Wi‑Fi — освободить память одним нажатием.\n"
                    + "• Таймер сна быстро: долгое нажатие ОК на шестерёнке.\n"
                    + "• Дважды «Домой» — вернуться в приложение, которое было открыто.\n"
                    + "• Подсказки по очереди внизу главного экрана.";

    /** В этом доме есть Home Assistant: подключён в настройках или за последние 2 недели присылал данные на телевизор. */
    static boolean haHome(Context c) {
        if (HaConfig.enabled(c)) return true;
        return System.currentTimeMillis() - HaReceiver.prefs(c).getLong("seen", 0) < 14 * 86400_000L;
    }

    /** «Что нового» для этого телевизора: строки про Home Assistant — только там, где он есть. */
    static String whatsNew(Context c) {
        boolean ha = haHome(c);
        StringBuilder out = new StringBuilder();
        for (String line : WHATS_NEW.replace("{HA_ONLINE}", ha ? " Home Assistant и Алиса тоже до него достают." : "").split("\n")) {
            if (!ha && (line.contains("Home Assistant") || line.contains("Алис") || line.contains("домофон") || line.contains("протечк")
                    || line.contains("климат"))) continue;
            if (out.length() > 0) out.append('\n');
            out.append(line);
        }
        return out.toString();
    }

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
        if (!upgrade) p(c).edit().putInt("seen_ver", cur).apply(); // при обновлении — отметка по «Понятно»
        return upgrade;
    }

    static void whatsNewSeen(Context c) { p(c).edit().putInt("seen_ver", Updater.myVersion(c)).apply(); }
}
