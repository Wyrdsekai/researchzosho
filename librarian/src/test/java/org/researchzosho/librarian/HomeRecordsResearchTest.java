package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Where a county publishes its records, researched once and read: the addresses told apart by their shape, a dataset tried as any candidate, a lookup page said. No web, no model. */
class HomeRecordsResearchTest {

    static final class StandIn implements Researcher.Drive {
        @Override public ObjectNode chat(ArrayNode m, ArrayNode t, int x, String y) { throw new UnsupportedOperationException(); }
        @Override public int contextWindow() { return 32_000; }
        @Override public String classify(ArrayNode m, int max) { return "[]"; }
    }

    final Map<String, String> answers = new HashMap<>();
    final List<String> fetched = new ArrayList<>(), asked = new ArrayList<>();

    @BeforeEach void standIns() {
        HomeSensors.FETCH = u -> { fetched.add(u); for (Map.Entry<String, String> e : answers.entrySet()) if (u.contains(e.getKey())) return e.getValue(); return null; };
        HomeSensors.BYTES = u -> null;
        HomeRecords.SEARCH = q -> List.of();
        HomeSensors.MAP_POINT = q -> null;
        Career.DRIVE = StandIn::new;
        HomeRecordsResearch.PROBE = u -> null;
        HomeRecordsResearch.RESEARCH = (st, ask, title, stop) -> { throw new IllegalStateException("no research was expected"); };
    }

    @AfterEach void back() {
        HomeSensors.FETCH = CareerSources::get; HomeSensors.BYTES = HomeSensors::fetchBytes; HomeRecords.SEARCH = q -> HomeSources.SEARCH.apply(q); HomeSensors.MAP_POINT = HomeSensors::pointFromMap;
        Career.DRIVE = Explain::drive; HomeRecordsResearch.PROBE = HomeRecordsResearch::probe; HomeRecordsResearch.RESEARCH = HomeRecordsResearch::research;
    }

    @Test void theAddressesOfAnAnswerAreToldApartByTheirShape() {
        String text = "The assessor's roll is on the county's portal (https://data.example.gov/d/abcd-1234), the sales as https://data.example.gov/resource/wxyz-9876.json. "
                + "Parcels: https://services1.arcgis.com/Zz/arcgis/rest/services/Parcels/FeatureServer/0/query?where=1=1. Permits: https://data.example.gov/dataset/permits/resource/0f1e2d3c-4b5a-6978-8a9b-0c1d2e3f4a5b. "
                + "A download: https://files.example.gov/sales_2026.csv; the GIS zip https://files.example.gov/parcels.zip, and the lookup at https://assessor.example.gov/search. Also https://data.example.gov/d/abcd-1234.";
        List<String> urls = HomeRecordsResearch.urls(text);
        assertEquals(7, urls.size(), urls.toString());
        assertEquals("https://data.example.gov/d/abcd-1234", urls.get(0), "the trailing bracket and full stop are off, and the address is once");
        Map<String, String> kinds = new HashMap<>();
        for (String u : urls) { HomeRecordsResearch.Source s = HomeRecordsResearch.classify(u); kinds.put(u, s.kind() + "|" + s.id()); }
        assertEquals("socrata|abcd-1234", kinds.get("https://data.example.gov/d/abcd-1234"));
        assertEquals("socrata|wxyz-9876", kinds.get("https://data.example.gov/resource/wxyz-9876.json"));
        assertEquals("arcgis|https://services1.arcgis.com/Zz/arcgis/rest/services/Parcels/FeatureServer/0", kinds.get("https://services1.arcgis.com/Zz/arcgis/rest/services/Parcels/FeatureServer/0/query?where=1=1"));
        assertEquals("ckan|0f1e2d3c-4b5a-6978-8a9b-0c1d2e3f4a5b", kinds.get("https://data.example.gov/dataset/permits/resource/0f1e2d3c-4b5a-6978-8a9b-0c1d2e3f4a5b"));
        assertEquals("csv|", kinds.get("https://files.example.gov/sales_2026.csv"));
        assertEquals("zip|", kinds.get("https://files.example.gov/parcels.zip"));
        assertEquals("unknown|", kinds.get("https://assessor.example.gov/search"), "a page is asked what it answers with; here nothing answers");
        HomeRecordsResearch.PROBE = u -> u.contains("assessor.example.gov") ? new String[]{"text/html; charset=utf-8", "<!DOCTYPE html><html>"} : u.contains("export") ? new String[]{"text/csv", "a,b\n1,2\n"} : null;
        assertEquals("page", HomeRecordsResearch.classify("https://assessor.example.gov/search").kind());
        assertEquals("csv", HomeRecordsResearch.classify("https://files.example.gov/export?format=csv").kind(), "what it answers with says it is a file");
    }

