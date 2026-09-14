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

import static org.junit.jupiter.api.Assertions.*;

/** The Librarian you can talk to: tools in the loop, the reply check, sessions that persist, a run filed on a yes. */
class LibrarianChatTest {
    static final ObjectMapper M = new ObjectMapper();
    @TempDir Path tmp;

    /** A scripted drive: each entry is what the model does on that call — a tool call, or words. */
    static Researcher.Drive scripted(List<ObjectNode> steps, List<ArrayNode> seen) {
        return new Researcher.Drive() {
            int i = 0;
            @Override public ObjectNode chat(ArrayNode messages, ArrayNode tools, int maxTokens, String toolChoice) {
                seen.add(messages.deepCopy());
                ObjectNode step = steps.get(Math.min(i++, steps.size() - 1)).deepCopy();
                if (step.has("tool") && "none".equals(toolChoice)) { ObjectNode m = M.createObjectNode(); m.put("role", "assistant"); m.put("content", "I looked, and could not finish."); return m; }
                ObjectNode m = M.createObjectNode(); m.put("role", "assistant");
                if (step.has("tool")) {
                    m.put("content", "");
                    ObjectNode tc = m.putArray("tool_calls").addObject(); tc.put("id", "c" + i); tc.put("type", "function");
                    tc.putObject("function").put("name", step.get("tool").asText()).put("arguments", step.path("args").toString());
                } else m.put("content", step.get("say").asText());
                return m;
            }
            @Override public String classify(ArrayNode messages, int maxTokens) { return ""; }
            @Override public int contextWindow() { return 16384; }
        };
    }
    static ObjectNode tool(String name, String argsJson) throws Exception { ObjectNode o = M.createObjectNode(); o.put("tool", name); o.set("args", M.readTree(argsJson)); return o; }
    static ObjectNode say(String words) { ObjectNode o = M.createObjectNode(); o.put("say", words); return o; }

