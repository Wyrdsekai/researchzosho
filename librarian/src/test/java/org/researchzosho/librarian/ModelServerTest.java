package org.researchzosho.librarian;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import org.junit.jupiter.api.Assumptions;
import org.researchzosho.Config;
/**
 * The model server on demand, on all three platforms: the row for the memory, the files an install writes, and the install
 * itself with every seam faked — the platform included, so a Linux host exercises the macOS and Windows paths too.
 */
class ModelServerTest {
    final ModelServer.Os realOs = ModelServer.os;
    final ModelServer.Runner realRunner = ModelServer.runner;
    final ModelServer.Downloader realDownloader = ModelServer.downloader;
    final Predicate<String> realHealth = ModelServer.health;
    final ModelServer.Detacher realDetach = ModelServer.detach;
    final Map<String, String> realSums = new HashMap<>(ModelServer.sums);
    @AfterEach void restore() { ModelServer.sums.clear(); ModelServer.sums.putAll(realSums); ModelServer.os = realOs; ModelServer.runner = realRunner; ModelServer.downloader = realDownloader; ModelServer.health = realHealth; ModelServer.detach = realDetach; }

    @Test
    void theRowFollowsTheMemory() {
        assertEquals("qwen3.8-27b", ModelServer.rowFor(48, 16).name());
        assertEquals("gpt-oss-20b", ModelServer.rowFor(16, 16).name());
        assertEquals("qwen3.5-9b", ModelServer.rowFor(11, 16).name());
        assertEquals("gemma-4-e4b", ModelServer.rowFor(6, 16).name());
        assertEquals("gemma-4-e2b", ModelServer.rowFor(2, 16).name());
        assertNull(ModelServer.rowFor(1, 16));
        ModelServer.Os was = ModelServer.os; ModelServer.os = ModelServer.Os.linux;
        try {
            assertEquals("qwen3.6-35b-a3b", ModelServer.rowFor(16, 64).name(), "the same card with 64 GB of RAM behind it: the experts-in-RAM model");
            assertEquals("qwen3.6-35b-a3b", ModelServer.rowFor(6, 32).name());
        } finally { ModelServer.os = was; }
    }

    @Test
    void eachPlatformsPlanStartsTheRightServerTheRightWay(@TempDir Path tmp) {
        Path model = tmp.resolve("models/gpt-oss-20b-F16.gguf"), dir = tmp.resolve("model");
        var linux = ModelServer.plan(ModelServer.Os.linux, ModelServer.rowFor(16, 16), model, dir, tmp.resolve("unit.service"), "all", 20, false, null);
        assertTrue(linux.configYaml().contains("cmd: " + dir.resolve("run.sh") + " ${PORT}") && linux.configYaml().contains("cmdStop: docker stop researchzosho-model") && linux.configYaml().contains("ttl: 1200") && linux.configYaml().contains("\"local-model\""), linux.configYaml());
        assertTrue(linux.runScript().contains("--gpus all") && linux.runScript().contains("-m /m/gpt-oss-20b-F16.gguf") && linux.runScript().contains("-c 32768 --parallel 2") && linux.runScript().contains("reasoning_effort"), linux.runScript());
        assertTrue(linux.unitText().contains("ExecStart=" + dir.resolve("llama-swap")) && linux.unitText().contains("--listen 127.0.0.1:8211"), linux.unitText());
        var mac = ModelServer.plan(ModelServer.Os.macos, ModelServer.rowFor(11, 16), tmp.resolve("models/Qwen3.5-9B-Q4_K_M.gguf"), dir, tmp.resolve("org.x.plist"), "all", 20, false, null);
        assertTrue(mac.configYaml().contains("cmd: " + dir.resolve("llama").resolve("llama-server") + " -m " + tmp.resolve("models/Qwen3.5-9B-Q4_K_M.gguf") + " --host 127.0.0.1 --port ${PORT} --jinja -c 16384 --parallel 1 -ngl 99 --flash-attn on --chat-template-kwargs '{\"enable_thinking\":false}'"), mac.configYaml());
        assertFalse(mac.configYaml().contains("cmdStop"), "no container to stop on macOS");
        assertNull(mac.runScript());
        assertTrue(mac.unitText().contains("<string>org.researchzosho.model</string>") && mac.unitText().contains("<string>--listen</string>\n    <string>127.0.0.1:8211</string>") && mac.unitText().contains("<key>KeepAlive</key><true/>"), mac.unitText());
        var win = ModelServer.plan(ModelServer.Os.windows, ModelServer.rowFor(16, 16), tmp.resolve("models/gpt-oss-20b-F16.gguf"), dir, dir.resolve("start.ps1"), "all", 45, true, null);
        assertTrue(win.configYaml().contains("cmd: " + dir.resolve("llama").resolve("llama-server.exe") + " -m ") && win.configYaml().contains("--chat-template-kwargs \"{\\\"reasoning_effort\\\":\\\"low\\\"}\"") && win.configYaml().contains("ttl: 2700"), win.configYaml());
        assertTrue(win.unitText().contains("Start-Process -FilePath '" + dir.resolve("llama-swap.exe") + "'") && win.unitText().contains("'0.0.0.0:8211'"), win.unitText());
        var shared = ModelServer.plan(ModelServer.Os.linux, ModelServer.rowFor(48, 16), model, dir, tmp.resolve("u"), "3", 45, true, null);
        assertTrue(shared.runScript().contains("--gpus '\"device=3\"'") && shared.unitText().contains("--listen 0.0.0.0:8211"), shared.runScript());
    }

