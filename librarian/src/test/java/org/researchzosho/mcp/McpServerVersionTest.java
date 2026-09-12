package org.researchzosho.mcp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** The MCP server introduces itself as the release it is; it said 0.1.2 through 0.1.7. */
class McpServerVersionTest {
    @Test
    void serverVersionIsTheReleaseVersion() {
        assertEquals(org.researchzosho.Version.string(), McpServer.SERVER_VERSION);
        assertNotEquals("0.1.2", McpServer.SERVER_VERSION);
    }
}
