package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;
import org.researchzosho.drive.DriveClient;
import org.researchzosho.mcp.McpServer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

/**
 * The surfaces of a translation: the chat's own turn (the file attached or on the shelves, the time said first, the tier asked, the glossary
 * line), the Keep page's form, the protocol's tool and the command line; the model stood in for, and no model asked by the chat itself.
 */
class TranslateTurnTest {
    static final ObjectMapper M = new ObjectMapper();

    static Researcher.Drive noModel() {
        return new Researcher.Drive() {
            @Override public ObjectNode chat(ArrayNode messages, ArrayNode tools, int maxTokens, String toolChoice) { throw new AssertionError("the turn is the library's own: the model is not asked"); }
            @Override public String classify(ArrayNode messages, int maxTokens) { return ""; }
            @Override public int contextWindow() { return 16384; }
        };
    }

    @BeforeEach void standInTheScorers() { TranslationScores.SCORERS = () -> new TranslationScores.Scorers(TranslationTest.labseStandIn(), null); Translation.SETUP_SAID = () -> ""; }
    @AfterEach void restoreTheScorers() { TranslationScores.SCORERS = TranslationScores::configured; Translation.SETUP_SAID = () -> { String a = TranslationScores.labseSetupSaid(), b = TranslationScores.metricxSetupSaid(); return a.isEmpty() ? b : b.isEmpty() ? a : a + "; " + b; }; }

    static Path notes(Path dir, String name) throws Exception {
        Path f = dir.resolve(name);
        Files.writeString(f, "# The Saros dial\n\nThe dial predicts eclipses over 223 lunar months, about 18 years.\n\n- the month\n- the hour\n", StandardCharsets.UTF_8);
        return f;
    }

