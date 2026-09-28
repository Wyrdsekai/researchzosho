package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** A life is more than a birth and a death: what a person did stands between them, in order of date, each line a claim. */
class FamilyLifeTest {

    private static FamilyAccount.Fact fact(String s, String r, String o, String date) { return new FamilyAccount.Fact(s, r, o, date, "q"); }

    @Test
    void thePersonsDatedClaimsInOrderAndTheirRelativesLeftToTheTree(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        FamilyAccount.file(store, new FamilyAccount.Read(List.of(), List.of(
                fact("森田勇", "died-on", "昭和20年", ""), fact("森田勇", "life-event", "陸軍中将に昇進", "大正3年"), fact("森田勇", "born-in", "津", "嘉永5年"),
                fact("森田勇", "life-event", "founded a trading company", "1898"), fact("森田勇", "occupation", "軍人", ""), fact("森田一郎", "child-of", "森田勇", ""),
                fact("森田一郎", "life-event", "graduated", "1920")), List.of()), "https://ja.example.org/morita", "a page");
        List<FamilyLife.Line> life = FamilyLife.of(store, "森田勇");
        assertEquals(List.of(1852, 1898, 1914, 1945), life.stream().map(FamilyLife.Line::year).filter(y -> y != null).toList(), life.toString());
        assertEquals(5, life.size(), "his son's graduation and the kinship claim are not lines of his life: " + life);
        String shown = FamilyLife.render("森田勇", life);
        assertTrue(shown.contains("1914  陸軍中将に昇進 (大正3年 (1914)).  [draft, F-") && shown.contains("1852  was born in 津") && shown.contains("https://ja.example.org/morita"), shown);
        assertTrue(shown.indexOf("without a date") > shown.indexOf("1945"), shown);
        assertNull(FamilyLife.of(store, "nobody of this name"));
        assertEquals("event", Graph.build(store).node(Graph.build(store).nodeIdOf("陸軍中将に昇進")).kind());
    }

    @Test
    void theHolesInALifeWhatItRestsOnAndTwoLivesSideBySide(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        // one man's dates from a family tree site (a clue), his death from a newspaper scan (a record)
        FamilyAccount.file(store, new FamilyAccount.Read(List.of(), List.of(fact("森田勇", "born-in", "津", "1852"), fact("森田勇", "life-event", "founded a trading company", "1898")), List.of()), "https://www.geni.com/people/x/1", "a tree site");
        FamilyAccount.file(store, new FamilyAccount.Read(List.of(), List.of(fact("森田勇", "died-on", "1945", "")), List.of()), "https://chroniclingamerica.loc.gov/lccn/sn1/1945-03-02/ed-1/seq-3/", "a newspaper");
        FamilyAccount.file(store, new FamilyAccount.Read(List.of(), List.of(fact("森田勇 (born 1870)", "born-in", "京都", "1870"), fact("森田勇 (born 1870)", "occupation", "教師", "1900")), List.of()), "file:///notes.txt", "an aunt");
        List<FamilyLife.Line> life = FamilyLife.of(store, "森田勇");
        String shown = FamilyLife.render("森田勇", life);
        assertTrue(shown.contains("1852  was born in 津 (1852).  [draft, F-") && shown.contains("(a clue only)  https://www.geni.com"), shown);
        assertTrue(shown.contains("1945  died on 1945") || shown.contains("1945  "), shown);
        assertTrue(shown.contains("(a record)  https://chroniclingamerica.loc.gov"), "a newspaper scan is a record: " + shown);
        assertTrue(shown.contains("— nothing between 1898 and 1945 (47 years)"), shown);
        assertTrue(shown.contains("Nothing is written about: a marriage."), "a founding is work: " + shown);
        assertTrue(shown.contains("2 lines rest on a clue only"), shown);

        String both = FamilyLife.beside("森田勇", life, "森田勇 (born 1870)", FamilyLife.of(store, "森田勇 (born 1870)"));
        assertTrue(both.contains("1852    was born in 津 (1852). (clue)") && both.contains("1870") && both.contains("was born in 京都 (1870). (clue)") && both.contains("1945"), both);
        assertTrue(both.indexOf("\n1852") < both.indexOf("\n1870") && both.indexOf("\n1870") < both.indexOf("\n1898"), "one column of years for both: " + both);
    }

