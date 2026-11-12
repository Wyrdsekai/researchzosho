package org.researchzosho.librarian;

import java.text.Bidi;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A PDF's pages as a person reads them (0.7.3): from the glyphs a page draws, each with its place, its size and the way its line runs,
 * to the page's paragraphs in reading order. A pure function: no PDF, no font, no file; {@link PdfGlyphs} feeds it from PDFBox, and the
 * tests feed it pages of made-up glyphs.
 *
 * <p>What it does, in order. The glyphs are put into lines along the way each runs: left to right (H), top to bottom (V: Japanese,
 * Chinese and Korean set vertically, and Latin turned with them), bottom to top (U: a stamp up the margin). A line of a column is the
 * glyphs at one height (H) or one column's centre (V); a wide space in a line splits it only where the lines around have one at the
 * same place (a gutter between columns, a gap between the tiers of a vertical page), so a name set with wide spacing stays one line.
 * A horizontal digit inside a vertical column (縦中横) is read as part of the column. Small glyphs beside a line are its marks: kana
 * beside a vertical column are ruby and are left out, a footnote's number stays where it is set, a superscript is written as one. Lines
 * at the edge of the page that are page numbers, or that come back on other pages (a running head), are taken out as the page's
 * furniture. The page is then turned so that its lines run left to right and follow each other downward (a right-to-left page is
 * mirrored, a vertical one turned a quarter), the lines are gathered into blocks, and the blocks are ordered by cutting the page
 * along its gutters, a column read whole before the next. Within a block a paragraph starts at an indented line, after a short line
 * that ends a sentence, or after a wider gap; its lines are joined with a space where the script spaces its words, without one where
 * it does not, and a word hyphenated at the line's end is joined again. A line with right-to-left letters is put in the order its
 * reader expects (the visual order turned to the logical one by the bidirectional algorithm, a ligature kept whole).
 *
 * <p>Each page carries its measurements ({@link Stats}): the reader's sensor ({@link PdfReading}) decides from them whether the page's
 * text layer is read or the page is read by eye.
 */
public final class PdfLayout {
    private PdfLayout() { }

    /** The way a glyph's line runs: left to right, top to bottom, bottom to top. */
    public enum Axis { H, V, U }

    /**
     * One glyph as the page shows it: its text, the box of its em in points from the page's top left corner (for a horizontal glyph
     * from 0.8 of its size above the baseline to 0.2 below; for a vertical one its em square), its size in points, the way its line
     * runs, whether its font is bold, whether it is drawn invisibly (the text layer a scanner's recognition puts over the picture).
     */
    public record Glyph(String text, double x0, double y0, double x1, double y1, double size, Axis axis, boolean bold, boolean invisible) {
        double cx() { return (x0 + x1) / 2; }
        double cy() { return (y0 + y1) / 2; }
        /** A horizontal glyph: its left edge, its baseline, its size and its width. */
        public static Glyph h(String text, double x, double baseline, double size, double width) { return new Glyph(text, x, baseline - 0.8 * size, x + width, baseline + 0.2 * size, size, Axis.H, false, false); }
        /** A vertical glyph: the centre of its column, its top, its size. */
        public static Glyph v(String text, double centre, double top, double size) { return new Glyph(text, centre - size / 2, top, centre + size / 2, top + size, size, Axis.V, false, false); }
        public Glyph bolded() { return new Glyph(text, x0, y0, x1, y1, size, axis, true, invisible); }
        public Glyph hidden() { return new Glyph(text, x0, y0, x1, y1, size, axis, bold, true); }
    }

    /** A rectangle on the page, in points from its top left corner. */
    public record Box(double x0, double y0, double x1, double y1) {
        public double w() { return Math.max(0, x1 - x0); }
        public double h() { return Math.max(0, y1 - y0); }
        public double area() { return w() * h(); }
        boolean holds(double x, double y) { return x >= x0 && x <= x1 && y >= y0 && y <= y1; }
    }

    /** One page as the adapter found it: its number from 1, its size in points, its glyphs, the pictures drawn on it. */
    public record PageIn(int number, double width, double height, List<Glyph> glyphs, List<Box> pictures) { }

    public static final String TITLE = "title", HEADING = "heading", PARA = "para", CAPTION = "caption", PICTURE = "picture", FIGURE = "figure";

    /**
     * A paragraph, heading, caption, picture or a figure's labels, in reading order: its kind, its text ("" for a picture), the page it
     * starts on, its box, whether it opens a paragraph for certain (its first line indented), a heading's level, its size, its lines, and
     * its box in the page's frame (turned so that its lines run left to right).
     */
    public record Block(String kind, String text, int page, Box box, boolean opens, int level, double size, int lines, Box frame) {
        Block withText(String t) { return new Block(kind, t, page, box, opens, level, size, lines, frame); }
        Block as(String k, int l) { return new Block(k, text, page, box, opens, l, size, lines, frame); }
        Block joined(Block next) { return new Block(kind, join(text, next.text()), page, box, opens, level, size, lines + next.lines(), frame); }
    }

    /**
     * What the page gave, counted for the sensor: the glyphs drawn (and of them: drawn invisibly; letters or digits; bad, a glyph whose
     * text is the replacement character or a private-use, control or unassigned code point); the characters of its blocks; its
     * units (paragraphs, headings, captions), how many are fragments of one to five characters, how many have twenty characters or more
     * and how many of those end a sentence; the words of the scripts that space them and how many look like words; its lines, the lines
     * taken out as furniture, the ruby and the marks found; the share of the page its largest picture covers.
     */
    public record Stats(int glyphs, int invisible, int chars, int letters, int bad, int units, int fragments, int fragmentChars, int longUnits, int ended,
                        int oneLine, int tokens, int wordLike, int lines, int furniture, int ruby, int marks, double pictureCover, int pictures) {
        /** The share of the units of twenty characters or more that are one line and end no sentence: lines that were not joined into paragraphs. */
        public double oneLineShare() { return longUnits == 0 ? 0 : oneLine / (double) longUnits; }
        public double fragmentCharShare() { return chars == 0 ? 0 : fragmentChars / (double) chars; }
        public double fragmentShare() { return units == 0 ? 0 : fragments / (double) units; }
        public double endedShare() { return longUnits == 0 ? 1 : ended / (double) longUnits; }
        public double badShare() { return glyphs == 0 ? 0 : bad / (double) glyphs; }
        public double letterShare() { return glyphs == 0 ? 1 : letters / (double) glyphs; }
        public double wordShare() { return tokens == 0 ? 1 : wordLike / (double) tokens; }
        public double invisibleShare() { return glyphs == 0 ? 0 : invisible / (double) glyphs; }
    }

    /** A page read: its number, its size, the way it is read (ltr, rtl, vertical, up), its blocks in order, its measurements. */
    public record Page(int number, double width, double height, String direction, List<Block> blocks, Stats stats) {
        /** The page's words, its blocks with a blank line between. */
        public String text() {
            StringBuilder b = new StringBuilder();
            for (Block k : blocks) if (!k.text().isBlank()) b.append(b.length() == 0 ? "" : "\n\n").append(k.text());
            return b.toString();
        }
    }

    // ---- the document: every page laid out, the furniture found across pages, a paragraph that runs on joined ----

    /** The pages laid out, the running heads and page numbers taken out, a paragraph that runs on over a break joined to its start. */
    public static List<Page> document(List<PageIn> in) {
        List<Prepared> ps = new ArrayList<>();
        for (PageIn p : in) ps.add(prepare(p));
        return finish(ps);
    }

    /** One page on its own: no furniture is found without the other pages, beyond its page number. */
    public static Page page(PageIn p) { return document(List.of(p)).get(0); }

    /**
     * A page's lines and its glyphs' counts, the glyphs themselves let go: what {@link #finish} needs, so that a long document is read a
     * few pages at a time and never holds every glyph at once.
     */
    public static final class Prepared {
        final int number; final double width, height; final List<Line> lines; final List<Box> pictures;
        final int glyphs, invisible, letters, bad, leaders;
        Prepared(PageIn p, List<Line> lines) {
            number = p.number(); width = p.width(); height = p.height(); this.lines = lines; pictures = List.copyOf(p.pictures());
            int g = 0, inv = 0, let = 0, b = 0, lead = 0;
            for (Glyph x : p.glyphs()) {
                if (blank(x.text())) continue;
                g++;
                if (x.invisible()) inv++;
                if (x.text().codePoints().anyMatch(PdfLayout::bad)) b++;
                else if (x.text().codePoints().anyMatch(Character::isLetterOrDigit)) let++;
                else if (LEADER.matcher(x.text()).matches()) lead++;
            }
            glyphs = g; invisible = inv; letters = let; bad = b; leaders = lead;
            for (Line l : lines) { l.gs.clear(); l.spaces.clear(); }
        }
        public int number() { return number; }
        public int glyphs() { return glyphs; }
    }

