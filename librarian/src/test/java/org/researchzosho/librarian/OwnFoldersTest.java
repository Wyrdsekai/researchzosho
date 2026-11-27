package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The programs' own folders are never read for the library or for a model (2026-11-26): ResearchZosho's (its settings with the person's
 * keys, phones.json, the tokens, the model folder, the logs), CodeZaiku's ~/.codezaiku and the old ~/.codeplane. Every door is refused
 * with the sentence that names the folder (the tool as the command line, the web pages, MCP and /v1 call it; the chat on a yes and with
 * /go; the other tools that take a path), a link into a folder is refused by where it leads, and a folder that holds them (a home folder)
 * is read without them, and says so. A file the browser posted, the library's own shelves and an ordinary file go on as before.
 */
class OwnFoldersTest {
    static final ObjectMapper M = new ObjectMapper();
    /** A made-up key: the text that must never reach the shelves. */
    static final String KEY = "rz-made-up-brave-key-0123456789abcdef";
    static final String CZ_WORDS = "codezaiku-own-notes-marker", CP_WORDS = "codeplane-old-notes-marker", RZ_WORDS = "researchzosho-own-help-marker";

    @TempDir Path tmp;
    String realHome;
    Path home, rz, cz, cp, config;
    LibraryStore store;

    @BeforeEach void scratch() throws Exception {
        home = tmp.resolve("home");
        QuietHome.quiet(home);
        realHome = System.getProperty("user.home"); System.setProperty("user.home", home.toString()); Config.invalidate();
        Config.set("RESEARCHZOSHO_BRAVE_KEY", KEY);
        config = Config.userConfigPath();
        rz = Config.home();
        Files.writeString(rz.resolve("phones.json"), "{\"phones\":[],\"codes\":[]}\n");
        Files.createDirectories(rz.resolve("help")); Files.writeString(rz.resolve("help").resolve("notes.md"), "# Help\n\n" + RZ_WORDS + " and the key " + KEY + "\n");
        cz = home.resolve(".codezaiku"); Files.createDirectories(cz.resolve("model")); Files.writeString(cz.resolve("notes.md"), "# CodeZaiku\n\n" + CZ_WORDS + "\n");
        Files.writeString(cz.resolve("model").resolve("start.txt"), "the model's start script " + CZ_WORDS + "\n");
        cp = home.resolve(".codeplane"); Files.createDirectories(cp); Files.writeString(cp.resolve("old.md"), "# CodePlane\n\n" + CP_WORDS + "\n");
        Path papers = home.resolve("papers"); Files.createDirectories(papers);
        Files.writeString(papers.resolve("gears.md"), "# On gears\n\nThe Antikythera gears were cut by hand with files.\n");
        store = new LibraryStore(tmp.resolve("lib")); store.init();
        Config.set("RESEARCHZOSHO_LIBRARY", tmp.resolve("lib").toString());
        assertTrue(Files.readString(config).contains(KEY), "the settings file holds the made-up key");
    }

    @AfterEach void back() { System.setProperty("user.home", realHome); Config.invalidate(); }

    static ObjectNode person(ObjectNode a) { a.putObject("patron").put("did", "person").put("name", "keeper").put("runtime", "cli"); return a; }
    static ObjectNode path(Path p) { return person(M.createObjectNode().put("path", p.toString())); }

    /** Every file of the library's folder, as text: what the shelves hold. */
    String shelves() throws Exception {
        StringBuilder b = new StringBuilder();
        try (Stream<Path> s = Files.walk(store.root())) { for (Path p : s.filter(Files::isRegularFile).toList()) { try { b.append(new String(Files.readAllBytes(p), StandardCharsets.UTF_8)); } catch (Exception ignored) { } } }
        return b.toString();
    }

    void nothingOfThemKept() throws Exception {
        String s = shelves();
        assertFalse(s.contains(KEY), "the key is on the shelves");
        assertFalse(s.contains(CZ_WORDS) || s.contains(CP_WORDS) || s.contains(RZ_WORDS), "a file of the programs' own folders is on the shelves");
    }

    /** What a call answered or threw, as text. */
    static String said(Callable<?> call) {
        try { return String.valueOf(call.call()); } catch (Exception e) { return e.getClass().getSimpleName() + ": " + e.getMessage(); }
    }

    static void namesTheFolder(String said, Path folder, String whose) {
        assertTrue(said.contains("is inside " + folder) && said.contains("the folder of " + whose + "'s settings and keys, so the library does not read it."), said);
    }

