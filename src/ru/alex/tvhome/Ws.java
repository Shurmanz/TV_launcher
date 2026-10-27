package ru.alex.tvhome;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

import javax.net.ssl.SSLSocketFactory;

/** Минимальный WebSocket-клиент (RFC 6455) для API Home Assistant: текстовые сообщения, ping/pong. */
final class Ws implements AutoCloseable {
    private final Socket sock;
    private final DataInputStream in;
    private final OutputStream out;
    private final SecureRandom rnd = new SecureRandom();

    Ws(String httpUrl, String path) throws IOException {
        URI u = URI.create(httpUrl);
        boolean tls = "https".equalsIgnoreCase(u.getScheme());
        int port = u.getPort() > 0 ? u.getPort() : (tls ? 443 : 80);
        Socket s = tls ? SSLSocketFactory.getDefault().createSocket() : new Socket();
        s.connect(new InetSocketAddress(u.getHost(), port), 10_000);
        s.setSoTimeout(0);
        s.setKeepAlive(true);
        sock = s;
        in = new DataInputStream(s.getInputStream());
        out = s.getOutputStream();
        byte[] key = new byte[16];
        rnd.nextBytes(key);
        String req = "GET " + path + " HTTP/1.1\r\nHost: " + u.getHost() + ":" + port + "\r\nUpgrade: websocket\r\n"
                + "Connection: Upgrade\r\nSec-WebSocket-Key: " + android.util.Base64.encodeToString(key, android.util.Base64.NO_WRAP)
                + "\r\nSec-WebSocket-Version: 13\r\n\r\n";
        out.write(req.getBytes(StandardCharsets.US_ASCII));
        out.flush();
        String status = readLine(in);
        if (!status.contains(" 101")) throw new IOException("handshake: " + status);
        while (!readLine(in).isEmpty()) { /* заголовки ответа не нужны */ }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
        }
        if (c == -1 && b.size() == 0) throw new IOException("closed");
        return b.toString("UTF-8");
    }

    synchronized void send(String text) throws IOException { frame(0x1, text.getBytes(StandardCharsets.UTF_8)); }

    private synchronized void frame(int opcode, byte[] data) throws IOException {
        ByteArrayOutputStream f = new ByteArrayOutputStream();
        f.write(0x80 | opcode);
        int n = data.length;
        if (n < 126) f.write(0x80 | n);
        else if (n < 65536) { f.write(0x80 | 126); f.write(n >> 8); f.write(n); }
        else { f.write(0x80 | 127); for (int i = 7; i >= 0; i--) f.write((int) ((long) n >> (8 * i))); }
        byte[] mask = new byte[4];
        rnd.nextBytes(mask);
        f.write(mask);
        for (int i = 0; i < n; i++) f.write(data[i] ^ mask[i & 3]);
        out.write(f.toByteArray());
        out.flush();
    }

    /** Следующее текстовое сообщение (склеивает фрагменты, отвечает на ping). */
    String receive() throws IOException {
        ByteArrayOutputStream msg = new ByteArrayOutputStream();
        while (true) {
            int b0 = in.readUnsignedByte(), b1 = in.readUnsignedByte();
            boolean fin = (b0 & 0x80) != 0;
            int op = b0 & 0x0F;
            long len = b1 & 0x7F;
            if (len == 126) len = in.readUnsignedShort();
            else if (len == 127) len = in.readLong();
            byte[] mask = null;
            if ((b1 & 0x80) != 0) { mask = new byte[4]; in.readFully(mask); }
            if (len > 32 * 1024 * 1024) throw new IOException("frame too big");
            byte[] data = new byte[(int) len];
            in.readFully(data);
            if (mask != null) for (int i = 0; i < data.length; i++) data[i] ^= mask[i & 3];
            if (op == 0x8) throw new IOException("closed by server");
            if (op == 0x9) { frame(0xA, data); continue; }
            if (op == 0xA) continue;
            msg.write(data);
            if (fin) return msg.toString("UTF-8");
        }
    }

    @Override public void close() {
        try { sock.close(); } catch (IOException ignored) { }
    }
}
