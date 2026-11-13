package org.researchzosho.librarian;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The card the heavy helpers go on (0.7.4), through made-up readings of ss, nvidia-smi and /proc: the card of the model server beside
 * them by each of the three ways alone, two ways that disagree, nothing found; the card this program's model server was installed with;
 * the only card; the question on a machine with several, and the stop with --yes; the line that says which rule chose. And what the
 * choice does: the environment of a started Python helper, the docker arguments of LaBSE's container, the unit's line.
 */
class HelperCardsTest {
    String home;
    HelperCards.Probe probe;
    HelperServer.Starter start;
    final ByteArrayOutputStream said = new ByteArrayOutputStream();

    static final String FOUR = "0, GPU-aaaa0000-0000-0000-0000-000000000000, NVIDIA RTX A6000, 49140, 48300\n"
            + "1, GPU-bbbb1111-1111-1111-1111-111111111111, NVIDIA RTX A6000, 49140, 2100\n"
            + "2, GPU-cccc2222-2222-2222-2222-222222222222, NVIDIA RTX A6000, 49140, 47900\n"
            + "3, GPU-dddd3333-3333-3333-3333-333333333333, NVIDIA RTX 6000 Ada Generation, 49140, 45000\n";

    /** A machine: nvidia-smi's cards, ss's listeners, the compute apps, each process's children, environment and command line. */
    static final class Fake implements HelperCards.Probe {
        String cards = FOUR, ss = "", apps = "";
        final Map<Long, List<Long>> children = new HashMap<>();
        final Map<Long, String> env = new HashMap<>();
        final Map<Long, List<String>> cmd = new HashMap<>();
        @Override public String run(List<String> c) {
            if (c.get(0).equals("nvidia-smi") && c.get(1).startsWith("--query-gpu")) return cards;
            if (c.get(0).equals("nvidia-smi") && c.get(1).startsWith("--query-compute-apps")) return apps;
            if (c.get(0).equals("ss")) return ss;
            return null;
        }
        @Override public List<Long> descendants(long pid) { return children.getOrDefault(pid, List.of()); }
        @Override public String environ(long pid) { return env.getOrDefault(pid, ""); }
        @Override public List<String> cmdline(long pid) { return cmd.getOrDefault(pid, List.of()); }
    }

    Fake fake;

    @BeforeEach void isolate(@TempDir Path tmp) {
        home = System.getProperty("user.home"); System.setProperty("user.home", tmp.resolve("home").toString()); Config.invalidate();
        probe = HelperCards.PROBE; start = HelperServer.START;
        fake = new Fake(); HelperCards.PROBE = fake;
        fake.ss = "LISTEN 0      4096         0.0.0.0:8212       0.0.0.0:*    users:((\"llama-swap\",pid=4100,fd=7))\n"
                + "LISTEN 0      4096       127.0.0.1:4649       0.0.0.0:*    users:((\"java\",pid=900,fd=12))\n";
        fake.children.put(4100L, List.of(4200L, 4300L));
    }
    @AfterEach void restore() { HelperCards.PROBE = probe; HelperServer.START = start; System.setProperty("user.home", home); Config.invalidate(); }

    HelperCards.Choice choose(int beside, String typed, boolean ask) throws IOException {
        return HelperCards.choose(null, false, beside, ask, new BufferedReader(new StringReader(typed)), new PrintStream(said, true, StandardCharsets.UTF_8));
    }

    @Test void theModelServersCardByMemoryHeld() throws IOException {
        fake.apps = "4300, GPU-cccc2222-2222-2222-2222-222222222222\n9999, GPU-aaaa0000-0000-0000-0000-000000000000\n";
        HelperCards.Choice c = choose(8212, "", false);
        assertEquals("2", c.card().index());
        assertEquals("The helpers go on card 2 (NVIDIA RTX A6000, 46.8 GB free), the card of the model server at port 8212.", c.said());
    }

    @Test void theModelServersCardByItsEnvironment() throws IOException {
        fake.env.put(4200L, "HOME=/home/x\0CUDA_VISIBLE_DEVICES=3\0PATH=/usr/bin");
        assertEquals("3", choose(8212, "", false).card().index());
        fake.env.put(4200L, "CUDA_VISIBLE_DEVICES=0,1");   // two cards named: says nothing of one
        assertTrue(choose(8212, "", false).stop());
    }

