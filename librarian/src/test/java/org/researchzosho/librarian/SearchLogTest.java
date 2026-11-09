package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.researchzosho.Config;
import org.researchzosho.tools.ForumSearchTool;
import org.researchzosho.tools.Tool;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.StandardOpenOption;
/** The research log: every search a run made, written by the library from the tool calls, kept under the subject, shown to the next run. */
class SearchLogTest {

    @Test
    void aRunsSearchesAreLoggedUnderItsSubjectAndTheNextRunIsShownThem(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        ResearcherTest.ScriptedDrive drive = new ResearcherTest.ScriptedDrive();
        drive.criticWantsMore = false;
        Researcher researcher = new Researcher(drive, new ResearcherTest.FakeTools(), null, 2);
        String question = "Endo Genzaburo (also written 遠藤源三郎; born 1872): how were the Antikythera gears cut?";
        Researcher.Filed filed = Researcher.file(store, researcher, new Researcher.Ask(question, "depth", 60, List.of()), "patron:person");
        assertTrue(filed.admitted(), filed.reason());
        List<SearchLog.Entry> made = SearchLog.about(store, "遠藤源三郎: who were the parents?");
        assertTrue(made.isEmpty(), "another written form is another lead; the log is keyed by how the question began: " + made);
        made = SearchLog.about(store, "Endo Genzaburo (born 1872): who were the parents?");
        assertFalse(made.isEmpty(), "the run searched the web at least once");
        SearchLog.Entry e = made.get(0);
        assertEquals("web", e.where());
        assertEquals("antikythera gears", e.query());
        assertEquals(1, e.found(), "the fake engine returns one numbered result");
        assertEquals(filed.investigationId(), e.job());
        assertEquals(question, e.about());
        String shown = SearchLog.block(store, "Endo Genzaburo (born 1872): when did he die?", 40);
        assertTrue(shown.contains("SEARCHED BEFORE ON THIS SUBJECT") && shown.contains("web: antikythera gears  → 1 result  [" + filed.investigationId() + "]"), shown);
        assertEquals("", SearchLog.block(store, "Endo Haru (born 1880): when did she die?", 40));
    }

    @Test
    void aCollectionThatDidNotAnswerIsLoggedAsThatAndNeverAsASearchThatFoundNothing() {
        assertEquals(SearchLog.FAILED, SearchLog.outcome("ERROR: search backend unreachable at http://localhost:8888"));
        assertEquals(SearchLog.FAILED, SearchLog.outcome("ERROR: Chronicling America did not answer (timeout). This is not a search that found nothing"));
        assertEquals(SearchLog.FAILED, SearchLog.outcome(SearchLog.DEGRADED + ": no results came back because the upstream engines are blocked"));
        assertEquals(SearchLog.FAILED, SearchLog.outcome("ERROR: neither Crossref nor OpenAlex answered."));
        assertEquals(SearchLog.OK, SearchLog.outcome("no results for: \"Endo Genzaburo\""));
        assertEquals(SearchLog.OK, SearchLog.outcome("1. A page — https://example.org/a"));
        assertNull(SearchLog.outcome(SearchLog.ALREADY + ": you already ran this exact query"), "the same query again is no search");
        assertNull(SearchLog.outcome("ERROR: no source \"nowhere\". The sources are: loc-newspapers"), "a collection that does not exist was not searched");

        Researcher r = new Researcher(new ResearcherTest.ScriptedDrive(), new ResearcherTest.FakeTools(), null, 2);
        ObjectMapper m = new ObjectMapper();
        r.logSearch("record_search", m.createObjectNode().put("source", "loc-newspapers").put("query", "\"Ellis Hart\""), "ERROR: Chronicling America did not answer (HTTP 503). This is not a search that found nothing: try once more or use another source.");
        r.logSearch("web_search", m.createObjectNode().put("query", "Ellis Hart"), SearchLog.ALREADY + ": you already ran this exact query");
        r.logSearch("web_search", m.createObjectNode().put("query", "Ellis Hart 1880"), "no results for: Ellis Hart 1880");
        assertEquals(2, r.searches().size(), "the repeat is not written down: " + r.searches());
        assertTrue(r.searches().get(0).failed());
        assertFalse(r.searches().get(1).failed());
        assertTrue(SearchLog.line(r.searches().get(0)).endsWith("loc-newspapers: \"Ellis Hart\"  → did not answer"), SearchLog.line(r.searches().get(0)));
        assertTrue(SearchLog.line(r.searches().get(1)).endsWith("→ nothing"));
    }

