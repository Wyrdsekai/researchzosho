package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A possible link respects the blocks as a join does ({@link FamilyLinks}): two names that differ by one character are shown as maybe one
 * person only when nothing says they are two, and a block is read as the family's pages read it, the words of a relation for a sex no
 * claim files, and a parent worked out from a brother or sister.
 */
class FamilyLinksPossibleTest {

    private static final String PAGE = "file:///family/register-page.txt";

    private static LibraryStore family(Path tmp) throws Exception {
        LibraryStore s = new LibraryStore(tmp.resolve("lib"));
        s.init();
        FamilyPeople.holdsAFamily(s);
        return s;
    }

    private static void claim(LibraryStore store, String s, String p, String o, String quote) throws Exception {
        String sentence = s + " " + p.replace('-', ' ') + " " + o + ".";
        store.write(new Finding(store.nextFindingId(sentence), sentence, List.of(), Finding.State.draft, Finding.ClaimType.extraction,
                Finding.Confidence.low, "family-account", Instant.now().toString(), LocalDate.now().toString(), Finding.Volatility.stable, "",
                List.of(new Finding.Source(PAGE, "a family paper", "")), List.of(), null, sentence + "\n\nThe account says: \"" + quote + "\"\n",
                new Finding.Triple(s, p, o), List.of()));
    }

    private static boolean maybeOne(LibraryStore store, String a, String b) throws Exception {
        return FamilyLinks.update(store).links().stream().anyMatch(l -> !l.joins()
                && (l.written().equals(a) && l.personLabel().equals(b) || l.written().equals(b) && l.personLabel().equals(a)));
    }

    @Test
    void aManAndAWomanAreNoPossibleLinkWhenTheWordsOfARelationGiveTheSexNoClaimFiles(@TempDir Path tmp) throws Exception {
        LibraryStore control = family(tmp.resolve("control"));
        claim(control, "森田花子", "sex", "female", "森田花子、女。");
        claim(control, "森田花夫", "married-to", "森田春子", "森田花夫と森田春子は昭和五年に結婚した。");
        assertTrue(maybeOne(control, "森田花子", "森田花夫"), "the names differ by one character and nothing says they are two");

        LibraryStore store = family(tmp.resolve("words"));
        claim(store, "森田花子", "sex", "female", "森田花子、女。");
        claim(store, "森田花夫", "married-to", "森田春子", "森田春子の夫 森田花夫。");
        assertFalse(maybeOne(store, "森田花子", "森田花夫"), "森田春子の夫 says 森田花夫 is a man, and 森田花子 is filed as a woman");
    }

    @Test
    void theHusbandOfAWomanFiledAsAWomanIsAManForTheBlocks(@TempDir Path tmp) throws Exception {
        LibraryStore store = family(tmp);
        claim(store, "森田花子", "sex", "female", "森田花子、女。");
        claim(store, "森田花夫", "married-to", "森田春子", "森田花夫と森田春子は昭和五年に結婚した。");
        claim(store, "森田春子", "sex", "female", "森田春子、女。");
        assertFalse(maybeOne(store, "森田花子", "森田花夫"), "married to a woman, 森田花夫 is read as a man, as the pages word a marriage");
    }

    @Test
    void aParentWorkedOutFromABrotherOrSisterIsNoPossibleLink(@TempDir Path tmp) throws Exception {
        LibraryStore control = family(tmp.resolve("control"));
        claim(control, "森田一郎", "child-of", "森田健二", "森田健二の長男 森田一郎。");
        claim(control, "森田健治", "born-on", "1910", "森田健治、明治四十三年生。");
        assertTrue(maybeOne(control, "森田健二", "森田健治"));

        LibraryStore store = family(tmp.resolve("worked"));
        claim(store, "森田一郎", "child-of", "森田健二", "森田健二の長男 森田一郎。");
        claim(store, "森田健治", "sibling-of", "森田一郎", "森田健治は森田一郎の兄弟である。");
        assertFalse(maybeOne(store, "森田健二", "森田健治"), "森田健治 is shown as a child of 森田健二, worked out from his brother: not maybe him");
    }

    @Test
    void aStatedRelationIsNoPossibleLink(@TempDir Path tmp) throws Exception {
        LibraryStore store = family(tmp);
        claim(store, "森田健治", "child-of", "森田健二", "森田健二の子 森田健治。");
        assertFalse(maybeOne(store, "森田健二", "森田健治"), "a father and his son");
    }
}
