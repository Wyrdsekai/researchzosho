package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.drive.DeclineJudge;
import org.researchzosho.drive.Declined;
import org.researchzosho.drive.DriveClient;
import org.researchzosho.drive.Judge;
import org.researchzosho.mcp.McpServer;
import org.researchzosho.tools.ImageText;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The library's other steps that ask the model something: when the model declines, the step says so and does not treat the decline
 * as an empty answer, a default, an unparseable reply, a claim, a picture's writing, or a reason to ask again. Placeholder words only.
 */
class DeclinedStepsTest {
    static final ObjectMapper M = new ObjectMapper();
    static final String DECLINE = "I am not able to help with that request.";
    @TempDir Path tmp;

    /** A model server that declines every task in words and answers the typed judge's question about it with a sure yes. */
    static HttpServer decliningServer() throws Exception { return decliningServer(new AtomicInteger()); }

    /** As above, counting the tasks it was sent (not the judge's questions). */
    static HttpServer decliningServer(AtomicInteger tasks) throws Exception {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/v1/chat/completions", x -> {
            JsonNode body = M.readTree(x.getRequestBody().readAllBytes());
            JsonNode out;
            if (body.has("grammar")) out = DeclinesTest.judged(0.97);
            else { tasks.incrementAndGet(); ObjectNode r = M.createObjectNode(); r.putArray("choices").addObject().put("finish_reason", "stop").set("message", DeclinesTest.prose(DECLINE)); out = r; }
            byte[] b = M.writeValueAsBytes(out);
            x.getResponseHeaders().set("Content-Type", "application/json");
            x.sendResponseHeaders(200, b.length);
            try (var os = x.getResponseBody()) { os.write(b); }
        });
        s.start();
        return s;
    }

    /** A drive watched as the library's seats are, over a model that declines everything in words. */
    static Researcher.Drive decliningSeat() {
        Researcher.Drive words = new Researcher.Drive() {
            @Override public ObjectNode chat(ArrayNode messages, ArrayNode tools, int maxTokens, String toolChoice) { return DeclinesTest.prose(DECLINE); }
            @Override public String classify(ArrayNode messages, int maxTokens) { return DECLINE; }
            @Override public int contextWindow() { return 16384; }
        };
        return Declines.watch(words, "judge", "placeholder-model", new DeclineJudge(new Judge("m", body -> DeclinesTest.judged(0.97)), null), null);
    }

    @Test
    void theReviewSaysTheModelDeclinedInsteadOfUnparseable() throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        LibrarianIndex index = new LibrarianIndex(store);
        Investigation inv = Acquisitions.admit(store, index, "placeholder question?", "A placeholder answer. https://example.org/a", "model:test");
        HttpServer s = decliningServer();
        try {
            var out = new LibrarianReview(store, index, LibrarianReview.driveJudge(new DriveClient("http://127.0.0.1:" + s.getAddress().getPort(), "placeholder-model")), "librarian:test").review(inv);
            assertTrue(out.problems().stream().noneMatch(p -> p.contains("unparseable")), out.problems().toString());
            assertTrue(out.problems().stream().anyMatch(p -> p.startsWith("The model this library uses (placeholder-model) declined to read the claims out of this report. ResearchZosho did not try to get around it.")), out.problems().toString());
            assertEquals(Finding.State.draft, store.investigation(inv.id()).state());
        } finally { s.stop(0); }
    }

    @Test
    void theNightlyReviewDoesNotSendADeclinedReportAgainUntilItChanges() throws Exception {
        // M5: the report stays a draft, and the next night's review leaves it: the same request is not sent every night
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        LibrarianIndex index = new LibrarianIndex(store);
        Acquisitions.admit(store, index, "placeholder question?", "A placeholder answer. https://example.org/a", "model:test");
        AtomicInteger tasks = new AtomicInteger();
        HttpServer s = decliningServer(tasks);
        try {
            LibrarianReview review = new LibrarianReview(store, index, LibrarianReview.driveJudge(new DriveClient("http://127.0.0.1:" + s.getAddress().getPort(), "placeholder-model")), "librarian:test");
            Crews.reviewDrafts(store, review);
            String second = Crews.reviewDrafts(store, review);
            assertEquals(1, tasks.get(), "the declined report was sent once, not every night");
            assertTrue(second.contains("1 left as drafts because the model declined to review them before"), second);
        } finally { s.stop(0); }
    }

    @Test
    void aPictureTheModelDeclinedToReadIsNotSavedAsItsWriting() throws Exception {
        BufferedImage img = new BufferedImage(40, 20, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream png = new ByteArrayOutputStream(); ImageIO.write(img, "png", png);
        HttpServer s = decliningServer();
        try {
            ImageText.use(ImageText.live(new DriveClient("http://127.0.0.1:" + s.getAddress().getPort(), "placeholder-model")));
            StringBuilder why = new StringBuilder();
            assertEquals("", ImageText.read(png.toByteArray(), "placeholder.png", why), "a decline is not the picture's writing");
            assertTrue(why.toString().startsWith("The model this library uses (placeholder-model) declined to read the writing in this picture."), why.toString());
            assertFalse(why.toString().contains("needs a model that reads images"), why.toString());
        } finally { ImageText.use(null); s.stop(0); }
    }

    @Test
    void thePerspectivesSeatIsWatched() throws Exception {
        // M9: library_perspectives and the perspectives command asked an unwatched seat, and a decline printed "(no perspectives were found)"
        HttpServer s = decliningServer();
        try {
            Researcher.Drive seat = LibraryProtocol.perspectivesDrive("http://127.0.0.1:" + s.getAddress().getPort(), "placeholder-model");
            Declined d = assertThrows(Declined.class, () -> Perspectives.discover("a placeholder question about a placeholder topic", seat, null, 5));
            assertEquals("placeholder-model", d.model());
        } finally { s.stop(0); }
    }

    @Test
    void aSharpenTheModelDeclinedIsSaidNotRetried() throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Declined d = assertThrows(Declined.class, () -> Sharpen.run(store, decliningSeat(), null, "a placeholder question about a placeholder topic"));
        assertEquals("to sharpen this question", d.step());
    }

    @Test
    void aConversationsClaimsAreNotTheModelsDecline() {
        var extractor = Conversations.modelExtractor(decliningSeat());
        Declined d = assertThrows(Declined.class, () -> extractor.apply("The placeholder society was founded in 1900."));
        assertEquals("to list the checkable claims in this text", d.step());
    }

    @Test
    void anAbsorbWhoseClaimsTheModelDeclinedSaysWhatWasShelvedAndWhichPartWasDeclined() throws Exception {
        // M6: the transcripts are shelved and their questions filed before the claims are asked for; a decline there is said beside what
        // was done, per conversation of an export, instead of the whole call failing as unavailable
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Patrons.set(store, "did:key:zW", "W", Patrons.Level.write);
        Supplier<Researcher.Drive> was = Explain.DRIVES;
        Explain.DRIVES = DeclinedStepsTest::decliningSeat;
        try {
            ObjectNode req = M.createObjectNode().put("jsonrpc", "2.0").put("id", 1).put("method", "tools/call");
            ObjectNode params = req.putObject("params"); params.put("name", "library_absorb");
            params.putObject("arguments").put("text", ConversationsTest.chatGpt()).putObject("patron").put("did", "did:key:zW");
            JsonNode env = McpServer.envelopeFor(req, store);
            assertTrue(env.path("error").isMissingNode(), env.toString());
            JsonNode r = env.path("result").path("structuredContent");
            assertEquals(2, r.path("absorbed").asInt(), r.toString());
            JsonNode saros = r.path("threads").get(0), second = r.path("threads").get(1);
            assertFalse(saros.path("raw").asText().isEmpty(), "the transcript is on the shelves: " + saros);
            assertTrue(saros.path("declined").asText().startsWith("The model this library uses (placeholder-model) declined to list the checkable claims in this text. ResearchZosho did not try to get around it."), saros.toString());
            assertTrue(second.path("declined").isMissingNode(), "a conversation with no claims to list was not declined: " + second);
            assertTrue(r.path("questions_filed").asInt() > 0, "the questions were filed: " + r);
            String summary = r.path("summary").asText();
            assertTrue(summary.contains("The model declined to list the claims in 1 of the conversations (\"Saros dial\")") && summary.contains("ResearchZosho did not try to get around it."), summary);
            // a meeting: the transcript and its decisions are saved, and the result says the model declined its claims
            ObjectNode meeting = new LibraryProtocol(store).meeting(M.createObjectNode().put("text", "[00:01] Ada: Is the Saros 223 months?\nBrook: It is, 223 lunar months, and we decided to keep the dial.\n")
                    .put("title", "Quick call").set("patron", M.createObjectNode().put("did", "did:key:zW")));
            assertFalse(meeting.path("raw").asText().isEmpty(), meeting.toString());
            assertTrue(meeting.path("declined").asText().contains("declined to list the checkable claims"), meeting.toString());
            assertTrue(meeting.path("summary").asText().contains("The model declined to list the claims made in the meeting"), meeting.toString());
        } finally { Explain.DRIVES = was; }
    }

    @Test
    void aDeclineHasItsOwnErrorCodeOnTheWireNotAnOutage() throws Exception {
        // L7: a decline went out as unavailable, -32002 and HTTP 503, the same as an outage, and 503 invites a retry
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Patrons.set(store, "did:key:zW", "W", Patrons.Level.write);
        Investigation inv = Acquisitions.admit(store, new LibrarianIndex(store), "placeholder question?", "A placeholder answer about placeholder gears. https://example.org/a", "model:test");
        Supplier<Researcher.Drive> was = Explain.DRIVES;
        Explain.DRIVES = DeclinedStepsTest::decliningSeat;
        try {
            ObjectNode req = M.createObjectNode().put("jsonrpc", "2.0").put("id", 1).put("method", "tools/call");
            ObjectNode params = req.putObject("params"); params.put("name", "library_explain");
            params.putObject("arguments").put("id", inv.id()).put("rung", "beginner").put("fresh", true).putObject("patron").put("did", "did:key:zW");
            JsonNode env = McpServer.envelopeFor(req, store);
            assertEquals("declined", env.path("error").path("data").path("code").asText(), env.toString());
            assertEquals(-32006, env.path("error").path("code").asInt(), env.toString());
            assertTrue(env.path("error").path("message").asText().startsWith("The model this library uses (placeholder-model) declined"), env.toString());
        } finally { Explain.DRIVES = was; }
        assertEquals(422, LibrarianDaemon.status("declined"), "not a status a client retries");
    }

    @Test
    void aBridgeQuestionTheModelDeclinedIsNotReplacedByThePlainOne(@TempDir Path home) throws Exception {
        LibraryStore store = BridgesTest.seeded(home);
        List<Bridges.Pair> pairs = Bridges.candidates(store, Bridges.Settings.defaults().with("reach", "high"), "nutrition--fish-oil", new Random(1), null);
        assertFalse(pairs.isEmpty());
        assertThrows(Declined.class, () -> Bridges.question(decliningSeat(), pairs.get(0)));
    }

    @Test
    void theHousekeepingNotesADeclineAsADecline() throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        store.write(new Finding("F-0001-placeholder", "A placeholder claim about a placeholder topic", List.of(), Finding.State.accepted, Finding.ClaimType.extraction,
                Finding.Confidence.medium, "model:test", "2026-09-23T00:00:00Z", "2026-09-23", Finding.Volatility.slow, "",
                List.of(new Finding.Source("https://example.org/a", "n/a", "a")), List.of(), null, "The placeholder society was founded in 1900 by the placeholder people.\n"));
        Declined filtered = new Declined("placeholder-model", "", Declined.How.FILTERED);
        Triples.fill(store, claim -> { throw filtered; }, 5);
        Concepts.fill(store, claim -> { throw filtered; }, 5);
        Finding f = store.finding("F-0001-placeholder");
        String triples = f.notes().stream().filter(n -> n.kind().equals("triples")).findFirst().orElseThrow().text();
        assertTrue(triples.contains("declined"), triples);
        assertTrue(Concepts.of(f).isEmpty(), "a decline is not a concept: " + Concepts.of(f));
        String concepts = f.notes().stream().filter(n -> n.kind().equals(Concepts.NOTE)).findFirst().orElseThrow().text();
        assertTrue(concepts.contains("declined"), concepts);
    }

    @Test
    void sortingTheWebsPeopleIsNotDoneWithoutTheModelWhenItDeclined() {
        List<FamilyIdentity.Page> rows = List.of(new FamilyIdentity.Page("https://example.org/1", "Placeholder Person, painter", "a painter"),
                new FamilyIdentity.Page("https://example.org/2", "Placeholder Person, engineer", "an engineer"));
        assertThrows(Declined.class, () -> FamilyIdentity.sort("Placeholder Person", List.of("Placeholder Person"), rows, prompt -> { throw new Declined("placeholder-model", DECLINE, Declined.How.WORDS); }));
    }
}
