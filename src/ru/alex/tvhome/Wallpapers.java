package ru.alex.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.List;
import java.util.Random;

/**
 * Подбор фото природы под время суток, погоду и сезон.
 * Источник — Wikimedia Commons: «Quality images» (запасной уровень — «Valued images»), сезон и погода
 * в каждом запросе, без людей на переднем плане, пляжи — только летом. Скачанное кешируется,
 * офлайн показывается кеш. Неудачное фото можно скрыть навсегда.
 */
final class Wallpapers {
    private static final int CACHE_MAX_NATURE = 80;
    private static final int CACHE_MAX_SPACE = 50; // 4K весят 2–5 МБ — держим меньше
    private static final long LIST_TTL = 6 * 3600 * 1000L;
    private static final long SPACE_TTL = 24 * 3600 * 1000L; // космос не зависит от погоды — раз в сутки

    private final File dir;
    private final SharedPreferences prefs;
    private final Random rnd = new Random();
    private final boolean space;
    private final int cacheMax;

    Wallpapers(Context ctx) { this(ctx, false); }

    /** space=true — фон «Космос»: свой кеш, 4K-превью, свой список. */
    Wallpapers(Context ctx, boolean space) {
        this.space = space;
        cacheMax = space ? CACHE_MAX_SPACE : CACHE_MAX_NATURE;
        dir = new File(ctx.getCacheDir(), space ? "wall_space" : "wall");
        dir.mkdirs();
        prefs = ctx.getSharedPreferences("walls", Context.MODE_PRIVATE);
        if (prefs.getInt("cacheVer", 1) < 2) { // фото, подобранные по старым правилам (лето, люди), — убрать
            File[] fs = dir.listFiles();
            if (fs != null) for (File f : fs) f.delete();
            prefs.edit().putInt("cacheVer", 2).apply();
        }
    }

    /** Ключ сцены, например "v3_day_cloudy_autumn" (v3 — расширенный подбор, старые списки не используются). */
    static String sceneKey(Weather.Phase p, Weather.Sky s) {
        return "v3_" + p.name().toLowerCase() + "_" + s.name().toLowerCase() + "_" + season();
    }

    static String season() {
        int m = Calendar.getInstance().get(Calendar.MONTH) + 1;
        if (m == 12 || m <= 2) return "winter";
        if (m <= 5) return "spring";
        if (m <= 8) return "summer";
        return "autumn";
    }

    static boolean summer() { return season().equals("summer"); }

    /** Основы запросов для неба и времени суток (короткие: длинные почти ничего не находят). */
    private static String[] bases(Weather.Phase p, Weather.Sky s) {
        switch (s) {
            case CLEAR:
                switch (p) {
                    case DAWN: return new String[]{"sunrise", "morning mist", "sunrise lake", "dawn", "morning forest", "sunrise mountains"};
                    case DAY: return new String[]{"forest", "lake", "river", "landscape", "mountains", "meadow", "field", "valley", "waterfall", "hills"};
                    case DUSK: return new String[]{"sunset", "sunset lake", "evening", "sunset mountains", "sunset river", "golden hour"};
                    default: return new String[]{"night", "moon", "starry sky", "milky way", "night landscape"};
                }
            case CLOUDY:
                switch (p) {
                    case DAWN: return new String[]{"morning clouds", "overcast", "morning mist", "dawn clouds"};
                    case DAY: return new String[]{"overcast", "cloudy", "clouds", "grey sky", "mist", "cloudy forest", "cloudy lake", "cloudy mountains"};
                    case DUSK: return new String[]{"dusk", "evening clouds", "cloudy sunset", "sunset clouds", "evening", "twilight"};
                    default: return new String[]{"night clouds", "night", "night landscape"};
                }
            case FOG:
                return p == Weather.Phase.NIGHT ? new String[]{"fog night", "mist night", "night fog"}
                        : new String[]{"fog", "mist", "foggy forest", "fog lake", "fog river", "foggy morning"};
            case RAIN:
                return p == Weather.Phase.NIGHT ? new String[]{"rain night", "night"}
                        : new String[]{"rain", "rainy", "rain clouds", "rainy forest", "rain lake"};
            case SNOW:
                return p == Weather.Phase.NIGHT ? new String[]{"snow night", "winter night", "winter moon"}
                        : new String[]{"snowfall", "snow forest", "snowy", "winter landscape", "snow mountains", "frozen lake"};
            default:
                return new String[]{"thunderstorm", "storm clouds", "lightning"};
        }
    }

