package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.librarian.profiles.GenealogyProfile;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** What a read's names and families become: one claim per name with its reading, the name a value beside the person, the family a family. */
class FamilyNamesFilingTest {

    static final String BIRTH = "健二は1905年に遠藤家に生まれた。";

    static FamilyAccount.Read read(List<FamilyAccount.Person> people, List<FamilyAccount.Fact> facts, List<FamilyAccount.NameRead> names, List<FamilyAccount.FamilyRead> families) {
        return new FamilyAccount.Read(people, facts, List.of(), List.of(), names, families);
    }

    static List<Finding> nameClaims(LibraryStore store) {
        return store.scanFindings().findings().stream().filter(f -> f.triple() != null && f.triple().predicate().equals("has-name")).toList();
    }

    @Test
    void aNameIsOneClaimWithItsReadingAndTheNameIsAValueBesideThePerson(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameHistoryTest.store(tmp);
        FamilyAccount.Outcome o = FamilyAccount.fileAsRead(store, read(List.of(), List.of(new FamilyAccount.Fact("森田健二", "born-in", "広島県安芸郡", "1905", BIRTH)),
                List.of(new FamilyAccount.NameRead("森田健二", "遠藤健二", "遠藤", "健二", List.of("えんどう けんじ", "Endō Kenji"), "birth", "", "1905", BIRTH)), List.of()), "file:///family/book.txt", "an aunt");
        assertEquals(1, o.names());
        assertEquals(2, o.claims(), "the birthplace and the name");
        List<Finding> names = nameClaims(store);
        assertEquals(1, names.size());
        Finding f = names.get(0);
        assertEquals(new Finding.Triple("森田健二", "has-name", "name: 遠藤健二"), f.triple());
        assertEquals("森田健二 was named 遠藤健二 at birth (1905).", f.title());
        assertTrue(f.body().startsWith("森田健二 was named 遠藤健二 at birth (1905).\n\nThe account says: \"" + BIRTH + "\""), f.body());
        assertEquals(1905, FamilyChecks.claimDate(f).year(), "the date stands where the checks read a claim's date");
        Map<String, String> d = FamilyDetail.of(f);
        assertEquals("遠藤", d.get("family"));
        assertEquals("健二", d.get("given"));
        assertEquals("birth", d.get("kind"));
        assertEquals("1905", d.get("from"));
        assertEquals("遠藤健二@ja-Hani,えんどう けんじ@ja-Hira,Endō Kenji@ja-Latn", d.get("forms"));
        assertEquals(Finding.State.draft, f.state());
        assertEquals("family-account", f.writer());
        String nodes = Files.readString(Graph.nodesFile(store));
        assertTrue(nodes.contains("- name: 遠藤健二 — value: name: 遠藤健二"), "the name is a value, never a person: " + nodes);
        Graph g = FamilyPeople.view(store);
        assertEquals("value", g.node(g.nodeIdOf("name: 遠藤健二")).kind());
        assertEquals(g.nodeIdOf("森田健二"), g.nodeIdOf("遠藤健二"), "the name and its forms lead to the person");
        assertEquals(g.nodeIdOf("森田健二"), g.nodeIdOf("Endō Kenji"));
        // the same name read again from another source: the one claim, with both sources
        FamilyAccount.Outcome again = FamilyAccount.fileAsRead(store, read(List.of(), List.of(),
                List.of(new FamilyAccount.NameRead("森田健二", "遠藤健二", "遠藤", "健二", List.of(), "birth", "", "1905", "遠藤健二 明治三十八年生")), List.of()), "file:///family/register.txt", "an uncle");
        assertEquals(0, again.claims());
        assertEquals(1, again.names());
        assertEquals(1, nameClaims(store).size(), "one claim per name");
        assertEquals(2, nameClaims(store).get(0).sources().size(), "with the second source kept");
    }

    @Test
    void aNameThatIsAnotherPersonIsNotKeptAsAnotherNameAndTheReadSaysSo(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameHistoryTest.store(tmp);
        FamilyAccount.fileAsRead(store, read(List.of(), List.of(new FamilyAccount.Fact("遠藤健二", "born-in", "山口", "1880", "遠藤健二は1880年に山口で生まれた。")), List.of(), List.of()), "file:///family/a.txt", "an aunt");
        FamilyAccount.Outcome o = FamilyAccount.fileAsRead(store, read(List.of(), List.of(new FamilyAccount.Fact("森田健二", "born-in", "広島", "1905", BIRTH)),
                List.of(new FamilyAccount.NameRead("森田健二", "遠藤健二", "遠藤", "健二", List.of(), "birth", "", "1905", BIRTH)), List.of()), "file:///family/b.txt", "an aunt");
        Graph g = FamilyPeople.view(store);
        assertNotEquals(g.nodeIdOf("森田健二"), g.nodeIdOf("遠藤健二"), "two entries are joined only by the person's act");
        assertTrue(o.dropped().stream().anyMatch(d -> d.contains("\"遠藤健二\" was not kept as another name of 森田健二")), o.dropped().toString());
        assertEquals(1, nameClaims(store).size(), "the name claim is filed all the same: the record that links them, for the question");
    }