    @Test void aCountyThePlatformsGiveNothingForIsResearchedOnceAndItsDatasetTriedAndItsLookupPageSaid(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        // the platforms: nothing (no portal answers, the catalogue is silent); the research names a Socrata dataset and a lookup page
        HomeRecordsResearch.RESEARCH = (st, ask, title, stop) -> {
            asked.add(ask.question() + " || " + title + " || " + ask.maxMinutes() + " min, " + ask.subQuestions().size() + " sub-questions, " + ask.sources());
            return "Sangamon County publishes its parcel values on the county portal: https://data.sangamon.example/d/abcd-1234 (Socrata). Building permits are searched one address at a time at https://permits.sangamon.example/lookup.";
        };
        HomeRecordsResearch.PROBE = u -> u.contains("lookup") ? new String[]{"text/html", "<html>"} : null;
        answers.put("data.sangamon.example/api/views/abcd-1234.json", "{\"name\": \"Parcel Assessments\", \"columns\": [{\"fieldName\": \"site_address\"}, {\"fieldName\": \"total_assessed_value\"}, {\"fieldName\": \"year_built\"}, {\"fieldName\": \"pin\"}]}");
        answers.put("data.sangamon.example/resource/abcd-1234.json?$limit=5", "[{\"site_address\": \"10 ELM ST\", \"total_assessed_value\": \"120000\", \"year_built\": \"1950\", \"pin\": \"1\"}]");
        answers.put("data.sangamon.example/resource/abcd-1234.json?$where=", "[{\"site_address\": \"120 ELM ST\", \"total_assessed_value\": \"255,000\", \"year_built\": \"1961\", \"pin\": \"2\"}, {\"site_address\": \"126 ELM ST\", \"total_assessed_value\": \"240,000\", \"year_built\": \"1961\", \"pin\": \"3\"}]");
        HomeRecords.Found f = HomeRecords.find(store, "Springfield", "IL", "Sangamon County", 39.80, -89.65, "ELM ST", 122, true);
        assertEquals(1, asked.size(), asked.toString());
        assertTrue(asked.get(0).startsWith("Where does Sangamon County, IL, and the city of Springfield publish its property records online") && asked.get(0).contains("Property records of Sangamon County, IL: where they are published || 8 min, 3 sub-questions, web"), asked.get(0));
        assertNotNull(f.sales(), f.looked().toString());
        assertEquals("Parcel Assessments", f.sales().name()); assertEquals("Socrata", f.sales().platform()); assertEquals("total_assessed_value", f.sales().field("value"));
        assertNull(f.permits());
        assertEquals("https://permits.sangamon.example/lookup", f.lookup(), "the lookup page the research named is kept for the card");
        assertTrue(f.looked().stream().anyMatch(x -> x.startsWith("the library's research on where Sangamon County publishes its records") && x.contains("2 addresses named, 1 datasets, 0 files, 1 pages")), f.looked().toString());
        List<String> prices = HomeRecords.prices(f.sales(), "ELM ST", 122, "Springfield");
        assertEquals("120 ELM ST: assessed at $255,000, built 1961", prices.get(0), prices.toString());
        // kept: a month for the county's research, and the city's look says it was researched, so the next look asks nothing
        assertTrue(Files.isRegularFile(HomeRecordsResearch.file(store, "Sangamon County", "IL")));
        HomeRecordsResearch.RESEARCH = (st, ask, title, stop) -> { throw new IllegalStateException("researched again"); };
        HomeRecords.Found again = HomeRecords.find(store, "Springfield", "IL", "Sangamon County", 39.80, -89.65, "ELM ST", 122, true);
        assertEquals("abcd-1234", again.sales().id()); assertEquals(f.lookup(), again.lookup());
        // the card's line: sales from the research, no permits; and, for a county with only a lookup page, the page
        String line = HomeRecordsLine.line("Springfield", "IL", "Sangamon County", "", f.sales(), null, f.looked(), f.lookup());
        assertEquals("Records: Parcel Assessments, Socrata. No permits source is known for Springfield, Sangamon County.", line);
        assertEquals("No records source a program can read is known for Springfield, Sangamon County; the county publishes its records through a lookup page at https://permits.sangamon.example/lookup.",
                HomeRecordsLine.line("Springfield", "IL", "Sangamon County", "", null, null, f.looked(), f.lookup()));
    }