    @Test void theModelServersCardByTheDockerRunOfItsChild() throws IOException {
        // the real case: a llama-swap proxy, its model unloaded, its child `docker run … --gpus "device=GPU-…"`
        for (String gpus : List.of("\"device=GPU-dddd3333-3333-3333-3333-333333333333\"", "device=GPU-dddd3333-3333-3333-3333-333333333333", "'\"device=3\"'", "device=3")) {
            fake.cmd.put(4300L, List.of("docker", "run", "--rm", "--name", "model", "--gpus", gpus, "ghcr.io/ggml-org/llama.cpp:server-cuda", "-m", "/m/x.gguf"));
            HelperCards.Choice c = choose(8212, "", false);
            assertEquals("3", c.card().index(), gpus);
        }
        fake.cmd.put(4300L, List.of("docker", "run", "--gpus=device=1", "image"));
        assertEquals("1", choose(8212, "", false).card().index());
    }

    @Test void twoWaysThatDisagreeOrNothingFoundLeaveItToTheNextRule() throws IOException {
        fake.apps = "4300, GPU-cccc2222-2222-2222-2222-222222222222\n";
        fake.env.put(4200L, "CUDA_VISIBLE_DEVICES=3");
        HelperCards.Choice c = choose(8212, "", false);
        assertTrue(c.stop(), "card 2 by memory and card 3 by environment: the rule does not decide, and with --yes nothing else does");
        assertEquals("The model server at port 8212 is on more than one card (2, 3): name the one for the helpers with --gpu <index>.", c.said(), "what is the case, said");
        assertEquals("This machine has 4 cards (0 NVIDIA RTX A6000, 1 NVIDIA RTX A6000, 2 NVIDIA RTX A6000, 3 NVIDIA RTX 6000 Ada Generation) and nothing says which the helpers go on: name one with --gpu <index>, or --cpu.", choose(0, "", false).said(), "no model server asked: the general line");
        fake.apps = ""; fake.env.clear();
        assertTrue(choose(8212, "", false).stop(), "nothing found");
        assertTrue(choose(9999, "", false).stop(), "nothing listens on that port");
    }

    @Test void theCardThisProgramsModelServerWasInstalledWith() throws IOException {
        Files.createDirectories(ModelServer.dir());
        Files.writeString(ModelServer.dir().resolve("plan.properties"), "name=x\ngpus=1\nidle_minutes=10\nshare=false\n", StandardCharsets.UTF_8);
        HelperCards.Choice c = choose(0, "", false);
        assertEquals("The helpers go on card 1 (NVIDIA RTX A6000, 2.1 GB free), the card this program's model server was installed with.", c.said());
        Files.writeString(ModelServer.dir().resolve("plan.properties"), "name=x\ngpus=all\n", StandardCharsets.UTF_8);
        assertTrue(choose(0, "", false).stop(), "all cards names none");
    }

