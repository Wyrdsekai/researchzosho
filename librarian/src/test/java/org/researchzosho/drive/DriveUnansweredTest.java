package org.researchzosho.drive;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.researchzosho.drive.aws.AwsCredentials;
import org.researchzosho.drive.aws.Bedrock;
import org.researchzosho.librarian.FamilyAccount;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * An HTTP error from the model server is no answer, and says whether the model is out of reach for every request (a status every other
 * request would get too) or did not answer this one. A 503 while the model loads is waited for and asked again, within the call's limit.
 */
class DriveUnansweredTest {

    static final ObjectMapper M = new ObjectMapper();
    static final String LOADING = "{\"error\":{\"code\":503,\"message\":\"Loading model\",\"type\":\"unavailable_error\"}}";

    static ArrayNode task() { ArrayNode m = M.createArrayNode(); m.addObject().put("role", "user").put("content", "Read the notes. Answer with JSON only."); return m; }

    static HttpServer answering(int status, String body, AtomicInteger calls) throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/v1/chat/completions", x -> {
            calls.incrementAndGet();
            x.getRequestBody().readAllBytes();
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            x.getResponseHeaders().set("Content-Type", "application/json");
            x.sendResponseHeaders(status, b.length);
            try (var os = x.getResponseBody()) { os.write(b); }
        });
        s.start();
        return s;
    }

    /** What the family read makes of the last classify's silence: null when the model answered. */
    static FamilyAccount.Unanswered silence(String address) {
        IOException none = DriveClient.lastClassifyUnanswered();
        return none == null ? null : new FamilyAccount.Unanswered(address, none);
    }

    @Test
    void anErrorStatusFromTheModelServerIsNoAnswer() throws Exception {
        long[] was = DriveTesting.loadingWaits(10, 10);
        try {
            for (int status : new int[]{503, 502, 504, 401, 403, 404, 429, 500, 400}) {
                AtomicInteger calls = new AtomicInteger();
                HttpServer s = answering(status, status == 503 ? LOADING : "{\"error\":{\"message\":\"no\"}}", calls);
                String address = "http://127.0.0.1:" + s.getAddress().getPort();
                try {
                    assertEquals("", new DriveClient(address, "m").classify(task(), 64), String.valueOf(status));
                    FamilyAccount.Unanswered u = silence(address);
                    if (status == 400) { assertNull(u, "a 400 is the server's answer about this request"); assertEquals(1, calls.get()); continue; }
                    assertNotNull(u, status + " is no answer");
                    // a status every other request would get too stops a read as a model out of reach does; a 500 is about this request
                    assertEquals(status != 500, u.unreachable(), String.valueOf(status));
                    assertEquals(status == 503 ? 3 : 1, calls.get(), status + ": a 503 is waited for and asked again twice, any other error once");
                    if (status == 503) assertTrue(DriveClient.lastClassifyUnanswered().getMessage().contains("HTTP 503: Loading model"), DriveClient.lastClassifyUnanswered().getMessage());
                } finally { s.stop(0); }
            }
        } finally { DriveTesting.loadingWaits(was); }
    }

    @Test
    void aModelThatFinishesLoadingIsAskedAgainAndAnswers() throws Exception {
        long[] was = DriveTesting.loadingWaits(10, 10, 10);
        AtomicInteger calls = new AtomicInteger();
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/v1/chat/completions", x -> {
            x.getRequestBody().readAllBytes();
            boolean loading = calls.incrementAndGet() <= 2;
            byte[] b = (loading ? LOADING : "{\"choices\":[{\"index\":0,\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"{\\\"facts\\\": []}\"}}]}").getBytes(StandardCharsets.UTF_8);
            x.sendResponseHeaders(loading ? 503 : 200, b.length);
            try (var os = x.getResponseBody()) { os.write(b); }
        });
        s.start();
        try {
            DriveClient c = new DriveClient("http://127.0.0.1:" + s.getAddress().getPort(), "m");
            assertEquals("{\"facts\": []}", c.classify(task(), 64));
            assertNull(DriveClient.lastClassifyUnanswered());
            assertEquals(3, calls.get());
        } finally { s.stop(0); DriveTesting.loadingWaits(was); }
    }

    @Test
    void aDecisionAPersonWaitsOnIsNotHeldPastItsLimitForALoadingModel() throws Exception {
        long[] was = DriveTesting.loadingWaits(30_000);
        AtomicInteger calls = new AtomicInteger();
        HttpServer s = answering(503, LOADING, calls);
        try {
            DriveClient c = new DriveClient("http://127.0.0.1:" + s.getAddress().getPort(), "m").decisionTimeout(Duration.ofSeconds(2));
            long t0 = System.nanoTime();
            assertEquals("", c.classify(task(), 4));
            assertTrue(System.nanoTime() - t0 < Duration.ofSeconds(10).toNanos(), "a wait longer than the call's limit is not taken");
            assertEquals(1, calls.get());
            assertNotNull(DriveClient.lastClassifyUnanswered());
        } finally { s.stop(0); DriveTesting.loadingWaits(was); }
    }

    @Test
    void aBedrockCallThatRanOutOfTimeDidNotAnswerAndIsNotOutOfReach() {
        // Bedrock's transport says every failure as a Refused with status 0; the time limit passing is one of them
        Bedrock.Refused slow = new Bedrock.Refused(0, "Bedrock in us-east-1 could not be reached: Amazon Bedrock at bedrock-runtime.us-east-1.amazonaws.com gave no answer within 300 seconds, so the request was given up and its connection closed");
        IOException none = DriveClient.bedrockUnanswered(slow);
        assertNotNull(none, "no answer came");
        FamilyAccount.Unanswered u = new FamilyAccount.Unanswered("bedrock:us-east-1", none);
        assertFalse(u.unreachable(), "a slow answer does not end the read of the whole folder");
        assertTrue(u.said("a-notes.txt").startsWith("The model at bedrock:us-east-1 did not answer"), u.said("a-notes.txt"));
        // a connection that was never made, kept as the cause, is a model out of reach
        Bedrock.Refused down = new Bedrock.Refused(0, "Bedrock in us-east-1 could not be reached: null");
        down.initCause(new ConnectException());
        assertTrue(new FamilyAccount.Unanswered("bedrock:us-east-1", DriveClient.bedrockUnanswered(down)).unreachable());
    }

    @Test
    void aBedrockRefusalEveryRequestWouldGetAndNoSignInAreNoAnswer() {
        for (int status : new int[]{403, 404, 429, 503, 500}) {
            IOException none = DriveClient.bedrockUnanswered(new Bedrock.Refused(status, "Bedrock refused the request (HTTP " + status + ")."));
            assertNotNull(none, String.valueOf(status));
            assertEquals(status != 500, new FamilyAccount.Unanswered("bedrock:us-east-1", none).unreachable(), String.valueOf(status));
        }
        IOException signIn = DriveClient.bedrockUnanswered(new AwsCredentials.Unavailable("The AWS command line gave no credentials. Sign in first: `aws sso login`, or `aws configure` for keys."));
        assertNotNull(signIn, "no sign-in is no answer");
        assertTrue(new FamilyAccount.Unanswered("bedrock:us-east-1", signIn).unreachable(), "no request can go out without a sign-in");
        assertNull(DriveClient.bedrockUnanswered(new Bedrock.Refused(400, "This model does not read pictures.")), "a 400 is Bedrock's answer about this request");
    }
}
