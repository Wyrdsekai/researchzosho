package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;
import org.researchzosho.librarian.profiles.BooksProfile;
import org.researchzosho.tools.BookSearchTool;
import org.researchzosho.tools.ContentPolicy;
import org.researchzosho.tools.Books;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The books field (0.6.0): the catalogues answer by words, subject, author and NDC, a work's record is read, and only what they return may be named. */
class BooksTest {

    static final ObjectMapper M = new ObjectMapper();
    HttpServer server; Map<String, String> saved = new HashMap<>();

    @BeforeEach void stub() throws Exception {
        saved.putAll(Books.BASE);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        serve("/search.json", "application/json", "{\"numFound\":2,\"docs\":[{\"key\":\"/works/OL1W\",\"title\":\"Field Guide to the Moths of Hokkaido\",\"author_name\":[\"Kestrel Hayashi\"],\"first_publish_year\":2019,\"edition_count\":1,\"subject\":[\"Moths\",\"Hokkaido (Japan)\",\"Identification\"],\"language\":[\"eng\"],\"isbn\":[\"9780000000011\"],\"publisher\":[\"Heron Street Press\"],\"number_of_pages_median\":212},"
                + "{\"key\":\"/works/OL2W\",\"title\":\"Moths of the World\",\"author_name\":[\"A. Bramble\"],\"first_publish_year\":2001,\"edition_count\":14,\"ratings_count\":320,\"ratings_average\":4.12,\"subject\":[\"Moths\"],\"language\":[\"eng\"],\"isbn\":[\"9780000000028\"]},"
                + "{\"key\":\"/works/OL3W\",\"title\":\"Harbour Lights\",\"author_name\":[\"Pell Marrow\"],\"first_publish_year\":1998,\"edition_count\":2,\"subject\":[\"Science fiction\",\"Fiction, science fiction, space opera\"],\"language\":[\"eng\"]}]}");
        serve("/works/OL1W.json", "application/json", "{\"title\":\"Field Guide to the Moths of Hokkaido\",\"key\":\"/works/OL1W\",\"description\":{\"type\":\"/type/text\",\"value\":\"Every moth recorded on the island, with keys and plates.\"},\"subjects\":[\"Moths\",\"Hokkaido (Japan)\"],\"subject_places\":[\"Hokkaido\"],\"first_publish_date\":\"2019\"}");
        serve("/opensearch", "application/xml", "<?xml version=\"1.0\"?><rss xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:dcterms=\"http://purl.org/dc/terms/\" xmlns:dcndl=\"http://ndl.go.jp/dcndl/terms/\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" version=\"2.0\"><channel><item>"
                + "<title>北海道の蛾 図鑑</title><link>https://ndlsearch.ndl.go.jp/books/R100000002-I000000001</link><guid isPermaLink=\"true\">https://ndlsearch.ndl.go.jp/books/R100000002-I000000001</guid><dc:title>北海道の蛾 図鑑</dc:title><dc:creator>林 ケストレル</dc:creator><dc:publisher>鷺通り出版</dc:publisher><dcterms:issued>2019</dcterms:issued>"
                + "<dc:subject>蛾</dc:subject><dc:subject>北海道</dc:subject><dc:subject xsi:type=\"dcndl:NDC9\">486.8</dc:subject><dc:identifier xsi:type=\"dcndl:ISBN\">9784000000019</dc:identifier></item></channel></rss>");
        serve("/get", "application/json", "[{\"summary\":{\"isbn\":\"9784000000019\",\"title\":\"北海道の蛾\",\"volume\":\"図鑑\",\"publisher\":\"鷺通り出版\",\"pubdate\":\"201904\",\"author\":\"林,ケストレル\"},\"onix\":{\"CollateralDetail\":{\"TextContent\":[{\"TextType\":\"03\",\"Text\":\"北海道で記録された蛾を網羅。\"},{\"TextType\":\"04\",\"Text\":\"第1章 採集と同定\\n第2章 科別解説\"}]}}}]");
        serve("/cinii", "application/json", "{\"@context\":{},\"@graph\":[{\"opensearch:totalResults\":\"1\",\"items\":[{\"title\":\"北海道の蛾 図鑑\",\"@id\":\"https://ci.nii.ac.jp/ncid/BA00000001\",\"dc:creator\":\"林ケストレル著\",\"dc:publisher\":[\"鷺通り出版\"],\"dc:date\":\"2019\",\"dcterms:hasPart\":[{\"@id\":\"urn:isbn:9784000000019\"}],\"cinii:ownerCount\":37}]}]}");
        server.start();
        Books.BASE.put("openlibrary", base); Books.BASE.put("ndl", base + "/opensearch"); Books.BASE.put("openbd", base + "/get"); Books.BASE.put("cinii", base + "/cinii");
    }