    @Test void theWordsAreReadForAFileALanguageAFormatAndATier() {
        assertNull(TranslateTurn.parse("what is the saros?"));
        assertNull(TranslateTurn.parse("translate this into Klingon"), "a language the library does not know is left to the model");
        assertEquals(new TranslateTurn.Ask("this file", "ja", "", ""), TranslateTurn.parse("Translate this file into Japanese"));
        assertEquals(new TranslateTurn.Ask("saros notes.md", "de", "pdf", "checked"), TranslateTurn.parse("translate saros notes.md to German as a PDF, checked"));
        assertEquals(new TranslateTurn.Ask("it", "ja", "", "thorough"), TranslateTurn.parse("translate it into Japanese, thorough"));
        assertEquals(new TranslateTurn.Ask("the notes", "ja", "epub", "reviewed"), TranslateTurn.parse("translate the notes into Japanese as epub, reviewed"));
        assertEquals(new TranslateTurn.Ask("it", "fr", "", "quick"), TranslateTurn.parse("please translate it into French, quick"));
        assertEquals(new TranslateTurn.Ask("このファイル", "ja", "", "checked"), TranslateTurn.parse("このファイルを日本語に翻訳して、確認付きで"));
        assertEquals(new TranslateTurn.Ask("それ", "en", "epub", ""), TranslateTurn.parse("それを英語に訳して EPUBで"));
        // the models named: the reader after "checked/read/reviewed by", the translator after "translated by" or a plain "by"
        assertEquals(new TranslateTurn.Ask("it", "ja", "", "checked", "", "kimi"), TranslateTurn.parse("translate it into Japanese, checked by kimi"));
        assertEquals(new TranslateTurn.Ask("the notes", "de", "pdf", "reviewed", "kimi", "auto"), TranslateTurn.parse("translate the notes into German as a PDF, translated by kimi, reviewed by the strongest other model"));
        assertEquals(new TranslateTurn.Ask("this file", "fr", "", "", "gemma26", ""), TranslateTurn.parse("translate this file into French by gemma26"));
        assertEquals(new TranslateTurn.Ask("saros notes.md", "ja", "", "checked", "", "auto"), TranslateTurn.parse("translate saros notes.md, checked by auto, into Japanese"));
        // as people say it: the passive, the names as words, the check asked for as the tier (the keeper's words of 2026-11-07)
        assertEquals(new TranslateTurn.Ask("this epub", "en", "", "thorough", "DeepSeek V4.1 Flash", "kimi k3"),
                TranslateTurn.parse("i would like this epub translated into english using DeepSeek V4.1 Flash and having kimi k3 do a thorough check"));
        assertEquals(new TranslateTurn.Ask("it", "ja", "", "", "", ""), TranslateTurn.parse("have it translated to Japanese"));
        assertEquals(new TranslateTurn.Ask("the saros notes", "de", "pdf", "reviewed", "", "gemma26"), TranslateTurn.parse("I want the saros notes translated into German as a PDF, with gemma26 doing a careful review"));
        assertEquals(new TranslateTurn.Ask("saros notes.md", "fr", "", "", "", ""), TranslateTurn.parse("please give me a French translation of saros notes.md"));
        assertEquals(new TranslateTurn.Ask("this file", "en", "", "checked", "", "kimi"), TranslateTurn.parse("translate this file into English and have kimi check it"));
        // the sentence the page puts under the words for a posted file does not hide the ask, and names the entry on the shelves
        String posted = "i would like this epub translated into english using DeepSeek V4.1 Flash and having kimi k3 do a thorough check\n\nI attached rekopis.epub; it is on the shelves as 2026-11-07-8ad9f9068287.md (Rękopis znaleziony w Saragossie).";
        assertEquals(new TranslateTurn.Ask("this epub", "en", "", "thorough", "DeepSeek V4.1 Flash", "kimi k3"), TranslateTurn.parse(posted));
        assertEquals("2026-11-07-8ad9f9068287.md", TranslateTurn.attachedId(posted)); assertNull(TranslateTurn.attachedId("translate it into French"));
        for (String w : new String[]{"this epub", "this file", "the attached document", "this pdf", "my book", "that docx", "this one", "it", "attached", "この本"}) assertTrue(TranslateTurn.pointsAtTheFile(w), w + " is the file posted with the turn");
        assertFalse(TranslateTurn.pointsAtTheFile("this epub is long") || TranslateTurn.pointsAtTheFile("saros notes.md"));
        assertTrue(TranslateTurn.pointsAtTheFile("this file") && TranslateTurn.pointsAtTheFile("Here it is.") && TranslateTurn.pointsAtTheFile("attached") && !TranslateTurn.pointsAtTheFile("this file is long"));
        assertTrue(TranslateTurn.asksTranslation("translate this file into Japanese") && !TranslateTurn.asksTranslation("read this in Japanese"));
    }

