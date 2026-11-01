package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.librarian.CareerCut.Cut;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A change for one search only: read against the standing record, applied to the run's own topics, keep-outs and cut, and never to what the person decided. */
class CareerVariationTest {
    static final class StandIn implements Researcher.Drive {
        @Override public ObjectNode chat(ArrayNode m, ArrayNode t, int x, String y) { throw new UnsupportedOperationException(); }
        @Override public int contextWindow() { return 32_000; }
        @Override public String prose(ArrayNode m, int max) { return CareerInterviewTest.READING; }
        @Override public String classify(ArrayNode m, int max) {
            String p = m.get(0).path("content").asText();
            if (p.startsWith("A person looking for work has a standing record") && p.contains("without the pets field, and New Hampshire as well"))
                return "{\"topics\": [{\"dimension\": \"domain\", \"state\": \"open\", \"want\": \"\"}, {\"dimension\": \"place\", \"state\": \"decided\", \"want\": \"Boston or New Hampshire\"}], "
                        + "\"keep_out_add\": [\"nights\"], \"keep_out_drop\": [], \"companies\": [], \"said\": \"This search leaves the pets field out and looks in New Hampshire as well as Boston.\"}";
            if (p.startsWith("Turn what a person wants"))
                return p.contains("Boston or New Hampshire")
                        ? "{\"role_titles\": [\"Engineer\"], \"ownership_titles\": [], \"titles_not\": [], \"areas\": [], \"places\": [\"Boston\", \"MA\", \"New Hampshire\", \"NH\"], \"remote\": \"\", \"remote_where\": [], \"max_people\": 0, \"min_people\": 0, \"prefer_titles\": [], \"prefer_places\": []}"
                        : "{\"role_titles\": [\"Engineer\"], \"ownership_titles\": [], \"titles_not\": [], \"areas\": [], \"places\": [\"Boston\", \"MA\"], \"remote\": \"\", \"remote_where\": [], \"max_people\": 0, \"min_people\": 0, \"prefer_titles\": [], \"prefer_places\": []}";
            return "[]";
        }
    }

    @Test void theWordsChangeTheRunsTopicsAndCutAndNotTheRecord(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Career.DRIVE = StandIn::new;
        CareerCut.NEAR = (city, within, km) -> new CareerCut.Towns("", List.of());
        try {
            Career.Seeker s = CareerInterviewTest.seeker(store, tmp);
            Career.setWant(store, s, "domain", "decided", "anything to do with pets or animals");
            Career.setWant(store, s, "role", "decided", "senior engineer");
            Career.setWant(store, s, "place", "decided", "Boston");
            Cut standing = CareerCut.make(store, s);
            assertEquals(List.of("Boston", "MA"), standing.places());
            CareerVariation.Variation var = CareerVariation.read(store, s, "without the pets field, and New Hampshire as well");
            assertEquals("This search leaves the pets field out and looks in New Hampshire as well as Boston.", var.said());
            assertEquals("open", Career.find(var.wants().path("dimensions"), "domain").path("state").asText());
            assertEquals("Boston or New Hampshire", Career.find(var.wants().path("dimensions"), "place").path("want").asText());
            assertEquals("this search", Career.find(var.wants().path("dimensions"), "place").path("by").asText());
            assertTrue(var.keepOut().contains("nights"));
            assertFalse(CareerCompanies.wantsOf(var.wants()).containsKey("domain"), "the field is out for this run");
            // the record stands as it was
            assertEquals("decided", Career.find(Career.wantsKept(store, s).path("dimensions"), "domain").path("state").asText());
            assertEquals("anything to do with pets or animals", Career.find(Career.wantsKept(store, s).path("dimensions"), "domain").path("want").asText());
            assertFalse(CareerSearch.keepOut(store, s).contains("nights"));
            // the run's own cut, from the varied topics, is not the standing cut
            Cut own = CareerCut.make(store, s, var.wants(), var.keepOut(), "this search", false);
            assertEquals(List.of("Boston", "MA", "New Hampshire", "NH"), own.places());
            assertEquals(List.of("Boston", "MA"), CareerCut.kept(store, s).places(), "the standing cut stands");
            assertTrue(Files.readString(s.dir(store).resolve("log.jsonl")).contains("The library made this search's own cut from what you decided and what you said for this search: "));
            assertEquals(own, CareerCut.fromJson(CareerCut.json(own)), "a cut kept with a run reads back whole");
            // words that change nothing: the record as it stands, said so
            CareerVariation.Variation same = CareerVariation.read(store, s, "");
            assertEquals("", same.said()); assertEquals("decided", Career.find(same.wants().path("dimensions"), "domain").path("state").asText());
            // filed with the words, the job carries them and the sentence says so
            String said = CareerFind.file(store, s, "person", "career-search", "without the pets field, and New Hampshire as well");
            assertTrue(said.contains("For this search only: without the pets field, and New Hampshire as well; what you decided stands as it is."), said);
            ObjectNode job = new Jobs(store, j -> { throw new IllegalStateException("filed only"); }).active().get(0);
            assertEquals("without the pets field, and New Hampshire as well", job.path("args").path("vary").asText());
        } finally { Career.DRIVE = Explain::drive; CareerCut.NEAR = CareerCut::nearFromWikidata; }
    }
}
