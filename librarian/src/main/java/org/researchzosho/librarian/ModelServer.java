package org.researchzosho.librarian;

import org.researchzosho.tools.Outbound;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import java.io.File;
import java.nio.file.LinkOption;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.researchzosho.Config;
/**
 * The model server on this machine, on demand: a proxy (llama-swap) that starts llama.cpp on the first request and
 * stops it after a quiet spell, so the card is free in between and nothing has to be up all the time. Every program
 * points at the proxy, never at a server directly, so moving the model to another machine is one address.
 *
 * <p>{@code install} picks the measured model for what this machine has, fetches the model file and the proxy, puts
 * llama.cpp behind it, installs the proxy as a service that starts with the person's session on {@link #PORT}, and
 * points this program's drive at it. A proxy that already answers there — the other product's, or this one's from
 * before — is used as it is. On Linux the server is llama.cpp's container for the GPU the kernel names ({@link Gpu}): CUDA on an
 * NVIDIA card (Docker with the NVIDIA runtime), ROCm on an AMD Instinct card, Vulkan on a Radeon, an AMD APU or an Intel GPU
 * (Docker with the kernel's render devices passed through); on
 * macOS its native Metal build sized by unified memory, as a launchd agent; on Windows its Vulkan build, which runs on
 * any card, as a logon task.
 *
 * <p>The seams ({@link #os}, {@link #runner}, {@link #downloader}, {@link #health}) let a test drive every platform's
 * install on any host, without a card, a network, docker or a service manager.
 */
public final class ModelServer {

    private ModelServer() { }

    /**
     * A second model server on one machine, for a trial of the real install beside the machine's own: its port
     * ({@code -Dresearchzosho.model.port}) and the first part of every name it makes on Linux, the containers, the service and the
     * network ({@code -Dresearchzosho.model.name}). Unset, they are what they always were.
     */
    static final String NAMED = System.getProperty("researchzosho.model.name", "researchzosho");
    public static final int PORT = Integer.getInteger("researchzosho.model.port", 8211);
    public static final String URL = "http://127.0.0.1:" + PORT;
    public static final String UNIT = NAMED + "-model";                         // systemd --user unit (Linux)
    public static final String LABEL = "org.researchzosho.model";             // launchd agent (macOS)
    public static final String TASK = "ResearchZoshoModel";                     // scheduled task (Windows)
    public static final String CONTAINER = NAMED + "-model";
    /** The Docker network the model's and the embeddings' containers are on when they have no way out ({@link Shut}). */
    public static final String NETWORK = NAMED + "-model-shut";
    public static final String SWAP_VERSION = "255";
    public static final String LLAMA_BUILD = "b10929";
    /**
     * The chat model's idle minutes before llama-swap unloads it (0.8.0 review, round C2: the owner's "5 - 10 min"); a setting changes
     * it ({@link #MODEL_IDLE}), and so does `model install --idle-minutes`. Before 0.8.0 it was {@link #OLD_DEFAULT_IDLE_MINUTES}.
     */
    public static final int DEFAULT_IDLE_MINUTES = 10, OLD_DEFAULT_IDLE_MINUTES = 20;
    /** The setting of the chat model's idle minutes on this machine's model server. */
    public static final String MODEL_IDLE = "RESEARCHZOSHO_MODEL_IDLE_MINUTES";

    /** The chat model's idle minutes as set ({@link #MODEL_IDLE}); null when unset or not a number. */
    static Integer idleSet() {
        String v = Config.get(MODEL_IDLE);
        try { return v == null || v.isBlank() ? null : Math.max(0, Integer.parseInt(v.strip())); } catch (NumberFormatException e) { return null; }
    }

    /**
     * The chat model's idle minutes for an install whose plan says {@code m}: the setting, else the person's own (`idle_chosen=yours`, or
     * a number that was never a default), else this release's default: an install from before whose minutes were the old default moves.
     */
    static int chatIdle(Map<String, String> m) {
        Integer set = idleSet();
        if (set != null) return set;
        int had;
        try { had = Integer.parseInt(m.getOrDefault("idle_minutes", String.valueOf(DEFAULT_IDLE_MINUTES)).strip()); } catch (NumberFormatException e) { had = DEFAULT_IDLE_MINUTES; }
        if ("yours".equals(m.get("idle_chosen"))) return had;
        return had == OLD_DEFAULT_IDLE_MINUTES || had == DEFAULT_IDLE_MINUTES ? DEFAULT_IDLE_MINUTES : had;
    }

    /** The plan's line that says the idle minutes are the person's own: when they are not this release's default. */
    static String idleChosen(int idleMinutes) { return idleMinutes == DEFAULT_IDLE_MINUTES ? "" : "idle_chosen=yours\n"; }

    /** The embeddings server beside the model: the same address, model name "embed" (Embeddings' default), never idles out. */
    public static final String EMBED_NAME = "embed";
    public static final String EMBED_CONTAINER = NAMED + "-model-embed";
    public static final String EMBED_FILE = "Qwen3-Embedding-0.6B-Q8_0.gguf";
    public static final String EMBED_URL = "https://huggingface.co/Qwen/Qwen3-Embedding-0.6B-GGUF/resolve/main/" + EMBED_FILE;
    public static final String EMBED_SHA256 = "06507c7b42688469c4e7298b0a1e16deff06caf291cf0a5b278c308249c3e439";   // 639 MB

    public enum Os {
        linux, macos, windows;
        static Os detect() {
            String n = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            return n.contains("win") ? windows : n.contains("mac") || n.contains("darwin") ? macos : linux;
        }
    }

    /** A measured model row: the memory it fits, the file and its sha256 as recorded from Hugging Face, and the llama.cpp flags the guide names for it. */
    public record Row(String name, int minGb, String hf, String file, String sha256, int ctx, int parallel, String extra, Moe moe) {
        public Row(String name, int minGb, String hf, String file, String sha256, int ctx, int parallel, String extra) { this(name, minGb, hf, file, sha256, ctx, parallel, extra, null); }
        String url() { return "https://huggingface.co/" + hf + "/resolve/main/" + file; }
    }

    /**
     * A mixture-of-experts model that runs with its experts in RAM: each token uses a few billion of its weights, so the card holds the
     * attention layers and the cache ({@code baseGb}) and as many layers' experts as fit ({@code gbPerLayer} each, of {@code layers}), and
     * the rest stay in RAM, of which the machine needs {@code minRamGb}. Measured 2026-09-18/19 on a 16 GB desktop card and an 8 GB laptop
     * card: 4 GB of card read 1200 and 700 tokens a second and wrote about 30, and ten more gigabytes of card bought 40% of reading speed.
     */
    public record Moe(int minRamGb, int layers, double gbPerLayer, double baseGb, int wholeGb) { }

    // The 27B was measured on Qwen3.8-27B-Q4_K_M.gguf (2026-08-15); unsloth replaced that file with the UD quantization
    // of the same model, and only the UD file resolves now (found by a reviewer, 2026-09-12). `model check` reads every row.
    // One conversation holds the chat: its tools and instructions are about 16,000 tokens, so 16,384 held none of it (gpt-oss-20b with 32k
    // for two refused the first page the chat read, 2026-11-18). gpt-oss-20b was measured on a 16 GB card: 32k for one loads (13.1 GB for
    // the server), 64k for two does not. The 9B and Gemma 4 E4B by their cache: 32 KiB a token for the 9B (8 of its 32 layers keep one),
    // 16 KiB for E4B (4 of its own 24), so 32k adds 0.5 GB and 0.25 GB to what they used at 16k (5.9 and 3.6 GB). Gemma 4 E2B used its
    // 2 GB card at 16k already, and keeps it.
    public static final List<Row> ROWS = List.of(
        new Row("qwen3.8-27b",  24, "unsloth/Qwen3.8-27B-GGUF",    "Qwen3.8-27B-UD-Q4_K_M.gguf", "322e194ff79741c7baa497c240f677f54b201b0efab44ca8e50f122b39123482", 131072, 4, "--chat-template-kwargs '{\"reasoning_effort\":\"low\"}'"),
        new Row("qwen3.6-35b-a3b", 4, "unsloth/Qwen3.6-35B-A3B-GGUF", "Qwen3.6-35B-A3B-UD-Q4_K_M.gguf", "ac0e2c1189e055faa36eff361580e79c5bd6f8e76bffb4ce547f167d53e31a61", 65536, 2, "-b 4096 -ub 4096", new Moe(32, 40, 0.46, 4.5, 28)),
        new Row("gpt-oss-20b",  16, "unsloth/gpt-oss-20b-GGUF",    "gpt-oss-20b-F16.gguf",       "4e4f9cd88d6456e4f389e7262eca4a8d565211e2b22ece9ca7a8556168ff3c66",  32768, 1, "--chat-template-kwargs '{\"reasoning_effort\":\"low\"}'"),
        new Row("qwen3.5-9b",    8, "unsloth/Qwen3.5-9B-GGUF",     "Qwen3.5-9B-Q4_K_M.gguf",     "03b74727a860a56338e042c4420bb3f04b2fec5734175f4cb9fa853daf52b7e8",  32768, 1, "--chat-template-kwargs '{\"enable_thinking\":false}'"),
        new Row("gemma-4-e4b",   4, "unsloth/gemma-4-E4B-it-GGUF", "gemma-4-E4B-it-Q4_K_M.gguf", "85a896a047553e842f25297ee5b031d64ff30147d9c4af17b1e4b394cd1fab87",  32768, 1, ""),
        new Row("gemma-4-e2b",   2, "unsloth/gemma-4-E2B-it-GGUF", "gemma-4-E2B-it-Q4_K_M.gguf", "740185b21d22ceb83a11c3aa62ad5842ef32c70f6096d756bbee85a1e4ec34b8",  16384, 1, ""));

    /**
     * What every download must hash to: the model files above, llama-swap's assets from its published checksums, and
     * llama.cpp's pinned build, which publishes none, hashed when the build was pinned. A download that does not match
     * is not installed — the same rule as the one-line installer and the npm launcher. A test may put its own entries here.
     */
    public static final Map<String, String> sums = new ConcurrentHashMap<>(Map.ofEntries(
        Map.entry("llama-swap_255_linux_amd64.tar.gz",   "84aa0df0cf3e302a8591e39de347f64c0c7dce1c3a948df68723a82e1fb4f1d4"),
        Map.entry("llama-swap_255_linux_arm64.tar.gz",   "98686bc626e2d3df3b340b963fd4e4f4d3dd02dcd1bf31f0c777fb09e3053288"),
        Map.entry("llama-swap_255_darwin_arm64.tar.gz",  "d11b4c733da1c64ffd1b955f64b6af92a8c66a443aeeafeef2e84d3bf1fbf531"),
        Map.entry("llama-swap_255_darwin_amd64.tar.gz",  "98383f95298919cd6a73fcb11dadc78d53754bfe5d3de519fdead112f336f707"),
        Map.entry("llama-swap_255_windows_amd64.zip",    "14b40b2e11479af9a83dec3ac2bf7f82864e62099f012b171591b871b9f88aae"),
        Map.entry("llama-b10929-bin-macos-arm64.tar.gz", "d2367a6381911313944cf6e52da44b1d62a5c239f7a79fd4ddeb4001c301544b"),
        Map.entry("llama-b10929-bin-macos-x64.tar.gz",   "09621ac7f7636a6577075aba3bf40db4e22203aa2cac87c2dff7532982f89313"),
        Map.entry("llama-b10929-bin-win-vulkan-x64.zip", "0527be2bcb79797337c4c500e6c25a62e0cdb572b14ef2db1066c94706208936")));
    /**
     * The file that lets a model read pictures (llama.cpp's --mmproj), by row: mmproj-F16.gguf of the row's own repository, about
     * 1 GB, with its sha256 as recorded from Hugging Face. A row without one reads text only.
     */
    static final Map<String, String> VISION = Map.of(
        "qwen3.8-27b", "cbb841a9ee0636b2ec172f5bb8df2ea8dfeb01e90fe7c6126581d662a0b4e43e",
        "qwen3.6-35b-a3b", "8971ee4f331ff0a4c609374f32984b3d4e6dc086c0aa35f1d637fad1829e887f",
        "qwen3.5-9b",  "f70dc3509053962b0d0d3ee8a7eacebf5d60aa560cad78254ae8698516ae029f",
        "gemma-4-e4b", "ddf46c21d7078e95338cfc22306b19b276a29a5ad089023449dd54d4b6170a51",
        "gemma-4-e2b", "140be8d7849741f88c50757d529b84373ee8e27052cc2236855b537f4a8215fa");

    static Path visionFile(Row row, Path modelFile) { return modelFile.toAbsolutePath().getParent().resolve("mmproj-" + row.name() + "-F16.gguf"); }

    static { for (Row r : ROWS) sums.put(r.file(), r.sha256()); sums.put(EMBED_FILE, EMBED_SHA256); for (var v : VISION.entrySet()) sums.put("mmproj-" + v.getKey() + "-F16.gguf", v.getValue());
        for (var f : Embed.FILES.entrySet()) sums.put(Embed.FOLDER + "/" + f.getKey(), f.getValue()); }

    static String sha256(Path p) throws Exception {
        var md = MessageDigest.getInstance("SHA-256");
        try (var in = Files.newInputStream(p)) { byte[] b = new byte[1 << 16]; int n; while ((n = in.read(b)) > 0) md.update(b, 0, n); }
        return HexFormat.of().formatHex(md.digest());
    }

    /** Fetch {@code url} to {@code dest} and check it against the recorded hash; a mismatch is deleted and refused. */
    static void fetchChecked(String url, Path dest) throws Exception {
        downloader.fetch(url, dest);
        String want = sums.get(dest.getParent() == null ? "" : dest.getParent().getFileName() + "/" + dest.getFileName());   // a model that is several files in a folder of its own
        if (want == null) want = sums.get(dest.getFileName().toString());
        if (want == null) return;   // a file the person named with --file has no recorded hash; the rows and the pinned builds do
        String got = sha256(dest);
        if (!want.equalsIgnoreCase(got)) { Files.deleteIfExists(dest); throw new IOException("checksum mismatch for " + dest.getFileName() + "; refusing to install\n  expected " + want + "\n  got      " + got); }
    }

    /** The row for {@code gb} of memory for the model, or null when no measured model fits. */
    public static Row rowFor(double gb) { return rowFor(gb, ramGb()); }

    /**
     * The first row this machine can serve. A dense model needs its whole size on the card. An experts-in-RAM model needs a small card and
     * enough RAM; not on a Mac, whose memory is one pool.
     */
    public static Row rowFor(double gb, double ram) {
        for (Row r : ROWS) {
            if (r.moe() == null) { if (gb >= r.minGb()) return r; continue; }
            // Measured on Linux with CUDA (a desktop and a laptop, 2026-09-18/19). Under Vulkan, which is what Windows runs, the same model
            // with every expert in RAM loaded and answered (543 tokens a second read, 25 written, on the desktop card), and the Windows
            // Vulkan build ran a small experts model with --cpu-moe and --n-cpu-moe on an integrated AMD card. A Mac's memory is one
            // pool with no RAM to offload to, so it keeps its rows.
            if (os != Os.macos && gb >= r.minGb() && ram >= r.moe().minRamGb()) return r;
        }
        return null;
    }

    static final double VISION_GB = 1.0, EMBED_GB = 1.3;

    /** The slot an earlier release wrote for these rows ({@code ctx/parallel}): an install still on it is moved to the row's ({@link #slotRule}). */
    static final Map<String, String> SLOTS_BEFORE = Map.of("gpt-oss-20b", "32768/2", "qwen3.5-9b", "16384/1", "gemma-4-e4b", "16384/1");

    /** Where the experts go, for a card with this much free memory: all in RAM, the first N layers in RAM, or "" when everything fits or the memory is unified. */
    static String moeArgs(Row row, double freeGb) { return moeArgs(row, freeGb, 0); }

    /** {@code alsoOnCardGb}: what else this install puts on the same card — the file that reads pictures, the embeddings server. On an 8 GB laptop card they left 0.6 GB free before this counted them. */
    static String moeArgs(Row row, double freeGb, double alsoOnCardGb) {
        if (row.moe() == null || os == Os.macos) return "";
        Moe m = row.moe();
        int onCard = (int) Math.floor(Math.max(0, freeGb - m.baseGb() - alsoOnCardGb - 1.0) / m.gbPerLayer());   // a gigabyte of head room: a desktop draws on the same card
        int inRam = Math.max(0, m.layers() - onCard);
        return inRam >= m.layers() ? "--cpu-moe" : inRam == 0 ? "" : "--n-cpu-moe " + inRam;
    }

    /**
     * The cores to run on, one logical CPU for each fast physical core. With experts in RAM the CPU does real work, and the wrong count
     * is expensive: sixteen threads on eight cores wrote 7 tokens a second where eight wrote 34, and a laptop's efficiency cores halved it.
     * Linux reads the core map and each core's top frequency; macOS asks for its performance cores; elsewhere, the physical cores.
     */
    static List<Integer> fastCores() {
        try {
            if (os == Os.linux) {
                Result r = runner.run(List.of("lscpu", "-p=CPU,CORE,MAXMHZ"));
                Map<String, double[]> byCore = new LinkedHashMap<>();   // core → [first logical cpu, max MHz]
                for (String line : r.out().split("\\R")) {
                    if (line.startsWith("#") || line.isBlank()) continue;
                    String[] f = line.split(",");
                    if (f.length < 2) continue;
                    double mhz = f.length > 2 && !f[2].isBlank() ? Double.parseDouble(f[2]) : 0;
                    byCore.putIfAbsent(f[1], new double[]{Double.parseDouble(f[0]), mhz});
                }
                double top = byCore.values().stream().mapToDouble(v -> v[1]).max().orElse(0);
                List<Integer> out = new ArrayList<>();
                for (double[] v : byCore.values()) if (top == 0 || v[1] >= top * 0.85) out.add((int) v[0]);
                return out;
            }
            Result r = os == Os.macos ? runner.run(List.of("sh", "-c", "sysctl -n hw.perflevel0.physicalcpu 2>/dev/null || sysctl -n hw.physicalcpu"))
                    : runner.run(List.of("powershell", "-NoProfile", "-Command", "(Get-CimInstance Win32_Processor | Measure-Object -Property NumberOfCores -Sum).Sum"));
            int n = Integer.parseInt(r.out().strip().split("\\R")[0].strip());
            List<Integer> out = new ArrayList<>();
            for (int i = 0; i < n; i++) out.add(i);
            return out;
        } catch (Exception e) { return List.of(); }
    }

