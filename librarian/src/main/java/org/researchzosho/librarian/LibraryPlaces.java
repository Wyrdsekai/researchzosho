package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.researchzosho.Config;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.channels.Channels;
import java.nio.channels.FileLock;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Where a library under the account service lives (0.8.0, places): the one place in the code that builds a library's path. The
 * runners, the node and the commands ask it; nothing else puts a library's folder together.
 *
 * <p>This is the first form, a volume per machine. A library is a folder {@code <root>/libraries/<name>} holding the library itself
 * ({@code library/}), its own settings ({@code home/}), its temporary folder and its Java home, and its log. Its <b>copy</b> is
 * {@code <accounts_copies>/<library id>/}: the library and its settings as files, a list of what the copy holds (each file's size
 * and time), and a mark written last that says when the copy was whole. Bringing a copy up to date reads the library's side and
 * writes only what changed. A copy made while the library is stopped holds its search index too; one made while it runs leaves
 * the index as the last stopped copy had it, since an index is written to while it is read. A folder taken out of service (moved,
 * replaced by its copy, removed) is <b>set aside</b>, {@code <root>/aside/…}, and removed with what else was set aside for it when
 * its days of grace are over.
 *
 * <p>With a shared file system a second form would answer the same calls: one folder for every machine, and nothing to bring.
 */
public final class LibraryPlaces {
    /** The folder the libraries' copies are kept in (another disk, a mounted bucket). Unset: no copies. */
    public static final String COPIES = "RESEARCHZOSHO_ACCOUNTS_COPIES";
    /** After how many minutes a library's copy is brought up to date at a quiet moment when something changed. Unset: 10. */
    public static final String COPY_AFTER = "RESEARCHZOSHO_ACCOUNTS_COPY_AFTER";
    /** How long a folder set aside is kept before it is removed: 30 days; a test shortens it. */
    static volatile long GRACE_MS = 30L * 24 * 3_600_000;

    /** What a library's folder holds, by name. */
    static final String LIBRARY = "library", HOME = "home", TMP = "tmp", USER = "user", LOG = "serve.log";
    /** What a copy holds: the library and its settings; the rest of the folder is made again. */
    static final List<String> KEPT = List.of(LIBRARY, HOME);
    /** The search index, inside the library: copied only while the library is stopped. */
    static final String INDEX = LIBRARY + "/.index";
    /** The note a library brought back from its copy finds in itself, and shows. */
    static final String BROUGHT_BACK = "brought-back.json";
    /** The note a library whose folder was moved finds in itself: where it was, so its jobs' own paths are put right at its start. */
    static final String MOVED_FROM = "moved-from.txt";

    /** Writes into the library at {@code now} where it was before, for {@link #relocated} at its next start. */
    static void movedFrom(Path now, Path was) throws IOException {
        Path catalog = library(now).resolve("catalog");
        Files.createDirectories(catalog);
        Files.writeString(catalog.resolve(MOVED_FROM), library(was).toAbsolutePath().normalize() + "\n", StandardCharsets.UTF_8);
    }

    /**
     * At a library's start: when its folder was moved (another root, or brought back from its copy onto another machine), the paths its
     * jobs keep of their own files (the document a translation reads, where it writes) name the old place: they are put right, in the
     * job records and in the work each kept, so a job goes on from its kept work, reads its document where it is now, and writes there.
     * How many files were put right.
     */
    public static int relocated(LibraryStore store) throws IOException {
        Path note = store.root().resolve("catalog").resolve(MOVED_FROM);
        if (!Files.isRegularFile(note)) return 0;
        String was = Files.readString(note, StandardCharsets.UTF_8).strip(), now = store.root().toAbsolutePath().normalize().toString();
        int n = 0;
        Path jobs = store.root().resolve("catalog").resolve("jobs");
        if (!was.isEmpty() && !was.equals(now) && Files.isDirectory(jobs)) {
            try (Stream<Path> s = Files.walk(jobs)) {
                // the job records, and the work each kept (where it writes is kept there too: catalog/jobs/work/<job>/artifacts.jsonl)
                for (Path f : s.filter(p -> (p.toString().endsWith(".json") || p.toString().endsWith(".jsonl")) && !jobs.relativize(p).toString().startsWith("done")).toList()) {
                    String text = Files.readString(f, StandardCharsets.UTF_8), put = text.replace(was + "/", now + "/").replace(was + "\"", now + "\"");
                    if (!put.equals(text)) { writeWhole(f, put); n++; }
                }
            }
        }
        Files.delete(note);
        return n;
    }

    private final AccountStore store;
    /** The node of this machine that changes the places ("" on a machine that runs none): what it sets aside is written down for it, and swept by it (ROB 4). */
    private final String node;

    public LibraryPlaces(AccountStore store) { this(store, ""); }

    /** Places as the node {@code node} of this machine changes them ("" for a machine that runs none). */
    public LibraryPlaces(AccountStore store, String node) { this.store = store; this.node = node == null ? "" : node; }

    // ---- where ----

    /** The folder a new library named {@code name} is made in, under a root. */
    static Path newFolder(Path root, String name) { return root.resolve("libraries").resolve(name); }

    /** The folder a library is kept in. */
    static Path folder(AccountStore.Library lib) { return Path.of(lib.folder()); }
    static Path library(AccountStore.Library lib) { return library(folder(lib)); }
    static Path library(Path folder) { return folder.resolve(LIBRARY); }
    static Path home(Path folder) { return folder.resolve(HOME); }
    static Path tmp(Path folder) { return folder.resolve(TMP); }
    static Path user(Path folder) { return folder.resolve(USER); }
    static Path log(Path folder) { return folder.resolve(LOG); }

