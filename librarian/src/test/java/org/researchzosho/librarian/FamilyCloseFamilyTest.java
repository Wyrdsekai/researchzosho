package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.librarian.profiles.GenealogyProfile;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The questions go only to close family: the owner; the owner's parents, brothers and sisters, grandparents and great-grandparents; and the
 * brothers, sisters, children, husbands and wives of any of those. Each question says who its people are to the owner. What is not asked
 * stays on the person's page as not settled. Where the owner's own note and a source disagree, the owner is asked, whoever it concerns.
 */
class FamilyCloseFamilyTest {

    static final String OWNER = "the owner of this library";

    static void file(LibraryStore store, String locator, String teller, List<FamilyAccount.Fact> facts, List<FamilyAccount.NameRead> names) throws Exception {
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), facts, List.of(), List.of(), names, List.of()), teller, f -> List.of(locator), f -> List.of());
    }

    static FamilyAccount.Fact fact(String s, String r, String o, String quote) { return FamilyNameKindFromWordsTest.fact(s, r, o, quote); }

    static FamilyAccount.NameRead name(String person, String name, String family, String given, String quote) { return FamilyNameKindFromWordsTest.name(person, name, family, given, quote); }

    /**
     * The owner's note beside a link: Tom Hale is the owner's great-grandfather. A memoir: Walter Hale, Tom's younger brother, signed as Walter
     * Hall; Arthur Ellis, a grocer the memoir mentions once, signed as Arthur Elliot.
     */
    static LibraryStore hales(Path tmp) throws Exception {
        LibraryStore store = FamilyNameKindFromWordsTest.store(tmp);
        file(store, "told://link-note/https://example.org/tom-hale", OWNER, List.of(fact("Tom Hale", "relative-of", OWNER, "About Tom Hale: my father's father's father")), List.of());
        String brother = "Walter Hale was the younger brother of Tom Hale.";
        file(store, "file:///family/memoir.txt", "an aunt", List.of(fact("Walter Hale", "sibling-of", "Tom Hale", brother), fact("Walter Hale", "sex", "male", brother),
                        fact("Arthur Ellis", "lived-in", "Leeds", "Arthur Ellis, a grocer, lived in Leeds.")),
                List.of(name("Walter Hale", "Walter Hall", "Hall", "Walter", "In Leeds Walter Hale signed his letters Walter Hall."),
                        name("Arthur Ellis", "Arthur Elliot", "Elliot", "Arthur", "Arthur Ellis signed the lease Arthur Elliot.")));
        return store;
    }

    private static List<FamilyNameQuestions.Question> about(List<FamilyNameQuestions.Question> qs, LibraryStore store, String person) throws Exception {
        String id = FamilyPeople.view(store).nodeIdOf(person);
        return qs.stream().filter(q -> q.people().contains(id)).toList();
    }

    @Test
    void theOwnersOwnWordsSayWhereARelativeStands() {
        FamilyClose.Kin k = FamilyClose.chain("About Tom Hale: my great grandfather on my father's side (my father's father's father)");
        assertEquals(List.of(3, 0), List.of(k.up(), k.down()));
        assertEquals("your father's father's father", k.said(), "the words that say most, and \"my father's side\" says only which side");
        assertTrue(k.close());
        assertFalse(FamilyClose.chain("i think this is also my father's cousin").close(), "a father's cousin is not close family");
        assertTrue(FamilyClose.chain("About Shōji Endō: my mother's uncle").close(), "a grandparent's brother is");
        assertTrue(FamilyClose.chain("私の祖父です").close());
        assertNull(FamilyClose.chain("my father-in-law"), "family by marriage is not walked");
        assertNull(FamilyClose.chain("my father, or perhaps my brother"), "words that say two things say nothing");
        assertEquals("your father's father's brother", FamilyClose.described(OWNER + "'s father's father's younger brother").said(), "one plain word a step");
    }

    // one plain word a step, the same way for everybody: no "paternal grandfather", no "parent's child"
    private static final Pattern ONE_STYLE = Pattern.compile("you|your (?:father|mother|parent|son|daughter|child|brother|sister|brother or sister|husband|wife|husband or wife)"
            + "(?:'s (?:father|mother|parent|son|daughter|child|brother|sister|brother or sister|husband|wife|husband or wife))*");

    @Test
    void aRelationIsWrittenOneWayAParentsChildIsABrotherOrSisterAndTheSexIsSaidWhereTheLibraryHasIt(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameKindFromWordsTest.store(tmp);
        file(store, "told://link-note/https://example.org/tom-hale", OWNER, List.of(fact("Tom Hale", "relative-of", OWNER, "About Tom Hale: my paternal grandfather")), List.of());
        file(store, "file:///family/memoir.txt", "an aunt", List.of(
                fact("Tom Hale", "child-of", "Sam Hale", "Tom Hale was the son of Sam Hale."), fact("Sam Hale", "sex", "male", "Sam Hale, a miller."),
                fact("Sam Hale", "parent-of", "Ruth Hale", "Sam Hale's daughter Ruth Hale kept the farm."),
                fact("Sam Hale", "parent-of", "Ned Hale", "Ned Hale, a child of Sam Hale, went to sea."), fact("Ned Hale", "sex", "male", "Ned Hale, a sailor."),
                fact("Sam Hale", "parent-of", "Kit Hale", "Sam Hale left the mill to Kit Hale."),
                fact("Sam Hale", "parent-of", "Kit", "Sam Hale left the barn to Kit."),
                fact("Sam Hale", "married-to", "Meg Hale", "Sam Hale married Meg Hale in 1880."),
                fact("Sam Hale", "sibling-of", "Bess Hale", "Sam Hale lived with his sister Bess Hale."),
                fact("Sam Hale", "sibling-of", "Lou Hale", "Hale, Sam (brother): letters, 27")), List.of());
        Graph g = FamilyPeople.view(store);
        FamilyClose.Close close = FamilyClose.of(g);
        assertEquals("your father's father", close.said(g.nodeIdOf("Tom Hale")), "the owner's \"paternal grandfather\" in the one way");
        assertEquals("your father's father's father", close.said(g.nodeIdOf("Sam Hale")));
        assertEquals("your father's father's sister", close.said(g.nodeIdOf("Ruth Hale")), "a parent's daughter is a sister, as the words \"his daughter\" say");
        assertEquals("your father's father's brother", close.said(g.nodeIdOf("Ned Hale")), "a claim of his sex says brother");
        assertEquals("your father's father's brother or sister", close.said(g.nodeIdOf("Kit Hale")), "nothing says which");
        assertEquals("your father's father's father's wife", close.said(g.nodeIdOf("Meg Hale")), "the wife of a man");
        assertEquals("your father's father's father's sister", close.said(g.nodeIdOf("Bess Hale")), "\"his sister Bess Hale\"");
        assertEquals("your father's father's father's brother or sister", close.said(g.nodeIdOf("Lou Hale")), "an index's \"(brother)\" is Sam's word, not Lou's");
        assertEquals("male", FamilyClose.sexOf(Map.of("x", Map.of("male", List.of("F-1", "F-2", "F-3"), "female", List.of("F-4"))), "x"), "most of the claims");
        assertEquals("", FamilyClose.sexOf(Map.of("x", Map.of("male", List.of("F-1"), "female", List.of("F-2"))), "x"), "claims that disagree evenly say nothing");
        for (Graph.Node n : g.nodes()) {
            String said = close.said(n.id());
            if (!said.isEmpty()) assertTrue(ONE_STYLE.matcher(said).matches() && !said.matches(".*parent's (?:son|daughter|child).*"), n.label() + ": " + said);
        }
        assertEquals("your father's mother", FamilyClose.chain("私の父方の祖母").said());
        assertEquals("your mother's father", FamilyClose.chain("About Tom Ellis: my maternal grandfather").said());
        // "Kit", a given name alone in the text that writes Kit Hale in full, is linked to him: no question asks whether they are one
        String kit = g.nodeIdOf("Kit");
        assertTrue(FamilyNameQuestions.open(store).stream().noneMatch(x -> x.kind().equals("one-person") && x.people().contains(kit)));
        // two entries of one relation, in one question, are said once
        assertEquals("Kit and Kit Hale are both your father's father's brother or sister. ",
                FamilyNameQuestions.saidOnce(Map.of("your father's father's brother or sister", List.of("Kit", "Kit Hale"))));
        assertEquals("Ned Hale is your father's father's brother. ", FamilyNameQuestions.saidOnce(Map.of("your father's father's brother", List.of("Ned Hale"))));
    }

    @Test
    void wordsThatAreADescribedRelativesOwnSayWhoThatSideIsAndNothingOfTheOther(@TempDir Path tmp) throws Exception {
        // the owner's note "my mother's grandfather" beside a page about a whole family, filed under the page's first name: the claim ties
        // Ned to the described relative, who is Sam by the owner's other words
        LibraryStore store = FamilyNameKindFromWordsTest.store(tmp);
        file(store, "told://link-note/https://example.org/sam-hale", OWNER, List.of(fact("Sam Hale", "relative-of", OWNER, "About Sam Hale: my mother's grandfather")), List.of());
        Graph.alias(store, "Sam Hale", List.of(OWNER + "'s mother's grandfather"));
        file(store, "told://link-note/https://example.org/hale-family", OWNER, List.of(fact("Ned Hale", "relative-of", OWNER + "'s mother's grandfather",
                "About the Hale family (Ned Hale's tree): my mother's grandfather")), List.of());
        String reg = "Ned Hale was the elder brother of Sam Hale.";
        file(store, "file:///family/register.txt", "a register", List.of(fact("Ned Hale", "sibling-of", "Sam Hale", reg), fact("Ned Hale", "sex", "male", reg), fact("Sam Hale", "sex", "male", reg)), List.of());
        Graph g = FamilyPeople.view(store);
        FamilyClose.Close close = FamilyClose.of(g);
        assertEquals(g.nodeIdOf("Sam Hale"), g.nodeIdOf(OWNER + "'s mother's grandfather"), "the described relative is Sam");
        assertEquals("your mother's parent's father", close.said(g.nodeIdOf("Sam Hale")));
        assertEquals("your mother's parent's father's brother", close.said(g.nodeIdOf("Ned Hale")), "the words are the grandfather's own; Ned is his brother by the register");
    }

    @Test
    void aRelationWrittenBeforeItsChainStandsAtTheChainsEndAndWinsOverALooseWordOrAnOlderDescription(@TempDir Path tmp) throws Exception {
        FamilyClose.Kin k = FamilyClose.chain("keibatsugaku says he is the elder brother of my mother's grandfather");
        assertEquals("your mother's parent's father's brother", k.said(), "the brother of the grandfather, not the grandfather");
        assertEquals("your mother's parent's father's brother", FamilyClose.chain("my mom called him her uncle. he is the elder brother of my mother's grandfather").said(), "the words about him win over \"my mom\"");
        assertEquals("your father's brother's daughter", FamilyClose.chain("the daughter of my father's brother").said());
        // the claims: Sam is the mother's grandfather; Ned is Sam's brother; Ned adopted Kit. The owner's words on Ned: her uncle, and the elder brother
        LibraryStore store = FamilyNameKindFromWordsTest.store(tmp);
        file(store, "told://link-note/https://example.org/sam-hale", OWNER, List.of(fact("Sam Hale", "relative-of", OWNER, "About Sam Hale: my mother's grandfather")), List.of());
        file(store, "told://link-note/https://example.org/ned-hale", OWNER, List.of(fact("Ned Hale", "relative-of", OWNER,
                "About Ned Hale: my mom called him her uncle. the register says he is the elder brother of my mother's grandfather")), List.of());
        String reg = "Ned Hale was the elder brother of Sam Hale.", adopted = "Ned Hale adopted Kit Hale as his son in 1900.";
        file(store, "file:///family/register.txt", "a register", List.of(fact("Ned Hale", "sibling-of", "Sam Hale", reg), fact("Kit Hale", "adopted-by", "Ned Hale", adopted),
                fact("Ned Hale", "sex", "male", reg), fact("Sam Hale", "sex", "male", reg)), List.of());
        Graph g = FamilyPeople.view(store);
        FamilyClose.Close close = FamilyClose.of(g);
        assertEquals("your mother's parent's father", close.said(g.nodeIdOf("Sam Hale")));
        assertEquals("your mother's parent's father's brother", close.said(g.nodeIdOf("Ned Hale")), "at the end of the chain as written");
        assertEquals("your mother's parent's father's brother's son", close.said(g.nodeIdOf("Kit Hale")), "his son follows from it");
        // an older description of the grandfather, joined into Ned as an other name, does not outweigh the owner's words in the claim
        Graph.alias(store, "Ned Hale", List.of(OWNER + "'s mother's grandfather"));
        assertEquals("your mother's parent's father's brother", FamilyClose.of(FamilyPeople.view(store)).said(FamilyPeople.view(store).nodeIdOf("Ned Hale")));
        // a loose word alone: the exact path the claims give wins over it
        LibraryStore loose = FamilyNameKindFromWordsTest.store(tmp.resolve("loose"));
        file(loose, "told://link-note/https://example.org/sam-hale", OWNER, List.of(fact("Sam Hale", "relative-of", OWNER, "About Sam Hale: my mother's grandfather")), List.of());
        file(loose, "told://link-note/https://example.org/ned-hale", OWNER, List.of(fact("Ned Hale", "relative-of", OWNER, "About Ned Hale: my uncle")), List.of());
        file(loose, "file:///family/register.txt", "a register", List.of(fact("Ned Hale", "sibling-of", "Sam Hale", reg), fact("Ned Hale", "sex", "male", reg)), List.of());
        Graph lg = FamilyPeople.view(loose);
        assertEquals("your mother's parent's father's brother", FamilyClose.of(lg).said(lg.nodeIdOf("Ned Hale")), "\"my uncle\" is said of a great-uncle too: the claims decide");
        // a path of another shape does not say the word more exactly: "my father's cousin" stands over a path through a marriage claim
        LibraryStore cousin = FamilyNameKindFromWordsTest.store(tmp.resolve("cousin"));
        file(cousin, "told://link-note/https://example.org/tom-hale", OWNER, List.of(fact("Tom Hale", "relative-of", OWNER, "About Tom Hale: my father's father")), List.of());
        file(cousin, "told://link-note/https://example.org/may-wood", OWNER, List.of(fact("May Wood", "relative-of", OWNER, "About May Wood: my father's cousin")), List.of());
        String tree = "Tom Hale married Bess Hale; May Wood is a child of Bess Hale.";
        file(cousin, "https://www.geni.com/people/Bess-Hale/1", "a tree", List.of(fact("Tom Hale", "married-to", "Bess Hale", tree), fact("May Wood", "child-of", "Bess Hale", tree), fact("Tom Hale", "sex", "male", tree)), List.of());
        Graph cg = FamilyPeople.view(cousin);
        assertEquals("your father's parent's brother or sister's child", FamilyClose.of(cg).said(cg.nodeIdOf("May Wood")), "a wife's child is no cousin's path: the word stands");
        assertFalse(FamilyClose.of(cg).close(cg.nodeIdOf("May Wood")));
        // and where the claims give no path, the loose word stands
        LibraryStore alone = FamilyNameKindFromWordsTest.store(tmp.resolve("alone"));
        file(alone, "told://link-note/https://example.org/ned-hale", OWNER, List.of(fact("Ned Hale", "relative-of", OWNER, "About Ned Hale: my uncle")), List.of());
        Graph ag = FamilyPeople.view(alone);
        assertEquals("your parent's brother", FamilyClose.of(ag).said(ag.nodeIdOf("Ned Hale")));
    }

    @Test
    void aGreatGrandparentsBrotherIsAskedAboutAndTheQuestionSaysWhoHeIs(@TempDir Path tmp) throws Exception {
        LibraryStore store = hales(tmp);
        Graph g = FamilyPeople.view(store);
        FamilyClose.Close close = FamilyClose.of(g);
        assertTrue(close.known());
        assertTrue(close.close(g.nodeIdOf("Walter Hale")));
        assertEquals("your father's father's father's brother", close.said(g.nodeIdOf("Walter Hale")));
        List<FamilyNameQuestions.Question> walter = about(FamilyNameQuestions.open(store), store, "Walter Hale");
        assertEquals(1, walter.size(), walter.toString());
        assertTrue(walter.get(0).text().startsWith("Walter Hale is your father's father's father's brother. "), walter.get(0).text());
    }

    @Test
    void aManABookMentionsOnceIsNotAskedAboutAndHisPageSaysWhatIsNotSettled(@TempDir Path tmp) throws Exception {
        LibraryStore store = hales(tmp);
        assertEquals(List.of(), about(FamilyNameQuestions.open(store), store, "Arthur Ellis"), "nobody is asked about a grocer the memoir mentions");
        String arthur = FamilyPeople.view(store).nodeIdOf("Arthur Ellis");
        List<FamilyNameQuestions.Question> kept = FamilyNameQuestions.notAsked(store, Set.of(arthur));
        assertEquals(1, kept.size(), kept.toString());
        assertTrue(kept.get(0).text().contains("Arthur Elliot"), kept.get(0).text());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        FamilyNamePages.cliNames(store, "Arthur Ellis", new PrintStream(out, true, StandardCharsets.UTF_8));
        String page = out.toString(StandardCharsets.UTF_8);
        assertTrue(page.contains(FamilyNamePages.NOT_SETTLED) && page.contains("Arthur Elliot"), page);
        assertTrue(page.contains("because Arthur Ellis is outside your close family"), "each thing held back names whom it is held back for: " + page);
        // on another person's page, a question held back for somebody else names that somebody, never "this person"
        Graph g = FamilyPeople.view(store);
        FamilyNameQuestions.Question other = new FamilyNameQuestions.Question("abc123", "name-change-how", List.of(arthur, g.nodeIdOf("Walter Hale")), "How did he come by the name?", List.of(), List.of());
        assertEquals("Arthur Ellis is", FamilyNameQuestions.outsideSaid(g, other));
        assertEquals(1, FamilyNameQuestions.byName(store, Set.of(arthur)).size(), "the family can still ask about him by name");
        assertTrue(FamilyNameQuestions.notAskedSaid(1).contains("researchzosho genealogy names"), "the list says where it is shown");
    }

    @Test
    void onceTheOwnersEntryIsJoinedIntoTheOwnersNameTheWalkStartsThereAndReadsTheOwnersNotes(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameKindFromWordsTest.store(tmp);
        file(store, "told://link-note/https://example.org/walter-hale", OWNER, List.of(fact("Walter Hale", "relative-of", OWNER, "About Walter Hale: my father's father's brother")), List.of());
        file(store, "file:///family/notes.txt", "the writer of notes.txt", List.of(fact("Ann Hale", "born-on", "1970", "i am Ann Hale, born 1970"),
                fact("Jim Hale", "relative-of", "Ann Hale", "Jim Hale is my father's brother")),
                List.of(name("Jim Hale", "Jim Hall", "Hall", "Jim", "Jim Hale signed his letters Jim Hall.")));
        String jim = FamilyPeople.view(store).nodeIdOf("Jim Hale");
        assertFalse(FamilyClose.of(FamilyPeople.view(store)).close(jim), "while the notes' writer is not known to be the owner, their words are not the owner's");
        assertEquals(List.of(), about(FamilyNameQuestions.open(store), store, "Jim Hale"));
        // the linking joins the owner's entry into the person the notes say they are
        Graph.merge(store, OWNER, "Ann Hale", "person", "the notes say: i am Ann Hale", new GenealogyProfile());
        Graph g = FamilyPeople.view(store);
        FamilyClose.Close close = FamilyClose.of(g);
        assertEquals("you", close.said(g.nodeIdOf("Ann Hale")));
        assertEquals("your father's brother", close.said(g.nodeIdOf("Jim Hale")), "the notes are the owner's own now");
        assertEquals("your father's father's brother", close.said(g.nodeIdOf("Walter Hale")));
        assertEquals(1, about(FamilyNameQuestions.open(store), store, "Jim Hale").size());
    }

    @Test
    void withNoOwnerTheFamilysOwnFilesNameCloseFamily(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameKindFromWordsTest.store(tmp);
        file(store, "file:///family/tree.txt", "an aunt", List.of(fact("Ruth Hart", "lived-in", "York", "Ruth Hart lived in York.")),
                List.of(name("Ruth Hart", "Ruth Ellis", "Ellis", "Ruth", "Ruth Hart signed her first letters Ruth Ellis.")));
        file(store, "https://example.org/leeds-grocers", "a web page", List.of(fact("Sam Wood", "lived-in", "Leeds", "Sam Wood kept a shop in Leeds.")),
                List.of(name("Sam Wood", "Samuel Woods", "Woods", "Samuel", "Sam Wood signed as Samuel Woods.")));
        List<FamilyNameQuestions.Question> open = FamilyNameQuestions.open(store);
        assertEquals(1, about(open, store, "Ruth Hart").size(), open.toString());
        assertEquals(List.of(), about(open, store, "Sam Wood"), "somebody only a web page names is no family the family's own files name");
    }

    /**
     * The owner's note beside a link makes Lily Wood the daughter of the owner's father's father's younger brother; an obituary names her
     * parents Sam and Nell Wood.
     */
    static LibraryStore lily(Path tmp) throws Exception {
        LibraryStore store = hales(tmp);
        file(store, "told://link-note/https://example.org/lily-wood", OWNER, List.of(fact("Lily Wood", "relative-of", OWNER + "'s father's father's younger brother",
                "lily wood - my father's father's younger brother's daughter")), List.of());
        String born = "Lily was born to Sam and Nell Wood of Leeds.";
        file(store, "file:///family/obituary.txt", "an aunt", List.of(fact("Sam Wood", "parent-of", "Lily Wood", born), fact("Nell Wood", "parent-of", "Lily Wood", born),
                fact("Sam Wood", "sex", "male", "her father Sam Wood, a joiner")), List.of());
        return store;
    }

    private static List<FamilyNameQuestions.Question> notes(LibraryStore store) throws Exception {
        return FamilyNameQuestions.open(store).stream().filter(q -> q.kind().equals("notes-source")).toList();
    }

    @Test
    void whereTheOwnersNoteAndASourceDisagreeTheOwnerIsAskedWithBothPassages(@TempDir Path tmp) throws Exception {
        LibraryStore store = lily(tmp);
        List<FamilyNameQuestions.Question> qs = notes(store);
        assertEquals(1, qs.size(), qs.toString());
        FamilyNameQuestions.Question q = qs.get(0);
        assertTrue(q.text().contains("“lily wood - my father's father's younger brother's daughter”, so your father's father's brother is Lily Wood's father"), q.text());
        assertTrue(q.text().contains("The source, obituary.txt, says: “Lily was born to Sam and Nell Wood of Leeds.”"), q.text());
        assertTrue(q.text().contains("Is Sam Wood the same person as your father's father's brother, or is your note or the source wrong?"), q.text());
        assertEquals(List.of("c1", "notes", "source", "unsure", "later"), q.options().stream().map(FamilyNameQuestions.Option::key).toList());
        // Lily is no close family: the owner is asked all the same, as only the owner can correct the owner's notes
        assertFalse(FamilyClose.of(FamilyPeople.view(store)).close(FamilyPeople.view(store).nodeIdOf("Lily Wood")));
        Finding note = store.scanFindings().findings().stream().filter(f -> f.triple() != null && f.triple().subject().equals("Lily Wood") && f.triple().predicate().equals("relative-of")).findFirst().orElseThrow();
        FamilyNameQuestions.answer(store, q.code(), "notes", "", "Ann");
        assertEquals(Finding.State.disputed, store.finding(note.id()).state());
        assertEquals(List.of(), notes(store), "the note is disputed: nothing disagrees any more");
        FamilyNameQuestions.reopen(store, q.code());
        assertEquals(Finding.State.draft, store.finding(note.id()).state(), "taking the answer back counts the note again");
        assertEquals(1, notes(store).size());
    }

    @Test
    void aNoteASourceAgreesWithAsksNothingAndTwoRelationsBetweenTheSamePeopleAreAsked(@TempDir Path tmp) throws Exception {
        LibraryStore store = hales(tmp);
        file(store, "told://link-note/https://example.org/mary-hale", OWNER, List.of(fact("Tom Hale", "parent-of", "Mary Hale", "About Mary Hale: Tom Hale's daughter")), List.of());
        String census = "Tom Hale, head; Ann Hale, wife; Mary Hale, daughter.";
        file(store, "https://example.org/census-1901", "a census", List.of(fact("Tom Hale", "parent-of", "Mary Hale", census), fact("Ann Hale", "parent-of", "Mary Hale", census)), List.of());
        assertEquals(List.of(), notes(store), "the census names Tom Hale as her father too");
        file(store, "told://link-note/https://example.org/jack-hale", OWNER, List.of(fact("Jack Hale", "parent-of", "Emma Hale", "About Jack Hale: Emma Hale's father")), List.of());
        file(store, "https://example.org/emma-hale", "a page", List.of(fact("Emma Hale", "sibling-of", "Jack Hale", "Emma Hale and her brother Jack Hale ran the shop.")), List.of());
        List<FamilyNameQuestions.Question> qs = notes(store);
        assertEquals(1, qs.size(), qs.toString());
        assertTrue(qs.get(0).text().contains("“About Jack Hale: Emma Hale's father”, so Jack Hale is Emma Hale's father.")
                && qs.get(0).text().contains("“Emma Hale and her brother Jack Hale ran the shop.”, so Jack Hale is Emma Hale's brother. Which is right?"), qs.get(0).text());
        assertEquals(List.of("notes", "source", "unsure", "later"), qs.get(0).options().stream().map(FamilyNameQuestions.Option::key).toList());
    }
}
