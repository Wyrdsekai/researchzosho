package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import java.io.File;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Predicate;
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
 * before — is used as it is. On Linux the server is llama.cpp's CUDA container (Docker with the NVIDIA runtime); on
 * macOS its native Metal build sized by unified memory, as a launchd agent; on Windows its Vulkan build, which runs on
 * any card, as a logon task.
 *
 * <p>The seams ({@link #os}, {@link #runner}, {@link #downloader}, {@link #health}) let a test drive every platform's
 * install on any host, without a card, a network, docker or a service manager.
 */
public final class ModelServer {

    private ModelServer() { }

    public static final int PORT = 8211;
    public static final String URL = "http://127.0.0.1:" + PORT;
    public static final String UNIT = "researchzosho-model";                  // systemd --user unit (Linux)
    public static final String LABEL = "org.researchzosho.model";             // launchd agent (macOS)
    public static final String TASK = "ResearchZoshoModel";                     // scheduled task (Windows)
    public static final String CONTAINER = "researchzosho-model";
    public static final String SWAP_VERSION = "255";
    public static final String LLAMA_BUILD = "b10929";
    public static final int DEFAULT_IDLE_MINUTES = 20;

    /** The embeddings server beside the model: the same address, model name "embed" (Embeddings' default), never idles out. */
    public static final String EMBED_NAME = "embed";
    public static final String EMBED_CONTAINER = "researchzosho-model-embed";
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
    public static final List<Row> ROWS = List.of(
        new Row("qwen3.8-27b",  24, "unsloth/Qwen3.8-27B-GGUF",    "Qwen3.8-27B-UD-Q4_K_M.gguf", "322e194ff79741c7baa497c240f677f54b201b0efab44ca8e50f122b39123482", 131072, 4, "--chat-template-kwargs '{\"reasoning_effort\":\"low\"}'"),
        new Row("qwen3.6-35b-a3b", 4, "unsloth/Qwen3.6-35B-A3B-GGUF", "Qwen3.6-35B-A3B-UD-Q4_K_M.gguf", "ac0e2c1189e055faa36eff361580e79c5bd6f8e76bffb4ce547f167d53e31a61", 65536, 2, "-b 4096 -ub 4096", new Moe(32, 40, 0.46, 4.5, 28)),
        new Row("gpt-oss-20b",  16, "unsloth/gpt-oss-20b-GGUF",    "gpt-oss-20b-F16.gguf",       "4e4f9cd88d6456e4f389e7262eca4a8d565211e2b22ece9ca7a8556168ff3c66",  32768, 2, "--chat-template-kwargs '{\"reasoning_effort\":\"low\"}'"),
        new Row("qwen3.5-9b",    8, "unsloth/Qwen3.5-9B-GGUF",     "Qwen3.5-9B-Q4_K_M.gguf",     "03b74727a860a56338e042c4420bb3f04b2fec5734175f4cb9fa853daf52b7e8",  16384, 1, "--chat-template-kwargs '{\"enable_thinking\":false}'"),
        new Row("gemma-4-e4b",   4, "unsloth/gemma-4-E4B-it-GGUF", "gemma-4-E4B-it-Q4_K_M.gguf", "85a896a047553e842f25297ee5b031d64ff30147d9c4af17b1e4b394cd1fab87",  16384, 1, ""),
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

    static { for (Row r : ROWS) sums.put(r.file(), r.sha256()); sums.put(EMBED_FILE, EMBED_SHA256); for (var v : VISION.entrySet()) sums.put("mmproj-" + v.getKey() + "-F16.gguf", v.getValue()); }

    static String sha256(Path p) throws Exception {
        var md = MessageDigest.getInstance("SHA-256");
        try (var in = Files.newInputStream(p)) { byte[] b = new byte[1 << 16]; int n; while ((n = in.read(b)) > 0) md.update(b, 0, n); }
        return HexFormat.of().formatHex(md.digest());
    }

    /** Fetch {@code url} to {@code dest} and check it against the recorded hash; a mismatch is deleted and refused. */
    static void fetchChecked(String url, Path dest) throws Exception {
        downloader.fetch(url, dest);
        String want = sums.get(dest.getFileName().toString());
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

    /** Free memory on the NVIDIA card now, in GB; the whole card when that cannot be read. */
    static double nvidiaFreeGb() {
        try {
            Result r = runner.run(List.of("nvidia-smi", "--query-gpu=memory.free", "--format=csv,noheader,nounits"));
            if (r.code() == 0) return Double.parseDouble(r.out().strip().split("\\R")[0].strip()) / 1024.0;
        } catch (Exception ignored) { }
        return nvidiaGb();
    }

    /** The flags an experts-in-RAM row adds: where the experts go, and the threads. "" for a dense row, which runs as it always did. */
    static String moeAndThreads(Row row) {
        if (row.moe() == null) return "";
        List<Integer> cores = fastCores();
        double also = (VISION.containsKey(row.name()) ? VISION_GB : 0) + (embedTagFor(os) != null && os == Os.linux ? EMBED_GB : 0);
        String moe = moeArgs(row, os == Os.macos ? 0 : nvidiaFreeGb(), also);
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
    public static Runner runner = cmd -> {
        Path out = Files.createTempFile("researchzosho-cmd", ".out");
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(out.toFile()).start();
            if (!p.waitFor(COMMAND_SECONDS, TimeUnit.SECONDS)) { p.destroyForcibly(); return new Result(-1, cmd.get(0) + " gave no answer in " + COMMAND_SECONDS + " s and was stopped"); }
            return new Result(p.exitValue(), Files.readString(out, StandardCharsets.UTF_8));
        } finally { Files.deleteIfExists(out); }
    };
    public static Downloader downloader = (url, dest) -> {
        Path part = dest.resolveSibling(dest.getFileName() + ".part");
        Process p = new ProcessBuilder(os == Os.windows ? "curl.exe" : "curl", "-fL", "--progress-bar", "-o", part.toString(), url).inheritIO().start();
        if (p.waitFor() != 0) throw new IOException("download failed: " + url);
        Files.move(part, dest, StandardCopyOption.REPLACE_EXISTING);
    };
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient PROBE_HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

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

    /** The largest NVIDIA card's memory in GB, rounded; 0 when nvidia-smi does not answer. */
    static double nvidiaGb() {
        try {
            Result r = runner.run(List.of("nvidia-smi", "--query-gpu=memory.total", "--format=csv,noheader,nounits"));
            if (r.code() != 0) return 0;
            double best = 0;
            for (String line : r.out().split("\\R")) { String t = line.strip(); if (!t.isEmpty()) best = Math.max(best, Math.round(Double.parseDouble(t) / 1024.0)); }
            return best;
        } catch (Exception e) { return 0; }
    }

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
     * Memory the model may take, in GB: an NVIDIA card's on Linux; on macOS about seven tenths of unified memory, which is
     * what the system lets the GPU wire; on Windows the NVIDIA card where there is one, else half the RAM, which is what an
     * integrated card shares.
     */
    public static double budgetGb() {
        return switch (os) {
            case linux -> nvidiaGb();
            case macos -> Math.floor(ramGb() * 0.7);
            case windows -> { double n = nvidiaGb(); yield n > 0 ? n : Math.floor(ramGb() * 0.5); }
        };
    }

    /** What the server runs on here, for the offer: "the Ada" is not known, the backend is. */
    static String backend() { return switch (os) { case linux -> "CUDA in Docker"; case macos -> "Metal"; case windows -> "Vulkan"; }; }

    /** Why this machine cannot serve a model on demand, or null when it can. */
    public static String unsupported() {
        switch (os) {
            case linux -> {
                try {
                    Result d = runner.run(List.of("docker", "info", "--format", "{{.Runtimes}}"));
                    if (d.code() != 0) return "docker is not on this machine (on Linux the model runs in llama.cpp's container)";
                    if (!d.out().contains("nvidia")) return "docker here has no NVIDIA runtime (install nvidia-container-toolkit)";
                } catch (Exception e) { return "docker is not on this machine (on Linux the model runs in llama.cpp's container)"; }
                if (nvidiaGb() <= 0) return "no NVIDIA card answers nvidia-smi";
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
     * How this machine embeds beside the model: on Linux the Text Embeddings Inference image for the card (null when
     * there is no card docker can use: the CPU image is a tenth of a chunk a second, not worth a service); on macOS and
     * Windows llama.cpp's own build with {@link #EMBED_FILE} ("llama").
     */
    static String embedTagFor(Os os) {
        if (os != Os.linux) return "llama";
        try {   // through this class's runner seam, so a test's fake machine decides (Embed.tag reads the real docker)
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
    public static Plan plan(Os os, Row row, Path modelFile, Path dir, Path unit, String gpus, int idleMinutes, boolean share, String embedTag) {
        String cmd = switch (os) {
            case linux -> dir.resolve("run.sh") + " ${PORT}";
            case macos -> dir.resolve("llama").resolve("llama-server") + " " + serverArgs(row, modelFile, os);
            case windows -> dir.resolve("llama").resolve("llama-server.exe") + " " + serverArgs(row, modelFile, os);
        };
        String cfg = "# ResearchZosho: the model server on demand. llama-swap listens on " + PORT + "; the server starts on the first request\n"
                + "# and stops after ttl seconds idle. `researchzosho model status` reads it.\n"
                + "healthCheckTimeout: 600\n"
                + "startPort: 10001\n"
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
            // the embeddings server: its own group with swapping off, so it lives beside the model instead of evicting it
            // (and is not evicted by it), and no ttl — it is small and every search wants it
            String embedCmd = os == Os.linux ? dir.resolve("run-embed.sh") + " ${PORT}"
                    : dir.resolve("llama").resolve(os == Os.windows ? "llama-server.exe" : "llama-server") + " -m " + modelFile.getParent().resolve(EMBED_FILE)
                      + " --host 127.0.0.1 --port ${PORT} --embedding --pooling last -c 8192 -b 8192 -ub 8192 -ngl 99";
            cfg += "  \"" + EMBED_NAME + "\":\n"
                + "    cmd: " + embedCmd + "\n"
                + (os == Os.linux ? "    cmdStop: docker stop " + EMBED_CONTAINER + "\n" : "")
                + "    proxy: http://127.0.0.1:${PORT}\n"
                + "    checkEndpoint: /health\n"
                + "    ttl: 0\n"
                + "    aliases: [\"" + Embed.MODEL + "\", \"text-embedding\"]\n"
                + "groups:\n"
                + "  \"embedding\":\n"
                + "    swap: false\n"
                + "    exclusive: false\n"
                + "    persistent: true\n"
                + "    members: [\"" + EMBED_NAME + "\"]\n";
            if (os == Os.linux) embedScript = "#!/bin/sh\n"
                + "# The embeddings server (Text Embeddings Inference, " + Embed.MODEL + "), started by llama-swap on demand beside the model; $1 is the port it assigned.\n"
                + "# The model downloads into " + Embed.dir() + " on the first start.\n"
                + "exec docker run --rm --name " + EMBED_CONTAINER + " --gpus " + (gpus.equals("all") ? "all" : "'\"device=" + gpus + "\"'")
                + " -p 127.0.0.1:$1:80 -v " + Embed.dir().toAbsolutePath() + ":/data " + Embed.IMAGE + ":" + embedTag + " \\\n"
                + "  --model-id " + Embed.MODEL + " --pooling last-token --max-client-batch-size 128 --max-batch-tokens 65536 --auto-truncate\n";
        }
        String run = os != Os.linux ? null : "#!/bin/sh\n"
                + "# " + row.name() + " in llama.cpp, started by llama-swap on demand; $1 is the port it assigned. The flags are the measured ones.\n"
                + "exec docker run --rm --name " + CONTAINER + " --gpus " + (gpus.equals("all") ? "all" : "'\"device=" + gpus + "\"'")
                + (row.moe() == null || fastCores().isEmpty() ? "" : " --cpuset-cpus=" + String.join(",", fastCores().stream().map(String::valueOf).toList()))
                + " -p 127.0.0.1:$1:8080 -v " + modelFile.getParent() + ":/m ghcr.io/ggml-org/llama.cpp:server-cuda \\\n"
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
            case windows -> "# ResearchZosho: start the model proxy (llama-swap) in the background; a logon task runs this, and `researchzosho model install` ran it once now\n"
                    + "if (-not (Get-Process llama-swap -ErrorAction SilentlyContinue)) {\n"
                    + "  Start-Process -FilePath '" + swap + "' -ArgumentList '--config','\"" + dir.resolve("config.yaml") + "\"','--listen','" + listen + "' -WindowStyle Hidden -RedirectStandardOutput '" + dir.resolve("llama-swap.log") + "' -RedirectStandardError '" + dir.resolve("llama-swap.err") + "'\n}\n";
        };
        return new Plan(row, modelFile, dir, unit, cfg, run, unitText, embedScript, embedTag != null);
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
                out.println("  A model server already answers at " + URL + " on this machine, so the library uses that one and does not install another.");
                Config.set("RESEARCHZOSHO_DRIVE", URL);
                return up.isEmpty() ? "local-model" : up;
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
            if (embedTag != null && os != Os.linux) {
                // the embeddings model for llama.cpp's own build; the Linux container fetches its own copy from Hugging Face
                Path ef = modelFile.getParent().resolve(EMBED_FILE);
                if (!Files.isRegularFile(ef)) { out.println("  downloading " + EMBED_FILE + " (about 0.6 GB) into " + modelFile.getParent()); fetchChecked(EMBED_URL, ef); }
            }
            Plan p = plan(os, row, modelFile, dir, unitPath(os), gpus, idleMinutes, share, embedTag);
            Files.writeString(dir.resolve("plan.properties"), planProperties(row, modelFile, gpus, idleMinutes, share) + "chosen=" + chosen + "\n", StandardCharsets.UTF_8);
            Files.writeString(dir.resolve("config.yaml"), p.configYaml(), StandardCharsets.UTF_8);
            if (p.runScript() != null) { Files.writeString(dir.resolve("run.sh"), p.runScript(), StandardCharsets.UTF_8); dir.resolve("run.sh").toFile().setExecutable(true); }
            if (p.embedScript() != null) { Files.createDirectories(Embed.dir()); Files.writeString(dir.resolve("run-embed.sh"), p.embedScript(), StandardCharsets.UTF_8); dir.resolve("run-embed.sh").toFile().setExecutable(true); }
            Files.createDirectories(p.unit().getParent());
            Files.writeString(p.unit(), p.unitText(), StandardCharsets.UTF_8);
            String s = startService(os, p);
            if (!s.isEmpty()) return s;
            for (int i = 0; i < 30 && !health.test(URL); i++) Thread.sleep(1000);
            if (!health.test(URL)) return "!the proxy did not answer at " + URL + " within 30 s: " + logHint();
            Config.set("RESEARCHZOSHO_DRIVE", URL);
            Config.set("RESEARCHZOSHO_MODEL", row.name());
            out.println("  Done. The model starts by itself at " + URL + " when the library needs it (it runs on " + backend() + "), and stops again after " + idleMinutes + " minutes with nothing to do, so it only uses the machine while it works. The library is set to use it.");
            if (p.embeds()) {
                Config.set("RESEARCHZOSHO_EMBED", URL);
                out.println("  the embeddings server (" + Embed.MODEL + ") comes up at the same address beside it and stays; search by meaning is on"
                        + (os == Os.linux ? " (the model downloads once, about 1.2 GB, on the first search)" : "") + ". `researchzosho rebuild` indexes what is already on the shelves with it.");
            } else {
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
        return switch (os) { case linux -> "journalctl --user -u " + UNIT; case macos, windows -> dir().resolve("llama-swap.log").toString(); };
    }

    /** `model check`: does every row's file still resolve where the row says, and the pinned builds too? For the release gate. */
    public static int check(PrintStream out) {
        int bad = 0;
        List<String> urls = new ArrayList<>();
        for (Row r : ROWS) urls.add(r.url());
        for (String a : new String[]{"linux_amd64.tar.gz", "linux_arm64.tar.gz", "darwin_arm64.tar.gz", "darwin_amd64.tar.gz", "windows_amd64.zip"}) urls.add("https://github.com/mostlygeek/llama-swap/releases/download/v" + SWAP_VERSION + "/llama-swap_" + SWAP_VERSION + "_" + a);
        for (String a : new String[]{"macos-arm64.tar.gz", "macos-x64.tar.gz", "win-vulkan-x64.zip"}) urls.add("https://github.com/ggml-org/llama.cpp/releases/download/" + LLAMA_BUILD + "/llama-" + LLAMA_BUILD + "-bin-" + a);
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
    static String serviceState() {
        try {
            return switch (os) {
                case linux -> runner.run(List.of("systemctl", "--user", "is-active", UNIT)).out().strip();
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
        Matcher g = Pattern.compile("--gpus\\s+(?:'\"device=([^\"]+)\"'|(all))").matcher(server); m.put("gpus", g.find() ? (g.group(1) != null ? g.group(1) : "all") : "all");
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
            if (y.contains("\"" + EMBED_NAME + "\":")) return "";
            String embedTag = embedTagFor(os);
            if (embedTag == null) return "";   // nothing to add on this machine
            Map<String, String> m = readPlan(dir);
            if (!m.containsKey("name") || !m.containsKey("file")) return "!the model config in " + dir + " could not be read back; `researchzosho model uninstall` and `model install` again";
            Path modelFile = Path.of(m.get("file"));
            Row known = null; for (Row r : ROWS) if (r.file().equals(modelFile.getFileName().toString())) known = r;
            Row row = known != null ? known : new Row(m.get("name"), 0, "", modelFile.getFileName().toString(), "", Integer.parseInt(m.getOrDefault("ctx", "16384")), Integer.parseInt(m.getOrDefault("parallel", "1")), m.getOrDefault("extra", ""));
            String gpus = m.getOrDefault("gpus", "all"); int idle = Integer.parseInt(m.getOrDefault("idle_minutes", String.valueOf(DEFAULT_IDLE_MINUTES))); boolean share = Boolean.parseBoolean(m.getOrDefault("share", "false"));
            if (os != Os.linux) {
                Path ef = modelFile.getParent().resolve(EMBED_FILE);
                if (!Files.isRegularFile(ef)) { out.println("  downloading " + EMBED_FILE + " (about 0.6 GB) into " + modelFile.getParent()); fetchChecked(EMBED_URL, ef); }
            }
            Plan p = plan(os, row, modelFile, dir, unitPath(os), gpus, idle, share, embedTag);
            Files.writeString(dir.resolve("plan.properties"), planProperties(row, modelFile, gpus, idle, share) + "chosen=" + m.getOrDefault("chosen", known != null ? "suggested" : "yours") + "\n", StandardCharsets.UTF_8);
            Files.writeString(cfg, p.configYaml(), StandardCharsets.UTF_8);
            if (p.embedScript() != null) { Files.createDirectories(Embed.dir()); Files.writeString(dir.resolve("run-embed.sh"), p.embedScript(), StandardCharsets.UTF_8); dir.resolve("run-embed.sh").toFile().setExecutable(true); }
            // the proxy rereads its config only on a restart
            switch (os) {
                case linux -> runner.run(List.of("systemctl", "--user", "restart", UNIT));
                case macos -> { runner.run(List.of("launchctl", "bootout", "gui/" + uid() + "/" + LABEL)); runner.run(List.of("launchctl", "bootstrap", "gui/" + uid(), p.unit().toString())); }
                case windows -> { runner.run(List.of("taskkill", "/im", "llama-swap.exe", "/f")); detach.start(List.of("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", p.unit().toString())); }
            }
            String beforeEmbed = Config.stored("RESEARCHZOSHO_EMBED");
            Path prev = dir.resolve("previous");
            if (Files.exists(prev)) { String[] lines = Files.readString(prev, StandardCharsets.UTF_8).split("\n", -1); if (lines.length < 3 || lines[2].isBlank()) Files.writeString(prev, (lines.length > 0 ? lines[0] : "") + "\n" + (lines.length > 1 ? lines[1] : "") + "\n" + (beforeEmbed == null ? "" : beforeEmbed) + "\n", StandardCharsets.UTF_8); }
            Config.set("RESEARCHZOSHO_EMBED", URL);
            out.println("  the model server of this machine now carries the embeddings server (" + Embed.MODEL + ") beside the model at " + URL + "; search by meaning is on. `researchzosho rebuild` indexes what is already on the shelves with it.");
            return row.name();
        } catch (Exception e) { return "!" + e.getMessage(); }
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
            b.append("  embeddings: ").append(y.contains("\"" + EMBED_NAME + "\":") ? Embed.MODEL + " beside it at the same address, model name \"" + EMBED_NAME + "\", never idles out" : "none beside it (no card docker can use; RESEARCHZOSHO_EMBED can point elsewhere)").append('\n');
        } catch (IOException e) { b.append("  config unreadable: ").append(e.getMessage()).append('\n'); }
        boolean up = health.test(URL);
        b.append("  proxy at ").append(URL).append(": ").append(up ? "answers" : "does not answer (service " + serviceState() + "; log: " + logHint() + ")").append('\n');
        if (up) {
            try {
                HttpClient c = PROBE_HTTP;
                String running = c.send(HttpRequest.newBuilder(URI.create(URL + "/running")).timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.ofString()).body();
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
        try {
            String sib = siblingDrive();
            if (!force && URL.equals(sib)) return "!CodeZaiku's settings also point at this proxy (" + Path.of(home(), ".codezaiku", "config") + "); point it elsewhere first, or pass --force";
            switch (os) {
                case linux -> { runner.run(List.of("systemctl", "--user", "disable", "--now", UNIT)); Files.deleteIfExists(unitPath(os)); runner.run(List.of("systemctl", "--user", "daemon-reload")); }
                case macos -> { runner.run(List.of("launchctl", "bootout", "gui/" + uid() + "/" + LABEL)); Files.deleteIfExists(unitPath(os)); }
                case windows -> { runner.run(List.of("schtasks", "/delete", "/tn", TASK, "/f")); runner.run(List.of("taskkill", "/im", "llama-swap.exe", "/f")); runner.run(List.of("taskkill", "/im", "llama-server.exe", "/f")); }
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
            if (Files.isDirectory(d)) { try (var s = Files.walk(d)) { s.sorted(Comparator.reverseOrder()).forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) { } }); } }
            return "removed the service and the proxy; the model files in " + modelsDir() + " stay" + restored;
        } catch (Exception e) { return "!" + e.getMessage(); }
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
        Config.set("RESEARCHZOSHO_DRIVE", base);
        if (model != null) Config.set("RESEARCHZOSHO_MODEL", model);
        out.println("  The library now uses " + (model == null ? "the model" : model) + " at " + base + ". The service takes it for its next research run and its next question; a run going now finishes on the model it started with.");
        return 0;
    }

    /** `model …` from the command line: install [--file F] [--gpu N] [--idle-minutes N] [--share] | status | stop | uninstall. */
    public static int command(String[] a, int from, PrintStream out) {
        String op = a.length > from ? a[from] : "status";
        switch (op) {
            case "install" -> {
                Path file = null; String gpus = "all"; int idle = DEFAULT_IDLE_MINUTES; boolean share = false, own = false;
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