    @Test void aDatasetsPageOnAHubSiteOrACkanPortalResolvesToTheDatasetItself(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        // a Hub site's page names its item in its own text; the Hub's API names the layer; the layer's fields and rows settle the columns
        answers.put("data.example.gov/datasets/parcels-1/about", "<html><script>window.__hub = {\"id\": \"fa26cd9326c446179be059e00449cb1f_0\", \"also\": \"fa26cd9326c446179be059e00449cb1f_0\"}</script></html>");
        answers.put("hub.arcgis.com/api/v3/datasets/fa26cd9326c446179be059e00449cb1f_0", "{\"data\": {\"attributes\": {\"name\": \"Parcels\", \"type\": \"Feature Layer\", \"url\": \"https://services2.arcgis.com/Zz/arcgis/rest/services/Parcels_view/FeatureServer/0\"}}}");
        answers.put("Parcels_view/FeatureServer/0?f=json", "{\"name\": \"Parcels\", \"fields\": [{\"name\": \"SITUS_ADDR\"}, {\"name\": \"TOTAL_APPR\"}, {\"name\": \"YEAR_BUILT\"}]}");
        answers.put("Parcels_view/FeatureServer/0/query?where=1%3D1&outFields=*&resultRecordCount=5", "{\"features\": [{\"attributes\": {\"SITUS_ADDR\": \"10 ELM ST\", \"TOTAL_APPR\": 120000, \"YEAR_BUILT\": 1950}}]}");
        answers.put("Parcels_view/FeatureServer/0/query?where=", "{\"features\": [{\"attributes\": {\"SITUS_ADDR\": \"120 ELM ST\", \"TOTAL_APPR\": 255000, \"YEAR_BUILT\": 1961}}]}");
        HomeRecordsResearch.Source hub = HomeRecordsResearch.classify("https://data.example.gov/datasets/parcels-1/about");
        assertEquals("arcgis", hub.kind()); assertEquals("https://services2.arcgis.com/Zz/arcgis/rest/services/Parcels_view/FeatureServer/0", hub.id());
        assertEquals("arcgis", HomeRecordsResearch.classify("https://x-hub.arcgis.com/datasets/fa26cd9326c446179be059e00449cb1f_0/explore").kind(), "the item id in the address itself");
        // a layer whose own description errs still names its fields in a query's answer, and is named after its service
        answers.put("ParcelsBroken/FeatureServer/1?f=json", "{\"error\": {\"code\": 500, \"message\": null}}");
        answers.put("ParcelsBroken/FeatureServer/1/query?where=1%3D1&outFields=*&resultRecordCount=1", "{\"fields\": [{\"name\": \"PARCEL_ADDR\"}, {\"name\": \"TOTAL_VALUE\"}, {\"name\": \"OwnCity\"}], \"features\": [{\"attributes\": {\"PARCEL_ADDR\": \"1 MAIN ST\", \"TOTAL_VALUE\": 5, \"OwnCity\": \"ELSEWHERE\"}}]}");
        answers.put("ParcelsBroken/FeatureServer/1/query?where=1%3D1&outFields=*&resultRecordCount=5", "{\"features\": [{\"attributes\": {\"PARCEL_ADDR\": \"1 MAIN ST\", \"TOTAL_VALUE\": 5, \"OwnCity\": \"ELSEWHERE\"}}]}");
        List<String> looked = new ArrayList<>();
        HomeRecords.Dataset broken = HomeRecords.fromSource(new HomeRecordsResearch.Source("https://x/datasets/a", "arcgis", "x", "https://services.example/rest/services/ParcelsBroken/FeatureServer/1"), "sales", looked);
        assertNotNull(broken, looked.toString());
        assertEquals("ParcelsBroken", broken.name()); assertEquals("PARCEL_ADDR", broken.field("address")); assertEquals("", broken.field("city"), "the owner's city is not the home's");
        HomeRecords.Dataset silent = HomeRecords.fromSource(new HomeRecordsResearch.Source("https://x/datasets/b", "arcgis", "x", "https://services.example/rest/services/Silent/FeatureServer/0"), "sales", looked);
        assertNull(silent); assertTrue(looked.stream().anyMatch(x -> x.contains("Silent/FeatureServer/0 did not answer")), looked.toString());
        // a CKAN portal's dataset page: the portal's API names its table, or a file when it keeps none
        answers.put("data.example.org/api/3/action/package_show?id=building-permits", "{\"success\": true, \"result\": {\"resources\": [{\"id\": \"0f1e2d3c-4b5a-6978-8a9b-0c1d2e3f4a5b\", \"datastore_active\": true}]}}");
        answers.put("data.example.org/api/3/action/package_show?id=sales-2026", "{\"success\": true, \"result\": {\"resources\": [{\"id\": \"x\", \"datastore_active\": false, \"format\": \"CSV\", \"url\": \"https://files.example.org/sales_2026.csv\"}]}}");
        HomeRecordsResearch.Source ckan = HomeRecordsResearch.classify("https://data.example.org/dataset/building-permits");
        assertEquals("ckan|0f1e2d3c-4b5a-6978-8a9b-0c1d2e3f4a5b", ckan.kind() + "|" + ckan.id());
        HomeRecordsResearch.Source csv = HomeRecordsResearch.classify("https://data.example.org/dataset/sales-2026");
        assertEquals("csv|https://files.example.org/sales_2026.csv", csv.kind() + "|" + csv.url());
        // the county's research kept from the day it named only pages: read back, the Hub page is the dataset now, and the look keeps it
        HomeRecordsResearch.RESEARCH = (st, ask, title, stop) -> "The parcels are at https://data.example.gov/datasets/parcels-1/about on the county's open data site.";
        HomeRecords.Found f = HomeRecords.find(store, "Springfield", "IL", "Sangamon County", 39.80, -89.65, "ELM ST", 122, true);
        assertNotNull(f.sales(), f.looked().toString());
        assertEquals("ArcGIS", f.sales().platform()); assertEquals("Parcels", f.sales().name()); assertEquals("TOTAL_APPR", f.sales().field("value"));
        assertEquals(List.of("120 ELM ST: assessed at $255,000, built 1961"), HomeRecords.prices(f.sales(), "ELM ST", 122, "Springfield"));
    }

