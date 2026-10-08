package com.baru.player;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Server HTTP mini di 127.0.0.1 untuk menyajikan master .m3u8 buatan sendiri
 * (HLS video+audio terpisah) ke aplikasi pemutar eksternal.
 * Pengganti `python -m http.server` di baru.sh.
 */
public final class LocalServer {

    public static final int PORT = 8765;

    private static LocalServer instance;

    private final File root;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private ServerSocket server;

    private LocalServer(File root) {
        this.root = root;
    }

    public static synchronized boolean ensureStarted(File root) {
        if (instance != null && instance.server != null && !instance.server.isClosed()) {
            return true;
        }
        try {
            LocalServer s = new LocalServer(root);
            s.start();
            instance = s;
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private void start() throws IOException {
        server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT));
        Thread t = new Thread(() -> {
            while (!server.isClosed()) {
                try {
                    final Socket c = server.accept();
                    pool.execute(() -> handle(c));
                } catch (IOException e) {
                    break;
                }
            }
        }, "local-server");
        t.setDaemon(true);
        t.start();
    }

    private void handle(Socket client) {
        try (Socket s = client) {
            s.setSoTimeout(10000);
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII));
            String line = in.readLine();
            String l;
            while ((l = in.readLine()) != null && !l.isEmpty()) { /* buang header */ }
            if (line == null) return;

            OutputStream out = s.getOutputStream();
            String[] p = line.split(" ");
            boolean head = p.length > 0 && p[0].equals("HEAD");
            if (p.length < 2 || !(head || p[0].equals("GET"))) {
                respond(out, 405, "Method Not Allowed", "text/plain", null, false);
                return;
            }
            String path = p[1];
            int q = path.indexOf('?');
            if (q >= 0) path = path.substring(0, q);
            String name = path.startsWith("/") ? path.substring(1) : path;

            if (name.isEmpty() || name.contains("/") || name.contains("\\") || name.contains("..")) {
                respond(out, 404, "Not Found", "text/plain", null, head);
                return;
            }
            File f = new File(root, name);
            if (!f.isFile()) {
                respond(out, 404, "Not Found", "text/plain", null, head);
                return;
            }
            respond(out, 200, "OK", mime(name), readAll(f), head);
        } catch (IOException ignored) {
            // klien menutup koneksi
        }
    }

    private static String mime(String name) {
        String n = name.toLowerCase();
        if (n.endsWith(".m3u8")) return "application/vnd.apple.mpegurl";
        if (n.endsWith(".m3u")) return "audio/x-mpegurl";
        return "application/octet-stream";
    }

    private static byte[] readAll(File f) throws IOException {
        try (InputStream is = new FileInputStream(f)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toByteArray();
        }
    }

    private static void respond(OutputStream out, int code, String msg, String type,
                                byte[] body, boolean head) throws IOException {
        int len = body == null ? 0 : body.length;
        String h = "HTTP/1.1 " + code + " " + msg + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + len + "\r\n"
                + "Access-Control-Allow-Origin: *\r\n"
                + "Connection: close\r\n\r\n";
        out.write(h.getBytes(StandardCharsets.US_ASCII));
        if (body != null && !head) out.write(body);
        out.flush();
    }
}
