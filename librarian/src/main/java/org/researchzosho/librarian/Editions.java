package org.researchzosho.librarian;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.Year;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the library can compute about a document's edition, for the writer and the reader (0.6.2): a date read from the text's head
 * when the page gives none (a PDF handbook says "Approved 23 June 2026, Effective 22 September 2026" and nothing else dates it), and
 * a pair of sources that look like editions of one document — the same kind of document, nearly the same title, different dates.
 * The writer is told both and decides; the library does not pick the source. Measured need: on 2026-10-15 the local 27B wrote three
 * times from the October 2023 rattan handbook with the June 2026 one captured beside it (a kingdom's own rules are not an edition of
 * the Society's, and the rule here keeps them apart: a different kind word, or a title with more than two words of its own).
 */
public final class Editions {

    private Editions() { }

    private static final int HEAD = 2_500;
    private static final String MONTHS = "january|february|march|april|may|june|july|august|september|october|november|december";
    private static final Pattern DAY_MONTH_YEAR = Pattern.compile("\\b(\\d{1,2})(?:st|nd|rd|th)?\\s+(?:day\\s+of\\s+)?(" + MONTHS + "),?\\s+(\\d{4})\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern MONTH_DAY_YEAR = Pattern.compile("\\b(" + MONTHS + ")\\s+(\\d{1,2})(?:st|nd|rd|th)?,?\\s+(\\d{4})\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern MONTH_YEAR = Pattern.compile("\\b(" + MONTHS + "),?\\s+(\\d{4})\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern ISO = Pattern.compile("\\b(\\d{4})-(\\d{2})-(\\d{2})\\b");
    private static final Pattern KEYWORD = Pattern.compile("\\b(approved|effective|revised|updated|published|issued|adopted|version|edition|amended|in force)\\b", Pattern.CASE_INSENSITIVE);
    /** A date after one of these is when a copy was made, or names the edition before this one, not this one. */
    private static final Pattern NOT_EDITION = Pattern.compile("\\b(generated|printed|downloaded|accessed|retrieved|rendered|exported|previous version|prior version|changes from|supersedes|replaces|replacing|since)\\b[^\\n]{0,16}$", Pattern.CASE_INSENSITIVE);
    /** The title block: a date this early is the edition's, whatever words deeper in the head say. */
    private static final int TITLE_BLOCK = 200;
    private static final Set<String> KEYWORDS = Set.of("approved", "effective", "revised", "updated", "published", "issued", "adopted", "version", "edition", "amended", "force");
    /** The kind of document a title names; two editions share one. */
    private static final Set<String> KINDS = Set.of("handbook", "handbooks", "manual", "rules", "rulebook", "standards", "standard", "policy", "policies", "guide", "guidelines", "regulations", "regulation", "code", "charter", "bylaws", "procedures", "specification", "constitution", "ordinance", "ordinances");
    private static final Set<String> STOP = Set.of("the", "a", "an", "of", "for", "and", "or", "to", "in", "on", "by", "with", "inc", "ltd", "llc", "copyright", "page", "pdf", "generated", "based", "upon", "as", "at", "from", "its", "this", "that");

    /** A dated mention in a text's head. */
    public record Dated(LocalDate date, boolean dayKnown, String keyword) {
        /** "22 September 2026 (effective)", "October 2023". */
        public String text() {
            String d = dayKnown ? date.getDayOfMonth() + " " + month(date) + " " + date.getYear() : month(date) + " " + date.getYear();
            return keyword == null ? d : d + " (" + keyword + ")";
        }
        private static String month(LocalDate d) { String m = d.getMonth().name().toLowerCase(Locale.ROOT); return Character.toUpperCase(m.charAt(0)) + m.substring(1); }
    }

    /** The latest plausible date in the first part of a text, a date after a word like "approved" or "effective" preferred; null when none. */
    public static Dated dateInHead(String text) {
        if (text == null || text.isBlank()) return null;
        String head = text.length() > HEAD ? text.substring(0, HEAD) : text;
        int thisYear = Year.now().getValue();
        List<Object[]> found = new ArrayList<>();   // {LocalDate, dayKnown, start}
        Matcher m = DAY_MONTH_YEAR.matcher(head);
        while (m.find()) add(found, head, m.group(3), m.group(2), m.group(1), m.start(), thisYear);
        m = MONTH_DAY_YEAR.matcher(head);
        while (m.find()) add(found, head, m.group(3), m.group(1), m.group(2), m.start(), thisYear);
        m = ISO.matcher(head);
        while (m.find()) add(found, head, m.group(1), m.group(2), m.group(3), m.start(), thisYear);
        m = MONTH_YEAR.matcher(head);
        while (m.find()) { boolean covered = false; for (Object[] f : found) if (Math.abs((int) f[2] - m.start()) < 16) covered = true; if (!covered) add(found, head, m.group(2), m.group(1), null, m.start(), thisYear); }
        if (found.isEmpty()) return null;
        // the edition's date: one in the title block first ("Armored Combat Marshal's Handbook October 2023"), then one after an edition
        // word, then the latest; among equals the latest (a handbook's "Approved 23 June 2026 Effective 22 September 2026" is the latter)
        Object[] best = null; int bestRank = -1;
        for (Object[] f : found) {
            int at = (int) f[2]; String word = keywordBefore(head, at);
            int rank = at < TITLE_BLOCK ? 2 : word != null ? 1 : 0;
            if (best == null || rank > bestRank || (rank == bestRank && ((LocalDate) f[0]).isAfter((LocalDate) best[0]))) { best = f; bestRank = rank; }
        }
        return new Dated((LocalDate) best[0], (boolean) best[1], keywordBefore(head, (int) best[2]));
    }

    private static void add(List<Object[]> found, String head, String year, String month, String day, int start, int thisYear) {
        try {
            if (NOT_EDITION.matcher(head.substring(Math.max(0, start - 28), start)).find()) return;   // "PDF generated: July 16, 2025", "Changes from Previous Version (June 2022)"
            int y = Integer.parseInt(year);
            if (y < 1980 || y > thisYear + 2) return;
            int mo = month.matches("\\d+") ? Integer.parseInt(month) : monthNumber(month);
            if (mo < 1 || mo > 12) return;
            int d = day == null ? 1 : Integer.parseInt(day);
            if (d < 1 || d > 31) return;
            found.add(new Object[]{LocalDate.of(y, mo, Math.min(d, LocalDate.of(y, mo, 1).lengthOfMonth())), day != null, start});
        } catch (RuntimeException ignored) { }
    }

    private static int monthNumber(String name) {
        String[] names = MONTHS.split("\\|");
        for (int i = 0; i < names.length; i++) if (names[i].equalsIgnoreCase(name)) return i + 1;
        return 0;
    }

    /** The edition word within 40 characters before a date ("Approved 23 June 2026"), lower-cased, or null. */
    private static String keywordBefore(String head, int at) {
        String before = head.substring(Math.max(0, at - 40), at);
        Matcher k = KEYWORD.matcher(before); String last = null;
        while (k.find()) last = k.group(1).toLowerCase(Locale.ROOT);
        return last;
    }

    /** A source as the edition question sees it: its title words (from the head of its text, up to the first date, the publisher's name taken out) and its date. */
    public record Doc(String locator, Set<String> titleWords, Dated dated) {
        String kind() { for (String w : titleWords) if (KINDS.contains(w)) return w; return null; }
    }

    /** Lower-case letters and single spaces, the stop words out, a space at each end. */
    private static String normalized(String s) {
        StringBuilder b = new StringBuilder(" ");
        for (String w : s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}]+", " ").strip().split("\\s+")) if (!w.isBlank() && !STOP.contains(w)) b.append(w).append(' ');
        return b.toString();
    }

    public static Doc describe(String locator, String text) {
        String t = text == null ? "" : text.length() > HEAD ? text.substring(0, HEAD) : text;
        String flat = t.replaceAll("\\s+", " ").strip();
        // the title: what comes before the first date, at most fourteen words
        int firstDate = flat.length();
        for (Pattern p : List.of(DAY_MONTH_YEAR, MONTH_DAY_YEAR, MONTH_YEAR, ISO)) { Matcher m = p.matcher(flat); if (m.find() && m.start() < firstDate) firstDate = m.start(); }
        String titleLine = flat.substring(0, firstDate);
        List<String> words = new ArrayList<>();
        for (String w : titleLine.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}]+", " ").strip().split("\\s+")) if (!w.isBlank() && !STOP.contains(w) && !KEYWORDS.contains(w) && w.length() > 1) words.add(w);
        if (words.size() > 14) words = words.subList(0, 14);
        // the publisher's name: a run of three or more title words that comes again as a phrase in the text right after the title
        // ("… an official corporate publication of the Society for Creative Anachronism")
        String after = normalized(flat.substring(Math.min(flat.length(), firstDate), Math.min(flat.length(), firstDate + 800)));
        boolean[] publisher = new boolean[words.size()];
        for (int i = 0; i + 3 <= words.size(); i++) {
            for (int len = words.size() - i; len >= 3; len--) {
                String phrase = String.join(" ", words.subList(i, i + len));
                if (after.contains(" " + phrase + " ")) { for (int k = i; k < i + len; k++) publisher[k] = true; break; }
            }
        }
        Set<String> title = new LinkedHashSet<>();
        for (int i = 0; i < words.size(); i++) if (KINDS.contains(words.get(i)) || !publisher[i]) title.add(words.get(i));
        if (title.isEmpty()) title.addAll(words);
        return new Doc(locator, title, dateInHead(t));
    }

    /** Whether two sources look like editions of one document: the same kind word, the shorter title mostly inside the longer, the longer with at most two words of its own, and different dates. */
    static boolean editions(Doc a, Doc b) {
        if (a.dated() == null || b.dated() == null || a.dated().date().equals(b.dated().date())) return false;
        String ka = a.kind(), kb = b.kind();
        if (ka == null || kb == null || !sameKind(ka, kb)) return false;
        Set<String> small = a.titleWords().size() <= b.titleWords().size() ? a.titleWords() : b.titleWords(), large = small == a.titleWords() ? b.titleWords() : a.titleWords();
        Set<String> smallOthers = new LinkedHashSet<>(small), largeOthers = new LinkedHashSet<>(large);
        smallOthers.removeIf(KINDS::contains); largeOthers.removeIf(KINDS::contains);
        if (smallOthers.isEmpty()) return false;
        long shared = smallOthers.stream().filter(largeOthers::contains).count();
        if (shared < 2 || shared * 3 < smallOthers.size() * 2) return false;   // at least two words in common, and two thirds of the shorter title
        return largeOthers.size() - shared <= 2;
    }

    private static boolean sameKind(String a, String b) {
        String x = a.endsWith("s") && !a.endsWith("ss") ? a.substring(0, a.length() - 1) : a, y = b.endsWith("s") && !b.endsWith("ss") ? b.substring(0, b.length() - 1) : b;
        return x.equals(y) || (x.equals("policy") && y.equals("policie")) || (x.equals("policie") && y.equals("policy"));
    }

    /** The sources, described from their captures; a locator with no capture is left out. */
    public static Map<String, Doc> describe(LibraryStore store, List<String> locators) {
        Map<String, Doc> out = new LinkedHashMap<>();
        for (String loc : locators) {
            try {
                Path p = RawCapture.find(store, loc);
                if (p == null) continue;
                String[] r = RawCapture.read(p);
                Doc d = describe(loc, r[2]);
                if (d.dated() != null) out.put(loc, d);
            } catch (Exception ignored) { }
        }
        return out;
    }

    /**
     * What the writer is told: each dated source with its date, and each pair that looks like editions of one document, the newer named.
     * Empty when no source carries a date in its head.
     */
    public static String block(LibraryStore store, List<String> locators) {
        Map<String, Doc> docs = describe(store, locators);
        if (docs.isEmpty()) return "";
        StringBuilder b = new StringBuilder("DATED SOURCES (read by the library from each document's own text; the page gave no date):\n");
        for (Doc d : docs.values()) b.append("- ").append(d.locator()).append(" — ").append(d.dated().text()).append('\n');
        List<Doc> list = new ArrayList<>(docs.values());
        List<String> pairs = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) for (int j = i + 1; j < list.size(); j++) {
            Doc a = list.get(i), c = list.get(j);
            if (!editions(a, c)) continue;
            Doc older = a.dated().date().isBefore(c.dated().date()) ? a : c, newer = older == a ? c : a;
            pairs.add("- " + older.locator() + " (" + older.dated().text() + ") looks like an earlier edition of " + newer.locator() + " (" + newer.dated().text() + "): the newer governs unless the texts say otherwise, and say which you used.");
        }
        if (!pairs.isEmpty()) { b.append("EDITIONS OF ONE DOCUMENT (the titles match; a kingdom's or a chapter's own rules are not an edition of the society's):\n"); for (String p : pairs) b.append(p).append('\n'); }
        return b.toString().strip();
    }

    /** For the reference list: the newer edition's number for each older one among the references, by locator. */
    public static Map<String, String> olderEditions(Map<String, Doc> docs, Map<String, Integer> numberOfLocator) {
        Map<String, String> out = new LinkedHashMap<>();
        List<Doc> list = new ArrayList<>(docs.values());
        for (int i = 0; i < list.size(); i++) for (int j = i + 1; j < list.size(); j++) {
            Doc a = list.get(i), c = list.get(j);
            if (!editions(a, c)) continue;
            Doc older = a.dated().date().isBefore(c.dated().date()) ? a : c, newer = older == a ? c : a;
            Integer n = numberOfLocator.get(newer.locator());
            if (n != null) out.put(older.locator(), "[" + n + "]");
        }
        return out;
    }
}
