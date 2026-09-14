package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** A conversation with another assistant, absorbed: shelved as it is, the person's questions filed, the assistant's claims listed to check. */
class ConversationsTest {

    private static final ObjectMapper M = new ObjectMapper();
    private static java.util.function.Supplier<Researcher.Drive> drives;

    @BeforeAll static void noModel() { drives = Explain.DRIVES; Explain.DRIVES = () -> null; }
    @AfterAll static void restore() { Explain.DRIVES = drives; }

    static final String MARKDOWN = """
            # Antikythera chat

            **User:** How were the gears of the Antikythera mechanism made?

            **Assistant:** Sure! The Antikythera mechanism's gears were cut by hand from bronze sheet about 2 mm thick. The largest gear has 223 teeth, which matches the Saros cycle of 223 lunar months. Feel free to ask more.

            **User:** Which museum has it?

            **Assistant:** It is held by the National Archaeological Museum in Athens, which acquired it in 1901. I hope this helps!
            """;

    static String chatGpt() {
        return """
            [{"title":"Saros dial","mapping":{
              "root":{"id":"root","parent":null,"children":["m1"],"message":null},
              "m1":{"id":"m1","parent":"root","children":["m2"],"message":{"author":{"role":"user"},"content":{"content_type":"text","parts":["What does the back dial of the Antikythera mechanism show?"]}}},
              "m2":{"id":"m2","parent":"m1","children":["m3a","m3b"],"message":{"author":{"role":"assistant"},"content":{"content_type":"text","parts":["The back dial has a spiral of 223 cells for the Saros period of 223 lunar months. It predicts eclipses."]}}},
              "m3a":{"id":"m3a","parent":"m2","children":[],"message":{"author":{"role":"user"},"content":{"content_type":"text","parts":["never mind"]}}},
              "m3b":{"id":"m3b","parent":"m2","children":[],"message":{"author":{"role":"user"},"content":{"content_type":"text","parts":["Who first described the Saros dial?"]}}}
            }},
            {"title":"Second thread","mapping":{
              "r":{"id":"r","parent":null,"children":["a"],"message":null},
              "a":{"id":"a","parent":"r","children":[],"message":{"author":{"role":"user"},"content":{"content_type":"text","parts":["Is the Metonic cycle 19 years?"]}}}
            }}]
            """;
    }

    static String claude() {
        return """
            [{"name":"Gears","chat_messages":[
              {"sender":"human","text":"How thick is the bronze of the Antikythera gears?"},
              {"sender":"assistant","text":"The bronze sheet is about 2 mm thick. The teeth are triangular, cut with a file."}
            ]}]
            """;
    }

    @Test
    void theExportShapesParseIntoTurns() {
        List<Conversations.Thread> md = Conversations.parse(MARKDOWN.getBytes(StandardCharsets.UTF_8), "antikythera-chat.md");
        assertEquals(1, md.size());
        Conversations.Thread t = md.get(0);
        assertEquals("antikythera chat", t.title());
        assertEquals(List.of("person", "assistant", "person", "assistant"), t.turns().stream().map(Conversations.Turn::role).toList());
        assertTrue(t.turns().get(0).text().startsWith("How were the gears"), t.turns().get(0).text());

        List<Conversations.Thread> gpt = Conversations.parse(chatGpt().getBytes(StandardCharsets.UTF_8), "conversations.json");
        assertEquals(2, gpt.size());
        assertEquals("Saros dial", gpt.get(0).title());
        assertEquals(3, gpt.get(0).turns().size(), "root through the kept branch: user, assistant, the last child");
        assertEquals("Who first described the Saros dial?", gpt.get(0).turns().get(2).text());
        assertEquals("Is the Metonic cycle 19 years?", gpt.get(1).turns().get(0).text());

        List<Conversations.Thread> cl = Conversations.parse(claude().getBytes(StandardCharsets.UTF_8), "conversations.json");
        assertEquals(1, cl.size()); assertEquals("Gears", cl.get(0).title());
        assertEquals("assistant", cl.get(0).turns().get(1).role());

        List<Conversations.Thread> plain = Conversations.parse("Notes on gears. The largest gear has 223 teeth. Why 223?".getBytes(StandardCharsets.UTF_8), "notes.txt");
        assertEquals(List.of("author"), plain.get(0).turns().stream().map(Conversations.Turn::role).toList(), "no markers = one voice");
    }

    @Test
    void questionsAreThePersonsAndClaimsAreTheAssistants() {
        Conversations.Thread t = Conversations.parse(MARKDOWN.getBytes(StandardCharsets.UTF_8), "chat.md").get(0);
        assertEquals(List.of("How were the gears of the Antikythera mechanism made?", "Which museum has it?"), Conversations.questions(t));
        List<String> claims = Conversations.claims(t, null);
        assertTrue(claims.stream().anyMatch(c -> c.contains("223 teeth")), claims.toString());
        assertTrue(claims.stream().anyMatch(c -> c.contains("1901")), claims.toString());
        assertFalse(claims.stream().anyMatch(c -> c.startsWith("Sure") || c.contains("Feel free") || c.contains("hope this")), "the chatter is left out: " + claims);
        assertFalse(claims.stream().anyMatch(c -> c.endsWith("?")));
        // the model's reading, when there is one, is a list of lines
        var extractor = Conversations.modelExtractor(new ResearcherTest.ScriptedDrive() {
            @Override public String classify(com.fasterxml.jackson.databind.node.ArrayNode messages, int maxTokens) { return "- The gears were cut from bronze sheet 2 mm thick.\n2. The largest gear has 223 teeth.\nNONE"; }
        });
        assertEquals(List.of("The gears were cut from bronze sheet 2 mm thick.", "The largest gear has 223 teeth."), extractor.apply("anything"));
    }

