package org.researchzosho.librarian;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * What the findings shelf held when it was last read (0.8.0). The pages, the status call and the chat ask for every finding, some of
 * them thirty times for one page, and each ask read every file of the shelf again: 2,346 files for a library of 6.9 GB, 340,000 file
 * calls for the page of past work. Here a file is read again only when it is new, or its size, its modified time or its identity on
 * the disk changed since it was read; a file gone is forgotten. Each ask still lists the folder and looks at each file's size and
 * time, so a finding written by another process (the command line beside the service, a job in another thread) is seen at the next
 * ask, whatever this process did meanwhile. One finding asked for by its id is read the same way. A file modified within {@link #SETTLED_MS} of its reading is read again at every ask
 * until it has settled: on a disk that keeps times to the second, two writes in one second with the same size look the same.
 *
 * <p>What it holds: per shelf, each file's name, size, time and identity, and the finding read from it (or the reason it could not
 * be read). For a library of 6.9 GB that is 2,346 findings from 2.4 MB of files, 5.5 MB of heap (measured 2026-11-17). At most
 * {@link #SHELVES} shelves are kept (the least recently asked goes first), and of one shelf at most {@link #MAX_BYTES} of files, about
 * 37 MB of heap at that rate: a file beyond that is read at every ask, as before.
 */
final class FindingsCache {
    private FindingsCache() { }

    /** How long after its last modification a file must have been read for its reading to be kept. */
    static final long SETTLED_MS = 2_000;
    /** The most shelves kept, and the most bytes of files kept of one shelf. */
    static final int SHELVES = 4;
    static volatile long MAX_BYTES = 16L * 1024 * 1024;

    /** How many files were read from the disk: a test counts by it. */
    static final AtomicLong READS = new AtomicLong();

    /** One file as it was read: its size, time and identity then, when it was read, and the finding or the reason it is not one. */
    private record Entry(long size, long modified, Object key, long readAt, Finding finding, String problem) {
        boolean same(BasicFileAttributes a) { return a.size() == size && a.lastModifiedTime().toMillis() == modified && Objects.equals(a.fileKey(), key); }
        boolean settled() { return readAt - modified >= SETTLED_MS; }
    }

    private static final class Shelf {
        final Map<String, Entry> files = new ConcurrentHashMap<>();
        final AtomicLong bytes = new AtomicLong();
    }

    private static final Map<Path, Shelf> SHELVES_KEPT = new LinkedHashMap<>(16, 0.75f, true);

    private static Shelf shelf(Path dir) {
        Path key = dir.toAbsolutePath().normalize();
        synchronized (SHELVES_KEPT) {
            Shelf s = SHELVES_KEPT.computeIfAbsent(key, k -> new Shelf());
            while (SHELVES_KEPT.size() > SHELVES) { Path eldest = SHELVES_KEPT.keySet().iterator().next(); SHELVES_KEPT.remove(eldest); }
            return s;
        }
    }

    /** Every shelf forgotten (a test's). */
    static void clear() { synchronized (SHELVES_KEPT) { SHELVES_KEPT.clear(); } }

    /** The findings of the shelf {@code dir} and the files that are not findings, in the order of their names; each caller gets findings of its own. */
    static LibraryStore.Scan scan(Path dir) {
        List<Finding> ok = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        if (!Files.isDirectory(dir)) return new LibraryStore.Scan(ok, problems);
        Shelf shelf = shelf(dir);
        List<Path> names;
        try (var files = Files.list(dir)) { names = files.sorted(Comparator.comparing(Path::getFileName)).toList(); }
        catch (IOException e) { problems.add("findings/: " + e.getMessage()); return new LibraryStore.Scan(ok, problems); }
        List<String> here = new ArrayList<>(names.size());
        for (Path p : names) {
            String name = p.getFileName().toString();
            if (!name.endsWith(".md")) continue;
            here.add(name);
            Entry e = shelf.files.get(name);
            BasicFileAttributes a;
            try { a = Files.readAttributes(p, BasicFileAttributes.class); } catch (IOException gone) { a = null; }
            if (e == null || a == null || !e.same(a) || !e.settled()) e = read(shelf, name, p, a);
            if (e.finding() != null) ok.add(own(e.finding()));
            else problems.add(e.problem());
        }
        if (shelf.files.size() > here.size()) {   // the files gone since the last ask are forgotten
            List<String> gone = new ArrayList<>(shelf.files.keySet());
            gone.removeAll(here);
            synchronized (shelf) { for (String g : gone) { Entry was = shelf.files.remove(g); if (was != null) shelf.bytes.addAndGet(-was.size()); } }
        }
        return new LibraryStore.Scan(ok, problems);
    }

    /**
     * One finding by its file, {@code p} in the shelf {@code dir}, as {@link #scan} would give it: the reading kept when the file is
     * still the one read, else read now. Null when the file is not a finding: the caller reads it itself and says why.
     */
    static Finding one(Path dir, Path p) {
        Shelf shelf = shelf(dir);
        String name = p.getFileName().toString();
        BasicFileAttributes a;
        try { a = Files.readAttributes(p, BasicFileAttributes.class); } catch (IOException gone) { return null; }
        Entry e = shelf.files.get(name);
        if (e == null || !e.same(a) || !e.settled()) e = read(shelf, name, p, a);
        return e.finding() == null ? null : own(e.finding());
    }

    /** One file read, and kept when it settled before it was read and the shelf has room. */
    private static Entry read(Shelf shelf, String name, Path p, BasicFileAttributes a) {
        long readAt = System.currentTimeMillis();
        Finding f = null; String problem = null;
        try {
            READS.incrementAndGet();
            f = Finding.parse(Files.readString(p, StandardCharsets.UTF_8));
            if (!name.equals(f.id() + ".md")) { problem = name + ": id '" + f.id() + "' does not match filename"; f = null; }
        } catch (Exception e) { problem = name + ": " + e.getMessage(); f = null; }
        Entry made = new Entry(a == null ? -1 : a.size(), a == null ? -1 : a.lastModifiedTime().toMillis(), a == null ? null : a.fileKey(), readAt, f, problem);
        synchronized (shelf) {   // one change of a shelf at a time: two reads of one file at once count it once (ROB 11)
            Entry was = shelf.files.remove(name);
            if (was != null) shelf.bytes.addAndGet(-was.size());
            if (a != null && shelf.bytes.get() + made.size() <= MAX_BYTES) { Entry old = shelf.files.put(name, made); shelf.bytes.addAndGet(made.size() - (old == null ? 0 : old.size())); }
        }
        return made;
    }

    /** A finding of the caller's own: its lists are fresh where reading the file makes fresh ones, so what one caller changes no other sees. */
    private static Finding own(Finding f) {
        return new Finding(f.id(), f.title(), fresh(f.subjects()), f.state(), f.claimType(), f.confidence(), f.writer(), f.recordedAt(), f.validAsOf(),
                f.volatility(), f.reviewBy(), fresh(f.sources()), fresh(f.supersedes()), f.review(), f.body(), f.triple(), f.notes());
    }

    private static <T> List<T> fresh(List<T> l) { return l instanceof ArrayList<T> ? new ArrayList<>(l) : l; }
}
