package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What a family fact rests on: a second source for a fact already held is kept with it, the fact rests on the best of all the claims
 * that say it, a fact the person disputed or retired is not filed again when it comes back, and a source the person doubts shows every
 * fact that rests on it.
 */
class FamilySourcesTest {

    static final String RECORD = "https://www.loc.gov/item/sn00000001/1851-06-01/ed-1/seq-4/";

    private static LibraryStore store(Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        return store;
    }

    private static FamilyAccount.Fact fact(String s, String r, String o, String date, String quote) { return new FamilyAccount.Fact(s, r, o, date, quote); }

    private static FamilyAccount.Read read(FamilyAccount.Fact... facts) { return new FamilyAccount.Read(List.of(), List.of(facts), List.of()); }

    private static Finding claim(LibraryStore store, String subject, String predicate) {
        return store.scanFindings().findings().stream().filter(f -> f.triple() != null && f.triple().subject().equals(subject) && f.triple().predicate().equals(predicate)).findFirst().orElseThrow();
    }

    @Test
    void aSecondSourceForAFactAlreadyHeldIsKeptWithItAndTheFactRestsOnTheBest(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        FamilyAccount.file(store, read(fact("Tom Ellis", "born-in", "Leeds", "1851", "Tom Ellis was born in Leeds in 1851."), fact("Tom Ellis", "died-in", "York", "1920", "He died in York in 1920."),
                fact("Tom Ellis", "child-of", "Ann Hart", "", "Tom was Ann Hart's child.")), "file:///family/notes.txt", "an aunt");
        FamilyAccount.Outcome o = FamilyAccount.file(store, read(fact("Tom Ellis", "born-in", "Leeds", "1851", "ELLIS, Tom, born at Leeds")), RECORD, "a newspaper");
        assertEquals(0, o.claims(), "the fact is held: no second claim");
        assertEquals(1, o.sourcesAdded(), "but the newspaper is kept as its source");
        Finding born = claim(store, "Tom Ellis", "born-in");
        assertEquals(List.of("file:///family/notes.txt", RECORD), born.sources().stream().map(Finding.Source::locator).toList());
        assertEquals(Finding.State.draft, born.state(), "a family draft stays a draft: the family decides");
        assertTrue(born.notes().stream().anyMatch(n -> n.kind().equals("source-added") && n.text().contains(RECORD) && n.text().contains("ELLIS, Tom, born at Leeds")), born.notes().toString());
        assertEquals(Evidence.record, Evidence.of(born));
        // reading the newspaper again adds nothing
        assertEquals(0, FamilyAccount.file(store, read(fact("Tom Ellis", "born-in", "Leeds", "1851", "ELLIS, Tom, born at Leeds")), RECORD, "a newspaper").sourcesAdded());
        assertEquals(2, claim(store, "Tom Ellis", "born-in").sources().size());

        // a research run that filed the same fact as a claim of its own, and a parent written the other way round, from a record
        store.write(new Finding("F-0100-tom-ellis-died-in-york", "Tom Ellis died in York", List.of(), Finding.State.draft, Finding.ClaimType.extraction, Finding.Confidence.medium,
                "model:test", Instant.now().toString(), "2026-09-22", Finding.Volatility.stable, "", List.of(new Finding.Source(RECORD, "n/a", "cited by I-0001")), List.of(), null,
                "Tom Ellis died in York (1920).\n", new Finding.Triple("Tom Ellis", "died-in", "York"), List.of()));
        store.write(new Finding("F-0101-ann-hart-parent-of-tom-ellis", "Ann Hart is a parent of Tom Ellis", List.of(), Finding.State.draft, Finding.ClaimType.extraction, Finding.Confidence.medium,
                "model:test", Instant.now().toString(), "2026-09-22", Finding.Volatility.stable, "", List.of(new Finding.Source(RECORD, "n/a", "cited by I-0001")), List.of(), null,
                "Ann Hart is a parent of Tom Ellis.\n", new Finding.Triple("Ann Hart", "parent-of", "Tom Ellis"), List.of()));
        Graph g = Graph.build(store);
        Map<String, Finding> all = new LinkedHashMap<>();
        for (Finding f : store.scanFindings().findings()) all.put(f.id(), f);
        assertEquals(List.of(), FamilyQuestions.cluesOnly(g, all, g.nodeIdOf("Tom Ellis")), "every fact about him is also in a record, whichever claim carries it");
        List<FamilyLife.Line> life = FamilyLife.of(store, "Tom Ellis");
        assertTrue(life.stream().allMatch(l -> l.evidence().equals("record")), life.toString());
        FamilyTree.Tree tree = FamilyTree.around(store, "Tom Ellis", 2, 2);
        assertTrue(tree.links().stream().allMatch(l -> l.evidence().equals("record")), "the account's child-of and the record's parent-of are one fact: " + tree.links());
    }

