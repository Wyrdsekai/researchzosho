package org.researchzosho.librarian;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** The recommendation prints the serve command in the form the machine's GPU takes. */
class ModelsGpuTest {

    @TempDir Path sys;
    Path realDrm = Gpu.SYS_DRM, realKfd = Gpu.SYS_KFD, realDev = Gpu.DEV;
    Searx.Runner realRunner = Searx.runner;

    @BeforeEach void standIn() throws Exception {
        Files.createDirectories(sys.resolve("drm")); Files.createDirectories(sys.resolve("kfd")); Files.createDirectories(sys.resolve("dev"));
        Gpu.SYS_DRM = sys.resolve("drm"); Gpu.SYS_KFD = sys.resolve("kfd"); Gpu.DEV = sys.resolve("dev");
        Searx.runner = cmd -> new Searx.Result(0, cmd.get(0).equals("nvidia-smi") ? "nvidia-smi not found. This is AMD country.\n" : "");
    }
    @AfterEach void restore() { Gpu.SYS_DRM = realDrm; Gpu.SYS_KFD = realKfd; Gpu.DEV = realDev; Searx.runner = realRunner; }

    void amd(String pciId, long vramBytes, String kfdProps) throws Exception {
        Path d = sys.resolve("drm").resolve("card0").resolve("device");
        Files.createDirectories(d);
        Files.writeString(d.resolve("vendor"), "0x1002\n"); Files.writeString(d.resolve("device"), pciId + "\n"); Files.writeString(d.resolve("mem_info_vram_total"), vramBytes + "\n");
        if (kfdProps != null) { Files.createDirectories(sys.resolve("kfd").resolve("1")); Files.writeString(sys.resolve("kfd").resolve("1").resolve("properties"), kfdProps); }
    }

    @Test
    void anInstinctCardGetsTheRocmCommandAndARadeonTheVulkanOne() throws Exception {
        amd("0x74b5", 205822885888L, "gfx_target_version 90402\n");
        assertEquals(192, (int) Models.cardGb());
        String s = Models.describeThisMachine();
        assertTrue(s.startsWith("This machine's card has 192 GB."), s);
        assertTrue(s.contains("--device /dev/kfd --device /dev/dri --group-add video -p 127.0.0.1:8080:8080") && s.contains("llama.cpp:server-rocm"), s);
        assertFalse(s.contains("--gpus all") || s.contains("server-cuda"), s);
        Files.walk(sys.resolve("kfd").resolve("1")).sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        Files.writeString(sys.resolve("drm").resolve("card0").resolve("device").resolve("device"), "0x744c\n");   // an RX 7900, 24 GB
        Files.writeString(sys.resolve("drm").resolve("card0").resolve("device").resolve("mem_info_vram_total"), (24L << 30) + "\n");
        s = Models.describeThisMachine();
        assertTrue(s.startsWith("This machine's card has 24 GB."), s);
        assertTrue(s.contains("--device /dev/dri --group-add video -p") && s.contains("llama.cpp:server-vulkan") && !s.contains("/dev/kfd"), s);
    }

    @Test
    void withoutAGpuTheCommandsKeepTheCudaFormAndTheFirstLineSaysSo() {
        String s = Models.describeThisMachine();
        assertTrue(s.startsWith("No GPU with memory of its own was found on this machine"), s);
        assertTrue(Models.describe(24).contains("--gpus all") && Models.describe(24).contains("server-cuda"), "the page's form, for a reader on any machine");
    }
}
