package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * An index and the embedder that built it, when another takes its place (0.7.4). The embedder's name holds its address, and a refresh
 * took any other name for another model: a server that moved, to the machine that serves the helpers or to another port, had the
 * whole index built again with no word of it, and one that did not answer at that moment had it built again with no vectors at all.
 */
class IndexEmbedderMovedTest {
    static final ObjectMapper M = new ObjectMapper();

    /** A text's vector under a model: one seed is one model, whatever serves it; two seeds are two models. */
    static float[] vector(String text, int seed) {
        float[] v = new float[24];
        for (int i = 0; i < text.length(); i++) for (int k = 0; k < v.length; k++) v[k] += (int) (((text.charAt(i) + 1L) * 2654435761L + seed * 40503L * (k + 1) + k * 7919L) % 7) - 3;
        double n = 0; for (float x : v) n += x * x;
        n = Math.sqrt(n);
        if (n > 0) for (int k = 0; k < v.length; k++) v[k] /= (float) n;
        return v;
    }

    /** A stand-in embedder under a name; it keeps what it was asked to embed, and can be made silent. */
    static final class Stub implements Embeddings.Embedder {
        final String id; final int seed; boolean answers = true; final List<String> asked = new ArrayList<>();
        Stub(String id, int seed) { this.id = id; this.seed = seed; }
        @Override public float[] embed(String text) { if (!answers) return null; asked.add(text); return vector(text, seed); }
        @Override public String modelId() { return id; }
        long entriesEmbedded() { return asked.stream().filter(t -> !t.equals(LibrarianIndex.PROBE_TEXT)).count(); }
    }

    private static Finding f(String id, String title, String body) {
        return new Finding(id, title, List.of(), Finding.State.accepted, Finding.ClaimType.extraction, Finding.Confidence.high,
                "person", Instant.now().toString(), "2026-09-05", Finding.Volatility.stable, "",
                List.of(new Finding.Source("https://example.org/" + id, "n/a", "s")), List.of(), null, body);
    }

