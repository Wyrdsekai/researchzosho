package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Two ways a run's knowledge used to be lost between the write-up and the shelves: the reviewer took its claims from
 * the first sections only, and a claim the cataloguer could not place kept no subject at all — and a claim with no
 * subject is in no area, so the map, a scoped search and bridges never see it (measured 2026-09-15 on a bicycle run
 * whose record carried tube layups, fatigue and load testing and whose ten claims were eight about handling).
 */
class ReviewCoverageTest {

    static final String RECORD = """
            ## Question

            Bicycle frame geometry and design.

            ## Answer (as submitted by the run — draft until reviewed)

            ## Head Angle, Trail, and Self-Stability

            Trail sets the speed at which a frame is stable [1].

            ## Tube Shapes and Material Layups

            A carbon layup sets stiffness independently of the tube's outside shape [2].

            ## Fatigue Failure: Metal vs. Composite

            Aluminium has no fatigue limit; composite fails by delamination [3].

            ## Safety Certification and Load Testing

            ISO 4210 sets the load cases a production frame must survive [4].

            ## Sources

            [1] https://example.org/a
            [2] https://example.org/b

            ## Cite-check

            nothing to report

            ## Evidence

            rows
            """;

    @Test
    void theReviewerIsToldToCoverEverySectionOfTheRecord() {
        List<String> secs = LibrarianReview.sections(RECORD);
        assertEquals(List.of("Head Angle, Trail, and Self-Stability", "Tube Shapes and Material Layups",
                "Fatigue Failure: Metal vs. Composite", "Safety Certification and Load Testing"), secs,
                "the frame the runner always writes is not a section");
        String rule = LibrarianReview.sectionRule(RECORD);
        assertTrue(rule.contains("4 sections") && rule.contains("Tube Shapes and Material Layups") && rule.contains("at least one finding for EACH"), rule);
        assertTrue(rule.contains("4 to 12 findings"), rule);
        String prompt = LibrarianReview.extractPrompt(RECORD);
        assertTrue(prompt.contains("Fatigue Failure: Metal vs. Composite"), "the sections reach the model");
        assertTrue(LibrarianReview.sectionRule("no headings here").startsWith("one finding for each distinct thing"), "a record with no sections still has a rule");
        // a long record raises the ceiling instead of cutting sections off
        StringBuilder many = new StringBuilder();
        for (int i = 1; i <= 14; i++) many.append("## Section ").append(i).append("\n\ntext\n\n");
        assertTrue(LibrarianReview.sectionRule(many.toString()).contains("14 to 17 findings"), LibrarianReview.sectionRule(many.toString()));
    }

    @Test
    void aClaimTheCataloguerCannotPlaceGoesWhereItsRunWent(@TempDir Path home) throws Exception {
        LibraryStore store = new LibraryStore(home.resolve("lib")); store.init();
        java.nio.file.Files.writeString(store.subjectsFile(), "# Subjects\n\n- cycling--frames — bicycle frames\n");
        store.write(BridgesTest.claim("cycling--frames", "Trail sets the stable speed", "Trail sets the speed at which a frame is stable.", "trail", "sets", "stable speed")
                .withNote(new Finding.Note("x", "y", "2026-09-15", "z")));
        // two claims from the same run: one grounded, one the cataloguer will not place
        Finding placed = store.scanFindings().findings().get(0);
        store.write(new Finding(placed.id(), placed.title(), placed.subjects(), placed.state(), placed.claimType(), placed.confidence(), placed.writer(),
                placed.recordedAt(), placed.validAsOf(), placed.volatility(), placed.reviewBy(),
                List.of(new Finding.Source("https://example.org/a", "n/a", "cited by I-0002-bicycle-frame-geometry")), placed.supersedes(), placed.review(), placed.body(), placed.triple(), placed.notes()));
        store.write(new Finding("F-0099-isotropic-metal-stiffness", "Isotropic metal stiffness coupling", List.of(), Finding.State.draft,
                Finding.ClaimType.extraction, Finding.Confidence.high, "patron:person", "2026-09-15T00:00:00Z", "2026-09-15", Finding.Volatility.stable, "",
                List.of(new Finding.Source("https://example.org/b", "n/a", "cited by I-0002-bicycle-frame-geometry")), List.of(), null,
                "A metal tube's cross-section determines its torsional and lateral stiffness together.\n",
                new Finding.Triple("metal tube cross-section", "determines", "torsional stiffness"), List.of()));
        assertEquals("I-0002-bicycle-frame-geometry", Cataloger.origin(store.finding("F-0099-isotropic-metal-stiffness")));
        assertEquals(List.of("cycling--frames"), Cataloger.fromItsRun(store, store.finding("F-0099-isotropic-metal-stiffness")));
        // the cataloguer says nothing matches; the claim still lands in its run's area, with a note saying why
        Cataloger.Outcome o = Cataloger.run(store, (vocab, text) -> "{\"subjects\": [], \"proposed\": []}", false);
        assertEquals(1, o.grounded());
        Finding after = store.finding("F-0099-isotropic-metal-stiffness");
        assertEquals(List.of("cycling--frames"), after.subjects());
        assertTrue(after.notes().stream().anyMatch(n -> n.kind().equals("catalog") && n.text().contains("I-0002-bicycle-frame-geometry")), after.notes().toString());
        // and it is now in the area bridges reads
        assertTrue(Bridges.areas(store).stream().anyMatch(a -> a.slug().equals("cycling--frames")
                && a.findings().stream().anyMatch(f -> f.id().equals("F-0099-isotropic-metal-stiffness"))), "the claim is in an area now");
        // a claim with no run and no match is left alone
        store.write(new Finding("F-0098-orphan", "Orphan", List.of(), Finding.State.draft, Finding.ClaimType.extraction, Finding.Confidence.low,
                "person", "2026-09-15T00:00:00Z", "2026-09-15", Finding.Volatility.stable, "",
                List.of(new Finding.Source("https://example.org/c", "n/a", "patron submission")), List.of(), null, "Something with no run.\n", null, List.of()));
        assertEquals(0, Cataloger.run(store, (vocab, text) -> "{\"subjects\": [], \"proposed\": []}", false).grounded());
        assertTrue(store.finding("F-0098-orphan").subjects().isEmpty());
    }
}
