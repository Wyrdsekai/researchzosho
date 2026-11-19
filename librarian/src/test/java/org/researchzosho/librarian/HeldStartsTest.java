package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Everything the chat's own model starts or writes waits for the person (the owner, 2026-11-18: "the yes - we need a confirmation. else
 * there may be more info that the user provides"; "this is for chats only"). The probe saw a small model, asked only what a page says,
 * file a research run, a note and the page's address as a conversation. Now each waits: a yes does it as stated, a no does nothing, and
 * anything else the person writes reaches the model with what it proposed, which may propose it again, changed, and is asked again.
 */
class HeldStartsTest {
    static final ObjectMapper M = new ObjectMapper();
    @TempDir Path tmp;

    LibraryStore library() throws Exception { LibraryStore s = new LibraryStore(tmp.resolve("lib")); s.init(); return s; }
    static List<JsonNode> runs(LibraryStore store) throws Exception { return new ArrayList<>(new Jobs(store, j -> { throw new IllegalStateException("read only"); }).active()); }
    static JsonNode a(String json) throws Exception { return M.readTree(json); }

    @Test
    void whatTheModelStartsOrWritesWaitsAndWhatOnlyReadsDoesNot() throws Exception {
        for (String[] held : new String[][]{
                {"library_research", "{\"question\":\"Why did the ferry close?\"}"}, {"library_translate", "{\"raw\":\"x.md\",\"to\":\"ja\"}"},
                {"library_tell", "{\"subject\":\"the ferry\",\"text\":\"My grandmother crossed in 1952.\"}"}, {"library_submit", "{\"claim\":\"The ferry closed in 1968.\"}"},
                {"library_items", "{\"text\":\"a\\nb\"}"}, {"library_check", "{\"text\":\"my draft\"}"}, {"library_questions", "{\"text\":\"why?\"}"},
                {"library_absorb", "{\"url\":\"https://example.net/conversation\"}"}, {"library_meeting", "{\"text\":\"minutes\"}"},
                {"library_reading", "{\"text\":\"10.1000/x\"}"}, {"library_bookmarks", "{\"path\":\"/home/x/bookmarks.html\"}"},
                {"library_survey", "{\"url\":\"https://example.net/repo\"}"}, {"library_add", "{\"url\":\"https://example.net/page\"}"},
                {"library_images", "{\"op\":\"find\",\"words\":\"ferry\",\"keep\":true}"}, {"library_images", "{\"op\":\"describe\",\"images\":[\"/home/x/photo.jpg\"]}"},
                {"library_inbox", "{\"op\":\"accept\",\"ids\":[\"D-0001\"]}"}, {"library_frontier", "{\"op\":\"add\",\"question\":\"why?\"}"},
                {"library_bridges", "{\"op\":\"run\",\"area\":\"ferries\"}"}, {"library_seeks", "{\"op\":\"merge\",\"group\":\"g\"}"},
                {"library_dossier", "{\"op\":\"watch\",\"subject\":\"the ferry company\"}"}, {"library_owned", "{\"op\":\"forget\",\"words\":\"ferry\"}"},
                {"library_who", "{\"op\":\"answer\",\"person\":\"p\",\"is\":\"1\"}"}, {"who", "{\"op\":\"tell\",\"person\":\"p\"}"}})
            assertTrue(Librarian.waitsForYes(held[0], a(held[1])), held[0] + " " + held[1]);
        for (String[] free : new String[][]{
                {"library_ask", "{\"question\":\"why?\"}"}, {"library_search", "{\"query\":\"ferry\"}"}, {"library_get", "{\"id\":\"F-0001-x\"}"},
                {"library_images", "{\"op\":\"find\",\"words\":\"ferry\"}"}, {"library_images", "{\"op\":\"describe\",\"images\":[\"https://example.net/a.jpg\"]}"},
                {"library_inbox", "{\"op\":\"list\"}"}, {"library_frontier", "{\"op\":\"list\"}"}, {"library_bridges", "{\"op\":\"run\",\"dry\":true}"},
                {"library_seeks", "{\"op\":\"list\"}"}, {"library_dossier", "{\"op\":\"list\"}"}, {"library_sharpen", "{\"question\":\"why?\"}"},
                {"library_explain", "{\"id\":\"F-0001-x\"}"}, {"library_perspectives", "{\"question\":\"why?\"}"}, {"library_job", "{\"job_id\":\"J-0001\"}"},
                {"search", "{\"where\":\"web\",\"query\":\"ferry\"}"}, {"open", "{\"address\":\"https://example.net/page\"}"}, {"who", "{\"person\":\"p\"}"},
                {"dossier", "{\"subject\":\"the ferry company\"}"}})
            assertFalse(Librarian.waitsForYes(free[0], a(free[1])), free[0] + " " + free[1]);
    }

