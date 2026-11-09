package org.researchzosho.librarian;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** The records of a home read by the sensors on stand-in services: the geocoder, the flood layer, the schools, the transit feeds, a city's portal. No web. */
class HomeSensorsTest {

    final Map<String, String> answers = new HashMap<>();
    final Map<String, byte[]> bytes = new HashMap<>();

    @BeforeEach void standIns() {
        HomeCensus.KEY_VALUE = () -> null;   // the Census figures want a key: the line without one is pinned below, whatever this box has
        HomeSensors.FETCH = u -> { for (Map.Entry<String, String> e : answers.entrySet()) if (u.contains(e.getKey())) return e.getValue(); return null; };
        HomeSensors.BYTES = u -> bytes.get(u);
        HomeRecords.SEARCH = q -> List.of();
        HomeSensors.MAP_POINT = q -> null;
        Career.DRIVE = () -> null;
    }

    @AfterEach void back() { HomeCensus.KEY_VALUE = () -> Config.get(HomeCensus.KEY); HomeSensors.FETCH = CareerSources::get; HomeSensors.BYTES = HomeSensors::fetchBytes; HomeRecords.SEARCH = q -> HomeSources.SEARCH.apply(q); HomeSensors.MAP_POINT = HomeSensors::pointFromMap; Career.DRIVE = Explain::drive; }

    @Test void anAddressTheCensusMissesIsPlacedByTheMapAndItsTractAndCountyReadByThePoint(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        answers.put("geocoder/geographies/onelineaddress", "{\"result\": {\"addressMatches\": []}}");
        answers.put("geocoder/geographies/coordinates?x=-70.2574&y=43.6592", "{\"result\": {\"geographies\": {\"Counties\": [{\"NAME\": \"Cumberland County\"}], \"Census Tracts\": [{\"GEOID\": \"23005000300\"}]}}}");
        HomeSensors.MAP_POINT = q -> q.startsWith("389 Congress St") ? new double[]{43.6592, -70.2574} : null;
        HomeSensors.Placed p = HomeSensors.place("389 Congress St", "Portland", "ME");
        assertNotNull(p);
        assertEquals("Cumberland County", p.county()); assertEquals("23005000300", p.tract()); assertEquals(43.6592, p.lat(), 1e-9);
        assertNull(HomeSensors.place("1 Nowhere Rd", "Portland", "ME"), "neither places it");
        Home.Listing l = new Home.Listing("x", "a test", "sale", "389 Congress St", "Portland", "ME", "", 400_000, 3, 1, 0, "house", "", 0, 0, "", "", "", "");
        String t = HomeSensors.sheet(store, l).text();
        assertFalse(t.startsWith("Where it is: unknown"), t);
        String looked = HomeSensors.sheet(store, l).facts().path("records_looked").toString();
        assertTrue(looked.contains("queries for sales") && !looked.contains("not asked"), "ArcGIS is asked once the map has placed the home: " + looked);
    }