    LibraryStore libraryWithAFinding() throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Finding f = new Finding("F-0001-gears", "The Antikythera gears were cut by hand with a file", List.of(), Finding.State.accepted, Finding.ClaimType.extraction,
                Finding.Confidence.medium, "model:test", "2026-09-13T00:00:00Z", "2026-09-13", Finding.Volatility.slow, "",
                List.of(new Finding.Source("https://example.org/gears", "n/a", "the gears")), List.of(), null,
                "The gears were cut by hand; tooth spacing varies by 0.3 mm.\n");
        store.write(f);
        new LibrarianIndex(store).rebuild();
        return store;
    }

    @Test
    void aQuestionGoesThroughAToolAndTheReplyCarriesTheAnswer() throws Exception {
        LibraryStore store = libraryWithAFinding();
        List<ArrayNode> seen = new ArrayList<>();
        var drive = scripted(List.of(tool("library_search", "{\"query\":\"antikythera gears\"}"), say("The shelves hold one finding: the gears were cut by hand [F-0001-gears]. Would you like the source?")), seen);
        Librarian lib = new Librarian(store, drive, Librarian.person(), Librarian.Session.open(store));
        String reply = lib.say("how were the gears cut?");
        assertTrue(reply.contains("[F-0001-gears]"), reply);
        assertEquals(2, seen.size(), "one tool round, then the words");
        JsonNode second = seen.get(1);
        assertEquals("tool", second.get(second.size() - 1).path("role").asText(), "the tool result was in the model's context");
        assertTrue(second.get(second.size() - 1).path("content").asText().contains("F-0001-gears"), "the search found the finding");
        assertEquals("system", seen.get(0).get(0).path("role").asText());
        assertTrue(seen.get(0).get(0).path("content").asText().contains("I don't know"), "the rules are in the prompt");
        // the ledger line
        String ledger = Files.readString(store.root().resolve("catalog").resolve("chat-turns.jsonl"));
        assertTrue(ledger.contains("\"tools\":[\"library_search\"]") && ledger.contains("\"unbacked\":0"), ledger);
    }

    @Test
    void aFigureNoToolResultHoldsIsAdmittedAsAGuess() throws Exception {
        LibraryStore store = libraryWithAFinding();
        var drive = scripted(List.of(tool("library_search", "{\"query\":\"gears\"}"), tool("library_get", "{\"id\":\"F-0001-gears\"}"), say("The tooth spacing varies by 0.3 mm [F-0001-gears], and the mechanism weighs 2.4 kg.")), new ArrayList<>());
        Librarian lib = new Librarian(store, drive, Librarian.person(), Librarian.Session.open(store));
        String reply = lib.say("tell me about the gears");
        assertTrue(reply.contains("2.4 kg"), reply);
        assertTrue(reply.contains("is not in anything I looked up"), reply);
        assertFalse(reply.contains("0.3 mm is not"), "the spacing IS in the finding: " + reply);
    }

    @Test
    void aSessionPersistsAndResumes() throws Exception {
        LibraryStore store = libraryWithAFinding();
        Librarian.Session s = Librarian.Session.open(store);
        var drive = scripted(List.of(say("Yes. The shelves hold one finding on that.")), new ArrayList<>());
        new Librarian(store, drive, Librarian.person(), s).say("anything on gears?");
        Librarian.Session again = Librarian.Session.resume(store, s.id);
        assertNotNull(again);
        assertEquals(2, again.messages().size());
        assertEquals("anything on gears?", again.title());
        assertEquals(s.id, Librarian.Session.latest(store).id);
        List<ArrayNode> seen = new ArrayList<>();
        new Librarian(store, scripted(List.of(say("Why: because one source says so.")), seen), Librarian.person(), again).say("why?");
        assertEquals(4, seen.get(0).size(), "system + the earlier turn + the follow-up: the thread is in the context");
    }

    @Test
    void findOutFilesAResearchRun() throws Exception {
        LibraryStore store = libraryWithAFinding();
        var drive = scripted(List.of(tool("library_research", "{\"question\":\"How were the Antikythera gears cut?\",\"mode\":\"broad\",\"max_minutes\":30}"), say("Filed as a run; it takes a while. I will tell you what it found when you ask.")), new ArrayList<>());
        String reply = new Librarian(store, drive, Librarian.person(), Librarian.Session.open(store)).say("find out how the gears were cut");
        assertTrue(reply.contains("Filed"), reply);
        Jobs jobs = new Jobs(store, j -> { throw new IllegalStateException("read only"); });
        assertEquals(1, jobs.active().size(), "one queued run");
        assertEquals("queued", jobs.active().get(0).path("state").asText());
    }

    @Test
    void theToolsAreTheLibrarysAndNothingElse() {
        ArrayNode tools = Librarian.tools();
        List<String> names = new ArrayList<>();
        for (JsonNode t : tools) names.add(t.path("function").path("name").asText());
        assertTrue(names.contains("library_ask") && names.contains("library_research") && names.contains("library_inbox"), names.toString());
        assertTrue(names.stream().allMatch(n -> n.startsWith("library_")), names.toString());
        assertFalse(names.contains("library_access") || names.contains("library_request_access"), "access administration is not a conversation");
    }
}

class LibrarianChatShapeTest {
    static final ObjectMapper M2 = new ObjectMapper();

    @Test
    void aLongListIsTrimmedToItsFirstItemsWithACount() throws Exception {
        ObjectNode r = M2.createObjectNode();
        ArrayNode items = r.putArray("items");
        for (int i = 0; i < 116; i++) items.addObject().put("id", "F-" + i);
        r.put("paused", false);
        JsonNode t = Librarian.trimResult(r);
        assertEquals(Librarian.LIST_CAP, t.path("items").size());
        assertEquals(116, t.path("items_total").asInt());
        assertTrue(t.path("items_shown").asText().startsWith(Librarian.LIST_CAP + " of 116 items shown"), t.toString());
        assertFalse(t.path("paused").asBoolean());
    }

    @Test
    void thinkingIsNeverTheReplyAndAnEmptyAnswerGetsOneNudge() throws Exception {
        java.nio.file.Path tmp = java.nio.file.Files.createTempDirectory("rzchat");
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        List<ArrayNode> seen = new ArrayList<>();
        Researcher.Drive drive = new Researcher.Drive() {
            int i = 0;
            @Override public ObjectNode chat(ArrayNode messages, ArrayNode tools, int maxTokens, String toolChoice) {
                seen.add(messages.deepCopy());
                ObjectNode m = M2.createObjectNode(); m.put("role", "assistant");
                if (i++ == 0) { m.put("content", ""); m.put("reasoning_content", "The user asks about drafts. Let me think about which finding they mean…"); }
                else m.put("content", "None of those is a draft; they are accepted or disputed.");
                return m;
            }
            @Override public String classify(ArrayNode messages, int maxTokens) { return ""; }
            @Override public int contextWindow() { return 16384; }
        };
        String reply = new Librarian(store, drive, Librarian.person(), Librarian.Session.open(store)).say("why is that a draft?");
        assertEquals("None of those is a draft; they are accepted or disputed.", reply);
        assertEquals(2, seen.size());
        assertEquals("Say it in words now, briefly, from what you have looked up.", seen.get(1).get(seen.get(1).size() - 1).path("content").asText());
        assertFalse(reply.contains("Let me think"), "the model's thinking is never shown as its reply");
    }
}

