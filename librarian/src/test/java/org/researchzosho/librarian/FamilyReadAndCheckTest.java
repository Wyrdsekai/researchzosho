package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The family reader and the check on what they give each other: an other name that is somebody else's, a picture that backs a fact
 * already held, two trees that agree on one page, two people with one family-tree id, and the events that come after a death.
 */
class FamilyReadAndCheckTest {

    private static LibraryStore store(Path tmp) throws Exception { LibraryStore s = new LibraryStore(tmp.resolve("lib")); s.init(); return s; }

    private static FamilyAccount.Fact fact(String s, String r, String o, String date, String quote) { return new FamilyAccount.Fact(s, r, o, date, quote); }

    private static Path ged(Path tmp, String name, String body) throws Exception {
        Path f = tmp.resolve(name);
        Files.writeString(f, "0 HEAD\n1 CHAR UTF-8\n" + body + "0 TRLR\n", StandardCharsets.UTF_8);
        return f;
    }

    @Test
    void anOtherNameThatIsSomebodyElsesDoesNotJoinTheTwo(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        // the mother, from an earlier read
        FamilyAccount.file(store, new FamilyAccount.Read(List.of(), List.of(fact("Kimie Hart", "born-in", "Leeds", "1850", "Kimie Hart was born in Leeds in 1850")), List.of()), "file:///family/notes.txt", "an aunt");
        // the daughter, named for her mother, from a tree site that gives her maiden name as another name; the mother is in the same read
        FamilyAccount.Read geni = new FamilyAccount.Read(List.of(new FamilyAccount.Person("Kimie Ellis", "", List.of("Kimie Hart"))),
                List.of(fact("Kimie Ellis", "born-in", "York", "1875", "born 1875 in York"), fact("Kimie Ellis", "child-of", "Kimie Hart", "", "daughter of Kimie Hart")), List.of());
        FamilyAccount.Outcome o = FamilyAccount.file(store, geni, "https://www.geni.com/people/Kimie-Ellis/1", "Geni");
        Graph g = Graph.build(store);
        assertEquals("kimie hart", g.nodeIdOf("Kimie Hart"), "the mother is still herself");
        assertTrue(g.edges().stream().noneMatch(e -> e.from().equals(e.to())), "nobody is their own parent: " + g.edges());
        assertTrue(g.edges().stream().anyMatch(e -> e.predicate().equals("born-in") && e.from().equals("kimie hart") && g.node(e.to()).label().equals("Leeds")), "her birth is still hers");
        assertTrue(o.dropped().stream().anyMatch(d -> d.contains("\"Kimie Hart\" was not kept as another name of Kimie Ellis") && d.contains("researchzosho graph merge \"Kimie Hart\" \"Kimie Ellis\"")), o.dropped().toString());

        // the same when the mother is new in this read
        LibraryStore fresh = store(tmp.resolve("fresh"));
        FamilyAccount.file(fresh, new FamilyAccount.Read(List.of(new FamilyAccount.Person("Kimie Ellis", "", List.of("Kimie Hart"))),
                List.of(fact("Kimie Hart", "born-in", "Leeds", "1850", "Kimie Hart, born 1850 in Leeds"), fact("Kimie Ellis", "child-of", "Kimie Hart", "", "daughter of Kimie Hart")), List.of()), "https://www.geni.com/people/Kimie-Ellis/1", "Geni");
        Graph f = Graph.build(fresh);
        assertNotEquals(f.nodeIdOf("Kimie Hart"), f.nodeIdOf("Kimie Ellis"));
    }