    /** Поисковые запросы для сцены: сезон в каждом + пара запросов «северная природа». */
    static List<String> queries(Weather.Phase p, Weather.Sky s) {
        String season = s == Weather.Sky.SNOW ? "winter" : season();
        List<String> q = new ArrayList<>();
        for (String b : bases(p, s)) q.add(season + " " + b);
        if (p == Weather.Phase.DAY || p == Weather.Phase.DAWN) {
            q.add(season + " Russia");
            q.add(season + " Finland");
        }
        return q;
    }

    /** Люди на переднем плане — такие снимки не берём (по описанию/категориям файла). */
    static final String PEOPLE = "-people -person -portrait -woman -women -man -men -girl -boy -child -children "
            + "-kids -family -tourist -tourists -hiker -hikers -wedding -model -selfie -crowd -festival "
            + "-sport -cyclist -runner -fishermen -couple";

    /** Слова, которые не подходят к текущему сезону. */
    static String offSeason() {
        switch (season()) {
            case "summer": return "-winter -snow -autumn";
            case "winter": return "-summer -beach -autumn -spring -swimming";
            case "autumn": return "-summer -beach -sea -palm -tropical -swimming -spring -winter";
            default: return "-summer -beach -sea -palm -tropical -swimming -autumn";
        }
    }

    private static final String[] TITLE_PEOPLE = {"people", "person", "portrait", "woman", "women", " man ", " men ",
            "girl", "boy", "child", "kids", "family", "tourist", "hiker", "wedding", "model", "selfie", "crowd",
            "festival", "cyclist", "runner", "couple", "мужчина", "женщина", "девушка", "дети", "портрет"};
    private static final String[] TITLE_SUMMER = {"beach", "strand", "plage", "playa", "spiaggia", "palm",
            "tropical", "summer", "пляж", "лето"};

    /** Доп. отсев по имени файла (в нём часто пишут, что на фото). */
    static boolean titleOk(String title) {
        String t = " " + title.toLowerCase(Locale.ROOT).replace('_', ' ').replace('-', ' ') + " ";
        for (String w : TITLE_PEOPLE) if (t.contains(w)) return false;
        if (!summer()) for (String w : TITLE_SUMMER) if (t.contains(w)) return false;
        return true;
    }

    // ---------- скрытые фото ----------

    private static String id(String entry) { return Integer.toHexString(entry.split("\t")[0].hashCode()); }

    void hide(String entry) {
        if (entry == null) return;
        Set<String> h = new HashSet<>(prefs.getStringSet("hidden", Collections.emptySet()));
        h.add(id(entry));
        prefs.edit().putStringSet("hidden", h).apply();
        File f = new File(dir, Integer.toHexString(entry.hashCode()) + ".jpg");
        f.delete();
    }

    boolean hidden(String entry) {
        return prefs.getStringSet("hidden", Collections.emptySet()).contains(id(entry));
    }

    /** Сколько фото держать в списке сцены и сколько последних показанных не повторять. */
    private static final int LIST_MAX = 400;
    private static final int RECENT_MAX = 80;

