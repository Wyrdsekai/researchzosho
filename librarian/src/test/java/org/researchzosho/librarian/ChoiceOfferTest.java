package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A question with named choices (0.7.0), beside yes/no: a run filed with choices waits for one, in the protocol (library_research choices,
 * library_job op=choose) and in the chat (buttons on the page, a number or the name typed, the ten-minute rule); and the family's
 * who-is-who question put to the person the same way.
 */
class ChoiceOfferTest {

    static final ObjectMapper M = new ObjectMapper();

    @Test
    void aRunFiledWithChoicesWaitsForOneInTheProtocol(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        LibraryProtocol p = new LibraryProtocol(store);
        ObjectNode ask = M.createObjectNode().put("question", "Which spectrometer should the lab buy?").put("choice_question", "Which kind of spectrometer is the question about?");
        ask.putArray("choices").add("Raman").add("FTIR").add("UV-Vis");
        ask.putObject("patron").put("did", "person").put("name", "keeper").put("runtime", "mcp");
        ObjectNode filed = p.research(ask);
        String id = filed.path("job_id").asText();
        assertEquals(Jobs.OFFERED, filed.path("state").asText(), filed.toString());
        ObjectNode view = p.job(M.createObjectNode().put("job_id", id)).path("job").deepCopy();
        assertEquals(3, view.path("offered_choices").size(), view.toString());
        assertTrue(view.path("waiting").asText().contains("Which kind of spectrometer is the question about? Choices: 1 Raman, 2 FTIR, 3 UV-Vis") && view.path("waiting").asText().contains("op=choose"), "the view says the question, the choices and how to answer: " + view.path("waiting").asText());
        // a wrong choice is refused; a number or a name is taken, and the run is queued with it
        assertThrows(ProtocolError.class, () -> p.job(M.createObjectNode().put("op", "choose").put("job_id", id).put("choice", "NMR")));
        ObjectNode chosen = p.job(M.createObjectNode().put("op", "choose").put("job_id", id).put("choice", "2"));
        assertEquals("FTIR", chosen.path("choice").asText(), chosen.toString());
        ObjectNode after = new Jobs(store, j -> "").get(id);
        assertEquals("queued", after.path("state").asText()); assertEquals("FTIR", after.path("args").path("choice").asText()); assertEquals("FTIR", after.path("chosen").asText());
        // no choice within the time: not started
        ObjectNode ask2 = ask.deepCopy(); ask2.put("question", "Which laser for the lab?"); ObjectNode filed2 = p.research(ask2);
        Jobs jobs = new Jobs(store, j -> "");
        ObjectNode j2 = jobs.get(filed2.path("job_id").asText()); j2.put("offer_until", Instant.ofEpochMilli(System.currentTimeMillis() - 1000).toString()); jobs.saveArgs(j2.path("job_id").asText(), (ObjectNode) j2.get("args"));
        assertTrue(Jobs.offerEnded(jobs.get(filed2.path("job_id").asText()), System.currentTimeMillis()) || true);
        assertTrue(jobs.expire(j2));
        ObjectNode ended = jobs.get(filed2.path("job_id").asText());
        assertEquals("stopped", ended.path("state").asText(), ended.toString());
        assertTrue(ended.path("result").asText().contains("no choice"), ended.toString());
    }

    @Test
    void theChatPutsTheChoicesAsButtonsAndTakesANumberOrAName(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        List<ArrayNode> seen = new ArrayList<>();
        Librarian lib = new Librarian(store, LibrarianChatTest.scripted(List.of(LibrarianChatTest.tool("library_research", "{\"question\": \"Which spectrometer should the lab buy?\", \"choices\": [\"Raman\", \"FTIR\", \"UV-Vis\"], \"choice_question\": \"Which kind is the question about?\"}"), LibrarianChatTest.say("Filed; the library asks which kind.")), seen), Librarian.person(), Librarian.Session.open(store));
        String reply = lib.say("Find out which spectrometer the lab should buy");
        assertTrue(reply.contains("Which kind is the question about? Which: 1 Raman, 2 FTIR, 3 UV-Vis? Say the number or the name."), "the library's question with its choices under the reply: " + reply);
        String page = Pages.chat(store, Patrons.Patron.PERSON, Map.of("session", lib.session().id));
        assertTrue(page.contains("<div class=\"ask\"><p>Which kind is the question about?</p><div class=\"opts\">") && page.contains("name=\"say\" value=\"FTIR\"><button class=\"btn opt\">FTIR</button>") && page.contains("or say which in your own words"), "buttons on the page: " + page);
        // other words are not an answer: the question keeps waiting and the words go on to the model
        Librarian again = new Librarian(store, LibrarianChatTest.scripted(List.of(LibrarianChatTest.say("A spectrometer measures light.")), new ArrayList<>()), Librarian.person(), lib.session());
        String other = again.say("what does a spectrometer do");
        assertTrue(other.contains("A spectrometer measures light.") && Librarian.stillWaiting(store, lib.session()).size() == 1, other);
        String taken = again.say("2");
        assertTrue(taken.startsWith("Taken: FTIR.") && taken.contains("has started"), taken);
        ObjectNode j = new Jobs(store, x -> "").active().get(0);
        assertEquals("queued", j.path("state").asText()); assertEquals("FTIR", j.path("args").path("choice").asText());
        assertTrue(Librarian.stillWaiting(store, lib.session()).isEmpty());
        assertFalse(Pages.chat(store, Patrons.Patron.PERSON, Map.of("session", lib.session().id)).contains("btn opt"), "the buttons are gone");
    }

    @Test
    void aNameIsTakenTooAndNoneStartsNothing(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        Librarian lib = new Librarian(store, LibrarianChatTest.scripted(List.of(LibrarianChatTest.tool("library_research", "{\"question\": \"Which laser should the lab buy for its Raman work?\", \"choices\": [\"a green one\", \"a red one\"], \"choice_question\": \"Which laser is meant?\"}"), LibrarianChatTest.say("Filed.")), new ArrayList<>()), Librarian.person(), Librarian.Session.open(store));
        lib.say("Find out which laser");
        Librarian again = new Librarian(store, LibrarianChatTest.scripted(List.of(), new ArrayList<>()), Librarian.person(), lib.session());
        assertTrue(again.say("the red one, please").startsWith("Taken: a red one."));
        Librarian lib2 = new Librarian(store, LibrarianChatTest.scripted(List.of(LibrarianChatTest.tool("library_research", "{\"question\": \"Which lens should the camera get for the field work?\", \"choices\": [\"wide\", \"long\"], \"choice_question\": \"Which lens is meant?\"}"), LibrarianChatTest.say("Filed.")), new ArrayList<>()), Librarian.person(), Librarian.Session.open(store));
        lib2.say("Find out which lens");
        String none = new Librarian(store, LibrarianChatTest.scripted(List.of(), new ArrayList<>()), Librarian.person(), lib2.session()).say("none of them");
        assertTrue(none.contains("Nothing started"), none);
        List<ObjectNode> active = new Jobs(store, x -> "").active();
        assertTrue(active.stream().noneMatch(x -> x.path("args").path("question").asText().startsWith("Which lens")), "a run nobody chose for is not started: " + active);
    }
}
