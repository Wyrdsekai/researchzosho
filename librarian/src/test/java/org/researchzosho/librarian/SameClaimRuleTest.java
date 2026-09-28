package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The same claim, by its triple, when it comes back from a later run. What happens depends on who set the claim on the shelf aside. A
 * claim the person disputed or retired is not filed again: their decision holds against later runs, and a note on the old claim says
 * where the fact came back. A claim the library disputed by itself (the review marking both sides of a contradiction, the inventory, the
 * retraction check) takes the new source, so later sources can settle it. A disputed copy never keeps a live copy of the same claim from
 * gaining the source.
 */
class SameClaimRuleTest {

    static final String GITHUB = "https://github.com/tide/tidebook/blob/main/README.md";
    static final String DOCS = "https://docs.rs/tidebook/latest/tidebook/cache/index.html";

    private static LibraryStore store(Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        return store;
    }

    /** One candidate claim of a review: tidebook {predicate} {object}, read in one source. */
    static String candidate(String predicate, String object, String source) {
        return """
                [{"title": "tidebook %s %s", "claim": "tidebook %s %s.", "claim_type": "extraction", "confidence": "high", "volatility": "stable",
                  "sources": ["%s"], "triple": {"subject": "tidebook", "predicate": "%s", "object": "%s"}}]""".formatted(predicate, object, predicate, object, source, predicate, object);
    }

    /** A review of one report that says {@code said} and cites {@code source}; {@code compare} answers the model's duplicate question, null when it must not be asked. */
    static LibrarianReview.Outcome review(LibraryStore store, String extract, String said, String source, Supplier<String> compare) throws Exception {
        LibrarianIndex index = new LibrarianIndex(store);
        Investigation inv = Acquisitions.admit(store, index, "What does tidebook keep its cache in?", said + " " + source, "model:test");
        return new LibrarianReview(store, index, new LibrarianReview.Judge() {
            @Override public String extract(String b) { return extract; }
            @Override public String compare(String c, String n) {
                if (compare == null) throw new AssertionError("the triple settles it, the model is not asked: " + n);
                return compare.get();
            }
        }, "librarian:test").review(inv);
    }

    static Finding draft(String id, String predicate, String object, String source) {
        return new Finding(id, "tidebook " + predicate + " " + object, List.of(), Finding.State.draft, Finding.ClaimType.extraction, Finding.Confidence.medium,
                "model:test", Instant.now().toString(), "2026-09-23", Finding.Volatility.stable, "", List.of(new Finding.Source(source, "n/a", "cited by I-0001")), List.of(), null,
                "tidebook " + predicate + " " + object + ".\n", new Finding.Triple("tidebook", predicate, object), List.of());
    }

    static List<Finding> withTriple(LibraryStore store, String predicate, String object) {
        return store.scanFindings().findings().stream().filter(f -> f.triple() != null && f.triple().predicate().equals(predicate) && f.triple().object().equals(object)).toList();
    }

    static Finding as(Finding f, Finding.State state, Finding.Note... notes) {
        return new Finding(f.id(), f.title(), f.subjects(), state, f.claimType(), f.confidence(), f.writer(), f.recordedAt(), f.validAsOf(), f.volatility(), f.reviewBy(),
                f.sources(), f.supersedes(), f.review(), f.body(), f.triple(), List.of(notes));
    }

    @Test
    void whoSetAClaimAsideIsReadFromItsNotesInOrder() {
        Finding f = draft("F-0001-tidebook-uses-sqlite", "uses", "SQLite", GITHUB);
        Finding.Note person = new Finding.Note("disputed", "person", "2026-09-20", "the readme is about an old version");
        Finding.Note accepted = new Finding.Note("accepted", "person", "2026-09-20", "accepted by the person");
        Finding.Note contradicted = new Finding.Note("disputed", "librarian:test", "2026-09-21", "contradicted by F-0002-tidebook-uses-postgresql");
        Finding.Note inventory = new Finding.Note("inventory", "inventory", "2026-09-21", "source does not support the claim — the page says the cache is in memory");
        assertNull(f.setAsideBy(), "a live claim is set aside by nobody");
        assertEquals(Finding.SetAside.person, as(f, Finding.State.disputed).setAsideBy(), "a state line changed by hand leaves no note, and is the person's");
        assertEquals(Finding.SetAside.person, as(f, Finding.State.retired, new Finding.Note("retired", "person", "2026-09-20", "retired by the person")).setAsideBy());
        assertEquals(Finding.SetAside.library, as(f, Finding.State.disputed, contradicted).setAsideBy(), "the review's contradiction pass");
        assertEquals(Finding.SetAside.library, as(f, Finding.State.disputed, inventory).setAsideBy(), "the inventory and the retraction check");
        assertEquals(Finding.SetAside.library, as(f, Finding.State.disputed, accepted, contradicted).setAsideBy(), "accepted by the person, disputed by the library after that");
        assertEquals(Finding.SetAside.person, as(f, Finding.State.disputed, person, contradicted).setAsideBy(), "the person's dispute holds whatever the library noted after it");
        assertTrue(Finding.contradiction(contradicted) && !Finding.contradiction(person) && !Finding.contradiction(inventory));
    }

