package ru.alex.tvhome;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Всплывающая карточка события поверх всего экрана — и поверх фильма. Окно не берёт фокус и касания:
 * пульт продолжает управлять тем, что открыто. Нужно разрешение «поверх других приложений»
 * (appops SYSTEM_ALERT_WINDOW allow, выдаётся через adb).
 */
final class Overlay {
    private static final Handler ui = new Handler(Looper.getMainLooper());
    private static View current;
    private static Runnable hideTask;

    private Overlay() { }

    private static final class Kind {
        final String emoji, title, text;
        final int accent, seconds;
        Kind(String emoji, String title, String text, int accent, int seconds) {
            this.emoji = emoji; this.title = title; this.text = text; this.accent = accent; this.seconds = seconds;
        }
    }

    private static Kind kind(String k) {
        switch (k) {
            case "doorbell": return new Kind("🔔", "Звонок в домофон", "Кто-то звонит в дверь подъезда", 0xFF5AA9FF, 20);
            case "leak":     return new Kind("💧", "Протечка на кухне!", "Датчик протечки обнаружил воду", 0xFFFF453A, 45);
            case "balcony":  return new Kind("🚪", "Балкон открыт", "Дверь открыта больше 5 минут, а климат работает", 0xFFFF9F0A, 15);
            case "vac_done": return new Kind("✅", "Уборка закончена", "Пылесос вернулся на базу", 0xFF30D158, 10);
            case "vac_err":  return new Kind("⚠️", "Пылесосу нужна помощь", "Робот остановился с ошибкой", 0xFFFF9F0A, 20);
            default:         return new Kind("ℹ️", "Home Assistant", "", 0xFF8A9BB5, 12);
        }
    }

    static void show(Context ctx, String k, String title, String text) {
        ui.post(() -> doShow(ctx, k, title, text));
    }

    private static void doShow(Context ctx, String k, String title, String text) {
        if (!Settings.canDrawOverlays(ctx)) {
            android.util.Log.w(Net.TAG, "overlay: нет разрешения SYSTEM_ALERT_WINDOW");
            return;
        }
        Fonts.init(ctx);
        Kind kd = kind(k);
        WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        hideNow(wm);

        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        int p = Ui.dp(ctx, 16);
        card.setPadding(p, Ui.dp(ctx, 14), Ui.dp(ctx, 22), Ui.dp(ctx, 14));
        card.setBackground(Ui.round(0xF21C1C1F, Ui.dp(ctx, 20), Ui.dp(ctx, 2), kd.accent));

        TextView em = new TextView(ctx);
        em.setText(kd.emoji);
        em.setTextSize(26);
        em.setGravity(Gravity.CENTER);
        em.setBackground(Ui.round((kd.accent & 0x00FFFFFF) | 0x33000000, Ui.dp(ctx, 100), 0, 0));
        card.addView(em, new LinearLayout.LayoutParams(Ui.dp(ctx, 48), Ui.dp(ctx, 48)));

        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t = Ui.text(ctx, 18, 600, 0xFFFFFFFF);
        t.setShadowLayer(0, 0, 0, 0);
        t.setText(title != null && !title.isEmpty() ? title : kd.title);
        col.addView(t);
        String body = text != null && !text.isEmpty() ? text : kd.text;
        if (!body.isEmpty()) {
            TextView s = Ui.text(ctx, 14, 400, 0xB3FFFFFF);
            s.setShadowLayer(0, 0, 0, 0);
            s.setText(body);
            LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            sl.topMargin = Ui.dp(ctx, 4);
            col.addView(s, sl);
        }
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cl.leftMargin = Ui.dp(ctx, 14);
        card.addView(col, cl);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.END;
        lp.x = Ui.dp(ctx, 40);
        lp.y = Ui.dp(ctx, 36);
        try {
            wm.addView(card, lp);
        } catch (Exception e) {
            Net.log("overlay add", e);
            return;
        }
        current = card;
        card.setAlpha(0f);
        card.setTranslationX(Ui.dp(ctx, 60));
        card.animate().alpha(1f).translationX(0).setDuration(260).setInterpolator(new DecelerateInterpolator()).start();
        hideTask = () -> {
            if (current != card) return;
            card.animate().alpha(0f).translationX(Ui.dp(ctx, 60)).setDuration(220)
                    .withEndAction(() -> hideNow(wm)).start();
        };
        ui.postDelayed(hideTask, kd.seconds * 1000L);
    }

    private static void hideNow(WindowManager wm) {
        if (hideTask != null) ui.removeCallbacks(hideTask);
        hideTask = null;
        if (current != null) {
            try { wm.removeView(current); } catch (Exception ignored) { }
            current = null;
        }
    }
}
