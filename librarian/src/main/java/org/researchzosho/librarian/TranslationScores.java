package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.researchzosho.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * The scores a program gives each unit of a translation, with no model of the library's: LaBSE (sentence-transformers/LaBSE, Apache
 * 2.0, 0.5B, 109 languages) served by Text Embeddings Inference as the search embeddings are, the cosine between the source unit and
 * the translated unit, higher the closer in meaning; and MetricX-24-Hybrid in quality-estimation mode (google/metricx-24-hybrid-large-v2p6,
 * Apache 2.0, mT5-based), 0 to 25 where lower is better, an optional helper set up on first use in a Python environment of its own, as the
 * video helper is. The LaBSE server starts on demand through Docker, the model fetched once (about 1.9 GB); without Docker an address in
 * {@code RESEARCHZOSHO_LABSE} serves. Without MetricX a checked translation runs on LaBSE alone and the note says so.
 *
 * <p>What counts as a bad score is never a fixed number: the thresholds are measured per language pair from the scores of the units
 * accepted so far (catalog/translate-scores.json), the lowest tenth of LaBSE and the highest tenth of MetricX, once twenty are on
 * record; before that the scores are shown and nothing is flagged by them. The first units of a pair come from a translation read by hand
 * ({@code researchzosho translate scores accept}).
 */
public final class TranslationScores {
    private static final Logger log = LoggerFactory.getLogger(TranslationScores.class);
    private TranslationScores() { }

    private static final ObjectMapper M = new ObjectMapper();
    public static final String LABSE_CONTAINER = "researchzosho-labse", LABSE_MODEL = "sentence-transformers/LaBSE", LABSE_SIZE = "about 1.9 GB";
    public static final int LABSE_PORT = 8216;
    public static final String METRICX_MODEL = "google/metricx-24-hybrid-large-v2p6", METRICX_TOKENIZER = "google/mt5-xl";
    public static final String METRICX_SIZE = "about 4.7 GB for the model (its weights as 32-bit floats; the repository holds them twice over, and a fetch of both is 9.2 GB) and about 1 GB for its Python packages (torch's CPU build, transformers, sentencepiece)";
    /** Accepted units a pair needs on record before a score flags anything. */
    public static final int MIN_ACCEPTED = 20;

    /** A scorer: one score a pair (source, translation), null for a pair it could not score. */
    public interface Scorer {
        List<Double> score(List<String[]> pairs) throws IOException;
        /** The scorer's name for the note: LaBSE, MetricX-24. */
        String name();
        /** The model and where it runs. */
        String model();
        /** Whether a higher score is the better one. */
        boolean higherIsBetter();
    }

    /** The scorers at hand: LaBSE, and MetricX when it is installed; either null when not. */
    public record Scorers(Scorer labse, Scorer metricx) {
        public boolean any() { return labse != null || metricx != null; }
        /** The scorers that ran, named for the note. */
        public String said() {
            List<String> parts = new ArrayList<>();
            if (labse != null) parts.add(labse.name() + " (" + labse.model() + ")");
            if (metricx != null) parts.add(metricx.name() + " (" + metricx.model() + ")");
            return parts.isEmpty() ? "no scorer: LaBSE is not set up and MetricX-24 is not installed" : String.join(", ", parts) + (metricx == null ? "; MetricX-24 is not installed, so the tier ran on LaBSE alone" : "");
        }
    }

    /** The scorers for a run; a test stands this in. */
    static volatile Supplier<Scorers> SCORERS = TranslationScores::configured;

    static Scorers configured() {
        String url = labseAddress(true);
        Scorer labse = url == null ? null : labse(url);
        Scorer metricx = metricxInstalled() ? metricx() : null;
        return new Scorers(labse, metricx);
    }

    // ---- LaBSE through Text Embeddings Inference ----

    public static Path labseDir() { return Config.home().resolve("labse"); }

    /** The LaBSE server's address when one answers: {@code RESEARCHZOSHO_LABSE} (an address, or off), else the container, started when {@code start} and Docker is there; null when none. */
    public static String labseAddress(boolean start) {
        String set = Config.get("RESEARCHZOSHO_LABSE", "").strip();
        if (set.equalsIgnoreCase("off")) return null;
        if (!set.isEmpty()) return Embed.answers(set) ? set.replaceAll("/+$", "") : null;
        String local = "http://127.0.0.1:" + LABSE_PORT;
        if (Embed.answers(local)) return local;
        if (!start || !Searx.haveDocker()) return null;
        String r = startLabse(false);
        return r.startsWith("!") ? null : r;
    }