    /** Where what the checks still doubt goes, in the person's words: the notes at the end, no notes, or nothing said (the setting decides). */
    @Test void theWordsAreReadForTheNotes() {
        assertEquals(new TranslateTurn.Ask("it", "ja", "", "", "", "", "end"), TranslateTurn.parse("translate it into Japanese, with the notes at the end"));
        assertEquals(new TranslateTurn.Ask("this file", "de", "pdf", "checked", "", "", "off"), TranslateTurn.parse("translate this file into German as a PDF, checked, no notes"));
        assertEquals(new TranslateTurn.Ask("this file", "ja", "", "", "", "", "end"), TranslateTurn.parse("translate this file with the notes at the end into Japanese"));
        assertEquals(new TranslateTurn.Ask("saros notes.md", "fr", "", "reviewed", "", "kimi", "end"), TranslateTurn.parse("translate saros notes.md into French, reviewed by kimi, and list what the checks doubt at the end of the document"));
        assertEquals(new TranslateTurn.Ask("this epub", "en", "", "", "", "", "end"), TranslateTurn.parse("I would like this epub translated into English with translator's notes"));
        assertEquals(new TranslateTurn.Ask("このファイル", "ja", "", "checked", "", "", "end"), TranslateTurn.parse("このファイルを日本語に翻訳して、確認付きで、訳注は末尾に"));
        assertEquals(new TranslateTurn.Ask("このファイル", "ja", "", "", "", "", "end"), TranslateTurn.parse("このファイルを日本語に訳注付きで翻訳して"));
        assertEquals(new TranslateTurn.Ask("それ", "en", "", "", "", "", "off"), TranslateTurn.parse("それを英語に訳して、訳注なしで"));
        // the word notes in a title or a name is not an ask for them
        assertEquals(new TranslateTurn.Ask("my notes on the saros", "de", "", ""), TranslateTurn.parse("translate my notes on the saros into German"));
        assertEquals(new TranslateTurn.Ask("the notes", "ja", "epub", "reviewed"), TranslateTurn.parse("translate the notes into Japanese as epub, reviewed"));
        // an answer to the chat's question: the notes read and taken out, the tier left
        assertEquals("end", TranslateTurn.notesIn("checked, with the notes at the end")); assertEquals("checked", TranslateTurn.withoutNotes("checked, with the notes at the end"));
        assertEquals("off", TranslateTurn.notesIn("yes, no notes")); assertEquals("yes", TranslateTurn.withoutNotes("yes, no notes"));
        assertEquals("end", TranslateTurn.notesIn("確認付き、訳注は末尾に")); assertEquals("確認付き", TranslateTurn.withoutNotes("確認付き、訳注は末尾に"));
        assertEquals("off", TranslateTurn.notesIn("notes=off")); assertEquals("end", TranslateTurn.notesIn("notes: end")); assertEquals("", TranslateTurn.notesIn("thorough"));
        assertEquals("checked", TranslateTurn.tierIn("確認付き")); assertEquals("thorough", TranslateTurn.tierIn("全文査読で"));
        // the chat's own words for the choice are read back as it
        assertTrue(TranslateTurn.NOTES_OFF_ASKED.contains("\"with the notes at the end\"") && TranslateTurn.notesIn("with the notes at the end").equals("end"));
        assertTrue(TranslateTurn.NOTES_END_ASKED.contains("\"no notes\"") && TranslateTurn.notesIn("no notes").equals("off"));
        assertEquals("end", TranslateTurn.notesIn(Chrome.in("ja").t(TranslateTurn.NOTES_OFF_ASKED).replaceAll(".*「([^」]+)」.*", "$1")), "the Japanese sentence's own words");
        assertEquals("off", TranslateTurn.notesIn(Chrome.in("ja").t(TranslateTurn.NOTES_END_ASKED).replaceAll(".*「([^」]+)」.*", "$1")));
    }

    @Test void theChatSaysTheTimeFirstAsksTheTierAndRunsOnTheAnswer(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Path f = notes(Files.createDirectories(tmp.resolve("docs")), "saros notes.md");
        Librarian.Session session = Librarian.Session.open(store);
        Librarian lib = new Librarian(store, noModel(), Librarian.person(), session);
        Translation.DRIVE_ANSWERS = url -> true;
        Translation.ANY_MODEL = () -> true;
        try {
            assertEquals("Which file? Attach it to this message (\"this file\" with it is enough: the ask is kept), name a document on the shelves by its title, or give its path on the library's machine. The formats read: txt, md, pdf, docx, epub.", lib.say("translate this file into Japanese"));
            // the file attached to the turn, as the page keeps it; the time said first, the tier asked
            session.att = M.createObjectNode().put("name", "saros notes.md").put("path", f.toString());
            String reply = lib.say("translate this file into Japanese");
            assertTrue(reply.startsWith("\"saros notes.md\" has 4 units with words (") && reply.contains("written in English. Into Japanese as md: ") && reply.contains(" checked, the default; quick ") && reply.contains(", thorough ") && reply.contains("(a first guess: nothing has been timed on this model yet). Checked, quick, reviewed or thorough? ") && reply.contains("(J-0001; the choice starts it)"), reply);
            List<ObjectNode> active = new Jobs(store, j -> "").active();
            assertEquals(1, active.size()); assertEquals(Jobs.OFFERED, active.get(0).path("state").asText()); assertEquals(Translation.JOB_KIND, active.get(0).path("kind").asText()); assertEquals(f.toString(), active.get(0).path("args").path("path").asText());
            String taken = lib.say("thorough");
            assertTrue(taken.startsWith("Taken: thorough. The translation J-0001 runs now; this chat says when it is done"), taken);
            ObjectNode job = new Jobs(store, j -> "").get("J-0001");
            assertEquals("queued", job.path("state").asText()); assertEquals("thorough", job.path("args").path("choice").asText());
            // a path in the words, from the keeper; the tier in the words; a short run starts at once
            String direct = lib.say("translate " + f + " into German, quick");
            assertTrue(direct.contains("Into German as md: ") && direct.contains("Translating it now, quick (J-0002): "), direct);
            assertEquals("queued", new Jobs(store, j -> "").get("J-0002").path("state").asText());
            // a document on the shelves, by its title
            Corpus.addFile(store, f, "notes", false);
            String shelved = lib.say("translate the saros dial into French");
            assertTrue(shelved.contains("\"saros notes.md\" has 4 units") && shelved.contains("Into French"), shelved);
            // the glossary line, and the list
            assertTrue(lib.say("render Acme Corp as アクメ社 in Japanese").startsWith("The glossary renders \"Acme Corp\" as \"アクメ社\" in Japanese from now on."));
            assertTrue(lib.say("用語集：Saros → サロス（日本語）").contains("\"Saros\" as \"サロス\""));
            assertTrue(lib.say("show me the glossary").contains("Acme Corp → アクメ社 (Japanese)"));
            assertEquals(Map.of("Acme Corp", "アクメ社", "Saros", "サロス"), Glossary.forLanguage(store, "ja"));
            // no model at all: one sentence
            Translation.ANY_MODEL = () -> false;
            assertEquals(Translation.NO_MODEL, lib.say("translate " + f + " into Italian"));
        } finally { Translation.DRIVE_ANSWERS = Crews::driveAnswers; Translation.ANY_MODEL = Translation::anyModelNow; }
    }

