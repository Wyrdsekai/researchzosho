package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** A dossier report's Timeline read into one claim a dated event (0.6.6), sourced by the references each cites; a second pass files nothing. */
class TimelineClaimsTest {
    static final String BODY = "## Question\n\nWho is Ada Example? A full dossier.\n\n## Answer\n\nAda Example is an engineer [1].\n\n## Timeline\n\n"
            + "1959: Born Fort Meade, MD [7]. 1978–79: Research assistant, Ames Lab, DOE [29]. 1984: The Big U [84]. 1992: Snow Crash [7][84]. 2004: Confusion; System of the World [84]. 2030: Nothing cited here.\n\n"
            + "## References\n\n[1] Wikidata Q1 — https://www.wikidata.org/wiki/Q1  (dated 2024)  [third-party]\n[7] https://en.wikipedia.org/wiki/Ada_Example  [third-party]\n[29] Encyclopedia — https://www.encyclopedia.com/x  [third-party]\n[84] Summary Bibliography — https://isfdb.org/cgi-bin/ea.cgi?429=  [third-party]\n\n4 source(s).\n";

    @Test
    void theEventsAreReadWithTheirReferences() {
        List<TimelineClaims.Event> ev = TimelineClaims.events(BODY);
        assertEquals(6, ev.size(), ev.toString());
        assertEquals("1959", ev.get(0).year()); assertEquals("Born Fort Meade, MD", ev.get(0).text()); assertEquals(List.of("https://en.wikipedia.org/wiki/Ada_Example"), ev.get(0).locators());
        assertEquals("1978–79", ev.get(1).year()); assertEquals(List.of("https://www.encyclopedia.com/x"), ev.get(1).locators());
        assertEquals("The Big U", ev.get(2).text()); assertEquals(List.of("https://isfdb.org/cgi-bin/ea.cgi?429="), ev.get(2).locators());
        assertEquals(2, ev.get(3).locators().size(), "two citations, two references");
        assertEquals("Confusion; System of the World", ev.get(4).text());
        assertTrue(ev.get(5).locators().isEmpty(), "an event with no citation has no source");
    }

    @Test
    void fullDatesMonthsAndSpansAreEventsToo() {
        String body = "## Timeline\n\n1984-12-10: Founded by Bosack and Lerner in San Francisco, CA [2]. 1987: Bill Graves becomes CEO [47]. 1990: IPO; Bosack resigns [12][73].1995: John Chambers becomes CEO [47]. 1993-09-24: Crescendo, $94.5M [10]. 1994-07: Newport, $95M [10]. 1999–2006: Member, Technical Staff [1]. 2026-07-25: FY2026 ends; 10-K filed 2026-09-02 [14][16].\n\n## References\n\n[1] https://a.example/1\n[2] https://a.example/2\n[10] https://a.example/10\n[12] https://a.example/12\n[14] https://a.example/14\n[16] https://a.example/16\n[47] https://a.example/47\n[73] https://a.example/73\n";
        List<TimelineClaims.Event> ev = TimelineClaims.events(body);
        assertEquals(List.of("1984-12-10", "1987", "1990", "1995", "1993-09-24", "1994-07", "1999–2006", "2026-07-25"), ev.stream().map(TimelineClaims.Event::year).toList(), ev.toString());
        assertEquals("Founded by Bosack and Lerner in San Francisco, CA", ev.get(0).text());
        assertEquals("IPO; Bosack resigns", ev.get(2).text(), "a missing space before the next date still splits");
        assertEquals("FY2026 ends; 10-K filed 2026-09-02", ev.get(7).text(), "a date inside the text is not a new event");
        assertEquals(2, ev.get(7).locators().size());
    }

