package org.researchzosho.librarian;

import org.researchzosho.tools.DocText;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * A person's own documents as a corpus: a folder of PDFs, office files, EPUBs, markdown and text,
 * shelved as raw captures under a named COLLECTION so a question can be put to them first — or to
 * them only. Every file is a {@code file://} capture with the personal tier's trust, its collection in
 * the frontmatter and in the index, so the shelf search can be scoped to it. Registered folders are
 * rescanned by the nightly crews; a file already captured with the same text is skipped.
 */
public final class Corpus {

    static final java.util.Set<String> EXTENSIONS = java.util.Set.of("pdf", "docx", "pptx", "odt", "epub", "md", "txt", "html", "htm", "rst", "tex");

    public record Outcome(int seen, int added, int unchanged, int skipped, List<String> problems) { }

    private Corpus() { }

    /** Shelve every document under {@code dir} into {@code collection}. Idempotent. */
    public static Outcome addFolder(LibraryStore store, Path dir, String collection, boolean recursive) throws IOException {
        List<String> problems = new ArrayList<>();
        int seen = 0, added = 0, unchanged = 0, skipped = 0;
        if (!Files.isDirectory(dir)) throw new IOException(dir + " is not a directory");
        List<Path> files;
        try (Stream<Path> s = recursive ? Files.walk(dir) : Files.list(dir)) {
            files = s.filter(Files::isRegularFile).filter(p -> EXTENSIONS.contains(ext(p))).sorted().toList();
        }
        for (Path f : files) {
            seen++;
            try {
                byte[] bytes = Files.readAllBytes(f);
                if (bytes.length > 60_000_000) { skipped++; problems.add(f.getFileName() + ": larger than 60 MB, skipped"); continue; }
                DocText.Doc doc = DocText.convert(bytes, f.getFileName().toString());
                if (doc.text().isBlank() || doc.kind().startsWith("pdf-unreadable")) { skipped++; problems.add(f.getFileName() + ": no text (" + doc.kind() + ")"); continue; }
                String locator = "file://" + f.toAbsolutePath().normalize();
                Path have = RawCapture.find(store, locator);
                if (have != null && RawCapture.read(have)[2].strip().equals(doc.text().strip())) { unchanged++; continue; }
                String title = doc.title().isBlank() ? f.getFileName().toString().replaceAll("\\.[A-Za-z0-9]+$", "").replace('_', ' ') : doc.title();
                Path p = RawCapture.capture(store, locator, doc.text(), title, "corpus:" + collection, collection);
                if (p == null) { skipped++; problems.add(f.getFileName() + ": not captured"); continue; }
                added++;
            } catch (Exception e) {
                skipped++; problems.add(f.getFileName() + ": " + e.getMessage());
            }
        }
        store.circulate("corpus", collection + ": " + added + " added, " + unchanged + " unchanged, " + skipped + " skipped of " + seen + " from " + dir);
        return new Outcome(seen, added, unchanged, skipped, problems);
    }

    static String ext(Path p) {
        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
        int i = n.lastIndexOf('.');
        return i < 0 ? "" : n.substring(i + 1);
    }

    /** A collection name from a folder name: lowercase, hyphens. */
    public static String nameFor(Path dir) {
        String n = dir.toAbsolutePath().normalize().getFileName().toString().toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", "-").replaceAll("^-|-$", "");
        return n.isEmpty() ? "corpus" : n;
    }

    // ---- registered folders, rescanned nightly ----

    static Path registry(LibraryStore store) { return store.root().resolve("catalog").resolve("collections.md"); }

    public static Map<String, Path> registered(LibraryStore store) throws IOException {
        Map<String, Path> out = new LinkedHashMap<>();
        Path f = registry(store);
        if (!Files.exists(f)) return out;
        for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
            if (!line.startsWith("- ")) continue;
            String[] p = line.substring(2).split(" — ", 2);
            if (p.length == 2) out.put(p[0].strip(), Path.of(p[1].strip()));
        }
        return out;
    }

    public static void register(LibraryStore store, String name, Path dir) throws IOException {
        Map<String, Path> all = registered(store);
        all.put(name, dir.toAbsolutePath().normalize());
        Files.createDirectories(registry(store).getParent());
        StringBuilder sb = new StringBuilder("# Collections — the person's own corpora, rescanned by the crews\n\nOne per line: `- <name> — <folder>`.\n\n");
        for (Map.Entry<String, Path> e : all.entrySet()) sb.append("- ").append(e.getKey()).append(" — ").append(e.getValue()).append('\n');
        Files.writeString(registry(store), sb.toString(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    /** Rescan every registered folder; the crews' step. */
    public static String rescan(LibraryStore store) throws IOException {
        Map<String, Path> all = registered(store);
        if (all.isEmpty()) return "no collections registered";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Path> e : all.entrySet()) {
            if (!Files.isDirectory(e.getValue())) { sb.append(e.getKey()).append(": folder missing; "); continue; }
            Outcome o = addFolder(store, e.getValue(), e.getKey(), true);
            sb.append(e.getKey()).append(": ").append(o.added()).append(" added, ").append(o.unchanged()).append(" unchanged; ");
        }
        return sb.toString().strip();
    }
}
