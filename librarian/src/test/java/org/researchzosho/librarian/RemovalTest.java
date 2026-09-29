package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
/** A report or a claim out of the library for good: the plan, the three choices, what stays, the log, the CLI's protocol and the page. */
class RemovalTest {

    static final ObjectMapper M = new ObjectMapper();
    static Supplier<Researcher.Drive> drives;
    @BeforeAll static void noModel() { drives = Explain.DRIVES; Explain.DRIVES = () -> null; }
    @AfterAll static void restore() { Explain.DRIVES = drives; }

    static Finding claim(String id, String title, String from) {
        return new Finding(id, title, List.of("tides--tables"), Finding.State.accepted, Finding.ClaimType.extraction, Finding.Confidence.high, "model:t", Instant.now().toString(), "2026-09-17",
                Finding.Volatility.stable, "", List.of(new Finding.Source("https://example.org/" + id, "n/a", "cited by " + from)), List.of(), null, title + ".\n");
    }

    /** Two reports; the second also lists one of the first's claims. */
    static LibraryStore seeded(Path home) throws Exception {
        LibraryStore store = new LibraryStore(home.resolve("lib")); store.init();
        Files.writeString(store.subjectsFile(), "# Subjects\n\n- tides--tables — tide tables\n");
        store.write(claim("F-0001-a", "Harbour A agrees within ten centimetres", "I-0001-tides"));
        store.write(claim("F-0002-b", "Harbour B disagrees by a metre", "I-0001-tides"));
        store.write(claim("F-0003-c", "The IHO list has 37 constituents", "I-0001-tides"));
        store.write(claim("F-0004-d", "Harbour D has no gauge", "I-0002-gauges"));
        store.write(new Investigation("I-0001-tides", "How well do the tide tables agree?", Finding.State.accepted, "model:t", Instant.now().toString(), List.of("F-0001-a", "F-0002-b", "F-0003-c"), List.of(), "The tables agree at A.\n"));
        store.write(new Investigation("I-0002-gauges", "Which harbours have gauges?", Finding.State.accepted, "model:t", Instant.now().toString(), List.of("F-0004-d", "F-0003-c"), List.of(), "D has none; the IHO list matters [F-0003-c].\n"));
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        return store;
    }

    static ObjectNode patron(ObjectNode a) { a.putObject("patron").put("did", "person").put("name", "keeper").put("runtime", "cli"); return a; }

