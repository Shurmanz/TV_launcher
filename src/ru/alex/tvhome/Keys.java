package ru.alex.tvhome;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.view.KeyEvent;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * «Кнопки пульта»: своё действие на любую кнопку (Netflix, YouTube, цветные, цифры…) — коротко и по долгому нажатию.
 * Кнопку не выбирают из списка, а «ловят»: человек жмёт её на своём пульте, поэтому подходит любой телевизор.
 * Действие: "app:<пакет>", "watch", "home", "inputs", "last", "off". Пустое долгое — нет.
 */
final class Keys {
    static final class Bind {
        final String id;
        String name, tap, hold;
        Bind(String id, String name) { this.id = id; this.name = name; }
    }

    private static Map<String, Bind> cache;

    private Keys() { }

    private static SharedPreferences p(Context c) { return c.getSharedPreferences("keys", Context.MODE_PRIVATE); }

    /** Кнопка: код клавиши, а если система его не знает — скан-код. */
    static String id(KeyEvent e) { return e.getKeyCode() != 0 ? "k" + e.getKeyCode() : "s" + e.getScanCode(); }

    /** Навигацию, громкость и питание не переназначаем — иначе можно остаться без управления. */
    static boolean forbidden(int kc) {
        switch (kc) {
            case KeyEvent.KEYCODE_DPAD_UP: case KeyEvent.KEYCODE_DPAD_DOWN: case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT: case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER: case KeyEvent.KEYCODE_BACK: case KeyEvent.KEYCODE_HOME:
            case KeyEvent.KEYCODE_VOLUME_UP: case KeyEvent.KEYCODE_VOLUME_DOWN: case KeyEvent.KEYCODE_VOLUME_MUTE:
            case KeyEvent.KEYCODE_MUTE: case KeyEvent.KEYCODE_POWER: case KeyEvent.KEYCODE_TV_POWER:
            case KeyEvent.KEYCODE_SLEEP: case KeyEvent.KEYCODE_WAKEUP: case KeyEvent.KEYCODE_ESCAPE:
            case 268: case 269: case 270: case 271: // диагонали D-pad
                return true;
            default:
                return false;
        }
    }

    /** Человеческое название кнопки. */
    static String name(KeyEvent e) {
        int kc = e.getKeyCode();
        if (kc == 0) return "Кнопка (код " + e.getScanCode() + ")";
        if (kc >= KeyEvent.KEYCODE_0 && kc <= KeyEvent.KEYCODE_9) return "Цифра " + (kc - KeyEvent.KEYCODE_0);
        String s = KeyEvent.keyCodeToString(kc);
        if (s.startsWith("KEYCODE_")) s = s.substring(8);
        switch (s) {
            case "PROG_RED": return "Красная";
            case "PROG_GREEN": return "Зелёная";
            case "PROG_YELLOW": return "Жёлтая";
            case "PROG_BLUE": return "Синяя";
            case "GUIDE": return "Гид";
            case "INFO": return "Инфо";
            case "SETTINGS": return "Настройки";
            case "TV_INPUT": return "Вход (Source)";
            case "TV": return "ТВ";
            case "CHANNEL_UP": return "Канал +";
            case "CHANNEL_DOWN": return "Канал −";
            case "MENU": return "Меню";
            case "SEARCH": return "Поиск";
            case "ASSIST": case "VOICE_ASSIST": return "Микрофон";
            case "MEDIA_PLAY_PAUSE": return "Пуск/пауза";
            case "MEDIA_PLAY": return "Пуск";
            case "MEDIA_PAUSE": return "Пауза";
            case "MEDIA_STOP": return "Стоп";
            case "MEDIA_NEXT": return "Следующий";
            case "MEDIA_PREVIOUS": return "Предыдущий";
            case "MEDIA_REWIND": return "Перемотка назад";
            case "MEDIA_FAST_FORWARD": return "Перемотка вперёд";
            case "CAPTIONS": return "Субтитры";
            case "MEDIA_AUDIO_TRACK": return "Звуковая дорожка";
            case "ALL_APPS": return "Все приложения";
            case "BOOKMARK": return "Закладка";
            case "DVR": return "Запись";
            case "EXPLORER": return "Браузер";
            case "BUTTON_1": return "Кнопка 1";
        }
        if (s.startsWith("FEATURED_APP_")) return "Кнопка приложения " + s.substring(13);
        if (s.matches("\\d+")) return "Кнопка (код " + s + ")";
        return s.replace('_', ' ');
    }

