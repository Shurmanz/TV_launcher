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
    private static final int CACHE_MAX = 80;
    private static final long LIST_TTL = 6 * 3600 * 1000L;

    private final File dir;
    private final SharedPreferences prefs;
    private final Random rnd = new Random();

    Wallpapers(Context ctx) {
        dir = new File(ctx.getCacheDir(), "wall");
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

    private static JSONObject commonsQuery(String search, int offset) throws Exception {
        String url = "https://commons.wikimedia.org/w/api.php?action=query&format=json&formatversion=2"
                + "&generator=search&gsrnamespace=6&gsrlimit=50&gsroffset=" + offset
                + "&gsrsearch=" + Uri.encode(search)
                + "&prop=imageinfo&iiprop=url%7Csize%7Cmime&iiurlwidth=1920";
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
            if (!thumb.isEmpty()) res.add(orig.isEmpty() || orig.equals(thumb) ? thumb : thumb + "\t" + orig);
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

    /** Файл картинки в кеше (скачивает при необходимости). Запись: "превью[\tоригинал]". Вызывать в фоне. */
    File fetch(String entry) throws Exception {
        File f = new File(dir, Integer.toHexString(entry.hashCode()) + ".jpg");
        if (!f.exists()) {
            Exception last = null;
            for (String url : entry.split("\t")) {
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
        if (fs == null || fs.length <= CACHE_MAX) return;
        Arrays.sort(fs, Comparator.comparingLong(File::lastModified));
        for (int i = 0; i < fs.length - CACHE_MAX; i++) fs[i].delete();
    }

    private static String join(List<String> l) {
        StringBuilder sb = new StringBuilder();
        for (String s : l) { if (sb.length() > 0) sb.append('\n'); sb.append(s); }
        return sb.toString();
    }
}
