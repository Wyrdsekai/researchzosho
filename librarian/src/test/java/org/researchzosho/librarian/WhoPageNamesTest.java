package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The page "Who is who" asks a question about names and families first, each answer a button with what it does; the answer posted is the
 * family's word, the page says what was done and goes on to the next. The Decisions page, the home page and the inbox count them.
 */
class WhoPageNamesTest {

    @Test
    void thePageAsksTheNamesFirstTakesTheAnswerAndSaysWhatItDid(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyWhoNamesSittingTest.family(tmp);
        Patrons.Patron me = Patrons.Patron.PERSON;
        String page = WhoPage.body(store, me, "", "");
        FamilyNameQuestions.Question q = FamilyNameQuestions.open(store).get(0);
        assertTrue(page.contains("<h2>How did the name change?</h2>") && page.contains("Question 1 of 1 about names and families."), page);
        assertTrue(page.contains("<input type=\"hidden\" name=\"kind\" value=\"names\"><input type=\"hidden\" name=\"code\" value=\"" + q.code() + "\">"), page);
        for (FamilyNameQuestions.Option o : q.options()) assertTrue(page.contains("<button name=\"choice\" value=\"" + o.key() + "\">") && page.contains(Pages.esc(o.does())), "every answer says what it does: " + o);
        assertTrue(page.contains("<h2>Everyone the library searched for on the web</h2>") && page.contains(">森田健二 (born 遠藤)</a>") && page.contains("/who?person=%E6%A3%AE%E7%94%B0%E5%81%A5%E4%BA%8C"),
                "the web list is still there, each person under their heading and linked by the name the library writes: " + page);
        // the person's own web question, asked for by name, is the web question as it always was
        assertTrue(WhoPage.body(store, me, "森田健二", "").contains("<h2>森田健二 (born 遠藤)</h2>"));

        Profile.Page posted = FamilyPages.page(store, me, "/who", "POST", Map.of(), Map.of("kind", "names", "code", q.code(), "choice", "marriage"));
        String to = posted.redirect();
        assertTrue(to.startsWith("/who?show="), to);
        String done = URLDecoder.decode(to.substring("/who?show=".length()), StandardCharsets.UTF_8);
        assertTrue(done.startsWith("done:Saved as your answer: 森田健二 was named 森田健二 at marriage"), done);
        String next = WhoPage.body(store, me, "", done);
        assertTrue(next.contains("<strong>Saved as your answer:") && next.contains("<h2>When did the name change?</h2>") && next.contains("<input name=\"year\""), "the answer said, and the next question, which takes a year: " + next);

        FamilyNameQuestions.Question when = FamilyNameQuestions.open(store).get(0);
        FamilyPages.page(store, me, "/who", "POST", Map.of(), Map.of("kind", "names", "code", when.code(), "choice", "year", "year", "1932"));
        assertEquals("森田健二", FamilyNameHistory.of(FamilyPeople.view(store)).at(FamilyPeople.view(store).nodeIdOf("森田健二"), 1933).written());
        assertThrows(ProtocolError.class, () -> FamilyPages.page(store, me, "/who", "POST", Map.of(), Map.of("kind", "names", "code", when.code(), "choice", "year", "year", "1932")), "answered: it waits no more");
    }

    @Test
    void theDecisionsPageTheHomePageAndTheInboxCountTheQuestionsAboutNames(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyWhoNamesSittingTest.family(tmp);
        String decide = DecisionsPage.body(store, Patrons.Patron.PERSON, "");
        assertTrue(decide.contains("<h2>Names and families</h2><p><a href=\"/who\">1 question about names and families waits</a> for your answer: how a name changed (1)."), decide);
        assertTrue(FamilyPages.homeSection(store, true).contains("1 question about names and families waits</strong></a> for you too: how a name changed (1)."), FamilyPages.homeSection(store, true));
        assertTrue(FamilyPages.inboxNote(store, true).contains("1 question about names and families waits for you:</strong> how a name changed (1)."), FamilyPages.inboxNote(store, true));
        assertFalse(FamilyPages.inboxNote(store, false).contains("names and families"), "somebody who may only read is not told");
    }
}
