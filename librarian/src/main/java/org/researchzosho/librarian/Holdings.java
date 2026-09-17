package org.researchzosho.librarian;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What the person's own lists hold: a Calibre library, an inventory, a reading list — every list shelved
 * through {@code items}. An exact lookup by the words of a title and an author, cheap enough to ask for
 * every candidate, so a run or the chat can tell owned from not owned without a model guessing from a
 * search snippet. The lists are parsed once and kept in memory until the file changes.
 */
public final class Holdings {

    /** One list entry that matched: the item, its note, and the list it is on. */
    public record Match(String item, String note, String list, String raw) { }

    private record Cached(long modified, String title, List<Items.Item> items) { }

    private static final Map<Path, Cached> CACHE = new ConcurrentHashMap<>();

    /** Query words shorter than this are not required to match: "a", "of", initials. */
    static final int WORD_CHARS = 2;

    private Holdings() { }

    /** Letters and digits only, lower case, accents removed: "Le Carré" and "le carre" are the same words. */
    static String norm(String s) {
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return n.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
    }

    static List<String> words(String q) {
        List<String> out = new ArrayList<>();
        for (String w : norm(q).split(" ")) if (w.length() >= WORD_CHARS) out.add(w);
        return out;
    }

    /** Every shelved list (lists/, kept whole), parsed, from the cache or the file. */
    static List<Map.Entry<Path, Cached>> lists(LibraryStore store) throws IOException {
        List<Map.Entry<Path, Cached>> out = new ArrayList<>();
        if (!Files.isDirectory(store.listsDir())) return out;
        try (var files = Files.list(store.listsDir())) {
            for (Path p : (Iterable<Path>) files.sorted()::iterator) {
                if (!p.getFileName().toString().endsWith(".md")) continue;
                long modified = Files.getLastModifiedTime(p).toMillis();
                Cached c = CACHE.get(p);
                if (c == null || c.modified() != modified) {
                    String text;
                    try { text = Files.readString(p, java.nio.charset.StandardCharsets.UTF_8); } catch (IOException e) { continue; }
                    String title = text.startsWith("# ") ? text.substring(2, Math.max(2, text.indexOf('\n') < 0 ? text.length() : text.indexOf('\n'))).strip() : p.getFileName().toString();
                    c = new Cached(modified, title, Items.parse(text, null, Integer.MAX_VALUE));
                    CACHE.put(p, c);
                }
                if (!c.items().isEmpty()) out.add(Map.entry(p, c));
            }
        }
        return out;
    }

    /**
     * The list entries every word of the query appears in (title words, an author's name, a year), best first:
     * an entry whose name holds all the words before one that needs its note. {@code skipRaw} leaves one list
     * out — the list being checked against the others.
     */
    public static List<Match> find(LibraryStore store, String query, int max, String skipRaw) throws IOException {
        List<String> want = words(query);
        if (want.isEmpty()) return List.of();
        List<Match> inName = new ArrayList<>(), inLine = new ArrayList<>();
        for (Map.Entry<Path, Cached> e : lists(store)) {
            String rawName = "lists/" + e.getKey().getFileName();
            if (skipRaw != null && rawName.endsWith(skipRaw)) continue;
            for (Items.Item it : e.getValue().items()) {
                String name = " " + norm(it.name()) + " ", line = " " + norm(it.line()) + " ";
                boolean allName = true, allLine = true;
                for (String w : want) {
                    if (!name.contains(" " + w + " ")) allName = false;
                    if (!line.contains(" " + w + " ")) { allLine = false; break; }
                }
                if (!allLine) continue;
                (allName ? inName : inLine).add(new Match(it.name(), it.note(), e.getValue().title(), rawName));
                if (inName.size() >= max) break;
            }
        }
        List<Match> out = new ArrayList<>(inName);
        for (Match m : inLine) { if (out.size() >= max) break; out.add(m); }
        return out.size() > max ? out.subList(0, max) : out;
    }

    /** How many entries the shelved lists hold in all; 0 when the shelves cannot be read. */
    public static int size(LibraryStore store) {
        try { int n = 0; for (Map.Entry<Path, Cached> e : lists(store)) n += e.getValue().items().size(); return n; }
        catch (IOException e) { return 0; }
    }
}
