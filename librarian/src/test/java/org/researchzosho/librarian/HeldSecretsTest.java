package org.researchzosho.librarian;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;
import org.researchzosho.drive.DriveClient;
import org.researchzosho.tools.Fetch;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A document that holds the exact value of a secret this library keeps is not kept (2026-11-26), wherever it comes from: a file anywhere
 * on the disk, each file of an archive, a page from the web, a note or a claim. The refusal names the setting the secret belongs to and
 * never the value: no answer, no log line and no kept file holds the made-up key's text. A value shorter than sixteen characters is not
 * matched, and a key the library does not hold is not this layer's to find.
 */
class HeldSecretsTest {
    static final ObjectMapper M = new ObjectMapper();
    /** Made-up secrets, one for each place the library keeps one. */
    static final String BRAVE = "rz-made-up-brave-0123456789abcdef", HOST = "rz-made-up-host-key-fedcba9876543210", MCP = "rz-made-up-mcp-key-00112233445566",
            TOKEN = "rz-made-up-connect-token-a1b2c3d4e5", DB_PASSWORD = "rz-made-up-db-password-778899", HELPERS = "rz-made-up-helpers-secret-5566778899",
            SHORT = "rzshort12345", NOT_HELD = "rz-made-up-key-nobody-keeps-998877";
    static final List<String> ALL = List.of(BRAVE, HOST, MCP, TOKEN, DB_PASSWORD, HELPERS);

    @TempDir Path tmp;
    String realHome;
    Path home, docs;
    LibraryStore store;
    LibraryProtocol p;
    final List<String> answers = new ArrayList<>();
    final ListAppender<ILoggingEvent> logged = new ListAppender<>();
    final Logger root = (Logger) LoggerFactory.getLogger("ROOT");

    @BeforeEach void scratch() throws Exception {
        home = tmp.resolve("home");
        QuietHome.quiet(home);
        realHome = System.getProperty("user.home"); System.setProperty("user.home", home.toString()); Config.invalidate();
        Config.set("RESEARCHZOSHO_BRAVE_KEY", BRAVE);
        Config.set(DriveClient.hostKeySetting("models.example.org"), HOST);
        Config.set(McpServers.KEY_PREFIX + "NOTES_KEY", MCP);
        Config.set(Remote.TOKEN, TOKEN);
        Config.set("RESEARCHZOSHO_CENSUS_KEY", SHORT);   // a secret too short to match
        Files.writeString(Databases.file(), "{\"orders\":{\"kind\":\"postgres\",\"url\":\"postgres://reader:" + DB_PASSWORD + "@db.example.org/orders\",\"added_at\":\"2026-11-26T00:00:00Z\",\"hide\":[]}}\n");
        Files.writeString(HelpersInstall.secretFile(), HELPERS + "\n");
        docs = home.resolve("docs"); Files.createDirectories(docs);
        store = new LibraryStore(tmp.resolve("lib")); store.init();
        Config.set("RESEARCHZOSHO_LIBRARY", tmp.resolve("lib").toString());
        p = new LibraryProtocol(store);
        logged.start(); root.addAppender(logged);
    }

    @AfterEach void back() { root.detachAppender(logged); System.setProperty("user.home", realHome); Config.invalidate(); }

    static ObjectNode person(ObjectNode a) { a.putObject("patron").put("did", "person").put("name", "keeper").put("runtime", "cli"); return a; }

    Path file(String name, String text) throws Exception { Path f = docs.resolve(name); Files.writeString(f, text); return f; }

    /** What a call answered or threw, as text, kept for the check that no answer holds a key. */
    String said(Callable<?> call) {
        String s;
        try { s = String.valueOf(call.call()); } catch (Exception e) { s = e.getClass().getSimpleName() + ": " + e.getMessage(); }
        answers.add(s);
        return s;
    }

    String shelves() throws Exception {
        StringBuilder b = new StringBuilder();
        try (Stream<Path> s = Files.walk(store.root())) { for (Path f : s.filter(Files::isRegularFile).toList()) { try { b.append(new String(Files.readAllBytes(f), StandardCharsets.UTF_8)); } catch (Exception ignored) { } } }
        return b.toString();
    }

