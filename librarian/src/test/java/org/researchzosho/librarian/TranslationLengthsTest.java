package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The length check knows the pair: a band of the ratio of characters by the scripts the two sides are written in, read off the texts, until
 * the library's record of the pair holds twenty units, then the record's own; a paragraph at the pair's middle passes and one at twice the
 * band's top is flagged, with the band in the sentence; a short unit is judged only for being far too long; a pair with no prior is not
 * judged by its length until its record holds the units.
 */
class TranslationLengthsTest {
    static final String JA = "委員会は新しい制度について長く話し合い、地域の病院を支えるために必要な費用と人の数を確かめた。";
    static final String EN = "The committee discussed the new system at length and checked the money and the people needed to support the hospitals of the region.";
    static final String ZH = "委员会就新制度进行了长时间的讨论，并核实了支持地区医院所需的资金和人员数量。";
    static final String KO = "위원회는 새로운 제도에 대해 오랫동안 논의하고 지역 병원을 지원하는 데 필요한 비용과 인원을 확인했다.";
    static final String DE = "Der Ausschuss hat lange über das neue System gesprochen und geprüft, wie viel Geld und wie viele Menschen die Krankenhäuser der Region brauchen.";
    static final String AR = "ناقشت اللجنة النظام الجديد مطولا وتحققت من المال والعدد اللازم من الناس لدعم مستشفيات المنطقة.";

    /** A text of {@code n} characters in the script of {@code sample}: the sample repeated and cut. */
    static String of(String sample, int n) { StringBuilder b = new StringBuilder(); while (b.length() < n) b.append(sample).append(' '); return b.substring(0, n); }

    /** The pair's band from the scripts of two samples, as a document's check makes it with no record. */
    static TranslationLengths.Band band(String from, String to, String source, String translation) { return TranslationLengths.band(null, from, to, source, translation); }

    /** At the pair's middle a paragraph passes; at twice the band's top it is flagged as longer; at half the band's foot as shorter. */
    static void middlePassesTwiceTheTopIsFlagged(TranslationLengths.Band b, double middle, String from, String to) {
        int s = 200;
        assertNull(TranslationLengths.flagged(b, s, (int) Math.round(s * middle), Chrome.ENGLISH), "a faithful paragraph at the middle of " + from + "→" + to + " (" + middle + ") passes");
        String longer = TranslationLengths.flagged(b, s, (int) Math.round(s * b.hi() * 2), Chrome.ENGLISH);
        assertNotNull(longer, "twice the band's top is flagged for " + from + "→" + to);
        assertTrue(longer.contains("for " + Chrome.ENGLISH.languageName(from) + " into " + Chrome.ENGLISH.languageName(to) + " a translation is usually " + TranslationLengths.ratio(b.lo(), Chrome.ENGLISH) + " to " + TranslationLengths.ratio(b.hi(), Chrome.ENGLISH) + " times as long: something may be added"), longer);
        String shorter = TranslationLengths.flagged(b, s, (int) Math.round(s * b.lo() / 2), Chrome.ENGLISH);
        assertTrue(shorter != null && shorter.endsWith("something may be left out"), "half the band's foot is flagged as shorter: " + shorter);
    }

