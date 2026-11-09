package org.researchzosho.librarian;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The pages the surfaces audit of 2026-11-07 found as views only, now with their forms: seeks, perspectives, a claim submitted, forecasts,
 * bridges, bookmarks and surveys read in, the databases, the books and podcasts pages, the MCP servers' tools and cap, the proposed
 * subjects, what the person tells on the dossier page, and the keeper's access side. Each through the daemon on an ephemeral port, as a
 * browser sends it.
 */
class ParityPagesTest {
    @TempDir Path tmp;
    String realHome;

    @BeforeEach void scratch() throws Exception {
        QuietHome.quiet(tmp.resolve("home"));
        realHome = System.getProperty("user.home"); System.setProperty("user.home", tmp.resolve("home").toString()); Config.invalidate();
        LibraryProtocol.LEADS = (subject, text) -> List.of();
    }
    @AfterEach void back() { System.setProperty("user.home", realHome); Config.invalidate(); }

    LibraryStore library() throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        store.write(new Finding("F-0001-gears", "The gears were cut by hand", List.of("gears--cutting"), Finding.State.accepted, Finding.ClaimType.extraction, Finding.Confidence.high,
                "person", Instant.now().toString(), "2026-09-05", Finding.Volatility.stable, "",
                List.of(new Finding.Source("https://museum.example/gears", "n/a", "the museum's own page")), List.of(), null, "The museum states the gears were cut with files.\n"));
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        return store;
    }

    static HttpResponse<String> get(HttpClient c, String url) throws Exception { return c.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString()); }

    static HttpResponse<String> post(HttpClient c, String url, String origin, String form) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form));
        if (origin != null) b.header("Origin", origin);
        return c.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }

    /** The note a form's redirect carries. */
    static String note(HttpResponse<String> r) {
        String to = r.headers().firstValue("Location").orElse("");
        int at = to.indexOf("note=");
        if (at < 0) return "";
        String rest = to.substring(at + 5); int amp = rest.indexOf('&'); int hash = rest.indexOf('#');
        int end = amp < 0 ? (hash < 0 ? rest.length() : hash) : (hash < 0 ? amp : Math.min(amp, hash));
        return URLDecoder.decode(rest.substring(0, end), StandardCharsets.UTF_8);
    }

    @Test
    void everyNewPageOpensAndItsFormsAct() throws Exception {
        LibraryStore store = library();
        LibrarianDaemon d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1);
        try {
            HttpClient c = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
            String base = "http://127.0.0.1:" + d.port();
            // the pages open, each with its form
            for (String[] p : new String[][]{{"/seeks", "No seeks yet."}, {"/perspectives", "name=\"q\""}, {"/submit", "name=\"claim\""}, {"/forecasts", "No forecasts yet."}, {"/bridges", "name=\"area\""}, {"/launch", "name=\"op\" value=\"bookmarks\""}, {"/db", "No database has been added yet."}, {"/books", "name=\"op\" value=\"react\""}, {"/podcasts", "name=\"op\" value=\"follow\""}}) {
                HttpResponse<String> r = get(c, base + p[0]);
                assertEquals(200, r.statusCode(), p[0]);
                assertTrue(r.body().contains(p[1]), p[0] + " shows " + p[1] + ": " + r.body().substring(Math.max(0, r.body().indexOf("<main"))).replaceAll("<[^>]+>", " ").replaceAll("\\s+", " "));
            }
            // the menu names them
            String home = get(c, base + "/seeks").body();
            for (String href : List.of("/seeks", "/bridges", "/forecasts", "/perspectives", "/submit", "/launch", "/db")) assertTrue(home.contains("href=\"" + href + "\""), href + " in the menu");
            // a claim submitted is a draft in the inbox
            HttpResponse<String> filed = post(c, base + "/submit", base, "claim=" + enc("The dials show the Saros cycle of 223 synodic months.") + "&sources=" + enc("https://museum.example/dials\n") + "&why=" + enc("the museum's page"));
            assertEquals(303, filed.statusCode(), filed.body());
            String n = note(filed);
            assertTrue(n.startsWith("Filed as a draft: F-"), n);
            assertEquals(Finding.State.draft, store.finding(n.substring("Filed as a draft: ".length(), n.indexOf('.'))).state());
            assertTrue(note(post(c, base + "/submit", base, "claim=" + enc("Too short.") + "&sources=")).contains("too short") || note(post(c, base + "/submit", base, "claim=" + enc("A claim with no source at all, which is refused.") + "&sources=")).contains("source"), "refused without a source");
            // the proposed subjects decided on the Subjects page
            Files.writeString(store.subjectsFile().resolveSibling("subjects.proposed.md"), "# Proposed subjects\n\n- gears--cutting — how the gears were cut\n- dials--saros — the Saros dial\n", StandardCharsets.UTF_8);
            String subjects = get(c, base + "/subjects").body();
            assertTrue(subjects.contains("name=\"p1\" value=\"gears--cutting\"") && subjects.contains(">Accept the ticked<"), subjects);
            HttpResponse<String> accepted = post(c, base + "/subjects", base, "op=accept&p1=gears--cutting");
            assertTrue(note(accepted).startsWith("Accepted gears--cutting — how the gears were cut."), note(accepted));
            assertNotNull(Vocabulary.read(store.subjectsFile()).get("gears--cutting"));
            assertTrue(note(post(c, base + "/subjects", base, "op=drop&all=1")).startsWith("Dropped dials--saros."));
            // bookmarks read in from pasted addresses
            HttpResponse<String> marks = post(c, base + "/launch", base, "op=bookmarks&text=" + enc("https://museum.example/gears\nhttps://museum.example/dials\n"));
            assertEquals(303, marks.statusCode(), marks.body());
            assertFalse(note(marks).isEmpty(), "the bookmarks' summary: " + marks.headers().map());
            // the seeks: nothing to merge yet, said plainly
            assertTrue(note(post(c, base + "/seeks", base, "op=merge&group=nope")).length() > 10);
            // a forecast scored: none to score, said plainly
            assertTrue(note(post(c, base + "/forecasts", base, "score=P-0001&id=P-0001&outcome=happened")).length() > 5);
            // the dossier page: told, from its own form
            HttpResponse<String> told = post(c, base + "/dossier", base, "subject=" + enc("Ada Example") + "&tell=1&text=" + enc("She ran the workshop from 1990 to 2005 and had the gears cut by hand."));
            assertEquals(303, told.statusCode(), told.body());
            assertTrue(note(told).startsWith("Noted, in your words, as F-"), note(told));
            String dossier = get(c, base + "/dossier?subject=" + enc("Ada Example")).body();
            assertTrue(dossier.contains("ran the workshop from 1990 to 2005") && dossier.contains("name=\"tell\"") && dossier.contains("name=\"story\""), dossier);
            // the books page's reaction, and the podcasts page's unfollow of nothing
            HttpResponse<String> react = post(c, base + "/books", base, "op=react&reaction=like&title=" + enc("A Book Nobody Wrote"));
            assertEquals(303, react.statusCode(), react.body());
            assertFalse(note(react).isEmpty(), react.headers().map().toString());
            assertTrue(note(post(c, base + "/podcasts", base, "op=unfollow&which=nothing")).startsWith("No followed show is nothing."));
            // the keeper's access side: the default level, an id listed, a token for it
            assertEquals("Anyone not listed may read.", note(post(c, base + "/settings", base, "op=reader-default&level=read")));
            assertEquals("did:key:zz may write.", note(post(c, base + "/settings", base, "op=reader-allow&did=did:key:zz&name=A+Program&level=write")));
            HttpResponse<String> token = post(c, base + "/settings", base, "op=reader-token&did=did:key:zz");
            assertEquals(200, token.statusCode(), token.body());
            assertTrue(token.body().contains("A token for did:key:zz, shown this once"), token.body());
            Patrons.setDefault(store, Patrons.Level.write);   // open again, as the pages were
            // the database page's query of a database that is not there
            assertTrue(note(post(c, base + "/db", base, "database=nothing&sql=" + enc("select 1"))).contains("No database is named nothing"));
            // the MCP section's cap on a server that is not there
            assertFalse(note(post(c, base + "/keep", base, "op=mcp-cap&name=nothing&value=10")).isEmpty());
        } finally { d.stop(); }
    }
}