    static byte[] gtfs(String stops) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(out)) {
            z.putNextEntry(new ZipEntry("agency.txt")); z.write("agency_id,agency_name\nA,Example Transit\n".getBytes(StandardCharsets.UTF_8)); z.closeEntry();
            z.putNextEntry(new ZipEntry("stops.txt")); z.write(stops.getBytes(StandardCharsets.UTF_8)); z.closeEntry();
        }
        return out.toByteArray();
    }

    @Test void everyFactWithItsSourceAndTheUnknownsSaid(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        answers.put("geocoding.geo.census.gov", """
                {"result": {"addressMatches": [{"coordinates": {"x": -73.98, "y": 40.72}, "geographies": {"Census Tracts": [{"GEOID": "36061002601"}]}}]}}""");
        answers.put("hazards.fema.gov", "{\"features\": [{\"attributes\": {\"FLD_ZONE\": \"AE\", \"ZONE_SUBTY\": null}}]}");
        answers.put("educationdata.urban.org", """
                {"results": [{"school_name": "P.S. 64", "latitude": 40.722, "longitude": -73.981, "lowest_grade_offered": 0, "highest_grade_offered": 5},
                             {"school_name": "Far Away High", "latitude": 40.80, "longitude": -73.95, "lowest_grade_offered": 9, "highest_grade_offered": 12}]}""");
        answers.put("files.mobilitydatabase.org/feeds_v2.csv", """
                id,data_type,provider,status,urls.direct_download,urls.latest,location.bounding_box.minimum_latitude,location.bounding_box.maximum_latitude,location.bounding_box.minimum_longitude,location.bounding_box.maximum_longitude
                mdb-1,gtfs,"Example Transit, Inc.",active,https://feeds.example/city.zip,,40.5,41.0,-74.3,-73.7
                mdb-2,gtfs,Statewide Bus,active,https://feeds.example/state.zip,,40.3,41.2,-74.5,-73.5
                mdb-5,gtfs,Continental Rail,active,https://feeds.example/rail.zip,,25.0,49.0,-125.0,-67.0
                mdb-3,gtfs,Elsewhere,active,https://feeds.example/else.zip,,30.0,31.0,-90.0,-89.0
                mdb-4,gtfs-rt,Realtime,active,https://feeds.example/rt,,40.5,41.0,-74.3,-73.7
                """);
        bytes.put("https://feeds.example/city.zip", gtfs("stop_id,stop_name,stop_lat,stop_lon\n1,2 Av,40.7235,-73.9899\n2,Delancey St,40.7183,-73.9881\n3,Harlem,40.81,-73.94\n"));
        bytes.put("https://feeds.example/state.zip", gtfs("stop_id,stop_name,stop_lat,stop_lon\n9,Houston St bus,40.7225,-73.9815\n"));
        answers.put("usep-8jbt.json", "[{\"address\": \"270 EAST 3 STREET\", \"sale_price\": \"2150000\", \"sale_date\": \"2026-03-04T00:00:00.000\", \"gross_square_feet\": \"3200\"}, {\"address\": \"274 EAST 3 STREET\", \"sale_price\": \"0\", \"sale_date\": \"2026-01-01T00:00:00.000\"}]");
        answers.put("ipu4-2q9a.json", "[{\"house__\": \"272\", \"street_name\": \"EAST 3 STREET\", \"job_type\": \"A2\", \"work_type\": \"PL\", \"issuance_date\": \"2026-05-06T00:00:00.000\"}]");
        Home.Listing l = new Home.Listing("x", "a test", "sale", "272 East 3 Street #4", "New York", "NY", "10009", 1_100_000, 2, 1, 900, "condo", "", 0, 0, "", "", "", "");
        HomeSensors.Sheet sheet = HomeSensors.sheet(store, l);
        String t = sheet.text();
        assertEquals(40.72, sheet.facts().path("lat").asDouble(), 1e-9);
        assertEquals("36061002601", sheet.facts().path("tract").asText());
        assertTrue(t.contains("Flood zone: AE (FEMA NFHL, "), t);
        assertTrue(t.contains("Schools within about 1.5 km: P.S. 64 (") && t.contains("m, K–5) (NCES directory 2022 via the Urban Institute, "), t);
        assertFalse(t.contains("Far Away High"), "a school 9 km off is not around the home");
        assertTrue(t.contains("Nearest transit stops: Houston St bus (305 m, Statewide Bus); Delancey St (708 m, Example Transit, Inc.); 2 Av ("), "nearest first, from the feeds that hold the point, a continental one left out: " + t);
        assertFalse(t.contains("Harlem"), "a stop 10 km off is not near");
        assertTrue(t.contains("Sales or values on the street: 270 EAST 3 STREET: $2,150,000 on 2026-03-04"), t);
        assertFalse(t.contains("274 EAST 3 STREET"), "a $0 transfer is not a sale");
        assertTrue(t.contains("Permits on the street: 272 EAST 3 STREET: A2 PL permit, 2026-05-06"), t);
        assertTrue(t.contains("Census tract 36061002601: the tract's figures (income, age, owners and renters, home value, rent) need a Census key (RESEARCHZOSHO_CENSUS_KEY), free from api.census.gov/data/key_signup.html; not set."), t);
        assertEquals("EAST 3 STREET", HomeSensors.street("272 East 3 Street #4"));
        // the feeds and the zips are kept for a month
        assertTrue(Files.isRegularFile(store.root().resolve("catalog").resolve("home-cache").resolve("feeds_v2.csv")));
    }

    @Test void theReadersWrittenForACityServeTheirCountyAndPhiladelphiaReadsThroughCarto(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        assertEquals("WPRDC: Allegheny County's property sales", HomeSensors.recordReader("Mount Lebanon", "PA", "Allegheny County"));
        assertEquals("Analyze Boston: the city's property assessment and permits", HomeSensors.recordReader("Chelsea", "MA", "Suffolk County"));
        assertEquals("NYC Open Data: the city's rolling sales and DOB permits", HomeSensors.recordReader("Flushing", "NY", "Queens County"));
        assertEquals("", HomeSensors.recordReader("Scranton", "PA", "Lackawanna County"));
        answers.put("phl.carto.com/api/v2/sql?q=SELECT+location", "{\"rows\": [{\"location\": \"1400 JOHN F KENNEDY BLVD\", \"sale_price\": 1, \"sale_date\": \"2021-09-01T04:00:00Z\", \"market_value\": 3943100, \"year_built\": \"1960\", \"total_livable_area\": 167440, \"category_code_description\": \"OFFICES\"}, {\"location\": \"1500 JOHN F KENNEDY BLVD\", \"sale_price\": 2500000, \"sale_date\": \"2024-04-20T04:00:00Z\", \"market_value\": 2600000, \"year_built\": null, \"total_livable_area\": null, \"category_code_description\": \"VACANT LAND\"}, {\"location\": \"12 MARKET ST\", \"sale_price\": 500000, \"sale_date\": \"2024-01-01T04:00:00Z\", \"market_value\": 1, \"category_code_description\": \"\"}]}");
        answers.put("phl.carto.com/api/v2/sql?q=SELECT+address", "{\"rows\": [{\"address\": \"1880 JOHN F KENNEDY BLVD\", \"permitissuedate\": \"2026-10-29T04:00:00Z\", \"permitdescription\": \"Electrical Permit\", \"typeofwork\": \"Addition and/or Alteration\"}]}");
        Home.Listing l = new Home.Listing("x", "a test", "sale", "1400 John F Kennedy Blvd", "Philadelphia", "PA", "", 1_000_000, 0, 0, 0, "", "", 39.95, -75.16, "", "", "", "");
        String t = HomeSensors.sheet(store, l).text();
        assertTrue(t.contains("Sales or values on the street: 1400 JOHN F KENNEDY BLVD: market value $3,943,100 (offices), built 1960, 167,440 sq ft; 1500 JOHN F KENNEDY BLVD: $2,500,000 on 2024-04-20, market value $2,600,000 (vacant land) (Philadelphia's OPA property records and L&I permits (Carto), "), t);
        assertTrue(t.contains("Permits on the street: 1880 JOHN F KENNEDY BLVD: Electrical Permit, Addition and/or Alteration, 2026-10-29 (Philadelphia's OPA"), t);
    }

    @Test void whenNothingAnswersEveryLineSaysSo(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Home.Listing l = new Home.Listing("x", "a test", "sale", "1 Main St", "Dayton", "OH", "", 200_000, 3, 1, 0, "house", "", 0, 0, "", "", "", "");
        String t = HomeSensors.sheet(store, l).text();
        assertTrue(t.startsWith("Where it is: unknown; the Census geocoder did not place the address"), t);
        assertTrue(t.contains("Sales or values on the street: unknown; no dataset of sales or values with addresses answered for Dayton on Socrata, ArcGIS or CKAN"), t);
        assertTrue(t.contains("Permits on the street: unknown; no dataset of permits with addresses answered for Dayton on Socrata, ArcGIS or CKAN"), t);
        assertFalse(t.contains("Flood zone"), "no point, no flood zone line");
        // placed, but the services are silent
        answers.put("geocoding.geo.census.gov", "{\"result\": {\"addressMatches\": [{\"coordinates\": {\"x\": -84.19, \"y\": 39.76}, \"geographies\": {}}]}}");
        t = HomeSensors.sheet(store, l).text();
        assertTrue(t.contains("Flood zone: unknown; FEMA's flood layer did not answer"), t);
        assertTrue(t.contains("Schools within about 1.5 km: none found in the federal directory for that city, or it did not answer"), t);
        assertTrue(t.contains("Nearest transit stops: none within 1.2 km in the feeds that cover the point, or no feed covers it"), t);
    }
}
