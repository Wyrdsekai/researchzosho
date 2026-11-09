package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;
import org.researchzosho.mcp.McpServer;
import org.researchzosho.tools.ExaSearchTool;
import org.researchzosho.tools.Firecrawl;
import org.researchzosho.tools.PubMed;
import org.researchzosho.tools.SearchTally;
import org.researchzosho.tools.TavilySearchTool;
import org.researchzosho.tools.WebSearchTool;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A run's own settings said in the chat (0.7.0): the time limit, the turn limit, where the run reads, no paid search service, and the
 * model, read from the person's words in English and Japanese; said back under the reply; put on the job over what the model passed;
 * at the tally's gate for the paid places; and in the report's first lines. Every run is on a scripted stand-in: nothing reaches a model,
 * the network, a clock or the home box.
 */
class RunLimitsTest {

    static final ObjectMapper M = new ObjectMapper();
    /** Three entries of three tiers: the default a B, fable an A, a small local one a C. */
    static final Map<String, ModelEntries.Entry> ENTRIES = Map.of(
            "default", new ModelEntries.Entry("default", "http://homebox:8212", "qwen3.8-27b", null, null, 0, ""),
            "fable", new ModelEntries.Entry("fable", "anthropic", "claude-fable-5-1", null, null, 0, ""),
            "local-9b", new ModelEntries.Entry("local-9b", "http://homebox:8211", "qwen3.5-9b", null, null, 0, ""));

    static RunLimits read(String words) { return RunLimits.fromWords(words, ENTRIES); }

    @Test
    void theTimeLimitIsReadFromEnglishAndJapaneseWords() {
        Map<String, Integer> forms = Map.ofEntries(
                Map.entry("find out what the Saros cycle is, don't exceed 30 minutes", 30), Map.entry("Do not exceed 2 hours on this.", 120),
                Map.entry("within half an hour please", 30), Map.entry("take up to 2 hours", 120), Map.entry("spend ten minutes on this", 10),
                Map.entry("no more than 45 min", 45), Map.entry("an hour and a half at most", 90), Map.entry("30 minutes max", 30), Map.entry("in under 20 minutes", 20),
                Map.entry("1.5 hours tops", 90), Map.entry("limit it to one hour", 60), Map.entry("keep it under 15 minutes", 15), Map.entry("give it two and a half hours", 150),
                Map.entry("a time limit of 25 minutes", 25), Map.entry("stop after 40 minutes", 40), Map.entry("max 10 mins", 10), Map.entry("at most a quarter of an hour", 15),
                Map.entry("サロス周期とは何か調べて。30分以内で。", 30), Map.entry("２時間まで", 120), Map.entry("1時間半以内", 90), Map.entry("最大45分", 45), Map.entry("2時間を超えないで", 120),
                Map.entry("半時間以内", 30), Map.entry("1時間20分まで", 80), Map.entry("サロス周期とは何か、30分以内で調べて", 30),
                Map.entry("find out what the Saros cycle is, within half an hour", 30), Map.entry("find out how the gears were cut and take up to 2 hours", 120));
        for (var f : forms.entrySet()) assertEquals(f.getValue(), read(f.getKey()).maxMinutes(), f.getKey());
        // a limit phrase in a question's own clause is a mention; an instruction's lead is a setting wherever it stands
        for (String s : List.of("why does a total solar eclipse take at most 7 minutes to see?", "find out why the eclipse takes at most 7 minutes", "how long is it, 30 minutes at most?", "30分以内に何が起きるか調べて", "does it take up to 2 hours?"))
            assertEquals(0, read(s).maxMinutes(), s);
        assertEquals(30, read("find out why the eclipse takes 7 minutes, don't exceed 30 minutes").maxMinutes());
        assertEquals(30, read("30分以内で調べてくれますか").maxMinutes(), "a polite request ends in か");
        assertEquals(5, read("what is the Saros cycle? don't exceed 5 minutes, from the shelves only").maxMinutes());
    }

