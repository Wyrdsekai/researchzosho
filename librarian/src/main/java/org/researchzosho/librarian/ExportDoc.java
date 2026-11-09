package org.researchzosho.librarian;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A document to export (0.7.0): a report or a dossier as its page shows it, as blocks the PDF and the Word writers lay out ({@link
 * ExportPdf}, {@link ExportDocx}). It is read from the Markdown the library writes, by the rules its pages read it with ({@link Pages#md}):
 * headings, paragraphs whose lines run together, lists (bulleted, numbered, and a source's own line, "[12] …"), tables, code and rules; and
 * within a line bold, italic, code and links: [label](address), a bare address, and a source's number [12], which links to that source's
 * address when the document knows it. An address that is not on the web (a page of this library, a file) stays as its words.
 */
final class ExportDoc {

    /** A piece of a line in one style; {@code href} the address it links to, or null. */
    record Span(String text, boolean bold, boolean italic, boolean code, String href) {
        static Span plain(String t) { return new Span(t, false, false, false, null); }
        static Span bold(String t) { return new Span(t, true, false, false, null); }
    }

    sealed interface Block permits Heading, Para, Item, Table, Code, Rule, Image { }

    /**
     * A picture on a line of its own, {@code ![alt](src)}: its words, its address, its bytes when the library holds it (a kept picture
     * at {@code /images/<file>}; null for one on the web, which an export does not fetch), and the page it came from when known, for the
     * caption a writer leaves when it cannot show the picture.
     */
    record Image(String alt, String src, byte[] bytes, String page) implements Block {
        /** The caption under the picture, or in its place: the words, then where it is from. */
        String caption() { String from = page != null && !page.isBlank() ? page : src != null && src.matches("(?i)^https?://.+") ? src : ""; return (alt == null || alt.isBlank() ? "Picture" : alt) + (from.isEmpty() ? "" : " — " + from); }
    }

    record Heading(int level, List<Span> spans) implements Block { }

    /** A paragraph; {@code small}: a line of facts under a title, set smaller. */
    record Para(List<Span> spans, boolean small) implements Block { }

    enum Mark { BULLET, NUMBER, SOURCE }

    /** One line of a list: its depth from 0, its mark (a bullet, its number as written, or a source's number in brackets), its text. */
    record Item(int depth, Mark mark, int number, List<Span> spans) implements Block { }

    /** A table: its rows of cells, each cell its spans; the first row is the header when the Markdown said so. */
    record Table(List<List<List<Span>>> rows, boolean header) implements Block { }

    record Code(List<String> lines) implements Block { }

    record Rule() implements Block { }

    final String title, lang, footer;
    final List<Block> blocks;

    ExportDoc(String title, String lang, String footer, List<Block> blocks) {
        this.title = title == null ? "" : title;
        this.lang = lang == null || lang.isBlank() ? "en" : lang;
        this.footer = footer == null ? "" : footer;
        this.blocks = List.copyOf(blocks);
    }

    /** The document read from Markdown; {@code cites} maps a source's number to its address, for the [n] in the text (may be empty). */
    static ExportDoc of(String title, String lang, String footer, String markdown, Map<Integer, String> cites) {
        return new ExportDoc(title, lang, footer, parse(markdown, cites, null));
    }

    /** The same, with {@code pictures} giving a picture's bytes and page by its address, for the pictures the writers embed. */
    static ExportDoc of(String title, String lang, String footer, String markdown, Map<Integer, String> cites, Function<String, Image> pictures) {
        return new ExportDoc(title, lang, footer, parse(markdown, cites, pictures));
    }

    /** The same document with a block put in after its title (the first heading), or first when it has none. */
    ExportDoc withAfterTitle(Block b) {
        List<Block> out = new ArrayList<>(blocks);
        int at = !out.isEmpty() && out.get(0) instanceof Heading ? 1 : 0;
        out.add(at, b);
        return new ExportDoc(title, lang, footer, out);
    }

    /** Every character the document shows, the footer's too: what the fonts must cover. */
    String text() {
        StringBuilder b = new StringBuilder(footer).append('\n');
        for (Block x : blocks) {
            switch (x) {
                case Heading h -> spans(b, h.spans());
                case Para p -> spans(b, p.spans());
                case Item i -> { b.append(i.mark() == Mark.SOURCE ? "[" + i.number() + "] " : i.mark() == Mark.NUMBER ? i.number() + ". " : ""); spans(b, i.spans()); }
                case Table t -> { for (List<List<Span>> row : t.rows()) for (List<Span> cell : row) spans(b, cell); }
                case Code c -> { for (String l : c.lines()) b.append(l).append('\n'); }
                case Rule r -> { }
                case Image i -> b.append(i.caption());
            }
            b.append('\n');
        }
        return b.toString();
    }

    private static void spans(StringBuilder b, List<Span> spans) { for (Span s : spans) b.append(s.text()); }

    /** The text of some spans, run together. */
    static String plain(List<Span> spans) { StringBuilder b = new StringBuilder(); spans(b, spans); return b.toString(); }

    // ---- reading Markdown ----

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*?)\\s*#*\\s*$");
    private static final Pattern BULLET = Pattern.compile("^(\\s*)[-*•+]\\s+(.*)$");
    private static final Pattern NUMBERED = Pattern.compile("^(\\s*)(\\d{1,5})[.)]\\s+(.*)$");
    private static final Pattern SOURCE = Pattern.compile("^\\[(\\d{1,5})\\]\\s+(.*)$");
    private static final Pattern TABLE_SEP = Pattern.compile("\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?");
    private static final Pattern RULE = Pattern.compile("^(?:-\\s*){3,}$|^(?:\\*\\s*){3,}$|^(?:_\\s*){3,}$");
    private static final Pattern SOURCE_LINE = Pattern.compile("^(?:https?|file)://\\S+(?:\\s*(?:\\(.*\\)|—.*))?$");
    private static final Pattern IMAGE_LINE = Pattern.compile("^!\\[([^\\]]*)\\]\\(([^)\\s]+)(?:\\s+\"[^\"]*\")?\\)$");

    /** The blocks of a Markdown text. */
    static List<Block> parse(String markdown, Map<Integer, String> cites) { return parse(markdown, cites, null); }

    /** The same; {@code pictures} gives a picture's bytes and page by its address, null for none (the picture is then its caption). */
    static List<Block> parse(String markdown, Map<Integer, String> cites, Function<String, Image> pictures) {
        Map<Integer, String> c = cites == null ? Map.of() : cites;
        List<Block> out = new ArrayList<>();
        String text = Normalizer.normalize(Pages.unentity(markdown == null ? "" : markdown), Normalizer.Form.NFC).replace("\r\n", "\n").replace('\r', '\n');
        StringBuilder para = new StringBuilder();
        List<List<List<Span>>> rows = null;
        boolean header = false;
        List<String> code = null;
        List<Integer> indents = new ArrayList<>();   // the open list's indentations, one per depth
        int lastItem = -1;   // the list line a following indented line continues, or -1
        for (String raw : text.split("\n", -1)) {
            String line = raw.stripTrailing(), t = line.strip();
            if (code != null) {
                if (t.startsWith("```")) { out.add(new Code(code)); code = null; }
                else code.add(raw.replace("\t", "    "));
                continue;
            }
            if (t.startsWith("```")) { flush(out, para, c); if (rows != null) { out.add(new Table(rows, header)); rows = null; } code = new ArrayList<>(); lastItem = -1; continue; }
            if (t.length() > 1 && t.startsWith("|") && t.endsWith("|")) {   // a table: rows of | cells |; the |---| line under the first row makes it the header
                flush(out, para, c); lastItem = -1;
                if (TABLE_SEP.matcher(t).matches()) { if (rows != null && rows.size() == 1) header = true; continue; }
                if (rows == null) { rows = new ArrayList<>(); header = false; }
                List<List<Span>> cells = new ArrayList<>();
                for (String cell : t.substring(1, t.length() - 1).split("(?<!\\\\)\\|", -1)) cells.add(inline(cell.strip().replace("\\|", "|"), c));
                rows.add(cells);
                continue;
            } else if (rows != null) { out.add(new Table(rows, header)); rows = null; }
            if (t.isEmpty()) { flush(out, para, c); lastItem = -1; continue; }
            Matcher h = HEADING.matcher(line);
            if (line.startsWith("#") && h.matches()) { flush(out, para, c); indents.clear(); lastItem = -1; out.add(new Heading(h.group(1).length(), inline(h.group(2), c))); continue; }
            if (RULE.matcher(t).matches()) { flush(out, para, c); indents.clear(); lastItem = -1; out.add(new Rule()); continue; }
            Matcher img = IMAGE_LINE.matcher(t);
            if (img.matches()) {   // a picture on a line of its own: the library's copy when it holds one, else its caption
                flush(out, para, c); indents.clear(); lastItem = -1;
                Image i = pictures == null ? null : pictures.apply(img.group(2));
                out.add(i == null ? new Image(img.group(1), img.group(2), null, null) : new Image(img.group(1).isBlank() ? i.alt() : img.group(1), img.group(2), i.bytes(), i.page()));
                continue;
            }
            Matcher src = SOURCE.matcher(t);
            if (src.matches()) {   // a source's own line, its number hanging: "[12] Title — https://…"
                flush(out, para, c); indents.clear();
                out.add(new Item(0, Mark.SOURCE, Integer.parseInt(src.group(1)), inline(src.group(2), c)));
                lastItem = out.size() - 1;
                continue;
            }
            Matcher b = BULLET.matcher(line), n = NUMBERED.matcher(line);
            boolean bullet = b.matches() && !RULE.matcher(t).matches(), numbered = !bullet && n.matches();
            if (bullet || numbered) {
                flush(out, para, c);
                int indent = (bullet ? b.group(1) : n.group(1)).replace("\t", "    ").length();
                while (!indents.isEmpty() && indent < indents.get(indents.size() - 1)) indents.remove(indents.size() - 1);
                if (indents.isEmpty() || indent > indents.get(indents.size() - 1)) indents.add(indent);
                int depth = Math.min(3, indents.size() - 1);
                out.add(bullet ? new Item(depth, Mark.BULLET, 0, inline(b.group(2), c)) : new Item(depth, Mark.NUMBER, Integer.parseInt(n.group(2)), inline(n.group(3), c)));
                lastItem = out.size() - 1;
                continue;
            }
            if (lastItem >= 0 && raw.startsWith(" ")) {   // a line indented under a list line continues it
                Item it = (Item) out.get(lastItem);
                List<Span> more = new ArrayList<>(it.spans()); more.add(Span.plain(" ")); more.addAll(inline(t, c));
                out.set(lastItem, new Item(it.depth(), it.mark(), it.number(), more));
                continue;
            }
            lastItem = -1;
            if (SOURCE_LINE.matcher(t).matches()) { flush(out, para, c); out.add(new Para(inline(t, c), false)); continue; }   // an address on its own line stays on its own line
            if (t.startsWith(">")) t = t.replaceFirst("^>\\s?", "");
            indents.clear();
            if (para.length() > 0) para.append(' ');
            para.append(t);
        }
        flush(out, para, c);
        if (rows != null) out.add(new Table(rows, header));
        if (code != null) out.add(new Code(code));
        return out;
    }

    private static void flush(List<Block> out, StringBuilder para, Map<Integer, String> cites) {
        if (para.length() == 0) return;
        out.add(new Para(inline(para.toString(), cites), false));
        para.setLength(0);
    }

    // within a line: links first, then bare addresses and a source's number, then code, bold and italic
    private static final Pattern INLINE = Pattern.compile(
            "\\[([^\\]\\n]+)\\]\\(([^)\\s]+)(?:\\s+\"[^\"]*\")?\\)"            // 1, 2: [label](address)
            + "|<((?:https?|mailto):[^>\\s]+)>"                              // 3: <https://…>
            + "|\\[\\[([^\\]|]+)(?:\\|([^\\]]+))?\\]\\]"                     // 4, 5: [[an id|its label]]
            + "|((?:https?)://[^\\s<>\"\\]\\[]+)"                            // 6: a bare address
            + "|\\[(\\d{1,5})\\]"                                            // 7: a source's number
            + "|`([^`]+)`"                                                   // 8: code
            + "|\\*\\*(.+?)\\*\\*|__(.+?)__"                                 // 9, 10: bold
            + "|(?<![*\\w])\\*(?![\\s*])(.+?)(?<![\\s*])\\*(?![*\\w])"       // 11: italic
            + "|(?<![_\\w])_(?![\\s_])(.+?)(?<![\\s_])_(?![_\\w])");          // 12: italic
    private static final Pattern ESCAPED = Pattern.compile("\\\\([*_\\[\\]|#`\\\\])");

    /** A line's spans. */
    static List<Span> inline(String text, Map<Integer, String> cites) {
        // an escaped mark stands for itself: hidden from the patterns in the private-use area, given back in the spans
        Matcher e = ESCAPED.matcher(text == null ? "" : text);
        StringBuilder hidden = new StringBuilder();
        while (e.find()) e.appendReplacement(hidden, Matcher.quoteReplacement(String.valueOf((char) (0xE000 + e.group(1).charAt(0)))));
        e.appendTail(hidden);
        List<Span> out = new ArrayList<>();
        inline(hidden.toString(), cites == null ? Map.of() : cites, false, false, out);
        List<Span> shown = new ArrayList<>();
        for (Span s : out) shown.add(new Span(unhide(s.text()), s.bold(), s.italic(), s.code(), s.href()));
        return merge(shown);
    }

    private static String unhide(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) { char ch = s.charAt(i); b.append(ch >= 0xE000 && ch < 0xE080 ? (char) (ch - 0xE000) : ch); }
        return b.toString();
    }

    private static void inline(String s, Map<Integer, String> cites, boolean bold, boolean italic, List<Span> out) {
        Matcher m = INLINE.matcher(s).useTransparentBounds(true).useAnchoringBounds(false);
        int at = 0;
        while (m.find()) {
            if (m.start() > at) out.add(new Span(s.substring(at, m.start()), bold, italic, false, null));
            int next = m.end();
            if (m.group(1) != null) {   // [label](address): a link when the address is on the web, else the label
                String href = web(m.group(2));
                List<Span> label = new ArrayList<>();
                inline(m.group(1), Map.of(), bold, italic, label);
                for (Span l : label) out.add(new Span(l.text(), l.bold(), l.italic(), l.code(), href != null ? href : l.href()));
            } else if (m.group(3) != null) out.add(new Span(m.group(3), bold, italic, false, web(m.group(3))));
            else if (m.group(4) != null) out.add(new Span(m.group(5) != null ? m.group(5) : m.group(4), bold, italic, false, null));
            else if (m.group(6) != null) {   // a bare address, its closing punctuation left out of it
                String url = m.group(6), tail = "";
                while (!url.isEmpty() && ".,;:!?'\")".indexOf(url.charAt(url.length() - 1)) >= 0) {
                    char last = url.charAt(url.length() - 1);
                    if (last == ')' && count(url, '(') >= count(url, ')')) break;   // Foo_(bar): the parenthesis is the address's
                    tail = last + tail; url = url.substring(0, url.length() - 1);
                }
                out.add(new Span(url, bold, italic, false, web(url)));
                next = m.end() - tail.length();
            } else if (m.group(7) != null) out.add(new Span(m.group(0), bold, italic, false, web(cites.get(Integer.parseInt(m.group(7))))));
            else if (m.group(8) != null) out.add(new Span(m.group(8), bold, italic, true, null));
            else if (m.group(9) != null || m.group(10) != null) inline(m.group(9) != null ? m.group(9) : m.group(10), cites, true, italic, out);
            else inline(m.group(11) != null ? m.group(11) : m.group(12), cites, bold, true, out);
            at = next;
            if (next != m.end()) m.region(next, s.length());
        }
        if (at < s.length()) out.add(new Span(s.substring(at), bold, italic, false, null));
    }

    private static int count(String s, char c) { int n = 0; for (int i = 0; i < s.length(); i++) if (s.charAt(i) == c) n++; return n; }

    /** The address when a reader can open it from a file: on the web, or mail; null otherwise. */
    static String web(String href) {
        if (href == null) return null;
        String h = href.strip();
        return h.matches("(?i)^(https?://|mailto:)\\S+$") ? h : null;
    }

    /** Neighbouring spans of one style as one. */
    private static List<Span> merge(List<Span> in) {
        List<Span> out = new ArrayList<>();
        for (Span s : in) {
            if (s.text().isEmpty()) continue;
            Span last = out.isEmpty() ? null : out.get(out.size() - 1);
            if (last != null && last.bold() == s.bold() && last.italic() == s.italic() && last.code() == s.code() && Objects.equals(last.href(), s.href()))
                out.set(out.size() - 1, new Span(last.text() + s.text(), s.bold(), s.italic(), s.code(), s.href()));
            else out.add(s);
        }
        return out;
    }
}
