package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Who the graph takes for a person when nobody marked them: somebody a claim about a family names, whatever words the model
 * wrote the relation in, and nobody a claim about a company, a machine or the universe names because a word such as parent or
 * born was in it.
 */
class WhoIsAPersonTest {

    private static Finding claim(LibraryStore store, String s, String p, String o, String line, String cites) throws Exception {
        Finding x = new Finding(store.nextFindingId(s + " " + p + " " + o), line, List.of(), Finding.State.draft, Finding.ClaimType.extraction,
                Finding.Confidence.medium, "model:research", Instant.now().toString(), LocalDate.now().toString(), Finding.Volatility.stable, "",
                List.of(new Finding.Source("https://example.org/page", "n/a", cites)), List.of(), null, line + "\n", new Finding.Triple(s, p, o), List.of());
        store.write(x);
        return x;
    }

    private static Graph.Node node(LibraryStore store, String name) throws Exception { Graph g = Graph.build(store); return g.node(g.nodeIdOf(name)); }

    @Test
    void aClaimAboutACompanyOrAMachineMakesNobodyAPerson(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        claim(store, "Alan Turing", "invented", "Turing machine", "Alan Turing invented the Turing machine.", "cited by I-0001-machines");
        claim(store, "Instagram", "parent", "Meta", "Meta is the parent company of Instagram.", "cited by I-0001-machines");
        claim(store, "Alan Turing", "born in", "Paddington", "Alan Turing was born in Paddington.", "cited by I-0001-machines");
        claim(store, "The universe", "age", "13.8 billion years", "The universe is 13.8 billion years old.", "cited by I-0001-machines");
        claim(store, "Meta-analysis", "pools", "effect sizes", "A meta-analysis pools effect sizes across studies.", "cited by I-0001-machines");
        store.write(new Investigation("I-0001-machines", "Who invented the first computing machines?", Finding.State.accepted, "model:research", Instant.now().toString(), List.of(), List.of(), "Notes.\n"));
        for (String n : List.of("Alan Turing", "Instagram", "Meta", "The universe")) {
            Graph.Node x = node(store, n);
            assertFalse(x.kind().equals("person") || x.mayBeLiving(), x.toString());
        }
        Path vault = tmp.resolve("vault");
        Vault.generate(store, vault);
        for (String n : List.of("alan turing", "instagram", "meta")) assertTrue(Files.exists(vault.resolve("Things").resolve(n + ".md")), n);
    }

