package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.tools.Fetch;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Reading material in from the chat: a folder kept (text copied) or linked (read in place, nothing copied),
 * a dry pass that counts first, a url fetched and kept, and who may hand the library a path.
 */
class LibraryAddTest {

    private static final ObjectMapper M = new ObjectMapper();

    static LibraryStore library(Path home) throws Exception {
        LibraryStore store = new LibraryStore(home.resolve("lib")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        return store;
    }

    static Path docs(Path home) throws Exception {
        Path docs = home.resolve("drive").resolve("papers"); Files.createDirectories(docs.resolve("sub"));
        Files.writeString(docs.resolve("gears.md"), "# On gears\n\nThe Antikythera gears were cut by hand with files; the teeth are triangular.\n");
        Files.writeString(docs.resolve("sub").resolve("dials.txt"), "The front dial shows the zodiac and the Egyptian calendar; the back dials show the Metonic and Saros cycles.\n");
        return docs;
    }

    static ObjectNode person(ObjectNode args) {
        args.putObject("patron").put("did", "person").put("name", "keeper").put("runtime", "cli");
        return args;
    }

    @Test
    void aLinkedFolderIsReadInPlaceAndNothingIsCopied(@TempDir Path home) throws Exception {
        LibraryStore store = library(home);
        Path docs = docs(home);
        Corpus.Outcome o = Corpus.addFolder(store, docs, "papers", true, true);
        assertEquals(2, o.added()); assertEquals(0, o.skipped());
        Path raw = RawCapture.find(store, "file://" + docs.resolve("gears.md").toAbsolutePath().normalize());
        assertNotNull(raw);
        String onDisk = Files.readString(raw);
        assertTrue(onDisk.contains("linked: " + docs.resolve("gears.md").toAbsolutePath().normalize()), onDisk);
        assertTrue(onDisk.contains("sha256: ") && onDisk.contains("bytes: "), onDisk);
        assertFalse(onDisk.contains("triangular"), "the text is not in the capture");
        assertEquals("papers", RawCapture.collectionOf(raw));
        // reading the capture reads the file
        String[] r = RawCapture.read(raw);
        assertTrue(r[2].contains("teeth are triangular"), r[2]);
        assertEquals("gears", r[1], "the file name, since a markdown file has no title field");
        // and the index knows it, so the shelves find it
        assertFalse(new LibrarianIndex(store, Embeddings.none()).searchIn("metonic saros cycles", 5, null, "papers").isEmpty(), "linked files are searchable");
        // a second pass compares hashes and touches nothing
        Corpus.Outcome again = Corpus.addFolder(store, docs, "papers", true, true);
        assertEquals(0, again.added()); assertEquals(2, again.unchanged()); assertEquals(0, again.changed());
    }

    @Test
    void anUnmountedDriveSaysSoWhenRead(@TempDir Path home) throws Exception {
        LibraryStore store = library(home);
        Path docs = docs(home);
        Corpus.addFolder(store, docs, "papers", true, true);
        Path raw = RawCapture.find(store, "file://" + docs.resolve("gears.md").toAbsolutePath().normalize());
        // the drive goes away
        Files.delete(docs.resolve("gears.md"));
        String body = RawCapture.read(raw)[2];
        assertTrue(body.startsWith("[not reachable now: ") && body.contains("gears.md") && body.contains("mount the drive"), body);
        // a rebuild while it is away keeps the entry findable by its title, without the marker as its text
        LibrarianIndex idx = new LibrarianIndex(store, Embeddings.none());
        idx.rebuild();
        assertFalse(idx.hasChunks(raw.getFileName().toString()), "no text to index while the drive is away");
        for (var h : idx.searchIn("not reachable now mount the drive", 5, null, "papers")) assertFalse(h.snippet().contains("not reachable"), "the marker is not indexed as text: " + h);
        // it comes back, with the same bytes: read again, no stale cache; the next rescan reads it into the index
        Files.writeString(docs.resolve("gears.md"), "# On gears\n\nThe Antikythera gears were cut by hand with files; the teeth are triangular.\n");
        assertTrue(RawCapture.read(raw)[2].contains("triangular"));
        Corpus.Outcome back = Corpus.addFolder(store, docs, "papers", true, true);
        assertEquals(2, back.unchanged());
        assertTrue(idx.hasChunks(raw.getFileName().toString()), "read into the index once the drive is back");
        assertFalse(idx.searchIn("gears cut by hand triangular", 5, null, "papers").isEmpty());
        // the rescan of a registered linked folder that is not mounted says so
        Corpus.register(store, "papers", docs, true);
        assertEquals(java.util.Set.of("papers"), Corpus.linked(store));
        assertEquals(docs.toAbsolutePath().normalize(), Corpus.registered(store).get("papers"));
        Path parent = docs;
        Files.walk(parent).sorted(java.util.Comparator.reverseOrder()).forEach(p -> { try { Files.delete(p); } catch (Exception e) { throw new RuntimeException(e); } });
        assertTrue(Corpus.rescan(store).contains("papers: folder not reachable now"), Corpus.rescan(store));
    }

    @Test
    void aChangedLinkedFileMarksTheClaimsRestingOnItForReview(@TempDir Path home) throws Exception {
        LibraryStore store = library(home);
        Path docs = docs(home);
        Corpus.addFolder(store, docs, "papers", true, true);
        String locator = "file://" + docs.resolve("gears.md").toAbsolutePath().normalize();
        Finding f = new Finding("F-0001-gears", "Gears cut by hand", List.of(), Finding.State.accepted, Finding.ClaimType.extraction, Finding.Confidence.high,
                "person", "2026-09-14T00:00:00Z", "2026-09-14", Finding.Volatility.stable, "",
                List.of(new Finding.Source(locator, "n/a", "the note says so")), List.of(), null, "The gears were cut by hand.\n");
        Finding approved = new Finding(f.id(), f.title(), f.subjects(), f.state(), f.claimType(), f.confidence(), f.writer(), f.recordedAt(), f.validAsOf(),
                f.volatility(), f.reviewBy(), f.sources(), f.supersedes(), new Finding.Review(1, "librarian:test", "accepted", f.contentHash(), "t"), f.body());
        store.write(approved);
        assertFalse(store.finding("F-0001-gears").reviewStale());
        // the file changes on the drive
        Files.writeString(docs.resolve("gears.md"), "# On gears\n\nThe Antikythera gears were cast, not cut; the teeth are square.\n");
        Corpus.Outcome o = Corpus.addFolder(store, docs, "papers", true, true);
        assertEquals(1, o.changed(), o.toString()); assertEquals(1, o.unchanged());
        Finding after = store.finding("F-0001-gears");
        assertTrue(after.reviewStale(), "the approval no longer covers the source");
        assertTrue(after.sources().get(0).edition().startsWith("sha256:"), after.sources().get(0).edition());
        assertTrue(RawCapture.read(RawCapture.find(store, locator))[2].contains("teeth are square"), "the new text is what is read");
        assertFalse(new LibrarianIndex(store, Embeddings.none()).searchIn("gears cast square", 5, null, "papers").isEmpty(), "re-indexed");
    }

    @Test
    void theSurveyCountsWithoutShelving(@TempDir Path home) throws Exception {
        LibraryStore store = library(home);
        Path docs = docs(home);
        Corpus.Survey sv = Corpus.survey(store, docs, true);
        assertEquals(2, sv.files());
        assertEquals(Files.size(docs.resolve("gears.md")) + Files.size(docs.resolve("sub").resolve("dials.txt")), sv.bytes());
        assertTrue(sv.textBytes() > 0 && sv.textBytes() <= sv.bytes() + 20, "text is about the size of the files: " + sv.textBytes());
        assertEquals(1, sv.byType().get("md")); assertEquals(1, sv.byType().get("txt"));
        assertTrue(sv.freeBytes() > 0, "the disk's free space is read");
        assertTrue(sv.line().contains("2 document(s)") && sv.line().contains("free"), sv.line());
        try (var s = Files.list(store.rawDir())) { assertEquals(0, s.count(), "nothing shelved"); }
        // through the protocol: survey then keep
        LibraryProtocol p = new LibraryProtocol(store);
        ObjectNode r = p.add(person(M.createObjectNode().put("path", docs.toString()).put("mode", "survey")));
        assertEquals("folder", r.path("kind").asText()); assertEquals(2, r.path("files").asInt()); assertEquals("papers", r.path("collection").asText());
        assertTrue(r.path("next").asText().contains("mode=keep") && r.path("next").asText().contains("mode=link"));
        ObjectNode kept = p.add(person(M.createObjectNode().put("path", docs.toString()).put("mode", "keep").put("register", true)));
        assertEquals(2, kept.path("added").asInt()); assertTrue(kept.path("registered").asBoolean());
        assertTrue(kept.path("next").asText().contains("collections: [\"papers\"]"), kept.path("next").asText());
        assertTrue(Files.readString(RawCapture.find(store, "file://" + docs.resolve("gears.md").toAbsolutePath().normalize())).contains("triangular"), "kept = the text is on the shelves");
        assertEquals(java.util.Set.of(), Corpus.linked(store));
    }

    @Test
    void keepAfterLinkWritesTheTextOverThePointerAndBack(@TempDir Path home) throws Exception {
        LibraryStore store = library(home);
        Path docs = docs(home);
        Corpus.addFolder(store, docs, "papers", true, true);
        String locator = "file://" + docs.resolve("gears.md").toAbsolutePath().normalize();
        assertNotNull(RawCapture.linkedFile(RawCapture.find(store, locator)));
        Corpus.Outcome kept = Corpus.addFolder(store, docs, "papers", true, false);
        assertEquals(2, kept.added(), "keep after link is a change, not 'unchanged': " + kept);
        Path raw = RawCapture.find(store, locator);
        assertNull(RawCapture.linkedFile(raw));
        assertTrue(Files.readString(raw).contains("triangular"), "the text is on the shelves now");
        assertEquals(2, Corpus.addFolder(store, docs, "papers", true, false).unchanged());
        // and back to a link
        assertEquals(2, Corpus.addFolder(store, docs, "papers", true, true).added());
        assertNotNull(RawCapture.linkedFile(RawCapture.find(store, locator)));
        assertFalse(Files.readString(RawCapture.find(store, locator)).contains("triangular"));
    }

    @Test
    void aPathIsTheKeepersToGiveAUrlAnyWriterMay(@TempDir Path home) throws Exception {
        LibraryStore store = library(home);
        Path docs = docs(home);
        LibraryProtocol p = new LibraryProtocol(store);
        ObjectNode stranger = M.createObjectNode().put("path", docs.toString());
        stranger.putObject("patron").put("did", "did:key:zStranger").put("name", "s").put("runtime", "mcp");
        ProtocolError e = assertThrows(ProtocolError.class, () -> p.add(stranger));
        assertTrue(e.getMessage().contains("keeper"), e.getMessage());
        assertThrows(ProtocolError.class, () -> p.add(person(M.createObjectNode())), "a path or a url is needed");
        assertThrows(ProtocolError.class, () -> p.add(person(M.createObjectNode().put("path", docs.toString()).put("mode", "copy"))), "mode is keep, link or survey");
        ProtocolError missing = assertThrows(ProtocolError.class, () -> p.add(person(M.createObjectNode().put("path", home.resolve("nfs").resolve("share").toString()))));
        assertTrue(missing.getMessage().contains("mounted"), missing.getMessage());
        // a single file, linked
        ObjectNode one = p.add(person(M.createObjectNode().put("path", docs.resolve("gears.md").toString()).put("mode", "link")));
        assertEquals("file", one.path("kind").asText()); assertEquals("gears", one.path("title").asText());
        assertNotNull(RawCapture.linkedFile(store.rawDir().resolve(one.path("raw").asText())));
        // a url, fetched and kept
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var f = Fetch.class.getDeclaredField("allowLoopback"); f.setAccessible(true); f.set(null, true);
        try {
            s.createContext("/", x -> {
                byte[] b = "<html><head><title>The Saros dial</title><meta property=\"article:published_time\" content=\"2024-03-02\"></head><body><p>The back dial counts 223 lunar months, the Saros period, and predicts eclipses.</p></body></html>".getBytes(StandardCharsets.UTF_8);
                x.getResponseHeaders().set("Content-Type", "text/html");
                x.sendResponseHeaders(200, b.length);
                try (var o = x.getResponseBody()) { o.write(b); }
            });
            s.start();
            String url = "http://127.0.0.1:" + s.getAddress().getPort() + "/saros";
            ObjectNode got = p.add(stranger.deepCopy().put("url", url).remove(List.of("path")));
            assertEquals("url", got.path("kind").asText()); assertEquals("The Saros dial", got.path("title").asText());
            Path raw = store.rawDir().resolve(got.path("raw").asText());
            assertTrue(Files.readString(raw).contains("223 lunar months"), "kept");
            assertEquals("2024-03-02", RawCapture.published(raw));
            ProtocolError link = assertThrows(ProtocolError.class, () -> p.add(person(M.createObjectNode().put("url", url).put("mode", "link"))));
            assertTrue(link.getMessage().contains("always kept"), link.getMessage());
        } finally { s.stop(0); f.set(null, false); }
    }

    @Test
    void theChatCanReadMaterialIn() throws Exception {
        assertTrue(Librarian.TOOLS.contains("library_add"));
        boolean listed = false;
        for (var t : Librarian.tools()) if ("library_add".equals(t.path("function").path("name").asText())) { listed = true; assertTrue(t.path("function").path("description").asText().contains("survey")); }
        assertTrue(listed, "the chat offers it");
        boolean served = false;
        for (var t : org.researchzosho.mcp.McpServer.allTools()) if ("library_add".equals(t.path("name").asText())) served = true;
        assertTrue(served, "the MCP server offers it");
    }
}