    @Test void theOnlyCardTheQuestionAndTheStop() throws IOException {
        fake.cards = "0, GPU-aaaa0000-0000-0000-0000-000000000000, NVIDIA GeForce RTX 4060 Ti, 16380, 9100\n";
        assertEquals("The helpers go on card 0 (NVIDIA GeForce RTX 4060 Ti, 8.9 GB free), the only card on this machine.", choose(0, "", false).said());
        fake.cards = FOUR;
        HelperCards.Choice asked = choose(0, "2\n", true);
        String q = said.toString(StandardCharsets.UTF_8);
        assertTrue(q.contains("This machine has 4 cards. Which one do the heavy helpers go on?\n  0    NVIDIA RTX A6000, 47.2 GB free of 48 GB\n  1    NVIDIA RTX A6000, 2.1 GB free of 48 GB"), q);
        assertTrue(q.contains("  cpu  the processor (no card)\nWhich? (an index, or cpu) "), q);
        assertEquals("The helpers go on card 2 (NVIDIA RTX A6000, 46.8 GB free), as you chose.", asked.said());
        assertTrue(choose(0, "cpu\n", true).cpu());
        assertTrue(choose(0, "\n", true).stop(), "no card chosen: nothing changed");
        // named, kept, and the kept one next time
        assertEquals("The helpers go on card 3 (NVIDIA RTX 6000 Ada Generation, 43.9 GB free) (--gpu 3).", HelperCards.choose("3", false, 0, false, null, new PrintStream(said)).said());
        assertTrue(HelperCards.choose("7", false, 0, false, null, new PrintStream(said)).stop(), "no card 7 here");
        Config.set(Helpers.GPU_NAME, "3");
        assertEquals("The helpers go on card 3 (NVIDIA RTX 6000 Ada Generation, 43.9 GB free), as chosen before (helpers_gpu 3).", choose(8212, "", false).said());
        Config.set(Helpers.GPU_NAME, "cpu");
        assertTrue(choose(8212, "", false).cpu());
        assertEquals("The helpers run on the processor (--cpu): none of them touches a card.", HelperCards.choose(null, true, 0, false, null, new PrintStream(said)).said());
        // helpers serve, with nothing to decide: as before, said
        Config.set(Helpers.GPU_NAME, "");
        HelperCards.Choice serve = HelperCards.choose(null, false, 0, HelperCards.Undecided.AS_BEFORE, null, new PrintStream(said));
        assertFalse(serve.stop()); assertNull(serve.card()); assertNull(serve.visible());
    }

