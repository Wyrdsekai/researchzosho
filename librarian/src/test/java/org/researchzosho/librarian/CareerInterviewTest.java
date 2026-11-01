package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.librarian.profiles.CareerProfile;
import org.researchzosho.librarian.profiles.GenealogyProfile;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

class CareerInterviewTest {
    static final ObjectMapper M = new ObjectMapper();

    static final String READING = "## The arc\nYou founded twice [E2].\n## Questions for you\n"
            + "1. Why do you keep founding companies? (what drives you) [E2][C2]\n"
            + "2. Which size of company suited you best, and why? (preference) [E2][E3][general knowledge]\n"
            + "3. What would a good week in your next job look like? (what you want next) [E2]\n";

    /** A stand-in model: the reading ends in three questions; the answers to 1 and 2 settle a topic each, and 1 opens a follow-up. */
    static final class StandIn implements Researcher.Drive {
        @Override public ObjectNode chat(ArrayNode m, ArrayNode t, int x, String y) { throw new UnsupportedOperationException(); }
        @Override public int contextWindow() { return 32_000; }
        /** The reading the stand-in writes; a test sets a second one to have the reading written again. */
        static volatile String reading = READING;
        @Override public String prose(ArrayNode m, int max) { return reading; }
        @Override public String classify(ArrayNode m, int max) {
            String p = m.get(0).path("content").asText();
            if (p.startsWith("A person looking for work answered questions")) {
                assertTrue(p.contains("company size (the size of the company)"), "the model is given the topics by name");
                return "Here it is: {\"topics\": [{\"answer\": \"1\", \"dimension\": \"company size\", \"state\": \"decided\", \"want\": \"under fifty people\"},"
                        + "{\"answer\": \"2\", \"dimension\": \"pace\", \"state\": \"leaning\", \"want\": \"probably a steady pace\"},"
                        + "{\"answer\": \"2\", \"dimension\": \"hats\", \"state\": \"decided\", \"want\": \"big ones\"}],"
                        + "\"followups\": [{\"answer\": \"1\", \"followup\": \"What makes a small team work well for you?\"}]}";
            }
            return "[{\"dimension\":\"domain\",\"state\":\"open\",\"want\":\"\",\"from\":\"\"}]";
        }
    }

    static Career.Seeker seeker(LibraryStore store, Path tmp) throws Exception {
        Path export = tmp.resolve("profile.json");
        Files.writeString(export, CareerTest.linkedin(), UTF_8);
        Career.Seeker s = Career.provide(store, export.toString(), "", true).seeker();
        Career.reading(store, s); Career.wants(store, s);
        return s;
    }


    @Test void aGuessOnTheRoleThePlaceOrTheRightToWorkIsAskedAsTheQuestionItIsBeforeTheSearchRunsOnIt(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Career.DRIVE = StandIn::new;
        try {
        Career.Seeker s = seeker(store, tmp);
        Career.setWant(store, s, "right to work", "guess", "You seem to hold the licence of the state you work in; is it the one the next post needs?");
        Career.setWant(store, s, "place", "guess", "Every role so far was in Chicago");
        Career.setWant(store, s, "role", "decided", "engineering lead");
        Career.setWant(store, s, "arrangement", "guess", "full-time, as every role was");
        ObjectNode o = CareerInterview.state(store, s);
        CareerInterview.minimum(store, s, o);
        List<String> tagged = new ArrayList<>();
        for (CareerInterview.Question q : CareerInterview.questions(o)) if (q.tag().startsWith(CareerInterview.MINIMUM)) tagged.add(q.tag() + " | " + q.text());
        assertTrue(tagged.contains("the minimum: right to work | You seem to hold the licence of the state you work in; is it the one the next post needs?"), "a guess on the right to work is asked as the question it already is: " + tagged);
        assertTrue(tagged.contains("the minimum: place | Is this right: Every role so far was in Chicago?"), "a guess written as a statement is asked as one: " + tagged);
        assertTrue(tagged.stream().noneMatch(x -> x.startsWith("the minimum: role ")), "a decided role is not asked: " + tagged);
        assertTrue(tagged.stream().noneMatch(x -> x.startsWith("the minimum: arrangement ")), "a guess on a topic a wrong guess does not cost the search is left to the reading's questions: " + tagged);
        int n = CareerInterview.questions(o).size();
        CareerInterview.minimum(store, s, o);
        assertEquals(n, CareerInterview.questions(o).size(), "asked once");
        } finally { Career.DRIVE = Explain::drive; }
    }

