package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What the cataloger asks about, and how often. A library whose subject list was empty asked the model about every one of its 2,296
 * claims after each research run and again every night, and filed none of them: with an empty list the model can only propose.
 */
class CatalogerScopeTest {

    @TempDir Path tmp;

    static Finding claim(String id, String title, String writer, String sourceNote) {
        return new Finding(id, title, List.of(), Finding.State.accepted, Finding.ClaimType.extraction, Finding.Confidence.high, writer,
                Instant.now().toString(), "2026-09-02", Finding.Volatility.stable, "",
                List.of(new Finding.Source("https://example.org/" + id, "n/a", sourceNote)), List.of(), null, title + ".\n");
    }

    LibraryStore library(String subjects) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib-" + System.nanoTime()));
        store.init();
        Files.writeString(store.subjectsFile(), "# Subjects\n\n" + subjects, StandardCharsets.UTF_8);
        return store;
    }

    /** A judge that proposes "tides--tables" for anything about tides, "misc--other" otherwise, and picks from the list when it can. */
    static Cataloger.Judge judge(List<String> asked) {
        return (vocab, text) -> {
            asked.add(text.lines().findFirst().orElse(""));
            boolean tides = text.toLowerCase().contains("tide");
            if (tides && vocab.contains("tides--tables")) return "{\"subjects\": [\"tides--tables\"], \"proposed\": []}";
            return "{\"subjects\": [], \"proposed\": [\"" + (tides ? "tides--tables: tide tables" : "misc--other: other") + "\"]}";
        };
    }

    @Test
    void familyClaimsAreLeftOutAndOrdinaryClaimsAreFiledTheSameAsInALibraryWithoutThem() throws Exception {
        // the same ordinary claim, in a library with family claims and in one without: the same question, the same subject
        List<String> asked = new ArrayList<>(), plain = new ArrayList<>();
        LibraryStore withFamily = library("- tides--tables — tide tables\n");
        withFamily.write(claim("F-0001-tide", "Harbour A tide tables agree", "model:t", "s"));
        withFamily.write(claim("F-0002-fam", "The placeholder ancestor was born in 1901", "family-account", "the family's account"));
        withFamily.write(claim("F-0003-ged", "Placeholder A married Placeholder B", "gedcom-import", "tree file"));
        var o = Cataloger.run(withFamily, judge(asked), false);
        LibraryStore without = library("- tides--tables — tide tables\n");
        without.write(claim("F-0001-tide", "Harbour A tide tables agree", "model:t", "s"));
        var p = Cataloger.run(without, judge(plain), false);
        assertEquals(List.of("Harbour A tide tables agree"), asked, "the family claims were not asked about");
        assertEquals(plain, asked);
        assertEquals(p.grounded(), o.grounded());
        assertEquals(List.of("tides--tables"), withFamily.finding("F-0001-tide").subjects());
        assertEquals(List.of(), withFamily.finding("F-0002-fam").subjects(), "a family's claim keeps no subject");
        assertEquals(0, Cataloger.waiting(without));
        assertEquals(0, Cataloger.waiting(withFamily));
    }

    @Test
    void aClaimThatGotOnlyProposalsIsNotAskedAgainUntilTheListOrTheClaimChanges() throws Exception {
        List<String> asked = new ArrayList<>();
        LibraryStore store = library("");
        store.write(claim("F-0001-tide", "Harbour A tide tables agree", "model:t", "s"));
        Cataloger.run(store, judge(asked), false);
        Cataloger.run(store, judge(asked), false);
        assertEquals(1, asked.size(), "the second pass does not ask the same thing again: " + asked);
        assertEquals(0, Cataloger.waiting(store));
        // the person accepts the proposal: the list changed, so the claim is asked again, and filed
        Cataloger.addToVocabulary(store, "tides--tables", "tide tables");
        assertEquals(1, Cataloger.waiting(store));
        Cataloger.run(store, judge(asked), false);
        assertEquals(2, asked.size());
        assertEquals(List.of("tides--tables"), store.finding("F-0001-tide").subjects());
    }

    @Test
    void afterARunOnlyItsOwnClaimsAreAskedAboutAndTheNightlyPassTakesAFewAtATime() throws Exception {
        List<String> asked = new ArrayList<>();
        LibraryStore store = library("- tides--tables — tide tables\n");
        for (int i = 1; i <= 5; i++) store.write(claim("F-000" + i + "-t", "Harbour " + i + " tide tables agree", "model:t", "s"));
        Cataloger.run(store, judge(asked), false, Set.of("F-0002-t"), 0);
        assertEquals(List.of("Harbour 2 tide tables agree"), asked, "only the run's own claim");
        asked.clear();
        Cataloger.run(store, judge(asked), false, null, 2);
        assertEquals(2, asked.size(), "at most the nightly limit");
        assertEquals(2, Cataloger.waiting(store));
    }

    @Test
    void seedingAddsTheSubjectsSeveralClaimsWereGivenThenFilesThemUnderIt() throws Exception {
        List<String> asked = new ArrayList<>();
        LibraryStore store = library("");
        store.write(claim("F-0001-a", "Harbour A tide tables agree", "model:t", "s"));
        store.write(claim("F-0002-b", "Harbour B tide gauge drifted", "model:t", "s"));
        store.write(claim("F-0003-c", "The ferry timetable changed in May", "model:t", "s"));
        store.write(claim("F-0004-fam", "The placeholder ancestor was born in 1901", "family-account", "the family's account"));
        Cataloger.Seeded s = Cataloger.seed(store, judge(asked), 2);
        assertEquals(3, s.asked(), "the family claim is left out");
        assertEquals(List.of("tides--tables"), s.added(), "misc--other was given to one claim only");
        assertEquals(2, s.filed());
        assertEquals(List.of("tides--tables"), store.finding("F-0001-a").subjects());
        assertEquals(List.of(), store.finding("F-0003-c").subjects());
        assertEquals(Set.of("tides--tables"), Cataloger.vocabulary(store).keySet());
    }
}
