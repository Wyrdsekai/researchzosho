package org.researchzosho.librarian;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A library in a container reaches the account service through a socket of its node's that goes where the node itself reaches the
 * service (its own loopback, when the node is part of the service's process), never through the web's gate, which lets no library reach
 * the service by its name (0.8.0 review, round A). In a deployment where the service is a name on a private network, the library's
 * calls (asking for a use, its people and keys) come to the service from its own machine, where they are answered.
 */
class ServiceRelayTest {
    HttpServer up; AccountStore store;

    @AfterEach void stop() throws IOException {
        if (up != null) up.stop(0);
        if (store != null) store.close();
        Config.set(ContainerLibraries.SOCKETS, "");
    }

    static String exchange(SocketChannel ch, String request) throws IOException {
        try (ch) {
            ch.write(ByteBuffer.wrap(request.getBytes(StandardCharsets.US_ASCII)));
            ByteBuffer b = ByteBuffer.allocate(8192);
            StringBuilder got = new StringBuilder();
            long until = System.currentTimeMillis() + 15_000;
            while (System.currentTimeMillis() < until) { int n = ch.read(b); if (n < 0) break; b.flip(); got.append(StandardCharsets.US_ASCII.decode(b)); b.clear(); }   // to its end: each side closes
            return got.toString();
        }
    }

    @Test
    void aLibraryReachesTheServiceThroughTheNodesOwnWayAndNotThroughTheWebsGate(@TempDir Path tmp) throws Exception {
        up = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        up.createContext("/", x -> { byte[] b = "the service\n".getBytes(StandardCharsets.US_ASCII); x.sendResponseHeaders(200, b.length); x.getResponseBody().write(b); x.close(); });
        up.start();
        Path root = tmp.resolve("accounts");
        Config.set(ContainerLibraries.SOCKETS, Files.createDirectories(tmp.resolve("s")).toString());   // a short path: a socket's path holds at most 107 bytes
        store = AccountStore.open(root.resolve("accounts.db").toString());
        AccountStore.Library lib = AccountService.newAccount(store, root, "Ann Example", "", false, "the test").library();
        // the service's statements name it by an address on a private network; the node is part of its process, and reaches it on its loopback
        ContainerLibraries runner = new ContainerLibraries(store, "http://10.249.73.9:4649", "http://127.0.0.1:" + up.getAddress().getPort());
        Relays relays = new Relays();
        try {
            StringBuilder serviceAt = new StringBuilder();
            Map<String, String> env = runner.environment(lib, relays, serviceAt, new HashSet<>());
            assertTrue(serviceAt.toString().matches("http://127\\.0\\.0\\.1:\\d+"), serviceAt.toString());
            String port = serviceAt.substring(serviceAt.lastIndexOf(":") + 1);
            assertTrue(("," + env.get(Relays.SPEC) + ",").contains("," + port + "=" + ContainerLibraries.SERVICE_SOCKET + ","), env.get(Relays.SPEC));
            // through the node's socket for it: the service answers
            Path dir = ContainerLibraries.sockets(lib);
            String answered = exchange(Relays.unixAt(dir.resolve(ContainerLibraries.SERVICE_SOCKET + ".sock")), "GET /healthz HTTP/1.1\r\nHost: service\r\nConnection: close\r\n\r\n");
            assertTrue(answered.startsWith("HTTP/1.1 200") && answered.contains("the service"), answered);
            // and the web's gate does not take the library to the service's address
            String gate = exchange(Relays.unixAt(dir.resolve(ContainerLibraries.WEB_SOCKET + ".sock")), "GET http://10.249.73.9:4649/internal/use/start HTTP/1.1\r\nHost: 10.249.73.9:4649\r\nConnection: close\r\n\r\n");
            assertTrue(gate.startsWith("HTTP/1.1 403"), "refused by the gate: " + gate);
        } finally { relays.close(); }
    }
}
