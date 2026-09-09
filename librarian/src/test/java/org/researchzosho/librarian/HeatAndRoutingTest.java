package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Usage heat ranks what patrons were given; the desk routes by intent before it searches. */
class HeatAndRoutingTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static Finding f(String id, String title, String body, String... subjects) {
        return new Finding(id, title, List.of(subjects), Finding.State.accepted, Finding.ClaimType.extraction, Finding.Confidence.high,
                "person", Instant.now().toString(), "2026-09-05", Finding.Volatility.stable, "",
                List.of(new Finding.Source("https://ex.org/" + id, "n/a", "s")), List.of(), null, body);
    }

    private static LibraryStore shelf(Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp); store.init();
        store.write(f("F-0001-keigo-a", "Keigo dissolves into register", "Keigo dissolves into English register in subtitles.\n", "japanese--keigo"));
        store.write(f("F-0002-keigo-b", "Keigo kept as honorific", "Keigo is kept as an honorific suffix in subtitles.\n", "japanese--keigo"));
        store.write(f("F-0003-timing", "Subtitle timing", "A subtitle line stays on screen for at most seven seconds.\n", "subtitling--timing"));
        Files.writeString(store.subjectsFile(), "# Subjects\n\n- japanese--keigo — the honorific system\n- subtitling--timing — line duration\n", StandardCharsets.UTF_8);
        Files.createDirectories(store.rawDir());
        Files.writeString(store.rawDir().resolve("2026-09-01-abc.md"), "---\nurl: https://example.org/hosaka2016\ntitle: Hosaka 2016\nfetched_at: 2026-09-01T10:00:00Z\nfetched_by: test\n---\nThe paper.\n", StandardCharsets.UTF_8);
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        return store;
    }

    @Test
    void heatIsRecordedFoldedAndBoostsWithoutReordering(@TempDir Path tmp) throws Exception {
        LibraryStore store = shelf(tmp);
        var p = new LibraryProtocol(store);
        // two findings tie on words; nothing is hot yet
        var cold = new LibrarianIndex(store, Embeddings.none()).search("keigo subtitles", 5);
        assertEquals(2, cold.stream().filter(h -> h.id().startsWith("F-000")).limit(2).count());
        // the desk hands out F-0002 many times
        for (int i = 0; i < 20; i++) Heat.used(store, List.of("F-0002-keigo-b"));
        p.ask((ObjectNode) M.readTree("{\"question\":\"keigo kept honorific\"}"));   // the desk records what it returned too
        assertTrue(Files.readString(Heat.log(store)).contains("F-0002-keigo-b"));
        assertEquals(2, Heat.fold(store, 30) >= 1 ? 2 : 0);
        Map<String, Integer> heat = Heat.load(store);
        assertTrue(heat.get("F-0002-keigo-b") >= 20);
        assertEquals(1.0, Heat.boost(heat, "F-0003-timing"), "cold stays 1");
        assertTrue(Heat.boost(heat, "F-0002-keigo-b") > 1.2 && Heat.boost(heat, "F-0002-keigo-b") < 1.6, "a modest boost");
        var hot = new LibrarianIndex(store, Embeddings.none()).search("keigo subtitles", 5);
        assertEquals("F-0002-keigo-b", hot.get(0).id(), "the hot one leads a tie");
        // a stale line outside the window is not counted
        Files.writeString(Heat.log(store), "2020-01-01T00:00:00Z\tF-0003-timing\n", StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
        Heat.fold(store, 30);
        assertNull(Heat.load(store).get("F-0003-timing"));
    }

    @Test
    void theDeskRoutesByIntentBeforeSearching(@TempDir Path tmp) throws Exception {
        LibraryStore store = shelf(tmp);
        var p = new LibraryProtocol(store);
        ObjectNode byId = p.ask((ObjectNode) M.readTree("{\"question\":\"F-0003-timing\"}"));
        assertEquals("id", byId.get("routed").asText());
        assertEquals("F-0003-timing", byId.get("entries").get(0).get("id").asText());
        ObjectNode byUrl = p.ask((ObjectNode) M.readTree("{\"question\":\"https://example.org/hosaka2016\"}"));
        assertEquals("locator", byUrl.get("routed").asText());
        assertEquals("raw", byUrl.get("entries").get(0).get("kind").asText());
        ObjectNode bySubject = p.ask((ObjectNode) M.readTree("{\"question\":\"everything on japanese--keigo\"}"));
        assertEquals("subject", bySubject.get("routed").asText());
        assertEquals(2, bySubject.get("entries").size());
        for (var e : bySubject.get("entries")) assertTrue(e.get("subjects").toString().contains("japanese--keigo"));
        assertEquals("subject", p.ask((ObjectNode) M.readTree("{\"question\":\"japanese keigo\"}")).get("routed").asText(), "the slug's words name the shelf too");
        ObjectNode changes = p.ask((ObjectNode) M.readTree("{\"question\":\"what changed since 2026-01-01\"}"));
        assertEquals("changes", changes.get("routed").asText());
        assertTrue(changes.get("changes").size() >= 3, "the three additions");
        assertFalse(changes.get("holds_nothing").asBoolean(), "a changes answer is never 'holds nothing'");
        ObjectNode lastDays = p.ask((ObjectNode) M.readTree("{\"question\":\"what is new in the last 3 days\"}"));
        assertEquals("changes", lastDays.get("routed").asText());
        ObjectNode plain = p.ask((ObjectNode) M.readTree("{\"question\":\"how long may a subtitle line stay on screen\"}"));
        assertEquals("search", plain.get("routed").asText());
        assertEquals("F-0003-timing", plain.get("entries").get(0).get("id").asText());
        // an unheld URL is not demand; an unheld question is
        ObjectNode noUrl = p.ask((ObjectNode) M.readTree("{\"question\":\"https://nowhere.example/x\"}"));
        assertTrue(noUrl.get("holds_nothing").asBoolean());
        assertFalse(noUrl.has("filed_as_demand"));
        ObjectNode noQ = p.ask((ObjectNode) M.readTree("{\"question\":\"zebra crossings on the moon in 1740\"}"));
        assertTrue(noQ.get("filed_as_demand").asBoolean());
    }
}
