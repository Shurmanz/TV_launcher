package ru.alex.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;

/**
 * На Google TV кнопка «Домой» всегда открывает штатный лаунчер (launcherx), и отключать его нельзя:
 * в нём живут результаты голосового поиска, профили и кнопки YouTube/Netflix на пульте.
 * Поэтому служба только следит за появлением штатного главного экрана и сразу открывает вместо него «Дом».
 *
 * Нажатия кнопок НЕ перехватываются (без FLAG_REQUEST_FILTER_KEY_EVENTS): на TCL фильтрация ломает
 * автоповтор стрелок. Содержимое окон не читается (canRetrieveWindowContent=false).
 */
public class HomeKeyService extends AccessibilityService {
    private static final String STOCK = "com.google.android.apps.tv.launcherx";
    /** Окна голосового поиска / Ассистента: после них launcherx показывает результаты — их не трогаем. */
    private static final String[] VOICE = {
            "com.google.android.katniss",
            "com.google.android.tv.assistant",
            "com.google.android.googlequicksearchbox",
    };
    private static final long VOICE_GRACE_MS = 90_000L;

    private long lastLaunch;
    private long lastVoice;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkgCs = e.getPackageName();
        if (pkgCs == null) return;
        String pkg = pkgCs.toString();
        String cls = e.getClassName() == null ? "" : e.getClassName().toString();
        long now = SystemClock.elapsedRealtime();

        for (String v : VOICE) {
            if (pkg.equals(v)) { lastVoice = now; return; }
        }
        if (!pkg.equals(STOCK)) {
            // ушли в любое другое приложение — «окно голосового поиска» закончилось
            if (!pkg.equals("android") && !pkg.startsWith("com.android.systemui")) lastVoice = 0;
            return;
        }
        if (cls.contains("LauncherDeepLink")) { lastVoice = now; return; } // переход из Ассистента
        if (!cls.contains("HomeActivity")) return;
        if (lastVoice != 0 && now - lastVoice < VOICE_GRACE_MS) return; // показываются результаты поиска
        openHome(now);
    }

    private void openHome(long now) {
        if (now - lastLaunch < 600) return;
        lastLaunch = now;
        Intent i = new Intent(this, HomeActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        startActivity(i);
    }

    @Override
    public void onInterrupt() { }
}