    @Test
    void aTimelineWrittenOneEventALineWithDashesCircaAndMonthsIsReadToo() {
        String body = "## Timeline\n\n1973 (Jul) — Born [4].\nc. 1981 — Attended St. Ignatius College Prep (yearbook on archive.org) [60].\n1991–1995 — B.S. Psychology, Trinity College; played MUDs in college [3][22].\n2003 (Aug) — Moved to Monolith Productions as Lead Game Designer [6].\n2016 (Aug–Oct) — NPD Group acquired EEDAR; announced 29 Aug 2016, closed early Oct 2016 [14].\n\n## References\n\n[3] https://a.example/3\n[4] https://a.example/4\n[6] https://a.example/6\n[14] https://a.example/14\n[22] https://a.example/22\n[60] https://a.example/60\n";
        List<TimelineClaims.Event> ev = TimelineClaims.events(body);
        assertEquals(List.of("1973 (Jul)", "c. 1981", "1991–1995", "2003 (Aug)", "2016 (Aug–Oct)"), ev.stream().map(TimelineClaims.Event::year).toList(), ev.toString());
        assertEquals("Born", ev.get(0).text()); assertEquals(List.of("https://a.example/4"), ev.get(0).locators());
        assertEquals("NPD Group acquired EEDAR; announced 29 Aug 2016, closed early Oct 2016", ev.get(4).text(), "a date inside the text stays inside it");
        assertEquals(2, ev.get(2).locators().size());
    }

    @Test
    void decadesApproximationsAndMonthNamesAreDatesToo() {
        String body = "## Timeline\n\n- Early 2000s: AOL, Dulles VA — Senior Marketing Manager [1].\n- ~2005–2007: Monster.com, Maynard MA — VP Product Management (dates unspecified) [1].\n- Jan 2007–Jan 2009: JNJ Mobile / MocoSpace.com [1].\n- Sep 2021 – Jun 2024: VP Engineering [1][2].\n- Late 2019: Studio Director [2].\n\n## References\n\n[1] file:///home/someone/profile.json\n[2] https://a.example/2\n";
        List<TimelineClaims.Event> ev = TimelineClaims.events(body);
        assertEquals(List.of("Early 2000s", "~2005–2007", "Jan 2007–Jan 2009", "Sep 2021 – Jun 2024", "Late 2019"), ev.stream().map(TimelineClaims.Event::year).toList(), ev.toString());
        assertEquals("AOL, Dulles VA — Senior Marketing Manager", ev.get(0).text(), "a dash inside the event stays inside it");
        assertEquals("Monster.com, Maynard MA — VP Product Management (dates unspecified)", ev.get(1).text());
    }

    @Test
    void aSuppliedFileIsASourceForAnEventLikeAWebPage() {
        String body = "## Timeline\n\n1992–1997: BS in Electrical Engineering, Trinity College [6]. 1998–1999: Technical Support, WebLine Communications [6][7].\n\n## References\n\n[6] LinkedIn export — file:///home/someone/profile.json  [supplied]\n[7] https://www.sec.gov/x  [public record]\n";
        List<TimelineClaims.Event> ev = TimelineClaims.events(body);
        assertEquals(2, ev.size(), ev.toString());
        assertEquals(List.of("file:///home/someone/profile.json"), ev.get(0).locators(), "the supplied profile sources the event");
        assertEquals(List.of("file:///home/someone/profile.json", "https://www.sec.gov/x"), ev.get(1).locators());
        assertEquals(SourceClass.supplied, SourceClass.of(ev.get(0).locators().get(0)));
    }

    @Test
    void anOrganisationsFilingsWrittenAsABulletedTimelineAreEventsToo() {
        String body = "## Registry card\n\nCisco Systems, Inc. [1].\n\n## Filings\n\n- 1984-12-10: Incorporated in California [24][5].\n- 1987-04-15: Stanford licenses router software to Cisco: $19,300 cash + $150,000 royalties [18][5].\n- 1990-08-28: Lerner fired; Bosack resigns [17][22].\n\n## Money\n\n1987: Sequoia invests $2.5 million [6].\n\n## References\n\n[1] https://a.example/1\n[5] https://a.example/5\n[17] https://a.example/17\n[18] https://a.example/18\n[22] https://a.example/22\n[24] https://a.example/24\n";
        List<TimelineClaims.Event> ev = TimelineClaims.events(body);
        assertEquals(List.of("1984-12-10", "1987-04-15", "1990-08-28"), ev.stream().map(TimelineClaims.Event::year).toList(), "the Filings' bullets, not the Money paragraph: " + ev);
        assertEquals("Stanford licenses router software to Cisco: $19,300 cash + $150,000 royalties", ev.get(1).text());
        assertEquals(List.of("https://a.example/18", "https://a.example/5"), ev.get(1).locators());
    }