    @Test
    void aFactThePersonDisputedOrRetiredIsNotFiledAgainWhenItComesBack(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        FamilyAccount.file(store, read(fact("Kimie Hale", "child-of", "Genzaburo Hale", "", "Kimie was Genzaburo Hale's child."), fact("Kimie Hale", "born-in", "Sendai", "1901", "Kimie Hale was born in Sendai in 1901.")),
                "file:///family/notes.txt", "an aunt");
        Council council = new Council(store);
        Finding parent = claim(store, "Kimie Hale", "child-of");
        council.dispute(parent.id(), "the book mixes up two families");
        council.retire(claim(store, "Kimie Hale", "born-in").id());
        FamilyAccount.Outcome o = FamilyAccount.file(store, read(fact("Kimie Hale", "child-of", "Genzaburo Hale", "", "Kimie, child of Genzaburo Hale"), fact("Kimie Hale", "born-in", "Sendai", "1901", "born in Sendai, 1901")),
                "https://example.org/hale-family", "a page");
        assertEquals(0, o.claims(), o.dropped().toString());
        assertEquals(0, o.sourcesAdded());
        assertTrue(o.dropped().stream().anyMatch(d -> d.startsWith("\"Kimie Hale is a child of Genzaburo Hale\" was not filed again, because it is a fact that is disputed")), o.dropped().toString());
        assertTrue(o.dropped().stream().anyMatch(d -> d.contains("because it is a fact that is retired")), o.dropped().toString());
        Finding disputed = store.finding(parent.id());
        assertEquals(Finding.State.disputed, disputed.state());
        assertEquals(1, disputed.sources().size(), "a disputed fact does not gain a source");
        assertTrue(disputed.notes().stream().anyMatch(n -> n.kind().equals("met-again") && n.text().contains("https://example.org/hale-family")), disputed.notes().toString());

        // a research run that brings the retired fact back: not filed, and nobody asks the model whether it is a duplicate
        LibrarianIndex index = new LibrarianIndex(store);
        Investigation inv = Acquisitions.admit(store, index, "Kimie Hale", "Kimie Hale was born in Sendai. https://example.org/sendai-births", "model:test");
        String extract = """
                [{"title": "Kimie Hale born in Sendai", "claim": "Kimie Hale was born in Sendai in 1901.", "claim_type": "extraction", "confidence": "high", "volatility": "stable",
                  "sources": ["https://example.org/sendai-births"], "triple": {"subject": "Kimie Hale", "predicate": "born-in", "object": "Sendai"}}]""";
        var out = new LibrarianReview(store, index, new LibrarianReview.Judge() {
            @Override public String extract(String b) { return extract; }
            @Override public String compare(String c, String n) { throw new AssertionError("the triple settles it: " + n); }
        }, "librarian:test").review(inv);
        assertTrue(out.problems().stream().anyMatch(p -> p.contains("which is retired; it came back from https://example.org/sendai-births and was not filed again")), out.problems().toString());
        assertEquals(1, store.scanFindings().findings().stream().filter(f -> f.triple() != null && f.triple().predicate().equals("born-in")).count(), "no second copy");
        assertTrue(claim(store, "Kimie Hale", "born-in").notes().stream().anyMatch(n -> n.kind().equals("met-again") && n.text().contains("sendai-births")));
    }