    @Test
    void somebodyARunAboutTheFamilyFindsIsAPersonHoweverTheRelationIsWritten(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(new FamilyAccount.Fact("Tom Ellis", "died-on", "1995", "", "Tom Ellis died in 1995.")), List.of()), "file:///family/notes.txt", "an aunt");
        // a family-history run somebody asked genealogy for
        store.write(new Investigation("I-0009-children", "Who were the children of Tom Ellis?", Finding.State.draft, "model:research", Instant.now().toString(), List.of(), List.of(), "The obituary names his daughter Mary Hart.\n"));
        Fields.record(store, "I-0009-children", "J-0009", "genealogy", "genealogy-command");
        claim(store, "Mary Hart", "is the daughter of", "Tom Ellis", "Mary Hart is the daughter of Tom Ellis.", "cited by I-0009-children");
        claim(store, "Mary Hart", "lives in", "Leeds", "Mary Hart lives in Leeds.", "cited by I-0009-children");
        claim(store, "Mary Hart", "was born in", "1990", "Mary Hart was born in 1990.", "cited by I-0009-children");
        Graph g = FamilyPeople.view(store);
        assertEquals("child-of", g.predicateOf("is the daughter of"));
        assertEquals("born-in", g.predicateOf("was born in"));
        assertEquals("lived-in", g.predicateOf("lives in"));
        assertEquals("child-of", g.predicateOf("is a child of"), "a relation's own description");
        assertEquals("married-to", g.predicateOf("is married to"));
        Graph.Node mary = node(store, "Mary Hart");
        assertEquals("person", mary.kind(), "the library's own map knows her too: the claims are genealogy's own work");
        assertTrue(mary.mayBeLiving(), mary.toString());
        assertEquals("is the daughter of", Graph.build(store).predicateOf("is the daughter of"), "an ordinary claim's words stay as written on the map");

        // a home or a job alone makes a person of the subject only in a claim about the family
        LibraryStore other = new LibraryStore(tmp.resolve("other")); other.init();
        other.write(new Investigation("I-0010-firms", "Which shipyards worked in Kure?", Finding.State.draft, "model:research", Instant.now().toString(), List.of(), List.of(), "Notes.\n"));
        claim(other, "Kure Naval Arsenal", "lives in", "Kure", "The arsenal lives on in Kure.", "cited by I-0010-firms");
        assertNotEquals("person", node(other, "Kure Naval Arsenal").kind());
    }

    @Test
    void theExtractorsAreGivenTheWordsForAFamilyRelationForGenealogyWorkAlone(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        String rule = LibrarianReview.extractionRule(store, Set.of("genealogy"));
        assertTrue(LibrarianReview.extractPrompt("A report.", rule).contains("child-of, married-to"), "a family-history run's review is given the family relations");
        assertTrue(Triples.prompt("Ann Hale was born in Leeds.", rule).contains("born-in (a place)"));
        assertFalse(LibrarianReview.extractPrompt("A report.").contains("child-of") || Triples.prompt("Dropbox was founded in 2007.", "").contains("born-in"), "an ordinary claim is not");
        assertEquals("", LibrarianReview.extractionRule(store, Set.of()));
    }

    /** A claim as a research run's review files it: the triple in the model's own words, and no mark on anybody. */
    private static Finding found(LibraryStore store, String s, String p, String o, String line) throws Exception {
        Finding x = new Finding(store.nextFindingId(s + " " + p + " " + o), line, List.of(), Finding.State.draft, Finding.ClaimType.extraction,
                Finding.Confidence.medium, "model:research", Instant.now().toString(), LocalDate.now().toString(), Finding.Volatility.stable, "",
                List.of(new Finding.Source("https://example.org/obituary-kenji-endo", "n/a", "cited by I-0003")), List.of(), null, line + "\n",
                new Finding.Triple(s, p, o), List.of());
        store.write(x);
        return x;
    }

    @Test
    void somebodyAResearchRunFindsIsAPersonWhoMayBeLivingByTheDates(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        FamilyLivingRuleTest.drafted(store, "Ichiro Endo", "born-on", "1900", "Ichiro Endo was born in 1900.");
        FamilyLivingRuleTest.drafted(store, "Ichiro Endo", "died-on", "1990", "Ichiro Endo died in 1990.");
        FamilyLivingRuleTest.drafted(store, "Ichiro Endo", "parent-of", "Kenji Endo", "Ichiro Endo was a parent of Kenji Endo.");
        FamilyLivingRuleTest.drafted(store, "Kenji Endo", "born-on", "1950", "Kenji Endo was born in 1950.");
        FamilyLivingRuleTest.drafted(store, "Kenji Endo", "died-on", "2020", "Kenji Endo died in 2020.");
        Graph.setKind(store, "Ichiro Endo", "person");
        Graph.setKind(store, "Kenji Endo", "person");
        // an obituary a family-history run read: a son and his daughter, and a great-grandmother, as the review files them
        Fields.record(store, "I-0003", "J-0003", "genealogy", "genealogy-command");
        List.of(
                found(store, "Taro Endo", "son of", "Kenji Endo", "Taro Endo is a son of Kenji Endo."),
                found(store, "Taro Endo", "born on", "1985", "Taro Endo was born in 1985."),
                found(store, "Ren Endo", "child-of", "Taro Endo", "Ren Endo is a child of Taro Endo."),
                found(store, "Ren Endo", "born-on", "2016", "Ren Endo was born in 2016."));
        found(store, "Hisa Endo", "mother of", "Ichiro Endo", "Hisa Endo was the mother of Ichiro Endo.");

        Graph g = Graph.build(store);
        for (String who : List.of("Taro Endo", "Ren Endo")) {
            Graph.Node n = g.node(g.nodeIdOf(who));
            assertEquals("person", n.kind(), n.toString());
            assertTrue(n.mayBeLiving(), "the dates say they may be living: " + n);
        }
        Graph.Node hisa = g.node(g.nodeIdOf("Hisa Endo"));
        assertEquals("person", hisa.kind());
        assertFalse(hisa.mayBeLiving(), "the mother of a man born in 1900 is placed in the past: " + hisa);

        // the research: --skip-living asks about neither and names neither; with the living, the child born in 2016 is asked about too
        List<FamilyQuestions.Ask> dead = FamilyQuestions.around(store, "Ichiro Endo", 6, 3, false);
        assertTrue(dead.stream().noneMatch(a -> a.question().contains("Taro Endo") || a.question().contains("Ren Endo")), dead.toString());
        List<FamilyQuestions.Ask> all = FamilyQuestions.around(store, "Ichiro Endo", 6, 3, true);
        assertTrue(all.stream().anyMatch(a -> a.person().equals("Ren Endo")) && all.stream().anyMatch(a -> a.person().equals("Taro Endo")), all.toString());
    }
}
