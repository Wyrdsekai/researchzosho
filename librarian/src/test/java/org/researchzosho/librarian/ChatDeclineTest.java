package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.drive.DeclineJudge;
import org.researchzosho.drive.Declined;
import org.researchzosho.drive.Judge;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A chat turn the model declined shows the library's statement: which model, that it declined, that ResearchZosho did not try to get
 * around it, and what the model said. No nudge, no "ask me again, another way", no second request. The words are placeholders.
 */
class ChatDeclineTest {
    static final ObjectMapper M = new ObjectMapper();
    @TempDir Path tmp;

    @Test
    void aDeclinedTurnShowsTheStatementInsteadOfNudging() throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        List<ArrayNode> seen = new ArrayList<>();
        Researcher.Drive drive = new Researcher.Drive() {
            @Override public ObjectNode chat(ArrayNode messages, ArrayNode tools, int maxTokens, String toolChoice) {
                seen.add(messages.deepCopy());
                throw new Declined("placeholder-model", "", Declined.How.FILTERED);
            }
            @Override public String classify(ArrayNode messages, int maxTokens) { return ""; }
            @Override public int contextWindow() { return 16384; }
        };
        Librarian.Session session = Librarian.Session.open(store);
        String reply = new Librarian(store, drive, Librarian.person(), session).say("a placeholder question");
        assertEquals(1, seen.size(), "asked once: no nudge, no second try");
        assertTrue(reply.startsWith("The model this library uses (placeholder-model) declined to answer this. ResearchZosho did not try to get around it."), reply);
        assertTrue(reply.contains("filtered"), reply);
        assertFalse(reply.contains("ask me again"), reply);
        assertFalse(reply.contains("Say it in words"), reply);
        String turns = Files.readString(store.root().resolve("catalog").resolve("chat-turns.jsonl"), StandardCharsets.UTF_8);
        assertTrue(turns.contains("\"declined\":true"), turns);
    }

    @Test
    void aToolWhoseModelDeclinedEndsTheTurnWithTheStatement() throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Path paper = tmp.resolve("tides.md");
        Files.writeString(paper, "# Tides\n\nA placeholder paper about placeholder tides. " + "It measures the placeholder harbours. ".repeat(20), StandardCharsets.UTF_8);
        Supplier<Researcher.Drive> was = Explain.DRIVES;
        // the drive the library's tools use declines what it is asked; the chat's own model called the tool
        Explain.DRIVES = () -> new Researcher.Drive() {
            @Override public ObjectNode chat(ArrayNode messages, ArrayNode tools, int maxTokens, String toolChoice) { throw new Declined("placeholder-model", "I am not able to help with that request.", Declined.How.WORDS); }
            @Override public String classify(ArrayNode messages, int maxTokens) { throw new Declined("placeholder-model", "I am not able to help with that request.", Declined.How.WORDS); }
            @Override public int contextWindow() { return 16384; }
        };
        try {
            List<ArrayNode> seen = new ArrayList<>();
            ObjectNode survey = M.createObjectNode().put("path", paper.toString());
            var drive = LibrarianChatTest.scripted(List.of(LibrarianChatTest.tool("library_survey", survey.toString()),
                    LibrarianChatTest.say("I read the paper and described it.")), seen);
            String reply = new Librarian(store, drive, Librarian.person(), Librarian.Session.open(store)).say("read this paper in");
            assertEquals(1, seen.size(), "the chat's model was not asked to carry on around the decline");
            assertTrue(reply.contains("The model this library uses (placeholder-model) declined") && reply.contains("ResearchZosho did not try to get around it."), reply);
            assertTrue(reply.contains("I am not able to help with that request."), reply);
        } finally { Explain.DRIVES = was; }
    }

    @Test
    void aToolThatDidItsPartAndWhoseModelDeclinedTheRestIsSaidUnderTheReply() throws Exception {
        // M6: absorb shelves the transcript and files its questions, and the model declines to list its claims: the chat goes on, and the
        // library's statement is said under the reply, whatever the chat's model made of the result
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Supplier<Researcher.Drive> was = Explain.DRIVES;
        Explain.DRIVES = () -> new Researcher.Drive() {
            @Override public ObjectNode chat(ArrayNode messages, ArrayNode tools, int maxTokens, String toolChoice) { throw new Declined("placeholder-model", "I am not able to help with that request.", Declined.How.WORDS); }
            @Override public String classify(ArrayNode messages, int maxTokens) { throw new Declined("placeholder-model", "I am not able to help with that request.", Declined.How.WORDS); }
            @Override public int contextWindow() { return 16384; }
        };
        try {
            List<ArrayNode> seen = new ArrayList<>();
            ObjectNode absorb = M.createObjectNode().put("text", ConversationsTest.MARKDOWN).put("title", "Pasted placeholder chat");
            var drive = LibrarianChatTest.scripted(List.of(LibrarianChatTest.tool("library_absorb", absorb.toString()),
                    LibrarianChatTest.say("I shelved the conversation.")), seen);
            String reply = new Librarian(store, drive, Librarian.person(), Librarian.Session.open(store)).say("read this conversation in");
            assertTrue(reply.startsWith("I shelved the conversation."), reply);
            assertTrue(reply.contains("The model this library uses (placeholder-model) declined to list the checkable claims in this text. ResearchZosho did not try to get around it."), reply);
            String turns = Files.readString(store.root().resolve("catalog").resolve("chat-turns.jsonl"), StandardCharsets.UTF_8);
            assertTrue(turns.contains("\"declined\":true"), turns);
        } finally { Explain.DRIVES = was; }
    }

    @Test
    void theToolsAfterADeclinedOneInTheSameBatchAreNotRun() throws Exception {
        // M7: the model asks for two tools in one turn; the first one's model declines its step, so the second is not run and the reply says so
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Path paper = tmp.resolve("tides.md");
        Files.writeString(paper, "# Tides\n\nA placeholder paper about placeholder tides. " + "It measures the placeholder harbours. ".repeat(20), StandardCharsets.UTF_8);
        Supplier<Researcher.Drive> was = Explain.DRIVES;
        Explain.DRIVES = () -> new Researcher.Drive() {
            @Override public ObjectNode chat(ArrayNode messages, ArrayNode tools, int maxTokens, String toolChoice) { throw new Declined("placeholder-model", "I am not able to help with that request.", Declined.How.WORDS); }
            @Override public String classify(ArrayNode messages, int maxTokens) { throw new Declined("placeholder-model", "I am not able to help with that request.", Declined.How.WORDS); }
            @Override public int contextWindow() { return 16384; }
        };
        try {
            List<ArrayNode> seen = new ArrayList<>();
            Researcher.Drive drive = new Researcher.Drive() {
                @Override public ObjectNode chat(ArrayNode messages, ArrayNode tools, int maxTokens, String toolChoice) {
                    seen.add(messages.deepCopy());
                    ObjectNode m = M.createObjectNode(); m.put("role", "assistant"); m.put("content", "");
                    ArrayNode calls = m.putArray("tool_calls");
                    ObjectNode a = calls.addObject(); a.put("id", "c1"); a.put("type", "function");
                    a.putObject("function").put("name", "library_survey").put("arguments", M.createObjectNode().put("path", paper.toString()).toString());
                    ObjectNode b = calls.addObject(); b.put("id", "c2"); b.put("type", "function");
                    b.putObject("function").put("name", "library_frontier").put("arguments", M.createObjectNode().put("op", "add").put("question", "What placeholder harbours were measured?").toString());
                    return m;
                }
                @Override public String classify(ArrayNode messages, int maxTokens) { return ""; }
                @Override public int contextWindow() { return 16384; }
            };
            String reply = new Librarian(store, drive, Librarian.person(), Librarian.Session.open(store)).say("read this paper in, and note a question");
            assertEquals(1, seen.size());
            Path frontier = store.frontierFile();
            String open = Files.exists(frontier) ? Files.readString(frontier, StandardCharsets.UTF_8) : "";
            assertFalse(open.contains("placeholder harbours were measured"), "the tool after the declined one was not run: " + open);
            assertTrue(reply.contains("The library did not run the other tool the model asked for in the same turn: library_frontier."), reply);
        } finally { Explain.DRIVES = was; }
    }

    @Test
    void aReplyTheJudgeWasUnsureOfIsWrittenOnTheTurnsLine() throws Exception {
        // L10: the chat's seat has no run trace, and what the judge was unsure of was dropped; it goes on the turn's line of the turn log
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Researcher.Drive words = new Researcher.Drive() {
            @Override public ObjectNode chat(ArrayNode messages, ArrayNode tools, int maxTokens, String toolChoice) {
                ObjectNode m = M.createObjectNode(); m.put("role", "assistant"); m.put("content", "Sorry, I can't find anything on the shelves about that."); return m;
            }
            @Override public String classify(ArrayNode messages, int maxTokens) { return ""; }
            @Override public int contextWindow() { return 16384; }
        };
        Researcher.Drive watched = Declines.watch(words, "chat", "placeholder-model", new DeclineJudge(new Judge("m", body -> DeclinesTest.judged(0.55)), null), null);
        String reply = new Librarian(store, watched, Librarian.person(), Librarian.Session.open(store)).say("a placeholder question");
        assertEquals("Sorry, I can't find anything on the shelves about that.", reply, "not sure: the reply goes on as it was");
        String turns = Files.readString(store.root().resolve("catalog").resolve("chat-turns.jsonl"), StandardCharsets.UTF_8);
        assertTrue(turns.contains("\"decline_unsure\"") && turns.contains("\"seat\":\"chat\""), turns);
    }

    @Test
    void theNightlySeatsAndTheStepsOutsideARunNoteOnTheCrewsLog() throws Exception {
        // L10: the explorer, the bridges and the protocol's own seat have no trace either; they write on the crews log
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Researcher.Drive words = new Researcher.Drive() {
            @Override public ObjectNode chat(ArrayNode messages, ArrayNode tools, int maxTokens, String toolChoice) { return M.createObjectNode(); }
            @Override public String classify(ArrayNode messages, int maxTokens) { return "Sorry, I cannot tell from these sources."; }
            @Override public int contextWindow() { return 16384; }
        };
        Researcher.Drive watched = Declines.watch(words, "judge", "placeholder-model", new DeclineJudge(new Judge("m", body -> DeclinesTest.judged(0.55)), null), Declines.toCrewsLog(store, "explorer"));
        watched.prose(M.createArrayNode().add(M.createObjectNode().put("role", "user").put("content", "x")), 100);
        String log = Files.readString(store.root().resolve("catalog").resolve("crews.log"), StandardCharsets.UTF_8);
        assertTrue(log.contains("\texplorer\t") && log.contains("decline_unsure") && log.contains("I cannot tell from these sources"), log);
    }
}
