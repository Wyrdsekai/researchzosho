package org.researchzosho.librarian;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BiPredicate;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MetricX-24 on a card (0.7.4): with a card chosen on Linux x86_64, a fresh environment gets torch's CUDA build (PyPI's), and one whose
 * torch cannot see the card gets it in place of the processor build; one that can is left alone; a CUDA build that cannot be installed
 * falls back to the processor build, said in one line; the model's first load runs under the chosen card's environment (its UUID), and
 * what it loaded on is said from its own reply. With no card chosen (a library's own machine), the processor build, as before.
 */
class MetricxTorchTest {
    String home;
    Searx.Runner runner;
    BiPredicate<Path, HelperCards.Choice> sees;
    HelperServer.Starter check;
    BooleanSupplier cuda, win;
    final List<String> pip = new ArrayList<>();
    final List<Map<String, String>> checked = new ArrayList<>();
    final List<String> said = new ArrayList<>();
    String reply = "{\"ready\": true, \"device\": \"cuda\", \"card\": \"NVIDIA RTX A6000\", \"card_uuid\": \"GPU-cccc2222-2222-2222-2222-222222222222\"}";
    int cudaInstall = 0;

    static final HelperCards.Card TWO = new HelperCards.Card("2", "GPU-cccc2222-2222-2222-2222-222222222222", "NVIDIA RTX A6000", 49140, 47900);
    static final HelperCards.Choice CARD = new HelperCards.Choice(TWO, false, "", false), CPU = new HelperCards.Choice(null, true, "", false);

    @BeforeEach void isolate(@TempDir Path tmp) {
        home = System.getProperty("user.home"); System.setProperty("user.home", tmp.resolve("home").toString()); Config.invalidate();
        runner = Searx.runner; sees = TranslationScores.TORCH_SEES; check = TranslationScores.CHECK; cuda = TranslationScores.CUDA_BUILD; win = TranslationScores.WINDOWS;
        TranslationScores.CUDA_BUILD = () -> true; TranslationScores.WINDOWS = () -> false;
        Searx.runner = c -> {
            String line = String.join(" ", c);
            pip.add(line.replace(TranslationScores.metricxDir().toString(), "<dir>"));
            boolean isCuda = line.contains(" install ") && line.endsWith(" torch") && line.contains("--no-cache-dir");
            return new Searx.Result(isCuda ? cudaInstall : 0, isCuda && cudaInstall != 0 ? "ERROR: No matching distribution found for torch" : "");
        };
        TranslationScores.CHECK = pb -> { checked.add(new HashMap<>(pb.environment())); return answering(reply); };
    }
    @AfterEach void restore() {
        Searx.runner = runner; TranslationScores.TORCH_SEES = sees; TranslationScores.CHECK = check; TranslationScores.CUDA_BUILD = cuda; TranslationScores.WINDOWS = win;
        System.setProperty("user.home", home); Config.invalidate();
    }

    /** A process that has printed {@code out} and ended well. */
    static Process answering(String out) {
        return new Process() {
            @Override public OutputStream getOutputStream() { return OutputStream.nullOutputStream(); }
            @Override public InputStream getInputStream() { return new ByteArrayInputStream((out + "\n").getBytes(StandardCharsets.UTF_8)); }
            @Override public InputStream getErrorStream() { return InputStream.nullInputStream(); }
            @Override public int waitFor() { return 0; }
            @Override public boolean waitFor(long t, TimeUnit u) { return true; }
            @Override public int exitValue() { return 0; }
            @Override public void destroy() { }
        };
    }

    @Test void aFreshEnvironmentOnACardGetsTheCudaBuildAndLoadsUnderThatCard() {
        assertEquals("ready", TranslationScores.installMetricx(CARD, said::add));
        assertTrue(pip.stream().anyMatch(l -> l.endsWith("venv/bin/pip install --quiet --no-cache-dir --upgrade torch")), "PyPI's torch, the CUDA build on Linux: " + pip);
        assertTrue(pip.stream().noneMatch(l -> l.contains("whl/cpu")), pip.toString());
        assertEquals("GPU-cccc2222-2222-2222-2222-222222222222", checked.get(0).get("CUDA_VISIBLE_DEVICES"), "the first load runs on the chosen card, by its UUID");
        assertEquals("PCI_BUS_ID", checked.get(0).get("CUDA_DEVICE_ORDER"));
        assertEquals(List.of("MetricX-24 loads on the card (NVIDIA RTX A6000)."), said);
        TranslationScores.WINDOWS = () -> true;
        assertTrue(TranslationScores.torchInstall(Path.of("pip.exe"), true).contains("https://download.pytorch.org/whl/cu132"), "Windows: PyTorch's CUDA index, PyPI's being the processor build");
    }

    @Test void aCudaBuildThatCannotBeInstalledFallsBackToTheProcessorBuildAndSaysSo() {
        cudaInstall = 1; reply = "{\"ready\": true, \"device\": \"cpu\"}";
        assertEquals("ready", TranslationScores.installMetricx(CARD, said::add));
        assertEquals("torch's CUDA build could not be installed (ERROR: No matching distribution found for torch), so MetricX-24 gets the processor build and scores on the processor.", said.get(0));
        assertTrue(pip.stream().anyMatch(l -> l.endsWith("install --quiet --upgrade torch --index-url https://download.pytorch.org/whl/cpu")), pip.toString());
        assertEquals("MetricX-24 loads on the processor.", said.get(1), "said from the load's own reply");
    }

    @Test void anEnvironmentWhoseTorchCannotSeeTheCardHasItReplacedAndOneThatCanIsLeftAlone() throws Exception {
        Path py = TranslationScores.metricxPython();
        Files.createDirectories(py.getParent()); Files.writeString(py, "#!/bin/sh\n");
        TranslationScores.TORCH_SEES = (p, c) -> false;
        assertEquals("ready", TranslationScores.installMetricx(CARD, said::add));
        assertEquals("MetricX-24's torch cannot see a card (it is the processor build): torch's CUDA build in place of its processor build: a 2.8 GB download, about 4 GB more on the disk.", said.get(0));
        assertEquals(List.of("<dir>/venv/bin/pip uninstall --yes --quiet torch", "<dir>/venv/bin/pip install --quiet --no-cache-dir --upgrade torch"), pip);
        pip.clear(); said.clear();
        TranslationScores.TORCH_SEES = (p, c) -> true;
        assertEquals("ready", TranslationScores.installMetricx(CARD, said::add));
        assertTrue(pip.isEmpty(), "a torch that sees the card is left alone: " + pip);
        // the processor chosen: no card for the load, and nothing swapped
        TranslationScores.TORCH_SEES = (p, c) -> false; reply = "{\"ready\": true, \"device\": \"cpu\"}";
        assertEquals("ready", TranslationScores.installMetricx(CPU, said::add));
        assertTrue(pip.isEmpty(), pip.toString());
        assertEquals("", checked.get(checked.size() - 1).get("CUDA_VISIBLE_DEVICES"));
    }

    @Test void aLibrarysOwnMachineKeepsTheProcessorBuild() {
        assertEquals("ready", TranslationScores.installMetricx());
        assertTrue(pip.stream().anyMatch(l -> l.endsWith("install --quiet --upgrade torch --index-url https://download.pytorch.org/whl/cpu")), pip.toString());
        assertEquals(System.getenv("CUDA_VISIBLE_DEVICES"), checked.get(0).get("CUDA_VISIBLE_DEVICES"), "no card named: the environment as it was");
    }
}