    @Test
    void thePlanNamesWhatGoesAndWhatStaysAndAllThreeChoicesDoWhatTheySay(@TempDir Path home) throws Exception {
        LibraryStore store = seeded(home);
        // all: the report and the claims that are its alone; F-0003-c is listed by I-0002 too and stays
        Removal.Plan all = Removal.plan(store, "I-0001-tides", Removal.What.all);
        assertTrue(all.reportGoes()); assertEquals(List.of("F-0001-a", "F-0002-b"), all.claimsGo()); assertEquals(List.of("F-0003-c"), all.claimsStay());
        String words = Removal.describe(all);
        assertTrue(words.startsWith("Delete the report I-0001-tides") && words.contains("Keep 1 claim(s) that another report also cites: F-0003-c") && words.endsWith("are kept."), words);
        // report only: nothing but the report; claims only: the claims, the report kept with its list pruned
        Removal.Plan report = Removal.plan(store, "I-0001-tides", Removal.What.report);
        assertTrue(report.reportGoes() && report.claimsGo().isEmpty() && report.claimsStay().size() == 3);
        Removal.Plan claims = Removal.plan(store, "I-0001-tides", Removal.What.claims);
        assertFalse(claims.reportGoes()); assertEquals(List.of("F-0001-a", "F-0002-b"), claims.claimsGo());
        // a single claim
        Removal.Plan one = Removal.plan(store, "F-0004-d", Removal.What.all);
        assertEquals("finding", one.kind()); assertEquals(List.of("F-0004-d"), one.claimsGo()); assertFalse(one.reportGoes());
        assertThrows(IOException.class, () -> Removal.plan(store, "I-9999-none", Removal.What.all));
        assertThrows(IOException.class, () -> Removal.plan(store, "../etc/passwd", Removal.What.all), "a name that is not an id");
        // claims only, applied: the claims are gone from disk and the index, the report stays with two ids fewer, the log says removed
        Removal.Done done = Removal.apply(store, claims, "person");
        assertEquals(List.of("F-0001-a", "F-0002-b"), done.removed());
        assertNull(store.finding("F-0001-a")); assertNotNull(store.finding("F-0003-c"));
        assertEquals(List.of("F-0003-c"), store.investigation("I-0001-tides").findings());
        LibrarianIndex index = new LibrarianIndex(store);
        assertTrue(index.search("Harbour A agrees", 5).stream().noneMatch(h -> h.id().equals("F-0001-a")), "out of the index");
        assertTrue(index.search("IHO constituents", 5).stream().anyMatch(h -> h.id().equals("F-0003-c")), "the kept claim is still found");
        String log = Files.readString(Changes.file(store));
        assertTrue(log.contains("F-0001-a") && log.contains("removed"), log);
        // then the report itself: report only, since its claims are already gone
        Removal.Done gone = Removal.apply(store, Removal.plan(store, "I-0001-tides", Removal.What.all), "person");
        assertEquals(List.of("I-0001-tides"), gone.removed(), "F-0003-c is I-0002's too");
        assertNull(store.investigation("I-0001-tides")); assertNotNull(store.finding("F-0003-c"));
        assertTrue(index.search("tide tables agree", 5).stream().noneMatch(h -> h.id().equals("I-0001-tides")));
        // the protocol: dry shows, then removes; a write patron is needed
        LibraryProtocol p = new LibraryProtocol(store);
        ObjectNode dry = p.remove(patron(M.createObjectNode().put("id", "I-0002-gauges").put("dry", true)));
        assertTrue(dry.path("dry").asBoolean()); assertEquals(2, dry.path("claims_go").size()); assertFalse(dry.has("removed"));
        assertTrue(dry.path("summary").asText().startsWith("Preview: 3 entries would be deleted. Nothing was deleted."), dry.path("summary").asText());
        assertNotNull(store.investigation("I-0002-gauges"));
        ObjectNode r = p.remove(patron(M.createObjectNode().put("id", "I-0002-gauges")));
        assertEquals(3, r.path("removed").size()); assertNull(store.investigation("I-0002-gauges")); assertNull(store.finding("F-0003-c"));
        assertThrows(ProtocolError.class, () -> p.remove(patron(M.createObjectNode().put("id", "I-0002-gauges"))), "gone is gone");
        assertThrows(ProtocolError.class, () -> p.remove(patron(M.createObjectNode().put("id", "F-0004-d").put("what", "sideways"))));
        ObjectNode reader = M.createObjectNode().put("id", "F-0004-d"); reader.putObject("patron").put("did", "did:key:someone").put("name", "s").put("runtime", "mcp");
        Patrons.set(store, "did:key:someone", "Someone", Patrons.Level.read);
        assertThrows(ProtocolError.class, () -> p.remove(reader), "a reader may not remove");
    }

