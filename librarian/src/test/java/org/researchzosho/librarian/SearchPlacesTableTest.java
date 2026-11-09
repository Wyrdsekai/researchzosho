package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;
import org.researchzosho.tools.CustomSearchTool;
import org.researchzosho.tools.ExaSearchTool;
import org.researchzosho.tools.PubMed;
import org.researchzosho.tools.SearchTally;
import org.researchzosho.tools.SemanticScholarTool;
import org.researchzosho.tools.TavilySearchTool;
import org.researchzosho.tools.Tool;
import org.researchzosho.tools.WebSearchTool;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The search places of 0.7.0 on the brain's table and in the registry: search names them with one literal call each, a paid place with no
 * key or with its month's searches used says so in one sentence and is left off, the keeper's own service is a place under its own name,
 * the places reach a run through search alone, and the Sources and keys rows carry the month's limit and dollars and check each key.
 */
class SearchPlacesTableTest {
    static final ObjectMapper M = new ObjectMapper();
    @TempDir Path tmp;
    String realHome;

    @BeforeEach void scratch() { realHome = System.getProperty("user.home"); System.setProperty("user.home", tmp.resolve("home").toString()); Config.invalidate(); SearchTally.override = tmp.resolve("tally.json"); }
    @AfterEach void back() { System.setProperty("user.home", realHome); Config.invalidate(); SearchTally.override = null; OutsideServices.HTTP = OutsideServices::send; }

