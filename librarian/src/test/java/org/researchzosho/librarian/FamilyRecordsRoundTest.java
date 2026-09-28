package org.researchzosho.librarian;

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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The family's records as the owner handles them: a namesake's label is no date, two birth years at one place are a decision,
 * a file passed on keeps every parent and child and each question under its own person, a reset of one file takes back that file
 * and the questions written from it, one person keeps the name that tells namesakes apart, and the owner's later word outlives
 * an unmerge.
 */
class FamilyRecordsRoundTest {

    private static Graph.Node node(LibraryStore store, String name) throws Exception { Graph g = Graph.build(store); return g.node(g.nodeIdOf(name)); }

    private static FamilyAccount.Fact fact(String s, String r, String o, String date) { return new FamilyAccount.Fact(s, r, o, date, s + " " + r + " " + o); }

    private static String out(Call c) throws Exception {
        PrintStream was = System.out;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        try { c.run(); } finally { System.setOut(was); }
        return out.toString(StandardCharsets.UTF_8);
    }

    private interface Call { void run() throws Exception; }

    @Test
    void aNamesakesLabelIsNotTheDateOfAnUndatedMarriage(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Path ged = tmp.resolve("ns.ged");
        Files.writeString(ged, "0 HEAD\n0 @I1@ INDI\n1 NAME Tom /Hale/\n1 SEX M\n1 BIRT\n2 DATE 1870\n1 DEAT\n2 DATE 1940\n1 FAMS @F1@\n"
                + "0 @I2@ INDI\n1 NAME Ruth /Hale/\n1 BIRT\n2 DATE 1860\n1 FAMS @F1@\n"
                + "0 @I3@ INDI\n1 NAME Tom /Hale/\n1 SEX M\n1 BIRT\n2 DATE 1902\n1 DEAT\n2 DATE 1970\n1 OCCU miner\n1 FAMC @F1@\n1 FAMS @F2@\n"
                + "0 @I4@ INDI\n1 NAME Ann /Hart/\n1 BIRT\n2 DATE 1905\n2 PLAC York\n1 FAMS @F2@\n"
                + "0 @F1@ FAM\n1 HUSB @I1@\n1 WIFE @I2@\n1 CHIL @I3@\n0 @F2@ FAM\n1 HUSB @I3@\n1 WIFE @I4@\n0 TRLR\n", StandardCharsets.UTF_8);
        Gedcom.importFile(store, ged);
        List<FamilyChecks.Problem> problems = FamilyChecks.check(store);
        assertTrue(problems.stream().noneMatch(p -> p.text().contains("at the marriage")), problems.stream().map(FamilyChecks.Problem::text).toList().toString());
        assertTrue(problems.stream().noneMatch(p -> p.text().contains("(born 1870) (born 1870)")), problems.toString());
        Finding marriage = store.scanFindings().findings().stream().filter(f -> f.triple() != null && f.triple().predicate().equals("married-to") && f.triple().subject().contains("1902")).findFirst().orElseThrow();
        assertNull(FamilyChecks.claimDate(marriage), "the marriage has no date: " + marriage.body());
        Finding miner = store.scanFindings().findings().stream().filter(f -> f.triple() != null && f.triple().predicate().equals("occupation")).findFirst().orElseThrow();
        assertNull(FamilyChecks.claimYear(miner));
        assertTrue(FamilyLife.of(store, "Tom Hale (born 1902)").stream().noneMatch(l -> l.year() != null && l.year() == 1902 && l.text().contains("miner")));
    }

