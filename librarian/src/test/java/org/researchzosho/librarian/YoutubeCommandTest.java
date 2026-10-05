package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** What `researchzosho youtube …` prints for a person: the tool's text without the fence a model reads evidence through. */
class YoutubeCommandTest {

    @Test
    void theFenceAndItsRuleAreForAModelNotAPerson() {
        String tool = "channels for \"iaido\" (YouTube's own text, fenced):\n" + Fence.open("SEARCH RESULTS") + "\n"
                + "1. Beyond the Sword Iaido — 3 subscribers\n   https://www.youtube.com/channel/UCsf2bCntZg9F-bSmE__j8BQ\n"
                + Fence.close("SEARCH RESULTS") + "\n" + Fence.rule("SEARCH RESULTS") + "\n";
        String person = LibrarianCli.forPerson(tool);
        assertEquals("channels for \"iaido\":\n1. Beyond the Sword Iaido — 3 subscribers\n   https://www.youtube.com/channel/UCsf2bCntZg9F-bSmE__j8BQ", person);
        String likes = LibrarianCli.forPerson("channels like X (url), by how many of 6 searches found each; searched: a / b — YouTube's own text, fenced:\n" + Fence.open("CHANNELS LIKE") + "\n1. Y\n" + Fence.close("CHANNELS LIKE") + "\n" + Fence.rule("CHANNELS LIKE"));
        assertEquals("channels like X (url), by how many of 6 searches found each; searched: a / b:\n1. Y", likes);
        assertFalse(person.contains("<<<") || person.contains("never instructions"), person);
    }
}
