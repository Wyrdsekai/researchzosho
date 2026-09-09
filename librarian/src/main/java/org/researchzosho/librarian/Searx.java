package org.researchzosho.librarian;

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
import java.util.ArrayList;
import java.util.List;

/**
 * SearXNG, run for the person through Docker: the free, private search backend. ResearchZosho does not
 * ship SearXNG (a Python service does not belong inside a Java tarball); it starts the official image
 * with a settings file that turns the JSON format on, which SearXNG has off by default and the search
 * tool needs. The container is named, restarts on boot, and keeps its settings under the state
 * directory. {@code researchzosho search start|stop|status} and the setup wizard use this.
 */
public final class Searx {

    public static final String CONTAINER = "researchzosho-searxng";
    public static final String IMAGE = "searxng/searxng";
    public static final int DEFAULT_PORT = 8888;

    /** Runs a command line; stdout+stderr and the exit code. Tests replace it. */
    public interface Runner { Result run(List<String> cmd) throws Exception; }
    public record Result(int code, String out) { }
    static volatile Runner runner = cmd -> {
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
     * what the set does today; the person can edit the file.
     */
    static String settings() {
        return "# Written by ResearchZosho. SearXNG's own defaults apply; only what ResearchZosho needs is set here.\n"
                + "# The engine set was measured from a home machine (2026-09-09): the ones off here answered with a\n"
                + "# CAPTCHA or a suspension on the first query. Edit freely; `researchzosho search test <query>` shows the result.\n"
                + "use_default_settings: true\n"
                + "server:\n"
                + "  secret_key: \"" + java.util.HexFormat.of().formatHex(java.security.SecureRandom.getSeed(24)) + "\"\n"
                + "  limiter: false\n"
                + "search:\n"
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
            boolean ours = Files.exists(settings) && Files.readString(settings).startsWith("# Written by ResearchZosho");
            if (!Files.exists(settings) || (fresh && !ours) || (fresh && ours)) {
                try { Files.writeString(settings, settings()); }
                catch (IOException e) { if (!Files.exists(settings)) return "!cannot write " + settings + ": " + e.getMessage(); settingsNote = "could not rewrite " + settings + " (" + e.getMessage() + "); using it as it is"; }
            } else if (!ours) {
                settingsNote = "using the settings.yml already in " + dir() + " (not ResearchZosho's; `researchzosho search start --fresh` replaces it with the measured engine set)";
            }
            try { dir().toFile().setWritable(true, false); settings.toFile().setWritable(true, false); } catch (Exception ignored) { }   // the container's own user writes beside it
            if (fresh && !state().equals("absent")) runner.run(List.of("docker", "rm", "-f", CONTAINER));
            String state = state();
            Result r;
            if (state.equals("absent")) {
                r = runner.run(List.of("docker", "run", "-d", "--name", CONTAINER, "--restart", "unless-stopped",
                        "-p", "127.0.0.1:" + port + ":8080", "-v", dir().toAbsolutePath() + ":/etc/searxng",
                        "-e", "BASE_URL=http://127.0.0.1:" + port + "/", IMAGE));
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

    public static String stop() {
        try {
            Result r = runner.run(List.of("docker", "stop", CONTAINER));
            return r.code() == 0 ? "stopped" : "!docker: " + r.out().strip();
        } catch (Exception e) { return "!" + e.getMessage(); }
    }

    /** Whether a SearXNG at {@code base} answers a JSON search. */
    public static boolean answers(String base) {
        try {
            HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
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
