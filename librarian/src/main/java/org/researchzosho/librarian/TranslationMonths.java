package org.researchzosho.librarian;

import java.text.Normalizer;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A month the source writes as a number and the translation writes by its name: 10月 and "October", 10월 and "Oktober", the middle of
 * 2025-10-12 and "Oct". The number check counts that month present ({@link Translation#mechanical}). Months only: a day, a year or a count
 * is never excused, a month number counts only where the source's writing makes it one, and a translation's month name counts only in
 * the language translated into, as that language writes it (French "mai" is May; Italian "mai" is never, and is not read).
 */
final class TranslationMonths {

    private TranslationMonths() { }

    /** A month number where the writing makes it one: before 月 or 월, or the middle of a date written year first (2025-10-12, 2025/10/12, 2025.10.12). */
    static final Pattern IN_SOURCE = Pattern.compile("(?<![\\d.,])(\\d{1,2})\\s*[月월]|(?<![\\d.,/-])\\d{4}[-/.](\\d{1,2})[-/.]\\d{1,2}(?!\\d)");

    /** A language's month names and their short forms, January first; {@code cased}: the names are written with a capital and read only so ("May", "Mai"). */
    record Names(List<String> full, List<String> shortForms, boolean cased) { }

    static final Map<String, Names> NAMES = Map.of(
            "en", new Names(List.of("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December"),
                    List.of("Jan", "Feb", "Mar", "Apr", "", "Jun", "Jul", "Aug", "Sept|Sep", "Oct", "Nov", "Dec"), true),
            "de", new Names(List.of("Januar|Jänner", "Februar", "März", "April", "Mai", "Juni", "Juli", "August", "September", "Oktober", "November", "Dezember"),
                    List.of("Jan", "Feb", "Mrz|Mär", "Apr", "", "Jun", "Jul", "Aug", "Sept|Sep", "Okt", "Nov", "Dez"), true),
            "fr", new Names(List.of("janvier", "février", "mars", "avril", "mai", "juin", "juillet", "août", "septembre", "octobre", "novembre", "décembre"),
                    List.of("janv", "févr|fév", "", "avr", "", "", "juil", "", "sept", "oct", "nov", "déc"), false),
            "es", new Names(List.of("enero", "febrero", "marzo", "abril", "mayo", "junio", "julio", "agosto", "septiembre|setiembre", "octubre", "noviembre", "diciembre"),
                    List.of("ene", "feb", "mar", "abr", "may", "jun", "jul", "ago", "sept|sep", "oct", "nov", "dic"), false),
            "it", new Names(List.of("gennaio", "febbraio", "marzo", "aprile", "maggio", "giugno", "luglio", "agosto", "settembre", "ottobre", "novembre", "dicembre"),
                    List.of("genn|gen", "febbr|feb", "mar", "apr", "magg|mag", "giu", "lug", "ago", "sett|set", "ott", "nov", "dic"), false),
            "pt", new Names(List.of("janeiro", "fevereiro", "março", "abril", "maio", "junho", "julho", "agosto", "setembro", "outubro", "novembro", "dezembro"),
                    List.of("jan", "fev", "mar", "abr", "mai", "jun", "jul", "ago", "set", "out", "nov", "dez"), false),
            "ru", new Names(List.of("январ[ьяе]", "феврал[ьяе]", "март[ае]?", "апрел[ьяе]", "ма[йяе]", "июн[ьяе]", "июл[ьяе]", "август[ае]?", "сентябр[ьяе]", "октябр[ьяе]", "ноябр[ьяе]", "декабр[ьяе]"),
                    List.of("янв", "фев", "мар", "апр", "", "", "", "авг", "сент|сен", "окт", "нояб|ноя", "дек"), false));

    /** How many times each month stands as a number in the source, where its writing makes it a month; by the month, 1 to 12. */
    static Map<Integer, Integer> numbered(String source) {
        Map<Integer, Integer> out = new HashMap<>();
        Matcher m = IN_SOURCE.matcher(Normalizer.normalize(source == null ? "" : source, Normalizer.Form.NFKC));
        while (m.find()) {
            int month = Integer.parseInt(m.group(1) != null ? m.group(1) : m.group(2));
            if (month >= 1 && month <= 12) out.merge(month, 1, Integer::sum);
        }
        return out;
    }

    /**
     * How many times each month stands by its name in a translation into {@code to}: a full name as a word; a short form only beside a
     * number ("12 Oct 2025", "Oct. 12"), since alone, or before a full stop, it is often another word (Spanish "al mar.", the sea).
     */
    static Map<Integer, Integer> named(String to, String translation) {
        Map<Integer, Integer> out = new HashMap<>();
        Names n = NAMES.get(to == null ? "" : to.toLowerCase(Locale.ROOT));
        if (n == null || translation == null) return out;
        String t = Normalizer.normalize(translation, Normalizer.Form.NFC);
        int flags = Pattern.UNICODE_CHARACTER_CLASS | (n.cased() ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        for (int month = 1; month <= 12; month++) {
            int count = 0;
            Matcher full = Pattern.compile("(?<![\\p{L}])(?:" + n.full().get(month - 1) + ")(?![\\p{L}])", flags).matcher(t);
            while (full.find()) count++;
            String s = n.shortForms().get(month - 1);
            if (!s.isEmpty()) {
                Matcher abbr = Pattern.compile("(?<![\\p{L}])(?:" + s + ")(?![\\p{L}])", flags).matcher(t);
                while (abbr.find()) {
                    boolean before = t.substring(Math.max(0, abbr.start() - 4), abbr.start()).matches("(?s).*\\d[\\s,.]*");
                    boolean after = t.substring(abbr.end(), Math.min(t.length(), abbr.end() + 4)).matches("(?s)\\.?[\\s,]*\\d.*");
                    if (before || after) count++;
                }
            }
            if (count > 0) out.put(month, count);
        }
        return out;
    }

    /** The numbers a translation lacks, without each month the source writes as a number and the translation by its name: as many as stand both ways. */
    static void excused(String source, String translation, String to, List<String> missing) {
        if (missing.isEmpty() || to == null || to.isEmpty()) return;
        Map<Integer, Integer> numbered = numbered(source);
        if (numbered.isEmpty()) return;
        Map<Integer, Integer> named = named(to, translation);
        for (var e : numbered.entrySet()) {
            int times = Math.min(e.getValue(), named.getOrDefault(e.getKey(), 0));
            for (int k = 0; k < times; k++) missing.remove(String.valueOf(e.getKey()));
        }
    }
}