    /**
     * Список URL для сцены: из кеша prefs (6 ч) или свежий подбор в Commons. Вызывать в фоне.
     * Порядок каждый раз новый: сначала то, что давно (или ни разу) не показывали.
     */
    List<String> urlsFor(Weather.Phase p, Weather.Sky s) {
        String key = sceneKey(p, s);
        long ts = prefs.getLong(key + ".ts", 0);
        String saved = prefs.getString(key, null);
        if (saved != null && System.currentTimeMillis() - ts < LIST_TTL) {
            return order(visible(Arrays.asList(saved.split("\n"))));
        }
        List<String> qs = queries(p, s);
        LinkedHashSet<String> set = new LinkedHashSet<>();
        boolean limited = false;
        // 1) «Quality images»: первая страница выдачи и ещё одна дальше, если она есть
        for (String query : qs) {
            try { set.addAll(searchCommons(query, 0, true)); }
            catch (Exception e) { Net.log("commons '" + query + "' t0", e); if (is429(e)) { limited = true; break; } }
        }
        // 2) «Featured pictures» — лучшие снимки Commons, по первым запросам сцены
        for (int i = 0; i < Math.min(4, qs.size()) && !limited; i++) {
            try { set.addAll(searchCommons(qs.get(i), 2, false)); }
            catch (Exception e) { Net.log("commons '" + qs.get(i) + "' t2", e); if (is429(e)) limited = true; }
        }
        // 3) мало — добираем «Valued images» (тоже отобранные)
        if (set.size() < 60 && !limited) {
            for (String query : qs) {
                try { set.addAll(searchCommons(query, 1, false)); }
                catch (Exception e) { Net.log("commons '" + query + "' t1", e); if (is429(e)) break; }
            }
        }
        List<String> out = new ArrayList<>(set);
        android.util.Log.i(Net.TAG, "scene " + key + ": collected " + out.size() + " photos" + (limited ? " (429)" : ""));
        if (out.isEmpty() && saved != null) return order(visible(Arrays.asList(saved.split("\n"))));
        Collections.shuffle(out, rnd);
        if (out.size() > LIST_MAX) out = new ArrayList<>(out.subList(0, LIST_MAX));
        if (!out.isEmpty()) {
            // маленький список (например, после отказа 429) — повторить через 30 мин, а не через 6 ч
            long ts2 = out.size() >= 40 ? System.currentTimeMillis() : System.currentTimeMillis() - LIST_TTL + 30 * 60_000L;
            prefs.edit().putString(key, join(out)).putLong(key + ".ts", ts2).apply();
        }
        return order(visible(out));
    }

    private static boolean is429(Exception e) { return String.valueOf(e.getMessage()).contains("429"); }

    private List<String> visible(List<String> l) {
        List<String> r = new ArrayList<>();
        for (String e : l) if (!e.isEmpty() && !hidden(e)) r.add(e);
        return r;
    }

    // ---------- недавно показанные ----------

    /** Запомнить, что фото показано: такие уходят в конец очереди, пока не наберётся RECENT_MAX других. */
    void markShown(String entry) {
        if (entry == null) return;
        String id = id(entry);
        List<String> r = new ArrayList<>(Arrays.asList(prefs.getString("recent", "").split(",")));
        r.remove(id);
        r.remove("");
        r.add(id);
        while (r.size() > RECENT_MAX) r.remove(0);
        prefs.edit().putString("recent", android.text.TextUtils.join(",", r)).apply();
    }

    /** Перемешать заново и поставить недавно показанные в конец (самые свежие — в самый конец). */
    private List<String> order(List<String> l) {
        List<String> r = new ArrayList<>(l);
        Collections.shuffle(r, rnd);
        List<String> recent = Arrays.asList(prefs.getString("recent", "").split(","));
        java.util.Map<String, Integer> pos = new java.util.HashMap<>();
        for (int i = 0; i < recent.size(); i++) if (!recent.get(i).isEmpty()) pos.put(recent.get(i), i);
        Collections.sort(r, (a, b) -> Integer.compare(
                pos.getOrDefault(id(a), -1), pos.getOrDefault(id(b), -1))); // сортировка устойчива: свежие не трогает
        return r;
    }

