package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.tools.Tool;
import org.researchzosho.tools.WebFetchTool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A run and the chat read the person's own shelved file by the file:// locator the shelf search listed (round G, the final live check on
 * 0fbf7188: shelf_search listed file:///…/kestrel-private-notes.md, and read_pages and open on exactly that string came back NOT OPENED;
 * in c8e057ed the same read worked). Only that: a file:// written in a page or in a note's text, a file outside the library, a locator
 * the model rewrote, and a file of this machine added by someone who is not its owner stay as they were.
 */
class ShelvedFileReadBackTest {
    static final ObjectMapper M = new ObjectMapper();

    /** The person's notes on the shelves (collection "notes"), a diary on the shelves in another collection, and a file outside the library. */
    record Shelves(LibraryStore store, String notes, String diary, String outside) { }

    static Shelves shelves(Path home) throws Exception {
        LibraryStore store = new LibraryStore(home.resolve("researchzosho-library")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        Path outsideFile = home.resolve("elsewhere").resolve("door-codes.txt");
        Files.createDirectories(outsideFile.getParent());
        Files.writeString(outsideFile, "The cellar door code is CELLAR4417.\n");
        Path diaryDir = home.resolve("diary"); Files.createDirectories(diaryDir);
        Files.writeString(diaryDir.resolve("diary.md"), "# Diary\n\nThe garden shed key hangs behind the clock, ZEBRA9921.\n");
        Corpus.addFolder(store, diaryDir, "diary", false);
        String diary = "file://" + diaryDir.resolve("diary.md").toAbsolutePath().normalize();
        String outside = "file://" + outsideFile.toAbsolutePath().normalize();
        Path notesDir = home.resolve("notes"); Files.createDirectories(notesDir);
        Files.writeString(notesDir.resolve("kestrel-private-notes.md"), "# My notes on Kestrel Sound (private)\n\nMy grandmother crossed on the Kestrel Sound ferry in 1952; "
                + "her ticket stub is in the family archive box. See also " + diary + " and " + outside + ".\n");
        Corpus.addFolder(store, notesDir, "notes", false);
        String notes = "file://" + notesDir.resolve("kestrel-private-notes.md").toAbsolutePath().normalize();
        assertNotNull(RawCapture.find(store, notes));
        assertNotNull(RawCapture.find(store, diary));
        assertNull(RawCapture.find(store, outside));
        return new Shelves(store, notes, diary, outside);
    }

    @Test
    void aRunReadsTheShelvedFileItsShelfSearchListedAndNothingElseByFile(@TempDir Path home) throws Exception {
        String real = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        try {
            Shelves s = shelves(home);
            List<String> fetched = new CopyOnWriteArrayList<>();
            List<String[]> answers = new CopyOnWriteArrayList<>();
            Researcher.Tools tools = new Researcher.Tools() {
                @Override public List<Tool> web(String focus) {
                    return List.of(ResearcherTest.tool("web_search", "search", "query", a -> "1. A page — https://example.org/kestrel — the person's files: " + s.diary() + " " + s.outside()),
                            ResearcherTest.tool("web_fetch", "fetch", "url", a -> { fetched.add(a.path("url").asText()); return "source: " + a.path("url").asText() + " — The ferry ran until 1968. My files: " + s.diary(); }));
                }
                @Override public BooleanSupplier exhausted() { return () -> false; }
            };
            ResearcherTest.ScriptedDrive drive = new ResearcherTest.ScriptedDrive() {
                @Override public ObjectNode chat(ArrayNode history, ArrayNode tools, int maxTokens, String toolChoice) {
                    List<String> n = names(tools);
                    if (n.equals(List.of("done"))) return call("done", M.createObjectNode().put("summary", "closed"));
                    if (n.contains("write_section")) return super.chat(history, tools, maxTokens, toolChoice);
                    List<String[]> now = new ArrayList<>();   // the worker's calls, each with what it was told
                    String called = null;
                    for (JsonNode m : history) {
                        for (JsonNode c : m.path("tool_calls")) called = c.path("function").path("name").asText() + " " + c.path("function").path("arguments").asText();
                        if ("tool".equals(m.path("role").asText())) now.add(new String[]{called, m.path("content").asText()});
                    }
                    if (n.contains("shelf_search") && now.size() >= answers.size()) { answers.clear(); answers.addAll(now); }   // the worker's own table; the other asks of a run have no history of calls
                    String listed = null;   // the locator as the shelf search listed it, copied from its result
                    for (JsonNode m : history) if ("tool".equals(m.path("role").asText())) for (String line : m.path("content").asText().split("\n")) if (line.strip().startsWith("file://") && line.contains("kestrel")) listed = line.strip();
                    return switch (assistantTurns(history) + 1) {
                        case 1 -> call("shelf_search", M.createObjectNode().put("query", "Kestrel Sound ferry grandmother"));
                        case 2 -> call("read_pages", M.createObjectNode().put("url", listed).put("page", 1));
                        case 3 -> call("open", M.createObjectNode().put("address", listed));
                        case 4 -> call("read_pages", M.createObjectNode().put("url", s.diary()));     // a shelved file the notes' text names, never listed
                        case 5 -> call("read_pages", M.createObjectNode().put("url", s.outside()));   // a file outside the library the notes' text names
                        case 6 -> call("web_search", M.createObjectNode().put("query", "Kestrel Sound ferry"));
                        case 7 -> call("read_pages", M.createObjectNode().put("url", s.diary()).put("page", 2));   // the same, now written in a search's result too
                        case 8 -> call("read_pages", M.createObjectNode().put("url", listed.replace("kestrel-private", "Kestrel-Private")));   // the listed locator, rewritten
                        case 9 -> call("note", M.createObjectNode().put("claim", "the grandmother crossed in 1952").put("source", listed).put("quote", "crossed on the Kestrel Sound ferry in 1952"));
                        default -> call("done", M.createObjectNode().put("summary", "from the shelves"));
                    };
                }
            };
            drive.criticWantsMore = false;
            var r = new Researcher(drive, drive, tools, null, 1, s.store()).run(
                    new Researcher.Ask("When did my grandmother cross on the Kestrel Sound ferry?", "depth", 30, List.of("when?"), "both", List.of("notes")), "");
            assertTrue(answers.size() >= 8, "every step answered: " + answers.size());
            String read = answers.get(1)[1], opened = answers.get(2)[1];
            assertTrue(answers.get(0)[1].contains(s.notes()), "the shelf search lists the notes by their file:// locator: " + answers.get(0)[1]);
            assertTrue(read.contains("ticket stub"), "read_pages on the listed locator reads the notes: " + read);
            assertTrue(opened.contains("ticket stub"), "and open on it reads them from the library: " + opened);
            for (int i : new int[]{3, 4, 6, 7}) assertTrue(answers.get(i)[1].startsWith("NOT OPENED: "), "step " + (i + 1) + " is refused: " + answers.get(i)[0] + " → " + answers.get(i)[1]);
            assertTrue(answers.get(5)[1].contains(s.diary()), "the search's result wrote the diary's locator: " + answers.get(5)[1]);
            assertEquals(List.of(), fetched, "the web was never asked for a file: " + fetched);
            for (String[] a : answers) assertFalse(a[1].contains("ZEBRA9921") || a[1].contains("CELLAR4417"), "no file it was not listed is read: " + a[0] + " → " + a[1]);
            assertTrue(r.evidence().contains("source: " + s.notes()), r.evidence());
            // the web fetch itself, handed the listed locator, sends nothing to the web and says where it is read
            String told = new WebFetchTool().execute(M.readTree("{\"url\":\"" + s.notes() + "\"}"));
            assertTrue(told.startsWith("ERROR: ") && told.contains("read_pages"), told);
        } finally { System.setProperty("user.home", real); }
    }

    @Test
    void theChatOpensTheShelvedFileItsSearchListedAndAReaderAddsNoFileOfThisMachine(@TempDir Path home) throws Exception {
        String real = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        try {
            Shelves s = shelves(home);
            List<ArrayNode> seen = new ArrayList<>();
            var drive = LibrarianChatTest.scripted(List.of(
                    LibrarianChatTest.tool("search", "{\"where\":\"shelves\",\"query\":\"Kestrel Sound grandmother\"}"),
                    LibrarianChatTest.tool("open", "{\"address\":\"" + s.notes() + "\"}"),
                    LibrarianChatTest.tool("open", "{\"address\":\"" + s.outside() + "\"}"),
                    LibrarianChatTest.say("Your grandmother crossed in 1952.")), seen);
            Librarian.Session mine = Librarian.Session.open(s.store());
            new Librarian(s.store(), drive, Librarian.person(), mine).say("When did my grandmother cross? Look in my notes.");
            List<String> told = new ArrayList<>();
            for (ObjectNode m : mine.messages()) if ("tool".equals(m.path("role").asText())) told.add(m.path("content").asText());
            assertEquals(3, told.size(), told.toString());
            assertTrue(told.get(0).contains(s.notes()), "the search lists the notes: " + told.get(0));
            assertTrue(told.get(1).contains("ticket stub"), "open on the listed locator reads them: " + told.get(1));
            assertTrue(told.get(2).startsWith("NOT OPENED: ") && !told.get(2).contains("CELLAR4417"), "a file outside the library the notes name stays closed: " + told.get(2));

            // a reader of the library: adding a file of this machine by its file:// locator is not theirs; nothing runs and they are asked nothing
            Patrons.set(s.store(), "did:key:reader", "a cousin", Patrons.Level.read);
            Patrons.Patron cousin = new Patrons.Patron("did:key:reader", "a cousin", "web");
            long before; try (var f = Files.list(s.store().rawDir())) { before = f.count(); }
            var add = LibrarianChatTest.scripted(List.of(
                    LibrarianChatTest.tool("search", "{\"where\":\"shelves\",\"query\":\"Kestrel Sound grandmother\"}"),
                    LibrarianChatTest.tool("library_add", "{\"url\":\"" + s.notes() + "\"}"),
                    LibrarianChatTest.say("Done.")), new ArrayList<>());
            Librarian.Session theirs = Librarian.Session.latest(s.store(), cousin);
            new Librarian(s.store(), add, cousin, theirs).say("Add my notes again.");
            String last = "";
            for (ObjectNode m : theirs.messages()) if ("tool".equals(m.path("role").asText())) last = m.path("content").asText();
            assertTrue(last.startsWith("ERROR: the person in this conversation reads this library"), last);
            try (var f = Files.list(s.store().rawDir())) { assertEquals(before, f.count(), "nothing was added"); }
            // and a writer who is not the machine's owner is refused a file:// locator as a file of this machine
            assertTrue(Librarian.machinePath(M.readTree("{\"url\":\"" + s.notes() + "\"}")), "a file:// locator is a file of this machine");
            assertFalse(Librarian.machinePath(M.readTree("{\"url\":\"https://example.org/kestrel\"}")), "an address on the web is not");
        } finally { System.setProperty("user.home", real); }
    }

    @Test
    void aResumedRunReadsWhatItsKeptShelfSearchListedAndNotWhatItsSnippetsSay() throws Exception {
        String listed = "file:///shelf-" + System.nanoTime() + "/kestrel-private-notes.md", inSnippet = "file:///shelf-" + System.nanoTime() + "/door-codes.txt";
        String kept = "shelf results for \"Kestrel\":\n" + Fence.open("SHELF RESULTS") + "\n1. " + listed + " — " + listed + "  [raw, captured]\n   " + listed + "\n   See also " + inSnippet + "\n"
                + Fence.close("SHELF RESULTS") + "\n";
        assertTrue(GivenAddresses.unopened("read_pages", M.readTree("{\"url\":\"" + listed + "\"}")).startsWith("NOT OPENED: "), "not given before");
        Researcher.ShelfSearchTool.listedAgain(kept);
        assertNull(GivenAddresses.unopened("read_pages", M.readTree("{\"url\":\"" + listed + "\"}")), "the locator under the hit is given again");
        assertTrue(GivenAddresses.unopened("read_pages", M.readTree("{\"url\":\"" + inSnippet + "\"}")).startsWith("NOT OPENED: "), "a snippet's words give nothing");
    }
}
