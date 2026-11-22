package org.researchzosho.librarian;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The model server with no way out (0.8.1). On Linux `model install` puts the model's and the embeddings' containers on a Docker
 * network that has no route to anything but the machine, proves it (the servers answer through it, an outside address does not
 * answer from inside), and steps back to the earlier way when the proof does not hold. A stand-in answers for docker, for the
 * machine's routes and for the model, so every branch runs here: the arrangement written, each step back, what the status says, an
 * earlier version's install left alone and changed over when asked.
 */
class ModelServerShutInTest {
    final ModelServer.Os realOs = ModelServer.os;
    final ModelServer.Runner realRunner = ModelServer.runner;
    final ModelServer.Downloader realDownloader = ModelServer.downloader;
    final Predicate<String> realHealth = ModelServer.health;
    final ModelServer.Asker realAsker = ModelServer.asker;
    final Map<String, String> realSums = new HashMap<>(ModelServer.sums);
    final String realHome = System.getProperty("user.home");
    Path realDrm = Gpu.SYS_DRM, realKfd = Gpu.SYS_KFD, realDev = Gpu.DEV;

    static final String SHA_X = "2d711642b726b04401627ca9fbac32f5c8530fb1903cc4db02258717921a4881";   // sha256 of "x", what the stand-in's downloads hold
    static final String NET = ModelServer.NETWORK, RANGE = "172.16.211.0/28";
    static final String HOME_ROUTES = "default via 192.168.1.1 dev eth0 proto dhcp src 192.168.1.20 metric 100\n192.168.1.0/24 dev eth0 proto kernel scope link src 192.168.1.20\n"
            + "172.17.0.0/16 dev docker0 proto kernel scope link src 172.17.0.1 linkdown\nlocal 127.0.0.0/8 dev lo table local proto kernel scope host src 127.0.0.1\n";

    @TempDir Path home;

    /** The machine the stand-in describes: its routes, Docker's networks, what the proof's commands answer, what the model answers. */
    final class Machine {
        final List<List<String>> ran = new ArrayList<>();
        final Map<String, String> networks = new LinkedHashMap<>();   // name → "true|false subnet"
        final List<String> fetched = new ArrayList<>(), asked = new ArrayList<>();
        String routes = HOME_ROUTES;
        List<String> refused = List.of();              // ranges Docker will not give
        int control = 0, inside = 7, insideEmbed = 7, names = 2, ip = 0, pull = 0;
        boolean images = true, proxyUpWhenShut = true, started, bridgesRouted = true;
        String controlSaid = "13.33.109.46";
        String modelShut = "", modelOpen = "", embedShut = "", embedOpen = "";   // "": answers; "!why": does not

        boolean shutNow() { try { return Files.readString(ModelServer.dir().resolve("config.yaml")).contains("proxy: http://172.") || Files.readString(ModelServer.dir().resolve("config.yaml")).contains("proxy: http://10."); } catch (IOException e) { return false; } }

        ModelServer.Result run(List<String> cmd) {
            ran.add(cmd);
            String c = cmd.get(0), all = String.join(" ", cmd);
            if (c.equals("ip")) {   // the machine's routes, and one for each Docker network: its bridge is an interface of the machine (not so with rootless Docker)
                StringBuilder b = new StringBuilder(routes);
                if (bridgesRouted) networks.forEach((name, net) -> { String subnet = net.split(" ")[1]; if (!routes.contains(subnet + " dev br-")) b.append(subnet).append(" dev br-6bd65cf20e25 proto kernel scope link src ").append(subnet.replaceFirst("\\.\\d+/\\d+$", ".1")).append(" linkdown\n"); });
                return new ModelServer.Result(ip, b.toString());
            }
            if (c.equals("nvidia-smi")) return new ModelServer.Result(0, all.contains("compute_cap") ? "8.9\n" : all.contains("name,") ? "NVIDIA RTX A4000, 16380\n" : "16380\n");
            if (c.startsWith("curl")) return new ModelServer.Result(0, "HTTP/2 200\ncontent-length: 13780000000\n");
            if (c.equals("tar")) {
                try { Path into = Path.of(cmd.get(cmd.indexOf("-C") + 1)); Files.createDirectories(into); Files.writeString(into.resolve("llama-swap"), "x"); } catch (IOException e) { return new ModelServer.Result(1, e.toString()); }
                return new ModelServer.Result(0, "");
            }
            if (c.equals("systemctl")) { if (cmd.contains("enable") || cmd.contains("restart")) started = true; return new ModelServer.Result(0, cmd.contains("is-active") ? "active\n" : ""); }
            if (!c.equals("docker")) return new ModelServer.Result(0, "");
            String verb = cmd.get(1);
            if (verb.equals("info")) return new ModelServer.Result(0, "map[nvidia:{...} runc:{...}]");
            if (verb.equals("network")) {
                String op = cmd.get(2);
                if (op.equals("inspect")) {
                    String have = networks.get(cmd.get(3));
                    if (have == null) return new ModelServer.Result(1, "Error response from daemon: network " + cmd.get(3) + " not found\n");
                    return new ModelServer.Result(0, (all.contains(".IPAM") ? have : have.split(" ")[0]) + "\n");
                }
                if (op.equals("create")) {
                    String subnet = cmd.get(cmd.indexOf("--subnet") + 1), name = cmd.get(cmd.size() - 1);
                    if (refused.contains(subnet)) return new ModelServer.Result(1, "Error response from daemon: invalid pool request: Pool overlaps with other one on this address space\n");
                    networks.put(name, cmd.contains("--internal") + " " + subnet);
                    return new ModelServer.Result(0, "6bd65cf20e25\n");
                }
                if (op.equals("rm")) return new ModelServer.Result(networks.remove(cmd.get(3)) != null ? 0 : 1, "");
            }
            if (verb.equals("image")) return new ModelServer.Result(images ? 0 : 1, images ? "sha256:abc\n" : "Error response from daemon: No such image\n");
            if (verb.equals("pull")) return new ModelServer.Result(pull, pull == 0 ? "" : "Error response from daemon: Get \"https://ghcr.io/v2/\": dial tcp: lookup ghcr.io: no such host\n");
            if (verb.equals("run") && cmd.contains("--entrypoint")) return new ModelServer.Result(control, control == 0 ? controlSaid : "curl: (6) Could not resolve host: huggingface.co\n");
            if (verb.equals("exec") && cmd.contains("curl")) {
                int rc = cmd.get(2).equals(ModelServer.EMBED_CONTAINER) ? insideEmbed : inside;
                return new ModelServer.Result(rc, rc == 7 ? "curl: (7) Failed to connect to 13.33.109.46 port 443 after 0 ms: Couldn't connect to server\n" : rc >= 125 ? "OCI runtime exec failed: exec failed: unable to start container process: exec: \"curl\": executable file not found in $PATH\n" : "");
            }
            if (verb.equals("exec") && cmd.contains("getent")) return new ModelServer.Result(names, names == 0 ? "2600:9000:200b:c000:17:b174:6d00:93a1 huggingface.co\n" : "");
            return new ModelServer.Result(0, "");
        }

