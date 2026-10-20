package org.researchzosho.librarian;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
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
        Searx.runner = cmd -> { ran.add(cmd); throw new IOException("docker: not found"); };
        String r = Embed.start(8215, false);
        assertTrue(r.startsWith("!docker is not on this machine"), r);
        assertEquals("absent", Embed.state());
        Searx.runner = cmd -> new Searx.Result(0, cmd.contains("inspect") ? "true\n" : "");
        assertEquals("running", Embed.state());
        Searx.runner = cmd -> new Searx.Result(0, cmd.contains("inspect") ? "false\n" : "");
        assertEquals("stopped", Embed.state());
    }

    @Test
    void onAnAmdOrIntelChipTheEmbeddingsRunInLlamaCppsContainerOnTheChip() throws Exception {
        Path realDrm = Gpu.SYS_DRM, realKfd = Gpu.SYS_KFD, realDev = Gpu.DEV;
        Path sys = Files.createTempDirectory("rz-embed-sys");
        try {
            Path d = sys.resolve("drm").resolve("card0").resolve("device"); Files.createDirectories(d);
            Files.writeString(d.resolve("vendor"), "0x1002\n"); Files.writeString(d.resolve("device"), "0x74b5\n"); Files.writeString(d.resolve("mem_info_vram_total"), "205822885888\n");
            Files.createDirectories(sys.resolve("kfd").resolve("1")); Files.writeString(sys.resolve("kfd").resolve("1").resolve("properties"), "gfx_target_version 90402\n");
            Gpu.SYS_DRM = sys.resolve("drm"); Gpu.SYS_KFD = sys.resolve("kfd"); Gpu.DEV = sys.resolve("dev");
            Searx.runner = cmd -> new Searx.Result(0, cmd.get(0).equals("nvidia-smi") ? "nvidia-smi not found. This is AMD country.\n" : "map[runc:{...}]");
            assertEquals("llama-rocm", Embed.tag(), "no TEI image for an AMD card: llama.cpp's ROCm container");
            assertTrue(Embed.gpu());
            String s = String.join(" ", Embed.plan(8215, "llama-rocm").get(0));
            assertTrue(s.startsWith("docker run -d --name researchzosho-embed --restart unless-stopped --device /dev/kfd --device /dev/dri --group-add video -p 127.0.0.1:8215:8080 -v "), s);
            assertTrue(s.endsWith(":/m ghcr.io/ggml-org/llama.cpp:server-rocm -m /m/Qwen3-Embedding-0.6B-Q8_0.gguf --host 0.0.0.0 --port 8080 --embedding --pooling last -c 8192 -b 8192 -ub 8192 -ngl 99"), s);
            assertFalse(s.contains("--gpus") || s.contains("text-embeddings-inference") || s.contains("--model-id"), s);
            Files.writeString(d.resolve("vendor"), "0x8086\n"); Files.delete(d.resolve("mem_info_vram_total")); Files.walk(sys.resolve("kfd")).sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            assertEquals("llama-vulkan", Embed.tag(), "an Intel chip: the Vulkan container");
            String v = String.join(" ", Embed.plan(8215, "llama-vulkan").get(0));
            assertTrue(v.contains("--restart unless-stopped --device /dev/dri --group-add video -p") && !v.contains("/dev/kfd") && v.contains("llama.cpp:server-vulkan -m /m/"), v);
        } finally { Gpu.SYS_DRM = realDrm; Gpu.SYS_KFD = realKfd; Gpu.DEV = realDev; }
    }
}
