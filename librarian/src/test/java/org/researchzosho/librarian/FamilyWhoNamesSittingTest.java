package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.librarian.profiles.GenealogyProfile;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The who-is-who sitting asks the questions about names and families first, then the web questions; library_who shows a question about
 * names with its code and its options (kind=names), takes the answer by code and choice, and counts them in op=list, while its old ops
 * answer as they always did.
 */
class FamilyWhoNamesSittingTest {

    @AfterEach void restore() { Interaction.OVERRIDE = null; Interaction.INPUT = null; }

    static final List<FamilyIdentity.Page> WEB = List.of(new FamilyIdentity.Page("https://books.example/kenji", "Kenji Morita <author>", "A book on shops. Born 1905."),
            new FamilyIdentity.Page("https://films.example/name/2", "Kenji Morita | Actor", "An actor."));

    static LibraryStore family(Path tmp) throws Exception {
        LibraryStore store = FamilyNameQuestionsTest.store(tmp);
        FamilyNameQuestionsTest.file(store, "file:///family/book.txt", List.of(FamilyNameQuestionsTest.fact("森田健二", "born-on", "1905", "", "健二 1905年生")),
                List.of(FamilyNameQuestionsTest.name("森田健二", "遠藤健二", "遠藤", "健二", "birth", "1905", "健二は1905年に遠藤家に生まれた。")));
        FamilyIdentity.find(store, FamilyPeople.view(store), "森田健二", q -> WEB, p -> "rows 1: an author of a book on shops\nrows 2: an actor");
        return store;
    }

    static String who(LibraryStore store, String typed, String... words) throws Exception {
        Interaction.OVERRIDE = true;
        Interaction.INPUT = new BufferedReader(new StringReader(typed));
        String[] args = new String[words.length + 3];
        args[0] = "researchzosho"; args[1] = "genealogy"; args[2] = "who";
        System.arraycopy(words, 0, args, 3, words.length);
        PrintStream was = System.out;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        try { new GenealogyProfile().cli(store, args); } finally { System.setOut(was); }
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    void theSittingAsksTheNamesFirstThenWhoIsWhoOnTheWeb(@TempDir Path tmp) throws Exception {
        LibraryStore store = family(tmp);
        String out = who(store, "2\n\nstop\n");
        int names = out.indexOf("HOW DID THE NAME CHANGE?"), web = out.indexOf("WHO IS 森田健二 (BORN 遠藤) ON THE WEB?");
        assertTrue(names >= 0 && web > names, "the names first, then the web, under the heading: " + out);
        assertTrue(out.contains("Question 1 of 1 about names and families") && out.contains("Type later   to put it off"), out);
        assertTrue(out.contains("Saved as your answer: 森田健二 was named 森田健二 on entering the family as 婿養子"), "the second answer, 婿養子: " + out);
        assertTrue(out.contains("WHEN DID THE NAME CHANGE?") && out.contains("Skipped for now. It stays open"), "the question the answer raised came next, and Enter skipped it: " + out);
        assertTrue(out.contains("You answered 1 question about names and families."), out);
        assertTrue(out.contains("Stopped."), "stop ends the web sitting: " + out);
        assertEquals(List.of("name-change-when"), FamilyNameQuestions.open(store).stream().map(FamilyNameQuestions.Question::kind).toList());

        String listed = who(store, "", "--list");
        assertTrue(listed.startsWith("Questions about names and families that only your family can answer (1):") && listed.contains("[code ") && listed.contains("森田健二 (born 遠藤): WAITING FOR YOUR ANSWER"), listed);
        String answered = who(store, "", "--answered");
        assertTrue(answered.contains("Your answers to questions about names and families, each with the command that takes it back:") && answered.contains("researchzosho dispute F-"), answered);
        String code = FamilyNameQuestions.asked(store).keySet().iterator().next();
        assertTrue(who(store, "", "--reopen", code).startsWith("The question " + code + " is open again."));
    }

    @Test
    void libraryWhoShowsANamesQuestionTakesTheAnswerByCodeAndItsOldOpsAreAsTheyWere(@TempDir Path tmp) throws Exception {
        LibraryStore store = family(tmp);
        LibraryProtocol p = new LibraryProtocol(store);
        p.whoSearch = () -> (FamilyIdentity.Search) q -> WEB; p.whoModel = () -> (Function<String, String>) x -> "rows 1: an author\nrows 2: an actor";
        ObjectMapper m = new ObjectMapper();
        Function<String, ObjectNode> call = json -> { try { ObjectNode a = (ObjectNode) m.readTree(json); a.putObject("patron").put("did", "person").put("name", "me").put("runtime", "cli"); return p.who(a); } catch (Exception e) { throw new IllegalStateException(e.getMessage(), e); } };

        ObjectNode list = call.apply("{\"op\":\"list\"}");
        assertEquals(1, list.path("waiting").asInt(), "the web question, as before");
        assertEquals(1, list.path("names_waiting").asInt());
        assertEquals("森田健二", list.path("people").get(0).path("person").asText());
        assertEquals("森田健二", call.apply("{\"op\":\"show\"}").path("question").path("person").asText(), "without kind=names, show is the web question it always was");

        ObjectNode shown = call.apply("{\"op\":\"show\",\"kind\":\"names\"}");
        assertEquals("names", shown.path("question").path("kind").asText());
        assertEquals("name-change-how", shown.path("question").path("question_kind").asText());
        String code = shown.path("question").path("code").asText();
        assertEquals(6, code.length());
        assertTrue(shown.path("question").path("options").get(0).has("does") && shown.path("question").path("options").get(0).has("says"), shown.toString());
        assertEquals("later", shown.path("question").path("options").get(shown.path("question").path("options").size() - 1).path("key").asText());
        assertEquals(shown.path("question").path("code").asText(), call.apply("{\"op\":\"show\",\"kind\":\"names\",\"person\":\"森田健二\"}").path("question").path("code").asText(), "about one person");

        ObjectNode said = call.apply("{\"op\":\"answer\",\"code\":\"" + code + "\",\"choice\":\"mukoyoshi\"}");
        assertTrue(said.path("summary").asText().startsWith("Saved as your answer: 森田健二 was named 森田健二 on entering the family as 婿養子"), said.toString());
        Finding named = store.scanFindings().findings().stream().filter(f -> f.triple() != null && f.triple().predicate().equals("has-name") && f.sources().get(0).locator().equals(FamilyNameQuestions.SOURCE + code)).findFirst().orElseThrow();
        assertTrue(named.sources().get(0).edition().equals("as told by me"), named.sources().toString());
        assertEquals("name-change-when", call.apply("{\"op\":\"show\",\"kind\":\"names\"}").path("question").path("question_kind").asText(), "the next that waits");
        assertThrows(IllegalStateException.class, () -> call.apply("{\"op\":\"answer\",\"code\":\"" + code + "\",\"choice\":\"mukoyoshi\"}"), "answered: it waits no more");
        assertTrue(Files.exists(FamilyNameQuestions.askedFile(store)));
    }
}
