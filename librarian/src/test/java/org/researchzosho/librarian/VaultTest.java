package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.util.List;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** The vault: clean notes with YAML an editor parses, links between them, refreshed without churn, stale notes removed. */
class VaultTest {

    static final ObjectMapper M = new ObjectMapper();

    /** Every frontmatter scalar is quoted and every list is a block list: what Obsidian's parser wants. */
    static void assertCleanYaml(Path note) throws Exception {
        String s = Files.readString(note);
        assertTrue(s.startsWith("---\n"), note.toString());
        String head = s.substring(4, s.indexOf("\n---\n", 4));
        for (String line : head.split("\n")) {
            if (line.startsWith("  - ")) { assertTrue(line.substring(4).startsWith("\""), line); continue; }
            int c = line.indexOf(": ");
            if (c < 0) { assertTrue(line.endsWith(":"), "a list key or a scalar: " + line); continue; }
            String v = line.substring(c + 2);
            assertTrue(v.startsWith("\"") || v.equals("[]"), "quoted scalar or empty list: " + line);
        }
    }

    @Test
    void notesLinksRefreshAndStale(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Files.writeString(store.root().resolve("catalog").resolve("subjects.md"), "# Subjects\n\n- gears — ancient gear cutting: \"files\" and dividing plates\n");
        RawCapture.capture(store, "https://example.org/gears", "The gears were cut by hand with files.", "Gears: a note", "test", "");
        var p = new LibraryProtocol(store);
        String fid = p.submit((ObjectNode) M.readTree("{\"claim\":\"The Antikythera gears were cut by hand with files, one tooth at a time, on a dividing plate.\","
                + "\"title\":\"Gears: cut by hand\",\"sources\":[\"https://example.org/gears\"],\"claim_type\":\"extraction\","
                + "\"triple\":{\"subject\":\"Antikythera mechanism\",\"predicate\":\"was made with\",\"object\":\"hand files\"},\"patron\":{\"did\":\"person\",\"runtime\":\"chat\"}}")).get("id").asText();
        Path vault = tmp.resolve("vault");
        var o1 = Vault.generate(store, vault);
        assertTrue(o1.written() >= 4 && o1.removed() == 0, o1.toString());
        Path fnote = vault.resolve("Findings").resolve(fid + ".md");
        assertTrue(Files.exists(fnote));
        assertCleanYaml(fnote);
        String f = Files.readString(fnote);
        assertTrue(f.contains("type: \"finding\"") && f.contains("title: \"Gears: cut by hand\"") && f.contains("state: \"draft\""), f);
        assertTrue(f.contains("[[Sources/") && f.contains("https://example.org/gears"), "the source is a link to its note: " + f);
        assertTrue(f.contains("[[Things/Antikythera mechanism]] **was made with** [[Things/hand files]]"), f);
        assertTrue(f.contains("related_to:") && f.contains("[[Things/hand files]]"), f);
        assertCleanYaml(vault.resolve("Home.md")); assertCleanYaml(vault.resolve("Inbox.md"));
        assertTrue(Files.readString(vault.resolve("Inbox.md")).contains("[[Findings/" + fid), "a draft is in the inbox");
        assertTrue(Files.readString(vault.resolve("Home.md")).contains("Editing a note here changes nothing in the library"));
        var srcs = Files.list(vault.resolve("Sources")).toList();
        assertEquals(1, srcs.size()); assertCleanYaml(srcs.get(0));
        assertTrue(Files.readString(srcs.get(0)).contains("title: \"Gears: a note\""), "a colon in a title is quoted");
        // nothing changed: nothing rewritten
        var o2 = Vault.generate(store, vault);
        assertEquals(0, o2.written(), o2.toString()); assertEquals(o1.written(), o2.unchanged());
        // a person's own note is never touched; a note of ours that is gone from the library is removed
        Files.writeString(vault.resolve("My plan.md"), "# mine\n");
        Files.delete(store.root().resolve("findings").resolve(fid + ".md"));
        var o3 = Vault.generate(store, vault);
        assertFalse(Files.exists(fnote), "the finding's note went with the finding");
        assertTrue(Files.exists(vault.resolve("My plan.md")));
        assertTrue(o3.removed() >= 1);
        // a note of ours that the person edited is left alone
        Path home = vault.resolve("Home.md");
        Files.writeString(home, Files.readString(home) + "\nmy own line\n");
        Vault.generate(store, vault);
        assertTrue(Files.readString(home).contains("my own line"), "an edited note is not overwritten");
    }

    @Test
    void theNightlyStepOnlyRefreshesAVaultThatExists(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        assertTrue(Vault.refresh(store).startsWith("no vault"));
        Vault.generate(store, Vault.dir(store));
        assertEquals(tmp.resolve("lib-vault"), Vault.dir(store));
        assertTrue(Vault.refresh(store).contains("unchanged"));
    }

    @org.junit.jupiter.api.Test
    void theServiceKeepsTheVaultWithinSecondsOfAChange(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Path vault = Vault.dir(store);
        assertFalse(Vault.exists(store), "no vault until the person asks for one");
        Vault.generate(store, vault);   // the person asks once: researchzosho vault
        assertTrue(Vault.exists(store));
        long mark = Vault.newest(store);
        LibrarianDaemon.VAULT_FOLLOW_MS = 200;
        LibrarianDaemon d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1);
        try {
            Thread.sleep(50);
            Finding f = new Finding(store.nextFindingId("late claim"), "A late claim", List.of(), Finding.State.accepted, Finding.ClaimType.extraction, Finding.Confidence.high,
                    "person", java.time.Instant.now().toString(), "2026-09-08", Finding.Volatility.stable, "",
                    List.of(new Finding.Source("https://a.example/1", "n/a", "s")), List.of(), null, "It was late.\n");
            store.write(f);
            assertTrue(Vault.newest(store) >= mark);
            boolean seen = false;
            for (int i = 0; i < 100 && !seen; i++) {
                Thread.sleep(100);
                try (var s = Files.walk(vault)) { seen = s.anyMatch(p -> p.getFileName().toString().contains("late-claim") || p.getFileName().toString().contains("A late claim")); }
            }
            assertTrue(seen, "the vault has the new claim's note without anyone asking");
        } finally { d.stop(); LibrarianDaemon.VAULT_FOLLOW_MS = 10_000; }
    }
}
