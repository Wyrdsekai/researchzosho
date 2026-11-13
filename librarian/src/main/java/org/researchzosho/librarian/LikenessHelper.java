package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.researchzosho.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.net.http.HttpRequest;
import java.net.http.HttpClient;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * The likeness helper (0.7.0): two embeddings measured on the CPU in a Python environment of the library's own, so that a judgement of
 * "the same person" or "the same object, place or style" rests first on a number and then on the seeing model's reading. The picture
 * embedding is open_clip's ViT-B-32 with the LAION-2B weights ({@code laion2b_s34b_b79k}: the code MIT, the weights MIT by their model
 * card, 605,219,813 bytes); the face embedding is OpenCV's SFace recogniser (Apache-2.0, 38,696,353 bytes) after its YuNet detector (MIT,
 * 232,589 bytes), from opencv/opencv_zoo. facenet-pytorch was not taken: its code is MIT but its weights were trained on VGGFace2, a
 * dataset licensed for non-commercial research, so they are not clearly free for every library's use.
 *
 * <p>Set up the way the translation scorers are: the sizes said first, a disk gate, by the daemon in the background when the room is
 * there, by {@code researchzosho images likeness install} now; {@code RESEARCHZOSHO_LIKENESS=off} leaves it out. One helper process a
 * daemon, started on the first measure, JSON lines over its stdin and stdout ({@code likeness_helper.py}). Without it every judgement
 * works as before and the replies say "no likeness helper is set up; the model's reading alone".
 *
 * <p>The face thresholds come from 26 public-domain portraits of thirteen people, two sittings each, under
 * {@code src/test/resources/images/likeness} (their sources in SOURCES.md), measured on 2026-11-08: the same person's two sittings
 * scored 0.499 to 0.923 (median 0.661) and two different people 0.087 to 0.336 (median 0.186), so a face score at or above
 * {@link #FACE_SAME} says the same person and one below {@link #FACE_DIFFERENT} says a different one; between them the number is said and
 * the model decides. The picture embedding does not separate people (the same person's sittings scored 0.586 to 0.946, different people
 * up to 0.669), so it ranks the candidates and is said, and never overrides a verdict.
 */
final class LikenessHelper {
    private static final Logger log = LoggerFactory.getLogger(LikenessHelper.class);
    private static final ObjectMapper M = new ObjectMapper();

    static final String SETTING = "RESEARCHZOSHO_LIKENESS";
    static final String SHORT = "CLIP ViT-B-32 for pictures, SFace for faces";
    static final String PICTURE_MODEL = "open_clip ViT-B-32 with the LAION-2B weights (laion2b_s34b_b79k)";
    static final String FACE_MODEL = "OpenCV's YuNet face detector and SFace face recogniser";
    static final String SIZES = "about 1.3 GB of Python packages (torch's CPU build, torchvision, open_clip, OpenCV), 605 MB for the picture model's weights and 39 MB for the two face models";
    static final String LICENCES = "open_clip's code and the LAION weights are MIT, YuNet is MIT, SFace is Apache-2.0";
    /** The room the helper takes on the disk under the library's home, with the fetches' headroom. */
    static final long NEEDS_BYTES = 4L << 30;
    /** A face score at or above this says the same person; below {@link #FACE_DIFFERENT} a different one; between them the model decides. */
    static final double FACE_SAME = 0.45, FACE_DIFFERENT = 0.30;
    /**
     * A face box under this many pixels on its longer side, or found with less confidence than this, is not measured: the picture score is
     * said instead. The Stais likeness live (2026-11-08) measured 0.22 between a bust portrait and a full-length photograph of the same man
     * decades younger, whose face was small; the recogniser bridges neither decades nor tiny faces.
     */
    static final int MIN_FACE_PX = 80;
    static final double MIN_FACE_CONF = 0.7;

    private LikenessHelper() { }

    /**
     * One measurement of a candidate against the reference: the picture similarity; the face similarity when both pictures hold a face, with
     * each face box's longer side in pixels and the detector's confidence, which decide whether the face score is used ({@link #faceUsable}).
     */
    record Measure(double picture, Double face, boolean refFace, boolean candFace, int refPx, int candPx, double refConf, double candConf) {
        /** A measure whose faces, when found, are large and clear enough to use. */
        Measure(double picture, Double face, boolean refFace, boolean candFace) { this(picture, face, refFace, candFace, face == null ? 0 : 1000, face == null ? 0 : 1000, face == null ? 0 : 1.0, face == null ? 0 : 1.0); }
        /** Whether the face score stands: both faces found, neither under {@link LikenessHelper#MIN_FACE_PX} on its longer side nor found with less confidence than {@link LikenessHelper#MIN_FACE_CONF}. */
        boolean faceUsable() { return face != null && Math.min(refPx, candPx) >= MIN_FACE_PX && Math.min(refConf, candConf) >= MIN_FACE_CONF; }
        /** What ranks the candidates: the faces when both have one and can be measured, else the pictures. */
        double rank() { return faceUsable() ? face : picture; }
        /** Why the face score is not used, when faces were found: "the face in the candidate is too small to measure (41 px)"; "" when it is used or no face was found. */
        String faceUnusableWhy() {
            if (face == null || faceUsable()) return "";
            if (Math.min(refPx, candPx) < MIN_FACE_PX) return "the face in the " + (candPx <= refPx ? "candidate" : "reference") + " is too small to measure (" + Math.min(refPx, candPx) + " px)";
            return "the face in the " + (candConf <= refConf ? "candidate" : "reference") + " is not clear enough to measure (confidence " + fmt(Math.min(refConf, candConf)) + ")";
        }
        /** "faces measure 0.62" / "pictures measure 0.78 (no face found in the candidate)" / "the face in the candidate is too small to measure (41 px); pictures measure 0.78". */
        String said() {
            if (faceUsable()) return "faces measure " + fmt(face);
            if (face != null) return faceUnusableWhy() + "; pictures measure " + fmt(picture);
            String missing = !refFace && !candFace ? "no face found in either picture" : !refFace ? "no face found in the reference" : !candFace ? "no face found in the candidate" : "";
            return "pictures measure " + fmt(picture) + (missing.isEmpty() ? "" : " (" + missing + ")");
        }
        ObjectNode json(ObjectMapper m) {
            ObjectNode o = m.createObjectNode(); o.put("picture", round(picture)); if (face != null) { o.put("face", round(face)); o.put("face_used", faceUsable()); o.putArray("face_px").add(refPx).add(candPx); o.putArray("face_conf").add(round(refConf)).add(round(candConf)); }
            o.put("reference_face", refFace); o.put("candidate_face", candFace); o.put("said", said()); return o;
        }
        static String fmt(double d) { return String.format(Locale.ROOT, "%.2f", d); }
        static double round(double d) { return Math.round(d * 1000.0) / 1000.0; }
    }

    /** What measures: the helper when it is set up and on; a test stands one in. */
    interface Scorer { Measure score(byte[] ref, String refType, byte[] cand, String candType) throws IOException; }
    static volatile Scorer SCORER = null;

    /** The scorer at hand, or null: a stand-in first, else the helper when it is installed and the setting is on. */
    static Scorer scorer() {
        Scorer s = SCORER;
        if (s != null) return s;
        if (!on()) return null;
        String at = Helpers.address(Helpers.Helper.LIKENESS);
        if (at != null) return new Served(at);   // served by another machine (0.7.4)
        return Helpers.localAllowed() && installed() ? HELPER : null;
    }

    static boolean on() { return !Config.get(SETTING, "").strip().equalsIgnoreCase("off"); }

    /** "no likeness helper is set up; the model's reading alone" / "the likeness helper (…) measured each candidate against the reference first, and the model that sees judged them in that order". */
    static String said() { return said(Chrome.ENGLISH); }
    static String said(Chrome c) {
        Scorer s = scorer();
        if (s instanceof Served served) return c.t("the likeness helper ({0}), at {1}, measured each candidate against the reference first, and the model that sees judged them in that order", SHORT, served.address);
        return s == null ? c.t("no likeness helper is set up; the model's reading alone") : c.t("the likeness helper ({0}) measured each candidate against the reference first, and the model that sees judged them in that order", SHORT);
    }

    // ---- the helper's place on the disk ----

    static Path dir() { String set = Config.get("RESEARCHZOSHO_LIKENESS_DIR", "").strip(); return set.isEmpty() ? Config.home().resolve("likeness") : Path.of(set); }
    static Path script() { return dir().resolve("likeness_helper.py"); }
    static Path ready() { return dir().resolve("ready"); }
    static Path python() {
        String set = Config.get("RESEARCHZOSHO_LIKENESS_PYTHON", "").strip();
        if (!set.isEmpty()) return Path.of(set);
        boolean win = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        return dir().resolve("venv").resolve(win ? "Scripts" : "bin").resolve(win ? "python.exe" : "python");
    }
    /** The Python packages the helper needs, as pip takes them; a change here sets the helper up again on the next start. */
    static final String PACKAGES = "torch torchvision (the CPU index); open_clip_torch opencv-python-headless pillow numpy";

    /** The build's script, or null when it is not in this build. */
    static byte[] scriptBytes() { try (InputStream in = LikenessHelper.class.getResourceAsStream("/org/researchzosho/librarian/likeness_helper.py")) { return in == null ? null : in.readAllBytes(); } catch (IOException e) { return null; } }
    static String scriptHash() { byte[] b = scriptBytes(); return b == null ? "" : Images.sha256(b); }
    /** What the marker says the helper was set up with — the script's hash, the packages, the models — so a release that changes any of them sets up what changed. */
    static String markerText() { return "script " + scriptHash() + "\npackages " + PACKAGES + "\nmodels " + PICTURE_MODEL + "; " + FACE_MODEL + "\n"; }
    static boolean markerCurrent() { try { return Files.isRegularFile(ready()) && Files.readString(ready(), StandardCharsets.UTF_8).equals(markerText()); } catch (IOException e) { return false; } }
    static boolean packagesCurrent() { try { return Files.isRegularFile(ready()) && Files.readString(ready(), StandardCharsets.UTF_8).contains("\npackages " + PACKAGES + "\n"); } catch (IOException e) { return false; } }
    /** Whether the helper's environment is there, from whatever release: its Python and a script. */
    static boolean present() { return Files.isRegularFile(script()) && Files.exists(python()); }
    /** Whether the helper is installed as this release has it: its Python, the script, and the marker naming this script, these packages and these models. */
    static boolean installed() { return present() && markerCurrent(); }

    /** The load of the models through the installed script, "ready" or "!" and the reason; stood in by tests. */
    static volatile Supplier<String> CHECK = LikenessHelper::checkNow;
    static String checkNow() {
        try {
            Process pr = new ProcessBuilder(command(python(), "--check")).redirectErrorStream(true).start();
            String out;
            try (InputStream in = pr.getInputStream()) { out = new String(in.readAllBytes(), StandardCharsets.UTF_8); }
            if (!pr.waitFor(60, TimeUnit.MINUTES)) { pr.destroyForcibly(); return "!the models did not load within an hour"; }
            if (pr.exitValue() != 0 || !out.contains("\"ready\": true")) return "!the models could not be loaded: " + TranslationScores.last(out);
            return "ready";
        } catch (Exception e) { return "!" + e.getMessage(); }
    }

    /**
     * An installed helper follows the release: when the build's script differs from the installed one it is copied over, the helper
     * process is stopped so the next measure starts the new script, the models are loaded once to be sure, and the marker is rewritten;
     * when only the packages changed, the marker says so and the background setup installs them. One line for the log; "" when nothing
     * was to do. Run at the daemon's start and by {@code images likeness status|install}.
     */
    static synchronized String refresh() {
        if (!on() || !present()) return "";
        byte[] now = scriptBytes();
        if (now == null) return "";
        boolean differs;
        try { differs = !Arrays.equals(now, Files.readAllBytes(script())); } catch (IOException e) { differs = true; }
        if (!differs && markerCurrent()) return "";
        if (!differs) return "The likeness helper's packages are from an earlier release; set up again in the background.";
        try { Files.write(script(), now); } catch (IOException e) { return "The likeness helper's script is from an earlier release, and could not be refreshed: " + e.getMessage(); }
        HELPER.stop();
        String r = CHECK.get();
        if (!r.equals("ready")) return "The likeness helper's script is from an earlier release; refreshing it failed: " + r.replaceFirst("^!", "");
        try { Files.writeString(ready(), markerText(), StandardCharsets.UTF_8); } catch (IOException ignored) { }
        return "The likeness helper's script is from an earlier release; refreshed.";
    }

    /** The helper's state, for a person. */
    static String status() {
        if (!on()) return "The likeness helper is off (" + SETTING + "=off): judgements of likeness rest on the seeing model's reading alone.";
        String at = Helpers.address(Helpers.Helper.LIKENESS);
        if (at != null) { String h = Helpers.health(at); return "The likeness helper is served at " + at + (h == null ? ", which does not answer now: judgements of likeness rest on the seeing model's reading alone until it does." : " (" + h + ")."); }
        if (!Helpers.localAllowed()) return "The likeness helper is not run on this machine (local_helpers off) and no address names one: judgements of likeness rest on the seeing model's reading alone. researchzosho settings set helpers http://<machine>:" + Helpers.PORT + " names a machine that serves it.";
        if (installed()) return "The likeness helper is set up at " + dir() + ": " + PICTURE_MODEL + " for pictures, " + FACE_MODEL + " for faces (" + LICENCES + "). A judgement of likeness measures each candidate against the reference first and the model that sees judges them in that order.";
        String tried = "";
        try { if (Files.isRegularFile(triedMark())) tried = " The last setup failed: " + Files.readString(triedMark()).strip() + "."; } catch (IOException ignored) { }
        return "The likeness helper is not set up: judgements of likeness rest on the seeing model's reading alone. researchzosho images likeness install sets it up (" + SIZES + "; " + LICENCES + ") on the CPU; " + SETTING + "=off leaves it out." + tried;
    }

    /** The usable room on the disk under the library's home, or -1 when it cannot be read; a test stands it in. */
    static volatile LongSupplier FREE = LikenessHelper::freeBytesNow;
    static long freeBytesNow() { try { Path h = Config.home(); Files.createDirectories(h); return Files.getFileStore(h).getUsableSpace(); } catch (Exception e) { return -1; } }

    /** The install, stood in by tests: "ready", or "!" and the reason. */
    static volatile Supplier<String> INSTALL = LikenessHelper::install;

    /** Sets the helper up: a Python environment with torch's CPU build, open_clip and OpenCV, the script, the models fetched on the first load; "ready", or "!" and what stopped it. */
    static String install() {
        try {
            Files.createDirectories(dir());
            try (InputStream in = LikenessHelper.class.getResourceAsStream("/org/researchzosho/librarian/likeness_helper.py")) {
                if (in == null) return "!the helper's script is not in this build";
                Files.write(script(), in.readAllBytes());
            }
            Path py = python();
            boolean win = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
            if (Config.get("RESEARCHZOSHO_LIKENESS_PYTHON", "").isBlank() && (!Files.exists(py) || !packagesCurrent())) {   // a release that names other packages installs them into the environment that is there
                Path venv = dir().resolve("venv");
                Searx.Result v = Searx.runner.run(List.of(win ? "python" : "python3", "-m", "venv", venv.toString()));
                if (v.code() != 0) return "!Python's venv could not make " + venv + ": " + TranslationScores.last(v.out()) + " (Python 3 with venv is needed)";
                Path pip = venv.resolve(win ? "Scripts" : "bin").resolve(win ? "pip.exe" : "pip");
                Searx.Result t = Searx.runner.run(List.of(pip.toString(), "install", "--quiet", "--upgrade", "torch", "torchvision", "--index-url", "https://download.pytorch.org/whl/cpu"));
                if (t.code() != 0) return "!installing torch into " + venv + " failed: " + TranslationScores.last(t.out());
                Searx.Result p = Searx.runner.run(List.of(pip.toString(), "install", "--quiet", "--upgrade", "open_clip_torch", "opencv-python-headless", "pillow", "numpy"));
                if (p.code() != 0) return "!installing open_clip and OpenCV into " + venv + " failed: " + TranslationScores.last(p.out());
            }
            String r = CHECK.get();
            if (!r.equals("ready")) return r;
            Files.writeString(ready(), markerText(), StandardCharsets.UTF_8);
            return "ready";
        } catch (Exception e) { return "!" + e.getMessage(); }
    }

    /** The command that runs the script: the helper's own environment in isolated mode, or a Python the person named with its own site packages. */
    static List<String> command(Path py, String... more) {
        List<String> cmd = new ArrayList<>(List.of(py.toString()));
        if (Config.get("RESEARCHZOSHO_LIKENESS_PYTHON", "").isBlank()) cmd.add("-I"); else cmd.addAll(List.of("-E", "-P"));
        cmd.addAll(List.of(script().toString(), "--cache", dir().resolve("hub").toString(), "--models", dir().resolve("models").toString()));
        cmd.addAll(List.of(more));
        return cmd;
    }

    private static volatile Thread setupThread;
    static Path triedMark() { return dir().resolve("tried"); }

    /** Sets the helper up in the background: what was started, or why nothing was ("" when it is there or the setting is off). A failed try waits a day; a disk without the room is said. */
    static synchronized String setupInBackground(String why) {
        Thread t = setupThread;
        if (t != null && t.isAlive()) return "the likeness helper is being set up already";
        if (!on() || installed() || !Helpers.localAllowed() || Helpers.address(Helpers.Helper.LIKENESS) != null) return "";   // nothing to set up here
        try {
            Path mark = triedMark();
            if (Files.exists(mark) && Files.getLastModifiedTime(mark).toInstant().isAfter(Instant.now().minus(Duration.ofDays(1))))
                return "a setup of the likeness helper failed within the day (" + Files.readString(mark).strip() + "); researchzosho images likeness install tries now";
        } catch (IOException ignored) { }
        long free = FREE.getAsLong();
        if (free >= 0 && free < NEEDS_BYTES) return "the disk under " + Config.home() + " has " + (free >> 30) + " GB free and the likeness helper needs about " + (NEEDS_BYTES >> 30) + " GB, so it is not set up; " + SETTING + "=off leaves it out";
        t = new Thread(() -> {
            String r = INSTALL.get();
            log.info("likeness: {}", r.equals("ready") ? "the helper is ready" : r);
            try { Files.createDirectories(dir()); if (r.equals("ready")) Files.deleteIfExists(triedMark()); else Files.writeString(triedMark(), r.replaceFirst("^!", "") + "\n", StandardCharsets.UTF_8); } catch (IOException ignored) { }
        }, "likeness-setup");
        t.setDaemon(true); setupThread = t; t.start();
        return "setting up the likeness helper in the background (" + SIZES + "), " + why;
    }
    static boolean setupRunning() { Thread t = setupThread; return t != null && t.isAlive(); }
    static void awaitSetup() throws InterruptedException { Thread t = setupThread; if (t != null) t.join(60_000); }

    // ---- the helper served by another machine (0.7.4) ----

    /** The likeness helper at another machine's address: one request a call, the helpers' secret with it; a call that fails is that measure's failure. */
    static final class Served implements Scorer {
        private final String address;
        private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        Served(String address) { this.address = address; }
        @Override public Measure score(byte[] ref, String refType, byte[] cand, String candType) throws IOException {
            ObjectNode req = M.createObjectNode(); ObjectNode one = req.putArray("requests").addObject();
            one.put("id", "1"); one.put("ref", Base64.getEncoder().encodeToString(ref)); one.put("cand", Base64.getEncoder().encodeToString(cand));
            HttpResponse<String> r;
            try { r = HTTP.send(Helpers.request(address + "/measure").timeout(Duration.ofMinutes(5)).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(req.toString(), StandardCharsets.UTF_8)).build(), HttpResponse.BodyHandlers.ofString()); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("interrupted"); }
            if (r.statusCode() != 200) throw new IOException("the likeness helper at " + address + " answered HTTP " + r.statusCode());
            JsonNode j = M.readTree(r.body()).path("replies").path(0);
            if (j.isMissingNode() || j.has("error")) throw new IOException("the likeness helper at " + address + ": " + j.path("error").asText("no reply"));
            boolean refFace = j.path("faces").path(0).asBoolean(false), candFace = j.path("faces").path(1).asBoolean(false);
            return new Measure(j.path("picture").asDouble(), j.hasNonNull("face") ? j.path("face").asDouble() : null, refFace, candFace,
                    j.path("face_px").path(0).asInt(refFace ? 1000 : 0), j.path("face_px").path(1).asInt(candFace ? 1000 : 0), j.path("face_conf").path(0).asDouble(refFace ? 1.0 : 0), j.path("face_conf").path(1).asDouble(candFace ? 1.0 : 0));
        }
    }

    // ---- the one helper process a daemon ----

    private static final Helper HELPER = new Helper();

    /** The helper process: started on the first measure, one request a line, restarted once when it dies. */
    static final class Helper implements Scorer {
        private Process process; private Writer in; private BufferedReader out; private long n = 0;
        private synchronized void start() throws IOException {
            stop();
            process = new ProcessBuilder(command(python())).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            in = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
            out = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        }
        synchronized void stop() { if (process != null) { process.destroy(); process = null; in = null; out = null; } }
        @Override public synchronized Measure score(byte[] ref, String refType, byte[] cand, String candType) throws IOException {
            if (process == null || !process.isAlive()) start();
            try { return ask(ref, cand); }
            catch (IOException first) { start(); return ask(ref, cand); }   // once more on a fresh process
        }
        private Measure ask(byte[] ref, byte[] cand) throws IOException {
            ObjectNode req = M.createObjectNode(); req.put("id", String.valueOf(++n)); req.put("ref", Base64.getEncoder().encodeToString(ref)); req.put("cand", Base64.getEncoder().encodeToString(cand));
            in.write(req.toString()); in.write('\n'); in.flush();
            String line = out.readLine();
            if (line == null) throw new IOException("the likeness helper ended");
            JsonNode j = M.readTree(line);
            if (j.has("error")) throw new IOException("the likeness helper: " + j.path("error").asText());
            boolean refFace = j.path("faces").path(0).asBoolean(false), candFace = j.path("faces").path(1).asBoolean(false);
            return new Measure(j.path("picture").asDouble(), j.hasNonNull("face") ? j.path("face").asDouble() : null, refFace, candFace,
                    j.path("face_px").path(0).asInt(refFace ? 1000 : 0), j.path("face_px").path(1).asInt(candFace ? 1000 : 0), j.path("face_conf").path(0).asDouble(refFace ? 1.0 : 0), j.path("face_conf").path(1).asDouble(candFace ? 1.0 : 0));
        }
    }
}
