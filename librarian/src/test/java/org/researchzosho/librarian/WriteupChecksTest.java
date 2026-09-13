package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The write-up against its own evidence: the contradictions the 27B produced on 2026-09-12, caught without a model. */
class WriteupChecksTest {
    static final String EVIDENCE = "- Karakeep is licensed AGPL-3.0 — source: https://github.com/karakeep-app/karakeep\n"
            + "- GPT-5.6 Sol leads BrowseComp at 92.2% — source: https://leaderboard.example/browsecomp\n"
            + "- CVE-2026-75773 affects Karakeep — source: https://osv.dev/x\n"
            + "The benchmark holds 1,266 questions. Recall fell from 84.3% to 21.4% on the open corpus.";

    @Test
    void aNumberTheEvidenceNeverStatesIsListed() {
        String answer = "Top scores cluster in the 38–58% range on BrowseComp. Recall fell to 21.4% on the open corpus. The set has 1,266 questions. It took 3 days.";
        List<String> off = WriteupChecks.numbersUnbacked(answer, EVIDENCE);
        assertEquals(2, off.size(), off.toString());
        assertTrue(off.get(0).startsWith("38 %") || off.get(0).startsWith("38%") || off.get(0).contains("38"), off.get(0));
        assertTrue(off.stream().anyMatch(l -> l.startsWith("58")), off.toString());
        assertTrue(off.stream().noneMatch(l -> l.contains("21.4") || l.contains("1266")), "numbers the evidence holds pass; commas do not matter");
    }

    @Test
    void aLicenceOrCveTheEvidenceNeverStatesIsListed() {
        String answer = "Karakeep is MIT-licensed. Khoj has CVE-2025-69207. Karakeep's CVE-2026-75773 is public.";
        List<String> off = WriteupChecks.namesUnbacked(answer, EVIDENCE);
        assertEquals(2, off.size(), off.toString());
        assertTrue(off.get(0).startsWith("MIT"), off.get(0));
        assertTrue(off.get(1).startsWith("CVE-2025-69207"), off.get(1));
        assertTrue(WriteupChecks.namesUnbacked("Karakeep is AGPL-3.0 licensed.", EVIDENCE).isEmpty(), "AGPL-3.0 is in the evidence");
    }

    @Test
    void aQuotationInNoSourceIsListed() {
        String source = "No LLM-era system in the corpus demonstrates an externally validated in-loop oracle; only 38% ship claim verification.";
        String answer = "The survey notes that \"no LLM-era system in the corpus demonstrates an externally validated in-loop oracle\" and that \"every harness now ships a verified oracle by default\".";
        List<String> off = WriteupChecks.quotesUnbacked(answer, List.of(source));
        assertEquals(1, off.size(), off.toString());
        assertTrue(off.get(0).contains("every harness now ships"), off.get(0));
    }
}

class WriteupChecksScopedTest {
    @Test
    void aNumberIsCheckedAgainstTheNotesAndTheSentenceOwnSources() {
        var refs = List.of(new CiteCheck.Ref(1, "https://a.example/leaderboard", "", "Board"), new CiteCheck.Ref(2, "https://b.example/other", "", "Other"));
        var texts = java.util.Map.of(1, "GPT-5.6 Sol leads at 92.2% on the board.", 2, "Somewhere in this long page the number 58% appears about something else entirely.");
        String notes = "- the leader scores 92.2% — source: https://a.example/leaderboard";
        String answer = "Top scores cluster in the 38–58% range [1]. The leader scores 92.2% [1]. Cost fell 40% last year.";
        var off = WriteupChecks.numbersUnbacked(answer, notes, refs, texts);
        assertEquals(3, off.size(), off.toString());
        assertTrue(off.stream().anyMatch(l -> l.startsWith("58")) && off.stream().anyMatch(l -> l.startsWith("38")) && off.stream().anyMatch(l -> l.startsWith("40")), off.toString());
        assertTrue(WriteupChecks.numbersUnbacked("Somewhere the number is 58% [2].", notes, refs, texts).isEmpty(), "its own cited source holds it");
    }
}

class WriteupChecksTightenedTest {
    @Test
    void scareQuotesAreNotQuotations() {
        String answer = "The lead runs \"one-shot\" subagents owned by the caller via a SubagentRun handle returning a final result, and \"continuable\" ones as well.";
        assertTrue(WriteupChecks.quotesUnbacked(answer, List.of("nothing relevant")).isEmpty());
        String real = "The survey says: \"no LLM-era system in the corpus demonstrates an externally validated oracle\" today.";
        assertEquals(1, WriteupChecks.quotesUnbacked(real, List.of("nothing relevant")).size());
    }

    @Test
    void aLicenceBelongsToTheProductTheSentenceNames() {
        String notes = "- AnythingLLM is MIT-licensed — source: https://a.example\n- Khoj is licensed AGPL-3.0 — source: https://k.example\n";
        var off = WriteupChecks.namesUnbacked("Khoj is open-source (MIT).", notes);
        assertEquals(1, off.size(), off.toString());
        assertTrue(WriteupChecks.namesUnbacked("AnythingLLM is open-source (MIT).", notes).isEmpty());
    }

    @Test
    void rangesShareTheirUnitForEveryUnit() {
        var off = WriteupChecks.numbersUnbacked("Indexing takes 30–120 seconds per document and needs an 8–12 GB card.", "- indexing takes 45 seconds — source: x");
        assertTrue(off.stream().anyMatch(l -> l.startsWith("30 ")) && off.stream().anyMatch(l -> l.startsWith("120 ")) && off.stream().anyMatch(l -> l.startsWith("12 ")), off.toString());
    }
}
