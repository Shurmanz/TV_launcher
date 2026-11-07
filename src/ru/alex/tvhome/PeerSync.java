package ru.alex.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/**
 * Общий список «Слежу» между телевизорами дома — напрямую по Wi‑Fi, без облака и без HA.
 * Каждый Slauncher держит крошечный HTTP на порту 8098 (только GET/POST /follow) и объявляет себя в сети (NSD, _slauncher._tcp).
 * При обновлении ищем соседей пару секунд, забираем их список и берём более новый; свой новый — отправляем им.
 * Спящий телевизор не отвечает — подтянет список сам, когда проснётся.
 */
final class PeerSync {
    static final int PORT = 8098;
    private static final String TYPE = "_slauncher._tcp.";
    private static ServerSocket server;
    private static boolean registered;

    private PeerSync() { }

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("peers", Context.MODE_PRIVATE); }

    /** Поднять сервер и объявить себя в сети (повторный вызов ничего не делает). */
    static synchronized void start(Context ctx) {
        Context c = ctx.getApplicationContext();
        if (server == null) {
            try {
                ServerSocket ss = new ServerSocket();
                ss.setReuseAddress(true);
                ss.bind(new InetSocketAddress(PORT));
                server = ss;
                new Thread(() -> {
                    while (!ss.isClosed()) {
                        try (Socket s = ss.accept()) {
                            s.setSoTimeout(8000);
                            handle(c, s);
                        } catch (Exception e) {
                            if (!ss.isClosed()) Net.log("peer req", e);
                        }
                    }
                }, "peer-sync").start();
            } catch (Exception e) {
                Net.log("peer server", e);
            }
        }
        if (!registered && server != null) {
            try {
                NsdServiceInfo si = new NsdServiceInfo();
                si.setServiceName("Slauncher " + android.os.Build.MODEL);
                si.setServiceType(TYPE);
                si.setPort(PORT);
                NsdManager nm = (NsdManager) c.getSystemService(Context.NSD_SERVICE);
                nm.registerService(si, NsdManager.PROTOCOL_DNS_SD, new NsdManager.RegistrationListener() {
                    @Override public void onServiceRegistered(NsdServiceInfo i) { Log.i(Net.TAG, "peer: объявлен как " + i.getServiceName()); }
                    @Override public void onRegistrationFailed(NsdServiceInfo i, int err) { registered = false; }
                    @Override public void onServiceUnregistered(NsdServiceInfo i) { }
                    @Override public void onUnregistrationFailed(NsdServiceInfo i, int err) { }
                });
                registered = true;
            } catch (Exception e) {
                Net.log("peer nsd", e);
            }
        }
    }

    private static void handle(Context c, Socket s) throws Exception {
        BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
        String line = r.readLine();
        if (line == null) return;
        int len = 0;
        String h;
        while ((h = r.readLine()) != null && !h.isEmpty()) {
            if (h.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:")) len = Integer.parseInt(h.substring(15).trim());
        }
        OutputStream out = s.getOutputStream();
        if (line.startsWith("GET /follow")) {
            reply(out, 200, Shows.followDoc(c).toString());
        } else if (line.startsWith("POST /follow") && len > 0 && len < 512_000) {
            char[] buf = new char[len];
            int got = 0, n;
            while (got < len && (n = r.read(buf, got, len - got)) > 0) got += n;
            boolean took = Shows.adopt(c, new JSONObject(new String(buf, 0, got)));
            rememberPeer(c, s.getInetAddress().getHostAddress());
            reply(out, 200, took ? "{\"ok\":true}" : "{\"ok\":false}");
        } else {
            reply(out, 404, "{}");
        }
    }

    private static void reply(OutputStream out, int code, String body) throws Exception {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        out.write(("HTTP/1.1 " + code + " OK\r\nContent-Type: application/json\r\nContent-Length: " + b.length
                + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(b);
        out.flush();
    }

    private static void rememberPeer(Context c, String ip) {
        if (ip == null || ip.equals(HaSetupServer.ip())) return;
        Set<String> s = new HashSet<>(prefs(c).getStringSet("ips", new HashSet<>()));
        if (s.add(ip)) prefs(c).edit().putStringSet("ips", s).apply();
    }

    /** Соседи: запомненные адреса + найденные в сети за ~3 с. Вызывать не из UI-потока. */
    private static Set<String> peers(Context c) {
        Set<String> found = new HashSet<>(prefs(c).getStringSet("ips", new HashSet<>()));
        NsdManager nm = (NsdManager) c.getSystemService(Context.NSD_SERVICE);
        java.util.List<NsdServiceInfo> seen = new java.util.ArrayList<>();
        NsdManager.DiscoveryListener dl = new NsdManager.DiscoveryListener() {
            @Override public void onStartDiscoveryFailed(String t, int e) { }
            @Override public void onStopDiscoveryFailed(String t, int e) { }
            @Override public void onDiscoveryStarted(String t) { }
            @Override public void onDiscoveryStopped(String t) { }
            @Override public void onServiceFound(NsdServiceInfo i) { synchronized (seen) { seen.add(i); } }
            @Override public void onServiceLost(NsdServiceInfo i) { }
        };
        try {
            nm.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, dl);
            Thread.sleep(3000);
            nm.stopServiceDiscovery(dl);
        } catch (Exception e) { Net.log("peer discover", e); }
        String me = HaSetupServer.ip();
        java.util.List<NsdServiceInfo> list;
        synchronized (seen) { list = new java.util.ArrayList<>(seen); }
        for (NsdServiceInfo i : list) {
            // resolve — по одному, ждём до 3 с
            final String[] ip = new String[1];
            final Object lock = new Object();
            try {
                nm.resolveService(i, new NsdManager.ResolveListener() {
                    @Override public void onResolveFailed(NsdServiceInfo x, int e) { synchronized (lock) { lock.notifyAll(); } }
                    @Override public void onServiceResolved(NsdServiceInfo x) {
                        if (x.getHost() != null) ip[0] = x.getHost().getHostAddress();
                        synchronized (lock) { lock.notifyAll(); }
                    }
                });
                synchronized (lock) { lock.wait(3000); }
            } catch (Exception e) { Net.log("peer resolve", e); }
            if (ip[0] != null && !ip[0].equals(me) && !ip[0].contains(":")) {
                found.add(ip[0]);
                rememberPeer(c, ip[0]);
            }
        }
        found.remove(me);
        return found;
    }

    /** Сверить «Слежу» с соседями: берём более новый, свой новый отправляем. */
    static void sync(Context c) {
        start(c);
        JSONObject mine = Shows.followDoc(c);
        Set<String> ps = peers(c);
        if (ps.isEmpty()) return;
        long best = mine.optLong("t");
        for (String ip : ps) {
            try {
                JSONObject theirs = new JSONObject(get("http://" + ip + ":" + PORT + "/follow"));
                if (theirs.optLong("t") > best && Shows.adopt(c, theirs)) best = theirs.optLong("t");
                else if (theirs.optLong("t") < mine.optLong("t")) post("http://" + ip + ":" + PORT + "/follow", mine.toString());
            } catch (Exception e) {
                Log.i(Net.TAG, "peer " + ip + ": нет ответа (" + e.getMessage() + ")");
            }
        }
    }

    /** Список изменили на этом телевизоре — сразу отправить соседям. */
    static void push(Context c, JSONObject doc) {
        start(c);
        for (String ip : peers(c)) {
            try { post("http://" + ip + ":" + PORT + "/follow", doc.toString()); Log.i(Net.TAG, "peer " + ip + ": «Слежу» отправлен"); }
            catch (Exception e) { Log.i(Net.TAG, "peer " + ip + ": нет ответа (" + e.getMessage() + ")"); }
        }
    }

    private static String get(String url) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setConnectTimeout(2500);
        h.setReadTimeout(4000);
        try (java.io.InputStream in = h.getInputStream()) {
            return new String(readAll(in), StandardCharsets.UTF_8);
        } finally { h.disconnect(); }
    }

    private static void post(String url, String body) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setConnectTimeout(2500);
        h.setReadTimeout(4000);
        h.setDoOutput(true);
        h.setRequestProperty("Content-Type", "application/json");
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        h.setFixedLengthStreamingMode(b.length);
        try (OutputStream o = h.getOutputStream()) { o.write(b); }
        try { h.getResponseCode(); } finally { h.disconnect(); }
    }

    private static byte[] readAll(java.io.InputStream in) throws Exception {
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) > 0) bo.write(b, 0, n);
        return bo.toByteArray();
    }
}