        void install() {
            ModelServer.os = ModelServer.Os.linux;
            ModelServer.runner = this::run;
            ModelServer.downloader = (url, dest) -> {
                fetched.add(url); Files.createDirectories(dest.getParent()); Files.writeString(dest, "x");
                ModelServer.sums.put(dest.getFileName().toString(), SHA_X); ModelServer.sums.put(dest.getParent().getFileName() + "/" + dest.getFileName(), SHA_X);
            };
            ModelServer.health = base -> started && (proxyUpWhenShut || !shutNow());
            ModelServer.asker = new ModelServer.Asker() {
                @Override public String complete(String base, String model, int seconds) { asked.add("complete " + (shutNow() ? "shut" : "open")); return shutNow() ? modelShut : modelOpen; }
                @Override public String embed(String base, int seconds) { asked.add("embed " + (shutNow() ? "shut" : "open")); return shutNow() ? embedShut : embedOpen; }
                @Override public void rest(String base) { asked.add("rest"); }
            };
        }

        boolean ranLine(String... words) { return ran.stream().anyMatch(c -> c.equals(List.of(words))); }
        long count(String... first) { return ran.stream().filter(c -> c.size() >= first.length && c.subList(0, first.length).equals(List.of(first))).count(); }
    }

    Machine machine;

    @BeforeEach void standIn() throws Exception {
        Path sys = Files.createTempDirectory("rz-sys");
        for (String d : List.of("drm", "kfd", "dev")) Files.createDirectories(sys.resolve(d));
        Gpu.SYS_DRM = sys.resolve("drm"); Gpu.SYS_KFD = sys.resolve("kfd"); Gpu.DEV = sys.resolve("dev");
        System.setProperty("user.home", home.toString());
        Config.invalidate();
        machine = new Machine();
        machine.install();
    }

    @AfterEach void restore() {
        Gpu.SYS_DRM = realDrm; Gpu.SYS_KFD = realKfd; Gpu.DEV = realDev;
        ModelServer.sums.clear(); ModelServer.sums.putAll(realSums);
        ModelServer.os = realOs; ModelServer.runner = realRunner; ModelServer.downloader = realDownloader; ModelServer.health = realHealth; ModelServer.asker = realAsker;
        System.setProperty("user.home", realHome);
        Config.invalidate();
    }

    String install() {
        var out = new ByteArrayOutputStream();
        String r = ModelServer.install(null, "all", 10, false, new PrintStream(out, true));
        assertFalse(r.startsWith("!"), r + "\n" + out);
        return out.toString();
    }

    String file(String name) throws IOException { return Files.readString(ModelServer.dir().resolve(name)); }
    Map<String, String> plan() throws IOException { return ModelServer.readPlan(ModelServer.dir()); }

    /** The three files as the earlier way writes them for this machine's install: what a step back has to leave, to the letter. */
    Map<String, String> earlierFiles(String folder) {
        Path models = home.resolve("models");
        return ModelServer.filesOf(ModelServer.plan(ModelServer.Os.linux, ModelServer.rowFor(16, 16), models.resolve("gpt-oss-20b-F16.gguf"), ModelServer.dir(), ModelServer.unitPath(ModelServer.Os.linux), "all", 10, false, "89-1.8", "cuda", null, folder));
    }

    void assertEarlierWay(String folder) throws IOException {
        for (var f : earlierFiles(folder).entrySet()) assertEquals(f.getValue(), file(f.getKey()), f.getKey() + " is the earlier way's, to the letter");
        assertTrue(file("run.sh").contains(" -p 127.0.0.1:$1:8080 ") && !file("run.sh").contains("--network"), file("run.sh"));
        assertNull(machine.networks.get(NET), "the network of the attempt is gone");
    }

    // ---- the arrangement written ----

    @Test
    void theEarlierWaysFilesAreToTheLetterWhatVersion080Wrote(@TempDir Path tmp) {
        Path dir = tmp.resolve("model"), models = tmp.resolve("models");
        var row = new ModelServer.Row("qwen3.5-4b-bf16", 0, "", "Qwen3.5-4B-bf16.gguf", "", 65536, 2, "");
        var p = ModelServer.plan(ModelServer.Os.linux, row, models.resolve("Qwen3.5-4B-bf16.gguf"), dir, tmp.resolve("u.service"), "all", 10, false, "89-1.8", "cuda", null, null);
        // the files released 0.8.0 wrote for this install, as kept from its own code on 2026-11-20; only the folders differ
        assertEquals("#!/bin/sh\n"
                + "# qwen3.5-4b-bf16 in llama.cpp, started by llama-swap on demand; $1 is the port it assigned. The flags are the measured ones.\n"
                + "exec docker run --rm --name researchzosho-model --gpus all -p 127.0.0.1:$1:8080 -v " + models + ":/m ghcr.io/ggml-org/llama.cpp:server-cuda \\\n"
                + "  -m /m/Qwen3.5-4B-bf16.gguf --host 0.0.0.0 --port 8080 --jinja -c 65536 --parallel 2 -ngl 99 --flash-attn on\n", p.runScript());
        assertEquals("#!/bin/sh\n"
                + "# The embeddings server (Text Embeddings Inference, Qwen/Qwen3-Embedding-0.6B), started by llama-swap on demand beside the model; $1 is the port it assigned.\n"
                + "# The model downloads into " + Embed.dir() + " on the first start.\n"
                + "exec docker run --rm --name researchzosho-model-embed --gpus all -p 127.0.0.1:$1:80 -v " + Embed.dir().toAbsolutePath() + ":/data ghcr.io/huggingface/text-embeddings-inference:89-1.8 \\\n"
                + "  --model-id Qwen/Qwen3-Embedding-0.6B --pooling last-token --max-client-batch-size 128 --max-batch-tokens 65536 --auto-truncate\n", p.embedScript());
        assertEquals("# ResearchZosho: the model server on demand. llama-swap listens on 8211; the server starts on the first request\n"
                + "# and stops after ttl seconds idle. `researchzosho model status` reads it.\n"
                + "healthCheckTimeout: 600\nstartPort: 10001\nlogLevel: info\nmodels:\n"
                + "  \"qwen3.5-4b-bf16\":\n    cmd: " + dir.resolve("run.sh") + " ${PORT}\n    cmdStop: docker stop researchzosho-model\n    proxy: http://127.0.0.1:${PORT}\n    ttl: 600\n"
                + "    aliases: [\"local-model\", \"Qwen3.5-4B-bf16.gguf\", \"/m/Qwen3.5-4B-bf16.gguf\"]\n"
                + "  \"embed\":\n    cmd: " + dir.resolve("run-embed.sh") + " ${PORT}\n    cmdStop: docker stop researchzosho-model-embed\n    proxy: http://127.0.0.1:${PORT}\n    checkEndpoint: /health\n    ttl: 300\n"
                + "    aliases: [\"Qwen/Qwen3-Embedding-0.6B\", \"text-embedding\"]\n"
                + "groups:\n  \"embedding\":\n    swap: false\n    exclusive: false\n    persistent: true\n    members: [\"embed\"]\n", p.configYaml());
    }