    static LibraryStore shelves(Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp); store.init();
        store.write(f("F-0001-a", "Alpha claim", "Alpha body about gears.\n"));
        store.write(f("F-0002-b", "Beta claim", "Beta body about tablets.\n"));
        return store;
    }

    @Test void twoSeedsAreTwoModelsToTheProbe() {
        float[] a = vector(LibrarianIndex.PROBE_TEXT, 1), b = vector(LibrarianIndex.PROBE_TEXT, 2);
        double dot = 0; for (int i = 0; i < a.length; i++) dot += a[i] * b[i];
        assertTrue(dot < 0.9, "the stand-ins must differ for the tests below to mean anything: " + dot);
    }

    @Test void anEmbedderThatMovedAndGivesTheSameVectorsKeepsTheIndex(@TempDir Path tmp) throws Exception {
        LibraryStore store = shelves(tmp);
        Stub first = new Stub("embed@http://homebox:30001", 1);
        assertEquals(2, new LibrarianIndex(store, first).rebuild());
        assertEquals(2, first.entriesEmbedded());
        assertTrue(Files.exists(new LibrarianIndex(store, first).probeFile()), "the probe's vector is kept beside the index");
        Stub moved = new Stub("embed@http://homebox:8217/embed", 1);   // the same model, now from the machine that serves the helpers
        LibrarianIndex idx = new LibrarianIndex(store, moved);
        List<String> said = new ArrayList<>();
        assertEquals(0, idx.refresh(said::add), "nothing changed on disk: nothing is indexed again");
        assertEquals(0, moved.entriesEmbedded(), "no entry was embedded again: " + moved.asked);
        assertEquals(1, said.size(), said.toString());
        assertTrue(said.get(0).contains("moved from http://homebox:30001 to http://homebox:8217/embed and give the same vectors"), said.get(0));
        assertEquals("embed@http://homebox:8217/embed", idx.indexedWith(), "the index is now this embedder's");
        assertEquals(0, idx.refresh(said::add));
        assertEquals(1, said.size(), "and the next refresh has nothing to say");
    }

    @Test void anotherModelBuildsTheIndexAgainAndSaysSoFirst(@TempDir Path tmp) throws Exception {
        LibraryStore store = shelves(tmp);
        new LibrarianIndex(store, new Stub("embed@http://homebox:30001", 1)).rebuild();
        Stub other = new Stub("embed@http://homebox:30002", 2);
        LibrarianIndex idx = new LibrarianIndex(store, other);
        List<String> said = new ArrayList<>();
        long[] embeddedWhenSaid = {-1};
        assertEquals(2, idx.refresh(line -> { said.add(line); embeddedWhenSaid[0] = other.entriesEmbedded(); }), "every entry, again");
        assertEquals(2, other.entriesEmbedded());
        assertEquals(0, embeddedWhenSaid[0], "said before the work, not after it");
        assertTrue(said.get(0).contains("gives different vectors (another model): every entry is indexed again"), said.get(0));
        assertEquals("embed@http://homebox:30002", idx.indexedWith());
        // and it is this model's index from here on: the same model at another address keeps it
        Stub again = new Stub("embed@http://homebox:8217/embed", 2);
        assertEquals(0, new LibrarianIndex(store, again).refresh());
        assertEquals(0, again.entriesEmbedded());
    }

    @Test void aServerThatDoesNotAnswerLeavesTheIndexItsVectorsAndItsNameAlone(@TempDir Path tmp) throws Exception {
        LibraryStore store = shelves(tmp);
        new LibrarianIndex(store, new Stub("embed@http://homebox:30001", 1)).rebuild();
        Stub away = new Stub("embed@http://homebox:8217/embed", 1); away.answers = false;   // named, and off at this moment
        LibrarianIndex idx = new LibrarianIndex(store, away);
        // one entry changes on disk meanwhile
        Path fa = store.findingsDir().resolve("F-0001-a.md");
        Thread.sleep(20);
        Files.writeString(fa, Files.readString(fa).replace("about gears", "about Antikythera gears"), StandardCharsets.UTF_8);
        Files.setLastModifiedTime(fa, FileTime.from(Instant.now()));
        Thread.sleep(20);
        assertEquals(1, idx.refresh(), "what changed is indexed; the rest is left as it is");
        assertTrue(idx.said().contains("http://homebox:8217/embed, does not answer, so the index stays as http://homebox:30001 built it"), idx.said());
        assertEquals("embed@http://homebox:30001", idx.indexedWith(), "it is still the first embedder's index: nothing names the silent one as its builder");
        assertFalse(idx.search("Antikythera", 5).isEmpty(), "the edit is found by its words");
        // the server answers again, the same model: the index is kept, as for any embedder that moved
        away.answers = true; away.asked.clear();
        assertEquals(0, idx.refresh());
        assertTrue(idx.said().contains("give the same vectors"), idx.said());
        assertEquals(0, away.entriesEmbedded());
        assertEquals("embed@http://homebox:8217/embed", idx.indexedWith());
    }

    @Test void noEmbedderSetLeavesTheVectorsWhereTheyAre(@TempDir Path tmp) throws Exception {
        LibraryStore store = shelves(tmp);
        Stub first = new Stub("embed@http://homebox:30001", 1);
        new LibrarianIndex(store, first).rebuild();
        LibrarianIndex none = new LibrarianIndex(store, Embeddings.none());
        assertEquals(0, none.refresh(), "a refresh never builds the index again to take its vectors out");
        assertTrue(none.said().contains("keeps the vectors it was built with (http://homebox:30001)"), none.said());
        assertEquals("embed@http://homebox:30001", none.indexedWith());
        // the embedder set again: the index is its own, with nothing to say and nothing embedded again
        first.asked.clear();
        LibrarianIndex back = new LibrarianIndex(store, first);
        assertEquals(0, back.refresh());
        assertEquals("", back.said());
        assertEquals(0, first.entriesEmbedded());
    }

    /** An OpenAI-style embeddings server that answers with {@link #vector} under a seed, and keeps what it was asked. */
    static HttpServer server(int seed, List<String> asked) throws Exception {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/v1/embeddings", x -> {
            JsonNode in = M.readTree(x.getRequestBody().readAllBytes()).path("input");
            List<String> texts = new ArrayList<>();
            if (in.isArray()) in.forEach(t -> texts.add(t.asText())); else texts.add(in.asText());
            asked.addAll(texts);
            ObjectNode o = M.createObjectNode(); ArrayNode data = o.putArray("data");
            for (int i = 0; i < texts.size(); i++) { ObjectNode d = data.addObject(); d.put("index", i); ArrayNode e = d.putArray("embedding"); for (float v : vector(texts.get(i), seed)) e.add(v); }
            byte[] b = o.toString().getBytes(StandardCharsets.UTF_8);
            x.getResponseHeaders().set("Content-Type", "application/json");
            x.sendResponseHeaders(200, b.length);
            try (OutputStream os = x.getResponseBody()) { os.write(b); }
        });
        s.start();
        return s;
    }

    @Test void anIndexBuiltBeforeTheProbeWasKeptAsksTheAddressThatBuiltIt(@TempDir Path tmp) throws Exception {
        LibraryStore store = shelves(tmp);
        List<String> asked = new CopyOnWriteArrayList<>();
        HttpServer old = server(1, asked);
        String address = "http://127.0.0.1:" + old.getAddress().getPort();
        try {
            LibrarianIndex built = new LibrarianIndex(store, Embeddings.http(address, "embed"));
            assertEquals(2, built.rebuild());
            Files.delete(built.probeFile());   // as an index of a release before 0.7.4 is: its builder's name, and no probe
            asked.clear();
            Stub moved = new Stub("embed@http://homebox:8217/embed", 1);
            LibrarianIndex idx = new LibrarianIndex(store, moved);
            assertEquals(0, idx.refresh());
            assertEquals(List.of(LibrarianIndex.PROBE_TEXT), asked, "the address that built it was asked for the one sentence");
            assertTrue(idx.said().contains("moved from " + address + " to http://homebox:8217/embed and give the same vectors"), idx.said());
            assertEquals(0, moved.entriesEmbedded());
            assertTrue(Files.exists(idx.probeFile()), "and the probe is kept from here on");
        } finally { old.stop(0); }
    }

    @Test void anOldIndexWhoseBuilderNoLongerAnswersIsBuiltAgainAndSaysWhy(@TempDir Path tmp) throws Exception {
        LibraryStore store = shelves(tmp);
        HttpServer old = server(1, new CopyOnWriteArrayList<>());
        String address = "http://127.0.0.1:" + old.getAddress().getPort();
        LibrarianIndex built = new LibrarianIndex(store, Embeddings.http(address, "embed"));
        try { built.rebuild(); } finally { old.stop(0); }
        Files.delete(built.probeFile());
        Stub set = new Stub("embed@http://homebox:8217/embed", 1);
        LibrarianIndex idx = new LibrarianIndex(store, set);
        assertEquals(2, idx.refresh(), "nothing tells whether it is the same model: every entry, again");
        assertTrue(idx.said().contains("cannot be compared with it (the old one no longer answers): every entry is indexed again"), idx.said());
        assertEquals(2, set.entriesEmbedded());
    }
}
