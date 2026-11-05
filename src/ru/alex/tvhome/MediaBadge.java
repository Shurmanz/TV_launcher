package ru.alex.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Плашка формата при старте видео, как у TCL: «4K · HDR10 · 23,976 к/с · HEVC · Dolby Atmos».
 *
 * Откуда данные (без root):
 *  • видео — журнал видеотракта MediaTek: раз в ~5 с строка DispLink «flip to PQ … Hdr 1, FrameRate 23976 … FWxH 3840x2160»;
 *  • кодек — dumpsys media.resource_manager (Name: c2.mtk.hevc.decoder);
 *  • звук — dumpsys media.audio_flinger (формат выхода и DecoderFormat у аудиотракта MediaTek).
 * Нужны READ_LOGS и DUMP (pm grant через adb — выдаёт «Оптимизация» или HA). Без них — тихо ничего не показываем.
 * Старт воспроизведения ловим через AudioPlaybackCallback: он есть у любого приложения и ничего не стоит в простое.
 */
final class MediaBadge {
    static final String[] PERMS = {"android.permission.READ_LOGS", "android.permission.DUMP"};
    private static final long QUIET_RESHOW_MS = 90_000L; // пауза короче — после неё не показываем повторно

    private static MediaBadge inst;

    static synchronized void start(Context c) {
        if (inst != null) return;
        inst = new MediaBadge(c.getApplicationContext());
    }

