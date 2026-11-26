package org.researchzosho.librarian;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;

import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * `embed start` with no way out (0.8.3): the container on this program's network with no way out, at a fixed address, no port of the
 * machine published, the right to write raw packets dropped, proven by the three steps `model install` uses; else the earlier way, said.
 * Docker and the routes are stand-ins that record what they were asked.
 */
class EmbedNoWayOutTest {
    @TempDir Path home;
    String realHome;
    Searx.Runner realSearx;
    ModelServer.Runner realModel;
    ModelServer.Os realOs;
    Predicate<String> realAnswers;
    final List<List<String>> docker = new ArrayList<>(), machine = new ArrayList<>();
    String state = "absent";
    boolean networkMade;
    int inside = 7, control = 0;

    @BeforeEach void own() throws Exception {
        realHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        Files.createDirectories(home.resolve(".researchzosho"));
        Config.invalidate(); Config.forgetHome();
        realSearx = Searx.runner; realModel = ModelServer.runner; realOs = ModelServer.os; realAnswers = Embed.ANSWERS;
        ModelServer.os = ModelServer.Os.linux;
        Path folder = Embed.dir().resolve(Embed.FOLDER);
        Files.createDirectories(folder);
        for (String f : List.of("config.json", "tokenizer.json", "model.safetensors")) Files.writeString(folder.resolve(f), "{}");
        Searx.runner = cmd -> {
            docker.add(cmd);
            String c = String.join(" ", cmd);
            if (c.startsWith("docker version")) return new Searx.Result(0, "27");
            if (c.startsWith("docker inspect -f {{.State.Running}}")) return state.equals("absent") ? new Searx.Result(1, "No such object") : new Searx.Result(0, String.valueOf(state.equals("running")));
            if (c.startsWith("docker run -d")) { state = "running"; return new Searx.Result(0, "id"); }
            if (c.startsWith("docker rm -f")) { state = "absent"; return new Searx.Result(0, ""); }
            if (c.startsWith("docker start")) { state = "running"; return new Searx.Result(0, ""); }
            if (c.startsWith("docker run --rm --entrypoint curl")) return new Searx.Result(control, control == 0 ? "13.33.109.43" : "curl: (6) Could not resolve host");
            if (c.startsWith("docker exec")) return new Searx.Result(inside, "");
            return new Searx.Result(0, "");
        };
        ModelServer.runner = cmd -> {
            machine.add(cmd);
            String c = String.join(" ", cmd);
            if (c.startsWith("docker network inspect")) return networkMade ? new ModelServer.Result(0, "true 172.16.211.0/28") : new ModelServer.Result(1, "not found");
            if (c.startsWith("ip -4 route")) return new ModelServer.Result(0, "default via 192.168.1.1 dev eth0\n" + (networkMade ? "172.16.211.0/28 dev br-1 proto kernel scope link\n" : ""));
            if (c.startsWith("docker network create")) { networkMade = true; return new ModelServer.Result(0, "id"); }
            return new ModelServer.Result(0, "");
        };
        Embed.ANSWERS = url -> true;
        ModelNetworks.forget();
    }

    @AfterEach void back() {
        Searx.runner = realSearx; ModelServer.runner = realModel; ModelServer.os = realOs; Embed.ANSWERS = realAnswers; Embed.note = "";
        System.setProperty("user.home", realHome);
        Config.invalidate(); Config.forgetHome();
        ModelNetworks.forget();
    }

    List<String> run() { return docker.stream().filter(c -> c.size() > 2 && c.get(1).equals("run") && c.get(2).equals("-d")).reduce((a, b) -> b).orElseThrow(); }

