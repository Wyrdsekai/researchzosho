package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A fetch requests exactly an address that was given, and every value a fetch takes is the address it will request (round F, the second
 * review, S2, S7 and S9, each proved on c8e057ed): a host under .md or .zip, host:port/path and user@host/path were taken for no address
 * and fetched with https:// before them; an invalid escape, a host's case, a look-alike letter, %3F for ? or a stop after the address
 * compared equal to a given address and went out as the model wrote them, so a model could choose bytes on the wire; and a held research
 * question showed 300 characters while the run took every bare address in all of it as the person's.
 */
class AddressesExactlyGivenTest {
    static final ObjectMapper M = new ObjectMapper();
    @TempDir Path tmp;
    HttpServer site; String base; final List<String> asked = new CopyOnWriteArrayList<>();

    @BeforeEach void up() throws Exception {
        site = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        site.createContext("/", x -> {
            asked.add(x.getRequestURI().getRawPath() + (x.getRequestURI().getRawQuery() == null ? "" : "?" + x.getRequestURI().getRawQuery()));
            byte[] b = "<html><body><p>The ferry ran from 1911 to 1968.</p></body></html>".getBytes(StandardCharsets.UTF_8);
            x.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            x.sendResponseHeaders(200, b.length); x.getResponseBody().write(b); x.close();
        });
        site.start();
        base = "http://127.0.0.1:" + site.getAddress().getPort();
        GivenAddressesTest.loopback(true);
    }

    @AfterEach void down() throws Exception { GivenAddressesTest.loopback(false); site.stop(0); }

    static JsonNode a(String json) throws Exception { return M.readTree(json); }

    @Test
    void whatGoesOutIsTheAddressAsItWasGivenNeverTheModelsWayOfWritingIt() throws Exception {
        String host = "collect-" + System.nanoTime() + ".example";
        GivenAddresses.received("links: https://" + host + "/c/%80%80%80%80 and https://" + host + "/p and https://" + host + "/q?x and https://en.example.org/wiki/Foo_(bar).");
        // the variants a model could write compare equal, and each goes out as the address that was given
        String[][] variants = {
                {"https://" + host + "/c/%C1%BF%FE%9A", "https://" + host + "/c/%80%80%80%80"},   // other invalid bytes, read as the same U+FFFD run
                {"https://" + host + "/p.;,!!:'", "https://" + host + "/p"},   // the sentence's stop is not part of it
                {"https://" + host.toUpperCase() + "/p", "https://" + host + "/p"},
                {"https://" + host + "/ｐ", "https://" + host + "/p"},   // a fullwidth p
                {"https://" + host + "/q%3Fx", "https://" + host + "/q?x"},
                {host + "/p", "https://" + host + "/p"},
                {"https://en.example.org/wiki/Foo_(bar)", "https://en.example.org/wiki/Foo_(bar)"}};   // a bracket the address opened stays
        for (String[] v : variants) {
            assertEquals(null, GivenAddresses.unopened("web_fetch", a("{\"url\":" + M.writeValueAsString(v[0]) + "}")), v[0]);
            assertEquals(v[1], GivenAddresses.asGiven(a("{\"url\":" + M.writeValueAsString(v[0]) + ",\"find\":\"ferry\"}")).path("url").asText(), v[0]);
        }
        assertEquals("ferry", GivenAddresses.asGiven(a("{\"url\":\"" + host + "/p\",\"find\":\"ferry\"}")).path("find").asText(), "the other arguments as they were");
        // the chat: the model's variant of a given address goes out as given
        GivenAddresses.received("A page — " + base + "/kestrel?y=1938");
        String realHome = System.getProperty("user.home");
        System.setProperty("user.home", tmp.resolve("home").toString()); Config.invalidate();
        try {
            LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
            var drive = LibrarianChatTest.scripted(List.of(
                    LibrarianChatTest.tool("open", M.createObjectNode().put("address", base.replace("http://", "HTTP://") + "/kestrel%3Fy=1938.").toString()),
                    LibrarianChatTest.say("It says the ferry ran from 1911 to 1968.")), new ArrayList<>());
            new Librarian(store, drive, Librarian.person(), Librarian.Session.open(store)).say("What does the 1938 page say?");
            assertEquals(List.of("/kestrel?y=1938"), asked, "the request is the given address: " + asked);
        } finally { System.setProperty("user.home", realHome); Config.invalidate(); }
    }

