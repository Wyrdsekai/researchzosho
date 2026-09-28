package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A given name alone and a full name with that given part, or a name in Latin letters and one in characters, are one person in a read only
 * when the read itself gives them as one person's: the text writes one in the brackets right after the other ("Morita Kenji (森田健二)"), or
 * the model gives the one among the other's other spellings or names. Otherwise they are two entries, and the family is asked whether they
 * are one person. Relatives the read relates, directly or through other relatives, are never joined, and nor are birth years that clash.
 */
class FamilyGivenNameAloneTest {

    private static FamilyAccount.Fact fact(String s, String r, String o, String date, String quote) { return new FamilyAccount.Fact(s, r, o, date, quote); }

    private static FamilyAccount.Person person(String name, String family, String given) { return new FamilyAccount.Person(name, "", List.of(), family, given); }

    private static boolean has(LibraryStore store, String subject, String predicate) {
        return store.scanFindings().findings().stream().anyMatch(f -> f.triple() != null && f.triple().subject().equals(subject) && f.triple().predicate().equals(predicate));
    }

    /** Whether the family is asked whether two entries are one person. */
    private static boolean asked(LibraryStore store, String a, String b) throws Exception {
        Graph g = FamilyPeople.view(store);
        String x = g.nodeIdOf(a), y = g.nodeIdOf(b);
        return FamilyNameQuestions.open(store).stream().anyMatch(q -> q.kind().equals("one-person") && q.people().contains(x) && q.people().contains(y));
    }

    @Test
    void kenjiBesideTheOneMoritaKenjiIsAskedAboutUnlessTheModelGivesThemAsOnePersons(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameHistoryTest.store(tmp);
        FamilyAccount.file(store, new FamilyAccount.Read(List.of(person("Morita Kenji", "Morita", "Kenji"), person("Morita Haru", "Morita", "Haru")),
                List.of(fact("Morita Kenji", "married-to", "Morita Haru", "1932", "In 1932 Morita Kenji married Haru."),
                        fact("Kenji", "occupation", "shopkeeper", "", "Kenji ran the family shop for thirty years.")), List.of()), "file:///family/book.txt", "an aunt");
        Graph g = FamilyPeople.unlinkedView(store);
        assertNotNull(g.node(g.nodeIdOf("Kenji")), "nothing in the read gives Kenji as Morita Kenji's: the read files an entry of its own");
        assertNotEquals(g.nodeIdOf("Kenji"), g.nodeIdOf("Morita Kenji"));
        assertTrue(has(store, "Kenji", "occupation"));
        // the text names one Kenji in full: genealogy's view links the given name alone to him ({@link FamilyLinks}), and nobody is asked
        Graph linked = FamilyPeople.view(store);
        Finding shop = store.scanFindings().findings().stream().filter(f -> f.triple() != null && f.triple().subject().equals("Kenji")).findFirst().orElseThrow();
        assertEquals(linked.nodeIdOf("Morita Kenji"), linked.nodeOf(shop, true));
        assertFalse(asked(store, "Kenji", "Morita Kenji"), "linked by the evidence, not asked");
        // the model gives the given name among his other spellings: one person
        LibraryStore one = FamilyNameHistoryTest.store(tmp.resolve("one"));
        FamilyAccount.file(one, new FamilyAccount.Read(List.of(new FamilyAccount.Person("Morita Kenji", "", List.of("Kenji"), "Morita", "Kenji"), person("Morita Haru", "Morita", "Haru")),
                List.of(fact("Morita Kenji", "married-to", "Morita Haru", "1932", "In 1932 Morita Kenji married Haru."),
                        fact("Kenji", "occupation", "shopkeeper", "", "Kenji ran the family shop for thirty years.")), List.of()), "file:///family/book.txt", "an aunt");
        Graph og = FamilyPeople.view(one);
        assertNull(og.node(og.nodeIdOf("Kenji")), "no entry for the given name alone");
        assertTrue(has(one, "Morita Kenji", "occupation"), "the shop is his");
        assertFalse(og.node(og.nodeIdOf("Morita Kenji")).aliases().contains("Kenji"), "a given name alone is no other name of his");
    }

