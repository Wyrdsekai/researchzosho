package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Stopping;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A run stopped while its report is being settled: the claims the triples step had not reached are not written down as answered, so the
 * nightly housekeeping asks about them as it would have (a stop gives up the call in flight, and a step that took that for "no answer"
 * would have kept every remaining claim from being asked again).
 */
class StoppedSettleTest {

    @Test
    void aStopInTheTriplesStepLeavesTheClaimsItDidNotAnswerForTheNight(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        store.write(new Finding("F-0001-placeholder", "A placeholder claim about a placeholder topic", List.of(), Finding.State.accepted, Finding.ClaimType.extraction,
                Finding.Confidence.medium, "model:test", "2026-09-23T00:00:00Z", "2026-09-23", Finding.Volatility.slow, "",
                List.of(new Finding.Source("https://example.org/a", "n/a", "a")), List.of(), null, "The placeholder society was founded in 1900 by the placeholder people.\n"));
        assertThrows(Stopping.Requested.class, () -> Triples.fill(store, claim -> { throw new Stopping.Requested(); }, 5));
        Finding f = store.finding("F-0001-placeholder");
        assertTrue(f.notes().stream().noneMatch(n -> n.kind().equals("triples")), "not written down as asked: " + f.notes());
        assertEquals(1, Triples.fill(store, claim -> "{\"triple\": null}", 5).asked(), "the housekeeping asks about it");
    }
}
