package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A family's GEDCOM file read whole: one person per INDI record however many share a name, every character set older programs
 * write, every name form, the events beyond birth and death, every citation, what is not read listed, a newer copy correcting the
 * older one, the ids family-tree sites give a person, a dry run, and an export that keeps its sources and the living out of sight.
 */
class GedcomTest {

    private static LibraryStore store(Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        return store;
    }

    private static Path ged(Path tmp, String name, String body) throws Exception {
        Path f = tmp.resolve(name);
        Files.writeString(f, "0 HEAD\n1 CHAR UTF-8\n" + body + "0 TRLR\n", StandardCharsets.UTF_8);
        return f;
    }

    private static List<Finding> claims(LibraryStore store, String subject, String predicate) {
        return store.scanFindings().findings().stream().filter(f -> f.triple() != null && f.triple().subject().equals(subject) && f.triple().predicate().equals(predicate)).toList();
    }

    /** Father and son, both Tom Ellis, both born in Leeds; the son may be living, and the father's record comes last in the file. */
    static final String FATHER_AND_SON = "0 @I2@ INDI\n1 NAME Tom /Ellis/\n1 FAMC @F1@\n1 BIRT\n2 DATE 1990\n2 PLAC Leeds\n"
            + "0 @I1@ INDI\n1 NAME Tom /Ellis/\n1 FAMS @F1@\n1 BIRT\n2 DATE 1851\n2 PLAC Leeds\n1 DEAT\n2 DATE 1920\n"
            + "0 @I3@ INDI\n1 NAME Ann /Hart/\n1 FAMS @F1@\n1 BIRT\n2 DATE 1855\n1 DEAT\n2 DATE 1930\n"
            + "0 @F1@ FAM\n1 HUSB @I1@\n1 WIFE @I3@\n1 CHIL @I2@\n";

    @Test
    void twoRecordsOfOneNameAreTwoPeopleNotOneWithThreeParents(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        Path file = ged(tmp, "ellis.ged", FATHER_AND_SON);
        Gedcom.Outcome o = Gedcom.importFile(store, file);
        assertEquals(List.of("The file has 2 people named Tom Ellis. Each is a person of their own: Tom Ellis (born 1990), Tom Ellis (born 1851)."), o.apart());
        Graph g = Graph.build(store);
        Graph.Node son = g.node(g.nodeIdOf("Tom Ellis (born 1990)")), father = g.node(g.nodeIdOf("Tom Ellis (born 1851)"));
        assertNotNull(son); assertNotNull(father);
        assertNull(g.node("tom ellis"), "nobody is filed under the bare name");
        assertTrue(son.mayBeLiving(), "the son may be living although his dead father's record comes after his");
        assertFalse(father.mayBeLiving());
        assertTrue(g.edges().stream().noneMatch(e -> e.from().equals(e.to())), "no self-loop");
        assertEquals(Set.of(father.id(), "ann hart"), Set.copyOf(g.edges().stream().filter(e -> e.predicate().equals("parent-of") && e.to().equals(son.id())).map(Graph.Edge::from).toList()), "two birth parents");
        assertEquals(1, claims(store, "Tom Ellis (born 1990)", "born-in").size(), "the son's birth in the same town as his father's is kept");
        assertEquals(1, claims(store, "Tom Ellis (born 1851)", "born-in").size());
        String checks = FamilyChecks.render(FamilyChecks.check(store));
        assertFalse(checks.contains("birth parents") || checks.contains("their own ancestor"), checks);
        assertTrue(checks.contains("[same-name] Tom Ellis (born 1990) and Tom Ellis (born 1851) share a name and are two people"), checks);
        // the same file again lands on the same people
        Gedcom.Outcome again = Gedcom.importFile(store, file);
        assertEquals(0, again.findings());
        assertEquals(0, again.superseded());
    }

