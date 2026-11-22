package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The chat on a model server that gives each conversation little room (2026-11-18: gpt-oss-20b served with 32k for two conversations,
 * 16,384 tokens each, and the chat's tools and instructions about 16,000 of them). The tools go with every request, so the second try
 * after the server's refusal cuts the history to what is left beside them; and when even the least a turn can send does not fit, the
 * turn says so, with the server's numbers, instead of failing.
 */
class ChatFitsTest {
    static final ObjectMapper M = new ObjectMapper();
    @TempDir Path tmp;

    /** What the server was sent: the tools' tokens, the instructions' and the whole request's, as llama.cpp would count them here. */
    record Sent(int tools, int instructions, int all) { }

    /** A server like llama.cpp: each conversation holds {@code window} tokens, and a request larger than that is refused with its count. */
    static Researcher.Drive server(int window, List<Sent> sent) {
        return new Researcher.Drive() {
            @Override public ObjectNode chat(ArrayNode messages, ArrayNode tools, int maxTokens, String toolChoice) {
                int t = Researcher.estTokens(tools == null ? "" : tools.toString()), n = t;
                for (JsonNode m : messages) n += Researcher.estTokens(m.path("content").asText("")) + Researcher.estTokens(m.path("tool_calls").toString()) + 8;
                sent.add(new Sent(t, Researcher.estTokens(messages.path(0).path("content").asText("")), n));
                if (n > window) throw new IllegalStateException("drive HTTP 400: {\"error\":{\"code\":400,\"message\":\"request (" + n + " tokens) exceeds the available context size (" + window + " tokens), try increasing it\",\"type\":\"exceed_context_size_error\"}}");
                ObjectNode m = M.createObjectNode(); m.put("role", "assistant"); m.put("content", "The ferry ran from 1911 to 1968."); return m;
            }
            @Override public String classify(ArrayNode messages, int maxTokens) { return ""; }
            @Override public int contextWindow() { return window; }
        };
    }

    LibraryStore library() throws Exception { LibraryStore s = new LibraryStore(tmp.resolve("lib")); s.init(); return s; }

    @Test
    void aTurnWhoseToolsAndInstructionsDoNotFitTheServersRoomSaysSo() throws Exception {
        LibraryStore store = library();
        List<Sent> sent = new ArrayList<>();
        Librarian.Session s = Librarian.Session.open(store);
        // a server with room for less than any form of the table and the instructions (0.8.1: at 16,384 a turn now fits, below)
        String reply = new Librarian(store, server(6000, sent), Librarian.person(), s).say("When did the ferry stop running?");
        assertEquals(2, sent.size(), "the turn as it was, then the least it can send: " + sent);
        assertTrue(reply.contains("6,000") && reply.contains("does not fit"), reply);
        assertTrue(reply.contains(String.format(Locale.ROOT, "%,d", sent.get(1).all())), "the server's own count of the least a turn sends: " + reply);
        assertEquals("library", s.messages().get(s.messages().size() - 1).path("by").asText(), "the library's sentence, not the model's words");
        // the server of 2026-11-18, 16,384 a conversation: the whole table and the instructions were about 16,000 of it and no turn
        // fitted; the table's form is now chosen from the window (ChatTable.formFor), and the turn is answered in one request
        List<Sent> small = new ArrayList<>();
        Librarian.Session s2 = Librarian.Session.open(store);
        String answered = new Librarian(store, server(16384, small), Librarian.person(), s2).say("When did the ferry stop running?");
        assertEquals("The ferry ran from 1911 to 1968.", answered, small.toString());
        assertEquals(1, small.size(), small.toString());
        // the whole table does not fit 16,384 with room for the conversation beside it: the fallback, eight in full with the list
        assertTrue(Files.readString(Librarian.turnsFile(store)).contains("\"table\":\"" + ChatTable.fallback().said + "\""), Files.readString(Librarian.turnsFile(store)));
        assertTrue(small.get(0).all() < 16384 / 2, "the whole request, well inside the window: " + small);
    }

    @Test
    void aTurnThatFitsOnceItsHistoryIsCutBesideTheToolsIsAnswered() throws Exception {
        LibraryStore store = library();
        Librarian.Session s = Librarian.Session.open(store);
        String long800 = "The ferry log for that year runs to many pages. ".repeat(66);
        for (int i = 0; i < 12; i++) {
            ObjectNode u = M.createObjectNode(); u.put("role", "user"); u.put("content", "Question " + i + " about the ferry"); u.put("at", 1L); s.append(u);
            ObjectNode a = M.createObjectNode(); a.put("role", "assistant"); a.put("content", long800); a.put("at", 1L); s.append(a);
        }
        // the whole table, whatever the window: this is the retry that cuts the history beside the table sent, which a window this
        // size would otherwise answer with a smaller form of the table and no retry (0.8.1)
        ChatTable.forced = ChatTable.Form.WHOLE;
        try {
        // what the tools and the instructions take, read off one turn on a server with room for all of it
        List<Sent> probe = new ArrayList<>();
        new Librarian(store, server(1_000_000, probe), Librarian.person(), s).say("And before that?");
        String words = "When did the ferry stop running?";
        Sent p = probe.get(0);
        // room for the tools, the instructions, these words and the reply, and little more: a third of it is more than the history may take
        int window = p.tools() + p.instructions() + Researcher.estTokens(words) + Librarian.REPLY_TOKENS + 512 + 300;
        List<Sent> sent = new ArrayList<>();
        String reply = new Librarian(store, server(window, sent), Librarian.person(), s).say(words);
        assertEquals("The ferry ran from 1911 to 1968.", reply, "answered on the second try: " + sent + " in " + window);
        assertEquals(2, sent.size());
        assertTrue(sent.get(1).all() <= window - Librarian.REPLY_TOKENS, "the second try left the reply its room: " + sent + " in " + window);
        } finally { ChatTable.forced = null; }
    }
}
