package org.researchzosho.librarian;

import org.researchzosho.Config;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
                ready(Path.of(args[2]));
                waitFor(Path.of(args[2]));
                LibraryStore store = new LibraryStore(Path.of(args[1]));
                try (Lease l = Lease.acquire(store)) {
                    System.out.println("held " + ProcessHandle.current().pid());
                    System.out.flush();
                    Thread.sleep(Long.parseLong(args[3]));
                } catch (IOException e) { System.out.println("refused"); }
            }
            case "ids" -> {
                ready(Path.of(args[2]));
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
            case "answers" -> {   // a library's server in a fresh process, as the program makes it: the median time of an answer, in ms
                LibraryStore store = new LibraryStore(Path.of(args[1])); store.init();
                LibrarianDaemon d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1);
                try {
                    HttpClient c = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
                    URI u = URI.create("http://127.0.0.1:" + d.port() + "/healthz");
                    List<Long> took = new ArrayList<>();
                    for (int i = 0; i < 45; i++) {
                        long t0 = System.nanoTime();
                        c.send(HttpRequest.newBuilder(u).GET().build(), HttpResponse.BodyHandlers.ofString());
                        if (i >= 5) took.add((System.nanoTime() - t0) / 1_000_000);
                    }
                    took.sort(null);
                    System.out.println("median " + took.get(took.size() / 2));
                } finally { d.stop(); }
            }
            default -> throw new IllegalArgumentException(args[0]);
        }
        System.out.flush();
    }

    /** Says this child is up and about to wait for the signal: a file beside it, named for its process. */
    static void ready(Path go) throws IOException { Files.writeString(go.resolveSibling(go.getFileName() + ".ready-" + ProcessHandle.current().pid()), ""); }

    /** Waits until {@code n} children of the signal {@code go} said they are up (at most a minute), so that it is given when all of them wait. */
    static void allReady(Path go, int n) throws IOException, InterruptedException {
        long until = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < until) {
            try (var s = Files.list(go.getParent())) { if (s.filter(p -> p.getFileName().toString().startsWith(go.getFileName() + ".ready-")).count() >= n) return; }
            Thread.sleep(20);
        }
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
