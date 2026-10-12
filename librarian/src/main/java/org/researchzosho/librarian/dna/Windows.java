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
