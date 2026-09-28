package org.researchzosho.librarian;

import java.text.Normalizer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import java.util.Arrays;
import java.util.Locale;
import java.time.Year;
/**
 * A date as a family source writes it, kept as written with the western year worked out beside it: a Japanese era year
 * (明治四十年, 昭和元年, M40, Shōwa 3), a plain year, and whether it is exact, about, before, after or between two years
 * (BET 1850 AND 1860, 1850-1860, 1850 or 1851, 1750/51). Each date is a range of years, {@link #earliest()} to
 * {@link #latest()}, so a check can ask whether any year in it fits. Arithmetic, no model: an era year converted by a
 * model is a year nobody can check.
 */
public record FamilyDate(String written, int year, String qualifier, int until) {

    public FamilyDate(String written, int year, String qualifier) { this(written, year, qualifier, year); }

    /** How far either side of its year an "about" date reaches; and the end of a "before" or "after" date that has none. */
    public static final int ABOUT_YEARS = 5, OPEN = 9999;

    /** How much wider an age counted the old Japanese way (数え年, 享年) makes a birth year: it is one more than the age in full years, or the same. */
    public static final int COUNTED_YEARS = 1;

    // First years are the conventional ones (天保元年 = 1830). Before 1873 Japan kept a lunisolar calendar, so a day late in
    // an era year can fall in January or February of the next western year; the year shown is the conventional one.
    /** era name, its first year, its last year (0 = still running), and the spellings it is written in */
    private record Era(String kanji, int first, int last, String... also) { }

    private static final Era[] ERAS = {
            new Era("令和", 2019, 0, "reiwa", "r"), new Era("平成", 1989, 2019, "heisei", "h"), new Era("昭和", 1926, 1989, "showa", "shōwa", "s"),
            new Era("大正", 1912, 1926, "taisho", "taishō", "t"), new Era("明治", 1868, 1912, "meiji", "m"),
            new Era("慶応", 1865, 1868, "keio", "keiō"), new Era("元治", 1864, 1865, "genji"), new Era("文久", 1861, 1864, "bunkyu", "bunkyū"),
            new Era("万延", 1860, 1861, "man'en", "manen"), new Era("安政", 1854, 1860, "ansei"), new Era("嘉永", 1848, 1854, "kaei"),
            new Era("弘化", 1844, 1848, "koka", "kōka"), new Era("天保", 1830, 1844, "tenpo", "tenpō", "tempo"), new Era("文政", 1818, 1830, "bunsei"),
            new Era("文化", 1804, 1818, "bunka"), new Era("享和", 1801, 1804, "kyowa", "kyōwa"), new Era("寛政", 1789, 1801, "kansei")};

    // EST and CAL (estimated, calculated) are years somebody worked out: about, for every check
    private static final Pattern ABOUT = Pattern.compile("(?i)\\b(about|abt|circa|around|approximately|est|estimated|cal|calculated)\\b|\\bca?\\.|頃|ころ|ごろ|約|前後");
    private static final Pattern BEFORE = Pattern.compile("(?i)\\b(before|bef\\.?|by|until)\\b|以前|まで|より前");
    private static final Pattern AFTER = Pattern.compile("(?i)\\b(after|aft\\.?|since)\\b|以降|以後|より後");

    private static final String Y = "(?<!\\d)(1[5-9]\\d\\d|20\\d\\d)(?!\\d)";
    private static final Pattern SPAN = Pattern.compile("(?i)\\b(?:bet|between|from)\\b.*?" + Y + ".*?\\b(?:and|to|until|till)\\b.*?" + Y);
    private static final Pattern PAIR = Pattern.compile("(?i)" + Y + "\\s*(?:[-–—~〜]|\\bto\\b|\\bor\\b|\\buntil\\b)\\s*" + Y);
    private static final Pattern DUAL = Pattern.compile("(?<!\\d)(1[5-9]\\d\\d)/(\\d\\d)(?!\\d)");   // 1750/51: the old style and the new year of one date
    private static final Pattern RANGE_WORDS = Pattern.compile("(?i)\\b(bet|between|from|and|to|until|till|or)\\b\\.?|[-–—~〜/]");

