package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.researchzosho.librarian.profiles.GenealogyProfile;
import org.researchzosho.drive.Declined;
import java.util.LinkedHashMap;
import java.util.Map;
/** A tree asks its own research questions: from what is known, about the dead only, the least known first, each person once. */
class FamilyQuestionsTest {

    private static FamilyAccount.Fact fact(String s, String r, String o, String date) { return new FamilyAccount.Fact(s, r, o, date, "q"); }

    private static LibraryStore family(Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        FamilyAccount.file(store, new FamilyAccount.Read(List.of(), List.of(
                fact("髙橋源三郎", "born-on", "明治5年", ""), fact("髙橋源三郎", "born-in", "広島", ""), fact("髙橋源三郎", "died-on", "昭和20年", ""),
                fact("髙橋源三郎", "married-to", "髙橋ハル", ""), fact("髙橋ハル", "died-on", "昭和30年", ""),
                fact("髙橋正一", "child-of", "髙橋源三郎", ""), fact("髙橋正一", "child-of", "髙橋ハル", ""), fact("髙橋正一", "born-on", "明治41年", ""), fact("髙橋正一", "died-on", "1990", ""),
                fact("髙橋まり", "child-of", "髙橋正一", ""), fact("髙橋まり", "born-on", "1975", "")), List.of()), "file:///notes.txt", "an aunt");
        return store;
    }

    @Test
    void aQuestionPerDeadPersonWrittenFromTheClaims(@TempDir Path tmp) throws Exception {
        List<FamilyQuestions.Ask> asks = FamilyQuestions.around(family(tmp), "髙橋まり", 6, 3);
        String all = String.join("\n", asks.stream().map(FamilyQuestions.Ask::question).toList());
        assertEquals(3, asks.size(), all);
        assertFalse(all.contains("髙橋まり"), "a living person is not asked about, and not named in a parent's question: " + all);
        assertTrue(all.contains("髙橋源三郎 (also written 高橋源三郎; born 1872 in 広島; died 1945; married 髙橋ハル):"), all);
        assertTrue(all.contains("髙橋正一 (also written 高橋正一; born 1908; died 1990; child of 髙橋源三郎 and 髙橋ハル):"), all);
        assertEquals("髙橋ハル", asks.get(0).person(), "the person the library knows least about comes first: " + all);
    }

    @Test
    void theEdgeOfTheTreeIsAskedToGrow(@TempDir Path tmp) throws Exception {
        List<FamilyQuestions.Ask> asks = FamilyQuestions.around(family(tmp), "髙橋まり", 6, 3);
        String top = asks.stream().filter(a -> a.person().equals("髙橋源三郎")).findFirst().orElseThrow().question();
        String mid = asks.stream().filter(a -> a.person().equals("髙橋正一")).findFirst().orElseThrow().question();
        assertTrue(top.contains("1. Who were the parents of 髙橋源三郎?"), top);
        assertFalse(mid.contains("Who were the parents") || mid.contains("Who were the children"), "his parents and a child are in the tree: " + mid);
        // the concise questions, one per hole, are carried apart from the text and go to the run as its sub-questions
        FamilyQuestions.Ask topAsk = asks.stream().filter(a -> a.person().equals("髙橋源三郎")).findFirst().orElseThrow();
        assertEquals("Who were the parents of 髙橋源三郎?", topAsk.questions().get(0));
        assertTrue(topAsk.questions().stream().anyMatch(q -> q.startsWith("Which of these facts about 髙橋源三郎, known so far only from a family account or somebody's tree, does a record confirm: born 明治5年")), "his dates come from an aunt, a clue: " + topAsk.questions());
        assertTrue(topAsk.questions().stream().anyMatch(q -> q.startsWith("What did 髙橋源三郎 do for a living")), topAsk.questions().toString());
        assertFalse(topAsk.questions().stream().anyMatch(q -> q.startsWith("When and where was 髙橋源三郎 born")), "his birth is known: " + topAsk.questions());
        assertTrue(topAsk.questions().size() <= 8);
        String capped = FamilyQuestions.around(family(tmp.resolve("b")), "髙橋まり", 2, 3).stream().filter(a -> a.person().equals("髙橋源三郎")).findFirst().orElseThrow().question();
        assertFalse(capped.contains("parents?"), "two generations up was all that was asked for: " + capped);
    }

