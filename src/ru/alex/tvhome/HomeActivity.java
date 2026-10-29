package ru.alex.tvhome;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class HomeActivity extends Activity {
    private static final long WALL_DAY = 10 * 60_000L;
    private static final long WALL_NIGHT = 30 * 60_000L;
    private static final int COLS = 5;
    /** Увеличение плитки в фокусе (как в tvOS 26). */
    private static final float FOCUS = 1.14f;
    private int lastTile = -1;

    private Apps apps;
    private FrameLayout root, content;
    private WallpaperView wall;
    private View nightDim;
    private GlowView glow;
    private ClockView clock;
    private TextView weatherBtn, netLabel, moveHint, tip;
    private LinearLayout updBar;
    private TextView sleepInfo;
    private final Runnable sleepTick = new Runnable() {
        @Override public void run() {
            int m = Comfort.sleepLeftMin(HomeActivity.this);
            sleepInfo.setText("⏾ выключение через " + m + " мин");
            sleepInfo.setVisibility(m > 0 ? View.VISIBLE : View.GONE);
            if (m > 0 && resumed) root.postDelayed(this, 30_000);
        }
    };
    private Icon settingsIcon;
    private View inputsChip;
    private HaCorner haCorner;
    private ScrollView scroll;
    private LinearLayout grid;
    private FrameLayout panel;
    private View beforePanel;
    private boolean resumed;
    private String moving; // пакет, который сейчас перемещаем
    private int tileW, tileH;

    private List<Apps.App> allApps = new ArrayList<>();
    private List<Apps.App> dock = new ArrayList<>();
    private final Map<String, Integer> accents = new HashMap<>();
    private ConnectivityManager.NetworkCallback netCb;

    // ---------- жизненный цикл ----------

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Fonts.init(this);
        apps = new Apps(this);
        BootReceiver.ensureService(this);
        buildUi();
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        registerReceiver(pkgReceiver, f);
        watchNetwork();
        HaClient.ensure(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        reloadApps();
        wall.setSky(Comfort.bgSky(this));
        wall.start();
        updateNet();
        HaReceiver.listener = () -> { if (haCorner != null) haCorner.refresh(); };
        haCorner.refresh();
        Updater.check(this, false, (avail, name) -> { settingsIcon.setDot(avail); updateTip(); });
        HaClient.ensure(this);
        updateTip(true);
        root.removeCallbacks(sleepTick);
        sleepTick.run();
        if (panel == null && Comfort.shouldShowWhatsNew(this)) root.postDelayed(this::openWhatsNew, 800);
        if (!setupOffered && !getSharedPreferences(SetupActivity.PREF, MODE_PRIVATE).getBoolean("seen", false)
                && SetupActivity.needed(this)) {
            setupOffered = true; // мастер — один раз за запуск, пока не нажали «Готово»
            startActivity(new Intent(this, SetupActivity.class));
        }
    }

    private static boolean setupOffered;
    private long lastHomeIntent;

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        wall.stop();
        HaReceiver.listener = null;
        if (moving != null) endMove();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        setIntent(intent);
        super.onNewIntent(intent);
        // «Домой» пришёл прямо к нам (Slauncher — системный главный экран): второе нажатие подряд — в прошлое приложение
        if (!intent.getBooleanExtra("from_service", false) && Intent.ACTION_MAIN.equals(intent.getAction())) {
            long now = android.os.SystemClock.elapsedRealtime();
            if (lastHomeIntent != 0 && now - lastHomeIntent > 250 && now - lastHomeIntent < 1500
                    && Comfort.open(this, Comfort.lastApp(this))) { lastHomeIntent = 0; Comfort.tipDone(this, "dbl"); return; }
            lastHomeIntent = now;
        }
        closePanel();
        if (moving != null) endMove();
        focusFirstTile();
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(pkgReceiver);
        try {
            if (netCb != null) ((ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE)).unregisterNetworkCallback(netCb);
        } catch (Exception ignored) { }
        wall.release();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (moving != null) { endMove(); return; }
        if (panel != null) closePanel();
        // на домашнем экране «Назад» ничего не делает
    }

    private final BroadcastReceiver pkgReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { if (resumed) reloadApps(); }
    };

    private int dp(float v) { return Ui.dp(this, v); }

    // ---------- разметка ----------

    private void buildUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0B0F14);
        root.setClipChildren(false);

        wall = new WallpaperView(this, WALL_DAY);
        wall.setScaleX(1.06f); // запас под параллакс
        wall.setScaleY(1.06f);
        root.addView(wall, match());

        nightDim = new View(this);
        nightDim.setBackgroundColor(0xFF000000);
        nightDim.setAlpha(0f);
        root.addView(nightDim, match());

        View topScrim = new View(this);
        topScrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xA6000000, 0x40000000, 0x00000000}));
        root.addView(topScrim, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(230), Gravity.TOP));
        View bottomScrim = new View(this);
        bottomScrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xCC000000, 0x66000000, 0x00000000}));
        root.addView(bottomScrim, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(330), Gravity.BOTTOM));

        glow = new GlowView(this);
        root.addView(glow, match());

        content = new FrameLayout(this);
        content.setClipChildren(false);
        root.addView(content, match());

        // --- слева: приветствие, часы, дата, погода
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        weatherBtn = Ui.text(this, 17, 400, 0xE6FFFFFF);
        weatherBtn.setText("Погода загружается…");
        weatherBtn.setPadding(dp(14), dp(8), dp(14), dp(8));
        weatherBtn.setFocusable(true);
        weatherBtn.setOnFocusChangeListener((v, has) -> {
            v.setBackground(has ? Ui.round(0xF2FFFFFF, dp(100), 0, 0) : null);
            weatherBtn.setTextColor(has ? 0xFF111111 : 0xE6FFFFFF);
            if (has) weatherBtn.setShadowLayer(0, 0, 0, 0); else Ui.strongShadow(weatherBtn);
            if (has) glow.hide();
        });
        weatherBtn.setOnClickListener(v -> openWeather());
        clock = new ClockView(this, 58, weatherBtn);
        clock.setNightListener(this::applyNight);
        wall.addListener(clock);
        left.addView(clock, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wl.setMargins(-dp(14), -dp(8), 0, 0); // строка погоды ближе к часам
        left.addView(weatherBtn, wl);
        FrameLayout.LayoutParams ll = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
        ll.setMargins(dp(64), dp(38), 0, 0);
        content.addView(left, ll);

        // --- справа сверху: климат из Home Assistant
        haCorner = new HaCorner(this);
        FrameLayout.LayoutParams hl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        hl.setMargins(0, dp(44), dp(64), 0);
        content.addView(haCorner, hl);
        haCorner.refresh();

        // --- под погодой, сразу над плитками: Входы, сеть, Настройки — круглые значки
        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setClipChildren(false);
        inputsChip = chip(new Icon(Icon.INPUT, 0xFFFFFFFF), "Входы", v -> openInputs(), null);
        chips.addView(inputsChip);
        View net = chip(new Icon(Icon.WIFI, 0xFFFFFFFF), "Wi‑Fi", v -> openSettings(Settings.ACTION_WIFI_SETTINGS), null);
        netLabel = (TextView) ((ViewGroup) net).getChildAt(1);
        chips.addView(net);
        chips.addView(chip(new Icon(Icon.ROCKET, 0xFFFFFFFF), "Ускорить", this::boost, null));
        settingsIcon = new Icon(Icon.GEAR, 0xFFFFFFFF);
        chips.addView(chip(settingsIcon, "Настройки", v -> openGearMenu(),
                v -> { openSleep(); return true; }));
        sleepInfo = Ui.text(this, 14, 500, 0xE6FFFFFF);
        sleepInfo.setPadding(dp(4), dp(10), dp(4), dp(10));
        sleepInfo.setVisibility(View.GONE);
        chips.addView(sleepInfo);
        LinearLayout.LayoutParams chl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        chl.topMargin = dp(12);
        left.addView(chips, chl);
        left.setClipChildren(false);

        // --- плитки: по 5 в строке, лишние — ниже, с прокруткой
        int sw = getResources().getDisplayMetrics().widthPixels;
        int side = dp(64), gap = dp(24);
        tileW = (sw - 2 * side - (COLS - 1) * gap) / COLS;
        tileH = Math.round(tileW * 9f / 16f);
        glow.setTile(tileW, tileH, dp(14), FOCUS);

        // прокрутку сетки ведём сами (см. onTileFocused): плитки не «подпрыгивают» от фокуса,
        // а «вниз» с нижнего ряда ничего не двигает
        scroll = new ScrollView(this) {
            @Override protected int computeScrollDeltaToGetChildRectOnScreen(android.graphics.Rect r) { return 0; }
            @Override public boolean executeKeyEvent(KeyEvent e) {
                int k = e.getKeyCode();
                if (k == KeyEvent.KEYCODE_DPAD_DOWN || k == KeyEvent.KEYCODE_DPAD_UP) {
                    View cur = findFocus();
                    View next = cur == null ? null : android.view.FocusFinder.getInstance().findNextFocus(this, cur,
                            k == KeyEvent.KEYCODE_DPAD_DOWN ? View.FOCUS_DOWN : View.FOCUS_UP);
                    if (next == null) return k == KeyEvent.KEYCODE_DPAD_DOWN; // вниз — никуда; вверх — к кнопкам
                }
                return super.executeKeyEvent(e);
            }
        };
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        scroll.setFillViewport(true);
        scroll.setSmoothScrollingEnabled(true);
        scroll.setVerticalFadingEdgeEnabled(false); // затемнение края резало верхний ряд при прокрутке вниз
        // сетка прокрутилась — подсветка едет за плиткой в фокусе
        scroll.setOnScrollChangeListener((v, x, y, ox, oy) -> {
            View f = getCurrentFocus();
            if (f != null && tileIndex(f) >= 0) glowTo(f);
        });
        grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setClipChildren(false);
        grid.setClipToPadding(false);
        grid.setPadding(side, dp(36), side, dp(70));
        scroll.addView(grid, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        FrameLayout.LayoutParams gl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        gl.topMargin = dp(236);
        content.addView(scroll, gl);

        moveHint = Ui.text(this, 15, 500, 0xFFFFFFFF);
        moveHint.setText("←  →  ↑  ↓  — переместить     ОК — готово");
        moveHint.setPadding(dp(20), dp(10), dp(20), dp(10));
        moveHint.setBackground(Ui.round(0xCC1C1C1E, dp(100), dp(1), 0x33FFFFFF));
        moveHint.setVisibility(View.GONE);
        FrameLayout.LayoutParams ml = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        ml.bottomMargin = dp(28);
        root.addView(moveHint, ml);

        // подсказка внизу: как добавить приложения / напоминание, что «Домой» не ведёт в Slauncher
        tip = Ui.text(this, 15, 500, 0xFFFFFFFF);
        tip.setShadowLayer(0, 0, 0, 0);
        tip.setPadding(dp(20), dp(10), dp(20), dp(10));
        tip.setVisibility(View.GONE);
        FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        tl.bottomMargin = dp(28);
        content.addView(tip, tl);

        // плашка «вышла новая версия»: Обновить / Позже
        updBar = new LinearLayout(this);
        updBar.setGravity(Gravity.CENTER_VERTICAL);
        updBar.setPadding(dp(22), dp(8), dp(10), dp(8));
        updBar.setBackground(Ui.round(0xE61C1C1E, dp(100), dp(1), 0x40FFFFFF));
        updBar.setVisibility(View.GONE);
        TextView ut = Ui.text(this, 15, 500, 0xFFFFFFFF);
        ut.setShadowLayer(0, 0, 0, 0);
        updBar.addView(ut);
        LinearLayout ua = new LinearLayout(this);
        LinearLayout.LayoutParams ual = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ual.leftMargin = dp(16);
        addAction(ua, "Обновить", () -> { updBar.setVisibility(View.GONE); runUpdate(null); });
        addAction(ua, "Позже", () -> { Updater.dismissBanner(this); updateTip(); focusFirstTile(); });
        updBar.addView(ua, ual);
        content.addView(updBar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL) {{ bottomMargin = dp(24); }});

        setContentView(root);
    }

    /** Внизу экрана: напоминание про кнопку «Домой» (важнее) или подсказка, как добавить приложения. */
    private void updateTip() { updateTip(false); }

    private String tipText;

    private void updateTip(boolean count) {
        if (tip == null) return;
        boolean showUpd = SetupActivity.homeServiceOn(this) && Updater.available(this) && !Updater.bannerDismissed(this)
                && moving == null;
        if (showUpd) {
            ((TextView) updBar.getChildAt(0)).setText("Вышла новая версия Slauncher " + Updater.remoteName(this));
            updBar.setVisibility(View.VISIBLE);
            tip.setVisibility(View.GONE);
            return;
        }
        updBar.setVisibility(View.GONE);
        if (!SetupActivity.homeServiceOn(this)) {
            tip.setText("Кнопка «Домой» на пульте сейчас ведёт не в Slauncher  ·  ОК — включить");
            tip.setFocusable(true);
            tip.setOnFocusChangeListener((v, has) -> {
                v.setBackground(Ui.round(has ? 0xFFFF9F0A : 0xCC1C1C1E, dp(100), dp(has ? 0 : 2), 0xFFFF9F0A));
                tip.setTextColor(has ? 0xFF111111 : 0xFFFFFFFF);
            });
            tip.setBackground(Ui.round(0xCC1C1C1E, dp(100), dp(2), 0xFFFF9F0A));
            tip.setTextColor(0xFFFFFFFF);
            tip.setOnClickListener(v -> startActivity(new Intent(this, SetupActivity.class)));
            tip.setVisibility(View.VISIBLE);
        } else if ((tipText = Comfort.tip(this, count)) != null) {
            tip.setText(tipText);
            tip.setFocusable(false);
            tip.setOnClickListener(null);
            tip.setOnFocusChangeListener(null);
            tip.setBackground(Ui.round(0xB31C1C1E, dp(100), dp(1), 0x33FFFFFF));
            tip.setTextColor(0xE6FFFFFF);
            tip.setVisibility(View.VISIBLE);
        } else tip.setVisibility(View.GONE);
    }

    private FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private View chip(Icon icon, String label, View.OnClickListener click, View.OnLongClickListener longClick) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.HORIZONTAL);
        c.setGravity(Gravity.CENTER_VERTICAL);
        c.setPadding(dp(10), dp(10), dp(10), dp(10));
        c.setFocusable(true);
        Ui.pill(c, false);
        ImageView iv = new ImageView(this);
        iv.setImageDrawable(icon);
        c.addView(iv, new LinearLayout.LayoutParams(dp(20), dp(20)));
        TextView t = Ui.text(this, 15, 500, 0xFF111111);
        t.setShadowLayer(0, 0, 0, 0);
        t.setSingleLine(true);
        t.setText(label);
        t.setVisibility(View.GONE); // подпись — только у выбранного значка
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tl.leftMargin = dp(8);
        c.addView(t, tl);
        c.setOnFocusChangeListener((v, has) -> {
            Ui.pill(v, has);
            ((Icon) iv.getDrawable()).setColor(has ? 0xFF111111 : 0xFFFFFFFF);
            t.setVisibility(has ? View.VISIBLE : View.GONE);
            v.setPadding(dp(10), dp(10), has ? dp(16) : dp(10), dp(10));
            Ui.focusScale(v, has, 1.06f);
            if (has) glow.hide();
        });
        c.setOnClickListener(click);
        if (longClick != null) c.setOnLongClickListener(longClick);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(12);
        c.setLayoutParams(lp);
        return c;
    }

    // ---------- ночной режим ----------

    private void applyNight(boolean night) {
        nightDim.animate().alpha(night ? 0.45f : 0f).setDuration(1500).start();
        glow.setLevel(night ? 0.45f : 0.9f);
        wall.setInterval(night ? WALL_NIGHT : WALL_DAY);
    }

    // ---------- плитки ----------

    private int accent(Apps.App a) {
        Integer c = accents.get(a.pkg);
        if (c == null) {
            c = Ui.accent(a.banner != null ? a.banner : a.icon);
            accents.put(a.pkg, c);
        }
        return c;
    }

    private View cell(Apps.App app, int index) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        cell.setClipChildren(false);

        FrameLayout card = new FrameLayout(this);
        float r = dp(14);
        card.setBackground(Ui.round(0x33FFFFFF, r, 0, 0));
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), r); }
        });
        card.setClipToOutline(true);
        card.setFocusable(true);
        card.setElevation(dp(2));
        ImageView img = new ImageView(this);
        if (app == null) { // «Добавить приложение», когда главный экран пуст
            img.setImageDrawable(new Icon(Icon.PLUS, 0xFFFFFFFF));
            card.addView(img, new FrameLayout.LayoutParams(tileH / 3, tileH / 3, Gravity.CENTER));
        } else if (app.banner != null) {
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            img.setImageDrawable(app.banner);
            card.addView(img, match());
        } else {
            img.setScaleType(ImageView.ScaleType.FIT_CENTER);
            img.setImageDrawable(app.icon);
            card.addView(img, new FrameLayout.LayoutParams(Math.round(tileH * 0.56f), Math.round(tileH * 0.56f), Gravity.CENTER));
        }
        // «стекло»: постоянный мягкий блик сверху + световая полоса, пробегающая при выделении
        View sheen = new View(this);
        sheen.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x2EFFFFFF, 0x08FFFFFF, 0x00FFFFFF}));
        sheen.setAlpha(0f);
        card.addView(sheen, match());
        View glare = new View(this);
        glare.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0x00FFFFFF, 0x00FFFFFF, 0x59FFFFFF, 0x00FFFFFF, 0x00FFFFFF}));
        glare.setAlpha(0f);
        card.addView(glare, match());
        card.setCameraDistance(9000 * getResources().getDisplayMetrics().density);
        cell.addView(card, new LinearLayout.LayoutParams(tileW, tileH));

        TextView label = Ui.text(this, 16, 500, 0xFFFFFFFF);
        label.setText(app == null ? "Добавить приложение" : app.label);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setGravity(Gravity.CENTER);
        label.setAlpha(0f);
        label.setTranslationY(-dp(8)); // в фокусе съезжает на место — посередине между плиткой и рядом ниже
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(tileW + dp(20), ViewGroup.LayoutParams.WRAP_CONTENT);
        ll.topMargin = dp(16);
        cell.addView(label, ll);

        card.setTag(app == null ? "" : app.pkg);
        card.setOnFocusChangeListener((v, has) -> {
            focusFx(v, sheen, glare, has, index);
            label.animate().alpha(has ? 1f : 0f).translationY(has ? 0 : -dp(8)).setDuration(has ? 260 : 150)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
            if (has) onTileFocused(v, app, index);
        });
        card.setOnClickListener(v -> {
            if (moving != null) { endMove(); return; }
            if (app == null) { openLibrary(null); return; }
            glow.pulse();
            v.animate().scaleX(1.06f).scaleY(1.06f).setDuration(90).withEndAction(() ->
                    v.animate().scaleX(FOCUS).scaleY(FOCUS).setDuration(140).start()).start();
            v.postDelayed(() -> launch(app), 170);
        });
        card.setOnLongClickListener(v -> {
            if (moving == null) openLibrary(app);
            return true;
        });
        return cell;
    }

    /**
     * «Лёгкий Apple»: плитка в фокусе плавно крупнее, с подсветкой и светлым верхом.
     * Без настоящей тени, 3D-наклона и раздвигания соседей — это тормозило на слабом ТВ.
     * На время анимации плитка рисуется готовым слоем (withLayer), живой фон на миг замирает.
     */
    private void focusFx(View card, View sheen, View glare, boolean has, int index) {
        card.animate().cancel();
        glare.setAlpha(0f);
        android.view.animation.DecelerateInterpolator ease = new android.view.animation.DecelerateInterpolator(1.5f);
        if (!has) {
            card.animate().scaleX(1f).scaleY(1f).setDuration(160).setInterpolator(ease).withLayer().start();
            sheen.animate().alpha(0f).setDuration(140).start();
            return;
        }
        lastTile = index;
        wall.calm(400);
        card.animate().scaleX(FOCUS).scaleY(FOCUS).setDuration(200).setInterpolator(ease).withLayer().start();
        sheen.animate().alpha(1f).setDuration(200).start();
    }


    private int glowColor = 0xFF8A9BB5;

    private void glowTo(View card) {
        int[] a = new int[2], b = new int[2];
        card.getLocationInWindow(a);
        root.getLocationInWindow(b);
        glow.moveTo(a[0] - b[0] + card.getWidth() / 2f, a[1] - b[1] + card.getHeight() / 2f, glowColor);
    }

    /** Два ряда — сетка стоит. Три и больше — ряд над выбранным виден, верхние ряды уезжают вверх целиком. */
    private void scrollToRow(int row) {
        if (grid.getChildCount() < 2) { scroll.smoothScrollTo(0, 0); return; }
        int step = grid.getChildAt(1).getTop() - grid.getChildAt(0).getTop();
        int target = row >= 2 ? (row - 1) * step : 0;
        if (scroll.getScrollY() != target) scroll.smoothScrollTo(0, target);
    }

    private void onTileFocused(View card, Apps.App app, int index) {
        scrollToRow(index / COLS);
        card.post(() -> {
            int[] a = new int[2], b = new int[2];
            card.getLocationInWindow(a);
            root.getLocationInWindow(b);
            float cx = a[0] - b[0] + card.getWidth() / 2f;
            float cy = a[1] - b[1] + card.getHeight() / 2f;
            glowColor = app == null ? 0xFF8A9BB5 : accent(app);
            glow.moveTo(cx, cy, glowColor);
            // увеличенная плитка с подписью не должна уходить под подсказку/плашку внизу — тогда прячем их
            float bottom = cy + card.getHeight() * FOCUS / 2f + dp(44);
            for (View bar : new View[]{tip, updBar}) {
                if (bar == null || bar.getVisibility() != View.VISIBLE) continue;
                int[] t = new int[2];
                bar.getLocationInWindow(t);
                boolean hide = bottom > t[1] - b[1] && !bar.hasFocus();
                bar.animate().alpha(hide ? 0f : 1f).setDuration(220).start();
            }
        });
        // параллакс: фон чуть смещается вслед за фокусом (с живым фоном — нет: лишняя перерисовка всего экрана)
        if (wall.isSky()) return;
        int col = index % COLS, row = index / COLS;
        wall.animate().translationX((2 - col) * dp(9)).translationY(-Math.min(row, 3) * dp(6))
                .setDuration(900).setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
    }

    private void reloadApps() {
        String focusedPkg = null;
        View f = getCurrentFocus();
        if (f != null && f.getTag() instanceof String) focusedPkg = (String) f.getTag();
        allApps = apps.all();
        dock = apps.dock(allApps);
        rebuildGrid(focusedPkg != null ? focusedPkg : (f == null ? null : ""), f != null && !(f.getTag() instanceof String));
    }

    /** Перестроить сетку; focusPkg — какую плитку выделить (null — первую). */
    private void rebuildGrid(String focusPkg, boolean keepOtherFocus) {
        grid.removeAllViews();
        int gap = dp(24);
        List<Apps.App> items = new ArrayList<>(dock);
        if (items.isEmpty()) items.add(null);
        LinearLayout row = null;
        for (int i = 0; i < items.size(); i++) {
            if (i % COLS == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setClipChildren(false);
                LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                if (i > 0) rl.topMargin = dp(8);
                grid.addView(row, rl);
            }
            View c = cell(items.get(i), i);
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(tileW, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i % COLS > 0) cp.leftMargin = gap;
            row.addView(c, cp);
        }
        if (panel != null || keepOtherFocus) return;
        View target = findTile(focusPkg);
        if (target == null) target = tileAt(0);
        if (target != null) target.requestFocus();
    }

    private View tileAt(int i) {
        int r = i / COLS, c = i % COLS;
        if (r >= grid.getChildCount()) return null;
        ViewGroup row = (ViewGroup) grid.getChildAt(r);
        if (c >= row.getChildCount()) return null;
        return ((ViewGroup) row.getChildAt(c)).getChildAt(0);
    }

    private View findTile(String pkg) {
        if (pkg == null) return null;
        for (int i = 0; i < dock.size(); i++) if (dock.get(i).pkg.equals(pkg)) return tileAt(i);
        return null;
    }

    private int tileIndex(View v) {
        for (int i = 0; i < Math.max(1, dock.size()); i++) if (tileAt(i) == v) return i;
        return -1;
    }

    private void focusFirstTile() {
        View t = tileAt(0);
        if (t != null) t.requestFocus();
    }

    private void launch(Apps.App app) {
        try {
            startActivity(app.launch);
        } catch (Exception e) {
            Toast.makeText(this, "Не удалось открыть " + app.label, Toast.LENGTH_SHORT).show();
        }
    }

    // ---------- шестерёнка: настройки ТВ / Slauncher ----------

    private void openGearMenu() {
        Panel p = new Panel(this, "Настройки", null);
        LinearLayout row = new LinearLayout(this);
        View first = addAction(row, "Настройки телевизора", () -> { closePanel(); openSettings(Settings.ACTION_SETTINGS); });
        addAction(row, "Настройки Slauncher", this::openAppSettings);
        p.body.addView(row);
        showPanel(p, first);
    }

    private TextView infoLine(String text) {
        TextView t = Ui.text(this, 16, 400, 0xE6FFFFFF);
        t.setShadowLayer(0, 0, 0, 0);
        t.setText(text);
        return t;
    }

    /** Строка настроек: слева заголовок и пояснение, справа кнопки — всё помещается без прокрутки. */
    private void section(Panel p, String head, String text, LinearLayout actions) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView h = Ui.text(this, 12, 600, 0x80FFFFFF);
        h.setShadowLayer(0, 0, 0, 0);
        h.setLetterSpacing(0.12f);
        h.setText(head.toUpperCase(new Locale("ru")));
        col.addView(h);
        TextView t = infoLine(text);
        t.setTextSize(15);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tl.topMargin = dp(4);
        col.addView(t, tl);
        row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (actions != null) {
            LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            al.leftMargin = dp(16);
            row.addView(actions, al);
        }
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rl.topMargin = p.body.getChildCount() == 0 ? dp(4) : dp(14);
        if (p.body.getChildCount() > 0) {
            View sep = new View(this);
            sep.setBackgroundColor(0x1AFFFFFF);
            LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1);
            sl.topMargin = dp(14);
            p.body.addView(sep, sl);
        }
        p.body.addView(row, rl);
    }

    private void openAppSettings() {
        Panel p = new Panel(this, "Настройки Slauncher", null);
        p.count.setText("версия " + Updater.myName(this));

        LinearLayout upd = new LinearLayout(this);
        boolean avail = Updater.available(this);
        View first = addAction(upd, avail ? "Обновить до " + Updater.remoteName(this) : "Проверить обновление", () -> runUpdate(p));
        section(p, "Обновление", avail ? "Доступна новая версия " + Updater.remoteName(this) + "."
                : "Установлена последняя проверенная версия.", upd);

        LinearLayout ha = new LinearLayout(this);
        addAction(ha, "Настроить с телефона", this::openHaSetup);
        if (!HaConfig.url(this).isEmpty()) {
            boolean on = HaConfig.enabled(this);
            addAction(ha, on ? "Выключить" : "Включить", () -> {
                HaConfig.setEnabled(this, !on);
                HaClient.restart(this);
                if (on) { HaReceiver.prefs(this).edit().remove("data").apply(); haCorner.refresh(); }
                openAppSettings();
            });
        }
        String haState = HaConfig.url(this).isEmpty() ? "Не настроен. Климат по комнатам и карточки событий — по желанию."
                : (HaConfig.enabled(this) ? "Связь: " + HaClient.status + "  ·  комнат: " + HaConfig.rooms(this).length()
                + "  ·  событий: " + HaConfig.events(this).length() : "Выключен.");
        section(p, "Home Assistant", haState, ha);

        LinearLayout city = new LinearLayout(this);
        addAction(city, "Выбрать город", this::openCityPicker);
        if (!Loc.auto(this)) addAction(city, "Определять автоматически", () -> {
            Loc.setAuto(this);
            Toast.makeText(this, "Определяю город…", Toast.LENGTH_SHORT).show();
            new Thread(() -> {
                Loc.refreshAuto(this, true);
                runOnUiThread(() -> { wall.refreshWeather(); wall.next(); openAppSettings(); });
            }).start();
        });
        section(p, "Город для погоды", Loc.name(this) + (!Loc.auto(this) ? " — выбран вручную"
                : Loc.vpnSuspected(this) ? " — интернет идёт через VPN, город мог определиться неточно: лучше выбрать вручную"
                : " — определён автоматически"), city);

        LinearLayout perm = new LinearLayout(this);
        addAction(perm, "Удобство", this::openComfort);
        addAction(perm, "Оптимизация", this::openOptimizer);
        addAction(perm, "Разрешения", () -> { closePanel(); startActivity(new Intent(this, SetupActivity.class)); });
        section(p, "Система", "Таймер сна, память, перенос; чистка от рекламы; разрешения.", perm);
        showPanel(p, first);
    }

    private void runUpdate(Panel p) {
        Updater.check(this, true, (avail, name) -> {
            if (!avail) { Toast.makeText(this, "Установлена последняя версия " + Updater.myName(this), Toast.LENGTH_LONG).show(); return; }
            if (Adb.known(this)) { // тихо, без экрана подтверждения
                Updater.silentInstall(this, (text, done) -> Toast.makeText(this, text, Toast.LENGTH_LONG).show());
                return;
            }
            if (!Updater.canInstall(this)) {
                Toast.makeText(this, "Разрешите Slauncher устанавливать приложения", Toast.LENGTH_LONG).show();
                try {
                    startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, android.net.Uri.parse("package:" + getPackageName()))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (Exception e) { startActivity(new Intent(this, SetupActivity.class)); }
                return;
            }
            Updater.downloadAndInstall(this, (text, done) -> Toast.makeText(this, text, Toast.LENGTH_SHORT).show());
        });
    }

    /** Выбор города вручную: поле ввода (экранная клавиатура пульта) → «Найти» → список совпадений. */
    private void openCityPicker() {
        Panel p = new Panel(this, "Город для погоды", "Введите название и нажмите «Найти»");
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        android.widget.EditText q = new android.widget.EditText(this);
        q.setSingleLine(true);
        q.setHint("например, Вологда");
        q.setTextColor(0xFFFFFFFF);
        q.setHintTextColor(0x66FFFFFF);
        q.setTextSize(18);
        q.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        q.setPadding(dp(18), dp(10), dp(18), dp(10));
        q.setBackground(Ui.round(0x22FFFFFF, dp(14), dp(1), 0x33FFFFFF));
        q.setOnFocusChangeListener((v, has) -> v.setBackground(Ui.round(has ? 0x33FFFFFF : 0x22FFFFFF, dp(14), dp(has ? 2 : 1), has ? 0xE6FFFFFF : 0x33FFFFFF)));
        row.addView(q, new LinearLayout.LayoutParams(dp(360), ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout results = new LinearLayout(this);
        Runnable find = () -> {
            String text = q.getText().toString().trim();
            if (text.isEmpty()) return;
            results.removeAllViews();
            results.addView(infoLine("Ищу…"));
            new Thread(() -> {
                List<Loc.City> found;
                try { found = Loc.search(text); } catch (Exception e) { found = new ArrayList<>(); }
                final List<Loc.City> f = found;
                runOnUiThread(() -> {
                    results.removeAllViews();
                    ((android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                            .hideSoftInputFromWindow(q.getWindowToken(), 0); // убрать клавиатуру — видно список
                    if (f.isEmpty()) { results.addView(infoLine("Ничего не нашлось")); return; }
                    View first = null;
                    for (Loc.City c : f) {
                        View b = addAction(results, c.name + (c.region.isEmpty() ? "" : " · " + c.region), () -> {
                            Loc.setManual(this, c);
                            closePanel();
                            Toast.makeText(this, "Город: " + c.name, Toast.LENGTH_SHORT).show();
                            wall.refreshWeather();
                            wall.next();
                        });
                        if (first == null) first = b;
                    }
                    if (first != null) first.requestFocus();
                });
            }).start();
        };
        q.setOnEditorActionListener((v, id, ev) -> { find.run(); return true; });
        LinearLayout.LayoutParams fl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fl.leftMargin = dp(12);
        row.addView(Panel.action(this, "Найти", find), fl);
        p.body.addView(row);
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setClipToPadding(false);
        hs.setPadding(0, dp(16), 0, dp(4));
        hs.addView(results);
        p.body.addView(hs);
        showPanel(p, q);
    }

    // ---------- оптимизация (встроенный ADB) ----------

    private Optimizer.State optState;
    private boolean backupAsked; // в этом заходе уже спросили про резервную копию

    private interface Job<T> { T run() throws Exception; }
    private interface Ok<T> { void ok(T v); }

    /** Фоновая работа через ADB с подписью в заголовке панели. */
    private <T> void bg(Panel p, String busy, Job<T> job, Ok<T> ok) {
        if (p != null && busy != null) p.count.setText(busy);
        new Thread(() -> {
            try {
                T v = job.run();
                runOnUiThread(() -> ok.ok(v));
            } catch (Exception e) {
                Net.log("optimizer", e);
                runOnUiThread(() -> {
                    Toast.makeText(this, "Не получилось: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    if (p != null && panel == p) p.count.setText("");
                });
            }
        }).start();
    }

    private void openOptimizer() {
        backupAsked = false;
        Panel p = new Panel(this, "Оптимизация", null);
        p.count.setText("проверяю доступ…");
        LinearLayout row = new LinearLayout(this);
        View close = addAction(row, "Закрыть", this::closePanel);
        p.body.addView(row);
        showPanel(p, close);
        new Thread(() -> {
            Optimizer.State s = null;
            String err = null;
            try { s = Optimizer.read(this); }
            catch (Exception e) { err = e instanceof Adb.NoAccess ? e.getMessage() : "ошибка: " + e.getMessage(); Net.log("adb", e); }
            final Optimizer.State fs = s;
            final String fe = err;
            runOnUiThread(() -> {
                if (panel != p) return;
                if (fs != null) { optState = fs; buildOptimizer(null); }
                else buildOptConnect(fe);
            });
        }).start();
    }

    private void buildOptConnect(String err) {
        boolean off = "сетевая отладка выключена".equals(err);
        Panel p = new Panel(this, "Оптимизация",
                "Slauncher сам отключит рекламу и лишние приложения, настроит разрешения и будет обновляться без подтверждений.");
        p.count.setText("нет доступа");
        LinearLayout acts = new LinearLayout(this);
        View first;
        if (off) {
            first = addAction(acts, "Для разработчиков", () -> openSettings(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
            addAction(acts, "Проверить снова", this::openOptimizer);
        } else {
            first = addAction(acts, "Подключить", () -> {
                p.count.setText("подтвердите на экране…");
                bg(p, null, () -> Adb.sh(this, "echo ok", 90_000), v -> {
                    Toast.makeText(this, "Доступ получен ✓", Toast.LENGTH_SHORT).show();
                    openOptimizer();
                });
            });
        }
        addAction(acts, "Закрыть", this::closePanel);
        section(p, "Доступ к системе", off
                ? "Сетевая отладка выключена. Включите: Настройки → Система → Для разработчиков → «Отладка по сети» "
                + "(режим разработчика: Об устройстве → 7 раз нажать «Сборка»)."
                : "Нажмите «Подключить». На экране появится «Разрешить отладку?» — отметьте «Всегда разрешать» и нажмите OK.",
                acts);
        showPanel(p, first);
    }

    private List<Optimizer.Item> present(boolean recommendedEnabledOnly) {
        List<Optimizer.Item> r = new ArrayList<>();
        for (Optimizer.Item i : Optimizer.CATALOG) {
            Optimizer.St s = optState.of(i.pkg);
            if (s == Optimizer.St.ABSENT) continue;
            if (recommendedEnabledOnly && !(i.recommended && s == Optimizer.St.ENABLED)) continue;
            boolean dup = false;
            for (Optimizer.Item x : r) if (x.pkg.equals(i.pkg)) dup = true;
            if (!dup) r.add(i);
        }
        return r;
    }

    private void buildOptimizer(String focus) {
        Panel p = new Panel(this, "Оптимизация", null);
        p.count.setText("доступ к системе ✓");

        String a = optState.anim;
        boolean fast = a.equals("0.5") || a.equals("0.5x");
        LinearLayout perm = new LinearLayout(this);
        View first = addAction(perm, "Включить всё", () -> bg(p, "настраиваю…", () -> Optimizer.fixAll(this), v -> {
            p.count.setText("доступ к системе ✓");
            Toast.makeText(this, "0".equals(v) ? "Всё уже было включено ✓" : "Разрешения Slauncher включены ✓", Toast.LENGTH_LONG).show();
        }));
        addAction(perm, fast ? "Обычные анимации" : "Ускорить анимации", () -> bg(p, "меняю…", () -> {
            Optimizer.setAnim(this, fast ? "1.0" : "0.5");
            return Optimizer.read(this);
        }, s -> { optState = s; buildOptimizer(null); }));
        section(p, "Разрешения и скорость", "Кнопка «Домой», показ поверх фильма, заставка, автозапуск. Анимации: "
                + (fast ? "ускорены (0.5×)." : "обычные."), perm);

        List<Optimizer.Item> todo = present(true);
        int off = 0;
        for (Optimizer.Item i : present(false)) if (optState.of(i.pkg) != Optimizer.St.ENABLED) off++;
        LinearLayout apps = new LinearLayout(this);
        if (!todo.isEmpty()) addAction(apps, "Отключить лишнее (" + todo.size() + ")", () -> withBackup(() ->
                bg(p, "отключаю…", () -> {
                    List<String> cmds = new ArrayList<>();
                    for (Optimizer.Item i : todo) cmds.add("pm disable-user --user 0 " + i.pkg);
                    Optimizer.runBatch(this, cmds);
                    Adb.prefs(this).edit().putBoolean("dirty", true).apply();
                    return Optimizer.read(this);
                }, s -> { optState = s; buildOptimizer(null); Toast.makeText(this, "Отключено: " + todo.size(), Toast.LENGTH_LONG).show(); }),
                this::buildOptimizerAgain));
        View list = addAction(apps, "Выбрать…", () -> openOptList(null));
        section(p, "Реклама и лишние приложения", (todo.isEmpty() ? "Всё лишнее уже отключено" : "Можно отключить: " + todo.size())
                + "  ·  отключено: " + off + "  ·  сторонних: " + optState.third.size(), apps);

        LinearLayout gtv = new LinearLayout(this);
        String gtvText;
        if (Optimizer.hasStockHome(this, optState)) {
            boolean gOff = Optimizer.homeOff(this, optState);
            String hn = Optimizer.homeName(this, optState);
            addAction(gtv, gOff ? "Вернуть " + hn : "Отключить " + hn, () -> {
                if (gOff) bg(p, "возвращаю…", () -> { Optimizer.homeOn(this); return Optimizer.read(this); },
                        s -> { optState = s; buildOptimizer(null); Toast.makeText(this, hn + " включён обратно", Toast.LENGTH_LONG).show(); });
                else bg(p, "проверяю Wi‑Fi…", () -> Optimizer.wifiFromSetup(this), fromSetup -> {
                    if (fromSetup) confirmGoogleTvOff();
                    else withBackup(this::doGoogleTvOff, this::buildOptimizerAgain);
                });
            });
            gtvText = gOff ? "Штатный экран " + hn + " отключён, домашний экран — Slauncher."
                    : hn + " с рекламой работает в фоне. Можно отключить — вернуть одной кнопкой.";
        } else gtvText = "Персонализация рекламы и рекламный идентификатор — в настройках Google.";
        addAction(gtv, "Реклама Google", () -> {
            try { startActivity(new Intent("com.google.android.gms.settings.ADS_PRIVACY").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
            catch (Exception e) { openSettings(Settings.ACTION_PRIVACY_SETTINGS); }
        });
        section(p, "Штатный главный экран", gtvText, gtv);

        String last = Optimizer.lastBackup(this);
        LinearLayout bk = new LinearLayout(this);
        addAction(bk, "Сохранить сейчас", () -> bg(p, "сохраняю копию…", () -> Optimizer.backup(this, Optimizer.read(this), null),
                d -> { Toast.makeText(this, "Копия сохранена ✓", Toast.LENGTH_LONG).show(); buildOptimizer(null); }));
        addAction(bk, "Восстановить…", this::openRestore);
        section(p, "Резервные копии", last.isEmpty() ? "Ещё не делалась — Slauncher предложит её перед изменениями."
                : "Последняя: " + Optimizer.pretty(last) + ". Папка Slauncher/backups.", bk);
        showPanel(p, todo.isEmpty() ? list : first);
    }

    private void buildOptimizerAgain() { buildOptimizer(null); }

    /** «Удалить Slauncher и вернуть всё как было»: Google TV, приложения из самой первой копии, разрешения, сам Slauncher. */
    private void confirmUninstallAll() {
        Panel p = new Panel(this, "Вернуть как было?", "Ищу самую первую резервную копию…");
        LinearLayout row = new LinearLayout(this);
        View cancel = addAction(row, "Отмена", this::openComfort);
        p.body.addView(row);
        showPanel(p, cancel);
        bg(p, null, () -> Optimizer.listBackups(this), list -> {
            if (panel != p) return;
            String first = list.isEmpty() ? null : list.get(list.size() - 1);
            p.hint.setText("Телевизор вернётся к тому, что было до Slauncher: "
                    + (optState != null && Optimizer.homeOff(this, optState) ? "включится " + Optimizer.homeName(this, optState) + ", " : "")
                    + (first != null ? "приложения — как в самой первой копии (" + Optimizer.pretty(first) + "), " : "")
                    + "уберутся разрешения и заставка Slauncher, затем он удалится. Резервные копии останутся в памяти ТВ.");
            addAction(row, "Удалить и вернуть", () -> bg(p, "возвращаю…", () -> { Optimizer.uninstallAll(this, first); return 0; }, v -> { }));
        });
    }

    private void doGoogleTvOff() {
        Panel p = panel instanceof Panel ? (Panel) panel : null;
        bg(p, "отключаю…", () -> { Optimizer.homeOff(this); return Optimizer.read(this); },
                s -> { optState = s; buildOptimizer(null); Toast.makeText(this, "Штатный экран отключён, домашний — Slauncher", Toast.LENGTH_LONG).show(); });
    }

    /** Сеть Wi‑Fi «принадлежит» мастеру Google — при отключении Android её забудет. Предупредить. */
    private void confirmGoogleTvOff() {
        Panel p = new Panel(this, "Wi‑Fi придётся подключить заново",
                "Сеть Wi‑Fi на этом телевизоре сохранил мастер первичной настройки Google. При отключении штатного экрана Android её забудет: "
                        + "после этого откройте значок Wi‑Fi на главном экране и введите пароль. Один раз — потом сеть будет системной.");
        LinearLayout row = new LinearLayout(this);
        View cancel = addAction(row, "Отмена", this::buildOptimizerAgain);
        addAction(row, "Понятно, отключить", () -> withBackup(this::doGoogleTvOff, this::buildOptimizerAgain));
        p.body.addView(row);
        showPanel(p, cancel);
    }

    /** Перед первым изменением в этом заходе — предложить резервную копию. */
    private void withBackup(Runnable action, Runnable back) {
        if (backupAsked) { action.run(); return; }
        Panel p = new Panel(this, "Резервная копия",
                "Перед изменениями сохранить, какие приложения сейчас включены и отключены? Из копии всё возвращается одной кнопкой: "
                        + "Оптимизация → Восстановить.");
        LinearLayout row = new LinearLayout(this);
        View first = addAction(row, "Сохранить и продолжить", () -> bg(p, "сохраняю копию…",
                () -> Optimizer.backup(this, Optimizer.read(this), null), d -> {
                    backupAsked = true;
                    Toast.makeText(this, "Копия сохранена ✓", Toast.LENGTH_SHORT).show();
                    action.run();
                }));
        addAction(row, "Без копии", () -> { backupAsked = true; action.run(); });
        addAction(row, "Отмена", back);
        p.body.addView(row);
        showPanel(p, first);
    }

    private void openOptList(String focusPkg) {
        Panel p = new Panel(this, "Приложения и сервисы",
                "Системные отключаются (можно вернуть), сторонние удаляются — установочный файл уходит в копию.");
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        sv.setClipToPadding(false);
        sv.setFadingEdgeLength(dp(30));
        sv.setVerticalFadingEdgeEnabled(true);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(4), dp(2), dp(14), dp(8));
        sv.addView(col);
        View first = null, focus = null;
        String group = null;
        int off = 0;
        for (Optimizer.Item i : present(false)) {
            if (!i.group.equals(group)) { group = i.group; col.addView(listHead(group)); }
            Optimizer.St s = optState.of(i.pkg);
            if (s != Optimizer.St.ENABLED) off++;
            View b = listRow(col, i.name, i.pkg, s, false);
            if (first == null) first = b;
            if (i.pkg.equals(focusPkg)) focus = b;
        }
        if (!optState.third.isEmpty()) {
            col.addView(listHead("Сторонние приложения"));
            android.content.pm.PackageManager pm = getPackageManager();
            List<String[]> third = new ArrayList<>();
            for (String pkg : optState.third) {
                String label = pkg;
                try { label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString(); } catch (Exception ignored) { }
                third.add(new String[]{label, pkg});
            }
            java.util.Collections.sort(third, (x, y) -> x[0].compareToIgnoreCase(y[0]));
            for (String[] t : third) {
                View b = listRow(col, t[0], t[1], Optimizer.St.ENABLED, true);
                if (first == null) first = b;
                if (t[1].equals(focusPkg)) focus = b;
            }
        }
        p.count.setText("отключено: " + off);
        // панель не обрезает детей (эффект фокуса) — список обрезаем отдельной рамкой, иначе он вылезает на заголовок
        FrameLayout clip = new FrameLayout(this);
        clip.setClipChildren(true);
        clip.addView(sv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        p.body.addView(clip, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(250)));
        LinearLayout bottom = new LinearLayout(this);
        bottom.setGravity(Gravity.END);
        addAction(bottom, "Готово", () -> buildOptimizer(null));
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bl.topMargin = dp(12);
        p.body.addView(bottom, bl);
        showPanel(p, focus != null ? focus : first);
    }

    private TextView listHead(String text) {
        TextView h = Ui.text(this, 12, 600, 0x80FFFFFF);
        h.setShadowLayer(0, 0, 0, 0);
        h.setLetterSpacing(0.12f);
        h.setText(text.toUpperCase(new Locale("ru")));
        h.setPadding(0, dp(12), 0, dp(4));
        return h;
    }

    private View listRow(LinearLayout col, String name, String pkg, Optimizer.St s, boolean third) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, dp(5), 0, dp(5));
        LinearLayout txt = new LinearLayout(this);
        txt.setOrientation(LinearLayout.VERTICAL);
        TextView n = infoLine(name);
        n.setTextSize(15);
        txt.addView(n);
        TextView pk = Ui.text(this, 11, 400, 0x66FFFFFF);
        pk.setShadowLayer(0, 0, 0, 0);
        pk.setText(pkg);
        txt.addView(pk);
        r.addView(txt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView st = Ui.text(this, 13, 500, s == Optimizer.St.ENABLED ? 0xFF30D158 : 0x99FFFFFF);
        st.setShadowLayer(0, 0, 0, 0);
        st.setText(s == Optimizer.St.ENABLED ? (third ? "установлено" : "включено") : s == Optimizer.St.DISABLED ? "отключено" : "удалено");
        r.addView(st, new LinearLayout.LayoutParams(dp(110), ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout acts = new LinearLayout(this);
        View b;
        if (third) {
            b = addAction(acts, "Удалить", () -> confirmUninstall(name, pkg));
        } else if (s == Optimizer.St.ENABLED) {
            b = addAction(acts, "Отключить", () -> withBackup(() -> listOp(pkg, () -> Optimizer.disable(this, pkg)), () -> openOptList(pkg)));
        } else {
            b = addAction(acts, s == Optimizer.St.REMOVED ? "Вернуть" : "Включить", () -> listOp(pkg, () -> Optimizer.enable(this, pkg, s)));
        }
        b.setTag(pkg);
        r.addView(acts, new LinearLayout.LayoutParams(dp(150), ViewGroup.LayoutParams.WRAP_CONTENT));
        col.addView(r);
        return b;
    }

    private interface Act { void run() throws Exception; }

    private void listOp(String pkg, Act act) {
        Panel p = panel instanceof Panel ? (Panel) panel : null;
        bg(p, "выполняю…", () -> { act.run(); return Optimizer.read(this); }, s -> { optState = s; openOptList(pkg); });
    }

    private void confirmUninstall(String name, String pkg) {
        Panel p = new Panel(this, "Удалить «" + name + "»?",
                "Установочный файл сохранится в резервной копии — вернуть: Оптимизация → Восстановить. Данные приложения (вход, настройки) не сохраняются.");
        LinearLayout row = new LinearLayout(this);
        View cancel = addAction(row, "Отмена", () -> openOptList(pkg));
        addAction(row, "Удалить", () -> withBackup(() -> listOp(null, () -> Optimizer.uninstall(this, pkg)), () -> openOptList(pkg)));
        p.body.addView(row);
        showPanel(p, cancel);
    }

    private void openRestore() {
        Panel p = new Panel(this, "Восстановить из копии",
                "Приложения вернутся в состояние на момент копии; удалённые сторонние — установятся заново. Slauncher не трогается.");
        LinearLayout strip = p.strip();
        View cancel = addAction(strip, "Отмена", () -> buildOptimizer(null));
        showPanel(p, cancel);
        bg(p, "ищу копии…", () -> Optimizer.listBackups(this), list -> {
            if (panel != p) return;
            p.count.setText(list.isEmpty() ? "копий нет" : "копий: " + list.size());
            strip.removeView(cancel);
            View first = null;
            for (int i = 0; i < Math.min(8, list.size()); i++) {
                String name = list.get(i);
                View b = addAction(strip, Optimizer.pretty(name), () -> bg(p, "восстанавливаю…", () -> Optimizer.restore(this, name), n -> {
                    Toast.makeText(this, n == 0 ? "Всё уже как в копии" : "Готово, изменений: " + n, Toast.LENGTH_LONG).show();
                    openOptimizer();
                }));
                if (first == null) first = b;
            }
            addAction(strip, "Отмена", () -> buildOptimizer(null));
            if (first != null) first.requestFocus();
            else strip.getChildAt(0).requestFocus();
        });
    }

    // ---------- удобство ----------

    private void openComfort() {
        Panel p = new Panel(this, "Удобство", null);
        boolean adb = Adb.known(this);

        LinearLayout sl = new LinearLayout(this);
        View first = addAction(sl, "Таймер сна", this::openSleep);
        addAction(sl, "Освободить память", () -> bg(p, "освобождаю…", () -> Comfort.freeMemory(this), r -> {
            long gain = Math.max(0, r[1] - r[0]);
            Toast.makeText(this, (gain > 0 ? "Освобождено " + Comfort.mb(gain) + ". " : "")
                    + "Свободно " + Comfort.gb(r[1]) + " из " + Comfort.gb(r[2]), Toast.LENGTH_LONG).show();
            openComfort();
        }));
        int left = Comfort.sleepLeftMin(this);
        section(p, "Сон и память", (left > 0 ? "Выключение через " + left + " мин.  " : "")
                + "Свободно " + Comfort.gb(Comfort.availMem(this)) + " из " + Comfort.gb(Comfort.totalMem(this)) + ".", sl);

        LinearLayout st = new LinearLayout(this);
        addAction(st, "Выбрать…", this::openStartPicker);
        String sp = Comfort.startPkg(this);
        section(p, "При включении открывать", sp.isEmpty() ? "Главный экран Slauncher." : label(sp) + " (кнопка «Домой» — в Slauncher).", st);

        LinearLayout bgr = new LinearLayout(this);
        boolean skyOn = Comfort.bgSky(this);
        addAction(bgr, (skyOn ? "" : "✓ ") + "Фото природы", () -> setHomeBackground(false));
        addAction(bgr, (skyOn ? "✓ " : "") + "Живая погода", () -> setHomeBackground(true));
        section(p, "Фон главного экрана", skyOn ? "Живая погода: дождь, снег, туман — под погоду и время суток."
                : "Фото природы под время суток, сезон и погоду.", bgr);

        LinearLayout tr = new LinearLayout(this);
        addAction(tr, "Сохранить", () -> bg(p, "сохраняю…", () -> Transfer.save(this), n -> {
            Toast.makeText(this, "Сохранено: " + n + (Adb.known(this) ? " (Slauncher/settings)" : ""), Toast.LENGTH_LONG).show();
            openComfort();
        }));
        addAction(tr, "Загрузить…", this::openTransferLoad);
        addAction(tr, "С телефона", this::openTransferPhone);
        section(p, "Перенос настроек", "Плитки, город, Home Assistant, фон — на другой ТВ.", tr);

        LinearLayout back = new LinearLayout(this);
        addAction(back, "Вернуть как было…", () -> {
            if (Adb.known(this)) confirmUninstallAll();
            else Toast.makeText(this, "Нужна «Оптимизация» → «Подключить». Или: Настройки ТВ → Приложения → Slauncher → Удалить",
                    Toast.LENGTH_LONG).show();
        });
        section(p, "Вернуть как было", "Убрать Slauncher и все его изменения с телевизора.", back);
        if (!adb) p.count.setText("часть функций — после «Оптимизация → Подключить»");
        showPanel(p, first);
    }

    private void setHomeBackground(boolean sky) {
        Comfort.setBgSky(this, sky);
        wall.setSky(sky);
        openComfort();
    }

    private boolean boosting;

    /** Ракета: закрыть всё, что висит в фоне, и показать, сколько памяти освободилось. */
    private void boost(View v) {
        if (boosting) return;
        boosting = true;
        View icon = ((ViewGroup) v).getChildAt(0);
        icon.animate().translationX(dp(5)).translationY(-dp(5)).setDuration(220).withEndAction(() ->
                icon.animate().translationX(-dp(3)).translationY(dp(3)).setDuration(160).withEndAction(() ->
                        icon.animate().translationX(dp(5)).translationY(-dp(5)).setDuration(220).start()).start()).start();
        new Thread(() -> {
            long[] r = Comfort.freeMemory(this);
            runOnUiThread(() -> {
                boosting = false;
                icon.animate().translationX(0).translationY(0).setDuration(200).start();
                long gain = Math.max(0, r[1] - r[0]);
                Toast.makeText(this, (gain >= 10L * 1048576 ? "Освобождено " + Comfort.mb(gain) + " · " : "Уже было чисто · ")
                        + "свободно " + Comfort.gb(r[1]), Toast.LENGTH_LONG).show();
                hideTipFor(3800);
            });
        }).start();
    }

    /** Убрать подсказку внизу, пока висит тост (он показывается ровно на её месте). */
    private void hideTipFor(long ms) {
        if (tip == null || tip.getVisibility() != View.VISIBLE) return;
        tip.animate().alpha(0f).setDuration(150).start();
        tip.postDelayed(() -> tip.animate().alpha(1f).setDuration(250).start(), ms);
    }

    private String label(String pkg) {
        try { return getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(pkg, 0)).toString(); }
        catch (Exception e) { return pkg; }
    }

    private void openSleep() {
        Comfort.tipDone(this, "sleep");
        updateTip();
        Panel p = new Panel(this, "Таймер сна", Adb.known(this)
                ? "Телевизор уйдёт в дежурный режим. За минуту до этого появится предупреждение поверх фильма."
                : "Для таймера нужна «Оптимизация»: Настройки Slauncher → Система → Оптимизация → «Подключить».");
        int left = Comfort.sleepLeftMin(this);
        if (left > 0) p.count.setText("выключение через " + left + " мин");
        LinearLayout row = new LinearLayout(this);
        View first = null;
        if (Adb.known(this)) {
            for (int m : new int[]{15, 30, 60, 90, 120}) {
                View b = addAction(row, m + " мин", () -> {
                    Comfort.setSleep(this, m);
                    closePanel();
                    Toast.makeText(this, "Телевизор выключится через " + m + " мин", Toast.LENGTH_LONG).show();
                    root.removeCallbacks(sleepTick);
                    sleepTick.run();
                });
                if (first == null) first = b;
            }
            if (left > 0) first = addAction(row, "Отменить", () -> {
                Comfort.setSleep(this, 0);
                closePanel();
                sleepTick.run();
                Toast.makeText(this, "Таймер сна отменён", Toast.LENGTH_SHORT).show();
            });
        } else {
            first = addAction(row, "Открыть оптимизацию", this::openOptimizer);
        }
        p.body.addView(row);
        showPanel(p, first);
    }

    private void openStartPicker() {
        Panel p = new Panel(this, "При включении открывать", "Работает после перезагрузки и после выхода из дежурного режима.");
        LinearLayout strip = p.strip();
        String cur = Comfort.startPkg(this);
        View first = addAction(strip, (cur.isEmpty() ? "✓ " : "") + "Главный экран", () -> { Comfort.setStartPkg(this, ""); openComfort(); });
        List<Apps.App> list = new ArrayList<>(dock);
        for (Apps.App a : allApps) if (!list.contains(a)) list.add(a);
        for (Apps.App a : list) {
            if (Apps.SETTINGS.equals(a.pkg)) continue;
            View b = addAction(strip, (a.pkg.equals(cur) ? "✓ " : "") + a.label, () -> { Comfort.setStartPkg(this, a.pkg); openComfort(); });
            if (a.pkg.equals(cur)) first = b;
        }
        showPanel(p, first);
    }

    private void openTransferLoad() {
        Panel p = new Panel(this, "Загрузить настройки", "Файлы из папки Slauncher/settings на этом телевизоре.");
        LinearLayout strip = p.strip();
        View cancel = addAction(strip, "Отмена", this::openComfort);
        showPanel(p, cancel);
        bg(p, "ищу файлы…", () -> Transfer.list(this), names -> {
            if (panel != p) return;
            p.count.setText(names.isEmpty() ? "файлов нет — сохраните или загрузите с телефона" : "файлов: " + names.size());
            View first = null;
            for (int i = 0; i < Math.min(8, names.size()); i++) {
                String n = names.get(i);
                View b = addAction(strip, Transfer.pretty(n), () -> bg(p, "загружаю…",
                        () -> Transfer.apply(this, Transfer.read(this, n)), this::afterImport));
                if (first == null) first = b;
            }
            if (first != null) first.requestFocus();
        });
    }

    private void openTransferPhone() {
        Panel p = new Panel(this, "Перенос с телефона", "Откройте на телефоне в той же Wi‑Fi сети:");
        TextView addr = Ui.text(this, 34, 500, 0xFFFFFFFF);
        addr.setShadowLayer(0, 0, 0, 0);
        p.body.addView(addr);
        TextView how = Ui.text(this, 15, 400, 0xB3FFFFFF);
        how.setShadowLayer(0, 0, 0, 0);
        how.setText("Там можно скачать настройки этого телевизора или загрузить файл с другого. Страница работает, пока открыт этот экран.");
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hl.topMargin = dp(14);
        hl.bottomMargin = dp(16);
        p.body.addView(how, hl);
        LinearLayout row = new LinearLayout(this);
        View close = addAction(row, "Закрыть", this::closePanel);
        p.body.addView(row);
        showPanel(p, close); // закрывает прошлую панель — сервер запускаем уже после
        String pin = Transfer.start(this, this::afterImport);
        addr.setText(pin == null ? "Не удалось открыть страницу" : "http://" + HaSetupServer.ip() + ":" + Transfer.PORT + "/" + pin);
    }

    /** После загрузки файла: плитки/город/старт уже применены; предложить отключить те же приложения. */
    private void afterImport(Transfer.Applied a) {
        reloadApps();
        wall.refreshWeather();
        haCorner.refresh();
        int n = a.toDisable.size();
        boolean more = n > 0 || a.anim != null || a.googleTvOff;
        Panel p = new Panel(this, "Настройки загружены ✓", "Плитки, город, стартовое приложение и Home Assistant применены"
                + (HaConfig.url(this).isEmpty() || HaConfig.enabled(this) ? "." : " — токен Home Assistant введите заново: Home Assistant → «Настроить с телефона».")
                + (more ? "\nНа том телевизоре ещё было: " + (n > 0 ? "отключено приложений — " + n + "; " : "")
                + (a.anim != null ? "анимации " + a.anim + "×; " : "") + (a.googleTvOff ? "штатный главный экран отключён" : "") : ""));
        LinearLayout row = new LinearLayout(this);
        View first;
        if (more && Adb.known(this)) {
            first = addAction(row, "Сделать так же", () -> withBackup(() -> bg(p, "применяю…", () -> {
                List<String> cmds = new ArrayList<>();
                for (String pkg : a.toDisable) cmds.add("pm disable-user --user 0 " + pkg);
                if (a.anim != null) cmds.add(Optimizer.animCmd(a.anim));
                Optimizer.runBatch(this, cmds);
                Adb.prefs(this).edit().putBoolean("dirty", true).apply();
                return Optimizer.read(this);
            }, s -> {
                optState = s;
                if (a.googleTvOff) Toast.makeText(this, "Штатный главный экран — отдельно: Оптимизация → «Отключить…»", Toast.LENGTH_LONG).show();
                buildOptimizer(null);
            }), this::closePanel));
            addAction(row, "Не надо", this::closePanel);
        } else {
            first = addAction(row, "Готово", this::closePanel);
            if (more) p.count.setText("отключить так же — после «Оптимизация → Подключить»");
        }
        p.body.addView(row);
        showPanel(p, first);
    }

    private void openWhatsNew() {
        if (panel != null) return;
        Panel p = new Panel(this, "Что нового в Slauncher " + Updater.myName(this), null);
        TextView t = infoLine(Comfort.WHATS_NEW);
        t.setLineSpacing(dp(4), 1f);
        p.body.addView(t);
        LinearLayout row = new LinearLayout(this);
        View ok = addAction(row, "Понятно", this::closePanel);
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rl.topMargin = dp(18);
        p.body.addView(row, rl);
        showPanel(p, ok);
    }

    private void openHaSetup() {
        Panel p = new Panel(this, "Home Assistant", "Откройте на телефоне в той же Wi‑Fi сети:");
        TextView addr = Ui.text(this, 34, 500, 0xFFFFFFFF);
        addr.setShadowLayer(0, 0, 0, 0);
        p.body.addView(addr);
        TextView how = Ui.text(this, 15, 400, 0xB3FFFFFF);
        how.setShadowLayer(0, 0, 0, 0);
        how.setText("1. Введите адрес Home Assistant и долгосрочный токен (HA → профиль → Безопасность).\n"
                + "2. Выберите комнаты и события. Страница работает, пока открыт этот экран.");
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hl.topMargin = dp(14);
        hl.bottomMargin = dp(16);
        p.body.addView(how, hl);
        LinearLayout row = new LinearLayout(this);
        View close = addAction(row, "Закрыть", this::closePanel);
        p.body.addView(row);
        showPanel(p, close); // закрывает прошлую панель — сервер запускаем уже после
        String pin = HaSetupServer.start(this, () -> {
            Toast.makeText(this, "Home Assistant настроен ✓", Toast.LENGTH_LONG).show();
            closePanel();
            haCorner.refresh();
        });
        addr.setText(pin == null ? "Не удалось открыть страницу настройки" : "http://" + HaSetupServer.ip() + ":" + HaSetupServer.PORT + "/" + pin);
    }

    private void openSettings(String action) {
        try {
            startActivity(new Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            try { startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
            catch (Exception ignored) { }
        }
    }

    // ---------- перемещение плитки ----------

    private void startMove(String pkg) {
        closePanel();
        moving = pkg;
        moveHint.setVisibility(View.VISIBLE);
        tip.setVisibility(View.GONE);
        updBar.setVisibility(View.GONE);
        View t = findTile(pkg);
        if (t != null) {
            t.requestFocus();
            t.setForeground(Ui.round(0, dp(14), dp(3), 0xFFFFFFFF));
        }
    }

    private void endMove() {
        View t = findTile(moving);
        if (t != null) t.setForeground(null);
        moving = null;
        moveHint.setVisibility(View.GONE);
        updateTip();
    }

    private boolean moveBy(int delta) {
        List<String> pk = apps.dockPackages();
        // работаем по реальному порядку дока (без удалённых приложений)
        List<String> d = new ArrayList<>();
        for (Apps.App a : dock) d.add(a.pkg);
        int i = d.indexOf(moving);
        int j = Math.max(0, Math.min(d.size() - 1, i + delta));
        if (i < 0 || j == i) return true;
        d.remove(i);
        d.add(j, moving);
        for (String p : pk) if (!d.contains(p)) d.add(p); // сохранить и временно недоступные
        apps.saveDock(d);
        dock = apps.dock(allApps);
        String m = moving;
        rebuildGrid(m, false);
        View t = findTile(m);
        if (t != null) t.setForeground(Ui.round(0, dp(14), dp(3), 0xFFFFFFFF));
        return true;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getAction() == KeyEvent.ACTION_DOWN && moving != null && panel == null) {
            switch (e.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_LEFT: return moveBy(-1);
                case KeyEvent.KEYCODE_DPAD_RIGHT: return moveBy(1);
                case KeyEvent.KEYCODE_DPAD_UP: return moveBy(-COLS);
                case KeyEvent.KEYCODE_DPAD_DOWN: return moveBy(COLS);
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    endMove();
                    return true;
                default:
                    break;
            }
        }
        if (e.getAction() == KeyEvent.ACTION_DOWN && panel == null && moving == null) {
            int k = e.getKeyCode();
            if (k == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || k == KeyEvent.KEYCODE_MEDIA_NEXT
                    || k == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD || k == KeyEvent.KEYCODE_MEDIA_PLAY) {
                nextPhoto();
                return true;
            }
            if (k == KeyEvent.KEYCODE_DPAD_RIGHT) { // «вправо» с последней плитки строки — следующее фото
                View f = getCurrentFocus();
                int idx = f == null ? -1 : tileIndex(f);
                if (idx >= 0 && (idx % COLS == COLS - 1 || idx == Math.max(1, dock.size()) - 1)) {
                    nextPhoto();
                    return true;
                }
            }
            if (k == KeyEvent.KEYCODE_MENU) {
                View f = getCurrentFocus();
                int idx = f == null ? -1 : tileIndex(f);
                openLibrary(idx >= 0 && idx < dock.size() ? dock.get(idx) : null);
                return true;
            }
        }
        return super.dispatchKeyEvent(e);
    }

    private void nextPhoto() {
        if (wall.isSky()) return; // живой фон — фото нет
        wall.next();
        Toast.makeText(this, "Следующее фото", Toast.LENGTH_SHORT).show();
    }

    // ---------- панели ----------

    private void showPanel(FrameLayout p, View first) {
        closePanel();
        beforePanel = getCurrentFocus();
        panel = p;
        content.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        p.setAlpha(0f);
        root.addView(p, match());
        p.animate().alpha(1f).setDuration(180).start();
        glow.hide();
        if (first != null) first.requestFocus();
        else p.requestFocus();
    }

    private void closePanel() {
        HaSetupServer.stop();
        Transfer.stop();
        if (panel == null) return;
        final View o = panel;
        panel = null;
        content.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        o.animate().alpha(0f).setDuration(150).setListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator a) { root.removeView(o); }
        }).start();
        View back = beforePanel;
        beforePanel = null;
        if (back != null && back.isAttachedToWindow() && back.getParent() != null) back.requestFocus();
        else focusFirstTile();
    }

    private static String plural(int n, String one, String few, String many) {
        int m10 = n % 10, m100 = n % 100;
        if (m10 == 1 && m100 != 11) return n + " " + one;
        if (m10 >= 2 && m10 <= 4 && (m100 < 12 || m100 > 14)) return n + " " + few;
        return n + " " + many;
    }

    // --- библиотека приложений (долгое нажатие ОК / кнопка меню)

    private void openLibrary(Apps.App ctx) {
        getSharedPreferences("ui", MODE_PRIVATE).edit().putBoolean("lib_used", true).apply();
        updateTip();
        Panel p = new Panel(this, "Библиотека приложений", "ОК — добавить на главный  ·  удерживать — открыть");
        List<Apps.App> rest = new ArrayList<>();
        for (Apps.App a : allApps) {
            boolean in = false;
            for (Apps.App d : dock) if (d.pkg.equals(a.pkg)) { in = true; break; }
            if (!in) rest.add(a);
        }
        p.count.setText(plural(rest.size(), "приложение", "приложения", "приложений"));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        View firstAction = null;
        if (ctx != null) {
            firstAction = addAction(actions, "Переместить «" + ctx.label + "»", () -> startMove(ctx.pkg));
            addAction(actions, "Убрать с главного", () -> {
                List<String> d = apps.dockPackages();
                d.remove(ctx.pkg);
                apps.saveDock(d);
                closePanel();
                reloadApps();
            });
        }
        if (!wall.isSky()) {
            View next = addAction(actions, "Следующее фото", () -> { closePanel(); nextPhoto(); });
            if (firstAction == null) firstAction = next;
        }
        if (wall.hasPhoto()) {
            addAction(actions, "Скрыть это фото навсегда", () -> {
                closePanel();
                wall.hideCurrent();
                Toast.makeText(this, "Это фото больше не появится", Toast.LENGTH_SHORT).show();
            });
        }
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        al.bottomMargin = dp(14);
        p.body.addView(actions, al);

        View firstCard = null;
        if (rest.isEmpty()) {
            TextView t = Ui.text(this, 16, 400, 0xB3FFFFFF);
            t.setText("Все приложения уже на главном экране");
            t.setPadding(0, dp(30), 0, dp(30));
            p.body.addView(t);
        } else {
            LinearLayout strip = p.strip();
            for (Apps.App a : rest) {
                View card = libraryCard(a, strip, p);
                if (firstCard == null) firstCard = card;
            }
        }
        showPanel(p, firstCard != null ? firstCard : firstAction);
    }

    private View addAction(LinearLayout row, String text, Runnable r) {
        TextView t = Panel.action(this, text, r);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (row.getChildCount() > 0) lp.leftMargin = dp(10);
        row.addView(t, lp);
        return t;
    }

    private View libraryCard(Apps.App a, LinearLayout strip, Panel p) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(dp(10), dp(16), dp(10), dp(12));
        Panel.cardStyle(card, 22);
        ImageView ic = new ImageView(this);
        ic.setImageDrawable(a.icon);
        card.addView(ic, new LinearLayout.LayoutParams(dp(60), dp(60)));
        TextView name = Ui.text(this, 15, 500, 0xFFFFFFFF);
        name.setShadowLayer(0, 0, 0, 0);
        name.setText(a.label);
        name.setGravity(Gravity.CENTER);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nl.topMargin = dp(12);
        card.addView(name, nl);
        TextView sub = Ui.text(this, 12, 400, 0xB3FFFFFF);
        sub.setShadowLayer(0, 0, 0, 0);
        sub.setText("Добавить на главный");
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sl.topMargin = dp(6);
        card.addView(sub, sl);

        card.setOnClickListener(v -> {
            List<String> d = apps.dockPackages();
            if (!d.contains(a.pkg)) d.add(a.pkg);
            apps.saveDock(d);
            dock = apps.dock(allApps);
            rebuildGrid(null, true);
            int i = strip.indexOfChild(v);
            View nextFocus = strip.getChildCount() > 1
                    ? strip.getChildAt(i + 1 < strip.getChildCount() ? i + 1 : i - 1) : null;
            strip.removeView(v);
            p.count.setText(plural(strip.getChildCount(), "приложение", "приложения", "приложений"));
            Toast.makeText(this, "«" + a.label + "» на главном экране", Toast.LENGTH_SHORT).show();
            if (nextFocus != null) nextFocus.requestFocus();
            else closePanel();
        });
        card.setOnLongClickListener(v -> { closePanel(); launch(a); return true; });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(150), dp(176));
        if (strip.getChildCount() > 0) lp.leftMargin = dp(14);
        strip.addView(card, lp);
        return card;
    }

    // --- входы

    /** Выпадающий список входов под значком «Входы»: HDMI · AV · ТВ, разделённые тонкими линиями. */
    private void openInputs() {
        List<TvInputs.In> list = TvInputs.list(this);
        boolean reliable = TvInputs.stateReliable(list);

        FrameLayout overlay = new FrameLayout(this);
        overlay.setBackgroundColor(0x33000000);
        overlay.setClickable(true);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(6), dp(6), dp(6), dp(6));
        card.setBackground(Ui.round(0xF21C1C1F, dp(20), dp(1), 0x26FFFFFF));
        card.setElevation(dp(20));

        View first = null;
        int lastGroup = -1;
        if (list.isEmpty()) {
            first = menuRow(card, Icon.TV, "Открыть ТВ", null, () -> {
                closePanel();
                try { startActivity(new Intent(Intent.ACTION_MAIN).setComponent(TvInputs.TCL_TV).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
                catch (Exception ignored) { }
            });
        }
        for (TvInputs.In in : list) {
            int group = in.type == android.media.tv.TvInputInfo.TYPE_HDMI ? 0
                    : in.type == android.media.tv.TvInputInfo.TYPE_TUNER ? 2 : 1;
            if (lastGroup >= 0 && group != lastGroup) {
                View sep = new View(this);
                sep.setBackgroundColor(0x26FFFFFF);
                LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1) / 2));
                sl.setMargins(dp(14), dp(6), dp(14), dp(6));
                card.addView(sep, sl);
            }
            lastGroup = group;
            String sub = in.device != null ? in.device
                    : in.port != null ? in.port
                    : reliable && in.connected && group == 0 ? "Подключено" : null;
            View row = menuRow(card, group == 0 ? Icon.INPUT : group == 1 ? Icon.AV : Icon.TV,
                    group == 2 ? "Телевидение" : in.label, sub, () -> {
                        closePanel();
                        if (!TvInputs.open(this, in)) Toast.makeText(this, "Не удалось переключить вход", Toast.LENGTH_SHORT).show();
                    });
            if (first == null) first = row;
        }

        // позиция: под значком «Входы», но не ниже края экрана
        int[] a = new int[2], r = new int[2];
        inputsChip.getLocationInWindow(a);
        root.getLocationInWindow(r);
        int w = dp(320);
        card.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED);
        int h = card.getMeasuredHeight();
        int top = a[1] - r[1] + inputsChip.getHeight() + dp(8);
        top = Math.max(dp(16), Math.min(top, root.getHeight() - h - dp(16)));
        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
        cl.leftMargin = a[0] - r[0];
        cl.topMargin = top;
        overlay.addView(card, cl);
        card.setPivotX(0);
        card.setPivotY(0);
        card.setScaleX(0.96f);
        card.setScaleY(0.96f);
        card.animate().scaleX(1f).scaleY(1f).setDuration(160).setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
        showPanel(overlay, first);
    }

    /** Строка меню: значок, название, серая подпись справа; в фокусе — белая плашка. */
    private View menuRow(LinearLayout parent, int iconKind, String title, String sub, Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), 0, dp(14), 0);
        row.setFocusable(true);
        Icon ic = new Icon(iconKind, 0xFFFFFFFF);
        ImageView iv = new ImageView(this);
        iv.setImageDrawable(ic);
        row.addView(iv, new LinearLayout.LayoutParams(dp(18), dp(18)));
        TextView t = Ui.text(this, 15, 500, 0xFFFFFFFF);
        t.setShadowLayer(0, 0, 0, 0);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setText(title);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tl.leftMargin = dp(14);
        row.addView(t, tl);
        TextView st = null;
        if (sub != null) {
            st = Ui.text(this, 11, 400, 0x80FFFFFF);
            st.setShadowLayer(0, 0, 0, 0);
            st.setSingleLine(true);
            st.setText(sub);
            LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            sl.leftMargin = dp(10);
            row.addView(st, sl);
        }
        final TextView subView = st;
        row.setOnFocusChangeListener((v, has) -> {
            v.setBackground(has ? Ui.round(0xFFF5F5F7, dp(14), 0, 0) : null);
            ic.setColor(has ? 0xFF111111 : 0xFFFFFFFF);
            t.setTextColor(has ? 0xFF111111 : 0xFFFFFFFF);
            if (subView != null) subView.setTextColor(has ? 0x99111111 : 0x80FFFFFF);
        });
        row.setOnClickListener(v -> action.run());
        parent.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)));
        return row;
    }

    // --- погода

    private void openWeather() {
        Weather w = WallpaperView.lastWeather;
        Panel p = new Panel(this, "Погода · " + Loc.name(this), null);
        if (w == null) {
            p.count.setText("");
            TextView t = Ui.text(this, 16, 400, 0xB3FFFFFF);
            t.setText("Погода ещё загружается — попробуйте через минуту");
            p.body.addView(t);
            LinearLayout row = new LinearLayout(this);
            View ok = addAction(row, "Закрыть", this::closePanel);
            p.body.addView(row);
            showPanel(p, ok);
            return;
        }
        p.count.setText("met.no · " + new SimpleDateFormat("HH:mm", Locale.US).format(w.fetchedAt));
        TextView now = Ui.text(this, 18, 400, 0xFFFFFFFF);
        now.setShadowLayer(0, 0, 0, 0);
        String h = w.hint();
        now.setText("Сейчас " + w.describe() + (h.isEmpty() ? "" : "  ·  " + h));
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nl.topMargin = dp(10);
        nl.bottomMargin = dp(6);
        p.body.addView(now, nl);

        View first = null;
        if (w.hours.isEmpty()) {
            TextView t = Ui.text(this, 15, 400, 0xB3FFFFFF);
            t.setText("Подробный прогноз сейчас недоступен");
            p.body.addView(t);
            LinearLayout row = new LinearLayout(this);
            first = addAction(row, "Закрыть", this::closePanel);
            p.body.addView(row);
        } else {
            SimpleDateFormat hm = new SimpleDateFormat("HH:mm", Locale.US);
            LinearLayout hours = p.strip();
            int n = 0;
            for (Weather.Hour hr : w.hours) {
                if (hr.time < System.currentTimeMillis() - 3600_000L) continue;
                if (n++ >= 12) break;
                LinearLayout c = smallCard(hm.format(hr.time), Weather.emoji(hr.code, hr.night), Weather.temp(hr.temp),
                        hr.precip >= 0.1 ? String.format(Locale.US, "%.1f мм", hr.precip).replace('.', ',') : " ");
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(62), dp(112));
                if (hours.getChildCount() > 0) lp.leftMargin = dp(6);
                hours.addView(c, lp);
                if (first == null) first = c;
            }
            TextView wk = Ui.text(this, 14, 500, 0xB3FFFFFF);
            wk.setShadowLayer(0, 0, 0, 0);
            wk.setText("НА НЕДЕЛЮ");
            wk.setLetterSpacing(0.15f);
            LinearLayout.LayoutParams kl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            kl.topMargin = dp(6);
            p.body.addView(wk, kl);
            LinearLayout days = p.strip();
            Calendar cal = Calendar.getInstance();
            int today = cal.get(Calendar.DAY_OF_YEAR);
            SimpleDateFormat df = new SimpleDateFormat("EE, d", new Locale("ru"));
            int d = 0;
            for (Weather.Day day : w.days) {
                if (d++ >= 7) break;
                cal.setTimeInMillis(day.date);
                int doy = cal.get(Calendar.DAY_OF_YEAR);
                String name = doy == today ? "Сегодня" : doy == today + 1 ? "Завтра" : df.format(day.date);
                name = Character.toUpperCase(name.charAt(0)) + name.substring(1);
                LinearLayout c = smallCard(name, Weather.emoji(day.code < 0 ? 3 : day.code, false),
                        Weather.temp(day.max) + " / " + Weather.temp(day.min),
                        Math.round(day.wind) + " м/с" + (day.precip >= 0.5 ? " · " + Math.round(day.precip) + " мм" : ""));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(104), dp(124));
                if (days.getChildCount() > 0) lp.leftMargin = dp(8);
                days.addView(c, lp);
            }
        }
        showPanel(p, first);
    }

    private LinearLayout smallCard(String top, String emoji, String main, String bottom) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        c.setPadding(dp(4), dp(10), dp(4), dp(8));
        Panel.cardStyle(c, 18);
        String[] texts = {top, emoji, main, bottom};
        float[] sizes = {13, 24, 16, 11};
        int[] weights = {500, 400, 500, 400};
        int[] colors = {0xCCFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0xA6FFFFFF};
        for (int i = 0; i < 4; i++) {
            TextView t = Ui.text(this, sizes[i], weights[i], colors[i]);
            t.setShadowLayer(0, 0, 0, 0);
            t.setText(texts[i]);
            t.setSingleLine(true);
            t.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) lp.topMargin = dp(i == 1 ? 6 : 6);
            c.addView(t, lp);
        }
        return c;
    }

    // ---------- сеть ----------

    private void watchNetwork() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            netCb = new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network n) {
                    runOnUiThread(() -> { updateNet(); if (wall != null) wall.refreshWeather(); });
                }
                @Override public void onLost(Network n) { runOnUiThread(HomeActivity.this::updateNet); }
                @Override public void onCapabilitiesChanged(Network n, NetworkCapabilities c) { runOnUiThread(HomeActivity.this::updateNet); }
            };
            cm.registerDefaultNetworkCallback(netCb);
        } catch (Exception e) {
            Net.log("net callback", e);
        }
    }

    private void updateNet() {
        String label = "Нет сети";
        int kind = Icon.NO_NET;
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            Network n = cm.getActiveNetwork();
            NetworkCapabilities c = n == null ? null : cm.getNetworkCapabilities(n);
            if (c != null && c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                label = "Ethernet";
                kind = Icon.ETHERNET;
            } else if (c != null && c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                label = "Wi‑Fi";
                kind = Icon.WIFI;
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
                WifiInfo wi = wm == null ? null : wm.getConnectionInfo();
                String ssid = wi == null ? null : wi.getSSID();
                if (ssid != null && !ssid.contains("unknown ssid") && ssid.length() > 2) {
                    label = ssid.replaceAll("^\"|\"$", "");
                }
            }
        } catch (Exception e) {
            Net.log("net state", e);
        }
        if (netLabel != null) {
            netLabel.setText(label.length() > 18 ? label.substring(0, 17) + "…" : label);
            View chip = (View) netLabel.getParent();
            ImageView iv = (ImageView) ((ViewGroup) chip).getChildAt(0);
            iv.setImageDrawable(new Icon(kind, chip.hasFocus() ? 0xFF111111 : 0xFFFFFFFF));
        }
    }
}
