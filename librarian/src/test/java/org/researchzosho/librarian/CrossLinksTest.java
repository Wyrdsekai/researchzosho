package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Links across dossiers (0.6.6): a name two subjects' claims share, found on the shelves, shown on both dossiers and filed as a question when a report lands. */
class CrossLinksTest {
    static Finding claim(LibraryStore store, String title, String body) throws Exception {
        String id = store.nextFindingId(title);
        Finding f = new Finding(id, title, List.of(), Finding.State.draft, Finding.ClaimType.extraction, Finding.Confidence.medium, "patron:test", Instant.now().toString(), "2026-10-24", Finding.Volatility.slow, "",
                List.of(new Finding.Source("https://a.example/" + id, "n/a", "x")), List.of(), null, body + "\n");
        store.write(f); return f;
    }
    static String report(LibraryStore store, String title) throws Exception { return report(store, title, ""); }
    static String report(LibraryStore store, String title, String more) throws Exception {
        String id = store.nextInvestigationId(title);
        store.write(new Investigation(id, title, Finding.State.accepted, "patron:test", Instant.now().toString(), List.of(), List.of(), "## Question\n\n" + title + "\n\n## Answer\n\nx\n" + more));
        return id;
    }

    @Test
    void aNameTwoDossiersShareIsALinkWithTheClaimsOnEachSideAndAQuestionAtLanding(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        report(store, "Who is Ada Example? A full dossier.");
        String acme = report(store, "Corporate history of Acme Corp.: a full dossier.", "\n## People\n\nJoshua Sample — Co-founder & CEO. Earlier at clypd and at MocoSpace; a Patent holder.\n");
        Finding a = claim(store, "Ada Example, 2009–2011: Principal Architect, WHERE LLC", "Ada Example worked at WHERE LLC in Boston from 2009 to 2011. She holds a Patent and was Founded in nothing.");
        claim(store, "Ada Example, 1999: joined Cisco", "In 1999 Ada Example joined Cisco in Cambridge, later MocoSpace.");
        Finding b = claim(store, "Acme Corp.: its chief executive", "Acme Corp.'s chief executive Joshua Sample was earlier at WHERE LLC and at Xandr.");
        claim(store, "Acme Corp., 2024: seed round", "Acme Corp. raised a seed round in June 2024 in Boston.");
        List<CrossLinks.Link> links = CrossLinks.of(store, "Ada Example");
        assertEquals(List.of("MocoSpace", "WHERE LLC"), links.stream().map(CrossLinks.Link::name).sorted().toList(), "WHERE LLC from the claims, MocoSpace from the report's People section; not Patent, not Founded, not Boston: " + links);
        CrossLinks.Link moco = links.stream().filter(x -> x.name().equals("MocoSpace")).findFirst().orElseThrow();
        assertEquals(List.of(acme), moco.otherClaims(), "a name only the report carries is cited by the report");
        links = links.stream().filter(x -> x.name().equals("WHERE LLC")).toList();
        CrossLinks.Link l = links.get(0);
        assertEquals("WHERE LLC", l.name()); assertEquals("Acme Corp", l.other(), "the subject as the question names it, its trailing stop dropped");
        assertEquals(List.of(a.id()), l.ownClaims()); assertEquals(List.of(b.id()), l.otherClaims());
        assertTrue(links.stream().noneMatch(x -> x.name().equals("Boston")), "a city both name is not a link: everyone is somewhere");
        List<CrossLinks.Link> back = CrossLinks.of(store, "Acme Corp");
        assertEquals(List.of("MocoSpace", "WHERE LLC"), back.stream().map(CrossLinks.Link::name).sorted().toList(), "the same two names from the other side: " + back);
        assertTrue(back.stream().allMatch(x -> x.other().equals("Ada Example")));
        // the dossier carries it
        String md = Dossier.of(store, "Ada Example").path("markdown").asText();
        assertTrue(md.contains("## Shared with other dossiers\n") && md.contains("- WHERE LLC — also in the dossier of Acme Corp [" + a.id() + " | " + b.id() + "]"), md);
        assertEquals(2, Dossier.of(store, "Ada Example").path("links").asInt());
        String html = DossierPage.render(store, "Ada Example");
        assertTrue(html.contains("<h2 id=\"shared\">Shared with other dossiers</h2>") && html.contains("<b>WHERE LLC</b></td><td><a href=\"/dossier?subject=Acme%20Corp\">Acme Corp</a>"), html);
        // at landing, the question
        List<String> filed = CrossLinks.file(store, "Ada Example");
        assertEquals(2, filed.size(), filed.toString());
        assertTrue(filed.contains("Ada Example and Acme Corp both name WHERE LLC (" + a.id() + ", " + b.id() + "): how are they connected?"), filed.toString());
        assertTrue(filed.stream().anyMatch(q -> q.startsWith("Ada Example and Acme Corp both name MocoSpace (") && q.contains(acme)), "the report-only name files its question too, citing the report: " + filed);
        assertEquals(0, CrossLinks.file(store, "Ada Example").size(), "filed once");
        assertTrue(Frontier.read(store).stream().anyMatch(x -> x.open() && x.text().contains("both name WHERE LLC")));
    }