    @Test
    void aPersonIsAskedAboutOnce(@TempDir Path tmp) throws Exception {
        LibraryStore store = family(tmp);
        List<FamilyQuestions.Ask> asks = FamilyQuestions.around(store, "髙橋まり", 6, 3);
        store.frontier("person me (from the family tree)", asks.get(0).question());
        List<String> before = FamilyQuestions.everAsked(store);
        assertTrue(FamilyQuestions.asked(before, asks.get(0).person()));
        assertFalse(FamilyQuestions.asked(before, asks.get(1).person()), "one templated question does not stand for another person's");
        assertNull(FamilyQuestions.around(store, "nobody of this name", 6, 3));
    }

    @Test
    void theLivingAreAskedAboutTheirPublicLifeAChildLikeAnyoneElse(@TempDir Path tmp) throws Exception {
        LibraryStore store = family(tmp);
        int thisYear = LocalDate.now().getYear();
        FamilyAccount.file(store, new FamilyAccount.Read(List.of(), List.of(fact("髙橋そら", "child-of", "髙橋まり", ""), fact("髙橋そら", "born-on", String.valueOf(thisYear - 9), "")), List.of()), "file:///notes.txt", "an aunt");
        List<FamilyQuestions.Ask> asks = FamilyQuestions.around(store, "髙橋まり", 6, 3, true);
        String all = String.join("\n", asks.stream().map(FamilyQuestions.Ask::question).toList());
        assertTrue(all.contains("髙橋まり (also written 高橋まり; born 1975; child of 髙橋正一): answer each of these questions") && all.contains("What do public sources say of 髙橋まり's work and public life?") && !all.contains("did 髙橋まり die"), "the living are asked about their public life and never their death: " + all);
        assertTrue(all.contains("What do public sources say of 髙橋そら's work and public life?"), "a nine-year-old is a living person like any other: " + all);
        assertEquals(5, asks.size(), all);
        assertTrue(asks.get(3).living() && asks.get(4).living(), "those who have died are asked about before the living: " + all);
        assertTrue(FamilyQuestions.around(store, "髙橋まり", 6, 3, false) == null || FamilyQuestions.around(store, "髙橋まり", 6, 3, false).stream().noneMatch(a -> a.person().equals("髙橋そら") || a.person().equals("髙橋まり")), "--skip-living leaves both out");
    }

    @Test
    void withNobodyNamedEverybodyIsGoneThroughEvenFamiliesThatDoNotTouchYet(@TempDir Path tmp) throws Exception {
        LibraryStore store = family(tmp);
        FamilyAccount.file(store, new FamilyAccount.Read(List.of(), List.of(fact("森田勇", "parent-of", "森田一郎", ""), fact("森田勇", "died-on", "1940", ""), fact("森田一郎", "died-on", "1980", "")), List.of()), "file:///mother.txt", "an aunt");
        List<String> people = FamilyQuestions.everybody(store, 6, 3, true).stream().map(FamilyQuestions.Ask::person).toList();
        assertTrue(people.containsAll(List.of("髙橋源三郎", "髙橋ハル", "髙橋正一", "髙橋まり", "森田勇", "森田一郎")), "the father's side and the mother's side, which no fact joins yet: " + people);
        assertEquals(people.size(), new HashSet<>(people).size(), "each person once");
        assertEquals("髙橋まり", people.get(people.size() - 1), "the living last");
    }