    @Test
    void aFactTheLibraryDisputedByItselfKeepsATextOrATreeFileThatGivesItAgainAsAFurtherSource(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        FamilyAccount.file(store, read(fact("Tom Ellis", "born-in", "Leeds", "1851", "Tom Ellis was born in Leeds in 1851.")), "file:///family/notes.txt", "an aunt");
        Finding born = claim(store, "Tom Ellis", "born-in");
        // the review found a claim that gives another place, and marked both sides disputed; nobody decided by hand
        store.write(new Finding(born.id(), born.title(), born.subjects(), Finding.State.disputed, born.claimType(), born.confidence(), born.writer(), born.recordedAt(), born.validAsOf(),
                born.volatility(), born.reviewBy(), born.sources(), born.supersedes(), born.review(), born.body(), born.triple(), born.notes())
                .withNote(new Finding.Note("disputed", "librarian:test", "2026-09-23", "contradicted by F-0100-tom-ellis-born-in-york")));
        FamilyAccount.Outcome o = FamilyAccount.file(store, read(fact("Tom Ellis", "born-in", "Leeds", "1851", "ELLIS, Tom, born at Leeds")), RECORD, "a newspaper");
        assertEquals(0, o.claims(), o.dropped().toString());
        assertEquals(1, o.sourcesAdded(), "the newspaper is kept as a source of the disputed fact: " + o.dropped());
        assertTrue(o.dropped().stream().noneMatch(d -> d.contains("was not filed again")), o.dropped().toString());
        Finding held = store.finding(born.id());
        assertEquals(Finding.State.disputed, held.state(), "later sources settle it, not the reader");
        assertEquals(List.of("file:///family/notes.txt", RECORD), held.sources().stream().map(Finding.Source::locator).toList());

        Path ged = tmp.resolve("ellis.ged");
        Files.writeString(ged, "0 HEAD\n1 CHAR UTF-8\n0 @I1@ INDI\n1 NAME Tom /Ellis/\n1 BIRT\n2 DATE 1851\n2 PLAC Leeds\n0 TRLR\n", StandardCharsets.UTF_8);
        Gedcom.importFile(store, ged);
        held = store.finding(born.id());
        assertEquals(Finding.State.disputed, held.state());
        assertEquals(3, held.sources().size(), "the tree file is a further source too: " + held.sources());
    }

    @Test
    void aResearchRunThatFindsAFamilyFactAgainBacksItWithoutAskingTheModelAndLeavesItForTheFamily(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        FamilyAccount.file(store, read(fact("Tom Ellis", "born-in", "Leeds", "1851", "Tom Ellis was born in Leeds in 1851.")), "file:///family/notes.txt", "an aunt");
        LibrarianIndex index = new LibrarianIndex(store);
        Investigation inv = Acquisitions.admit(store, index, "Tom Ellis", "Tom Ellis was born in Leeds. " + RECORD, "model:test");
        String extract = """
                [{"title": "Tom Ellis born in Leeds", "claim": "Tom Ellis was born in Leeds in 1851.", "claim_type": "extraction", "confidence": "high", "volatility": "stable",
                  "sources": ["%s"], "triple": {"subject": "Tom Ellis", "predicate": "born-in", "object": "Leeds"}}]""".formatted(RECORD);
        new LibrarianReview(store, index, new LibrarianReview.Judge() {
            @Override public String extract(String b) { return extract; }
            @Override public String compare(String c, String n) { throw new AssertionError("the triple settles it: " + n); }
        }, "librarian:test").review(inv);
        Finding born = claim(store, "Tom Ellis", "born-in");
        assertEquals(1, store.scanFindings().findings().stream().filter(f -> f.triple() != null).count());
        assertTrue(born.sources().stream().anyMatch(s -> s.locator().equals(RECORD)), born.sources().toString());
        assertEquals(Finding.State.draft, born.state(), "the family's own account waits for the family's word, whatever record backs it");
    }

    @Test
    void aTreeFileThatGivesAFactTheNotesGaveIsKeptAsAFurtherSource(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        FamilyAccount.file(store, read(fact("Tom Ellis", "born-in", "Leeds", "1851", "Tom Ellis was born in Leeds in 1851.")), "file:///family/notes.txt", "an aunt");
        Path ged = tmp.resolve("ellis.ged");
        Files.writeString(ged, "0 HEAD\n1 CHAR UTF-8\n0 @I1@ INDI\n1 NAME Tom /Ellis/\n1 BIRT\n2 DATE 1851\n2 PLAC Leeds\n1 DEAT\n2 DATE 1920\n0 TRLR\n", StandardCharsets.UTF_8);
        Gedcom.Outcome o = Gedcom.importFile(store, ged);
        Finding born = claim(store, "Tom Ellis", "born-in");
        assertEquals(2, born.sources().size(), born.sources().toString());
        assertTrue(born.sources().get(1).locator().endsWith("ellis.ged"));
        assertTrue(o.problems().stream().anyMatch(p -> p.startsWith("1 fact in the file was already in your library from another source. The file was added to that fact as a further source")), o.problems().toString());
        Gedcom.importFile(store, ged);
        assertEquals(2, claim(store, "Tom Ellis", "born-in").sources().size(), "the same file again adds nothing");
    }

