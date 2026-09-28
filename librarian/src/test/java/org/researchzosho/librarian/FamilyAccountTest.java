package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.librarian.profiles.GenealogyProfile;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.text.Normalizer;
import java.util.ArrayList;
/** A family's own account becomes people and DRAFT claims; only what the text really says is kept, and the dates are worked out by arithmetic. */
class FamilyAccountTest {

    private static final String TEXT = "齋藤清は明治十九年に広島県安芸郡で生まれた。\n\n清は大正三年に山本家の婿養子となり、山本清と名乗った。\n\n"
            + "My grandmother Hana was born in Honolulu in about 1905.";

    private static final String ANSWER = """
            Here is the JSON: {"people": [{"name": "齋藤清", "reading": "さいとう きよし", "also": ["山本清"], "living": false},
              {"name": "Hana", "reading": "", "also": [], "living": false}],
             "facts": [
              {"subject": "齋藤清", "relation": "born-in", "object": "広島県安芸郡", "date": "明治十九年", "quote": "齋藤清は明治十九年に広島県安芸郡で生まれた。"},
              {"subject": "齋藤清", "relation": "adopted-by", "object": "山本家", "date": "大正三年", "quote": "清は大正三年に山本家の婿養子となり"},
              {"subject": "Hana", "relation": "born-in", "object": "Honolulu", "date": "about 1905", "quote": "My grandmother Hana was born in Honolulu in about 1905."},
              {"subject": "齋藤清", "relation": "died-in", "object": "東京", "date": "", "quote": "清は東京で亡くなった。"},
              {"subject": "齋藤清", "relation": "was-friends-with", "object": "田中", "date": "", "quote": "齋藤清は明治十九年に広島県安芸郡で生まれた。"}]}""";

    @Test
    void onlyWhatTheTextSaysIsKeptAndTheRestIsNamed() {
        FamilyAccount.Read read = FamilyAccount.read(TEXT, "Mara", new GenealogyProfile().predicates(), prompt -> {
            assertTrue(prompt.contains("told by Mara") && prompt.contains("- adopted-by: the subject was adopted by the object") && prompt.contains("THE ACCOUNT:\n齋藤清は"), prompt);
            return ANSWER;
        });
        assertEquals(2, read.people().size());
        assertEquals(3, read.facts().size());
        assertEquals(2, read.dropped().size(), read.dropped().toString());
        assertTrue(read.dropped().get(0).contains("could not find the words in the text that say it. The words it was given were: \"清は東京で亡くなった。\""), "an invented death is left out: " + read.dropped());
        assertTrue(read.dropped().get(1).contains("the library does not have that kind of family relation"));
        assertTrue(FamilyAccount.read(TEXT, "Mara", new GenealogyProfile().predicates(), p -> "I cannot help with that").dropped().get(0).contains("One part of the text could not be read. It begins with:"));
    }

