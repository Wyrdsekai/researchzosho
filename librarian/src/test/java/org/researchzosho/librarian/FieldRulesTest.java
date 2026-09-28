package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.function.BooleanSupplier;
import org.researchzosho.records.RecordSource;
import org.researchzosho.records.RecordSources;
import org.researchzosho.tools.RecordSearchTool;
import org.researchzosho.tools.Tool;
/**
 * A profile's rules reach the planner, every worker, the critic and the writer when the run is one of its field's, and no other run. For
 * genealogy, a module, that is a run somebody asked for in genealogy mode: its words alone never make one.
 */
class FieldRulesTest {

    static class Recording extends ResearcherTest.ScriptedDrive {
        final List<String> prompts = new CopyOnWriteArrayList<>();
        @Override public String classify(ArrayNode messages, int maxTokens) {
            prompts.add(messages.get(messages.size() - 1).path("content").asText());
            return super.classify(messages, maxTokens);
        }
        String systemOf(String tool) {
            for (ArrayNode h : histories) for (JsonNode m : h) if ("system".equals(m.path("role").asText()) && m.path("content").asText().contains(tool)) return m.path("content").asText();
            return "";
        }
    }

    @Test
    void aRunAskedForInGenealogyModeCarriesTheGenealogyRulesEverywhere() {
        Recording drive = new Recording();
        var r = new Researcher(drive, new ResearcherTest.FakeTools(), null, 2).run(
                new Researcher.Ask("Who were my great-grandfather's parents, and what did he do for a living in Hiroshima?", "depth", 60, List.of()).withFields(List.of("genealogy")), "");
        assertNotNull(r);
        String plan = drive.prompts.stream().filter(p -> p.contains("Decompose")).findFirst().orElse("");
        assertTrue(plan.contains("GENEALOGY: This is a family-history question") && plan.contains("records only the family can request"), plan);
        assertTrue(drive.prompts.stream().noneMatch(p -> p.contains("Name the PERSPECTIVES")), "a field that splits the work its own way is not asked who studies it");
        String worker = drive.systemOf("You are a researcher working for a library");
        assertTrue(worker.contains("GENEALOGY: Work from records") && worker.contains("second identifier") && worker.contains("finds nothing, note that as well"), worker);
        String critic = drive.prompts.stream().filter(p -> p.contains("reviewing research COVERAGE")).findFirst().orElse("");
        assertTrue(critic.contains("GENEALOGY: For family history the evidence is enough when") && critic.indexOf("GENEALOGY") < critic.indexOf("Answer with JSON only"), critic);
        String writer = drive.systemOf("write_section");
        assertTrue(writer.contains("'Identity'") && writer.contains("'Records to request'") && writer.contains("listed after your text by the library itself"), writer);
    }

    @Test
    void aRecordSearchThatFindsNothingIsListedByTheHarnessAndAFoundRecordIsCitedAsTheRecord() throws Exception {
        var mapper = new ObjectMapper();
        var source = RecordSources.all().stream().filter(x -> x.id().equals("loc-newspapers")).findFirst().orElseThrow();
        var tool = new RecordSearchTool(List.of(source), url -> url.contains("Ellis")
                ? "{\"results\": [{\"partof_title\": [\"the dunedin star\"], \"date\": \"1952-03-04\", \"shelf_id\": 7, \"id\": \"https://www.loc.gov/resource/sn1/1952-03-04/ed-1/?sp=7\"}]}"
                : "{\"results\": []}");
        Researcher r = new Researcher(new Recording(), new ResearcherTest.FakeTools(), null, 1);
        tool.listen(r::recordSearched);
        assertEquals("", r.searchedSection());
        tool.execute(mapper.readTree("{\"source\": \"loc-newspapers\", \"query\": \"\\\"Arthur Ellis\\\" fitter\"}"));
        assertTrue(r.searchedSection().startsWith("## Record collections searched") && r.searchedSection().contains("- Chronicling America (Library of Congress): 1 search, 1 found records") && !r.searchedSection().contains("Searched and not found"), r.searchedSection());
        assertEquals("the dunedin star, 1952-03-04, page 7, Library of Congress, Chronicling America", r.citedAsRecord(List.of("cite:something else", "https://www.loc.gov/resource/sn1/1952-03-04/ed-1/?sp=7")), "the reference reads as the record, not as a bare address");
        assertEquals("", r.citedAsRecord(List.of("https://example.org/other")));
        tool.execute(mapper.readTree("{\"source\": \"loc-newspapers\", \"query\": \"Artur Elis\", \"from_year\": 1950, \"to_year\": 1955}"));
        tool.execute(mapper.readTree("{\"source\": \"loc-newspapers\", \"query\": \"Artur Elis\", \"from_year\": 1950, \"to_year\": 1955}"));
        assertTrue(r.recordKindsLine().startsWith("record collections searched by kind: newspaper ×3; kinds not searched at all: ") && r.recordKindsLine().contains("book"), r.recordKindsLine());
        String section = r.searchedSection();
        assertTrue(section.contains("### Searched and not found") && section.contains("3 searches, 1 found records") && section.contains("Not searched in this run: ") && section.contains("- Chronicling America (Library of Congress): Artur Elis, 1950-1955"), section);
        assertEquals(1, section.lines().filter(l -> l.contains(": Artur Elis")).count(), "the same search twice is one line");
    }