    @Test
    void withNoWayOutEachContainerIsOnTheNetworkAtItsAddressAndTheProxyPassesToIt(@TempDir Path tmp) {
        Path dir = tmp.resolve("model"), models = tmp.resolve("models");
        var shut = ModelServer.Shut.of(RANGE);
        assertEquals("172.16.211.2", shut.modelAt()); assertEquals("172.16.211.3", shut.embedAt());
        var row = ModelServer.rowFor(16, 16);
        var open = ModelServer.plan(ModelServer.Os.linux, row, models.resolve(row.file()), dir, tmp.resolve("u"), "3", 10, false, "89-1.8", "cuda", null, "/data/" + Embed.FOLDER);
        var p = ModelServer.plan(ModelServer.Os.linux, row, models.resolve(row.file()), dir, tmp.resolve("u"), "3", 10, false, "89-1.8", "cuda", shut, "/data/" + Embed.FOLDER);
        String run = p.runScript(), embed = p.embedScript(), y = p.configYaml();
        assertTrue(run.contains("exec docker run --rm --name researchzosho-model --gpus '\"device=3\"' --network " + NET + " --ip 172.16.211.2 --cap-drop NET_RAW -v " + models + ":/m ghcr.io/ggml-org/llama.cpp:server-cuda"), run);
        assertFalse(run.contains(" -p ") || embed.contains(" -p "), "no port of the machine is published: " + run + embed);
        assertTrue(run.contains("\ndocker network inspect " + NET + " >/dev/null 2>&1 || docker network create --internal --subnet " + RANGE + " " + NET + " >/dev/null 2>&1\nexec docker run "), "a network that was removed is made again before the start: " + run);
        assertTrue(embed.contains("--name researchzosho-model-embed --gpus '\"device=3\"' --network " + NET + " --ip 172.16.211.3 --cap-drop NET_RAW -v " + Embed.dir().toAbsolutePath() + ":/data:ro " + Embed.IMAGE + ":89-1.8"), embed);
        assertTrue(embed.contains("--model-id /data/" + Embed.FOLDER + " --pooling last-token") && !embed.contains("--model-id " + Embed.MODEL), "it reads its model from the folder and asks nobody: " + embed);
        assertTrue(y.contains("    cmd: " + dir.resolve("run.sh") + "\n    cmdStop: docker stop researchzosho-model\n    proxy: http://172.16.211.2:8080\n"), y);
        assertTrue(y.contains("    cmd: " + dir.resolve("run-embed.sh") + "\n    cmdStop: docker stop researchzosho-model-embed\n    proxy: http://172.16.211.3:80\n"), y);
        assertFalse(y.contains("${PORT}"), "the proxy assigns no port: " + y);
        assertEquals(open.unitText(), p.unitText(), "the service is the same one");
        // one text decides the difference, both ways: turned back, the files are the earlier way's to the letter
        assertEquals(ModelServer.filesOf(open), ModelServer.toOpen(dir, ModelServer.filesOf(p)));
        assertEquals(ModelServer.filesOf(p), ModelServer.toShut(dir, ModelServer.filesOf(open), shut));
        // llama.cpp as the embeddings server (an AMD or Intel chip) listens on 8080 inside, and its container is turned the same way
        var amd = ModelServer.plan(ModelServer.Os.linux, row, models.resolve(row.file()), dir, tmp.resolve("u"), "all", 10, false, "llama", "vulkan", shut, null);
        assertTrue(amd.embedScript().contains("--device /dev/dri --group-add video --network " + NET + " --ip 172.16.211.3 --cap-drop NET_RAW -v " + models + ":/m ghcr.io/ggml-org/llama.cpp:server-vulkan") && amd.configYaml().contains("proxy: http://172.16.211.3:8080\n"), amd.embedScript() + amd.configYaml());
        assertTrue(amd.runScript().contains("--device /dev/dri --group-add video --network " + NET + " --ip 172.16.211.2 --cap-drop NET_RAW"), amd.runScript());
        // where the server is a plain program there is no container to shut in: the plan is what it was
        var mac = ModelServer.plan(ModelServer.Os.macos, ModelServer.rowFor(11, 16), models.resolve("Qwen3.5-9B-Q4_K_M.gguf"), dir, tmp.resolve("p"), "all", 10, false, "llama", "", shut, null);
        assertEquals(ModelServer.plan(ModelServer.Os.macos, ModelServer.rowFor(11, 16), models.resolve("Qwen3.5-9B-Q4_K_M.gguf"), dir, tmp.resolve("p"), "all", 10, false, "llama").configYaml(), mac.configYaml());
        // files that are not of the shape this program writes are not turned at all
        Map<String, String> edited = new LinkedHashMap<>(ModelServer.filesOf(open));
        edited.put("run.sh", edited.get("run.sh").replace("-p 127.0.0.1:$1:8080", "-p 8080:8080"));
        assertNull(ModelServer.toShut(dir, edited, shut), "a start script with another published port is the person's own");
    }

    // ---- the free range ----

