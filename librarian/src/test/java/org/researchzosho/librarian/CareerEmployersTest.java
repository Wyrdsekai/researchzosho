package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The employers in a decided field come from the library's own research, once a month for the same field, role and place; the model reads the names out of the answer. */
class CareerEmployersTest {
    static final class StandIn implements Researcher.Drive {
        @Override public ObjectNode chat(ArrayNode m, ArrayNode t, int x, String y) { throw new UnsupportedOperationException(); }
        @Override public int contextWindow() { return 32_000; }
        @Override public String prose(ArrayNode m, int max) { return CareerInterviewTest.READING; }
        @Override public String classify(ArrayNode m, int max) {
            String p = m.get(0).path("content").asText();
            if (p.startsWith("From the library's research answer below") && p.contains("Embark"))
                return "[{\"name\": \"Embark Veterinary\", \"website\": \"https://embarkvet.com\", \"where\": \"Boston, MA\", \"why\": \"dog DNA tests, hires software engineers\"}, "
                        + "{\"name\": \"Ethos Veterinary Health\", \"website\": \"\", \"where\": \"Woburn, MA\", \"why\": \"veterinary hospitals with engineering staff\"}, {\"name\": \"embark veterinary\", \"website\": \"\", \"where\": \"\", \"why\": \"named twice\"}]";
            return "[]";
        }
    }

    @Test void theEmployersInTheFieldComeFromTheLibrarysResearchOnceAMonth(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Career.DRIVE = StandIn::new;   // before the seeker: nothing here reaches for a model of the box's
        Career.Seeker s = CareerInterviewTest.seeker(store, tmp);
        List<String> asked = new ArrayList<>(), phases = new ArrayList<>();
        CareerEmployers.RESEARCH = (st, ask, title, stop) -> { asked.add(ask.question() + " || " + title + " || " + ask.maxMinutes() + " min, " + ask.subQuestions().size() + " sub-questions"); return "Embark Veterinary (embarkvet.com), in Boston, makes dog DNA tests and hires software engineers [1]. Ethos Veterinary Health, Woburn, runs veterinary hospitals [2]."; };
        try {
            List<CareerEmployers.Named> named = CareerEmployers.find(store, s, "pets or animals", "senior engineer", "Boston, MA", phases::add, () -> false);
            assertEquals(List.of("Embark Veterinary", "Ethos Veterinary Health"), named.stream().map(CareerEmployers.Named::name).toList(), "each once, the second spelling of a name left out");
            assertEquals("https://embarkvet.com", named.get(0).site()); assertEquals("Woburn, MA", named.get(1).where());
            assertEquals(1, asked.size());
            assertTrue(asked.get(0).startsWith("Which employers in the field of pets or animals, in or near Boston, MA, employ people as senior engineer?") && asked.get(0).contains("|| Employers in the field of pets or animals near Boston, MA || 8 min, 3 sub-questions"), asked.get(0));
            assertTrue(phases.get(0).startsWith("Researching the employers in the field of pets or animals near Boston, MA, about 8 minutes"), phases.get(0));
            String log = Files.readString(s.dir(store).resolve("log.jsonl"));
            assertTrue(log.contains("Employers in the field of pets or animals near Boston, MA, by the library's research: Embark Veterinary, Ethos Veterinary Health. Each is checked by its hiring page before its openings are read."), log);
            // the month's answer serves again for the same field, role and place; another field is researched afresh
            CareerEmployers.RESEARCH = (st, ask, title, stop) -> { throw new IllegalStateException("researched again"); };
            assertEquals(2, CareerEmployers.find(store, s, "pets or animals", "senior engineer", "Boston, MA", phases::add, () -> false).size());
            CareerEmployers.RESEARCH = (st, ask, title, stop) -> "";
            assertTrue(CareerEmployers.find(store, s, "oncology nursing", "nurse", "Austin, Texas", phases::add, () -> false).isEmpty(), "an empty answer names nobody");
            assertTrue(Files.readString(s.dir(store).resolve("log.jsonl")).contains("The library's research named no employer in the field of oncology nursing near Austin, Texas."));
            // the run that cannot be made is said, and the search goes on without it
            CareerEmployers.RESEARCH = (st, ask, title, stop) -> { throw new IllegalStateException("no model answers"); };
            assertTrue(CareerEmployers.find(store, s, "vegan kitchens", "chef", "Pune", phases::add, () -> false).isEmpty());
            assertTrue(Files.readString(s.dir(store).resolve("log.jsonl")).contains("could not research the employers in your field this time: no model answers"));
        } finally { Career.DRIVE = Explain::drive; CareerEmployers.RESEARCH = CareerEmployers::research; }
    }
}
