package org.researchzosho.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** A page that is gone, does not answer, or answers with a wall: web_fetch reads the archive's copy and says so (0.5.5). */
class ArchivedCopyFallbackTest {

    static final ObjectMapper J = new ObjectMapper();
    HttpServer server; String base;

    @BeforeEach void serve() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/", x -> {
            String path = x.getRequestURI().getPath();
            byte[] body; int status = 200; String type = "text/html";
            if (path.equals("/gone.html")) { body = "<html><body>not found</body></html>".getBytes(StandardCharsets.UTF_8); status = 404; }
            else if (path.equals("/walled.html")) { body = "<html><head><title>Access Denied</title></head><body>Making sure you're not a bot!</body></html>".getBytes(StandardCharsets.UTF_8); status = 403; }
            else if (path.equals("/alive.html")) body = "<html><head><title>Alive</title></head><body>The live page, still here, with its own text about gears.</body></html>".getBytes(StandardCharsets.UTF_8);
            else if (path.startsWith("/web/99999999id_/")) { x.getResponseHeaders().add("Location", base + "/web/20100101000000id_/" + path.substring("/web/99999999id_/".length())); x.sendResponseHeaders(302, -1); x.close(); return; }
            else if (path.startsWith("/web/20100101000000id_/")) body = "<html><head><title>The old page</title></head><body>The old page text, as it was in 2010: the gears were cut by hand with files on a dividing plate.</body></html>".getBytes(StandardCharsets.UTF_8);
            else if (path.startsWith("/timegate/")) { body = "no".getBytes(StandardCharsets.UTF_8); status = 404; }
            else { body = "no".getBytes(StandardCharsets.UTF_8); status = 404; }
            x.getResponseHeaders().add("Content-Type", type);
            x.sendResponseHeaders(status, body.length); x.getResponseBody().write(body); x.close();
        });
        server.start();
        Archives.WAYBACK = base; Archives.ARCHIVE_TODAY = base;
        Fetch.allowLoopback = true;
    }

    @AfterEach void stop() { server.stop(0); Archives.WAYBACK = "https://web.archive.org"; Archives.ARCHIVE_TODAY = "https://archive.ph"; Fetch.allowLoopback = false; }

    static ObjectNode args(String json) throws Exception { return (ObjectNode) J.readTree(json); }

    @Test
    void aGonePageIsReadFromTheArchiveAndSaidSo() throws Exception {
        String out = new WebFetchTool().execute(args("{\"url\":\"" + base + "/gone.html\"}"));
        assertTrue(out.startsWith("archived copy of " + base + "/gone.html, saved on 2010-01-01 by the Wayback Machine — the live page answered HTTP 404"), out);
        assertTrue(out.contains("source: " + base + "/web/20100101000000id_/" + base + "/gone.html"), "the copy's address is what is cited: " + out);
        assertTrue(out.contains("cut by hand with files"), out);
    }

    @Test
    void aWalledPageIsReadFromTheArchiveInsteadOfBecomingARequest() throws Exception {
        int before = WebFetchTool.WALLS.size();
        String out = new WebFetchTool().execute(args("{\"url\":\"" + base + "/walled.html\"}"));
        assertTrue(out.startsWith("archived copy of ") && out.contains("the live page answered"), out);
        assertTrue(out.contains("as it was in 2010"), out);
        assertEquals(before, WebFetchTool.WALLS.size(), "no source request when the copy answered");
    }

    @Test
    void aLivePageIsNeverReplacedByACopy() throws Exception {
        String out = new WebFetchTool().execute(args("{\"url\":\"" + base + "/alive.html\"}"));
        assertFalse(out.startsWith("archived copy"), out);
        assertTrue(out.contains("still here"), out);
    }

    @Test
    void aPageTheArchivesLackStaysAnError() throws Exception {
        Archives.WAYBACK = base + "/nowhere"; Archives.ARCHIVE_TODAY = base + "/nowhere";
        String out = new WebFetchTool().execute(args("{\"url\":\"" + base + "/gone.html\"}"));
        assertTrue(out.startsWith("ERROR: HTTP 404"), out);
    }
}