    @Test
    void theNetworkTakesTheFirstRangeNoRouteOfTheMachineOverlapsAndDockerAccepts() {
        assertArrayEquals(new long[]{0xAC10D300L, 0xAC10D30FL}, ModelServer.range("172.16.211.0/28"));
        assertNull(ModelServer.range("172.16.211.0/40")); assertNull(ModelServer.range("default")); assertNull(ModelServer.range("300.1.1.1"));
        var used = ModelServer.routed(HOME_ROUTES + "100.64.0.5 dev tailscale0 table 52\n0.0.0.0/0 via 10.9.0.1 dev wg0 table 51820\n");
        assertEquals(10, used.size(), "every range and address the routes name (the destinations, the gateways, the machine's own addresses), and not 0.0.0.0/0, which is the default written another way");
        assertTrue(used.stream().noneMatch(r -> r[0] == 0 && r[1] > 0xFFFFFFF0L), "the default route names no range");
        // a home machine: the first of the list is free
        var made = ModelServer.makeNetwork();
        assertEquals(RANGE, made.shut().subnet()); assertNull(made.why());
        assertEquals("true " + RANGE, machine.networks.get(NET), "made with --internal and that range");
        assertTrue(machine.ranLine("docker", "network", "create", "--internal", "--subnet", RANGE, NET), machine.ran.toString());
        // one this program made is used as it is, whatever the routes say now (its own route is among them)
        machine.ran.clear(); machine.routes = HOME_ROUTES + "172.16.211.0/28 dev br-6bd65cf20e25 proto kernel scope link src 172.16.211.1 linkdown\n";
        assertEquals(RANGE, ModelServer.makeNetwork().shut().subnet());
        assertEquals(0, machine.count("docker", "network", "create"), "nothing is made twice");
        // a tunnel routes all of 172.16: the next range that nothing overlaps
        machine.networks.clear(); machine.routes = HOME_ROUTES + "172.16.0.0/12 via 10.8.0.1 dev tun0\n10.8.0.0/24 dev tun0 proto kernel scope link src 10.8.0.6\n";
        assertEquals("10.213.71.0/28", ModelServer.makeNetwork().shut().subnet());
        // a virtual machine's network holds one address of a range: that range is not taken either
        machine.networks.clear(); machine.routes = HOME_ROUTES + "172.16.211.8 dev vmnet1 scope link\n";
        assertEquals("10.213.71.0/28", ModelServer.makeNetwork().shut().subnet());
        // Docker refuses a range one of its own networks holds: the next
        machine.networks.clear(); machine.routes = HOME_ROUTES; machine.refused = List.of(RANGE, "10.213.71.0/28");
        assertEquals("172.16.93.0/28", ModelServer.makeNetwork().shut().subnet());
        // nothing free, or Docker refuses every one: nothing is made, and the reason is words
        machine.networks.clear(); machine.refused = ModelServer.RANGES;
        made = ModelServer.makeNetwork();
        assertNull(made.shut()); assertTrue(made.why().startsWith("Docker did not make its network (Error response from daemon: invalid pool request"), made.why());
        machine.refused = List.of(); machine.routes = "10.0.0.0/8 dev tun0\n172.16.0.0/12 dev tun1\n192.168.0.0/16 dev tun2\n";
        assertEquals("no address range was free for its network", ModelServer.makeNetwork().why());
        assertTrue(machine.networks.isEmpty());
        // rootless Docker, or Docker in a virtual machine: the network is made, the machine has no route to it, and it is taken away again
        machine.routes = HOME_ROUTES; machine.bridgesRouted = false;
        made = ModelServer.makeNetwork();
        assertNull(made.shut()); assertTrue(made.why().startsWith("this machine has no route to its own Docker networks (rootless Docker, or Docker in a virtual machine)"), made.why());
        assertTrue(machine.networks.isEmpty() && machine.ranLine("docker", "network", "rm", NET), "nothing is left behind: " + machine.networks);
        machine.bridgesRouted = true;
        // the routes cannot be read: no range is guessed
        machine.ip = 127; machine.routes = "";
        assertTrue(ModelServer.makeNetwork().why().startsWith("the routes of this machine could not be read"), ModelServer.makeNetwork().why());
        machine.ip = 0; machine.routes = HOME_ROUTES;
        // a network of that name that is not one without a way out is not this program's, and is left alone
        machine.networks.put(NET, "false 172.30.0.0/16");
        made = ModelServer.makeNetwork();
        assertNull(made.shut()); assertTrue(made.why().contains("is already there and is not one this program made"), made.why());
        assertEquals("false 172.30.0.0/16", machine.networks.get(NET));
    }

    // ---- the install proves itself ----

    @Test
    void anInstallIsSetUpWithNoWayOutProvesItAndSaysSo() throws Exception {
        String out = install();
        assertEquals("true " + RANGE, machine.networks.get(NET));
        assertTrue(file("run.sh").contains("--network " + NET + " --ip 172.16.211.2 --cap-drop NET_RAW") && file("config.yaml").contains("proxy: http://172.16.211.2:8080") && file("config.yaml").contains("proxy: http://172.16.211.3:80"), file("run.sh") + file("config.yaml"));
        // the embeddings model is fetched by the installer, each file checked, and the server reads the folder
        for (String name : Embed.FILES.keySet()) assertTrue(machine.fetched.contains("https://huggingface.co/Qwen/Qwen3-Embedding-0.6B/resolve/" + Embed.REVISION + "/" + name), name + ": " + machine.fetched);
        assertTrue(file("run-embed.sh").contains("--model-id /data/" + Embed.FOLDER + " ") && file("run-embed.sh").contains(":/data:ro "), file("run-embed.sh"));
        // the proof, in its order: the control, the embeddings server alone and its container, let go, the model and its container
        assertEquals(List.of("rest", "embed shut", "rest", "complete shut", "rest"), machine.asked, "each alone on the card, and the card left free at the end, as an install always left it");
        List<String> proof = machine.ran.stream().filter(c -> c.get(0).equals("docker") && (c.get(1).equals("exec") || c.contains("--entrypoint"))).map(c -> String.join(" ", c)).toList();
        assertEquals(List.of(
                "docker run --rm --entrypoint curl ghcr.io/ggml-org/llama.cpp:server-cuda -4 -sS -o /dev/null -m 15 -A researchzosho.org -w %{remote_ip} https://huggingface.co/",
                "docker exec researchzosho-model-embed curl -sS -o /dev/null -m 8 -k -A researchzosho.org https://13.33.109.46/",
                "docker exec researchzosho-model curl -sS -o /dev/null -m 8 -k -A researchzosho.org https://13.33.109.46/",
                "docker exec researchzosho-model getent hosts huggingface.co"), proof);
        assertTrue(out.contains("The model server has no way out to the network: its containers are on a Docker network with no route to anything but this machine. Tried just now: the model answered through it, and an outside address that another container reached could not be reached from inside."), out);
        assertFalse(out.contains("could not be set up") || out.contains("looks names up"), out);
        assertEquals("shut", plan().get("network")); assertEquals(RANGE, plan().get("subnet")); assertNotNull(plan().get("network_tried"));
        assertEquals(0, machine.count("systemctl", "--user", "restart"), "nothing was stepped back");
        String status = ModelServer.status();
        assertTrue(status.contains("  network: The model server has no way out to the network: its containers are on a Docker network with no route to anything but this machine (" + NET + "). Tried on " + plan().get("network_tried") + ": the model answered through it, and an outside address could not be reached from inside.\n"), status);
        // given again, the install is as wanted: nothing is asked, nothing restarted
        machine.asked.clear();
        install();
        assertTrue(machine.asked.isEmpty() && machine.count("systemctl", "--user", "restart") == 0, machine.asked.toString());
        // uninstall takes the network with it
        ModelServer.health = base -> false;
        assertFalse(ModelServer.uninstall(true).startsWith("!"));
        assertNull(machine.networks.get(NET), "the network goes with the install that made it");
    }

