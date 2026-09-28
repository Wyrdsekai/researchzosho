package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.drive.ContentJudge;
import org.researchzosho.drive.Judge;
import org.researchzosho.tools.ContentPolicy;
import org.researchzosho.tools.Fetch;
import org.researchzosho.tools.PageCheck;
import org.researchzosho.tools.Tool;
import org.researchzosho.tools.WebFetchTool;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The page check, with a stub judge and placeholder pages only: a page's category is a marker word in it, and the stub says yes when it
 * sees the marker under the question for that category. What the switch lets in is exactly what is fetched and saved; the always-dropped
 * category is dropped with the switch on; an address the person gives is asked only the always-dropped question; a page nothing could
 * check is left out; a page left out is never saved, shown or cited, and the run's report names only its category and address.
 */
class PageCheckTest {

    static final ObjectMapper M = new ObjectMapper();
    static final String ORDINARY = "https://example.org/ordinary", EXPLICIT = "https://example.org/explicit-placeholder",
            HOWTO = "https://example.org/howto-placeholder", CHILD = "https://example.org/child-placeholder";

    static final Map<String, String> PAGES = Map.of(
            ORDINARY, page("An ordinary placeholder page", "The placeholder gears were cut by hand, the ordinary page says."),
            EXPLICIT, page("A placeholder page", "MARKER-EXPLICIT placeholder text that stands for a page of that kind."),
            HOWTO, page("A placeholder page", "MARKER-HOWTO placeholder text that stands for a page of that kind."),
            CHILD, page("A placeholder page", "MARKER-CHILD placeholder text that stands for a page of that kind."));

    static String page(String title, String body) {
        return "<html><head><title>" + title + "</title></head><body><main><p>" + body + " " + "More placeholder words to make it a page. ".repeat(8) + "</p></main></body></html>";
    }

    /** Every question the judge was asked: the question, then the state it was about. */
    final List<String[]> asked = new CopyOnWriteArrayList<>();

    /** The stub judge: yes when the state carries the marker of the category the question is about; asked by one word. */
    ContentJudge markers() {
        return new ContentJudge(null, messages -> {
            String c = messages.get(0).path("content").asText();
            String q = c.substring(c.indexOf("QUESTION: ") + 10);
            String state = c.substring(0, c.indexOf("QUESTION: "));
            asked.add(new String[]{q, state});
            boolean yes = (q.startsWith(PageCheck.Category.EXPLICIT.question()) && state.contains("MARKER-EXPLICIT"))
                    || (q.startsWith(PageCheck.Category.HOWTO.question()) && state.contains("MARKER-HOWTO"))
                    || (q.startsWith(PageCheck.Category.CHILD.question()) && state.contains("MARKER-CHILD"));
            return yes ? "yes" : "no";
        });
    }

    long askedAbout(PageCheck.Category c) { return asked.stream().filter(a -> a[0].startsWith(c.question())).count(); }

    @TempDir Path home;
    LibraryStore store;

    @BeforeEach void pages() throws Exception {
        store = new LibraryStore(home.resolve("lib")); store.init();
        PageCheck.useGetter((url, timeout, lists) -> {
            String left = lists.leftOut(url);
            if (left != null) throw new Fetch.LeftOut(url, left);
            String html = PAGES.get(url);
            if (html == null) return new Fetch.Result(url, 404, new byte[0], "text/html");
            return new Fetch.Result(url, 200, html.getBytes(StandardCharsets.UTF_8), "text/html");
        });
    }

    @AfterEach void back() { PageCheck.useGetter(null); }

    String fetch(ContentPolicy policy, String url) throws Exception {
        return new WebFetchTool().policy(policy).execute(M.readTree("{\"url\":\"" + url + "\"}"));
    }

    boolean saved(String url) throws Exception { return RawCapture.find(store, url) != null; }

