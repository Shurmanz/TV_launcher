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
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/**
 * Подбор фото природы под время суток, погоду и сезон.
 * Источник — Wikimedia Commons, только файлы из «Quality images» (свободные лицензии),
 * горизонтальные, шириной от 2400 px. Скачанное кешируется, офлайн показывается кеш.
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
    }

    /** Ключ сцены, например "day_cloudy_autumn". */
    static String sceneKey(Weather.Phase p, Weather.Sky s) {
        return p.name().toLowerCase() + "_" + s.name().toLowerCase() + "_" + season();
    }

    static String season() {
        int m = Calendar.getInstance().get(Calendar.MONTH) + 1;
        if (m == 12 || m <= 2) return "winter";
        if (m <= 5) return "spring";
        if (m <= 8) return "summer";
        return "autumn";
    }

    /** Поисковые запросы для сцены (английские — так у Commons больше совпадений). */
    static List<String> queries(Weather.Phase p, Weather.Sky s) {
        String season = season();
        String seasonWord = season.equals("summer") ? "" : season + " ";
        List<String> q = new ArrayList<>();
        switch (s) {
            case CLEAR:
                switch (p) {
                    case DAWN: q.add("sunrise landscape"); q.add("sunrise lake"); q.add(seasonWord + "sunrise"); break;
                    case DAY: q.add(seasonWord + "landscape"); q.add(seasonWord + "forest"); q.add("mountain lake"); q.add(seasonWord + "river valley"); break;
                    case DUSK: q.add("sunset landscape"); q.add("sunset sea"); q.add(seasonWord + "sunset"); break;
                    default: q.add("milky way landscape"); q.add("starry sky mountains"); q.add("night landscape moon"); break;
                }
                break;
            case CLOUDY:
                switch (p) {
                    case DAWN: q.add("sunrise clouds landscape"); q.add("morning clouds"); break;
                    case DAY: q.add(seasonWord + "clouds landscape"); q.add("overcast landscape"); q.add(seasonWord + "meadow clouds"); break;
                    case DUSK: q.add("sunset clouds"); q.add("evening clouds landscape"); break;
                    default: q.add("moon clouds night"); q.add("night clouds landscape"); break;
                }
                break;
            case FOG:
                switch (p) {
                    case NIGHT: q.add("fog night"); q.add("misty night"); break;
                    case DUSK: q.add("fog sunset"); q.add("mist evening"); break;
                    default: q.add(seasonWord + "foggy forest"); q.add("misty morning"); q.add("fog lake"); break;
                }
                break;
            case RAIN:
                switch (p) {
                    case NIGHT: q.add("night rain"); q.add("storm clouds night"); break;
                    case DUSK: q.add("rain clouds sunset"); q.add("dramatic clouds sunset"); break;
                    default: q.add("rain landscape"); q.add("rainy forest"); q.add("rain clouds landscape"); break;
                }
                break;
            case SNOW:
                switch (p) {
                    case DAWN: q.add("winter sunrise"); q.add("snow sunrise"); break;
                    case DAY: q.add("snowy forest"); q.add("snowfall landscape"); q.add("winter landscape snow"); break;
                    case DUSK: q.add("winter sunset"); q.add("snow sunset"); break;
                    default: q.add("winter night snow"); q.add("snowy night"); break;
                }
                break;
            case STORM:
                q.add("thunderstorm"); q.add("lightning landscape"); q.add("storm clouds landscape");
                break;
        }
        return q;
    }

    /** Список URL для сцены: из кеша prefs или свежий запрос к Commons. Вызывать в фоне. */
    List<String> urlsFor(Weather.Phase p, Weather.Sky s) {
        String key = sceneKey(p, s);
        long ts = prefs.getLong(key + ".ts", 0);
        String saved = prefs.getString(key, null);
        if (saved != null && System.currentTimeMillis() - ts < LIST_TTL) {
            return new ArrayList<>(Arrays.asList(saved.split("\n")));
        }
        List<String> out = new ArrayList<>();
        for (String query : queries(p, s)) {
            for (int tier = 0; tier < 2; tier++) {
                try {
                    List<String> r = searchCommons(query, tier);
                    out.addAll(r);
                    if (r.size() >= 8) break; // хватает качественных — запасной уровень не нужен
                } catch (Exception e) {
                    Net.log("commons '" + query + "' t" + tier, e);
                }
            }
        }
        if (out.isEmpty() && saved != null) return new ArrayList<>(Arrays.asList(saved.split("\n")));
        Collections.shuffle(out, rnd);
        if (!out.isEmpty()) {
            prefs.edit().putString(key, join(out)).putLong(key + ".ts", System.currentTimeMillis()).apply();
        }
        return out;
    }

    /**
     * Поиск в Commons. Уровень 0 — только «Quality images», уровень 1 — любые растровые фото.
     * Сортировка по релевантности (random подмешивал случайные несвязанные файлы),
     * разнообразие — за счёт случайного сдвига по выдаче.
     */
    List<String> searchCommons(String query, int tier) throws Exception {
        // шаблоны можно переопределить через DebugReceiver (--es tpl0 "... %s ...") без пересборки
        String tpl = prefs.getString("tpl" + tier, tier == 0 ? "%s incategory:\"Quality images\"" : "%s filetype:bitmap");
        String search = tpl.replace("%s", query);
        int offset = rnd.nextInt(3) * 50;
        JSONObject o = commonsQuery(search, offset);
        if (offset > 0 && o.optJSONObject("query") == null) o = commonsQuery(search, 0); // выдача короче сдвига
        return parseCommons(o, query, search, tier);
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
        int seen = 0, badMime = 0, small = 0, badAspect = 0, noThumb = 0;
        String sample = null;
        int minW = prefs.getInt("minw", 1280);
        for (int i = 0; i < pages.length(); i++) {
            JSONObject page = pages.getJSONObject(i);
            JSONArray ii = page.optJSONArray("imageinfo");
            if (ii == null || ii.length() == 0) continue;
            seen++;
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
                + " fit (mime " + badMime + ", small " + small + ", aspect " + badAspect + ", url " + noThumb + ")");
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
