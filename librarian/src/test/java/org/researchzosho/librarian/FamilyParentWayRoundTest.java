package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.librarian.profiles.GenealogyProfile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A parent and a child the wrong way round. At reading, the words win over the model's direction: "Tom's daughter Ann", "Ann, the daughter
 * of Tom", 父 森田勇, 長男 正一, "My parents, Ruth and Tom". In the family's view, a parent the birth years make no older than twelve years
 * before the child is set aside, with the reason, and raises no question; so is a claim its own words say the other way round.
 */
class FamilyParentWayRoundTest {

    private static FamilyAccount.Fact said(String s, String r, String o, String quote) { return new FamilyAccount.Fact(s, r, o, "", quote); }
    private static FamilyAccount.Fact fact(String s, String r, String o) { return new FamilyAccount.Fact(s, r, o, "", ""); }

    private static LibraryStore store(Path tmp, String teller, FamilyAccount.Fact... facts) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(facts), List.of()), "file:///community.pdf", teller);
        return store;
    }

    private static String parent(String quote, String a, String b, String teller) { return FamilyKin.parentByWords(quote, List.of(a), List.of(b), teller); }

    @Test
    void theWordsSayWhichOfTheTwoIsTheParent() {
        assertEquals("a", parent("Tom Hale's daughter Ann Hale", "Tom Hale", "Ann Hale", ""));
        assertEquals("b", parent("Tom Hale's daughter Ann Hale", "Ann Hale", "Tom Hale", ""));
        assertEquals("a", parent("Ann Hale, the daughter of Tom Hale, was born in Leeds.", "Tom Hale", "Ann Hale", ""));
        assertEquals("b", parent("Ann Hale was the mother of Tom Hale.", "Tom Hale", "Ann Hale", ""));
        assertEquals("a", parent("父：森田勇", "森田勇", "森田正一", ""), "a register's label");
        assertEquals("b", parent("長男 森田正一", "森田正一", "森田勇", ""));
        assertEquals("a", parent("森田勇の長男正一", "森田勇", "森田正一", ""));
        assertEquals("b", parent("正一は勇の長男である。", "森田正一", "森田勇", ""));
        assertEquals("b", parent("My parents, Ruth Hale and Tom Hale, met in Leeds in 1930.", "Kimie Hale", "Ruth Hale", "Kimie Hale"), "the teller's parents");
        assertEquals("b", parent("My parents, Ruth Hale and Tom Hale, met in Leeds in 1930.", "Kimie Hale", "Tom Hale", "the writer of community.pdf"), "the second of the list");
        assertEquals("", parent("My parents, Ruth Hale and Tom Hale, met in Leeds in 1930.", "Ned Ellis", "Ruth Hale", "Kimie Hale"), "the teller's parents say nothing of Ned");
        assertEquals("", parent("Ken Morita's father Tom Hale kept a shop.", "Ann Hale", "Tom Hale", ""), "the father of somebody else");
        assertEquals("", parent("Tom Hale, my mother's uncle", "Ann Hale", "Tom Hale", "Kimie Hale"), "a step of a chain");
        assertEquals("", parent("Tom Hale married Ann Ellis in 1905.", "Tom Hale", "Ann Ellis", ""), "no word for a parent or a child");
        assertEquals("", parent("Tom Hale is the father of Ann Hale; Ann Hale is the mother of Tom Hale.", "Tom Hale", "Ann Hale", ""), "both ways: nothing");
        assertEquals("", parent("Their children include Nell, Ned and Beth, and grandchildren Will and Kit (children of Ned).", "Ann Hale", "Ned Hale", ""),
                "the children of Ned are his grandchildren, not Ann");
        assertEquals("", parent("母は 遠藤勇 の次女ハル（1911年 - 1990年）。", "森田ハル", "森田正一", ""), "the daughter of somebody else");
    }

    @Test
    void aReadFilesTheWayTheWordsSayIt() {
        String text = "Preface\n\nMy parents, Ruth Hale and Tom Hale, met in Leeds in 1930, and I was born in 1932.\n";
        FamilyAccount.Read r = FamilyAccount.read(text, "Kimie Hale", new GenealogyProfile().predicates(), p -> """
                {"people": [], "facts": [
                 {"subject": "Ruth Hale", "relation": "child-of", "object": "Kimie Hale", "date": "", "quote": "My parents, Ruth Hale and Tom Hale, met in Leeds in 1930, and I was born in 1932."},
                 {"subject": "Kimie Hale", "relation": "child-of", "object": "Tom Hale", "date": "", "quote": "My parents, Ruth Hale and Tom Hale, met in Leeds in 1930, and I was born in 1932."}]}""");
        List<String> triples = r.facts().stream().map(f -> f.subject() + " | " + f.relation() + " | " + f.object()).toList();
        assertTrue(triples.contains("Kimie Hale | child-of | Ruth Hale") && triples.contains("Kimie Hale | child-of | Tom Hale"), triples.toString());
        assertTrue(r.dropped().stream().anyMatch(d -> d.contains(FamilyAccount.TURNED)), r.dropped().toString());
        assertTrue(GenealogyProfile.droppedSaid(r.dropped()).contains("the other way round from how the model read it, because the text's own words say who is the parent and who is the child"));
    }

    @Test
    void theViewSetsAsideAParentTheYearsMakeTheWrongWayRound(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp, "an aunt",
                fact("Ann Hale", "child-of", "Mary Hale"), fact("Ann Hale", "born-on", "1900"), fact("Mary Hale", "born-on", "1946"),
                fact("Ned Hale", "child-of", "Tom Hale"), fact("Ned Hale", "born-on", "1930"), fact("Tom Hale", "born-on", "1925"),
                fact("Ruth Hale", "child-of", "Tom Hale"), fact("Ruth Hale", "born-on", "1955"),
                fact("Ken Hale", "child-of", "Mary Hale"), fact("Ken Hale", "born-on", "1975"));
        Graph g = FamilyPeople.view(store);
        List<String> parents = FamilyKin.parents(g).entrySet().stream().flatMap(e -> e.getValue().stream().map(l -> g.node(e.getKey()).label() + " < " + g.node(l.other()).label())).sorted().toList();
        assertEquals(List.of("Ken Hale < Mary Hale", "Ruth Hale < Tom Hale"), parents, "the two the years allow");
        List<String> ann = FamilyDoubts.about(store, g, g.nodeIdOf("Ann Hale"));
        assertEquals(1, ann.size(), ann.toString());
        assertTrue(ann.get(0).contains("the years say this is the wrong way round (Mary Hale was born in 1946, Ann Hale in 1900)"), ann.get(0));
        assertTrue(FamilyDoubts.about(store, g, g.nodeIdOf("Ned Hale")).get(0).contains("the years say this cannot be, as a parent is at least 12 years older than a child"));
        List<FamilyChecks.Problem> checks = FamilyChecks.check(store);
        assertTrue(checks.stream().filter(p -> p.text().contains("Ann Hale") || p.text().contains("Ned Hale")).allMatch(p -> p.kind().equals("set aside")), "the check says only that they are set aside: " + FamilyChecks.render(checks));
        assertTrue(FamilyNameQuestions.about(store, g, Set.of(g.nodeIdOf("Ann Hale"), g.nodeIdOf("Mary Hale"), g.nodeIdOf("Ned Hale"), g.nodeIdOf("Tom Hale"))).isEmpty(), "no question");
        String page = FamilyWhoATextIsByTest.run(store, "names", "Mary Hale");
        assertTrue(page.contains(FamilyDoubts.HEADING) && page.contains("Ann Hale is a child of Mary Hale"), "still shown on the person's page: " + page);
        Graph core = Graph.build(store);
        assertEquals(4, core.edges().stream().filter(e -> e.predicate().equals("child-of") && !e.disputed()).count(), "the claims themselves stay as they were");
    }

    @Test
    void theViewSetsAsideAClaimItsOwnWordsSayTheOtherWayRound(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp, "Kimie Hale",
                said("Ruth Hale", "child-of", "Kimie Hale", "My parents, Ruth Hale and Tom Hale, met in Leeds in 1930."),
                said("Ned Hale", "child-of", "Kimie Hale", "My son Ned Hale was born in 1975."));
        Graph g = FamilyPeople.view(store);
        List<String> ruth = FamilyDoubts.about(store, g, g.nodeIdOf("Ruth Hale"));
        assertEquals(1, ruth.size(), ruth.toString());
        assertTrue(ruth.get(0).contains("the words say this is the wrong way round"), ruth.get(0));
        assertTrue(FamilyDoubts.about(store, g, g.nodeIdOf("Ned Hale")).isEmpty(), "my son: the words agree");
    }

    @Test
    void aReadKeepsAParentsBirthYearAnOlderWrongClaimContradicts(@TempDir Path tmp) throws Exception {
        // an older link note made Mary the parent of Ken, born 1865; the family's notes now give Mary's own birth year
        LibraryStore store = store(tmp, "the owner of this library", said("Mary Hale", "parent-of", "Ken Ellis", "Ken Ellis - my mother (Mary Hale)"), fact("Ken Ellis", "born-on", "1865"));
        Path notes = tmp.resolve("notes.txt");
        Files.writeString(notes, "my mother Mary Hale was born 11 aug 1948\n", StandardCharsets.UTF_8);
        GenealogyProfile.useReader(p -> p.contains("Answer with one word") ? "no" : """
                {"people": [{"name": "Mary Hale", "sex": "female"}], "facts": [
                 {"subject": "Mary Hale", "relation": "born-on", "object": "11 aug 1948", "date": "", "quote": "my mother Mary Hale was born 11 aug 1948"}]}""");
        try {
            String out = FamilyWhoATextIsByTest.run(store, "read", notes.toString());
            assertFalse(out.contains("was left out, because it cannot be true"), out);
            assertTrue(store.scanFindings().findings().stream().anyMatch(f -> f.triple() != null && f.triple().subject().equals("Mary Hale") && f.triple().predicate().equals("born-on")), "the year is filed");
        } finally { GenealogyProfile.useReader(null); }
        Graph g = FamilyPeople.view(store);
        assertTrue(FamilyKin.parents(g).isEmpty(), "the view sets the relation aside");
        assertTrue(FamilyDoubts.about(store, g, g.nodeIdOf("Ken Ellis")).get(0).contains("the years say this is the wrong way round"));
    }

    @Test
    void aClaimTheFamilyAcceptedStands(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp, "an aunt", fact("Ann Hale", "child-of", "Mary Hale"), fact("Ann Hale", "born-on", "1900"), fact("Mary Hale", "born-on", "1946"));
        Finding claim = store.scanFindings().findings().stream().filter(f -> f.triple() != null && f.triple().predicate().equals("child-of")).findFirst().orElseThrow();
        new Council(store).accept(claim.id());
        Graph g = FamilyPeople.view(store);
        assertEquals(1, FamilyKin.parents(g).size());
        assertTrue(FamilyDoubts.about(store, g, g.nodeIdOf("Ann Hale")).isEmpty());
    }
}
