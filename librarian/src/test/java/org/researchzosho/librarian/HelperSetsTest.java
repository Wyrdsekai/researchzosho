package org.researchzosho.librarian;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;
import org.researchzosho.librarian.Helpers.Helper;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.IntPredicate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A separate set of helpers per card, as an option (0.7.4): two libraries whose models sit on different cards of one machine each have
 * the helpers on their own card. Through the seams, on a made-up machine of four cards: a second library's --separate lands on its own
 * card and port with the first untouched; run again, it is found and nothing is made; without --separate, on the card of a set that
 * runs, that set; --gpu to a card with no set and no --separate, the question, and the stop with --yes; helpers port; the status of two
 * sets; uninstall --gpu; and the two things from the real run (the model server whose card cannot be read, the wait not said when the
 * service answers at once).
 */
class HelperSetsTest {
    String home;
    HelpersInstall.Machine machine;
    Service.Exec exec;
    HelperCards.Probe probe;
    IntPredicate free;
    BooleanSupplier cuda, win;
    final List<String> ran = new ArrayList<>();
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    HelpersInstallTest.Stand m;
    HelperCardsTest.Fake fake;
    final Map<Integer, Boolean> answersAtOnce = new HashMap<>();

    @BeforeEach void isolate(@TempDir Path tmp) {
        home = System.getProperty("user.home"); System.setProperty("user.home", tmp.resolve("home").toString()); Config.invalidate();
        machine = HelpersInstall.MACHINE; exec = Service.EXEC; probe = HelperCards.PROBE; free = HelperSets.PORT_FREE; cuda = TranslationScores.CUDA_BUILD; win = TranslationScores.WINDOWS;
        TranslationScores.CUDA_BUILD = () -> true; TranslationScores.WINDOWS = () -> false;
        m = new HelpersInstallTest.Stand() {
            @Override public Map<String, String> ownHealth(int port, String secret, Duration wait) {
                if (wait.isZero() && !answersAtOnce.getOrDefault(port, true)) return null;
                Map<String, String> h = new LinkedHashMap<>();
                h.put("metricx", "google/metricx-24-hybrid-large-v2p6 on cuda (" + (port == 8217 ? "card 2" : "card 3") + ", NVIDIA RTX A6000)");
                h.put("labse", "sentence-transformers/LaBSE");
                return h;
            }
        };
        m.set.addAll(List.of(Helper.METRICX, Helper.LABSE, Helper.LIKENESS)); m.labse = "card 2";
        HelpersInstall.MACHINE = m;
        Service.EXEC = (cmd, o) -> { ran.add(String.join(" ", cmd)); return 0; };
        fake = new HelperCardsTest.Fake(); HelperCards.PROBE = fake;
        HelperSets.PORT_FREE = p -> true;
    }
    @AfterEach void restore() {
        HelpersInstall.MACHINE = machine; Service.EXEC = exec; HelperCards.PROBE = probe; HelperSets.PORT_FREE = free; TranslationScores.CUDA_BUILD = cuda; TranslationScores.WINDOWS = win;
        System.setProperty("user.home", home); Config.invalidate();
    }

    int install(String typed, String... flags) throws Exception {
        boolean yes = false, cpu = false, separate = false, move = false; String gpu = null; int beside = 0;
        for (int i = 0; i < flags.length; i++) switch (flags[i]) {
            case "--yes" -> yes = true;
            case "--cpu" -> cpu = true;
            case "--separate" -> separate = true;
            case "--move" -> move = true;
            case "--gpu" -> gpu = flags[++i];
            case "--beside-port" -> beside = Integer.parseInt(flags[++i]);
            default -> throw new IllegalArgumentException(flags[i]);
        }
        return HelpersInstall.install(new HelpersInstall.Options(HelperServer.all(), false, yes, gpu, cpu, beside, Helpers.PORT, separate, move), new BufferedReader(new StringReader(typed)), new PrintStream(out, true, StandardCharsets.UTF_8));
    }

    int port(String... flags) throws Exception {
        boolean cpu = false, separate = false; String gpu = null; int beside = 0;
        for (int i = 0; i < flags.length; i++) switch (flags[i]) {
            case "--cpu" -> cpu = true;
            case "--separate" -> separate = true;
            case "--gpu" -> gpu = flags[++i];
            case "--beside-port" -> beside = Integer.parseInt(flags[++i]);
            default -> throw new IllegalArgumentException(flags[i]);
        }
        return HelpersInstall.port(new HelpersInstall.Options(HelperServer.all(), false, true, gpu, cpu, beside, Helpers.PORT, separate, false), new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(out, true, StandardCharsets.UTF_8));
    }

    String said() { String s = out.toString(StandardCharsets.UTF_8); out.reset(); return s; }

    /** The model server at port 8213 runs on card 3: the proxy listening there, its child holding memory on that card. */
    void modelServerOnCardThree() {
        fake.ss = "LISTEN 0 4096 0.0.0.0:8212 0.0.0.0:* users:((\"llama-swap\",pid=4100,fd=7))\nLISTEN 0 4096 0.0.0.0:8213 0.0.0.0:* users:((\"llama-swap\",pid=5100,fd=7))\n";
        fake.children.put(5100L, List.of(5200L));
        fake.apps = "5200, GPU-dddd3333-3333-3333-3333-333333333333\n";
    }