    @Test
    void theAddToolRefusesEveryPathInsideTheProgramsOwnFoldersAndSaysWhich() throws Exception {
        LibraryProtocol p = new LibraryProtocol(store);
        for (Path f : List.of(config, rz.resolve("phones.json"), rz.resolve("help").resolve("notes.md"), rz, rz.resolve("not-there.md"), home.resolve("papers").resolve("..").resolve(".researchzosho").resolve("config"))) {
            for (String mode : List.of("keep", "link", "survey")) {
                ProtocolError e = assertThrows(ProtocolError.class, () -> p.add(path(f).put("mode", mode)), f + " " + mode);
                assertEquals("forbidden", e.code);
                namesTheFolder(e.getMessage(), rz, "ResearchZosho");
            }
        }
        namesTheFolder(said(() -> p.add(path(cz.resolve("notes.md")))), cz, "CodeZaiku");
        namesTheFolder(said(() -> p.add(path(cz))), cz, "CodeZaiku");
        namesTheFolder(said(() -> p.add(path(cp.resolve("old.md")))), cp, "CodePlane");
        // an archive kept inside the folder
        Path zip = rz.resolve("backup.zip");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(zip))) { z.putNextEntry(new ZipEntry("keys.md")); z.write(("the key " + KEY).getBytes(StandardCharsets.UTF_8)); z.closeEntry(); }
        namesTheFolder(said(() -> p.add(path(zip))), rz, "ResearchZosho");
        nothingOfThemKept();
        // the control: an ordinary file of the same home is read in
        ObjectNode ok = p.add(path(home.resolve("papers").resolve("gears.md")));
        assertEquals(1, ok.path("added").asInt(), ok.toString());
        assertTrue(shelves().contains("cut by hand with files"));
    }

    @Test
    void theOtherToolsThatTakeAPathAreRefusedToo() throws Exception {
        LibraryProtocol p = new LibraryProtocol(store);
        Path pic = rz.resolve("face.png");
        Files.write(pic, new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n', 0, 0, 0, 13, 'I', 'H', 'D', 'R', 0, 0, 0, 1, 0, 0, 0, 1, 8, 2, 0, 0, 0});
        List<String> doors = new ArrayList<>();
        doors.add(said(() -> p.translate(path(config).put("to", "ja"))));
        doors.add(said(() -> p.absorb(path(config))));
        doors.add(said(() -> p.items(path(config))));
        doors.add(said(() -> p.check(path(config))));
        doors.add(said(() -> p.bookmarks(path(config))));
        doors.add(said(() -> p.survey(person(M.createObjectNode().put("op", "survey").put("path", config.toString())))));
        doors.add(said(() -> p.survey(person(M.createObjectNode().put("op", "survey").put("url", "~/.researchzosho/config")))));   // the Read in page's box sends url
        doors.add(said(() -> p.prefs(path(config).put("op", "import"))));
        doors.add(said(() -> p.dna(path(config).put("op", "pack-import"))));
        doors.add(said(() -> p.owned(path(config).put("op", "import"))));
        doors.add(said(() -> p.career(person(M.createObjectNode().put("op", "provide").put("what", config.toString())))));
        doors.add(said(() -> Images.bytesOf(store, pic.toString(), true)));   // every picture named by its path (describe, judge, a reference) is read here
        doors.add(said(() -> TranslateTurn.resolve(store, null, Patrons.Patron.from(path(config)), config.toString())));   // a document to translate, named in the chat
        doors.add(said(() -> { Corpus.register(store, "settings", rz); return "registered"; }));   // collection add, add --register
        for (String d : doors) namesTheFolder(d, rz, "ResearchZosho");
        nothingOfThemKept();
        assertTrue(Corpus.registered(store).isEmpty(), "nothing registered");
    }

    @Test
    void aLinkIsRefusedByWhereItLeadsAndAFolderThatHoldsThemIsReadWithoutThem() throws Exception {
        LibraryProtocol p = new LibraryProtocol(store);
        Path notes = home.resolve("notes"); Files.createDirectories(notes);
        Path link = notes.resolve("settings.md"); Files.createSymbolicLink(link, config);
        namesTheFolder(said(() -> p.add(path(link))), rz, "ResearchZosho");
        Path dirLink = home.resolve("rz-link"); Files.createSymbolicLink(dirLink, rz);
        namesTheFolder(said(() -> p.add(path(dirLink))), rz, "ResearchZosho");
        // inside a folder that is read: a link to a file of the folder is left out, the folder's own files are read
        Path papers = home.resolve("papers");
        Files.createSymbolicLink(papers.resolve("help-copy.md"), rz.resolve("help").resolve("notes.md"));
        Files.writeString(papers.resolve("dials.txt"), "The front dial shows the zodiac.\n");
        ObjectNode r = p.add(path(papers));
        assertEquals(2, r.path("added").asInt(), r.toString());
        nothingOfThemKept();
        // the home folder: read without the three folders, and the answer says so
        ObjectNode survey = p.add(path(home).put("mode", "survey"));
        assertTrue(survey.path("summary").asText().contains("Left out: " + rz + ", the folder of ResearchZosho's settings and keys.")
                && survey.path("summary").asText().contains("Left out: " + cz + ", the folder of CodeZaiku's settings and keys.")
                && survey.path("summary").asText().contains("Left out: " + cp + ", the folder of CodePlane's settings and keys."), survey.toString());
        ObjectNode all = p.add(path(home));
        assertEquals(3, all.path("left_out").size(), all.toString());
        assertTrue(all.path("summary").asText().contains("Left out: " + rz), all.toString());
        assertEquals(2, all.path("seen").asInt(), "the two files of papers, and nothing of the three folders nor the links into them: " + all);
        nothingOfThemKept();
        assertTrue(shelves().contains("The front dial shows the zodiac"), "the home folder's own files are read");
    }

    @Test
    void aLinkMadeBeforeIntoTheFolderIsNotReadAgain() throws Exception {
        // a capture an earlier version linked to the settings file: its head points there, and nothing of the file is read now
        Path raw = store.rawDir().resolve("2026-11-01-0123456789ab.md");
        Files.createDirectories(raw.getParent());
        Files.writeString(raw, "---\nurl: file://" + config + "\ntitle: config\nfetched_at: 2026-11-01T00:00:00Z\nfetched_by: researchzosho-add\nlinked: " + config + "\nsha256: 0\nbytes: 1\n---\n");
        String text = RawCapture.read(raw)[2];
        assertTrue(text.startsWith("[not read: ") && text.contains("is inside " + rz), text);
        assertFalse(text.contains(KEY));
    }

    @Test
    void theChatIsRefusedOnAYesAndWithGo() throws Exception {
        ObjectNode add = LibrarianChatTest.tool("library_add", "{\"path\":\"" + config + "\"}");
        List<ArrayNode> seen = new ArrayList<>();
        String go = GoAheadTest.typed(new Librarian(store, GoAheadTest.model(List.of(add, LibrarianChatTest.say("Read in.")), seen), Librarian.person(), Librarian.Session.open(store)), "/go read in " + config);
        assertFalse(go.contains("Done without asking"), go);
        namesTheFolder(lastTool(seen), rz, "ResearchZosho");   // what the model was told, and so says
        seen.clear();
        Librarian l = new Librarian(store, GoAheadTest.model(List.of(add, LibrarianChatTest.say("Read in.")), seen), Librarian.person(), Librarian.Session.open(store));
        String asked = l.say("please read my settings file in: " + config);
        assertTrue(asked.contains("Do it?"), asked);
        String yes = l.say("yes");
        assertTrue(yes.contains("is inside " + rz) || lastTool(seen).contains("is inside " + rz), yes + " / " + lastTool(seen));
        nothingOfThemKept();
        // the control: the same chat reads an ordinary file in
        Path gears = home.resolve("papers").resolve("gears.md");
        String ok = GoAheadTest.typed(GoAheadTest.lib(store, Librarian.Session.open(store), List.of(LibrarianChatTest.tool("library_add", "{\"path\":\"" + gears + "\"}"), LibrarianChatTest.say("Read in."))), "/go read in " + gears);
        assertTrue(ok.contains("Done without asking"), ok);
        assertTrue(shelves().contains("cut by hand with files"));
    }

    /** The last tool result the stand-in model was sent. */
    static String lastTool(List<ArrayNode> seen) {
        String out = "";
        for (ArrayNode messages : seen) for (JsonNode m : messages) if ("tool".equals(m.path("role").asText())) out = m.path("content").asText("");
        return out;
    }

    record Ran(int code, String out, String err) { }

    static Ran cli(String... words) {
        String[] args = new String[words.length + 1]; args[0] = "researchzosho"; System.arraycopy(words, 0, args, 1, words.length);
        PrintStream o = System.out, e = System.err;
        ByteArrayOutputStream bo = new ByteArrayOutputStream(), be = new ByteArrayOutputStream();
        System.setOut(new PrintStream(bo, true, StandardCharsets.UTF_8)); System.setErr(new PrintStream(be, true, StandardCharsets.UTF_8));
        try { return new Ran(LibrarianCli.run(args, "http://127.0.0.1:1", "m"), bo.toString(StandardCharsets.UTF_8), be.toString(StandardCharsets.UTF_8)); }
        finally { System.setOut(o); System.setErr(e); }
    }

    @Test
    void theCommandLineIsRefusedAndReadsAHomeFolderWithoutThem() throws Exception {
        for (List<String> words : List.of(List.of("add", config.toString()), List.of("add", config.toString(), "--link"), List.of("add", config.toString(), "--for", "https://example.org/page"),
                List.of("add", rz.toString()), List.of("collection", "add", "settings", rz.toString()))) {
            Ran r = cli(words.toArray(String[]::new));
            assertEquals(2, r.code(), words + ": " + r.out() + r.err());
            namesTheFolder(r.err(), rz, "ResearchZosho");
        }
        Ran folder = cli("add", home.resolve("papers").toString());
        assertEquals(0, folder.code(), folder.err());
        Ran homeFolder = cli("add", home.toString(), "--survey");
        assertTrue(homeFolder.out().contains("Left out: " + rz) && homeFolder.out().contains("Left out: " + cz), homeFolder.out());
        nothingOfThemKept();
    }

    @Test
    void mcpAndV1AndTheWebFormAreRefused() throws Exception {
        Boolean signIn = WebAccess.OVERRIDE, local = WebAccess.LOCAL_OVERRIDE;
        LibrarianDaemon d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1);
        try {
            String base = "http://127.0.0.1:" + d.port();
            ApiKeys.Minted key = ApiKeys.mint(store, Accounts.OWNER, "a script of the owner's", Patrons.Level.write);
            HttpClient c = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
            Path gears = home.resolve("papers").resolve("gears.md");
            // /v1: the control first, then the settings file
            HttpResponse<String> ok = c.send(HttpRequest.newBuilder(URI.create(base + "/v1/add")).header("Authorization", "Bearer " + key.secret()).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(M.createObjectNode().put("path", gears.toString()).toString())).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, ok.statusCode(), ok.body());
            HttpResponse<String> v1 = c.send(HttpRequest.newBuilder(URI.create(base + "/v1/add")).header("Authorization", "Bearer " + key.secret()).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(M.createObjectNode().put("path", config.toString()).toString())).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(403, v1.statusCode(), v1.body());
            namesTheFolder(v1.body(), rz, "ResearchZosho");
            // MCP over the library's own address
            String call = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"library_add\",\"arguments\":{\"path\":" + M.writeValueAsString(config.toString()) + "}}}";
            HttpResponse<String> rpc = c.send(HttpRequest.newBuilder(URI.create(base + "/rpc")).header("Authorization", "Bearer " + key.secret()).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(call)).build(), HttpResponse.BodyHandlers.ofString());
            namesTheFolder(rpc.body(), rz, "ResearchZosho");
            // the Read in page's box, on this machine's own browser
            WebAccess.OVERRIDE = Boolean.TRUE; WebAccess.LOCAL_OVERRIDE = true;
            HttpResponse<String> page = c.send(HttpRequest.newBuilder(URI.create(base + "/launch")).header("Content-Type", "application/x-www-form-urlencoded").header("Sec-Fetch-Site", "same-origin")
                    .POST(HttpRequest.BodyPublishers.ofString(PhoneStandIns.form("op", "survey", "url", config.toString()))).build(), HttpResponse.BodyHandlers.ofString());
            String note = URLDecoder.decode(page.headers().firstValue("Location").orElse(""), StandardCharsets.UTF_8);   // the page's answer, said on the page it goes back to
            assertEquals(303, page.statusCode());
            namesTheFolder(note, rz, "ResearchZosho");
            nothingOfThemKept();
        } finally { d.stop(); WebAccess.OVERRIDE = signIn; WebAccess.LOCAL_OVERRIDE = local; }
    }

    @Test
    void aFileTheBrowserPostedAndTheLibrarysOwnShelvesGoOnAsBefore() throws Exception {
        LibraryProtocol p = new LibraryProtocol(store);
        Path up = Uploads.newFolder();
        try {
            Path posted = up.resolve("posted.md"); Files.writeString(posted, "# Posted\n\nA file the browser posted, about the Saros dial.\n");
            assertEquals(1, p.add(path(posted)).path("added").asInt());
        } finally { Uploads.drop(Map.of("file.path", up.resolve("posted.md").toString())); }
        // a library kept inside the program's own folder (an account service's, under accounts/): its own files are its own
        LibraryStore inside = new LibraryStore(rz.resolve("accounts").resolve("lib-1")); inside.init();
        Path own = inside.root().resolve("catalog").resolve("translations").resolve("in"); Files.createDirectories(own);
        Files.writeString(own.resolve("copy.md"), "# A copy the library made\n\nIts own words.\n");
        assertNotNull(Corpus.addFile(inside, own.resolve("copy.md"), "", false), "the library's own file is not refused");
        assertEquals(null, OwnFolders.holding(own.resolve("copy.md"), inside));
        assertNotNull(OwnFolders.holding(config, inside), "while the settings beside it still are");
    }
}
