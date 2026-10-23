package ru.alex.tvhome;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Calendar;

/** Погода и фаза суток для Череповца через open-meteo.com (без ключа). */
final class Weather {
    // Центр Череповца
    static final double LAT = 59.127, LON = 37.909;

    enum Phase { DAWN, DAY, DUSK, NIGHT }
    enum Sky { CLEAR, CLOUDY, FOG, RAIN, SNOW, STORM }

    final double temperature;
    final int code;
    final long sunrise, sunset; // epoch millis
    final long fetchedAt;

    Weather(double temperature, int code, long sunrise, long sunset, long fetchedAt) {
        this.temperature = temperature;
        this.code = code;
        this.sunrise = sunrise;
        this.sunset = sunset;
        this.fetchedAt = fetchedAt;
    }

    /**
     * Источники по очереди: api.met.no (им пользуется и Home Assistant — из сети доступен),
     * затем open-meteo и wttr.in. Восход и закат считаются локально (см. sunTimes).
     */
    static Weather fetch() throws Exception {
        try {
            return fetchMetNo();
        } catch (Exception e) {
            Net.log("met.no", e);
        }
        try {
            return fetchOpenMeteo();
        } catch (Exception e) {
            Net.log("open-meteo", e);
        }
        return fetchWttr();
    }

    private static Weather fetchMetNo() throws Exception {
        JSONObject o = new JSONObject(Net.getText(
                "https://api.met.no/weatherapi/locationforecast/2.0/compact?lat=" + LAT + "&lon=" + LON));
        JSONObject first = o.getJSONObject("properties").getJSONArray("timeseries").getJSONObject(0)
                .getJSONObject("data");
        double t = first.getJSONObject("instant").getJSONObject("details").getDouble("air_temperature");
        String sym = "";
        JSONObject next = first.optJSONObject("next_1_hours");
        if (next == null) next = first.optJSONObject("next_6_hours");
        if (next != null) sym = next.getJSONObject("summary").optString("symbol_code", "");
        long[] sun = sunTimes(System.currentTimeMillis());
        return new Weather(t, metToWmo(sym), sun[0], sun[1], System.currentTimeMillis());
    }

    /** Символы met.no ("lightrain", "partlycloudy_day", ...) -> близкие коды WMO. */
    static int metToWmo(String s) {
        if (s.contains("thunder")) return 95;
        if (s.contains("snow") || s.contains("sleet")) return 73;
        if (s.contains("lightrain") || s.contains("drizzle")) return 53;
        if (s.contains("rain")) return 63;
        if (s.contains("fog")) return 45;
        if (s.startsWith("partlycloudy")) return 2;
        if (s.startsWith("fair")) return 1;
        if (s.startsWith("clearsky")) return 0;
        if (s.startsWith("cloudy")) return 3;
        return 3;
    }