    /** An organisation report's Filings paragraph with dates written the American way, sourced by a Sources list and no References (I-0188, 2026-10-25). */
    static final String FILINGS = "## Question\n\nThe Lown Hospitals Index, the Lown Institute's flagship project: what it is and who built it.\n\n## Answer\n\nx [2].\n\n## Filings\n\n"
            + "1973: Lown Cardiovascular Research Foundation founded by Bernard Lown [2]. January 1996: 501(c)(3) tax exemption issued [4]. 2012: renamed the Lown Institute [2]. 2013: Right Care Alliance founded [33]. "
            + "July 7, 2020: Lown Hospitals Index first published (3,282 hospitals) [10]. September 2021: 2021 methodology published (54 metrics) [11]. March 30, 2021: Shannon Brownlee steps down [7]. "
            + "February 17, 2023: Kern, Robinson, Rogers welcomed to board [8]. 24 June 2025: 2025/26 edition launched [22]. June 2026: 2026-27 edition released (3,618 hospitals) [28][29]. FY2025 990 filed (fiscal year ending Aug 2025) [4].\n\n"
            + "## Sources\n\n[2] History of the Lown Institute (own publication) — https://lowninstitute.org/about/history/\n[4] Lown Institute Inc - Nonprofit Explorer - ProPublica (public record) — https://projects.propublica.org/nonprofits/organizations/43291770\n"
            + "[7] Press release (own publication, 2021-03-30) — https://lowninstitute.org/press-release-steps-down/\n[8] Press release (own publication, 2023-02-17) — https://lowninstitute.org/press-release-board/\n[10] Health Capital (third-party, 2020-07-07) — https://www.healthcapital.com/lown.php\n"
            + "[11] 2021 Methodology (own publication, 2021-09) — https://lownhospitalsindex.org/2021_Methodology.pdf\n[22] Constructive (third-party, 2025-06-24) — https://constructive.co/news/lown/\n[28] 2026-27 Rankings — https://2026.lownhospitalsindex.org/rankings/\n[29] https://lowninstitute.org/press-2026/\n[33] Right Care Alliance — https://rightcarealliance.org/about/\n";

    @Test
    void americanDatesAreReadAndTheSourcesListResolvesTheCitationsWhenThereAreNoReferences() {
        List<TimelineClaims.Event> ev = TimelineClaims.events(FILINGS);
        assertEquals(List.of("1973", "January 1996", "2012", "2013", "July 7, 2020", "September 2021", "March 30, 2021", "February 17, 2023", "24 June 2025", "June 2026"),
                ev.stream().map(TimelineClaims.Event::year).toList(), "ten dated events, a date with its day on either side of the month read like any other: " + ev);
        assertEquals("Lown Hospitals Index first published (3,282 hospitals)", ev.get(4).text());
        assertEquals(List.of("https://www.healthcapital.com/lown.php"), ev.get(4).locators(), "the citation resolved in the Sources list");
        assertEquals("2026-27 edition released (3,618 hospitals). FY2025 990 filed (fiscal year ending Aug 2025)", ev.get(9).text(), "an undated sentence stays with the event before it");
        assertEquals(3, ev.get(9).locators().size());
        assertTrue(ev.stream().allMatch(e -> !e.locators().isEmpty()), "every event sourced: " + ev);
    }

