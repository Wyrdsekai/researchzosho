package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.tools.Fetch;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/** The launching points: a draft to check, a reading list, a list of questions, a bookmarks export, a meeting transcript. */
class LaunchPointsTest {

    private static final ObjectMapper M = new ObjectMapper();
    private static java.util.function.Supplier<Researcher.Drive> drives;
    private static HttpServer web;
    private static String base;
    private static final Map<String, String> PAGES = new ConcurrentHashMap<>();
    private static java.lang.reflect.Field loopback;

    @BeforeAll static void up() throws Exception {
        drives = Explain.DRIVES; Explain.DRIVES = () -> null;
        loopback = Fetch.class.getDeclaredField("allowLoopback"); loopback.setAccessible(true); loopback.set(null, true);
        web = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        web.createContext("/", x -> {
            String body = PAGES.get(x.getRequestURI().getPath());
            if (body == null) { x.sendResponseHeaders(404, -1); return; }
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            x.getResponseHeaders().set("Content-Type", "text/html");
            x.sendResponseHeaders(200, b.length);
            try (var o = x.getResponseBody()) { o.write(b); }
        });
        web.start();
        base = "http://127.0.0.1:" + web.getAddress().getPort();
        PAGES.put("/harrison", "<html><head><title>John Harrison and the chronometer</title></head><body><p>John Harrison built the H4 marine chronometer in 1759; it lost five seconds on the voyage to Jamaica. The Board of Longitude paid him in stages.</p></body></html>");
        PAGES.put("/saros", "<html><head><title>The Saros dial</title></head><body><p>The back dial counts 223 lunar months, the Saros period, and predicts eclipses over 18 years.</p></body></html>");
    }
    @AfterAll static void down() throws Exception { Explain.DRIVES = drives; web.stop(0); loopback.set(null, false); }