    @Test
    void theEmbeddingsServerRidesBesideTheModelInItsOwnGroup(@TempDir Path tmp) {
        Path model = tmp.resolve("models/gpt-oss-20b-F16.gguf"), dir = tmp.resolve("model");
        var linux = ModelServer.plan(ModelServer.Os.linux, ModelServer.rowFor(16, 16), model, dir, tmp.resolve("unit.service"), "3", 20, false, "89-1.8");
        assertTrue(linux.embeds());
        String y = linux.configYaml();
        assertTrue(y.contains("  \"embed\":\n    cmd: " + dir.resolve("run-embed.sh") + " ${PORT}\n    cmdStop: docker stop researchzosho-model-embed\n"), y);
        assertTrue(y.contains("    checkEndpoint: /health\n    ttl: 0\n") && y.contains("groups:\n  \"embedding\":\n    swap: false\n    exclusive: false\n    persistent: true\n    members: [\"embed\"]"), y);
        assertTrue(y.indexOf("\"gpt-oss-20b\":") < y.indexOf("\"embed\":"), "the drive stays the first model (status reads the first name)");
        assertTrue(linux.embedScript().contains("--gpus '\"device=3\"'") && linux.embedScript().contains("-p 127.0.0.1:$1:80") && linux.embedScript().contains("text-embeddings-inference:89-1.8") && linux.embedScript().contains("--model-id Qwen/Qwen3-Embedding-0.6B --pooling last-token"), linux.embedScript());
        var none = ModelServer.plan(ModelServer.Os.linux, ModelServer.rowFor(16, 16), model, dir, tmp.resolve("unit.service"), "all", 20, false, null);
        assertFalse(none.embeds()); assertFalse(none.configYaml().contains("embed")); assertNull(none.embedScript());
        var mac = ModelServer.plan(ModelServer.Os.macos, ModelServer.rowFor(11, 16), tmp.resolve("models/Qwen3.5-9B-Q4_K_M.gguf"), dir, tmp.resolve("org.x.plist"), "all", 20, false, "llama");
        assertTrue(mac.embeds()); assertNull(mac.embedScript());
        assertTrue(mac.configYaml().contains("cmd: " + dir.resolve("llama").resolve("llama-server") + " -m " + tmp.resolve("models/Qwen3-Embedding-0.6B-Q8_0.gguf") + " --host 127.0.0.1 --port ${PORT} --embedding --pooling last"), mac.configYaml());
        assertFalse(mac.configYaml().contains("cmdStop"));
        assertEquals("llama", ModelServer.embedTagFor(ModelServer.Os.windows));
    }

    static final String SHA_X = "2d711642b726b04401627ca9fbac32f5c8530fb1903cc4db02258717921a4881";   // sha256 of "x", what the fake downloads hold

