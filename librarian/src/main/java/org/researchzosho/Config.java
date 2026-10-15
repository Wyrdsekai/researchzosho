package org.researchzosho;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Settings, from a config file or the environment.
 *
 * <p>Lookup order for a key such as {@code RESEARCHZOSHO_DRIVE}, first hit wins:
 * <ol>
 *   <li>the environment variable — an explicit export always wins;</li>
 *   <li>the same setting under its CodeZaiku spelling in the environment ({@code CODEZAIKU_LIBRARIAN_DRIVE},
 *       then {@code CODEZAIKU_DRIVE}) — ResearchZosho lived inside CodeZaiku until 2026-09-06 and every
 *       install from that time is configured in those names;</li>
 *   <li>{@code $RESEARCHZOSHO_CONFIG} if set, else {@code <home>/config} — see {@link #home()};</li>
 *   <li>{@code /etc/researchzosho/config} — the system-wide file a package can drop.</li>
 * </ol>
 *
 * <p>File format: {@code key = value}, {@code #} comments, blank lines ignored. Keys may be written in
 * the environment form or a dotted shorthand — {@code RESEARCHZOSHO_DRIVE}, {@code drive} and
 * {@code CODEZAIKU_LIBRARIAN_DRIVE} all resolve to the same setting. In a shared {@code ~/.codezaiku/config}
 * the bare {@code drive} key is CodeZaiku's coding model, so there {@code librarian.drive} is read first.
 */
public final class Config {

    public static final String PREFIX = "RESEARCHZOSHO_";
    static final String LEGACY_PREFIX = "CODEZAIKU_";

    /** Settings whose CodeZaiku spelling carried a {@code LIBRARIAN_} infix. */
    private static final Map<String, String> LEGACY_ALIAS = Map.of(
            "RESEARCHZOSHO_DRIVE", "CODEZAIKU_LIBRARIAN_DRIVE",
            "RESEARCHZOSHO_URL", "CODEZAIKU_LIBRARIAN_URL",
            "RESEARCHZOSHO_TOKEN", "CODEZAIKU_LIBRARIAN_TOKEN",
            "RESEARCHZOSHO_XMX", "CODEZAIKU_LIBRARIAN_XMX");

    /**
     * What the config file said when it was last read: the settings, the file they came from and the user config's mtime then (-1: it did
     * not exist). One immutable value, swapped whole. Before 0.6.1 the settings were one map that a change cleared and a reload refilled in
     * place, so a thread reading while another thread changed a setting saw no settings at all: the share watcher read "share off" while the
     * tunnel wrote its address, stopped the tunnel and started another (the CI failure of 2026-10-15), and a research run starting at that
     * moment would have found no model set.
     */
    private record Snapshot(Map<String, String> file, Path from, long mtime) { }
    private static volatile Snapshot snapshot;
    /** Counts the changes; a load that overlapped one returns what it read to its caller but does not keep it. */
    private static final AtomicLong CHANGES = new AtomicLong();
    /** Loading has a lock of its own, so a read never waits behind a write (writes hold {@code Config.class}). */
    private static final Object LOAD = new Object();

    /**
     * A LIVE read: the user config file is re-read when it changed since it was loaded, so a setting
     * edited while the daemon runs — the research sharing knobs — takes effect at the next read, with no
     * restart. One stat per call; use it for the few keys that are meant to move under a running process.
     */
    public static String live(String envKey) {
        reloadIfChanged();
        return get(envKey);
    }

    public static int liveInt(String envKey, int fallback) {
        try { String v = live(envKey); return (v == null || v.isBlank()) ? fallback : Integer.parseInt(v.trim()); }
        catch (NumberFormatException e) { return fallback; }
    }

    public static boolean liveOn(String envKey, boolean fallback) {
        reloadIfChanged();
        return isOn(envKey, fallback);
    }

    static void reloadIfChanged() {
        Snapshot s = snapshot;
        if (s != null && mtime(userConfigPath()) != s.mtime()) invalidate();
    }

    private static long mtime(Path p) {
        try { return Files.exists(p) ? Files.getLastModifiedTime(p).toMillis() : -1; } catch (IOException e) { return -1; }
    }

    /** The value for a key such as {@code RESEARCHZOSHO_DRIVE}, or null. */
    public static String get(String envKey) {
        String v = raw(envKey);
        return envKey.endsWith("_DRIVE") || envKey.equals("RESEARCHZOSHO_EMBED") || envKey.equals("RESEARCHZOSHO_RERANK") ? driveBase(v) : v;
    }

    /**
     * A model server's address as every caller expects it: the base, with no {@code /v1} on the end. Providers print
     * their address as {@code https://host/v1}, setup's own example is one, and each request then went to
     * {@code /v1/v1/…} and came back 404. A trailing {@code /v1}, {@code /v1/chat/completions}, {@code /v1/models} or
     * {@code /v1/embeddings} is accepted, and trailing slashes. Anything that is not an http address is left alone.
     */
    public static String driveBase(String url) {
        if (url == null) return null;
        String u = url.strip();
        if (!u.regionMatches(true, 0, "http", 0, 4)) return url;
        u = u.replaceAll("/+$", "");
        for (String tail : new String[]{"/chat/completions", "/completions", "/models", "/embeddings", "/rerank"})
            if (u.endsWith("/v1" + tail)) { u = u.substring(0, u.length() - tail.length()); break; }
        if (u.endsWith("/v1")) u = u.substring(0, u.length() - 3);
        return u.replaceAll("/+$", "");
    }

    private static String raw(String envKey) {
        Snapshot s = loaded();
        Map<String, String> file = s.file();
        Path loadedFrom = s.from();
        String key = normalize(envKey);
        for (String c : candidates(key)) {
            String v = env(c);
            if (v != null) return v;
        }
        boolean legacyFile = loadedFrom != null && loadedFrom.getParent() != null
                && loadedFrom.getParent().getFileName() != null
                && loadedFrom.getParent().getFileName().toString().equals(".codezaiku");
        List<String> fileKeys = new ArrayList<>();
        if (legacyFile && LEGACY_ALIAS.containsKey(key)) fileKeys.add(normalize(LEGACY_ALIAS.get(key)));
        fileKeys.add(key);
        if (!legacyFile && LEGACY_ALIAS.containsKey(key)) fileKeys.add(normalize(LEGACY_ALIAS.get(key)));
        for (String c : fileKeys) {
            String v = file.get(c);
            if (v != null && !v.isBlank()) return v;
        }
        for (String c : candidates(key)) {
            String v = env(c + DEFAULT_SUFFIX);
            if (v != null) return v;
        }
        return null;
    }

    /** The value the config FILE holds for a key, ignoring the environment: what `set` wrote. Null when the file has none. */
    public static String stored(String envKey) {
        Map<String, String> file = loaded().file();
        String key = normalize(envKey);
        String v = file.get(key);
        if ((v == null || v.isBlank()) && LEGACY_ALIAS.containsKey(key)) v = file.get(normalize(LEGACY_ALIAS.get(key)));
        return v == null || v.isBlank() ? null : v;
    }

    /** The environment names to try for one normalized key, most specific first. */
    static List<String> candidates(String key) {
        List<String> out = new ArrayList<>();
        out.add(key);
        String alias = LEGACY_ALIAS.get(key);
        if (alias != null) out.add(alias);
        if (key.startsWith(PREFIX)) out.add(LEGACY_PREFIX + key.substring(PREFIX.length()));
        return out;
    }

    /** Read an environment variable. */
    public static String env(String key) {
        String v = System.getenv(key);
        return (v != null && !v.isBlank()) ? v : null;
    }

    /** {@code <KEY>_DEFAULT}: a host OFFERS a value rather than imposing one; used only when nothing else is set. */
    public static final String DEFAULT_SUFFIX = "_DEFAULT";

    /** Precedence, highest first: an explicit override, this machine's file, a host default, nothing. */
    static String resolve(String envOverride, String fromFile, String hostDefault) {
        if (envOverride != null && !envOverride.isBlank()) return envOverride;
        if (fromFile != null && !fromFile.isBlank()) return fromFile;
        if (hostDefault != null && !hostDefault.isBlank()) return hostDefault;
        return null;
    }

    public static String get(String envKey, String fallback) {
        String v = get(envKey);
        return (v == null || v.isBlank()) ? fallback : v;
    }

    public static boolean isOn(String envKey, boolean fallback) {
        String v = get(envKey);
        if (v == null || v.isBlank()) return fallback;
        v = v.trim().toLowerCase(Locale.ROOT);
        return v.equals("on") || v.equals("true") || v.equals("yes") || v.equals("1");
    }

    public static int getInt(String envKey, int fallback) {
        try {
            String v = get(envKey);
            return (v == null || v.isBlank()) ? fallback : Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public static double getDouble(String envKey, double fallback) {
        try {
            String v = get(envKey);
            return (v == null || v.isBlank()) ? fallback : Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** Which file the settings came from, or null when none was found. */
    public static Path source() {
        return loaded().from();
    }

    /** The per-user config path, whether or not it exists yet. */
    public static Path userConfigPath() {
        String explicit = env("RESEARCHZOSHO_CONFIG");
        if (explicit != null && !explicit.isBlank()) return Paths.get(explicit);
        return home().resolve("config");
    }

    /**
     * The per-user state directory: {@code ~/.researchzosho} — or {@code ~/.codezaiku} when that is the one
     * that exists and the new one does not. An install from before the split keeps its config, its logs
     * and its service script rather than silently starting empty. The library itself is a separate
     * directory ({@code RESEARCHZOSHO_LIBRARY}); see {@code LibraryStore.open()}.
     */
    public static Path home() {
        return stateDir(Paths.get(System.getProperty("user.home", ".")));
    }

    /** {@link #home()} for an explicit user home — the seam the service planner and its tests use. */
    public static Path stateDir(Path userHome) {
        Path now = userHome.resolve(".researchzosho");
        Path was = userHome.resolve(".codezaiku");
        return (!Files.isDirectory(now) && Files.isDirectory(was)) ? was : now;
    }

    /** The settings as the file says now: the snapshot, read again first when there is none. */
    private static Snapshot loaded() {
        Snapshot s = snapshot;
        if (s != null) return s;
        synchronized (LOAD) {
            s = snapshot;
            if (s != null) return s;
            long changes = CHANGES.get();
            // the mtime BEFORE the content: a write landing between the two is seen as a change at the next live read, never hidden behind it
            long mtime = mtime(userConfigPath());
            Map<String, String> file = new LinkedHashMap<>();
            Path from = null;
            for (Path p : new Path[]{userConfigPath(), Paths.get("/etc/researchzosho/config")}) {
                // a folder is no config file: said once, and the settings stay as they are without one, instead of every command ending in an error
                if (p != null && Files.isDirectory(p)) {
                    System.err.println("The config file " + p + " is a folder, so no settings are read from it. If you set RESEARCHZOSHO_CONFIG, it names the file itself.");
                    continue;
                }
                if (p != null && Files.isReadable(p)) {
                    read(p, file);
                    from = p;
                    break;
                }
            }
            s = new Snapshot(Collections.unmodifiableMap(file), from, mtime);
            // kept only when no change landed while reading: the file is replaced whole, so what was read is complete either way, but it may
            // already be the old one, and a kept old snapshot would be served to every reader that does not look at the file's time
            if (CHANGES.get() == changes) snapshot = s;
            return s;
        }
    }

    private static void read(Path p, Map<String, String> into) {
        try {
            for (String raw : Files.readAllLines(p)) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int eq = line.indexOf('=');
                if (eq < 0) continue;
                String key = normalize(line.substring(0, eq).trim());
                String val = line.substring(eq + 1).trim();
                if (val.length() >= 2
                        && ((val.startsWith("\"") && val.endsWith("\""))
                         || (val.startsWith("'") && val.endsWith("'"))))
                    val = val.substring(1, val.length() - 1);
                if (!key.isEmpty()) into.put(key, val);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + p, e);
        }
    }

    /** {@code drive}, {@code RESEARCHZOSHO_DRIVE} and {@code CODEZAIKU_DRIVE} name the same setting. */
    static String normalize(String key) {
        String k = key.trim().toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_');
        if (k.startsWith(LEGACY_PREFIX)) k = k.substring(LEGACY_PREFIX.length());
        if (k.startsWith(PREFIX)) k = k.substring(PREFIX.length());
        return PREFIX + k;
    }

    /**
     * Set a key in the user config file, preserving comments, ordering and the spelling already used. One change at a time in this process,
     * and the file is replaced whole (written beside it, then moved over it), so another thread or another program reading it sees the old
     * file or the new one, never a half-written one.
     */
    public static synchronized void set(String key, String value) throws IOException {
        Path cfg = userConfigPath();
        Files.createDirectories(cfg.getParent());
        String want = normalize(key);
        List<String> lines = Files.exists(cfg) ? new ArrayList<>(Files.readAllLines(cfg)) : new ArrayList<>();
        boolean replaced = false;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            int eq = trimmed.indexOf('=');
            if (eq < 0) continue;
            if (!normalize(trimmed.substring(0, eq).trim()).equals(want)) continue;
            String lead = line.substring(0, line.indexOf(trimmed.charAt(0)));
            lines.set(i, lead + trimmed.substring(0, eq).trim() + " = " + value);
            replaced = true;
            break;
        }
        if (!replaced) {
            if (!lines.isEmpty() && !lines.get(lines.size() - 1).isBlank()) lines.add("");
            lines.add(shorthand(want) + " = " + value);
        }
        Path tmp = cfg.resolveSibling(cfg.getFileName() + ".writing-" + ProcessHandle.current().pid());
        Files.writeString(tmp, String.join("\n", lines) + "\n");
        // the file holds keys: the new one keeps the old one's permissions, which a freshly made file would not
        try { if (Files.exists(cfg)) Files.setPosixFilePermissions(tmp, Files.getPosixFilePermissions(cfg)); } catch (UnsupportedOperationException ignored) { }
        try { Files.move(tmp, cfg, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException e) { Files.move(tmp, cfg, StandardCopyOption.REPLACE_EXISTING); }
        invalidate();
    }

    /** Set a key only while it still holds {@code expected} (an absent key counts as ""): what a thread that wrote a value uses to take it back,
     *  so it never wipes a newer value another thread wrote since. True when it was set. */
    public static synchronized boolean replace(String key, String expected, String value) throws IOException {
        invalidate();
        String now = stored(key);
        if (!(now == null ? "" : now).equals(expected == null ? "" : expected)) return false;
        set(key, value);
        return true;
    }

    /** RESEARCHZOSHO_JOB_WORKERS → job.workers, the form the starter config uses. */
    static String shorthand(String envKey) {
        String k = envKey.startsWith(PREFIX) ? envKey.substring(PREFIX.length()) : envKey;
        return k.toLowerCase(Locale.ROOT).replace('_', '.');
    }

    /** Read the file again at the next look. Readers holding the old snapshot finish with it: nobody sees an empty one. */
    public static void invalidate() { CHANGES.incrementAndGet(); snapshot = null; }

    private Config() { }
}
