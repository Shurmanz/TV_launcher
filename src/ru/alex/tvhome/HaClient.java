package ru.alex.tvhome;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Прямая связь с Home Assistant (опция, без автоматизаций в HA): WebSocket + subscribe_entities.
 * Держит климат по комнатам для угла экрана и показывает карточки событий поверх всего.
 * Живёт в процессе Slauncher; запускается службой кнопки «Домой» (она работает всегда) и главным экраном.
 */
final class HaClient {
    private static HaClient inst;
    private static final Handler ui = new Handler(Looper.getMainLooper());

    private final Context ctx;
    private volatile boolean running;
    private volatile Ws ws;
    private Thread thread;
    private final Map<String, String> states = new HashMap<>();
    private final Map<String, String> names = new HashMap<>();
    private final Map<String, Runnable> doorTimers = new HashMap<>();
    static volatile String status = "выключено";
    static volatile long lastOk;

    private HaClient(Context c) { ctx = c.getApplicationContext(); }

    /** Запустить, если включено в настройках (безопасно вызывать сколько угодно раз). */
    static synchronized void ensure(Context c) {
        if (!HaConfig.enabled(c)) { stop(); status = "выключено"; return; }
        if (inst == null) inst = new HaClient(c);
        inst.start();
    }

    static synchronized void restart(Context c) { stop(); ensure(c); }

    static synchronized void stop() {
        if (inst != null) inst.halt();
        inst = null;
    }

    private synchronized void start() {
        if (running) return;
        running = true;
        thread = new Thread(this::loop, "ha-client");
        thread.start();
    }

    private void halt() {
        running = false;
        Ws w = ws;
        if (w != null) w.close();
        for (Runnable r : doorTimers.values()) ui.removeCallbacks(r);
        doorTimers.clear();
    }

    private void loop() {
        long backoff = 5_000;
        while (running) {
            try (Ws w = new Ws(HaConfig.url(ctx), "/api/websocket")) {
                ws = w;
                status = "подключение…";
                JSONObject hello = new JSONObject(w.receive());
                if (!"auth_required".equals(hello.optString("type"))) throw new Exception("ожидался auth_required");
                w.send(new JSONObject().put("type", "auth").put("access_token", HaConfig.token(ctx)).toString());
                JSONObject auth = new JSONObject(w.receive());
                if (!"auth_ok".equals(auth.optString("type"))) {
                    status = "неверный токен";
                    android.util.Log.w(Net.TAG, "ha auth: " + auth);
                    sleep(60_000);
                    continue;
                }
                JSONArray ids = new JSONArray();
                for (String id : HaConfig.watched(ctx)) ids.put(id);
                w.send(new JSONObject().put("id", 1).put("type", "subscribe_entities").put("entity_ids", ids).toString());
                status = "подключено";
                backoff = 5_000;
                long lastPing = SystemClock.elapsedRealtime();
                int msgId = 2;
                // ping раз в 45 с: держим соединение и замечаем обрыв
                Thread pinger = new Thread(() -> {
                    int id = 1000;
                    while (running && ws == w) {
                        sleep(45_000);
                        try { w.send(new JSONObject().put("id", id++).put("type", "ping").toString()); }
                        catch (Exception e) { w.close(); return; }
                    }
                }, "ha-ping");
                pinger.start();
                while (running) {
                    JSONObject m = new JSONObject(w.receive());
                    lastOk = System.currentTimeMillis();
                    if ("event".equals(m.optString("type"))) onEvent(m.optJSONObject("event"));
                }
            } catch (Exception e) {
                if (!running) break;
                status = "нет связи";
                Net.log("ha ws", e);
                sleep(backoff);
                backoff = Math.min(backoff * 2, 120_000);
            } finally {
                ws = null;
            }
        }
    }

