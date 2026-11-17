package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.librarian.profiles.CareerProfile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a person the keeper gave write access to can reach (0.7.8): the library, and no file of the machine it runs on. Each ask goes
 * through the real port, as that person signed in with their token, with the sign-in on. Two ways out were open in 0.7.7: a survey took
 * a file in its url field that it refused in its path field, and a plain form field named like an uploaded file's was believed to be
 * one. The keeper still surveys a file by its path, at the terminal and on the library's own pages, and a file a browser really posted
 * is still taken.
 */
class WritersReachTest {

    static final ObjectMapper M = new ObjectMapper();
    static final String FRIEND = "did:key:friend";
    /** Words of the keeper's own files, which lie outside the library: found nowhere in an answer or under the library when the door is shut. */
    static final String KEPT_BACK = "What the keeper keeps to themselves", KEPT_BACK_PROJECT = "The keeper's own project", SOMEONE_ELSES = "What another person is posting";
    static final String SURVEY_SAID = "Only the library owner can survey a file or a folder on this machine, from the terminal or the library's own pages. A url works from here.";

    Supplier<Researcher.Drive> drives;
    Boolean signIn, local;

    @BeforeEach void signInOnAndNoModel() { drives = Explain.DRIVES; Explain.DRIVES = () -> null; signIn = WebAccess.OVERRIDE; local = WebAccess.LOCAL_OVERRIDE; WebAccess.OVERRIDE = true; WebAccess.LOCAL_OVERRIDE = null; }
    @AfterEach void restore() { Explain.DRIVES = drives; WebAccess.OVERRIDE = signIn; WebAccess.LOCAL_OVERRIDE = local; Translation.DRIVE_ANSWERS = Crews::driveAnswers; Translation.ANY_MODEL = Translation::anyModelNow; }

    /** The keeper's file, outside the library. */
    static Path keepersFile(Path tmp, String name) throws IOException {
        Path f = Files.createDirectories(tmp.resolve("keepers")).resolve(name);
        Files.writeString(f, "# The keeper's own notes\n\n" + KEPT_BACK + ", line one of a file that lies outside the library. Line two of the keeper's file.\n", StandardCharsets.UTF_8);
        return f;
    }

    /** The keeper's folder, outside the library: a project with a README. */
    static Path keepersFolder(Path tmp) throws IOException {
        Path dir = Files.createDirectories(tmp.resolve("keepers").resolve("project"));
        Files.writeString(dir.resolve("README.md"), "# Project\n\n" + KEPT_BACK_PROJECT + ", whose README lies outside the library and says so at some length.\n", StandardCharsets.UTF_8);
        return dir;
    }

    /** A person with write access who is not the keeper: listed, with a token. */
    static String friend(LibraryStore store) throws IOException {
        Patrons.set(store, FRIEND, "A friend", Patrons.Level.write);
        return Patrons.issueToken(store, FRIEND);
    }

    static String enc(Object s) { return URLEncoder.encode(String.valueOf(s), StandardCharsets.UTF_8); }

    static HttpClient client() { return HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(); }

