package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;
import org.researchzosho.drive.ContentJudge;
import org.researchzosho.drive.Declined;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A decline label on one part of a chat reply switches off no check of another (2026-11-24). In the chat battery a thinking model's replies
 * that said, truthfully, that a report was not on the shelves were labelled "declined to answer this", and under the label the figure
 * check never ran ("~12,000 per branch" went unadmitted) and no picture could be placed. Now: a label is put on only when the model's
 * ANSWER declines (a thought is no verdict); under a real one the checks still read what the model said, and no picture is fetched for
 * the words it declined. Through the real /chat post, with a stand-in model, engines, fetcher and seeing model.
 */
class ChatDeclinedPartTest {
    static final ObjectMapper M = new ObjectMapper();
    static final String ASK = "how does the pin-and-slot mechanism work?";

    String home;
    Supplier<List<Images.Engine>> engines; Images.Fetcher fetcher; BiFunction<String, String, Researcher.Drive> drives; BiFunction<ModelEntries.Entry, Images.Fetched, ContentJudge.Reading> check;

    @BeforeEach void isolate(@TempDir Path tmp) throws Exception {
        home = System.getProperty("user.home"); System.setProperty("user.home", tmp.resolve("home").toString()); Config.invalidate();
        engines = Images.ENGINES; fetcher = Images.FETCHER; drives = Vision.DRIVES; check = Images.CHECK;
        Images.ENGINES = () -> List.of(new Images.Engine("Brave", (w, n) -> Images.parseBrave(ImagesTest.fixture("brave-images.json"))), new Images.Engine("SearXNG", (w, n) -> Images.parseSearx(ImagesTest.fixture("searx-images.json"))));
        Images.FETCHER = url -> url.endsWith(".svg") ? new Images.Fetched("<svg/>".getBytes(StandardCharsets.UTF_8), "image/svg+xml") : new Images.Fetched(ImagesTest.square(url), "image/png");
        Images.CHECK = (e, got) -> new ContentJudge.Reading(ContentJudge.Verdict.NO, 0.0, "test");
        ImagesTest.seeingDefault();
    }
    @AfterEach void restore() { System.setProperty("user.home", home); Config.invalidate(); Images.ENGINES = engines; Images.FETCHER = fetcher; Vision.DRIVES = drives; Images.CHECK = check; }