    /** The machine as the fakes describe it: which commands answer, what the archives unpack to. */
    private List<List<String>> fakeMachine(ModelServer.Os os, boolean[] started, List<String> fetched) {
        List<List<String>> ran = new ArrayList<>();
        ModelServer.os = os;
        ModelServer.runner = cmd -> {
            ran.add(cmd);
            String c = cmd.get(0);
            if (c.equals("docker")) return new ModelServer.Result(os == ModelServer.Os.linux ? 0 : 1, "map[nvidia:{...} runc:{...}]");
            if (c.equals("nvidia-smi")) return new ModelServer.Result(os == ModelServer.Os.linux ? 0 : 1, cmd.stream().anyMatch(x -> x.contains("compute_cap")) ? "8.9\n" : "16380\n");
            if (c.equals("sysctl")) return new ModelServer.Result(0, String.valueOf(16L * 1073741824L) + "\n");
            if (c.equals("powershell") && cmd.contains("-Command")) return new ModelServer.Result(0, String.valueOf(13L * 1073741824L) + "\n");
            if (c.startsWith("curl")) return new ModelServer.Result(0, "HTTP/2 200\ncontent-length: 13780000000\n");
            if (c.startsWith("tar")) {   // unpacks: the proxy into -C, or llama-server into -C
                Path into = Path.of(cmd.get(cmd.indexOf("-C") + 1));
                String archive = cmd.get(2);
                try {
                    Files.createDirectories(into);
                    if (archive.contains("llama-swap")) Files.writeString(into.resolve(os == ModelServer.Os.windows ? "llama-swap.exe" : "llama-swap"), "x");
                    else Files.writeString(into.resolve(os == ModelServer.Os.windows ? "llama-server.exe" : "llama-server"), "x");
                } catch (Exception e) { return new ModelServer.Result(1, e.toString()); }
                return new ModelServer.Result(0, "");
            }
            if (c.equals("id")) return new ModelServer.Result(0, "501\n");
            if (cmd.contains("enable") || cmd.contains("bootstrap")) started[0] = true;
            return new ModelServer.Result(0, "");
        };
        ModelServer.detach = cmd -> { ran.add(cmd); if (cmd.get(0).equals("powershell") && cmd.contains("-File")) started[0] = true; };
        ModelServer.downloader = (url, dest) -> { fetched.add(url); Files.createDirectories(dest.getParent()); Files.writeString(dest, "x"); ModelServer.sums.put(dest.getFileName().toString(), SHA_X); };
        ModelServer.health = base -> started[0];
        return ran;
    }

    @Test
    void onLinuxInstallWritesTheFilesStartsTheServiceAndPointsTheDriveAtTheProxy(@TempDir Path home) throws Exception {
        String realHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        Config.invalidate();
        boolean[] started = {false}; List<String> fetched = new ArrayList<>();
        try {
            List<List<String>> ran = fakeMachine(ModelServer.Os.linux, started, fetched);
            Config.set("RESEARCHZOSHO_DRIVE", "http://elsewhere:8211");   // a drive the person had set, to be put back on uninstall
            assertTrue(ModelServer.offer().startsWith("Serve gpt-oss-20b on this machine on demand (CUDA in Docker): it downloads once (about 14 GB)"), ModelServer.offer());
            var out = new ByteArrayOutputStream();
            String r = ModelServer.install(null, "all", 20, false, new PrintStream(out, true));
            assertEquals("gpt-oss-20b", r, out.toString());
            assertTrue(fetched.get(0).endsWith("/gpt-oss-20b-F16.gguf") && fetched.get(1).contains("llama-swap_255_linux_"), fetched.toString());
            assertEquals(2, fetched.size(), "no llama.cpp download on Linux: the container is the server");
            Path dir = ModelServer.dir();
            assertTrue(Files.readString(dir.resolve("config.yaml")).contains("\"gpt-oss-20b\":"));
            assertTrue(Files.isExecutable(dir.resolve("run.sh")));
            assertTrue(Files.readString(home.resolve(".config/systemd/user/researchzosho-model.service")).contains("--listen 127.0.0.1:8211"));
            assertTrue(ran.stream().anyMatch(c -> c.equals(List.of("systemctl", "--user", "enable", "--now", "researchzosho-model"))), ran.toString());
            assertEquals("http://127.0.0.1:8211", Config.get("RESEARCHZOSHO_DRIVE"));
            assertEquals("gpt-oss-20b", Config.get("RESEARCHZOSHO_MODEL"));
            // the test JVM pins RESEARCHZOSHO_EMBED=off in its environment, so read what install wrote to the file
            assertTrue(Files.readString(Config.userConfigPath()).matches("(?s).*(?m)^embed\\s*=\\s*http://127\\.0\\.0\\.1:8211\\s*$.*"), "the embedder rides at the same address: " + Files.readString(Config.userConfigPath()));
            assertTrue(Files.isExecutable(dir.resolve("run-embed.sh")) && Files.readString(dir.resolve("config.yaml")).contains("\"embed\":"), "the embeddings entry and its script");
            assertTrue(out.toString().contains("search by meaning is on"), out.toString());
            ModelServer.health = base -> false;
            String u = ModelServer.uninstall();
            assertTrue(u.contains("the drive is back to http://elsewhere:8211") && u.contains("embeddings off"), u);
            assertEquals("http://elsewhere:8211", Config.get("RESEARCHZOSHO_DRIVE"));
            assertTrue(Files.readString(Config.userConfigPath()).matches("(?s).*(?m)^embed\\s*=\\s*off\\s*$.*"), "embeddings back off: " + Files.readString(Config.userConfigPath()));
            assertFalse(Files.exists(ModelServer.dir()));
            ModelServer.health = base -> true;
            assertEquals("local-model", ModelServer.install(null, "all", 20, false, new PrintStream(new ByteArrayOutputStream())));
            assertTrue(ModelServer.offer().startsWith("A model proxy already answers"));
        } finally {
            System.setProperty("user.home", realHome);
            Config.invalidate();
        }
    }

