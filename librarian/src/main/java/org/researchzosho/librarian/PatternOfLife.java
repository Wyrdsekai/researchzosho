package org.researchzosho.librarian;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The pattern of a subject's public traces (0.6.6), computed from the claims on the shelves and nothing else: how much the record says
 * about each year, the places it names with the years it names them in, and the appearances — talks, panels, interviews, podcasts,
 * hearings. A pattern of what the record holds, not a tracking of anyone: a place is listed because a claim says the subject was there.
 */
public final class PatternOfLife {
    private PatternOfLife() { }

    public record Pattern(Map<String, Integer> byYear, Map<String, List<String>> places, List<String> appearances) {
        public boolean isEmpty() { return byYear.isEmpty() && places.isEmpty() && appearances.isEmpty(); }
    }

    static final java.util.regex.Pattern APPEARANCE = java.util.regex.Pattern.compile("(?i)\\b(conference|summit|keynote|panel|talk at|spoke at|speaker at|presented at|interview(?:ed)?|podcast|testif(?:y|ied)|hearing|lecture|fireside|appeared (?:on|at)|guest on)\\b");

    /** The pattern from a subject's claims: the years, the places with their years, the appearances with their years. */
    public static Pattern of(List<Finding> claims) {
        Map<String, Integer> byYear = new TreeMap<>();
        Map<String, TreeSet<String>> places = new TreeMap<>();
        List<String> appearances = new ArrayList<>();
        for (Finding f : claims) {
            if (f.state() == Finding.State.retired || f.state() == Finding.State.superseded) continue;
            String year = Dossier.yearOf(f);
            if (year != null) byYear.merge(year, 1, Integer::sum);
            String text = f.title() + " " + f.body();
            Matcher pl = CrossLinks.PLACE.matcher(text);
            while (pl.find()) { String place = pl.group(1); if (!CrossLinks.STOP.contains(place) && CrossLinks.isATown(place)) places.computeIfAbsent(place, k -> new TreeSet<>()).add(year == null ? "undated" : year); }   // "Columbia University, New York" names no town
            for (String place : CrossLinks.placesJa(text)) places.computeIfAbsent(place, k -> new TreeSet<>()).add(year == null ? "undated" : year);   // by name, or after a prefecture: never 高市 or 内閣府
            if (APPEARANCE.matcher(text).find()) appearances.add((year == null ? "undated" : year) + ": " + Acquisitions.compress(f.title(), 110));
        }
        Map<String, List<String>> placeYears = new LinkedHashMap<>();
        for (var e : places.entrySet()) placeYears.put(e.getKey(), new ArrayList<>(e.getValue()));
        appearances.sort(String.CASE_INSENSITIVE_ORDER);
        return new Pattern(byYear, placeYears, appearances);
    }

    /** The pattern as markdown: a bar a year, the places with their years, the appearances. */
    public static String markdown(Pattern p) {
        if (p.isEmpty()) return "";
        StringBuilder b = new StringBuilder("## Pattern of life\n\nWhat the record holds, by year; the places it names; the appearances. Computed from the claims, not written by a model.\n\n");
        if (!p.byYear().isEmpty()) {
            int max = p.byYear().values().stream().max(Integer::compare).orElse(1);
            b.append("### By year\n\n");
            for (var e : p.byYear().entrySet()) b.append("- ").append(e.getKey()).append(" ").append("█".repeat(Math.max(1, Math.round(e.getValue() * 20f / max)))).append(' ').append(e.getValue()).append('\n');
            b.append('\n');
        }
        if (!p.places().isEmpty()) { b.append("### Places named\n\n"); for (var e : p.places().entrySet()) b.append("- ").append(e.getKey()).append(" (").append(String.join(", ", e.getValue())).append(")\n"); b.append('\n'); }
        if (!p.appearances().isEmpty()) { b.append("### Appearances\n\n"); for (String a : p.appearances()) b.append("- ").append(a).append('\n'); b.append('\n'); }
        return b.toString();
    }

    /** The pattern as the page shows it: bars a year, the places, the appearances; "" when the record holds none. */
    public static String html(Pattern p) {
        if (p.isEmpty()) return "";
        StringBuilder b = new StringBuilder();
        if (!p.byYear().isEmpty()) {
            int max = p.byYear().values().stream().max(Integer::compare).orElse(1);
            b.append("<h3>By year</h3><ul class=\"bars\">");
            for (var e : p.byYear().entrySet()) b.append("<li><span class=\"y\">").append(Pages.esc(e.getKey())).append("</span><span class=\"bar\" style=\"width:").append(Math.max(2, Math.round(e.getValue() * 100f / max))).append("%\"></span><span class=\"n\">").append(e.getValue()).append("</span></li>");
            b.append("</ul>");
        }
        if (!p.places().isEmpty()) { b.append("<h3>Places named</h3><ul class=\"plain\">"); for (var e : p.places().entrySet()) b.append("<li>").append(Pages.esc(e.getKey())).append(" <span class=\"date\">").append(Pages.esc(String.join(", ", e.getValue()))).append("</span></li>"); b.append("</ul>"); }
        if (!p.appearances().isEmpty()) { b.append("<h3>Appearances</h3><ul class=\"plain\">"); for (String a : p.appearances()) b.append("<li>").append(Pages.esc(a)).append("</li>"); b.append("</ul>"); }
        return b.toString();
    }
}
