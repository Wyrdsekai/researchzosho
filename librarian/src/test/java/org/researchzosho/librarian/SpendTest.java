package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** The spend ledger: one line per run at the entry's prices, summed by host and by entry for the month and for all time. */
class SpendTest {
    @Test
    void runsAreRecordedAndSummedByHostAndEntry(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        ModelEntries.Entry ds = new ModelEntries.Entry("deepseek", "https://openrouter.ai/api", "deepseek/deepseek-v4.1-flash", new double[]{0.024, 0.72, 0.005}, "", 0, "");
        ModelEntries.Entry fable = new ModelEntries.Entry("fable", "https://api.anthropic.com", "claude-fable-5-1", new double[]{10, 50, 0.25}, "", 20, "");
        assertEquals(0, Spend.monthTotal(store, "deepseek"), 1e-9);
        Spend.record(store, "J-0001", "I-0001", ds, new long[]{4_170_000, 3_500_000, 135_000}, ds.cost(4_170_000, 3_500_000, 135_000));
        Spend.record(store, "J-0002", "I-0002", ds, new long[]{2_000_000, 1_000_000, 50_000}, ds.cost(2_000_000, 1_000_000, 50_000));
        Spend.record(store, "J-0003", "I-0003", fable, new long[]{2_378_161, 1_643_446, 52_157}, fable.cost(2_378_161, 1_643_446, 52_157));
        assertEquals(3, Spend.lines(store).size());
        assertEquals(0.13 + ds.cost(2_000_000, 1_000_000, 50_000), Spend.monthTotal(store, "deepseek"), 0.02, "the entry's month");
        assertEquals(Spend.monthTotal(store, "deepseek"), Spend.monthTotal(store, "openrouter.ai"), 1e-9, "the host's month, the same runs");
        assertEquals(10.5, Spend.monthTotal(store, "fable"), 0.3, "Fable's run of 2026-10-15 at list prices");
        var sum = Spend.summary(store);
        assertEquals(2, sum.path("hosts").size()); assertEquals(2, sum.path("entries").size());
        var fableRow = sum.path("entries").get(1);
        assertEquals("fable", fableRow.path("name").asText()); assertEquals(1, fableRow.path("runs_month").asInt()); assertFalse(fableRow.has("cap"), "no cap unless the entry is kept in the settings");
        assertEquals("$10.50", Spend.dollars(10.5));
    }
}
