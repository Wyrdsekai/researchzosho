package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The person and organization fields (0.6.6): they join a run on dossier words, a "who is Name Name", a legal form or a register number, and stay out of the rest. */
class PeopleAndOrganizationsProfilesTest {

    @Test
    void thePersonFieldJoinsOnDossierWordsAndAWhoIsName() {
        Profile person = Profiles.named("person");
        assertNotNull(person, "registered");
        assertTrue(person.wantsRecords());
        for (String q : new String[]{
                "Who is Neal Stephenson, and what is his record of working with companies?",
                "Put together a dossier on the current Japanese prime minister: career, positions, statements.",
                "Background check on the founder of a startup I am about to sign with; here is the linkedin.com/in/ page.",
                "What is known about James Bond in the novels, and who holds the rights today?",
                "高市早苗の経歴と人物像をまとめて",
                "この人物について身元を調べたい"}) assertTrue(person.applies(q), q);
        for (String q : new String[]{
                "What did Arthur Ellis patent in Dunedin?",
                "Who were my great-grandfather's parents?",
                "How were the Antikythera gears cut?",
                "Who is the maintainer of the fastest JSON parser for Rust?",
                "Which companies filed patents on solid-state batteries in Japan since 2020?"}) assertFalse(person.applies(q), q);
        for (String rule : new String[]{person.register(), person.planRules(), person.criticRules(), person.writerRules(), person.extractionRule()}) assertFalse(rule.isBlank());
        assertTrue(person.register().contains("https://www.wikidata.org/wiki/Q312740"), "a complete address to copy, not a placeholder");
        assertTrue(person.register().contains("two dossiers that never mix"), "the fictional fence");
        assertTrue(person.extractionRule().contains("canon"), "the five source classes");
        assertTrue(person.writerRules().contains("as of today, the date at the top of this prompt"), "current is dated by the run, not by memory");
        assertTrue(person.writerRules().startsWith("The Answer section, first, is the Reading"), "the core's first section is Answer; the field names it, never fights it");
        assertTrue(person.planRules().contains("google-books") && person.planRules().contains("chm-oral-histories"), "the books and the oral histories are where the people are");
        assertTrue(person.planRules().contains("never what is gathered") && person.planRules().contains("open-library-author"), "the leaning decides the Reading; every facet is gathered in full, the works complete");
        assertTrue(person.planRules().contains("the one the person is known for, gets a sub-question of its own"), "a person's principal work is a facet with depth");
        assertTrue(person.planRules().contains("edgar-fulltext") && person.planRules().contains("diet-speaker"), "the plan names a collection per facet");
        assertTrue(person.predicates().stream().anyMatch(t -> t.slug().equals("worked-at")));
    }

    @Test
    void theOrganizationFieldJoinsOnDossierWordsLegalFormsAndRegisterNumbers() {
        Profile org = Profiles.named("organization");
        assertNotNull(org, "registered");
        assertTrue(org.wantsRecords());
        for (String q : new String[]{
                "Give me the corporate history of Cisco Systems, Inc. and its subsidiaries.",
                "Who owns the Lown Institute, and who sits on its board of trustees?",
                "What is the history of the university called The New School, and who runs it now?",
                "Due diligence on Enfi: officers, filings, money raised.",
                "株式会社トヨタ自動車の沿革と役員一覧",
                "法人番号 1234567890123 の法人情報",
                "Which entity has LEI 549300683FM6U8ZDR046?",
                "What is Miskatonic University in Lovecraft's stories, and who owns the name today?"}) assertTrue(org.applies(q), q);
        for (String q : new String[]{
                "Which companies filed patents on solid-state batteries in Japan since 2020?",
                "How were the Antikythera gears cut?",
                "What does 17 U.S.C. § 107 say about fair use?",
                "Who were my great-grandfather's parents?"}) assertFalse(org.applies(q), q);
        for (String rule : new String[]{org.register(), org.planRules(), org.criticRules(), org.writerRules(), org.extractionRule()}) assertFalse(rule.isBlank());
        assertTrue(org.register().contains("https://projects.propublica.org/nonprofits/organizations/43291770"));
        assertTrue(org.writerRules().contains("Registry card"));
        assertTrue(org.planRules().contains("gleif") && org.planRules().contains("crt-sh"), "the plan names a collection per facet");
        assertTrue(org.planRules().contains("never what is gathered"), "the leaning decides the Reading, never what is gathered");
        assertTrue(org.planRules().contains("the flagship above all, gets a sub-question of its own") && org.criticRules().contains("has an account of its own, not a sentence") && org.writerRules().contains("The work ("), "an organisation's work is a facet with depth, not a line among the registers");
        assertTrue(org.predicates().stream().anyMatch(t -> t.slug().equals("subsidiary-of")));
    }
}