    @Test
    void anInstallFromBeforeTheEmbedderIsBroughtUpToDateWithoutReinstalling(@TempDir Path home) throws Exception {
        String realHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        Config.invalidate();
        boolean[] started = {false}; List<String> fetched = new ArrayList<>();
        try {
            List<List<String>> ran = fakeMachine(ModelServer.Os.linux, started, fetched);
            // the files the 0.3.0 installer wrote: one model, no plan.properties, no embed
            Path dir = ModelServer.dir(); Files.createDirectories(dir);
            Path model = ModelServer.modelsDir().resolve("gpt-oss-20b-F16.gguf");
            var old = ModelServer.plan(ModelServer.Os.linux, ModelServer.rowFor(16, 16), model, dir, home.resolve(".config/systemd/user/researchzosho-model.service"), "3", 45, true, null);
            Files.writeString(dir.resolve("config.yaml"), old.configYaml()); Files.writeString(dir.resolve("run.sh"), old.runScript());
            Files.createDirectories(old.unit().getParent()); Files.writeString(old.unit(), old.unitText());
            Files.writeString(dir.resolve("previous"), "http://elsewhere:8211\nlocal-model\n");
            Config.set("RESEARCHZOSHO_DRIVE", "http://127.0.0.1:8211");
            ModelServer.health = base -> true;
            var out = new ByteArrayOutputStream();
            String r = ModelServer.install(null, "all", 20, false, new PrintStream(out, true));
            assertEquals("gpt-oss-20b", r, out.toString());
            String y = Files.readString(dir.resolve("config.yaml"));
            assertTrue(y.contains("\"embed\":") && y.contains("groups:"), y);
            assertTrue(y.contains("ttl: 2700") && Files.readString(dir.resolve("run.sh")).contains("--gpus '\"device=3\"'") && Files.readString(old.unit()).contains("0.0.0.0:8211"), "the old plan is kept: idle minutes, the card, the listen address");
            assertTrue(Files.readString(dir.resolve("run-embed.sh")).contains("--gpus '\"device=3\"'"), "the embedder takes the same card");
            assertTrue(Files.exists(dir.resolve("plan.properties")));
            assertTrue(ran.stream().anyMatch(c -> c.equals(List.of("systemctl", "--user", "restart", "researchzosho-model"))), "the proxy is restarted to read the new config: " + ran);
            assertTrue(Files.readString(Config.userConfigPath()).matches("(?s).*(?m)^embed\\s*=\\s*http://127\\.0\\.0\\.1:8211\\s*$.*"));
            assertEquals("", ModelServer.upgrade(new PrintStream(new ByteArrayOutputStream())), "already current: nothing the second time");
            assertTrue(Files.readString(dir.resolve("previous")).split("\n", -1).length >= 3, "previous carries the embed line now");
        } finally {
            System.setProperty("user.home", realHome);
            Config.invalidate();
        }
    }

    /** A Linux box as the installer sees it: a card (total and free MiB), RAM, and the core map lscpu prints. */
    private void linuxBox(int cardMiB, int freeMiB, int ramGb, String lscpu) {
        ModelServer.os = ModelServer.Os.linux;
        ModelServer.runner = cmd -> {
            String all = String.join(" ", cmd);
            if (cmd.get(0).equals("nvidia-smi")) return new ModelServer.Result(0, all.contains("memory.free") ? freeMiB + "\n" : all.contains("compute_cap") ? "8.9\n" : cardMiB + "\n");
            if (all.contains("MemTotal")) return new ModelServer.Result(0, String.valueOf(ramGb * 1073741824L) + "\n");
            if (cmd.get(0).equals("lscpu")) return new ModelServer.Result(0, lscpu);
            if (cmd.get(0).equals("docker")) return new ModelServer.Result(0, "map[nvidia:{...} runc:{...}]");
            return new ModelServer.Result(0, "");
        };
    }

