package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.net.URLDecoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The living rule, which the graph works out from the claims each time it is read: a year somewhere in a research run's sentence is
 * not the date of the claim, a date in the brackets the family reader and the import write is, and a date disputed or accepted counts
 * at once. Whether a person may be living decides what the research asks of them, never who sees them.
 */
class FamilyLivingRuleTest {

    private static Finding claim(LibraryStore store, String s, String p, String o, String line) throws Exception { return claim(store, s, p, o, line, "model:research"); }

    private static Finding claim(LibraryStore store, String s, String p, String o, String line, String writer) throws Exception {
        Finding x = new Finding(store.nextFindingId(s + " " + p + " " + o), line, List.of(), Finding.State.accepted, Finding.ClaimType.extraction,
                Finding.Confidence.medium, writer, Instant.now().toString(), LocalDate.now().toString(), Finding.Volatility.stable, "",
                List.of(new Finding.Source("https://example.org/ellis", "", "a page")), List.of(), null, line + "\n", new Finding.Triple(s, p, o), List.of());
        store.write(x);
        return x;
    }

    /** A draft claim as the family's own notes give it: a line an aunt told, with the notes as its source. */
    static Finding drafted(LibraryStore store, String s, String p, String o, String line) throws Exception {
        Finding x = new Finding(store.nextFindingId(s + " " + p + " " + o), line, List.of(), Finding.State.draft, Finding.ClaimType.extraction,
                Finding.Confidence.medium, "test", Instant.now().toString(), LocalDate.now().toString(), Finding.Volatility.stable, "",
                List.of(new Finding.Source("file:///family/notes.txt", "as told by an aunt", "the family's own account")), List.of(), null, line + "\n",
                new Finding.Triple(s, p, o), List.of());
        store.write(x);
        return x;
    }

    @Test
    void aYearElsewhereInASentenceDoesNotDateThePeopleTheClaimNames(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Finding kin = claim(store, "Kimie Hale", "relative-of", "Tom Ellis", "Kimie Hale is a great-granddaughter of Tom Ellis, who emigrated to Hawaii in 1885.");
        Finding shop = claim(store, "髙橋正一", "works-at", "髙橋商店", "髙橋正一 works at 髙橋商店, which his great-grandfather opened in 明治30年.");
        for (String n : List.of("Kimie Hale", "Tom Ellis", "髙橋正一")) Graph.setKind(store, n, "person");
        assertEquals("", FamilyLiving.of(kin).date(), "the year is the emigration's, not the claim's");
        assertEquals("", FamilyLiving.of(shop).date());
        Graph g = Graph.build(store);
        for (String n : List.of("Kimie Hale", "Tom Ellis", "髙橋正一")) assertTrue(g.node(g.nodeIdOf(n)).mayBeLiving(), n + " may be living");

        // a date in the brackets a family file puts at the end still dates the claim
        claim(store, "Tom Ellis", "born-in", "Leeds", "Tom Ellis was born in Leeds (1880).", "family-account");
        g = Graph.build(store);
        assertFalse(g.node(g.nodeIdOf("Tom Ellis")).mayBeLiving());
        assertTrue(g.node(g.nodeIdOf("Kimie Hale")).mayBeLiving(), "the born-in of her relative does not date her");
    }

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
    void aBracketAtTheEndOfAResearchRunsSentenceIsNotTheClaimsDate(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Finding lifespan = claim(store, "Kimie Hale", "relative-of", "Tom Ellis", "Kimie Hale is a great-granddaughter of the miner Tom Ellis (1845–1901).");
        Finding born = claim(store, "Ann Hale", "great-granddaughter of", "Tom Ellis", "Ann Hale is a great-granddaughter of Tom Ellis (born 1860).");
        for (String n : List.of("Kimie Hale", "Ann Hale", "Tom Ellis")) Graph.setKind(store, n, "person");
        assertEquals("", FamilyLiving.of(lifespan).date(), "the lifespan is Tom Ellis's, written after his name");
        assertEquals("", FamilyLiving.of(born).date());
        for (String n : List.of("Kimie Hale", "Ann Hale")) assertTrue(node(store, n).mayBeLiving(), n);
    }