    @Test
    void theTurnLimitIsReadFromEnglishAndJapaneseWords() {
        Map<String, Integer> forms = Map.of("in at most 20 turns", 20, "up to twelve turns", 12, "15 turns max", 15, "no more than 8 model steps", 8, "limit it to 30 steps", 30,
                "20ターン以内", 20, "最大12ターン", 12, "10ステップまで", 10);
        for (var f : forms.entrySet()) assertEquals(f.getValue(), read(f.getKey()).maxTurns(), f.getKey());
        RunLimits both = read("in at most 20 turns and within 30 minutes");
        assertEquals(20, both.maxTurns()); assertEquals(30, both.maxMinutes());
    }

    @Test
    void whereTheRunReadsIsReadFromEnglishAndJapaneseWords() {
        for (String s : List.of("from the shelves only", "find out about the Saros cycle, shelves only.", "without the web", "don't use the web for this", "no web search", "棚だけから", "蔵書のみ", "ウェブは使わずに", "ネット検索なしで"))
            assertEquals("shelves", read(s).sources(), s);
        for (String s : List.of("search the web too", "use the web as well", "look on the web too", "include the web", "the shelves and the web", "ウェブも検索して", "ネットも"))
            assertEquals("both", read(s).sources(), s);
        for (String s : List.of("from the web only", "web only please", "skip the shelves", "ウェブだけ", "棚は使わずに"))
            assertEquals("web", read(s).sources(), s);
    }

    @Test
    void noPaidServiceIsReadFromEnglishAndJapaneseWords() {
        for (String s : List.of("use no paid services for this run", "without paid services", "don't use any paid search", "free services only", "nothing paid", "without spending credits",
                "有料サービスは使わないで", "有料は使わず", "無料のサービスだけで", "お金をかけずに", "課金なしで"))
            assertTrue(read(s).noPaid(), s);
        assertFalse(read("a paid subscription to Nature").noPaid()); assertFalse(read("what the free press wrote").noPaid());
    }

    @Test
    void theModelIsOneOfTheLibrarysEntriesByNameOrTheBigOneWhenOneStandsAbove() {
        assertEquals("fable", read("find out how the gears were cut, with fable").model());
        assertEquals("fable", read("use fable for this").model());
        assertEquals("local-9b", read("using the local-9b model").model());
        assertEquals("fable", read("run it on fable").model());
        assertEquals("fable", read("use the big model for this").model());
        assertEquals("fable", read("一番強いモデルで調べて").model());
        assertEquals("fable", read("サロス周期について、fableで調べて").model());
        assertEquals("local-9b", read("local-9bを使って").model());
        assertEquals("default", read("with the default model").model());
        assertEquals("", read("with default settings").model(), "the bare word default is an ordinary word");
        assertEquals("", read("on local history").model(), "no entry is called local");
        assertEquals("", read("the fable of the tortoise").model(), "a name without the words that choose a model");
        // two entries of the best tier: "the big model" is not clearly one of them
        Map<String, ModelEntries.Entry> two = Map.of("fable", ENTRIES.get("fable"), "kimi", new ModelEntries.Entry("kimi", "openrouter", "moonshotai/kimi-k3", null, null, 0, ""), "default", ENTRIES.get("default"));
        assertEquals("", RunLimits.fromWords("use the big model for this", two).model());
        assertEquals("", RunLimits.fromWords("with fable", Map.of()).model(), "no entries, no model");
    }

    @Test
    void aMentionThatIsNotASettingSetsNothing() {
        for (String s : List.of("why does a total solar eclipse take at most 7 minutes to see?", "what happened in the 30 minutes after the eclipse?", "what is the web of life?",
                "the shelves only hold two books on this", "no web page says when the gears were cut", "turns of the century", "the minute details of the mechanism",
                "how many people work for the free press?", "a 20 minute film about the Saros cycle", "how did they pay for the paid staff?", "the model of the mechanism in the museum"))
            assertEquals(RunLimits.NONE, read(s), s);
        assertTrue(RunLimits.NONE.isEmpty()); assertEquals("", RunLimits.NONE.described());
    }

