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
import org.researchzosho.mcp.StandInMcp;
import org.researchzosho.tools.SearchTally;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/** The brain picks a tool of another MCP server, in a research run and in the chat, with a scripted drive: the call reaches the stand-in, the result comes back fenced and is cited by its locator. */
class McpBrainTest {
    static final ObjectMapper J = new ObjectMapper();
    static final String LOCATOR = "mcp://notes/search_notes?query=canvas%20tote";
    @TempDir Path tmp;
    String realHome;

    @BeforeEach void scratch() { realHome = System.getProperty("user.home"); System.setProperty("user.home", tmp.resolve("home").toString()); Config.invalidate(); SearchTally.override = tmp.resolve("tally.json"); }
    @AfterEach void back() { McpConnections.closeAll(); System.setProperty("user.home", realHome); Config.invalidate(); SearchTally.override = null; }

    static LibraryStore withNotes(Path at) throws Exception {
        LibraryStore store = new LibraryStore(at); store.init();
        ObjectNode a = (ObjectNode) J.readTree("{\"op\": \"add\", \"name\": \"notes\"}");
        ArrayNode c = a.putArray("command"); StandInMcp.command().forEach(c::add);
        a.set("patron", LibrarianDaemon.patronNode(Patrons.Patron.PERSON));
        assertTrue(new LibraryProtocol(store).mcp(a).path("answers").asBoolean());
        return store;
    }

    static ObjectNode call(String name, ObjectNode args) {
        ObjectNode m = J.createObjectNode(); m.put("role", "assistant"); m.put("content", "");
        ObjectNode c = m.putArray("tool_calls").addObject();
        c.put("id", "c" + System.nanoTime()); c.put("type", "function");
        c.putObject("function").put("name", name).put("arguments", args.toString());
        return m;
    }

    /** A worker that asks the notes server, notes what it showed with its locator, and finishes; a writer that cites it. */
    static class Drive implements Researcher.Drive {
        final String model;
        final List<List<String>> offered = new CopyOnWriteArrayList<>();
        final List<ArrayNode> histories = new CopyOnWriteArrayList<>();
        Drive(String model) { this.model = model; }
        @Override public ObjectNode chat(ArrayNode history, ArrayNode tools, int maxTokens, String toolChoice) {
            List<String> n = new ArrayList<>(); for (JsonNode t : tools) n.add(t.path("function").path("name").asText());
            offered.add(n); histories.add(history.deepCopy());
            if (n.equals(List.of("done"))) return call("done", J.createObjectNode().put("summary", "closed on the deadline"));
            int turn = 1; for (JsonNode m : history) if ("assistant".equals(m.path("role").asText())) turn++;
            if (n.contains("write_section")) return turn == 1 ? call("write_section", J.createObjectNode().put("heading", "Answer").put("text", "Canvas totes are sewn from 12 oz duck canvas (" + LOCATOR + ").")) : call("done", J.createObjectNode().put("summary", "one source"));
            return switch (turn) {
                case 1 -> call("notes.search_notes", J.createObjectNode().put("query", "canvas tote"));
                case 2 -> call("note", J.createObjectNode().put("claim", "canvas totes are sewn from 12 oz duck canvas").put("source", LOCATOR).put("quote", "Canvas tote bags are sewn from 12 oz duck canvas"));
                default -> call("done", J.createObjectNode().put("summary", "the notes say 12 oz duck canvas"));
            };
        }
        @Override public String classify(ArrayNode messages, int maxTokens) {
            String prompt = messages.get(messages.size() - 1).path("content").asText();
            if (prompt.contains("Decompose")) return "[\"what are canvas totes sewn from, by my notes?\"]";
            if (prompt.contains("reviewing research COVERAGE")) return "{\"sufficient\": true}";
            return "Canvas totes are sewn from 12 oz duck canvas (" + LOCATOR + ").";
        }
        @Override public int contextWindow() { return 32_000; }
        @Override public String model() { return model; }
    }