    @Test
    void aSmallCardWithEnoughRamGetsTheExpertsInRamModelPinnedToItsFastCores() {
        // a laptop: 8 GB card with a desktop on it, 93 GB RAM, six fast cores with two threads each and eight efficiency cores
        StringBuilder hybrid = new StringBuilder("# CPU,Core,Maxmhz\n");
        for (int cpu = 0; cpu < 12; cpu++) hybrid.append(cpu).append(',').append(cpu / 2).append(",5200.0000\n");
        for (int cpu = 12; cpu < 20; cpu++) hybrid.append(cpu).append(',').append(cpu - 6).append(",4100.0000\n");
        linuxBox(8188, 6700, 93, hybrid.toString());
        ModelServer.Row row = ModelServer.rowFor(ModelServer.budgetGb());
        assertEquals("qwen3.6-35b-a3b", row.name());
        assertEquals(List.of(0, 2, 4, 6, 8, 10), ModelServer.fastCores(), "one logical CPU for each fast core; the efficiency cores halved the writing speed when measured");
        assertEquals(" --cpu-moe --no-mmproj-offload -t 6 -tb 6", ModelServer.moeAndThreads(row), "6.5 GB free, and the picture file and the embeddings server share the card: every expert stays in RAM (the first real install left 0.6 GB free)");
        // a 16 GB desktop card, eight plain cores with two threads each
        StringBuilder plain = new StringBuilder();
        for (int cpu = 0; cpu < 16; cpu++) plain.append(cpu).append(',').append(cpu % 8).append(",4500.0000\n");
        linuxBox(16380, 16300, 62, plain.toString());
        assertEquals(" --n-cpu-moe 23 -t 8 -tb 8", ModelServer.moeAndThreads(ModelServer.rowFor(ModelServer.budgetGb())), "17 layers on a 16 GB card beside the picture file and the embeddings server");
        assertEquals("--n-cpu-moe 18", ModelServer.moeArgs(ModelServer.rowFor(16, 64), 15.9), "and with the card to itself, the setting measured on it: 14.2 GB used");
        // no free memory worth the name: everything in RAM
        linuxBox(8188, 5000, 64, plain.toString());
        assertTrue(ModelServer.moeAndThreads(ModelServer.rowFor(ModelServer.budgetGb())).startsWith(" --cpu-moe"));
    }

    @Test
    void afterAnUpdateTheSuggestionIsSaidOnceItDiffersAndNeverForAModelThePersonChose(@TempDir Path home) throws Exception {
        String realHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        Config.invalidate();
        try {
            StringBuilder plain = new StringBuilder();
            for (int cpu = 0; cpu < 16; cpu++) plain.append(cpu).append(',').append(cpu % 8).append(",4500.0000\n");
            linuxBox(16380, 16300, 62, plain.toString());
            assertEquals("", ModelServer.suggestionNotice(), "nothing of ours installed: nothing to say");
            Path dir = ModelServer.dir(); Files.createDirectories(dir);
            // what an earlier release installed on this 16 GB box, before the experts-in-RAM row existed; no `chosen` line yet
            Files.writeString(dir.resolve("plan.properties"), "name=gpt-oss-20b\nfile=" + home.resolve("models/gpt-oss-20b-F16.gguf") + "\nctx=32768\nparallel=2\nextra=\ngpus=all\nidle_minutes=20\nshare=false\n");
            String said = ModelServer.suggestionNotice();
            assertTrue(said.startsWith("This version suggests qwen3.6-35b-a3b for this machine") && said.contains("it runs gpt-oss-20b") && said.contains("researchzosho model switch"), said);
            Files.writeString(dir.resolve("plan.properties"), Files.readString(dir.resolve("plan.properties")) + "chosen=yours\n");
            assertEquals("", ModelServer.suggestionNotice(), "the person chose this model: their choice is not second-guessed");
            Files.writeString(dir.resolve("plan.properties"), "name=qwen3.6-35b-a3b\nfile=" + home.resolve("models/x.gguf") + "\nchosen=suggested\n");
            assertEquals("", ModelServer.suggestionNotice(), "already on the suggested model");
            // prune: what the installed model does not use is listed, and removed only when asked
            Files.createDirectories(home.resolve("models"));
            Files.writeString(home.resolve("models/x.gguf"), "in use"); Files.writeString(home.resolve("models/gpt-oss-20b-F16.gguf"), "old"); Files.writeString(home.resolve("models/mmproj-qwen3.6-35b-a3b-F16.gguf"), "in use");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            String listed = ModelServer.prune(false, new PrintStream(out));
            assertTrue(listed.startsWith("1 file(s)") && out.toString().contains("gpt-oss-20b-F16.gguf") && Files.exists(home.resolve("models/gpt-oss-20b-F16.gguf")), listed + out);
            assertTrue(ModelServer.prune(true, new PrintStream(out)).endsWith("removed"));
            assertFalse(Files.exists(home.resolve("models/gpt-oss-20b-F16.gguf")));
            assertTrue(Files.exists(home.resolve("models/x.gguf")) && Files.exists(home.resolve("models/mmproj-qwen3.6-35b-a3b-F16.gguf")));
        } finally {
            System.setProperty("user.home", realHome);
            Config.invalidate();
        }
    }

