package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The library protocol, contract 1.0 — what a patron runtime can rely on. */
class LibraryProtocolTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static LibraryStore shelf(Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp);
        store.init();
        Finding accepted = new Finding("F-0001-keigo-has-no-english-equivalent", "Keigo has no direct English equivalent",
                List.of("japanese--keigo"), Finding.State.accepted, Finding.ClaimType.extraction, Finding.Confidence.high,
                "person", Instant.now().toString(), "2026-09-01", Finding.Volatility.stable, "",
                List.of(new Finding.Source("https://example.org/hosaka2016", "Hosaka 2016", "the source")),
                List.of(), new Finding.Review(1, "person", "accept", "sha256:0", Instant.now().toString()),
                "Japanese honorific register (keigo) has no direct English equivalent; translators dissolve or foreignize it.\n");
        Finding disputed = new Finding("F-0002-subtitlers-should-always-dissolve-keigo", "Subtitlers should always dissolve keigo",
                List.of("japanese--keigo", "translation--strategy"), Finding.State.disputed, Finding.ClaimType.interpretation,
                Finding.Confidence.low, "model:test", Instant.now().toString(), "2026-09-01", Finding.Volatility.slow, "",
                List.of(new Finding.Source("https://example.org/blog", "n/a", "a blog")), List.of(), null,
                "Subtitlers should always dissolve keigo into neutral English register.\n");
        store.write(accepted); store.write(disputed);
        Files.writeString(store.subjectsFile(), "# Subjects\n\n- japanese--keigo — the honorific system\n- translation--strategy — domestication, foreignization\n", StandardCharsets.UTF_8);
        Files.createDirectories(store.rawDir());
        Files.writeString(store.rawDir().resolve("2026-09-01-abc123.md"),
                "---\nurl: https://example.org/hosaka2016\ntitle: Hosaka 2016 on keigo\nfetched_at: 2026-09-01T10:00:00Z\nfetched_by: test\n---\n"
                + "敬語 keigo in subtitling: the full text of the paper.\n", StandardCharsets.UTF_8);
        store.frontier("gap", "How do Korean honorifics compare to keigo in subtitling?");
        new LibrarianIndex(store).rebuild();
        return store;
    }

    private static ObjectNode args(String json) throws Exception { return (ObjectNode) M.readTree(json); }

    @Test
    void identityIsMintedOnceAndSurvivesARename(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("first")); store.init();
        var id = store.identity();
        assertTrue(id.id().startsWith("lib_"), id.id());
        assertEquals(id.id(), store.identity().id());
        Files.move(tmp.resolve("first"), tmp.resolve("renamed"));
        var moved = new LibraryStore(tmp.resolve("renamed")).identity();
        assertEquals(id.id(), moved.id());
        assertEquals("renamed", moved.name());
    }

    @Test
    void everyResultCarriesProvenanceAndEntriesAreSelfDescribing(@TempDir Path tmp) throws Exception {
        var p = new LibraryProtocol(shelf(tmp));
        ObjectNode r = p.ask(args("{\"question\":\"keigo English equivalent\",\"patron\":{\"did\":\"did:key:z1\",\"name\":\"A\",\"runtime\":\"wyrdsekai\"}}"));
        assertEquals(LibraryProtocol.CONTRACT, r.get("contract").asText());
        assertTrue(r.get("library_id").asText().startsWith("lib_"));
        assertFalse(r.get("holds_nothing").asBoolean());
        var e = r.get("entries").get(0);
        for (String k : new String[]{"id", "kind", "state", "claim_type", "confidence", "writer", "recorded_at", "sources"}) {
            assertTrue(e.has(k), "entry carries " + k);
        }
        assertEquals("Hosaka 2016", e.get("sources").get(0).get("edition").asText());
        // no edition on the wire is null, never "n/a"
        ObjectNode g = p.get(args("{\"id\":\"F-0002-subtitlers-should-always-dissolve-keigo\"}"));
        assertTrue(g.get("entry").get("sources").get(0).get("edition").isNull());
        assertEquals(1, r.get("open_threads").size(), "the frontier thread touching keigo");
        // circulation named the patron
        assertTrue(Files.readString(p.store().circulationFile()).contains("did:key:z1"));
    }

    @Test
    void anEmptyAnswerIsNeverPadded(@TempDir Path tmp) throws Exception {
        var p = new LibraryProtocol(shelf(tmp));
        ObjectNode r = p.ask(args("{\"question\":\"quantum chromodynamics lattice\"}"));
        assertTrue(r.get("holds_nothing").asBoolean());
        assertEquals(0, r.get("entries").size());
        // one matching word in a six-word question is NOT a holding (Wyrdsekai's zebra crossings)
        ObjectNode weak = p.ask(args("{\"question\":\"zebra crossings on the moon with keigo in 1740\"}"));
        assertTrue(weak.get("holds_nothing").asBoolean(), "a one-term match must not fill the package");
        assertEquals("not_established", p.established(args("{\"claim\":\"zebra crossings on the moon with keigo in 1740\"}")).get("verdict").asText());
        // but plain search still surfaces the weak hit, scored low
        assertFalse(p.search(args("{\"query\":\"zebra crossings on the moon with keigo in 1740\"}")).get("hits").isEmpty());
        // and a question that really is about the holding still lands
        assertFalse(p.ask(args("{\"question\":\"keigo English equivalent\"}")).get("holds_nothing").asBoolean());
    }

    @Test
    void searchPagesWithACursorAndGetReturnsTheFullEntry(@TempDir Path tmp) throws Exception {
        var p = new LibraryProtocol(shelf(tmp));
        ObjectNode page1 = p.search(args("{\"query\":\"keigo\",\"k\":1}"));
        assertEquals(1, page1.get("hits").size());
        assertFalse(page1.get("next_cursor").isNull());
        ObjectNode page2 = p.search(args("{\"query\":\"keigo\",\"k\":1,\"cursor\":\"" + page1.get("next_cursor").asText() + "\"}"));
        assertNotEquals(page1.get("hits").get(0).get("id").asText(), page2.get("hits").get(0).get("id").asText());
        assertTrue(page1.get("hits").get(0).has("subjects"));
        ObjectNode g = p.get(args("{\"id\":\"F-0001-keigo-has-no-english-equivalent\"}"));
        var e = g.get("entry");
        assertEquals("accept", e.get("review").get("decision").asText());
        assertTrue(e.has("superseded_by"));
        assertEquals(1, e.get("related").size(), "F-0002 shares japanese--keigo");
        var nf = assertThrows(ProtocolError.class, () -> p.get(args("{\"id\":\"F-9999-nope\"}")));
        assertEquals("not_found", nf.code);
    }

    @Test
    void readReturnsTheVerbatimTextBehindALocator(@TempDir Path tmp) throws Exception {
        var p = new LibraryProtocol(shelf(tmp));
        ObjectNode r = p.read(args("{\"locator\":\"https://example.org/hosaka2016\",\"max_chars\":12}"));
        assertEquals("Hosaka 2016 on keigo", r.get("title").asText());
        assertEquals("2026-09-01T10:00:00Z", r.get("captured_at").asText());
        assertTrue(r.get("edition").isNull(), "a capture date is not an edition");
        assertTrue(r.get("truncated").asBoolean());
        assertEquals(12, r.get("text").asText().length());
        assertEquals("not_found", assertThrows(ProtocolError.class, () -> p.read(args("{\"locator\":\"https://nowhere.example\"}"))).code);
        // the same body through the resource path
        assertTrue(p.resourcesRead("raw://https://example.org/hosaka2016").get("contents").get(0).get("text").asText().contains("敬語"));
    }

    @Test
    void establishedVerdictComesFromStates(@TempDir Path tmp) throws Exception {
        var p = new LibraryProtocol(shelf(tmp));
        // the raw document says "keigo" too; the verdict's search must see findings only
        for (var h : new LibrarianIndex(p.store()).search("keigo", 10, null, "finding")) assertEquals("finding", h.kind());
        assertTrue(new LibrarianIndex(p.store()).search("keigo", 10).stream().anyMatch(h -> "raw".equals(h.kind())), "the unfiltered search does reach raw");
        assertEquals("disputed", p.established(args("{\"claim\":\"subtitlers should dissolve keigo\"}")).get("verdict").asText());
        assertEquals("not_established", p.established(args("{\"claim\":\"lattice quantum chromodynamics\"}")).get("verdict").asText());
        // retire the dispute → the accepted entry alone decides
        var store = p.store();
        Finding d = store.finding("F-0002-subtitlers-should-always-dissolve-keigo");
        store.write(new Finding(d.id(), d.title(), d.subjects(), Finding.State.retired, d.claimType(), d.confidence(), d.writer(),
                d.recordedAt(), d.validAsOf(), d.volatility(), d.reviewBy(), d.sources(), d.supersedes(), d.review(), d.body()));
        new LibrarianIndex(store).rebuild();
        assertEquals("established", p.established(args("{\"claim\":\"keigo has no English equivalent\"}")).get("verdict").asText());
        // a draft is listed as unreviewed and never moves the verdict
        Patrons.set(store, "did:key:zW", "W", Patrons.Level.write);
        p.submit(args("{\"claim\":\"Vietnamese honorific particles behave like keigo in subtitles.\",\"sources\":[\"https://example.org/v\"],\"patron\":{\"did\":\"did:key:zW\"}}"));
        ObjectNode v = p.established(args("{\"claim\":\"Vietnamese particles\"}"));
        assertEquals(1, v.get("unreviewed").size());
        assertEquals("not_established", v.get("verdict").asText());
    }

    @Test
    void submitNeedsSourcesAndWriteAccess(@TempDir Path tmp) throws Exception {
        var p = new LibraryProtocol(shelf(tmp));
        String patron = "\"patron\":{\"did\":\"did:key:zW\",\"name\":\"W\",\"runtime\":\"wyrdsekai\"}";
        // default policy is read: a named patron may not write until listed
        var fb = assertThrows(ProtocolError.class, () -> p.submit(args("{\"claim\":\"Korean honorifics are handled the same way in subtitles.\",\"sources\":[\"https://example.org/k\"]," + patron + "}")));
        assertEquals("forbidden", fb.code);
        Patrons.set(p.store(), "did:key:zW", "W", Patrons.Level.write);
        var ns = assertThrows(ProtocolError.class, () -> p.submit(args("{\"claim\":\"Korean honorifics are handled the same way in subtitles.\",\"sources\":[]," + patron + "}")));
        assertEquals("no_sources", ns.code);
        ObjectNode ok = p.submit(args("{\"claim\":\"Korean honorifics are handled the same way in subtitles.\",\"claim_type\":\"interpretation\","
                + "\"sources\":[{\"locator\":\"https://example.org/k\",\"edition\":\"Kim 2020\"}]," + patron + "}"));
        assertEquals("draft", ok.get("state").asText());
        Finding f = p.store().finding(ok.get("id").asText());
        assertEquals("patron:did:key:zW", f.writer());
        assertEquals("Kim 2020", f.sources().get(0).edition());
        assertEquals(Finding.State.draft, f.state());
        assertEquals("invalid_args", assertThrows(ProtocolError.class,
                () -> p.submit(args("{\"claim\":\"Korean honorifics are handled the same way in subtitles.\",\"claim_type\":\"guess\",\"sources\":[\"x\"]," + patron + "}"))).code);
    }

    @Test
    void denyListAndDefaultAreHonoured(@TempDir Path tmp) throws Exception {
        var p = new LibraryProtocol(shelf(tmp));
        Patrons.set(p.store(), "did:key:bad", "turned away", Patrons.Level.deny);
        assertEquals("forbidden", assertThrows(ProtocolError.class,
                () -> p.status(args("{\"patron\":{\"did\":\"did:key:bad\"}}"))).code);
        assertNotNull(p.status(args("{}")).get("counts"));   // anonymous reads under default: read
        Patrons.setDefault(p.store(), Patrons.Level.deny);
        assertEquals("forbidden", assertThrows(ProtocolError.class, () -> p.status(args("{}"))).code);
        // the file round-trips through the editor and the loader
        var pol = Patrons.load(p.store());
        assertEquals(Patrons.Level.deny, pol.dflt());
        assertEquals("turned away", pol.listed().get(0).name());
    }

    @Test
    void frontierSubjectsAndStatus(@TempDir Path tmp) throws Exception {
        var p = new LibraryProtocol(shelf(tmp));
        Patrons.set(p.store(), "did:key:zW", "W", Patrons.Level.write);
        ObjectNode added = p.frontier(args("{\"op\":\"add\",\"question\":\"Does Vietnamese have a keigo analogue?\",\"patron\":{\"did\":\"did:key:zW\"}}"));
        assertTrue(added.get("filed").asBoolean());
        ObjectNode list = p.frontier(args("{}"));
        assertEquals(2, list.get("questions").size());
        assertTrue(list.get("questions").get(1).get("kind").asText().contains("did:key:zW"));
        ObjectNode subs = p.subjects(args("{}"));
        var byId = new java.util.HashMap<String, com.fasterxml.jackson.databind.JsonNode>();
        for (var s : subs.get("subjects")) byId.put(s.get("id").asText(), s);
        assertEquals("japanese", byId.get("japanese--keigo").get("broader").asText());
        assertEquals("japanese", byId.get("japanese--keigo").get("facet").asText());
        assertEquals("japanese", byId.get("japanese").get("facet").asText());
        assertEquals(2, byId.get("japanese--keigo").get("count").asInt());
        assertEquals("japanese--keigo", byId.get("japanese").get("narrower").get(0).asText());
        ObjectNode st = p.status(args("{}"));
        assertEquals(2, st.get("counts").get("finding").get("total").asInt());
        assertEquals(1, st.get("counts").get("finding").get("accepted").asInt());
        assertEquals(1, st.get("counts").get("raw").asInt());
        assertFalse(st.get("last_updated").asText().isEmpty());
    }

    @Test
    void changesAreRecallNotices(@TempDir Path tmp) throws Exception {
        var p = new LibraryProtocol(shelf(tmp));
        ObjectNode all = p.changes(args("{}"));
        assertEquals(LibraryProtocol.CONTRACT, all.get("contract").asText());
        assertEquals(2, all.get("changes").size(), "two findings were added");
        assertEquals("added", all.get("changes").get(0).get("event").asText());
        String cursor = all.get("next_cursor").asText();
        new Council(p.store()).retire("F-0002-subtitlers-should-always-dissolve-keigo");
        ObjectNode later = p.changes(args("{\"since\":\"" + cursor + "\"}"));
        assertEquals(1, later.get("changes").size());
        assertEquals("state:disputed→retired", later.get("changes").get(0).get("event").asText());
        assertEquals("F-0002-subtitlers-should-always-dissolve-keigo", later.get("changes").get(0).get("id").asText());
        assertFalse(later.get("more").asBoolean());
        assertEquals("invalid_args", assertThrows(ProtocolError.class, () -> p.changes(args("{\"since\":\"x\"}"))).code);
    }

    @Test
    void resourcesListPagesAndReadMatchesGet(@TempDir Path tmp) throws Exception {
        var p = new LibraryProtocol(shelf(tmp));
        ObjectNode all = p.resourcesList(null);
        assertEquals(3, all.get("resources").size());   // 2 findings + 1 raw
        assertEquals("finding://F-0001-keigo-has-no-english-equivalent", all.get("resources").get(0).get("uri").asText());
        String text = p.resourcesRead("finding://F-0001-keigo-has-no-english-equivalent").get("contents").get(0).get("text").asText();
        assertTrue(text.contains("Keigo has no direct English equivalent"));
        assertEquals("not_found", assertThrows(ProtocolError.class, () -> p.resourcesRead("finding://F-0000-x")).code);
        assertEquals(3, p.resourceTemplates().get("resourceTemplates").size());
    }
}
