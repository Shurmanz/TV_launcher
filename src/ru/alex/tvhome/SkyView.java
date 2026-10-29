package ru.alex.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

import java.util.Calendar;
import java.util.Random;

/**
 * «Живая погода»: абстрактный анимированный фон вместо фото. Небо по времени суток и сезону,
 * мягкие пятна-облака, дождь, снег, туман, звёзды, вспышки грозы. Всё рисуется на GPU
 * градиентами и линиями — без картинок; ~30 кадров/с, только пока экран виден.
 */
final class SkyView extends View {
    private static final long FRAME_MS = 33;

    private Weather.Phase phase = Weather.Phase.DAY;
    private Weather.Sky sky = Weather.Sky.CLOUDY;
    private int code = 3;
    private boolean running;
    private final long t0 = SystemClock.uptimeMillis();

    // кэш шейдеров под текущий размер и сцену
    private int cw, ch;
    private boolean dirty = true;
    private final Paint skyP = new Paint(), glowP = new Paint(), shadeP = new Paint(), flashP = new Paint();
    private final Paint blobP = new Paint(Paint.ANTI_ALIAS_FLAG), fogP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint[] rainP = new Paint[3], snowP = new Paint[3];
    private final Paint starP = new Paint(Paint.ANTI_ALIAS_FLAG), moonP = new Paint(Paint.ANTI_ALIAS_FLAG), rayP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private RadialGradient blobShader, fogShader;
    private final Paint blobLightP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int blobLight;
    private final android.graphics.Path ray = new android.graphics.Path();
    private final Random cloudRnd = new Random();
    private final Paint cutP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int[] pal = new int[3];
    private int blobColor;
    private float blobAlpha;
    private int blobs, rainN, snowN, starN;
    private boolean sun, sunLow, moon;

    // частицы: x, y, глубина (0..1), фаза
    private float[] px, py, pd, pf;
    private float[] lines = new float[0];
    private final float[][] pts = new float[3][];

    SkyView(Context c) {
        super(c);
        for (int i = 0; i < 3; i++) {
            rainP[i] = new Paint(Paint.ANTI_ALIAS_FLAG);
            rainP[i].setStrokeCap(Paint.Cap.ROUND);
            rainP[i].setColor(Color.argb((int) (255 * (0.14f + i * 0.14f)), 215, 228, 242));
            snowP[i] = new Paint(Paint.ANTI_ALIAS_FLAG);
            snowP[i].setStrokeCap(Paint.Cap.ROUND);
            snowP[i].setColor(Color.argb((int) (255 * (0.40f + i * 0.25f)), 255, 255, 255));
        }
        starP.setColor(Color.WHITE);
        starP.setStrokeCap(Paint.Cap.ROUND);
        moonP.setColor(0xFFEEF2FF);
    }

    /** Отладка (DebugReceiver --es sky "NIGHT/SNOW/73"): показать сцену независимо от погоды; "" — вернуть настоящую. */
    static volatile String force = "";
    private String applied = "";
    private Weather.Phase realPhase = Weather.Phase.DAY;
    private Weather.Sky realSky = Weather.Sky.CLOUDY;
    private int realCode = 3;

    void setScene(Weather.Phase p, Weather.Sky s, int wmo) {
        realPhase = p; realSky = s; realCode = wmo;
        if (!force.isEmpty()) return;
        apply(p, s, wmo);
    }

    private void checkForce() {
        String f = force;
        if (f.equals(applied)) return;
        applied = f;
        if (f.isEmpty()) { apply(realPhase, realSky, realCode); return; }
        try {
            String[] a = f.split("/");
            apply(Weather.Phase.valueOf(a[0]), Weather.Sky.valueOf(a[1]), a.length > 2 ? Integer.parseInt(a[2]) : 3);
        } catch (Exception e) { Net.log("sky force " + f, e); }
    }

