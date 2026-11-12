package org.researchzosho.librarian;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.librarian.Translation.Flag;
import org.researchzosho.librarian.Translation.Outcome;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The record the score thresholds are measured from is a fair sample: ten translations whose paragraphs all score from one distribution
 * keep their flagged share near one in ten per scorer, run after run. A record that held only the paragraphs nothing flagged was cut off
 * at the threshold it set, and the threshold tightened at every run on translations of the same quality (about a tenth flagged per scorer
 * on the second run, near three tenths after ten).
 */
class TranslationRecordFairnessTest {
    static final int PARAGRAPHS = 80, RUNS = 10;
    static final String REVISED = "überarbeitet ";
    static final String[] WORDS = {"river", "mill", "bridge", "garden", "lantern", "harbour", "orchard", "meadow", "window", "letter", "winter", "kettle"};

    @BeforeEach void standIns() { Translation.SETUP_SAID = () -> ""; }
    @AfterEach void back() { TranslationScores.SCORERS = TranslationScores::configured; }

    /**
     * A scorer that gives every first-pass translation a fresh draw from one distribution (the translations are all of the same quality)
     * and a revision the best score, so a revision is always kept and raises no flag of its own: the flags counted are the first checks'.
     */
    static TranslationScores.Scorer drawn(Random r, String name, boolean higherIsBetter, double mean, double sd, double lo, double hi) {
        return new TranslationScores.Scorer() {
            @Override public List<Double> score(List<String[]> pairs) {
                List<Double> out = new ArrayList<>();
                for (String[] p : pairs) out.add(p[1].startsWith(REVISED) ? (higherIsBetter ? hi : lo) : Math.round(Math.max(lo, Math.min(hi, mean + sd * r.nextGaussian())) * 10000) / 10000.0);
                return out;
            }
            @Override public String name() { return name; }
            @Override public String model() { return "a stand-in"; }
            @Override public boolean higherIsBetter() { return higherIsBetter; }
        };
    }

    /** A document of paragraphs of plain words, each of forty characters or more, with no number, address, citation or repeated name. */
    static Path document(Path dir, int run) throws Exception {
        Files.createDirectories(dir);
        StringBuilder b = new StringBuilder("# Notes from the valley\n\n");
        for (int p = 0; p < PARAGRAPHS; p++) {
            StringBuilder s = new StringBuilder("The");
            for (int w = 0; w < 9; w++) s.append(' ').append(WORDS[(p * 7 + w * 5 + run * 3 + w * p) % WORDS.length]);
            b.append(s).append(" stood quietly by the road that evening.\n\n");
        }
        Path f = dir.resolve("valley-" + run + ".md");
        Files.writeString(f, b.toString(), StandardCharsets.UTF_8);
        return f;
    }

    @Test void theFlaggedShareStaysNearOneInTenPerScorerRunAfterRun(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Random r = new Random(20261111);
        TranslationScores.SCORERS = () -> new TranslationScores.Scorers(drawn(r, "LaBSE", true, 0.85, 0.05, 0, 1), drawn(r, "MetricX-24", false, 6, 2.5, 0, 25));
        double[] labse = new double[RUNS], metricx = new double[RUNS];
        StringBuilder seen = new StringBuilder();
        for (int run = 0; run < RUNS; run++) {
            TranslationTest.Script sc = new TranslationTest.Script();
            sc.translate = t -> "übersetzt " + t;
            sc.revise = t -> REVISED + t;
            Outcome o = Translation.run(store, document(tmp.resolve("docs"), run), "de", "", "checked", tmp.resolve("out-" + run + ".md"), null, TranslationTest.seats(sc), null, () -> false, Checkpoint.NONE, "", String.format("J-%04d", run + 1));
            labse[run] = o.flags().stream().filter(f -> f.kind().equals("labse")).map(Flag::unit).distinct().count() / (double) PARAGRAPHS;
            metricx[run] = o.flags().stream().filter(f -> f.kind().equals("metricx")).map(Flag::unit).distinct().count() / (double) PARAGRAPHS;
            seen.append(String.format("run %d: LaBSE %.3f, MetricX-24 %.3f, withheld \"%s\", %s%n", run + 1, labse[run], metricx[run], o.withheld(), o.thresholds().said()));
        }
        System.out.print(seen);
        assertEquals(0, labse[0] + metricx[0], "the first run has no record to measure from: " + seen);
        double meanL = 0, meanM = 0, lateL = 0, lateM = 0;
        for (int run = 1; run < RUNS; run++) { meanL += labse[run] / (RUNS - 1); meanM += metricx[run] / (RUNS - 1); }
        for (int run = RUNS - 3; run < RUNS; run++) { lateL += labse[run] / 3; lateM += metricx[run] / 3; }
        assertTrue(meanL > 0.06 && meanL < 0.14 && meanM > 0.06 && meanM < 0.14, "about one paragraph in ten per scorer over runs 2 to 10: LaBSE " + meanL + ", MetricX-24 " + meanM + "\n" + seen);
        assertTrue(lateL < 0.15 && lateM < 0.15, "no drift: the last three runs flag as the second did, LaBSE " + lateL + ", MetricX-24 " + lateM + "\n" + seen);
    }

    /**
     * The withheld rule counts what the program's own checks found, never the scores: a record whose thresholds flag every paragraph by
     * score cannot lock the library out of it. The job's paragraphs go on, and the threshold moves toward what the model writes.
     */
    @Test void aSkewedThresholdCannotLockALibraryOutOfItsOwnRecord(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        List<TranslationScores.Row> seeds = new ArrayList<>(); for (int i = 0; i < 40; i++) seeds.add(new TranslationScores.Row("J-0001", "", i + 1, 0.90 + i * 0.002, null, 3.0));
        TranslationScores.accept(store, "ja", "en", seeds);
        double before = TranslationScores.thresholds(store, "ja", "en").labseLow();
        Path in = DocNamesTest.doc(tmp.resolve("docs"));
        TranslationScores.SCORERS = () -> new TranslationScores.Scorers(TranslationTest.labseStandIn(), null);
        Outcome o = Translation.run(store, in, "en", "", "checked", null, null, DocNamesTest.seats(DocNamesTest.script()), null, () -> false, Checkpoint.NONE, "", "J-0050");
        assertEquals(5, o.flags().stream().filter(fl -> fl.kind().equals("labse")).count(), "the stand-in's LaBSE, 0.3 for every paragraph here, is below the threshold " + before + " on all five: " + o.flags());
        assertEquals("", o.withheld(), "the program's own checks flagged one paragraph of five, the stray name");
        assertEquals(Map.of("ja→en", 5), TranslationScores.onRecord(store, "J-0050"));
        assertTrue(TranslationScores.thresholds(store, "ja", "en").labseLow() < before, "the record renews: " + TranslationScores.thresholds(store, "ja", "en").said());
    }
}
