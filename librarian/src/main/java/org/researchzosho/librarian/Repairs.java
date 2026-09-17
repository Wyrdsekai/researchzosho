package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * What an update owes the library: things an earlier version saved wrongly are fixed by the new one,
 * without being asked. It runs once per version when the service starts, and by hand as
 * {@code researchzosho repair}.
 *
 * <p>Today it knows one kind of damage: a saved page that is unreadable binary, from a version that
 * could not read that kind of file (a Calibre database, a PDF). When the file it came from is still on
 * disk, it is read again with this version: a Calibre database becomes a list, any other document
 * becomes readable text, and the unreadable copy is removed. When the file is gone, the page is left
 * alone and counted, so the person knows.
 */
public final class Repairs {

    private static final ObjectMapper M = new ObjectMapper();

    /** What a repair pass did. */
    public record Outcome(int looked, List<String> repaired, List<String> leftAlone) {
        public String summary() {
            if (repaired.isEmpty() && leftAlone.isEmpty()) return "nothing to repair (" + looked + " saved pages looked at)";
            return repaired.size() + " repaired" + (leftAlone.isEmpty() ? "" : ", " + leftAlone.size() + " unreadable and left alone because the original file is gone");
        }
    }

    private Repairs() { }

    static Path marker(LibraryStore store) { return store.root().resolve("catalog").resolve("repairs.md"); }

    /** Whether this version has already repaired this library. */
    public static boolean doneFor(LibraryStore store, String version) {
        try { return Files.exists(marker(store)) && Files.readString(marker(store), StandardCharsets.UTF_8).contains("version: " + version + "\n"); } catch (IOException e) { return false; }
    }

    /** Run once per version: the service calls this when it starts. */
    public static Outcome onceFor(LibraryStore store, String version) throws IOException {
        if (doneFor(store, version)) return null;
        Outcome o = run(store);
        Files.createDirectories(marker(store).getParent());
        Files.writeString(marker(store), "# Repairs\n\nversion: " + version + "\nat: " + java.time.Instant.now() + "\nresult: " + o.summary() + "\n", StandardCharsets.UTF_8);
        return o;
    }

    /** The unreadable saved pages, by file name. */
    public static List<Path> unreadable(LibraryStore store) throws IOException {
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(store.rawDir())) return out;
        try (var files = Files.list(store.rawDir())) {
            for (Path p : files.filter(x -> x.getFileName().toString().endsWith(".md")).sorted().toList()) {
                try { if (RawCapture.linkedFile(p) == null && RawCapture.looksBinary(RawCapture.read(p)[2])) out.add(p); } catch (Exception ignored) { }
            }
        }
        return out;
    }

    /** Repair now. */
    public static Outcome run(LibraryStore store) throws IOException {
        List<String> repaired = new ArrayList<>(), left = new ArrayList<>();
        int looked = 0;
        if (Files.isDirectory(store.rawDir())) try (var files = Files.list(store.rawDir())) { looked = (int) files.filter(x -> x.getFileName().toString().endsWith(".md")).count(); }
        LibraryProtocol protocol = new LibraryProtocol(store);
        for (Path bad : unreadable(store)) {
            String locator = RawCapture.read(bad)[0];
            Path source = localFile(locator);
            if (source == null || !Files.isRegularFile(source)) { left.add(bad.getFileName() + " (" + locator + ")"); continue; }
            try {
                ObjectNode a = M.createObjectNode().put("path", source.toString());
                a.putObject("patron").put("did", "person").put("name", "repair").put("runtime", "local");
                if (LibraryProtocol.isCalibreDatabase(source)) {
                    ObjectNode r = protocol.items(a.put("as", "none"));
                    repaired.add(source + " is now the list \"" + r.path("title").asText() + "\" (" + r.path("items_found").asInt() + " books)");
                } else {
                    protocol.add(a);
                    repaired.add(source + " was read again as text");
                }
                RawCapture.dropUnreadable(store, locator);
                if (Files.exists(bad) && RawCapture.looksBinary(RawCapture.read(bad)[2])) Files.delete(bad);
            } catch (Exception e) { left.add(bad.getFileName() + " (" + locator + "): " + e.getMessage()); }
        }
        if (!repaired.isEmpty() || !left.isEmpty()) store.circulate("repair", repaired.size() + " repaired, " + left.size() + " left alone :: " + String.join(" | ", repaired));
        return new Outcome(looked, repaired, left);
    }

    /** The file a file:// locator names, or null. */
    static Path localFile(String locator) {
        if (locator == null || !locator.startsWith("file:")) return null;
        try { return Path.of(URI.create(locator)); } catch (Exception e) { return null; }
    }
}
