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
import org.researchzosho.drive.ContentJudge;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A picture under a chat reply (0.7.0), through the real /chat post: a question about a mechanism gets one picture placed under the
 * model's reply with the line that says why; a question about the models gets none (the library's own turn); an ask for pictures is the
 * pictures turn as before, with no second picture; a reply that carries a picture already gets none; a Japanese question is read too. The
 * model is a stand-in that answers with fixed words, the engines, the fetcher and the seeing model are stood in.
 */
class ChatPictureTest {
    static final ObjectMapper M = new ObjectMapper();
    static final String MECHANISM = "A pin on one wheel rides in a slot on another, so the second wheel turns faster and slower across each revolution; that is what gives the Moon's pointer its varying speed.";

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

    /** A model that answers every chat turn with {@code content} — streamed when the chat asks for a stream, whole otherwise — and the typed judges with no. */
    static HttpServer model(String content) throws Exception {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/", x -> {
            JsonNode body = M.readTree(x.getRequestBody().readAllBytes());
            if (body.path("stream").asBoolean(false) && !body.has("grammar")) {
                x.getResponseHeaders().set("Content-Type", "text/event-stream");
                x.sendResponseHeaders(200, 0);
                try (OutputStream os = x.getResponseBody()) {
                    os.write(("data: {\"choices\":[{\"delta\":{\"content\":" + M.writeValueAsString(content) + "},\"index\":0}]}\n\n").getBytes(StandardCharsets.UTF_8));
                    os.write("data: {\"choices\":[{\"delta\":{},\"index\":0,\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
                }
                return;
            }
            JsonNode out;
            if (body.has("grammar")) out = DeclinesTest.judged(0.03);
            else { ObjectNode r = M.createObjectNode(); r.putArray("choices").addObject().put("finish_reason", "stop").set("message", DeclinesTest.prose(content)); out = r; }
            byte[] b = M.writeValueAsBytes(out);
            x.getResponseHeaders().set("Content-Type", "application/json");
            x.sendResponseHeaders(200, b.length); x.getResponseBody().write(b); x.close();
        });
        s.start();
        return s;
    }

    static String lastAssistant(LibraryStore store, String session) throws Exception {
        List<ObjectNode> ms = Librarian.Session.resume(store, session).messages();
        ObjectNode last = null;
        for (ObjectNode m : ms) if ("assistant".equals(m.path("role").asText())) last = m;
        assertNotNull(last, ms.toString());
        return last.path("content").asText();
    }

    @Test void aQuestionAboutAMechanismGetsOnePictureUnderTheReplyAndTheOtherTurnsGetNone(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        List<String> questions = new ArrayList<>();
        Vision.DRIVES = (d, m) -> RunPicturesTest.eyesFor(questions, "a diagram of the pin-and-slot mechanism", "機構の図");
        HttpServer model = model(MECHANISM);
        LibrarianDaemon d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:" + model.getAddress().getPort(), "stand-in", -1);
        try {
            HttpClient c = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
            String base = "http://127.0.0.1:" + d.port();
            Librarian.Session s = Librarian.Session.open(store);
            // a question about a mechanism: the model's words, then one picture, then the line that says why it is there
            ModelsTurnTest.chat(c, base, s.id, "how does the pin-and-slot mechanism work?", null);
            String reply = lastAssistant(store, s.id);
            assertTrue(reply.startsWith(MECHANISM + "\n\n![Wild Dog Breeds: Red Fox](/images/" + RunPicturesTest.sha16(Vision.square(255, 0, 0)) + ".png)\n\nPicture: Wild Dog Breeds: Red Fox, judged \"a diagram of the pin-and-slot mechanism\": yes — a red fox stands in snow (kept as "), reply);
            assertEquals(1, reply.split("!\\[", -1).length - 1, "one picture a reply: " + reply);
            assertTrue(questions.stream().anyMatch(q -> q.startsWith("Does this picture show a diagram of the pin-and-slot mechanism? The page it stands on is titled \"Wild Dog Breeds: Red Fox\". " + Images.SHOWS_RULE)), questions.toString());
            Images.Shelved kept = Images.onShelves(store).stream().filter(e -> e.criteria().equals("a diagram of the pin-and-slot mechanism")).findFirst().orElseThrow();
            assertEquals(Images.Verdict.YES, kept.verdict()); assertTrue(reply.contains("(kept as " + kept.id() + ")"), reply);
            // the same in Japanese: the words' own heads name the mechanism, and the picture is judged against the Japanese words
            questions.clear();
            ModelsTurnTest.chat(c, base, s.id, "ピンとスロット機構の仕組みは？", null);
            reply = lastAssistant(store, s.id);
            assertTrue(reply.contains("judged \"ピンとスロット機構の図\": yes"), reply);
            assertTrue(questions.stream().anyMatch(q -> q.startsWith("Does this picture show ピンとスロット機構の図?")), questions.toString());
            // a question about the models is the library's own turn: no picture
            ModelsTurnTest.chat(c, base, s.id, "make deepseek the default", null);
            reply = lastAssistant(store, s.id);
            assertFalse(reply.contains("![") || reply.contains("Picture: "), reply);
            // an ask for pictures is the pictures turn as before: its candidates, and no second picture under them
            ModelsTurnTest.chat(c, base, s.id, "find pictures of the Antikythera mechanism", null);
            reply = lastAssistant(store, s.id);
            assertTrue(reply.contains("pictures found for \"Antikythera mechanism\"") || reply.contains("Antikythera mechanism"), reply);
            assertFalse(reply.contains("\nPicture: "), reply);
            // an abstract question gets none
            questions.clear();
            ModelsTurnTest.chat(c, base, s.id, "why does the significance of the mechanism matter?", null);
            reply = lastAssistant(store, s.id);
            assertEquals(MECHANISM, reply); assertTrue(questions.isEmpty());
        } finally { d.stop(); model.stop(0); }
    }

    @Test void aReplyThatCarriesAPictureAlreadyGetsNone(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        List<String> questions = new ArrayList<>();
        Vision.DRIVES = (d, m) -> RunPicturesTest.eyesFor(questions, "a diagram");
        String withPicture = "Here is the gearing as the museum draws it:\n\n![the gearing](/images/abc.png)";
        HttpServer model = model(withPicture);
        LibrarianDaemon d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:" + model.getAddress().getPort(), "stand-in", -1);
        try {
            HttpClient c = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
            Librarian.Session s = Librarian.Session.open(store);
            ModelsTurnTest.chat(c, "http://127.0.0.1:" + d.port(), s.id, "how does the gear train work?", null);
            String reply = lastAssistant(store, s.id);
            assertEquals(withPicture, reply);
            assertTrue(questions.isEmpty(), "nothing went to the seeing model: " + questions);
        } finally { d.stop(); model.stop(0); }
    }
}