    @Test
    void theClaimsAreDraftsDatedByArithmeticAndANameKeepsItsOtherForms(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        FamilyAccount.Read read = FamilyAccount.read(TEXT, "Mara", new GenealogyProfile().predicates(), p -> ANSWER);
        FamilyAccount.Outcome o = FamilyAccount.file(store, read, "file:///family/account.txt", "Mara's great-aunt");
        assertEquals(3, o.claims());
        assertEquals(0, o.mayBeLiving(), "everyone in this account is placed more than 110 years ago, so nobody is taken for living: about 1905 reaches 1910 at the latest");
        List<Finding> all = store.scanFindings().findings();
        assertTrue(all.stream().allMatch(f -> f.state() == Finding.State.draft && f.writer().equals("family-account") && f.confidence() == Finding.Confidence.low));
        Finding born = all.stream().filter(f -> f.triple().predicate().equals("born-in") && f.triple().subject().equals("齋藤清")).findFirst().orElseThrow();
        assertTrue(born.body().contains("(明治十九年 (1886))") && born.body().contains("The account says: \"齋藤清は明治十九年に"), born.body());
        assertEquals("as told by Mara's great-aunt", born.sources().get(0).edition());
        Finding hana = all.stream().filter(f -> f.triple().subject().equals("Hana")).findFirst().orElseThrow();
        assertTrue(hana.body().contains("(about 1905)"), hana.body());
        String nodes = Files.readString(Graph.nodesFile(store));
        assertTrue(nodes.contains("— person: 齋藤清 | also:") && nodes.contains("— person, private: Hana") == false, "naming a person's other forms keeps them a person: " + nodes);
        Graph g = Graph.build(store);
        assertEquals(g.nodeIdOf("齋藤清"), g.nodeIdOf("斎藤清"), "the modern form of the name is the same person");
        assertEquals(g.nodeIdOf("齋藤清"), g.nodeIdOf("山本清"), "and so is the name he took on adoption");
        assertEquals(g.nodeIdOf("齋藤清"), g.nodeIdOf("さいとう きよし"));
        // 山本家 is a family: an adoption into it is his entry into the family as 婿養子, never an adoption by a person named 山本家
        Graph.Edge entered = g.edges().stream().filter(e -> e.to().equals(g.nodeIdOf("山本家"))).findFirst().orElseThrow();
        assertEquals("member-of", entered.predicate());
        assertTrue(FamilyHouses.isFamily(g, entered.to()));
        assertEquals("mukoyoshi", FamilyHouses.families(FamilyPeople.view(store), g.nodeIdOf("齋藤清")).get(0).how(), "the words say 婿養子");
        // told twice: nothing is filed twice
        assertEquals(0, FamilyAccount.file(store, read, "file:///family/account.txt", "Mara's great-aunt").claims());
        // and a question that names him is now a family-history question, with none of the field's words in it
        assertEquals(List.of("genealogy"), Fields.recognised(store, "山本清 は何の仕事をしていたか"));
    }

    @Test
    void onlyTheFamilyIsFiledAndAParentOfSomeoneLongDeadIsNotTakenForLiving(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        FamilyAccount.Read read = new FamilyAccount.Read(
                List.of(new FamilyAccount.Person("高峰譲吉", "", List.of()), new FamilyAccount.Person("a colleague named in passing", "", List.of())),
                List.of(new FamilyAccount.Fact("高峰譲吉", "born-on", "嘉永7年", "", "q"), new FamilyAccount.Fact("高峰精一", "parent-of", "高峰譲吉", "", "q"),
                        new FamilyAccount.Fact("高峰譲吉", "married-to", "Caroline Hitch", "", "q"), new FamilyAccount.Fact("高峰譲吉", "parent-of", "a son", "", "q")), List.of());
        FamilyAccount.Outcome o = FamilyAccount.file(store, read, "https://ja.wikipedia.org/?curid=30391", "Wikipedia");
        assertEquals(4, o.people(), "the colleague is in no fact and is not filed");
        assertEquals(1, o.mayBeLiving(), "the father (no later than 1854) and the wife (within a lifetime of 1854) are not living; the son, of whom nothing is dated, may be");
        assertFalse(Files.readString(Graph.nodesFile(store)).contains("colleague"));
    }

    @Test
    void aRegisterSaysAThingAcrossTwoLines() {
        String page = "戸主 髙橋源三郎\n明治五年参月拾弐日生\n妻 ツネ 明治拾年生\n長男 正一";
        String flat = Normalizer.normalize(page, Normalizer.Form.NFKC).replaceAll("[\\s\\p{Punct}]+", "");
        assertTrue(FamilyAccount.said(flat, "妻 ツネ 明治拾年生"));
        assertTrue(FamilyAccount.said(flat, "戸主 髙橋源三郎\n妻 ツネ"), "two lines of the page, not next to each other");
        assertTrue(FamilyAccount.said(flat, "戸主 髙橋源三郎 … 長男 正一"));
        assertFalse(FamilyAccount.said(flat, "戸主 髙橋源三郎\n弐女 ハナ"), "one line that is not there sinks it");
        assertFalse(FamilyAccount.said(flat, "ツネは源三郎の妻である"), "a sentence the model wrote itself");
    }