    @Test
    void theReadingsQuestionsAreTakenApartIntoWhatIsAskedWhatItIsAboutAndWhatItRestsOn() {
        List<CareerInterview.Question> qs = CareerInterview.fromReading(READING);
        assertEquals(3, qs.size());
        assertEquals("Which size of company suited you best, and why?", qs.get(1).text());
        assertEquals("preference", qs.get(1).tag());
        assertEquals("[E2][E3][general knowledge]", qs.get(1).ids());
        assertTrue(CareerInterview.fromReading("## Possible wants and changes\n1. Remote or not? (place, or remote) [E1]").get(0).text().startsWith("Remote or not?"), "an older reading's questions count");
    }

    @Test
    void oneAtATimeInAChatTheCountFirstAnswersKeptAsFactsAndTopicsSettledInTheirWords(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Career.DRIVE = StandIn::new;
        PrintStream was = System.out; ByteArrayOutputStream sink = new ByteArrayOutputStream();
        try {
            Career.Seeker s = seeker(store, tmp);
            assertTrue(Career.text(store, s, "/career").contains("To answer the reading's questions one at a time, type /career interview."));
            String opened = CareerInterview.start(store, s, "terminal");
            assertTrue(opened.startsWith("The library has 5 questions for you") && opened.contains("Type skip to pass on one, or stop to finish another time")
                    && opened.endsWith("Question 1 of 5: Why do you keep founding companies?"), opened);
            assertNotNull(CareerInterview.holder(store, "terminal"));
            assertNull(CareerInterview.take(store, "web:someone-else", "hello"), "another chat's lines are its own, not answers");

            System.setOut(new PrintStream(sink, true, UTF_8));   // the terminal route prints a word while the model reads
            String first = LibrarianCli.fieldTakes(store, "Under fifty people, I like small teams.");
            System.setOut(was);
            assertEquals("Taken: The size of the company: decided. Under fifty people.\n\nA follow-up to question 1: What makes a small team work well for you?", first);
            assertEquals("Passed over.\n\nQuestion 2 of 5: Which size of company suited you best, and why?", CareerInterview.take(store, "terminal", "skip"));
            assertEquals("Stopped. 1 of 5 questions are answered; /career interview picks up at question 2.", CareerInterview.take(store, "terminal", "stop"));
            assertNull(CareerInterview.take(store, "terminal", "anything"), "stopped, the interview takes no more lines");
            String again = CareerInterview.start(store, s, "web:abc");
            assertTrue(again.startsWith("Picking up where you stopped: 1 of 5 questions answered.") && again.endsWith("Question 2 of 5: Which size of company suited you best, and why?"), again);
            assertNull(CareerInterview.take(store, "terminal", "small"), "the browser chat holds it now");
            String second = CareerInterview.take(store, "web:abc", "Probably a steady pace, somewhere small.");
            assertTrue(second.startsWith("Taken: The pace of the work: a guess. Probably a steady pace.\n\nQuestion 3 of 5:"), second);
            assertFalse(second.contains("hats"), "a topic the model named that is not one is left out");
            String third = CareerInterview.take(store, "web:abc", "Building something with a few people I trust.");
            assertTrue(third.startsWith("Kept your answer. It settles no topic by itself, and the next reading will use it.") && third.endsWith("Question 4 of 5: " + CareerInterview.LOOK_FOR_Q), third);
            assertTrue(CareerInterview.take(store, "web:abc", "skip").endsWith("Question 5 of 5: " + CareerInterview.KEEP_OUT_Q), "the two closing questions come last");
            String last = CareerInterview.take(store, "web:abc", "skip");
            assertTrue(last.contains("That was the last question. You answered 3 of 5, and the library set 2 topics of what you want from your answers.") && last.contains("type /career reading"), last);
            assertNull(CareerInterview.holder(store, "web:abc"), "the interview closes at its end");

            ArrayNode facts = Career.evidence(store, s);
            long answers = 0; for (JsonNode f : facts) if (f.path("kind").asText().equals("answer")) answers++;
            assertEquals(3, answers, "each answer is a fact the next reading uses");
            assertTrue(facts.toString().contains("Asked: Why do you keep founding companies? You said: Under fifty people, I like small teams."));
            assertTrue(Files.readString(s.dir(store).resolve("told.md")).contains("Answering question 2: Which size of company suited you best, and why?"));
            assertTrue(Files.readString(s.dir(store).resolve("interview-settle.txt")).contains("\"company size\""), "the model's own reading of the answers is kept");
            ObjectNode w = Career.wants(store, s);
            assertEquals("decided", Career.find(w.path("dimensions"), "company size").path("state").asText(), "a topic set from an answer stays through a new draft");
            assertEquals("From your answer to question 1.", Career.basis(Career.find(w.path("dimensions"), "company size")));
            assertTrue(String.join("\n", Career.logTail(store, s, 30)).contains("From your answer to question 1, the library set the size of the company to decided: under fifty people."));

            // the page shows every question, the answers given in the chats, and what was taken; the reading is shown without them
            String page = CareerPage.body(store, s, true);
            assertTrue(page.contains("<details class=\"sec\" id=\"questions\"><summary><h2>Questions for you</h2></summary>") && page.contains("3 of 5 questions are answered.") && page.contains(">Under fifty people, I like small teams.</textarea>")
                    && page.contains("<b>A follow-up to question 1:</b> What makes a small team work well for you?") && page.contains("Taken: The size of the company: decided."), page);
            int prose = page.indexOf("<div class=\"prose\">");
            assertFalse(page.substring(prose, page.indexOf("</div>", prose)).contains("Why do you keep founding companies"), "the reading is shown without its questions, which are the form");
            CareerProfile cp = new CareerProfile();
            Profile.Page posted = cp.page(store, Patrons.Patron.PERSON, "/career", "POST", Map.of(), Map.of("op", "answers", "seeker", "jane-doe", "a.1a", "We talk every day and ship every week.", "a.1", "Under fifty people, I like small teams."));
            assertEquals("/career?seeker=jane-doe#questions", posted.redirect());
            assertEquals("We talk every day and ship every week.", CareerInterview.answerOf(CareerInterview.state(store, s), "1a"), "the page answers the follow-up; the unchanged answer is not read again");

            // a new reading continues the interview as a new round: every answer stays, the two closing questions come first in the
            // round since they were passed over, and the reading's new questions follow, numbered on
            StandIn.reading = "*Read on 2026-10-29 from 9 facts.*\n## The arc\nYou founded twice [E2].\n## Questions for you\n"
                    + "1. You said under fifty people [E4]; how small is too small? (preference) [E4]\n2. You said a good week has shipping in it [E6]; shipping what? (what you want next) [E6]\n";
            Career.reading(store, s);
            JsonNode cont = CareerInterview.state(store, s);
            assertEquals("Under fifty people, I like small teams.", CareerInterview.answerOf(cont, "1"), "the earlier answers stay");
            assertEquals("We talk every day and ship every week.", CareerInterview.answerOf(cont, "1a"));
            assertEquals(List.of("1", "1a", "2", "3", "4", "5", "6", "7"), CareerInterview.questions(cont).stream().map(CareerInterview.Question::n).toList());
            assertEquals(CareerInterview.LOOK_FOR_Q, CareerInterview.byN(cont, "4").text()); assertEquals(CareerInterview.KEEP_OUT_Q, CareerInterview.byN(cont, "5").text());
            assertEquals("You said under fifty people [E4]; how small is too small?", CareerInterview.byN(cont, "6").text());
            assertTrue(cont.path("skipped").isEmpty(), "the closing questions passed over come back to be asked");
            List<CareerInterview.Round> rounds = CareerInterview.rounds(cont);
            assertEquals(2, rounds.size());
            assertEquals(3, rounds.get(0).count()); assertEquals(4, rounds.get(1).from()); assertEquals(4, rounds.get(1).count()); assertEquals(2, rounds.get(1).deeper());
            assertTrue(rounds.get(1).label().matches("the reading of \\d{4}-\\d\\d-\\d\\d \\(\\d+ facts\\)"), "named by the library's own first line: " + rounds.get(1).label());
            try (Stream<Path> files = Files.list(s.dir(store))) { assertTrue(files.noneMatch(f -> f.getFileName().toString().startsWith("interview-2")), "nothing is set aside"); }
            String round2 = CareerInterview.start(store, s, "terminal");
            assertEquals("You answered 3 questions before. The library wrote its reading again with your answers, and has 4 more: 2 about the search, then 2 that go deeper into what you said. "
                    + "Answer any, skip any, or stop when you like; /career search needs no more than you have given.\n\nQuestion 4 of 7: " + CareerInterview.LOOK_FOR_Q, round2);
            String page2 = CareerPage.body(store, s, true);
            assertTrue(page2.contains("3 of 7 questions are answered.") && page2.contains("<h3>From " + rounds.get(1).label() + "</h3>") && page2.contains("Written with your answers so far: 2 about the search, then 2 that go deeper")
                    && page2.contains(">Under fifty people, I like small teams.</textarea>"), page2);
        } finally { System.setOut(was); Career.DRIVE = Explain::drive; StandIn.reading = READING; }
    }

