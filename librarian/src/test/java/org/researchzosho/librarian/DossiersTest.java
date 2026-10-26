package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.researchzosho.librarian.profiles.OrganizationProfile;
import org.researchzosho.librarian.profiles.PersonProfile;

import static org.junit.jupiter.api.Assertions.*;

/** The list of dossiers (0.6.6): every subject a report was written on, the latest run first, on the page, the command and the protocol. */
class DossiersTest {
    static Finding claim(LibraryStore store, String title, String body) throws Exception {
        String id = store.nextFindingId(title);
        Finding f = new Finding(id, title, List.of(), Finding.State.accepted, Finding.ClaimType.extraction, Finding.Confidence.medium, "patron:test", Instant.now().toString(), "2026-10-25", Finding.Volatility.slow, "",
                List.of(new Finding.Source("https://a.example/" + id, "n/a", "x")), List.of(), null, body + "\n");
        store.write(f); return f;
    }

    @Test
    void everyDossierIsListedWithItsKindCountsLastRunAndStory(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        assertEquals(0, Dossiers.list(store).size());
        assertTrue(Dossiers.text(Dossiers.list(store)).startsWith("No dossiers yet."));
        String i1 = store.nextInvestigationId("Who is Ada Example? A full dossier.");
        store.write(new Investigation(i1, "Who is Ada Example? A full dossier.", Finding.State.accepted, "patron:test", "2026-10-20T10:00:00Z", List.of(), List.of(), "## Question\n\nWho is Ada Example?\n\n## Answer\n\nx\n"));
        String i2 = store.nextInvestigationId("Corporate history of Acme Corp.: a full dossier.");
        store.write(new Investigation(i2, "Corporate history of Acme Corp.: a full dossier.", Finding.State.accepted, "patron:test", "2026-10-24T10:00:00Z", List.of(), List.of(), "## Question\n\nAcme\n\n## Answer\n\nx\n"));
        String i3 = store.nextInvestigationId("Corporate history of Acme Corp.: its filings.");
        store.write(new Investigation(i3, "Corporate history of Acme Corp.: its filings.", Finding.State.accepted, "patron:test", "2026-10-22T10:00:00Z", List.of(), List.of(), "## Question\n\nAcme\n\n## Answer\n\nx\n"));
        String s1 = store.nextInvestigationId("The story of Ada Example");
        store.write(new Investigation(s1, "The story of Ada Example", Finding.State.draft, "librarian:story", "2026-10-21T10:00:00Z", List.of(), List.of(), "# The story of Ada Example\n\n## 1990s\n\nx\n"));
        String i4 = store.nextInvestigationId("As of October 2026, the shelves hold: a survey.");
        store.write(new Investigation(i4, "As of October 2026, the shelves hold: a survey.", Finding.State.accepted, "patron:test", "2026-10-25T10:00:00Z", List.of(), List.of(), "## Question\n\nx\n\n## Answer\n\nx\n"));
        String i5 = store.nextInvestigationId("Benford Corp, the Needham maker of widgets: who runs it?");
        store.write(new Investigation(i5, "Benford Corp, the Needham maker of widgets: who runs it?", Finding.State.accepted, "patron:test", "2026-10-25T11:00:00Z", List.of(), List.of(), "## Question\n\nx\n\n## Answer\n\nx\n"));
        claim(store, "Ada Example, 1999: joined Acme", "In 1999 Ada Example joined Acme Corp.");
        claim(store, "Acme Corp., 2024: seed round", "Acme Corp. raised a seed round.");
        List<Dossiers.Entry> all = Dossiers.list(store);
        assertEquals(2, all.size(), "a question that opens with a date, or with a name but without a person's or an organisation's field, is not a dossier: " + all);
        Dossiers.Entry acme = all.get(0), ada = all.get(1);
        assertEquals("Acme Corp", acme.subject(), "the latest run first"); assertEquals("organization", acme.kind()); assertEquals(2, acme.reports()); assertEquals("2026-10-24", acme.lastRun()); assertFalse(acme.story());
        assertEquals(2, acme.claims(), "the claims naming it: the one on Ada names Acme too");
        assertEquals("Ada Example", ada.subject()); assertEquals("person", ada.kind()); assertEquals(1, ada.reports()); assertEquals("2026-10-20", ada.lastRun()); assertTrue(ada.story(), "the story is written");
        String text = Dossiers.text(all);
        assertTrue(text.startsWith("2 dossiers on the shelves, the latest run first.") && text.contains("\n  Acme Corp — organization, 2 claims, 2 reports, last run 2026-10-24\n  Ada Example — person, 1 claim, 1 report, last run 2026-10-20, story written"), text);
        // the page
        String html = DossierPage.render(store, "");
        assertTrue(html.contains("<h2 id=\"all\">The dossiers on the shelves</h2>") && html.contains("<a href=\"/dossier?subject=Acme%20Corp\">Acme Corp</a></td><td>organization</td><td>2</td><td>2</td><td>2026-10-24</td><td></td>")
                && html.contains("<a href=\"/dossier?subject=Ada%20Example\">Ada Example</a></td><td>person</td><td>1</td><td>1</td><td>2026-10-20</td><td>written</td>"), html);
        // the protocol: op=list, or no subject
        LibraryProtocol p = new LibraryProtocol(store);
        ObjectNode a = new ObjectMapper().createObjectNode(); a.put("op", "list");
        var r = p.dossier(a);
        assertEquals(2, r.path("count").asInt()); assertEquals("Acme Corp", r.path("dossiers").get(0).path("subject").asText()); assertTrue(r.path("dossiers").get(1).path("story").asBoolean());
        assertEquals(2, p.dossier(new ObjectMapper().createObjectNode()).path("count").asInt(), "no subject lists them too");
        // the menu's Library section leads to the dossiers, below Map, once; neither field adds the entry
        String nav = Pages.siteNav(store, new Patrons.Patron("did:key:reader", "a reader", "web"));
        int lib = nav.indexOf("<b>Library</b>"), map = nav.indexOf("href=\"/map\""), doss = nav.indexOf("href=\"/dossier\""), work = nav.indexOf("<b>Work</b>");
        assertTrue(lib >= 0 && map > lib && doss > map && doss < work, nav);
        assertEquals(doss, nav.lastIndexOf("href=\"/dossier\""), "once");
        assertTrue(new PersonProfile().menu().isEmpty() && new OrganizationProfile().menu().isEmpty());
    }
}