    /** Null when the text names no year at all. */
    public static FamilyDate parse(String text) {
        if (text == null || text.isBlank()) return null;
        String written = text.strip();
        String t = Normalizer.normalize(written, Normalizer.Form.NFKC);
        String q = ABOUT.matcher(t).find() ? "about" : BEFORE.matcher(t).find() ? "before" : AFTER.matcher(t).find() ? "after" : "";
        int year = eraYear(t);
        if (year == 0) {
            int[] r = range(t);
            if (r != null) return new FamilyDate(written, r[0], "between", r[1]);
            Matcher m = Pattern.compile(Y).matcher(t); if (m.find()) year = Integer.parseInt(m.group(1));
        }
        return year == 0 ? null : new FamilyDate(written, year, q);
    }

    /**
     * The years a question's subject lived: {born, until}, from "born …" and "died …" in the question, each read as a date with its range:
     * "born about 1850" starts at 1845, "born 1854 or before" has no start (-{@link #OPEN}), and a death "about 1928" ends in 1933. Null
     * when the question gives no birth year.
     */
    public static int[] lived(String question) {
        FamilyDate born = bornOf(question);
        if (born == null) return null;
        FamilyDate died = dateAfter(question, "died");
        return new int[]{born.earliest(), died == null ? Year.now().getValue() : died.latest()};
    }

    /** The birth a question gives its subject ("born about 1850 in 伊勢"); null when it gives none. */
    public static FamilyDate bornOf(String question) { return dateAfter(question, "born"); }

    /** The words that start another event of a life, and with it another date. */
    private static final Pattern EVENT = Pattern.compile("(?i)\\b(born|died|death|buried|married|baptised|baptized|christened)\\b");
    /** A register's shorthand at the start of what follows born or died, with the words a date carries: "S3", "about M40", "T12頃". */
    private static final Pattern SHORTHAND_FIRST = Pattern.compile("(?i)^\\s*((?:(?:about|abt|circa|around|c\\.|ca\\.?|before|bef\\.?|after|aft\\.?)\\s*|約)?"
            + "[mtshr]\\.?\\s?[0-9]{1,2}(?:\\s?[./\\-・]\\s?[0-9]{1,2}){0,2}(?:頃|ころ|ごろ|以前|以降|まで|年)?)(?![\\p{L}\\p{N}])");

    private static FamilyDate dateAfter(String question, String word) {
        if (question == null) return null;
        Matcher m = Pattern.compile("(?i)\\b" + word + "\\b([^;:)）]*)").matcher(question);
        while (m.find()) {
            // the date of this event ends where another event starts: "born S3, died 2020" is born in 1928
            String phrase = m.group(1);
            Matcher next = EVENT.matcher(phrase);
            while (next.find()) if (!next.group().equalsIgnoreCase(word)) { phrase = phrase.substring(0, next.start()); break; }
            // a register's shorthand right after the word is the date, whatever follows it: "born S3 Osaka" is born in 1928
            Matcher shorthand = SHORTHAND_FIRST.matcher(phrase);
            FamilyDate d = shorthand.find() ? parse(shorthand.group(1)) : null;
            // the date comes first and the place after it: "born about 1850 in a village by the sea" is about 1850, not before
            if (d == null) d = parse(phrase.strip().split("\\s+(in|at|near)\\s+(?!\\d)")[0]);
            if (d != null) return d;
        }
        return null;
    }

    /**
     * How many years after the event the sources wrote it down, when each of them says when (a GEDCOM citation's own entry date): a
     * birth that is known only from a death record made seventy years later is second-hand, however good the record. 0 when one source
     * was written within a year of the event, or a source does not say when it was written.
     */
    public static int writtenLater(Finding f) {
        if (f.triple() == null || f.sources().isEmpty()) return 0;
        FamilyDate on = f.triple().predicate().endsWith("-on") ? parse(f.triple().object()) : null;
        Integer event = on != null ? Integer.valueOf(on.year()) : FamilyChecks.claimYear(f);
        if (event == null) return 0;
        int least = Integer.MAX_VALUE;
        for (Finding.Source s : f.sources()) {
            String edition = s.edition().replaceAll("\\s*\\{page: [^}]*\\}", "");   // the page a tree file cites, kept after the date
            int at = edition.indexOf(Gedcom.ENTERED);
            if (at < 0) return 0;
            String when = edition.substring(at + Gedcom.ENTERED.length()).split(";", 2)[0].strip();
            FamilyDate d = parse(when);
            if (d == null) return 0;
            least = Math.min(least, d.year() - event);
        }
        return least >= 2 ? least : 0;
    }