    @Test
    void aBookIsKeptToTheFamilyNamedAndPartsThatNeverNameItAreNotSentToTheModel() {
        String book = "The Takahashi family farmed at Saeki. 髙橋源三郎 married 渡邊ツネ in 1906.\n\n" + "The village council met in spring. Mayor Endo opened the road. ".repeat(140)
                + "\n\n" + "Schoolmaster Endo Kenji married Sato Hana. ".repeat(160) + "\n\nLater 高橋正一 moved to Kure.";
        int[] calls = {0}; List<String> said = new ArrayList<>();
        FamilyAccount.Read read = FamilyAccount.read(book, "a town history", new GenealogyProfile().predicates(), prompt -> {
            calls[0]++;
            return "{\"people\": [], \"facts\": [{\"subject\": \"髙橋源三郎\", \"relation\": \"married-to\", \"object\": \"渡邊ツネ\", \"date\": \"1906\", \"quote\": \"髙橋源三郎 married 渡邊ツネ in 1906.\"},"
                 + " {\"subject\": \"Endo Kenji\", \"relation\": \"married-to\", \"object\": \"Sato Hana\", \"date\": \"\", \"quote\": \"Schoolmaster Endo Kenji married Sato Hana.\"}]}";
        }, List.of("高橋", "Takahashi"), said::add);
        assertTrue(FamilyAccount.pieces(book).size() >= 3 && calls[0] < FamilyAccount.pieces(book).size(), "the parts about the council and the schoolmaster were never sent: " + calls[0] + " of " + FamilyAccount.pieces(book).size());
        assertEquals(1, read.facts().size(), "and the Endo marriage, in a part that was read, is not the family's");
        assertEquals("髙橋源三郎", read.facts().get(0).subject(), "高橋 asked for, 髙橋 written: one name");
        assertTrue(said.get(0).startsWith("reading part 1 of "), said.toString());
    }

    @Test
    void aLongAccountIsReadInPieces() {
        String para = "清は広島で暮らした。".repeat(200);
        List<String> pieces = FamilyAccount.pieces(para + "\n\n" + para + "\n\n" + para);
        assertTrue(pieces.size() >= 2 && pieces.stream().allMatch(p -> p.length() <= FamilyAccount.CHUNK + 2), pieces.size() + " piece(s)");
        assertEquals("齋藤 髙橋 渡邊 → 斎藤 高橋 渡辺", "齋藤 髙橋 渡邊 → " + KanjiForms.modern("齋藤 髙橋 渡邊"));
    }

    private static final String TABLE = "森田 勇\n\n 元首 | \n 大正天皇 | \n 配偶者 | \n森田ふさ | \n 子女 | \n 森田一郎 （長男）\n 森田三郎 （末男） | \n 親族 | \n 森田岳 （玄孫） | \n\n"
            + "森田勇は軍人である。弟の森田実は医師であった。";

    @Test
    void aTableSaysAThingAcrossItsCellsAndTheModelIsAskedOnceWhetherTheJoinedWordsSayIt() {
        List<String> asked = new ArrayList<>();
        FamilyAccount.Read read = FamilyAccount.read(TABLE, "a page", new GenealogyProfile().predicates(), prompt -> {
            if (prompt.startsWith("A reader took these words from a page")) { asked.add(prompt); return prompt.contains("元首") ? "no" : "<think>a label and a name</think>Yes."; }
            return """
                {"people": [], "facts": [
                 {"subject": "森田勇", "relation": "parent-of", "object": "森田三郎", "date": "", "quote": "子女：森田三郎（末男）"},
                 {"subject": "森田勇", "relation": "married-to", "object": "大正天皇", "date": "", "quote": "元首：大正天皇"},
                 {"subject": "森田実", "relation": "brother-of", "object": "森田勇", "date": "", "quote": "弟の森田実は医師であった。"},
                 {"subject": "森田岳", "relation": "great-grandchild-of", "object": "森田勇", "date": "", "quote": "親族：森田岳（玄孫）"},
                 {"subject": "森田勇", "relation": "living", "object": "false", "date": "", "quote": "森田勇は軍人である。"}]}""";
        });
        String kept = read.facts().toString();
        assertTrue(kept.contains("relation=parent-of, object=森田三郎"), "the label and the name stand two lines apart in the table: " + kept + read.dropped());
        assertFalse(kept.contains("大正天皇"), "a head of state is not a wife, and the model said so when asked: " + kept);
        assertTrue(kept.contains("subject=森田実, relation=sibling-of"), "the model's own word for a relation is looked up: " + kept);
        assertTrue(kept.contains("subject=森田岳, relation=relative-of"), kept);
        assertEquals(1, asked.size(), "a sentence copied whole is not asked about, and a kinship label in brackets (末男, 玄孫) answers by itself; the head of state is asked: " + asked);
        assertEquals(1, read.dropped().size(), read.dropped().toString());
        assertNull(FamilyAccount.stitched("子女" + "あ".repeat(900) + "森田三郎", "子女：森田三郎"), "words far apart in the text are not one statement");
    }