    @Test void japaneseIntoEnglishTheRunsBandAndItsSentence() {
        TranslationLengths.Band b = band("ja", "en", JA, EN);
        assertEquals(TranslationLengths.Band.PRIOR, b.basis());
        assertEquals(2.08, b.lo()); assertEquals(3.81, b.hi());
        middlePassesTwiceTheTopIsFlagged(b, 3.31, "ja", "en");   // the median of a real paper's 97 paragraphs
        // the paper's ninetieth percentile, and its tenth, pass: the faithful paragraphs the old band of 3.0 flagged
        assertNull(TranslationLengths.flagged(b, 257, 980, Chrome.ENGLISH), "3.81 times passes");
        assertNull(TranslationLengths.flagged(b, 257, 684, Chrome.ENGLISH), "2.66 times passes");
        String said = TranslationLengths.flagged(b, 257, 1568, Chrome.ENGLISH);
        assertEquals("the translation is 6.1 times as long as the source (characters: 1568 for 257); for Japanese into English a translation is usually 2.1 to 3.8 times as long: something may be added", said);
        assertEquals("訳は原文の 6.1 倍の長さです（文字数：原文 257 に対して 1568）。日本語から英語では、訳はふつう 2.1 倍から 3.8 倍の長さです。何かが足されているかもしれません", TranslationLengths.flagged(b, 257, 1568, Chrome.in("ja")));
        assertTrue(TranslationLengths.flagged(b, 257, 1568, Chrome.in("de")).contains("6,1"), "the ratio in the language's own decimal form: " + TranslationLengths.flagged(b, 257, 1568, Chrome.in("de")));
    }

    @Test void englishIntoJapanese() { middlePassesTwiceTheTopIsFlagged(band("en", "ja", EN, JA), 0.37, "en", "ja"); }
    @Test void chineseIntoEnglish() { TranslationLengths.Band b = band("zh", "en", ZH, EN); assertEquals(3.59, b.lo()); middlePassesTwiceTheTopIsFlagged(b, 4.09, "zh", "en"); }
    @Test void koreanIntoEnglish() { TranslationLengths.Band b = band("ko", "en", KO, EN); assertEquals(1.92, b.lo()); middlePassesTwiceTheTopIsFlagged(b, 2.48, "ko", "en"); }
    @Test void englishIntoGerman() { TranslationLengths.Band b = band("en", "de", EN, DE); assertEquals(0.84, b.lo()); middlePassesTwiceTheTopIsFlagged(b, 1.14, "en", "de"); }
    @Test void germanIntoEnglish() { middlePassesTwiceTheTopIsFlagged(band("de", "en", DE, EN), 0.88, "de", "en"); }

    /** The scripts are read off the texts, not the codes: a document of Cyrillic letters the reader called "en" is an alphabet, as English is. */
    @Test void theScriptsAreReadOffTheLetters() {
        assertEquals(TranslationLengths.Script.KANA, TranslationLengths.scriptOf(JA));
        assertEquals(TranslationLengths.Script.HAN, TranslationLengths.scriptOf(ZH));
        assertEquals(TranslationLengths.Script.HANGUL, TranslationLengths.scriptOf(KO));
        assertEquals(TranslationLengths.Script.ALPHABET, TranslationLengths.scriptOf("Комитет долго обсуждал новую систему."));
        assertNull(TranslationLengths.scriptOf(AR)); assertNull(TranslationLengths.scriptOf("1234 5678"));
        assertEquals(0.84, band("en", "en", "Комитет долго обсуждал новую систему.", EN).lo());
    }

    /** A pair the priors do not cover, Arabic into English: no length flag at any ratio, and the note says why, until the record holds the pair. */
    @Test void aPairWithNoPriorIsNotJudgedByItsLength(@TempDir Path tmp) {
        TranslationLengths.Band b = band("ar", "en", AR, EN);
        assertFalse(b.known()); assertEquals(TranslationLengths.Band.NONE, b.basis());
        assertNull(TranslationLengths.flagged(b, 100, 1000, Chrome.ENGLISH)); assertNull(TranslationLengths.flagged(b, 100, 5, Chrome.ENGLISH));
        assertEquals("for Arabic into English the length is not checked: this program has no measured band for the scripts the two are written in, and the library's record holds 0 of the 20 units of the pair it needs", TranslationLengths.said(b, Chrome.ENGLISH));
        // the record holds twenty of the pair: the band is the record's
        LibraryStore store = store(tmp);
        TranslationScores.accept(store, "ar", "en", rows("J-0001", 20, 1.2, 1.6));
        TranslationLengths.Band r = TranslationLengths.band(store, "ar", "en", AR, EN);
        assertEquals(TranslationLengths.Band.RECORD, r.basis()); assertEquals(20, r.recorded());
        assertNotNull(TranslationLengths.flagged(r, 100, 400, Chrome.ENGLISH));
    }