    @Test
    void theQuestionNamesTheMakerOfAFlagshipOrOpensWithItsSubject() {
        assertEquals("the Lown Institute", TimelineClaims.subjectOf("The Lown Hospitals Index, the Lown Institute's flagship project: what it is and what it claims to measure, how it ranks hospitals."), "a project is a facet of its maker");
        assertEquals("Acme Corp", TimelineClaims.subjectOf("Acme Corp, the Needham maker of widgets: who runs it and who owns it?"));
        assertEquals("The New School", TimelineClaims.subjectOf("The New School, the university in New York City: a full dossier."));
        assertEquals("Ada Example", TimelineClaims.subjectOf("Who is Ada Example, the engineer: a full dossier?"), "the older shapes first");
        assertNull(TimelineClaims.subjectOf("How do forensic auditors use Benford's law?"), "a question with no subject before a colon names none");
        assertNull(TimelineClaims.subjectOf("As of October 2026, the library holds a survey of readers: what does it say?"), "a question that opens with a date is not about a name");
        assertNull(TimelineClaims.subjectOf("For anime, which streaming services carry subtitles: a comparison."), "nor one that opens with a preposition");
        assertNull(TimelineClaims.subjectOf("What does a fully agentic, self-hosted research tool look like: a survey?"), "nor a sentence");
        assertNull(TimelineClaims.subjectOf("History of the iaido gi (the training uniform): where the cut came from."), "a lowercase thing after 'history of' is a thing, not a name");
        assertEquals("the Lown Institute", TimelineClaims.subjectOf("History of the Lown Institute: a full dossier."), "the article stays with the name");
        assertEquals("高峰譲吉", TimelineClaims.subjectOf("Family history of 高峰譲吉: his line."), "a name in kanji is a name");
    }

    @Test
    void anEventDatedThreeWaysIsOneClaim(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        String i1 = store.nextInvestigationId("Corporate history of the Lown Institute: a full dossier.");
        Investigation first = new Investigation(i1, "Corporate history of the Lown Institute: a full dossier.", Finding.State.accepted, "patron:test", Instant.now().toString(), List.of(), List.of(),
                "## Filings\n\n2020: Lown Hospitals Index first published [1]. 2021 (Feb 2): Patricia Gabow named Board Chair [1].\n\n## References\n\n[1] https://a.example/\n");
        store.write(first);
        assertEquals("the Lown Institute", TimelineClaims.subjectOf(store, first));
        assertEquals(2, TimelineClaims.file(store, first, "the Lown Institute").size());
        String i2 = store.nextInvestigationId("The Lown Hospitals Index, the Lown Institute's flagship project: what it is.");
        Investigation second = new Investigation(i2, "The Lown Hospitals Index, the Lown Institute's flagship project: what it is.", Finding.State.accepted, "patron:test", Instant.now().toString(), List.of(), List.of(), FILINGS);
        store.write(second);
        assertEquals("the Lown Institute", TimelineClaims.subjectOf(store, second), "the maker's dossier");
        List<String> filed = TimelineClaims.file(store, second, "the Lown Institute");
        assertEquals(9, filed.size(), "ten events, of which July 7, 2020 is the 2020 event already filed: " + filed);
        assertTrue(filed.stream().map(id -> { try { return store.finding(id).title(); } catch (Exception e) { throw new RuntimeException(e); } }).anyMatch(t -> t.equals("the Lown Institute, March 30, 2021: Shannon Brownlee steps down")), filed.toString());
        assertEquals(0, TimelineClaims.file(store, second, "the Lown Institute").size(), "a second pass files nothing");
        // each claim holds on its event's date, not the day it was filed
        Map<String, String> held = new HashMap<>();
        for (String id : filed) { Finding f = store.finding(id); held.put(f.title(), f.validAsOf()); }
        assertEquals("2023-02-17", held.get("the Lown Institute, February 17, 2023: Kern, Robinson, Rogers welcomed to board"), held.toString());
        assertEquals("2013", held.get("the Lown Institute, 2013: Right Care Alliance founded"), held.toString());
    }