    @Test
    void aPartTheModelCouldNotAnswerForIsReadAgainInHalves() {
        String para = "高山正は明治三年に生まれた。".repeat(40);
        String text = para + "\n" + para + "\n高山正は東京で亡くなった。";
        List<Integer> sizes = new ArrayList<>();
        FamilyAccount.Read read = FamilyAccount.read(text, "a page", new GenealogyProfile().predicates(), prompt -> {
            String piece = prompt.substring(prompt.indexOf("THE ACCOUNT:\n") + 13); sizes.add(piece.length());
            if (piece.length() > 700) return "{\"people\": [ … the reply ran out";
            return piece.contains("亡くなった") ? "{\"people\":[],\"facts\":[{\"subject\":\"高山正\",\"relation\":\"died-in\",\"object\":\"東京\",\"date\":\"\",\"quote\":\"高山正は東京で亡くなった。\"}]}" : "{\"people\":[],\"facts\":[]}";
        });
        assertEquals(1, read.facts().size(), sizes + " " + read.dropped());
        assertTrue(read.dropped().isEmpty(), read.dropped().toString());
    }

    private static FamilyAccount.Fact fact(String s, String r, String o, String date) { return new FamilyAccount.Fact(s, r, o, date, "q"); }

    @Test
    void whatCannotBeTrueIsNotFiledWhateverTheModelRead(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        FamilyAccount.file(store, new FamilyAccount.Read(List.of(), List.of(fact("森田勇", "born-on", "1852", ""), fact("森田久", "born-on", "1912", ""), fact("森田久", "child-of", "森田貫", "")), List.of()), "file:///a.txt", "a page");
        FamilyAccount.Outcome o = FamilyAccount.file(store, new FamilyAccount.Read(
                List.of(new FamilyAccount.Person("森田久", "", List.of("Viscount Morita", "the writer of book.epub — the person who keeps this library notes: \"x\""))),
                List.of(fact("森田勇", "child-of", "森田久", ""), fact("森田久", "parent-of", "森田貫", ""), fact("森田久", "parent-of", "森田久", ""), fact("森田久", "child-of", "森田勇", "")), List.of()),
                "file:///b.txt", "the writer of book.epub — the person who keeps this library notes: \"x\"");
        String left = String.join("\n", o.dropped());
        assertEquals(1, o.claims(), left);
        assertTrue(left.contains("\"森田勇 is a child of 森田久\" was left out, because it cannot be true: 森田久 was born in 1912 and 森田勇 in 1852."), left);
        assertTrue(left.contains("\"森田久 is a parent of 森田貫\" was left out, because your library already has it the other way round"), left);
        assertTrue(left.contains("makes a person their own parent"), left);
        Graph g = Graph.build(store);
        // neither the teller nor a title with the family name alone (which strangers share) is another name of his
        assertTrue(!g.node(g.nodeIdOf("森田久")).aliases().contains("Viscount Morita") && g.node(g.nodeIdOf("森田久")).aliases().stream().noneMatch(a -> a.startsWith("the writer of")), g.node(g.nodeIdOf("森田久")).aliases().toString());
    }