    /** A heading, a name, a number: never flagged as short, flagged as long only past what a unit of forty characters may reach. */
    @Test void aShortUnitIsJudgedOnlyForBeingFarTooLong() {
        TranslationLengths.Band b = band("ja", "en", JA, EN);
        assertEquals(229, b.shortMost(), "3.81 × 1.5 × 40");
        assertNull(TranslationLengths.flagged(b, 4, 2, Chrome.ENGLISH), "「第一章」 as \"I\": short is never flagged");
        assertNull(TranslationLengths.flagged(b, 4, 40, Chrome.ENGLISH), "ten times as long, but a heading: 「はじめに」 is \"Introduction\"");
        assertEquals("the translation of this short unit has 300 characters for 6; for Japanese into English even a source of 40 characters is usually translated in at most 229: something may be added", TranslationLengths.flagged(b, 6, 300, Chrome.ENGLISH));
    }

    /** Once the pair holds twenty units on the library's record, the band is the middle 80% of their ratios, not the prior; the note says which. */
    @Test void theRecordsBandReplacesThePrior(@TempDir Path tmp) {
        LibraryStore store = store(tmp);
        TranslationScores.accept(store, "ja", "en", rows("J-0070", 19, 2.6, 3.9));
        assertEquals(TranslationLengths.Band.PRIOR, TranslationLengths.band(store, "ja", "en", JA, EN).basis(), "nineteen are not enough");
        String prior = TranslationLengths.said(TranslationLengths.band(store, "ja", "en", JA, EN), Chrome.ENGLISH);
        assertTrue(prior.startsWith("for Japanese into English a translation is usually 2.1 to 3.8 times as long, measured on parallel texts for the scripts the two are written in, until the library's record holds 20 units of the pair (19 so far); a paragraph is flagged below 1.4 or above 5.7 times"), prior);
        TranslationScores.accept(store, "ja", "en", rows("J-0071", 20, 2.7, 3.8));
        TranslationLengths.Band b = TranslationLengths.band(store, "ja", "en", JA, EN);
        assertEquals(TranslationLengths.Band.RECORD, b.basis()); assertEquals(39, b.recorded());
        assertTrue(b.lo() > 2.6 && b.hi() < 3.9, b.toString());
        assertTrue(TranslationLengths.said(b, Chrome.ENGLISH).contains("the middle 80% of the 39 units of the pair on this library's record"), TranslationLengths.said(b, Chrome.ENGLISH));
    }

    /** The program's check on one unit outside a document takes the prior for the unit's own letters and the target language. */
    @Test void theUnitChecksOwnBand() {
        assertTrue(Translation.mechanical(0, JA, of(EN, (int) (JA.length() * 3.3)), Map.of(), Set.of(), "en", "").isEmpty(), "Japanese into English at 3.3 times: nothing");
        assertTrue(Translation.mechanical(0, JA, of(EN, JA.length() * 8), Map.of(), Set.of(), "en", "").stream().anyMatch(f -> f.kind().equals("length") && f.why().contains("for the source's language into English a translation is usually 2.1 to 3.8 times as long")));
    }

    static LibraryStore store(Path tmp) { try { LibraryStore s = new LibraryStore(tmp.resolve("lib")); s.init(); return s; } catch (Exception e) { throw new IllegalStateException(e); } }

    /** {@code n} rows of a job, their length ratios spread evenly from {@code lo} to {@code hi}. */
    static List<TranslationScores.Row> rows(String job, int n, double lo, double hi) {
        List<TranslationScores.Row> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(new TranslationScores.Row(job, "", i + 1, 0.8, null, lo + (hi - lo) * i / Math.max(1, n - 1)));
        return out;
    }
}