    @Test void withoutAModelOrWithoutTheAskNothingIsResearched(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        HomeRecordsResearch.RESEARCH = (st, ask, title, stop) -> { throw new IllegalStateException("researched"); };
        HomeRecords.Found f = HomeRecords.find(store, "Dayton", "OH", "Montgomery County", 39.76, -84.19, "MAIN ST", 1);   // the bench's and the sensors' plain look: no research
        assertNull(f.sales()); assertEquals("", f.lookup());
        assertTrue(f.looked().stream().noneMatch(x -> x.contains("research")), f.looked().toString());
        Career.DRIVE = () -> null;   // no model configured: a person's card looks, and says what it looked at, but researches nothing
        f = HomeRecords.find(store, "Elsewhere", "OH", "Clark County", 39.9, -83.8, "MAIN ST", 1, true);
        assertTrue(f.looked().stream().noneMatch(x -> x.contains("research")), f.looked().toString());
        // the research that fails is said, and tried again after a week rather than a month
        Career.DRIVE = StandIn::new;
        HomeRecordsResearch.RESEARCH = (st, ask, title, stop) -> { throw new IllegalStateException("the drive is down"); };
        f = HomeRecords.find(store, "Elsewhere", "OH", "Clark County", 39.9, -83.8, "MAIN ST", 1, true);
        assertTrue(f.looked().stream().anyMatch(x -> x.contains("0 addresses named") && x.contains("the research could not run: the drive is down")), f.looked().toString());
    }
}