    /** The models the words name go into the run: the reader as judge, the translator as its entry; the chat's /model translates when the words name none; an unknown name is answered, not run. */
    @Test void theChatRunsWithTheModelsTheWordsNameOrTheOneItTalksTo(@TempDir Path tmp) throws Exception {
        String home = System.getProperty("user.home"); System.setProperty("user.home", tmp.resolve("home").toString()); Config.invalidate();
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Path f = notes(Files.createDirectories(tmp.resolve("docs")), "saros notes.md");
        Librarian.Session session = Librarian.Session.open(store);
        Librarian lib = new Librarian(store, noModel(), Librarian.person(), session);
        Translation.DRIVE_ANSWERS = url -> true;
        Translation.ANY_MODEL = () -> true;
        try {
            Config.set("RESEARCHZOSHO_DRIVE", "http://homebox:8212"); Config.set("RESEARCHZOSHO_MODEL", "qwen3.8-27b");
            ModelEntries.put("gemma26", "http://homebox:8213", "gemma-4-26b-a4b", null, null, null, null);
            ModelEntries.put("kimi", "openrouter", "moonshotai/kimi-k3", "0.192/16/0.182", null, null, null);
            // a hosted entry without its host's key is said before anything runs
            String keyless = lib.say("translate " + f + " into German, quick, checked by kimi");
            assertTrue(keyless.startsWith("kimi runs on openrouter.ai and no key is kept for it. Keep one: researchzosho models key openrouter <key> (a key from openrouter.ai/keys;") && keyless.contains("Models that can run now: default (qwen3.8-27b), gemma26 (gemma-4-26b-a4b); hosted, no key yet: kimi"), keyless);
            assertEquals(0, new Jobs(store, j -> "").active().size(), "nothing was filed");
            Config.set(DriveClient.hostKeySetting("openrouter.ai"), "sk-or-test");
            String said = lib.say("translate " + f + " into German, quick, translated by gemma26, checked by kimi");
            assertTrue(said.contains("Translated by gemma26. Checked by kimi.") && said.contains("Translating it now, quick (J-0001)"), said);
            ObjectNode job = new Jobs(store, j -> "").get("J-0001");
            assertEquals("kimi", job.path("args").path("judge").asText()); assertEquals("gemma26", job.path("args").path("model_entry").asText());
            // the model this chat talks to translates when the words name none
            session.model("gemma26");
            said = lib.say("translate " + f + " into French, quick");
            assertTrue(said.contains("Translated by gemma26.") && said.contains("(J-0002)"), said);
            assertEquals("gemma26", new Jobs(store, j -> "").get("J-0002").path("args").path("model_entry").asText());
            assertEquals("", new Jobs(store, j -> "").get("J-0002").path("args").path("judge").asText(""));
            // auto for the reader is kept as the word the run reads
            said = lib.say("translate " + f + " into Italian, quick, checked by the strongest other model");
            assertTrue(said.contains("Checked by the strongest other model the library has."), said);
            assertEquals("auto", new Jobs(store, j -> "").get("J-0003").path("args").path("judge").asText());
            // the names as people say them resolve to the entries: "Kimi K3" is kimi (moonshotai/kimi-k3)
            said = lib.say("i would like " + f + " translated into Spanish using gemma26 and having Kimi K3 do a thorough check");
            assertTrue(said.contains("Translated by gemma26. Checked by kimi.") && said.contains("thorough"), said);
            assertEquals("kimi", new Jobs(store, j -> "").get("J-0004").path("args").path("judge").asText()); assertEquals("thorough", new Jobs(store, j -> "").get("J-0004").path("args").path("quality").asText());
            // a name no entry comes close to: the entries that exist are said, nothing is run
            String no = lib.say("translate " + f + " into Italian, checked by DeepSeek V4.1 Flash");
            assertTrue(no.startsWith("No model entry matches \"DeepSeek V4.1 Flash\". Models that can run now: default (qwen3.8-27b), gemma26 (gemma-4-26b-a4b); hosted, key kept: kimi (moonshotai/kimi-k3). To add the one named: researchzosho models add deepseek openrouter deepseek/deepseek-v4.1-flash --prices in/out/cache")
                    && no.contains("or researchzosho models add deepseek nebius deepseek-ai/DeepSeek-V4.1-Flash; then researchzosho models key openrouter <key> (a key from openrouter.ai/keys;") && no.endsWith("Meanwhile auto picks among the models here (\"checked by the strongest other model\", in a translation's words)."), no);
            String nobody = lib.say("translate " + f + " into Italian, translated by nobody");
            assertTrue(nobody.startsWith("No model entry matches \"nobody\". Models that can run now:") && nobody.contains("One is added with: researchzosho models add <name> <openrouter | anthropic | openai | gemini | nebius | huggingface | an address> <model id>"), nobody);
            assertEquals(4, new Jobs(store, j -> "").active().size(), "no fifth run was filed");
            // the ask without its file is kept; "this file" with the file runs it
            session.att = null;
            assertTrue(lib.say("translate the missing thing into Korean, quick, checked by kimi").startsWith("Which file?"), "a file attached earlier in the conversation counts as \"this file\", so the ask names a document that is nowhere");
            assertEquals(4, new Jobs(store, j -> "").active().size(), "nothing ran without the file");
            session.att = M.createObjectNode().put("name", f.getFileName().toString()).put("path", f.toString());
            String carried = lib.say("this file\n\nI attached " + f.getFileName() + "; it is on the shelves as nothing-yet.md (saros notes).");
            assertTrue(carried.contains("Into Korean as md") && carried.contains("Checked by kimi.") && carried.contains("(J-0005)"), carried);
            assertEquals("ko", new Jobs(store, j -> "").get("J-0005").path("args").path("to").asText()); assertEquals("quick", new Jobs(store, j -> "").get("J-0005").path("args").path("quality").asText());
            assertNull(session.translateAsk(), "the kept ask is spent");
            // words about a translation the turn cannot read are answered by the turn, never the model
            String unread = lib.say("can you get me a translation of this document, something good");
            assertTrue(unread.startsWith("I read this as a translation ask but could not tell which language to translate into. Say it as \"translate this file into Japanese\"") && unread.contains("Models that can run now:"), unread);
            String unread2 = lib.say("I need the attached thing in Japanese, translation please\n\nI attached x.pdf; it is on the shelves as nothing.md (x).");
            assertTrue(unread2.startsWith("I read this as a translation ask but could not tell which document of the ones named."), unread2);
            assertFalse(TranslateTurn.mentions("who translated Eco's novels into English") && TranslateTurn.namesALanguage("who translated Eco's novels into English") && false, "a research question is not hijacked: no file, no 'this file'");
        } finally { Translation.DRIVE_ANSWERS = Crews::driveAnswers; Translation.ANY_MODEL = Translation::anyModelNow; System.setProperty("user.home", home); Config.invalidate(); }
    }

