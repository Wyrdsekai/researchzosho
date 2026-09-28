package org.researchzosho.librarian;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A model server that goes away in the middle of a run — refused connections while it restarts, then 503 while it loads its model, as
 * Wyrdsekai's brain does when it moves between the card and RAM — is waited for, and the run goes on where it was. It used to ask once
 * more after three seconds and then end the worker's sub-question.
 */
class ServerAwayRunTest {

    @Test
    void aServerThatIsAwayIsToldApartFromAnAnswerAndFromACallThatRanOutOfTime() {
        assertTrue(Researcher.serverAway(new RuntimeException("chat() failed against http://x", new ConnectException("Connection refused"))));
        assertTrue(Researcher.serverAway(new RuntimeException("chat() failed against http://x", new IOException("Connection reset"))));
        assertTrue(Researcher.serverAway(new RuntimeException("chat() failed against http://x", new HttpConnectTimeoutException("HTTP connect timed out"))));
        assertTrue(Researcher.serverAway(new IllegalStateException("drive HTTP 503: {\"error\":{\"message\":\"Loading model\"}}")));
        assertTrue(Researcher.serverAway(new IllegalStateException("drive HTTP 502: bad gateway")));
        assertFalse(Researcher.serverAway(new IllegalStateException("drive HTTP 500: failed to parse tool call")), "the model's own bad call");
        assertFalse(Researcher.serverAway(new IllegalStateException("drive HTTP 400: the request exceeds the context")), "an answer about the request");
        assertFalse(Researcher.serverAway(new RuntimeException("chat() failed against http://x", new HttpTimeoutException("gave no answer within 300 seconds"))), "held until its limit");
        assertFalse(Researcher.serverAway(new RuntimeException("chat() failed against http://x", new JsonParseException(null, "Unexpected character"))), "an answer that is not JSON");
        assertFalse(Researcher.serverAway(new IOException("the model server at http://x answered HTTP 500: out of memory")), "a 5xx about this request");
    }

    @Test
    void aRunWaitsOutAServerThatWentAwayAndGoesOnWhereItWas() {
        long[] real = Researcher.awayWaitsMs;
        Researcher.awayWaitsMs = new long[]{20, 20, 20};
        try {
            ResearcherTest.ScriptedDrive scripted = new ResearcherTest.ScriptedDrive();
            AtomicInteger calls = new AtomicInteger();
            Researcher.Drive away = new Researcher.Drive() {
                @Override public ObjectNode chat(ArrayNode messages, ArrayNode tools, int maxTokens, String toolChoice) {
                    int n = calls.incrementAndGet();
                    if (n == 4 || n == 5 || n == 6) throw new RuntimeException("chat() failed against http://127.0.0.1:8200", new ConnectException("Connection refused"));
                    if (n == 7) throw new IllegalStateException("drive HTTP 503: {\"error\":{\"message\":\"Loading model\"}}");
                    return scripted.chat(messages, tools, maxTokens, toolChoice);
                }
                @Override public String classify(ArrayNode messages, int maxTokens) { return scripted.classify(messages, maxTokens); }
                @Override public int contextWindow() { return scripted.contextWindow(); }
            };
            List<String> said = new CopyOnWriteArrayList<>();
            var r = new Researcher(away, new ResearcherTest.FakeTools(), said::add, 2).run(
                    new Researcher.Ask("How were the Antikythera gears cut, and by whom?", "broad", 60,
                            List.of("how were the gears cut?", "who cut them?")), "");
            assertTrue(calls.get() > 7, "the outage fell inside the run");
            assertTrue(r.done(), String.join("\n", said));
            assertTrue(said.stream().noneMatch(l -> l.contains("drive failed on turn")), "no worker ended its sub-question for it: " + said);
            assertTrue(said.stream().anyMatch(l -> l.contains("the run waits for it")) && said.stream().anyMatch(l -> l.contains("answers again after")), said.toString());
            assertTrue(r.evidence().contains("SUB-QUESTION: who cut them?") && r.evidence().contains("SUB-QUESTION: what tools survive?"), "both rounds researched: " + r.evidence());
            assertTrue(r.answer().startsWith("## Answer"), r.answer());
        } finally {
            Researcher.awayWaitsMs = real;
        }
    }
}
