package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The class of a source for a dossier (0.6.6): computed from the locator, by the record collection's tier or the host, never asked of the model. */
class SourceClassTest {

    @Test
    void aSitesWhoseAddressCarriesTheSubjectsNameIsItsOwnPublication() {
        SourceClass.Own rz = SourceClass.Own.of(List.of("ResearchZosho"), "organization");
        assertEquals(SourceClass.ownPublication, SourceClass.of("https://researchzosho.org/download/", rz));
        assertEquals(SourceClass.ownPublication, SourceClass.of("https://blog.researchzosho.org/x", rz), "a site of its own under its name");
        assertEquals(SourceClass.thirdParty, SourceClass.of("https://startupscores.com/open-source/researchzosho", rz), "a page about it elsewhere is third-party");
        assertEquals(SourceClass.thirdParty, SourceClass.of("https://researchzosho.org/download/"), "with no subject known, nothing changes");
        assertEquals(SourceClass.ownPublication, SourceClass.of("https://www.newschool.edu/about", SourceClass.Own.of(List.of("The New School"), "organization")));
        assertEquals(SourceClass.ownPublication, SourceClass.of("https://www.harvard.edu/", SourceClass.Own.of(List.of("Harvard University"), "organization")));
        assertEquals(SourceClass.ownPublication, SourceClass.of("https://about.mercari.com/en/", SourceClass.Own.of(List.of("Mercari US"), "organization")));
        assertEquals(SourceClass.ownPublication, SourceClass.of("https://www.bbc.co.uk/news", SourceClass.Own.of(List.of("BBC"), "organization")), "a two-part suffix: the name stands one place further left");
        assertEquals(SourceClass.publicRecord, SourceClass.of("https://www.usa.gov/agencies", SourceClass.Own.of(List.of("USA gov"), "organization")), "a stronger class stays");
        assertEquals(SourceClass.Own.NONE, SourceClass.Own.of(List.of("Jane Doe"), "person"), "a person's own site is not read from a name: a namesake's would pass for theirs");
        assertEquals(SourceClass.thirdParty, SourceClass.of("https://janedoe.com/", SourceClass.Own.of(List.of("Jane Doe"), "person")));
    }

    @Test
    void aRunReadsTheSubjectsOwnSitesFromItsQuestionsOwnWords() {
        SourceClass.Own q = SourceClass.Own.inText("Who is ResearchZosho, and what does the BBC say about The New School?");
        assertTrue(q.owns("https://researchzosho.org/") && q.owns("https://www.bbc.co.uk/x") && q.owns("https://newschool.edu/"), q.labels().toString());
        assertFalse(q.owns("https://whois.com/x"), "small words never make a name");
        assertFalse(q.owns("https://say.com/x") || q.owns("https://doesthe.com/"), q.labels().toString());
        assertEquals(SourceClass.Own.NONE, SourceClass.Own.inText(""));
    }

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