    @Test
    void theSettingsAreSaidBackInEnglishAndJapanese() {
        RunLimits l = new RunLimits(30, 20, "shelves", true, "fable");
        assertEquals("up to 30 minutes, up to 20 turns, from the shelves only, no paid search services, on the model fable", l.described(Chrome.in("en")));
        assertEquals("最大30分、最大20ターン、棚だけから、有料の検索サービスなし、モデル fable で", l.described(Chrome.in("ja")));
        assertEquals("up to 2 hours, the web too", new RunLimits(120, 0, "both", false, "").described(Chrome.in("en")));
        assertEquals("up to 1 hour, from the web only", new RunLimits(60, 0, "web", false, "").described(Chrome.in("en")));
        assertEquals("最大2時間、ウェブも", new RunLimits(120, 0, "both", false, "").described(Chrome.in("ja")));
        ObjectNode args = M.createObjectNode().put("sources", "both").put("max_minutes", 90);
        l.put(args);
        assertEquals(30, args.path("max_minutes").asInt()); assertEquals(20, args.path("max_turns").asInt()); assertEquals("shelves", args.path("sources").asText());
        assertFalse(args.path("paid").asBoolean(true)); assertEquals("fable", args.path("model").asText());
        assertEquals(new RunLimits(30, 20, "shelves", true, "fable"), RunLimits.of(args), "a job's arguments read back");
        assertEquals(RunLimits.NONE, RunLimits.of(M.createObjectNode().put("sources", "both").put("model_entry", "default")));
    }

    @Test
    void theChatSaysBackWhatItReadAndTheRunItFilesCarriesItOverWhatTheModelPassed(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        List<ArrayNode> seen = new ArrayList<>();
        Librarian lib = new Librarian(store, LibrarianChatTest.scripted(List.of(LibrarianChatTest.tool("library_research", "{\"question\": \"What is the Saros cycle?\", \"max_minutes\": 120, \"sources\": \"both\"}"),
                LibrarianChatTest.say("Filed. I will say when it is done.")), seen), Librarian.person(), Librarian.Session.open(store));
        String reply = lib.say("Find out what the Saros cycle is. Don't exceed 30 minutes, from the shelves only.");
        List<ObjectNode> active = new Jobs(store, j -> "").active();
        JsonNode args = active.get(0).path("args");
        assertEquals(30, args.path("max_minutes").asInt(), "the person's words win over the model's 120: " + args);
        assertEquals("shelves", args.path("sources").asText(), args.toString());
        String id = active.get(0).path("job_id").asText();
        assertTrue(reply.contains("Research run " + id + ", as you asked: up to 30 minutes, from the shelves only."), reply);
        boolean told = false;   // the tool's result as the session keeps it (the model's own copy may be cleared to fit a small window)
        for (ObjectNode m : lib.session().messages()) if ("tool".equals(m.path("role").asText()) && m.path("content").asText().contains("The person's own words set this run: up to 30 minutes, from the shelves only.")) told = true;
        assertTrue(told, "the model's tool result says so too: " + lib.session().messages());
        assertEquals(2, seen.size(), "one tool round, then the words");
        // nothing said: the model's own arguments stand, and nothing is said back
        Librarian two = new Librarian(store, LibrarianChatTest.scripted(List.of(LibrarianChatTest.tool("library_research", "{\"question\": \"How long is the Saros cycle, in days?\", \"max_minutes\": 45}"),
                LibrarianChatTest.say("Filed.")), new ArrayList<>()), Librarian.person(), Librarian.Session.open(store));
        String plain = two.say("Find out how long the Saros cycle is in days.");
        active = new Jobs(store, j -> "").active();
        assertEquals(45, active.get(active.size() - 1).path("args").path("max_minutes").asInt());
        assertFalse(plain.contains("as you asked"), plain);
        // in Japanese, the line under the reply is Japanese
        Chrome.here("ja");
        try {
            Librarian ja = new Librarian(store, LibrarianChatTest.scripted(List.of(LibrarianChatTest.tool("library_research", "{\"question\": \"サロス周期とは何か、その周期はどれほどの長さか\"}"),
                    LibrarianChatTest.say("調査を出しました。")), new ArrayList<>()), Librarian.person(), Librarian.Session.open(store));
            String said = ja.say("サロス周期とは何か調べて。30分以内、棚だけから、有料サービスは使わないで。");
            active = new Jobs(store, j -> "").active();
            JsonNode jargs = active.get(active.size() - 1).path("args");
            assertEquals(30, jargs.path("max_minutes").asInt()); assertEquals("shelves", jargs.path("sources").asText()); assertFalse(jargs.path("paid").asBoolean(true));
            assertTrue(said.contains("調査 " + active.get(active.size() - 1).path("job_id").asText() + "、ご希望のとおり：最大30分、棚だけから、有料の検索サービスなし。"), said);
        } finally { Chrome.clear(); }
    }

