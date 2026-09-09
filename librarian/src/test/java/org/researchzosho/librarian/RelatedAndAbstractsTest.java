package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RelatedAndAbstractsTest {

    @TempDir Path tmp;
    LibraryStore store;

    private Finding f(String id, String title, Finding.State st, String body, String... subjects) throws Exception {
        Finding x = new Finding(id, title, List.of(subjects), st, Finding.ClaimType.extraction, Finding.Confidence.high,
                "person", Instant.now().toString(), "2026-09-03", Finding.Volatility.stable, "",
                List.of(new Finding.Source("https://a.org/" + id, "n/a", "s")), List.of(), null, body);
        store.write(x);
        return x;
    }

    private void seed() throws Exception {
        store = new LibraryStore(tmp); store.init();
        Files.writeString(store.subjectsFile(), "# S\n\n- japanese--keigo — the honorific system\n- translation--strategy — strategies\n- speech--wav2vec2 — models\n", StandardCharsets.UTF_8);
        f("F-0001-dissolve", "Keigo dissolves", Finding.State.accepted, "Dissolve into register.\n", "japanese--keigo", "translation--strategy");
        f("F-0002-retain", "Retention of honorifics", Finding.State.draft, "Keep -san.\n", "japanese--keigo", "translation--strategy");
        f("F-0003-ctc", "CTC alignment", Finding.State.accepted, "Word timestamps.\n", "speech--wav2vec2");
        f("F-0004-gone", "Retired keigo claim", Finding.State.retired, "old\n", "japanese--keigo");
    }

    @Test
    void relatedIsBySubjectOverlapAndSkipsRetired() throws Exception {
        seed();
        var rel = Related.of(store, store.finding("F-0001-dissolve"), 5);
        assertEquals(1, rel.size(), "F-0002 shares both subjects; F-0003 shares none; F-0004 is retired");
        assertEquals("F-0002-retain", rel.get(0).id());
        assertEquals(List.of("japanese--keigo", "translation--strategy"), rel.get(0).shared());
        assertEquals(1.0, rel.get(0).score(), 0.001);
        var edges = Related.coOccurrence(store);
        assertEquals(2, edges.get("japanese--keigo").get("translation--strategy"));
        assertEquals(3, Related.counts(store).get("speech--wav2vec2") + Related.counts(store).get("japanese--keigo"));
    }

    @Test
    void articlesAreWrittenFromTheShelf_citationsChecked_andRegeneratedOnlyWhenTheShelfChanges() throws Exception {
        seed();
        int[] calls = {0};
        Abstracts.Writer writer = (subject, desc, enumerated) -> {
            calls[0]++;
            assertTrue(enumerated.contains("[F-0001-dissolve] (accepted"), "accepted findings are enumerated with state");
            return "## Summary\nKeigo dissolves into register [F-0001-dissolve]. Retention is a draft position [F-0002-retain].\n"
                    + "A stray claim from nowhere [F-9999-minted].\n## Open questions\nNothing settled about this sentence here.";
        };
        var out = Abstracts.run(store, writer, List.of("japanese--keigo"));
        assertEquals(1, out.written());
        assertTrue(out.problems().get(0).contains("F-9999-minted"), "unknown ids are flagged");
        Path a = Abstracts.articleFile(store, "japanese--keigo");
        String text = Files.readString(a);
        assertTrue(text.contains("shelf_hash: sha256:"), text);
        assertTrue(text.contains("uncited_sentences: 1/"), "the uncited sentence is counted: " + text);
        assertTrue(text.contains("findings: [F-0001-dissolve, F-0002-retain]"), "retired excluded, accepted first");
        // unchanged shelf → no regeneration
        var again = Abstracts.run(store, writer, List.of("japanese--keigo"));
        assertEquals(0, again.written()); assertEquals(1, again.unchanged()); assertEquals(1, calls[0]);
        // the shelf changes (a promotion) → regenerated
        new Council(store).accept("F-0002-retain");
        assertEquals(1, Abstracts.run(store, writer, List.of("japanese--keigo")).written());
        assertEquals(2, calls[0]);
        // an article citing nothing gets ONE retry (with the ids restated); a second refusal stands
        int[] tries = {0};
        var refused = Abstracts.run(store, (s, d, e) -> { tries[0]++; assertTrue(tries[0] == 1 || e.contains("RETRY")); return "Just prose with no citations at all."; }, List.of("speech--wav2vec2"));
        assertEquals(2, tries[0], "exactly one retry");
        assertEquals(0, refused.written());
        assertTrue(refused.problems().get(0).contains("refused (head: \"Just prose"), refused.problems().get(0));
        // bare or parenthesised ids are citations too, normalised to brackets
        var bare = Abstracts.run(store, (s, d, e) -> "## Summary\nCTC alignment yields word timestamps (F-0003-ctc). Also see F-0003-ctc again.", List.of("speech--wav2vec2"));
        assertEquals(1, bare.written(), bare.problems().toString());
        assertTrue(Files.readString(Abstracts.articleFile(store, "speech--wav2vec2")).contains("timestamps [F-0003-ctc]."));
        // and the article is findable at the desk
        var hits = new LibrarianIndex(store, Embeddings.none()).search("keigo dissolves register", 5);
        assertTrue(hits.stream().anyMatch(h -> "article".equals(h.kind()) && h.id().equals("A-japanese--keigo")), hits.toString());
    }
}
