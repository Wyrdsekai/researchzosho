package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.researchzosho.records.RecordSources;
import org.researchzosho.tools.RecordSearchTool;
import org.researchzosho.tools.Tool;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The prior-art goal through the brain's shape: its words and its decision rules reach the planner and every worker, its shape reaches the
 * writer and binds the answer to what was read, the pool, the reading of its abstracts and the claims are on the table with no patent search
 * that ranks, the plan is one chain, and it fires on plain asks in English and Japanese and not on their neighbours.
 */
class PriorArtGoalTest {
    static final String ASK = "Has anyone already patented a dog bowl that folds flat and doubles as a throwing disc? Every application and grant from 2012 to 2022.";
    /** No patent word in it: the goal is named, as the protocol names the field for it. */
    static final String PLAIN = "Find what was filed before 2015 for a bowl that folds flat.";

    /** A drive that keeps every planning prompt it was sent and the words of every tool it was offered. */
    static class Seen extends RunBrainTest.Drive {
        final List<String> plans = new CopyOnWriteArrayList<>();
        final List<String> toolWords = new CopyOnWriteArrayList<>();
        @Override public ObjectNode chat(ArrayNode history, ArrayNode tools, int maxTokens, String toolChoice) {
            for (JsonNode t : tools) toolWords.add(t.path("function").path("name").asText() + ": " + t.path("function").path("description").asText());
            return super.chat(history, tools, maxTokens, toolChoice);
        }
        @Override public String classify(ArrayNode messages, int maxTokens) {
            String prompt = messages.get(messages.size() - 1).path("content").asText();
            if (prompt.contains("Decompose")) plans.add(prompt);
            return super.classify(messages, maxTokens);
        }
        /** The system prompt of every worker: the ones that research, not the writer. */
        List<String> workers() { List<String> out = new ArrayList<>(); for (ArrayNode h : histories) { String s = h.get(0).path("content").asText(); if (s.contains("You are a researcher")) out.add(s); } return out; }
        String writer() { return histories.stream().map(h -> h.get(0).path("content").asText()).filter(t -> t.contains("Then call done.")).findFirst().orElse(""); }
        String words(String tool) { return toolWords.stream().filter(w -> w.startsWith(tool + ": ")).findFirst().orElse(""); }
    }

    /** The fake web tools and the record collections, read by nobody: the drive never calls them, and a reader that is asked says so. */
    static class ToolsWithRecords implements Researcher.Tools {
        final ResearcherTest.FakeTools web = new ResearcherTest.FakeTools();
        @Override public List<Tool> web(String focus) {
            List<Tool> out = new ArrayList<>(web.web(focus));
            out.add(new RecordSearchTool(RecordSources.all(), url -> { throw new IllegalStateException("a test reads no collection"); }));
            return out;
        }
        @Override public BooleanSupplier exhausted() { return () -> false; }
    }

    static Seen run(String question, List<String> fields, List<String> goals) {
        Seen drive = new Seen();
        new Researcher(drive, drive, new ToolsWithRecords(), s -> { }, 1, null).run(new Researcher.Ask(question, "depth", 60, List.of()).withFields(fields).withGoals(goals), "");
        return drive;
    }

    @Test
    void theGoalsWordsAndRulesReachThePlannerAndEveryWorker() {
        Goal g = Goals.named("prior-art");
        String block = "THIS RUN SERVES THE GOAL prior-art: " + g.words() + " " + g.decisionRules();
        Seen byWords = run(ASK, List.of(), List.of());
        assertFalse(byWords.plans.isEmpty(), "the run planned");
        assertTrue(byWords.plans.get(0).contains(block), "the planner reads the goal's words and rules: " + byWords.plans.get(0));
        assertTrue(byWords.plans.get(0).contains("PATENTS: This is a question about patents"), "and the field's own plan rules joined by the question's words");
        assertFalse(byWords.workers().isEmpty(), "workers ran");
        for (String sys : byWords.workers()) assertTrue(sys.contains(block), "every worker reads the goal: " + sys.substring(0, Math.min(400, sys.length())));
        // named as the protocol names it: the goal first, the field it resolves to beside it
        assertEquals("patents", LibraryProtocol.goalField("prior-art"), "naming the goal names the patents field, so its collections and rules join");
        Seen named = run(PLAIN, List.of("patents"), List.of("prior-art"));
        assertTrue(named.plans.get(0).contains(block), "a plain ask with the goal named is planned under it: " + named.plans.get(0));
        for (String sys : named.workers()) assertTrue(sys.contains(block) && sys.contains("THE PATENTS CONVENTION."), "every worker reads the goal and the convention its claims carry");
        assertEquals("prior-art", Fields.goalsForRun(null, new Researcher.Ask(PLAIN, "depth", 60, List.of()).withFields(List.of("patents")).withGoals(List.of("prior-art"))).get(0).name());
    }