    @Test
    void theRegistersAndPublishersEveryDossierCitesAreNotLinks(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        report(store, "Corporate history of Lown Institute: a full dossier.", "\n## Registry card\n\nEIN 04-3291770; NTEE code H99 per ProPublica, E05 per GuideStar. The IRS issued its exemption in 1996.\n");
        report(store, "Who is James Sample? A full dossier.", "\n## Holdings and filings\n\nTreasurer of Example Fund (Form 990 per ProPublica); the IRS lists it under H99. Earlier at MocoSpace.\n");
        claim(store, "Lown Institute, 1996: IRS issued 501(c)(3) tax exemption", "In January 1996 the IRS issued the Lown Institute its exemption, as ProPublica records.");
        claim(store, "James Sample, 2019: joined MocoSpace", "James Sample joined MocoSpace in 2019; ProPublica lists his fund.");
        claim(store, "Lown Institute, 2023: Kern joined the board", "Daniel Kern, CFA, CFP, joined the board; the Alliance sits at 21 Longwood Ave, Brookline, MA, and Lown died in Chestnut Hill, MA.");
        claim(store, "James Sample, 2000: a dinner speaker at CFP", "James Sample spoke at CFP in Brookline, MA; he is a CFP himself.");
        List<CrossLinks.Link> links = CrossLinks.of(store, "Lown Institute");
        assertTrue(links.stream().noneMatch(x -> x.name().equals("ProPublica") || x.name().equals("GuideStar") || x.name().equals("IRS") || x.name().equals("Form 990")), "where the library looked is not a connection: " + links);
        assertTrue(links.stream().noneMatch(x -> x.name().equals("CFP") || x.name().equals("CFA") || x.name().equals("Brookline") || x.name().equals("Chestnut Hill")), "a credential or a town written as an address is not a connection: " + links);
        claim(store, "Lown Institute, 2026: not on OpenSanctions", "OpenSanctions lists nothing on the Lown Institute; the FDA cleared nothing of theirs.");
        claim(store, "James Sample, 2026: not on OpenSanctions", "OpenSanctions lists nothing on James Sample; the FDA has no record of him.");
        assertTrue(CrossLinks.of(store, "Lown Institute").stream().noneMatch(x -> x.name().equals("OpenSanctions") || x.name().equals("FDA")), "a register or a regulator both answer to is not a connection");
        // Japanese names link, titles stripped; places and generic words do not
        report(store, "高市早苗の経歴と人物像: a full dossier.");
        report(store, "Corporate history of 日本製鉄株式会社: a full dossier.");
        claim(store, "高市早苗, 2024: 日本銀行について", "高市早苗氏は東京都千代田区で日本銀行総裁と会談した。株式会社の設立は1990年。");
        claim(store, "日本製鉄, 2023: 日本銀行の見方", "日本製鉄は日本銀行の政策を東京都で評価した。株式会社として設立。");
        List<CrossLinks.Link> ja = CrossLinks.of(store, "高市早苗");
        assertTrue(ja.stream().anyMatch(x -> x.name().equals("日本銀行") && x.other().equals("日本製鉄株式会社")), "the bank both name, without its title: " + ja);
        assertTrue(ja.stream().noneMatch(x -> x.name().equals("東京都") || x.name().equals("千代田区") || x.name().equals("株式会社") || x.name().endsWith("総裁")), "a place or a generic word is not a link: " + ja);
    }

    @Test
    void aJapanesePlaceIsAPrefectureAWardOrACityByNameOrAMunicipalityAfterItsPrefecture() {
        List<String> p = CrossLinks.placesJa("高市早苗, 1961-03-07: 奈良県天理市に生まれる。2006年に内閣府特命担当大臣、第2次高市内閣。東京都千代田区で日本政府が会見。");
        assertTrue(p.contains("奈良県") && p.contains("天理市") && p.contains("千代田区"), p.toString());
        assertFalse(p.contains("東京都"), "the capital is every dossier's; the ward is the place: " + p);
        assertFalse(p.contains("高市") || p.contains("次高市") || p.contains("内閣府") || p.contains("日本政府"), "a surname that ends in 市 and an office that ends in 府 are no places: " + p);
    }

    @Test
    void aStatusWordInCapitalsAndASourcesOwnNameAreNotLinks(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        report(store, "Who is Ada Example? A full dossier.");
        report(store, "Corporate history of Acme Corp.: a full dossier.");
        claim(store, "Ada Example, 2020: GLEIF entity", "Ada Example's entity is ACTIVE at GLEIF; she is NOT associated with Orin Zatkin. A photograph is on Wikimedia Commons.");
        claim(store, "Acme Corp., 2021: GLEIF entity", "Acme Corp. is ACTIVE at GLEIF and NOT dissolved; its chair came from Orin Zatkin. Its logo is on Wikimedia Commons.");
        assertEquals(Set.of("Orin Zatkin"), CrossLinks.names("Acme Corp. is ACTIVE at GLEIF and NOT dissolved; its chair came from Orin Zatkin. Its logo is on Wikimedia Commons.", "Acme Corp"), "a sentence ends at the dot: the next sentence's first word is not part of the name");
        assertEquals(Set.of("Orin Zatkin"), CrossLinks.names("Ada Example's entity is ACTIVE at GLEIF; she is NOT associated with Orin Zatkin.", "Ada Example"), "a possessive is the subject's own name");
        assertEquals(Set.of("Orin Zatkin", "Columbia University"), CrossLinks.names("Legal Name: Acme Corp. Board Chair: Orin Zatkin, of NYC, who taught at Columbia University, New York.", "Acme Corp"), "a register's labels and a city's letters are no names; a university before a comma is a name, not a town");
        List<String> names = CrossLinks.of(store, "Ada Example").stream().map(CrossLinks.Link::name).sorted().toList();
        assertEquals(List.of("Orin Zatkin"), names, "a registry's status word in capitals and a source's own name are where the library looked, not a connection: " + names);
    }
}
