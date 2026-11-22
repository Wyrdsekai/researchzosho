package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The chat's table in two levels (0.8.1): the whole table where it fits the model's window, else eight tools in full and every other tool in
 * one line under library_tool. Every name of the full table stays runnable and nothing else is: whether
 * a tool sits on the table in full, is called directly, or is opened through library_tool first, a call with an address nobody gave is
 * refused, a call that writes is held for the person's yes with the question a person sees today, and a name on no list is refused.
 */
class ChatTwoLevelsTest {
    static final ObjectMapper M = new ObjectMapper();
    static final String MADE_UP = "https://nobody-gave-this.example.net/page";
    /** The person's words of a turn that writes: every address a call below uses is one they gave. */
    static final String GIVEN = "Here are mine: https://example.net/page https://example.net/a.jpg https://example.net/feed.xml https://example.net/repo https://example.net/v";
    @TempDir Path tmp;

    @AfterEach void unforce() { ChatTable.forced = null; }

    LibraryStore library(String n) throws Exception { LibraryStore s = new LibraryStore(tmp.resolve(n)); s.init(); return s; }

    /** A model that makes the calls given, in order, then says a sentence; what it was sent is kept: the tools of each request. */
    static Researcher.Drive model(List<ObjectNode> steps, List<List<String>> toolsSeen, List<Integer> sizes, int window) {
        Researcher.Drive inner = LibrarianChatTest.scripted(steps, new ArrayList<>());
        return new Researcher.Drive() {
            @Override public ObjectNode chat(ArrayNode messages, ArrayNode tools, int maxTokens, String toolChoice) {
                List<String> names = new ArrayList<>(); for (JsonNode t : tools) names.add(t.path("function").path("name").asText());
                toolsSeen.add(names); sizes.add(tools.toString().length());
                return inner.chat(messages, tools, maxTokens, toolChoice);
            }
            @Override public String classify(ArrayNode messages, int maxTokens) { return ""; }
            @Override public int contextWindow() { return window; }
        };
    }

    /** The full table of a library's chat: the names, as the whole form sends them. */
    List<String> fullNames(LibraryStore store) throws Exception {
        ChatTable.forced = ChatTable.Form.WHOLE;
        List<List<String>> seen = new ArrayList<>();
        new Librarian(store, model(List.of(LibrarianChatTest.say("ok")), seen, new ArrayList<>(), 32768), Librarian.person(), Librarian.Session.open(store)).say("hello");
        return seen.get(0);
    }

