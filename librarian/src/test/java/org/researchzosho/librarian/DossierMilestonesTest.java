package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.researchzosho.librarian.profiles.OrganizationProfile;
import org.researchzosho.librarian.profiles.PersonProfile;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The major things first: a dossier's timeline opens with the milestones, read from the write-up's Milestones list and from the prose of the
 * Registry card, dated by the event's own year; a dated identity claim already on the shelves sits at its date; the claims under the subject's
 * legal name are the subject's; and the page says which runs the timeline came from. A university founded in 1636 had a timeline that
 * started in 2001, because the one run asked who holds power and the timeline step read only decisions, statements and media (2026-11-04).
 */
class DossierMilestonesTest {

    static final String BODY = "## Question\n\nWho is Example University? A full dossier.\n\n## Answer\n\nExample University, founded 1636 in Newtowne as New College [1], is governed by two boards [2].\n\n"
            + "## Milestones\n\n1639: Renamed Example College after a bequest [1].\n1780: Named a university in the state constitution [1].\n\n"
            + "## Registry card\n\nFounded 1636 in Newtowne, Massachusetts, as New College by a vote of the General Court [1]; chartered 1650 as the President and Fellows of Example College [2]. "
            + "Legal name: \"President and Fellows of Example College\" [2]. Tax exemption granted Dec. 1967 [3].\n\n"
            + "## Decisions\n\n2010-12: Decision — the Corporation grew from 7 to 13 members [2].\n\n"
            + "## References\n\n[1] https://a.example/history\n[2] https://a.example/charter\n[3] https://a.example/irs\n";

    @Test
    void milestonesAreReadFromTheListAndTheRegistryCardDatedByTheEventsYear() {
        List<TimelineClaims.Event> ev = TimelineClaims.events(BODY);
        List<String> marked = new ArrayList<>();
        for (TimelineClaims.Event e : ev) if (e.text().startsWith(TimelineClaims.MILESTONE)) marked.add(e.year() + " " + e.text());
        assertEquals(4, marked.size(), marked.toString());
        assertTrue(marked.get(0).startsWith("1639 " + TimelineClaims.MILESTONE + "Renamed Example College"), marked.get(0));
        assertTrue(marked.get(1).startsWith("1780 " + TimelineClaims.MILESTONE + "Named a university"), marked.get(1));
        assertTrue(marked.get(2).startsWith("1636 " + TimelineClaims.MILESTONE + "Founded 1636 in Newtowne"), "the founding, dated by the year beside its verb: " + marked.get(2));
        assertTrue(marked.get(3).startsWith("1650 " + TimelineClaims.MILESTONE + "chartered 1650 as the President and Fellows"), "the charter, its own year: " + marked.get(3));
        assertTrue(marked.stream().noneMatch(m -> m.contains("Tax exemption")), "an exemption is no milestone: " + marked);
        assertEquals(List.of("https://a.example/history"), ev.stream().filter(e -> e.text().contains("Founded 1636")).findFirst().orElseThrow().locators());
        assertTrue(TimelineClaims.opensWithMilestone("Born in Fort Meade") && TimelineClaims.opensWithMilestone("was appointed dean") && !TimelineClaims.opensWithMilestone("The board met"));
    }