    private void apply(Weather.Phase p, Weather.Sky s, int wmo) {
        if (p == phase && s == sky && wmo == code && !dirty) return;
        phase = p;
        sky = s;
        code = wmo;
        dirty = true;
        invalidate();
    }

    String sceneName() { return phase + "/" + sky; }

    void setRunning(boolean on) {
        running = on;
        if (on) invalidate();
    }

    // ---------- палитры ----------

    private static int[] day(Weather.Sky s) {
        switch (s) {
            case CLEAR: return new int[]{0xFF2F7FC8, 0xFF62A8E0, 0xFFA8D4F0};
            case RAIN: return new int[]{0xFF3A4A5C, 0xFF55677A, 0xFF7B8A96};
            case SNOW: return new int[]{0xFF5E6E86, 0xFF8C9AAE, 0xFFC4CCD8};
            case FOG: return new int[]{0xFF9AA3A8, 0xFFC4C2BC, 0xFFE0D4C4};
            case STORM: return new int[]{0xFF14141E, 0xFF2A2438, 0xFF463A52};
            default: return new int[]{0xFF4C5663, 0xFF6B7480, 0xFF9A9A98};
        }
    }

    private static int[] night(Weather.Sky s) {
        switch (s) {
            case CLEAR: return new int[]{0xFF05070F, 0xFF0B1430, 0xFF18244A};
            case RAIN: return new int[]{0xFF0E141C, 0xFF1C2633, 0xFF2C3846};
            case SNOW: return new int[]{0xFF1C2242, 0xFF3A3F6E, 0xFF6A6A9A};
            case FOG: return new int[]{0xFF20242A, 0xFF3A3E44, 0xFF55585C};
            case STORM: return new int[]{0xFF0A0A12, 0xFF1C1828, 0xFF2E2640};
            default: return new int[]{0xFF0D1118, 0xFF1A2230, 0xFF2A3240};
        }
    }

    private static final int[] DAWN = {0xFF2A3A6A, 0xFFC87A7A, 0xFFF2B27A};
    private static final int[] DUSK = {0xFF2A2048, 0xFFA24A5A, 0xFFF0A060};

    private static int mix(int a, int b, float k) {
        return Color.rgb((int) (Color.red(a) + (Color.red(b) - Color.red(a)) * k),
                (int) (Color.green(a) + (Color.green(b) - Color.green(a)) * k),
                (int) (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * k));
    }