    /**
     * Поиск в Commons. Уровень 0 — «Quality images», 1 — «Valued images», 2 — «Featured pictures».
     * Сортировка по релевантности (random подмешивал случайные несвязанные файлы);
     * more=true — взять ещё одну страницу выдачи (следующие 50), если она есть.
     */
    List<String> searchCommons(String query, int tier, boolean more) throws Exception {
        // шаблоны можно переопределить через DebugReceiver (--es tpl0 "... %s ...") без пересборки
        String def = tier == 0 ? "%s incategory:\"Quality images\""
                : tier == 1 ? "%s incategory:\"Valued images\""
                : "%s incategory:\"Featured pictures on Wikimedia Commons\"";
        String tpl = prefs.getString("tpl" + tier, def);
        String search = tpl.replace("%s", query + " " + PEOPLE + " " + offSeason());
        throttle();
        JSONObject o = commonsQuery(search, 0);
        List<String> res = parseCommons(o, query, search, tier);
        JSONObject cont = o.optJSONObject("continue");
        if (more && cont != null && cont.has("gsroffset")) {
            throttle();
            res.addAll(parseCommons(commonsQuery(search, cont.optInt("gsroffset", 50)), query, search, tier));
        }
        return res;
    }

    private static long lastQuery;

    /** Не чаще одного запроса в 1,5 с — иначе Wikimedia отвечает 429 «слишком много запросов». */
    private static synchronized void throttle() {
        long wait = lastQuery + 1500 - System.currentTimeMillis();
        if (wait > 0) try { Thread.sleep(wait); } catch (InterruptedException ignored) { }
        lastQuery = System.currentTimeMillis();
    }

    private static JSONObject commonsQuery(String search, int offset) throws Exception { return commonsQuery(search, offset, 1920); }

    private static JSONObject commonsQuery(String search, int offset, int width) throws Exception {
        String url = "https://commons.wikimedia.org/w/api.php?action=query&format=json&formatversion=2"
                + "&generator=search&gsrnamespace=6&gsrlimit=50&gsroffset=" + offset
                + "&gsrsearch=" + Uri.encode(search)
                + "&prop=imageinfo&iiprop=url%7Csize%7Cmime%7Cextmetadata&iiurlwidth=" + width
                + "&iiextmetadatafilter=GPSLatitude%7CGPSLongitude%7CObjectName";
        return new JSONObject(Net.getText(url));
    }

    private List<String> parseCommons(JSONObject o, String query, String search, int tier) throws Exception {
        List<String> res = new ArrayList<>();
        JSONObject q = o.optJSONObject("query");
        if (q == null) {
            android.util.Log.i(Net.TAG, "commons no 'query' for [" + search + "]: "
                    + o.toString().substring(0, Math.min(300, o.toString().length())));
            return res;
        }
        JSONArray pages = q.optJSONArray("pages");
        if (pages == null) return res;
        int seen = 0, badTitle = 0, badMime = 0, small = 0, badAspect = 0, noThumb = 0;
        String sample = null;
        int minW = prefs.getInt("minw", 1280);
        for (int i = 0; i < pages.length(); i++) {
            JSONObject page = pages.getJSONObject(i);
            JSONArray ii = page.optJSONArray("imageinfo");
            if (ii == null || ii.length() == 0) continue;
            seen++;
            if (!titleOk(page.optString("title", ""))) { badTitle++; continue; }
            JSONObject info = ii.getJSONObject(0);
            String mime = info.optString("mime", "");
            if (!mime.isEmpty() && !"image/jpeg".equals(mime)) { badMime++; continue; }
            int w = info.optInt("width"), h = info.optInt("height");
            if (w > 0 && w < minW) { small++; continue; }
            if (w > 0 && h > 0 && ((double) w / h < 1.25 || (double) w / h > 2.6)) { badAspect++; continue; } // горизонтальные
            // превью может быть на thumb.wikimedia.org или upload.wikimedia.org; оригинал — запасной вариант
            String thumb = wm(info.optString("thumburl", ""));
            String orig = wm(info.optString("url", ""));
            if (thumb.isEmpty()) thumb = orig;
            if (!thumb.isEmpty()) {
                String e = orig.isEmpty() || orig.equals(thumb) ? thumb : thumb + "\t" + orig;
                res.add(e + meta(info.optJSONObject("extmetadata"), page.optString("title", "")));
            }
            else { noThumb++; if (sample == null) sample = info.toString(); }
        }
        android.util.Log.i(Net.TAG, "commons t" + tier + " [" + query + "]: " + seen + " files, " + res.size()
                + " fit (title " + badTitle + ", mime " + badMime + ", small " + small + ", aspect " + badAspect + ", url " + noThumb + ")");
        if (sample != null) android.util.Log.i(Net.TAG, "commons sample: " + sample.substring(0, Math.min(400, sample.length())));
        return res;
    }

