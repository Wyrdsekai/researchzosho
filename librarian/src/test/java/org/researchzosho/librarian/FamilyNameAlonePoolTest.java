package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * f-owner-family-name-alone-pool-one-person: an entry written by a family name alone ("Hart", "Mr. Hart") holds the claims of every source that
 * wrote only the family name, which may be about several people. A "one person or two?" question paired such an entry with one real person,
 * and "one person" would have joined the whole pool into that person: on the owner's library, one brother's facts onto the other brother. The
 * entry is never one side of that question. Who it is, is asked by its own question, which offers the people who bore the family name and
 * moves the claims of the words it quotes.
 *
 * <p>f-owner-pool-one-passage-at-a-time: that question came one passage at a time, with every entry of the family name as a choice: women for
 * words that say "Mr.", people born after the passage's year, a described servant, and one man under several entries. Now it is one question
 * for each source and each way the source writes the person ("Mr. Hart", "old Hart"), with only the people the words do not contradict, the
 * likeliest first, six at most, and whether two of them are one person is asked before it.
 */
class FamilyNameAlonePoolTest {

    static final String MILL = "Hart kept the Hart family's mill near Leeds in 1890.";
    static final String PARISH = "Hart, the priest of the Hart family, served St Mary's in York in 1895.";

    /** Tom Hart the miller and John Hart the priest, from a register; a mill book and a parish book that write each of them only as "Hart". */
    static LibraryStore pool(Path tmp) throws Exception {
        LibraryStore store = FamilyNameQuestionsTest.store(tmp);
        String reg = "Tom Hart, a miller, was born in 1850. John Hart, a priest, was born in 1852.";
        FamilyNameQuestionsTest.file(store, "file:///family/register.txt", List.of(
                FamilyNameQuestionsTest.fact("Tom Hart", "born-on", "1850", "", reg), FamilyNameQuestionsTest.fact("Tom Hart", "occupation", "miller", "", reg),
                FamilyNameQuestionsTest.fact("John Hart", "born-on", "1852", "", reg), FamilyNameQuestionsTest.fact("John Hart", "occupation", "priest", "", reg)),
                List.of(FamilyNameQuestionsTest.name("Tom Hart", "Tom Hart", "Hart", "Tom", "birth", "1850", reg),
                        FamilyNameQuestionsTest.name("John Hart", "John Hart", "Hart", "John", "birth", "1852", reg)));
        FamilyOneWordNamesTest.told(store, "mill-book.txt", "Hart", "occupation", "miller", MILL);
        FamilyOneWordNamesTest.told(store, "mill-book.txt", "Hart", "lived-in", "Leeds", MILL);
        FamilyOneWordNamesTest.told(store, "parish-book.txt", "Hart", "occupation", "priest", PARISH);
        FamilyOneWordNamesTest.told(store, "parish-book.txt", "Hart", "lived-in", "York", PARISH);
        return store;
    }

    /** The claims of one passage of a source's words. */
    static Set<String> passage(LibraryStore store, String quote) {
        Set<String> out = new TreeSet<>();
        for (Finding f : store.scanFindings().findings()) if (FamilyChecks.quoteOf(f).equals(quote)) out.add(f.id());
        return out;
    }

    static List<String> texts(List<FamilyNameQuestions.Question> qs) { return qs.stream().map(q -> q.kind() + ": " + q.text()).toList(); }

    static FamilyNameQuestions.Question aboutHart(LibraryStore store) throws Exception {
        String hart = FamilyPeople.view(store).nodeIdOf("Hart");
        List<FamilyNameQuestions.Question> all = FamilyNameQuestions.open(store);
        return all.stream().filter(q -> q.kind().equals("family-name-alone") && q.people().contains(hart)).findFirst()
                .orElseThrow(() -> new AssertionError("no question asks who “Hart” is: " + texts(all)));
    }

    static FamilyNameQuestions.Option offered(FamilyNameQuestions.Question q, String person) {
        return q.options().stream().filter(o -> o.says().startsWith(person)).findFirst()
                .orElseThrow(() -> new AssertionError("the question about “Hart” does not offer " + person + ": " + q.options().stream().map(FamilyNameQuestions.Option::says).toList()));
    }