    @Test
    void aSourceThePersonDoubtsShowsEveryFactThatRestsOnItAndWhichHaveNothingElse(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        FamilyAccount.file(store, read(fact("Kimie Hale", "child-of", "Genzaburo Hale", "", "Kimie was Genzaburo Hale's child."), fact("Kimie Hale", "born-in", "Sendai", "1901", "Kimie Hale was born in Sendai in 1901."),
                fact("Genzaburo Hale", "occupation", "merchant", "", "Genzaburo Hale was a merchant.")), "file:///family/community.pdf", "a book");
        // a page gives the birth too, and a research run files the father's work from a record as a claim of its own
        FamilyAccount.file(store, read(fact("Kimie Hale", "born-in", "Sendai", "1901", "born in Sendai, 1901")), "https://example.org/hale-family", "a page");
        store.write(new Finding("F-0100-genzaburo-hale-occupation-merchant", "Genzaburo Hale worked as a merchant", List.of(), Finding.State.draft, Finding.ClaimType.extraction, Finding.Confidence.medium,
                "model:test", Instant.now().toString(), "2026-09-22", Finding.Volatility.stable, "", List.of(new Finding.Source(RECORD, "n/a", "cited by I-0001")), List.of(), null,
                "Genzaburo Hale worked as a merchant.\n", new Finding.Triple("Genzaburo Hale", "occupation", "merchant"), List.of()));
        Evidence.Resting r = Evidence.restingOn(store, "community.pdf", "");
        assertEquals(List.of("Kimie Hale is a child of Genzaburo Hale."), r.alone().stream().map(f -> f.body().lines().findFirst().orElse("")).toList());
        assertEquals(2, r.backed().size(), "the birth has the page beside it, the work has the record's own claim");
        assertEquals(r.alone().size() + r.backed().size(), Evidence.restingOn(store, "file:///family/community.pdf", "").alone().size() + Evidence.restingOn(store, "file:///family/community.pdf", "").backed().size());
        String said = Evidence.restingForPerson("community.pdf", r, false);
        assertTrue(said.startsWith("3 facts in your library rest on community.pdf.") && said.contains("Facts that have no other source (1). If this source is wrong, nothing else holds them up:")
                && said.contains("researchzosho dispute " + r.alone().get(0).id().replaceFirst("^(F-\\d+).*", "$1") + " \"the reason"), "the example is a fact from the list: " + said);
        // after a dispute, the fact being disputed is left out of what else the source holds up
        Finding child = claim(store, "Kimie Hale", "child-of");
        assertEquals(0, Evidence.restingOn(store, "community.pdf", child.id()).alone().size());
        assertTrue(Evidence.restingOn(store, "https://example.org/hale-family/", "").backed().size() == 1, "an address in another spelling is the same source");
        assertTrue(Evidence.restingOn(store, "notes.txt", "").isEmpty());
    }

