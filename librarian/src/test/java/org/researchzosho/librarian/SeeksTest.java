package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Seeks (0.6.6): a seek runs on its own copy of the shelves and leaves the library clean; the merge sorts the seeks' claims into agreed
 * (filed, with every seek's sources), disputed (filed as such, with the question on the frontier) and found by one (listed, not filed);
 * a group is dropped whole, and the merged report stays.
 */
class SeeksTest {

    static final ObjectMapper M = new ObjectMapper();
    static final String PROBE_ANSWER = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"hi\"}}]}";

    @AfterEach void restore() { Seeks.EMBEDDER = Embeddings::configured; }

    static Finding claim(LibraryStore store, String title, String body, String source) throws Exception {
        String id = store.nextFindingId(title);
        Finding f = new Finding(id, title, List.of(), Finding.State.draft, Finding.ClaimType.extraction, Finding.Confidence.medium, "model:test", Instant.now().toString(), "2026-10-21",
                Finding.Volatility.stable, "", List.of(new Finding.Source(source, "n/a", "s")), List.of(), null, body);
        store.write(f);
        return f;
    }

    static HttpResponse<String> post(HttpClient c, String url, String json) throws Exception {
        return c.send(HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json)).build(), HttpResponse.BodyHandlers.ofString());
    }

    static JsonNode awaitDone(HttpClient c, String base, String jobId) throws Exception {
        JsonNode job = null;
        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline) {
            job = M.readTree(c.send(HttpRequest.newBuilder(URI.create(base + "/v1/jobs/" + jobId)).GET().build(), HttpResponse.BodyHandlers.ofString()).body());
            String st = job.path("state").asText();
            if (st.equals("done") || st.equals("failed") || st.equals("stopped")) break;
            Thread.sleep(100);
        }
        assertNotNull(job);
        assertEquals("done", job.path("state").asText(), job.toString());
        return job;
    }

    static long count(Path dir) throws Exception { if (!Files.isDirectory(dir)) return 0; try (Stream<Path> s = Files.list(dir)) { return s.count(); } }

    @Test
    void aSeekRunsOnItsOwnShelvesAndTheMergeFilesWhatTheSeeksAgreeOn(@TempDir Path tmp) throws Exception {
        Seeks.EMBEDDER = Embeddings::none;
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Finding before = claim(store, "The sky over the workshop was clear", "A weather note.", "https://example.org/weather");
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        Patrons.setDefault(store, Patrons.Level.write);
        HttpServer probe = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        probe.createContext("/", x -> { byte[] b = PROBE_ANSWER.getBytes(StandardCharsets.UTF_8); x.sendResponseHeaders(200, b.length); try (OutputStream o = x.getResponseBody()) { o.write(b); } });
        probe.start();
        String driveUrl = "http://127.0.0.1:" + probe.getAddress().getPort();
        LibrarianDaemon d = LibrarianDaemon.start(store, "127.0.0.1", 0, driveUrl, "scripted", -1);
        d.researcherFactory = x -> { ResearcherTest.ScriptedDrive s = new ResearcherTest.ScriptedDrive(); s.criticWantsMore = false; return new Researcher(s, new ResearcherTest.FakeTools(), null, 2); };
        d.judgeFactory = x -> new Researcher.Drive() {
            @Override public ObjectNode chat(ArrayNode messages, ArrayNode tools, int maxTokens, String toolChoice) { return null; }
            @Override public String classify(ArrayNode messages, int maxTokens) {
                String p = messages.get(messages.size() - 1).path("content").asText();
                return p.contains("\nA: ") && p.contains("hand") ? "SAME\nBoth say the gears were cut by hand." : "DIFFERENT\nnot alike";
            }
            @Override public int contextWindow() { return 8192; }
            @Override public String prose(ArrayNode messages, int maxTokens) { return "Merged answer: both seeks found the gears were cut by hand."; }
        };
        HttpClient c = HttpClient.newHttpClient();
        String base = "http://127.0.0.1:" + d.port();
        try {
            String ask = "{\"question\":\"How were the Antikythera gears cut?\",\"sub_questions\":[\"how were they cut?\",\"by whom?\"],\"seek\":\"g1\"}";
            assertEquals(400, post(c, base + "/v1/research", ask.replace("\"g1\"", "\"Bad Name!\"")).statusCode(), "a group's name is checked");
            // the first seek: on its own shelves, seeded with the library's, and the library untouched
            var filed = post(c, base + "/v1/research", ask);
            assertEquals(200, filed.statusCode(), filed.body());
            String j1 = M.readTree(filed.body()).path("job_id").asText();
            JsonNode job = awaitDone(c, base, j1);
            assertTrue(job.path("result").asText().startsWith("seek g1: investigation I-"), job.toString());
            assertTrue(job.path("investigation").asText("").isEmpty(), "the run's report is not on the library's shelves: " + job);
            assertEquals(0, count(store.investigationsDir()), "the library has no report from the seek");
            assertEquals(1, count(store.findingsDir()), "and no finding from it");
            Path scratch = store.root().resolve("seeks").resolve("g1").resolve(j1);
            assertTrue(Files.isRegularFile(scratch.resolve("findings").resolve(before.id() + ".md")), "the seek's shelves started as a copy of the library's");
            // the index was copied, not rebuilt: the library's segment files (immutable, named _0.…) are in the seek's index; the segments_N file is renamed by every commit
            try (Stream<Path> s = Files.list(store.luceneDir())) {
                List<String> segments = s.map(p -> p.getFileName().toString()).filter(n -> n.startsWith("_")).toList();
                assertFalse(segments.isEmpty(), "the library's index has a segment");
                for (String n : segments) assertTrue(Files.exists(scratch.resolve(".index").resolve(n)), "the library's index file " + n + " was copied, not rebuilt");
            }
            assertEquals(1, count(scratch.resolve("investigations")), "the seek's report is on its own shelves");
            JsonNode list = M.readTree(post(c, base + "/v1/seeks", "{\"op\":\"list\"}").body());
            JsonNode g = list.path("groups").get(0);
            assertEquals("g1", g.path("group").asText()); assertEquals("How were the Antikythera gears cut?", g.path("question").asText());
            JsonNode s1 = g.path("seeks").get(0);
            assertEquals(j1, s1.path("job_id").asText()); assertEquals("done", s1.path("state").asText()); assertEquals("scripted", s1.path("model").asText()); assertEquals("both", s1.path("sources").asText());
            assertTrue(s1.path("investigation").asText().startsWith("I-"), s1.toString());
            JsonNode read = M.readTree(post(c, base + "/v1/seeks", "{\"op\":\"read\",\"group\":\"g1\",\"job_id\":\"" + j1 + "\"}").body());
            assertTrue(read.path("body").asText().contains("cut by hand"), read.toString());
            assertTrue(read.path("findings").isArray(), read.toString());
            var early = post(c, base + "/v1/seeks", "{\"op\":\"merge\",\"group\":\"g1\"}");
            assertEquals(400, early.statusCode(), "one seek cannot be merged: " + early.body());
            assertEquals(404, post(c, base + "/v1/seeks", "{\"op\":\"read\",\"group\":\"g1\"}").statusCode(), "no merged report yet");
            // the second seek, then the merge: the claim both made is filed once, with the seeks named
            String j2 = M.readTree(post(c, base + "/v1/research", ask).body()).path("job_id").asText();
            awaitDone(c, base, j2);
            var merge = post(c, base + "/v1/seeks", "{\"op\":\"merge\",\"group\":\"g1\"}");
            assertEquals(200, merge.statusCode(), merge.body());
            String jm = M.readTree(merge.body()).path("job_id").asText();
            JsonNode mj = awaitDone(c, base, jm);
            assertTrue(mj.path("result").asText().startsWith("investigation I-"), mj.toString());
            String mid = mj.path("investigation").asText();
            assertTrue(mid.startsWith("I-"), mj.toString());
            Investigation merged = store.investigation(mid);
            assertNotNull(merged);
            assertTrue(merged.body().contains("## Agreed") && merged.body().contains("| " + j1 + " | scripted | both | I-") && merged.body().contains("| " + j2 + " | scripted | both | I-"), merged.body());
            assertTrue(merged.body().contains("Merged answer: both seeks found the gears were cut by hand."), merged.body());
            assertTrue(merged.body().contains("No contradictions between the seeks."), merged.body());
            assertEquals("patron:anonymous", merged.writer());
            list = M.readTree(post(c, base + "/v1/seeks", "{\"op\":\"list\"}").body());
            assertEquals(mid, list.path("groups").get(0).path("merged").path("investigation").asText());
            assertEquals(list.path("groups").get(0).path("merged").path("agreed").asInt(), merged.findings().size(), "every agreed claim filed once, and nothing else");
            for (String id : merged.findings()) { Finding kept = store.finding(id); assertEquals("seeks:g1", kept.writer()); assertEquals(Finding.State.draft, kept.state()); }
            assertEquals(mid, M.readTree(post(c, base + "/v1/seeks", "{\"op\":\"read\",\"group\":\"g1\"}").body()).path("id").asText(), "the merged report, by the group alone");
            // dropped: the seeks' shelves go, the merged report stays
            JsonNode drop = M.readTree(post(c, base + "/v1/seeks", "{\"op\":\"drop\",\"group\":\"g1\"}").body());
            assertTrue(drop.path("dropped").asBoolean(), drop.toString());
            assertFalse(Files.exists(store.root().resolve("seeks").resolve("g1")));
            assertNotNull(store.investigation(mid));
            assertEquals(404, post(c, base + "/v1/seeks", "{\"op\":\"drop\",\"group\":\"g1\"}").statusCode());
        } finally { d.stop(); probe.stop(0); }
    }

    @Test
    void theMergeSortsClaimsIntoAgreedDisputedAndFoundByOne(@TempDir Path tmp) throws Exception {
        Seeks.EMBEDDER = Embeddings::none;
        LibraryStore lead = new LibraryStore(tmp.resolve("lib")); lead.init();
        new LibrarianIndex(lead, Embeddings.none()).rebuild();
        String q = "How was the Antikythera mechanism made?";
        LibraryStore one = Seeks.scratchFor(lead, "g2", "J-0001", q, "m1", "both");
        LibraryStore two = Seeks.scratchFor(lead, "g2", "J-0002", q, "m2", "web");
        Finding a1 = claim(one, "The gears were cut by hand", "Cut with files on a dividing plate.", "https://a.example/gears");
        Finding a2 = claim(one, "The dial has 223 teeth", "Counted on the back dial.", "https://a.example/dial");
        Finding a3 = claim(one, "The device was made in Rhodes", "By the school of Posidonius.", "https://a.example/rhodes");
        Finding b1 = claim(two, "The gears were cut by hand with files", "Files, no lathe.", "https://b.example/gears");
        Finding b2 = claim(two, "The dial has 224 teeth", "A different count.", "https://b.example/dial");
        String i1 = one.nextInvestigationId(q), i2 = two.nextInvestigationId(q);
        assertTrue(i1.startsWith("I-10001-") && a1.id().startsWith("F-10001-") && a3.id().startsWith("F-10003-"), "a seek's ids carry its run's number: " + i1 + " " + a1.id());
        assertTrue(i2.startsWith("I-20001-"), i2);
        one.write(new Investigation(i1, q, Finding.State.accepted, "patron:person", Instant.now().toString(), List.of(a1.id(), a2.id(), a3.id()), List.of("Who cut them?"), "## Answer\n\nBy hand, in Rhodes.\n\n## Sources\n\n[1] a.example\n"));
        two.write(new Investigation(i2, q, Finding.State.accepted, "patron:person", Instant.now().toString(), List.of(b1.id(), b2.id()), List.of("Who cut them?", "Which dial?"), "## Answer\n\nBy hand with files.\n"));
        Seeks.landed(lead, "g2", "J-0001", i1, "filed"); Seeks.landed(lead, "g2", "J-0002", i2, "filed");
        assertEquals(List.of("J-0001", "J-0002"), Seeks.jobsOf(Seeks.group(lead, "g2")));
        assertEquals("By hand, in Rhodes.", Seeks.answerOf(Seeks.read(lead, "g2", "J-0001").body()));
        int[] teethAsked = {0};
        Researcher.Drive judge = new Researcher.Drive() {
            @Override public ObjectNode chat(ArrayNode messages, ArrayNode tools, int maxTokens, String toolChoice) { return null; }
            @Override public String classify(ArrayNode messages, int maxTokens) {
                String p = messages.get(messages.size() - 1).path("content").asText();
                // the first reading of the teeth pair is the junk a busy server once gave: thinking, a tool call, a greeting; the second is read
                if (p.contains("teeth")) return ++teethAsked[0] == 1 ? "<think>let me see</think>\n<tool_call><function=note>junk</function></tool_call>\n</think> Hey! How's it going?" : "Contradict.\nThe tooth counts differ.";
                if (p.contains("hand")) return "SAME\nSame claim.";
                return "DIFFERENT\nunrelated";
            }
            @Override public int contextWindow() { return 8192; }
            @Override public String prose(ArrayNode messages, int maxTokens) { assertTrue(messages.get(0).path("content").asText().contains("Researcher J-0001 (m1) answered:\nBy hand, in Rhodes.")); return "Hand-cut; the tooth count is disputed; Rhodes is one seek's word."; }
        };
        Seeks.Merged m = Seeks.merge(lead, "g2", judge, "patron:person");
        assertEquals(1, m.agreed()); assertEquals(1, m.disputed()); assertEquals(1, m.lone());
        Investigation merged = lead.investigation(m.investigationId());
        assertNotNull(merged);
        assertEquals(Finding.State.draft, merged.state());
        assertEquals(List.of("Who cut them?", "Which dial?"), merged.open());
        String body = merged.body();
        assertTrue(body.contains("## Answer\n\nHand-cut; the tooth count is disputed"), body);
        assertTrue(body.contains("- The gears were cut by hand — found by J-0001, J-0002 ["), body);
        assertTrue(body.contains("- The dial has 223 teeth (J-0001 (m1)) against The dial has 224 teeth (J-0002 (m2)): The tooth counts differ."), body);
        assertTrue(body.contains("- The device was made in Rhodes — only J-0001 (m1) (https://a.example/rhodes)"), body);
        assertEquals(2, teethAsked[0], "the junk reading was asked again");
        assertTrue(body.contains("## Judgments\n\n2 pairs put to the judge.\n\n"), body);
        assertTrue(body.contains("- The gears were cut by hand (J-0001) ↔ The gears were cut by hand with files (J-0002): SAME — Same claim."), body);
        assertTrue(body.contains("- The dial has 223 teeth (J-0001) ↔ The dial has 224 teeth (J-0002): CONTRADICT — The tooth counts differ."), body);
        assertTrue(body.contains("| J-0001 | m1 | both | " + i1 + " | 3 |") && body.contains("| J-0002 | m2 | web | " + i2 + " | 2 |"), body);
        assertEquals(3, merged.findings().size(), "the agreed claim and the two disputed ones");
        var scan = lead.scanFindings();
        assertEquals(3, scan.findings().size());
        Finding agreed = scan.findings().stream().filter(f -> f.writer().equals("seeks:g2")).findFirst().orElseThrow();
        assertEquals(Finding.State.draft, agreed.state()); assertEquals("The gears were cut by hand", agreed.title());
        assertEquals(List.of("https://a.example/gears", "https://b.example/gears"), agreed.sources().stream().map(Finding.Source::locator).toList(), "every seek's sources");
        assertEquals(2, scan.findings().stream().filter(f -> f.state() == Finding.State.disputed).count());
        assertTrue(scan.findings().stream().anyMatch(f -> f.writer().equals("seek:J-0002") && f.title().equals("The dial has 224 teeth")));
        String frontier = Files.readString(lead.frontierFile(), StandardCharsets.UTF_8);
        assertTrue(frontier.contains("what evidence would settle it? (seeks g2: J-0001 vs J-0002)"), frontier);
        assertFalse(scan.findings().stream().anyMatch(f -> f.title().contains("Rhodes")), "what only one seek found is not filed");
        assertEquals(m.investigationId(), Seeks.read(lead, "g2", null).id());
        assertTrue(m.investigationId().matches("I-\\d{4}-.*"), "the merged report keeps the library's own numbering: " + m.investigationId());
        assertEquals(1, Seeks.group(lead, "g2").path("merged").path("disputed").asInt());
        assertTrue(new LibrarianIndex(lead, Embeddings.none()).search("gears cut hand", 5).stream().anyMatch(h -> h.id().equals(agreed.id())), "the filed claim is searchable");
        assertTrue(Seeks.drop(lead, "g2")); assertFalse(Seeks.drop(lead, "g2"));
        assertFalse(Files.exists(lead.root().resolve("seeks").resolve("g2")));
        assertNotNull(lead.investigation(m.investigationId()), "the merged report stays");
        assertNull(Seeks.read(lead, "g2", null));
        assertThrows(ProtocolError.class, () -> Seeks.checkedName("Bad Name!"));
        assertEquals("gedcom-7", Seeks.checkedName(" GEDCOM-7 "));
    }

    @Test
    void theJudgesReplyIsCleanedAndReadForItsOneVerdictOrNotAtAll() {
        assertEquals("CONTRADICT", Seeks.verdictOf("CONTRADICT\nThe counts differ."));
        assertEquals("SAME", Seeks.verdictOf("Same.\nThey say the same thing."));
        assertEquals("DIFFERENT", Seeks.verdictOf("These are different: one is about gears, the other about the dial."), "named once in the whole reply");
        assertNull(Seeks.verdictOf("They are the same in part and contradict in part."), "two verdicts is no verdict");
        assertNull(Seeks.verdictOf("")); assertNull(Seeks.verdictOf("Hey! How's it going?"));
        assertEquals("", Seeks.clean("<think>long thought</think>"));
        assertEquals("CONTRADICT\nwhy", Seeks.clean("<think>a</think>\nCONTRADICT\nwhy"));
        assertEquals("Hey! How's it going?", Seeks.clean("</think> Hey! How's it going?"));
        assertEquals("", Seeks.clean("<tool_call><function=note>junk"));
        assertEquals(Seeks.UNREAD, Seeks.ask(new Researcher.Drive() {
            @Override public ObjectNode chat(ArrayNode m, ArrayNode t, int x, String c) { return null; }
            @Override public String classify(ArrayNode m, int x) { return "<tool_call>nothing readable"; }
            @Override public int contextWindow() { return 8192; }
        }, "q", new Finding("F-0001-a", "A", java.util.List.of(), Finding.State.draft, Finding.ClaimType.extraction, Finding.Confidence.medium, "t", "2026-10-22T00:00:00Z", "2026-10-22", Finding.Volatility.stable, "", java.util.List.of(), java.util.List.of(), null, "a"),
           new Finding("F-0002-b", "B", java.util.List.of(), Finding.State.draft, Finding.ClaimType.extraction, Finding.Confidence.medium, "t", "2026-10-22T00:00:00Z", "2026-10-22", Finding.Volatility.stable, "", java.util.List.of(), java.util.List.of(), null, "b"))[0],
           "junk twice is no verdict, not DIFFERENT");
    }
}
