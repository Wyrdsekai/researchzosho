package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;
import org.researchzosho.mcp.McpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every way a person or a program asks for a document's translation carries the choice of where what the checks still doubt goes: the
 * notes at the end (each such passage numbered, a last section that lists them) or off (the note beside it alone), the setting
 * translate_notes when the ask says neither. Each surface files a small translation both ways, the service runs it on stand-ins for the
 * model and the scorers, and the job's arguments and the document are read: the last section there with end, gone with off. A scan of the
 * program's own source pins the list: a new way of filing a translation, or a call that files one without the notes, fails here until it
 * is walked below. The Java client's own test files through the client; the Python SDK's test is in sdk/python/tests.
 */
class TranslationNotesParityTest {
    static final ObjectMapper M = new ObjectMapper();
    static final Pattern MARK = Pattern.compile("\\[†?\\d+\\]");
    @TempDir Path tmp;
    String realHome, base, token;
    LibraryStore store;
    LibrarianDaemon d;
    final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    @BeforeEach void up() throws Exception {
        realHome = System.getProperty("user.home");
        System.setProperty("user.home", Files.createDirectories(tmp.resolve("home")).toString()); Config.invalidate();
        TranslationScores.SCORERS = () -> new TranslationScores.Scorers(TranslationTest.labseStandIn(), null); Translation.SETUP_SAID = () -> "";
        Translation.SEATS = (drive, model, judge, back) -> TranslationTest.seats(TranslationNotesTest.dropping());
        Translation.DRIVE_ANSWERS = url -> true; Translation.ANY_MODEL = () -> true;
        store = new LibraryStore(tmp.resolve("lib")); store.init();
        Patrons.set(store, "did:key:zParity", "a program at homebox", Patrons.Level.write);
        token = Patrons.issueToken(store, "did:key:zParity");
        d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1);   // it runs every job filed below, as the service does
        base = d.url();
    }

    @AfterEach void down() {
        if (d != null) d.stop();
        Chrome.clear();
        TranslationScores.SCORERS = TranslationScores::configured; Translation.SETUP_SAID = () -> { String a = TranslationScores.labseSetupSaid(), b = TranslationScores.metricxSetupSaid(); return a.isEmpty() ? b : b.isEmpty() ? a : a + "; " + b; };
        Translation.SEATS = Translation::seatsFor; Translation.DRIVE_ANSWERS = Crews::driveAnswers; Translation.ANY_MODEL = Translation::anyModelNow;
        System.setProperty("user.home", realHome); Config.invalidate();
    }

    // ---- the walk ----

    @Test void theCommandLineAndTheTranslateForm() throws Exception {
        // the command: --notes end and --notes off, and the setting when it says neither; run here, as with no service for this library
        for (String notes : new String[]{"end", "off", ""}) {
            if (notes.isEmpty()) Settings.set(Settings.named("translate_notes"), "end");
            Path in = doc("cli-" + notes), out = tmp.resolve("out").resolve("cli-" + notes + ".ja.md");
            List<String> argv = new ArrayList<>(List.of("researchzosho", "translate", in.toString(), "--to", "ja", "--out", out.toString()));
            if (!notes.isEmpty()) { argv.add("--notes"); argv.add(notes); }
            assertEquals(0, quietly(() -> TranslateCli.translate(store, argv.toArray(String[]::new))), "the command with --notes " + notes);
            assertDocument("the command line, --notes " + (notes.isEmpty() ? "left out, the setting end" : notes), Files.readString(out), !notes.equals("off"));
        }
        Settings.set(Settings.named("translate_notes"), "off");
        assertEquals(2, quietly(() -> TranslateCli.translate(store, new String[]{"researchzosho", "translate", doc("cli-bad").toString(), "--to", "ja", "--notes", "sideways"})), "a word that is neither");
        // the Translate form: the box ticked, the box left unticked; the page says where the doubts go, as filed
        for (boolean ticked : new boolean[]{true, false}) {
            Path up = Files.createTempDirectory(Uploads.DIR);
            Path f = Files.copy(doc("form-" + ticked), up.resolve("form-" + ticked + ".md"));
            Map<String, String> form = new TreeMap<>(Map.of("file.path", f.toString(), "file.name", f.getFileName().toString(), "to", "ja", "notes_box", "1"));
            if (ticked) form.put("notes", "end");
            String page = Pages.translatePost(store, Patrons.Patron.PERSON, form);
            assertTrue(page.contains(Pages.esc(ticked ? Translation.NOTES_AT_END_SAID : Translation.NOTES_OFF_SAID)), "the form's page says where they go: " + page);
            walked("the Translate form, the box " + (ticked ? "ticked" : "unticked"), jobIn(page, "href=\"/jobs/(J-\\d+)\""), ticked);
        }
        Path up = Files.createTempDirectory(Uploads.DIR);
        Path f = Files.copy(doc("form-bad"), up.resolve("form-bad.md"));
        assertTrue(Pages.translatePost(store, Patrons.Patron.PERSON, Map.of("file.path", f.toString(), "file.name", "form-bad.md", "to", "ja", "notes", "sideways")).contains(Pages.esc(Translation.NOTES_SAID)), "a post of a program's own with a word that is neither");
    }

    @Test void theChatsOwnTurnInEnglishAndInJapanese() throws Exception {
        Librarian.Session session = Librarian.Session.open(store);
        Librarian lib = new Librarian(store, TranslateTurnTest.noModel(), Librarian.person(), session);
        // in English, in the person's own words, both ways; the reply says where the doubts go as filed, and how to have them at the end
        String end = lib.say("translate " + doc("chat-en-end") + " into Japanese, checked, and list what the checks doubt at the end");
        assertTrue(end.contains(Translation.NOTES_AT_END_SAID), end);
        walked("the chat in English, \"and list what the checks doubt at the end\"", jobIn(end, "Translating it now, checked \\((J-\\d+)\\)"), true);
        Settings.set(Settings.named("translate_notes"), "end");   // the setting says end; the words say no
        String off = lib.say("translate " + doc("chat-en-off") + " into Japanese, checked, no notes");
        assertTrue(off.contains(Translation.NOTES_OFF_SAID + " " + TranslateTurn.NOTES_HOW), off);
        walked("the chat in English, \"no notes\" over the setting's end", jobIn(off, "Translating it now, checked \\((J-\\d+)\\)"), false);
        String bySetting = lib.say("translate " + doc("chat-en-setting") + " into Japanese, checked");
        walked("the chat in English, nothing said, the setting's end", jobIn(bySetting, "Translating it now, checked \\((J-\\d+)\\)"), true);
        Settings.set(Settings.named("translate_notes"), "off");
        // the chat's own question about the tier says how to have them at the end, and the answer is read for it
        String asked = lib.say("translate " + doc("chat-en-asked") + " into Japanese");
        assertTrue(asked.contains("Checked, quick, reviewed or thorough? ") && asked.contains(TranslateTurn.NOTES_OFF_ASKED), asked);
        String id = jobIn(asked, "\\((J-\\d+); the choice starts it\\)");
        String taken = lib.say("checked, with the notes at the end");
        assertTrue(taken.startsWith("Taken: checked.") && taken.contains(Translation.NOTES_AT_END_SAID), taken);
        walked("the chat's question answered \"checked, with the notes at the end\"", id, true);
        // the notes alone are kept and the question asked again with them; "no notes" there is not a no to the run
        String asked2 = lib.say("translate " + doc("chat-en-notes-first") + " into Japanese");
        String id2 = jobIn(asked2, "\\((J-\\d+); the choice starts it\\)");
        String noted = lib.say("with the notes at the end");
        assertTrue(noted.startsWith("Noted: what the checks still doubt will be numbered at the end of the document too.") && noted.contains(TranslateTurn.NOTES_END_ASKED) && noted.contains("Which: 1 checked, 2 quick, 3 reviewed, 4 thorough?"), noted);
        assertEquals(Jobs.OFFERED, new Jobs(store, j -> "").get(id2).path("state").asText(), "still waiting for the tier");
        assertTrue(lib.say("no notes").startsWith("Noted: what the checks still doubt will be listed in the note beside the translation only."));
        assertEquals(Jobs.OFFERED, new Jobs(store, j -> "").get(id2).path("state").asText(), "\"no notes\" is not a no to the run");
        assertTrue(lib.say("thorough").startsWith("Taken: thorough."));
        walked("the chat's question, the notes said first, then taken back", id2, false);

        // in Japanese: the person's words, the reply's sentence in Japanese, the question's sentence in Japanese and its answer read
        Chrome.MISSING.clear();
        Chrome.here("ja");
        try {
            String ja = lib.say(doc("chat-ja-end") + "を日本語に翻訳して、確認付きで、訳注は末尾に");
            assertTrue(ja.contains(japanese(Translation.NOTES_AT_END_SAID)), ja);
            walked("the chat in Japanese, 「訳注は末尾に」", jobIn(ja, "(J-\\d+)"), true);
            String jaOff = lib.say(doc("chat-ja-off") + "を日本語に翻訳して、確認付きで、訳注なし");
            assertTrue(jaOff.contains(japanese(Translation.NOTES_OFF_SAID)) && jaOff.contains(japanese(TranslateTurn.NOTES_HOW)), jaOff);
            walked("the chat in Japanese, 「訳注なし」", jobIn(jaOff, "(J-\\d+)"), false);
            String jaAsked = lib.say(doc("chat-ja-asked") + "を日本語に翻訳して");
            assertTrue(jaAsked.contains(japanese(TranslateTurn.NOTES_OFF_ASKED)), jaAsked);
            String jaId = jobIn(jaAsked, "（(J-\\d+)。選ぶと始まります）");
            String jaTaken = lib.say("確認付き、訳注は末尾に");
            assertTrue(jaTaken.contains(japanese(Translation.NOTES_AT_END_SAID)), "the tier in Japanese is understood, and the notes with it: " + jaTaken);
            walked("the chat's question answered 「確認付き、訳注は末尾に」", jaId, true);
            String jaNoted = lib.say(doc("chat-ja-noted") + "を日本語に翻訳して");
            jobIn(jaNoted, "（(J-\\d+)。選ぶと始まります）");
            assertTrue(lib.say("訳注は末尾に").startsWith(japanese("Noted: what the checks still doubt will be numbered at the end of the document too.")));
            assertTrue(lib.say("訳注なし").startsWith(japanese("Noted: what the checks still doubt will be listed in the note beside the translation only.")));
            lib.say("none");
            assertTrue(ChatCommands.help(store, null).contains("「訳注は末尾に」と書けば"), "the chat's /help says the words, in Japanese");
        } finally { Chrome.clear(); }
        assertTrue(ChatCommands.help(store, null).contains("\"with the notes at the end\" numbers what the checks still doubt and lists it at the end of the document, \"no notes\" keeps it to the note beside the translation"), "and in English");
        List<String> missing = Chrome.MISSING.stream().filter(m -> m.contains("doubt") || m.contains("notes") || m.contains("Translation {0}")).toList();
        assertTrue(missing.isEmpty(), "the Japanese file lacks: " + missing);
        // the kept ask ("Which file?") carries the notes to the file that comes next
        assertTrue(lib.say("translate this file into Japanese, checked, with the notes at the end").startsWith("Which file?"));
        assertEquals("end", session.translateAsk().notes());
        session.att = M.createObjectNode().put("name", "chat-kept.md").put("path", doc("chat-kept").toString());
        String kept = lib.say("this file");
        walked("the chat's kept ask, the file after \"Which file?\"", jobIn(kept, "Translating it now, checked \\((J-\\d+)\\)"), true);
    }

    @Test void theWebChatWithTheDocumentAttached() throws Exception {
        Librarian.Session s = Librarian.Session.open(store);
        String text = Files.readString(doc("web"));
        for (boolean end : new boolean[]{true, false}) {
            String say = "translate this file into Japanese, checked, " + (end ? "with the notes at the end" : "no notes");
            HttpResponse<String> r = ChatAttachmentTest.post(http, base + "/chat", "----rzparity", ChatAttachmentTest.multipart("----rzparity", s.id, say, "web-" + end + ".md", text), null);
            assertEquals(200, r.statusCode(), r.body());
            assertTrue(r.body().contains(end ? Translation.NOTES_AT_END_SAID : Translation.NOTES_OFF_SAID), r.body());
            walked("the web chat with the file attached, " + (end ? "\"with the notes at the end\"" : "\"no notes\""), jobIn(r.body(), "Translating it now, checked \\((J-\\d+)\\)"), end);
        }
    }

    @Test void theChatsModelCallingTheTool() throws Exception {
        // the tool as the chat's model is given it: the argument in its schema, and its words say when to pass it
        JsonNode fn = null;
        for (JsonNode t : Librarian.tools(store)) if (t.path("function").path("name").asText().equals("library_translate")) fn = t.path("function");
        assertNotNull(fn, "the chat is given library_translate");
        assertEquals("[\"off\",\"end\"]", fn.path("parameters").path("properties").path("notes").path("enum").toString(), fn.toString());
        assertTrue(fn.path("description").asText().contains("notes=end when the person asks for what the checks doubt (the notes) at the end of the document, notes=off when they ask for no notes"), fn.path("description").asText());
        for (String notes : new String[]{"end", "off"}) {
            Path f = doc("model-" + notes);
            List<ArrayNode> seen = new ArrayList<>();
            Researcher.Drive drive = LibrarianChatTest.scripted(List.of(LibrarianChatTest.tool("library_translate", M.createObjectNode().put("path", f.toString()).put("to", "ja").put("quality", "checked").put("notes", notes).toString()), LibrarianChatTest.say("I have filed it.")), seen);
            Librarian lib = new Librarian(store, drive, Librarian.person(), Librarian.Session.open(store));
            String reply = lib.say("please put the clock document into Japanese for me, " + (notes.equals("end") ? "with what the checks doubt at the end" : "no notes"));
            assertEquals(2, seen.size(), "the model was asked, and called the tool: " + reply);
            String id = jobIn(reply, "Translation (J-\\d+):");
            // under the model's words, the library's own line, from the run as filed
            assertTrue(reply.startsWith("I have filed it.") && reply.contains("Translation " + id + ": " + (notes.equals("end") ? Translation.NOTES_AT_END_SAID : Translation.NOTES_OFF_SAID + " " + TranslateTurn.NOTES_HOW)), reply);
            assertEquals("chat", new Jobs(store, j -> "").get(id).path("args").path("by").asText());
            ObjectNode ran = walked("the chat's model calling library_translate, notes=" + notes, id, notes.equals("end"));
            if (notes.equals("off")) assertTrue(ran.path("result").asText().contains("say \"with the notes at the end\" when you ask for the translation in the chat"), ran.path("result").asText());
        }
    }

    @Test void theProtocolMcpOverStdioAndHttpAndTheHttpApi() throws Exception {
        String raw = Corpus.addFile(store, doc("shelved"), "", false).getFileName().toString();
        // the protocol's own call, from the keeper with a path
        for (String notes : new String[]{"end", "off"}) {
            ObjectNode a = M.createObjectNode(); a.put("path", doc("protocol-" + notes).toString()); a.put("to", "ja"); a.put("notes", notes); a.putObject("patron").put("did", "person").put("name", "keeper").put("runtime", "cli");
            ObjectNode r = new LibraryProtocol(store).translate(a);
            assertEquals(notes, r.path("notes").asText());
            walked("library_translate through the protocol, notes=" + notes, r.path("job_id").asText(), notes.equals("end"));
        }
        // MCP over stdio: the envelope the stdio server writes for each line; the schema lists the argument, a bad word is refused
        assertNotesInSchema("MCP over stdio", McpServer.envelopeFor(rpc(1, "tools/list", M.createObjectNode()), store).path("result"));
        for (String notes : new String[]{"end", "off"}) {
            JsonNode env = McpServer.envelopeFor(call(2, raw, notes, M.createObjectNode().put("did", "did:key:zParity")), store);
            assertEquals(notes, env.path("result").path("structuredContent").path("notes").asText(), env.toString());
            walked("MCP over stdio, notes=" + notes, env.path("result").path("structuredContent").path("job_id").asText(), notes.equals("end"));
        }
        JsonNode badStdio = McpServer.envelopeFor(call(3, raw, "sideways", M.createObjectNode().put("did", "did:key:zParity")), store);
        assertEquals("invalid_args", badStdio.path("error").path("data").path("code").asText(), badStdio.toString());
        assertEquals(Translation.NOTES_SAID, badStdio.path("error").path("message").asText());
        // MCP over HTTP: /rpc with the program's token
        assertNotesInSchema("MCP over HTTP", post("/rpc", rpc(4, "tools/list", M.createObjectNode())).path("result"));
        for (String notes : new String[]{"end", "off"}) {
            JsonNode env = post("/rpc", call(5, raw, notes, null));
            assertEquals(notes, env.path("result").path("structuredContent").path("notes").asText(), env.toString());
            walked("MCP over HTTP, notes=" + notes, env.path("result").path("structuredContent").path("job_id").asText(), notes.equals("end"));
        }
        JsonNode badRpc = post("/rpc", call(6, raw, "sideways", null));
        assertEquals("invalid_args", badRpc.path("error").path("data").path("code").asText(), badRpc.toString());
        // the HTTP API: POST /v1/translate, the answer and the job say which; a bad word is a 400 with the sentence
        for (String notes : new String[]{"end", "off"}) {
            JsonNode r = post("/v1/translate", M.createObjectNode().put("raw", raw).put("to", "ja").put("notes", notes));
            assertEquals(notes, r.path("notes").asText(), r.toString());
            walked("POST /v1/translate, notes=" + notes, r.path("job_id").asText(), notes.equals("end"));
            HttpResponse<String> job = http.send(HttpRequest.newBuilder(URI.create(base + "/v1/jobs/" + r.path("job_id").asText())).header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(notes, M.readTree(job.body()).path("notes").asText(), "GET /v1/jobs/{id} says it: " + job.body());
        }
        Settings.set(Settings.named("translate_notes"), "end");
        JsonNode bySetting = post("/v1/translate", M.createObjectNode().put("raw", raw).put("to", "ja"));
        assertEquals("end", bySetting.path("notes").asText(), "left out: the setting");
        walked("POST /v1/translate, notes left out, the setting's end", bySetting.path("job_id").asText(), true);
        HttpResponse<String> bad = http.send(HttpRequest.newBuilder(URI.create(base + "/v1/translate")).header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(M.createObjectNode().put("raw", raw).put("to", "ja").put("notes", "sideways").toString())).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(400, bad.statusCode(), bad.body());
        assertEquals("invalid_args", M.readTree(bad.body()).path("error").path("code").asText());
        assertEquals(Translation.NOTES_SAID, M.readTree(bad.body()).path("error").path("message").asText());
    }

    // ---- the list the walk is held to ----

    /**
     * Every place in the program that files a document's translation or hands library_translate its arguments, by file and count: what
     * the tests above walk. Translation.java is the translation itself and is not counted.
     */
    static final Map<String, Integer> ENTRIES = Map.of(
            "LibrarianCli.java", 1,      // the command: TranslateCli.translate
            "TranslateCli.java", 3,      // its arguments, its job when a service runs, its run here
            "Pages.java", 2,             // the Translate form: its arguments and its job
            "TranslateTurn.java", 4,     // the chat's own turn, the web chat's attachment through it: its arguments and its three ways of filing
            "LibraryProtocol.java", 2,   // library_translate: its arguments and its job
            "Librarian.java", 1,         // the chat's model calling library_translate
            "McpServer.java", 1,         // MCP over stdio, and over HTTP at /rpc
            "LibrarianDaemon.java", 1);  // POST /v1/translate
    static final Pattern ENTRY = Pattern.compile("Translation\\.args\\(|Translation\\.run\\(|submit(?:Offered)?\\(\\s*Translation\\.JOB_KIND|\"translate-doc\"|\\b\\w+\\.translate\\(|::translate\\b");
    static final Pattern FILES_ONE = Pattern.compile("Translation\\.(?:args|run)\\(");

    @Test void everyWayOfAskingIsWalkedAndCarriesTheNotes() throws Exception {
        Path main = GenealogyBoundaryTest.src().resolve("main").resolve("java");
        Map<String, Integer> found = new TreeMap<>();
        List<String> without = new ArrayList<>();
        try (Stream<Path> files = Files.walk(main)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String name = p.getFileName().toString();
                if (name.equals("Translation.java")) continue;
                String src = Files.readString(p);
                Matcher m = ENTRY.matcher(src);
                int n = 0; while (m.find()) n++;
                if (n > 0) found.put(name, n);
                // a call that files a translation passes the person's choice: the form of Translation.args and Translation.run that takes the notes,
                // or a longer one that takes them and more (the language the person says the document is in; the job's id)
                Matcher c = FILES_ONE.matcher(src);
                while (c.find()) if (arguments(src, c.end() - 1) < 12) without.add(name + ":" + (src.substring(0, c.start()).split("\n", -1).length) + " " + c.group());
            }
        }
        assertEquals(new TreeMap<>(ENTRIES), found, "a way of asking for a document's translation was added or taken away: walk it in this test with notes=end and notes=off, and list it in ENTRIES");
        assertTrue(without.isEmpty(), "these file a translation without the person's choice of notes; pass it (the twelve-argument form, or a longer one): " + without);
        // the agent-to-agent interface asks and researches, and translates nothing: a skill that translates carries the notes and is walked here
        assertFalse(Pattern.compile("library_translate|Translation\\.|TranslateTurn|translate-doc").matcher(Files.readString(main.resolve("org/researchzosho/librarian/A2a.java"))).find(), "A2A can ask for a translation now: carry the notes and walk it here");
        // the npm launcher starts the MCP server over stdio, whose schema is the one walked above; it lists no tools of its own
        Path root = GenealogyBoundaryTest.src().toAbsolutePath().normalize().getParent().getParent();
        Path npm = root.resolve("npm").resolve("bin").resolve("index.js");
        if (Files.exists(npm)) { String js = Files.readString(npm); assertTrue(js.contains("run(script, ['mcp', ...args])") && !js.contains("library_translate"), "the launcher passes through to `researchzosho mcp`"); }
        // the clients pass the argument to the wire (their own tests file through them)
        Path client = root.resolve("client/src/main/java/org/researchzosho/client/LibrarianClient.java"), python = root.resolve("sdk/python/researchzosho/__init__.py");
        if (Files.exists(client)) assertTrue(Files.readString(client).contains("a.put(\"notes\", notes.strip())"), "the Java client's translate carries notes");
        if (Files.exists(python)) assertTrue(Files.readString(python).contains("body[\"notes\"] = notes"), "the Python SDK's translate carries notes");
    }

    /** The number of arguments of the call whose opening parenthesis is at {@code open}: commas at its own depth, strings and characters skipped. */
    static int arguments(String src, int open) {
        int depth = 0, n = 1;
        for (int i = open; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '"' || c == '\'') { for (i++; i < src.length() && src.charAt(i) != c; i++) if (src.charAt(i) == '\\') i++; continue; }
            if (c == '(' || c == '{' || c == '[') depth++;
            else if (c == ')' || c == '}' || c == ']') { if (--depth == 0) return n; }
            else if (c == ',' && depth == 1) n++;
        }
        return -1;
    }

    @Test void theArgumentCounterReadsACall() {
        assertEquals(3, arguments("f(a, g(b, c), \"d, e\")", 1));
        assertEquals(2, arguments("f(x -> { int a = 1, b = 2; }, ',')", 1));
    }

    // ---- helpers ----

    /** The clock document under a name of its own, so no two runs write the same file. */
    Path doc(String name) throws Exception {
        Path f = TranslationNotesTest.clock(tmp.resolve("docs").resolve(name));
        return Files.move(f, f.resolveSibling(name + ".md"));
    }

    /** The job the service ran, after the job's arguments are checked and its document read: the last section there, or not. */
    ObjectNode walked(String surface, String id, boolean end) throws Exception {
        ObjectNode filed = new Jobs(store, j -> "").get(id);
        assertNotNull(filed, surface + ": no job " + id);
        assertEquals(Translation.JOB_KIND, filed.path("kind").asText(), surface);
        assertEquals(end ? "end" : "off", filed.path("args").path("notes").asText(), surface + ": the job's arguments " + filed.path("args"));
        ObjectNode ran = ran(id);
        assertDocument(surface, document(ran), end);
        return ran;
    }

    static void assertDocument(String surface, String doc, boolean end) {
        if (end) assertTrue(doc.contains(TranslationNotesTest.KEPT + " [3]\n") && doc.contains("\n## 訳注\n\n[1] number 「7」"), surface + ": the notes at the end: " + doc);
        else assertTrue(!doc.contains("訳注") && !MARK.matcher(doc).find() && doc.strip().endsWith(TranslationNotesTest.KEPT), surface + ": the translation alone: " + doc);
    }

    /** The job once the service has run it. */
    ObjectNode ran(String id) throws Exception {
        Jobs jobs = new Jobs(store, j -> "");
        post("/v1/job", M.createObjectNode());   // the route after which a filed job is taken up at once
        long until = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < until) {
            ObjectNode j = jobs.get(id);
            String state = j == null ? "" : j.path("state").asText();
            if (state.equals("done") || state.equals("failed") || state.equals("stopped")) { assertEquals("done", state, j.toString()); return j; }
            Thread.sleep(50);
        }
        fail(id + " did not finish in a minute: " + jobs.get(id));
        return null;
    }

    /** The translated document a finished job names: under the library's exports, or where the keeper said. */
    String document(ObjectNode job) throws IOException {
        String result = job.path("result").asText();
        Matcher m = Pattern.compile("Download: \\[([^\\]]+)\\]").matcher(result);
        if (m.find()) return Files.readString(Export.exportsDir(store).resolve(m.group(1)));
        m = Pattern.compile("(?m)^The file: (.+)$").matcher(result);
        assertTrue(m.find(), result);
        return Files.readString(Path.of(m.group(1).strip()));
    }

    /** The last job the words name: the chat's page holds every turn of the conversation. */
    static String jobIn(String said, String pattern) {
        Matcher m = Pattern.compile(pattern).matcher(said);
        String last = null;
        while (m.find()) last = m.group(1);
        assertNotNull(last, "no job in: " + said);
        return last;
    }

    /** The Japanese of a sentence, which the Japanese file must have. */
    static String japanese(String en) {
        String ja = Chrome.in("ja").t(en);
        assertNotEquals(en, ja, "the Japanese file has no sentence for: " + en);
        return ja;
    }

    static ObjectNode rpc(int id, String method, ObjectNode params) {
        ObjectNode r = M.createObjectNode(); r.put("jsonrpc", "2.0"); r.put("id", id); r.put("method", method); r.set("params", params); return r;
    }

    static ObjectNode call(int id, String raw, String notes, ObjectNode patron) {
        ObjectNode args = M.createObjectNode().put("raw", raw).put("to", "ja").put("notes", notes);
        if (patron != null) args.set("patron", patron);
        return rpc(id, "tools/call", M.createObjectNode().put("name", "library_translate").set("arguments", args));
    }

    static void assertNotesInSchema(String surface, JsonNode list) {
        for (JsonNode t : list.path("tools")) if (t.path("name").asText().equals("library_translate")) {
            assertEquals("[\"off\",\"end\"]", t.path("inputSchema").path("properties").path("notes").path("enum").toString(), surface + ": " + t);
            assertTrue(t.path("description").asText().contains("Pass notes=end when the person asks for the doubts (the notes) at the end of the document"), surface + ": " + t.path("description").asText());
            return;
        }
        fail(surface + " does not list library_translate: " + list);
    }

    JsonNode post(String path, ObjectNode body) throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(base + path)).header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(), HttpResponse.BodyHandlers.ofString());
        return M.readTree(r.body());
    }

    interface Run { int run() throws Exception; }

    /** A command's exit code, its printing kept out of the test's own output. */
    static int quietly(Run r) throws Exception {
        PrintStream out = System.out, err = System.err;
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        System.setOut(new PrintStream(sink, true, StandardCharsets.UTF_8)); System.setErr(new PrintStream(sink, true, StandardCharsets.UTF_8));
        try { return r.run(); } finally { System.setOut(out); System.setErr(err); }
    }
}
