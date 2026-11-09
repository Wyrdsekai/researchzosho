package org.researchzosho.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The MCP client against the stand-in server: the handshake, the tools page by page, a call, an error, a timeout, over stdio and over Streamable HTTP. Nothing reaches the network. */
class McpClientTest {
    static final ObjectMapper M = new ObjectMapper();
    static final Duration T = Duration.ofSeconds(20);

    @Test
    void overStdioTheHandshakeListsEveryPageCallsAndKeepsStderrInTheLog(@TempDir Path tmp) throws Exception {
        Path log = tmp.resolve("logs").resolve("notes.log");
        try (McpClient c = McpClient.stdio(StandInMcp.command(), Map.of("NOTES_GREETING", "hello"), log)) {
            c.initialize(T);
            assertEquals("2025-06-18", c.protocol(), "the client offered its newest version, the server answered one it speaks, and the client took it");
            assertEquals("notes-stand-in", c.serverName());
            List<McpClient.Tool> tools = c.tools(T);
            assertEquals(List.of("search_notes", "get_note", "create_note", "notes_digest", "slow_lookup", "broken_lookup", "get_greeting", "bad name!"), tools.stream().map(McpClient.Tool::name).toList(), "three pages followed by their cursor");
            assertEquals("query", tools.get(0).inputSchema().path("required").get(0).asText());

            McpClient.Result r = c.call("search_notes", M.readTree("{\"query\": \"canvas tote\"}"), T);
            assertFalse(r.error());
            assertTrue(r.text().contains("Canvas tote bags are sewn from 12 oz duck canvas"), r.text());
            assertTrue(r.links().containsAll(List.of("https://notes.example.org/handles", "https://notes.example.org/tote-canvas")), "the result's own addresses: " + r.links());

            McpClient.Result broken = c.call("broken_lookup", M.readTree("{}"), T);
            assertTrue(broken.error() && broken.text().equals("The other index is offline."), "a tool that answered that it failed is a result marked as an error");

            McpClient.Failure unknown = assertThrows(McpClient.Failure.class, () -> c.call("no_such_tool", M.readTree("{}"), T));
            assertEquals(McpClient.Failure.Kind.ERROR, unknown.kind);
            assertEquals(-32602, unknown.code);
            assertEquals("Unknown tool: no_such_tool", unknown.said, "the server's own words, apart from the sentence");
            assertTrue(unknown.getMessage().startsWith("The server answered the call with an error (code -32602)"), unknown.getMessage());

            McpClient.Result env = c.call("get_greeting", M.readTree("{}"), T);
            assertEquals("greeting=hello library-setting=none", env.text(), "the environment named is given, the library's own settings are not");

            McpClient.Failure slow = assertThrows(McpClient.Failure.class, () -> c.call("slow_lookup", M.readTree("{\"word\": \"x\"}"), Duration.ofSeconds(1)));
            assertEquals(McpClient.Failure.Kind.TIMEOUT, slow.kind);
            assertEquals("The server gave no answer within 1 second.", slow.getMessage());
        }
        String kept = Files.readString(log);
        assertTrue(kept.contains(StandInMcp.STDERR_LINE) && kept.contains("started: "), "what the program said on stderr is in the log: " + kept);
    }

    @Test
    void aStopEndsTheWaitAtOnceOverStdioAndOverHttp(@TempDir Path tmp) throws Exception {
        try (McpClient c = McpClient.stdio(StandInMcp.command(), Map.of(), tmp.resolve("x.log"))) {
            c.initialize(T);
            long t0 = System.currentTimeMillis();
            McpClient.Failure f = assertThrows(McpClient.Failure.class, () -> c.call("slow_lookup", M.readTree("{\"word\": \"x\"}"), Duration.ofSeconds(30), () -> System.currentTimeMillis() - t0 > 300));
            assertEquals(McpClient.Failure.Kind.STOPPED, f.kind);
            assertEquals("The call was stopped before the server answered.", f.getMessage());
            assertTrue(System.currentTimeMillis() - t0 < 2500, "a person who stops a run is not kept waiting on the other program: " + (System.currentTimeMillis() - t0) + " ms");
        }
        try (StandInHttp s = new StandInHttp(null, null, null); McpClient c = McpClient.http(s.url(), Map.of())) {
            c.initialize(T);
            long t0 = System.currentTimeMillis();
            McpClient.Failure f = assertThrows(McpClient.Failure.class, () -> c.call("slow_lookup", M.readTree("{\"word\": \"x\"}"), Duration.ofSeconds(30), () -> System.currentTimeMillis() - t0 > 300));
            assertEquals(McpClient.Failure.Kind.STOPPED, f.kind);
            assertTrue(System.currentTimeMillis() - t0 < 2500, (System.currentTimeMillis() - t0) + " ms");
        }
    }