    private void buildScene() {
        int[] p;
        if (phase == Weather.Phase.NIGHT) p = night(sky);
        else if (phase == Weather.Phase.DAY) p = day(sky);
        else {
            int[] warm = phase == Weather.Phase.DAWN ? DAWN : DUSK;
            p = sky == Weather.Sky.CLEAR ? warm.clone() : new int[]{
                    mix(day(sky)[0], warm[0], 0.4f), mix(day(sky)[1], warm[1], 0.4f), mix(day(sky)[2], warm[2], 0.4f)};
        }
        // оттенок сезона
        int m = Calendar.getInstance().get(Calendar.MONTH);
        int tint; float tk;
        if (m == 11 || m <= 1) { tint = 0xFF8090C8; tk = 0.12f; }
        else if (m >= 8 && m <= 10) { tint = 0xFFC07A40; tk = 0.10f; }
        else if (m >= 5 && m <= 7) { tint = 0xFFE0B070; tk = 0.06f; }
        else { tint = 0xFF90C0A0; tk = 0.06f; }
        p[1] = mix(p[1], tint, tk);
        p[2] = mix(p[2], tint, tk);
        pal = p;

        boolean nightish = phase == Weather.Phase.NIGHT;
        sun = !nightish && (sky == Weather.Sky.CLEAR || (sky == Weather.Sky.CLOUDY && code <= 2));
        sunLow = phase == Weather.Phase.DAWN || phase == Weather.Phase.DUSK;
        moon = nightish && (sky == Weather.Sky.CLEAR || (sky == Weather.Sky.CLOUDY && code <= 2));
        starN = nightish ? (sky == Weather.Sky.CLEAR ? 260 : sky == Weather.Sky.CLOUDY && code <= 2 ? 90 : 0) : 0;

        switch (sky) {
            case CLEAR: blobs = 4; blobAlpha = 0.25f; break;
            case FOG: blobs = 6; blobAlpha = 0.5f; break;
            case SNOW: blobs = 9; blobAlpha = 0.6f; break;
            case STORM: blobs = 14; blobAlpha = 0.85f; break;
            default: blobs = 14; blobAlpha = 0.9f;
        }
        // тёмные и светлые пятна вперемешку — объём «туч», а не ровный градиент
        blobColor = sky == Weather.Sky.CLEAR && !nightish ? 0xFFFFFFFF
                : sky == Weather.Sky.FOG ? mix(p[2], 0xFFFFFFFF, 0.3f) : mix(p[0], 0xFF000000, nightish ? 0.2f : 0.42f);
        blobLight = sky == Weather.Sky.CLEAR && !nightish ? 0xFFFFFFFF : mix(p[2], 0xFFFFFFFF, nightish ? 0.12f : 0.4f);

        rainN = sky == Weather.Sky.STORM ? 520 : sky == Weather.Sky.RAIN ? (code >= 51 && code <= 57 ? 160 : code >= 80 ? 420 : 300) : 0;
        snowN = sky == Weather.Sky.SNOW ? (code == 71 ? 160 : code >= 75 || code == 86 ? 380 : 260) : 0;

        int n = Math.max(Math.max(rainN, snowN), starN);
        Random r = new Random(42);
        px = new float[n]; py = new float[n]; pd = new float[n]; pf = new float[n];
        for (int i = 0; i < n; i++) { px[i] = r.nextFloat(); py[i] = r.nextFloat(); pd[i] = r.nextFloat(); pf[i] = r.nextFloat() * 6.28f; }
        lines = new float[rainN * 4];
        for (int i = 0; i < 3; i++) pts[i] = new float[n * 2];
        cw = -1; // пересобрать шейдеры
        dirty = false;
    }