    @Test
    void eachHeldSecretInAnUnrelatedFileIsRefusedByTheNameItIsKeptUnder() throws Exception {
        Map<String, String> names = Map.of(BRAVE, "the setting brave_key", HOST, "the setting key_models_example_org", MCP, "the setting mcp_notes_key",
                TOKEN, "the setting token", DB_PASSWORD, "the password of the database orders", HELPERS, "the setting helpers_secret");
        for (var e : names.entrySet()) {
            Path f = file("notes-" + e.getValue().replaceAll("\\W+", "-") + ".md", "# My notes\n\nThe value is " + e.getKey() + " and nothing else.\n");
            String keep = said(() -> p.add(person(M.createObjectNode().put("path", f.toString()))));
            String link = said(() -> p.add(person(M.createObjectNode().put("path", f.toString()).put("mode", "link"))));
            for (String s : List.of(keep, link)) {
                assertTrue(s.contains("ProtocolError") && s.contains("so it is not kept"), s);
                String name = e.getValue().startsWith("the password") ? "the password of the database orders" : "the value of the setting " + e.getValue().substring("the setting ".length());
                assertTrue(s.contains(name), e.getValue() + ": " + s);
            }
            assertEquals(names.get(e.getKey()), HeldSecrets.in("x " + e.getKey() + " y").toString());
        }
        // the control: the same file without the key is kept
        assertEquals(1, p.add(person(M.createObjectNode().put("path", file("clean.md", "# My notes\n\nNothing secret here, the Saros dial.\n").toString()))).path("added").asInt());
        // a folder: the file with a key is left out and said, the others kept
        Path folder = docs.resolve("folder"); Files.createDirectories(folder);
        Files.writeString(folder.resolve("a.md"), "# A\n\nThe front dial.\n"); Files.writeString(folder.resolve("b.md"), "# B\n\nkey: " + BRAVE + "\n");
        ObjectNode r = p.add(person(M.createObjectNode().put("path", folder.toString())));
        answers.add(r.toString());
        assertEquals(1, r.path("added").asInt(), r.toString());
        assertTrue(r.toString().contains("b.md: This document holds the value of the setting brave_key"), r.toString());
        // the command line
        Ran cli = cli("add", file("cli.md", "the key " + BRAVE).toString());
        answers.add(cli.out() + cli.err());
        assertEquals(1, cli.code());
        assertTrue(cli.err().contains("the setting brave_key"), cli.err());
        noKeyAnywhere();
        assertTrue(shelves().contains("Nothing secret here") && shelves().contains("The front dial"));
    }