    @Test
    void 健二Beside森田健二IsAskedAboutUnlessTheTextWritesThemTogether(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameHistoryTest.store(tmp);
        String q = "健二は森田家の婿養子となり、森田健二と名乗った。";
        FamilyAccount.file(store, new FamilyAccount.Read(List.of(), List.of(fact("森田健二", "member-of", "森田家", "1932", q), fact("健二", "born-in", "広島", "1905", "健二は1905年に広島で生まれた。")),
                List.of(), List.of(), List.of(), List.of(new FamilyAccount.FamilyRead("森田", "森田家", "", q))), "file:///family/book.txt", "an aunt");
        Graph g = FamilyPeople.unlinkedView(store);
        assertNotNull(g.node(g.nodeIdOf("健二")), "the read files two entries");
        // the text names one 健二 in full, 森田健二: genealogy's view links the given name alone to him, and nobody is asked
        Finding born0 = store.scanFindings().findings().stream().filter(f -> f.triple() != null && f.triple().subject().equals("健二")).findFirst().orElseThrow();
        assertEquals(FamilyPeople.view(store).nodeIdOf("森田健二"), FamilyPeople.view(store).nodeOf(born0, true));
        assertFalse(asked(store, "健二", "森田健二"), "linked by the evidence, not asked");
        // the text writes the whole name in the brackets after the given name: one person
        LibraryStore one = FamilyNameHistoryTest.store(tmp.resolve("one"));
        String born = "健二（森田健二）は1905年に広島で生まれた。";
        FamilyAccount.file(one, new FamilyAccount.Read(List.of(), List.of(fact("森田健二", "member-of", "森田家", "1932", q), fact("健二", "born-in", "広島", "1905", born)),
                List.of(), List.of(), List.of(), List.of(new FamilyAccount.FamilyRead("森田", "森田家", "", q))), "file:///family/book.txt", "an aunt");
        Graph og = FamilyPeople.view(one);
        assertNull(og.node(og.nodeIdOf("健二")));
        assertTrue(has(one, "森田健二", "born-in"), "the parts come from the family the read names: 森田 is a family name, so 健二 is his given name");
    }

    @Test
    void twoKenjisJoinNothingAndNeitherDoClashingYears(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameHistoryTest.store(tmp.resolve("two"));
        FamilyAccount.file(store, new FamilyAccount.Read(List.of(person("Morita Kenji", "Morita", "Kenji"), person("Endo Kenji", "Endo", "Kenji")),
                List.of(fact("Morita Kenji", "born-in", "Kure", "1905", "Morita Kenji was born in Kure in 1905."), fact("Endo Kenji", "born-in", "Hiroshima", "1911", "Endo Kenji was born in Hiroshima in 1911."),
                        fact("Kenji", "occupation", "shopkeeper", "", "Kenji ran the family shop.")), List.of()), "file:///family/book.txt", "an aunt");
        assertTrue(has(store, "Kenji", "occupation"), "which Kenji is not for the library to say");
        LibraryStore apart = FamilyNameHistoryTest.store(tmp.resolve("years"));
        FamilyAccount.file(apart, new FamilyAccount.Read(List.of(person("Morita Kenji", "Morita", "Kenji")),
                List.of(fact("Morita Kenji", "born-in", "Kure", "1905", "Morita Kenji was born in Kure in 1905."), fact("Kenji", "born-in", "Kure", "1850", "Kenji was born in Kure in 1850.")), List.of()), "file:///family/book.txt", "an aunt");
        assertTrue(has(apart, "Kenji", "born-in"), "born fifty-five years apart: two people");
        Graph g = FamilyPeople.view(apart);
        assertNotEquals(g.nodeIdOf("Kenji"), g.nodeIdOf("Morita Kenji"));
    }

    @Test
    void aFullNameWhosePartsNothingGivesJoinsNothing(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameHistoryTest.store(tmp);
        FamilyAccount.file(store, new FamilyAccount.Read(List.of(), List.of(fact("Ruth Ellis", "born-in", "York", "1850", "Ruth Ellis was born in York in 1850."),
                fact("Ruth", "occupation", "teacher", "", "Ruth taught at the school.")), List.of()), "file:///family/notes.txt", "an aunt");
        assertTrue(has(store, "Ruth", "occupation"), "which word of Ruth Ellis is the given name, nothing here says");
    }