    private void buildShaders(int w, int h) {
        cw = w; ch = h;
        skyP.setShader(new LinearGradient(0, 0, 0, h, pal, new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        if (sun) {
            float x = sunLow ? w * 0.72f : w * 0.8f, y = sunLow ? h * 0.8f : h * 0.18f;
            glowP.setShader(new RadialGradient(x, y, w * (sunLow ? 0.38f : 0.32f),
                    new int[]{sunLow ? 0xF2FFD28C : 0xFFFFFAE1, sunLow ? 0x99FFAA64 : 0x8CFFF0C8, 0x00FFDCA0},
                    new float[]{0f, 0.14f, 1f}, Shader.TileMode.CLAMP));
            rayP.setShader(new LinearGradient(0, 0, w * 0.6f, 0, 0x1EFFF0D2, 0x00FFF0D2, Shader.TileMode.CLAMP));
        } else if (moon) {
            float x = w * 0.8f, y = h * 0.2f;
            glowP.setShader(new RadialGradient(x, y, w * 0.22f, new int[]{0xE6DCE6FF, 0x59B4C8FF, 0x007888DC},
                    new float[]{0f, 0.1f, 1f}, Shader.TileMode.CLAMP));
        } else glowP.setShader(null);
        blobShader = new RadialGradient(0, 0, 1f, new int[]{blobColor, blobColor & 0xB3FFFFFF, blobColor & 0x00FFFFFF},
                new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP);
        blobP.setShader(blobShader);
        blobLightP.setShader(new RadialGradient(0, 0, 1f, new int[]{blobLight, blobLight & 0x99FFFFFF, blobLight & 0x00FFFFFF},
                new float[]{0f, 0.4f, 1f}, Shader.TileMode.CLAMP));
        fogShader = new RadialGradient(0, 0, 1f, new int[]{0x55FFFCF5, 0x00FFFCF5}, null, Shader.TileMode.CLAMP);
        fogP.setShader(fogShader);
        float dark = sky == Weather.Sky.FOG || (sky == Weather.Sky.CLEAR && phase == Weather.Phase.DAY) ? 0.5f : 0.38f;
        shadeP.setShader(new LinearGradient(0, 0, 0, h,
                new int[]{0x40000000, 0x00000000, 0x00000000, Color.argb((int) (255 * dark), 0, 0, 0)},
                new float[]{0f, 0.3f, 0.45f, 1f}, Shader.TileMode.CLAMP));
        flashP.setShader(new RadialGradient(w * 0.62f, h * 0.18f, w * 0.6f, 0xFFD2C8FF, 0x00A096E6, Shader.TileMode.CLAMP));
        cutP.setColor(mix(pal[0], pal[1], 0.5f));
        ray.reset();
        ray.moveTo(0, 0);
        ray.lineTo(w * 0.6f, -h * 0.05f);
        ray.lineTo(w * 0.6f, h * 0.05f);
        ray.close();
        float dp = getResources().getDisplayMetrics().density;
        for (int i = 0; i < 3; i++) {
            rainP[i].setStrokeWidth((0.6f + i * 0.6f) * dp);
            snowP[i].setStrokeWidth((1.6f + i * 2.0f) * dp);
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        checkForce();
        if (dirty) buildScene();
        if (cw != w || ch != h) buildShaders(w, h);
        float t = (SystemClock.uptimeMillis() - t0) / 1000f;

        c.drawRect(0, 0, w, h, skyP);
        if (glowP.getShader() != null) c.drawRect(0, 0, w, h, glowP);
        if (sun) rays(c, w, h, t);
        if (starN > 0) stars(c, w, h, t);
        if (moon) {
            float x = w * 0.8f, y = h * 0.2f, r = h * 0.042f;
            c.drawCircle(x, y, r, moonP);
            c.drawCircle(x + r * 0.45f, y - r * 0.2f, r * 0.92f, cutP);
        }
        clouds(c, w, h, t);
        if (sky == Weather.Sky.STORM) flash(c, w, h, t);
        if (sky == Weather.Sky.FOG) fog(c, w, h, t);
        if (rainN > 0) rain(c, w, h, t);
        if (snowN > 0) snow(c, w, h, t);
        c.drawRect(0, 0, w, h, shadeP);

        if (running) postInvalidateDelayed(FRAME_MS);
    }

    private void rays(Canvas c, int w, int h, float t) {
        float x = sunLow ? w * 0.72f : w * 0.8f, y = sunLow ? h * 0.8f : h * 0.18f;
        c.save();
        c.translate(x, y);
        c.rotate(t * 1.6f);
        for (int i = 0; i < 12; i++) { c.rotate(30); c.drawPath(ray, rayP); }
        c.restore();
    }

    private void stars(Canvas c, int w, int h, float t) {
        float dp = getResources().getDisplayMetrics().density;
        int[] n = new int[3];
        for (int i = 0; i < starN; i++) {
            float tw = Math.abs((float) Math.sin(t * (0.6f + pd[i]) + pf[i]));
            int b = tw < 0.33f ? 0 : tw < 0.66f ? 1 : 2;
            pts[b][n[b]++] = px[i] * w;
            pts[b][n[b]++] = py[i] * h * 0.8f;
        }
        for (int b = 0; b < 3; b++) {
            starP.setAlpha(70 + b * 85);
            starP.setStrokeWidth((1.2f + b * 0.6f) * dp);
            c.drawPoints(pts[b], 0, n[b], starP);
        }
    }

    private void clouds(Canvas c, int w, int h, float t) {
        Random r = cloudRnd;
        r.setSeed(7);
        float span = w * 1.5f;
        for (int i = 0; i < blobs; i++) {
            float speed = (sky == Weather.Sky.STORM ? 40 : 16) + r.nextFloat() * 22;
            float bx = (r.nextFloat() * span + t * speed) % span - w * 0.25f;
            float by = r.nextFloat() * h * (sky == Weather.Sky.FOG ? 1f : 0.7f) + (float) Math.sin(t * 0.2f + i) * h * 0.02f;
            float rad = h * (0.2f + r.nextFloat() * 0.32f);
            Paint bp = i % 3 == 2 ? blobLightP : blobP;
            bp.setAlpha((int) (255 * blobAlpha * (0.6f + r.nextFloat() * 0.4f) * (bp == blobLightP ? 0.85f : 1f)));
            c.save();
            c.translate(bx, by);
            c.scale(rad * 1.7f, rad * 0.75f);
            c.drawCircle(0, 0, 1f, bp);
            c.restore();
        }
    }

    private void fog(Canvas c, int w, int h, float t) {
        for (int i = 0; i < 6; i++) {
            float y = h * (0.25f + i * 0.13f);
            float x = (t * (10 + i * 6)) % (w * 1.6f) - w * 0.3f;
            fogP.setAlpha(150 + i * 15);
            c.save();
            c.translate(x, y);
            c.scale(w * 0.75f, h * 0.11f);
            c.drawCircle(0, 0, 1f, fogP);
            c.restore();
            c.save();
            c.translate(x - w * 1.1f, y + h * 0.03f);
            c.scale(w * 0.7f, h * 0.1f);
            c.drawCircle(0, 0, 1f, fogP);
            c.restore();
        }
    }

    private void flash(Canvas c, int w, int h, float t) {
        float k = t % 6.5f;
        float f = k < 0.08f ? 1f : k < 0.16f ? 0.25f : k < 0.24f ? 0.8f : Math.max(0f, 1f - (k - 0.24f) * 2.2f) * 0.35f;
        if (f <= 0) return;
        flashP.setAlpha((int) (190 * f));
        c.drawRect(0, 0, w, h, flashP);
    }

    private void rain(Canvas c, int w, int h, float t) {
        float a = sky == Weather.Sky.STORM ? 0.28f : 0.18f;
        // по слоям: дальние тоньше и медленнее
        for (int layer = 0; layer < 3; layer++) {
            int n = 0;
            for (int i = 0; i < rainN; i++) {
                int l = pd[i] < 0.45f ? 0 : pd[i] < 0.8f ? 1 : 2;
                if (l != layer) continue;
                float len = h * (0.03f + pd[i] * 0.06f), sp = h * (0.9f + pd[i] * 1.3f);
                float y = (py[i] * (h + 200) + t * sp) % (h + 200) - 100;
                float x = px[i] * (w + 400) - 200 - y * a;
                lines[n++] = x; lines[n++] = y; lines[n++] = x - len * a; lines[n++] = y + len;
            }
            c.drawLines(lines, 0, n, rainP[layer]);
        }
    }

    private void snow(Canvas c, int w, int h, float t) {
        int[] n = new int[3];
        for (int i = 0; i < snowN; i++) {
            int l = pd[i] < 0.5f ? 0 : pd[i] < 0.85f ? 1 : 2;
            float sp = h * (0.03f + pd[i] * 0.08f);
            float y = (py[i] * (h + 40) + t * sp) % (h + 40) - 20;
            float x = px[i] * w + (float) Math.sin(t * (0.5f + pd[i]) + pf[i]) * h * (0.02f + pd[i] * 0.03f);
            pts[l][n[l]++] = x;
            pts[l][n[l]++] = y;
        }
        for (int l = 0; l < 3; l++) c.drawPoints(pts[l], 0, n[l], snowP[l]);
    }
}
