package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;
import org.researchzosho.tools.SearchTally;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code researchzosho settings set <name> --file <path>} (0.8.4): the value read from a file the person names, so that a key never stands
 * on a command line. Before, the form did not exist, and the command kept the words "--file <path>" as the setting's value and said it was
 * set. The value is the file's one line; a file that is not one value is refused and nothing of it is shown; a word that begins with two
 * dashes is never kept as a value; a secret is never printed whole; and no door other than the command line reads a file. Every key here
 * is made up; every file is in the test's own folder, and its home is a scratch one.
 */
class SettingsFileTest {

    static final ObjectMapper M = new ObjectMapper();
    static final String EXA = "RESEARCHZOSHO_EXA_KEY", KEY = "made-up-exa-key-0123456789abcdef";

    @TempDir Path tmp;
    String realHome;
    Path keys;

    @BeforeEach void scratchHome() throws Exception {
        realHome = System.getProperty("user.home");
        Path home = Files.createDirectories(tmp.resolve("home"));
        System.setProperty("user.home", home.toString()); Config.invalidate();
        QuietHome.quiet(home);
        new LibraryStore(home.resolve("researchzosho-library")).init();   // the library the command finds in this home
        keys = Files.createDirectories(tmp.resolve("keys"));
        SearchTally.override = tmp.resolve("tally.json");
    }

    @AfterEach void restoreHome() {
        Config.provide(Map.of()); SearchTally.override = null;
        System.setProperty("user.home", realHome); Config.invalidate();
    }

    record Ran(int code, String out, String err) { String all() { return out + err; } }

    /** The whole command, as a person types it, with what it printed. */
    static Ran cli(String... words) {
        String[] args = new String[words.length + 1];
        args[0] = "researchzosho";
        System.arraycopy(words, 0, args, 1, words.length);
        PrintStream o = System.out, e = System.err;
        ByteArrayOutputStream bo = new ByteArrayOutputStream(), be = new ByteArrayOutputStream();
        System.setOut(new PrintStream(bo, true, StandardCharsets.UTF_8)); System.setErr(new PrintStream(be, true, StandardCharsets.UTF_8));
        try { return new Ran(LibrarianCli.run(args, "http://127.0.0.1:1", "m"), bo.toString(StandardCharsets.UTF_8), be.toString(StandardCharsets.UTF_8)); }
        finally { System.setOut(o); System.setErr(e); }
    }

    Path file(String name, String text) throws Exception { return Files.writeString(keys.resolve(name), text, StandardCharsets.UTF_8); }

    static String stored(String key) { Config.invalidate(); return Config.stored(key); }