    @Test
    void whenDockerLooksNamesUpForTheContainerTheInstallAndTheStatusSayIt() throws Exception {
        machine.names = 0;
        String out = install();
        assertTrue(out.contains("has no way out to the network") && out.contains("Docker on this machine still looks names up for the model server, which a program could use to send a little information out. Docker 29 does not."), out);
        assertEquals("looked-up", plan().get("network_names"));
        assertTrue(ModelServer.status().contains("still looks names up for the model server"), ModelServer.status());
    }

    // ---- or steps back ----

    @Test
    void whenTheModelDoesNotAnswerThroughItTheEarlierWayIsSetUpAndSaid() throws Exception {
        machine.modelShut = "!no answer in 660 seconds";
        String out = install();
        assertEarlierWay("/data/" + Embed.FOLDER);
        assertTrue(machine.ranLine("docker", "stop", "researchzosho-model") && machine.ranLine("docker", "stop", "researchzosho-model-embed") && machine.ranLine("docker", "network", "rm", NET), "the attempt's containers and network are taken away: " + machine.ran);
        assertTrue(machine.ranLine("systemctl", "--user", "restart", "researchzosho-model"), "the proxy reads the earlier files: " + machine.ran);
        assertTrue(out.contains("The model server could not be set up with no way out to the network on this machine, because the model did not answer through it (no answer in 660 seconds). It is set up the way earlier versions did, so it can reach the network. To try again: researchzosho model install"), out);
        assertTrue(out.contains("Set up the earlier way, the model answered."), out);
        assertEquals(List.of("rest", "embed shut", "rest", "complete shut", "complete open", "rest"), machine.asked, "asked again the earlier way, so the person reads that it answers");
        assertEquals("open", plan().get("network")); assertEquals("unproven", plan().get("network_why"));
        String status = ModelServer.status();
        assertTrue(status.contains("  network: The model server can reach the network: on " + plan().get("network_tried") + " it could not be set up with no way out, because the model did not answer through it (no answer in 660 seconds). researchzosho model install tries again.\n"), status);
        assertEquals("http://127.0.0.1:8211", Config.get("RESEARCHZOSHO_DRIVE"), "the install is finished all the same");
        // `model install` again tries again, and when the model answers this time the install changes over
        machine.modelShut = ""; machine.asked.clear();
        String again = install();
        assertTrue(again.contains("Setting the model server of this machine up again with no way out to the network.") && again.contains("has no way out to the network"), again);
        assertEquals("shut", plan().get("network"));
        assertTrue(file("run.sh").contains("--network " + NET), file("run.sh"));
    }

    @Test
    void whenTheEmbeddingsServerAnswersNeitherWayTheCauseIsSaidToLieElsewhere() throws Exception {
        machine.embedShut = "!it returned HTTP 500: upstream command exited prematurely"; machine.embedOpen = "!it returned HTTP 500: upstream command exited prematurely";
        String out = install();
        assertEarlierWay("/data/" + Embed.FOLDER);
        assertTrue(out.contains("because the embeddings server did not answer through it (it returned HTTP 500: upstream command exited prematurely)"), out);
        assertTrue(out.contains("Set up the earlier way, the embeddings server did not answer either (it returned HTTP 500: upstream command exited prematurely), so the cause lies elsewhere. Its log: journalctl --user -u researchzosho-model"), out);
        assertTrue(out.contains("Set up the earlier way, the model answered."), out);
        assertEquals(List.of("rest", "embed shut", "embed open", "rest", "complete open", "rest"), machine.asked);
        // and when it answers the earlier way, the arrangement was the cause, and that is what is said
        ModelServer.health = base -> false; ModelServer.uninstall(true); machine.install(); machine.started = false; machine.asked.clear();
        machine.embedOpen = "";
        out = install();
        assertTrue(out.contains("Set up the earlier way, the embeddings server answered.") && !out.contains("lies elsewhere"), out);
    }

    @Test
    void whenAnOutsideAddressAnswersFromInsideTheEarlierWayIsSetUpAndSaid() throws Exception {
        machine.inside = 0;
        String out = install();
        assertEarlierWay("/data/" + Embed.FOLDER);
        assertTrue(out.contains("because an outside address answered from inside the container researchzosho-model. It is set up the way earlier versions did"), out);
        assertTrue(ModelServer.status().contains("The model server can reach the network: on "), ModelServer.status());
        // any answer of the outside counts, a refused handshake too (curl's 35): only "could not connect" and "no answer in time" are a wall
        ModelServer.health = base -> false; ModelServer.uninstall(true); machine.install(); machine.started = false;
        machine.inside = 7; machine.insideEmbed = 35;
        out = install();
        assertTrue(out.contains("because an outside address answered from inside the container researchzosho-model-embed."), out);
        assertEarlierWay("/data/" + Embed.FOLDER);
        ModelServer.health = base -> false; ModelServer.uninstall(true); machine.install(); machine.started = false;
        machine.insideEmbed = 28;
        assertTrue(install().contains("has no way out to the network"), "no answer in time is a wall");
    }

    @Test
    void aProofThatCannotBeRunIsNoProofAndTheEarlierWayIsSetUp() throws Exception {
        // no container on the machine reaches the outside address: a silence from inside would mean nothing
        machine.control = 6;
        String out = install();
        assertEarlierWay("/data/" + Embed.FOLDER);
        assertTrue(out.contains("because no container on this machine reached an outside address just now, so it could not be tried (curl: (6) Could not resolve host: huggingface.co)"), out);
        assertEquals(List.of("complete open", "rest"), machine.asked, "neither server was loaded for a proof that could not be made; the model is asked the earlier way");
        // the control answered, and said no number
        ModelServer.health = base -> false; ModelServer.uninstall(true); machine.install(); machine.started = false;
        machine.control = 0; machine.controlSaid = "";
        assertTrue(install().contains("so it could not be tried"));
        // the check has no curl to run inside the container: not "could not connect"
        ModelServer.health = base -> false; ModelServer.uninstall(true); machine.install(); machine.started = false;
        machine.controlSaid = "13.33.109.46"; machine.inside = 127;
        out = install();
        assertTrue(out.contains("because the check could not be run inside the container researchzosho-model (OCI runtime exec failed"), out);
        assertEarlierWay("/data/" + Embed.FOLDER);
    }