    @Test
    void aYesToTheRunTheModelOfferedCarriesTheSettingsSaidTheTurnBefore(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        Librarian.Session s = Librarian.Session.open(store);
        // the model answers from the shelves and offers the run; the settings were in the question
        new Librarian(store, LibrarianChatTest.scripted(List.of(LibrarianChatTest.say("The shelves hold nothing on it. I can go and find out; say the word.")), new ArrayList<>()), Librarian.person(), s)
                .say("what is the Saros cycle? don't exceed 5 minutes, from the shelves only");
        assertTrue(new Jobs(store, j -> "").active().isEmpty(), "nothing filed yet");
        // a bare yes: the run the model files now carries them, over the model's own shelves-and-web
        String reply = new Librarian(store, LibrarianChatTest.scripted(List.of(LibrarianChatTest.tool("library_research", "{\"question\": \"What is the Saros cycle?\", \"sources\": \"both\", \"max_minutes\": 5}"),
                LibrarianChatTest.say("Filed.")), new ArrayList<>()), Librarian.person(), s).say("yes");
        List<ObjectNode> active = new Jobs(store, j -> "").active();
        JsonNode args = active.get(0).path("args");
        assertEquals(5, args.path("max_minutes").asInt(), args.toString()); assertEquals("shelves", args.path("sources").asText(), args.toString());
        assertTrue(reply.contains("as you asked: up to 5 minutes, from the shelves only."), reply);
        // other words than a yes carry nothing over: a new request is read on its own
        new Librarian(store, LibrarianChatTest.scripted(List.of(LibrarianChatTest.tool("library_research", "{\"question\": \"How long is a Saros series, in years and eclipses?\"}"),
                LibrarianChatTest.say("Filed.")), new ArrayList<>()), Librarian.person(), s).say("and find out how long a Saros series lasts");
        active = new Jobs(store, j -> "").active();
        assertEquals(0, active.get(active.size() - 1).path("args").path("max_minutes").asInt(), active.get(active.size() - 1).toString());
        assertEquals("both", active.get(active.size() - 1).path("args").path("sources").asText());
        for (String yes : List.of("yes", "Yes, file it.", "go ahead", "do it", "はい", "お願いします。", "ok")) assertTrue(Librarian.ASSENT.matcher(yes).matches(), yes);
        for (String no : List.of("yes, and make it about the exeligmos too", "find out", "no", "", "yes but from the web")) assertFalse(Librarian.ASSENT.matcher(no).matches(), no);
    }

