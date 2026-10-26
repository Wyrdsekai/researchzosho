package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.researchzosho.librarian.profiles.OrganizationProfile;
import org.researchzosho.librarian.profiles.PersonProfile;

import static org.junit.jupiter.api.Assertions.*;

/** The facets of a target package (0.6.6): the planner, the critic and the writer of both fields are told them. */
class FacetsTest {
    @Test
    void bothFieldsPlanCriticiseAndWriteTheFacets() {
        OrganizationProfile o = new OrganizationProfile(); PersonProfile p = new PersonProfile();
        for (String facet : new String[]{"Customers, peers and alternatives", "Power and governance", "people in power in depth", "Workforce and signals", "none named in the record is a finding"}) assertTrue(o.planRules().contains(facet), facet);
        assertTrue(o.criticRules().contains("a chair or a chief executive with no decision is a gap"));
        for (String section : new String[]{"Customers, peers and alternatives (", "Power and governance (", "Decisions (a dated list", "Workforce and signals ("}) assertTrue(o.writerRules().contains(section), section);
        assertTrue(o.criticRules().contains("no named peer or alternative"));
        assertTrue(p.planRules().contains("The second is what they do, which for a person says who they are") && p.writerRules().contains("What they do (now: the role") && p.writerRules().contains("the Reading opens with what they do"));
        assertTrue(p.writerRules().contains("Conjectures and Forecasts are written when the question asks what to expect") && p.writerRules().contains("Not asked: the question did not ask what to expect"), "for every person alike, the question decides");
        assertTrue(p.planRules().contains("Influences:") && p.planRules().contains("Decisions:"));
        assertTrue(p.criticRules().contains("the reason the person gave"));
        assertTrue(p.writerRules().contains("Decisions (the principal moves of the career") && p.writerRules().contains("Influences (who and what shaped them"));
        assertTrue(Facets.DECISION_LINE.contains("WHO decided WHAT"));
        assertTrue(o.planRules().contains("Statements: what they said in their own words") && o.planRules().contains("wikimedia-commons") && p.planRules().contains("bluesky-posts by the handle found"));
        assertTrue(o.writerRules().contains("Statements (what they said") && o.writerRules().contains("Media (one a line") && p.writerRules().contains("Statements (what they said") && p.writerRules().contains("Media (one a line"));
        assertTrue(p.criticRules().contains("no statement in their own words on record is a gap"));
    }

    @Test
    void aDecisionIsOneTheSubjectsOwnPeopleTook() {
        assertTrue(Facets.DECISION_LINE.contains("a decision is one the subject's own people took") && Facets.DECISION_LINE.contains("belongs in the Timeline"));
    }
}