    @Test
    void theValueIsTheFilesOneLineAndOnlyTheSettingKeepsIt() throws Exception {
        Path f = file("exa.txt", "  " + KEY + "  \n");
        Ran set = cli("settings", "set", "exa_key", "--file", f.toString());
        assertEquals(0, set.code(), set.all());
        assertEquals(KEY, stored(EXA), "the file's line, without the line end and the spaces around it; before 0.8.4 the words \"--file " + f + "\" were kept");
        assertEquals("exa_key is set. (set (ends with …def)) The value was read from " + f + ".\n", set.out(), "set, and where the value came from");
        assertFalse(set.all().contains(KEY), "never the key: " + set.all());
        String config = Files.readString(Config.userConfigPath());
        assertEquals(1, config.split(KEY, -1).length - 1, "the settings file holds the key once, as the setting: " + config.length() + " characters");
        assertFalse(config.contains("--file") || config.contains(f.toString()), "and nothing of the command");
        try (Stream<Path> all = Files.walk(Path.of(System.getProperty("user.home")))) {   // the library and the program's folder: no other copy anywhere
            List<Path> holding = all.filter(Files::isRegularFile).filter(x -> !x.equals(Config.userConfigPath())).filter(x -> { try { return Files.readString(x, StandardCharsets.ISO_8859_1).contains(KEY); } catch (Exception e) { return false; } }).toList();
            assertTrue(holding.isEmpty(), "the key is kept in the setting alone: " + holding);
        }
        if (!System.getProperty("os.name", "").toLowerCase().contains("win"))
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(Config.userConfigPath())), "a settings file made by the first set is readable only by its owner");
        // a file an editor on another system wrote: a mark at its start and a carriage return at each line's end
        Path crlf = file("brave.txt", "﻿made-up-brave-key-0123456789\r\n\r\n");
        assertEquals(0, cli("settings", "set", "brave_key", "--file", crlf.toString()).code());
        assertEquals("made-up-brave-key-0123456789", stored("RESEARCHZOSHO_BRAVE_KEY"));
        // a model host's key, by the same rule
        Ran host = cli("models", "key", "openrouter", "--file", f.toString());
        assertEquals(0, host.code(), host.all());
        assertEquals(KEY, stored("RESEARCHZOSHO_KEY_OPENROUTER_AI"), "the host's key, read from the file");
        assertTrue(host.out().endsWith(" The key was read from " + f + ".\n") && !host.all().contains(KEY), host.all());
    }

    @Test
    void aWordThatBeginsWithTwoDashesIsNeverKeptAsAValue() throws Exception {
        Path f = file("exa.txt", KEY + "\n");
        for (List<String> words : List.of(List.of("--file"), List.of("made-up", "--file", f.toString()), List.of("--file", f.toString(), "extra"), List.of("--fiel", f.toString()), List.of("--"))) {
            List<String> all = new ArrayList<>(List.of("settings", "set", "exa_key")); all.addAll(words);
            Ran r = cli(all.toArray(new String[0]));
            assertEquals(2, r.code(), words + ": " + r.all());
            assertTrue(r.err().contains("usage: researchzosho settings set <name> <value>") && r.err().contains("researchzosho settings set <name> --file <path>") && r.err().endsWith("Nothing was changed.\n"), r.err());
            assertNull(stored(EXA), words + " is not kept as the key");
        }
        assertTrue(cli("settings", "set", "exa_key", "--file").err().startsWith("--file needs the path of a file.\n"));
        assertTrue(cli("settings", "set", "exa_key", "--fiel", f.toString()).err().startsWith("researchzosho settings set does not take --fiel.\n"));
        // a value that really begins with two dashes is given after --
        Ran dashed = cli("settings", "set", "explorer_types", "--", "--odd-value");
        assertEquals(0, dashed.code(), dashed.all());
        assertEquals("--odd-value", stored("RESEARCHZOSHO_EXPLORER_TYPES"));
        // unset takes the name alone, and read-in a language: neither keeps nor drops anything on such a word
        assertEquals(0, cli("settings", "set", "exa_key", KEY).code());
        Ran unset = cli("settings", "unset", "exa_key", "--file", f.toString());
        assertEquals(2, unset.code(), unset.all());
        assertEquals(KEY, stored(EXA), "nothing was unset");
        Ran readIn = cli("settings", "read-in", "--file", f.toString());
        assertEquals(2, readIn.code(), "before 0.8.4 the words were kept as the language to read in: " + readIn.all());
        assertFalse(readIn.out().contains("You read every report in"), readIn.all());
        // the same swallow, where a key is the next word of another command
        Ran record = cli("records", "key", "serpapi-inventor", "--file");
        assertEquals(2, record.code(), record.all());
        assertNull(stored("RESEARCHZOSHO_SERPAPI_KEY"), "before 0.8.4 the word --file was saved as the key");
        assertEquals(0, cli("records", "key", "serpapi-inventor", "--file", f.toString()).code());
        assertEquals(KEY, stored("RESEARCHZOSHO_SERPAPI_KEY"));
        assertEquals(0, cli("models", "key", "openrouter", "made-up-router-key-0123456789").code());
        Ran forget = cli("models", "key", "openrouter", "--file");
        assertEquals(2, forget.code(), forget.all());
        assertEquals("made-up-router-key-0123456789", stored("RESEARCHZOSHO_KEY_OPENROUTER_AI"), "before 0.8.4 a --file with no path forgot the host's key");
        Ran peer = cli("peer", "add", "far", "http://127.0.0.1:1", "--file", f.toString(), "--group", "lab");
        assertEquals(0, peer.code(), peer.all());
        Peers.Peer far = Peers.list(LibraryStore.open()).stream().filter(p -> p.name().equals("far")).findFirst().orElseThrow();
        assertEquals(KEY, far.token(), "the peer's token, read from the file");
        assertEquals(List.of("lab"), far.groups());
    }

    @Test
    void aFileThatIsNotOneValueIsRefusedByNameAndNothingOfItIsShown() throws Exception {
        String one = "made-up-first-line-0123456789", two = "made-up-second-line-0123456789";
        Path twoLines = file("two.txt", one + "\n" + two + "\n"), empty = file("empty.txt", ""), blank = file("blank.txt", "  \n\n\t\n");
        String missing = "/home/user/no-such-folder/exa.txt";
        Map<String, String> said = Map.of(
                twoLines.toString(), "The file " + twoLines + " has 2 lines with text on them. The value is read from a file that holds it alone, on one line, so that no line is guessed at. Nothing was changed.\n",
                empty.toString(), "The file " + empty + " is empty. Nothing was changed.\n",
                blank.toString(), "The file " + blank + " is empty. Nothing was changed.\n",
                missing, "There is no file at " + missing + ". Nothing was changed.\n",
                keys.toString(), keys + " is a folder, not a file. Nothing was changed.\n");
        for (var e : said.entrySet()) {
            Ran r = cli("settings", "set", "exa_key", "--file", e.getKey());
            assertEquals(1, r.code(), e.getKey() + ": " + r.all());
            assertEquals(e.getValue(), r.err());
            assertEquals("", r.out());
            assertNull(stored(EXA), "nothing was kept from " + e.getKey());
            assertFalse(r.all().contains(one) || r.all().contains(two), "nothing of the file is shown");
        }
        Path big = file("big.txt", "x".repeat(70_000));
        assertEquals("The file " + big + " is larger than one value can be (64 KB). Nothing was changed.\n", cli("settings", "set", "exa_key", "--file", big.toString()).err());
        assertNull(stored(EXA));
    }

    @Test
    void aSecretIsNeverPrintedBySetOrByTheList() throws Exception {
        // a short secret: its last three characters would be half of it, or all; before 0.8.4 they were shown
        Ran shortOne = cli("settings", "set", "brave_key", "abcxyz");
        assertEquals("brave_key is set. (set)\n", shortOne.out());
        assertFalse(cli("settings").out().contains("xyz"), "the list shows it only as set");
        Ran shortHost = cli("models", "key", "openrouter", "k9z");
        assertEquals("A key is kept for openrouter.ai. It goes to that host and nowhere else.\n", shortHost.out(), "a host's key the same");
        Ran set = cli("settings", "set", "exa_key", KEY);
        assertFalse(set.all().contains(KEY), set.all());
        Ran list = cli("settings");
        assertEquals(0, list.code(), list.all());
        assertTrue(list.out().contains("\n  exa_key  = set (ends with …def)\n") && !list.out().contains(KEY), "the list says it is set and how it ends");
        assertTrue(list.out().contains("settings set <name> --file <path> reads it from a file (for a key)"), "and how a key is set from a file");
        // a secret not from the settings file (as one in the environment, given to the program from outside), the same
        Config.provide(Map.of("RESEARCHZOSHO_TAVILY_KEY", "made-up-tavily-key-9876543210"));
        Ran provided = cli("settings");
        assertTrue(provided.out().contains("\n  tavily_key  = set (ends with …210)\n") && !provided.out().contains("made-up-tavily-key-9876543210"), "a secret from outside the file is not shown either");
    }

    @Test
    void theServiceOfAccountsSetsFromAFileByTheSameRule() throws Exception {
        Path f = file("exa.txt", KEY + "\n"), twoLines = file("two.txt", "made-up-one-0123456789\nmade-up-two-0123456789\n");
        ByteArrayOutputStream out = new ByteArrayOutputStream(), err = new ByteArrayOutputStream();
        int rc = AccountsCli.settings(new String[]{"researchzosho", "settings", "set", "exa_key", "--file", f.toString()}, new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
        assertEquals(0, rc, err.toString(StandardCharsets.UTF_8));
        assertEquals(KEY, stored(EXA));
        assertEquals("exa_key is set. (set (ends with …def)) The value was read from " + f + ".\n", out.toString(StandardCharsets.UTF_8));
        for (String[] words : List.of(new String[]{"--file"}, new String[]{"--file", twoLines.toString()}, new String[]{"made-up", "--file", f.toString()})) {
            String[] args = new String[4 + words.length];
            args[0] = "researchzosho"; args[1] = "settings"; args[2] = "set"; args[3] = "tavily_key";
            System.arraycopy(words, 0, args, 4, words.length);
            ByteArrayOutputStream o = new ByteArrayOutputStream(), e = new ByteArrayOutputStream();
            assertTrue(AccountsCli.settings(args, new PrintStream(o, true, StandardCharsets.UTF_8), new PrintStream(e, true, StandardCharsets.UTF_8)) != 0, String.join(" ", words));
            assertNull(stored("RESEARCHZOSHO_TAVILY_KEY"), String.join(" ", words) + " is not kept");
            assertFalse(e.toString(StandardCharsets.UTF_8).contains("made-up-one"), "nothing of the file is shown");
        }
    }

    /**
     * The chat, the web app, the phone, MCP and the API change a setting through the protocol, and none of them reads a file: a value
     * written as the command line's file form is refused with the command that reads one, and neither the words nor the file's text is
     * kept. Before 0.8.4 each kept the words "--file <path>" as the value.
     */
    @Test
    void noDoorReadsAFile() throws Exception {
        Path f = file("exa.txt", KEY + "\n");
        String fileForm = "--file " + f;
        Path lib = tmp.resolve("lib");
        LibraryStore store = new LibraryStore(lib); store.init();
        LibraryProtocol p = new LibraryProtocol(store);
        ObjectNode a = M.createObjectNode().put("op", "set").put("name", "exa_key").put("value", fileForm);
        a.putObject("patron").put("did", "person").put("name", "").put("runtime", "test");
        ProtocolError refused = assertThrows(ProtocolError.class, () -> p.settings(a));
        assertEquals("invalid_args", refused.code);
        assertEquals("A value is read from a file only on the command line of the library's own computer: researchzosho settings set exa_key --file <path>. Nothing was changed.", refused.getMessage());
        ObjectNode k = M.createObjectNode().put("op", "key").put("host", "openrouter").put("value", fileForm);
        k.putObject("patron").put("did", "person").put("name", "").put("runtime", "test");
        assertThrows(ProtocolError.class, () -> p.models(k));
        ObjectNode m = M.createObjectNode().put("op", "add").put("name", "docs").put("url", "https://mcp.example.org/mcp").put("header_key", "mcp_docs_key").put("header_value", fileForm);
        m.putObject("patron").put("did", "person").put("name", "").put("runtime", "test");
        assertThrows(ProtocolError.class, () -> p.mcp(m), "an MCP server's key, the same");
        assertNull(stored(EXA)); assertNull(stored("RESEARCHZOSHO_KEY_OPENROUTER_AI")); assertNull(stored("RESEARCHZOSHO_MCP_DOCS_KEY"));
        assertTrue(McpServers.named(store, "docs") == null, "and the server was not added");
        // the chat's tool, as the person of the conversation
        Librarian chatOf = new Librarian(store, LibrarianChatTest.scripted(List.of(), new ArrayList<>()), Librarian.person(), Librarian.Session.open(store));
        String chat = chatOf.call("library_settings", M.readTree("{\"op\":\"set\",\"name\":\"exa_key\",\"value\":" + M.writeValueAsString(fileForm) + "}"));
        assertTrue(chat.contains("only on the command line"), chat);
        assertNull(stored(EXA), "the chat kept nothing");
        // the phone does not reach the settings at all
        assertNotNull(PhoneRules.refusal(store, null, "library_settings", M.readTree("{\"op\":\"set\",\"name\":\"exa_key\",\"value\":" + M.writeValueAsString(fileForm) + "}")));
        // the web app's form, the API and MCP, at the library's own address
        LibrarianDaemon d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1);
        try {
            HttpClient c = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
            String base = "http://127.0.0.1:" + d.port();
            HttpResponse<String> form = c.send(HttpRequest.newBuilder(URI.create(base + "/settings")).header("Content-Type", "application/x-www-form-urlencoded").header("Origin", base)
                    .POST(HttpRequest.BodyPublishers.ofString("name=exa_key&value=" + URLEncoder.encode(fileForm, StandardCharsets.UTF_8))).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(303, form.statusCode(), form.body());
            assertTrue(URLDecoder.decode(form.headers().firstValue("Location").orElse(""), StandardCharsets.UTF_8).contains("only on the command line"), "the page says why: " + form.headers().map());
            assertNull(stored(EXA), "the web app kept nothing");
            HttpResponse<String> api = c.send(HttpRequest.newBuilder(URI.create(base + "/v1/settings")).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(M.writeValueAsString(M.createObjectNode().put("op", "set").put("name", "exa_key").put("value", fileForm)))).build(), HttpResponse.BodyHandlers.ofString());
            assertTrue(api.body().contains("only on the command line"), api.statusCode() + " " + api.body());
            assertNull(stored(EXA), "the API kept nothing");
            ObjectNode call = M.createObjectNode().put("jsonrpc", "2.0").put("id", 1).put("method", "tools/call");
            call.putObject("params").put("name", "library_settings").putObject("arguments").put("op", "set").put("name", "exa_key").put("value", fileForm);
            HttpResponse<String> mcp = c.send(HttpRequest.newBuilder(URI.create(base + "/rpc")).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(M.writeValueAsString(call))).build(), HttpResponse.BodyHandlers.ofString());
            assertTrue(mcp.body().contains("only on the command line"), mcp.statusCode() + " " + mcp.body());
            assertNull(stored(EXA), "MCP kept nothing");
        } finally { d.stop(); McpConnections.closeAll(); }
        JsonNode listed = p.settings(M.readTree("{\"op\":\"list\",\"patron\":{\"did\":\"person\",\"name\":\"\",\"runtime\":\"test\"}}"));
        assertFalse(listed.toString().contains(KEY) || listed.toString().contains(fileForm), "nothing of the file and nothing of the words anywhere in the settings");
    }
}