    static boolean enabled(Context c) { return prefs(c).getBoolean("badge", true); }
    static void setEnabled(Context c, boolean on) { prefs(c).edit().putBoolean("badge", on).apply(); }
    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("comfort", Context.MODE_PRIVATE); }

    static boolean permitted(Context c) {
        for (String p : PERMS) if (c.checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) return false;
        return true;
    }

    private final Context ctx;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Handler bg;
    private boolean playing;
    private long stoppedAt;
    private int session; // растёт при каждом новом старте — старые пробы не показывают

    private MediaBadge(Context c) {
        ctx = c;
        HandlerThread t = new HandlerThread("badge");
        t.start();
        bg = new Handler(t.getLooper());
        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return;
        am.registerAudioPlaybackCallback(new AudioManager.AudioPlaybackCallback() {
            @Override public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> configs) { changed(configs); }
        }, ui);
        changed(am.getActivePlaybackConfigurations());
    }

    private void changed(List<AudioPlaybackConfiguration> configs) {
        boolean media = false;
        if (configs != null) for (AudioPlaybackConfiguration pc : configs) {
            AudioAttributes a = pc.getAudioAttributes();
            if (a != null && (a.getUsage() == AudioAttributes.USAGE_MEDIA || a.getUsage() == AudioAttributes.USAGE_UNKNOWN)) { media = true; break; }
        }
        long now = SystemClock.elapsedRealtime();
        if (media && !playing) {
            playing = true;
            boolean fresh = stoppedAt == 0 || now - stoppedAt > QUIET_RESHOW_MS;
            if (fresh && enabled(ctx) && permitted(ctx)) {
                final int s = ++session;
                // видеотракт пишет строку раз в ~5 с: пробуем через 3 и через 7 с
                bg.postDelayed(() -> probe(s, false), 3000);
                bg.postDelayed(() -> probe(s, true), 7500);
            }
        } else if (!media && playing) {
            playing = false;
            stoppedAt = now;
        }
    }

    private int shownFor = -1;

    private void probe(int s, boolean last) {
        if (s != session || shownFor == s || !playing) return;
        try {
            Info i = read();
            String text = i.text();
            Log.i(Net.TAG, "badge: " + (text.isEmpty() ? "(нет видео)" : text) + " | " + i.raw);
            if (i.hasVideo || (last && !text.isEmpty())) {
                shownFor = s;
                if (!text.isEmpty()) Overlay.badge(ctx, text, i.hdr);
            }
        } catch (Exception e) {
            Net.log("badge", e);
        }
    }

    // ---------- чтение ----------

    static final class Info {
        boolean hasVideo;
        int width, height, hdr = -1;
        double fps;
        String codec = "", audio = "", raw = "";

        String text() {
            StringBuilder sb = new StringBuilder();
            if (hasVideo) {
                add(sb, res(width, height));
                add(sb, hdrName(hdr));
                if (fps > 1) add(sb, Math.abs(fps - Math.round(fps)) < 0.01 ? Math.round(fps) + " к/с"
                        : String.format(Locale.US, "%.3f к/с", fps).replace('.', ','));
                add(sb, codec);
            }
            add(sb, audio);
            return sb.toString();
        }

        private static void add(StringBuilder sb, String s) {
            if (s == null || s.isEmpty()) return;
            if (sb.length() > 0) sb.append("  ·  ");
            sb.append(s);
        }

        static String res(int w, int h) {
            if (w <= 0) return "";
            if (w >= 7000) return "8K";
            if (w >= 3200 || h >= 2000) return "4K";
            if (w >= 2400) return "1440p";
            if (w >= 1800 || h >= 1000) return "1080p";
            if (w >= 1200 || h >= 700) return "720p";
            return h + "p";
        }

        /** Код Hdr у видеотракта MediaTek. 0 — SDR, 1 — HDR10 (проверено). Остальное — по порядку типов Android. */
        static String hdrName(int h) {
            switch (h) {
                case -1: case 0: return "";
                case 1: return "HDR10";
                case 2: return "HLG";
                case 3: return "HDR10+";
                case 4: return "Dolby Vision";
                default: return "HDR";
            }
        }
    }

    private static final Pattern DISP = Pattern.compile(
            "^(\\d\\d-\\d\\d \\d\\d:\\d\\d:\\d\\d)\\.\\d+.*flipToPq.*?Hdr (\\d+).*?FrameRate (\\d+).*?FWxH (\\d+)x(\\d+)");

    static Info read() {
        Info i = new Info();
        // видео: последняя строка видеотракта, не старше 15 с
        String disp = exec("logcat", "-d", "-t", "300", "-s", "DispLink:I");
        String lastLine = null;
        for (String l : disp.split("\n")) if (l.contains("flipToPq")) lastLine = l;
        if (lastLine != null) {
            Matcher m = DISP.matcher(lastLine);
            if (m.find() && fresh(m.group(1), 15_000L)) {
                i.hasVideo = true;
                i.hdr = Integer.parseInt(m.group(2));
                i.fps = Integer.parseInt(m.group(3)) / 1000.0;
                i.width = Integer.parseInt(m.group(4));
                i.height = Integer.parseInt(m.group(5));
                i.raw += "disp[" + lastLine.replaceAll(".*flipToPq", "").replaceAll(",? ?gapMs.*", "").trim() + "] ";
            }
        }
        // кодек
        String rm = exec("dumpsys", "media.resource_manager");
        Matcher cm = Pattern.compile("Name: (c2\\.[\\w.]+|OMX\\.[\\w.]+)").matcher(rm);
        while (cm.find()) {
            String n = cm.group(1).toLowerCase(Locale.ROOT);
            if (n.contains("audio") || n.contains(".aac") || n.contains(".mp3")) continue;
            i.codec = codecName(n);
            if (n.contains("dolby") || n.contains("dvhe") || n.contains("dvav") || n.contains(".dv.")) i.hdr = Math.max(i.hdr, 4);
            i.raw += "codec[" + n + "] ";
            if (!i.codec.isEmpty()) break;
        }
        // звук
        String af = exec("dumpsys", "media.audio_flinger");
        i.audio = audioName(af);
        i.raw += "audio[" + i.audio + "]";
        return i;
    }

    static String codecName(String n) {
        if (n.contains("hevc") || n.contains("h265")) return "HEVC";
        if (n.contains("avc") || n.contains("h264")) return "H.264";
        if (n.contains("av1")) return "AV1";
        if (n.contains("vp9")) return "VP9";
        if (n.contains("vp8")) return "VP8";
        if (n.contains("mpeg2")) return "MPEG-2";
        if (n.contains("dvhe") || n.contains("dolby")) return "HEVC";
        return "";
    }

    /** Самое «громкое» из того, что сейчас идёт на выход звука: Atmos > TrueHD > DD+ > DD > DTS. PCM не показываем. */
    static String audioName(String af) {
        String s = af.toUpperCase(Locale.ROOT);
        // только активные потоки: формат выхода прямых/offload-потоков и DecoderFormat аудиотракта MediaTek
        StringBuilder live = new StringBuilder();
        for (String l : s.split("\n")) {
            if (l.contains("HAL FORMAT") || l.contains("DECODERFORMAT=")) live.append(l).append('\n');
        }
        String t = live.toString();
        if (t.contains("E_AC3_JOC") || t.contains("ATMOS") || t.contains("FORMAT_MAT") || t.contains("=MAT")) return "Dolby Atmos";
        if (t.contains("AC4")) return "Dolby AC-4";
        if (t.contains("DOLBY_TRUEHD") || t.contains("TRUEHD")) return "Dolby TrueHD";
        if (t.contains("E_AC3") || t.contains("DDP") || t.contains("EAC3")) return "Dolby Digital Plus";
        if (t.contains("AUDIO_FORMAT_AC3") || t.contains("DECODERFORMAT=AC3") || t.contains("DECODERFORMAT=DD")) return "Dolby Digital";
        if (t.contains("DTS_HD") || t.contains("DTSHD")) return "DTS-HD";
        if (t.contains("DTS")) return "DTS";
        return "";
    }

    private static boolean fresh(String stamp, long maxAgeMs) {
        try {
            Calendar now = Calendar.getInstance();
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
            java.util.Date d = f.parse(now.get(Calendar.YEAR) + "-" + stamp);
            return d != null && Math.abs(System.currentTimeMillis() - d.getTime()) < maxAgeMs;
        } catch (Exception e) { return true; }
    }

    private static String exec(String... cmd) {
        Process p = null;
        try {
            p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String l;
                while ((l = r.readLine()) != null) { sb.append(l).append('\n'); if (sb.length() > 400_000) break; }
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        } finally {
            if (p != null) p.destroy();
        }
    }
}