    @Test
    void theDecisionsOfThePeopleInPowerAreEventsMarkedAsDecisions(@TempDir Path tmp) throws Exception {
        String body = "## Filings\n\n2012: renamed the Lown Institute [1].\n\n## Decisions\n\n2012-01: The directors of the Lown Foundation decided to change the mission from clinical care to health policy — "
                + "\"the right care at the right time\" [1] — Saini and Brownlee — the Institute as it stands [1].\n2023-09-11: The board accepted Arnold Ventures' $1.5M — "
                + "\"to study hospital tax exemptions\" [2] — Saini — two reports [2].\n\n## References\n\n[1] https://a.example/history\n[2] https://a.example/grant\n";
        List<TimelineClaims.Event> ev = TimelineClaims.events(body);
        assertEquals(3, ev.size(), ev.toString());
        assertEquals("renamed the Lown Institute", ev.get(0).text());
        assertTrue(ev.get(1).text().startsWith(TimelineClaims.DECISION + "The directors of the Lown Foundation decided"), ev.get(1).text());
        assertEquals("2023-09-11", ev.get(2).year()); assertEquals(List.of("https://a.example/grant"), ev.get(2).locators());
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        String i1 = store.nextInvestigationId("Corporate history of the Lown Institute: a full dossier.");
        Investigation inv = new Investigation(i1, "Corporate history of the Lown Institute: a full dossier.", Finding.State.accepted, "patron:test", Instant.now().toString(), List.of(), List.of(), body);
        store.write(inv);
        List<String> filed = TimelineClaims.file(store, inv, "the Lown Institute");
        assertEquals(3, filed.size(), filed.toString());
        List<Finding> all = new ArrayList<>(); for (String id : filed) all.add(store.finding(id));
        List<Finding> decisions = Dossier.decisions(all);
        assertEquals(2, decisions.size(), "the two decisions, apart from the plain event: " + decisions);
        assertTrue(decisions.get(0).title().startsWith("the Lown Institute, 2012-01: Decision — "), decisions.get(0).title());
        assertEquals("2023-09-11", decisions.get(1).validAsOf());
        String md = Dossier.of(store, "the Lown Institute").path("markdown").asText();
        assertTrue(md.contains("## Decisions\n") && md.contains("- 2023: ") , md);
        String html = DossierPage.render(store, "the Lown Institute");
        assertTrue(html.contains("<h2 id=\"decisions\">Decisions</h2>") && html.contains("Arnold Ventures"), html);
    }

