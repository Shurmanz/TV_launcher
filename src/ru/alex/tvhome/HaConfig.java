package ru.alex.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Настройки связи с Home Assistant (опция). Хранятся только на этом телевизоре.
 *  rooms:  [{"n":"Спальня","t":"sensor..","h":"sensor..","c":"sensor.."}]
 *  events: [{"e":"binary_sensor..","k":"leak|doorbell|door|vacuum|other","m":5,"cl":true,"name":"…"}]
 *  climate: ["climate.ac", …] — для правила «дверь открыта, а климат работает»
 */
final class HaConfig {
    private HaConfig() { }

    static SharedPreferences p(Context c) { return c.getSharedPreferences("ha_cfg", Context.MODE_PRIVATE); }

    static boolean enabled(Context c) { return p(c).getBoolean("enabled", false) && !url(c).isEmpty() && !token(c).isEmpty(); }
    static String url(Context c) { return p(c).getString("url", ""); }
    static String token(Context c) { return p(c).getString("token", ""); }

    static JSONArray rooms(Context c) { return arr(p(c).getString("rooms", "[]")); }
    static JSONArray events(Context c) { return arr(p(c).getString("events", "[]")); }
    static JSONArray climate(Context c) { return arr(p(c).getString("climate", "[]")); }

    static void setEnabled(Context c, boolean on) { p(c).edit().putBoolean("enabled", on).apply(); }

    static void saveAuth(Context c, String url, String token) {
        SharedPreferences.Editor e = p(c).edit().putString("url", url.replaceAll("/+$", ""));
        if (token != null && !token.isEmpty()) e.putString("token", token.trim());
        e.apply();
    }

    static void saveEntities(Context c, JSONArray rooms, JSONArray events, JSONArray climate) {
        p(c).edit().putString("rooms", rooms.toString()).putString("events", events.toString())
                .putString("climate", climate.toString()).putBoolean("enabled", true).apply();
    }

    static JSONArray arr(String s) {
        try { return new JSONArray(s); } catch (Exception e) { return new JSONArray(); }
    }

    /** Все сущности, за которыми нужно следить. */
    static java.util.Set<String> watched(Context c) {
        java.util.Set<String> s = new java.util.LinkedHashSet<>();
        JSONArray r = rooms(c);
        for (int i = 0; i < r.length(); i++) {
            JSONObject o = r.optJSONObject(i);
            if (o == null) continue;
            for (String k : new String[]{"t", "h", "c"}) if (!o.optString(k).isEmpty()) s.add(o.optString(k));
        }
        JSONArray ev = events(c);
        for (int i = 0; i < ev.length(); i++) {
            JSONObject o = ev.optJSONObject(i);
            if (o != null && !o.optString("e").isEmpty()) s.add(o.optString("e"));
        }
        JSONArray cl = climate(c);
        for (int i = 0; i < cl.length(); i++) s.add(cl.optString(i));
        return s;
    }
}