    static Tool fake(String name, List<String> calls) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return name; }
            @Override public ObjectNode parametersSchema(ObjectMapper j) { return j.createObjectNode(); }
            @Override public String execute(JsonNode args) { calls.add(name + " " + args); return "result of " + name; }
        };
    }

    @Test void searchNamesTheNewPlacesWithALiteralCallAndLeavesAPaidPlaceOffUntilItsKeyIsSetAndWhileItsMonthIsUsed() throws Exception {
        List<String> calls = new ArrayList<>();
        Map<String, Tool> byName = new LinkedHashMap<>(), places = new LinkedHashMap<>();
        byName.put("web_search", fake("web_search", calls));
        for (String n : List.of("pubmed_search", "semantic_scholar_search", "exa_search")) places.put(n, fake(n, calls));
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        List<Instrument> table = Instruments.forRun(store, byName, places, new Researcher.Ask("q", "broad", 0, List.of(), "web", List.of(), 0, List.of(), List.of()));
        Instrument search = table.get(0);
        assertEquals(1, byName.size(), "the places reach the run through search alone");
        String words = search.words();
        assertTrue(words.contains("where = web | pubmed | semantic-scholar · ") && !words.contains("| exa") && !words.contains("exa for the web"), "no Exa key: off the table: " + words);
        assertTrue(words.contains("{\"where\": \"pubmed\", \"query\": \"metformin gestational diabetes\"}") && words.contains("{\"where\": \"semantic-scholar\", \"query\": \"retrieval augmented generation hallucination\"}"), words);
        String noKey = search.asTool().execute(M.readTree("{\"where\":\"exa\",\"query\":\"x\"}"));
        assertEquals("Exa needs a key: the library's keeper gets one at https://dashboard.exa.ai/api-keys and sets it as exa_key in the settings.", noKey);
        assertTrue(calls.isEmpty(), "no call to a place that is off the table");
        String pm = search.asTool().execute(M.readTree("{\"where\":\"PMC\",\"query\":\"neonatal hypoglycaemia\",\"full_text\":true}"));
        assertEquals("result of pubmed_search", pm);
        assertEquals("pubmed_search {\"query\":\"neonatal hypoglycaemia\",\"full_text\":true}", calls.get(0), "another name of the place, and full_text passed on");
        Config.set(ExaSearchTool.KEY, "exa-test-key");
        Config.set(ExaSearchTool.LIMIT, "1");
        assertTrue(search.words().contains("where = web | pubmed | semantic-scholar | exa · ") && search.words().contains("and the month's limit, and leaves the table when that is used"), search.words());
        assertEquals("result of exa_search", search.asTool().execute(M.readTree("{\"where\":\"exa\",\"query\":\"x\"}")));
        SearchTally.count(ExaSearchTool.TALLY, 0, 0.007);   // the month's one search, made
        assertFalse(search.words().contains("| exa") || search.words().contains("exa for the web"), "the month's searches used: off the table: " + search.words());
        String used = search.asTool().execute(M.readTree("{\"where\":\"exa\",\"query\":\"y\"}"));
        assertTrue(used.startsWith("This month's 1 search through Exa is used, so Exa is left off until the month turns"), used);
        assertEquals(2, calls.size(), "and no call past it");
        assertFalse(Places.named("exa").available());
        assertEquals(used, Places.named("exa").unavailableBecause());
    }

    @Test void theKeepersOwnSearchServiceIsAPlaceUnderItsOwnNameAndCannotTakeABuiltInOne() throws Exception {
        List<String> calls = new ArrayList<>();
        Map<String, Tool> byName = new LinkedHashMap<>();
        byName.put("custom_search", fake("custom_search", calls));
        Instrument search = Instruments.search(byName);
        assertTrue(search.asTool().execute(M.readTree("{\"where\":\"custom\",\"query\":\"x\"}")).startsWith("No search service of the library's own is set"), "no address: one sentence");
        Config.set(CustomSearchTool.URL, "http://192.168.1.20:7700/search?q={q}");
        Config.set(CustomSearchTool.NAME, "Intranet");
        assertEquals("intranet", Places.customName());
        assertTrue(search.words().contains("where = intranet · ") && search.words().contains("intranet for the search service set up for this library"), search.words());
        assertEquals("result of custom_search", search.asTool().execute(M.readTree("{\"where\":\"intranet\",\"query\":\"onboarding\"}")));
        assertNotNull(Places.named("intranet"));
        assertTrue(Places.named("intranet").words().contains("{\"where\": \"intranet\", \"query\": \"onboarding checklist\"}"));
        Config.set(CustomSearchTool.NAME, "web");
        assertEquals("custom", Places.customName(), "a built-in place's name is not taken");
        Settings.Setting name = Settings.named("custom_search_name");
        assertEquals("custom_search_name is the name of a place on the table, and Scholar is already one of the library's own places: choose another word.", Settings.refuse(name, "Scholar"));
        assertEquals("", Settings.refuse(name, "intranet"));
        assertEquals("web_first is one of off, exa, tavily.", Settings.refuse(Settings.named("web_first"), "google"));
        assertTrue(Places.names().containsAll(List.of("pubmed", "semantic-scholar", "exa", "tavily")), Places.names().toString());
    }

    @Test void aRunsWebToolsCarryTheFreePlacesAlwaysAndThePaidOnesOnlyWithAKeyAndRoomInTheMonth() throws Exception {
        List<String> names = Researcher.webTools().web("a question").stream().map(Tool::name).toList();
        assertTrue(names.containsAll(List.of("pubmed_search", "semantic_scholar_search")) && !names.contains("exa_search") && !names.contains("tavily_search") && !names.contains("custom_search"), names.toString());
        Config.set(TavilySearchTool.KEY, "tvly-test-key");
        Config.set(CustomSearchTool.URL, "http://127.0.0.1:9/search");
        names = Researcher.webTools().web("a question").stream().map(Tool::name).toList();
        assertTrue(names.contains("tavily_search") && names.contains("custom_search"), names.toString());
        Config.set(TavilySearchTool.LIMIT, "0");
        assertFalse(Researcher.webTools().web("a question").stream().map(Tool::name).toList().contains("tavily_search"), "a limit of 0 turns it off");
        for (String place : Instruments.PLACE_ONLY) assertTrue(Instruments.PLACES.containsValue(place) || place.equals("custom_search"), place);
    }

    @Test void theRegistryHasEachPlaceWithItsTermsTheMonthsLimitAndDollarsAndChecksEachKey() throws Exception {
        for (String key : List.of(PubMed.KEY, SemanticScholarTool.KEY, ExaSearchTool.KEY, TavilySearchTool.KEY, CustomSearchTool.KEY)) {
            OutsideServices.Service s = OutsideServices.of(key);
            assertNotNull(s, key);
            assertEquals(OutsideServices.READ_PLACES, s.checkedOn(), key);
            assertTrue(s.link().startsWith("https://"), key);
        }
        assertEquals("Your own search service", OutsideServices.of(CustomSearchTool.URL).name());
        assertTrue(OutsideServices.of(ExaSearchTool.KEY).terms().startsWith("paid") && OutsideServices.of(ExaSearchTool.KEY).terms().contains("$7 per 1,000 searches"));
        assertTrue(OutsideServices.of(TavilySearchTool.KEY).terms().startsWith("free: 1,000 credits a month"));
        SearchTally.count(ExaSearchTool.TALLY, 0, 0.007);
        SearchTally.count(ExaSearchTool.TALLY, 0, 0.007);
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        OutsideServices.Row exa = null, tavily = null, serper = null;
        for (OutsideServices.Row r : OutsideServices.rows(store)) { switch (r.service().name()) { case "Exa" -> exa = r; case "Tavily" -> tavily = r; case "Serper" -> serper = r; default -> { } } }
        assertNotNull(exa); assertNotNull(tavily); assertNotNull(serper);
        assertEquals("2 of the 714 a month the library stops at, $0.014", OutsideServices.use(exa), "the month's use beside the limit, and the dollars Exa said");
        assertEquals("none of the 1,000 a month the library stops at", OutsideServices.use(tavily));
        assertEquals("none", OutsideServices.use(serper), "a service with no limit reads as before");
        String page = Pages.keysSection(store);
        assertTrue(page.contains("2 of the 714 a month the library stops at, $0.014") && page.contains("→ RESEARCHZOSHO_CUSTOM_SEARCH_URL, RESEARCHZOSHO_CUSTOM_SEARCH_KEY"), "the Sources and keys page shows the limit beside the use");
        JsonNode exaJson = null;
        for (JsonNode j : OutsideServices.json(store)) if (j.path("service").asText().equals("Exa")) exaJson = j;
        assertNotNull(exaJson);
        assertTrue(exaJson.path("monthly_limit").asInt() == 714 && exaJson.path("dollars_this_month").asDouble() == 0.014, exaJson.toString());
        // the checks: one cheap call each, what came back in words
        Config.set(PubMed.KEY, "ncbi-test-key"); Config.set(TavilySearchTool.KEY, "tvly-test-key"); Config.set(ExaSearchTool.KEY, "exa-test-key"); Config.set(SemanticScholarTool.KEY, "s2-test-key");
        List<OutsideServices.Req> asked = new ArrayList<>();
        OutsideServices.HTTP = r -> {
            asked.add(r);
            if (r.url().contains("/einfo.fcgi")) return r.url().contains("api_key=ncbi-test-key") ? new OutsideServices.Res(200, Map.of("x-ratelimit-limit", "10"), "{}") : new OutsideServices.Res(400, Map.of(), "{\"error\":\"API key invalid\"}");
            if (r.url().startsWith("https://api.tavily.com/usage")) return new OutsideServices.Res(200, Map.of(), "{\"key\":{\"usage\":150,\"limit\":1000},\"account\":{\"current_plan\":\"Researcher\",\"plan_usage\":150,\"plan_limit\":1000}}");
            if (r.url().startsWith("https://api.exa.ai/search")) return new OutsideServices.Res(200, Map.of(), "{\"results\":[],\"costDollars\":{\"total\":0.004}}");
            if (r.url().startsWith("https://api.semanticscholar.org/")) return new OutsideServices.Res(403, Map.of(), "{\"message\":\"Forbidden\"}");
            return new OutsideServices.Res(0, Map.of(), "");
        };
        Map<String, OutsideServices.Result> by = new LinkedHashMap<>();
        for (OutsideServices.Result r : OutsideServices.check(store)) by.put(r.service(), r);
        assertEquals("accepted, 10 requests a second", by.get("PubMed (NCBI)").said());
        assertEquals("refused: the key is not accepted", by.get("Semantic Scholar").said());
        assertEquals("accepted, 150 of 1000 credits used this month on the Researcher plan", by.get("Tavily").said());
        assertEquals("accepted; Exa does not say what is left, and this check's search cost $0.004", by.get("Exa").said());
        for (OutsideServices.Req r : asked) if (r.url().startsWith("https://api.exa.ai/")) assertTrue(r.body().contains("\"type\":\"lite\"") && r.body().contains("\"numResults\":1"), "the check is the lighter search: " + r.body());
        assertEquals(3, SearchTally.thisMonth().get(ExaSearchTool.TALLY)[0], "the check's search is counted against the month");
        assertEquals(0.018, SearchTally.dollarsThisMonth(ExaSearchTool.TALLY), 1e-9);
        // the check of a paid place never goes past the month's limit
        Config.set(ExaSearchTool.LIMIT, "3");
        assertTrue(OutsideServices.probe(OutsideServices.of(ExaSearchTool.KEY)).said().startsWith("not checked: This month's 3 searches through Exa are used"));
        assertTrue(WebSearchTool.firstPlace().isEmpty(), "web_first unset: no paid place first");
    }
}