    /**
     * A worker that searches through the table's search: the web, PubMed under another of its names, Exa with no key set, a place that does
     * not exist, the forums without a site, a collection of records with years, and the book catalogues.
     */
    static final class TableSearches extends ResearcherTest.ScriptedDrive {
        static final ObjectMapper J = new ObjectMapper();
        @Override public ObjectNode chat(ArrayNode history, ArrayNode tools, int maxTokens, String toolChoice) {
            List<String> n = names(tools);
            if (n.contains("write_section") || n.equals(List.of("done")) || !n.contains("search")) return super.chat(history, tools, maxTokens, toolChoice);
            return switch (assistantTurns(history) + 1) {
                case 1 -> call("search", J.createObjectNode().put("where", "web").put("query", "antikythera gears"));
                case 2 -> call("search", J.createObjectNode().put("where", "PubMed Central").put("query", "bronze gear wear"));
                case 3 -> call("search", J.createObjectNode().put("where", "exa").put("query", "gear trains by meaning"));
                case 4 -> call("search", J.createObjectNode().put("where", "the moon").put("query", "gears"));
                case 5 -> call("search", J.createObjectNode().put("where", "forums").put("query", "antikythera mechanism"));
                case 6 -> call("search", J.createObjectNode().put("where", "records").put("source", "loc-newspapers").put("query", "\"Antikythera\"").put("from_year", 1900).put("to_year", 1910));
                case 7 -> call("search", J.createObjectNode().put("where", "books").put("query", "ancient gearing"));
                case 8 -> call("web_fetch", J.createObjectNode().put("url", "https://example.org/gears"));
                case 9 -> call("note", J.createObjectNode().put("claim", "the gears were cut by hand with files").put("source", "https://example.org/gears").put("quote", "cut by hand with files"));
                default -> call("done", J.createObjectNode().put("summary", "hand-cut, one source"));
            };
        }
    }

    @Test
    void everySearchThroughTheTableIsLoggedUnderItsPlaceAndOnlyASearchThatWasMade(@TempDir Path tmp) throws Exception {
        String realHome = System.getProperty("user.home");
        System.setProperty("user.home", tmp.resolve("home").toString()); Config.invalidate();   // no key for Exa from anywhere
        try {
            LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
            new LibrarianIndex(store, Embeddings.none()).rebuild();
            Map<String, JsonNode> recordCalls = new LinkedHashMap<>();
            Researcher.Tools tools = new Researcher.Tools() {
                @Override public List<Tool> web(String focus) {
                    List<Tool> out = new ArrayList<>(new ResearcherTest.FakeTools().web(focus));
                    out.add(ResearcherTest.tool("pubmed_search", "pubmed", "query", a -> "results for \"" + a.path("query").asText() + "\" (PubMed):\n1. A trial of bronze wear\n   https://pubmed.ncbi.nlm.nih.gov/101/\n2. A second study\n   https://pubmed.ncbi.nlm.nih.gov/102/\n"));
                    out.add(ResearcherTest.tool("record_search", "records", "query", a -> { recordCalls.put(a.path("query").asText(), a.deepCopy()); return "1. Antikythera find reported — The Evening Star, 1902\n   https://chroniclingamerica.loc.gov/lccn/sn0000/1902-01-01/ed-1/seq-1/\n"; }));
                    out.add(ResearcherTest.tool("book_search", "books", "query", a -> "Books for \"" + a.path("query").asText() + "\":\n- Gears of the Greeks — Hale (1974)\n- Ancient Gearing — Ellis (2001)\n"));
                    out.add(new ForumSearchTool());   // the real one: with no site it answers before it asks anybody
                    return out;
                }
                @Override public BooleanSupplier exhausted() { return () -> false; }
            };
            TableSearches drive = new TableSearches();
            drive.criticWantsMore = false;
            Researcher researcher = new Researcher(drive, tools, null, 2);
            Researcher.Filed filed = Researcher.file(store, researcher, new Researcher.Ask("Hale Tom (born 1850): how were the Antikythera gears cut?", "depth", 60, List.of()), "patron:person");
            assertTrue(filed.admitted(), filed.reason());
            List<SearchLog.Entry> made = SearchLog.about(store, "Hale Tom (born 1850): who made them?");
            List<String> lines = made.stream().map(e -> e.where() + " | " + e.query() + " | " + e.fromYear() + "-" + e.toYear() + " | " + e.found() + " | " + e.status() + " | " + e.job()).distinct().sorted().toList();
            String job = filed.investigationId();
            assertEquals(List.of(
                    "books | ancient gearing | 0-0 | 2 | ok | " + job,
                    "loc-newspapers | \"Antikythera\" | 1900-1910 | 1 | ok | " + job,
                    "pubmed | bronze gear wear | 0-0 | 2 | ok | " + job,
                    "web | antikythera gears | 0-0 | 1 | ok | " + job), lines,
                    "each search a place took, under the place's own name and the collection it named; Exa with no key, a place that is none and a forum with no site searched nothing");
            JsonNode asked = recordCalls.get("\"Antikythera\"");
            assertNotNull(asked, "the records were searched through search");
            assertEquals("loc-newspapers", asked.path("source").asText(), "the collection named in the call reaches the records");
            assertEquals(1900, asked.path("from_year").asInt()); assertEquals(1910, asked.path("to_year").asInt());
            String shown = SearchLog.block(store, "Hale Tom (born 1850): when did he die?", 40);
            assertTrue(shown.contains("pubmed: bronze gear wear  → 2 results") && shown.contains("books: ancient gearing  → 2 results") && shown.contains("loc-newspapers: \"Antikythera\" (1900-1910)  → 1 result"), shown);
        } finally { System.setProperty("user.home", realHome); Config.invalidate(); }
    }

