package ru.alex.tvhome;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/** Погода, прогноз и фаза суток для Череповца (met.no, запасные — open-meteo и wttr.in). */
final class Weather {
    // Центр Череповца
    static final double LAT = 59.127, LON = 37.909;

    enum Phase { DAWN, DAY, DUSK, NIGHT }
    enum Sky { CLEAR, CLOUDY, FOG, RAIN, SNOW, STORM }

    final double temperature;
    final int code;
    final long sunrise, sunset; // epoch millis
    final long fetchedAt;
    /** Почасовой прогноз (до ~60 ч) и по дням (до 9 дней); пусты, если источник не met.no. */
    final List<Hour> hours = new ArrayList<>();
    final List<Day> days = new ArrayList<>();

    static final class Hour {
        long time; double temp; int code; double precip; boolean night;
    }

    static final class Day {
        long date; double min = 99, max = -99, wind, precip; int code = -1; boolean noonFound;
    }

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
        JSONArray ts = o.getJSONObject("properties").getJSONArray("timeseries");
        JSONObject first = ts.getJSONObject(0).getJSONObject("data");
        double t = first.getJSONObject("instant").getJSONObject("details").getDouble("air_temperature");
        String sym = symbol(first, "next_1_hours");
        if (sym.isEmpty()) sym = symbol(first, "next_6_hours");
        long[] sun = sunTimes(System.currentTimeMillis());
        Weather w = new Weather(t, metToWmo(sym), sun[0], sun[1], System.currentTimeMillis());
        try { w.parseForecast(ts); } catch (Exception e) { Net.log("forecast", e); }
        return w;
    }

    private static String symbol(JSONObject data, String key) {
        JSONObject n = data.optJSONObject(key);
        JSONObject sm = n == null ? null : n.optJSONObject("summary");
        return sm == null ? "" : sm.optString("symbol_code", "");
    }

    private static double precip(JSONObject data, String key) {
        JSONObject n = data.optJSONObject(key);
        if (n == null) return 0;
        JSONObject d = n.optJSONObject("details");
        return d == null ? 0 : d.optDouble("precipitation_amount", 0);
    }

    private static final SimpleDateFormat ISO = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
    static { ISO.setTimeZone(TimeZone.getTimeZone("UTC")); }

    private void parseForecast(JSONArray ts) throws Exception {
        long now = System.currentTimeMillis();
        Calendar cal = Calendar.getInstance();
        Day day = null;
        for (int i = 0; i < ts.length(); i++) {
            JSONObject e = ts.getJSONObject(i);
            long time;
            synchronized (ISO) { time = ISO.parse(e.getString("time")).getTime(); }
            JSONObject data = e.getJSONObject("data");
            JSONObject det = data.getJSONObject("instant").getJSONObject("details");
            double temp = det.optDouble("air_temperature", 0);
            double wind = det.optDouble("wind_speed", 0);
            String s1 = symbol(data, "next_1_hours"), s6 = symbol(data, "next_6_hours");
            boolean hourly = !s1.isEmpty();

            if (hourly && time >= now - 3600_000L && hours.size() < 24) {
                Hour h = new Hour();
                h.time = time; h.temp = temp; h.code = metToWmo(s1); h.precip = precip(data, "next_1_hours");
                h.night = s1.contains("night");
                hours.add(h);
            }

            cal.setTimeInMillis(time);
            int hod = cal.get(Calendar.HOUR_OF_DAY);
            cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0);
            cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0);
            long date = cal.getTimeInMillis();
            if (day == null || day.date != date) {
                if (days.size() >= 9) break;
                day = new Day();
                day.date = date;
                days.add(day);
            }
            day.min = Math.min(day.min, temp);
            day.max = Math.max(day.max, temp);
            day.wind = Math.max(day.wind, wind);
            if (hourly) day.precip += precip(data, "next_1_hours");
            else if (!s6.isEmpty()) day.precip += precip(data, "next_6_hours");
            String s = !s6.isEmpty() ? s6 : s1;
            if (!s.isEmpty() && !day.noonFound && (hod >= 11 && hod <= 15 || day.code < 0)) {
                day.code = metToWmo(s);
                if (hod >= 11 && hod <= 15) day.noonFound = true;
            }
        }
    }

    /** Коротко о ближайших часах: «к 21:00 дождь», «к 15:00 без осадков», «ночью +2°». */
    String hint() {
        if (hours.size() < 3) return "";
        long now = System.currentTimeMillis();
        boolean wetNow = wet(code);
        SimpleDateFormat hm = new SimpleDateFormat("HH:mm", new Locale("ru"));
        for (Hour h : hours) {
            if (h.time <= now || h.time > now + 12 * 3600_000L) continue;
            if (!wetNow && wet(h.code)) return "к " + hm.format(h.time) + " " + word(h.code);
            if (wetNow && !wet(h.code)) return "к " + hm.format(h.time) + " без осадков";
        }
        Calendar c = Calendar.getInstance();
        int hourNow = c.get(Calendar.HOUR_OF_DAY);
        double mn = 99, mx = -99;
        for (Hour h : hours) {
            if (h.time <= now || h.time > now + 12 * 3600_000L) continue;
            mn = Math.min(mn, h.temp);
            mx = Math.max(mx, h.temp);
        }
        if (mn > 90) return "";
        return hourNow >= 12 || hourNow < 4 ? "ночью " + temp(mn) : "днём до " + temp(mx);
    }

    static boolean wet(int c) { return c >= 51; }

    static String word(int c) {
        if (c >= 95) return "гроза";
        if ((c >= 71 && c <= 77) || c == 85 || c == 86) return "снег";
        if (c >= 51 && c <= 57) return "морось";
        if (c == 66 || c == 67) return "мокрый снег";
        return c >= 80 ? "ливень" : "дождь";
    }

    static String temp(double t) {
        long r = Math.round(t);
        return (r > 0 ? "+" : "") + r + "°";
    }

    /** Значок погоды (эмодзи) для панели прогноза. */
    static String emoji(int c, boolean night) {
        if (c >= 95) return "⛈";
        if ((c >= 71 && c <= 77) || c == 85 || c == 86) return "🌨";
        if (c >= 51) return "🌧";
        if (c == 45 || c == 48) return "🌫";
        if (c == 3) return "☁";
        if (c == 2) return night ? "☁" : "⛅";
        return night ? "🌙" : "☀";
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
        String temp = temp(temperature);
        return s.isEmpty() ? temp : temp + " " + s;
    }
}