    @Test
    void whenNoNetworkCanBeMadeOrAnImageCannotBeFetchedTheInstallIsTheEarlierWayFromTheStart() throws Exception {
        machine.refused = ModelServer.RANGES;
        String out = install();
        assertEarlierWay("/data/" + Embed.FOLDER);
        assertTrue(out.contains("The model server could not be set up with no way out to the network on this machine, because Docker did not make its network (Error response from daemon: invalid pool request"), out);
        assertTrue(machine.asked.isEmpty(), "nothing to prove: the model is not loaded by the install, as in every release before: " + machine.asked);
        assertEquals(0, machine.count("systemctl", "--user", "restart"));
        assertTrue(ModelServer.status().contains("because Docker did not make its network"), ModelServer.status());
        ModelServer.health = base -> false; ModelServer.uninstall(true); machine.install(); machine.started = false;
        machine.refused = List.of(); machine.images = false; machine.pull = 1;
        out = install();
        assertTrue(out.contains("Fetching ghcr.io/ggml-org/llama.cpp:server-cuda") && out.contains("because a program it runs could not be fetched (ghcr.io/ggml-org/llama.cpp:server-cuda: Error response from daemon: Get"), out);
        assertEarlierWay("/data/" + Embed.FOLDER);
        // and fetched, both images are there before the proof asks anything
        ModelServer.health = base -> false; ModelServer.uninstall(true); machine.install(); machine.started = false;
        machine.pull = 0;
        out = install();
        assertTrue(machine.ranLine("docker", "pull", "ghcr.io/ggml-org/llama.cpp:server-cuda") && machine.ranLine("docker", "pull", Embed.IMAGE + ":89-1.8") && out.contains("has no way out to the network"), out);
    }

    @Test
    void whenTheProxyDoesNotComeUpWithTheNewFilesTheEarlierOnesArePutBack() throws Exception {
        machine.proxyUpWhenShut = false;
        String out = install();
        assertEarlierWay("/data/" + Embed.FOLDER);
        assertTrue(out.contains("because the proxy did not answer at http://127.0.0.1:8211. It is set up the way earlier versions did"), out);
        assertEquals("open", plan().get("network"));
        assertEquals("http://127.0.0.1:8211", Config.get("RESEARCHZOSHO_DRIVE"));
    }

    @Test
    void aTestThatStandsInForTheMachineHasToStandInForTheModelToo() {
        ModelServer.asker = realAsker;   // the real one would ask whatever answers on this machine's port
        String r = ModelServer.install(null, "all", 10, false, new PrintStream(new ByteArrayOutputStream()));
        assertTrue(r.startsWith("!a test that stands in for the machine has to stand in for the model's answers too"), r);
    }

    // ---- the setting ----

    @Test
    void theSettingChoosesTheEarlierWayOnPurposeAndInstallGivenAgainAppliesAChange() throws Exception {
        Config.set(ModelServer.MODEL_NETWORK, "open");
        String out = install();
        assertEarlierWay("/data/" + Embed.FOLDER);
        assertTrue(machine.networks.isEmpty() && machine.asked.isEmpty() && machine.count("docker", "network", "create") == 0, "no network, no proof: " + machine.ran);
        assertTrue(out.contains("The setting model_network is open, so the model server is set up able to reach the network, as earlier versions did."), out);
        assertEquals("asked", plan().get("network_why"));
        assertTrue(ModelServer.status().contains("  network: The model server can reach the network, because the setting model_network is open. With it closed or unset, researchzosho model install sets it up with no way out.\n"), ModelServer.status());
        install();
        assertTrue(machine.asked.isEmpty(), "given again with the setting open, it is as wanted");
        // closed: the status says how to get it, and `model install` changes it over
        Config.set(ModelServer.MODEL_NETWORK, "closed");
        assertTrue(ModelServer.status().contains("The model server can reach the network: the setting model_network was open when it was set up. researchzosho model install sets it up with no way out."), ModelServer.status());
        out = install();
        assertTrue(out.contains("has no way out to the network") && file("run.sh").contains("--network " + NET) && "shut".equals(plan().get("network")), out);
        assertFalse(plan().containsKey("network_why"), "the plan says one thing: " + plan());
        // open again: the status says the setting waits for `model install`, which turns the files back and takes the network away
        Config.set(ModelServer.MODEL_NETWORK, "open");
        assertTrue(ModelServer.status().contains("has no way out to the network") && ModelServer.status().contains("The setting model_network is open now; researchzosho model install applies it."), ModelServer.status());
        out = install();
        assertTrue(out.contains("The setting model_network is open, so the model server is set up able to reach the network"), out);
        assertEarlierWay("/data/" + Embed.FOLDER);
        assertEquals("asked", plan().get("network_why"));
        assertFalse(plan().containsKey("subnet"));
    }

    // ---- an earlier version's install ----

    /** The install as released 0.8.0 left it: its three files, its unit, its plan without a word about the network, the model's files its embeddings server fetched for itself. */
    Map<String, String> installOf080() throws Exception {
        Path dir = ModelServer.dir(); Files.createDirectories(dir);
        Path model = ModelServer.modelsDir().resolve("gpt-oss-20b-F16.gguf");
        var old = ModelServer.plan(ModelServer.Os.linux, ModelServer.rowFor(16, 16), model, dir, ModelServer.unitPath(ModelServer.Os.linux), "all", 10, false, "89-1.8");
        Map<String, String> files = new LinkedHashMap<>(ModelServer.filesOf(old));
        files.put("run.sh", files.get("run.sh").replace(" -ngl 99 ", " -ngl 99 --no-webui "));   // a flag the person added by hand
        ModelServer.writeFiles(dir, files);
        Files.createDirectories(old.unit().getParent()); Files.writeString(old.unit(), old.unitText());
        Files.writeString(dir.resolve("plan.properties"), ModelServer.planProperties(ModelServer.rowFor(16, 16), model, "all", 10, false) + "chosen=suggested\n");
        Files.writeString(dir.resolve("previous"), "\n\n\n");
        Path snapshot = Embed.dir().resolve("models--Qwen--Qwen3-Embedding-0.6B/snapshots/0123abc");
        Files.createDirectories(snapshot);
        for (String f : List.of("config.json", "tokenizer.json", "model.safetensors")) Files.writeString(snapshot.resolve(f), "x");
        Config.set("RESEARCHZOSHO_DRIVE", ModelServer.URL);
        machine.started = true;
        return files;
    }

    @Test
    void anInstallOfAnEarlierVersionIsLeftAsItIsAndTheStatusSaysHowToChangeIt() throws Exception {
        Map<String, String> before = installOf080();
        String planBefore = file("plan.properties");
        assertEquals("", ModelServer.upgrade(new PrintStream(new ByteArrayOutputStream())), "the update changes nothing of a running model server");
        assertEquals("", ModelServer.repairStop());
        String status = ModelServer.status();
        for (var f : before.entrySet()) assertEquals(f.getValue(), file(f.getKey()), f.getKey() + " untouched");
        assertEquals(planBefore, file("plan.properties"));
        assertTrue(machine.networks.isEmpty() && machine.asked.isEmpty() && machine.count("systemctl", "--user", "restart") == 0, machine.ran.toString());
        assertTrue(status.contains("  network: The model server can reach the network: a version before 0.8.1 set it up. researchzosho model install sets it up with no way out.\n"), status);
    }