    /** https-ссылка на любой хост *.wikimedia.org без трекинговых параметров, иначе "". */
    private static String wm(String u) {
        if (u.startsWith("//")) u = "https:" + u;
        int q = u.indexOf('?');
        if (q > 0) u = u.substring(0, q);
        Uri p = Uri.parse(u);
        String host = p.getHost();
        return "https".equals(p.getScheme()) && host != null && host.endsWith(".wikimedia.org") ? u : "";
    }

    // ---------- фон «Космос» ----------

    /**
     * Запросы для космоса: снимки JWST, Hubble, ESO и избранное Commons. Все — от 3840 px по ширине (4K),
     * только JPEG. filew/filemime — фильтры поиска Commons, отсекают мелочь ещё на сервере.
     */
    private static final String[] SPACE_QUERIES = {
            "nebula \"James Webb\"", "galaxy \"James Webb\"", "\"deep field\"", "\"Pillars of Creation\"",
            "nebula Hubble", "galaxy Hubble", "\"star cluster\" Hubble", "\"planetary nebula\"",
            "nebula ESO", "galaxy ESO", "\"Milky Way\" ESO", "\"Milky Way\" night sky",
            "nebula incategory:\"Featured pictures on Wikimedia Commons\"",
            "galaxy incategory:\"Featured pictures on Wikimedia Commons\"",
            "\"Milky Way\" incategory:\"Featured pictures on Wikimedia Commons\"",
            "aurora \"International Space Station\"", "\"artist's impression\" exoplanet",
            "supernova Hubble", "\"spiral galaxy\"", "\"Orion Nebula\"", "\"Carina Nebula\"",
    };

    /** Что в космической выдаче — не картинка для фона: схемы, графики, сам телескоп, люди. */
    private static final String[] SPACE_JUNK = {"diagram", "chart", "graph", "spectrum", "logo", "infographic", "map ",
            "annotated", "labeled", "labelled", "comparison", "compass", "scale bar", "mirror", "telescope at",
            "launch", "rocket", "team", "scientist", "engineer", "astronaut", "crew", "poster", "banner", "text",
            "plot", "data", "observatory building", "dome", "antenna", "cleanroom", "clean room", "assembly", "model of",
            "instrument", "vibration", "testing", " test", "hardware", "spacecraft", "sunshield", "cryo", "miri", "nircam",
            "nirspec", "segment", "unboxing", "shipping", "hangar", "facility", "workers", "technician",
            // фото с мероприятий NASA (люди на сцене): «(NHQ2026…)», панели, брифинги, лекции
            "(nhq", "nhq20", "(ksc", "(jsc", "event", "panel", "briefing", "conference", "ceremony", "symposium",
            "lecture", "speaks", "speech", "talk ", "presentation", "audience", "award", "visit", "administrator",
            "meeting", "workshop", "press", "media day", "celebration", "anniversary", "legacy", "exhibit", "museum"};

    /** То же самое — минусами прямо в поиске Commons, чтобы такое не занимало место в выдаче. */
    private static final String SPACE_MINUS = "-NHQ -panel -briefing -conference -ceremony -event -lecture -audience";