    @Test
    void onePersonWrittenSeveralWaysIsFiledOnce(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        FamilyAccount.file(store, new FamilyAccount.Read(List.of(), List.of(fact("Aki Endō", "child-of", "Kenjirō (Hisa) Endō", ""), fact("Kenjirō (Hisa) Endō", "died-on", "1919", "")), List.of()), "https://tree.example.org/1", "a tree site");
        FamilyAccount.Outcome o = FamilyAccount.file(store, new FamilyAccount.Read(List.of(new FamilyAccount.Person("Endo Hisa", "", List.of("Hisa", "Viscount Endo"))),
                List.of(fact("Endo Aki", "child-of", "Endo, Hisa", ""), fact("Endo Hisa", "occupation", "governor", ""), fact("Endo Hisa", "parent-of", "Endo Nori", "")), List.of()), "file:///book.epub", "a book");
        Graph g = Graph.build(store);
        long people = g.nodes().stream().filter(n -> n.kind().equals("person")).count();
        assertEquals(3, people, "the father, Aki and Nori: " + g.nodes().stream().filter(n -> n.kind().equals("person")).map(Graph.Node::label).toList());
        assertEquals(1, o.alreadyHeld(), "Aki is his child was already there, under the other spelling");
        Graph.Node father = g.node(g.nodeIdOf("Kenjirō (Hisa) Endō"));
        assertTrue(father.aliases().contains("Endo Hisa") && !father.aliases().contains("Viscount Endo") && !father.aliases().contains("Hisa") && !father.aliases().contains("Endo"), "a name with a comma in it does not come back as two names of one word, and a title with the family name alone is no other name: " + father.aliases());
        assertTrue(FamilyChecks.check(store).stream().noneMatch(p -> p.text().contains("birth parents")), FamilyChecks.render(FamilyChecks.check(store)));
        assertTrue(FamilyNames.sameByName(g).isEmpty());
    }

    @Test
    void aBirthYearThatMakesSomebodyYoungerThanTheirOwnChildIsFiledAndTheViewSetsTheParentClaimAside(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        FamilyAccount.file(store, new FamilyAccount.Read(List.of(), List.of(fact("森田勇", "parent-of", "森田一郎", ""), fact("森田一郎", "born-on", "1886", "")), List.of()), "https://tree.example.org/1", "a tree site");
        FamilyAccount.Outcome o = FamilyAccount.file(store, new FamilyAccount.Read(List.of(), List.of(fact("森田勇", "born-on", "1889", ""), fact("森田勇", "occupation", "governor", "")), List.of()), "file:///book.epub", "a book");
        assertEquals(2, o.claims(), "a read keeps what the source says: " + o.dropped());
        assertTrue(o.dropped().isEmpty(), o.dropped().toString());
        Graph g = FamilyPeople.view(store);
        assertTrue(FamilyKin.parents(g).isEmpty(), "the view sets the parent claim aside");
        List<String> aside = FamilyDoubts.about(store, g, g.nodeIdOf("森田勇"));
        assertEquals(1, aside.size(), aside.toString());
        assertTrue(aside.get(0).contains("森田勇 is a parent of 森田一郎") && aside.get(0).contains("the years say this is the wrong way round (森田勇 was born in 1889, 森田一郎 in 1886)"), aside.get(0));
    }

    @Test
    void aRelationTheFamilyDisputedStopsNeitherABirthYearNorTheRelationTheOtherWayRound(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        // a book took Tom Hale for the father of Isamu Morita, born 1925, and the family disputed it
        FamilyAccount.file(store, new FamilyAccount.Read(List.of(), List.of(fact("Tom Hale", "parent-of", "Isamu Morita", ""), fact("Isamu Morita", "born-on", "1925", "")), List.of()), "file:///book.epub", "a book");
        Finding wrong = store.scanFindings().findings().stream().filter(f -> f.triple().predicate().equals("parent-of")).findFirst().orElseThrow();
        new Council(store).dispute(wrong.id(), "the book's mistake: Isamu Morita is Tom Hale's father");
        FamilyAccount.Outcome o = FamilyAccount.file(store, new FamilyAccount.Read(List.of(), List.of(fact("Tom Hale", "born-on", "1950", ""), fact("Isamu Morita", "parent-of", "Tom Hale", "")), List.of()), "file:///home/me/notes.txt", "an aunt");
        assertEquals(List.of(), o.dropped());
        assertEquals(2, o.claims());
    }

    @Test
    void aQuoteWithALetterOrTwoMisreadByAScanStillStandsInTheText() {
        String text = "My grandfather was spared internlllent until March 1 942 due to the intervention of the Foreign Office. He left London in 1924.";
        String whole = FamilyAccount.flat(text);
        assertNotNull(FamilyAccount.stitched(whole, "My grandfather was spared internment until March 1942 due to the intervention of the Foreign Office"), "two of thirteen pieces misread by the scan");
        assertNull(FamilyAccount.stitched(whole, "He left London in 1924 and returned again in 1934"), "half a sentence the text does not carry is not a scan's slip");
        assertNotNull(FamilyAccount.stitched(whole, "He left London in 1924"), "a quote the text carries as it is");
        assertNull(FamilyAccount.stitched(whole, "He was interned on the Isle of Man with his brother until the war ended"), "a quote the text does not carry is still a fabrication");
    }

