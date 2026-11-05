package ru.alex.tvhome;

import android.content.Context;
import android.text.TextUtils;
import android.widget.TextView;

/** Маленькая подпись «где снято» в углу экрана: плавно меняется вместе с фото. */
final class PhotoCaption extends TextView {
    private String shown;

    PhotoCaption(Context c) {
        super(c);
        float d = c.getResources().getDisplayMetrics().density;
        setTextSize(13);
        setTextColor(0xB3FFFFFF);
        Fonts.apply(this, 400);
        setIncludeFontPadding(false);
        setShadowLayer(6 * d, 0, d, 0xCC000000);
        setSingleLine(true);
        setEllipsize(TextUtils.TruncateAt.END);
        setMaxWidth(Math.round(520 * d));
        setAlpha(0f);
    }

    /** Новая подпись (null — убрать). */
    void show(String text) {
        if (TextUtils.equals(text, shown)) return;
        shown = text;
        animate().cancel();
        if (getAlpha() < 0.05f) { apply(); return; }
        animate().alpha(0f).setDuration(400).withEndAction(this::apply).start();
    }

    private void apply() {
        if (shown == null || shown.isEmpty()) { setText(""); setAlpha(0f); return; }
        setText("📍 " + shown);
        animate().alpha(1f).setDuration(900).start();
    }
}