    @AfterEach void restore() { server.stop(0); Books.BASE.putAll(saved); Shelf.override = null; ShelfAuthors.override = null; }

    void serve(String path, String type, String body) {
        server.createContext(path, x -> { byte[] b = body.getBytes(StandardCharsets.UTF_8); x.getResponseHeaders().set("Content-Type", type); x.sendResponseHeaders(200, b.length); try (OutputStream o = x.getResponseBody()) { o.write(b); } });
    }

    static ObjectNode args(String json) throws Exception { return (ObjectNode) M.readTree(json); }

    @Test
    void theCataloguesAnswerAndAWorkIsReadAndTheFieldJoinsOnTheQuestion() throws Exception {
        ContentPolicy run = ContentPolicy.defaults();
        BookSearchTool t = new BookSearchTool().policy(run);
        String search = t.execute(args("{\"kind\":\"subject\",\"query\":\"Moths\"}"));
        assertTrue(run.catalogue().contains("field guide to the moths of hokkaido") && run.catalogue().contains("field guide to the moths of hokkaido|hayashi") && run.catalogue().contains("/works/OL2W") && run.catalogue().contains("harbour lights|marrow") && run.catalogue().contains("moths of the world@2001"), "what the catalogue returned is recorded on the run, for the write-up's title check: " + run.catalogue());
        assertTrue(search.contains("3 book(s) in Open Library, subject \"Moths\"") && search.contains("Field Guide to the Moths of Hokkaido — Kestrel Hayashi (2019); Heron Street Press; subjects: Moths, Hokkaido (Japan), Identification; 1 edition(s); ISBN 9780000000011; 212 pages — record: ")
                && search.contains("320 ratings averaging 4.1 (a cue, not a rank)") && search.contains("only these may be recommended"), search);
        String work = t.execute(args("{\"kind\":\"work\",\"query\":\"OL1W\"}"));
        assertTrue(work.contains("Record: Field Guide to the Moths of Hokkaido (first published 2019)") && work.contains("Description: Every moth recorded on the island") && work.contains("Places: Hokkaido"), work);
        String ja = t.execute(args("{\"kind\":\"search\",\"query\":\"北海道の蛾\"}"));
        assertTrue(ja.startsWith("CiNii Books is not set up (the setting cinii_appid") && ja.contains("1 book(s) in NDL Search") && ja.contains("北海道の蛾 図鑑 — 林 ケストレル (2019); 鷺通り出版; subjects: 蛾, 北海道; ISBN 9784000000019; NDC 486.8 — record: https://ndlsearch.ndl.go.jp/books/R100000002-I000000001"), ja);
        String realHome = System.getProperty("user.home");
        try {
            System.setProperty("user.home", Files.createTempDirectory("rz-books-home").toString()); Config.invalidate(); Config.set(Books.CINII_KEY, "test-appid");
            String both = t.execute(args("{\"kind\":\"subject\",\"query\":\"蛾\"}"));
            assertTrue(both.startsWith("1 book(s) in CiNii Books, 件名 \"蛾\"") && both.contains("北海道の蛾 図鑑 — 林ケストレル著 (2019); 鷺通り出版; ISBN 9784000000019; held by 37 Japanese libraries — record: https://ci.nii.ac.jp/ncid/BA00000001") && both.contains("1 book(s) in NDL Search, 件名 \"蛾\""), both);
        } finally { System.setProperty("user.home", realHome); Config.invalidate(); }
        String openbd = t.execute(args("{\"kind\":\"work\",\"query\":\"9784000000019\"}"));
        assertTrue(openbd.contains("Record: 北海道の蛾 図鑑 (first published 201904)") && openbd.contains("Description: 北海道で記録された蛾を網羅。") && openbd.contains("目次: 第1章 採集と同定 第2章 科別解説"), openbd);
        BooksProfile p = new BooksProfile();
        assertTrue(p.register().contains("kind=shelf") && p.writerRules().contains("your shelf:") && p.criticRules().contains("fewer rows beat padding"));
        assertTrue(p.applies("Which book on information retrieval should I read first, as a beginner?"));
        assertTrue(p.applies("機械学習のおすすめの本は？"));
        assertFalse(p.applies("Which USB-C hub should I buy?"));
        assertTrue(Profiles.known().stream().anyMatch(x -> x.name().equals("books")), "registered with the other fields");
        assertTrue(p.register().contains("book_search") && p.register().contains("not named") && p.writerRules().contains("No \"best\"") && p.chatNote().contains("one question at a time"));
    }