    public static Prepared prepare(PageIn p) { return new Prepared(p, lines(p.glyphs())); }

    /** The prepared pages laid out together: furniture found across them, each page ordered, paragraphs joined over breaks, the title found. */
    public static List<Page> finish(List<Prepared> ps) {
        furniture(ps);
        List<Page> pages = new ArrayList<>();
        for (Prepared p : ps) pages.add(page(p));
        return title(joinRuns(pages));
    }

    // ---- lines ----

    /** A line of glyphs: its axis, its glyphs, its box, its size; marks to set into it; ruby left out; whether it is furniture. */
    static final class Line {
        final Axis axis;
        final List<Glyph> gs = new ArrayList<>();
        final List<Glyph> spaces = new ArrayList<>();
        final List<double[]> markAt = new ArrayList<>();   // [along position, index into markText]
        final List<String> markText = new ArrayList<>();
        double x0 = Double.MAX_VALUE, y0 = Double.MAX_VALUE, x1 = -Double.MAX_VALUE, y1 = -Double.MAX_VALUE, size;
        int ruby, marks; boolean furniture, bold, invisible; String text;
        Line(Axis a) { axis = a; }
        void add(Glyph g) { gs.add(g); x0 = Math.min(x0, g.x0()); y0 = Math.min(y0, g.y0()); x1 = Math.max(x1, g.x1()); y1 = Math.max(y1, g.y1()); }
        void done() {
            List<Double> s = new ArrayList<>(); int b = 0, inv = 0;
            for (Glyph g : gs) { s.add(g.size()); if (g.bold()) b++; if (g.invisible()) inv++; }
            s.sort(null); size = s.isEmpty() ? 0 : s.get(s.size() / 2);
            bold = !gs.isEmpty() && b * 5 >= gs.size() * 4; invisible = !gs.isEmpty() && inv * 2 > gs.size();
        }
        double a0() { return axis == Axis.H ? x0 : axis == Axis.V ? y0 : -y1; }
        double a1() { return axis == Axis.H ? x1 : axis == Axis.V ? y1 : -y0; }
        Box box() { return new Box(x0, y0, x1, y1); }
        int chars() { return text == null ? 0 : text.codePointCount(0, text.length()); }
    }

    static double a0(Glyph g, Axis a) { return a == Axis.H ? g.x0() : a == Axis.V ? g.y0() : -g.y1(); }
    static double a1(Glyph g, Axis a) { return a == Axis.H ? g.x1() : a == Axis.V ? g.y1() : -g.y0(); }
    static double ac(Glyph g, Axis a) { return (a0(g, a) + a1(g, a)) / 2; }
    /** The cross position a line's glyphs share: the baseline of a horizontal glyph, the centre of a vertical one's column. */
    static double key(Glyph g, Axis a) { return a == Axis.H ? g.y1() - 0.2 * g.size() : g.cx(); }