    private static synchronized Map<String, Bind> map(Context c) {
        if (cache != null) return cache;
        Map<String, Bind> m = new LinkedHashMap<>();
        try {
            JSONObject o = new JSONObject(p(c).getString("map", "{}"));
            for (Iterator<String> it = o.keys(); it.hasNext(); ) {
                String id = it.next();
                JSONObject b = o.getJSONObject(id);
                Bind x = new Bind(id, b.optString("n", id));
                x.tap = b.optString("t", null);
                x.hold = b.optString("h", null);
                if (x.hold != null && x.hold.isEmpty()) x.hold = null;
                m.put(id, x);
            }
        } catch (Exception e) { Net.log("keys", e); }
        cache = m;
        return m;
    }

    private static synchronized void store(Context c, Map<String, Bind> m) {
        JSONObject o = new JSONObject();
        try {
            for (Bind b : m.values()) {
                JSONObject j = new JSONObject().put("n", b.name);
                if (b.tap != null) j.put("t", b.tap);
                if (b.hold != null) j.put("h", b.hold);
                o.put(b.id, j);
            }
        } catch (Exception ignored) { }
        p(c).edit().putString("map", o.toString()).apply();
        cache = m;
        HomeKeyService.refresh();
    }

    static Bind get(Context c, String id) { return map(c).get(id); }

    static boolean any(Context c) { return !map(c).isEmpty(); }

    static List<Bind> all(Context c) { return new ArrayList<>(map(c).values()); }

    static void save(Context c, Bind b) {
        Map<String, Bind> m = new LinkedHashMap<>(map(c));
        m.put(b.id, b);
        store(c, m);
    }

    static void remove(Context c, String id) {
        Map<String, Bind> m = new LinkedHashMap<>(map(c));
        m.remove(id);
        store(c, m);
    }

    /** Действия Slauncher (не приложения): код → подпись. */
    static final String[][] ACTIONS = {
            {"watch", "Что посмотреть"},
            {"home", "Главный экран"},
            {"inputs", "Входы (HDMI)"},
            {"last", "Прошлое приложение"},
            {"off", "Выключить экран"},
    };

    static String label(Context c, String a) {
        if (a == null) return "ничего";
        for (String[] x : ACTIONS) if (x[0].equals(a)) return x[1];
        if (a.startsWith("app:")) {
            String pkg = a.substring(4);
            try {
                return c.getPackageManager().getApplicationLabel(c.getPackageManager().getApplicationInfo(pkg, 0)).toString();
            } catch (Exception e) { return pkg + " (не установлено)"; }
        }
        return a;
    }

    /** Выполнить действие (из службы, вне UI Slauncher). */
    static void run(Context c, String a) {
        if (a == null) return;
        if (a.startsWith("app:")) {
            if (!Comfort.open(c, a.substring(4)))
                android.widget.Toast.makeText(c, "Приложение не найдено — переназначьте кнопку в настройках Slauncher",
                        android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        switch (a) {
            case "last":
                if (!Comfort.open(c, Comfort.lastApp(c))) home(c, null);
                return;
            case "off":
                new Thread(() -> {
                    try {
                        if (Adb.known(c)) { Adb.sh(c, "input keyevent 223"); return; }
                    } catch (Exception e) { Net.log("keys off", e); }
                    HomeKeyService s = HomeKeyService.inst;
                    if (s != null) s.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN);
                }).start();
                return;
            case "watch": case "inputs":
                home(c, a);
                return;
            default:
                home(c, null);
        }
    }

    private static void home(Context c, String open) {
        Intent i = new Intent(c, HomeActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        if (open != null) i.putExtra("open", open);
        c.startActivity(i);
    }
}
