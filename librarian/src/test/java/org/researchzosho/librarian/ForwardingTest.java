package org.researchzosho.librarian;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the account service carries between a caller and a library, through the service's own port: an answer written over time arrives
 * as it is written and not at its end; a large file goes through as a stream, with the length the caller declared; a request that
 * takes long is waited for; and when the caller goes away, the library's side is closed too. The library here is a stand-in that
 * writes, reads and waits as told, and notes what reached it: the statement, and nothing else that says who the caller is.
 *
 * <p>The suite runs it in seconds. {@code RZ_FORWARD_SECONDS} and {@code RZ_FORWARD_MB} lengthen it for a run by hand (180 and 700 were
 * run on 2026-11-17); the lines it prints are the measurement.
 */
class ForwardingTest {
    static final int SECONDS = Integer.parseInt(System.getenv().getOrDefault("RZ_FORWARD_SECONDS", "3")), MB = Integer.parseInt(System.getenv().getOrDefault("RZ_FORWARD_MB", "48"));

    HttpServer library; AccountStore store; AccountService svc; String key;
    final Map<String, List<String>> seen = new ConcurrentHashMap<>();
    final AtomicLong received = new AtomicLong(), goneAt = new AtomicLong();
    final CountDownLatch gone = new CountDownLatch(1);
    volatile String declared = "";

    @BeforeEach
    void start(@TempDir Path tmp) throws Exception {
        library = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        library.createContext("/stream", x -> {   // a line every 250 ms, each flushed
            int n = Integer.parseInt(x.getRequestURI().getQuery());
            x.sendResponseHeaders(200, 0);
            try (OutputStream o = x.getResponseBody()) { for (int i = 1; i <= n; i++) { o.write(("line " + i + "\n").getBytes(StandardCharsets.UTF_8)); o.flush(); Thread.sleep(250); } }
            catch (IOException e) { goneAt.set(System.currentTimeMillis()); gone.countDown(); }
            catch (InterruptedException ignored) { }
        });
        library.createContext("/upload", x -> {
            x.getRequestHeaders().forEach(seen::put);
            declared = String.valueOf(x.getRequestHeaders().getFirst("Content-Length"));
            long n = 0; byte[] b = new byte[65536];
            try (InputStream in = x.getRequestBody()) { for (int r; (r = in.read(b)) > 0; ) n += r; }
            received.set(n);
            byte[] out = ("received " + n).getBytes(StandardCharsets.UTF_8);
            x.getResponseHeaders().add("Set-Cookie", "rz_lang=ja; Path=/");
            x.sendResponseHeaders(200, out.length); x.getResponseBody().write(out); x.close();
        });
        library.createContext("/slow", x -> {
            try { Thread.sleep(Integer.parseInt(x.getRequestURI().getQuery()) * 1000L); } catch (InterruptedException ignored) { }
            byte[] out = "answered".getBytes(StandardCharsets.UTF_8);
            x.sendResponseHeaders(200, out.length); x.getResponseBody().write(out); x.close();
        });
        library.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        library.start();
        String at = "http://127.0.0.1:" + library.getAddress().getPort();
        store = AccountStore.open(tmp.resolve("accounts.db").toString());
        svc = AccountService.start(store, "127.0.0.1", 0, self -> new LibraryRunner() {
            @Override public String ensure(AccountStore.Library l) { return at; }
            @Override public void lost(String id) { }
            @Override public void stopAll() { }
        });
        AccountStore.Account ann = store.createAccount("Ann Example", "", false);
        store.addLibrary("lib_stand_in", "ann", "Ann's", ann.id(), tmp.toString());
        key = store.mintKey(ann.id(), "lib_stand_in", "write", "the test").secret();
    }

    @AfterEach void stop() { svc.stop(); store.close(); library.stop(0); }

    HttpClient client() { return HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build(); }

    @Test
    void anAnswerWrittenOverTimeArrivesAsItIsWritten() throws Exception {
        int lines = SECONDS * 4;
        long t0 = System.currentTimeMillis(), first = 0, prev = 0, longest = 0; int got = 0;
        HttpResponse<InputStream> r = client().send(HttpRequest.newBuilder(URI.create(svc.url() + "/stream?" + lines)).header("Authorization", "Bearer " + key).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, r.statusCode());
        try (BufferedReader br = new BufferedReader(new InputStreamReader(r.body(), StandardCharsets.UTF_8))) {
            for (String l; (l = br.readLine()) != null; ) { long now = System.currentTimeMillis(); got++; if (first == 0) first = now; else longest = Math.max(longest, now - prev); prev = now; }
        }
        long whole = System.currentTimeMillis() - t0;
        System.out.println("[forwarding] stream: " + got + " lines over " + (prev - first) / 1000.0 + " s, the longest wait between two lines " + longest + " ms, the first line " + (first - t0) + " ms after the request");
        assertEquals(lines, got);
        assertTrue(first - t0 < whole / 2, "the first line came long before the last: " + (first - t0) + " ms of " + whole);
        assertTrue(longest < 1500, "no line was held back for the others: the longest wait was " + longest + " ms");
        assertTrue(prev - first >= (lines - 2) * 250L, "the lines came spread over the time they were written in");
    }

