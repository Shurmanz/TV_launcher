package ru.alex.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.provider.Settings;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Список приложений и содержимое дока. */
final class Apps {
    static final String SETTINGS = "__settings__";

    static final String[] DEFAULT_DOCK = {
            "com.google.android.youtube.tv",
            "ru.yourok.num",
            "mobi.zona",
            "com.vk.tv",
    };

    static final class App {
        final String pkg;
        final String label;
        final Intent launch;
        final Drawable banner; // может быть null
        final Drawable icon;

        App(String pkg, String label, Intent launch, Drawable banner, Drawable icon) {
            this.pkg = pkg;
            this.label = label;
            this.launch = launch;
            this.banner = banner;
            this.icon = icon;
        }
    }

    private final Context ctx;
    private final PackageManager pm;
    private final SharedPreferences prefs;

    Apps(Context ctx) {
        this.ctx = ctx;
        pm = ctx.getPackageManager();
        prefs = ctx.getSharedPreferences("dock", Context.MODE_PRIVATE);
    }

    /** Все запускаемые приложения (TV-лаунчер + обычные), по алфавиту, без самого «Дома». */
    List<App> all() {
        Map<String, App> map = new LinkedHashMap<>();
        collect(map, Intent.CATEGORY_LEANBACK_LAUNCHER);
        collect(map, Intent.CATEGORY_LAUNCHER);
        map.remove(ctx.getPackageName());
        List<App> list = new ArrayList<>(map.values());
        Collator col = Collator.getInstance(new Locale("ru"));
        list.sort((a, b) -> col.compare(a.label, b.label));
        // свой ярлык «Настройки» — только если системного приложения настроек нет (обычно оно есть)
        if (!map.containsKey("com.android.tv.settings")) list.add(0, settingsApp());
        return list;
    }

    private void collect(Map<String, App> map, String category) {
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(category);
        for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
            ActivityInfo ai = ri.activityInfo;
            if (map.containsKey(ai.packageName)) continue;
            Intent launch = new Intent(Intent.ACTION_MAIN).addCategory(category)
                    .setComponent(new ComponentName(ai.packageName, ai.name))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            Drawable banner = ai.loadBanner(pm);
            if (banner == null) banner = ai.applicationInfo.loadBanner(pm);
            map.put(ai.packageName, new App(ai.packageName, String.valueOf(ri.loadLabel(pm)), launch,
                    banner, ri.loadIcon(pm)));
        }
    }

    private App settingsApp() {
        Intent i = new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Drawable icon = null;
        try {
            icon = pm.getApplicationIcon("com.android.tv.settings");
        } catch (PackageManager.NameNotFoundException ignored) {
            icon = ctx.getDrawable(android.R.drawable.ic_menu_preferences);
        }
        return new App(SETTINGS, "Настройки", i, null, icon);
    }

    List<String> dockPackages() {
        String s = prefs.getString("pkgs", null);
        if (s == null) return new ArrayList<>(Arrays.asList(DEFAULT_DOCK));
        List<String> l = new ArrayList<>();
        for (String p : s.split(",")) if (!p.isEmpty()) l.add(p);
        return l;
    }

    void saveDock(List<String> pkgs) {
        prefs.edit().putString("pkgs", String.join(",", pkgs)).apply();
    }

    /** Док в виде App, пропуская удалённые приложения. */
    List<App> dock(List<App> all) {
        List<App> res = new ArrayList<>();
        for (String p : dockPackages()) {
            for (App a : all) if (a.pkg.equals(p)) { res.add(a); break; }
        }
        return res;
    }
}
