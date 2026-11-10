package org.researchzosho.librarian;

import org.researchzosho.Config;
import org.researchzosho.Version;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
/**
 * Updating the installed program to the latest release: the same download the installers make, checked against the release's own
 * SHA256SUMS. A release installs into {@code <root>/versions/<version>/} beside the one that runs, and a pointer says which one runs:
 * {@code current}, a symbolic link to {@code versions/<version>}, on Linux and macOS; {@code current.txt}, the version's name in a file,
 * on Windows, where a folder a running program uses cannot be renamed. The launchers in {@code <root>/bin} run the version the pointer
 * names, so a running program's own files are never touched, and the service restarts onto the new version at once: the jobs it had
 * running resume from the work they kept ({@link Checkpoint}). The version before stays as the way back ({@code researchzosho update back});
 * two are kept. An install of the first layout (bin/ and lib/ at the root, as 0.7.1 and earlier install) takes the new layout at its first
 * update: its files go into {@code versions/<its version>/}, on Linux and macOS by hard links, so the running program keeps its own until
 * it restarts, and on Windows by a small helper once the program has ended. The library and the settings live elsewhere and are not touched.
 *
 * <p>{@code RESEARCHZOSHO_UPDATE}: {@code check} (default) says when a newer release exists; {@code auto} has the service install it after
 * the nightly housekeeping and restart at once; {@code off} does neither. {@code researchzosho update now} does it by hand, and restarts the
 * service at once too. A run from the source tree never updates.
 */
public final class Updater {

    private Updater() { }

