package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** GET /healthz: a proxy's or a platform's check, answered with no sign-in whatever the pages require. */
class HealthzTest {
    private static final ObjectMapper M = new ObjectMapper();

    @Test
    void healthzAnswersWithTheVersionAndTheLibraryWithoutSignIn(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp); store.init();
        LibrarianDaemon d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1);
        WebAccess.OVERRIDE = Boolean.TRUE;   // the pages ask for a sign-in: the check does not
        try {
            HttpClient c = HttpClient.newHttpClient();
            HttpResponse<String> r = c.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + d.port() + "/healthz")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, r.statusCode(), r.body());
            JsonNode j = M.readTree(r.body());
            assertTrue(j.path("ok").asBoolean(false), r.body());
            assertFalse(j.path("version").asText("").isBlank(), r.body());
            assertEquals(store.identity().id(), j.path("library_id").asText(), r.body());
            assertEquals(store.identity().name(), j.path("library_name").asText(), r.body());
            HttpResponse<String> p = c.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + d.port() + "/healthz")).POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(405, p.statusCode(), "only GET");
        } finally { WebAccess.OVERRIDE = null; d.stop(); }
    }
}
