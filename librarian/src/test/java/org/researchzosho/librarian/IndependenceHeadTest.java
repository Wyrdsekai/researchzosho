package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Two outlets, one wire story: the same opening paragraphs under different headlines are one source. */
class IndependenceHeadTest {
    @Test
    void sameOpeningIsOneText() {
        String wire = "LONDON, Sept 12 (Wire) - The central bank held rates on Thursday, citing persistent services inflation and a labour market that has cooled only slowly, and signalled that any cut would wait for clearer evidence that wage growth is easing. Policymakers voted seven to two to keep the benchmark at 4.25 percent, the level it has held since May, after data this week showed core prices rising faster than forecast. ";
        String a = wire + "Analysts at three banks said the vote split was wider than expected.";
        String b = wire + "The pound rose after the decision, while gilt yields edged higher through the afternoon session as traders pared back bets on a November move.";
        assertTrue(Independence.sameHead(a, b));
        assertTrue(Independence.bodiesAfterHeadOverlap(a + a, b + b), "a wire story shares its body past the head too");
        String other = "TOKYO, Sept 12 - The yen weakened past 150 to the dollar on Thursday as the Bank of Japan kept policy unchanged and gave no signal on the timing of its next step, disappointing investors who had positioned for a hawkish turn after last week's wage data showed the strongest gains in three decades. ";
        assertFalse(Independence.sameHead(a, other + other));
        assertFalse(Independence.sameHead("short", a), "a short text has no head to compare");
    }
}

class IndependenceCitingTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp;

    @Test
    void citingIsNotACopyAndDoesNotChain() throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        String paperA = "https://arxiv.org/abs/2601.11111", paperB = "https://arxiv.org/abs/2602.22222", survey = "https://zylos.example/survey";
        String filler = " Lorem ipsum dolor sit amet consectetur adipiscing elit sed do eiusmod tempor incididunt ut labore et dolore magna aliqua ".repeat(12);
        RawCapture.capture(store, paperA, "Paper A studies gears." + filler + "gears " + filler, "Paper A", "test", "");
        RawCapture.capture(store, paperB, "Paper B studies levers." + filler.replace("Lorem", "Quorem") + "levers " + filler.replace("ipsum", "opsum"), "Paper B", "test", "");
        RawCapture.capture(store, survey, "A survey of both: see https://arxiv.org/abs/2601.11111 and https://arxiv.org/abs/2602.22222 for the details. " + filler.replace("dolor", "color"), "A survey", "test", "");
        var locs = java.util.List.of(paperA, paperB, survey);
        var clusters = Independence.clusters(store, locs);
        assertEquals(3, new java.util.HashSet<>(clusters.values()).size(), "three distinct texts: " + clusters);
        var deriv = Independence.derivatives(store, locs);
        assertEquals(paperA, deriv.get(survey), "the survey cites paper A");
        assertNull(deriv.get(paperA)); assertNull(deriv.get(paperB));
        assertEquals(2, Independence.independent(store, locs), "two voices: the papers; the survey stands on them");
    }
}

class IndependenceSiteChromeTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp;

    @Test
    void sharedSiteChromeOverDifferentBodiesIsNotOneText() throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        String chrome = "Skip to content Navigation Menu Sign in Appearance settings Product Solutions Resources Open Source Enterprise Pricing Search or jump to Sign in Sign up Pull requests Issues Marketplace Explore ".repeat(5);
        StringBuilder ba = new StringBuilder(chrome), bb = new StringBuilder(chrome);
        for (int i = 0; i < 40; i++) {
            ba.append("Step ").append(i).append(": the extension keeps citation ").append(i * 7).append(" attached when a note is copied from the notebook into document ").append(i * 3).append(". ");
            bb.append("Connector ").append(i).append(": permissions for workspace ").append(i * 5).append(" are mirrored from the source system before indexing batch ").append(i * 11).append(". ");
        }
        String a = ba.toString(), b = bb.toString();
        RawCapture.capture(store, "https://github.example/nicremo/notebooklm-citation", a, "GitHub - nicremo/notebookLM-citation", "test", "");
        RawCapture.capture(store, "https://github.example/onyx-dot-app/onyx", b, "GitHub - onyx-dot-app/onyx", "test", "");
        var c = Independence.clusters(store, java.util.List.of("https://github.example/nicremo/notebooklm-citation", "https://github.example/onyx-dot-app/onyx"));
        assertEquals(2, new java.util.HashSet<>(c.values()).size(), "two GitHub pages are two texts: " + c);
        assertFalse(Independence.bodiesAfterHeadOverlap(a, b));
    }
}