    /**
     * В названии должно быть что-то про космос — иначе в выдачу лезут однофамильцы: «SNR» (рок-группа,
     * парламент, заповедник), Samsung Galaxy, кафе «Nebula». Плюс русские, испанские, французские слова и
     * метки архивов Hubble/ESO (heic…, potw…, eso…).
     */
    private static final java.util.regex.Pattern SPACE_WORDS = java.util.regex.Pattern.compile(
            "nebula|n[eé]buleuse|nebulosa|туманност|galax|галакт|\\bngc ?\\d|\\bic ?\\d|\\bm ?\\d{1,3}\\b|messier|"
            + "\\bsh2|cluster|скоплен|supernova|сверхнов|milky ?way|млечн|v[ií]a l[aá]ctea|voie lact|"
            + "\\bstars?\\b|starry|звезд|звёзд|estrell|[ée]toile|cosmic|космос|deep field|webb|jwst|hubble|"
            + "\\beso\\d|\\bheic|potw|\\bopo\\d|carina|orion|орион|andromeda|андромед|pillars|aurora|сияни|"
            + "jupiter|юпитер|saturn|сатурн|planet|планет|comet|комет|quasar|pulsar|night sky|ночное небо|"
            + "astrophot|astronom|астроном|eclipse|затмени|lagoon|trifid|horsehead|eagle|crab|whirlpool|sombrero|"
            + "pleiades|плеяд|magellan|магеллан");

    static boolean spaceTitleOk(String title) {
        String t = " " + title.toLowerCase(Locale.ROOT).replace('_', ' ') + " ";
        for (String w : SPACE_JUNK) if (t.contains(w)) return false;
        if (!SPACE_WORDS.matcher(t).find()) return false;
        return titleOk(title);
    }

    /** Список 4K-снимков космоса: кеш на сутки, иначе свежий подбор. Порядок случайный, недавние — в конце. Вызывать в фоне. */
    List<String> spaceUrls() {
        String key = "space_v5"; // v5 — в названии обязательно что-то про космос; старый кеш чистится
        long ts = prefs.getLong(key + ".ts", 0);
        String saved = prefs.getString(key, null);
        if (saved != null && System.currentTimeMillis() - ts < SPACE_TTL) return order(visible(Arrays.asList(saved.split("\n"))));
        if (saved == null) { // новые правила подбора — старый кеш (там бывали концерты и цветы) выбрасываем
            File[] old = dir.listFiles();
            if (old != null) for (File f : old) f.delete();
        }
        LinkedHashSet<String> set = new LinkedHashSet<>();
        boolean limited = false;
        List<String> qs = new ArrayList<>(Arrays.asList(SPACE_QUERIES));
        Collections.shuffle(qs, rnd);
        for (String q : qs) {
            try { set.addAll(searchSpace(q)); }
            catch (Exception e) { Net.log("space '" + q + "'", e); if (is429(e)) { limited = true; break; } }
            if (set.size() >= LIST_MAX) break;
        }
        List<String> out = new ArrayList<>(set);
        android.util.Log.i(Net.TAG, "space: collected " + out.size() + " photos" + (limited ? " (429)" : ""));
        if (out.isEmpty() && saved != null) return order(visible(Arrays.asList(saved.split("\n"))));
        Collections.shuffle(out, rnd);
        if (out.size() > LIST_MAX) out = new ArrayList<>(out.subList(0, LIST_MAX));
        if (!out.isEmpty()) {
            long ts2 = out.size() >= 50 ? System.currentTimeMillis() : System.currentTimeMillis() - SPACE_TTL + 30 * 60_000L;
            prefs.edit().putString(key, join(out)).putLong(key + ".ts", ts2).apply();
        }
        return order(visible(out));
    }