    @Test void theKeepPageFormAndTheProtocolToolFileTheRunWithThePlan(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Translation.ANY_MODEL = () -> true;
        try {
            String form = Pages.translateForm(store);
            assertTrue(form.contains("action=\"/translate\" enctype=\"multipart/form-data\"") && form.contains("name=\"to\"") && form.contains("name=\"as\"") && form.contains("name=\"quality\"") && form.contains("name=\"judge\"") && form.contains("name=\"back\"") && form.contains("value=\"glossary-add\""), form);
            Path up = Files.createTempDirectory(Uploads.DIR);
            Path posted = notes(up, "posted notes.md");
            String out = Pages.translatePost(store, Patrons.Patron.PERSON, Map.of("file.path", posted.toString(), "file.name", "posted notes.md", "to", "ja", "as", "pdf", "quality", "reviewed"));
            assertTrue(out.contains("<b>Filed as <a href=\"/jobs/J-0001\">J-0001</a>.</b>") && out.contains("posted notes.md: 4 units with words") && out.contains("Into Japanese as pdf, reviewed: ") && out.contains("All four tiers: ") && out.contains("checked, the default; quick"), out);
            assertFalse(Files.exists(up), "the upload folder is dropped once the file is kept under the library");
            ObjectNode job = new Jobs(store, j -> "").get("J-0001");
            assertEquals(Translation.inbox(store).resolve("posted notes.md").toString(), job.path("args").path("path").asText());
            assertEquals("pdf", job.path("args").path("as").asText()); assertEquals("web", job.path("args").path("by").asText());
            assertTrue(Pages.translatePost(store, Patrons.Patron.PERSON, Map.of("op", "glossary-add", "term", "Saros", "lang", "ja", "rendering", "サロス")).contains("renders &quot;Saros&quot; as &quot;サロス&quot;"));
            assertTrue(Pages.translateForm(store).contains("Saros → サロス"));
            // the protocol's tool: the plan at once, the job filed; a path from the keeper only; the glossary op
            Path f = notes(Files.createDirectories(tmp.resolve("docs")), "saros notes.md");
            ObjectNode a = M.createObjectNode(); a.put("path", f.toString()); a.put("to", "Japanese"); a.put("quality", "quick"); a.putObject("patron").put("did", "person").put("name", "keeper").put("runtime", "cli");
            ObjectNode r = new LibraryProtocol(store).translate(a);
            assertEquals("J-0002", r.path("job_id").asText()); assertEquals(4, r.path("units").asInt()); assertEquals("ja", r.path("to").asText()); assertEquals("md", r.path("as").asText()); assertTrue(r.path("estimate").asText().contains("quick") && r.path("estimates").asText().contains("checked, the default") && r.path("estimate_seconds_by_tier").has("thorough"), r.toString());
            assertTrue(r.path("summary").asText().startsWith("Filed J-0002: \"saros notes.md\" (4 units"), r.path("summary").asText());
            ObjectNode fromReader = a.deepCopy(); fromReader.putObject("patron").put("did", "did:key:reader").put("name", "a reader").put("runtime", "mcp");
            Patrons.set(store, "did:key:reader", "a reader", Patrons.Level.write);
            assertEquals("forbidden", assertThrows(ProtocolError.class, () -> new LibraryProtocol(store).translate(fromReader)).code);
            ObjectNode byRaw = fromReader.deepCopy(); byRaw.remove("path"); byRaw.put("raw", RawCapture.find(store, "file://" + f.toAbsolutePath().normalize()) == null ? Corpus.addFile(store, f, "", false).getFileName().toString() : RawCapture.find(store, "file://" + f.toAbsolutePath().normalize()).getFileName().toString());
            assertEquals("J-0003", new LibraryProtocol(store).translate(byRaw).path("job_id").asText(), "a document on the shelves may be named by any writer");
            ObjectNode g = M.createObjectNode(); g.put("op", "glossary"); g.put("term", "Acme Corp"); g.put("rendering", "アクメ社"); g.put("lang", "ja"); g.putObject("patron").put("did", "person").put("name", "keeper").put("runtime", "cli");
            assertEquals(2, new LibraryProtocol(store).translate(g).path("entries").size());
            // the tool is on the list, and dispatched
            JsonNode tools = McpServer.handle("tools/list", M.createObjectNode()).get("tools");
            JsonNode tool = null; for (JsonNode t : tools) if (t.path("name").asText().equals("library_translate")) tool = t;
            assertNotNull(tool); assertTrue(tool.path("inputSchema").path("properties").has("judge") && tool.path("inputSchema").path("properties").has("model") && tool.path("inputSchema").path("properties").has("back_translation"));
            // no model: unavailable, in one sentence
            Translation.ANY_MODEL = () -> false;
            assertEquals("unavailable", assertThrows(ProtocolError.class, () -> new LibraryProtocol(store).translate(a)).code);
        } finally { Translation.ANY_MODEL = Translation::anyModelNow; }
    }

