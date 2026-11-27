package org.researchzosho.librarian;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every file the model install fetches by a pin is pinned to a revision of its repository too (0.8.4). On 2026-11-11 the publisher
 * replaced the 27B's file on main under the same name; every release until then asked main, downloaded 16.5 GB, refused it at the
 * checksum and deleted it. Here: the pins themselves, the install against a stand-in Hugging Face on the loopback (the address it asks,
 * a download of other bytes, a file of another size at the model's name, an unfinished download from before), and the release's check
 * ({@code model check}) against the same stand-in. Nothing here reaches the network or runs a tool of the machine.
 */
class ModelPinTest {
    final ModelServer.Os realOs = ModelServer.os;
    final ModelServer.Runner realRunner = ModelServer.runner;
    final ModelServer.Downloader realDownloader = ModelServer.downloader;
    final Predicate<String> realHealth = ModelServer.health;
    final Function<String, ModelServer.Served> realServedAt = ModelServer.servedAt;
    final Function<String, String> realReach = ModelServer.reach;
    final Map<String, String> realSums = new HashMap<>(ModelServer.sums);
    final Map<String, Long> realSizes = new HashMap<>(ModelServer.sizes);
    final long realBackoff = ModelServer.checkBackoffMs;
    final String realHome = System.getProperty("user.home");

    static final ModelServer.Row BIG = ModelServer.ROWS.get(0);
    static final String AUGUST = "4ca720788d1e01f1bff70c033e0d0028fd02e502";
    static final byte[] PINNED = "the file the row was measured on".getBytes(StandardCharsets.UTF_8);
    static final byte[] SAME_SIZE = "a new upload under the same name".getBytes(StandardCharsets.UTF_8);   // as long as PINNED, other bytes
    static final byte[] LONGER = "a newer quantization under the same name, and longer".getBytes(StandardCharsets.UTF_8);

    HttpServer hf;
    String base;
    /** What the stand-in serves, by path: a status, its headers, its body. */
    record Answer(int status, Map<String, String> headers, byte[] body) { }
    final Map<String, Answer> served = new ConcurrentHashMap<>();
    /** Every request it saw: "GET /path", and "Range" when one was asked. */
    final List<String> seen = new CopyOnWriteArrayList<>();
    final Map<String, Integer> tooMany = new ConcurrentHashMap<>();   // a path answered 429 this many more times

    @BeforeEach void standIn() throws IOException {
        hf = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        hf.createContext("/", x -> {
            String path = x.getRequestURI().getPath();
            seen.add(x.getRequestMethod() + " " + path + (x.getRequestHeaders().containsKey("Range") ? " Range" : ""));
            Integer left = tooMany.get(path);
            if (left != null && left > 0) { tooMany.put(path, left - 1); x.sendResponseHeaders(429, -1); x.close(); return; }
            Answer a = served.get(path);
            if (a == null) { x.sendResponseHeaders(404, -1); x.close(); return; }
            a.headers().forEach((k, v) -> x.getResponseHeaders().set(k, v));
            boolean head = x.getRequestMethod().equals("HEAD");
            if (head) { if (!a.headers().containsKey("Content-Length")) x.getResponseHeaders().set("Content-Length", String.valueOf(a.body().length)); x.sendResponseHeaders(a.status(), -1); }
            else { x.sendResponseHeaders(a.status(), a.body().length == 0 ? -1 : a.body().length); x.getResponseBody().write(a.body()); }
            x.close();
        });
        hf.start();
        base = "http://127.0.0.1:" + hf.getAddress().getPort();
        ModelServer.reach = url -> url.replace("https://huggingface.co/", base + "/hf/").replace("https://api.github.com/", base + "/api/")
                .replace("https://github.com/", base + "/gh/").replace("https://raw.githubusercontent.com/", base + "/raw/");
        ModelServer.checkBackoffMs = 0;
    }

    @AfterEach void back() {
        hf.stop(0);
        ModelServer.os = realOs; ModelServer.runner = realRunner; ModelServer.downloader = realDownloader; ModelServer.health = realHealth; ModelServer.servedAt = realServedAt;
        ModelServer.reach = realReach; ModelServer.checkBackoffMs = realBackoff;
        ModelServer.sums.clear(); ModelServer.sums.putAll(realSums); ModelServer.sizes.clear(); ModelServer.sizes.putAll(realSizes);
        System.setProperty("user.home", realHome);
        Config.invalidate();
    }

    /** The path the stand-in serves an address at. */
    String at(String url) { return URI.create(ModelServer.reach.apply(url)).getPath(); }

    void serve(String url, byte[] body) { served.put(at(url), new Answer(200, Map.of(), body)); }

    // ---- the pins ----

    @Test void everyRowNamesARevisionAShaAndASizeAndItsAddressIsAtTheRevision() {
        for (ModelServer.Row r : ModelServer.ROWS) {
            assertTrue(r.revision().matches("[0-9a-f]{40}"), r.name() + ": a commit, not a branch: " + r.revision());
            assertTrue(r.sha256().matches("[0-9a-f]{64}"), r.name());
            assertTrue(r.bytes() > 1_000_000_000L, r.name() + ": " + r.bytes());
            assertEquals("https://huggingface.co/" + r.hf() + "/resolve/" + r.revision() + "/" + r.file(), r.url());
            assertEquals("https://huggingface.co/" + r.hf() + "/resolve/" + r.revision() + "/mmproj-F16.gguf", r.visionUrl());
            assertEquals(r.sha256(), ModelServer.sums.get(r.file()));
            assertEquals(r.bytes(), ModelServer.sizes.get(r.file()));
        }
        assertEquals(AUGUST, BIG.revision(), "the 27B stays on the August file every 27B number was measured on");
        assertEquals("322e194ff79741c7baa497c240f677f54b201b0efab44ca8e50f122b39123482", BIG.sha256());
        assertEquals(16_464_440_224L, BIG.bytes());
        assertEquals(" (about 16 GB)", ModelServer.sizeNote(BIG), "from the row: nothing is asked of anyone");
    }

    @Test void everyPinnedFileIsAtARevisionWithAShaAndASize() {
        List<ModelServer.Pinned> all = ModelServer.pinnedFiles();
        int onHuggingFace = 0;
        for (ModelServer.Pinned p : all) {
            assertTrue(p.sha256().matches("[0-9a-f]{64}"), p.url());
            assertTrue(p.bytes() > 0, p.url());
            assertFalse(p.url().contains("/resolve/main/") || p.url().contains("/main/"), "no pin names a branch: " + p.url());
            if (p.url().startsWith("https://huggingface.co/")) {
                onHuggingFace++;
                assertTrue(ModelServer.AT_REVISION.matcher(p.url()).find(), p.url());
                assertTrue(p.mainUrl().contains("/resolve/main/"), p.mainUrl());
            } else assertTrue(p.url().matches("https://raw\\.githubusercontent\\.com/[^/]+/[^/]+/[0-9a-f]{40}/.+"), "elsewhere at a commit: " + p.url());
        }
        // six rows, five picture files, the embeddings model twice over (one file, six files), the comic helper's eighteen
        assertEquals(6 + 5 + 1 + 6 + 18, onHuggingFace, all.stream().map(ModelServer.Pinned::url).toList().toString());
        assertTrue(ModelServer.EMBED_URL.startsWith("https://huggingface.co/Qwen/Qwen3-Embedding-0.6B-GGUF/resolve/d20cf9c16f82914a21dbd9c645f56895fb1d7750/"), ModelServer.EMBED_URL);
        for (String f : Embed.FILES.keySet()) assertEquals(Embed.SIZES.get(f), ModelServer.sizes.get(Embed.FOLDER + "/" + f), f);
        assertEquals(Embed.FILES.keySet(), Embed.SIZES.keySet());
    }

    @Test void theGuidesServingLineNamesTheRevisionForAModelTheInstallInstalls() {
        assertEquals(BIG.url(), Models.address(BIG.hf(), BIG.file()));
        assertEquals("https://huggingface.co/unsloth/gemma-4-12b-it-GGUF/resolve/main/gemma-4-12b-it-Q4_K_M.gguf", Models.address("unsloth/gemma-4-12b-it-GGUF", "gemma-4-12b-it-Q4_K_M.gguf"), "a file no row pins");
    }

    // ---- the install, against the stand-in ----

    /** A Linux machine with a 24 GB card: the 27B's row. Only the model's download is reached in these cases; every other tool fails. */
    Path machine(Path home) throws IOException {
        System.setProperty("user.home", home.toString());
        Config.invalidate();
        QuietHome.quiet(home);
        ModelServer.os = ModelServer.Os.linux;
        ModelServer.health = b -> false;
        ModelServer.servedAt = b -> null;
        ModelServer.runner = cmd -> {
            String c = cmd.get(0);
            if (c.equals("nvidia-smi")) return new ModelServer.Result(0, "Stand-in Card, 24576\n");
            if (c.equals("docker") && cmd.contains("info")) return new ModelServer.Result(0, "map[nvidia:{} runc:{}]\n");
            if (c.equals("sh")) return new ModelServer.Result(0, String.valueOf(64L << 30) + "\n");
            return new ModelServer.Result(1, "not on this stand-in machine");
        };
        // the machine's curl, in Java: what the stand-in serves at the address, to the path it is given; a failure leaves what came
        ModelServer.downloader = (url, dest) -> {
            HttpResponse<InputStream> r = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(ModelServer.reach.apply(url))).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
            if (r.statusCode() != 200) throw new IOException("download failed: " + url);
            try (InputStream in = r.body()) { Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING); }
        };
        assertEquals(BIG.name(), ModelServer.rowFor(ModelServer.budgetGb()).name());
        return home.resolve("models");
    }

    String install(ByteArrayOutputStream out) { return ModelServer.install(null, null, "all", 10, false, true, new PrintStream(out, true)); }

    @Test void theInstallAsksForTheFileAtTheRowsRevisionAndNeverAtMain(@TempDir Path home) throws Exception {
        Path models = machine(home);
        ModelServer.sums.put(BIG.file(), ModelServer.sha256(PINNED)); ModelServer.sizes.put(BIG.file(), (long) PINNED.length);   // the stand-in's bytes stand for the August file
        serve(BIG.url(), PINNED);
        serve(BIG.url().replace("/resolve/" + AUGUST + "/", "/resolve/main/"), LONGER);   // main has moved on, as Hugging Face's has
        var out = new ByteArrayOutputStream();
        String r = install(out);
        assertArrayEquals(PINNED, Files.readAllBytes(models.resolve(BIG.file())), "the pinned file is in place: " + r + "\n" + out);
        assertTrue(seen.contains("GET /hf/unsloth/Qwen3.8-27B-GGUF/resolve/" + AUGUST + "/Qwen3.8-27B-UD-Q4_K_M.gguf"), seen.toString());
        assertTrue(seen.stream().noneMatch(s -> s.contains("/resolve/main/")), "main is never asked: " + seen);
        assertFalse(Files.exists(models.resolve(BIG.file() + ".part")));
        assertTrue(out.toString().contains("Downloading the model Qwen3.8-27B-UD-Q4_K_M.gguf (about 16 GB)"), out.toString());
        assertTrue(seen.contains("GET /hf/unsloth/Qwen3.8-27B-GGUF/resolve/" + AUGUST + "/mmproj-F16.gguf"), "the picture file at the same revision: " + seen);
    }

    @Test void aDownloadOfOtherBytesIsRefusedBeforeItIsPutInPlaceAndNothingIsLeft(@TempDir Path home) throws Exception {
        Path models = machine(home);
        ModelServer.sums.put(BIG.file(), ModelServer.sha256(PINNED)); ModelServer.sizes.put(BIG.file(), (long) PINNED.length);
        serve(BIG.url(), SAME_SIZE);   // the right size and other bytes: the sha256 refuses it
        List<Path> written = new ArrayList<>();
        ModelServer.Downloader http = ModelServer.downloader;
        ModelServer.downloader = (url, dest) -> { written.add(dest); http.fetch(url, dest); assertFalse(Files.exists(models.resolve(BIG.file())), "nothing at the model's name while it is unchecked"); };
        String r = install(new ByteArrayOutputStream());
        assertTrue(r.startsWith("!checksum mismatch for Qwen3.8-27B-UD-Q4_K_M.gguf; refusing to install, and it was not kept"), r);
        assertTrue(r.contains("from https://huggingface.co/unsloth/Qwen3.8-27B-GGUF/resolve/" + AUGUST + "/"), r);
        assertEquals(List.of(models.resolve(BIG.file() + ".part")), written, "the download goes to the .part, never to the model's name");
        try (var ls = Files.list(models)) { assertEquals(List.of(), ls.toList(), "nothing is left: not the model, not the download"); }

        serve(BIG.url(), LONGER);   // another size: refused without being hashed
        r = install(new ByteArrayOutputStream());
        assertTrue(r.startsWith("!the downloaded Qwen3.8-27B-UD-Q4_K_M.gguf is " + LONGER.length + " bytes, not the " + PINNED.length + " of the file this version installs; refusing to install it, and it was not kept"), r);
        try (var ls = Files.list(models)) { assertEquals(List.of(), ls.toList()); }
    }

    @Test void aFileOfAnotherSizeAtTheModelsNameIsNotTakenForTheModelAndIsLeftAsItIs(@TempDir Path home) throws Exception {
        Path models = machine(home);
        Files.createDirectories(models);
        Files.write(models.resolve(BIG.file()), LONGER);   // a whole file of other bytes: a stop in an earlier release's check, or one fetched by hand from main
        serve(BIG.url(), PINNED);
        var out = new ByteArrayOutputStream();
        String r = install(out);
        assertTrue(r.startsWith("!Qwen3.8-27B-UD-Q4_K_M.gguf in " + models + " is not the file this version installs: it is " + LONGER.length + " bytes, and that file is 16,464,440,224."), r);
        assertTrue(r.contains("It is left as it is.") && r.contains("researchzosho model install --file " + models.resolve(BIG.file())), r);
        assertFalse(out.toString().contains("already in"), "not taken for the model: " + out);
        assertArrayEquals(LONGER, Files.readAllBytes(models.resolve(BIG.file())), "and left as it was");
        assertEquals(List.of(), seen, "nothing is downloaded over it");
    }

    @Test void anUnfinishedDownloadFromBeforeIsSaidAndStartedAgainFromTheBeginning(@TempDir Path home) throws Exception {
        Path models = machine(home);
        ModelServer.sums.put(BIG.file(), ModelServer.sha256(PINNED)); ModelServer.sizes.put(BIG.file(), (long) PINNED.length);
        Files.createDirectories(models);
        Files.write(models.resolve(BIG.file() + ".part"), "a newer quantization, cut short".getBytes(StandardCharsets.UTF_8));   // a stop during an earlier release's download of main
        serve(BIG.url(), PINNED);
        var out = new ByteArrayOutputStream();
        install(out);
        assertTrue(out.toString().contains("  An unfinished download of Qwen3.8-27B-UD-Q4_K_M.gguf from an earlier try is in " + models + ". It cannot be continued, so the download starts again from the beginning."), out.toString());
        assertTrue(seen.contains("GET /hf/unsloth/Qwen3.8-27B-GGUF/resolve/" + AUGUST + "/Qwen3.8-27B-UD-Q4_K_M.gguf"), "from the beginning, no range: " + seen);
        assertArrayEquals(PINNED, Files.readAllBytes(models.resolve(BIG.file())));
        assertFalse(Files.exists(models.resolve(BIG.file() + ".part")), "nothing of the old download is left");
    }

    @Test void aDownloadThatFailsPartWayLeavesNothing(@TempDir Path home) throws Exception {
        Path models = machine(home);
        ModelServer.downloader = (url, dest) -> { Files.writeString(dest, "the first part of a file"); throw new IOException("download failed: " + url); };   // as curl -f ends on a broken connection
        String r = install(new ByteArrayOutputStream());
        assertTrue(r.startsWith("!download failed: https://huggingface.co/unsloth/Qwen3.8-27B-GGUF/resolve/" + AUGUST + "/"), r);
        try (var ls = Files.list(models)) { assertEquals(List.of(), ls.toList()); }
    }

    // ---- the release's check, against the stand-in ----

    /** The stand-in answers for every pin as Hugging Face and GitHub do when the pins hold: the sha256 and size at the revision, the same on main, each build in its release's listing. */
    void everyPinHolds() {
        for (ModelServer.Pinned p : ModelServer.pinnedFiles()) {
            Map<String, String> h = p.mainUrl() == null ? Map.of("Content-Length", String.valueOf(p.bytes()))   // a HEAD of GitHub's says the size in its Content-Length
                    : Map.of("x-linked-etag", "\"" + p.sha256() + "\"", "x-linked-size", String.valueOf(p.bytes()));
            served.put(at(p.url()), new Answer(200, h, new byte[0]));
            if (p.mainUrl() != null) served.put(at(p.mainUrl()), new Answer(200, h, new byte[0]));
        }
        for (ModelServer.Build b : ModelServer.pinnedBuilds()) {
            StringBuilder j = new StringBuilder("{\"assets\": [");
            for (String a : b.assets()) j.append(j.charAt(j.length() - 1) == '[' ? "" : ", ").append("{\"name\": \"").append(a).append("\", \"digest\": \"sha256:").append(ModelServer.sums.get(a)).append("\"}");
            served.put(at(b.listing()), new Answer(200, Map.of("Content-Type", "application/json"), j.append("]}").toString().getBytes(StandardCharsets.UTF_8)));
        }
    }

    String check(int expect) {
        var out = new ByteArrayOutputStream();
        int rc = ModelServer.check(new PrintStream(out, true));
        assertEquals(expect, rc, out.toString());
        return out.toString();
    }

    @Test void theCheckPassesWhenEveryPinHoldsAndAsksAsTheProgramWithNothingElse() {
        everyPinHolds();
        String o = check(0);
        assertTrue(o.contains("  ok   200  " + BIG.url()), o);
        assertTrue(o.contains("  ok   200  https://github.com/mostlygeek/llama-swap/releases/download/v255/llama-swap_255_windows_amd64.zip"), o);
        assertTrue(o.contains("  ok   200  https://raw.githubusercontent.com/google/fonts/"), o);
        assertTrue(o.endsWith("  every pinned file is the one pinned, at its revision, and every pinned build resolves\n"), o);
        assertFalse(o.contains("note") || o.contains("DIFF") || o.contains("GONE"), o);
    }

    @Test void aFileThatMovedOnMainIsSaidAndDoesNotFailTheCheck() {
        everyPinHolds();
        String main = BIG.url().replace("/resolve/" + AUGUST + "/", "/resolve/main/");
        served.put(at(main), new Answer(200, Map.of("x-linked-etag", "\"1bce504dfecd84153666928a41d8a66706f0158d3a317bc5196b72819d7dc529\"", "x-linked-size", "16564259744"), new byte[0]));
        String o = check(0);
        assertTrue(o.contains("  ok   200  " + BIG.url()), o);
        assertTrue(o.contains("  note Qwen3.8-27B-UD-Q4_K_M.gguf (qwen3.8-27b) on main is now sha256 1bce504dfecd84153666928a41d8a66706f0158d3a317bc5196b72819d7dc529, 16564259744 bytes; the pin stays at 4ca720788d1e"), o);
        assertTrue(o.contains("  1 moved on main (said above)"), o);
    }

    @Test void aPinThatDoesNotResolveOrDoesNotMatchFailsTheCheck() {
        everyPinHolds();
        ModelServer.Row gpt = ModelServer.ROWS.get(2);
        served.remove(at(gpt.url()));   // the revision is gone (a history rewritten, a repository removed)
        served.put(at(ModelServer.ROWS.get(3).url()), new Answer(200, Map.of("x-linked-etag", "\"" + "0".repeat(64) + "\"", "x-linked-size", "5680522464"), new byte[0]));
        ModelServer.Pinned config = ModelServer.pinnedFiles().stream().filter(p -> p.url().endsWith("/config.json") && p.url().contains("Qwen3-Embedding")).findFirst().orElseThrow();
        // a small file Hugging Face keeps in git: its etag is git's own hash, so the check fetches it and hashes it
        Map<String, String> git = Map.of("x-linked-etag", "\"cef2749ee93607b8f9a58ec72f4f6bfaf874e71d\"", "x-linked-size", "727");
        served.put(at(config.url()), new Answer(200, git, "{\"not\": \"the pinned config\"}".getBytes(StandardCharsets.UTF_8)));
        served.put(at(config.mainUrl()), new Answer(200, git, new byte[0]));
        String o = check(1);
        assertTrue(o.contains("  GONE 404  " + gpt.url() + "  (gpt-oss-20b)"), o);
        assertTrue(o.contains("  DIFF 200  " + ModelServer.ROWS.get(3).url() + "  (qwen3.5-9b: sha256 " + "0".repeat(64) + ", 5680522464 bytes there; the pin is sha256 03b74727"), o);
        assertTrue(o.contains("  DIFF 200  " + config.url()), o);
        assertTrue(seen.contains("GET " + at(config.url())), "fetched to be hashed: " + seen);
        assertTrue(o.contains("  1 missing: re-point the pin") && o.contains("  2 not the file pinned"), o);
    }

    @Test void aBuildWhoseDigestDiffersFailsAndOneMissingFromItsListingIsGone() {
        everyPinHolds();
        ModelServer.Build swap = ModelServer.pinnedBuilds().get(0);
        served.put(at(swap.listing()), new Answer(200, Map.of(), ("{\"assets\": [{\"name\": \"llama-swap_255_linux_amd64.tar.gz\", \"digest\": \"sha256:" + "1".repeat(64) + "\"}]}").getBytes(StandardCharsets.UTF_8)));
        String o = check(1);
        assertTrue(o.contains("  DIFF 200  https://github.com/mostlygeek/llama-swap/releases/download/v255/llama-swap_255_linux_amd64.tar.gz  (GitHub gives sha256 " + "1".repeat(64)), o);
        assertTrue(o.contains("  GONE 404  https://github.com/mostlygeek/llama-swap/releases/download/v255/llama-swap_255_darwin_arm64.tar.gz  (not in the release's listing)"), o);
    }

    @Test void aHostThatRateLimitsIsAskedThreeTimesAndSaidNotFailed() {
        everyPinHolds();
        ModelServer.Row e2b = ModelServer.ROWS.get(5);
        tooMany.put(at(e2b.url()), 3);
        String o = check(0);
        assertEquals(3, seen.stream().filter(s -> s.equals("HEAD " + at(e2b.url()))).count(), seen.toString());
        assertTrue(o.contains("  WAIT 429  " + e2b.url()), o);
        assertTrue(o.contains("  1 not checked") && !o.contains("GONE") && !o.contains(" missing:"), o);
    }
}
