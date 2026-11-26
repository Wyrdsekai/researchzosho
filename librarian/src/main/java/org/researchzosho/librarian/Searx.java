package org.researchzosho.librarian;

import org.researchzosho.tools.Outbound;
import org.researchzosho.tools.WebSearchTool;
import org.researchzosho.Config;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import java.security.SecureRandom;
import java.security.NoSuchAlgorithmException;
import java.security.MessageDigest;
import java.util.HexFormat;
/**
 * SearXNG, run for the person through Docker: the free, private search backend. ResearchZosho does not
 * ship SearXNG (a Python service does not belong inside a Java tarball); it starts the official image
 * with a settings file that turns the JSON format on, which SearXNG has off by default and the search
 * tool needs. The container is named, restarts on boot, and keeps its settings under the state
 * directory. {@code researchzosho search start|stop|status} and the setup wizard use this.
 */
public final class Searx {
    private static final HttpClient PROBE_HTTP = Outbound.client(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());

    public static final String CONTAINER = "researchzosho-searxng";
    public static final String IMAGE = "searxng/searxng";
    public static final int DEFAULT_PORT = 8888;

    /** Runs a command line; stdout+stderr and the exit code. Tests replace it. */
    public interface Runner { Result run(List<String> cmd) throws Exception; }
    public record Result(int code, String out) { }
    /**
     * The runner of the machine's commands (docker, nvidia-smi, an archive tool, a Python, pip). Where the property
     * {@code researchzosho.commands} is {@code off} (the test task sets it, and a process this one starts is given it), it runs none, and
     * answers every command as a machine without the program does.
     */
    static volatile Runner runner = "off".equals(System.getProperty("researchzosho.commands")) ? cmd -> { throw new IOException("Cannot run program \"" + (cmd.isEmpty() ? "" : cmd.get(0)) + "\": no command of the machine is run here (researchzosho.commands=off)"); } : cmd -> {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String o = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new Result(p.waitFor(), o);
    };

    private Searx() { }

    /** Whether docker is on this machine and its daemon answers. */
    public static boolean haveDocker() {
        try { return runner.run(List.of("docker", "version", "--format", "{{.Server.Version}}")).code() == 0; }
        catch (Exception e) { return false; }
    }

    /** Where the container keeps its settings: {@code <state dir>/searxng}. */
    public static Path dir() { return Config.home().resolve("searxng"); }

    /**
     * The settings file the container reads: SearXNG's defaults, the JSON format the search tool needs, and an engine
     * set MEASURED from a home box on 2026-09-09. SearXNG's default set leans on DuckDuckGo, Google, Qwant, Startpage
     * and Brave, and every one of those answered the first query with a CAPTCHA or "too many requests" and stayed
     * suspended; Bing answered but matched the first word only. What answered with real results: Seznam and Naver
     * (English), Yandex (English and Japanese), Yahoo (Japanese), and Wikipedia. So those are on and the others are
     * off, which also keeps a search from waiting on engines that will not answer. `researchzosho search test` shows
     * what the set does today; the person can edit the file. Safe search is moderate ({@code safe_search: 1}) for the engines that
     * have the setting; the search tool asks for the same in every request.
     */
    public static String settings() { return settings(avoidedEngines(Avoided.places())); }

    /** The same, with these engines taken out of what SearXNG loads at all ({@link #avoidedEngines}): none, and its defaults stand whole. */
    static String settings(List<String> removed) { return settings(removed, HexFormat.of().formatHex(SecureRandom.getSeed(24))); }

    /** The same, with the secret key given: a file written again keeps the one it had. */
    static String settings(List<String> removed, String key) { return settings(removed, key, known()); }

    /** The names of the engines the image on this machine has, when its own list was read; null when it was not, and every engine this program knows is named. */
    static Set<String> known() { Map<String, String> image = imageEngines; return image == null || image.isEmpty() ? null : image.keySet(); }

    /**
     * The engines this program switches off beside the ones SearXNG leaves off itself: each answered a home machine with a CAPTCHA, a
     * refusal or nothing when it was measured, or needs a key of its own, and each would still be sent every search's words.
     */
    static final List<String> OFF = List.of("bing", "duckduckgo", "google", "google cse", "google cse images", "qwant", "startpage", "brave", "mojeek", "presearch");