    @Test
    void theModelsGuideOffersTheExpertsInRamModelOnlyWhereItRuns() {
        linuxBox(8188, 6700, 93, "0,0,4000\n");
        String laptop = Models.describe(8);
        assertTrue(laptop.contains("93 GB of RAM") && laptop.contains("Qwen3.6-35B-A3B") && laptop.indexOf("Qwen3.6-35B-A3B") < laptop.indexOf("With the model wholly on the card"), laptop);
        linuxBox(8188, 6700, 16, "0,0,4000\n");
        assertFalse(Models.describe(8).contains("Qwen3.6-35B-A3B"), "16 GB of RAM: the guide is what it was");
        linuxBox(49140, 49000, 128, "0,0,4000\n");
        assertFalse(Models.describe(48).contains("experts in RAM"), "a card that holds the 27B is offered the 27B");
        assertTrue(Models.describeAll().contains("Any card with 4 GB free, and 32 GB of RAM"));
    }

    @Test
    void withoutTheRamOrWithABigCardTheChoiceIsWhatItWas() {
        linuxBox(8188, 8000, 16, "0,0,4000\n1,1,4000\n");
        assertEquals("qwen3.5-9b", ModelServer.rowFor(ModelServer.budgetGb()).name(), "16 GB of RAM cannot hold the experts");
        linuxBox(49140, 49000, 128, "0,0,4000\n");
        assertEquals("qwen3.8-27b", ModelServer.rowFor(ModelServer.budgetGb()).name(), "a card that holds the 27B whole still gets it");
        assertEquals("", ModelServer.moeAndThreads(ModelServer.rowFor(ModelServer.budgetGb())), "and a dense model runs with the flags it always had");
        ModelServer.os = ModelServer.Os.macos;
        assertEquals("qwen3.8-27b", ModelServer.rowFor(33, 48).name(), "unified memory that holds the 27B whole gets the 27B");
        assertEquals("gpt-oss-20b", ModelServer.rowFor(22, 32).name(), "and below that the choice on a Mac is what it was: its memory is one pool, so there is no RAM to put experts in");
        ModelServer.os = ModelServer.Os.windows;
        assertEquals("qwen3.6-35b-a3b", ModelServer.rowFor(16, 64).name(), "Windows runs it under Vulkan: the mechanism was run there, and the model itself under Vulkan on Linux");
        assertEquals("gpt-oss-20b", ModelServer.rowFor(16, 16).name());
    }