    /** Восход и заход солнца (epoch ms) для суток, в которые попадает {@code when}. Алгоритм NOAA. */
    static long[] sunTimes(long when) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(when);
        int n = c.get(Calendar.DAY_OF_YEAR);
        Calendar utcMidnight = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
        utcMidnight.clear();
        utcMidnight.set(c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH));
        long base = utcMidnight.getTimeInMillis();
        return new long[]{base + sunEvent(n, true), base + sunEvent(n, false)};
    }

    private static long sunEvent(int n, boolean rise) {
        double lngHour = LON / 15.0;
        double t = n + ((rise ? 6 : 18) - lngHour) / 24.0;
        double m = 0.9856 * t - 3.289;
        double l = norm(m + 1.916 * sin(m) + 0.020 * sin(2 * m) + 282.634);
        double ra = norm(Math.toDegrees(Math.atan(0.91764 * Math.tan(Math.toRadians(l)))));
        ra += Math.floor(l / 90) * 90 - Math.floor(ra / 90) * 90;
        ra /= 15.0;
        double sinDec = 0.39782 * sin(l);
        double cosDec = Math.cos(Math.asin(sinDec));
        double cosH = (Math.cos(Math.toRadians(90.833)) - sinDec * sin(LAT)) / (cosDec * Math.cos(Math.toRadians(LAT)));
        cosH = Math.max(-1, Math.min(1, cosH)); // полярный день/ночь — на широте Череповца не бывает
        double h = rise ? 360 - Math.toDegrees(Math.acos(cosH)) : Math.toDegrees(Math.acos(cosH));
        h /= 15.0;
        double tt = h + ra - 0.06571 * t - 6.622;
        double ut = ((tt - lngHour) % 24 + 24) % 24;
        return Math.round(ut * 3600_000.0);
    }

    private static double sin(double deg) { return Math.sin(Math.toRadians(deg)); }
    private static double norm(double deg) { return ((deg % 360) + 360) % 360; }

    private static Weather fetchWttr() throws Exception {
        JSONObject o = new JSONObject(Net.getText("https://wttr.in/" + LAT + "," + LON + "?format=j1&lang=ru"));
        JSONObject cur = o.getJSONArray("current_condition").getJSONObject(0);
        double t = cur.getDouble("temp_C");
        int code = wwoToWmo(cur.getInt("weatherCode"));
        JSONObject astro = o.getJSONArray("weather").getJSONObject(0).getJSONArray("astronomy").getJSONObject(0);
        return new Weather(t, code, todayAt(astro.optString("sunrise")), todayAt(astro.optString("sunset")),
                System.currentTimeMillis());
    }

    /** "07:42 AM" (местное время) -> epoch millis сегодня. */
    private static long todayAt(String s) {
        try {
            String[] p = s.trim().split("[: ]");
            int h = Integer.parseInt(p[0]) % 12, m = Integer.parseInt(p[1]);
            if (p.length > 2 && p[2].equalsIgnoreCase("PM")) h += 12;
            Calendar c = Calendar.getInstance();
            c.set(Calendar.HOUR_OF_DAY, h);
            c.set(Calendar.MINUTE, m);
            c.set(Calendar.SECOND, 0);
            return c.getTimeInMillis();
        } catch (Exception e) {
            return 0;
        }
    }

    /** Коды World Weather Online (wttr.in) -> близкие коды WMO. */
    private static int wwoToWmo(int c) {
        if (c == 113) return 0;
        if (c == 116) return 2;
        if (c == 119 || c == 122) return 3;
        if (c == 143 || c == 248 || c == 260) return 45;
        if (c == 200 || (c >= 386 && c <= 395)) return 95;
        if (c == 179 || c == 182 || c == 185 || c == 227 || c == 230 || (c >= 320 && c <= 338)
                || c == 350 || (c >= 362 && c <= 377)) return 73;
        if (c == 263 || c == 266 || c == 281 || c == 284) return 53;
        return 63; // остальные — дождь
    }

    private static Weather fetchOpenMeteo() throws Exception {
        String url = "https://api.open-meteo.com/v1/forecast?latitude=" + LAT + "&longitude=" + LON
                + "&current=temperature_2m,weather_code&daily=sunrise,sunset&timezone=auto"
                + "&timeformat=unixtime&forecast_days=1";
        JSONObject o = new JSONObject(Net.getText(url));
        JSONObject cur = o.getJSONObject("current");
        JSONObject daily = o.getJSONObject("daily");
        JSONArray sr = daily.getJSONArray("sunrise"), ss = daily.getJSONArray("sunset");
        return new Weather(cur.getDouble("temperature_2m"), cur.getInt("weather_code"),
                sr.getLong(0) * 1000L, ss.getLong(0) * 1000L, System.currentTimeMillis());
    }

    /** Фаза суток: рассвет/закат — по ±50 минут от восхода/захода. Без данных — по часам. */
    static Phase phase(Weather w, long now) {
        final long win = 50 * 60 * 1000L;
        long[] sun = sunTimes(now);
        {
            long sr = sun[0], ss = sun[1];
            if (Math.abs(now - sr) <= win) return Phase.DAWN;
            if (Math.abs(now - ss) <= win) return Phase.DUSK;
            if (now > sr && now < ss) return Phase.DAY;
            return Phase.NIGHT;
        }
    }

    static Sky sky(Weather w) {
        if (w == null) return Sky.CLEAR;
        int c = w.code;
        if (c <= 1) return Sky.CLEAR;
        if (c <= 3) return Sky.CLOUDY;
        if (c == 45 || c == 48) return Sky.FOG;
        if ((c >= 71 && c <= 77) || c == 85 || c == 86) return Sky.SNOW;
        if (c >= 95) return Sky.STORM;
        return Sky.RAIN; // 51–67, 80–82
    }

    String describe() {
        int c = code;
        String s;
        if (c == 0) s = "ясно";
        else if (c == 1) s = "преимущественно ясно";
        else if (c == 2) s = "переменная облачность";
        else if (c == 3) s = "пасмурно";
        else if (c == 45 || c == 48) s = "туман";
        else if (c >= 51 && c <= 57) s = "морось";
        else if (c >= 61 && c <= 67) s = "дождь";
        else if (c >= 71 && c <= 77) s = "снег";
        else if (c >= 80 && c <= 82) s = "ливень";
        else if (c == 85 || c == 86) s = "снегопад";
        else if (c >= 95) s = "гроза";
        else s = "";
        long t = Math.round(temperature);
        String temp = (t > 0 ? "+" : "") + t + "°";
        return s.isEmpty() ? temp : temp + "  " + s;
    }
}