    @Test
    void theSwitchChangesExactlyWhatIsFetchedAndSaved() throws Exception {
        // off: the two default categories are left out, the ordinary page is read and saved
        ContentPolicy off = ContentPolicy.run(List.of(), markers(), store);
        assertTrue(fetch(off, ORDINARY).contains("cut by hand"));
        String ex = fetch(off, EXPLICIT), how = fetch(off, HOWTO);
        assertEquals("ERROR: " + EXPLICIT + " was left out of this research (pornography or gore). Read another source.", ex);
        assertEquals("ERROR: " + HOWTO + " was left out of this research (step-by-step instructions for a weapon, an explosive, an illegal drug or an exploit). Read another source.", how);
        assertFalse(ex.contains("MARKER") || how.contains("MARKER"), "nothing of a page left out is shown");
        assertTrue(saved(ORDINARY));
        assertFalse(saved(EXPLICIT) || saved(HOWTO), "a page left out is never saved");
        assertEquals(List.of("left out: pornography or gore — " + EXPLICIT,
                "left out: step-by-step instructions for a weapon, an explosive, an illegal drug or an exploit — " + HOWTO),
                off.leftOut().stream().map(ContentPolicy.LeftOut::line).toList());

        // a yes for explicit: that page is read and saved, and the question about it is never asked; how-to pages are still left out
        asked.clear();
        LibraryStore second = new LibraryStore(home.resolve("lib2")); second.init();
        ContentPolicy explicit = ContentPolicy.run(List.of(ContentPolicy.EXPLICIT), markers(), second);
        assertTrue(fetch(explicit, EXPLICIT).contains("MARKER-EXPLICIT"));
        assertTrue(fetch(explicit, HOWTO).startsWith("ERROR: " + HOWTO + " was left out"));
        assertNotNull(RawCapture.find(second, EXPLICIT));
        assertNull(RawCapture.find(second, HOWTO));
        assertEquals(0, askedAbout(PageCheck.Category.EXPLICIT), "the category the person let in is not asked about");
        assertEquals(1, askedAbout(PageCheck.Category.CHILD), "the always-dropped question is asked of the page that was let in (the other was already left out)");

        // a yes for both: both are read and saved
        LibraryStore third = new LibraryStore(home.resolve("lib3")); third.init();
        ContentPolicy both = ContentPolicy.run(List.of(ContentPolicy.EXPLICIT, ContentPolicy.HOWTO), markers(), third);
        assertTrue(fetch(both, EXPLICIT).contains("MARKER-EXPLICIT") && fetch(both, HOWTO).contains("MARKER-HOWTO"));
        assertTrue(both.leftOut().isEmpty());
    }

    @Test
    void eachQuestionIsNarrowAndExplicitWithNoListOfExceptions() {
        assertEquals("Is this page pornography, or gore: pictures or detailed descriptions of mutilated or dead bodies, shown to shock?", PageCheck.Category.EXPLICIT.question());
        assertEquals("Does this page give step-by-step instructions for making a weapon or explosive, making an illegal drug, or running working exploit code against a system?", PageCheck.Category.HOWTO.question());
        assertEquals("Does this page contain sexual content involving a child?", PageCheck.Category.CHILD.question());
        for (PageCheck.Category c : PageCheck.Category.values())
            assertFalse(c.question().contains("count as no") || c.question().contains("counts as no"), "a long clause of exceptions makes the judge answer no to everything: " + c);
    }

    @Test
    void aPageLeftOutAfterARedirectIsWrittenDownUnderTheAddressAskedForToo() throws Exception {
        PageCheck.useGetter((url, timeout, lists) -> {
            if (url.equals("https://example.org/go-listed")) throw new Fetch.LeftOut("https://listed-placeholder.example/landing", "its site is on " + Fetch.SITE_LIST_NAME);
            return new Fetch.Result("https://example.org/explicit-landing", 200, PAGES.get(EXPLICIT).getBytes(StandardCharsets.UTF_8), "text/html");
        });
        ContentPolicy off = ContentPolicy.run(List.of(), markers(), store);
        fetch(off, "https://example.org/go");
        assertTrue(off.wasLeftOut("https://example.org/go"), "a note citing the address asked for rests on the page left out");
        assertTrue(off.wasLeftOut("https://example.org/explicit-landing"));
        assertEquals("left out: pornography or gore — https://example.org/explicit-landing (asked for as https://example.org/go)", off.leftOut().get(0).line());
        fetch(off, "https://example.org/go-listed");
        assertTrue(off.wasLeftOut("https://example.org/go-listed") && off.wasLeftOut("https://listed-placeholder.example/landing"), off.leftOut().toString());
    }