    @Test
    void aDecisionCitingAClaimIsSourcedByThatClaimAndAFaultedSentenceIsNotAClaim(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Finding strata = new Finding("F-0001-stratacom", "Cisco Systems, 1996: acquired StrataCom", List.of(), Finding.State.accepted, Finding.ClaimType.extraction, Finding.Confidence.high, "patron:test",
                Instant.now().toString(), "1996-04", Finding.Volatility.stable, "", List.of(new Finding.Source("https://news.example/stratacom", "n/a", "x"), new Finding.Source("https://sec.example/8k", "n/a", "x")), List.of(), null, "Cisco acquired StrataCom for $4B.\n");
        store.write(strata);
        String body = "## Decisions\n\n1996-04: Acquired StrataCom for $4B (ATM switching) [F-0001].\n2001-03-09: Chambers announced cost-cutting. Chambers: \"We're taking these steps because of the [market environment]\" [1].\n"
                + "2007-03-15: Acquired WebEx for $3.2B [F-9999-missing].\n2010-04-18: Acquired Tandberg for $3.3B [1]. [not supported by the cited source on check]\n\n"
                + "## Checks\n\n- quotation \"We're taking these steps because of the [market environment]\" — in no source read this run\n\n## References\n\n[1] https://news.example/layoffs\n";
        String i1 = store.nextInvestigationId("Corporate history of Cisco Systems, Inc.: power.");
        Investigation inv = new Investigation(i1, "Corporate history of Cisco Systems, Inc.: power.", Finding.State.accepted, "patron:test", Instant.now().toString(), List.of(), List.of(), body);
        store.write(inv);
        List<TimelineClaims.Event> ev = TimelineClaims.events(body);
        assertEquals(4, ev.size()); assertEquals(List.of("claim:F-0001"), ev.get(0).locators(), "the writer cites a claim by its code"); assertEquals(TimelineClaims.DECISION + "Acquired StrataCom for $4B (ATM switching)", ev.get(0).text());
        List<String> filed = TimelineClaims.file(store, inv, "Cisco Systems");
        assertEquals(1, filed.size(), "the StrataCom decision, sourced by its claim's two sources; the faulted quotation, the missing claim and the marked sentence are not claims: " + filed);
        Finding f = store.finding(filed.get(0));
        assertEquals(List.of("https://news.example/stratacom", "https://sec.example/8k"), f.sources().stream().map(Finding.Source::locator).toList());
        assertEquals("1996-04", f.validAsOf());
        assertEquals(List.of("we re taking these steps because of the market environment"), TimelineClaims.faulted(body));
        // two cuts in one year with different numbers are two decisions; the same event in other words is one
        String more = "## Decisions\n\n2024-02-14: Robbins announced 5% workforce cut (~4,245 jobs) [1].\n2024-08: ~6,000 jobs cut (7%) [1].\n2024-08-14: About 6,000 jobs were cut, seven percent of the workforce [1].\n\n## References\n\n[1] https://news.example/cuts\n";
        String i2 = store.nextInvestigationId("Corporate history of Cisco Systems, Inc.: cuts.");
        Investigation cuts = new Investigation(i2, "Corporate history of Cisco Systems, Inc.: cuts.", Finding.State.accepted, "patron:test", Instant.now().toString(), List.of(), List.of(), more);
        store.write(cuts);
        List<String> two = TimelineClaims.file(store, cuts, "Cisco Systems");
        assertEquals(2, two.size(), "February and August, once each: " + two);
    }

