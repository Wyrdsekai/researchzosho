package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A 2026 arXiv id was dated 2025 in every write-up when the page carried no date (measured 2026-09-12); the id says the month. */
class ArxivDateTest {
    @Test
    void theIdSaysTheMonth() {
        assertEquals("2026-06", Citations.arxivPosted("https://arxiv.org/abs/2606.09498"));
        assertEquals("2026-08", Citations.arxivPosted("arXiv:2608.24306v2"));
        assertEquals("2025-12", Citations.arxivPosted("see 2512.01948 for the split"));
        assertEquals("", Citations.arxivPosted("DOI 10.1000/182 and version 1.2.34"));
        assertEquals("", Citations.arxivPosted("the 5200.12345 order number"), "a year past 2040 is not a paper id");
        assertEquals("", Citations.arxivPosted("2613.00001"), "month 13 is not a month");
    }

    @Test
    void aNoteOnAnArxivSourceCarriesThePostingMonth() {
        var nb = new Researcher.Notebook();
        var args = new ObjectMapper().createObjectNode();
        args.put("claim", "Self-Harness edits only the harness"); args.put("source", "https://arxiv.org/abs/2606.09498"); args.put("quote", "weights stay fixed");
        nb.execute(args);
        assertTrue(nb.render().contains("(arXiv, posted 2026-06)"), nb.render());
    }

    @Test
    void progressWordsSayNoCeilingInsteadOfZero() {
        var p = new ObjectMapper().createObjectNode();
        p.put("phase", "workers"); p.put("round", 1); p.put("rounds", 2); p.put("workers_done", 3); p.put("workers_total", 8); p.put("turns_used", 41); p.put("turns_ceiling", 0);
        String w = LibrarianCli.progressWords(p);
        assertEquals("workers, round 1 of up to 2, workers 3/8, turns 41, no turn ceiling", w);
        p.put("turns_ceiling", 200);
        assertTrue(LibrarianCli.progressWords(p).contains("turns 41 of 200"), LibrarianCli.progressWords(p));
        p.put("deadline_at", java.time.Instant.now().plusSeconds(600).toString());
        assertTrue(LibrarianCli.progressWords(p).matches(".*, (9|10) min left"), LibrarianCli.progressWords(p));
    }
}
