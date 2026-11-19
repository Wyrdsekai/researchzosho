package org.researchzosho.librarian;

import org.researchzosho.Main;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * The proof that a process a record names is the one the program means (0.8.0). A record of a process number (a pid file, a lease
 * file) outlives the process, and the system gives the number to another: the number proves nothing. A process is the one meant only
 * when it lives, started no later than the record was last written (one started after it cannot be the one that wrote it), and its
 * whole command line is what the caller's rule accepts. The program stops a process only on this proof. Where a command line cannot
 * be read, nothing is proven and nothing is stopped.
 */
final class ProcessProof {
    private ProcessProof() { }

    /** A test's own reading of a command line; null: the system's. */
    static volatile Function<Long, List<String>> READ;

    private static String os() { return System.getProperty("os.name", "").toLowerCase(Locale.ROOT); }

    /**
     * The words of a live process's whole command line, as the system shows it: on Linux {@code /proc/<pid>/cmdline} (Java's own reading
     * of it is cut short for a long command line, and then holds no arguments at all); on macOS {@code ps -ww -o command= -p <pid>}; on
     * Windows the CommandLine that CIM gives ({@link #windowsCommand}); elsewhere Java's own. Empty when it cannot be read.
     */
    static List<String> words(long pid) {
        Function<Long, List<String>> r = READ;
        if (r != null) return r.apply(pid);
        try {
            Path proc = Path.of("/proc", String.valueOf(pid), "cmdline");
            if (Files.isReadable(proc)) {
                String all = new String(Files.readAllBytes(proc), StandardCharsets.UTF_8);
                return all.isEmpty() ? List.of() : List.of(all.split("\0"));
            }
            String os = os();
            if (os.contains("mac") || os.contains("darwin")) return split(run(List.of("ps", "-ww", "-o", "command=", "-p", String.valueOf(pid))));   // -ww: never cut to a width
            if (os.contains("win")) return split(run(windowsCommand(pid)));
            return ProcessHandle.of(pid).flatMap(h -> h.info().arguments()).map(List::of).orElse(List.of());
        } catch (IOException | RuntimeException e) { return List.of(); }
    }

    /** The command that gives a Windows process's whole command line: CIM's Win32_Process, by its number. */
    static List<String> windowsCommand(long pid) {
        return List.of("powershell", "-NoProfile", "-NonInteractive", "-Command", "(Get-CimInstance Win32_Process -Filter \"ProcessId=" + pid + "\").CommandLine");
    }

    private static List<String> split(String line) { String s = line == null ? "" : line.strip(); return s.isEmpty() ? List.of() : List.of(s.split("\\s+")); }

    /** What a short command prints, within five seconds; "" when it does not answer. */
    private static String run(List<String> cmd) throws IOException {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        try {
            p.getOutputStream().close();
            if (!p.waitFor(5, TimeUnit.SECONDS)) { p.destroyForcibly(); return ""; }
            return p.exitValue() == 0 ? new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8) : "";
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); p.destroyForcibly(); return ""; }
    }

    /**
     * How much later than the record's time a process may seem to have started and still be the one that wrote it. The check is against
     * a number the system gave to another process since, which takes far longer than this; but a file system that keeps times to the
     * second or two (HFS+, FAT) and the kernel's boot time in whole seconds (from which a process's start is reckoned) would otherwise
     * make the real server fail it.
     */
    static final Duration ALLOWANCE = Duration.ofSeconds(2);

    /** Whether a process that started at {@code started} may be the one that wrote a record last written at {@code written}. */
    static boolean startedBy(Instant started, Instant written) {
        return started != null && written != null && !started.isAfter(written.plus(ALLOWANCE));
    }

    /**
     * Whether the live process {@code pid} is the one {@code record} names: it is not this process, it started no later than the record
     * was last written ({@link #startedBy}, within the allowance), and its command line is what {@code ours} accepts.
     */
    static boolean proven(long pid, Path record, Predicate<List<String>> ours) {
        ProcessHandle h = ProcessHandle.of(pid).orElse(null);
        if (h == null || !h.isAlive() || pid == ProcessHandle.current().pid()) return false;
        Instant started = h.info().startInstant().orElse(null);
        Instant written;
        try { written = Files.getLastModifiedTime(record).toInstant(); } catch (IOException e) { return false; }
        if (!startedBy(started, written)) return false;
        return ours.test(words(pid));
    }

    /** The command line of this program's own server: its main class, then {@code serve}, and not a library an account service started. */
    static boolean server(List<String> words) {
        int main = words.indexOf(MAIN);
        return main >= 0 && main + 1 < words.size() && words.get(main + 1).equals("serve") && !words.contains("--vouched-by");
    }

    /** This program's main class, as a command line names it. */
    static final String MAIN = Main.class.getName();
}
