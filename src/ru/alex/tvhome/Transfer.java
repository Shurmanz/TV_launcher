package ru.alex.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

/**
 * Перенос настроек Slauncher: JSON-файл (плитки, скрытые фото, город, стартовое приложение, анимации,
 * отключённые пакеты, Home Assistant без токена). Хранится в памяти ТВ: Slauncher/settings/.
 * С телефона: http://<IP>:8098/<PIN> — скачать файл с этого ТВ или загрузить на него.
 */
final class Transfer {
    private Transfer() { }

    static final String DIR = "/sdcard/Slauncher/settings";
    static final int PORT = 8098;

    // ---------- что входит в файл ----------

    static JSONObject export(Context c) throws Exception {
        JSONObject o = new JSONObject();
        o.put("app", "Slauncher").put("version", Updater.myName(c))
                .put("created", new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date()));
        o.put("dock", new JSONArray(new Apps(c).dockPackages()));
        o.put("photos_hidden", new JSONArray(c.getSharedPreferences("walls", Context.MODE_PRIVATE)
                .getStringSet("hidden", Collections.emptySet())));
        SharedPreferences loc = Loc.p(c);
        o.put("city", new JSONObject().put("auto", Loc.auto(c)).put("name", Loc.name(c))
                .put("lat", loc.getFloat("lat", 59.127f)).put("lon", loc.getFloat("lon", 37.909f)));
        o.put("start_app", Comfort.startPkg(c));
        String anim = Settings.Global.getString(c.getContentResolver(), Settings.Global.WINDOW_ANIMATION_SCALE);
        o.put("animations", anim == null ? "1.0" : anim);
        PackageManager pm = c.getPackageManager();
        JSONArray off = new JSONArray();
        Set<String> seen = new HashSet<>();
        for (Optimizer.Item i : Optimizer.CATALOG) if (seen.add(i.pkg) && isOff(pm, i.pkg)) off.put(i.pkg);
        o.put("disabled_apps", off);
        boolean homeOff = !Optimizer.homesOff(c).isEmpty();
        for (String h : Optimizer.KNOWN_HOMES) homeOff |= isOff(pm, h);
        o.put("google_tv_off", homeOff); // = «штатный главный экран отключён» (имя поля — для совместимости)
        o.put("home_assistant", new JSONObject().put("url", HaConfig.url(c))
                .put("rooms", HaConfig.rooms(c)).put("events", HaConfig.events(c)).put("climate", HaConfig.climate(c)));
        return o;
    }

    private static boolean isOff(PackageManager pm, String pkg) {
        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES | PackageManager.MATCH_DISABLED_COMPONENTS);
            if ((ai.flags & ApplicationInfo.FLAG_INSTALLED) == 0) return true;
            int st = pm.getApplicationEnabledSetting(pkg);
            return st == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER || st == PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        } catch (Exception e) { return false; }
    }

    /** Что ещё предложить после загрузки (нужна «Оптимизация»). */
    static final class Applied {
        final List<String> toDisable = new ArrayList<>();
        String anim;
        boolean googleTvOff;
        String from;
    }

    /** Применить файл: плитки, фото, город, старт, HA сразу; пакеты/анимации/Google TV — вернуть для подтверждения. */
    static Applied apply(Context c, JSONObject o) throws Exception {
        if (!"Slauncher".equals(o.optString("app"))) throw new Exception("это не файл настроек Slauncher");
        Applied a = new Applied();
        a.from = o.optString("created");
        JSONArray dock = o.optJSONArray("dock");
        if (dock != null) {
            List<String> l = new ArrayList<>();
            for (int i = 0; i < dock.length(); i++) l.add(dock.optString(i));
            new Apps(c).saveDock(l);
        }
        JSONArray ph = o.optJSONArray("photos_hidden");
        if (ph != null) {
            Set<String> h = new HashSet<>();
            for (int i = 0; i < ph.length(); i++) h.add(ph.optString(i));
            c.getSharedPreferences("walls", Context.MODE_PRIVATE).edit().putStringSet("hidden", h).apply();
        }
        JSONObject city = o.optJSONObject("city");
        if (city != null) {
            if (city.optBoolean("auto", true)) Loc.setAuto(c);
            else Loc.setManual(c, new Loc.City(city.optString("name"), "", city.optDouble("lat"), city.optDouble("lon")));
        }
        String start = o.optString("start_app", "");
        if (start.isEmpty() || Comfort.launchIntent(c, start) != null) Comfort.setStartPkg(c, start);
        JSONObject ha = o.optJSONObject("home_assistant");
        if (ha != null && !ha.optString("url").isEmpty()) {
            boolean sameServer = ha.optString("url").equals(HaConfig.url(c)) && !HaConfig.token(c).isEmpty();
            HaConfig.saveAuth(c, ha.optString("url"), null);
            HaConfig.saveEntities(c, ha.optJSONArray("rooms") == null ? new JSONArray() : ha.optJSONArray("rooms"),
                    ha.optJSONArray("events") == null ? new JSONArray() : ha.optJSONArray("events"),
                    ha.optJSONArray("climate") == null ? new JSONArray() : ha.optJSONArray("climate"));
            HaConfig.setEnabled(c, sameServer); // на другом ТВ токен вводится заново: Home Assistant → «Настроить с телефона»
            HaClient.restart(c);
        }
        PackageManager pm = c.getPackageManager();
        JSONArray off = o.optJSONArray("disabled_apps");
        if (off != null) for (int i = 0; i < off.length(); i++) {
            String p = off.optString(i);
            try { pm.getApplicationInfo(p, 0); if (!isOff(pm, p)) a.toDisable.add(p); } catch (Exception ignored) { }
        }
        String anim = o.optString("animations", "");
        String cur = Settings.Global.getString(c.getContentResolver(), Settings.Global.WINDOW_ANIMATION_SCALE);
        if (!anim.isEmpty() && !anim.equals(cur == null ? "1.0" : cur)) a.anim = anim;
        a.googleTvOff = o.optBoolean("google_tv_off") && !Optimizer.stockHomes(c).isEmpty();
        return a;
    }

    // ---------- файлы ----------

    static String stamp() { return new SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(new Date()); }

    private static File localDir(Context c) {
        File d = new File(c.getExternalFilesDir(null), "settings");
        d.mkdirs();
        return d;
    }

    /** Сохранить в память ТВ. Возвращает имя файла. */
    static String save(Context c) throws Exception {
        String name = "slauncher-" + stamp() + ".json";
        File f = new File(localDir(c), name);
        write(f, export(c).toString(2));
        if (Adb.known(c)) { // общая папка в памяти ТВ — видна файловым менеджерам и переживает удаление Slauncher
            try { Adb.sh(c, "mkdir -p " + DIR + " && cp " + f.getPath() + " " + DIR + "/"); } catch (Exception ignored) { }
        }
        return name;
    }

    static List<String> list(Context c) {
        Set<String> names = new HashSet<>();
        File[] fs = localDir(c).listFiles();
        if (fs != null) for (File f : fs) if (f.getName().endsWith(".json")) names.add(f.getName());
        if (Adb.known(c)) {
            try {
                for (String l : Adb.sh(c, "ls -1 " + DIR + " 2>/dev/null").split("\n"))
                    if (l.trim().endsWith(".json")) names.add(l.trim());
            } catch (Exception ignored) { }
        }
        List<String> l = new ArrayList<>(names);
        Collections.sort(l, Collections.reverseOrder());
        return l;
    }

    static JSONObject read(Context c, String name) throws Exception {
        File f = new File(localDir(c), name);
        if (f.exists()) return new JSONObject(readAll(new FileInputStream(f)));
        return new JSONObject(Adb.sh(c, "cat " + DIR + "/" + Adb.q(name)));
    }

    static String pretty(String name) {
        String s = name.replace("slauncher-", "").replace(".json", "");
        try {
            Date d = new SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).parse(s);
            return new SimpleDateFormat("d MMMM, HH:mm", new Locale("ru")).format(d);
        } catch (Exception e) { return name; }
    }

    private static void write(File f, String s) throws Exception {
        try (FileOutputStream o = new FileOutputStream(f)) { o.write(s.getBytes(StandardCharsets.UTF_8)); }
    }

    private static String readAll(InputStream in) throws Exception {
        try (InputStream i = in) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = i.read(buf)) > 0) b.write(buf, 0, n);
            return b.toString("UTF-8");
        }
    }

    // ---------- страница для телефона ----------

    interface Imported { void onImported(Applied a); }

    private static ServerSocket server;
    private static String pin;
    private static volatile Imported imported;

    static synchronized String start(Context ctx, Imported cb) {
        imported = cb;
        if (server != null) return pin;
        pin = String.format(Locale.US, "%04d", new Random().nextInt(10000));
        Context c = ctx.getApplicationContext();
        try { server = new ServerSocket(PORT); } catch (Exception e) { Net.log("transfer server", e); return null; }
        ServerSocket ss = server;
        new Thread(() -> {
            while (!ss.isClosed()) {
                try (Socket s = ss.accept()) {
                    s.setSoTimeout(15_000);
                    handle(c, s);
                } catch (Exception e) {
                    if (!ss.isClosed()) Net.log("transfer req", e);
                }
            }
        }, "transfer").start();
        return pin;
    }

    static synchronized void stop() {
        imported = null;
        if (server != null) try { server.close(); } catch (Exception ignored) { }
        server = null;
    }

    private static void handle(Context c, Socket s) throws Exception {
        InputStream in = s.getInputStream();
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int b, state = 0;
        while ((b = in.read()) != -1) { // заголовки до пустой строки, тело читаем байтами (UTF-8!)
            head.write(b);
            state = (b == '\r' || b == '\n') ? state + 1 : 0;
            if (state == 4 || head.size() > 16384) break;
        }
        String[] lines = head.toString("UTF-8").split("\r\n");
        String[] rl = lines[0].split(" ");
        String method = rl[0], path = rl.length > 1 ? rl[1] : "/";
        int len = 0;
        for (String h : lines) if (h.toLowerCase(Locale.US).startsWith("content-length:")) len = Integer.parseInt(h.substring(15).trim());
        byte[] body = new byte[Math.min(len, 1_000_000)];
        int got = 0;
        while (got < body.length) { int n = in.read(body, got, body.length - got); if (n < 0) break; got += n; }
        OutputStream out = s.getOutputStream();
        String base = "/" + pin;
        if (!path.startsWith(base)) { send(out, "text/html", page("Неверная ссылка", "<p>Откройте адрес с экрана телевизора.</p>")); return; }
        String sub = path.substring(base.length());
        if (sub.startsWith("/slauncher.json")) {
            byte[] j = export(c).toString(2).getBytes(StandardCharsets.UTF_8);
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\nContent-Disposition: attachment; filename=\"slauncher-"
                    + stamp() + ".json\"\r\nContent-Length: " + j.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.write(j);
            out.flush();
        } else if ("POST".equals(method) && sub.equals("/import")) {
            String msg;
            try {
                JSONObject o = new JSONObject(new String(body, 0, got, StandardCharsets.UTF_8));
                write(new File(localDir(c), "slauncher-" + stamp() + "-phone.json"), o.toString(2));
                Applied a = apply(c, o);
                Imported cb = imported;
                if (cb != null) new Handler(Looper.getMainLooper()).post(() -> cb.onImported(a));
                msg = "<h2>Готово ✓</h2><p>Настройки загружены на телевизор. Дальше — на экране ТВ.</p>";
            } catch (Exception e) {
                msg = "<h2>Не получилось</h2><p>" + e.getMessage() + "</p>";
            }
            send(out, "text/html", msg);
        } else {
            send(out, "text/html", page("Перенос настроек Slauncher",
                    "<p><a class=b href='" + base + "/slauncher.json'>Скачать настройки с этого телевизора</a></p>"
                            + "<p>Загрузить файл настроек на этот телевизор:</p>"
                            + "<input type=file id=f accept='.json,application/json'>"
                            + "<p><button class=b onclick=\"var x=document.getElementById('f').files[0];if(!x)return;"
                            + "x.text().then(function(t){return fetch(location.pathname.replace(/\\/$/,'')+'/import',{method:'POST',body:t})})"
                            + ".then(function(r){return r.text()}).then(function(h){document.getElementById('r').innerHTML=h})\">Загрузить</button></p>"
                            + "<div id=r></div>"));
        }
    }

    private static String page(String title, String body) {
        return "<!doctype html><html lang=ru><head><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'>"
                + "<title>" + title + "</title><style>body{font-family:system-ui,sans-serif;max-width:560px;margin:24px auto;padding:0 16px;"
                + "background:#111;color:#eee}a,button{color:#fff}.b{display:inline-block;background:#2d6cdf;border:0;border-radius:12px;"
                + "padding:12px 18px;font-size:16px;text-decoration:none}input{margin:8px 0;color:#eee}</style></head><body><h1>"
                + title + "</h1>" + body + "</body></html>";
    }

    private static void send(OutputStream out, String type, String html) throws Exception {
        byte[] b = html.getBytes(StandardCharsets.UTF_8);
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: " + type + "; charset=utf-8\r\nContent-Length: " + b.length
                + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(b);
        out.flush();
    }
}
