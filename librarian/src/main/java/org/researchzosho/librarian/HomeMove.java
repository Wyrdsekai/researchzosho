package org.researchzosho.librarian;

import org.researchzosho.Config;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Each program in a folder of its own (0.8.3). Until 0.8.3 this program lived in {@code ~/.codezaiku} whenever that folder existed and
 * {@code ~/.researchzosho} did not: an install from before the split of 2026-09-06 kept its settings, and every ResearchZosho installed
 * beside a CodeZaiku after it lived in CodeZaiku's folder, shared its settings file and its model folder, and changed CodeZaiku's files
 * there. This moves such an install out, once, by itself: this program's own files to {@code ~/.researchzosho}, CodeZaiku's left as
 * they are. It runs at the start of any command when no service of this program runs, so the service's own start carries it out (an
 * update restarts the service), and as {@code researchzosho home move [--dry-run|--back]}.
 *
 * <p>The order keeps a working install in one of the two places at every step: everything is gathered in a stage beside the new
 * folder, with a journal ({@link #JOURNAL}), by renames on one disk (nothing is copied but the settings and two small shared files, nothing
 * is downloaded); the stage becomes {@code ~/.researchzosho} in one rename, the commit; what follows (the units' paths, the model folder's
 * scripts, the containers that mount a moved folder) is said when it cannot be done, and the move again finishes it. A failure before
 * the commit puts back every renamed entry by the journal: nothing moved. CodeZaiku's settings file is read, never changed.
 *
 * <p>No value of a setting is printed, logged or journaled: the plan, the paragraph and the journal carry names and counts.
 */
public final class HomeMove {
    private HomeMove() { }

    /** The journal: one line per step, names only. In the stage while it is gathered, in the new folder after the commit (the way back reads it). */
    public static final String JOURNAL = "moved.journal";

    /** Names both programs keep under their homes that this program copies: the original stays the other program's. */
    static final List<String> COPIED = List.of("search-tally.json", "openrouter-providers.json");
    /** Names both programs keep that stay where they are: a lock is made again where it is needed. */
    static final List<String> LEFT = List.of("update.lock");
    /** The Windows logon task's script ({@link Service#windowsTaskScript}): it moves, and the task is pointed at it in the new folder. */
    static final String TASK_SCRIPT = Service.WIN_TASK + ".ps1";
    /** CodeZaiku's names, from its code (2026-11-25): they stay. */
    static final List<String> THEIRS = List.of("sandbox", "git", "git-put-back", "git-guard", "envs", "research", "ocean", "library-cache",
            "knowledge-packs", "conventions", "spend.jsonl", "codezaiku-latest.txt", "chat", "web", "familiar-memory", "project-id", "oss-private-names",
            "audit.jsonl", "scratch");

    static Path userHome() { return Path.of(System.getProperty("user.home")); }
    static Path was() { return userHome().resolve(".codezaiku"); }
    static Path now() { return userHome().resolve(".researchzosho"); }
    static Path stage() { return userHome().resolve(".researchzosho-moving"); }

    /** The model folder's shape: this program's own install (moved whole), the other program's with this program's entries in it (taken out), or neither. */
    enum Model { OWN, THEIRS, NONE }

    /**
     * What a move would do: {@code why} null when there is something to move, else why there is not. {@code moved}: this program's
     * entries; {@code copied}; {@code stays}: the other program's and those both keep; {@code unknown}: names neither program's code makes,
     * which stay; {@code settings}: how many settings this program reads in the shared file; {@code units}: this program's unit files that
     * name the old folder; {@code containers}: this program's containers whose folder moves.
     */
    record Plan(String why, List<String> moved, List<String> copied, List<String> stays, List<String> unknown, Model model, int settings,
                boolean config, List<Path> units, List<String> containers) {
        boolean due() { return why == null; }
    }

    private static Plan none(String why) { return new Plan(why, List.of(), List.of(), List.of(), List.of(), Model.NONE, 0, false, List.of(), List.of()); }

    /** The plan for this machine's home, read from the folders, never changing anything. */
    static Plan plan() throws IOException {
        if (Config.env(Config.HOME_NAME) != null) return none("the folder is named by " + Config.HOME_NAME);
        Path was = was(), now = now();
        if (Files.isDirectory(now)) return none("this program has its own folder, " + now);
        if (!Files.isDirectory(was) || !Config.livesIn(was)) return none("nothing of this program's is in " + was);
        List<String> moved = new ArrayList<>(), copied = new ArrayList<>(), stays = new ArrayList<>(), unknown = new ArrayList<>(), containers = new ArrayList<>();
        Model model = Model.NONE;
        boolean config = false;
        try (Stream<Path> in = Files.list(was)) {
            for (Path p : in.sorted().toList()) {
                String n = p.getFileName().toString();
                if (n.equals("config")) { config = Config.env("RESEARCHZOSHO_CONFIG") == null; if (!config) stays.add(n); continue; }
                if (n.equals("model")) { model = modelShape(p); if (model == Model.OWN) moved.add(n); else stays.add(n); continue; }
                if (Config.OWN_NAMES.contains(n)) { moved.add(n); continue; }
                if (COPIED.contains(n)) { copied.add(n); continue; }
                if (LEFT.contains(n) || THEIRS.contains(n)) { stays.add(n); continue; }
                unknown.add(n);
            }
        }
        int settings = 0;
        if (config) settings = Config.readAsShared(was.resolve("config")).size();
        for (Map.Entry<String, String> c : FOLDERS.entrySet()) if (moved.contains(c.getValue().split("/")[0]) && mountsUnder(c.getKey(), was)) containers.add(c.getKey());
        return new Plan(null, moved, copied, stays, unknown, model, settings, config, unitsNaming(was), containers);
    }

    /** This program's containers and the folder of the home each mounts: made again when that folder moves. */
    static final Map<String, String> FOLDERS = orderedOf(Searx.CONTAINER, "searxng", TranslationScores.LABSE_CONTAINER, "labse",
            Embed.CONTAINER, "embed", Video.CONTAINER_WHISPER, "video/whisper");

    private static Map<String, String> orderedOf(String... kv) { Map<String, String> m = new LinkedHashMap<>(); for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]); return Collections.unmodifiableMap(m); }

    /** Whether this program's container of that name is there and mounts a folder under {@code folder}; Docker is only asked, nothing changed. */
    static boolean mountsUnder(String name, Path folder) {
        try {
            Searx.Result r = Searx.runner.run(List.of("docker", "inspect", "-f", "{{range .Mounts}}{{.Source}};{{end}}", name));
            if (r.code() != 0) return false;
            for (String src : r.out().strip().split(";")) if (under(src, folder)) return true;
        } catch (Exception ignored) { }
        return false;
    }

    /**
     * Whether a container's mount source lies under the folder: by the folder's own path, and on Windows also with forward slashes and as
     * Docker Desktop's Linux side names a folder of drive C ({@code /run/desktop/mnt/host/c/...}), without regard to case. Docker Desktop
     * was not on the Windows machine this was tried on: those two forms are what its documentation shows, not what was seen.
     */
    static boolean under(String src, Path folder) {
        String s = src.strip();
        if (s.isEmpty()) return false;
        boolean win = File.separatorChar == '\\';
        List<String> forms = new ArrayList<>(List.of(folder.toString() + File.separator));
        if (win) {
            String slashed = folder.toString().replace('\\', '/');
            forms.add(slashed + "/");
            if (slashed.length() > 2 && slashed.charAt(1) == ':') forms.add("/run/desktop/mnt/host/" + Character.toLowerCase(slashed.charAt(0)) + slashed.substring(2) + "/");
        }
        for (String f : forms) if (s.regionMatches(win, 0, f, 0, f.length())) return true;
        return false;
    }

    /** Whose model server the folder holds: by its start script on Linux; elsewhere by this program's own unit for it. */
    static Model modelShape(Path dir) throws IOException {
        String named = ModelServer.containerOf(dir.resolve("run.sh"), null);
        if (ModelServer.CONTAINER.equals(named)) return Model.OWN;
        if (named == null && Files.isRegularFile(dir.resolve("config.yaml")) && Files.readString(dir.resolve("config.yaml"), StandardCharsets.UTF_8).startsWith("# ResearchZosho")
                && !Files.isRegularFile(dir.resolve("run.sh"))) return Model.OWN;   // macOS or Windows: this program's config, no container
        boolean entry = Files.isRegularFile(dir.resolve("config.yaml")) && Files.readString(dir.resolve("config.yaml"), StandardCharsets.UTF_8).contains("  \"" + ModelServer.EMBED_NAME + "\":");
        boolean script = ModelServer.EMBED_CONTAINER.equals(ModelServer.containerOf(dir.resolve("run-embed.sh"), null));
        return entry || script ? Model.THEIRS : Model.NONE;
    }

    /** This program's own unit files (its names only) that name a path in the old folder. */
    static List<Path> unitsNaming(Path was) {
        List<Path> out = new ArrayList<>();
        Path home = userHome();
        List<Path> folders = List.of(home.resolve(".config").resolve("systemd").resolve("user"), home.resolve("Library").resolve("LaunchAgents"));
        for (Path f : folders) {
            if (!Files.isDirectory(f)) continue;
            try (Stream<Path> in = Files.list(f)) {
                for (Path u : in.sorted().toList()) {
                    String n = u.getFileName().toString();
                    if (!(n.endsWith(".service") || n.endsWith(".plist")) || n.startsWith("codezaiku") || n.startsWith("org.codezaiku.")) continue;
                    String t = Files.readString(u, StandardCharsets.UTF_8);
                    boolean ours = n.startsWith("researchzosho") || n.startsWith("org.researchzosho.") || t.toLowerCase(Locale.ROOT).contains("researchzosho");
                    if (ours && forms(was).stream().anyMatch(t::contains)) out.add(u);
                }
            } catch (IOException | RuntimeException ignored) { }
        }
        return out;
    }

    /** The plan in words, names only: what the dry run prints, and what the paragraph says it did. */
    static String said(Plan p, boolean done) {
        if (!p.due()) return "Nothing to move: " + p.why() + ".";
        String w = done ? "" : "would ";
        StringBuilder b = new StringBuilder();
        b.append("ResearchZosho ").append(done ? "kept" : "keeps").append(" its files in CodeZaiku's folder, ").append(was()).append(". ");
        b.append(done ? "They are now in " : "They would move to ").append(now()).append(".\n");
        if (p.config()) b.append("  settings: the ").append(p.settings()).append(" settings ResearchZosho reads in ").append(was().resolve("config"))
                .append(" ").append(w).append("go into its own settings file, with the values it reads there; CodeZaiku's file ").append(done ? "was" : "would be").append(" left as it is.\n");
        if (!p.moved().isEmpty()) b.append("  moved: ").append(String.join(", ", p.moved())).append('\n');
        if (!p.copied().isEmpty()) b.append("  copied (both programs keep one): ").append(String.join(", ", p.copied())).append('\n');
        if (p.model() == Model.THEIRS) b.append("  the model server in ").append(was().resolve("model")).append(" is CodeZaiku's: ResearchZosho's embeddings entry and its start script ")
                .append(done ? "were" : "would be").append(" taken out of it, for an embeddings server of ResearchZosho's own\n");
        if (!p.units().isEmpty()) b.append("  services whose paths ").append(done ? "were" : "would be").append(" changed: ").append(String.join(", ", p.units().stream().map(u -> u.getFileName().toString()).toList())).append('\n');
        if (!p.containers().isEmpty()) b.append("  containers ").append(done ? "made" : "to be made").append(" again with the new folder: ").append(String.join(", ", p.containers())).append('\n');
        if (!p.stays().isEmpty()) b.append("  left in ").append(was()).append(" (CodeZaiku's, or both programs'): ").append(String.join(", ", p.stays())).append('\n');
        if (!p.unknown().isEmpty()) b.append("  left, neither program's: ").append(String.join(", ", p.unknown())).append('\n');
        return b.toString();
    }

    /** The dry run: what a move would do, names only. */
    public static String dryRun() {
        try {
            String left = Files.isDirectory(stage()) ? "A move that stopped before it finished left " + stage() + " (" + String.join(", ", journalOf(stage()).stream()
                    .filter(l -> l.startsWith("moved ")).map(l -> l.substring(6)).toList()) + "); the next start of ResearchZosho puts those back first.\n" : "";
            return left + said(plan(), false);
        } catch (Exception e) { return "The move could not be planned: " + e.getMessage(); }
    }

    /** Whether a service of this program runs from the old folder: then the move waits for that service's next start, which carries it out. */
    static boolean serviceRuns() {
        for (Path pid : List.of(was().resolve("researchzosho.pid"), now().resolve("researchzosho.pid")))   // the old folder before a move, the new one after it (the way back)
            if (Service.runningServer(pid) != null || Service.cannotTell(pid) != null) return true;
        return false;
    }

    /**
     * At the start of a command: the move, when one is due and no service of this program runs (a job runs only inside the service). What
     * it did is said on {@code err}, which an MCP server's protocol does not read. Quiet when there is nothing to move.
     */
    public static void atStart(String verb, PrintStream err) {
        if (List.of("", "home", "help", "--help", "-h", "--version", "version", "-V").contains(verb)) return;
        try {
            recover();
            if (Config.env(Config.HOME_NAME) != null || Files.isDirectory(now()) || !Files.isDirectory(was()) || !Config.livesIn(was()) || serviceRuns()) return;   // the plan's own first checks, without asking Docker
            String r = run();
            if (!r.isEmpty()) err.println(r.startsWith("!") ? "ResearchZosho did not move its files out of CodeZaiku's folder: " + r.substring(1) : r);
            if (reloading) {   // launchd stops this process to load the agent again; nothing has started yet
                err.flush();
                try { Thread.sleep(RELOAD_WAIT_MS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                reloading = false;
                err.println("ResearchZosho's service was not loaded again with its new folder; it goes on, and it is loaded again at your next login (or now: researchzosho service stop, then researchzosho service start).");
            }
        } catch (Exception e) { err.println("ResearchZosho did not move its files out of CodeZaiku's folder: " + e.getMessage()); }
    }

    /** {@code researchzosho home move [--dry-run|--back]}. */
    public static int command(String[] args, int from, PrintStream out) {
        List<String> a = List.of(args).subList(Math.min(from, args.length), args.length);
        if (a.isEmpty() || !a.get(0).equals("move")) { out.println("usage: researchzosho home move [--dry-run|--back]"); return 2; }
        if (a.contains("--dry-run")) { out.print(dryRun()); return 0; }
        try {
            recover();
            if (serviceRuns()) { out.println("ResearchZosho's service runs from " + was() + ": it moves its files itself at its next start (researchzosho service restart starts it again now)."); return 1; }
            String r = a.contains("--back") ? back() : run();
            if (r.isEmpty()) { out.println(said(plan(), false)); return 0; }
            out.println(r.startsWith("!") ? "Nothing was moved: " + r.substring(1) : r);
            return r.startsWith("!") ? 1 : 0;
        } catch (Exception e) { out.println("Nothing was moved: " + e.getMessage()); return 1; }
    }

    // ---- the move ----

    private static void journal(Path folder, String line) throws IOException {
        Files.writeString(folder.resolve(JOURNAL), line + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    static List<String> journalOf(Path folder) {
        try { return Files.isRegularFile(folder.resolve(JOURNAL)) ? Files.readAllLines(folder.resolve(JOURNAL), StandardCharsets.UTF_8) : List.of(); }
        catch (IOException e) { return List.of(); }
    }

    static Path lockFile() { return userHome().resolve(".researchzosho-moving.lock"); }

    /** The move's lock, held across processes (the system lets it go when a process dies); null when another process holds it and did not let go in time. */
    private static FileLock lock(long waitMs) throws IOException {
        FileChannel ch = FileChannel.open(lockFile(), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        long until = System.currentTimeMillis() + waitMs;
        while (true) {
            FileLock l;
            try { l = ch.tryLock(); } catch (OverlappingFileLockException e) { l = null; }   // this process holds it already, in another thread
            if (l != null) return l;
            if (System.currentTimeMillis() > until) { ch.close(); return null; }
            try { Thread.sleep(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); ch.close(); return null; }
        }
    }

    private static void unlock(FileLock l) {
        if (l == null) return;
        try { l.release(); l.channel().close(); } catch (IOException ignored) { }
        try { Files.deleteIfExists(lockFile()); } catch (IOException ignored) { }   // after the commit nothing is due, so a second process that still waits finds nothing to do
    }

    /** A rename on one disk, never a copy: a folder of gigabytes moves in a moment or not at all. */
    private static void rename(Path from, Path to) throws IOException {
        try { Files.move(from, to, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException e) { throw new IOException("the two folders are on different disks, so " + from.getFileName() + " would have to be copied; the move renames only"); }
        catch (AccessDeniedException e) {   // Windows: a folder with a file some program holds open cannot be renamed (tried on Windows 11, 2026-11-25)
            if (ModelServer.os != ModelServer.Os.windows) throw e;
            throw new IOException("a program has a file in " + from.getFileName() + " open, and Windows does not let a folder move while it does; close that program (or restart the computer) and start ResearchZosho again");
        }
    }

    /** A test's hook: called between two steps of the move with the step's name; a test that throws there stands for a failure at that point. */
    static volatile Consumer<String> STEP = s -> { };

    /**
     * The move. "" when there is nothing to move; "!" and why when nothing was moved; else the paragraph that says what was moved and
     * what was left, names only.
     */
    static synchronized String run() throws IOException {
        FileLock held = lock(15 * 60_000L);   // another process's move, its containers made again included, ends first
        if (held == null) return "!another ResearchZosho is moving its files just now";
        try { return moved(); } finally { unlock(held); Config.forgetHome(); Config.invalidate(); }
    }

    private static String moved() throws IOException {
        if (Files.isDirectory(stage())) putBack(stage());   // a move that stopped before its commit: put back first
        Plan p = plan();
        if (!p.due()) return "";
        Path was = was(), now = now(), st = stage();
        boolean proxyStopped = p.model() == Model.OWN && ModelServer.os == ModelServer.Os.windows && stopProxy(was.resolve("model"));   // its log is open while it runs: Windows would refuse the rename
        Files.createDirectories(st);
        try { Files.setPosixFilePermissions(st, PosixFilePermissions.fromString("rwx------")); } catch (UnsupportedOperationException ignored) { }
        try {
            journal(st, "begun " + LocalDate.now());
            if (proxyStopped) journal(st, "stopped the model proxy");
            if (p.config()) {
                Map<String, String> view = Config.readAsShared(was.resolve("config"));
                Path cfg = st.resolve("config");
                Files.writeString(cfg, "", StandardCharsets.UTF_8);
                try { Files.setPosixFilePermissions(cfg, PosixFilePermissions.fromString("rw-------")); } catch (UnsupportedOperationException ignored) { }
                Files.writeString(cfg, Config.text(view, "ResearchZosho's settings, read from " + was.resolve("config") + " on " + LocalDate.now() + " (that file was left as it was)"), StandardCharsets.UTF_8);
                if (!Config.readAsOwn(cfg).equals(view)) throw new IOException("its settings did not read back the same from the new file");
                journal(st, "settings " + view.size());
                STEP.accept("settings");
            }
            for (String n : p.copied()) { Files.copy(was.resolve(n), st.resolve(n), StandardCopyOption.COPY_ATTRIBUTES, LinkOption.NOFOLLOW_LINKS); journal(st, "copied " + n); }
            for (String n : p.moved()) { rename(was.resolve(n), st.resolve(n)); journal(st, "moved " + n); STEP.accept("moved " + n); }
            journal(st, "staged");
            STEP.accept("staged");
            rename(st, now);   // the commit: from here this program finds itself in its own folder
        } catch (IOException | RuntimeException e) {
            String back = putBack(st);
            if (proxyStopped) startProxy(was.resolve("model"));   // where it was, as it was
            return "!" + e.getMessage() + (back.isEmpty() ? "" : "; " + back);
        } finally { Config.forgetHome(); Config.invalidate(); }
        journal(now, "committed");
        List<String> notes = new ArrayList<>(), left = after(p, notes);
        journal(now, "done");
        String s = said(p, true);
        for (String n : notes) s += "  " + n + "\n";
        if (!left.isEmpty()) s += "  not done, and what does it: " + String.join("; ", left) + "\n";
        return s + "  The way back: researchzosho home move --back (it reads " + now.resolve(JOURNAL) + ", which you may delete when all is well).";
    }

    /** A stage left by a move that stopped before its commit (a crash, a kill): put back first, so that nothing is half here and half there. */
    static void recover() {
        if (!Files.isDirectory(stage())) return;
        try {
            FileLock held = lock(0);
            if (held == null) return;   // a move is under way in another process: its stage is not a leftover
            try { if (Files.isDirectory(stage())) putBack(stage()); } finally { unlock(held); }
        } catch (IOException ignored) { }
    }

    /** Every entry the journal says was renamed into the stage goes back, the stage's copies are removed, and the stage with them. "" when all went back. */
    static String putBack(Path st) {
        List<String> lines = new ArrayList<>(journalOf(st));
        Collections.reverse(lines);
        List<String> stuck = new ArrayList<>();
        for (String l : lines) {
            if (!l.startsWith("moved ")) continue;
            String n = l.substring("moved ".length());
            try { if (Files.exists(st.resolve(n), LinkOption.NOFOLLOW_LINKS)) rename(st.resolve(n), was().resolve(n)); } catch (IOException e) { stuck.add(n); }
        }
        if (!stuck.isEmpty()) return "these are still in " + st + " and the next start puts them back: " + String.join(", ", stuck);
        try (Stream<Path> w = Files.walk(st)) { for (Path q : w.sorted(Collections.reverseOrder()).toList()) Files.deleteIfExists(q); } catch (IOException ignored) { }
        return "";
    }

    /**
     * The steps after the commit: the model folder (its own install's paths; the other program's with this program's entries taken out
     * and this program's own embeddings server started), the units' paths, the containers made again. What could not be done, each with
     * what does it; the move run again does it.
     */
    static List<String> after(Plan p, List<String> notes) {
        List<String> left = new ArrayList<>();
        Path was = was(), now = now();
        List<String> names = new ArrayList<>(p.moved());
        if (p.model() == Model.OWN) {
            Path dir = now.resolve("model");
            for (String f : List.of("config.yaml", "run.sh", "run-embed.sh", "plan.properties", "start.ps1"))
                try { relocate(dir.resolve(f), was, now, names); } catch (IOException e) { left.add(f + " in " + dir + " still names " + was + " (" + e.getMessage() + "): researchzosho home move"); }
        }
        for (Path u : p.units()) try { relocate(u, was, now, names); } catch (IOException e) { left.add(u + " still names " + was + ": researchzosho home move"); }
        if (names.contains(TASK_SCRIPT)) try { relocate(now.resolve(TASK_SCRIPT), was, now, names); } catch (IOException e) { left.add(TASK_SCRIPT + " still names " + was + " (" + e.getMessage() + ")"); }
        left.addAll(scripts(now, was, now, names));
        switch (ModelServer.os) {
            case linux -> {
                try {
                    if (!p.units().isEmpty()) {
                        ModelServer.runner.run(List.of("systemctl", "--user", "daemon-reload"));
                        if (p.model() == Model.OWN) ModelServer.runner.run(List.of("systemctl", "--user", "restart", ModelServer.UNIT));   // the proxy reads its config and scripts from the new folder
                    }
                } catch (Exception e) { left.add("the services were not reloaded (" + e.getMessage() + "): systemctl --user daemon-reload"); }
            }
            case macos -> agents(p.model() == Model.OWN, notes, left);
            case windows -> {
                if (names.contains(TASK_SCRIPT)) {
                    String r = task(was, now, true);
                    if (r.isEmpty()) notes.add("the logon task " + Service.WIN_TASK + " now starts the library from its script in the new folder");
                    else if (!r.equals("-")) left.add(r);
                }
                if (p.model() == Model.OWN) { String r = modelTask(now.resolve("model"), journalOf(now).contains("stopped the model proxy")); if (!r.isEmpty()) left.add(r); }
            }
        }
        if (p.model() == Model.THEIRS) left.addAll(outOfTheirs(was.resolve("model"), now, notes));
        for (String c : p.containers()) {
            String r = again(c, was);
            if (r.startsWith("=")) { log(now, "again " + c); if (r.length() > 1) notes.add(r.substring(1)); }
            else if (!r.isEmpty()) left.add(r);
        }
        return left;
    }

    private static void log(Path folder, String line) { try { journal(folder, line); } catch (IOException ignored) { } }

    // ---- the system's own services ----

    /** macOS: the launch agent the running process is, as launchd names it in XPC_SERVICE_NAME; a test sets it. */
    static volatile String AGENT = System.getenv("XPC_SERVICE_NAME");
    /** How long a service that asked to be loaded again waits to be stopped for it before it goes on as it is. */
    static volatile long RELOAD_WAIT_MS = 30_000;
    /** macOS's own perl, which starts the helper in a session of its own; a test points it elsewhere. */
    static volatile Path PERL = Path.of("/usr/bin/perl");
    /** Set when this process asked launchd to load its own agent again: the start waits for it ({@link #atStart}). */
    static volatile boolean reloading;

    static Path agentPlist(String label) { return userHome().resolve("Library").resolve("LaunchAgents").resolve(label + ".plist"); }

    /**
     * macOS: launchd keeps the definition it loaded, so an agent whose file was given the new paths runs the old ones until it is loaded
     * again (its output file in the old folder, its model proxy's server by its old path). This program's own model agent is loaded again
     * now: booted out and bootstrapped by its exact label, which unloads a loaded model once. The library's agent: when this process is
     * that agent (the move ran at its start, before any job), a helper of its own process group, which launchd does not stop with the
     * agent, boots it out and bootstraps it a moment later; when the agent is loaded and this process is not it, the same from here; when
     * it is not loaded, `service start` loads the new file.
     */
    static void agents(boolean ownModel, List<String> notes, List<String> left) {
        String uid = Service.uid();
        if (ownModel && Files.isRegularFile(agentPlist(ModelServer.LABEL))) {
            String r = reload(uid, ModelServer.LABEL);
            if (r.isEmpty()) notes.add("its model server's launch agent (" + ModelServer.LABEL + ") was loaded again from its file; a loaded model was unloaded once");
            else left.add(r);
        }
        String label = Service.MAC_LABEL;
        Path plist = agentPlist(label);
        if (!Files.isRegularFile(plist)) return;
        if (label.equals(AGENT)) {
            Path perl = PERL;
            if (!Files.isExecutable(perl)) { left.add("its service still runs with the old folder's paths until it is loaded again (at your next login, or now: researchzosho service stop, then researchzosho service start)"); return; }
            try {
                // setsid: a session and process group of its own, so that launchd's stop of the agent (which ends the agent's process group) leaves it running
                ModelServer.detach.start(List.of(perl.toString(), "-MPOSIX", "-e", "POSIX::setsid(); sleep 2; system('launchctl', 'bootout', $ARGV[0]); for (1 .. 10) { sleep 1; last if system('launchctl', 'bootstrap', $ARGV[1], $ARGV[2]) == 0; }",
                        "gui/" + uid + "/" + label, "gui/" + uid, plist.toString()));
                reloading = true;
                notes.add("its service is loaded again with the new folder: it stops and starts once now");
            } catch (Exception e) { left.add("its service could not be loaded again (" + e.getMessage() + "); at your next login it is, or now: researchzosho service stop, then researchzosho service start"); }
            return;
        }
        try {
            if (ModelServer.runner.run(List.of("launchctl", "print", "gui/" + uid + "/" + label)).code() != 0) { notes.add("its service is not loaded now; researchzosho service start starts it from its file"); return; }
        } catch (Exception e) { return; }
        String r = reload(uid, label);
        if (r.isEmpty()) notes.add("its service was loaded again from its file"); else left.add(r);
    }

    /** One agent of this program's booted out and bootstrapped from its file, by its exact label. "" when loaded; else what to do. */
    static String reload(String uid, String label) {
        try {
            ModelServer.runner.run(List.of("launchctl", "bootout", "gui/" + uid + "/" + label));
            ModelServer.Result r = ModelServer.runner.run(List.of("launchctl", "bootstrap", "gui/" + uid, agentPlist(label).toString()));
            if (r.code() == 0) return "";
            return label + " was not loaded again (" + ModelServer.firstLine(r.out()) + "): launchctl bootstrap gui/" + uid + " " + agentPlist(label);
        } catch (Exception e) { return label + " was not loaded again (" + e.getMessage() + "): launchctl bootstrap gui/" + uid + " " + agentPlist(label); }
    }

    /**
     * Windows: the logon task runs the script by its path, so the task is registered again with the script in the new folder (the server
     * that runs now is not the task's process, and goes on). When the task cannot be registered again, the script goes back where the task
     * looks for it, and that is said: the install works, with that one file left in the old folder. "" when done or not needed.
     */
    static String task(Path from, Path to, boolean undo) {
        try {
            if (ModelServer.runner.run(List.of("schtasks", "/Query", "/TN", Service.WIN_TASK)).code() != 0) return "-";   // no task: nothing names the script
            ModelServer.Result r = ModelServer.runner.run(List.of("schtasks", "/Create", "/F", "/SC", "ONLOGON", "/TN", Service.WIN_TASK, "/TR", Service.windowsCommand(to.resolve(TASK_SCRIPT)), "/RL", "LIMITED"));
            if (r.code() == 0) return "";   // the server that runs now is not the task's process: it goes on
            if (!undo) return "the logon task " + Service.WIN_TASK + " still names " + from.resolve(TASK_SCRIPT) + " (" + ModelServer.firstLine(r.out()) + "); researchzosho service install writes it again";
            rename(to.resolve(TASK_SCRIPT), from.resolve(TASK_SCRIPT));   // its log's path stays the new one: the server writes there
            return "the logon task " + Service.WIN_TASK + " could not be pointed at the new folder (" + ModelServer.firstLine(r.out()) + "), so its script " + TASK_SCRIPT
                    + " stays in " + from + "; researchzosho service install writes it again";
        } catch (Exception e) { return "the logon task " + Service.WIN_TASK + " was not changed (" + e.getMessage() + "); researchzosho service install writes it again"; }
    }

    /** Windows: this program's own model proxy, stopped by the number it recorded (never another llama-swap). Whether one was stopped. */
    static boolean stopProxy(Path dir) {
        if (!Files.isRegularFile(dir.resolve(ModelServer.SWAP_PID))) return false;
        ModelServer.stopOurProxy(dir);
        return true;
    }

    /** Windows: the model proxy started from its start script, as `model install` starts it. */
    static void startProxy(Path dir) {
        try { if (Files.isRegularFile(dir.resolve("start.ps1"))) ModelServer.detach.start(List.of("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", dir.resolve("start.ps1").toString())); }
        catch (Exception ignored) { }
    }

    /** Windows: the model's logon task registered again with its start script in the new folder, and the proxy started again when the move stopped it. */
    static String modelTask(Path dir, boolean wasRunning) {
        String said = "";
        try {
            if (ModelServer.runner.run(List.of("schtasks", "/query", "/tn", ModelServer.TASK)).code() == 0) {
                String tr = "powershell -NoProfile -ExecutionPolicy Bypass -File \\\"" + dir.resolve("start.ps1") + "\\\"";
                ModelServer.Result r = ModelServer.runner.run(List.of("schtasks", "/create", "/tn", ModelServer.TASK, "/sc", "onlogon", "/tr", tr, "/f"));
                if (r.code() != 0) said = "the model's logon task " + ModelServer.TASK + " still names the old folder (" + ModelServer.firstLine(r.out()) + "): researchzosho model install";
            }
        } catch (Exception e) { said = "the model's logon task was not changed (" + e.getMessage() + "): researchzosho model install"; }
        if (wasRunning) startProxy(dir);
        return said;
    }

    /** The folder in the new home where the move keeps what it took out of the other program's model folder, for the way back. */
    static final String TAKEN = "moved-out-of-model";

    /** {@link Embed#start} waits at most two minutes for the first answer during a move, which a service's start waits for; the server goes on starting after that. */
    private static String quick(Supplier<String> start) {
        int was = Embed.WAITS;
        Embed.WAITS = 60;
        try { return start.get(); } finally { Embed.WAITS = was; }
    }

    /**
     * The helpers' Python environments (a {@code venv} under a moved folder: video's yt-dlp, the likeness helper, MetricX, the comic
     * helper) name their own folder in their scripts: each command script starts with {@code #!<the venv>/bin/python}, so after a rename
     * {@code yt-dlp} and {@code pip} would find no interpreter. Those first lines and the activate scripts are given the new path; the
     * Python itself, run by its own path, finds its environment wherever it is. Windows' {@code .exe} launchers hold the path inside a
     * program and are not changed: there `researchzosho helpers install` sets them up again. What could not be changed, with what does it.
     */
    static List<String> scripts(Path under, Path from, Path to, List<String> names) {
        List<String> left = new ArrayList<>();
        for (String n : names) {
            Path top = under.resolve(n);
            if (!Files.isDirectory(top, LinkOption.NOFOLLOW_LINKS)) continue;
            try (Stream<Path> w = Files.walk(top, 4)) {
                for (Path cfg : w.filter(q -> q.getFileName().toString().equals("pyvenv.cfg")).toList()) {
                    for (String b : List.of("bin", "Scripts")) {   // Scripts on Windows
                        Path bin = cfg.getParent().resolve(b);
                        if (!Files.isDirectory(bin, LinkOption.NOFOLLOW_LINKS)) continue;
                        try (Stream<Path> in = Files.list(bin)) {
                            for (Path f : in.toList()) {
                                if (!Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS) || Files.size(f) > 4 * 1024 * 1024) continue;
                                String fn = f.getFileName().toString().toLowerCase(Locale.ROOT);
                                try {
                                    if (fn.endsWith(".exe")) launcher(f, from, to, names);
                                    else if (fn.startsWith("activate") || fn.endsWith(".bat") || fn.endsWith(".cmd") || fn.endsWith(".ps1") || startsWithShebang(f)) relocate(f, from, to, names);
                                } catch (IOException | RuntimeException e) { left.add(f + " still names " + from + " (" + e.getMessage() + ")"); }
                            }
                        }
                    }
                }
            } catch (IOException | RuntimeException e) { left.add("the Python environments under " + top + " were not looked at (" + e.getMessage() + ")"); }
        }
        return left;
    }

    private static boolean startsWithShebang(Path f) throws IOException {
        byte[] head = new byte[2];
        try (var s = Files.newInputStream(f)) { return s.read(head) == 2 && head[0] == '#' && head[1] == '!'; }
    }

    /**
     * A Windows command of a Python environment (pip.exe, yt-dlp.exe): a small program with a first line {@code #!<the venv>\Scripts\python.exe}
     * and the script after it, which the program runs with that Python. After a rename it finds no Python (tried on Windows 11, 2026-11-25:
     * pip.exe exited 1 and printed nothing). That one line, in its UTF-8, is given the new path; every other byte stays, and the command
     * works again, with a longer path too (tried the same day). The python.exe of the environment needs nothing: it finds its environment
     * from where it is.
     */
    static void launcher(Path f, Path from, Path to, List<String> names) throws IOException {
        byte[] b = Files.readAllBytes(f), out = launcher(b, line -> relocated(line, from, to, names));
        if (out != b) Files.write(f, out);
    }

    /** The launcher's bytes with its last {@code #!} line changed by {@code change}; the same array when nothing changed. */
    static byte[] launcher(byte[] b, UnaryOperator<String> change) {
        int at = lastIndexOf(b, new byte[]{'#', '!'});
        if (at < 0) return b;
        int end = at;
        while (end < b.length && b[end] != '\n' && end - at < 4096) end++;
        if (end >= b.length || b[end] != '\n') return b;
        String line = new String(b, at, end - at, StandardCharsets.UTF_8), moved = change.apply(line);
        if (moved.equals(line)) return b;
        byte[] nl = moved.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[b.length - (end - at) + nl.length];
        System.arraycopy(b, 0, out, 0, at);
        System.arraycopy(nl, 0, out, at, nl.length);
        System.arraycopy(b, end, out, at + nl.length, b.length - end);
        return out;
    }

    private static int lastIndexOf(byte[] b, byte[] what) {
        outer:
        for (int i = b.length - what.length; i >= 0; i--) {
            for (int j = 0; j < what.length; j++) if (b[i + j] != what[j]) continue outer;
            return i;
        }
        return -1;
    }

    /** Every path under the old folder to a moved name becomes the same path under the new one, in this one file; other text stays. */
    static void relocate(Path file, Path was, Path now, List<String> names) throws IOException {
        if (!Files.isRegularFile(file) || names.isEmpty()) return;
        String t = Files.readString(file, StandardCharsets.UTF_8), u = relocated(t, was, now, names);
        if (!u.equals(t)) Files.writeString(file, u, StandardCharsets.UTF_8);
    }

    /** The text with every path under the old folder to a moved name made the same path under the new one, in each form {@link #forms} knows. */
    static String relocated(String text, Path was, Path now, List<String> names) {
        return relocated(text, was.toString(), now.toString(), userHome().toString(), File.separatorChar == '\\', names);
    }

    /** The same, for paths as text: {@code windows} when they are Windows paths (back slashes, any case). */
    static String relocated(String text, String was, String now, String home, boolean win, List<String> names) {
        if (names.isEmpty()) return text;
        String alt = String.join("|", names.stream().map(Pattern::quote).toList());
        List<String> from = forms(was, home, win), to = forms(now, home, win);
        String u = text;
        for (int i = 0; i < from.size(); i++)
            u = Pattern.compile(Pattern.quote(from.get(i)) + "(" + alt + ")(?=[/\\\\:\\s\"'<]|$)", Pattern.MULTILINE | (win ? Pattern.CASE_INSENSITIVE : 0))
                    .matcher(u).replaceAll(Matcher.quoteReplacement(to.get(i)) + "$1");
        return u;
    }

    static List<String> forms(Path folder) { return forms(folder.toString(), userHome().toString(), File.separatorChar == '\\'); }

    /**
     * How a file names a folder of the home, each form ending with its separator. Linux and macOS: the whole path, and under the home
     * systemd's {@code %h/} and a shell's {@code $HOME/}. Windows: the whole path with back slashes and with forward ones, and under the
     * home {@code %USERPROFILE%\} and PowerShell's {@code $env:USERPROFILE\}; compared without regard to case, as Windows does. A short
     * 8.3 form ({@code CODEZA~1}) is not one of them: nothing of this program writes one.
     */
    static List<String> forms(String folder, String home, boolean windows) {
        String sep = windows ? "\\" : "/";
        String abs = folder.endsWith(sep) ? folder : folder + sep;
        String h = home.endsWith(sep) ? home : home + sep;
        String rel = abs.regionMatches(windows, 0, h, 0, h.length()) ? abs.substring(h.length()) : null;
        List<String> out = new ArrayList<>(List.of(abs));
        if (windows) {
            out.add(abs.replace('\\', '/'));
            if (rel != null) { out.add("%USERPROFILE%\\" + rel); out.add("$env:USERPROFILE\\" + rel); }
        } else if (rel != null) { out.add("%h/" + rel); out.add("$HOME/" + rel); }
        return out;
    }

    /**
     * A path given on a command line (serve's {@code --log}) as it is after the move: under the new folder when it named an entry the move
     * took there. A service started by a unit the move could not rewrite then writes in the new folder all the same.
     */
    public static Path relocated(Path given) {
        try {
            Path abs = given.toAbsolutePath().normalize(), was = was(), now = now();
            if (!abs.startsWith(was) || abs.equals(was)) return given;
            String first = was.relativize(abs).getName(0).toString();
            if (!journalOf(now).contains("moved " + first)) return given;
            return now.resolve(was.relativize(abs));
        } catch (RuntimeException e) { return given; }
    }

    /**
     * The other program's model server in its folder, with this program's embeddings entry and start script in it: both are taken out (the
     * one write this move makes into the other program's folder, and it removes only this program's own lines). That program's proxy is
     * not restarted: it keeps the old entry in memory until its own next start, and nobody asks it for that entry any more. This program's
     * own embeddings server is started instead, with no way out where that holds, and the library's setting points at it.
     */
    static List<String> outOfTheirs(Path dir, Path now, List<String> notes) {
        List<String> left = new ArrayList<>();
        boolean card = false;
        try {
            Path cfg = dir.resolve("config.yaml"), script = dir.resolve("run-embed.sh"), kept = now.resolve(TAKEN);
            Files.createDirectories(kept);
            if (Files.isRegularFile(script)) { String t = Files.readString(script, StandardCharsets.UTF_8); card = t.contains("--gpus") || t.contains("--device"); }
            if (Files.isRegularFile(cfg)) {
                String y = Files.readString(cfg, StandardCharsets.UTF_8), w = ModelServer.withoutEmbed(y);
                if (!w.equals(y)) {
                    Files.writeString(kept.resolve("config.yaml.before"), y, StandardCharsets.UTF_8);
                    Files.writeString(kept.resolve("config.yaml.after"), w, StandardCharsets.UTF_8);
                    Files.writeString(cfg, w, StandardCharsets.UTF_8);
                    log(now, "took entry config.yaml");
                }
            }
            if (ModelServer.EMBED_CONTAINER.equals(ModelServer.containerOf(script, null))) {
                Files.copy(script, kept.resolve("run-embed.sh"), StandardCopyOption.COPY_ATTRIBUTES);
                Files.delete(script);
                log(now, "took run-embed.sh");
            }
            try { ModelServer.runner.run(List.of("docker", "stop", ModelServer.EMBED_CONTAINER)); } catch (Exception ignored) { }
        } catch (IOException e) { left.add("ResearchZosho's embeddings entry is still in " + dir + " (" + e.getMessage() + "): researchzosho home move"); }
        String set = Config.stored("RESEARCHZOSHO_EMBED");
        if (set == null || !ModelsAwake.base(set).equals(ModelServer.URL)) return left;   // the library's embeddings are elsewhere: nothing of it was in that server
        boolean cpu = !(card && Embed.gpu());   // on a card when the removed one was
        String r = !Helpers.localAllowed() ? "!this machine runs no heavy helper"
                : !Embed.onDisk(cpu ? "cpu-" + Embed.VERSION : Embed.tag()) ? "!its model is not on this machine yet, and a move downloads nothing"
                : quick(() -> Embed.start(Embed.DEFAULT_PORT, cpu));
        try {
            if (r.startsWith("!the container started")) {   // made and starting, slower than the move waits: the library's address is its own all the same
                Config.set("RESEARCHZOSHO_EMBED", Embed.address());
                notes.add("ResearchZosho's own embeddings server was still starting after two minutes, at " + Embed.address() + "; search by meaning comes back when it answers (researchzosho embed status says how it stands)");
            } else if (r.startsWith("!")) { Config.set("RESEARCHZOSHO_EMBED", "off"); left.add("ResearchZosho's own embeddings server did not start (" + r.substring(1) + "), so search is by words: researchzosho embed start"); }
            else Config.set("RESEARCHZOSHO_EMBED", r);
            if (!Embed.state().equals("absent")) log(now, "made " + Embed.CONTAINER);
        } catch (IOException e) { left.add("the embeddings setting was not written (" + e.getMessage() + ")"); }
        if (!Embed.note.isEmpty()) notes.add(Embed.note);
        return left;
    }

    /**
     * One of this program's containers made again with its folder in the new place, when it mounts a folder under the old one: removed by
     * its exact name and made by the same command that makes it, with the port and the card it had. "" when not needed; "=" and what
     * there is to say when it was made again; else why not.
     */
    static String again(String name, Path was) {
        try {
            Searx.Result i = Searx.runner.run(List.of("docker", "inspect", "-f", "{{.Config.Image}}|{{range .Mounts}}{{.Source}};{{end}}|{{range $p, $b := .HostConfig.PortBindings}}{{(index $b 0).HostPort}};{{end}}|{{len .HostConfig.DeviceRequests}}|{{range .HostConfig.DeviceRequests}}{{join .DeviceIDs \",\"}}{{end}}|{{.State.Running}}", name));
            if (i.code() != 0) return "";   // no such container here
            String[] f = (i.out().strip() + "|||||").split("\\|", -1);
            boolean running = f[5].strip().equals("true");   // one the idle rule had stopped is made again stopped
            boolean mounts = false;
            for (String src : f[1].split(";")) if (under(src, was)) mounts = true;
            if (!mounts) return "";   // made with another folder: not this home's, left alone
            String image = f[0], tag = image.contains(":") ? image.substring(image.lastIndexOf(':') + 1) : "";
            int port = f[2].isBlank() ? -1 : Integer.parseInt(f[2].split(";")[0].strip());
            boolean card = !f[3].isBlank() && !f[3].strip().equals("0");
            String cards = f[4].isBlank() ? "all" : "device=" + f[4].strip();   // the cards it was given: all, or the ones it names
            List<String> cmd;
            if (name.equals(Searx.CONTAINER)) cmd = Searx.runCommand(port > 0 ? port : Searx.DEFAULT_PORT);
            else if (name.equals(TranslationScores.LABSE_CONTAINER)) cmd = TranslationScores.labseCommand(port > 0 ? port : TranslationScores.LABSE_PORT, tag, cards);
            else if (name.equals(Embed.CONTAINER)) cmd = null;   // the embeddings server is made by its own start, with no way out where that holds
            else if (name.equals(Video.CONTAINER_WHISPER)) cmd = Video.whisperPlan("absent").get(0);
            else return "";
            if (cmd == null && !Embed.onDisk(card ? Embed.tag() : "cpu-" + Embed.VERSION))   // left as it is: made again, it would download its model
                return name + " still mounts " + was.resolve("embed") + ": its model is not in the moved folder, and a move downloads nothing; researchzosho embed start --again makes it again";
            Searx.runner.run(List.of("docker", "rm", "-f", name));
            if (cmd == null) {
                int at = port > 0 ? port : Embed.DEFAULT_PORT;
                String r = quick(() -> Embed.start(at, !card));
                if (r.startsWith("!")) return name + " was not made again (" + r.substring(1) + "): researchzosho embed start";
                if (!running) Embed.stop();
                String set = Config.stored("RESEARCHZOSHO_EMBED");
                if (set != null && !set.equalsIgnoreCase("off") && !set.equalsIgnoreCase("none") && !ModelsAwake.base(set).equals(r)) Config.set("RESEARCHZOSHO_EMBED", r);
                return "=" + Embed.note;
            }
            cmd = kept(cmd, image, card, cards);
            if (!running && cmd.size() > 2 && cmd.get(1).equals("run") && cmd.get(2).equals("-d")) {   // made, not started: docker create takes the same words
                List<String> made = new ArrayList<>(cmd);
                made.remove(2);
                made.set(1, "create");
                cmd = made;
            }
            Searx.Result r = Searx.runner.run(cmd);
            return r.code() == 0 ? "=" : name + " was not made again (" + ModelServer.firstLine(r.out()) + ")";
        } catch (Exception e) { return name + " was not made again (" + e.getMessage() + ")"; }
    }

    /**
     * The command made with what the old container had that the program's own command would choose afresh: its image, when it is the
     * same image under another tag (a pinned version stays pinned), and its cards, when it had some and the command gives none.
     */
    static List<String> kept(List<String> cmd, String image, boolean card, String cards) {
        List<String> out = new ArrayList<>(cmd);
        int colon = image.lastIndexOf(':');
        String repo = colon > 0 && !image.substring(colon).contains("/") ? image.substring(0, colon) : image;
        int at = -1;
        for (int i = 3; i < out.size(); i++) if (!out.get(i - 1).startsWith("-") || out.get(i - 1).equals("-d")) { String a = out.get(i); if (a.equals(repo) || a.startsWith(repo + ":")) { at = i; break; } }
        if (at < 0) return out;
        if (!image.isBlank()) out.set(at, image);
        if (card && !out.contains("--gpus")) { out.add(at, cards); out.add(at, "--gpus"); }
        return out;
    }

    /**
     * The way back, by the journal: every entry of the new folder goes back to the old one (what the move renamed, and what this program
     * wrote there since), the units' and scripts' paths are turned back, the services reloaded, the entry taken out of the other
     * program's model folder put back when that file is still as the move left it, the embeddings server the move started removed, and
     * the containers the move made again made once more with the old folders. The new folder's settings file is removed: the old shared
     * file was never changed, so the settings are as they were before the move, and one changed since the move is not there (said).
     */
    static synchronized String back() throws IOException {
        FileLock held = lock(60_000L);
        if (held == null) return "!another ResearchZosho is moving its files just now";
        try { return backed(); } finally { unlock(held); Config.forgetHome(); Config.invalidate(); }
    }

    private static String backed() throws IOException {
        Path was = was(), now = now();
        List<String> lines = journalOf(now);
        if (lines.isEmpty()) return "!no move of this program's is written down in " + now.resolve(JOURNAL);
        if (!Files.isDirectory(was)) return "!" + was + " is not there any more";
        List<String> said = new ArrayList<>();
        List<String> all = new ArrayList<>();
        try (Stream<Path> in = Files.list(now)) {
            for (Path q : in.sorted().toList()) { String n = q.getFileName().toString(); if (!n.equals("config") && !n.equals(JOURNAL) && !n.equals(TAKEN)) all.add(n); }
        }
        List<String> clash = all.stream().filter(n -> Files.exists(was.resolve(n), LinkOption.NOFOLLOW_LINKS) && !COPIED.contains(n) && !LEFT.contains(n)).toList();
        if (!clash.isEmpty()) return "!these are in both folders, so nothing was moved back: " + String.join(", ", clash);
        boolean made = lines.contains("made " + Embed.CONTAINER);   // the move started it, the way back removes it, and where it answered goes with it
        List<String> back = all.stream().filter(n -> !COPIED.contains(n) && !LEFT.contains(n) && !(made && n.equals("embed-at"))).toList();
        boolean ownModel = back.contains("model") && modelShape(now.resolve("model")) == Model.OWN;
        boolean proxyStopped = ownModel && ModelServer.os == ModelServer.Os.windows && stopProxy(now.resolve("model"));
        List<Path> units = unitsNaming(now);
        // the renames first, each undone when one is refused (Windows refuses a folder with a file open in it): all back, or none
        List<String> done = new ArrayList<>();
        try { for (String n : back) { rename(now.resolve(n), was.resolve(n)); done.add(n); } }
        catch (IOException e) {
            Collections.reverse(done);
            for (String n : done) try { rename(was.resolve(n), now.resolve(n)); } catch (IOException ignored) { }
            if (proxyStopped) startProxy(now.resolve("model"));
            return "!" + e.getMessage() + "; nothing was moved back";
        }
        for (Path u : units) relocate(u, now, was, all);
        if (ownModel) for (String f : List.of("config.yaml", "run.sh", "run-embed.sh", "plan.properties", "start.ps1")) relocate(was.resolve("model").resolve(f), now, was, all);
        if (back.contains(TASK_SCRIPT)) relocate(was.resolve(TASK_SCRIPT), now, was, all);
        said.addAll(scripts(was, now, was, back));
        Path kept = now.resolve(TAKEN), dir = was.resolve("model");
        if (lines.contains("took entry config.yaml")) {
            Path cfg = dir.resolve("config.yaml");
            String after = Files.isRegularFile(kept.resolve("config.yaml.after")) ? Files.readString(kept.resolve("config.yaml.after"), StandardCharsets.UTF_8) : null;
            if (after != null && Files.isRegularFile(cfg) && Files.readString(cfg, StandardCharsets.UTF_8).equals(after)) Files.copy(kept.resolve("config.yaml.before"), cfg, StandardCopyOption.REPLACE_EXISTING);
            else said.add("the model server's config in " + dir + " changed since the move, so ResearchZosho's embeddings entry was not put back in it");
        }
        if (lines.contains("took run-embed.sh") && Files.isRegularFile(kept.resolve("run-embed.sh")) && !Files.exists(dir.resolve("run-embed.sh")))
            Files.copy(kept.resolve("run-embed.sh"), dir.resolve("run-embed.sh"), StandardCopyOption.COPY_ATTRIBUTES);
        try (Stream<Path> w = Files.walk(now)) { for (Path q : w.sorted(Collections.reverseOrder()).toList()) Files.deleteIfExists(q); }
        Config.forgetHome(); Config.invalidate();
        switch (ModelServer.os) {
            case linux -> {
                try {
                    if (!units.isEmpty()) {
                        ModelServer.runner.run(List.of("systemctl", "--user", "daemon-reload"));
                        if (ownModel) ModelServer.runner.run(List.of("systemctl", "--user", "restart", ModelServer.UNIT));
                    }
                } catch (Exception e) { said.add("the services were not reloaded (" + e.getMessage() + "): systemctl --user daemon-reload"); }
            }
            case macos -> { List<String> notes = new ArrayList<>(); agents(ownModel, notes, said); said.addAll(notes); }
            case windows -> {
                if (back.contains(TASK_SCRIPT)) { String r = task(now, was, false); if (!r.isEmpty() && !r.equals("-")) said.add(r); }
                if (ownModel) { String r = modelTask(dir, proxyStopped); if (!r.isEmpty()) said.add(r); }
            }
        }
        if (made) try { Searx.runner.run(List.of("docker", "rm", "-f", Embed.CONTAINER)); } catch (Exception ignored) { }
        for (String l : lines) if (l.startsWith("again ")) { String r = again(l.substring("again ".length()), now); if (!r.isEmpty() && !r.equals("=")) said.add(r.startsWith("=") ? r.substring(1) : r); }
        return "ResearchZosho's files are back in " + was + " (" + String.join(", ", back) + "); its settings are read from " + was.resolve("config")
                + " again, as before the move, and a setting changed since the move is not there. " + now + " is removed."
                + (said.isEmpty() ? "" : "\n  " + String.join("\n  ", said));
    }
}
