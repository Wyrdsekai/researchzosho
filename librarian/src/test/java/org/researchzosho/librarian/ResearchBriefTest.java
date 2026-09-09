package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The Research Requirements Document: the model proposes, the machine applies, the run receives it verbatim. */
class ResearchBriefTest {

    private static final ObjectMapper M = new ObjectMapper();

    @Test
    void appliesPatchesAppendsDedupesRemovesAndRendersTheRun() throws Exception {
        ResearchBrief b = new ResearchBrief("i want to understand pokemon");
        assertTrue(b.empty());
        assertEquals("i want to understand pokemon", b.question());
        int n = b.apply(M.readTree("{\"question\":\"Why is Pokémon so enduringly popular?\",\"depth\":\"depth\","
                + "\"scope_in\":[\"the video games\",\"the anime\"],\"scope_out\":[\"trading-card price speculation\"],"
                + "\"sub_questions\":[\"what design choices drive collection?\",\"what does the academic literature say?\"],"
                + "\"sources\":[\"Japanese-language sources\",\"scholarly\"],\"asks\":[\"a timeline of releases\"],\"held\":[\"F-0004-x\"]}"));
        assertEquals(11, n);   // question, depth, 2+1 scope, 2 sub-questions, 2 sources, 1 ask, 1 held
        assertFalse(b.empty());
        assertEquals("depth", b.depth());
        // duplicates (case-insensitive) do not count; a -item removes
        assertEquals(1, b.apply(M.readTree("{\"scope_in\":[\"The Video Games\",\"the manga\"],\"scope_out\":[\"- trading-card price speculation\"]}")) - 1,
                "one add, one removal, one duplicate");
        String r = b.render();
        assertTrue(r.contains("question:  Why is Pokémon so enduringly popular?"));
        assertTrue(r.contains("- the manga"));
        assertFalse(r.contains("trading-card"), "removed");
        String q = b.toQuestion();
        assertTrue(q.startsWith("Why is Pokémon so enduringly popular?"));
        assertTrue(q.contains("IN SCOPE: the video games; the anime; the manga"));
        assertTrue(q.contains("SUB-QUESTIONS to answer:\n- what design choices drive collection?"));
        assertTrue(q.contains("SPECIFIC ASKS (deliverables):\n- a timeline of releases"));
        assertTrue(q.contains("ALREADY HELD by the library (build on it, do not re-research): F-0004-x"));
        assertEquals(2, b.turns());
        // garbage never breaks it
        assertEquals(0, b.apply(M.readTree("[1,2]")));
        assertEquals(0, b.apply(null));
    }
}
