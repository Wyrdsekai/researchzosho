package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.tools.WebSearchTool;
import org.researchzosho.Config;
import org.researchzosho.tools.SearchTally;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
/** Sources and keys (0.7.0): one registry of the outside services, every key setting in it, the table with this month's use, and the check of each key with what it says in words. */
class OutsideServicesTest {
    @TempDir Path home;
    String realHome;

    @BeforeEach void scratchHome() { realHome = System.getProperty("user.home"); System.setProperty("user.home", home.toString()); Config.invalidate(); SearchTally.override = home.resolve("tally.json"); }
    @AfterEach void back() { System.setProperty("user.home", realHome); Config.invalidate(); SearchTally.override = null; OutsideServices.HTTP = OutsideServices::send; }

    @Test void everyKeySettingBelongsToAServiceWithItsTermsAndADate() {
        for (Settings.Setting s : Settings.known()) {
            if (s.kind() != Settings.Kind.secret || OutsideServices.notAService(s.key())) continue;   // the library's own sign-in secrets are no outside service
            OutsideServices.Service svc = OutsideServices.of(s.key());
            assertNotNull(svc, s.key() + " is a key with no service in the registry");
            assertTrue(svc.link().startsWith("https://") && !svc.terms().isEmpty() && svc.checkedOn().matches("\\d{4}-\\d{2}-\\d{2}"), svc.name());
            assertTrue(svc.terms().startsWith("free") || svc.terms().startsWith("paid") || svc.terms().contains("free"), svc.name() + ": " + svc.terms());
        }
        assertEquals("RentCast", OutsideServices.of("RESEARCHZOSHO_RENTCAST_KEY").name());
        assertNull(OutsideServices.of("RESEARCHZOSHO_MODEL"));
    }

