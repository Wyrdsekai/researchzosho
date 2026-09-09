package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RetractionsTest {
    private static Finding f(String id, String locator) {
        return new Finding(id, "A claim from " + locator, List.of(), Finding.State.accepted, Finding.ClaimType.extraction, Finding.Confidence.high,
                "person", Instant.now().toString(), "2026-09-08", Finding.Volatility.stable, "",
                List.of(new Finding.Source(locator, "n/a", "the paper")), List.of(), null, "The paper says so.\n");
    }

    @Test
    void aRetractedSourceDisputesTheClaimAndAConcernIsNoted(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        store.write(f("F-0001-retracted", "https://doi.org/10.1000/retracted"));
        store.write(f("F-0002-concern", "https://doi.org/10.1000/concern"));
        store.write(f("F-0003-fine", "https://doi.org/10.1000/fine"));
        store.write(f("F-0004-noweb", "https://example.org/no-doi"));
        Retractions.Lookup lookup = doi -> switch (doi) {
            case "10.1000/retracted" -> new Retractions.Notice("retraction", "2026-03-01", "10.1000/retracted-notice");
            case "10.1000/concern" -> new Retractions.Notice("expression_of_concern", "2026-05-02", "");
            default -> null;
        };
        Retractions.Outcome o = Retractions.check(store, lookup, 10, LocalDate.of(2026, 9, 8));
        assertEquals(3, o.checked(), "only DOIs are looked up: " + o);
        assertEquals(1, o.retracted()); assertEquals(1, o.concerns());
        Finding r = store.finding("F-0001-retracted");
        assertEquals(Finding.State.disputed, r.state());
        assertTrue(r.notes().stream().anyMatch(n -> n.kind().equals("retracted") && n.text().contains("10.1000/retracted") && n.text().contains("2026-03-01")), r.notes().toString());
        Finding c = store.finding("F-0002-concern");
        assertEquals(Finding.State.accepted, c.state(), "a concern does not dispute");
        assertTrue(c.notes().stream().anyMatch(n -> n.kind().equals("concern")));
        assertEquals(Finding.State.accepted, store.finding("F-0003-fine").state());
        // remembered: the next night looks nothing up again within thirty days, and does not note twice
        Retractions.Outcome again = Retractions.check(store, doi -> { throw new AssertionError("looked up again"); }, 10, LocalDate.of(2026, 9, 9));
        assertEquals(0, again.checked());
        assertEquals(1, store.finding("F-0001-retracted").notes().stream().filter(n -> n.kind().equals("retracted")).count());
        assertTrue(Files.readString(Retractions.file(store)).contains("10.1000/retracted\t2026-09-08\tretraction"));
    }

    @Test
    void crossrefNoticeListsAreRead() throws Exception {
        var m = new ObjectMapper();
        assertNull(Retractions.parse(m.readTree("{\"message\":{\"items\":[]}}"), "10.1/x"));
        // the shape Crossref answered on 2026-09-08 for works?filter=updates:10.1016/S0140-6736(97)11096-0 — a correction in 2004, the retraction in 2010
        String live = "{\"message\":{\"total-results\":2,\"items\":["
                + "{\"DOI\":\"10.1016/s0140-6736(04)15715-2\",\"type\":\"journal-article\",\"update-to\":[{\"DOI\":\"10.1016/s0140-6736(97)11096-0\",\"type\":\"correction\",\"label\":\"Correction\",\"source\":\"retraction-watch\",\"updated\":{\"date-time\":\"2004-03-06T00:00:00Z\"}}]},"
                + "{\"DOI\":\"10.1016/s0140-6736(10)60175-4\",\"type\":\"journal-article\",\"update-to\":[{\"DOI\":\"10.1016/s0140-6736(97)11096-0\",\"type\":\"retraction\",\"label\":\"Retraction\",\"source\":\"retraction-watch\",\"updated\":{\"date-time\":\"2010-02-06T00:00:00Z\"}}]}]}}";
        var r = Retractions.parse(m.readTree(live), "10.1016/S0140-6736(97)11096-0");
        assertEquals("retraction", r.type()); assertEquals("2010-02-06", r.date()); assertEquals("10.1016/s0140-6736(10)60175-4", r.noticeDoi());
        // a notice about some other DOI in the same list does not count
        assertNull(Retractions.parse(m.readTree(live), "10.1016/other"));
        assertTrue(Retractions.retracts("partial_retraction") && !Retractions.retracts("expression_of_concern") && !Retractions.retracts("erratum"));
    }
}