    /** A topic decided by an earlier answer keeps its words until an answer says it changed; and every answer can be read again. */
    @Test
    void anEarlierDecidedTopicStandsUnlessAnAnswerChangesItAndTheAnswersCanBeReadAgain(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        String[] settle = {""};
        Career.DRIVE = () -> new Researcher.Drive() {
            @Override public ObjectNode chat(ArrayNode m, ArrayNode t, int x, String y) { throw new UnsupportedOperationException(); }
            @Override public int contextWindow() { return 32_000; }
            @Override public String prose(ArrayNode m, int max) { return READING; }
            @Override public String classify(ArrayNode m, int max) {
                String p = m.get(0).path("content").asText();
                if (p.startsWith("A person looking for work answered questions")) {
                    assertTrue(p.contains("what the person wants next for that topic"), "the model is told to read for the next job");
                    return settle[0];
                }
                return "[]";
            }
        };
        try {
            Career.Seeker s = seeker(store, tmp);
            settle[0] = "{\"topics\": [{\"answer\": \"1\", \"dimension\": \"company size\", \"state\": \"decided\", \"want\": \"under fifty people\"}]}";
            CareerInterview.saveAnswers(store, s, Map.of("1", "Under fifty people."));
            assertEquals("under fifty people", Career.find(Career.wantsKept(store, s).path("dimensions"), "company size").path("want").asText());
            settle[0] = "{\"topics\": [{\"answer\": \"2\", \"dimension\": \"company size\", \"state\": \"decided\", \"want\": \"we were twelve at the start\"}]}";
            String said = CareerInterview.saveAnswers(store, s, Map.of("2", "We were twelve at the start."));
            assertEquals("under fifty people", Career.find(Career.wantsKept(store, s).path("dimensions"), "company size").path("want").asText(), "an answer about the past does not overwrite what was decided");
            assertTrue(said.contains("settles no topic by itself"), said);
            settle[0] = "{\"topics\": [{\"answer\": \"3\", \"dimension\": \"company size\", \"state\": \"decided\", \"want\": \"up to two hundred\", \"changes\": true}]}";
            CareerInterview.saveAnswers(store, s, Map.of("3", "Up to two hundred, I have changed my mind."));
            assertEquals("up to two hundred", Career.find(Career.wantsKept(store, s).path("dimensions"), "company size").path("want").asText(), "an answer that says it changed replaces it");

            // read again: the topics set from answers are cleared and set afresh from one reading of every answer, in question order
            settle[0] = "{\"topics\": [{\"answer\": \"1\", \"dimension\": \"company size\", \"state\": \"decided\", \"want\": \"under fifty\"}, {\"answer\": \"3\", \"dimension\": \"role\", \"state\": \"decided\", \"want\": \"CTO\"}]}";
            assertEquals("The library read your 3 answers again: they set 2 topics, 2 of them decided.", CareerInterview.resettle(store, s));
            JsonNode w = Career.wantsKept(store, s).path("dimensions");
            assertEquals("under fifty", Career.find(w, "company size").path("want").asText()); assertEquals("CTO", Career.find(w, "role").path("want").asText());
            assertEquals("Taken: Your role: decided. CTO.", CareerInterview.takenOf(CareerInterview.state(store, s), "3"));
            assertEquals("", CareerInterview.takenOf(CareerInterview.state(store, s), "2"), "an answer that settles nothing now has no note");
            CareerProfile cp = new CareerProfile();
            assertTrue(cp.chatReply(store, Patrons.Patron.PERSON, "terminal", "/career answers").startsWith("The library read your 3 answers again"));
            assertTrue(CareerPage.body(store, s, true).contains(">Read my answers again</button>"));
        } finally { Career.DRIVE = Explain::drive; }
    }

