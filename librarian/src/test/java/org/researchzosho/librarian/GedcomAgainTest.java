package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A family file read a second time, as the family keeps exporting it: an unchanged copy changes nothing, two births the file gives
 * one person are both kept, a fact the person disputed or accepted is not filed again in newer words, a birth between two years is
 * not a second person, and the export writes back what the import read: who is the husband, and a foster child.
 */
class GedcomAgainTest {

    private static LibraryStore store(Path tmp) throws Exception { LibraryStore s = new LibraryStore(tmp.resolve("lib")); s.init(); return s; }

    private static Path ged(Path tmp, String name, String body) throws Exception {
        Path f = tmp.resolve(name);
        Files.writeString(f, "0 HEAD\n1 CHAR UTF-8\n" + body + "0 TRLR\n", StandardCharsets.UTF_8);
        return f;
    }

    /** A claim as an older version of the import wrote it: its own words, the same file, the same event. */
    private static Finding older(LibraryStore store, Path file, String s, String p, String o, String line, String event, Finding.State state) throws Exception {
        Finding f = new Finding(store.nextFindingId(s + " " + p + " " + o), line, List.of(), state, Finding.ClaimType.extraction, Finding.Confidence.medium, "gedcom-import",
                Instant.now().toString(), LocalDate.now().toString(), Finding.Volatility.stable, "",
                List.of(new Finding.Source("file://" + file.toAbsolutePath().normalize(), "GEDCOM " + event, "the family file the person shelved")), List.of(), null, line + "\n",
                new Finding.Triple(s, p, o), List.of());
        store.write(f);
        return f;
    }

    private static List<Finding> live(LibraryStore store, String predicate) {
        return store.scanFindings().findings().stream().filter(f -> f.triple() != null && f.triple().predicate().equals(predicate) && f.state() != Finding.State.superseded).toList();
    }

