package ru.alex.tvhome;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.provider.Settings;

/**
 * На некоторых TCL после перезагрузки сбрасывается accessibility_enabled — тогда «Домой» снова ведёт
 * на стандартный экран. Если при установке через adb выдано WRITE_SECURE_SETTINGS, включаем службу обратно.
 * Чужие службы из списка не трогаем — только дописываем свою.
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) // дошло — значит, автозапуск разрешён
            ctx.getSharedPreferences(SetupActivity.PREF, Context.MODE_PRIVATE).edit().putBoolean("tcl_autostart", true).apply();
        ensureService(ctx);
    }

    static void ensureService(Context ctx) {
        if (ctx.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") != PackageManager.PERMISSION_GRANTED) return;
        try {
            String me = new ComponentName(ctx, HomeKeyService.class).flattenToString();
            String list = Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            boolean present = false;
            if (list != null) for (String s : list.split(":")) if (s.equalsIgnoreCase(me)) present = true;
            if (!present) {
                String nl = (list == null || list.isEmpty() || list.equals("null")) ? me : list + ":" + me;
                Settings.Secure.putString(ctx.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, nl);
            }
            if (Settings.Secure.getInt(ctx.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 0) != 1) {
                Settings.Secure.putInt(ctx.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            }
        } catch (Exception ignored) { }
    }
}