    /** The root a library's folder is under: {@code <root>/libraries/<name>}. */
    static Path rootOf(AccountStore.Library lib) { Path f = folder(lib).toAbsolutePath().normalize(); return f.getParent() == null || f.getParent().getParent() == null ? f : f.getParent().getParent(); }

    /** The folder the copies are kept in; null when none is set. */
    static Path copies() { String v = Config.get(COPIES); return v == null || v.isBlank() ? null : Path.of(v.strip()).toAbsolutePath().normalize(); }

    /** A library's copy; null when no folder for copies is set. An id that is not one is refused: no path is built from it. */
    static Path copy(String libraryId) {
        Path c = copies();
        if (c == null) return null;
        if (!idOk(libraryId)) throw new IllegalArgumentException(notAnId(libraryId));
        return under(c, libraryId);
    }

    /** A library's id as the last part of a path may hold it: letters, digits, _ and -, at most 64 (the program makes lib_ and 16 hex digits). */
    static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    static boolean idOk(String id) { return id != null && ID.matcher(id).matches(); }
    static String notAnId(String id) { return Pages.t("\"{0}\" is not a library's id: a library's id holds letters, digits, _ and - only. Nothing was taken in.", String.valueOf(id)); }

    /** {@code base} and one part under it, which must stay under it. */
    static Path under(Path base, String part) {
        Path b = base.toAbsolutePath().normalize(), p = b.resolve(part).normalize();
        if (!p.startsWith(b) || p.equals(b) || !p.getParent().equals(b)) throw new IllegalArgumentException(part + " is not one folder under " + b);
        return p;
    }

    /** After how long, in milliseconds, a copy is brought up to date at a quiet moment when something changed. */
    static long copyAfterMs() { Long t = COPY_AFTER_MS; return t != null ? t : Math.max(1, Config.getInt(COPY_AFTER, 10)) * 60_000L; }
    /** A test's own time, in milliseconds; null: the setting. */
    static volatile Long COPY_AFTER_MS = Long.getLong("researchzosho.accounts.copy_after_ms");

    // ---- the copy ----

    /** How long a pass or a change of a library's folder waits for another one of its copy to end; a test shortens it. */
    static volatile long COPY_WAIT_MS = 15 * 60_000L;
    private static final Map<String, ReentrantLock> COPY_HELD = new ConcurrentHashMap<>();

    /**
     * The library's copy held for one pass or one change of its folder, whatever process makes it (0.8.0 review, ROB 7): a lock of this
     * process, and a lock on a file in the copy's folder that another process (the node, the command line) waits on as well. Taken again
     * by the same thread, it is the same hold. Null when no folder for copies is set.
     */
    static Closeable holdCopy(String libraryId) throws IOException {
        Path c = copy(libraryId);
        if (c == null) return null;
        ReentrantLock mine = COPY_HELD.computeIfAbsent(libraryId, k -> new ReentrantLock());
        try { if (!mine.tryLock(COPY_WAIT_MS, TimeUnit.MILLISECONDS)) throw new IOException(Pages.t("The copy of the library {0} is being written by another task; ask again in a few minutes.", libraryId)); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("interrupted"); }
        if (mine.getHoldCount() > 1) return mine::unlock;   // this thread holds it already
        FileChannel ch = null;
        try {
            Path holds = copies().resolve(HOLDS);   // beside the copies, not in one: a copy removed leaves its hold, and one waiting finds nothing to copy
            Files.createDirectories(holds);
            ch = FileChannel.open(under(holds, libraryId), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            long until = System.currentTimeMillis() + COPY_WAIT_MS;
            FileLock lock;
            while ((lock = ch.tryLock()) == null) {
                if (System.currentTimeMillis() > until) throw new IOException(Pages.t("The copy of the library {0} is being written by another task; ask again in a few minutes.", libraryId));
                try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("interrupted"); }
            }
            FileLock held = lock; FileChannel open = ch;
            return () -> { try { held.release(); open.close(); } finally { mine.unlock(); } };
        } catch (IOException | RuntimeException e) {
            if (ch != null) { try { ch.close(); } catch (IOException ignored) { } }
            mine.unlock();
            throw e;
        }
    }

    /** The folder of the copies' holds, a file a library, beside the copies. */
    static final String HOLDS = ".holds";

    /** One pass that brought a copy up to date: from when to when, of which start, whether the library was stopped (the index came too), and what it found. */
    public record Copy(String library, long began, long ended, long epoch, boolean stopped, int files, long bytes, int changed, int removed, boolean interrupted) {
        /** When the copy was made, as a person reads it. */
        String when() { return DateTimeFormatter.ofPattern("d MMMM yyyy, HH:mm z", Locale.ENGLISH).format(Instant.ofEpochMilli(ended > 0 ? ended : began).atZone(ZoneId.systemDefault())); }   // with its zone: a service in a container tells UTC
    }

    /** A file as the copy last saw it on the library's side: its size and its time; a folder has size -1. */
    private record Seen(long size, long time) { }

    /** Whether a path inside a library's folder is never part of a copy: the folder's own lease, which names a process of this machine. */
    static boolean never(String rel) { String n = rel.substring(rel.lastIndexOf('/') + 1); return n.startsWith("serve.lock"); }

