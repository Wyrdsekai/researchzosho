package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.librarian.profiles.GenealogyProfile;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Words that speak of somebody else give nobody a parent, a name or a brother. A parent word owned by or naming a third person ("Nora Lindqvist,
 * Tom's daughter"; 子爵 森田勇 二男), a likeness ("like a father to me"), a step to no relative ("my mother's friend") and a party the words
 * neither write nor stand for are no parent claim; two names under 妻： are wives, not brother and sister; a description anchored on the
 * account's I where the words say "her father" is the other party's father; and in the view, a parent and a sibling claim between the same two
 * from one source cannot both stand.
 */
class FamilyWordsOfSomebodyElseTest {

    private static FamilyAccount.Read read(String text, String teller, String answer) {
        return FamilyAccount.read(text, teller, new GenealogyProfile().predicates(), p -> p.contains("Answer with one word") ? "no" : answer);
    }

    private static String fact(String s, String r, String o, String quote) {
        return "{\"subject\": \"" + s + "\", \"relation\": \"" + r + "\", \"object\": \"" + o + "\", \"date\": \"\", \"quote\": \"" + quote + "\"}";
    }

    private static List<String> triples(FamilyAccount.Read r) { return r.facts().stream().filter(f -> !f.relation().equals("sex")).map(f -> f.subject() + " | " + f.relation() + " | " + f.object()).sorted().toList(); }
    private static FamilyAccount.Fact said(String s, String r, String o, String quote) { return new FamilyAccount.Fact(s, r, o, "", quote); }

    @Test
    void aParentWordOwnedByOrNamingAThirdPersonIsNotThePairs() {
        List<String> tom = List.of("Tom Hale"), ann = List.of("Ann Hale"), ken = List.of("Ken Ellis");
        assertEquals("others", FamilyKin.kinds("Nora Lindqvist, Ken Ellis's daughter, recalls how her father lent money to Tom Hale.", tom, ken).instead(), "the daughter is Nora, not Tom");
        assertEquals("", FamilyKin.kinds("Ann Hale, Ken Ellis's daughter, recalls how her father lent money to Tom Hale.", ann, ken).instead(), "the daughter is a party: the words are the pair's");
        assertEquals("adoptive", FamilyKin.kinds("婿養子： 健二 （実業家、 子爵 遠藤勇 二男）", List.of("森田健二", "健二"), List.of("森田勇")).instead(), "二男 is the viscount's, a third person: the words say 婿養子 alone");
        assertEquals("", FamilyKin.kinds("婿養子： 健二 （実業家、 子爵 遠藤勇 二男）").instead(), "with nobody named, 二男 counts as before");
        assertEquals("as-if", FamilyKin.kinds("Ken Ellis was like a father to me.", ken, List.of("Ann Hale")).instead());
        assertEquals("as-if", FamilyKin.kinds("Ken Ellis was a father figure.").instead());
        assertEquals("chain", FamilyKin.kinds("my mother's friend Enid came to stay", List.of("Enid"), ann).instead(), "a friend of the mother: a parent word only as a step to somebody else");
        assertEquals("", FamilyKin.kinds("Her daughter Ann Hale kept her name.", List.of("Ann Hale"), List.of("Ann Hale (born 1941)")).instead(), "two namesakes: whose the word is cannot be told");
        assertEquals(0, FamilyKin.kinds("my mother's friend Enid came to stay", List.of("Enid"), ann).plain());
        assertEquals("", FamilyKin.kinds("Ann Hale's father Tom Hale kept a shop", tom, ann).instead(), "a plain word of the pair changes nothing");
    }

    @Test
    void aParentClaimNeedsTheWordsToSayParentOfThePair() {
        String text = "Nora Lindqvist, Ken Ellis's daughter, recalls how her father lent money to Tom Hale. Ken Ellis was like a father to me. "
                + "My mother's friend Enid Hart came to stay. The shop carried on through the war, with Ned Hart in the kitchen and Mary Ellis managing the books. "
                + "They convinced mother that they were empowered to search the house. Ann Hale's father Tom Hale kept a shop. My father Ken Hale was a printer.";
        FamilyAccount.Read r = read(text, "Ruth Hale", "{\"people\": [{\"name\": \"Ruth Hale\", \"sex\": \"female\"}, {\"name\": \"Nora Lindqvist\", \"sex\": \"female\"}, {\"name\": \"Ken Ellis\", \"sex\": \"male\"}, {\"name\": \"Enid Hart\"}, {\"name\": \"Ned Hart\"}, {\"name\": \"Mary Ellis\"}], \"facts\": ["
                + fact("Ruth Hale", "child-of", "Ken Ellis", "Nora Lindqvist, Ken Ellis's daughter, recalls how her father lent money to Tom Hale.") + ", "
                + fact("Ken Ellis", "parent-of", "Ruth Hale", "Ken Ellis was like a father to me.") + ", "
                + fact("Enid Hart", "parent-of", "Ruth Hale", "My mother's friend Enid Hart came to stay.") + ", "
                + fact("Ned Hart", "parent-of", "Ruth Hale", "The shop carried on through the war, with Ned Hart in the kitchen and Mary Ellis managing the books.") + ", "
                + fact("Ruth Hale", "child-of", "Mary Ellis", "They convinced mother that they were empowered to search the house.") + ", "
                + fact("Tom Hale", "parent-of", "Ann Hale", "Ann Hale's father Tom Hale kept a shop.") + ", "
                + fact("Ken Hale", "parent-of", "Ruth Hale", "My father Ken Hale was a printer.") + "]}");
        assertEquals(List.of("Ken Hale | parent-of | Ruth Hale", "Tom Hale | parent-of | Ann Hale"), triples(r), "the two whose words say parent of the pair");
        List<String> why = r.dropped();
        assertTrue(why.stream().anyMatch(d -> d.contains("Ruth Hale is a child of Ken Ellis") && d.contains("speak of somebody else's parent or child")), why.toString());
        assertTrue(why.stream().anyMatch(d -> d.contains("Ken Ellis is a parent of Ruth Hale") && d.contains("a likeness")), why.toString());
        assertTrue(why.stream().anyMatch(d -> d.contains("Enid Hart is a parent of Ruth Hale") && d.contains("only as a step to somebody else")), why.toString());
        assertTrue(why.stream().anyMatch(d -> d.contains("Ned Hart is a parent of Ruth Hale") && d.contains("do not write Ruth Hale")), why.toString());
        assertTrue(why.stream().anyMatch(d -> d.contains("Ruth Hale is a child of Mary Ellis") && d.contains("do not write")), why.toString());
        // a label quote from an infobox names the page's subject nowhere, and stays
        FamilyAccount.Read label = read("父：森田勇", "a page", "{\"people\": [], \"facts\": [" + fact("森田正一", "child-of", "森田勇", "父：森田勇") + "]}");
        assertEquals(List.of("森田正一 | child-of | 森田勇"), triples(label));
        // his, her: the party the words stand for when nobody else is named before
        FamilyAccount.Read pronoun = read("Tom Hale kept a shop. His son Ned was born in 1930.", "an aunt", "{\"people\": [{\"name\": \"Tom Hale\"}, {\"name\": \"Ned\"}], \"facts\": [" + fact("Tom Hale", "parent-of", "Ned", "His son Ned was born in 1930.") + "]}");
        assertEquals(List.of("Tom Hale | parent-of | Ned"), triples(pronoun));
    }

    @Test
    void aDescriptionAnchoredOnTheAccountsIIsReanchoredWhereTheWordsSayHerFather() {
        String q = "Wilma Hale's name is Wilma, but she is generally known as Rose, after Nell Gwyn, a name given to her by her banker father with socialist ideals.";
        FamilyAccount.Read r = read(q, "Kimie Hale", "{\"people\": [{\"name\": \"Wilma Hale\", \"sex\": \"female\"}], \"facts\": [" + fact("Kimie Hale's father", "parent-of", "Wilma Hale", q) + "]}");
        assertEquals(List.of("Wilma Hale's father | parent-of | Wilma Hale"), triples(r), "her father is Wilma's, not the writer's");
        assertTrue(r.dropped().stream().anyMatch(d -> d.contains(FamilyAccount.ANCHORED) && d.contains("the words say her father, Wilma Hale's")), r.dropped().toString());
        FamilyAccount.Read mine = read("My father took my mother and me to Leeds.", "Kimie Hale", "{\"people\": [], \"facts\": [" + fact("Kimie Hale", "child-of", "Kimie Hale's father", "My father took my mother and me to Leeds.") + "]}");
        assertEquals(List.of("Kimie Hale | child-of | Kimie Hale's father"), triples(mine), "my father stays the writer's");
        // the same words filed the other way round, and with the writer's description on the object
        FamilyAccount.Read up = read(q, "Kimie Hale", "{\"people\": [{\"name\": \"Wilma Hale\", \"sex\": \"female\"}], \"facts\": [" + fact("Wilma Hale", "child-of", "Kimie Hale's father", q) + "]}");
        assertEquals(List.of("Wilma Hale | child-of | Wilma Hale's father"), triples(up), "a description of Wilma's father, never Kimie's");
        // somebody else named before the other party: the words may be that person's, and the description stays as the model gave it, to be judged by its own words
        String two = "Ann Ellis wrote that Wilma Hale was given the name by her banker father.";
        FamilyAccount.Read first = read(two, "Kimie Hale", "{\"people\": [{\"name\": \"Ann Ellis\"}, {\"name\": \"Wilma Hale\"}], \"facts\": [" + fact("Kimie Hale's father", "parent-of", "Wilma Hale", two) + "]}");
        assertTrue(first.facts().stream().noneMatch(f -> f.subject().equals("Wilma Hale's father")), first.facts().toString());
    }

    @Test
    void theViewSetsAsideAParentClaimBetweenTwoWrittenAsHusbandAndWife(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        // a memoir's "her banker father", once filed as the writer's father, whom the links resolve to the husband of the woman the words are about
        String q = "Ann Ellis's name is Enid, a name given to her by her banker father.";
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(said("Kimie Hale", "child-of", "Ned Hale", "My father, Ned Hale, kept the bank."), said("Kimie Hale's father", "parent-of", "Ann Ellis", q),
                said("Ned Hale", "sex", "male", "My father, Ned Hale, kept the bank.")), List.of()), "file:///family/hale-memoir.txt", "Kimie Hale");
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(said("Ned Hale", "married-to", "Ann Ellis", "Ned Hale married Ann Ellis in 1925."), said("Ann Ellis", "sex", "female", "Ned Hale married Ann Ellis in 1925.")), List.of()),
                "https://example.org/hale", "a page");
        Graph g = FamilyPeople.view(store);
        String ned = g.nodeIdOf("Ned Hale"), ann = g.nodeIdOf("Ann Ellis");
        List<Graph.Edge> parent = g.edges().stream().filter(e -> (e.predicate().equals("parent-of") || e.predicate().equals("child-of")) && Graph.pair(e.from(), e.to()).equals(Graph.pair(ned, ann))).toList();
        assertFalse(parent.isEmpty(), "the description resolved to the husband: " + g.edges().stream().map(e -> g.node(e.from()).label() + " " + e.predicate() + " " + g.node(e.to()).label()).toList());
        assertTrue(parent.stream().allMatch(Graph.Edge::disputed), "set aside: " + parent);
        assertTrue(g.edges().stream().anyMatch(e -> e.predicate().equals("married-to") && !e.disputed()), "the marriage stands");
        assertTrue(FamilyDoubts.about(store, g, ann).stream().anyMatch(a -> a.contains(FamilyDoubts.MARRIED_AND_PARENT)), FamilyDoubts.about(store, g, ann).toString());
        assertEquals(1, FamilyDoubts.marriedAndParent(g).size());
        assertEquals(ned, FamilyDoubts.marriedAndParent(g).get(0)[0], "the parent first");
        assertEquals(ann, FamilyDoubts.marriedAndParent(g).get(0)[1]);
        assertTrue(FamilyNameQuestions.about(store, g, Set.of(ned, ann)).stream().noneMatch(x -> x.text().contains("birth") || x.text().contains("adopt")), "no question is raised");
        List<String> notSettled = FamilySummary.of(store, true).notSettled().stream().map(FamilySummary.Line::plain).filter(l -> l.contains(FamilyDoubts.MARRIED_AND_PARENT)).toList();
        assertEquals(1, notSettled.size(), "the summary says so once: " + notSettled);
        assertTrue(notSettled.get(0).contains("Ned Hale") && notSettled.get(0).contains("Ann Ellis"), notSettled.get(0));
        // the plain case, both claims about the two by name and from different sources, and a claim the family accepted stands
        LibraryStore plain = new LibraryStore(tmp.resolve("plain")); plain.init();
        new LibrarianIndex(plain, Embeddings.none()).rebuild();
        FamilyAccount.fileAsRead(plain, new FamilyAccount.Read(List.of(), List.of(said("Tom Hale", "parent-of", "Meg Ellis", "Tom Hale's daughter Meg Ellis.")), List.of()), "file:///family/a.txt", "an aunt");
        FamilyAccount.fileAsRead(plain, new FamilyAccount.Read(List.of(), List.of(said("Tom Hale", "married-to", "Meg Ellis", "Tom Hale married Meg Ellis.")), List.of()), "file:///family/b.txt", "an uncle");
        Graph pg = FamilyPeople.view(plain);
        assertTrue(pg.edges().stream().filter(e -> e.predicate().equals("parent-of")).allMatch(Graph.Edge::disputed));
        new Council(plain).accept(plain.scanFindings().findings().stream().filter(f -> f.triple() != null && f.triple().predicate().equals("parent-of")).findFirst().orElseThrow().id());
        Graph ag = FamilyPeople.view(plain);
        assertTrue(ag.edges().stream().filter(e -> e.predicate().equals("parent-of")).noneMatch(Graph.Edge::disputed), "the family's word stands");
        assertTrue(FamilyDoubts.marriedAndParent(ag).isEmpty());
    }

    @Test
    void twoNamesUnderAWifeLabelAreWivesNotSiblings() {
        String q = "妻：森田ハル（男爵 遠藤勇 三女、遠藤まり（ 遠藤勇 三女）後妻）";
        FamilyAccount.Read r = read(q, "a page", "{\"people\": [], \"facts\": [" + fact("森田ハル", "sibling-of", "遠藤まり", q) + ", " + fact("森田健二", "married-to", "森田ハル", q) + "]}");
        assertEquals(List.of("森田健二 | married-to | 森田ハル"), triples(r));
        assertTrue(r.dropped().stream().anyMatch(d -> d.contains("森田ハル is a brother or sister of 遠藤まり") && d.contains("speak of a wife or a husband")), r.dropped().toString());
        assertTrue(FamilyKin.spousesOnly("妻：ハル、後妻：まり") && !FamilyKin.spousesOnly("長男：正一、長女：まり") && !FamilyKin.spousesOnly("his wife Ann and her brother Tom"));
        assertFalse(FamilyKin.spousesOnly("女：まり（東京、森田勇妻）", List.of("森田健二"), List.of("まり")), "a register's 女 line is a daughter's, whoever she married");
        FamilyAccount.Read register = read("女：まり（東京、森田勇妻）", "a page", "{\"people\": [], \"facts\": [" + fact("森田健二", "sibling-of", "まり", "女：まり（東京、森田勇妻）") + ", " + fact("森田健二", "parent-of", "まり", "女：まり（東京、森田勇妻）") + "]}");
        assertEquals(2, triples(register).size(), "both readings of the line are filed; the family's view sets them aside as one source read two ways: " + register.dropped());
    }

    @Test
    void theViewSetsAsideASiblingClaimWhoseWordsSayWives(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        String q = "妻：森田ハル（男爵 遠藤勇 三女、遠藤まり（ 遠藤勇 三女）後妻）";
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(said("森田ハル", "sibling-of", "遠藤まり", q), said("森田健二", "married-to", "森田ハル", q)), List.of()), "https://ja.example.org/wiki/x", "a page");
        Graph g = FamilyPeople.view(store);
        assertTrue(g.edges().stream().noneMatch(e -> e.predicate().equals("sibling-of") && !e.disputed()));
        assertTrue(FamilyDoubts.about(store, g, g.nodeIdOf("森田ハル")).get(0).contains("speak of a wife or a husband"));
    }

    @Test
    void theViewSetsAsideAParentAndASiblingClaimBetweenTheSameTwoFromOneSource(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(said("森田健二", "parent-of", "森田まり", "女：まり（森田、東京）"), said("森田健二", "sibling-of", "森田まり", "女：まり（森田、東京）"),
                said("森田健二", "parent-of", "森田正一", "長男：正一")), List.of()), "https://example.org/family/morita", "a page");
        Graph g = FamilyPeople.view(store);
        assertEquals(List.of("森田健二 parent-of 森田正一"), g.edges().stream().filter(e -> !e.disputed() && (e.predicate().equals("parent-of") || e.predicate().equals("sibling-of"))).map(e -> g.node(e.from()).label() + " " + e.predicate() + " " + g.node(e.to()).label()).toList());
        List<String> aside = FamilyDoubts.about(store, g, g.nodeIdOf("森田まり"));
        assertEquals(2, aside.size(), aside.toString());
        assertTrue(aside.stream().allMatch(a -> a.contains("the source is read two ways")), aside.toString());
        assertTrue(FamilyNameQuestions.about(store, g, Set.of(g.nodeIdOf("森田健二"), g.nodeIdOf("森田まり"))).stream().noneMatch(q -> q.text().contains("birth") || q.text().contains("adopt")), "no birth-or-adoptive question");
        // the same two from two sources: both stand, and the check shows them
        LibraryStore two = new LibraryStore(tmp.resolve("lib2")); two.init();
        new LibrarianIndex(two, Embeddings.none()).rebuild();
        FamilyAccount.fileAsRead(two, new FamilyAccount.Read(List.of(), List.of(said("森田健二", "parent-of", "森田まり", "女：まり")), List.of()), "https://example.org/a", "a page");
        FamilyAccount.fileAsRead(two, new FamilyAccount.Read(List.of(), List.of(said("森田健二", "sibling-of", "森田まり", "妹のまり")), List.of()), "https://example.org/b", "a page");
        Graph g2 = FamilyPeople.view(two);
        assertTrue(FamilyDoubts.about(two, g2, g2.nodeIdOf("森田まり")).isEmpty());
    }

    @Test
    void inTheViewAPlainWordOfAThirdPersonDoesNotKeepABirthParent(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(said("森田健二", "child-of", "森田勇", "婿養子： 健二 （実業家、 子爵 遠藤勇 二男）"), said("森田健二", "child-of", "遠藤勇", "父：遠藤勇")), List.of()), "https://ja.example.org/wiki/x", "a page");
        Graph g = FamilyPeople.view(store);
        assertEquals(1, FamilyKin.parents(g).get(g.nodeIdOf("森田健二")).size(), "one birth father: the 婿養子 line is read as adoptive");
        assertTrue(FamilyDoubts.readAsAbout(store, g, g.nodeIdOf("森田勇")).get(0).contains("read as adoptive: the words say 婿養子"));
        assertTrue(FamilyNameQuestions.about(store, g, Set.of(g.nodeIdOf("森田健二"), g.nodeIdOf("森田勇"), g.nodeIdOf("遠藤勇"))).stream().noneMatch(q -> q.text().contains("birth")), "no birth-father question");
    }
}