    @Test
    void twoBirthYearsAtOnePlaceAreADecisionAndBothAreSettledTogether(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Path ged = tmp.resolve("tree.ged");
        Files.writeString(ged, "0 HEAD\n0 @I1@ INDI\n1 NAME Genzaburo /Endo/\n1 BIRT\n2 DATE 1870\n2 PLAC Sendai\n1 DEAT\n2 DATE 1930\n0 TRLR\n", StandardCharsets.UTF_8);
        Gedcom.importFile(store, ged);
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(fact("Genzaburo Endo", "born-in", "Sendai", "1880")), List.of()), "file:///family/aunt.txt", "an aunt");
        List<FamilyDecisions.Clash> clashes = FamilyDecisions.clashes(store);
        assertEquals(1, clashes.size(), clashes.toString());
        assertEquals("birth year", clashes.get(0).what());
        String page = DecisionsPage.body(store, Patrons.Patron.PERSON, "");
        assertTrue(page.contains("Keep Sendai (1870)") && page.contains("Keep Sendai (1880)"), page);
        // a third source with another place and the first year: one question for each pair, and keeping one settles the year too
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(fact("Genzaburo Endo", "born-in", "Osaka", "1870")), List.of()), "file:///family/letters.txt", "a cousin");
        clashes = FamilyDecisions.clashes(store);
        assertEquals(3, clashes.size(), clashes.toString());
        FamilyDecisions.Clash first = clashes.stream().filter(c -> c.what().equals("birth year")).findFirst().orElseThrow();
        Finding sendai1870 = first.one().body().contains("1870") ? first.one() : first.other();
        DecisionsPage.post(store, Patrons.Patron.PERSON, Map.of("kind", "clash", "one", first.one().id(), "other", first.other().id(), "do", sendai1870 == first.one() ? "keep1" : "keep2"));
        assertFalse(FamilyDecisions.clashes(store).isEmpty(), "the birthplace against Osaka still waits for the family's word");
    }

    @Test
    void aFileForPassingOnKeepsEveryParentAndEachQuestionUnderItsOwnPerson(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Path ged = tmp.resolve("tree.ged");
        Files.writeString(ged, "0 HEAD\n0 @I1@ INDI\n1 NAME Genzaburo /Endo/\n1 SEX M\n1 BIRT\n2 DATE 1870\n1 DEAT\n2 DATE 1940\n1 FAMS @F1@\n"
                + "0 @I2@ INDI\n1 NAME Isamu /Endo/\n1 BIRT\n2 DATE 1900\n2 PLAC Sendai\n1 DEAT\n2 DATE 1975\n1 FAMC @F1@\n"
                + "0 @I3@ INDI\n1 NAME Hisa /Morita/\n1 OCCU teacher\n2 DATE 1925\n1 DEAT\n2 DATE 1980\n"
                + "0 @F1@ FAM\n1 HUSB @I1@\n1 CHIL @I2@\n0 TRLR\n", StandardCharsets.UTF_8);
        Gedcom.importFile(store, ged);
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(fact("Isamu Endo", "married-to", "Hisa Morita", "")), List.of()), "file:///family/notes.txt", "an aunt");
        for (FamilyQuestions.Ask a : FamilyQuestions.everybody(store, 6, 3, true)) store.frontier("person me (from the family tree)", a.question());
        String file = Gedcom.export(store, "Isamu Endo");
        assertTrue(file.contains(" FAM\n1 HUSB @I") && file.contains("1 NAME Genzaburo /Endo/"), file);
        String isamu = file.substring(file.indexOf("1 NAME Isamu /Endo/"), file.indexOf("0 @", file.indexOf("1 NAME Isamu /Endo/")));
        assertTrue(isamu.contains("1 FAMC @F"), "the father's family, with no mother in the file: " + isamu);
        assertFalse(isamu.contains("_TODO Genzaburo Endo") || isamu.contains("_TODO Hisa Morita"), "a question goes under the person it is about: " + isamu);
        assertTrue(isamu.contains("1 _TODO 1. ") && !isamu.contains("answer each of these questions"), "the questions, without the research's own instructions: " + isamu);
        assertTrue(file.contains("1 OCCU teacher\n2 DATE 1925\n"), file);
        // a second library reads the father and the son back, linked, and files each question once
        LibraryStore other = new LibraryStore(tmp.resolve("other")); other.init();
        Path back = tmp.resolve("back.ged");
        Files.writeString(back, file, StandardCharsets.UTF_8);
        Gedcom.importFile(other, back);
        assertTrue(Graph.build(other).edges().stream().anyMatch(e -> e.predicate().equals("parent-of") && e.from().equals("genzaburo endo") && e.to().equals("isamu endo")));
        assertTrue(Frontier.read(other).stream().noneMatch(l -> l.text().startsWith("Isamu Endo: Genzaburo Endo")), Frontier.read(other).toString());
    }

    private static Finding claim(LibraryStore store, String s, String p, String o, String line, String locator) throws Exception {
        Finding x = new Finding(store.nextFindingId(s + " " + p + " " + o), line, List.of(), Finding.State.draft, Finding.ClaimType.extraction,
                Finding.Confidence.low, "family-account", Instant.now().toString(), LocalDate.now().toString(), Finding.Volatility.stable, "",
                List.of(new Finding.Source(locator, "as told", "the family's own account")), List.of(), null, line + "\n", new Finding.Triple(s, p, o), List.of());
        store.write(x);
        return x;
    }

    @Test
    void aYearInsideWhatAClaimSaysDatesTheClaimAndAYearInANamesakesNameDoesNot(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        // a research run writes plain sentences, and the year can sit inside the event it took as the value
        Finding honour = FamilyLivingRuleTest.drafted(store, "Kenji Endo", "life-event", "received the Order of the Sacred Treasure in 1985", "Kenji Endo received the Order of the Sacred Treasure in 1985.");
        assertEquals(1985, FamilyChecks.claimYear(honour));
        Finding job = FamilyLivingRuleTest.drafted(store, "Kenji Endo", "occupation", "welder at the Kure Naval Arsenal from 1950", "Kenji Endo worked as welder at the Kure Naval Arsenal from 1950.");
        assertEquals(1950, FamilyChecks.claimYear(job));
        Finding wife = FamilyLivingRuleTest.drafted(store, "Tom Hale", "married-to", "Kimie Hale (born 1902)", "Tom Hale married Kimie Hale (born 1902).");
        assertNull(FamilyChecks.claimDate(wife), "a namesake's label is still no date");
    }

    @Test
    void eachSideOfAClashNamesEverySourceThatGivesIt(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        // a research page gave the birth first, the family's tree file gives it too, and a book gives another place and year
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(fact("Genzaburo Morita", "born-in", "Sendai", "1890")), List.of()), "https://www.example.org/morita-family", "a web page");
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(fact("Genzaburo Morita", "born-in", "Sendai", "1890")), List.of()), "file:///home/me/tree.ged", "a tree file");
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(fact("Genzaburo Morita", "born-in", "Osaka", "1888")), List.of()), "file:///home/me/history.pdf", "a book");
        String page = DecisionsPage.body(store, Patrons.Patron.PERSON, "");
        assertTrue(page.contains("from www.example.org, morita-family and tree.ged)"), "keeping Osaka disputes the tree file's birth too, and the page says so: " + page);
        String check = FamilyChecks.forPerson(store, FamilyChecks.check(store));
        assertTrue(check.contains("(from www.example.org, morita-family and tree.ged)"), check);
    }

    @Test
    void aResetOfOneFileTakesBackThatFileAndTheQuestionsWrittenFromIt(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        claim(store, "Genzaburo Endo", "parent-of", "Isamu Endo", "Genzaburo Endo is a parent of Isamu Endo.", "file:///home/me/family-sources/notes.txt");
        claim(store, "Genzaburo Endo", "born-in", "Sendai", "Genzaburo Endo was born in Sendai (1870).", "file:///home/me/family-sources/community-history.pdf");
        claim(store, "Genzaburo Endo", "occupation", "rice merchant", "Genzaburo Endo worked as rice merchant.", "file:///home/me/family-sources/community-history.pdf");
        claim(store, "Isamu Endo", "died-on", "1975", "Isamu Endo died in 1975.", "file:///home/me/family-sources/letters.pdf");
        for (String n : List.of("Genzaburo Endo", "Isamu Endo")) Graph.setKind(store, n, "person");
        String q = FamilyQuestions.around(store, "Genzaburo Endo", 6, 3, true).stream().filter(a -> a.person().equals("Genzaburo Endo")).findFirst().orElseThrow().question();
        assertTrue(q.contains("rice merchant"), q);
        store.frontier("person me (from the family tree)", q);
        Path ledger = store.root().resolve("family").resolve("read-files.tsv");
        Files.createDirectories(ledger.getParent());
        Files.writeString(ledger, "a1\t2026-09-20\t/home/me/family-sources/notes.txt\nb2\t2026-09-20\t/home/me/family-sources/community-history.pdf\nc3\t2026-09-20\t/home/me/family-sources/letters.pdf\n");
        // a word of the folder's name matches no file
        assertTrue(FamilyReset.plan(store, List.of("family-account"), "sources").claims().isEmpty());
        FamilyReset.Plan pdfs = FamilyReset.plan(store, List.of("family-account"), ".pdf");
        assertEquals(2, pdfs.files().size(), "the words are in two names, and the plan says which: " + pdfs.files());
        FamilyReset.Plan book = FamilyReset.plan(store, List.of("family-account"), "community-history.pdf");
        assertEquals(List.of("/home/me/family-sources/community-history.pdf"), book.files());
        assertEquals(2, book.claims().size());
        assertEquals(1, book.waiting(), "the question written from the book's facts");
        FamilyReset.Done done = FamilyReset.apply(store, book);
        assertEquals(1, done.waiting());
        assertTrue(Frontier.read(store).stream().noneMatch(l -> l.open() && l.text().contains("rice merchant")), Frontier.read(store).toString());
        List<String> left = Files.readAllLines(ledger);
        assertEquals(2, left.size(), "only the book is forgotten: " + left);
        assertEquals(2, store.scanFindings().findings().size());
    }

    @Test
    void onePersonKeepsTheNameThatTellsNamesakesApartOnTheCheckAndOnThePage(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        // what an older import left under the bare name: replaced claims, and the aunt's note
        String[] old = {"born-on\t1928", "born-in\tSendai", "died-on\t2001", "married-to\tAiko Sato"};
        for (String o : old) {
            String[] p = o.split("\t");
            Finding f = claim(store, "Kenji Endo", p[0], p[1], "Kenji Endo " + p[0] + " " + p[1] + ".", "file:///home/me/old.ged");
            store.write(new Finding(f.id(), f.title(), f.subjects(), Finding.State.superseded, f.claimType(), f.confidence(), "gedcom-import", f.recordedAt(), f.validAsOf(), f.volatility(), f.reviewBy(),
                    f.sources(), f.supersedes(), f.review(), f.body(), f.triple(), f.notes()));
        }
        claim(store, "Kenji Endo", "occupation", "teacher", "Kenji Endo worked as teacher.", "file:///family/notes.txt");
        claim(store, "Kenji Endo (born 1928)", "born-on", "1928", "Kenji Endo (born 1928) was born in 1928.", "file:///home/me/new.ged");
        claim(store, "Kenji Endo (born 1928)", "died-on", "2001", "Kenji Endo (born 1928) died in 2001.", "file:///home/me/new.ged");
        for (String n : List.of("Kenji Endo", "Kenji Endo (born 1928)")) Graph.setKind(store, n, "person");
        Graph g = Graph.build(store);
        assertTrue(FamilyDecisions.foldsInto(g, store.scanFindings().findings(), "Kenji Endo", "Kenji Endo (born 1928)"));
        assertFalse(FamilyDecisions.foldsInto(g, store.scanFindings().findings(), "Kenji Endo (born 1928)", "Kenji Endo"));
    }

}