    @Test
    void aClaimThePersonDisputedOrRetiredIsNotFiledAgainAndTheReviewSaysTheirDecisionHolds(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        store.write(draft("F-0001-tidebook-uses-sqlite", "uses", "SQLite", GITHUB));
        store.write(draft("F-0002-tidebook-licensed-under-mit", "licensed-under", "MIT", GITHUB));
        Council council = new Council(store);
        council.dispute("F-0001-tidebook-uses-sqlite", "the readme is about an old version");
        council.retire("F-0002-tidebook-licensed-under-mit");

        var disputed = review(store, candidate("uses", "SQLite", "https://example.org/tidebook-sqlite"), "tidebook keeps its cache in SQLite.", "https://example.org/tidebook-sqlite", null);
        assertTrue(disputed.problems().stream().anyMatch(p -> p.contains("F-0001-tidebook-uses-sqlite, which is disputed; it came back from https://example.org/tidebook-sqlite and was not filed again, because you disputed it and your decision holds")),
                disputed.problems().toString());
        var retired = review(store, candidate("licensed-under", "MIT", "https://example.org/tidebook-licence"), "tidebook is under the MIT licence.", "https://example.org/tidebook-licence", null);
        assertTrue(retired.problems().stream().anyMatch(p -> p.contains("which is retired; it came back from https://example.org/tidebook-licence and was not filed again, because you retired it and your decision holds")),
                retired.problems().toString());

        assertEquals(1, withTriple(store, "uses", "SQLite").size(), "no second copy");
        assertEquals(1, withTriple(store, "licensed-under", "MIT").size(), "no second copy");
        Finding held = store.finding("F-0001-tidebook-uses-sqlite");
        assertEquals(Finding.State.disputed, held.state());
        assertEquals(List.of(GITHUB), held.sources().stream().map(Finding.Source::locator).toList(), "a claim the person disputed does not gain the source");
        assertTrue(held.notes().stream().anyMatch(n -> n.kind().equals("met-again") && n.text().contains("tidebook-sqlite")), held.notes().toString());
        assertEquals(Finding.State.retired, store.finding("F-0002-tidebook-licensed-under-mit").state());
    }

    @Test
    void aClaimTheReviewDisputedByItselfGainsTheSourceSoLaterSourcesCanSettleIt(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        review(store, candidate("uses", "SQLite", "https://example.org/a"), "tidebook keeps its cache in SQLite.", "https://example.org/a", null);
        String first = withTriple(store, "uses", "SQLite").get(0).id();
        // a second report says PostgreSQL: the review marks both sides disputed, which nobody decided by hand
        var clash = review(store, candidate("uses", "PostgreSQL", "https://example.org/b"), "tidebook keeps its cache in PostgreSQL.", "https://example.org/b",
                () -> "{\"verdict\": \"contradicts\", \"id\": \"" + first + "\"}");
        assertEquals(1, clash.disputed().size(), clash.toString());
        assertEquals(Finding.State.disputed, store.finding(first).state());

        // a third report says SQLite again, from another source: the source joins the disputed claim, which stays disputed
        var again = review(store, candidate("uses", "SQLite", "https://example.org/c"), "tidebook keeps its cache in SQLite.", "https://example.org/c", null);
        assertEquals(1, withTriple(store, "uses", "SQLite").size(), "no second copy: " + again);
        Finding held = store.finding(first);
        assertEquals(Finding.State.disputed, held.state(), "a source is weighed by the person or by later sources, never by accepting the claim on its own");
        assertEquals(List.of("https://example.org/a", "https://example.org/c"), held.sources().stream().map(Finding.Source::locator).toList());
        assertTrue(again.problems().stream().anyMatch(p -> p.contains(first + ", which is disputed; it came back from https://example.org/c and was added to it as a further source, because the library disputed it by itself")
                && p.contains("so that later sources can settle it")), again.problems().toString());
    }

    @Test
    void aClaimTheInventoryDisputedGainsTheSourceToo(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        Finding f = draft("F-0001-tidebook-uses-sqlite", "uses", "SQLite", GITHUB);
        store.write(f);
        Inventory.dispute(store, new LibrarianIndex(store), store.finding(f.id()), "the page says the cache is in memory");
        var again = review(store, candidate("uses", "SQLite", DOCS), "tidebook keeps its cache in SQLite.", DOCS, null);
        Finding held = store.finding(f.id());
        assertEquals(Finding.State.disputed, held.state());
        assertEquals(List.of(GITHUB, DOCS), held.sources().stream().map(Finding.Source::locator).toList(), again.problems().toString());
        assertTrue(again.problems().stream().anyMatch(p -> p.contains("because the library disputed it by itself")), again.problems().toString());
    }