    @Test
    void aKinshipLabelInATableSaysTheRelationWithoutAsking() {
        assertTrue(FamilyAccount.labelled("森田源三郎 （父）"));
        assertTrue(FamilyAccount.labelled("森田岳 （二男）"));
        assertTrue(FamilyAccount.labelled("曾孫・ 森田岳 （衆議院議員）"));
        assertTrue(FamilyAccount.labelled("Spouse: Ann Hale"));
        assertFalse(FamilyAccount.labelled("次代 森田正一"), "a successor in an office is not a relative");
        assertFalse(FamilyAccount.labelled("先代 遠藤源太郎 （陸軍次官）"));
        assertFalse(FamilyAccount.labelled("森田龍 （1966年 - 2006年）"), "years alone say nothing, and the model is asked");
    }

    @Test
    void whoeverTheYearsPutMoreThanALifetimeBackIsNotLivingAcrossTheWholeLibrary(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        // the notes name three generations with no dates; a page later gives the youngest a birth year
        FamilyAccount.file(store, new FamilyAccount.Read(List.of(), List.of(fact("森田勇", "parent-of", "森田一郎", ""), fact("森田一郎", "parent-of", "森田正", ""), fact("森田正", "parent-of", "森田まり", "")), List.of()), "file:///notes.txt", "an aunt");
        Graph g = Graph.build(store);
        assertTrue(g.node(g.nodeIdOf("森田勇")).mayBeLiving(), "with no dates, the safe side");
        FamilyAccount.file(store, new FamilyAccount.Read(List.of(), List.of(fact("森田正", "born-on", "1930", "")), List.of()), "https://ja.example.org/morita", "a page");
        g = Graph.build(store);
        assertFalse(g.node(g.nodeIdOf("森田勇")).mayBeLiving(), "born by 1906: his grandson was born in 1930");
        assertTrue(g.node(g.nodeIdOf("森田一郎")).mayBeLiving(), "born by 1918: within a lifetime, so he may be living");
        assertTrue(g.node(g.nodeIdOf("森田正")).mayBeLiving(), "born 1930: may be living");
        assertTrue(g.node(g.nodeIdOf("森田まり")).mayBeLiving());
    }

    @Test
    void withAJudgeTheTableQuotesAreDecidedByAProbabilityAndTheOneWordAnswerIsNotAsked() {
        List<String> asked = new ArrayList<>(), judged = new ArrayList<>();
        FamilyAccount.Read read = FamilyAccount.read(TABLE, "a page", new GenealogyProfile().predicates(), prompt -> {
            if (prompt.startsWith("A reader took these words from a page")) { asked.add(prompt); return "yes"; }
            return """
                {"people": [], "facts": [
                 {"subject": "森田勇", "relation": "parent-of", "object": "森田三郎", "date": "", "quote": "子女：森田三郎（末男）"},
                 {"subject": "森田勇", "relation": "married-to", "object": "大正天皇", "date": "", "quote": "元首：大正天皇"},
                 {"subject": "森田岳", "relation": "great-grandchild-of", "object": "森田勇", "date": "", "quote": "親族：森田岳（玄孫）"}]}""";
        }, List.of(), null, question -> { judged.add(question); return question.contains("元首") ? 0.08 : question.contains("玄孫") ? 0.58 : 0.97; });
        String kept = read.facts().toString();
        assertTrue(asked.isEmpty(), "the judge decides; nobody is asked for a word: " + asked);
        assertEquals(1, judged.size(), "末男 and 玄孫 are kinship labels and answer by themselves; only the head of state is judged: " + judged);
        assertTrue(kept.contains("object=森田三郎"), kept);
        assertFalse(kept.contains("大正天皇"), "0.08: no");
        assertTrue(kept.contains("subject=森田岳"), kept);
    }

