package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** A person's own documents as a corpus the runner searches first or only; a wall becomes a request the person can answer. */
class CorpusAndRequestsTest {

    private static final ObjectMapper M = new ObjectMapper();

    @Test
    void aFolderBecomesACollectionTheShelvesCanBeScopedTo(@TempDir Path home) throws Exception {
        LibraryStore store = new LibraryStore(home.resolve("lib")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        Path docs = home.resolve("thesis"); Files.createDirectories(docs.resolve("sub"));
        Files.writeString(docs.resolve("guardrails.md"), "# DNA interpretation guardrails\n\nContinental-level composition is reliable; specific percentages are estimates. Haplogroups confirm deep ancestry lines.\n");
        Files.writeString(docs.resolve("sub").resolve("pitfalls.txt"), "The Ancestry hint trap: hints are algorithmic matches, not verified connections. Two trees that copied one index are one source.\n");
        Files.writeString(docs.resolve("notes.bin"), "not a document");
        Corpus.Outcome o = Corpus.addFolder(store, docs, "thesis", true);
        assertEquals(2, o.seen()); assertEquals(2, o.added()); assertEquals(0, o.skipped());
        assertEquals(0, Corpus.addFolder(store, docs, "thesis", true).added(), "unchanged files are not re-captured");
        assertEquals(2, Corpus.addFolder(store, docs, "thesis", true).unchanged());
        Path raw = RawCapture.find(store, "file://" + docs.resolve("guardrails.md").toAbsolutePath().normalize());
        assertNotNull(raw);
        assertEquals("thesis", RawCapture.collectionOf(raw));
        LibrarianIndex idx = new LibrarianIndex(store, Embeddings.none());
        assertFalse(idx.searchIn("haplogroups deep ancestry", 5, null, "thesis").isEmpty(), "found inside the collection");
        assertTrue(idx.searchIn("haplogroups deep ancestry", 5, null, "elsewhere").isEmpty(), "and not in another");
        // the worker's tool renders the shelves with locators, fenced
        String out = new Researcher.ShelfSearchTool(store, List.of("thesis")).execute(M.readTree("{\"query\":\"hint trap verified connections\"}"));
        assertTrue(out.contains(Fence.open("SHELF RESULTS")) && out.contains("file://") && out.contains("pitfalls"), out);
        assertTrue(new Researcher.ShelfSearchTool(store, List.of("nothing")).execute(M.readTree("{\"query\":\"hint trap\"}")).startsWith("the shelves hold nothing"));
        // registered folders are rescanned by the crews
        Corpus.register(store, "thesis", docs);
        Files.writeString(docs.resolve("new.md"), "# A new note\n\nA third document arrived later, about census age rounding.\n");
        assertTrue(Corpus.rescan(store).contains("thesis: 1 added, 2 unchanged"), Corpus.rescan(store));
        assertEquals(List.of("thesis"), List.copyOf(Corpus.registered(store).keySet()));
    }

    @Test
    void aShelvesOnlyAskSearchesTheCorpusAndNeverTheWeb(@TempDir Path home) throws Exception {
        String real = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        try {
            LibraryStore store = new LibraryStore(home.resolve("researchzosho-library")); store.init();
            new LibrarianIndex(store, Embeddings.none()).rebuild();
            Path docs = home.resolve("corpus"); Files.createDirectories(docs);
            Files.writeString(docs.resolve("gears.md"), "# On gears\n\nThe Antikythera gears were cut by hand with files; the teeth are triangular.\n");
            Corpus.addFolder(store, docs, "corpus", false);
            java.util.concurrent.atomic.AtomicInteger webCalls = new java.util.concurrent.atomic.AtomicInteger();
            Researcher.Tools tools = new Researcher.Tools() {
                @Override public List<org.researchzosho.tools.Tool> web(String focus) { webCalls.incrementAndGet(); return new ResearcherTest.FakeTools().web(focus); }
                @Override public java.util.function.BooleanSupplier exhausted() { return () -> false; }
            };
            ResearcherTest.ScriptedDrive drive = new ResearcherTest.ScriptedDrive() {
                @Override public ObjectNode chat(ArrayNode history, ArrayNode tools, int maxTokens, String toolChoice) {
                    List<String> n = names(tools);
                    offered.add(String.join(",", n));
                    if (n.equals(List.of("done"))) return call("done", M.createObjectNode().put("summary", "closed"));
                    if (n.contains("write_section")) return super.chat(history, tools, maxTokens, toolChoice);
                    int turn = assistantTurns(history) + 1;
                    if (turn == 1) return call("shelf_search", M.createObjectNode().put("query", "antikythera gears cut"));
                    if (turn == 2) return call("note", M.createObjectNode().put("claim", "the teeth are triangular").put("source", "file://" + docs.resolve("gears.md").toAbsolutePath().normalize()).put("quote", "the teeth are triangular"));
                    return call("done", M.createObjectNode().put("summary", "from the shelves"));
                }
            };
            drive.criticWantsMore = false;
            var r = new Researcher(drive, drive, tools, null, 1, store).run(
                    new Researcher.Ask("How were the Antikythera gears cut?", "depth", 30, List.of("how?"), "shelves", List.of("corpus")), "");
            assertTrue(drive.offered.stream().noneMatch(o -> o.contains("web_search")), "no web tool offered on a shelves-only ask: " + drive.offered);
            assertTrue(drive.offered.stream().anyMatch(o -> o.contains("shelf_search")), drive.offered.toString());
            assertTrue(r.evidence().contains("the teeth are triangular — source: file://"), r.evidence());
            assertTrue(r.answer().contains("## References") && r.answer().contains("gears.md"), r.answer());
            // and the shelf is admitted: a file:// source read from the shelves is a source (the first live shelves-only run was refused as "answered from memory")
            var filed = Researcher.file(store, new Researcher(drive, drive, tools, null, 1, store),
                    new Researcher.Ask("How were the Antikythera gears cut?", "depth", 30, List.of("how?"), "shelves", List.of("corpus")), "patron:test");
            assertTrue(filed.admitted(), filed.reason());
            assertTrue(Acquisitions.urls("see file:///home/x/paper.pdf and raw/2026-09-07-0123456789ab.md").size() == 2);
        } finally { System.setProperty("user.home", real); }
    }

    @Test
    void aWallBecomesARequestThePersonAnswersWithTheDocument(@TempDir Path home) throws Exception {
        LibraryStore store = new LibraryStore(home.resolve("lib")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        Requests.note(store, "https://journal.example/paper/123", "a cookie wall", "What did the paper measure?");
        Requests.note(store, "https://journal.example/paper/123", "a cookie wall", "again");   // idempotent while open
        assertEquals(1, Requests.open(store).size());
        assertEquals("a cookie wall", Requests.open(store).get(0).reason());
        assertNull(RawCapture.find(store, "https://journal.example/paper/123"));
        Path p = Requests.supply(store, "https://journal.example/paper/123", "The paper measured a standard deviation of one degree. " + "x".repeat(300), "The paper");
        assertNotNull(p);
        assertEquals(p, RawCapture.find(store, "https://journal.example/paper/123"), "the supplied document IS the capture behind the url");
        assertEquals("person-supplied", RawCapture.read(p).length == 3 ? "person-supplied" : "?");
        assertTrue(Files.readString(p).contains("fetched_by: person-supplied"));
        assertEquals(0, Requests.open(store).size(), "the request is marked supplied");
        assertTrue(Files.readString(Requests.file(store), StandardCharsets.UTF_8).contains("[supplied "));
        assertTrue(Changes.since(store, 0, 10).stream().anyMatch(c -> c.event().equals("supplied") && c.kind().equals("source")));
    }
}