    @Test
    void aDisputedCopyNeverKeepsALiveCopyOfTheSameClaimFromGainingTheSource(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        // the library disputed one copy by itself; another copy of the same claim waits in the inbox
        store.write(draft("F-0001-tidebook-uses-sqlite", "uses", "SQLite", "https://example.org/old"));
        Inventory.dispute(store, new LibrarianIndex(store), store.finding("F-0001-tidebook-uses-sqlite"), "the page says the cache is in memory");
        store.write(draft("F-0002-tidebook-uses-sqlite", "uses", "SQLite", GITHUB));
        var out = review(store, candidate("uses", "SQLite", DOCS), "tidebook keeps its cache in SQLite.", DOCS, null);
        Finding live = store.finding("F-0002-tidebook-uses-sqlite");
        assertTrue(live.sources().stream().anyMatch(s -> s.locator().equals(DOCS)), "the live copy gains the source: " + out.problems());
        assertEquals(Finding.State.accepted, live.state(), "two independent sources, the thing itself and its documentation: accepted as any claim is");
        assertEquals(1, store.finding("F-0001-tidebook-uses-sqlite").sources().size(), "the disputed copy is left as it was");
        assertTrue(out.problems().stream().anyMatch(p -> p.contains("F-0002-tidebook-uses-sqlite, which gained the source") && p.contains("F-0001-tidebook-uses-sqlite")), out.problems().toString());

        // the person disputed one copy: the live copy still gains the source, and waits for the person instead of being accepted on its own
        store.write(draft("F-0003-tidebook-written-in-rust", "written-in", "Rust", "https://example.org/old"));
        new Council(store).dispute("F-0003-tidebook-written-in-rust", "that page is about a fork");
        store.write(draft("F-0004-tidebook-written-in-rust", "written-in", "Rust", GITHUB));
        var person = review(store, candidate("written-in", "Rust", DOCS), "tidebook is written in Rust.", DOCS, null);
        Finding waits = store.finding("F-0004-tidebook-written-in-rust");
        assertTrue(waits.sources().stream().anyMatch(s -> s.locator().equals(DOCS)), person.problems().toString());
        assertEquals(Finding.State.draft, waits.state(), "the person said no to this claim once: the library does not accept it by itself");
        assertTrue(person.problems().stream().anyMatch(p -> p.contains("waits for your decision, because you disputed its copy F-0003-tidebook-written-in-rust")), person.problems().toString());
        assertEquals(1, withTriple(store, "written-in", "Rust").stream().filter(f -> f.state() == Finding.State.disputed).count());
    }

    /** A candidate the extractor gave no triple, so only the model's duplicate call can tie it to a claim on the shelf. */
    static String untripled(String says, String source) {
        return """
                [{"title": "%s", "claim": "%s.", "claim_type": "extraction", "confidence": "high", "volatility": "stable", "sources": ["%s"]}]""".formatted(says, says, source);
    }

    static Supplier<String> duplicateOf(String id) { return () -> "{\"verdict\": \"duplicate\", \"id\": \"" + id + "\"}"; }

    @Test
    void theModelsDuplicateCallKeepsTheLiveCopyOfAClaimThePersonDisputedWaiting(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        store.write(draft("F-0001-tidebook-written-in-rust", "written-in", "Rust", "https://example.org/old"));
        new Council(store).dispute("F-0001-tidebook-written-in-rust", "that page is about a fork");
        store.write(draft("F-0002-tidebook-written-in-rust", "written-in", "Rust", GITHUB));
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        var out = review(store, untripled("tidebook is written in Rust", DOCS), "tidebook is written in Rust.", DOCS, duplicateOf("F-0002-tidebook-written-in-rust"));
        Finding live = store.finding("F-0002-tidebook-written-in-rust");
        assertTrue(live.sources().stream().anyMatch(s -> s.locator().equals(DOCS)), out.problems().toString());
        assertEquals(Finding.State.draft, live.state(), "the person said no to this claim once: the library does not accept it by itself, whoever found the match");
        assertTrue(out.problems().stream().anyMatch(p -> p.contains("F-0002-tidebook-written-in-rust, which gained the source; it waits for your decision, because you disputed its copy F-0001-tidebook-written-in-rust")),
                out.problems().toString());
    }

