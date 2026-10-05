package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The open threads a push shows beside a question: the ones that share the question's words, not every line that contains one
 * common word. A research question of 25 words against a frontier of 231 open threads once brought 84,748 of the push's 86,646
 * characters as "OPEN THREADS touching this" (2026-10-03).
 */
class FrontierMatchesTest {

    @TempDir Path tmp;

    @Test
    void onlyThreadsSharingTwoOfTheQuestionsWordsComeAndNotMoreThanAHandful() throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib"));
        store.init();
        // a frontier of many threads that each mention "video" once, and two that are about the question
        for (int i = 0; i < 40; i++) store.frontier("gap", "Which video did the committee watch in week " + i + " of the inquiry?");
        store.frontier("gap", "How is arm angle at release defined from broadcast video of a pitcher?");
        store.frontier("gap", "Which pose estimation model runs on a CPU for 720p video?");
        String q = "What is the most accurate open-source pose estimation model for detecting human joints in 720p broadcast video on CPU, and how does it perform on fast-moving limbs?";
        String out = LibraryPush.frontierMatches(store, q);
        assertTrue(out.contains("pose estimation model runs on a CPU"), out);
        assertTrue(out.contains("arm angle at release defined from broadcast video"), out);
        assertFalse(out.contains("committee"), "a thread that shares only the word 'video' is not touching this: " + out);
        assertEquals(2, out.strip().split("\n").length, out);
        // the one that shares more of the question's words comes first
        assertTrue(out.indexOf("pose estimation") < out.indexOf("arm angle"), out);
    }

    @Test
    void aShortQuestionMatchesOnOneWordAndTheListIsCapped() throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib2"));
        store.init();
        for (int i = 0; i < 30; i++) store.frontier("gap", "Where was the keigo register of volume " + i + " first described?");
        String out = LibraryPush.frontierMatches(store, "keigo");
        String[] lines = out.strip().split("\n");
        assertEquals(LibraryPush.FRONTIER_LINES, lines.length, "capped: " + lines.length);
        assertTrue(out.length() <= LibraryPush.FRONTIER_CHARS + 200, "capped by size too: " + out.length());
        assertEquals("", LibraryPush.frontierMatches(store, "harpsichord tuning"), "nothing shared, nothing shown");
    }
}