    /**
     * The latest year the words allow, for the living rule: "between 1900 and 1930" is 1930, "before 1910" is 1910, "about 1885" is
     * {@link #ABOUT_YEARS} later. Null for "after 1910", which sets no end, and for words that name no year. The last year the words
     * name counts too, where it is later, so a date read only in part still errs toward "could still be living".
     */
    public static Integer latestYear(String text) {
        FamilyDate d = parse(text);
        if (d == null || d.latest() >= OPEN / 2) return null;
        String t = Normalizer.normalize(text, Normalizer.Form.NFKC);
        Matcher m = Pattern.compile(Y).matcher(t);
        int last = d.latest();
        while (m.find()) last = Math.max(last, Integer.parseInt(m.group(1)));
        return last;
    }

    /** {first, last} of a date written as two years; null for one year. */
    private static int[] range(String t) {
        int a = 0, b = 0;
        Matcher m = SPAN.matcher(t);
        if (m.find()) { a = Integer.parseInt(m.group(1)); b = Integer.parseInt(m.group(2)); }
        else if ((m = PAIR.matcher(t)).find()) { a = Integer.parseInt(m.group(1)); b = Integer.parseInt(m.group(2)); }
        else if ((m = DUAL.matcher(t)).find()) { a = Integer.parseInt(m.group(1)); b = a / 100 * 100 + Integer.parseInt(m.group(2)); if (b < a) b += 100; }
        if (a == 0 || a == b) return null;
        return new int[]{Math.min(a, b), Math.max(a, b)};
    }

    /** The first year the date allows; -{@link #OPEN} for a date before a year. */
    public int earliest() { return switch (qualifier) { case "about" -> year - ABOUT_YEARS; case "before" -> -OPEN; default -> year; }; }

    /** The last year the date allows; {@link #OPEN} for a date after a year. */
    public int latest() { return switch (qualifier) { case "about" -> year + ABOUT_YEARS; case "after" -> OPEN; case "between" -> until; default -> year; }; }

    /** The year the date is written around: its own year, or an about year. Null for before, after and between, which name an edge. */
    public Integer centre() { return qualifier.isEmpty() || qualifier.equals("about") ? year : null; }

    public boolean exact() { return qualifier.isEmpty(); }

    /** "1907", "about 1907", "1907 or before", "1907 or after", "between 1850 and 1860": the year or years, as a sentence says them. */
    public String phrase() {
        return switch (qualifier) {
            case "about" -> "about " + year;
            case "before" -> year + " or before";
            case "after" -> year + " or after";
            case "between" -> "between " + year + " and " + until;
            default -> String.valueOf(year);
        };
    }

    /** "in 1907", "about 1907", "between 1850 and 1860": what follows "born" or "died" in a sentence. */
    public String in() { return exact() || qualifier.equals("before") || qualifier.equals("after") ? "in " + phrase() : phrase(); }

    /** Whether two dates have no year in common, even allowing {@code slack} years between them. */
    public static boolean apart(FamilyDate a, FamilyDate b, int slack) { return a.earliest() - b.latest() > slack || b.earliest() - a.latest() > slack; }

    /** A date from the first and last year it allows, with {@link #OPEN} for an end it does not have. */
    public static FamilyDate between(String written, int from, int to) {
        if (from <= -OPEN / 2 && to >= OPEN / 2) return null;
        if (from <= -OPEN / 2) return new FamilyDate(written, to, "before");
        if (to >= OPEN / 2) return new FamilyDate(written, from, "after");
        return from >= to ? new FamilyDate(written, to, "") : new FamilyDate(written, from, "between", to);
    }

