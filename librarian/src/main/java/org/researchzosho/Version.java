package org.researchzosho;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** The release version: the jar's manifest says it; a run from the source tree says so instead. */
public final class Version {
    private Version() { }

    public static String string() {
        String v = Version.class.getPackage() == null ? null : Version.class.getPackage().getImplementationVersion();
        return v == null || v.isBlank() ? "dev (from the source tree)" : v;
    }

    /** Whether this is a release build (a number), not a run from the source tree. */
    public static boolean isRelease() { return string().matches("\\d+\\.\\d+\\.\\d+"); }

    static final String RELEASES = "https://api.github.com/repos/Wyrdsekai/researchzosho/releases/latest";
    static Path cache() { return Config.home().resolve("latest-version.txt"); }
    private static volatile boolean refreshing;

    /**
     * The latest released version, from a cache that is refreshed in the background at most once a day;
     * null until the first refresh has happened. Never blocks the caller, never throws.
     */
    public static String latestCached() {
        try {
            Path c = cache();
            long age = Files.exists(c) ? System.currentTimeMillis() - Files.getLastModifiedTime(c).toMillis() : Long.MAX_VALUE;
            String cached = Files.exists(c) ? Files.readString(c, StandardCharsets.UTF_8).strip() : null;
            if (age > 24L * 3600 * 1000 && !refreshing) {
                refreshing = true;
                Thread t = new Thread(() -> { try { String v = latest(); if (v != null) { Files.createDirectories(c.getParent()); Files.writeString(c, v, StandardCharsets.UTF_8); } } catch (Exception ignored) { } finally { refreshing = false; } }, "version-check");
                t.setDaemon(true); t.start();
            }
            return cached == null || cached.isEmpty() ? null : cached;
        } catch (Exception e) { return null; }
    }

    /** The latest released version, asked of GitHub now; null when it cannot be reached. */
    public static String latest() {
        try {
            var http = java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(5)).build();
            var r = http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(RELEASES)).timeout(java.time.Duration.ofSeconds(10))
                    .header("User-Agent", "ResearchZosho/" + string()).header("Accept", "application/vnd.github+json").GET().build(), java.net.http.HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) return null;
            var m = java.util.regex.Pattern.compile("\"tag_name\"\\s*:\\s*\"v?([0-9][^\"]*)\"").matcher(r.body());
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