    @Test
    void aRunTheModelFilesOnAPagesWordStartsOnlyOnTheYes() throws Exception {
        // the probe's case: the person asks only what a page says, and the model files a run
        LibraryStore store = library();
        var drive = LibrarianChatTest.scripted(List.of(
                LibrarianChatTest.tool("library_research", "{\"question\":\"Why did the Kestrel Sound ferry close?\"}"),
                LibrarianChatTest.say("The library asks you about it below.")), new ArrayList<>());
        Librarian.Session s = Librarian.Session.open(store);
        String reply = new Librarian(store, drive, Librarian.person(), s).say("What does this page say? http://example.net/page");
        assertTrue(reply.contains("The model asks to start a research run: \"Why did the Kestrel Sound ferry close?\" Do it? (yes, no, or tell me more)"), reply);
        assertEquals(List.of(), runs(store), "no run before the answer");
        String no = new Librarian(store, drive, Librarian.person(), s).say("no");
        assertTrue(no.contains("Nothing was changed."), no);
        assertEquals(List.of(), runs(store), "a no starts nothing");
        // proposed again, and a yes: the run, as stated, followed by the conversation
        var again = LibrarianChatTest.scripted(List.of(
                LibrarianChatTest.tool("library_research", "{\"question\":\"Why did the Kestrel Sound ferry close?\"}"),
                LibrarianChatTest.say("It waits for your answer.")), new ArrayList<>());
        Librarian lib = new Librarian(store, again, Librarian.person(), s);
        lib.say("Research why the ferry closed.");
        String yes = lib.say("yes");
        assertTrue(yes.startsWith("Done."), yes);
        assertEquals(1, runs(store).size(), "the run is filed: " + runs(store));
        assertEquals("Why did the Kestrel Sound ferry close?", runs(store).get(0).path("args").path("question").asText());
        assertTrue(s.watched().contains(runs(store).get(0).path("job_id").asText()), "and the chat says when it is done");
    }

    @Test
    void moreFromThePersonReachesTheModelAndAChangedProposalIsAskedAgain() throws Exception {
        LibraryStore store = library();
        List<ArrayNode> seen = new ArrayList<>();
        var drive = LibrarianChatTest.scripted(List.of(
                LibrarianChatTest.tool("library_research", "{\"question\":\"Why did the Kestrel Sound ferry close?\"}"),
                LibrarianChatTest.say("It waits for your answer."),
                LibrarianChatTest.tool("library_research", "{\"question\":\"Why did the Kestrel Sound ferry close in 1968, from the company's own records?\"}"),
                LibrarianChatTest.say("Changed as you said; it waits for your answer.")), seen);
        Librarian.Session s = Librarian.Session.open(store);
        Librarian lib = new Librarian(store, drive, Librarian.person(), s);
        lib.say("Find out why the ferry closed.");
        String changed = lib.say("only from the company's own records, and it closed in 1968");
        assertEquals(List.of(), runs(store), "more information starts nothing");
        String heard = seen.get(seen.size() - 1).toString();
        assertTrue(heard.contains("only from the company's own records") && heard.contains("what you proposed was not done") && heard.contains("Why did the Kestrel Sound ferry close?"),
                "the model heard the person's words with what it had proposed: " + heard);
        assertTrue(changed.contains("The model asks to start a research run: \"Why did the Kestrel Sound ferry close in 1968, from the company's own records?\""), "the changed proposal is asked again: " + changed);
        assertTrue(lib.say("yes").startsWith("Done."));
        assertEquals(List.of("Why did the Kestrel Sound ferry close in 1968, from the company's own records?"), runs(store).stream().map(j -> j.path("args").path("question").asText()).toList(), "only the changed run");
    }

