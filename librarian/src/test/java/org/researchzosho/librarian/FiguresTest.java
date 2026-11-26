package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A report's figures against the notes and sources it was written from, read the way each text writes its numbers. From the research
 * look of 2026-11-24: the sentence "UK Biobank (~386,000 adults) …" was flagged as stating a number nothing held (the worker's note said
 * 386,258; the JAMA page, which the run kept, writes 386 258 with narrow no-break spaces), and the writer, asked to revise it, put in a
 * wrong figure from another study.
 */
class FiguresTest {
    /** The worker's note of that run, word for word. */
    static final String KIM_NOTE = "In UK Biobank (386,258 participants, mean age 56, 52.3% female, mean follow-up 4.5 y, 16,979 incident arrhythmias), after adjustment for "
            + "demographic characteristics, comorbid conditions, and lifestyle habits, each additional daily cup of habitual coffee was associated with 3% lower risk of "
            + "incident arrhythmia (HR 0.97; 95% CI 0.96–0.98); for AF/flutter specifically HR 0.97 (0.96–0.98); Mendelian randomization showed no significant association. "
            + "— source: Kim et al., JAMA Internal Medicine 2021, via JAMA Network (archived copy)";
    /** The JAMA page's own sentences as the run kept them: every grouped number with a narrow no-break space. */
    static final String JAMA = "After exclusion criteria were applied, 386 258 individuals were available for analyses. A total of 386 258 individuals (mean [SD] age, 56 [8] years; "
            + "52.3% female) were assessed. During a mean (SD) follow-up of 4.5 (3.1) years, 16 979 participants developed an incident arrhythmia. Among 502 543 UK Biobank "
            + "participants, a median of 2 cups (IQR, 1-4 cups) of coffee were consumed per day, and 111 218 participants (22.1%) did not drink coffee.";
    static final List<CiteCheck.Ref> REFS = List.of(new CiteCheck.Ref(4, "https://jamanetwork.example/fullarticle/2782015", "", "Kim 2021"));

    static List<String> off(String sentence, String notes, String source) {
        return WriteupChecks.numbersUnbacked(sentence, notes, REFS, source == null ? Map.of() : Map.of(4, source));
    }
    static List<WriteupChecks.Unbacked> unsettled(String sentence, String notes, String source) {
        return WriteupChecks.numbersUnsettledAt(sentence, notes, REFS, source == null ? Map.of() : Map.of(4, source));
    }

    @Test
    void theRecordsSentenceIsBackedByTheNoteItWasWrittenFrom() {
        String sentence = "UK Biobank (~386,000 adults): each additional daily cup HR 0.97 (0.96–0.98) for incident arrhythmias and AF/flutter specifically [4].";
        assertEquals(List.of(), off(sentence, KIM_NOTE, JAMA), "~386,000 is 386,258 rounded to the thousand");
        assertEquals(List.of(), off(sentence, "", JAMA), "and the source alone backs it too, its 386 258 read as one number");
    }

    @Test
    void aSourceGroupsDigitsItsOwnWay() {
        String sentence = "UK Biobank enrolled 386,258 participants after exclusions [4].";
        for (String source : List.of("386 258 individuals", "386 258 individuals", "386 258 individuals", "386'258 Personen", "386’258 Personen",
                "3,86,258 participants", "Es wurden 386.258 Personen untersucht; 52,3 % waren Frauen.", "UK Biobank の 38万6258人", "Population 1 234 567 in all, and 386 258 here")) {
            assertEquals(List.of(), off(sentence, "", source), "read as one number: " + source);
        }
        // and the report may write it as the source does: the figure is read whole, and checked
        assertEquals(List.of(), off("UK Biobank enrolled 386 258 participants [4].", KIM_NOTE, null));
        assertEquals(1, off("UK Biobank enrolled 391 258 participants [4].", KIM_NOTE, JAMA).size(), "a spaced figure no source holds is now checked, and flagged");
        assertEquals(1, off(sentence, "", "about 386 trials and 258 sites").size(), "two numbers a sentence apart are not one");
    }