    public static String labseState() {
        try { Searx.Result r = Searx.runner.run(List.of("docker", "inspect", "-f", "{{.State.Running}}", LABSE_CONTAINER)); if (r.code() != 0) return "absent"; return r.out().strip().equals("true") ? "running" : "stopped"; }
        catch (Exception e) { return "absent"; }
    }

    /** What setting LaBSE up would take, for a plan that says it first; "" when it is already there. */
    public static String labseSetupSaid() {
        String set = Config.get("RESEARCHZOSHO_LABSE", "").strip();
        if (set.equalsIgnoreCase("off")) return "LaBSE is off (RESEARCHZOSHO_LABSE=off): no score by meaning";
        if (!set.isEmpty() || Embed.answers("http://127.0.0.1:" + LABSE_PORT) || labseState().equals("running")) return "";
        if (!labseState().equals("absent")) return "the LaBSE server is stopped; the run starts it";
        return Searx.haveDocker() ? "LaBSE is not set up yet: the run starts it through Docker, and the model is fetched once (" + LABSE_MODEL + ", " + LABSE_SIZE + ")" : "LaBSE is not set up and Docker is not on this machine: no score by meaning (RESEARCHZOSHO_LABSE names a server elsewhere)";
    }

    /** Starts the LaBSE container, fetching the model the first time; the address, or "!" and what stopped it. */
    public static String startLabse(boolean cpu) {
        if (!Searx.haveDocker()) return "!docker is not on this machine (https://docs.docker.com/get-docker/)";
        try {
            Files.createDirectories(labseDir());
            String tag = cpu ? "cpu-" + Embed.VERSION : Embed.teiTag();
            String state = labseState();
            Searx.Result r;
            if (state.equals("absent")) r = Searx.runner.run(labseCommand(LABSE_PORT, tag));
            else if (state.equals("stopped")) r = Searx.runner.run(List.of("docker", "start", LABSE_CONTAINER));
            else r = new Searx.Result(0, "");
            if (r.code() != 0) return "!docker: " + r.out().strip();
            String url = "http://127.0.0.1:" + LABSE_PORT;
            for (int i = 0; i < 300; i++) { if (Embed.answers(url)) return url; Thread.sleep(2000); }
            return "!the container started but no embeddings call answered at " + url + " within ten minutes (docker logs " + LABSE_CONTAINER + ")";
        } catch (Exception e) { return "!" + e.getMessage(); }
    }

    public static String stopLabse() {
        try { Searx.Result r = Searx.runner.run(List.of("docker", "stop", LABSE_CONTAINER)); return r.code() == 0 ? "stopped" : "!docker: " + r.out().strip(); }
        catch (Exception e) { return "!" + e.getMessage(); }
    }

    /** The docker command that creates the LaBSE container: loopback only, the GPU when the tag is not cpu, CLS pooling as the model declares. */
    static List<String> labseCommand(int port, String tag) {
        List<String> cmd = new ArrayList<>(List.of("docker", "run", "-d", "--name", LABSE_CONTAINER, "--restart", "unless-stopped", "-p", "127.0.0.1:" + port + ":80", "-v", labseDir().toAbsolutePath() + ":/data"));
        if (!tag.startsWith("cpu")) { cmd.add("--gpus"); cmd.add("all"); }
        cmd.add(Embed.IMAGE + ":" + tag);
        cmd.addAll(List.of("--model-id", LABSE_MODEL, "--max-client-batch-size", "128", "--max-batch-tokens", tag.startsWith("cpu") ? "2048" : "16384", "--auto-truncate"));
        return cmd;
    }