    /**
     * A model whose chat turns answer {@code words}; the typed decline judge is answered by {@code typed} (the grammar request), the plain
     * one-word judge by {@code plain} (given the request).
     */
    static HttpServer model(String words, Supplier<JsonNode> typed, Function<JsonNode, JsonNode> plain) throws Exception {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/", x -> {
            if (!"POST".equals(x.getRequestMethod())) { x.getRequestBody().readAllBytes(); x.sendResponseHeaders(404, -1); x.close(); return; }
            JsonNode body = M.readTree(x.getRequestBody().readAllBytes());
            String last = body.path("messages").path(body.path("messages").size() - 1).path("content").asText("");
            if (body.path("stream").asBoolean(false) && !body.has("grammar")) {
                x.getResponseHeaders().set("Content-Type", "text/event-stream");
                x.sendResponseHeaders(200, 0);
                try (OutputStream os = x.getResponseBody()) {
                    os.write(("data: {\"choices\":[{\"delta\":{\"content\":" + M.writeValueAsString(words) + "},\"index\":0}]}\n\n").getBytes(StandardCharsets.UTF_8));
                    os.write("data: {\"choices\":[{\"delta\":{},\"index\":0,\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
                }
                return;
            }
            JsonNode out;
            if (body.has("grammar")) out = typed.get();
            else if (last.contains("declining to do the task")) out = plain.apply(body);
            else { ObjectNode r = M.createObjectNode(); r.putArray("choices").addObject().put("finish_reason", "stop").set("message", DeclinesTest.prose(words)); out = r; }
            byte[] b = M.writeValueAsBytes(out);
            x.getResponseHeaders().set("Content-Type", "application/json");
            x.sendResponseHeaders(200, b.length); x.getResponseBody().write(b); x.close();
        });
        s.start();
        return s;
    }

    /** The typed judge's answer from a model whose first token is its thinking's (the record of 2026-11-24): the grammar does not hold. */
    static JsonNode thinkingFirst() throws Exception {
        return M.readTree("{\"choices\":[{\"finish_reason\":\"length\",\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"n\"},\"logprobs\":{\"content\":[{\"id\":77,\"token\":\"n\",\"logprob\":-34.15,"
                + "\"top_logprobs\":[{\"id\":200001,\"token\":\"\",\"logprob\":0.0},{\"id\":200006,\"token\":\"\",\"logprob\":-21.1}]}]}}],\"usage\":{\"completion_tokens\":1,\"prompt_tokens\":164}}");
    }

    /** The plain one-word judge answered with thinking only, at any allowance: a thought that begins with "Yes". */
    static JsonNode thoughtYes(JsonNode req) {
        ObjectNode r = M.createObjectNode();
        ObjectNode c = r.putArray("choices").addObject();
        c.put("finish_reason", "length");
        c.putObject("message").put("role", "assistant").put("content", "").put("reasoning_content", "Yes, the reply says it can't find a note, but that is a report of absence, not a refusal, so the answer is");
        r.putObject("usage").put("completion_tokens", req.path("max_tokens").asInt()).put("prompt_tokens", 170);
        return r;
    }

    static String turn(LibraryStore store, HttpServer model, String words) throws Exception {
        LibrarianDaemon d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:" + model.getAddress().getPort(), "stand-in", -1);
        try {
            HttpClient c = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
            Librarian.Session s = Librarian.Session.open(store);
            ModelsTurnTest.chat(c, "http://127.0.0.1:" + d.port(), s.id, words, null);
            return ChatPictureTest.lastAssistant(store, s.id);
        } finally { d.stop(); }
    }

    /** The false decline of the records: the reply reports an absence, the judge only thought; no label, and the reply goes through every check and gets its picture. */
    @Test void aReplyTheJudgeOnlyThoughtAboutIsNoDeclineAndGoesThroughEveryCheck(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        List<String> questions = new ArrayList<>();
        Vision.DRIVES = (d, m) -> RunPicturesTest.eyesFor(questions, "a diagram of the pin-and-slot mechanism");
        String words = "I can't find a note on the shelves about it, but a pin on one wheel rides in a slot on another, so the second wheel turns faster and slower across each turn; about 12,000 such clocks were made.";
        HttpServer model = model(words, () -> { try { return thinkingFirst(); } catch (Exception e) { throw new RuntimeException(e); } }, ChatDeclinedPartTest::thoughtYes);
        try {
            String reply = turn(store, model, ASK);
            assertFalse(reply.contains("declined"), "a thought is no verdict: " + reply);
            assertTrue(reply.startsWith(words), reply);
            assertTrue(reply.contains("The figure 12000 is not in anything I looked up; take it as my guess."), "the figure check read the reply: " + reply);
            assertTrue(reply.contains("![") && reply.contains("Picture: "), "the picture under it, as for any reply: " + reply);
        } finally { model.stop(0); }
    }

    /** A real decline: the label stays, the checks still read what the model said under it, and no picture is fetched for what it declined. */
    @Test void aRealDeclineKeepsItsLabelTheChecksReadItsWordsAndNoPictureComes(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        List<String> questions = new ArrayList<>();
        Vision.DRIVES = (d, m) -> RunPicturesTest.eyesFor(questions, "a diagram of the pin-and-slot mechanism");
        String words = "I'm sorry, I can't help with that. About 12,000 such clocks were made, and that is all I will say.";
        HttpServer model = model(words, () -> DeclinesTest.judged(0.97), r -> DeclinesTest.judged(0.97));
        try {
            String reply = turn(store, model, ASK);
            assertTrue(reply.startsWith("The model this library uses (") && reply.contains(") declined to answer this. ResearchZosho did not try to get around it."), reply);
            assertTrue(reply.contains("The figure 12000 is not in anything I looked up; take it as my guess."), "the checks read the words the label quotes: " + reply);
            assertFalse(reply.contains("![") || reply.contains("Picture: "), "no picture for what the model declined: " + reply);
            assertTrue(questions.isEmpty(), "the seeing model was asked nothing: " + questions);
        } finally { model.stop(0); }
    }

    /**
     * A decline after a go-ahead was carried out (the go-ahead of 2026-11-25): the library's line for what was done without asking is shown
     * under the label, since it was done, and the checks read only the model's words: a figure in the library's own line (the run's
     * question) is never admitted as the model's guess, while one in the model's words is.
     */
    @Test void aDeclineAfterAGoAheadShowsTheLibrarysLineAndTheChecksReadOnlyTheModelsWords(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        String q = "Why were 45,000 trees cut at Kestrel Sound?";
        int[] round = {0};
        Researcher.Drive drive = new Researcher.Drive() {
            @Override public ObjectNode chat(ArrayNode messages, ArrayNode tools, int maxTokens, String toolChoice) {
                if (round[0]++ == 0) {
                    ObjectNode m = M.createObjectNode(); m.put("role", "assistant"); m.put("content", "");
                    ObjectNode c = m.putArray("tool_calls").addObject(); c.put("id", "c1"); c.put("type", "function");
                    c.putObject("function").put("name", "library_research").put("arguments", M.createObjectNode().put("question", q).toString());
                    return m;
                }
                throw new Declined("stand-in", "I'm sorry, I can't say more about it. About 12,000 such clocks were made.", Declined.How.WORDS);
            }
            @Override public String classify(ArrayNode messages, int maxTokens) { return ""; }
            @Override public int contextWindow() { return 16384; }
        };
        Librarian l = new Librarian(store, drive, Librarian.person(), Librarian.Session.open(store));
        String reply = l.say("/go find out why the trees were cut", "/go find out why the trees were cut");
        assertTrue(reply.startsWith("The model this library uses (stand-in) declined to answer this."), reply);
        assertEquals(1, HeldStartsTest.runs(store).size(), "the run the go-ahead reached was filed: " + reply);
        assertNotNull(GoAheadTest.doneLine(reply), "what was done without asking is said under the label: " + reply);
        assertTrue(reply.contains("The figure 12000 is not in anything I looked up; take it as my guess."), "the model's words are checked: " + reply);
        assertFalse(reply.contains("45000"), "the library's own line is not read as the model's words: " + reply);
    }
}
