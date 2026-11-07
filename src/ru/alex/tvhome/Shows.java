package ru.alex.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.LruCache;
import android.widget.ImageView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * «Что посмотреть» и «Новые серии».
 * Источник — MyShows (api.myshows.me, JSON-RPC, без ключа): новинки фантастики и триллеров за этот и прошлый год,
 * рейтинги КиноПоиска / IMDb и даты выхода серий. Открываем в Зоне (по id КиноПоиска — сразу карточка сериала)
 * или в NUM (по id TMDB, его берём из Wikidata по id IMDb).
 * Список «Слежу» общий для всех телевизоров: хранится в Home Assistant (frontend user data того же пользователя).
 */
final class Shows {
    private static final String RPC = "https://api.myshows.me/v2/rpc/";
    private static final String HA_KEY = "slauncher_follow";
    static final String ZONA = "mobi.zona", ZONA_MAIN = "ru.zona.app.android.MainActivity", NUM = "ru.yourok.num";
    private static final long PICKS_TTL = 12 * 3600_000L, EPS_TTL = 3 * 3600_000L, NEW_WINDOW = 24 * 3600_000L;
    private static final int[] GENRES = {9, 26}; // фантастика, триллер
    private static final Handler ui = new Handler(Looper.getMainLooper());
    private static final ExecutorService net = Executors.newFixedThreadPool(3);
    private static volatile boolean refreshing;

    private Shows() { }

    // ---------- модель ----------

    static final class Show {
        int id, year, kpId, imdbId, watching, voted, tmdbId;
        double rating, kp, imdb;
        boolean movie;
        String title = "", orig = "", image = "", desc = "", status = "", genres = "", digital = "";

        static Show of(JSONObject o) {
            Show s = new Show();
            s.id = o.optInt("id");
            s.title = o.optString("title", "");
            s.orig = o.optString("titleOriginal", "");
            s.year = o.optInt("year");
            s.status = o.optString("status", "");
            s.watching = o.optInt("watching");
            s.voted = o.optInt("voted");
            s.rating = o.optDouble("rating", 0);
            s.image = o.optString("image", "");
            s.kpId = o.optInt("kinopoiskId");
            s.imdbId = o.optInt("imdbId");
            s.kp = o.optDouble("kinopoiskRating", 0);
            s.imdb = o.optDouble("imdbRating", 0);
            s.desc = clean(o.optString("description", ""));
            s.genres = o.optString("genres", "");
            s.movie = o.optBoolean("movie");
            s.tmdbId = o.optInt("tmdbId");
            s.digital = o.optString("digital", "");
            JSONArray g = o.optJSONArray("genreIds");
            if (g != null) s.genres = genreNames(g);
            return s;
        }

        JSONObject json() {
            JSONObject o = new JSONObject();
            try {
                o.put("id", id).put("title", title).put("titleOriginal", orig).put("year", year).put("status", status)
                        .put("watching", watching).put("voted", voted).put("rating", rating).put("image", image)
                        .put("kinopoiskId", kpId).put("imdbId", imdbId).put("kinopoiskRating", kp).put("imdbRating", imdb)
                        .put("description", desc).put("genres", genres).put("movie", movie).put("tmdbId", tmdbId)
                        .put("digital", digital);
            } catch (Exception ignored) { }
            return o;
        }

        /** «8.1 КП» / «8.6 IMDb» / «4.4 из 5 MyShows» — что есть. */
        String ratingText() {
            if (kp >= 1) return String.format(Locale.US, "КП %.1f", kp) + (imdb >= 1 ? String.format(Locale.US, "  ·  IMDb %.1f", imdb) : "");
            if (imdb >= 1) return String.format(Locale.US, "IMDb %.1f", imdb);
            if (rating > 0) return String.format(Locale.US, "★ %.1f из 5", rating);
            return "";
        }

        String shortRating() {
            if (kp >= 1) return String.format(Locale.US, "%.1f", kp);
            if (imdb >= 1) return String.format(Locale.US, "%.1f", imdb);
            if (rating > 0) return String.format(Locale.US, "%.1f", rating * 2);
            return "";
        }
    }