    @Test
    void modelInstallGivenAgainChangesAnEarlierInstallOverAndKeepsWhatThePersonWrote() throws Exception {
        Map<String, String> before = installOf080();
        String out = install();
        assertTrue(out.contains("Setting the model server of this machine up again with no way out to the network. A model that is loaded now is unloaded for it.") && out.contains("has no way out to the network"), out);
        assertFalse(out.contains("already answers"), out);
        String run = file("run.sh"), embed = file("run-embed.sh");
        assertTrue(run.contains(" -ngl 99 --no-webui ") && run.contains("--network " + NET + " --ip 172.16.211.2 --cap-drop NET_RAW") && !run.contains(" -p "), "only the way out changed; the person's flag stays: " + run);
        assertTrue(embed.contains("--model-id /data/models--Qwen--Qwen3-Embedding-0.6B/snapshots/0123abc ") && embed.contains(":/data:ro ") && embed.contains("--ip 172.16.211.3"), "the embeddings server reads the copy it fetched under the earlier version, so its answers stay the ones the index was built with: " + embed);
        assertTrue(machine.fetched.isEmpty(), "nothing is downloaded for it: " + machine.fetched);
        assertTrue(machine.ranLine("systemctl", "--user", "restart", "researchzosho-model"));
        assertEquals("shut", plan().get("network")); assertEquals("suggested", plan().get("chosen")); assertEquals("gpt-oss-20b", plan().get("name"));
        assertEquals(before, ModelServer.toOpen(ModelServer.dir(), Map.of("config.yaml", file("config.yaml"), "run.sh", run, "run-embed.sh", embed.replace("--model-id /data/models--Qwen--Qwen3-Embedding-0.6B/snapshots/0123abc ", "--model-id Qwen/Qwen3-Embedding-0.6B ").replace(":/data:ro ", ":/data ").replaceFirst("(?m)^# It reads its model from the folder.*\\n", "# The model downloads into " + Embed.dir() + " on the first start.\n"))),
                "turned back and by its name again, the files are the ones 0.8.0 wrote");
    }

    @Test
    void whenTheChangeOverDoesNotHoldTheInstallGetsBackExactlyTheFilesItHad() throws Exception {
        Map<String, String> before = installOf080();
        machine.modelShut = "!it returned HTTP 502: upstream command exited prematurely";
        String out = install();
        for (var f : before.entrySet()) assertEquals(f.getValue(), file(f.getKey()), f.getKey() + " is what it was before, to the letter");
        assertNull(machine.networks.get(NET));
        assertTrue(out.contains("because the model did not answer through it (it returned HTTP 502: upstream command exited prematurely). It is set up the way earlier versions did") && out.contains("Set up the earlier way, the model answered."), out);
        assertEquals(2, machine.count("systemctl", "--user", "restart"), "once to read the new files, once to read the old ones again");
        assertEquals("unproven", plan().get("network_why")); assertEquals("suggested", plan().get("chosen"));
        // no free range: nothing of the install is touched, and the proxy is not restarted
        machine.ran.clear(); machine.refused = ModelServer.RANGES;
        out = install();
        assertTrue(out.contains("It stays as it was, able to reach the network. To try again: researchzosho model install"), out);
        assertEquals(0, machine.count("systemctl", "--user", "restart"));
        for (var f : before.entrySet()) assertEquals(f.getValue(), file(f.getKey()));
    }

    @Test
    void anotherProgramsInstallInTheSameFolderIsLeftAsItIs() throws Exception {
        installOf080();
        Files.writeString(ModelServer.dir().resolve("run.sh"), file("run.sh").replace("--name " + ModelServer.CONTAINER, "--name other-model"));
        String run = file("run.sh"), cfg = file("config.yaml");
        String out = install();
        assertTrue(out.contains("A model server already answers at http://127.0.0.1:8211 on this machine, so the library uses that one and does not install another."), out);
        assertEquals(run, file("run.sh")); assertEquals(cfg.replace("docker stop " + ModelServer.CONTAINER + "\n    proxy", "docker stop other-model\n    proxy"), file("config.yaml").replace("docker stop " + ModelServer.CONTAINER + "\n    proxy", "docker stop other-model\n    proxy"));
        assertTrue(machine.networks.isEmpty() && machine.asked.isEmpty());
        assertTrue(ModelServer.status().contains("  network: The model server can reach the network: another program set it up, and this one leaves it as it is.\n"), ModelServer.status());
    }

    /**
     * The folder as it is found where CodeZaiku and this program share a home and a model server: CodeZaiku's own installer wrote the
     * start scripts and the service under its names (codezaiku-model), and this program's update added its embeddings server and wrote
     * its plan file beside them, which begins as this program's plan does. This program has named its container and its service
     * researchzosho-model since its first model server, so the server is another program's, and the plan file does not make it this
     * one's. While it answers, `model install` changes nothing, runs nothing and says the library uses it; the status says another
     * program set it up and that this one leaves it as it is. The control: the plan file is this program's, word for word.
     */
    @Test
    void codeZaikusModelServerInTheSharedFolderIsAnotherProgramsEvenWithThisProgramsPlanFileBesideIt() throws Exception {
        Path dir = ModelServer.dir(); Files.createDirectories(dir);
        Path model = ModelServer.modelsDir().resolve("gpt-oss-20b-F16.gguf");
        var plan = ModelServer.plan(ModelServer.Os.linux, ModelServer.rowFor(16, 16), model, dir, ModelServer.unitPath(ModelServer.Os.linux), "all", 10, false, "89-1.8");
        Map<String, String> files = new LinkedHashMap<>();
        for (var f : ModelServer.filesOf(plan).entrySet()) files.put(f.getKey(), f.getValue().replace(ModelServer.CONTAINER, "codezaiku-model"));
        ModelServer.writeFiles(dir, files);
        Path unit = ModelServer.unitPath(ModelServer.Os.linux).resolveSibling("codezaiku-model.service");
        Files.createDirectories(unit.getParent()); Files.writeString(unit, plan.unitText());
        Files.writeString(dir.resolve("plan.properties"), ModelServer.planProperties(ModelServer.rowFor(16, 16), model, "all", 10, false) + "chosen=suggested\n");
        Config.set("RESEARCHZOSHO_DRIVE", ModelServer.URL);
        machine.started = true;
        assertTrue(file("plan.properties").startsWith("# ResearchZosho: what `model install` was told") && file("run.sh").contains("--name codezaiku-model ") && !Files.exists(ModelServer.unitPath(ModelServer.Os.linux)), "the control: the folder is of that form");
        String out = install();
        assertTrue(out.contains("A model server already answers at http://127.0.0.1:8211 on this machine, so the library uses that one and does not install another."), out);
        for (var f : files.entrySet()) assertEquals(f.getValue(), file(f.getKey()), f.getKey() + " untouched");
        assertTrue(machine.ran.isEmpty() && machine.networks.isEmpty() && !Files.exists(ModelServer.unitPath(ModelServer.Os.linux)), "nothing run, no network, no service of this program's: " + machine.ran);
        assertTrue(ModelServer.status().contains("  network: The model server can reach the network: another program set it up, and this one leaves it as it is.\n"), ModelServer.status());
    }