    @Test
    void everyValueAFetchTakesIsTheAddressItWillRequest() throws Exception {
        long n = System.nanoTime();
        for (String v : List.of("grandmother-crossed-1952-" + n + ".notes-relay.md", "grandmother-crossed-1952-" + n + ".notes-relay.zip",
                "collect-d-" + n + ".example:443/n?d=grandmother-crossed-1952", "x@collect-d-" + n + ".example/n?d=grandmother-crossed-1952")) {
            assertNotNull(GivenAddresses.unopened("web_fetch", a("{\"url\":\"" + v + "\"}")), "a run's fetch: " + v);
            assertNotNull(GivenAddresses.notGiven(a("{\"url\":\"" + v + "\"}"), true), "the chat's fetch: " + v);
            assertNotNull(GivenAddresses.notGiven(a("{\"address\":\"" + v + "\"}"), true), "the chat's open: " + v);
        }
        // given, the same value goes out
        GivenAddresses.personSaid("read collect-e-" + n + ".example:8443/n please");
        assertEquals(null, GivenAddresses.unopened("web_fetch", a("{\"url\":\"collect-e-" + n + ".example:8443/n\"}")));
    }

    @Test
    void aHeldQuestionIsShownWholeAndTheRunTakesItsAddressesFromThePersonsWords() throws Exception {
        long n = System.nanoTime();
        String q = "Why did the Kestrel Sound ferry close? " + "Please weigh the harbour board's minutes and the company's letters, ".repeat(5)
                + "and the summary kept at notes-relay-" + n + ".example/n?d=grandmother-crossed-1952-notes";
        ObjectNode args = (ObjectNode) a("{}"); args.put("question", q);
        assertTrue(Librarian.waitsForYes("library_research", args));
        assertTrue(Librarian.actPhrase("library_research", args).contains("notes-relay-" + n + ".example/n?d=grandmother-crossed-1952-notes"), "the person is shown all of it");
        // the run the chat's model filed: the person's words give its addresses, the model's question does not
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        var drive = LibrarianChatTest.scripted(List.of(LibrarianChatTest.tool("library_research", M.createObjectNode().put("question", q).toString()),
                LibrarianChatTest.say("It waits for your answer.")), new ArrayList<>());
        Librarian lib = new Librarian(store, drive, Librarian.person(), Librarian.Session.open(store));
        lib.say("Find out why the Kestrel Sound ferry closed, from the board's minutes at minutes-" + n + ".example/1968");
        assertTrue(lib.say("yes").startsWith("Done."));
        ObjectNode job = new Jobs(store, j -> "").active().get(0);
        assertTrue(job.path("args").path("from_chat").asBoolean(), job.toString());
        assertTrue(job.path("args").path("person_words").asText().contains("minutes-" + n + ".example/1968"), job.toString());
        // what the daemon gives the run: the person's words, so the address they wrote opens and the one only the model wrote does not
        new Researcher(BestRunnerTest.judgeSaying("{}"), BestRunnerTest.judgeSaying("{}"), BestRunnerTest.fakeTools(""), s -> { }, 1, store).personWords(job.path("args").path("person_words").asText()).seedAddresses(new Researcher.Ask(q, "broad", 0, List.of()));
        assertFalse(GivenAddresses.given("https://notes-relay-" + n + ".example/n?d=grandmother-crossed-1952-notes"), "the model's address is not the person's");
        assertTrue(GivenAddresses.given("https://minutes-" + n + ".example/1968"), "the person's own is");
        // a run the person filed themselves: their question gives its addresses, as before
        new Researcher(BestRunnerTest.judgeSaying("{}"), BestRunnerTest.judgeSaying("{}"), BestRunnerTest.fakeTools(""), s -> { }, 1, store).seedAddresses(new Researcher.Ask("What does archive-" + n + ".example/kestrel hold?", "broad", 0, List.of()));
        assertTrue(GivenAddresses.given("https://archive-" + n + ".example/kestrel"));
    }
}