    @Test void theCommandLineSaysTheTimeAndRunsHereWhenNoServiceServesTheLibrary(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Path f = notes(Files.createDirectories(tmp.resolve("docs")), "saros notes.md");
        TranslationTest.Script sc = new TranslationTest.Script();
        Translation.SEATS = (drive, model, judge, back) -> TranslationTest.seats(sc);
        Translation.ANY_MODEL = () -> true; Translation.DRIVE_ANSWERS = url -> true;
        var bo = new ByteArrayOutputStream(); var be = new ByteArrayOutputStream();
        var out = System.out; var err = System.err;
        System.setOut(new PrintStream(bo, true, StandardCharsets.UTF_8)); System.setErr(new PrintStream(be, true, StandardCharsets.UTF_8));
        try {
            assertEquals(2, TranslateCli.translate(store, new String[]{"librarian", "translate", f.toString()}), "a language is needed");
            assertEquals(0, TranslateCli.translate(store, new String[]{"librarian", "translate", f.toString(), "--to", "ja", "--quick"}));
            String said = bo.toString(StandardCharsets.UTF_8);
            assertTrue(said.contains("\"saros notes.md\": 4 units with words") && said.contains("Into Japanese as md, quick: ") && said.contains("\n  (") && said.contains(" checked, the default; quick ") && said.contains("Translated \"The Saros dial\" into Japanese (quick)") && said.contains("The file: " + f.resolveSibling("saros notes.ja.md")), said);
            bo.reset();
            assertEquals(0, TranslateCli.translate(store, new String[]{"librarian", "translate", f.toString(), "--to", "de"}), "the default tier runs here too");
            assertTrue(bo.toString(StandardCharsets.UTF_8).contains("Into German as md, checked: ") && bo.toString(StandardCharsets.UTF_8).contains("(checked)"), bo.toString(StandardCharsets.UTF_8));
            assertTrue(Files.exists(f.resolveSibling("saros notes.ja.md")) && Files.exists(f.resolveSibling("saros notes.ja.translation.md")));
            bo.reset();
            assertEquals(0, TranslateCli.glossary(store, new String[]{"librarian", "glossary", "add", "Saros", "ja", "サロス"}));
            assertEquals(0, TranslateCli.glossary(store, new String[]{"librarian", "glossary", "list"}));
            assertTrue(bo.toString(StandardCharsets.UTF_8).contains("Saros → サロス (Japanese)"));
            assertEquals(2, TranslateCli.translate(store, new String[]{"librarian", "translate", f.toString(), "--to", "ja", "--model", "nobody"}));
            assertTrue(be.toString(StandardCharsets.UTF_8).contains("No model entry matches \"nobody\"."));
        } finally { System.setOut(out); System.setErr(err); Translation.SEATS = Translation::seatsFor; Translation.ANY_MODEL = Translation::anyModelNow; Translation.DRIVE_ANSWERS = Crews::driveAnswers; }
    }
}