    @Test
    void absorbShelvesFilesAndListsButFilesNoFinding(@TempDir Path home) throws Exception {
        LibraryStore store = new LibraryStore(home.resolve("lib")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        Path f = home.resolve("chat.md"); Files.writeString(f, MARKDOWN);
        LibraryProtocol p = new LibraryProtocol(store);
        ObjectNode a = M.createObjectNode().put("path", f.toString());
        a.putObject("patron").put("did", "person").put("name", "keeper").put("runtime", "cli");
        ObjectNode r = p.absorb(a);
        assertEquals(1, r.path("absorbed").asInt()); assertEquals(0, r.path("remaining").asInt());
        assertEquals(2, r.path("questions_filed").asInt());
        assertTrue(r.path("claims_to_check").asInt() >= 2, r.toString());
        assertEquals("mechanical", r.path("claims_by").asText());
        assertEquals("How were the gears of the Antikythera mechanism made?", r.path("main_question").asText());
        assertTrue(r.path("summary").asText().contains("none is filed as a finding"), r.path("summary").asText());
        // on the shelves, as a conversation, in the conversations collection
        String rawName = r.path("threads").get(0).path("raw").asText();
        Path raw = store.rawDir().resolve(rawName);
        assertTrue(Files.exists(raw));
        String[] rr = RawCapture.read(raw);
        assertTrue(rr[0].startsWith("conversation://"), rr[0]);
        assertTrue(rr[2].contains("## Person") && rr[2].contains("## Assistant") && rr[2].contains("223 teeth"), rr[2]);
        assertEquals("conversations", RawCapture.collectionOf(raw));
        assertFalse(new LibrarianIndex(store, Embeddings.none()).searchIn("Saros cycle lunar months", 5, null, "conversations").isEmpty(), "searchable");
        // the questions are on the frontier as the person's, once
        List<Frontier.Line> open = Frontier.read(store);
        assertEquals(2, open.size(), open.toString());
        assertEquals("person", Frontier.typeOf(open.get(0)));
        assertTrue(open.get(0).kind().contains("from a conversation"), open.get(0).kind());
        ObjectNode again = p.absorb(a.deepCopy());
        assertEquals(0, again.path("questions_filed").asInt(), "the same thread twice files nothing new");
        assertEquals(2, again.path("threads").get(0).path("questions_already_open").size());
        assertEquals(2, Frontier.read(store).size());
        // nothing became a finding
        assertEquals(0, store.scanFindings().findings().size());
        // verify files one run whose question carries the claims, numbered
        ObjectNode v = p.absorb(a.deepCopy().put("verify", true));
        String job = v.path("verify_job_id").asText();
        assertTrue(job.startsWith("J-"), v.toString());
        var jobs = new Jobs(store, j -> { throw new IllegalStateException("read only"); });
        var filed = jobs.active().stream().filter(j -> j.path("job_id").asText().equals(job)).findFirst().orElseThrow();
        String q = filed.path("args").path("question").asText();
        assertTrue(q.startsWith("Check each of these claims") && q.contains("1. ") && q.contains("223"), q);
        assertEquals("both", filed.path("args").path("sources").asText());
        assertTrue(v.path("summary").asText().contains("a run is checking them"));
    }

    @Test
    void anExportTakesTheFirstFewAndSaysHowManyRemain(@TempDir Path home) throws Exception {
        LibraryStore store = new LibraryStore(home.resolve("lib")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        Path f = home.resolve("conversations.json"); Files.writeString(f, chatGpt());
        LibraryProtocol p = new LibraryProtocol(store);
        ObjectNode a = M.createObjectNode().put("path", f.toString()).put("limit", 1);
        a.putObject("patron").put("did", "person").put("name", "keeper").put("runtime", "cli");
        ObjectNode r = p.absorb(a);
        assertEquals(2, r.path("threads_found").asInt()); assertEquals(1, r.path("absorbed").asInt()); assertEquals(1, r.path("remaining").asInt());
        assertEquals("Saros dial", r.path("threads").get(0).path("title").asText());
        assertEquals(2, r.path("threads").get(0).path("questions_filed").size(), "both of the person's questions on the kept branch");
        // pasted text from any write patron; a path from a stranger is refused
        ObjectNode pasted = M.createObjectNode().put("text", MARKDOWN).put("title", "Pasted gears");
        pasted.putObject("patron").put("did", "did:key:zFriend").put("name", "f").put("runtime", "mcp");
        Patrons.setDefault(store, Patrons.Level.write);
        ObjectNode pr = p.absorb(pasted);
        assertEquals("Pasted gears", pr.path("threads").get(0).path("title").asText());
        assertEquals("pasted", pr.path("source").asText());
        ObjectNode strangerPath = M.createObjectNode().put("path", f.toString());
        strangerPath.putObject("patron").put("did", "did:key:zFriend").put("name", "f").put("runtime", "mcp");
        assertTrue(assertThrows(ProtocolError.class, () -> p.absorb(strangerPath)).getMessage().contains("keeper"));
        assertThrows(ProtocolError.class, () -> p.absorb(M.createObjectNode().put("path", f.toString()).put("text", "x")), "one input, not two");
        // the chat and the MCP server offer it
        assertTrue(Librarian.TOOLS.contains("library_absorb"));
        boolean served = false; for (var t : org.researchzosho.mcp.McpServer.allTools()) if (t.path("name").asText().equals("library_absorb")) served = true;
        assertTrue(served);
    }
}
