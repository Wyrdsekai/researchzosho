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
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code researchzosho helpers install} on the machine that serves the helpers (0.7.4), as a plan through its seams: a fresh machine
 * (what is missing set up after one question with the sizes, the secret made, the service installed and started, its health asked, the
 * one line for the library's machine); a second run (nothing fetched, the secret and the service kept, said); --only; the card on a
 * machine with several (asked, or a stop with --yes); a refusal on a machine set to run none; macOS, with no service; uninstall; secret.
 * No real service and no real fetch: the systemctl calls, the machine and nvidia-smi are stood in, and the home is a scratch folder.
 */
class HelpersInstallTest {
    String home;
    HelpersInstall.Machine machine;
    Service.Exec exec;
    HelperCards.Probe probe;
    final List<String> ran = new ArrayList<>();
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    Stand m;

    /** A machine: what is set up, what answers, what each install does, which system it is, and the front's health. */
    static class Stand implements HelpersInstall.Machine {
        final Set<Helper> set = EnumSet.noneOf(Helper.class), answering = EnumSet.noneOf(Helper.class);
        final List<String> installs = new ArrayList<>();
        Service.Os os = Service.Os.linux;
        String healthSecret;
        boolean torchSees = true;
        @Override public boolean installed(Helper h) { return set.contains(h); }
        @Override public boolean torchSeesCard(HelperCards.Choice card) { return torchSees; }
        String labse = "card 0";
        @Override public String labseOn() { return labse; }
        @Override public String install(Helper h, HelperCards.Choice card, PrintStream out) { installs.add(h.path + (card.cpu() ? " cpu" : card.card() != null ? " card " + card.card().index() : "")); set.add(h); return "ready"; }
        @Override public boolean answers(Helper h) { return answering.contains(h); }
        @Override public long freeBytes() { return 200L << 30; }
        @Override public Service.Os os() { return os; }
        @Override public String launcher() { return "/opt/rz/bin/researchzosho"; }
        @Override public Map<String, String> ownHealth(int port, String secret, Duration wait) {
            healthSecret = secret;
            Map<String, String> m = new LinkedHashMap<>();
            for (Helper h : List.of(Helper.METRICX, Helper.LABSE, Helper.LIKENESS, Helper.EMBED)) if (set.contains(h) || answering.contains(h)) m.put(h.path, h == Helper.METRICX ? "google/metricx-24-hybrid-large-v2p6 on cuda (card 0, NVIDIA GeForce RTX 4060 Ti)" : "answering");
            return m;
        }
    }

    @BeforeEach void isolate(@TempDir Path tmp) {
        home = System.getProperty("user.home"); System.setProperty("user.home", tmp.resolve("home").toString()); Config.invalidate();
        machine = HelpersInstall.MACHINE; exec = Service.EXEC; probe = HelperCards.PROBE;
        m = new Stand(); HelpersInstall.MACHINE = m;
        Service.EXEC = (cmd, o) -> { ran.add(String.join(" ", cmd)); return cmd.contains("is-active") ? 3 : 0; };
        HelperCards.PROBE = cards("0, GPU-aaaa0000-0000-0000-0000-000000000000, NVIDIA GeForce RTX 4060 Ti, 16380, 9100\n");
        TranslationScores.CUDA_BUILD = () -> true; TranslationScores.WINDOWS = () -> false;   // a Linux x86_64 machine, whatever the tests run on
    }
    @AfterEach void restore() {
        HelpersInstall.MACHINE = machine; Service.EXEC = exec; HelperCards.PROBE = probe; System.setProperty("user.home", home); Config.invalidate();
        TranslationScores.CUDA_BUILD = CUDA; TranslationScores.WINDOWS = WIN;
    }
    static final BooleanSupplier CUDA = TranslationScores.CUDA_BUILD, WIN = TranslationScores.WINDOWS;

    static HelperCards.Probe cards(String listing) {
        return new HelperCards.Probe() {
            @Override public String run(List<String> c) { return c.get(0).equals("nvidia-smi") && c.get(1).startsWith("--query-gpu") ? listing : null; }
            @Override public List<Long> descendants(long pid) { return List.of(); }
            @Override public String environ(long pid) { return ""; }
            @Override public List<String> cmdline(long pid) { return List.of(); }
        };
    }

    int install(String typed, String... flags) throws Exception {
        List<Helper> only = HelperServer.all(); boolean onlyGiven = false, yes = false, cpu = false, move = false; String gpu = null;
        for (int i = 0; i < flags.length; i++) switch (flags[i]) {
            case "--yes" -> yes = true;
            case "--cpu" -> cpu = true;
            case "--gpu" -> gpu = flags[++i];
            case "--move" -> move = true;
            case "--only" -> { onlyGiven = true; only = new ArrayList<>(); for (String n : flags[++i].split(",")) only.add(Helper.of(n)); }
            default -> throw new IllegalArgumentException(flags[i]);
        }
        return HelpersInstall.install(new HelpersInstall.Options(only, onlyGiven, yes, gpu, cpu, 0, Helpers.PORT, false, move), new BufferedReader(new StringReader(typed)), new PrintStream(out, true, StandardCharsets.UTF_8));
    }

    String said() { String s = out.toString(StandardCharsets.UTF_8); out.reset(); return s; }

    @Test void aFreshMachineGetsEverythingAfterOneQuestionAndEndsWithTheLineForTheLibrary() throws Exception {
        m.answering.add(Helper.EMBED);
        assertEquals(0, install("y\n"));
        String s = said();
        assertTrue(s.startsWith("The heavy helpers on this machine, to serve the libraries of other machines:\nThe helpers go on card 0 (NVIDIA GeForce RTX 4060 Ti, 8.9 GB free), the only card on this machine.\n"
                + "  MetricX-24: to set up (google/metricx-24-hybrid-large-v2p6, about 4.7 GB for the model (its weights as 32-bit floats; the repository holds them twice over, and a fetch of both is 9.2 GB) and, for its Python packages with torch's CUDA build (torch, NVIDIA's CUDA libraries, triton, transformers, sentencepiece), a 2.8 GB download that takes 5.2 GB on the disk)\n"), s);
        assertTrue(s.contains("\n  LaBSE: to set up (sentence-transformers/LaBSE, about 1.9 GB, fetched once by the embeddings image through Docker)\n"), s);
        assertTrue(s.contains("\n  speech-to-text: not running here, so it is left out (researchzosho video install sets it up)\n  the embeddings server: answers here, so it is served too\n"), s);
        assertTrue(s.contains("\nFetch and set up MetricX-24 (about 14.4 GB at most), LaBSE (about 1.9 GB at most) and the likeness helper (about 2 GB at most) now, about 18.3 GB at most in all? [y/N] "), s);
        assertEquals(List.of("metricx card 0", "labse card 0", "likeness card 0"), m.installs);
        String secret = Files.readString(HelpersInstall.secretFile()).strip();
        assertTrue(secret.matches("[0-9a-f]{48}"));
        assertTrue(s.contains("A secret was made and kept in " + HelpersInstall.secretFile() + ", which only you can read.\n"), s);
        assertTrue(s.contains("The card is kept for the service and the next install (helpers_gpu 0).\n"), s);
        assertEquals("0", Config.get(Helpers.GPU_NAME));
        assertEquals(List.of("systemctl --user daemon-reload", "systemctl --user enable --now researchzosho-helpers"), ran, "a new unit: written, loaded, enabled and started");
        String unit = Files.readString(HelpersInstall.unitFile());
        assertTrue(unit.contains(" /opt/rz/bin/researchzosho helpers serve --bind 0.0.0.0 --port 8217 --secret-file " + HelpersInstall.secretFile() + " --gpu 0\n"), unit);
        assertTrue(s.contains("Installed and started the service researchzosho-helpers: it serves on port 8217 of every address of this machine, also after a restart"), s);
        assertEquals(secret, m.healthSecret, "its own health asked with the secret");
        assertTrue(s.contains("It serves metricx (google/metricx-24-hybrid-large-v2p6 on cuda (card 0, NVIDIA GeForce RTX 4060 Ti)), labse (answering), likeness (answering), embed (answering).\n"), s);
        assertTrue(s.endsWith("A library whose model is served from this machine finds these helpers by itself. On the library's machine, this sets the secret:\nresearchzosho settings set helpers_secret " + secret + "\n"), s);
        // the second run: nothing fetched, nothing asked, the secret and the service kept, and it says so
        Service.EXEC = (cmd, o) -> { ran.add(String.join(" ", cmd)); return 0; };
        ran.clear(); m.installs.clear();
        assertEquals(0, install(""));
        String again = said();
        assertTrue(again.contains("  MetricX-24: set up already\n  LaBSE: set up already\n  the likeness helper: set up already\n"), again);
        assertTrue(again.contains("The helpers go on card 0 (NVIDIA GeForce RTX 4060 Ti, 8.9 GB free), where they run already (port 8217).\n"), again);
        assertFalse(again.contains("[y/N]"), "nothing to fetch: no question");
        assertTrue(again.contains("The secret is kept in " + HelpersInstall.secretFile() + ", as before.\n"), again);
        assertTrue(again.contains("The service researchzosho-helpers is installed and running already, as before.\n"), again);
        assertEquals(List.of("systemctl --user is-active researchzosho-helpers"), ran);
        assertTrue(m.installs.isEmpty());
        assertEquals(secret, Files.readString(HelpersInstall.secretFile()).strip());
        assertTrue(again.endsWith("researchzosho settings set helpers_secret " + secret + "\n"));
        // the secret command prints it alone; uninstall removes the service and says the models stay
        ByteArrayOutputStream so = new ByteArrayOutputStream();
        assertEquals(0, HelpersInstall.secret(new PrintStream(so, true), new PrintStream(new ByteArrayOutputStream())));
        assertEquals(secret + "\n", so.toString());
        ran.clear();
        assertEquals(0, HelpersInstall.uninstall(new PrintStream(out, true)));
        assertEquals(List.of("systemctl --user disable --now researchzosho-helpers", "systemctl --user daemon-reload"), ran);
        assertFalse(Files.exists(HelpersInstall.unitFile()));
        assertTrue(said().startsWith("Removed the set on card 0 (port 8217, researchzosho-helpers): its helpers are no longer served from this machine.\nThe models stay as they are"));
    }

    @Test void onlyTheOnesNamedAndAChangedServiceRewritten() throws Exception {
        m.set.add(Helper.METRICX);
        assertEquals(0, install("y\n", "--only", "metricx,labse"));
        String s = said();
        assertFalse(s.contains("likeness"), s);
        assertEquals(List.of("labse card 0"), m.installs);
        assertTrue(Files.readString(HelpersInstall.unitFile()).contains(" --gpu 0 --only metricx,labse\n"), "one word: a unit splits at the space");
        ran.clear();
        assertEquals(0, install("move\n", "--cpu", "--only", "metricx,labse"));
        String c = said();
        assertTrue(c.contains("The helpers run on the processor (--cpu): none of them touches a card.\n"), c);
        assertTrue(c.contains("The service researchzosho-helpers was installed with other flags; it was rewritten and restarted on port 8217.\n"), c);
        assertTrue(Files.readString(HelpersInstall.unitFile()).contains(" --cpu --only metricx,labse\n"));
        assertTrue(ran.contains("systemctl --user restart researchzosho-helpers"), ran.toString());
        assertEquals("cpu", Config.get(Helpers.GPU_NAME));
    }

    @Test void severalCardsAreAskedAboutAndWithYesItStops() throws Exception {
        HelperCards.PROBE = cards(HelperCardsTest.FOUR);
        assertEquals(1, install("", "--yes"));
        assertTrue(said().endsWith("This machine has 4 cards (0 NVIDIA RTX A6000, 1 NVIDIA RTX A6000, 2 NVIDIA RTX A6000, 3 NVIDIA RTX 6000 Ada Generation) and nothing says which the helpers go on: name one with --gpu <index>, or --cpu.\n"));
        assertTrue(m.installs.isEmpty()); assertFalse(Files.exists(HelpersInstall.secretFile()));
        assertEquals(0, install("2\ny\n"));
        String s = said();
        assertTrue(s.contains("Which? (an index, or cpu) The helpers go on card 2 (NVIDIA RTX A6000, 46.8 GB free), as you chose.\n  MetricX-24: to set up"), s);
        assertTrue(Files.readString(HelpersInstall.unitFile()).contains(" --gpu 2\n"));
        assertEquals(1, install("", "--gpu", "3", "--yes"), "a card with no set, with --yes and no --move: nothing moves silently");
        assertTrue(said().contains("The helpers go on card 3 (NVIDIA RTX 6000 Ada Generation, 43.9 GB free) (--gpu 3).\n"));
        assertEquals(0, install("", "--gpu", "3", "--yes", "--move"));
        assertTrue(Files.readString(HelpersInstall.unitFile()).contains(" --gpu 3\n"));
    }

    @Test void aMachineSetToRunNoneRefusesAndMacosHasNoServiceYet() throws Exception {
        Config.set(Helpers.LOCAL, "off");
        assertEquals(1, install("y\n"));
        assertEquals("This machine is set to run no heavy helper (local_helpers off), so none is set up or served here: researchzosho settings set local_helpers on allows it.\n", said());
        assertTrue(m.installs.isEmpty() && ran.isEmpty());
        Config.set(Helpers.LOCAL, "");
        m.os = Service.Os.macos;
        assertEquals(0, install("y\n"));
        String s = said();
        assertTrue(s.contains("This system has no service for the helpers yet. To serve them, run this and leave it running:\nresearchzosho helpers serve --bind 0.0.0.0 --port 8217 --secret-file " + HelpersInstall.secretFile() + " --gpu 0\n"), s);
        assertTrue(ran.isEmpty(), "no systemctl on macOS: " + ran);
        assertTrue(s.endsWith("researchzosho settings set helpers_secret " + HelpersInstall.keptSecret() + "\n"));
        // no question answered: nothing fetched
        m.set.clear(); m.os = Service.Os.linux;
        assertEquals(1, install("n\n"));
        assertTrue(said().endsWith("Nothing was fetched or changed.\n"));
    }

    @Test void anInstalledMetricxWhoseTorchCannotSeeTheCardGetsTheCudaBuildCountedInTheQuestion() throws Exception {
        m.set.addAll(List.of(Helper.METRICX, Helper.LABSE, Helper.LIKENESS)); m.torchSees = false;
        assertEquals(0, install("y\n"));
        String s = said();
        assertTrue(s.contains("  MetricX-24: set up, but its torch cannot see a card, so it gets torch's CUDA build in place of its processor build: a 2.8 GB download, about 4 GB more on the disk\n"), s);
        assertTrue(s.contains("Fetch and set up MetricX-24 (about 4 GB at most) now? [y/N] "), s);
        assertEquals(List.of("metricx card 0"), m.installs, "the install path swaps the torch; the others are left as they are");
        // one that can see it is left alone, and the processor's choice asks for no CUDA build at all
        m.installs.clear(); m.torchSees = true;
        assertEquals(0, install(""));
        assertTrue(said().contains("  MetricX-24: set up already\n"));
        m.set.clear(); m.torchSees = false;
        assertEquals(0, install("move\ny\n", "--cpu", "--only", "metricx"));
        String cpu = said();
        assertTrue(cpu.contains("about 1 GB for its Python packages (torch's CPU build, transformers, sentencepiece))\nFetch and set up MetricX-24 (about 10.4 GB at most) now? [y/N] "), cpu);
        assertEquals(List.of("metricx cpu"), m.installs);
    }

    @Test void theSecretCommandWithNoneKept() {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        assertEquals(1, HelpersInstall.secret(new PrintStream(new ByteArrayOutputStream()), new PrintStream(err, true)));
        assertEquals("No secret is kept here yet: researchzosho helpers install makes one.\n", err.toString());
    }
}
