package ru.alex.tvhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

/**
 * Данные от Home Assistant. Приходят только через adb (в манифесте receiver закрыт разрешением DUMP,
 * которое есть у shell и нет у обычных приложений), так что подделать их из сети нельзя, а токен HA
 * в телевизоре не хранится.
 *
 *   am broadcast -n ru.alex.tvhome/.HaReceiver -a ru.alex.tvhome.HA_STATUS --es data bed:22.7,41,436/liv:25.6,37/...
 *   am broadcast -n ru.alex.tvhome/.HaReceiver -a ru.alex.tvhome.HA_NOTIFY --es kind doorbell [--es title ... --es text ...]
 */
public class HaReceiver extends BroadcastReceiver {
    static final String STATUS = "ru.alex.tvhome.HA_STATUS";
    static final String NOTIFY = "ru.alex.tvhome.HA_NOTIFY";

    interface Listener { void onHaStatus(); }
    static volatile Listener listener;

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("ha", Context.MODE_PRIVATE); }

    @Override
    public void onReceive(Context ctx, Intent i) {
        String a = i.getAction();
        android.util.Log.i(Net.TAG, "ha " + a + " " + i.getExtras());
        if (STATUS.equals(a)) {
            String data = i.getStringExtra("data");
            if (data == null) return;
            prefs(ctx).edit().putString("data", data).putLong("at", System.currentTimeMillis()).apply();
            Listener l = listener;
            if (l != null) new Handler(Looper.getMainLooper()).post(l::onHaStatus);
        } else if (NOTIFY.equals(a)) {
            String kind = i.getStringExtra("kind");
            Overlay.show(ctx.getApplicationContext(), kind == null ? "info" : kind,
                    i.getStringExtra("title"), i.getStringExtra("text"));
        }
    }
}