    /** A record gives John Hart the name Hart; another gives the entry Hart the name John Hart. Neither pairs the entry with him. */
    @Test
    void anEntryOfAFamilyNameAloneIsNeverOneSideOfAOnePersonQuestion(@TempDir Path tmp) throws Exception {
        LibraryStore his = pool(tmp.resolve("his"));
        FamilyNameQuestionsTest.file(his, "file:///family/parish.txt", List.of(), List.of(FamilyNameQuestionsTest.name("John Hart", "Hart", "", "", "", "", "The priest John Hart, called Hart in the parish.")));
        LibraryStore its = pool(tmp.resolve("its"));
        FamilyNameQuestionsTest.file(its, "file:///family/parish.txt", List.of(), List.of(FamilyNameQuestionsTest.name("Hart", "John Hart", "Hart", "John", "", "", "Hart, that is John Hart, the priest.")));
        for (LibraryStore store : List.of(his, its)) {
            Graph g = FamilyPeople.view(store);
            String hart = g.nodeIdOf("Hart");
            List<FamilyNameQuestions.Question> all = FamilyNameQuestions.open(store);
            List<FamilyNameQuestions.Question> pairs = all.stream().filter(q -> q.kind().equals("one-person") && q.people().contains(hart)).toList();
            assertTrue(pairs.isEmpty(), "no question asks whether the entry “Hart” and a person are one person: " + texts(pairs));
            assertTrue(FamilyNameQuestions.forChecks(store, g).stream().noneMatch(q -> q.people().contains(hart)), "nor do the checks: " + texts(FamilyNameQuestions.forChecks(store, g)));
            assertTrue(all.stream().anyMatch(q -> q.kind().equals("family-name-alone") && q.people().contains(hart)), "who “Hart” is, is asked by its own question: " + texts(all));
        }
    }

    /** The question about the entry offers the people who bore the family name; the answer for one source's words moves those words' claims alone. */
    @Test
    void theQuestionAboutThePoolOffersThePeopleWhoBoreTheNameAndEachAnswerMovesOnePassage(@TempDir Path tmp) throws Exception {
        LibraryStore store = pool(tmp);
        Set<String> mill = passage(store, MILL), parish = passage(store, PARISH);
        String tom = FamilyPeople.view(store).nodeIdOf("Tom Hart"), john = FamilyPeople.view(store).nodeIdOf("John Hart");
        Set<String> tomsOwn = FamilyOneWordNamesTest.claimsOf(FamilyPeople.view(store), tom), johnsOwn = FamilyOneWordNamesTest.claimsOf(FamilyPeople.view(store), john);

        FamilyNameQuestions.Question first = aboutHart(store);
        assertTrue(first.text().contains("In mill-book.txt, “Hart” is written in 1 passage: “" + MILL + "”"), "the question quotes one source's words: " + first.text());
        assertTrue(first.options().get(0).says().startsWith("Tom Hart"), "the miller first, since a fact of the words agrees with his: " + first.options());
        offered(first, "John Hart");
        FamilyNameQuestions.Option tomOption = offered(first, "Tom Hart");
        assertTrue(tomOption.does().contains("elsewhere in your sources stays as it is"), "the option says the other words stay: " + tomOption.does());
        FamilyNameQuestions.answer(store, first.code(), tomOption.key(), "", "Mary");

        Graph g = FamilyPeople.view(store);
        String hart = g.nodeIdOf("Hart");
        Set<String> tomsNow = new TreeSet<>(tomsOwn); tomsNow.addAll(mill);
        assertEquals(tomsNow, FamilyOneWordNamesTest.claimsOf(g, tom), "the mill book's words are about Tom Hart");
        assertEquals(parish, FamilyOneWordNamesTest.claimsOf(g, hart), "the parish book's words stay with “Hart”");
        assertEquals(johnsOwn, FamilyOneWordNamesTest.claimsOf(g, john), "John Hart is as he was");
        assertTrue(Graph.merges(store).isEmpty(), "nothing is joined: " + Graph.merges(store));

        FamilyNameQuestions.Question second = aboutHart(store);
        assertTrue(second.text().contains("In parish-book.txt, “Hart” is written in 1 passage: “" + PARISH + "”"), "the other book's words are asked about next: " + second.text());
        assertTrue(second.options().get(0).says().startsWith("John Hart"), "the priest first: " + second.options());
        FamilyNameQuestions.answer(store, second.code(), offered(second, "John Hart").key(), "", "Mary");
        Graph after = FamilyPeople.view(store);
        Set<String> johnsNow = new TreeSet<>(johnsOwn); johnsNow.addAll(parish);
        assertEquals(johnsNow, FamilyOneWordNamesTest.claimsOf(after, john), "the parish book's words are about John Hart");
        assertEquals(tomsNow, FamilyOneWordNamesTest.claimsOf(after, tom), "and Tom Hart keeps the mill book's");
        assertTrue(Graph.merges(store).isEmpty(), "nothing is joined: " + Graph.merges(store));
    }

