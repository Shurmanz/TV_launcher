package ru.alex.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/**
 * Правый верхний угол: климат по комнатам из Home Assistant.
 * Строка данных: «bed:22.7,41,436/liv:25.6,37/kit:24.0,48/bal:11.5,72» (temp,hum[,co2]; «-» — нет данных).
 */
final class HaCorner extends LinearLayout {
    private static final String[][] ROOMS = {
            {"bed", "Спальня"}, {"liv", "Комната"}, {"kit", "Кухня"}, {"bal", "Балкон"},
    };
    private static final long STALE_MS = 30 * 60_000L;

    HaCorner(Context c) {
        super(c);
        setOrientation(VERTICAL);
        setGravity(Gravity.END);
    }

    void refresh() {
        removeAllViews();
        SharedPreferences p = HaReceiver.prefs(getContext());
        String data = p.getString("data", null);
        long at = p.getLong("at", 0);
        if (data == null) { setVisibility(GONE); return; }
        setVisibility(VISIBLE);
        setAlpha(System.currentTimeMillis() - at > STALE_MS ? 0.45f : 1f); // давно не обновлялось — приглушаем
        // ключи bed/liv/kit/bal — от старой автоматизации HA; иначе ключ — уже название комнаты
        java.util.Map<String, String> labels = new java.util.HashMap<>();
        for (String[] r : ROOMS) labels.put(r[0], r[1]);
        for (String part : data.split("/")) {
            int i = part.indexOf(':');
            if (i <= 0) continue;
            String key = part.substring(0, i);
            String label = labels.containsKey(key) ? labels.get(key) : key;
            addView(row(label, part.substring(i + 1).split(",")));
        }
    }

    private TextView row(String name, String[] v) {
        TextView t = Ui.text(getContext(), 15, 400, 0xFFFFFFFF);
        Ui.strongShadow(t);
        t.setGravity(Gravity.END);
        SpannableStringBuilder sb = new SpannableStringBuilder();
        int s0 = sb.length();
        sb.append(name.toUpperCase(new Locale("ru"))).append("   ");
        sb.setSpan(new ForegroundColorSpan(0xB3FFFFFF), s0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.append(temp(v.length > 0 ? v[0] : "-"));
        sb.append("  ·  ").append(num(v.length > 1 ? v[1] : "-", 0)).append('%');
        if (v.length > 2) {
            sb.append("  ·  CO₂ ");
            int c0 = sb.length();
            String co2 = num(v[2], 0);
            sb.append(co2);
            int color = co2Color(v[2]);
            if (color != 0) sb.setSpan(new ForegroundColorSpan(color), c0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        LayoutParams lp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.END;
        lp.topMargin = getChildCount() == 0 ? 0 : Ui.dp(getContext(), 6);
        t.setLayoutParams(lp);
        t.setText(sb);
        t.setLetterSpacing(0.02f);
        return t;
    }

    private static String temp(String s) {
        try {
            double d = Double.parseDouble(s);
            return String.format(new Locale("ru"), "%s%.1f°", d > 0 ? "+" : "", d).replace('.', ',');
        } catch (Exception e) { return "—"; }
    }

    private static String num(String s, int dec) {
        try { return String.valueOf(Math.round(Double.parseDouble(s))); }
        catch (Exception e) { return "—"; }
    }

    private static int co2Color(String s) {
        try {
            double d = Double.parseDouble(s);
            if (d >= 1400) return 0xFFFF6B5E;
            if (d >= 1000) return 0xFFFFD60A;
        } catch (Exception ignored) { }
        return 0;
    }
}
