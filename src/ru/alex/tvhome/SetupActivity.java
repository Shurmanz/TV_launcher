package ru.alex.tvhome;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Мастер настройки Slauncher: список того, что нужно разрешить, со статусом ✓ / ! и кнопкой «Открыть».
 * Открывается при первом запуске (если что-то не включено) и из «Настройки Slauncher → Проверка разрешений».
 */
public class SetupActivity extends Activity {
    static final String PREF = "setup";

    private LinearLayout list;
    private View firstButton;

    private static final class Step {
        final String title, desc, adb;
        final boolean ok, important;
        final Runnable open;
        Step(String title, String desc, boolean ok, boolean important, Runnable open, String adb) {
            this.title = title; this.desc = desc; this.ok = ok; this.important = important; this.open = open; this.adb = adb;
        }
    }

    /** Есть ли важные невыполненные пункты (для показа при первом запуске). */
    static boolean needed(Activity a) {
        return !homeServiceOn(a) || !Settings.canDrawOverlays(a);
    }

    static boolean homeServiceOn(android.content.Context c) {
        String me = new ComponentName(c, HomeKeyService.class).flattenToString();
        String list = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (list == null) return false;
        for (String s : list.split(":")) if (s.equalsIgnoreCase(me)) return true;
        return false;
    }

