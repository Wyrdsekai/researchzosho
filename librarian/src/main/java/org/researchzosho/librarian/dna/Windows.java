package org.researchzosho.librarian.dna;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The windows the labelled layer reads (0.6.0): up to twelve windows of 1,500 words, spread evenly over the whole text so that the
 * first starts at the first word and the last ends at the last, each with its position from 0 (the start) to 1 (the end); and the
 * one excerpt the book-level questions read — the opening, a slice from the middle and the close. The cut the measurement tooling
 * settled on; a text too short for two windows gets none.
 */
public final class Windows {
    private Windows() { }

    /** The settled window count and width, in words. */
    public static final int COUNT = 12, WIDTH = 1500;
    /** The excerpt's three parts, in words, and the shortest text that gets one. */
    public static final int OPENING = 2500, MIDDLE = 1500, CLOSE = 1000, EXCERPT_MIN = 5000;

    public record Window(int index, int of, double pos, String text) { }

    static final Pattern TOKEN = Pattern.compile("\\S+", Pattern.UNICODE_CHARACTER_CLASS);

    /** The chapters joined, blank ones left out, as the words between runs of white space. */
    public static List<String> words(List<String> chapters) {
        StringBuilder sb = new StringBuilder();
        for (String c : chapters) { if (c == null || c.isBlank()) continue; if (sb.length() > 0) sb.append("\n\n"); sb.append(c); }
        return words(sb.toString());
    }

    public static List<String> words(String text) {
        List<String> out = new ArrayList<>();
        Matcher m = TOKEN.matcher(text);
        while (m.find()) out.add(m.group());
        return out;
    }

    /** Up to {@code k} windows of {@code w} words; none when the text has fewer than two windows' worth. */
    public static List<Window> cut(List<String> words, int k, int w) {
        List<Window> out = new ArrayList<>();
        int len = words.size();
        if (len < 2 * w) return out;
        int count = Math.min(k, Math.max(2, len / w));
        double step = (len - w) / (double) (count - 1);
        for (int i = 0; i < count; i++) {
            int s = (int) (i * step);
            double pos = round(s / (double) Math.max(1, len - w), 3);
            out.add(new Window(i, count, pos, String.join(" ", words.subList(s, Math.min(len, s + w)))));
        }
        return out;
    }

    public static List<Window> cut(List<String> words) { return cut(words, COUNT, WIDTH); }

    /** The first non-blank line of a chapter, as its heading: what most EPUBs put first. */
    public static String heading(String chapter) {
        for (String line : chapter.split("\n")) { String t = line.strip(); if (!t.isEmpty()) return t; }
        return "";
    }

    /** Headings of the front matter: the pages before the book begins. */
    static final Pattern FRONT = Pattern.compile("(?iu)^(title page|half[- ]?title|copyright\\b.{0,160}|contents|table of contents|dedication|epigraph|also by (the )?(author|.{1,60})|other books by .{1,60}|books by .{1,60}|praise for .{1,80}|about the authors?|a note on the (text|type)|list of (illustrations|figures|tables|maps|plates)|目次|奥付|扉)\\s*[:.]?$");
    /** Headings of the back matter: the apparatus after the book ends. */
    static final Pattern BACK = Pattern.compile("(?iu)^((selected |select |annotated |a )?bibliograph(y|ies)|references|works cited|sources( and .{1,40})?|(end|foot)?notes( and (sources|references)| on sources| to .{1,60})?|glossary( of .{1,40})?|index( of .{1,40})?|acknowledge?ments?|about the authors?|also by (the )?(author|.{1,60})|other books by .{1,60}|further reading|suggested reading|recommended reading|for further reading|appendix( [a-z0-9]+)?[:.]?.{0,60}|appendices|credits|permissions( acknowledgments)?|copyright\\b.{0,160}|list of (illustrations|figures|tables|maps|plates)|a note on the (text|type)|about the type|索引|参考文献|参考資料|注|註|原注|訳注|謝辞|奥付|著者紹介|著者略歴|訳者紹介)\\s*[:.]?$");
    /** A line of an index: it ends in a page number or a range of them. */
    static final Pattern INDEX_LINE = Pattern.compile("\\d{1,4}(\\s*[-–]\\s*\\d{1,4})?$");
    /** A line of a bibliography: a year in parentheses, or a year closing the line. */
    static final Pattern BIB_LINE = Pattern.compile("\\((19|20)\\d\\d[a-z]?\\)|[,.]\\s*(19|20)\\d\\d\\.?$");

