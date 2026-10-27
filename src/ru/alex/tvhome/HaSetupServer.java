package ru.alex.tvhome;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Страница настройки Home Assistant для телефона: http://<IP телевизора>:8099/<PIN>.
 * Работает, только пока на телевизоре открыт экран «Home Assistant» в настройках Slauncher.
 * Адрес и токен HA вводятся на телефоне (пультом это мучение), датчики выбираются из списка.
 */
final class HaSetupServer {
    static final int PORT = 8099;
    private static ServerSocket server;
    private static String pin;
    private static List<JSONObject> cache = new ArrayList<>();
    interface Done { void onSaved(); }
    private static volatile Done done;

    private HaSetupServer() { }

    static synchronized String start(Context ctx, Done cb) {
        done = cb;
        if (server != null) return pin;
        pin = String.format("%04d", new Random().nextInt(10000));
        Context c = ctx.getApplicationContext();
        try {
            server = new ServerSocket(PORT);
        } catch (Exception e) {
            Net.log("setup server", e);
            return null;
        }
        ServerSocket ss = server;
        new Thread(() -> {
            while (!ss.isClosed()) {
                try (Socket s = ss.accept()) {
                    s.setSoTimeout(15_000);
                    handle(c, s);
                } catch (Exception e) {
                    if (!ss.isClosed()) Net.log("setup req", e);
                }
            }
        }, "ha-setup").start();
        return pin;
    }

    static synchronized void stop() {
        done = null;
        if (server != null) try { server.close(); } catch (Exception ignored) { }
        server = null;
    }