    /** Words that write a relative of the entry but not its name are not what the question quotes first when a passage of the same source shows the name. */
    @Test
    void theQuestionQuotesAPassageThatShowsTheName(@TempDir Path tmp) throws Exception {
        LibraryStore store = pool(tmp.resolve("relative"));
        FamilyOneWordNamesTest.told(store, "letters.txt", "Ruth Hale", "sex", "female", "Ruth Hale, a teacher.");
        FamilyOneWordNamesTest.told(store, "letters.txt", "Hart", "married-to", "Ruth Hale", "Ruth Hale married a miller of Leeds in 1880.");
        FamilyOneWordNamesTest.told(store, "letters.txt", "Hart", "lived-in", "Leeds", "Hart milled corn at Leeds.");
        String hart = FamilyPeople.view(store).nodeIdOf("Hart");
        FamilyNameQuestions.Question q = FamilyNameQuestions.open(store).stream().filter(x -> x.kind().equals("family-name-alone") && x.people().contains(hart) && x.text().contains("In letters.txt")).findFirst().orElseThrow();
        assertTrue(q.text().contains("In letters.txt, “Hart” is written in 2 passages: “Hart milled corn at Leeds.” and “Ruth Hale married a miller of Leeds in 1880.”"), "the words that show the name first: " + q.text());

        LibraryStore late = FamilyNameQuestionsTest.store(tmp.resolve("late"));
        FamilyNameQuestionsTest.file(late, "file:///family/register.txt", List.of(FamilyNameQuestionsTest.fact("Tom Hart", "born-on", "1850", "", "Tom Hart was born in 1850.")),
                List.of(FamilyNameQuestionsTest.name("Tom Hart", "Tom Hart", "Hart", "Tom", "birth", "1850", "Tom Hart was born in 1850.")));
        String longer = "In the spring of 1890 the old water mill on the river below the church at Kirkstall, which had stood idle through three hard winters and two floods,"
                + " was taken on at last by the Hart family's youngest son, Hart, who kept it until he died.";
        FamilyOneWordNamesTest.told(late, "mill-book.txt", "Hart", "occupation", "miller", longer);
        FamilyNameQuestions.Question l = aboutHart(late);
        assertTrue(l.text().contains("taken on at last by the Hart family's youngest son, Hart"), "the quoted words show the name: " + l.text());
    }

    // ── one question per source and written form ──────────────────────────────────────────────────────────────────

    static final String BAKERY = "Mr. Hart kept a bakery in Hull in 1905.";
    static final String MARKET = "Mr. Hart sold his bread at the market in Hull in 1906.";
    static final String OLD = "In 1911 old Hart still kept the Hart family's mill at York.";
    static final String RENT = "Mr. Hart rented the mill at York.";