    @Test
    void anUnchangedFileReadAgainAddsNothingBesideTheNotesItDatesMoreExactly(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        // the notes give the year, the tree file the day
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(new FamilyAccount.Fact("Kimie Hart", "born-in", "Whitby", "1922", "Kimie Hart was born in Whitby in 1922"),
                new FamilyAccount.Fact("Kimie Hart", "occupation", "potter", "1950", "Kimie Hart was a potter in 1950")), List.of()), "file:///home/me/notes.txt", "an aunt");
        Path file = ged(tmp, "tree.ged", "0 @I1@ INDI\n1 NAME Kimie /Hart/\n1 BIRT\n2 DATE 12 MAR 1922\n2 PLAC Whitby\n1 OCCU potter\n2 DATE ABT 1950\n1 DEAT\n2 DATE 1990\n");
        Gedcom.importFile(store, file);
        assertEquals(1, live(store, "born-in").size(), "the file joins the notes' birth as a further source");
        assertEquals(1, live(store, "occupation").size(), "and the job");
        for (int i = 0; i < 2; i++) {
            Gedcom.Outcome again = Gedcom.importFile(store, file);
            assertEquals(0, again.findings(), "read " + (i + 2) + " times");
        }
        assertEquals(1, live(store, "born-in").size());
        assertEquals(1, live(store, "occupation").size());
    }

    @Test
    void twoBirthsInTheFileAreBothKeptAndAnUnchangedCopyChangesNothing(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        Path file = ged(tmp, "tree.ged", "0 @I1@ INDI\n1 NAME Tom /Hale/\n1 BIRT\n2 DATE 1850\n1 BIRT\n2 DATE 1851\n2 PLAC York\n1 DEAT\n2 DATE 1900\n2 PLAC Leeds\n1 DEAT\n2 DATE 1901\n2 PLAC Hull\n"
                + "1 BURI\n2 DATE 1901\n2 PLAC York\n1 BURI\n2 DATE 1902\n2 PLAC York\n");
        Gedcom.Outcome first = Gedcom.importFile(store, file);
        assertEquals(6, first.findings());
        assertEquals(0, first.superseded(), "the second birth does not replace the first");
        assertEquals(1, live(store, "born-on").size());
        assertEquals(1, live(store, "born-in").size());
        assertEquals(2, live(store, "died-in").size());
        assertEquals(2, live(store, "buried-in").size(), "two burials in York in two years are two claims");
        Gedcom.Outcome again = Gedcom.importFile(store, file);
        assertEquals(0, again.findings(), "the same file again files nothing");
        assertEquals(0, again.superseded());
    }

    @Test
    void aCopyReadAgainKeepsWhatAnOlderImportFiledInOtherWordsAndCorrectsOnlyWhatChanged(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        Path file = ged(tmp, "tree.ged", "0 @I1@ INDI\n1 NAME Tom /Hale/\n1 BIRT\n2 DATE ABT 1850\n1 BIRT\n2 DATE 1851\n2 PLAC York\n"
                + "0 @I2@ INDI\n1 NAME Ann /Ellis/\n1 BIRT\n2 DATE BEF 1855\n"
                + "0 @I3@ INDI\n1 NAME Joe /Ellis/\n1 BIRT\n2 DATE 1860\n2 PLAC Leeds\n1 DEAT\n2 DATE 1930\n");
        // what an older version filed from this file: its own words, one fact disputed by the person and one accepted
        Finding about = older(store, file, "Tom Hale", "born-on", "ABT 1850", "Tom Hale was born on ABT 1850.", "@I1@ BIRT", Finding.State.draft);
        Finding york = older(store, file, "Tom Hale", "born-in", "York", "Tom Hale was born in York (1851).", "@I1@ BIRT", Finding.State.draft);
        Finding bef = older(store, file, "Ann Ellis", "born-on", "BEF 1855", "Ann Ellis was born on BEF 1855.", "@I2@ BIRT", Finding.State.accepted);
        Finding leeds = older(store, file, "Joe Ellis", "born-in", "Leeds", "Joe Ellis born in Leeds (1860).", "@I3@ BIRT", Finding.State.disputed);
        Finding died = older(store, file, "Joe Ellis", "died-on", "1920", "Joe Ellis died in 1920.", "@I3@ DEAT", Finding.State.draft);
        Gedcom.Outcome o = Gedcom.importFile(store, file);
        for (Finding f : List.of(about, york, bef, leeds)) assertEquals(f.state(), store.finding(f.id()).state(), f.id() + " is as the person left it");
        assertEquals(1, live(store, "born-on").stream().filter(f -> f.triple().subject().equals("Tom Hale")).count(), "no second draft of the same birth");
        assertEquals(1, live(store, "born-on").stream().filter(f -> f.triple().subject().equals("Ann Ellis")).count(), "an accepted fact gets no draft beside it");
        assertEquals(1, live(store, "born-in").stream().filter(f -> f.triple().subject().equals("Joe Ellis")).count(), "a disputed fact is not filed again");
        // the death the file now dates 1930 replaces the draft that said 1920, and only that one
        assertEquals(1, o.superseded(), o.toString());
        assertEquals(Finding.State.superseded, store.finding(died.id()).state());
        Finding now = live(store, "died-on").stream().filter(f -> f.triple().subject().equals("Joe Ellis")).findFirst().orElseThrow();
        assertEquals("1930", now.triple().object());
        assertEquals(List.of(died.id()), now.supersedes());
    }

    @Test
    void aBirthBetweenTwoYearsThatHoldTheLibrarysYearIsTheSamePerson(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(new FamilyAccount.Fact("Tom Hale", "born-on", "1855", "", "Tom Hale was born in 1855"),
                new FamilyAccount.Fact("Tom Hale", "married-to", "Ann Hart", "", "Tom married Ann Hart")), List.of()), "file:///family/notes.txt", "an aunt");
        for (String date : List.of("BET 1840 AND 1860", "ABT 1851", "BEF 1860")) {
            Path file = ged(tmp, "t" + date.hashCode() + ".ged", "0 @I1@ INDI\n1 NAME Tom /Hale/\n1 FAMS @F1@\n1 BIRT\n2 DATE " + date + "\n0 @I2@ INDI\n1 NAME Ann /Hart/\n1 FAMS @F1@\n0 @F1@ FAM\n1 HUSB @I1@\n1 WIFE @I2@\n");
            String dry = Gedcom.dryRun(store, file);
            assertFalse(dry.contains("They are two people"), date + ": " + dry);
            Gedcom.importFile(store, file);
            assertTrue(store.scanFindings().findings().stream().noneMatch(f -> f.triple() != null && f.triple().subject().startsWith("Tom Hale (")), date + " filed a second Tom Hale");
        }
        // a date that no year of it fits is a second person, and the name and the sentence give the date as the file does
        Path far = ged(tmp, "far.ged", "0 @I1@ INDI\n1 NAME Tom /Hale/\n1 BIRT\n2 DATE BET 1800 AND 1810\n");
        String dry = Gedcom.dryRun(store, far);
        assertTrue(dry.contains("Tom Hale in the file was born between 1800 and 1810, and the Tom Hale already in your library was born in 1855. They are two people, so the one from the file is filed as Tom Hale (born between 1800 and 1810)."), dry);
    }

    @Test
    void aNameWrittenAnotherWayIsNotOfferedAsTheSamePersonWhenTheBirthsAreYearsApart(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(new FamilyAccount.Fact("Hisa Endo", "born-on", "1851", "", "Hisa Endo was born in 1851")), List.of()), "file:///family/notes.txt", "an aunt");
        String far = Gedcom.dryRun(store, ged(tmp, "far.ged", "0 @I1@ INDI\n1 NAME Endo /Hisa/\n1 BIRT\n2 DATE 1890\n"));
        assertFalse(far.contains("born around the same time") || far.contains("may be Hisa Endo"), far);
        String near = Gedcom.dryRun(store, ged(tmp, "near.ged", "0 @I1@ INDI\n1 NAME Endo /Hisa/\n1 BIRT\n2 DATE 1852\n"));
        assertTrue(near.contains("Endo Hisa in the file may be Hisa Endo in your library") && near.contains("Both were born around the same time (1852 and 1851)."), near);
    }

    @Test
    void theExportWritesTheHusbandTheSexesAndAFosterChildBack(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        Path file = ged(tmp, "tree.ged", "0 @I1@ INDI\n1 NAME Tom /Ellis/\n1 SEX M\n1 FAMS @F1@\n1 DEAT\n2 DATE 1920\n"
                + "0 @I2@ INDI\n1 NAME Ann /Hart/\n1 SEX F\n1 FAMS @F1@\n1 DEAT\n2 DATE 1925\n"
                + "0 @I3@ INDI\n1 NAME Hisa /Ellis/\n1 SEX F\n1 FAMC @F1@\n2 PEDI foster\n1 BIRT\n2 DATE 1880\n"
                + "0 @F1@ FAM\n1 HUSB @I1@\n1 WIFE @I2@\n1 CHIL @I3@\n1 MARR\n2 DATE 1875\n");
        Gedcom.importFile(store, file);
        String out = Gedcom.export(store, "Tom Ellis");
        assertTrue(out.contains("1 NAME Tom /Ellis/\n1 SEX M\n") && out.contains("1 NAME Ann /Hart/\n1 SEX F\n"), out);
        String tom = out.substring(0, out.indexOf("1 NAME Tom /Ellis/")).replaceAll("(?s).*0 (@I\\d+@) INDI\\n$", "$1");
        assertTrue(out.contains("1 HUSB " + tom + "\n"), "the man is the husband: " + out);
        assertTrue(out.contains("1 NAME Hisa /Ellis/"), "the foster child is in the family: " + out);
        assertTrue(out.contains("2 PEDI foster"), out);
        // and a second library reads the same family from the export
        LibraryStore other = store(tmp.resolve("other"));
        Gedcom.importFile(other, ged(tmp, "back.ged", out.replaceFirst("(?s)^0 HEAD.*?(?=0 @)", "").replace("0 TRLR\n", "")));
        Graph g = Graph.build(other);
        assertTrue(g.edges().stream().anyMatch(e -> e.predicate().equals("foster-child-of") && e.from().equals(g.nodeIdOf("Hisa Ellis")) && e.to().equals(g.nodeIdOf("Tom Ellis"))), g.edges().toString());
        assertTrue(g.edges().stream().anyMatch(e -> e.predicate().equals("sex") && e.from().equals(g.nodeIdOf("Ann Hart"))));
    }

    @Test
    void anOlderImportThatJoinedAFatherAndASonIsCorrectedWholeAndSaysSoOnce(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        Path file = ged(tmp, "tree.ged", "0 @I1@ INDI\n1 NAME John /Ellis/\n1 BIRT\n2 DATE 1870\n1 FAMS @F1@\n"
                + "0 @I2@ INDI\n1 NAME John /Ellis/\n1 BIRT\n2 DATE 1902\n1 FAMC @F1@\n1 FAMS @F2@\n"
                + "0 @I3@ INDI\n1 NAME Mary /Hale/\n1 FAMS @F2@\n0 @I4@ INDI\n1 NAME Tom /Ellis/\n1 BIRT\n2 DATE 1925\n1 FAMC @F2@\n"
                + "0 @F1@ FAM\n1 HUSB @I1@\n1 CHIL @I2@\n0 @F2@ FAM\n1 HUSB @I2@\n1 WIFE @I3@\n1 MARR\n2 DATE 1924\n1 CHIL @I4@\n");
        // what an older version filed: the two men of one name as one
        older(store, file, "John Ellis", "born-on", "1870", "John Ellis was born in 1870.", "@I1@ BIRT", Finding.State.draft);
        older(store, file, "John Ellis", "born-on", "1902", "John Ellis was born in 1902.", "@I2@ BIRT", Finding.State.draft);
        older(store, file, "John Ellis", "parent-of", "John Ellis", "John Ellis is a parent of John Ellis.", "@F1@ CHIL", Finding.State.draft);
        older(store, file, "John Ellis", "parent-of", "Tom Ellis", "John Ellis is a parent of Tom Ellis.", "@F2@ CHIL", Finding.State.draft);
        older(store, file, "Mary Hale", "parent-of", "Tom Ellis", "Mary Hale is a parent of Tom Ellis.", "@F2@ CHIL", Finding.State.draft);
        older(store, file, "John Ellis", "married-to", "Mary Hale", "John Ellis was married to Mary Hale (1924).", "@F2@ MARR", Finding.State.draft);
        Gedcom.Outcome o = Gedcom.importFile(store, file);
        assertTrue(o.apart().stream().anyMatch(a -> a.contains("An earlier import of this file took the people named John Ellis for one person")), o.apart().toString());
        assertTrue(o.moved() >= 3, "the two births and the marriage are said again, of the men the file names apart: " + o);
        List<String> parents = live(store, "parent-of").stream().filter(f -> f.state() == Finding.State.draft).map(f -> f.triple().subject() + " > " + f.triple().object()).toList();
        assertFalse(parents.contains("John Ellis > John Ellis"), "nobody is their own parent any more: " + parents);
        assertFalse(parents.contains("John Ellis > Tom Ellis"), parents.toString());
        assertTrue(parents.contains("Mary Hale > Tom Ellis"), "what this copy says again stays: " + parents);
        List<FamilyChecks.Problem> problems = FamilyChecks.check(store);
        assertTrue(problems.stream().noneMatch(p -> p.text().contains("birth parents") || p.text().contains("own ancestor")), problems.toString());
        assertTrue(FamilyDecisions.pairs(store).stream().noneMatch(p -> p.fold().equals("John Ellis") || p.into().equals("John Ellis")), "the bare name is not offered to join again");
        Gedcom.Outcome again = Gedcom.importFile(store, file);
        assertTrue(again.apart().stream().noneMatch(a -> a.contains("An earlier import")), "the advice is not given again once it is done: " + again.apart());
        FamilyReset.Plan plan = FamilyReset.plan(store, List.of("gedcom-import"), "tree.ged");
        assertEquals(0, plan.kept(), "nothing here was accepted or disputed: " + plan);
        assertTrue(plan.replaced() >= 5, plan.toString());
    }

    @Test
    void afterTheBareNameIsJoinedIntoANamesakeTheImportGivesNoAdviceToStartAgain(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        // the notes name the grandfather without his year; the tree file tells him from his grandson of the same name
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(new FamilyAccount.Fact("Tom Hale", "lived-in", "York", "", "Tom Hale lived in York")), List.of()), "file:///home/me/notes.txt", "an aunt");
        Path file = ged(tmp, "tree.ged", "0 @I1@ INDI\n1 NAME Tom /Hale/\n1 BIRT\n2 DATE 1920\n1 DEAT\n2 DATE 1990\n0 @I2@ INDI\n1 NAME Tom /Hale/\n1 BIRT\n2 DATE 1985\n");
        Gedcom.importFile(store, file);
        // the owner joins the notes' Tom Hale into the grandfather, as the check suggests
        Graph.merge(store, "Tom Hale", "Tom Hale (born 1920)", "person");
        for (int i = 0; i < 2; i++) {
            Gedcom.Outcome again = Gedcom.importFile(store, file);
            assertTrue(again.apart().stream().noneMatch(a -> a.contains("An earlier import")), "no earlier import took the two for one: " + again.apart());
        }
    }

    @Test
    void aFurtherSourceWithAnotherYearIsAClaimOfItsOwn(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(new FamilyAccount.Fact("Tom Ellis", "born-in", "York", "1901", "Tom Ellis was born in York in 1901"),
                new FamilyAccount.Fact("Tom Ellis", "married-to", "Ann Hart", "", "Tom Ellis married Ann Hart")), List.of()), "file:///family/notes.txt", "an aunt");
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(new FamilyAccount.Fact("Tom Ellis", "born-in", "York", "1899", "Tom Ellis was born at York in 1899")), List.of()), "file:///family/book.pdf", "a book");
        Path file = ged(tmp, "t.ged", "0 @I1@ INDI\n1 NAME Tom /Ellis/\n1 BIRT\n2 DATE 1901\n2 PLAC York\n1 FAMS @F1@\n0 @I2@ INDI\n1 NAME Ann /Hart/\n1 FAMS @F1@\n0 @F1@ FAM\n1 HUSB @I1@\n1 WIFE @I2@\n1 MARR\n2 DATE 1930\n2 PLAC Leeds\n");
        Gedcom.importFile(store, file);
        List<String> births = live(store, "born-in").stream().map(Finding::title).toList();
        assertTrue(births.stream().anyMatch(t -> t.contains("1899")) && births.stream().anyMatch(t -> t.contains("1901")), "the book's year is kept: " + births);
        Finding y1901 = live(store, "born-in").stream().filter(f -> f.title().contains("1901")).findFirst().orElseThrow();
        assertEquals(2, y1901.sources().size(), "the tree file's 1901 joins the notes' 1901");
        assertTrue(live(store, "married-to").stream().anyMatch(f -> f.title().contains("1930")), "the marriage year the file gives is kept: " + live(store, "married-to").stream().map(Finding::title).toList());
        assertTrue(FamilyChecks.check(store).stream().anyMatch(p -> p.text().contains("two birth years")), "and the check shows the two years");
    }
}