    @Test
    void onMacOsInstallFetchesTheMetalBuildSizesByUnifiedMemoryAndLoadsALaunchAgent(@TempDir Path home) throws Exception {
        String realHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        Config.invalidate();
        boolean[] started = {false}; List<String> fetched = new ArrayList<>();
        try {
            List<List<String>> ran = fakeMachine(ModelServer.Os.macos, started, fetched);
            assertEquals(11, (int) ModelServer.budgetGb(), "seven tenths of 16 GB");
            assertTrue(ModelServer.offer().startsWith("Serve qwen3.5-9b on this machine on demand (Metal)"), ModelServer.offer());
            String r = ModelServer.install(null, "all", 20, false, new PrintStream(new ByteArrayOutputStream()));
            assertEquals("qwen3.5-9b", r);
            assertTrue(fetched.stream().anyMatch(u -> u.contains("llama-swap_255_darwin_")), fetched.toString());
            assertTrue(fetched.stream().anyMatch(u -> u.contains("/releases/download/b10929/llama-b10929-bin-macos-")), fetched.toString());
            Path dir = ModelServer.dir();
            assertTrue(Files.exists(dir.resolve("llama").resolve("llama-server")));
            assertTrue(Files.readString(dir.resolve("config.yaml")).contains("llama-server -m " + home.resolve("models/Qwen3.5-9B-Q4_K_M.gguf")));
            // the file that lets the model read pictures comes with it, checked like every download, and the server is started with it
            assertTrue(fetched.contains("https://huggingface.co/unsloth/Qwen3.5-9B-GGUF/resolve/main/mmproj-F16.gguf"), fetched.toString());
            assertTrue(Files.readString(dir.resolve("config.yaml")).contains(" --mmproj " + home.resolve("models/mmproj-qwen3.5-9b-F16.gguf") + " --host"), Files.readString(dir.resolve("config.yaml")));
            Path plist = home.resolve("Library/LaunchAgents/org.researchzosho.model.plist");
            assertTrue(Files.readString(plist).contains("<string>org.researchzosho.model</string>"));
            assertTrue(ran.stream().anyMatch(c -> c.size() >= 4 && c.get(0).equals("launchctl") && c.get(1).equals("bootstrap") && c.get(3).equals(plist.toString())), ran.toString());
            assertEquals("http://127.0.0.1:8211", Config.get("RESEARCHZOSHO_DRIVE"));
        } finally {
            System.setProperty("user.home", realHome);
            Config.invalidate();
        }
    }

    @Test
    void onWindowsInstallFetchesTheVulkanBuildSizesByRamWithoutAnNvidiaCardAndRegistersALogonTask(@TempDir Path home) throws Exception {
        String realHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        Config.invalidate();
        boolean[] started = {false}; List<String> fetched = new ArrayList<>();
        try {
            List<List<String>> ran = fakeMachine(ModelServer.Os.windows, started, fetched);
            Assumptions.assumeTrue(System.getProperty("os.arch", "").contains("64") && !System.getProperty("os.arch", "").contains("aarch"), "the Windows path is x64");
            assertEquals(6, (int) ModelServer.budgetGb(), "half of 13 GB RAM when no NVIDIA card answers");
            assertTrue(ModelServer.offer().startsWith("Serve gemma-4-e4b on this machine on demand (Vulkan)"), ModelServer.offer());
            String r = ModelServer.install(null, "all", 20, false, new PrintStream(new ByteArrayOutputStream()));
            assertEquals("gemma-4-e4b", r);
            assertTrue(fetched.stream().anyMatch(u -> u.endsWith("llama-swap_255_windows_amd64.zip")), fetched.toString());
            assertTrue(fetched.stream().anyMatch(u -> u.endsWith("llama-b10929-bin-win-vulkan-x64.zip")), fetched.toString());
            Path dir = ModelServer.dir();
            assertTrue(Files.exists(dir.resolve("llama").resolve("llama-server.exe")));
            assertTrue(Files.readString(dir.resolve("config.yaml")).contains("llama-server.exe -m "));
            assertTrue(Files.readString(dir.resolve("start.ps1")).contains("Start-Process -FilePath '" + dir.resolve("llama-swap.exe") + "'"));
            assertTrue(ran.stream().anyMatch(c -> c.get(0).equals("schtasks") && c.contains("/create") && c.contains("ResearchZoshoModel") && c.contains("onlogon")), ran.toString());
            assertTrue(ran.stream().anyMatch(c -> c.get(0).equals("powershell") && c.contains("-File") && c.contains(dir.resolve("start.ps1").toString())), "started now, detached: " + ran);
            assertEquals("http://127.0.0.1:8211", Config.get("RESEARCHZOSHO_DRIVE"));
        } finally {
            System.setProperty("user.home", realHome);
            Config.invalidate();
        }
    }

    @Test
    void withoutDockerOnLinuxItSaysWhy() {
        ModelServer.os = ModelServer.Os.linux;
        ModelServer.health = base -> false;
        ModelServer.runner = cmd -> { throw new IOException("docker: not found"); };
        assertTrue(ModelServer.unsupported().contains("docker"), ModelServer.unsupported());
        assertNull(ModelServer.offer());
        ModelServer.runner = cmd -> cmd.get(0).equals("docker") ? new ModelServer.Result(0, "map[runc:{...}]") : new ModelServer.Result(0, "16380");
        assertTrue(ModelServer.unsupported().contains("NVIDIA runtime"), ModelServer.unsupported());
    }

