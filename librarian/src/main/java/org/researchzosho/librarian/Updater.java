package org.researchzosho.librarian;

import org.researchzosho.Config;
import org.researchzosho.Version;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
 * Updating the installed program to the latest release: the same download the installers make, checked against the
 * release's own SHA256SUMS, unpacked beside the install and swapped in with two renames. The library and the settings
 * live elsewhere and are not touched. Windows cannot rename a folder a running program uses, and this program runs
 * from the install it replaces, so there a small helper does the two renames once the program has ended.
 *
 * <p>{@code RESEARCHZOSHO_UPDATE}: {@code check} (default) says when a newer release exists; {@code auto} lets the
 * daemon update itself at a quiet moment after the housekeeping, when no run is active, and restart; {@code off}
 * does neither. {@code researchzosho update now} does it by hand. A run from the source tree never updates.
 */
public final class Updater {

    private Updater() { }

    public static final String REPO = "Wyrdsekai/researchzosho";
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL).build();   // a GitHub release asset is a 302 to its store; without this `update now` said "HTTP 302" (a test box, 2026-09-10)

    /** check | auto | off */
    public static String mode() {
        String m = Config.get("RESEARCHZOSHO_UPDATE", "check").toLowerCase(Locale.ROOT).strip();
        return m.equals("auto") || m.equals("off") ? m : "check";
    }

    /** The install root: the parent of the lib/ directory holding this jar; null when running from the source tree. */
    public static Path root() {
        try {
            Path self = Path.of(Version.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (!self.toString().endsWith(".jar")) return null;
            Path lib = self.getParent();
            return lib == null || !lib.getFileName().toString().equals("lib") ? null : lib.getParent();
        } catch (Exception e) { return null; }
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

    /** {@code finishesAfterExit}: on Windows the new version is swapped in by a helper once this program has ended. */
    public record Outcome(boolean updated, String from, String to, String note, boolean finishesAfterExit, Result result) {
        public Outcome(boolean updated, String from, String to, String note, boolean finishesAfterExit) { this(updated, from, to, note, finishesAfterExit, updated ? Result.UPDATED : Result.FAILED); }
        public Outcome(boolean updated, String from, String to, String note) { this(updated, from, to, note, false); }
    }

    // ── one update at a time ──────────────────────────────────────────────────────────────────────────────────────

    /** The file an update holds locked from its start to its end. The system lets go of it when the program ends, however it ends. */
    static Path lockFile() { return Config.home().resolve("update.lock"); }

    /** The mark a Windows update leaves while its helper, which runs after this program has ended, swaps the files: the helper's folder. */
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
        } catch (IOException held) { return Files.exists(lock); }   // Windows: the helper holds the file open while it swaps
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
     * Runs an update under the lock. Another update holding it, or a Windows helper still swapping, makes this one BUSY at once: it does
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

    /** The version whose files are in the install now: another program may have updated them since this one started. */
    static String installedVersion(Path root) {
        try (var s = Files.list(root.resolve("lib"))) {
            for (Path p : s.toList()) {
                Matcher m = INSTALLED_JAR.matcher(p.getFileName().toString());
                if (m.matches()) return m.group(1);
            }
        } catch (IOException | RuntimeException unreadable) { }
        return Version.string();
    }

    private static final Pattern INSTALLED_JAR = Pattern.compile("^librarian-(\\d+\\.\\d+\\.\\d+)\\.jar$");

    private static final ObjectMapper JSON = new ObjectMapper();

    /** `update --json`: what a program that updates ResearchZosho reads before it asks for an update. */
    public static String statusJson() {
        Path root = root();
        String latest = Version.latest(), installed = root == null ? Version.string() : installedVersion(root);
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

    /** What `researchzosho update` says: the installed version, the latest, the mode, and what would happen. */
    public static String status() {
        String latest = Version.latest();
        StringBuilder b = new StringBuilder();
        b.append("installed: ").append(Version.string()).append(root() == null ? " (from the source tree; updates are git pull)" : " at " + root()).append('\n');
        b.append("latest:    ").append(latest == null ? "unknown (could not reach GitHub)" : latest).append('\n');
        b.append("mode:      ").append(mode()).append(" (RESEARCHZOSHO_UPDATE = check | auto | off; researchzosho update auto on|off)").append('\n');
        if (updating()) b.append("an update is running now\n");
        if (latest != null && Version.isRelease() && Version.compare(latest, Version.string()) > 0)
            b.append(mode().equals("auto") ? "the daemon updates itself after the next housekeeping, when idle; or now: researchzosho update now"
                                           : "a newer release: researchzosho update now  (the library and settings stay)").append('\n');
        return b.toString();
    }

    /** Update now, to the latest release (or {@code version}); restart the service when one is installed. */
    public static Outcome now(String version, boolean restart, PrintStream out) {
        String running = Version.string();
        if (!Version.isRelease()) return new Outcome(false, running, running, "this is a run from the source tree; update it with git", false, Result.NOT_HERE);
        Path root = root();
        if (root == null) return new Outcome(false, running, running, "cannot find the install root (no lib/ beside this jar)", false, Result.NOT_HERE);
        return guarded(lockFile(), pendingFile(), () -> nowLocked(root, version, restart, out));
    }

    /** The update itself, under the lock: the version is checked again against the files, which another update may have replaced. */
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
     * Where the update looks besides the install: the running server's pid record, the Windows service's task script
     * and the log the Windows helper writes. The tests pass their own, so they never see this machine's server.
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

    /**
     * The update for the platform named. Linux and macOS rename the new version into place now, because a running
     * program there keeps its open files after a rename, and restart the service. Windows cannot rename a folder a
     * running program uses, and this program runs from the install it replaces, so there the renames are handed to a
     * helper that does them after this program has ended ({@link #handOff}).
     */
    static Outcome install(Service.Os os, Path root, String have, String target, String base, boolean restart, Places places, PrintStream out, Starter starter) throws Exception {
        if (os != Service.Os.windows) {
            swapIn(root, target, base, out);
            String note = "updated to " + target;
            if (restart) note += "; " + restartService(out);
            else note += "; restart the service to run it";
            return new Outcome(true, have, target, note);
        }
        WindowsSwap swap = handOff(root, have, target, base, restart, places, out, starter);
        return new Outcome(true, have, target, windowsNote(swap), true);
    }

    /** The auto mode's turn: after the housekeeping, when no run is active. Returns what it did, or "" when nothing. */
    public static String maybeAuto(LibraryStore store, boolean idle) {
        if (!mode().equals("auto") || !Version.isRelease() || !idle) return "";
        String latest = Version.latest();
        if (latest == null || Version.compare(latest, Version.string()) <= 0) return "";
        var buf = new ByteArrayOutputStream();
        Outcome o = now(latest, true, new PrintStream(buf, true));
        Crews.log(store, "update", o.note(), 0);
        // Windows: the helper swaps the folders once this server has ended, then starts the service again
        if (o.finishesAfterExit()) Runtime.getRuntime().exit(0);
        return o.note();
    }

    /**
     * Download {@code researchzosho-<version>.tar.gz} and SHA256SUMS from {@code base}, verify, unpack beside the root,
     * and swap: root → root.old, new → root, then remove old. Throws with a plain reason on any failure, leaving the
     * install as it was.
     */
    static void swapIn(Path root, String version, String base, PrintStream out) throws Exception {
        Path work = Files.createTempDirectory(root.toAbsolutePath().getParent(), ".researchzosho-update-");
        try {
            Path fresh = fetchAndUnpack(root, version, base, work, out);
            Path old = root.resolveSibling(root.getFileName() + ".old");
            deleteTree(old);
            try {
                Files.move(root, old, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception e) {
                throw new IOException("could not move the running install aside (" + e.getMessage() + ")");
            }
            try {
                Files.move(fresh, root, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception e) {
                Files.move(old, root, StandardCopyOption.ATOMIC_MOVE);   // put it back
                throw new IOException("could not put the new install in place (" + e.getMessage() + "); the old one is back");
            }
            deleteTree(old);
            out.println("researchzosho: " + version + " is in place at " + root);
        } finally {
            deleteTree(work);
        }
    }

    /**
     * Download the release's tarball for this install and its SHA256SUMS into {@code work}, check the one against the
     * other, and unpack it there. Returns the unpacked {@code researchzosho} folder. Throws with a plain reason.
     */
    static Path fetchAndUnpack(Path root, String version, String base, Path work, PrintStream out) throws Exception {
        // an install that carries its own Java (a jre/ beside bin/) stays one: the next version's build for this platform
        boolean runtime = Files.isDirectory(root.resolve("jre"));
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

    /**
     * What the Windows helper is given. {@code waitFor}: the processes that hold the install's files, this program and
     * a server it stopped, whose end the helper waits for. {@code server}: that server, or 0. {@code launcher}: the
     * process that started this program (the command window running researchzosho.bat, which reads the rest of the
     * .bat after Java ends), waited for a few seconds at most, or 0. {@code service}: the task script to start again
     * after the swap, or null.
     */
    record WindowsSwap(Path root, Path fresh, Path old, Path work, Path script, List<Long> waitFor, long server, long launcher,
                       Path service, Path log, String from, String to, Path lock, Path pending) { }

    /**
     * Windows: download, check and unpack as the other platforms do, then write the helper script beside the install,
     * start it detached, and stop the running server, whose files it holds. Nothing changes on disk until this program
     * has ended; if the helper cannot be started, the download is removed and the server keeps running.
     */
    static WindowsSwap handOff(Path root, String from, String to, String base, boolean restart, Places places, PrintStream out, Starter starter) throws Exception {
        Path work = Files.createTempDirectory(root.toAbsolutePath().getParent(), ".researchzosho-update-");
        boolean handed = false;
        try {
            Path fresh = fetchAndUnpack(root, to, base, work, out);
            Path old = root.resolveSibling(root.getFileName() + ".old");
            try { deleteTree(old); } catch (IOException e) { throw new IOException(old + " is left from an earlier update and cannot be removed (" + e.getMessage() + ")"); }
            long self = ProcessHandle.current().pid();
            Long recorded = Service.recordedPid(places.pidFile());
            boolean selfServes = recorded != null && recorded == self;   // the daemon updating itself (auto mode)
            Long server = Service.runningServer(places.pidFile());
            List<Long> waitFor = server == null ? List.of(self) : List.of(self, server);
            Path service = restart && (selfServes || server != null) && Files.isRegularFile(places.taskScript()) ? places.taskScript() : null;
            long launcher = ProcessHandle.current().parent().map(ProcessHandle::pid).orElse(0L);
            WindowsSwap swap = new WindowsSwap(root.toAbsolutePath(), fresh.toAbsolutePath(), old.toAbsolutePath(), work.toAbsolutePath(), work.resolve("finish-update.ps1").toAbsolutePath(),
                    waitFor, server == null ? 0 : server, launcher, service, places.log().toAbsolutePath(), from, to, places.lock().toAbsolutePath(), places.pending().toAbsolutePath());
            Files.createDirectories(swap.log().getParent());
            // the update goes on after this program ends: the mark says so to any other update until the helper removes it
            Files.createDirectories(swap.pending().getParent());
            Files.writeString(swap.pending(), swap.work() + "\n", StandardCharsets.UTF_8);
            // with a byte order mark: Windows PowerShell reads a script without one as ANSI, and a user folder can have any letters
            Files.writeString(swap.script(), "\uFEFF" + windowsHelper(swap), StandardCharsets.UTF_8);
            starter.start(windowsHelperStart(swap.script(), swap.root().getParent()));
            handed = true;
            if (server != null) Service.stopRecorded(places.pidFile(), out);
            return swap;
        } finally {
            if (!handed) { deleteTree(work); Files.deleteIfExists(places.pending()); }
        }
    }

    /** A PowerShell string literal. */
    private static String ps(Object v) { return "'" + String.valueOf(v).replace("'", "''") + "'"; }

    /**
     * The helper: wait for this program (and the stopped server) to end, move the install aside, move the new version
     * in, remove the old one. A folder that cannot be moved is tried again for half a minute (a virus scanner reading
     * new files holds them for a moment); when it still cannot, whatever was moved is put back, so the install is
     * never half swapped. Then it starts the service again when it was running, writes what it did to the log, and
     * removes its own folder.
     */
    static String windowsHelper(WindowsSwap s) {
        StringBuilder waits = new StringBuilder();
        for (Long id : s.waitFor()) waits.append(waits.isEmpty() ? "" : ", ").append(id);
        return "# ResearchZosho: finishes 'researchzosho update now' once the program has ended. Written by the program; it removes itself.\n"
                + "$ErrorActionPreference = 'Continue'\n"
                + "$from = " + ps(s.from()) + "; $to = " + ps(s.to()) + "\n"
                + "$root = " + ps(s.root()) + "\n"
                + "$fresh = " + ps(s.fresh()) + "\n"
                + "$old = " + ps(s.old()) + "\n"
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
                function Move-Folder([string]$a, [string]$b) {
                  $why = ''
                  for ($i = 0; $i -lt 30; $i++) {
                    try { [System.IO.Directory]::Move($a, $b); return '' }
                    catch { $e = $_.Exception; while ($e.InnerException) { $e = $e.InnerException }; $why = $e.Message; Start-Sleep -Seconds 1 }
                  }
                  return $why
                }
                Say "Updating ResearchZosho from $from to $to in $root. Waiting for the program to end."
                $ended = $true
                foreach ($id in $waitFor) { if (-not (Test-Gone $id 600000)) { $ended = $false; Say "Process $id was still running after ten minutes." } }
                if ($launcher -gt 0) { [void](Test-Gone $launcher 5000) }
                Start-Sleep -Milliseconds 500
                # the update lock, held while the files are swapped: another update that starts now finds it busy
                $held = $null
                if ($lockPath) { for ($i = 0; $i -lt 60 -and -not $held; $i++) { try { $held = [System.IO.File]::Open($lockPath, 'OpenOrCreate', 'ReadWrite', 'None') } catch { Start-Sleep -Milliseconds 500 } } }
                if (-not $ended) {
                  Say "Nothing was changed. $from stays installed. Close the program and run researchzosho update now again."
                } else {
                  if (Test-Path -LiteralPath $old) { Remove-Item -LiteralPath $old -Recurse -Force -ErrorAction SilentlyContinue }
                  $why = Move-Folder $root $old
                  if ($why) {
                    Say "Could not move $root aside: $why Nothing was changed. $from stays installed. Close every program that runs ResearchZosho, such as another terminal or an MCP host, and run researchzosho update now again."
                  } else {
                    $why = Move-Folder $fresh $root
                    if ($why) {
                      Say "Could not put $to in place: $why"
                      $back = Move-Folder $old $root
                      if ($back) { Say "Could not put $from back either: $back The program is in $old. Rename that folder to $(Split-Path -Leaf $root) to use it again." }
                      else { Say "Put $from back in place. Nothing was changed." }
                    } else {
                      Say "$to is in place in $root."
                      Remove-Item -LiteralPath $old -Recurse -Force -ErrorAction SilentlyContinue
                      if (Test-Path -LiteralPath $old) { Say "Could not remove the old version in $old. The next update removes it." }
                    }
                  }
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
     * Start the helper detached: a throwaway PowerShell runs Start-Process, which gives the helper its own hidden
     * window and none of this program's handles. A child started straight from this JVM would inherit the caller's
     * output pipe, and whoever reads this command's output would wait for the helper, which waits for this command.
     * No double quote in the command: the JVM's own quoting of the argument would break on one; [char]34 writes it.
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

    /** What `update now` says on Windows, where the swap happens after it ends. */
    static String windowsNote(WindowsSwap s) {
        boolean selfServes = s.server() == 0 && s.service() != null;   // the daemon updating itself: no other server, and a service to start again
        StringBuilder b = new StringBuilder();
        if (selfServes) {
            b.append(s.to()).append(" is downloaded and checked. The server stops now so the update can finish, and the service starts again on ").append(s.to()).append('.');
        } else {
            b.append(s.to()).append(" is downloaded and checked. The update finishes when this command ends. Open a new terminal and run researchzosho --version to see ").append(s.to()).append('.');
            if (s.server() != 0 && s.service() != null) b.append(" The server was stopped for the update, and the service starts again on ").append(s.to()).append(" once the files are in place.");
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
        if (p == null || !Files.exists(p)) return;
        try (var s = Files.walk(p)) { for (Path x : s.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(x); }
    }
}
