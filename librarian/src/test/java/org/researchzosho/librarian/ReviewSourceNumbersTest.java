package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The reviewer's sources are numbered as the report's references number them (the live check of 2026-11-18, F3 item 2): the list numbered
 * them by their first appearance in the record, the model used the report's numbers, and four of six claims were filed with another
 * source (a claim of Coastal Facts filed with the society as its only source).
 */
class ReviewSourceNumbersTest {
    static final String SOCIETY = "https://merrow-society.example.org/ferry-years", COAST = "https://coastal-facts.org/kestrel-sound-ferry-closure";

    @Test
    void sTwoIsTheReportsOwnTwoInWhateverOrderTheRecordFirstNamesThem() {
        for (boolean coastFirst : new boolean[]{true, false}) {
            String body = "## Answer\n\nCoastal Facts claims the MV Corrie's sinking closed the ferry (" + (coastFirst ? COAST : SOCIETY) + "). "
                    + "The society says the causeway did (" + (coastFirst ? SOCIETY : COAST) + ").\n\n## References\n\n"
                    + "[1] The ferry years — " + SOCIETY + "\n[2] Coastal Facts: why the Kestrel Sound ferry closed — " + COAST + "\n\n2 source(s), 2 independent.\n";
            List<String> n = LibrarianReview.numbered(body);
            assertEquals(List.of(SOCIETY, COAST), n.subList(0, 2), "S1 and S2 are the report's [1] and [2], whichever the text names first");
            assertEquals(COAST, LibrarianReview.resolveCited(n, "S2"));
            assertEquals(COAST, LibrarianReview.resolveCited(n, "[2]"), "the report's own number reads the same");
            assertEquals(SOCIETY, LibrarianReview.resolveCited(n, "S1"));
            String prompt = LibrarianReview.extractPrompt(body, "");
            assertTrue(prompt.contains("[S1] " + SOCIETY + "\n  [S2] " + COAST), prompt);
        }
        // a number the references skip names nothing
        List<String> gap = LibrarianReview.numbered("## References\n\n[2] B — " + COAST + "\n");
        assertEquals(null, LibrarianReview.resolveCited(gap, "S1"));
        assertEquals(COAST, LibrarianReview.resolveCited(gap, "S2"));
    }
}