    @Test
    void theTimelineOpensWithTheMilestonesAndADatedIdentityClaimSitsAtItsDate(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        // an identity extraction on the legal name: its triple's object is the year, its valid_as_of the page's date — the timeline places it by the year
        String f1 = store.nextFindingId("Example Corporation charter date");
        store.write(new Finding(f1, "Example Corporation charter date", List.of(), Finding.State.draft, Finding.ClaimType.extraction, Finding.Confidence.high, "patron:test", Instant.now().toString(), "2021-02-17", Finding.Volatility.stable, "",
                List.of(new Finding.Source("https://a.example/charter", "n/a", "x")), List.of(), null, "The Example Corporation, formally the President and Fellows of Example College, was chartered in 1650.\n", new Finding.Triple("Example Corporation", "chartered on", "1650"), List.of()));
        // the subject's own claim that names its legal name: the claims under that name are the subject's too
        String f2 = store.nextFindingId("Legal name of Example University");
        store.write(new Finding(f2, "Legal name of Example University", List.of(), Finding.State.draft, Finding.ClaimType.extraction, Finding.Confidence.high, "patron:test", Instant.now().toString(), "2026-10-26", Finding.Volatility.stable, "",
                List.of(new Finding.Source("https://a.example/charter", "n/a", "x")), List.of(), null, "The legal name of Example University is President and Fellows of Example College, known as the Example Corporation.\n", new Finding.Triple("Example University", "has legal name", "Example Corporation"), List.of()));
        String i1 = store.nextInvestigationId("Who is Example University? A full dossier.");
        Investigation inv = new Investigation(i1, "Who is Example University? A full dossier.", Finding.State.accepted, "patron:test", Instant.now().toString(), List.of(f2), List.of(), BODY);
        store.write(inv);
        List<String> filed = TimelineClaims.file(store, inv, "Example University");
        assertEquals(5, filed.size(), "four milestones and one decision: " + filed);
        Finding founding = null;
        for (String id : filed) { Finding f = store.finding(id); if (f.title().contains("1636")) founding = f; }
        assertTrue(founding != null && founding.validAsOf().equals("1636") && founding.title().startsWith("Example University, 1636: " + TimelineClaims.MILESTONE), String.valueOf(founding));
        Dossier.Gathered d = Dossier.gather(store, "Example University");
        assertTrue(d.claims().stream().anyMatch(f -> f.id().equals(f1)), "the charter claim under the legal name is gathered through the subject's own identity claim: " + d.claims().stream().map(Finding::id).toList());
        assertEquals(4, Dossier.milestones(d.claims()).size());
        String md = Dossier.of(store, "Example University").path("markdown").asText();
        int tl = md.indexOf("## Timeline\n");
        String timeline = md.substring(tl, md.indexOf("\n## ", tl + 5));
        String[] lines = timeline.strip().split("\n");
        assertTrue(lines[2].startsWith("- 1636: Example University, 1636: " + TimelineClaims.MILESTONE + "Founded"), "the founding opens the timeline: " + lines[2]);
        assertTrue(lines[3].startsWith("- 1639: "), lines[3]);
        assertTrue(timeline.contains("- 1650: Example University, 1650: " + TimelineClaims.MILESTONE + "chartered") && timeline.contains("- 1650: Example Corporation charter date [") , "the charter twice: the milestone and the identity claim at its year, not the page's: " + timeline);
        assertTrue(timeline.indexOf("- 1650: Example University, 1650: " + TimelineClaims.MILESTONE) < timeline.indexOf("- 1650: Example Corporation charter date"), "the milestone first within the year");
        assertTrue(timeline.indexOf("- 2010: ") > timeline.indexOf("- 1780: "), timeline);
        assertFalse(timeline.contains("- 2021: "), "the charter claim is placed at its year, not at the page's date: " + timeline);
        String charter = timeline.substring(timeline.indexOf("- 1650: Example Corporation charter date ["), timeline.indexOf("\n", timeline.indexOf("- 1650: Example Corporation charter date [")));
        assertTrue(charter.contains(", said 2021-02-17] ("), "the page's date is said as the day the source said it: " + charter);
        String html = DossierPage.render(store, "Example University");
        assertTrue(html.contains("<h2 id=\"timeline\">Timeline</h2><div class=\"sec\"><p class=\"k\">From 1 run: Who is Example University? A full dossier. Milestones, decisions, statements and media, the milestones first within a year.</p>"), html.substring(html.indexOf("id=\"timeline\""), Math.min(html.length(), html.indexOf("id=\"timeline\"") + 400)));
        assertTrue(html.indexOf("Founded 1636") < html.indexOf("grew from 7 to 13"), "the page's timeline opens with the founding");
    }

    @Test
    void aTimelineWithNoMilestoneSaysTheFoundingComesWithTheFullResearch(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        String body = "## Question\n\nCorporate history of Example University: who holds power over it and how it decides.\n\n## Answer\n\nTwo boards [1].\n\n## Decisions\n\n2010-12: Decision — the Corporation grew from 7 to 13 members [1].\n\n## References\n\n[1] https://a.example/charter\n";
        String i1 = store.nextInvestigationId("Corporate history of Example University: who holds power over it and how it decides.");
        Investigation inv = new Investigation(i1, "Corporate history of Example University: who holds power over it and how it decides.", Finding.State.accepted, "patron:test", Instant.now().toString(), List.of(), List.of(), body);
        store.write(inv);
        TimelineClaims.file(store, inv, "Example University");
        String html = DossierPage.render(store, "Example University");
        assertTrue(html.contains("<p class=\"k\">From 1 run: Corporate history of Example University: who holds power over it and ") && html.contains(". Milestones, decisions, statements and media; the founding and the basics come with the full research.</p>"), html.substring(html.indexOf("id=\"timeline\""), Math.min(html.length(), html.indexOf("id=\"timeline\"") + 500)));
    }

    @Test
    void theBriefsAskForTheMajorThingsFirst() {
        var org = new OrganizationProfile();
        assertTrue(org.planRules().contains("The first sub-question establishes the origin and the milestones, the major things first: founded or established (the year, the place, by whom, under what name)"), org.planRules());
        assertTrue(org.criticRules().startsWith("For an organisation's dossier the evidence is not enough while the founding is missing or rests on no source"), org.criticRules());
        assertTrue(org.writerRules().contains("Milestones (one a line, YEAR: what happened") && org.writerRules().contains("Registry card (opening with the founding and the charter, Founded YEAR in PLACE as NAME [n]"), org.writerRules());
        var person = new PersonProfile();
        assertTrue(person.planRules().contains("born when and where; educated where and when; the roles in order with their years"), person.planRules());
        assertTrue(person.writerRules().contains("Timeline (year by year, opening with the milestones"), person.writerRules());
        assertTrue(Goals.named("dossier").decisionRules().startsWith("The major things first: for an organisation its founding"), Goals.named("dossier").decisionRules());
    }
}