    /** Один запрос космоса: первая страница и ещё одна, если есть. Только превью 3840 px — оригиналы бывают по 100 МБ. */
    List<String> searchSpace(String query) throws Exception {
        String search = query + " " + SPACE_MINUS + " " + PEOPLE + " filew:>3839 filemime:image/jpeg";
        List<String> res = new ArrayList<>();
        int offset = 0;
        for (int pageNo = 0; pageNo < 2; pageNo++) {
            throttle();
            JSONObject o = commonsQuery(search, offset, 3840);
            JSONObject q = o.optJSONObject("query");
            JSONArray pages = q == null ? null : q.optJSONArray("pages");
            int seen = 0, junk = 0, shape = 0;
            if (pages != null) for (int i = 0; i < pages.length(); i++) {
                JSONObject page = pages.getJSONObject(i);
                JSONArray ii = page.optJSONArray("imageinfo");
                if (ii == null || ii.length() == 0) continue;
                seen++;
                String title = page.optString("title", "");
                if (!spaceTitleOk(title)) { junk++; continue; }
                JSONObject info = ii.getJSONObject(0);
                int w = info.optInt("width"), h = info.optInt("height");
                if (w < 3840 || h <= 0 || (double) w / h < 1.0 || (double) w / h > 2.6) { shape++; continue; } // широкие и квадратные
                String thumb = wm(info.optString("thumburl", ""));
                if (thumb.isEmpty() || info.optInt("thumbwidth", 0) < 3800) { shape++; continue; }
                res.add(thumb + meta(null, title));
            }
            android.util.Log.i(Net.TAG, "space [" + query + "] p" + pageNo + ": " + seen + " files, junk " + junk + ", shape " + shape);
            JSONObject cont = o.optJSONObject("continue");
            if (cont == null || !cont.has("gsroffset")) break;
            offset = cont.optInt("gsroffset", 50);
        }
        return res;
    }

    // ---------- подпись «где снято» ----------

    /**
     * Доп. поля записи: "\t@широта,долгота" (откуда снято) и "\t#название" (как назвал автор).
     * Запись: "превью[\tоригинал][\t@lat,lon][\t#title]".
     */
    private static String meta(JSONObject em, String fileTitle) {
        StringBuilder sb = new StringBuilder();
        if (em != null) {
            try {
                double lat = Double.parseDouble(metaValue(em, "GPSLatitude"));
                double lon = Double.parseDouble(metaValue(em, "GPSLongitude"));
                if (Math.abs(lat) > 0.01 || Math.abs(lon) > 0.01)
                    sb.append("\t@").append(String.format(Locale.ROOT, "%.4f,%.4f", lat, lon));
            } catch (Exception ignored) { }
        }
        String name = cleanTitle(em == null ? "" : metaValue(em, "ObjectName"));
        if (name.isEmpty()) name = cleanTitle(fileTitle);
        if (!name.isEmpty()) sb.append("\t#").append(name.replace('\t', ' ').replace('\n', ' '));
        return sb.toString();
    }

    private static String metaValue(JSONObject em, String k) {
        JSONObject o = em.optJSONObject(k);
        return o == null ? "" : o.optString("value", "");
    }

    /** Название файла → читаемая подпись: без "File:", расширения, дат, номеров кадров и HTML. "" — если смысла нет. */
    static String cleanTitle(String t) {
        if (t == null) return "";
        t = t.replaceAll("<[^>]*>", " ").replaceFirst("^(?i)(File|Файл):", "");
        t = t.replaceAll("(?i)\\.(jpe?g|png|tiff?|webp)$", "");
        t = t.replace('_', ' ');
        t = t.replaceAll("(?i)\\b(panoramio|flickr|wikimedia|commons)\\b", " ");    // источник выгрузки
        t = t.replaceAll("\\([^)]*\\d[^)]*\\)", " ");                              // (2019), (DSC_1234)
        t = t.replaceAll("(?i)\\b(IMG|DSC[FN]?|PXL|DJI|GOPR|P|PB|PA|PC|_MG)[ _-]?\\d{3,}\\b", " ");
        t = t.replaceAll("\\b\\d{4}[-.]\\d{1,2}[-.]\\d{1,2}\\b", " ");             // даты
        t = t.replaceAll("\\b\\d{3,}\\b", " ");                                     // длинные номера
        t = t.replaceAll("\\s*[-–—,]\\s*([-–—,]\\s*)+", " - ");
        t = t.replaceAll("\\s+", " ").trim().replaceAll("^[-–—,.\\s]+|[-–—,.\\s]+$", "");
        int letters = 0;
        for (char ch : t.toCharArray()) if (Character.isLetter(ch)) letters++;
        if (letters < 5) return "";
        return t.length() > 70 ? t.substring(0, 69).trim() + "…" : t;
    }

    private static long lastGeo;