    @Test
    void severalPeopleToStartFromOrOnlyThePeopleNamed(@TempDir Path tmp) throws Exception {
        LibraryStore store = family(tmp);
        FamilyAccount.file(store, new FamilyAccount.Read(List.of(), List.of(fact("森田勇", "parent-of", "森田一郎", ""), fact("森田勇", "died-on", "1940", ""), fact("森田一郎", "died-on", "1980", "")), List.of()), "file:///mother.txt", "an aunt");
        List<String> unknown = new ArrayList<>();
        List<String> both = FamilyQuestions.aroundEach(store, List.of("髙橋正一", "森田勇", "nobody"), 6, 3, true, false, unknown).stream().map(FamilyQuestions.Ask::person).toList();
        assertTrue(both.containsAll(List.of("髙橋源三郎", "髙橋正一", "森田勇", "森田一郎")), both.toString());
        assertEquals(List.of("nobody"), unknown);
        List<FamilyQuestions.Ask> asks = FamilyQuestions.aroundEach(store, List.of("髙橋正一", "森田勇"), 6, 3, true, false, new ArrayList<>());
        List<String> ordered = FamilyQuestions.namedFirst(store, asks, List.of("髙橋正一", "森田勇")).stream().map(FamilyQuestions.Ask::person).toList();
        assertEquals(List.of("髙橋正一", "森田勇"), ordered.subList(0, 2), "the people named are searched for first, then their relatives: " + ordered);
        assertEquals(asks.size(), ordered.size());
        List<String> only = FamilyQuestions.aroundEach(store, List.of("髙橋正一", "森田勇"), 6, 3, true, true, new ArrayList<>()).stream().map(FamilyQuestions.Ask::person).toList();
        assertEquals(Set.of("髙橋正一", "森田勇"), new HashSet<>(only), "only the people named, without their relatives");
    }

    @Test
    void aTypedNameIsFoundWithoutItsAccentsAndANameItCannotPlaceIsAnsweredWithTheNamesItCouldBe(@TempDir Path tmp) throws Exception {
        LibraryStore store = family(tmp);
        FamilyAccount.file(store, new FamilyAccount.Read(List.of(), List.of(fact("Robert Morō", "child-of", "Hisa Morō", ""), fact("Robert Morō", "died-on", "1990", ""),
                fact("the writer of town.pdf — the person who keeps this library notes: x", "child-of", "Hisa Morō", ""), fact("Mara's mother's father", "parent-of", "Hisa Morō", "")), List.of()), "file:///b.txt", "a book");
        boolean[] exact = {false};
        assertEquals(List.of("Robert Morō"), FamilyQuestions.meant(store, "robert moro", exact));
        assertTrue(exact[0]);
        assertEquals(List.of("髙橋正一"), FamilyQuestions.meant(store, "高橋正一", exact), "the modern form of a name finds the old one");
        List<String> near = FamilyQuestions.meant(store, "Bob Morō", exact);
        assertFalse(exact[0]);
        assertTrue(near.contains("Robert Morō") && near.contains("Hisa Morō") && near.stream().noneMatch(n -> n.startsWith("the writer")), near.toString());
        List<String> asked = FamilyQuestions.everybody(store, 6, 3, true).stream().map(FamilyQuestions.Ask::person).toList();
        assertTrue(asked.contains("Robert Morō") && asked.stream().noneMatch(n -> n.startsWith("the writer of") || n.contains("mother's father")), "somebody the account did not name is in the tree and is not searched for: " + asked);
    }

    @Test
    void waitingIsNotSearched(@TempDir Path tmp) throws Exception {
        LibraryStore store = family(tmp);
        List<FamilyQuestions.Ask> asks = FamilyQuestions.around(store, "髙橋まり", 6, 3);
        store.frontier("person me (from the family tree)", asks.get(0).question());
        assertEquals(asks.get(0).question(), FamilyQuestions.waitingFor(FamilyQuestions.waiting(store), asks.get(0).person()));
        assertFalse(FamilyQuestions.asked(FamilyQuestions.searched(store), asks.get(0).person()), "a question on the waiting list has not been searched for");
        Frontier.markExplored(store, asks.get(0).question(), "started by name as J-0001");
        assertNull(FamilyQuestions.waitingFor(FamilyQuestions.waiting(store), asks.get(0).person()));
        assertTrue(FamilyQuestions.asked(FamilyQuestions.searched(store), asks.get(0).person()));
    }

