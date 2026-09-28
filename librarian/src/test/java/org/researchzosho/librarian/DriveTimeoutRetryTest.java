package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;
import org.researchzosho.HangingServer;
import org.researchzosho.drive.DriveClient;

import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A call to the model that ran out of its time limit is not asked again with the same limit: it would run out again, and the run would
 * wait twice as long for nothing. A request that never got through (a connection refused or reset, a connection that could not be made in
 * time) is asked once more, as before.
 */
class DriveTimeoutRetryTest {

    @Test
    void aCallThatRanOutOfTimeIsNotAskedAgainAndOneThatNeverGotThroughIs() throws Exception {
        assertFalse(Researcher.askAgain(new RuntimeException("chat() failed against http://x", new HttpTimeoutException("the model server at http://x gave no answer within 300 seconds"))));
        assertTrue(Researcher.askAgain(new RuntimeException("chat() failed against http://x", new ConnectException("Connection refused"))));
        assertTrue(Researcher.askAgain(new RuntimeException("chat() failed against http://x", new HttpConnectTimeoutException("HTTP connect timed out"))));
        assertTrue(Researcher.askAgain(new RuntimeException("chat() failed against http://x", new IOException("connection reset"))));
        // a server that sends the head of its answer and then nothing: the call ends at its limit, as one that ran out of time
        try (HangingServer s = new HangingServer(HangingServer.Mode.HEAD_ONLY)) {
            DriveClient c = new DriveClient(s.url(), "placeholder-model").timeLimit(Duration.ofSeconds(2));
            ArrayNode task = new ObjectMapper().createArrayNode();
            task.addObject().put("role", "user").put("content", "Summarise the placeholder topic.");
            RuntimeException e = assertTimeoutPreemptively(Duration.ofSeconds(20), () -> assertThrows(RuntimeException.class, () -> c.chat(task, null, 64, "auto")));
            assertFalse(Researcher.askAgain(e), "not asked again: " + e + " / " + e.getCause());
        }
    }
}
