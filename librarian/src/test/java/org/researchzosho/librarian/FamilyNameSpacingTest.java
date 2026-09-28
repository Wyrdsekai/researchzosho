package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.librarian.profiles.GenealogyProfile;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A name typed without the space the library writes it with is the same name (on the owner's library, 2026-09-24, a typed name found an
 * entry of nodes.md that no fact was about, and the command said the person did not exist). The library holds two entries for one
 * person here: one written without a space, curated, with no fact about it, and one written with a space, whose other name carries every
 * fact. A command takes the entry the facts rest on and says so; the check and the tidy propose joining the two.
 */
class FamilyNameSpacingTest {

    private static FamilyAccount.Fact fact(String s, String r, String o) { return new FamilyAccount.Fact(s, r, o, "", "q"); }

    /** Two entries for 山田太郎: the one without a space holds nothing, the one with a space is reached through "Taro Yamada", which the facts name. */
    static LibraryStore library(Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(fact("Taro Yamada", "born-on", "1890"), fact("Taro Yamada", "died-on", "1960"),
                fact("Taro Yamada", "married-to", "Hana Yamada"), fact("Hana Yamada", "died-on", "1970"), fact("Ichiro Yamada", "child-of", "Taro Yamada"), fact("Ichiro Yamada", "died-on", "1995"),
                fact("Shoichi Takahashi", "born-on", "1908"), fact("Shoichi Takahashi", "died-on", "1990")), List.of()), "file:///family/notes.txt", "an aunt");
        Path nodes = Graph.nodesFile(store);
        List<String> kept = new ArrayList<>();
        for (String l : Files.readAllLines(nodes, StandardCharsets.UTF_8)) if (!l.startsWith("- taro yamada ") && !l.startsWith("- shoichi takahashi ")) kept.add(l);
        kept.add("- 山田太郎 — person: 山田太郎");
        kept.add("- 山田 太郎 — person: 山田 太郎 | also: Taro Yamada, ヤマダ タロウ, T Yamada");
        kept.add("- 髙橋正一 — person: 髙橋正一");
        kept.add("- 高橋 正一 — person: 高橋 正一 | also: Shoichi Takahashi");
        Files.write(nodes, kept, StandardCharsets.UTF_8);
        return store;
    }

    private interface Call { int run() throws Exception; }

    /** What a command printed, both streams, and what it returned. */
    static String[] said(Call c) throws Exception {
        PrintStream out = System.out, err = System.err;
        ByteArrayOutputStream o = new ByteArrayOutputStream(), e = new ByteArrayOutputStream();
        System.setOut(new PrintStream(o, true, StandardCharsets.UTF_8)); System.setErr(new PrintStream(e, true, StandardCharsets.UTF_8));
        int rc;
        try { rc = c.run(); } finally { System.setOut(out); System.setErr(err); }
        return new String[]{o.toString(StandardCharsets.UTF_8) + e.toString(StandardCharsets.UTF_8), String.valueOf(rc)};
    }

    static String[] cli(LibraryStore store, String... words) throws Exception {
        String[] args = new String[words.length + 2];
        args[0] = "researchzosho"; args[1] = "genealogy";
        System.arraycopy(words, 0, args, 2, words.length);
        return said(() -> new GenealogyProfile().cli(store, args));
    }

    @Test
    void aNameTypedWithoutTheSpaceFindsThePersonTheFactsRestOnAndSaysSo(@TempDir Path tmp) throws Exception {
        LibraryStore store = library(tmp);
        FamilyQuestions.Found f = FamilyQuestions.find(store, "山田太郎");
        assertEquals("山田 太郎", f.person(), "the entry the facts rest on, not the empty one: " + f);
        assertTrue(f.note().contains("\"山田太郎\", which no fact is about") && f.note().contains("\"山田 太郎\", which 4 facts are about")
                && f.note().contains("The library takes \"山田 太郎\"") && f.note().contains("researchzosho genealogy tidy"), f.note());
        assertEquals("山田 太郎", FamilyQuestions.find(store, "山田　太郎").person(), "a full-width space is a space");
        assertEquals("山田 太郎", FamilyQuestions.find(store, "ヤマダタロウ").person(), "the reading in kana, written without its space");
        assertEquals("高橋 正一", FamilyQuestions.find(store, "髙橋正一").person(), "the old form of a character and no space, together");
        assertEquals("山田 太郎", FamilyQuestions.find(store, "山田 太郎").person());
        assertEquals("", FamilyQuestions.find(store, "山田 太郎").note(), "typed as the library writes it: nothing to say");
        boolean[] exact = {false};
        assertEquals(List.of("山田 太郎"), FamilyQuestions.meant(store, "山田太郎", exact), "library_who and every other caller of meant");
        assertTrue(exact[0]);
    }

    @Test
    void everyCommandThatTakesAPersonsNameFindsThem(@TempDir Path tmp) throws Exception {
        LibraryStore store = library(tmp);
        String[] research = cli(store, "research", "山田太郎", "--list");
        assertFalse(research[0].contains("does not have a person named"), research[0]);
        assertTrue(research[0].contains("The library takes \"山田 太郎\"") && research[0].contains("Who were the parents of 山田 太郎?"), research[0]);
        String svg = tmp.resolve("tree.svg").toString();
        String[] tree = cli(store, "tree", "山田太郎", "--out", svg);
        assertEquals("0", tree[1], tree[0]);
        assertTrue(tree[0].contains("around 山田 太郎"), tree[0]);
        String[] life = cli(store, "life", "山田太郎");
        assertEquals("0", life[1], life[0]);
        assertTrue(life[0].contains("1890"), life[0]);
        String[] related = cli(store, "related", "山田太郎", "Ichiro Yamada");
        assertEquals("0", related[1], related[0]);
        String[] hold = cli(store, "hold", "山田太郎");
        assertEquals("0", hold[1], hold[0]);
        assertTrue(hold[0].contains("The questions the library asks about 山田 太郎"), hold[0]);
        String[] log = cli(store, "log", "山田太郎");
        assertEquals("0", log[1], log[0]);
        String[] who = cli(store, "who", "山田太郎", "--list");
        assertEquals("0", who[1], who[0]);
        String[] export = cli(store, "export", "山田太郎");
        assertTrue(export[0].contains("1890"), "the person's GEDCOM, not an empty file: " + export[0]);
        String died = store.scanFindings().findings().stream().filter(x -> x.triple() != null && x.triple().subject().equals("Taro Yamada") && x.triple().predicate().equals("died-on")).findFirst().orElseThrow().id();
        String[] split = cli(store, "split", "山田太郎", "--as", "山田太郎 (died 1960)", "--claims", died);
        assertEquals("0", split[1], split[0]);
    }

    @Test
    void theCheckAndTheTidyProposeJoiningTheTwoEntriesAndNeverJoinThemSilently(@TempDir Path tmp) throws Exception {
        LibraryStore store = library(tmp);
        String check = FamilyChecks.forPerson(store, FamilyChecks.check(store));
        assertTrue(check.contains("researchzosho graph merge \"山田太郎\" \"山田 太郎\""), check);
        assertTrue(check.contains("researchzosho graph merge \"髙橋正一\" \"高橋 正一\""), check);
        assertFalse(FamilyQuestions.find(store, "山田太郎").note().isEmpty(), "the check changed nothing: still two entries");
        String[] asked = cli(store, "tidy");
        assertTrue(asked[0].contains("山田 太郎   ←   山田太郎") && asked[0].contains("Nothing was changed"), "without a yes nothing is joined: " + asked[0]);
        assertFalse(FamilyQuestions.find(store, "山田太郎").note().isEmpty(), "still two entries");
        String[] done = cli(store, "tidy", "--yes");
        assertTrue(done[0].contains("Done."), done[0]);
        FamilyQuestions.Found after = FamilyQuestions.find(store, "山田太郎");
        assertEquals("山田 太郎", after.person());
        assertEquals("", after.note(), "one entry now: " + after.note());
        assertFalse(FamilyChecks.forPerson(store, FamilyChecks.check(store)).contains("graph merge \"山田太郎\""), "not proposed again");
    }
}
