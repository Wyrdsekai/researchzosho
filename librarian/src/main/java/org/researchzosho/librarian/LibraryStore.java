package org.researchzosho.librarian;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.researchzosho.Config;

/**
 * The library on disk — plain markdown files in one directory tree, no service required to
 * read your own knowledge (the architecture notes). The corpus is PRIVATE; only this code is
 * ever published. Git on the directory is the person's choice; nothing here depends on it.
 *
 * <pre>
 *   raw/            immutable captured sources (provenance stamped at capture, never later)
 *   extracts/       per-source distillations
 *   findings/       F-*.md — the atomic units ({@link Finding})
 *   investigations/ I-*.md — one per research run ({@link Investigation})
 *   articles/       per-subject syntheses (the abstracting crew, later)
 *   frontier/       OPEN.md — the open-questions ledger
 *   catalog/        INDEX.md (human browsing), subjects.md (controlled vocabulary),
 *                   sources.md (source-health notes), circulation.log (usage)
 *   .index/         Lucene — derived, rebuildable, never the source of truth
 * </pre>
 *
 * <p>Reads FAIL CLOSED per entry: a malformed file becomes a named problem for the librarian
 * to surface, never a silent skip (a store that quietly drops what it cannot parse is a store
 * whose contents you cannot trust) — and never a crash that takes the readable corpus down
 * with it.
 */
public final class LibraryStore {

    private final Path root;

    public LibraryStore(Path root) {
        this.root = root;
    }

    /**
     * The configured library home: {@code RESEARCHZOSHO_LIBRARY} (or {@code CODEZAIKU_LIBRARY}), else
     * {@code ~/researchzosho-library} — or {@code ~/codezaiku-library} when that is the one that exists,
     * so a library built before the split is found where it is.
     */
    public static LibraryStore open() {
        String override = Config.get("RESEARCHZOSHO_LIBRARY");
        Path p = (override != null && !override.isBlank())
                ? Path.of(override)
                : defaultRoot(Path.of(System.getProperty("user.home")));
        return new LibraryStore(p);
    }

    static Path defaultRoot(Path userHome) {
        Path now = userHome.resolve("researchzosho-library");
        Path was = userHome.resolve("codezaiku-library");
        return (!Files.isDirectory(now) && Files.isDirectory(was)) ? was : now;
    }

    public Path root() { return root; }
    public Path rawDir() { return root.resolve("raw"); }
    public Path extractsDir() { return root.resolve("extracts"); }
    public Path findingsDir() { return root.resolve("findings"); }
    public Path investigationsDir() { return root.resolve("investigations"); }
    public Path articlesDir() { return root.resolve("articles"); }
    public Path frontierFile() { return root.resolve("frontier").resolve("OPEN.md"); }
    public Path indexFile() { return root.resolve("catalog").resolve("INDEX.md"); }
    public Path subjectsFile() { return root.resolve("catalog").resolve("subjects.md"); }
    public Path sourcesFile() { return root.resolve("catalog").resolve("sources.md"); }
    public Path circulationFile() { return root.resolve("catalog").resolve("circulation.log"); }
    public Path luceneDir() { return root.resolve(".index"); }
    public Path libraryFile() { return root.resolve("catalog").resolve("library.md"); }

    /** This corpus's identity: a stable id minted once, and a name the person may change. */
    public record Identity(String id, String name) { }

