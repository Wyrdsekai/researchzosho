package org.researchzosho.librarian;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * How long a faithful translation is, for the program's length check: the band of the ratio of characters (translation to source) a
 * paragraph of a pair falls in, and the flag outside it.
 *
 * <p>A ratio of characters means something only for the way the two languages are written: English is about three times as long as the
 * Japanese it translates and a quarter of the Chinese, and a band fixed for every pair flagged nearly every faithful paragraph of a
 * Japanese paper translated into English (72 of 137 units, the English at 3.31 times the Japanese at the median, 2026-11-10).
 * So the band is a pair's own: the middle 80% of the ratios of its units on the library's record once {@link TranslationScores#MIN_ACCEPTED}
 * are there, as the scores' thresholds are; until then a prior by the script each side is written in ({@link Script}), read off the
 * document's own letters, not its language code. The priors are the middle 80% (the tenth to the ninetieth percentile) of the paragraph
 * ratios of the Universal Declaration of Human Rights in ten languages (the UDHR in Unicode texts: English, German, French, Spanish,
 * Portuguese, Italian, Russian, Japanese, Chinese, Korean; 4,760 aligned paragraphs of 40 characters or more, pooled by the scripts of the
 * two sides), and for Japanese with English also the 97 paragraphs of the paper above (2.66 to 3.81), whose ratio is higher than the
 * Declaration's (2.08 to 3.33): the prior takes the wider of the two. They agree with what translators publish: 2 to 2.5 Japanese
 * characters, and 1.3 to 1.7 Chinese ones, to an English word of about 6 characters with its space (4.79 letters a word in Google Books;
 * 6.1 characters a word in the Declaration's English). docs/DESIGN_TRANSLATION.md says where each number came from.
 *
 * <p>A paragraph is flagged when its ratio is below the band's lower end divided by {@link #MARGIN} or above its upper end times it: at
 * 1.5, none of the 4,760 aligned paragraphs of the Declaration falls outside (the seven that do are paragraphs the texts divide
 * differently), while a paragraph with half of it left out, or as much again added, does. A unit under {@link #SHORT} characters (a
 * heading, a name, a number, a cell) has a ratio that says little (IBM's guide gives 200 to 300% for strings of ten characters), so it is
 * never flagged as short and is flagged as long only when its translation is longer than the band allows a unit of {@link #SHORT}
 * characters: a heading turned into a paragraph.
 */
final class TranslationLengths {
    private TranslationLengths() { }

    /** How a text is written, as its letters show: Han alone (Chinese), Han with kana (Japanese), Hangul (Korean), an alphabet (Latin, Cyrillic, Greek, Armenian, Georgian). */
    enum Script { HAN, KANA, HANGUL, ALPHABET }

    /** The unit under this many characters of its source is judged by the short rule. */
    static final int SHORT = Translation.SCORED_FROM;
    /** How far past the band a paragraph's ratio goes before it is flagged. */
    static final double MARGIN = 1.5;

    /** A band: the middle 80% of the ratio, and how it was set (the library's record of the pair, a prior by script, or none). */
    record Band(String from, String to, Double lo, Double hi, String basis, int recorded) {
        static final String RECORD = "record", PRIOR = "prior", NONE = "none";
        boolean known() { return lo != null && hi != null; }
        double flagBelow() { return lo / MARGIN; }
        double flagAbove() { return hi * MARGIN; }
        /** The most characters a unit shorter than {@link #SHORT} may have in its translation. */
        int shortMost() { return (int) Math.ceil(hi * MARGIN * SHORT); }
    }

    /**
     * The priors by the scripts of the two sides: the tenth and the ninetieth percentile of the Declaration's paragraph ratios, pooled by
     * script (n is the paragraphs), Japanese with an alphabet widened by the paper above.
     */
    static final Map<String, double[]> PRIORS = Map.ofEntries(
            Map.entry("ALPHABET>ALPHABET", new double[]{0.84, 1.18}),   // n 2,271 (7 languages, 42 pairs), median 1.00
            Map.entry("ALPHABET>HAN", new double[]{0.20, 0.29}),        // n 357, median 0.24
            Map.entry("ALPHABET>HANGUL", new double[]{0.33, 0.53}),     // n 386, median 0.41
            Map.entry("ALPHABET>KANA", new double[]{0.26, 0.48}),       // n 357, median 0.37 (0.30 to 0.48); the paper inverted, 0.26 to 0.38
            Map.entry("HAN>ALPHABET", new double[]{3.59, 4.89}),        // n 175, median 4.09
            Map.entry("HANGUL>ALPHABET", new double[]{1.92, 3.01}),     // n 304, median 2.48
            Map.entry("KANA>ALPHABET", new double[]{2.08, 3.81}),       // n 273, median 2.75 (2.08 to 3.33); the paper, 2.66 to 3.81
            Map.entry("KANA>HAN", new double[]{0.51, 0.83}),            // n 45, one pair of texts
            Map.entry("HAN>KANA", new double[]{1.15, 1.77}),            // n 29
            Map.entry("KANA>HANGUL", new double[]{0.97, 1.26}),         // n 39
            Map.entry("HANGUL>KANA", new double[]{0.76, 1.03}),         // n 40
            Map.entry("HAN>HANGUL", new double[]{1.45, 1.96}),          // n 25
            Map.entry("HANGUL>HAN", new double[]{0.49, 0.68}));         // n 40

    /** The script a text is written in, from its letters; null when it has none, or is written in a script the priors do not cover (Arabic, Hebrew, Thai, Devanagari…). */
    static Script scriptOf(String text) {
        if (text == null) return null;
        int letters = 0, kana = 0, han = 0, hangul = 0, alphabet = 0;
        for (int i = 0; i < text.length(); ) {
            int c = text.codePointAt(i); i += Character.charCount(c);
            if (!Character.isLetter(c)) continue;
            letters++;
            switch (Character.UnicodeScript.of(c)) {
                case HIRAGANA, KATAKANA -> kana++;
                case HAN -> han++;
                case HANGUL -> hangul++;
                case LATIN, CYRILLIC, GREEK, ARMENIAN, GEORGIAN -> alphabet++;
                default -> { }
            }
        }
        if (letters == 0) return null;
        if ((kana + han) * 2 > letters) return kana * 50 > kana + han ? Script.KANA : Script.HAN;   // Japanese has kana in every paragraph; Chinese none
        if (hangul * 2 > letters) return Script.HANGUL;
        if (alphabet * 2 > letters) return Script.ALPHABET;
        return null;
    }

    /** The script a language the translation offers by name is written in; null for any other code. */
    static Script scriptOfCode(String code) {
        return switch (code == null ? "" : code) { case "ja" -> Script.KANA; case "zh" -> Script.HAN; case "ko" -> Script.HANGUL; case "en", "de", "fr", "es", "pt", "it", "ru" -> Script.ALPHABET; default -> null; };
    }

    /** The prior band for the scripts of the two sides; null when there is none for them. */
    static double[] prior(Script from, Script to) {
        if (from == null || to == null) return null;
        return PRIORS.get(from + ">" + to);
    }

    /**
     * The band for a document's translation: the library's record of the pair once it holds {@link TranslationScores#MIN_ACCEPTED} ratios,
     * else the prior by the scripts of the source's text and the translation's; none when there is neither.
     */
    static Band band(LibraryStore store, String from, String to, String sourceText, String translationText) {
        List<Double> rec = store == null ? List.of() : TranslationScores.lengths(store, from, to);
        if (rec.size() >= TranslationScores.MIN_ACCEPTED) return new Band(from, to, TranslationScores.percentile(rec, 10), TranslationScores.percentile(rec, 90), Band.RECORD, rec.size());
        double[] p = prior(sourceText == null ? scriptOfCode(from) : scriptOf(sourceText), translationText == null ? scriptOfCode(to) : scriptOf(translationText));
        return p == null ? new Band(from, to, null, null, Band.NONE, rec.size()) : new Band(from, to, p[0], p[1], Band.PRIOR, rec.size());
    }

    /** The prior band for one unit judged alone, its scripts read off its own text and the target language's: for a check made outside a document. */
    static Band unitBand(String source, String translation, String to) {
        Script t = scriptOfCode(to);
        double[] p = prior(scriptOf(source), t == null ? scriptOf(translation) : t);
        return p == null ? new Band("", to, null, null, Band.NONE, 0) : new Band("", to, p[0], p[1], Band.PRIOR, 0);
    }

    /** A ratio as a person reads it in a sentence of their language: two places under 1 (0.37), one place from 1 (3.8; 3,8 in German). */
    static String ratio(double r, Chrome c) { return c.decimal(r, r < 1 ? 2 : 1); }

    /** A pair's languages as a sentence names them, the source's and the target's. */
    private static String[] names(Band b, Chrome c) { return new String[]{b.from().isEmpty() ? c.t("the source's language") : c.languageName(b.from()), c.languageName(b.to())}; }

    /** The length flag's reason for one unit, or null when its length is within what the band allows. */
    static String flagged(Band b, int sourceChars, int translationChars, Chrome c) {
        if (b == null || !b.known() || sourceChars <= 0) return null;
        String[] n = names(b, c);
        if (sourceChars < SHORT) {
            if (translationChars <= b.shortMost()) return null;
            return c.t("the translation of this short unit has {0} characters for {1}; for {2} into {3} even a source of {4} characters is usually translated in at most {5}: something may be added",
                    translationChars, sourceChars, n[0], n[1], SHORT, b.shortMost());
        }
        double r = translationChars / (double) sourceChars;
        if (r >= b.flagBelow() && r <= b.flagAbove()) return null;
        return c.t(r < b.flagBelow() ? "the translation is {0} times as long as the source (characters: {1} for {2}); for {3} into {4} a translation is usually {5} to {6} times as long: something may be left out"
                        : "the translation is {0} times as long as the source (characters: {1} for {2}); for {3} into {4} a translation is usually {5} to {6} times as long: something may be added",
                ratio(r, c), translationChars, sourceChars, n[0], n[1], ratio(b.lo(), c), ratio(b.hi(), c));
    }

    /** What the band is and where it came from, for the note's line on the program's checks. */
    static String said(Band b, Chrome c) {
        String[] n = names(b, c);
        if (!b.known()) return c.t("for {0} into {1} the length is not checked: this program has no measured band for the scripts the two are written in, and the library's record holds {2} of the {3} units of the pair it needs", n[0], n[1], b.recorded(), TranslationScores.MIN_ACCEPTED);
        String flags = c.t("a paragraph is flagged below {0} or above {1} times, a unit under {2} characters only when its translation is longer than {3} characters", ratio(b.flagBelow(), c), ratio(b.flagAbove(), c), SHORT, b.shortMost());
        return b.basis().equals(Band.RECORD)
                ? c.t("for {0} into {1} a translation is usually {2} to {3} times as long, the middle 80% of the {4} units of the pair on this library's record; {5}", n[0], n[1], ratio(b.lo(), c), ratio(b.hi(), c), b.recorded(), flags)
                : c.t("for {0} into {1} a translation is usually {2} to {3} times as long, measured on parallel texts for the scripts the two are written in, until the library's record holds {4} units of the pair ({5} so far); {6}", n[0], n[1], ratio(b.lo(), c), ratio(b.hi(), c), TranslationScores.MIN_ACCEPTED, b.recorded(), flags);
    }

    /** The band in a few words, after a withdrawal: "the length band 2.7 to 3.8 times, from 97 units on the record". */
    static String shortSaid(Band b, Chrome c) {
        if (!b.known()) return c.t("no length band yet");
        return b.basis().equals(Band.RECORD) ? c.t("the length band {0} to {1} times, from {2} units on the record", ratio(b.lo(), c), ratio(b.hi(), c), b.recorded())
                : c.t("the length band {0} to {1} times, from parallel texts until {2} units are on the record", ratio(b.lo(), c), ratio(b.hi(), c), TranslationScores.MIN_ACCEPTED);
    }
}
