package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Three ways a description of a relative is found ({@link FamilyLinks}), with invented families. "Morita's father", a memoir's "my father"
 * filed with the narrator's family name, is the established father of the one child it has by the claims. "Kenji Morita's brother" that a
 * read named Osamu is the established brother of Kenji Morita whose name has Osamu in it. "森田健二の父の父", a description written in
 * Japanese, walks from 森田健二 as "Kenji Morita's father's father" does.
 */
class FamilyLinksDescriptionsTest {

    private static final String NOTES = "file:///family/notes.txt";
    private static final String REGISTER = "file:///family/register.txt";
    private static final String BOOK = "file:///family/morita-memoir.txt";

    private static LibraryStore family(Path tmp) throws Exception {
        LibraryStore s = new LibraryStore(tmp.resolve("lib"));
        s.init();
        FamilyPeople.holdsAFamily(s);
        return s;
    }

    private static void claim(LibraryStore store, String s, String p, String o, String source) throws Exception {
        String sentence = s + " " + p.replace('-', ' ') + " " + o + ".";
        store.write(new Finding(store.nextFindingId(sentence), sentence, List.of(), Finding.State.draft, Finding.ClaimType.extraction,
                Finding.Confidence.low, "family-account", Instant.now().toString(), LocalDate.now().toString(), Finding.Volatility.stable, "",
                List.of(new Finding.Source(source, "a family paper", "")), List.of(), null, sentence + "\n\nThe account says: \"" + sentence + "\"\n",
                new Finding.Triple(s, p, o), List.of()));
    }

    /** A relation two sources give: established for the walk. */
    private static void established(LibraryStore store, String s, String p, String o) throws Exception { claim(store, s, p, o, REGISTER); claim(store, s, p, o, NOTES); }

    private static String entry(LibraryStore store, String name) throws IOException { return FamilyPeople.view(store).nodeIdOf(name); }

    private static FamilyLinks.Link linkOf(LibraryStore store, String written) throws IOException {
        return FamilyLinks.current(store).links().stream().filter(l -> l.written().equals(written)).findFirst().orElse(null);
    }

    // ── "Morita's father": from a family name alone ─────────────────────────────────────────────────────────────────

    private static LibraryStore moritas(Path tmp, boolean kenjiOneByEvidence) throws Exception {
        LibraryStore store = family(tmp);
        claim(store, "Kenji Morita", "born-on", "1905", REGISTER);
        if (kenjiOneByEvidence) claim(store, "Kenji Morita", "born-on", "1905", BOOK);
        established(store, "Isamu Morita", "parent-of", "Kenji Morita");
        established(store, "Isamu Morita", "sex", "male");
        established(store, "Kenji Morita", "married-to", "Sumiko Morita");
        established(store, "Sumiko Morita", "sex", "female");
        claim(store, "Morita's father", "parent-of", "Kenji Morita", BOOK);
        claim(store, "Morita's father", "occupation", "shopkeeper", BOOK);
        claim(store, "Morita's wife", "married-to", "Kenji Morita", BOOK);
        claim(store, "Morita's wife", "occupation", "bookkeeper", BOOK);
        return store;
    }

    @Test
    void aFamilyNamesFatherIsTheEstablishedFatherOfTheOneChildItHas(@TempDir Path tmp) throws Exception {
        LibraryStore store = moritas(tmp, true);
        assertEquals(entry(store, "Isamu Morita"), entry(store, "Morita's father"), FamilyLinks.current(store).links().stream().map(FamilyLinks::sentence).toList().toString());
        FamilyLinks.Link l = linkOf(store, "Morita's father");
        assertNotNull(l);
        assertEquals(FamilyLinks.Grade.probable, l.grade());
        assertEquals("L3", l.rule());
        assertEquals(entry(store, "Sumiko Morita"), entry(store, "Morita's wife"), "and a family name's wife, from the one husband it has");
    }

    @Test
    void aChildWhoIsNotOnePersonByTheEvidenceLeadsNowhere(@TempDir Path tmp) throws Exception {
        LibraryStore store = moritas(tmp, false);
        assertNotEquals(entry(store, "Isamu Morita"), entry(store, "Morita's father"), "one source's Kenji Morita may be anybody's");
    }

    // ── "Kenji Morita's brother", named Osamu ────────────────────────────────────────────────────────────────────────