    private static final Pattern FOUR_DIGITS = Pattern.compile(Y);
    /** A month or a day after the shorthand: 年, M40.3.5, S3・5・1, "M40, 3, 5", S3 5 1, S3 5月. */
    private static final Pattern DATE_AFTER = Pattern.compile("^\\s*(?:年|[./\\-・]\\s?[0-9]{1,2}|,\\s?[0-9]{1,2}(?![0-9])|\\s[0-9]{1,2}(?![0-9])|\\s[0-9]{1,2}\\s*月)");
    /**
     * The words a date may carry beside the shorthand, in English and in Japanese: about, before, after and their like (頃, 約, 以前, まで,
     * 以降), and born or died written after it (生まれ, 生, 没, 死亡).
     */
    private static final Pattern BESIDE = Pattern.compile("(?i)\\b(about|abt|circa|around|approximately|est|estimated|cal|calculated|before|bef|by|until|after|aft|since|ca?|or)\\b\\.?"
            + "|頃|ころ|ごろ|約|前後|以前|まで|より前|以降|以後|より後|生まれ|生|没|歿|死亡|死去");

    /**
     * Whether the shorthand at {@code from}..{@code to} is written as a date: alone but for words a date carries (about, 頃, 以前, 生まれ, 没)
     * and punctuation, or followed by a month, a day or 年. Any other word beside it makes it something else: "S3 bucket", "an M1 Mac".
     */
    private static boolean asADate(String low, int from, int to) {
        String after = low.substring(to);
        if (DATE_AFTER.matcher(after).find()) return true;
        String before = BESIDE.matcher(low.substring(0, from)).replaceAll("");
        return before.replaceAll("[\\s.,;:(（~〜]", "").isEmpty() && BESIDE.matcher(after).replaceAll("").replaceAll("[\\s.,;:()（）?？]", "").isEmpty();
    }

    static int eraYear(String t) {
        String low = t.toLowerCase(Locale.ROOT);
        for (Era e : ERAS) {
            Matcher m = Pattern.compile(Pattern.quote(e.kanji()) + "\\s*(元|[0-9]{1,2}|[〇一二三四五六七八九十壱壹弐貳参參肆伍陸漆捌玖拾廿卅]{1,3})\\s*年?").matcher(t);
            if (m.find()) return within(e, m.group(1));
            for (String a : e.also()) {
                if (a.length() == 1) {
                    // a one-letter form is the register's shorthand (M40, S.3, M40.3.5, S3年). It is read only where it is written as a date: the
                    // whole of the text, or with a month, a day or 年 after it, and never beside a four-digit year. "born 2020 on an M1 Mac" is
                    // 2020, and "S3 bucket" is no date at all.
                    if (FOUR_DIGITS.matcher(t).find()) continue;
                    Matcher r = Pattern.compile("(?<![a-z0-9])" + a + "\\.?\\s?([0-9]{1,2})(?![0-9])").matcher(low);
                    while (r.find()) if (asADate(low, r.start(), r.end())) return within(e, r.group(1));
                    continue;
                }
                Matcher r = Pattern.compile("(?<![a-z])" + Pattern.quote(a) + "\\s*([0-9]{1,2}|gannen)").matcher(low);
                if (r.find()) return within(e, r.group(1));
            }
        }
        return 0;
    }

    private static int within(Era e, String n) {
        int k = n.equals("元") || n.equals("gannen") ? 1 : n.matches("[0-9]+") ? Integer.parseInt(n) : kanjiNumber(n);
        int year = e.first() + k - 1;
        return k < 1 || (e.last() > 0 && year > e.last()) ? 0 : year;   // 明治50年 does not exist: better no year than a wrong one
    }

    static int kanjiNumber(String s) {
        int total = 0, current = 0;
        // a register writes its numbers in the formal forms that cannot be altered with a stroke: 壱弐参拾, and 廿 for twenty, 卅 for thirty
        String plain = s.replace('壱', '一').replace('壹', '一').replace('弐', '二').replace('貳', '二').replace('参', '三').replace('參', '三').replace('肆', '四').replace('伍', '五')
                .replace('陸', '六').replace('漆', '七').replace('捌', '八').replace('玖', '九').replace('拾', '十').replace("廿", "二十").replace("卅", "三十");
        for (char c : plain.toCharArray()) {
            int d = "〇一二三四五六七八九".indexOf(c);
            if (d >= 0) current = current * 10 + d;
            else if (c == '十') { total += (current == 0 ? 1 : current) * 10; current = 0; }
            else if (c == '百') { total += (current == 0 ? 1 : current) * 100; current = 0; }
        }
        return total + current;
    }

