package org.researchzosho;

import org.researchzosho.tools.Outbound;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.regex.Pattern;
/** The release version: the jar's manifest says it; a run from the source tree says so instead. */
public final class Version {
    private Version() { }
    private static final HttpClient HTTP = Outbound.client(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());

    public static String string() {
        String v = Version.class.getPackage() == null ? null : Version.class.getPackage().getImplementationVersion();
        if (v != null && !v.isBlank()) return v;
        String n = number();
        return n == null ? "dev (from the source tree)" : n + " (source tree)";
    }

    /** The version number alone: from the jar's manifest, else from the resource the build writes; null when neither says. */
    public static String number() {
        String v = Version.class.getPackage() == null ? null : Version.class.getPackage().getImplementationVersion();
        if (v != null && !v.isBlank()) return v.strip();
        try (InputStream in = Version.class.getResourceAsStream("/org/researchzosho/version.txt")) {
            if (in == null) return null;
            String r = new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
            return r.isEmpty() ? null : r;
        } catch (IOException e) { return null; }
    }

    /** Whether this is a release build (a number), not a run from the source tree. */
    public static boolean isRelease() { return string().matches("\\d+\\.\\d+\\.\\d+"); }

    static final String RELEASES = "https://api.github.com/repos/Wyrdsekai/researchzosho/releases/latest";
    static Path cache() { return Config.home().resolve("latest-version.txt"); }
    /** One check at a time (0.8.4): two pages at once each started one. */
    private static final AtomicBoolean CHECKING = new AtomicBoolean();
    /** When the last check failed (GitHub not reached: offline, or refused), 0 when it did not; no page starts another for {@link #FAILED_WAIT_MS}. */
    private static volatile long failedAt;
    /** How long after a failed check no page starts another: an offline machine started one at every page. An hour, as a day is the usual wait. */
    static final long FAILED_WAIT_MS = 3_600_000;
    /** What a check asks, and the clock it is timed by; a test stands them in. */
    static volatile Supplier<String> LATEST = Version::latest;
    static volatile LongSupplier NOW = System::currentTimeMillis;
    private static volatile Thread checker;
    /**
     * Whether the cache is refreshed from GitHub at all. The suite turns it off for every test (NoLiveChecks): a test that makes a
     * home of its own started a check there, a request to GitHub from a test, and the answer was written into that home after the
     * test had ended, while its folder was being taken away (the public CI of 0.8.1; of 0.6.3 before it, when QuietHome was made
     * for the tests that knew to call it).
     */
    static volatile boolean refreshes = true;

    /**
     * The latest released version, from a cache that is refreshed in the background at most once a day;
     * null until the first refresh has happened. Never blocks the caller, never throws.
     */
    public static String latestCached() {
        try {
            Path c = cache();
            long now = NOW.getAsLong();
            long age = Files.exists(c) ? now - Files.getLastModifiedTime(c).toMillis() : Long.MAX_VALUE;
            String cached = Files.exists(c) ? Files.readString(c, StandardCharsets.UTF_8).strip() : null;
            if (refreshes && age > 24L * 3600 * 1000 && now - failedAt >= FAILED_WAIT_MS && CHECKING.compareAndSet(false, true)) {
                Thread t = new Thread(() -> {
                    try {
                        String v = LATEST.get();
                        if (v == null) failedAt = NOW.getAsLong();
                        else { failedAt = 0; Files.createDirectories(c.getParent()); Files.writeString(c, v, StandardCharsets.UTF_8); }
                    } catch (Exception e) { failedAt = NOW.getAsLong(); }
                    finally { CHECKING.set(false); }
                }, "version-check");
                t.setDaemon(true); checker = t; t.start();
            }
            return cached == null || cached.isEmpty() ? null : cached;
        } catch (Exception e) { return null; }
    }

    /** Waits for a check under way to end; for a test. */
    static void settleCheck() throws InterruptedException { Thread t = checker; if (t != null) t.join(30_000); }

    /** The latest released version, asked of GitHub now; null when it cannot be reached. */
    public static String latest() {
        try {
            var http = HTTP;
            var r = http.send(HttpRequest.newBuilder(URI.create(RELEASES)).timeout(Duration.ofSeconds(10))
                    .header("User-Agent", "ResearchZosho/" + string()).header("Accept", "application/vnd.github+json").GET().build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) return null;
            var m = Pattern.compile("\"tag_name\"\\s*:\\s*\"v?([0-9][^\"]*)\"").matcher(r.body());
            return m.find() ? m.group(1) : null;
        } catch (Exception e) { return null; }
    }

    /** "a.b.c" against "x.y.z": positive when the first is newer. */
    public static int compare(String a, String b) {
        String[] x = a.split("[^0-9]+"), y = b.split("[^0-9]+");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length && !x[i].isEmpty() ? Integer.parseInt(x[i]) : 0, q = i < y.length && !y[i].isEmpty() ? Integer.parseInt(y[i]) : 0;
            if (p != q) return p - q;
        }
        return 0;
    }

    /** One line when a newer release exists than this one, from the cache; "" otherwise or when unknown. */
    public static String updateNotice() {
        String latest = latestCached();
        if (latest == null || !isRelease() || compare(latest, string()) <= 0) return "";
        return "ResearchZosho " + latest + " is available (this is " + string() + "). Update: curl -fsSL https://researchzosho.org/install | sh  (Windows: irm https://researchzosho.org/install.ps1 | iex); the library and settings stay.";
    }
}
