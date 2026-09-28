package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Whom the questions a family research sends to search services name: the facts it asks a record for, a family file's research notes
 * and a record question from the decisions page name the living, a child among them, unless the owner gives --skip-living. A GEDCOM
 * file made to be passed on names everybody.
 */
class FamilyQuestionsLeadsTest {

    private static LibraryStore store(Path tmp) throws Exception { LibraryStore s = new LibraryStore(tmp.resolve("lib")); s.init(); return s; }

    private static FamilyAccount.Fact fact(String s, String r, String o, String date) { return new FamilyAccount.Fact(s, r, o, date, s + " " + r + " " + o); }

    private static void read(LibraryStore store, FamilyAccount.Fact... facts) throws Exception {
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(facts), List.of()), "file:///family/tree.txt", "an aunt");
    }

    private static FamilyQuestions.Ask ask(List<FamilyQuestions.Ask> asks, String person) {
        return asks.stream().filter(a -> a.person().equals(person)).findFirst().orElseThrow(() -> new AssertionError(person + " is not asked about: " + asks));
    }

    @Test
    void aFactToConfirmNamesASpouseWhoMayBeLivingOnlyWhenTheLivingAreAskedFor(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        read(store, fact("Tom Ellis", "born-on", "1880", ""), fact("Tom Ellis", "died-on", "1950", ""), fact("Tom Ellis", "child-of", "Ann Hale", ""),
                fact("Ann Hale", "born-on", "1855", ""), fact("Ann Hale", "died-on", "1920", ""),
                fact("Joe Ellis", "child-of", "Tom Ellis", ""), fact("Joe Ellis", "born-on", "1930", ""), fact("Joe Ellis", "died-on", "2010", ""),
                fact("Joe Ellis", "married-to", "Kimie Ellis", ""), fact("Kimie Ellis", "born-on", "1950", ""));
        for (boolean living : new boolean[]{true, false}) {
            List<FamilyQuestions.Ask> asks = FamilyQuestions.around(store, "Tom Ellis", 2, 2, living);
            assertTrue(ask(asks, "Tom Ellis").question().contains("child of Ann Hale"), "his mother, who died in 1920, is named, living=" + living + ": " + ask(asks, "Tom Ellis").question());
            assertTrue(ask(asks, "Tom Ellis").question().contains("born 1880"), "the facts about Tom himself are asked for");
        }
        assertFalse(ask(FamilyQuestions.around(store, "Tom Ellis", 2, 2, false), "Joe Ellis").question().contains("Kimie Ellis"), "without the living, his widow is not named");
        assertTrue(ask(FamilyQuestions.around(store, "Tom Ellis", 2, 2, true), "Joe Ellis").question().contains("married Kimie Ellis"), "the owner asked for the living: she is named");
    }

    @Test
    void aResearchNoteBecomesAnOpenQuestionWhoeverItNames(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        Path ged = tmp.resolve("tree.ged");
        Files.writeString(ged, "0 HEAD\n1 CHAR UTF-8\n"
                + "0 @I1@ INDI\n1 NAME Tom /Ellis/\n1 BIRT\n2 DATE 1880\n1 DEAT\n2 DATE 1940\n1 _TODO Ask cousin Kimie Hart about the family bible\n1 _TODO Find the ship he came on\n"
                + "0 @I2@ INDI\n1 NAME Kimie /Hart/\n1 BIRT\n2 DATE 1990\n"
                + "0 TRLR\n", StandardCharsets.UTF_8);
        Gedcom.Outcome o = Gedcom.importFile(store, ged);
        assertTrue(Frontier.read(store).stream().anyMatch(l -> l.text().equals("Tom Ellis: Ask cousin Kimie Hart about the family bible")), "a note that names a living cousin is a question too");
        assertTrue(Frontier.read(store).stream().anyMatch(l -> l.text().equals("Tom Ellis: Find the ship he came on")));
        assertTrue(o.problems().stream().anyMatch(p -> p.startsWith("2 research notes")), o.problems().toString());
    }

    @Test
    void aRecordQuestionFromTheDecisionsPageFollowsSkipLivingLikeEveryOtherQuestion(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        read(store, fact("Genzaburo Hale", "born-on", "1975", ""), fact("Kimie Hale", "child-of", "Genzaburo Hale", ""), fact("Kimie Hale", "born-on", "2014", ""),
                fact("K. Hale", "child-of", "Genzaburo Hale", ""), fact("Tom Hale", "died-on", "1920", ""), fact("Tom Hales", "lived-in", "Leeds", ""));
        for (Set<String> two : List.of(Set.of("Kimie Hale", "K. Hale"), Set.of("Tom Hale", "Tom Hales"))) {
            FamilyDecisions.Pair pair = FamilyDecisions.pairs(store).stream().filter(p -> Set.of(p.fold(), p.into()).equals(two)).findFirst().orElseThrow(() -> new AssertionError(two.toString()));
            String done = URLDecoder.decode(DecisionsPage.post(store, Patrons.Patron.PERSON, Map.of("kind", "pair", "code", pair.code(), "do", "unsure")), StandardCharsets.UTF_8);
            assertTrue(done.contains("The next time you give the command researchzosho genealogy research") && done.contains("--skip-living"), "the answer says the research asks it, and what leaves it out: " + done);
        }
        // with the living, the research asks each record question, a child's among them; with --skip-living it names nobody who may be living
        String kimie = String.join(" ", ask(FamilyQuestions.around(store, "Genzaburo Hale", 2, 2, true), "Kimie Hale").questions());
        assertTrue(kimie.contains("Which record shows whether"), kimie);
        assertTrue(ask(FamilyQuestions.around(store, "Tom Hale", 2, 2, true), "Tom Hale").questions().get(0).startsWith("Which record shows whether"));
        for (FamilyQuestions.Ask a : FamilyQuestions.around(store, "Genzaburo Hale", 2, 2, false)) assertFalse(a.question().contains("Kimie Hale"), a.question());
        assertFalse(ask(FamilyQuestions.around(store, "Tom Hale", 2, 2, false), "Tom Hale").question().contains("Tom Hales"));
        String page = DecisionsPage.body(store, Patrons.Patron.PERSON, "");
        assertFalse(page.contains("each can be taken back"), "the page says what each answer does, and how to change it");
        // once his dates place him long ago, the record is asked for first with --skip-living too
        read(store, fact("Tom Hales", "died-on", "1919", ""));
        FamilyQuestions.Ask tom = ask(FamilyQuestions.around(store, "Tom Hale", 2, 2, false), "Tom Hale");
        assertTrue(tom.questions().get(0).startsWith("Which record shows whether"), tom.questions().toString());
    }

    @Test
    void aGedcomFileNamesTheTellerOfAnAccountInItsSources(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        FamilyAccount.Read read = new FamilyAccount.Read(List.of(), List.of(fact("Tom Ellis", "born-in", "Leeds", "1901"), fact("Tom Ellis", "died-on", "1971", ""),
                fact("Tom Ellis", "parent-of", "Kimie Hart", ""), fact("Kimie Hart", "born-on", "1958", "")), List.of());
        FamilyAccount.file(store, read, "Kimie Hart (your note: Mum, 17 Mill Lane, told me this over tea)", f -> List.of("file:///home/me/letters.txt"), f -> List.of());
        String out = Gedcom.export(store, "Tom Ellis");
        assertTrue(out.contains("1 NAME Tom /Ellis/") && out.contains("1 NAME Kimie /Hart/"), out);
        assertTrue(out.contains("3 PAGE letters.txt, as told by Kimie Hart"), "the source says who told it: " + out);
    }
}