    /**
     * Memory free on the GPU now, in GB, for placing the experts: an NVIDIA card's as nvidia-smi reads it; an AMD GPU's whole
     * allotment, because what a stopped container held still counts as used for a while and the allotment is the model's;
     * the whole budget when nothing can be read.
     */
    static double gpuFreeGb() {
        if (gpu().kind() == Gpu.Kind.nvidia) {
            try {
                Result r = runner.run(List.of("nvidia-smi", "--query-gpu=memory.free", "--format=csv,noheader,nounits"));
                if (r.code() == 0) return Double.parseDouble(r.out().strip().split("\\R")[0].strip()) / 1024.0;
            } catch (Exception ignored) { }
        }
        return budgetGb();
    }

    /** The flags an experts-in-RAM row adds: where the experts go, and the threads. "" for a dense row, which runs as it always did. */
    static String moeAndThreads(Row row) {
        if (row.moe() == null) return "";
        List<Integer> cores = fastCores();
        double also = (VISION.containsKey(row.name()) ? VISION_GB : 0) + (embedTagFor(os) != null && os == Os.linux ? EMBED_GB : 0);
        String moe = moeArgs(row, os == Os.macos ? 0 : gpuFreeGb(), also);
        // a card too small for even one layer of experts is too small for the picture reader as well: it runs from RAM (slower pictures, 0.9 GB back)
        String pictures = moe.equals("--cpu-moe") && VISION.containsKey(row.name()) ? " --no-mmproj-offload" : "";
        return (moe.isEmpty() ? "" : " " + moe) + pictures + (cores.isEmpty() ? "" : " -t " + cores.size() + " -tb " + cores.size());
    }

    // ---- seams ----

    public interface Runner { Result run(List<String> cmd) throws Exception; }
    public record Result(int code, String out) { }
    /** Fetch {@code url} to {@code dest}, showing progress to the person; throws on failure. */
    public interface Downloader { void fetch(String url, Path dest) throws Exception; }

