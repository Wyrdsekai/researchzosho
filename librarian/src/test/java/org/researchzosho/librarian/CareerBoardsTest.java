package org.researchzosho.librarian;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/** A portfolio board read the way a search engine may: the sitemap, then only the job pages whose address names a role that passes. */
class CareerBoardsTest {
    final Map<String, String> pages = new HashMap<>();
    final List<String> asked = new ArrayList<>();

    static String jobPage(String title, String company, String city, String region, boolean remote, String stage, String feedLink) {
        return "<html><script type=\"application/ld+json\">{\"@context\": \"https://schema.org\", \"@type\": \"JobPosting\", \"title\": \"" + title + "\", \"datePosted\": \"2026-10-20T12:00:00.000Z\","
                + " \"hiringOrganization\": {\"@type\": \"Organization\", \"name\": \"" + company + "\"}, \"jobLocation\": {\"@type\": \"Place\", \"address\": {\"@type\": \"PostalAddress\", \"addressLocality\": \"" + city + "\", \"addressRegion\": \"" + region + "\", \"addressCountry\": \"US\"}},"
                + (remote ? " \"jobLocationType\": \"TELECOMMUTE\"," : "") + " \"description\": \"<p>Lead the team.</p>\"}</script>"
                + "<script id=\"__NEXT_DATA__\">{\"props\":{\"stage\":\"" + stage + "\"}}</script>" + (feedLink.isEmpty() ? "" : "<a href=\"" + feedLink + "\">Apply</a>") + "</html>";
    }

    @BeforeEach void standIns() {
        CareerSources.reset(); CareerSources.POLITE_MS = 0;
        CareerSources.RAW = url -> { asked.add(url); return pages.get(url); };
        String site = "https://jobs.underscore.vc";
        pages.put(site + "/sitemap.xml", "<urlset><url><loc>" + site + "</loc></url><url><loc>" + site + "/sitemap_jobs1.xml</loc></url>"
                + "<url><loc>" + site + "/companies/acme/jobs/11-software-engineer</loc></url><url><loc>" + site + "/companies/acme/jobs/12-vp-of-engineering</loc></url></urlset>");
        pages.put(site + "/sitemap_jobs1.xml", "<urlset><url><loc>" + site + "/companies/beta/jobs/21-cto</loc></url><url><loc>" + site + "/companies/gamma/jobs/31-vp-engineering</loc></url><url><loc>" + site + "/companies/delta/jobs/41-vp-sales</loc></url></urlset>");
        pages.put(site + "/companies/acme/jobs/12-vp-of-engineering", jobPage("VP of Engineering", "Acme", "Boston", "MA", false, "series_b", "https://boards.greenhouse.io/acme"));
        pages.put(site + "/companies/beta/jobs/21-cto", jobPage("CTO", "Beta", "San Francisco", "CA", true, "seed", ""));
        pages.put(site + "/companies/gamma/jobs/31-vp-engineering", jobPage("VP Engineering", "Gamma", "Austin", "TX", false, "series_a", "https://jobs.ashbyhq.com/gamma"));
    }

    @AfterEach void back() { CareerSources.RAW = CareerSources::fetch; CareerSources.POLITE_MS = 1000; CareerSources.reset(); }

    @Test void onlyThePagesWhoseAddressNamesAPassingRoleAreReadAndThePlaceDecides() {
        Predicate<String> ok = t -> t.toLowerCase().matches(".*\\b(vp|cto)\\b.*") && !t.toLowerCase().contains("sales");
        CareerBoards.Found f = CareerBoards.find(List.of("Boston", "Cambridge", "MA"), true, ok, 10);
        assertEquals(List.of("Acme", "Beta"), f.companies().stream().map(CareerSources.Discovered::company).toList(), "Gamma is in Austin and not remote; Delta's page names sales and is never read");
        assertFalse(asked.contains("https://jobs.underscore.vc/companies/acme/jobs/11-software-engineer") || asked.contains("https://jobs.underscore.vc/companies/delta/jobs/41-vp-sales"), "a page whose address names no passing role is not read");
        CareerSources.Discovered acme = f.companies().get(0);
        assertEquals("greenhouse:acme", acme.feed().label());
        assertEquals("on Underscore VC's portfolio board, hiring for VP of Engineering in Boston, MA, US", acme.feed().how());
        assertEquals("series B", f.known().get("acme").stage()); assertEquals("Underscore VC", f.known().get("acme").board());
        assertNull(f.companies().get(1).feed(), "Beta's page links no hiring board: its own board is looked for by name later");
        CareerSources.Opening p = f.postings().get("beta");
        assertEquals("CTO", p.title()); assertTrue(p.remote()); assertEquals("board:Underscore VC", p.source()); assertEquals("2026-10-20", p.posted()); assertEquals("Lead the team.", p.text());
        assertEquals("Underscore VC: 5 job pages, 3 named a role that passes, 3 read, 2 companies", f.notes().get(0));
        assertTrue(CareerBoards.find(List.of("Denver", "CO"), false, ok, 10).companies().isEmpty(), "no board is known for Denver, and the one for every place is not on this stand-in");
    }