    @Test
    void theRulesAreThePremisesEnumerateAndFilterByFitNeverARankedFirstPage() {
        String rules = Goals.named("prior-art").decisionRules();
        assertTrue(rules.startsWith("Prior art goes in one order: the class, then patents, then patent_abstracts, then patent_claims."), rules);
        assertTrue(rules.contains("search where=records source=epo-classification with the thing in plain words gives the groups") && rules.contains("or from the classes on a known patent's record (patent_claims with its number in publications prints them)"), "how the class is found: " + rules);
        assertTrue(rules.contains("{\"classes\": [\"A47G19/22\"], \"from_year\": 2005, \"to_year\": 2015, \"features\": [\"lid, cover, cap, Deckel, 蓋\", \"straw, drinking tube, Trinkhalm, ストロー\"]}, never a query"), "a complete literal call, and never a query: " + rules);
        assertTrue(rules.contains("it enumerates for itself every publication of each class in those years from the office's own records, page by page to the end, says the office's own count") && rules.contains("never by citations, family size or an engine's rank"), rules);
        assertTrue(rules.contains("Then patent_abstracts with that key in from") && rules.contains("the same call with page=2 goes on") && rules.contains("Then patent_claims with that key in from reads the claims of the families that pass, best first, from the office's own full text"), rules);
        assertTrue(rules.contains("it reads the claims from Google Patents' copy of the office's record at the publication's own address, and says which claims came from where") && rules.contains("a patent's page is never opened by you for its claims or its status") && rules.contains("a feature no claim read meets is said to be met by none that was read") && rules.endsWith("A run of this kind has no patent search that ranks on its table: patents is the pool."), rules);
        assertFalse(rules.contains("epo-query") || rules.contains("google-patents") || rules.contains("pd within"), "the model writes no office query and reads no engine's page: " + rules);
        String words = Goals.named("prior-art").words();
        assertTrue(words.contains("whether an idea is new") && words.contains("Example: \"has anyone patented"), "the words carry a plain ask as the example: " + words);
        assertEquals(List.of("patents", "patent_abstracts", "patent_claims", "search"), Goals.named("prior-art").instruments());
    }

    @Test
    void theShapeReachesTheWriterAndBindsTheAnswerToWhatWasRead() {
        Goal g = Goals.named("prior-art");
        Seen drive = run(ASK, List.of(), List.of());
        String writer = drive.writer();
        assertFalse(writer.isEmpty(), "the writer was asked");
        assertTrue(writer.contains("THE REPORT SERVES THE GOAL prior-art: " + g.shape()), "the goal's shape reaches the writer");
        assertTrue(writer.contains("THE PATENTS CONVENTION."), "and the convention");
        String shape = g.shape();
        assertTrue(shape.contains("for each feature of the invention the ask named, the closest publication whose claims were read, by its number with the office's link on the line, the number of the claim and its words quoted"), "it names what was checked: " + shape);
        assertTrue(shape.contains("met by none that was read, which is not the same as none that exists"), "it says what was not: " + shape);
        assertTrue(shape.contains("the office's own count of publications, how many families were enumerated, how many abstracts were read, how many families' claims were read"), "the counts: " + shape);
        assertTrue(shape.contains("Then what was not read, one line each") && shape.contains("the publications whose claims the office's service does not hold") && shape.contains("the other classes, the years, the offices and the languages not searched"), shape);
        assertTrue(shape.contains("a publication whose claims were not read is listed as unread") && shape.contains("Nothing is said of a patent beyond what its own record showed"), "it claims nothing beyond the evidence: " + shape);
        assertTrue(shape.contains("each quoted claim says where it was read: the office's own full text, a named member of the family, or Google Patents' copy of the office's record") && shape.contains("how many families' claims were read from the office and how many from Google Patents' copy, how many by their abstracts alone"), "which claims came from which source: " + shape);
        assertTrue(shape.contains("whether it was granted, is pending or has lapsed is said only in the words of the source that says it, as that source's reading (\"Google Patents words its status as Pending\"), or left out"), "a status is never the report's own fact: " + shape);
        assertTrue(writer.contains("A status such as pending or granted is given only in the words of the page that words it, as that page's own reading, never as a fact of the report."), "and the field's rule for the write-up says the same");
        assertTrue(writer.contains("PATENTS: Open with each feature's closest claim, quoted with its number and the office's link"), "the field's own rule for the write-up: " + writer.substring(0, Math.min(3000, writer.length())));
    }

