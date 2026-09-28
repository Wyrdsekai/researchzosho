package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The owner's rule in a library that holds family work too: the ordinary items in it (the ordinary claims, the ordinary questions, an
 * ordinary ask that resembles a family question) look the same with genealogy on and with it off. Library C holds the ordinary fixture of
 * {@link GenealogyIsolationDifferentialTest} and a family: the family's own account, a run asked for in genealogy mode with the claims its
 * review filed, a question the family research left waiting, and the searches of that run. Library D holds the same, with genealogy
 * switched off. What differs is named: the family pages in the menu, the family tool in the chat, and what genealogy's own work reads as.
 */
class GenealogyMixedLibraryTest {

    static LibraryStore mixed(Path root, boolean genealogyOn) throws Exception {
        LibraryStore store = GenealogyIsolationDifferentialTest.fixture(root, true);
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(
                new FamilyAccount.Fact("Tom Hale", "died-on", "1901", "", "Tom Hale died in 1901."),
                new FamilyAccount.Fact("Tom Hale", "lived-in", "Leeds", "", "Tom Hale lived in Leeds."),
                new FamilyAccount.Fact("Ann Ellis", "child-of", "Tom Hale", "", "Ann Ellis was Tom Hale's daughter."),
                new FamilyAccount.Fact("Arthur Ellis", "sibling-of", "Ann Ellis", "", "Arthur Ellis was her brother.")), List.of()), "file:///family/notes.txt", "an aunt");
        // a run asked for in genealogy mode, and what its review filed
        store.write(new Investigation("I-0010-hale-parents", "Tom Hale (died 1901): who were the parents?", Finding.State.accepted, "model:research", Instant.now().toString(), List.of(), List.of(), "Notes.\n"));
        Fields.record(store, "I-0010-hale-parents", "J-0010", "genealogy", "genealogy-command");
        int n = 100;
        for (String[] c : new String[][]{{"Mary Hale", "is the daughter of", "Tom Hale"}, {"Mary Hale", "works as", "a teacher"}}) {
            String line = c[0] + " " + c[1] + " " + c[2] + ".";
            store.write(new Finding(String.format("F-%04d-family", ++n), line, List.of(), Finding.State.accepted, Finding.ClaimType.extraction, Finding.Confidence.medium, "reviewer",
                    "2026-09-01T00:00:00Z", "2026-09-01", Finding.Volatility.stable, "", List.of(new Finding.Source("https://example.org/register-" + n, "n/a", "cited by I-0010-hale-parents")),
                    List.of(), null, line + "\n", new Finding.Triple(c[0], c[1], c[2]), List.of()));
        }
        // an ordinary claim about a namesake
        store.write(new Finding("F-0200-pamphlet", "Tom wrote a pamphlet.", List.of(), Finding.State.accepted, Finding.ClaimType.extraction, Finding.Confidence.medium, "reviewer",
                "2026-09-01T00:00:00Z", "2026-09-01", Finding.Volatility.stable, "", List.of(new Finding.Source("https://example.org/pamphlet", "n/a", "cited by I-0001-notes")),
                List.of(), null, "Tom wrote a pamphlet.\n", new Finding.Triple("Tom", "wrote", "a pamphlet"), List.of()));
        store.frontier("person me " + FamilyReset.FROM_TREE + Fields.mark("genealogy"), "Tom Hale (died 1901): who were the parents of Tom Hale of Leeds?");
        SearchLog.add(store, List.of(new SearchLog.Entry("2026-09-02", "Tom Hale (died 1901): who were the parents?", "loc-newspapers", "\"Tom Hale\" Leeds", 1880, 1901, 2, "I-0010-hale-parents", SearchLog.OK)));
        // and what that run looked for and did not find, as the review files the report's own section
        for (Looked.Entry e : Looked.fromReport("I-0010-hale-parents", "Tom Hale (died 1901): who were the parents?",
                "### Searched and not found\n- freebmd: \"Tom Hale\" Leeds births, 1850-1870\n", "2026-09-02")) Looked.add(store, e);
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        if (!genealogyOn) Profiles.disable(store, "genealogy");
        return store;
    }

    /** What the ordinary items of a library look like. */
    static Map<String, String> ordinary(LibraryStore store) throws Exception {
        Map<String, String> out = new LinkedHashMap<>();
        Graph g = Graph.build(store);
        List<String> edges = new ArrayList<>();
        for (Graph.Edge e : g.edges()) {
            if (!e.findingId().endsWith("-claim") && !e.findingId().equals("F-0200-pamphlet")) continue;   // the ordinary claims
            edges.add(e.from() + " [" + g.node(e.from()).kind() + "] -" + e.predicate() + "-> " + e.to() + " [" + g.node(e.to()).kind() + "]");
        }
        edges.sort(null);
        out.put("ordinary edges", String.join("\n", edges));
        for (int i = 0; i < 6; i++) {
            String q = GenealogyIsolationDifferentialTest.QUESTIONS.get(i);
            out.put("known " + i, Researcher.known(store, q));
            GenealogyIsolationDifferentialTest.Recording drive = new GenealogyIsolationDifferentialTest.Recording();
            List<String> log = new CopyOnWriteArrayList<>();
            new Researcher(drive, drive, new ResearcherTest.FakeTools(), log::add, 2, store).run(new Researcher.Ask(q, "depth", 60, List.of()), Researcher.known(store, q));
            List<String> seen = new ArrayList<>(drive.seen); seen.sort(null);
            out.put("run " + i, GenealogyIsolationDifferentialTest.mask(String.join("\n----\n", seen), store.root()));
        }
        Finding f = store.finding("F-0001-claim");
        out.put("triple prompt of an ordinary claim", Triples.prompt(f.body(), LibrarianReview.extractionRule(store, Fields.ofClaim(store, f))));
        out.put("review of an ordinary run", LibrarianReview.extractPrompt("A report.\n", LibrarianReview.extractionRule(store, Fields.ofRun(store, "I-0001-notes"))));
        out.put("an ordinary ask like the family's waiting question is filed", String.valueOf(Frontier.demand(store, "Who were the parents of Tom Hale of Leeds, and when?", "patron:x")));
        out.put("an ordinary run on the name is told the ordinary searches only", Researcher.known(store, "Tom Hale (died 1901): who were the parents?"));
        return out;
    }

    @Test
    void theOrdinaryItemsOfALibraryWithAFamilyLookTheSameWithGenealogyOnAndOff(@TempDir Path tmp) throws Exception {
        LibraryStore c = mixed(tmp.resolve("c").resolve("lib"), true);
        LibraryStore d = mixed(tmp.resolve("d").resolve("lib"), false);
        Map<String, String> oc = ordinary(c), od = ordinary(d);
        List<Executable> checks = new ArrayList<>();
        for (String k : oc.keySet()) { String x = od.get(k), y = oc.get(k); checks.add(() -> assertEquals(x, y, k + " differs:" + GenealogyIsolationDifferentialTest.firstDifference(x, y))); }
        assertAll(checks);
        assertEquals("true", oc.get("an ordinary ask like the family's waiting question is filed"), "an ordinary ask is not folded into the family's question");
        assertFalse(oc.get("an ordinary run on the name is told the ordinary searches only").contains("loc-newspapers"), "the family-history run's searches are the family's");
        assertFalse(oc.get("an ordinary run on the name is told the ordinary searches only").contains("freebmd"), "and so is what it looked for and did not find");
    }

    @Test
    void whatDiffersIsNamed(@TempDir Path tmp) throws Exception {
        LibraryStore c = mixed(tmp.resolve("c").resolve("lib"), true);
        LibraryStore d = mixed(tmp.resolve("d").resolve("lib"), false);
        assertTrue(Pages.hasFamily(c) && !Pages.hasFamily(d), "the family pages are in the menu where genealogy is on and holds the family");
        assertTrue(Librarian.toolNames(c).contains("library_who") && !Librarian.toolNames(d).contains("library_who"), "and the chat is given the family tool");
        // genealogy's own work reads as genealogy's where it is on: the run's claim with its relation, the person it names
        Graph gc = Graph.build(c), gd = Graph.build(d);
        assertEquals("child-of", relation(gc, "mary hale", "tom hale"));
        assertEquals("is the daughter of", relation(gd, "mary hale", "tom hale"), "switched off, it reads as any claim");
        assertEquals("person", gc.node("mary hale").kind());
        String family = Researcher.known(c, "Tom Hale (died 1901): who were the parents?", List.of("genealogy"));
        assertTrue(family.contains("loc-newspapers") && family.contains("freebmd"), "a run in genealogy mode is told the family's searches, and what they did not find: " + family);
        String rule = LibrarianReview.extractionRule(c, Fields.ofRun(c, "I-0010-hale-parents"));
        assertTrue(rule.contains("child-of"), "the family-history run's review is given the family relations");
        assertEquals("", LibrarianReview.extractionRule(d, Fields.ofRun(d, "I-0010-hale-parents")), "not when genealogy is off");
    }

    private static String relation(Graph g, String from, String to) {
        for (Graph.Edge e : g.edges()) if (e.from().equals(from) && e.to().equals(to) && !e.predicate().equals("is filed under")) return e.predicate();
        return null;
    }

}