    /**
     * A pool "Hart": port-book.txt writes "Mr. Hart" in two passages (1905, 1906) and "old Hart" in one (1911), and mill-book.txt writes
     * "Mr. Hart" in one, with no year. The register has Tom Hart, a baker of Hull; John Hart, a priest, whom port-book.txt names too; Ellis
     * Hart; Mary Hart and Ann Hart, two women; and Hale Hart, born in 1920. A letter describes a servant of the family and does not name him,
     * and a record gives Tom Hale the name Tom Hart, so the library may hold one man twice.
     */
    static LibraryStore groups(Path tmp) throws Exception {
        LibraryStore store = FamilyNameQuestionsTest.store(tmp);
        String reg = "The register of the Hart family.";
        List<FamilyAccount.Fact> facts = new ArrayList<>();
        List<FamilyAccount.NameRead> names = new ArrayList<>();
        for (String[] p : new String[][]{{"Tom Hart", "Tom", "1850", "male"}, {"John Hart", "John", "1852", "male"}, {"Ellis Hart", "Ellis", "1860", "male"},
                {"Mary Hart", "Mary", "1851", "female"}, {"Ann Hart", "Ann", "", "female"}, {"Hale Hart", "Hale", "1920", "male"}}) {
            if (!p[2].isEmpty()) facts.add(FamilyNameQuestionsTest.fact(p[0], "born-on", p[2], "", reg));
            facts.add(FamilyNameQuestionsTest.fact(p[0], "sex", p[3], "", reg));
            names.add(FamilyNameQuestionsTest.name(p[0], p[0], "Hart", p[1], "birth", p[2], reg));
        }
        facts.add(FamilyNameQuestionsTest.fact("Tom Hart", "occupation", "baker", "", reg));
        facts.add(FamilyNameQuestionsTest.fact("Tom Hart", "lived-in", "Hull", "", reg));
        facts.add(FamilyNameQuestionsTest.fact("John Hart", "occupation", "priest", "", reg));
        FamilyNameQuestionsTest.file(store, "file:///family/register.txt", facts, names);
        FamilyNameQuestionsTest.file(store, "file:///family/record.txt", List.of(FamilyNameQuestionsTest.fact("Tom Hale", "sex", "male", "", "Tom Hale, called Tom Hart.")),
                List.of(FamilyNameQuestionsTest.name("Tom Hale", "Tom Hart", "Hart", "Tom", "", "", "Tom Hale, called Tom Hart.")));
        FamilyOneWordNamesTest.told(store, "letters.txt", "an old servant of the Hart family", "occupation", "gardener", "An old servant of the Hart family kept the garden.");
        FamilyNameQuestionsTest.file(store, "file:///family/port-book.txt", List.of(FamilyNameQuestionsTest.fact("John Hart", "life-event", "preached at the harbour chapel", "1904", "John Hart preached at the harbour chapel in 1904.")), List.of());
        FamilyNameQuestionsTest.file(store, "file:///family/port-book.txt", List.of(
                FamilyNameQuestionsTest.fact("Hart", "occupation", "baker", "1905", BAKERY), FamilyNameQuestionsTest.fact("Hart", "lived-in", "Hull", "1905", BAKERY),
                FamilyNameQuestionsTest.fact("Hart", "life-event", "sold his bread at the market", "1906", MARKET),
                FamilyNameQuestionsTest.fact("Hart", "occupation", "miller", "1911", OLD)), List.of());
        FamilyNameQuestionsTest.file(store, "file:///family/mill-book.txt", List.of(FamilyNameQuestionsTest.fact("Hart", "life-event", "rented the mill at York", "", RENT)), List.of());
        return store;
    }

    static List<FamilyNameQuestions.Question> aboutTheEntry(LibraryStore store) throws Exception {
        String hart = FamilyPeople.view(store).nodeIdOf("Hart");
        return FamilyNameQuestions.open(store).stream().filter(q -> q.kind().equals("family-name-alone") && q.people().contains(hart)).toList();
    }

    static FamilyNameQuestions.Question saying(List<FamilyNameQuestions.Question> qs, String words) {
        return qs.stream().filter(q -> q.text().contains(words)).findFirst().orElseThrow(() -> new AssertionError("no question says " + words + ": " + texts(qs)));
    }

    static List<String> offeredPeople(FamilyNameQuestions.Question q) {
        return q.options().stream().filter(o -> o.key().matches("c\\d+")).map(o -> o.says().replaceFirst(" \\(.*$", "")).toList();
    }

