package org.researchzosho.librarian;

import org.researchzosho.Config;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * An embeddings server, run for the person through Docker: Text Embeddings Inference serving Qwen3-Embedding-0.6B, so
 * search works by meaning as well as by words. ResearchZosho does not ship a model; it starts the official image, which
 * fetches the model once (about 1.2 GB) into the state directory. The server, not the model, decides the speed: the
 * same model measured 13 chunks a second under llama.cpp and 115 under this image on the same card (2026-09-10), so a
 * library of a thousand documents re-indexes in two minutes instead of twenty. On a machine with an NVIDIA card and the
 * container runtime for it the GPU image is used; otherwise the CPU image, which works and is slower.
 * {@code researchzosho embed start|stop|status|test} and the setup wizard use this.
 */
public final class Embed {

    public static final String CONTAINER = "researchzosho-embed";
    public static final String MODEL = "Qwen/Qwen3-Embedding-0.6B";
    public static final String IMAGE = "ghcr.io/huggingface/text-embeddings-inference";
    public static final String VERSION = "1.8";
    public static final int DEFAULT_PORT = 8215;

    private Embed() { }

    /** Whether this machine can run the GPU image: an NVIDIA card the docker daemon can hand to a container. */
    public static boolean gpu() { return !tag().startsWith("cpu"); }

    /** Where the container keeps the model it fetched: {@code <state dir>/embed}. */
    public static Path dir() { return Config.home().resolve("embed"); }

    /**
     * The image tag for this machine's card: Text Embeddings Inference builds one image per compute capability. From
     * {@code nvidia-smi --query-gpu=compute_cap} and the docker daemon's runtimes; "cpu" when either is missing.
     */
    public static String tag() {
        try {
            Searx.Result rt = Searx.runner.run(List.of("docker", "info", "--format", "{{.Runtimes}}"));
            if (rt.code() != 0 || !rt.out().contains("nvidia")) return "cpu-" + VERSION;
            Searx.Result cap = Searx.runner.run(List.of("nvidia-smi", "--query-gpu=compute_cap", "--format=csv,noheader"));
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

    /** The docker command that creates the container: loopback only, restarts with the machine, the GPU when the tag is not cpu. */
    static List<String> runCommand(int port, String tag) {
        List<String> cmd = new ArrayList<>(List.of("docker", "run", "-d", "--name", CONTAINER, "--restart", "unless-stopped",
                "-p", "127.0.0.1:" + port + ":80", "-v", dir().toAbsolutePath() + ":/data"));
        if (!tag.startsWith("cpu")) { cmd.add("--gpus"); cmd.add("all"); }
        cmd.add(IMAGE + ":" + tag);
        // --auto-truncate: the model declares a 32k input limit and the server refuses a smaller batch without it (the CPU
        // image exited on 16384, 2026-09-10); the client caps each text long before that anyway
        cmd.addAll(List.of("--model-id", MODEL, "--pooling", "last-token", "--max-client-batch-size", "128",
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
            if (state.equals("absent")) r = Searx.runner.run(runCommand(port, tag));
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
        try {
            HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(base.replaceAll("/+$", "") + "/v1/embeddings"))
                    .timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"model\":\"embed\",\"input\":[\"ready\"]}")).build(), HttpResponse.BodyHandlers.ofString());
            return r.statusCode() == 200 && r.body().contains("\"embedding\"");
        } catch (Exception e) { return false; }
    }
}