    /** The cosine between each source unit and its translation, through a LaBSE server; 0 to 1 after the server's normalisation, higher the closer. */
    static Scorer labse(String url) {
        Embeddings.Embedder e = Embeddings.http(url, "embed");
        return new Scorer() {
            @Override public List<Double> score(List<String[]> pairs) {
                List<String> texts = new ArrayList<>();
                for (String[] p : pairs) { texts.add(DocUnits.plainText(p[0])); texts.add(DocUnits.plainText(p[1])); }
                List<float[]> vs = e.embedAll(texts);
                List<Double> out = new ArrayList<>();
                for (int i = 0; i < pairs.size(); i++) {
                    float[] a = vs.size() > 2 * i ? vs.get(2 * i) : null, b = vs.size() > 2 * i + 1 ? vs.get(2 * i + 1) : null;
                    if (a == null || b == null || a.length != b.length) { out.add(null); continue; }
                    double dot = 0; for (int k = 0; k < a.length; k++) dot += a[k] * b[k];
                    out.add(Math.round(dot * 10000) / 10000.0);
                }
                return out;
            }
            @Override public String name() { return "LaBSE"; }
            @Override public String model() { return LABSE_MODEL + " at " + url; }
            @Override public boolean higherIsBetter() { return true; }
        };
    }

    // ---- MetricX-24 in a Python environment of its own ----

    public static Path metricxDir() { return Config.home().resolve("metricx"); }
    static Path metricxScript() { return metricxDir().resolve("metricx_score.py"); }
    static Path metricxPython() {
        String set = Config.get("RESEARCHZOSHO_METRICX_PYTHON", "").strip();
        if (!set.isEmpty()) return Path.of(set);
        boolean win = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        return metricxDir().resolve("venv").resolve(win ? "Scripts" : "bin").resolve(win ? "python.exe" : "python");
    }
    static Path metricxReady() { return metricxDir().resolve("ready"); }

    // ---- the two scorers set up where they are missing: by setup, by the daemon when it starts (so after an update), from the Translate fold ----

    /** {@code RESEARCHZOSHO_SCORERS=off} leaves the scorers out; anything else, the default, sets them up where they are missing. */
    public static boolean setupAllowed() { return !Config.get("RESEARCHZOSHO_SCORERS", "").strip().equalsIgnoreCase("off"); }

    /** The room the two scorers take on the disk under the library's home: LaBSE's model, MetricX's model and packages, and the fetch that holds the model twice. */
    public static final long NEEDS_BYTES = 16L << 30;

    /** The usable room on the disk under the library's home, or -1 when it cannot be read; a test stands it in. */
    static volatile LongSupplier FREE = TranslationScores::freeBytesNow;
    public static long freeBytes() { return FREE.getAsLong(); }
    static long freeBytesNow() {
        try { Path h = Config.home(); Files.createDirectories(h); return Files.getFileStore(h).getUsableSpace(); } catch (Exception e) { return -1; }
    }

    /** What is missing of the two scorers, for a person: "LaBSE (about 1.9 GB) and MetricX-24 (…)"; "" when both are there. */
    public static String missingSaid() {
        List<String> parts = new ArrayList<>();
        if (labseSetupSaid().startsWith("LaBSE is not set up")) parts.add("LaBSE (" + LABSE_SIZE + ")");
        if (!metricxInstalled()) parts.add("MetricX-24 (" + METRICX_SIZE + ")");
        return String.join(" and ", parts);
    }

    /** The two acts, stood in by tests: LaBSE's start through Docker, MetricX's install. Each answers the address or "ready", or "!" and the reason. */
    static volatile Supplier<String> LABSE_START = () -> startLabse(false), METRICX_INSTALL = TranslationScores::installMetricx;

    /** Sets up what is missing, one after the other, saying each step to {@code say}; "ready" when both are there afterwards, else what stood in the way. Minutes long: a caller runs it off any request thread. */
    public static synchronized String ensureAll(Consumer<String> say) {
        List<String> notes = new ArrayList<>();
        if (labseSetupSaid().startsWith("LaBSE is not set up")) {
            if (!Searx.haveDocker()) notes.add("LaBSE needs Docker, which is not on this machine");
            else {
                say.accept("Setting LaBSE up: " + LABSE_MODEL + ", " + LABSE_SIZE + ", a server through Docker");
                String r = LABSE_START.get();
                notes.add(r.startsWith("!") ? "LaBSE: " + r.substring(1) : "LaBSE answers at " + r);
            }
        }
        if (!metricxInstalled()) {
            say.accept("Installing MetricX-24: " + METRICX_MODEL + ", " + METRICX_SIZE);
            String r = METRICX_INSTALL.get();
            notes.add(r.equals("ready") ? "MetricX-24 is ready" : "MetricX-24: " + (r.startsWith("!") ? r.substring(1) : r));
        }
        boolean both = missingSaid().isEmpty();
        return both ? "ready" : String.join("; ", notes);
    }