    @Test
    void aClaimNoRunMadeStaysWhenTheReportThatOnlyConfirmedItIsRemoved(@TempDir Path home) throws Exception {
        LibraryStore store = seeded(home);
        // a claim the person added by hand; a later run confirmed it, so the review added its source and listed it in the report
        Finding own = new Finding("F-0005-own", "Harbour E floods at spring tides", List.of("tides--tables"), Finding.State.accepted, Finding.ClaimType.extraction,
                Finding.Confidence.high, "patron:person", Instant.now().toString(), "2026-09-17", Finding.Volatility.stable, "",
                List.of(new Finding.Source("https://example.org/own", "n/a", "the harbour master's notice"),
                        new Finding.Source("https://example.org/again", "n/a", "corroborates, from I-0003-floods")), List.of(), null, "Harbour E floods.\n");
        store.write(own);
        store.write(claim("F-0006-made", "Harbour E's wall was raised in 1998", "I-0003-floods"));
        store.write(new Investigation("I-0003-floods", "Which harbours flood?", Finding.State.accepted, "model:t", Instant.now().toString(),
                List.of("F-0006-made", "F-0005-own"), List.of(), "E floods [F-0005-own]; its wall was raised [F-0006-made].\n"));
        Removal.Plan plan = Removal.plan(store, "I-0003-floods", Removal.What.all);
        assertEquals(List.of("F-0006-made"), plan.claimsGo(), "only the claim the run made");
        assertTrue(plan.claimsStay().contains("F-0005-own") && plan.notFromARun().contains("F-0005-own"), plan.toString());
        assertTrue(Removal.describe(plan).contains("Keep 1 claim(s) that were in the library before this run, which only confirmed them: F-0005-own"), Removal.describe(plan));
        Removal.apply(store, plan, "person");
        assertNull(store.investigation("I-0003-floods")); assertNull(store.finding("F-0006-made"));
        assertNotNull(store.finding("F-0005-own"), "the person's own claim is still there");
        // and a claim's origin is the run that made it, not one that only confirmed it
        assertEquals("", Cataloger.origin(store.finding("F-0005-own")));
        assertEquals("I-0001-tides", Cataloger.origin(store.finding("F-0001-a")));
    }

    static HttpResponse<String> get(HttpClient c, String url, String cookie) throws Exception {
        var b = HttpRequest.newBuilder(URI.create(url)).GET(); if (cookie != null) b.header("Cookie", cookie);
        return c.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
    static HttpResponse<String> post(HttpClient c, String url, String form, String cookie) throws Exception {
        var b = HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form)); if (cookie != null) b.header("Cookie", cookie);
        return c.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void thePageShowsThePlanAsksOnceMoreAndRemovesOnlyOnce(@TempDir Path home) throws Exception {
        LibraryStore store = seeded(home);
        Patrons.set(store, "did:key:me", "Me", Patrons.Level.write);
        String token = Patrons.issueToken(store, "did:key:me");
        LibrarianDaemon d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1);
        HttpClient c = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        String base = "http://127.0.0.1:" + d.port();
        try {
            // the fold is on a report page and on a claim page for someone who may write
            String cookie = Pages.COOKIE + "=" + token;
            var report = get(c, base + "/entry/I-0001-tides", cookie);
            assertTrue(report.body().contains("<summary class=\"k\">Delete…</summary>") && report.body().contains("value=\"claims\""), "the three choices on a report");
            var claimPage = get(c, base + "/entry/F-0004-d", cookie);
            assertTrue(claimPage.body().contains("Delete this claim."), "one choice on a claim");
            // the first post shows the plan and a confirm button with a one-time token; nothing is gone yet
            var plan = post(c, base + "/remove", "id=I-0001-tides&what=all", cookie);
            assertEquals(200, plan.statusCode());
            assertTrue(plan.body().contains("Keep 1 claim(s) that another report also cites: F-0003-c"), plan.body());
            assertNotNull(store.investigation("I-0001-tides"));
            Matcher m = Pattern.compile("name=\"once\" value=\"([0-9a-f]+)\"").matcher(plan.body()); assertTrue(m.find());
            String once = m.group(1);
            // the confirm removes; the same token again removes nothing more (the report is gone, and the token is spent)
            var done = post(c, base + "/remove", "id=I-0001-tides&what=all&confirm=1&once=" + once, cookie);
            assertEquals(200, done.statusCode());
            assertTrue(done.body().contains("Deleted 3 entries. 1 claim was kept because another report cites it.") && done.body().contains("<li>I-0001-tides</li>"), done.body());
            assertNull(store.investigation("I-0001-tides")); assertNull(store.finding("F-0001-a")); assertNotNull(store.finding("F-0003-c"));
            var again = post(c, base + "/remove", "id=I-0002-gauges&what=all&confirm=1&once=" + once, cookie);
            assertTrue(again.body().contains("<button>Delete</button>"), "a spent token shows the plan again instead of deleting");
            assertNotNull(store.investigation("I-0002-gauges"));
            // without a sign-in the fold is not on the page and the post is refused when the sign-in is on
            var anon = get(c, base + "/entry/I-0002-gauges", null);
            assertEquals(200, anon.statusCode());
        } finally { d.stop(); }
    }
}
