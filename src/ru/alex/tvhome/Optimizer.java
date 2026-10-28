package ru.alex.tvhome;

import android.content.Context;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Оптимизация телевизора через встроенный ADB: отключение рекламы и лишних приложений,
 * резервные копии состояния и возврат «как было», настройка разрешений Slauncher одной кнопкой.
 * Системные приложения только отключаются (pm disable-user) — их всегда можно включить обратно.
 * Сторонние приложения удаляются, но их установочные файлы сначала сохраняются в резервную копию.
 */
final class Optimizer {
    private Optimizer() { }

    static final String BACKUP_ROOT = "/sdcard/Slauncher/backups";

    /** Каталог известного «мусора». Показываются только пакеты, которые есть на этом устройстве. */
    static final class Item {
        final String pkg, name, group;
        final boolean recommended;
        Item(String pkg, String name, String group, boolean rec) { this.pkg = pkg; this.name = name; this.group = group; this.recommended = rec; }
    }

    static final String G_GOOGLE = "Google — на любом телевизоре", G_APPS = "Чужие сервисы и приложения",
            G_TCL = "TCL", G_XIAOMI = "Xiaomi";

    static final Item[] CATALOG = {
            // Google: одинаково на любом Android TV / Google TV
            new Item("com.google.android.feedback", "Отчёты Google", G_GOOGLE, true),
            new Item("com.google.android.partnersetup", "Партнёрские настройки Google", G_GOOGLE, true),
            new Item("android.autoinstalls.config.google.gtvpai", "Автоустановка партнёрских приложений", G_GOOGLE, true),
            new Item("com.google.android.tvrecommendations", "Рекомендации Android TV", G_GOOGLE, true),
            new Item("com.google.android.leanbacklauncher.recommendations", "Рекомендации Android TV", G_GOOGLE, true),
            new Item("com.google.android.videos", "Google TV: фильмы", G_GOOGLE, true),
            new Item("com.google.android.play.games", "Google Play Игры", G_GOOGLE, true),
            new Item("com.google.android.youtube.tvmusic", "YouTube Music", G_GOOGLE, true),
            new Item("com.google.android.music", "Google Play Музыка", G_GOOGLE, true),
            new Item("com.google.android.apps.tv.dreamx", "Заставка Google (её заменяет заставка Slauncher)", G_GOOGLE, true),
            new Item("com.google.android.backdrop", "Заставка Google", G_GOOGLE, true),
            new Item("com.google.android.marvin.talkback", "TalkBack (озвучка экрана)", G_GOOGLE, true),
            new Item("com.google.android.syncadapters.calendar", "Синхронизация календаря", G_GOOGLE, true),
            new Item("com.google.android.onetimeinitializer", "Первичная настройка Google", G_GOOGLE, true),
            new Item("com.android.printspooler", "Печать", G_GOOGLE, true),
            new Item("com.android.hotspot2.osulogin", "Вход в сети Passpoint", G_GOOGLE, true),
            new Item("com.android.wallpaperbackup", "Резервные копии обоев", G_GOOGLE, true),
            new Item("com.google.android.apps.mediashell", "Chromecast (трансляция с телефона)", G_GOOGLE, false),
            // чужие сервисы
            new Item("com.netflix.ninja", "Netflix", G_APPS, true),
            new Item("com.amazon.amazonvideo.livingroom", "Prime Video", G_APPS, true),
            new Item("com.tcl.smartalexa", "Alexa", G_APPS, true),
            new Item("au.com.stan.and", "Stan", G_APPS, true),
            new Item("jp.unext.tv.player", "U-NEXT", G_APPS, true),
            new Item("air.jp.co.fujitv.fodviewer.tv", "FOD", G_APPS, true),
            new Item("tv.abema", "ABEMA", G_APPS, true),
            new Item("tv.wuaki.apptv", "Rakuten TV", G_APPS, true),
            new Item("jp.happyon.android", "Hulu Japan", G_APPS, true),
            // TCL
            new Item("com.tcl.waterfall.overseas", "Рекламная лента TCL", G_TCL, true),
            new Item("com.tcl.channelplus", "TCL Channel+ (реклама каналов)", G_TCL, true),
            new Item("com.tcl.esticker", "Рекламные наклейки TCL", G_TCL, true),
            new Item("com.tcl.messagebox", "Сообщения TCL", G_TCL, true),
            new Item("com.tcl.guard", "TCL Guard", G_TCL, true),
            new Item("com.tcl.logkit", "Сбор журналов TCL", G_TCL, true),
            new Item("com.tcl.dashboard", "Панель TCL", G_TCL, true),
            new Item("com.tcl.tv.tclhome_passive", "Рекомендации TCL Home", G_TCL, true),
            new Item("com.tcl.partnercustomizer", "Партнёрские настройки TCL", G_TCL, true),
            new Item("com.tcl.browser", "Браузер TCL", G_TCL, true),
            new Item("com.tcl.gallery", "Галерея TCL", G_TCL, true),
            new Item("com.tcl.ui_mediaCenter", "Медиацентр TCL", G_TCL, true),
            new Item("com.tcl.gamebar", "Игровая панель TCL", G_TCL, true),
            new Item("com.tcl.ocean.instructions", "Инструкция TCL", G_TCL, true),
            new Item("com.tcl.airplay2", "AirPlay TCL", G_TCL, true),
            new Item("com.mediatek.AirplayAPK", "AirPlay MediaTek", G_TCL, true),
            new Item("com.tcl.hearaid", "Слуховой помощник TCL", G_TCL, true),
            new Item("com.tcl.android.webview", "WebView TCL (есть WebView Google)", G_TCL, true),
            new Item("com.tcl.notereminder", "Напоминания TCL", G_TCL, false),
            new Item("com.tcl.usercenter", "Аккаунт TCL", G_TCL, false),
            // Xiaomi (дополнится по реальному списку с приставки)
            new Item("com.xiaomi.mitv.tvpush", "Реклама Xiaomi", G_XIAOMI, true),
            new Item("com.xiaomi.mitv.advertise", "Реклама Xiaomi", G_XIAOMI, true),
            new Item("com.miui.systemAdSolution", "Реклама Xiaomi", G_XIAOMI, true),
    };