    private static volatile Thread setupThread;
    /** The marker of a setup that failed, with the reason; a new try waits a day. */
    static Path triedMark() { return Config.home().resolve("scorers.tried"); }

    /**
     * Sets the missing scorers up in the background, one setup at a time: what was started, or why nothing was ("" when nothing is
     * missing or the setting is off). The daemon calls this when it starts, so a release that needs the scorers sets them up after an
     * update; the Translate fold calls it on the person's click. A try that failed is not repeated within a day; a disk without the room is said.
     */
    public static synchronized String setupInBackground(String why) {
        Thread t = setupThread;
        if (t != null && t.isAlive()) return "the scorers are being set up already";
        if (!setupAllowed()) return "";
        String what = missingSaid();
        if (what.isEmpty()) return "";
        try {
            Path mark = triedMark();
            if (Files.exists(mark) && Files.getLastModifiedTime(mark).toInstant().isAfter(Instant.now().minus(Duration.ofDays(1))))
                return "a setup of the scorers failed within the day (" + Files.readString(mark).strip() + "); researchzosho translate scorers install tries now";
        } catch (IOException ignored) { }
        long free = freeBytes();
        if (free >= 0 && free < NEEDS_BYTES) return "the disk under " + Config.home() + " has " + (free >> 30) + " GB free and the scorers need about " + (NEEDS_BYTES >> 30) + " GB, so they are not set up; RESEARCHZOSHO_SCORERS=off stops this notice";
        t = new Thread(() -> {
            String r = ensureAll(line -> log.info("scorers: {}", line));
            log.info("scorers: {}", r);
            try { if (r.equals("ready")) Files.deleteIfExists(triedMark()); else Files.writeString(triedMark(), r + "\n", StandardCharsets.UTF_8); } catch (IOException ignored) { }
        }, "scorers-setup");
        t.setDaemon(true); setupThread = t; t.start();
        return "setting up " + what + " in the background, " + why;
    }

    /** Whether the background setup is running now. */
    public static boolean setupRunning() { Thread t = setupThread; return t != null && t.isAlive(); }

    /** Waits for a background setup to end; for tests. */
    static void awaitSetup() throws InterruptedException { Thread t = setupThread; if (t != null) t.join(60_000); }

    /**
     * The MetricX script follows the release: when the build's differs from the installed one it is copied over (the script runs afresh at
     * each scoring call, so nothing restarts; its packages are pip's, upgraded at install, and not keyed). One line, "" when nothing was to
     * do or MetricX is not installed. Run at the daemon's start and by {@code translate scorers status|install}.
     */
    public static String refreshMetricxScript() {
        if (!Files.exists(metricxPython()) || !Files.isRegularFile(metricxScript())) return "";
        try (InputStream in = TranslationScores.class.getResourceAsStream("/org/researchzosho/librarian/metricx_score.py")) {
            if (in == null) return "";
            byte[] now = in.readAllBytes();
            if (Arrays.equals(now, Files.readAllBytes(metricxScript()))) return "";
            Files.write(metricxScript(), now);
            return "The translation scorers' MetricX script is from an earlier release; refreshed.";
        } catch (IOException e) { return "The translation scorers' MetricX script is from an earlier release, and could not be refreshed: " + e.getMessage(); }
    }

    /** Whether the helper is installed: its Python and the marker its first successful load left. */
    public static boolean metricxInstalled() { return Files.isRegularFile(metricxReady()) && Files.isRegularFile(metricxScript()) && Files.exists(metricxPython()); }

    /** What installing MetricX takes, for a plan that says it first; "" when it is installed. */
    public static String metricxSetupSaid() {
        return metricxInstalled() ? "" : "MetricX-24 is not installed, so the tier runs on LaBSE alone; researchzosho translate scorers install sets it up (" + METRICX_SIZE + ")";
    }

