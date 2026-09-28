package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The living rule is one rule wherever it runs: the graph works it out from every claim each time it is read, so a family read, a
 * tree file and a dispute all count at once. A death a source gives without a date is a claim like any other.
 */
class LivingEvidenceTest {

    private static Graph.Node node(LibraryStore store, String name) throws Exception { Graph g = Graph.build(store); return g.node(g.nodeIdOf(name)); }

    private static FamilyAccount.Fact fact(String s, String r, String o, String date) { return new FamilyAccount.Fact(s, r, o, date, s + " " + r + " " + o); }

    private static FamilyAccount.Read read(List<FamilyAccount.Person> people, FamilyAccount.Fact... facts) { return new FamilyAccount.Read(people, List.of(facts), List.of()); }

    private static String out(Call c) throws Exception {
        PrintStream was = System.out;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        try { c.run(); } finally { System.setOut(was); }
        return out.toString(StandardCharsets.UTF_8);
    }

    private interface Call { void run() throws Exception; }

    @Test
    void aDeathASourceGivesWithoutADateIsFiledAsAClaim(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        // a tree file: a birth of 1990 and a death with neither a date nor a place, a cremation with none either, and a birth of 1990 alone
        Path ged = tmp.resolve("tree.ged");
        Files.writeString(ged, "0 HEAD\n0 @I1@ INDI\n1 NAME Ken /Endo/\n1 BIRT\n2 DATE 1990\n1 DEAT Y\n0 @I2@ INDI\n1 NAME Genzaburo /Hart/\n1 CREM\n"
                + "0 @I3@ INDI\n1 NAME Mari /Endo/\n1 BIRT\n2 DATE 1990\n2 PLAC Kure\n0 TRLR\n", StandardCharsets.UTF_8);
        Gedcom.Outcome o = Gedcom.importFile(store, ged);
        assertEquals(1, o.mayBeLiving(), "born in 1990, and nothing says she died: " + o);
        assertTrue(store.scanFindings().findings().stream().anyMatch(f -> f.body().startsWith("Ken Endo: died.")), "the death is a claim, with the file as its source");
        assertTrue(store.scanFindings().findings().stream().anyMatch(f -> f.body().startsWith("Genzaburo Hart: cremated.")));
        assertFalse(node(store, "Ken Endo").mayBeLiving(), "a death the file gives counts over his own birth: he has died");
        assertFalse(node(store, "Genzaburo Hart").mayBeLiving());
        assertTrue(node(store, "Mari Endo").mayBeLiving());
        // the tree prints a '?' for a death the library knows of and cannot date, and nothing for somebody who may be living
        assertTrue(FamilyTree.svg(FamilyTree.around(store, "Ken Endo", 1, 1), n -> "", x -> "").contains("1990 – ?"), "Ken Endo died, when is not known");
        // the export gives the death back as a death
        assertTrue(Gedcom.export(store, "Ken Endo").contains("\n1 DEAT\n2 SOUR"), Gedcom.export(store, "Ken Endo"));

        // Geni saying a relative is not living is a death too
        LibraryStore geni = new LibraryStore(tmp.resolve("geni")); geni.init();
        FamilyAccount.Read read = GeniFamily.read(new ObjectMapper().readTree(
                "{\"focus\":{\"id\":\"profile-1\",\"name\":\"Tom Hale\",\"is_alive\":true},\"nodes\":{\"profile-1\":{},\"profile-2\":{\"name\":\"Ann Hale\",\"is_alive\":false}}}"));
        FamilyAccount.file(geni, read, "https://www.geni.com/people/Tom-Hale/1", "Geni");
        assertFalse(node(geni, "Ann Hale").mayBeLiving(), "Geni says she is not living: " + geni.scanFindings().findings());
    }


