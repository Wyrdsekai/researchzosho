package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Everything genealogy reads of a claim's people reads the links ({@link FamilyLinks}): a relation a text writes with a given name alone,
 * linked to the person the same text names in full, is that person's relation for the names over a life and for the questions too.
 */
class FamilyLinkedClaimsTest {

    private static final String PAGE = "file:///family/parish-page.txt";

    @Test
    void theSameWhenTheReadFiledHerNamesUnderHerGivenNameAlone(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameHistoryTest.store(tmp);
        FamilyPeople.holdsAFamily(store);
        FamilyNameHistoryTest.file(store, "file:///family/tree-notes.txt", List.of(
                        new FamilyAccount.Fact("Mary Ellis", "sex", "female", "", "Mary Ellis (1852-1920)."),
                        new FamilyAccount.Fact("Mary Ellis", "married-to", "John Ellis", "", "Mary Ellis (1852-1920), wife of John Ellis."),
                        new FamilyAccount.Fact("Mary Ellis", "child-of", "Tom Hale", "", "Mary Ellis, daughter of Tom Hale.")),
                List.of());
        FamilyNameHistoryTest.file(store, PAGE, List.of(
                        new FamilyAccount.Fact("Mary", "child-of", "Tom Hale", "", "Mary was the daughter of Tom Hale, the miller."),
                        new FamilyAccount.Fact("Mary", "married-to", "John Ellis", "1875", "In 1875 Mary married John Ellis."),
                        new FamilyAccount.Fact("Tom Hale", "sex", "male", "", "Mary was the daughter of Tom Hale, the miller."),
                        new FamilyAccount.Fact("John Ellis", "sex", "male", "", "In 1875 Mary married John Ellis.")),
                List.of(FamilyNameHistoryTest.name("Mary", "Mary Hale", "Hale", "Mary", "", "", "Mary Hale, as she was, grew up at the mill."),
                        FamilyNameHistoryTest.name("Mary", "Mary Ellis", "Ellis", "Mary", "", "", "Mary Ellis kept the shop after 1900."),
                        FamilyNameHistoryTest.name("Tom Hale", "Tom Hale", "Hale", "Tom", "birth", "", "Mary was the daughter of Tom Hale, the miller."),
                        FamilyNameHistoryTest.name("John Ellis", "John Ellis", "Ellis", "John", "birth", "", "In 1875 Mary married John Ellis.")));
        Graph.alias(store, "Mary", List.of("Mary Hale", "Mary Ellis"), PAGE);
        Graph g = FamilyPeople.view(store);
        String mary = g.nodeIdOf("Mary Ellis");
        FamilyNameHistory.Index idx = FamilyNameHistory.of(g);
        assertNotNull(idx.birth(mary), "her name at birth is worked out from her father's family name: " + idx.names(mary) + " LINKS " + FamilyLinks.current(store).links().stream().map(FamilyLinks::sentence).toList() + " EDGES " + g.edges().stream().filter(e -> e.from().equals(mary) || e.to().equals(mary)).map(e -> e.from() + " " + e.predicate() + " " + e.to()).toList());
        assertEquals("Mary Hale", idx.birth(mary).written());
        FamilyNameHistory.Name married = idx.latest(mary);
        assertEquals("Mary Ellis", married.written());
        assertEquals("marriage", married.kind(), "worked out from her marriage: " + idx.names(mary));
        assertEquals(1875, married.from().year(), idx.names(mary).toString());
        assertTrue(FamilyNameQuestions.open(store).stream().noneMatch(q -> q.text().contains("given name alone")),
                FamilyNameQuestions.open(store).stream().map(FamilyNameQuestions.Question::text).toList().toString());
    }