    @Test
    void thePoolTheReadingAndTheClaimsAreOnTheTableAndNoPatentSearchThatRanks() {
        Seen drive = run(ASK, List.of(), List.of());
        List<String> offered = GoalRunTest.worker(drive);
        assertTrue(offered.containsAll(List.of("patents", "patent_abstracts", "patent_claims", "record_search", "search", "open")), "the pool, the reading, the claims, the collections, the grouped search and open are on the table: " + offered);
        String records = drive.words("record_search");
        for (String id : Researcher.RANKED_PATENT_SEARCHES) assertFalse(records.contains("- " + id + ":"), id + " is not a collection of a run about prior art: " + records);
        assertEquals(List.of("google-patents", "serpapi-patents", "epo-query"), Researcher.RANKED_PATENT_SEARCHES, "the two that rank, and the office's query a model wrote and read the first page of");
        for (String id : List.of("epo-applicant", "epo-inventor", "epo-classification")) assertTrue(records.contains("- " + id + ":"), id + " stays: a name or the classification, not a pool: " + records);
        assertFalse(records.contains("- courtlistener:") || records.contains("- loc-newspapers:"), "the law's and the family's collections are not: " + records);
        assertTrue(drive.words("patents").contains("you give only the classes, the years and the features, never a query") && drive.words("patents").contains(Instruments.PATENTS_EXAMPLE), "the worker reads the pool's words with the literal call: " + drive.words("patents"));
        // named by its goal on a plain ask, and carried by a sub-question of another run: the same table
        Seen named = run(PLAIN, List.of("patents"), List.of("prior-art"));
        assertTrue(GoalRunTest.worker(named).containsAll(List.of("patents", "patent_abstracts", "patent_claims")) && !named.words("record_search").contains("- google-patents:"), GoalRunTest.worker(named).toString());
        // a run that is not about patents has none of the three, and its collections are what they were
        Seen other = run("How were the Antikythera gears cut?", List.of(), List.of());
        List<String> otherTable = GoalRunTest.worker(other);
        assertFalse(otherTable.contains("patents") || otherTable.contains("patent_abstracts") || otherTable.contains("patent_claims"), "an ordinary run's table is what it was: " + otherTable);
        // the other runs of the patent collections keep the office's query and its pages
        RecordSearchTool all = new RecordSearchTool(RecordSources.all(), url -> { throw new IllegalStateException("a test reads no collection"); });
        assertTrue(all.description().contains("cpc=A01K5/01 and pd within \"2012 2022\"") && all.description().contains("Pages, up to 100 a page."), "the EPO query still says how a class and its years are paged, for the runs that keep it");
        assertFalse(all.without(Researcher.RANKED_PATENT_SEARCHES).description().contains("- epo-query:"));
    }

    @Test
    void thePlanIsOneChainAndTheFieldsRulesNameThePoolNeverASearch() {
        Seen drive = run(ASK, List.of(), List.of());
        String plan = drive.plans.get(0);
        assertTrue(plan.contains("PATENTS: This is a question about patents. When it asks whether something was already patented or invented, or for the prior art of an invention, this is the exception to splitting by facet: plan one sub-question carrying the whole ask, and one only"), plan);
        assertTrue(plan.contains("The pool comes from patents, never from a search") && plan.contains("{\"classes\": [\"A47G19/22\"], \"from_year\": 2005, \"to_year\": 2015, \"features\": [\"lid, cover, cap\", \"straw, drinking tube\"]}"), plan);
        assertFalse(plan.contains("THE COLLECTIONS a worker can search by name") || plan.contains("(search: "), "the plan of a run about prior art gives out no collections, one to a sub-question: " + plan);
        boolean started = false;
        for (ArrayNode h : drive.histories) if (h.size() > 1 && h.get(0).path("content").asText().contains("You are a researcher")) { started = true; assertTrue(h.get(1).path("content").asText().contains(Researcher.PRIOR_ART_START), "a worker is told its first moves in the goal's order: " + h.get(1).path("content").asText()); }
        assertTrue(started && Researcher.PRIOR_ART_START.startsWith("Start by finding the classification the invention sits in") && Researcher.PRIOR_ART_START.contains("{\"where\": \"records\", \"source\": \"epo-classification\"") && Researcher.PRIOR_ART_START.contains("Then call patents with the classes, the years and the invention's features; then patent_abstracts and then patent_claims"));
        for (String sys : drive.workers()) {
            assertTrue(sys.contains("Prior art is found by enumerating a class from the office's own records, never from a search engine's first page") && sys.contains("it reads the claims from Google Patents' copy of the office's record and says so. A patent is read with patent_claims and never by opening its page yourself."), "every worker reads the field's register: " + sys.substring(0, Math.min(600, sys.length())));
            assertFalse(sys.contains("google-patents by words") || sys.contains("patents.google.com/patent/"), "and is sent to no engine and no page that refuses");
        }
    }