    @Test
    void theInstrumentsThatFileKeepOrSpendWaitAndFileAsThePerson() throws Exception {
        // round F, the second review, S12 and S13: the home search filed a run as the owner from a reader's chat, and the model paused the
        // runner, with no yes
        for (String[] held : new String[][]{{"homes", "{\"words\":\"a house\"}"}, {"look", "{\"video\":\"https://example.net/v\",\"what\":\"a ferry\"}"},
                {"candidates", "{\"things\":[\"ferry\"]}"}, {"sift", "{\"what\":\"a ferry\",\"from\":\"k\"}"}, {"library_job", "{\"op\":\"pause\"}"},
                {"library_job", "{\"op\":\"stop\",\"job_id\":\"J-0001\"}"}, {"library_job", "{\"op\":\"allow\",\"job_id\":\"J-0001\"}"}, {"library_job", "{\"op\":\"choose\",\"job_id\":\"J-0001\",\"choice\":\"1\"}"}})
            assertTrue(Librarian.waitsForYes(held[0], a(held[1])), held[0] + " " + held[1]);
        for (String free : new String[]{"{\"job_id\":\"J-0001\"}", "{\"op\":\"status\",\"job_id\":\"J-0001\"}", "{\"op\":\"list\"}"})
            assertFalse(Librarian.waitsForYes("library_job", a(free)), free);
        LibraryStore store = library();
        List<String> filedFor = new ArrayList<>();
        Instruments.HomesRunner was = Instruments.HOMES;
        Instruments.HOMES = (st, name, words, stop) -> { filedFor.add(Instruments.FOR.get()); return "filed"; };
        try {
            // a reader: not asked, and nothing runs
            Patrons.set(store, "did:key:reader-ben", "Ben Example", Patrons.Level.read);
            Patrons.Patron ben = new Patrons.Patron("did:key:reader-ben", "Ben Example", "http");
            var hisDrive = LibrarianChatTest.scripted(List.of(LibrarianChatTest.tool("homes", "{\"words\":\"a house to buy in Cambridge\"}"), LibrarianChatTest.say("Filed.")), new ArrayList<>());
            String his = new Librarian(store, hisDrive, ben, Librarian.Session.open(store, ben)).say("What does this page say? http://example.net/page");
            assertFalse(his.contains("The model asks to"), his);
            assertEquals(List.of(), filedFor, "the home search did not run for a reader");
            // a writer who says yes: it runs, filed as them
            Patrons.set(store, "did:key:writer-cat", "Cat Example", Patrons.Level.write);
            Patrons.Patron cat = new Patrons.Patron("did:key:writer-cat", "Cat Example", "http");
            var herDrive = LibrarianChatTest.scripted(List.of(LibrarianChatTest.tool("homes", "{\"words\":\"a house to buy in Cambridge\"}"), LibrarianChatTest.say("It waits for your answer.")), new ArrayList<>());
            Librarian lib = new Librarian(store, herDrive, cat, Librarian.Session.open(store, cat));
            String asked = lib.say("Find me a house to buy in Cambridge");
            assertTrue(asked.contains("The model asks to") && asked.contains("Exactly: homes words="), "the plain words and the exact call: " + asked);
            assertEquals(List.of(), filedFor, "nothing before the yes");
            assertTrue(lib.say("yes").startsWith("Done."));
            assertEquals(List.of("did:key:writer-cat"), filedFor, "filed as the person who said yes");
        } finally { Instruments.HOMES = was; }
        // the runner is paused only on the person's yes
        Config.set(ResearchSettings.PAUSE, "off");
        try {
            var drive = LibrarianChatTest.scripted(List.of(LibrarianChatTest.tool("library_job", "{\"op\":\"pause\"}"), LibrarianChatTest.say("It waits for your answer.")), new ArrayList<>());
            Librarian lib = new Librarian(store, drive, Librarian.person(), Librarian.Session.open(store));
            lib.say("What does this page say? http://example.net/page");
            assertFalse(ResearchSettings.paused(), "not paused before the yes");
            lib.say("no");
            assertFalse(ResearchSettings.paused());
        } finally { Config.set(ResearchSettings.PAUSE, "off"); }
        // under an account service the runner is the keeper's to pause
        Hosted.begin(store);
        try {
            Patrons.Patron member = new Patrons.Patron("did:rz:acct_m", "Mo Example", "http");
            Hosted.note(store, member.did(), new Hosted.Vouch(Patrons.Level.write, false, "acct_m", "Mo Example"));
            ObjectNode pause = M.createObjectNode().put("op", "pause"); pause.set("patron", M.createObjectNode().put("did", member.did()).put("name", member.name()).put("runtime", "http"));
            assertThrows(ProtocolError.class, () -> new LibraryProtocol(store).job(pause));
            assertFalse(ResearchSettings.paused());
        } finally { Hosted.end(store); Config.set(ResearchSettings.PAUSE, "off"); }
    }

