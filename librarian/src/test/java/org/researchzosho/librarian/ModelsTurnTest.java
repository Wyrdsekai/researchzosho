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
import org.researchzosho.drive.DriveClient;
import org.researchzosho.mcp.McpServer;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Words about the models the library runs on are the library's own turn, through the real chat post: the two sentences the keeper typed on
 * 2026-11-08 ("J-0309 - woah shouldnt we be using the local - aka qwen 27b model? why are we using openrouter as the main model", then
 * "the default model should be the qwen on homebox gpu2"), against the five entries his library had and a run that failed with a
 * NoClassDefFoundError; the changes a keeper and a reader ask for; the Japanese forms; the notice for the failed run; the protocol's
 * answer with the default stated outright; and the rule the chat's model is given once a turn holds tool results. The daemon's model is a
 * stand-in that counts every completion asked of it, and the count stays at zero.
 */
class ModelsTurnTest {
    static final ObjectMapper M = new ObjectMapper();
    static final String ERROR = "job error: java.lang.NoClassDefFoundError: org/researchzosho/librarian/Likeness";
    String home;

    @BeforeEach void isolate(@TempDir Path tmp) { home = System.getProperty("user.home"); System.setProperty("user.home", tmp.resolve("home").toString()); Config.invalidate(); }
    @AfterEach void restore() { System.setProperty("user.home", home); Config.invalidate(); }

    /** The five entries of the live library on 2026-11-08: the default on homebox card 2, the 27B on card 1, gemma26 beside it, deepseek and kimi on openrouter.ai with its key kept. */
    static void fiveEntries() throws Exception {
        Config.set("RESEARCHZOSHO_DRIVE", "http://homebox:8212"); Config.set("RESEARCHZOSHO_MODEL", "qwen3.8-27b");
        ModelEntries.put("27b-gpu1", "http://homebox:8213", "qwen3.8-27b", null, null, null, "the 27B on homebox card 1, for seeks run in pairs");
        ModelEntries.put("deepseek", "openrouter", "deepseek/deepseek-v4.1-flash", "0.051/0.567/0.018", null, "10", "DeepSeek V4.1 Flash on OpenRouter");
        ModelEntries.put("gemma26", "http://homebox:8213", "gemma-4-26b-a4b", null, null, null, "comparison trial on homebox card 1");
        ModelEntries.put("kimi", "openrouter", "moonshotai/kimi-k3", "0.192/16/0.182", null, "10", "Kimi K3 on OpenRouter");
        Config.set(DriveClient.hostKeySetting("openrouter.ai"), "sk-or-test");
    }

    @Test void whenTheOnlyEntryOnTheNamedHostIsTheDefaultAlreadyTheReplySaysSoInsteadOfOfferingToMakeTheDefaultTheDefault() throws Exception {
        Config.set("RESEARCHZOSHO_DRIVE", "http://homebox:8212"); Config.set("RESEARCHZOSHO_MODEL", "qwen3.8-27b");
        ModelEntries.put("deepseek", "openrouter", "deepseek/deepseek-v4.1-flash", "0.051/0.567/0.018", null, "10", "DeepSeek V4.1 Flash on OpenRouter");
        assertEquals("No model entry is named \"qwen on homebox gpu2\". default is the only entry on homebox and it is the default already. Nothing was changed.", ModelsTurn.change(null, null, "the qwen on homebox gpu2"));
    }

    /** A research run that failed on the default's server after 24 minutes with a rebuild's error, as J-0309 did. */
    static void failedRun(LibraryStore store, String id) throws Exception {
        Jobs jobs = new Jobs(store, j -> "");
        Path month = jobs.dir().resolve("done").resolve("2026-11"); Files.createDirectories(month);
        long ended = System.currentTimeMillis() - 60_000, started = ended - 24 * 60_000;
        ObjectNode j = M.createObjectNode(); j.put("job_id", id); j.put("kind", "research"); j.put("state", "failed"); j.put("patron", "person");
        j.put("queued_at", Instant.ofEpochMilli(started - 5_000).toString()); j.put("started_at", Instant.ofEpochMilli(started).toString()); j.put("ended_at", Instant.ofEpochMilli(ended).toString());
        j.put("drive", "http://homebox:8212");
        j.putObject("args").put("question", "Who is the woman in this photograph?").put("mode", "broad");
        j.put("result", ERROR + "\n\tat org.researchzosho.librarian.RunPictures.entities(RunPictures.java:88)"); j.put("is_error", true);
        Files.writeString(month.resolve(id + ".json"), M.writeValueAsString(j));
    }