    // ---------- Google TV Launcher ----------

    static final String GTV = "com.google.android.apps.tv.launcherx", GTV_SETUP = "com.google.android.tungsten.setupwraith";

    // ---------- штатный главный экран (Google TV / Android TV Home / PatchWall Xiaomi …) ----------

    /** Известные «главные экраны» — на случай, если отключили без списка (версии до 1.11). */
    static final String[] KNOWN_HOMES = {GTV, "com.google.android.tvlauncher", "com.google.android.leanbacklauncher",
            "com.mitv.tvhome", "com.xiaomi.mitv.tvhome"};
    private static final java.util.Set<String> NOT_HOMES = new java.util.HashSet<>(java.util.Arrays.asList(
            "com.android.tv.settings", "com.android.settings", GTV_SETUP));

    /** Включённые сейчас чужие главные экраны. */
    static List<String> stockHomes(Context c) {
        List<String> r = new ArrayList<>();
        android.content.Intent h = new android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME);
        for (android.content.pm.ResolveInfo ri : c.getPackageManager().queryIntentActivities(h, 0)) {
            String p = ri.activityInfo.packageName;
            if (p.equals(c.getPackageName()) || NOT_HOMES.contains(p) || r.contains(p)) continue;
            r.add(p);
        }
        return r;
    }

    /** Что отключали мы (для точного возврата). */
    static List<String> homesOff(Context c) {
        List<String> r = new ArrayList<>();
        for (String p : Adb.prefs(c).getString("homes_off", "").split(",")) if (!p.isEmpty()) r.add(p);
        return r;
    }

    static boolean homeOff(Context c, State s) {
        for (String p : homesOff(c)) if (s.of(p) == St.DISABLED) return true;
        for (String p : KNOWN_HOMES) if (s.of(p) == St.DISABLED) return true;
        return false;
    }

    static boolean hasStockHome(Context c, State s) { return homeOff(c, s) || !stockHomes(c).isEmpty(); }

    /** Название штатного экрана для кнопок: «Google TV», «PatchWall», «Android TV Home»… */
    static String homeName(Context c, State s) {
        List<String> l = homeOff(c, s) ? homesOff(c) : stockHomes(c);
        if (l.isEmpty()) for (String p : KNOWN_HOMES) if (s.of(p) == St.DISABLED) { l.add(p); break; }
        if (l.isEmpty()) return "главный экран";
        String p = l.get(0);
        if (p.equals(GTV)) return "Google TV";
        if (p.contains("mitv")) return "PatchWall";
        if (p.contains("tvlauncher") || p.contains("leanback")) return "Android TV Home";
        try {
            android.content.pm.PackageManager pm = c.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(p, android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS)).toString();
        } catch (Exception e) { return "главный экран"; }
    }

    /**
     * Отключить штатный главный экран (и мастер setupwraith — он иначе включает его обратно).
     * Домашним экраном становится Slauncher. Список отключённого запоминается для «Вернуть».
     */
    static void homeOff(Context c) throws Exception {
        List<String> homes = stockHomes(c);
        if (homes.isEmpty()) return;
        State s = read(c);
        List<String> off = new ArrayList<>(homes);
        List<String> cmds = new ArrayList<>();
        cmds.add("cmd package set-home-activity " + c.getPackageName() + "/.HomeActivity");
        if (s.of(GTV_SETUP) == St.ENABLED) { cmds.add("pm disable-user --user 0 " + GTV_SETUP); off.add(GTV_SETUP); }
        for (String h : homes) cmds.add("pm disable-user --user 0 " + h);
        Adb.prefs(c).edit().putString("homes_off", String.join(",", off)).putBoolean("dirty", true).commit();
        runBatch(c, cmds);
    }

    /** Вернуть штатный главный экран. */
    static void homeOn(Context c) throws Exception {
        java.util.Set<String> all = new java.util.LinkedHashSet<>(homesOff(c));
        State s = read(c);
        for (String p : KNOWN_HOMES) if (s.of(p) == St.DISABLED) all.add(p);
        if (s.of(GTV_SETUP) == St.DISABLED) all.add(GTV_SETUP);
        List<String> cmds = new ArrayList<>();
        for (String p : all) cmds.add("pm enable " + p);
        runBatch(c, cmds);
        Adb.prefs(c).edit().remove("homes_off").putBoolean("dirty", true).apply();
    }

    /**
     * Сохранённая сеть Wi‑Fi добавлена мастером первичной настройки Google (setupwraith)?
     * Тогда при его отключении Android удалит и сеть — пароль придётся вводить заново (так было на TCL 28.10).
     */
    /**
     * Вернуть телевизор «как было» и удалить Slauncher: Google TV, состояние приложений из первой копии,
     * анимации, служба «Домой» и заставка, затем pm uninstall (на нём процесс Slauncher завершится).
     */
    static void uninstallAll(Context c, String firstBackup) throws Exception {
        if (firstBackup != null) restore(c, firstBackup);
        State s = read(c);
        if (homeOff(c, s)) homeOn(c); // после копии: штатный главный экран включён в любом случае
        String me = c.getPackageName();
        String svc = new android.content.ComponentName(c, HomeKeyService.class).flattenToString();
        String acc = Adb.sh(c, "settings get secure enabled_accessibility_services").trim();
        StringBuilder rest = new StringBuilder();
        for (String a : acc.split(":")) {
            android.content.ComponentName cn = android.content.ComponentName.unflattenFromString(a);
            if (a.isEmpty() || a.equals("null") || (cn != null && cn.flattenToString().equalsIgnoreCase(svc))) continue;
            if (rest.length() > 0) rest.append(':');
            rest.append(a);
        }
        List<String> cmds = new ArrayList<>();
        cmds.add(rest.length() == 0 ? "settings delete secure enabled_accessibility_services"
                : "settings put secure enabled_accessibility_services " + Adb.q(rest.toString()));
        String dream = Adb.sh(c, "settings get secure screensaver_components").trim();
        if (dream.startsWith(me)) {
            cmds.add(s.of("com.google.android.apps.tv.dreamx") != St.ABSENT
                    ? "pm enable com.google.android.apps.tv.dreamx; settings put secure screensaver_components com.google.android.apps.tv.dreamx/.service.Backdrop"
                    : "settings delete secure screensaver_components");
        }
        runBatch(c, cmds);
        Adb.sh(c, "pm uninstall " + me);
    }

    static boolean wifiFromSetup(Context c) throws Exception {
        String r = Adb.sh(c, "dumpsys wifi | sed -n '/Configured networks Begin/,/Configured networks End/p' | grep -c 'cname="
                + GTV_SETUP + "'");
        try { return Integer.parseInt(r.trim()) > 0; } catch (Exception e) { return false; }
    }


    /** Состояние пакета на устройстве. */
    enum St { ENABLED, DISABLED, REMOVED, ABSENT }

    static final class State {
        final Map<String, St> pkgs = new LinkedHashMap<>();
        final List<String> third = new ArrayList<>();
        String anim = "";
        St of(String p) { St s = pkgs.get(p); return s == null ? St.ABSENT : s; }
    }

    private static List<String> lines(String out) {
        List<String> r = new ArrayList<>();
        for (String l : out.split("\n")) {
            l = l.trim();
            if (l.startsWith("package:")) r.add(l.substring(8));
        }
        return r;
    }

    /** Прочитать текущее состояние всех пакетов (одна команда). */
    static State read(Context c) throws Exception {
        String out = Adb.sh(c, "echo @U; pm list packages -u; echo @I; pm list packages; echo @D; pm list packages -d; "
                + "echo @T; pm list packages -3; echo @A; settings get global window_animation_scale");
        Map<String, String> part = new HashMap<>();
        String key = null;
        StringBuilder sb = new StringBuilder();
        for (String l : out.split("\n")) {
            if (l.startsWith("@")) { if (key != null) part.put(key, sb.toString()); key = l.trim(); sb.setLength(0); }
            else sb.append(l).append('\n');
        }
        if (key != null) part.put(key, sb.toString());
        State s = new State();
        java.util.Set<String> inst = new java.util.HashSet<>(lines(part.getOrDefault("@I", "")));
        java.util.Set<String> dis = new java.util.HashSet<>(lines(part.getOrDefault("@D", "")));
        for (String p : lines(part.getOrDefault("@U", "")))
            s.pkgs.put(p, !inst.contains(p) ? St.REMOVED : dis.contains(p) ? St.DISABLED : St.ENABLED);
        for (String p : lines(part.getOrDefault("@T", ""))) if (!p.equals(c.getPackageName())) s.third.add(p);
        s.anim = part.getOrDefault("@A", "").trim();
        return s;
    }

    // ---------- резервные копии ----------

    static boolean backupNeeded(Context c) { return Adb.prefs(c).getBoolean("dirty", true); }

    static String lastBackup(Context c) { return Adb.prefs(c).getString("last_backup", ""); }

    /**
     * Сохранить состояние в /sdcard/Slauncher/backups/<дата>/: state.txt (enabled/disabled/removed пакет),
     * установочные файлы сторонних приложений из saveApks и restore.sh для ручного возврата через adb.
     */
    static String backup(Context c, State s, List<String> saveApks) throws Exception {
        String name = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(new Date());
        String dir = BACKUP_ROOT + "/" + name;
        StringBuilder st = new StringBuilder();
        for (Map.Entry<String, St> e : s.pkgs.entrySet())
            st.append(e.getValue().name().toLowerCase(Locale.US)).append(' ').append(e.getKey()).append('\n');
        st.append("anim ").append(s.anim).append('\n');
        File tmp = new File(c.getExternalFilesDir(null), "state.txt");
        File script = new File(c.getExternalFilesDir(null), "restore.sh");
        write(tmp, st.toString());
        write(script, RESTORE_SH);
        String r = Adb.sh(c, "mkdir -p " + dir + "/apk && cp " + tmp.getPath() + " " + dir + "/state.txt && cp "
                + script.getPath() + " " + dir + "/restore.sh && echo OK");
        if (!r.contains("OK")) throw new Exception("не удалось записать копию: " + r.trim());
        if (saveApks != null) for (String p : saveApks) saveApk(c, dir, p);
        Adb.prefs(c).edit().putBoolean("dirty", false).putString("last_backup", name).putString("last_dir", dir).apply();
        return dir;
    }

    /** Скопировать установочные файлы приложения (включая split-APK) в папку копии. */
    static void saveApk(Context c, String dir, String pkg) throws Exception {
        String r = Adb.sh(c, "d=" + dir + "/apk/" + pkg + "; mkdir -p $d; for f in $(pm path " + pkg
                + " | cut -d: -f2); do cp $f $d/ || exit 1; done; ls $d | wc -l");
        int n;
        try { n = Integer.parseInt(r.trim().replaceAll("\\D+", "")); } catch (Exception e) { n = 0; }
        if (n == 0) throw new Exception("не удалось сохранить " + pkg);
    }

    /** Ручной возврат: adb shell sh /sdcard/Slauncher/backups/<дата>/restore.sh */
    static final String RESTORE_SH = "#!/system/bin/sh\n"
            + "# Возврат состояния приложений из этой копии Slauncher: adb shell sh <папка>/restore.sh\n"
            + "d=$(dirname \"$0\")\n"
            + "for a in \"$d\"/apk/*/; do [ -d \"$a\" ] || continue; p=$(basename \"$a\");\n"
            + "  pm path \"$p\" >/dev/null 2>&1 && continue\n"
            + "  t=/data/local/tmp/slr; rm -rf $t; mkdir -p $t; cp \"$a\"*.apk $t/\n"
            + "  sid=$(pm install-create -r | grep -o '[0-9][0-9]*'); for f in $t/*.apk; do\n"
            + "    pm install-write -S $(stat -c %s \"$f\") $sid $(basename \"$f\") \"$f\" >/dev/null; done; pm install-commit $sid; rm -rf $t; done\n"
            + "while read st p; do case $st in\n"
            + "  enabled) cmd package install-existing \"$p\" >/dev/null 2>&1; pm enable \"$p\" >/dev/null 2>&1;;\n"
            + "  disabled) cmd package install-existing \"$p\" >/dev/null 2>&1; pm disable-user --user 0 \"$p\" >/dev/null 2>&1;;\n"
            + "  anim) settings put global window_animation_scale $p; settings put global transition_animation_scale $p;"
            + " settings put global animator_duration_scale $p;;\n"
            + "esac; done < \"$d/state.txt\"\necho done\n";

    private static void write(File f, String s) throws Exception {
        try (java.io.FileOutputStream o = new java.io.FileOutputStream(f)) { o.write(s.getBytes("UTF-8")); }
    }

    static List<String> listBackups(Context c) throws Exception {
        List<String> r = new ArrayList<>();
        for (String l : Adb.sh(c, "ls -1 " + BACKUP_ROOT + " 2>/dev/null").split("\n")) if (!l.trim().isEmpty()) r.add(l.trim());
        java.util.Collections.sort(r, java.util.Collections.reverseOrder());
        return r;
    }

    static String pretty(String name) {
        try {
            Date d = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).parse(name);
            return new SimpleDateFormat("d MMMM, HH:mm", new Locale("ru")).format(d);
        } catch (Exception e) { return name; }
    }

    /** Вернуть состояние из копии: только различия, быстро. Возвращает число изменений. */
    static int restore(Context c, String name) throws Exception {
        String dir = BACKUP_ROOT + "/" + name;
        String saved = Adb.sh(c, "cat " + dir + "/state.txt; echo @APK; ls -1 " + dir + "/apk 2>/dev/null");
        State now = read(c);
        List<String> cmds = new ArrayList<>();
        boolean apkPart = false;
        for (String l : saved.split("\n")) {
            l = l.trim();
            if (l.isEmpty()) continue;
            if (l.equals("@APK")) { apkPart = true; continue; }
            if (apkPart) {
                if (now.of(l) == St.ABSENT) cmds.add(installCmd(dir + "/apk/" + l));
                continue;
            }
            int sp = l.indexOf(' ');
            if (sp < 0) continue;
            String st = l.substring(0, sp), p = l.substring(sp + 1);
            if (st.equals("anim")) {
                if (!p.isEmpty() && !p.equals("null") && !p.equals(now.anim)) cmds.add(animCmd(p));
                continue;
            }
            if (p.equals(c.getPackageName())) continue;
            St cur = now.of(p);
            if (cur == St.ABSENT) continue;
            switch (st) {
                case "enabled":
                    if (cur == St.REMOVED) cmds.add("cmd package install-existing " + p);
                    if (cur == St.DISABLED || cur == St.REMOVED) cmds.add("pm enable " + p);
                    break;
                case "disabled":
                    if (cur == St.REMOVED) cmds.add("cmd package install-existing " + p);
                    if (cur != St.DISABLED) cmds.add("pm disable-user --user 0 " + p);
                    break;
                case "removed":
                    if (cur != St.REMOVED && !now.third.contains(p)) cmds.add("pm uninstall -k --user 0 " + p);
                    break;
                default:
                    break;
            }
        }
        runBatch(c, cmds);
        Adb.prefs(c).edit().putBoolean("dirty", true).apply();
        return cmds.size();
    }

    static String installCmd(String apkDir) {
        // pm не читает файлы с /sdcard — сначала копия в /data/local/tmp
        return "t=/data/local/tmp/slr; rm -rf $t; mkdir -p $t; cp " + apkDir + "/*.apk $t/; "
                + "sid=$(pm install-create -r | grep -o '[0-9][0-9]*'); for f in $t/*.apk; do "
                + "pm install-write -S $(stat -c %s $f) $sid $(basename $f) $f >/dev/null; done; pm install-commit $sid; rm -rf $t";
    }

    static String animCmd(String v) {
        return "settings put global window_animation_scale " + v + "; settings put global transition_animation_scale " + v
                + "; settings put global animator_duration_scale " + v;
    }

    /** Выполнить команды пачками (ограничение длины строки ADB). */
    static void runBatch(Context c, List<String> cmds) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (String cmd : cmds) {
            if (sb.length() + cmd.length() > 3000) { Adb.sh(c, sb.toString()); sb.setLength(0); }
            if (sb.length() > 0) sb.append("; ");
            sb.append(cmd).append(" >/dev/null 2>&1");
        }
        if (sb.length() > 0) Adb.sh(c, sb.toString());
    }

    // ---------- действия ----------

    static void disable(Context c, String p) throws Exception {
        Adb.sh(c, "pm disable-user --user 0 " + p);
        Adb.prefs(c).edit().putBoolean("dirty", true).apply();
    }

    static void enable(Context c, String p, St cur) throws Exception {
        Adb.sh(c, (cur == St.REMOVED ? "cmd package install-existing " + p + "; " : "") + "pm enable " + p);
        Adb.prefs(c).edit().putBoolean("dirty", true).apply();
    }

    /** Удалить стороннее приложение: установочные файлы обязательно сохраняются в последнюю копию. */
    static void uninstall(Context c, String p) throws Exception {
        String dir = Adb.prefs(c).getString("last_dir", "");
        if (dir.isEmpty()) dir = backup(c, read(c), null);
        saveApk(c, dir, p);
        String r = Adb.sh(c, "pm uninstall " + p);
        if (!r.contains("Success")) throw new Exception(r.trim());
        Adb.prefs(c).edit().putBoolean("dirty", true).apply();
    }

    static void setAnim(Context c, String v) throws Exception {
        Adb.sh(c, animCmd(v));
        Adb.prefs(c).edit().putBoolean("dirty", true).apply();
    }

    /** Все разрешения Slauncher одной командой. Возвращает вывод (для журнала). */
    static String fixAll(Context c) throws Exception {
        String me = c.getPackageName();
        List<String> cmds = new ArrayList<>();
        // Пишем только то, чего нет: перезапись списка служб перезапускает службу «Домой» и закрывает экран
        if (!SetupActivity.homeServiceOn(c)) {
            String svc = new android.content.ComponentName(c, HomeKeyService.class).flattenToString();
            String cur = Adb.sh(c, "settings get secure enabled_accessibility_services").trim();
            String acc = (cur.isEmpty() || cur.equals("null")) ? svc : cur + ":" + svc;
            cmds.add("settings put secure enabled_accessibility_services " + Adb.q(acc));
            cmds.add("settings put secure accessibility_enabled 1");
        }
        // appops меняем только если ещё не «allow»: смена REQUEST_INSTALL_PACKAGES перезапускает Slauncher
        String ops = Adb.sh(c, "appops get " + me);
        if (!android.provider.Settings.canDrawOverlays(c)) cmds.add("appops set " + me + " SYSTEM_ALERT_WINDOW allow");
        if (ops.contains("APP_AUTO_START") && !ops.matches("(?s).*APP_AUTO_START: allow.*"))
            cmds.add("appops set " + me + " APP_AUTO_START allow");
        if (c.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            cmds.add("pm grant " + me + " android.permission.ACCESS_FINE_LOCATION");
        if (c.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") != android.content.pm.PackageManager.PERMISSION_GRANTED)
            cmds.add("pm grant " + me + " android.permission.WRITE_SECURE_SETTINGS");
        if (!SetupActivity.dreamOn(c)) {
            cmds.add("settings put secure screensaver_components " + me + "/.NatureDream");
            cmds.add("settings put secure screensaver_enabled 1");
        }
        if (!Updater.canInstall(c)) cmds.add("appops set " + me + " REQUEST_INSTALL_PACKAGES allow"); // последним
        c.getSharedPreferences(SetupActivity.PREF, Context.MODE_PRIVATE).edit().putBoolean("tcl_autostart", true).commit();
        runBatch(c, cmds);
        return String.valueOf(cmds.size());
    }

    /**
     * Тихое обновление: APK уже скачан; ставим через оболочку. Фоновый процесс adbd убивает при закрытии
     * соединения (проверено на TCL), поэтому pm install выполняется синхронно — Slauncher при этом
     * перезапускается системой, а BootReceiver по MY_PACKAGE_REPLACED открывает главный экран.
     */
    static void silentInstall(Context c, File apk) throws Exception {
        String tmp = "/data/local/tmp/slauncher_update.apk";
        String r = Adb.sh(c, "cp " + apk.getPath() + " " + tmp + " && echo OK");
        if (!r.contains("OK")) throw new Exception("нет доступа к файлу обновления");
        c.getSharedPreferences("update", Context.MODE_PRIVATE).edit().putBoolean("relaunch", true).commit();
        String out;
        try { out = Adb.sh(c, "pm install -r " + tmp + " 2>&1; rm -f " + tmp); }
        catch (java.io.IOException e) { return; } // соединение оборвалось — установка уже идёт
        if (!out.contains("Success")) {
            c.getSharedPreferences("update", Context.MODE_PRIVATE).edit().remove("relaunch").commit();
            throw new Exception(out.trim());
        }
    }
}
