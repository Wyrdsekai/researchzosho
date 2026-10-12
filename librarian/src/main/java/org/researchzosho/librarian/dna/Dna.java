package org.researchzosho.librarian.dna;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.researchzosho.Config;
import org.researchzosho.librarian.AtomicWrite;
import org.researchzosho.librarian.Shelf;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The DNA store (0.6.0): one document per work, under {@code ~/.researchzosho/dna/<kind>s/<key>.json}, holding the three layers — the
 * record (what the catalogues and the shelf say, each with its source), the measured (counted from the text by a program, with its
 * version), the labelled (the model on windows of the text, with its prompt version and the model's name). A work is keyed by the hash of
 * its file, so the same book in two libraries is one document and a changed file is a new one; a layer is current when its version is
 * the code's, else it is made again. An index of the keys with a labelled layer keeps "like X" from reading every document on a shelf
 * of seventy thousand. The documents are about the work, not the person: they travel in the shared pool's packs and the reactions and
 * preferences stay elsewhere (docs/REC_DNA.md).
 */
public final class Dna {
    private Dna() { }

    static final ObjectMapper M = new ObjectMapper();
    public static final String BOOK = "book", PODCAST = "podcast";

    /** A test's own folder. */
    static volatile Path override;
    /** Points the store at a folder (a test's own); null for the person's. Forgets what the shelf glue remembered. */
    public static void useDir(Path dir) { override = dir; ShelfDna.forget(); }
    public static Path dir() { return override != null ? override : Config.home().resolve("dna"); }
    public static Path kindDir(String kind) { return dir().resolve(kind + "s"); }
    public static Path file(String kind, String key) { return kindDir(kind).resolve(key + ".json"); }
    static Path index(String kind) { return kindDir(kind).resolve("labelled.txt"); }

    /** The key of a file: the first sixteen hex digits of its SHA-256. */
    public static String key(Path file) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file)) { byte[] buf = new byte[1 << 16]; int n; while ((n = in.read(buf)) > 0) md.update(buf, 0, n); }
            StringBuilder sb = new StringBuilder(); for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return sb.substring(0, 16);
        } catch (java.security.NoSuchAlgorithmException e) { throw new IOException(e); }
    }

    /** The document, or null when the store has none. */
    public static ObjectNode read(String kind, String key) {
        Path f = file(kind, key);
        if (!Files.isRegularFile(f)) return null;
        try { JsonNode n = M.readTree(Files.readString(f, StandardCharsets.UTF_8)); return n.isObject() ? (ObjectNode) n : null; } catch (Exception e) { return null; }
    }

    /** A new document with its record layer. */
    public static ObjectNode fresh(String kind, String key, ObjectNode record) {
        ObjectNode d = M.createObjectNode();
        d.put("key", key); d.put("kind", kind); d.put("made", Instant.now().toString());
        d.set("record", record == null ? M.createObjectNode() : record);
        return d;
    }

    /** Writes the document, whole, and keeps the labelled index in step. */
    public static synchronized void write(ObjectNode doc) throws IOException {
        String kind = doc.path("kind").asText(BOOK), key = doc.path("key").asText();
        if (key.isEmpty()) throw new IOException("a DNA document needs its key");
        Files.createDirectories(kindDir(kind));
        AtomicWrite.text(file(kind, key), M.writerWithDefaultPrettyPrinter().writeValueAsString(doc));
        boolean labelled = labelledCurrent(doc);
        Set<String> idx = indexKeys(kind);
        if (labelled ? idx.add(key) : idx.remove(key)) AtomicWrite.text(index(kind), String.join("\n", idx) + (idx.isEmpty() ? "" : "\n"));
    }

    static Set<String> indexKeys(String kind) {
        Set<String> out = new LinkedHashSet<>();
        Path f = index(kind);
        if (Files.isRegularFile(f)) { try { for (String l : Files.readAllLines(f, StandardCharsets.UTF_8)) if (!l.isBlank()) out.add(l.strip()); } catch (IOException ignored) { } }
        return out;
    }

    /** Whether the measured layer is there and of the code's version. */
    public static boolean measuredCurrent(ObjectNode doc) { return doc != null && Measured.VERSION.equals(doc.path("measured").path("version").asText("")) && doc.path("measured").has("features"); }

    /** Whether the labelled layer is there, of the code's version, with at least one window read. */
    public static boolean labelledCurrent(ObjectNode doc) { return doc != null && Labels.VERSION.equals(doc.path("labelled").path("version").asText("")) && doc.path("labelled").path("windows").asInt(0) > 0; }

    /** Every document with a current labelled layer, by the index; a stale index line is dropped as it is met. */
    public static List<ObjectNode> labelled(String kind) {
        List<ObjectNode> out = new ArrayList<>();
        Set<String> idx = indexKeys(kind); boolean changed = false;
        for (String key : new ArrayList<>(idx)) {
            ObjectNode d = read(kind, key);
            if (labelledCurrent(d)) out.add(d); else { idx.remove(key); changed = true; }
        }
        if (changed) { try { AtomicWrite.text(index(kind), String.join("\n", idx) + (idx.isEmpty() ? "" : "\n")); } catch (IOException ignored) { } }
        return out;
    }

    /** How many documents of the kind the store holds, and how many of them are labelled. */
    public static int[] counts(String kind) {
        int all = 0;
        Path d = kindDir(kind);
        if (Files.isDirectory(d)) { try (Stream<Path> s = Files.list(d)) { all = (int) s.filter(p -> p.getFileName().toString().endsWith(".json")).count(); } catch (IOException ignored) { } }
        return new int[]{all, indexKeys(kind).size()};
    }

    /** Every document of the kind (a read of every file; for the measured-only uses). */
    public static List<ObjectNode> all(String kind) {
        List<ObjectNode> out = new ArrayList<>();
        Path d = kindDir(kind);
        if (!Files.isDirectory(d)) return out;
        try (Stream<Path> s = Files.list(d)) {
            for (Path p : s.filter(x -> x.getFileName().toString().endsWith(".json")).sorted().toList()) {
                try { JsonNode n = M.readTree(Files.readString(p, StandardCharsets.UTF_8)); if (n.isObject()) out.add((ObjectNode) n); } catch (Exception ignored) { }
            }
        } catch (IOException ignored) { }
        return out;
    }

    /** The labelled document whose record has this title (and this author, when given), or null: a row of a list found again by what the person typed. */
    public static ObjectNode byTitle(String kind, String title, String author) {
        String want = Shelf.norm(title), wantShort = Shelf.shortTitle(title);
        if (want.isEmpty()) return null;
        ObjectNode loose = null;
        for (ObjectNode d : labelled(kind)) {
            String t = Shelf.norm(d.path("record").path("title").asText(""));
            if (!t.equals(want) && !Shelf.shortTitle(d.path("record").path("title").asText("")).equals(wantShort)) continue;
            if (author == null || author.isBlank() || LikeX.sameAuthor(author, d.path("record").path("authors").asText(""))) return d;
            if (loose == null) loose = d;
        }
        return loose;
    }

    /** The keys the store holds for the kind. */
    public static Set<String> keys(String kind) {
        Set<String> out = new HashSet<>();
        Path d = kindDir(kind);
        if (!Files.isDirectory(d)) return out;
        try (Stream<Path> s = Files.list(d)) { s.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".json")).forEach(n -> out.add(n.substring(0, n.length() - 5))); } catch (IOException ignored) { }
        return out;
    }
}