    private static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException ignored) { } }

    /** subscribe_entities: "a" — полные состояния, "c" — изменения ("+": {"s": новое}). */
    private void onEvent(JSONObject ev) {
        if (ev == null) return;
        Map<String, String[]> changed = new HashMap<>(); // id → {старое, новое}
        JSONObject a = ev.optJSONObject("a");
        if (a != null) {
            for (Iterator<String> it = a.keys(); it.hasNext(); ) {
                String id = it.next();
                JSONObject o = a.optJSONObject(id);
                if (o == null) continue;
                JSONObject attrs = o.optJSONObject("a");
                if (attrs != null && attrs.has("friendly_name")) names.put(id, attrs.optString("friendly_name"));
                String s = o.optString("s");
                states.put(id, s); // первичная загрузка — без уведомлений
            }
        }
        JSONObject c = ev.optJSONObject("c");
        if (c != null) {
            for (Iterator<String> it = c.keys(); it.hasNext(); ) {
                String id = it.next();
                JSONObject plus = c.optJSONObject(id) == null ? null : c.optJSONObject(id).optJSONObject("+");
                if (plus == null || !plus.has("s")) continue;
                String old = states.get(id), now = plus.optString("s");
                states.put(id, now);
                if (old == null || !old.equals(now)) changed.put(id, new String[]{old, now});
            }
        }
        writeClimate();
        for (Map.Entry<String, String[]> e : changed.entrySet()) checkRules(e.getKey(), e.getValue()[0], e.getValue()[1]);
    }

    /** Строка для угла: «Спальня:21.4,42,640/Кухня:24.0,47». */
    private void writeClimate() {
        JSONArray r = HaConfig.rooms(ctx);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < r.length(); i++) {
            JSONObject o = r.optJSONObject(i);
            if (o == null) continue;
            String n = o.optString("n").replaceAll("[:/,]", " ").trim();
            if (n.isEmpty()) continue;
            if (sb.length() > 0) sb.append('/');
            sb.append(n).append(':').append(val(o.optString("t"))).append(',').append(val(o.optString("h")));
            if (!o.optString("c").isEmpty()) sb.append(',').append(val(o.optString("c")));
        }
        String data = sb.toString();
        if (data.isEmpty()) return;
        HaReceiver.prefs(ctx).edit().putString("data", data).putLong("at", System.currentTimeMillis()).apply();
        HaReceiver.Listener l = HaReceiver.listener;
        if (l != null) ui.post(l::onHaStatus);
    }

    private String val(String id) {
        String s = id.isEmpty() ? null : states.get(id);
        try { return String.valueOf(Math.round(Double.parseDouble(s) * 10) / 10.0); } catch (Exception e) { return "-"; }
    }

    private void checkRules(String id, String old, String now) {
        JSONArray ev = HaConfig.events(ctx);
        for (int i = 0; i < ev.length(); i++) {
            JSONObject r = ev.optJSONObject(i);
            if (r == null || !id.equals(r.optString("e"))) continue;
            String kind = r.optString("k", "other");
            String name = r.optString("name", names.containsKey(id) ? names.get(id) : id);
            switch (kind) {
                case "vacuum":
                    if ("docked".equals(now) && ("cleaning".equals(old) || "returning".equals(old)))
                        Overlay.show(ctx, "vac_done", null, null);
                    else if ("error".equals(now)) Overlay.show(ctx, "vac_err", null, null);
                    break;
                case "door": {
                    Runnable prev = doorTimers.remove(id);
                    if (prev != null) ui.removeCallbacks(prev);
                    if ("on".equals(now)) {
                        int min = Math.max(1, r.optInt("m", 5));
                        boolean needClimate = r.optBoolean("cl", false);
                        Runnable t = () -> {
                            doorTimers.remove(id);
                            if (!"on".equals(states.get(id))) return;
                            if (needClimate && !climateRunning()) return;
                            Overlay.show(ctx, "balcony", name + " открыт" + (needClimate ? ", а климат работает" : ""),
                                    "Открыто больше " + min + " мин");
                        };
                        doorTimers.put(id, t);
                        ui.postDelayed(t, min * 60_000L);
                    }
                    break;
                }
                default:
                    if ("on".equals(now) && !"on".equals(old)) {
                        String k = "doorbell".equals(kind) || "leak".equals(kind) ? kind : "info";
                        Overlay.show(ctx, k, "info".equals(k) ? name : null, "info".equals(k) ? "Сработал датчик" : null);
                    }
            }
        }
    }

    private boolean climateRunning() {
        JSONArray cl = HaConfig.climate(ctx);
        for (int i = 0; i < cl.length(); i++) {
            String s = states.get(cl.optString(i));
            if (s != null && !"off".equals(s) && !"unavailable".equals(s) && !"unknown".equals(s)) return true;
        }
        return false;
    }
}
