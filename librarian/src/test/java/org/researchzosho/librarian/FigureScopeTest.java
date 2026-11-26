package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The figure check of a report against every source read in its run, and a decade read as years. From the Everest run of 2026-11-25
 * (rz-087, q27-b, the 27B): "Mount Everest's current official height is 8,848.86 m (29,031.7 ft) … [5]" was flagged "number 29031 — not
 * in any note or source read this run" and the revision deleted the true figure, while the kept Wikipedia and AP pages both state it; the
 * cited [5] was never kept. The same report's "trigonometric surveying (1850s)" was flagged as "1850 s", a decade read as seconds.
 */
class FigureScopeTest {
    /** The kept pages' own lines, as the run kept them: Wikipedia's infobox (no-break spaces) and the AP sentence. */
    static final String WIKIPEDIA = "Mount Everest\n Elevation | 8,848.86  m (29,031.7  ft) [ note 2 ] \n Prominence | 8,848.86 m (29,031.7 ft) Ranked 1st";
    static final String AP = "The new height of the world’s highest peak is 8,848.86 meters (29,031.7 feet), which is slightly more than Nepal’s previous measurement and about four meters (13 feet) higher than China’s.";
    static final String EVEREST = "Mount Everest's current official height is 8,848.86 m (29,031.7 ft), announced jointly by Nepal and China on 8 December 2020 [5].";
    static final List<CiteCheck.Ref> REFS = List.of(new CiteCheck.Ref(3, "https://en.wikipedia.org/wiki/Mount_Everest", ""),
            new CiteCheck.Ref(5, "https://kathmandupost.com/national/2020/12/08/it-s-official-mount-everest-is-8-848-86-metres-tall", ""),
            new CiteCheck.Ref(7, "https://apnews.com/article/science-nepal-china-58bae27255ab0865f97c303c4ebc3aa8", ""));
    /** A worker's note of that run: the height in metres, and no feet. */
    static final String NOTES = "- A Nepal government team installed GPS and satellite equipment on the summit of Everest in May 2019; the resulting official height was announced as 8,848.86 m on 8 December 2020. — source: https://apnews.com/article/x";

    @Test
    void aFigureAnotherKeptSourceStatesIsNotFlaggedAndIsNamedWithWhereItIs() {
        Map<Integer, String> kept = Map.of(3, WIKIPEDIA, 7, AP);   // [5] was not kept
        assertEquals(List.of(), WriteupChecks.numbersUnbacked(EVEREST, NOTES, REFS, kept), "two kept pages state it: not \"in no source read this run\"");
        List<WriteupChecks.Elsewhere> e = WriteupChecks.numbersElsewhereAt(EVEREST, NOTES, REFS, kept, Map.of());
        assertEquals(1, e.size(), e.toString());
        assertEquals("29031.7 ft", e.get(0).what(), "29,031.7 is one number, never 29031");
        assertEquals("the sentence cites [5], which was not kept this run; [3], [7], read this run, state it", e.get(0).said());
        // a page read this run that is not among the references counts too, named by its address
        List<WriteupChecks.Elsewhere> page = WriteupChecks.numbersElsewhereAt(EVEREST, NOTES, REFS, Map.of(), Map.of("https://apnews.com/article/x", AP));
        assertEquals("the sentence cites [5], which was not kept this run; https://apnews.com/article/x, read this run, states it", page.get(0).said());
        // the control: in nothing kept, it is flagged, and the line is true
        assertEquals(1, WriteupChecks.numbersUnbacked(EVEREST.replace("29,031.7", "29,035.2"), NOTES, REFS, kept).size());
        // a cited source that was kept and does not state it, beside one that does not
        List<WriteupChecks.Elsewhere> silent = WriteupChecks.numbersElsewhereAt(EVEREST.replace("[5]", "[5][3]"), NOTES, REFS, Map.of(3, "Mount Everest is the highest mountain.", 7, AP), Map.of());
        assertEquals("the sentence cites [5], not kept this run, and [3], which does not state it; [7], read this run, states it", silent.get(0).said());
    }

    @Test
    void aSentenceWhoseFigureAKeptSourceStatesIsNotMarkedSoNeverRevised() {
        Map<Integer, String> kept = Map.of(3, WIKIPEDIA, 7, AP);
        List<WriteupChecks.Unbacked> marked = WriteupChecks.numbersUnbackedAt(EVEREST, NOTES, REFS, kept);
        assertEquals(EVEREST, WriteupChecks.markInline(EVEREST, marked, "number not in any note or source read this run: "), "nothing marked, so nothing goes to the revision");
    }

    @Test
    void aSmallFigureCountsInAnUncitedSourceOnlyWithItsUnit() {
        List<CiteCheck.Ref> refs = List.of(new CiteCheck.Ref(1, "https://a.example/board", ""), new CiteCheck.Ref(2, "https://b.example/other", ""));
        Map<Integer, String> texts = Map.of(1, "GPT-5.6 Sol leads at 92.2% on the board.", 2, "Of 58 people asked, 40 said yes; elsewhere 37% did.");
        assertEquals(List.of(), WriteupChecks.numbersUnbacked("Turnout was 37% in the second poll [1].", "", refs, texts), "37% as the other source writes it");
        assertEquals(1, WriteupChecks.numbersElsewhereAt("Turnout was 37% in the second poll [1].", "", refs, texts, Map.of()).size());
        assertEquals(1, WriteupChecks.numbersUnbacked("Scores reached 58% [1].", "", refs, texts).size(), "58 people is not 58%: a two-digit number any page holds");
    }

    @Test
    void aDecadeIsYearsNotSeconds() {
        // the record's sentence and the plain case: flagged as "1850 s" and "1990 s" before
        String record = "The figure has changed over the years for five interrelated reasons: (1) measurement technology improved from trigonometric surveying (1850s) to satellite geodesy (1999–2020) [1].";
        assertEquals(List.of(), WriteupChecks.numbersUnbacked(record, "- surveys of 1852 and 1856 — source: x", List.of(), Map.of()));
        for (String s : List.of("Prices rose sharply in the 1990s.", "Prices rose sharply in the 1990s and 2000s.", "It was a hit in the '90s.", "It was a hit in the ’90s.",
                "It was a hit in the 90s.", "It peaked in the mid-90s.", "Ein Hit der 1850er Jahre.", "1850年代に測量された。", "1850년대에 측량되었다."))
            assertEquals(List.of(), WriteupChecks.numbersUnbacked(s, ""), s);
        // the controls: a duration in seconds is still a quantity
        assertEquals(1, WriteupChecks.numbersUnbacked("The survey took 1850 s.", "").size(), "written with a space, seconds");
        assertEquals(1, WriteupChecks.numbersUnbacked("The machine boots in 90s.", "").size(), "no 'the' or apostrophe: 90 seconds");
        assertEquals(1, WriteupChecks.numbersUnbacked("The run took 1855s.", "").size(), "not a decade: 1855 seconds");
    }
}