    @Test
    void statementsAndMediaAreEventsMarkedAsSuchAndTheDossierListsThem(@TempDir Path tmp) throws Exception {
        String body = "## Statements\n\n2019-06: Science Fiction Book Club interview — \"Heinlein has stuck with me more than any of the others\" [1].\n\n"
                + "## Media\n\n2019: a portrait at a book signing — by C. Michel — File:Ada Example in 2019.jpg, Wikimedia Commons (page 123), CC BY-SA 4.0 [2].\n2023: a talk at the gears summit — Gears Summit channel — YouTube — https://www.youtube.com/watch?v=dQw4w9WgXcQ [3].\n\n"
                + "2020: a lecture portrait, Wikimedia Commons, CC BY 4.0 [4].\n\n"
                + "## References\n\n[1] https://a.example/interview\n[2] https://commons.wikimedia.org/w/index.php?curid=123\n[3] https://www.youtube.com/watch?v=dQw4w9WgXcQ\n[4] File:Ada Example lecture 2020.jpg, Wikimedia Commons (page 456) — https://commons.wikimedia.org/w/index.php?curid=456  [third-party]\n";
        List<TimelineClaims.Event> ev = TimelineClaims.events(body);
        assertEquals(4, ev.size(), ev.toString());
        assertTrue(ev.get(0).text().startsWith(TimelineClaims.SAID + "Science Fiction Book Club interview") && ev.get(1).text().startsWith(TimelineClaims.MEDIA + "a portrait"), ev.toString());
        assertEquals("File:Ada Example lecture 2020.jpg, Wikimedia Commons", TimelineClaims.refTitles(body).get("https://commons.wikimedia.org/w/index.php?curid=456"), "the reference's title, without its page note");
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        String i1 = store.nextInvestigationId("Who is Ada Example? A full dossier.");
        Investigation inv = new Investigation(i1, "Who is Ada Example? A full dossier.", Finding.State.accepted, "patron:test", Instant.now().toString(), List.of(), List.of(), body);
        store.write(inv);
        assertEquals(4, TimelineClaims.file(store, inv, "Ada Example").size());
        Dossier.Gathered d = Dossier.gather(store, "Ada Example");
        assertEquals(1, Dossier.statements(d.claims()).size()); assertEquals(3, Dossier.media(d.claims()).size()); assertEquals(0, Dossier.decisions(d.claims()).size());
        assertTrue(Dossier.media(d.claims()).stream().anyMatch(f -> f.body().contains("a lecture portrait, Wikimedia Commons, CC BY 4.0 (File:Ada Example lecture 2020.jpg, Wikimedia Commons)")), "the media claim names the file its reference carries");
        String md = Dossier.of(store, "Ada Example").path("markdown").asText();
        assertTrue(md.contains("## In their own words\n") && md.contains("## Media\n") && md.contains("Heinlein has stuck"), md);
        String html = DossierPage.render(store, "Ada Example");
        assertTrue(html.contains("<h2 id=\"said\">In their own words</h2>") && html.contains("<h2 id=\"media\">Media</h2>"), html);
        assertTrue(html.contains("<img src=\"https://commons.wikimedia.org/wiki/Special:FilePath/Ada_Example_in_2019.jpg?width=240\"") && html.contains("<img src=\"https://img.youtube.com/vi/dQw4w9WgXcQ/mqdefault.jpg\"") && html.contains("Special:FilePath/Ada_Example_lecture_2020.jpg?width=240"), "thumbnails from the addresses and from the files the references name: " + html.substring(html.indexOf("id=\"media\""), html.indexOf("id=\"media\"") + 900));
        assertTrue(html.contains("<h2 id=\"pattern\">Pattern of life</h2>") && html.contains("<ul class=\"bars\">"), "the pattern of life, from the three dated claims");
        // a media claim filed before the files were named: the thumbnail comes from what the report's reference list calls its address
        String old = store.nextFindingId("Ada Example, 2008: Media — a photograph at a camp, by B. Lee, Wikimedia Commons, CC");
        store.write(new Finding(old, "Ada Example, 2008: Media — a photograph at a camp, by B. Lee, Wikimedia Commons, CC", List.of(), Finding.State.draft, Finding.ClaimType.extraction, Finding.Confidence.medium, "librarian:timeline", Instant.now().toString(), "2008", Finding.Volatility.stable, "",
                List.of(new Finding.Source("https://commons.wikimedia.org/w/index.php?curid=789", "n/a", "cited for this event in " + i1)), List.of(), null, "Ada Example, 2008: Media — a photograph at a camp, by B. Lee, Wikimedia Commons, CC.\n"));
        String i2 = store.nextInvestigationId("Who is Ada Example? Media.");
        store.write(new Investigation(i2, "Who is Ada Example? Media.", Finding.State.accepted, "patron:test", Instant.now().toString(), List.of(old), List.of(), "## Question\n\nWho is Ada Example? Media.\n\n## Answer\n\nx [1]\n\n## References\n\n[1] File:Ada Example camp 2008.jpg, Wikimedia Commons (page 789) — https://commons.wikimedia.org/w/index.php?curid=789  [third-party]\n"));
        assertTrue(DossierPage.render(store, "Ada Example").contains("Special:FilePath/Ada_Example_camp_2008.jpg?width=240"), "the file's name from the reference list the claim's address is listed in");
    }

