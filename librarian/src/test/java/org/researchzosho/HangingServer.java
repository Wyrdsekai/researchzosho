package org.researchzosho;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * A model server that takes a request and never answers it, as the owner's did on 2026-09-24 (the request went out at 05:51:19, the
 * connection stayed open, no answer came). {@link Mode#SILENT} sends nothing at all; {@link Mode#HEAD_ONLY} sends the head of a 200
 * answer and then nothing, which the HTTP client's own time limit does not cover. Every connection is watched after its request: when
 * the client closes it, {@link #closed} counts down. Requests a test names ({@link #answer}) are answered at once instead: a probe, the
 * window size.
 */
public final class HangingServer implements AutoCloseable {

    public enum Mode { SILENT, HEAD_ONLY }

    /** One request as it arrived: the method and path, and the body. */
    public record Request(String line, String body) { }

    private final ServerSocket socket;
    private final Mode mode;
    private final Predicate<Request> answered;
    private final String answer;
    public final List<Request> hung = new CopyOnWriteArrayList<>();
    public final List<Request> seen = new CopyOnWriteArrayList<>();
    private final List<Socket> open = new CopyOnWriteArrayList<>();
    private volatile CountDownLatch arrived = new CountDownLatch(1), closed = new CountDownLatch(1);

    public HangingServer(Mode mode) throws IOException { this(mode, r -> false, ""); }

    /** Requests {@code answered} picks get {@code answer} (JSON, status 200) at once; every other one hangs. */
    public HangingServer(Mode mode, Predicate<Request> answered, String answer) throws IOException {
        this.mode = mode; this.answered = answered; this.answer = answer;
        this.socket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread t = new Thread(this::accept, "hanging-server");
        t.setDaemon(true);
        t.start();
    }

    public String url() { return "http://127.0.0.1:" + socket.getLocalPort(); }

    /** Waits until a request that hangs has arrived. */
    public boolean awaitHung(long seconds) throws InterruptedException { return arrived.await(seconds, TimeUnit.SECONDS); }

    /** Waits until the client closed a connection whose request hangs. */
    public boolean awaitClosed(long seconds) throws InterruptedException { return closed.await(seconds, TimeUnit.SECONDS); }

    /** Watch for the next hanging request and the next close. */
    public void reset() { arrived = new CountDownLatch(1); closed = new CountDownLatch(1); }

    private void accept() {
        while (!socket.isClosed()) {
            try {
                Socket s = socket.accept();
                open.add(s);
                Thread t = new Thread(() -> serve(s), "hanging-server-connection");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) { return; }
        }
    }

    private void serve(Socket s) {
        try (s) {
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();
            while (true) {
                Request r = read(in);
                if (r == null) return;
                seen.add(r);
                if (answered.test(r)) {
                    byte[] b = answer.getBytes(StandardCharsets.UTF_8);
                    out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: " + b.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    out.write(b);
                    out.flush();
                    continue;
                }
                hung.add(r);
                if (mode == Mode.HEAD_ONLY) {
                    out.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 100000\r\n\r\n{\"choices\":".getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                }
                arrived.countDown();
                // nothing more is sent; the client closing the connection ends the read
                while (in.read() >= 0) { /* whatever the client sends is not answered either */ }
                closed.countDown();
                return;
            }
        } catch (IOException e) {
            closed.countDown();
        }
    }

    /** One request's head and body, or null at the end of the connection. */
    private static Request read(InputStream in) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int c, state = 0;
        while (state < 4 && (c = in.read()) >= 0) {
            head.write(c);
            state = (c == '\r' && (state == 0 || state == 2)) || (c == '\n' && (state == 1 || state == 3)) ? state + 1 : c == '\r' ? 1 : 0;
        }
        if (state < 4) return null;
        String h = head.toString(StandardCharsets.US_ASCII);
        int length = 0;
        for (String line : h.split("\r\n")) if (line.toLowerCase(Locale.ROOT).startsWith("content-length:")) length = Integer.parseInt(line.substring(15).strip());
        byte[] body = in.readNBytes(length);
        return new Request(h.split("\r\n", 2)[0], new String(body, StandardCharsets.UTF_8));
    }

    @Override public void close() throws IOException {
        socket.close();
        for (Socket s : open) { try { s.close(); } catch (IOException ignored) { } }
    }
}