    @Test
    void thePlanIsGivenTheCollectionsAndAWorkerStartsInTheOnesItsSubQuestionNames() {
        Recording drive = new Recording() {
            @Override public String classify(ArrayNode messages, int maxTokens) {
                String p = messages.get(messages.size() - 1).path("content").asText();
                if (p.contains("Decompose")) { prompts.add(p); return "[\"The 1887 wedding of Caroline Hitch in New Orleans (search: loc-newspapers, internet-archive)\", \"Parents of 高峰譲吉 in 高岡 (search: ndl-fulltext)\"]"; }
                return super.classify(messages, maxTokens);
            }
        };
        Researcher.Tools withRecords = new Researcher.Tools() {
            final ResearcherTest.FakeTools base = new ResearcherTest.FakeTools();
            @Override public List<Tool> web(String focus) {
                List<Tool> out = new ArrayList<>(base.web(focus));
                out.add(new RecordSearchTool(RecordSources.ordered(List.of("ja"), focus), url -> "{}"));
                return out;
            }
            @Override public BooleanSupplier exhausted() { return base.exhausted(); }
        };
        new Researcher(drive, withRecords, null, 2).run(new Researcher.Ask("Family history of 高峰譲吉: who were his parents and his wife, my great-grandfather's generation?", "depth", 60, List.of()).withFields(List.of("genealogy")), "");
        String plan = drive.prompts.stream().filter(p -> p.contains("Decompose")).findFirst().orElse("");
        assertTrue(plan.contains("THE COLLECTIONS a worker can search by name") && plan.contains("- loc-newspapers: newspaper, US, 1756-1963") && plan.contains("(search: loc-newspapers, internet-archive)"), plan);
        String wedding = "", parents = "";
        for (ArrayNode h : drive.histories) for (JsonNode m : h) { String c = m.path("content").asText(); if (!"user".equals(m.path("role").asText())) continue; if (c.contains("YOUR SUB-QUESTION") && c.contains("1887 wedding")) wedding = c; if (c.contains("YOUR SUB-QUESTION") && c.contains("Parents of")) parents = c; }
        assertTrue(wedding.contains("Start with record_search in loc-newspapers, internet-archive: the person's name alone first"), wedding);
        assertTrue(parents.contains("Start with record_search in ndl-fulltext"), parents);
        // and the list that worker reads begins with the collections it was given, not with the language of a name in the sub-question
        List<RecordSource> order = RecordSources.ordered(List.of("ja"), "The 1887 wedding of Caroline Hitch 高峰譲吉 (search: loc-newspapers, internet-archive)");
        assertEquals(List.of("loc-newspapers", "internet-archive"), order.subList(0, 2).stream().map(RecordSource::id).toList());
    }

    @Test
    void anyOtherRunCarriesNoneOfThem() {
        // a question with no field of its own, and a family-history question nobody asked genealogy mode for: both are ordinary research
        for (String q : List.of("How were the Antikythera gears cut?", "Who were my great-grandfather's parents, and what did he do for a living in Hiroshima?")) {
            Recording drive = new Recording();
            new Researcher(drive, new ResearcherTest.FakeTools(), null, 2).run(new Researcher.Ask(q, "depth", 60, List.of()), "");
            assertTrue(drive.prompts.stream().anyMatch(p -> p.contains("Name the PERSPECTIVES")), "a question with no field of its own is still asked who studies it: " + q);
            for (String p : drive.prompts) assertFalse(p.contains("GENEALOGY"), p);
            for (ArrayNode h : drive.histories) for (JsonNode m : h) assertFalse(m.path("content").asText().contains("GENEALOGY"), m.path("content").asText());
        }
    }
}