    /** Sets the helper up: a Python environment with torch's CPU build and transformers, the script, the model fetched on the first load; "ready", or "!" and what stopped it. */
    public static String installMetricx() {
        try {
            Files.createDirectories(metricxDir());
            try (InputStream in = TranslationScores.class.getResourceAsStream("/org/researchzosho/librarian/metricx_score.py")) {
                if (in == null) return "!the scoring script is not in this build";
                Files.write(metricxScript(), in.readAllBytes());
            }
            Path py = metricxPython();
            if (Config.get("RESEARCHZOSHO_METRICX_PYTHON", "").isBlank() && !Files.exists(py)) {
                Path venv = metricxDir().resolve("venv");
                Searx.Result v = Searx.runner.run(List.of(System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? "python" : "python3", "-m", "venv", venv.toString()));
                if (v.code() != 0) return "!Python's venv could not make " + venv + ": " + last(v.out()) + " (Python 3 with venv is needed)";
                Path pip = venv.resolve(System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? "Scripts" : "bin").resolve(System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? "pip.exe" : "pip");
                Searx.Result t = Searx.runner.run(List.of(pip.toString(), "install", "--quiet", "--upgrade", "torch", "--index-url", "https://download.pytorch.org/whl/cpu"));
                if (t.code() != 0) return "!installing torch into " + venv + " failed: " + last(t.out());
                Searx.Result p = Searx.runner.run(List.of(pip.toString(), "install", "--quiet", "--upgrade", "transformers", "sentencepiece", "protobuf"));
                if (p.code() != 0) return "!installing transformers into " + venv + " failed: " + last(p.out());
            }
            // the first load fetches the model and the tokenizer; a load that answers ready leaves the marker
            Process pr = new ProcessBuilder(pythonCommand(py, "--check")).redirectErrorStream(true).start();
            String out;
            try (InputStream in = pr.getInputStream()) { out = new String(in.readAllBytes(), StandardCharsets.UTF_8); }
            if (!pr.waitFor(60, TimeUnit.MINUTES)) { pr.destroyForcibly(); return "!the model did not load within an hour"; }
            if (pr.exitValue() != 0 || !out.contains("\"ready\"")) return "!the model could not be loaded: " + last(out);
            Files.writeString(metricxReady(), METRICX_MODEL + "\n", StandardCharsets.UTF_8);
            return "ready";
        } catch (Exception e) { return "!" + e.getMessage(); }
    }

    /**
     * The command that runs the script: the helper's own environment in isolated mode; a Python the person named with
     * {@code RESEARCHZOSHO_METRICX_PYTHON} with no PYTHONPATH and no script folder on its path, but its own site packages, since torch
     * lives there (a user-site torch was hidden by -I, 2026-11-07).
     */
    static List<String> pythonCommand(Path py, String... more) {
        List<String> cmd = new ArrayList<>(List.of(py.toString()));
        if (Config.get("RESEARCHZOSHO_METRICX_PYTHON", "").isBlank()) cmd.add("-I"); else cmd.addAll(List.of("-E", "-P"));
        cmd.addAll(List.of(metricxScript().toString(), "--model", METRICX_MODEL, "--tokenizer", METRICX_TOKENIZER, "--cache", metricxDir().resolve("hub").toString()));
        cmd.addAll(List.of(more));
        return cmd;
    }

    static String last(String s) { String[] ls = (s == null ? "" : s).strip().split("\n"); return ls.length == 0 ? "" : ls[ls.length - 1].strip(); }

