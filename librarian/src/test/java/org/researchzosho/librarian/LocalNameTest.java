package org.researchzosho.librarian;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A library on the loopback answers only to the loopback's names, and a browser on this computer keeps the library only by this computer's names. */
class LocalNameTest {
    Boolean signIn, local;
    @BeforeEach void keep() { signIn = WebAccess.OVERRIDE; local = WebAccess.LOCAL_OVERRIDE; }
    @AfterEach void restore() { WebAccess.OVERRIDE = signIn; WebAccess.LOCAL_OVERRIDE = local; }

    /** A request as a browser sends it after a page pointed its own name at this computer: its Host is that name (the JDK's client sends no other). */
    static String under(int port, String host, String path) throws Exception {
        try (Socket s = new Socket("127.0.0.1", port)) {
            s.setSoTimeout(10_000);
            OutputStream out = s.getOutputStream();
            String head = host == null ? "GET " + path + " HTTP/1.0\r\n\r\n" : "GET " + path + " HTTP/1.1\r\nHost: " + host + "\r\nConnection: close\r\n\r\n";
            out.write(head.getBytes(StandardCharsets.US_ASCII));
            out.flush();
            return new String(s.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        }
    }
    static int status(String response) { return Integer.parseInt(response.substring(9, 12)); }

    @Test
    void theLoopbacksNamesAndThisComputersOwn() {
        assertEquals("localhost", LocalName.nameOf("LocalHost:4649"));
        assertEquals("[::1]", LocalName.nameOf("[::1]:4649"));
        assertEquals("localhost", LocalName.nameOf("localhost.:4649"), "a trailing dot is the same name");
        for (String h : List.of("localhost:4649", "127.0.0.1:4649", "127.1.2.3", "[::1]:4649", "localhost")) assertTrue(LocalName.loopback(h), h);
        for (String h : List.of("elsewhere.example:4649", "127.0.0.1.elsewhere.example", "localhost.elsewhere.example", "128.0.0.1", "127.0.0.256", "10.0.0.5:4649", "[::2]", ""))
            assertFalse(LocalName.loopback(h), h);
        String own = LocalName.ownName();
        if (!own.isEmpty()) {
            assertTrue(LocalName.thisComputer(own + ":4649"), "this computer's own name");
            assertTrue(LocalName.thisComputer(own + ".local"));
            assertFalse(LocalName.loopback(own + ":4649") && !own.equals("localhost"), "its own name is not a loopback name");
            assertFalse(LocalName.thisComputer(own + ".elsewhere.example"));
        }
        assertTrue(LocalName.servedHere(null) && LocalName.servedHere(""), "no Host: a program's call");
        assertFalse(LocalName.servedHere("elsewhere.example"));
    }

    @Test
    void aLibraryOnTheLoopbackAnswersOnlyToTheLoopbacksNames(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp); store.init();
        LibrarianDaemon d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1);
        int port = d.port();
        try {
            String renamed = under(port, "elsewhere.example:" + port, "/");
            assertEquals(403, status(renamed), renamed);
            assertTrue(renamed.contains(LocalName.REFUSED), renamed);
            assertEquals(403, status(under(port, "elsewhere.example:" + port, "/healthz")), "not even its name and id under another name");
            assertEquals(403, status(under(port, "elsewhere.example:" + port, "/v1/status")), "nor the protocol");
            assertEquals(200, status(under(port, "localhost:" + port, "/healthz")));
            assertEquals(200, status(under(port, "127.0.0.1:" + port, "/healthz")));
            assertEquals(200, status(under(port, null, "/healthz")), "a program's call with no Host goes on");
        } finally { d.stop(); }
    }

    @Test
    void aBrowserOnThisComputerKeepsTheLibraryOnlyByThisComputersNames(@TempDir Path tmp) throws Exception {
        WebAccess.OVERRIDE = true; WebAccess.LOCAL_OVERRIDE = true;   // sign-in on, and a browser on this computer is the keeper
        LibraryStore store = new LibraryStore(tmp); store.init();
        LibrarianDaemon d = LibrarianDaemon.start(store, "0.0.0.0", 0, "http://127.0.0.1:1", "none", -1);   // on the network: every name reaches it
        int port = d.port();
        try {
            String keeper = under(port, "localhost:" + port, "/keep");
            assertEquals(200, status(keeper), keeper.lines().findFirst().orElse(""));
            assertTrue(keeper.contains("sign out") && !keeper.contains("Keep is the page of the person who keeps this library"), "by this computer's name: the keeper");
            String renamed = under(port, "elsewhere.example:" + port, "/keep");
            assertTrue(renamed.contains("Keep is the page of the person who keeps this library") && renamed.contains("sign in") && !renamed.contains("sign out"),
                    "from this computer under another name: a visitor, asked to sign in");
        } finally { d.stop(); }
    }
}
