package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** A parenthetical that names a site, a wiki page, or an author and year finds its reference. */
class CiteMapTest {
    @Test
    void siteNamesAndWikiPagesMapToTheirReference() {
        List<CiteCheck.Ref> refs = List.of(
                new CiteCheck.Ref(1, "https://www.soumu.go.jp/johotsusintokei/whitepaper/ja/r06/html/nd123.html", "", "令和6年版 情報通信白書"),
                new CiteCheck.Ref(2, "https://de.wikipedia.org/wiki/Wikipedia:Belege", "", "Wikipedia:Belege – Wikipedia"),
                new CiteCheck.Ref(3, "https://the-decoder.de/ki-content-farmen-2026/", "", "KI-Content-Farmen verdoppeln sich"),
                new CiteCheck.Ref(4, "https://the-decoder.de/another-story/", "", "Something else"),
                new CiteCheck.Ref(5, "https://arxiv.org/abs/2401.00001", "Caulfield, M. (2019). SIFT: The Four Moves", "SIFT: The Four Moves"));
        assertEquals(1, CiteCheck.map("soumu.go.jp", refs).n());
        assertEquals(1, CiteCheck.map("総務省 2024, soumu.go.jp", refs).n());
        assertEquals(2, CiteCheck.map("de.wikipedia:Belege", refs).n());
        assertNull(CiteCheck.map("the-decoder.de", refs), "two references on one host: the site name alone cannot choose");
        assertEquals(5, CiteCheck.map("Caulfield 2019", refs).n(), "author and year still map by words");
        assertNull(CiteCheck.map("nothing.example", refs));
    }
}