    @Test
    void anInstallCutShortBeforeItsProofClaimsNothingAndIsProvenWhenGivenAgain() throws Exception {
        install();
        ModelServer.rewriteNet(ModelServer.dir(), ModelServer.shutProperties(ModelServer.Shut.of(RANGE), false, false));   // as the install leaves the plan before the proof
        assertFalse(plan().containsKey("network_tried"));
        assertTrue(ModelServer.status().contains("  network: The model server is set up with no way out to the network, but setting it up stopped before that was tried. researchzosho model install tries it.\n"), ModelServer.status());
        machine.asked.clear();
        String out = install();
        assertTrue(out.contains("has no way out to the network") && plan().containsKey("network_tried"), out);
        assertEquals(List.of("rest", "embed shut", "rest", "complete shut", "rest"), machine.asked);
    }

    @Test
    void theStatusDoesNotClaimWhatTheFilesOrTheNetworkNoLongerAre() throws Exception {
        install();
        machine.networks.put(NET, "false " + RANGE);   // made again by hand as an ordinary network
        assertTrue(ModelServer.status().contains("  network: The model server can reach the network: it was set up with no way out, and its start script or its Docker network (" + NET + ") is no longer as this program made it. researchzosho model install sets it up with no way out.\n"), ModelServer.status());
        machine.networks.put(NET, "true " + RANGE);
        assertTrue(ModelServer.status().contains("has no way out to the network"));
        machine.networks.remove(NET);   // removed (a prune): the start script makes it again, and the claim is of how the model starts
        assertTrue(ModelServer.status().contains("has no way out to the network"));
        Files.writeString(ModelServer.dir().resolve("run.sh"), file("run.sh").replace(" --network " + NET + " --ip 172.16.211.2 --cap-drop NET_RAW", " -p 127.0.0.1:8080:8080"));
        assertTrue(ModelServer.status().contains("is no longer as this program made it"), ModelServer.status());
        // where the server is a plain program, it says that
        ModelServer.os = ModelServer.Os.macos;
        assertTrue(ModelServer.status().contains("  network: On this system the model server is an ordinary program, not in a container, so nothing keeps it from the network.\n"), ModelServer.status());
        ModelServer.os = ModelServer.Os.windows;
        assertTrue(ModelServer.status().contains("an ordinary program, not in a container"), ModelServer.status());
    }

    // ---- the embeddings model's folder ----

    @Test
    void theEmbeddingsServerIsGivenItsFolderAndWhenTheFilesCannotBeFetchedItIsLeftOutUntilTheyAre() throws Exception {
        assertNull(Embed.modelFolder(), "nothing on the disk yet");
        for (var f : Embed.FILES.entrySet()) assertEquals(f.getValue(), ModelServer.sums.get(Embed.FOLDER + "/" + f.getKey()), "each file is checked against its recorded sha256");
        assertEquals("Qwen3-Embedding-0.6B-97b0c61", Embed.FOLDER);
        // the download fails: with no way out the server could not fetch for itself, so it is left out and the person is told
        ModelServer.Downloader works = ModelServer.downloader;
        ModelServer.downloader = (url, dest) -> { if (url.contains("Qwen3-Embedding")) throw new IOException("download failed: " + url); works.fetch(url, dest); };
        String out = install();
        assertTrue(out.contains("That download failed (download failed: https://huggingface.co/Qwen/Qwen3-Embedding-0.6B/resolve/" + Embed.REVISION + "/config.json). The model is installed and answers; search by meaning is off until the embeddings model is fetched, which the library tries again each time its service starts."), out);
        assertTrue(out.contains("has no way out to the network") && !out.contains("no graphics card"), out);
        assertFalse(file("config.yaml").contains("\"embed\":") || Files.exists(ModelServer.dir().resolve("run-embed.sh")), file("config.yaml"));
        assertEquals(List.of("rest", "complete shut", "rest"), machine.asked);
        // the next start of the service fetches it and adds the server, with no way out like the model
        ModelServer.downloader = works;
        assertEquals("gpt-oss-20b", ModelServer.upgrade(new PrintStream(new ByteArrayOutputStream())));
        assertTrue(file("config.yaml").contains("\"embed\":") && file("config.yaml").contains("proxy: http://172.16.211.3:80\n") && file("config.yaml").contains("proxy: http://172.16.211.2:8080\n"), file("config.yaml"));
        assertTrue(file("run-embed.sh").contains("--network " + NET + " --ip 172.16.211.3") && file("run-embed.sh").contains("--model-id /data/" + Embed.FOLDER + " "), file("run-embed.sh"));
        assertEquals("shut", plan().get("network")); assertEquals(RANGE, plan().get("subnet"));
        assertEquals("/data/" + Embed.FOLDER, Embed.modelFolder());
        // a copy the server fetched for itself is found when the installer's own is not there: the newest whole one
        Path cache = Embed.dir().resolve("models--Qwen--Qwen3-Embedding-0.6B/snapshots");
        for (String rev : List.of("aaa", "bbb")) { Files.createDirectories(cache.resolve(rev)); Files.writeString(cache.resolve(rev).resolve("config.json"), "x"); Files.writeString(cache.resolve(rev).resolve("tokenizer.json"), "x"); }
        Files.writeString(cache.resolve("aaa").resolve("model.safetensors"), "x");
        Files.delete(Embed.dir().resolve(Embed.FOLDER).resolve("model.safetensors"));
        assertEquals("/data/models--Qwen--Qwen3-Embedding-0.6B/snapshots/aaa", Embed.modelFolder(), "bbb lacks its weights");
    }

    @Test
    void theEarlierWayWithAFailedDownloadStartsTheEmbeddingsServerByItsNameAsBefore() throws Exception {
        Config.set(ModelServer.MODEL_NETWORK, "open");
        ModelServer.Downloader works = ModelServer.downloader;
        ModelServer.downloader = (url, dest) -> { if (url.contains("Qwen3-Embedding")) throw new IOException("download failed: " + url); works.fetch(url, dest); };
        String out = install();
        assertTrue(out.contains("The embeddings server fetches its model itself the first time it starts."), out);
        assertEarlierWay(null);
        assertTrue(file("run-embed.sh").contains("--model-id Qwen/Qwen3-Embedding-0.6B ") && out.contains("the model downloads once, about 1.2 GB, on the first search"), file("run-embed.sh") + out);
    }
}
