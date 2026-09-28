package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.records.RecordSources;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A date is a range of years, and the checks ask whether any year in the ranges fits: "about", "before" and "between" no longer make a
 * true family impossible. The family's other dates bound a birth nobody wrote down, a record's age dates a birth, and a register's
 * "同年" gets its year from the date before it.
 */
class FamilyRangesTest {

    private static FamilyAccount.Fact fact(String s, String r, String o, String date) { return new FamilyAccount.Fact(s, r, o, date, "q"); }

    private static LibraryStore store(Path tmp, FamilyAccount.Fact... facts) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(facts), List.of()), "file:///notes.txt", "an aunt");
        return store;
    }

    @Test
    void aQualifiedDateIsImpossibleOnlyWhenNoYearInItFits(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp,
                fact("Tom Hale", "died-on", "after 1845", ""), fact("Ann Hale", "child-of", "Tom Hale", ""), fact("Ann Hale", "born-on", "1848", ""),
                fact("Kimie Hart", "born-on", "BEF 1850", ""), fact("Isamu Hart", "child-of", "Kimie Hart", ""), fact("Isamu Hart", "born-on", "1855", ""),
                fact("Mari Ellis", "born-on", "BET 1850 AND 1860", ""), fact("Mari Ellis", "born-in", "Dunedin", "BET 1850 AND 1860"), fact("Mari Ellis", "died-on", "1855", ""),
                fact("Haru Ellis", "born-on", "about 1850", ""), fact("Shoichi Ellis", "child-of", "Haru Ellis", ""), fact("Shoichi Ellis", "born-on", "1860", ""),
                fact("Genzaburo Morita", "born-on", "about 1850", ""), fact("Genzaburo Morrita", "born-on", "1853", ""));
        String all = FamilyChecks.render(FamilyChecks.check(store));
        assertFalse(all.contains("[impossible]"), "died after 1845 with a child in 1848, born before 1850 with a child in 1855, a birth between 1850 and 1860 and a death in 1855: all can be true. " + all);
        assertFalse(all.contains("two birth years"), "one range is read the same way from a born-on and from a born-in claim: " + all);
        assertTrue(all.contains("[unlikely] Haru Ellis (born about 1850) was about 10 when Shoichi Ellis was born in 1860."), "only the year it is written around breaks the rule: " + all);
        assertTrue(all.contains("[same-person?] Genzaburo Morita and Genzaburo Morrita"), "about 1850 and 1853 do not set two people apart: " + all);
        assertFalse(all.contains("[same-name]"), all);
        Finding died = store.scanFindings().findings().stream().filter(f -> f.triple().subject().equals("Tom Hale")).findFirst().orElseThrow();
        assertTrue(died.body().startsWith("Tom Hale died after 1845."), died.body());
    }

    @Test
    void aDateWithNoYearIsNamedAndTheLivingRuleUsesTheLastYearADateAllows(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        Path ged = tmp.resolve("tree.ged");
        Files.writeString(ged, "0 HEAD\n"
                + "0 @I1@ INDI\n1 NAME Tom /Hale/\n1 BIRT\n2 DATE 1850\n1 DEAT\n2 DATE 1885\n2 PLAC Dunedin\n2 AGE 42y\n"
                + "0 @I2@ INDI\n1 NAME Kimie /Hale/\n1 BIRT\n2 DATE ABT MAR\n1 DEAT\n2 DATE 1901\n"
                + "0 @I3@ INDI\n1 NAME Ann /Hart/\n1 BIRT\n2 DATE AFT 1900\n"
                + "0 @I4@ INDI\n1 NAME Mari /Hart/\n1 BIRT\n2 DATE BET 1910 AND 1930\n"
                + "0 @I5@ INDI\n1 NAME Haru /Hart/\n1 BIRT\n2 DATE ABT 1900\n0 TRLR\n", StandardCharsets.UTF_8);
        Gedcom.importFile(store, ged);
        Graph g = Graph.build(store);
        assertTrue(g.node(g.nodeIdOf("Ann Hart")).mayBeLiving(), "born after 1900 may be alive today");
        assertTrue(g.node(g.nodeIdOf("Mari Hart")).mayBeLiving(), "and so may somebody born between 1910 and 1930; the first year used to decide");
        assertFalse(g.node(g.nodeIdOf("Haru Hart")).mayBeLiving(), "about 1900 ends in 1905, more than a lifetime ago");
        Finding aged = store.scanFindings().findings().stream().filter(f -> f.triple().predicate().equals("aged")).findFirst().orElseThrow();
        assertEquals("42y", aged.triple().object(), "an age at an event is kept as the file writes it");
        String all = FamilyChecks.render(FamilyChecks.check(store));
        assertTrue(all.contains("[unreadable] Kimie Hale's birth is dated \"ABT MAR\", and the library finds no year in it"), "a date the checks cannot read used to be skipped without a word: " + all);
        assertTrue(all.contains("[unlikely] Tom Hale was aged 42y in 1885, so born between 1842 and 1843, but the birth is dated 1850."), all);
        String said = FamilyChecks.forPerson(store, FamilyChecks.check(store));
        assertTrue(said.contains("DATES THE LIBRARY COULD NOT READ (1)"), said);
        assertTrue(FamilyLife.render("Tom Hale", FamilyLife.of(store, "Tom Hale")).contains("was aged 42y (1885) (so born between 1842 and 1843)."), FamilyLife.render("Tom Hale", FamilyLife.of(store, "Tom Hale")));
    }

    @Test
    void everyDatedThingInALifeAndOneParentsChildren(@TempDir Path tmp) throws Exception {
        String all = FamilyChecks.render(FamilyChecks.check(store(tmp,
                fact("Tom Hale", "born-on", "1850", ""), fact("Tom Hale", "died-on", "1900", ""),
                fact("Tom Hale", "lived-in", "Dunedin", "1840"), fact("Tom Hale", "migrated-to", "Otago", "1905"),
                fact("Tom Hale", "life-event", "given a rank after his death", "1910"), fact("Tom Hale", "occupation", "surveyor", "1895"),
                fact("Tom Hale", "married-to", "Ann Hart", "1880"), fact("Mari Hale", "child-of", "Tom Hale", ""), fact("Mari Hale", "child-of", "Ann Hart", ""), fact("Mari Hale", "born-on", "1875", ""),
                fact("Isamu Morita", "parent-of", "Haru Morita", ""), fact("Haru Morita", "born-on", "1850", ""), fact("Isamu Morita", "parent-of", "Kimie Morita", ""), fact("Kimie Morita", "born-on", "1860", ""),
                fact("Isamu Morita", "parent-of", "Shoichi Morita", ""), fact("Shoichi Morita", "born-on", "1895", ""),
                fact("Ann Ellis", "parent-of", "Tom Ellis", ""), fact("Tom Ellis", "born-on", "1850", ""), fact("Ann Ellis", "parent-of", "Kimie Ellis", ""), fact("Kimie Ellis", "born-on", "1875", ""))));
        assertTrue(all.contains("[impossible] \"Tom Hale lived in Dunedin\" is dated 1840, before Tom Hale's birth in 1850."), all);
        assertTrue(all.contains("[impossible] \"Tom Hale moved to Otago\" is dated 1905, after Tom Hale's death in 1900."), all);
        assertTrue(all.contains("[unlikely] \"Tom Hale: given a rank after his death\" is dated 1910, after Tom Hale's death in 1900. An honour or a work can be dated after a death"), "a posthumous rank can be true: " + all);
        assertFalse(all.contains("surveyor\" is dated"), all);
        assertTrue(all.contains("[unlikely] Isamu Morita's children were born over 45 years, from Haru Morita (1850) to Shoichi Morita (1895).") && all.contains("genealogy split"), all);
        assertTrue(all.contains("[unlikely] Ann Ellis had no child between Tom Ellis (1850) and Kimie Ellis (1875), 25 years later."), all);
        assertTrue(all.contains("[note] Mari Hale was born in 1875, before the marriage of Tom Hale and Ann Hart in 1880."), all);
    }

    @Test
    void aBirthNobodyWroteDownIsBoundByTheFamilyAndAnEmptyWindowCannotBe(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp,
                fact("Genzaburo Takahashi", "born-on", "1820", ""), fact("Shoichi Takahashi", "child-of", "Genzaburo Takahashi", ""),
                fact("Shoichi Takahashi", "parent-of", "Mari Takahashi", ""), fact("Mari Takahashi", "born-on", "1870", ""),
                fact("Shoichi Takahashi", "married-to", "Haru Takahashi", "1860"),
                fact("Genzaburo Endo", "born-on", "1840", ""), fact("Isamu Endo", "child-of", "Genzaburo Endo", ""),
                fact("Isamu Endo", "parent-of", "Haru Endo", ""), fact("Haru Endo", "born-on", "1850", ""));
        Graph g = Graph.build(store);
        FamilyBounds.Window w = FamilyBounds.of(store).get(g.nodeIdOf("Shoichi Takahashi"));
        assertEquals("between 1832 and 1848", w.born(), "twelve after his father's birth, twelve before his marriage");
        assertEquals(1848, w.bornBefore().year());
        String all = FamilyChecks.render(FamilyChecks.check(store));
        assertTrue(all.contains("[impossible] Isamu Endo has no birth date, but the family's dates put the birth in 1852 or after (the parent Genzaburo Endo was born in 1840 at the earliest, and a parent is at least 12) and in 1838 or before (the child Haru Endo was born in 1850 at the latest"),
                "no pair of claims is wrong on its own, the generation between them is: " + all);
        assertFalse(all.contains("Shoichi Takahashi has no birth date"), all);
        String q = FamilyQuestions.around(store, "Shoichi Takahashi", 2, 2).stream().filter(a -> a.person().equals("Shoichi Takahashi")).findFirst().orElseThrow().question();
        assertTrue(q.contains("born between 1832 and 1848, as worked out from the family's other dates"), q);
        assertEquals(1832, RecordSources.lived(q)[0], "the record searches are held to the window");
        String life = FamilyLife.render("Shoichi Takahashi", FamilyLife.of(store, "Shoichi Takahashi"));
        assertTrue(life.contains("  1848  born between 1832 and 1848  [worked out from F-") && life.contains("not a claim]"), life);
        assertTrue(store.scanFindings().findings().stream().noneMatch(f -> f.triple().predicate().startsWith("born") && f.triple().subject().equals("Shoichi Takahashi")), "a window is never filed");
    }

    @Test
    void aQuestionSaysAboutAndTheSearchYearsStayWide(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp, fact("Haru Ellis", "born-on", "about 1850", ""), fact("Haru Ellis", "died-on", "1911", ""), fact("Tom Ellis", "child-of", "Haru Ellis", ""), fact("Tom Ellis", "born-on", "BEF 1880", ""));
        List<FamilyQuestions.Ask> asks = FamilyQuestions.around(store, "Haru Ellis", 2, 2);
        String haru = asks.stream().filter(a -> a.person().equals("Haru Ellis")).findFirst().orElseThrow().question();
        assertTrue(haru.contains("born about 1850") && haru.contains("Where was Haru Ellis born, and in which year exactly? The library has only about 1850."), haru);
        assertArrayEquals(new int[]{1845, 1911}, RecordSources.lived(haru), "about 1850 starts the search years at 1845");
        String tom = asks.stream().filter(a -> a.person().equals("Tom Ellis")).findFirst().orElseThrow().question();
        assertTrue(tom.contains("born 1880 or before"), tom);
        assertEquals(-FamilyDate.OPEN, RecordSources.lived(tom)[0], "a birth before 1880 drops no collection for ending too early");
        assertEquals("1880 or before", FamilyDate.bornOf("Tom Ellis (born 1880 or before in a village by the sea): who were the parents?").phrase());
        assertEquals("about 1850", FamilyDate.bornOf("Haru Ellis (born about 1850 in a village by the sea)").phrase(), "a place is not read as part of the date");
        assertEquals("c. 1850", FamilyTree.brief("about 1850"));
        LibraryStore young = store(tmp.resolve("young"), fact("Mari Hale", "born-on", "2000", ""), fact("Tom Hale", "child-of", "Mari Hale", ""));
        assertTrue(FamilyQuestions.around(young, "Mari Hale", 2, 2, true).stream().anyMatch(a -> a.person().equals("Tom Hale") && a.living()),
                "a child whose mother was born in 2000 is a living person like any other, asked about with the living");
        assertTrue(FamilyQuestions.around(young, "Mari Hale", 2, 2, false).stream().noneMatch(a -> a.person().equals("Tom Hale")), "and left out with --skip-living");
    }

    @Test
    void aRegisterDateWrittenAgainstTheOneBeforeItGetsItsYear(@TempDir Path tmp) throws Exception {
        String entry = "明治四十年十二月五日髙橋源三郎ト婚姻届出 翌年三月一日長男正一出生";
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        FamilyAccount.Outcome o = FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(
                new FamilyAccount.Fact("髙橋源三郎", "married-to", "髙橋ハル", "明治四十年十二月五日", entry),
                new FamilyAccount.Fact("髙橋正一", "born-on", "翌年三月一日", "", entry),
                new FamilyAccount.Fact("髙橋正一", "child-of", "髙橋源三郎", "", entry),
                new FamilyAccount.Fact("髙橋まり", "born-on", "同年", "", "まり同年生")), List.of()), "file:///register.txt", "the register");
        assertEquals(4, o.claims(), "a 同年 with no date before it names no year and is left out, as before (the fourth claim is 正一's sex, from 長男)");
        assertTrue(store.scanFindings().findings().stream().noneMatch(f -> f.triple().subject().equals("髙橋まり")));
        Finding born = store.scanFindings().findings().stream().filter(f -> f.triple().predicate().equals("born-on")).findFirst().orElseThrow();
        assertEquals("翌年三月一日 (1908, the year worked out from the entry's earlier date)", born.triple().object());
        assertTrue(born.body().contains("the year 1908 was worked out from \"明治四十年十二月五日\", the date written before it."), born.body());
        List<FamilyLife.Line> life = FamilyLife.of(store, "髙橋正一");
        assertEquals(1908, life.stream().filter(l -> l.predicate().equals("born-on")).findFirst().orElseThrow().year());
        assertEquals(FamilyDate.parse("1908").year(), FamilyDate.parse(born.triple().object()).year());
        assertTrue(FamilyDate.parse(born.triple().object()).exact(), "the words about where the year came from are not read as before or about");
    }

    @Test
    void aReadKeepsADateTheLibrarysOwnClaimsContradictAndTheViewWeighsIt(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp, fact("Tom Hart", "born-on", "1855", ""), fact("Tom Hart", "child-of", "Isamu Hart", ""), fact("Kimie Hart", "born-on", "about 1850", ""));
        FamilyAccount.Outcome o = FamilyAccount.file(store, new FamilyAccount.Read(List.of(), List.of(fact("Isamu Hart", "born-on", "before 1860", "")), List.of()), "file:///b.txt", "a cousin");
        assertEquals(1, o.claims(), "born before 1860 may be 1840: " + o.dropped());
        FamilyAccount.Outcome wrong = FamilyAccount.file(store, new FamilyAccount.Read(List.of(), List.of(fact("Isamu Hart", "born-on", "about 1870", "")), List.of()), "file:///c.txt", "a cousin");
        assertEquals(1, wrong.claims(), "a year the library's own claims contradict is filed all the same; the view weighs it: " + wrong.dropped());
        Graph g = FamilyPeople.view(store);
        assertTrue(FamilyDoubts.about(store, g, g.nodeIdOf("Isamu Hart")).isEmpty(), "born before 1860 still allows the relation, so the view keeps it and the checks show the two years");
        assertTrue(FamilyChecks.render(FamilyChecks.check(store)).contains("Isamu Hart has two birth years"), FamilyChecks.render(FamilyChecks.check(store)));
        assertEquals(0, FamilyAccount.file(store, new FamilyAccount.Read(List.of(), List.of(fact("Tom Hart", "born-on", "BET 1850 AND 1860", "")), List.of()), "file:///d.txt", "a cousin").claims(), "a range around the year held says no more");
        assertEquals(1, FamilyAccount.file(store, new FamilyAccount.Read(List.of(), List.of(fact("Kimie Hart", "born-on", "1850", "")), List.of()), "file:///e.txt", "a cousin").claims(), "a year says more than an about year, and is filed beside it");
    }
}