    @Test
    void aPlaceholderThatCarriesANameIsTheEstablishedRelativeWhoseNameHasIt(@TempDir Path tmp) throws Exception {
        LibraryStore store = family(tmp);
        claim(store, "Kenji Morita", "born-on", "1905", REGISTER);
        established(store, "Osamu Morita", "sibling-of", "Kenji Morita");
        established(store, "Osamu Morita", "sex", "male");
        established(store, "Kengo Morita", "sibling-of", "Kenji Morita");
        established(store, "Kengo Morita", "sex", "male");
        established(store, "Ichiro Morita", "child-of", "Kenji Morita");
        established(store, "Ichiro Morita", "sex", "male");
        claim(store, "Kenji Morita's brother", "occupation", "sailor", BOOK);
        claim(store, "Kenji Morita's son", "occupation", "clerk", BOOK);
        Graph.alias(store, "Kenji Morita's brother", List.of("Osamu"), BOOK);
        Graph.alias(store, "Kenji Morita's son", List.of("Ichiro"), BOOK);
        assertEquals(entry(store, "Osamu Morita"), entry(store, "Kenji Morita's brother"), "of his two brothers, the one named Osamu: " + FamilyLinks.current(store).links().stream().map(FamilyLinks::sentence).toList());
        assertEquals(FamilyLinks.Grade.probable, linkOf(store, "Kenji Morita's brother").grade());
        assertEquals(entry(store, "Ichiro Morita"), entry(store, "Kenji Morita's son"), "and a son the same");
        assertNotEquals(entry(store, "Kengo Morita"), entry(store, "Kenji Morita's brother"));

        LibraryStore unnamed = family(tmp.resolve("unnamed"));
        established(unnamed, "Osamu Morita", "sibling-of", "Kenji Morita");
        established(unnamed, "Osamu Morita", "sex", "male");
        claim(unnamed, "Kenji Morita's brother", "occupation", "sailor", BOOK);
        assertNotEquals(entry(unnamed, "Osamu Morita"), entry(unnamed, "Kenji Morita's brother"), "a brother with no name carried: the library holding one brother is no sign he is the one");
    }

    // ── 森田健二の父の父: a description in Japanese ──────────────────────────────────────────────────────────────────

    @Test
    void aDescriptionInJapaneseWalksFromTheNamedPersonAsTheEnglishOneDoes(@TempDir Path tmp) throws Exception {
        LibraryStore store = family(tmp);
        established(store, "森田勇", "parent-of", "森田健二");
        established(store, "森田勇", "sex", "male");
        established(store, "森田正一", "parent-of", "森田勇");
        established(store, "森田正一", "sex", "male");
        established(store, "森田ハル", "parent-of", "森田勇");
        established(store, "森田ハル", "sex", "female");
        established(store, "森田健二", "married-to", "森田花子");
        established(store, "森田花子", "sex", "female");
        claim(store, "森田健二の父の父", "occupation", "農業", BOOK);
        claim(store, "森田健二の父方の祖母", "born-in", "京都", BOOK);
        claim(store, "森田健二の妻", "born-in", "神戸", BOOK);
        claim(store, "森田健二の長男", "born-in", "神戸", BOOK);
        assertEquals(List.of("森田健二", "father's father"), List.of(FamilyLinks.description("森田健二の父の父")));
        assertEquals(List.of("森田健二", "father's mother"), List.of(FamilyLinks.description("森田健二の父方の祖母")));
        assertEquals(List.of("森田健二", FamilyLinks.GRAND_PARENT + "'s mother"), List.of(FamilyLinks.description("森田健二の祖母")));
        assertEquals(entry(store, "森田正一"), entry(store, "森田健二の父の父"), FamilyLinks.current(store).links().stream().map(FamilyLinks::sentence).toList().toString());
        assertEquals(FamilyLinks.Grade.proved, linkOf(store, "森田健二の父の父").grade(), "a father's father: one answer by nature");
        assertEquals(entry(store, "森田ハル"), entry(store, "森田健二の父方の祖母"));
        assertEquals(entry(store, "森田花子"), entry(store, "森田健二の妻"));
        assertNotEquals(entry(store, "森田健二"), entry(store, "森田健二の長男"), "a son the library holds none of stays as written");
    }
}
