package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** A worker's turn writes a tool call or a summary: its output budget is capped, so a model that runs on costs minutes, not an hour (run E, 2026-10-15: 49,152 tokens in one 42-minute call). */
class WorkerOutBudgetTest {
    @Test
    void aWorkerTurnIsCappedAndTheWriterIsNot() {
        ArrayNode history = new ObjectMapper().createArrayNode();
        history.addObject().put("role", "user").put("content", "a short question");
        assertEquals(49_152, Researcher.outBudget(history, 98_304), "the writer may use half the window");
        assertEquals(Researcher.WORKER_OUT_MAX, Researcher.workerOutBudget(history, 98_304), "a worker turn may not");
        assertEquals(4_000, Researcher.workerOutBudget(history, 8_000), "a small window: the window's share, under the cap");
        assertTrue(Researcher.workerOutBudget(history, 1_000) >= 512, "never less than the floor");
    }
}