    @Test
    void overStdioACommandThatCannotStartIsSaidInASentence(@TempDir Path tmp) {
        McpClient.Failure f = assertThrows(McpClient.Failure.class, () -> McpClient.stdio(List.of(tmp.resolve("no-such-program").toString()), Map.of(), tmp.resolve("x.log")));
        assertEquals(McpClient.Failure.Kind.UNREACHABLE, f.kind);
        assertTrue(f.getMessage().startsWith("The command " + tmp.resolve("no-such-program") + " could not be started"), f.getMessage());
    }

    @Test
    void overHttpTheSessionAndTheVersionRideOnEveryRequestAndACallComesAsAStream() throws Exception {
        try (StandInHttp s = new StandInHttp("k-123", "Authorization", null)) {
            try (McpClient c = McpClient.http(s.url(), Map.of("Authorization", "Bearer k-123"))) {
                c.initialize(T);
                assertEquals("2025-06-18", c.protocol());
                assertEquals(8, c.tools(T).size());
                McpClient.Result r = c.call("search_notes", M.readTree("{\"query\": \"canvas\"}"), T);
                assertTrue(r.text().contains("Canvas tote bags") && !r.error(), "the answer read off the event stream, after a progress note and a ping: " + r.text());
            }
            for (StandInHttp.Seen seen : s.seen) {
                if (seen.rpcMethod().equals("initialize")) { assertNull(seen.session()); continue; }
                assertEquals("session-1", seen.session(), "every request after the handshake names the session: " + seen);
                assertEquals("2025-06-18", seen.version(), "and the version agreed: " + seen);
                assertEquals("Bearer k-123", seen.auth());
            }
            assertEquals(1, s.of("notifications/initialized").size());
            Thread.sleep(300);
            assertEquals(1, s.of("(answer)").size(), "the ping the server sent in the stream was answered: " + s.counts());
            assertTrue(s.seen.stream().anyMatch(x -> x.httpMethod().equals("DELETE")), "the session is ended when the client closes: " + s.counts());
        }
    }

    @Test
    void overHttpAMissingKeyAWrongVersionAndAForgottenSessionAreEachHandled() throws Exception {
        try (StandInHttp s = new StandInHttp("k-123", "x-api-key", null)) {
            try (McpClient c = McpClient.http(s.url(), Map.of())) {
                McpClient.Failure f = assertThrows(McpClient.Failure.class, () -> c.initialize(T));
                assertEquals(McpClient.Failure.Kind.REFUSED, f.kind);
                assertEquals("The server refused the call (HTTP 401): it may want a key, and none is set.", f.getMessage());
            }
            try (McpClient c = McpClient.http(s.url(), Map.of("x-api-key", "k-123"))) {
                c.initialize(T);
                s.forgetAfter = 0;
                McpClient.Result r = c.call("get_note", M.readTree("{\"id\": \"n-7\"}"), T);
                assertEquals("The note n-7 in full.", r.text(), "a server that forgot the session is greeted again and the call made once more");
                assertEquals(2, s.of("initialize").stream().filter(x -> "k-123".equals(x.apiKey())).count(), "greeted twice with the key: " + s.counts());
            }
        }
        try (StandInHttp old = new StandInHttp(null, null, "1999-01-01"); McpClient c = McpClient.http(old.url(), Map.of())) {
            McpClient.Failure f = assertThrows(McpClient.Failure.class, () -> c.initialize(T));
            assertEquals(McpClient.Failure.Kind.PROTOCOL, f.kind);
            assertTrue(f.getMessage().startsWith("The server speaks MCP version 1999-01-01, and this library speaks 2025-11-25, 2025-06-18"), f.getMessage());
        }
    }

    @Test
    void overHttpATimeoutIsSaidAndNothingAnswersIsSaid() throws Exception {
        try (StandInHttp s = new StandInHttp(null, null, null); McpClient c = McpClient.http(s.url(), Map.of())) {
            c.initialize(T);
            McpClient.Failure f = assertThrows(McpClient.Failure.class, () -> c.call("slow_lookup", M.readTree("{\"word\": \"x\"}"), Duration.ofSeconds(1)));
            assertEquals(McpClient.Failure.Kind.TIMEOUT, f.kind);
        }
        try (McpClient c = McpClient.http("http://127.0.0.1:1/mcp", Map.of())) {
            McpClient.Failure f = assertThrows(McpClient.Failure.class, () -> c.initialize(Duration.ofSeconds(5)));
            assertEquals(McpClient.Failure.Kind.UNREACHABLE, f.kind);
            assertEquals("Nothing answers at 127.0.0.1:1.", f.getMessage());
        }
    }
}
