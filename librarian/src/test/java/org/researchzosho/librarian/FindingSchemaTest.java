package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The schema layer's contract: format/parse round-trips exactly, and everything malformed
 * FAILS CLOSED with an error naming the problem — the store never guesses, never repairs.
 */
class FindingSchemaTest {

    private static Finding sample() {
        return new Finding(
                "F-0412-yakuza-register-flattening",
                "Direct translation flattens yakuza sociolect into neutral English",
                List.of("translation--register", "japanese--sociolect"),
                Finding.State.draft,
                Finding.ClaimType.interpretation,
                Finding.Confidence.medium,
                "model:qwen3.8-27b",
                "2026-09-01T10:00:00Z",
                "2026-09-01",
                Finding.Volatility.slow,
                "2027-03-01",
                List.of(new Finding.Source("raw/2026-09-01-tokyovice.md",
                        "Tokyo Vice S1E3, Crunchyroll EN subtitles, 2022",
                        "the observed flattening instance")),
                List.of(),
                null,
                "The claim, with its evidence summary.\n\nAnd caveats.\n");
    }

    @Test
    void roundTripIsExact() {
        Finding f = sample();
        assertEquals(f, Finding.parse(f.format()));
    }

    @Test
    void roundTripWithReviewAndSupersedes() {
        Finding f = new Finding(sample().id(), sample().title(), sample().subjects(),
                Finding.State.accepted, sample().claimType(), sample().confidence(),
                sample().writer(), sample().recordedAt(), sample().validAsOf(),
                sample().volatility(), sample().reviewBy(), sample().sources(),
                List.of("F-0001-old"),
                new Finding.Review(1, "librarian:glm", "accepted", sample().contentHash(),
                        "2026-09-01T11:00:00Z"),
                sample().body());
        assertEquals(f, Finding.parse(f.format()));
        assertFalse(f.reviewStale());
    }

    @Test
    void editingBodyStalesReview() {
        Finding f = sample();
        Finding approved = new Finding(f.id(), f.title(), f.subjects(), Finding.State.accepted,
                f.claimType(), f.confidence(), f.writer(), f.recordedAt(), f.validAsOf(),
                f.volatility(), f.reviewBy(), f.sources(), f.supersedes(),
                new Finding.Review(1, "librarian:glm", "accepted", f.contentHash(), "t"),
                f.body());
        assertFalse(approved.reviewStale());
        Finding edited = new Finding(approved.id(), approved.title(), approved.subjects(),
                approved.state(), approved.claimType(), approved.confidence(), approved.writer(),
                approved.recordedAt(), approved.validAsOf(), approved.volatility(),
                approved.reviewBy(), approved.sources(), approved.supersedes(), approved.review(),
                approved.body() + "\nA new sentence.");
        assertTrue(edited.reviewStale(), "content edit must mechanically stale the approval");
    }

    @Test
    void stateChangeDoesNotStaleReview() {
        Finding f = sample();
        var review = new Finding.Review(1, "librarian:glm", "accepted", f.contentHash(), "t");
        Finding promoted = new Finding(f.id(), f.title(), f.subjects(), Finding.State.accepted,
                f.claimType(), f.confidence(), f.writer(), f.recordedAt(), f.validAsOf(),
                f.volatility(), f.reviewBy(), f.sources(), f.supersedes(), review, f.body());
        assertFalse(promoted.reviewStale(), "promotion is the approval's PURPOSE, not an edit");
    }

    @Test
    void subjectsAreOutsideTheHashAndLegacySignaturesMigrate() throws Exception {
        Finding f = sample();
        Finding cataloged = new Finding(f.id(), f.title(), java.util.List.of("translation--register"), f.state(),
                f.claimType(), f.confidence(), f.writer(), f.recordedAt(), f.validAsOf(), f.volatility(),
                f.reviewBy(), f.sources(), f.supersedes(), f.review(), f.body());
        assertEquals(f.contentHash(), cataloged.contentHash(), "cataloging is metadata, not substance");
        // a review signed under the OLD formula migrates; one whose content really changed does not
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("cz-migrate");
        LibraryStore store = new LibraryStore(dir); store.init();
        Finding legacySigned = new Finding(f.id(), f.title(), f.subjects(), Finding.State.accepted, f.claimType(),
                f.confidence(), f.writer(), f.recordedAt(), f.validAsOf(), f.volatility(), f.reviewBy(), f.sources(),
                f.supersedes(), new Finding.Review(1, "librarian:t", "accepted", f.legacyContentHash(), "t"), f.body());
        store.write(legacySigned);
        Finding reallyEdited = new Finding("F-0413-edited", f.title(), f.subjects(), Finding.State.accepted, f.claimType(),
                f.confidence(), f.writer(), f.recordedAt(), f.validAsOf(), f.volatility(), f.reviewBy(), f.sources(),
                f.supersedes(), new Finding.Review(1, "librarian:t", "accepted", "sha256:0000000000000000", "t"), f.body());
        store.write(reallyEdited);
        assertEquals(1, store.migrateReviewHashes());
        assertFalse(store.finding(f.id()).reviewStale());
        assertTrue(store.finding("F-0413-edited").reviewStale(), "a genuinely stale review is never re-signed");
    }

