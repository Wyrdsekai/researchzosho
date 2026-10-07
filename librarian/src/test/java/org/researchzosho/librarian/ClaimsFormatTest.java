package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** `library_get` with format=claims: a report's claims as records, each with what the checks made of it (0.5.4). */
class ClaimsFormatTest {

    static final ObjectMapper M = new ObjectMapper();

    static Finding f(String id, String title, String body, List<Finding.Source> sources) {
        return new Finding(id, title, List.of("gears--cutting"), Finding.State.draft, Finding.ClaimType.extraction, Finding.Confidence.medium,
                "patron:prog", Instant.now().toString(), "2026-10-07", Finding.Volatility.stable, "", sources, List.of(), null, body);
    }

    @Test
    void aReportsClaimsComeBackAsRecordsWithTheirCheck(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp); store.init();
        List<Finding.Source> src = List.of(new Finding.Source("https://museum.example/gears", "n/a", "the museum's page"));
        store.write(f("F-0001-gears-cut-by-hand", "Gears cut by hand", "The gears were cut by hand with files [1].\n", src));
        store.write(f("F-0002-gears-cut-in-1901", "Gears cut in 1901", "The gears were cut in 1901 [1]. [not supported by the cited source on check]\n", src));
        store.write(f("F-0003-gears-weigh", "The gears weigh", "The gears weigh 12 kg. [number not in any note or source read this run: 12 kg]\n", src));
        store.write(f("F-0004-gears-bronze", "The gears are bronze", "The gears are bronze.\n", List.of()));
        store.write(new Investigation("I-0001-gears", "Gears", Finding.State.accepted, "patron:prog", Instant.now().toString(),
                List.of("F-0001-gears-cut-by-hand", "F-0002-gears-cut-in-1901", "F-0003-gears-weigh", "F-0004-gears-bronze", "F-0099-missing"), List.of(),
                "## Question\n\nHow?\n\n## Answer\n\nBy hand [1].\n"));
        LibraryProtocol p = new LibraryProtocol(store);
        ObjectNode r = p.get((ObjectNode) M.readTree("{\"id\":\"I-0001-gears\",\"format\":\"claims\",\"patron\":{\"did\":\"did:key:prog-1\",\"name\":\"a program\",\"runtime\":\"x\"}}"));
        assertEquals(4, r.path("count").asInt(), r.toString());
        assertEquals("Gears", r.path("title").asText());
        JsonNode c = r.path("claims");
        assertEquals("cited", c.get(0).path("check").asText());
        assertEquals("not supported", c.get(1).path("check").asText());
        assertEquals("number unbacked", c.get(2).path("check").asText());
        assertEquals("unchecked", c.get(3).path("check").asText());
        assertEquals("https://museum.example/gears", c.get(0).path("sources").get(0).path("locator").asText());
        assertEquals("medium", c.get(0).path("confidence").asText());
        assertEquals("draft", c.get(0).path("state").asText());
        assertTrue(c.get(0).path("text").asText().startsWith("The gears were cut by hand"));
        ProtocolError e = assertThrows(ProtocolError.class, () -> p.get((ObjectNode) M.readTree("{\"id\":\"F-0001-gears-cut-by-hand\",\"format\":\"claims\",\"patron\":{\"did\":\"did:key:prog-1\",\"name\":\"a\",\"runtime\":\"x\"}}")));
        assertEquals("invalid_args", e.code);
    }
}