    @Test
    void aLargeFileGoesThroughAsAStreamWithItsLengthAndOnlyTheStatementSaysWhoAsks() throws Exception {
        long total = MB * 1024L * 1024L;
        InputStream file = new InputStream() {
            long left = total;
            @Override public int read() { if (left <= 0) return -1; left--; return 'x'; }
            @Override public int read(byte[] b, int off, int len) { if (left <= 0) return -1; int n = (int) Math.min(len, left); Arrays.fill(b, off, off + n, (byte) 'x'); left -= n; return n; }
        };
        Runtime rt = Runtime.getRuntime();
        System.gc();
        long before = rt.totalMemory() - rt.freeMemory(), t0 = System.currentTimeMillis();
        AtomicLong peak = new AtomicLong(before);
        Thread watch = new Thread(() -> { while (!Thread.currentThread().isInterrupted()) { peak.accumulateAndGet(rt.totalMemory() - rt.freeMemory(), Math::max); try { Thread.sleep(20); } catch (InterruptedException e) { return; } } });
        watch.setDaemon(true); watch.start();
        HttpResponse<String> r = client().send(HttpRequest.newBuilder(URI.create(svc.url() + "/upload")).header("Authorization", "Bearer " + key).header("Cookie", AccountService.COOKIE + "=a-session-secret; rz_lang=fr")
                .header("X-RZ-Statement", "forged").header("Tailscale-User-Login", "someone@example.org").header("X-Forwarded-For", "203.0.113.9")
                .POST(HttpRequest.BodyPublishers.fromPublisher(HttpRequest.BodyPublishers.ofInputStream(() -> file), total)).build(), HttpResponse.BodyHandlers.ofString());
        watch.interrupt();
        long ms = System.currentTimeMillis() - t0;
        System.out.println("[forwarding] upload: " + total + " bytes in " + ms + " ms; this process's heap in use rose from " + before / (1024 * 1024) + " MB to a peak of " + peak.get() / (1024 * 1024) + " MB (caller, service and stand-in library together in the suite; alone with a 64 MB heap by hand)");
        assertEquals(200, r.statusCode(), r.body());
        assertEquals("received " + total, r.body());
        assertEquals(total, received.get());
        assertEquals(String.valueOf(total), declared, "the length the caller declared reached the library");
        // who is asking: the statement the service signed, for this library, and nothing the caller sent
        AccountStore.Signing k = store.signing();
        Statement.Claims c = Statement.verify(seen.get("X-rz-statement").get(0), kid -> Statement.fromJwk(Statement.M.readTree(store.publicJwks().get(0))), svc.url(), "lib_stand_in");
        assertEquals("Ann Example", c.name()); assertEquals(Statement.KEEPER, c.lvl()); assertEquals(Statement.KEY, c.via()); assertEquals(k.kid().isEmpty(), false);
        assertEquals(1, seen.get("X-rz-statement").size(), "the caller's own header of that name was dropped");
        assertNull(seen.get("Authorization"), "the key stays at the service");
        assertNull(seen.get("Tailscale-user-login"), "a relay's header a caller wrote is dropped");
        assertEquals(List.of("rz_lang=fr"), seen.get("Cookie"), "the library's own small cookie goes on; the session's never");
        assertEquals(List.of("127.0.0.1"), seen.get("X-forwarded-for"), "the address the service saw, not one the caller claimed");
        assertEquals(List.of("http"), seen.get("X-forwarded-proto"));
        assertTrue(seen.get("X-forwarded-host").get(0).startsWith("127.0.0.1:"), String.valueOf(seen.get("X-forwarded-host")));
        assertTrue(r.headers().allValues("Set-Cookie").contains("rz_lang=ja; Path=/"), "what the library sets comes back");
    }

    @Test
    void aRequestThatTakesLongIsWaitedFor() throws Exception {
        long t0 = System.currentTimeMillis();
        HttpResponse<String> r = client().send(HttpRequest.newBuilder(URI.create(svc.url() + "/slow?" + SECONDS)).header("Authorization", "Bearer " + key).GET().build(), HttpResponse.BodyHandlers.ofString());
        long ms = System.currentTimeMillis() - t0;
        System.out.println("[forwarding] a request the library answered after " + SECONDS + " s came back after " + ms / 1000.0 + " s");
        assertEquals(200, r.statusCode()); assertEquals("answered", r.body());
        assertTrue(ms >= SECONDS * 1000L && ms < SECONDS * 1000L + 5000, ms + " ms");
    }

    @Test
    void whenTheCallerGoesAwayTheLibrarysSideIsClosedToo() throws Exception {
        long left;
        try (Socket s = new Socket("127.0.0.1", svc.port())) {
            s.getOutputStream().write(("GET /stream?240 HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer " + key + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            s.getOutputStream().flush();
            BufferedReader br = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            int lines = 0;
            for (String l; lines < 3 && (l = br.readLine()) != null; ) if (l.startsWith("line ")) lines++;
            assertEquals(3, lines, "the stream had begun");
            left = System.currentTimeMillis();
        }   // the socket closes: a browser tab shut, a program ended
        assertTrue(gone.await(15, TimeUnit.SECONDS), "the library's write failed: its side was closed");
        System.out.println("[forwarding] the caller left; the library's side was closed " + (goneAt.get() - left) + " ms later (it writes a line every 250 ms)");
        assertFalse(goneAt.get() - left > 10_000);
    }
}