    @Test void aSecondLibrarysSeparateSetLandsOnItsOwnCardAndPortWithTheFirstUntouched() throws Exception {
        assertEquals(0, install("", "--gpu", "2", "--yes"));
        String firstUnit = Files.readString(HelpersInstall.unitFile());
        assertTrue(firstUnit.contains(" helpers serve --bind 0.0.0.0 --port 8217 --secret-file ") && firstUnit.contains(" --gpu 2\n"), firstUnit);
        String secret = HelpersInstall.keptSecret();
        said(); ran.clear();
        modelServerOnCardThree();
        assertEquals(0, install("", "--separate", "--beside-port", "8213", "--yes"));
        String s = said();
        assertTrue(s.contains("The helpers go on card 3 (NVIDIA RTX 6000 Ada Generation, 43.9 GB free), the card of the model server at port 8213.\n"
                + "A separate set on card 3: port 8218, unit researchzosho-helpers-gpu3, beside the one on card 2 (port 8217).\n"), s);
        assertTrue(s.contains("  LaBSE: one container on this machine, on card 2; every set uses it\n  the likeness helper: set up already; it runs on the processor in each set\n"), s);
        assertEquals(firstUnit, Files.readString(HelpersInstall.unitFile()), "the first set untouched");
        String unit = Files.readString(HelpersInstall.unitFile("researchzosho-helpers-gpu3"));
        assertTrue(unit.contains(" helpers serve --bind 0.0.0.0 --port 8218 --secret-file " + HelpersInstall.secretFile() + " --gpu 3\n"), unit);
        assertEquals(List.of("systemctl --user daemon-reload", "systemctl --user enable --now researchzosho-helpers-gpu3"), ran);
        assertEquals(secret, HelpersInstall.keptSecret(), "the machine's one secret for every set");
        assertEquals("2", Config.get(Helpers.GPU_NAME), "the first set's kept card is not the separate one's");
        assertTrue(s.endsWith("A library uses this set when it names it: researchzosho settings set helpers http://<this machine>:8218 (helpers install --on <this machine> --separate does that for it). On the library's machine, this sets the secret:\nresearchzosho settings set helpers_secret " + secret + "\n"), s);
        // run again: found, nothing made
        ran.clear();
        assertEquals(0, install("", "--separate", "--beside-port", "8213", "--yes"));
        String again = said();
        assertTrue(again.contains("A set of helpers runs on card 3 already (port 8218, researchzosho-helpers-gpu3): it is the one, as before.\n"), again);
        assertTrue(again.contains("The service researchzosho-helpers-gpu3 is installed and running already, as before.\n"), again);
        assertEquals(List.of("systemctl --user is-active researchzosho-helpers-gpu3"), ran);
        assertEquals(2, HelperSets.sets().size());
        // without --separate, the model server's card is one where a set runs: that set (the second library, run again, keeps its own)
        ran.clear();
        assertEquals(0, install("", "--beside-port", "8213", "--yes"));
        assertTrue(said().contains("A set of helpers runs on card 3 already (port 8218, researchzosho-helpers-gpu3): it is the one, as before.\n"));
        assertEquals(firstUnit, Files.readString(HelpersInstall.unitFile()));
        // and a call with no card named joins the first, on the card its unit names
        assertEquals(0, install("", "--yes"));
        assertTrue(said().contains("The helpers go on card 2 (NVIDIA RTX A6000, 46.8 GB free), where they run already (port 8217).\n"));
    }

    @Test void separateWithNoSetYetIsTheFirstAndTheProcessorCountsAsOneCard() throws Exception {
        assertEquals(0, install("", "--separate", "--cpu", "--yes"));
        assertTrue(said().contains("No set of helpers runs on this machine yet, so this one is the first: port 8217, the one a library finds by itself.\n"));
        assertTrue(Files.readString(HelpersInstall.unitFile()).contains(" --port 8217 --secret-file " + HelpersInstall.secretFile() + " --cpu\n"));
        assertEquals(0, install("", "--separate", "--cpu", "--yes"));
        assertTrue(said().contains("A set of helpers runs on the processor already (port 8217, researchzosho-helpers): it is the one, as before.\n"));
        assertEquals(1, HelperSets.sets().size());
    }