    @Test
    void aRelationsYearDatesNobodyAndARelativesArithmeticDoesNotOutweighAnOwnBirth(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        // the reader put the emigration year beside the relation
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(new FamilyAccount.Person("Kimie Hale", "", List.of())), List.of(
                fact("Kimie Hale", "relative-of", "Tom Ellis", "1885"), fact("Kimie Hale", "child-of", "Mary Ellis", ""), fact("Mary Ellis", "born-in", "York", "1945"),
                fact("Ann Hale", "sibling-of", "Tom Ellis", "1885")), List.of()),
                "file:///family/notes.txt", "Kimie Hale");
        assertTrue(node(store, "Mary Ellis").mayBeLiving(), "born 1945: her own birth says more than a relative's arithmetic");
        assertTrue(node(store, "Kimie Hale").mayBeLiving());
        for (String n : List.of("Tom Ellis", "Ann Hale")) assertTrue(node(store, n).mayBeLiving(), "a year beside a relation dates nobody: " + n);
        // a later dated fact places Kimie in the past, and her mother's own birth still keeps Mary Ellis where it puts her
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(fact("Kimie Hale", "lived-in", "Hilo", "1885")), List.of()), "file:///family/letters.txt", "an aunt");
        assertFalse(node(store, "Kimie Hale").mayBeLiving(), Files.readString(Graph.nodesFile(store)));
        assertTrue(node(store, "Mary Ellis").mayBeLiving());
    }

    @Test
    void aPersonAnOlderVersionMarkedCountsAsTheDatesSayAndTheOldWordsGo(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(fact("Tom Ellis", "born-on", "1900", ""), fact("Tom Ellis", "died-on", "1970", "")), List.of()), "file:///family/book.txt", "a book");
        Finding kin = claim(store, "Tom Ellis", "child-of", "Genzo Hale", "Tom Ellis is a child of Genzo Hale.", "family-account");
        store.write(new Finding(kin.id(), kin.title(), kin.subjects(), Finding.State.disputed, kin.claimType(), kin.confidence(), kin.writer(), kin.recordedAt(), kin.validAsOf(),
                kin.volatility(), kin.reviewBy(), kin.sources(), kin.supersedes(), kin.review(), kin.body(), kin.triple(), kin.notes()));
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(fact("Genzo Hale", "born-on", "1940", "")), List.of()), "file:///family/notes.txt", "an aunt");
        // what an older version wrote: Tom Ellis shown, Genzo Hale shown by the father claim the owner disputed since
        String lines = Files.readString(Graph.nodesFile(store)).replace("— person: Genzo Hale", "— person, kept public: Genzo Hale").replace("— person: Tom Ellis", "— person, private: Tom Ellis");
        Files.writeString(Graph.nodesFile(store), lines, StandardCharsets.UTF_8);
        assertTrue(node(store, "Genzo Hale").mayBeLiving(), "born 1940, and nothing that counts says he died: " + Files.readString(Graph.nodesFile(store)));
        assertFalse(node(store, "Tom Ellis").mayBeLiving(), "died 1970, whatever the old line said");
        assertTrue(FamilyQuestions.around(store, "Genzo Hale", 6, 3, false) == null || FamilyQuestions.around(store, "Genzo Hale", 6, 3, false).stream().noneMatch(a -> a.person().equals("Genzo Hale")));
        assertFalse(Gedcom.export(store, "Tom Ellis").contains("Genzo"), "the disputed father claim joins nobody");
        // the next write of the file leaves the old words out
        Graph.setKind(store, "Tom Ellis", "person");
        String now = Files.readString(Graph.nodesFile(store));
        assertTrue(now.contains("— person: Genzo Hale") && now.contains("— person: Tom Ellis") && !now.contains("kept public") && !now.contains("private"), now);
    }

    @Test
    void whenTheDateThatDecidedIsDisputedThePersonMayBeLivingAgainAtOnce(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Path ged = tmp.resolve("t6.ged");
        Files.writeString(ged, "0 HEAD\n0 @I1@ INDI\n1 NAME Kimie /Hale/\n1 BIRT\n2 DATE 1900\n1 FAMC @F1@\n0 @I2@ INDI\n1 NAME Genzaburo /Hale/\n1 FAMS @F1@\n0 @F1@ FAM\n1 HUSB @I2@\n1 CHIL @I1@\n0 TRLR\n", StandardCharsets.UTF_8);
        Gedcom.importFile(store, ged);
        assertFalse(node(store, "Kimie Hale").mayBeLiving(), "born 1900, as the file says");
        Finding birth = store.scanFindings().findings().stream().filter(f -> f.triple() != null && f.triple().predicate().equals("born-on")).findFirst().orElseThrow();

        // the owner disputes the year: nothing else places her or her father in the past
        Council council = new Council(store);
        council.dispute(birth.id(), "she was born in 2000");
        assertTrue(node(store, "Kimie Hale").mayBeLiving() && node(store, "Genzaburo Hale").mayBeLiving(), Files.readString(Graph.nodesFile(store)));

        // accepted again, the year counts again
        council.accept(birth.id());
        assertFalse(node(store, "Kimie Hale").mayBeLiving());

        // a death a research run filed counts the moment it is filed
        council.dispute(birth.id(), "she was born in 2000");
        assertTrue(node(store, "Kimie Hale").mayBeLiving());
        drafted(store, "Kimie Hale", "died-on", "1990", "Kimie Hale died in 1990.");
        assertFalse(node(store, "Kimie Hale").mayBeLiving(), Files.readString(Graph.nodesFile(store)));
    }

    @Test
    void keepingTheOtherYearOnTheDecisionsPageCountsAtOnce(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Path ged = tmp.resolve("t6.ged");
        Files.writeString(ged, "0 HEAD\n0 @I1@ INDI\n1 NAME Kimie /Hale/\n1 BIRT\n2 DATE 1900\n0 TRLR\n", StandardCharsets.UTF_8);
        Gedcom.importFile(store, ged);
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(fact("Kimie Hale", "born-on", "2000", "")), List.of()), "file:///family/notes.txt", "an aunt");
        FamilyDecisions.Clash c = FamilyDecisions.clashes(store).get(0);
        Finding twoThousand = c.one().triple().object().contains("2000") ? c.one() : c.other();
        String form = twoThousand == c.one() ? "keep1" : "keep2";
        String done = URLDecoder.decode(DecisionsPage.post(store, Patrons.Patron.PERSON, Map.of("kind", "clash", "one", c.one().id(), "other", c.other().id(), "do", form)), StandardCharsets.UTF_8);
        assertTrue(node(store, "Kimie Hale").mayBeLiving(), Files.readString(Graph.nodesFile(store)));
        assertTrue(done.contains("is accepted") && !done.contains("private"), done);
    }

    @Test
    void anAgeInARecordDatesThePersonByTheRecordsYear(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(new FamilyAccount.Fact("Tom Hale", "aged", "18", "1880", "Tom Hale, 18, labourer")), List.of()), "file:///family/census.txt", "a census");
        Finding aged = store.scanFindings().findings().stream().filter(f -> f.triple() != null && f.triple().predicate().equals("aged")).findFirst().orElseThrow();
        assertEquals(1880, FamilyDate.latestYear(FamilyLiving.of(aged).date()), "the age is not taken for a name: " + aged.body());
        Graph g = Graph.build(store);
        assertFalse(g.node(g.nodeIdOf("Tom Hale")).mayBeLiving(), "a record of 1880 places him more than a lifetime back");
    }

    @Test
    void theLatestYearADateAllowsDecidesAndAfterSetsNoEnd() {
        assertEquals(1930, FamilyDate.latestYear("BET 1905 AND 1930"));
        assertEquals(1930, FamilyDate.latestYear("between 1905 and 1930"));
        assertEquals(1910, FamilyDate.latestYear("BEF 1910"));
        assertEquals(1885 + FamilyDate.ABOUT_YEARS, FamilyDate.latestYear("about 1885"), "an about date reaches as far as the date checks let it");
        assertEquals(1830, FamilyDate.latestYear("EST 1825"));
        assertEquals(1910, FamilyDate.latestYear("AFT 1900 BEF 1910"), "a date read only in part still errs toward living");
        assertEquals(1907, FamilyDate.latestYear("明治40年"));
        assertNull(FamilyDate.latestYear("AFT 1900"), "born after 1900: could be born any time since");
        assertNull(FamilyDate.latestYear("明治40年以降"));
        assertNull(FamilyDate.latestYear("the spring"));
    }

    @Test
    void oneLivingRuleForAGedcomFile(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Path ged = tmp.resolve("takahashi.ged");
        Files.writeString(ged, "0 HEAD\n1 CHAR UTF-8\n"
                + "0 @I1@ INDI\n1 NAME Shoichi /Takahashi/\n1 BIRT\n2 DATE BET 1905 AND 1930\n"
                + "0 @I2@ INDI\n1 NAME Kimie /Takahashi/\n1 BIRT\n2 DATE AFT 1900\n"
                + "0 @I3@ INDI\n1 NAME Haru /Morita/\n1 CHR\n2 DATE 1850\n"
                + "0 @I4@ INDI\n1 NAME Tom /Ellis/\n1 FAMS @F1@\n"
                + "0 @I5@ INDI\n1 NAME Ann /Ellis/\n1 FAMS @F1@\n"
                + "0 @I6@ INDI\n1 NAME Isamu /Morita/\n1 FAMS @F2@\n"
                + "0 @I7@ INDI\n1 NAME Mari /Morita/\n1 FAMC @F2@\n1 BIRT\n2 DATE 1880\n2 PLAC Sendai\n"
                + "0 @I8@ INDI\n1 NAME Genzaburo /Hart/\n1 CREM\n"
                + "0 @I9@ INDI\n1 NAME Kimie /Hale/\n1 BIRT\n2 DATE BEF 1880\n"
                + "0 @F1@ FAM\n1 HUSB @I4@\n1 WIFE @I5@\n1 MARR\n2 DATE 1870\n"
                + "0 @F2@ FAM\n1 HUSB @I6@\n1 CHIL @I7@\n"
                + "0 TRLR\n", StandardCharsets.UTF_8);
        Gedcom.importFile(store, ged);
        assertTrue(living(store, "shoichi takahashi"), "born as late as 1930: may be living");
        assertTrue(living(store, "kimie takahashi"), "born after 1900: may be living");
        assertFalse(living(store, "haru morita"), "christened in 1850");
        assertFalse(living(store, "tom ellis"), "married in 1870");
        assertFalse(living(store, "ann ellis"), "married in 1870");
        assertFalse(living(store, "isamu morita"), "the father of somebody born in 1880");
        assertFalse(living(store, "genzaburo hart"), "cremated, with no date");
        assertFalse(living(store, "kimie hale"), "born before 1880");
    }

    private static boolean living(LibraryStore store, String id) throws Exception {
        Graph.Node n = Graph.build(store).node(id);
        assertNotNull(n, id + " is in the graph");
        return n.mayBeLiving();
    }

    @Test
    void oneLivingRuleAcrossTheLibrary(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        // an aunt's notes name a mother with no dates, and a couple married "after 1900"
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(
                new FamilyAccount.Fact("Ann Hart", "parent-of", "Tom Hart", "", "Ann was Tom's mother"),
                new FamilyAccount.Fact("Kimie Hale", "married-to", "Shoichi Hale", "after 1900", "Kimie married Shoichi after 1900")), List.of()), "file:///notes.txt", "an aunt");
        Graph g = Graph.build(store);
        assertTrue(g.node("ann hart").mayBeLiving(), "no dates: the safe side");
        assertTrue(g.node("kimie hale").mayBeLiving() && g.node("shoichi hale").mayBeLiving(), "married after 1900: that sets no end, so they may be living");
        // a GEDCOM file then dates Tom's birth, with a place: the claim is 'born in Leeds', and its year is in its words
        Path ged = tmp.resolve("hart.ged");
        Files.writeString(ged, "0 HEAD\n1 CHAR UTF-8\n0 @I1@ INDI\n1 NAME Tom /Hart/\n1 BIRT\n2 DATE 1880\n2 PLAC Leeds\n0 TRLR\n", StandardCharsets.UTF_8);
        Gedcom.importFile(store, ged);
        // the rule reads the year in a born-in claim and carries it to the mother
        g = Graph.build(store);
        assertFalse(g.node("ann hart").mayBeLiving());
        assertTrue(g.node("kimie hale").mayBeLiving(), "still nothing puts her a lifetime back");
    }

    @Test
    void theRuleSaysOfEachPersonOnlyWhetherTheyMayBeLiving() {
        Map<String, Boolean> living = FamilyLiving.decide(List.of(
                new FamilyLiving.Claim("Tom Hart", "born-in", "Leeds", "1880"),
                new FamilyLiving.Claim("Ann Hart", "parent-of", "Tom Hart", ""),
                new FamilyLiving.Claim("Kimie Hale", "born-in", "Kure", "2001")), x -> x);
        assertEquals(Map.of("Tom Hart", false, "Ann Hart", false, "Kimie Hale", true), living, "a place is nobody, and the mother is dated through her son");
    }
}
