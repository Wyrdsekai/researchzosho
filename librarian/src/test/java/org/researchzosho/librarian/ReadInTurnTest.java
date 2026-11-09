package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** "Read this in Japanese" in the chat (0.7.0): the report shown last, or the one named, read in that language through the pages' own mechanism; no model asked. */
class ReadInTurnTest {
    static final ObjectMapper M = new ObjectMapper();

    static Researcher.Drive noModel() {
        return new Researcher.Drive() {
            @Override public ObjectNode chat(ArrayNode messages, ArrayNode tools, int maxTokens, String toolChoice) { throw new AssertionError("the turn is the library's own: the model is not asked"); }
            @Override public String classify(ArrayNode messages, int maxTokens) { return ""; }
            @Override public int contextWindow() { return 16384; }
        };
    }

    @Test void theReportShownLastOrNamedIsReadInTheLanguageAsked(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        String id = "I-0001-why-eclipses-repeat";
        store.write(new Investigation(id, "Why do eclipses repeat?", Finding.State.accepted, "model:test", Instant.now().toString(), List.of(), List.of(), "## Answer\n\nThe saros, every 18 years [1].\n\n## References\n\n[1] https://example.org/saros\n", "en"));
        assertNull(ReadInTurn.parse("what is the saros?"), "a question is not an ask to read in a language");
        assertNull(ReadInTurn.parse("read this in Klingon"), "a language the library does not know is left to the model");
        assertEquals(new ReadInTurn.Ask("this", "ja"), ReadInTurn.parse("Read this in Japanese"));
        assertEquals(new ReadInTurn.Ask("the report", "fr"), ReadInTurn.parse("show me the report in French."));
        assertEquals(new ReadInTurn.Ask("この報告書", "en"), ReadInTurn.parse("この報告書を英語で読みたい"));
        Librarian.Session session = Librarian.Session.open(store);
        Librarian lib = new Librarian(store, noModel(), Librarian.person(), session);
        assertEquals("Which report? Name it by its id, or open it in this conversation first, and say again which language to read it in.", lib.say("read this in Japanese"));
        // the conversation opened the report: a tool's answer names it
        ObjectNode tool = M.createObjectNode(); tool.put("role", "tool"); tool.put("name", "library_get"); tool.put("content", "{\"id\":\"" + id + "\",\"title\":\"Why do eclipses repeat?\"}");
        session.append(tool);
        String reply = lib.say("read this in Japanese");
        assertTrue(reply.startsWith("Translating \"Why do eclipses repeat?\" into Japanese now (J-") && reply.contains("Then open /entry/" + id + "?in=ja;"), reply);
        List<ObjectNode> queued = new Jobs(store, j -> "").active();
        assertEquals(1, queued.size()); assertEquals("translate", queued.get(0).path("kind").asText()); assertEquals(id, queued.get(0).path("args").path("report").asText()); assertEquals("ja", queued.get(0).path("args").path("lang").asText());
        String again = lib.say("この報告書を日本語で");
        assertTrue(again.startsWith("\"Why do eclipses repeat?\" is being translated into Japanese (J-"), "the run already filed is named, not filed twice: " + again);
        assertEquals(1, new Jobs(store, j -> "").active().size());
        assertEquals("\"Why do eclipses repeat?\" is written in English already: /entry/" + id + ".", lib.say("read " + id + " in English"));
        assertTrue(lib.say("read the dossier on Ada Example in German").startsWith("Translating the dossier on Ada Example into German now (J-"));
        assertTrue(session.messages.stream().anyMatch(m -> m.path("role").asText().equals("assistant") && m.path("content").asText().startsWith("Translating \"Why do eclipses repeat?\"")), "the turn is in the conversation");
    }
}
