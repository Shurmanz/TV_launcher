package ru.alex.tvhome;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class HomeActivity extends Activity {
    private static final long WALL_INTERVAL = 60_000L;

    private Apps apps;
    private FrameLayout root;
    private WallpaperView wall;
    private TextView focusLabel;
    private LinearLayout dockRow;
    private FrameLayout overlay;
    private boolean resumed;

    private List<Apps.App> allApps = new ArrayList<>();

    // ---------- жизненный цикл ----------

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        apps = new Apps(this);
        BootReceiver.ensureService(this);
        buildUi();
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        registerReceiver(pkgReceiver, f);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        reloadApps();
        wall.start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        wall.stop();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        setIntent(intent);
        super.onNewIntent(intent);
        closeOverlay();
        focusFirstDock();
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(pkgReceiver);
        wall.release();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (overlay != null) closeOverlay();
        // на домашнем экране «Назад» ничего не делает
    }

    private final BroadcastReceiver pkgReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { if (resumed) reloadApps(); }
    };

    // ---------- разметка ----------

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private void buildUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0B0F14);

        wall = new WallpaperView(this, WALL_INTERVAL);
        root.addView(wall, matchParent());

        // затемнение снизу под док и сверху справа под часы
        View bottomScrim = new View(this);
        bottomScrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xCC000000, 0x66000000, 0x00000000}));
        FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(300), Gravity.BOTTOM);
        root.addView(bottomScrim, bl);

        View topScrim = new View(this);
        // радиальное затемнение: к краям прозрачность ровно 0, без видимой «рамки»
        GradientDrawable tg = new GradientDrawable();
        tg.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        tg.setColors(new int[]{0x8C000000, 0x33000000, 0x00000000});
        tg.setGradientCenter(1f, 0f);
        tg.setGradientRadius(dp(400));
        topScrim.setBackground(tg);
        root.addView(topScrim, new FrameLayout.LayoutParams(dp(640), dp(420), Gravity.TOP | Gravity.END));

        // часы, дата, погода
        ClockView clockBox = new ClockView(this, 64, Gravity.END);
        wall.setListener(clockBox);
        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        cl.setMargins(0, dp(36), dp(56), 0);
        root.addView(clockBox, cl);

        // подпись под фокусом и док
        focusLabel = text(18, Typeface.create("sans-serif-medium", Typeface.NORMAL), 0xFFFFFFFF);
        focusLabel.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout.LayoutParams fl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        fl.bottomMargin = dp(40);
        root.addView(focusLabel, fl);

        dockRow = new LinearLayout(this);
        dockRow.setOrientation(LinearLayout.HORIZONTAL);
        dockRow.setGravity(Gravity.CENTER);
        dockRow.setClipChildren(false);
        dockRow.setClipToPadding(false);
        dockRow.setPadding(dp(24), dp(16), dp(24), dp(16));
        FrameLayout.LayoutParams dl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        dl.bottomMargin = dp(76);
        root.addView(dockRow, dl);
        root.setClipChildren(false);

        setContentView(root);
    }

    private FrameLayout.LayoutParams matchParent() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private TextView text(float sp, Typeface tf, int color) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(tf);
        t.setTextColor(color);
        t.setShadowLayer(dp(6), 0, dp(1), 0x99000000);
        t.setIncludeFontPadding(false);
        return t;
    }

    // ---------- плитки ----------

    private View tile(Apps.App app, int w, int h, boolean inDock) {
        FrameLayout card = new FrameLayout(this);
        GradientDrawable bgd = new GradientDrawable();
        bgd.setCornerRadius(dp(14));
        bgd.setColor(0x33FFFFFF);
        card.setBackground(bgd);
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(14)); }
        });
        card.setClipToOutline(true);
        card.setFocusable(true);
        card.setFocusableInTouchMode(true);
        card.setElevation(dp(2));

        ImageView img = new ImageView(this);
        if (app.banner != null) {
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            img.setImageDrawable(app.banner);
            card.addView(img, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } else {
            img.setScaleType(ImageView.ScaleType.FIT_CENTER);
            img.setImageDrawable(app.icon);
            FrameLayout.LayoutParams il = new FrameLayout.LayoutParams(Math.round(h * 0.5f), Math.round(h * 0.5f), Gravity.CENTER);
            il.topMargin = -Math.round(h * 0.08f);
            card.addView(img, il);
            TextView name = text(13, Typeface.create("sans-serif-medium", Typeface.NORMAL), 0xFFFFFFFF);
            name.setText(app.label);
            name.setSingleLine(true);
            name.setGravity(Gravity.CENTER);
            FrameLayout.LayoutParams nl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
            nl.bottomMargin = dp(8);
            card.addView(name, nl);
        }

        card.setOnFocusChangeListener((v, has) -> {
            float s = has ? 1.12f : 1f;
            v.animate().scaleX(s).scaleY(s).translationZ(has ? dp(18) : 0)
                    .setInterpolator(new DecelerateInterpolator()).setDuration(160).start();
            if (has && inDock) focusLabel.setText(app.label);
            else if (inDock && !dockHasFocus()) focusLabel.setText("");
        });
        card.setOnClickListener(v -> launch(app));
        card.setOnLongClickListener(v -> { editMenu(app, inDock); return true; });
        card.setTag(app.pkg);
        card.setLayoutParams(new ViewGroup.LayoutParams(w, h));
        return card;
    }

    private boolean dockHasFocus() {
        View f = getCurrentFocus();
        return f != null && f.getParent() == dockRow;
    }

    private View allAppsTile(int w, int h) {
        FrameLayout card = new FrameLayout(this);
        GradientDrawable bgd = new GradientDrawable();
        bgd.setCornerRadius(dp(14));
        bgd.setColor(0x40FFFFFF);
        bgd.setStroke(dp(1), 0x40FFFFFF);
        card.setBackground(bgd);
        card.setFocusable(true);
        card.setFocusableInTouchMode(true);
        TextView dots = text(30, Typeface.create("sans-serif-light", Typeface.NORMAL), 0xFFFFFFFF);
        dots.setText("⋯");
        dots.setGravity(Gravity.CENTER);
        card.addView(dots, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        card.setOnFocusChangeListener((v, has) -> {
            float s = has ? 1.12f : 1f;
            v.animate().scaleX(s).scaleY(s).translationZ(has ? dp(18) : 0).setDuration(160).start();
            if (has) focusLabel.setText("Все приложения");
        });
        card.setOnClickListener(v -> openOverlay());
        card.setLayoutParams(new ViewGroup.LayoutParams(w, h));
        return card;
    }

    private void reloadApps() {
        String focusedPkg = null;
        View f = getCurrentFocus();
        if (f != null && f.getTag() instanceof String) focusedPkg = (String) f.getTag();

        allApps = apps.all();
        dockRow.removeAllViews();
        List<Apps.App> dock = apps.dock(allApps);
        int gap = dp(24);
        int avail = getResources().getDisplayMetrics().widthPixels - dp(120);
        float units = dock.size() + 0.56f;
        int w = Math.min(dp(176), Math.round((avail - (dock.size() + 1) * gap) / units));
        int h = Math.round(w * 9f / 16f);
        for (Apps.App a : dock) {
            View t = tile(a, w, h, true);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
            lp.setMargins(gap / 2, 0, gap / 2, 0);
            dockRow.addView(t, lp);
        }
        int mw = Math.round(w * 0.56f);
        View more = allAppsTile(mw, h);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(mw, h);
        lp.setMargins(gap / 2, 0, gap / 2, 0);
        dockRow.addView(more, lp);

        if (overlay == null) {
            View target = null;
            for (int i = 0; i < dockRow.getChildCount(); i++) {
                View c = dockRow.getChildAt(i);
                if (focusedPkg != null && focusedPkg.equals(c.getTag())) target = c;
            }
            if (target == null) target = dockRow.getChildAt(0);
            target.requestFocus();
        }
    }

    private void focusFirstDock() {
        if (dockRow.getChildCount() > 0) dockRow.getChildAt(0).requestFocus();
    }

    private void launch(Apps.App app) {
        try {
            startActivity(app.launch);
        } catch (Exception e) {
            Toast.makeText(this, "Не удалось открыть " + app.label, Toast.LENGTH_SHORT).show();
        }
    }

    // ---------- все приложения ----------

    private void openOverlay() {
        if (overlay != null) return;
        overlay = new FrameLayout(this);
        overlay.setBackgroundColor(0xE60B0F14);
        overlay.setAlpha(0f);

        ScrollView sv = new ScrollView(this);
        sv.setClipChildren(false);
        sv.setClipToPadding(false);
        sv.setPadding(dp(80), dp(40), dp(80), dp(40));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setClipChildren(false);

        TextView title = text(28, Typeface.create("sans-serif-medium", Typeface.NORMAL), 0xFFFFFFFF);
        title.setText("Приложения");
        col.addView(title);
        TextView hint = text(14, Typeface.DEFAULT, 0x99FFFFFF);
        hint.setText("Удерживайте ОК, чтобы добавить приложение в док");
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hl.topMargin = dp(6);
        hl.bottomMargin = dp(24);
        col.addView(hint, hl);

        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(5);
        grid.setClipChildren(false);
        int w = dp(176), h = dp(99), m = dp(12);
        for (Apps.App a : allApps) {
            View t = tile(a, w, h, false);
            GridLayout.LayoutParams gl = new GridLayout.LayoutParams();
            gl.width = w;
            gl.height = h;
            gl.setMargins(m, m, m, m);
            grid.addView(t, gl);
        }
        col.addView(grid);
        sv.addView(col);
        overlay.addView(sv, matchParent());
        root.addView(overlay, matchParent());
        overlay.animate().alpha(1f).setDuration(180).start();
        if (grid.getChildCount() > 0) grid.getChildAt(0).requestFocus();
    }

    private void closeOverlay() {
        if (overlay == null) return;
        final View o = overlay;
        overlay = null;
        o.animate().alpha(0f).setDuration(150).setListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator a) { root.removeView(o); }
        }).start();
        focusFirstDock();
    }

    // ---------- правка дока ----------

    private void editMenu(Apps.App app, boolean inDock) {
        List<String> dock = apps.dockPackages();
        int pos = dock.indexOf(app.pkg);
        List<String> items = new ArrayList<>();
        List<Runnable> acts = new ArrayList<>();
        if (pos < 0) {
            items.add("Добавить в док");
            acts.add(() -> { dock.add(app.pkg); apps.saveDock(dock); });
        } else {
            if (pos > 0) {
                items.add("Сдвинуть влево");
                acts.add(() -> { dock.remove(pos); dock.add(pos - 1, app.pkg); apps.saveDock(dock); });
            }
            if (pos < dock.size() - 1) {
                items.add("Сдвинуть вправо");
                acts.add(() -> { dock.remove(pos); dock.add(pos + 1, app.pkg); apps.saveDock(dock); });
            }
            items.add("Убрать из дока");
            acts.add(() -> { dock.remove(pos); apps.saveDock(dock); });
        }
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(app.label)
                .setItems(items.toArray(new String[0]), (d, which) -> {
                    acts.get(which).run();
                    if (overlay != null && !inDock) closeOverlay();
                    reloadApps();
                    for (int i = 0; i < dockRow.getChildCount(); i++) {
                        if (app.pkg.equals(dockRow.getChildAt(i).getTag())) dockRow.getChildAt(i).requestFocus();
                    }
                })
                .show();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            View f = getCurrentFocus();
            if (f != null) f.performLongClick();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}