    /** One command, as the person types it, in the library under {@code home}; what it printed. */
    private static String cli(Path home, String... words) throws Exception {
        String realHome = System.getProperty("user.home");
        PrintStream was = System.out;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        System.setProperty("user.home", home.toString());   // the command opens ~/researchzosho-library
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        try {
            String[] args = new String[words.length + 1];
            args[0] = "librarian";
            System.arraycopy(words, 0, args, 1, words.length);
            LibrarianCli.run(args, "http://127.0.0.1:1", "m");
        } finally { System.setOut(was); System.setProperty("user.home", realHome); }
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    void theFamilysOwnCommandsCountAnOrdinaryClaimThatGivesTheSameFactInItsOwnWords(@TempDir Path home) throws Exception {
        LibraryStore store = new LibraryStore(home.resolve("researchzosho-library")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        FamilyAccount.file(store, read(fact("Tom Ellis", "child-of", "Ann Hart", "", "Tom was Ann Hart's son."), fact("Tom Ellis", "born-in", "Leeds", "1851", "Tom Ellis was born in Leeds in 1851.")),
                "file:///family/community.pdf", "a book");
        // an ordinary research run read the same parent in a record and wrote it in its own words
        store.write(new Finding("F-0100-tom-ellis-son-of-ann-hart", "Tom Ellis was the son of Ann Hart", List.of(), Finding.State.accepted, Finding.ClaimType.extraction, Finding.Confidence.medium,
                "model:test", Instant.now().toString(), "2026-09-22", Finding.Volatility.stable, "", List.of(new Finding.Source(RECORD, "n/a", "cited by I-0003-ordinary")), List.of(), null,
                "Tom Ellis was the son of Ann Hart.\n", new Finding.Triple("Tom Ellis", "son of", "Ann Hart"), List.of()));
        String said = cli(home, "genealogy", "source", "community.pdf");
        assertTrue(said.contains("Facts that another source also gives (1)") && said.substring(said.indexOf("another source also gives")).contains("Tom Ellis is a child of Ann Hart"),
                "the parent is backed by the record: " + said);
        // disputing the family's birth fact: what else rests on the book is read the family's way too
        String disputed = cli(home, "dispute", claim(store, "Tom Ellis", "born-in").id(), "the book is wrong about the town");
        assertTrue(disputed.contains("Facts that another source also gives (1)"), disputed);
        assertEquals("son of", Graph.build(store).edges().stream().filter(e -> e.findingId().startsWith("F-0100")).findFirst().orElseThrow().predicate(), "the core map keeps the ordinary claim's words");
    }

    @Test
    void aBirthASourceWroteDownSeventyYearsLaterSaysSo(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        Path ged = tmp.resolve("ellis.ged");
        Files.writeString(ged, "0 HEAD\n1 CHAR UTF-8\n0 @S1@ SOUR\n1 TITL Death register, York\n0 @S2@ SOUR\n1 TITL Parish register, Leeds\n"
                + "0 @I1@ INDI\n1 NAME Tom /Ellis/\n1 BIRT\n2 DATE 1851\n2 PLAC Leeds\n2 SOUR @S1@\n3 PAGE entry 12\n3 DATA\n4 DATE 3 MAR 1920\n4 TEXT born at Leeds\n1 DEAT\n2 DATE 1920\n2 PLAC York\n2 SOUR @S1@\n3 DATA\n4 DATE 3 MAR 1920\n"
                + "0 @I2@ INDI\n1 NAME Ann /Hart/\n1 BIRT\n2 DATE 1855\n2 PLAC Leeds\n2 SOUR @S2@\n3 DATA\n4 DATE 1855\n1 DEAT\n2 DATE 1930\n0 TRLR\n", StandardCharsets.UTF_8);
        Gedcom.importFile(store, ged);
        Finding born = claim(store, "Tom Ellis", "born-in");
        assertTrue(born.sources().get(0).edition().contains("cited there: Death register, York, entry 12 born at Leeds; the source wrote this down on 3 MAR 1920"), born.sources().get(0).edition());
        assertEquals(69, Evidence.writtenLater(born));
        assertEquals(0, Evidence.writtenLater(claim(store, "Tom Ellis", "died-in")), "the death was written down the year it happened");
        assertEquals(0, Evidence.writtenLater(claim(store, "Ann Hart", "born-in")), "a baptism entered at the time");
        String life = FamilyLife.render("Tom Ellis", FamilyLife.of(store, "Tom Ellis"));
        assertTrue(life.contains("(written down 69 years after it happened)"), life);
    }

    @Test
    void anotherNameKeepsWhereItCameFrom(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        FamilyAccount.file(store, new FamilyAccount.Read(List.of(new FamilyAccount.Person("森田勇", "もりた いさむ", List.of())),
                List.of(fact("森田勇", "born-in", "仙台", "1880", "森田勇は仙台で生まれた")), List.of()), "file:///family/notes.txt", "an aunt");
        FamilyAccount.file(store, new FamilyAccount.Read(List.of(new FamilyAccount.Person("森田勇", "", List.of("Isamu Morita"))),
                List.of(fact("森田勇", "died-in", "Sendai", "1950", "Isamu Morita died in Sendai")), List.of()), "https://www.geni.com/people/Isamu-Morita/6000000000001", "Geni");
        Map<String, List<String>> from = Graph.aliasSources(store, "森田勇");
        assertEquals(List.of("file:///family/notes.txt"), from.get("もりた いさむ"));
        assertEquals(List.of("https://www.geni.com/people/Isamu-Morita/6000000000001"), from.get("Isamu Morita"));
        String said = FamilyLife.otherNames(store, "森田勇");
        assertTrue(said.contains("Isamu Morita (from www.geni.com, 6000000000001, a clue only)") && said.contains("もりた いさむ (from notes.txt, a clue only)"), said);
        Graph.dropAliases(store, List.<String[]>of(new String[]{"森田勇", "Isamu Morita"}));
        assertFalse(Graph.aliasSources(store, "森田勇").containsKey("Isamu Morita"), "a name taken away takes its source with it");
        assertTrue(Graph.aliasSources(store, "森田勇").containsKey("もりた いさむ"));
    }
}