    /** Whether a chapter reads as an index or a bibliography by its shape: forty lines or more, most of them short and ending as those do. */
    static boolean apparatusLike(String chapter) {
        int lines = 0, index = 0, bib = 0;
        for (String line : chapter.split("\n")) {
            String t = line.strip(); if (t.isEmpty()) continue;
            lines++;
            if (t.length() <= 90 && INDEX_LINE.matcher(t).find()) index++;
            if (t.length() <= 400 && BIB_LINE.matcher(t).find()) bib++;
        }
        return lines >= 40 && (index * 10 >= lines * 6 || bib * 10 >= lines * 6);
    }

    /** A chapter of under forty words at either end is a page of the apparatus (a title page, a dedication, an "also by" list), never the book. */
    static final int TINY = 40;

    static boolean front(String chapter) { return FRONT.matcher(heading(chapter)).matches() || count(chapter) < TINY; }
    static boolean back(String chapter) { return BACK.matcher(heading(chapter)).matches() || apparatusLike(chapter) || count(chapter) < TINY; }

    /**
     * The chapters of the body: the front matter (title page, copyright, contents, dedication…) and the back matter (notes, bibliography,
     * index, glossary, acknowledgements…) left out, told by each chapter's heading, by its shape or by its size (under forty words), only at
     * the two ends and never from the middle; the whole when the cut would leave less than half the words. The labelled layer reads this; the measured layer counts the
     * whole text as the tooling did. Before this the last windows of most nonfiction fell in the index and the bibliography, and "index"
     * and "bibliography" became topics that "like X" counted as subjects (docs/REC_BOOKS.md, the port's lists judged).
     */
    public static List<String> body(List<String> chapters) {
        List<String> cs = new ArrayList<>(); for (String c : chapters) if (c != null && !c.isBlank()) cs.add(c);
        int start = 0, end = cs.size();
        while (start < end && front(cs.get(start))) start++;
        while (end > start && back(cs.get(end - 1))) end--;
        if (start == 0 && end == cs.size()) return cs;
        List<String> out = new ArrayList<>(cs.subList(start, end));
        long kept = 0, all = 0; for (String c : cs) all += count(c); for (String c : out) kept += count(c);
        return kept * 2 < all ? cs : out;
    }

    static int count(String text) { int n = 0; Matcher m = TOKEN.matcher(text); while (m.find()) n++; return n; }

    /** The opening, a slice from the middle and the close, marked; null when the text is shorter than {@link #EXCERPT_MIN} words. */
    public static String excerpt(List<String> words) {
        int len = words.size();
        if (len < EXCERPT_MIN) return null;
        int mid = len / 2;
        return "[OPENING]\n" + String.join(" ", words.subList(0, OPENING)) + "\n\n[FROM THE MIDDLE]\n" + String.join(" ", words.subList(mid - MIDDLE / 2, mid + MIDDLE / 2))
                + "\n\n[CLOSE]\n" + String.join(" ", words.subList(len - CLOSE, len));
    }

    /** Rounded as Python rounds a float: the exact binary value to {@code places} decimals, ties to even. */
    public static double round(double v, int places) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return v;
        return new BigDecimal(v).setScale(places, RoundingMode.HALF_EVEN).doubleValue();
    }
}
