package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;
import org.researchzosho.mcp.McpServer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The chat's table, made smaller (0.8.1): it went with every round of every turn, 18,332 tokens of the release model's 32,768 and more
 * than the whole window of a model served with 16,384 (the server's own count, 2026-11-21). What the chat leaves out the program fills
 * itself or a chat has no use for; the protocol keeps every argument; a rule a past mistake put in a tool's words stays in the chat's.
 */
class ChatTableTest {
    static final ObjectMapper M = new ObjectMapper();
    @TempDir Path tmp;
    String home;

    @AfterEach void restore() { if (home != null) { System.setProperty("user.home", home); Config.invalidate(); } }

    static Map<String, JsonNode> chat() {
        Map<String, JsonNode> out = new LinkedHashMap<>();
        for (JsonNode t : Librarian.tools()) out.put(t.path("function").path("name").asText(), t.path("function"));
        return out;
    }

    static Map<String, JsonNode> protocol() {
        Map<String, JsonNode> out = new LinkedHashMap<>();
        for (JsonNode t : McpServer.allTools()) out.put(t.path("name").asText(), t);
        return out;
    }

    /** All the words the chat's model reads of one tool: its description and every argument's. */
    static String words(JsonNode fn) {
        StringBuilder b = new StringBuilder(fn.path("description").asText(""));
        fn.path("parameters").path("properties").fields().forEachRemaining(e -> b.append('\n').append(e.getKey()).append(": ").append(e.getValue().path("description").asText("")));
        return b.toString();
    }

    @Test
    void theChatLeavesOutOnlyWhatTheProgramFillsAndTheProtocolKeepsEveryArgument() {
        Map<String, JsonNode> chat = chat(), protocol = protocol();
        ChatTable.LEFT_OUT.forEach((tool, args) -> {
            for (String a : args) {
                assertFalse(chat.get(tool).path("parameters").path("properties").has(a), "the chat's " + tool + " has no " + a);
                assertTrue(protocol.get(tool).path("inputSchema").path("properties").has(a), "the protocol's " + tool + " still takes " + a);
            }
        });
        assertEquals(List.of("kind", "name", "shows", "min_width", "min_height", "orientation", "format", "ai", "differ_by", "differ_quote", "minutes", "keep_full"), ChatTable.LEFT_OUT.get("library_images"));
        // what a person can ask for through the model and nothing else gives: the translation's format, and the locale of a run's crisis help
        assertTrue(chat.get("library_translate").path("parameters").path("properties").has("as"), "the format a person asks for reaches a translation only through as");
        for (String t : List.of("library_research", "library_items", "library_check", "library_questions", "library_meeting", "library_absorb"))
            assertTrue(chat.get(t).path("parameters").path("properties").has("locale"), t + " keeps locale: it says which country's help a run names");
        for (String t : List.of("library_frontier", "library_inbox", "library_bridges"))
            assertEquals(protocol.get(t).path("inputSchema").path("properties").size() - 1, chat.get(t).path("parameters").path("properties").size(), t + " keeps every argument but the patron");
        // the pictures a call names: a list of strings, where the protocol's list had no shape and the model sent [] or made-up objects
        JsonNode images = chat.get("library_images").path("parameters").path("properties").path("images");
        assertEquals("string", images.path("items").path("type").asText(), images.toString());
        assertTrue(protocol.get("library_images").path("inputSchema").path("properties").path("images").path("items").isEmpty(), "the protocol takes a find's found[] objects too");
        ChatTable.WORDS.forEach((key, w) -> {
            String[] k = key.split("\\.");
            assertEquals(w, chat.get(k[0]).path("parameters").path("properties").path(k[1]).path("description").asText(), key);
        });
        for (JsonNode fn : chat.values()) {
            JsonNode req = fn.path("parameters").path("required");
            for (JsonNode r : req) assertTrue(fn.path("parameters").path("properties").has(r.asText()), fn.path("name").asText() + " requires only what it takes: " + r);
        }
    }

    @Test
    void everyRuleAPastMistakePutInAToolsWordsIsInTheChatsWords() {
        Map<String, JsonNode> chat = chat();
        // tool → sentences, each there because of a defect or the owner's word (the commit is named in the step's hand-back)
        Map<String, List<String>> rules = new LinkedHashMap<>();
        rules.put("library_translate", List.of("copied as they wrote them, never your summary", "notes=end when the person asks for what the checks doubt (the notes) at the end of the document, notes=off when they ask for no notes",
                "both only names library_models lists", "comic=on when the person says a PDF is a manga", "reads = right-to-left when the person says the comic reads from the right", "a run with one waits for their yes"));
        rules.put("library_images", List.of("keeping what is found is a collection (keep=true files one, as op=collect does)", "write them as plain addresses", "never a folder on disk",
                "When no model sees pictures the answer says how one is added: say that, in the library's words"));
        rules.put("library_research", List.of("holds the run until the person picks one: the library asks them under your reply, so ask nothing yourself", "paid=false keeps it to the free search places",
                "library_models op=list names them", "max_minutes or max_turns caps it when the person gives a time or a count of steps"));
        rules.put("library_my_shelf", List.of("check every book before recommending it", "a bulk import counts as holdings, not taste", "A rating counts only when the person gave it", "A reference, never a requirement"));
        rules.put("library_frontier", List.of("exactly as listed"));
        rules.put("library_settings", List.of("after the person said so in this conversation"));
        rules.put("library_share", List.of("Installing a missing program is the person's own step: tell them to say /share"));
        rules.put("library_help", List.of("the question in English words"));
        for (var e : rules.entrySet()) for (String s : e.getValue()) assertTrue(words(chat.get(e.getKey())).contains(s), e.getKey() + ": " + s);
        // a collection's form left the chat's table; its rules went with it into the form the chat's model fills from the person's request
        StringBuilder fb = new StringBuilder(); for (String[] f : CollectionForm.FIELDS.values()) fb.append(f[1]).append('\n'); String form = fb.toString();
        for (String s : List.of("copied exactly as the person wrote it in the request", "The library checks the name is there", "those words copied exactly from the request",
                "The library keeps differ_by only when these words are in the request", "4K is 3840 wide by 2160 tall"))
            assertTrue(form.contains(s), "the form keeps: " + s);
    }

    @Test
    void theChatCarriesNoInstrumentOnlyARunUsesNorARunsRules() throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        List<Instrument> table = Instruments.forChat(store);
        assertEquals(List.of("search", "open", "look", "patents", "patent_abstracts", "patent_claims", "records", "homes", "board"), table.stream().map(Instrument::name).toList());
        for (String gone : ChatTable.NOT_IN_CHAT) assertFalse(Instruments.isInstrument(table, gone), gone);
        List<Instrument> run = Instruments.forRun(store, new LinkedHashMap<>(), new Researcher.Ask("q", "broad", 0, List.of(), "web", List.of(), 0, List.of(), List.of()));
        assertTrue(Instruments.isInstrument(run, "candidates") && Instruments.isInstrument(run, "sift") && Instruments.isInstrument(run, "dossier"), "a run keeps them");
        // the patent instruments' words in the chat: what they answer and when they pay as before, a run's spending gone, the key and the example kept
        for (Instrument i : table) {
            String w = ChatTable.words(i);
            String[] mine = w.split(" · "), theirs = i.words().split(" · ");
            assertEquals(theirs[0], mine[0], i.name() + ": what it answers");
            if (theirs.length > 1) assertEquals(theirs[1], mine[1], i.name() + ": when it pays");
        }
        Instrument patents = table.get(3), claims = table.get(5), abstracts = table.get(4);
        assertFalse(ChatTable.words(patents).contains("megabytes"), ChatTable.words(patents));
        assertTrue(ChatTable.words(patents).contains("it needs the office's free key") && ChatTable.words(patents).contains("Example: {\"classes\": [\"A47G19/22\"]"), ChatTable.words(patents));
        assertTrue(ChatTable.words(claims).contains("never by opening a patent's page yourself") && ChatTable.words(claims).contains("{\"publications\": [\"EP1000000A1\"]}") && !ChatTable.words(claims).contains("every three seconds"), ChatTable.words(claims));
        assertTrue(!ChatTable.words(abstracts).contains("on a 27B") && ChatTable.words(abstracts).contains("page=2 goes on"), ChatTable.words(abstracts));
        // the chat's instructions: no run's rules
        Librarian lib = new Librarian(store, LibrarianChatTest.scripted(List.of(LibrarianChatTest.say("ok")), new ArrayList<>()), Librarian.person(), Librarian.Session.open(store));
        String note = lib.instrumentsNote(), system = lib.systemPrompt() + "\n" + note;
        for (String run_s : List.of("pursue", "file {", "done with", "words alone are not a step", "The answer is a call", "sift {", "candidates {", "is dossier.", "shelf:"))
            assertFalse(system.contains(run_s), "a run's rule in the chat's instructions: " + run_s);
        // each instrument's "when it pays" is in the instructions as a run has it: without these lines the battery's "Open <address>" was
        // saved instead of opened, in both runs (2026-11-21), and opened again with them back
        for (Instrument i : table) assertTrue(system.contains("  " + i.name() + ": " + Brain.whenItPays(i.words())), i.name());
        assertTrue(note.contains("video-sites is for other video sites (Vimeo, Bilibili, niconico)") && note.contains("is search with where=shelves"), note);
        assertTrue(note.contains("A video the person remembers but cannot name is a research run: file it with library_research, goal choose"), "the chat still files a remembered video as a run: " + note);
        // the small tier keeps the shapes to copy and none of a run's
        String small = new Brain(BrainTest.drive("w"), null, "qwen3.5-9b", null, Brain.Tier.SMALL, null).chatPreamble(table);
        assertTrue(small.contains("search " + table.get(0).example()) && !small.contains("pursue") && !small.contains("candidates") && !small.contains("The answer is a call"), small);
        // and a run's own words are as they were
        String runs = new Brain(BrainTest.drive("w"), null, "qwen3.8-27b", null, Brain.Tier.LARGE, null).preamble(run);
        assertTrue(runs.contains("pursued with pursue") && runs.contains("candidates: the first move for a remembered video"), runs);
    }

    @Test
    void aCallToAnInstrumentTheChatNoLongerCarriesIsRefusedAndNothingRuns() throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        List<String> ran = new ArrayList<>();
        var cand = Instruments.CANDIDATES; var sift = Instruments.SIFT;
        Instruments.CANDIDATES = InstrumentsTest.fake("candidates", ran); Instruments.SIFT = InstrumentsTest.fake("sift", ran);
        try {
            for (String[] call : new String[][]{{"candidates", "{\"things\":[\"apron\"]}"}, {"sift", "{\"what\":\"an apron\",\"from\":\"k\"}"}, {"dossier", "{\"subject\":\"Ada Lovelace\"}"}, {"shelf", "{\"query\":\"kilns\"}"}}) {
                Librarian.Session s = Librarian.Session.open(store);
                var drive = LibrarianChatTest.scripted(List.of(LibrarianChatTest.tool(call[0], call[1]), LibrarianChatTest.say("I could not.")), new ArrayList<>());
                String reply = new Librarian(store, drive, Librarian.person(), s).say("help me with " + call[0]);
                String result = "";
                for (JsonNode m : s.messages()) if ("tool".equals(m.path("role").asText())) result = m.path("content").asText();
                assertTrue(result.startsWith("ERROR: no tool named " + call[0] + " is on the table of this conversation"), call[0] + ": " + result);
                assertFalse(reply.contains("The model asks to"), "nothing is put to the person: " + reply);
            }
            assertEquals(List.of(), ran, "no instrument ran");
        } finally { Instruments.CANDIDATES = cand; Instruments.SIFT = sift; }
    }

    @Test
    void aPathFromThePersonsHomeIsThatPathInEveryCheckAndInTheQuestion() throws Exception {
        assertEquals("/srv/reader/Documents/notes.md", ChatTable.home("~/Documents/notes.md", "/srv/reader"));
        assertEquals("/srv/reader", ChatTable.home("~", "/srv/reader/"));
        assertEquals("/opt/notes.md", ChatTable.home("/opt/notes.md", "/srv/reader"));
        assertEquals("~other/notes.md", ChatTable.home("~other/notes.md", "/srv/reader"), "another user's home is not read as the person's");
        JsonNode out = ChatTable.homeIn(M.readTree("{\"path\":\"~/a.md\",\"images\":[\"~/p.jpg\",\"https://example.net/q.jpg\"],\"url\":\"~/not-a-path\"}"), "/srv/reader");
        assertEquals("{\"path\":\"/srv/reader/a.md\",\"images\":[\"/srv/reader/p.jpg\",\"https://example.net/q.jpg\"],\"url\":\"~/not-a-path\"}", out.toString());
        // through the chat, with the person's home a folder of this test's
        home = System.getProperty("user.home");
        Path me = tmp.resolve("home"); Files.createDirectories(me.resolve("papers"));
        Files.writeString(me.resolve("papers/one.txt"), "a paper about soil", StandardCharsets.UTF_8);
        System.setProperty("user.home", me.toString()); Config.invalidate();
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Librarian.Session s = Librarian.Session.open(store);
        var drive = LibrarianChatTest.scripted(List.of(LibrarianChatTest.tool("library_add", "{\"path\":\"~/papers\",\"mode\":\"survey\"}"),
                LibrarianChatTest.tool("library_add", "{\"path\":\"~/papers/one.txt\"}"), LibrarianChatTest.say("It waits for your answer.")), new ArrayList<>());
        String reply = new Librarian(store, drive, Librarian.person(), s).say("How many files are in ~/papers? Then add ~/papers/one.txt.");
        List<String> results = new ArrayList<>();
        for (JsonNode m : s.messages()) if ("tool".equals(m.path("role").asText())) results.add(m.path("content").asText());
        assertFalse(results.get(0).contains("Nothing is at"), "the folder in the person's home was counted: " + results.get(0));
        assertTrue(results.get(0).contains("\"files\":1") || results.get(0).contains("1 file"), results.get(0));
        assertTrue(reply.contains(me.resolve("papers/one.txt").toString()), "the question says the file it will read: " + reply);
    }

    @Test
    void aShortCodeOpensTheOneEntryItNamesAndElseSaysWhichExist() throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        for (String id : List.of("F-0001-gears-cut-by-hand", "F-0002-a-first", "F-0002-b-second"))
            store.write(new Finding(id, "A claim " + id, List.of(), Finding.State.draft, Finding.ClaimType.extraction, Finding.Confidence.medium, "person", "2026-11-21T00:00:00Z", "2026-11-21",
                    Finding.Volatility.slow, "", List.of(new Finding.Source("https://example.org/a", "n/a", "a")), List.of(), null, "The text of " + id + ".\n"));
        LibraryProtocol lp = new LibraryProtocol(store);
        ObjectNode a = M.createObjectNode(); a.putObject("patron").put("did", "person").put("name", "keeper").put("runtime", "cli");
        a.put("id", "F-0001");
        assertEquals("F-0001-gears-cut-by-hand", lp.get(a).path("entry").path("id").asText(), "the one claim the code names opens");
        a.put("id", "F-0002");
        String several = assertThrows(ProtocolError.class, () -> lp.get(a)).getMessage();
        assertTrue(several.contains("F-0002 is the code of 2 entries: F-0002-a-first, F-0002-b-second"), several);
        a.put("id", "F-0009");
        String none = assertThrows(ProtocolError.class, () -> lp.get(a)).getMessage();
        assertTrue(none.contains("No entry has the id F-0009. The claims on the shelves: F-0001-gears-cut-by-hand, F-0002-a-first, F-0002-b-second."), none);
        a.put("id", "I-0004");
        assertTrue(assertThrows(ProtocolError.class, () -> lp.get(a)).getMessage().contains("the shelves hold no reports"));
        a.put("id", "F-0001-gears-cut-by-hand");
        assertEquals("F-0001-gears-cut-by-hand", lp.get(a).path("entry").path("id").asText(), "a full id opens as before");
    }

    @Test
    void aCollectionFromTheChatsTableIsReadFromThePersonsWordsAndAnOlderCallKeepsItsForm() throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        String ask = "I need 20 landscape photos of lighthouses, at least 1920 wide, and no AI-generated ones.";
        // the chat's table: the request, and a number; the form is read from the person's words, a second call
        List<ArrayNode> seen = new ArrayList<>();
        var drive = LibrarianChatTest.scripted(List.of(
                LibrarianChatTest.tool("library_images", "{\"op\":\"collect\",\"words\":\"lighthouses\",\"count\":20,\"ask\":\"" + ask + "\"}"),
                LibrarianChatTest.tool("collect", "{\"words\":\"lighthouses\",\"kind\":\"other\",\"count\":20,\"min_width\":1920,\"orientation\":\"landscape\",\"ai\":\"no\"}"),
                LibrarianChatTest.say("It waits for your answer.")), seen);
        Librarian.Session s = Librarian.Session.open(store);
        new Librarian(store, drive, Librarian.person(), s).say(ask);
        JsonNode held = heldCall(s);
        assertNotNull(held, "the collection waits for a yes");
        assertEquals(1920, held.path("args").path("min_width").asInt(), held.toString());
        assertEquals("landscape", held.path("args").path("orientation").asText(), held.toString());
        assertEquals("no", held.path("args").path("ai").asText(), held.toString());
        assertEquals(3, seen.size(), "the turn, the form read from the request, the reply");
        // a call that carries the form (an older table's, a program's) keeps its own, and no second call is made
        List<ArrayNode> seen2 = new ArrayList<>();
        var older = LibrarianChatTest.scripted(List.of(
                LibrarianChatTest.tool("library_images", "{\"op\":\"collect\",\"words\":\"lighthouses\",\"kind\":\"other\",\"count\":20,\"min_width\":1920,\"orientation\":\"landscape\",\"ai\":\"no\"}"),
                LibrarianChatTest.say("It waits for your answer.")), seen2);
        Librarian.Session s2 = Librarian.Session.open(store);
        new Librarian(store, older, Librarian.person(), s2).say(ask);
        assertEquals(1920, heldCall(s2).path("args").path("min_width").asInt());
        assertEquals(2, seen2.size(), "no second call: " + seen2.size());
    }

    /** The call a conversation's question holds for a yes, decoded. */
    static JsonNode heldCall(Librarian.Session s) throws Exception {
        for (String[] o : s.pending()) if (o[0].startsWith(Librarian.ACT)) return M.readTree(Base64.getUrlDecoder().decode(o[0].substring(Librarian.ACT.length())));
        return null;
    }
}
