package org.researchzosho.librarian;

import org.researchzosho.Config;
import org.researchzosho.tools.VideoText;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
/**
 * The video helper: how ResearchZosho reaches YouTube without an API key. YouTube's Data API allows a hundred searches a day, which one
 * research run can use up, so the library works the way yt-dlp does, reading the pages a browser reads. YouTube refuses that from an
 * address that does it for long (the owner's decision: never from the home connection, and do not test it), so every request leaves
 * through a Cloudflare WARP tunnel that only this helper's traffic uses — a WireGuard device registered at install, no account — and
 * carries the proof-of-origin token YouTube now asks for, made by a token provider that lives in the tunnel's network. Measured on
 * 2026-09-29: an hour at about three hundred operations (searches with later pages, channel feeds, video details, audio) with no block.
 *
 * <p>Three containers' worth of parts, two of them containers: gluetun (the tunnel, with an HTTP proxy on 127.0.0.1 that only this
 * machine reaches), the token provider (in gluetun's network, so its own requests go through the tunnel too), and yt-dlp in a Python
 * environment of the library's own, run with the proxy and the provider on every call. {@code researchzosho video install} sets all
 * three up; {@code start}, {@code stop} and {@code status} manage them; {@code test} runs one search. Nothing of YouTube's is kept
 * but what the library derives (titles, descriptions, timestamps, links, transcripts); media is deleted after it is read.
 *
 * <p>This route is against YouTube's terms of service and it breaks when YouTube changes its pages. The helper paces itself (a call
 * every twelve seconds at most, the measured rate), notices a refusal (HTTP 429, "sign in to confirm") and says so in words, with when.
 * Two other programs are downloaded at install and kept under the state directory, each under the MIT licence: wgcf (ViRb3), which
 * registers the WARP device and writes its WireGuard profile, and Deno, the JavaScript runtime yt-dlp needs for YouTube's player code.
 */
public final class Video {

    public static final String CONTAINER_VPN = "researchzosho-warp";
    public static final String CONTAINER_POT = "researchzosho-pot";
    /** gluetun's own image. The tag floats on purpose: WARP's endpoints and WireGuard move, and gluetun follows them. */
    public static final String VPN_IMAGE = "qmcgaw/gluetun:latest";
    /** The token provider, pinned: the yt-dlp plugin installed beside it must match its major version. */
    public static final String POT_IMAGE = "brainicism/bgutil-ytdlp-pot-provider:2.0.0";
    public static final String POT_PLUGIN = "bgutil-ytdlp-pot-provider==2.0.0";
    /** The HTTP proxy's port, the same inside the container and on this machine: yt-dlp hands its proxy address to the token provider. */
    public static final int PROXY_PORT = 18888;
    /** The token provider's port on this machine; inside the tunnel's network it is 4416. */
    public static final int POT_PORT = 14416;
    /** The least time between two calls to YouTube: three hundred an hour was measured without a block; this keeps under it. */
    public static final long PACE_MS = 12_000;
    /**
     * The transcription server for videos without usable captions: faster-whisper behind an OpenAI-style speech API (speaches, MIT), the
     * CPU build. Its model downloads on first use. RESEARCHZOSHO_WHISPER_MODEL names another model; the default is small enough for a
     * laptop and good enough for English and Japanese speech.
     */
    public static final String CONTAINER_WHISPER = "researchzosho-whisper";
    public static final String WHISPER_IMAGE = "ghcr.io/speaches-ai/speaches:latest-cpu";
    public static final int WHISPER_PORT = 18890;
    public static String whisperModel() { return Config.get("RESEARCHZOSHO_WHISPER_MODEL", "Systran/faster-whisper-small"); }
    /** What happened to the transcription server at the last start; "" when it came up. */
    public static volatile String whisperNote = "";

    /** Runs a command line; stdout+stderr and the exit code. Tests replace it. */
    public interface Runner { Result run(List<String> cmd, Duration timeout) throws Exception; }
    public record Result(int code, String out) { }
    /** Fetches a web address to a file. Tests replace it. */
    public interface Downloader { void fetch(String url, Path to) throws Exception; }