    static boolean dreamOn(android.content.Context c) {
        String s = Settings.Secure.getString(c.getContentResolver(), "screensaver_components");
        return s != null && s.contains(c.getPackageName());
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Fonts.init(this);
        FrameLayout root = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF14171C, 0xFF0B0C0F});
        root.setBackground(bg);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int side = Ui.dp(this, 96);
        col.setPadding(side, Ui.dp(this, 30), side, Ui.dp(this, 20));

        TextView title = Ui.text(this, 26, 500, 0xFFFFFFFF);
        title.setText("Настройка Slauncher");
        col.addView(title);
        TextView sub = Ui.text(this, 15, 400, 0xB3FFFFFF);
        sub.setText("Включите то, что нужно. Любой пункт можно пропустить и вернуться позже: Настройки → Настройки Slauncher.");
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sl.topMargin = Ui.dp(this, 8);
        sl.bottomMargin = Ui.dp(this, 12);
        col.addView(sub, sl);

        ScrollView sv = new ScrollView(this);
        sv.setClipToPadding(false);
        sv.setVerticalScrollBarEnabled(false);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(Ui.dp(this, 6), Ui.dp(this, 4), Ui.dp(this, 6), Ui.dp(this, 4));
        list.setClipChildren(false);
        sv.addView(list);
        col.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setGravity(Gravity.END);
        TextView doneBtn = Panel.action(this, "Готово", () -> {
            getSharedPreferences(PREF, MODE_PRIVATE).edit().putBoolean("seen", true).apply();
            finish();
        });
        TextView autoBtn = Panel.action(this, "Включить всё автоматически", () -> {
            android.widget.Toast.makeText(this, Adb.known(this) ? "Настраиваю…"
                    : "Если появится «Разрешить отладку?» — отметьте «Всегда» и нажмите OK", android.widget.Toast.LENGTH_LONG).show();
            new Thread(() -> {
                String err = null;
                try { Adb.sh(this, "echo ok", 90_000); Optimizer.fixAll(this); }
                catch (Exception e) {
                    err = "сетевая отладка выключена".equals(e.getMessage())
                            ? "Сначала включите «Отладка по сети»: Настройки → Система → Для разработчиков"
                            : "Не получилось: " + e.getMessage();
                }
                final String fe = err;
                runOnUiThread(() -> {
                    android.widget.Toast.makeText(this, fe == null ? "Готово ✓" : fe, android.widget.Toast.LENGTH_LONG).show();
                    build();
                });
            }).start();
        });
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        al.rightMargin = Ui.dp(this, 12);
        bottom.addView(autoBtn, al);
        bottom.addView(doneBtn);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bl.topMargin = Ui.dp(this, 8);
        col.addView(bottom, bl);

        root.addView(col, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        build(); // после возврата из настроек статусы обновляются
    }

    private Step[] steps() {
        boolean tcl = Build.MANUFACTURER.toLowerCase().contains("tcl") || Build.BRAND.toLowerCase().contains("tcl");
        return new Step[]{
                new Step("Кнопка «Домой» открывает Slauncher",
                        "Специальные возможности → «Slauncher: кнопка «Домой»» → Включить.",
                        homeServiceOn(this), true, () -> open(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)),
                        "adb shell settings put secure enabled_accessibility_services " + getPackageName() + "/.HomeKeyService"),
                new Step("Уведомления поверх фильма",
                        "Показ поверх других приложений → Slauncher → Разрешить. Нужно для карточек домофона и протечки.",
                        Settings.canDrawOverlays(this), true,
                        () -> open(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()))),
                        "adb shell appops set " + getPackageName() + " SYSTEM_ALERT_WINDOW allow"),
                new Step("Заставка Slauncher",
                        "Заставка → «Slauncher: природа»: часы и погода на фото, пока телевизор простаивает.",
                        dreamOn(this), false, () -> open(new Intent(Settings.ACTION_DREAM_SETTINGS)),
                        "adb shell settings put secure screensaver_components " + getPackageName() + "/.NatureDream"),
                new Step("Имя Wi‑Fi сети",
                        "Разрешение на местоположение — без него Android не сообщает название сети.",
                        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED, false,
                        () -> requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 1),
                        "adb shell pm grant " + getPackageName() + " android.permission.ACCESS_FINE_LOCATION"),
                new Step("Установка обновлений",
                        "Разрешить Slauncher устанавливать приложения — для кнопки «Обновить».",
                        Updater.canInstall(this), false,
                        () -> open(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName()))),
                        "adb shell appops set " + getPackageName() + " REQUEST_INSTALL_PACKAGES allow"),
                tcl ? new Step("Автозапуск (TCL)",
                        "TCL не даёт сторонним приложениям запускаться после перезагрузки. Без этого после включения откроется Google TV. "
                                + "На экране телевизора такого переключателя нет — только командой:",
                        getSharedPreferences(PREF, MODE_PRIVATE).getBoolean("tcl_autostart", false), false, null,
                        "adb shell appops set " + getPackageName() + " APP_AUTO_START allow") : null,
        };
    }

    private void build() {
        list.removeAllViews();
        firstButton = null;
        View firstMissing = null;
        for (Step s : steps()) {
            if (s == null) continue;
            View row = row(s);
            list.addView(row);
            if (!s.ok && firstMissing == null) firstMissing = row.findViewWithTag("btn");
        }
        View f = firstMissing != null ? firstMissing : firstButton;
        if (f != null) f.requestFocus();
    }

    private View row(Step s) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(Ui.dp(this, 16), Ui.dp(this, 9), Ui.dp(this, 14), Ui.dp(this, 9));
        r.setBackground(Ui.round(0x14FFFFFF, Ui.dp(this, 18), Ui.dp(this, 1), 0x1FFFFFFF));

        TextView mark = Ui.text(this, 20, 600, s.ok ? 0xFF30D158 : (s.important ? 0xFFFF9F0A : 0x80FFFFFF));
        mark.setShadowLayer(0, 0, 0, 0);
        mark.setText(s.ok ? "✓" : (s.important ? "!" : "•"));
        mark.setGravity(Gravity.CENTER);
        r.addView(mark, new LinearLayout.LayoutParams(Ui.dp(this, 34), ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout txt = new LinearLayout(this);
        txt.setOrientation(LinearLayout.VERTICAL);
        TextView t = Ui.text(this, 16, 500, s.ok ? 0xCCFFFFFF : 0xFFFFFFFF);
        t.setShadowLayer(0, 0, 0, 0);
        t.setText(s.title + (s.ok ? "" : s.important ? "  — не включено" : "  — не включено (по желанию)"));
        txt.addView(t);
        TextView d = Ui.text(this, 12, 400, 0x99FFFFFF);
        d.setShadowLayer(0, 0, 0, 0);
        d.setText(s.desc);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dl.topMargin = Ui.dp(this, 2);
        if (!s.ok) txt.addView(d, dl); // включённые пункты — одной строкой
        if (!s.ok && s.adb != null) {
            TextView a = Ui.text(this, 11, 400, 0x66FFFFFF);
            a.setShadowLayer(0, 0, 0, 0);
            a.setTypeface(android.graphics.Typeface.MONOSPACE);
            a.setText((s.open == null ? "" : "Если экрана нет: ") + s.adb);
            LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            al.topMargin = Ui.dp(this, 2);
            txt.addView(a, al);
        }
        r.addView(txt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (!s.ok && s.open != null) {
            TextView btn = Panel.action(this, "Открыть", s.open);
            btn.setTag("btn");
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            bp.leftMargin = Ui.dp(this, 14);
            r.addView(btn, bp);
            if (firstButton == null) firstButton = btn;
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(this, 7);
        r.setLayoutParams(lp);
        return r;
    }

    private void open(Intent i) {
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            android.widget.Toast.makeText(this, "На этом телевизоре такого экрана нет — используйте команду ADB из подсказки",
                    android.widget.Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] p, int[] r) { build(); }
}