    /**
     * Подпись для фото: место по координатам по-русски (OpenStreetMap, кешируется), иначе название от автора.
     * null — подписать нечем. Вызывать в фоне.
     */
    String caption(String entry) {
        if (entry == null) return null;
        String coords = null, title = null;
        for (String part : entry.split("\t")) {
            if (part.startsWith("@")) coords = part.substring(1);
            else if (part.startsWith("#")) title = part.substring(1);
        }
        if (coords != null) {
            String place = place(coords);
            if (place != null) return place;
        }
        return title == null || title.isEmpty() ? null : title;
    }

    private String place(String coords) {
        String[] ll = coords.split(",");
        if (ll.length != 2) return null;
        String key;
        try {
            key = "geo_" + String.format(Locale.ROOT, "%.2f_%.2f", Double.parseDouble(ll[0]), Double.parseDouble(ll[1]));
        } catch (Exception e) { return null; }
        String cached = prefs.getString(key, null);
        if (cached != null) return cached.isEmpty() ? null : cached;
        try {
            synchronized (Wallpapers.class) { // OpenStreetMap просит не чаще раза в секунду
                long wait = lastGeo + 1100 - System.currentTimeMillis();
                if (wait > 0) Thread.sleep(wait);
                lastGeo = System.currentTimeMillis();
            }
            JSONObject o = new JSONObject(Net.getText("https://nominatim.openstreetmap.org/reverse?format=jsonv2&zoom=10"
                    + "&accept-language=ru&lat=" + ll[0] + "&lon=" + ll[1]));
            JSONObject a = o.optJSONObject("address");
            String res = "";
            if (a != null) {
                String local = first(a, "city", "town", "village", "hamlet", "municipality", "county");
                String region = first(a, "state", "region", "province");
                String country = a.optString("country", "");
                // коротко: «посёлок/город, страна»; нет такого — «регион, страна»
                List<String> parts = new ArrayList<>();
                String where = !local.isEmpty() ? local : region;
                if (!where.isEmpty()) parts.add(where);
                if (!country.isEmpty() && !country.equals(where)) parts.add(country);
                res = android.text.TextUtils.join(", ", parts);
            }
            prefs.edit().putString(key, res).apply();
            return res.isEmpty() ? null : res;
        } catch (Exception e) {
            Net.log("geo " + coords, e);
            return null; // сеть подвела — в следующий раз спросим снова
        }
    }

    private static String first(JSONObject a, String... keys) {
        for (String k : keys) { String v = a.optString(k, ""); if (!v.isEmpty()) return v; }
        return "";
    }

    /** Файл картинки в кеше (скачивает при необходимости). Вызывать в фоне. */
    File fetch(String entry) throws Exception {
        File f = new File(dir, Integer.toHexString(entry.hashCode()) + ".jpg");
        if (!f.exists()) {
            Exception last = null;
            for (String url : entry.split("\t")) {
                if (!url.startsWith("https://")) continue; // доп. поля (@координаты, #название)
                try { Net.download(url, f); last = null; break; } catch (Exception e) { last = e; }
            }
            if (last != null) throw last;
            trim();
        }
        f.setLastModified(System.currentTimeMillis());
        return f;
    }

    /** Любая картинка из кеша — на случай, когда сети нет. */
    File anyCached() {
        File[] fs = dir.listFiles((d, n) -> n.endsWith(".jpg"));
        if (fs == null || fs.length == 0) return null;
        return fs[rnd.nextInt(fs.length)];
    }

    private void trim() {
        File[] fs = dir.listFiles((d, n) -> n.endsWith(".jpg"));
        if (fs == null || fs.length <= cacheMax) return;
        Arrays.sort(fs, Comparator.comparingLong(File::lastModified));
        for (int i = 0; i < fs.length - cacheMax; i++) fs[i].delete();
    }

    private static String join(List<String> l) {
        StringBuilder sb = new StringBuilder();
        for (String s : l) { if (sb.length() > 0) sb.append('\n'); sb.append(s); }
        return sb.toString();
    }
}
