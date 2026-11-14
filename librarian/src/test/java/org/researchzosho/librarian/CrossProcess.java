package org.researchzosho.librarian;

import org.researchzosho.Config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * A second process for the tests of what two processes on one library must not both do: each child is a JVM of its own on the test's
 * classpath, started by {@link #start}, and waits for a signal file so that the children begin together. A child prints what it did, one
 * line per thing, and exits.
 */
final class CrossProcess {

    private CrossProcess() { }

    /**
     * {@code lease <library> <go> <hold-ms>}: takes the library's lease, prints "held" or "refused", holds it that long, lets it go.
     * {@code ids <library> <go> <count>}: issues that many job ids, one a line. {@code home}: prints the per-machine settings folder.
     * {@code hold-serials <library>}: holds the id counter's guard, says "holding", and waits to be killed.
     * {@code serve}: the serve command as a person runs it, on the library its environment names, the lease looked at every 200 ms; the
     * process ends with the command's code.
     */
    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "lease" -> {
                waitFor(Path.of(args[2]));
                LibraryStore store = new LibraryStore(Path.of(args[1]));
                try (Lease l = Lease.acquire(store)) {
                    System.out.println("held " + ProcessHandle.current().pid());
                    System.out.flush();
                    Thread.sleep(Long.parseLong(args[3]));
                } catch (IOException e) { System.out.println("refused"); }
            }
            case "ids" -> {
                waitFor(Path.of(args[2]));
                LibraryStore store = new LibraryStore(Path.of(args[1]));
                int n = Integer.parseInt(args[3]);
                StringBuilder b = new StringBuilder();
                for (int i = 0; i < n; i++) b.append(store.nextJobId()).append('\n');
                System.out.print(b);
            }
            case "home" -> System.out.println(Config.home());
            case "hold-serials" -> {   // holds the id counter's guard until killed: a process that dies in the step
                LibraryStore store = new LibraryStore(Path.of(args[1]));
                FileLocks.under(store.serialsGuard(), () -> {
                    System.out.println("holding"); System.out.flush();
                    try { Thread.sleep(600_000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    return null;
                });
            }
            case "serve" -> {
                Lease.WATCH_MS = 200;
                LibrarianDaemon.GIVE_UP_WAIT_MS = 5_000;
                int rc = LibrarianCli.run(new String[]{"librarian", "serve", "--host", "127.0.0.1", "--port", "0", "--no-crews"}, "http://127.0.0.1:1", "stand-in");
                System.out.flush(); System.err.flush();
                System.exit(rc);
            }
            default -> throw new IllegalArgumentException(args[0]);
        }
        System.out.flush();
    }

    /** Waits until the signal file is there: the children begin together. */
    static void waitFor(Path go) throws InterruptedException {
        long until = System.currentTimeMillis() + 60_000;
        while (!Files.exists(go) && System.currentTimeMillis() < until) Thread.sleep(1);
    }

    /** A child JVM on this test's classpath, with its own home folder, running {@link #main} with these words. */
    static Process start(Path home, String... words) throws IOException { return start(Map.of(), home, words); }

    /** The same, with these environment variables set for it. */
    static Process start(Map<String, String> env, Path home, String... words) throws IOException {
        String jvm = ProcessHandle.current().info().command().orElse("java");
        List<String> cmd = new ArrayList<>(List.of(jvm, "-Duser.home=" + home, "-cp", System.getProperty("java.class.path"), CrossProcess.class.getName()));
        cmd.addAll(List.of(words));
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        pb.environment().putAll(env);
        return pb.start();
    }

    /** What a child printed, once it ended (a minute at most). */
    static String output(Process p) throws Exception {
        String out = new String(p.getInputStream().readAllBytes());
        if (!p.waitFor(60, TimeUnit.SECONDS)) { p.destroyForcibly(); throw new IllegalStateException("a child did not end: " + out); }
        return out;
    }
}
