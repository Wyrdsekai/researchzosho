package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The company card: the dossier builder's own run, bounded; the headcount for the cut read from the claims; the state on the page. */
class CareerCompanyTest {
    static final ObjectMapper M = new ObjectMapper();

    static Finding claim(String id, String title, String body, String asOf, String locator) {
        return new Finding(id, title, List.of(), Finding.State.accepted, Finding.ClaimType.extraction, Finding.Confidence.high, "model:t", Instant.now().toString(), asOf,
                Finding.Volatility.stable, "", List.of(new Finding.Source(locator, "n/a", "cited")), List.of(), null, body + "\n");
    }

    @Test void theCardIsABoundedOrganisationRunAnchoredToTheSiteAndTheHeadcountComesFromTheClaims(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Career.DRIVE = CareerInterviewTest.StandIn::new;
        try {
            Career.Seeker s = CareerInterviewTest.seeker(store, tmp);
            ObjectNode c = M.createObjectNode().put("name", "Marble").put("site", "marble.example");
            String said = CareerCompany.file(store, s, c, false, "person");
            assertTrue(said.startsWith("The company card of Marble is filed as J-"), said);
            Jobs jobs = new Jobs(store, j -> { throw new IllegalStateException("filed only"); });
            ObjectNode job = jobs.active().get(0);
            JsonNode a = job.path("args");
            assertEquals("organization", a.path("field").asText()); assertEquals(8, a.path("max_minutes").asInt()); assertEquals(12, a.path("max_turns").asInt());
            assertTrue(a.path("question").asText().startsWith("What is Marble (marble.example), the company, today:"), a.path("question").asText());
            assertEquals(3, a.path("sub_questions").size(), "the fewest questions that answer the wants");
            assertTrue(a.path("sub_questions").get(0).asText().startsWith("How many people work at Marble (marble.example)"));
            assertEquals(job.path("job_id").asText(), c.path("card").path("job_id").asText());
            assertTrue(CareerCompany.file(store, s, c, false, "person").startsWith("The card of Marble is already filed"), "not twice");
            assertTrue(CareerCompany.state(store, c).startsWith("card filed "));
            String full = CareerCompany.file(store, s, c, true, "person");
            assertTrue(full.startsWith("The full dossier of Marble is filed as J-"), full);
            assertEquals(0, jobs.active().get(1).path("args").path("max_minutes").asInt(), "the full dossier has no cap");
            assertTrue(jobs.active().get(1).path("args").path("question").asText().startsWith("Who is Marble (marble.example)? A full dossier"));

            // the run landed: its claims name the company; the headcount is the latest dated claim that says it
            assertNull(CareerCompany.people(store, "Marble"));
            store.write(claim("F-0001-marble", "Marble raised a $12M series A in 2025", "Marble, the robotics company, raised a $12M series A led by Example Ventures.", "2025-06-01", "https://www.marble.example/news/series-a"));
            store.write(claim("F-0002-marble", "Marble had about 40 employees in 2025", "By its LinkedIn page Marble had about 40 employees in June 2025.", "2025-06-30", "https://www.linkedin.com/company/marble-robotics"));
            store.write(claim("F-0003-marble", "Marble's headcount grew to 55", "Marble's headcount grew to 55 after the round, the Globe reported.", "2026-03-01", "https://www.example-globe.com/marble"));
            store.write(claim("F-0004-marble", "Marble had 58 full-time employees at year end", "As of December 31, 2026, Marble had 58 full-time employees, 30 of them in research and development.", "2026-12-31", "https://www.marble.example/annual"));
            CareerSize.People p = CareerCompany.people(store, "Marble");
            assertNotNull(p); assertEquals(58, p.low()); assertTrue(p.source().startsWith("its company card, own publication"), p.source()); assertTrue(p.source().endsWith(", as of 2026-12-31"), p.source());
            store.write(new Finding("F-0005-tiny", "Tiny headcount", List.of(), Finding.State.accepted, Finding.ClaimType.extraction, Finding.Confidence.high, "model:t", Instant.now().toString(), "2026-10-29",
                    Finding.Volatility.stable, "", List.of(new Finding.Source("https://www.linkedin.com/company/tiny", "n/a", "cited")), List.of(), null, "Tiny has approximately 5 to 6 employees, by LinkedIn and Built In.\n",
                    new Finding.Triple("Tiny", "has headcount", "5-6"), List.of()));
            CareerSize.People tiny = CareerCompany.people(store, "Tiny");
            assertEquals(5, tiny.low()); assertEquals(6, tiny.high());
            assertTrue(CareerCompany.known(store, "Marble"));
            String about = CareerCompany.about(store, "Marble");
            assertTrue(about.contains("series A") && about.contains("55"), about);
            assertFalse(CareerCompany.landed(store, c), "the run is still queued: not landed, whatever the shelves hold");
            ObjectNode fresh = M.createObjectNode().put("name", "Marble"); fresh.putObject("card").put("job_id", "J-none").put("filed", "2026-10-29").put("full", false).put("built", "");
            assertTrue(CareerCompany.landed(store, fresh), "a run no longer in the catalogue, with the claims on the shelves: landed");
            assertTrue(CareerCompany.state(store, fresh).startsWith("card built "), CareerCompany.state(store, fresh));
            assertEquals(58, fresh.path("people").path("low").asInt(), "the headcount from the card's claims is kept for the cut");
            assertEquals("series A", fresh.path("stage").asText(), "the stage too, from the claim on the round"); assertEquals("its company card", fresh.path("stage_by").asText());
            assertEquals("", CareerCompany.stage(store, "Nobody"));
            assertTrue(CareerCompany.onShelves(fresh));
        } finally { Career.DRIVE = Explain::drive; }
    }
}
