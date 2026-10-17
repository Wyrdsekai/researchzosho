package org.researchzosho.librarian;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;
import org.researchzosho.drive.DriveClient;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Named models: kept in the settings, the default one the drive and model as they stand, keys once per host, a cost from prices. */
class ModelEntriesTest {

    String home;

    @BeforeEach void isolate(@TempDir Path tmp) { home = System.getProperty("user.home"); System.setProperty("user.home", tmp.toString()); Config.invalidate(); }
    @AfterEach void restore() { System.setProperty("user.home", home); Config.invalidate(); }

    @Test
    void entriesAreKeptByNameWithTheDefaultFirst() throws Exception {
        Config.set("RESEARCHZOSHO_DRIVE", "http://localhost:8080"); Config.set("RESEARCHZOSHO_MODEL", "qwen3.8-27b");
        assertEquals(1, ModelEntries.all().size()); assertEquals("qwen3.8-27b", ModelEntries.defaultEntry().model());
        ModelEntries.Entry e = ModelEntries.put("deepseek", "openrouter", "deepseek/deepseek-v4.1-flash", "0.024/0.72/0.005", null, "20", "cheap, reads the most");
        assertEquals("https://openrouter.ai/api", e.drive(), "kept as a drive is kept: without the /v1 the library puts under every address"); assertEquals("openrouter.ai", e.host()); assertTrue(e.hosted());
        assertEquals("deepseek: deepseek/deepseek-v4.1-flash via openrouter.ai — $0.024/0.72/0.005 per M, cap $20 a month, tier A (cheap, reads the most)", e.line());
        ModelEntries.put("local-27b", "http://homebox:8212/v1", "qwen3.8-27b", null, null, null, null);
        Map<String, ModelEntries.Entry> all = ModelEntries.all();
        assertEquals(java.util.List.of("default", "deepseek", "local-27b"), java.util.List.copyOf(all.keySet()), "the default first, then by name");
        assertEquals("http://homebox:8212", all.get("local-27b").drive(), "an address pasted with /v1 is the same drive"); assertFalse(all.get("local-27b").hosted());
        assertNull(ModelEntries.get("nobody"));
        ModelEntries.put("deepseek", null, null, null, "low", null, null);
        assertEquals("low", ModelEntries.get("deepseek").effort()); assertEquals("deepseek/deepseek-v4.1-flash", ModelEntries.get("deepseek").model(), "a null field is left as it was");
        ModelEntries.remove("deepseek");
        assertNull(ModelEntries.get("deepseek")); assertEquals(2, ModelEntries.all().size());
        assertThrows(IllegalArgumentException.class, () -> ModelEntries.remove("default"));
        assertThrows(IllegalArgumentException.class, () -> ModelEntries.put("Bad Name!", "openrouter", "m", null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> ModelEntries.put("x", "openrouter", "m", "cheap", null, null, null), "prices are numbers");
        assertThrows(IllegalArgumentException.class, () -> ModelEntries.put("x", "ftp://nowhere", "m", null, null, null, null));
        ModelEntries.put("default", "anthropic", "claude-sonnet-5-5", "2/10/0.1", null, null, null);
        assertEquals("https://api.anthropic.com", Config.get("RESEARCHZOSHO_DRIVE"), "the default entry is the settings themselves"); assertEquals("claude-sonnet-5-5", ModelEntries.defaultEntry().model());
        assertEquals("2/10/0.1", ModelEntries.defaultEntry().pricesText());
    }

    @Test
    void aCostComesFromThePricesAndTheCacheShare() {
        ModelEntries.Entry e = new ModelEntries.Entry("x", "https://openrouter.ai/api/v1", "m", new double[]{0.024, 0.72, 0.005}, "", 0, "");
        // 4.17 M prompt tokens of which 3.5 M cached, 135 k completion: the DeepSeek run of 2026-10-15 at list prices
        assertEquals(0.13, e.cost(4_170_000, 3_500_000, 135_000), 0.01);
        ModelEntries.Entry two = new ModelEntries.Entry("y", "https://api.example", "m", new double[]{2, 10}, "", 0, "");
        assertEquals(2 + 1, two.cost(1_000_000, 0, 100_000), 1e-9, "two prices: a cached token costs the input price");
        assertEquals(0, new ModelEntries.Entry("z", "http://localhost:8080", "m", null, "", 0, "").cost(1, 1, 1), "no prices, no cost");
    }

    @Test
    void aKeyIsKeptOncePerHostAndGoesOnlyThere() throws Exception {
        Config.set("RESEARCHZOSHO_DRIVE", "http://localhost:8080");
        Config.set(DriveClient.hostKeySetting("openrouter.ai"), "sk-or-123");
        assertEquals("RESEARCHZOSHO_KEY_OPENROUTER_AI", DriveClient.hostKeySetting("openrouter.ai"));
        assertEquals("sk-or-123", DriveClient.keyFor("https://openrouter.ai/api/v1/chat/completions"), "the host's own key");
        assertNull(DriveClient.keyFor("https://api.openai.com/v1"), "another host gets nothing");
        assertNull(DriveClient.keyFor("https://evil.example/openrouter.ai"), "a path is not a host");
        assertEquals("sk-or-123", DriveClient.hostKey("OpenRouter.ai"), "case does not matter");
        assertEquals("https://api.tokenfactory.nebius.com/v1", ModelEntries.hostAddress("nebius")); assertEquals("https://router.huggingface.co/v1", ModelEntries.hostAddress("hf"));
        assertEquals("https://api.anthropic.com", ModelEntries.hostAddress("claude")); assertEquals("http://box:8080", ModelEntries.hostAddress("http://box:8080/v1/"));
    }
}
