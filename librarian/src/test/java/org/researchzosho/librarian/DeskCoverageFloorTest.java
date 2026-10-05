package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The desk's floor for long texts: a captured page that has three common words of a long question is not a holding; a finding
 * with the same three words is; a captured page that is about the question is. Measured on the live shelf 2026-10-03: without
 * this, ten long questions about nothing on the shelf all got an answer, every one a captured page.
 */
class DeskCoverageFloorTest {

    @TempDir Path tmp;

    private LibraryStore shelf() throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib"));
        store.init();
        Files.createDirectories(store.rawDir());
        // a long captured page about something else, which happens to carry "model", "video" and "human" once each among much else
        StringBuilder page = new StringBuilder("---\nurl: https://example.org/garden\ntitle: Notes on a kitchen garden\nfetched_at: 2026-09-01T10:00:00Z\nfetched_by: test\n---\n");
        for (int i = 0; i < 60; i++) page.append("The beans climbed the trellis in week ").append(i).append(" while the soil warmed and the slugs came at night. ");
        page.append("A video of the harvest shows a model greenhouse and the human effort behind it.\n");
        Files.writeString(store.rawDir().resolve("2026-09-01-garden01.md"), page.toString(), StandardCharsets.UTF_8);
        // a captured page that IS about the question
        Files.writeString(store.rawDir().resolve("2026-09-02-pose0001.md"),
                "---\nurl: https://example.org/pose\ntitle: Pose estimation on broadcast video\nfetched_at: 2026-09-02T10:00:00Z\nfetched_by: test\n---\n"
                + "Open-source pose estimation models detect human joints in broadcast video on a CPU; accuracy falls on occluded and fast-moving limbs.\n", StandardCharsets.UTF_8);
        // a finding, one claim, with the three common words
        Finding f = new Finding("F-0001-model-video", "A model of the human video pipeline", List.of(), Finding.State.accepted, Finding.ClaimType.interpretation,
                Finding.Confidence.medium, "person", Instant.now().toString(), "2026-09-01", Finding.Volatility.stable, "",
                List.of(new Finding.Source("https://example.org/f1", "", "s")), List.of(), null, "The model reads the human from the video.\n");
        store.write(f);
        new LibrarianIndex(store).rebuild();
        return store;
    }

    @Test
    void aLongPageWithThreeCommonWordsIsNotAHoldingAFindingOrAPageAboutItIs() throws Exception {
        LibrarianIndex index = new LibrarianIndex(shelf(), Embeddings.none());
        String q = "What is the most accurate open-source pose estimation model for detecting human joints in broadcast video on a CPU, and how does it do on occluded limbs?";
        List<LibrarianIndex.Hit> strict = index.searchStrict(q, 10, null, null);
        List<String> ids = strict.stream().map(LibrarianIndex.Hit::id).toList();
        assertTrue(ids.contains("2026-09-02-pose0001.md"), "the page about the question is a holding: " + ids);
        assertTrue(ids.contains("F-0001-model-video"), "a finding is judged by the minimum-match rule alone: " + ids);
        assertFalse(ids.contains("2026-09-01-garden01.md"), "the garden page has three of the question's words and none of its substance: " + ids);
        // plain search keeps the weak hit — scores are comparable within one call; only the desk is floored
        List<String> plain = index.search(q, 10).stream().map(LibrarianIndex.Hit::id).toList();
        assertTrue(plain.contains("2026-09-01-garden01.md"), plain.toString());
        // a long question the shelf holds nothing on: nothing
        assertTrue(index.searchStrict("Which tuning systems were used for the harpsichord in Naples between 1690 and 1740, and how would a modern player decide between them for a recording of a trio sonata?", 10, null, null).isEmpty());
    }
}
