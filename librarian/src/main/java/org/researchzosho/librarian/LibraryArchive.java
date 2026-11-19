package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.researchzosho.Config;
import org.researchzosho.Version;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * A library to take home (0.8.0, stage 4): one zip file, {@code <name>.rzlib.zip}, that every system opens. Its first entry is
 * {@code researchzosho-library.json}, what the archive is (format 1, the library's id, name and title, when it was made and packed,
 * which service packed it and which version, how many files and bytes follow, and the library's own settings). Then the library's
 * folder under {@code library/}: its findings, the pages it saved, its reports and investigations, its catalog with its jobs'
 * records, and its search index. Then its own settings under {@code home/config}: the language, how reports and translations are
 * written, what its nights do. Not in it: the service's keys and settings, the readers and the keys of programs a library of a
 * person's own kept before it came to the service (their hashes, but other people's), the people let in (the service keeps them),
 * the work a running job keeps to resume, logs, locks and temporary files. docs/public/LIBRARY_ARCHIVE.md is the format.
 */
final class LibraryArchive {
    private LibraryArchive() { }

    static final String MANIFEST = "researchzosho-library.json", SUFFIX = ".rzlib.zip", LIBRARY = "library/", SETTINGS = "home/config";
    static final int FORMAT = 1;

    /** The library's own files a library of a person's own kept for other people: never in an archive. */
    private static final List<String> OTHER_PEOPLE = List.of("catalog/accounts.json", "catalog/keys.json", "catalog/patrons.md", "catalog/access-requests.md",
            "catalog/webhooks.md");   // other people's webhooks: their addresses and their secrets (round F)

    /** Whether a file of a library (its path under the library's folder, with /) goes into the archive. */
    static boolean carried(String rel) {
        String name = rel.substring(rel.lastIndexOf('/') + 1);
        if (OTHER_PEOPLE.contains(rel)) return false;
        if (rel.startsWith("catalog/chat/readers/")) return false;   // the conversations of the people who read the library are theirs, not its keeper's (round F)
        if (rel.startsWith("catalog/jobs/work/")) return false;   // a running job's kept work and its checkpoint: a job resumes where it ran, not where it is taken
        if (name.endsWith(".log") || name.endsWith(".lock") || name.startsWith(".lock") || name.startsWith("serve.lock") || name.endsWith(".pid") || name.endsWith(LibraryPlaces.HALF)) return false;
        if (name.equals(LibraryPlaces.BROUGHT_BACK) || name.equals(LibraryPlaces.MOVED_FROM)) return false;   // the service's notes to itself
        for (String part : rel.split("/")) if (part.equals("tmp")) return false;
        return true;
    }

    /** The settings of a library's own an archive carries: how its reports and translations are written and what its nights do, not a state of the moment. */
    static boolean ownSetting(String key) { return Hosted.ownSetting(key) && !key.equals(ResearchSettings.PAUSE) && !key.equals("RESEARCHZOSHO_EXPLORER_TONIGHT"); }

    static String settingKey(String written) {
        String k = written.strip().toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_');
        return k.startsWith("RESEARCHZOSHO_") ? k : "RESEARCHZOSHO_" + k;
    }

    /** The library's own settings in a settings file: key = value lines, the keys as the settings file normalises them; others left out. */
    static Map<String, String> ownSettings(String text) {
        Map<String, String> m = new TreeMap<>();
        for (String line : text.split("\n")) {
            String l = line.strip();
            int eq = l.indexOf('=');
            if (l.isEmpty() || l.startsWith("#") || eq < 1) continue;
            String key = settingKey(l.substring(0, eq)), value = l.substring(eq + 1).strip();
            if (ownSetting(key) && !value.isEmpty()) m.put(key, value);
        }
        return m;
    }

    private record Item(String rel, Path path, long size, long time) { }