    @Test
    void oneQuestionForEachSourceAndWrittenFormWithOnlyThePeopleTheWordsDoNotContradictTheLikeliestFirst(@TempDir Path tmp) throws Exception {
        LibraryStore store = groups(tmp);
        List<FamilyNameQuestions.Question> qs = aboutTheEntry(store);
        assertEquals(3, qs.size(), "port-book.txt's Mr. Hart, port-book.txt's old Hart, mill-book.txt's Mr. Hart: " + texts(qs));
        FamilyNameQuestions.Question port = saying(qs, "In port-book.txt, “Mr. Hart” is written in 2 passages: “" + BAKERY + "” and “" + MARKET + "”");
        saying(qs, "In port-book.txt, “old Hart” is written in 1 passage: “" + OLD + "”");
        saying(qs, "In mill-book.txt, “Mr. Hart” is written in 1 passage: “" + RENT + "”");
        assertTrue(port.text().endsWith("Who is he?"), "Mr. says a man: " + port.text());
        Set<String> portClaims = new TreeSet<>(passage(store, BAKERY)); portClaims.addAll(passage(store, MARKET));
        assertEquals(portClaims, new TreeSet<>(port.findings()), "the question is about both passages' claims");

        // only the people the words do not contradict: no woman for "Mr.", nobody born after 1905, no described servant
        List<String> people = offeredPeople(port);
        for (String no : List.of("Mary Hart", "Ann Hart", "Hale Hart", "an old servant of the Hart family")) assertFalse(people.contains(no), no + " is left out: " + people);
        assertTrue(port.text().contains("other people of the name are left out, because their years or their sex do not fit"), port.text());
        // the likeliest first: John Hart, whom the same book names; then Tom Hart, a baker of Hull as the words say; then the other men, by name
        assertEquals(List.of("John Hart", "Tom Hart", "Ellis Hart", "Tom Hale"), people, port.options().toString());
        assertEquals(List.of("c1", "c2", "c3", "c4", FamilyNameQuestions.SOMEONE, "person", "family", "split", "later"), port.options().stream().map(FamilyNameQuestions.Option::key).toList());
        assertEquals("Someone else in my library — I will type the name", port.options().get(4).says());
        assertEquals("They are not all the same person", port.options().get(7).says());

        // the answer moves the claims of both passages, and taking it back moves them all back
        String tom = FamilyPeople.view(store).nodeIdOf("Tom Hart"), hart = FamilyPeople.view(store).nodeIdOf("Hart");
        Set<String> tomsOwn = FamilyOneWordNamesTest.claimsOf(FamilyPeople.view(store), tom);
        FamilyNameQuestions.answer(store, port.code(), "c2", "", "Mary");
        Set<String> tomsNow = new TreeSet<>(tomsOwn); tomsNow.addAll(portClaims);
        assertEquals(tomsNow, FamilyOneWordNamesTest.claimsOf(FamilyPeople.view(store), tom), "both passages are about Tom Hart");
        assertTrue(Collections.disjoint(portClaims, FamilyOneWordNamesTest.claimsOf(FamilyPeople.view(store), hart)), "and none of them about the entry");
        FamilyNameQuestions.reopen(store, port.code());
        assertEquals(tomsOwn, FamilyOneWordNamesTest.claimsOf(FamilyPeople.view(store), tom), "taken back, Tom Hart is as he was");
        assertTrue(FamilyOneWordNamesTest.claimsOf(FamilyPeople.view(store), hart).containsAll(portClaims), "and both passages are about the entry again");
        assertTrue(aboutTheEntry(store).stream().anyMatch(q -> q.code().equals(port.code())), "and the question is asked again");
    }