    /** An age as a record writes it: whole years (0 for months, weeks or days), whether it is counted the old Japanese way (数え年, 享年), and whether it is about. */
    public record Age(int years, boolean counted, boolean about) { }

    private static final Pattern COUNTED = Pattern.compile("数え|数へ|享年|行年");
    private static final Pattern UNDER_A_YEAR = Pattern.compile("(?i)^\\s*<\\s*1\\s*y|\\d\\s*(m|mo|mos|months?|w|wks?|weeks?|d|days?)\\b|ヶ月|か月|カ月|ケ月|箇月|週間|\\d\\s*日|\\binfant\\b|\\bstillborn\\b");

    /** Null when no age can be read: "42", "42y 3m", "42歳", "七十三歳", "享年73", "数え年5歳", "3 months", "INFANT". */
    public static Age age(String text) {
        if (text == null || text.isBlank()) return null;
        String t = Normalizer.normalize(text.strip(), Normalizer.Form.NFKC);
        boolean counted = COUNTED.matcher(t).find() && !t.contains("満"), about = ABOUT.matcher(t).find();
        if (t.matches("(?i)\\s*<\\s*1\\s*y.*")) return new Age(0, false, about);
        Matcher y = Pattern.compile("(?i)(\\d{1,3})\\s*(?:y\\b|yrs?\\b|years?\\b|歳|才|さい)").matcher(t);
        if (y.find()) return new Age(Integer.parseInt(y.group(1)), counted, about);
        Matcher k = Pattern.compile("([〇一二三四五六七八九十百]+)\\s*(?:歳|才)").matcher(t);
        if (k.find()) return new Age(kanjiNumber(k.group(1)), counted, about);
        if (UNDER_A_YEAR.matcher(t).find()) return new Age(0, counted, about);
        Matcher bare = Pattern.compile("^\\D*?(\\d{1,3})\\D*$").matcher(t);
        if (bare.find()) return new Age(Integer.parseInt(bare.group(1)), counted, about);
        Matcher kb = Pattern.compile("^(?:享年|行年|数え年?|数へ年?)?\\s*([〇一二三四五六七八九十百]+)$").matcher(t);
        return kb.find() ? new Age(kanjiNumber(kb.group(1)), counted, about) : null;
    }

    /**
     * The years a person of this age at this date was born in: an age of N in full years in the year Y is a birth in Y-N-1 or Y-N; an age counted
     * the old Japanese way reaches {@link #COUNTED_YEARS} later, since 享年 is written both ways. Null when the date has no end on either side.
     */
    public static FamilyDate bornFrom(Age a, FamilyDate at) {
        if (a == null || at == null) return null;
        int widen = a.about() ? ABOUT_YEARS : 0;
        int from = at.earliest() <= -OPEN / 2 ? -OPEN : at.earliest() - a.years() - 1 - widen;
        int to = at.latest() >= OPEN / 2 ? OPEN : at.latest() - a.years() + (a.counted() ? COUNTED_YEARS : 0) + widen;
        return between("aged " + a.years() + " " + at.in(), from, to);
    }

    private static final Pattern RELATIVE = Pattern.compile("(?i)同日|同月|同年|翌々年|翌年|翌月|翌日|前年|\\bthe same (?:day|month|year)\\b|\\b(?:the )?(?:following|next) year\\b|\\b(?:the )?(?:previous|preceding) year\\b|\\bthe year before\\b");

    /** Whether a date is written against an earlier one and names no year of its own: 同年五月一日, 翌年, the following year. */
    public static boolean relative(String text) { return text != null && parse(text) == null && RELATIVE.matcher(Normalizer.normalize(text, Normalizer.Form.NFKC)).find(); }

