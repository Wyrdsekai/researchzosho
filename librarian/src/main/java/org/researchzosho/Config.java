package org.researchzosho;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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

    private static final Map<String, String> FILE = new LinkedHashMap<>();
    private static volatile boolean loaded = false;
    private static Path loadedFrom = null;
    private static volatile long loadedMtime = -1;   // the user config's mtime at load; -1 = it did not exist

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
        long m = mtime(userConfigPath());
        if (loaded && m != loadedMtime) invalidate();
    }

    private static long mtime(Path p) {
        try { return Files.exists(p) ? Files.getLastModifiedTime(p).toMillis() : -1; } catch (IOException e) { return -1; }
    }

    /** The value for a key such as {@code RESEARCHZOSHO_DRIVE}, or null. */
    public static String get(String envKey) {
        ensureLoaded();
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
            String v = FILE.get(c);
            if (v != null && !v.isBlank()) return v;
        }
        for (String c : candidates(key)) {
            String v = env(c + DEFAULT_SUFFIX);
            if (v != null) return v;
        }
        return null;
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
        ensureLoaded();
        return loadedFrom;
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

    private static void ensureLoaded() {
        if (loaded) return;
        synchronized (Config.class) {
            if (loaded) return;
            for (Path p : new Path[]{userConfigPath(), Paths.get("/etc/researchzosho/config")}) {
                if (p != null && Files.isReadable(p)) {
                    read(p);
                    loadedFrom = p;
                    break;
                }
            }
            loadedMtime = mtime(userConfigPath());
            loaded = true;
        }
    }

    private static void read(Path p) {
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
                if (!key.isEmpty()) FILE.put(key, val);
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

    /** Set a key in the user config file, preserving comments, ordering and the spelling already used. */
    public static void set(String key, String value) throws IOException {
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
        Files.writeString(cfg, String.join("\n", lines) + "\n");
        invalidate();
    }

    /** RESEARCHZOSHO_JOB_WORKERS → job.workers, the form the starter config uses. */
    static String shorthand(String envKey) {
        String k = envKey.startsWith(PREFIX) ? envKey.substring(PREFIX.length()) : envKey;
        return k.toLowerCase(Locale.ROOT).replace('_', '.');
    }

    public static synchronized void invalidate() {
        FILE.clear();
        loaded = false;
        loadedFrom = null;
    }

    private Config() { }
}