    @Test
    void theFamilyCanSayThePassagesAreNotAllOnePersonAndTakeThatBack(@TempDir Path tmp) throws Exception {
        LibraryStore store = groups(tmp);
        FamilyNameQuestions.Question port = saying(aboutTheEntry(store), "In port-book.txt, “Mr. Hart” is written in 2 passages");
        String said = FamilyNameQuestions.answer(store, port.code(), "split", "", "Mary");
        assertTrue(said.contains("is asked about on its own"), said);
        List<FamilyNameQuestions.Question> now = aboutTheEntry(store);
        assertTrue(now.stream().noneMatch(q -> q.code().equals(port.code())), "the question about both passages is answered: " + texts(now));
        FamilyNameQuestions.Question bakery = saying(now, "In port-book.txt, “Mr. Hart” is written in 1 passage: “" + BAKERY + "”");
        FamilyNameQuestions.Question market = saying(now, "In port-book.txt, “Mr. Hart” is written in 1 passage: “" + MARKET + "”");
        assertTrue(bakery.text().contains("You said the passages of port-book.txt that write “Mr. Hart” are not all one person"), bakery.text());
        assertTrue(bakery.options().stream().noneMatch(o -> o.key().equals("split")), "one passage is not split again");
        assertEquals(passage(store, MARKET), new TreeSet<>(market.findings()));

        // one passage answered on its own moves its claims alone
        String tom = FamilyPeople.view(store).nodeIdOf("Tom Hart");
        FamilyNameQuestions.answer(store, bakery.code(), bakery.options().stream().filter(o -> o.says().startsWith("Tom Hart")).findFirst().orElseThrow().key(), "", "Mary");
        Set<String> toms = FamilyOneWordNamesTest.claimsOf(FamilyPeople.view(store), tom);
        assertTrue(toms.containsAll(passage(store, BAKERY)) && Collections.disjoint(toms, passage(store, MARKET)), "the bakery is Tom Hart's, the market is not: " + toms);

        // taking back the answer about the bakery, and then "not all one person", asks about both passages together again
        FamilyNameQuestions.reopen(store, bakery.code());
        FamilyNameQuestions.reopen(store, port.code());
        assertTrue(aboutTheEntry(store).stream().anyMatch(q -> q.code().equals(port.code())), "the question about both passages is back: " + texts(aboutTheEntry(store)));
    }

    @Test
    void theFamilyCanTypeSomebodyElseInTheLibraryByNameAndANameThatFindsNobodyIsAskedAgain(@TempDir Path tmp) throws Exception {
        LibraryStore store = groups(tmp);
        FamilyNameQuestions.Question mill = saying(aboutTheEntry(store), "In mill-book.txt, “Mr. Hart”");
        IllegalArgumentException nobody = assertThrows(IllegalArgumentException.class, () -> FamilyNameQuestions.answer(store, mill.code(), FamilyNameQuestions.SOMEONE, "", "Mary", "Morita Kenji"));
        assertTrue(nobody.getMessage().contains("Your library has nobody named \"Morita Kenji\""), nobody.getMessage());
        assertThrows(IllegalArgumentException.class, () -> FamilyNameQuestions.answer(store, mill.code(), FamilyNameQuestions.SOMEONE, "", "Mary", "Hart"), "the entry itself is no answer");
        assertTrue(FamilyNameQuestions.asked(store).isEmpty(), "nothing was answered");

        // at a terminal: the number of that answer, a name that finds nobody, then one that does
        int number = mill.options().stream().filter(o -> !o.key().equals("later")).map(FamilyNameQuestions.Option::key).toList().indexOf(FamilyNameQuestions.SOMEONE) + 1;
        ByteArrayOutputStream shown = new ByteArrayOutputStream();
        FamilyNameQuestions.sitting(store, List.of(mill), null, new BufferedReader(new StringReader(number + "\nMorita Kenji\nJohn Hart\nstop\n")), new PrintStream(shown, true, StandardCharsets.UTF_8), "Mary");
        String out = shown.toString(StandardCharsets.UTF_8);
        assertTrue(out.contains("Your library has nobody named \"Morita Kenji\"") && out.contains("Their name: "), "it says so and asks again: " + out);
        String john = FamilyPeople.view(store).nodeIdOf("John Hart");
        Set<String> rent = passage(store, RENT);
        assertTrue(FamilyOneWordNamesTest.claimsOf(FamilyPeople.view(store), john).containsAll(rent), "the mill book's words are about John Hart: " + out);
        FamilyNameQuestions.reopen(store, mill.code());
        assertTrue(Collections.disjoint(FamilyOneWordNamesTest.claimsOf(FamilyPeople.view(store), john), rent), "taken back, they are not");
        assertTrue(FamilyOneWordNamesTest.claimsOf(FamilyPeople.view(store), FamilyPeople.view(store).nodeIdOf("Hart")).containsAll(rent), "they are about the entry again");

        // on the page "Who is who": that answer has a field for the name, and a name that finds nobody is said so
        String page = WhoPage.body(store, Patrons.Patron.PERSON, "", "names-" + mill.code());
        assertTrue(page.contains("<button name=\"choice\" value=\"" + FamilyNameQuestions.SOMEONE + "\">") && page.contains("<input name=\"name\""), page);
        ProtocolError none = assertThrows(ProtocolError.class, () -> FamilyPages.page(store, Patrons.Patron.PERSON, "/who", "POST", Map.of(), Map.of("kind", "names", "code", mill.code(), "choice", FamilyNameQuestions.SOMEONE, "name", "Morita Kenji")));
        assertTrue(none.getMessage().contains("Your library has nobody named \"Morita Kenji\""), none.getMessage());
        FamilyPages.page(store, Patrons.Patron.PERSON, "/who", "POST", Map.of(), Map.of("kind", "names", "code", mill.code(), "choice", FamilyNameQuestions.SOMEONE, "name", "John Hart"));
        assertTrue(FamilyOneWordNamesTest.claimsOf(FamilyPeople.view(store), john).containsAll(rent), "answered from the page");
    }