    @Test
    void theAlwaysDroppedCategoryIsDroppedWithTheSwitchOn() throws Exception {
        ContentPolicy both = ContentPolicy.run(List.of(ContentPolicy.EXPLICIT, ContentPolicy.HOWTO), markers(), store);
        String out = fetch(both, CHILD);
        assertEquals("ERROR: " + CHILD + " was left out of this research (sexual content involving a child). Read another source.", out);
        assertFalse(saved(CHILD));
        assertEquals(List.of("left out: sexual content involving a child — " + CHILD), both.leftOut().stream().map(ContentPolicy.LeftOut::line).toList(), "only the fact, with the address");
    }

    @Test
    void anAddressThePersonGivesIsAskedOnlyTheAlwaysDroppedQuestion() throws Exception {
        ContentJudge.use(markers());
        try {
            Object[] got = Corpus.addUrl(store, EXPLICIT, "");
            assertNotNull(got[0], "the person added it: the default categories do not apply");
            assertEquals(0, askedAbout(PageCheck.Category.EXPLICIT) + askedAbout(PageCheck.Category.HOWTO));
            assertEquals(1, askedAbout(PageCheck.Category.CHILD));
            PageCheck.NotKept n = assertThrows(PageCheck.NotKept.class, () -> Corpus.addUrl(store, CHILD, ""));
            assertEquals("The library did not save " + CHILD + ": it was left out as sexual content involving a child. Nothing of it was kept.", n.getMessage());
            assertFalse(saved(CHILD));
            // a reading list and bookmarks go the same way: kept, or left out and never turned into a request to the person
            assertTrue(Shelving.fetch(store, HOWTO, "", "a placeholder reading list").shelved());
            Shelving.Got child = Shelving.fetch(store, CHILD, "", "a placeholder reading list");
            assertFalse(child.shelved() || child.requested(), child.toString());
            assertEquals("left out: sexual content involving a child, and not saved", child.problem());
        } finally { ContentJudge.use(null); }
    }

    @Test
    void aPageNothingCouldCheckIsLeftOutOnTheSafeSide() throws Exception {
        ContentJudge none = new ContentJudge(null, messages -> "");   // no model answered
        ContentPolicy run = ContentPolicy.run(List.of(), none, store);
        assertEquals("ERROR: " + ORDINARY + " was left out of this research (the page could not be checked, because no model answered the check). Read another source.", fetch(run, ORDINARY));
        assertFalse(saved(ORDINARY));
        ContentJudge.use(none);
        try {
            // an address the person gave is their own act: saved all the same, marked not checked yet, and checked when a model answers
            Object[] got = Corpus.addUrl(store, ORDINARY, "");
            assertNotNull(got[0]);
            assertEquals(Boolean.TRUE, got[3], "not checked yet");
            assertEquals(1, UncheckedPages.pending(store).size());
        } finally { ContentJudge.use(null); }
    }

