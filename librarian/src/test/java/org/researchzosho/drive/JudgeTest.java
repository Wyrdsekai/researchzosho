package org.researchzosho.drive;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.net.http.HttpTimeoutException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** A typed decision from the model: the request asks for one token under a grammar, and the answer is read from the token probabilities. */
class JudgeTest {
    static final ObjectMapper M = new ObjectMapper();

    /** A server that answers with these top tokens and their log-probabilities. */
    static JsonNode server(String content, Object... tokenLogprob) throws Exception {
        ObjectNode r = M.createObjectNode();
        ObjectNode c = r.putArray("choices").addObject();
        c.putObject("message").put("role", "assistant").put("content", content);
        var tops = c.putObject("logprobs").putArray("content").addObject().put("token", content).putArray("top_logprobs");
        for (int i = 0; i < tokenLogprob.length; i += 2) tops.addObject().put("token", (String) tokenLogprob[i]).put("logprob", ((Number) tokenLogprob[i + 1]).doubleValue());
        return r;
    }

    @Test
    void aYesNoIsAskedUnderAGrammarAndReadFromTheProbabilities() throws Exception {
        ObjectNode[] sent = new ObjectNode[1];
        Judge j = new Judge("m", body -> { sent[0] = body; return server("Yes", "Yes", Math.log(0.6), "yes", Math.log(0.25), "The", Math.log(0.1), "No", Math.log(0.05)); });
        Judge.Answer a = j.noul("森田源三郎 （父）", "Do the words say a family relation?");
        assertEquals(1, sent[0].path("max_tokens").asInt());
        assertEquals("root ::= \"yes\" | \"Yes\" | \"no\" | \"No\"", sent[0].path("grammar").asText());
        assertFalse(sent[0].path("chat_template_kwargs").path("enable_thinking").asBoolean(true), "the thinking is off: the first token is the answer");
        assertTrue(sent[0].path("logprobs").asBoolean());
        assertTrue(a.yes());
        assertEquals(0.85 / 0.90, a.p("yes"), 1e-9, "yes and Yes together, over the allowed tokens only");
        assertEquals(0.05 / 0.90, a.p("no"), 1e-9);
        assertEquals(a.p("yes") - a.p("no"), a.confidence(), 1e-9);
    }

    @Test
    void aChoiceIsAnsweredByLetterAndMappedBackToTheOption() throws Exception {
        ObjectNode[] sent = new ObjectNode[1];
        Judge j = new Judge("m", body -> { sent[0] = body; return server("B", "B", Math.log(0.7), "A", Math.log(0.2), "C", Math.log(0.1)); });
        Judge.Answer a = j.choice("a page about a bridge engineer", "Who is this?", List.of("an actress", "an engineer", "a translator"));
        assertEquals("root ::= \"A\" | \"B\" | \"C\"", sent[0].path("grammar").asText());
        assertTrue(sent[0].path("messages").get(0).path("content").asText().contains("B. an engineer"));
        assertEquals("an engineer", a.choice());
        assertEquals(0.7, a.p("an engineer"), 1e-9);
        assertEquals(0.5, a.confidence(), 1e-9);
    }

    @Test
    void withoutProbabilitiesTheWordAloneCountsAndAFailureIsTheSafeAnswer() throws Exception {
        Judge j = new Judge("m", body -> { ObjectNode r = M.createObjectNode(); r.putArray("choices").addObject().putObject("message").put("content", "no"); return r; });
        Judge.Answer a = j.noul("x", "y?");
        assertFalse(a.yes()); assertEquals(1.0, a.p("no"), 1e-9); assertEquals(1.0, a.confidence(), 1e-9);
        Judge failing = new Judge("m", body -> { throw new IllegalStateException("down"); });
        Judge.Answer f = failing.noul("x", "y?");
        assertFalse(f.yes()); assertEquals(0.0, f.confidence(), 1e-9, "nothing is claimed when the server did not answer");
    }

    @Test
    void aQuestionThatGotNoAnswerHasNoProbabilityRatherThanANo() throws Exception {
        // the request did not get through: its time limit passed, or the server answered with an error in place of a reply
        for (Exception silent : List.of(new HttpTimeoutException("the model server at http://127.0.0.1:8211 gave no answer within 300 seconds"),
                new ConnectException("the model server at http://127.0.0.1:8211 answered HTTP 503: Loading model"))) {
            Judge.Answer a = new Judge("m", body -> { throw silent; }).noul("| Tom Ellis | John Ellis |", "Do the words say that Tom Ellis is a child of John Ellis?");
            assertFalse(a.ran(), "a caller that checks ran() falls back as before");
            assertSame(silent, a.unanswered());
            Judge.NoAnswer n = assertThrows(Judge.NoAnswer.class, () -> a.p("yes"), "no answer is not a probability of 0");
            assertSame(silent, n.getCause());
            assertTrue(n.getMessage().startsWith("The model did not answer when the library asked it to check a fact it read"), n.getMessage());
        }
        // the server's refusal of the typed request is an answer about the request: it did not run, as before
        Judge.Answer refused = new Judge("m", body -> { throw new IllegalStateException("HTTP 400: grammar is not supported"); }).noul("x", "y?");
        assertFalse(refused.ran());
        assertNull(refused.unanswered());
    }

    @Test
    void aGrammarTheServerIgnoredIsNoAnswer() throws Exception {
        // M2: a server that accepts the grammar field, ignores it and returns token probabilities: the generated token is no allowed
        // answer, and the sliver of "Yes" among its alternatives must not become a sure yes
        Judge ignored = new Judge("m", body -> server("The", "The", Math.log(0.9), "Yes", Math.log(0.01), "**", Math.log(0.05)));
        Judge.Answer a = ignored.noul("a reply", "Is it a decline?");
        assertFalse(a.ran(), "the grammar did not hold: the judge did not run");
        assertFalse(a.yes());
        // under a grammar that held, the allowed answers carrying almost none of the probability are noise too
        Judge sliver = new Judge("m", body -> server("yes", "The", Math.log(0.95), "yes", Math.log(0.004), "no", Math.log(0.001)));
        assertFalse(sliver.noul("a reply", "Is it a decline?").ran(), "yes and no together held 0.5% of the probability");
        // a word that is no allowed answer, with no probabilities at all
        Judge rambling = new Judge("m", body -> { ObjectNode r = M.createObjectNode(); r.putArray("choices").addObject().putObject("message").put("content", "Perhaps"); return r; });
        assertFalse(rambling.noul("x", "y?").ran());
        // an answer that holds is read as before
        Judge held = new Judge("m", body -> server("Yes", "Yes", Math.log(0.6), "No", Math.log(0.3)));
        assertTrue(held.noul("x", "y?").ran());
        assertEquals(2.0 / 3.0, held.noul("x", "y?").p("yes"), 1e-9);
    }
}
