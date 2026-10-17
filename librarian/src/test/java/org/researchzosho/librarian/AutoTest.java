package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Best judgement for the library: the cheapest named model that clears the tier and, when somebody waits, the wait; a free local model
 * when nobody waits; the wait relaxed before the tier; an unusable model passed over with its reason; the research tiers by family.
 */
class AutoTest {

    static ModelEntries.Entry entry(String name, String drive, String model, String prices, String tier) { return new ModelEntries.Entry(name, drive, model, ModelEntries.prices(prices), "", 0, "", tier); }
    static Auto.Candidate cand(ModelEntries.Entry e, String tier, boolean ok, String whyNot, double min, double dollars, boolean free) { return new Auto.Candidate(e, tier, ok, whyNot, min, dollars, free); }

    ModelEntries.Entry local = entry("local-27b", "http://localhost:8080", "qwen3.8-27b", "0/0", "B");
    ModelEntries.Entry deepseek = entry("deepseek", "https://openrouter.ai/api/v1", "deepseek/deepseek-v4.1-flash", "0.04/0.68/0.005", "A");
    ModelEntries.Entry fable = entry("fable", "https://api.anthropic.com", "claude-fable-5-1", "5/25/0.5", "A");

    @Test
    void aResearchRunTakesTheCheapestTierAModelAndAChatTurnTheQuickest() {
        List<Auto.Candidate> all = List.of(cand(local, "B", true, null, 40, 0, true), cand(deepseek, "A", true, null, 10, 0.13, false), cand(fable, "A", true, null, 9, 2.5, false));
        Auto.Pick run = Auto.pick(all, new Auto.Job("research", "A", false, 0));
        assertEquals("deepseek", run.entry().name(), run.why());
        assertTrue(run.why().startsWith("auto chose deepseek: tier A for a tier-A research job, about 10 minutes, about $0.13"), run.why());
        assertTrue(run.why().contains("fable about $2.50, dearer or slower"), run.why());
        assertFalse(run.why().contains("local-27b"), "below the tier: not in the reckoning: " + run.why());
        Auto.Pick chat = Auto.pick(List.of(cand(local, "B", true, null, 4, 0, true), cand(deepseek, "A", true, null, 1, 0.01, false)), new Auto.Job("chat", "B", true, 5));
        assertEquals("local-27b", chat.entry().name(), "free and quick enough for a chat turn: " + chat.why());
        Auto.Pick relaxed = Auto.pick(List.of(cand(local, "B", true, null, 40, 0, true), cand(deepseek, "A", true, null, 10, 0.13, false)), new Auto.Job("quick", "A", true, 5));
        assertEquals("deepseek", relaxed.entry().name()); assertTrue(relaxed.why().contains("the wait is relaxed"), relaxed.why());
        Auto.Pick onlyB = Auto.pick(List.of(cand(local, "B", true, null, 40, 0, true), cand(deepseek, "A", false, "no key kept for openrouter.ai", 10, 0.13, false)), new Auto.Job("research", "A", false, 0));
        assertEquals("local-27b", onlyB.entry().name()); assertTrue(onlyB.why().contains("nothing named is tier A, the best available is B") && onlyB.why().contains("deepseek: no key kept for openrouter.ai"), onlyB.why());
        assertTrue(Auto.pick(List.of(cand(deepseek, "A", false, "its monthly cap is reached", 10, 0.13, false)), new Auto.Job("research", "A", false, 0)).why().contains("using the default"));
    }

    @Test
    void theResearchTiersByFamily() {
        assertEquals("A", ModelTiers.of("deepseek/deepseek-v4.1-flash")); assertEquals("A", ModelTiers.of("moonshotai/kimi-k3")); assertEquals("A", ModelTiers.of("gpt-6.1-sol")); assertEquals("A", ModelTiers.of("z-ai/glm-5.3-flash"));
        assertEquals("B", ModelTiers.of("z-ai/glm-5.3")); assertEquals("B", ModelTiers.of("claude-sonnet-5-5")); assertEquals("B", ModelTiers.of("qwen3.8-27b")); assertEquals("C", ModelTiers.of("gpt-6-luna")); assertEquals("C", ModelTiers.of("tencent/hy4-preview"));
        assertEquals("C", ModelTiers.of("nobody/knows")); assertEquals("C", ModelTiers.of(null));
        assertEquals("A", entry("x", "https://openrouter.ai/api/v1", "moonshotai/kimi-k3", null, null).tier()); assertEquals("C", entry("x", "https://openrouter.ai/api/v1", "moonshotai/kimi-k3", null, "c").tier(), "set by hand");
    }
}
