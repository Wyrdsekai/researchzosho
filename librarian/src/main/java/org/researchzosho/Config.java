package org.researchzosho;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

import java.util.TreeMap;
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

    /**
     * Settings another program provides for this one (0.8.0): a node of an account service is given the service's settings that its
     * counting and caps go by. They come before the environment and the file. Empty in every other program.
     */
    private static volatile Map<String, String> provided = Map.of();

    /** The provided settings, by their names as the file writes them; replaces what was provided before. */
    public static void provide(Map<String, String> values) {
        Map<String, String> m = new TreeMap<>();
        if (values != null) values.forEach((k, v) -> { if (k != null && v != null && !v.isBlank()) m.put(normalize(k), v); });
        provided = Map.copyOf(m);
    }
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

    /** The settings file read again when it changed since it was read; what a process that runs for long calls before it reads again. */
    public static void reloadIfChanged() {
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

    /** The OpenAI-style API's root under a model server's address: the address plus {@code /v1}, except Gemini's, whose OpenAI-style root
     *  ({@code …/v1beta/openai}) carries no {@code /v1} of its own. */
    public static String v1(String base) {
        String b = base == null ? "" : base.strip().replaceAll("/+$", "");
        return b.endsWith("/openai") ? b : b + "/v1";
    }

    /**
     * The value set for a key: provided, in the environment or in the file, without what a host only offers ({@code <KEY>_DEFAULT}).
     * What a service shares with its nodes is what was set, never an offer the service's own host made to it.
     */
    public static String explicit(String envKey) {
        String v = raw(envKey, false);
        return envKey.endsWith("_DRIVE") ? driveBase(v) : v;
    }

    private static String raw(String envKey) { return raw(envKey, true); }

    private static String raw(String envKey, boolean offers) {
        Snapshot s = loaded();
        Map<String, String> file = s.file();
        Path loadedFrom = s.from();
        String key = normalize(envKey);
        String given = provided.get(key);
        if (given != null) return given;
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
        if (!offers) return null;
        for (String c : candidates(key)) {
            String v = env(c + DEFAULT_SUFFIX);
            if (v != null) return v;
        }
        return null;
    }

    /** Every key the config FILE holds under a prefix ("RESEARCHZOSHO_ENTRY_"), normalized, with its value; the environment is not read. */
    public static Map<String, String> storedWithPrefix(String envPrefix) {
        String want = normalize(envPrefix);
        if (want.endsWith("_")) { } else want = want + "_";
        Map<String, String> out = new TreeMap<>();
        for (Map.Entry<String, String> e : loaded().file().entrySet()) if (e.getKey().startsWith(want) && e.getValue() != null && !e.getValue().isBlank()) out.put(e.getKey(), e.getValue());
        for (Map.Entry<String, String> e : provided.entrySet()) if (e.getKey().startsWith(want)) out.put(e.getKey(), e.getValue());   // what another program provides counts as written
        return out;
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
     * The per-user state directory: {@code $RESEARCHZOSHO_HOME} when set; else {@code ~/.researchzosho} — or {@code ~/.codezaiku} when that is the one
     * that exists and the new one does not. An install from before the split keeps its config, its logs
     * and its service script rather than silently starting empty. The library itself is a separate
     * directory ({@code RESEARCHZOSHO_LIBRARY}); see {@code LibraryStore.open()}.
     */
    public static Path home() {
        String set = env(HOME_NAME);   // a container names it on its settings volume; a person's machine leaves it unset
        if (set != null) return Paths.get(set.strip());
        return stateDir(Paths.get(System.getProperty("user.home", ".")));
    }

    /**
     * The environment variable that puts the per-machine settings folder somewhere else than {@code ~/.researchzosho}: the container image
     * sets it on its settings volume ({@code /config/home}), so what lives there (the month's count of paid searches, the helpers'
     * secret, the update's state, the caches) outlives the container.
     */
    public static final String HOME_NAME = "RESEARCHZOSHO_HOME";

    /**
     * {@link #home()} for an explicit user home — the seam the service planner and its tests use. {@code ~/.researchzosho}; or
     * {@code ~/.codezaiku} only while an install of this program from before 0.8.3 lives there and has not moved yet
     * ({@link #livesIn}): its next start moves it (HomeMove), and from then on {@code ~/.researchzosho} exists. Until 0.8.3 the rule was
     * "{@code ~/.codezaiku} whenever it is a folder and {@code ~/.researchzosho} is not", kept from the split of 2026-09-06 so that an
     * install from before it kept its settings; it put every ResearchZosho installed beside a CodeZaiku into CodeZaiku's folder.
     */
    public static Path stateDir(Path userHome) {
        Path now = userHome.resolve(".researchzosho");
        if (Files.isDirectory(now)) return now;
        Path was = userHome.resolve(".codezaiku");
        return Files.isDirectory(was) && livesInCached(was) ? was : now;
    }

    /**
     * The names this program makes under its home that the other program's code never makes there: one of them in
     * {@code ~/.codezaiku} means this program has lived in that folder. {@code config}, {@code model}, {@code update.lock},
     * {@code search-tally.json} and {@code openrouter-providers.json} are both programs' names, and say nothing alone.
     */
    public static final List<String> OWN_NAMES = List.of("logs", "help", "latest-version.txt", "researchzosho.pid", "site-list", "update.pending",
            "update-held", "update-restart", "labse", "likeness", "metricx", "comic", "embed", "video", "searxng", "shelf", "share", "share.json",
            "podcastindex", "recalls", "dna", "prefs", "idle", "spellings", "record-sources.json", "pdf-eye-speed.json", "scorers.tried",
            "helpers.secret", "helpers-found.json", "accounts", "accounts-own-keys.key", "career-cache", "databases.json", "drivers",
            "embed-at", "ResearchZosho.ps1", "phones.json", "phones.lock");

    /** Settings only this program writes (as the file normalises them): one of them in a shared file means this program has lived there. */
    static boolean ownSetting(String normalized) {
        String k = normalized.startsWith(PREFIX) ? normalized.substring(PREFIX.length()) : normalized;
        return k.startsWith("ENTRY_") || k.startsWith("EXPLORER_") || k.equals("WEB_SIGNIN") || k.equals("WEB_LOCAL") || k.equals("RESEARCH_PAUSE")
                || k.equals("RESEARCH_WINDOW") || k.equals("HELPERS") || k.startsWith("HELPERS_") || k.equals("LOCAL_HELPERS") || k.equals("MODEL_NETWORK")
                || k.equals("REPORT_LENGTH") || k.equals("CITATION_STYLE") || k.equals("LIBRARY_NAME");
    }

    /** Whether this program has lived in that folder (a {@code ~/.codezaiku}): a name of {@link #OWN_NAMES} in it, or a setting only it writes. */
    public static boolean livesIn(Path folder) {
        for (String n : OWN_NAMES) if (Files.exists(folder.resolve(n), LinkOption.NOFOLLOW_LINKS)) return true;
        Path cfg = folder.resolve("config");
        if (!Files.isRegularFile(cfg)) return false;
        Map<String, String> m = new LinkedHashMap<>();
        try { read(cfg, m); } catch (UncheckedIOException e) { return false; }
        for (String k : m.keySet()) if (ownSetting(k)) return true;
        return false;
    }

    /** {@link #livesIn}, looked at again at most every few seconds: the home is asked for at every read of a setting. */
    private record Lives(Path folder, boolean verdict, long at) { }
    private static volatile Lives lives;
    static final long LIVES_MS = 5_000;

    private static boolean livesInCached(Path folder) {
        Lives l = lives;
        long now = System.currentTimeMillis();
        if (l != null && l.folder().equals(folder) && now - l.at() < LIVES_MS) return l.verdict();
        boolean v = livesIn(folder);
        lives = new Lives(folder, v, now);
        return v;
    }

    /** Forgets the cached verdict: the move, and a test that changes the folders, call it. */
    public static void forgetHome() { lives = null; }

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

    /**
     * What this program reads from a settings file shared with CodeZaiku (a {@code ~/.codezaiku/config}): every setting by its normalized
     * name with the value a read gives, the {@code librarian.} spelling first for the four names CodeZaiku spelled so ({@link #LEGACY_ALIAS}),
     * as a read of that file does. A blank value is no setting, as at a read. No value leaves this map but into a settings file.
     */
    public static Map<String, String> readAsShared(Path file) { return view(file, true); }

    /** The same for a settings file of this program's own, where the plain name is read before the {@code librarian.} one. */
    public static Map<String, String> readAsOwn(Path file) { return view(file, false); }

    private static Map<String, String> view(Path file, boolean shared) {
        Map<String, String> raw = new LinkedHashMap<>(), out = new TreeMap<>();
        read(file, raw);
        for (Map.Entry<String, String> e : raw.entrySet()) if (e.getValue() != null && !e.getValue().isBlank()) out.put(e.getKey(), e.getValue());
        for (Map.Entry<String, String> a : LEGACY_ALIAS.entrySet()) {
            String plain = out.get(a.getKey()), spelled = out.get(normalize(a.getValue()));
            String read = shared ? (spelled != null ? spelled : plain) : (plain != null ? plain : spelled);
            if (read != null) out.put(a.getKey(), read);
        }
        return out;
    }

    /**
     * A settings file's text for these settings: one line each, in the form {@link #set} writes, each value written so that a read gives
     * it back as it is (in quotes where it begins or ends with a space or a pair of quotes), under a first line that says {@code note}.
     */
    public static String text(Map<String, String> settings, String note) {
        StringBuilder b = new StringBuilder();
        if (note != null && !note.isBlank()) b.append("# ").append(note.replace("\n", " ")).append("\n\n");
        for (Map.Entry<String, String> e : settings.entrySet()) {
            String v = e.getValue(), t = v.strip();
            boolean quote = !t.equals(v) || v.length() >= 2 && (v.startsWith("\"") && v.endsWith("\"") || v.startsWith("'") && v.endsWith("'"));
            b.append(shorthand(e.getKey())).append(" = ").append(quote ? "\"" + v + "\"" : v).append('\n');
        }
        return b.toString();
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
        Predicate<String> w = writable;
        if (w != null && !w.test(normalize(key))) throw new IOException(NOT_WRITABLE);
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
        // the file holds keys: a new one is readable only by its owner from its first byte (0.8.4: before, a settings file the first
        // `settings set` made was as readable as any new file, by every user of the machine where the umask allows it), and a file
        // that was there keeps its permissions
        Files.deleteIfExists(tmp);
        try { Files.createFile(tmp, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))); } catch (UnsupportedOperationException notPosix) { /* Windows: the home folder is the person's own */ }
        Files.writeString(tmp, String.join("\n", lines) + "\n");
        try { if (Files.exists(cfg)) Files.setPosixFilePermissions(tmp, Files.getPosixFilePermissions(cfg)); } catch (UnsupportedOperationException ignored) { }
        try { Files.move(tmp, cfg, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException e) { Files.move(tmp, cfg, StandardCopyOption.REPLACE_EXISTING); }
        invalidate();
    }

    /**
     * The settings a write may change, where a process may change only some: a library that runs under an account service writes its own
     * (how its reports are written, what its nights do) and no other, whatever asked for the write. Null everywhere else: every setting.
     */
    private static volatile Predicate<String> writable;
    /** What a refused write says. */
    public static final String NOT_WRITABLE = "This library runs under an account service, and this setting is set by whoever runs the service.";

    /** Limits what {@link #set} may write to the keys the test passes (given as the file normalises them); null lifts the limit. */
    public static void writable(Predicate<String> only) { writable = only; }

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