    @Test
    void whetherTwoEntriesOfOneManAreOnePersonIsAskedBeforeTheQuestionThatOffersThem(@TempDir Path tmp) throws Exception {
        LibraryStore store = groups(tmp);
        Graph g = FamilyPeople.view(store);
        String tom = g.nodeIdOf("Tom Hart"), hale = g.nodeIdOf("Tom Hale");
        FamilyNameQuestions.Question mill = saying(aboutTheEntry(store), "In mill-book.txt, “Mr. Hart”");
        assertTrue(offeredPeople(mill).containsAll(List.of("Tom Hart", "Tom Hale")), "the words give no year: Tom Hale, called Tom Hart, is offered beside him: " + offeredPeople(mill));
        List<FamilyNameQuestions.Question> ordered = FamilyNameQuestions.ordered(FamilyNameQuestions.open(store));
        int pair = -1, pool = -1;
        for (int i = 0; i < ordered.size(); i++) {
            FamilyNameQuestions.Question q = ordered.get(i);
            if (q.kind().equals("one-person") && q.people().containsAll(List.of(tom, hale))) pair = i;
            if (q.code().equals(mill.code())) pool = i;
        }
        assertTrue(pair >= 0 && pair < pool, "one person or two is asked first: " + texts(ordered));
        FamilyNameQuestions.answer(store, ordered.get(pair).code(), "one", "", "Mary");
        List<String> once = offeredPeople(saying(aboutTheEntry(store), "In mill-book.txt, “Mr. Hart”"));
        assertEquals(1, once.stream().filter(x -> x.equals("Tom Hart") || x.equals("Tom Hale")).count(), "then the man is offered once: " + once);
    }

    /**
     * The words of mill-book.txt filed under "Hart" write "Tom Hart" once: that question offers first the two entries your library writes so,
     * Tom Hart and Tom Hale (called Tom Hart), and the same book's "Mr. Hart" offers them first too, since that book names him in full in its
     * words about the entry. Whether those two are one man is asked before either question.
     */
    @Test
    void aPersonTheWordsWriteInFullComesFirstAndSoInTheSameSourcesOtherWords(@TempDir Path tmp) throws Exception {
        LibraryStore store = groups(tmp);
        String took = "Tom Hart took the Hart family's mill at York in 1899.";
        FamilyOneWordNamesTest.told(store, "mill-book.txt", "Hart", "occupation", "miller", took);
        List<FamilyNameQuestions.Question> qs = aboutTheEntry(store);
        FamilyNameQuestions.Question full = saying(qs, "In mill-book.txt, “Tom Hart” is written in 1 passage: “" + took + "”");
        assertEquals(Set.of("Tom Hart", "Tom Hale"), Set.copyOf(offeredPeople(full).subList(0, 2)), full.options().toString());
        FamilyNameQuestions.Question mr = saying(qs, "In mill-book.txt, “Mr. Hart”");
        assertEquals(Set.of("Tom Hart", "Tom Hale"), Set.copyOf(offeredPeople(mr).subList(0, 2)), "the book names him in full: " + mr.options());
        FamilyNameQuestions.Question port = saying(qs, "In port-book.txt, “Mr. Hart” is written in 2 passages");
        assertEquals("John Hart", offeredPeople(port).get(0), "another book's words do not count for port-book.txt: " + port.options());
    }