    @Test
    void aWifesMarriageIsHersAndTheRelativesEventsStandInsideTheLife(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(
                fact("Tom Hale", "born-on", "1850", ""), fact("Tom Hale", "married-to", "Ann Hart", "1880"), fact("Tom Hale", "died-on", "1920", ""),
                fact("Ann Hart", "born-on", "1855", ""),
                fact("Tom Hale", "child-of", "Genzaburo Hale", ""), fact("Genzaburo Hale", "died-on", "1870", ""), fact("Genzaburo Hale", "sex", "male", ""),
                fact("Isamu Hale", "child-of", "Genzaburo Hale", ""), fact("Isamu Hale", "born-on", "1840", ""),
                fact("Mari Hale", "child-of", "Tom Hale", ""), fact("Mari Hale", "born-on", "1882", ""), fact("Mari Hale", "born-in", "Leeds", "1882"), fact("Mari Hale", "sex", "female", ""),
                fact("Kimie Hale", "child-of", "Tom Hale", ""), fact("Kimie Hale", "born-on", "1885", "")), List.of()), "file:///notes.txt", "an aunt");

        String ann = FamilyLife.render("Ann Hart", FamilyLife.of(store, "Ann Hart"));
        assertTrue(ann.contains("1880  Tom Hale was married to Ann Hart"), "the claim was written about her husband, and it is her marriage too: " + ann);
        assertFalse(ann.contains("Nothing is written about: a marriage") || ann.contains("a marriage."), ann);

        List<FamilyLife.Line> life = FamilyLife.of(store, "Tom Hale");
        String tom = FamilyLife.render("Tom Hale", life);
        assertTrue(tom.contains("1870  The father Genzaburo Hale died in 1870") && tom.contains(", a relative's event]"), tom);
        assertTrue(tom.contains("1882  The daughter Mari Hale was born in Leeds"), "one line for her birth, the one with the place: " + tom);
        assertEquals(1, life.stream().filter(l -> l.predicate().equals(FamilyLife.RELATIVE) && l.text().contains("Mari Hale")).count(), tom);
        assertFalse(tom.contains("Isamu Hale"), "a brother born before Tom is outside his life: " + tom);
        assertTrue(tom.contains("1885  The child Kimie Hale was born in 1885"), "every relative's event inside his life is there: " + tom);
        assertTrue(tom.contains("— nothing between 1850 and 1880 (30 years), except the relatives' events below"), tom);
        assertTrue(tom.indexOf("nothing between 1850 and 1880") < tom.indexOf("1870  The father"), tom);
        assertTrue(tom.contains("— nothing between 1880 and 1920 (40 years), except the relatives' events below"), tom);
        assertTrue(FamilyLife.missing(life).contains("their work"), "a relative's event counts toward nothing of the person's own");
        String both = FamilyLife.beside("Tom Hale", life, "Ann Hart", FamilyLife.of(store, "Ann Hart"));
        assertFalse(both.contains("Genzaburo"), "side by side stays the two people's own claims: " + both);
    }

    private static Path ged(Path tmp, String body) throws Exception {
        Path f = tmp.resolve("t.ged");
        Files.writeString(f, "0 HEAD\n1 CHAR UTF-8\n" + body + "0 TRLR\n", StandardCharsets.UTF_8);
        return f;
    }

    @Test
    void aPersonWithNoDateOfTheirOwnHasALifeAndADeathWithItsPlaceIsDated(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Gedcom.importFile(store, ged(tmp, "0 @I1@ INDI\n1 NAME Tom /Ellis/\n1 SEX M\n1 OCCU miner\n1 FAMS @F1@\n0 @I2@ INDI\n1 NAME Ann /Hart/\n1 SEX F\n1 FAMS @F1@\n"
                + "0 @I3@ INDI\n1 NAME Mary /Ellis/\n1 FAMC @F1@\n0 @F1@ FAM\n1 HUSB @I1@\n1 WIFE @I2@\n1 CHIL @I3@\n"
                + "0 @I4@ INDI\n1 NAME John /Smith/\n1 BIRT\n2 PLAC Leeds\n1 DEAT\n2 DATE 1920\n2 PLAC York\n"));
        for (String who : List.of("Tom Ellis", "Ann Hart", "Mary Ellis")) {
            List<FamilyLife.Line> lines = FamilyLife.of(store, who);
            assertNotNull(lines, who);
            assertTrue(FamilyLife.render(who, lines).contains(who), who);
        }
        List<FamilyLife.Line> smith = FamilyLife.of(store, "John Smith");
        assertTrue(smith.stream().anyMatch(l -> l.predicate().equals("died-in") && Integer.valueOf(1920).equals(l.year())), smith.toString());
        assertFalse(FamilyLife.missing(smith).contains("the date of their death"), FamilyLife.missing(smith).toString());
    }

    @Test
    void aDisputedRelationIsNoRelativeInALifeOrARelationAndAnOwnParentBoundsNothing(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(fact("Tom Ellis", "born-on", "1900", ""), fact("Tom Ellis", "died-on", "1970", ""),
                fact("Tom Ellis", "child-of", "Genzo Hale", ""), fact("Kimie Hale", "child-of", "Genzo Hale", ""), fact("Genzo Hale", "born-on", "1860", ""), fact("Genzo Hale", "died-on", "1930", "")), List.of()),
                "file:///family/tree.txt", "a tree");
        Finding kin = store.scanFindings().findings().stream().filter(f -> f.title().contains("Tom Ellis") && f.triple().object().equals("Genzo Hale")).findFirst().orElseThrow();
        assertTrue(FamilyLife.of(store, "Tom Ellis").stream().anyMatch(l -> l.text().contains("Genzo")), "before the dispute, the father's events are in the life");
        new Council(store).dispute(kin.id(), "not his father");
        assertTrue(FamilyLife.of(store, "Tom Ellis").stream().noneMatch(l -> l.text().contains("Genzo")), FamilyLife.of(store, "Tom Ellis").toString());
        Graph g = Graph.build(store);
        String related = FamilyKin.said(g, "Kimie Hale", "Tom Ellis");
        assertTrue(related == null || related.isEmpty(), "no relation through a claim the family disputed: " + related);
        List<FamilyQuestions.Ask> asks = FamilyQuestions.around(store, "Tom Ellis", 6, 3, false);
        assertTrue(asks.stream().noneMatch(a -> a.question().contains("Genzo")), asks.toString());

        // a person recorded as their own parent: the arithmetic bounds nothing through that claim
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(fact("John Ellis", "parent-of", "John Ellis", ""), fact("John Ellis", "parent-of", "Ruth Ellis", ""), fact("Ruth Ellis", "born-on", "1900", "")), List.of()),
                "file:///family/old-import.txt", "a tree");
        FamilyBounds.Window w = FamilyBounds.of(store).get(Graph.build(store).nodeIdOf("John Ellis"));
        assertNotNull(w);
        assertEquals("in 1888 or before", w.born(), "a parent of a child born in 1900, and no more: " + w.born());
    }
}