    @Test
    void aGivenNameAloneThatAFactOfTheReadRelatesToTheFullNameIsAnotherPerson(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameHistoryTest.store(tmp);
        String q = "Kenji's grandson Morita Kenji was born in Kure in 1950.";
        FamilyAccount.file(store, new FamilyAccount.Read(List.of(person("Kenji", "", "Kenji"), person("Morita Kenji", "Morita", "Kenji")),
                List.of(fact("Morita Kenji", "born-in", "Kure", "1950", q), fact("Morita Kenji", "relative-of", "Kenji", "", q)), List.of()), "file:///family/book.txt", "an aunt");
        Graph g = FamilyPeople.view(store);
        assertNotNull(g.node(g.nodeIdOf("Kenji")), "the grandfather is not his grandson");
        assertNotEquals(g.nodeIdOf("Kenji"), g.nodeIdOf("Morita Kenji"));
        assertTrue(store.scanFindings().findings().stream().noneMatch(f -> f.triple() != null && f.triple().subject().equals(f.triple().object())), "nobody is their own relative");
        LibraryStore jr = FamilyNameHistoryTest.store(tmp.resolve("jr"));
        FamilyAccount.file(jr, new FamilyAccount.Read(List.of(person("John", "", "John"), person("John Hale", "Hale", "John")),
                List.of(fact("John Hale", "child-of", "John", "", "John's son John Hale was born in 1850."), fact("John Hale", "born-on", "1850", "", "John's son John Hale was born in 1850.")), List.of()), "file:///family/book.txt", "an aunt");
        assertTrue(jr.scanFindings().findings().stream().anyMatch(f -> f.triple() != null && f.triple().subject().equals("John Hale") && f.triple().predicate().equals("child-of") && f.triple().object().equals("John")),
                "the father named after nobody but himself stays");
    }

    @Test
    void aNamesakeInOtherLettersThatTheReadRelatesIsNotJoined(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameHistoryTest.store(tmp);
        String a = "森田健二（もりた けんじ）was born in 1850 and founded the shop.", b = "His grandson Morita Kenji, named after him, was born in 1950.";
        FamilyAccount.file(store, new FamilyAccount.Read(List.of(new FamilyAccount.Person("森田健二", "もりた けんじ", List.of(), "森田", "健二"), person("Morita Kenji", "Morita", "Kenji")),
                List.of(fact("森田健二", "born-on", "1850", "", a), fact("Morita Kenji", "born-on", "1950", "", b), fact("Morita Kenji", "relative-of", "森田健二", "", b)), List.of()), "file:///family/book.txt", "an aunt");
        Graph g = FamilyPeople.view(store);
        assertNotEquals(g.nodeIdOf("森田健二"), g.nodeIdOf("Morita Kenji"), "a grandson named after his grandfather is his grandson");
        assertTrue(store.scanFindings().findings().stream().noneMatch(f -> f.triple() != null && f.triple().subject().equals(f.triple().object())), "nobody is their own relative");
        // with nothing that relates them and no years apart, the reading joins the two ways of writing one man
        LibraryStore one = FamilyNameHistoryTest.store(tmp.resolve("one"));
        FamilyAccount.file(one, new FamilyAccount.Read(List.of(new FamilyAccount.Person("森田健二", "もりた けんじ", List.of(), "森田", "健二"), person("Morita Kenji", "Morita", "Kenji")),
                List.of(fact("森田健二", "born-on", "1905", "", "森田健二（もりた けんじ）は1905年生まれ。"), fact("Morita Kenji", "occupation", "shopkeeper", "", "Morita Kenji ran the shop.")), List.of()), "file:///family/book.txt", "an aunt");
        Graph og = FamilyPeople.view(one);
        assertNotEquals(og.nodeIdOf("森田健二"), og.nodeIdOf("Morita Kenji"), "a spelling of the reading alone joins nobody");
        assertTrue(asked(one, "森田健二", "Morita Kenji"), "the family is asked, with the reading the text gives");
        // the text writes the two together, one in the brackets after the other: one person, under the characters
        LibraryStore paired = FamilyNameHistoryTest.store(tmp.resolve("paired"));
        FamilyAccount.file(paired, new FamilyAccount.Read(List.of(new FamilyAccount.Person("森田健二", "もりた けんじ", List.of(), "森田", "健二"), person("Morita Kenji", "Morita", "Kenji")),
                List.of(fact("森田健二", "born-on", "1905", "", "森田健二（もりた けんじ）は1905年生まれ。"), fact("Morita Kenji", "occupation", "shopkeeper", "", "Morita Kenji (森田健二) ran the shop.")), List.of()), "file:///family/book.txt", "an aunt");
        Graph pg = FamilyPeople.view(paired);
        assertEquals(pg.nodeIdOf("森田健二"), pg.nodeIdOf("Morita Kenji"));
        assertTrue(has(paired, "森田健二", "occupation"));
    }

