package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.researchzosho.drive.DriveClient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A model that thinks on its quick checks even at the lowest level its chat template lists (Inkling 2 Mini, 2026-11-25: "none" is
 * "Thinking effort level: 0" in its template, and about 290 of two runs' checks came back all thinking and were asked again with room)
 * gets the room from the first request, one request where it took two. A model that answers without thinking (the 27B) is asked as before.
 */
class QuickRoomTest {
    static final ObjectMapper M = new ObjectMapper();
    static final int ROOM = 2048;

    static ArrayNode ask() { ArrayNode m = M.createArrayNode(); m.addObject().put("role", "user").put("content", "QUESTION: is the sky green? Answer with one word: yes or no."); return m; }

    /** A model that thinks at every level and answers only with room for it. */
    static String thinksAtEveryLevel(JsonNode r) { return r.path("max_tokens").asInt() >= ROOM ? CutAnswerTest.said("no") : CutAnswerTest.thoughtOnly("The user asks"); }

    @Test
    void aDriveThatThinksAtItsLowestLevelIsGivenRoomFromTheFirstRequest() throws Exception {
        List<JsonNode> requests = Collections.synchronizedList(new ArrayList<>());
        HttpServer s = CutAnswerTest.serve(requests, QuickRoomTest::thinksAtEveryLevel, CutAnswerTest.INKLING_PROPS, null);
        try {
            DriveClient c = new DriveClient(CutAnswerTest.url(s), "m");
            assertEquals("no", c.classify(ask(), 4));   // thinks at "low": the lowest level is learnt, and asked again with room
            assertEquals(List.of(4, ROOM), maxTokens(requests));
            assertEquals("no", c.classify(ask(), 4));   // thinks at "none" too: from now on room from the first request
            assertEquals(List.of(4, ROOM, 4, ROOM), maxTokens(requests));
            assertEquals("none", requests.get(2).path("chat_template_kwargs").path("reasoning_effort").asText());
            assertEquals("no", c.classify(ask(), 4));
            assertEquals("no", c.classify(ask(), 60));
            assertEquals(List.of(4, ROOM, 4, ROOM, 4 + ROOM, 60 + ROOM), maxTokens(requests), "one request each, with the answer's own allowance beside the room");
            assertEquals("", DriveClient.lastClassifyProblem());
        } finally { s.stop(0); }
    }

    @Test
    void aDriveWhoseServerListsNoLevelsAndThinksTwiceIsGivenRoomToo() throws Exception {
        List<JsonNode> requests = Collections.synchronizedList(new ArrayList<>());
        HttpServer s = CutAnswerTest.serve(requests, QuickRoomTest::thinksAtEveryLevel, null, null);
        try {
            DriveClient c = new DriveClient(CutAnswerTest.url(s), "m");
            c.classify(ask(), 4); c.classify(ask(), 4); c.classify(ask(), 4);
            assertEquals(List.of(4, ROOM, 4, ROOM, 4 + ROOM), maxTokens(requests));
        } finally { s.stop(0); }
    }

    /** The control: a model that answers without thinking (every quick check of the 27B in the records) is asked as before, one small request. */
    @Test
    void aModelThatAnswersWithoutThinkingIsAskedAsBefore() throws Exception {
        List<JsonNode> requests = Collections.synchronizedList(new ArrayList<>());
        HttpServer s = CutAnswerTest.serve(requests, r -> CutAnswerTest.said("no"), CutAnswerTest.INKLING_PROPS, null);
        try {
            DriveClient c = new DriveClient(CutAnswerTest.url(s), "m");
            for (int i = 0; i < 4; i++) c.classify(ask(), 4);
            assertEquals(List.of(4, 4, 4, 4), maxTokens(requests));
            for (JsonNode r : requests) assertEquals("low", r.path("chat_template_kwargs").path("reasoning_effort").asText());
        } finally { s.stop(0); }
    }

    /** The control: a model that thought once and answers in the small budget at the lowest level keeps its small budget. */
    @Test
    void aModelThatStopsThinkingAtTheLowestLevelKeepsItsSmallBudget() throws Exception {
        List<JsonNode> requests = Collections.synchronizedList(new ArrayList<>());
        HttpServer s = CutAnswerTest.serve(requests, CutAnswerTest::thinksUnlessNone, CutAnswerTest.INKLING_PROPS, null);
        try {
            DriveClient c = new DriveClient(CutAnswerTest.url(s), "m");
            for (int i = 0; i < 3; i++) c.classify(ask(), 4);
            assertEquals(List.of(4, ROOM, 4, 4), maxTokens(requests));
        } finally { s.stop(0); }
    }

    static List<Integer> maxTokens(List<JsonNode> requests) { List<Integer> out = new ArrayList<>(); for (JsonNode r : requests) out.add(r.path("max_tokens").asInt()); return out; }
}
