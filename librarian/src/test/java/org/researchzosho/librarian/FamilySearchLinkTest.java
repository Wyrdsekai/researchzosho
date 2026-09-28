package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.researchzosho.librarian.profiles.GenealogyProfile;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A search engine's page in the family's link list: the library runs the search itself, and reads a page it finds only when the page's
 * TITLE names the person the search is for. A word of the query in the text is no reason: "family tree" stands in pages about anybody.
 */
class FamilySearchLinkTest {

    private static final List<String> HELD = List.of("森田健二", "Morita Kenji", "遠藤正一");
    private static final List<String> FAMILIES = List.of("森田", "遠藤");

    @Test
    void aResultIsReadOnlyWhenItsTitleNamesThePersonTheSearchIsFor() {
        assertTrue(GenealogyProfile.searchResultAboutFamily("森田家（森田健二の家系図） - 閨閥学", "森田健二 系図", HELD, FAMILIES));
        assertTrue(GenealogyProfile.searchResultAboutFamily("Morita Kenji - Wikipedia", "morita kenji family", HELD, FAMILIES), "case and spacing do not matter");
        // an article about somebody else that happens to carry the query's other word in its text: the title decides, and it names nobody of the family
        assertFalse(GenealogyProfile.searchResultAboutFamily("織田信長 - 维基百科", "森田健二 系図", HELD, FAMILIES));
        assertFalse(GenealogyProfile.searchResultAboutFamily("家系図の書き方", "森田健二 系図", HELD, FAMILIES), "the query's common word in the title is not the person");
    }

    @Test
    void withNoHeldNameInTheQueryAFamilyNameInTheTitleIsEnough() {
        assertTrue(GenealogyProfile.searchResultAboutFamily("遠藤家の歴史", "endo family history", HELD, FAMILIES));
        assertFalse(GenealogyProfile.searchResultAboutFamily("織田信長 - 维基百科", "endo family history", HELD, FAMILIES));
        assertFalse(GenealogyProfile.searchResultAboutFamily("", "森田健二", HELD, FAMILIES), "no title, no read");
    }
}
