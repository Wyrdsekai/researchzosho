package org.researchzosho.librarian.dna;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.*;

/** The shared pool's pack: written with its header and provenance and nothing of the person, read into a store that lacks the documents, the published one fetched when announced. */
class PoolTest {

    Path tmp;
    @BeforeEach void dirs(@TempDir Path t) { tmp = t; Dna.useDir(t.resolve("dna")); Prefs.useDir(t.resolve("prefs")); }
    @AfterEach void reset() { Dna.useDir(null); Prefs.useDir(null); Pool.site = "https://researchzosho.org/dna/"; }

    static ObjectNode doc(String key, String title, String version) throws Exception {
        ObjectNode r = Dna.M.createObjectNode(); r.put("title", title); r.put("authors", "Someone"); r.put("calibre_id", 42); r.put("added", "2024-01-01"); r.putArray("tags").add("Travel");
        ObjectNode d = Dna.fresh(Dna.BOOK, key, r); d.put("file", "/shelf/Calibre/" + title + ".epub");
        ObjectNode l = d.putObject("labelled"); l.put("version", version); l.put("windows", 12); l.put("kind", "novel"); l.putObject("mood").put("gentle", 0.5); l.putArray("topics").add("harbours"); l.putObject("book").put("audience", "adult"); l.putObject("scales"); l.putObject("signature");
        Dna.write(d); return d;
    }

    @Test void aPackCarriesTheLayersAndTheProvenanceAndNothingOfThePerson() throws Exception {
        doc("k1", "One", Labels.VERSION); doc("k2", "Two", Labels.VERSION); doc("k3", "Three", "labelled-0.0");
        Path pack = tmp.resolve("out").resolve("books.jsonl.gz");
        assertEquals(2, Pool.export(Dna.BOOK, pack, "a reader"), "the two current labelled documents; the stale one is not in the index");
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new GZIPInputStream(Files.newInputStream(pack)), StandardCharsets.UTF_8))) {
            JsonNode head = Dna.M.readTree(r.readLine());
            assertEquals(Pool.FORMAT, head.path("format").asText()); assertEquals("book", head.path("kind").asText()); assertEquals(2, head.path("count").asInt()); assertEquals("a reader", head.path("contributor").asText()); assertEquals(Labels.VERSION, head.path("labelled_version").asText());
            JsonNode d = Dna.M.readTree(r.readLine());
            assertEquals("a reader", d.path("from").path("contributor").asText());
            assertFalse(d.has("file")); assertFalse(d.path("record").has("calibre_id")); assertFalse(d.path("record").has("added"));
            assertEquals("Travel", d.path("record").path("tags").get(0).asText()); assertEquals(0.5, d.path("labelled").path("mood").path("gentle").asDouble());
        }
        // another machine: the pack read in, then read again
        Dna.useDir(tmp.resolve("dna2"));
        Pool.Loaded l = Pool.importPack(pack);
        assertEquals("2 document(s) added, 0 kept as this machine had them, 0 set aside (another prompt version), of 2 in the pack of books.", l.said());
        assertEquals(2, Dna.labelled(Dna.BOOK).size());
        assertEquals("0 document(s) added, 2 kept as this machine had them, 0 set aside (another prompt version), of 2 in the pack of books.", Pool.importPack(pack).said());
        // a stale document in a pack is set aside; a file that is not a pack is refused
        Path stale = tmp.resolve("stale.jsonl");
        Files.writeString(stale, "{\"format\":\"" + Pool.FORMAT + "\",\"kind\":\"book\"}\n" + Pool.packed(doc("k9", "Nine", "labelled-0.0"), "").toString() + "\n", StandardCharsets.UTF_8);
        Dna.useDir(tmp.resolve("dna3"));
        assertEquals("0 document(s) added, 0 kept as this machine had them, 1 set aside (another prompt version), of 1 in the pack of books.", Pool.importPack(stale).said());
        Files.writeString(tmp.resolve("junk.jsonl"), "{\"hello\":1}\n", StandardCharsets.UTF_8);
        assertEquals("not a DNA pack: the first line does not say " + Pool.FORMAT, assertThrows(IOException.class, () -> Pool.importPack(tmp.resolve("junk.jsonl"))).getMessage());
    }

    @Test void thePublishedPackIsFetchedWhenAnnouncedAndSaidToBeMissingWhenNot() throws Exception {
        doc("k1", "One", Labels.VERSION);
        Path pack = tmp.resolve("pub.jsonl"); Pool.export(Dna.BOOK, pack, "researchzosho.org");
        byte[] packBytes = Files.readAllBytes(pack);
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/dna/books.json", ex -> { byte[] b = ("{\"url\":\"http://127.0.0.1:" + s.getAddress().getPort() + "/dna/books-2026-10-11.jsonl\",\"made\":\"2026-10-11\"}").getBytes(StandardCharsets.UTF_8); ex.sendResponseHeaders(200, b.length); ex.getResponseBody().write(b); ex.close(); });
        s.createContext("/dna/books-2026-10-11.jsonl", ex -> { ex.sendResponseHeaders(200, packBytes.length); ex.getResponseBody().write(packBytes); ex.close(); });
        s.createContext("/dna/podcasts.json", ex -> { ex.sendResponseHeaders(404, -1); ex.close(); });
        s.start();
        try {
            Pool.site = "http://127.0.0.1:" + s.getAddress().getPort() + "/dna/";
            Dna.useDir(tmp.resolve("fresh"));
            assertEquals("The published pack of 2026-10-11: 1 document(s) added, 0 kept as this machine had them, 0 set aside (another prompt version), of 1 in the pack of books.", Pool.refresh(Dna.BOOK));
            assertEquals("The published pack of 2026-10-11 is already in.", Pool.refresh(Dna.BOOK));
            assertEquals("No pack is published yet for podcasts; the format is ready (" + Pool.FORMAT + ") and " + Pool.site + "podcasts.json will announce the first.", Pool.refresh(Dna.PODCAST));
        } finally { s.stop(0); }
        assertTrue(Pool.status(Dna.BOOK).startsWith("The store holds 1 book document(s), 1 labelled. Sharing is off: nothing leaves this machine"), Pool.status(Dna.BOOK));
        assertEquals("sharing is off (RESEARCHZOSHO_DNA_SHARE)", Pool.nightly(Dna.BOOK));
    }
}