    @Test
    void aCallAPlaceCouldNotMakeIsNoSearch() {
        for (String said : List.of("ERROR: site is needed: reddit, or a Stack Exchange site's name.", "ERROR: query is empty", "ERROR: kind must be one of shows, episodes, transcript, people, similar, browse",
                "ERROR: kind is search, subject, author, ndc, work or shelf.", "ERROR: episodes needs feed, the show's feed address (podcast_search kind=shows gives it)",
                "ERROR: this run has made its 30 archive lookups; write with what was found.", "ERROR: forum_search has been called 12 times in this run, which is its limit. Work with what was read."))
            assertNull(SearchLog.outcome(said), said);
        for (String said : List.of("ERROR: Semantic Scholar is busy: the calls it shares among programs without a key are used up for the moment.", "ERROR: PubMed did not answer. This is not a search that found nothing: try once more, or search scholar instead.",
                "ERROR: search failed — connection refused", "ERROR: no video site answered (timeout)"))
            assertEquals(SearchLog.FAILED, SearchLog.outcome(said), said);
        assertEquals(2, SearchLog.found("books", "Books:\n- One — Hale (1974)\n- Two — Ellis (2001)\n"));
        assertEquals(0, SearchLog.found("web", "Notes:\n- not a result\n"), "a dash counts only for a place that lists with dashes");
        assertEquals(3, SearchLog.found("pubmed", "results:\n1. A\n   https://a\n2. B\n3. C\n"));
    }