    /** A program's call, with the person's token. */
    static HttpResponse<String> call(HttpClient c, String url, String token, ObjectNode body) throws Exception {
        return c.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(120)).header("Authorization", "Bearer " + token).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(), HttpResponse.BodyHandlers.ofString());
    }

    /** A plain form, as a page without a file posts it; signed in when a token is given. */
    static HttpResponse<String> form(HttpClient c, String url, String token, String fields) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(120)).header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(fields));
        if (token != null) b.header("Cookie", Pages.COOKIE + "=" + token);
        return c.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** A form with files, as a browser posts one: the text fields, then each file under the field "file". */
    static HttpResponse<String> upload(HttpClient c, String url, String token, Map<String, String> fields, Map<String, byte[]> files) throws Exception {
        String boundary = "----rzreach";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (var f : fields.entrySet()) out.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + f.getKey() + "\"\r\n\r\n" + f.getValue() + "\r\n").getBytes(StandardCharsets.UTF_8));
        for (var f : files.entrySet()) {
            out.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + f.getKey() + "\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.writeBytes(f.getValue()); out.writeBytes("\r\n".getBytes(StandardCharsets.UTF_8));
        }
        out.writeBytes(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(120)).header("Content-Type", "multipart/form-data; boundary=" + boundary).POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray()));
        if (token != null) b.header("Cookie", Pages.COOKIE + "=" + token);
        return c.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** The files under the library that hold these words: none, when nothing of the keeper's file was let in. */
    static List<String> holding(LibraryStore store, String words) throws IOException {
        List<String> out = new ArrayList<>();
        try (Stream<Path> s = Files.walk(store.root())) {
            for (Path p : s.filter(Files::isRegularFile).toList()) if (new String(Files.readAllBytes(p), StandardCharsets.ISO_8859_1).contains(words)) out.add(store.root().relativize(p).toString());
        }
        return out;
    }

    /** The same, counting only the files not seen before: what one ask let in, told apart from what an earlier one did. */
    static List<String> let(LibraryStore store, String words, Set<String> seen) throws IOException {
        List<String> out = new ArrayList<>();
        for (String f : holding(store, words)) if (seen.add(f)) out.add(f);
        return out;
    }

    static ObjectNode json(String... pairs) { ObjectNode o = M.createObjectNode(); for (int i = 0; i + 1 < pairs.length; i += 2) o.put(pairs[i], pairs[i + 1]); return o; }

    static byte[] png() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(24, 24, BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }

    /** A survey names a thing on this machine in whatever field: the keeper's to read, and nobody else's. */
    @Test
    void aWriterSurveysNothingOnTheMachineWhateverFieldNamesIt(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Path file = keepersFile(tmp, "keepers-own.txt"), folder = keepersFolder(tmp);
        String token = friend(store);
        LibrarianDaemon d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1);
        try {
            HttpClient c = client();
            String base = "http://127.0.0.1:" + d.port();
            // the path field: refused in 0.7.7 too
            HttpResponse<String> byPath = call(c, base + "/v1/survey", token, json("path", file.toString(), "kind", "paper"));
            assertEquals(403, byPath.statusCode(), byPath.body());
            assertTrue(byPath.body().contains(SURVEY_SAID), byPath.body());
            // the same thing in the url field, as each kind and as none; a folder; a repository on disk by its .git; a file: address
            Map<String, ObjectNode> asks = new LinkedHashMap<>();
            asks.put("url=<file> kind=paper", json("url", file.toString(), "kind", "paper"));
            asks.put("url=<file>, the kind left to the library", json("url", file.toString()));
            asks.put("url=<file> kind=issues", json("url", file.toString(), "kind", "issues"));
            asks.put("url=<folder> kind=repo", json("url", folder.toString(), "kind", "repo"));
            asks.put("url=<folder>, the kind left to the library", json("url", folder.toString()));
            asks.put("url=file://<file> kind=paper", json("url", "file://" + file, "kind", "paper"));
            asks.put("path=<folder>/.git", json("path", folder.resolve(".git").toString()));
            asks.put("url=<folder>/.git kind=repo", json("url", folder.resolve(".git").toString(), "kind", "repo"));
            asks.put("op=pick url=<file>", json("op", "pick", "url", file.toString()));
            List<Executable> checks = new ArrayList<>();
            for (var ask : asks.entrySet()) {
                HttpResponse<String> r = call(c, base + "/v1/survey", token, ask.getValue());
                checks.add(() -> assertTrue(r.statusCode() == 403 && r.body().contains(SURVEY_SAID) && !r.body().contains(KEPT_BACK) && !r.body().contains(KEPT_BACK_PROJECT), "survey " + ask.getKey() + " -> " + r.statusCode() + " " + r.body()));
            }
            // library_repo is the same call under an older name
            HttpResponse<String> repo = call(c, base + "/v1/repo", token, json("url", folder.toString()));
            checks.add(() -> assertTrue(repo.statusCode() == 403 && repo.body().contains(SURVEY_SAID) && !repo.body().contains(KEPT_BACK_PROJECT), "repo url=<folder> -> " + repo.statusCode() + " " + repo.body()));
            // the same tool over the MCP route
            ObjectNode rpc = M.createObjectNode().put("jsonrpc", "2.0").put("id", 1).put("method", "tools/call");
            rpc.putObject("params").put("name", "library_survey").set("arguments", json("url", file.toString(), "kind", "paper"));
            HttpResponse<String> mcp = call(c, base + "/rpc", token, rpc);
            checks.add(() -> assertTrue(mcp.body().contains("Only the library owner can survey a file or a folder on this machine") && !mcp.body().contains(KEPT_BACK), "MCP library_survey url=<file> -> " + mcp.statusCode() + " " + mcp.body()));
            // the Read in page's box, as that person signed in: the box's words go in the url field
            HttpResponse<String> page = form(c, base + LaunchPage.PATH, token, "op=survey&kind=paper&url=" + enc(file));
            checks.add(() -> assertTrue(page.statusCode() / 100 == 3 && page.headers().firstValue("Location").orElse("").contains(enc("Only the library owner can survey a file or a folder on this machine")) && !page.body().contains("The keeper's own notes"), "the Read in page, url=<file> -> " + page.statusCode() + " " + page.headers().firstValue("Location").orElse("") + " " + page.body().replaceAll("(?s).*<main", "<main").replaceAll("\\s+", " ")));
            // and nothing of either came into the library
            List<String> in = holding(store, KEPT_BACK), inProject = holding(store, KEPT_BACK_PROJECT);
            checks.add(() -> assertTrue(in.isEmpty() && inProject.isEmpty(), "the keeper's words are under the library, in " + in + " " + inProject));
            assertAll(checks);
        } finally { d.stop(); }
    }

    /** The keeper reads a file in as before: at the terminal by its path, and on the library's own pages, whose box sends it as url. */
    @Test
    void theKeeperSurveysAFileByItsPathAtTheTerminalAndOnTheLibrarysOwnPages(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Path atTerminal = keepersFile(tmp, "terminal-notes.txt"), onPages = keepersFile(tmp, "page-notes.txt"), whileOpen = keepersFile(tmp, "open-notes.txt"), folder = keepersFolder(tmp);
        // the terminal: researchzosho survey <file>
        assertEquals(0, LibrarianCli.survey(store, new String[]{"librarian", "survey", atTerminal.toString()}));
        assertNotNull(Surveys.claimFor(store, "terminal-notes"), "the terminal's survey filed its claim");
        assertEquals(0, LibrarianCli.survey(store, new String[]{"librarian", "repo", folder.toString()}));
        assertNotNull(Surveys.claimFor(store, "project"), "and a folder's");
        LibrarianDaemon d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1);
        try {
            HttpClient c = client();
            String base = "http://127.0.0.1:" + d.port();
            // the sign-in on: a browser on the library's own computer is the keeper without signing in
            HttpResponse<String> r = form(c, base + LaunchPage.PATH, null, "op=survey&kind=&url=" + enc(onPages));
            assertEquals(200, r.statusCode(), r.body());
            assertTrue(r.body().contains("Read the document page-notes"), r.body().replaceAll("(?s).*<main", "<main"));
            assertNotNull(Surveys.claimFor(store, "page-notes"));
            // the pages open (no sign-in asked): someone in the browser may do what the keeper may
            WebAccess.OVERRIDE = false;
            r = form(c, base + LaunchPage.PATH, null, "op=survey&kind=paper&url=" + enc(whileOpen));
            assertEquals(200, r.statusCode(), r.body());
            assertTrue(r.body().contains("Read the document open-notes"), r.body().replaceAll("(?s).*<main", "<main"));
            assertNotNull(Surveys.claimFor(store, "open-notes"));
            assertFalse(holding(store, KEPT_BACK).isEmpty(), "the keeper's own survey shelves the text, as before");
        } finally { d.stop(); }
    }

    /** No field a form carries names an uploaded file: the Translate page, the chat, the Career page and the Add page read only what this server saved. */
    @Test
    void aFormFieldNamedLikeAnUploadedFileIsNotOne(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Path file = keepersFile(tmp, "keepers-own.txt"), picture = Files.write(tmp.resolve("keepers").resolve("keepers-own.png"), png());
        String token = friend(store);
        Patrons.Patron friend = new Patrons.Patron(FRIEND, "A friend", "web");
        Translation.DRIVE_ANSWERS = url -> true; Translation.ANY_MODEL = () -> true;
        LibrarianDaemon d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1);
        try {
            HttpClient c = client();
            String base = "http://127.0.0.1:" + d.port(), posed = "file.path=" + enc(file) + "&file.name=taken.txt";
            List<Executable> checks = new ArrayList<>();
            Set<String> seen = new TreeSet<>();
            // the Translate page: the form of 0.7.7's record
            HttpResponse<String> translate = form(c, base + "/translate", token, posed + "&to=fr");
            List<String> afterTranslate = let(store, KEPT_BACK, seen);
            checks.add(() -> assertTrue(translate.statusCode() == 200 && translate.body().contains("Choose a file to translate.") && afterTranslate.isEmpty(), "/translate with a field named file.path -> " + translate.statusCode() + "; the keeper's file is under the library in " + afterTranslate));
            // the chat: the same field with an ask to translate, and with an ask about a picture
            Librarian.Session s = Librarian.Session.open(store, friend);
            HttpResponse<String> chat = form(c, base + "/chat", token, "page=1&session=" + enc(s.id) + "&say=" + enc("I would like this document translated into French") + "&" + posed);
            List<String> afterChat = let(store, KEPT_BACK, seen);
            checks.add(() -> assertTrue(chat.statusCode() == 200 && afterChat.isEmpty(), "/chat, an ask to translate with a field named file.path -> " + chat.statusCode() + "; the keeper's file is under the library in " + afterChat));
            HttpResponse<String> look = form(c, base + "/chat", token, "page=1&session=" + enc(s.id) + "&say=" + enc("what is this?") + "&file.path=" + enc(picture) + "&file.name=taken.png");
            List<String> pictures = new ArrayList<>();
            if (Files.isDirectory(Images.imagesDir(store))) try (Stream<Path> kept = Files.list(Images.imagesDir(store))) { kept.forEach(p -> pictures.add(p.getFileName().toString())); }
            checks.add(() -> assertTrue(look.statusCode() == 200 && pictures.isEmpty(), "/chat, an ask about a picture with a field named file.path -> " + look.statusCode() + "; the keeper's picture is kept as " + pictures));
            // the Career page's "provide"
            HttpResponse<String> career = form(c, base + "/career", token, "op=provide&name=" + enc("Somebody Else") + "&text=&" + posed);
            List<String> afterCareer = let(store, KEPT_BACK, seen);
            checks.add(() -> assertTrue(afterCareer.isEmpty(), "/career op=provide with a field named file.path -> " + career.statusCode() + "; the keeper's file is under the library in " + afterCareer));
            // the Add page: its add refused the path in 0.7.7 too; nothing is read either way
            HttpResponse<String> add = form(c, base + "/add", token, posed);
            List<String> afterAdd = let(store, KEPT_BACK, seen);
            checks.add(() -> assertTrue(add.statusCode() == 200 && !add.body().contains("<b>Added:</b>") && afterAdd.isEmpty(), "/add with a field named file.path -> " + add.statusCode() + "; under the library in " + afterAdd));
            // a form with files whose text fields are named like a file's: the parser's own rule, through the port
            Map<String, String> fields = new LinkedHashMap<>(); fields.put("to", "fr"); fields.put("file.path", file.toString()); fields.put("file.name", "taken.txt");
            HttpResponse<String> multi = upload(c, base + "/translate", token, fields, Map.of());
            List<String> afterMulti = let(store, KEPT_BACK, seen);
            checks.add(() -> assertTrue(multi.statusCode() == 200 && multi.body().contains("Choose a file to translate.") && afterMulti.isEmpty(), "/translate, a form with files and a text field named file.path -> " + multi.statusCode() + "; under the library in " + afterMulti));
            // a file of another person's post that is being read at this moment, in the folder this server made for that post: a field
            // names that no more than any other, and the other person's folder is not removed with this form
            Path others = Files.createTempDirectory(Uploads.DIR);
            try {
                Path theirs = Files.writeString(others.resolve("theirs.txt"), SOMEONE_ELSES + ", in a post of their own that is still being read.\n", StandardCharsets.UTF_8);
                HttpResponse<String> inFlight = form(c, base + "/translate", token, "file.path=" + enc(theirs) + "&file.name=theirs.txt&to=fr");
                List<String> took = holding(store, SOMEONE_ELSES);
                boolean stays = Files.exists(theirs);
                checks.add(() -> assertTrue(inFlight.body().contains("Choose a file to translate.") && took.isEmpty() && stays, "/translate with a field naming a file in another post's upload folder -> " + inFlight.statusCode() + "; under the library in " + took + "; the other post's file is " + (stays ? "still there" : "gone")));
            } finally { Uploads.remove(others); }
            assertTrue(Files.exists(file) && Files.exists(picture), "and the keeper's files are where they were");
            assertAll(checks);
        } finally { d.stop(); }
    }

    /**
     * The same at each page's own code, handed a form that was not read from a post at all: every page that takes a file reads it
     * through {@link Uploads#files}, and a path outside a folder this server made for an upload is no file of the form's.
     */
    @Test
    void everyPageThatTakesAFileReadsOnlyWhatThisServerSaved(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Path file = keepersFile(tmp, "keepers-own.txt"), picture = Files.write(tmp.resolve("keepers").resolve("keepers-own.png"), png());
        friend(store);
        Patrons.Patron friend = new Patrons.Patron(FRIEND, "A friend", "web");
        LibraryProtocol p = new LibraryProtocol(store);
        List<Executable> checks = new ArrayList<>();
        Set<String> seen = new TreeSet<>();
        String translate = Pages.translatePost(store, friend, Map.of("file.path", file.toString(), "file.name", "taken.txt", "to", "fr"));
        List<String> afterTranslate = let(store, KEPT_BACK, seen);
        checks.add(() -> assertTrue(translate.contains("Choose a file to translate.") && afterTranslate.isEmpty(), "the Translate page: the keeper's file is under the library in " + afterTranslate));
        Librarian.Session s = Librarian.Session.open(store, friend);
        String attached = Pages.attach(store, p, friend, s, Map.of("say", "I would like this document translated into French", "file.path", file.toString(), "file.name", "taken.txt"));
        List<String> afterChat = let(store, KEPT_BACK, seen);
        checks.add(() -> assertTrue("".equals(attached) && afterChat.isEmpty(), "the chat, an ask to translate: the keeper's file is under the library in " + afterChat));
        Pages.attach(store, p, friend, s, Map.of("say", "what is this?", "file.path", picture.toString(), "file.name", "taken.png"));
        List<String> pictures = new ArrayList<>();
        if (Files.isDirectory(Images.imagesDir(store))) try (Stream<Path> kept = Files.list(Images.imagesDir(store))) { kept.forEach(x -> pictures.add(x.getFileName().toString())); }
        checks.add(() -> assertTrue(pictures.isEmpty(), "the chat, an ask about a picture: the keeper's picture is kept as " + pictures));
        Profile.Page career = new CareerProfile().page(store, friend, "/career", "POST", Map.of(), Map.of("op", "provide", "name", "Somebody Else", "text", "", "file.path", file.toString(), "file.name", "taken.txt"));
        List<String> afterCareer = let(store, KEPT_BACK, seen);
        checks.add(() -> assertTrue(afterCareer.isEmpty() && career.body() != null && career.body().contains("Write something, or choose a file."), "the Career page: the keeper's file is under the library in " + afterCareer));
        String add = Pages.addPost(store, p, friend, Map.of("file.path", file.toString(), "file.name", "taken.txt"));
        List<String> afterAdd = let(store, KEPT_BACK, seen);
        checks.add(() -> assertTrue(!add.contains("<b>Added:</b>") && afterAdd.isEmpty(), "the Add page: under the library in " + afterAdd));
        assertTrue(Files.exists(file) && Files.exists(picture), "and the keeper's files are where they were");
        assertAll(checks);
    }

    /** A file the browser posted is the person's own, whoever they are: taken by the Add page, the Translate page, the chat and the Career page as before. */
    @Test
    void aFileTheBrowserPostedIsStillTaken(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        String token = friend(store);
        Patrons.Patron friend = new Patrons.Patron(FRIEND, "A friend", "web");
        Translation.DRIVE_ANSWERS = url -> true; Translation.ANY_MODEL = () -> true;
        LibrarianDaemon d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1);
        try {
            HttpClient c = client();
            String base = "http://127.0.0.1:" + d.port();
            byte[] notes = "The Saros cycle is 223 synodic months.\n\nIt is about 18 years and 11 days.\n".repeat(6).getBytes(StandardCharsets.UTF_8);
            // the Add page
            HttpResponse<String> add = upload(c, base + "/add", token, Map.of("collection", "notes"), Map.of("saros notes.txt", notes));
            assertEquals(200, add.statusCode(), add.body());
            assertTrue(add.body().contains("<b>Added:</b> saros notes.txt"), add.body().replaceAll("(?s).*<main", "<main"));
            // the Translate page: the file kept under the library for the run, and the run filed
            HttpResponse<String> translate = upload(c, base + "/translate", token, Map.of("to", "fr", "quality", "quick"), Map.of("saros for translation.txt", notes));
            assertEquals(200, translate.statusCode(), translate.body());
            assertTrue(Files.isRegularFile(Translation.inbox(store).resolve("saros for translation.txt")), "the posted file is kept for the run");
            assertTrue(translate.body().contains("Filed as") && translate.body().contains("saros for translation.txt") && !translate.body().contains("Choose a file to translate."), translate.body().replaceAll("(?s).*<main", "<main"));
            // the chat: an ask to translate with its file, and a file sent with a question
            Librarian.Session s = Librarian.Session.open(store, friend);
            Map<String, String> turn = new LinkedHashMap<>(); turn.put("page", "1"); turn.put("session", s.id); turn.put("say", "I would like this document translated into Japanese");
            HttpResponse<String> chat = upload(c, base + "/chat", token, turn, Map.of("saros-note.txt", notes));
            assertEquals(200, chat.statusCode(), chat.body());
            assertTrue(chat.body().contains("units with words") && chat.body().contains("Into Japanese as txt"), chat.body());
            assertTrue(Files.isRegularFile(Translation.inbox(store).resolve("saros-note.txt")), "the chat's file is kept for the run");
            turn.put("say", "What do my notes say?");
            HttpResponse<String> attached = upload(c, base + "/chat", token, turn, Map.of("metonic.txt", "The Metonic cycle is 235 synodic months.\n".repeat(6).getBytes(StandardCharsets.UTF_8)));
            assertEquals(200, attached.statusCode(), attached.body());
            assertTrue(attached.body().contains("<b>@metonic.txt</b> on the shelves"), attached.body());
            // the Career page: a resume posted with the form
            HttpResponse<String> career = upload(c, base + "/career", token, Map.of("op", "provide", "name", "Somebody Else", "text", ""), Map.of("resume.txt", "Somebody Else. Twelve years as a harbour pilot at the port of Example; before that a tide-table clerk.\n".getBytes(StandardCharsets.UTF_8)));
            assertTrue(career.statusCode() / 100 == 3, "the Career page goes on to the person's page: " + career.statusCode() + " " + career.body());
            assertFalse(holding(store, "Twelve years as a harbour pilot").isEmpty(), "the posted resume is kept and read");
        } finally { d.stop(); }
    }
}
