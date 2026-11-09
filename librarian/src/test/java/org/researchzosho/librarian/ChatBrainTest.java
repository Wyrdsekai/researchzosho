package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import org.researchzosho.tools.Tool;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The chat decides through the brain: the fields' instruments are on its table, and a home ask runs the home search, not a research run. */
class ChatBrainTest {
    static final ObjectMapper M = new ObjectMapper();
    @TempDir Path tmp;

    @AfterEach void live() { Instruments.HOMES = Instruments::homesLive; }

    static Researcher.Drive scripted(List<ObjectNode> steps, List<List<String>> toolsSeen, List<ArrayNode> messagesSeen) {
        return new Researcher.Drive() {
            int i = 0;
            @Override public ObjectNode chat(ArrayNode messages, ArrayNode tools, int maxTokens, String toolChoice) {
                List<String> names = new ArrayList<>(); for (JsonNode t : tools) names.add(t.path("function").path("name").asText());
                toolsSeen.add(names); messagesSeen.add(messages.deepCopy());
                ObjectNode step = steps.get(Math.min(i++, steps.size() - 1));
                ObjectNode m = M.createObjectNode(); m.put("role", "assistant");
                if (step.has("tool") && !"none".equals(toolChoice)) {
                    m.put("content", "");
                    ObjectNode tc = m.putArray("tool_calls").addObject(); tc.put("id", "c" + i); tc.put("type", "function");
                    tc.putObject("function").put("name", step.get("tool").asText()).put("arguments", step.path("args").toString());
                } else m.put("content", step.path("say").asText("I looked, and could not finish."));
                return m;
            }
            @Override public String classify(ArrayNode messages, int maxTokens) { return ""; }
            @Override public int contextWindow() { return 16384; }
            @Override public String model() { return "qwen3.5-9b"; }
        };
    }

    @Test void aHomeAskRunsTheHomeSearchFromTheChatsTable() throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        List<String> ran = new ArrayList<>();
        Instruments.HOMES = (s, name, words, stop) -> { ran.add(name + " | " + words); return "3 homes fit; the search is kept."; };
        ObjectNode homes = M.createObjectNode(); homes.put("tool", "homes"); homes.set("args", M.readTree("{\"words\":\"a house to buy in Cambridge\"}"));
        ObjectNode say = M.createObjectNode(); say.put("say", "Three homes fit; the Home page lists them with a link on each.");
        List<List<String>> toolsSeen = new ArrayList<>(); List<ArrayNode> messagesSeen = new ArrayList<>();
        Librarian lib = new Librarian(store, scripted(List.of(homes, say), toolsSeen, messagesSeen), Librarian.person(), Librarian.Session.open(store));
        String reply = lib.say("find me a house to buy in Cambridge");
        assertEquals("Three homes fit; the Home page lists them with a link on each.", reply);
        assertEquals(List.of("a house to buy in Cambridge | a house to buy in Cambridge"), ran, "the home search ran, named from the words");
        List<String> offered = toolsSeen.get(0);
        assertTrue(offered.containsAll(List.of("homes", "look", "records", "board", "dossier", "search", "open", "shelf", "library_research")), offered.toString());
        assertFalse(offered.contains("who"), "no family work in this library, so no who: " + offered);
        assertFalse(offered.contains("ask") || offered.contains("pursue"), "the chat offers none of the brain's own tools; the reply is the question");
        String system = messagesSeen.get(0).get(0).path("content").asText();
        assertTrue(system.contains("A home to buy or rent is homes, not a research run") && system.contains("Copy the shape of these calls exactly") && system.contains("homes {\"words\": "), "a small model's words carry the calls to copy:\n" + system.substring(Math.max(0, system.length() - 900)));
        String toolResult = null;
        for (JsonNode m : lib.session().messages) if ("tool".equals(m.path("role").asText())) toolResult = m.path("content").asText();
        assertEquals("3 homes fit; the search is kept.", toolResult);
    }

    @Test void aRememberedVideoIsSearchedForAndThenLookedAtFromTheChat() throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        List<String> looked = new ArrayList<>();
        Instruments.LOOK = new Tool() {
            @Override public String name() { return "look"; }
            @Override public String description() { return "a stand-in"; }
            @Override public ObjectNode parametersSchema(ObjectMapper m) { return M.createObjectNode(); }
            @Override public String execute(JsonNode args) { looked.add(args.path("video").asText() + " | " + args.path("what").asText()); return "silent: found · measurements at the start: found at 0:30 · read 29 frames in 2 minutes"; }
        };
        try {
            ObjectNode search = M.createObjectNode(); search.put("tool", "search"); search.set("args", M.readTree("{\"where\":\"shelves\",\"query\":\"tote bag tutorial\"}"));
            ObjectNode look = M.createObjectNode(); look.put("tool", "look"); look.set("args", M.readTree("{\"video\":\"https://www.youtube.com/watch?v=dQw4w9WgXcQ\",\"what\":\"silent; the measurements on screen at the start\",\"sparse\":true}"));
            ObjectNode say = M.createObjectNode(); say.put("say", "It is this one: silent, the measurements at 0:30.");
            List<List<String>> toolsSeen = new ArrayList<>(); List<ArrayNode> messagesSeen = new ArrayList<>();
            Librarian lib = new Librarian(store, scripted(List.of(search, look, say), toolsSeen, messagesSeen), Librarian.person(), Librarian.Session.open(store));
            String reply = lib.say("find the sewing video I watched: a silent tote bag tutorial with the measurements on screen at the start");
            assertEquals("It is this one: silent, the measurements at 0:30.", reply);
            assertEquals(List.of("https://www.youtube.com/watch?v=dQw4w9WgXcQ | silent; the measurements on screen at the start"), looked, "the look ran on the candidate");
            String system = messagesSeen.get(0).get(0).path("content").asText();
            assertTrue(system.contains("A video the person remembers but cannot name is a research run: file it with library_research, goal choose") && system.contains("A video the person names or links is look, sparse, here in the turn"), system);
            List<String> results = new ArrayList<>();
            for (JsonNode m : lib.session().messages) if ("tool".equals(m.path("role").asText())) results.add(m.path("content").asText());
            assertEquals(2, results.size(), results.toString());
            assertTrue(results.get(1).contains("found at 0:30"));
        } finally { Instruments.LOOK = null; }
    }

    @Test void aLargeModelGetsTheTableWithTheReasons() throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib2")); store.init();
        ObjectNode say = M.createObjectNode(); say.put("say", "Nothing on the shelves about that.");
        List<List<String>> toolsSeen = new ArrayList<>(); List<ArrayNode> messagesSeen = new ArrayList<>();
        Researcher.Drive big = new Researcher.Drive() {
            final Researcher.Drive inner = scripted(List.of(say), toolsSeen, messagesSeen);
            @Override public ObjectNode chat(ArrayNode messages, ArrayNode tools, int maxTokens, String toolChoice) { return inner.chat(messages, tools, maxTokens, toolChoice); }
            @Override public String classify(ArrayNode messages, int maxTokens) { return ""; }
            @Override public int contextWindow() { return 32000; }
            @Override public String model() { return "qwen3.8-27b"; }
        };
        new Librarian(store, big, Librarian.person(), Librarian.Session.open(store)).say("anything on gears?");
        String system = messagesSeen.get(0).get(0).path("content").asText();
        assertTrue(system.contains("look: when the question is about what a video shows") && !system.contains("look: Reads a video's frames") && system.contains("weigh a costly instrument"), system.substring(Math.max(0, system.length() - 600)));
        assertFalse(system.contains("Copy the shape of these calls"), "the large tier has no literal calls");
    }
}