    @Test void aCardWithNoSetAndNoSeparateIsAskedAboutAndWithYesItStopsUnlessMove() throws Exception {
        assertEquals(0, install("", "--gpu", "2", "--yes"));
        said();
        String first = Files.readString(HelpersInstall.unitFile());
        String line = "The helpers here run on card 2 (port 8217) for every library that uses them, and card 1 has none: moving them there serves every one of those libraries from card 1 from then on; --separate keeps them and adds a second set on card 1.";
        assertEquals(1, install("", "--gpu", "1", "--yes"));
        assertTrue(said().endsWith(line + " Nothing was changed: --move moves them, --separate adds a set.\n"));
        assertEquals(first, Files.readString(HelpersInstall.unitFile()), "nothing moved silently");
        assertEquals(1, install("\n", "--gpu", "1"));
        String asked = said();
        assertTrue(asked.contains(line + "\nMove them to card 1, make a separate set there, or nothing? [move/separate/N] Nothing was changed.\n"), asked);
        assertEquals(0, install("separate\n", "--gpu", "1"));
        assertTrue(said().contains("A separate set on card 1: port 8218, unit researchzosho-helpers-gpu1, beside the one on card 2 (port 8217).\n"));
        assertEquals(0, install("", "--gpu", "3", "--move", "--yes"));
        String moved = said();
        assertTrue(moved.contains(line.replace("card 1", "card 3") + "\nMoving them to card 3 (--move).\n"), moved);
        assertTrue(Files.readString(HelpersInstall.unitFile()).contains(" --port 8217 --secret-file " + HelpersInstall.secretFile() + " --gpu 3\n"));
        assertTrue(moved.contains("The service researchzosho-helpers was installed with other flags; it was rewritten and restarted on port 8217.\n"), moved);
        assertEquals("3", Config.get(Helpers.GPU_NAME));
    }

    @Test void thePortOfASetTheStatusOfEverySetAndUninstallingOne() throws Exception {
        assertEquals(0, install("", "--gpu", "2", "--yes"));
        assertEquals(0, install("", "--separate", "--gpu", "3", "--yes"));
        said();
        assertEquals(0, port()); assertEquals("8217\n", said());
        assertEquals(0, port("--gpu", "3")); assertEquals("8218\n", said());
        assertEquals(0, port("--separate", "--gpu", "3")); assertEquals("8218\n", said());
        modelServerOnCardThree();
        assertEquals(0, port("--beside-port", "8213")); assertEquals("8218\n", said());
        assertEquals(1, port("--gpu", "1")); assertEquals("No set of helpers here answers for these flags: researchzosho helpers install sets one up.\n", said());
        assertEquals(1, port("--cpu"));
        said();
        answersAtOnce.put(8218, true);
        Service.EXEC = (cmd, o) -> { ran.add(String.join(" ", cmd)); return cmd.contains("researchzosho-helpers-gpu3") && cmd.contains("is-active") ? 3 : 0; };
        assertEquals(0, HelpersInstall.status(new PrintStream(out, true, StandardCharsets.UTF_8)));
        assertEquals("2 sets of helpers on this machine:\n"
                + "  researchzosho-helpers: card 2, port 8217 (the one a library finds by itself), running; it serves metricx (google/metricx-24-hybrid-large-v2p6 on cuda (card 2, NVIDIA RTX A6000)), labse (sentence-transformers/LaBSE).\n"
                + "  researchzosho-helpers-gpu3: card 3, port 8218, not running.\n", said());
        Service.EXEC = (cmd, o) -> { ran.add(String.join(" ", cmd)); return 0; };
        ran.clear();
        assertEquals(0, HelpersInstall.uninstall("3", new PrintStream(out, true, StandardCharsets.UTF_8)));
        assertTrue(said().startsWith("Removed the set on card 3 (port 8218, researchzosho-helpers-gpu3): its helpers are no longer served from this machine.\nThe models stay as they are"));
        assertEquals(List.of("systemctl --user disable --now researchzosho-helpers-gpu3", "systemctl --user daemon-reload"), ran);
        assertTrue(Files.exists(HelpersInstall.unitFile()), "the first set stays");
        assertEquals(0, HelpersInstall.uninstall(new PrintStream(out, true, StandardCharsets.UTF_8)));
        assertTrue(said().startsWith("Removed the set on card 2 (port 8217, researchzosho-helpers)"));
        assertTrue(HelperSets.sets().isEmpty());
    }

    @Test void aModelServerWhoseCardCannotBeReadIsSaidSoAndTheWaitIsNotSaidWhenItAnswersAtOnce() throws Exception {
        // right after the big machine restarts: the proxy listens, it has no child, no card can be read
        fake.ss = "LISTEN 0 4096 0.0.0.0:8212 0.0.0.0:* users:((\"llama-swap\",pid=4100,fd=7))\n";
        fake.children.clear();
        assertEquals(1, install("", "--beside-port", "8212", "--yes"));
        assertTrue(said().endsWith("The model server at port 8212 runs no model right now, so its card cannot be read: name the card with --gpu <index>, or use the model once and run this again.\n"));
        assertEquals(1, install("", "--beside-port", "8299", "--yes"));
        assertTrue(said().endsWith("Nothing listens on port 8299 here, so the card of a model server there cannot be read: name the card with --gpu <index>, or --cpu.\n"));
        // the service answers at the first ask: no wait said; one that does not: the wait said
        assertEquals(0, install("", "--gpu", "2", "--yes"));
        assertFalse(said().contains("Waiting for it to answer"), "it answered at once");
        answersAtOnce.put(8217, false);
        assertEquals(0, install("", "--gpu", "2", "--yes"));
        assertTrue(said().contains("Waiting for it to answer (its first start loads the models, which takes minutes)…\nIt serves metricx"));
    }
}