    @Test
    void aWomansBirthAndMarriedNamesAreWorkedOutFromRelationsWrittenWithHerGivenNameAlone(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameHistoryTest.store(tmp);
        FamilyPeople.holdsAFamily(store);
        FamilyNameHistoryTest.file(store, "file:///family/tree-notes.txt", List.of(
                        new FamilyAccount.Fact("Mary Ellis", "married-to", "John Ellis", "", "Mary Ellis (1852-1920), wife of John Ellis.")), List.of());
        FamilyNameHistoryTest.file(store, PAGE, List.of(
                        new FamilyAccount.Fact("Mary Ellis", "sex", "female", "", "Mary Ellis, née Hale, is buried here."),
                        new FamilyAccount.Fact("Mary", "child-of", "Tom Hale", "", "Mary was the daughter of Tom Hale, the miller."),
                        new FamilyAccount.Fact("Mary", "married-to", "John Ellis", "1875", "In 1875 Mary married John Ellis."),
                        new FamilyAccount.Fact("Tom Hale", "sex", "male", "", "Mary was the daughter of Tom Hale, the miller."),
                        new FamilyAccount.Fact("John Ellis", "sex", "male", "", "In 1875 Mary married John Ellis.")),
                List.of(FamilyNameHistoryTest.name("Mary Ellis", "Mary Hale", "Hale", "Mary", "", "", "Mary Ellis, née Hale, is buried here."),
                        FamilyNameHistoryTest.name("Tom Hale", "Tom Hale", "Hale", "Tom", "birth", "", "Mary was the daughter of Tom Hale, the miller."),
                        FamilyNameHistoryTest.name("John Ellis", "John Ellis", "Ellis", "John", "birth", "", "In 1875 Mary married John Ellis.")));
        Graph g = FamilyPeople.view(store);
        String mary = g.nodeIdOf("Mary Ellis");
        FamilyNameHistory.Index idx = FamilyNameHistory.of(g);
        FamilyNameHistory.Name birth = idx.birth(mary);
        assertNotNull(birth, "her name at birth is worked out from her father's family name: " + idx.names(mary));
        assertEquals("Mary Hale", birth.written());
        FamilyNameHistory.Name married = idx.latest(mary);
        assertEquals("Mary Ellis", married.written());
        assertEquals("marriage", married.kind(), "worked out from her marriage to a man of that family name: " + idx.names(mary));
        assertEquals(1875, married.from().year());
        assertTrue(FamilyNameQuestions.open(store).stream().noneMatch(q -> q.text().contains("given name alone")),
                "the mentions are linked to her, so nobody is asked whether Mary is she: " + FamilyNameQuestions.open(store).stream().map(FamilyNameQuestions.Question::text).toList());
    }

    // ── a brother or sister with no parents of their own ──────────────────────────────────────────────────────────────

    private static LibraryStore hales(Path tmp, String siblingWords) throws Exception {
        LibraryStore store = FamilyNameHistoryTest.store(tmp);
        FamilyPeople.holdsAFamily(store);
        FamilyNameHistoryTest.file(store, "file:///family/tree-notes.txt", List.of(
                new FamilyAccount.Fact("Tom Hale", "child-of", "Isamu Hale", "", "Tom Hale, son of Isamu Hale and Ann Hale."),
                new FamilyAccount.Fact("Tom Hale", "child-of", "Ann Hale", "", "Tom Hale, son of Isamu Hale and Ann Hale.")), List.of());
        FamilyNameHistoryTest.file(store, "file:///family/notes.txt", List.of(
                new FamilyAccount.Fact("Ruth Hale", "sibling-of", "Tom Hale", "", siblingWords)), List.of());
        return store;
    }

    private static List<Graph.Edge> workedOut(Graph g, String child) {
        return g.edges().stream().filter(e -> e.state().equals(Graph.WORKED_OUT) && e.from().equals(g.nodeIdOf(child))).toList();
    }

    @Test
    void aSisterWithNoParentsOfHerOwnIsShownAsAChildOfHerBrothersParentsAndNoMarriageIsMadeOfIt(@TempDir Path tmp) throws Exception {
        LibraryStore store = hales(tmp, "Ruth Hale was Tom's sister.");
        Graph g = FamilyPeople.view(store);
        List<Graph.Edge> worked = workedOut(g, "Ruth Hale");
        assertEquals(2, worked.size(), worked.toString());
        assertTrue(worked.stream().allMatch(e -> e.predicate().equals("child-of")));
        assertTrue(worked.stream().anyMatch(e -> e.to().equals(g.nodeIdOf("Isamu Hale"))) && worked.stream().anyMatch(e -> e.to().equals(g.nodeIdOf("Ann Hale"))));
        assertTrue(g.edges().stream().noneMatch(e -> e.predicate().equals("married-to")), "two parents of one child are no marriage");
        assertTrue(store.scanFindings().findings().stream().noneMatch(f -> f.triple() != null && f.triple().subject().equals("Ruth Hale") && f.triple().predicate().equals("child-of")), "filed nowhere");
        assertTrue(Graph.build(store).edges().stream().noneMatch(e -> e.state().equals(Graph.WORKED_OUT)), "and the core graph never shows it");
        assertTrue(FamilyLinks.about(store, g, g.nodeIdOf("Ruth Hale")).stream().anyMatch(x -> x.contains("worked out: brother or sister of Tom Hale")), "her page says it is worked out, and from what");
    }