    @Test
    void datesAreHeldAsWrittenAndTheAppendixOfWorkerNotesIsNotRead() {
        assertEquals("2023-02-17", TimelineClaims.heldDate("Feb 17, 2023")); assertEquals("2021-02-02", TimelineClaims.heldDate("2021 (Feb 2)")); assertEquals("1996-01", TimelineClaims.heldDate("1996 (Jan)"));
        assertEquals("1984-12-10", TimelineClaims.heldDate("1984-12-10")); assertEquals("2012", TimelineClaims.heldDate("2012")); assertEquals("1999", TimelineClaims.heldDate("1999–2006"));
        assertEquals("2025-06-24", TimelineClaims.heldDate("24 June 2025")); assertEquals("2000", TimelineClaims.heldDate("Early 2000s")); assertEquals("2016-08", TimelineClaims.heldDate("2016 (Aug–Oct)"));
        assertEquals("1981", TimelineClaims.heldDate("c. 1981")); assertEquals("2021-09", TimelineClaims.heldDate("2021-09"));
        String body = "## Filings\n\n2013: Right Care Alliance founded [1].\n\n## References\n\n[1] https://a.example/\n\n## Worker findings (fan sub-investigations, verbatim)\n\n"
                + "WHAT THE LIBRARY ALREADY HOLDS\n\n## Timeline\n\n- 1973: the Lown Institute, 1973: Bernard Lown founded it [third-party, 2026-10-24] (F-1497-x)\n- 2026: Lown Institute current leadership [third-party] (F-1515-x)\n";
        List<TimelineClaims.Event> ev = TimelineClaims.events(body);
        assertEquals(1, ev.size(), "the appendix's echo of the dossier's own timeline is not the report's: " + ev);
        assertEquals("2013", ev.get(0).year());
    }

    @Test
    void theEventsAreFiledOnceAsClaimsOnTheSubjectWithTheReportNamed(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        String i1 = store.nextInvestigationId("Who is Ada Example? A full dossier.");
        Investigation inv = new Investigation(i1, "Who is Ada Example? A full dossier.", Finding.State.accepted, "patron:test", Instant.now().toString(), List.of(), List.of(), BODY);
        store.write(inv);
        assertEquals("Ada Example", TimelineClaims.subjectOf(store, inv), "the subject from the question");
        List<String> filed = TimelineClaims.file(store, inv, "Ada Example");
        assertEquals(5, filed.size(), "five sourced events; the unsourced one is not a claim: " + filed);
        Finding big = store.finding(filed.get(2));
        assertEquals("Ada Example, 1984: The Big U", big.title());
        assertEquals("librarian:timeline", big.writer());
        assertEquals("https://isfdb.org/cgi-bin/ea.cgi?429=", big.sources().get(0).locator());
        assertTrue(big.body().contains("Timeline of " + i1), big.body());
        assertEquals("1984", Dossier.yearOf(big), "the story places it by its year");
        assertEquals(5, store.investigation(i1).findings().size(), "the report lists the claims it gave");
        assertEquals(0, TimelineClaims.file(store, inv, "Ada Example").size(), "a second pass files nothing");
        // another report of the same subject phrases two of the events differently: they are the same events, not new claims
        String i3 = store.nextInvestigationId("Ada Example again");
        Investigation again = new Investigation(i3, "Who is Ada Example?", Finding.State.accepted, "patron:test", Instant.now().toString(), List.of(), List.of(),
                "## Timeline\n\n1984: Published The Big U, her first novel [1]. 1959: Born at Fort Meade, Maryland [1]. 1996: The Cobweb [1].\n\n## References\n\n[1] https://a.example/bib\n");
        store.write(again);
        List<String> more = TimelineClaims.file(store, again, "Ada Example");
        assertEquals(1, more.size(), "only the 1996 event is new: " + more);
        assertEquals("Ada Example, 1996: The Cobweb", store.finding(more.get(0)).title());
        // the subject from the claims' lead when the question does not say
        String i2 = store.nextInvestigationId("The record of a writer");
        Investigation other = new Investigation(i2, "The record of a writer", Finding.State.accepted, "patron:test", Instant.now().toString(), List.of(filed.get(0), filed.get(1)), List.of(), "## Timeline\n\n1990: x [1].\n\n## References\n\n[1] https://a.example/\n");
        store.write(other);
        assertEquals("Ada Example", TimelineClaims.subjectOf(store, other), "the name the claims lead with, before the first comma or colon");
    }
}
