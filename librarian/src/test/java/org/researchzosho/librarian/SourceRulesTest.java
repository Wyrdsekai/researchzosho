package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SourceRulesTest {
    @Test
    void trustAndRefuseCoverSubdomainsAndChangeTheTier(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        assertTrue(SourceRules.load(store).isEmpty());
        SourceRules.set(store, "trust", "https://www.nhk.or.jp/news/x", "the broadcaster's own site");
        SourceRules.set(store, "refuse", "content-farm.example", "made-up pages");
        SourceRules r = SourceRules.load(store);
        assertTrue(r.trusted("https://www3.nhk.or.jp/news/html/2026"), "a rule covers subdomains");
        assertTrue(r.refused("https://blog.content-farm.example/post/1"));
        assertFalse(r.refused("https://example.org/"));
        assertNull(r.ruleFor("https://x.example/"), "no rule, no match");
        assertEquals("the broadcaster's own site", r.ruleFor("nhk.or.jp").why());
        String file = Files.readString(SourceRules.file(store));
        assertTrue(file.contains("- trust nhk.or.jp — the broadcaster's own site") && file.contains("- refuse content-farm.example — made-up pages"), file);
        // the live rules, as the search tool and the tier see them
        SourceRules.OVERRIDE = store;
        try {
            assertEquals(SourceTier.primary, SourceTier.of("https://www3.nhk.or.jp/news/html/2026/x.html"), "a trusted host reads as primary");
            assertEquals(SourceTier.web, SourceTier.of("https://content-farm.example/x"));
        } finally { SourceRules.OVERRIDE = null; }
        SourceRules.set(store, "forget", "content-farm.example", "");
        assertFalse(SourceRules.load(store).refused("https://content-farm.example/x"));
        assertThrows(IllegalArgumentException.class, () -> SourceRules.set(store, "trust", "nonsense", ""));
    }
}