    @Test
    void aHalfSisterOrASisterWithParentsOfHerOwnIsGivenNone(@TempDir Path tmp) throws Exception {
        LibraryStore half = hales(tmp.resolve("half"), "Ruth Hale was Tom's half-sister.");
        assertTrue(workedOut(FamilyPeople.view(half), "Ruth Hale").isEmpty(), "a half-sister's other parent is not known");

        LibraryStore own = hales(tmp.resolve("own"), "Ruth Hale was Tom's sister.");
        FamilyNameHistoryTest.file(own, "file:///family/register.txt", List.of(
                new FamilyAccount.Fact("Ruth Hale", "child-of", "Mary Ellis", "", "Ruth Hale, daughter of Mary Ellis.")), List.of());
        assertTrue(workedOut(FamilyPeople.view(own), "Ruth Hale").isEmpty(), "a source names another parent of hers");
    }

    @Test
    void aSisterWhoseDatesRuleOutHerBrothersParentsIsGivenNone(@TempDir Path tmp) throws Exception {
        LibraryStore late = hales(tmp.resolve("late"), "Ruth Hale was Tom's sister.");
        FamilyNameHistoryTest.file(late, "file:///family/register.txt", List.of(
                new FamilyAccount.Fact("Ruth Hale", "born-on", "1948", "", "Ruth Hale, born 1948."),
                new FamilyAccount.Fact("Isamu Hale", "born-on", "1865", "", "Isamu Hale, born 1865.")), List.of());
        assertTrue(workedOut(FamilyPeople.view(late), "Ruth Hale").isEmpty(), "a father 83 years older than her is not hers");

        LibraryStore dead = hales(tmp.resolve("dead"), "Ruth Hale was Tom's sister.");
        FamilyNameHistoryTest.file(dead, "file:///family/register.txt", List.of(
                new FamilyAccount.Fact("Ruth Hale", "born-on", "1940", "", "Ruth Hale, born 1940."),
                new FamilyAccount.Fact("Ann Hale", "died-on", "1933", "", "Ann Hale died in 1933.")), List.of());
        assertTrue(workedOut(FamilyPeople.view(dead), "Ruth Hale").isEmpty(), "a mother dead seven years before her birth is not hers");

        LibraryStore fits = hales(tmp.resolve("fits"), "Ruth Hale was Tom's sister.");
        FamilyNameHistoryTest.file(fits, "file:///family/register.txt", List.of(
                new FamilyAccount.Fact("Ruth Hale", "born-on", "1905", "", "Ruth Hale, born 1905."),
                new FamilyAccount.Fact("Isamu Hale", "born-on", "1870", "", "Isamu Hale, born 1870.")), List.of());
        assertEquals(2, workedOut(FamilyPeople.view(fits), "Ruth Hale").size(), "dates that fit rule nobody out");
    }

    // ── a reading on file beside a name in characters ─────────────────────────────────────────────────────────────────

    @Test
    void aReadingInTheListOfNamesTiesAFamilyPartInCharactersToItsLettersWhenNoClaimSplitsIt(@TempDir Path tmp) throws Exception {
        // no claim gives 森田 as a family part; the library guesses it from the people who share it, and the list of names reads it もりた:
        // so Kenji Morita and 森田健二 carry one family part, and a wife of that family does not make 森田健二 a name he married into
        LibraryStore store = FamilyNameHistoryTest.store(tmp);
        FamilyPeople.holdsAFamily(store);
        FamilyNameHistoryTest.file(store, "file:///family/koseki-notes.txt", List.of(
                        new FamilyAccount.Fact("森田健二", "sex", "male", "", "森田健二、男。"),
                        new FamilyAccount.Fact("森田健二", "sibling-of", "森田二郎", "", "森田健二の弟 森田二郎。"),
                        new FamilyAccount.Fact("森田健二", "married-to", "森田花子", "1932", "昭和七年 森田花子と婚姻。"),
                        new FamilyAccount.Fact("森田二郎", "sex", "male", "", "森田二郎、男。"),
                        new FamilyAccount.Fact("森田花子", "sex", "female", "", "森田花子、女。")),
                List.of());
        FamilyNameHistoryTest.file(store, "file:///family/obituary.txt", List.of(
                        new FamilyAccount.Fact("森田健二", "died-on", "1990", "", "Kenji Morita died in 1990.")),
                List.of(FamilyNameHistoryTest.name("森田健二", "Kenji Morita", "Morita", "Kenji", "", "", "Kenji Morita died in 1990.")));
        Graph.alias(store, "森田健二", List.of("もりた けんじ"), "file:///family/koseki-notes.txt");
        Graph g = FamilyPeople.view(store);
        String kenji = g.nodeIdOf("森田健二");
        FamilyNameHistory.Index idx = FamilyNameHistory.of(g);
        assertTrue(idx.names(kenji).stream().noneMatch(n -> n.kind().equals("marriage")),
                "his name in letters is of the same family, so no family part changed at his marriage: " + idx.names(kenji));
    }
}