    @Test
    void aDateAlreadyHeldMoreExactlyIsNotFiledAgainCoarser(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        FamilyAccount.file(store, new FamilyAccount.Read(List.of(), List.of(fact("森田勇", "born-on", "1886年8月1日", "")), List.of()), "https://ja.example.org/a", "a page");
        FamilyAccount.Outcome o = FamilyAccount.file(store, new FamilyAccount.Read(List.of(), List.of(fact("森田勇", "born-on", "1886年", ""), fact("森田勇", "born-on", "1886年8月", ""), fact("森田勇", "born-on", "1887年", "")), List.of()), "https://ja.example.org/b", "a page");
        assertEquals(2, o.alreadyHeld(), "1886 and 1886-08 say no more than 1886-08-01");
        assertEquals(1, o.claims(), "1887 is another date, and is filed so that the check can see the conflict");
        assertTrue(FamilyAccount.sameDateCoarser(FamilyDate.parse("1886-08"), FamilyDate.parse("1886-08-01")));
        assertFalse(FamilyAccount.sameDateCoarser(FamilyDate.parse("1886-09"), FamilyDate.parse("1886-08-01")));
    }
    /**
     * f-owner-membership-how-dropped-on-dedupe: the library holds 髙橋勝's membership of the 髙橋 family from 1990 with nothing said of how he
     * came in. A later source says he came in by adoption, as its heir: the held claim gains that source and what it says, as the same
     * source would have filed it on its own.
     */
    @Test
    void aSourceThatSaysHowSomebodyCameInAddsItToTheClaimTheLibraryHolds(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameHistoryTest.store(tmp);
        String book = "In 1990 Masaru became Takahashi Masaru (髙橋勝) and carried on the Takahashi line.";
        String register = "1990年、森田勝は髙橋家の跡取りとして養子に入り、髙橋勝となった。";
        List<FamilyAccount.FamilyRead> takahashi = List.of(new FamilyAccount.FamilyRead("髙橋", "髙橋家", "", register));
        FamilyNameHistoryTest.fileWith(store, "file:///family/book.txt", List.of(new FamilyAccount.Fact("髙橋勝", "member-of", "髙橋家", "1990", book)), List.of(), takahashi);
        FamilyNameHistoryTest.fileWith(store, "file:///family/register.txt",
                List.of(new FamilyAccount.Fact("髙橋勝", "member-of", "髙橋家", "1990", register, Map.of("how", "adoption", "role", "heir"))), List.of(), takahashi);
        List<Finding> member = store.scanFindings().findings().stream().filter(f -> f.triple() != null && f.triple().predicate().equals("member-of")).toList();
        assertEquals(1, member.size(), "one membership, told twice: " + member);
        assertEquals(2, member.get(0).sources().size(), member.get(0).sources().toString());
        assertEquals("adoption", FamilyDetail.get(member.get(0), "how"), "how he came in, as the register says it: " + member.get(0).notes());
        assertEquals("heir", FamilyDetail.get(member.get(0), "role"), member.get(0).notes().toString());
    }
    /**
     * f-prompt-5: the text writes a person only in Latin letters, and the model gives the person in characters with no other spelling. The
     * person is written as the text writes them, the name in the quote whose family word the read ties to that family, and the characters
     * nobody wrote are kept nowhere.
     */
    @Test
    void aPersonGivenInCharactersTheTextNeverWritesIsWrittenAsTheTextDoes() {
        String text = "Endō Shōichi (遠藤正一) was born in 1875 and married Endō Toku in 1899.";
        FamilyAccount.Read r = FamilyReaderNamesTest.read(text + "\n", """
                {"people": [{"name": "遠藤正一", "family": "遠藤", "given": "正一", "also": ["Endō Shōichi"]}, {"name": "遠藤トク", "family": "遠藤", "given": "トク"}],
                 "facts": [{"subject": "遠藤正一", "relation": "married-to", "object": "遠藤トク", "date": "1899", "quote": "%s"}]}
                """.formatted(text));
        assertTrue(r.facts().stream().anyMatch(f -> f.relation().equals("married-to") && f.object().equals("Endō Toku")), r.facts() + " " + r.dropped());
        assertTrue(r.people().stream().noneMatch(p -> p.name().equals("遠藤トク")), r.people().toString());
        assertTrue(r.dropped().contains("遠藤トク is not written in the text, so the library writes this person as the text does: Endō Toku."), r.dropped().toString());
    }
}
