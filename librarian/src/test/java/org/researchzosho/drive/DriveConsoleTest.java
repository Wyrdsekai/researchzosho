package org.researchzosho.drive;

import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.ConnectException;
import java.net.ServerSocket;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What the model client puts on a person's terminal, against what it puts in the service's log. `research ask` on a library whose model
 * server was not running printed three `WARN DriveClient - classify() failed against http://localhost:8200: java.net.ConnectException`
 * lines (one per content check), and `youtube like` printed the service's note on how many requests the server takes at once
 * (2026-10-04). A person is told once, in a sentence; the service keeps its log lines.
 */
class DriveConsoleTest {

    /** What a call printed to standard error. */
    static String stderrOf(Callable<?> call) throws Exception {
        PrintStream was = System.err;
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        System.setErr(new PrintStream(b, true, StandardCharsets.UTF_8));
        try { call.call(); } finally { System.setErr(was); }
        return b.toString(StandardCharsets.UTF_8);
    }

    /** An address where nothing listens: a port taken and given back. */
    static String closedAddress() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) { return "http://127.0.0.1:" + s.getLocalPort(); }
    }

    static ArrayNode question() {
        ArrayNode m = DriveSharedServerTest.M.createArrayNode();
        m.addObject().put("role", "user").put("content", "Does this question need anything let in? Answer yes or no.");
        return m;
    }

    @Test
    void aPersonIsToldOnceInASentenceWhenTheModelServerDoesNotAnswer() throws Exception {
        boolean was = DriveClient.service;
        DriveClient.service = false;
        try {
            String address = closedAddress();
            String err = stderrOf(() -> { for (int i = 0; i < 3; i++) assertEquals("", new DriveClient(address, "m").classify(question(), 8)); return null; });
            String[] lines = err.strip().split("\n");
            assertEquals(1, lines.length, "one sentence for three checks: " + err);
            assertTrue(lines[0].startsWith("The model server at " + address + " does not answer (nothing is listening there)"), err);
            assertTrue(lines[0].contains("researchzosho model use <address>"), "it says what to do: " + err);
            assertFalse(err.contains("WARN") || err.contains("Exception"), "no log line, no exception name: " + err);
            assertNotNull(DriveClient.lastClassifyUnanswered(), "the caller still learns the check got no answer");
        } finally { DriveClient.service = was; }
    }

    @Test
    void theServiceLogsEachFailedCheckAsBefore() throws Exception {
        boolean was = DriveClient.service;
        DriveClient.service = true;
        try {
            String address = closedAddress();
            String err = stderrOf(() -> { for (int i = 0; i < 2; i++) new DriveClient(address, "m").classify(question(), 8); return null; });
            assertEquals(2, err.split("classify\\(\\) failed against " + address.replace(".", "\\."), -1).length - 1, "one log line per check: " + err);
            assertFalse(err.contains("researchzosho model use"), "the sentence is for a person's terminal: " + err);
        } finally { DriveClient.service = was; }
    }

    @Test
    void howManyRequestsTheServerTakesIsForTheServicesLogOnly() throws Exception {
        boolean was = DriveClient.service;
        for (boolean asService : new boolean[]{false, true}) {
            DriveSharedServerTest.Fake f = DriveSharedServerTest.fake(4, false);
            DriveClient.forgetServed();
            DriveClient.service = asService;
            try {
                String err = stderrOf(() -> { DriveSharedServerTest.askThreeAtOnce(f.base(), "m"); return null; });
                assertEquals(asService, err.contains("serves 4 requests at once"), (asService ? "the service logs it: " : "a person's terminal is not shown it: ") + err);
            } finally { DriveClient.service = was; f.server().stop(0); }
        }
    }

    @Test
    void whyARequestDidNotArriveInWords() {
        assertEquals("does not answer (nothing is listening there)", DriveClient.unreached(new ConnectException("Connection refused")));
        ConnectException unresolved = new ConnectException();
        unresolved.initCause(new UnresolvedAddressException());
        assertEquals("cannot be found (its name does not resolve)", DriveClient.unreached(unresolved));
        assertEquals("did not answer in time", DriveClient.unreached(new HttpTimeoutException("request timed out")));
        assertEquals("could not be reached (IllegalStateException)", DriveClient.unreached(new IllegalStateException("x")));
    }
}
