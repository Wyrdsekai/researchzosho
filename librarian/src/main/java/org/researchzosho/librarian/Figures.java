package org.researchzosho.librarian;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The numbers a text states, read the way the text writes them, for the checks that hold a report's figures against the notes and the
 * sources it was written from ({@link WriteupChecks}, {@link CiteCheck}).
 *
 * <p>Digits are grouped in several ways: by a comma (386,258), a full stop (386.258), a space, a no-break space, a narrow no-break space
 * or a thin space (386 258, as JAMA and the SI write it), an apostrophe (386'258, Swiss), in lakhs (3,86,258, Indian), or with a unit of
 * ten thousand (38万6258, 38.6万人; 億 and 兆 above it, in Chinese, Japanese and Korean). A comma and a full stop each mean a decimal point in
 * some languages and a thousands sign in others. Which reading holds is decided by what the text itself shows: a number grouped in a way
 * that cannot be anything else (1.234.567, 1.234,5, 3,5 beside no comma-grouped thousands) says the full stop groups; 1,234,567 or
 * 1,234.5 says the comma does. Where the text shows neither, a lone 1.234 or a plain space between digit groups is read both ways and
 * each reading is UNCERTAIN: a figure that only an uncertain reading holds is not settled ({@link Backing#UNSETTLED}), never called
 * unbacked.
 *
 * <p>Found on 2026-11-24: a report sentence giving "~386,000" UK Biobank participants was flagged as stating a number no note or source
 * held (the note said 386,258, the source 386 258 with narrow no-break spaces), and the writer, asked to revise it, replaced it with a
 * wrong one. The two faults: digit strings were compared, so a rounded figure never met the exact one, and a source's spaced groups
 * were read as two numbers.
 *
 * <p>Which roundings count ({@link #backs}): a figure written round, against a number of the evidence that rounds to it at the place of
 * the figure's last significant digit. Written round means: after a word of approximation (~, about, around, roughly, some, circa, and the
 * like in the library's languages), rounded half up; after a bound (over, more than, at least: the evidence at or above it and cut down
 * to it; under, fewer than, up to, nearly, almost: at or below it and rounded up to it); with a scale (thousand, million, billion, 万,
 * 億); or a whole number of a thousand or more ending in two or more zeros, which a check cannot tell from an exact count. Never the
 * other way round: a figure written to the unit is backed only by the same number, so an evidence number rounder than the figure backs
 * nothing. A whole number with ONE significant digit and no word of approximation (400,000 against 386,258) is not settled.
 */
final class Figures {
    private Figures() { }

    /** What the evidence makes of a figure. */
    enum Backing { BACKED, UNSETTLED, UNBACKED }

    /** The numbers a text states: those read one way only, and those a reading the text does not settle gives. */
    record Read(Set<BigDecimal> certain, Set<BigDecimal> uncertain) {
        static Read empty() { return new Read(new HashSet<>(), new HashSet<>()); }
        void addAll(Read o) { certain.addAll(o.certain); uncertain.addAll(o.uncertain); }
    }

    /** How a text uses the comma and the full stop between digits. */
    enum Convention { COMMA_GROUPS, DOT_GROUPS, UNKNOWN }

    // the spaces that group digits and do nothing else: no-break, narrow no-break, thin, figure
    private static final String TSP = "\\u00a0\\u202f\\u2009\\u2007";
    private static final String NOT_BEFORE = "(?<![\\d.,'’" + TSP + "])";
    private static final Pattern TYPO_SPACED = Pattern.compile(NOT_BEFORE + "\\d{1,3}(?:[" + TSP + "]\\d{3})+(?:[.,]\\d+)?(?!\\d)");
    private static final Pattern APOSTROPHE = Pattern.compile(NOT_BEFORE + "\\d{1,3}(?:['’]\\d{3})+(?:\\.\\d+)?(?![\\d'’])");
    private static final Pattern LAKHS = Pattern.compile(NOT_BEFORE + "\\d{1,2}(?:,\\d{2})+,\\d{3}(?:\\.\\d+)?(?!\\d|,\\d)");
    private static final Pattern COMMA_GROUPED = Pattern.compile(NOT_BEFORE + "\\d{1,3}(?:,\\d{3}){2,}(?:\\.\\d+)?(?!\\d|,\\d)|" + NOT_BEFORE + "\\d{1,3}(?:,\\d{3})+\\.\\d+(?!\\d)");
    private static final Pattern DOT_GROUPED = Pattern.compile(NOT_BEFORE + "\\d{1,3}(?:\\.\\d{3}){2,}(?:,\\d+)?(?!\\d|\\.\\d)|" + NOT_BEFORE + "\\d{1,3}(?:\\.\\d{3})+,\\d+(?!\\d)");
    private static final Pattern ONE_COMMA = Pattern.compile(NOT_BEFORE + "\\d{1,3},\\d{3}(?!\\d|[.,]\\d)");
    private static final Pattern ONE_DOT = Pattern.compile(NOT_BEFORE + "\\d{1,3}\\.\\d{3}(?!\\d|[.,]\\d)");
    private static final Pattern PLAIN_SPACED = Pattern.compile(NOT_BEFORE + "\\d{1,3}(?: \\d{3})+(?!\\d|[.,]\\d)");
    private static final Pattern DECIMAL_COMMA = Pattern.compile(NOT_BEFORE + "\\d+,\\d{1,2}(?![\\d,])");
    private static final Pattern DECIMAL_DOT = Pattern.compile(NOT_BEFORE + "\\d+\\.\\d{1,2}(?![\\d.])");
    private static final Pattern PLAIN = Pattern.compile("(?<![\\d])\\d+(?:\\.\\d+)?(?!\\d)");
    /** A number with the units of ten thousand and above, or with 千 inside such a number: 38万6258, 3万8千, 1億2000万, 38.6万, 3억 5천만. */
    private static final String EA_UNITS = "[兆조億亿억万萬만千천]";
    private static final Pattern EAST_ASIAN = Pattern.compile("(?<![\\d.,])\\d+(?:\\.\\d+)?\\s?" + EA_UNITS + "(?:\\s?(?:\\d+(?:\\.\\d+)?\\s?)?" + EA_UNITS + ")*\\d*(?![\\d])");
    private static final Map<Character, Long> EAST_ASIAN_UNITS = Map.of('兆', 1_000_000_000_000L, '조', 1_000_000_000_000L, '億', 100_000_000L, '亿', 100_000_000L,
            '억', 100_000_000L, '万', 10_000L, '萬', 10_000L, '만', 10_000L, '千', 1_000L, '천', 1_000L);

    /** What a text shows of its own use of the comma and the full stop: the forms that can be read only one way, counted. */
    static Convention convention(String text) {
        if (text == null || text.isEmpty()) return Convention.UNKNOWN;
        int comma = count(COMMA_GROUPED, text), dot = count(DOT_GROUPED, text);
        if (dot != comma) return dot > comma ? Convention.DOT_GROUPS : Convention.COMMA_GROUPS;
        // no grouped number settles it: the decimals do, 3,5 against 3.5
        int decComma = count(DECIMAL_COMMA, text), decDot = count(DECIMAL_DOT, text);
        if (decComma != decDot) return decComma > decDot ? Convention.DOT_GROUPS : Convention.COMMA_GROUPS;
        return Convention.UNKNOWN;
    }

    private static int count(Pattern p, String text) { int n = 0; Matcher m = p.matcher(text); while (m.find()) n++; return n; }

    /** Whether the text groups digits with spaces: a no-break kind of space between digit groups, or three plain-spaced groups in a row. */
    static boolean spaceGroups(String text) {
        return TYPO_SPACED.matcher(text).find() || Pattern.compile(NOT_BEFORE + "\\d{1,3}(?: \\d{3}){2,}(?!\\d)").matcher(text).find();
    }

    /** Every number of {@code text}, in every way the text may write it, read as the text itself decides. */
    static Read read(String text) {
        Read out = Read.empty();
        if (text == null || text.isEmpty()) return out;
        Convention c = convention(text);
        boolean spaced = spaceGroups(text);
        boolean[] taken = new boolean[text.length()];
        // the forms that are one number whatever the text's convention
        for (Pattern p : List.of(EAST_ASIAN, TYPO_SPACED, APOSTROPHE, LAKHS, COMMA_GROUPED, DOT_GROUPED)) {
            Matcher m = p.matcher(text);
            while (m.find()) {
                if (anyTaken(taken, m.start(), m.end())) continue;
                BigDecimal v = p == EAST_ASIAN ? eastAsian(m.group()) : p == DOT_GROUPED ? dotted(m.group()) : p == LAKHS ? lakhs(m.group()) : grouped(m.group());
                if (v != null) out.certain.add(norm(v));
                take(taken, m.start(), m.end());
            }
        }
        // the forms whose reading the text's convention decides
        Matcher m = ONE_COMMA.matcher(text);
        while (m.find()) {
            if (anyTaken(taken, m.start(), m.end())) continue;
            BigDecimal thousands = new BigDecimal(m.group().replace(",", "")), decimal = new BigDecimal(m.group().replace(',', '.'));
            if (c == Convention.COMMA_GROUPS) out.certain.add(norm(thousands));
            else if (c == Convention.DOT_GROUPS) out.certain.add(norm(decimal));
            else { out.uncertain.add(norm(thousands)); out.uncertain.add(norm(decimal)); }
            take(taken, m.start(), m.end());
        }
        m = ONE_DOT.matcher(text);
        while (m.find()) {
            if (anyTaken(taken, m.start(), m.end())) continue;
            BigDecimal thousands = new BigDecimal(m.group().replace(".", "")), decimal = new BigDecimal(m.group());
            if (c == Convention.DOT_GROUPS) out.certain.add(norm(thousands));
            else if (c == Convention.COMMA_GROUPS) out.certain.add(norm(decimal));
            else { out.uncertain.add(norm(thousands)); out.uncertain.add(norm(decimal)); }
            take(taken, m.start(), m.end());
        }
        m = PLAIN_SPACED.matcher(text);
        while (m.find()) {
            if (anyTaken(taken, m.start(), m.end())) continue;
            BigDecimal whole = new BigDecimal(m.group().replace(" ", ""));
            // the groups apart are read below as plain numbers; the whole is one number when the text groups with spaces
            if (spaced) { out.certain.add(norm(whole)); take(taken, m.start(), m.end()); }
            else out.uncertain.add(norm(whole));
        }
        m = DECIMAL_COMMA.matcher(text);
        while (m.find()) {
            if (anyTaken(taken, m.start(), m.end())) continue;
            BigDecimal decimal = new BigDecimal(m.group().replace(',', '.'));
            if (c == Convention.DOT_GROUPS) { out.certain.add(norm(decimal)); take(taken, m.start(), m.end()); }
            else if (c == Convention.UNKNOWN) out.uncertain.add(norm(decimal));
        }
        m = PLAIN.matcher(text);
        while (m.find()) {
            if (anyTaken(taken, m.start(), m.end())) continue;
            try { out.certain.add(norm(new BigDecimal(m.group()))); } catch (NumberFormatException ignored) { }
        }
        // a scale word after a number: "1.2 million" is 1,200,000 as well
        Matcher s = SCALED.matcher(text);
        while (s.find()) {
            BigDecimal n = number(s.group(1), c);
            BigDecimal k = scale(s.group(2));
            if (n != null && k != null) out.certain.add(norm(n.multiply(k)));
        }
        out.uncertain.removeAll(out.certain);
        return out;
    }

    private static final Pattern SCALED = Pattern.compile("(?i)(?<![\\d.,])(\\d+(?:[.,]\\d+)?)\\s?(thousand|million|billion|trillion|Tausend|Million(?:en)?|Milliarde(?:n)?|mille|millions?|milliards?|mil|millones|millón|mila|milioni|milione|miliardi|miliardo|milhões|milhão|bilhões|bilhão|тыс\\.?|тысяч[аи]?|млн\\.?|миллион(?:а|ов)?|млрд\\.?|миллиард(?:а|ов)?)(?![\\p{L}])");

    /** What a scale word multiplies by; null for none. */
    static BigDecimal scale(String word) {
        if (word == null) return null;
        String w = word.toLowerCase(Locale.ROOT).replace(".", "");
        if (w.equals("k") || w.startsWith("thousand") || w.startsWith("tausend") || w.equals("mille") || w.equals("mil") || w.equals("mila") || w.startsWith("тыс")) return BigDecimal.valueOf(1_000L);
        if (w.startsWith("billion") || w.startsWith("milliard") || w.startsWith("miliard") || w.startsWith("bilh") || w.startsWith("млрд") || w.startsWith("миллиард")) return BigDecimal.valueOf(1_000_000_000L);
        if (w.startsWith("trillion")) return BigDecimal.valueOf(1_000_000_000_000L);
        if (w.startsWith("million") || w.startsWith("millon") || w.startsWith("millón") || w.startsWith("milion") || w.startsWith("milh") || w.startsWith("млн") || w.startsWith("миллион")) return BigDecimal.valueOf(1_000_000L);
        return null;
    }

    /** One written number, the comma and the full stop read as {@code c} says (a comma is a decimal point only where full stops group). */
    static BigDecimal number(String s, Convention c) {
        try {
            if (s.indexOf(',') >= 0 && s.indexOf('.') < 0 && c == Convention.DOT_GROUPS) return new BigDecimal(s.replace(',', '.'));
            if (s.matches("\\d+,\\d{1,2}")) return new BigDecimal(s.replace(',', '.'));
            return new BigDecimal(s.replace(",", ""));
        } catch (NumberFormatException e) { return null; }
    }

    private static boolean anyTaken(boolean[] taken, int from, int to) { for (int i = from; i < to; i++) if (taken[i]) return true; return false; }
    private static void take(boolean[] taken, int from, int to) { for (int i = from; i < to; i++) taken[i] = true; }

    /** A number grouped by commas, spaces or apostrophes, its decimal part after a full stop or a comma. */
    private static BigDecimal grouped(String s) {
        String t = s.replaceAll("[,'’" + TSP + " ](?=\\d{3}(?!\\d))", "");   // the group signs: before each group of three
        t = t.replace(',', '.');   // what is left is the decimal sign
        try { return new BigDecimal(t); } catch (NumberFormatException e) { return null; }
    }

    /** A number grouped in lakhs and crores (3,86,258; 12,34,56,789): every comma groups. */
    private static BigDecimal lakhs(String s) {
        try { return new BigDecimal(s.replace(",", "")); } catch (NumberFormatException e) { return null; }
    }

    /** A number grouped by full stops, its decimal part after a comma. */
    private static BigDecimal dotted(String s) {
        try { return new BigDecimal(s.replace(".", "").replace(',', '.')); } catch (NumberFormatException e) { return null; }
    }

    /**
     * 38万6258, 3万8千, 1億2000万, 5千万, 38.6万, 3억 5천만: the digits before 千 make a part below the next unit of ten thousand or above,
     * each such unit takes what came before it since the last one, and the digits after the last unit are added as they are.
     */
    static BigDecimal eastAsian(String s) {
        Matcher m = Pattern.compile("(\\d+(?:\\.\\d+)?)|(" + EA_UNITS + ")").matcher(s);
        BigDecimal total = BigDecimal.ZERO, section = BigDecimal.ZERO, n = null;
        boolean unit = false;
        while (m.find()) {
            if (m.group(1) != null) { n = new BigDecimal(m.group(1)); continue; }
            unit = true;
            long u = EAST_ASIAN_UNITS.get(m.group(2).charAt(0));
            if (u == 1_000L) { section = section.add((n == null ? BigDecimal.ONE : n).multiply(BigDecimal.valueOf(u))); n = null; continue; }
            BigDecimal here = section.add(n == null ? BigDecimal.ZERO : n);
            total = total.add((here.signum() == 0 ? BigDecimal.ONE : here).multiply(BigDecimal.valueOf(u)));
            section = BigDecimal.ZERO; n = null;
        }
        if (!unit) return null;
        return total.add(section).add(n == null ? BigDecimal.ZERO : n);
    }

    static BigDecimal norm(BigDecimal v) { BigDecimal s = v.stripTrailingZeros(); return s.scale() < 0 ? s.setScale(0) : s; }

    // ---- a figure of the report, and whether the evidence backs it ----

    /** How a figure was written: as approximate, as a lower or an upper bound, or as it is. */
    enum Hedge { NONE, ABOUT, OVER, UNDER }

    /** Words of approximation just before a figure, in the library's languages; "~386,000", "about 386,000", "約38万". */
    private static final Pattern ABOUT_BEFORE = Pattern.compile("(?i)(?:~|≈|∼|\\b(?:about|around|approximately|approx\\.?|roughly|some|circa|ca\\.|c\\.|an estimated|estimated|close to|etwa|rund|ungefähr|environ|près de|aproximadamente|alrededor de|unos|unas|cerca de|intorno a|около|примерно|порядка)|約|约|大约|大約|およそ|ほぼ|약|대략)\\s*$");
    private static final Pattern OVER_BEFORE = Pattern.compile("(?i)(?:>|≥|\\b(?:over|more than|above|at least|upwards of|in excess of|über|mehr als|plus de|más de|oltre|più di|mais de|более|свыше|больше)|超过|超過|逾|超)\\s*$");
    private static final Pattern UNDER_BEFORE = Pattern.compile("(?i)(?:<|≤|\\b(?:under|less than|fewer than|below|up to|nearly|almost|just under|knapp|fast|unter|weniger als|moins de|presque|menos de|casi|meno di|quasi|quase|менее|меньше|почти)|近|将近|將近|거의)\\s*$");
    /** Bounds written after the figure: 386,000+, 38万人以上, 1,000명 이상, 38万人余り. */
    private static final Pattern OVER_AFTER = Pattern.compile("^(?:\\+|\\s?(?:[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}\\p{IsHangul}]{0,2})(?:以上|超|余り|余|多|여|이상|넘))");
    private static final Pattern UNDER_AFTER = Pattern.compile("^\\s?(?:[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}\\p{IsHangul}]{0,2})(?:未満|弱|미만)");
    private static final Pattern ABOUT_AFTER = Pattern.compile("^\\s?(?:[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}\\p{IsHangul}]{0,2})(?:前後|程度|左右|ほど|くらい|ぐらい|가량|정도|쯤)");

    /** How the words around a figure write it: {@code before} the text up to it, {@code after} the text after it (its unit included). */
    static Hedge hedge(String before, String after) {
        String b = before == null ? "" : before.length() > 40 ? before.substring(before.length() - 40) : before;
        String a = after == null ? "" : after.length() > 12 ? after.substring(0, 12) : after;
        if (OVER_BEFORE.matcher(b).find() || OVER_AFTER.matcher(a).find()) return Hedge.OVER;
        if (UNDER_BEFORE.matcher(b).find() || UNDER_AFTER.matcher(a).find()) return Hedge.UNDER;
        if (ABOUT_BEFORE.matcher(b).find() || ABOUT_AFTER.matcher(a).find()) return Hedge.ABOUT;
        return Hedge.NONE;
    }

    /**
     * A figure as the report writes it: its value, the place of its last significant digit (its precision), whether it is written round
     * (a word of approximation or a bound, a scale, or a whole number of a thousand or more ending in two or more zeros), how it is hedged,
     * and how many significant digits it has.
     */
    record Figure(BigDecimal value, BigDecimal step, boolean round, Hedge hedge, int significant) { }

    /** {@code digits} as the report writes them (separators taken out already), {@code scale} the word or character after them or null. */
    static Figure figure(String digits, String scaleWord, Hedge hedge) {
        BigDecimal n;
        try { n = new BigDecimal(digits); } catch (NumberFormatException e) { return null; }
        BigDecimal k = scale(scaleWord);
        boolean scaled = k != null;
        if (k == null) k = BigDecimal.ONE;
        BigDecimal step;
        int significant;
        String unscaled = n.toPlainString();
        if (unscaled.contains(".")) {
            int decimals = unscaled.length() - unscaled.indexOf('.') - 1;
            step = BigDecimal.ONE.movePointLeft(decimals);
            significant = unscaled.replace(".", "").replaceFirst("^0+", "").length();
        } else {
            int zeros = 0;
            for (int i = unscaled.length() - 1; i > 0 && unscaled.charAt(i) == '0'; i--) zeros++;
            step = BigDecimal.ONE.movePointRight(zeros);
            significant = Math.max(1, unscaled.length() - zeros);
        }
        BigDecimal value = n.multiply(k);
        boolean wholeRound = !unscaled.contains(".") && value.compareTo(BigDecimal.valueOf(1000)) >= 0 && step.compareTo(BigDecimal.valueOf(100)) >= 0;
        boolean round = hedge != Hedge.NONE || scaled || wholeRound;
        return new Figure(norm(value), norm(step.multiply(k)), round, hedge, significant);
    }

    /** Whether {@code e}, a number of the evidence, gives the figure: the same number, or, for a figure written round, one that rounds to it. */
    static boolean gives(Figure f, BigDecimal e) {
        if (e.compareTo(f.value()) == 0) return true;
        if (!f.round()) return false;
        BigDecimal step = f.step();
        return switch (f.hedge()) {
            // at or above it and cut down to it: "over 380,000" for 386,258; never "over 390,000"
            case OVER -> e.compareTo(f.value()) >= 0 && e.divide(step, 0, RoundingMode.FLOOR).multiply(step).compareTo(f.value()) == 0;
            // at or below it and rounded up to it: "nearly 390,000" for 386,258
            case UNDER -> e.compareTo(f.value()) <= 0 && e.divide(step, 0, RoundingMode.CEILING).multiply(step).compareTo(f.value()) == 0;
            default -> e.divide(step, 0, RoundingMode.HALF_UP).multiply(step).compareTo(f.value()) == 0;
        };
    }

    /**
     * What the evidence makes of a figure: BACKED when a number the evidence states for certain gives it; UNSETTLED when only a reading the
     * evidence does not settle gives it, or when the figure is a whole number of one significant digit with no word of approximation that
     * a number of the evidence would round to (400,000 for 386,258); UNBACKED otherwise.
     */
    static Backing backs(Figure f, Read evidence) {
        if (f == null) return Backing.UNBACKED;
        boolean coarse = f.round() && f.hedge() == Hedge.NONE && f.significant() < 2 && f.step().compareTo(BigDecimal.ONE) > 0;
        for (BigDecimal e : evidence.certain()) if (gives(f, e)) return coarse && e.compareTo(f.value()) != 0 ? Backing.UNSETTLED : Backing.BACKED;
        for (BigDecimal e : evidence.uncertain()) if (gives(f, e)) return Backing.UNSETTLED;
        return Backing.UNBACKED;
    }

    /**
     * The sentence with the groupings the report's own convention settles written as plain digits, so that a figure is read as one number:
     * 386 258 (any space between groups where the report groups with spaces, a no-break kind always), 386'258, 3,86,258, 1.234.567 and
     * 1.234,5, 38万6258 and 38.6万, and a decimal comma where full stops group. Only the reading changes; the sentence a mark is placed after
     * is the report's own.
     */
    static String plainDigits(String sentence, Convention reportConvention, boolean reportSpaces) {
        String s = sentence;
        s = replace(s, EAST_ASIAN, g -> { BigDecimal v = eastAsian(g); return v == null ? g : norm(v).toPlainString(); });
        s = replace(s, TYPO_SPACED, g -> { BigDecimal v = grouped(g); return v == null ? g : v.toPlainString(); });
        s = replace(s, APOSTROPHE, g -> { BigDecimal v = grouped(g); return v == null ? g : v.toPlainString(); });
        s = replace(s, LAKHS, g -> { BigDecimal v = lakhs(g); return v == null ? g : v.toPlainString(); });
        s = replace(s, DOT_GROUPED, g -> { BigDecimal v = dotted(g); return v == null ? g : v.toPlainString(); });
        if (reportSpaces) s = replace(s, PLAIN_SPACED, g -> g.replace(" ", ""));
        if (reportConvention == Convention.DOT_GROUPS) {
            s = replace(s, ONE_DOT, g -> g.replace(".", ""));
            s = replace(s, DECIMAL_COMMA, g -> g.replace(',', '.'));
        }
        return s;
    }

    private static String replace(String s, Pattern p, UnaryOperator<String> f) {
        Matcher m = p.matcher(s);
        StringBuilder b = new StringBuilder();
        while (m.find()) m.appendReplacement(b, Matcher.quoteReplacement(f.apply(m.group())));
        m.appendTail(b);
        return b.toString();
    }
}
