package org.researchzosho.librarian;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;
import org.researchzosho.mcp.StandInMcp;
import org.researchzosho.tools.SearchTally;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The Keep page's section of other MCP servers: the keeper's alone, its forms carried out only from the library's own page. */
class McpPageTest {
    @TempDir Path tmp;
    String realHome;

    @BeforeEach void scratch() { realHome = System.getProperty("user.home"); System.setProperty("user.home", tmp.resolve("home").toString()); Config.invalidate(); SearchTally.override = tmp.resolve("tally.json"); }
    @AfterEach void back() { McpConnections.closeAll(); System.setProperty("user.home", realHome); Config.invalidate(); SearchTally.override = null; }

    static HttpResponse<String> post(HttpClient c, String url, String origin, String form) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form));
        if (origin != null) b.header("Origin", origin);
        return c.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }

    @Test
    void theKeeperAddsTestsAndRemovesAServerOnTheKeepPage() throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        LibrarianDaemon d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1);
        try {
            HttpClient c = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
            String base = "http://127.0.0.1:" + d.port();
            String keep = KeepPageTest.get(c, base + "/keep", null).body();
            assertTrue(keep.contains("<details class=\"sec\" id=\"mcp\"><summary><h2>Other MCP servers</h2><span class=\"gist\">none yet</span>"), keep);
            assertTrue(keep.contains("name=\"op\" value=\"mcp-preset\"") && keep.contains(">Add Exa</button>") && keep.contains(">Add Tavily</button>") && keep.contains("name=\"op\" value=\"mcp-add\""), keep);

            List<String> words = new ArrayList<>();
            for (String w : StandInMcp.command()) words.add("\"" + w + "\"");
            String form = "op=mcp-add&name=notes&command=" + enc(String.join(" ", words));
            HttpResponse<String> foreign = post(c, base + "/keep", "https://elsewhere.example", form);
            assertEquals(403, foreign.statusCode(), "a form posted from another site is not carried out");
            assertTrue(foreign.body().contains("This form came from another site, so it was not carried out."), foreign.body());
            assertNull(McpServers.named(store, "notes"), "and nothing was set up");

            HttpResponse<String> added = post(c, base + "/keep", base, form);
            assertEquals(303, added.statusCode(), added.body());
            String to = added.headers().firstValue("Location").orElse("");
            assertTrue(to.startsWith("/keep?note=") && to.endsWith("#mcp"), to);
            String note = URLDecoder.decode(to.substring("/keep?note=".length(), to.length() - 4), StandardCharsets.UTF_8);
            assertTrue(note.startsWith("The MCP server notes is added. It is the program "), note);
            keep = KeepPageTest.get(c, base + "/keep", null).body();
            assertTrue(keep.contains("<span class=\"gist\">1 server, 5 tools research may call</span>"), keep);
            assertTrue(keep.contains("Research may call 5 of its 8: search_notes, get_note, slow_lookup, broken_lookup, get_greeting.") && keep.contains("Kept off, because research reads and does not act: create_note (its name says it changes things (create))"), keep);

            HttpResponse<String> tested = post(c, base + "/keep", base, "op=mcp-test&name=notes");
            assertTrue(tested.headers().firstValue("Location").orElse("").contains(enc("The MCP server notes answers (MCP 2025-06-18). It has 8 tools.")), tested.headers().map().toString());
            post(c, base + "/keep", base, "op=mcp-readers&name=notes&value=on");
            assertTrue(McpServers.named(store, "notes").readers);
            post(c, base + "/keep", base, "op=mcp-remove&name=notes");
            assertNull(McpServers.named(store, "notes"));

            // a writer has Keep, without this section; a reader has neither
            Patrons.set(store, "did:key:zW", "a writer", Patrons.Level.write);
            String writer = KeepPageTest.get(c, base + "/keep", Pages.COOKIE + "=" + Patrons.issueToken(store, "did:key:zW")).body();
            assertTrue(writer.contains("<details class=\"sec\" id=\"settings\">") && !writer.contains("id=\"mcp\""), writer);
            HttpResponse<String> refused = c.send(HttpRequest.newBuilder(URI.create(base + "/keep")).header("Cookie", Pages.COOKIE + "=" + Patrons.issueToken(store, "did:key:zW"))
                    .header("Content-Type", "application/x-www-form-urlencoded").header("Origin", base).POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(403, refused.statusCode(), "a writer cannot start a program on the library's computer");
            assertNull(McpServers.named(store, "notes"));
        } finally { d.stop(); }
    }
}