    /** The earlier version set an answered interview aside when the reading was written again; it comes back as the first round. */
    @Test
    void anInterviewSetAsideByTheEarlierVersionComesBackAsTheFirstRound(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Career.DRIVE = StandIn::new;
        try {
            Career.Seeker s = seeker(store, tmp);
            String key = CareerInterview.readingKey(Career.readingKept(store, s));
            Files.writeString(s.dir(store).resolve("interview-2026-10-27-1a2b3c.json"), """
                    {"reading": "*Read on 2026-10-27 from 10 facts.*#1a2b3c", "begun": "2026-10-27", "held_by": "", "open": false, "current": "",
                     "questions": [{"n": "1", "text": "Why do you keep founding companies?", "tag": "what drives you", "ids": "[E2]", "followup_to": ""},
                                   {"n": "1a", "text": "What makes a small team work well for you?", "tag": "follow-up", "ids": "", "followup_to": "1"},
                                   {"n": "2", "text": "Which size suited you best?", "tag": "preference", "ids": "[E3]", "followup_to": ""}],
                     "answers": {"1": {"text": "Creation.", "at": "2026-10-27"}, "1a": {"text": "Trust.", "at": "2026-10-27"}, "2": {"text": "Small.", "at": "2026-10-27"}},
                     "skipped": [], "taken": {"1": "Taken: The kind of work: decided. Creation."}}""", UTF_8);
            ObjectNode fresh = M.createObjectNode().put("reading", key).put("begun", "2026-10-28").put("held_by", "").put("open", false).put("current", "");
            ArrayNode qs = fresh.putArray("questions");
            int n = 0; for (CareerInterview.Question q : CareerInterview.fromReading(Career.readingKept(store, s))) qs.addObject().put("n", String.valueOf(++n)).put("text", q.text()).put("tag", q.tag()).put("ids", q.ids()).put("followup_to", "");
            fresh.putObject("answers"); fresh.putArray("skipped"); fresh.putObject("taken");
            CareerInterview.save(store, s, fresh);

            JsonNode o = CareerInterview.state(store, s);
            assertEquals(List.of("1", "1a", "2", "3", "4", "5", "6", "7"), CareerInterview.questions(o).stream().map(CareerInterview.Question::n).toList(), "the earlier round, the closing pair, then the new reading's three");
            assertEquals("Creation.", CareerInterview.answerOf(o, "1")); assertEquals("Trust.", CareerInterview.answerOf(o, "1a")); assertEquals("Small.", CareerInterview.answerOf(o, "2"));
            assertEquals("Taken: The kind of work: decided. Creation.", CareerInterview.takenOf(o, "1"));
            assertEquals(CareerInterview.LOOK_FOR_Q, CareerInterview.byN(o, "3").text());
            assertEquals("Why do you keep founding companies?", CareerInterview.byN(o, "5").text());
            List<CareerInterview.Round> rounds = CareerInterview.rounds(o);
            assertEquals(2, rounds.size());
            assertEquals("the reading of 2026-10-27 (10 facts)", rounds.get(0).label()); assertEquals(2, rounds.get(0).count()); assertEquals(2, rounds.get(0).deeper());
            assertEquals(3, rounds.get(1).from()); assertEquals(5, rounds.get(1).count()); assertEquals(3, rounds.get(1).deeper());
            assertEquals("interview-2026-10-27-1a2b3c.json", o.path("restored_from").get(0).asText());
            assertEquals(8, CareerInterview.questions(CareerInterview.state(store, s)).size(), "brought back once");
            assertTrue(CareerInterview.start(store, s, "terminal").startsWith("You answered 2 questions before. The library wrote its reading again with your answers, and has 5 more: 2 about the search, then 3 that go deeper"));
            String page = CareerPage.body(store, s, true);
            assertTrue(page.contains("2 of 7 questions are answered.") && page.contains("<h3>From the reading of 2026-10-27 (10 facts)</h3>") && page.contains(">Creation.</textarea>"), page);
        } finally { Career.DRIVE = Explain::drive; }
    }

