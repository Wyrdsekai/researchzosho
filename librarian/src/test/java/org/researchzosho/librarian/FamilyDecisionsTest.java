package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** One page for what only the family can settle: each answer says what it will do, and "I cannot tell" asks the research for a record. */
class FamilyDecisionsTest {

    private static FamilyAccount.Fact fact(String s, String r, String o) { return new FamilyAccount.Fact(s, r, o, "", "q"); }

    @Test
    void oneNameInLatinLettersAndInCharactersGoesIntoTheEntryInCharactersAsTheQuestionsJoinIt(@TempDir Path tmp) throws Exception {
        // the Decisions page and the checks join a pair the way the question about it does: the characters say which family
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(fact("Morita Kenji", "born-on", "1905"), fact("Morita Kenji", "lived-in", "Hiroshima"),
                fact("森田健二", "occupation", "silk merchant")), List.of()), "file:///aunt.txt", "an aunt");
        Graph g = FamilyPeople.view(store);
        assertTrue(FamilyDecisions.foldsInto(g, store.scanFindings().findings(), "Morita Kenji", "森田健二"), "the entry in Latin letters goes into the one in characters, birth and all");
        assertFalse(FamilyDecisions.foldsInto(g, store.scanFindings().findings(), "森田健二", "Morita Kenji"));
    }

    @Test
    void theNamesThenTheDisagreeingClaimsThenWhoIsWhoAndEachAnswerSaysWhatItDoes(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(
                fact("Genzaburo Takahashi", "child-of", "Isamu Takahashi"), fact("Genzaburo Takahashi", "born-on", "1872"),
                fact("Genzaburo Takahasi", "child-of", "Isamu Takahashi"), fact("Genzaburo Takahasi", "born-on", "1872"),
                fact("Tom Ellis", "born-in", "Glasgow"), fact("Tom Elis", "lived-in", "Leeds"),
                fact("Ann Hale", "born-on", "1850"), fact("Ann Hale", "died-on", "1920")), List.of()), "file:///aunt.txt", "an aunt");
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(fact("Ann Hale", "born-on", "1855")), List.of()), "https://tree.example/ann", "a tree site");
        FamilyIdentity.find(store, Graph.build(store), "Ann Hale", q -> List.of(new FamilyIdentity.Page("https://example.org/a", "Ann Hale", "a teacher"), new FamilyIdentity.Page("https://example.org/b", "Ann Hale", "a singer")), null);

        String page = DecisionsPage.body(store, Patrons.Patron.PERSON, "");
        assertTrue(page.indexOf("One person or two?") < page.indexOf("Two sources that disagree") && page.indexOf("Two sources that disagree") < page.indexOf("Who is who on the web"), page);
        FamilyDecisions.Pair gen = FamilyDecisions.pairs(store).stream().filter(p -> p.into().startsWith("Genzaburo")).findFirst().orElseThrow();
        assertEquals("Genzaburo Takahasi", gen.fold());
        assertEquals(2, gen.moving().size());
        assertTrue(page.contains("One person</button> joins “Genzaburo Takahasi” into “Genzaburo Takahashi”. The 2 claims about “Genzaburo Takahasi” (" + gen.moving().get(0)) && page.contains("are then about “Genzaburo Takahashi”")
                && page.contains("The reason kept with it: both are children of Isamu Takahashi; both born in 1872."), "the effect is said before the answer: " + page);
        assertTrue(page.contains("Keep 1850</button> accepts") && page.contains("as disputed, with your choice as the reason. Both stay on record."), page);
        assertTrue(page.contains("/who?person=Ann+Hale"), page);

        // I cannot tell: a record question, asked in the research for both, and the pair still waits
        String to = DecisionsPage.post(store, Patrons.Patron.PERSON, Map.of("kind", "pair", "code", gen.code(), "do", "unsure"));
        assertTrue(URLDecoder.decode(to, StandardCharsets.UTF_8).contains("asks for a record that tells them apart"), to);
        String asked = FamilyDecisions.recordQuestion(gen);
        assertTrue(Frontier.read(store).stream().anyMatch(l -> l.text().equals(asked) && l.parked() && !l.researchable()), "it waits for the person's research, not as a run of its own");
        FamilyQuestions.Ask ask = FamilyQuestions.around(store, "Genzaburo Takahasi", 2, 2).stream().filter(a -> a.person().equals("Genzaburo Takahasi")).findFirst().orElseThrow();
        assertEquals(asked, ask.questions().get(0), "the research for the person asks for the record first: " + ask.questions());
        assertEquals(1, FamilyDecisions.pairs(store).stream().filter(p -> p.code().equals(gen.code())).count());
        DecisionsPage.post(store, Patrons.Patron.PERSON, Map.of("kind", "pair", "code", gen.code(), "do", "unsure"));
        assertEquals(1, Frontier.read(store).stream().filter(l -> l.text().equals(asked)).count(), "asked once");

        // one person: the merge runs with the reason, and the record question is answered
        DecisionsPage.post(store, Patrons.Patron.PERSON, Map.of("kind", "pair", "code", gen.code(), "do", "one"));
        Graph g = Graph.build(store);
        assertEquals(g.nodeIdOf("Genzaburo Takahashi"), g.nodeIdOf("Genzaburo Takahasi"));
        assertTrue(Frontier.read(store).stream().noneMatch(l -> l.text().equals(asked) && l.open()));
        assertTrue(URLDecoder.decode(DecisionsPage.post(store, Patrons.Patron.PERSON, Map.of("kind", "pair", "code", gen.code(), "do", "one")), StandardCharsets.UTF_8).contains("not waiting any more"), "a second press does nothing");

        // two people
        FamilyDecisions.Pair tom = FamilyDecisions.pairs(store).stream().filter(p -> p.into().startsWith("Tom")).findFirst().orElseThrow();
        DecisionsPage.post(store, Patrons.Patron.PERSON, Map.of("kind", "pair", "code", tom.code(), "do", "two"));
        assertTrue(FamilyDecisions.pairs(store).isEmpty(), FamilyDecisions.pairs(store).toString());

        // keep one of two claims: accepted, the other disputed, both on record
        FamilyDecisions.Clash c = FamilyDecisions.clashes(store).get(0);
        String keep = c.one().triple().object().equals("1850") ? "keep1" : "keep2";
        DecisionsPage.post(store, Patrons.Patron.PERSON, Map.of("kind", "clash", "one", c.one().id(), "other", c.other().id(), "do", keep));
        Finding kept = store.finding(keep.equals("keep1") ? c.one().id() : c.other().id()), other = store.finding(keep.equals("keep1") ? c.other().id() : c.one().id());
        assertEquals(Finding.State.accepted, kept.state());
        assertEquals(Finding.State.disputed, other.state());
        assertTrue(other.body().contains("the family kept " + kept.id()), other.body());
        assertTrue(FamilyDecisions.clashes(store).isEmpty());
        assertTrue(DecisionsPage.body(store, Patrons.Patron.PERSON, "").contains("Who is who on the web"), "the web questions are still there");
    }

    @Test
    void aRecordQuestionThatNamesSomebodyWhoMayBeLivingStaysWithTheFamily(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(fact("Tom Hale", "died-on", "1920"), fact("Tom Hales", "lived-in", "Leeds")), List.of()), "file:///aunt.txt", "an aunt");
        Graph g = Graph.build(store);
        assertTrue(g.node(g.nodeIdOf("Tom Hales")).mayBeLiving(), "nothing dates Tom Hales, so he may be living");
        FamilyDecisions.Pair pair = FamilyDecisions.pairs(store).stream().filter(p -> Set.of(p.fold(), p.into()).equals(Set.of("Tom Hale", "Tom Hales"))).findFirst().orElseThrow();
        String asked = FamilyDecisions.cannotTell(store, FamilyDecisions.recordQuestion(pair), "person");
        FamilyQuestions.Ask tom = FamilyQuestions.around(store, "Tom Hale", 2, 2).stream().filter(a -> a.person().equals("Tom Hale")).findFirst().orElseThrow();
        assertFalse(tom.questions().contains(asked) || tom.question().contains("Tom Hales"), "the question names somebody who may be living, so it is not sent to a search service: " + tom.question());
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(fact("Tom Hales", "died-on", "1919")), List.of()), "file:///register.txt", "a register");
        tom = FamilyQuestions.around(store, "Tom Hale", 2, 2).stream().filter(a -> a.person().equals("Tom Hale")).findFirst().orElseThrow();
        assertEquals(asked, tom.questions().get(0), "once his dates place him long ago, the record is asked for first");
    }

    @Test
    void aReaderIsNotOfferedTheFamilysPagesAndIsToldWhyInASentence(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(new FamilyAccount.Fact("Tom Hale", "born-on", "1850", "", "q"), new FamilyAccount.Fact("Tom Hale", "parent-of", "Ann Hale", "", "q")), List.of()), "file:///family/notes.txt", "an aunt");
        Patrons.setDefault(store, Patrons.Level.read);
        Patrons.set(store, "did:key:cousin", "a cousin", Patrons.Level.read);
        Patrons.Patron cousin = new Patrons.Patron("did:key:cousin", "a cousin", "web");
        String home = Pages.page(store, cousin, "Home", "", 0, null);
        assertTrue(home.contains("href=\"/tree\""), home);
        assertFalse(home.contains("href=\"/decide\"") || home.contains("href=\"/who\""), "a reader is not offered the pages that take the family's word");
        assertTrue(Pages.page(store, Patrons.Patron.PERSON, "Home", "", 0, null).contains("href=\"/decide\""));
        ProtocolError e = assertThrows(ProtocolError.class, () -> DecisionsPage.body(store, cousin, ""));
        assertTrue(e.getMessage().startsWith("This page is for the people who may change the library") && !e.getMessage().contains("patrons.md"), e.getMessage());
        ProtocolError w = assertThrows(ProtocolError.class, () -> WhoPage.body(store, cousin, "", ""));
        assertEquals(e.getMessage(), w.getMessage());
    }

    @Test
    void aClashNamesEachSourceByItsFileAndThePlacesReadAsSentences(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        String folder = "file:///home/someone/Documents/Family%20History/trees/hale-family-papers/";
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(new FamilyAccount.Fact("Tom Hale", "born-in", "York", "1850", "q")), List.of()), folder + "aunt.ged", "a tree");
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(new FamilyAccount.Fact("Tom Hale", "born-in", "Leeds", "1850", "q")), List.of()), folder + "cousin.ged", "a tree");
        String page = DecisionsPage.body(store, Patrons.Patron.PERSON, "");
        assertTrue(page.contains("from aunt.ged") && page.contains("from cousin.ged"), page);
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(new FamilyAccount.Fact("Ann Hale", "died-in", "York", "", "q"), new FamilyAccount.Fact("Ann Hales", "died-in", "Leeds", "", "q")), List.of()), "file:///family/notes.txt", "an aunt");
        String said = FamilyChecks.check(store).stream().filter(p -> p.text().contains("Ann Hale")).findFirst().orElseThrow().text();
        assertTrue(said.contains("Ann Hale died in York and Ann Hales in Leeds") || said.contains("Ann Hales died in Leeds and Ann Hale in York"), said);
        assertFalse(said.contains("was died"), said);
    }
}
