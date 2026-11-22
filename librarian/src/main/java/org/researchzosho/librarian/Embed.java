package org.researchzosho.librarian;

import org.researchzosho.tools.Outbound;
import org.researchzosho.Config;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * An embeddings server, run for the person through Docker: Text Embeddings Inference serving Qwen3-Embedding-0.6B, so
 * search works by meaning as well as by words. ResearchZosho does not ship a model; it fetches the model once (about 1.2 GB) into
 * the state directory, each file checked, and starts the official image with that folder (0.8.1; before, the image fetched it). The server, not the model, decides the speed: the
 * same model measured 13 chunks a second under llama.cpp and 115 under this image on the same card (2026-09-10), so a
 * library of a thousand documents re-indexes in two minutes instead of twenty. On a machine with an NVIDIA card and the
 * container runtime for it the GPU image is used. Text Embeddings Inference has no image for an AMD or Intel chip, so there
 * llama.cpp's container (ROCm or Vulkan, see {@link Gpu}) serves the same model from {@link ModelServer#EMBED_FILE} on the
 * chip ("llama-rocm", "llama-vulkan"); otherwise the CPU image, which works and is slower.
 * {@code researchzosho embed start|stop|status|test} and the setup wizard use this.
 */
public final class Embed {
    private static final HttpClient PROBE_HTTP = Outbound.client(HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(3)).build());

    public static final String CONTAINER = "researchzosho-embed";
    public static final String MODEL = "Qwen/Qwen3-Embedding-0.6B";
    public static final String IMAGE = "ghcr.io/huggingface/text-embeddings-inference";
    public static final String VERSION = "1.8";
    public static final int DEFAULT_PORT = 8215;

    private Embed() { }

    /**
     * The model's files as Text Embeddings Inference reads them, pinned (0.8.1): the revision of {@link #MODEL} on Hugging Face and the
     * sha256 of each of the files the server takes from it. Started by its model's name, the server asks huggingface.co for these at
     * every start (measured 2026-11-20: 34 to 44 packets each time). Given the folder they are in, it asks nobody, and its answers are the
     * same numbers (tried 2026-11-21: one sentence embedded both ways, no difference in any of the 1,024). So `model install` fetches
     * them itself, checked like every download, and names the folder.
     */
    static final String REVISION = "97b0c614be4d77ee51c0cef4e5f07c00f9eb65b3";
    static final Map<String, String> FILES = filesOf(
            "config.json", "b5bf1f51fc45be473a54718cef92448d90a1be001bf9b9a44b8c7f10a19feaa9",
            "config_sentence_transformers.json", "10667c72ddb772627bf1780cb7f86af8e2ae0032b8c243c731172064105c6961",
            "modules.json", "84e40c8e006c9b1d6c122e02cba9b02458120b5fb0c87b746c41e0207cf642cf",
            "tokenizer_config.json", "253153d0738ceb4c668d2eff957714dd2bea0b56de772a9fdccd96cbf517e6a0",
            "tokenizer.json", "def76fb086971c7867b829c23a26261e38d9d74e02139253b38aeb9df8b4b50a",
            "model.safetensors", "0437e45c94563b09e13cb7a64478fc406947a93cb34a7e05870fc8dcd48e23fd");   // 1.2 GB, last
    /** The folder under {@link #dir} that `model install` fetches them into: the model's name and its revision's first letters. */
    static final String FOLDER = MODEL.substring(MODEL.indexOf('/') + 1) + "-" + REVISION.substring(0, 7);

    private static Map<String, String> filesOf(String... nameAndSum) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < nameAndSum.length; i += 2) m.put(nameAndSum[i], nameAndSum[i + 1]);
        return Collections.unmodifiableMap(m);
    }

    static String fileUrl(String name) { return "https://huggingface.co/" + MODEL + "/resolve/" + REVISION + "/" + name; }

    /** Whether a folder holds the model: its configuration, its tokenizer and its weights (a link to a file that is not there is no file). */
    static boolean whole(Path folder) {
        for (String f : List.of("config.json", "tokenizer.json", "model.safetensors")) if (!Files.isRegularFile(folder.resolve(f))) return false;
        return true;
    }

    /**
     * The model's folder as the container sees it under {@code /data}: the one `model install` fetched, else the newest whole copy the
     * server fetched for itself under an earlier version (its own cache's layout: the answers stay the ones the index was built with);
     * null when there is neither.
     */
    static String modelFolder() {
        if (whole(dir().resolve(FOLDER))) return "/data/" + FOLDER;
        String cache = "models--" + MODEL.replace("/", "--");
        Path snapshots = dir().resolve(cache).resolve("snapshots");
        if (!Files.isDirectory(snapshots)) return null;
        try (Stream<Path> revisions = Files.list(snapshots)) {
            return revisions.filter(Embed::whole).max(Comparator.comparingLong((Path r) -> r.toFile().lastModified()).thenComparing(Path::getFileName))
                    .map(r -> "/data/" + cache + "/snapshots/" + r.getFileName()).orElse(null);
        } catch (IOException | RuntimeException e) { return null; }
    }

    /** The files fetched into {@link #FOLDER}, each checked against its recorded sha256; the ones already there are kept. The folder as the container sees it. */
    static String fetchModel() throws Exception {
        Path folder = dir().resolve(FOLDER);
        Files.createDirectories(folder);
        for (String name : FILES.keySet()) if (!Files.isRegularFile(folder.resolve(name))) ModelServer.fetchChecked(fileUrl(name), folder.resolve(name));
        return "/data/" + FOLDER;
    }

    /** Whether this machine can embed on a GPU: an NVIDIA card the docker daemon can hand to a container, or an AMD or Intel chip. */
    public static boolean gpu() { return !tag().startsWith("cpu"); }

    /** Where the container keeps the model it fetched: {@code <state dir>/embed}. */
    public static Path dir() { return Config.home().resolve("embed"); }

    /**
     * The image tag for this machine's card: Text Embeddings Inference builds one image per compute capability. From
     * {@code nvidia-smi --query-gpu=compute_cap} and the docker daemon's runtimes; "cpu" when either is missing.
     */
    public static String tag() {
        String t = teiTag();
        if (!t.startsWith("cpu")) return t;
        Gpu g = Gpu.kernel();   // no TEI image for an AMD or Intel chip: llama.cpp's container, the same model (read from the kernel alone; nvidia-smi is not asked)
        return g.kind() == Gpu.Kind.amd || g.kind() == Gpu.Kind.intel ? "llama-" + g.build() : t;
    }

    /** The Text Embeddings Inference tag for an NVIDIA card docker can use; "cpu-…" otherwise. */
    static String teiTag() { return teiTag(null); }

    /** The same for one card ({@code card} its index; null: the first card), whose compute capability picks the image. */
    static String teiTag(String card) {
        try {
            Searx.Result rt = Searx.runner.run(List.of("docker", "info", "--format", "{{.Runtimes}}"));
            if (rt.code() != 0 || !rt.out().contains("nvidia")) return "cpu-" + VERSION;
            Searx.Result cap = Searx.runner.run(card == null ? List.of("nvidia-smi", "--query-gpu=compute_cap", "--format=csv,noheader") : List.of("nvidia-smi", "-i", card, "--query-gpu=compute_cap", "--format=csv,noheader"));
            if (cap.code() != 0) return "cpu-" + VERSION;
            return tagFor(cap.out().strip().split("\\R")[0].strip());
        } catch (Exception e) { return "cpu-" + VERSION; }
    }

    /** The tag for a compute capability: 7.5 Turing, 8.0 Ampere, 8.6 Ampere consumer, 8.9 Ada, 9.0 Hopper; unknown → cpu. */
    static String tagFor(String computeCap) {
        return switch (computeCap) {
            case "7.5" -> "turing-" + VERSION;
            case "8.0" -> VERSION;
            case "8.6" -> "86-" + VERSION;
            case "8.9" -> "89-" + VERSION;
            case "9.0" -> "hopper-" + VERSION;
            default -> "cpu-" + VERSION;
        };
    }

    /** "running", "stopped" (exists, not running), or "absent". */
    public static String state() {
        try {
            Searx.Result r = Searx.runner.run(List.of("docker", "inspect", "-f", "{{.State.Running}}", CONTAINER));
            if (r.code() != 0) return "absent";
            return r.out().strip().equals("true") ? "running" : "stopped";
        } catch (Exception e) { return "absent"; }
    }

    /**
     * The docker command that creates the container: loopback only, restarts with the machine, the GPU when the tag is not cpu.
     * A "llama-" tag is llama.cpp's container on an AMD or Intel chip, serving the embeddings file from the models folder with the
     * same flags the model server's embeddings entry uses; the TEI image otherwise.
     */
    static List<String> runCommand(int port, String tag) { return runCommand(port, tag, null); }

    /**
     * {@code folder}: the model's folder as the container sees it ({@link #modelFolder}); Text Embeddings Inference then reads its
     * model from there, read-only, and asks nobody. Null starts it by the model's name: it fetches the model on its first start, and
     * asks huggingface.co for the files at every start.
     */
    static List<String> runCommand(int port, String tag, String folder) {
        if (tag.startsWith("llama-")) {
            String build = tag.substring("llama-".length());
            List<String> cmd = new ArrayList<>(List.of("docker", "run", "-d", "--name", CONTAINER, "--restart", "unless-stopped"));
            if (build.equals("rocm")) cmd.addAll(List.of("--device", "/dev/kfd"));
            cmd.addAll(List.of("--device", "/dev/dri", "--group-add", "video", "-p", "127.0.0.1:" + port + ":8080", "-v", ModelServer.modelsDir().toAbsolutePath() + ":/m",
                    "ghcr.io/ggml-org/llama.cpp:server-" + build, "-m", "/m/" + ModelServer.EMBED_FILE, "--host", "0.0.0.0", "--port", "8080",
                    "--embedding", "--pooling", "last", "-c", "8192", "-b", "8192", "-ub", "8192", "-ngl", "99"));
            return cmd;
        }
        List<String> cmd = new ArrayList<>(List.of("docker", "run", "-d", "--name", CONTAINER, "--restart", "unless-stopped",
                "-p", "127.0.0.1:" + port + ":80", "-v", dir().toAbsolutePath() + (folder == null ? ":/data" : ":/data:ro")));
        if (!tag.startsWith("cpu")) { cmd.add("--gpus"); cmd.add("all"); }
        cmd.add(IMAGE + ":" + tag);
        // --auto-truncate: the model declares a 32k input limit and the server refuses a smaller batch without it (the CPU
        // image exited on 16384, 2026-09-10); the client caps each text long before that anyway
        cmd.addAll(List.of("--model-id", folder == null ? MODEL : folder, "--pooling", "last-token", "--max-client-batch-size", "128",
                "--max-batch-tokens", tag.startsWith("cpu") ? "2048" : "65536", "--auto-truncate"));   // the CPU backend warms up on one core: 16384 never finished, 2048 is ready in 41 s
        return cmd;
    }

    /** The docker command lines this would run, for a test to read without docker. */
    static List<List<String>> plan(int port, String tag) { return List.of(runCommand(port, tag)); }

    /**
     * Start the embeddings server on {@code port}: create the container if it is absent (the model downloads on the
     * first start), start it if it is stopped, then wait for an embeddings call to answer. Returns the address, or
     * "!reason". {@code cpu} forces the CPU image.
     */
    public static String start(int port, boolean cpu) {
        if (!Searx.haveDocker()) return "!docker is not on this machine (https://docs.docker.com/get-docker/)";
        try {
            Files.createDirectories(dir());
            String tag = cpu ? "cpu-" + VERSION : tag();
            String state = state();
            Searx.Result r;
            if (state.equals("absent") && tag.startsWith("llama-")) {   // llama.cpp reads the file from the models folder; the TEI image fetches its own
                Path ef = ModelServer.modelsDir().resolve(ModelServer.EMBED_FILE);
                if (!Files.isRegularFile(ef)) { Files.createDirectories(ef.getParent()); ModelServer.fetchChecked(ModelServer.EMBED_URL, ef); }
            }
            String folder = null;
            if (state.equals("absent") && !tag.startsWith("llama-")) {   // the server is given its model's folder, fetched here and checked; when that fails it fetches for itself, as before
                folder = modelFolder();
                if (folder == null) try { folder = fetchModel(); } catch (Exception e) { folder = null; }
            }
            if (state.equals("absent")) r = Searx.runner.run(runCommand(port, tag, folder));
            else if (state.equals("stopped")) r = Searx.runner.run(List.of("docker", "start", CONTAINER));
            else r = new Searx.Result(0, "");
            if (r.code() != 0) return "!docker: " + r.out().strip();
            String url = "http://127.0.0.1:" + port;
            for (int i = 0; i < 300; i++) {   // the first start fetches the model: minutes on a slow line
                if (answers(url)) return url;
                Thread.sleep(2000);
            }
            return "!the container started but no embeddings call answered at " + url + " within ten minutes (docker logs " + CONTAINER + ")";
        } catch (Exception e) {
            return "!" + e.getMessage();
        }
    }

    public static String stop() {
        try {
            Searx.Result r = Searx.runner.run(List.of("docker", "stop", CONTAINER));
            return r.code() == 0 ? "stopped" : "!docker: " + r.out().strip();
        } catch (Exception e) { return "!" + e.getMessage(); }
    }

    /** Whether an embeddings server at {@code base} answers the OpenAI-style call. */
    public static boolean answers(String base) {
        if (Helpers.away(base)) return false;   // it gave no connection a moment ago
        long t0 = System.currentTimeMillis();
        try {
            HttpClient http = PROBE_HTTP;
            HttpResponse<String> r = http.send(Helpers.request(base.replaceAll("/+$", "") + "/v1/embeddings")
                    .timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"model\":\"embed\",\"input\":[\"ready\"]}")).build(), HttpResponse.BodyHandlers.ofString());
            return r.statusCode() == 200 && r.body().contains("\"embedding\"");
        } catch (Exception e) { Helpers.failed(base, e, t0); return false; }
    }
}