    /** MetricX-24 in QE mode over the pairs: the script loaded once for the batch, one line a unit. */
    static Scorer metricx() {
        return new Scorer() {
            @Override public List<Double> score(List<String[]> pairs) throws IOException {
                List<Double> out = new ArrayList<>();
                if (pairs.isEmpty()) return out;
                Process pr = new ProcessBuilder(pythonCommand(metricxPython())).redirectError(ProcessBuilder.Redirect.DISCARD).start();
                try (Writer w = new OutputStreamWriter(pr.getOutputStream(), StandardCharsets.UTF_8)) {
                    for (String[] p : pairs) { ObjectNode j = M.createObjectNode(); j.put("source", DocUnits.plainText(p[0])); j.put("hypothesis", DocUnits.plainText(p[1])); w.write(j.toString()); w.write('\n'); }
                }
                try (BufferedReader r = new BufferedReader(new InputStreamReader(pr.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) { if (line.isBlank()) continue; try { JsonNode j = M.readTree(line); out.add(j.has("score") ? j.get("score").asDouble() : null); } catch (IOException e) { out.add(null); } }
                }
                try { if (!pr.waitFor(30, TimeUnit.MINUTES)) pr.destroyForcibly(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                while (out.size() < pairs.size()) out.add(null);
                return out;
            }
            @Override public String name() { return "MetricX-24"; }
            @Override public String model() { return METRICX_MODEL + " in QE mode, " + (Config.get("RESEARCHZOSHO_METRICX_PYTHON", "").isBlank() ? "in its own Python environment" : "with " + Config.get("RESEARCHZOSHO_METRICX_PYTHON", "")); }
            @Override public boolean higherIsBetter() { return false; }
        };
    }

    // ---- the distribution of accepted units, per language pair ----

    public static Path file(LibraryStore store) { return store.root().resolve("catalog").resolve("translate-scores.json"); }

    static String pair(String from, String to) { return from + "→" + to; }

    /** What flags a score for a pair: the lowest tenth of LaBSE and the highest tenth of MetricX among the accepted units, once twenty are on record. */
    public record Thresholds(String pair, int labseAccepted, int metricxAccepted, Double labseLow, Double metricxHigh) {
        public boolean ready() { return labseLow != null || metricxHigh != null; }
        /** What the thresholds are, for the note. */
        public String said() {
            if (!ready()) return "no distribution yet for " + pair + ": nothing is flagged by score until " + MIN_ACCEPTED + " accepted units are on record (" + Math.max(labseAccepted, metricxAccepted) + " so far); the scores are shown";
            StringBuilder b = new StringBuilder();
            if (labseLow != null) b.append("LaBSE below ").append(labseLow).append(" flags (the lowest tenth of ").append(labseAccepted).append(" accepted units)");
            if (metricxHigh != null) b.append(b.length() == 0 ? "" : "; ").append("MetricX-24 above ").append(metricxHigh).append(" flags (the highest tenth of ").append(metricxAccepted).append(" accepted units)");
            return b.toString();
        }
    }

    public static Thresholds thresholds(LibraryStore store, String from, String to) {
        List<Double> labse = accepted(store, from, to, "labse"), metricx = accepted(store, from, to, "metricx");
        return new Thresholds(pair(from, to), labse.size(), metricx.size(), labse.size() >= MIN_ACCEPTED ? percentile(labse, 10) : null, metricx.size() >= MIN_ACCEPTED ? percentile(metricx, 90) : null);
    }

    static List<Double> accepted(LibraryStore store, String from, String to, String scorer) {
        List<Double> out = new ArrayList<>();
        try {
            Path f = file(store);
            if (!Files.isRegularFile(f)) return out;
            for (JsonNode v : M.readTree(Files.readString(f, StandardCharsets.UTF_8)).path(pair(from, to)).path(scorer)) if (v.isNumber()) out.add(v.asDouble());
        } catch (IOException ignored) { }
        Collections.sort(out);
        return out;
    }

    /** The scores of units accepted as right go on the record for their pair; a null score is skipped. */
    public static synchronized void accept(LibraryStore store, String from, String to, List<Double> labse, List<Double> metricx) {
        try {
            Path f = file(store);
            ObjectNode j = Files.isRegularFile(f) ? (ObjectNode) M.readTree(Files.readString(f, StandardCharsets.UTF_8)) : M.createObjectNode();
            ObjectNode p = j.has(pair(from, to)) ? (ObjectNode) j.get(pair(from, to)) : j.putObject(pair(from, to));
            for (String scorer : List.of("labse", "metricx")) {
                List<Double> add = scorer.equals("labse") ? labse : metricx;
                if (add == null) continue;
                ArrayNode a = p.has(scorer) ? (ArrayNode) p.get(scorer) : p.putArray(scorer);
                for (Double d : add) if (d != null) a.add(Math.round(d * 10000) / 10000.0);
            }
            Files.createDirectories(f.getParent());
            AtomicWrite.text(f, M.writerWithDefaultPrettyPrinter().writeValueAsString(j) + "\n");
        } catch (IOException ignored) { /* the record only sharpens the next threshold */ }
    }

    /** The value below which {@code p} per cent of a sorted list lies. */
    static double percentile(List<Double> sorted, double p) {
        if (sorted.isEmpty()) return 0;
        double rank = p / 100.0 * (sorted.size() - 1);
        int lo = (int) Math.floor(rank), hi = (int) Math.ceil(rank);
        double v = sorted.get(lo) + (sorted.get(hi) - sorted.get(lo)) * (rank - lo);
        return Math.round(v * 10000) / 10000.0;
    }
}
