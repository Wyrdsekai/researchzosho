package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.mcp.McpServer;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Who may see a library is decided by access alone: the patrons file says who may read, who may write, and who keeps it.
 * Anyone who may read sees everything in it, the living in a family as much as anybody else. What changes the library needs
 * write access, and a conversation belongs to whoever had it.
 */
class ReaderAccessTest {

    private static final ObjectMapper M = new ObjectMapper();
    private static final String OWNER = "\"patron\":{\"did\":\"person\"}";

    private static ObjectNode args(String json) throws Exception { return (ObjectNode) M.readTree(json); }

    private static List<String> ids(JsonNode array) { List<String> out = new ArrayList<>(); for (JsonNode n : array) out.add(n.path("id").asText()); return out; }

    private static List<String> jobIds(JsonNode array) { List<String> out = new ArrayList<>(); for (JsonNode n : array) out.add(n.path("job_id").asText()); return out; }

    private static Finding claim(LibraryStore store, String s, String p, String o, String line) throws Exception { return FamilyLivingRuleTest.drafted(store, s, p, o, line); }

    /** A great-grandfather who died in 1920, his son with no dates, and the son's daughter, born 1990. */
    record Shelf(LibraryStore store, Finding died, Finding marisBirth, Finding marisHome, Finding sonOf) { }

    static Shelf shelf(Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Finding died = claim(store, "Genzaburo Endo", "died-on", "1920", "Genzaburo Endo died in 1920.");
        Finding sonOf = claim(store, "Genzaburo Endo", "parent-of", "Isamu Endo", "Genzaburo Endo was a parent of Isamu Endo.");
        claim(store, "Isamu Endo", "parent-of", "Mari Endo", "Isamu Endo was a parent of Mari Endo.");
        Finding birth = claim(store, "Mari Endo", "born-on", "1990", "Mari Endo was born in 1990.");
        Finding home = claim(store, "Mari Endo", "lived-in", "Sendai", "Mari Endo lived in Sendai (2015).");
        Graph.setKind(store, "Genzaburo Endo", "person");
        Graph.setKind(store, "Isamu Endo", "person");
        Graph.setKind(store, "Mari Endo", "person");
        Graph.setKind(store, "Sendai", "place");
        store.write(new Investigation("I-0001-where-does-the-family-live-now", "Where does the family live now?", Finding.State.draft, "model:test",
                Instant.now().toString(), List.of(home.id()), List.of(), "## Answer\n\nMari Endo teaches at a school in Sendai.\n"));
        store.write(new Investigation("I-0002-where-was-genzaburo-endo-buried", "Where was Genzaburo Endo buried?", Finding.State.accepted, "model:test",
                Instant.now().toString(), List.of(died.id()), List.of(), "## Answer\n\nGenzaburo Endo was buried in the family grave at Sendai.\n"));
        RawCapture.capture(store, "file:///family/told-20260901.md", "Genzaburo Endo was a rice merchant in Sendai. His grandson's family still keeps the shop.", "What the family told", "corpus:family", "family");
        store.frontier("gap", "Where did Mari Endo study, and when?");
        store.frontier("gap", "Where is Genzaburo Endo's grave in Sendai?");
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        Patrons.setDefault(store, Patrons.Level.read);   // somebody else may read the library now
        return new Shelf(store, died, birth, home, sonOf);
    }

    /** A finished research run, as the first ledger layout kept it: the ledger sorts it into its month when it is next read. */
    private static void ran(LibraryStore store, String id, String question, String result) throws Exception {
        Path dir = store.root().resolve("catalog").resolve("jobs");
        Files.createDirectories(dir);
        ObjectNode j = M.createObjectNode();
        j.put("job_id", id); j.put("kind", "research"); j.put("patron", "");
        j.putObject("args").put("question", question);
        j.put("state", "done"); j.put("queued_at", "2026-09-20T10:00:00Z"); j.put("started_at", "2026-09-20T10:00:05Z"); j.put("ended_at", "2026-09-20T10:30:00Z");
        j.put("result", result); j.put("restarted", 0);
        Files.writeString(dir.resolve(id + ".json"), j.toString(), StandardCharsets.UTF_8);
    }

