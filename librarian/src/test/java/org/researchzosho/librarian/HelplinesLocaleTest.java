package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.drive.ContentJudge;
import org.researchzosho.drive.Judge;
import org.researchzosho.mcp.McpServer;

import java.nio.file.Path;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Where to find help, in the person's language and with their country first: a program sends the person's locale with the question
 * (Wyrdsekai runs companions in English, Spanish and Japanese), and gets the help as text and as data it can show its own way.
 */
class HelplinesLocaleTest {

    static final ObjectMapper M = new ObjectMapper();
    static final String SURE = "A placeholder question from a person, marked MARKER-HARM, about their own plans?";

    @TempDir Path tmp;

    @BeforeEach void judge() {
        ContentJudge.use(new ContentJudge(new Judge("m", body -> {
            String c = body.path("messages").path(0).path("content").asText();
            return DeclinesTest.judged(c.contains(ContentOffer.HARM) && c.contains("MARKER-HARM") ? 0.97 : 0.02);
        }), null));
    }

    @AfterEach void back() { ContentJudge.use(null); }

    @Test
    void theTextComesInThePersonsLanguageWithTheirCountryFirst() {
        String ar = CrisisHelp.text(Locale.forLanguageTag("es-AR"));
        assertTrue(ar.startsWith(CrisisHelp.OPENING.get("es") + "\n- En Argentina: llama al 135, gratis desde la Ciudad y el Gran Buenos Aires;"), ar);
        assertTrue(ar.contains("\n- En cualquier lugar del mundo: findahelpline.com") && ar.endsWith(CrisisHelp.EMERGENCY.get("es")), ar);
        String es = CrisisHelp.text(Locale.forLanguageTag("es-ES"));
        assertTrue(es.startsWith(CrisisHelp.OPENING.get("es") + "\n- En España: llama al 024, gratis y confidencial, a cualquier hora."), es);
        String jp = CrisisHelp.text(Locale.forLanguageTag("ja-JP"));
        assertTrue(jp.startsWith(CrisisHelp.OPENING.get("ja") + "\n- 日本：よりそいホットライン 0120-279-338"), jp);
        // Spanish with no country the help has: the directory first; a language the help does not have: English
        String mx = CrisisHelp.text(Locale.forLanguageTag("es-MX"));
        assertTrue(mx.startsWith(CrisisHelp.OPENING.get("es") + "\n- En cualquier lugar del mundo"), mx);
        assertTrue(mx.contains("En Estados Unidos te atienden en español."), mx);
        assertTrue(CrisisHelp.text(Locale.forLanguageTag("fr-FR")).startsWith(CrisisHelp.OPENING.get("en")));
        // English is as it was
        assertTrue(CrisisHelp.text(Locale.US).startsWith(CrisisHelp.OPENING.get("en") + "\n- In the United States and Canada: call or text 988, free, at any hour.\n- Anywhere in the world"));
    }

    @Test
    void theDataNamesEachServiceInTheOrderShown() {
        ObjectNode d = CrisisHelp.data(Locale.forLanguageTag("es-ES"));
        assertEquals("es", d.path("language").asText());
        JsonNode first = d.path("helplines").path(0), second = d.path("helplines").path(1);
        assertEquals("ES", first.path("countries").path(0).asText());
        assertEquals("call 024", first.path("contact").asText());
        assertTrue(first.path("free").asBoolean() && first.path("url").asText().startsWith("https://www.sanidad.gob.es"));
        assertEquals("En España: llama al 024, gratis y confidencial, a cualquier hora.", first.path("text").asText());
        assertEquals("*", second.path("countries").path(0).asText(), "the worldwide directory second");
        assertEquals(CrisisHelp.LINES.size() + 1, d.path("helplines").size());
        for (JsonNode h : d.path("helplines")) for (String f : new String[]{"name", "contact", "url", "text"}) assertFalse(h.path(f).asText().isBlank(), f + " in " + h);
    }

    @Test
    void theConfirmErrorCarriesTheHelpInThePersonsLanguageOverTheProtocolAndMcp() throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        ObjectNode a = M.createObjectNode().put("question", SURE).put("locale", "ja-JP");
        a.putObject("patron").put("did", "person").put("name", "keeper").put("runtime", "cli");
        ProtocolError e = assertThrows(ProtocolError.class, () -> new LibraryProtocol(store).research(a));
        assertEquals("confirm", e.code);
        assertTrue(e.getMessage().startsWith(CrisisHelp.OPENING.get("ja")), e.getMessage());
        assertTrue(e.getMessage().endsWith(ContentOffer.CONFIRM), "the sentence for the program stays as it was");
        assertEquals("ja", e.extra.path("language").asText());
        assertEquals("JP", e.extra.path("helplines").path(0).path("countries").path(0).asText());
        // over MCP, from a program the library knows with write access: error.data has the code and the helplines beside it
        Patrons.set(store, "did:key:companion", "Companion", Patrons.Level.write);
        ObjectNode viaMcp = a.deepCopy();
        viaMcp.putObject("patron").put("did", "did:key:companion").put("name", "Companion").put("runtime", "wyrdsekai");
        ObjectNode req = M.createObjectNode().put("jsonrpc", "2.0").put("id", 7).put("method", "tools/call");
        ObjectNode params = req.putObject("params").put("name", "library_research");
        params.set("arguments", viaMcp);
        ObjectNode env = McpServer.envelopeFor(req, store);
        JsonNode err = env.path("error");
        assertEquals(-32007, err.path("code").asInt(), env.toString());
        assertEquals("confirm", err.path("data").path("code").asText());
        assertEquals("ja", err.path("data").path("language").asText());
        assertEquals("JP", err.path("data").path("helplines").path(0).path("countries").path(0).asText(), env.toString());
        // no locale: the machine's own, and the data still comes
        ObjectNode plain = a.deepCopy(); plain.remove("locale");
        ProtocolError e2 = assertThrows(ProtocolError.class, () -> new LibraryProtocol(store).research(plain));
        assertTrue(e2.extra.path("helplines").isArray() && e2.extra.path("helplines").size() > 1);
    }
}
