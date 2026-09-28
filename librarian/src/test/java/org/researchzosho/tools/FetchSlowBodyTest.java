package org.researchzosho.tools;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.researchzosho.HangingServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A page that comes slowly but steadily is read to its end, however long that takes: a scan from an archive's server is exactly such a
 * page, and a limit on the whole body as short as the wait for its head threw it away. A page that stops coming, or comes a byte now and
 * then, is given up.
 */
class FetchSlowBodyTest {

    @BeforeEach void loopback() { Fetch.allowLoopback = true; }
    @AfterEach void back() { Fetch.allowLoopback = false; }

    /** A site that sends its page's head at once and then the body in {@code pieces} pieces of {@code size} bytes, one every {@code everyMs}. */
    static final class SlowSite implements AutoCloseable {
        final ServerSocket socket;
        SlowSite(int pieces, int size, long everyMs) throws IOException {
            socket = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
            Thread t = new Thread(() -> {
                try (Socket s = socket.accept()) {
                    InputStream in = s.getInputStream();
                    int state = 0, c;
                    while (state < 4 && (c = in.read()) >= 0) state = (c == '\r' && (state == 0 || state == 2)) || (c == '\n' && (state == 1 || state == 3)) ? state + 1 : c == '\r' ? 1 : 0;
                    OutputStream out = s.getOutputStream();
                    out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: " + (long) pieces * size + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                    byte[] piece = new byte[size];
                    Arrays.fill(piece, (byte) 'a');
                    for (int i = 0; i < pieces; i++) { Thread.sleep(everyMs); out.write(piece); out.flush(); }
                    Thread.sleep(500);
                } catch (Exception ignored) { }
            }, "slow-site");
            t.setDaemon(true);
            t.start();
        }
        String url() { return "http://127.0.0.1:" + socket.getLocalPort() + "/placeholder-scan.pdf"; }
        @Override public void close() throws IOException { socket.close(); }
    }

    @Test
    void aPageThatComesSlowlyButSteadilyIsReadToItsEnd() throws Exception {
        // 40 KB over about three seconds, with a wait for the head of one second: the old limit gave up after that one second
        try (SlowSite site = new SlowSite(20, 2048, 150)) {
            Fetch.Result r = assertTimeoutPreemptively(Duration.ofSeconds(30), () -> Fetch.get(site.url(), Duration.ofSeconds(1)));
            assertEquals(200, r.status());
            assertEquals(20 * 2048, r.body().length, "the whole page");
        }
    }

    @Test
    void aPageThatComesAByteNowAndThenIsGivenUp() throws Exception {
        // a byte every 300 ms never leaves the reading idle for a second, and is far slower than any page
        try (SlowSite site = new SlowSite(200, 1, 300)) {
            long t0 = System.nanoTime();
            Exception e = assertTimeoutPreemptively(Duration.ofSeconds(30), () -> assertThrows(Exception.class, () -> Fetch.get(site.url(), Duration.ofSeconds(1))));
            long ms = (System.nanoTime() - t0) / 1_000_000;
            assertTrue(ms < 8_000, "given up soon, not after the 200 bytes' minute: " + ms + " ms");
            assertTrue(String.valueOf(e.getMessage()).contains("too slowly"), String.valueOf(e));
        }
    }

    @Test
    void aSiteThatSendsTheHeadAndThenNothingIsGivenUpWhenNothingComes() throws Exception {
        try (HangingServer s = new HangingServer(HangingServer.Mode.HEAD_ONLY)) {
            long t0 = System.nanoTime();
            Exception e = assertTimeoutPreemptively(Duration.ofSeconds(20), () -> assertThrows(Exception.class, () -> Fetch.get(s.url() + "/placeholder-page", Duration.ofSeconds(2))));
            long ms = (System.nanoTime() - t0) / 1_000_000;
            assertTrue(ms < 8_000, ms + " ms");
            assertTrue(String.valueOf(e.getMessage()).contains("sent nothing for 2 seconds"), String.valueOf(e));
            assertTrue(s.awaitClosed(5), "the connection was closed");
        }
    }
}