    /** A model server that counts the completions asked of it and answers none. */
    static HttpServer standIn(AtomicInteger completions) throws Exception {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/", x -> { if (x.getRequestURI().getPath().endsWith("/chat/completions")) completions.incrementAndGet(); byte[] b = "{\"error\":\"stand-in\"}".getBytes(StandardCharsets.UTF_8); x.sendResponseHeaders(503, b.length); x.getResponseBody().write(b); x.close(); });
        s.start();
        return s;
    }

    static String chat(HttpClient c, String base, String session, String say, String cookie) throws Exception {
        HttpResponse<String> r = ChatAttachmentTest.post(c, base + "/chat", "----rzboundary", ChatAttachmentTest.multipart("----rzboundary", session, say, Map.of()), cookie);
        assertEquals(200, r.statusCode(), r.body());
        return r.body();
    }

    /** The library's last reply in a conversation, and that it was the library's own turn with no tool and no model. */
    static String lastReply(LibraryStore store, String session) throws Exception {
        Librarian.Session s = Librarian.Session.resume(store, session);
        List<ObjectNode> ms = s.messages();
        ObjectNode last = null;
        for (ObjectNode m : ms) if ("assistant".equals(m.path("role").asText()) && m.hasNonNull("at")) last = m;
        assertNotNull(last, ms.toString());
        assertEquals("library", last.path("by").asText(), "the turn is the library's own: " + last);
        return last.path("content").asText();
    }

    @Test
    void theTwoSentencesOfTheLiveSessionAreAnsweredByTheLibraryFromItsOwnStateAndNoModelIsAsked() throws Exception {
        LibraryStore store = new LibraryStore(Path.of(System.getProperty("user.home")).resolve("researchzosho-library")); store.init();   // where LibraryStore.open() looks, under the home the test set
        fiveEntries(); failedRun(store, "J-0309");
        AtomicInteger completions = new AtomicInteger();
        HttpServer model = standIn(completions);
        LibrarianDaemon d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:" + model.getAddress().getPort(), "stand-in", -1);
        try {
            HttpClient c = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
            String base = "http://127.0.0.1:" + d.port();
            Librarian.Session s = Librarian.Session.open(store);
            s.watch("J-0309");   // the conversation started the run, so the chat announces how it ended
            // the first sentence, as typed: the run's record first, then the default and the entries; nothing of the model's inventions
            String body = chat(c, base, s.id, "J-0309 - woah shouldnt we be using the local - aka qwen 27b model?  why are we using openrouter as the main model", null);
            String reply = lastReply(store, s.id);
            System.out.println("[models-turn] reply 1:\n" + reply);
            assertEquals("Research run J-0309 ran on the entry default: qwen3.8-27b on homebox at http://homebox:8212. It ended as failed after 24 min with this error: \"" + ERROR + "\". It did not run on openrouter.ai.\n\n"
                    + "The default is the entry named default: qwen3.8-27b on homebox at http://homebox:8212. A run or a chat that names no model uses it.\n"
                    + "The other entries:\n"
                    + "27b-gpu1: qwen3.8-27b on homebox at http://homebox:8213; a local server, no key needed.\n"
                    + "deepseek: deepseek/deepseek-v4.1-flash on openrouter.ai at https://openrouter.ai/api; a key for openrouter.ai is kept.\n"
                    + "gemma26: gemma-4-26b-a4b on homebox at http://homebox:8213; a local server, no key needed.\n"
                    + "kimi: moonshotai/kimi-k3 on openrouter.ai at https://openrouter.ai/api; a key for openrouter.ai is kept.\n"
                    + "To change the default, say \"make 27b-gpu1 the default\" with the name of the entry you mean; /model and a name switches this chat alone.", reply);
            for (String never : new String[]{"openrouter/auto", "localhost:8081", "first model entry that has a key", "Qwen3.6", "key: true", "usage cap"}) assertFalse(reply.contains(never) || body.contains(never), never);
            // the notice for the failed run, in the same conversation: the entry it ran on and the error, nothing left to guess
            String notice = "Research run J-0309 ended as failed after 24 min. It ran on default (qwen3.8-27b at http://homebox:8212) and failed with: \"" + ERROR + "\". Say \"what happened\" and I will look.";
            System.out.println("[models-turn] notice:\n" + notice);
            assertTrue(body.contains(Pages.esc(notice)) || body.contains(notice), "the page carries the notice: " + body);
            assertTrue(Librarian.Session.resume(store, s.id).messages().stream().anyMatch(m -> notice.equals(m.path("content").asText())), "and the conversation keeps it");
            // the second sentence: no entry is named so, nothing changes, the entries on that host are listed with their addresses, and the question which
            chat(c, base, s.id, "the default model should be the qwen on homebox gpu2", null);
            reply = lastReply(store, s.id);
            System.out.println("[models-turn] reply 2:\n" + reply);
            assertEquals("No model entry is named \"qwen on homebox gpu2\". On homebox: default (qwen3.8-27b at http://homebox:8212; the default now), 27b-gpu1 (qwen3.8-27b at http://homebox:8213), gemma26 (gemma-4-26b-a4b at http://homebox:8213). Which one? Say \"make 27b-gpu1 the default\" with the name of the one you mean. Nothing was changed.", reply);
            assertEquals("http://homebox:8212", Config.get("RESEARCHZOSHO_DRIVE")); assertEquals("qwen3.8-27b", Config.get("RESEARCHZOSHO_MODEL"));
            // a name that fits two entries equally: asked, not guessed
            chat(c, base, s.id, "make qwen the default", null);
            reply = lastReply(store, s.id);
            assertEquals("\"qwen\" could be any of these: default (qwen3.8-27b at http://homebox:8212; the default now), 27b-gpu1 (qwen3.8-27b at http://homebox:8213). Which one? Say \"make 27b-gpu1 the default\" with the name of the one you mean. Nothing was changed.", reply);
            // the keeper makes deepseek the default: the one operation model use runs, said with what it was before
            chat(c, base, s.id, "make deepseek the default", null);
            reply = lastReply(store, s.id);
            System.out.println("[models-turn] change:\n" + reply);
            assertEquals("The default is now deepseek: deepseek/deepseek-v4.1-flash on openrouter.ai at https://openrouter.ai/api. It was qwen3.8-27b on homebox at http://homebox:8212. Runs and chats that name no model use it from now on; a run going now finishes on the model it started with.", reply);
            assertEquals("https://openrouter.ai/api", Config.get("RESEARCHZOSHO_DRIVE")); assertEquals("deepseek/deepseek-v4.1-flash", Config.get("RESEARCHZOSHO_MODEL"));
            assertEquals("deepseek/deepseek-v4.1-flash", ModelEntries.defaultEntry().model());
            // already the default: said, nothing changes
            chat(c, base, s.id, "use deepseek as the default", null);
            assertEquals("deepseek is the default already: deepseek/deepseek-v4.1-flash on openrouter.ai at https://openrouter.ai/api. Nothing changed.", lastReply(store, s.id));
            chat(c, base, s.id, "switch the default to kimi", null);
            assertTrue(lastReply(store, s.id).startsWith("The default is now kimi: moonshotai/kimi-k3 on openrouter.ai at https://openrouter.ai/api. It was deepseek/deepseek-v4.1-flash on openrouter.ai at https://openrouter.ai/api."), lastReply(store, s.id));
            chat(c, base, s.id, "use kimi as the default", null);
            assertEquals("kimi is the default already: moonshotai/kimi-k3 on openrouter.ai at https://openrouter.ai/api. Nothing changed.", lastReply(store, s.id));
            // a question about the state, and about one entry
            chat(c, base, s.id, "which models do we have?", null);
            reply = lastReply(store, s.id);
            assertTrue(reply.startsWith("The default is the entry named default: moonshotai/kimi-k3 on openrouter.ai at https://openrouter.ai/api. A run or a chat that names no model uses it.\nThe other entries:\n27b-gpu1: qwen3.8-27b on homebox at http://homebox:8213; a local server, no key needed.\n") && reply.contains("kimi: moonshotai/kimi-k3 on openrouter.ai at https://openrouter.ai/api; a key for openrouter.ai is kept; the same model and address as the default."), reply);
            chat(c, base, s.id, "does deepseek have a key?", null);
            reply = lastReply(store, s.id);
            assertTrue(reply.startsWith("deepseek is set up: deepseek/deepseek-v4.1-flash on openrouter.ai at https://openrouter.ai/api; a key for openrouter.ai is kept. It is not the default; say \"make deepseek the default\" to make it so, or /model deepseek for this chat alone.\n\nThe default is the entry named default:"), reply);
            // the Japanese forms
            chat(c, base, s.id, "既定のモデルはどれ？", null);
            assertTrue(lastReply(store, s.id).startsWith("The default is the entry named default: moonshotai/kimi-k3 on openrouter.ai"), lastReply(store, s.id));
            chat(c, base, s.id, "deepseekを既定にして", null);
            assertTrue(lastReply(store, s.id).startsWith("The default is now deepseek: deepseek/deepseek-v4.1-flash on openrouter.ai at https://openrouter.ai/api. It was moonshotai/kimi-k3 on openrouter.ai"), lastReply(store, s.id));
            assertEquals("deepseek/deepseek-v4.1-flash", Config.get("RESEARCHZOSHO_MODEL"));
            chat(c, base, s.id, "どのモデルを使っていますか", null);
            assertTrue(lastReply(store, s.id).startsWith("The default is the entry named default: deepseek/deepseek-v4.1-flash on openrouter.ai"), lastReply(store, s.id));
            // a reader asks for a change: told whose it is, and the command the keeper would run; nothing changes
            Patrons.set(store, "did:key:zReader", "a reader", Patrons.Level.read);
            String cookie = Pages.COOKIE + "=" + Patrons.issueToken(store, "did:key:zReader");
            Librarian.Session theirs = Librarian.Session.open(store, new Patrons.Patron("did:key:zReader", "a reader", "web"));
            chat(c, base, theirs.id, "make kimi the default", cookie);
            Librarian.Session again = Librarian.Session.resume(store, theirs.id, new Patrons.Patron("did:key:zReader", "a reader", "web"));
            ObjectNode last = again.messages().get(again.messages().size() - 1);
            assertEquals("library", last.path("by").asText(), last.toString());
            assertEquals("Only the person who keeps this library and those who may write to it change the default. The keeper would run: researchzosho model use https://openrouter.ai/api moonshotai/kimi-k3", last.path("content").asText());
            assertEquals("deepseek/deepseek-v4.1-flash", Config.get("RESEARCHZOSHO_MODEL"), "a reader changes nothing");
            // every turn above was the library's: the stand-in model was asked for nothing, and the ledger shows no tool
            assertEquals(0, completions.get(), "the model was asked");
            for (String line : Files.readAllLines(store.root().resolve("catalog").resolve("chat-turns.jsonl"))) assertEquals(0, M.readTree(line).path("tools").size(), line);
        } finally { d.stop(); model.stop(0); }
    }

    @Test
    void theProtocolStatesTheDefaultOutrightAndEachKeyAsAWord() throws Exception {
        LibraryStore store = new LibraryStore(Path.of(System.getProperty("user.home")).resolve("researchzosho-library")); store.init();   // where the MCP server's LibraryStore.open() looks
        fiveEntries();
        Patrons.set(store, "did:key:zWriter", "A writer", Patrons.Level.write);
        ObjectNode args = M.createObjectNode(); args.put("op", "list"); args.putObject("patron").put("did", "person").put("name", "").put("runtime", "cli");
        JsonNode r = new LibraryProtocol(store).models(args);
        System.out.println("[models-turn] protocol: default=" + r.path("default") + " summary=" + r.path("summary").asText());
        assertEquals("{\"name\":\"default\",\"model\":\"qwen3.8-27b\",\"host\":\"homebox\",\"drive\":\"http://homebox:8212\"}", r.path("default").toString());
        assertEquals("The default is qwen3.8-27b on homebox at http://homebox:8212; runs and chats that name no model use it. 5 entries; deepseek and kimi run on openrouter.ai with a key kept; 27b-gpu1 and gemma26 need none and have none kept.", r.path("summary").asText());
        Map<String, String> keys = new LinkedHashMap<>();
        for (JsonNode m : r.path("models")) keys.put(m.path("name").asText(), m.path("key").asText());
        assertEquals(Map.of("default", "not needed", "27b-gpu1", "not needed", "deepseek", "kept", "gemma26", "not needed", "kimi", "kept"), keys);
        // the same over MCP, as a writing patron
        ObjectNode call = M.createObjectNode(); call.put("name", "library_models"); ObjectNode a = call.putObject("arguments"); a.put("op", "list"); a.putObject("patron").put("did", "did:key:zWriter").put("name", "A writer").put("runtime", "test");
        JsonNode mcp = McpServer.handle("tools/call", call).get("structuredContent");
        assertEquals("qwen3.8-27b", mcp.path("default").path("model").asText(), mcp.toString());
        assertTrue(mcp.path("summary").asText().startsWith("The default is qwen3.8-27b on homebox at http://homebox:8212;"), mcp.toString());
        // a host's key forgotten: the word says missing, and the summary says so
        Config.set(DriveClient.hostKeySetting("openrouter.ai"), "");
        r = new LibraryProtocol(store).models(args);
        for (JsonNode m : r.path("models")) if (m.path("name").asText().equals("kimi")) assertEquals("missing", m.path("key").asText());
        assertTrue(r.path("summary").asText().contains("deepseek and kimi run on openrouter.ai with no key kept"), r.path("summary").asText());
        // and the add answer carries the same word
        ObjectNode add = args.deepCopy(); add.put("op", "add"); add.put("name", "glm"); add.put("drive", "openrouter"); add.put("model", "z-ai/glm-5.3-flash");
        assertEquals("missing", new LibraryProtocol(store).models(add).path("key").asText());
    }

    @Test
    void theModelIsToldThatThisTurnsToolResultsStandOverTheConversationOnceItHasAny(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        List<ArrayNode> seen = new ArrayList<>();
        Researcher.Drive drive = LibrarianChatTest.scripted(List.of(LibrarianChatTest.tool("library_status", "{}"), LibrarianChatTest.say("The library holds nothing yet.")), seen);
        Librarian lib = new Librarian(store, drive, Librarian.person(), Librarian.Session.open(store));
        lib.say("how big is the library?");
        assertEquals(2, seen.size(), "a tool round, then the words");
        assertFalse(seen.get(0).get(0).path("content").asText().contains(Librarian.TOOL_RESULTS_RULE), "before any tool result the rule is not there");
        assertTrue(seen.get(1).get(0).path("content").asText().endsWith("\n\n" + Librarian.TOOL_RESULTS_RULE), "with a tool result in the turn, the system prompt ends with the rule: " + seen.get(1).get(0).path("content").asText());
        assertEquals("Answer from the tool results of this turn; they are the library's state now, and they stand over anything said earlier in this conversation.", Librarian.TOOL_RESULTS_RULE);
    }

    /** The words that are the turn's and the words that are not: a research question with "model" in it is left alone. */
    @Test
    void theDomainIsTheLibrarysOwnModelsNotEveryModelWord() throws Exception {
        fiveEntries();
        for (String w : new String[]{"which model is the default?", "what model do we use?", "which models do we have", "is kimi set up?", "does deepseek have a key", "why did J-0309 use openrouter?",
                "the default model should be the qwen on homebox gpu2", "make deepseek the default", "use kimi as the default", "switch the default to deepseek", "set the default model to kimi", "既定のモデルはどれ？", "deepseekを既定にして", "どのモデルを使っていますか",
                "J-0309 - woah shouldnt we be using the local - aka qwen 27b model?  why are we using openrouter as the main model"})
            assertTrue(ModelsTurn.about(w), w);
        for (String w : new String[]{"which model of telescope should we buy?", "what does the standard model say about neutrinos", "find out which model of the Antikythera mechanism is in the museum, with deepseek", "how were the gears cut?", "what happened", "research the Saros dial using kimi", "a model of a ship", "/model kimi"})
            assertFalse(ModelsTurn.about(w), w);
        assertEquals("qwen on homebox gpu2", ModelsTurn.changeTarget("the default model should be the qwen on homebox gpu2").replaceFirst("^the ", ""));
        assertEquals("kimi", ModelsTurn.changeTarget("use kimi as the default")); assertEquals("deepseek", ModelsTurn.changeTarget("make deepseek the default from now on")); assertEquals("deepseek", ModelsTurn.changeTarget("deepseekを既定にして"));
        assertNull(ModelsTurn.changeTarget("which model is the default"));
        assertEquals(List.of("homebox"), ModelsTurn.namedHosts("the qwen on homebox gpu2")); assertEquals(List.of("openrouter.ai"), ModelsTurn.namedHosts("why are we using openrouter"));
        assertEquals("default, 27b-gpu1", String.join(", ", ModelEntries.resolveAll("qwen").stream().map(ModelEntries.Entry::name).toList()), "two entries serve the model: both come back, the default first");
        assertEquals("27b-gpu1", ModelEntries.resolve("27b-gpu1").name()); assertEquals("default", ModelEntries.onDrive("http://homebox:8212/v1").name()); assertEquals("27b-gpu1", ModelEntries.onDrive("http://homebox:8213").name());
        assertEquals(List.of("default", "27b-gpu1", "gemma26"), ModelEntries.onHost("homebox").stream().map(ModelEntries.Entry::name).toList());
        assertEquals("a local server, no key needed", ModelEntries.keyText(ModelEntries.get("gemma26"))); assertEquals("kept", ModelEntries.keyWord(ModelEntries.get("kimi")));
    }
}