    @Test
    void aReaderSeesEverythingTheOwnerSees(@TempDir Path tmp) throws Exception {
        Shelf s = shelf(tmp);
        ran(s.store(), "J-001", "Where did Mari Endo study?", "investigation I-0001-where-does-the-family-live-now");
        LibraryProtocol p = new LibraryProtocol(s.store());
        Patrons.set(s.store(), "did:key:reader", "a cousin", Patrons.Level.read);
        Patrons.Patron cousin = new Patrons.Patron("did:key:reader", "a cousin", "web");

        // the protocol: search, one entry, the saved family text, the inbox, the open questions, the changes, the map and the runs
        List<String> hits = ids(p.search(args("{\"query\":\"Endo Sendai\",\"k\":50}")).path("hits"));
        assertEquals(ids(p.search(args("{\"query\":\"Endo Sendai\",\"k\":50," + OWNER + "}")).path("hits")), hits, "a reader finds what the owner finds");
        assertTrue(hits.contains(s.marisHome().id()) && hits.contains("I-0001-where-does-the-family-live-now"), hits.toString());
        assertEquals(s.marisHome().id(), p.get(args("{\"id\":\"" + s.marisHome().id() + "\"}")).path("entry").path("id").asText());
        assertTrue(p.read(args("{\"locator\":\"file:///family/told-20260901.md\"}")).path("text").asText().contains("rice merchant"), "the family's own text too");
        assertTrue(ids(p.inbox(args("{\"op\":\"list\"}")).path("items")).contains(s.marisBirth().id()));
        assertTrue(p.frontier(args("{\"op\":\"list\"}")).path("questions").toString().contains("Mari Endo"));
        assertTrue(p.changes(args("{}")).path("changes").toString().contains(s.marisBirth().id()));
        assertTrue(p.map(args("{\"focus\":\"Genzaburo Endo\",\"depth\":2}")).path("nodes").toString().contains("Isamu"));
        assertFalse(p.map(args("{\"focus\":\"Mari Endo\"}")).path("node").toString().contains("\"private\""), "a node carries no private mark");
        assertEquals(List.of("J-001"), jobIds(p.job(args("{\"limit\":50}")).path("finished")));
        ObjectNode asked = p.ask(args("{\"question\":\"Where does Mari Endo live in Sendai?\",\"peers\":\"none\"}"));
        assertTrue(asked.path("rendered").asText().contains("Mari Endo lived in Sendai"), asked.path("rendered").asText());

        // the pages, for a browser that has signed in as a reader: the tree draws the living, and names everybody
        assertTrue(Pages.entry(s.store(), p, cousin, s.marisHome().id()).contains("Sendai"));
        assertTrue(Pages.inbox(s.store(), p, cousin, Map.of()).contains("Mari"));
        assertTrue(Pages.changes(s.store(), p, cousin, Map.of()).contains(s.marisBirth().id()));
        String tree = FamilyPages.treeBody(s.store(), "Genzaburo Endo");
        assertTrue(tree.contains("Isamu Endo"), tree);
        String names = FamilyPages.treeBody(s.store(), "");
        assertTrue(names.contains("Mari Endo") && !names.contains("not shown to readers"), names);
        assertTrue(FamilyTree.around(s.store(), "Genzaburo Endo", 3, 3).people().stream().anyMatch(x -> x.label().contains("Isamu")));
        String out = Gedcom.export(s.store(), "Genzaburo Endo");
        assertTrue(out.contains("1 NAME Isamu /Endo/") && out.contains("1 NAME Mari /Endo/") && !out.contains("RESN") && !out.contains("1 NAME Living"), "the export writes everyone in full: " + out);
    }

    @Test
    void aReadersUnansweredQuestionIsFiledAsDemandLikeAnyOther(@TempDir Path tmp) throws Exception {
        Shelf s = shelf(tmp);
        Patrons.set(s.store(), "did:key:reader", "a cousin", Patrons.Level.read);
        LibraryProtocol p = new LibraryProtocol(s.store());
        ObjectNode r = p.ask(args("{\"question\":\"Which steamship crossed from Yokohama to Honolulu in 1899?\",\"peers\":\"none\",\"patron\":{\"did\":\"did:key:reader\"}}"));
        assertTrue(r.path("holds_nothing").asBoolean(), r.toString());
        assertTrue(r.path("filed_as_demand").asBoolean(), "a reader is told the question was filed: " + r);
        String open = p.frontier(args("{\"op\":\"list\"}")).path("questions").toString();
        assertTrue(open.contains("Which steamship crossed from Yokohama"), "and it is an open question like any other: " + open);
    }

