package org.researchzosho.librarian;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The command line from elsewhere (AFTER_0_6 §5): connected to a library on another computer, the verbs with a protocol call go over
 * the wire and come back in plain lines; the folder's verbs say to run them there; a wrong token is not kept; the mcp relay carries the
 * token and turns a refusal into a JSON-RPC error.
 */
class ConnectTest {

    String realHome;

    @BeforeEach void scratch(@TempDir Path tmp) { realHome = System.getProperty("user.home"); System.setProperty("user.home", tmp.resolve("home").toString()); Config.invalidate(); }
    @AfterEach void restore() { System.setProperty("user.home", realHome); Config.invalidate(); }

    private static Finding f(String id, String title, String body, String locator) {
        return new Finding(id, title, List.of("gears--cutting"), Finding.State.accepted, Finding.ClaimType.extraction, Finding.Confidence.high,
                "person", Instant.now().toString(), "2026-09-05", Finding.Volatility.stable, "",
                List.of(new Finding.Source(locator, "n/a", "the museum's own page")), List.of(), null, body);
    }

    record Ran(int code, String out, String err) { }

    static Ran run(String... words) {
        String[] args = new String[words.length + 1]; args[0] = "librarian"; System.arraycopy(words, 0, args, 1, words.length);
        PrintStream o = System.out, e = System.err;
        ByteArrayOutputStream bo = new ByteArrayOutputStream(), be = new ByteArrayOutputStream();
        System.setOut(new PrintStream(bo, true, StandardCharsets.UTF_8)); System.setErr(new PrintStream(be, true, StandardCharsets.UTF_8));
        try { return new Ran(LibrarianCli.run(args, "http://127.0.0.1:1", "m"), bo.toString(StandardCharsets.UTF_8), be.toString(StandardCharsets.UTF_8)); }
        finally { System.setOut(o); System.setErr(e); }
    }

    static LibraryStore lib(Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        store.write(f("F-0001-gears", "The gears were cut by hand", "The museum states the gears were cut with files.\n", "https://museum.example/gears"));
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        Patrons.setDefault(store, Patrons.Level.deny);
        Patrons.set(store, "did:key:me", "Me", Patrons.Level.write);
        return store;
    }

    @Test
    void theVerbsGoOverTheWireAndTheFoldersVerbsSayWhereToRun(@TempDir Path tmp) throws Exception {
        LibraryStore store = lib(tmp);
        String token = Patrons.issueToken(store, "did:key:me");
        LibrarianDaemon d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1);
        try {
            String url = "http://127.0.0.1:" + d.port();
            Ran c = run("connect", url + "/", token);
            assertEquals(0, c.code(), c.err());
            assertTrue(c.out().startsWith("Connected to lib at " + url + ": 1 claims, 0 reports"), c.out());
            assertTrue(Remote.connected()); assertEquals(url, Remote.address(), "the trailing slash is dropped");
            Ran st = run("status");
            assertTrue(st.out().startsWith("library lib at " + url) && st.out().contains("1 claims"), st.out() + st.err());
            Ran ask = run("ask", "how", "were", "the", "gears", "cut");
            assertEquals(0, ask.code(), ask.err()); assertTrue(ask.out().contains("[F-0001-gears] The gears were cut by hand"), ask.out());
            Ran none = run("ask", "the", "colour", "of", "the", "moons", "of", "jupiter");
            assertTrue(none.out().startsWith("The library holds nothing on this yet."), none.out());
            Ran search = run("search", "gears");
            assertTrue(search.out().contains("- [F-0001-gears] The gears were cut by hand"), search.out());
            Ran get = run("get", "F-0001-gears");
            assertTrue(get.out().contains("The gears were cut by hand") && get.out().contains("cut with files"), get.out());
            Ran est = run("established", "the", "gears", "were", "cut", "by", "hand");
            assertTrue(est.out().startsWith("verdict: "), est.out() + est.err());
            Ran qa = run("questions", "add", "Who", "measured", "the", "teeth", "first?");
            assertEquals(0, qa.code(), qa.err()); assertTrue(qa.out().startsWith("Filed as an open question"), qa.out());
            Ran ql = run("questions");
            assertTrue(ql.out().contains("Who measured the teeth first?"), ql.out());
            // a research run asked for from elsewhere: this test daemon has no model, so the library's own refusal is what comes back, as its sentence
            Ran ra = run("research", "ask", "How", "were", "the", "gears", "cut?", "--max-turns", "2");
            assertEquals(1, ra.code(), ra.out()); assertTrue(ra.err().startsWith(url + " says: No model is answering"), ra.err());
            Ran jobs = run("jobs");
            assertEquals(0, jobs.code(), jobs.err()); assertTrue(jobs.out().startsWith("research runs at " + url + ": 0 running, 0 queued"), jobs.out());
            Ran one = run("jobs", "J-0001");
            assertEquals(1, one.code()); assertTrue(one.err().contains("says:"), one.err());
            assertEquals(0, run("subjects").code()); assertEquals(0, run("changes").code()); assertEquals(0, run("inbox").code());
            Ran map = run("map", "gears");
            assertEquals(0, map.code(), map.err());
            Ran svc = run("service", "status");
            assertEquals(2, svc.code()); assertTrue(svc.err().contains("works on the computer where the library lives (" + url + ")"), svc.err());
            assertEquals(2, run("reader", "list").code()); assertEquals(2, run("add", "x.txt").code());
            Ran where = run("connect");
            assertTrue(where.out().startsWith("Connected to lib at " + url), where.out());
            // a wrong token: the library says so, and the working one stays
            Ran bad = run("connect", url, "not-a-token");
            assertEquals(1, bad.code()); assertTrue(bad.err().startsWith("Not connected:") && bad.err().contains("token"), bad.err());
            assertEquals(token, Config.get(Remote.TOKEN)); assertEquals(url, Remote.address());
            Ran off = run("connect", "off");
            assertEquals(0, off.code()); assertFalse(Remote.connected());
            assertEquals(2, run("connect", "mylibrary.example", "t").code(), "an address without its scheme is explained");
        } finally { d.stop(); }
    }

    @Test
    void theMcpRelayCarriesTheTokenAndWrapsARefusal(@TempDir Path tmp) throws Exception {
        LibraryStore store = lib(tmp);
        String token = Patrons.issueToken(store, "did:key:me");
        LibrarianDaemon d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1);
        try {
            Config.set("RESEARCHZOSHO_LIBRARY", "http://127.0.0.1:" + d.port()); Config.set(Remote.TOKEN, token);
            String init = Remote.rpc("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{\"name\":\"t\",\"version\":\"0\"}}}");
            assertTrue(init.contains("\"protocolVersion\"") && init.contains("\"id\":1"), init);
            assertNull(Remote.rpc("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"), "a notification gets no line");
            String list = Remote.rpc("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}");
            assertTrue(list.contains("library_ask") && !list.contains("codezaiku"), list);
            String call = Remote.rpc("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"library_status\",\"arguments\":{}}}");
            assertTrue(call.contains("\"id\":3") && call.contains("result"), call);
            Config.set(Remote.TOKEN, "wrong");
            String refused = Remote.rpc("{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/list\"}");
            assertTrue(refused.contains("\"error\"") && refused.contains("\"id\":4") && refused.toLowerCase().contains("token"), refused);
        } finally { d.stop(); }
    }
}