    public static final String REPO = "Wyrdsekai/researchzosho";
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL).build();   // a GitHub release asset is a 302 to its store; without this `update now` said "HTTP 302" (a test box, 2026-09-10)

    /** The mark an update leaves before it restarts the service: the version it restarts onto, for the jobs the stop interrupts. */
    static Path restartMark() { return Config.home().resolve("update-restart"); }

    /** The version an update is restarting the service onto, read once and removed; null when the service stops for another reason. */
    public static String restartingTo() {
        Path f = restartMark();
        try {
            if (!Files.isRegularFile(f)) return null;
            boolean fresh = System.currentTimeMillis() - Files.getLastModifiedTime(f).toMillis() < 15L * 60 * 1000;
            String v = Files.readString(f, StandardCharsets.UTF_8).strip();
            Files.deleteIfExists(f);
            return fresh && !v.isEmpty() ? v : null;
        } catch (IOException | RuntimeException unreadable) { return null; }
    }

    /** The mark {@code update back} leaves: the version gone back from, which the auto mode does not take again. */
    static Path heldMark() { return Config.home().resolve("update-held"); }

    /** The version the person went back from; null when they did not. */
    static String held() {
        try { if (!Files.isRegularFile(heldMark())) return null; String v = Files.readString(heldMark(), StandardCharsets.UTF_8).strip(); return v.isEmpty() ? null : v; }
        catch (IOException e) { return null; }
    }

    /** An update the person asks for by hand: the version they went back from is no longer held back. */
    public static void unhold() { try { Files.deleteIfExists(heldMark()); } catch (IOException ignored) { } }

    /** check | auto | off */
    public static String mode() {
        String m = Config.get("RESEARCHZOSHO_UPDATE", "check").toLowerCase(Locale.ROOT).strip();
        return m.equals("auto") || m.equals("off") ? m : "check";
    }

    /** The install root: the folder above {@code versions/<version>/lib} holding this jar, or above a {@code lib/} at the root; null from the source tree. */
    public static Path root() {
        Path lib = runningLib();
        return lib == null ? null : rootOf(lib);
    }

    /** The lib/ folder this program runs from; null when it runs from the source tree. */
    static Path runningLib() {
        try {
            Path self = Path.of(Version.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (!self.toString().endsWith(".jar")) return null;
            Path lib = self.getParent();
            return lib == null || !lib.getFileName().toString().equals("lib") ? null : lib;
        } catch (Exception e) { return null; }
    }

    /** The install root of a lib/ folder: two above it under {@code versions/}, else its parent (the first layout). */
    static Path rootOf(Path lib) {
        Path home = lib.getParent();
        Path above = home == null ? null : home.getParent();
        return above != null && above.getFileName() != null && above.getFileName().toString().equals(VERSIONS) ? above.getParent() : home;
    }

    /**
     * What an update came to, and the exit code `update now` ends with, for a program that runs it (CodeZaiku, Wyrdsekai): 0 when it
     * updated or was already current, 75 when another update is running (the usual code for "try again later"), 3 when this install
     * cannot update itself (a run from the source tree), 1 when it failed. A usage mistake ends with 2, as every command's does.
     */
    public enum Result {
        UPDATED(0), CURRENT(0), BUSY(75), NOT_HERE(3), FAILED(1);
        public final int code;
        Result(int code) { this.code = code; }
        /** The word the JSON gives: updated, current, busy, not-here, failed. */
        public String word() { return name().toLowerCase(Locale.ROOT).replace('_', '-'); }
    }

    /** {@code finishesAfterExit}: on Windows a helper finishes the update once this program has ended (it starts the service again, or moves the first layout's files). */
    public record Outcome(boolean updated, String from, String to, String note, boolean finishesAfterExit, Result result) {
        public Outcome(boolean updated, String from, String to, String note, boolean finishesAfterExit) { this(updated, from, to, note, finishesAfterExit, updated ? Result.UPDATED : Result.FAILED); }
        public Outcome(boolean updated, String from, String to, String note) { this(updated, from, to, note, false); }
    }

    // ── the layout: versions side by side, and the pointer ───────────────────────────────────────────────────────────

    static final String VERSIONS = "versions", CURRENT = "current", POINTER = "current.txt";
    /** Words in every launcher this program writes into {@code <root>/bin}: how an install of the layout with versions is known. */
    static final String LAUNCHER_MARK = "ResearchZosho: runs the version";

    /** An install's folders: {@code versions/}, the pointer, the launchers. */
    record Layout(Path root, Service.Os os) {
        Path versions() { return root.resolve(VERSIONS); }
        Path link() { return root.resolve(CURRENT); }
        Path pointer() { return root.resolve(POINTER); }
        boolean windows() { return os == Service.Os.windows; }
        Path launcher() { return root.resolve("bin").resolve(windows() ? "researchzosho.bat" : "researchzosho"); }

        /** Whether the install is of the layout with versions: its launcher is this program's own, and the pointer names a version. */
        boolean versioned() {
            try { if (!Files.isRegularFile(launcher()) || !Files.readString(launcher(), StandardCharsets.UTF_8).contains(LAUNCHER_MARK)) return false; }
            catch (IOException e) { return false; }
            return current() != null;
        }

        /** The version the pointer names; null when there is none. */
        String current() {
            try {
                if (windows()) {
                    if (!Files.isRegularFile(pointer())) return null;
                    String v = Files.readString(pointer(), StandardCharsets.UTF_8).strip();
                    return v.isEmpty() ? null : v;
                }
                if (!Files.isSymbolicLink(link())) return null;
                Path t = Files.readSymbolicLink(link());
                return t.getFileName() == null ? null : t.getFileName().toString();
            } catch (IOException e) { return null; }
        }

        /** The versions kept, oldest first: the folders under versions/ that hold a lib/. */
        List<String> installed() {
            List<String> out = new ArrayList<>();
            if (!Files.isDirectory(versions())) return out;
            try (var s = Files.list(versions())) {
                for (Path p : s.toList()) { String n = p.getFileName().toString(); if (n.matches("\\d+\\.\\d+\\.\\d+.*") && Files.isDirectory(p.resolve("lib"))) out.add(n); }
            } catch (IOException ignored) { }
            out.sort(Version::compare);
            return out;
        }
    }

    // ── one update at a time ──────────────────────────────────────────────────────────────────────────────────────

    /** The file an update holds locked from its start to its end. The system lets go of it when the program ends, however it ends. */
    static Path lockFile() { return Config.home().resolve("update.lock"); }

    /** The mark a Windows update leaves while its helper, which runs after this program has ended, finishes it: the helper's folder. */
    static Path pendingFile() { return Config.home().resolve("update.pending"); }

    /** Whether an update of this install is going on now, in this or another program. */
    public static boolean updating() { return updating(lockFile(), pendingFile()); }

    static boolean updating(Path lock, Path pending) {
        if (pendingLive(pending)) return true;
        try {
            Files.createDirectories(lock.getParent());
            try (FileChannel ch = FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                FileLock l;
                try { l = ch.tryLock(); } catch (OverlappingFileLockException inThisProgram) { return true; }
                if (l == null) return true;
                l.release();
                return false;
            }
        } catch (IOException held) { return Files.exists(lock); }   // Windows: the helper holds the file open while it works
    }

    /**
     * Whether a Windows helper is still at work: its mark names its folder, which it removes when it is done, and it is younger than half
     * an hour (the helper waits ten minutes at most for the program to end). An older mark, or one whose folder is gone, is removed.
     */
    static boolean pendingLive(Path pending) {
        try {
            if (!Files.exists(pending)) return false;
            String work = Files.readString(pending, StandardCharsets.UTF_8).strip();
            boolean young = System.currentTimeMillis() - Files.getLastModifiedTime(pending).toMillis() < 30L * 60 * 1000;
            if (young && !work.isEmpty() && Files.isDirectory(Path.of(work))) return true;
            Files.deleteIfExists(pending);
            return false;
        } catch (IOException | RuntimeException unreadable) { return false; }
    }

    /**
     * Runs an update under the lock. Another update holding it, or a Windows helper still at work, makes this one BUSY at once: it does
     * not wait, and the program that asked can ask again later, when a check of the version finds the install current.
     */
    static Outcome guarded(Path lock, Path pending, Supplier<Outcome> work) {
        String have = Version.string();
        Outcome busy = new Outcome(false, have, have, "another update of ResearchZosho is running now; nothing was changed", false, Result.BUSY);
        try {
            Files.createDirectories(lock.getParent());
            try (FileChannel ch = FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                FileLock held;
                try { held = ch.tryLock(); } catch (OverlappingFileLockException inThisProgram) { held = null; }
                if (held == null) return busy;
                try {
                    if (pendingLive(pending)) return busy;
                    return work.get();
                } finally {
                    if (held.isValid()) held.release();
                }
            }
        } catch (IOException e) {
            if (pendingLive(pending)) return busy;
            return new Outcome(false, have, have, "could not take the update lock " + lock + " (" + e.getMessage() + ")", false, Result.FAILED);
        }
    }

    /** The version that runs when the program starts now: the one the pointer names, else the jar in lib/ (the first layout). */
    static String installedVersion(Path root) { return installedVersion(new Layout(root, Service.os())); }

    static String installedVersion(Layout l) {
        if (l.versioned()) return l.current();
        String flat = jarVersion(l.root().resolve("lib"));
        return flat != null ? flat : Version.string();
    }

    /** The version of the program's jar in {@code lib}; null when there is none. */
    static String jarVersion(Path lib) {
        try (var s = Files.list(lib)) {
            for (Path p : s.toList()) {
                Matcher m = INSTALLED_JAR.matcher(p.getFileName().toString());
                if (m.matches()) return m.group(1);
            }
        } catch (IOException | RuntimeException unreadable) { }
        return null;
    }

    private static final Pattern INSTALLED_JAR = Pattern.compile("^librarian-(\\d+\\.\\d+\\.\\d+)\\.jar$");

    private static final ObjectMapper JSON = new ObjectMapper();

    /** `update --json`: what a program that updates ResearchZosho reads before it asks for an update. */
    public static String statusJson() {
        Path root = root();
        Layout l = root == null ? null : new Layout(root, Service.os());
        String latest = Version.latest(), installed = l == null ? Version.string() : installedVersion(l);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("program", "researchzosho");
        m.put("installed", installed);
        m.put("running", Version.string());
        m.put("latest", latest);
        m.put("newer", latest != null && Version.isRelease() && Version.compare(latest, installed) > 0);
        m.put("mode", mode());
        m.put("root", root == null ? null : root.toString());
        m.put("canUpdate", Version.isRelease() && root != null);
        m.put("updating", updating());
        m.put("versions", l == null ? List.of() : l.installed());   // the versions kept side by side; the one before the current is the way back
        m.put("held", held());
        try { return JSON.writeValueAsString(m); } catch (IOException e) { return "{}"; }
    }

    /** `update now --json`: what the update came to. */
    public static String outcomeJson(Outcome o) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("result", o.result().word());
        m.put("code", o.result().code);
        m.put("from", o.from());
        m.put("to", o.to());
        m.put("finishesAfterExit", o.finishesAfterExit());
        m.put("note", o.note());
        try { return JSON.writeValueAsString(m); } catch (IOException e) { return "{}"; }
    }

    /** What `researchzosho update` says: the installed version, the versions kept, the latest, the mode, and what would happen. */
    public static String status() {
        String latest = Version.latest();
        Path root = root();
        Layout l = root == null ? null : new Layout(root, Service.os());
        StringBuilder b = new StringBuilder();
        b.append("installed: ").append(Version.string()).append(root == null ? " (from the source tree; updates are git pull)" : " at " + root).append('\n');
        if (l != null && l.installed().size() > 1) b.append("kept:      ").append(String.join(", ", l.installed())).append(" (researchzosho update back runs the one before)").append('\n');
        b.append("latest:    ").append(latest == null ? "unknown (could not reach GitHub)" : latest).append('\n');
        b.append("mode:      ").append(mode()).append(" (RESEARCHZOSHO_UPDATE = check | auto | off; researchzosho update auto on|off)").append('\n');
        if (updating()) b.append("an update is running now\n");
        if (latest != null && Version.isRelease() && Version.compare(latest, Version.string()) > 0)
            b.append(mode().equals("auto") ? "the service installs it after the next housekeeping and restarts; or now: researchzosho update now"
                                           : "a newer release: researchzosho update now  (the library and settings stay; a running job resumes after the restart)").append('\n');
        return b.toString();
    }

    /** Update now, to the latest release (or {@code version}); restart the service at once when one is installed. */
    public static Outcome now(String version, boolean restart, PrintStream out) {
        String running = Version.string();
        if (!Version.isRelease()) return new Outcome(false, running, running, "this is a run from the source tree; update it with git", false, Result.NOT_HERE);
        Path root = root();
        if (root == null) return new Outcome(false, running, running, "cannot find the install root (no lib/ beside this jar)", false, Result.NOT_HERE);
        return guarded(lockFile(), pendingFile(), () -> nowLocked(root, version, restart, out));
    }

    /** The update itself, under the lock: the version is checked again against the install, which another update may have changed. */
    static Outcome nowLocked(Path root, String version, boolean restart, PrintStream out) {
        String have = installedVersion(root);
        String target = version != null ? version : Version.latest();
        if (target == null) return new Outcome(false, have, have, "could not reach GitHub for the latest release", false, Result.FAILED);
        if (Version.compare(target, have) <= 0) return new Outcome(false, have, target, "already " + have, false, Result.CURRENT);
        try {
            out.println("researchzosho: updating " + have + " to " + target);   // "to", not an arrow: a Windows console has no arrow and printed "?"
            String base = System.getenv("RESEARCHZOSHO_DOWNLOAD_BASE");
            return install(Service.os(), root, have, target, base == null || base.isBlank() ? "https://github.com/" + REPO + "/releases/download/v" + target : base,
                    restart, Places.live(), out, Updater::startDetached);
        } catch (Exception e) {
            return new Outcome(false, have, target, "not updated: " + e.getMessage());
        }
    }

    /**
     * Where the update looks besides the install: the running server's pid record, the Windows service's task script, the log the Windows
     * helper writes, the lock and the helper's mark. The tests pass their own, so they never see this machine's server.
     */
    record Places(Path pidFile, Path taskScript, Path log, Path lock, Path pending) {
        /** The lock and the mark beside the log's folder, as the tests make them. */
        Places(Path pidFile, Path taskScript, Path log) { this(pidFile, taskScript, log, log.getParent().resolveSibling("update.lock"), log.getParent().resolveSibling("update.pending")); }

        static Places live() {
            return new Places(Service.pidFile(), Service.windowsTaskScript(Path.of(System.getProperty("user.home"))), Config.home().resolve("logs").resolve("update.log"), lockFile(), pendingFile());
        }
    }

    /** Starts a command and does not wait for what it starts in turn. */
    interface Starter { void start(List<String> command) throws IOException; }

    /** As below, with the lib/ folder this program runs from, whose files are never removed while it runs. */
    static Outcome install(Service.Os os, Path root, String have, String target, String base, boolean restart, Places places, PrintStream out, Starter starter) throws Exception {
        return install(os, root, have, target, base, restart, places, out, starter, runningLib());
    }

    /**
     * The update for the platform named. The release is fetched, checked and unpacked beside the install, and moved into {@code
     * versions/<target>} in one rename on the same drive; a version kept from before (a way back taken) is used as it is. An install of the
     * first layout takes the new one first (on Windows its files move once the program has ended). Then the pointer names {@code target},
     * the versions beyond two go, and the service restarts at once: on Linux and macOS now, on Windows by the helper once this program, or
     * the server it stops, has ended. A failed download or check leaves everything as it was, the pointer too. {@code running}: the lib/
     * folder this program runs from, never removed.
     */
    static Outcome install(Service.Os os, Path root, String have, String target, String base, boolean restart, Places places, PrintStream out, Starter starter, Path running) throws Exception {
        Layout l = new Layout(root, os);
        boolean flat = !l.versioned();
        String from = flat ? have : l.current();
        Path dir = l.versions().resolve(target);
        Path work = null;
        boolean handed = false;
        try {
            if (Files.isDirectory(dir.resolve("lib"))) out.println("researchzosho: " + target + " is kept from before; nothing to download");
            else {
                work = Files.createTempDirectory(root.toAbsolutePath().getParent(), ".researchzosho-update-");
                Path fresh = fetchAndUnpack(root, target, base, work, out);
                Files.createDirectories(l.versions());
                deleteTree(dir);   // a folder an attempt left that did not finish
                Files.move(fresh, dir, StandardCopyOption.ATOMIC_MOVE);   // beside the install, on the same drive: one rename
            }
            if (flat && !l.windows()) migrate(l, from, out);   // the first layout's files into versions/<from>, by hard links: the running program keeps its own
            point(l, target);
            if (!l.windows()) launchers(l);
            out.println("researchzosho: " + target + " is in place in " + dir + ", and " + (l.windows() ? POINTER : CURRENT) + " names it");
            prune(l, new HashSet<>(List.of(target, from)), running, out);
            if (!l.windows()) {
                String note = "updated to " + target + "; " + from + " stays beside it as the way back (researchzosho update back)";
                return new Outcome(true, have, target, note + (restart ? "; " + restartOnto(target, out) : "; restart the service to run it"));
            }
            Windows w = windows(l, flat, from, target, restart, places, out, starter, work);
            handed = w.handed();
            return new Outcome(true, have, target, w.note(), w.handed());
        } finally {
            if (!handed && work != null) deleteTree(work);
        }
    }

    /**
     * The service restarts onto {@code version} now. The mark says so first, so the jobs the stop interrupts say they resume after an update
     * to it; when no service restarts, the mark goes.
     */
    static String restartOnto(String version, PrintStream out) {
        try { Files.createDirectories(restartMark().getParent()); Files.writeString(restartMark(), version + "\n", StandardCharsets.UTF_8); } catch (IOException ignored) { }
        String said = restartService(out);
        if (!said.equals("the service restarted")) { try { Files.deleteIfExists(restartMark()); } catch (IOException ignored) { } }
        return said;
    }

    /**
     * The first layout's files (bin/, lib/, jre/ and the rest at the root) go into {@code versions/<from>}, each as a hard link (a copy where
     * the disk has none), so the running program keeps the files it has open and the ones it opens later; then the pointer names {@code from}
     * and the launchers in bin/ are this program's. The names at the root that only the running program still uses go at the next update.
     */
    static void migrate(Layout l, String from, PrintStream out) throws IOException {
        Path dest = l.versions().resolve(from);
        if (!Files.isDirectory(dest.resolve("lib"))) {
            Path part = l.versions().resolve("." + from + ".part");
            deleteTree(part);
            Files.createDirectories(part);
            try (var s = Files.list(l.root())) {
                for (Path p : s.toList()) {
                    String n = p.getFileName().toString();
                    if (n.equals(VERSIONS) || n.equals(CURRENT) || n.equals(POINTER) || n.startsWith(".")) continue;
                    linkTree(p, part.resolve(n));
                }
            }
            Files.move(part, dest, StandardCopyOption.ATOMIC_MOVE);
        }
        point(l, from);
        launchers(l);
        out.println("researchzosho: this install now keeps its versions side by side, under " + l.versions() + "; " + from + " is the first of them");
    }

    /** A copy of a tree made of hard links, a symbolic link staying one: nothing is written twice, and the original names stay for the program that runs from them. */
    static void linkTree(Path from, Path to) throws IOException {
        if (Files.isSymbolicLink(from)) { Files.createSymbolicLink(to, Files.readSymbolicLink(from)); return; }
        if (Files.isDirectory(from)) {
            Files.createDirectories(to);
            try (var s = Files.list(from)) { for (Path c : s.toList()) linkTree(c, to.resolve(c.getFileName().toString())); }
            return;
        }
        try { Files.createLink(to, from); }
        catch (IOException | UnsupportedOperationException noLinks) { Files.copy(from, to, StandardCopyOption.COPY_ATTRIBUTES); }
    }

    /** The pointer names {@code version}: the link replaced in one rename on Linux and macOS, the file on Windows. */
    static void point(Layout l, String version) throws IOException {
        if (l.windows()) { AtomicWrite.text(l.pointer(), version + "\r\n"); return; }
        Path tmp = l.root().resolve(".current-" + ProcessHandle.current().pid());
        Files.deleteIfExists(tmp);
        Files.createSymbolicLink(tmp, Path.of(VERSIONS, version));   // relative: the install can be moved whole
        try { Files.move(tmp, l.link(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (IOException e) { Files.deleteIfExists(tmp); throw e; }
    }

    /** The launcher in {@code <root>/bin} for Linux and macOS: it runs {@code name} from the version {@code current} points at. */
    static String unixLauncher(String name) {
        return "#!/bin/sh\n"
                + "# " + LAUNCHER_MARK + " that 'current' points at. researchzosho update installs a new version beside it in versions/ and moves the pointer.\n"
                + "p=$0\n"
                + "while [ -h \"$p\" ]; do l=$(ls -ld \"$p\"); t=${l#*' -> '}; case $t in /*) p=$t ;; *) p=$(dirname \"$p\")/$t ;; esac; done\n"
                + "root=$(cd \"$(dirname \"$p\")/..\" && pwd -P) || exit 1\n"
                + "exec \"$root/current/bin/" + name + "\" \"$@\"\n";
    }

    /** The launcher in {@code <root>\bin} for Windows: it runs {@code name} from the version {@code current.txt} names. ASCII only, CRLF. */
    static String windowsLauncher(String name) {
        return String.join("\r\n",
                "@echo off",
                "rem " + LAUNCHER_MARK + " that current.txt names. researchzosho update installs a new version beside it in versions\\ and rewrites the name.",
                "setlocal",
                "set \"RZ_ROOT=%~dp0..\"",
                "set /p RZ_VERSION=<\"%RZ_ROOT%\\current.txt\"",
                "endlocal & call \"%~dp0..\\versions\\%RZ_VERSION%\\bin\\" + name + ".bat\" %*",
                "exit /b %ERRORLEVEL%",
                "");
    }

    /** The launchers this program writes into {@code <root>/bin}, written when they say anything else. */
    static void launchers(Layout l) throws IOException {
        Path bin = l.root().resolve("bin");
        Files.createDirectories(bin);
        for (String name : List.of("researchzosho", "zosho")) {
            Path f = bin.resolve(l.windows() ? name + ".bat" : name);
            String text = l.windows() ? windowsLauncher(name) : unixLauncher(name);
            try { if (Files.isRegularFile(f) && !Files.isSymbolicLink(f) && Files.readString(f, StandardCharsets.UTF_8).equals(text)) continue; } catch (IOException ignored) { }
            AtomicWrite.text(f, text);   // a new file in one rename: a launcher running now keeps reading its own
            if (!l.windows()) { try { Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("rwxr-xr-x")); } catch (UnsupportedOperationException ignored) { } }
        }
    }

    /**
     * The versions beyond {@code keep} go, except the one this program runs from; then, on an install of the new layout, the first layout's
     * names at the root that nothing runs from any more. A folder that cannot be removed (in use on Windows) goes at the next update.
     */
    static void prune(Layout l, Set<String> keep, Path running, PrintStream out) {
        Path runningHome = running == null ? null : running.toAbsolutePath().normalize().getParent();
        for (String v : l.installed()) {
            Path d = l.versions().resolve(v);
            if (keep.contains(v) || d.toAbsolutePath().normalize().equals(runningHome)) continue;
            try { deleteTree(d); out.println("researchzosho: " + v + " removed; two versions are kept"); }
            catch (IOException inUse) { out.println("researchzosho: " + v + " is still in use; it is removed at the next update"); }
        }
        boolean runsFromRoot = runningHome != null && runningHome.equals(l.root().toAbsolutePath().normalize());
        if (!l.versioned() || runsFromRoot) return;
        for (String n : List.of("lib", "jre", "README.md", "LICENSE")) { try { deleteTree(l.root().resolve(n)); } catch (IOException ignored) { } }
    }

    /**
     * {@code researchzosho update back}: the version before the one that runs runs again, and the service restarts onto it at once. The one
     * gone back from stays beside it, and the auto mode does not take it again; {@code researchzosho update now} goes to it again.
     */
    public static Outcome back(boolean restart, PrintStream out) {
        String running = Version.string();
        if (!Version.isRelease()) return new Outcome(false, running, running, "this is a run from the source tree; it has no versions to go back to", false, Result.NOT_HERE);
        Path root = root();
        if (root == null) return new Outcome(false, running, running, "cannot find the install root (no lib/ beside this jar)", false, Result.NOT_HERE);
        return guarded(lockFile(), pendingFile(), () -> {
            try { return backLocked(Service.os(), root, restart, Places.live(), out, Updater::startDetached); }
            catch (Exception e) { return new Outcome(false, running, running, "not changed: " + e.getMessage()); }
        });
    }

    static Outcome backLocked(Service.Os os, Path root, boolean restart, Places places, PrintStream out, Starter starter) throws Exception {
        Layout l = new Layout(root, os);
        String have = installedVersion(l);
        if (!l.versioned()) return new Outcome(false, have, have, "this install keeps one version; the way back is there after its next update, which keeps " + have + " beside the new one", false, Result.NOT_HERE);
        String prev = previous(l, have);
        if (prev == null) return new Outcome(false, have, have, "no version older than " + have + " is kept; nothing was changed", false, Result.FAILED);
        point(l, prev);
        try { Files.createDirectories(heldMark().getParent()); Files.writeString(heldMark(), have + "\n", StandardCharsets.UTF_8); } catch (IOException ignored) { }
        out.println("researchzosho: " + prev + " runs again; " + have + " stays beside it");
        String stays = "back to " + prev + "; " + have + " stays beside it, the auto-update does not take it again, and researchzosho update now goes to it again";
        if (!l.windows()) return new Outcome(true, have, prev, stays + (restart ? "; " + restartOnto(prev, out) : "; restart the service to run it"));
        Windows w = windows(l, false, have, prev, restart, places, out, starter, null);
        return new Outcome(true, have, prev, stays + ". " + w.note(), w.handed());
    }

    /** The version to go back to: the newest kept below {@code current}; null when none is. */
    static String previous(Layout l, String current) {
        String below = null;
        for (String v : l.installed()) if (Version.compare(v, current) < 0) below = v;
        return below;
    }

    /** The auto mode's turn: after the housekeeping, at once, whether or not a job runs (the jobs resume after the restart). Returns what it did, or "". */
    public static String maybeAuto(LibraryStore store) {
        if (!mode().equals("auto")) return "";
        boolean release = Version.isRelease();
        String latest = release ? Version.latest() : null;
        Path root = root();
        String whyNot = whyNot(release, latest, root == null ? Version.string() : installedVersion(root), held());
        if (!whyNot.isEmpty()) { Crews.log(store, "update", "auto: " + whyNot, 0); return ""; }   // said, so a night that did nothing is never silent
        var buf = new ByteArrayOutputStream();
        Outcome o = now(latest, true, new PrintStream(buf, true));
        Crews.log(store, "update", o.note(), 0);
        // Windows: the helper starts the service again once this server has ended
        if (o.finishesAfterExit()) Runtime.getRuntime().exit(0);
        return o.note();
    }

    /** Why the auto mode does nothing tonight, in a line for the log; "" when a newer release should be installed. {@code held}: the version gone back from. */
    static String whyNot(boolean release, String latest, String installed, String held) {
        if (!release) return "this is not an installed release, so nothing is installed";
        if (latest == null) return "the latest release could not be read from GitHub; the next try is tomorrow night";
        if (Version.compare(latest, installed) <= 0) return "up to date (" + installed + ")";
        if (held != null && Version.compare(latest, held) <= 0) return "you went back from " + held + " with researchzosho update back, so the auto-update waits for a release newer than it; researchzosho update now installs " + latest;
        return "";
    }

    /**
     * Download {@code researchzosho-<version>.tar.gz} and SHA256SUMS from {@code base} into {@code work}, check the one against the other,
     * and unpack it there. Returns the unpacked {@code researchzosho} folder. Throws with a plain reason.
     */
    static Path fetchAndUnpack(Path root, String version, String base, Path work, PrintStream out) throws Exception {
        // an install that carries its own Java (a jre/ in the version that runs, or at the root of the first layout) stays one: the next version's build for this platform
        Layout here = new Layout(root, Service.os());
        String cur = here.current();
        boolean runtime = Files.isDirectory(root.resolve("jre")) || (cur != null && Files.isDirectory(here.versions().resolve(cur).resolve("jre")));
        String tar = "researchzosho-" + version + (runtime ? "-" + platformTag() : "") + ".tar.gz";
        Path tarPath = work.resolve(tar);
        fetch(base + "/" + tar, tarPath);
        Path sums = work.resolve("SHA256SUMS");
        try { fetch(base + "/SHA256SUMS", sums); } catch (IOException e) { throw new IOException("the release has no SHA256SUMS; refusing an unchecked download"); }
        String expect = null;
        for (String line : Files.readAllLines(sums, StandardCharsets.UTF_8)) {
            String[] p = line.strip().split("\\s+");
            if (p.length >= 2 && (p[1].equals(tar) || p[1].equals("./" + tar) || p[1].equals("*" + tar))) expect = p[0].toLowerCase(Locale.ROOT);
        }
        if (expect == null) throw new IOException(tar + " is not listed in SHA256SUMS");
        String actual = sha256(tarPath);
        if (!expect.equals(actual)) throw new IOException("checksum mismatch for " + tar + "; refusing to install\n  expected " + expect + "\n  got      " + actual);
        out.println("researchzosho: checksum verified");
        Path unpack = work.resolve("x");
        Files.createDirectories(unpack);
        Process p = new ProcessBuilder("tar", "xzf", tarPath.toString(), "-C", unpack.toString()).redirectErrorStream(true).start();
        String o = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (p.waitFor() != 0) throw new IOException("could not unpack " + tar + ": " + o.strip());
        Path fresh = unpack.resolve("researchzosho");
        if (!Files.isDirectory(fresh.resolve("lib"))) throw new IOException(tar + " does not contain researchzosho/lib");
        if (runtime && !Files.isDirectory(fresh.resolve("jre"))) throw new IOException(tar + " carries no runtime; this install has one, and the next must too");
        return fresh;
    }

    // ── Windows: the helper that finishes once the program has ended ───────────────────────────────────────────────────

    /**
     * What the Windows helper is given. {@code flat}: the install is of the first layout, and its files move into {@code versions\<from>}
     * once the program has ended, the launchers in {@code launchers} then going into {@code <root>\bin}. {@code waitFor}: the processes that
     * must end first, this program and a server it stopped. {@code server}: that server, or 0. {@code launcher}: the process that started
     * this program (the command window running researchzosho.bat, which reads the rest of the .bat after Java ends), waited for a few seconds
     * at most, or 0. {@code service}: the task script to start again, or null.
     */
    record WindowsSwap(Path root, Path versions, Path launchers, Path work, Path script, List<Long> waitFor, long server, long launcher,
                       Path service, Path log, String from, String to, boolean flat, Path lock, Path pending) { }

    /** What the Windows part of an update did: a helper handed the rest, and the note. */
    record Windows(boolean handed, String note) { }

    /**
     * Windows, the version in place and the pointer naming it: a helper is needed when the install's first-layout files must move (only
     * once the program has ended), or when the service must start again after this program or the server it stops has ended. Without
     * either, the update is done, and the next start of the program runs the new version.
     */
    static Windows windows(Layout l, boolean flat, String from, String to, boolean restart, Places places, PrintStream out, Starter starter, Path work) throws Exception {
        long self = ProcessHandle.current().pid();
        Long recorded = Service.recordedPid(places.pidFile());
        boolean selfServes = recorded != null && recorded == self;   // the service updating itself (auto mode)
        Long server = Service.runningServer(places.pidFile());
        boolean serviceComesBack = restart && (selfServes || server != null) && Files.isRegularFile(places.taskScript());
        boolean stopServer = server != null && (flat || restart);   // its files move, or it restarts onto the new version
        if (!flat && !serviceComesBack && !stopServer) {
            String said = to + " is in place; the next start of the program runs it.";
            if (server != null || selfServes) said += " The server runs " + from + " until it restarts: researchzosho service restart.";
            return new Windows(false, said);
        }
        // the logon task's script looks for the launcher's own Java beside bin\; from now on it is in the version current.txt names
        if (flat && Files.isRegularFile(places.taskScript())) {
            String was = Files.readString(places.taskScript(), StandardCharsets.UTF_8), now = Service.withVersionedJava(was);
            if (!now.equals(was)) Files.writeString(places.taskScript(), now, StandardCharsets.UTF_8);
        }
        Path folder = work != null ? work : Files.createTempDirectory(l.root().toAbsolutePath().getParent(), ".researchzosho-update-");
        boolean handed = false;
        try {
            Path launchers = folder.resolve("launchers");
            Files.createDirectories(launchers);
            for (String name : List.of("researchzosho", "zosho")) Files.writeString(launchers.resolve(name + ".bat"), windowsLauncher(name), StandardCharsets.US_ASCII);
            List<Long> waitFor = server == null ? List.of(self) : List.of(self, server);
            long launcher = ProcessHandle.current().parent().map(ProcessHandle::pid).orElse(0L);
            WindowsSwap swap = new WindowsSwap(l.root().toAbsolutePath(), l.versions().toAbsolutePath(), launchers.toAbsolutePath(), folder.toAbsolutePath(), folder.resolve("finish-update.ps1").toAbsolutePath(),
                    waitFor, server == null ? 0 : server, launcher, serviceComesBack ? places.taskScript() : null, places.log().toAbsolutePath(), from, to, flat, places.lock().toAbsolutePath(), places.pending().toAbsolutePath());
            Files.createDirectories(swap.log().getParent());
            // the update goes on after this program ends: the mark says so to any other update until the helper removes it
            Files.createDirectories(swap.pending().getParent());
            Files.writeString(swap.pending(), swap.work() + "\n", StandardCharsets.UTF_8);
            // with a byte order mark: Windows PowerShell reads a script without one as ANSI, and a user folder can have any letters
            Files.writeString(swap.script(), "﻿" + windowsHelper(swap), StandardCharsets.UTF_8);
            starter.start(windowsHelperStart(swap.script(), swap.root().getParent()));
            handed = true;
            // the jobs the stop interrupts say they resume after an update to it
            if (serviceComesBack || stopServer) { try { Files.createDirectories(restartMark().getParent()); Files.writeString(restartMark(), to + "\n", StandardCharsets.UTF_8); } catch (IOException ignored) { } }
            if (stopServer) Service.stopRecorded(places.pidFile(), out);
            return new Windows(true, windowsNote(swap));
        } finally {
            if (!handed) { deleteTree(folder); Files.deleteIfExists(places.pending()); }
        }
    }

    /** A PowerShell string literal. */
    private static String ps(Object v) { return "'" + String.valueOf(v).replace("'", "''") + "'"; }

    /**
     * The helper: wait for this program (and the stopped server) to end; for an install of the first layout, move its files into {@code
     * versions\<from>} and put the launchers that read {@code current.txt} into {@code bin}. A file that cannot be moved is tried again for
     * half a minute (a virus scanner reading new files holds them for a moment); when it still cannot, what was moved is put back, so the
     * install is never half moved and the old version runs as before. Then it starts the service again when it was running, writes what it
     * did to the log, and removes its own folder.
     */
    static String windowsHelper(WindowsSwap s) {
        StringBuilder waits = new StringBuilder();
        for (Long id : s.waitFor()) waits.append(waits.isEmpty() ? "" : ", ").append(id);
        return "# ResearchZosho: finishes 'researchzosho update' once the program has ended. Written by the program; it removes itself.\n"
                + "$ErrorActionPreference = 'Continue'\n"
                + "$from = " + ps(s.from()) + "; $to = " + ps(s.to()) + "\n"
                + "$root = " + ps(s.root()) + "\n"
                + "$versions = " + ps(s.versions()) + "\n"
                + "$launchers = " + ps(s.launchers()) + "\n"
                + "$flat = " + (s.flat() ? "$true" : "$false") + "\n"
                + "$work = " + ps(s.work()) + "\n"
                + "$log = " + ps(s.log()) + "\n"
                + "$service = " + ps(s.service() == null ? "" : s.service()) + "\n"
                + "$server = " + s.server() + "\n"
                + "$waitFor = @(" + waits + ")\n"
                + "$launcher = " + s.launcher() + "\n"
                + "$lockPath = " + ps(s.lock() == null ? "" : s.lock()) + "\n"
                + "$pending = " + ps(s.pending() == null ? "" : s.pending()) + "\n"
                + """
                function Say([string]$m) { try { Add-Content -LiteralPath $log -Value ((Get-Date -Format 'yyyy-MM-dd HH:mm:ss') + '  ' + $m) -Encoding UTF8 } catch { } }
                function Test-Gone([long]$id, [int]$ms) {
                  $until = (Get-Date).AddMilliseconds($ms)
                  while ((Get-Date) -lt $until) { if ($null -eq (Get-Process -Id $id -ErrorAction SilentlyContinue)) { return $true }; Start-Sleep -Milliseconds 250 }
                  return $null -eq (Get-Process -Id $id -ErrorAction SilentlyContinue)
                }
                function Move-Path([string]$a, [string]$b) {
                  $why = ''
                  for ($i = 0; $i -lt 30; $i++) {
                    try { if (Test-Path -LiteralPath $a -PathType Container) { [System.IO.Directory]::Move($a, $b) } else { [System.IO.File]::Move($a, $b) }; return '' }
                    catch { $e = $_.Exception; while ($e.InnerException) { $e = $e.InnerException }; $why = $e.Message; Start-Sleep -Seconds 1 }
                  }
                  return $why
                }
                Say "Updating ResearchZosho from $from to $to in $root. Waiting for the program to end."
                $ended = $true
                foreach ($id in $waitFor) { if (-not (Test-Gone $id 600000)) { $ended = $false; Say "Process $id was still running after ten minutes." } }
                if ($launcher -gt 0) { [void](Test-Gone $launcher 5000) }
                Start-Sleep -Milliseconds 500
                # the update lock, held while the files move: another update that starts now finds it busy
                $held = $null
                if ($lockPath) { for ($i = 0; $i -lt 60 -and -not $held; $i++) { try { $held = [System.IO.File]::Open($lockPath, 'OpenOrCreate', 'ReadWrite', 'None') } catch { Start-Sleep -Milliseconds 500 } } }
                if (-not $ended) {
                  Say "The program did not end, so its files were not moved. $from runs as before. Close the program and run researchzosho update now again."
                } elseif ($flat) {
                  $dest = Join-Path $versions $from
                  New-Item -ItemType Directory -Force -Path $dest | Out-Null
                  $moved = @(); $why = ''; $failed = ''
                  foreach ($n in @('bin', 'lib', 'jre', 'README.md', 'LICENSE')) {
                    $p = Join-Path $root $n
                    if (-not (Test-Path -LiteralPath $p)) { continue }
                    $why = Move-Path $p (Join-Path $dest $n)
                    if ($why) { $failed = $n; break }
                    $moved += $n
                  }
                  if ($why) {
                    Say "Could not move $failed into $dest`: $why"
                    foreach ($n in $moved) { [void](Move-Path (Join-Path $dest $n) (Join-Path $root $n)) }
                    Say "Put $from back as it was; it runs as before. $to is kept in $versions and runs after the next update. Close every program that runs ResearchZosho, such as another terminal or an MCP host, and run researchzosho update now again."
                  } else {
                    New-Item -ItemType Directory -Force -Path (Join-Path $root 'bin') | Out-Null
                    Copy-Item -Path (Join-Path $launchers '*') -Destination (Join-Path $root 'bin') -Force
                    Say "$to is in place. $from moved into $dest and stays as the way back; the launchers in $root\\bin run the version current.txt names."
                  }
                } else {
                  Say "$to is in place in $versions, and current.txt names it."
                }
                if ($service -and (Test-Path -LiteralPath $service) -and ($server -eq 0 -or (Test-Gone $server 0))) {
                  Start-Process -FilePath powershell -ArgumentList ('-NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File "' + $service + '"') -WorkingDirectory (Split-Path -Parent $service) -WindowStyle Hidden
                  Say "Started the service again."
                }
                if ($held) { $held.Close() }
                if ($pending) { Remove-Item -LiteralPath $pending -Force -ErrorAction SilentlyContinue }
                Set-Location -LiteralPath (Split-Path -Parent $work)
                Remove-Item -LiteralPath $work -Recurse -Force -ErrorAction SilentlyContinue
                """;
    }

    /**
     * Start the helper detached: a throwaway PowerShell runs Start-Process, which gives the helper its own hidden window and none of this
     * program's handles. A child started straight from this JVM would inherit the caller's output pipe, and whoever reads this command's
     * output would wait for the helper, which waits for this command. No double quote in the command: the JVM's own quoting of the argument
     * would break on one; [char]34 writes it.
     */
    static List<String> windowsHelperStart(Path script, Path dir) {
        return List.of("powershell", "-NoProfile", "-NonInteractive", "-Command",
                "Start-Process -FilePath powershell -ArgumentList ('-NoProfile -NonInteractive -WindowStyle Hidden -ExecutionPolicy Bypass -File ' + [char]34 + "
                        + ps(script) + " + [char]34) -WorkingDirectory " + ps(dir) + " -WindowStyle Hidden");
    }

    /** Run the starting command to its end, with its output in a file: never a pipe the started helper could hold open. */
    static void startDetached(List<String> command) throws IOException {
        Path said = Files.createTempFile("researchzosho-update-start", ".txt");
        try {
            Process p = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(said.toFile()).start();
            if (!p.waitFor(60, TimeUnit.SECONDS)) { p.destroyForcibly(); throw new IOException("the helper that finishes the update did not start within a minute"); }
            String o = Files.readString(said, StandardCharsets.UTF_8).strip();
            if (p.exitValue() != 0) throw new IOException("the helper that finishes the update did not start (" + (o.isEmpty() ? "exit " + p.exitValue() : o) + ")");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        } finally {
            try { Files.deleteIfExists(said); } catch (IOException ignored) { }
        }
    }

    /** What an update says on Windows, where a helper finishes it after the program ends. */
    static String windowsNote(WindowsSwap s) {
        boolean selfServes = s.server() == 0 && s.service() != null;   // the service updating itself: no other server, and a service to start again
        StringBuilder b = new StringBuilder();
        if (selfServes) b.append(s.to()).append(" is in place. The server stops now, and the service starts again on ").append(s.to()).append('.');
        else {
            b.append(s.to()).append(" is in place.");
            if (s.flat()) b.append(" The update finishes when this command ends: the install moves to keeping its versions side by side. Open a new terminal and run researchzosho --version to see ").append(s.to()).append('.');
            if (s.server() != 0 && s.service() != null) b.append(" The server was stopped, and the service starts again on ").append(s.to()).append(s.flat() ? " once the files are in place." : " once it has ended.");
            else if (s.server() != 0) b.append(" The server was stopped for the update. Start it again when the update has finished, with researchzosho serve or researchzosho service install.");
        }
        b.append(" What the update did is written to ").append(s.log()).append('.');
        return b.toString();
    }

    /** Restart the installed service, if any; returns what happened. */
    public static String restartService(PrintStream out) {
        try {
            String os = Service.os().name().toLowerCase(Locale.ROOT);
            List<String> cmd = os.startsWith("lin") ? List.of("systemctl", "--user", "restart", Service.NAME)
                    : os.startsWith("mac") ? List.of("launchctl", "kickstart", "-k", "gui/" + Service.uid() + "/" + Service.MAC_LABEL)
                    : List.of("schtasks", "/Run", "/TN", Service.WIN_TASK);
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String o = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            int rc = p.waitFor();
            String r = rc == 0 ? "the service restarted" : "the service did not restart (" + (o.isEmpty() ? "exit " + rc : o) + "); start it by hand";
            out.println("researchzosho: " + r);
            return r;
        } catch (Exception e) {
            return "no service to restart (" + e.getMessage() + ")";
        }
    }

    /** The release's name for this machine's build with its own runtime: linux-x64, linux-arm64, macos-x64, macos-arm64, windows-x64. */
    static String platformTag() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        String o = os.contains("win") ? "windows" : os.contains("mac") || os.contains("darwin") ? "macos" : "linux";
        String a = arch.contains("aarch64") || arch.contains("arm64") ? "arm64" : "x64";
        return o + "-" + a;
    }

    private static void fetch(String url, Path to) throws IOException {
        try {
            HttpResponse<Path> r = HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(5))
                    .header("User-Agent", "ResearchZosho/" + Version.string()).GET().build(), HttpResponse.BodyHandlers.ofFile(to));
            if (r.statusCode() != 200) throw new IOException("HTTP " + r.statusCode() + " for " + url);
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("interrupted"); }
    }

    static String sha256(Path p) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (var in = Files.newInputStream(p)) { byte[] buf = new byte[1 << 16]; int n; while ((n = in.read(buf)) > 0) md.update(buf, 0, n); }
            StringBuilder sb = new StringBuilder(); for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) { throw new IOException(e); }
    }

    static void deleteTree(Path p) throws IOException {
        if (p == null || !Files.exists(p, LinkOption.NOFOLLOW_LINKS)) return;
        if (Files.isSymbolicLink(p)) { Files.delete(p); return; }
        try (var s = Files.walk(p)) { for (Path x : s.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(x); }
    }
}