    @Test
    void aDownloadThatDoesNotMatchItsRecordedHashIsRefusedAndDeleted(@TempDir Path home) throws Exception {
        String realHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        Config.invalidate();
        boolean[] started = {false}; List<String> fetched = new ArrayList<>();
        try {
            fakeMachine(ModelServer.Os.linux, started, fetched);
            ModelServer.downloader = (url, dest) -> { fetched.add(url); Files.createDirectories(dest.getParent()); Files.writeString(dest, "tampered"); };   // the recorded hash stays
            String r = ModelServer.install(null, "all", 20, false, new PrintStream(new ByteArrayOutputStream()));
            assertTrue(r.startsWith("!checksum mismatch for gpt-oss-20b-F16.gguf"), r);
            assertFalse(Files.exists(home.resolve("models/gpt-oss-20b-F16.gguf")), "nothing kept");
            assertNull(Config.get("RESEARCHZOSHO_DRIVE"), "the drive was not touched");
        } finally {
            System.setProperty("user.home", realHome);
            Config.invalidate();
        }
    }

    @Test
    void uninstallRefusesWhileTheOtherProductStillPointsAtTheProxy(@TempDir Path home) throws Exception {
        String realHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        Config.invalidate();
        try {
            ModelServer.os = ModelServer.Os.linux;
            ModelServer.runner = cmd -> new ModelServer.Result(0, "");
            Files.createDirectories(home.resolve(".researchzosho"));   // this product's own state dir, so its settings are its own file
            Path sib = home.resolve(".codezaiku").resolve("config");
            Files.createDirectories(sib.getParent());
            Files.writeString(sib, "drive = http://127.0.0.1:8211\n");
            String r = ModelServer.uninstall();
            assertTrue(r.startsWith("!CodeZaiku's settings also point at this proxy"), r);
            assertFalse(ModelServer.uninstall(true).startsWith("!"), "forced");
        } finally {
            System.setProperty("user.home", realHome);
            Config.invalidate();
        }
    }

    @Test
    void checkReadsEveryRowAndPinnedBuildAndNamesWhatIsGone() {
        ModelServer.os = ModelServer.Os.linux;
        ModelServer.runner = cmd -> new ModelServer.Result(0, cmd.get(cmd.size() - 1).contains("gpt-oss") ? "404" : "200");
        var out = new ByteArrayOutputStream();
        assertEquals(1, ModelServer.check(new PrintStream(out, true)));
        String o = out.toString();
        assertTrue(o.contains("GONE 404  https://huggingface.co/unsloth/gpt-oss-20b-GGUF/resolve/main/gpt-oss-20b-F16.gguf"), o);
        assertTrue(o.contains("ok   200  https://huggingface.co/unsloth/Qwen3.8-27B-GGUF/resolve/main/Qwen3.8-27B-UD-Q4_K_M.gguf"), o);
        assertTrue(o.contains("llama-swap_255_windows_amd64.zip") && o.contains("llama-b10929-bin-win-vulkan-x64.zip"), o);
        assertTrue(o.contains("1 missing"), o);
    }

    @Test
    void checkReadsARateLimitAsUncheckedNotGone() {
        ModelServer.os = ModelServer.Os.linux;
        ModelServer.checkBackoffMs = 0;
        var calls = new AtomicInteger();
        ModelServer.runner = cmd -> { String u = cmd.get(cmd.size() - 1); if (u.contains("gemma-4-E2B")) calls.incrementAndGet(); return new ModelServer.Result(0, u.contains("gemma-4-E2B") ? "429" : "200"); };
        var out = new ByteArrayOutputStream();
        assertEquals(0, ModelServer.check(new PrintStream(out, true)), "a 429 is not a missing file: the release gate does not fail on it");
        String o = out.toString();
        assertEquals(3, calls.get(), "asked three times before giving up on the host");
        assertTrue(o.contains("WAIT 429  https://huggingface.co/unsloth/gemma-4-E2B-it-GGUF/resolve/main/gemma-4-E2B-it-Q4_K_M.gguf"), o);
        assertTrue(o.contains("1 not checked") && !o.contains("GONE") && !o.contains(" missing:"), o);
    }
}