    @Test
    void theWholeTableGoesWhereItFitsAndASmallerWindowGetsEightAndTheListOfEveryOtherTool() throws Exception {
        LibraryStore store = library("lib");
        List<String> full = fullNames(store);
        assertFalse(full.contains(ChatTable.OPENER), "the whole table has no library_tool");
        ChatTable.forced = null;
        Map<Integer, ChatTable.Form> expected = new LinkedHashMap<>();
        for (int window : new int[]{131072, 32768, 16384}) {
            List<List<String>> seen = new ArrayList<>(); List<Integer> sizes = new ArrayList<>();
            Librarian.Session s = Librarian.Session.open(store);
            new Librarian(store, model(List.of(LibrarianChatTest.say("ok")), seen, sizes, window), Librarian.person(), s).say("hello");
            List<String> sent = seen.get(0);
            String line = Files.readAllLines(Librarian.turnsFile(store)).get(Files.readAllLines(Librarian.turnsFile(store)).size() - 1);
            ChatTable.Form form = ChatTable.Form.named(M.readTree(line).path("table").asText());
            assertNotNull(form, "the turn's line says the form: " + line + "\n all lines: " + Files.readString(Librarian.turnsFile(store)));
            expected.put(window, form);
            // the whole table where the turn's retry on an overflow would still leave the history its share beside it and the reply
            int room = window - (int) Math.ceil(sizes.get(0) / ChatTable.CHARS_PER_TOKEN) - Librarian.REPLY_TOKENS - Librarian.FIT_MARGIN;
            if (form == ChatTable.Form.WHOLE) { assertTrue(room >= window * Librarian.RETRY_HISTORY_SHARE, window + ": " + room); assertEquals(full, sent); continue; }
            // every name of the full table is on this table or in library_tool's list, and library_tool lists nothing else
            assertTrue(sent.containsAll(form.inFull.stream().filter(full::contains).toList()), form + ": " + sent);
            assertEquals(ChatTable.OPENER, sent.get(sent.size() - 1), "library_tool comes last");
            Set<String> all = new LinkedHashSet<>(sent); all.remove(ChatTable.OPENER);
            List<String> listed = new ArrayList<>();
            ChatTable.Form f = form;
            ArrayNode table = ChatTable.sent(fullTable(store), f, Set.of(), Set.of());
            for (JsonNode e : ChatTable.find(table, ChatTable.OPENER).path("function").path("parameters").path("properties").path("name").path("enum")) listed.add(e.asText());
            all.addAll(listed);
            assertEquals(new LinkedHashSet<>(full), all, form + ": the full list, no more and no less");
        }
        for (ChatTable.Form f : ChatTable.Form.values())
            System.out.println("FORM " + f.said + ": " + ChatTable.tokens(ChatTable.sent(fullTable(store), f, Set.of(), Set.of()).toString()) + " tokens estimated");
        assertEquals(ChatTable.Form.WHOLE, expected.get(131072), "a large window takes the whole table");
        assertEquals(ChatTable.Form.WHOLE, expected.get(32768), "this release's model, served with 32,768, keeps the whole table");
        assertEquals(ChatTable.Form.EIGHT, expected.get(16384), "16,384: the whole table does not fit, eight in full with the list");
        // where the rule turns: the smallest window the whole table fits, and the one below it
        ArrayNode whole = fullTable(store);
        int w = 8000; while (!ChatTable.wholeFits(w, whole)) w++;
        assertTrue(w > 16384 && w < 32768, "the whole table fits from " + w);
        assertEquals(ChatTable.Form.WHOLE, ChatTable.formFor(w, whole));
        assertEquals(ChatTable.Form.EIGHT, ChatTable.formFor(w - 1, whole));
        System.out.println("the whole table fits from a window of " + w);
        // the setting for tests and the battery wins over the rule
        ChatTable.forced = ChatTable.Form.EIGHT;
        List<List<String>> seen = new ArrayList<>();
        new Librarian(store, model(List.of(LibrarianChatTest.say("ok")), seen, new ArrayList<>(), 131072), Librarian.person(), Librarian.Session.open(store)).say("hello");
        assertEquals(ChatTable.EIGHT.size() + 1, seen.get(0).size(), seen.get(0).toString());
        // the instructions say how a tool they name is opened, only when the table lists tools
        assertTrue(Files.readString(Librarian.turnsFile(store)).contains("\"table\":\"8\""));
    }

    ArrayNode fullTable(LibraryStore store) {
        ArrayNode full = Librarian.tools(store);
        for (Instrument i : Instruments.forChat(store)) full.add(Brain.fn(i.name(), ChatTable.words(i), i.schema(M)));
        return full;
    }

    /** One turn: the calls given, then a sentence. What came back of each call, the reply, and the question the turn left for a yes. */
    record Turn(List<String> results, String reply, String held, List<List<String>> toolsSeen) { }

    Turn turn(LibraryStore store, ChatTable.Form form, String words, ObjectNode... calls) throws Exception {
        ChatTable.forced = form;
        List<ObjectNode> steps = new ArrayList<>(List.of(calls)); steps.add(LibrarianChatTest.say("It waits for your answer."));
        List<List<String>> seen = new ArrayList<>();
        Librarian.Session s = Librarian.Session.open(store);
        String reply = new Librarian(store, model(steps, seen, new ArrayList<>(), 32768), Librarian.person(), s).say(words);
        List<String> results = new ArrayList<>();
        for (JsonNode m : s.messages()) if ("tool".equals(m.path("role").asText())) results.add(m.path("content").asText());
        String held = null;
        for (String[] o : s.pending()) if (o[0].startsWith(Librarian.ACT)) held = o[2];
        return new Turn(results, reply, held, seen);
    }

    static ObjectNode call(String name, String args) throws Exception { return LibrarianChatTest.tool(name, args); }
    static ObjectNode open(String name) throws Exception { return LibrarianChatTest.tool(ChatTable.OPENER, "{\"name\":\"" + name + "\"}"); }

