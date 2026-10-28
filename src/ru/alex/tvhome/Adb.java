package ru.alex.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAKeyGenParameterSpec;
import java.security.spec.X509EncodedKeySpec;

/**
 * Встроенный «Shizuku»: Slauncher подключается к сетевой отладке ADB этого же телевизора (127.0.0.1:5555)
 * и выполняет команды с правами оболочки. При первом подключении Android спрашивает
 * «Разрешить отладку?» — отметить «Всегда разрешать» и нажать OK. Дальше подключение тихое, и после перезагрузки тоже.
 */
final class Adb {
    private static final int CNXN = 0x4e584e43, AUTH = 0x48545541, OPEN = 0x4e45504f, OKAY = 0x59414b4f,
            CLSE = 0x45534c43, WRTE = 0x45545257;
    private static final int VERSION = 0x01000001, MAXDATA = 256 * 1024;
    private static final int AUTH_TOKEN = 1, AUTH_SIGNATURE = 2, AUTH_RSAPUBLICKEY = 3;
    static final int PORT = 5555;

    /** Состояние для экрана: что сейчас с доступом. */
    static volatile String status = "";
    static volatile boolean waitingConfirm;

    static final class NoAccess extends IOException { NoAccess(String m) { super(m); } }

    private Adb() { }

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("adb", Context.MODE_PRIVATE); }

    /** Подключались ли уже успешно (значит, разрешение на ТВ выдано). */
    static boolean known(Context c) { return prefs(c).getBoolean("ok", false); }

    // ---------- ключ ----------

    private static KeyPair keys(Context c) throws Exception {
        SharedPreferences p = prefs(c);
        KeyFactory kf = KeyFactory.getInstance("RSA");
        String pr = p.getString("priv", null), pu = p.getString("pub", null);
        if (pr != null && pu != null) {
            return new KeyPair(kf.generatePublic(new X509EncodedKeySpec(Base64.decode(pu, Base64.NO_WRAP))),
                    kf.generatePrivate(new PKCS8EncodedKeySpec(Base64.decode(pr, Base64.NO_WRAP))));
        }
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(new RSAKeyGenParameterSpec(2048, RSAKeyGenParameterSpec.F4));
        KeyPair kp = g.generateKeyPair();
        p.edit().putString("priv", Base64.encodeToString(kp.getPrivate().getEncoded(), Base64.NO_WRAP))
                .putString("pub", Base64.encodeToString(kp.getPublic().getEncoded(), Base64.NO_WRAP)).apply();
        return kp;
    }

    /** Подпись токена как RSA_sign(NID_sha1): PKCS#1 v1.5 с DigestInfo SHA-1, токен = «хэш». */
    private static byte[] sign(RSAPrivateKey k, byte[] token) {
        byte[] prefix = {0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14};
        int len = (k.getModulus().bitLength() + 7) / 8;
        byte[] em = new byte[len];
        em[0] = 0;
        em[1] = 1;
        int ps = len - 3 - prefix.length - token.length;
        for (int i = 0; i < ps; i++) em[2 + i] = (byte) 0xFF;
        em[2 + ps] = 0;
        System.arraycopy(prefix, 0, em, 3 + ps, prefix.length);
        System.arraycopy(token, 0, em, 3 + ps + prefix.length, token.length);
        BigInteger s = new BigInteger(1, em).modPow(k.getPrivateExponent(), k.getModulus());
        byte[] raw = s.toByteArray();
        byte[] out = new byte[len];
        int skip = raw.length > len ? raw.length - len : 0;
        System.arraycopy(raw, skip, out, len - (raw.length - skip), raw.length - skip);
        return out;
    }

    /** Открытый ключ в формате adb (mincrypt RSAPublicKey, little-endian) + base64 + имя. */
    private static byte[] adbPublicKey(RSAPublicKey k) {
        BigInteger n = k.getModulus();
        int words = 64;
        ByteBuffer b = ByteBuffer.allocate(4 + 4 + words * 4 * 2 + 4).order(ByteOrder.LITTLE_ENDIAN);
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        BigInteger n0inv = n.mod(r32).modInverse(r32);
        b.putInt(words);
        b.putInt(r32.subtract(n0inv).intValue());
        putWords(b, n, words);
        putWords(b, BigInteger.ONE.shiftLeft(words * 32).pow(2).mod(n), words);
        b.putInt(k.getPublicExponent().intValue());
        String s = Base64.encodeToString(b.array(), Base64.NO_WRAP) + " slauncher@tv\0";
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static void putWords(ByteBuffer b, BigInteger v, int words) {
        BigInteger mask = BigInteger.ONE.shiftLeft(32).subtract(BigInteger.ONE);
        for (int i = 0; i < words; i++) b.putInt(v.shiftRight(32 * i).and(mask).intValue());
    }

    // ---------- протокол ----------

    private static final class Msg {
        int cmd, a0, a1;
        byte[] data;
    }

    private final static class Conn implements AutoCloseable {
        final Socket s;
        final DataInputStream in;
        final OutputStream out;

        Conn(int timeoutMs) throws IOException {
            s = new Socket();
            try {
                s.connect(new InetSocketAddress("127.0.0.1", PORT), 3000);
            } catch (IOException e) {
                s.close();
                throw new NoAccess("сетевая отладка выключена");
            }
            s.setSoTimeout(timeoutMs);
            in = new DataInputStream(s.getInputStream());
            out = s.getOutputStream();
        }

        void send(int cmd, int a0, int a1, byte[] data) throws IOException {
            if (data == null) data = new byte[0];
            int sum = 0;
            for (byte x : data) sum += x & 0xFF;
            ByteBuffer h = ByteBuffer.allocate(24 + data.length).order(ByteOrder.LITTLE_ENDIAN);
            h.putInt(cmd).putInt(a0).putInt(a1).putInt(data.length).putInt(sum).putInt(~cmd).put(data);
            out.write(h.array());
            out.flush();
        }

        Msg read() throws IOException {
            byte[] hdr = new byte[24];
            in.readFully(hdr);
            ByteBuffer h = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN);
            Msg m = new Msg();
            m.cmd = h.getInt();
            m.a0 = h.getInt();
            m.a1 = h.getInt();
            int len = h.getInt();
            if (len < 0 || len > 1024 * 1024) throw new IOException("bad adb packet");
            m.data = new byte[len];
            in.readFully(m.data);
            return m;
        }

        @Override public void close() { try { s.close(); } catch (IOException ignored) { } }
    }

    /** Подключиться и пройти авторизацию. confirmWaitMs — сколько ждать нажатия «Разрешить» на ТВ. */
    private static Conn connect(Context c, int confirmWaitMs) throws Exception {
        KeyPair kp = keys(c.getApplicationContext());
        Conn conn = new Conn(8000);
        boolean ok = false;
        try {
            conn.send(CNXN, VERSION, MAXDATA, "host::\0".getBytes(StandardCharsets.UTF_8));
            boolean sentSig = false, sentKey = false;
            while (true) {
                Msg m = conn.read();
                if (m.cmd == CNXN) break;
                if (m.cmd != AUTH || m.a0 != AUTH_TOKEN) throw new IOException("adb: неожиданный ответ");
                if (!sentSig) {
                    conn.send(AUTH, AUTH_SIGNATURE, 0, sign((RSAPrivateKey) kp.getPrivate(), m.data));
                    sentSig = true;
                } else if (!sentKey) {
                    if (confirmWaitMs <= 0) throw new NoAccess("нужно разрешение на экране");
                    conn.send(AUTH, AUTH_RSAPUBLICKEY, 0, adbPublicKey((RSAPublicKey) kp.getPublic()));
                    sentKey = true;
                    waitingConfirm = true;
                    status = "подтвердите на экране: «Разрешить отладку» → «Всегда»";
                    conn.s.setSoTimeout(confirmWaitMs);
                } else {
                    throw new NoAccess("разрешение не выдано");
                }
            }
            waitingConfirm = false;
            conn.s.setSoTimeout(120_000);
            prefs(c).edit().putBoolean("ok", true).apply();
            status = "работает";
            ok = true;
            return conn;
        } catch (java.net.SocketTimeoutException e) {
            throw new NoAccess("не подтверждено на экране");
        } finally {
            waitingConfirm = false;
            if (!ok) conn.close();
        }
    }

    /** Выполнить команду оболочки и вернуть вывод. confirmWaitMs > 0 — можно показать запрос на ТВ. */
    static synchronized String sh(Context c, String cmd, int confirmWaitMs) throws Exception {
        try (Conn conn = connect(c, confirmWaitMs)) {
            int local = 1;
            conn.send(OPEN, local, 0, ("shell:" + cmd + "\0").getBytes(StandardCharsets.UTF_8));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int remote = 0;
            while (true) {
                Msg m;
                try { m = conn.read(); } catch (java.io.EOFException e) { break; }
                if (m.cmd == OKAY) { remote = m.a0; continue; }
                if (m.cmd == WRTE) {
                    remote = m.a0;
                    out.write(m.data);
                    conn.send(OKAY, local, remote, null);
                    continue;
                }
                if (m.cmd == CLSE) {
                    if (remote != 0) conn.send(CLSE, local, remote, null);
                    break;
                }
            }
            return out.toString("UTF-8").replace("\r", "");
        } catch (NoAccess e) {
            status = e.getMessage();
            throw e;
        } catch (Exception e) {
            if (status.equals("работает")) status = "ошибка: " + e.getMessage();
            throw e;
        }
    }

    static String sh(Context c, String cmd) throws Exception { return sh(c, cmd, 0); }

    /** Проверка доступа (без запроса на экране). */
    static boolean check(Context c) {
        try { return sh(c, "echo ok").trim().equals("ok"); } catch (Exception e) { return false; }
    }

    static String q(String s) { return "'" + s.replace("'", "'\\''") + "'"; }
}