    @Test
    void theBrowserChatRunsTheFieldsCommandsAndTheInterviewWithoutTheModel(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Career.DRIVE = StandIn::new;
        try {
            seeker(store, tmp);
            Librarian.Session session = Librarian.Session.open(store, Patrons.Patron.PERSON);
            Map<String, String> say = Map.of("session", session.id, "say", "/help");
            Pages.chatPost(null, store, Patrons.Patron.PERSON, say);   // no daemon, no model: a reply that came from the model would fail here
            assertTrue(lastReply(store, session.id).contains("- /career (your working life and what you want next"), lastReply(store, session.id));
            Pages.chatPost(null, store, Patrons.Patron.PERSON, Map.of("session", session.id, "say", "/career interview"));
            assertTrue(lastReply(store, session.id).endsWith("Question 1 of 5: Why do you keep founding companies?"), lastReply(store, session.id));
            Pages.chatPost(null, store, Patrons.Patron.PERSON, Map.of("session", session.id, "say", "Under fifty people."));
            assertTrue(lastReply(store, session.id).startsWith("Taken: The size of the company: decided."), "a plain line in the chat that holds the interview is its answer");
            Pages.chatPost(null, store, Patrons.Patron.PERSON, Map.of("session", session.id, "say", "/nothing"));
            assertEquals("That is not one of the chat's commands. /help lists them.", lastReply(store, session.id));

            // someone else who may write: the career record is theirs to change, the family's facts are the teller's
            Patrons.set(store, "did:key:writer", "A writer", Patrons.Level.write);
            Patrons.Patron writer = new Patrons.Patron("did:key:writer", "A writer", "web");
            String tell = new GenealogyProfile().chatReply(store, writer, "web:w", "/family tell my grandfather was a sailor");
            assertTrue(tell.startsWith("This one changes the family's facts, and the library takes those from the person whose family it is."), tell);
            Patrons.set(store, "did:key:reader", "A reader", Patrons.Level.read);
            Patrons.Patron reader = new Patrons.Patron("did:key:reader", "A reader", "web");
            assertTrue(new CareerProfile().chatReply(store, reader, "web:r", "/career want pay decided more").startsWith("That changes the record, so it is for those who may write"));
            assertTrue(new CareerProfile().chatReply(store, reader, "web:r", "/career read").startsWith("The library holds "), "reading the record is for anyone who may read");
            assertNull(new CareerProfile().chatReply(store, reader, "web:r", "/family check"), "not this field's command");
        } finally { Career.DRIVE = Explain::drive; }
    }

    static String lastReply(LibraryStore store, String id) throws Exception {
        List<ObjectNode> ms = Librarian.Session.resume(store, id, Patrons.Patron.PERSON).messages();
        for (int i = ms.size() - 1; i >= 0; i--) if (ms.get(i).path("role").asText().equals("assistant")) return ms.get(i).path("content").asText();
        return "";
    }
}