    @Test void theCheckAsksEachServiceWithAKeyAndSaysWhatCameBack(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Config.set("RESEARCHZOSHO_SERPAPI_KEY", "serp-test"); Config.set("RESEARCHZOSHO_FIRECRAWL_KEY", "fc-test"); Config.set("RESEARCHZOSHO_STACKEXCHANGE_KEY", "se-test");
        Config.set("RESEARCHZOSHO_BRAVE_KEY", "brave-test"); Config.set("RESEARCHZOSHO_API_KEY", "or-test"); Config.set("RESEARCHZOSHO_DRIVE", "https://openrouter.ai/api/v1");
        Config.invalidate();
        List<String> asked = new ArrayList<>();
        OutsideServices.HTTP = r -> {
            asked.add(r.url());
            assertFalse(r.url().contains("serp-test") && !r.url().startsWith("https://serpapi.com/account.json"), "the key goes only where its service wants it");
            if (r.url().startsWith("https://serpapi.com/account.json")) return new OutsideServices.Res(200, Map.of(), "{\"plan_searches_left\":180,\"searches_per_month\":250,\"this_month_usage\":70}");
            if (r.url().startsWith("https://api.firecrawl.dev/")) return new OutsideServices.Res(401, Map.of(), "{\"error\":\"Unauthorized\"}");
            if (r.url().startsWith("https://api.stackexchange.com/")) return new OutsideServices.Res(200, Map.of(), "{\"quota_remaining\":9871,\"quota_max\":10000}");
            if (r.url().startsWith("https://api.search.brave.com/")) return new OutsideServices.Res(200, Map.of("x-ratelimit-remaining", asked.stream().filter(u -> u.startsWith("https://api.search.brave.com/")).count() > 1 ? "49, 0" : "1, 1987", "x-ratelimit-limit", asked.stream().filter(u -> u.startsWith("https://api.search.brave.com/")).count() > 1 ? "50, 0" : "1, 2000"), "{}");
            if (r.url().startsWith("https://openrouter.ai/api/v1/auth/key")) { assertEquals("Bearer or-test", r.headers().get("Authorization")); return new OutsideServices.Res(200, Map.of(), "{\"data\":{\"usage\":12.5,\"limit\":null,\"limit_remaining\":null}}"); }
            return new OutsideServices.Res(0, Map.of(), "");
        };
        List<OutsideServices.Result> rs = OutsideServices.check(store);
        assertEquals(5, rs.size(), rs.toString());
        Map<String, OutsideServices.Result> by = new HashMap<>(); for (OutsideServices.Result r : rs) by.put(r.service(), r);
        assertEquals("accepted, 180 of 250 searches left this month", by.get("SerpApi").said()); assertEquals(Boolean.TRUE, by.get("SerpApi").accepted());
        assertEquals("refused: the key is not accepted", by.get("Firecrawl").said()); assertEquals(Boolean.FALSE, by.get("Firecrawl").accepted());
        assertEquals("accepted, 9871 of 10000 calls left today", by.get("Stack Exchange").said());
        assertEquals("accepted, 1987 of 2000 calls left this month", by.get("Brave Search").said());
        assertEquals("accepted; $12.50 used, no spending limit set on the key", by.get("The model server").said());
        assertEquals(5, asked.size(), "one call each: " + asked);
        assertEquals("accepted; this plan counts in dollars, not calls ($5 of free calls a month, then $5 per 1,000), and allows 50 calls a second", OutsideServices.probe(OutsideServices.of("RESEARCHZOSHO_BRAVE_KEY")).said(), "Brave's credit plans put 0 as the monthly figure");
        // kept, and read into the table and the notice
        Map<String, OutsideServices.Result> kept = OutsideServices.kept(store);
        assertEquals(Boolean.FALSE, kept.get("Firecrawl").accepted());
        assertEquals("At the last check the key for Firecrawl was refused: see Sources and keys below.", OutsideServices.refusedNotice(store));
        int[] t = SearchTally.thisMonth().getOrDefault("SerpApi", new int[]{0, 0});
        assertEquals(1, t[0], "the probe is a call in the tally");
        JsonNode j = OutsideServices.json(store);
        JsonNode serp = null; for (JsonNode x : j) if (x.path("service").asText().equals("SerpApi")) serp = x;
        assertNotNull(serp);
        assertTrue(serp.path("set").asBoolean() && serp.path("use_this_month").asText().equals("1 call") && serp.path("last_check").path("said").asText().startsWith("accepted, 180") && serp.path("link").asText().startsWith("https://serpapi.com") && serp.path("checked_on").asText().equals(OutsideServices.READ), serp.toString());
        String text = OutsideServices.text(store);
        assertTrue(text.contains("SerpApi  (key set)") && text.contains("Free: 250 searches a month; paid from $25 a month for 1,000 (2.5 cents each). Read on " + OutsideServices.READ + ".") && text.contains("This month: 1 call. Last check: accepted, 180 of 250 searches left this month ("), text);
        assertTrue(text.contains("RentCast  (no key)") && text.contains("Key: https://app.rentcast.io/app/api → RESEARCHZOSHO_RENTCAST_KEY."), text);
    }


    @Test void aCheckThatIsAPaidSearchIsNeverMadePastTheMonthsLimit() throws Exception {
        Config.set(WebSearchTool.SERPER_KEY, "serper-test"); Config.set(WebSearchTool.SERPER_LIMIT, "1"); Config.invalidate();
        SearchTally.count(WebSearchTool.SERPER, 1);   // this month's one search is made
        List<String> asked = new ArrayList<>();
        OutsideServices.HTTP = r -> { asked.add(r.url()); return new OutsideServices.Res(200, Map.of(), "{\"organic\":[],\"credits\":1}"); };
        OutsideServices.Result r = OutsideServices.probe(OutsideServices.of(WebSearchTool.SERPER_KEY));
        assertEquals("not checked: This month's 1 search through Serper is used, so Serper is left off until the month turns; the library's keeper can raise serper_monthly_limit in the settings.", r.said());
        assertTrue(asked.isEmpty(), "no search went: " + asked);
        Config.set(WebSearchTool.SERPER_LIMIT, ""); Config.invalidate();
        assertEquals(Boolean.TRUE, OutsideServices.probe(OutsideServices.of(WebSearchTool.SERPER_KEY)).accepted(), "with no limit the check is made");
        assertEquals(1, asked.size());
        assertEquals(2, SearchTally.thisMonth().get(WebSearchTool.SERPER)[0], "and counted as the search it is");
    }
}