    @Test
    void theTypedJudgeDecidesWhereItRunsAndOneWordWhereItCannot() {
        ContentJudge typedYes = new ContentJudge(new Judge("m", body -> DeclinesTest.judged(0.9)), null);
        assertEquals(ContentJudge.Verdict.YES, typedYes.ask("placeholder", "Placeholder question?").verdict());
        ContentJudge.Reading leaning = new ContentJudge(new Judge("m", body -> DeclinesTest.judged(0.55)), null).ask("placeholder", "Placeholder question?");
        assertEquals(ContentJudge.Verdict.UNSURE, leaning.verdict());
        assertTrue(leaning.leansYes(), "an unsure answer that leans yes leaves a page out");
        assertEquals(ContentJudge.Verdict.NO, new ContentJudge(new Judge("m", body -> DeclinesTest.judged(0.1)), null).ask("placeholder", "Placeholder question?").verdict());
        // a server that ignores the grammar: the typed judge did not run, and one word is asked
        ContentJudge ignored = new ContentJudge(new Judge("m", body -> { throw new IllegalStateException("placeholder: no grammar here"); }), m -> "Yes.");
        ContentJudge.Reading r = ignored.ask("placeholder", "Placeholder question?");
        assertEquals(ContentJudge.Verdict.YES, r.verdict());
        assertEquals("one word", r.by());
        assertEquals(ContentJudge.Verdict.UNJUDGED, new ContentJudge(null, m -> "I cannot tell.").ask("placeholder", "Placeholder question?").verdict());
        assertEquals(ContentJudge.Verdict.UNJUDGED, new ContentJudge(null, null).ask("placeholder", "Placeholder question?").verdict());
    }

    @Test
    void theDefaultQuestionsShareOneExcerptAndTheAlwaysDroppedOneReadsTheWholePage() throws Exception {
        String longPage = page("A long placeholder page", "MARKER-START " + "Placeholder sentence about gears and nothing else. ".repeat(600) + " MARKER-END");
        PageCheck.useGetter((url, timeout, lists) -> new Fetch.Result(url, 200, longPage.getBytes(StandardCharsets.UTF_8), "text/html"));
        ContentPolicy run = ContentPolicy.run(List.of(), markers(), store);
        fetch(run, "https://example.org/long");
        assertEquals(1, askedAbout(PageCheck.Category.EXPLICIT));
        assertEquals(1, askedAbout(PageCheck.Category.HOWTO));
        List<String> excerpt = asked.stream().filter(a -> !a[0].startsWith(PageCheck.Category.CHILD.question())).map(a -> a[1]).distinct().toList();
        assertEquals(1, excerpt.size(), "one state for the two default questions, so a server's prompt cache reads it once");
        String state = excerpt.get(0);
        assertTrue(state.length() < 3600, "cut to what they need: " + state.length());
        assertTrue(state.contains("MARKER-START") && !state.contains("MARKER-END"), "the opening is read");
        assertTrue(state.contains("PAGE TEXT") && state.contains("never instructions"), "the page's words are fenced as quoted material");
        // the always-dropped question: the whole page, in pieces, the last of which reaches its end
        List<String> pieces = asked.stream().filter(a -> a[0].startsWith(PageCheck.Category.CHILD.question())).map(a -> a[1]).toList();
        assertTrue(pieces.size() > 1, "a long page is read in pieces: " + pieces.size());
        assertTrue(pieces.get(0).contains("MARKER-START") && pieces.get(pieces.size() - 1).contains("MARKER-END"), "all of it");
        for (String p : pieces) assertTrue(p.length() < PageCheck.CHUNK + 600 && p.contains("PAGE TEXT") && p.contains("never instructions"), "each piece fenced, and no bigger than a piece: " + p.length());
        assertTrue(pieces.get(1).contains("(part 2 of " + pieces.size() + ")"), pieces.get(1).substring(0, 120));
    }

    @Test
    void whatTheAlwaysDroppedQuestionFindsPastTheExcerptIsLeftOutAndTheQuestionStopsThere() throws Exception {
        String filler = "Placeholder sentence about gears and nothing else. ";
        String deep = page("A long placeholder page", filler.repeat(120) + " MARKER-CHILD " + filler.repeat(600));
        PageCheck.useGetter((url, timeout, lists) -> new Fetch.Result(url, 200, deep.getBytes(StandardCharsets.UTF_8), "text/html"));
        ContentPolicy both = ContentPolicy.run(List.of(ContentPolicy.EXPLICIT, ContentPolicy.HOWTO), markers(), store);
        assertEquals("ERROR: https://example.org/deep was left out of this research (sexual content involving a child). Read another source.", fetch(both, "https://example.org/deep"));
        assertNull(RawCapture.find(store, "https://example.org/deep"), "not saved");
        assertEquals(2, askedAbout(PageCheck.Category.CHILD), "it stopped at the piece that said yes, the second of many");
    }