    @Test
    void anArchiveAPageANoteAndAClaimAreCheckedToo() throws Exception {
        Path zip = docs.resolve("papers.zip");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(zip))) {
            z.putNextEntry(new ZipEntry("fine.md")); z.write("# Fine\n\nThe Metonic cycle.\n".getBytes(StandardCharsets.UTF_8)); z.closeEntry();
            z.putNextEntry(new ZipEntry("keys.md")); z.write(("# Keys\n\n" + HOST + "\n").getBytes(StandardCharsets.UTF_8)); z.closeEntry();
        }
        String archive = said(() -> p.add(person(M.createObjectNode().put("path", zip.toString()))));
        assertTrue(archive.contains("keys.md: This document holds the value of the setting key_models_example_org"), archive);
        assertTrue(shelves().contains("The Metonic cycle"), "the archive's other file is kept");
        // a page, from a stand-in server on the loopback
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        Field loop = Fetch.class.getDeclaredField("allowLoopback"); loop.setAccessible(true); loop.set(null, true);
        try {
            s.createContext("/", x -> {
                String body = x.getRequestURI().getPath().endsWith("keys") ? "<html><head><title>A pasted config</title></head><body><p>My settings: brave_key=" + BRAVE + " and more words about the page.</p></body></html>"
                        : "<html><head><title>The Saros dial</title></head><body><p>The back dial counts 223 lunar months and predicts eclipses.</p></body></html>";
                byte[] b = body.getBytes(StandardCharsets.UTF_8);
                x.getResponseHeaders().set("Content-Type", "text/html"); x.sendResponseHeaders(200, b.length);
                try (var o = x.getResponseBody()) { o.write(b); }
            });
            s.start();
            String base = "http://127.0.0.1:" + s.getAddress().getPort();
            String page = said(() -> p.add(person(M.createObjectNode().put("url", base + "/keys"))));
            assertTrue(page.contains("ProtocolError") && page.contains("the setting brave_key"), page);
            String ok = said(() -> p.add(person(M.createObjectNode().put("url", base + "/saros"))));
            assertTrue(ok.contains("\"added\":1"), ok);
        } finally { s.stop(0); loop.set(null, false); }
        // a note told to the library, and a claim submitted
        String told = said(() -> p.tell(person(M.createObjectNode().put("subject", "my settings").put("text", "The model key is " + TOKEN + ", keep it."))));
        assertTrue(told.contains("the setting token"), told);
        String claim = said(() -> p.submit(person(M.createObjectNode().put("title", "A claim").put("claim", "The password is " + DB_PASSWORD + ".")).set("sources", M.createArrayNode().add(M.createObjectNode().put("locator", "https://example.org/x")))));
        assertTrue(claim.contains("the password of the database orders"), claim);
        noKeyAnywhere();
        assertTrue(shelves().contains("223 lunar months"), "the page without a key is kept");
    }

    @Test
    void aShortValueAndAKeyTheLibraryDoesNotHoldAreNotThisLayersToRefuse() throws Exception {
        assertTrue(SHORT.length() < HeldSecrets.LEAST && BRAVE.length() >= HeldSecrets.LEAST);
        assertNull(HeldSecrets.in("census key " + SHORT), "too short to match: a word, a year or a number in ordinary text could be it");
        assertEquals(1, p.add(person(M.createObjectNode().put("path", file("short.md", "# Census\n\nThe census key " + SHORT + " is short.\n").toString()))).path("added").asInt());
        assertEquals(1, p.add(person(M.createObjectNode().put("path", file("other.md", "# Somebody's key\n\n" + NOT_HELD + "\n").toString()))).path("added").asInt());
        assertTrue(shelves().contains(NOT_HELD), "a key the library does not hold is kept: matching by the look of a key is not this layer's");
        // a held value inside a longer word still matches: it is the exact copy that counts, not where it stands
        assertNotNull(HeldSecrets.in("prefix" + BRAVE + "suffix"));
        // what is held, by name, never by value
        List<String> held = new ArrayList<>(); for (HeldSecrets.Held h : HeldSecrets.all()) held.add(h.toString());
        assertTrue(held.containsAll(List.of("the setting brave_key", "the setting key_models_example_org", "the setting mcp_notes_key", "the setting token", "the password of the database orders", "the setting helpers_secret")), held.toString());
        assertFalse(held.toString().contains("census"), "the short one is not held for matching");
        for (String v : ALL) assertFalse(held.toString().contains(v));
    }

    @Test
    void aLinkedFileThatComesToHoldAKeyIsNotReadAgain() throws Exception {
        Path f = file("linked.md", "# Linked\n\nThe Saros dial, read in place.\n");
        ObjectNode r = p.add(person(M.createObjectNode().put("path", f.toString()).put("mode", "link")));
        Path raw = store.rawDir().resolve(r.path("raw").asText());
        assertTrue(RawCapture.read(raw)[2].contains("read in place"));
        Files.writeString(f, "# Linked\n\nThe Saros dial, read in place. key: " + BRAVE + "\n");
        Files.setLastModifiedTime(f, FileTime.fromMillis(System.currentTimeMillis() + 5_000));
        String now = RawCapture.read(raw)[2];
        answers.add(now);
        assertTrue(now.startsWith("[not read: ") && now.contains("the setting brave_key"), now);
        noKeyAnywhere();
    }

    /** No answer, no log line and no file the library keeps holds any made-up secret's text, nor a part of one. */
    void noKeyAnywhere() throws Exception {
        String kept = shelves();
        for (String v : ALL) {
            String part = v.substring(v.length() - 10);
            for (String a : answers) assertFalse(a.contains(part), "an answer holds a key: " + a);
            for (ILoggingEvent e : logged.list) assertFalse(e.getFormattedMessage().contains(part), "a log line holds a key: " + e.getFormattedMessage());
            assertFalse(kept.contains(part), "the shelves hold a key");
        }
        assertTrue(logged.list.stream().anyMatch(e -> e.getFormattedMessage().contains("A document was not kept") || e.getFormattedMessage().contains("A linked document was not read")), "each refusal is logged, by the setting's name");
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
}
