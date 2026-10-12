package org.researchzosho.librarian.dna;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.tools.Books;

import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The record layer filled from the catalogue: by ISBN first, then by title and author; a miss remembered; the labelled documents first. */
class RecordFillTest {

    String was;
    @AfterEach void reset() { Dna.useDir(null); if (was != null) Books.BASE.put("openlibrary", was); }

    static ObjectNode doc(String key, String title, String authors, String isbn, boolean labelled) throws Exception {
        ObjectNode r = Dna.M.createObjectNode(); r.put("title", title); r.put("authors", authors); r.put("isbn", isbn); r.putArray("tags").add("General"); r.putObject("sources").put("calibre", "2026-10-11T00:00:00Z");
        ObjectNode d = Dna.fresh(Dna.BOOK, key, r);
        if (labelled) { ObjectNode l = d.putObject("labelled"); l.put("version", Labels.VERSION); l.put("windows", 12); l.put("kind", "novel"); l.putObject("mood"); l.putArray("topics"); l.putObject("book"); l.putObject("scales"); l.putObject("signature"); }
        Dna.write(d); return d;
    }

    @Test void theCatalogueFillsTheRecordByIsbnThenByTitleAndRemembersAMiss(@TempDir Path tmp) throws Exception {
        Dna.useDir(tmp.resolve("dna"));
        List<String> asked = new ArrayList<>();
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/search.json", ex -> {
            String q = URLDecoder.decode(ex.getRequestURI().getRawQuery(), StandardCharsets.UTF_8); asked.add(q);
            String body;
            if (q.contains("q=isbn:9780000000011")) body = "{\"docs\":[{\"key\":\"/works/OL1W\",\"title\":\"The Salt Road\",\"author_name\":[\"Tamsin Vorle\"],\"first_publish_year\":2019,\"edition_count\":7,\"ratings_count\":12,\"ratings_average\":3.9,\"subject\":[\"Walking\",\"Hokkaido (Japan)\",\"Travel\"],\"isbn\":[\"9780000000011\"]}]}";
            else if (q.contains("q=Moths of the Northern Islands") && q.contains("author=Idris Quell")) body = "{\"docs\":[{\"key\":\"/works/OL2W\",\"title\":\"Moths of the Northern Islands: A Field Guide\",\"author_name\":[\"I. Quell\"],\"first_publish_year\":2015,\"edition_count\":2,\"ratings_count\":0,\"subject\":[\"Moths\",\"Natural history\"]},{\"key\":\"/works/OL9W\",\"title\":\"Something Else\",\"author_name\":[\"Nobody\"]}]}";
            else body = "{\"docs\":[]}";
            byte[] b = body.getBytes(StandardCharsets.UTF_8); ex.sendResponseHeaders(200, b.length); ex.getResponseBody().write(b); ex.close();
        });
        s.start();
        was = Books.BASE.get("openlibrary");
        Books.BASE.put("openlibrary", "http://127.0.0.1:" + s.getAddress().getPort());
        try {
            doc("k1", "The Salt Road: A Walk Across Hokkaido", "Tamsin Vorle", "9780000000011", false);
            doc("k2", "Moths of the Northern Islands", "Idris Quell", "", true);
            doc("k3", "A Grammar of Lost Rivers", "Pell Marrow", "", false);
            String said = RecordFill.nightly(60_000, () -> false);
            assertEquals("2 of the 3 documents carry the catalogue's record; tonight 2 filled, 1 not in the catalogue", said);
            assertTrue(asked.get(0).contains("Moths"), "the labelled document is asked for first: " + asked);
            ObjectNode k1 = Dna.read(Dna.BOOK, "k1");
            assertEquals(List.of("Walking", "Hokkaido (Japan)", "Travel"), List.of(k1.path("record").path("subjects").get(0).asText(), k1.path("record").path("subjects").get(1).asText(), k1.path("record").path("subjects").get(2).asText()));
            assertEquals("/works/OL1W", k1.path("record").path("ol_key").asText()); assertEquals(7, k1.path("record").path("edition_count").asInt()); assertEquals("3.9", k1.path("record").path("ratings_average").asText()); assertEquals("2019", k1.path("record").path("first_published").asText());
            assertTrue(k1.path("record").path("sources").has("openlibrary"));
            ObjectNode k2 = Dna.read(Dna.BOOK, "k2");
            assertEquals("Moths", k2.path("record").path("subjects").get(0).asText(), "found by title and author when there is no ISBN; the subtitle on the catalogue's side does not hide it");
            ObjectNode k3 = Dna.read(Dna.BOOK, "k3");
            assertFalse(k3.path("record").has("subjects")); assertTrue(k3.path("record").path("sources").has("openlibrary_miss"));
            assertTrue(RecordFill.filled(k3), "a miss stands for a month");
            int n = asked.size();
            assertEquals("2 of the 3 documents carry the catalogue's record; tonight 0 filled, 0 not in the catalogue", RecordFill.nightly(60_000, () -> false));
            assertEquals(n, asked.size(), "nothing is asked twice");
            // the subjects now count in "like X"
            assertTrue(LikeX.subjectTokens(k1).contains("hokkaido"));
        } finally { s.stop(0); }
    }
}
