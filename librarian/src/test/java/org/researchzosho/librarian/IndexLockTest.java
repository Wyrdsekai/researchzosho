package org.researchzosho.librarian;

import org.apache.lucene.analysis.cjk.CJKAnalyzer;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** A crew's write waits for a rebuild's lock instead of dying on it; and the nightly clock. */
class IndexLockTest {

    @Test
    void nightlyFiresAtTheNextOccurrenceOfTheHour() {
        var now = java.time.ZonedDateTime.of(2026, 9, 3, 17, 30, 0, 0, java.time.ZoneId.of("UTC"));
        assertEquals(java.time.Duration.ofHours(9).plusMinutes(30).toMillis(), Crews.millisUntil(3, now), "03:00 tomorrow");
        assertEquals(java.time.Duration.ofMinutes(30).toMillis(), Crews.millisUntil(18, now), "18:00 today");
        var atThree = now.withHour(3).withMinute(0);
        assertEquals(java.time.Duration.ofHours(24).toMillis(), Crews.millisUntil(3, atThree), "exactly on the hour → tomorrow, never 0");
    }

    @Test
    void upsertWaitsForAHeldLock(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp); store.init();
        LibrarianIndex idx = new LibrarianIndex(store, Embeddings.none());
        idx.rebuild();   // creates the index directory
        Finding f = new Finding("F-0001-x", "X", List.of(), Finding.State.accepted, Finding.ClaimType.extraction,
                Finding.Confidence.high, "person", Instant.now().toString(), "2026-09-03", Finding.Volatility.stable, "",
                List.of(new Finding.Source("https://a.org", "n/a", "s")), List.of(), null, "x\n");
        // hold the lock as "another process" would, release it after 2.5s
        var dir = FSDirectory.open(store.luceneDir());
        IndexWriter holder = new IndexWriter(dir, new IndexWriterConfig(new CJKAnalyzer()));
        Thread releaser = new Thread(() -> { try { Thread.sleep(2500); holder.close(); } catch (Exception ignored) { } });
        releaser.start();
        long t0 = System.currentTimeMillis();
        idx.upsert(f);   // must wait, not throw
        long waited = System.currentTimeMillis() - t0;
        releaser.join();
        assertTrue(waited >= 2000, "waited for the lock: " + waited + "ms");
        assertEquals("F-0001-x", idx.search("X", 3).get(0).id());
    }
}