    /**
     * The same, naming only the engines in {@code has} (null: all): SearXNG's list changes from image to image (presearch left it in
     * 2026), and an engine named in the file that the image does not have is read as a new engine with nothing behind it.
     */
    static String settings(List<String> removed, String key, Set<String> has) {
        StringBuilder defaults = new StringBuilder();
        if (removed.isEmpty()) defaults.append("use_default_settings: true\n");
        else {
            defaults.append("# The engines based in the places this library avoids (researchzosho settings: avoid_places) are not loaded,\n")
                    .append("# so that no search, and no shortcut typed into a search, reaches them.\n")
                    .append("use_default_settings:\n  engines:\n    remove:\n");
            for (String name : removed) defaults.append("      - ").append(written(name)).append('\n');
        }
        StringBuilder on = new StringBuilder();
        for (String name : ON) if (!removed.contains(name) && (has == null || has.contains(name))) on.append("  - name: ").append(written(name)).append("\n    disabled: false\n");
        StringBuilder off = new StringBuilder();
        for (String name : OFF) if (!removed.contains(name) && (has == null || has.contains(name))) off.append("  - name: ").append(written(name)).append("\n    disabled: true\n");
        String text = "# Written by ResearchZosho. SearXNG's own defaults apply; only what ResearchZosho needs is set here.\n"
                + "# The engine set was measured from a home machine (2026-09-09): the ones off here answered with a\n"
                + "# CAPTCHA or a suspension on the first query. Edit freely; `researchzosho search test <query>` shows the result.\n"
                + "# google cse and google cse images need a key of their own: asked without one on 2026-11-20 they answered nothing, and they\n"
                + "# would still be sent every search's words, so they are off. Switch one on here when you have its key.\n"
                + "# While this file is exactly as ResearchZosho wrote it (check " + NO_CHECK + "), a new version of ResearchZosho may write it again.\n"
                + "# Change anything in it, or take anything out, and it is yours: it is then left as it is.\n"
                + defaults
                + "# contact_url: the address SearXNG names to Wikimedia and OpenStreetMap in its requests. Without one the SearXNG of late\n"
                + "# 2026 does not load the wikipedia, wikidata and openstreetmap engines. It is this program's site; it names no person.\n"
                + "general:\n"
                + "  contact_url: \"" + CONTACT + "\"\n"
                + "server:\n"
                + "  secret_key: \"" + key + "\"\n"
                + "  limiter: false\n"
                + "search:\n"
                + "  safe_search: 1\n"
                + "  formats:\n"
                + "    - html\n"
                + "    - json\n"
                + "engines:\n"
                + on
                + off;
        return text.replace("(check " + NO_CHECK + ")", "(check " + check(text) + ")");
    }

    /**
     * The check a file this version wrote carries in its own comment: a digest of the whole text, the check's own place filled with
     * zeros. It says one thing, whether the file is still, to the letter, what this program wrote. A person's edit of any kind
     * changes the text and not the check, so the file is theirs from then on. Until the second reading of 0.8.1 the file was told
     * as this program's own by writing it again from the names it held and comparing: an edit that only took something out (an
     * engine's entry, a name from the list of engines not loaded) gave the same text again, and the next start put it back.
     */
    private static final String NO_CHECK = "0".repeat(32);
    private static final Pattern CHECK = Pattern.compile("\\(check ([0-9a-f]{32})\\)");