    /**
     * A date as {@link #parse(String)} reads it; and a date written against an earlier one (同日, 同月十日, 同年, 翌年, 前年, the following year) with its
     * year worked out from {@code anchor}, the date written before it. Null when neither gives a year.
     */
    public static FamilyDate parse(String text, FamilyDate anchor) {
        FamilyDate own = parse(text);
        if (own != null || anchor == null || !relative(text)) return own;
        String t = Normalizer.normalize(text.strip(), Normalizer.Form.NFKC);
        Matcher m = RELATIVE.matcher(t);
        m.find();
        String w = m.group().toLowerCase(Locale.ROOT);
        int shift = w.equals("翌々年") ? 2 : w.equals("翌年") || w.contains("following") || w.contains("next") ? 1
                : w.equals("前年") || w.contains("previous") || w.contains("preceding") || w.contains("before") ? -1 : 0;
        if (w.equals("翌月") || w.equals("翌日")) { int[] md = monthDay(anchor.written()); if (md[0] == 12 && (w.equals("翌月") || md[1] == 31)) shift = 1; }
        String rest = m.replaceAll(" ");
        String q = ABOUT.matcher(rest).find() ? "about" : BEFORE.matcher(rest).find() ? "before" : AFTER.matcher(rest).find() ? "after" : "";
        return q.isEmpty() ? new FamilyDate(text.strip(), anchor.year() + shift, anchor.qualifier(), anchor.until() + shift) : new FamilyDate(text.strip(), anchor.year() + shift, q);
    }

    /** The last date written in a text: the one a date written against "the same year" goes back to. Null when it has none. */
    public static FamilyDate lastIn(String text) {
        if (text == null || text.isBlank()) return null;
        String t = Normalizer.normalize(text, Normalizer.Form.NFKC);
        int at = -1;
        Matcher y = Pattern.compile(Y).matcher(t);
        while (y.find()) at = Math.max(at, y.start());
        for (Era e : ERAS) { int k = t.lastIndexOf(e.kanji()); at = Math.max(at, k); }
        if (at < 0) return null;
        String tail = t.substring(at);
        Matcher d = DATE_AT.matcher(tail);
        return parse(d.lookingAt() ? d.group() : tail);   // the date alone, not the words of the entry that follow it
    }

    private static final String NUMERAL = "(?:[0-9]{1,2}|[〇一二三四五六七八九十壱壹弐貳参參肆伍陸漆捌玖拾廿卅]{1,3})";
    private static final Pattern DATE_AT = Pattern.compile("(?:" + String.join("|", Arrays.stream(ERAS).map(Era::kanji).toList()) + ")\\s*(?:元|" + NUMERAL + ")\\s*年(?:\\s*" + NUMERAL + "\\s*月)?(?:\\s*" + NUMERAL + "\\s*日)?"
            + "|" + Y + "(?:年(?:[0-9]{1,2}月)?(?:[0-9]{1,2}日)?|[-/.][0-9]{1,2}(?:[-/.][0-9]{1,2})?)?");

    /** {month, day} of a written date, 0 where it has none: 十二月五日, 12月5日, 1907-12-05. */
    static int[] monthDay(String written) {
        String t = Normalizer.normalize(written == null ? "" : written, Normalizer.Form.NFKC);
        Matcher jp = Pattern.compile("(" + NUMERAL + ")\\s*月(?:\\s*(" + NUMERAL + ")\\s*日)?").matcher(t);
        Matcher iso = Pattern.compile("\\d{4}[-/.](\\d{1,2})(?:[-/.](\\d{1,2}))?").matcher(t);
        if (jp.find()) return new int[]{number(jp.group(1)), jp.group(2) == null ? 0 : number(jp.group(2))};
        if (iso.find()) return new int[]{Integer.parseInt(iso.group(1)), iso.group(2) == null ? 0 : Integer.parseInt(iso.group(2))};
        return new int[]{0, 0};
    }

    private static int number(String n) { return n.matches("[0-9]+") ? Integer.parseInt(n) : kanjiNumber(n); }

    /** "明治40年 (1907)", "about 1885", "1907": what a claim shows. The source's own words first when they are not the bare year. */
    public String shown() {
        String y = qualifier.equals("between") ? phrase() : (qualifier.isEmpty() ? "" : qualifier + " ") + year;
        String bare = AFTER.matcher(BEFORE.matcher(ABOUT.matcher(Normalizer.normalize(written, Normalizer.Form.NFKC)).replaceAll("")).replaceAll("")).replaceAll("").strip();
        if (qualifier.equals("between")) bare = RANGE_WORDS.matcher(bare).replaceAll(" ").replaceAll("\\s+", " ").strip();
        return bare.equals(qualifier.equals("between") ? year + " " + until : String.valueOf(year)) ? y : written + " (" + y + ")";   // "around 1906" is "about 1906", not both
    }
}
