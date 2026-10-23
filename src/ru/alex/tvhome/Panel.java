package ru.alex.tvhome;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Матовая панель поверх главного экрана: заголовок, счётчик справа, подсказка и содержимое. */
final class Panel extends FrameLayout {
    final LinearLayout body;
    final TextView title, count, hint;

    Panel(Context c, String titleText, String hintText) {
        super(c);
        setBackgroundColor(0x99000000);
        setClickable(true); // клики не проходят на экран под панелью

        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Ui.round(0xF21E1E21, Ui.dp(c, 28), Ui.dp(c, 1), 0x33FFFFFF));
        card.setPadding(Ui.dp(c, 32), Ui.dp(c, 26), Ui.dp(c, 32), Ui.dp(c, 28));
        card.setClipChildren(false);
        card.setClipToPadding(false);
        card.setElevation(Ui.dp(c, 24));

        FrameLayout head = new FrameLayout(c);
        title = Ui.text(c, 24, 500, 0xFFFFFFFF);
        title.setText(titleText);
        head.addView(title, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.START | Gravity.CENTER_VERTICAL));
        count = Ui.text(c, 14, 500, 0xB3FFFFFF);
        head.addView(count, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.END | Gravity.CENTER_VERTICAL));
        card.addView(head);

        hint = Ui.text(c, 14, 400, 0xB3FFFFFF);
        hint.setText(hintText);
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hl.topMargin = Ui.dp(c, 8);
        hl.bottomMargin = Ui.dp(c, 18);
        card.addView(hint, hl);
        if (hintText == null || hintText.isEmpty()) hint.setVisibility(GONE);

        body = new LinearLayout(c);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setClipChildren(false);
        body.setClipToPadding(false);
        card.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LayoutParams cl = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_VERTICAL);
        cl.setMargins(Ui.dp(c, 56), Ui.dp(c, 40), Ui.dp(c, 56), Ui.dp(c, 40));
        addView(card, cl);
    }

    /** Горизонтальная лента карточек (прокрутка стрелками). */
    LinearLayout strip() {
        Context c = getContext();
        HorizontalScrollView hs = new HorizontalScrollView(c);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setClipChildren(false);
        hs.setClipToPadding(false);
        hs.setPadding(Ui.dp(c, 6), Ui.dp(c, 10), Ui.dp(c, 6), Ui.dp(c, 10));
        hs.setFadingEdgeLength(Ui.dp(c, 40));
        hs.setHorizontalFadingEdgeEnabled(true);
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);
        hs.addView(row);
        body.addView(hs, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    /** Карточка в стиле панели: в фокусе светлее, с белой рамкой и чуть крупнее. */
    static void cardStyle(View v, float radiusDp) {
        Context c = v.getContext();
        float r = Ui.dp(c, radiusDp);
        v.setBackground(Ui.round(0x14FFFFFF, r, Ui.dp(c, 1), 0x24FFFFFF));
        v.setFocusable(true);
        v.setOnFocusChangeListener((x, has) -> {
            x.setBackground(has ? Ui.round(0x40FFFFFF, r, Ui.dp(c, 2), 0xE6FFFFFF)
                    : Ui.round(0x14FFFFFF, r, Ui.dp(c, 1), 0x24FFFFFF));
            Ui.focusScale(x, has, 1.05f);
        });
    }

    /** Кнопка-«таблетка» для ряда действий. */
    static TextView action(Context c, String text, Runnable r) {
        TextView t = Ui.text(c, 15, 500, 0xFFFFFFFF);
        t.setShadowLayer(0, 0, 0, 0);
        t.setText(text);
        t.setPadding(Ui.dp(c, 18), Ui.dp(c, 10), Ui.dp(c, 18), Ui.dp(c, 10));
        t.setFocusable(true);
        Ui.pill(t, false);
        t.setOnFocusChangeListener((v, has) -> {
            Ui.pill(v, has);
            t.setTextColor(has ? 0xFF111111 : 0xFFFFFFFF);
        });
        t.setOnClickListener(v -> r.run());
        return t;
    }
}