    public static Os os = Os.detect();
    /**
     * Start something that keeps running (the Windows proxy through its start script) without holding its output pipe:
     * a child that inherits our stdout would keep a runner's read open forever. Nothing is read; failure to spawn is the
     * only error.
     */
    public interface Detacher { void start(List<String> cmd) throws Exception; }
    public static Detacher detach = cmd -> {
        Process p = new ProcessBuilder(cmd).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD)
                .redirectInput(ProcessBuilder.Redirect.from(new File(os == Os.windows ? "NUL" : "/dev/null"))).start();
        p.waitFor(10, TimeUnit.SECONDS);   // the starter itself returns at once; the proxy it launched lives on
    };
    /** How long one command may take before it is killed: a docker CLI whose daemon is not running can wait forever (macOS, 2026-09-15). */
    static final int COMMAND_SECONDS = 120;
    static final Runner REAL_RUNNER = cmd -> {
        Path out = Files.createTempFile("researchzosho-cmd", ".out");
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(out.toFile()).start();
            if (!p.waitFor(COMMAND_SECONDS, TimeUnit.SECONDS)) { p.destroyForcibly(); return new Result(-1, cmd.get(0) + " gave no answer in " + COMMAND_SECONDS + " s and was stopped"); }
            return new Result(p.exitValue(), Files.readString(out, StandardCharsets.UTF_8));
        } finally { Files.deleteIfExists(out); }
    };
    public static Runner runner = REAL_RUNNER;
    /**
     * A command that takes as long as it takes and shows the person its own progress (fetching an image of several gigabytes). On the
     * real machine it writes to the terminal; where a test stands in for the machine, the test's runner answers for it.
     */
    static Result runShown(List<String> cmd) throws Exception {
        if (runner != REAL_RUNNER) return runner.run(cmd);
        Process p = new ProcessBuilder(cmd).inheritIO().start();
        return new Result(p.waitFor(), "");
    }
    public static Downloader downloader = (url, dest) -> {
        Path part = dest.resolveSibling(dest.getFileName() + ".part");
        Process p = new ProcessBuilder(os == Os.windows ? "curl.exe" : "curl", "-fL", "--progress-bar", "-o", part.toString(), url).inheritIO().start();
        if (p.waitFor() != 0) throw new IOException("download failed: " + url);
        Files.move(part, dest, StandardCopyOption.REPLACE_EXISTING);
    };
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient PROBE_HTTP = Outbound.client(HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(2)).build());

    /** What the proxy at {@code base} has loaded now: llama-swap's /running, as it answers; "" when it does not. A seam: the test suite reads no proxy of the machine. */
    public static Function<String, String> loaded = base -> {
        try { return PROBE_HTTP.send(HttpRequest.newBuilder(URI.create(base + "/running")).timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.ofString()).body(); }
        catch (Exception e) { return ""; }
    };

    /** Whether a proxy answers at {@code base}: llama-swap's /health says OK; any other server's model list will do. */
    public static Predicate<String> health = base -> {
        try {
            HttpClient c = PROBE_HTTP;
            HttpResponse<String> r = c.send(HttpRequest.newBuilder(URI.create(base + "/health")).timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() == 200) return true;
            r = c.send(HttpRequest.newBuilder(URI.create(base + "/v1/models")).timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.ofString());
            return r.statusCode() == 200;
        } catch (Exception e) { return false; }
    };

    /** The ports another program's model server usually listens on: Wyrdsekai's drive, llama.cpp, vLLM, Ollama, LM Studio. */
    static final List<Integer> SHARED_PORTS = List.of(8200, 8080, 8000, 11434, 1234);

    /** What a server serves: the names its /v1/models lists, and the file llama.cpp's /props says it loaded (a server started with --alias lists only the alias). */
    public record Served(List<String> ids, String file) { }

    /** What the server at {@code base} serves; null when nothing answers there. */
    public static Function<String, Served> servedAt = base -> {
        try {
            HttpResponse<String> r = PROBE_HTTP.send(HttpRequest.newBuilder(URI.create(base + "/v1/models")).timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) return null;
            List<String> ids = new ArrayList<>();
            for (JsonNode m : JSON.readTree(r.body()).path("data")) if (m.hasNonNull("id")) ids.add(m.get("id").asText());
            String file = null;
            try {
                HttpResponse<String> p = PROBE_HTTP.send(HttpRequest.newBuilder(URI.create(base + "/props")).timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.ofString());
                if (p.statusCode() == 200) file = JSON.readTree(p.body()).path("model_path").asText(null);
            } catch (Exception ignored) { }
            return new Served(ids, file);
        } catch (Exception e) { return null; }
    };

    /** A server another program runs on this machine that serves one of the measured models: its address, the name to ask it for, the row. */
    record Shared(String base, String model, Row row) { }

    /** The shared server with the best measured model on the usual ports (the rows are in order, best first), or null. */
    static Shared shared() {
        Shared best = null;
        for (int port : SHARED_PORTS) {
            String base = "http://127.0.0.1:" + port;
            Served s = servedAt.apply(base);
            if (s == null) continue;
            List<Shared> here = new ArrayList<>();
            for (String id : s.ids()) { Row r = rowServed(id); if (r != null) here.add(new Shared(base, id, r)); }
            if (here.isEmpty() && s.file() != null && s.ids().size() == 1) {
                Row r = rowServed(s.file().substring(Math.max(s.file().lastIndexOf('/'), s.file().lastIndexOf('\\')) + 1));
                if (r != null) here.add(new Shared(base, s.ids().get(0), r));
            }
            for (Shared h : here) if (best == null || ROWS.indexOf(h.row()) < ROWS.indexOf(best.row())) best = h;
        }
        return best;
    }

    /** The measured row a served model is, by the letters and digits of its name or file ("Qwen3.6-35B-A3B-UD-Q4_K_M.gguf", "qwen3.6:35b-a3b"); null for any other model. */
    static Row rowServed(String name) {
        String s = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        for (Row r : ROWS) if (s.contains(r.name().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", ""))) return r;
        return null;
    }

    // ---- what this machine is ----

    public static Path dir() { return Config.home().resolve("model"); }
    public static Path modelsDir() { return Path.of(System.getProperty("user.home")).resolve("models"); }
    static String home() { return System.getProperty("user.home"); }

    /** The largest NVIDIA card's memory in GB, rounded; 0 when nvidia-smi does not name a card with a memory size (a stub that prints a sentence and exits 0 is not a card). */
    static double nvidiaGb() { Gpu g = Gpu.nvidia(runner); return g == null ? 0 : g.gb(); }

    /** The GPU this Linux machine has, as the kernel tells it. */
    static Gpu gpu() { return Gpu.detect(runner); }

    /** The machine's RAM in GB, 0 when unknown. */
    static double ramGb() {
        try {
            Result r = switch (os) {
                case macos -> runner.run(List.of("sysctl", "-n", "hw.memsize"));
                case windows -> runner.run(List.of("powershell", "-NoProfile", "-Command", "(Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory"));
                case linux -> runner.run(List.of("sh", "-c", "grep MemTotal /proc/meminfo | awk '{print $2*1024}'"));
            };
            if (r.code() != 0) return 0;
            return Long.parseLong(r.out().strip().split("\\R")[0].strip()) / 1073741824.0;
        } catch (Exception e) { return 0; }
    }

    /**
     * Memory the model may take, in GB: on Linux an NVIDIA card's, or what the BIOS set aside for an AMD GPU (a discrete
     * card's own memory, an APU's carve-out), or for an Intel GPU half the RAM it shares, up to {@link #INTEL_GB}; on macOS about
     * seven tenths of unified memory, which is what the system lets the GPU wire; on Windows the NVIDIA card where there
     * is one, else half the RAM, which is what an integrated card shares.
     */
    /**
     * The most an Intel GPU is given: it shares the system's memory but not a card's speed (a 4B ran at 9 tokens a second on an
     * Arrow Lake chip against 7 on its CPU, 2026-10-20), so the pick stops at the 9B's row rather than the 27B that half the RAM would buy.
     */
    static final double INTEL_GB = 8;

    public static double budgetGb() {
        return switch (os) {
            case linux -> { Gpu g = gpu(); yield g.kind() == Gpu.Kind.intel ? Math.min(INTEL_GB, Math.floor(ramGb() * 0.5)) : g.gb(); }
            case macos -> Math.floor(ramGb() * 0.7);
            case windows -> { double n = nvidiaGb(); yield n > 0 ? n : Math.floor(ramGb() * 0.5); }
        };
    }

    /** What the server runs on here, for the offer: "the Ada" is not known, the backend is. */
    static String backend() {
        return switch (os) {
            case linux -> switch (gpu().build()) { case "rocm" -> "ROCm in Docker"; case "vulkan" -> "Vulkan in Docker"; default -> "CUDA in Docker"; };
            case macos -> "Metal";
            case windows -> "Vulkan";
        };
    }

    /** Why this machine cannot serve a model on demand, or null when it can. */
    public static String unsupported() {
        switch (os) {
            case linux -> {
                String runtimes;
                try {
                    Result d = runner.run(List.of("docker", "info", "--format", "{{.Runtimes}}"));
                    if (d.code() != 0) return "docker is not on this machine (on Linux the model runs in llama.cpp's container)";
                    runtimes = d.out();
                } catch (Exception e) { return "docker is not on this machine (on Linux the model runs in llama.cpp's container)"; }
                Gpu g = gpu();
                switch (g.kind()) {
                    case nvidia -> { if (!runtimes.contains("nvidia")) return "docker here has no NVIDIA runtime (install nvidia-container-toolkit)"; }
                    case amd, intel -> { String m = g.missingDevices(); if (m != null) return m; }
                    case none -> { return "no GPU: nvidia-smi names no NVIDIA card, and the kernel lists no AMD or Intel one under /sys/class/drm"; }
                }
            }
            case macos -> { }
            case windows -> { if (!arch().equals("amd64")) return "Windows on arm64 has no Vulkan build of llama.cpp yet"; }
        }
        double gb = budgetGb();
        if (gb <= 0) return "could not tell how much memory the model may take";
        if (rowFor(gb) == null) return "about " + (int) gb + " GB for the model; the smallest measured model wants 2 GB";
        return null;
    }

    /** One sentence for setup: what install would do here, or null when unsupported. */
    public static String offer() {
        if (health.test(URL)) return "A model proxy already answers at " + URL + "; use it for the drive.";
        if (unsupported() != null) return null;
        Row r = rowFor(budgetGb());
        return "Serve " + r.name() + " on this machine on demand (" + backend() + "): it downloads once" + sizeNote(r) + ", starts when a run needs it, and stops after "
                + DEFAULT_IDLE_MINUTES + " idle minutes.";
    }

    static String sizeNote(Row r) {
        try {
            Result h = runner.run(List.of(os == Os.windows ? "curl.exe" : "curl", "-sIL", r.url()));
            Matcher m = Pattern.compile("(?i)content-length:\\s*(\\d+)").matcher(h.out());
            long n = 0; while (m.find()) n = Long.parseLong(m.group(1));
            return n > 0 ? " (about " + Math.round(n / 1e9) + " GB)" : "";
        } catch (Exception e) { return ""; }
    }

    static String arch() {
        String a = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        return a.contains("aarch64") || a.contains("arm64") ? "arm64" : "amd64";
    }

    // ---- the files the install writes ----

    /** Everything install writes: the proxy config, the run script (Linux), and the service definition for this platform. */
    /** {@code embedScript} is the Linux run script for the embeddings container (null elsewhere); {@code embeds} says the plan carries an embeddings server. */
    public record Plan(Row row, Path modelFile, Path dir, Path unit, String configYaml, String runScript, String unitText, String embedScript, boolean embeds) {
        public Plan(Row row, Path modelFile, Path dir, Path unit, String configYaml, String runScript, String unitText) { this(row, modelFile, dir, unit, configYaml, runScript, unitText, null, false); }
    }

    /**
     * How this machine embeds beside the model: on Linux with an NVIDIA card the Text Embeddings Inference image for the
     * card (null when docker cannot use it: the CPU image is a tenth of a chunk a second, not worth a service); on Linux with
     * an AMD or Intel GPU, and on macOS and Windows, llama.cpp with {@link #EMBED_FILE} ("llama"): its container on the
     * same GPU as the model, or its own build.
     */
    static String embedTagFor(Os os) {
        if (os != Os.linux) return "llama";
        try {   // through this class's runner seam, so a test's fake machine decides (Embed.tag reads the real docker)
            Gpu.Kind kind = gpu().kind();
            if (kind == Gpu.Kind.amd || kind == Gpu.Kind.intel) return "llama";
            Result rt = runner.run(List.of("docker", "info", "--format", "{{.Runtimes}}"));
            if (rt.code() != 0 || !rt.out().contains("nvidia")) return null;
            Result cap = runner.run(List.of("nvidia-smi", "--query-gpu=compute_cap", "--format=csv,noheader"));
            if (cap.code() != 0) return null;
            String t = Embed.tagFor(cap.out().strip().split("\\R")[0].strip());
            return t.startsWith("cpu") ? null : t;
        } catch (Exception e) { return null; }
    }

    static Path unitPath(Os os) {
        return switch (os) {
            case linux -> Path.of(home(), ".config", "systemd", "user", UNIT + ".service");
            case macos -> Path.of(home(), "Library", "LaunchAgents", LABEL + ".plist");
            case windows -> dir().resolve("start.ps1");
        };
    }

    /** llama.cpp's flags for a row on a bare server, with the port the proxy assigns and quoting the platform's shell reads. */
    static String serverArgs(Row row, Path modelFile, Os os) {
        String extra = os == Os.windows ? windowsQuoted(row.extra()) : row.extra();
        String vision = Files.isRegularFile(visionFile(row, modelFile)) ? " --mmproj " + visionFile(row, modelFile) : "";
        return "-m " + modelFile + vision + " --host 127.0.0.1 --port ${PORT} --jinja -c " + row.ctx() + " --parallel " + row.parallel() + " -ngl 99 --flash-attn on" + moeAndThreads(row) + (extra.isEmpty() ? "" : " " + extra);
    }

    /** {@code --chat-template-kwargs '{"k":"v"}'} in the form a Windows command line reads: double quotes, the inner ones escaped. */
    static String windowsQuoted(String extra) {
        Matcher m = Pattern.compile("^--chat-template-kwargs '(.*)'$").matcher(extra);
        if (!m.matches()) return extra;
        return "--chat-template-kwargs \"" + m.group(1).replace("\"", "\\\"") + "\"";
    }

    /** {@code embedTag}: a TEI image tag (Linux), "llama" (macOS, Windows), or null for no embeddings server. The caller decides
     *  (install and upgrade ask {@link #embedTagFor}); the plan itself never touches the machine, so a test can build one anywhere. */
    public static Plan plan(Os os, Row row, Path modelFile, Path dir, Path unit, String gpus, int idleMinutes, boolean share, String embedTag) { return plan(os, row, modelFile, dir, unit, gpus, idleMinutes, share, embedTag, "cuda"); }

    /**
     * {@code build} is the llama.cpp container the Linux scripts start ({@link Gpu#build()}): cuda with the NVIDIA runtime's
     * {@code --gpus}; rocm with the compute interface and the render devices passed through; vulkan with the render devices.
     * {@code gpus} is "all" or one device's index: {@code --gpus} for CUDA, the backend's visible-devices variable for the others
     * (left unset for "all": a visible-devices list that is empty means no device, and the CPU serves at a tenth of the speed).
     */
    public static Plan plan(Os os, Row row, Path modelFile, Path dir, Path unit, String gpus, int idleMinutes, boolean share, String embedTag, String build) {
        return plan(os, row, modelFile, dir, unit, gpus, idleMinutes, share, embedTag, build, null, null);
    }

    /**
     * {@code shut}: on Linux, the network with no way out that the two containers are put on, each at its fixed address there, where
     * the proxy reaches it ({@link Shut}); null writes the lines every release before 0.8.1 wrote, a port of this machine's own
     * published for each. {@code embedFolder}: the folder Text Embeddings Inference reads its model from, as the container sees it
     * ({@link #embedFolder}); null starts it by the model's name, and it asks Hugging Face for the files at every start.
     */
    public static Plan plan(Os os, Row row, Path modelFile, Path dir, Path unit, String gpus, int idleMinutes, boolean share, String embedTag, String build, Shut shut, String embedFolder) {
        if (os != Os.linux) shut = null;
        String image = imageOf(build);
        String devices = switch (build) {
            case "rocm" -> "--device /dev/kfd --device /dev/dri --group-add video" + (gpus.equals("all") ? "" : " -e HIP_VISIBLE_DEVICES=" + gpus);
            case "vulkan" -> "--device /dev/dri --group-add video" + (gpus.equals("all") ? "" : " -e GGML_VK_VISIBLE_DEVICES=" + gpus);
            default -> "--gpus " + (gpus.equals("all") ? "all" : "'\"device=" + gpus + "\"'");
        };
        String cmd = switch (os) {
            case linux -> dir.resolve("run.sh") + " ${PORT}";
            case macos -> dir.resolve("llama").resolve("llama-server") + " " + serverArgs(row, modelFile, os);
            case windows -> dir.resolve("llama").resolve("llama-server.exe") + " " + serverArgs(row, modelFile, os);
        };
        String cfg = "# ResearchZosho: the model server on demand. llama-swap listens on " + PORT + "; the server starts on the first request\n"
                + "# and stops after ttl seconds idle. `researchzosho model status` reads it.\n"
                + "healthCheckTimeout: 600\n"
                + "startPort: " + (PORT == 8211 ? 10001 : PORT + 1) + "\n"
                + "logLevel: info\n"
                + "models:\n"
                + "  \"" + row.name() + "\":\n"
                + "    cmd: " + cmd + "\n"
                + (os == Os.linux ? "    cmdStop: docker stop " + CONTAINER + "\n" : "")
                + "    proxy: http://127.0.0.1:${PORT}\n"
                + "    ttl: " + (idleMinutes * 60) + "\n"
                + "    aliases: [\"local-model\", \"" + row.file() + "\", \"/m/" + row.file() + "\"]\n";
        String embedScript = null;
        if (embedTag != null) {
            // the embeddings server: its own group with swapping off, so it lives beside the model instead of evicting it (and is not
            // evicted by it); unloaded after its own idle time (0.8.0 review, round C2: five minutes), held loaded while work runs
            String embedCmd = os == Os.linux ? dir.resolve("run-embed.sh") + " ${PORT}"
                    : dir.resolve("llama").resolve(os == Os.windows ? "llama-server.exe" : "llama-server") + " -m " + modelFile.getParent().resolve(EMBED_FILE)
                      + " --host 127.0.0.1 --port ${PORT} --embedding --pooling last -c 8192 -b 8192 -ub 8192 -ngl 99";
            cfg += "  \"" + EMBED_NAME + "\":\n"
                + "    cmd: " + embedCmd + "\n"
                + (os == Os.linux ? "    cmdStop: docker stop " + EMBED_CONTAINER + "\n" : "")
                + "    proxy: http://127.0.0.1:${PORT}\n"
                + "    checkEndpoint: /health\n"
                + "    ttl: " + ModelsAwake.embedIdleMinutes() * 60 + "\n"   // unloaded when idle too (C2), staying in its group, which the model does not evict
                + "    aliases: [\"" + Embed.MODEL + "\", \"text-embedding\"]\n"
                + "groups:\n"
                + "  \"embedding\":\n"
                + "    swap: false\n"
                + "    exclusive: false\n"
                + "    persistent: true\n"
                + "    members: [\"" + EMBED_NAME + "\"]\n";
            if (os == Os.linux) embedScript = embedTag.equals("llama") ? "#!/bin/sh\n"
                + "# The embeddings server (" + Embed.MODEL + " in llama.cpp, on the same GPU as the model), started by llama-swap on demand beside the model; $1 is the port it assigned.\n"
                + "exec docker run --rm --name " + EMBED_CONTAINER + " " + devices + " -p 127.0.0.1:$1:8080 -v " + modelFile.getParent() + ":/m ghcr.io/ggml-org/llama.cpp:" + image + " \\\n"
                + "  -m /m/" + EMBED_FILE + " --host 0.0.0.0 --port 8080 --embedding --pooling last -c 8192 -b 8192 -ub 8192 -ngl 99\n"
                : "#!/bin/sh\n"
                + "# The embeddings server (Text Embeddings Inference, " + Embed.MODEL + "), started by llama-swap on demand beside the model; $1 is the port it assigned.\n"
                + "# The model downloads into " + Embed.dir() + " on the first start.\n"
                + "exec docker run --rm --name " + EMBED_CONTAINER + " --gpus " + (gpus.equals("all") ? "all" : "'\"device=" + gpus + "\"'")
                + " -p 127.0.0.1:$1:80 -v " + Embed.dir().toAbsolutePath() + ":/data " + Embed.IMAGE + ":" + embedTag + " \\\n"
                + "  --model-id " + Embed.MODEL + " --pooling last-token --max-client-batch-size 128 --max-batch-tokens 65536 --auto-truncate\n";
            if (embedScript != null && embedFolder != null) embedScript = byFolder(embedScript, embedFolder);
        }
        String run = os != Os.linux ? null : "#!/bin/sh\n"
                + "# " + row.name() + " in llama.cpp, started by llama-swap on demand; $1 is the port it assigned. The flags are the measured ones.\n"
                + "exec docker run --rm --name " + CONTAINER + " " + devices
                + (row.moe() == null || fastCores().isEmpty() ? "" : " --cpuset-cpus=" + String.join(",", fastCores().stream().map(String::valueOf).toList()))
                + " -p 127.0.0.1:$1:8080 -v " + modelFile.getParent() + ":/m ghcr.io/ggml-org/llama.cpp:" + image + " \\\n"
                + "  -m /m/" + modelFile.getFileName() + (Files.isRegularFile(visionFile(row, modelFile)) ? " --mmproj /m/" + visionFile(row, modelFile).getFileName() : "") + " --host 0.0.0.0 --port 8080 --jinja -c " + row.ctx() + " --parallel " + row.parallel() + " -ngl 99 --flash-attn on" + moeAndThreads(row)
                + (row.extra().isEmpty() ? "" : " \\\n  " + row.extra()) + "\n";
        String listen = (share ? "0.0.0.0" : "127.0.0.1") + ":" + PORT;
        Path swap = dir.resolve(os == Os.windows ? "llama-swap.exe" : "llama-swap");
        String unitText = switch (os) {
            case linux -> "[Unit]\nDescription=ResearchZosho: the model server on demand (llama-swap)\nAfter=network-online.target docker.service\n\n"
                    + "[Service]\nExecStart=" + swap + " --config " + dir.resolve("config.yaml") + " --listen " + listen + "\n"
                    + "Restart=always\nRestartSec=5\n\n[Install]\nWantedBy=default.target\n";
            case macos -> "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n"
                    + "<plist version=\"1.0\"><dict>\n  <key>Label</key><string>" + LABEL + "</string>\n"
                    + "  <key>ProgramArguments</key><array>\n    <string>" + swap + "</string>\n    <string>--config</string>\n    <string>" + dir.resolve("config.yaml") + "</string>\n"
                    + "    <string>--listen</string>\n    <string>" + listen + "</string>\n  </array>\n"
                    + "  <key>RunAtLoad</key><true/>\n  <key>KeepAlive</key><true/>\n"
                    + "  <key>StandardOutPath</key><string>" + dir.resolve("llama-swap.log") + "</string>\n  <key>StandardErrorPath</key><string>" + dir.resolve("llama-swap.log") + "</string>\n"
                    + "</dict></plist>\n";
            // Start-Process joins its argument list with spaces and quotes nothing: the config's path goes in double quotes of its own
            // the number of the llama-swap it starts is kept, so the program stops only that one: never another by its name (0.8.0)
            case windows -> "# ResearchZosho: start the model proxy (llama-swap) in the background; a logon task runs this, and `researchzosho model install` ran it once now\n"
                    + "$kept = '" + dir.resolve(SWAP_PID) + "'\n"
                    + "$ours = $null\n"
                    + "if (Test-Path $kept) { $ours = Get-Process -Id ([int](Get-Content $kept -TotalCount 1)) -ErrorAction SilentlyContinue }\n"
                    + "if (-not ($ours -and $ours.ProcessName -eq 'llama-swap')) {\n"
                    + "  $started = Start-Process -FilePath '" + swap + "' -ArgumentList '--config','\"" + dir.resolve("config.yaml") + "\"','--listen','" + listen + "' -WindowStyle Hidden -RedirectStandardOutput '" + dir.resolve("llama-swap.log") + "' -RedirectStandardError '" + dir.resolve("llama-swap.err") + "' -PassThru\n"
                    + "  Set-Content -Path $kept -Value $started.Id\n}\n";
        };
        if (shut != null) {   // written the earlier way above, then turned: one text decides what "no way out" changes, for a new install and for one that changes over
            Map<String, String> turned = toShut(dir, filesOf(new Plan(row, modelFile, dir, unit, cfg, run, unitText, embedScript, embedTag != null)), shut);
            cfg = turned.get("config.yaml"); run = turned.get("run.sh"); embedScript = turned.get("run-embed.sh");
        }
        return new Plan(row, modelFile, dir, unit, cfg, run, unitText, embedScript, embedTag != null);
    }

    // ---- no way out (0.8.1) ----

    /**
     * The setting that chooses whether the model server `model install` sets up on Linux may reach the network: unset or closed, it is
     * set up with no way out; open, the way every release before 0.8.1 set it up, for a machine whose model server has to reach one.
     */
    public static final String MODEL_NETWORK = "RESEARCHZOSHO_MODEL_NETWORK";

    static boolean openAsked() {
        String v = Config.get(MODEL_NETWORK);
        return v != null && List.of("open", "on", "yes", "true").contains(v.strip().toLowerCase(Locale.ROOT));
    }

    static String imageOf(String build) { return switch (build) { case "rocm" -> "server-rocm"; case "vulkan" -> "server-vulkan"; default -> "server-cuda"; }; }

    /**
     * The model server with no way out (0.8.1). On Linux the model and the embeddings server are containers, and a container on a Docker
     * network made with {@code --internal} has no route to anything but the machine it runs on: no gateway, no name service for outside
     * names, and Docker drops what is sent across that network's edge. The machine itself still reaches the container at the
     * container's own address, so the proxy (llama-swap, a process of the person's) passes requests to that address in place of a
     * published port. The address has to be known before the container starts, so the network has a range of its own and each of
     * the two containers a fixed address in it. The containers also lose the right to write raw packets ({@code --cap-drop NET_RAW}),
     * which is the one way a program could address a packet past its routes.
     *
     * <p>What this gives: nothing a model file, a chat template or a fault in the model server does can send anything off the
     * machine. What it does not: the container still reaches the machine itself, so a program on the machine that listens on every
     * address and passes things on (a proxy, a name server) would be a way out.
     */
    public record Shut(String subnet, String modelAt, String embedAt) {
        /** The range's second and third addresses; the first is the machine's own on that network. Null when the range is not one. */
        static Shut of(String subnet) {
            long[] r = range(subnet);
            return r == null || r[1] - r[0] < 7 ? null : new Shut(subnet, dotted(r[0] + 2), dotted(r[0] + 3));
        }

        /**
         * What a start script says and does before its {@code docker run}: the arrangement in words, and the network made again when
         * it is gone. A {@code docker network prune} removes a network no container is on, which is this one whenever the model is
         * not loaded.
         */
        String said(String at) {
            return "# No way out: it runs on a Docker network that has no route to anything but this machine (" + NETWORK + ", " + subnet + "),\n"
                 + "# may not write raw packets, and answers this machine at " + at + " ($1 is not used). When the network was removed it is made again here.\n"
                 + "docker network inspect " + NETWORK + " >/dev/null 2>&1 || docker network create --internal --subnet " + subnet + " " + NETWORK + " >/dev/null 2>&1\n";
        }

        String flags(String at) { return " --network " + NETWORK + " --ip " + at + " --cap-drop NET_RAW"; }
    }

    /**
     * The ranges tried for the network, in this order: sixteen addresses each, private, and away from the ones Docker hands out by
     * itself (172.17 to 172.31, 192.168), home routers, and the usual virtual machines and tunnels. The first that no route of the
     * machine overlaps and that Docker accepts is taken.
     */
    static final List<String> RANGES = List.of("172.16.211.0/28", "10.213.71.0/28", "172.16.93.0/28", "10.157.29.0/28", "192.168.251.0/28", "172.31.251.0/28");

    private static final Pattern IPV4 = Pattern.compile("(?<![\\d.])(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})(?:/(\\d{1,2}))?(?![\\d.])");

    /** An IPv4 range ("172.16.211.0/28", or one address) as its first and last address; null when it is not one. */
    static long[] range(String cidr) {
        Matcher m = IPV4.matcher(cidr == null ? "" : cidr.strip());
        if (!m.matches()) return null;
        long a = 0;
        for (int i = 1; i <= 4; i++) { int part = Integer.parseInt(m.group(i)); if (part > 255) return null; a = a << 8 | part; }
        int bits = m.group(5) == null ? 32 : Integer.parseInt(m.group(5));
        if (bits > 32) return null;
        long size = 1L << (32 - bits), first = a & ~(size - 1);
        return new long[]{first, first + size - 1};
    }

    static String dotted(long a) { return (a >> 24 & 255) + "." + (a >> 16 & 255) + "." + (a >> 8 & 255) + "." + (a & 255); }

    /**
     * Every range and address the machine's routes name ({@code ip -4 route show table all}: the networks it is on, its own
     * addresses, what a tunnel or a virtual machine routes, every table). The default route names none.
     */
    static List<long[]> routed(String routes) {
        List<long[]> used = new ArrayList<>();
        Matcher m = IPV4.matcher(routes);
        while (m.find()) {
            long[] r = range(m.group());
            if (r != null && !"0".equals(m.group(5))) used.add(r);
        }
        return used;
    }

    static boolean overlaps(long[] r, List<long[]> used) {
        for (long[] u : used) if (r[0] <= u[1] && u[0] <= r[1]) return true;
        return false;
    }

    /** The network as made, or why it was not: one of the two is null. */
    record Made(Shut shut, String why) { }

    /**
     * The network with no way out, made or found. One of this program's own that is already there is used as it is. Else the
     * machine's routes are read, and the first of {@link #RANGES} that none of them overlaps is given to Docker, which refuses a range
     * one of its own networks holds; the next is tried then. Nothing is made when no range is free. Once the network is there the
     * machine has to have a route to it: with rootless Docker, or Docker inside a virtual machine, it has none, a container's address
     * is out of the machine's reach, and the model would be waited for in vain. The network is taken away again then.
     */
    static Made makeNetwork() {
        Chrome c = Chrome.now();
        try {
            Result have = runner.run(List.of("docker", "network", "inspect", NETWORK, "--format", "{{.Internal}} {{range .IPAM.Config}}{{.Subnet}}{{end}}"));
            if (have.code() == 0) {
                Matcher m = Pattern.compile("(?m)^(true|false) (\\S+)\\s*$").matcher(have.out().strip());
                Shut there = m.find() && m.group(1).equals("true") ? Shut.of(m.group(2)) : null;
                return there != null ? routedTo(there) : new Made(null, c.t("a Docker network named {0} is already there and is not one this program made", NETWORK));
            }
            Result routes = runner.run(List.of("ip", "-4", "route", "show", "table", "all"));
            if (routes.code() != 0 || routes.out().isBlank()) return new Made(null, c.t("the routes of this machine could not be read, so no free address range could be chosen for its network"));
            List<long[]> used = routed(routes.out());
            String refused = "";
            for (String candidate : RANGES) {
                if (overlaps(range(candidate), used)) continue;
                Result made = runner.run(List.of("docker", "network", "create", "--internal", "--subnet", candidate, NETWORK));
                if (made.code() == 0) return routedTo(Shut.of(candidate));
                refused = firstLine(made.out());
            }
            return new Made(null, refused.isEmpty() ? c.t("no address range was free for its network") : c.t("Docker did not make its network ({0})", refused));
        } catch (Exception e) { return new Made(null, c.t("Docker did not make its network ({0})", String.valueOf(e.getMessage()))); }
    }

    /** The network, when the machine has a route to its range; else it is removed and the reason given. */
    private static Made routedTo(Shut shut) throws Exception {
        Result routes = runner.run(List.of("ip", "-4", "route", "show", "table", "all"));
        if (routes.code() == 0 && Pattern.compile("(?m)^" + Pattern.quote(shut.subnet()) + "\\s").matcher(routes.out()).find()) return new Made(shut, null);
        runner.run(List.of("docker", "network", "rm", NETWORK));
        return new Made(null, Chrome.now().t("this machine has no route to its own Docker networks (rootless Docker, or Docker in a virtual machine), so it could not reach a container there"));
    }

    static String firstLine(String text) {
        String t = text == null ? "" : text.strip();
        int nl = t.indexOf('\n');
        t = nl < 0 ? t : t.substring(0, nl).strip();
        return t.length() > 200 ? t.substring(0, 200) + "…" : t;
    }

    /** The network removed by its name and the two containers stopped by theirs: what a step back, a change to the open way and an uninstall leave behind otherwise. */
    static void removeNetwork() {
        try {
            runner.run(List.of("docker", "stop", CONTAINER));
            runner.run(List.of("docker", "stop", EMBED_CONTAINER));
            if (Embed.recordedSubnet() != null && !Embed.state().equals("absent")) return;   // the embeddings server `embed start` made is on it too, and starts there again
            for (int i = 0; i < 5; i++) {   // a container that is still going away holds the network for a moment
                Result r = runner.run(List.of("docker", "network", "rm", NETWORK));
                if (r.code() == 0 || !r.out().contains("active endpoints")) break;
                Thread.sleep(1000);
            }
        } catch (Exception ignored) { }
    }

    /** What the proof asks of the model server through the proxy. The real one asks over HTTP; a test gives its own. */
    public interface Asker {
        /** One short completion from {@code model} at {@code base}, waited for up to {@code seconds}: "" when the model wrote something, else "!" and why. */
        String complete(String base, String model, int seconds);
        /** One embedding from the embeddings server at {@code base}: "" when a vector came back, else "!" and why. */
        String embed(String base, int seconds);
        /** Everything the proxy at {@code base} holds loaded is let go, so that what is asked next has the card to itself. */
        void rest(String base);
    }

    static final Asker REAL_ASKER = new Asker() {
        @Override public String complete(String base, String model, int seconds) {
            try {
                var body = JSON.createObjectNode();
                body.put("model", model); body.put("max_tokens", 64);
                body.putArray("messages").addObject().put("role", "user").put("content", "Reply with the single word: ready");
                HttpResponse<String> r = PROBE_HTTP.send(HttpRequest.newBuilder(URI.create(base + "/v1/chat/completions")).timeout(Duration.ofSeconds(seconds))
                        .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(), HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() != 200) return "!" + Chrome.now().t("it returned HTTP {0}: {1}", r.statusCode(), errorSaid(r.body()));
                JsonNode j = JSON.readTree(r.body()), message = j.path("choices").path(0).path("message");
                boolean wrote = !message.path("content").asText("").isBlank() || !message.path("reasoning_content").asText("").isBlank() || j.path("usage").path("completion_tokens").asInt(0) > 0;
                return wrote ? "" : "!" + Chrome.now().t("its reply held no words: {0}", firstLine(r.body()));
            } catch (Exception e) { return "!" + notAnswered(e, seconds); }
        }
        @Override public String embed(String base, int seconds) {
            try {
                HttpResponse<String> r = PROBE_HTTP.send(HttpRequest.newBuilder(URI.create(base + "/v1/embeddings")).timeout(Duration.ofSeconds(seconds))
                        .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{\"model\":\"" + EMBED_NAME + "\",\"input\":[\"ready\"]}")).build(), HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() != 200) return "!" + Chrome.now().t("it returned HTTP {0}: {1}", r.statusCode(), errorSaid(r.body()));
                return JSON.readTree(r.body()).path("data").path(0).path("embedding").size() > 0 ? "" : "!" + Chrome.now().t("its reply held no numbers: {0}", firstLine(r.body()));
            } catch (Exception e) { return "!" + notAnswered(e, seconds); }
        }
        @Override public void rest(String base) {
            try { PROBE_HTTP.send(HttpRequest.newBuilder(URI.create(base + "/unload")).timeout(Duration.ofSeconds(60)).GET().build(), HttpResponse.BodyHandlers.discarding()); }
            catch (Exception ignored) { }
        }
    };
    public static Asker asker = REAL_ASKER;

    /** A server's error in its own words: the message of an OpenAI-style error body, else the body's first line. */
    static String errorSaid(String body) {
        try { String m = JSON.readTree(body).path("error").path("message").asText(""); if (!m.isBlank()) return firstLine(m); } catch (Exception ignored) { }
        return firstLine(body);
    }

    private static String notAnswered(Exception e, int seconds) {
        return e instanceof HttpTimeoutException ? Chrome.now().t("no answer in {0} seconds", seconds) : e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    /** How long the first answer is waited for: the proxy itself gives a model ten minutes to load. */
    static final int PROOF_SECONDS = 660;
    /** The outside address of the proof: the site the model's files came from. It is asked by its name once, from an ordinary container, and by the number that gave from inside. */
    static final String OUTSIDE = "https://huggingface.co/";

    /**
     * What the proof found: {@code failed} null when the arrangement holds, else why it does not, as words that follow "because";
     * {@code names}: Docker looked an outside name up for the container; {@code silent}: which of the two servers gave no answer
     * ("model", "embed"), or "" when both answered and the fault was found elsewhere.
     */
    record Proof(String failed, boolean names, String silent) {
        static Proof holds(boolean names) { return new Proof(null, names, ""); }
        static Proof fails(String why, String silent) { return new Proof(why, false, silent); }
    }

    /** One request from inside a container to the outside address by its number: null when it could not connect, else why the arrangement does not hold. */
    private static String reaches(String container, String number) throws Exception {
        Chrome c = Chrome.now();
        Result in = runner.run(List.of("docker", "exec", container, "curl", "-sS", "-o", "/dev/null", "-m", "8", "-k", "-A", "researchzosho.org", "https://" + number + "/"));
        if (in.code() == 7 || in.code() == 28) return null;   // curl's own word: it could not connect, or nothing came back in time
        if (in.code() < 0 || in.code() >= 125) return c.t("the check could not be run inside the container {0} ({1})", container, firstLine(in.out()));
        return c.t("an outside address answered from inside the container {0}", container);
    }

    /**
     * The arrangement, proven by what it does. First the control: a container on an ordinary network asks the outside address, which
     * has to answer there, or a silence from inside would mean nothing. Then each server alone, so that neither wants the card's
     * memory while the other warms up: through the proxy the embeddings server returns a vector, and the same outside request, by the
     * number the control reached, is made inside its container and has to fail to connect; it is let go; the model writes a
     * completion, and the request is made inside its container. Last, whether Docker looks an outside name up for the container.
     */
    static Proof prove(String model, boolean embeds, String build) throws Exception {
        Chrome c = Chrome.now();
        if (runner != REAL_RUNNER && asker == REAL_ASKER) throw new IllegalStateException("a test that stands in for the machine has to stand in for the model's answers too (ModelServer.asker)");
        String image = "ghcr.io/ggml-org/llama.cpp:" + imageOf(build);
        Result control = runner.run(List.of("docker", "run", "--rm", "--entrypoint", "curl", image, "-4", "-sS", "-o", "/dev/null", "-m", "15", "-A", "researchzosho.org", "-w", "%{remote_ip}", OUTSIDE));
        Matcher at = IPV4.matcher(control.out());
        String number = null;
        while (at.find()) if (at.group(5) == null) number = at.group();
        if (control.code() != 0 || number == null)
            return Proof.fails(c.t("no container on this machine reached an outside address just now, so it could not be tried ({0})", firstLine(control.out())), "");
        asker.rest(URL);   // whatever the proxy still holds from before is let go: each server is asked with the card to itself
        if (embeds) {
            String e = asker.embed(URL, 300);
            if (e.startsWith("!")) return Proof.fails(c.t("the embeddings server did not answer through it ({0})", e.substring(1)), "embed");
            String out = reaches(EMBED_CONTAINER, number);
            if (out != null) return Proof.fails(out, "");
            asker.rest(URL);
        }
        String a = asker.complete(URL, model, PROOF_SECONDS);
        if (a.startsWith("!")) return Proof.fails(c.t("the model did not answer through it ({0})", a.substring(1)), "model");
        String out = reaches(CONTAINER, number);
        if (out != null) return Proof.fails(out, "");
        Result name = runner.run(List.of("docker", "exec", CONTAINER, "getent", "hosts", "huggingface.co"));
        return Proof.holds(name.code() == 0 && name.out().contains("huggingface.co"));
    }

    /** The files that decide which way an install is set up, by their names in the model folder; a script the plan has not is left out. */
    static Map<String, String> filesOf(Plan p) {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("config.yaml", p.configYaml());
        if (p.runScript() != null) files.put("run.sh", p.runScript());
        if (p.embedScript() != null) files.put("run-embed.sh", p.embedScript());
        return files;
    }

    /** The port a start script's server listens on inside its container: Text Embeddings Inference on 80, llama.cpp on 8080. */
    static int insidePort(String script) { return script.contains(Embed.IMAGE + ":") ? 80 : 8080; }

    private static final String PROXY_OPEN = "    proxy: http://127.0.0.1:${PORT}\n";

    /** Where the block of the model whose command is {@code script} ends in a config: before the next model's name, or the groups, or the end. */
    private static int blockEnd(String cfg, int from) {
        Matcher next = Pattern.compile("(?m)^(?:  \"[^\"\\n]+\":|\\S.*)$").matcher(cfg);
        return next.find(from) ? next.start() : cfg.length();
    }

    /**
     * The files of an install set up the earlier way, turned into the ones with no way out. Each start script loses its published port
     * and gains the network, its fixed address there and the right it gives up, with the lines that say so and make the network again
     * when it is gone; the config stops handing the scripts a port and passes requests to the addresses. Everything else in them stays
     * as it was written, by whichever release or by the person. Null when a file is not of the shape this program writes.
     */
    static Map<String, String> toShut(Path dir, Map<String, String> files, Shut shut) {
        String cfg = files.get("config.yaml");
        if (cfg == null || !files.containsKey("run.sh")) return null;
        Map<String, String> scripts = new LinkedHashMap<>();
        for (String name : List.of("run.sh", "run-embed.sh")) {
            String script = files.get(name), cmd = "    cmd: " + dir.resolve(name);
            int at = cfg.indexOf(cmd + " ${PORT}\n");
            if (script == null || (at < 0 && name.equals("run-embed.sh"))) continue;   // no embeddings server in this install
            if (at < 0) return null;
            String address = name.equals("run.sh") ? shut.modelAt() : shut.embedAt(), published = " -p 127.0.0.1:$1:" + insidePort(script) + " ";
            Matcher exec = Pattern.compile("(?m)^exec docker run ").matcher(script);
            int end = blockEnd(cfg, at + cmd.length());
            String block = cfg.substring(at, end);
            if (script.split(Pattern.quote(published), -1).length != 2 || !exec.find() || block.split(Pattern.quote(PROXY_OPEN), -1).length != 2) return null;
            scripts.put(name, script.substring(0, exec.start()) + shut.said(address + ":" + insidePort(script)) + script.substring(exec.start()).replace(published, shut.flags(address) + " "));
            cfg = cfg.substring(0, at) + block.replace(cmd + " ${PORT}\n", cmd + "\n").replace(PROXY_OPEN, "    proxy: http://" + address + ":" + insidePort(script) + "\n") + cfg.substring(end);
        }
        Map<String, String> out = new LinkedHashMap<>();
        out.put("config.yaml", cfg);
        out.putAll(scripts);
        return out;
    }

    /** The other way: the files of an install with no way out, turned into the earlier way's. Null when a file is not of the shape {@link #toShut} leaves. */
    static Map<String, String> toOpen(Path dir, Map<String, String> files) {
        String cfg = files.get("config.yaml");
        if (cfg == null || !files.containsKey("run.sh")) return null;
        Map<String, String> scripts = new LinkedHashMap<>();
        for (String name : List.of("run.sh", "run-embed.sh")) {
            String script = files.get(name), cmd = "    cmd: " + dir.resolve(name);
            int at = cfg.indexOf(cmd + "\n");
            if (script == null || (at < 0 && name.equals("run-embed.sh"))) continue;
            if (at < 0) return null;
            Matcher flags = Pattern.compile(" --network \\S+ --ip (\\S+) --cap-drop NET_RAW ").matcher(script);
            if (!flags.find()) return null;
            String proxy = "    proxy: http://" + flags.group(1) + ":" + insidePort(script) + "\n";
            int end = blockEnd(cfg, at + cmd.length());
            String block = cfg.substring(at, end);
            if (!block.contains(proxy)) return null;
            scripts.put(name, script.replaceFirst("(?m)^# No way out: .*\\n# may not write raw packets.*\\n", "").replaceFirst("(?m)^docker network inspect \\S+ >/dev/null 2>&1 \\|\\| docker network create .*\\n", "")
                    .replace(flags.group(), " -p 127.0.0.1:$1:" + insidePort(script) + " "));
            cfg = cfg.substring(0, at) + block.replace(cmd + "\n", cmd + " ${PORT}\n").replace(proxy, PROXY_OPEN) + cfg.substring(end);
        }
        Map<String, String> out = new LinkedHashMap<>();
        out.put("config.yaml", cfg);
        out.putAll(scripts);
        return out;
    }

    /**
     * The embeddings server's start script with its model's folder in place of the model's name, read-only: it starts from the
     * files it has and asks nobody. A script that names no model by its name (one already given a folder) comes back as it is.
     */
    static String byFolder(String script, String folder) {
        String byName = "--model-id " + Embed.MODEL + " ";
        if (!script.contains(byName) || folder == null) return script;
        return script.replace(byName, "--model-id " + folder + " ").replace(":/data " + Embed.IMAGE + ":", ":/data:ro " + Embed.IMAGE + ":")
                .replaceFirst("(?m)^# The model downloads into .* on the first start\\.\\n", Matcher.quoteReplacement("# It reads its model from the folder it is given (under " + Embed.dir() + ", which it sees as /data) and asks nobody for it.\n"));
    }

    static void writeFiles(Path dir, Map<String, String> files) throws IOException {
        for (var f : files.entrySet()) {
            Files.writeString(dir.resolve(f.getKey()), f.getValue(), StandardCharsets.UTF_8);
            if (f.getKey().endsWith(".sh")) dir.resolve(f.getKey()).toFile().setExecutable(true);
        }
    }

    /** How long one of the seconds {@link #answersWithin} waits lasts, in milliseconds: a second, except in a test of a proxy that never answers. */
    static volatile long SECOND_MS = 1000;

    static boolean answersWithin(int seconds) throws InterruptedException {
        for (int i = 0; i < seconds && !health.test(URL); i++) Thread.sleep(SECOND_MS);
        return health.test(URL);
    }

    /**
     * It proves itself or steps back. The install is set up with no way out and its proxy answers: the proof is run, and when it holds
     * the person is told. When it does not, the files of the earlier way ({@code earlier}: what this release writes for it, or what
     * the install had before `model install` was given again) are put back, the proxy is started again to read them, the containers
     * and the network of the attempt are taken away, and the model is asked again that way, so that the person reads whether it answers.
     */
    static Proof proveOrStepBack(String model, boolean embeds, String build, Path dir, Map<String, String> earlier, PrintStream out) throws Exception {
        Chrome c = Chrome.now();
        out.println("  " + c.t("Asking the model for a first answer, to see that it works with no way out to the network. The model is loaded for this, which can take a few minutes with a large one."));
        Proof proof = prove(model, embeds, build);
        if (proof.failed() == null) {
            asker.rest(URL);   // the card is free again, as an install has always left it; the first request loads the model
            out.println("  " + c.t("The model server has no way out to the network: its containers are on a Docker network with no route to anything but this machine. Tried just now: the model answered through it, and an outside address that another container reached could not be reached from inside."));
            if (proof.names()) out.println("  " + c.t("Docker on this machine still looks names up for the model server, which a program could use to send a little information out. Docker 29 does not."));
            return proof;
        }
        writeFiles(dir, earlier);
        restartProxy(dir, unitPath(os), out);
        removeNetwork();
        out.println("  " + c.t("The model server could not be set up with no way out to the network on this machine, because {0}. It is set up the way earlier versions did, so it can reach the network. To try again: researchzosho model install", proof.failed()));
        // asked again the earlier way, so the person reads whether it answers: when it does not either, the arrangement was not the cause
        boolean up = answersWithin(30);
        if (proof.silent().equals("embed")) {
            String again = up ? asker.embed(URL, 300) : "!" + c.t("the proxy did not answer at {0}", URL);
            out.println("  " + (again.startsWith("!") ? c.t("Set up the earlier way, the embeddings server did not answer either ({0}), so the cause lies elsewhere. Its log: {1}", again.substring(1), logHint())
                                                      : c.t("Set up the earlier way, the embeddings server answered.")));
            if (up) asker.rest(URL);
        }
        String again = up ? asker.complete(URL, model, PROOF_SECONDS) : "!" + c.t("the proxy did not answer at {0}", URL);
        out.println("  " + (again.startsWith("!") ? c.t("Set up the earlier way, the model did not answer either ({0}), so the cause lies elsewhere. Its log: {1}", again.substring(1), logHint())
                                                  : c.t("Set up the earlier way, the model answered.")));
        if (up) asker.rest(URL);
        return proof;
    }

    /**
     * The lines of the plan that say an install is set up with no way out: its network's range, and, once the proof held, the day it
     * did ({@code proven}) and whether Docker looked a name up for the container. Without the day the files are written and nothing
     * is claimed yet.
     */
    static String shutProperties(Shut shut, boolean proven, boolean names) {
        return "network=shut\nsubnet=" + shut.subnet() + "\n" + (proven ? "network_tried=" + LocalDate.now() + "\n" : "") + (proven && names ? "network_names=looked-up\n" : "");
    }

    /** The lines of the plan that say an install can reach the network, and why: "asked" (the setting), or "unproven" with the reason the attempt gave. */
    static String openProperties(String why, String reason) {
        return "network=open\nnetwork_why=" + why + "\n" + (reason == null || reason.isBlank() ? "" : "network_reason=" + reason.replace("\n", " ") + "\n") + "network_tried=" + LocalDate.now() + "\n";
    }

    /** The same lines read back from a plan, to write them again when the rest of the plan is rewritten. */
    static String netProperties(Map<String, String> m) {
        StringBuilder b = new StringBuilder();
        for (String k : List.of("network", "subnet", "network_why", "network_reason", "network_tried", "network_names")) if (m.containsKey(k)) b.append(k).append('=').append(m.get(k)).append('\n');
        return b.toString();
    }

    /** The network an install's plan says it is on, when it is set up with no way out; null otherwise. */
    static Shut shutOf(Map<String, String> m) { return "shut".equals(m.get("network")) ? Shut.of(m.get("subnet")) : null; }

    /** The plan's lines with the ones about the way out replaced. */
    static void rewriteNet(Path dir, String netLines) throws IOException {
        Path props = dir.resolve("plan.properties");
        String kept = Files.exists(props) ? Files.readString(props, StandardCharsets.UTF_8).replaceAll("(?m)^(network|subnet|network_why|network_reason|network_tried|network_names)=.*\\n?", "") : "";
        Files.writeString(props, kept + (kept.isEmpty() || kept.endsWith("\n") ? "" : "\n") + netLines, StandardCharsets.UTF_8);
    }

    /** The llama.cpp image and the embeddings server's, fetched before the proof when they are not on the machine: a model that waits for an image of several gigabytes would look like one that does not answer. "" when both are there; else what Docker said. */
    static String fetchImages(String build, String embedTag, PrintStream out) throws Exception {
        List<String> images = new ArrayList<>(List.of("ghcr.io/ggml-org/llama.cpp:" + imageOf(build)));
        if (embedTag != null && !embedTag.equals("llama")) images.add(Embed.IMAGE + ":" + embedTag);
        for (String image : images) {
            if (runner.run(List.of("docker", "image", "inspect", "--format", "{{.Id}}", image)).code() == 0) continue;
            out.println("  " + Chrome.now().t("Fetching {0}, the program in a container that Docker runs here. It is several gigabytes.", image));
            Result r = runShown(List.of("docker", "pull", image));
            if (r.code() != 0) return image + (r.out().isBlank() ? "" : ": " + firstLine(r.out()));
        }
        return "";
    }

    /** The embeddings server an install has, read from its files: a Text Embeddings Inference tag, "llama", or null when it has none. */
    static String embedTagOf(Path dir) throws IOException {
        Path script = dir.resolve("run-embed.sh"), cfg = dir.resolve("config.yaml");
        if (!Files.isRegularFile(script) || !Files.isRegularFile(cfg) || !Files.readString(cfg, StandardCharsets.UTF_8).contains("\"" + EMBED_NAME + "\":")) return null;
        Matcher m = Pattern.compile(Pattern.quote(Embed.IMAGE) + ":(\\S+)").matcher(Files.readString(script, StandardCharsets.UTF_8));
        return m.find() ? m.group(1) : "llama";
    }

    /** The build of llama.cpp an install's start script runs, read from the script. */
    static String buildOf(Path dir) throws IOException {
        String run = Files.readString(dir.resolve("run.sh"), StandardCharsets.UTF_8);
        return run.contains(":server-rocm") ? "rocm" : run.contains(":server-vulkan") ? "vulkan" : "cuda";
    }

    /**
     * `model install` given again where this program's own model server already answers (Linux): the install is moved to the way that
     * is wanted now. From the earlier way to no way out, proven, or stepped back to exactly the files it had; from no way out to the
     * earlier way when the setting asks for it. Only the lines that decide the way out change ({@link #toShut}, {@link #toOpen}): the
     * model, its flags and anything the person wrote into the files stay. "" when there is nothing to change: not Linux, not an
     * install of this program's (another program's start script or service is left as it is), or already set up as wanted. Else the
     * model's name.
     */
    static String changeOver(PrintStream out) throws Exception {
        if (os != Os.linux) return "";
        Chrome c = Chrome.now();
        Path dir = dir();
        if (!Files.exists(dir.resolve("plan.properties")) || !Files.exists(dir.resolve("run.sh")) || !Files.exists(dir.resolve("config.yaml"))) return "";
        if (!CONTAINER.equals(containerOf(dir.resolve("run.sh"), null)) || !Files.isRegularFile(unitPath(os))) return "";
        Map<String, String> m = readPlan(dir);
        if (!m.containsKey("name")) return "";
        String name = m.get("name");
        boolean isShut = shutOf(m) != null, wantOpen = openAsked(), proven = m.containsKey("network_tried");
        if (!isShut && wantOpen || isShut && !wantOpen && proven) return "";   // already set up the way that is wanted
        String embedTag = embedTagOf(dir), build = buildOf(dir);
        Map<String, String> earlier = new LinkedHashMap<>();
        for (String f : List.of("config.yaml", "run.sh", "run-embed.sh")) if (Files.isRegularFile(dir.resolve(f))) earlier.put(f, Files.readString(dir.resolve(f), StandardCharsets.UTF_8));
        if (isShut && !wantOpen) {   // set up with no way out by an install that did not get to the end of its proof: proven now, or stepped back
            Map<String, String> back = toOpen(dir, earlier);
            if (back == null || !answersWithin(5)) return "";
            Proof proof = proveOrStepBack(name, embedTag != null, build, dir, back, out);
            rewriteNet(dir, proof.failed() == null ? shutProperties(shutOf(m), true, proof.names()) : openProperties("unproven", proof.failed()));
            return name;
        }
        if (wantOpen) {   // from no way out to the earlier way, on the person's word
            Map<String, String> open = toOpen(dir, earlier);
            if (open == null) { out.println("  " + c.t("The setting {0} is open, but the model server's files in {1} are not as this program wrote them, so they are left as they are. researchzosho model uninstall, then researchzosho model install, sets it up afresh.", settingName(), dir)); return name; }
            writeFiles(dir, open);
            rewriteNet(dir, openProperties("asked", ""));
            restartProxy(dir, unitPath(os), out);
            removeNetwork();
            out.println("  " + c.t("The setting {0} is open, so the model server is set up able to reach the network, as earlier versions did.", settingName()));
            return name;
        }
        String reason = null, folder = null;
        boolean tei = embedTag != null && !embedTag.equals("llama");
        if (tei && (folder = Embed.modelFolder()) == null) {
            try { out.println("  " + c.t("Downloading the embeddings model ({0}, about 1.2 GB) into {1}. It is checked when it is done.", Embed.MODEL, Embed.dir().resolve(Embed.FOLDER))); folder = Embed.fetchModel(); }
            catch (Exception e) { reason = c.t("the files of the embeddings model could not be fetched ({0})", String.valueOf(e.getMessage())); }
        }
        if (reason == null) { String images = fetchImages(build, embedTag, out); if (!images.isEmpty()) reason = c.t("a program it runs could not be fetched ({0})", images); }
        Made made = reason == null ? makeNetwork() : new Made(null, reason);
        Map<String, String> turned = null;
        if (made.shut() != null) {
            Map<String, String> from = new LinkedHashMap<>(earlier);
            String given = folder;
            if (tei) from.computeIfPresent("run-embed.sh", (k, script) -> byFolder(script, given));
            turned = toShut(dir, from, made.shut());
            if (turned == null) { removeNetwork(); made = new Made(null, c.t("its files in {0} are not as this program wrote them", dir)); }
        }
        if (made.shut() == null) {
            rewriteNet(dir, openProperties("unproven", made.why()));
            out.println("  " + c.t("The model server could not be set up with no way out to the network on this machine, because {0}. It stays as it was, able to reach the network. To try again: researchzosho model install", made.why()));
            return name;
        }
        out.println("  " + c.t("Setting the model server of this machine up again with no way out to the network. A model that is loaded now is unloaded for it."));
        writeFiles(dir, turned);
        rewriteNet(dir, shutProperties(made.shut(), false, false));
        restartProxy(dir, unitPath(os), out);
        Proof proof = answersWithin(30) ? proveOrStepBack(name, embedTag != null, build, dir, earlier, out) : stepBackUnanswered(dir, earlier, out);
        rewriteNet(dir, proof.failed() == null ? shutProperties(made.shut(), true, proof.names()) : openProperties("unproven", proof.failed()));
        return name;
    }

    /** The proxy itself did not come back with the new files: the earlier ones are put back and it is started again. */
    static Proof stepBackUnanswered(Path dir, Map<String, String> earlier, PrintStream out) throws Exception {
        Chrome c = Chrome.now();
        String why = c.t("the proxy did not answer at {0}", URL);
        writeFiles(dir, earlier);
        restartProxy(dir, unitPath(os), out);
        removeNetwork();
        out.println("  " + c.t("The model server could not be set up with no way out to the network on this machine, because {0}. It is set up the way earlier versions did, so it can reach the network. To try again: researchzosho model install", why));
        return Proof.fails(why, "");
    }

    /** The setting's name as a person types it. */
    static String settingName() { return MODEL_NETWORK.replaceFirst("^RESEARCHZOSHO_", "").toLowerCase(Locale.ROOT); }

    /**
     * Whether the model server in this folder is another program's: its start script starts a container that is not this program's.
     * Where CodeZaiku and this program share a home they share this folder, and the one that set the model server up made its start
     * script, its service and its network; this program's plan file beside them does not make it this program's.
     */
    static boolean anotherPrograms(Path dir) {
        String named = containerOf(dir.resolve("run.sh"), null);
        return named != null && !named.equals(CONTAINER);
    }

    /**
     * The network a start script puts its model on when that gives it no way out, read from the script alone: the container is on a
     * network the script makes with {@code --internal}, at a fixed address, and no port of this machine is published. Null otherwise.
     * Both programs write their start scripts so (this program's {@link Shut#said}, CodeZaiku's the same lines).
     */
    static String noWayOutNetwork(String script) {
        if (script == null || script.contains(" -p ")) return null;
        Matcher on = Pattern.compile(" --network (\\S+) --ip \\S+ ").matcher(script);
        if (!on.find()) return null;
        String net = on.group(1);
        return Pattern.compile("(?m)^docker network inspect " + Pattern.quote(net) + " .*docker network create --internal --subnet \\S+ " + Pattern.quote(net) + "\\b").matcher(script).find() ? net : null;
    }

    /**
     * What `model status` says of the way out, in a sentence or two: none, and since when; or that the model server can reach the
     * network, and why: the setting, an install from before 0.8.1, an attempt that did not hold, another program's install; with the
     * command that changes it. Where the server is a plain program (macOS, Windows) it says that.
     */
    static String wayOutSaid() {
        Chrome c = Chrome.now();
        if (os != Os.linux) return c.t("On this system the model server is an ordinary program, not in a container, so nothing keeps it from the network.");
        try {
            Path dir = dir();
            Map<String, String> m = readPlan(dir);
            String again = c.t("researchzosho model install sets it up with no way out.");
            boolean ours = Files.exists(dir.resolve("plan.properties")) && CONTAINER.equals(containerOf(dir.resolve("run.sh"), null)) && Files.isRegularFile(unitPath(os));
            // another program's model server in this folder (CodeZaiku's, where the two share a home): said from its own start script, before
            // anything this program's plan lines say, which that program may have written; and no command of this program's is advised for it
            if (anotherPrograms(dir)) {
                String theirs = noWayOutNetwork(Files.readString(dir.resolve("run.sh"), StandardCharsets.UTF_8));
                return theirs != null ? c.t("Another program set the model server up, and this one leaves it as it is. By its start script the model is on a Docker network of its own ({0}), with no port of this machine published.", theirs)
                                      : c.t("The model server can reach the network: another program set it up, and this one leaves it as it is.");
            }
            Shut shut = shutOf(m);
            if (shut != null) {
                String run = Files.isRegularFile(dir.resolve("run.sh")) ? Files.readString(dir.resolve("run.sh"), StandardCharsets.UTF_8) : "";
                Result net = runner.run(List.of("docker", "network", "inspect", NETWORK, "--format", "{{.Internal}}"));
                if (!run.contains("--network " + NETWORK + " ") || (net.code() == 0 && !net.out().strip().equals("true")))
                    return c.t("The model server can reach the network: it was set up with no way out, and its start script or its Docker network ({0}) is no longer as this program made it.", NETWORK) + " " + again;
                if (!m.containsKey("network_tried")) return c.t("The model server is set up with no way out to the network, but setting it up stopped before that was tried. researchzosho model install tries it.");
                String said = c.t("The model server has no way out to the network: its containers are on a Docker network with no route to anything but this machine ({0}). Tried on {1}: the model answered through it, and an outside address could not be reached from inside.", NETWORK, m.get("network_tried"));
                if ("looked-up".equals(m.get("network_names"))) said += " " + c.t("Docker on this machine still looks names up for the model server, which a program could use to send a little information out. Docker 29 does not.");
                if (openAsked()) said += " " + c.t("The setting {0} is open now; researchzosho model install applies it.", settingName());
                return said;
            }
            if (!ours) return c.t("The model server can reach the network: another program set it up, and this one leaves it as it is.");
            if (openAsked()) return c.t("The model server can reach the network, because the setting {0} is open. With it closed or unset, researchzosho model install sets it up with no way out.", settingName());
            String why = m.getOrDefault("network_why", "");
            if (why.equals("unproven")) return c.t("The model server can reach the network: on {0} it could not be set up with no way out, because {1}.", m.getOrDefault("network_tried", "?"), m.getOrDefault("network_reason", "?")) + " " + c.t("researchzosho model install tries again.");
            if (why.equals("asked")) return c.t("The model server can reach the network: the setting {0} was open when it was set up.", settingName()) + " " + again;
            return c.t("The model server can reach the network: a version before 0.8.1 set it up.") + " " + again;
        } catch (Exception e) { return c.t("Whether the model server can reach the network could not be read ({0}).", String.valueOf(e.getMessage())); }
    }

    /** The service, started: systemd --user, launchd, or a logon task plus a start now. "!reason" on failure. */
    static String startService(Os os, Plan p) throws Exception {
        switch (os) {
            case linux -> {
                Result r = runner.run(List.of("systemctl", "--user", "daemon-reload"));
                if (r.code() != 0) return "!systemctl --user daemon-reload: " + r.out();
                r = runner.run(List.of("systemctl", "--user", "enable", "--now", UNIT));
                if (r.code() != 0) return "!systemctl --user enable --now " + UNIT + ": " + r.out();
                try { runner.run(List.of("loginctl", "enable-linger", System.getProperty("user.name"))); } catch (Exception ignored) { }
            }
            case macos -> {
                String uid = uid();
                runner.run(List.of("launchctl", "bootout", "gui/" + uid + "/" + LABEL));   // an earlier one, if any
                Result r = runner.run(List.of("launchctl", "bootstrap", "gui/" + uid, p.unit().toString()));
                if (r.code() != 0) return "!launchctl bootstrap: " + r.out();
            }
            case windows -> {
                // the script's path in double quotes, written \" so that they reach schtasks through the JVM's quoting: a user folder may have a space
                String tr = "powershell -NoProfile -ExecutionPolicy Bypass -File \\\"" + p.unit() + "\\\"";
                Result r = runner.run(List.of("schtasks", "/create", "/tn", TASK, "/sc", "onlogon", "/tr", tr, "/f"));
                if (r.code() != 0) return "!schtasks /create: " + r.out();
                detach.start(List.of("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", p.unit().toString()));   // now, not at the next logon
            }
        }
        return "";
    }

    static String uid() {
        try { Result r = runner.run(List.of("id", "-u")); return r.out().strip(); } catch (Exception e) { return "501"; }
    }

    /** Unpack an archive into {@code into}: a tarball, or a zip through the same tar on Windows and macOS. */
    static String unpack(Path archive, Path into, boolean stripTop) throws Exception {
        Files.createDirectories(into);
        List<String> cmd = new ArrayList<>(List.of(os == Os.windows ? "tar.exe" : "tar", archive.toString().endsWith(".zip") ? "-xf" : "-xzf", archive.toString(), "-C", into.toString()));
        if (stripTop) cmd.add("--strip-components=1");
        Result r = runner.run(cmd);
        return r.code() == 0 ? "" : "!could not unpack " + archive.getFileName() + ": " + r.out();
    }

    /**
     * Install: returns the model's name, or "!reason". {@code file} names a model file already on disk (any GGUF) instead of
     * the measured row's; {@code gpus} is "all" or a device index (Linux); {@code share} listens on every interface so other
     * machines can use this card.
     */
    public static String install(Path file, String gpus, int idleMinutes, boolean share, PrintStream out) { return install(file, null, gpus, idleMinutes, share, true, out); }

    static String install(Path file, Row named, String gpus, int idleMinutes, boolean share, PrintStream out) { return install(file, named, gpus, idleMinutes, share, true, out); }

    /**
     * {@code named}: the row the person asked for by name (`model switch <name>`); null takes the one this machine is suggested.
     * {@code own}: install a model of the library's own even when another program's server on this machine already serves a
     * measured one; without it, and with no file or row named, the library uses that server and downloads nothing.
     */
    static String install(Path file, Row named, String gpus, int idleMinutes, boolean share, boolean own, PrintStream out) {
        try {
            if (health.test(URL)) {
                String up = upgrade(out);
                if (up.startsWith("!")) return up;
                // this program's own install on Linux, set up the other way than the one wanted now: it changes over, proven or stepped back (0.8.1)
                String over = changeOver(out);
                if (over.isEmpty()) out.println("  A model server already answers at " + URL + " on this machine, so the library uses that one and does not install another.");
                Config.set("RESEARCHZOSHO_DRIVE", URL);
                return !up.isEmpty() ? up : !over.isEmpty() ? over : "local-model";
            }
            Shared there = own || file != null || named != null ? null : shared();
            if (there != null) return useShared(there, out);
            String why = unsupported();
            if (why != null) return "!" + why;
            Row row = named != null ? named : rowFor(budgetGb());
            String chosen = file != null || named != null ? "yours" : "suggested";
            Path modelFile;
            if (file != null) {
                if (!Files.isRegularFile(file)) return "!no such file: " + file;
                modelFile = file.toAbsolutePath();
                row = new Row(stem(file.getFileName().toString()), 0, "", file.getFileName().toString(), "", row.ctx(), row.parallel(), "");
            } else {
                Files.createDirectories(modelsDir());
                modelFile = modelsDir().resolve(row.file());
                if (!Files.isRegularFile(modelFile)) {
                    out.println("  Downloading the model " + row.file() + sizeNote(row) + " into " + modelsDir() + ". This is a large file and can take a while; it is checked when it is done.");
                    fetchChecked(row.url(), modelFile);
                } else out.println("  The model " + row.file() + " is already in " + modelsDir() + ", so it is not downloaded again.");
                // the file that lets this model read pictures (a photographed page, a scanned PDF); without it the model reads text only
                Path vision = visionFile(row, modelFile);
                if (VISION.containsKey(row.name()) && !Files.isRegularFile(vision)) {
                    out.println("  Downloading " + vision.getFileName() + " (about 1 GB). With it the model can read pictures, such as scanned pages and photographs.");
                    try { fetchChecked("https://huggingface.co/" + row.hf() + "/resolve/main/mmproj-F16.gguf", vision); }
                    catch (Exception e) { out.println("  That download failed (" + e.getMessage() + "). The model is installed and reads text, but it cannot read pictures yet. To try the download again, give the same command again."); }
                }
            }
            Path dir = dir();
            Files.createDirectories(dir);
            // the proxy
            Path swap = dir.resolve(os == Os.windows ? "llama-swap.exe" : "llama-swap");
            if (!Files.exists(swap)) {
                String asset = "llama-swap_" + SWAP_VERSION + "_" + (os == Os.macos ? "darwin" : os.name()) + "_" + arch() + (os == Os.windows ? ".zip" : ".tar.gz");
                out.println("  Fetching llama-swap " + SWAP_VERSION + ", the small program that starts the model when something needs it and stops it when nothing does.");
                Path a = dir.resolve(asset);
                fetchChecked("https://github.com/mostlygeek/llama-swap/releases/download/v" + SWAP_VERSION + "/" + asset, a);
                String u = unpack(a, dir, false);
                if (!u.isEmpty()) return u;
                Files.deleteIfExists(a);
                swap.toFile().setExecutable(true);
            }
            // the server: llama.cpp's own build on macOS (Metal) and Windows (Vulkan); the container on Linux
            if (os != Os.linux) {
                Path server = dir.resolve("llama").resolve(os == Os.windows ? "llama-server.exe" : "llama-server");
                if (!Files.exists(server)) {
                    String asset = os == Os.macos ? "llama-" + LLAMA_BUILD + "-bin-macos-" + (arch().equals("arm64") ? "arm64" : "x64") + ".tar.gz"
                                                  : "llama-" + LLAMA_BUILD + "-bin-win-vulkan-x64.zip";
                    out.println("  Fetching llama.cpp " + LLAMA_BUILD + ", the program that runs the model, in its version for " + backend() + ".");
                    Path a = dir.resolve(asset);
                    fetchChecked("https://github.com/ggml-org/llama.cpp/releases/download/" + LLAMA_BUILD + "/" + asset, a);
                    String u = unpack(a, dir.resolve("llama"), os == Os.macos);   // the macOS tarball has one top folder; the zip is flat
                    if (!u.isEmpty()) return u;
                    Files.deleteIfExists(a);
                    if (os == Os.macos) server.toFile().setExecutable(true);
                }
            }
            // what the drive and the embedder were, so uninstall can put them back (a working address on another machine, say)
            String before = Config.get("RESEARCHZOSHO_DRIVE"), beforeModel = Config.get("RESEARCHZOSHO_MODEL"), beforeEmbed = Config.stored("RESEARCHZOSHO_EMBED");   // the file, not the environment
            if (!Files.exists(dir.resolve("previous")))
                Files.writeString(dir.resolve("previous"), (before == null ? "" : before) + "\n" + (beforeModel == null ? "" : beforeModel) + "\n" + (beforeEmbed == null ? "" : beforeEmbed) + "\n", StandardCharsets.UTF_8);
            String embedTag = embedTagFor(os);
            if ("llama".equals(embedTag)) {
                // the embeddings model for llama.cpp (its own build on macOS and Windows, its container on a Linux box with an AMD or Intel GPU); the TEI container fetches its own copy from Hugging Face
                Path ef = modelFile.getParent().resolve(EMBED_FILE);
                if (!Files.isRegularFile(ef)) { out.println("  downloading " + EMBED_FILE + " (about 0.6 GB) into " + modelFile.getParent()); fetchChecked(EMBED_URL, ef); }
            }
            Chrome c = Chrome.now();
            String build = os == Os.linux ? gpu().build() : "";
            boolean tei = os == Os.linux && embedTag != null && !embedTag.equals("llama"), embedLater = false;
            // the way out (0.8.1): on Linux the two containers get none, unless the setting asks for the earlier way or something stands in the way of the proof
            Shut shut = null;
            String openWhy = null, reason = null;
            if (os == Os.linux) {
                if (openAsked()) openWhy = "asked";
                else {
                    String images = fetchImages(build, embedTag, out);
                    Made made = images.isEmpty() ? makeNetwork() : new Made(null, c.t("a program it runs could not be fetched ({0})", images));
                    shut = made.shut();
                    if (shut == null) { openWhy = "unproven"; reason = made.why(); }
                }
            }
            // Text Embeddings Inference reads its model from a folder, fetched here and checked, so it asks nobody for it at its starts
            String folder = null;
            if (tei) {
                Files.createDirectories(Embed.dir());
                folder = Embed.modelFolder();
                if (folder == null) {
                    out.println("  " + c.t("Downloading the embeddings model ({0}, about 1.2 GB) into {1}. It is checked when it is done.", Embed.MODEL, Embed.dir().resolve(Embed.FOLDER)));
                    try { folder = Embed.fetchModel(); }
                    catch (Exception e) {
                        // with no way out the server cannot fetch its model itself: it is left out now and added at a later start of the service; the earlier way lets it fetch
                        if (shut != null) { embedTag = null; embedLater = true; }
                        out.println("  " + (shut != null ? c.t("That download failed ({0}). The model is installed and answers; search by meaning is off until the embeddings model is fetched, which the library tries again each time its service starts.", String.valueOf(e.getMessage()))
                                                         : c.t("That download failed ({0}). The embeddings server fetches its model itself the first time it starts.", String.valueOf(e.getMessage()))));
                    }
                }
            }
            Plan earlier = plan(os, row, modelFile, dir, unitPath(os), gpus, idleMinutes, share, embedTag, build, null, folder);
            Plan p = shut == null ? earlier : plan(os, row, modelFile, dir, unitPath(os), gpus, idleMinutes, share, embedTag, build, shut, folder);
            String planLines = planProperties(row, modelFile, gpus, idleMinutes, share) + "chosen=" + chosen + "\n" + idleChosen(idleMinutes);
            Files.writeString(dir.resolve("plan.properties"), planLines + (os != Os.linux ? "" : shut != null ? shutProperties(shut, false, false) : openProperties(openWhy, reason)), StandardCharsets.UTF_8);
            writeFiles(dir, filesOf(p));
            Files.createDirectories(p.unit().getParent());
            Files.writeString(p.unit(), p.unitText(), StandardCharsets.UTF_8);
            String s = startService(os, p);
            if (!s.isEmpty()) return s;
            if (!answersWithin(30)) {
                if (shut == null) return "!the proxy did not answer at " + URL + " within 30 s: " + logHint();
                Proof unanswered = stepBackUnanswered(dir, filesOf(earlier), out);   // the earlier way's files; when the proxy stays silent with them too, the fault is not the arrangement's
                rewriteNet(dir, openProperties("unproven", unanswered.failed()));
                if (!answersWithin(30)) return "!the proxy did not answer at " + URL + " within 30 s: " + logHint();
                shut = null;
            }
            if (os == Os.linux && openWhy != null)
                out.println("  " + (openWhy.equals("asked") ? c.t("The setting {0} is open, so the model server is set up able to reach the network, as earlier versions did.", settingName())
                        : c.t("The model server could not be set up with no way out to the network on this machine, because {0}. It is set up the way earlier versions did, so it can reach the network. To try again: researchzosho model install", reason)));
            if (shut != null) {
                Proof proof = proveOrStepBack(row.name(), p.embeds(), build, dir, filesOf(earlier), out);
                rewriteNet(dir, proof.failed() == null ? shutProperties(shut, true, proof.names()) : openProperties("unproven", proof.failed()));
            }
            Config.set("RESEARCHZOSHO_DRIVE", URL);
            Config.set("RESEARCHZOSHO_MODEL", row.name());
            out.println("  Done. The model starts by itself at " + URL + " when the library needs it (it runs on " + backend() + "), and stops again after " + idleMinutes + " minutes with nothing to do, so it only uses the machine while it works. The library is set to use it.");
            if (p.embeds()) {
                Config.set("RESEARCHZOSHO_EMBED", URL);
                out.println("  the embeddings server (" + Embed.MODEL + ") comes up at the same address beside it and stays; search by meaning is on"
                        + (tei && folder == null ? " (the model downloads once, about 1.2 GB, on the first search)" : "") + ". `researchzosho rebuild` indexes what is already on the shelves with it.");
            } else if (!embedLater) {
                out.println("  This machine has no graphics card that Docker can use, so the embeddings server (which lets the library search by meaning) is not installed. The library searches by words instead. If another machine runs one, point the setting RESEARCHZOSHO_EMBED at it.");
            }
            return row.name();
        } catch (Exception e) {
            return "!" + e.getMessage();
        }
    }

    /** Point the library at another program's server that serves a measured model, and say what that means. */
    static String useShared(Shared s, PrintStream out) throws IOException {
        out.println("  Another program already runs a model server on this machine, at " + s.base() + ", with the model " + s.model()
                + (s.model().equals(s.row().name()) ? "" : " (" + s.row().name() + ")") + ", one of the models ResearchZosho is measured with."
                + " The library uses that server, so nothing is downloaded and the model is in memory once for every program that uses it.");
        Row suggested = null;
        try { if (unsupported() == null) suggested = rowFor(budgetGb()); } catch (Exception ignored) { }
        if (suggested != null && ROWS.indexOf(suggested) < ROWS.indexOf(s.row()))
            out.println("  This machine could run " + suggested.name() + ", which did better in ResearchZosho's measurements. To install it as the library's own model beside the other one: researchzosho model install --own");
        else out.println("  To install a model of the library's own instead: researchzosho model install --own");
        String embed = Config.get("RESEARCHZOSHO_EMBED");
        if (embed == null || embed.isBlank() || embed.equalsIgnoreCase("off") || embed.equalsIgnoreCase("none"))
            out.println("  The library searches by words. Searching by meaning needs an embeddings server, which comes with a model of the library's own; the setting RESEARCHZOSHO_EMBED can also point at one that runs elsewhere.");
        Config.set("RESEARCHZOSHO_DRIVE", s.base());
        Config.set("RESEARCHZOSHO_MODEL", s.model());
        return s.model();
    }

    static String logHint() {
        return switch (os) { case linux -> "journalctl --user -u " + proxyUnit(); case macos, windows -> dir().resolve("llama-swap.log").toString(); };
    }

    /** `model check`: does every row's file still resolve where the row says, and the pinned builds too? For the release gate. */
    public static int check(PrintStream out) {
        int bad = 0;
        List<String> urls = new ArrayList<>();
        for (Row r : ROWS) urls.add(r.url());
        for (String a : new String[]{"linux_amd64.tar.gz", "linux_arm64.tar.gz", "darwin_arm64.tar.gz", "darwin_amd64.tar.gz", "windows_amd64.zip"}) urls.add("https://github.com/mostlygeek/llama-swap/releases/download/v" + SWAP_VERSION + "/llama-swap_" + SWAP_VERSION + "_" + a);
        for (String a : new String[]{"macos-arm64.tar.gz", "macos-x64.tar.gz", "win-vulkan-x64.zip"}) urls.add("https://github.com/ggml-org/llama.cpp/releases/download/" + LLAMA_BUILD + "/llama-" + LLAMA_BUILD + "-bin-" + a);
        for (String f : Embed.FILES.keySet()) urls.add(Embed.fileUrl(f));   // the embeddings model's files, by their pinned revision
        int limited = 0;
        for (String u : urls) {
            String status = "";
            // a host that is rate-limiting (429) says nothing about the file: wait and ask again, up to three times
            for (int attempt = 0; attempt < 3; attempt++) {
                if (attempt > 0) { try { Thread.sleep(checkBackoffMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; } }
                try {
                    Result h = runner.run(List.of(os == Os.windows ? "curl.exe" : "curl", "-sIL", "-o", os == Os.windows ? "NUL" : "/dev/null", "-w", "%{http_code}", u));
                    status = h.out().strip();
                } catch (Exception e) { status = "no curl"; }
                if (!status.equals("429")) break;
            }
            boolean ok = status.equals("200"), rateLimited = status.equals("429");
            if (rateLimited) limited++; else if (!ok) bad++;
            out.println("  " + (ok ? "ok  " : rateLimited ? "WAIT" : "GONE") + " " + status + "  " + u + (rateLimited ? "  (the host is rate-limiting this machine; not checked, not proof of absence)" : ""));
        }
        if (bad == 0 && limited == 0) out.println("  every row and pinned build resolves");
        if (bad > 0) out.println("  " + bad + " missing: re-point the row (and re-record its sha256) before a release");
        if (limited > 0) out.println("  " + limited + " not checked: the host answered 429 (rate-limited) three times; nothing is known to be missing, run the check again later");
        return bad == 0 ? 0 : 1;
    }

    /** The pause between attempts when a host answers 429; a test sets it to 0. */
    static long checkBackoffMs = 20_000;

    /** The other product's settings file, when it is not this product's own; its drive line, or null. */
    static String siblingDrive() {
        Path own = Config.userConfigPath().toAbsolutePath();
        Path sib = Path.of(home(), ".codezaiku", "config").toAbsolutePath();
        if (sib.equals(own) || !Files.isRegularFile(sib)) return null;
        try {
            for (String line : Files.readAllLines(sib, StandardCharsets.UTF_8)) {
                String t = line.strip();
                if (t.startsWith("drive") && t.contains("=")) return t.substring(t.indexOf('=') + 1).strip();
            }
        } catch (IOException ignored) { }
        return null;
    }

    static String stem(String name) { int i = name.lastIndexOf('.'); return (i > 0 ? name.substring(0, i) : name).toLowerCase(Locale.ROOT); }

    /**
     * After an update, or when the machine changed: the model this release suggests for this machine against the one installed. One line
     * when they differ and the installed one was our suggestion; "" when they agree, when nothing of ours is installed, or when the person
     * chose the model themselves. Nothing is switched for anybody: a switch is a download of many gigabytes and different answers.
     */
    public static String suggestionNotice() {
        try {
            Path dir = dir();
            if (!Files.exists(dir.resolve("plan.properties"))) return "";
            Map<String, String> m = readPlan(dir);
            String have = m.getOrDefault("name", "");
            boolean ours = ROWS.stream().anyMatch(r -> r.name().equals(have));
            if (!m.getOrDefault("chosen", ours ? "suggested" : "yours").equals("suggested")) return "";
            if (unsupported() != null) return "";
            Row now = rowFor(budgetGb());
            if (now == null || now.name().equals(have)) return "";
            return "This version suggests " + now.name() + " for this machine" + (now.moe() != null ? " (its experts run from RAM, so a small card is enough)" : "") + "; it runs " + have
                    + ". To change: researchzosho model switch   (a download" + sizeNote(now) + "; the old file stays until `researchzosho model prune`)";
        } catch (Exception e) { return ""; }
    }

    /** Replace the installed model: the suggested one, or the one named. The card, the idle minutes and the sharing stay as they were. */
    public static String switchTo(String name, PrintStream out) {
        try {
            Path dir = dir();
            if (!Files.exists(dir.resolve("plan.properties"))) return "!no model of ours is installed here; `researchzosho model install`";
            if (setUpByAnother(dir)) return "!the model server here is another program's, and this program does not change its model";   // a switch is an uninstall and an install: neither is this program's to do there
            Map<String, String> m = readPlan(dir);
            Row to = null;
            if (name == null || name.isBlank()) { if (unsupported() != null) return "!" + unsupported(); to = rowFor(budgetGb()); }
            else for (Row r : ROWS) if (r.name().equalsIgnoreCase(name.strip())) to = r;
            if (to == null) return "!no model called " + name + "; the measured ones: " + String.join(", ", ROWS.stream().map(Row::name).toList());
            if (to.name().equals(m.get("name"))) return "!" + to.name() + " is the model already installed";
            String gpus = m.getOrDefault("gpus", "all"); int idle = Integer.parseInt(m.getOrDefault("idle_minutes", String.valueOf(DEFAULT_IDLE_MINUTES))); boolean share = Boolean.parseBoolean(m.getOrDefault("share", "false"));
            // the download first: a failed one leaves the running model as it was
            Files.createDirectories(modelsDir());
            Path file = modelsDir().resolve(to.file());
            if (!Files.isRegularFile(file)) { out.println("  downloading " + to.file() + sizeNote(to) + " into " + modelsDir()); fetchChecked(to.url(), file); }
            String gone = uninstall(true);
            if (gone.startsWith("!")) return gone;
            return install(null, name == null || name.isBlank() ? null : to, gpus, idle, share, out);
        } catch (Exception e) { return "!" + e.getMessage(); }
    }

    /** The model files in the models folder that the installed model does not use, with their sizes; deleted when {@code delete}. */
    public static String prune(boolean delete, PrintStream out) {
        try {
            Path dir = dir();
            Set<String> keep = new HashSet<>(List.of(EMBED_FILE));
            if (Files.exists(dir.resolve("plan.properties"))) {
                Map<String, String> m = readPlan(dir);
                Path f = Path.of(m.getOrDefault("file", ""));
                if (f.getFileName() != null) keep.add(f.getFileName().toString());
                keep.add("mmproj-" + m.getOrDefault("name", "") + "-F16.gguf");
            }
            if (!Files.isDirectory(modelsDir())) return "nothing to remove";
            long total = 0; int n = 0;
            try (var ls = Files.list(modelsDir())) {
                for (Path f : ls.filter(x -> x.getFileName().toString().endsWith(".gguf") || x.getFileName().toString().endsWith(".part")).sorted().toList()) {
                    if (keep.contains(f.getFileName().toString())) continue;
                    long size = Files.size(f); total += size; n++;
                    out.println("  " + f.getFileName() + "  " + String.format(Locale.ROOT, "%.1f GB", size / 1e9) + (delete ? "  removed" : ""));
                    if (delete) Files.delete(f);
                }
            }
            if (n == 0) return "nothing to remove: every model file in " + modelsDir() + " is in use";
            return String.format(Locale.ROOT, "%d file(s), %.1f GB", n, total / 1e9) + (delete ? " removed" : " not used by the installed model. Remove them: researchzosho model prune --yes");
        } catch (Exception e) { return "!" + e.getMessage(); }
    }

    /** Whether the service is registered, per platform. */
    /** The container a start script of this folder starts ({@code docker run … --name <it>}); {@code otherwise} when the script is not there or names none. */
    static String containerOf(Path script, String otherwise) {
        try {
            if (Files.isRegularFile(script)) {
                Matcher m = Pattern.compile("--name\\s+([A-Za-z0-9][A-Za-z0-9_.-]*)").matcher(Files.readString(script, StandardCharsets.UTF_8));
                if (m.find()) return m.group(1);
            }
        } catch (IOException ignored) { }
        return otherwise;
    }

    /**
     * The config with every stop command naming the container that its own start script starts. The two can disagree where this folder
     * is one that two programs' installers write (a home they share): one wrote the start script under its container's name, the other
     * rewrote the config with its own. The proxy then stopped a container that was never there, so a model that went idle, or was
     * unloaded, stayed up and kept its memory.
     */
    static String stopsWhatStarts(String yaml) {
        Matcher m = Pattern.compile("(?m)^(\\s*cmd:\\s*(\\S+\\.sh)\\b[^\\n]*\\n\\s*cmdStop:\\s*docker stop\\s+)(\\S+)").matcher(yaml);
        StringBuilder out = new StringBuilder();
        while (m.find()) m.appendReplacement(out, Matcher.quoteReplacement(m.group(1) + containerOf(Path.of(m.group(2)), m.group(3))));
        m.appendTail(out);
        return out.toString();
    }

    /**
     * The systemd unit that runs this folder's proxy: this program's own, or the one another program's installer made for the same folder.
     * A restart sent to a unit that is not there does nothing and says nothing, and the proxy rereads its config only on a restart.
     */
    static String proxyUnit() {
        Path own = unitPath(Os.linux);
        if (Files.isRegularFile(own) || own.getParent() == null || !Files.isDirectory(own.getParent())) return UNIT;
        String swap = dir().resolve("llama-swap").toString();
        try (Stream<Path> units = Files.list(own.getParent())) {
            for (Path u : units.filter(x -> x.getFileName().toString().endsWith(".service")).sorted().toList())
                if (Files.readString(u, StandardCharsets.UTF_8).contains(swap)) return u.getFileName().toString().replaceFirst("\\.service$", "");
        } catch (IOException | RuntimeException ignored) { }
        return UNIT;
    }

    /**
     * Puts right an install saved with a stop command that names a container its start script does not start (see {@link #stopsWhatStarts}):
     * the command is corrected, the proxy is restarted to read it, and a model the old command left running is stopped, so the next
     * request starts it afresh under its name. What it did, in a sentence; "" when there was nothing to put right; "!" and why when it could not.
     */
    public static String repairStop() {
        if (os != Os.linux) return "";
        try {
            Path cfg = dir().resolve("config.yaml");
            if (!Files.isRegularFile(cfg)) return "";
            String y = Files.readString(cfg, StandardCharsets.UTF_8), right = stopsWhatStarts(y);
            if (right.equals(y)) return "";
            Files.writeString(cfg, right, StandardCharsets.UTF_8);
            runner.run(List.of("systemctl", "--user", "restart", proxyUnit()));
            String left = containerOf(dir().resolve("run.sh"), null);
            if (left != null) runner.run(List.of("docker", "stop", left));
            return "the model server's stop command named a container that its start script does not start, so a model that went idle was never stopped and kept its memory. The command is corrected and the proxy restarted; the model starts again on the next request";
        } catch (Exception e) { return "!" + e.getMessage(); }
    }

    static String serviceState() {
        try {
            return switch (os) {
                case linux -> runner.run(List.of("systemctl", "--user", "is-active", proxyUnit())).out().strip();
                case macos -> runner.run(List.of("launchctl", "print", "gui/" + uid() + "/" + LABEL)).code() == 0 ? "loaded" : "not loaded";
                case windows -> runner.run(List.of("schtasks", "/query", "/tn", TASK)).code() == 0 ? "task registered" : "no task";
            };
        } catch (Exception e) { return "unknown"; }
    }

    /** A few lines for `model status`. */
    /** What a later release needs to rewrite the config: the row, the file, the card, the idle minutes, the listen address. */
    static String planProperties(Row row, Path modelFile, String gpus, int idleMinutes, boolean share) {
        return "# ResearchZosho: what `model install` was told; a later release rewrites config.yaml from this\n"
                + "name=" + row.name() + "\nfile=" + modelFile.toAbsolutePath() + "\nctx=" + row.ctx() + "\nparallel=" + row.parallel() + "\nextra=" + row.extra().replace("\n", " ")
                + "\ngpus=" + gpus + "\nidle_minutes=" + idleMinutes + "\nshare=" + share + "\n";
    }

    /** An install of ours that predates the embeddings server: read its plan back, or reconstruct it from the files it wrote. */
    static Map<String, String> readPlan(Path dir) throws IOException {
        Map<String, String> m = new HashMap<>();
        Path props = dir.resolve("plan.properties");
        if (Files.exists(props)) {
            for (String line : Files.readAllLines(props, StandardCharsets.UTF_8)) { int eq = line.indexOf('='); if (eq > 0 && !line.startsWith("#")) m.put(line.substring(0, eq).strip(), line.substring(eq + 1).strip()); }
            return m;
        }
        String y = Files.readString(dir.resolve("config.yaml"), StandardCharsets.UTF_8);
        Matcher n = Pattern.compile("\"([^\"]+)\":\\s*\\n\\s*cmd:").matcher(y);
        if (!n.find()) return m;
        m.put("name", n.group(1));
        Matcher t = Pattern.compile("ttl:\\s*(\\d+)").matcher(y);
        m.put("idle_minutes", t.find() ? String.valueOf(Integer.parseInt(t.group(1)) / 60) : String.valueOf(DEFAULT_IDLE_MINUTES));
        String server = y;   // where -m, -c, --parallel and --gpus live: run.sh on Linux, the cmd line elsewhere
        Path run = dir.resolve("run.sh");
        if (Files.exists(run)) server = Files.readString(run, StandardCharsets.UTF_8);
        Matcher f = Pattern.compile("-m\\s+(\\S+)").matcher(server);
        if (f.find()) { String file = f.group(1); m.put("file", file.startsWith("/m/") ? modelsDir().resolve(file.substring(3)).toString() : file); }
        Matcher c = Pattern.compile("-c\\s+(\\d+)").matcher(server); if (c.find()) m.put("ctx", c.group(1));
        Matcher pl = Pattern.compile("--parallel\\s+(\\d+)").matcher(server); if (pl.find()) m.put("parallel", pl.group(1));
        Matcher g = Pattern.compile("--gpus\\s+(?:'\"device=([^\"]+)\"'|(all))|VISIBLE_DEVICES=(\\S+)").matcher(server);   // CUDA's --gpus, or the device variable the ROCm and Vulkan scripts set
        m.put("gpus", g.find() ? (g.group(1) != null ? g.group(1) : g.group(3) != null ? g.group(3) : "all") : "all");
        Matcher x = Pattern.compile("--chat-template-kwargs\\s+(\\S+)").matcher(server); m.put("extra", x.find() ? "--chat-template-kwargs " + x.group(1) : "");
        boolean share = false;
        try { share = Files.readString(unitPath(os), StandardCharsets.UTF_8).contains("0.0.0.0:" + PORT); } catch (IOException ignored) { }
        m.put("share", String.valueOf(share));
        return m;
    }

    /**
     * Bring an install of ours up to this release: today, the embeddings server beside the model. Returns "" when there is
     * nothing to do (no install here, or already current), the model's name when the config was rewritten and the service
     * restarted, or "!reason". Runs at every daemon start, so `update now` carries it out without a word from the person.
     */
    public static String upgrade(PrintStream out) {
        try {
            Path dir = dir();
            Path cfg = dir.resolve("config.yaml");
            if (!Files.exists(cfg)) return "";
            String y = Files.readString(cfg, StandardCharsets.UTF_8);
            boolean slot = slotRule(dir, out);   // the slot of an earlier release, in the start script and the config
            if (slot) y = Files.readString(cfg, StandardCharsets.UTF_8);
            if (y.contains("\"" + EMBED_NAME + "\":")) {
                String idle = idleRule(dir, cfg, y, out);
                if (slot && idle.isEmpty()) { restartProxy(dir, unitPath(os), out); return readPlan(dir).getOrDefault("name", ""); }   // the proxy starts the model with its new slot
                return idle;
            }
            String embedTag = embedTagFor(os);
            if (embedTag == null) return "";   // nothing to add on this machine
            Map<String, String> m = readPlan(dir);
            if (!m.containsKey("name") || !m.containsKey("file")) return "!the model config in " + dir + " could not be read back; `researchzosho model uninstall` and `model install` again";
            Path modelFile = Path.of(m.get("file"));
            Row known = null; for (Row r : ROWS) if (r.file().equals(modelFile.getFileName().toString())) known = r;
            Row row = known != null ? known : new Row(m.get("name"), 0, "", modelFile.getFileName().toString(), "", Integer.parseInt(m.getOrDefault("ctx", "16384")), Integer.parseInt(m.getOrDefault("parallel", "1")), m.getOrDefault("extra", ""));
            String gpus = m.getOrDefault("gpus", "all"); int idle = chatIdle(m); boolean share = Boolean.parseBoolean(m.getOrDefault("share", "false"));
            if ("llama".equals(embedTag)) {
                Path ef = modelFile.getParent().resolve(EMBED_FILE);
                if (!Files.isRegularFile(ef)) { out.println("  downloading " + EMBED_FILE + " (about 0.6 GB) into " + modelFile.getParent()); fetchChecked(EMBED_URL, ef); }
            }
            // the install stays set up the way it is (0.8.1): with no way out the embeddings server needs its model's folder, since it cannot fetch
            Shut shut = os == Os.linux ? shutOf(m) : null;
            // beside another program's model server (CodeZaiku's, in a home the two share) this program adds its embeddings server and changes
            // nothing of that program's: its lines in the config, its start script, its plan lines, its network. When its model has no way out
            // the embeddings server gets none either, on a network of this program's own, made on a range of this program's own (the other
            // program's network and range are its own, and its uninstall or a change of its way removes them); when none can be made the
            // embeddings server is not added, and that is said
            boolean beside = os == Os.linux && anotherPrograms(dir);
            if (beside) {
                if (Pattern.compile("(?m)^groups:").matcher(y).find()) return "";   // a config with groups of its own is not of the shape this program adds to
                shut = null;
                if (noWayOutNetwork(Files.readString(dir.resolve("run.sh"), StandardCharsets.UTF_8)) != null) {
                    Made made = makeNetwork();
                    if (made.shut() == null) return "!" + Chrome.now().t("the embeddings server was not added beside the model server another program set up with no way out to the network, because {0}; search by meaning stays off, and the next start of the service tries again", made.why());
                    shut = made.shut();
                }
            }
            String folder = null;
            if (os == Os.linux && !"llama".equals(embedTag)) {
                Files.createDirectories(Embed.dir());
                folder = Embed.modelFolder();
                if (folder == null && shut != null) {
                    try { folder = Embed.fetchModel(); }
                    catch (Exception e) { return "!" + Chrome.now().t("the embeddings model could not be fetched ({0}); search by meaning stays off, and the next start of the service tries again", String.valueOf(e.getMessage())); }
                }
            }
            Plan p = plan(os, row, modelFile, dir, unitPath(os), gpus, idle, share, embedTag, os == Os.linux ? gpu().build() : "", shut, folder);
            int entry = p.configYaml().indexOf("  \"" + EMBED_NAME + "\":");
            if (beside && (entry < 0 || p.embedScript() == null)) return "";
            if (!beside) Files.writeString(dir.resolve("plan.properties"), planProperties(row, modelFile, gpus, idle, share) + "chosen=" + m.getOrDefault("chosen", known != null ? "suggested" : "yours") + "\n" + idleChosen(idle) + netProperties(m), StandardCharsets.UTF_8);
            if (p.embedScript() != null) { Files.createDirectories(Embed.dir()); Files.writeString(dir.resolve("run-embed.sh"), p.embedScript(), StandardCharsets.UTF_8); dir.resolve("run-embed.sh").toFile().setExecutable(true); }
            // the model's start script stays as it is, whoever wrote it, so the config's stop command names the container that script starts;
            // beside another program's model only this program's entry and its group are added after that program's lines
            Files.writeString(cfg, beside ? y + (y.endsWith("\n") ? "" : "\n") + p.configYaml().substring(entry) : stopsWhatStarts(p.configYaml()), StandardCharsets.UTF_8);
            restartProxy(dir, p.unit(), out);   // the proxy rereads its config only on a restart
            String beforeEmbed = Config.stored("RESEARCHZOSHO_EMBED");
            Path prev = dir.resolve("previous");
            if (Files.exists(prev)) { String[] lines = Files.readString(prev, StandardCharsets.UTF_8).split("\n", -1); if (lines.length < 3 || lines[2].isBlank()) Files.writeString(prev, (lines.length > 0 ? lines[0] : "") + "\n" + (lines.length > 1 ? lines[1] : "") + "\n" + (beforeEmbed == null ? "" : beforeEmbed) + "\n", StandardCharsets.UTF_8); }
            Config.set("RESEARCHZOSHO_EMBED", URL);
            out.println("  the model server of this machine now carries the embeddings server (" + Embed.MODEL + ") beside the model at " + URL + "; search by meaning is on. `researchzosho rebuild` indexes what is already on the shelves with it.");
            return row.name();
        } catch (Exception e) { return "!" + e.getMessage(); }
    }

    /** The proxy started again, so it reads its config: by the system's own way on Linux and macOS, by its number on Windows. */
    static void restartProxy(Path dir, Path unit, PrintStream out) throws Exception {
        switch (os) {
            case linux -> runner.run(List.of("systemctl", "--user", "restart", proxyUnit()));
            case macos -> { runner.run(List.of("launchctl", "bootout", "gui/" + uid() + "/" + LABEL)); runner.run(List.of("launchctl", "bootstrap", "gui/" + uid(), unit.toString())); }
            case windows -> { String left = stopOurProxy(dir); if (!left.isEmpty()) out.println("  " + left); detach.start(List.of("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", unit.toString())); }
        }
    }

    /**
     * An install of ours brought to this release's idle times (0.8.0 review, round C2): the chat model's ({@link #chatIdle}) and the
     * embeddings model's ({@link ModelsAwake#embedIdleMinutes}), written into its config's ttl lines and its plan, the proxy restarted.
     * "" when they are as they should be; else the model's name.
     */
    static String idleRule(Path dir, Path cfg, String y, PrintStream out) throws Exception {
        Map<String, String> m = readPlan(dir);
        Matcher first = Pattern.compile("(?m)^  \"([^\"]+)\":\\s*\\n\\s*cmd:").matcher(y);
        if (!first.find()) return "";
        String name = first.group(1);
        int chat = chatIdle(m), embed = ModelsAwake.embedIdleMinutes();
        // beside another program's model only this program's embeddings server is this program's to set: that program's model keeps its own idle time
        boolean beside = os == Os.linux && anotherPrograms(dir);
        String now = beside ? withTtl(y, EMBED_NAME, embed * 60) : withTtl(withTtl(y, name, chat * 60), EMBED_NAME, embed * 60);
        if (now.equals(y)) return "";
        Files.writeString(cfg, now, StandardCharsets.UTF_8);
        if (beside) {
            restartProxy(dir, unitPath(os), out);
            out.println("  the embeddings server beside the model now stops after " + (embed == 0 ? "no idle time (it stays loaded)" : embed + " idle minutes") + "; work that runs keeps it loaded.");
            return name;
        }
        Path props = dir.resolve("plan.properties");
        if (Files.exists(props)) {
            String pp = Files.readString(props, StandardCharsets.UTF_8).replaceAll("(?m)^idle_minutes=.*$", "idle_minutes=" + chat).replaceAll("(?m)^idle_chosen=.*\\n?", "");
            Files.writeString(props, pp + (pp.endsWith("\n") ? "" : "\n") + idleChosen(chat), StandardCharsets.UTF_8);
        }
        restartProxy(dir, unitPath(os), out);
        out.println("  the model server of this machine now lets the model go after " + chat + " idle minutes and the embeddings model after " + (embed == 0 ? "none (it stays loaded)" : embed + "") + "; work that runs keeps them loaded.");
        return name;
    }

    /**
     * An install of a row whose slot this release changed, still on the slot an earlier one wrote ({@link #SLOTS_BEFORE}): moved to the
     * row's, in the start script and the config and the plan, so one conversation holds the chat. Left as it is when its person chose their
     * own model, or wrote another slot by hand. True when it rewrote something; the caller restarts the proxy.
     */
    static boolean slotRule(Path dir, PrintStream out) throws IOException {
        if (os == Os.linux && anotherPrograms(dir)) return false;   // another program's model (a home the two share): its slot is that program's
        Map<String, String> m = readPlan(dir);
        String name = m.getOrDefault("name", "");
        Row row = null; for (Row r : ROWS) if (r.name().equals(name)) row = r;
        String before = SLOTS_BEFORE.get(name);
        if (row == null || before == null || !m.getOrDefault("chosen", "suggested").equals("suggested")) return false;
        String[] b = before.split("/");
        String was = " -c " + b[0] + " --parallel " + b[1] + " ", now = " -c " + row.ctx() + " --parallel " + row.parallel() + " ";
        if (was.equals(now)) return false;
        boolean changed = false;
        for (Path f : List.of(dir.resolve("run.sh"), dir.resolve("config.yaml"))) {
            if (!Files.exists(f)) continue;
            String s = Files.readString(f, StandardCharsets.UTF_8), t = s.replace(was, now);
            if (!t.equals(s)) { Files.writeString(f, t, StandardCharsets.UTF_8); changed = true; }
        }
        if (!changed) return false;
        Path props = dir.resolve("plan.properties");
        if (Files.exists(props)) Files.writeString(props, Files.readString(props, StandardCharsets.UTF_8).replaceAll("(?m)^ctx=.*$", "ctx=" + row.ctx()).replaceAll("(?m)^parallel=.*$", "parallel=" + row.parallel()), StandardCharsets.UTF_8);
        out.println("  the model server of this machine now gives " + row.name() + " " + row.ctx() / row.parallel() + " tokens for a conversation" + (row.parallel() == 1 ? ", one at a time" : "") + ", so the chat's tools and a page fit.");
        return true;
    }

    /** A config with the ttl of the model {@code name}'s block set to {@code seconds}. */
    static String withTtl(String y, String name, int seconds) {
        return Pattern.compile("(?m)^(  \"" + Pattern.quote(name) + "\":\\n(?:    .*\\n)*?)    ttl: \\d+$").matcher(y).replaceFirst("$1    ttl: " + seconds);
    }

    /** Where the number of the llama-swap this program started on Windows is kept. */
    static final String SWAP_PID = "llama-swap.pid";

    /** The running processes that are a llama-swap by their program's name; whose they are is told by their command line. A test gives its own. */
    static final Supplier<List<Long>> SWAPS_RUNNING = () -> ProcessHandle.allProcesses().filter(h -> h.info().command().orElse("").toLowerCase(Locale.ROOT).contains("llama-swap")).map(ProcessHandle::pid).toList();
    static volatile Supplier<List<Long>> SWAPS = SWAPS_RUNNING;

    /**
     * The llama-swap this program started on Windows, stopped with the model servers it started (its process tree), by the number kept
     * when it was started, and only when that process is proven to be it ({@link ProcessProof}): it started no later than the number was
     * kept, and its command line is llama-swap's with this program's own config file. Never by its name: a llama-swap the person runs
     * themselves is left alone. "" when nothing else holds the proxy's port afterwards; else a sentence that says so.
     */
    static String stopOurProxy(Path dir) {
        Path kept = dir.resolve(SWAP_PID);
        String config = dir.resolve("config.yaml").toString();
        Predicate<List<String>> ours = w -> String.join(" ", w).toLowerCase(Locale.ROOT).contains("llama-swap") && String.join(" ", w).contains(config);
        try {
            Long pid = Files.isRegularFile(kept) ? Long.parseLong(Files.readString(kept, StandardCharsets.UTF_8).strip()) : null;
            if (pid != null && ProcessProof.proven(pid, kept, ours))
                runner.run(List.of("taskkill", "/pid", String.valueOf(pid), "/t", "/f"));
            if (pid == null)   // an install from a release before the number was kept (ROB 13): a llama-swap whose command line names this program's own config is ours
                for (long other : SWAPS.get()) if (other != ProcessHandle.current().pid() && ours.test(ProcessProof.words(other))) runner.run(List.of("taskkill", "/pid", String.valueOf(other), "/t", "/f"));
            Files.deleteIfExists(kept);
        } catch (Exception e) { /* nothing proven, nothing stopped */ }
        return health.test(URL) ? "A model proxy this program did not start answers at " + URL + " (a llama-swap you started yourself?), so it was left alone; stop it, then run researchzosho model install again." : "";
    }

    public static String status() {
        StringBuilder b = new StringBuilder();
        String drive = Config.get("RESEARCHZOSHO_DRIVE");
        b.append("  drive: ").append(drive == null || drive.isBlank() ? "(unset)" : drive).append('\n');
        Path cfg = dir().resolve("config.yaml");
        if (!Files.exists(cfg)) { b.append("  no model server of this machine's own (`researchzosho model install` sets one up)\n"); return b.toString(); }
        String name = "?";
        try {
            String y = Files.readString(cfg, StandardCharsets.UTF_8);
            Matcher m = Pattern.compile("\"([^\"]+)\":\\s*\\n\\s*cmd:").matcher(y);
            name = m.find() ? m.group(1) : "?";
            Matcher t = Pattern.compile("ttl:\\s*(\\d+)").matcher(y);
            b.append("  model: ").append(name).append(" on ").append(backend()).append(t.find() ? ", stops after " + (Integer.parseInt(t.group(1)) / 60) + " idle minutes" : "").append('\n');
            Matcher et = Pattern.compile("(?m)^  \"" + EMBED_NAME + "\":\\n(?:    .*\\n)*?    ttl: (\\d+)$").matcher(y);
            int embedTtl = et.find() ? Integer.parseInt(et.group(1)) : 0;
            b.append("  embeddings: ").append(y.contains("\"" + EMBED_NAME + "\":") ? Embed.MODEL + " beside it at the same address, model name \"" + EMBED_NAME + "\", " + (embedTtl == 0 ? "never idles out" : "stops after " + embedTtl / 60 + " idle minutes") : "none beside it (no card docker can use; RESEARCHZOSHO_EMBED can point elsewhere)").append('\n');
        } catch (IOException e) { b.append("  config unreadable: ").append(e.getMessage()).append('\n'); }
        b.append("  network: ").append(wayOutSaid()).append('\n');
        boolean up = health.test(URL);
        b.append("  proxy at ").append(URL).append(": ").append(up ? "answers" : "does not answer (service " + serviceState() + "; log: " + logHint() + ")").append('\n');
        if (up) {
            try {
                String running = loaded.apply(URL);
                boolean modelUp = running.contains("\"model\":\"" + name + "\""), embedUp = running.contains("\"model\":\"" + EMBED_NAME + "\"");
                b.append("  loaded now: ").append(modelUp ? "the model, yes (the memory is in use)" : "the model, no (the memory is free; the next request starts it)").append(embedUp ? "; the embeddings server, yes" : "").append('\n');
            } catch (Exception ignored) { }
        }
        return b.toString();
    }

    /** Unload the model now (the proxy stays; the next request starts it again). */
    public static String stop() {
        try {
            HttpClient c = PROBE_HTTP;
            HttpResponse<String> r = c.send(HttpRequest.newBuilder(URI.create(URL + "/unload")).timeout(Duration.ofSeconds(20)).GET().build(), HttpResponse.BodyHandlers.ofString());
            return r.statusCode() < 300 ? "unloaded; the memory is free" : "!the proxy answered " + r.statusCode();
        } catch (Exception e) { return "!no proxy answers at " + URL; }
    }

    /** Remove the service and the proxy; the model files in ~/models stay; the drive goes back to what it was before install. */
    public static String uninstall() { return uninstall(false); }

    public static String uninstall(boolean force) {
        StringBuilder said = new StringBuilder();   // what is said of a llama-swap this program did not start, before the rest
        try {
            // a model server another program set up in this folder (CodeZaiku's, in a home the two share) is that program's: it stays, and only
            // what this program put beside it goes
            if (setUpByAnother(dir())) return leaveAnothers();
            String sib = siblingDrive();
            if (!force && URL.equals(sib)) return "!CodeZaiku's settings also point at this proxy (" + Path.of(home(), ".codezaiku", "config") + "); point it elsewhere first, or pass --force";
            switch (os) {
                case linux -> {
                    boolean shut = false;
                    try { shut = Files.exists(dir().resolve("plan.properties")) && shutOf(readPlan(dir())) != null; } catch (IOException | RuntimeException ignored) { }
                    runner.run(List.of("systemctl", "--user", "disable", "--now", UNIT)); Files.deleteIfExists(unitPath(os)); runner.run(List.of("systemctl", "--user", "daemon-reload"));
                    if (shut) removeNetwork();   // the network with no way out goes with the install that made it
                }
                case macos -> { runner.run(List.of("launchctl", "bootout", "gui/" + uid() + "/" + LABEL)); Files.deleteIfExists(unitPath(os)); }
                case windows -> { runner.run(List.of("schtasks", "/delete", "/tn", TASK, "/f")); String left = stopOurProxy(dir()); if (!left.isEmpty()) said.append(left).append(' '); }
            }
            Path d = dir();
            String restored = "";
            Path prev = d.resolve("previous");
            if (Files.exists(prev)) {
                String[] lines = Files.readString(prev, StandardCharsets.UTF_8).split("\n", -1);
                String drive = lines.length > 0 ? lines[0].strip() : "", model = lines.length > 1 ? lines[1].strip() : "", embed = lines.length > 2 ? lines[2].strip() : "";
                if (URL.equals(Config.get("RESEARCHZOSHO_DRIVE"))) {   // still pointing at the proxy being removed: put the old address back
                    Config.set("RESEARCHZOSHO_DRIVE", drive.isEmpty() ? "http://localhost:8200" : drive);
                    Config.set("RESEARCHZOSHO_MODEL", model.isEmpty() ? "local-model" : model);
                    restored = "; the drive is back to " + (drive.isEmpty() ? "its default" : drive);
                }
                if (URL.equals(Config.stored("RESEARCHZOSHO_EMBED"))) {   // the file, not the environment: what install wrote
                    boolean none = embed.isEmpty() || embed.equalsIgnoreCase("off") || embed.equalsIgnoreCase("none");
                    Config.set("RESEARCHZOSHO_EMBED", none ? "off" : embed);
                    restored += "; embeddings " + (none ? "off (search by words)" : "back to " + embed);
                }
            }
            return said + "removed the service and the proxy; the model files in " + modelsDir() + " stay" + restored + removeOwn(d);
        } catch (Exception e) { return "!" + e.getMessage(); }
    }

    /** The files an install of this program makes in its folder, by name: what an uninstall removes, and nothing else. */
    static final List<String> OWN_FILES = List.of("config.yaml", "run.sh", "run-embed.sh", "plan.properties", "previous", "llama-swap", "llama-swap.exe",
            "LICENSE.md", "README.md", "start.ps1", "llama-swap.log", "llama-swap.err", SWAP_PID);

    /** Whether a name in the folder is one this program made: one of {@link #OWN_FILES}, a download of its own (an archive, a part), or its llama.cpp folder. */
    static boolean ownName(String name) {
        return OWN_FILES.contains(name) || name.equals("llama") || name.endsWith(".part") || name.startsWith("llama-swap_" + SWAP_VERSION + "_") || name.startsWith("llama-" + LLAMA_BUILD + "-bin-");
    }

    /**
     * What this program made in its folder removed, and the folder when nothing is left in it. A file another program wrote there stays,
     * and so does the folder then. "" when the folder is gone; else the words that say what stays.
     */
    static String removeOwn(Path d) throws IOException {
        if (!Files.isDirectory(d)) return "";
        List<String> stays = new ArrayList<>();
        try (Stream<Path> in = Files.list(d)) {
            for (Path p : in.sorted().toList()) {
                String name = p.getFileName().toString();
                if (!ownName(name)) { stays.add(name); continue; }
                if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) { try (var s = Files.walk(p)) { s.sorted(Comparator.reverseOrder()).forEach(q -> { try { Files.deleteIfExists(q); } catch (IOException ignored) { } }); } }
                else Files.deleteIfExists(p);
            }
        }
        if (stays.isEmpty()) { Files.deleteIfExists(d); return ""; }
        return "; the folder " + d + " stays, since it holds what this program did not make (" + String.join(", ", stays) + ")";
    }

    /**
     * Whether the model server in this folder was set up by another program (a home the two share): on Linux its start script starts
     * another program's container; on macOS this program's launch agent is not there; on Windows the start script is not this program's.
     */
    static boolean setUpByAnother(Path dir) throws IOException {
        return switch (os) {
            case linux -> anotherPrograms(dir);
            case macos -> Files.isRegularFile(dir.resolve("config.yaml")) && !Files.isRegularFile(unitPath(Os.macos));
            case windows -> Files.isRegularFile(dir.resolve("start.ps1")) && !Files.readString(dir.resolve("start.ps1"), StandardCharsets.UTF_8).startsWith("# ResearchZosho");
        };
    }

    /** A config with this program's embeddings server taken out: its entry, and its group (with the groups line, when it was the only one). */
    static String withoutEmbed(String y) {
        String head = "  \"" + EMBED_NAME + "\":\n";
        int at = y.indexOf(head);
        String out = at < 0 ? y : y.substring(0, at) + y.substring(blockEnd(y, at + head.length()));
        out = out.replaceFirst("(?m)^  \"embedding\":\\n(?:    .*\\n)*", "");
        return out.replaceFirst("(?m)^groups:\\n(?!  )", "");
    }

    /**
     * `model uninstall` where another program set the model server up: that server, its files, its service and its network stay, and the
     * library goes on using it. What this program put beside it goes: its embeddings server's entry in the config (the proxy is started
     * again to read it, on Linux), its start script, its container, and its own network with no way out; the embeddings setting goes back
     * to what it was.
     */
    static String leaveAnothers() throws Exception {
        Path d = dir(), cfg = d.resolve("config.yaml"), script = d.resolve("run-embed.sh");
        boolean removed = false, ownNetwork = false;
        if (Files.isRegularFile(script) && EMBED_CONTAINER.equals(containerOf(script, null))) {
            ownNetwork = Files.readString(script, StandardCharsets.UTF_8).contains("--network " + NETWORK + " ");
            Files.delete(script);
            removed = true;
        }
        if (Files.isRegularFile(cfg)) {
            String y = Files.readString(cfg, StandardCharsets.UTF_8), without = withoutEmbed(y);
            if (!without.equals(y)) {
                Files.writeString(cfg, without, StandardCharsets.UTF_8);
                removed = true;
                if (os == Os.linux) runner.run(List.of("systemctl", "--user", "restart", proxyUnit()));   // the proxy rereads its config only on a restart
            }
        }
        if (os == Os.linux && removed) { if (ownNetwork) removeNetwork(); else runner.run(List.of("docker", "stop", EMBED_CONTAINER)); }
        String restored = "";
        if (URL.equals(Config.stored("RESEARCHZOSHO_EMBED"))) {   // the file, not the environment: what this program's upgrade wrote
            String[] lines = Files.isRegularFile(d.resolve("previous")) ? Files.readString(d.resolve("previous"), StandardCharsets.UTF_8).split("\n", -1) : new String[0];
            String embed = lines.length > 2 ? lines[2].strip() : "";
            boolean none = embed.isEmpty() || embed.equalsIgnoreCase("off") || embed.equalsIgnoreCase("none") || embed.equals(URL);
            Config.set("RESEARCHZOSHO_EMBED", none ? "off" : embed);
            restored = "; embeddings " + (none ? "off (search by words)" : "back to " + embed);
        }
        return "the model server in " + d + " is another program's, so it stays and the library goes on using it"
                + (removed ? "; this program's embeddings server beside it is removed" : "; nothing of this program's was beside it") + restored;
    }

    /**
     * `model use <address> [<model>]`: point the library at another model server now. The server is asked what it serves and the model
     * is asked to reply before anything is changed; then the settings are written, and the running service takes the new model for its
     * next run and its next question (a run going now finishes on the model it started with). Nothing to restart.
     */
    static int use(String address, String name, Setup.Probe probe, PrintStream out) throws IOException {
        if (address == null || address.isBlank()) { out.println("usage: researchzosho model use <address> [<model>]   for example: researchzosho model use http://192.168.1.20:8080 qwen3.8-27b"); return 2; }
        String base = Config.driveBase(address.strip());
        String key = Setup.local(base) ? null : Config.get("RESEARCHZOSHO_API_KEY");
        List<String> ids = probe.models(base, key);
        if (ids == null) { out.println("  No model server answers at " + base + ". Nothing was changed."); return 1; }
        String model = name == null || name.isBlank() ? null : name.strip();
        if (model == null) {
            if (ids.size() == 1) model = ids.get(0);
            else if (!ids.isEmpty()) {
                out.println("  The server at " + base + " serves " + ids.size() + " models. Name the one to use:");
                for (String id : ids) out.println("    researchzosho model use " + base + " " + id);
                out.println("  Nothing was changed.");
                return 2;
            }
        } else if (!ids.isEmpty() && !ids.contains(model)) {
            out.println("  The server at " + base + " does not serve a model called " + model + ". It serves: " + String.join(", ", ids) + ". Nothing was changed.");
            return 1;
        }
        String hello = probe.chat(base, model == null ? "local-model" : model, key);
        if (hello.startsWith("!")) { out.println("  The server answered, but the model did not reply (" + hello.substring(1) + "). Nothing was changed."); return 1; }
        ModelEntries.put(ModelEntries.DEFAULT, base, model, null, null, null, null);   // the one operation that sets the default: the Settings page and the chat's "make X the default" go through it too
        out.println("  The library now uses " + (model == null ? "the model" : model) + " at " + base + ". The service takes it for its next research run and its next question; a run going now finishes on the model it started with.");
        return 0;
    }

    /** `model …` from the command line: install [--file F] [--gpu N] [--idle-minutes N] [--share] | status | stop | uninstall. */
    public static int command(String[] a, int from, PrintStream out) {
        String op = a.length > from ? a[from] : "status";
        switch (op) {
            case "install" -> {
                if (!Helpers.localAllowed()) { out.println(Helpers.localRefusedSaid("a model server")); return 1; }
                Path file = null; String gpus = "all"; int idle = idleSet() != null ? idleSet() : DEFAULT_IDLE_MINUTES; boolean share = false, own = false;
                for (int i = from + 1; i < a.length; i++) {
                    switch (a[i]) {
                        case "--file" -> file = Path.of(a[++i]);
                        case "--gpu" -> gpus = a[++i];
                        case "--idle-minutes" -> idle = Integer.parseInt(a[++i]);
                        case "--share" -> share = true;
                        case "--own" -> own = true;
                        default -> { out.println("usage: researchzosho model install [--own] [--file <gguf>] [--gpu <index>] [--idle-minutes N] [--share]"); return 2; }
                    }
                }
                String r = install(file, null, gpus, idle, share, own, out);
                if (r.startsWith("!")) { out.println("  The model could not be set up: " + r.substring(1)); return 1; }
                out.println("  The library now uses the model " + r + " at " + Config.get("RESEARCHZOSHO_DRIVE") + ". To see how it is doing: researchzosho model status");
                return 0;
            }
            case "status" -> { out.print(status()); String n = suggestionNotice(); if (!n.isEmpty()) out.println("  " + n); return 0; }
            case "stop" -> { String r = stop(); out.println("  " + (r.startsWith("!") ? r.substring(1) : r)); return r.startsWith("!") ? 1 : 0; }
            case "uninstall" -> { String r = uninstall(a.length > from + 1 && a[from + 1].equals("--force")); out.println("  " + (r.startsWith("!") ? r.substring(1) : r)); return r.startsWith("!") ? 1 : 0; }
            case "check" -> { return check(out); }
            case "switch" -> {
                String r = switchTo(a.length > from + 1 ? a[from + 1] : null, out);
                if (r.startsWith("!")) { out.println("  The model was not changed: " + r.substring(1)); return 1; }
                out.println("  The library now uses the model " + r + " at " + URL + ". The one it used before stays on the disk; researchzosho model prune removes models that are not in use.");
                return 0;
            }
            case "use" -> {
                try { return use(a.length > from + 1 ? a[from + 1] : null, a.length > from + 2 ? a[from + 2] : null, Setup.liveProbe(), out); }
                catch (IOException e) { out.println("  The settings could not be written: " + e.getMessage() + ". Nothing was changed."); return 1; }
            }
            case "prune" -> { String r = prune(a.length > from + 1 && a[from + 1].equals("--yes"), out); out.println("  " + (r.startsWith("!") ? r.substring(1) : r)); return r.startsWith("!") ? 1 : 0; }
            default -> { out.println("usage: researchzosho model install|status|use <address> [<model>]|switch [<name>]|prune [--yes]|stop|uninstall [--force]|check"); return 2; }
        }
    }
}