    @Test
    void aBurialOrACensusTheTreeFileDatesWithoutAPlaceIsFiledAndStays(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Path ged = tmp.resolve("tree.ged");
        Files.writeString(ged, "0 HEAD\n0 @I1@ INDI\n1 NAME Tom /Hale/\n1 SEX M\n1 BURI\n2 DATE 12 MAR 1901\n"
                + "0 @I2@ INDI\n1 NAME Ann /Hale/\n1 SEX F\n1 CENS\n2 DATE 1881\n"
                + "0 @I3@ INDI\n1 NAME Ed /Hale/\n1 SEX M\n1 CREM\n2 DATE 1950\n0 TRLR\n", StandardCharsets.UTF_8);
        Gedcom.Outcome o = Gedcom.importFile(store, ged);
        assertEquals(0, o.mayBeLiving(), o.toString());
        for (String n : List.of("Tom Hale", "Ann Hale", "Ed Hale")) assertFalse(node(store, n).mayBeLiving(), n + ": " + Files.readString(Graph.nodesFile(store)));
        assertTrue(store.scanFindings().findings().stream().anyMatch(f -> f.body().startsWith("Tom Hale: buried (12 MAR 1901).")));
        // a later text that names him says nothing of his death, and he stays where the file put him
        FamilyAccount.fileAsRead(store, read(List.of(), fact("Tom Hale", "occupation", "carter", "")), "file:///family/notes.txt", "an aunt");
        assertFalse(node(store, "Tom Hale").mayBeLiving(), Files.readString(Graph.nodesFile(store)));
    }

    private static Finding claim(LibraryStore store, String s, String p, String o, String line, String writer, Finding.State state, List<Finding.Note> notes) throws Exception {
        Finding x = new Finding(store.nextFindingId(s + " " + p + " " + o), line, List.of(), state, Finding.ClaimType.extraction,
                Finding.Confidence.medium, writer, Instant.now().toString(), LocalDate.now().toString(), Finding.Volatility.stable, "",
                List.of(new Finding.Source("https://example.org/ellis", "", "a page")), List.of(), null, line + "\n", new Finding.Triple(s, p, o), notes);
        store.write(x);
        return x;
    }

    @Test
    void twoYearsTheReviewDisputedAgainstEachOtherStillPlaceThePersonInThePast(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        FamilyAccount.fileAsRead(store, read(List.of(), fact("Tom Ellis", "born-on", "1850", "")), "file:///family/notes.txt", "an aunt");
        assertFalse(node(store, "Tom Ellis").mayBeLiving());
        // a research run files 1852, and the review marks both disputed against each other, as it does for a contradiction
        Finding family = store.scanFindings().findings().get(0);
        Finding run = claim(store, "Tom Ellis", "born-on", "1852", "Tom Ellis was born in 1852.", "model:research", Finding.State.disputed,
                List.of(new Finding.Note("disputed", "librarian", LocalDate.now().toString(), "contradicts " + family.id() + " (same subject and predicate, different object)")));
        store.write(new Finding(family.id(), family.title(), family.subjects(), Finding.State.disputed, family.claimType(), family.confidence(), family.writer(), family.recordedAt(),
                family.validAsOf(), family.volatility(), family.reviewBy(), family.sources(), family.supersedes(), family.review(), family.body(), family.triple(), family.notes())
                .withNote(new Finding.Note("disputed", "librarian", LocalDate.now().toString(), "contradicted by " + run.id())));
        assertFalse(node(store, "Tom Ellis").mayBeLiving(), "the review asked which year, not whether");
        // the owner disputing a year by hand is the owner's word: that year no longer counts
        new Council(store).dispute(run.id(), "not him");
        new Council(store).dispute(family.id(), "no record");
        assertTrue(node(store, "Tom Ellis").mayBeLiving(), "at once, with nothing to run first");
    }