    /** How words write a person by a family name, in several shapes. */
    @Test
    void theWrittenFormIsTheNameWithItsTitleOrWordOfAgeAndTheSexTheWordsGive() {
        Predicate<String> tomIsAName = w -> w.equals("Tom");
        FamilyNameQuestions.Form scan = FamilyNameQuestions.formIn("The deeds were signed by M r. Hart at the bank.", "Hart", tomIsAName, List.of());
        assertEquals("Mr. Hart", scan.shown(), "a title a scan wrote with a space inside");
        assertEquals("male", scan.sex());
        assertEquals(FamilyNameQuestions.formIn("Mr Hart came.", "Hart", tomIsAName, List.of()).key(), scan.key(), "Mr. and Mr are one form");
        assertEquals("female", FamilyNameQuestions.formIn("Mrs. Hart kept the shop.", "Hart", tomIsAName, List.of()).sex());
        assertEquals("female", FamilyNameQuestions.formIn("Her sister, Sister Hart, taught there.", "Hart", tomIsAName, List.of()).sex());
        FamilyNameQuestions.Form old = FamilyNameQuestions.formIn("In 1911 old Hart still kept the mill.", "Hart", tomIsAName, List.of());
        assertEquals("old Hart", old.shown());
        assertEquals("", old.sex());
        assertNotEquals(old.key(), scan.key());
        assertEquals("old Mr. Hart", FamilyNameQuestions.formIn("They buried old Mr. Hart.", "Hart", tomIsAName, List.of()).shown());
        assertEquals("Hart", FamilyNameQuestions.formIn("In general Hart was kind.", "Hart", tomIsAName, List.of()).shown(), "a title counts written with a capital");
        assertEquals("Hart", FamilyNameQuestions.formIn("When Hart came home, he wrote.", "Hart", tomIsAName, List.of()).shown(), "a word the library knows as no name");
        assertEquals("Hart", FamilyNameQuestions.formIn("Tom Hart's father, Hart, kept the mill.", "Hart", tomIsAName, List.of()).shown(), "the name written alone before a whole name");
        assertEquals("Tom Hart", FamilyNameQuestions.formIn("Tom Hart kept the mill.", "Hart", tomIsAName, List.of()).shown());
        assertEquals("Hart, Tom", FamilyNameQuestions.formIn("Hart, Tom, 12-15", "Hart", tomIsAName, List.of()).shown(), "an index's form");
        assertTrue(FamilyNameQuestions.formIn("The Harts of Leeds kept the mill.", "Hart", tomIsAName, List.of()).family());
        assertTrue(FamilyNameQuestions.formIn("The Hart family kept the mill.", "Hart", tomIsAName, List.of()).family());
        assertEquals("Hart", FamilyNameQuestions.formIn("The Hart family's mill was kept by Hart.", "Hart", tomIsAName, List.of()).shown(), "a person before the family");
        assertNull(FamilyNameQuestions.formIn("He kept the mill.", "Hart", tomIsAName, List.of()));
        // in characters: a rank before, an honorific after, the family, a whole name the library knows
        assertEquals("male", FamilyNameQuestions.formIn("遠藤氏は村の医者だった。", "遠藤", null, List.of()).sex());
        assertEquals("遠藤夫人", FamilyNameQuestions.formIn("遠藤夫人は茶を点てた。", "遠藤", null, List.of()).shown());
        assertEquals("female", FamilyNameQuestions.formIn("遠藤夫人は茶を点てた。", "遠藤", null, List.of()).sex());
        assertEquals("子爵 遠藤", FamilyNameQuestions.formIn("子爵遠藤は京都に住んだ。", "遠藤", null, List.of()).shown());
        assertTrue(FamilyNameQuestions.formIn("遠藤家の墓は広島にある。", "遠藤", null, List.of()).family());
        assertEquals("遠藤健二", FamilyNameQuestions.formIn("遠藤健二は教師だった。", "遠藤", null, List.of("遠藤健二")).shown());
        assertEquals("遠藤", FamilyNameQuestions.formIn("遠藤の子、森田健二。", "遠藤", null, List.of("遠藤健二")).shown());
    }
}
