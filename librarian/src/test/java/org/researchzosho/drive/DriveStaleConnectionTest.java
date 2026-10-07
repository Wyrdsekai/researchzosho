package org.researchzosho.drive;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.researchzosho.HttpSettings;
import org.researchzosho.Stopping;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A model server that closes a kept-alive connection under the client. llama.cpp's server, which {@code researchzosho model} runs, drops
 * a connection idle for 5 seconds; the JVM's pool reused such connections for up to 20 minutes, so a request sent at that moment failed
 * ("HTTP/1.1 header parser received no bytes") or, through the port proxy, was swallowed and waited out the call's limit (2026-10-06).
 * The request goes once more on a fresh connection; a request the server is slow on is not sent twice.
 */
class DriveStaleConnectionTest {

    static final ObjectMapper M = new ObjectMapper();

    /**
     * Speaks just enough HTTP/1.1. A GET (the client's look at /props and the like) is answered with an empty object; the first chat
     * request on a connection is answered and the connection kept; the second chat request on it is read and then dropped.
     */
    static final class DroppingServer implements AutoCloseable {
        final ServerSocket server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        final AtomicInteger requests = new AtomicInteger(), connections = new AtomicInteger(), dropped = new AtomicInteger();   // requests = chats answered

        DroppingServer() throws IOException {
            Thread t = new Thread(() -> {
                try {
                    while (!server.isClosed()) {
                        Socket s = server.accept();
                        connections.incrementAndGet();
                        new Thread(() -> serve(s)).start();
                    }
                } catch (IOException closed) { /* done */ }
            });
            t.setDaemon(true);
            t.start();
        }

        String base() { return "http://127.0.0.1:" + server.getLocalPort(); }

        private void serve(Socket s) {
            try (s; InputStream in = s.getInputStream(); OutputStream out = s.getOutputStream()) {
                for (int chatsOnThisConnection = 0; ; ) {
                    String line = readRequest(in);
                    if (line == null) return;
                    boolean chat = line.startsWith("POST ");
                    if (chat) {
                        chatsOnThisConnection++;
                        if (chatsOnThisConnection == 2) { dropped.incrementAndGet(); return; }   // close without a word, as the server at its idle limit does
                    }
                    byte[] body = (chat
                            ? "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"answer " + requests.incrementAndGet() + "\"},\"finish_reason\":\"stop\"}],"
                              + "\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":2,\"total_tokens\":7}}"
                            : "{}").getBytes(StandardCharsets.UTF_8);
                    out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: " + body.length + "\r\nConnection: keep-alive\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                    out.write(body);
                    out.flush();
                }
            } catch (IOException ignored) { }
        }

        /** Reads one request (headers, then Content-Length bytes of body) and returns its request line; null when the connection ended first. */
        private static String readRequest(InputStream in) throws IOException {
            StringBuilder head = new StringBuilder();
            int c, len = -1;
            while ((c = in.read()) != -1) {
                head.append((char) c);
                if (head.length() >= 4 && head.substring(head.length() - 4).equals("\r\n\r\n")) break;
            }
            if (c == -1 && head.length() == 0) return null;
            String[] lines = head.toString().split("\r\n");
            for (String line : lines) if (line.toLowerCase().startsWith("content-length:")) len = Integer.parseInt(line.substring(15).trim());
            for (int i = 0; i < len; i++) if (in.read() == -1) return null;
            return lines[0];
        }

        @Override public void close() throws IOException { server.close(); }
    }

    static ArrayNode hello() {
        ArrayNode m = M.createArrayNode();
        m.addObject().put("role", "user").put("content", "hello");
        return m;
    }

    @Test
    void aRequestOnAConnectionTheServerClosedIsSentAgainOnce() throws Exception {
        try (DroppingServer srv = new DroppingServer()) {
            DriveClient.forgetServed();
            DriveClient d = new DriveClient(srv.base(), "m");
            long before = Stopping.STALE_RETRIES.get();
            ObjectNode first = d.chat(hello(), null, 16, "auto");
            assertEquals("answer 1", first.path("content").asText(), first.toString());
            ObjectNode second = d.chat(hello(), null, 16, "auto");
            assertEquals("answer 2", second.path("content").asText(), "answered on a new connection: " + second);
            assertEquals(1, srv.dropped.get(), "the server dropped one request");
            assertEquals(2, srv.connections.get(), "the retry opened a second connection");
            assertEquals(before + 1, Stopping.STALE_RETRIES.get(), "one request was sent again");
        }
    }

    @Test
    void whatCountsAsAStaleConnection() {
        assertTrue(Stopping.staleConnection(new IOException("HTTP/1.1 header parser received no bytes")));
        assertTrue(Stopping.staleConnection(new IOException("boom", new IOException("Connection reset"))));
        assertFalse(Stopping.staleConnection(new HttpTimeoutException("request timed out")), "a slow server is never sent the request twice");
        assertFalse(Stopping.staleConnection(new IOException("Received fatal alert: handshake_failure")));
    }

    @Test
    void theIdleLimitIsBelowTheServers() {
        String was = System.getProperty(HttpSettings.KEEPALIVE);
        try {
            System.clearProperty(HttpSettings.KEEPALIVE);
            assertEquals("3", HttpSettings.apply());
            System.setProperty(HttpSettings.KEEPALIVE, "7");
            assertEquals("7", HttpSettings.apply(), "an explicit setting is kept");
        } finally {
            if (was == null) System.clearProperty(HttpSettings.KEEPALIVE); else System.setProperty(HttpSettings.KEEPALIVE, was);
        }
    }
}
