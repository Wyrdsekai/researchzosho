package org.researchzosho.librarian;

import org.researchzosho.Config;
import org.researchzosho.Version;

import java.io.IOException;
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

/**
 * Updating the installed program to the latest release: the same download the installers make, checked against the
 * release's own SHA256SUMS, unpacked beside the install and swapped in with two renames. The library and the settings
 * live elsewhere and are not touched.
 *
 * <p>{@code RESEARCHZOSHO_UPDATE}: {@code check} (default) says when a newer release exists; {@code auto} lets the
 * daemon update itself at a quiet moment after the housekeeping, when no run is active, and restart; {@code off}
 * does neither. {@code researchzosho update now} does it by hand. A run from the source tree never updates.
 */
public final class Updater {

    private Updater() { }

    public static final String REPO = "Wyrdsekai/researchzosho";
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL).build();   // a GitHub release asset is a 302 to its store; without this `update now` said "HTTP 302" (dolores, 2026-09-10)

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

    public record Outcome(boolean updated, String from, String to, String note) { }

    /** What `researchzosho update` says: the installed version, the latest, the mode, and what would happen. */
    public static String status() {
        String latest = Version.latest();
        StringBuilder b = new StringBuilder();
        b.append("installed: ").append(Version.string()).append(root() == null ? " (from the source tree; updates are git pull)" : " at " + root()).append('\n');
        b.append("latest:    ").append(latest == null ? "unknown (could not reach GitHub)" : latest).append('\n');
        b.append("mode:      ").append(mode()).append(" (RESEARCHZOSHO_UPDATE = check | auto | off; researchzosho update auto on|off)").append('\n');
        if (latest != null && Version.isRelease() && Version.compare(latest, Version.string()) > 0)
            b.append(mode().equals("auto") ? "the daemon updates itself after the next housekeeping, when idle; or now: researchzosho update now"
                                           : "a newer release: researchzosho update now  (the library and settings stay)").append('\n');
        return b.toString();
    }

    /** Update now, to the latest release (or {@code version}); restart the service when one is installed. */
    public static Outcome now(String version, boolean restart, java.io.PrintStream out) {
        String have = Version.string();
        if (!Version.isRelease()) return new Outcome(false, have, have, "this is a run from the source tree; update it with git");
        Path root = root();
        if (root == null) return new Outcome(false, have, have, "cannot find the install root (no lib/ beside this jar)");
        String target = version != null ? version : Version.latest();
        if (target == null) return new Outcome(false, have, have, "could not reach GitHub for the latest release");
        if (Version.compare(target, have) <= 0) return new Outcome(false, have, target, "already " + have);
        try {
            out.println("researchzosho: updating " + have + " → " + target);
            String base = System.getenv("RESEARCHZOSHO_DOWNLOAD_BASE");
            swapIn(root, target, base == null || base.isBlank() ? "https://github.com/" + REPO + "/releases/download/v" + target : base, out);
            String note = "updated to " + target;
            if (restart) note += "; " + restartService(out);
            else note += "; restart the service to run it";
            return new Outcome(true, have, target, note);
        } catch (Exception e) {
            return new Outcome(false, have, target, "not updated: " + e.getMessage());
        }
    }

    /** The auto mode's turn: after the housekeeping, when no run is active. Returns what it did, or "" when nothing. */
    public static String maybeAuto(LibraryStore store, boolean idle) {
        if (!mode().equals("auto") || !Version.isRelease() || !idle) return "";
        String latest = Version.latest();
        if (latest == null || Version.compare(latest, Version.string()) <= 0) return "";
        var buf = new java.io.ByteArrayOutputStream();
        Outcome o = now(latest, true, new java.io.PrintStream(buf, true));
        Crews.log(store, "update", o.note(), 0);
        return o.note();
    }

    /**
     * Download {@code researchzosho-<version>.tar.gz} and SHA256SUMS from {@code base}, verify, unpack beside the root,
     * and swap: root → root.old, new → root, then remove old. Throws with a plain reason on any failure, leaving the
     * install as it was.
     */
    static void swapIn(Path root, String version, String base, java.io.PrintStream out) throws Exception {
        // an install that carries its own Java (a jre/ beside bin/) stays one: the next version's build for this platform
        boolean runtime = Files.isDirectory(root.resolve("jre"));
        String tar = "researchzosho-" + version + (runtime ? "-" + platformTag() : "") + ".tar.gz";
        Path work = Files.createTempDirectory(root.toAbsolutePath().getParent(), ".researchzosho-update-");
        try {
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
            Path old = root.resolveSibling(root.getFileName() + ".old");
            deleteTree(old);
            try {
                Files.move(root, old, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception e) {
                throw new IOException("could not move the running install aside (" + e.getMessage() + "); on Windows stop the service first, then run researchzosho update now from a new terminal");
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

    /** Restart the installed service, if any; returns what happened. */
    public static String restartService(java.io.PrintStream out) {
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
        } catch (java.security.NoSuchAlgorithmException e) { throw new IOException(e); }
    }

    static void deleteTree(Path p) throws IOException {
        if (p == null || !Files.exists(p)) return;
        try (var s = Files.walk(p)) { for (Path x : s.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(x); }
    }
}
