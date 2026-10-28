package ru.alex.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Город для погоды и фото: автоматически по IP (раз в сутки) или выбранный вручную.
 * Поиск городов — Open-Meteo Geocoding (бесплатно, без ключа).
 */
final class Loc {
    static final class City {
        final String name, region;
        final double lat, lon;
        City(String name, String region, double lat, double lon) { this.name = name; this.region = region; this.lat = lat; this.lon = lon; }
    }

    private static final long DAY = 24 * 3600_000L;
    private Loc() { }

    static SharedPreferences p(Context c) { return c.getSharedPreferences("loc", Context.MODE_PRIVATE); }

    static boolean auto(Context c) { return p(c).getBoolean("auto", true); }
    static String name(Context c) { return p(c).getString("name", "Череповец"); }

    /** Применить сохранённые координаты (вызывать при старте). Обновление с 1.8 — оставляем Череповец вручную. */
    static void init(Context c) {
        SharedPreferences sp = p(c);
        if (!sp.contains("auto")) {
            boolean upgrade = false;
            try {
                android.content.pm.PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
                upgrade = pi.lastUpdateTime - pi.firstInstallTime > 60_000;
            } catch (Exception ignored) { }
            if (upgrade) sp.edit().putBoolean("auto", false).putString("name", "Череповец")
                    .putFloat("lat", 59.127f).putFloat("lon", 37.909f).apply();
        }
        if (sp.contains("lat")) {
            Weather.LAT = sp.getFloat("lat", 59.127f);
            Weather.LON = sp.getFloat("lon", 37.909f);
        }
    }

    static void setManual(Context c, City city) {
        p(c).edit().putBoolean("auto", false).putString("name", city.name)
                .putFloat("lat", (float) city.lat).putFloat("lon", (float) city.lon).apply();
        apply(c);
    }

    static void setAuto(Context c) {
        p(c).edit().putBoolean("auto", true).putLong("checked", 0).apply();
    }

    private static void apply(Context c) {
        Weather.LAT = p(c).getFloat("lat", 59.127f);
        Weather.LON = p(c).getFloat("lon", 37.909f);
        WallpaperView.lastWeather = null; // погоду и фото — заново для нового места
    }

    /** В авто-режиме уточнить город по IP (не чаще раза в сутки или force). Вызывать в фоне. Возвращает true, если место изменилось. */
    static boolean refreshAuto(Context c, boolean force) {
        SharedPreferences sp = p(c);
        if (!auto(c)) return false;
        if (!force && System.currentTimeMillis() - sp.getLong("checked", 0) < DAY && sp.contains("lat")) return false;
        City city = null;
        String ipTz = null;
        try {
            JSONObject o = new JSONObject(Net.getText("https://ipwho.is/?lang=ru"));
            if (o.optBoolean("success", false)) {
                city = new City(o.optString("city"), o.optString("region"), o.getDouble("latitude"), o.getDouble("longitude"));
                JSONObject tz = o.optJSONObject("timezone");
                ipTz = tz == null ? null : tz.optString("id");
            }
        } catch (Exception e) { Net.log("loc ipwho", e); }
        if (city == null) {
            try {
                JSONObject o = new JSONObject(Net.getText("https://ipapi.co/json/"));
                city = new City(o.optString("city"), o.optString("region"), o.getDouble("latitude"), o.getDouble("longitude"));
                ipTz = o.optString("timezone");
            } catch (Exception e) { Net.log("loc ipapi", e); }
        }
        // Через VPN IP «живёт» в другой стране — сверяем часовой пояс IP с поясом телевизора.
        String myTz = java.util.TimeZone.getDefault().getID();
        boolean vpn = city != null && ipTz != null && !ipTz.isEmpty() && !sameZone(ipTz, myTz);
        sp.edit().putBoolean("vpn", vpn).apply();
        if (city == null || city.name.isEmpty() || vpn) {
            android.util.Log.i(Net.TAG, "loc auto: ip " + (city == null ? "?" : city.name) + " tz " + ipTz + " vs " + myTz + " — не верим");
            if (sp.contains("lat")) { sp.edit().putLong("checked", System.currentTimeMillis()).apply(); return false; }
            city = byTimezone(myTz); // ещё не знаем город — берём центр часового пояса (Europe/Moscow → Москва)
            if (city == null) return false;
        }
        // название — по-русски через геокодер, если найдётся
        try {
            List<City> ru = search(city.name);
            for (City x : ru) if (Math.abs(x.lat - city.lat) < 0.6 && Math.abs(x.lon - city.lon) < 0.6) { city = new City(x.name, x.region, city.lat, city.lon); break; }
        } catch (Exception ignored) { }
        boolean moved = Math.abs(sp.getFloat("lat", 0) - city.lat) > 0.05 || Math.abs(sp.getFloat("lon", 0) - city.lon) > 0.05;
        sp.edit().putString("name", city.name).putFloat("lat", (float) city.lat).putFloat("lon", (float) city.lon)
                .putLong("checked", System.currentTimeMillis()).apply();
        android.util.Log.i(Net.TAG, "loc auto: " + city.name + " " + city.lat + "," + city.lon);
        if (moved) apply(c);
        return moved;
    }

    /** Пояса считаем одинаковыми, если совпадает смещение от UTC сейчас. */
    private static boolean sameZone(String a, String b) {
        if (a.equals(b)) return true;
        long now = System.currentTimeMillis();
        return java.util.TimeZone.getTimeZone(a).getOffset(now) == java.util.TimeZone.getTimeZone(b).getOffset(now);
    }

    static boolean vpnSuspected(Context c) { return p(c).getBoolean("vpn", false); }

    private static City byTimezone(String tz) {
        int i = tz.lastIndexOf('/');
        if (i < 0) return null;
        try {
            List<City> r = search(tz.substring(i + 1).replace('_', ' '));
            return r.isEmpty() ? null : r.get(0);
        } catch (Exception e) { return null; }
    }

    /** Поиск города по названию (Open-Meteo Geocoding, по-русски). */
    static List<City> search(String q) throws Exception {
        String url = "https://geocoding-api.open-meteo.com/v1/search?count=6&language=ru&format=json&name="
                + URLEncoder.encode(q.trim(), "UTF-8");
        JSONObject o = new JSONObject(Net.getText(url));
        JSONArray a = o.optJSONArray("results");
        List<City> res = new ArrayList<>();
        if (a == null) return res;
        for (int i = 0; i < a.length(); i++) {
            JSONObject r = a.getJSONObject(i);
            String fc = r.optString("feature_code");
            if (!fc.isEmpty() && !fc.startsWith("PPL")) continue; // только населённые пункты, без аэропортов и рек
            String region = r.optString("admin1");
            String country = r.optString("country");
            if (!country.isEmpty() && !country.toLowerCase(Locale.ROOT).startsWith("росс")) region = region.isEmpty() ? country : region + ", " + country;
            res.add(new City(r.optString("name"), region, r.getDouble("latitude"), r.getDouble("longitude")));
        }
        return res;
    }
}