    @Test
    void twoSistersOfOneNameAreToldApartByADeathOrByTheirNumberInTheFile(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        // the first Mari died young, and the next daughter was named after her; a third record of the name has no dates at all
        Gedcom.importFile(store, ged(tmp, "morita.ged", "0 @I1@ INDI\n1 NAME Isamu /Morita/\n1 FAMS @F1@\n1 DEAT\n2 DATE 1901\n"
                + "0 @I2@ INDI\n1 NAME Mari /Morita/\n1 FAMC @F1@\n1 DEAT\n2 DATE 1862\n"
                + "0 @I3@ INDI\n1 NAME Mari /Morita/\n1 FAMC @F1@\n"
                + "0 @F1@ FAM\n1 HUSB @I1@\n1 CHIL @I2@\n1 CHIL @I3@\n"));
        Graph g = Graph.build(store);
        assertNotNull(g.node(g.nodeIdOf("Mari Morita (died 1862)")));
        assertNotNull(g.node(g.nodeIdOf("Mari Morita (I3 in morita.ged)")), g.nodes().toString());
        assertEquals(2, g.edges().stream().filter(e -> e.predicate().equals("parent-of") && e.from().equals("isamu morita")).count(), "both daughters keep their father");
        assertTrue(Files.readString(Gedcom.labelsFile(store)).contains("morita.ged\t@I3@\t\tMari Morita (I3 in morita.ged)"), "the names given are kept for the next import");
    }

