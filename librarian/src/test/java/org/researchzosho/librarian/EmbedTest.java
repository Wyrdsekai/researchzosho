package org.researchzosho.librarian;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The embeddings server through Docker: the image for the card, the command lines, and the states — without docker. */
class EmbedTest {
    final List<List<String>> ran = new ArrayList<>();
    final Searx.Runner real = Searx.runner;
    @AfterEach void restore() { Searx.runner = real; }

    @Test
    void theImageFollowsTheCard() {
        assertEquals("89-" + Embed.VERSION, Embed.tagFor("8.9"), "Ada");
        assertEquals("86-" + Embed.VERSION, Embed.tagFor("8.6"));
        assertEquals(Embed.VERSION, Embed.tagFor("8.0"), "Ampere data-centre: the plain tag");
        assertEquals("turing-" + Embed.VERSION, Embed.tagFor("7.5"));
        assertEquals("hopper-" + Embed.VERSION, Embed.tagFor("9.0"));
        assertEquals("cpu-" + Embed.VERSION, Embed.tagFor("12.0"), "an unknown card gets the CPU image, never a guess");
        // no nvidia runtime in the docker daemon: cpu, and nvidia-smi is never asked
        Searx.runner = cmd -> { ran.add(cmd); return new Searx.Result(0, cmd.contains("info") ? "map[runc:{...}]" : "8.9"); };
        assertEquals("cpu-" + Embed.VERSION, Embed.tag());
        assertEquals(1, ran.size());
        Searx.runner = cmd -> new Searx.Result(0, cmd.contains("info") ? "map[nvidia:{...} runc:{...}]" : "8.9\n");
        assertEquals("89-" + Embed.VERSION, Embed.tag());
    }

    @Test
    void theRunCommandBindsLoopbackRestartsWithTheMachineAndUsesTheGpuOnlyWithAGpuImage() {
        List<String> gpu = Embed.plan(8215, "89-" + Embed.VERSION).get(0);
        assertEquals("docker", gpu.get(0));
        assertTrue(gpu.contains("--restart") && gpu.contains("unless-stopped"), gpu.toString());
        assertTrue(gpu.contains("127.0.0.1:8215:80"), "loopback only: " + gpu);
        assertTrue(gpu.contains("--gpus"), gpu.toString());
        assertTrue(gpu.contains(Embed.IMAGE + ":89-" + Embed.VERSION) && gpu.contains(Embed.MODEL) && gpu.contains("last-token"), gpu.toString());
        List<String> cpu = Embed.plan(8215, "cpu-" + Embed.VERSION).get(0);
        assertFalse(cpu.contains("--gpus"), "the CPU image asks for no GPU: " + cpu);
        assertTrue(cpu.contains("2048"), "a small warm-up batch on the CPU: " + cpu);
        assertTrue(cpu.contains("--auto-truncate") && gpu.contains("--auto-truncate"), "without it the server refuses a batch limit below the model's 32k input limit");
    }

    @Test
    void withoutDockerStartSaysSoAndStateReadsInspect() {
        Searx.runner = cmd -> { ran.add(cmd); throw new java.io.IOException("docker: not found"); };
        String r = Embed.start(8215, false);
        assertTrue(r.startsWith("!docker is not on this machine"), r);
        assertEquals("absent", Embed.state());
        Searx.runner = cmd -> new Searx.Result(0, cmd.contains("inspect") ? "true\n" : "");
        assertEquals("running", Embed.state());
        Searx.runner = cmd -> new Searx.Result(0, cmd.contains("inspect") ? "false\n" : "");
        assertEquals("stopped", Embed.state());
    }
}