    /** Описания на MyShows бывают с html (<p>, <br>, &nbsp;) — оставляем только текст. */
    static String clean(String d) {
        if (d == null) return "";
        if (d.indexOf('<') >= 0 || d.indexOf('&') >= 0) d = android.text.Html.fromHtml(d, android.text.Html.FROM_HTML_MODE_LEGACY).toString();
        return d.replace('\u00A0', ' ').replaceAll("\\s+", " ").trim();
    }

    static final class Ep {
        int showId, season, num;
        long air;
        String show = "", title = "";
    }

    private static String genreNames(JSONArray ids) {
        // сначала «наши» жанры, потом остальные; не больше трёх
        int[] order = {9, 26, 25, 30, 12, 112, 105, 28, 6};
        List<String> out = new ArrayList<>();
        for (int want : order) {
            for (int i = 0; i < ids.length(); i++) if (ids.optInt(i) == want) { String n = genre(want); if (!n.isEmpty()) out.add(n); }
            if (out.size() >= 3) break;
        }
        return android.text.TextUtils.join(", ", out);
    }

    private static String genre(int id) {
        switch (id) {
            case 9: return "фантастика";
            case 26: return "триллер";
            case 25: return "детектив";
            case 30: return "ужасы";
            case 12: return "фэнтези";
            case 112: return "мистика";
            case 105: return "шпионский";
            case 28: return "криминал";
            case 6: return "драма";
            default: return "";
        }
    }