    @Test
    void withAShelfEveryListMarksWhatIsOwnedAndTheLikedSubjectsPoolTheCatalogue(@TempDir Path tmp) throws Exception {
        Shelf.override = ShelfTest.calibre(tmp.resolve("calibre"));
        BookSearchTool t = new BookSearchTool();
        String search = t.execute(args("{\"kind\":\"search\",\"query\":\"moths\"}"));
        assertTrue(search.contains("Harbour Lights — Pell Marrow (1998); subjects: Science fiction, Fiction, science fiction, space opera; 2 edition(s) — record: ") && search.contains("OL3W — ON THE PERSON'S SHELF (rated 4.5★)"), search);
        assertFalse(search.contains("Field Guide to the Moths of Hokkaido — Kestrel Hayashi (2019); Heron Street Press; subjects: Moths, Hokkaido (Japan), Identification; 1 edition(s); ISBN 9780000000011; 212 pages — record: http://127.0.0.1:" + server.getAddress().getPort() + "/works/OL1W — ON"), "a book the shelf lacks is not marked");
        ShelfAuthors.override = tmp.resolve("authors");
        String pool = t.execute(args("{\"kind\":\"shelf\"}"));
        assertTrue(pool.startsWith("By the authors the person rates well — 2 candidate(s) from 1 author(s) on file; 1 already on the shelf left out. Only these may be recommended"), pool);
        assertTrue(pool.contains("Idris Quell (2 chosen, rated 4★):\n- Field Guide to the Moths of Hokkaido (2019); subjects: Moths, Hokkaido (Japan), Identification; 1 edition(s); fits 1: hokkaido japan — record: ") && pool.contains("\n- Moths of the World (2001); subjects: Moths; 14 edition(s) — record: "), "a one-word liked subject does not count as a fit: " + pool);
        assertTrue(pool.contains("Under the person's liked subjects (") && pool.contains("for breadth — these are the catalogue's best-known, not its tail; 1 already on the shelf left out") && !pool.contains("Harbour Lights"), pool);
        assertTrue(Files.isRegularFile(tmp.resolve("authors").resolve("idris-quell.json")), "the author's works are kept for next time");
        assertEquals("hokkaido japan", BookSearchTool.askedSubjects("Which field guide should I read next on the moths of Hokkaido (Japan)?"), "the liked subject the ask names, as the shelf spells it");
        assertEquals("", BookSearchTool.askedSubjects("What should I read next?"), "an ask that names no liked subject narrows nothing");
        String narrowed = t.execute(args("{\"kind\":\"shelf\",\"query\":\"hokkaido\"}"));
        assertTrue(narrowed.contains("1 candidate(s) from 1 author(s) on file") && narrowed.contains("narrowed to \"hokkaido\"") && narrowed.contains("Field Guide") && narrowed.indexOf("Moths of the World") > narrowed.indexOf("Under the person's liked subjects"), narrowed);
        assertTrue(ShelfAuthors.nightly(10_000).startsWith("1 author(s) the person went for; 0 read tonight, 1 on file, 0 to go"), ShelfAuthors.nightly(10_000));
        ContentPolicy runPolicy = ContentPolicy.defaults();
        String ev = new BooksProfile().runEvidence(null, new Researcher.Ask("What should I read next?", "broad", 0, List.of(), "web", List.of(), 0, List.of("books"), List.of()), runPolicy);
        assertTrue(ev.startsWith("WHAT THE SHELF SAYS TO START FROM (made by the library from the catalogues, not by a model;") && ev.contains("Idris Quell (2 chosen, rated 4★):") && ev.contains("Under the person's liked subjects"), ev);
        assertTrue(runPolicy.catalogue().contains("field guide to the moths of hokkaido|hayashi"), "the pool's books are known to the write-up's title check: " + runPolicy.catalogue());
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        ObjectNode a = M.createObjectNode(); a.put("op", "refresh"); a.put("minutes", 1); a.putObject("patron").put("did", "person").put("name", "owner").put("runtime", "cli");
        assertTrue(new LibraryProtocol(store).myShelf(a).path("summary").asText().startsWith("1 author(s) the person went for; 0 read tonight, 1 on file, 0 to go. The night housekeeping goes on from here"));
        ObjectNode v = M.createObjectNode(); v.put("op", "refresh"); v.putObject("patron").put("did", "visitor").put("name", "someone").put("runtime", "mcp");
        assertThrows(ProtocolError.class, () -> new LibraryProtocol(store).myShelf(v), "a visitor does not spend the catalogue's calls");
        ShelfAuthors.override = null;
        Shelf.override = tmp.resolve("none").resolve("metadata.db");
        assertTrue(t.execute(args("{\"kind\":\"shelf\"}")).startsWith("No Calibre library is set"));
        assertEquals("", new BooksProfile().runEvidence(null, new Researcher.Ask("What should I read next?", "broad", 0, List.of(), "web", List.of(), 0, List.of("books"), List.of()), ContentPolicy.defaults()), "no shelf, no pool");
    }
}
