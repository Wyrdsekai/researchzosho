package org.researchzosho.librarian;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The one records line of a home's card: which source answered, or that none is known and what was looked at, or the lookup page. No web. */
class HomeRecordsLineTest {

    final Map<String, String> answers = new HashMap<>();

    @BeforeEach void standIns() {
        HomeSensors.FETCH = u -> { for (Map.Entry<String, String> e : answers.entrySet()) if (u.contains(e.getKey())) return e.getValue(); return null; };
        HomeSensors.BYTES = u -> null;
        HomeRecords.SEARCH = q -> List.of();
        HomeSensors.MAP_POINT = q -> null;
        Career.DRIVE = () -> null;
    }

    @AfterEach void back() { HomeSensors.FETCH = CareerSources::get; HomeSensors.BYTES = HomeSensors::fetchBytes; HomeRecords.SEARCH = q -> HomeSources.SEARCH.apply(q); HomeSensors.MAP_POINT = HomeSensors::pointFromMap; Career.DRIVE = Explain::drive; }

    static HomeRecords.Dataset sales() { return new HomeRecords.Dataset("Socrata", "datacatalog.cookcountyil.gov", "wvhk-k5uv", "Assessor - Parcel Sales", Map.of("address", "addr", "price", "sale_price", "date", "sale_date"), "", Map.of(), "2026-09-30"); }
    static HomeRecords.Dataset roll() { return new HomeRecords.Dataset("ArcGIS", "services5.arcgis.com", "https://services5.arcgis.com/x/FeatureServer/0", "LA City Parcels", Map.of("address", "SitusFullAddress", "value", "Roll_TotalValue"), "", Map.of(), "2024"); }
    static HomeRecords.Dataset permits() { return new HomeRecords.Dataset("Socrata", "data.cityofchicago.org", "ydr8-5enu", "Building Permits", Map.of("address", "street", "date", "issue_date", "desc", "work_description"), "", Map.of(), "2026-10-01"); }

    @Test void theThreeWordings() {
        assertEquals("Records: Assessor - Parcel Sales, Socrata, newest row 2026; permits from Building Permits, Socrata, newest row 2026.",
                HomeRecordsLine.line("Chicago", "IL", "Cook County", "", sales(), permits(), List.of(), ""));
        assertEquals("Records: LA City Parcels, ArcGIS, roll of 2024. No permits source is known for Los Angeles, Los Angeles County.",
                HomeRecordsLine.line("Los Angeles", "CA", "Los Angeles County", "", roll(), null, List.of(), ""), "an assessor's roll says its year as a roll, not as a newest row");
        assertEquals("Records: permits from Building Permits, Socrata, newest row 2026. No sales or values source is known for Chicago, Cook County.",
                HomeRecordsLine.line("Chicago", "IL", "Cook County", "", null, permits(), List.of(), ""));
        HomeRecords.Dataset valued = new HomeRecords.Dataset("Socrata", "www.dallasopendata.com", "x", "Building Permits", Map.of("address", "a", "value", "value", "date", "issued_date"), "", Map.of(), "2026-01-02");
        assertEquals("Records: permits from Building Permits, Socrata, newest row 2026. No sales or values source is known for Dallas, Dallas County.",
                HomeRecordsLine.line("Dallas", "TX", "Dallas County", "", null, valued, List.of(), ""), "a permit's value column does not make the set a roll");
        HomeRecords.Dataset typo = new HomeRecords.Dataset("ArcGIS", "x", "y", "Property Sales", Map.of("address", "a", "price", "p", "date", "d"), "", Map.of(), "2206-12-28");
        assertEquals("Records: Property Sales, ArcGIS. No permits source is known for Detroit, Wayne County.", HomeRecordsLine.line("Detroit", "MI", "Wayne County", "", typo, null, List.of(), ""), "a typo's year is no newest row");
        List<String> looked = List.of("the city's own portal by a web search: 2 hosts probed for CKAN, 0 answer", "Socrata's catalogue, 4 queries for sales: 0 candidates",
                "ArcGIS Hub, 2 queries for sales: 0 layers whose extent holds the home", "the state's own parcels: Ohio's parcels (ArcGIS, x)", "none of the 1 answered for MAIN ST");
        assertEquals("No records source is known for Dayton, Montgomery County. The library looked at Socrata, ArcGIS, CKAN, Ohio's state layer and the web for the place's own portal.",
                HomeRecordsLine.line("Dayton", "OH", "Montgomery County", "", null, null, looked, ""));
        assertEquals("No records source is known for Elsewhere, Ohio. The library looked at Socrata.",
                HomeRecordsLine.line("Elsewhere", "OH", "", "", null, null, List.of("Socrata's catalogue, 2 queries for sales: 0 candidates", "ArcGIS Hub not asked: the home is not placed on the map"), ""),
                "the county unknown, the state names the place; what was not asked is not listed");
        assertEquals("No records source a program can read is known for Nashville, Davidson County; the county publishes its records through a lookup page at https://example.org/assessor/search.",
                HomeRecordsLine.line("Nashville", "TN", "Davidson County", "", null, null, looked, "https://example.org/assessor/search"));
        assertEquals("Records: NYC Open Data: the city's rolling sales and DOB permits, read by the library's own reader.",
                HomeRecordsLine.line("New York", "NY", "New York County", "NYC Open Data: the city's rolling sales and DOB permits", null, null, List.of(), ""));
        assertEquals("Records: WPRDC: Allegheny County's property sales, read by the library's own reader; the county's portal has no permits the library reads.",
                HomeRecordsLine.line("Pittsburgh", "PA", "Allegheny County", "WPRDC: Allegheny County's property sales", null, null, List.of(), ""));
    }

    @Test void theCardCarriesTheLineAndSaysItWhenFiled(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Home.Listing l = new Home.Listing("x", "a test", "sale", "1 Main St", "Dayton", "OH", "", 200_000, 3, 1, 0, "house", "", 0, 0, "", "", "", "");
        HomeSensors.Sheet sheet = HomeSensors.sheet(store, l);
        String line = sheet.facts().path("records_line").asText("");
        assertTrue(line.startsWith("No records source is known for Dayton, Ohio.") && line.contains("The library looked at Socrata"), line);
        assertTrue(sheet.text().contains("\n" + line + "\n"), "the line is in the records text: " + sheet.text());
        Home.Search s = Home.start(store, "a home");
        HomeCard.FILE = args -> "J-0001";
        try {
            String said = HomeCard.file(store, s, l, "person");
            assertTrue(said.contains(" " + line), said);
            assertEquals(line, HomeCard.kept(store, s, "x").path("records_line").asText(""), "kept on the card");
        } finally { HomeCard.FILE = HomeCard::fileRun; }
    }
}
