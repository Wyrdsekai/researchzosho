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
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.CompletableFuture;
import java.net.http.HttpResponse;
import java.net.http.HttpRequest;
import java.net.http.HttpClient;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Consumer;
import java.util.function.BiPredicate;
import java.util.function.BooleanSupplier;
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
 * <p>What counts as a bad score is never a fixed number: the thresholds are measured per language pair from the first-pass scores of
 * every paragraph checked translations put on the record (catalog/translate-scores.json), flagged or not, the lowest tenth of LaBSE and
 * the highest tenth of MetricX, once twenty are on it; before that the scores are shown and nothing is flagged by them. A fair sample,
 * so the thresholds flag about one paragraph in ten per scorer run after run ({@link #accepted}).
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

    /** A scorer: one score a pair (source, translation), null for a pair it could not score. A translation closes its scorers when it ends. */
    public interface Scorer extends AutoCloseable {
        List<Double> score(List<String[]> pairs) throws IOException;
        /** The scorer's name for the note: LaBSE, MetricX-24. */
        String name();
        /** The model and where it runs. */
        String model();
        /** Whether a higher score is the better one. */
        boolean higherIsBetter();
        /** What made the scorer give no more scores partway through, for the note; "" when nothing did. */
        default String stopped() { return ""; }
        /** Ends what the scorer keeps running, MetricX's process; a scorer that keeps nothing running has nothing to end. */
        @Override default void close() { }
        /** A stop asked in the middle of a step: the work under way ends now, a local process ended, a remote call abandoned. Any thread may call it. */
        default void abort() { }
    }

    /** The scorers at hand: LaBSE, and MetricX when it is installed; either null when not. Closed when the translation's checks end. */
    public record Scorers(Scorer labse, Scorer metricx) implements AutoCloseable {
        public boolean any() { return labse != null || metricx != null; }
        /** The scorers that ran, named for the note, and what stopped one partway. */
        public String said() {
            List<String> parts = new ArrayList<>();
            if (labse != null) parts.add(labse.name() + " (" + labse.model() + ")");
            if (metricx != null) parts.add(metricx.name() + " (" + metricx.model() + ")");
            StringBuilder stops = new StringBuilder();
            for (Scorer s : new Scorer[]{labse, metricx}) if (s != null && !s.stopped().isEmpty()) stops.append("; ").append(s.stopped());
            return parts.isEmpty() ? "no scorer: " + missing(Helpers.Helper.LABSE) + " and " + missing(Helpers.Helper.METRICX)
                    : String.join(", ", parts) + (metricx == null ? "; " + missing(Helpers.Helper.METRICX) + ", so the tier ran on LaBSE alone" : "") + (labse == null ? "; " + missing(Helpers.Helper.LABSE) + ", so the tier ran on MetricX-24 alone" : "") + stops;
        }
        @Override public void close() { if (labse != null) labse.close(); if (metricx != null) metricx.close(); }
        /** A stop in the middle of a step: each scorer ends what it has under way. */
        public void abort() { if (labse != null) labse.abort(); if (metricx != null) metricx.abort(); }
    }

    /**
     * Where LaBSE or MetricX-24 scores for this library, for {@code translate scorers status} (0.7.5), the two lines reading as a pair:
     * off; at the helpers address, with the front's own line ("scores at http://homebox:8217/metricx (google/… on cuda (card 2, …))"),
     * or that it does not answer there, or is not loaded there and why; on this machine, set up or not and how; or left out on a machine
     * that runs none, and why.
     */
    static String whereSaid(Helpers.Helper h) {
        if (off(h)) return "off (" + offSaid(h) + ")";
        String at = Helpers.address(h);
        if (at != null) {
            String line = null, base = Helpers.base();
            if (base != null && at.equals(base + "/" + h.path)) { Map<String, String> serves = Helpers.SERVES.apply(base); if (serves != null) line = serves.get(h.path); }
            if (line != null && line.startsWith("not loaded")) return "scores at " + at + ", which has it " + line;
            String health = Helpers.health(h, at);
            if (health == null) { String why = Helpers.unavailable(at); return "scores at " + at + (why != null ? ", which has it " + why : ", which does not answer now"); }
            String said = line != null && !line.isBlank() && !line.equals("answering") ? line : health;
            return "scores at " + at + (said.isBlank() ? "" : " (" + said + ")");
        }
        if (!Helpers.localAllowed()) return "left out: it is not run on this machine (local_helpers off) and no address names one";
        if (h == Helpers.Helper.LABSE) {
            String s = labseSetupSaid(), local = labseAddress(false);
            return !s.isEmpty() ? s : local != null ? "scores on this machine, answering at " + local : "scores on this machine; the server is not answering now, and a checked run starts it";
        }
        return metricxInstalled() ? "scores on this machine, installed in " + metricxDir() : METRICX_MISSING_SAID;
    }

    /** Why a scorer did not run, for the note: off, not run on this machine with no address naming one, named and not answering, or not set up here. */
    static String missing(Helpers.Helper h) {
        String name = h == Helpers.Helper.LABSE ? "LaBSE" : "MetricX-24";
        if (off(h)) return name + " is off (" + offSaid(h) + ")";
        String at = Helpers.address(h);
        if (at != null) return name + " at " + at + " did not answer";
        if (!Helpers.localAllowed()) return name + " is not run on this machine (local_helpers off) and no address names one";
        return h == Helpers.Helper.LABSE ? "LaBSE is not set up" : "MetricX-24 is not installed";
    }

    /** The scorers for a run; a test stands this in. */
    static volatile Supplier<Scorers> SCORERS = TranslationScores::configured;

    static Scorers configured() {
        if (off()) return new Scorers(null, null);   // left out, both: nothing is asked, here or anywhere
        String served = Helpers.off(Helpers.Helper.LABSE) ? null : Helpers.address(Helpers.Helper.LABSE);
        Scorer labse;
        if (served != null) labse = new Served(Helpers.Helper.LABSE, served);   // named on another machine: waits for it a bounded time when it does not answer
        else { String url = labseAddress(true); labse = url == null ? null : labse(url); }
        return new Scorers(labse, metricxScorer());
    }

    /** MetricX-24 for a run: off; else served at its address; else this machine's own process when it may run here and is installed; else none. */
    static Scorer metricxScorer() {
        if (off(Helpers.Helper.METRICX)) return null;
        String at = Helpers.address(Helpers.Helper.METRICX);
        if (at != null) return new Served(Helpers.Helper.METRICX, at);
        return Helpers.localAllowed() && metricxInstalled() ? metricx() : null;
    }

    // ---- LaBSE through Text Embeddings Inference ----

    public static Path labseDir() { return Config.home().resolve("labse"); }

    /** The LaBSE server's address when one answers: {@code RESEARCHZOSHO_LABSE} (an address, or off), else the container, started when {@code start} and Docker is there; null when none. */
    public static String labseAddress(boolean start) {
        if (off(Helpers.Helper.LABSE)) return null;   // a LaBSE that answers on this machine's port is not used either
        String at = Helpers.address(Helpers.Helper.LABSE);
        if (at != null) return Embed.answers(at) ? at : null;
        if (!Helpers.localAllowed()) return null;   // this machine runs no heavy helper: none is started or used here
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

    /** What the scorers need before a checked run, each a sentence of its own, in English; {@link #setupIn} says them in another language. */
    static final String LABSE_OFF_SAID = "LaBSE is off (RESEARCHZOSHO_LABSE=off): no score by meaning",
            LABSE_STOPPED_SAID = "the LaBSE server is stopped; the run starts it",
            LABSE_FETCH_SAID = "LaBSE is not set up yet: the run starts it through Docker, and the model is fetched once (" + LABSE_MODEL + ", " + LABSE_SIZE + ")",
            LABSE_NO_DOCKER_SAID = "LaBSE is not set up and Docker is not on this machine: no score by meaning (RESEARCHZOSHO_LABSE names a server elsewhere)",
            METRICX_MISSING_SAID = "MetricX-24 is not installed, so the tier runs on LaBSE alone; researchzosho translate scorers install sets it up (" + METRICX_SIZE + ")";
    static final List<String> SETUP_SENTENCES = List.of(LABSE_OFF_SAID, LABSE_STOPPED_SAID, LABSE_FETCH_SAID, LABSE_NO_DOCKER_SAID, METRICX_MISSING_SAID);

    /** What the scorers need, as a plan said it in English, in another language: each of its sentences put into the person's words. */
    static String setupIn(String english, Chrome c) {
        if (english == null || english.isEmpty() || c.english()) return english == null ? "" : english;
        TreeMap<Integer, String> at = new TreeMap<>();   // the sentences it holds, in their order
        for (String s : SETUP_SENTENCES) { int i = english.indexOf(s); if (i >= 0) at.put(i, s); }
        if (String.join("; ", at.values()).equals(english)) {   // each in the person's words, joined as their language joins two clauses
            List<String> said = new ArrayList<>(); for (String s : at.values()) said.add(c.t(s));
            return Translation.clauses(c, said.toArray(new String[0]));
        }
        String out = english;
        for (String s : SETUP_SENTENCES) out = out.replace(s, c.t(s));
        return out;
    }

    /** What setting LaBSE up would take, for a plan that says it first; "" when it is already there. */
    public static String labseSetupSaid() {
        if (off()) return "";   // the plan's own clause says both are off
        if (Helpers.off(Helpers.Helper.LABSE)) return LABSE_OFF_SAID;
        if (Helpers.address(Helpers.Helper.LABSE) != null || !Helpers.localAllowed() || LABSE_UP.getAsBoolean()) return "";   // elsewhere, or not here: the plan's own clause says where
        if (!labseState().equals("absent")) return LABSE_STOPPED_SAID;
        return Searx.haveDocker() ? LABSE_FETCH_SAID : LABSE_NO_DOCKER_SAID;
    }

    /** How many times, two seconds apart, a start waits for the LaBSE container to answer (ten minutes); a test shortens it. */
    static volatile int LABSE_WAITS = 300;

    /** Starts the LaBSE container, fetching the model the first time; the address, or "!" and what stopped it. */
    public static String startLabse(boolean cpu) { return startLabse(cpu ? null : "all", null, line -> { }); }

    /**
     * The same on a chosen card (0.7.4): {@code gpus} is Docker's {@code --gpus} value ("all", "device=2"), null for the processor;
     * {@code card} the chosen card's index, whose compute capability picks the image, or null. A container that exists already was
     * created with whatever cards it had: when a card is chosen, {@code say} says so, and the container is left as it is.
     */
    public static String startLabse(String gpus, String card, Consumer<String> say) {
        if (!Searx.haveDocker()) return "!docker is not on this machine (https://docs.docker.com/get-docker/)";
        try {
            Files.createDirectories(labseDir());
            String tag = gpus == null ? "cpu-" + Embed.VERSION : Embed.teiTag(card);
            String state = labseState();
            if (!state.equals("absent") && (card != null || gpus == null)) say.accept("The LaBSE container (" + LABSE_CONTAINER + ") exists already and keeps the cards it was created with; it is left as it is (docker rm -f " + LABSE_CONTAINER + " lets the next install create it on the card chosen).");
            Searx.Result r;
            if (state.equals("absent")) r = Searx.runner.run(labseCommand(LABSE_PORT, tag, gpus == null ? "all" : gpus));
            else if (state.equals("stopped")) r = Searx.runner.run(List.of("docker", "start", LABSE_CONTAINER));
            else r = new Searx.Result(0, "");
            if (r.code() != 0) return "!docker: " + r.out().strip();
            String url = "http://127.0.0.1:" + LABSE_PORT;
            for (int i = 0; i < LABSE_WAITS; i++) { if (Embed.answers(url)) return url; Thread.sleep(2000); }
            return "!the container started but no embeddings call answered at " + url + " within ten minutes (docker logs " + LABSE_CONTAINER + ")";
        } catch (Exception e) { return "!" + e.getMessage(); }
    }

    /**
     * Where the LaBSE container sits, from the cards Docker gave it when it was created: "card 2" (its UUID found among nvidia-smi's
     * cards), "all cards", "the processor" when it was given none; "" when that cannot be read.
     */
    static String labseOn() {
        try {
            Searx.Result r = Searx.runner.run(List.of("docker", "inspect", "-f", "{{json .HostConfig.DeviceRequests}}", LABSE_CONTAINER));
            if (r.code() != 0) return "";
            JsonNode d = M.readTree(r.out().strip().isEmpty() ? "null" : r.out().strip());
            if (d == null || !d.isArray() || d.isEmpty()) return "the processor";
            List<String> on = new ArrayList<>();
            for (JsonNode q : d) {
                if (q.path("Count").asInt(0) == -1) return "all cards";
                for (JsonNode id : q.path("DeviceIDs")) { HelperCards.Card c = HelperCards.named(id.asText(), HelperCards.cards()); on.add(c == null ? id.asText() : c.index()); }
            }
            return on.isEmpty() ? "all cards" : (on.size() == 1 ? "card " : "cards ") + String.join(", ", on);
        } catch (Exception e) { return ""; }
    }

    public static String stopLabse() {
        try { Searx.Result r = Searx.runner.run(List.of("docker", "stop", LABSE_CONTAINER)); return r.code() == 0 ? "stopped" : "!docker: " + r.out().strip(); }
        catch (Exception e) { return "!" + e.getMessage(); }
    }

    /** The docker command that creates the LaBSE container: loopback only, the GPU when the tag is not cpu, CLS pooling as the model declares. */
    static List<String> labseCommand(int port, String tag) { return labseCommand(port, tag, "all"); }

    /** The same, on the cards {@code gpus} names for Docker ("all", or "device=2" for one card). */
    static List<String> labseCommand(int port, String tag, String gpus) {
        List<String> cmd = new ArrayList<>(List.of("docker", "run", "-d", "--name", LABSE_CONTAINER, "--restart", "unless-stopped", "-p", "127.0.0.1:" + port + ":80", "-v", labseDir().toAbsolutePath() + ":/data"));
        if (!tag.startsWith("cpu")) { cmd.add("--gpus"); cmd.add(gpus); }
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

    // ---- a scorer served by another machine ----

    /** How long a run waits for a named scorer that stops answering before it goes on without it; a test shortens it. */
    static volatile Duration SERVED_RETRY = Duration.ofMinutes(2);
    /** The pairs MetricX-24 is sent in one call: a stop or a failure costs at most these. */
    static final int SERVED_AT_ONCE = 8;
    private static final HttpClient SERVED_HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    /**
     * LaBSE or MetricX-24 at another machine's address (0.7.4): the pairs sent a few at a time, the helpers' secret with each call. A call
     * that fails is tried again, the waits growing to fifteen seconds, for {@link #SERVED_RETRY}; then the scorer gives no more scores
     * and says so in the note, and the run goes on without it. It never starts a scorer of this machine in its place. A stop in the
     * middle of a step abandons the call under way.
     */
    static final class Served implements Scorer {
        private final Helpers.Helper helper;
        private final String address;
        private volatile boolean aborted;
        private volatile CompletableFuture<HttpResponse<String>> call;
        private String stopped = "", health;
        private int scored;

        Served(Helpers.Helper helper, String address) { this.helper = helper; this.address = address; }

        @Override public synchronized List<Double> score(List<String[]> pairs) {
            List<Double> out = new ArrayList<>();
            int step = helper == Helpers.Helper.METRICX ? SERVED_AT_ONCE : Math.max(1, pairs.size());
            for (int from = 0; from < pairs.size(); from += step) {
                List<String[]> part = pairs.subList(from, Math.min(pairs.size(), from + step));
                List<Double> got = stopped.isEmpty() && !aborted ? ask(part) : null;
                for (int k = 0; k < part.size(); k++) out.add(got == null || k >= got.size() ? null : got.get(k));
            }
            return out;
        }

        /** One call's scores, tried again for a bounded time; null when the scorer stopped or the run was stopped. */
        private List<Double> ask(List<String[]> part) {
            long firstFailure = 0, wait = 2_000;
            String why = "";
            while (!aborted) {
                try {
                    List<Double> got = call(part);
                    scored += part.size();
                    if (health == null) health = Helpers.health(address);
                    return got;
                } catch (Refused r) {   // a wrong secret is not a machine that is away: no wait
                    stopped = name() + " at " + address + " refused this library's secret (" + Helpers.SECRET_NAME + "), so the translation went on without it";
                    log.warn("{}", stopped);
                    return null;
                } catch (IOException e) { why = e.getMessage(); }
                if (aborted) return null;
                long now = System.currentTimeMillis();
                if (firstFailure == 0) firstFailure = now;
                if (now - firstFailure >= SERVED_RETRY.toMillis()) {
                    stopped = name() + " at " + address + " gave no " + (scored == 0 ? "scores" : "more scores after " + Metricx.units(scored)) + ": it did not answer for " + Metricx.said(SERVED_RETRY) + " (" + why + "); the translation went on without it";
                    log.warn("{}", stopped);
                    return null;
                }
                long until = Math.min(now + wait, firstFailure + SERVED_RETRY.toMillis());
                while (!aborted && System.currentTimeMillis() < until) { try { Thread.sleep(200); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return null; } }
                wait = Math.min(15_000, wait * 2);
            }
            return null;
        }

        /** One call: the scores of the pairs, in order, null for a pair the server could not score. */
        private List<Double> call(List<String[]> part) throws IOException {
            ObjectNode body = M.createObjectNode();
            String path;
            if (helper == Helpers.Helper.METRICX) {
                path = "/score";
                ArrayNode a = body.putArray("pairs");
                for (String[] p : part) a.addObject().put("source", DocUnits.plainText(p[0])).put("hypothesis", DocUnits.plainText(p[1]));
            } else {
                path = "/v1/embeddings";
                body.put("model", "embed");
                ArrayNode a = body.putArray("input");
                for (String[] p : part) { a.add(DocUnits.plainText(p[0])); a.add(DocUnits.plainText(p[1])); }
            }
            HttpRequest req = Helpers.request(address + path).timeout(Duration.ofMinutes(10)).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)).build();
            CompletableFuture<HttpResponse<String>> f = SERVED_HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString());
            call = f;
            HttpResponse<String> r;
            try {
                while (true) {
                    if (aborted) { f.cancel(true); throw new IOException("stopped"); }
                    try { r = f.get(200, TimeUnit.MILLISECONDS); break; } catch (TimeoutException te) { /* wait on */ }
                }
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); f.cancel(true); throw new IOException("interrupted"); }
            catch (ExecutionException e) { throw new IOException(e.getCause() == null ? "the call failed" : e.getCause().getClass().getSimpleName() + (e.getCause().getMessage() == null ? "" : ": " + e.getCause().getMessage())); }
            finally { call = null; }
            if (r.statusCode() == 401) throw new Refused();
            if (r.statusCode() != 200) {   // the front's own reason when it gives one ("not loaded: no room on card 3 (0.4 GB free)"), for the note
                String why = "";
                try { why = M.readTree(r.body() == null || r.body().isBlank() ? "{}" : r.body()).path("error").asText(""); } catch (IOException ignored) { }
                throw new IOException(why.isBlank() ? "HTTP " + r.statusCode() : why);
            }
            JsonNode j = M.readTree(r.body());
            List<Double> out = new ArrayList<>();
            if (helper == Helpers.Helper.METRICX) { for (JsonNode v : j.path("scores")) out.add(v.isNumber() ? v.asDouble() : null); return out; }
            float[][] vs = new float[part.size() * 2][];
            for (JsonNode d : j.path("data")) {
                int i = d.path("index").asInt(-1);
                if (i < 0 || i >= vs.length) continue;
                JsonNode e = d.path("embedding"); float[] v = new float[e.size()]; double norm = 0;
                for (int k = 0; k < v.length; k++) { v[k] = (float) e.get(k).asDouble(); norm += v[k] * v[k]; }
                norm = Math.sqrt(norm); if (norm > 0) for (int k = 0; k < v.length; k++) v[k] /= (float) norm;
                vs[i] = v;
            }
            for (int i = 0; i < part.size(); i++) {
                float[] a = vs[2 * i], b = vs[2 * i + 1];
                if (a == null || b == null || a.length != b.length) { out.add(null); continue; }
                double dot = 0; for (int k = 0; k < a.length; k++) dot += a[k] * b[k];
                out.add(Math.round(dot * 10000) / 10000.0);
            }
            return out;
        }

        @Override public void abort() { aborted = true; CompletableFuture<HttpResponse<String>> f = call; if (f != null) f.cancel(true); }

        /** The server refused the secret (HTTP 401). */
        static final class Refused extends IOException { Refused() { super("HTTP 401"); } }
        @Override public String name() { return helper == Helpers.Helper.METRICX ? "MetricX-24" : "LaBSE"; }
        @Override public synchronized String model() {
            String h = health != null ? health : Helpers.health(address);
            String model = helper == Helpers.Helper.METRICX ? METRICX_MODEL + " in QE mode" : LABSE_MODEL;
            return model + ", at " + address + (h == null || h.isBlank() || !h.contains(" on ") ? "" : ", " + h.substring(h.lastIndexOf(" on ") + 1));   // no bracket inside the note's own: "…, at http://homebox:8217/metricx, on cuda"
        }
        @Override public boolean higherIsBetter() { return helper == Helpers.Helper.LABSE; }
        @Override public synchronized String stopped() { return stopped; }
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
    public static boolean setupAllowed() { return !off(); }

    /** The setting that leaves both scorers out, whatever else is set. */
    static final String SCORERS_NAME = "RESEARCHZOSHO_SCORERS";

    /**
     * Whether the scorers are left out, both: {@code RESEARCHZOSHO_SCORERS=off}. Then no scorer runs, here or anywhere (a LaBSE that answers
     * on this machine's port, a helpers address, a helpers server on the model's machine), none is looked for, none is set up, and a
     * plan says so.
     */
    public static boolean off() { return Config.get(SCORERS_NAME, "").strip().equalsIgnoreCase("off"); }

    /** Whether this scorer is left out: both are ({@link #off()}), or its own setting is off. */
    static boolean off(Helpers.Helper h) { return off() || Helpers.off(h); }

    /** Why a scorer is left out, as its plan, its status and its note say it: the setting that does it. */
    static String offSaid(Helpers.Helper h) { return off() ? SCORERS_NAME + "=off" : h.setting + "=off"; }

    /** The room the two scorers take on the disk under the library's home: LaBSE's model, MetricX's model and packages, and the fetch that holds the model twice. */
    public static final long NEEDS_BYTES = 16L << 30;

    /** The usable room on the disk under the library's home, or -1 when it cannot be read; a test stands it in. */
    /** Whether a LaBSE server of this machine's own is up: one answers at its port, or its container runs. Stood in by tests, which must not read the machine they run on. */
    static volatile BooleanSupplier LABSE_UP = () -> Embed.answers("http://127.0.0.1:" + LABSE_PORT) || labseState().equals("running");
    static volatile LongSupplier FREE = TranslationScores::freeBytesNow;
    public static long freeBytes() { return FREE.getAsLong(); }
    static long freeBytesNow() {
        try { Path h = Config.home(); Files.createDirectories(h); return Files.getFileStore(h).getUsableSpace(); } catch (Exception e) { return -1; }
    }

    /** What is missing of the two scorers, for a person: "LaBSE (about 1.9 GB) and MetricX-24 (…)"; "" when both are there. */
    public static String missingSaid() { return missingSaid(Chrome.ENGLISH); }

    /** The same, in a language: each scorer and its size in its words, listed as the language lists. */
    public static String missingSaid(Chrome c) {
        List<String> parts = new ArrayList<>();
        if (here(Helpers.Helper.LABSE) && labseSetupSaid().startsWith("LaBSE is not set up")) parts.add(c.english() ? "LaBSE (" + LABSE_SIZE + ")" : c.t(LABSE_NEEDS));
        if (here(Helpers.Helper.METRICX) && !metricxInstalled()) parts.add(c.english() ? "MetricX-24 (" + METRICX_SIZE + ")" : c.t(METRICX_NEEDS));
        return c.english() ? String.join(" and ", parts) : c.and(parts);
    }

    /**
     * Whether this machine is where a scorer would run, so that its being absent here is something to set up: not turned off, and not
     * served by an address or a helpers machine. A library whose scores are made on another machine misses nothing by having no
     * MetricX-24 of its own.
     */
    static boolean here(Helpers.Helper h) { return !off(h) && Helpers.address(h) == null; }

    /** The two scorers with what they take, each a key of the interface's words. */
    static final String LABSE_NEEDS = "LaBSE (" + LABSE_SIZE + ")", METRICX_NEEDS = "MetricX-24 (" + METRICX_SIZE + ")";

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
        if (!setupAllowed() || !Helpers.localAllowed()) return "";   // this machine runs no heavy helper: nothing is set up here
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
     * The MetricX script follows the release: when the build's differs from the installed one it is copied over (the script runs afresh for
     * each translation, so nothing restarts; its packages are pip's, upgraded at install, and not keyed). One line, "" when nothing was to
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
        if (off(Helpers.Helper.METRICX) || Helpers.address(Helpers.Helper.METRICX) != null || !Helpers.localAllowed()) return "";   // the plan's own clause says where, or why not
        return metricxInstalled() ? "" : METRICX_MISSING_SAID;
    }

    /** Sets the helper up: a Python environment with torch's CPU build and transformers, the script, the model fetched on the first load; "ready", or "!" and what stopped it. */
    public static String installMetricx() { return installMetricx(null, line -> { }); }

    // ---- torch's CUDA build, for MetricX-24 on a card (0.7.4) ----

    /**
     * What torch's builds take, read from the wheels themselves (their sizes and their zip directories, torch 2.15.0, 2026-11-12) and
     * measured on a machine that holds both: the processor build's whole environment takes 1.2 GB on the disk; the CUDA build on Linux
     * x86_64, as PyPI gives it (torch 514 MB, NVIDIA's CUDA libraries, triton, with transformers, sentencepiece and protobuf), is a
     * 2.8 GB download that unpacks to 5.2 GB; on Windows x86_64 PyPI's torch is the processor build, and the CUDA one comes from
     * PyTorch's own index, a 2.0 GB download that unpacks to 2.9 GB. The model is 4.7 GB, 9.2 GB when both its weight files are fetched.
     */
    static final String METRICX_MODEL_SIZE = "about 4.7 GB for the model (its weights as 32-bit floats; the repository holds them twice over, and a fetch of both is 9.2 GB)";
    static final String METRICX_CUDA_SIZE_LINUX = METRICX_MODEL_SIZE + " and, for its Python packages with torch's CUDA build (torch, NVIDIA's CUDA libraries, triton, transformers, sentencepiece), a 2.8 GB download that takes 5.2 GB on the disk";
    static final String METRICX_CUDA_SIZE_WINDOWS = METRICX_MODEL_SIZE + " and, for its Python packages with torch's CUDA build, a 2.0 GB download that takes 3.0 GB on the disk";
    static final String TORCH_SWAP_LINUX = "torch's CUDA build in place of its processor build: a 2.8 GB download, about 4 GB more on the disk";
    static final String TORCH_SWAP_WINDOWS = "torch's CUDA build in place of its processor build: a 2.0 GB download, about 2 GB more on the disk";
    /** At most, on the disk: the model with both weight files, and the packages; with the CUDA build on Linux, on Windows, and with the processor build. */
    static final long METRICX_CUDA_BYTES_LINUX = (long) (14.4 * (1L << 30)), METRICX_CUDA_BYTES_WINDOWS = (long) (12.2 * (1L << 30)), METRICX_CPU_BYTES = (long) (10.4 * (1L << 30)), TORCH_SWAP_BYTES = 4L << 30;
    /** PyTorch's own index of CUDA builds for Windows, where PyPI's torch is the processor build. */
    static final String TORCH_CUDA_INDEX_WINDOWS = "https://download.pytorch.org/whl/cu132";

    /** Whether this is Windows, and whether torch has a CUDA build for this machine (Linux or Windows, on x86_64); a test stands them in. */
    static volatile BooleanSupplier WINDOWS = () -> System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    static volatile BooleanSupplier CUDA_BUILD = () -> {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT), arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        return (os.contains("linux") || os.contains("win")) && (arch.equals("amd64") || arch.equals("x86_64"));
    };

    static boolean windows() { return WINDOWS.getAsBoolean(); }
    static boolean cudaBuildHere() { return CUDA_BUILD.getAsBoolean(); }

    /** Whether MetricX-24 gets torch's CUDA build: a card chosen (not the processor), on a machine with that build, in its own environment. */
    static boolean cudaTorchFor(HelperCards.Choice card) {
        return card != null && card.card() != null && cudaBuildHere() && Config.get("RESEARCHZOSHO_METRICX_PYTHON", "").isBlank();
    }

    static String metricxSize(boolean cuda) { return !cuda ? METRICX_SIZE : windows() ? METRICX_CUDA_SIZE_WINDOWS : METRICX_CUDA_SIZE_LINUX; }
    static long metricxBytes(boolean cuda) { return !cuda ? METRICX_CPU_BYTES : windows() ? METRICX_CUDA_BYTES_WINDOWS : METRICX_CUDA_BYTES_LINUX; }
    static String torchSwapSize() { return windows() ? TORCH_SWAP_WINDOWS : TORCH_SWAP_LINUX; }

    /** pip's line for torch: the processor build from PyTorch's CPU index, or the CUDA build (PyPI's on Linux, PyTorch's CUDA index on Windows), not kept in pip's cache. */
    static List<String> torchInstall(Path pip, boolean cuda) {
        if (!cuda) return List.of(pip.toString(), "install", "--quiet", "--upgrade", "torch", "--index-url", "https://download.pytorch.org/whl/cpu");
        List<String> cmd = new ArrayList<>(List.of(pip.toString(), "install", "--quiet", "--no-cache-dir", "--upgrade", "torch"));
        if (windows()) cmd.addAll(List.of("--index-url", TORCH_CUDA_INDEX_WINDOWS));
        return cmd;
    }

    /** Whether MetricX-24's torch sees the chosen card: {@code torch.cuda.is_available()} under that card's environment; a test stands it in. */
    static volatile BiPredicate<Path, HelperCards.Choice> TORCH_SEES = TranslationScores::torchSeesNow;

    static boolean torchSeesNow(Path py, HelperCards.Choice card) {
        try {
            List<String> cmd = new ArrayList<>(List.of(py.toString()));
            if (Config.get("RESEARCHZOSHO_METRICX_PYTHON", "").isBlank()) cmd.add("-I"); else cmd.addAll(List.of("-E", "-P"));
            cmd.addAll(List.of("-c", "import torch; print(torch.cuda.is_available())"));
            ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
            HelperCards.into(pb.environment(), card);
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return p.waitFor(5, TimeUnit.MINUTES) && out.strip().endsWith("True");
        } catch (IOException e) { return false; }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
    }

    /** How the model's first load is started, under the card's environment; a test stands it in and reads the environment. */
    static volatile HelperServer.Starter CHECK = ProcessBuilder::start;

    /**
     * Sets MetricX-24 up, as {@link #installMetricx()}, for the card {@code card} chose (null: none chosen, as a library's own machine
     * sets it up). With a card, on Linux or Windows x86_64: a fresh environment gets torch's CUDA build; one already there whose torch
     * cannot see the card gets the CUDA build in place of its own, and one that can is left alone. A CUDA build that cannot be installed
     * (no wheel for that Python, no room) is said in one line, and the processor build goes in. The model's first load runs under the
     * card's environment, as the served process does, and what it loaded on is said from its own reply. {@code say} gets each line.
     */
    static String installMetricx(HelperCards.Choice card, Consumer<String> say) { return installMetricx(card, say, false); }

    /**
     * The same; {@code noFallback} ({@code --no-cpu-fallback}, on a card): the first load never moves to the processor. A card with no
     * room for it then is said plainly, the model's files are fetched all the same and the marker written, so the install goes on and
     * the service loads it when the card has room.
     */
    static String installMetricx(HelperCards.Choice card, Consumer<String> say, boolean noFallback) {
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
                boolean cuda = cudaTorchFor(card);
                Searx.Result t = Searx.runner.run(torchInstall(pip, cuda));
                if (t.code() != 0 && cuda) {
                    say.accept("torch's CUDA build could not be installed (" + last(t.out()) + "), so MetricX-24 gets the processor build and scores on the processor.");
                    t = Searx.runner.run(torchInstall(pip, false));
                }
                if (t.code() != 0) return "!installing torch into " + venv + " failed: " + last(t.out());
                Searx.Result p = Searx.runner.run(List.of(pip.toString(), "install", "--quiet", "--upgrade", "transformers", "sentencepiece", "protobuf"));
                if (p.code() != 0) return "!installing transformers into " + venv + " failed: " + last(p.out());
            } else if (cudaTorchFor(card) && !TORCH_SEES.test(py, card)) {
                // an environment set up before, with the processor build: its torch cannot see the card, so the CUDA build goes in its place
                Path pip = py.resolveSibling(windows() ? "pip.exe" : "pip");
                say.accept("MetricX-24's torch cannot see a card (it is the processor build): " + torchSwapSize() + ".");
                Searx.Result u = Searx.runner.run(List.of(pip.toString(), "uninstall", "--yes", "--quiet", "torch"));
                Searx.Result t = u.code() == 0 ? Searx.runner.run(torchInstall(pip, true)) : u;
                if (t.code() != 0) {
                    say.accept("torch's CUDA build could not be installed (" + last(t.out()) + "), so MetricX-24 keeps the processor build and scores on the processor.");
                    Searx.Result back = Searx.runner.run(torchInstall(pip, false));
                    if (back.code() != 0) return "!torch could not be put back into " + py.getParent().getParent() + ": " + last(back.out());
                }
            }
            // the first load fetches the model and the tokenizer, under the card's environment as the served process runs; a load that
            // answers ready leaves the marker, and its reply says what it loaded on
            boolean refuse = noFallback && card != null && !card.cpu();
            ProcessBuilder pb = new ProcessBuilder(refuse ? pythonCommand(py, "--check", "--no-cpu-fallback") : pythonCommand(py, "--check")).redirectErrorStream(true);
            HelperCards.into(pb.environment(), card);
            Process pr = CHECK.start(pb);
            String out;
            try (InputStream in = pr.getInputStream()) { out = new String(in.readAllBytes(), StandardCharsets.UTF_8); }
            if (!pr.waitFor(60, TimeUnit.MINUTES)) { pr.destroyForcibly(); return "!the model did not load within an hour"; }
            String refused = refuse ? notLoadedIn(out, card) : "";
            if (!refused.isEmpty()) {   // the card had no room: said, the model is fetched, the service loads it when there is room
                Files.writeString(metricxReady(), METRICX_MODEL + "\n", StandardCharsets.UTF_8);
                say.accept("MetricX-24 is set up, and not loaded now: " + refused + ". It is not loaded on the processor (--no-cpu-fallback); the service loads it when the card has room.");
                return "ready";
            }
            if (pr.exitValue() != 0 || !out.contains("\"ready\"")) return "!the model could not be loaded: " + last(out);
            Files.writeString(metricxReady(), METRICX_MODEL + "\n", StandardCharsets.UTF_8);
            if (card != null) say.accept(loadedOn(out));
            return "ready";
        } catch (Exception e) { return "!" + e.getMessage(); }
    }

    /** Why the model did not load, from its own line ({"not_loaded": "no room", "free_gb": 0.4}): "no room on card 3 (0.4 GB free)"; "" when it loaded. */
    static String notLoadedIn(String out, HelperCards.Choice card) {
        for (String line : out.split("\\R")) {
            if (!line.contains("\"not_loaded\"")) continue;
            HelperServer.Managed m = new HelperServer.Managed("metricx", "MetricX-24", port -> List.of(), card);
            return m.why(line.strip(), 3);
        }
        return "";
    }

    /** What the model's first load says it loaded on, in a line: the card by its name, or the processor. */
    static String loadedOn(String checkOut) {
        for (String line : checkOut.split("\\R")) {
            try {
                JsonNode j = M.readTree(line.strip());
                if (!j.path("ready").asBoolean(false)) continue;
                String device = j.path("device").asText("");
                return device.equals("cuda") ? "MetricX-24 loads on the card" + (j.path("card").asText("").isEmpty() ? "" : " (" + j.path("card").asText() + ")") + "." : "MetricX-24 loads on the processor.";
            } catch (IOException | RuntimeException ignored) { }
        }
        return "MetricX-24 loads.";
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

    /** No score from MetricX for this long on one unit is a scorer that has stopped answering: its process is ended and the translation goes on without it. */
    static volatile Duration METRICX_STALL = Duration.ofMinutes(5);
    /** The wait for a fresh process's first score, which comes after the model's load as well. */
    static volatile Duration METRICX_FIRST = Duration.ofMinutes(20);

    /** MetricX-24 in QE mode, one helper process for a whole translation. */
    static Scorer metricx() { return new Metricx(() -> pythonCommand(metricxPython())); }

    /**
     * MetricX-24 in QE mode, one helper process for a whole translation: started at the first unit, so the model is loaded once, and ended
     * when the translation closes its scorers. One unit is in flight at a time, its line written and its score read before the next is
     * written, so neither pipe fills: written all at once with the replies read after, the replies filled their pipe at about 3,600 units
     * and the two sides waited on each other with no end (a translation of 11,637 units sat 8.5 hours in scoring, 2026-11-09), and a
     * process started for each revised unit loaded the 4.7 GB model again each time. The writing and the reading are a thread of the
     * process's own, so the wait for a score always ends: no score within {@link #METRICX_STALL} ({@link #METRICX_FIRST} for a fresh
     * process) ends the process and the scorer, which gives no score for the units after. A process that ends is started once more; when
     * the second ends too, or a process cannot be started, the scorer gives no more scores. Each such stop is one WARN in the log and a
     * line in the note.
     */
    static final class Metricx implements Scorer {
        /** What the thread puts for a process that ended or could not be written to, and what it takes as the end of the input: no line of the script's or of a unit's is either. */
        private static final String ENDED = "\0ended", CLOSE = "\0close";
        private final Supplier<List<String>> command;
        private volatile Process process;
        private volatile boolean aborted;
        private BlockingQueue<String> requests, replies;
        private boolean loaded;
        private int starts, scored;
        private String stopped = "";

        Metricx(Supplier<List<String>> command) { this.command = command; }

        @Override public synchronized List<Double> score(List<String[]> pairs) {
            List<Double> out = new ArrayList<>();
            for (String[] p : pairs) {
                if (!stopped.isEmpty() || aborted) { out.add(null); continue; }
                ObjectNode j = M.createObjectNode(); j.put("source", DocUnits.plainText(p[0])); j.put("hypothesis", DocUnits.plainText(p[1]));
                out.add(ask(j.toString()));
            }
            return out;
        }

        /** One unit's score; null for a unit the script could not score, and for every unit once the scorer has stopped. */
        private Double ask(String line) {
            while (stopped.isEmpty() && !aborted) {
                if (process == null && !start()) return null;
                Duration wait = loaded ? METRICX_STALL : METRICX_FIRST;
                String reply;
                try { requests.put(line); reply = replies.poll(wait.toMillis(), TimeUnit.MILLISECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); stop("the wait for a score was interrupted"); return null; }
                if (aborted) return null;   // a stop asked in the middle of the step: the process is ended, nothing more is asked
                if (reply == null) { stop("no score came for " + said(wait) + (loaded ? " on one unit" : " after its process started")); return null; }
                if (reply.equals(ENDED)) { ended(); continue; }
                loaded = true;
                JsonNode j;
                try { j = M.readTree(reply); } catch (IOException e) { return null; }
                if (!j.has("score")) return null;
                scored++;
                return j.get("score").asDouble();
            }
            return null;
        }

        /** Starts the process and the thread that writes its units and reads its replies; false when it cannot be started, the scorer stopped. */
        private boolean start() {
            Process pr;
            try { pr = new ProcessBuilder(command.get()).redirectError(ProcessBuilder.Redirect.DISCARD).start(); }
            catch (IOException e) { stop("its process could not be started: " + e.getMessage()); return false; }
            starts++;
            BlockingQueue<String> rq = new LinkedBlockingQueue<>(), rp = new LinkedBlockingQueue<>();
            Thread t = new Thread(() -> pump(pr, rq, rp), "metricx-scorer");
            t.setDaemon(true);
            t.start();
            process = pr; requests = rq; replies = rp; loaded = false;
            return true;
        }

        /** The process ended under a unit: started once more the first time, the scorer stopped the second. */
        private void ended() {
            Process pr = process; process = null; loaded = false;
            String how = "with its output closed";
            try { if (pr.waitFor(2, TimeUnit.SECONDS)) how = "with exit code " + pr.exitValue(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            end(pr);
            if (starts >= 2) { stop("its process ended twice, the second time " + how); return; }
            log.warn("MetricX-24's process ended {} after {} scored; starting it once more", how, units(scored));
        }

        /**
         * The process's own thread: takes a unit, writes it, and reads lines until the script's reply to it (a line with a score or an error;
         * anything else a library prints is passed over), which it hands back; ENDED when the process ends or cannot be written to. CLOSE
         * closes the script's input, and the script ends as at the end of a file.
         */
        private static void pump(Process pr, BlockingQueue<String> requests, BlockingQueue<String> replies) {
            try (Writer w = new OutputStreamWriter(pr.getOutputStream(), StandardCharsets.UTF_8);
                 BufferedReader r = new BufferedReader(new InputStreamReader(pr.getInputStream(), StandardCharsets.UTF_8))) {
                while (true) {
                    String line = requests.take();
                    if (line.equals(CLOSE)) return;
                    w.write(line); w.write('\n'); w.flush();
                    String reply;
                    do reply = r.readLine(); while (reply != null && !isReply(reply));
                    replies.put(reply == null ? ENDED : reply);
                    if (reply == null) return;
                }
            } catch (IOException | InterruptedException e) { replies.offer(ENDED); }
        }

        static boolean isReply(String line) {
            try { JsonNode j = M.readTree(line); return j != null && j.isObject() && (j.has("score") || j.has("error")); } catch (IOException e) { return false; }
        }

        /** The scorer gives no more scores: one WARN, the process ended. */
        private void stop(String why) {
            stopped = "MetricX-24 gave no " + (scored == 0 ? "scores" : "more scores after " + units(scored)) + ": " + why;
            log.warn("{}; the translation goes on without it", stopped);
            Process pr = process; process = null; loaded = false;
            end(pr);
        }

        static String units(int n) { return n + (n == 1 ? " unit" : " units"); }

        private static void end(Process pr) {
            if (pr == null) return;
            pr.descendants().forEach(ProcessHandle::destroyForcibly);
            pr.destroyForcibly();
        }

        static String said(Duration d) {
            long m = d.toMinutes(), s = Math.max(1, (d.toMillis() + 999) / 1000);
            return m >= 1 && d.toSeconds() % 60 == 0 ? m + (m == 1 ? " minute" : " minutes") : s + (s == 1 ? " second" : " seconds");
        }

        @Override public synchronized String stopped() { return stopped; }

        /** The script's input closed, so it ends as at the end of a file; ended by force when it has not within five seconds. */
        @Override public synchronized void close() {
            Process pr = process; process = null; loaded = false;
            if (pr == null) return;
            requests.offer(CLOSE);
            try { if (pr.waitFor(5, TimeUnit.SECONDS)) return; } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            end(pr);
        }

        /** A stop in the middle of a step: no more units are asked, the process ended; the wait for the unit under way ends with it. */
        @Override public void abort() {
            aborted = true;
            Process pr = process;
            if (pr != null) end(pr);
        }

        @Override public String name() { return "MetricX-24"; }
        @Override public String model() { return METRICX_MODEL + " in QE mode, on this machine " + (Config.get("RESEARCHZOSHO_METRICX_PYTHON", "").isBlank() ? "in its own Python environment" : "with " + Config.get("RESEARCHZOSHO_METRICX_PYTHON", "")); }
        @Override public boolean higherIsBetter() { return false; }
    }

    // ---- the distribution of a pair's paragraphs, the record ----

    public static Path file(LibraryStore store) { return store.root().resolve("catalog").resolve("translate-scores.json"); }

    static String pair(String from, String to) { return from + "→" + to; }

    /** What flags a score for a pair: the lowest tenth of LaBSE and the highest tenth of MetricX among the paragraphs measured from, once twenty are on the record. */
    public record Thresholds(String pair, int labseAccepted, int metricxAccepted, Double labseLow, Double metricxHigh) {
        public boolean ready() { return labseLow != null || metricxHigh != null; }
        /** What the thresholds are, for the note. */
        public String said() { return said(Chrome.ENGLISH); }
        /** The same in a language. */
        public String said(Chrome c) {
            if (!ready()) return c.t("no distribution yet for {0}: nothing is flagged by score until {1} paragraphs are on the record ({2} so far); the scores are shown", pair, MIN_ACCEPTED, Math.max(labseAccepted, metricxAccepted));
            return Translation.clauses(c, labseLow == null ? "" : c.t("LaBSE below {0} flags (the lowest tenth of {1} paragraphs on the record)", decimal(labseLow, c), labseAccepted),
                    metricxHigh == null ? "" : c.t("MetricX-24 above {0} flags (the highest tenth of {1} paragraphs on the record)", decimal(metricxHigh, c), metricxAccepted));
        }
    }

    /** A score as a person reads it in a sentence of their language, with the places it has (0.6312; 0,6312 in German). */
    static String decimal(double d, Chrome c) { return c.decimal(d, Math.max(0, BigDecimal.valueOf(d).stripTrailingZeros().scale())); }

    public static Thresholds thresholds(LibraryStore store, String from, String to) {
        List<Double> labse = accepted(store, from, to, "labse"), metricx = accepted(store, from, to, "metricx");
        return new Thresholds(pair(from, to), labse.size(), metricx.size(), labse.size() >= MIN_ACCEPTED ? percentile(labse, 10) : null, metricx.size() >= MIN_ACCEPTED ? percentile(metricx, 90) : null);
    }

    /**
     * The record of a pair, the numbers the thresholds and the length band are measured from: every paragraph a translation scored, flagged
     * or not, with the scores of its first pass and its length ratio, as a row of {@code units}: {@code {"job": "J-0042", "note": "<the
     * translation note>", "unit": 12, "labse": 0.81, "metricx": 3.2, "length": 3.31, "whole": true}}, so that a job's units can be taken
     * off again. Only rows marked {@code whole} are measured from. The record is a measurement and must be a fair sample: the rows of earlier
     * versions held only the units nothing flagged, a sample cut off at the threshold it set, so each run measured a tighter one from
     * translations of the same quality (about a tenth flagged per scorer on the second run, near three tenths after ten). Those rows, and
     * the plain lists {@code labse} and {@code metricx} of the versions before them, stay in the file, are listed apart and are never
     * measured from ({@link #earlier}).
     */
    static List<Double> accepted(LibraryStore store, String from, String to, String scorer) {
        List<Double> out = new ArrayList<>();
        JsonNode p = record(store).path(pair(from, to));
        for (JsonNode r : p.path("units")) if (r.path("whole").asBoolean(false) && r.path(scorer).isNumber()) out.add(r.path(scorer).asDouble());
        Collections.sort(out);
        return out;
    }

    /** The length ratios (translation to source, in characters) of the pair's paragraphs measured from, sorted. */
    static List<Double> lengths(LibraryStore store, String from, String to) { return accepted(store, from, to, "length"); }

    /** The record as it stands; an empty object when there is none or it cannot be read. */
    static JsonNode record(LibraryStore store) {
        try {
            Path f = file(store);
            if (!Files.isRegularFile(f)) return M.createObjectNode();
            JsonNode j = M.readTree(Files.readString(f, StandardCharsets.UTF_8));
            return j == null || !j.isObject() ? M.createObjectNode() : j;
        } catch (IOException e) { return M.createObjectNode(); }
    }

    /**
     * One paragraph on the record: where it came from (the job, the translation note), its number in the document, the scores of its first
     * pass, its length ratio (null when not measured), and whether it is a row of a whole sample, measured from: every row written now is.
     */
    public record Row(String job, String note, int unit, Double labse, Double metricx, Double length, boolean whole) {
        public Row(String job, String note, int unit, Double labse, Double metricx, Double length) { this(job, note, unit, labse, metricx, length, true); }
    }

    /** A sample of scores onto the record for their pair, naming no job, measured from; a null score is skipped. For a translation's own paragraphs, {@link #accept(LibraryStore, String, String, List)}. */
    public static synchronized void accept(LibraryStore store, String from, String to, List<Double> labse, List<Double> metricx) {
        List<Row> rows = new ArrayList<>();
        int n = Math.max(labse == null ? 0 : labse.size(), metricx == null ? 0 : metricx.size());
        for (int i = 0; i < n; i++) rows.add(new Row("", "", 0, labse != null && i < labse.size() ? labse.get(i) : null, metricx != null && i < metricx.size() ? metricx.get(i) : null, null));
        accept(store, from, to, rows);
    }

    /**
     * Units onto the pair's record, each a row with where it came from. The rows a job (or, with no job, a translation note) put there
     * before are replaced, so a job that resumed after its units went on the record puts them there once.
     */
    public static synchronized void accept(LibraryStore store, String from, String to, List<Row> rows) {
        if (rows.isEmpty()) return;
        try {
            Path f = file(store);
            ObjectNode j = (ObjectNode) record(store).deepCopy();
            ObjectNode p = j.has(pair(from, to)) && j.get(pair(from, to)).isObject() ? (ObjectNode) j.get(pair(from, to)) : j.putObject(pair(from, to));
            ArrayNode units = p.has("units") && p.get("units").isArray() ? (ArrayNode) p.get("units") : p.putArray("units");
            Set<String> sources = new HashSet<>(); for (Row r : rows) if (!source(r.job(), r.note()).isEmpty()) sources.add(source(r.job(), r.note()));
            for (int i = units.size() - 1; i >= 0; i--) if (sources.contains(source(units.get(i).path("job").asText(""), units.get(i).path("note").asText("")))) units.remove(i);
            for (Row r : rows) {
                ObjectNode o = units.addObject();
                if (!r.job().isEmpty()) o.put("job", r.job());
                if (!r.note().isEmpty()) o.put("note", r.note());
                if (r.unit() > 0) o.put("unit", r.unit());
                if (r.labse() != null) o.put("labse", round4(r.labse()));
                if (r.metricx() != null) o.put("metricx", round4(r.metricx()));
                if (r.length() != null) o.put("length", round4(r.length()));
                if (r.whole()) o.put("whole", true);
            }
            Files.createDirectories(f.getParent());
            AtomicWrite.text(f, M.writerWithDefaultPrettyPrinter().writeValueAsString(j) + "\n");
        } catch (IOException ignored) { /* the record only sharpens the next threshold */ }
    }

    /** What names a row's origin for its replacement and withdrawal: the job, else the note; "" for neither. */
    private static String source(String job, String note) { return !job.isEmpty() ? "job:" + job : !note.isEmpty() ? "note:" + note : ""; }

    static double round4(double d) { return Math.round(d * 10000) / 10000.0; }

    /** What a withdrawal took off: the units by pair, and the units with no job named that stay. */
    public record Withdrawn(String what, Map<String, Integer> byPair, Map<String, Integer> unnamedLeft) {
        public int total() { int n = 0; for (int v : byPair.values()) n += v; return n; }
    }

    /**
     * A job's units taken off the record: {@code what} is a job's id (J-0042) or a translation note's path, for a translation run with no
     * job; every pair it put units on loses them. Units with no job named stay, and are counted for the sentence.
     */
    public static synchronized Withdrawn withdraw(LibraryStore store, String what) throws IOException {
        String w = what == null ? "" : what.strip();
        Map<String, Integer> by = new TreeMap<>(), unnamed = new TreeMap<>();
        if (w.isEmpty()) return new Withdrawn(w, by, unnamed);
        String key = w.matches("(?i)J-\\d+") ? "job:" + w.toUpperCase(Locale.ROOT) : "note:" + Path.of(w).toAbsolutePath().normalize();
        ObjectNode j = (ObjectNode) record(store).deepCopy();
        for (var e : iterable(j)) {
            if (!e.getValue().isObject()) continue;
            JsonNode units = e.getValue().path("units");
            if (!units.isArray()) continue;
            int n = 0;
            for (int i = units.size() - 1; i >= 0; i--) if (key.equals(source(units.get(i).path("job").asText(""), units.get(i).path("note").asText("")))) { ((ArrayNode) units).remove(i); n++; }
            if (n == 0) continue;
            by.put(e.getKey(), n);
            int left = Math.max(e.getValue().path("labse").size(), e.getValue().path("metricx").size());
            for (JsonNode r : units) if (r.path("job").asText("").isEmpty() && r.path("note").asText("").isEmpty()) left++;
            if (left > 0) unnamed.put(e.getKey(), left);
        }
        if (!by.isEmpty()) AtomicWrite.text(file(store), M.writerWithDefaultPrettyPrinter().writeValueAsString(j) + "\n");
        return new Withdrawn(w, by, unnamed);
    }

    /** The units of a pair that name no job taken off the record (the plain lists of an earlier version, and rows with no origin): how many. */
    public static synchronized int withdrawUnnamed(LibraryStore store, String from, String to) throws IOException {
        ObjectNode j = (ObjectNode) record(store).deepCopy();
        JsonNode p = j.path(pair(from, to));
        if (!p.isObject()) return 0;
        int n = 0;
        for (String scorer : List.of("labse", "metricx")) { n = Math.max(n, p.path(scorer).size()); ((ObjectNode) p).remove(scorer); }
        JsonNode units = p.path("units");
        if (units.isArray()) for (int i = units.size() - 1; i >= 0; i--) if (units.get(i).path("job").asText("").isEmpty() && units.get(i).path("note").asText("").isEmpty()) { ((ArrayNode) units).remove(i); n++; }
        if (n > 0) AtomicWrite.text(file(store), M.writerWithDefaultPrettyPrinter().writeValueAsString(j) + "\n");
        return n;
    }

    /** How many units a job (its id) has on the record, by pair. */
    public static Map<String, Integer> onRecord(LibraryStore store, String jobId) {
        Map<String, Integer> out = new TreeMap<>();
        if (jobId == null || jobId.isBlank()) return out;
        for (var e : iterable(record(store))) for (JsonNode r : e.getValue().path("units")) if (r.path("job").asText("").equalsIgnoreCase(jobId.strip())) out.merge(e.getKey(), 1, Integer::sum);
        return out;
    }

    /** What the record measures from, pair by pair: the paragraphs by the job or note they came from, "" for those that name none. */
    public static Map<String, Map<String, Integer>> holdings(LibraryStore store) { return holdings(store, true); }

    /** What earlier versions put on the record and nothing is measured from, pair by pair, the same way: their plain lists name none. */
    public static Map<String, Map<String, Integer>> earlier(LibraryStore store) { return holdings(store, false); }

    private static Map<String, Map<String, Integer>> holdings(LibraryStore store, boolean whole) {
        Map<String, Map<String, Integer>> out = new TreeMap<>();
        for (var e : iterable(record(store))) {
            if (!e.getValue().isObject()) continue;
            Map<String, Integer> m = new LinkedHashMap<>();
            int plain = Math.max(e.getValue().path("labse").size(), e.getValue().path("metricx").size());
            if (plain > 0 && !whole) m.put("", plain);
            for (JsonNode r : e.getValue().path("units")) if (r.path("whole").asBoolean(false) == whole) m.merge(!r.path("job").asText("").isEmpty() ? r.path("job").asText() : r.path("note").asText(""), 1, Integer::sum);
            if (!m.isEmpty()) out.put(e.getKey(), m);
        }
        return out;
    }

    /** How many entries of a pair earlier versions put on the record, which nothing is measured from. */
    public static int earlierCount(LibraryStore store, String from, String to) {
        int n = 0; for (int v : earlier(store).getOrDefault(pair(from, to), Map.of()).values()) n += v;
        return n;
    }

    /** Why the entries of earlier versions are not measured from, in one sentence: for the note and the list. */
    public static String earlierWhy(Chrome c) {
        return c.t("Earlier versions put on the record only the units nothing flagged or a person chose as right, a sample cut off at the threshold it set, which made the threshold tighter at every run; those entries stay in the file and nothing is measured from them.");
    }

    /** The two languages of a pair as the record keys it ("ja→en"). */
    static String[] languages(String pair) { String[] p = pair.split("→", 2); return p.length == 2 ? p : new String[]{pair, ""}; }

    private static List<Map.Entry<String, JsonNode>> iterable(JsonNode j) { List<Map.Entry<String, JsonNode>> out = new ArrayList<>(); j.fields().forEachRemaining(out::add); return out; }

    /**
     * What a withdrawal did, for a person: how many units left the record of which pair, and the thresholds and the length band now; or
     * that the record held none of it.
     */
    public static String withdrawnSaid(LibraryStore store, Withdrawn w, Chrome c) {
        if (w.byPair().isEmpty()) return c.t("No unit of {0} is on the record the checks' thresholds are measured from.", w.what());
        List<String> out = new ArrayList<>();
        for (var e : w.byPair().entrySet()) {
            String[] l = languages(e.getKey());
            out.add(c.t(e.getValue() == 1 ? "{0} unit of {1} left the record of {2} into {3}." : "{0} units of {1} left the record of {2} into {3}.", e.getValue(), w.what(), c.languageName(l[0]), c.languageName(l[1])));
            Thresholds t = thresholds(store, l[0], l[1]);
            TranslationLengths.Band b = TranslationLengths.band(store, l[0], l[1], null, null);
            out.add(c.t("The record of {0} into {1} now: {2}; {3}.", c.languageName(l[0]), c.languageName(l[1]), t.said(c), TranslationLengths.shortSaid(b, c)));
            Integer left = w.unnamedLeft().get(e.getKey());
            if (left != null) out.add(c.t(left == 1 ? "{0} unit on that record names no job (it was put there by an earlier version) and stays; researchzosho translate scores withdraw --unnamed {1} {2} takes such units off." : "{0} units on that record name no job (they were put there by an earlier version) and stay; researchzosho translate scores withdraw --unnamed {1} {2} takes such units off.", left, l[0], l[1]));
        }
        return PdfReading.sentences(c, out);
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