    @Test
    void whichRoundingsCount() {
        // a figure written round counts when the evidence rounds to it at the place of its last significant digit
        assertEquals(List.of(), off("About 386,000 adults took part [4].", "", JAMA));
        assertEquals(List.of(), off("Some ~390,000 adults took part [4].", "", JAMA), "390,000 is 386,258 to the ten thousand");
        assertEquals(List.of(), off("UK Biobank followed 386,000 adults [4].", "", JAMA), "386,000 as an exact count: the check cannot tell it from a rounded one");
        assertEquals(List.of(), off("UK Biobank followed 386,300 adults [4].", "", JAMA), "two trailing zeros: to the hundred");
        assertEquals(List.of(), off("UK Biobank followed over 380,000 adults [4].", "", JAMA), "a lower bound the evidence meets");
        assertEquals(List.of(), off("UK Biobank followed nearly 390,000 adults [4].", "", JAMA), "an upper bound the evidence approaches");
        assertEquals(List.of(), off("UK Biobank followed about 400,000 adults [4].", "", JAMA), "said to be approximate: to the hundred thousand");
        assertEquals(List.of(), off("UK Biobank followed 386 thousand adults [4].", "", JAMA));
        assertEquals(List.of(), off("UK Biobank followed 0.39 million adults [4].", "", JAMA));
        assertEquals(List.of(), off("UK Biobank の約38.6万人 [4].", "", JAMA));
        assertEquals(List.of(), off("UK Biobank の38万人以上 [4].", "", JAMA), "以上: at least");
        assertEquals(List.of(), off("About 22% did not drink coffee [4].", "", JAMA), "22.1% to the unit, said to be approximate");
        // the near misses
        assertEquals(1, off("Some ~385,000 adults took part [4].", "", JAMA).size(), "386,258 rounds to 386,000, not 385,000");
        assertEquals(1, off("UK Biobank followed 386,250 adults [4].", "", JAMA).size(), "one trailing zero is a figure to the ten: it must be the same number");
        assertEquals(1, off("UK Biobank followed over 390,000 adults [4].", "", JAMA).size(), "386,258 is not over 390,000");
        assertEquals(1, off("UK Biobank followed under 380,000 adults [4].", "", JAMA).size());
        assertEquals(1, off("UK Biobank followed 1.3 million adults [4].", "", JAMA).size());
        assertEquals(1, off("Some 22% did not drink coffee, and 37% drank decaf [4].", "", JAMA).size(), "37 is in nothing");
        assertEquals(1, off("23% drank decaf.", "- 22.6% drank decaf — source: x", null).size(), "a small figure written plainly is not rounded: 23% is not 22.6%");
        assertEquals(List.of(), off("About 23% drank decaf.", "- 22.6% drank decaf — source: x", null), "said to be approximate, it is");
        // never the other way round: a precise figure is not backed by a rounder one
        assertEquals(1, off("UK Biobank followed 386,258 adults.", "- UK Biobank: ~386,000 adults — source: x", null).size());
        assertEquals(1, off("UK Biobank followed 386,258 adults.", "- UK Biobank: 386 thousand adults — source: x", null).size());
        // a whole number of one significant digit, written plainly: the check cannot settle it
        assertEquals(List.of(), off("UK Biobank followed 400,000 adults [4].", "", JAMA), "not called unbacked");
        assertEquals(1, unsettled("UK Biobank followed 400,000 adults [4].", "", JAMA).size(), "named instead");
    }

    @Test
    void aReadingTheSourceDoesNotSettleLeavesTheFigureUnsettled() {
        // a lone 1.234 in a text that shows neither use of the full stop: 1234 or 1.234
        String source = "The trial enrolled 1.234 patients in Basel.";
        assertEquals(List.of(), off("The trial enrolled 1,234 patients [4].", "", source), "never called unbacked on a reading the source does not decide");
        assertEquals(1, unsettled("The trial enrolled 1,234 patients [4].", "", source).size());
        // the same text with its own way shown: the full stop groups
        String german = "The trial enrolled 1.234 patients in Basel; 12,5 % were women and the region has 1.234.567 people.";
        assertEquals(List.of(), off("The trial enrolled 1,234 patients [4].", "", german));
        assertEquals(List.of(), unsettled("The trial enrolled 1,234 patients [4].", "", german));
        // and an English text: the full stop is a decimal point, so 1.234 is no count
        String english = "The trial enrolled 1.234 kg of doses for 1,500,000 people at a 2.5% rate.";
        assertEquals(1, off("The trial counted 1,234 patients [4].", "", english).size(), "1.234 here is a decimal");
    }

    @Test
    void theReaderReadsEveryGrouping() {
        assertEquals(new BigDecimal("386258"), Figures.eastAsian("38万6258"));
        assertEquals(new BigDecimal("38000"), Figures.eastAsian("3万8千"));
        assertEquals(new BigDecimal("120000000"), Figures.eastAsian("1億2000万"));
        assertEquals(new BigDecimal("50000000"), Figures.eastAsian("5千万"));
        assertEquals(new BigDecimal("350000000"), Figures.eastAsian("3억 5천만"));
        assertEquals(0, new BigDecimal("386000").compareTo(Figures.eastAsian("38.6万")));
        Figures.Read r = Figures.read("1'234'567 und 12,34,567 und 1.234.567,5 und 1,234,567.5 und 386 258");
        for (String v : List.of("1234567", "1234567.5", "386258")) assertTrue(r.certain().contains(Figures.norm(new BigDecimal(v))), v + " in " + r);
        assertEquals(Figures.Convention.DOT_GROUPS, Figures.convention("1.234.567 Einwohner, 3,5 %"));
        assertEquals(Figures.Convention.COMMA_GROUPS, Figures.convention("1,234,567 people, 3.5%"));
        assertEquals(Figures.Convention.UNKNOWN, Figures.convention("1.234 patients"));
    }

    @Test
    void theCiteChecksOwnPassReadsTheSourcesGroupingsAndNoRounding() {
        assertTrue(CiteCheck.numbersInSource("UK Biobank enrolled 386,258 participants.", JAMA), "the same number written the source's way");
        assertFalse(CiteCheck.numbersInSource("UK Biobank enrolled 386,000 participants.", JAMA), "a rounded figure is the judge's to read, never a pass");
        assertEquals("supported", CiteCheck.mechanical("A total of 386,258 individuals were assessed.", JAMA));
    }

    @Test
    void theChatsCheckReadsTheSameWay() {
        assertEquals(List.of(), WriteupChecks.numbersUnbacked("UK Biobank followed about 386,000 adults.", JAMA));
        assertEquals(1, WriteupChecks.numbersUnbacked("UK Biobank followed about 12,000 adults.", JAMA).size());
    }
}
