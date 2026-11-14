package org.researchzosho.librarian;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code researchzosho helpers install --on <target>} (0.7.4) through the ssh seam: the program missing there (the published installer,
 * with this machine's test hooks), the program old there (updated), ssh refused (one line and the two commands by hand), the secret set
 * here and never printed, the model server's port passed on when the model is served from that machine, and a target refused before
 * any ssh runs.
 */
class HelpersRemoteTest {
    static final String KEPT_WORDS = "0123456789abcdef".repeat(3);   // what the other machine keeps: made here in pieces, so no line of this file reads as a credential to the export's scanner
    String home;
    HelpersRemote.Ssh ssh;
    HelpersRemote.HealthAsk health;
    Function<String, String> env;
    final List<List<String>> calls = new ArrayList<>();
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    /** What the machine there answers: by the start of the command it is given. */
    final Map<String, List<HelpersRemote.Ran>> answers = new LinkedHashMap<>();

    @BeforeEach void isolate(@TempDir Path tmp) {
        home = System.getProperty("user.home"); System.setProperty("user.home", tmp.resolve("home").toString()); Config.invalidate(); Helpers.forget();
        ssh = HelpersRemote.SSH; health = HelpersRemote.HEALTH; env = HelpersRemote.ENV;
        HelpersRemote.ENV = k -> null;
        HelpersRemote.STATUS = () -> "(the helpers' status)";
        HelpersRemote.HEALTH = (base, secret, wait) -> {   // in the front's own order, as its health lists them
            if (!KEPT_WORDS.equals(secret)) return null;
            Map<String, String> m = new LinkedHashMap<>(); m.put("metricx", "google/metricx-24-hybrid-large-v2p6 on cuda (card 2, NVIDIA RTX A6000)"); m.put("labse", "sentence-transformers/LaBSE");
            return m;
        };
        HelpersRemote.SSH = (argv, lines) -> {
            calls.add(argv);
            if (argv.get(1).equals("-G")) return new HelpersRemote.Ran(0, "user someone\nhostname 192.168.1.20\nport 22\n");
            String cmd = argv.get(argv.size() - 1).replace(HelpersRemote.PATH_THERE, "");
            for (var e : answers.entrySet()) if (cmd.startsWith(e.getKey()) && !e.getValue().isEmpty()) {
                HelpersRemote.Ran r = e.getValue().size() > 1 ? e.getValue().remove(0) : e.getValue().get(0);
                if (lines != null) for (String l : r.out().split("\n")) if (!l.isEmpty()) lines.accept(l);
                return r;
            }
            return new HelpersRemote.Ran(127, "sh: 1: " + cmd + ": not found");
        };
        answers.put("researchzosho helpers install", new ArrayList<>(List.of(new HelpersRemote.Ran(0, "The heavy helpers on this machine, to serve the libraries of other machines:\n  MetricX-24: set up already\nresearchzosho settings set helpers_secret " + KEPT_WORDS + "\n"))));
        answers.put("researchzosho helpers secret", new ArrayList<>(List.of(new HelpersRemote.Ran(0, KEPT_WORDS + "\n"))));
        answers.put("researchzosho helpers port", new ArrayList<>(List.of(new HelpersRemote.Ran(0, "8217\n"))));
    }
    @AfterEach void restore() { HelpersRemote.SSH = ssh; HelpersRemote.HEALTH = health; HelpersRemote.ENV = env; HelpersRemote.STATUS = HelpersCli::statusSaid; System.setProperty("user.home", home); Config.invalidate(); Helpers.forget(); }

    int on(String target, String typed, boolean yes, String gpu) throws Exception {
        return HelpersRemote.installOn(new HelpersRemote.Options(target, HelperServer.all(), false, yes, gpu, false), new BufferedReader(new StringReader(typed)), new PrintStream(out, true, StandardCharsets.UTF_8));
    }

    List<String> commands() { List<String> c = new ArrayList<>(); for (List<String> a : calls) if (!a.get(1).equals("-G")) c.add(a.get(a.size() - 1).replace(HelpersRemote.PATH_THERE, "")); return c; }

    @Test void aMachineWithoutTheProgramGetsThePublishedInstallerWithTheTestHooksThenTheHelpers() throws Exception {
        HelpersRemote.ENV = k -> switch (k) { case "RESEARCHZOSHO_DOWNLOAD_BASE" -> "http://192.168.1.9:8000/dist"; case "RESEARCHZOSHO_VERSION" -> "0.7.4"; default -> null; };
        answers.put("researchzosho --version", new ArrayList<>(List.of(new HelpersRemote.Ran(127, "sh: 1: researchzosho: not found\n"), new HelpersRemote.Ran(0, "researchzosho 0.7.4\n"))));
        answers.put("curl -fsSL https://researchzosho.org/install", new ArrayList<>(List.of(new HelpersRemote.Ran(0, "researchzosho: downloading researchzosho-0.7.4.tar.gz\nresearchzosho: checksum verified\n"))));
        assertEquals(0, on("someone@homebox", "y\n", false, null));
        String s = out.toString(StandardCharsets.UTF_8);
        assertEquals(List.of("researchzosho --version", "curl -fsSL https://researchzosho.org/install | RESEARCHZOSHO_DOWNLOAD_BASE='http://192.168.1.9:8000/dist' RESEARCHZOSHO_VERSION='0.7.4' sh",
                "researchzosho --version", "researchzosho helpers install --yes", "researchzosho helpers secret", "researchzosho helpers port"), commands());
        for (List<String> a : calls) if (!a.get(1).equals("-G")) assertEquals(List.of("ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=15", "someone@homebox"), a.subList(0, 6), "no password prompt; the target its own argument");
        assertTrue(s.startsWith("On someone@homebox: what is missing of MetricX-24 (google/metricx-24-hybrid-large-v2p6, about 4.7 GB"), s);
        assertTrue(s.contains("served with a secret on port 8217 by a user service. ResearchZosho itself is installed there first when it is missing (the published installer), or updated when it is older than 0.7.4.\nSet them up on someone@homebox now? [y/N] Asking someone@homebox which ResearchZosho it has…\n"), s);
        assertTrue(s.contains("ResearchZosho is not installed on someone@homebox: installing it with the published installer (https://researchzosho.org/install).\n  someone@homebox: researchzosho: downloading researchzosho-0.7.4.tar.gz\n"), s);
        assertTrue(s.contains("someone@homebox has ResearchZosho 0.7.4 now.\nSetting the helpers up on someone@homebox (researchzosho helpers install --yes):\n  someone@homebox: The heavy helpers on this machine"), s);
        assertEquals("http://192.168.1.20:8217", Config.get(Helpers.HELPERS), "the address ssh's own configuration gives the target");
        assertEquals(KEPT_WORDS, Config.get(Helpers.SECRET_NAME));
        assertTrue(s.contains("Set here: helpers http://192.168.1.20:8217, and its secret (helpers_secret), read from someone@homebox and not shown.\nhttp://192.168.1.20:8217 answers from here with the secret: it serves metricx, labse.\n"), s);
        assertTrue(s.endsWith("(the helpers' status)\n"), "the status last: " + s);
        assertTrue(s.contains("  someone@homebox: researchzosho settings set helpers_secret <the secret, read back and set here>\n"), s);
        assertFalse(s.contains(KEPT_WORDS), "the secret is never printed here, also not in the lines passed through");
    }

    @Test void anOlderProgramThereIsUpdatedFirst() throws Exception {
        HelpersRemote.ENV = k -> k.equals("RESEARCHZOSHO_VERSION") ? "0.7.4" : null;
        answers.put("researchzosho --version", new ArrayList<>(List.of(new HelpersRemote.Ran(0, "researchzosho 0.7.3\n"), new HelpersRemote.Ran(0, "researchzosho 0.7.4\n"))));
        answers.put("RESEARCHZOSHO_VERSION='0.7.4' researchzosho update now 0.7.4", new ArrayList<>(List.of(new HelpersRemote.Ran(0, "researchzosho: updating 0.7.3 to 0.7.4\n"))));
        assertEquals(0, on("homebox", "", true, "2"));
        assertEquals(List.of("researchzosho --version", "RESEARCHZOSHO_VERSION='0.7.4' researchzosho update now 0.7.4", "researchzosho --version", "researchzosho helpers install --yes --gpu 2", "researchzosho helpers secret", "researchzosho helpers port --gpu 2"), commands());
        assertTrue(out.toString().contains("homebox has ResearchZosho 0.7.3, older than 0.7.4: updating it there (researchzosho update now).\n  homebox: researchzosho: updating 0.7.3 to 0.7.4\n"), out.toString());
    }

    @Test void aProgramThereThatDoesNotKnowTheFallbackFlagIsUpdatedBeforeItIsHandedIt() throws Exception {
        HelpersRemote.ENV = k -> k.equals("RESEARCHZOSHO_VERSION") ? "0.7.5" : null;
        answers.put("researchzosho --version", new ArrayList<>(List.of(new HelpersRemote.Ran(0, "researchzosho 0.7.4\n"), new HelpersRemote.Ran(0, "researchzosho 0.7.5\n"))));
        answers.put("RESEARCHZOSHO_VERSION='0.7.5' researchzosho update now 0.7.5", new ArrayList<>(List.of(new HelpersRemote.Ran(0, "researchzosho: updating 0.7.4 to 0.7.5\n"))));
        assertEquals(0, HelpersRemote.installOn(new HelpersRemote.Options("homebox", HelperServer.all(), false, true, "2", false, false, false, false), new BufferedReader(new StringReader("")), new PrintStream(out, true, StandardCharsets.UTF_8)));
        assertEquals(List.of("researchzosho --version", "RESEARCHZOSHO_VERSION='0.7.5' researchzosho update now 0.7.5", "researchzosho --version", "researchzosho helpers install --yes --gpu 2 --no-cpu-fallback"), commands().subList(0, 4), "0.7.4 knows helpers install and not this flag: updated first, never handed a flag it would refuse");
        assertTrue(out.toString().contains("homebox has ResearchZosho 0.7.4, older than 0.7.5: updating it there (researchzosho update now).\n"), out.toString());
        assertTrue(out.toString().contains("or updated when it is older than 0.7.5."), "the question says the release this ask needs: " + out);
    }

    @Test void sshThatCannotLogInSaysSoInOneLineWithTheTwoCommandsByHand() throws Exception {
        answers.put("researchzosho --version", new ArrayList<>(List.of(new HelpersRemote.Ran(255, "someone@homebox: Permission denied (publickey).\n"))));
        assertEquals(1, on("someone@homebox", "", true, null));
        assertEquals("ssh could not log in to someone@homebox with your keys alone (someone@homebox: Permission denied (publickey).). Run these two by hand instead:\n"
                + "  on 192.168.1.20:  curl -fsSL https://researchzosho.org/install | sh && researchzosho helpers install\n"
                + "  here:  researchzosho settings set helpers http://192.168.1.20:8217 && researchzosho settings set helpers_secret <what researchzosho helpers secret prints on 192.168.1.20>\n",
                out.toString().substring(out.toString().indexOf("ssh could not")));
        assertNull(Config.get(Helpers.HELPERS)); assertNull(Config.get(Helpers.SECRET_NAME));
    }

    @Test void theModelServersPortGoesAlongWhenTheModelIsServedFromThatMachine() throws Exception {
        Config.set("RESEARCHZOSHO_DRIVE", "http://192.168.1.20:8212");
        answers.put("researchzosho --version", new ArrayList<>(List.of(new HelpersRemote.Ran(0, "researchzosho 0.7.4\n"))));
        assertEquals(0, on("homebox", "", true, null));
        assertTrue(commands().contains("researchzosho helpers install --yes --beside-port 8212"), commands().toString());
        assertTrue(out.toString().contains(", on the card of the model server at port 8212 there."), out.toString());
    }

    @Test void aSeparateSetIsAskedForThereAndThisLibraryIsPointedAtItsPort() throws Exception {
        answers.put("researchzosho --version", new ArrayList<>(List.of(new HelpersRemote.Ran(0, "researchzosho 0.7.4\n"))));
        answers.put("researchzosho helpers install", new ArrayList<>(List.of(new HelpersRemote.Ran(0, "A separate set on card 3: port 8218, unit researchzosho-helpers-gpu3, beside the one on card 2 (port 8217).\nresearchzosho settings set helpers_secret " + KEPT_WORDS + "\n"))));
        answers.put("researchzosho helpers port", new ArrayList<>(List.of(new HelpersRemote.Ran(0, "8218\n"))));
        HelpersRemote.HEALTH = (base, secret, wait) -> base.endsWith(":8218") && KEPT_WORDS.equals(secret) ? Map.of("metricx", "google/metricx-24-hybrid-large-v2p6 on cuda (card 3, NVIDIA RTX 6000 Ada Generation)") : null;
        assertEquals(0, HelpersRemote.installOn(new HelpersRemote.Options("homebox", HelperServer.all(), false, true, "3", false, true, false), new BufferedReader(new StringReader("")), new PrintStream(out, true, StandardCharsets.UTF_8)));
        assertEquals(List.of("researchzosho --version", "researchzosho helpers install --yes --gpu 3 --separate", "researchzosho helpers secret", "researchzosho helpers port --gpu 3 --separate"), commands());
        assertEquals("http://192.168.1.20:8218", Config.get(Helpers.HELPERS), "the separate set's own port");
        String s = out.toString(StandardCharsets.UTF_8);
        assertTrue(s.contains("Set here: helpers http://192.168.1.20:8218, and its secret (helpers_secret), read from homebox and not shown.\nhttp://192.168.1.20:8218 answers from here with the secret: it serves metricx.\n"), s);
        assertTrue(s.contains("as a set of its own, on a port of its own from 8218 (or the set already on that card),"), s);
        assertFalse(s.contains(KEPT_WORDS), "the secret is never printed here");
    }

    @Test void theRefusalOfTheProcessorIsPassedThrough() throws Exception {
        answers.put("researchzosho --version", new ArrayList<>(List.of(new HelpersRemote.Ran(0, "researchzosho 0.7.5\n"))));
        assertEquals(0, HelpersRemote.installOn(new HelpersRemote.Options("homebox", HelperServer.all(), false, true, "3", false, false, false, false), new BufferedReader(new StringReader("")), new PrintStream(out, true, StandardCharsets.UTF_8)));
        assertTrue(commands().contains("researchzosho helpers install --yes --gpu 3 --no-cpu-fallback"), commands().toString());
    }

    @Test void aTargetThatIsNotOneIsRefusedBeforeAnySsh() throws Exception {
        for (String bad : List.of("homebox;reboot", "-oProxyCommand=sh", "a@b@c", "user@-x", "home box", "$(id)")) {
            assertEquals(2, on(bad, "", true, null), bad);
        }
        assertEquals(2, on("homebox", "", true, "0;reboot"));
        assertTrue(calls.isEmpty(), "no ssh ran: " + calls);
        assertTrue(HelpersRemote.validTarget("someone@homebox.local") && HelpersRemote.validTarget("192.168.1.20") && HelpersRemote.validTarget("gpu_box-2"));
        assertEquals("'it'\\''s'", HelpersRemote.quoted("it's"));
    }

    @Test void aNoAtTheQuestionDoesNothingThere() throws Exception {
        assertEquals(1, on("homebox", "n\n", false, null));
        assertTrue(out.toString().endsWith("Nothing was done on homebox.\n"));
        assertTrue(commands().isEmpty());
    }
}
