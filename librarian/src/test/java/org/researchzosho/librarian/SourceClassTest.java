package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The class of a source for a dossier (0.6.6): computed from the locator, by the record collection's tier or the host, never asked of the model. */
class SourceClassTest {

    @Test
    void aRegisterAFilingACourtAndAGovernmentHostArePublicRecord() {
        for (String l : new String[]{"https://efts.sec.gov/LATEST/search-index?q=x", "https://www.sec.gov/cgi-bin/browse-edgar?action=getcompany&CIK=1", "https://search.gleif.org/#/record/549300683FM6U8ZDR046",
                "https://projects.propublica.org/nonprofits/organizations/43291770", "https://www.courtlistener.com/opinion/1/x/", "https://kokkai.ndl.go.jp/txt/1/22", "https://laws.e-gov.go.jp/law/1/35",
                "https://patents.google.com/patent/US12450652B1/en", "https://www.city.yokohama.lg.jp/x", "https://ndlsearch.ndl.go.jp/books/R100000002-I000000000"})
            assertEquals(SourceClass.publicRecord, SourceClass.of(l), l);
    }

    @Test
    void anAccountOrTheSubjectsOwnCodeIsOwnPublicationAndPressIsThirdParty() {
        assertEquals(SourceClass.ownPublication, SourceClass.of("https://bsky.app/profile/nealstephenson.bsky.social"));
        assertEquals(SourceClass.ownPublication, SourceClass.of("https://qiita.com/someone/items/abc"));
        assertEquals(SourceClass.ownPublication, SourceClass.of("https://github.com/someone/repo"), "the project itself");
        for (String l : new String[]{"https://www.nytimes.com/2024/01/01/x.html", "https://en.wikipedia.org/wiki/X", "https://www.wikidata.org/wiki/Q312740", "https://news.ycombinator.com/item?id=1",
                "https://archive.org/details/some-book", "https://www.loc.gov/resource/sn83030214/1920-01-01/ed-1/seq-1/", "https://example.com/about"})
            assertEquals(SourceClass.thirdParty, SourceClass.of(l), l);
        assertEquals(SourceClass.thirdParty, SourceClass.of(""));
        assertEquals(SourceClass.thirdParty, SourceClass.of("cite:Some Book, 2nd ed."));
    }

    @Test
    void aPageThePersonShelvedIsSuppliedAndAFindingTakesItsStrongestSource() {
        assertEquals(SourceClass.supplied, SourceClass.of("raw/pages/linkedin-export.json"));
        assertEquals(SourceClass.supplied, SourceClass.of("file:///home/someone/cv.pdf"));
        List<Finding.Source> mixed = List.of(new Finding.Source("https://www.nytimes.com/x", "", ""), new Finding.Source("https://www.sec.gov/Archives/edgar/data/1/x.htm", "", ""));
        assertEquals(SourceClass.publicRecord, SourceClass.of(mixed));
        assertEquals(SourceClass.ownPublication, SourceClass.of(List.of(new Finding.Source("https://bsky.app/profile/x", "", ""), new Finding.Source("https://example.com/", "", ""))));
        assertEquals(SourceClass.thirdParty, SourceClass.of(List.<Finding.Source>of()));
        assertEquals("public record", SourceClass.publicRecord.label());
    }
}
