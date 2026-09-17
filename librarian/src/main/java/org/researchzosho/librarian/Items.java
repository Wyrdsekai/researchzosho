package org.researchzosho.librarian;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A list of things — books, tools, places, compounds, an inventory of line items — as a starting point.
 * Not questions: items, and a LENS that says what the person wants to know about each one ("{item}: what
 * it is, who made it, what it is used for" unless they say). The list is shelved as it is, so the library
 * knows what the person has; each item is checked against the shelves (held or not); then each becomes a
 * question on the frontier, or the items go out as research runs in batches, a lane per item.
 *
 * <p>Reads one item per line (bullets and numbering stripped), a markdown table (first cell), or a CSV
 * (first column, or the named one). A note after " — ", " - " or a tab stays with the item.
 */
public final class Items {

    public static final String DEFAULT_LENS = "{item}: what it is, who made or wrote it, what it is for, and what is known about it";
    static final int MAX_ITEMS = org.researchzosho.Config.getInt("RESEARCHZOSHO_ITEMS_MAX", 200);
    /** Items per research run when the list goes out as runs: one lane each. */
    static final int BATCH = org.researchzosho.Config.getInt("RESEARCHZOSHO_ITEMS_BATCH", 8);

    public record Item(String name, String note) {
        public String line() { return note.isEmpty() ? name : name + " — " + note; }
    }

    private Items() { }

    static final Pattern BULLET = Pattern.compile("^\\s*(?:[-*•▪◦]|\\d+[.)]|\\[[ xX]\\])\\s*");
    static final Pattern SPLIT_NOTE = Pattern.compile("\\s+(?:—|–|-|\\||\\t)\\s+|\\t");

    /** The items in a file's text. {@code column} names a CSV column (null = the first). */
    public static List<Item> parse(String text, String column) {
        List<Item> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        String[] lines = text.split("\\r?\\n");
        boolean csv = lines.length > 1 && looksCsv(lines);
        int col = 0;
        if (csv) {
            String[] header = splitCsv(lines[0]);
            if (column != null) { for (int i = 0; i < header.length; i++) if (header[i].strip().equalsIgnoreCase(column)) col = i; }
            else for (String want : new String[]{"title", "name", "item", "book"}) {   // a header that names the thing's column picks it; else the first column
                boolean found = false;
                for (int i = 0; i < header.length; i++) if (header[i].strip().equalsIgnoreCase(want)) { col = i; found = true; break; }
                if (found) break;
            }
        }
        boolean first = true;
        for (String raw : lines) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#") || line.matches("^[-|:\\s]+$")) continue;   // headings, table rules
            String name, note = "";
            if (csv) {
                String[] cells = splitCsv(line);
                if (first) { first = false; if (looksHeader(cells)) continue; }
                if (col >= cells.length) continue;
                name = cells[col].strip();
                if (cells.length > 1) { StringBuilder sb = new StringBuilder(); for (int i = 0; i < cells.length; i++) if (i != col && !cells[i].isBlank()) { if (sb.length() > 0) sb.append(", "); sb.append(cells[i].strip()); } note = sb.toString(); }
            } else if (line.startsWith("|")) {
                String[] cells = line.substring(1).split("\\|");
                if (cells.length == 0) continue;
                name = cells[0].strip();
                if (first) { first = false; if (cells.length > 1 && looksHeader(cells)) continue; }
                StringBuilder sb = new StringBuilder(); for (int i = 1; i < cells.length; i++) if (!cells[i].isBlank()) { if (sb.length() > 0) sb.append(", "); sb.append(cells[i].strip()); } note = sb.toString();
            } else {
                String s = BULLET.matcher(line).replaceFirst("");
                String[] parts = SPLIT_NOTE.split(s, 2);
                name = parts[0].strip(); note = parts.length > 1 ? parts[1].strip() : "";
            }
            name = name.replaceAll("^[\"'“]+|[\"'”]+$", "").strip();
            if (name.length() < 2 || name.length() > 200) continue;
            if (!seen.add(name.toLowerCase(Locale.ROOT))) continue;
            out.add(new Item(name, note));
            if (out.size() >= MAX_ITEMS) break;
        }
        return out;
    }

    static boolean looksCsv(String[] lines) {
        int withComma = 0, n = 0;
        for (String l : lines) { if (l.isBlank()) continue; n++; if (l.contains(",") && !l.strip().startsWith("|") && !BULLET.matcher(l).find()) withComma++; if (n >= 10) break; }
        return n > 0 && withComma == n;
    }

    static boolean looksHeader(String[] cells) {
        for (String c : cells) { String s = c.strip().toLowerCase(Locale.ROOT); if (s.matches("(title|name|item|book|author|isbn|year|note|notes|qty|quantity|count|description|url|link|category|type)")) return true; }
        return false;
    }

    static String[] splitCsv(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder(); boolean q = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') { if (q && i + 1 < line.length() && line.charAt(i + 1) == '"') { cur.append('"'); i++; } else q = !q; }
            else if (c == ',' && !q) { out.add(cur.toString()); cur.setLength(0); }
            else cur.append(c);
        }
        out.add(cur.toString());
        return out.toArray(new String[0]);
    }

    /** The question for one item through the lens: {item} replaced, the note appended when there is one. */
    public static String question(Item it, String lens) {
        String l = lens == null || lens.isBlank() ? DEFAULT_LENS : lens.strip();
        String q = l.contains("{item}") ? l.replace("{item}", it.name()) : it.name() + ": " + l;
        if (!it.note().isEmpty()) q += " (" + it.note() + ")";
        return q;
    }

    /** The list on the shelves as it is: the library knows what the person has. */
    public static Path shelve(LibraryStore store, String title, List<Item> items, String source, String collection) {
        StringBuilder sb = new StringBuilder("# ").append(title).append("\n\n");
        for (Item it : items) sb.append("- ").append(it.line()).append('\n');
        String body = sb.toString();
        String locator = "list://" + HexFormat.of().formatHex(sha(body), 0, 8);
        return RawCapture.capture(store, locator, body, title, "list:" + Acquisitions.compress(source, 120), collection);
    }

    /** What the shelves hold on an item: the best hit that names it, or null. Cheap, mechanical, before any model runs. */
    public static LibrarianIndex.Hit held(LibrarianIndex index, Item it, String skipRaw) {
        try {
            for (LibrarianIndex.Hit h : index.search(it.name(), 3)) {
                if (h.id().equals(skipRaw)) continue;   // not the list itself
                String hay = (h.title() + " " + h.snippet()).toLowerCase(Locale.ROOT);
                if (hay.contains(it.name().toLowerCase(Locale.ROOT))) return h;
            }
        } catch (Exception ignored) { }
        return null;
    }

    /** One item's question onto the frontier as the person's, unless an open question already covers it. Returns false when held. */
    public static boolean file(LibraryStore store, List<Frontier.Line> open, String question, String who, String listTitle) throws IOException {
        for (Frontier.Line l : open) if (Frontier.sameQuestion(l.text(), question) || Frontier.jaccard(Frontier.terms(question), Frontier.terms(l.text())) >= 0.6) return false;
        store.frontier("person " + who + " (from a list: " + Acquisitions.compress(listTitle, 60) + ")", question);
        return true;
    }

    private static byte[] sha(String s) {
        try { return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
}