    @Test
    void aPersonWhoseBirthYearSetsThemApartFromSomebodyOfTheSameNameInTheLibraryIsFiledApart(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(new FamilyAccount.Fact("Ann Hart", "born-on", "1880", "", "Ann was born in 1880")), List.of()), "file:///notes.txt", "an aunt");
        Path file = ged(tmp, "hart.ged", "0 @I1@ INDI\n1 NAME Ann /Hart/\n1 BIRT\n2 DATE 1851\n1 DEAT\n2 DATE 1920\n0 @I2@ INDI\n1 NAME Tom /Hart/\n1 BIRT\n2 DATE 1853\n");
        int held = store.scanFindings().findings().size();
        String dry = Gedcom.dryRun(store, file);
        assertTrue(dry.contains("Ann Hart in the file was born in 1851, and the Ann Hart already in your library was born in 1880. They are two people, so the one from the file is filed as Ann Hart (born 1851)."), dry);
        assertEquals(held, store.scanFindings().findings().size(), "a dry run writes nothing");
        Gedcom.Outcome o = Gedcom.importFile(store, file);
        assertEquals(1, o.apart().size());
        Graph g = Graph.build(store);
        assertNotNull(g.node(g.nodeIdOf("Ann Hart (born 1851)")));
        assertEquals(1, claims(store, "Ann Hart", "born-on").size(), "the aunt's Ann Hart keeps only her own birth");
    }

    @Test
    void aDryRunSaysWhoIsAlreadyInTheLibraryAndWhy(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(
                new FamilyAccount.Fact("Tom Hale", "born-on", "1850", "", "q"), new FamilyAccount.Fact("Tom Hale", "parent-of", "Kimie Hale", "", "q"),
                new FamilyAccount.Fact("Shoichi Takahashi", "born-on", "1860", "", "q")), List.of()), "file:///notes.txt", "an aunt");
        Path file = ged(tmp, "hale.ged", "0 @I1@ INDI\n1 NAME Tom /Hale/\n1 FAMS @F1@\n1 BIRT\n2 DATE 1851\n1 DEAT\n2 DATE 1920\n"
                + "0 @I2@ INDI\n1 NAME Kimie /Hale/\n1 FAMC @F1@\n1 DEAT\n2 DATE 1950\n"
                + "0 @I3@ INDI\n1 NAME Takahashi, Shoichi\n1 DEAT\n2 DATE 1930\n1 EDUC\n2 PLAC Leeds\n"
                + "0 @F1@ FAM\n1 HUSB @I1@\n1 CHIL @I2@\n");
        int before = store.scanFindings().findings().size();
        String dry = Gedcom.dryRun(store, file);
        assertEquals(before, store.scanFindings().findings().size());
        assertTrue(dry.contains("Tom Hale in the file will be joined with Tom Hale in your library. Both were born around the same time (1851 and 1850). 1 relative is the same for both: Kimie Hale."), dry);
        assertTrue(dry.contains("Takahashi, Shoichi in the file may be Shoichi Takahashi in your library, written another way."), dry);
        assertTrue(dry.contains("1 entry of the kind the file calls EDUC (schooling) was not imported"), dry);
        assertTrue(dry.contains("To import the file, give the same command without --dry."), dry);
        Gedcom.importFile(store, file);
        String again = Gedcom.dryRun(store, file);
        assertTrue(again.contains("3 people were read from this file before.") && !again.contains("will be joined"), again);
    }

    @Test
    void theCharacterSetsOlderProgramsWriteAreRead(@TempDir Path tmp) throws Exception {
        // ANSEL puts the accent before its letter: 0xE2 is the acute accent, so E2 'e' is é
        byte[] ansel = ("0 HEAD\n1 CHAR ANSEL\n0 @I1@ INDI\n1 NAME Ren" + (char) 0xE2 + "ee /Hale/\n1 DEAT\n2 DATE 1900\n0 TRLR\n").getBytes(StandardCharsets.ISO_8859_1);
        Path a = tmp.resolve("ansel.ged"); Files.write(a, ansel);
        Path w = tmp.resolve("ansi.ged"); Files.write(w, "0 HEAD\n1 CHAR ANSI\n0 @I1@ INDI\n1 NAME Zoë /Hart/\n1 DEAT\n2 DATE 1900\n0 TRLR\n".getBytes(Charset.forName("windows-1252")));
        Path u = tmp.resolve("utf16.ged");
        ByteArrayOutputStream b = new ByteArrayOutputStream(); b.write(0xFF); b.write(0xFE);
        b.write("0 HEAD\n1 CHAR UNICODE\n0 @I1@ INDI\n1 NAME 勇 /森田/\n1 DEAT\n2 DATE 1900\n0 TRLR\n".getBytes(StandardCharsets.UTF_16LE));
        Files.write(u, b.toByteArray());
        Path lie = tmp.resolve("lie.ged"); Files.writeString(lie, "0 HEAD\n1 CHAR ANSEL\n0 @I1@ INDI\n1 NAME Endō /Genzaburo/\n1 DEAT\n2 DATE 1900\n0 TRLR\n", StandardCharsets.UTF_8);
        LibraryStore store = store(tmp);
        Gedcom.importFile(store, a);
        Gedcom.importFile(store, w);
        Gedcom.importFile(store, u);
        Gedcom.Outcome o = Gedcom.importFile(store, lie);
        Graph g = Graph.build(store);
        assertNotNull(g.node(g.nodeIdOf("Renée Hale")), g.nodes().stream().map(Graph.Node::label).toList().toString());
        assertNotNull(g.node(g.nodeIdOf("Zoë Hart")));
        assertNotNull(g.node(g.nodeIdOf("森田勇")));
        assertNotNull(g.node(g.nodeIdOf("Endō Genzaburo")));
        assertTrue(o.problems().get(0).contains("says its letters are written in the ANSEL character set, but they are written in UTF-8"), o.problems().toString());
        assertEquals("ANSEL", Gedcom.parse(a).charset());
    }

    @Test
    void everyNameEveryEventAndEveryCitationAndAListOfWhatWasNotRead(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        Gedcom.Outcome o = Gedcom.importFile(store, ged(tmp, "endo.ged",
                "0 @I1@ INDI\n1 NAME Kimie /Endo/\n2 NSFX Jr.\n2 _MARNM Hart\n2 ROMN Kimie /Endō/\n1 NAME 圭子 /遠藤/\n2 TYPE aka\n"
                        + "1 BIRT\n2 DATE Bapt 18 DEC 1690\n2 PLAC Leeds\n"
                        + "1 CENS\n2 DATE 1851\n2 PLAC Bradford\n1 CREM\n2 DATE 1760\n2 PLAC York\n1 PROB\n2 DATE 1761\n2 PLAC York\n"
                        + "1 DEAT\n2 DATE 1760\n2 SOUR @S1@\n3 PAGE folio 12\n3 QUAY 3\n2 SOUR @S2@\n3 PAGE entry 4\n"
                        + "1 EDUC\n2 PLAC Leeds\n1 EDUC\n2 PLAC York\n1 OBJE @M1@\n"
                        + "0 @S1@ SOUR\n1 TITL Parish register of Leeds\n0 @S2@ SOUR\n1 TITL Leeds Mercury\n"));
        Graph g = Graph.build(store);
        Graph.Node kimie = g.node(g.nodeIdOf("Kimie Endo Jr."));
        assertNotNull(kimie, "the suffix is part of the name");
        assertTrue(kimie.aliases().containsAll(List.of("Kimie Hart", "Kimie Endō", "遠藤圭子")), kimie.aliases().toString());
        assertEquals(1, claims(store, "Kimie Endo Jr.", "baptised-in").size(), "a birth date that says it is a baptism's is filed as a baptism");
        assertTrue(claims(store, "Kimie Endo Jr.", "born-in").isEmpty());
        assertTrue(o.problems().contains("The birth date of Kimie Endo Jr. in the file reads \"Bapt 18 DEC 1690\", which is the date of a baptism. It was filed as a baptism, not as a birth."), o.problems().toString());
        assertEquals("Bradford", claims(store, "Kimie Endo Jr.", "lived-in").get(0).triple().object(), "a census counts where the person lived");
        List<String> events = claims(store, "Kimie Endo Jr.", "life-event").stream().map(f -> f.triple().object()).toList();
        assertTrue(events.containsAll(List.of("cremated in York", "will proved in York")), events.toString());
        Finding death = claims(store, "Kimie Endo Jr.", "died-on").get(0);
        assertEquals(2, death.sources().size(), "every citation the file gives is a source of its own");
        assertTrue(death.sources().get(0).edition().contains("cited there: Parish register of Leeds, folio 12; the file rates it direct and primary evidence"), death.sources().get(0).edition());
        assertTrue(death.sources().get(1).edition().contains("Leeds Mercury, entry 4"), death.sources().get(1).edition());
        assertEquals(Evidence.clue, Evidence.of(death), "the file's own rating is its author's word: the claim is still a lead until the record is read");
        assertTrue(o.problems().contains("2 entries of the kind the file calls EDUC (schooling) were not imported, because the library does not read that kind of entry yet."), o.problems().toString());
        assertTrue(o.problems().stream().anyMatch(p -> p.contains("OBJE (pictures and other media)")), o.problems().toString());
        assertEquals("cremated", FamilyLiving.of(claims(store, "Kimie Endo Jr.", "life-event").stream().filter(f -> f.triple().object().startsWith("cremated")).findFirst().orElseThrow()).relation());
    }

    @Test
    void aNewerCopyOfTheFileCorrectsItsOwnDraftsAndNeverWhatThePersonAccepted(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        Path file = tmp.resolve("tree.ged");
        Files.writeString(file, "0 HEAD\n1 DATE 1 MAR 2026\n0 @I1@ INDI\n1 NAME Haru /Takahashi/\n1 BIRT\n2 DATE 1850\n2 PLAC Leeds\n1 DEAT\n2 DATE 1920\n0 @I2@ INDI\n1 NAME Tom /Hart/\n1 BIRT\n2 DATE 1852\n1 DEAT\n2 DATE 1930\n0 TRLR\n", StandardCharsets.UTF_8);
        Gedcom.importFile(store, file);
        Finding oldBirth = claims(store, "Haru Takahashi", "born-in").get(0);
        Finding tomsDeath = claims(store, "Tom Hart", "died-on").get(0);
        store.write(new Finding(tomsDeath.id(), tomsDeath.title(), tomsDeath.subjects(), Finding.State.accepted, tomsDeath.claimType(), tomsDeath.confidence(), tomsDeath.writer(), tomsDeath.recordedAt(),
                tomsDeath.validAsOf(), tomsDeath.volatility(), tomsDeath.reviewBy(), tomsDeath.sources(), tomsDeath.supersedes(), tomsDeath.review(), tomsDeath.body(), tomsDeath.triple(), tomsDeath.notes()));
        // the family corrects the birth year and Tom's death, and exports the tree again over the same file
        Files.writeString(file, "0 HEAD\n1 DATE 9 SEP 2026\n0 @I1@ INDI\n1 NAME Haru /Takahashi/\n1 BIRT\n2 DATE 1851\n2 PLAC Leeds\n1 DEAT\n2 DATE 1920\n0 @I2@ INDI\n1 NAME Tom /Hart/\n1 BIRT\n2 DATE 1852\n1 DEAT\n2 DATE 1931\n0 TRLR\n", StandardCharsets.UTF_8);
        Gedcom.Outcome o = Gedcom.importFile(store, file);
        assertEquals(1, o.superseded(), "only the draft is replaced");
        assertEquals(Finding.State.superseded, store.finding(oldBirth.id()).state());
        Finding newBirth = claims(store, "Haru Takahashi", "born-in").stream().filter(f -> f.state() == Finding.State.draft).findFirst().orElseThrow();
        assertEquals(List.of(oldBirth.id()), newBirth.supersedes());
        assertTrue(newBirth.body().startsWith("Haru Takahashi was born in Leeds (1851)."), newBirth.body());
        assertEquals(Finding.State.accepted, store.finding(tomsDeath.id()).state(), "what the person accepted stays");
        assertEquals(2, claims(store, "Tom Hart", "died-on").size(), "and the new date sits beside it for the person to see");
        String checks = FamilyChecks.render(FamilyChecks.check(store));
        assertFalse(checks.contains("two birth years"), "the replaced draft no longer counts: " + checks);
        // an older copy of the file, read after the newer one, corrects nothing
        Path old = tmp.resolve("old.ged");
        Files.writeString(old, "0 HEAD\n1 DATE 1 JAN 2020\n0 @I1@ INDI\n1 NAME Haru /Takahashi/\n1 BIRT\n2 DATE 1849\n2 PLAC Leeds\n0 TRLR\n", StandardCharsets.UTF_8);
        Files.copy(old, file, StandardCopyOption.REPLACE_EXISTING);
        assertEquals(0, Gedcom.importFile(store, file).superseded());
    }

    @Test
    void aFamilyTreeSitesIdForAPersonTellsWhoIsWho(@TempDir Path tmp) throws Exception {
        assertEquals(new PersonIds.Id("familysearch", "LZDP-6M9", "https://www.familysearch.org/tree/person/details/LZDP-6M9"), PersonIds.fromUrl("https://www.familysearch.org/tree/person/details/LZDP-6M9"));
        assertEquals("Ellis-12", PersonIds.fromUrl("https://www.wikitree.com/wiki/Ellis-12").id());
        assertEquals("123456", PersonIds.fromUrl("https://www.findagrave.com/memorial/123456/tom-hale").id());
        assertEquals("6000000012345678901", PersonIds.fromUrl("https://www.geni.com/people/Tom-Hale/6000000012345678901").id());
        assertNull(PersonIds.fromUrl("https://example.org/people/1"));
        LibraryStore store = store(tmp);
        Gedcom.importFile(store, ged(tmp, "a.ged", "0 @I1@ INDI\n1 NAME Tom /Hale/\n1 _FSFTID LZDP-6M9\n1 DEAT\n2 DATE 1900\n"
                + "0 @I2@ INDI\n1 NAME Ann /Ellis/\n1 EXID Ellis-12\n2 TYPE https://www.wikitree.com/wiki/\n1 DEAT\n2 DATE 1900\n"
                + "0 @I3@ INDI\n1 NAME Isamu /Endo/\n1 EXID Endo-7\n2 TYPE https://www.wikitree.com/wiki/\n1 DEAT\n2 DATE 1900\n"));
        Gedcom.importFile(store, ged(tmp, "b.ged", "0 @I1@ INDI\n1 NAME Thomas /Hale/\n1 _FSFTID LZDP-6M9\n1 DEAT\n2 DATE 1900\n"
                + "0 @I2@ INDI\n1 NAME Isamu /Endō/\n1 EXID Endo-9\n2 TYPE https://www.wikitree.com/wiki/\n1 DEAT\n2 DATE 1900\n"));
        Map<String, Set<PersonIds.Id>> ids = PersonIds.all(store, Graph.build(store));
        assertEquals("wikitree", ids.get("ann ellis").iterator().next().site());
        String checks = FamilyChecks.render(FamilyChecks.check(store));
        assertTrue(checks.contains("[same-person?] Tom Hale and Thomas Hale have the same FamilySearch id LZDP-6M9, so they are very likely one person"), checks);
        assertTrue(checks.contains("[same-name] Isamu Endo and Isamu Endō share a name and are two people: they have two different ids on one family-tree site (WikiTree id Endo-7 and Endo-9)."), checks);
        // a page whose address carries the id the family's file gives is marked on the who-is-who question
        FamilyIdentity.Question q = FamilyIdentity.find(store, Graph.build(store), "Tom Hale", query -> List.of(
                new FamilyIdentity.Page("https://www.familysearch.org/tree/person/details/LZDP-6M9", "Tom Hale 1840-1900", "Tom Hale"),
                new FamilyIdentity.Page("https://films.example/tom-hale", "Tom Hale, actor", "An actor.")), null);
        assertTrue(q.candidates().get(0).matches().contains("FamilySearch id LZDP-6M9"), q.candidates().toString());
    }

    @Test
    void theExportCarriesSourcesAndDraftsLeavesOutDisputesAndWritesEveryoneInFull(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        // Mari and her father Isamu may be living; her grandparents are reached through them
        Gedcom.importFile(store, ged(tmp, "endo.ged", "0 @I1@ INDI\n1 NAME Mari /Endo/\n1 FAMC @F1@\n1 BIRT\n2 DATE 1990\n2 PLAC Sendai\n1 _TODO Find her school records\n"
                + "0 @I2@ INDI\n1 NAME Isamu /Endo/\n1 FAMS @F1@\n1 FAMC @F2@\n1 BIRT\n2 DATE 1960\n"
                + "0 @I3@ INDI\n1 NAME Genzaburo /Endo/\n1 FAMS @F2@\n1 BIRT\n2 DATE 1890\n2 PLAC Sendai\n2 SOUR @S1@\n3 PAGE entry 3\n1 DEAT\n2 DATE 1950\n1 _TODO Where is Genzaburo Endo's grave?\n"
                + "0 @I4@ INDI\n1 NAME Kimie /Morita/\n1 FAMS @F2@\n1 DEAT\n2 DATE 1970\n"
                + "0 @I5@ INDI\n1 NAME Tom /Ellis/\n1 BIRT\n2 DATE 1880\n2 PLAC Sendai\n1 DEAT\n2 DATE 1940\n"
                + "0 @F1@ FAM\n1 HUSB @I2@\n1 CHIL @I1@\n0 @F2@ FAM\n1 HUSB @I3@\n1 WIFE @I4@\n1 MARR\n2 DATE 1920\n1 CHIL @I2@\n"
                + "0 @S1@ SOUR\n1 TITL Sendai family register\n"));
        assertTrue(Frontier.read(store).stream().anyMatch(l -> l.text().equals("Genzaburo Endo: Where is Genzaburo Endo's grave?")), "a research note in the file becomes an open question");
        assertTrue(Frontier.read(store).stream().anyMatch(l -> l.text().equals("Mari Endo: Find her school records")), "and so does one about a person who may be living");
        // a disputed claim is left out, and an accepted one is not called a draft
        Finding marr = claims(store, "Genzaburo Endo", "married-to").get(0);
        store.write(new Finding(marr.id(), marr.title(), marr.subjects(), Finding.State.accepted, marr.claimType(), marr.confidence(), marr.writer(), marr.recordedAt(), marr.validAsOf(), marr.volatility(), marr.reviewBy(), marr.sources(), marr.supersedes(), marr.review(), marr.body(), marr.triple(), marr.notes()));
        FamilyLivingRuleTest.drafted(store, "Kimie Morita", "born-in", "Kyoto", "Kimie Morita was born in Kyoto (1895).");
        Finding wrong = FamilyLivingRuleTest.drafted(store, "Kimie Morita", "lived-in", "Osaka", "Kimie Morita lived in Osaka (1930).");
        store.write(new Finding(wrong.id(), wrong.title(), wrong.subjects(), Finding.State.disputed, wrong.claimType(), wrong.confidence(), wrong.writer(), wrong.recordedAt(), wrong.validAsOf(), wrong.volatility(), wrong.reviewBy(), wrong.sources(), wrong.supersedes(), wrong.review(), wrong.body(), wrong.triple(), wrong.notes()));

        String out = Gedcom.export(store, "Mari Endo");
        assertTrue(out.contains("1 NAME Mari /Endo/\n1 BIRT\n2 DATE 1990\n2 PLAC Sendai") && out.contains("1 NAME Isamu /Endo/\n1 BIRT\n2 DATE 1960"), "the living are written in full: " + out);
        assertFalse(out.contains("RESN") || out.contains("1 NAME Living"), out);
        assertTrue(out.contains("1 NAME Genzaburo /Endo/\n") && out.contains("1 NAME Kimie /Morita/\n"), "the ancestors reached through living people arrive: " + out);
        assertFalse(out.contains("Tom Ellis"), "somebody born in the same town is not family");
        assertTrue(out.contains("1 BIRT\n2 DATE 1890\n2 PLAC Sendai\n2 SOUR @S"), out);
        assertTrue(out.contains("3 PAGE endo.ged, GEDCOM @I3@ BIRT; cited there: Sendai family register, entry 3"), out);
        assertTrue(out.contains("2 NOTE A draft: nobody has checked this against a record yet."), out);
        assertTrue(out.contains("1 MARR\n2 DATE 1920\n2 SOUR @S1@\n3 PAGE endo.ged, GEDCOM @F2@ MARR\n1 CHIL"), "an accepted claim has no draft note: " + out);
        assertTrue(out.contains("0 @S1@ SOUR\n1 TITL endo.ged\n1 REFN researchzosho:endo.ged"), out);
        assertTrue(out.contains("Kyoto") && !out.contains("Osaka"), "a disputed claim is not exported: " + out);
        assertTrue(out.contains("1 _TODO Where is Genzaburo Endo's grave?"), out);
        assertEquals(2, out.split(" FAM\n", -1).length - 1, "one couple, and a father whose wife is not in the file with his child: " + out);
        assertTrue(out.contains(" FAM\n1 HUSB @I2@\n1 CHIL @I1@\n"), "a parent alone keeps the link to the child: " + out);
        assertEquals(1, out.split("1 FAMC @F1@\n", -1).length - 1, "a child of the couple is in their family once: " + out);
    }

    @Test
    void aChildIsExportedUnderTheCoupleWhoAreBothItsParentsAndNoOtherMarriage(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        // Tom Hale married twice: Isamu is Ann's son, Mari is Mary's daughter, and nobody knows Genzo's mother
        Gedcom.importFile(store, ged(tmp, "hale.ged", "0 @I1@ INDI\n1 NAME Tom /Hale/\n1 SEX M\n1 FAMS @F1@\n1 FAMS @F2@\n1 BIRT\n2 DATE 1880\n1 DEAT\n2 DATE 1950\n"
                + "0 @I2@ INDI\n1 NAME Ann /Hart/\n1 SEX F\n1 FAMS @F1@\n1 BIRT\n2 DATE 1882\n1 DEAT\n2 DATE 1912\n"
                + "0 @I3@ INDI\n1 NAME Mary /Ellis/\n1 SEX F\n1 FAMS @F2@\n1 BIRT\n2 DATE 1890\n1 DEAT\n2 DATE 1960\n"
                + "0 @I4@ INDI\n1 NAME Isamu /Hale/\n1 FAMC @F1@\n1 BIRT\n2 DATE 1910\n"
                + "0 @I5@ INDI\n1 NAME Mari /Hale/\n1 FAMC @F2@\n1 BIRT\n2 DATE 1920\n"
                + "0 @I6@ INDI\n1 NAME Genzo /Hale/\n1 FAMC @F3@\n1 BIRT\n2 DATE 1925\n"
                + "0 @F1@ FAM\n1 HUSB @I1@\n1 WIFE @I2@\n1 MARR\n2 DATE 1909\n1 CHIL @I4@\n"
                + "0 @F2@ FAM\n1 HUSB @I1@\n1 WIFE @I3@\n1 MARR\n2 DATE 1915\n1 CHIL @I5@\n"
                + "0 @F3@ FAM\n1 HUSB @I1@\n1 CHIL @I6@\n"));
        String out = Gedcom.export(store, "Tom Hale");
        // the file read back into a library of its own gives each child the parents the first library holds, and no more
        LibraryStore again = store(tmp.resolve("again"));
        Path back = tmp.resolve("back.ged");
        Files.writeString(back, out, StandardCharsets.UTF_8);
        Gedcom.importFile(again, back);
        Graph g = Graph.build(again);
        for (String[] kid : new String[][]{{"Isamu Hale", "ann hart"}, {"Mari Hale", "mary ellis"}, {"Genzo Hale", null}}) {
            String id = g.nodeIdOf(kid[0]);
            Set<String> parents = Set.copyOf(g.edges().stream().filter(e -> e.predicate().equals("parent-of") && e.to().equals(id)).map(Graph.Edge::from).toList());
            assertEquals(kid[1] == null ? Set.of("tom hale") : Set.of("tom hale", kid[1]), parents, kid[0] + "'s parents, from " + out);
        }
        // each child's FAMC names the one family that lists it
        for (String block : out.split("\n0 ")) {
            if (!block.contains(" INDI\n")) continue;
            String who = block.substring(0, block.indexOf(' '));
            for (String famc : block.lines().filter(l -> l.startsWith("1 FAMC ")).map(l -> l.substring(7)).toList())
                assertTrue(out.contains("0 " + famc + " FAM\n") && (out.substring(out.indexOf("0 " + famc + " FAM\n")).split("\n0 ", 2)[0] + "\n").contains("1 CHIL " + who + "\n"), who + " in " + famc + ": " + out);
            assertTrue(block.lines().filter(l -> l.startsWith("1 FAMC ")).count() <= 1, who + " is the child of one family: " + out);
        }
    }
}