    private static String check(String textWithNoCheck) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(textWithNoCheck.getBytes(StandardCharsets.UTF_8))).substring(0, 32); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    /**
     * A name as it is written into the settings so that SearXNG reads it back as that name. The plain names the images use
     * (letters, digits, spaces, a dot, a hyphen) are written as they are; a name YAML would read as something else (a colon, a
     * mark at its beginning, a word that means yes, no or nothing, a number) is written between double quotes.
     */
    static String written(String name) {
        boolean plain = name.matches("[\\p{L}\\p{N}](?:[\\p{L}\\p{N} ._+-]*[\\p{L}\\p{N}._+-])?")
                && !name.matches("(?i)y|n|yes|no|on|off|true|false|null") && !name.matches("[-+]?[0-9.][0-9._]*(?:[eE][-+]?[0-9]+)?|0[xXoObB][0-9a-fA-F_]+");
        return plain ? name : "\"" + name.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static final Pattern KEY_LINE = Pattern.compile("(?m)^  secret_key: \"([0-9a-fA-F]*)\"[ \\t]*$");

    /**
     * The settings text the versions before 0.8.1 wrote, with this key: there were two, the first from 2026-09-09 and the one with
     * {@code safe_search} from 2026-09-23 on (0.8.0's). Both switch Yandex on. A file that is one of them to the letter, its key aside,
     * was written by this program and never edited.
     */
    static String earlier(boolean safeSearch, String key) {
        return "# Written by ResearchZosho. SearXNG's own defaults apply; only what ResearchZosho needs is set here.\n"
                + "# The engine set was measured from a home machine (2026-09-09): the ones off here answered with a\n"
                + "# CAPTCHA or a suspension on the first query. Edit freely; `researchzosho search test <query>` shows the result.\n"
                + "use_default_settings: true\n"
                + "server:\n"
                + "  secret_key: \"" + key + "\"\n"
                + "  limiter: false\n"
                + "search:\n"
                + (safeSearch ? "  safe_search: 1\n" : "")
                + "  formats:\n"
                + "    - html\n"
                + "    - json\n"
                + "engines:\n"
                + "  - name: seznam\n    disabled: false\n"
                + "  - name: naver\n    disabled: false\n"
                + "  - name: yandex\n    disabled: false\n"
                + "  - name: yahoo\n    disabled: false\n"
                + "  - name: wikipedia\n    disabled: false\n"
                + "  - name: bing\n    disabled: true\n"
                + "  - name: duckduckgo\n    disabled: true\n"
                + "  - name: google\n    disabled: true\n"
                + "  - name: qwant\n    disabled: true\n"
                + "  - name: startpage\n    disabled: true\n"
                + "  - name: brave\n    disabled: true\n"
                + "  - name: mojeek\n    disabled: true\n"
                + "  - name: presearch\n    disabled: true\n";
    }

    /** Whose a settings file is: an earlier version's as it wrote it, this version's as it wrote it, or the person's (anything else: one letter changed is enough). */
    enum Whose { EARLIER, OURS, THEIRS }

    /**
     * Told apart by the text alone. An earlier version's file is compared, letter for letter, with what that version wrote with the
     * file's own secret key. This version's file carries a check of its own text ({@link #check}), and is this program's while the
     * text still answers to it. A person who put an engine back, took one out, or changed anything else has a file that is neither,
     * and it is theirs.
     */
    static Whose whose(String text) {
        String t = text == null ? "" : text.replace("\r\n", "\n");
        Matcher k = KEY_LINE.matcher(t);
        String key = k.find() ? k.group(1) : "";
        if (t.equals(earlier(true, key)) || t.equals(earlier(false, key))) return Whose.EARLIER;
        Matcher c = CHECK.matcher(t);
        if (c.find() && c.group(1).equals(check(t.substring(0, c.start(1)) + NO_CHECK + t.substring(c.end(1))))) return Whose.OURS;
        return Whose.THEIRS;
    }

    /** The engines a settings text switches on or off by name: the names under its {@code engines:}. */
    static Set<String> namesIn(String text) {
        Set<String> out = new LinkedHashSet<>();
        int at = text.indexOf("\nengines:\n");
        if (at < 0) return out;
        Matcher m = Pattern.compile("(?m)^  - name: (.+?)\\s*$").matcher(text.substring(at));
        while (m.find()) out.add(unquoted(m.group(1)));
        return out;
    }

    /** The engines a settings text takes out of SearXNG's defaults: the names under its {@code remove:}. */
    static List<String> removedIn(String text) {
        List<String> out = new ArrayList<>();
        int at = text.indexOf("\n    remove:\n");
        if (at < 0) return out;
        for (String line : text.substring(at + "\n    remove:\n".length()).split("\n")) {
            Matcher m = Pattern.compile("^      - (.+?)\\s*$").matcher(line);
            if (!m.matches()) break;
            out.add(unquoted(m.group(1)));
        }
        return out;
    }

    /**
     * At the program's start: a settings file this program wrote and nobody edited is written again when this version would write
     * another (an earlier version's switches Yandex on; the places avoided may have changed), and the container is made again so that
     * it reads the file. A file the person edited is theirs and is left as it is: {@link #avoidedNote} says what it still loads.
     * Returns what was done, "" for nothing; nothing without docker or without a file.
     */
    public static String upgrade() {
        try {
            Path settings = dir().resolve("settings.yml");
            if (!Files.isRegularFile(settings)) return "";
            String had = Files.readString(settings).replace("\r\n", "\n");
            Whose whose = whose(had);
            if (whose == Whose.THEIRS) return "";
            if (whose == Whose.OURS && removedIn(had).equals(avoidedEngines(Avoided.places()))) return "";
            if (!haveDocker()) return "";
            int port = DEFAULT_PORT;
            try { URI at = URI.create(WebSearchTool.endpoint()); if (at.getPort() > 0 && Outbound.isLoopback(at.getHost())) port = at.getPort(); } catch (RuntimeException e) { /* the default port */ }
            String state = state();
            String did;
            if (state.equals("absent")) { String now = rewritten(had); if (now != null) Files.writeString(settings, now); did = now == null ? "" : WRITTEN_AGAIN; }
            else { String r = start(port, false); did = settingsNote.contains(WRITTEN_AGAIN) ? WRITTEN_AGAIN + (r.startsWith("!") ? " " + r.substring(1) : "") : ""; }
            if (did.isEmpty()) return "";
            // with the engines of the avoided places out, an old image searches with fewer engines than a current one: said with the change
            String old = imageNote();
            String said = old.isEmpty() ? did : did + " " + old;
            try { Files.writeString(afterUpgradeFile(), said + "\n"); } catch (IOException e) { /* said in the log all the same */ }
            return said;
        } catch (IOException | RuntimeException e) { return ""; }
    }

    static Path afterUpgradeFile() { return dir().resolve("after-upgrade.txt"); }

    /** What an upgrade did to the search settings, for the status command to say once: read, and forgotten. "" when there is nothing to say. */
    public static String afterUpgrade() {
        try {
            Path f = afterUpgradeFile();
            if (!Files.isRegularFile(f)) return "";
            String said = Files.readString(f).strip();
            Files.deleteIfExists(f);
            return said;
        } catch (IOException | RuntimeException e) { return ""; }
    }

    /** For the update command's own message: the old image's line, when the settings file is this program's own and the image is old; "" otherwise. Asks docker only when there is such a file. */
    public static String oldImageAtUpdate() {
        try {
            Path settings = dir().resolve("settings.yml");
            if (!Files.isRegularFile(settings) || whose(Files.readString(settings)) == Whose.THEIRS || !haveDocker()) return "";
            return imageNote();
        } catch (IOException | RuntimeException e) { return ""; }
    }

    static final String WRITTEN_AGAIN = "SearXNG's settings were the ones ResearchZosho wrote, unchanged; they are written again for this version, without the search engines based in the places this library avoids.";

    /** What is said of a settings file the person edited, or that is not this program's: at `search start`, and at `search status` through the language files. */
    static final String EDITED = "using the settings.yml already in {0} as it is (edited since ResearchZosho wrote it, or not its own; `researchzosho search start --fresh` replaces it with the measured engine set)";

    /**
     * For `search status`, every time: which of three the settings file is, read off the file itself ({@link #whose}). This
     * version's own, unchanged, with the engines of the avoided places left out; this program's and unchanged but not what this
     * version writes (an earlier version's, or one written before a place was added to those avoided), which the service's next
     * start or `search start` writes again; the person's, left as it is. Until 0.8.1's last round only `researchzosho status` said
     * the second, once, after it had been written again. "" when there is no file.
     */
    public static String settingsSaid() {
        try {
            Path settings = dir().resolve("settings.yml");
            if (!Files.isRegularFile(settings)) return "";
            Chrome c = Chrome.now();
            Whose whose = whose(Files.readString(settings));
            if (whose == Whose.THEIRS) return c.t(EDITED, dir());
            Set<String> places = Avoided.places();
            if (whose == Whose.OURS && stillLoaded(settings, places).isEmpty()) {
                String said = c.t("SearXNG's settings ({0}) are the ones ResearchZosho wrote for this version, unchanged.", settings);
                return places.isEmpty() ? said : c.sentences(List.of(said, c.t("They leave out the search engines based in {0}.", Avoided.names(places, c))));
            }
            return c.t("SearXNG's settings ({0}) are ones ResearchZosho wrote and nobody changed, but not the ones this version writes. They are written again the next time the library's service starts, or now by researchzosho search start.", settings);
        } catch (IOException | RuntimeException e) { return ""; }
    }

    /** The text this version writes in place of {@code had}, keeping its key; null when {@code had} is the person's, or already that text. */
    static String rewritten(String had) {
        if (whose(had) == Whose.THEIRS) return null;
        Matcher k = KEY_LINE.matcher(had);
        String key = k.find() && !k.group(1).isEmpty() ? k.group(1) : HexFormat.of().formatHex(SecureRandom.getSeed(24));
        String now = settings(avoidedEngines(Avoided.places()), key);
        return now.equals(had) ? null : now;
    }

    /** What the search container names as its contact in its requests to Wikimedia and OpenStreetMap: the project's site, no person. */
    static final String CONTACT = "https://researchzosho.org";

    /** The engines the measured set switches on. */
    static final List<String> ON = List.of("seznam", "naver", "yandex", "yahoo", "wikipedia");

    /**
     * Where a SearXNG engine is based, by the module that runs it (the {@code engine:} of its entry in SearXNG's settings): the country
     * of the company behind the website the module names as its own. Recorded on 2026-11-20 from SearXNG 2026.7.26, since an engine states
     * its website and no country. It decides nothing by itself: an engine is left out only when its place is one the library avoids
     * ({@link Avoided}), which the person sets.
     */
    static final Map<String, String> ENGINE_PLACE = Map.ofEntries(
            Map.entry("baidu", "CN"), Map.entry("sogou", "CN"), Map.entry("sogou_images", "CN"), Map.entry("sogou_videos", "CN"), Map.entry("sogou_wechat", "CN"),
            Map.entry("360search", "CN"), Map.entry("360search_videos", "CN"), Map.entry("quark", "CN"), Map.entry("bilibili", "CN"), Map.entry("iqiyi", "CN"),
            Map.entry("acfun", "CN"), Map.entry("chinaso", "CN"), Map.entry("yandex", "RU"), Map.entry("yandex_music", "RU"), Map.entry("yandex_api", "RU"), Map.entry("naver", "KR"), Map.entry("seznam", "CZ"));

    /**
     * The same for engines that have no module of their own (SearXNG defines them in its settings with a general module), by the
     * engine's name: Habr's (habr.com) and Kozmonavt's (kozmonavt.su), both off or inactive by SearXNG's default. Recorded 2026-11-20.
     */
    static final Map<String, String> ENGINE_NAME_PLACE = Map.of("habrahabr", "RU", "kozmonavt", "RU");

    /** The engines of those modules as SearXNG 2026.7.26 names them, each with its module: used when the image's own list cannot be read. */
    static final Map<String, String> ENGINES_RECORDED = Map.ofEntries(
            Map.entry("baidu", "baidu"), Map.entry("baidu images", "baidu"), Map.entry("baidu kaifa", "baidu"), Map.entry("sogou", "sogou"), Map.entry("sogou images", "sogou_images"),
            Map.entry("sogou videos", "sogou_videos"), Map.entry("sogou wechat", "sogou_wechat"), Map.entry("360search", "360search"), Map.entry("360search videos", "360search_videos"),
            Map.entry("quark", "quark"), Map.entry("quark images", "quark"), Map.entry("bilibili", "bilibili"), Map.entry("iqiyi", "iqiyi"), Map.entry("acfun", "acfun"),
            Map.entry("chinaso news", "chinaso"), Map.entry("yandex", "yandex"), Map.entry("yandex images", "yandex"), Map.entry("yandex music", "yandex_music"),
            Map.entry("naver", "naver"), Map.entry("naver images", "naver"), Map.entry("naver news", "naver"), Map.entry("naver videos", "naver"), Map.entry("seznam", "seznam"),
            Map.entry("habrahabr", "xpath"), Map.entry("kozmonavt", "xpath"));

    /** SearXNG's own list of engines (name → module), read from the image once; a test stands in. Null: not read yet. */
    static volatile Map<String, String> imageEngines;

    /**
     * Every engine with its module: the image's own list when it was read, the recorded names until then. Nothing is asked of docker
     * here; {@link #start} reads the image's list ({@link #readImageEngines}) before it writes a settings file.
     */
    static Map<String, String> engines() {
        Map<String, String> known = imageEngines;
        return known != null && !known.isEmpty() ? known : ENGINES_RECORDED;
    }

    /** The image's own settings read through docker, with no network, so that an engine a later SearXNG adds to a recorded module is left out too. */
    static synchronized void readImageEngines() {   // one reading at a time: a second start that arrives meanwhile waits for it and finds the list read
        if (imageEngines != null) return;
        try {
            Result r = runner.run(List.of("docker", "run", "--rm", "--network", "none", "--entrypoint", "cat", IMAGE, "/usr/local/searxng/searx/settings.yml"));
            Map<String, String> read = r.code() == 0 ? enginesIn(r.out()) : Map.of();
            if (!read.isEmpty()) imageEngines = read;   // a reading that failed is not remembered as "the image has none": the next start reads again
        } catch (Exception e) { /* not read: the recorded names stand in until it is */ }
    }

    /** The engines of a SearXNG settings text: each entry's name with its {@code engine:} (the name itself when it states none). */
    static Map<String, String> enginesIn(String yaml) {
        Map<String, String> out = new TreeMap<>();
        int at = yaml.indexOf("\nengines:");
        if (at < 0) return out;
        String name = null;
        for (String line : yaml.substring(at).split("\n")) {
            Matcher n = Pattern.compile("^\\s*-\\s+name:\\s*(.+?)\\s*$").matcher(line);
            if (n.matches()) { name = unquoted(n.group(1)); out.putIfAbsent(name, name.replace(' ', '_')); continue; }
            Matcher e = Pattern.compile("^\\s+engine:\\s*(.+?)\\s*$").matcher(line);
            if (e.matches() && name != null) out.put(name, unquoted(e.group(1)));
        }
        return out;
    }

    /**
     * A value of the settings as YAML reads it, for the values this program reads (an engine's name, a module's): between a matched
     * pair of double quotes, what stands inside with its backslashes read; between a matched pair of single quotes, what stands
     * inside, a doubled one being one; with no quotes, the text up to a comment. A quote at one end only is part of the value.
     */
    static String unquoted(String s) {
        String t = s.strip();
        if (t.length() >= 2 && t.charAt(0) == '"' && t.endsWith("\"") && !t.substring(1, t.length() - 1).replace("\\\\", "").replace("\\\"", "").contains("\""))
            return t.substring(1, t.length() - 1).replaceAll("\\\\([\\\\\"])", "$1");
        if (t.length() >= 2 && t.charAt(0) == '\'' && t.endsWith("'") && !t.substring(1, t.length() - 1).replace("''", "").contains("'"))
            return t.substring(1, t.length() - 1).replace("''", "'");
        if (t.startsWith("\"") || t.startsWith("'")) return t;
        int comment = t.indexOf(" #");
        return comment < 0 ? t : t.substring(0, comment).strip();
    }

    /** The engines based in {@code places}, by the names SearXNG's settings use, in order: what the settings take out of what it loads. */
    static List<String> avoidedEngines(Set<String> places) {
        List<String> out = new ArrayList<>();
        if (places.isEmpty()) return out;
        for (Map.Entry<String, String> e : engines().entrySet()) {
            String place = ENGINE_NAME_PLACE.containsKey(e.getKey()) ? ENGINE_NAME_PLACE.get(e.getKey()) : ENGINE_PLACE.get(e.getValue());
            if (place != null && places.contains(place)) out.add(e.getKey());
        }
        out.sort(null);   // one order every time: the file reads the same on every machine
        return out;
    }

    /**
     * The engines based in a place this library avoids that a settings file still lets SearXNG load: every one of them for a file that
     * takes SearXNG's defaults whole, the ones its remove list does not name otherwise. Empty when it loads none, when there is no file,
     * and for a file that lists its own engines in full, which names what it wants.
     */
    static List<String> stillLoaded(Path settings, Set<String> places) {
        List<String> avoided = avoidedEngines(places);
        if (avoided.isEmpty() || !Files.isRegularFile(settings)) return List.of();
        String text;
        try { text = Files.readString(settings); } catch (IOException e) { return List.of(); }
        if (Pattern.compile("(?m)^use_default_settings:\\s*false\\s*$").matcher(text).find()) return List.of();
        Map<String, Set<String>> lists = defaultsLists(text);
        Set<String> only = lists.get("keep_only"), remove = lists.getOrDefault("remove", Set.of());
        List<String> left = new ArrayList<>();
        for (String name : avoided) if (only != null ? only.contains(name) : !remove.contains(name)) left.add(name);
        return left;
    }

    /**
     * The two lists a settings file may hold under {@code use_default_settings: engines:}, as SearXNG reads them: {@code remove}
     * (the engines of its defaults that are not loaded) and {@code keep_only} (the only ones that are). Read by the file's own
     * structure, the keys where they stand and the items under them, written one a line or between square brackets; the same
     * words in a comment, or a list under another key, are neither (until the second reading of 0.8.1 every "- name" line after the
     * first "remove:" anywhere in the file was taken for a removal, and a file with a list of the only engines to keep was said to
     * load every engine).
     */
    static Map<String, Set<String>> defaultsLists(String text) {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        String[] lines = text.replace("\r\n", "\n").split("\n");
        int i = 0;
        while (i < lines.length && !lines[i].matches("use_default_settings:\\s*(?:#.*)?")) i++;
        int engines = -1, listAt = -1;
        String list = null;
        for (i++; i < lines.length; i++) {
            String t = lines[i].strip();
            if (t.isEmpty() || t.startsWith("#")) continue;
            int indent = lines[i].length() - lines[i].stripLeading().length();
            if (indent == 0) break;   // the next key of the file: the block has ended
            if (list != null && (indent > listAt || (indent == listAt && t.startsWith("-")))) {
                if (t.startsWith("-")) out.get(list).add(unquoted(t.substring(1)));
                continue;
            }
            list = null;
            if (engines >= 0 && indent <= engines) engines = -1;
            if (engines < 0) { if (t.matches("engines:\\s*(?:#.*)?")) engines = indent; continue; }
            Matcher k = Pattern.compile("(remove|keep_only):\\s*(.*)").matcher(t);
            if (!k.matches()) continue;
            Set<String> names = out.computeIfAbsent(k.group(1), x -> new LinkedHashSet<>());
            String rest = k.group(2).strip();
            if (rest.startsWith("[")) { for (String one : rest.substring(1, rest.indexOf(']') < 0 ? rest.length() : rest.indexOf(']')).split(",")) if (!one.isBlank()) names.add(unquoted(one)); }
            else if (rest.isEmpty() || rest.startsWith("#")) { list = k.group(1); listAt = indent; }
        }
        return out;
    }

    /**
     * Whether the settings file in place is the person's: edited since this program wrote it, or not its own. The one case in which
     * what the file still loads of an avoided place is theirs to decide, and `search status` adds {@link #avoidedNote} for it. This
     * program's own file from an earlier version is written again at the next start, as {@link #settingsSaid} says of it, and the
     * note's "if you put them there yourself, nothing needs doing" under that sentence spoke of a file nobody had edited (the
     * re-check of 0.8.1's last round). False when there is no file.
     */
    public static boolean settingsAreThePersons() {
        try {
            Path settings = dir().resolve("settings.yml");
            return Files.isRegularFile(settings) && whose(Files.readString(settings)) == Whose.THEIRS;
        } catch (IOException | RuntimeException e) { return false; }
    }

    /** What status says of a settings file that still loads such engines, and the command that writes it again; "" when it loads none. */
    public static String avoidedNote() {
        Set<String> places = Avoided.places();
        List<String> left = stillLoaded(dir().resolve("settings.yml"), places);
        if (left.isEmpty()) return "";
        return "SearXNG's settings (" + dir().resolve("settings.yml") + ") still load " + left.size() + " search engine" + (left.size() == 1 ? "" : "s") + " based in " + Avoided.names(places)
                + " (" + String.join(", ", left) + "). This program's own searches cannot reach them; a search typed into SearXNG's own page can. "
                + "If you put them there yourself, nothing needs doing: this program does not take an engine out of a file you edited, and it uses the engine's results as any other's. "
                + "`researchzosho search start --fresh` writes the settings again without them (it replaces the file: keep a copy first if you edited it).";
    }

    /**
     * A search's words with nothing in them that SearXNG reads as a command. SearXNG splits the words at white space and reads each
     * part by its first character, wherever the part stands ({@code searx/query.py}, 2026.7.26): {@code !name} picks an engine or a
     * category, switched off or not ({@code !bd}, {@code !images}); {@code !!name} sends the search to another site, and {@code !!} alone
     * to the first result; {@code :code} sets the language; {@code <3} or {@code <850} sets the time limit. A model's words, or a page's
     * that a model repeats, could so send a search to an engine the person never chose (found 2026-11-20: {@code !bd weather} called
     * Baidu, an engine switched off). The marks are taken off the front of every part, and the part searches as the word it is:
     * {@code !bd weather} is sent as {@code bd weather}. The white space is Python's, which SearXNG splits on.
     */
    public static String plain(String words) {
        if (words == null) return "";
        return COMMAND.matcher(words).replaceAll("$1").strip();
    }

    /** The white space Python's {@code \s} matches in text: what SearXNG splits a search's words on. */
    private static final String SPACE = "\\t\\n\\x0B\\f\\r\\x1C-\\x1F \\x85\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000";
    private static final Pattern COMMAND = Pattern.compile("(^|[" + SPACE + "])[!:<]+");

    /** "running", "stopped" (exists, not running), or "absent". */
    public static String state() {
        try {
            Result r = runner.run(List.of("docker", "inspect", "-f", "{{.State.Running}}", CONTAINER));
            if (r.code() != 0) return "absent";
            return r.out().strip().equals("true") ? "running" : "stopped";
        } catch (Exception e) { return "absent"; }
    }

    /**
     * Start SearXNG on {@code port}: write the settings if there are none, create the container if it is absent,
     * start it if it is stopped, then wait for a JSON search to answer. Returns the address, or "!reason".
     */
    public static String start(int port) { return start(port, false); }

    /** The command that makes the search service's container on {@code port}, with its settings' folder ({@link #dir}) in it. */
    static List<String> runCommand(int port) {
        return List.of("docker", "run", "-d", "--name", CONTAINER, "--restart", "unless-stopped",
                "-p", "127.0.0.1:" + port + ":8080", "-v", dir().toAbsolutePath() + ":/etc/searxng",
                "-e", "BASE_URL=http://127.0.0.1:" + port + "/", IMAGE);
    }

    /** A note about the settings in use, set by the last {@link #start}: "" when ResearchZosho's own file is in place. */
    public static volatile String settingsNote = "";

    /**
     * @param fresh rewrite the settings file with ResearchZosho's own (the measured engine set) even when one exists,
     *              and recreate the container so it reads them
     */
    public static String start(int port, boolean fresh) {
        if (!haveDocker()) return "!docker is not on this machine (https://docs.docker.com/get-docker/)";
        settingsNote = "";
        try {
            Files.createDirectories(dir());
            Path settings = dir().resolve("settings.yml");
            String had = Files.exists(settings) ? Files.readString(settings).replace("\r\n", "\n") : null;
            Whose whose = had == null ? null : whose(had);
            boolean again = false;
            if (had == null || fresh) {
                if (!Avoided.places().isEmpty()) readImageEngines();   // the engines to leave out, by the names this image gives them
                try { Files.writeString(settings, settings()); }
                catch (IOException e) { if (!Files.exists(settings)) return "!cannot write " + settings + ": " + e.getMessage(); settingsNote = "could not rewrite " + settings + " (" + e.getMessage() + "); using it as it is"; }
            } else if (whose != Whose.THEIRS) {
                // this program's own file with not a letter changed (an earlier version's, or this one's for other places): written again
                // when this version would write another, its key kept, and the container made again below so that it reads it
                if (!Avoided.places().isEmpty()) readImageEngines();
                String now = rewritten(had);
                if (now != null) try { Files.writeString(settings, now); again = true; settingsNote = WRITTEN_AGAIN; }
                catch (IOException e) { settingsNote = "could not rewrite " + settings + " (" + e.getMessage() + "); using it as it is"; }
            } else {
                settingsNote = EDITED.replace("{0}", dir().toString());
            }
            // a file the person edited is never written again without their word (--fresh); an earlier version's own file that nobody
            // touched was written again above. Either way, what the file in place still loads of an avoided place is said
            String note = avoidedNote();
            if (!note.isEmpty()) settingsNote = settingsNote.isEmpty() ? note : settingsNote + "\n  " + note;
            try { dir().toFile().setWritable(true, false); settings.toFile().setWritable(true, false); } catch (Exception ignored) { }   // the container's own user writes beside it
            if ((fresh || again) && !state().equals("absent")) runner.run(List.of("docker", "rm", "-f", CONTAINER));
            String state = state();
            Result r;
            if (state.equals("absent")) {
                r = runner.run(runCommand(port));
            } else if (state.equals("stopped")) {
                r = runner.run(List.of("docker", "start", CONTAINER));
            } else {
                r = new Result(0, "");
            }
            if (r.code() != 0) return "!docker: " + r.out().strip();
            String url = "http://127.0.0.1:" + port;
            for (int i = 0; i < 45; i++) {
                if (answers(url)) return url;
                Thread.sleep(2000);
            }
            return "!the container started but no JSON search answered at " + url + " within 90 seconds (docker logs " + CONTAINER + ")";
        } catch (Exception e) {
            return "!" + e.getMessage();
        }
    }

    /** How old an image may be before {@link #imageNote} says so: the engines' readers in it are as old as it is. */
    static final int IMAGE_DAYS = 90;

    /** The day the SearXNG image on this machine was made, as docker says it (2026-07-26); "" when there is none or docker does not answer. */
    static String imageMade() {
        try {
            Result r = runner.run(List.of("docker", "image", "inspect", "-f", "{{.Created}}", IMAGE));
            String out = r.code() == 0 ? r.out().strip() : "";
            return out.matches("\\d{4}-\\d{2}-\\d{2}.*") ? out.substring(0, 10) : "";
        } catch (Exception e) { return ""; }
    }

    /**
     * What status says of an old image; "" for a recent one or none. This program fetches the image once and never again, and SearXNG's
     * readers of the search engines go stale as the engines change: on 2026-11-20 the image of July 2026 got no answer from Yahoo in
     * twenty searches and first-word answers from Bing, and the day's image got answers from both to every one.
     */
    public static String imageNote() {
        String made = imageMade();
        if (made.isEmpty()) return "";
        try {
            long days = ChronoUnit.DAYS.between(LocalDate.parse(made), LocalDate.now());
            if (days < IMAGE_DAYS) return "";
            return "The SearXNG image on this machine is from " + made + " (" + days + " days old). Its readers of the search engines are that old, and engines that answer the current image may answer this one with nothing. "
                    + "`researchzosho search update` fetches the current image and makes the container again; your settings file stays as it is.";
        } catch (RuntimeException e) { return ""; }
    }

    /**
     * The current image fetched, and the container made again from it. The settings file is the person's when they edited it and is left
     * alone; this program's own is written again when the new image's list of engines makes it another ({@link #start}).
     */
    public static String update(int port) {
        if (!haveDocker()) return "!docker is not on this machine (https://docs.docker.com/get-docker/)";
        try {
            Result pulled = runner.run(List.of("docker", "pull", IMAGE));
            if (pulled.code() != 0) return "!docker could not fetch " + IMAGE + ": " + pulled.out().strip();
            imageEngines = null;   // the new image's own list is read when the settings are looked at
            if (!state().equals("absent")) runner.run(List.of("docker", "rm", "-f", CONTAINER));
            return start(port, false);
        } catch (Exception e) { return "!" + e.getMessage(); }
    }

    public static String stop() {
        try {
            Result r = runner.run(List.of("docker", "stop", CONTAINER));
            return r.code() == 0 ? "stopped" : "!docker: " + r.out().strip();
        } catch (Exception e) { return "!" + e.getMessage(); }
    }

    /** Whether a SearXNG at {@code base} answers a JSON search. */
    public static boolean answers(String base) {
        try {
            HttpClient http = PROBE_HTTP;
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(base.replaceAll("/+$", "") + "/search?q=ready&format=json"))
                    .timeout(Duration.ofSeconds(15)).header("Accept", "application/json").GET().build(), HttpResponse.BodyHandlers.ofString());
            return r.statusCode() == 200 && r.body().contains("\"results\"");
        } catch (Exception e) { return false; }
    }

    /** The docker command lines this would run, for a test to read without docker. */
    static List<List<String>> plan(int port) {
        List<List<String>> out = new ArrayList<>();
        out.add(List.of("docker", "run", "-d", "--name", CONTAINER, "--restart", "unless-stopped",
                "-p", "127.0.0.1:" + port + ":8080", "-v", dir().toAbsolutePath() + ":/etc/searxng",
                "-e", "BASE_URL=http://127.0.0.1:" + port + "/", IMAGE));
        return out;
    }
}