    @Test
    void theChatNamesOneOfTheLibrarysModelsForTheRun(@TempDir Path tmp) throws Exception {
        String home = System.getProperty("user.home");
        System.setProperty("user.home", tmp.toString()); Config.invalidate();
        try {
            ModelEntries.put("fable", "anthropic", "claude-fable-5-1", null, null, null, null);
            LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
            new LibrarianIndex(store, Embeddings.none()).rebuild();
            Librarian lib = new Librarian(store, LibrarianChatTest.scripted(List.of(LibrarianChatTest.tool("library_research", "{\"question\": \"What is the Saros cycle?\"}"),
                    LibrarianChatTest.say("Filed.")), new ArrayList<>()), Librarian.person(), Librarian.Session.open(store));
            String reply = lib.say("Find out what the Saros cycle is, with fable.");
            JsonNode args = new Jobs(store, j -> "").active().get(0).path("args");
            assertEquals("fable", args.path("model_entry").asText(), args.toString());
            assertTrue(reply.contains("as you asked: on the model fable."), reply);
        } finally { System.setProperty("user.home", home); Config.invalidate(); }
    }

    @Test
    void noPaidServiceLeavesThePaidPlacesOffAtTheTallysGateForTheRunsThreadsOnly(@TempDir Path tmp) throws Exception {
        SearchTally.override = tmp.resolve("search-tally.json");
        try {
            assertTrue(SearchTally.reserve(ExaSearchTool.TALLY)); SearchTally.settle(ExaSearchTool.TALLY, false, 0, 0);
            SearchTally.paidOff(true);
            assertFalse(SearchTally.reserve(ExaSearchTool.TALLY)); assertTrue(SearchTally.reached(TavilySearchTool.TALLY)); assertTrue(SearchTally.reached(WebSearchTool.SERPER)); assertTrue(SearchTally.reached(Firecrawl.TALLY));
            assertEquals("This run asked for no paid service, so Exa is left off for it.", SearchTally.usedUp(ExaSearchTool.TALLY, "Exa"));
            assertFalse(SearchTally.reached(PubMed.TALLY), "a free place, with no limit, is not a paid service");
            AtomicBoolean child = new AtomicBoolean(), stranger = new AtomicBoolean();
            Thread t = new Thread(() -> child.set(SearchTally.reached(ExaSearchTool.TALLY))); t.start(); t.join();
            assertTrue(child.get(), "a thread the run starts is under the same ask");
            SearchTally.paidOff(false);
            assertTrue(SearchTally.reserve(ExaSearchTool.TALLY)); SearchTally.settle(ExaSearchTool.TALLY, false, 0, 0);
            Thread s = new Thread(() -> stranger.set(SearchTally.reached(ExaSearchTool.TALLY))); s.start(); s.join();
            assertFalse(stranger.get(), "a thread started after the run is not");
        } finally { SearchTally.paidOff(false); SearchTally.override = null; }
    }

    @Test
    void theReportsFirstLinesCarryTheLimitsAndWhetherTheRunKeptToThem(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        ReportOptionsTest.OptionDrive drive = new ReportOptionsTest.OptionDrive(null);
        Researcher r = new Researcher(drive, drive, new ResearcherTest.FakeTools(), s -> { }, 2, store);
        r.paid(false);
        Researcher.Filed f = Researcher.file(store, r, new Researcher.Ask("How were the Antikythera gears cut?", "broad", 60, List.of("how were the gears cut?"), "both", List.of(), 30), "patron:test");
        assertTrue(f.admitted(), f.reason());
        String body = store.investigation(f.investigationId()).body();
        String head = body.substring(0, body.indexOf("## Answer"));
        assertTrue(head.contains("Limits asked for: up to 30 minutes, up to 60 turns, no paid search services. The run finished within them."), head);
        assertFalse(SearchTally.paidOff(), "cleared when the run ends");
        assertEquals("", RunLimits.reportSentence(new Researcher.Ask("q", "broad", 0, List.of()), true, false), "a run with no limits says nothing of them");
        assertTrue(RunLimits.reportSentence(new Researcher.Ask("q", "broad", 20, List.of()), true, true).endsWith("The run reached its limit before the work was done: the sections written before it stand, and what it did not get to is on the open questions."));
        assertEquals("Limits asked for: from the shelves only. ", RunLimits.reportSentence(new Researcher.Ask("q", "broad", 0, List.of(), "shelves", List.of()), true, false));
    }

