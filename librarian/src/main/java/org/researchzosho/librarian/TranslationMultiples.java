package org.researchzosho.librarian;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A number the source writes with a multiple in characters and the translation writes out: 8千人 and "8,000 people", 24万 and "240,000",
 * 3万8千円 and "38,000 yen", 7割 and "70%". The number check compares the digits of both sides ({@link Translation#mechanical}), and the
 * digits differ where the number is the same; such a number is counted present. Only where the source's writing makes the multiple (a
 * run of digits followed by 十, 百, 千, 万, 億 or 兆, alone or in a row as 3万8千; or by 割, tenths, which a translation writes as a
 * percentage), and only when the translation holds the multiplied number itself: in digits, grouped as its language groups them, or as
 * digits before the language's word for a thousand, a million or a billion ("30 million" for 3千万). A number left out or changed is
 * still missing.
 */
final class TranslationMultiples {

    private TranslationMultiples() { }

    /** One part of a number written with multiples: the digits, a decimal part or none, and the multiple's characters. */
    private static final Pattern PART = Pattern.compile("(\\d+)(?:\\.(\\d+))?\\s*(十万|百万|千万|[十百千万億兆割])");

    /** A number before a word for a thousand, a million, a billion: "30 million", "3,8 Millionen", "80 mil", "240 тысяч", "80k". */
    private static final Pattern WORDED = Pattern.compile("(\\d+(?:[.,]\\d+)?)\\s*(?:(k\\b|thousand|tausend|mille?\\b|mila|тыс)|(mil+i?[oó]n|milh[ãa]o|milh[õo]es|миллион)|(billion|milliard|mil millones|bilh[ãa]o|bilh[õo]es|миллиард))", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static double times(String multiple) {
        return switch (multiple) { case "十" -> 1e1; case "百" -> 1e2; case "千" -> 1e3; case "万" -> 1e4; case "十万" -> 1e5; case "百万" -> 1e6; case "千万" -> 1e7; case "億" -> 1e8; case "兆" -> 1e12; default -> 10; };   // 割: tenths, as a percentage
    }

    /** A number as the check's digit runs write it: whole, no separator; "" when it is no whole number. */
    private static String whole(double v) { return v == Math.rint(v) && v < 1e15 ? String.valueOf((long) v) : ""; }

    /**
     * The numbers a translation lacks, without each the source writes with a multiple and the translation writes out: the digit runs of
     * a part (3 and 8 of 3.8万) leave the list when the translation's own digit runs hold the part's number (38000), or the sum of the
     * parts written in a row (3万8千: 38000).
     */
    static void excused(String source, String translation, List<String> missing) {
        if (missing.isEmpty() || source == null || translation == null) return;
        String s = Normalizer.normalize(source, Normalizer.Form.NFKC).replaceAll("(?<=\\d),(?=\\d{3})", "");
        List<String> there = Translation.numbers(translation.replaceAll("(?<=\\d)[.\\u00a0\\u202f ](?=\\d{3}(?!\\d))", ""));   // 240.000 and 240 000, as other languages group
        there.addAll(Translation.numbers(translation));
        Matcher w = WORDED.matcher(translation);
        while (w.find()) { String n = whole(Double.parseDouble(w.group(1).replace(',', '.')) * (w.group(2) != null ? 1e3 : w.group(3) != null ? 1e6 : 1e9)); if (!n.isEmpty()) there.add(n); }
        Matcher m = PART.matcher(s);
        List<String[]> row = new ArrayList<>();   // the parts written in a row: {digits, decimals or null, the part's number}
        double sum = 0; int end = -1;
        while (true) {
            boolean found = m.find();
            if (!found || m.start() != end) {   // the row before this part has ended: excuse what it wrote out
                if (!row.isEmpty()) {
                    boolean together = row.size() > 1 && there.contains(whole(sum));
                    for (String[] part : row) if (together || there.contains(part[2])) { missing.remove(part[0].replaceFirst("^0+(?=\\d)", "")); if (part[1] != null) missing.remove(part[1].replaceFirst("^0+(?=\\d)", "")); }
                }
                row.clear(); sum = 0;
            }
            if (!found) break;
            double v = Double.parseDouble(m.group(1) + (m.group(2) == null ? "" : "." + m.group(2))) * times(m.group(3));
            if (!m.group(3).equals("割")) sum += v;
            row.add(new String[]{m.group(1), m.group(2), whole(v)});
            end = m.end();
        }
    }
}