    private static HttpResponse<String> http(HttpClient c, String url, String token, String rpc) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url));
        if (token != null) b.header("Authorization", "Bearer " + token);
        if (rpc != null) b.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(rpc));
        return c.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void overHttpAReaderWithATokenSeesWhatTheOwnerSeesAndADeniedCallerNothing(@TempDir Path tmp) throws Exception {
        Shelf s = shelf(tmp);
        ran(s.store(), "J-001", "Where did Mari Endo study?", "investigation I-0001-where-does-the-family-live-now");
        Patrons.set(s.store(), "did:key:reader", "a cousin", Patrons.Level.read);
        Patrons.set(s.store(), "did:key:gone", "a former partner", Patrons.Level.deny);
        String cousin = Patrons.issueToken(s.store(), "did:key:reader"), gone = Patrons.issueToken(s.store(), "did:key:gone");
        LibrarianDaemon d = LibrarianDaemon.start(s.store(), "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1);
        HttpClient c = HttpClient.newHttpClient();
        String base = "http://127.0.0.1:" + d.port();
        try {
            HttpResponse<String> job = http(c, base + "/v1/jobs/J-001", cousin, null);
            assertTrue(job.statusCode() == 200 && job.body().contains("Where did Mari Endo study?"), job.body());
            HttpResponse<String> noId = http(c, base + "/v1/jobs/", cousin, null);
            assertEquals(404, noId.statusCode(), noId.body());
            assertTrue(noId.body().contains("A research run is asked for by its id"), noId.body());
            String home = s.marisHome().id(), told = URLEncoder.encode("raw://file:///family/told-20260901.md", StandardCharsets.UTF_8);
            String listed = http(c, base + "/v1/resources", cousin, null).body();
            assertTrue(listed.contains(home) && listed.contains("family/told"), listed);
            assertEquals(200, http(c, base + "/v1/resource?uri=finding://" + home, cousin, null).statusCode());
            assertTrue(http(c, base + "/v1/resource?uri=" + told, cousin, null).body().contains("rice merchant"));
            String read = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"resources/read\",\"params\":{\"uri\":\"finding://" + home + "\"}}";
            assertTrue(http(c, base + "/rpc", cousin, read).body().contains("Mari Endo lived in Sendai"), "over MCP too");
            HttpResponse<String> got = http(c, base + "/download?id=" + home + "&as=md", cousin, null);
            assertTrue(got.statusCode() == 200 && got.body().contains("Mari Endo lived in Sendai"), got.body());

            // a caller the owner denied reads nothing
            assertEquals(403, http(c, base + "/v1/jobs/J-001", gone, null).statusCode());
            assertEquals(403, http(c, base + "/v1/jobs/", gone, null).statusCode());
            assertEquals(403, http(c, base + "/v1/resources", gone, null).statusCode());
            assertTrue(http(c, base + "/rpc", gone, read).body().contains("forbidden"));
        } finally { d.stop(); }
    }

    private static void say(Librarian.Session s, String words) throws Exception {
        ObjectNode m = M.createObjectNode(); m.put("role", "user"); m.put("content", words);
        s.append(m);
    }

    @Test
    void aReaderTalksInConversationsOfTheirOwnAndNeverOpensTheOwners(@TempDir Path tmp) throws Exception {
        Shelf s = shelf(tmp);
        Librarian.Session mine = Librarian.Session.open(s.store());
        say(mine, "Where does Mari Endo teach now?");
        Patrons.set(s.store(), "did:key:reader", "a cousin", Patrons.Level.read);
        Patrons.set(s.store(), "did:key:other", "a neighbour", Patrons.Level.read);
        Patrons.Patron cousin = new Patrons.Patron("did:key:reader", "a cousin", "web"), neighbour = new Patrons.Patron("did:key:other", "a neighbour", "web");

        // the owner's latest conversation is not a reader's latest, and its id does not open it for them
        assertFalse(Pages.chat(s.store(), cousin, Map.of()).contains("Mari"), "a reader does not land in the owner's conversation");
        assertFalse(Pages.chat(s.store(), cousin, Map.of("session", mine.id)).contains("Mari"), "nor open it by its id");
        assertNull(Librarian.Session.resume(s.store(), mine.id, cousin));
        assertEquals("", Pages.chatRuns(s.store(), new LibraryProtocol(s.store()), cousin, mine.id));
        assertTrue(Pages.chat(s.store(), Patrons.Patron.PERSON, Map.of()).contains("Where does Mari Endo teach now?"), "the owner's page opens it");

        // the reader's own conversation is kept apart and found again, by them only
        Librarian.Session theirs = Librarian.Session.latest(s.store(), cousin);
        assertNotEquals(mine.file, theirs.file);
        say(theirs, "Where was Genzaburo Endo buried?");
        assertTrue(Pages.chat(s.store(), cousin, Map.of()).contains("Where was Genzaburo Endo buried?"));
        assertEquals(List.of(theirs.id), Librarian.Session.list(s.store(), cousin));
        assertFalse(Pages.chat(s.store(), Patrons.Patron.PERSON, Map.of()).contains("Genzaburo Endo buried"), "the owner's latest is still the owner's own");
        assertEquals(List.of(mine.id), Librarian.Session.list(s.store()), "and the owner's list holds the owner's conversations only");
        assertFalse(Pages.chat(s.store(), neighbour, Map.of("session", theirs.id)).contains("Genzaburo Endo buried"), "one reader never opens another's");

        // a browser that has not signed in, when the sign-in is on, cannot be told apart from any other: it has no conversation to open
        WebAccess.OVERRIDE = Boolean.TRUE;
        try {
            String page = Pages.chat(s.store(), Patrons.Patron.WEB, Map.of());
            assertTrue(page.contains("Sign in to talk with the Librarian") && !page.contains("Mari") && !page.contains("Genzaburo Endo buried"), page);
            ProtocolError e = assertThrows(ProtocolError.class, () -> Pages.chatPost(null, s.store(), Patrons.Patron.WEB, Map.of("say", "hello")));
            assertEquals("forbidden", e.code);
        } finally { WebAccess.OVERRIDE = null; }
    }

    @Test
    void theFamilysDecisionsWhoIsWhoAndThePicturesTranscriptsAreForThoseWhoMayWrite(@TempDir Path tmp) throws Exception {
        Shelf s = shelf(tmp);
        Patrons.set(s.store(), "did:key:reader", "a cousin", Patrons.Level.read);
        Patrons.set(s.store(), "did:key:me", "the owner", Patrons.Level.write);
        String cousin = Pages.COOKIE + "=" + Patrons.issueToken(s.store(), "did:key:reader"), me = Pages.COOKIE + "=" + Patrons.issueToken(s.store(), "did:key:me");
        Patrons.Patron reader = new Patrons.Patron("did:key:reader", "a cousin", "web");

        // the pages: the decisions and who is who act on the library, so they need write access
        LibrarianDaemon d = LibrarianDaemon.start(s.store(), "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1);
        HttpClient c = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        String base = "http://127.0.0.1:" + d.port();
        try {
            assertEquals(403, PagesTest.get(c, base + "/decide", cousin).statusCode());
            assertEquals(403, PagesTest.post(c, base + "/decide", "kind=pair&code=x&do=two", cousin).statusCode());
            assertEquals(403, PagesTest.get(c, base + "/who", cousin).statusCode());
            assertEquals(403, PagesTest.post(c, base + "/who", "person=" + Pages.enc("Mari Endo") + "&do=none", cousin).statusCode());
            assertEquals(200, PagesTest.get(c, base + "/decide", me).statusCode());
            assertEquals(200, PagesTest.get(c, base + "/who", me).statusCode());
        } finally { d.stop(); }

        // library_who, over the protocol and in a reader's chat
        LibraryProtocol p = new LibraryProtocol(s.store());
        ProtocolError e = assertThrows(ProtocolError.class, () -> p.who(args("{\"op\":\"list\",\"patron\":{\"did\":\"did:key:reader\"}}")));
        assertEquals("forbidden", e.code);
        assertEquals("list", p.who(args("{\"op\":\"list\"," + OWNER + "}")).path("op").asText());
        String inChat = new Librarian(s.store(), null, reader, Librarian.Session.open(s.store(), reader)).call("library_who", args("{\"op\":\"list\"}"));
        assertTrue(inChat.startsWith("ERROR:") && inChat.contains("may not write"), inChat);

        // a picture's transcript is read and corrected at the command line only: no page and no tool shows it
        assertFalse(Pages.isPage("/transcript") || Pages.isPage("/transcripts"));
        for (JsonNode t : McpServer.allTools()) assertFalse(t.path("name").asText().contains("transcript"), t.path("name").asText());
    }

    @Test
    void asShippedEveryCallerMayWriteAndSeesWhatTheOwnerSees(@TempDir Path tmp) throws Exception {
        Shelf s = shelf(tmp);
        Patrons.setDefault(s.store(), Patrons.Level.write);
        List<String> hits = ids(new LibraryProtocol(s.store()).search(args("{\"query\":\"Endo Sendai\",\"k\":50}")).path("hits"));
        assertTrue(hits.contains(s.marisHome().id()), "a library nobody else reads is unchanged for its one user: " + hits);
    }
}