    @Test
    void aWorkerPicksTheServersToolAndCitesWhatItAnswered() throws Exception {
        LibraryStore store = withNotes(tmp.resolve("lib"));
        Drive drive = new Drive("qwen3.8-27b");
        var r = new Researcher(drive, drive, new ResearcherTest.FakeTools(), s -> { }, 1, store).run(new Researcher.Ask("What are canvas totes sewn from, by my notes?", "depth", 60, List.of()), "");
        List<String> first = drive.offered.get(0);
        assertTrue(first.containsAll(List.of("notes.search_notes", "notes.get_note", "search", "open", "note", "done")), first.toString());
        assertFalse(first.contains("notes.create_note") || first.contains("notes.notes_digest"), "the tools that change things are not on the table: " + first);
        String system = drive.histories.get(0).get(0).path("content").asText();
        assertTrue(system.contains("  notes.search_notes: from the MCP server notes, configured by the keeper: when the question needs what that server holds or reaches"), "the large tier's line for it: " + system);
        boolean fenced = false;
        for (ArrayNode h : drive.histories) for (JsonNode m : h) if ("tool".equals(m.path("role").asText()) && m.path("content").asText().startsWith("source: " + LOCATOR + " — ")
                && m.path("content").asText().contains(Fence.open("MCP RESULT notes.search_notes")) && m.path("content").asText().contains("Before your next step: note each fact this result showed")) fenced = true;
        assertTrue(fenced, "the worker read the result fenced and labelled, and was told to note it");
        assertTrue(r.answer().contains(LOCATOR), "the report cites the result by its locator: " + r.answer());
        assertEquals(1, SearchTally.thisMonth().get("MCP notes")[0]);
    }

    @Test
    void aSmallModelsWorkerIsShownOnlyTheToolsTheQuestionMatches() throws Exception {
        LibraryStore store = withNotes(tmp.resolve("lib"));
        Drive drive = new Drive("qwen3.5-9b");
        new Researcher(drive, drive, new ResearcherTest.FakeTools(), s -> { }, 1, store).run(new Researcher.Ask("What are canvas totes sewn from, by my notes?", "depth", 60, List.of()), "");
        List<String> mcp = drive.offered.get(0).stream().filter(n -> n.startsWith("notes.")).toList();
        assertEquals(List.of("notes.search_notes", "notes.get_note"), mcp, "the two whose names share a word with the question, at most three");
        String system = drive.histories.get(0).get(0).path("content").asText();
        assertTrue(system.contains("  notes.search_notes {\"query\":\"canvas totes sewn notes\"}"), "a literal call to copy: " + system);
    }

    @Test
    void aShelvesOnlyAskReachesNoOutsideServer() throws Exception {
        LibraryStore store = withNotes(tmp.resolve("lib"));
        Drive drive = new Drive("qwen3.8-27b");
        new Researcher(drive, drive, new ResearcherTest.FakeTools(), s -> { }, 1, store).run(new Researcher.Ask("What are canvas totes sewn from, by my notes?", "depth", 60, List.of(), "shelves", List.of()), "");
        assertTrue(drive.offered.get(0).stream().noneMatch(n -> n.startsWith("notes.")), drive.offered.get(0).toString());
    }

    @Test
    void theChatPicksTheServersToolAndSendsItOnlyTheModelsArguments() throws Exception {
        LibraryStore store = withNotes(tmp.resolve("lib"));
        List<List<String>> toolsSeen = new ArrayList<>(); List<ArrayNode> messagesSeen = new ArrayList<>();
        ObjectNode ask = J.createObjectNode(); ask.put("tool", "notes.search_notes"); ask.set("args", J.readTree("{\"query\": \"canvas tote\"}"));
        ObjectNode say = J.createObjectNode(); say.put("say", "Your notes say canvas totes are sewn from 12 oz duck canvas (" + LOCATOR + ").");
        Researcher.Drive d = ChatBrainTest.scripted(List.of(ask, say), toolsSeen, messagesSeen);
        Researcher.Drive large = new Researcher.Drive() {
            @Override public ObjectNode chat(ArrayNode m, ArrayNode t, int n, String c) { return d.chat(m, t, n, c); }
            @Override public String classify(ArrayNode m, int n) { return ""; }
            @Override public int contextWindow() { return 16384; }
            @Override public String model() { return "qwen3.8-27b"; }
        };
        Librarian lib = new Librarian(store, large, Librarian.person(), Librarian.Session.open(store));
        String reply = lib.say("what do my notes say about canvas totes?");
        assertEquals("Your notes say canvas totes are sewn from 12 oz duck canvas (" + LOCATOR + ").", reply);
        assertTrue(toolsSeen.get(0).containsAll(List.of("notes.search_notes", "notes.get_note", "library_research")) && !toolsSeen.get(0).contains("notes.create_note"), toolsSeen.get(0).toString());
        String result = "";
        for (JsonNode m : messagesSeen.get(1)) if ("tool".equals(m.path("role").asText())) result = m.path("content").asText();
        assertTrue(result.startsWith("source: " + LOCATOR + " — ") && result.contains(Fence.open("MCP RESULT notes.search_notes")), result);
        assertTrue(result.contains("(asked with {\"query\":\"canvas tote\"})"), "the server got the model's arguments and nothing of the library's, no patron among them: " + result);
    }
}