class LibrarianChatFitTest {
    static final ObjectMapper M3 = new ObjectMapper();

    @Test
    void theContextIsFittedToTheWindowByClearingOlderLookUpsFirst() throws Exception {
        java.nio.file.Path tmp = java.nio.file.Files.createTempDirectory("rzfit");
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        List<ArrayNode> seen = new ArrayList<>();
        String big = "x ".repeat(6000);   // ~3000 tokens a result on an 8000-token window
        Researcher.Drive drive = new Researcher.Drive() {
            int i = 0;
            @Override public ObjectNode chat(ArrayNode messages, ArrayNode tools, int maxTokens, String toolChoice) {
                seen.add(messages.deepCopy());
                ObjectNode m = M3.createObjectNode(); m.put("role", "assistant");
                if (i++ % 2 == 0) { m.put("content", ""); ObjectNode tc = m.putArray("tool_calls").addObject(); tc.put("id", "c" + i); tc.put("type", "function"); tc.putObject("function").put("name", "library_status").put("arguments", "{}"); }
                else m.put("content", "Done [F-0001-x].");
                return m;
            }
            @Override public String classify(ArrayNode messages, int maxTokens) { return ""; }
            @Override public int contextWindow() { return 8000; }
        };
        Librarian lib = new Librarian(store, drive, Librarian.person(), Librarian.Session.open(store)) {
            @Override String call(String name, JsonNode args) { return big; }
        };
        lib.say("one"); lib.say("two"); lib.say("three");
        ArrayNode last = seen.get(seen.size() - 1);
        int total = 0; int cleared = 0;
        for (JsonNode m : last) { total += Researcher.estTokens(m.path("content").asText("")); if (m.path("content").asText("").startsWith("[an earlier look-up, cleared")) cleared++; }
        assertTrue(total <= 8000 * 0.55 + 200, "fitted inside the window: " + total);
        assertTrue(cleared >= 1, "the oldest look-ups were cleared, not the words");
        assertEquals("system", last.get(0).path("role").asText());
    }
}

class LibrarianChatResultShapeTest {
    static final ObjectMapper M4 = new ObjectMapper();

    @Test
    void theCountComesBeforeTheListAndTheListFitsTheCap() throws Exception {
        ObjectNode r = M4.createObjectNode();
        r.put("library_id", "lib"); ArrayNode items = r.putArray("items");
        for (int i = 0; i < 112; i++) items.addObject().put("id", "F-0" + (100 + i) + "-a-long-slug-for-a-draft").put("title", "A draft claim number " + i + " with a title of ordinary length").put("state", "draft");
        JsonNode t = Librarian.trimResult(r, 3000);
        String text = t.toString();
        assertTrue(text.length() <= 3400, "fits: " + text.length());
        assertEquals(112, t.path("items_total").asInt());
        assertTrue(text.indexOf("items_total") < text.indexOf("\"items\":["), "the count comes first");
        assertTrue(t.path("items").size() > 0 && t.path("items").size() < 112);
        assertTrue(t.path("items_shown").asText().contains(" of 112 items shown"), t.path("items_shown").asText());
    }

    @Test
    void toolMarkupIsNeverAReply() {
        assertEquals("", Librarian.withoutToolMarkup("<tool_call>\n<function=library_get>\n<parameter=id>\nF-1\n</parameter>\n</function>\n</tool_call>"));
        assertEquals("The shelves hold two findings [F-1].", Librarian.withoutToolMarkup("The shelves hold two findings [F-1]. <tool_call><function=library_get><parameter=id>F-2</parameter></function></tool_call>"));
        assertEquals("Here is what I have.", Librarian.withoutToolMarkup("Here is what I have. <tool_call>\n<function=library_search>"));
    }
}