    /**
     * The library's identity for the protocol (docs/LIBRARY_PROTOCOL.md §2). The id is minted the
     * first time anyone asks and never changes across renames or moves — a patron cites
     * "F-0412 in {@code lib_…}" months later. The name defaults to the directory's.
     */
    public synchronized Identity identity() throws IOException {
        Path p = libraryFile();
        String id = null, name = null;
        if (Files.exists(p)) {
            for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                if (line.startsWith("id: ")) id = line.substring(4).strip();
                else if (line.startsWith("name: ")) name = line.substring(6).strip();
            }
        }
        String dirName = root.getFileName() == null ? "library" : root.getFileName().toString();
        if (name == null || name.isBlank()) name = dirName;
        if (id == null || id.isBlank()) {
            byte[] b = new byte[8];
            new java.security.SecureRandom().nextBytes(b);
            id = "lib_" + java.util.HexFormat.of().formatHex(b);
            Files.createDirectories(p.getParent());
            if (Files.exists(p)) {
                // the file already carries other lines (a `profiles:` line, say): insert the id, keep the rest
                // (measured live 2026-09-07: minting the id rewrote the file and lost the enabled profiles)
                Files.writeString(p, Files.readString(p, StandardCharsets.UTF_8).replaceFirst("(?m)^(# This library\\s*\\n)", "$1\nid: " + id + "\n"), StandardCharsets.UTF_8);
                if (!Files.readString(p, StandardCharsets.UTF_8).contains("id: " + id)) {
                    Files.writeString(p, "id: " + id + "\n" + Files.readString(p, StandardCharsets.UTF_8), StandardCharsets.UTF_8);
                }
            } else {
                // the id only: the name follows the directory until the person adds a `name:` line
                Files.writeString(p, "# This library\n\nid: " + id + "\n\n"
                        + "The id was minted once and never changes; patrons cite entries by it. Add a line\n"
                        + "`name: …` to give the library a name other than its directory's.\n",
                        StandardCharsets.UTF_8);
            }
        }
        return new Identity(id, name);
    }

    /**
     * Name the library: the {@code name:} line of {@code catalog/library.md}, which the pages, the status and every
     * protocol answer show as {@code library_name}. Without one the name is the folder's, and a folder made before the
     * split was called codezaiku-library, which read as a product label on the pages (2026-09-11). Blank = the folder.
     */
    public synchronized void setName(String name) throws IOException {
        identity();   // makes sure the file and the id exist
        Path p = libraryFile();
        String text = Files.readString(p, StandardCharsets.UTF_8);
        String clean = name == null ? "" : name.strip().replaceAll("\\s+", " ");
        String line = clean.isEmpty() ? "" : "name: " + clean + "\n";
        if (text.matches("(?s).*(?m)^name: .*")) text = text.replaceFirst("(?m)^name: .*\\n?", line);
        else text = text.replaceFirst("(?m)^(id: .*\\n)", "$1" + line.replace("$", "\\$"));
        Files.writeString(p, text, StandardCharsets.UTF_8);
    }

    /** Create the tree and seed the catalog files. Idempotent — never touches existing content. */
    public synchronized void init() throws IOException {
        for (Path d : new Path[]{rawDir(), extractsDir(), findingsDir(), investigationsDir(),
                articlesDir(), frontierFile().getParent(), indexFile().getParent(), luceneDir()}) {
            Files.createDirectories(d);
        }
        seed(frontierFile(), "# Frontier — open questions\n\n");
        seed(indexFile(), "# Index\n\n(regenerated by the librarian — do not edit by hand)\n\n");
        seed(subjectsFile(), "# Subjects — the controlled vocabulary\n\n"
                + "One canonical subject per line, `slug — description`. The cataloger grounds\n"
                + "entries to THESE; new vocabulary is the person's call, never minted silently.\n\n");
        seed(sourcesFile(), "# Source health\n\nLearned notes about sources: blocked hosts,"
                + " paywall behavior, reliability observations.\n\n");
    }

    private static void seed(Path p, String content) throws IOException {
        if (!Files.exists(p)) Files.writeString(p, content, StandardCharsets.UTF_8);
    }

    // ---- ids -------------------------------------------------------------------

    /** Slug a title: lowercase ascii + hyphens, capped. Non-ascii titles (JA) fall back to "entry". */
    public static String slug(String title) {
        String s = title == null ? "" : title.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (s.length() > 40) {
            s = s.substring(0, 40).replaceAll("-+$", "");
        }
        return s.isEmpty() ? "entry" : s;
    }

    /**
     * Ids are NEVER reused. The serial is the max of what is on disk and what the ledger
     * ({@code catalog/serials.txt}) says was ever issued — a deleted file must not free its
     * number, because a patron may already cite "F-0022 in lib_…" (it happened: a smoke-test
     * draft was removed and Wyrdsekai's next submission became a different F-0022, 2026-09-03).
     */
    public synchronized String nextFindingId(String title) throws IOException {
        return "F-" + String.format("%04d", issue("F", findingsDir())) + "-" + slug(title);
    }

    public synchronized String nextInvestigationId(String title) throws IOException {
        return "I-" + String.format("%04d", issue("I", investigationsDir())) + "-" + slug(title);
    }

    public Path serialsFile() { return root.resolve("catalog").resolve("serials.txt"); }

    /** Job ids for the daemon's ledger: J-<serial>, never reused. */
    public synchronized String nextJobId() throws IOException {
        return "J-" + String.format("%04d", issue("J", root.resolve("catalog").resolve("jobs")));
    }

    private int issue(String prefix, Path dir) throws IOException {
        java.util.Map<String, Integer> ledger = new java.util.TreeMap<>();
        if (Files.exists(serialsFile())) {
            for (String line : Files.readAllLines(serialsFile(), StandardCharsets.UTF_8)) {
                int eq = line.indexOf('=');
                if (eq > 0) try { ledger.put(line.substring(0, eq).strip(), Integer.parseInt(line.substring(eq + 1).strip())); } catch (NumberFormatException ignored) { }
            }
        }
        int next = Math.max(maxSerial(dir, prefix), ledger.getOrDefault(prefix, 0)) + 1;
        ledger.put(prefix, next);
        StringBuilder sb = new StringBuilder("# highest serial ever issued per prefix — never reuse an id\n");
        for (var e : ledger.entrySet()) sb.append(e.getKey()).append('=').append(e.getValue()).append('\n');
        Files.createDirectories(serialsFile().getParent());
        Files.writeString(serialsFile(), sb.toString(), StandardCharsets.UTF_8);
        return next;
    }

    private static int maxSerial(Path dir, String prefix) throws IOException {
        if (!Files.isDirectory(dir)) return 0;
        int max = 0;
        try (var files = Files.list(dir)) {
            for (Path p : files.toList()) {
                var m = java.util.regex.Pattern.compile(prefix + "-(\\d+)-.*\\.md")
                        .matcher(p.getFileName().toString());
                if (m.matches()) max = Math.max(max, Integer.parseInt(m.group(1)));
            }
        }
        return max;
    }

    // ---- write -----------------------------------------------------------------

    /**
     * Write a finding. Round-trips through {@link Finding#parse} first, so a Finding this code
     * assembled wrong is rejected HERE, before it pollutes the store — the same fail-closed
     * gate a hand-edited file meets on read.
     */
    public synchronized Path write(Finding f) throws IOException {
        String text = f.format();
        Finding check = Finding.parse(text);
        if (!check.id().equals(f.id())) throw new IllegalStateException("id drifted in round-trip");
        Path p = findingsDir().resolve(f.id() + ".md");
        Files.createDirectories(p.getParent());
        Finding before = null;
        if (Files.exists(p)) { try { before = Finding.parse(Files.readString(p, StandardCharsets.UTF_8)); } catch (Exception ignored) { } }
        Files.writeString(p, text, StandardCharsets.UTF_8);
        Changes.noteFinding(this, before, check);   // recall notices: patrons cite ids
        return p;
    }

    public synchronized Path write(Investigation inv) throws IOException {
        String text = inv.format();
        Investigation check = Investigation.parse(text);
        Path p = investigationsDir().resolve(inv.id() + ".md");
        Files.createDirectories(p.getParent());
        Investigation before = null;
        if (Files.exists(p)) { try { before = Investigation.parse(Files.readString(p, StandardCharsets.UTF_8)); } catch (Exception ignored) { } }
        Changes.noteInvestigation(this, before, check);
        Files.writeString(p, text, StandardCharsets.UTF_8);
        return p;
    }

    // ---- read ------------------------------------------------------------------

    /** A scan of the findings shelf: what parsed, and what did not (named, for the librarian). */
    public record Scan(List<Finding> findings, List<String> problems) { }

    public Scan scanFindings() {
        List<Finding> ok = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        Path dir = findingsDir();
        if (!Files.isDirectory(dir)) return new Scan(ok, problems);
        try (var files = Files.list(dir)) {
            for (Path p : files.sorted(Comparator.comparing(Path::getFileName)).toList()) {
                String name = p.getFileName().toString();
                if (!name.endsWith(".md")) continue;
                try {
                    Finding f = Finding.parse(Files.readString(p, StandardCharsets.UTF_8));
                    if (!name.equals(f.id() + ".md")) {
                        problems.add(name + ": id '" + f.id() + "' does not match filename");
                    } else {
                        ok.add(f);
                    }
                } catch (Exception e) {
                    problems.add(name + ": " + e.getMessage());
                }
            }
        } catch (IOException e) {
            problems.add("findings/: " + e.getMessage());
        }
        return new Scan(ok, problems);
    }

    /**
     * Whether {@code name} is a plain shelf name — one path segment, no separators, no {@code ..} —
     * the only shape an id or a raw file name may have. Everything that joins a caller-supplied id
     * onto a shelf directory checks this first: an id like {@code ../catalog/library.md} once read
     * the config file back through {@code library_get} (found by Wyrdsekai, 2026-09-07).
     */
    public static boolean safeName(String name) {
        if (name == null || name.isBlank()) return false;
        if (name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.indexOf('\0') >= 0) return false;
        if (name.equals(".") || name.equals("..")) return false;
        return true;
    }

    /** {@code dir/name}, or null when {@code name} is not a shelf name or the result would leave {@code dir}. */
    public static Path under(Path dir, String name) {
        if (!safeName(name)) return null;
        Path base = dir.toAbsolutePath().normalize();
        Path p = base.resolve(name).normalize();
        return p.startsWith(base) && !p.equals(base) ? p : null;
    }

    /** One finding by id, or null when absent. Malformed throws — a named entry must not lie. */
    public Finding finding(String id) throws IOException {
        Path p = under(findingsDir(), id + ".md");
        if (p == null) return null;
        if (!Files.exists(p)) return null;
        return Finding.parse(Files.readString(p, StandardCharsets.UTF_8));
    }

    public Investigation investigation(String id) throws IOException {
        Path p = under(investigationsDir(), id + ".md");
        if (p == null) return null;
        if (!Files.exists(p)) return null;
        return Investigation.parse(Files.readString(p, StandardCharsets.UTF_8));
    }

    // ---- cross-process locks ----------------------------------------------------

    /** Work that may throw IOException, run under a lock. */
    public interface Locked<T> { T run() throws IOException; }

    private static final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.locks.ReentrantLock> JVM_LOCKS = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Run {@code work} under a lock other PROCESSES honour too: a {@code catalog/.lock-<name>} file lock,
     * inside a JVM-wide lock of the same name (a FileChannel lock is per process, and two threads of one
     * JVM would otherwise collide on it). The daemon and a command line are two JVMs on one library, and
     * the frontier, the shelves file and the changes log are read-modify-write (Wyrdsekai, 2026-09-07).
     */
    public <T> T locked(String name, Locked<T> work) throws IOException {
        String key = root.toAbsolutePath() + "|" + name;
        java.util.concurrent.locks.ReentrantLock jvm = JVM_LOCKS.computeIfAbsent(key, k -> new java.util.concurrent.locks.ReentrantLock());
        jvm.lock();
        try {
            if (jvm.getHoldCount() > 1) return work.run();   // re-entrant: the file lock is already ours on this thread
            Path lockFile = root.resolve("catalog").resolve(".lock-" + name);
            Files.createDirectories(lockFile.getParent());
            try (java.nio.channels.FileChannel ch = java.nio.channels.FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 java.nio.channels.FileLock ignored = ch.lock()) {
                return work.run();
            }
        } finally {
            jvm.unlock();
        }
    }

    // ---- frontier + circulation ------------------------------------------------

    /** Append an open question to the frontier ledger, dated and attributed. */
    public void frontier(String kind, String text) throws IOException {
        locked("frontier", () -> {
            Files.createDirectories(frontierFile().getParent());
            seed(frontierFile(), "# Frontier — open questions\n\n");
            String line = "- " + LocalDate.now() + " [" + kind + "] " + text.strip().replaceAll("\\s+", " ") + "\n";
            Files.writeString(frontierFile(), line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            return null;
        });
    }

    /** Log one circulation event (a desk query, a push, a submission). TSV: instant, kind, detail. */
    public synchronized void circulate(String kind, String detail) {
        try {
            Files.createDirectories(circulationFile().getParent());
            String line = Instant.now() + "\t" + kind + "\t"
                    + detail.strip().replaceAll("\\s+", " ") + "\n";
            Files.writeString(circulationFile(), line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // circulation is telemetry — it must never break the operation it observes
        }
    }

    // ---- migrations ------------------------------------------------------------

    /**
     * Re-sign reviews whose recorded hash equals the LEGACY formula (subjects were inside the
     * hash before 2026-09-02): the approved content is provably unchanged, so the approval
     * carries over with the current formula. Anything else stays as it is — a genuinely stale
     * review is never touched. Returns how many were re-signed.
     */
    public synchronized int migrateReviewHashes() throws IOException {
        int n = 0;
        for (Finding f : scanFindings().findings()) {
            if (f.review() == null || !f.reviewStale()) continue;
            if (!f.review().contentHash().equals(f.legacyContentHash())) continue;
            Finding.Review r = f.review();
            write(new Finding(f.id(), f.title(), f.subjects(), f.state(), f.claimType(), f.confidence(),
                    f.writer(), f.recordedAt(), f.validAsOf(), f.volatility(), f.reviewBy(), f.sources(),
                    f.supersedes(), new Finding.Review(r.round(), r.reviewer(), r.decision(), f.contentHash(), r.at()),
                    f.body(), f.triple(), f.notes()));
            n++;
        }
        return n;
    }

    // ---- catalog ---------------------------------------------------------------

    /** Regenerate INDEX.md — the human browsing surface, one line per entry, newest last. */
    public synchronized void regenerateIndex() throws IOException {
        StringBuilder sb = new StringBuilder(
                "# Index\n\n(regenerated by the librarian — do not edit by hand)\n\n## Findings\n\n");
        Scan scan = scanFindings();
        for (Finding f : scan.findings()) {
            sb.append("- [").append(f.state()).append("] ").append(f.id())
              .append(" — ").append(f.title());
            if (f.reviewStale()) sb.append("  (review STALE)");
            sb.append('\n');
        }
        sb.append("\n## Investigations\n\n");
        if (Files.isDirectory(investigationsDir())) {
            try (var files = Files.list(investigationsDir())) {
                for (Path p : files.sorted(Comparator.comparing(Path::getFileName)).toList()) {
                    String name = p.getFileName().toString();
                    if (!name.endsWith(".md")) continue;
                    try {
                        Investigation inv = Investigation.parse(Files.readString(p, StandardCharsets.UTF_8));
                        sb.append("- [").append(inv.state()).append("] ").append(inv.id())
                          .append(" — ").append(inv.title()).append('\n');
                    } catch (Exception e) {
                        sb.append("- MALFORMED ").append(name).append(" — ").append(e.getMessage()).append('\n');
                    }
                }
            }
        }
        if (!scan.problems().isEmpty()) {
            sb.append("\n## Problems (fail-closed reads — fix the files)\n\n");
            for (String prob : scan.problems()) sb.append("- ").append(prob).append('\n');
        }
        Files.createDirectories(indexFile().getParent());
        Files.writeString(indexFile(), sb.toString(), StandardCharsets.UTF_8);
    }
}