    static LibraryStore library(Path home) throws Exception {
        LibraryStore store = new LibraryStore(home.resolve("lib")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        return store;
    }
    static ObjectNode person(ObjectNode a) { a.putObject("patron").put("did", "person").put("name", "keeper").put("runtime", "cli"); return a; }

    @Test
    void aDraftYieldsClaimsCitationsAndQuestions(@TempDir Path home) throws Exception {
        LibraryStore store = library(home);
        Path f = home.resolve("chapter.md");
        Files.writeString(f, "# Longitude\n\nJohn Harrison's H4 chronometer lost only five seconds on its 1761 voyage to Jamaica (" + base + "/harrison). The Board of Longitude paid 20,000 pounds in full in 1765. Was the prize ever formally awarded? See also doi:10.1000/xyz123 and " + base + "/missing.\n");
        ObjectNode r = new LibraryProtocol(store).check(person(M.createObjectNode().put("path", f.toString())));
        assertEquals("Longitude", r.path("title").asText());
        assertTrue(r.path("claims_to_check").size() >= 2, r.path("claims_to_check").toString());
        assertTrue(r.path("claims_to_check").toString().contains("five seconds"));
        assertEquals(List.of("Was the prize ever formally awarded?"), List.of(r.path("questions_filed").get(0).asText()));
        assertEquals(3, r.path("citations").size(), r.path("citations").toString());
        var byLoc = new java.util.HashMap<String, String>();
        for (var c : r.path("citations")) byLoc.put(c.path("locator").asText(), c.path("state").asText());
        assertEquals("shelved", byLoc.get(base + "/harrison"));
        assertEquals("requested", byLoc.get(base + "/missing"), "a 404 becomes a source request");
        assertTrue(byLoc.containsKey("https://doi.org/10.1000/xyz123"), byLoc.toString());
        assertTrue(Requests.open(store).stream().anyMatch(q -> q.locator().equals(base + "/missing")), "the request is on file");
        Path raw = store.rawDir().resolve(r.path("raw").asText());
        assertTrue(RawCapture.read(raw)[0].startsWith("draft://") && "drafts".equals(RawCapture.collectionOf(raw)));
        assertEquals("drafts", RawCapture.collectionOf(RawCapture.find(store, base + "/harrison")), "the citation is shelved with the draft");
        assertEquals(0, store.scanFindings().findings().size());
        assertTrue(r.path("summary").asText().contains("Nothing is saved as a claim."), r.path("summary").asText());
        // verify files the run, with the claims numbered
        ObjectNode v = new LibraryProtocol(store).check(person(M.createObjectNode().put("path", f.toString()).put("verify", true).put("fetch_citations", false)));
        String job = v.path("verify_job_id").asText();
        var filed = new Jobs(store, j -> { throw new IllegalStateException(); }).active().stream().filter(j -> j.path("job_id").asText().equals(job)).findFirst().orElseThrow();
        assertTrue(filed.path("args").path("question").asText().startsWith("Check each of these claims") && filed.path("args").path("question").asText().contains("draft"));
        assertEquals(0, v.path("citations").size(), "fetch_citations=false");
    }

    @Test
    void aReadingListInItsShapesIsFetchedOntoTheShelves(@TempDir Path home) throws Exception {
        LibraryStore store = library(home);
        List<Reading.Entry> bib = Reading.parse("""
                @book{sobel1995, title={Longitude: The True Story}, author={Sobel, Dava}, year={1995}, url={%s/harrison}}
                @article{freeth2006, title = "Decoding the Antikythera Mechanism", author = {Freeth, Tony and Bitsakis, Yanis}, journal = {Nature}, year = 2006, doi = {10.1038/nature05357}}
                @misc{notes, title={Some notes}}
                """.formatted(base));
        assertEquals(3, bib.size());
        assertEquals("Longitude: The True Story", bib.get(0).title()); assertEquals(base + "/harrison", bib.get(0).locator()); assertEquals("Sobel, Dava, 1995", bib.get(0).note());
        assertEquals("https://doi.org/10.1038/nature05357", bib.get(1).locator()); assertEquals("Freeth, Tony et al., 2006, Nature", bib.get(1).note());
        assertFalse(bib.get(2).located());
        List<Reading.Entry> ris = Reading.parse("TY  - JOUR\nTI  - The Saros dial\nAU  - Marchant, Jo\nPY  - 2008\nUR  - " + base + "/saros\nER  - \n\nTY  - BOOK\nTI  - Cosmos\nAU  - Sagan, Carl\nER  - \n");
        assertEquals(2, ris.size()); assertEquals(base + "/saros", ris.get(0).locator()); assertEquals("Marchant, Jo, 2008", ris.get(0).note()); assertFalse(ris.get(1).located());
        List<Reading.Entry> csv = Reading.parse("Title,Author,DOI,Url\nLongitude,Dava Sobel,,%s/harrison\nDecoding,Tony Freeth,10.1038/nature05357,\n".formatted(base));
        assertEquals(base + "/harrison", csv.get(0).locator()); assertEquals("https://doi.org/10.1038/nature05357", csv.get(1).locator());
        List<Reading.Entry> plain = Reading.parse("- " + base + "/saros — the dial\n- doi:10.1038/nature05357\n- Cosmos — Carl Sagan\n");
        assertEquals(base + "/saros", plain.get(0).locator()); assertEquals("the dial", plain.get(0).title());
        assertEquals("https://doi.org/10.1038/nature05357", plain.get(1).locator()); assertEquals("Cosmos", plain.get(2).title()); assertFalse(plain.get(2).located());

        Path f = home.resolve("reading.txt"); Files.writeString(f, "- " + base + "/harrison — Sobel\n- " + base + "/saros\n- " + base + "/gone\n- Cosmos — Carl Sagan\n");
        ObjectNode r = new LibraryProtocol(store).reading(person(M.createObjectNode().put("path", f.toString()).put("watch", true)));
        assertEquals("reading", r.path("collection").asText());
        assertEquals(4, r.path("entries_found").asInt()); assertEquals(2, r.path("shelved").asInt()); assertEquals(1, r.path("requested").asInt()); assertEquals(1, r.path("no_locator").asInt());
        assertEquals(List.of("Cosmos"), List.of(r.path("titles_without_locator").get(0).asText()));
        assertTrue(r.path("watching").asBoolean());
        assertEquals("reading", RawCapture.collectionOf(RawCapture.find(store, base + "/saros")));
        assertTrue(r.path("next").asText().contains("collections: [\"reading\"]") && r.path("next").asText().contains("library_items"), r.path("next").asText());
        // the list is registered as urls and the housekeeping re-reads it; a changed page gets a new capture on a later day (same day: unchanged)
        assertTrue(Corpus.registered(store).get("reading").toString().startsWith("urls:"), Corpus.registered(store).toString());
        String scan = Corpus.rescan(store);
        assertTrue(scan.contains("reading: 3 url(s) re-read, 0 changed, 2 unchanged, 1 not readable"), scan);
    }

    @Test
    void questionsGoOntoTheFrontierInOrderOrOutAsRuns(@TempDir Path home) throws Exception {
        LibraryStore store = library(home);
        Path f = home.resolve("syllabus.md"); Files.writeString(f, "# Week 1\n\n1. How did Harrison's H4 keep time at sea?\n2. Why did the Board of Longitude delay the prize?\n- Not a question at all.\n3. Compare H4 with the lunar distance method.\nWhat did Maskelyne argue?\n");
        List<String> qs = Questions.parse(Files.readString(f));
        assertEquals(4, qs.size(), qs.toString());
        assertEquals("Compare H4 with the lunar distance method.", qs.get(2));
        LibraryProtocol p = new LibraryProtocol(store);
        ObjectNode r = p.questions(person(M.createObjectNode().put("path", f.toString())));
        assertEquals(4, r.path("questions_filed").asInt());
        List<Frontier.Line> open = Frontier.read(store);
        assertEquals(4, open.size());
        assertEquals("How did Harrison's H4 keep time at sea?", open.get(0).text(), "in order");
        assertEquals("person", Frontier.typeOf(open.get(0)));
        assertEquals(4, p.questions(person(M.createObjectNode().put("path", f.toString()))).path("questions_already_open").asInt());
        ObjectNode runs = p.questions(person(M.createObjectNode().put("text", "Is the Metonic cycle 19 years?\nWho built the Antikythera mechanism?\n").put("as", "runs")));
        assertEquals(2, runs.path("jobs").size(), runs.toString());
        assertEquals(0, runs.path("questions_filed").asInt());
        assertEquals(4, Frontier.read(store).size(), "runs are not also filed");
    }

    @Test
    void bookmarksInTheirShapesAreFetchedAndWatched(@TempDir Path home) throws Exception {
        LibraryStore store = library(home);
        String netscape = "<!DOCTYPE NETSCAPE-Bookmark-file-1>\n<DL><p>\n<DT><H3 ADD_DATE=\"1\">Clocks</H3>\n<DL><p>\n<DT><A HREF=\"" + base + "/harrison\" ADD_DATE=\"2\">Harrison &amp; the chronometer</A>\n</DL><p>\n<DT><H3>Astronomy</H3>\n<DL><p>\n<DT><A HREF=\"" + base + "/saros\">Saros</A>\n<DT><A HREF=\"" + base + "/gone\">Gone</A>\n</DL><p>\n</DL><p>\n";
        List<Bookmarks.Mark> marks = Bookmarks.parse(netscape);
        assertEquals(3, marks.size());
        assertEquals("Harrison & the chronometer", marks.get(0).title()); assertEquals("Clocks", marks.get(0).folder()); assertEquals("Astronomy", marks.get(1).folder());
        String chrome = "{\"roots\":{\"bookmark_bar\":{\"children\":[{\"name\":\"Saros\",\"type\":\"url\",\"url\":\"" + base + "/saros\"},{\"name\":\"Clocks\",\"type\":\"folder\",\"children\":[{\"name\":\"H4\",\"type\":\"url\",\"url\":\"" + base + "/harrison\"}]}],\"name\":\"Bookmarks bar\",\"type\":\"folder\"}}}";
        List<Bookmarks.Mark> ch = Bookmarks.parse(chrome);
        assertEquals(2, ch.size()); assertEquals("Clocks", ch.get(1).folder()); assertEquals("Bookmarks bar", ch.get(0).folder());
        assertEquals(base + "/saros", Bookmarks.parse("- " + base + "/saros — the dial\n").get(0).url());

        Path f = home.resolve("bookmarks.html"); Files.writeString(f, netscape);
        LibraryProtocol p = new LibraryProtocol(store);
        ObjectNode r = p.bookmarks(person(M.createObjectNode().put("path", f.toString()).put("folder", "Astronomy").put("watch", true)));
        assertEquals(2, r.path("bookmarks_found").asInt()); assertEquals(1, r.path("shelved").asInt()); assertEquals(1, r.path("requested").asInt());
        assertEquals("astronomy", r.path("collection").asText());
        assertEquals("astronomy", RawCapture.collectionOf(RawCapture.find(store, base + "/saros")));
        assertTrue(Corpus.registered(store).get("astronomy").toString().startsWith("urls:"));
        ObjectNode all = p.bookmarks(person(M.createObjectNode().put("path", f.toString())));
        assertEquals(3, all.path("bookmarks_found").asInt()); assertEquals("bookmarks", all.path("collection").asText());
        assertEquals("already held", all.path("bookmarks").get(1).path("state").asText(), all.path("bookmarks").toString());
    }

    @Test
    void aMeetingKeepsDecisionsFilesQuestionsAndListsClaimsBySpeaker(@TempDir Path home) throws Exception {
        LibraryStore store = library(home);
        String vtt = """
                WEBVTT

                1
                00:00:01.000 --> 00:00:05.000
                <v Ada>The H4 lost five seconds on the Jamaica voyage in 1761. Should we cite Sobel for that?</v>

                2
                00:00:06.000 --> 00:00:12.000
                <v Brook>Yes. We will use the 1995 edition. The Board of Longitude paid 8,750 pounds in 1765.</v>

                3
                00:00:13.000 --> 00:00:15.000
                <v Brook>Action: Ada owns the chronometer section by Friday.</v>
                """;
        Meetings.Transcript t = Meetings.parse(vtt, "Editorial sync");
        assertEquals(List.of("Ada", "Brook"), t.speakers());
        assertEquals(2, t.turns().size(), "Brook's two cues are one turn");
        assertEquals(List.of("Should we cite Sobel for that?"), Meetings.questions(t));
        List<String> decisions = Meetings.decisions(t);
        assertEquals(2, decisions.size(), decisions.toString());
        assertTrue(decisions.get(0).startsWith("Brook: We will use the 1995 edition"), decisions.toString());
        assertTrue(decisions.get(1).contains("by Friday"));
        List<String> claims = Meetings.claims(t, null);
        assertTrue(claims.stream().anyMatch(c -> c.startsWith("Ada said: The H4 lost five seconds")), claims.toString());
        assertTrue(claims.stream().anyMatch(c -> c.startsWith("Brook said: The Board of Longitude paid")), claims.toString());
        assertFalse(claims.stream().anyMatch(c -> c.contains("We will use")), "a decision is not a claim to check: " + claims);
        Meetings.Transcript colon = Meetings.parse("[00:01] Ada: Is the Saros 223 months?\nBrook: It is, 223 lunar months.\n", "Quick call");
        assertEquals(List.of("Ada", "Brook"), colon.speakers());
        assertEquals("Is the Saros 223 months?", colon.turns().get(0).text());

        Path f = home.resolve("sync.vtt"); Files.writeString(f, vtt);
        ObjectNode r = new LibraryProtocol(store).meeting(person(M.createObjectNode().put("path", f.toString()).put("verify", true)));
        assertEquals("sync", r.path("title").asText());
        assertEquals(1, r.path("questions_filed").size()); assertEquals(2, r.path("decisions").size());
        Path raw = store.rawDir().resolve(r.path("raw").asText());
        String body = RawCapture.read(raw)[2];
        assertTrue(body.contains("## Decisions") && body.contains("Speakers: Ada, Brook") && body.contains("**Ada:**"), body);
        assertEquals("meetings", RawCapture.collectionOf(raw));
        assertTrue(r.path("verify_job_id").asText().startsWith("J-"));
        assertTrue(Frontier.read(store).get(0).kind().contains("meeting: "), Frontier.read(store).get(0).kind());
        assertEquals(0, store.scanFindings().findings().size());
        // the chat and the MCP server offer all five
        for (String t5 : List.of("library_check", "library_reading", "library_questions", "library_bookmarks", "library_meeting")) {
            assertTrue(Librarian.TOOLS.contains(t5), t5);
            boolean served = false; for (var tool : org.researchzosho.mcp.McpServer.allTools()) if (tool.path("name").asText().equals(t5)) served = true;
            assertTrue(served, t5);
        }
    }
}