    @Test
    void aPictureThatBacksAFactAlreadyHeldLeavesItsNoteAndIsCheckedByItsOwnWords(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(fact("Kimie Hale", "born-in", "Sendai", "1901", "Kimie Hale was born in Sendai in 1901")), List.of()), "file:///family/notes.txt", "an aunt");
        String picture = "file:///family/register.png";
        FamilyTranscript.Transcript t = FamilyTranscript.keep(store, picture, "register.png", "Kimie Hale, born 1901 at Sendai. Died 1970 at Tokyo.");
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(fact("Kimie Hale", "born-in", "Sendai", "1901", "Kimie Hale, born 1901 at Sendai"),
                fact("Kimie Hale", "died-in", "Tokyo", "1970", "Died 1970 at Tokyo")), List.of()), picture, "a picture", t.against());
        Finding birth = store.scanFindings().findings().stream().filter(f -> f.triple() != null && f.triple().predicate().equals("born-in")).findFirst().orElseThrow();
        assertEquals(2, birth.sources().size(), "the picture is a second source of the birth");
        assertTrue(FamilyTranscript.onMachineReading(birth), "and the claim says its words were checked only against the model's reading: " + birth.notes());
        // once the transcript is accepted, the birth is checked by the words the picture gave, not the aunt's
        FamilyTranscript.noteRecheck(store, FamilyTranscript.accept(store, t, null, "person"));
        String now = FamilyTranscript.checkedAgainst(store.finding(birth.id()));
        assertTrue(now.startsWith("the transcript of the picture register.png") && !now.endsWith("the quoted words are not in it"), now);
    }

    @Test
    void twoTreesThatAgreeOnOnePageAreNotTakenForAMisreading(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        Gedcom.importFile(store, ged(tmp, "aunt.ged", "0 @I1@ INDI\n1 NAME Tom /Hale/\n1 BIRT\n2 DATE 1850\n2 SOUR @S1@\n3 PAGE p. 12\n0 @S1@ SOUR\n1 TITL Dunedin Parish Register\n"));
        Gedcom.importFile(store, ged(tmp, "cousin.ged", "0 @I1@ INDI\n1 NAME Tom /Hale/\n1 BIRT\n2 DATE 1850\n2 SOUR @S1@\n3 PAGE page 12\n2 SOUR @S1@\n3 PAGE p. 12\n0 @S1@ SOUR\n1 TITL Dunedin parish register\n"));
        String checks = FamilyChecks.render(FamilyChecks.check(store));
        assertFalse(checks.contains("cite one page of one source"), checks);
        // two different years on that page still are
        Gedcom.importFile(store, ged(tmp, "other.ged", "0 @I1@ INDI\n1 NAME Tom /Hale/\n1 BIRT\n2 DATE 1852\n2 SOUR @S1@\n3 PAGE p. 12\n0 @S1@ SOUR\n1 TITL Dunedin Parish Register\n"));
        assertTrue(FamilyChecks.render(FamilyChecks.check(store)).contains("2 claims about Tom Hale differ and cite one page of one source"));
    }

    @Test
    void twoPeopleWithOneFamilyTreeIdCanBeAnsweredOnTheDecisionsPage(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        Gedcom.importFile(store, ged(tmp, "a.ged", "0 @I1@ INDI\n1 NAME Tom /Ellis/\n1 _FSFTID KWZ1-234\n1 DEAT\n2 DATE 1900\n"));
        Gedcom.importFile(store, ged(tmp, "b.ged", "0 @I1@ INDI\n1 NAME Thomas /Ellis/\n1 _FSFTID KWZ1-234\n1 DEAT\n2 DATE 1900\n"));
        List<FamilyDecisions.Pair> pairs = FamilyDecisions.pairs(store);
        assertEquals(1, pairs.size(), pairs.toString());
        assertTrue(DecisionsPage.body(store, Patrons.Patron.PERSON, "").contains("One person"));
    }

    @Test
    void aCremationAFuneralOrAProbateAfterTheDeathIsNoSignOfANamesake(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        Gedcom.importFile(store, ged(tmp, "tree.ged", "0 @I1@ INDI\n1 NAME Tom /Ellis/\n1 BIRT\n2 DATE 1851\n2 PLAC Leeds\n1 DEAT\n2 DATE 20 DEC 1910\n2 PLAC York\n"
                + "1 CREM\n2 DATE 3 JAN 1911\n2 PLAC York\n1 PROB\n2 DATE 12 JUN 1912\n2 PLAC York\n1 RESI\n2 DATE 1915\n2 PLAC Hull\n"));
        String checks = FamilyChecks.render(FamilyChecks.check(store));
        assertFalse(checks.contains("cremated in York\" is dated") || checks.contains("will proved in York\" is dated"), checks);
        assertTrue(checks.contains("\"Tom Ellis lived in Hull\" is dated 1915, after Tom Ellis's death"), "a home after the death still is: " + checks);
    }
}