    @Test
    void itStartsOnTheNetworkWithNoWayOutAtAFixedAddressAndProvesIt() throws Exception {
        String url = Embed.start(Embed.DEFAULT_PORT, true);
        assertEquals("http://172.16.211.4:80", url);
        String c = String.join(" ", run());
        assertTrue(c.contains("--network researchzosho-model-shut --ip 172.16.211.4 --cap-drop NET_RAW"), c);
        assertFalse(run().contains("-p"), "no port of the machine published: " + c);
        assertTrue(c.contains("--model-id /data/" + Embed.FOLDER), "from its folder, asking nobody");
        assertTrue(docker.stream().anyMatch(x -> String.join(" ", x).startsWith("docker run --rm --entrypoint curl")), "the control");
        assertTrue(docker.stream().anyMatch(x -> String.join(" ", x).equals("docker exec researchzosho-embed curl -sS -o /dev/null -m 8 -k -A researchzosho.org https://13.33.109.43/")),
                "inside, the number the control reached");
        assertTrue(Embed.note.startsWith("The embeddings server has no way out to the network"), Embed.note);
        assertEquals(url, Embed.address());
        assertEquals("172.16.211.0/28", Embed.recordedSubnet());
        assertTrue(ModelNetworks.inside(InetAddress.getByName("172.16.211.4"), ModelNetworks.ranges()), "the library refuses what comes from its network");
        assertEquals(url, ModelsAwake.machine().get(Embed.CONTAINER), "the idle rule stops and starts it where it answers");
        // stopped by the idle rule and started again: the same container, the same address
        state = "stopped";
        assertEquals(url, Embed.start(Embed.DEFAULT_PORT, true));
        assertEquals(List.of("docker", "start", Embed.CONTAINER), docker.stream().filter(x -> x.size() > 1 && x.get(1).equals("start")).findFirst().orElseThrow());
        assertEquals("", Embed.note, "nothing to say: it was made with no way out");
    }

    @Test
    void theModelServersUninstallLeavesTheNetworkWhileTheEmbeddingsServerIsOnIt() {
        Embed.start(Embed.DEFAULT_PORT, true);
        state = "stopped";   // the idle rule stopped it
        ModelServer.removeNetwork();
        assertTrue(machine.stream().noneMatch(c -> c.size() > 2 && c.get(1).equals("network") && c.get(2).equals("rm")), "its next start needs the network: " + machine);
        assertEquals("http://172.16.211.4:80", Embed.start(Embed.DEFAULT_PORT, true), "and it starts there again");
    }

    @Test
    void anEarlierContainerOnAnotherPortIsAskedAtThatPort() {
        state = "running";
        assertEquals("http://127.0.0.1:8299", Embed.start(8299, true));
    }

    @Test
    void whenAnOutsideAddressAnswersFromInsideItIsMadeTheEarlierWayAndSaysSo() {
        inside = 0;   // curl inside connected: the network lets it out
        String url = Embed.start(Embed.DEFAULT_PORT, true);
        assertEquals("http://127.0.0.1:8215", url);
        assertTrue(docker.contains(List.of("docker", "rm", "-f", Embed.CONTAINER)), "removed by its exact name");
        assertTrue(run().contains("127.0.0.1:8215:80"), "made again with the port published");
        assertEquals("The embeddings server can reach the network: it could not be set up with no way out, because an outside address answered from inside its container. It is set up the way earlier versions did.", Embed.note);
        assertEquals(url, Embed.address());
        assertNull(Embed.recordedSubnet());
    }

    @Test
    void whenNoContainerReachesOutsideTheCheckCannotBeMadeAndItIsSaid() {
        control = 6;
        assertEquals("http://127.0.0.1:8215", Embed.start(Embed.DEFAULT_PORT, true));
        assertTrue(Embed.note.contains("because no container on this machine reached an outside address just now, so it could not be tried"), Embed.note);
    }

    @Test
    void theSettingModelNetworkOpenKeepsTheEarlierWay() throws Exception {
        Config.set(ModelServer.MODEL_NETWORK, "open");
        assertEquals("http://127.0.0.1:8215", Embed.start(Embed.DEFAULT_PORT, true));
        assertTrue(run().contains("127.0.0.1:8215:80"));
        assertFalse(networkMade, "no network made");
        assertEquals("The embeddings server can reach the network: it could not be set up with no way out, because the setting model_network is open.", Embed.note);
    }

    @Test
    void aContainerMadeByAnEarlierVersionStaysAsItIsAndIsSaidUntilMadeAgain() {
        state = "stopped";   // made before 0.8.3: a published port, and no record of where it answers
        assertEquals("http://127.0.0.1:8215", Embed.start(Embed.DEFAULT_PORT, true));
        assertTrue(docker.stream().noneMatch(c -> c.size() > 1 && c.get(1).equals("rm")), "left as it is");
        assertTrue(Embed.note.startsWith("The embeddings server publishes a port of this machine and can reach the network"), Embed.note);
        assertTrue(Embed.note.contains("researchzosho embed start --again"), Embed.note);
        assertEquals("http://172.16.211.4:80", Embed.again(Embed.DEFAULT_PORT, true), "--again: removed and made with no way out");
    }

    @Test
    void onAnotherSystemItPublishesThePortAsBefore() {
        ModelServer.os = ModelServer.Os.macos;
        assertEquals("http://127.0.0.1:8215", Embed.start(Embed.DEFAULT_PORT, true));
        assertEquals("", Embed.note, "nothing else is possible there; the docs say so");
    }
}