    @Test
    void aPersonIsAskedAboutTogetherWithTheFamilyAroundThemAndABrotherKnownOnlyAsABrotherIsReached(@TempDir Path tmp) throws Exception {
        LibraryStore store = family(tmp);
        FamilyAccount.file(store, new FamilyAccount.Read(List.of(), List.of(fact("髙橋正二", "sibling-of", "髙橋正一", ""), fact("髙橋正二", "died-on", "1995", ""),
                fact("森田すみ", "sibling-of", "森田ゆり", ""), fact("森田ゆり", "married-to", "髙橋正一", ""), fact("森田ゆり", "died-on", "2001", ""), fact("森田ゆり", "born-in", "岡山", ""), fact("森田すみ", "died-on", "2010", "")), List.of()), "file:///notes.txt", "an aunt");
        List<FamilyQuestions.Ask> asks = FamilyQuestions.around(store, "髙橋まり", 6, 3, true);
        String shoji = asks.stream().filter(a -> a.person().equals("髙橋正二")).findFirst().orElseThrow().question();
        assertTrue(shoji.contains("The family around this person, as leads: brother or sister 髙橋正一 (born 1908; died 1990)"), "a brother the notes name only as a brother is reached, and asked about with his brother as a lead: " + shoji);
        assertTrue(shoji.contains("The brothers and sisters have the same parents"), shoji);
        String shoichi = asks.stream().filter(a -> a.person().equals("髙橋正一")).findFirst().orElseThrow().question();
        assertTrue(shoichi.contains("parent 髙橋源三郎 (born 1872 in 広島; died 1945)") && shoichi.contains("husband or wife 森田ゆり (born in 岡山; died 2001)") && shoichi.contains("When and where did 髙橋正一 die?") == false && shoichi.contains("What did 髙橋正一 do that was written about"), "a man the library knows to have died stays so when a later text names him without his death: " + shoichi);
        String quiet = FamilyQuestions.around(store, "髙橋まり", 6, 3, false).stream().filter(a -> a.person().equals("髙橋正一")).findFirst().orElseThrow().question();
        assertFalse(quiet.contains("髙橋まり"), "a living child is not named in a parent's question unless the owner asks about the living: " + quiet);
        String sumi = asks.stream().filter(a -> a.person().equals("森田すみ")).findFirst().orElseThrow().question();
        assertTrue(sumi.contains("brother or sister 森田ゆり (born in 岡山; died 2001)"), "the wife's sister, reached through the wife: " + sumi);
    }

    @Test
    void theOtherWaysANameIsWrittenGoIntoTheQuestion(@TempDir Path tmp) throws Exception {
        LibraryStore store = family(tmp);
        Graph.alias(store, "髙橋正一", List.of("Shoichi Takahashi", "高橋正一", "the writer of notes.txt"));
        String q = FamilyQuestions.around(store, "髙橋まり", 6, 3).stream().filter(a -> a.person().equals("髙橋正一")).findFirst().orElseThrow().question();
        assertTrue(q.startsWith("髙橋正一 (also written 高橋正一, Shoichi Takahashi; born 1908; died 1990;") && !q.contains("the writer of"), q);
        assertTrue(FamilyQuestions.asked(List.of(q), "髙橋正一"));
    }

