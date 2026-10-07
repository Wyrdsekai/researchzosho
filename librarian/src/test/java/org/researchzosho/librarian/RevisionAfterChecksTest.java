package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** The revision after the checks (0.5.4): which sentences are flagged, and that a revision replaces its sentence only when it passes the check. */
class RevisionAfterChecksTest {

    static final ObjectMapper J = new ObjectMapper();
    static final String EVIDENCE = "- BrowseComp holds 1,266 questions — source: https://example.org/bc\n- Recall on the open corpus was 21.4% — source: https://example.org/rc";
    static final List<CiteCheck.Ref> REFS = List.of(new CiteCheck.Ref(1, "https://example.org/bc", "", "BrowseComp"), new CiteCheck.Ref(2, "https://example.org/rc", "", "Recall"));

    @Test
    void theMarkedSentencesAreFlaggedOnceEachWithTheirSpans() {
        String marked = "## Answer\n\nThe set has 1,500 questions. [number not in any note or source read this run: 1500]\nRecall fell to 21.4% [2]. "
                + "Scores cluster near 58% [1] [not supported by the cited source on check], which is high. [number not in any note or source read this run: 58 %]\nThat is all.";
        List<Researcher.Flag> flags = Researcher.flagged(marked);
        assertEquals(2, flags.size(), flags.toString());
        assertEquals("The set has 1,500 questions.", flags.get(0).sentence());
        assertEquals("number not in any note or source read this run: 1500", flags.get(0).why());
        assertEquals("Scores cluster near 58% [1], which is high.", flags.get(1).sentence());
        assertTrue(flags.get(1).why().contains("not supported by the cited source") && flags.get(1).why().contains("58 %"), flags.get(1).why());
        for (Researcher.Flag f : flags) assertTrue(marked.substring(f.start(), f.end()).contains(f.sentence().substring(0, 12)), "the span holds the sentence");
        assertEquals("That is all.", marked.substring(flags.get(1).end()).strip(), "the span ends with the sentence's marks");
        assertTrue(Researcher.saysUnverified("This could not be verified in this run.") && !Researcher.saysUnverified("This is so."));
    }

    /** A drive whose one turn answers with the revise calls given. */
    static Researcher.Drive drive(List<ObjectNode> revisions) {
        return new Researcher.Drive() {
            @Override public ObjectNode chat(ArrayNode messages, ArrayNode tools, int maxTokens, String toolChoice) {
                ObjectNode m = J.createObjectNode(); m.put("role", "assistant"); m.putNull("content");
                ArrayNode calls = m.putArray("tool_calls");
                for (ObjectNode r : revisions) { ObjectNode c = calls.addObject(); c.put("id", "c" + calls.size()); c.put("type", "function"); ObjectNode f = c.putObject("function"); f.put("name", "revise"); f.put("arguments", r.toString()); }
                return m;
            }
            @Override public String classify(ArrayNode messages, int maxTokens) { return ""; }
            @Override public int contextWindow() { return 32_000; }
        };
    }

    @Test
    void aRevisionReplacesItsSentenceOnlyWhenItPassesTheCheck(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp); store.init();
        String text = "## Answer\n\nThe set has 1,500 questions. [number not in any note or source read this run: 1500]\n"
                + "Recall fell to 30% [2]. [not supported by the cited source on check]\n"
                + "Top scores cluster near 58% [1]. [not supported by the cited source on check]\n"
                + "The corpus is open. [number not in any note or source read this run: 9999]\nThat is all.";
        List<ObjectNode> revisions = List.of(
                J.createObjectNode().put("n", 1).put("revised", "The set has 1,266 questions [1].").put("why", "the note states 1,266"),
                J.createObjectNode().put("n", 2).put("revised", "Recall fell to 21.4% on the open corpus; the exact figure could not be verified in this run.").put("why", "the note says 21.4"),
                J.createObjectNode().put("n", 3).put("revised", "Top scores cluster near 60%.").put("why", "rounded"),   // still a number nothing states: kept as marked
                J.createObjectNode().put("original", "The corpus is open.").put("revised", "").put("why", "nothing supports it"));
        Researcher.Drive d = drive(revisions);
        List<String> log = new ArrayList<>(), notes = new ArrayList<>();
        Researcher r = new Researcher(d, d, new ResearcherTest.FakeTools(), log::add, 1, store);
        Map<Integer, String> textByRef = new HashMap<>();
        Researcher.Revision rev = r.revise(text, EVIDENCE, REFS, textByRef, Set.of(), new Researcher.Budget(0), notes);
        assertEquals(3, rev.changed(), rev.changes().toString());
        assertEquals(1, rev.kept(), rev.changes().toString());
        assertTrue(rev.text().contains("The set has 1,266 questions [1].") && !rev.text().contains("1,500"), rev.text());
        assertTrue(rev.text().contains("could not be verified in this run") && !rev.text().contains("30% [2]"), rev.text());
        assertTrue(rev.text().contains("Top scores cluster near 58% [1]. [not supported by the cited source on check]"), "kept as marked: " + rev.text());
        assertFalse(rev.text().contains("The corpus is open."), "left out: " + rev.text());
        assertTrue(rev.text().endsWith("That is all."), rev.text());
        assertEquals(4, rev.changes().size());
        assertTrue(rev.changes().get(2).startsWith("kept as marked:") && rev.changes().get(2).contains("60"), rev.changes().get(2));
        assertTrue(rev.changes().get(3).startsWith("left out:"), rev.changes().get(3));
        assertTrue(rev.summary().contains("3 changed, 1 stand as marked"), rev.summary());
    }

    @Test
    void withNoTurnLeftTheFlaggedSentencesStandAsMarked(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp); store.init();
        Researcher.Drive d = drive(List.of());
        Researcher r = new Researcher(d, d, new ResearcherTest.FakeTools(), s -> { }, 1, store);
        Researcher.Budget spent = new Researcher.Budget(4);
        while (spent.take()) { }
        String text = "A. [number not in any note or source read this run: 1500]";
        Researcher.Revision rev = r.revise(text, EVIDENCE, REFS, Map.of(), Set.of(), spent, new ArrayList<>());
        assertEquals(text, rev.text());
        assertEquals(1, rev.kept());
        assertTrue(rev.summary().contains("No turn was left"), rev.summary());
        assertEquals("", Researcher.flagged("No marks here.").isEmpty() ? "" : "x");
    }
}