    /**
     * Brings the library's copy up to date: every file of the library and its settings whose size or time changed is written (to a
     * name of its own, then put in place, so each file in the copy is whole), what is gone is removed, the list of what the copy
     * holds is written, and the mark last. {@code stopped}: the library is not running, so its index comes too. Null when no folder
     * for copies is set. A pass interrupted (the machine stopped) leaves a mark that says when it began; the next pass finishes it.
     */
    public Copy bringUp(AccountStore.Library lib, long epoch, boolean stopped) throws IOException {
        if (copy(lib.id()) == null) return null;
        try (Closeable held = holdCopy(lib.id())) { return bringUpHeld(lib, epoch, stopped); }   // one pass at a time, whatever process (ROB 7)
    }

    private Copy bringUpHeld(AccountStore.Library lib, long epoch, boolean stopped) throws IOException {
        Path to = copy(lib.id());
        if (to == null) return null;
        Path from = folder(lib);
        // a library that cannot be seen (its disk unmounted, a network mount lost) is not a library with nothing in it: its copy is left as it was
        if (!Files.isRegularFile(library(from).resolve("catalog").resolve("library.md"), LinkOption.NOFOLLOW_LINKS))
            throw new IOException(Pages.t("The library {0} cannot be seen at {1} (it has no catalog/library.md there), so its copy was left as it was.", lib.title(), from));
        Path files = to.resolve("files");
        Files.createDirectories(files);
        long began = System.currentTimeMillis();
        Path updating = to.resolve("updating");
        boolean wasInterrupted = Files.exists(updating);
        Files.writeString(updating, Instant.ofEpochMilli(began) + "\n", StandardCharsets.UTF_8);
        if (wasInterrupted) clearHalfWritten(files);
        Map<String, Seen> had = manifest(to), now = new TreeMap<>();
        List<String> unread = new ArrayList<>();
        int[] changed = {0}; long[] bytes = {0};
        for (String top : KEPT) {
            Path base = from.resolve(top);
            if (!Files.isDirectory(base, LinkOption.NOFOLLOW_LINKS)) continue;
            Files.walkFileTree(base, new SimpleFileVisitor<>() {
                @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes a) throws IOException {
                    String rel = rel(from, dir);
                    if (!listable(rel)) return FileVisitResult.SKIP_SUBTREE;   // a name with a line break or a tab would write lines of its own into the list
                    if (!stopped && rel.equals(INDEX)) return FileVisitResult.SKIP_SUBTREE;   // written to while it runs: left as the last stopped copy had it
                    now.put(rel, new Seen(-1, 0));
                    Files.createDirectories(files.resolve(rel));
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFile(Path f, BasicFileAttributes a) throws IOException {
                    if (!a.isRegularFile()) return FileVisitResult.CONTINUE;   // a link is not followed, and not copied
                    String rel = rel(from, f);
                    if (never(rel) || !listable(rel)) return FileVisitResult.CONTINUE;
                    Seen s = new Seen(a.size(), a.lastModifiedTime().toMillis());
                    Seen before = had.get(rel);
                    if (before == null || !before.equals(s) || !Files.exists(files.resolve(rel))) {
                        try { putFrom(from, rel, files.resolve(rel), s.time()); changed[0]++; }
                        catch (NoSuchFileException gone) { return FileVisitResult.CONTINUE; }   // removed while the pass ran: not in the copy
                        catch (LinkOnTheWay link) { return FileVisitResult.CONTINUE; }   // a folder on its way was swapped for a link: not read through, not copied
                        catch (IOException e) { unread.add(rel); return FileVisitResult.CONTINUE; }   // could not be read: the copy keeps what it had of it
                    }
                    now.put(rel, s);
                    bytes[0] += s.size();
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFileFailed(Path f, IOException e) {
                    if (!(e instanceof NoSuchFileException)) unread.add(rel(from, f));   // gone while the pass ran is nothing; not read is something
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        if (!unread.isEmpty()) {
            // what could be read is written; nothing is removed from the copy, the list keeps what the copy holds, and the copy is not called whole
            for (Map.Entry<String, Seen> e : had.entrySet()) now.putIfAbsent(e.getKey(), e.getValue());
            writeManifest(to, now);
            throw new IOException(Pages.t("Part of the library {0} could not be read ({1} files or folders, the first {2}): what could be read is in its copy, nothing was removed from the copy, and the copy is not marked whole.",
                    lib.title(), unread.size(), unread.get(0)));
        }
        int removed = 0;
        List<String> goneDirs = new ArrayList<>();
        for (Map.Entry<String, Seen> e : had.entrySet()) {
            String rel = e.getKey();
            if (now.containsKey(rel)) continue;
            if (!stopped && (rel.equals(INDEX) || rel.startsWith(INDEX + "/"))) { now.put(rel, e.getValue()); if (e.getValue().size() >= 0) bytes[0] += e.getValue().size(); continue; }   // the index of the last stopped copy is kept
            if (e.getValue().size() < 0) { goneDirs.add(rel); continue; }
            Files.deleteIfExists(files.resolve(rel));
            removed++;
        }
        goneDirs.sort(Comparator.reverseOrder());
        for (String rel : goneDirs) { try { Files.deleteIfExists(files.resolve(rel)); } catch (DirectoryNotEmptyException kept) { /* something of it is still in the copy */ } }
        writeManifest(to, now);
        int count = 0; for (Seen s : now.values()) if (s.size() >= 0) count++;
        long ended = System.currentTimeMillis();
        Copy c = new Copy(lib.id(), began, ended, epoch, stopped, count, bytes[0], changed[0], removed, false);
        ObjectNode m = Statement.M.createObjectNode();
        m.put("library_id", lib.id()); m.put("title", lib.title()); m.put("began", began); m.put("ended", ended); m.put("epoch", epoch); m.put("stopped", stopped);
        m.put("files", count); m.put("bytes", bytes[0]); m.put("changed", changed[0]); m.put("removed", removed);
        writeWhole(to.resolve("whole.json"), Statement.M.writerWithDefaultPrettyPrinter().writeValueAsString(m));
        Files.deleteIfExists(updating);
        store.copied(lib.id(), ended, epoch);
        return c;
    }

    /** The copy as its mark says, and whether a pass was under way when it was last written to; null when there is none. */
    public static Copy lastCopy(String libraryId) throws IOException {
        Path to = copy(libraryId);
        if (to == null || !Files.isRegularFile(to.resolve("whole.json"))) return null;
        JsonNode m = Statement.M.readTree(Files.readString(to.resolve("whole.json"), StandardCharsets.UTF_8));
        boolean interrupted = Files.exists(to.resolve("updating"));
        long began = m.path("began").asLong(), ended = m.path("ended").asLong();
        if (interrupted) { try { began = Instant.parse(Files.readString(to.resolve("updating"), StandardCharsets.UTF_8).strip()).toEpochMilli(); } catch (RuntimeException e) { /* the mark's own */ } }
        return new Copy(m.path("library_id").asText(""), began, interrupted ? 0 : ended, m.path("epoch").asLong(), m.path("stopped").asBoolean(), m.path("files").asInt(), m.path("bytes").asLong(),
                m.path("changed").asInt(), m.path("removed").asInt(), interrupted);
    }

    /**
     * The library brought back from its copy into its folder: what is in the folder now is set aside first. The library finds a note
     * in itself that says which copy it is, and shows it. A sentence for whoever asked.
     */
    public String bring(AccountStore.Library lib) throws IOException { return bringTo(lib, folder(lib)); }

    /**
     * The same into another folder ({@code to}: on a machine that takes the library over from one that is gone): the registry is
     * pointed at it. What is at {@code to} is set aside first.
     */
    public String bringTo(AccountStore.Library lib, Path to) throws IOException {
        if (copy(lib.id()) == null) return bringToHeld(lib, to);
        try (Closeable held = holdCopy(lib.id())) { return bringToHeld(lib, to); }   // no pass writes the copy while it is read back (ROB 7)
    }

    private String bringToHeld(AccountStore.Library lib, Path to) throws IOException {
        Copy c = lastCopy(lib.id());
        if (c == null) throw new IOException("The library " + lib.title() + " has no copy" + (copies() == null ? ": no folder for copies is set (accounts_copies)." : " in " + copies() + "."));
        Path files = copy(lib.id()).resolve("files"), target = to.toAbsolutePath().normalize();
        // the copy is written beside the folder first and checked; only then is the folder set aside and the copy put in its place, so a
        // restore that fails part-way leaves the library's folder as it was
        Path arriving = target.resolveSibling(target.getFileName() + ".arriving");
        remove(arriving);
        try {
            Map<String, Seen> held = manifest(copy(lib.id()));
            long[] put = {0, 0};
            for (Map.Entry<String, Seen> e : held.entrySet()) {
                Path into = arriving.resolve(e.getKey());
                if (e.getValue().size() < 0) { Files.createDirectories(into); continue; }
                Path source = files.resolve(e.getKey());
                if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) continue;
                put[1] += Files.size(source);
                put(source, into, e.getValue().time());
                put[0]++;
            }
            long[] there = {0, 0};
            for (String top : KEPT) { long[] n = count(arriving.resolve(top)); there[0] += n[0]; there[1] += n[1]; }
            if (there[0] != put[0] || there[1] != put[1])
                throw new IOException(Pages.t("The library {0} was not brought back: what was written from its copy ({1} files) is not what the copy holds ({2} files). Its folder is as it was.", lib.title(), there[0], put[0]));
            for (Path p : new Path[]{library(arriving), home(arriving), tmp(arriving), user(arriving)}) Files.createDirectories(p);
            ObjectNode note = Statement.M.createObjectNode();
            note.put("copy_began", c.began()); note.put("copy_ended", c.ended()); note.put("interrupted", c.interrupted()); note.put("with_index", c.stopped()); note.put("brought_at", System.currentTimeMillis());
            Path catalog = library(arriving).resolve("catalog");
            Files.createDirectories(catalog);
            Files.writeString(catalog.resolve(BROUGHT_BACK), Statement.M.writerWithDefaultPrettyPrinter().writeValueAsString(note), StandardCharsets.UTF_8);
            boolean elsewhere = !target.equals(folder(lib).toAbsolutePath().normalize());
            if (elsewhere) movedFrom(arriving, folder(lib));
            Path setAside = Files.exists(target, LinkOption.NOFOLLOW_LINKS) ? aside(lib, target, "replaced-by-its-copy") : null;
            try { move(arriving, target); }
            catch (IOException e) {   // the folder put back where it was, and not written down to be removed
                if (setAside != null) { move(setAside, target); store.asideGone(setAside.toString()); }
                throw e;
            }
            if (elsewhere) store.libraryFolder(lib.id(), target.toString());
            keepCopy(lib);
            return "The library " + lib.title() + " is brought back from its copy of " + c.when() + (c.interrupted() ? " (a copy that was being brought up to date when it was last written to)" : "") + "."
                    + (setAside == null ? "" : " What was in its folder is set aside at " + setAside + ".");
        } catch (IOException | RuntimeException e) {
            try { remove(arriving); } catch (IOException kept) { e.addSuppressed(kept); }
            throw e;
        }
    }

    /** A library in service again keeps its copy: it is no longer written down to be removed with what was set aside. */
    void keepCopy(AccountStore.Library lib) throws IOException {
        Path c = copy(lib.id());
        if (c != null) store.asideGone(c.toString());
    }

    /** How long a library brought back from its copy says so on its page. */
    static final long SAYS_BROUGHT_BACK_MS = 7L * 24 * 3_600_000;

    /**
     * What a library read from its own folder says of the copy it was brought back from, for a week after: the copy's time, whether
     * that copy was being brought up to date when it was last written to, and when it was brought back. Null otherwise. The library
     * reads this; the service writes it.
     */
    public static JsonNode broughtBack(LibraryStore store) {
        try {
            Path f = store.root().resolve("catalog").resolve(BROUGHT_BACK);
            if (!Files.isRegularFile(f)) return null;
            JsonNode n = Statement.M.readTree(Files.readString(f, StandardCharsets.UTF_8));
            return System.currentTimeMillis() - n.path("brought_at").asLong(0) > SAYS_BROUGHT_BACK_MS ? null : n;
        } catch (IOException | RuntimeException e) { return null; }
    }

    // ---- set aside, and removed when the grace is over ----

    /** A folder of a library's set aside under its root ({@code <root>/aside/<when>-<name>-<why>}), and written down to be removed when the grace is over. Where it went. */
    public Path aside(AccountStore.Library lib, Path what, String why) throws IOException {
        Path root = rootOf(lib), place = root.resolve("aside").resolve(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(Instant.now().atZone(ZoneId.systemDefault())) + "-" + lib.name() + "-" + why);
        for (int n = 2; Files.exists(place); n++) place = place.resolveSibling(place.getFileName() + "-" + n);
        Files.createDirectories(place.getParent());
        move(what, place);
        store.aside(place.toString(), lib.id(), why, System.currentTimeMillis() + GRACE_MS, node);
        return place;
    }

    /** A library's copy set aside where it is: written down to be removed with the rest when the grace is over. */
    void copyAside(AccountStore.Library lib, String why) throws IOException {
        Path c = copy(lib.id());
        if (c != null && Files.exists(c)) store.aside(c.toString(), lib.id(), why, System.currentTimeMillis() + GRACE_MS, node);
    }

    /** What was set aside on this machine's disk and whose grace is over, removed: another machine's is its own node's to remove (ROB 4). How many. */
    public int sweep() throws IOException {
        int n = 0;
        for (AccountStore.Aside a : store.asidesDue(System.currentTimeMillis(), node)) {
            AccountStore.Library lib = idOk(a.library()) ? store.library(a.library()) : null;
            if (lib != null && lib.active() && copies() != null && a.path().equals(copy(lib.id()).toString())) { store.asideGone(a.path()); continue; }   // the copy of a library in service again is its own
            remove(Path.of(a.path()));
            store.asideGone(a.path());
            n++;
        }
        return n;
    }

    /** A folder and everything under it removed, following no link. */
    static void remove(Path dir) throws IOException {
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) return;
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) { Files.deleteIfExists(dir); return; }
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        } catch (UncheckedIOException e) { if (!(e.getCause() instanceof NoSuchFileException)) throw e.getCause(); }   // gone meanwhile: another removal of it ran too
        catch (NoSuchFileException gone) { /* the same */ }
    }

    // ---- out of service, while a folder is moved, brought back, removed or released ----

    /** How long a command waits for the service to stop a library it took out of service; a test shortens it. */
    static volatile long STOP_WAIT_MS = 60_000;

    /**
     * Takes a library out of service and waits until nothing runs it: the registry says {@code state}, so the node holding it stops
     * it at its next round and gives its lease back; and no process of this machine holds the library's own lease. When that does
     * not come within a minute, the library is put back in service and the sentence says so.
     */
    public void outOfService(AccountStore.Library lib, String state) throws IOException {
        store.libraryState(lib.id(), state);
        long until = System.currentTimeMillis() + STOP_WAIT_MS;
        while (true) {
            // the store's lease is the service's own word on who serves it; the lease file in the library's folder is the library's to write,
            // any host and any time in it, so it decides nothing here (round F: a file naming another host blocked every removal)
            AccountStore.Placement p = store.placement(lib.id());
            if (p == null || p.leaseUntil() <= store.clock()) return;
            if (System.currentTimeMillis() > until) {
                store.libraryState(lib.id(), "active");
                throw new IOException("The library " + lib.title() + " did not stop within " + STOP_WAIT_MS / 1000 + " seconds of being taken out of service, so nothing was changed and it is served as before.");
            }
            try { Thread.sleep(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); store.libraryState(lib.id(), "active"); throw new IOException("interrupted"); }
        }
    }

    // ---- moving a folder, here or to another root ----

    /** The library's folder copied to a new root of this machine, the registry pointed at it, the old folder set aside. The library must be out of service. The new folder. */
    public Path moveTo(AccountStore.Library lib, Path root) throws IOException {
        Path from = folder(lib), to = newFolder(root.toAbsolutePath().normalize(), lib.name());
        if (Files.exists(to)) throw new IOException("There is a folder at " + to + " already; nothing was moved.");
        if (to.startsWith(from) || from.startsWith(to)) throw new IOException("The new place is inside the old one, or the other way round; nothing was moved.");
        Files.createDirectories(to.getParent());
        Path partial = to.resolveSibling(to.getFileName() + ".arriving");
        remove(partial);
        long[] copied = copyTree(from, partial, rel -> !never(rel) && (rel.equals(LIBRARY) || rel.startsWith(LIBRARY + "/") || rel.equals(HOME) || rel.startsWith(HOME + "/") || rel.equals(LOG)));
        long[] there = count(library(partial)), here = count(library(from));
        if (there[0] != here[0] - leases(library(from)) || there[1] != here[1] - leaseBytes(library(from))) {
            remove(partial);
            throw new IOException("The copy at the new place does not hold what the old folder holds (" + there[0] + " files against " + here[0] + "); nothing was moved.");
        }
        Files.createDirectories(tmp(partial)); Files.createDirectories(user(partial));
        move(partial, to);
        movedFrom(to, from);
        store.libraryFolder(lib.id(), to.toString());
        aside(lib, from, "moved");
        return to;
    }

    /** Files and bytes under a folder, following no link. */
    static long[] count(Path dir) throws IOException {
        long[] n = {0, 0};
        if (!Files.isDirectory(dir)) return n;
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path f, BasicFileAttributes a) { if (a.isRegularFile()) { n[0]++; n[1] += a.size(); } return FileVisitResult.CONTINUE; }
        });
        return n;
    }

    private static long leases(Path library) { long n = 0; for (String f : new String[]{"serve.lock", "serve.lock.guard"}) if (Files.isRegularFile(library.resolve("catalog").resolve(f), LinkOption.NOFOLLOW_LINKS)) n++; return n; }
    private static long leaseBytes(Path library) throws IOException { long n = 0; for (String f : new String[]{"serve.lock", "serve.lock.guard"}) { Path p = library.resolve("catalog").resolve(f); if (Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) n += Files.size(p); } return n; }

    /** A whole tree copied, the times kept, links not followed; {@code keep} decides by the path inside it. Files and bytes copied. */
    static long[] copyTree(Path from, Path to, Predicate<String> keep) throws IOException {
        long[] n = {0, 0};
        Files.walkFileTree(from, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes a) throws IOException {
                String rel = rel(from, dir);
                if (!rel.isEmpty() && !keep.test(rel)) return FileVisitResult.SKIP_SUBTREE;
                Files.createDirectories(rel.isEmpty() ? to : to.resolve(rel));
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path f, BasicFileAttributes a) throws IOException {
                String rel = rel(from, f);
                if (!a.isRegularFile() || !keep.test(rel)) return FileVisitResult.CONTINUE;
                put(f, to.resolve(rel), a.lastModifiedTime().toMillis());
                n[0]++; n[1] += a.size();
                return FileVisitResult.CONTINUE;
            }
        });
        return n;
    }

    /** A folder moved: renamed where it can be, else copied whole and the original removed. */
    static void move(Path from, Path to) throws IOException {
        try { Files.move(from, to, StandardCopyOption.ATOMIC_MOVE); return; }
        catch (AtomicMoveNotSupportedException other) { /* another file system: copied below */ }
        copyTree(from, to, rel -> true);
        remove(from);
    }

    // ---- taking in a library of a person's own machine, and letting one go ----

    /** A library taken in: as it is written down now, whether its folder was moved (renamed) or copied, its own readers and keys (not carried), and the settings of its own that came with it. */
    public record Taken(AccountStore.Library library, Path from, boolean renamed, List<String> readers, List<String> keys, List<String> settings) { }

    /**
     * An ordinary library's folder taken in as a library of the service, for {@code keeper}: its own lease is taken first, to prove
     * that nothing serves it; then it is moved under the root (renamed where it can be, else copied and the original left where it
     * is), with its id and its jobs. Its own settings that a hosted keeper may set come with it from this process's settings; nothing
     * else of them. Its own readers and the keys of their programs stay in its files and are not used under the service: they are
     * listed, so its keeper lets the people in again. A library the service once had and took out (removed, released) comes back to
     * the account that kept it.
     */
    public Taken take(Path folder, AccountStore.Account keeper, Path root) throws IOException { return take(folder, keeper, root, null); }

    /** The same, with the library's own settings given ({@code own}: from its archive, stage 4) in place of this process's. */
    public Taken take(Path folder, AccountStore.Account keeper, Path root, Map<String, String> own) throws IOException {
        Path given = folder.toAbsolutePath().normalize();
        // a link would be moved in place of the library, which would stay where it is and be served from there, with no copy of it made
        if (Files.isSymbolicLink(given)) throw new IOException(Pages.t("{0} is a link to {1}; nothing was taken in. Take in the library's own folder: {1}", given, given.toRealPath()));
        Path from = Files.exists(given) ? given.toRealPath() : given;
        LibraryStore source = new LibraryStore(from);
        if (!Files.isRegularFile(source.libraryFile())) throw new IOException("There is no library at " + from + ": a library's folder has catalog/library.md.");
        LibraryStore.Identity id = source.identity();
        if (!idOk(id.id())) throw new IOException(notAnId(id.id()));
        // a library with no name of its own is called by its folder's name, and its folder is called "library" under the service: it keeps the name it had
        if (Files.readAllLines(source.libraryFile(), StandardCharsets.UTF_8).stream().noneMatch(l -> l.startsWith("name: ") && !l.substring(6).isBlank())) source.setName(id.name());
        AccountStore.Library known = store.library(id.id());
        if (known != null && known.active()) throw new IOException("The service has this library already, as " + known.name() + " (\"" + known.title() + "\").");
        if (known != null && keeper != null && !known.owner().equals(keeper.id())) throw new IOException("This library was " + known.title() + " of the service, kept by another account; it comes back to that account. Leave out --for.");
        if (known == null && keeper == null) throw new IOException("Say whose library it is: --for \"<name>\".");
        String name = known != null ? known.name() : AccountService.freeName(store, keeper);
        Path to = newFolder(root.toAbsolutePath().normalize(), name);
        if (Files.exists(to)) throw new IOException("There is a folder at " + to + " already; nothing was taken in.");
        // what is listed: read before anything moves
        List<String> readers = new ArrayList<>(), keys = new ArrayList<>();
        Patrons.Policy policy = Patrons.load(source);
        for (Accounts.Account a : Accounts.list(source)) {
            if (a.did().equals(Accounts.OWNER)) continue;
            String level = ""; for (Patrons.Entry e : policy.listed()) if (e.did().equals(a.did())) level = e.level().name();
            readers.add(a.name() + (level.isEmpty() ? "" : ", " + (level.equals("write") ? "reads and writes" : level.equals("read") ? "reads" : level)));
        }
        for (Patrons.Entry e : policy.listed()) if (Accounts.list(source).stream().noneMatch(a -> a.did().equals(e.did())) && !e.did().equals(Accounts.OWNER)) readers.add(e.name() + ", " + (e.level() == Patrons.Level.write ? "reads and writes" : "reads") + " (with a reader's link)");
        for (ApiKeys.Key k : ApiKeys.list(source, null)) keys.add(k.label().isBlank() ? k.prefix() + "…" : k.label() + " (" + k.prefix() + "…)");
        boolean renamed;
        Files.createDirectories(to);
        long[] there = null, here = null;
        try (Lease held = Lease.acquire(source)) {   // nothing serves it while it is taken in
            renamed = Files.getFileStore(from).equals(Files.getFileStore(to));
            if (!renamed) {
                copyTree(from, library(to), rel -> !never(rel));
                there = count(library(to)); here = count(from);
                here[0] -= leases(from); here[1] -= leaseBytes(from);
            }
        } catch (IOException e) { remove(to); throw new IOException("The library at " + from + " is being served, or could not be read: " + e.getMessage()); }
        if (there != null && (there[0] != here[0] || there[1] != here[1])) {   // copied, not moved: the copy must hold what the library holds
            remove(to);
            throw new IOException(Pages.t("The library at {0} was not taken in: what was copied under the service ({1} files) is not what the library holds ({2} files). It is where it was.", from, there[0], here[0]));
        }
        if (renamed) {
            try { Files.move(from, library(to), StandardCopyOption.ATOMIC_MOVE); }
            catch (IOException e) { remove(to); throw new IOException("The library at " + from + " could not be moved: " + e.getMessage()); }
        }
        for (Path p : new Path[]{home(to), tmp(to), user(to)}) Files.createDirectories(p);
        List<String> carried = new ArrayList<>();
        StringBuilder config = new StringBuilder("# The library's own settings, taken in with it.\n");
        for (String key : new TreeSet<>(Hosted.OWN_SETTINGS)) {
            if (key.equals(ResearchSettings.PAUSE) || key.equals("RESEARCHZOSHO_EXPLORER_TONIGHT")) continue;   // a state of the moment (paused; tonight's choice), not how the library is kept
            String v = own != null ? own.get(key) : Config.get(key);
            if (v == null || v.isBlank()) continue;
            config.append(key).append(" = ").append(v.strip()).append('\n');
            Settings.Setting s = Settings.named(key);
            carried.add((s == null ? key.substring("RESEARCHZOSHO_".length()).toLowerCase(Locale.ROOT) : s.shortName()) + " = " + v.strip());
        }
        if (!carried.isEmpty()) Files.writeString(home(to).resolve("config"), config.toString(), StandardCharsets.UTF_8);
        if (known != null) { store.libraryFolder(known.id(), to.toString()); store.libraryState(known.id(), "active"); keepCopy(known); }
        else store.addLibrary(id.id(), name, id.name(), keeper.id(), to.toString());
        // taken back from where it was set aside: what is left of that folder (its home, tmp, its log) goes with it, and its record. The record
        // names the folder as it was written down, which may be reached through a link (macOS's /var is /private/var, a root on a linked disk):
        // looked up as the library's real place and as it was given (round G: on macOS the folder was left, its record with it)
        Path set = null;
        for (Path p : new Path[]{from.getParent(), given.getParent()}) if (set == null && p != null && store.asideAt(p.toString()) != null) set = p;
        if (renamed && set != null) { remove(set); store.asideGone(set.toString()); }
        return new Taken(store.library(id.id()), from, renamed, readers, keys, carried);
    }

    /**
     * A library let go: out of the service, its library folder moved to {@code to} as an ordinary library, where it is served as any
     * library of a person's own is; its own readers and keys, which were never removed from its files, work there again. The rest of
     * its folder here (its settings under the service, its log) and its copy are set aside. The library must be out of service.
     */
    public Path release(AccountStore.Library lib, Path to) throws IOException {
        Path dest = to.toAbsolutePath().normalize();
        if (Files.exists(dest) && !(Files.isDirectory(dest) && isEmpty(dest))) throw new IOException("There is something at " + dest + " already; nothing was let go.");
        if (Files.isDirectory(dest)) Files.delete(dest);
        Files.createDirectories(dest.getParent());
        move(library(lib), dest);
        for (String f : new String[]{"serve.lock", "serve.lock.guard"}) Files.deleteIfExists(dest.resolve("catalog").resolve(f));
        aside(lib, folder(lib), "released");
        copyAside(lib, "released");
        return dest;
    }

    private static boolean isEmpty(Path dir) throws IOException { try (Stream<Path> s = Files.list(dir)) { return s.findAny().isEmpty(); } }

    // ---- small things ----

    static String rel(Path base, Path p) { return base.relativize(p).toString().replace('\\', '/'); }

    /** One file written to a name of its own beside its place and then put there, so the place never holds half a file; its time kept. */
    static void put(Path from, Path to, long time) throws IOException {
        Files.createDirectories(to.getParent());
        Path half = to.resolveSibling(to.getFileName() + HALF);
        Files.copy(from, half, StandardCopyOption.REPLACE_EXISTING, LinkOption.NOFOLLOW_LINKS);
        Files.setLastModifiedTime(half, FileTime.fromMillis(time));
        try { Files.move(half, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException e) { Files.move(half, to, StandardCopyOption.REPLACE_EXISTING); }
    }

    /** A name the copy's list can hold: no line break, carriage return or tab, each of which is a line or a column of the list. */
    static boolean listable(String rel) { return rel.indexOf('\n') < 0 && rel.indexOf('\r') < 0 && rel.indexOf('\t') < 0; }

    /** A path read back from the list that stays inside the folder it is under: relative, with no part that is "..", "." or empty. */
    static boolean safeRel(String rel) {
        if (rel == null || rel.isEmpty() || !listable(rel) || rel.startsWith("/") || rel.indexOf('\\') >= 0 || rel.indexOf('\0') >= 0 || rel.matches("^[A-Za-z]:.*")) return false;
        for (String part : rel.split("/", -1)) if (part.isEmpty() || part.equals("..") || part.equals(".")) return false;
        return true;
    }

    /** A folder on the way to a file was a link when it was opened: the file is not read through it. */
    static final class LinkOnTheWay extends IOException {
        LinkOnTheWay(String rel) { super("a link on the way to " + rel); }
    }

    /**
     * A file of a library that may be running copied into its copy, opened from the library's folder one name at a time with no link
     * followed at any step, so a folder the library swaps for a link after the walk saw it is never read through (round F, the second
     * review: a container's link to another library's folder landed in its own copy and take-home archive).
     */
    static void putFrom(Path root, String rel, Path to, long time) throws IOException {
        if (!safeRel(rel)) throw new LinkOnTheWay(rel);
        Files.createDirectories(to.getParent());
        Path half = to.resolveSibling(to.getFileName() + HALF);
        try (InputStream in = openNoFollow(root, rel)) { Files.copy(in, half, StandardCopyOption.REPLACE_EXISTING); }
        Files.setLastModifiedTime(half, FileTime.fromMillis(time));
        try { Files.move(half, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException e) { Files.move(half, to, StandardCopyOption.REPLACE_EXISTING); }
    }

    /** {@code rel} under {@code root}, read with no link followed: through the open folders where the system can (openat), else by looking at each part first. */
    static InputStream openNoFollow(Path root, String rel) throws IOException {
        String[] parts = rel.split("/");
        try (DirectoryStream<Path> top = Files.newDirectoryStream(root)) {
            if (top instanceof SecureDirectoryStream<Path> at) {
                List<SecureDirectoryStream<Path>> opened = new ArrayList<>();
                try {
                    SecureDirectoryStream<Path> here = at;
                    for (int i = 0; i < parts.length - 1; i++) {
                        try { here = here.newDirectoryStream(Path.of(parts[i]), LinkOption.NOFOLLOW_LINKS); }
                        catch (NoSuchFileException gone) { throw gone; }
                        catch (IOException notAFolder) { throw new LinkOnTheWay(rel); }   // a link (ELOOP) or no longer a folder
                        opened.add(here);
                    }
                    SeekableByteChannel ch;
                    try { ch = here.newByteChannel(Path.of(parts[parts.length - 1]), Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)); }
                    catch (NoSuchFileException gone) { throw gone; }
                    catch (IOException link) { if (Files.isSymbolicLink(root.resolve(rel))) throw new LinkOnTheWay(rel); throw link; }
                    return Channels.newInputStream(ch);
                } finally { for (SecureDirectoryStream<Path> d : opened) d.close(); }
            }
        }
        Path p = root;
        for (String part : parts) { p = p.resolve(part); if (Files.isSymbolicLink(p)) throw new LinkOnTheWay(rel); }
        return Files.newInputStream(p, LinkOption.NOFOLLOW_LINKS);
    }

    /** The ending of a file being written into a copy. */
    static final String HALF = ".rz-copying";

    /** A pass that was interrupted left files half written: they go. */
    private static void clearHalfWritten(Path files) throws IOException {
        try (Stream<Path> s = Files.walk(files)) { for (Path p : s.filter(p -> p.getFileName().toString().endsWith(HALF)).toList()) Files.deleteIfExists(p); }
        catch (UncheckedIOException e) { throw e.getCause(); }
    }

    /** What the copy holds, by its list: a line a path, with its size (-1: a folder) and its time. */
    private static Map<String, Seen> manifest(Path copy) throws IOException {
        Map<String, Seen> m = new LinkedHashMap<>();
        Path f = copy.resolve("manifest.tsv");
        if (!Files.isRegularFile(f)) return m;
        for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
            String[] p = line.split("\t");
            if (p.length != 3 || !safeRel(p[2])) continue;   // a line that names a place outside the folder is no line of the list
            try { m.put(p[2], new Seen(Long.parseLong(p[0]), Long.parseLong(p[1]))); } catch (NumberFormatException skip) { /* a line of another kind */ }
        }
        return m;
    }

    private static void writeManifest(Path copy, Map<String, Seen> m) throws IOException {
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, Seen> e : m.entrySet()) b.append(e.getValue().size()).append('\t').append(e.getValue().time()).append('\t').append(e.getKey()).append('\n');
        writeWhole(copy.resolve("manifest.tsv"), b.toString());
    }

    private static void writeWhole(Path f, String text) throws IOException {
        Path half = f.resolveSibling(f.getFileName() + HALF);
        Files.writeString(half, text, StandardCharsets.UTF_8);
        try { Files.move(half, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException e) { Files.move(half, f, StandardCopyOption.REPLACE_EXISTING); }
    }
}