    @Test
    void unknownKeyFailsClosed() {
        String text = sample().format().replace("confidence: medium", "confidence: medium\nvibes: good");
        var e = assertThrows(IllegalArgumentException.class, () -> Finding.parse(text));
        assertTrue(e.getMessage().contains("vibes"), e.getMessage());
    }

    @Test
    void badEnumFailsClosed() {
        String text = sample().format().replace("state: draft", "state: pending");
        var e = assertThrows(IllegalArgumentException.class, () -> Finding.parse(text));
        assertTrue(e.getMessage().contains("pending"), e.getMessage());
    }

    @Test
    void wrongSchemaVersionFailsClosed() {
        String text = sample().format().replace("schema: 1", "schema: 2");
        assertThrows(IllegalArgumentException.class, () -> Finding.parse(text));
    }

    @Test
    void missingRequiredKeyFailsClosed() {
        String text = sample().format().replace("writer: model:qwen3.8-27b\n", "");
        var e = assertThrows(IllegalArgumentException.class, () -> Finding.parse(text));
        assertTrue(e.getMessage().contains("writer"), e.getMessage());
    }

    @Test
    void malformedSourceLineFailsClosed() {
        String text = sample().format().replaceFirst("source: .*\n", "source: just-a-url\n");
        var e = assertThrows(IllegalArgumentException.class, () -> Finding.parse(text));
        assertTrue(e.getMessage().contains("locator | edition | why"), e.getMessage());
    }

    @Test
    void badIdShapeFailsClosed() {
        String text = sample().format().replace(sample().id(), "finding-412");
        assertThrows(IllegalArgumentException.class, () -> Finding.parse(text));
    }

    @Test
    void investigationRoundTrip() {
        Investigation inv = new Investigation("I-0007-ja-alignment-models",
                "Which JA wav2vec2 alignment models are viable?",
                Finding.State.draft, "model:qwen3.8-27b", "2026-09-01T10:00:00Z",
                List.of("F-0412-yakuza-register-flattening"),
                List.of("word-level timing accuracy unmeasured"),
                "Sub-questions and synthesis prose.\n");
        assertEquals(inv, Investigation.parse(inv.format()));
    }

    @Test
    void investigationRejectsDisputedState() {
        Investigation inv = new Investigation("I-0007-x", "t", Finding.State.draft, "w", "t",
                List.of(), List.of(), "");
        String text = inv.format().replace("state: draft", "state: disputed");
        assertThrows(IllegalArgumentException.class, () -> Investigation.parse(text));
    }

    @Test
    void tripleAndNotesRoundTripAndStayOutsideTheHash() {
        Finding f = sample();
        String before = f.contentHash();
        Finding g = f.withTriple(new Finding.Triple("keigo", "has direct English equivalent", "none"))
                .withNote(new Finding.Note("inventory", "inventory", "2026-09-05", "source supports it | with a pipe kept"));
        assertEquals(g, Finding.parse(g.format()), "triple and note lines round-trip");
        assertTrue(g.format().contains("triple: keigo | has direct English equivalent | none"));
        assertTrue(g.format().contains("note: inventory | inventory | 2026-09-05 | source supports it | with a pipe kept"));
        assertEquals(before, g.contentHash(), "meta-facts and the machine reading are not reviewed substance");
        assertTrue(new Finding.Triple("Keigo ", "Has Direct English Equivalent", "None.").sameKey(g.triple()));
        assertTrue(new Finding.Triple("keigo", "has direct English equivalent", "a yes-marker").clashes(g.triple()));
        assertFalse(new Finding.Triple("keigo", "has direct English equivalent", "NONE").clashes(g.triple()), "same object, canonicalised");
        assertFalse(new Finding.Triple("register", "has direct English equivalent", "x").clashes(g.triple()));
        assertThrows(IllegalArgumentException.class, () -> Finding.parse(g.format().replace("note: inventory | inventory | 2026-09-05 |", "note: broken")));
    }
}
