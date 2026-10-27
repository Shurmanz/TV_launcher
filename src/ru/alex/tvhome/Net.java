package ru.alex.tvhome;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

final class Net {
    static final String TAG = "TvHome";

    static void log(String what, Throwable t) {
        android.util.Log.w(TAG, what + ": " + t, t);
    }

    // Wikimedia требует осмысленный User-Agent
    static final String UA = "TvHomeLauncher/1.0 (personal Android TV launcher)";

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", UA);
        c.setInstanceFollowRedirects(true);
        int code = c.getResponseCode();
        if (code != 200) {
            c.disconnect();
            throw new Exception("HTTP " + code + " " + url);
        }
        return c;
    }

    /** GET с токеном Home Assistant (Authorization: Bearer). */
    static String getTextAuth(String url, String token) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(20000);
        c.setRequestProperty("Authorization", "Bearer " + token);
        c.setRequestProperty("Content-Type", "application/json");
        int code = c.getResponseCode();
        if (code != 200) { c.disconnect(); throw new Exception("HTTP " + code); }
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b = new byte[16384];
            int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            return new String(bo.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            c.disconnect();
        }
    }

    static String getText(String url) throws Exception {
        HttpURLConnection c = open(url);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b = new byte[16384];
            int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            return new String(bo.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            c.disconnect();
        }
    }

    static void download(String url, File dst) throws Exception {
        File tmp = new File(dst.getPath() + ".part");
        HttpURLConnection c = open(url);
        try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(tmp)) {
            byte[] b = new byte[32768];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
        } finally {
            c.disconnect();
        }
        if (!tmp.renameTo(dst)) throw new Exception("rename failed");
    }
}