    static boolean blank(String s) { return s == null || s.isBlank() || s.codePoints().allMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c) || c == 0x200B); }

    /** The page's lines: built along each axis, the horizontal digits inside vertical columns set into them, the marks and ruby placed. */
    static List<Line> lines(List<Glyph> raw) {
        Map<Axis, List<Glyph>> by = new LinkedHashMap<>(), spaces = new LinkedHashMap<>();
        for (Axis a : Axis.values()) { by.put(a, new ArrayList<>()); spaces.put(a, new ArrayList<>()); }
        for (Glyph g : raw) {
            if (g.text() == null || g.text().isEmpty() || g.size() <= 0) continue;
            String t = CONTROL.matcher(normal(g.text())).replaceAll("");
            if (t.isEmpty()) continue;   // a control character drawn as a glyph (an anchor's mark) is no text; the sensor counts it
            Glyph n = new Glyph(t, g.x0(), g.y0(), g.x1(), g.y1(), g.size(), g.axis(), g.bold(), g.invisible());
            (blank(n.text()) ? spaces : by).get(n.axis()).add(n);
        }
        List<Glyph> combining = new ArrayList<>();
        for (Axis a : Axis.values()) by.get(a).removeIf(g -> { if (g.text().codePoints().allMatch(c -> Character.getType(c) == Character.NON_SPACING_MARK || Character.getType(c) == Character.ENCLOSING_MARK)) { combining.add(g); return true; } return false; });
        List<Line> v = tracks(dedupe(by.get(Axis.V), Axis.V), Axis.V);
        for (Line l : v) l.done();
        List<Glyph> h = new ArrayList<>();
        for (Glyph g : dedupe(by.get(Axis.H), Axis.H)) if (!tateChuYoko(g, v)) h.add(g);
        List<Line> out = new ArrayList<>(v);
        out.addAll(tracks(h, Axis.H));
        out.addAll(tracks(dedupe(by.get(Axis.U), Axis.U), Axis.U));
        for (Line l : out) l.done();
        for (Glyph m : combining) attachMark(m, out);
        for (Line l : out) for (Glyph s : spaces.get(l.axis)) if (Math.abs(key(s, l.axis) - (l.axis == Axis.H ? l.y1 - 0.2 * l.size : (l.x0 + l.x1) / 2)) < 0.5 * l.size && ac(s, l.axis) > l.a0() && ac(s, l.axis) < l.a1()) l.spaces.add(s);
        satellites(out);
        for (Line l : out) l.text = text(l);
        out.removeIf(l -> l.text.isBlank());
        brackets(out);
        return out;
    }

    /** A leader's or a rule's glyph: the dots of a contents line, a dash or an underline drawn as text. */
    private static final Pattern LEADER = Pattern.compile("[.·…‥・_\\-–—=]+");

    private static final Pattern CONTROL = Pattern.compile("[\\x00-\\x08\\x0B-\\x1F\\x7F-\\x9F]");

    /**
     * A combining mark drawn as a glyph of its own (an Arabic vowel sign over its letter): joined to the glyph of the nearest line whose
     * box holds the mark's middle along the line, so that it follows its letter in any order the line is read in; left out when no line
     * lies within a size of it.
     */
    static void attachMark(Glyph m, List<Line> lines) {
        Line best = null; double bestGap = Double.MAX_VALUE;
        for (Line l : lines) {
            if (l.axis != m.axis()) continue;
            double along = ac(m, l.axis);
            if (along < l.a0() || along > l.a1()) continue;
            double gap = l.axis == Axis.H ? Math.max(0, Math.max(l.y0 - m.y1(), m.y0() - l.y1)) : Math.max(0, Math.max(l.x0 - m.x1(), m.x0() - l.x1));
            if (gap <= l.size && gap < bestGap) { bestGap = gap; best = l; }
        }
        if (best == null) return;
        for (int i = 0; i < best.gs.size(); i++) {
            Glyph g = best.gs.get(i);
            double along = ac(m, best.axis);
            if (along >= a0(g, best.axis) && along <= a1(g, best.axis)) { best.gs.set(i, new Glyph(g.text() + m.text(), g.x0(), g.y0(), g.x1(), g.y1(), g.size(), g.axis(), g.bold(), g.invisible())); return; }
        }
    }

    /** Glyph text made comparable: Arabic presentation forms, Latin ligatures and the vertical forms of punctuation to their letters. */
    static String normal(String s) {
        boolean compat = false;
        for (int i = 0; i < s.length(); i++) { char c = s.charAt(i); if ((c >= 0xFB00 && c <= 0xFDFF) || (c >= 0xFE10 && c <= 0xFE19) || (c >= 0xFE30 && c <= 0xFE4F) || (c >= 0xFE70 && c <= 0xFEFF)) { compat = true; break; } }
        return compat ? Normalizer.normalize(s, Normalizer.Form.NFKC) : s;
    }

    /** A glyph drawn twice in the same place (a bold made by drawing it again a hair to the side, a shadow) counted once. */
    static List<Glyph> dedupe(List<Glyph> gs, Axis a) {
        if (gs.size() < 2) return gs;
        List<Glyph> s = new ArrayList<>(gs);
        s.sort(Comparator.comparingDouble(g -> a0(g, a)));
        List<Glyph> out = new ArrayList<>();
        for (Glyph g : s) {
            boolean dup = false;
            for (int k = out.size() - 1; k >= 0 && k >= out.size() - 6; k--) {
                Glyph o = out.get(k);
                if (o.text().equals(g.text()) && Math.abs(a0(o, a) - a0(g, a)) < 0.2 * g.size() && Math.abs(key(o, a) - key(g, a)) < 0.2 * g.size()) { dup = true; break; }
            }
            if (!dup) out.add(g);
        }
        return out;
    }

    /** A horizontal glyph standing inside a vertical column, a number set across it: it is put into the column. */
    static boolean tateChuYoko(Glyph g, List<Line> v) {
        Line best = null; double score = Double.MAX_VALUE;
        for (Line l : v) {
            double s = l.size;
            if (g.size() > s * 1.3) continue;
            if (g.cx() >= l.x0 - 0.1 * s && g.cx() <= l.x1 + 0.1 * s && g.cy() >= l.y0 - 1.5 * s && g.cy() <= l.y1 + 1.5 * s) {
                double sc = Math.abs(g.cx() - (l.x0 + l.x1) / 2) + 2 * Math.abs(g.size() - s);   // the column whose middle and size are nearest: a note's digit goes with its mark, not the text beside
                if (sc < score) { score = sc; best = l; }
            }
        }
        if (best == null) return false;
        best.add(g);
        return true;
    }

    /**
     * The lines of one axis: glyphs at one cross position (within 0.3 of the smaller size, sizes within 30%) form a row; a row is cut
     * where the space between two glyphs is wider than a size and a half (vertical) or one size (horizontal), and the pieces are joined
     * again across a space of up to three sizes unless two other rows nearby have a wide space at the same place (a gutter).
     */
    static List<Line> tracks(List<Glyph> gs, Axis a) {
        if (gs.isEmpty()) return new ArrayList<>();
        List<Glyph> s = new ArrayList<>(gs);
        s.sort(Comparator.comparingDouble((Glyph g) -> key(g, a)).thenComparingDouble(g -> a0(g, a)));
        List<List<Glyph>> rows = new ArrayList<>();
        List<double[]> rowKey = new ArrayList<>();   // [key, size]
        for (Glyph g : s) {
            double k = key(g, a);
            int found = -1;
            for (int r = rows.size() - 1; r >= 0; r--) {
                double rk = rowKey.get(r)[0], rs = rowKey.get(r)[1];
                if (rk < k - 1.5 * Math.max(rs, g.size())) break;
                boolean sameBase = Math.abs(k - rk) <= 0.3 * Math.min(rs, g.size()) && Math.max(rs, g.size()) <= 1.3 * Math.min(rs, g.size());
                boolean smallCaps = a == Axis.H && Math.abs(k - rk) <= 0.06 * Math.max(rs, g.size()) && Math.max(rs, g.size()) <= 2.2 * Math.min(rs, g.size());   // small capitals sit on the line's own baseline
                if (sameBase || smallCaps) { found = r; break; }
            }
            if (found < 0) { rows.add(new ArrayList<>()); rowKey.add(new double[]{k, g.size()}); found = rows.size() - 1; }
            rows.get(found).add(g);
            rowKey.get(found)[1] = Math.max(rowKey.get(found)[1], g.size());   // a row of small capitals takes its capitals' size
        }
        double split = a == Axis.H ? 1.0 : 1.5;
        // every row cut at its wide spaces; the wide spaces kept to find the gutters
        List<List<List<Glyph>>> pieces = new ArrayList<>();
        List<double[]> wide = new ArrayList<>();   // [row, key, start, end]
        for (int r = 0; r < rows.size(); r++) {
            List<Glyph> row = rows.get(r);
            row.sort(Comparator.comparingDouble(g -> a0(g, a)));
            double size = rowKey.get(r)[1];
            List<List<Glyph>> ps = new ArrayList<>();
            List<Glyph> cur = new ArrayList<>(); double end = -Double.MAX_VALUE;
            for (Glyph g : row) {
                double gap = a0(g, a) - end;
                if (!cur.isEmpty() && gap > 0.8 * size) wide.add(new double[]{r, rowKey.get(r)[0], end, a0(g, a)});
                if (!cur.isEmpty() && gap > split * size) { ps.add(cur); cur = new ArrayList<>(); }
                cur.add(g); end = Math.max(end, a1(g, a));
            }
            if (!cur.isEmpty()) ps.add(cur);
            pieces.add(ps);
        }
        List<Line> out = new ArrayList<>();
        for (int r = 0; r < pieces.size(); r++) {
            List<List<Glyph>> ps = pieces.get(r);
            double size = rowKey.get(r)[1], k = rowKey.get(r)[0];
            Line line = null; double end = 0;
            for (List<Glyph> p : ps) {
                double start = a0(p.get(0), a);
                // a list's mark or a note's number (three glyphs at most) is joined to its text across a space of up to two sizes, gutter or not:
                // the marks of a list stand one above the other, and their spaces line up as a gutter's would
                boolean mark = line != null && line.gs.size() <= 3 && start - end <= 2 * size;
                boolean join = line != null && start - end <= 3 * size && (mark || !gutter(wide, r, k, end, start, size));
                if (!join) { line = new Line(a); out.add(line); }
                for (Glyph g : p) { line.add(g); end = Math.max(end, a1(g, a)); }
            }
        }
        return out;
    }

    /** Whether the space from {@code start} to {@code end} in row {@code r} lines up with wide spaces of two other rows nearby. */
    static boolean gutter(List<double[]> wide, int r, double key, double start, double end, double size) {
        Set<Integer> rows = new HashSet<>();
        double lo = start + 0.15 * (end - start), hi = end - 0.15 * (end - start);
        for (double[] w : wide) {
            if ((int) w[0] == r || Math.abs(w[1] - key) > 14 * size) continue;
            if (Math.min(hi, w[3]) - Math.max(lo, w[2]) > 0.2 * size) rows.add((int) w[0]);
            if (rows.size() >= 2) return true;
        }
        return false;
    }

    /**
     * The small lines beside a larger one: ruby (kana only) is left out; any other mark (a footnote's number, a superscript) is set
     * into the larger line at its place. A small line is one of at most twelve glyphs, at most 0.8 of its neighbour's size, lying
     * within its neighbour's length and touching it: overlapping it across (a horizontal superscript or subscript), or beside it (a
     * vertical column's ruby and marks).
     */
    static void satellites(List<Line> lines) {
        List<Line> small = new ArrayList<>(lines);
        small.sort(Comparator.comparingDouble(l -> l.size));
        Set<Line> gone = new HashSet<>();
        for (Line s : small) {
            if (s.gs.size() > 12 || gone.contains(s)) continue;
            double len = s.a1() - s.a0();
            if (len > 10 * s.size) continue;
            Line host = null; double best = Double.MAX_VALUE;
            for (Line l : lines) {
                if (l == s || gone.contains(l) || l.axis != s.axis || l.size < s.size * 1.25 || l.gs.size() < 2) continue;
                double hs = l.size;
                if (s.a0() < l.a0() - 1.0 * hs || s.a1() > l.a1() + 1.0 * hs) continue;   // a note's number may stand just before its line
                double gap;
                if (s.axis == Axis.H) {
                    double overlap = Math.min(s.y1, l.y1) - Math.max(s.y0, l.y0);
                    if (overlap < 0.1 * s.size) continue;
                    gap = -overlap;
                } else {
                    gap = Math.max(s.x0 - l.x1, l.x0 - s.x1);
                    if (gap > 0.15 * hs) continue;
                }
                if (gap < best) { best = gap; host = l; }
            }
            if (host == null) continue;
            String t = rawText(s);
            if (!t.isEmpty() && t.codePoints().allMatch(PdfLayout::kana)) { host.ruby++; gone.add(s); continue; }
            String mark = s.axis == Axis.H && (s.y1 - 0.2 * s.size) < (host.y1 - 0.2 * host.size) - 0.15 * host.size ? superscript(t) : t;
            host.markAt.add(new double[]{(s.a0() + s.a1()) / 2, host.markText.size()});
            host.markText.add(mark);
            host.marks++;
            gone.add(s);
        }
        lines.removeAll(gone);
    }

    static boolean kana(int c) {
        Character.UnicodeScript sc = Character.UnicodeScript.of(c);
        return sc == Character.UnicodeScript.HIRAGANA || sc == Character.UnicodeScript.KATAKANA || c == 'ー' || c == '・';
    }

    private static final String SUP_FROM = "0123456789+-=()ni", SUP_TO = "⁰¹²³⁴⁵⁶⁷⁸⁹⁺⁻⁼⁽⁾ⁿⁱ";

    /** A raised mark as a superscript where the characters have one (¹², ⁽³⁾), else in brackets. */
    static String superscript(String t) {
        StringBuilder b = new StringBuilder();
        for (char c : t.toCharArray()) {
            int i = SUP_FROM.indexOf(c);
            if (i >= 0) b.append(SUP_TO.charAt(i));
            else if ("*†‡§¶,".indexOf(c) >= 0) b.append(c);
            else return "[" + t + "]";
        }
        return b.toString();
    }

    /** The glyphs of a line in their order along it, no spaces: for telling ruby from a mark. */
    static String rawText(Line l) {
        List<Glyph> g = new ArrayList<>(l.gs);
        g.sort(Comparator.comparingDouble(x -> ac(x, l.axis)));
        StringBuilder b = new StringBuilder();
        for (Glyph x : g) b.append(x.text());
        return b.toString().strip();
    }

    /** A character of the scripts written without spaces between words: Han, kana, their punctuation, the full-width forms. */
    static boolean cjk(int c) {
        Character.UnicodeScript s = Character.UnicodeScript.of(c);
        return s == Character.UnicodeScript.HAN || s == Character.UnicodeScript.HIRAGANA || s == Character.UnicodeScript.KATAKANA
                || (c >= 0x3000 && c <= 0x303F) || (c >= 0xFF00 && c <= 0xFFEF) || c == 'ー' || (c >= 0x2E80 && c <= 0x2FDF) || s == Character.UnicodeScript.THAI || s == Character.UnicodeScript.LAO || s == Character.UnicodeScript.KHMER || s == Character.UnicodeScript.MYANMAR;
    }

    static boolean rtl(int c) { byte d = Character.getDirectionality(c); return d == Character.DIRECTIONALITY_RIGHT_TO_LEFT || d == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC; }

    /**
     * The line's text: its glyphs in order along it, a space where the gap between two glyphs is wider than 0.15 of the size (0.35
     * between a CJK character and another script; never between two CJK characters) or where the PDF draws one, the marks at their
     * places; a line with right-to-left letters put in logical order.
     */
    static String text(Line l) {
        List<Glyph> g = new ArrayList<>(l.gs);
        Axis a = l.axis;
        if (a != Axis.H) g.sort(Comparator.comparingDouble((Glyph x) -> a0(x, a)).thenComparingDouble(x -> a == Axis.V ? x.x0() : -x.x0()));
        else g.sort(Comparator.comparingDouble((Glyph x) -> x.x0()));
        List<String> tokens = new ArrayList<>();
        List<double[]> marks = new ArrayList<>(l.markAt);
        marks.sort(Comparator.comparingDouble(m -> m[0]));
        int mi = 0;
        double end = -Double.MAX_VALUE; int lastCp = -1; Axis lastAxis = a;
        for (Glyph x : g) {
            while (mi < marks.size() && marks.get(mi)[0] < ac(x, a)) { tokens.add(l.markText.get((int) marks.get(mi)[1])); mi++; }
            int first = x.text().codePointAt(0);
            if (lastCp >= 0) {
                double gap = a0(x, a) - end;
                boolean bothCjk = cjk(lastCp) && cjk(first), oneCjk = cjk(lastCp) || cjk(first);
                boolean drawn = false;
                for (Glyph sp : l.spaces) if (ac(sp, a) > end - 0.1 * x.size() && ac(sp, a) < a0(x, a) + 0.1 * x.size()) { drawn = true; break; }
                boolean across = a == Axis.V && (x.axis() == Axis.H || lastAxis == Axis.H);   // a number set across a vertical column is part of it
                if (!bothCjk && !across && (gap > (oneCjk ? 0.35 : 0.15) * Math.min(x.size(), l.size) || (drawn && !oneCjk) || (drawn && gap > 0.2 * x.size()))) tokens.add(" ");
            }
            tokens.add(x.text());
            end = Math.max(end, a1(x, a));
            lastCp = x.text().codePointBefore(x.text().length());
            lastAxis = x.axis();
        }
        while (mi < marks.size()) { tokens.add(l.markText.get((int) marks.get(mi)[1])); mi++; }
        if (a == Axis.H && tokens.stream().anyMatch(t -> t.codePoints().anyMatch(PdfLayout::rtl))) tokens = logical(tokens);
        return String.join("", tokens).replaceAll("\\s+", " ").strip();
    }

    private static final Map<Character, Character> MIRROR = Map.of('(', ')', ')', '(', '[', ']', ']', '[', '{', '}', '}', '{', '<', '>', '>', '<', '«', '»', '»', '«');

    /**
     * A horizontal line's glyphs, left to right as drawn, put in the order a reader of a right-to-left script reads them: the
     * bidirectional algorithm's levels for the drawn order, then its reordering of the glyphs (a glyph that stands for several letters,
     * a ligature, kept as it is), a bracket inside a right-to-left run turned to face the other way. Numbers and Latin words inside
     * keep their own order.
     */
    static List<String> logical(List<String> visualIn) {
        // two numbers drawn with a space between (6 000) are one number to the bidirectional algorithm: a no-break space while it runs
        List<String> visual = new ArrayList<>(visualIn);
        for (int i = 1; i + 1 < visual.size(); i++) if (visual.get(i).equals(" ") && endsDigit(visual.get(i - 1)) && startsDigit(visual.get(i + 1))) visual.set(i, "\u00A0");
        String joined = String.join("", visual);
        int rtlCount = 0, ltrCount = 0;
        for (int i = 0; i < joined.length(); ) { int c = joined.codePointAt(i); if (rtl(c)) rtlCount++; else if (Character.isLetter(c)) ltrCount++; i += Character.charCount(c); }
        Bidi bidi = new Bidi(joined, rtlCount >= ltrCount ? Bidi.DIRECTION_RIGHT_TO_LEFT : Bidi.DIRECTION_LEFT_TO_RIGHT);
        int n = visual.size();
        byte[] levels = new byte[n];
        Integer[] order = new Integer[n];
        int pos = 0;
        for (int i = 0; i < n; i++) { levels[i] = (byte) bidi.getLevelAt(Math.min(pos, Math.max(0, joined.length() - 1))); pos += visual.get(i).length(); order[i] = i; }
        // the drawn order read as if it were logical and put in display order is the logical order: reversing a run twice restores it
        Bidi.reorderVisually(levels, 0, order, 0, n);
        List<String> out = new ArrayList<>();
        for (int i : order) out.add(visual.get(i).equals("\u00A0") ? " " : visual.get(i));
        return out;
    }

    /**
     * The brackets of the page's right-to-left lines turned round when the PDF maps each bracket glyph to the shape drawn rather than to
     * the character meant: decided for the page by its lines, from which comes first in each line, a closing or an opening bracket.
     */
    static void brackets(List<Line> lines) {
        int closingFirst = 0, openingFirst = 0;
        List<Line> rtlLines = new ArrayList<>();
        for (Line l : lines) {
            long r = l.text.codePoints().filter(PdfLayout::rtl).count(), lt = l.text.codePoints().filter(c -> Character.isLetter(c) && !rtl(c)).count();
            if (r == 0 || r < lt) continue;
            rtlLines.add(l);
            for (int i = 0; i < l.text.length(); i++) {
                char c = l.text.charAt(i);
                if ("([{".indexOf(c) >= 0) { openingFirst++; break; }
                if (")]}".indexOf(c) >= 0) { closingFirst++; break; }
            }
        }
        if (closingFirst <= openingFirst) return;
        for (Line l : rtlLines) {
            StringBuilder b = new StringBuilder(l.text);
            for (int i = 0; i < b.length(); i++) { Character m = MIRROR.get(b.charAt(i)); if (m != null && "()[]{}".indexOf(b.charAt(i)) >= 0) b.setCharAt(i, m); }
            l.text = b.toString();
        }
    }

    private static boolean endsDigit(String t) { return !t.isEmpty() && Character.isDigit(t.codePointBefore(t.length())); }
    private static boolean startsDigit(String t) { return !t.isEmpty() && Character.isDigit(t.codePointAt(0)); }

    // ---- furniture: page numbers and running heads at the page's edges ----

    private static final Pattern PAGE_NUMBER = Pattern.compile("(?i)^[\\s\\-–—(\\[]*(?:page\\s*|p\\.\\s*|seite\\s*|pág(?:ina)?\\.?\\s*)?(?:\\d{1,4}|[ivxlcdm]{1,7}|[〇一二三四五六七八九十百千]{1,6})(?:\\s*(?:of|/|von|de)\\s*\\d{1,4})?[\\s\\-–—)\\]]*$|^\\d{1,4}\\s*(?:ページ|頁)$|^[-–—]\\s*\\d{1,4}\\s*[-–—]$");

    /** Whether a line's text is a page's number: 12, xii, 一七, "Page 3 of 9", "- 12 -". */
    static boolean pageNumber(String t) { return t.length() <= 20 && PAGE_NUMBER.matcher(t.strip()).matches(); }

    /**
     * The lines at a page's edges (the outer tenth of its height, or of its width) that are its furniture: a page number; a line turned
     * up the margin; a line whose words, its numbers aside, come back at the edge of other pages, a quarter of them and at least two.
     */
    static void furniture(List<Prepared> in) {
        Map<String, Set<Integer>> seen = new HashMap<>();
        for (int i = 0; i < in.size(); i++) for (Line l : in.get(i).lines) if (edge(in.get(i), l)) seen.computeIfAbsent(norm(l.text), k -> new HashSet<>()).add(i);
        int needed = Math.max(2, (int) Math.ceil(in.size() * 0.25));
        for (int i = 0; i < in.size(); i++) {
            for (Line l : in.get(i).lines) {
                if (!edge(in.get(i), l) || l.chars() > 160) continue;
                if (pageNumber(l.text) || (l.axis == Axis.U && l.chars() < 160) || seen.getOrDefault(norm(l.text), Set.of()).size() >= needed) l.furniture = true;
            }
        }
    }

    static boolean edge(Prepared p, Line l) {
        double h = p.height, w = p.width;
        return l.y1 <= h * 0.1 || l.y0 >= h * 0.9 || l.x1 <= w * 0.1 || l.x0 >= w * 0.9;
    }

    static String norm(String t) { return t.toLowerCase(Locale.ROOT).replaceAll("[0-9〇一二三四五六七八九十]+", "#").replaceAll("\\s+", " ").strip(); }

    // ---- the page: lines into blocks, blocks into reading order, blocks into paragraphs ----

    /** The way the page is read: the axis most of its glyphs run along, and for horizontal text the script most of its letters are in. */
    static String direction(List<Line> lines) {
        long h = 0, v = 0, u = 0, r = 0, l = 0;
        for (Line x : lines) {
            if (x.furniture) continue;
            int n = x.chars();
            if (x.axis == Axis.V) v += n; else if (x.axis == Axis.U) u += n; else h += n;
            if (x.axis == Axis.H) for (int i = 0; i < x.text.length(); ) { int c = x.text.codePointAt(i); if (rtl(c)) r++; else if (Character.isLetter(c)) l++; i += Character.charCount(c); }
        }
        if (v > h && v >= u) return "vertical";
        if (u > h) return "up";
        return r > l ? "rtl" : "ltr";
    }

    /** A box turned so that the page's lines run left to right and follow each other downward. */
    static Box frame(Box b, String dir, double w, double h) {
        return switch (dir) {
            case "rtl" -> new Box(w - b.x1(), b.y0(), w - b.x0(), b.y1());
            case "vertical" -> new Box(b.y0(), w - b.x1(), b.y1(), w - b.x0());
            case "up" -> new Box(h - b.y1(), b.x0(), h - b.y0(), b.x1());
            default -> b;
        };
    }

    /** The axis that reads as the page's lines in its frame. */
    static Axis mainAxis(String dir) { return dir.equals("vertical") ? Axis.V : dir.equals("up") ? Axis.U : Axis.H; }

    /** A block being built: its lines with their boxes in the frame. */
    static final class Blk {
        final List<Line> lines = new ArrayList<>(); final List<Box> fb = new ArrayList<>();
        double x0 = Double.MAX_VALUE, y0 = Double.MAX_VALUE, x1 = -Double.MAX_VALUE, y1 = -Double.MAX_VALUE;
        final Box picture; final String own;   // a picture's box (no lines); the direction its lines are read in
        boolean placed;   // lines of another axis than the page's, standing in the page's frame by their box
        Blk(Box picture, String own) { this.picture = picture; this.own = own; }
        void add(Line l, Box f) { lines.add(l); fb.add(f); x0 = Math.min(x0, f.x0()); y0 = Math.min(y0, f.y0()); x1 = Math.max(x1, f.x1()); y1 = Math.max(y1, f.y1()); }
        void box(Box f) { x0 = Math.min(x0, f.x0()); y0 = Math.min(y0, f.y0()); x1 = Math.max(x1, f.x1()); y1 = Math.max(y1, f.y1()); }
        double size() { return lines.isEmpty() ? 0 : lines.get(0).size; }
    }

    static Page page(Prepared p) {
        List<Line> all = p.lines;
        List<Line> lines = new ArrayList<>();
        int furniture = 0;
        for (Line l : all) { if (l.furniture) furniture++; else lines.add(l); }
        String dir = direction(lines);
        Axis main = mainAxis(dir);
        double w = p.width, h = p.height;
        // the lines of the page's own axis gathered into blocks in its frame; the lines of another axis into blocks in theirs
        List<Blk> blocks = new ArrayList<>();
        Map<Axis, List<Line>> by = new LinkedHashMap<>();
        for (Line l : lines) by.computeIfAbsent(l.axis, k -> new ArrayList<>()).add(l);
        for (var e : by.entrySet()) {
            String own = e.getKey() == main ? dir : e.getKey() == Axis.V ? "vertical" : e.getKey() == Axis.U ? "up" : (direction(e.getValue()).equals("rtl") ? "rtl" : "ltr");
            for (Blk b : gather(e.getValue(), own, w, h)) {
                if (e.getKey() == main) { blocks.add(b); continue; }
                Blk placed = new Blk(null, own);   // a block of another axis keeps its lines in its own frame, and stands in the page's frame by its box
                placed.lines.addAll(b.lines); placed.fb.addAll(b.fb); placed.placed = true;
                Box real = union(b.lines);
                placed.box(frame(real, dir, w, h));
                blocks.add(placed);
            }
        }
        double pageArea = w * h, cover = 0;
        int pictureCount = 0;
        for (Box pic : p.pictures) {
            double share = pageArea <= 0 ? 0 : pic.area() / pageArea;
            cover = Math.max(cover, Math.min(1, share));
            if (share >= 0.01) pictureCount++;
            if (share >= 0.01 && share < 0.5) { Blk b = new Blk(pic, ""); b.box(frame(pic, dir, w, h)); blocks.add(b); }
        }
        List<Blk> ordered = order(blocks);
        // the body's size: the size most of the page's characters are set in
        List<double[]> sizes = new ArrayList<>();
        for (Line l : lines) sizes.add(new double[]{l.size, l.chars()});
        double body = weightedMedian(sizes);
        List<Block> out = new ArrayList<>();
        for (Blk b : ordered) {
            if (b.picture != null) { out.add(new Block(PICTURE, "", p.number, b.picture, true, 0, 0, 0, new Box(b.x0, b.y0, b.x1, b.y1))); continue; }
            out.addAll(paragraphs(b, p.number, body));
        }
        out = figures(out);
        return new Page(p.number, w, h, dir, out, stats(p, out, lines, furniture, cover, pictureCount));
    }

    /**
     * The labels of a drawing (a diagram's boxes and arrows drawn as lines, its words set as text) gathered into one block: pieces of at most
     * three short lines, each within three of its sizes of another, eight lines or more in three pieces or more that do not stand in one column
     * (a contents list does), make a figure; its labels are kept together, in reading order, as one block of the kind figure, so that they are neither
     * paragraphs nor lost.
     */
    static List<Block> figures(List<Block> bs) {
        List<Integer> tiny = new ArrayList<>();
        for (int i = 0; i < bs.size(); i++) {
            Block b = bs.get(i); int n = b.text().codePointCount(0, b.text().length());
            if ((b.kind().equals(PARA) || b.kind().equals(HEADING)) && b.lines() <= 3 && n <= 60 && n <= 25 * b.lines()) tiny.add(i);
        }
        if (tiny.size() < 3) return bs;
        int[] parent = new int[tiny.size()];
        for (int i = 0; i < parent.length; i++) parent[i] = i;
        for (int i = 0; i < tiny.size(); i++) for (int j = i + 1; j < tiny.size(); j++) {
            Box a = bs.get(tiny.get(i)).box(), b = bs.get(tiny.get(j)).box();
            double s = 3 * Math.max(bs.get(tiny.get(i)).size(), bs.get(tiny.get(j)).size());
            double dx = Math.max(0, Math.max(a.x0() - b.x1(), b.x0() - a.x1())), dy = Math.max(0, Math.max(a.y0() - b.y1(), b.y0() - a.y1()));
            if (dx <= s && dy <= s) { int ra = root(parent, i), rb = root(parent, j); if (ra != rb) parent[ra] = rb; }
        }
        Map<Integer, List<Integer>> groups = new LinkedHashMap<>();
        for (int i = 0; i < tiny.size(); i++) groups.computeIfAbsent(root(parent, i), k -> new ArrayList<>()).add(tiny.get(i));
        Set<Integer> gone = new HashSet<>();
        Map<Integer, Block> at = new HashMap<>();
        for (List<Integer> g : groups.values()) {
            // a figure: eight lines or more in three pieces or more, not one column of short entries (a contents list)
            int lines = 0; Set<Long> xs = new HashSet<>();
            for (int i : g) { Block b = bs.get(i); lines += b.lines(); xs.add(Math.round(b.box().x0() / Math.max(1, 2 * b.size()))); }
            if (lines < 8 || g.size() < 3 || xs.size() < 2) continue;
            StringBuilder t = new StringBuilder();
            double x0 = Double.MAX_VALUE, y0 = Double.MAX_VALUE, x1 = -Double.MAX_VALUE, y1 = -Double.MAX_VALUE;
            for (int i : g) { Block b = bs.get(i); t.append(t.length() == 0 ? "" : " ").append(b.text()); x0 = Math.min(x0, b.box().x0()); y0 = Math.min(y0, b.box().y0()); x1 = Math.max(x1, b.box().x1()); y1 = Math.max(y1, b.box().y1()); gone.add(i); }
            Block first = bs.get(g.get(0));
            at.put(g.get(0), new Block(FIGURE, t.toString(), first.page(), new Box(x0, y0, x1, y1), true, 0, first.size(), g.size(), first.frame()));
        }
        List<Block> out = new ArrayList<>();
        for (int i = 0; i < bs.size(); i++) { if (at.containsKey(i)) out.add(at.get(i)); else if (!gone.contains(i)) out.add(bs.get(i)); }
        return out;
    }

    private static int root(int[] p, int i) { while (p[i] != i) { p[i] = p[p[i]]; i = p[i]; } return i; }

    static Box union(List<Line> ls) {
        double x0 = Double.MAX_VALUE, y0 = Double.MAX_VALUE, x1 = -Double.MAX_VALUE, y1 = -Double.MAX_VALUE;
        for (Line l : ls) { x0 = Math.min(x0, l.x0); y0 = Math.min(y0, l.y0); x1 = Math.max(x1, l.x1); y1 = Math.max(y1, l.y1); }
        return new Box(x0, y0, x1, y1);
    }

    static Box boxes(List<Box> bs) {
        double x0 = Double.MAX_VALUE, y0 = Double.MAX_VALUE, x1 = -Double.MAX_VALUE, y1 = -Double.MAX_VALUE;
        for (Box b : bs) { x0 = Math.min(x0, b.x0()); y0 = Math.min(y0, b.y0()); x1 = Math.max(x1, b.x1()); y1 = Math.max(y1, b.y1()); }
        return new Box(x0, y0, x1, y1);
    }

    static double weightedMedian(List<double[]> vw) {
        if (vw.isEmpty()) return 0;
        vw.sort(Comparator.comparingDouble(x -> x[0]));
        double total = 0; for (double[] x : vw) total += x[1];
        double run = 0;
        for (double[] x : vw) { run += x[1]; if (run * 2 >= total) return x[0]; }
        return vw.get(vw.size() - 1)[0];
    }

    /**
     * Lines into blocks, in the frame of {@code dir}: a line joins the block above it when it is of the same size (within 15%) and
     * weight, overlaps the block's last line across by four tenths of the narrower, and lies below it by no more than one and a half
     * times the page's usual gap between lines (at least half a size).
     */
    static List<Blk> gather(List<Line> lines, String dir, double w, double h) {
        List<Box> f = new ArrayList<>();
        for (Line l : lines) f.add(frame(l.box(), dir, w, h));
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) idx.add(i);
        idx.sort(Comparator.comparingDouble((Integer i) -> f.get(i).y0()).thenComparingDouble(i -> f.get(i).x0()));
        // the page's usual gap: each line's gap to the nearest line below it that overlaps it and is of its size
        List<Double> gaps = new ArrayList<>();
        for (int a = 0; a < idx.size(); a++) {
            Box fa = f.get(idx.get(a)); Line la = lines.get(idx.get(a));
            double best = Double.MAX_VALUE;
            for (int b = a + 1; b < idx.size(); b++) {
                Box fb = f.get(idx.get(b)); Line lb = lines.get(idx.get(b));
                if (fb.y0() > fa.y1() + 3 * la.size) break;
                if (fb.y0() < fa.y0() + 0.3 * la.size || !sameSize(la, lb) || overlapX(fa, fb) < 0.4 * Math.min(fa.w(), fb.w())) continue;
                best = Math.min(best, fb.y0() - fa.y1());
            }
            if (best < Double.MAX_VALUE) gaps.add(best / Math.max(1, la.size));
        }
        gaps.sort(null);
        double usual = gaps.isEmpty() ? 0.4 : Math.max(0, gaps.get(gaps.size() / 2));
        List<Blk> out = new ArrayList<>();
        for (int i : idx) {
            Line l = lines.get(i); Box fl = f.get(i);
            Blk best = null; double bestGap = Double.MAX_VALUE;
            for (Blk b : out) {
                Line last = b.lines.get(b.lines.size() - 1); Box fbl = b.fb.get(b.fb.size() - 1);
                if (!sameSize(last, l) || last.bold != l.bold && (b.lines.size() == 1 || l.bold)) continue;
                double gap = fl.y0() - fbl.y1();
                if (fl.y0() < fbl.y0() + 0.3 * l.size || gap > Math.max(0.5, 1.5 * usual) * l.size + 0.5) continue;
                if (overlapX(fl, fbl) < 0.4 * Math.min(fl.w(), fbl.w())) continue;
                if (gap < bestGap) { bestGap = gap; best = b; }
            }
            if (best == null) { best = new Blk(null, dir); out.add(best); }
            best.add(l, fl);
        }
        return out;
    }

    static boolean sameSize(Line a, Line b) { return Math.max(a.size, b.size) <= 1.15 * Math.min(a.size, b.size) + 0.01; }
    static double overlapX(Box a, Box b) { return Math.min(a.x1(), b.x1()) - Math.max(a.x0(), b.x0()); }

    /**
     * The blocks in reading order, in the page's frame: cut down a gutter no block crosses and read the left part whole before the right;
     * where no such gutter runs the whole height, cut across where no block crosses, and read the bands from the top, a run of bands
     * that share a gutter read as one (two columns broken only by a shared gap read column by column); where neither cut exists, from
     * the top and then from the left.
     */
    static List<Blk> order(List<Blk> bs) {
        if (bs.size() <= 1) return bs;
        List<List<Blk>> cols = splitX(bs);
        if (cols.size() > 1) { List<Blk> out = new ArrayList<>(); for (List<Blk> c : cols) out.addAll(order(c)); return out; }
        List<List<Blk>> bands = splitY(bs);
        if (bands.size() > 1) {
            List<List<Blk>> groups = new ArrayList<>();
            List<Blk> g = new ArrayList<>(bands.get(0));
            for (int i = 1; i < bands.size(); i++) {
                List<Blk> b = bands.get(i), union = new ArrayList<>(g); union.addAll(b);
                if (splitX(g).size() > 1 && splitX(b).size() > 1 && splitX(union).size() > 1) g = union;
                else { groups.add(g); g = new ArrayList<>(b); }
            }
            groups.add(g);
            List<Blk> out = new ArrayList<>();
            for (List<Blk> x : groups) out.addAll(order(x));
            return out;
        }
        List<Blk> s = new ArrayList<>(bs);
        s.sort(Comparator.comparingDouble((Blk b) -> b.y0).thenComparingDouble(b -> b.x0));
        return s;
    }

    static List<List<Blk>> splitX(List<Blk> bs) {
        List<Blk> s = new ArrayList<>(bs);
        s.sort(Comparator.comparingDouble(b -> b.x0));
        List<List<Blk>> out = new ArrayList<>();
        List<Blk> cur = new ArrayList<>(); double right = -Double.MAX_VALUE;
        for (Blk b : s) {
            if (!cur.isEmpty() && b.x0 >= right - 0.5) { out.add(cur); cur = new ArrayList<>(); right = -Double.MAX_VALUE; }
            cur.add(b); right = Math.max(right, b.x1);
        }
        if (!cur.isEmpty()) out.add(cur);
        return out;
    }

    static List<List<Blk>> splitY(List<Blk> bs) {
        List<Blk> s = new ArrayList<>(bs);
        s.sort(Comparator.comparingDouble(b -> b.y0));
        List<List<Blk>> out = new ArrayList<>();
        List<Blk> cur = new ArrayList<>(); double bottom = -Double.MAX_VALUE;
        for (Blk b : s) {
            if (!cur.isEmpty() && b.y0 >= bottom - 0.5) { out.add(cur); cur = new ArrayList<>(); bottom = -Double.MAX_VALUE; }
            cur.add(b); bottom = Math.max(bottom, b.y1);
        }
        if (!cur.isEmpty()) out.add(cur);
        return out;
    }

    private static final Pattern ENDS = Pattern.compile("[.!?。！？．…‥:;：؟۔।]['\"’”」』）)\\]〕》»]*$");
    private static final Pattern ITEM = Pattern.compile("^(?:(?:[•●○◦▪■□◆◇‣∙·*\\-–]|\\(?\\d{1,3}[.)]|\\(?[a-zA-Z][.)]|[①-⑳]|\\([ivx]{1,4}\\))\\s+|[（(]\\d{1,3}[)）]|\\[\\d{1,3}]\\s|注\\s?\\d{1,3}|※).*");
    private static final Pattern CAPTION_START = Pattern.compile("(?i)^(?:figure|fig\\.|table|tab\\.|chart|plate|abbildung|abb\\.|tabelle|tableau|figura|tabla|рис\\.|таблица|图|圖|図|表|그림|표)\\s*[0-9０-９一二三四五六七八九十IVXivx]+.*");
    private static final Pattern NUMBERED_HEADING = Pattern.compile("^(?:\\d{1,2}(?:\\.\\d{1,2}){0,3}\\.?|[IVX]{1,5}\\.|[一二三四五六七八九十]{1,3}[、．.　 ]?|第[一二三四五六七八九十0-9]{1,3}[章節部])\\s*\\S.*");

    /** Whether a text ends a sentence: its last mark is a full stop, a question or an exclamation, a colon, in any script, a closing quote after it. */
    public static boolean endsSentence(String t) { return t != null && ENDS.matcher(t.strip()).find(); }

    /**
     * A block's paragraphs: its lines in order, a new paragraph at an indented line (an outdented one where most lines are indented:
     * a list or a bibliography), at a list's mark, after a short line that ends a sentence, after a gap wider than the block's usual
     * one; each paragraph a heading, a caption or a paragraph.
     */
    static List<Block> paragraphs(Blk b, int page, double body) {
        int n = b.lines.size();
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < n; i++) idx.add(i);
        idx.sort(Comparator.comparingDouble(i -> b.fb.get(i).y0()));
        double left = Double.MAX_VALUE, right = -Double.MAX_VALUE, s = b.size();
        for (Box f : b.fb) { left = Math.min(left, f.x0()); right = Math.max(right, f.x1()); }
        int indented = 0, shortLines = 0;
        List<Double> gaps = new ArrayList<>();
        for (int k = 0; k < n; k++) {
            Box f = b.fb.get(idx.get(k));
            if (f.x0() - left >= 0.5 * s) indented++;
            if (right - f.x1() >= 2 * s) shortLines++;
            if (k > 0) gaps.add(f.y0() - b.fb.get(idx.get(k - 1)).y1());
        }
        gaps.sort(null);
        double usualGap = gaps.isEmpty() ? 0 : gaps.get(gaps.size() / 2);
        int items = 0;
        for (Line l : b.lines) if (ITEM.matcher(l.text).matches()) items++;
        // a list or notes: two lines or more start with a list's or a note's mark; a paragraph starts at a mark, and an indented line continues one
        boolean listed = items >= 2;
        boolean hanging = listed || n >= 6 && indented * 3 >= n * 2, ragged = n >= 4 && shortLines * 10 > n * 3;
        List<Block> out = new ArrayList<>();
        List<Line> cur = new ArrayList<>(); List<Box> curBoxes = new ArrayList<>();
        boolean opens = true;
        for (int k = 0; k < n; k++) {
            int i = idx.get(k);
            Line l = b.lines.get(i); Box f = b.fb.get(i);
            boolean brk = false, isOpen = false;
            if (k > 0) {
                Box pf = b.fb.get(idx.get(k - 1)); Line pl = b.lines.get(idx.get(k - 1));
                boolean ind = f.x0() - left >= 0.5 * s && !centred(f, left, right, s), prevInd = pf.x0() - left >= 0.5 * s && !centred(pf, left, right, s);
                boolean item = ITEM.matcher(l.text).matches();
                if (listed) { if (item) { brk = true; isOpen = true; } }
                else {
                    // an indented line after a full one that ends no sentence continues it (a hanging indent); after a short line or a
                    // sentence's end it opens a paragraph (a first line's indent); a line back at the margin after indented ones opens one
                    // when the indented line before it was short or ended a sentence
                    double shortBy = right - pf.x1();
                    boolean prevShort = shortBy >= (ragged ? Math.max(2 * s, 0.3 * (right - left)) : 1.5 * s), prevEnds = endsSentence(pl.text);
                    if (ind && !prevInd && (prevShort || prevEnds)) { brk = true; isOpen = true; }
                    if (!ind && prevInd && (prevShort || prevEnds) && hanging) { brk = true; isOpen = true; }
                    if (prevEnds && prevShort) brk = true;
                    if (item) brk = true;
                }
                if (f.y0() - pf.y1() > Math.max(0.6 * s, 1.7 * usualGap + 0.3 * s)) brk = true;
                // a whole line in a script that does not space its words and a whole line in one that does (a title and its English) are two
                String sa = script(pl.text), sb = script(l.text);
                if (!sa.equals(sb) && !sa.equals("mixed") && !sb.equals("mixed") && !sa.equals("none") && !sb.equals("none")) brk = true;
            } else isOpen = n > 1 && f.x0() - left >= 0.5 * s && !listed;
            if (brk && !cur.isEmpty()) { out.add(block(cur, curBoxes, page, opens, body, b)); cur = new ArrayList<>(); curBoxes = new ArrayList<>(); opens = true; }
            if (k == 0) opens = isOpen;
            cur.add(l); curBoxes.add(f);
        }
        if (!cur.isEmpty()) out.add(block(cur, curBoxes, page, opens, body, b));
        return out;
    }

    /** The kind of script a line's letters are in: cjk (Han, kana: no spaces between words), spaced (any other), mixed, or none (no letters). */
    static String script(String t) {
        int cjk = 0, spaced = 0;
        for (int i = 0; i < t.length(); ) { int c = t.codePointAt(i); i += Character.charCount(c); if (!Character.isLetter(c)) continue; if (cjk(c)) cjk++; else spaced++; }
        return cjk == 0 && spaced == 0 ? "none" : cjk == 0 ? "spaced" : spaced == 0 ? "cjk" : "mixed";
    }

    /** A line set in the middle of its block: as far in from the left as it falls short of the right, both by a size and a half or more. */
    static boolean centred(Box f, double left, double right, double s) {
        double in = f.x0() - left, shortBy = right - f.x1();
        return in >= 1.5 * s && shortBy >= 1.5 * s && Math.abs(in - shortBy) < Math.max(1.0 * s, 0.2 * Math.max(in, shortBy));
    }

    static Block block(List<Line> ls, List<Box> fbs, int page, boolean opens, double body, Blk b) {
        StringBuilder t = new StringBuilder();
        for (Line l : ls) t.replace(0, t.length(), join(t.toString(), l.text));
        String text = t.toString().strip();
        Box box = union(ls);
        double size = ls.get(0).size;
        int chars = text.codePointCount(0, text.length());
        boolean bold = ls.stream().allMatch(l -> l.bold);
        String kind = PARA; int level = 0;
        if (CAPTION_START.matcher(text).matches() && chars <= 600) kind = CAPTION;
        else if (body > 0 && size >= 1.15 * body && ls.size() <= 3 && chars <= 200) { kind = HEADING; level = size >= 1.4 * body ? 1 : 2; }
        else if (bold && ls.size() <= 2 && chars <= 120 && !endsSentence(text)) { kind = HEADING; level = 2; }
        else if (ls.size() == 1 && chars <= 80 && !endsSentence(text) && NUMBERED_HEADING.matcher(text).matches() && size >= body * 0.98 && (bold || size > 1.05 * body)) { kind = HEADING; level = 2; }
        Box fr = b.placed ? new Box(b.x0, b.y0, b.x1, b.y1) : boxes(fbs);
        return new Block(kind, text, page, box, opens, level, size, ls.size(), fr);
    }

    /**
     * Two pieces of one paragraph joined: a word hyphenated at the line's end joined whole (the hyphen kept when the part before it is
     * already hyphenated or the next part starts with a capital or a digit), nothing between two characters of a script that does not
     * space its words, a space otherwise.
     */
    public static String join(String a, String b) {
        if (a.isEmpty()) return b;
        if (b.isEmpty()) return a;
        int last = a.codePointBefore(a.length()), first = b.codePointAt(0);
        if (last == 0xAD) return a.substring(0, a.length() - 1) + b;
        if ((last == '-' || last == 0x2010) && a.length() >= 2 && Character.isLetter(a.codePointBefore(a.length() - 1))) {
            String word = a.substring(a.lastIndexOf(' ') + 1, a.length() - 1);
            if (Character.isLowerCase(first) && !word.contains("-")) return a.substring(0, a.length() - 1) + b;
            return a + b;
        }
        if (cjk(last) || cjk(first)) return a + b;
        return a + " " + b;
    }

    /**
     * A paragraph that runs on over a column or a page break joined to its start: a paragraph that ends no sentence takes the next
     * paragraph of its size (within 15%) that does not open one, when that one stands in another column or on a later page and only
     * smaller text (a footnote, a page's foot), a picture or a caption lies between them.
     */
    static List<Page> joinRuns(List<Page> pages) {
        List<List<Block>> bs = new ArrayList<>();
        for (Page p : pages) bs.add(new ArrayList<>(p.blocks()));
        for (int pi = 0; pi < bs.size(); pi++) {
            for (int bi = 0; bi < bs.get(pi).size(); bi++) {
                Block x = bs.get(pi).get(bi);
                if (!x.kind().equals(PARA) || endsSentence(x.text())) continue;
                int qp = pi, qb = bi, skipped = 0;
                while (true) {
                    qb++;
                    if (qb >= bs.get(qp).size()) { qp++; qb = 0; if (qp >= bs.size() || qp > pi + 1) { qp = -1; break; } if (bs.get(qp).isEmpty()) { qb = -1; continue; } }
                    if (qb < 0) continue;
                    Block y = bs.get(qp).get(qb);
                    boolean small = y.size() > 0 && y.size() < 0.87 * x.size();
                    if (y.kind().equals(PICTURE) || y.kind().equals(CAPTION) || y.kind().equals(FIGURE) || (y.kind().equals(PARA) && small)) { if (++skipped > 8) { qp = -1; break; } continue; }
                    break;
                }
                if (qp < 0) continue;
                Block y = bs.get(qp).get(qb);
                if (!y.kind().equals(PARA) || y.opens() || Math.max(x.size(), y.size()) > 1.15 * Math.min(x.size(), y.size()) + 0.01) continue;
                if (!pages.get(pi).direction().equals(pages.get(qp).direction())) continue;
                boolean elsewhere = qp != pi || Math.min(x.frame().x1(), y.frame().x1()) - Math.max(x.frame().x0(), y.frame().x0()) <= 0;
                if (!elsewhere) continue;
                bs.get(pi).set(bi, x.joined(y));
                bs.get(qp).remove(qb);
                bi--;   // the joined paragraph may run on again
            }
        }
        List<Page> out = new ArrayList<>();
        for (int i = 0; i < pages.size(); i++) { Page p = pages.get(i); out.add(new Page(p.number(), p.width(), p.height(), p.direction(), bs.get(i), p.stats())); }
        return out;
    }

    /** The title: on the first page with words, the largest heading in its upper half, when it is larger than the body. */
    static List<Page> title(List<Page> pages) {
        for (int i = 0; i < pages.size(); i++) {
            Page p = pages.get(i);
            if (p.blocks().stream().noneMatch(b -> !b.kind().equals(PICTURE))) continue;
            int best = -1;
            for (int k = 0; k < p.blocks().size(); k++) {
                Block b = p.blocks().get(k);
                if (!b.kind().equals(HEADING)) continue;
                boolean upper = p.direction().equals("vertical") ? b.box().x0() >= p.width() * 0.4 : b.box().y1() <= p.height() * 0.5;
                if (upper && (best < 0 || b.size() > p.blocks().get(best).size())) best = k;
            }
            if (best >= 0) {
                List<Block> bs = new ArrayList<>(p.blocks());
                bs.set(best, bs.get(best).as(TITLE, 1));
                List<Page> out = new ArrayList<>(pages);
                out.set(i, new Page(p.number(), p.width(), p.height(), p.direction(), bs, p.stats()));
                return out;
            }
            return pages;
        }
        return pages;
    }


    // ---- the measurements ----

    private static final Pattern VOWEL = Pattern.compile("(?i)[aeiouyäöüàáâèéêìíîòóôùúûåæøœ]");

    /** Whether a code point is one no text should hold: the replacement character, private use, a control, unassigned, a lone surrogate. */
    static boolean bad(int c) {
        int t = Character.getType(c);
        return c == 0xFFFD || t == Character.PRIVATE_USE || (t == Character.CONTROL && c != '\t' && c != '\n') || t == Character.UNASSIGNED || t == Character.SURROGATE;
    }

    static Stats stats(Prepared p, List<Block> blocks, List<Line> lines, int furniture, double cover, int pictures) {
        int glyphs = p.glyphs - p.leaders, invisible = p.invisible, letters = p.letters, bad = p.bad;   // a contents page's dot leaders are not counted against its letters
        int chars = 0, units = 0, fragments = 0, fragmentChars = 0, longUnits = 0, ended = 0, oneLine = 0, tokens = 0, wordLike = 0, ruby = 0, marks = 0;
        for (Line l : lines) { ruby += l.ruby; marks += l.marks; }
        for (Block b : blocks) {
            if (b.kind().equals(PICTURE) || b.kind().equals(FIGURE)) continue;
            String t = b.text();
            units++;
            int n = 0;
            for (int i = 0; i < t.length(); ) {
                int c = t.codePointAt(i); i += Character.charCount(c);
                if (Character.isWhitespace(c)) continue;
                n++;
            }
            chars += n;
            if (n <= 5) { fragments++; fragmentChars += n; }
            // the sentence ends are counted on paragraphs of prose: a heading, a caption or a list's item ends without one by right
            // and only on prose, lines of twenty characters or more on average: a table's cells stacked into a block are not prose
            if (n >= 20 && n >= 20 * Math.max(1, b.lines()) && b.kind().equals(PARA) && !ITEM.matcher(t).matches()) { longUnits++; if (endsSentence(t)) ended++; else if (b.lines() <= 1) oneLine++; }
            for (String tok : t.split("\\s+")) {
                if (tok.isEmpty() || tok.codePoints().anyMatch(PdfLayout::cjk) || tok.codePoints().anyMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HANGUL)) continue;
                tokens++;
                String w = tok.replaceAll("^[\\p{P}\\p{S}]+|[\\p{P}\\p{S}\\d]+$", "");
                if (w.isEmpty()) { if (tok.matches("[\\d.,%:/()\\-–+]+|[\\p{P}]{1,3}")) wordLike++; continue; }
                boolean letterly = w.codePoints().allMatch(c -> Character.isLetter(c) || c == '\'' || c == '’' || c == '-' || c == '.' || Character.getType(c) == Character.NON_SPACING_MARK);
                boolean latin = w.codePoints().allMatch(c -> c < 0x250 || !Character.isLetter(c));
                if (letterly && w.length() <= 24 && (!latin || w.length() < 3 || VOWEL.matcher(Normalizer.normalize(w, Normalizer.Form.NFD).replaceAll("\\p{M}", "")).find() || w.equals(w.toUpperCase(Locale.ROOT)))) wordLike++;
                else if (w.matches("\\d+([.,]\\d+)*")) wordLike++;
            }
        }
        return new Stats(glyphs, invisible, chars, letters, bad, units, fragments, fragmentChars, longUnits, ended, oneLine, tokens, wordLike, lines.size(), furniture, ruby, marks, cover, pictures);
    }
}