    /** IPv4 телевизора в локальной сети. */
    static String ip() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses()))
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) return a.getHostAddress();
            }
        } catch (Exception ignored) { }
        return "IP-телевизора";
    }

    // ---------- HTTP ----------

    private static void handle(Context c, Socket s) throws Exception {
        BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
        String line = r.readLine();
        if (line == null) return;
        String[] rl = line.split(" ");
        String method = rl[0], path = rl.length > 1 ? rl[1] : "/";
        int len = 0;
        String h;
        while ((h = r.readLine()) != null && !h.isEmpty())
            if (h.toLowerCase().startsWith("content-length:")) len = Integer.parseInt(h.substring(15).trim());
        char[] body = new char[len];
        int got = 0;
        while (got < len) { int n = r.read(body, got, len - got); if (n < 0) break; got += n; }
        Map<String, String> f = form(new String(body, 0, got));
        String base = "/" + pin;
        OutputStream out = s.getOutputStream();
        if (!path.startsWith(base)) { send(out, 404, page("Неверная ссылка", "<p>Откройте адрес, который показан на экране телевизора.</p>")); return; }
        String sub = path.substring(base.length());
        if ("POST".equals(method) && sub.equals("/auth")) {
            String url = f.getOrDefault("url", "").trim();
            if (!url.startsWith("http")) url = "http://" + url;
            HaConfig.saveAuth(c, url, f.get("token"));
            String err = load(c);
            if (err != null) { send(out, 200, authPage(c, err)); return; }
            redirect(out, base + "/sensors");
        } else if (sub.equals("/sensors")) {
            if ("POST".equals(method)) {
                save(c, f);
                Done d = done;
                if (d != null) new android.os.Handler(android.os.Looper.getMainLooper()).post(d::onSaved);
                send(out, 200, page("Готово ✓", "<p>Настройки сохранены. Климат появится в углу экрана, "
                        + "карточки событий — поверх любого приложения.</p><p><a href='" + base + "/sensors'>Изменить датчики</a></p>"));
                return;
            }
            if (cache.isEmpty()) {
                String err = load(c);
                if (err != null) { send(out, 200, authPage(c, err)); return; }
            }
            send(out, 200, sensorsPage(c));
        } else if ("POST".equals(method) && sub.equals("/off")) {
            HaConfig.setEnabled(c, false);
            HaClient.stop();
            send(out, 200, page("Выключено", "<p>Связь с Home Assistant выключена. Угол климата скрыт.</p>"));
        } else {
            send(out, 200, authPage(c, null));
        }
    }

    private static Map<String, String> form(String body) {
        Map<String, String> m = new LinkedHashMap<>();
        if (body.isEmpty()) return m;
        for (String kv : body.split("&")) {
            int i = kv.indexOf('=');
            try {
                String k = URLDecoder.decode(i < 0 ? kv : kv.substring(0, i), "UTF-8");
                String v = i < 0 ? "" : URLDecoder.decode(kv.substring(i + 1), "UTF-8");
                m.put(k, v);
            } catch (Exception ignored) { }
        }
        return m;
    }

    private static void send(OutputStream out, int code, String html) throws Exception {
        byte[] b = html.getBytes(StandardCharsets.UTF_8);
        out.write(("HTTP/1.1 " + code + " OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: " + b.length
                + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(b);
        out.flush();
    }

    private static void redirect(OutputStream out, String to) throws Exception {
        out.write(("HTTP/1.1 303 See Other\r\nLocation: " + to + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    /** Загрузить все состояния из HA (проверка адреса и токена). */
    private static String load(Context c) {
        try {
            JSONArray a = new JSONArray(Net.getTextAuth(HaConfig.url(c) + "/api/states", HaConfig.token(c)));
            List<JSONObject> l = new ArrayList<>();
            for (int i = 0; i < a.length(); i++) l.add(a.getJSONObject(i));
            cache = l;
            return null;
        } catch (Exception e) {
            String m = String.valueOf(e.getMessage());
            if (m.contains("401")) return "Home Assistant не принял токен. Создайте новый: профиль → Безопасность → Долгосрочные токены доступа.";
            return "Не удалось подключиться к Home Assistant (" + m + "). Проверьте адрес — например http://192.168.1.10:8123";
        }
    }

    // ---------- страницы ----------

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static String page(String title, String body) {
        return "<!doctype html><html lang=ru><head><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'>"
                + "<title>Slauncher</title><style>"
                + "body{font:16px system-ui,sans-serif;margin:0;background:#111214;color:#eee}main{max-width:640px;margin:auto;padding:18px}"
                + "h1{font-size:22px;margin:6px 0 4px}h2{font-size:17px;margin:22px 0 8px;color:#bbb}p,.hint{color:#aaa;font-size:14px}"
                + "input,select{width:100%;box-sizing:border-box;padding:11px;margin:5px 0 10px;border-radius:10px;border:1px solid #333;background:#1c1d20;color:#fff;font-size:15px}"
                + "input[type=checkbox]{width:auto;margin-right:8px}label{display:block;font-size:13px;color:#999}"
                + ".card{background:#1a1b1e;border:1px solid #2a2b2f;border-radius:14px;padding:12px 14px;margin:10px 0}"
                + ".row{display:flex;gap:8px}.row>*{flex:1}.err{background:#3a1414;border-color:#6b2020;color:#ffb3b3}"
                + "button{width:100%;padding:13px;border:0;border-radius:12px;background:#fff;color:#111;font-size:16px;font-weight:600;margin-top:10px}"
                + "a{color:#8ab4ff}.ev{display:flex;align-items:center;gap:6px;flex-wrap:wrap}.ev input[type=number]{width:70px;margin:0}"
                + "</style></head><body><main><h1>" + esc(title) + "</h1>" + body + "</main></body></html>";
    }

    private static String authPage(Context c, String err) {
        boolean hasToken = !HaConfig.token(c).isEmpty();
        String url = HaConfig.url(c);
        return page("Slauncher · Home Assistant",
                "<p>Шаг 1 из 2. Адрес вашего Home Assistant и долгосрочный токен доступа "
                        + "(HA → профиль → Безопасность → «Долгосрочные токены доступа» → Создать).</p>"
                        + (err != null ? "<div class='card err'>" + esc(err) + "</div>" : "")
                        + "<form method=post action='/" + pin + "/auth'>"
                        + "<label>Адрес HA</label><input name=url placeholder='http://192.168.1.10:8123' value='" + esc(url) + "' required>"
                        + "<label>Токен</label><input name=token type=password autocomplete=off placeholder='"
                        + (hasToken ? "сохранён — оставьте пустым" : "вставьте токен") + "'" + (hasToken ? "" : " required") + ">"
                        + "<button>Подключиться</button></form>"
                        + (HaConfig.enabled(c) ? "<form method=post action='/" + pin + "/off'><button style='background:#333;color:#fff'>Выключить связь с HA</button></form>" : ""));
    }

    private static String dc(JSONObject st) {
        JSONObject a = st.optJSONObject("attributes");
        return a == null ? "" : a.optString("device_class");
    }

    private static String fname(JSONObject st) {
        JSONObject a = st.optJSONObject("attributes");
        String n = a == null ? "" : a.optString("friendly_name");
        return n.isEmpty() ? st.optString("entity_id") : n;
    }

    private static String options(List<JSONObject> list, String selected, boolean allowEmpty) {
        StringBuilder sb = new StringBuilder();
        if (allowEmpty) sb.append("<option value=''>— нет —</option>");
        for (JSONObject st : list) {
            String id = st.optString("entity_id");
            JSONObject a = st.optJSONObject("attributes");
            String unit = a == null ? "" : a.optString("unit_of_measurement");
            sb.append("<option value='").append(esc(id)).append("'").append(id.equals(selected) ? " selected" : "").append(">")
                    .append(esc(fname(st))).append(" (").append(esc(st.optString("state"))).append(esc(unit)).append(")</option>");
        }
        return sb.toString();
    }

    private static String guessName(String friendly) {
        String n = friendly.replaceAll("(?iu)\\b(датчик|воздуха|качества|температуры|температура|temperature|temp|humi|sensor|влажность)\\b", " ")
                .replaceAll("\\s+", " ").trim();
        if (n.isEmpty()) return friendly;
        return n.substring(0, 1).toUpperCase() + n.substring(1);
    }

    private static String sensorsPage(Context c) {
        List<JSONObject> temps = new ArrayList<>(), hums = new ArrayList<>(), co2 = new ArrayList<>(), evs = new ArrayList<>();
        Map<String, JSONObject> byId = new HashMap<>();
        for (JSONObject st : cache) {
            String id = st.optString("entity_id"), d = dc(st);
            byId.put(id, st);
            if (id.startsWith("sensor.")) {
                if ("temperature".equals(d)) temps.add(st);
                else if ("humidity".equals(d)) hums.add(st);
                else if ("carbon_dioxide".equals(d) || id.contains("co2")) co2.add(st);
            } else if (id.startsWith("vacuum.")) evs.add(st);
            else if (id.startsWith("binary_sensor.") && (kindFor(st) != null)) evs.add(st);
        }
        // комнаты: сохранённые или угаданные (датчик температуры, у которого есть «соседний» датчик влажности)
        JSONArray rooms = HaConfig.rooms(c);
        if (rooms.length() == 0) {
            for (JSONObject t : temps) {
                String id = t.optString("entity_id");
                String pre = id.replaceAll("_?temperature$", "");
                String hum = null, c2 = null;
                for (JSONObject h : hums) if (h.optString("entity_id").startsWith(pre)) hum = h.optString("entity_id");
                for (JSONObject x : co2) if (x.optString("entity_id").startsWith(pre)) c2 = x.optString("entity_id");
                if (hum == null) continue;
                try {
                    JSONObject r = new JSONObject().put("n", guessName(fname(t))).put("t", id).put("h", hum);
                    if (c2 != null) r.put("c", c2);
                    rooms.put(r);
                } catch (Exception ignored) { }
                if (rooms.length() >= 6) break;
            }
        }
        StringBuilder sb = new StringBuilder("<p>Шаг 2 из 2. Комнаты для угла экрана (до 6) и события для карточек поверх фильма.</p>"
                + "<form method=post action='/" + pin + "/sensors'><h2>Климат по комнатам</h2>");
        for (int i = 0; i < 6; i++) {
            JSONObject r = rooms.optJSONObject(i);
            if (r == null) r = new JSONObject();
            sb.append("<div class=card><label>Название</label><input name=r").append(i).append("_n value='").append(esc(r.optString("n")))
                    .append("' placeholder='например, Спальня'>")
                    .append("<label>Температура</label><select name=r").append(i).append("_t>").append(options(temps, r.optString("t"), true)).append("</select>")
                    .append("<div class=row><div><label>Влажность</label><select name=r").append(i).append("_h>").append(options(hums, r.optString("h"), true)).append("</select></div>")
                    .append("<div><label>CO₂</label><select name=r").append(i).append("_c>").append(options(co2, r.optString("c"), true)).append("</select></div></div></div>");
        }
        sb.append("<h2>События (карточка поверх всего)</h2>");
        Map<String, JSONObject> saved = new HashMap<>();
        JSONArray ev = HaConfig.events(c);
        for (int i = 0; i < ev.length(); i++) { JSONObject o = ev.optJSONObject(i); if (o != null) saved.put(o.optString("e"), o); }
        if (evs.isEmpty()) sb.append("<p>Подходящих датчиков не найдено.</p>");
        for (JSONObject st : evs) {
            String id = st.optString("entity_id");
            String kind = id.startsWith("vacuum.") ? "vacuum" : kindFor(st);
            JSONObject sv = saved.get(id);
            String label = "door".equals(kind) ? "дверь/окно" : "leak".equals(kind) ? "протечка" : "doorbell".equals(kind) ? "домофон" : "vacuum".equals(kind) ? "пылесос" : "датчик";
            sb.append("<div class='card ev'><input type=checkbox name='ev_").append(esc(id)).append("'").append(sv != null ? " checked" : "").append(">")
                    .append("<b>").append(esc(fname(st))).append("</b> <span class=hint>· ").append(label).append("</span>");
            if ("door".equals(kind)) {
                sb.append("<div style='width:100%'><span class=hint>сообщить, если открыто дольше</span> <input type=number min=1 name='m_").append(esc(id))
                        .append("' value='").append(sv != null ? sv.optInt("m", 5) : 5).append("'> <span class=hint>мин</span><br>")
                        .append("<label style='display:inline'><input type=checkbox name='cl_").append(esc(id)).append("'")
                        .append(sv == null || sv.optBoolean("cl", true) ? " checked" : "").append(">только если работает кондиционер/обогреватель</label></div>");
            }
            sb.append("</div>");
        }
        sb.append("<button>Сохранить</button></form>");
        return page("Slauncher · датчики", sb.toString());
    }

    /** Тип события по датчику: протечка, дверь/окно, домофон; null — не предлагаем. */
    private static String kindFor(JSONObject st) {
        String d = dc(st), id = st.optString("entity_id").toLowerCase(), n = fname(st).toLowerCase();
        if ("moisture".equals(d)) return "leak";
        if ("door".equals(d) || "window".equals(d) || "opening".equals(d) || "garage_door".equals(d)) return "door";
        if (id.contains("zvon") || id.contains("doorbell") || n.contains("звон") || n.contains("домофон") || n.contains("doorbell"))
            return "doorbell";
        return null;
    }

    private static void save(Context c, Map<String, String> f) {
        try {
            JSONArray rooms = new JSONArray();
            for (int i = 0; i < 6; i++) {
                String n = f.getOrDefault("r" + i + "_n", "").trim(), t = f.getOrDefault("r" + i + "_t", ""),
                        h = f.getOrDefault("r" + i + "_h", ""), c2 = f.getOrDefault("r" + i + "_c", "");
                if (n.isEmpty() || (t.isEmpty() && h.isEmpty())) continue;
                JSONObject r = new JSONObject().put("n", n).put("t", t).put("h", h);
                if (!c2.isEmpty()) r.put("c", c2);
                rooms.put(r);
            }
            JSONArray events = new JSONArray(), climate = new JSONArray();
            Map<String, JSONObject> byId = new HashMap<>();
            for (JSONObject st : cache) {
                byId.put(st.optString("entity_id"), st);
                if (st.optString("entity_id").startsWith("climate.")) climate.put(st.optString("entity_id"));
            }
            for (String k : f.keySet()) {
                if (!k.startsWith("ev_")) continue;
                String id = k.substring(3);
                JSONObject st = byId.get(id);
                String kind = id.startsWith("vacuum.") ? "vacuum" : st == null ? "other" : String.valueOf(kindFor(st));
                JSONObject o = new JSONObject().put("e", id).put("k", kind).put("name", st == null ? id : fname(st));
                if ("door".equals(kind)) {
                    int m = 5;
                    try { m = Integer.parseInt(f.getOrDefault("m_" + id, "5")); } catch (Exception ignored) { }
                    o.put("m", m).put("cl", f.containsKey("cl_" + id));
                }
                events.put(o);
            }
            HaConfig.saveEntities(c, rooms, events, climate);
            HaClient.restart(c);
        } catch (Exception e) {
            Net.log("setup save", e);
        }
    }
}
