package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The law and patents fields (0.6.6): they join a run on plainly legal or patent words, a citation shape or a publication number, and stay out of the rest. */
class LawAndPatentsProfilesTest {

    @Test
    void theLawFieldJoinsOnLegalWordsAndCitationShapesAndStaysOutOfBenfordsLaw() {
        Profile law = Profiles.named("law");
        assertNotNull(law, "registered");
        assertTrue(law.wantsRecords());
        for (String q : new String[]{
                "What did the Supreme Court hold in Roe v. Wade, and which later decisions narrowed it?",
                "Is a 30-day notice enough under the statute of limitations for a tort claim in Ohio?",
                "What does 17 U.S.C. § 107 say about fair use?",
                "Which city ordinance governs short-term rentals in Austin?",
                "What does 37 CFR 1.53 require for a provisional filing?",
                "特許法第三十五条の職務発明の対価はどう決まるのか",
                "令和5年(受)第123号の判決の要旨は何か",
                "東京都の受動喫煙防止条例は飲食店に何を求めるか"}) assertTrue(law.applies(q), q);
        for (String q : new String[]{
                "How large a sample does a Benford's Law first-digit test need in a forensic audit?",
                "Does Moore's law still hold for memory in 2026?",
                "How were the Antikythera gears cut?",
                "Which proteins regulate the cell cycle?"}) assertFalse(law.applies(q), q);
        for (String rule : new String[]{law.register(), law.planRules(), law.criticRules(), law.writerRules(), law.extractionRule()}) assertFalse(rule.isBlank());
        assertTrue(law.register().contains("https://www.courtlistener.com/c/F.3d/574/1228/"), "a complete address to copy, not a placeholder");
        assertTrue(law.predicates().stream().anyMatch(t -> t.slug().equals("in-force-from")));
    }

    @Test
    void thePatentsFieldJoinsOnPatentWordsAndPublicationNumbers() {
        Profile patents = Profiles.named("patents");
        assertNotNull(patents, "registered");
        assertTrue(patents.wantsRecords());
        for (String q : new String[]{
                "Is there prior art before 1990 for a gear cut by hand with a dividing plate?",
                "What does US12450652B1 claim, and who holds it?",
                "Which companies filed patents on solid-state batteries in Japan since 2020?",
                "特許第1234567号の請求項1は何を権利化しているか",
                "Can a provisional application be searched?"}) assertTrue(patents.applies(q), q);
        for (String q : new String[]{"How were the Antikythera gears cut?", "What is the best recording of the Bach cello suites?"}) assertFalse(patents.applies(q), q);
        for (String rule : new String[]{patents.register(), patents.planRules(), patents.criticRules(), patents.writerRules(), patents.extractionRule()}) assertFalse(rule.isBlank());
        assertTrue(patents.register().contains("https://patents.google.com/patent/US12450652B1/en"));
        assertTrue(patents.register().contains("never published"), "what a provisional is");
    }
}
