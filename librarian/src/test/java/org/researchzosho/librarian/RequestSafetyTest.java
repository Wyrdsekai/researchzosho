package org.researchzosho.librarian;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.net.Socket;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The 0.6.9 fixes: each request's marks are its own, a form is carried out only from the library's own page, a library on the loopback
 * answers only to the loopback's names, and a browser on this computer keeps the library only by this computer's names.
 */
class RequestSafetyTest {
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
    void aRelaysLoginThroughTheDoorIsNotTheNextVisitorsName(@TempDir Path tmp) throws Exception {
        LibraryStore store = LetPeopleInTest.lib(tmp);
        WebAccess.OVERRIDE = true; WebAccess.LOCAL_OVERRIDE = false;
        LibrarianDaemon d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1, 0);
        HttpClient c = LetPeopleInTest.client();
        String door = "http://127.0.0.1:" + d.doorPort();
        try {
            var bob = LetPeopleInTest.get(c, door + "/login", null, "Tailscale-User-Login", "bob@example.com", "Tailscale-User-Name", "Bob");
            assertTrue(bob.body().contains("You are bob@example.com to the relay"), "the relay's login, on its own request");
            var next = LetPeopleInTest.get(c, door + "/login", null);
            assertTrue(next.body().contains("Ask to be let in"), "the next visitor sees the ask form");
            assertFalse(next.body().contains("bob@example.com"), "and not the last visitor's login: each request's marks are its own");
        } finally { d.stop(); }
    }

    @Test
    void aFormFromAnotherSiteIsRefusedBeforeItIsRead(@TempDir Path tmp) throws Exception {
        LibraryStore store = LetPeopleInTest.lib(tmp);
        LibrarianDaemon d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1);
        HttpClient c = LetPeopleInTest.client();
        String main = "http://127.0.0.1:" + d.port();
        try {
            var elsewhere = LetPeopleInTest.post(c, main + "/login", "token=bogus", null, "Origin", "https://elsewhere.example");
            assertEquals(403, elsewhere.statusCode());
            assertEquals(OwnPage.REFUSED, elsewhere.body().strip());
            assertEquals(OwnPage.REFUSED, LetPeopleInTest.post(c, main + "/login", "token=bogus", null, "Sec-Fetch-Site", "cross-site").body().strip(), "the browser's own word");
            assertEquals(OwnPage.REFUSED, LetPeopleInTest.post(c, main + "/login", "token=bogus", null, "Referer", "https://elsewhere.example/page").body().strip(), "no Origin: the page it was on");
            assertNotEquals(OwnPage.REFUSED, LetPeopleInTest.post(c, main + "/login", "token=bogus", null, "Origin", main).body().strip(), "the library's own page");
            assertNotEquals(OwnPage.REFUSED, LetPeopleInTest.post(c, main + "/login", "token=bogus", null).body().strip(), "a program's call that names no page");
        } finally { d.stop(); }
    }

    @Test
    void aLibraryOnTheLoopbackAnswersOnlyToTheLoopbacksNames(@TempDir Path tmp) throws Exception {
        for (String h : List.of("localhost:4649", "127.0.0.1:4649", "[::1]:4649")) assertTrue(LocalName.loopback(h), h);
        for (String h : List.of("elsewhere.example:4649", "127.0.0.1.elsewhere.example", "localhost.elsewhere.example")) assertFalse(LocalName.loopback(h), h);
        LibraryStore store = LetPeopleInTest.lib(tmp);
        LibrarianDaemon d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1);
        int port = d.port();
        try {
            String renamed = under(port, "elsewhere.example:" + port, "/");
            assertEquals(403, status(renamed), renamed);
            assertTrue(renamed.contains(LocalName.REFUSED), renamed);
            assertEquals(403, status(under(port, "elsewhere.example:" + port, "/v1/status")), "nor the protocol");
            for (String host : new String[]{"localhost:" + port, "127.0.0.1:" + port, null}) {
                String r = under(port, host, "/v1/status");
                assertFalse(r.contains(LocalName.REFUSED), "served under " + host + " (a program's call when there is no Host): " + r.lines().findFirst().orElse(""));
            }
        } finally { d.stop(); }
    }

    @Test
    void aBrowserOnThisComputerKeepsTheLibraryOnlyByThisComputersNames(@TempDir Path tmp) throws Exception {
        WebAccess.OVERRIDE = true; WebAccess.LOCAL_OVERRIDE = true;
        LibraryStore store = LetPeopleInTest.lib(tmp);
        LibrarianDaemon d = LibrarianDaemon.start(store, "0.0.0.0", 0, "http://127.0.0.1:1", "none", -1);   // on the network: every name reaches it
        int port = d.port();
        try {
            String keeper = under(port, "localhost:" + port, "/settings");
            String renamed = under(port, "elsewhere.example:" + port, "/settings");
            assertEquals(200, status(keeper), keeper.lines().findFirst().orElse(""));
            assertTrue(keeper.contains("sign out"), "by this computer's name: the keeper");
            assertFalse(renamed.contains("sign out"), "from this computer under another name: a visitor, not the keeper: " + renamed.lines().findFirst().orElse(""));
        } finally { d.stop(); }
    }
}
