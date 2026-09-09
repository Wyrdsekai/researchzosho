package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The machine's half of cataloging: only vocabulary slugs are ever written; the rest is proposal. */
class CatalogerTest {

    @TempDir Path tmp;

    private LibraryStore seeded() throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib"));
        store.init();
        Files.writeString(store.subjectsFile(), "# Subjects\n\n- translation--register — register and sociolect in translation\n"
                + "- japanese--sociolect — how Japanese speech marks relation\n", StandardCharsets.UTF_8);
        Finding f = new Finding("F-0001-keigo", "Keigo flattens in subtitles", List.of(), Finding.State.accepted,
                Finding.ClaimType.extraction, Finding.Confidence.high, "model:test", Instant.now().toString(),
                "2026-09-02", Finding.Volatility.stable, "", List.of(new Finding.Source("https://www.jstage.jst.go.jp/x", "n/a", "s")),
                List.of(), null, "Body.\n");
        Finding approved = new Finding(f.id(), f.title(), f.subjects(), f.state(), f.claimType(), f.confidence(), f.writer(),
                f.recordedAt(), f.validAsOf(), f.volatility(), f.reviewBy(), f.sources(), f.supersedes(),
                new Finding.Review(1, "librarian:t", "accepted", f.contentHash(), "t"), f.body());
        store.write(approved);
        return store;
    }

    @Test
    void vocabularyParsesSlugsAndDescriptions() throws Exception {
        var v = Cataloger.vocabulary(seeded());
        assertEquals(2, v.size());
        assertEquals("register and sociolect in translation", v.get("translation--register"));
    }

    @Test
    void onlyVocabularySlugsAreWritten_unknownsBecomeProposals_andApprovalSurvives() throws Exception {
        LibraryStore store = seeded();
        Cataloger.Judge judge = (vocab, text) ->
                "{\"subjects\": [\"translation--register\", \"minted--slug\"], \"proposed\": [\"crime-fiction--english: EN genre voice\"]}";
        var out = Cataloger.run(store, judge, false);
        assertEquals(1, out.grounded());
        assertEquals(1, out.proposals());
        Finding g = store.finding("F-0001-keigo");
        assertEquals(List.of("translation--register"), g.subjects(), "the minted slug never lands");
        assertFalse(g.reviewStale(), "cataloging is metadata — the approval survives");
        String proposed = Files.readString(store.subjectsFile().resolveSibling("subjects.proposed.md"));
        assertTrue(proposed.contains("- crime-fiction--english — EN genre voice"), proposed);
        assertEquals(2, Cataloger.vocabulary(store).size(), "vocabulary untouched without accept-all");
    }

    @Test
    void acceptAllAddsProposalsToTheVocabularyAndAppliesThem() throws Exception {
        LibraryStore store = seeded();
        Cataloger.Judge judge = (vocab, text) ->
                "{\"subjects\": [\"japanese--sociolect\"], \"proposed\": [\"crime-fiction--english: EN genre voice\"]}";
        var out = Cataloger.run(store, judge, true);
        assertEquals(1, out.grounded());
        assertEquals(3, Cataloger.vocabulary(store).size());
        assertEquals(List.of("japanese--sociolect", "crime-fiction--english"), store.finding("F-0001-keigo").subjects());
    }

    @Test
    void alreadyCatalogedFindingsAreLeftAlone() throws Exception {
        LibraryStore store = seeded();
        Cataloger.run(store, (v, t) -> "{\"subjects\": [\"translation--register\"], \"proposed\": []}", false);
        var out = Cataloger.run(store, (v, t) -> { throw new AssertionError("must not be called"); }, false);
        assertEquals(0, out.grounded());
    }
}
