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

    private Apps apps;
    private FrameLayout root, content;
    private WallpaperView wall;
    private View nightDim;
    private GlowView glow;
    private ClockView clock;
    private TextView weatherBtn, netLabel, moveHint;
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
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        reloadApps();
        wall.start();
        updateNet();
        HaReceiver.listener = () -> { if (haCorner != null) haCorner.refresh(); };
        haCorner.refresh();
        Updater.check(this, false, (avail, name) -> settingsIcon.setDot(avail));
    }

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
        settingsIcon = new Icon(Icon.GEAR, 0xFFFFFFFF);
        chips.addView(chip(settingsIcon, "Настройки", v -> openSettings(Settings.ACTION_SETTINGS),
                v -> { openLibrary(null); return true; }));
        LinearLayout.LayoutParams chl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        chl.topMargin = dp(12);
        left.addView(chips, chl);
        left.setClipChildren(false);

        // --- плитки: по 5 в строке, лишние — ниже, с прокруткой
        int sw = getResources().getDisplayMetrics().widthPixels;
        int side = dp(64), gap = dp(24);
        tileW = (sw - 2 * side - (COLS - 1) * gap) / COLS;
        tileH = Math.round(tileW * 9f / 16f);
        glow.setTile(tileW, tileH, dp(14), 1.1f);

        scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        scroll.setFillViewport(true);
        scroll.setSmoothScrollingEnabled(true);
        scroll.setVerticalFadingEdgeEnabled(true);
        scroll.setFadingEdgeLength(dp(30));
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

        setContentView(root);
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
        cell.addView(card, new LinearLayout.LayoutParams(tileW, tileH));

        TextView label = Ui.text(this, 16, 500, 0xFFFFFFFF);
        label.setText(app == null ? "Добавить приложение" : app.label);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setGravity(Gravity.CENTER);
        label.setAlpha(0f);
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(tileW + dp(20), ViewGroup.LayoutParams.WRAP_CONTENT);
        ll.topMargin = dp(16);
        cell.addView(label, ll);

        card.setTag(app == null ? "" : app.pkg);
        card.setOnFocusChangeListener((v, has) -> {
            Ui.focusScale(v, has, 1.1f);
            label.animate().alpha(has ? 1f : 0f).translationY(has ? dp(4) : 0).setDuration(170).start();
            if (has) onTileFocused(v, app, index);
        });
        card.setOnClickListener(v -> {
            if (moving != null) { endMove(); return; }
            if (app == null) { openLibrary(null); return; }
            glow.pulse();
            v.animate().scaleX(0.97f).scaleY(0.97f).setDuration(90).withEndAction(() ->
                    v.animate().scaleX(1.1f).scaleY(1.1f).setDuration(120).start()).start();
            v.postDelayed(() -> launch(app), 170);
        });
        card.setOnLongClickListener(v -> {
            if (moving == null) openLibrary(app);
            return true;
        });
        return cell;
    }

    private void onTileFocused(View card, Apps.App app, int index) {
        card.post(() -> {
            int[] a = new int[2], b = new int[2];
            card.getLocationInWindow(a);
            root.getLocationInWindow(b);
            float cx = a[0] - b[0] + card.getWidth() / 2f;
            float cy = a[1] - b[1] + card.getHeight() / 2f;
            glow.moveTo(cx, cy, app == null ? 0xFF8A9BB5 : accent(app));
        });
        // параллакс: фон чуть смещается вслед за фокусом
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
        View next = addAction(actions, "Следующее фото", () -> { closePanel(); nextPhoto(); });
        if (firstAction == null) firstAction = next;
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
        Panel p = new Panel(this, "Погода · Череповец", null);
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