    @Test
    void theModelsDuplicateCallLeavesAClaimThePersonDisputedAsTheyLeftIt(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        store.write(draft("F-0001-tidebook-written-in-rust", "written-in", "Rust", "https://example.org/old"));
        new Council(store).dispute("F-0001-tidebook-written-in-rust", "that page is about a fork");
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        Finding.Review signed = store.finding("F-0001-tidebook-written-in-rust").review();
        var out = review(store, untripled("tidebook is written in Rust", DOCS), "tidebook is written in Rust.", DOCS, duplicateOf("F-0001-tidebook-written-in-rust"));
        Finding held = store.finding("F-0001-tidebook-written-in-rust");
        assertEquals(List.of("https://example.org/old"), held.sources().stream().map(Finding.Source::locator).toList(), "their decision holds against a later run: " + out.problems());
        assertEquals(signed, held.review(), "the review the person signed is left as it was");
        assertTrue(held.notes().stream().anyMatch(n -> n.kind().equals("met-again") && n.text().contains(DOCS)), held.notes().toString());
        assertTrue(out.problems().stream().anyMatch(p -> p.contains("F-0001-tidebook-written-in-rust, which is disputed; it came back from " + DOCS + " and was not filed again, because you disputed it and your decision holds")),
                out.problems().toString());
    }

    @Test
    void theModelsDuplicateCallAddsTheSourceToAClaimTheLibraryDisputedAndSaysSo(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        store.write(draft("F-0001-tidebook-uses-sqlite", "uses", "SQLite", GITHUB));
        Inventory.dispute(store, new LibrarianIndex(store), store.finding("F-0001-tidebook-uses-sqlite"), "the page says the cache is in memory");
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        var out = review(store, untripled("tidebook keeps its cache in SQLite", DOCS), "tidebook keeps its cache in SQLite.", DOCS, duplicateOf("F-0001-tidebook-uses-sqlite"));
        Finding held = store.finding("F-0001-tidebook-uses-sqlite");
        assertEquals(Finding.State.disputed, held.state());
        assertEquals(List.of(GITHUB, DOCS), held.sources().stream().map(Finding.Source::locator).toList());
        assertTrue(out.problems().stream().anyMatch(p -> p.contains("F-0001-tidebook-uses-sqlite, which is disputed; it came back from " + DOCS + " and was added to it as a further source, because the library disputed it by itself")),
                out.problems().toString());
    }

    @Test
    void anAcceptedCopyThatGainsTheSourceIsNotSaidToWait(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        store.write(draft("F-0003-tidebook-written-in-rust", "written-in", "Rust", "https://example.org/old"));
        new Council(store).dispute("F-0003-tidebook-written-in-rust", "that page is about a fork");
        store.write(draft("F-0004-tidebook-written-in-rust", "written-in", "Rust", GITHUB));
        new Council(store).accept("F-0004-tidebook-written-in-rust");
        var out = review(store, candidate("written-in", "Rust", DOCS), "tidebook is written in Rust.", DOCS, null);
        Finding copy = store.finding("F-0004-tidebook-written-in-rust");
        assertEquals(Finding.State.accepted, copy.state());
        assertTrue(copy.sources().stream().anyMatch(s -> s.locator().equals(DOCS)), out.problems().toString());
        assertTrue(out.problems().stream().anyMatch(p -> p.contains("F-0004-tidebook-written-in-rust, which gained the source and stays accepted; you disputed its copy F-0003-tidebook-written-in-rust, which stays as you left it")),
                out.problems().toString());
        assertTrue(out.problems().stream().noneMatch(p -> p.contains("waits for your decision")), out.problems().toString());
    }

    @Test
    void aClaimTheRetractionCheckDisputedIsSaidToCiteARetractedPaper(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        store.write(draft("F-0001-tidebook-uses-sqlite", "uses", "SQLite", "https://doi.org/10.1234/abc.5678"));
        Retractions.check(store, doi -> new Retractions.Notice("retraction", "2026-01-01", ""), 10, LocalDate.of(2026, 9, 20));
        assertEquals(Finding.SetAside.library, store.finding("F-0001-tidebook-uses-sqlite").setAsideBy());
        var again = review(store, candidate("uses", "SQLite", "https://example.org/tidebook-sqlite"), "tidebook keeps its cache in SQLite.", "https://example.org/tidebook-sqlite", null);
        assertTrue(again.problems().stream().anyMatch(p -> p.contains("because the library disputed it by itself (the retraction check found that the paper it cites, doi:10.1234/abc.5678, was retracted)")),
                again.problems().toString());
        assertTrue(again.problems().stream().noneMatch(p -> p.contains("does not say it")), "the paper may well say it: it was retracted " + again.problems());
        assertEquals(" (the retraction check found that the paper it cites, doi:10.9/x, was withdrawn)", LibrarianReview.retracted("the cited source doi:10.9/x was withdrawal on 2026-02-02 — Retraction Watch via Crossref"));
    }
}
