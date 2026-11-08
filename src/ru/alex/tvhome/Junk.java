package ru.alex.tvhome;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * «Лишние приложения»: всё, чего нет на главном экране, — когда открывали и сколько весит.
 * Отмеченное убирается: стороннее удаляется (апк сохраняется в Download/Slauncher/apps), встроенное выключается.
 * «Вернуть всё как было» ставит/включает обратно. Важное для работы телевизора в список не попадает —
 * узнаём по признакам (входы ТВ, клавиатура, главный экран, пульт), а не по названиям конкретной модели.
 * Ничего не работает в фоне: всё считается только при открытии экрана.
 */
final class Junk {
    static final String DIR = "/sdcard/Download/Slauncher/apps";

    static final class Item {
        String pkg, label;
        boolean system;
        long lastUsed; // 0 — не знаем / не открывали
        long size;
        android.graphics.drawable.Drawable icon;
    }

    private Junk() { }

    private static SharedPreferences p(Context c) { return c.getSharedPreferences("junk", Context.MODE_PRIVATE); }

    /** Чем телевизор пользуется сам — не показываем ни при каких условиях. */
    static Set<String> guarded(Context c) {
        PackageManager pm = c.getPackageManager();
        Set<String> s = new HashSet<>(java.util.Arrays.asList(
                c.getPackageName(), "com.android.vending", "com.google.android.gms", "com.google.android.gsf",
                "com.google.android.apps.mediashell", "com.android.tv.settings", "com.android.settings",
                "com.google.android.katniss", "com.google.android.tv.remote.service", TvInputs.TCL_TV.getPackageName()));
        try { // главные экраны
            for (ResolveInfo r : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0))
                s.add(r.activityInfo.packageName);
        } catch (Exception ignored) { }
        try { // клавиатуры
            android.view.inputmethod.InputMethodManager im =
                    (android.view.inputmethod.InputMethodManager) c.getSystemService(Context.INPUT_METHOD_SERVICE);
            for (android.view.inputmethod.InputMethodInfo i : im.getInputMethodList()) s.add(i.getPackageName());
        } catch (Exception ignored) { }
        try { // входы ТВ: HDMI, тюнер — и приложение, которое их показывает
            android.media.tv.TvInputManager tm = (android.media.tv.TvInputManager) c.getSystemService(Context.TV_INPUT_SERVICE);
            if (tm != null) for (android.media.tv.TvInputInfo i : tm.getTvInputList()) s.add(i.getServiceInfo().packageName);
            Intent live = new Intent(Intent.ACTION_VIEW, android.media.tv.TvContract.buildChannelUri(1));
            for (ResolveInfo r : pm.queryIntentActivities(live, 0)) if (isSystem(r.activityInfo.applicationInfo)) s.add(r.activityInfo.packageName);
        } catch (Exception ignored) { }
        try { // системные настройки (в т.ч. картинка/звук производителя)
            for (String a : new String[]{android.provider.Settings.ACTION_SETTINGS, android.provider.Settings.ACTION_DISPLAY_SETTINGS,
                    android.provider.Settings.ACTION_SOUND_SETTINGS, android.provider.Settings.ACTION_BLUETOOTH_SETTINGS})
                for (ResolveInfo r : pm.queryIntentActivities(new Intent(a), 0))
                    if (isSystem(r.activityInfo.applicationInfo)) s.add(r.activityInfo.packageName);
        } catch (Exception ignored) { }
        return s;
    }

    static boolean isSystem(ApplicationInfo ai) {
        return (ai.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
    }

    /** Служба пульта/Bluetooth и «вечные» системные приложения — тоже не трогаем. */
    private static boolean critical(PackageManager pm, ApplicationInfo ai) {
        if ((ai.flags & ApplicationInfo.FLAG_PERSISTENT) != 0) return true;
        if (!isSystem(ai)) return false;
        String n = ai.packageName.toLowerCase(java.util.Locale.ROOT);
        if (n.contains("rcu") || n.contains("remote") || n.contains("bluetooth") || n.contains("inputmethod")) return true;
        try {
            PackageInfo pi = pm.getPackageInfo(ai.packageName, PackageManager.GET_PERMISSIONS);
            if (pi.requestedPermissions != null)
                for (String perm : pi.requestedPermissions)
                    if ("android.permission.BLUETOOTH_PRIVILEGED".equals(perm) || "android.permission.TV_INPUT_HARDWARE".equals(perm))
                        return true;
        } catch (Exception ignored) { }
        return false;
    }

    /** Разрешение на статистику (когда открывали, сколько весит). Выдаётся через «Оптимизацию». */
    static boolean usageAllowed(Context c) {
        try {
            android.app.AppOpsManager ao = (android.app.AppOpsManager) c.getSystemService(Context.APP_OPS_SERVICE);
            return ao.checkOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), c.getPackageName())
                    == android.app.AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) { return false; }
    }

    /** Список кандидатов: приложения не с главного экрана. Сначала самые давно не открывавшиеся. Не из UI-потока. */
    static List<Item> list(Context c, List<Apps.App> all, List<String> dock) {
        if (!usageAllowed(c) && Adb.known(c)) {
            try { Adb.sh(c, "appops set " + c.getPackageName() + " GET_USAGE_STATS allow"); } catch (Exception e) { Net.log("junk appops", e); }
        }
        boolean stats = usageAllowed(c);
        Map<String, Long> used = new HashMap<>();
        if (stats) {
            try {
                android.app.usage.UsageStatsManager um = (android.app.usage.UsageStatsManager) c.getSystemService(Context.USAGE_STATS_SERVICE);
                long now = System.currentTimeMillis();
                for (Map.Entry<String, android.app.usage.UsageStats> e
                        : um.queryAndAggregateUsageStats(now - 2L * 365 * 86_400_000L, now).entrySet())
                    used.put(e.getKey(), e.getValue().getLastTimeUsed());
            } catch (Exception e) { Net.log("junk usage", e); }
        }
        PackageManager pm = c.getPackageManager();
        Set<String> guard = guarded(c);
        List<Item> out = new ArrayList<>();
        for (Apps.App a : all) {
            if (Apps.SETTINGS.equals(a.pkg) || dock.contains(a.pkg) || guard.contains(a.pkg)) continue;
            ApplicationInfo ai;
            try { ai = pm.getApplicationInfo(a.pkg, 0); } catch (Exception e) { continue; }
            if (critical(pm, ai)) continue;
            Item it = new Item();
            it.pkg = a.pkg;
            it.label = a.label;
            it.icon = a.icon;
            it.system = isSystem(ai);
            Long u = used.get(a.pkg);
            it.lastUsed = u == null || u < 86_400_000L * 365 * 10 ? 0 : u; // 1970 — значит не знаем
            it.size = size(c, ai, stats);
            out.add(it);
        }
        out.sort((x, y) -> x.lastUsed != y.lastUsed ? Long.compare(x.lastUsed, y.lastUsed) : Long.compare(y.size, x.size));
        return out;
    }

    private static long size(Context c, ApplicationInfo ai, boolean stats) {
        if (stats) {
            try {
                android.app.usage.StorageStatsManager sm = (android.app.usage.StorageStatsManager) c.getSystemService(Context.STORAGE_STATS_SERVICE);
                android.app.usage.StorageStats st = sm.queryStatsForPackage(android.os.storage.StorageManager.UUID_DEFAULT,
                        ai.packageName, android.os.Process.myUserHandle());
                return st.getAppBytes() + st.getDataBytes() + st.getCacheBytes();
            } catch (Exception ignored) { }
        }
        long n = new File(ai.sourceDir).length();
        if (ai.splitSourceDirs != null) for (String s : ai.splitSourceDirs) n += new File(s).length();
        return n;
    }

    static String usedText(long t) {
        if (t == 0) return "не открывалось";
        long d = (System.currentTimeMillis() - t) / 86_400_000L;
        if (d < 1) return "открывали сегодня";
        if (d < 2) return "открывали вчера";
        if (d < 30) return "не открывалось " + d + " " + plural(d, "день", "дня", "дней");
        long m = d / 30;
        if (m < 12) return "не открывалось " + m + " " + plural(m, "месяц", "месяца", "месяцев");
        return "не открывалось больше года";
    }

    private static String plural(long n, String one, String few, String many) {
        long a = n % 100, b = n % 10;
        if (a >= 11 && a <= 14) return many;
        if (b == 1) return one;
        if (b >= 2 && b <= 4) return few;
        return many;
    }

    // ---------- убрать / вернуть ----------

    static JSONArray removed(Context c) {
        try { return new JSONArray(p(c).getString("removed", "[]")); } catch (Exception e) { return new JSONArray(); }
    }

    private static void saveRemoved(Context c, JSONArray a) { p(c).edit().putString("removed", a.toString()).apply(); }

    /** Убрать одно приложение. Возвращает null или текст ошибки. Только с «Оптимизацией» (ADB). */
    static String remove(Context c, Item it) {
        try {
            PackageManager pm = c.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(it.pkg, 0);
            String r;
            boolean apk = !isSystem(ai);
            if (!apk) {
                r = Adb.sh(c, "pm disable-user --user 0 " + it.pkg);
                if (!r.contains("disabled")) return r.trim();
            } else {
                StringBuilder cmd = new StringBuilder("mkdir -p " + DIR + "/" + it.pkg + " && cp " + ai.sourceDir + " " + DIR + "/" + it.pkg + "/");
                if (ai.splitSourceDirs != null) for (String s : ai.splitSourceDirs) cmd.append(" && cp ").append(s).append(" ").append(DIR).append("/").append(it.pkg).append("/");
                cmd.append(" && echo COPIED");
                if (!Adb.sh(c, cmd.toString()).contains("COPIED")) return "не получилось сохранить апк";
                r = Adb.sh(c, "pm uninstall " + it.pkg);
                if (!r.contains("Success")) return r.trim();
            }
            JSONArray a = removed(c);
            a.put(new JSONObject().put("pkg", it.pkg).put("label", it.label).put("apk", apk));
            saveRemoved(c, a);
            return null;
        } catch (Exception e) { return e.getMessage(); }
    }

    /** Вернуть всё, что убирали через этот экран. Возвращает число вернувшихся. */
    static int restoreAll(Context c) {
        JSONArray a = removed(c), left = new JSONArray();
        int ok = 0;
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            String pkg = o.optString("pkg");
            try {
                String r;
                if (o.optBoolean("apk")) {
                    r = Adb.sh(c, "pm install -r " + DIR + "/" + pkg + "/*.apk");
                    if (r.contains("Success")) { ok++; continue; }
                } else {
                    r = Adb.sh(c, "pm enable " + pkg);
                    if (r.contains("enabled")) { ok++; continue; }
                }
                Net.log("junk restore " + pkg + ": " + r, null);
            } catch (Exception e) { Net.log("junk restore " + pkg, e); }
            left.put(o);
        }
        saveRemoved(c, left);
        return ok;
    }
}