    @Test
    void aNamesakesQuestionIsNotAQuestionAboutTheBareName(@TempDir Path tmp) throws Exception {
        // the grandfather is Tom Ellis, the cousin the tree file told apart is Tom Ellis (born 1985); only the cousin has been asked about
        String cousin = "Tom Ellis (born 1985) (born 1985 in Leeds; worked as teacher): answer each of these questions on its own. 1. What do public sources say of Tom Ellis (born 1985)'s work and public life?";
        assertFalse(FamilyQuestions.asked(List.of(cousin), "Tom Ellis"), "the grandfather is still to be searched");
        assertNull(FamilyQuestions.waitingFor(List.of(cousin), "Tom Ellis"));
        assertEquals(List.of(), FamilyQuestions.askedAbout(List.of(cousin), "Tom Ellis"));
        assertTrue(FamilyQuestions.asked(List.of(cousin), "Tom Ellis (born 1985)"));
        String grandfather = "Tom Ellis (born 1920 in York; died 1990; lived in Kure (Hiroshima)): answer each of these questions on its own.";
        assertTrue(FamilyQuestions.asked(List.of(grandfather), "Tom Ellis"), "brackets inside what is known are part of it");
        assertFalse(FamilyQuestions.asked(List.of(grandfather), "Tom Ellis (born 1985)"));
        assertTrue(FamilyQuestions.asked(List.of("Tom Ellis: Where is his grave?"), "Tom Ellis"), "a research note from a tree file");
        // a fresh start for the grandfather's notes takes back his waiting question and leaves the cousin's
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(fact("Tom Ellis", "born-in", "York", "1920")), List.of()), "file:///home/me/notes.txt", "an aunt");
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(fact("Tom Ellis (born 1985)", "born-in", "Leeds", "1985")), List.of()), "file:///home/me/tree.ged", "a tree file");
        store.frontier("person me (from the family tree)", cousin);
        store.frontier("person me (from the family tree)", grandfather);
        FamilyReset.Plan plan = FamilyReset.plan(store, List.of("family-account"), "notes.txt");
        assertEquals(List.of(grandfather), FamilyReset.waitingAbout(store, plan.claims()));
    }

    @Test
    void aPersonWithAPageOfTheirOwnIsAskedAboutWithThePagesThatMentionThem(@TempDir Path tmp) throws Exception {
        LibraryStore store = family(tmp);
        // the great-grandfather's dates were read from his own encyclopedia page, under a title with a note in brackets; his wife's from somebody else's page
        FamilyAccount.file(store, new FamilyAccount.Read(List.of(), List.of(fact("髙橋源三郎", "died-in", "東京", "")), List.of()), "https://ja.wikipedia.org/wiki/%E9%AB%99%E6%A9%8B%E6%BA%90%E4%B8%89%E9%83%8E_(%E8%BB%8D%E4%BA%BA)", "an encyclopedia");
        FamilyAccount.file(store, new FamilyAccount.Read(List.of(), List.of(fact("髙橋ハル", "born-in", "広島", "")), List.of()), "https://ja.wikipedia.org/wiki/%E5%BA%83%E5%B3%B6%E5%B8%82", "an encyclopedia");
        Graph g = Graph.build(store);
        List<String[]> own = FamilyQuestions.ownPages(store, g, "髙橋源三郎");
        assertEquals(1, own.size());
        assertEquals("ja", own.get(0)[0]);
        assertEquals("髙橋源三郎 (軍人)", own.get(0)[1]);
        assertTrue(FamilyQuestions.ownPages(store, g, "髙橋ハル").isEmpty(), "a page about a town is not the person's page");

        List<String> askedFor = new ArrayList<>();
        List<FamilyQuestions.Ask> asks = FamilyQuestions.withLeads(store, FamilyQuestions.around(store, "髙橋まり", 6, 3),
                (lang, title, person, years) -> { askedFor.add(person + " " + years); return List.of("某事件", "広島鉄道"); }, who -> { });
        assertEquals(List.of("髙橋源三郎 1872-1945"), askedFor, "only the person with a page, with their years: " + askedFor);
        FamilyQuestions.Ask his = asks.stream().filter(a -> a.person().equals("髙橋源三郎")).findFirst().orElseThrow();
        assertTrue(his.question().contains("Encyclopedia pages that mention this person") && his.question().contains("某事件; 広島鉄道"), his.question());
        assertTrue(asks.stream().filter(a -> !a.person().equals("髙橋源三郎")).noneMatch(a -> a.question().contains("Encyclopedia pages")));
    }

    @Test
    void aPersonWhoseLeadsTheModelDeclinedIsLeftAndTheOthersGoOn(@TempDir Path tmp) throws Exception {
        // M3: one person's leads declined: no exception out of the loop, that person's question is not sent on, the others are
        LibraryStore store = family(tmp);
        FamilyAccount.file(store, new FamilyAccount.Read(List.of(), List.of(new FamilyAccount.Fact("髙橋源三郎", "died-in", "東京", "", "q")), List.of()), "https://ja.wikipedia.org/wiki/%E9%AB%99%E6%A9%8B%E6%BA%90%E4%B8%89%E9%83%8E_(%E8%BB%8D%E4%BA%BA)", "an encyclopedia");
        List<FamilyQuestions.Ask> around = FamilyQuestions.around(store, "髙橋まり", 6, 3);
        FamilyQuestions.Leads declining = (lang, title, person, years) -> { throw new Declined("placeholder-model", "I am not able to help with that request.", Declined.How.WORDS); };
        List<FamilyQuestions.Ask> asks = assertDoesNotThrow(() -> FamilyQuestions.withLeads(store, around, declining, who -> { }));
        assertTrue(asks.stream().noneMatch(a -> a.person().equals("髙橋源三郎")), "his question is not sent on as if no page mentioned him");
        assertEquals(around.size() - 1, asks.size(), "everybody else goes on");
        Map<String, Declined> declined = new LinkedHashMap<>();
        FamilyQuestions.withLeads(store, around, declining, who -> { }, declined);
        assertEquals(List.of("髙橋源三郎"), List.copyOf(declined.keySet()));
        assertEquals("to pick the encyclopedia pages that mention 髙橋源三郎", declined.get("髙橋源三郎").step());
    }

    @Test
    void aMarriedWomanSearchedUnderOneFamilyNameOnlyIsToSearchUnderTheOther(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        FamilyAccount.file(store, new FamilyAccount.Read(List.of(new FamilyAccount.Person("Mari Hale", "", List.of("Mari Morita"))), List.of(
                fact("Mari Hale", "married-to", "Tom Hale", "1921"), fact("Mari Hale", "born-on", "1899", ""), fact("Mari Hale", "died-on", "1960", ""),
                fact("Tom Hale", "died-on", "1950", "")), List.of()), "file:///notes.txt", "an aunt");
        SearchLog.add(store, List.of(new SearchLog.Entry("2026-09-20", "Mari Hale (born 1899): …", "FamilySearch", "\"Mari Hale\" 1899", 1890, 1910, 0, "J-1"),
                new SearchLog.Entry("2026-09-20", "Mari Hale (born 1899): …", "web", "Mari Hale Sendai", 0, 0, 3, "J-1")));
        FamilyQuestions.Ask mari = FamilyQuestions.around(store, "Mari Hale", 2, 2).stream().filter(a -> a.person().equals("Mari Hale")).findFirst().orElseThrow();
        // Mari Hale, of the family part of the husband she married in 1921, carries it from that marriage (her sex is not filed, which does not matter)
        assertTrue(mari.questions().contains("Mari Hale has been searched for only under the name Mari Hale (from 1921). Search for Mari Hale under Mari Morita as well, in the places already searched (FamilySearch, web). "
                + "No search has carried Mari Hale and Mari Morita together: the record of the change names both, so search Mari Hale together with Morita."), mari.questions().toString());
        SearchLog.add(store, List.of(new SearchLog.Entry("2026-09-21", "Mari Hale: …", "FamilySearch", "\"Mari Morita\"", 0, 0, 1, "J-2")));
        mari = FamilyQuestions.around(store, "Mari Hale", 2, 2).stream().filter(a -> a.person().equals("Mari Hale")).findFirst().orElseThrow();
        assertTrue(mari.questions().stream().noneMatch(q -> q.contains("searched for only under")), "both names have been searched: " + mari.questions());
        assertTrue(mari.questions().contains("No search has carried Mari Hale and Mari Morita together: the record of the change names both, so search Mari Hale together with Morita."), mari.questions().toString());
        SearchLog.add(store, List.of(new SearchLog.Entry("2026-09-22", "Mari Hale: …", "web", "\"Mari Hale\" Morita marriage", 0, 0, 2, "J-3")));
        mari = FamilyQuestions.around(store, "Mari Hale", 2, 2).stream().filter(a -> a.person().equals("Mari Hale")).findFirst().orElseThrow();
        assertTrue(mari.questions().stream().noneMatch(q -> q.contains("Mari Morita")), "each name and the change have been searched, so the question is done: " + mari.questions());
        assertEquals(List.of("森田", "髙橋"), FamilyNames.surnames(List.of("森田まり", "髙橋 まり")));
        assertEquals(List.of(), FamilyNames.surnames(List.of("森田正一", "森田正二", "Tom Hale", "Ann Hale")), "two people's names, not one person's two family names");
    }

    @Test
    void aQuestionHeldForARecordIsLeftOutOfTheRunsAndListedWithItsReason(@TempDir Path tmp) throws Exception {
        LibraryStore store = family(tmp);
        List<String> before = FamilyQuestions.questionsOf(store, "髙橋源三郎");
        assertEquals("Who were the parents of 髙橋源三郎?", before.get(0));
        String clue = before.stream().filter(q -> q.startsWith("Which of these facts")).findFirst().orElseThrow();
        FamilyQuestions.Held h = FamilyQuestions.hold(store, "髙橋源三郎", before.get(0), "the 除籍謄本 asked from the town hall");
        FamilyQuestions.hold(store, "髙橋源三郎", clue, "the same register");
        assertEquals(LocalDate.now().toString(), h.date());
        List<String> after = FamilyQuestions.questionsOf(store, "髙橋源三郎");
        assertFalse(after.contains("Who were the parents of 髙橋源三郎?"), after.toString());
        assertTrue(after.stream().noneMatch(q -> q.startsWith("Which of these facts")), "the same question with other facts after its colon is still held: " + after);
        FamilyQuestions.Ask run = FamilyQuestions.around(store, "髙橋まり", 6, 3).stream().filter(a -> a.person().equals("髙橋源三郎")).findFirst().orElseThrow();
        assertFalse(run.question().contains("parents of 髙橋源三郎"), "the run is not sent after it: " + run.question());
        assertEquals(2, FamilyQuestions.heldFor(FamilyQuestions.held(store), "髙橋源三郎").size());
        assertTrue(FamilyQuestions.release(store, "髙橋源三郎", "Who were the parents of 髙橋源三郎?"));
        assertEquals("Who were the parents of 髙橋源三郎?", FamilyQuestions.questionsOf(store, "髙橋源三郎").get(0), "let go, it is asked again");
        assertFalse(FamilyQuestions.release(store, "髙橋源三郎", "Who were the parents of 髙橋源三郎?"));
        assertNull(FamilyQuestions.questionsOf(store, "Hale Tom"), "nobody in the tree");
    }

    @Test
    void theHoldCommandListsNumbersHoldsAndLetsGo(@TempDir Path tmp) throws Exception {
        LibraryStore store = family(tmp);
        PrintStream real = System.out;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        try {
            assertEquals(0, GenealogyProfile.hold(store, new String[]{"librarian", "genealogy", "hold", "髙橋源三郎"}));
            assertTrue(out.toString(StandardCharsets.UTF_8).contains("  1. Who were the parents of 髙橋源三郎?"));
            assertEquals(2, GenealogyProfile.hold(store, new String[]{"librarian", "genealogy", "hold", "髙橋源三郎", "1"}), "a hold says what it waits for");
            assertEquals(0, GenealogyProfile.hold(store, new String[]{"librarian", "genealogy", "hold", "髙橋源三郎", "1", "--until", "the", "除籍謄本", "from", "the", "town", "hall"}));
            assertEquals("the 除籍謄本 from the town hall", FamilyQuestions.held(store).get(0).why());
            out.reset();
            assertEquals(0, GenealogyProfile.hold(store, new String[]{"librarian", "genealogy", "hold", "髙橋源三郎"}));
            String listed = out.toString(StandardCharsets.UTF_8);
            assertTrue(listed.contains("Records to request for 髙橋源三郎") && listed.contains("waits for: the 除籍謄本 from the town hall, since " + LocalDate.now()), listed);
            assertEquals(0, GenealogyProfile.hold(store, new String[]{"librarian", "genealogy", "hold", "髙橋源三郎", "--release", "1"}));
            assertTrue(FamilyQuestions.held(store).isEmpty());
        } finally { System.setOut(real); }
    }

    @Test
    void theSitesWithNoSearchGetAnAddressFilledWithTheNameSplitTheWayTheFamilyWritesIt(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        FamilyAccount.file(store, new FamilyAccount.Read(List.of(
                new FamilyAccount.Person("遠藤源三郎", "", List.of("Endo Genzaburo")),
                new FamilyAccount.Person("Endo Haru", "", List.of()),
                new FamilyAccount.Person("Tom Hale", "", List.of()),
                new FamilyAccount.Person("Kimie Hale", "", List.of())), List.of(
                fact("遠藤源三郎", "married-to", "Endo Haru", ""), fact("遠藤源三郎", "born-on", "1872", ""), fact("遠藤源三郎", "died-on", "1945", ""),
                fact("Tom Hale", "born-on", "1850", ""), fact("Tom Hale", "born-in", "Boston", ""),
                fact("Kimie Hale", "child-of", "Tom Hale", ""), fact("Kimie Hale", "born-on", "1990", "")), List.of()), "file:///notes.txt", "an aunt");
        Graph g = Graph.build(store);
        assertArrayEquals(new String[]{"Genzaburo", "Endo"}, FamilyQuestions.givenFamily(g, g.node(g.nodeIdOf("遠藤源三郎"))), "the word he shares with his wife is the family name");
        assertArrayEquals(new String[]{"Tom", "Hale"}, FamilyQuestions.givenFamily(g, g.node(g.nodeIdOf("Tom Hale"))));
        List<String[]> sites = FamilyQuestions.siteLinks(g, "遠藤源三郎");
        String fs = sites.stream().filter(x -> x[0].equals("FamilySearch")).findFirst().orElseThrow()[2];
        assertEquals("https://www.familysearch.org/search/record/results?q.givenName=Genzaburo&q.surname=Endo&q.birthLikeDate.from=1872&q.birthLikeDate.to=1872&q.deathLikeDate.from=1945&q.deathLikeDate.to=1945", fs);
        String compgen = FamilyQuestions.siteLinks(g, "Tom Hale").stream().filter(x -> x[0].startsWith("CompGen")).findFirst().orElseThrow()[2];
        assertEquals("https://meta.genealogy.net/search?lastname=Hale&place=Boston", compgen);
        assertTrue(FamilyQuestions.siteLinks(g, "Kimie Hale").isEmpty(), "a person who may be living gets no links");
        assertTrue(FamilyQuestions.siteLinks(Graph.build(family(tmp.resolve("b"))), "髙橋源三郎").isEmpty(), "a name held only in kanji cannot be split into the fields these sites ask for");
    }

    @Test
    void theSearchLinksCarryTheYearsOfABirthAndADeathFiledWithTheirPlaces(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Path f = tmp.resolve("t3.ged");
        Files.writeString(f, "0 HEAD\n0 @I1@ INDI\n1 NAME Tom /Hale/\n1 BIRT\n2 DATE 1850\n2 PLAC York\n1 DEAT\n2 DATE 1920\n2 PLAC York\n0 TRLR\n", StandardCharsets.UTF_8);
        Gedcom.importFile(store, f);
        String familySearch = FamilyQuestions.siteLinks(Graph.build(store), "Tom Hale").stream().filter(x -> x[0].startsWith("FamilySearch")).findFirst().orElseThrow()[2];
        assertTrue(familySearch.contains("1850") && familySearch.contains("1920"), familySearch);
    }

    @Test
    void aFactTheFamilyDisputedIsNotAskedToBeConfirmed(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(new FamilyAccount.Fact("Ann Hart", "born-on", "ABT 1905", "", "q"), new FamilyAccount.Fact("Ann Hart", "died-on", "1980", "", "q")), List.of()),
                "file:///family/tree.txt", "a tree");
        Finding birth = store.scanFindings().findings().stream().filter(x -> x.triple().predicate().equals("born-on")).findFirst().orElseThrow();
        new Council(store).dispute(birth.id(), "she was born in 1907");
        String q = FamilyQuestions.around(store, "Ann Hart", 6, 3, false).stream().filter(a -> a.person().equals("Ann Hart")).findFirst().orElseThrow().question();
        assertFalse(q.contains("1905"), q);
        assertTrue(q.contains("When was Ann Hart born") || q.contains("When and where was Ann Hart born"), q);
    }
}