    @Test void theChoiceReachesThePythonsEnvironmentTheContainersArgumentsAndTheUnit() throws Exception {
        // MetricX-24 as installed, so the front starts its Python; the start stood in, and the process it gives ends at once
        Path dir = TranslationScores.metricxDir();
        Files.createDirectories(dir.resolve("venv").resolve("bin"));
        Files.writeString(TranslationScores.metricxReady(), "x"); Files.writeString(TranslationScores.metricxScript(), "#"); Files.writeString(TranslationScores.metricxPython(), "#!/bin/sh\n");
        List<Map<String, String>> envs = new ArrayList<>();
        HelperServer.START = pb -> { envs.add(new HashMap<>(pb.environment())); return ended(); };
        HelperCards.Choice two = choose(0, "2\n", true), cpu = HelperCards.choose(null, true, 0, false, null, new PrintStream(said));
        for (HelperCards.Choice c : List.of(two, cpu)) HelperServer.startBackends(List.of(Helpers.Helper.METRICX), new ArrayList<>(), line -> { }, c);
        // nvidia-smi lists three A6000s and then a faster Ada; CUDA numbers the fastest first, so CUDA's 2 is not nvidia-smi's 2: the card goes by its UUID
        assertEquals("GPU-cccc2222-2222-2222-2222-222222222222", envs.get(0).get("CUDA_VISIBLE_DEVICES"), "its own cuda is card 2, named by its UUID");
        assertEquals("PCI_BUS_ID", envs.get(0).get("CUDA_DEVICE_ORDER"));
        assertEquals("", envs.get(1).get("CUDA_VISIBLE_DEVICES"), "the processor: no card visible");
        HelperServer.startBackends(List.of(Helpers.Helper.METRICX), new ArrayList<>(), line -> { }, HelperCards.Choice.AS_BEFORE);
        assertEquals(System.getenv("CUDA_VISIBLE_DEVICES"), envs.get(2).get("CUDA_VISIBLE_DEVICES"), "nothing chosen: the environment as it was");
        // LaBSE's container on the card, and one that exists already left as it was
        assertTrue(String.join(" ", TranslationScores.labseCommand(8216, "86-1.8", two.dockerGpus())).contains("--gpus device=GPU-cccc2222-2222-2222-2222-222222222222 ghcr.io"), String.join(" ", TranslationScores.labseCommand(8216, "86-1.8", two.dockerGpus())));
        assertTrue(String.join(" ", TranslationScores.labseCommand(8216, "86-1.8")).contains("--gpus all "));
        assertFalse(TranslationScores.labseCommand(8216, "cpu-1.8", cpu.dockerGpus()).contains("--gpus"));
        Searx.Runner was = Searx.runner; int waits = TranslationScores.LABSE_WAITS;
        List<String> docker = new ArrayList<>();
        try {
            TranslationScores.LABSE_WAITS = 0;
            Searx.runner = c -> { docker.add(String.join(" ", c)); return c.get(1).equals("info") ? new Searx.Result(0, "map[nvidia:{} runc:{}]") : c.get(0).equals("nvidia-smi") ? new Searx.Result(0, "8.9\n") : c.get(1).equals("inspect") ? new Searx.Result(1, "") : new Searx.Result(0, ""); };
            List<String> lines = new ArrayList<>();
            TranslationScores.startLabse(two.dockerGpus(), two.card().uuid(), lines::add);
            assertTrue(docker.contains("nvidia-smi -i GPU-cccc2222-2222-2222-2222-222222222222 --query-gpu=compute_cap --format=csv,noheader"), "the image for card 2's own kind: " + docker);
            assertTrue(docker.stream().anyMatch(d -> d.startsWith("docker run -d --name researchzosho-labse") && d.contains("--gpus device=GPU-cccc2222-2222-2222-2222-222222222222 ghcr.io/huggingface/text-embeddings-inference:89-")), docker.toString());
            docker.clear();
            Searx.runner = c -> { docker.add(String.join(" ", c)); return c.get(1).equals("inspect") ? new Searx.Result(0, "true") : c.get(1).equals("info") ? new Searx.Result(0, "nvidia") : new Searx.Result(0, "8.6"); };
            TranslationScores.startLabse(two.dockerGpus(), two.card().uuid(), lines::add);
            assertTrue(lines.get(0).startsWith("The LaBSE container (researchzosho-labse) exists already and keeps the cards it was created with; it is left as it is"), lines.toString());
            assertTrue(docker.stream().noneMatch(d -> d.startsWith("docker run") || d.startsWith("docker rm")), "never recreated silently: " + docker);
        } finally { Searx.runner = was; TranslationScores.LABSE_WAITS = waits; }
        // the unit carries it
        String unit = HelpersInstall.unitText("/opt/rz/bin/researchzosho", 8217, Path.of("/home/x/.researchzosho/helpers.secret"), two.flag(), List.of(Helpers.Helper.METRICX, Helpers.Helper.LABSE), "/opt/java");
        assertTrue(unit.contains("' /opt/rz/bin/researchzosho helpers serve --bind 0.0.0.0 --port 8217 --secret-file /home/x/.researchzosho/helpers.secret --gpu 2 --only metricx,labse\n"), unit);
        assertTrue(HelpersInstall.unitText("/opt/rz/bin/researchzosho", 8217, Path.of("/s"), cpu.flag(), null, "/opt/java").contains("--secret-file /s --cpu\n"));
        // the front's health names the card from what the process says it runs on, never from the choice
        List<HelperCards.Card> cards = HelperCards.cards();
        assertEquals("google/metricx-24-hybrid-large-v2p6 on cuda (card 2, NVIDIA RTX A6000)", HelperServer.healthLine("google/metricx-24-hybrid-large-v2p6", "cuda", "NVIDIA RTX A6000", "GPU-cccc2222-2222-2222-2222-222222222222", cards));
        assertEquals("google/metricx-24-hybrid-large-v2p6 on cpu", HelperServer.healthLine("google/metricx-24-hybrid-large-v2p6", "cpu", "", "", cards), "the card had no room, or torch cannot see one: it says the processor");
        assertEquals("google/metricx-24-hybrid-large-v2p6 on cuda (NVIDIA RTX A6000)", HelperServer.healthLine("google/metricx-24-hybrid-large-v2p6", "cuda", "NVIDIA RTX A6000", "", cards), "a torch that gives no UUID: the card by its name");
    }

    /** A process that has ended, for a start stood in. */
    static Process ended() {
        return new Process() {
            @Override public OutputStream getOutputStream() { return OutputStream.nullOutputStream(); }
            @Override public InputStream getInputStream() { return InputStream.nullInputStream(); }
            @Override public InputStream getErrorStream() { return InputStream.nullInputStream(); }
            @Override public int waitFor() { return 1; }
            @Override public int exitValue() { return 1; }
            @Override public boolean isAlive() { return false; }
            @Override public void destroy() { }
        };
    }
}