    @Test
    void theProtocolTheCommandLineAndMcpTakePaid(@TempDir Path tmp) throws Exception {
        String realHome = System.getProperty("user.home");
        PrintStream realOut = System.out;
        try {
            System.setProperty("user.home", tmp.toString()); Config.invalidate();
            LibraryStore store = new LibraryStore(tmp.resolve("researchzosho-library")); store.init();
            LibraryProtocol p = new LibraryProtocol(store);
            ObjectNode ask = M.createObjectNode().put("question", "What is the Saros cycle, and who first described it?").put("paid", false).put("max_minutes", 30);
            ask.putObject("patron").put("did", "person").put("name", "keeper").put("runtime", "test");
            ObjectNode filed = p.research(ask, new LibraryProtocol.Way("asked", "cli", false));
            assertFalse(filed.path("paid").asBoolean(true), "the result echoes it: " + filed);
            JsonNode args = new Jobs(store, j -> "").get(filed.path("job_id").asText()).path("args");
            assertFalse(args.path("paid").asBoolean(true), args.toString()); assertEquals(30, args.path("max_minutes").asInt());
            ObjectNode plain = ask.deepCopy(); plain.remove("paid"); plain.put("question", "How long is the Saros cycle, in days and in years?");
            ObjectNode filedPlain = p.research(plain, new LibraryProtocol.Way("asked", "cli", false));
            assertFalse(filedPlain.has("paid")); assertFalse(new Jobs(store, j -> "").get(filedPlain.path("job_id").asText()).path("args").has("paid"), "nothing said, nothing on the job");
            ObjectNode bad = ask.deepCopy(); bad.put("paid", "yes"); bad.put("question", "Which eclipses belong to the same Saros series as the one of 2026?");
            ProtocolError e = assertThrows(ProtocolError.class, () -> p.research(bad, new LibraryProtocol.Way("asked", "cli", false)));
            assertEquals("invalid_args", e.code); assertTrue(e.getMessage().startsWith("paid, when given, is true or false"), e.getMessage());
            JsonNode props = null;
            for (JsonNode t : McpServer.allTools()) if (t.path("name").asText().equals("library_research")) props = t.path("inputSchema").path("properties");
            assertNotNull(props);
            assertEquals("boolean", props.path("paid").path("type").asText(), props.toString());
            // the chat's own description of the tool names the levers
            for (JsonNode t : Librarian.tools()) if (t.path("function").path("name").asText().equals("library_research")) assertTrue(t.path("function").path("description").asText().contains("max_minutes or max_turns caps it"), t.toString());
            ByteArrayOutputStream said = new ByteArrayOutputStream();
            System.setOut(new PrintStream(said, true, StandardCharsets.UTF_8));
            assertEquals(0, LibrarianCli.run(new String[]{"librarian", "research", "ask", "What is the Saros cycle, and how long is it?", "--no-paid", "--max-minutes", "20"}, "http://none", "m"));
            List<ObjectNode> active = new Jobs(store, j -> "").active();
            JsonNode cli = active.get(active.size() - 1).path("args");
            assertFalse(cli.path("paid").asBoolean(true), cli.toString()); assertEquals(20, cli.path("max_minutes").asInt());
            assertTrue(LibrarianCli.USAGE.contains("[--max-minutes N] [--no-paid]"), "the help says so");
        } finally { System.setOut(realOut); System.setProperty("user.home", realHome); Config.invalidate(); }
    }
}