    @Test
    void theNextRunIsToldToSearchAgainWhereTheCollectionDidNotAnswer(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        String q = "Hale Tom (born 1850): who were the parents?";
        SearchLog.add(store, List.of(
                new SearchLog.Entry("2026-09-20", q, "loc-newspapers", "\"Tom Hale\"", 0, 0, 0, "I-0001", SearchLog.FAILED),
                new SearchLog.Entry("2026-09-20", q, "internet-archive", "\"Tom Hale\"", 0, 0, 0, "I-0001", SearchLog.FAILED),
                new SearchLog.Entry("2026-09-21", q, "internet-archive", "\"Tom Hale\"", 0, 0, 2, "I-0002"),
                new SearchLog.Entry("2026-09-21", q, "web", "Tom Hale obituary", 0, 0, 0, "I-0002")));
        // a line written before the status existed reads as answered
        Files.writeString(SearchLog.file(store), "{\"date\":\"2026-09-01\",\"about\":\"" + q + "\",\"where\":\"web\",\"query\":\"Tom Hale 1850\",\"from\":0,\"to\":0,\"found\":0,\"job\":\"I-0000\"}\n",
                StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        List<SearchLog.Entry> all = SearchLog.all(store);
        assertEquals(5, all.size());
        assertEquals(List.of(true, true, false, false, false), all.stream().map(SearchLog.Entry::failed).toList(), "the status is kept, and an old line is an answered one");
        String shown = SearchLog.block(store, "Hale Tom (born 1850): when did he die?", 40);
        int tried = shown.indexOf("TRIED BEFORE, AND THE COLLECTION DID NOT ANSWER");
        assertTrue(tried > 0, shown);
        String before = shown.substring(0, tried), after = shown.substring(tried);
        assertFalse(before.contains("did not answer"), "a search that did not answer is not among the searches made: " + before);
        assertTrue(after.contains("loc-newspapers: \"Tom Hale\"  → did not answer"), after);
        assertFalse(after.contains("internet-archive"), "it answered on the next try, so it is not asked for again: " + after);
        assertTrue(before.contains("web: Tom Hale 1850  → nothing"), before);
    }

    @Test
    void aSearchThatThrewIsLoggedAsNotAnswered(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        ResearcherTest.ScriptedDrive drive = new ResearcherTest.ScriptedDrive();
        drive.criticWantsMore = false;
        Researcher.Tools down = new Researcher.Tools() {
            @Override public List<Tool> web(String focus) {
                return List.of(ResearcherTest.tool("web_search", "search", "query", a -> { throw new IOException("connection refused"); }),
                        ResearcherTest.tool("web_fetch", "fetch", "url", a -> "TEXT of " + a.path("url").asText() + ": the Antikythera gears were cut by hand with files."));
            }
            @Override public BooleanSupplier exhausted() { return () -> false; }
        };
        Researcher researcher = new Researcher(drive, down, null, 2);
        Researcher.file(store, researcher, new Researcher.Ask("Hart Ann (born 1901): how were the Antikythera gears cut?", "depth", 60, List.of()), "patron:person");
        List<SearchLog.Entry> made = SearchLog.all(store);
        assertFalse(made.isEmpty(), "the run tried to search");
        assertTrue(made.stream().allMatch(SearchLog.Entry::failed), "every search failed, and each is written as not answered: " + made);
    }

    @Test
    void theRunIsToldWhichWrittenFormsYearsAndCollectionsNoSearchHasUsed(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        FamilyAccount.file(store, new FamilyAccount.Read(List.of(new FamilyAccount.Person("遠藤源三郎", "", List.of("Endo Genzaburo", "遠藤源三朗"))),
                List.of(new FamilyAccount.Fact("遠藤源三郎", "born-on", "1872", "", "q")), List.of()), "file:///notes.txt", "an aunt");
        String q = "遠藤源三郎 (also written Endo Genzaburo, 遠藤源三朗; born 1872): who were the parents?";
        SearchLog.add(store, List.of(
                new SearchLog.Entry("2026-09-20", q, "web", "\"遠藤 源三郎\" 戸籍", 0, 0, 3, "I-0001"),
                new SearchLog.Entry("2026-09-20", q, "loc-newspapers", "\"Genzaburo Endo\"", 0, 0, 0, "I-0001"),
                new SearchLog.Entry("2026-09-20", q, "internet-archive", "\"遠藤源三朗\"", 0, 0, 0, "I-0001", SearchLog.FAILED)));
        assertFalse(SearchLog.block(store, q, 40).contains("NOT YET SEARCHED"), "an ordinary run on the name is told the log and nothing a family-history run works out");
        String shown = SearchLog.block(store, q, 40, List.of("genealogy"));
        int at = shown.indexOf("NOT YET SEARCHED FOR THIS PERSON");
        assertTrue(at > 0, "a run in genealogy mode is: " + shown);
        String never = shown.substring(at);
        assertTrue(never.contains("written forms of the name never searched: 遠藤源三朗"), "a search that did not answer did not search that form: " + never);
        assertFalse(never.contains("Endo Genzaburo"), "the same words in another order were searched: " + never);
        assertTrue(never.contains("held to the years of this life (born 1872)"), never);
        assertTrue(never.contains("internet-archive (") && !never.contains("loc-newspapers ("), never);
        assertFalse(never.contains("wikitree"), "an index is not a collection of records: " + never);
        assertEquals("", FamilyQuestions.neverSearched(store, "Hart Ann (born 1901): who were the parents?", SearchLog.all(store)), "somebody not in the tree is told nothing computed");

        // the searches of a run somebody asked genealogy for are that field's: an ordinary run on the same name is not told of them
        Fields.record(store, "I-0002", "J-0002", "genealogy", "genealogy-command");
        SearchLog.add(store, List.of(new SearchLog.Entry("2026-09-21", q, "ndl-fulltext", "遠藤源三郎", 0, 0, 2, "I-0002")));
        assertFalse(SearchLog.block(store, q, 40).contains("ndl-fulltext"), SearchLog.block(store, q, 40));
        assertTrue(SearchLog.block(store, q, 40, List.of("genealogy")).contains("ndl-fulltext: 遠藤源三郎"));
    }
}