    /** The files of a library folder that go into the archive, in order, and nothing that is a link. */
    private static List<Item> files(Path library) throws IOException {
        List<Item> out = new ArrayList<>();
        if (!Files.isDirectory(library, LinkOption.NOFOLLOW_LINKS)) return out;
        Files.walkFileTree(library, new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path f, BasicFileAttributes a) {
                if (!a.isRegularFile()) return FileVisitResult.CONTINUE;
                String rel = LibraryPlaces.rel(library, f);
                if (carried(rel)) out.add(new Item(rel, f, a.size(), a.lastModifiedTime().toMillis()));
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFileFailed(Path f, IOException e) { return FileVisitResult.CONTINUE; }   // gone meanwhile
        });
        out.sort((x, y) -> x.rel().compareTo(y.rel()));
        return out;
    }

    /**
     * The archive of the library whose folder is {@code base} ({@code library/} and {@code home/} under it: the library's own folder, or
     * its copy's files), written to {@code out} as it is made, never staged on a disk; {@code out} is not closed. Whoever calls holds the
     * library still: asleep, or its copy not brought up to date meanwhile. The manifest written.
     */
    static ObjectNode write(OutputStream out, Path base, AccountStore.Library lib, String from) throws IOException {
        return write(out, LibraryPlaces.library(base), LibraryPlaces.home(base).resolve("config"), lib.id(), lib.name(), lib.title(), lib.madeAt(), from);
    }

    /**
     * The same for a library's own folder {@code library} (with {@code catalog/library.md}) and the settings file {@code config} (null:
     * none), by the id, name, title and time the manifest says: a library of a person's own, packed to be taken in on another machine.
     */
    static ObjectNode write(OutputStream out, Path library, Path config, String libraryId, String libraryName, String libraryTitle, long madeAt, String from) throws IOException {
        if (!Files.isRegularFile(library.resolve("catalog").resolve("library.md"))) throw new IOException("There is no library at " + library + " to pack.");
        List<Item> files = files(library);
        Map<String, String> settings = config != null && Files.isRegularFile(config, LinkOption.NOFOLLOW_LINKS) ? ownSettings(Files.readString(config, StandardCharsets.UTF_8)) : Map.of();
        long bytes = 0; Map<String, Integer> counts = new TreeMap<>();
        for (Item f : files) { bytes += f.size(); int slash = f.rel().indexOf('/'); counts.merge(slash < 0 ? "." : f.rel().substring(0, slash), 1, Integer::sum); }
        ObjectNode m = Statement.M.createObjectNode();
        m.put("format", FORMAT);
        m.put("library_id", libraryId); m.put("name", libraryName); m.put("title", libraryTitle);
        m.put("made_at", Instant.ofEpochMilli(madeAt).toString()); m.put("packed_at", Instant.now().toString());
        m.put("from", from == null ? "" : from); m.put("version", Version.string());
        m.put("files", files.size()); m.put("bytes", bytes);
        ObjectNode c = m.putObject("counts"); counts.forEach(c::put);
        ObjectNode s = m.putObject("settings"); settings.forEach((k, v) -> s.put(k.substring("RESEARCHZOSHO_".length()).toLowerCase(Locale.ROOT), v));
        ZipOutputStream z = new ZipOutputStream(new FilterOutputStream(out) {
            @Override public void write(byte[] b, int off, int len) throws IOException { out.write(b, off, len); }
            @Override public void close() throws IOException { out.flush(); }   // the stream is the caller's
        });
        z.putNextEntry(new ZipEntry(MANIFEST));
        z.write(Statement.M.writerWithDefaultPrettyPrinter().writeValueAsBytes(m));
        z.closeEntry();
        byte[] buf = new byte[65536];
        for (Item f : files) {
            ZipEntry e = new ZipEntry(LIBRARY + f.rel());
            e.setLastModifiedTime(FileTime.fromMillis(f.time()));
            z.putNextEntry(e);
            long left = f.size();   // as many bytes as were counted: the manifest's sizes are the archive's
            try (InputStream in = Files.newInputStream(f.path())) {
                while (left > 0) { int n = in.read(buf, 0, (int) Math.min(buf.length, left)); if (n < 0) throw new IOException("The file " + f.rel() + " of the library changed while it was packed."); z.write(buf, 0, n); left -= n; }
            }
            z.closeEntry();
        }
        if (!settings.isEmpty()) {
            StringBuilder b = new StringBuilder("# The library's own settings, packed with it.\n");
            settings.forEach((k, v) -> b.append(k).append(" = ").append(v).append('\n'));
            z.putNextEntry(new ZipEntry(SETTINGS));
            z.write(b.toString().getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
        }
        z.finish();
        z.flush();
        return m;
    }

    /** An archive unpacked: what its manifest says, the folder the library is in now, and its own settings. */
    record Unpacked(JsonNode manifest, Path folder, Map<String, String> settings) {
        String title() { return manifest.path("title").asText(""); }
        String name() { return manifest.path("name").asText(""); }
    }

    /** Why an archive's entry may not be written: null when it may. */
    private static String unsafe(String name) {
        if (name.isEmpty() || name.startsWith("/") || name.contains("\\") || name.contains("\0") || name.matches("^[A-Za-z]:.*")) return "names a place outside its folder";
        for (String part : name.split("/")) if (part.equals("..") || part.equals(".")) return "names a place outside its folder";
        if (!name.startsWith(LIBRARY) && !name.equals(SETTINGS)) return "is not part of a library";
        return null;
    }

    /**
     * An archive read and unpacked into {@code into}, which must not exist yet or be empty: the manifest first, its format, every entry's
     * name (none outside the folder, none but the library and its settings), the free space, and the files and bytes the manifest
     * counts; the library in it must be the one the manifest names. Anything wrong leaves nothing behind and says what in a sentence.
     */
    static Unpacked unpack(Path archive, Path into) throws IOException { return unpack(archive, into, false); }

    /**
     * The same; with {@code carriedOnly} (a library taken in under the account service, 0.8.0 review, SEC 9), an entry an export would
     * not have carried (another person's accounts and keys, a job's work, a lock) is read and counted but not written: an archive made by
     * hand cannot plant them.
     */
    static Unpacked unpack(Path archive, Path into, boolean carriedOnly) throws IOException {
        Path to = into.toAbsolutePath().normalize();
        if (Files.exists(to, LinkOption.NOFOLLOW_LINKS) && !(Files.isDirectory(to, LinkOption.NOFOLLOW_LINKS) && empty(to))) throw new IOException("There is something at " + to + " already; nothing was taken. Name a folder that is not there yet.");
        if (!Files.isRegularFile(archive)) throw new IOException("There is no file at " + archive + ".");
        boolean made = false, existed = Files.isDirectory(to, LinkOption.NOFOLLOW_LINKS);
        try (ZipFile z = new ZipFile(archive.toFile())) {
            Enumeration<? extends ZipEntry> all = z.entries();
            ZipEntry first = all.hasMoreElements() ? all.nextElement() : null;
            if (first == null || !first.getName().equals(MANIFEST)) throw new IOException("This is not a library's archive: it does not begin with " + MANIFEST + ".");
            JsonNode m;
            try (InputStream in = z.getInputStream(first)) { m = Statement.M.readTree(in.readNBytes(1 << 20)); }
            int format = m.path("format").asInt(0);
            if (format > FORMAT) throw new IOException("This archive was made by a newer ResearchZosho (format " + format + "); this one reads format " + FORMAT + ". Update ResearchZosho and take it again.");
            if (format != FORMAT || m.path("library_id").asText("").isEmpty()) throw new IOException("This is not a library's archive that this ResearchZosho reads: its manifest has no format " + FORMAT + " and no library id.");
            if (!LibraryPlaces.idOk(m.path("library_id").asText(""))) throw new IOException(LibraryPlaces.notAnId(m.path("library_id").asText("")));
            // a library's id is a library's (lib_…): never one a machine of the service is known by, for a statement names either by its id (round F)
            if (!m.path("library_id").asText("").startsWith("lib_")) throw new IOException(LibraryPlaces.notAnId(m.path("library_id").asText("")));
            long files = m.path("files").asLong(-1), bytes = m.path("bytes").asLong(-1);
            if (files < 0 || bytes < 0) throw new IOException("The archive's manifest does not say how much it holds.");
            Path existing = to; while (existing != null && !Files.exists(existing)) existing = existing.getParent();
            if (existing != null && Files.getFileStore(existing).getUsableSpace() < bytes + 64L * 1024 * 1024) throw new IOException("There is not room for the library at " + to + ": it needs " + (bytes / (1024 * 1024) + 1) + " MB and some to spare.");
            Files.createDirectories(to);
            made = true;
            long seenFiles = 0, seenBytes = 0;
            Map<String, String> settings = new LinkedHashMap<>();
            byte[] buf = new byte[65536];
            while (all.hasMoreElements()) {
                ZipEntry e = all.nextElement();
                String why = unsafe(e.getName());
                if (why != null) throw new IOException("The archive's entry " + e.getName() + " " + why + "; nothing was taken.");
                if (e.isDirectory()) continue;
                if (e.getName().equals(SETTINGS)) {
                    try (InputStream in = z.getInputStream(e)) { settings.putAll(ownSettings(new String(in.readNBytes(1 << 20), StandardCharsets.UTF_8))); }
                    continue;
                }
                Path target = to.resolve(e.getName().substring(LIBRARY.length())).normalize();
                if (!target.startsWith(to) || target.equals(to)) throw new IOException("The archive's entry " + e.getName() + " names a place outside its folder; nothing was taken.");
                if (carriedOnly && !carried(e.getName().substring(LIBRARY.length()))) {   // counted against the manifest, not written
                    try (InputStream in = z.getInputStream(e)) { for (int n; (n = in.read(buf)) > 0; ) { seenBytes += n; if (seenBytes > bytes) throw new IOException("The archive holds more than its manifest says (" + bytes + " bytes); nothing was taken."); } }
                    seenFiles++;
                    continue;
                }
                Files.createDirectories(target.getParent());
                try (InputStream in = z.getInputStream(e); OutputStream o = Files.newOutputStream(target)) {
                    for (int n; (n = in.read(buf)) > 0; ) {
                        seenBytes += n;
                        if (seenBytes > bytes) throw new IOException("The archive holds more than its manifest says (" + bytes + " bytes); nothing was taken.");
                        o.write(buf, 0, n);
                    }
                }
                if (e.getLastModifiedTime() != null) Files.setLastModifiedTime(target, e.getLastModifiedTime());
                seenFiles++;
            }
            if (seenFiles != files || seenBytes != bytes) throw new IOException("The archive is not whole: its manifest says " + files + " files and " + bytes + " bytes, and it holds " + seenFiles + " files and " + seenBytes + " bytes; nothing was taken.");
            LibraryStore taken = new LibraryStore(to);
            if (!Files.isRegularFile(taken.libraryFile()) || !taken.identity().id().equals(m.path("library_id").asText())) throw new IOException("The library in the archive is not the one its manifest names; nothing was taken.");
            return new Unpacked(m, to, settings);
        } catch (IOException | RuntimeException e) {
            if (made && !existed) LibraryPlaces.remove(to);   // the folder this made, and only it
            else if (made) { try (Stream<Path> in = Files.list(to)) { for (Path p : in.toList()) LibraryPlaces.remove(p); } }   // an empty folder named for it: emptied again
            if (e instanceof ZipException) throw new IOException("This file is not a whole zip archive: " + e.getMessage());
            throw e;
        }
    }

    /**
     * {@code researchzosho take <archive> [folder]}: a library a service packed, opened as a library of this computer's own. It is
     * unpacked into a new folder (by default beside this program's settings folder, named from the library), never over one that is
     * there; whoever runs this keeps it, as they keep any library of their own. With no library of their own yet it is theirs from now
     * on; otherwise theirs stays, and the line that serves this one is said. Its own settings are said with the line that sets each.
     */
    static int take(String[] args, PrintStream out, PrintStream err) {
        if (args.length < 3) { err.println("usage: researchzosho take <archive> [folder]"); return 2; }
        if (AccountService.on()) { err.println("This machine serves accounts: a library's archive is taken in for an account with: researchzosho accounts take " + args[2] + " --for \"<name>\""); return 1; }
        try {
            Path archive = Path.of(args[2]).toAbsolutePath().normalize();
            Path into;
            if (args.length > 3) into = Path.of(args[3].replaceFirst("^~", System.getProperty("user.home"))).toAbsolutePath().normalize();
            else {
                JsonNode m = manifest(archive);
                String name = m.path("name").asText("library").replaceAll("[^A-Za-z0-9._-]+", "-");
                Path beside = Config.home().toAbsolutePath().normalize().getParent();
                into = beside.resolve("researchzosho-" + name);
                for (int n = 2; Files.exists(into); n++) into = beside.resolve("researchzosho-" + name + "-" + n);
            }
            Unpacked u = unpack(archive, into, true);   // only what an export carries (0.8.0 review, SEC 9): an archive made by hand plants no one's account or keys
            out.println("The library \"" + u.title() + "\" is at " + u.folder() + ", with its shelves, its reports and its jobs' records. It is yours, as any library of your own.");
            String mine = Config.get("RESEARCHZOSHO_LIBRARY");
            boolean none = (mine == null || mine.isBlank()) && !Files.isDirectory(LibraryStore.defaultRoot(Path.of(System.getProperty("user.home"))).resolve("catalog"));
            if (none) {
                Config.set("RESEARCHZOSHO_LIBRARY", u.folder().toString());
                out.println("  It is this computer's library now: researchzosho serve opens it.");
            } else {
                out.println("  Your library stays " + (mine == null || mine.isBlank() ? LibraryStore.defaultRoot(Path.of(System.getProperty("user.home"))) : mine) + ". This one is served with:");
                out.println("    RESEARCHZOSHO_LIBRARY=" + u.folder() + " researchzosho serve");
                out.println("  or made this computer's library with researchzosho setup.");
            }
            if (!u.settings().isEmpty()) {
                out.println("  Its own settings came with it; each is set here with:");
                for (Map.Entry<String, String> e : u.settings().entrySet()) out.println("    researchzosho settings set " + e.getKey().substring("RESEARCHZOSHO_".length()).toLowerCase(Locale.ROOT) + " " + e.getValue());
            }
            return 0;
        } catch (IOException e) { err.println(e.getMessage()); return 1; }
    }

    /** An archive's manifest, read without unpacking it. */
    static JsonNode manifest(Path archive) throws IOException {
        if (!Files.isRegularFile(archive)) throw new IOException("There is no file at " + archive + ".");
        try (ZipFile z = new ZipFile(archive.toFile())) {
            ZipEntry first = z.entries().hasMoreElements() ? z.entries().nextElement() : null;
            if (first == null || !first.getName().equals(MANIFEST)) throw new IOException("This is not a library's archive: it does not begin with " + MANIFEST + ".");
            try (InputStream in = z.getInputStream(first)) { return Statement.M.readTree(in.readNBytes(1 << 20)); }
        } catch (ZipException e) { throw new IOException("This file is not a whole zip archive: " + e.getMessage()); }
    }

    private static boolean empty(Path dir) throws IOException { try (Stream<Path> s = Files.list(dir)) { return s.findAny().isEmpty(); } }

    /** The file name an archive of the library is given. */
    static String fileName(AccountStore.Library lib) { return lib.name().replaceAll("[^A-Za-z0-9._-]+", "-") + SUFFIX; }
}