    /** A planner that splits the ask in four whatever its rules say, as the third live run's did. */
    static class Splits extends Seen {
        @Override public String classify(ArrayNode messages, int maxTokens) {
            String prompt = messages.get(messages.size() - 1).path("content").asText();
            if (prompt.contains("Decompose")) { plans.add(prompt); return "[\"Find the classification for kettles.\", \"Enumerate the pool of the class.\", \"Read the abstracts of the pool.\", \"Read the claims of those that pass.\"]"; }
            return super.classify(messages, maxTokens);
        }
    }

    /** The sub-question of every worker of a run, as each was handed it, once each. */
    static List<String> subQuestions(Seen drive) {
        List<String> subs = new ArrayList<>();
        String head = "YOUR SUB-QUESTION";
        for (ArrayNode h : drive.histories) {
            if (h.size() < 2 || !h.get(0).path("content").asText().contains("You are a researcher")) continue;
            String u = h.get(1).path("content").asText();
            int at = u.indexOf(head);
            if (at < 0) continue;
            int line = u.indexOf('\n', at), end = u.indexOf("\n\n", line + 1);
            String sub = u.substring(line + 1, end < 0 ? u.length() : end).strip();
            if (!subs.contains(sub)) subs.add(sub);
        }
        return subs;
    }

    static final List<String> PARTS = List.of("Find the classification for kettles.", "Enumerate the pool of the class.", "Read the abstracts of the pool.", "Read the claims of those that pass.");

    @Test
    void aPlanSplitIntoPartsStillGoesToOneWorkerSinceTheChainIsOne() {
        Splits drive = new Splits();
        new Researcher(drive, drive, new ToolsWithRecords(), s -> { }, 1, null).run(new Researcher.Ask(ASK, "depth", 60, List.of()), "");
        List<String> subs = subQuestions(drive);
        String one = ASK + " The parts of this, for one worker and in this order: (1) Find the classification for kettles. (2) Enumerate the pool of the class. (3) Read the abstracts of the pool. (4) Read the claims of those that pass.";
        assertTrue(subs.contains(one), "the planner's four parts are one worker's sub-question, in its order and with nothing dropped: " + subs);
        for (String part : PARTS) assertFalse(subs.contains(part), "and no part is a sub-question of its own: " + subs);
        // a run that is not about prior art keeps its planner's split
        Splits other = new Splits();
        new Researcher(other, other, new ToolsWithRecords(), s -> { }, 1, null).run(new Researcher.Ask("How were the Antikythera gears cut?", "depth", 60, List.of()), "");
        List<String> split = subQuestions(other);
        for (String part : PARTS) assertTrue(split.contains(part), "another run's parts are each a sub-question: " + split);
    }

    @Test
    void itFiresOnPlainAsksInEnglishAndJapaneseAndNotOnNeighbours() {
        Goal g = Goals.named("prior-art");
        for (String q : List.of(
                "Has anyone already patented a dog bowl that folds flat and doubles as a throwing disc?",
                "Is my idea new: a kettle whose spout folds flat for packing?",
                "What prior art exists for a self-tightening shoelace from 2010 to 2020?",
                "What does US10000000B2 claim, and who holds it?",
                "Can I get a patent on a bicycle pannier that unfolds into a backpack?",
                "Was this invention already filed by someone else before 2015?",
                "Run a freedom to operate check on a magnetic cable tidy.",
                "Has somebody invented a kettle that folds flat before me?",
                "折りたたみ式の犬用ボウルは既に特許がありますか",
                "この発明は新しいですか。先行技術を調べてください",
                "自転車のカゴがリュックになる仕組みで特許は取れますか"))
            assertTrue(g.appliesTo(q).yes(), "fires on: " + q + " (" + g.appliesTo(q).why() + ")");
        for (String q : List.of(
                "Who invented the telephone, and when?",
                "電話を発明したのは誰ですか",
                "Where can I buy a patent leather handbag under $100?",
                "The history of the steam engine and its inventors",
                "大学への出願手続きを教えてください",
                "Is the Hale cipher patently absurd as a code?",
                "What is the yield on the US 10-year treasury today?",
                "Which claims does my travel insurance cover for a cancelled flight?",
                "発明家エジソンの生涯を教えて",
                "How do I file a bug report against the Linux kernel?"))
            assertFalse(g.appliesTo(q).yes(), "does not fire on: " + q);
        assertFalse(Fields.goalsForRun(null, new Researcher.Ask("Who invented the telephone, and when?", "depth", 60, List.of())).stream().anyMatch(x -> x.name().equals("prior-art")), "a history question runs as itself");
    }
}
