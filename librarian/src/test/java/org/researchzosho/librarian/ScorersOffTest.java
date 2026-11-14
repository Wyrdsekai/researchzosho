package org.researchzosho.librarian;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code RESEARCHZOSHO_SCORERS=off} leaves the scorers out, as the guide says: no scorer runs, whether a helpers address names a machine
 * or the model's machine would be looked at for one, nothing is looked for, and the plan says the run makes no score.
 */
class ScorersOffTest {
    String home;
    Function<String, Helpers.Look> look;
    LongSupplier now;
    final long[] clock = {1_000_000};
    final List<String> looked = new ArrayList<>();

    @BeforeEach void standIn(@TempDir Path tmp) throws IOException {
        home = System.getProperty("user.home");
        System.setProperty("user.home", tmp.resolve("home").toString()); Config.invalidate();
        look = Helpers.LOOK; now = Helpers.NOW;
        Helpers.LOOK = base -> { looked.add(base); return new Helpers.Look(Helpers.Look.NOTHING, base, null, false, false); };
        Helpers.NOW = () -> clock[0];   // moved on past the five minutes a look is kept, so each plan would look again
        Helpers.forget();   // what an earlier test in this process found or found away is not this one's
        Translation.SETUP_SAID = () -> TranslationScores.labseSetupSaid() + TranslationScores.metricxSetupSaid();
        Config.set("RESEARCHZOSHO_DRIVE", "http://homebox:8212"); Config.set("RESEARCHZOSHO_MODEL", "qwen3.8-27b");
    }

    @AfterEach void restore() {
        Helpers.LOOK = look; Helpers.NOW = now; Helpers.forget();
        Translation.SETUP_SAID = () -> { String a = TranslationScores.labseSetupSaid(), b = TranslationScores.metricxSetupSaid(); return a.isEmpty() ? b : b.isEmpty() ? a : a + "; " + b; };
        System.setProperty("user.home", home); Config.invalidate();
    }

    @Test void noScorerRunsAtANamedHelpersMachine() throws Exception {
        Config.set("RESEARCHZOSHO_HELPERS", "http://127.0.0.1:1");   // a helpers machine named (nothing answers there)
        TranslationScores.Scorers named = TranslationScores.configured();
        assertTrue(named.labse() != null && named.metricx() != null, "with the scorers on, both would be asked there");
        named.close();
        Config.set("RESEARCHZOSHO_SCORERS", "off");
        try (TranslationScores.Scorers off = TranslationScores.configured()) {
            assertNull(off.labse(), "no LaBSE runs: the setting leaves the scorers out");
            assertNull(off.metricx(), "no MetricX-24 runs");
        }
        assertEquals("off (RESEARCHZOSHO_SCORERS=off)", TranslationScores.whereSaid(Helpers.Helper.LABSE));
        assertNull(TranslationScores.labseAddress(false), "a LaBSE that answers on this machine's port is not used either");
    }

    @Test void nothingIsLookedForAndThePlanSaysNoScoreIsMade(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Path f = TranslationTest.fixture(Files.createDirectories(tmp.resolve("docs")));
        Translation.plan(store, f, "ja", "", "checked", "qwen3.8-27b", "", "");
        assertFalse(looked.isEmpty(), "with the scorers on, the model's machine is looked at for a helpers server");
        looked.clear(); clock[0] += 10 * 60_000;
        Config.set("RESEARCHZOSHO_SCORERS", "off");
        Translation.Plan plan = Translation.plan(store, f, "ja", "", "checked", "qwen3.8-27b", "", "");
        assertEquals(List.of(), looked, "with them off, no machine is looked at for them");
        assertTrue(plan.said().contains("LaBSE and MetricX-24 are off (RESEARCHZOSHO_SCORERS=off), so the run makes no score"), plan.said());
    }
}