    /** A call of each tool that writes or starts work, each held for the person's yes on the whole table (Librarian.waitsForYes). */
    static final Map<String, String> WRITES = Map.ofEntries(
            Map.entry("library_research", "{\"question\":\"Why did the ferry close?\"}"), Map.entry("library_translate", "{\"raw\":\"x.md\",\"to\":\"ja\"}"),
            Map.entry("library_tell", "{\"subject\":\"the ferry\",\"text\":\"My grandmother crossed in 1952.\"}"),
            Map.entry("library_submit", "{\"claim\":\"The ferry closed in 1968.\",\"sources\":[\"https://example.net/page\"]}"),
            Map.entry("library_items", "{\"text\":\"a\\nb\"}"), Map.entry("library_check", "{\"text\":\"my draft\"}"), Map.entry("library_questions", "{\"text\":\"why?\"}"),
            Map.entry("library_absorb", "{\"text\":\"User: hi\\nAssistant: hello\"}"), Map.entry("library_meeting", "{\"text\":\"minutes\"}"),
            Map.entry("library_reading", "{\"text\":\"10.1000/x\"}"), Map.entry("library_bookmarks", "{\"text\":\"https://example.net/page\"}"),
            Map.entry("library_survey", "{\"url\":\"https://example.net/repo\"}"), Map.entry("library_add", "{\"url\":\"https://example.net/page\"}"),
            Map.entry("library_images", "{\"op\":\"keep\",\"images\":[\"https://example.net/a.jpg\"]}"), Map.entry("library_inbox", "{\"op\":\"accept\",\"ids\":[\"F-0001\"]}"),
            Map.entry("library_frontier", "{\"op\":\"add\",\"question\":\"why?\"}"), Map.entry("library_bridges", "{\"op\":\"accept\",\"question\":\"q\"}"),
            Map.entry("library_seeks", "{\"op\":\"merge\",\"group\":\"g\"}"), Map.entry("library_dossier", "{\"op\":\"watch\",\"subject\":\"the ferry company\"}"),
            Map.entry("library_owned", "{\"op\":\"forget\",\"file\":\"x.csv\"}"), Map.entry("library_settings", "{\"op\":\"set\",\"name\":\"RESEARCHZOSHO_OFFER_MINUTES\",\"value\":\"20\"}"),
            Map.entry("library_share", "{\"op\":\"off\"}"), Map.entry("library_models", "{\"op\":\"remove\",\"name\":\"ds\"}"),
            Map.entry("library_sources", "{\"op\":\"add\",\"address\":\"https://example.net/feed.xml\"}"), Map.entry("library_serials", "{\"op\":\"add\",\"name\":\"ferry\",\"query\":\"ferry news\"}"),
            Map.entry("library_job", "{\"op\":\"stop\",\"job_id\":\"J-0001\"}"), Map.entry("homes", "{\"words\":\"a house to buy in Cambridge\"}"),
            Map.entry("look", "{\"video\":\"https://example.net/v\",\"what\":\"a ferry\"}"));

    @Test
    void everyNameIsRunnableInEveryFormAndNothingElseIsWithTodaysChecksAndTodaysQuestion() throws Exception {
        LibraryStore store = library("lib");
        List<String> full = fullNames(store);
        assertTrue(full.containsAll(WRITES.keySet()), "every tool that writes is on the full list: " + full);
        // today's question for each call that writes: the whole table
        Map<String, String> today = new LinkedHashMap<>();
        for (var w : WRITES.entrySet()) {
            Turn t = turn(store, ChatTable.Form.WHOLE, GIVEN, call(w.getKey(), w.getValue()));
            assertNotNull(t.held(), w.getKey() + " is held on the whole table: " + t.results());
            today.put(w.getKey(), t.held());
        }
        int checked = 0;
        for (ChatTable.Form form : ChatTable.Form.values()) {
            for (String name : full) {
                boolean inFull = form == ChatTable.Form.WHOLE || form.inFull.contains(name);
                // the ways a tool is reached in this form: on the table in full; or called directly, and opened through library_tool first
                List<List<ObjectNode>> ways = new ArrayList<>();
                if (inFull) ways.add(List.of()); else { ways.add(List.of()); ways.add(List.of(open(name))); }
                for (List<ObjectNode> before : ways) {
                    String how = form + " " + name + (inFull ? " in full" : before.isEmpty() ? " called directly" : " opened through library_tool");
                    // an address nobody gave: refused, nothing run
                    List<ObjectNode> calls = new ArrayList<>(before); calls.add(call(name, "{\"url\":\"" + MADE_UP + "\"}"));
                    Turn t = turn(store, form, "Do it.", calls.toArray(ObjectNode[]::new));
                    String last = t.results().get(t.results().size() - 1);
                    assertEquals(GivenAddresses.refused(MADE_UP), last, how + ": an address nobody gave");
                    assertEquals(null, t.held(), how + ": nothing is asked");
                    if (!before.isEmpty()) assertTrue(t.results().get(0).startsWith(name + " is on your table now"), how + ": " + t.results().get(0));
                    // a call that writes: held for the person's yes, with today's question
                    if (WRITES.containsKey(name)) {
                        calls = new ArrayList<>(before); calls.add(call(name, WRITES.get(name)));
                        Turn w = turn(store, form, GIVEN, calls.toArray(ObjectNode[]::new));
                        assertEquals(today.get(name), w.held(), how + ": the question a person sees");
                        assertTrue(w.results().get(w.results().size() - 1).startsWith("NOT DONE YET"), how + ": " + w.results());
                    }
                    checked++;
                }
            }
            // a name on no list: refused, called directly and through library_tool
            for (String none : List.of("library_subscribe", "library_access", "library_mcp", "shell")) {
                Turn t = turn(store, form, "Do it.", call(none, "{}"));
                assertTrue(t.results().get(0).startsWith("ERROR: no tool named " + none + " is on the table of this conversation"), form + " " + none + ": " + t.results());
                if (form != ChatTable.Form.WHOLE) {
                    Turn o = turn(store, form, "Do it.", open(none), call(none, "{}"));
                    assertTrue(o.results().get(0).startsWith("ERROR: no tool named " + none) && o.results().get(1).startsWith("ERROR: no tool named " + none), form + " " + none + " through library_tool: " + o.results());
                } else {
                    Turn o = turn(store, form, "Do it.", open("library_search"));
                    assertTrue(o.results().get(0).startsWith("ERROR: no tool named library_tool is on the table"), form + " has no library_tool: " + o.results());
                }
            }
        }
        assertTrue(checked > 140, "ways checked: " + checked);
    }

