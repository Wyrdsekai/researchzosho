package org.researchzosho.librarian;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a paper's text says about its figures and tables, and the numeric rows its tables left in the
 * text stream. PDF text extraction keeps neither the images nor the table grid, but it keeps the
 * captions ("Figure 3. Tooth profile of gear B1…", "Table 2: Manufacturing errors…") and, usually, the
 * rows as lines of numbers. This lifts both out so a raw entry can say what it holds and a patron can
 * ask for "table 2" and get its rows rather than nothing. A heuristic, not a table parser: it names
 * what it found, it does not promise the grid.
 */
public final class Captions {

    private static final Pattern CAPTION = Pattern.compile(
            "(?m)^\\s*((?:Fig(?:ure|\\.)|Table|Tab\\.|Plate|図|表)\\s*\\d+[A-Za-z]?)\\s*[.:：]\\s*(.{3,400}?)\\s*$");
    private static final Pattern NUMERIC_ROW = Pattern.compile(
            "^\\s*\\S.{0,40}?(?:(?:\\s{2,}|\\t|\\s\\|\\s)[-+±]?\\d[\\d.,%]*(?:\\s*[±×x]\\s*[\\d.]+)?){2,}\\s*$");

    private Captions() { }

    public record Extract(List<String> figures, List<String> tables, List<String> rows) {
        public boolean isEmpty() { return figures.isEmpty() && tables.isEmpty() && rows.isEmpty(); }
    }

    /** Captions and numeric rows in {@code text}, capped so a long paper yields a summary, not a dump. */
    public static Extract extract(String text) {
        List<String> figures = new ArrayList<>(), tables = new ArrayList<>(), rows = new ArrayList<>();
        if (text == null || text.isBlank()) return new Extract(figures, tables, rows);
        Matcher m = CAPTION.matcher(text);
        while (m.find() && figures.size() + tables.size() < 60) {
            String label = m.group(1).replaceAll("\\s+", " ");
            String cap = label + ": " + m.group(2).replaceAll("\\s+", " ");
            boolean table = label.matches("(?i)^(Table|Tab\\.|表).*");
            List<String> into = table ? tables : figures;
            if (!into.contains(cap)) into.add(cap);
        }
        int run = 0;
        for (String line : text.split("\\r?\\n")) {
            if (rows.size() >= 200) break;
            if (NUMERIC_ROW.matcher(line).matches() && line.length() <= 200) {
                run++;
                if (run >= 2) {          // a lone numeric line is prose; two in a row is a table
                    rows.add(line.strip().replaceAll("\\s{2,}", " | "));
                }
            } else {
                run = 0;
            }
        }
        return new Extract(figures, tables, rows);
    }
}