    @Test
    void aNameAmongTheFactsIsFiledAsANameAndAFamilyWithItsSeat(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameHistoryTest.store(tmp);
        String q = "In 1940 Morita Shōji (森田正二) was adopted as heir into the Takahashi family (髙橋家) of 広島県佐伯郡, and became Takahashi Shōji (髙橋正二).";
        FamilyAccount.Outcome o = FamilyAccount.fileAsRead(store, read(List.of(),
                List.of(new FamilyAccount.Fact("森田正二", "has-name", "髙橋正二", "1940", q, Map.of("kind", "adoptive", "family", "髙橋", "given", "正二")),
                        new FamilyAccount.Fact("森田正二", "member-of", "髙橋家", "1940", q, Map.of("how", "adoption", "role", "heir", "from", "1940"))),
                List.of(), List.of(new FamilyAccount.FamilyRead("髙橋", "髙橋家", "広島県佐伯郡", q))), "file:///family/book.txt", "an aunt");
        assertEquals(1, o.names());
        assertEquals(1, o.families());
        Finding name = nameClaims(store).get(0);
        assertEquals("name: 髙橋正二", name.triple().object());
        assertEquals("adoptive", FamilyDetail.get(name, "kind"));
        assertEquals("森田正二 was named 髙橋正二 on adoption (1940).", name.title());
        Graph g = FamilyPeople.view(store);
        String family = g.nodeIdOf("髙橋 family (広島県佐伯郡)");
        assertEquals("family", g.node(family).kind(), "the family, labelled with its seat: " + FamilyHouses.all(g));
        assertEquals(family, g.nodeIdOf("髙橋家"));
        assertEquals("広島県佐伯郡", FamilyHouses.seat(g, family), "its seat is a claim of its own");
        assertTrue(store.scanFindings().findings().stream().anyMatch(f -> f.triple() != null && f.triple().predicate().equals("family-seat") && f.triple().subject().equals("髙橋 family (広島県佐伯郡)")));
        Finding member = store.scanFindings().findings().stream().filter(f -> f.triple() != null && f.triple().predicate().equals("member-of")).findFirst().orElseThrow();
        assertEquals("髙橋 family (広島県佐伯郡)", member.triple().object(), "a membership names the family by its label");
        assertEquals("森田正二 was adopted into 髙橋 family (広島県佐伯郡) as its heir (1940).", member.title());
        assertEquals(List.of(family), FamilyHouses.families(g, g.nodeIdOf("森田正二")).stream().map(FamilyHouses.Membership::family).toList());
    }

    @Test
    void theNewCommandsAnswerInSentences(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameHistoryTest.store(tmp);
        FamilyAccount.fileAsRead(store, read(List.of(), List.of(new FamilyAccount.Fact("森田健二", "member-of", "森田家", "1932", "健二は1932年に森田家に入った。", Map.of("how", "mukoyoshi"))),
                List.of(new FamilyAccount.NameRead("森田健二", "遠藤健二", "遠藤", "健二", List.of(), "birth", "", "1905", BIRTH)), List.of()), "file:///family/book.txt", "an aunt");
        String names = run(store, "names", "森田健二"), families = run(store, "family"), family = run(store, "family", "森田 family"), answered = run(store, "who", "--answered");
        assertTrue(names.startsWith("森田健二 (born 遠藤)") && names.contains("遠藤健二") && names.contains("researchzosho genealogy family \"森田 family\""), names);
        assertTrue(families.contains("森田 family"), families);
        assertTrue(family.contains("森田健二"), family);
        assertTrue(answered.contains("No question about names or families has been answered yet."), answered);
        assertTrue(new GenealogyProfile().usage().contains("genealogy names <person>") && new GenealogyProfile().usage().contains("genealogy family [<family> | <family name>]"));
    }

    static String run(LibraryStore store, String... words) throws Exception {
        String[] args = new String[words.length + 2];
        args[0] = "researchzosho"; args[1] = "genealogy";
        System.arraycopy(words, 0, args, 2, words.length);
        PrintStream was = System.out;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        try { new GenealogyProfile().cli(store, args); } finally { System.setOut(was); }
        return out.toString(StandardCharsets.UTF_8);
    }
}