    @Test
    void everyOneLineNamesEveryActionOfItsTool() throws Exception {
        // an action is a value of the tool's op argument, named by that word in its line, read from the tool's own schema: a line is all
        // the model has of a listed tool (my_shelf's dna and refresh, survey's pick and do were missing, 2026-11-22)
        LibraryStore store = library("lib");
        List<String> missing = new ArrayList<>();
        for (JsonNode t : fullTable(store)) {
            String name = t.path("function").path("name").asText();
            String line = ChatTable.line(name, t);
            for (JsonNode op : t.path("function").path("parameters").path("properties").path("op").path("enum"))
                if (!Pattern.compile("(?i)(?<![a-z_])" + Pattern.quote(op.asText()) + "(?![a-z_])").matcher(line).find()) missing.add(name + ": " + op.asText());
        }
        assertEquals(List.of(), missing, "actions a tool's one line does not name");
    }

    @Test
    void anOpenedToolJoinsTheTableAtItsEndAndStaysForTheConversationAndAWrongCallRunsNothing() throws Exception {
        LibraryStore store = library("lib");
        ChatTable.forced = ChatTable.Form.EIGHT;
        List<List<String>> seen = new ArrayList<>();
        Librarian.Session s = Librarian.Session.open(store);
        var drive = model(List.of(open("library_translate"), call("library_translate", "{\"raw\":\"x.md\",\"to\":\"ja\"}"), LibrarianChatTest.say("It waits."),
                LibrarianChatTest.say("Nothing else.")), seen, new ArrayList<>(), 32768);
        Librarian lib = new Librarian(store, drive, Librarian.person(), s);
        lib.say("I need the kiln notes in Japanese.");
        assertFalse(seen.get(0).contains("library_translate"), "listed, not in full, before it is opened: " + seen.get(0));
        assertEquals("library_translate", seen.get(1).get(seen.get(1).size() - 1), "opened: at the end of the next request's table: " + seen.get(1));
        String line = Files.readAllLines(Librarian.turnsFile(store)).get(0);
        assertTrue(line.contains("\"tools\":[\"library_tool\",\"library_translate\"]") && line.contains("\"table\":\"8\""), line);
        new Librarian(store, drive, Librarian.person(), s).say("no");   // the held question answered
        new Librarian(store, drive, Librarian.person(), s).say("and now?");
        assertTrue(seen.get(seen.size() - 1).contains("library_translate"), "still on the table in the conversation's next turn: " + seen.get(seen.size() - 1));
        // called directly with arguments the tool does not take: nothing runs, the form joins the table
        Turn t = turn(store, ChatTable.Form.EIGHT, "The kiln notes, in Japanese, please.", call("library_translate", "{\"document\":\"x.md\",\"language\":\"ja\"}"));
        assertTrue(t.results().get(0).startsWith("NOT RUN: library_translate takes other arguments (document, language)"), t.results().toString());
        assertEquals(null, t.held());
        assertEquals("library_translate", t.toolsSeen().get(1).get(t.toolsSeen().get(1).size() - 1));
    }
}
