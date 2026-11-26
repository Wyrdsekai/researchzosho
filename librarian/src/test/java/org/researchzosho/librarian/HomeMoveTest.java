package org.researchzosho.librarian;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Each program in a folder of its own (0.8.3): an install that lived in CodeZaiku's folder moves to {@code ~/.researchzosho}, once.
 * Every home here is a test's own, built to the shape of a machine where the two programs shared the folder: this machine's shape (CodeZaiku's
 * model server with this program's embeddings entry in it), the test machine's (this program's own model server in CodeZaiku's folder), and
 * a new one (CodeZaiku first, this program after). Docker, systemctl and the routes are stand-ins that record what they were asked; the
 * settings are made up, with a made-up key that a test looks for in everything the move printed, journaled or wrote.
 */
class HomeMoveTest {
    @TempDir Path home;
    String realHome;
    Searx.Runner realSearx;
    ModelServer.Runner realModel;
    ModelServer.Os realOs;
    Predicate<String> realAnswers;
    Consumer<String> realStep;

    /** Made up: a key that appears nowhere but in the settings files, so a test finds it wherever else it went. */
    static final String KEY = "mk-7f3a9c-MADEUP-0d41e2b8";
    static final String DB_SECRET = "pw-MADEUP-5512";

    final List<List<String>> docker = new ArrayList<>(), system = new ArrayList<>();
    /** The stand-in Docker's containers: name → image, mounts, port, cards, running. */
    record Box(String image, List<String> mounts, String port, int cards, boolean running) { }
    final Map<String, Box> boxes = new LinkedHashMap<>();
    boolean networkMade;
    int execCode = 7;                  // curl's "could not connect" inside the embeddings container: no way out
    String failRunOf = null;           // a container whose docker run fails

    @BeforeEach void own() {
        realHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        Config.invalidate(); Config.forgetHome();
        realSearx = Searx.runner; realModel = ModelServer.runner; realOs = ModelServer.os; realAnswers = Embed.ANSWERS; realStep = HomeMove.STEP;
        ModelServer.os = ModelServer.Os.linux;
        Searx.runner = this::dockerSays;
        ModelServer.runner = cmd -> { system.add(cmd); Searx.Result r = machineSays(cmd); return new ModelServer.Result(r.code(), r.out()); };
        Embed.ANSWERS = url -> true;
        Embed.note = "";
        ModelNetworks.forget();
    }

    @AfterEach void back() {
        Searx.runner = realSearx; ModelServer.runner = realModel; ModelServer.os = realOs; Embed.ANSWERS = realAnswers; HomeMove.STEP = realStep;
        System.setProperty("user.home", realHome);
        Config.invalidate(); Config.forgetHome();
        ModelNetworks.forget();
    }

    // ---- the stand-ins ----

    Searx.Result dockerSays(List<String> cmd) {
        docker.add(cmd);
        String c = String.join(" ", cmd);
        if (c.startsWith("docker version")) return new Searx.Result(0, "27.3.1");
        if (c.startsWith("docker info")) return new Searx.Result(0, "map[io.containerd.runc.v2:{} nvidia:{} runc:{}]");
        if (c.startsWith("nvidia-smi")) return new Searx.Result(0, "8.9");
        if (c.startsWith("docker inspect -f {{.State.Running}} ")) { Box b = boxes.get(cmd.get(cmd.size() - 1)); return b == null ? new Searx.Result(1, "Error: No such object") : new Searx.Result(0, String.valueOf(b.running())); }
        if (c.startsWith("docker inspect -f {{range .Mounts}}")) {
            Box b = boxes.get(cmd.get(cmd.size() - 1));
            return b == null ? new Searx.Result(1, "Error: No such object") : new Searx.Result(0, String.join(";", b.mounts()) + ";");
        }
        if (c.startsWith("docker inspect -f {{.Config.Image}}")) {
            Box b = boxes.get(cmd.get(cmd.size() - 1));
            if (b == null) return new Searx.Result(1, "Error: No such object");
            StringBuilder m = new StringBuilder();
            for (String s : b.mounts()) m.append(s).append(';');
            return new Searx.Result(0, b.image() + "|" + m + "|" + (b.port() == null ? "" : b.port() + ";") + "|" + b.cards() + "||" + b.running());
        }
        if (c.startsWith("docker rm -f ")) { boxes.remove(cmd.get(3)); return new Searx.Result(0, cmd.get(3)); }
        if (c.startsWith("docker start ")) { Box b = boxes.get(cmd.get(2)); if (b == null) return new Searx.Result(1, "no such container"); boxes.put(cmd.get(2), new Box(b.image(), b.mounts(), b.port(), b.cards(), true)); return new Searx.Result(0, ""); }
        if (c.startsWith("docker run --rm --entrypoint curl")) return new Searx.Result(0, "13.33.109.43");
        if (c.startsWith("docker exec ")) return new Searx.Result(execCode, execCode == 7 ? "curl: (7) Failed to connect to 13.33.109.43 port 443 after 0 ms: Couldn't connect to server" : "");
        if (c.startsWith("docker run -d ") || c.startsWith("docker create ")) {
            boolean start = cmd.get(1).equals("run");
            String name = null, port = null, image = null; List<String> mounts = new ArrayList<>(); int cards = 0;
            Set<String> valued = Set.of("--name", "--restart", "-p", "-v", "-e", "--gpus", "--network", "--ip", "--cap-drop", "--device", "--group-add", "--entrypoint", "--env-file");
            for (int i = start ? 3 : 2; i < cmd.size(); i++) {
                String a = cmd.get(i);
                if (valued.contains(a)) {
                    String v = cmd.get(++i);
                    switch (a) {
                        case "--name" -> name = v;
                        case "-p" -> port = v.split(":")[1];
                        case "-v" -> mounts.add(v.substring(0, v.indexOf(":/", 2)));   // the source ends where the container's path begins (C:\\… on Windows)
                        case "--gpus" -> cards = 1;
                        default -> { }
                    }
                } else if (!a.startsWith("-")) { image = a; break; }
            }
            if (name != null && name.equals(failRunOf)) return new Searx.Result(125, "docker: Error response from daemon: a test's refusal.");
            boxes.put(name, new Box(image, mounts, port, cards, start));
            return new Searx.Result(0, "0123456789ab");
        }
        return new Searx.Result(0, "");
    }

    Searx.Result machineSays(List<String> cmd) {
        String c = String.join(" ", cmd);
        if (c.startsWith("docker network inspect ")) return networkMade ? new Searx.Result(0, "true 172.16.211.0/28") : new Searx.Result(1, "Error: network not found");
        if (c.startsWith("ip -4 route")) return new Searx.Result(0, "default via 192.168.1.1 dev eth0 proto dhcp\n192.168.1.0/24 dev eth0 proto kernel scope link src 192.168.1.20\n"
                + (networkMade ? "172.16.211.0/28 dev br-5e1 proto kernel scope link src 172.16.211.1\n" : ""));
        if (c.startsWith("docker network create ")) { networkMade = true; return new Searx.Result(0, "4f1d"); }
        return new Searx.Result(0, "");
    }

    // ---- the homes ----

    Path was() { return home.resolve(".codezaiku"); }
    Path now() { return home.resolve(".researchzosho"); }
    Path units() { return home.resolve(".config").resolve("systemd").resolve("user"); }

    static void put(Path file, String text) throws IOException { Files.createDirectories(file.getParent()); Files.writeString(file, text, StandardCharsets.UTF_8); }

    /** The settings file both programs read: CodeZaiku's lines and this program's, with the made-up key among them. */
    String sharedConfig() {
        return "# CodeZaiku\ndrive = http://127.0.0.1:8211/v1\nmodel = qwen3.8-27b\nops.audit = on\nresearch.workers = 3\n"
                + "librarian.drive = http://127.0.0.1:8211/v1\nlibrarian.port = 4649\nbrave.key = " + KEY + "\nembed = http://127.0.0.1:8211\n"
                + "entry.alpha.url = http://192.0.2.7:8000/v1\nentry.alpha.model = made-up-model\nweb.signin = on\nreport.length = long\n";
    }

    /** CodeZaiku's own names, and names neither program's code makes: they stay. */
    void theirs(Path w) throws IOException {
        put(w.resolve("sandbox").resolve("profile"), "x");
        put(w.resolve("chat").resolve("history.jsonl"), "{}\n");
        put(w.resolve("envs").resolve("p1").resolve("marker"), "x");
        put(w.resolve("oss-private-names"), "made-up-name\n");
        put(w.resolve("spend.jsonl"), "{}\n");
        put(w.resolve("project-id"), "abc\n");
        put(w.resolve("bench").resolve("run1.txt"), "neither program's\n");
        put(w.resolve("config.bak-20260915-embed"), "brave.key = " + KEY + "\n");
        put(w.resolve("update.lock"), "");
        put(w.resolve("search-tally.json"), "{\"brave\":3}\n");
    }

    /** This program's own names in the folder, as an install that has run for a while has them. */
    void ours(Path w) throws IOException {
        put(w.resolve("logs").resolve("librarian-serve.log"), "pid 4242 recorded at " + w.resolve("researchzosho.pid") + "\n");
        put(w.resolve("researchzosho.pid"), "4194399\n");   // above any pid the system gives: no process
        put(w.resolve("latest-version.txt"), "");
        put(w.resolve("help").resolve("index.md"), "# help\n");
        put(w.resolve("site-list").resolve("last-try"), "2026-11-25T00:00:00Z\n");
        put(w.resolve("databases.json"), "{\"crm\":{\"url\":\"postgresql://db.example/crm\",\"password\":\"" + DB_SECRET + "\"}}\n");
        put(w.resolve("labse").resolve("marker"), "x");
    }

    /** This machine's shape: CodeZaiku's model server, with this program's embeddings entry and start script in its folder. */
    String czConfigYaml;
    void lainShaped() throws Exception {
        Path w = was();
        put(w.resolve("config"), sharedConfig());
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) Files.setPosixFilePermissions(w.resolve("config"), PosixFilePermissions.fromString("rw-------"));
        theirs(w); ours(w);
        put(w.resolve("searxng").resolve("settings.yml"), "use_default_settings: true\n");
        Path venv = w.resolve("video").resolve("venv");   // video's yt-dlp, in a Python environment of its own
        put(venv.resolve("pyvenv.cfg"), "home = /usr/bin\n");
        put(venv.resolve("bin").resolve("yt-dlp"), "#!" + venv.resolve("bin").resolve("python") + "\nimport sys\nfrom yt_dlp import main\n");
        put(venv.resolve("bin").resolve("activate"), "VIRTUAL_ENV=\"" + venv + "\"\nexport VIRTUAL_ENV\n");
        Path folder = w.resolve("embed").resolve(Embed.FOLDER);
        for (String f : List.of("config.json", "tokenizer.json", "model.safetensors")) put(folder.resolve(f), "{}");
        Path m = w.resolve("model");
        put(m.resolve("run.sh"), "#!/bin/sh\nexec docker run --rm --name codezaiku-model --gpus all -p 127.0.0.1:$1:8080 -v /srv/models:/m ghcr.io/ggml-org/llama.cpp:server-cuda -m /m/q.gguf\n");
        put(m.resolve("plan.properties"), "name=qwen3.8-27b\nnetwork=shut\nsubnet=172.16.221.0/28\n");
        czConfigYaml = "# CodeZaiku: the model server on demand.\nhealthCheckTimeout: 600\nmodels:\n"
                + "  \"qwen3.8-27b\":\n    cmd: " + m.resolve("run.sh") + " ${PORT}\n    cmdStop: docker stop codezaiku-model\n    proxy: http://127.0.0.1:${PORT}\n    ttl: 1200\n"
                + "  \"embed\":\n    cmd: " + m.resolve("run-embed.sh") + " ${PORT}\n    cmdStop: docker stop researchzosho-model-embed\n    proxy: http://127.0.0.1:${PORT}\n    checkEndpoint: /health\n    ttl: 300\n"
                + "    aliases: [\"Qwen/Qwen3-Embedding-0.6B\", \"text-embedding\"]\n"
                + "groups:\n  \"embedding\":\n    swap: false\n    exclusive: false\n    persistent: true\n    members: [\"embed\"]\n";
        put(m.resolve("config.yaml"), czConfigYaml);
        put(m.resolve("run-embed.sh"), "#!/bin/sh\nexec docker run --rm --name researchzosho-model-embed --gpus all -p 127.0.0.1:$1:80 -v " + w.resolve("embed")
                + ":/data ghcr.io/huggingface/text-embeddings-inference:89-1.8 --model-id /data/" + Embed.FOLDER + " --pooling last-token\n");
        put(units().resolve("researchzosho.service"), "[Unit]\nDescription=ResearchZosho\n[Service]\nExecStart=/home/user/src/rz/bin/researchzosho serve --host 0.0.0.0 --port 4649 --log "
                + w.resolve("logs").resolve("librarian-serve.log") + "\nRestart=on-failure\n");
        put(units().resolve("codezaiku-model.service"), "[Service]\nExecStart=" + m.resolve("llama-swap") + " --config " + m.resolve("config.yaml") + " --listen 127.0.0.1:8211\n");
        boxes.put(Searx.CONTAINER, new Box("searxng/searxng:2025.1.0", List.of(w.resolve("searxng").toString()), "8888", 0, true));
        boxes.put(TranslationScores.LABSE_CONTAINER, new Box(Embed.IMAGE + ":89-1.8", List.of(w.resolve("labse").toString()), "8216", 1, false));
        boxes.put("researchzosho-searxng-other", new Box("searxng/searxng:2025.1.0", List.of("/elsewhere/searxng"), "8889", 0, true));
    }

    /** The test machine's shape: this program's own model server installed in CodeZaiku's folder, and its own units naming it. */
    void doloresShaped() throws Exception {
        Path w = was();
        put(w.resolve("config"), sharedConfig().replace("embed = http://127.0.0.1:8211\n", "embed = http://127.0.0.1:8211/v1\n"));
        theirs(w); ours(w);
        put(w.resolve("ocean").resolve("x"), "x"); put(w.resolve("research").resolve("x"), "x"); put(w.resolve("familiar-memory").resolve("x"), "x");
        put(w.resolve("codezaiku-latest.txt"), "0.3.19\n");
        put(w.resolve("likeness").resolve("marker"), "x");
        put(w.resolve("pdf-eye-speed.json"), "{}\n");
        put(w.resolve("video").resolve("whisper").resolve("marker"), "x");
        put(w.resolve("embed").resolve(Embed.FOLDER).resolve("config.json"), "{}");
        Path m = w.resolve("model");
        put(m.resolve("run.sh"), "#!/bin/sh\nexec docker run --rm --name researchzosho-model --gpus all -p 127.0.0.1:$1:8080 -v /home/x/models:/m ghcr.io/ggml-org/llama.cpp:server-cuda -m /m/q.gguf\n");
        put(m.resolve("run-embed.sh"), "#!/bin/sh\nexec docker run --rm --name researchzosho-model-embed --gpus all -p 127.0.0.1:$1:80 -v " + w.resolve("embed") + ":/data ghcr.io/huggingface/text-embeddings-inference:89-1.8 --model-id /data/" + Embed.FOLDER + "\n");
        put(m.resolve("config.yaml"), "# ResearchZosho: the model server on demand.\nmodels:\n  \"q\":\n    cmd: " + m.resolve("run.sh") + " ${PORT}\n  \"embed\":\n    cmd: " + m.resolve("run-embed.sh") + " ${PORT}\n");
        put(m.resolve("plan.properties"), "name=q\nnetwork=open\n");
        put(m.resolve("llama-swap"), "binary");
        put(units().resolve("researchzosho-model.service"), "[Service]\nExecStart=" + m.resolve("llama-swap") + " --config " + m.resolve("config.yaml") + " --listen 127.0.0.1:8211\n");
        put(units().resolve("researchzosho.service"), "[Service]\nExecStart=%h/.local/bin/researchzosho serve --host 127.0.0.1 --port 4649 --log %h/.codezaiku/logs/librarian-serve.log\n");
        boxes.put(Video.CONTAINER_WHISPER, new Box("ghcr.io/speaches-ai/speaches:latest-cpu", List.of(w.resolve("video").resolve("whisper").toString()), "18890", 0, true));
    }

    /** Every file under a folder with a digest of what it holds: two equal snapshots are two equal trees. */
    static Map<String, String> snapshot(Path root) throws Exception {
        Map<String, String> out = new TreeMap<>();
        if (!Files.exists(root)) return out;
        try (Stream<Path> w = Files.walk(root)) {
            for (Path p : w.toList()) {
                String rel = root.relativize(p).toString();
                if (Files.isDirectory(p)) { out.put(rel + "/", "dir"); continue; }
                out.put(rel, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p))));
            }
        }
        return out;
    }

    static Set<String> names(Path folder) throws IOException {
        try (Stream<Path> in = Files.list(folder)) { return new TreeSet<>(in.map(p -> p.getFileName().toString()).toList()); }
    }

    static String at(Path file) throws IOException { return Files.readString(file, StandardCharsets.UTF_8); }

    /** Every file under the home that holds the made-up key, but the settings files that hold it on purpose. */
    List<String> keyIn() throws IOException {
        List<String> out = new ArrayList<>();
        try (Stream<Path> w = Files.walk(home)) {
            for (Path p : w.filter(Files::isRegularFile).toList()) {
                if (p.equals(was().resolve("config")) || p.equals(now().resolve("config")) || p.equals(was().resolve("config.bak-20260915-embed"))) continue;
                byte[] b = Files.readAllBytes(p);
                if (new String(b, StandardCharsets.ISO_8859_1).contains(KEY)) out.add(home.relativize(p).toString());
            }
        }
        return out;
    }

    String atStart(String verb) {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        HomeMove.atStart(verb, new PrintStream(err, true, StandardCharsets.UTF_8));
        return err.toString(StandardCharsets.UTF_8);
    }

    // ---- the tests ----

    @Test
    void thisMachinesShapeMovesItsOwnFilesLeavesCodeZaikusAndTakesItsEntryOutOfTheirModelServer() throws Exception {
        lainShaped();
        Map<String, String> viewBefore = Config.readAsShared(was().resolve("config"));
        String configBefore = at(was().resolve("config")), czUnit = at(units().resolve("codezaiku-model.service")), czRun = at(was().resolve("model").resolve("run.sh"));
        assertEquals(was(), Config.stateDir(home), "before the move it lives in CodeZaiku's folder");

        String dry = HomeMove.dryRun();
        assertTrue(dry.contains("would move to " + now()), dry);
        assertTrue(dry.contains("moved: databases.json, embed, help, labse, latest-version.txt, logs, researchzosho.pid, searxng, site-list, video"), dry);
        assertTrue(dry.contains("copied (both programs keep one): search-tally.json"), dry);
        assertTrue(dry.contains("left, neither program's: bench, config.bak-20260915-embed"), dry);
        assertTrue(dry.contains("the 12 settings ResearchZosho reads"), dry);
        assertTrue(dry.contains("containers to be made again with the new folder: researchzosho-searxng, researchzosho-labse\n"), "the ones that are there and mount a moved folder: " + dry);
        assertFalse(Files.exists(now()), "a dry run moves nothing");
        assertTrue(docker.stream().allMatch(c -> c.get(1).equals("inspect")) && system.isEmpty(), "a dry run only asks Docker what is there: " + docker);

        String said = atStart("serve");
        assertTrue(said.startsWith("ResearchZosho kept its files in CodeZaiku's folder, " + was() + ". They are now in " + now() + "."), said);
        for (String s : List.of(said, dry, at(now().resolve(HomeMove.JOURNAL)))) { assertFalse(s.contains(KEY), "no value of a setting is printed or journaled"); assertFalse(s.contains("8000/v1"), s); }
        assertEquals(List.of(), keyIn(), "the key is only in the two settings files");
        assertFalse(said.contains(DB_SECRET));

        // file by file
        assertEquals(new TreeSet<>(List.of("config", "databases.json", "embed", "embed-at", "help", "labse", "latest-version.txt", "logs", "moved-out-of-model", HomeMove.JOURNAL,
                "researchzosho.pid", "search-tally.json", "searxng", "site-list", "video")), names(now()));
        Path venv = now().resolve("video").resolve("venv");
        assertEquals("#!" + venv.resolve("bin").resolve("python") + "\nimport sys\nfrom yt_dlp import main\n", at(venv.resolve("bin").resolve("yt-dlp")), "its script finds its Python in the new place");
        assertEquals("VIRTUAL_ENV=\"" + venv + "\"\nexport VIRTUAL_ENV\n", at(venv.resolve("bin").resolve("activate")));
        assertEquals(new TreeSet<>(List.of("bench", "chat", "config", "config.bak-20260915-embed", "envs", "model", "oss-private-names", "project-id", "sandbox", "search-tally.json",
                "spend.jsonl", "update.lock")), names(was()));
        assertEquals(configBefore, at(was().resolve("config")), "CodeZaiku's settings file is not changed by a byte");
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {   // Windows has no such modes: the folder in the person's profile is theirs alone
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(now().resolve("config"))));
            assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(now())));
        }
        Map<String, String> mine = Config.readAsOwn(now().resolve("config"));
        assertEquals("http://172.16.211.4:80", mine.get("RESEARCHZOSHO_EMBED"), "the embeddings now its own server's, on the network with no way out");
        Map<String, String> expected = new TreeMap<>(viewBefore);
        expected.put("RESEARCHZOSHO_EMBED", "http://172.16.211.4:80");
        assertEquals(expected, mine, "every other setting it read in the shared file, with the value it read there");
        assertEquals(KEY, mine.get("RESEARCHZOSHO_BRAVE_KEY"));
        assertEquals("http://127.0.0.1:8211/v1", mine.get("RESEARCHZOSHO_DRIVE"), "the model: CodeZaiku's server, used by its address");
        Config.invalidate(); Config.forgetHome();
        assertEquals(now(), Config.stateDir(home));
        assertEquals(now(), Config.home());
        assertEquals(KEY, Config.stored("RESEARCHZOSHO_BRAVE_KEY"), "the program reads the key from its own file");

        // CodeZaiku's model server: only this program's lines out of it, its own files as they were, its proxy not restarted
        Path m = was().resolve("model");
        assertEquals(ModelServer.withoutEmbed(czConfigYaml), at(m.resolve("config.yaml")));
        assertTrue(at(m.resolve("config.yaml")).contains("\"qwen3.8-27b\":"), "CodeZaiku's model stays");
        assertFalse(Files.exists(m.resolve("run-embed.sh")));
        assertEquals(czRun, at(m.resolve("run.sh")));
        assertEquals(czConfigYaml, at(now().resolve("moved-out-of-model").resolve("config.yaml.before")));
        assertTrue(system.contains(List.of("docker", "stop", "researchzosho-model-embed")));
        assertTrue(system.stream().noneMatch(c -> String.join(" ", c).contains("codezaiku")), "nothing of CodeZaiku's is stopped or restarted: " + system);
        assertTrue(docker.stream().noneMatch(c -> String.join(" ", c).contains("codezaiku-model")), "nor its container");
        assertTrue(system.stream().noneMatch(c -> c.size() > 2 && c.get(2).equals("restart")), "no proxy restarted: " + system);

        // the units: this program's own path changed, CodeZaiku's unit as it was
        String unit = at(units().resolve("researchzosho.service"));
        assertTrue(unit.contains("--log " + now().resolve("logs").resolve("librarian-serve.log") + "\n"), unit);
        assertFalse(unit.contains(was().toString()), unit);
        assertEquals(czUnit, at(units().resolve("codezaiku-model.service")));
        assertTrue(system.contains(List.of("systemctl", "--user", "daemon-reload")));

        // the containers that mounted a moved folder: made again by their own commands, with their ports and cards; another left alone
        List<String> searx = docker.stream().filter(c -> c.size() > 4 && c.get(1).equals("run") && c.contains(Searx.CONTAINER)).findFirst().orElseThrow();
        assertTrue(searx.contains(now().resolve("searxng") + ":/etc/searxng") && searx.contains("127.0.0.1:8888:8080"), searx.toString());
        assertEquals("searxng/searxng:2025.1.0", searx.get(searx.indexOf("BASE_URL=http://127.0.0.1:8888/") + 1), "the image it had, pinned version and all: " + searx);
        List<String> labse = docker.stream().filter(c -> c.size() > 4 && c.get(1).equals("create") && c.contains(TranslationScores.LABSE_CONTAINER)).findFirst().orElseThrow();
        assertTrue(labse.contains(now().resolve("labse") + ":/data") && labse.contains("127.0.0.1:8216:80") && labse.contains("--gpus") && labse.contains("all"), labse.toString());
        assertFalse(boxes.get(TranslationScores.LABSE_CONTAINER).running(), "stopped by the idle rule before, made again stopped: its card stays free");
        assertTrue(boxes.containsKey("researchzosho-searxng-other") && docker.stream().noneMatch(c -> c.contains("researchzosho-searxng-other") && c.contains("rm")), "a container of another folder is left alone");
        List<String> embed = docker.stream().filter(c -> c.size() > 4 && c.get(1).equals("run") && c.contains(Embed.CONTAINER)).findFirst().orElseThrow();
        assertTrue(String.join(" ", embed).contains("--network researchzosho-model-shut --ip 172.16.211.4 --cap-drop NET_RAW -v " + now().resolve("embed") + ":/data:ro"), embed.toString());
        assertFalse(embed.contains("-p"), "no port of the machine published");
        assertTrue(embed.contains("--gpus"), "on the card, as the entry it replaces was");
        assertTrue(embed.contains("/data/" + Embed.FOLDER), "started from the model's folder: nothing downloaded");
        assertTrue(said.contains("The embeddings server has no way out to the network"), said);
        assertEquals("http://172.16.211.4:80", Embed.address());
        assertTrue(ModelNetworks.inside(InetAddress.getByName("172.16.211.4"), ModelNetworks.ranges()), "the library refuses its requests");
        assertEquals("http://172.16.211.4:80", ModelsAwake.machine().get(Embed.CONTAINER), "the idle rule finds it at its address");

        // twice in a row: nothing more
        Map<String, String> a = snapshot(was()), b = snapshot(now());
        int asked = docker.size() + system.size();
        assertEquals("", atStart("status"));
        assertEquals(a, snapshot(was())); assertEquals(b, snapshot(now()));
        assertEquals(asked, docker.size() + system.size(), "a second start asks Docker nothing");

        // serve's log, from a unit written before the move
        assertEquals(now().resolve("logs").resolve("librarian-serve.log"), HomeMove.relocated(was().resolve("logs").resolve("librarian-serve.log")));
        assertEquals(was().resolve("sandbox").resolve("x"), HomeMove.relocated(was().resolve("sandbox").resolve("x")), "CodeZaiku's paths stay");
    }

    @Test
    void withNoModelOnTheMachineItsOwnEmbeddingsServerIsNotStartedAndNothingIsDownloaded() throws Exception {
        lainShaped();
        Files.walk(was().resolve("embed").resolve(Embed.FOLDER)).sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        String said = atStart("serve");
        assertTrue(said.contains("not done, and what does it: ResearchZosho's own embeddings server did not start (its model is not on this machine yet, and a move downloads nothing), so search is by words: researchzosho embed start"), said);
        assertTrue(docker.stream().noneMatch(c -> c.contains(Embed.CONTAINER) && c.get(1).equals("run")), "not started");
        assertFalse(Files.exists(home.resolve("models")), "nothing fetched");
        assertEquals("off", Config.readAsOwn(now().resolve("config")).get("RESEARCHZOSHO_EMBED"));
        assertFalse(Files.exists(was().resolve("model").resolve("run-embed.sh")), "its entry is out of the other program's folder all the same");
    }

    @Test
    void theWayBackPutsEveryFileAndTheirModelServersEntryBack() throws Exception {
        lainShaped();
        Map<String, String> before = snapshot(was());
        String unitBefore = at(units().resolve("researchzosho.service"));
        atStart("serve");
        assertTrue(Files.isDirectory(now()));
        put(now().resolve("logs").resolve("written-after.log"), "x");   // written by this program after the move: goes back with it
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertEquals(0, HomeMove.command(new String[]{"home", "move", "--back"}, 1, new PrintStream(out, true, StandardCharsets.UTF_8)));
        String said = out.toString(StandardCharsets.UTF_8);
        assertTrue(said.startsWith("ResearchZosho's files are back in " + was()), said);
        assertFalse(said.contains(KEY));
        assertFalse(Files.exists(now()), "its own folder is gone");
        Map<String, String> after = snapshot(was());
        after.remove(Path.of("logs", "written-after.log").toString());
        assertEquals(before, after, "every file as it was, CodeZaiku's model config with this program's entry again");
        assertEquals(unitBefore, at(units().resolve("researchzosho.service")));
        assertTrue(docker.contains(List.of("docker", "rm", "-f", Embed.CONTAINER)), "the embeddings server the move made is removed");
        List<List<String>> runs = docker.stream().filter(c -> c.size() > 4 && c.get(1).equals("run") && c.contains(Searx.CONTAINER)).toList();
        assertTrue(runs.get(runs.size() - 1).contains(was().resolve("searxng") + ":/etc/searxng"), "made once more with the old folder");
        Config.invalidate(); Config.forgetHome();
        assertEquals(was(), Config.stateDir(home), "it lives where it did");
        assertEquals(KEY, Config.stored("RESEARCHZOSHO_BRAVE_KEY"));
    }

    @Test
    void theTestMachinesShapeMovesItsOwnModelServerWholeAndRestartsOnlyItsOwnProxy() throws Exception {
        doloresShaped();
        String said = atStart("update");
        assertTrue(said.contains("They are now in " + now()), said);
        assertTrue(names(now()).containsAll(List.of("model", "video", "likeness", "embed", "labse", "logs", "pdf-eye-speed.json", "config")), names(now()).toString());
        assertEquals(new TreeSet<>(List.of("bench", "chat", "codezaiku-latest.txt", "config", "config.bak-20260915-embed", "envs", "familiar-memory", "ocean", "oss-private-names",
                "project-id", "research", "sandbox", "search-tally.json", "spend.jsonl", "update.lock")), names(was()));
        Path m = now().resolve("model");
        assertTrue(at(m.resolve("config.yaml")).contains("cmd: " + m.resolve("run.sh") + " ${PORT}") && at(m.resolve("config.yaml")).contains("cmd: " + m.resolve("run-embed.sh")), at(m.resolve("config.yaml")));
        assertTrue(at(m.resolve("run-embed.sh")).contains("-v " + now().resolve("embed") + ":/data"), at(m.resolve("run-embed.sh")));
        assertTrue(at(m.resolve("run.sh")).contains("-v /home/x/models:/m"), "a path outside the home stays");
        assertEquals("[Service]\nExecStart=" + m.resolve("llama-swap") + " --config " + m.resolve("config.yaml") + " --listen 127.0.0.1:8211\n", at(units().resolve("researchzosho-model.service")));
        if (File.separatorChar == '/')   // systemd's %h: a unit of Linux; Windows has none
            assertEquals("[Service]\nExecStart=%h/.local/bin/researchzosho serve --host 127.0.0.1 --port 4649 --log %h/.researchzosho/logs/librarian-serve.log\n", at(units().resolve("researchzosho.service")));
        int reload = system.indexOf(List.of("systemctl", "--user", "daemon-reload")), restart = system.indexOf(List.of("systemctl", "--user", "restart", ModelServer.UNIT));
        assertTrue(reload >= 0 && restart > reload, "reloaded, then its own proxy restarted: " + system);
        List<String> whisper = docker.stream().filter(c -> c.size() > 4 && c.get(1).equals("run") && c.contains(Video.CONTAINER_WHISPER)).findFirst().orElseThrow();
        assertTrue(whisper.contains(now().resolve("video").resolve("whisper") + ":/home/ubuntu/.cache/huggingface"), whisper.toString());
        assertTrue(docker.stream().noneMatch(c -> c.size() > 4 && c.get(1).equals("run") && c.contains(Embed.CONTAINER)), "its embeddings server is its own model server's: none other started");
        assertFalse(said.contains(KEY));
        assertEquals(List.of(), keyIn());
        Config.invalidate(); Config.forgetHome();
        assertEquals("http://127.0.0.1:8211/v1", Config.stored("RESEARCHZOSHO_EMBED"), "its own model server, at the same address");
    }

    @Test
    void aProgramInstalledAfterCodeZaikuStartsInItsOwnFolderAndNeverWritesInCodeZaikus() throws Exception {
        put(was().resolve("config"), "drive = http://127.0.0.1:8211/v1\nmodel = qwen3.8-27b\nops.audit = on\n");
        put(was().resolve("sandbox").resolve("profile"), "x");
        put(was().resolve("model").resolve("run.sh"), "#!/bin/sh\nexec docker run --rm --name codezaiku-model image\n");
        Map<String, String> before = snapshot(was());
        assertEquals(now(), Config.stateDir(home), "CodeZaiku's folder holds nothing of this program's: its own folder");
        assertEquals("Nothing to move: nothing of this program's is in " + was() + ".", HomeMove.dryRun().strip());
        assertEquals("", atStart("setup"));
        Config.set("RESEARCHZOSHO_REPORT_LENGTH", "short");
        assertEquals("report.length = short", at(now().resolve("config")).strip().lines().filter(l -> !l.startsWith("#") && !l.isBlank()).findFirst().orElse(""));
        assertEquals(before, snapshot(was()), "CodeZaiku's folder untouched");
        // and the third machine's shape: both folders there, nothing moves
        assertEquals("", atStart("serve"));
        assertEquals(before, snapshot(was()));
    }

    @Test
    void nothingMovesWhileTheServiceRunsFromTheOldFolderAndItsNextStartMovesIt() throws Exception {
        lainShaped();
        Process server = StandInServer.start(home.resolve("stand-in"));
        try {
            Files.writeString(was().resolve("researchzosho.pid"), Long.toString(server.pid()));
            Map<String, String> before = snapshot(was());
            assertEquals("", atStart("status"), "a command beside the running service moves nothing and says nothing");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            assertEquals(1, HomeMove.command(new String[]{"home", "move"}, 1, new PrintStream(out, true, StandardCharsets.UTF_8)));
            assertTrue(out.toString(StandardCharsets.UTF_8).contains("moves its files itself at its next start"), out.toString(StandardCharsets.UTF_8));
            assertEquals(before, snapshot(was()));
            assertFalse(Files.exists(now()));
        } finally { server.destroyForcibly().waitFor(); }
        assertTrue(atStart("serve").contains("They are now in " + now()), "the service's next start carries it out");
    }

    @Test
    void aFailureHalfWayPutsEverythingBackAndAStopHalfWayIsPutBackAtTheNextStart() throws Exception {
        lainShaped();
        Map<String, String> before = snapshot(was());
        String configBefore = at(was().resolve("config"));
        HomeMove.STEP = s -> { if (s.equals("moved labse")) throw new IllegalStateException("a test's failure after labse"); };
        String said = atStart("serve");
        assertTrue(said.startsWith("ResearchZosho did not move its files out of CodeZaiku's folder: a test's failure after labse"), said);
        assertEquals(before, snapshot(was()), "every renamed entry back where it was: nothing moved");
        assertFalse(Files.exists(now()) || Files.exists(home.resolve(".researchzosho-moving")));
        assertEquals(was(), Config.stateDir(home), "the install works where it was");
        assertTrue(docker.stream().noneMatch(c -> c.contains("rm")) && system.stream().noneMatch(c -> c.contains("daemon-reload")), "nothing after the commit was begun");

        // a stop (power, a kill) at the same point: the stage is left with what was renamed into it
        HomeMove.STEP = s -> { if (s.equals("moved labse")) throw new StopHere(); };
        assertThrows(StopHere.class, HomeMove::run);
        Path stage = home.resolve(".researchzosho-moving");
        assertTrue(Files.isDirectory(stage.resolve("labse")) && !Files.exists(was().resolve("labse")), "half in the stage, as a killed process leaves it");
        assertEquals(configBefore, at(was().resolve("config")), "the shared settings file was never changed");
        assertTrue(HomeMove.dryRun().startsWith("A move that stopped before it finished left " + stage), HomeMove.dryRun());
        HomeMove.STEP = s -> { };
        HomeMove.recover();
        assertEquals(before, snapshot(was()), "the next start puts it back first");
        assertFalse(Files.exists(stage));
        assertTrue(atStart("serve").contains("They are now in " + now()), "and then moves it");
    }

    @Test
    void aFailureAfterTheCommitLeavesItMovedAndWorkingAndSaysWhatIsLeft() throws Exception {
        lainShaped();
        failRunOf = Searx.CONTAINER;
        String said = atStart("serve");
        assertTrue(said.contains("They are now in " + now()), said);
        assertTrue(said.contains("not done, and what does it: researchzosho-searxng was not made again (docker: Error response from daemon: a test's refusal.)"), said);
        Config.invalidate(); Config.forgetHome();
        assertEquals(now(), Config.home(), "moved, and working from its own folder");
    }

    @Test
    void aFailureRightAfterTheSettingsAreWrittenLeavesNothingAndPrintsNoValue() throws Exception {
        lainShaped();
        Map<String, String> before = snapshot(was());
        HomeMove.STEP = s -> { if (s.equals("settings")) throw new IllegalStateException("its settings did not read back the same from the new file"); };
        String said = atStart("serve");
        assertTrue(said.contains("its settings did not read back the same"), said);
        assertFalse(said.contains(KEY));
        assertEquals(before, snapshot(was()));
        assertFalse(Files.exists(now()));
    }

    /** Stands for a process that stops where it is: not an exception the move catches. */
    static final class StopHere extends Error { StopHere() { super("stopped here"); } }
}