    // ---------- хранилище ----------

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("shows", Context.MODE_PRIVATE); }
    private static File file(Context c, String n) { return new File(c.getFilesDir(), n); }

    private static JSONObject readJson(File f) {
        try { return new JSONObject(new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8")); }
        catch (Exception e) { return null; }
    }

    private static void writeJson(File f, JSONObject o) {
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(f)) { out.write(o.toString().getBytes("UTF-8")); }
        catch (Exception e) { Net.log("shows save", e); }
    }

    static List<Show> picks(Context c) {
        List<Show> out = new ArrayList<>();
        JSONObject o = readJson(file(c, "shows_picks.json"));
        JSONArray a = o == null ? null : o.optJSONArray("list");
        if (a != null) for (int i = 0; i < a.length(); i++) out.add(Show.of(a.optJSONObject(i)));
        return out;
    }

    // --- «Слежу»: {t: время изменения, list: [show…]}

    static JSONObject followDoc(Context c) {
        try { return new JSONObject(prefs(c).getString("follow", "{\"t\":0,\"list\":[]}")); }
        catch (Exception e) { return new JSONObject(); }
    }

    static List<Show> followed(Context c) {
        List<Show> out = new ArrayList<>();
        JSONArray a = followDoc(c).optJSONArray("list");
        if (a != null) for (int i = 0; i < a.length(); i++) out.add(Show.of(a.optJSONObject(i)));
        return out;
    }

    static boolean isFollowed(Context c, int id) {
        for (Show s : followed(c)) if (s.id == id) return true;
        return false;
    }

    static void setFollowed(Context c, Show s, boolean on, Runnable done) {
        List<Show> list = followed(c);
        for (int i = list.size() - 1; i >= 0; i--) if (list.get(i).id == s.id) list.remove(i);
        if (on) list.add(0, s);
        JSONArray a = new JSONArray();
        for (Show x : list) a.put(x.json());
        JSONObject doc = new JSONObject();
        try { doc.put("t", System.currentTimeMillis()).put("list", a); } catch (Exception ignored) { }
        prefs(c).edit().putString("follow", doc.toString()).apply();
        prefs(c).edit().putLong("eps_at", 0).apply(); // список изменился — даты серий перечитать
        Context app = c.getApplicationContext();
        net.execute(() -> {
            haPut(app, doc);
            PeerSync.push(app, doc);
            fetchEpisodes(app);
            if (done != null) ui.post(done);
        });
    }

    // ---------- обновление ----------

    /** Фоном: синхронизировать «Слежу» с HA, при необходимости обновить подборку и даты серий. done — в UI-потоке. */
    static void refresh(Context c, Runnable done) {
        if (refreshing) return;
        refreshing = true;
        Context app = c.getApplicationContext();
        net.execute(() -> {
            try {
                haSync(app);
                PeerSync.sync(app);
                long now = System.currentTimeMillis();
                if (now - prefs(app).getLong("picks_at", 0) > PICKS_TTL) fetchPicks(app);
                if (!token(app).isEmpty() && now - prefs(app).getLong("films_at", 0) > PICKS_TTL) fetchFilms(app);
                if (now - prefs(app).getLong("eps_at", 0) > EPS_TTL) fetchEpisodes(app);
            } catch (Throwable e) {
                Log.w(Net.TAG, "shows refresh: " + e, e);
            } finally {
                refreshing = false;
                if (done != null) ui.post(done);
            }
        });
    }

    private static JSONObject rpc(String method, JSONObject params) throws Exception {
        JSONObject req = new JSONObject().put("jsonrpc", "2.0").put("method", method).put("params", params).put("id", 1);
        JSONObject r = new JSONObject(Net.postJson(RPC, req.toString()));
        if (r.has("error")) throw new Exception(method + ": " + r.optJSONObject("error"));
        return r;
    }

    private static void fetchPicks(Context c) throws Exception {
        int y = Calendar.getInstance().get(Calendar.YEAR);
        Map<Integer, Show> m = new LinkedHashMap<>();
        for (int g : GENRES) for (int yr : new int[]{y, y - 1}) {
            JSONObject p = new JSONObject().put("search", new JSONObject().put("genre", g).put("year", yr))
                    .put("page", 0).put("pageSize", 30);
            JSONArray a = rpc("shows.Get", p).optJSONArray("result");
            if (a == null) continue;
            for (int i = 0; i < a.length(); i++) {
                Show s = Show.of(a.optJSONObject(i));
                if (s.id == 0 || s.voted < 150 || s.rating < 3.6) continue; // совсем слабое и никому не известное — мимо
                if (!m.containsKey(s.id)) m.put(s.id, s);
            }
        }
        List<Show> list = new ArrayList<>(m.values());
        // свежие — выше: этот год с весом 1,6
        Collections.sort(list, (a, b) -> Double.compare(score(b, y), score(a, y)));
        if (list.size() > 24) list = new ArrayList<>(list.subList(0, 24));
        // подробности: рейтинги КП/IMDb, описание, жанры
        for (int i = 0; i < list.size(); i++) {
            try {
                JSONObject r = rpc("shows.GetById", new JSONObject().put("showId", list.get(i).id).put("withEpisodes", false))
                        .optJSONObject("result");
                if (r != null) list.set(i, Show.of(r));
            } catch (Exception e) { Net.log("shows detail", e); }
        }
        JSONArray a = new JSONArray();
        for (Show s : list) a.put(s.json());
        writeJson(file(c, "shows_picks.json"), new JSONObject().put("list", a));
        prefs(c).edit().putLong("picks_at", System.currentTimeMillis()).apply();
        Log.i(Net.TAG, "shows: подборка " + list.size());
    }

    private static double score(Show s, int y) { return s.watching * (s.year >= y ? 1.6 : 1.0) * (0.6 + s.rating / 5); }

    private static final SimpleDateFormat AIR = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US);

    private static void fetchEpisodes(Context c) {
        List<Show> f = followed(c);
        JSONArray out = new JSONArray();
        long now = System.currentTimeMillis();
        for (Show s : f) {
            try {
                JSONObject r = rpc("shows.GetById", new JSONObject().put("showId", s.id).put("withEpisodes", true)).optJSONObject("result");
                JSONArray eps = r == null ? null : r.optJSONArray("episodes");
                if (eps == null) continue;
                for (int i = 0; i < eps.length(); i++) {
                    JSONObject e = eps.optJSONObject(i);
                    if (e == null || e.optBoolean("isSpecial") || e.optInt("episodeNumber") <= 0) continue;
                    long air;
                    synchronized (AIR) { air = AIR.parse(e.optString("airDateUTC")).getTime(); }
                    if (air < now - 7 * 86400_000L || air > now + 180 * 86400_000L) continue;
                    out.put(new JSONObject().put("showId", s.id).put("show", s.title).put("season", e.optInt("seasonNumber"))
                            .put("num", e.optInt("episodeNumber")).put("air", air).put("title", e.optString("title", "")));
                }
            } catch (Exception e) { Net.log("shows eps " + s.id, e); }
        }
        try { writeJson(file(c, "shows_eps.json"), new JSONObject().put("list", out)); } catch (Exception ignored) { }
        prefs(c).edit().putLong("eps_at", now).apply();
        Log.i(Net.TAG, "shows: серий в окне " + out.length() + " по " + f.size() + " сериалам");
    }

    static List<Ep> episodes(Context c) {
        List<Ep> out = new ArrayList<>();
        JSONObject o = readJson(file(c, "shows_eps.json"));
        JSONArray a = o == null ? null : o.optJSONArray("list");
        if (a == null) return out;
        java.util.Set<Integer> ids = new java.util.HashSet<>();
        for (Show s : followed(c)) ids.add(s.id);
        for (int i = 0; i < a.length(); i++) {
            JSONObject e = a.optJSONObject(i);
            Ep ep = new Ep();
            ep.showId = e.optInt("showId");
            if (!ids.contains(ep.showId)) continue; // перестали следить — не показываем
            ep.show = e.optString("show");
            ep.season = e.optInt("season");
            ep.num = e.optInt("num");
            ep.air = e.optLong("air");
            ep.title = e.optString("title");
            out.add(ep);
        }
        Collections.sort(out, (x, y) -> Long.compare(x.air, y.air));
        return out;
    }

    /** Вышедшее за последние сутки: по сериалу — одна строка. «Вышла 5 серия: Разделение», «Вышли 1–8 серии…». */
    static List<String> released(Context c) {
        long now = System.currentTimeMillis();
        Map<Integer, List<Ep>> by = new LinkedHashMap<>();
        for (Ep e : episodes(c)) {
            if (e.air > now || now - e.air > NEW_WINDOW) continue;
            List<Ep> l = by.get(e.showId);
            if (l == null) { l = new ArrayList<>(); by.put(e.showId, l); }
            l.add(e);
        }
        List<String> out = new ArrayList<>();
        for (List<Ep> l : by.values()) {
            Ep a = l.get(0), b = l.get(l.size() - 1);
            String season = a.season > 1 ? " " + a.season + " сезона" : "";
            if (l.size() == 1) out.add("Вышла " + a.num + " серия" + season + ": " + a.show);
            else if (a.num == 1 && l.size() >= 4) out.add("Вышел " + a.season + " сезон целиком: " + a.show);
            else out.add("Вышли " + a.num + "–" + b.num + " серии" + season + ": " + a.show);
        }
        return out;
    }

    /** Ближайшая серия сериала (ещё не вышла) или null. */
    static Ep next(Context c, int showId) {
        long now = System.currentTimeMillis();
        for (Ep e : episodes(c)) if (e.showId == showId && e.air > now) return e;
        return null;
    }

    /** Последняя вышедшая за неделю серия сериала или null. */
    static Ep lastAired(Context c, int showId) {
        long now = System.currentTimeMillis();
        Ep last = null;
        for (Ep e : episodes(c)) if (e.showId == showId && e.air <= now) last = e;
        return last;
    }

    static String when(long t) {
        Calendar a = Calendar.getInstance(), n = Calendar.getInstance();
        a.setTimeInMillis(t);
        int days = (int) Math.round((startOfDay(a) - startOfDay(n)) / 86400_000.0);
        String time = String.format(Locale.US, "%02d:%02d", a.get(Calendar.HOUR_OF_DAY), a.get(Calendar.MINUTE));
        if (days == 0) return "сегодня в " + time;
        if (days == 1) return "завтра в " + time;
        if (days == -1) return "вчера";
        if (days > 1 && days < 7) return new SimpleDateFormat("EEEE", new Locale("ru")).format(a.getTime()) + " в " + time;
        return new SimpleDateFormat("d MMMM", new Locale("ru")).format(a.getTime());
    }

    private static long startOfDay(Calendar c) {
        Calendar x = (Calendar) c.clone();
        x.set(Calendar.HOUR_OF_DAY, 0); x.set(Calendar.MINUTE, 0); x.set(Calendar.SECOND, 0); x.set(Calendar.MILLISECOND, 0);
        return x.getTimeInMillis();
    }


    // ---------- фильмы: цифровые релизы (poiskkino.dev, бывш. kinopoisk.dev — неофициальный API Кинопоиска) ----------

    private static final String KP = "https://api.poiskkino.dev";

    static String token(Context c) { return prefs(c).getString("kp_token", ""); }

    static String filmsError(Context c) { return prefs(c).getString("films_err", ""); }

    static List<Show> films(Context c) {
        List<Show> out = new ArrayList<>();
        JSONObject o = readJson(file(c, "shows_films.json"));
        JSONArray a = o == null ? null : o.optJSONArray("list");
        if (a != null) for (int i = 0; i < a.length(); i++) out.add(Show.of(a.optJSONObject(i)));
        return out;
    }

    /** Сохранить токен и сразу загрузить фильмы. done(ошибка или null) — в UI-потоке. */
    static void setToken(Context c, String t, java.util.function.Consumer<String> done) {
        Context app = c.getApplicationContext();
        prefs(app).edit().putString("kp_token", t.trim()).putLong("films_at", 0).apply();
        net.execute(() -> {
            String err = null;
            try { fetchFilms(app); } catch (Exception e) { err = e.getMessage(); }
            final String er = err;
            ui.post(() -> done.accept(er));
        });
    }

    static void refreshFilms(Context c, Runnable done) {
        Context app = c.getApplicationContext();
        net.execute(() -> {
            try { fetchFilms(app); } catch (Exception e) { Net.log("films", e); }
            ui.post(done);
        });
    }

    private static String kpGet(Context c, String pathQuery) throws Exception {
        java.net.HttpURLConnection h = (java.net.HttpURLConnection) new java.net.URL(KP + pathQuery).openConnection();
        h.setConnectTimeout(10000);
        h.setReadTimeout(20000);
        h.setRequestProperty("User-Agent", Net.UA);
        h.setRequestProperty("Accept", "application/json");
        h.setRequestProperty("X-API-KEY", token(c));
        int code = h.getResponseCode();
        if (code == 401 || code == 403) { h.disconnect(); throw new Exception("токен не подошёл (HTTP " + code + ")"); }
        if (code != 200) { h.disconnect(); throw new Exception("HTTP " + code); }
        try (java.io.InputStream in = h.getInputStream()) {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[16384];
            int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            return new String(bo.toByteArray(), "UTF-8");
        } finally { h.disconnect(); }
    }

    private static String str(JSONObject o, String k) { return o == null || o.isNull(k) ? "" : o.optString(k, ""); }

    static Show film(JSONObject o) {
        Show s = new Show();
        s.movie = true;
        s.kpId = o.optInt("id");
        s.id = -s.kpId; // свои id, чтобы не пересекались с MyShows
        s.title = str(o, "name");
        s.orig = str(o, "alternativeName");
        if (s.orig.isEmpty()) s.orig = str(o, "enName");
        if (s.title.isEmpty()) s.title = s.orig;
        s.year = o.optInt("year");
        JSONObject r = o.optJSONObject("rating");
        if (r != null) { s.kp = r.optDouble("kp", 0); s.imdb = r.optDouble("imdb", 0); }
        JSONObject v = o.optJSONObject("votes");
        if (v != null) s.voted = v.optInt("kp");
        JSONObject p = o.optJSONObject("poster");
        if (p != null) { s.image = str(p, "previewUrl"); if (s.image.isEmpty()) s.image = str(p, "url"); }
        s.desc = clean(str(o, "description"));
        if (s.desc.isEmpty()) s.desc = clean(str(o, "shortDescription"));
        JSONArray g = o.optJSONArray("genres");
        List<String> gs = new ArrayList<>();
        if (g != null) for (int i = 0; i < g.length() && gs.size() < 3; i++) gs.add(str(g.optJSONObject(i), "name"));
        s.genres = android.text.TextUtils.join(", ", gs);
        JSONObject ex = o.optJSONObject("externalId");
        if (ex != null) {
            String im = str(ex, "imdb").replaceAll("\\D", "");
            if (!im.isEmpty()) try { s.imdbId = Integer.parseInt(im); } catch (Exception ignored) { }
            s.tmdbId = ex.optInt("tmdb");
        }
        JSONObject pr = o.optJSONObject("premiere");
        String d = str(pr, "digital");
        if (d.length() >= 10) s.digital = d.substring(0, 10);
        return s;
    }

    private static void fetchFilms(Context c) throws Exception {
        if (token(c).isEmpty()) return;
        SimpleDateFormat f = new SimpleDateFormat("dd.MM.yyyy", Locale.US);
        long now = System.currentTimeMillis();
        String range = f.format(new java.util.Date(now - 90 * 86400_000L)) + "-" + f.format(new java.util.Date(now));
        Map<Integer, Show> m = new LinkedHashMap<>();
        try {
            for (String g : new String[]{"фантастика", "триллер"}) {
                String q = "/v1.4/movie?page=1&limit=50&type=movie&sortField=premiere.digital&sortType=-1&notNullFields=poster.url"
                        + "&genres.name=" + java.net.URLEncoder.encode(g, "UTF-8") + "&premiere.digital=" + range;
                JSONArray docs = new JSONObject(kpGet(c, q)).optJSONArray("docs");
                if (docs == null) continue;
                for (int i = 0; i < docs.length(); i++) {
                    Show s = film(docs.optJSONObject(i));
                    if (s.kpId == 0 || (s.kp > 0 && s.kp < 5.0)) continue; // провальные — мимо
                    if (!m.containsKey(s.kpId)) m.put(s.kpId, s);
                }
            }
        } catch (Exception e) {
            prefs(c).edit().putString("films_err", String.valueOf(e.getMessage())).apply();
            throw e;
        }
        List<Show> list = new ArrayList<>(m.values());
        Collections.sort(list, (a, b) -> b.digital.compareTo(a.digital)); // свежие цифровые релизы — первыми
        if (list.size() > 30) list = new ArrayList<>(list.subList(0, 30));
        JSONArray a = new JSONArray();
        for (Show s : list) a.put(s.json());
        writeJson(file(c, "shows_films.json"), new JSONObject().put("list", a));
        prefs(c).edit().putLong("films_at", now).putString("films_err", "").apply();
        Log.i(Net.TAG, "shows: фильмов в цифре " + list.size());
    }

    // ---------- поиск: сериалы (MyShows) + фильмы (Кинопоиск, если есть токен) ----------

    static void search(Context c, String q, java.util.function.Consumer<List<Show>> done) {
        Context app = c.getApplicationContext();
        net.execute(() -> {
            List<Show> out = new ArrayList<>();
            try {
                JSONArray a = rpc("shows.Search", new JSONObject().put("query", q)).optJSONArray("result");
                if (a != null) for (int i = 0; i < a.length() && i < 12; i++) out.add(Show.of(a.optJSONObject(i)));
            } catch (Exception e) { Net.log("search shows", e); }
            if (!token(app).isEmpty()) {
                try {
                    JSONArray d = new JSONObject(kpGet(app, "/v1.4/movie/search?page=1&limit=10&query=" + java.net.URLEncoder.encode(q, "UTF-8")))
                            .optJSONArray("docs");
                    if (d != null) for (int i = 0; i < d.length(); i++) {
                        JSONObject o = d.optJSONObject(i);
                        String type = str(o, "type");
                        if (!type.isEmpty() && !type.equals("movie") && !type.equals("cartoon")) continue; // сериалы уже есть из MyShows
                        Show s = film(o);
                        if (s.kpId != 0) out.add(s);
                    }
                } catch (Exception e) { Net.log("search films", e); }
            }
            ui.post(() -> done.accept(out));
        });
    }

    static String digitalText(Show s) {
        if (s.digital.isEmpty()) return "";
        try {
            java.util.Date d = new SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(s.digital);
            return "в цифре с " + new SimpleDateFormat("d MMMM", new Locale("ru")).format(d);
        } catch (Exception e) { return ""; }
    }

    // ---------- синхронизация «Слежу» через Home Assistant ----------

    private static JSONObject haCall(Context c, JSONObject cmd) {
        if (!HaConfig.enabled(c)) return null;
        try (Ws w = new Ws(HaConfig.url(c), "/api/websocket")) {
            JSONObject hello = new JSONObject(w.receive());
            if (!"auth_required".equals(hello.optString("type"))) return null;
            w.send(new JSONObject().put("type", "auth").put("access_token", HaConfig.token(c)).toString());
            if (!"auth_ok".equals(new JSONObject(w.receive()).optString("type"))) return null;
            w.send(cmd.put("id", 1).toString());
            for (int i = 0; i < 20; i++) {
                JSONObject m = new JSONObject(w.receive());
                if (m.optInt("id") == 1 && "result".equals(m.optString("type"))) return m;
            }
        } catch (Exception e) {
            Net.log("shows ha", e);
        }
        return null;
    }

    private static void haPut(Context c, JSONObject doc) {
        try { haCall(c, new JSONObject().put("type", "frontend/set_user_data").put("key", HA_KEY).put("value", doc)); }
        catch (Exception ignored) { }
    }

    /** Пришёл список «Слежу» с другого телевизора: берём, если он новее нашего. true — взяли. */
    static boolean adopt(Context c, JSONObject remote) {
        if (remote == null || remote.optJSONArray("list") == null) return false;
        if (remote.optLong("t") <= followDoc(c).optLong("t")) return false;
        prefs(c).edit().putString("follow", remote.toString()).putLong("eps_at", 0).apply();
        Log.i(Net.TAG, "shows: «Слежу» взят с другого телевизора (" + remote.optJSONArray("list").length() + ")");
        return true;
    }

    /** Новее то, у чего больше t: забираем из HA или отдаём в HA. */
    private static void haSync(Context c) {
        try {
            JSONObject r = haCall(c, new JSONObject().put("type", "frontend/get_user_data").put("key", HA_KEY));
            if (r == null || !r.optBoolean("success")) {
                Log.i(Net.TAG, "shows: HA не ответил (" + (HaConfig.enabled(c) ? "ha " + HaClient.status : "HA не настроен") + "): " + r);
                return;
            }
            JSONObject res = r.optJSONObject("result");
            JSONObject remote = res == null ? null : res.optJSONObject("value");
            JSONObject local = followDoc(c);
            long rt = remote == null ? -1 : remote.optLong("t"), lt = local.optLong("t");
            if (rt > lt) {
                prefs(c).edit().putString("follow", remote.toString()).putLong("eps_at", 0).apply();
                Log.i(Net.TAG, "shows: «Слежу» взят из HA");
            } else if (lt > rt) {
                haPut(c, local);
                Log.i(Net.TAG, "shows: «Слежу» отправлен в HA");
            }
        } catch (Exception e) { Net.log("shows sync", e); }
    }

    // ---------- открыть в приложении ----------

    static boolean installed(Context c, String pkg) {
        try { c.getPackageManager().getPackageInfo(pkg, 0); return true; } catch (Exception e) { return false; }
    }

    /** Зона: ссылка КиноПоиска открывает сразу карточку сериала. */
    static boolean openZona(Context c, Show s) {
        try {
            Intent i;
            if (s.kpId > 0) {
                i = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.kinopoisk.ru/" + (s.movie ? "film/" : "series/") + s.kpId + "/"));
                i.setComponent(new ComponentName(ZONA, ZONA_MAIN));
            } else {
                i = c.getPackageManager().getLaunchIntentForPackage(ZONA);
                if (i == null) return false;
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
            return true;
        } catch (Exception e) {
            Net.log("open zona", e);
            return false;
        }
    }

    /** NUM: открывает карточку по ссылке TMDB. Id TMDB берём из Wikidata по IMDb (фоном, кешируем). */
    static void openNum(Context c, Show s, Runnable fail) {
        Context app = c.getApplicationContext();
        net.execute(() -> {
            int tmdb = tmdb(app, s);
            ui.post(() -> {
                try {
                    Intent i;
                    if (tmdb > 0) {
                        i = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.themoviedb.org/" + (s.movie ? "movie/" : "tv/") + tmdb));
                        i.setPackage(NUM);
                    } else {
                        i = app.getPackageManager().getLaunchIntentForPackage(NUM);
                        if (i == null) { if (fail != null) fail.run(); return; }
                        if (fail != null) fail.run(); // откроем главный экран NUM, но скажем, что сериал не нашёлся
                    }
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    app.startActivity(i);
                } catch (Exception e) {
                    Net.log("open num", e);
                    if (fail != null) fail.run();
                }
            });
        });
    }

    private static int tmdb(Context c, Show s) {
        if (s.tmdbId > 0) return s.tmdbId;
        int cached = prefs(c).getInt("tmdb_" + s.id, 0);
        if (cached != 0) return cached;
        if (s.imdbId <= 0) return -1;
        try {
            String tt = String.format(Locale.US, "tt%07d", s.imdbId);
            JSONObject r = new JSONObject(Net.getText("https://www.wikidata.org/w/api.php?action=query&list=search&format=json&srlimit=1"
                    + "&srsearch=haswbstatement:P345=" + tt));
            JSONArray hits = r.optJSONObject("query").optJSONArray("search");
            if (hits == null || hits.length() == 0) return -1;
            String q = hits.optJSONObject(0).optString("title");
            String prop = s.movie ? "P4947" : "P4983"; // TMDB id фильма / сериала
            JSONObject cl = new JSONObject(Net.getText("https://www.wikidata.org/w/api.php?action=wbgetclaims&format=json&property=" + prop + "&entity=" + q));
            JSONArray p = cl.optJSONObject("claims").optJSONArray(prop);
            if (p == null || p.length() == 0) return -1;
            int id = Integer.parseInt(p.optJSONObject(0).optJSONObject("mainsnak").optJSONObject("datavalue").optString("value"));
            prefs(c).edit().putInt("tmdb_" + s.id, id).apply();
            return id;
        } catch (Exception e) {
            Net.log("tmdb " + s.id, e);
            return -1;
        }
    }

    // ---------- постеры ----------

    private static final LruCache<String, Bitmap> mem = new LruCache<String, Bitmap>(24 * 1024 * 1024) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
    };

    static void poster(ImageView iv, String url, int widthPx) {
        iv.setTag(url);
        if (url == null || url.isEmpty()) return;
        Bitmap b = mem.get(url);
        if (b != null) { iv.setImageBitmap(b); return; }
        Context app = iv.getContext().getApplicationContext();
        net.execute(() -> {
            try {
                File dir = new File(app.getCacheDir(), "posters");
                if (!dir.isDirectory() && !dir.mkdirs()) return;
                File f = new File(dir, Integer.toHexString(url.hashCode()) + ".jpg");
                if (!f.isFile()) Net.download(url, f);
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(f.getPath(), o);
                int s = 1;
                while (o.outWidth / (s * 2) >= widthPx) s *= 2;
                BitmapFactory.Options d = new BitmapFactory.Options();
                d.inSampleSize = s;
                Bitmap bm = BitmapFactory.decodeFile(f.getPath(), d);
                if (bm == null) { f.delete(); return; }
                mem.put(url, bm);
                ui.post(() -> { if (url.equals(iv.getTag())) iv.setImageBitmap(bm); });
            } catch (Exception e) { Net.log("poster", e); }
        });
    }
}