    public static volatile Runner runner = (cmd, timeout) -> {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        byte[] out;
        try (InputStream in = p.getInputStream()) { out = in.readAllBytes(); }
        if (!p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) { p.destroyForcibly(); return new Result(124, new String(out, StandardCharsets.UTF_8) + "\n(timed out after " + timeout.toSeconds() + " s)"); }
        return new Result(p.exitValue(), new String(out, StandardCharsets.UTF_8));
    };
    static volatile Downloader downloader = (url, to) -> {
        HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).connectTimeout(Duration.ofSeconds(20)).build();
        HttpResponse<InputStream> r = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(5))
                .header("User-Agent", "ResearchZosho (https://researchzosho.org)").GET().build(), HttpResponse.BodyHandlers.ofInputStream());
        if (r.statusCode() != 200) throw new IOException("HTTP " + r.statusCode() + " for " + url);
        Files.createDirectories(to.getParent());
        try (InputStream in = r.body()) { Files.copy(in, to, StandardCopyOption.REPLACE_EXISTING); }
    };
    private static final HttpClient PROBE = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private static final ObjectMapper J = new ObjectMapper();
    /** Whether the token provider answers; tests replace it. */
    public static volatile BooleanSupplier providerProbe = Video::providerAnswersNow;
    /** A GET through the tunnel's proxy, the body as text; tests replace it. */
    public interface Fetcher { String get(String url, Duration timeout) throws Exception; }
    public static volatile Fetcher fetcher = (url, timeout) -> {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL)
                .proxy(ProxySelector.of(new InetSocketAddress("127.0.0.1", PROXY_PORT))).build();
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(timeout)
                .header("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36").GET().build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) throw new IOException("HTTP " + r.statusCode());
        return r.body();
    };

    private Video() { }

    /** Where the helper keeps its parts: {@code <state dir>/video}. */
    public static Path dir() { return Config.home().resolve("video"); }
    static Path profile() { return dir().resolve("wgcf-profile.conf"); }
    static Path envFile() { return dir().resolve("warp.env"); }
    static Path wgcf() { return dir().resolve("bin").resolve(os().equals("windows") ? "wgcf.exe" : "wgcf"); }
    static Path deno() { return dir().resolve("bin").resolve(os().equals("windows") ? "deno.exe" : "deno"); }
    static Path ytdlp() { return dir().resolve("venv").resolve(os().equals("windows") ? "Scripts" : "bin").resolve(os().equals("windows") ? "yt-dlp.exe" : "yt-dlp"); }
    static Path blockFile() { return dir().resolve("refused.txt"); }

    public static boolean haveDocker() {
        try { return runner.run(List.of("docker", "version", "--format", "{{.Server.Version}}"), Duration.ofSeconds(20)).code() == 0; }
        catch (Exception e) { return false; }
    }

    /** "running", "stopped" or "absent" for a container. */
    public static String state(String container) {
        try {
            Result r = runner.run(List.of("docker", "inspect", "-f", "{{.State.Running}}", container), Duration.ofSeconds(20));
            if (r.code() != 0) return "absent";
            return r.out().strip().equals("true") ? "running" : "stopped";
        } catch (Exception e) { return "absent"; }
    }

    /** Whether everything is in place to run: the profile, the environment, yt-dlp. */
    /** Whether the helper is set up on this machine. Tests point it at a fixed answer. */
    public static volatile BooleanSupplier installedProbe = Video::installedNow;

    /** What a person is told when a question of the youtube field is sent on a library without the helper. */
    public static final String NOT_INSTALLED_NOTE = "This question is the youtube field's. The video helper is not installed, so the run reads YouTube "
            + "through web search only, without channel feeds, video details or transcripts. To add those: researchzosho video install (needs Docker and Python 3).";

    public static boolean installed() { return installedProbe.getAsBoolean(); }

    static boolean installedNow() {
        return Files.exists(profile()) && Files.exists(envFile()) && Files.exists(ytdlp());
    }

    /**
     * Set the helper up: the two downloaded programs, the WARP device, the tunnel's settings, yt-dlp's environment; then start it. Each
     * part is kept once made, so a second install repairs what is missing. Returns the proxy address, or "!" and what stopped it.
     */
    public static String install() {
        if (!haveDocker()) return "!docker is not on this machine (https://docs.docker.com/get-docker/)";
        try {
            Files.createDirectories(dir().resolve("bin"));
            if (!Files.exists(wgcf())) {
                String url = wgcfUrl();
                if (url == null) return "!no wgcf build is published for " + os() + "/" + arch() + "; register a WARP device by hand with wgcf and put its wgcf-profile.conf in " + dir();
                downloader.fetch(url, wgcf());
                executable(wgcf());
            }
            if (!Files.exists(profile())) {
                // wgcf keeps its account in the folder it runs in
                Result reg = runner.run(List.of(wgcf().toString(), "register", "--accept-tos", "--config", dir().resolve("wgcf-account.toml").toString()), Duration.ofMinutes(2));
                if (reg.code() != 0 && !Files.exists(dir().resolve("wgcf-account.toml"))) return "!registering a WARP device failed: " + last(reg.out());
                Result gen = runner.run(List.of(wgcf().toString(), "generate", "--config", dir().resolve("wgcf-account.toml").toString(), "--profile", profile().toString()), Duration.ofMinutes(2));
                if (gen.code() != 0 || !Files.exists(profile())) return "!writing the WireGuard profile failed: " + last(gen.out());
                privateFile(dir().resolve("wgcf-account.toml"));
                privateFile(profile());
            }
            Files.writeString(envFile(), envFromProfile(Files.readString(profile())));
            privateFile(envFile());
            // the containers read the settings when they are made: an install makes them anew
            for (String c : List.of(CONTAINER_POT, CONTAINER_VPN)) if (!state(c).equals("absent")) runner.run(List.of("docker", "rm", "-f", c), Duration.ofMinutes(1));
            if (!Files.exists(ytdlp())) {
                Path venv = dir().resolve("venv");
                Result v = runner.run(List.of(python(), "-m", "venv", venv.toString()), Duration.ofMinutes(3));
                if (v.code() != 0) return "!Python's venv could not make " + venv + ": " + last(v.out()) + " (Python 3 with venv is needed)";
                Path pip = venv.resolve(os().equals("windows") ? "Scripts" : "bin").resolve(os().equals("windows") ? "pip.exe" : "pip");
                Result p = runner.run(List.of(pip.toString(), "install", "--quiet", "--upgrade", "yt-dlp", "yt-dlp-ejs", "curl_cffi", POT_PLUGIN), Duration.ofMinutes(10));
                if (p.code() != 0) return "!installing yt-dlp into " + venv + " failed: " + last(p.out());
            }
            if (!Files.exists(deno())) {
                String url = denoUrl();
                if (url == null) return "!no Deno build is published for " + os() + "/" + arch() + "; install Deno (https://deno.com) and put it on the PATH";
                Path zip = dir().resolve("bin").resolve("deno.zip");
                downloader.fetch(url, zip);
                unzipOne(zip, deno().getFileName().toString(), deno());
                Files.deleteIfExists(zip);
                executable(deno());
            }
            return start();
        } catch (Exception e) {
            return "!" + e.getMessage();
        }
    }

    /** Start the tunnel and the token provider (creating them the first time), and wait until both answer. Returns the proxy address. */
    public static String start() {
        if (!haveDocker()) return "!docker is not on this machine (https://docs.docker.com/get-docker/)";
        if (!Files.exists(envFile())) return "!the helper is not installed: researchzosho video install";
        try {
            for (List<String> cmd : plan(state(CONTAINER_VPN), state(CONTAINER_POT))) {
                Result r = runner.run(cmd, Duration.ofMinutes(3));
                if (r.code() != 0) return "!docker: " + last(r.out());
            }
            // the transcription server is wanted, not required: a tunnel without it still searches and reads captions. Its cache folder is
            // made here, with its hub folder, and left writable: the server's own user writes the model into it and refuses an empty mount
            whisperNote = "";
            Path hub = dir().resolve("whisper").resolve("hub");
            Files.createDirectories(hub);
            try { dir().resolve("whisper").toFile().setWritable(true, false); hub.toFile().setWritable(true, false); } catch (Exception ignored) { }
            for (List<String> cmd : whisperPlan(state(CONTAINER_WHISPER))) {
                Result r = runner.run(cmd, Duration.ofMinutes(10));
                if (r.code() != 0) whisperNote = "the transcription server did not start (docker: " + last(r.out()) + "); captions still work";
            }
            String proxy = "http://127.0.0.1:" + PROXY_PORT;
            for (int i = 0; i < 45; i++) {
                if (tunnelAnswers() && providerAnswers()) return proxy;
                Thread.sleep(2000);
            }
            return "!the containers started but the tunnel or the token provider did not answer within 90 seconds (docker logs " + CONTAINER_VPN + ", docker logs " + CONTAINER_POT + ")";
        } catch (Exception e) {
            return "!" + e.getMessage();
        }
    }

    /** The docker command lines {@link #start} runs for the given states, for a test to read without docker. */
    static List<List<String>> plan(String vpnState, String potState) {
        List<List<String>> out = new ArrayList<>();
        if (vpnState.equals("absent")) {
            out.add(List.of("docker", "run", "-d", "--name", CONTAINER_VPN, "--restart", "unless-stopped",
                    "--cap-add", "NET_ADMIN", "--device", "/dev/net/tun:/dev/net/tun",
                    "-p", "127.0.0.1:" + PROXY_PORT + ":" + PROXY_PORT, "-p", "127.0.0.1:" + POT_PORT + ":4416",
                    "--env-file", envFile().toAbsolutePath().toString(), VPN_IMAGE));
        } else if (vpnState.equals("stopped")) {
            out.add(List.of("docker", "start", CONTAINER_VPN));
        }
        if (potState.equals("absent") || vpnState.equals("absent")) {
            // the provider lives in the tunnel's network, so it is made anew whenever the tunnel is
            if (!potState.equals("absent")) out.add(List.of("docker", "rm", "-f", CONTAINER_POT));
            out.add(List.of("docker", "run", "-d", "--name", CONTAINER_POT, "--restart", "unless-stopped",
                    "--network", "container:" + CONTAINER_VPN, POT_IMAGE, "--host", "0.0.0.0"));
        } else if (potState.equals("stopped")) {
            out.add(List.of("docker", "start", CONTAINER_POT));
        }
        return out;
    }

    /** The docker command lines that bring the transcription server up for its state: it has its own network (it never calls out). */
    static List<List<String>> whisperPlan(String state) {
        if (state.equals("absent")) return List.of(List.of("docker", "run", "-d", "--name", CONTAINER_WHISPER, "--restart", "unless-stopped",
                "-p", "127.0.0.1:" + WHISPER_PORT + ":8000", "-v", dir().resolve("whisper").toAbsolutePath() + ":/home/ubuntu/.cache/huggingface", WHISPER_IMAGE));
        if (state.equals("stopped")) return List.of(List.of("docker", "start", CONTAINER_WHISPER));
        return List.of();
    }

    /** Whether the transcription server answers. */
    public static boolean transcriptionReady() {
        try {
            HttpResponse<String> r = PROBE.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + WHISPER_PORT + "/health")).timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
            return r.statusCode() == 200;
        } catch (Exception e) { return false; }
    }

    /** Transcribes an audio file; tests replace it. Returns the server's verbose JSON (segments with start times and text). */
    public interface Transcriber { String transcribe(Path audio, String language) throws Exception; }
    static volatile Transcriber transcriber = Video::transcribeNow;

    /**
     * What is said in a video, as timed lines, from its audio: the audio is fetched through the tunnel, given to the transcription
     * server, and deleted. Null when the server is not there or the audio could not be had; empty when it heard nothing.
     */
    public static List<VideoText.Line> transcribe(String videoId, String language) {
        if (!transcriptionReady() || !whisperModelReady()) return null;
        Path audio = dir().resolve("tmp").resolve(videoId + ".audio");
        try {
            Files.createDirectories(audio.getParent());
            Result r = ytdlp(List.of("-f", "bestaudio[filesize<120M]/bestaudio", "-o", audio.toString(), "https://www.youtube.com/watch?v=" + videoId), Duration.ofMinutes(10));
            if (r.code() != 0 || !Files.exists(audio)) return null;
            String json = transcriber.transcribe(audio, language);
            List<VideoText.Line> lines = new ArrayList<>();
            for (JsonNode s : J.readTree(json).path("segments")) {
                String t = s.path("text").asText("").strip();
                if (!t.isEmpty()) lines.add(new VideoText.Line((int) s.path("start").asDouble(0), t));
            }
            return lines;
        } catch (Exception e) {
            return null;
        } finally {
            try { Files.deleteIfExists(audio); } catch (IOException ignored) { }
        }
    }

    private static volatile boolean modelKnownReady = false;

    /** Whether the server holds the model; it is asked to download it the first time (about a minute for the small one), and that is remembered. */
    static boolean whisperModelReady() {
        if (modelKnownReady) return true;
        try {
            HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            String base = "http://127.0.0.1:" + WHISPER_PORT;
            HttpResponse<String> list = http.send(HttpRequest.newBuilder(URI.create(base + "/v1/models")).timeout(Duration.ofSeconds(20)).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (list.statusCode() == 200 && list.body().contains("\"" + whisperModel() + "\"")) return modelKnownReady = true;
            HttpResponse<String> pull = http.send(HttpRequest.newBuilder(URI.create(base + "/v1/models/" + whisperModel())).timeout(Duration.ofMinutes(20))
                    .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            return modelKnownReady = pull.statusCode() == 200 || pull.statusCode() == 201;
        } catch (Exception e) { return false; }
    }

    private static String transcribeNow(Path audio, String language) throws Exception {
        String boundary = "----researchzosho" + Long.toHexString(System.nanoTime());
        byte[] file = Files.readAllBytes(audio);
        StringBuilder head = new StringBuilder();
        String lang = language == null || language.isBlank() ? "" : language.replaceAll("-.*$", "");
        for (String[] f : new String[][]{{"model", whisperModel()}, {"response_format", "verbose_json"}, {"language", lang}}) {
            if (f[1].isEmpty()) continue;
            head.append("--").append(boundary).append("\r\nContent-Disposition: form-data; name=\"").append(f[0]).append("\"\r\n\r\n").append(f[1]).append("\r\n");
        }
        head.append("--").append(boundary).append("\r\nContent-Disposition: form-data; name=\"file\"; filename=\"audio\"\r\nContent-Type: application/octet-stream\r\n\r\n");
        byte[] h = head.toString().getBytes(StandardCharsets.UTF_8), tail = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] body = new byte[h.length + file.length + tail.length];
        System.arraycopy(h, 0, body, 0, h.length); System.arraycopy(file, 0, body, h.length, file.length); System.arraycopy(tail, 0, body, h.length + file.length, tail.length);
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + WHISPER_PORT + "/v1/audio/transcriptions")).timeout(Duration.ofMinutes(30))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary).POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) throw new IOException("the transcription server answered HTTP " + r.statusCode() + ": " + last(r.body()));
        return r.body();
    }

    public static String stop() {
        try {
            runner.run(List.of("docker", "stop", CONTAINER_WHISPER), Duration.ofMinutes(1));
            Result a = runner.run(List.of("docker", "stop", CONTAINER_POT), Duration.ofMinutes(1));
            Result b = runner.run(List.of("docker", "stop", CONTAINER_VPN), Duration.ofMinutes(1));
            return a.code() == 0 && b.code() == 0 ? "stopped" : "!docker: " + last((a.code() == 0 ? b : a).out());
        } catch (Exception e) { return "!" + e.getMessage(); }
    }

    /** Whether YouTube answers through the tunnel's proxy. */
    public static boolean tunnelAnswers() {
        try {
            HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                    .proxy(ProxySelector.of(new InetSocketAddress("127.0.0.1", PROXY_PORT))).build();
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create("https://www.youtube.com/robots.txt")).timeout(Duration.ofSeconds(15)).GET().build(), HttpResponse.BodyHandlers.ofString());
            return r.statusCode() == 200;
        } catch (Exception e) { return false; }
    }

    /** Whether the token provider answers on this machine. */
    public static boolean providerAnswers() { return providerProbe.getAsBoolean(); }

    private static boolean providerAnswersNow() {
        try {
            HttpResponse<String> r = PROBE.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + POT_PORT + "/ping")).timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
            return r.statusCode() == 200;
        } catch (Exception e) { return false; }
    }

    /**
     * gluetun's settings from a WireGuard profile as wgcf writes it: the tunnel is a custom WireGuard one, the HTTP proxy is on, and the
     * token provider's port is let in. The private key is in this file, which is why it is kept readable by its owner alone.
     */
    static String envFromProfile(String conf) { return envFromProfile(conf, Video::ipv4Of); }

    /** WARP's WireGuard endpoint when the name cannot be resolved: Cloudflare's anycast address for it, the one the trial used. */
    static final String WARP_ENDPOINT_IP = "162.159.192.1";

    /** The IPv4 address of a host, or null. gluetun's custom WireGuard takes an address, and wgcf writes a name. */
    static String ipv4Of(String host) {
        try {
            for (InetAddress a : InetAddress.getAllByName(host)) if (a instanceof Inet4Address) return a.getHostAddress();
        } catch (Exception ignored) { }
        return null;
    }

    static String envFromProfile(String conf, Function<String, String> resolveV4) {
        Map<String, String> p = new LinkedHashMap<>();
        for (String line : conf.split("\\R")) {
            int eq = line.indexOf('=');
            if (eq > 0) p.put(line.substring(0, eq).strip(), line.substring(eq + 1).strip());
        }
        String endpoint = p.getOrDefault("Endpoint", "engage.cloudflareclient.com:2408");
        int colon = endpoint.lastIndexOf(':');
        String host = colon > 0 ? endpoint.substring(0, colon) : endpoint, port = colon > 0 ? endpoint.substring(colon + 1) : "2408";
        String addresses = p.getOrDefault("Address", "");
        // gluetun wants the IPv4 address of the device; wgcf writes IPv4 and IPv6 together
        String v4 = "";
        for (String a : addresses.split(",")) if (a.contains(".")) v4 = a.strip();
        StringBuilder sb = new StringBuilder();
        sb.append("# Written by ResearchZosho for the video helper: a Cloudflare WARP device as a custom WireGuard tunnel.\n");
        sb.append("VPN_SERVICE_PROVIDER=custom\nVPN_TYPE=wireguard\n");
        sb.append("WIREGUARD_PRIVATE_KEY=").append(p.getOrDefault("PrivateKey", "")).append('\n');
        sb.append("WIREGUARD_PUBLIC_KEY=").append(p.getOrDefault("PublicKey", "")).append('\n');
        sb.append("WIREGUARD_ADDRESSES=").append(v4.isEmpty() ? addresses : v4).append('\n');
        String ip = host.matches("[0-9.]+") ? host : resolveV4.apply(host);
        sb.append("WIREGUARD_ENDPOINT_IP=").append(ip == null || ip.isBlank() ? WARP_ENDPOINT_IP : ip).append('\n');
        sb.append("WIREGUARD_ENDPOINT_PORT=").append(port).append('\n');
        sb.append("WIREGUARD_MTU=").append(p.getOrDefault("MTU", "1280")).append('\n');
        sb.append("HTTPPROXY=on\nHTTPPROXY_LISTENING_ADDRESS=:").append(PROXY_PORT).append('\n');
        sb.append("FIREWALL_INPUT_PORTS=4416\nDNS_SERVER=on\nTZ=UTC\n");
        return sb.toString();
    }

    private static volatile long lastCallAt = 0;

    /**
     * One yt-dlp call through the tunnel with the token provider: {@code args} are yt-dlp's own. Refuses to run when the provider does not
     * answer, so that no request ever leaves from this machine's own address. Paced: a call waits until {@value #PACE_MS} ms have passed
     * since the last. A refusal from YouTube is noted with the time, for {@link #refusal()}.
     */
    public static Result ytdlp(List<String> args, Duration timeout) {
        if (!installed()) return new Result(3, "the video helper is not installed: researchzosho video install");
        if (!providerAnswers()) return new Result(3, "the video helper is not running (its token provider does not answer): researchzosho video start");
        pace();
        List<String> cmd = new ArrayList<>(command());
        cmd.addAll(args);
        try {
            Result r = runWithPath(cmd, timeout);
            if (refused(r.out())) noteRefusal(r.out());
            return r;
        } catch (Exception e) {
            return new Result(1, "yt-dlp could not be run: " + e.getMessage());
        }
    }

    /**
     * One page fetched through the tunnel — a caption track, a channel's feed — under the same rules as a yt-dlp call: the provider
     * must answer (the tunnel is up), the pace is kept, a refusal is noted. Returns null when the page could not be had.
     */
    public static String fetchThroughTunnel(String url, Duration timeout) {
        if (!installed() || !providerAnswers()) return null;
        pace();
        try { return fetcher.get(url, timeout); }
        catch (Exception e) {
            if (refused(e.getMessage())) noteRefusal(e.getMessage());
            return null;
        }
    }

    /** The pace, in milliseconds; a test with a stand-in for yt-dlp sets it to nothing. */
    public static volatile long paceMs = PACE_MS;

    /**
     * One call every twelve seconds across every process on the machine, not only this one: the service's research run and a
     * {@code researchzosho youtube …} typed by hand share one tunnel and one address at YouTube. The last call's time lives in a
     * file in the helper's folder, held under a file lock while the wait is measured and the new time written.
     */
    private static void pace() {
        synchronized (Video.class) {
            Path stamp = dir().resolve("pace");
            try (FileChannel ch = FileChannel.open(stamp, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE); FileLock lock = ch.lock()) {
                long last = lastCallAt;
                ByteBuffer buf = ByteBuffer.allocate(32);
                int n = ch.read(buf, 0);
                if (n > 0) { try { last = Math.max(last, Long.parseLong(new String(buf.array(), 0, n, StandardCharsets.US_ASCII).strip())); } catch (NumberFormatException ignored) { } }
                long wait = last + paceMs - System.currentTimeMillis();
                if (wait > 0) { try { Thread.sleep(wait); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
                lastCallAt = System.currentTimeMillis();
                ch.truncate(0);
                ch.write(ByteBuffer.wrap(Long.toString(lastCallAt).getBytes(StandardCharsets.US_ASCII)), 0);
            } catch (IOException e) {
                // no folder or no lock: pace within this process alone, as before
                long wait = lastCallAt + paceMs - System.currentTimeMillis();
                if (wait > 0) { try { Thread.sleep(wait); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); } }
                lastCallAt = System.currentTimeMillis();
            }
        }
    }

    /** The start of every yt-dlp command: no settings of the person's own, the tunnel's proxy, the token provider. */
    static List<String> command() {
        return List.of(ytdlp().toString(), "--ignore-config", "--no-warnings",
                "--proxy", "http://127.0.0.1:" + PROXY_PORT,
                "--extractor-args", "youtubepot-bgutilhttp:base_url=http://127.0.0.1:" + POT_PORT);
    }

    /** Deno sits beside the helper, not on the person's PATH: it is put in front for the call. */
    private static Result runWithPath(List<String> cmd, Duration timeout) throws Exception {
        if (runner != DEFAULT) return runner.run(cmd, timeout);
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        pb.environment().put("PATH", dir().resolve("bin") + java.io.File.pathSeparator + pb.environment().getOrDefault("PATH", ""));
        Process p = pb.start();
        byte[] out;
        try (InputStream in = p.getInputStream()) { out = in.readAllBytes(); }
        if (!p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) { p.destroyForcibly(); return new Result(124, new String(out, StandardCharsets.UTF_8) + "\n(timed out after " + timeout.toSeconds() + " s)"); }
        return new Result(p.exitValue(), new String(out, StandardCharsets.UTF_8));
    }
    private static final Runner DEFAULT = runner;

    /** YouTube's ways of saying no to this route. */
    public static boolean refused(String out) {
        if (out == null) return false;
        String o = out.toLowerCase(Locale.ROOT);
        return o.contains("http error 429") || o.contains("http 429") || o.contains("sign in to confirm") || o.contains("too many requests") || o.contains("this helps protect our community");
    }

    private static void noteRefusal(String out) {
        try {
            Files.createDirectories(dir());
            Files.writeString(blockFile(), Instant.now() + "\n" + last(out) + "\n");
        } catch (IOException ignored) { }
    }

    /** The last refusal YouTube gave this route, in words, or "" when there has been none. */
    public static String refusal() {
        try {
            if (!Files.exists(blockFile())) return "";
            String[] lines = Files.readString(blockFile()).split("\\R", 2);
            return "YouTube refused the keyless route at " + lines[0] + (lines.length > 1 ? " (" + lines[1].strip() + ")" : "")
                    + ". Searches and details may fail until the tunnel's address changes: researchzosho video stop, then start.";
        } catch (IOException e) { return ""; }
    }

    /** One search, as a test of the whole route: the titles YouTube returns for a query. */
    public static String test(String query, int n) {
        Result r = ytdlp(List.of("--flat-playlist", "--dump-single-json", "--playlist-end", String.valueOf(n), "ytsearch" + n + ":" + query), Duration.ofMinutes(3));
        if (r.code() != 0) return "!" + last(r.out());
        try {
            JsonNode j = J.readTree(r.out().substring(r.out().indexOf('{')));
            StringBuilder sb = new StringBuilder();
            for (JsonNode e : j.path("entries")) sb.append("- ").append(e.path("title").asText("?")).append("  (").append(e.path("channel").asText(e.path("uploader").asText("?"))).append(", ").append(e.path("url").asText("")).append(")\n");
            return sb.length() == 0 ? "!the search answered with no entries" : sb.toString().strip();
        } catch (Exception e) {
            return "!the search answered with something that is not JSON: " + last(r.out());
        }
    }

    /** What the helper's parts are doing, one line each. */
    public static String status() {
        StringBuilder sb = new StringBuilder();
        sb.append("  installed: ").append(installed() ? "yes (" + dir() + ")" : "no — researchzosho video install").append('\n');
        sb.append("  tunnel (").append(CONTAINER_VPN).append("): ").append(state(CONTAINER_VPN)).append(state(CONTAINER_VPN).equals("running") ? (tunnelAnswers() ? ", YouTube answers through it" : ", YouTube does not answer through it") : "").append('\n');
        sb.append("  token provider (").append(CONTAINER_POT).append("): ").append(state(CONTAINER_POT)).append(state(CONTAINER_POT).equals("running") ? (providerAnswers() ? ", answers" : ", does not answer") : "").append('\n');
        sb.append("  yt-dlp: ").append(Files.exists(ytdlp()) ? ytdlp().toString() : "not installed").append('\n');
        String ws = state(CONTAINER_WHISPER);
        sb.append("  transcription (").append(CONTAINER_WHISPER).append(", ").append(whisperModel()).append("): ").append(ws).append(ws.equals("running") ? (transcriptionReady() ? ", answers" : ", not answering yet (its model downloads on first use)") : "").append('\n');
        String refusal = refusal();
        if (!refusal.isEmpty()) sb.append("  ").append(refusal).append('\n');
        return sb.toString();
    }

    // ── the two downloaded programs ──────────────────────────────────────────────────────────────────────────────────────────────

    static String os() {
        String n = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return n.contains("win") ? "windows" : n.contains("mac") ? "darwin" : "linux";
    }

    static String arch() {
        String a = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        return a.contains("aarch64") || a.contains("arm64") ? "arm64" : "amd64";
    }

    private static String python() { return os().equals("windows") ? "python" : "python3"; }

    /** The wgcf release for this machine, found through GitHub's release listing so that a new version needs no new ResearchZosho. */
    static String wgcfUrl() throws Exception {
        String want = "_" + os() + "_" + arch() + (os().equals("windows") ? ".exe" : "");
        Path listing = dir().resolve("bin").resolve("wgcf-release.json");
        downloader.fetch("https://api.github.com/repos/ViRb3/wgcf/releases/latest", listing);
        JsonNode j = J.readTree(Files.readString(listing));
        Files.deleteIfExists(listing);
        for (JsonNode a : j.path("assets")) if (a.path("name").asText("").endsWith(want)) return a.path("browser_download_url").asText();
        return null;
    }

    static String denoUrl() {
        String target = switch (os() + "/" + arch()) {
            case "linux/amd64" -> "x86_64-unknown-linux-gnu";
            case "linux/arm64" -> "aarch64-unknown-linux-gnu";
            case "darwin/amd64" -> "x86_64-apple-darwin";
            case "darwin/arm64" -> "aarch64-apple-darwin";
            case "windows/amd64" -> "x86_64-pc-windows-msvc";
            default -> null;
        };
        return target == null ? null : "https://github.com/denoland/deno/releases/latest/download/deno-" + target + ".zip";
    }

    private static void unzipOne(Path zip, String name, Path to) throws IOException {
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
            for (ZipEntry e; (e = in.getNextEntry()) != null; ) {
                if (Path.of(e.getName()).getFileName().toString().equals(name)) { Files.copy(in, to, StandardCopyOption.REPLACE_EXISTING); return; }
            }
        }
        throw new IOException(name + " is not in " + zip);
    }

    private static void executable(Path p) {
        try { Files.setPosixFilePermissions(p, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_EXECUTE)); }
        catch (Exception ignored) { }
    }

    private static void privateFile(Path p) {
        try { Files.setPosixFilePermissions(p, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)); }
        catch (Exception ignored) { }
    }

    private static String last(String out) {
        if (out == null) return "";
        String[] lines = out.strip().split("\\R");
        String l = lines[lines.length - 1].strip();
        return l.length() > 300 ? l.substring(0, 300) + "…" : l;
    }
}
