package ru.alex.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.Uri;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Входы телевизора: HDMI (с состоянием подключения и именем CEC-устройства), AV, эфирное ТВ. */
final class TvInputs {
    static final class In {
        String id, label, device;
        int type;
        boolean connected;
    }

    /** Приложение ТВ у TCL; переключение входа — его VIEW с адресом passthrough-входа. */
    static final ComponentName TCL_TV = new ComponentName("com.tcl.tv", "com.tcl.tv.TVActivity");

    private TvInputs() { }

    static List<In> list(Context ctx) {
        List<In> res = new ArrayList<>();
        TvInputManager tm = (TvInputManager) ctx.getSystemService(Context.TV_INPUT_SERVICE);
        if (tm == null) return res;
        List<TvInputInfo> all;
        try { all = tm.getTvInputList(); } catch (Exception e) { Net.log("inputs", e); return res; }
        Map<String, String> childName = new HashMap<>();
        for (TvInputInfo i : all) {
            if (i.getParentId() != null) {
                CharSequence l = i.loadLabel(ctx);
                if (l != null) childName.put(i.getParentId(), l.toString());
            }
        }
        List<In> hdmi = new ArrayList<>(), av = new ArrayList<>(), tv = new ArrayList<>();
        for (TvInputInfo i : all) {
            if (i.getParentId() != null) continue;
            int t = i.getType();
            boolean pass = i.isPassthroughInput();
            List<In> bucket;
            if (t == TvInputInfo.TYPE_HDMI) bucket = hdmi;
            else if (pass && (t == TvInputInfo.TYPE_COMPOSITE || t == TvInputInfo.TYPE_COMPONENT
                    || t == TvInputInfo.TYPE_SVIDEO || t == TvInputInfo.TYPE_SCART || t == TvInputInfo.TYPE_VGA)) bucket = av;
            else if (t == TvInputInfo.TYPE_TUNER && i.getServiceInfo().packageName.equals("com.tcl.tvinput")) bucket = tv;
            else continue; // каналы-превью приложений (VK, VLC) — не входы
            In in = new In();
            in.id = i.getId();
            in.type = t;
            CharSequence custom = i.loadCustomLabel(ctx);
            CharSequence lab = custom != null && custom.length() > 0 ? custom : i.loadLabel(ctx);
            in.label = lab == null ? "Вход" : lab.toString();
            if (t == TvInputInfo.TYPE_TUNER) in.label = "ТВ";
            int st;
            try { st = tm.getInputState(in.id); } catch (Exception e) { st = TvInputManager.INPUT_STATE_CONNECTED; }
            in.connected = st != TvInputManager.INPUT_STATE_DISCONNECTED;
            in.device = childName.get(in.id);
            bucket.add(in);
        }
        hdmi.sort((a, b) -> a.label.compareToIgnoreCase(b.label));
        res.addAll(hdmi);
        res.addAll(av);
        res.addAll(tv);
        return res;
    }

    /** Переключиться на вход. Возвращает false, если ни один способ не сработал. */
    static boolean open(Context ctx, In in) {
        Uri uri = in.type == TvInputInfo.TYPE_TUNER
                ? TvContract.buildChannelsUriForInput(in.id)
                : TvContract.buildChannelUriForPassthroughInput(in.id);
        Intent[] tries = {
                new Intent(Intent.ACTION_VIEW, uri).setComponent(TCL_TV),
                new Intent(Intent.ACTION_VIEW, uri),
                new Intent(Intent.ACTION_MAIN).setComponent(TCL_TV),
        };
        for (Intent i : tries) {
            try {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
                android.util.Log.i(Net.TAG, "input " + in.id + " via " + i);
                return true;
            } catch (Exception e) {
                Net.log("input try " + i, e);
            }
        }
        return false;
    }
}