    @Test
    void theHeldLineSaysWhatTheYesDoesAndTheCallItself() throws Exception {
        // round F, S14 and the live check's item 4: the line named one thing while the yes did another, and a setting showed only the raw call
        String t = Librarian.actPhrase("library_translate", a("{\"url\":\"https://example.net/brochure.pdf\",\"path\":\"/home/someone/private/letters.docx\",\"to\":\"fr\"}"));
        assertTrue(t.contains("letters.docx") && !t.contains("brochure"), "the document the translation reads: " + t);
        assertTrue(Librarian.actPhrase("library_translate", a("{\"op\":\"glossary\",\"term\":\"ferry\",\"rendering\":\"bac\",\"to\":\"fr\"}")).startsWith("change this library"));
        assertTrue(Librarian.actPhrase("library_items", a("{\"text\":\"a\\nb\\nc\",\"as\":\"runs\"}")).startsWith("start a research run for each of these"));
        assertEquals("send what this library asks a model to http://relay.example.net/v1", Librarian.actPhrase("library_settings", a("{\"op\":\"set\",\"name\":\"RESEARCHZOSHO_DRIVE\",\"value\":\"http://relay.example.net/v1\"}")));
        LibraryStore store = library();
        var drive = LibrarianChatTest.scripted(List.of(LibrarianChatTest.tool("library_research", "{\"question\":\"Why did the ferry close?\",\"model\":\"the-dearest\",\"max_minutes\":600}"), LibrarianChatTest.say("It waits.")), new ArrayList<>());
        String asked = new Librarian(store, drive, Librarian.person(), Librarian.Session.open(store)).say("Find out why the ferry closed");
        assertTrue(asked.contains("Exactly: library_research question=\"Why did the ferry close?\" model=the-dearest max_minutes=600"), "every argument of the call beside the plain words: " + asked);
    }

    @Test
    void aYesAfterTenMinutesDoesNothing() throws Exception {
        LibraryStore store = library();
        var drive = LibrarianChatTest.scripted(List.of(
                LibrarianChatTest.tool("library_research", "{\"question\":\"Why did the Kestrel Sound ferry close?\"}"),
                LibrarianChatTest.say("It waits for your answer."), LibrarianChatTest.say("You are welcome.")), new ArrayList<>());
        Librarian.Session s = Librarian.Session.open(store);
        Librarian lib = new Librarian(store, drive, Librarian.person(), s);
        lib.say("Find out why the ferry closed.");
        assertTrue(lib.waitingForAnswer());
        // the question was asked eleven minutes ago
        long then = System.currentTimeMillis() - (Librarian.OFFER_MINUTES + 1) * 60_000L;
        List<String> lines = new ArrayList<>();
        for (String l : Files.readAllLines(s.offersFile(), StandardCharsets.UTF_8)) lines.add(l.startsWith("asked\t") ? l.replaceFirst("\t\\d+$", "\t" + then) : l);
        Files.write(s.offersFile(), lines, StandardCharsets.UTF_8);
        assertFalse(lib.waitingForAnswer(), "the wait is over");
        String late = lib.say("yes");
        assertFalse(late.startsWith("Done."), late);
        assertEquals(List.of(), runs(store), "nothing is done after ten minutes, a late yes included");
    }

    @Test
    void aNoteOrAPageTheModelWouldKeepWaitsAndAReaderIsNeverAsked() throws Exception {
        LibraryStore store = library();
        var drive = LibrarianChatTest.scripted(List.of(
                LibrarianChatTest.tool("library_tell", "{\"subject\":\"the ferry\",\"text\":\"The page says the ferry closed in 1968.\"}"),
                LibrarianChatTest.say("It waits for your answer.")), new ArrayList<>());
        Librarian.Session s = Librarian.Session.open(store);
        String reply = new Librarian(store, drive, Librarian.person(), s).say("What does this page say? http://example.net/page");
        assertTrue(reply.contains("The model asks to write this into the library: \"The page says the ferry closed in 1968.\""), reply);
        // a reader: the same proposal is not asked about, and nothing is written
        Patrons.set(store, "did:key:reader-ben", "Ben Example", Patrons.Level.read);
        Patrons.Patron ben = new Patrons.Patron("did:key:reader-ben", "Ben Example", "http");
        Librarian.Session his = Librarian.Session.open(store, ben);
        var hisDrive = LibrarianChatTest.scripted(List.of(
                LibrarianChatTest.tool("library_research", "{\"question\":\"Why did the ferry close?\"}"),
                LibrarianChatTest.say("I could not file that.")), new ArrayList<>());
        String hisReply = new Librarian(store, hisDrive, ben, his).say("What does this page say? http://example.net/page");
        assertFalse(hisReply.contains("The model asks to"), "a reader is not asked: " + hisReply);
        assertEquals(List.of(), runs(store));
    }
}