    @Test
    void aGrandsonNamedAfterHisGrandfatherAndRelatedThroughHisFatherIsNotJoinedAcrossScripts(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameHistoryTest.store(tmp);
        String grandson = "Shōichi's son Morita Kenji, named after his grandfather, ran it from 1950.";
        FamilyAccount.file(store, new FamilyAccount.Read(List.of(new FamilyAccount.Person("森田健二", "もりた けんじ", List.of(), "森田", "健二"),
                person("Morita Shōichi", "Morita", "Shōichi"), person("Morita Kenji", "Morita", "Kenji")),
                List.of(fact("森田健二", "born-on", "1850", "", "森田健二（もりた けんじ）was born in 1850 and founded the shop."),
                        fact("Morita Shōichi", "child-of", "森田健二", "", "His son Morita Shōichi ran it after him."),
                        fact("Morita Kenji", "child-of", "Morita Shōichi", "", grandson)), List.of()), "file:///family/book.txt", "an aunt");
        Graph g = FamilyPeople.view(store);
        assertNotEquals(g.nodeIdOf("森田健二"), g.nodeIdOf("Morita Kenji"), "the grandson is not his grandfather");
        assertTrue(store.scanFindings().findings().stream().anyMatch(f -> f.triple() != null && f.triple().subject().equals("Morita Kenji") && f.triple().predicate().equals("child-of") && f.triple().object().equals("Morita Shōichi")),
                "the grandson's father is kept: " + store.scanFindings().findings().stream().map(Finding::triple).toList());
    }

    @Test
    void aGrandfatherWrittenByHisGivenNameAloneIsNotJoinedIntoHisGrandsonThroughTheFather(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameHistoryTest.store(tmp);
        FamilyAccount.Outcome o = FamilyAccount.file(store, new FamilyAccount.Read(List.of(person("John", "", "John"), person("Tom Hale", "Hale", "Tom"), person("John Hale", "Hale", "John")),
                List.of(fact("Tom Hale", "child-of", "John", "", "John's son Tom Hale kept the inn."),
                        fact("John Hale", "child-of", "Tom Hale", "", "Tom's son John Hale was born in 1880."),
                        fact("John Hale", "born-on", "1880", "", "Tom's son John Hale was born in 1880.")), List.of()), "file:///family/letter.txt", "an aunt");
        assertTrue(o.dropped().stream().noneMatch(d -> d.contains("the other way round")), "nothing is dropped as the other way round: " + o.dropped());
        assertTrue(store.scanFindings().findings().stream().anyMatch(f -> f.triple() != null && f.triple().subject().equals("Tom Hale") && f.triple().predicate().equals("child-of") && f.triple().object().equals("John")),
                "Tom Hale's father is John, not his own son John Hale: " + store.scanFindings().findings().stream().map(Finding::triple).toList());
        assertTrue(has(store, "John Hale", "child-of"), "and the grandson's father is kept");
        // two facts of one text that say it both ways round: the text is named, not the library
        LibraryStore both = FamilyNameHistoryTest.store(tmp.resolve("both"));
        FamilyAccount.Outcome b = FamilyAccount.file(both, new FamilyAccount.Read(List.of(person("Tom Hale", "Hale", "Tom"), person("Ann Hale", "Hale", "Ann")),
                List.of(fact("Tom Hale", "child-of", "Ann Hale", "", "Tom Hale was the son of Ann Hale."), fact("Ann Hale", "child-of", "Tom Hale", "", "Ann Hale was the daughter of Tom Hale.")), List.of()),
                "file:///family/letter.txt", "an aunt");
        assertTrue(b.dropped().stream().anyMatch(d -> d.contains("because this text also says it the other way round (Ann Hale as the parent of Tom Hale)")), b.dropped().toString());
    }
}