    @Test
    void aChildTheTreeFileShowsIsALivingPersonLikeAnyOther(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Path ged = tmp.resolve("tree.ged");
        Files.writeString(ged, "0 HEAD\n0 @I1@ INDI\n1 NAME Kenji /Endo/\n1 SEX M\n1 BIRT\n2 DATE 1980\n1 DEAT\n2 DATE 2020\n1 FAMS @F1@\n"
                + "0 @I2@ INDI\n1 NAME Hana /Endo/\n1 SEX F\n1 BIRT\n2 DATE 1982\n1 FAMS @F1@\n1 _TODO find where Hana taught\n"
                + "0 @I3@ INDI\n1 NAME Ren /Endo/\n1 BIRT\n2 DATE 2016\n1 FAMC @F1@\n1 _TODO find Ren's school records in Kure\n"
                + "0 @F1@ FAM\n1 HUSB @I1@\n1 WIFE @I2@\n1 CHIL @I3@\n0 TRLR\n", StandardCharsets.UTF_8);
        Gedcom.Outcome o = Gedcom.importFile(store, ged);
        Graph.Node ren = node(store, "Ren Endo");
        assertTrue(ren.mayBeLiving(), "the dates say he may be living: " + ren);
        assertTrue(o.problems().stream().anyMatch(p -> p.startsWith("2 research notes in tree.ged were added")), o.problems().toString());
        // a note on a living adult and a note on a child are open questions like any other
        List<Frontier.Line> lines = Frontier.read(store);
        for (String who : List.of("Hana", "Ren")) {
            Frontier.Line l = lines.stream().filter(x -> x.text().contains(who)).findFirst().orElseThrow();
            assertTrue(!l.parked() && l.researchable(), l.toString());
        }
        // the search for everybody takes the child with the living, and leaves both out with --skip-living
        List<String> all = FamilyQuestions.everybody(store, 6, 3, true).stream().map(FamilyQuestions.Ask::person).toList();
        assertTrue(all.contains("Ren Endo") && all.contains("Hana Endo"), all.toString());
        List<String> skip = FamilyQuestions.everybody(store, 6, 3, false).stream().map(FamilyQuestions.Ask::person).toList();
        assertFalse(skip.contains("Ren Endo") || skip.contains("Hana Endo"), skip.toString());
    }

    @Test
    void aReadDecidesWithWhatTheLibraryHoldsAboutThePerson(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        FamilyAccount.fileAsRead(store, read(List.of(), fact("Genzo Hale", "born-on", "1948", "")), "file:///family/a.txt", "an aunt");
        assertTrue(node(store, "Genzo Hale").mayBeLiving());
        // a book places Genzo's son at work in 1925: the relation's arithmetic does not outweigh his own birth in 1948
        FamilyAccount.fileAsRead(store, read(List.of(), fact("Isamu Endo", "occupation", "teacher", "1925"),
                new FamilyAccount.Fact("Genzo Hale", "parent-of", "Isamu Endo", "", "Genzo Hale was the father of Isamu Endo.")), "file:///family/book.txt", "a book");
        assertTrue(node(store, "Genzo Hale").mayBeLiving(), Files.readString(Graph.nodesFile(store)));

        // a relation the owner disputed, read again, places nobody in time and gives nobody a sex
        LibraryStore two = new LibraryStore(tmp.resolve("two")); two.init();
        FamilyAccount.fileAsRead(two, read(List.of(), fact("Genzo Hale", "sex", "male", "")), "file:///family/notes.txt", "an aunt");
        FamilyAccount.Read book = read(List.of(), fact("Isamu Endo", "occupation", "teacher", "1925"), new FamilyAccount.Fact("Genzo Hale", "parent-of", "Isamu Endo", "", "Genzo Hale was the father of Isamu Endo."));
        FamilyAccount.fileAsRead(two, book, "file:///family/book.txt", "a book");
        Finding relation = two.scanFindings().findings().stream().filter(f -> f.triple().predicate().equals("parent-of")).findFirst().orElseThrow();
        new Council(two).dispute(relation.id(), "the book mixes up two families");
        Finding sex = two.scanFindings().findings().stream().filter(f -> f.triple().predicate().equals("sex")).findFirst().orElseThrow();
        int sources = sex.sources().size();
        Graph.setKind(two, "Genzo Hale", "person");
        FamilyAccount.fileAsRead(two, book, "file:///family/book.txt", "a book");
        assertTrue(node(two, "Genzo Hale").mayBeLiving(), "nothing that counts places him in the past: " + Files.readString(Graph.nodesFile(two)));
        assertEquals(sources, two.finding(sex.id()).sources().size(), "the disputed relation does not back his sex again");

        // a birth written with its place, the year in brackets, is a birth year the arithmetic of a read uses
        LibraryStore three = new LibraryStore(tmp.resolve("three")); three.init();
        FamilyAccount.fileAsRead(three, read(List.of(), fact("Genzo Hale", "born-in", "Leeds", "1948")), "file:///family/notes.txt", "an aunt");
        FamilyAccount.Outcome o = FamilyAccount.file(three, read(List.of(), fact("Isamu Endo", "born-on", "1925", ""), fact("Genzo Hale", "parent-of", "Isamu Endo", "")), "file:///family/book.txt", "a book");
        assertTrue(o.dropped().stream().anyMatch(d -> d.contains("cannot be true")), o.dropped().toString());
    }
}