    @Test void withALibraryTheBoardsAreSourcesOfTheOneListSeededOnceAndReadFromThere(@TempDir Path tmp) throws Exception {
        String realHome = System.getProperty("user.home"); System.setProperty("user.home", tmp.resolve("home").toString()); Config.invalidate();
        try {
            LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
            Config.set(CareerBoards.BOARDS_KEY, "My Fund=https://jobs.myfund.example/");
            Predicate<String> ok = t -> t.toLowerCase().matches(".*\\b(vp|cto)\\b.*") && !t.toLowerCase().contains("sales");
            CareerBoards.Found f = CareerBoards.find(store, List.of("Boston", "Cambridge", "MA"), true, ok, 10);
            assertEquals(List.of("Acme", "Beta"), f.companies().stream().map(CareerSources.Discovered::company).toList());
            List<Sources.Source> boards = Sources.kept(store);
            assertEquals(5, boards.size(), "the four known boards and the setting's, seeded once: " + boards);
            Sources.Source under = boards.get(0);
            assertEquals("board", under.kind()); assertEquals("https://jobs.underscore.vc", under.address()); assertEquals("Underscore VC's portfolio board", under.name()); assertEquals("Boston, Cambridge, Massachusetts, MA", under.place());
            assertEquals(CareerBoards.TABLE, under.foundBy()); assertEquals(2, under.yieldTotal()); assertEquals("seats", under.yields());
            assertEquals("", boards.get(3).place(), "the board for any place has none"); assertEquals("General Catalyst's portfolio board", boards.get(3).name());
            Sources.Source mine = boards.get(4);
            assertEquals("you", mine.foundBy()); assertEquals("My Fund", mine.name()); assertEquals("https://jobs.myfund.example", mine.address()); assertEquals("from your career_boards setting", mine.note());
            assertTrue(f.notes().get(0).startsWith("Underscore VC: 5 job pages") && f.notes().stream().anyMatch(n -> n.startsWith("General Catalyst (read for any place): ")), f.notes().toString());
            assertTrue(Sources.all(store).stream().anyMatch(x -> x.id().equals(under.id())), "the Sources page shows the boards");
            // taken off on the page: never read again, however the search runs
            assertTrue(Sources.remove(store, under.id()));
            asked.clear();
            CareerBoards.Found g = CareerBoards.find(store, List.of("Boston", "Cambridge", "MA"), true, ok, 10);
            assertTrue(g.companies().isEmpty() && asked.stream().noneMatch(u -> u.startsWith("https://jobs.underscore.vc")), "a board taken off is not read: " + asked);
            assertEquals(5, Sources.kept(store).size(), "seeding again adds nothing");
            assertTrue(Sources.restore(store, under.id()));
            // a refused host is never read either
            SourceRules.set(store, "refuse", "jobs.underscore.vc", "");
            asked.clear();
            CareerBoards.Found h = CareerBoards.find(store, List.of("Boston"), true, ok, 10);
            assertTrue(h.notes().contains("Underscore VC: not read, since you refused its host") && asked.stream().noneMatch(u -> u.startsWith("https://jobs.underscore.vc")), h.notes().toString());
        } finally { System.setProperty("user.home", realHome); Config.invalidate(); }
    }
}
