package ru.alex.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;

/**
 * Кнопка «Домой» открывает штатный лаунчер (Google TV launcherx, Android TV Home, PatchWall у Xiaomi…),
 * в нём живут результаты голосового поиска, профили и кнопки YouTube/Netflix на пульте.
 * Поэтому служба только следит за появлением штатного главного экрана и сразу открывает вместо него Slauncher.
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
        java.util.Set<String> homes = stockHomes(now).get(pkg);
        if (homes == null) {
            // ушли в любое другое приложение — «окно голосового поиска» закончилось
            if (!pkg.equals("android") && !pkg.startsWith("com.android.systemui")) lastVoice = 0;
            noteApp(pkg);
            return;
        }
        if (cls.contains("LauncherDeepLink")) { lastVoice = now; return; } // переход из Ассистента
        if (!homes.contains(cls) && !(pkg.equals(STOCK) && cls.contains("HomeActivity"))) return;
        if (lastVoice != 0 && now - lastVoice < VOICE_GRACE_MS) return; // показываются результаты поиска
        // второе «Домой» вскоре после первого — вернуться в прошлое приложение
        if (lastStock != 0 && now - lastStock > 250 && now - lastStock < 1500) {
            lastStock = 0;
            if (Comfort.open(this, Comfort.lastApp(this))) return;
        } else if (now - lastStock >= 250) lastStock = now;
        openHome(now);
    }

    private long lastStock;

    /** Запомнить приложение на экране (для двойного «Домой»): только то, что можно запустить, не мы и не системное. */
    private void noteApp(String pkg) {
        if (pkg.equals(getPackageName()) || pkg.equals("android") || pkg.startsWith("com.android.systemui")
                || pkg.equals("com.google.android.inputmethod.latin") || pkg.contains("inputmethod")) return;
        if (pkg.equals(Comfort.lastApp)) return;
        if (Comfort.launchIntent(this, pkg) != null) Comfort.noteApp(this, pkg);
    }

    /** Экран включился после дежурного режима — открыть стартовое приложение, если выбрано. */
    private long screenOffAt;
    private final android.content.BroadcastReceiver screen = new android.content.BroadcastReceiver() {
        @Override public void onReceive(android.content.Context c, Intent i) {
            if (Intent.ACTION_SCREEN_OFF.equals(i.getAction())) { screenOffAt = SystemClock.elapsedRealtime(); return; }
            if (screenOffAt != 0 && SystemClock.elapsedRealtime() - screenOffAt > 60_000L) openStart(1500);
            screenOffAt = 0;
        }
    };

    private void openStart(long delay) {
        String pkg = Comfort.startPkg(this);
        if (pkg.isEmpty()) return;
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> Comfort.open(this, pkg), delay);
    }

    /**
     * После перезагрузки штатный экран успевает открыться раньше, чем система подключит службу,
     * и события «окно сменилось» уже не будет. Поэтому в первые минуты после загрузки сразу открываем Slauncher.
     */
    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        HaClient.ensure(this); // служба живёт всегда — держит связь с HA и карточки событий поверх фильма
        if (SystemClock.elapsedRealtime() < 10 * 60_000L) {
            new android.os.Handler(android.os.Looper.getMainLooper())
                    .postDelayed(() -> openHome(SystemClock.elapsedRealtime()), 1500);
            openStart(3500); // после загрузки — любимое приложение поверх главного экрана
        }
        android.content.IntentFilter f = new android.content.IntentFilter(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_SCREEN_ON);
        try { registerReceiver(screen, f); } catch (Exception ignored) { }
    }

    /** Не наши «главные экраны»: пакет → классы их домашних активити. Google TV, Android TV Home, PatchWall Xiaomi и т.д. */
    private java.util.Map<String, java.util.Set<String>> homesCache;
    private long homesAt;
    private static final java.util.Set<String> NOT_HOMES = new java.util.HashSet<>(java.util.Arrays.asList(
            "com.android.tv.settings", "com.android.settings", "com.google.android.tungsten.setupwraith"));

    private java.util.Map<String, java.util.Set<String>> stockHomes(long now) {
        if (homesCache != null && now - homesAt < 5 * 60_000L) return homesCache;
        java.util.Map<String, java.util.Set<String>> m = new java.util.HashMap<>();
        try {
            Intent h = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            for (android.content.pm.ResolveInfo r : getPackageManager().queryIntentActivities(h, 0)) {
                String p = r.activityInfo.packageName;
                if (p.equals(getPackageName()) || NOT_HOMES.contains(p)) continue;
                java.util.Set<String> cs = m.get(p);
                if (cs == null) { cs = new java.util.HashSet<>(); m.put(p, cs); }
                cs.add(r.activityInfo.name);
                if (r.activityInfo.targetActivity != null) cs.add(r.activityInfo.targetActivity);
            }
        } catch (Exception ignored) { }
        if (!m.containsKey(STOCK)) m.put(STOCK, new java.util.HashSet<>());
        homesCache = m;
        homesAt = now;
        android.util.Log.i(Net.TAG, "stock homes: " + m);
        return m;
    }

    private void openHome(long now) {
        if (now - lastLaunch < 600) return;
        lastLaunch = now;
        Intent i = new Intent(this, HomeActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        i.putExtra("from_service", true); // двойное «Домой» в этом случае считает служба
        startActivity(i);
    }

    @Override
    public void onDestroy() {
        try { unregisterReceiver(screen); } catch (Exception ignored) { }
        super.onDestroy();
    }

    @Override
    public void onInterrupt() { }
}
