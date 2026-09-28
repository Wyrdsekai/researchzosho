package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Where a person can answer, a family-history question is a question to them, with no as the default: "Use genealogy mode for this
 * question? (y/N)". Only a clear yes turns genealogy mode on, for that question. Where nobody can answer, the run is ordinary and the
 * person is told how to ask for genealogy mode.
 */
class GenealogyOfferTest {

    static final ObjectMapper M = new ObjectMapper();
    static final String FAMILY = "Who were my great-grandfather's parents, and where did they farm?";
    static final String ASK_LINE = "Use genealogy mode for this question? (y/N)";

    @TempDir Path tmp;

    LibraryStore library() throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        return store;
    }

    static ObjectNode filed(LibraryStore store) throws Exception {
        List<ObjectNode> all = new Jobs(store, j -> "").active();
        return all.isEmpty() ? null : all.get(all.size() - 1);
    }

    Librarian chat(LibraryStore store, List<ObjectNode> steps, List<ArrayNode> seen) throws Exception {
        return new Librarian(store, LibrarianChatTest.scripted(steps, seen), Librarian.person(), Librarian.Session.open(store));
    }

    // ---- the chat ----

    @Test
    void theChatAsksOnceAndEnterIsNo() throws Exception {
        LibraryStore store = library();
        List<ArrayNode> seen = new ArrayList<>();
        Librarian lib = chat(store, List.of(LibrarianChatTest.tool("library_research", "{\"question\": \"" + FAMILY + "\"}"), LibrarianChatTest.say("I have filed it.")), seen);
        String reply = lib.say("Find out who my great-grandfather's parents were");
        assertTrue(reply.endsWith("This looks like family history. Genealogy mode searches record collections (registers, newspapers, censuses), builds your family tree and uses what the library already knows about your relatives. " + ASK_LINE), reply);
        ObjectNode job = filed(store);
        assertEquals(Jobs.OFFERED, job.path("state").asText(), "the run waits for the answer");
        assertFalse(job.path("args").has("field"));
        assertTrue(lib.waitingForAnswer());
        assertTrue(seen.get(seen.size() - 1).toString().contains("waits for the person's answer"), "the model is told the run waits");
        // the tool the model is given has no field: only the person chooses one
        for (var t : Librarian.tools(store)) assertFalse(t.path("function").path("parameters").path("properties").has("field"));

        int modelCalls = seen.size();
        String no = lib.say("");   // Enter
        assertEquals(modelCalls, seen.size(), "the answer is read before the model sees anything");
        assertTrue(no.startsWith("It is researched as ordinary research.") && no.contains(job.path("job_id").asText() + " has started"), no);
        ObjectNode after = new Jobs(store, j -> "").get(job.path("job_id").asText());
        assertEquals("queued", after.path("state").asText());
        assertFalse(after.path("args").has("field"), "no is ordinary research");
        assertFalse(lib.waitingForAnswer());

        // the same question again in this conversation: not asked again; filed as the person said
        Librarian again = new Librarian(store, LibrarianChatTest.scripted(List.of(LibrarianChatTest.tool("library_research", "{\"question\": \"" + FAMILY + "\"}"), LibrarianChatTest.say("Filed again.")), new ArrayList<>()),
                Librarian.person(), lib.session());
        String second = again.say("Look it up once more");
        assertFalse(second.contains("(y/N)"), second);
        assertEquals("queued", filed(store).path("state").asText());
        assertFalse(filed(store).path("args").has("field"));
    }

    @Test
    void aClearYesTurnsGenealogyModeOnForThatQuestionAndWordsAfterItAreATurn() throws Exception {
        LibraryStore store = library();
        List<ArrayNode> seen = new ArrayList<>();
        Librarian lib = chat(store, List.of(LibrarianChatTest.tool("library_research", "{\"question\": \"" + FAMILY + "\"}"), LibrarianChatTest.say("Filed."),
                LibrarianChatTest.say("His wife is not in the library yet.")), seen);
        lib.say("Find out who my great-grandfather's parents were");
        String id = filed(store).path("job_id").asText();
        String yes = lib.say("Yes, and what do you know about his wife?");
        ObjectNode job = new Jobs(store, j -> "").get(id);
        assertEquals("queued", job.path("state").asText());
        assertEquals("genealogy", job.path("args").path("field").asText(), "a clear yes asks for genealogy mode");
        assertEquals("chat-yes", job.path("args").path("field_how").asText());
        assertTrue(yes.startsWith("The question is researched in genealogy mode. Research run " + id + " has started"), yes);
        assertTrue(yes.endsWith("His wife is not in the library yet."), "the words after the yes are a turn like any other: " + yes);
        assertTrue(seen.get(seen.size() - 1).toString().contains("and what do you know about his wife?"), "the model sees the words after the yes");
        for (String w : List.of("y", "Y", "yes", "YES", "ｙｅｓ", "はい", "ja", "oui", "sí", "sim", "да", "是", "네", "yes.", "Yes, please")) assertNotNull(Librarian.yesWord(w), w);
        for (String w : List.of("", "n", "no", "yeah maybe", "yesterday", "japan", "sure")) assertNull(Librarian.yesWord(w), w);
    }

    @Test
    void aPersonWhoAsksForGenealogyModeInTheChatIsAskedToConfirmItWhateverTheModelWrote() throws Exception {
        // the person names the mode; the model's question has no family words, and it passes the field, or it does not
        for (String withField : List.of(", \"field\": \"genealogy\"", "")) {
            LibraryStore store = new LibraryStore(tmp.resolve("lib-" + withField.length())); store.init();
            new LibrarianIndex(store, Embeddings.none()).rebuild();
            List<ArrayNode> seen = new ArrayList<>();
            Librarian lib = chat(store, List.of(LibrarianChatTest.tool("library_research", "{\"question\": \"Who was Tom Hale of Leeds, and what records of his life exist?\"" + withField + "}"),
                    LibrarianChatTest.say("Filed.")), seen);
            String reply = lib.say("Please research Tom Hale of Leeds, my great-great-uncle, in genealogy mode.");
            assertTrue(reply.endsWith(ASK_LINE), reply);
            assertEquals(Jobs.OFFERED, filed(store).path("state").asText());
            lib.say("y");
            assertEquals("genealogy", filed(store).path("args").path("field").asText(), "a yes turns it on");
        }
        // the model passes the field for a person whose words do not name it: the person is asked, never the model's choice alone
        LibraryStore store = new LibraryStore(tmp.resolve("lib-model")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        Librarian lib = chat(store, List.of(LibrarianChatTest.tool("library_research", "{\"question\": \"Who was Ann Hart of Leeds?\", \"field\": \"genealogy\"}"), LibrarianChatTest.say("Filed.")), new ArrayList<>());
        assertTrue(lib.say("Look up Ann Hart of Leeds for me").endsWith(ASK_LINE));
        String no = lib.say("");
        assertTrue(no.startsWith("It is researched as ordinary research."), no);
        assertFalse(filed(store).path("args").has("field"), "Enter is no");
        // a field that is not one the person is asked about: ordinary research, and the model is told
        LibraryStore other = new LibraryStore(tmp.resolve("lib-other")); other.init();
        new LibrarianIndex(other, Embeddings.none()).rebuild();
        List<ArrayNode> seen = new ArrayList<>();
        Librarian plain = chat(other, List.of(LibrarianChatTest.tool("library_research", "{\"question\": \"How were the Antikythera gears cut?\", \"field\": \"astrology\"}"), LibrarianChatTest.say("Filed.")), seen);
        assertFalse(plain.say("Find out how the Antikythera gears were cut").contains("(y/N)"));
        assertEquals("queued", filed(other).path("state").asText());
        assertTrue(seen.get(seen.size() - 1).toString().contains("This run is ordinary research: the field you passed is not used."), "the model reads that the field was not used");
    }

    @Test
    void aPersonWhoAsksForGenealogyModeInASentenceOfItsOwnIsAskedAboutTheRunsOfThatMessage() throws Exception {
        // the person names the mode in a sentence of its own, or with words the model's question does not share
        String endo = "Taro Endo (born 1880 in Leeds): what records exist, and who were his parents?";
        List<String> said = List.of("Find out about Taro Endo, born 1880 in Leeds. Please use genealogy mode.", "Use genealogy mode. Taro Endo, born 1880 in Leeds.",
                "Research my family history in genealogy mode");
        for (int i = 0; i < said.size(); i++) {
            LibraryStore store = new LibraryStore(tmp.resolve("lib-" + i)); store.init();
            new LibrarianIndex(store, Embeddings.none()).rebuild();
            Librarian lib = chat(store, List.of(LibrarianChatTest.tool("library_research", "{\"question\": \"" + endo + "\"}"), LibrarianChatTest.say("Filed.")), new ArrayList<>());
            String reply = lib.say(said.get(i));
            assertTrue(reply.endsWith(ASK_LINE), said.get(i) + " → " + reply);
            assertEquals(Jobs.OFFERED, filed(store).path("state").asText());
            lib.say("y");
            assertEquals("genealogy", filed(store).path("args").path("field").asText(), "a yes turns it on");
        }
        // a list of questions after a line that asks for the mode: one question for all of them
        LibraryStore store = new LibraryStore(tmp.resolve("lib-list")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        String list = "Taro Endo (1880-1945): what records exist?\\nHana Endo (1885-1950): what records exist?";
        Librarian lib = chat(store, List.of(LibrarianChatTest.tool("library_questions", "{\"text\": \"" + list + "\", \"as\": \"runs\", \"title\": \"the Endos\"}"), LibrarianChatTest.say("Both are filed.")), new ArrayList<>());
        String reply = lib.say("Research these questions in genealogy mode:\nTaro Endo (1880-1945): what records exist?\nHana Endo (1885-1950): what records exist?");
        assertTrue(reply.endsWith("Use genealogy mode for these 2 questions? (y/N)"), reply);
        // asked for with a second request in the same sentence: only the run the request names waits
        LibraryStore two = new LibraryStore(tmp.resolve("lib-two")); two.init();
        new LibrarianIndex(two, Embeddings.none()).rebuild();
        Librarian both = chat(two, List.of(LibrarianChatTest.tool("library_research", "{\"question\": \"" + endo + "\"}"),
                LibrarianChatTest.tool("library_research", "{\"question\": \"How does sourdough fermentation work?\"}"), LibrarianChatTest.say("Filed both.")), new ArrayList<>());
        assertTrue(both.say("Research Taro Endo in genealogy mode and look up how sourdough fermentation works").endsWith(ASK_LINE));
        for (ObjectNode j : new Jobs(two, x -> "").active())
            assertEquals(j.path("args").path("question").asText().equals(endo) ? Jobs.OFFERED : "queued", j.path("state").asText(), j.toString());
    }

    @Test
    void twoRunsInOneTurnGetOneQuestionAndOtherWordsAreNo() throws Exception {
        LibraryStore store = library();
        Librarian lib = chat(store, List.of(LibrarianChatTest.tool("library_research", "{\"question\": \"" + FAMILY + "\"}"),
                LibrarianChatTest.tool("library_research", "{\"question\": \"Who was my great-grandmother, and where was she born?\"}"), LibrarianChatTest.say("Filed both."),
                LibrarianChatTest.say("The gears were cut by hand.")), new ArrayList<>());
        String reply = lib.say("Find out about my great-grandparents");
        assertEquals(1, reply.split("\\(y/N\\)", -1).length - 1, "one question for both: " + reply);
        assertTrue(reply.endsWith("Use genealogy mode for these 2 questions? (y/N)"), reply);
        String other = lib.say("How were the Antikythera gears cut?");
        assertTrue(other.startsWith("It is researched as ordinary research. Research runs ") && other.endsWith("The gears were cut by hand."), other);
        for (ObjectNode j : new Jobs(store, x -> "").active()) { assertEquals("queued", j.path("state").asText()); assertFalse(j.path("args").has("field")); }
    }

    @Test
    void aListOfQuestionsSentFromTheChatIsAskedAboutToo() throws Exception {
        LibraryStore store = library();
        String list = FAMILY + "\\nHow were the Antikythera gears cut, and with what tools?";
        Librarian lib = chat(store, List.of(LibrarianChatTest.tool("library_questions", "{\"text\": \"" + list + "\", \"as\": \"runs\", \"title\": \"my list\"}"), LibrarianChatTest.say("Both are filed.")), new ArrayList<>());
        String reply = lib.say("Send these two questions out as runs");
        assertTrue(reply.endsWith(ASK_LINE), reply);
        List<ObjectNode> jobs = new Jobs(store, j -> "").active();
        assertEquals(2, jobs.size(), jobs.toString());
        ObjectNode family = jobs.stream().filter(j -> j.path("args").path("question").asText().equals(FAMILY)).findFirst().orElseThrow();
        ObjectNode gears = jobs.stream().filter(j -> !j.path("args").path("question").asText().equals(FAMILY)).findFirst().orElseThrow();
        assertEquals(Jobs.OFFERED, family.path("state").asText());
        assertEquals("queued", gears.path("state").asText(), "an ordinary question starts as it is");
        lib.say("yes");
        assertEquals("genealogy", new Jobs(store, j -> "").get(family.path("job_id").asText()).path("args").path("field").asText());
    }

    @Test
    void aFamilyWordInWhatThePersonSaidHoldsOnlyTheRunItIsAbout() throws Exception {
        LibraryStore store = library();
        Librarian lib = chat(store, List.of(LibrarianChatTest.tool("library_research", "{\"question\": \"Where did Tom Hale farm near Leeds?\"}"),
                LibrarianChatTest.tool("library_research", "{\"question\": \"How were the Antikythera gears cut?\"}"), LibrarianChatTest.say("Filed both.")), new ArrayList<>());
        String reply = lib.say("My great-grandfather Tom Hale farmed near Leeds, find out where. Also find out how the Antikythera gears were cut.");
        assertTrue(reply.endsWith(ASK_LINE), "one question, for the one run: " + reply);
        for (ObjectNode j : new Jobs(store, x -> "").active()) {
            boolean family = j.path("args").path("question").asText().contains("Tom Hale");
            assertEquals(family ? Jobs.OFFERED : "queued", j.path("state").asText(), j.toString());
        }
    }

    @Test
    void aFamilyWordHoldsOnlyTheRequestItIsInWhenTwoRequestsShareASentence() throws Exception {
        List<String> said = List.of(
                "Find out who my great-grandmother's parents were and look up how sourdough fermentation works",
                "Find out who my great-grandmother's parents were, then research how sourdough fermentation works",
                "Find out who my great-grandmother's parents were and also how sourdough fermentation works",
                "Find out who my great-grandmother's parents were and how sourdough fermentation works",
                "曽祖母の森田まりの両親を調べて、それからサワードウの発酵の仕組みも調べて");
        for (int i = 0; i < said.size(); i++) {
            LibraryStore store = new LibraryStore(tmp.resolve("lib-" + i)); store.init();
            new LibrarianIndex(store, Embeddings.none()).rebuild();
            boolean ja = i == said.size() - 1;
            String family = ja ? "森田まり（1870年生まれ）の両親は誰か" : "Who were the parents of Ann Hart (1870)?", sourdough = ja ? "サワードウの発酵の仕組みは？" : "How does sourdough fermentation work?";
            Librarian lib = chat(store, List.of(LibrarianChatTest.tool("library_research", "{\"question\": \"" + family + "\"}"),
                    LibrarianChatTest.tool("library_research", "{\"question\": \"" + sourdough + "\"}"), LibrarianChatTest.say("Filed both.")), new ArrayList<>());
            String reply = lib.say(said.get(i));
            assertTrue(reply.endsWith(ASK_LINE), "one question, for the family run alone: " + said.get(i) + " → " + reply);
            for (ObjectNode j : new Jobs(store, x -> "").active()) {
                boolean isFamily = j.path("args").path("question").asText().equals(family);
                assertEquals(isFamily ? Jobs.OFFERED : "queued", j.path("state").asText(), said.get(i) + " → " + j);
            }
        }
    }

    @Test
    void aMessageAfterTheWaitEndedIsANewMessage() throws Exception {
        LibraryStore store = library();
        List<ArrayNode> seen = new ArrayList<>();
        Librarian lib = chat(store, List.of(LibrarianChatTest.tool("library_research", "{\"question\": \"" + FAMILY + "\"}"), LibrarianChatTest.say("Filed."),
                LibrarianChatTest.say("You are welcome.")), seen);
        lib.say("Find out who my great-grandfather's parents were");
        ObjectNode job = filed(store);
        String id = job.path("job_id").asText();
        // nobody answered in time: the service starts it as it was filed
        assertTrue(new Jobs(store, j -> "").expire(job));
        assertFalse(lib.waitingForAnswer(), "an ended wait is over");
        int modelCalls = seen.size();
        String late = lib.say("y");
        assertEquals(modelCalls + 1, seen.size(), "the words go to the model as a message like any other");
        assertTrue(late.startsWith("Research run " + id + " started as ordinary research, because no answer came within " + Librarian.OFFER_MINUTES + " minutes."), late);
        assertTrue(late.endsWith("You are welcome."), late);
        assertEquals(1, new Jobs(store, j -> "").active().size(), "no second run is offered or filed");
        assertFalse(new Jobs(store, j -> "").get(id).path("args").has("field"), "the run stays as it started");
        // a wait whose time is up although the service has not got to it yet ends the same way
        Librarian next = chat(store, List.of(LibrarianChatTest.tool("library_research", "{\"question\": \"" + FAMILY.replace("farm", "live") + "\"}"), LibrarianChatTest.say("Filed."),
                LibrarianChatTest.say("Noted.")), new ArrayList<>());
        next.say("Find out where my great-grandfather's parents lived");
        ObjectNode waiting = filed(store);
        waiting.put("offer_until", "2026-01-01T00:00:00Z");
        Files.writeString(new Jobs(store, j -> "").activeDir().resolve(waiting.path("job_id").asText() + ".json"), waiting.toString());
        assertFalse(next.waitingForAnswer());
        assertTrue(next.say("thanks").startsWith("Research run " + waiting.path("job_id").asText() + " started as ordinary research"));
        assertEquals("queued", new Jobs(store, j -> "").get(waiting.path("job_id").asText()).path("state").asText());
    }

    @Test
    void anAnswerThatCrossesTheEndOfTheWaitOffersASecondRunInGenealogyMode() throws Exception {
        LibraryStore store = library();
        Librarian lib = chat(store, List.of(LibrarianChatTest.tool("library_research", "{\"question\": \"" + FAMILY + "\"}"), LibrarianChatTest.say("Filed.")), new ArrayList<>());
        lib.say("Find out who my great-grandfather's parents were");
        ObjectNode job = filed(store);
        String id = job.path("job_id").asText();
        // the person's yes is on its way while the service ends the wait and starts the run as it was filed
        List<String[]> waiting = lib.session().pending();
        assertTrue(new Jobs(store, j -> "").expire(job));
        String late = lib.answer(waiting, "y").reply();
        assertTrue(late.contains("Research run " + id + " had already started as ordinary research") && late.endsWith("Start a second run of the same question in genealogy mode? (y/N)"), late);
        assertFalse(new Jobs(store, j -> "").get(id).path("args").has("field"), "the first run stays as it started");
        ObjectNode offered = filed(store);
        assertTrue(Jobs.view(offered).path("waiting").asText().contains("it starts only on a yes, and with no answer by"), "the runs page says what happens: " + Jobs.view(offered));
        assertFalse(Jobs.view(offered).path("waiting").asText().contains("ordinary research"), Jobs.view(offered).toString());
        String second = lib.say("yes");
        ObjectNode two = filed(store);
        assertNotEquals(id, two.path("job_id").asText());
        assertEquals("genealogy", two.path("args").path("field").asText(), second);
        assertEquals(FAMILY, two.path("args").path("question").asText());
    }

    @Test
    void leavingWithoutAnAnswerSaysWhatHappensToEachWaitingRun() throws Exception {
        LibraryStore store = library();
        Librarian lib = chat(store, List.of(LibrarianChatTest.tool("library_research", "{\"question\": \"" + FAMILY + "\"}"), LibrarianChatTest.say("Filed.")), new ArrayList<>());
        lib.say("Find out who my great-grandfather's parents were");
        assertEquals("The research the library asked you about starts as ordinary research.", lib.leaveUnanswered());
        assertEquals("queued", filed(store).path("state").asText());
        // the answer crossed the end of the wait, and the second run the chat then offered waits when the person leaves
        LibraryStore other = new LibraryStore(tmp.resolve("lib-again")); other.init();
        new LibrarianIndex(other, Embeddings.none()).rebuild();
        Librarian again = chat(other, List.of(LibrarianChatTest.tool("library_research", "{\"question\": \"" + FAMILY + "\"}"), LibrarianChatTest.say("Filed.")), new ArrayList<>());
        again.say("Find out who my great-grandfather's parents were");
        ObjectNode first = filed(other);
        List<String[]> waiting = again.session().pending();
        assertTrue(new Jobs(other, j -> "").expire(first));
        assertTrue(again.answer(waiting, "y").reply().endsWith("Start a second run of the same question in genealogy mode? (y/N)"));
        assertTrue(again.waitingForAnswer());
        String second = filed(other).path("job_id").asText();
        assertEquals("No second run was started. The run that already started goes on as ordinary research.", again.leaveUnanswered());
        assertEquals("stopped", new Jobs(other, j -> "").get(second).path("state").asText());
    }

    @Test
    void anythingButYesToASecondRunStartsNoSecondRun() throws Exception {
        for (String how : List.of("n", "", "leave", "no answer in time")) {
            LibraryStore store = new LibraryStore(tmp.resolve("lib-" + how.length())); store.init();
            new LibrarianIndex(store, Embeddings.none()).rebuild();
            Librarian lib = chat(store, List.of(LibrarianChatTest.tool("library_research", "{\"question\": \"" + FAMILY + "\"}"), LibrarianChatTest.say("Filed.")), new ArrayList<>());
            lib.say("Find out who my great-grandfather's parents were");
            ObjectNode first = filed(store);
            List<String[]> waiting = lib.session().pending();
            assertTrue(new Jobs(store, j -> "").expire(first));
            assertTrue(lib.answer(waiting, "y").reply().endsWith("Start a second run of the same question in genealogy mode? (y/N)"));
            String second = filed(store).path("job_id").asText();
            String reply = "";
            switch (how) {
                case "leave" -> lib.leaveUnanswered();
                case "no answer in time" -> { assertTrue(new Jobs(store, j -> "").expire(new Jobs(store, j -> "").get(second))); reply = lib.say("y"); }   // a yes that comes too late is a new message
                default -> reply = lib.say(how);
            }
            ObjectNode two = new Jobs(store, j -> "").get(second);
            assertEquals("stopped", two.path("state").asText(), how + ": the second run does not start");
            List<ObjectNode> active = new Jobs(store, j -> "").active();
            assertEquals(1, active.size(), how + ": only the first run is there: " + active);
            assertEquals(first.path("job_id").asText(), active.get(0).path("job_id").asText());
            if (how.equals("no answer in time")) assertTrue(reply.startsWith("No second run was started, because no answer came within " + Librarian.OFFER_MINUTES + " minutes."), reply);
            else if (!how.equals("leave")) assertEquals("No second run was started. The run that already started goes on as ordinary research.", reply, how);
            assertFalse(lib.waitingForAnswer(), how);
        }
    }

    @Test
    void aWaitingRunIsNeverQueuedAndExactlyOneOfTheAnswerAndTheEndOfTheWaitMovesIt() throws Exception {
        LibraryStore store = library();
        ObjectNode a = M.createObjectNode(); a.put("question", FAMILY); a.putObject("patron").put("did", "person");
        String id = new LibraryProtocol(store).research(a, LibraryProtocol.Way.QUIET, new Fields.Suggestion("genealogy", "x"), System.currentTimeMillis() + 60_000).path("job_id").asText();
        AtomicInteger ran = new AtomicInteger();
        Jobs worker = new Jobs(store, j -> { ran.incrementAndGet(); return "done"; });
        worker.pickUp();
        assertEquals(0, worker.queued(), "a run that waits for the person's answer is not queued");
        assertEquals(Jobs.OFFERED, worker.get(id).path("state").asText());
        // the wait not yet over: picking up leaves it; the answer and the end of the wait race, and one of them wins
        for (int round = 0; round < 5; round++) {
            String rid = new LibraryProtocol(store).research(a, LibraryProtocol.Way.QUIET, new Fields.Suggestion("genealogy", "x"), System.currentTimeMillis() - 1).path("job_id").asText();
            Jobs chatSide = new Jobs(store, j -> ""), serviceSide = new Jobs(store, j -> "");
            ObjectNode job = serviceSide.get(rid);
            CountDownLatch go = new CountDownLatch(1);
            boolean[] won = new boolean[2];
            Thread t1 = new Thread(() -> { try { go.await(); won[0] = chatSide.release(rid, "genealogy", "chat-yes"); } catch (Exception e) { throw new RuntimeException(e); } });
            Thread t2 = new Thread(() -> { try { go.await(); won[1] = serviceSide.expire(job); } catch (Exception e) { throw new RuntimeException(e); } });
            t1.start(); t2.start(); go.countDown(); t1.join(); t2.join();
            assertTrue(won[0] ^ won[1], "exactly one moved it");
            ObjectNode now = serviceSide.get(rid);
            assertEquals("queued", now.path("state").asText());
            assertEquals(won[0] ? "genealogy" : "", now.path("args").path("field").asText(""));
        }
        assertEquals(0, ran.get(), "nothing ran in this test");
    }

    // ---- the command line ----

    private String cli(Path home, String... words) throws Exception {
        String realHome = System.getProperty("user.home");
        PrintStream was = System.out;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        System.setProperty("user.home", home.toString());
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        try {
            String[] args = new String[words.length + 1];
            args[0] = "librarian";
            System.arraycopy(words, 0, args, 1, words.length);
            LibrarianCli.run(args, "http://127.0.0.1:1", "m");
        } finally { System.setOut(was); System.setProperty("user.home", realHome); }
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    void theResearchCommandAsksAtATerminalAndTellsAScript() throws Exception {
        Path home = tmp.resolve("home");
        LibraryStore store = new LibraryStore(home.resolve("researchzosho-library")); store.init();
        try {
            // at a terminal, y
            Interaction.OVERRIDE = true; Interaction.INPUT = new BufferedReader(new StringReader("y\n"));
            String said = cli(home, "research", "ask", FAMILY);
            assertTrue(said.contains(ASK_LINE) && said.contains("in genealogy mode"), said);
            assertEquals("genealogy", filed(store).path("args").path("field").asText());
            assertEquals("cli-yes", filed(store).path("args").path("field_how").asText());
            // the same question again at a terminal: the yes stands, and the person is not asked again
            Interaction.INPUT = new BufferedReader(new StringReader("\n"));
            said = cli(home, "research", "ask", FAMILY);
            assertTrue(!said.contains("(y/N)") && said.contains("in genealogy mode"), said);
            assertEquals("genealogy", filed(store).path("args").path("field").asText());
            // in a script nobody answers: it goes as ordinary research, and the person was told already
            Interaction.OVERRIDE = false;
            said = cli(home, "research", "ask", FAMILY);
            assertFalse(said.contains("(y/N)") || said.contains("genealogy"), said);
            assertFalse(filed(store).path("args").has("field"));
            Interaction.OVERRIDE = true;
            // at a terminal, Enter
            Interaction.INPUT = new BufferedReader(new StringReader("\n"));
            said = cli(home, "research", "ask", "Who was my great-grandmother, and where was she born?");
            assertTrue(said.contains(ASK_LINE) && !said.contains("in genealogy mode"), said);
            assertFalse(filed(store).path("args").has("field"), "Enter is no");
            // in a script: nothing is read, the run is ordinary, and the command that asks for genealogy mode is printed ready to paste
            Interaction.OVERRIDE = false; Interaction.INPUT = new BufferedReader(new StringReader("y\n"));
            said = cli(home, "research", "ask", "Where did my grandfather's family come from, and when?");
            assertFalse(said.contains("(y/N)"), said);
            assertFalse(filed(store).path("args").has("field"));
            assertTrue(said.contains("send it again with --genealogy: researchzosho research ask 'Where did my grandfather'\\''s family come from, and when?' --genealogy"), said);
            assertEquals("y", Interaction.INPUT.readLine(), "a script's input is never read");
            // asked for on the command line
            said = cli(home, "research", "ask", "What did Tom Hale do in Leeds?", "--genealogy");
            assertTrue(said.contains("in genealogy mode"), said);
            assertEquals("cli-flag", filed(store).path("args").path("field_how").asText());
            cli(home, "research", "ask", "What is the boiling point of ethanol at altitude?", "--field", "science");
            assertEquals("science", filed(store).path("args").path("field").asText());
        } finally { Interaction.OVERRIDE = null; Interaction.INPUT = null; }
    }

    // ---- the web page ----

    @Test
    void theResearchPageHasAnUntickedBoxAndSaysOnceThatAQuestionLooksLikeFamilyHistory() throws Exception {
        LibraryStore store = library();
        // a drive that answers, so the page gets past its check that a model answers
        HttpServer model = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        model.createContext("/", x -> { byte[] b = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"}}]}".getBytes(StandardCharsets.UTF_8); x.getResponseHeaders().set("Content-Type", "application/json"); x.sendResponseHeaders(200, b.length); x.getResponseBody().write(b); x.close(); });
        model.start();
        LibrarianDaemon d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:" + model.getAddress().getPort(), "local-model", -1);
        try {
            HttpClient c = HttpClient.newHttpClient();
            String base = "http://127.0.0.1:" + d.port();
            String form = PagesTest.get(c, base + "/research?q=" + Pages.enc(FAMILY), null).body();
            assertTrue(form.contains("<input type=\"checkbox\" name=\"field\" value=\"genealogy\"> Family history (genealogy mode): record collections, your family tree and your relatives' facts"), "an unticked box");
            assertTrue(form.contains("This looks like family history.") && form.contains("name=\"told\" value=\"1\""), "the suggestion beside it");
            assertFalse(PagesTest.get(c, base + "/research?q=" + Pages.enc("How were the Antikythera gears cut?"), null).body().contains("This looks like family history."));
            // sent without the box, before the page said anything: the page says it once and files nothing
            String q2 = "Who was my great-grandmother, and where was she born?";
            String first = PagesTest.post(c, base + "/research", "question=" + Pages.enc(q2) + "&mode=depth&sources=shelves&max_minutes=90", null).body();
            assertTrue(first.contains("This looks like family history.") && first.contains("name=\"told\" value=\"1\""), first);
            assertTrue(first.contains("<option value=\"depth\" selected>") && first.contains("<option value=\"shelves\" selected>") && first.contains("name=\"max_minutes\" value=\"90\""), "what the person chose is kept: " + first);
            assertTrue(new Jobs(store, j -> "").active().isEmpty() && new Jobs(store, j -> "").recent(5, null).isEmpty(), "nothing is filed until the person sends it again");
            // sent again: ordinary research
            String sent = PagesTest.post(c, base + "/research", "question=" + Pages.enc(q2) + "&mode=broad&sources=both&told=1", null).body();
            assertTrue(sent.contains("Sent as"), sent);
            assertFalse(job(store, sent).path("args").has("field"));
            // the box ticked: genealogy mode
            String ticked = PagesTest.post(c, base + "/research", "question=" + Pages.enc(FAMILY) + "&mode=broad&sources=both&field=genealogy", null).body();
            assertTrue(ticked.contains("in genealogy mode"), ticked);
            assertEquals("genealogy", job(store, ticked).path("args").path("field").asText());
            assertEquals("web-box", job(store, ticked).path("args").path("field_how").asText());
        } finally { d.stop(); model.stop(0); quiet(store); }
    }

    @Test
    void theQuestionsPageSaysOnTheRunsPageThatARunItSentLooksLikeFamilyHistory() throws Exception {
        LibraryStore store = library();
        store.frontier("person me", FAMILY);
        HttpServer model = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        model.createContext("/", x -> { byte[] b = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"}}]}".getBytes(StandardCharsets.UTF_8); x.getResponseHeaders().set("Content-Type", "application/json"); x.sendResponseHeaders(200, b.length); x.getResponseBody().write(b); x.close(); });
        model.start();
        LibrarianDaemon d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:" + model.getAddress().getPort(), "local-model", -1);
        try {
            HttpClient c = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
            String base = "http://127.0.0.1:" + d.port();
            var sent = PagesTest.post(c, base + "/questions", "op=selected&do=run&q0=" + Pages.enc(FAMILY), null);
            String to = sent.headers().firstValue("Location").orElse("");
            assertTrue(to.startsWith("/jobs?told=J-"), to);
            String runs = PagesTest.get(c, base + to, null).body();
            assertTrue(runs.contains("This looks like family history.") && runs.contains("was sent as ordinary research") && runs.contains("/research?q=" + Pages.enc(FAMILY) + "&field=genealogy"), runs);
            assertFalse(filed(store).path("args").has("field"), "the run itself is ordinary");
        } finally { d.stop(); model.stop(0); quiet(store); }
    }

    @Test
    void aListOfQuestionsSentFromTheCommandLineSaysWhichLooksLikeFamilyHistoryAndTakesGenealogyMode() throws Exception {
        Path home = tmp.resolve("home");
        LibraryStore store = new LibraryStore(home.resolve("researchzosho-library")); store.init();
        Path list = tmp.resolve("list.txt");
        Files.writeString(list, "How were the Antikythera gears cut, and with what tools?\n" + FAMILY + "\n");
        Explain.DRIVES = () -> null;   // no model: the draft's claims are read without one, and nothing is asked of a server
        try {
            Interaction.OVERRIDE = false;
            String said = cli(home, "questions", "file", list.toString(), "--as", "runs");
            assertTrue(said.contains("This looks like family history.") && said.contains("send it again with --genealogy: researchzosho research ask 'Who were my great-grandfather'\\''s parents, and where did they farm?' --genealogy"), said);
            for (ObjectNode j : new Jobs(store, x -> "").active()) assertFalse(j.path("args").has("field"), "sent as ordinary research");
            // asked for on the command line: every run of the list is genealogy's
            for (String[] flags : new String[][]{{"--genealogy"}, {"--field", "genealogy"}}) {
                Files.writeString(list, "Where did the Hales of Leeds live in 1881?\n");
                String[] words = new String[5 + flags.length];
                System.arraycopy(new String[]{"questions", "file", list.toString(), "--as", "runs"}, 0, words, 0, 5);
                System.arraycopy(flags, 0, words, 5, flags.length);
                cli(home, words);
                assertEquals("genealogy", filed(store).path("args").path("field").asText(), String.join(" ", flags));
            }
            // the run that checks a draft's claims, too
            Path draft = tmp.resolve("draft.md");
            Files.writeString(draft, "# Tom Hale\n\nTom Hale was born in Leeds in 1850. He worked as a weaver at the Leeds mill until 1901.\n");
            String checked = cli(home, "check", draft.toString(), "--verify", "--no-citations", "--genealogy");
            assertTrue(checked.contains("checking the claims: J-"), checked);
            assertEquals("genealogy", filed(store).path("args").path("field").asText(), checked);
        } finally { Interaction.OVERRIDE = null; Explain.DRIVES = Explain::configuredDrive; }
    }

    @Test
    void refiningAQuestionKeepsTheGenealogyBoxAsThePersonTickedIt() throws Exception {
        LibraryStore store = library();
        LibrarianDaemon d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1);
        Explain.DRIVES = () -> new Researcher.Drive() {
            @Override public ObjectNode chat(ArrayNode m, ArrayNode tools, int max, String choice) { throw new UnsupportedOperationException(); }
            @Override public int contextWindow() { return 32_000; }
            @Override public String classify(ArrayNode m, int max) {
                return m.get(m.size() - 1).path("content").asText().startsWith("A person typed a research question")
                        ? "{\"question\":\"Where and when was Tom Hart of Leeds born, and who were his parents?\",\"assumptions\":[\"Leeds in England\"],\"sub_questions\":[\"Which parish?\"],\"depth\":\"depth\",\"size\":\"full\"}" : "[]";
            }
        };
        try {
            HttpClient c = HttpClient.newHttpClient();
            String base = "http://127.0.0.1:" + d.port();
            String q = "Tom Hart of Leeds, born around 1850";
            // ticked, then "Refine it first": the page refreshes itself until the refined question is there, as a browser follows it
            String page = PagesTest.post(c, base + "/research", "question=" + Pages.enc(q) + "&field=genealogy&sharpen=1", null).body();
            for (int i = 0; i < 150 && !page.contains("<h2>Refined</h2>"); i++) {
                Matcher next = Pattern.compile("content=\"\\d+;url=([^\"]+)\"").matcher(page);
                assertTrue(next.find(), page);
                Thread.sleep(100);
                page = PagesTest.get(c, base + next.group(1).replace("&amp;", "&"), null).body();
            }
            assertTrue(page.contains("<h2>Refined</h2>"), page);
            assertTrue(page.contains("<input type=\"checkbox\" name=\"field\" value=\"genealogy\" checked>"), "the box is still ticked: " + page);
        } finally { d.stop(); Explain.DRIVES = Explain::configuredDrive; }
    }

    @Test
    void theWebChatAnswersWithTwoButtons() throws Exception {
        LibraryStore store = library();
        Librarian lib = chat(store, List.of(LibrarianChatTest.tool("library_research", "{\"question\": \"" + FAMILY + "\"}"), LibrarianChatTest.say("Filed.")), new ArrayList<>());
        lib.say("Find out who my great-grandfather's parents were");
        String page = Pages.chat(store, Patrons.Patron.PERSON, Map.of("session", lib.session().id));
        assertTrue(page.contains("name=\"say\" value=\"y\"><button>Yes</button>") && page.contains("name=\"say\" value=\"n\"><button class=\"quiet\">No</button>"), page);
        lib.say("n");
        assertFalse(Pages.chat(store, Patrons.Patron.PERSON, Map.of("session", lib.session().id)).contains("<button>Yes</button>"));
    }

    /** The job a "Sent as J-…" page names, wherever the service has moved it since. */
    static ObjectNode job(LibraryStore store, String page) throws Exception {
        Matcher m = Pattern.compile("Sent as <a href=\"/jobs/(J-\\d+)").matcher(page);
        assertTrue(m.find(), page);
        return new Jobs(store, j -> "").get(m.group(1));
    }

    /** Waits until no run the daemon started is still writing into the library, so the temporary folder can be deleted after the test. */
    static void quiet(LibraryStore store) throws Exception {
        for (int i = 0; i < 200 && !new Jobs(store, j -> "").active().isEmpty(); i++) Thread.sleep(100);
    }
}