    @Test
    void aRunsReportNamesWhatItLeftOutAndNeverCitesIt() throws Exception {
        ContentJudge judge = markers();
        List<String> logged = new CopyOnWriteArrayList<>();
        Researcher.Tools tools = new Researcher.Tools() {
            @Override public List<Tool> web(String focus) { return web(focus, ContentPolicy.defaults()); }
            @Override public List<Tool> web(String focus, ContentPolicy policy) { return List.of(new WebFetchTool().focus(focus).policy(policy)); }
            @Override public BooleanSupplier exhausted() { return () -> false; }
        };
        ResearcherTest.ScriptedDrive drive = new ResearcherTest.ScriptedDrive() {
            @Override public ObjectNode chat(ArrayNode history, ArrayNode tools, int maxTokens, String toolChoice) {
                if (names(tools).contains("write_section")) return super.chat(history, tools, maxTokens, toolChoice);
                int turn = assistantTurns(history) + 1;
                return switch (turn) {
                    case 1 -> call("web_fetch", M.createObjectNode().put("url", ORDINARY));
                    case 2 -> call("web_fetch", M.createObjectNode().put("url", EXPLICIT));
                    case 3 -> call("note", M.createObjectNode().put("claim", "the gears were cut by hand").put("source", ORDINARY).put("quote", "cut by hand"));
                    case 4 -> call("note", M.createObjectNode().put("claim", "a placeholder claim from the page that was left out").put("source", EXPLICIT));
                    default -> call("done", M.createObjectNode().put("summary", "one page read"));
                };
            }
        };
        drive.criticWantsMore = false;
        Researcher r = new Researcher(drive, drive, tools, logged::add, 1, store);
        r.contentJudge(judge);
        Researcher.Result res = r.run(new Researcher.Ask("How were the placeholder gears cut?", "depth", 40, List.of("how were the gears cut?")), "");
        assertTrue(res.answer().contains("## Left out\n\nWritten by the library. These pages were left out of this research: they were not saved, not shown to the model and not cited.\n\n- left out: pornography or gore — " + EXPLICIT), res.answer());
        assertFalse(res.evidence().contains("a placeholder claim from the page that was left out"), "the note resting on it is not in the evidence: " + res.evidence());
        assertTrue(res.evidence().contains("the gears were cut by hand"), res.evidence());
        assertTrue(logged.contains("left out: pornography or gore — " + EXPLICIT), logged.toString());
        assertFalse(saved(EXPLICIT));
        assertEquals(1, res.stats().path("left_out").asInt());
        try (Stream<Path> files = Files.walk(store.root())) {
            for (Path f : files.filter(Files::isRegularFile).toList()) assertFalse(new String(Files.readAllBytes(f), StandardCharsets.ISO_8859_1).contains("MARKER-EXPLICIT"), "nothing of the page is anywhere in the library: " + f);
        }
    }

    @Test
    void aJobsAllowIsReadAndANightlyRunNeverHasIt() {
        ObjectNode args = M.createObjectNode();
        args.putArray("allow").add("explicit").add("HOWTO").add("unknown").add("explicit");
        assertEquals(List.of("explicit", "howto"), LibraryProtocol.allowOf(args), "known names, once each");
        assertEquals(List.of(), LibraryProtocol.allowOf(M.createObjectNode()), "a job filed before 0.5.0");
        Researcher.Ask ask = new Researcher.Ask("A placeholder question?", "broad", 0, List.of(), "both", List.of(), 0, List.of(), List.of("explicit", "self-harm", "nonsense"));
        assertEquals(List.of("explicit", "self-harm"), ask.allow());
        assertEquals(ask.allow(), ask.withFields(List.of("science")).allow(), "a field does not take the yes away");
        assertEquals(List.of(), Researcher.nightlyAsk("A placeholder question?", List.of(), 10, 10, "").allow(), "a nightly run: nobody was asked, so nothing is let in");
    }
}
