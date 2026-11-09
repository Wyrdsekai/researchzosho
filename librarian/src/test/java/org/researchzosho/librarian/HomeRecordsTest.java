package org.researchzosho.librarian;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.librarian.CareerSources.Hit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** A city's records found by the platform it publishes on, Socrata, ArcGIS or CKAN, on stand-in services: the place's portals first, the datasets checked by their first rows and by the street, kept a month. No web, no map, no model. */
class HomeRecordsTest {

    final Map<String, String> answers = new LinkedHashMap<>();
    final List<String> fetched = new ArrayList<>();
    final List<String> searched = new ArrayList<>();
    volatile List<Hit> hits = List.of();

    @BeforeEach void standIns() {
        HomeSensors.FETCH = u -> { fetched.add(u); for (Map.Entry<String, String> e : answers.entrySet()) if (u.contains(e.getKey())) return e.getValue(); return null; };
        HomeSensors.BYTES = u -> null;
        HomeSensors.MAP_POINT = q -> null;
        HomeRecords.SEARCH = q -> { searched.add(q); return hits; };
        Career.DRIVE = () -> null;
    }

    @AfterEach void back() { HomeSensors.FETCH = CareerSources::get; HomeSensors.BYTES = HomeSensors::fetchBytes; HomeSensors.MAP_POINT = HomeSensors::pointFromMap; HomeRecords.SEARCH = q -> HomeSources.SEARCH.apply(q); Career.DRIVE = Explain::drive; }

    static final String SPRINGFIELD_CATALOG = """
            {"resultSetSize": 3, "results": [
              {"resource": {"id": "abcd-1234", "name": "Property Sales", "description": "Every recorded sale in the city", "columns_field_name": ["property_address", "city", "sale_price", "sale_date", "deed_type", "gross_square_feet", "year_built"]},
               "classification": {"domain_tags": ["property"]}, "metadata": {"domain": "data.springfield.gov"}},
              {"resource": {"id": "sum1-0000", "name": "Summary of Sales by Neighborhood", "description": "", "columns_field_name": ["neighborhood", "median_sale_price"]},
               "classification": {"domain_tags": []}, "metadata": {"domain": "data.springfield.gov"}},
              {"resource": {"id": "link-0000", "name": "Assessor Parcels", "description": "a link to the county's lookup", "columns_field_name": []},
               "classification": {"domain_tags": []}, "metadata": {"domain": "data.springfield.gov"}}
            ]}""";

    @Test void aCitysOwnSocrataPortalIsFoundByPatternItsDatasetCheckedByRowsAndByTheStreetAndKept(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        hits = List.of(new Hit("Springfield - Wikipedia", "https://en.wikipedia.org/wiki/Springfield", ""));
        answers.put("search_context=data.springfield.gov&only=datasets&limit=1", "{\"resultSetSize\": 40, \"results\": []}");
        answers.put("search_context=data.springfield.gov&q=assessor+parcels&only=datasets&limit=20", SPRINGFIELD_CATALOG);
        answers.put("api.us.socrata.com/api/catalog/v1", "{\"resultSetSize\": 0, \"results\": []}");
        answers.put("hub.arcgis.com", "{\"data\": []}");
        answers.put("data.springfield.gov/resource/abcd-1234.json?$limit=5", "[{\"property_address\": \"7 OAK ST\", \"city\": \"SPRINGFIELD\", \"sale_price\": \"200000\", \"sale_date\": \"2026-01-02T00:00:00.000\"}]");
        answers.put("data.springfield.gov/resource/abcd-1234.json?$where", """
                [{"property_address": "120 ELM ST", "city": "SPRINGFIELD", "sale_price": "456,000", "sale_date": "2026-03-04T00:00:00.000", "deed_type": "Warranty Deed", "gross_square_feet": "1,850", "year_built": "1925.0"},
                 {"property_address": "124 ELM ST", "city": "SPRINGFIELD", "sale_price": "0", "sale_date": "2026-01-01T00:00:00.000", "deed_type": "Quit Claim"},
                 {"property_address": "900 ELM ST", "city": "SPRINGFIELD", "sale_price": "310000", "sale_date": "2025-11-11T00:00:00.000", "deed_type": "Quit Claim Deed"}]""");
        HomeRecords.Found f = HomeRecords.find(store, "Springfield", "IL", "Sangamon County", 39.80, -89.65, "ELM ST", 122);
        String looked = String.join("\n", f.looked());
        assertNotNull(f.sales(), looked);
        assertEquals("Socrata", f.sales().platform()); assertEquals("abcd-1234", f.sales().id()); assertEquals("data.springfield.gov", f.sales().site());
        assertEquals("property_address", f.sales().field("address")); assertEquals("sale_price", f.sales().field("price")); assertEquals("deed_type", f.sales().field("desc"));
        assertNull(f.permits());
        assertTrue(looked.contains("the place's portals: ") && looked.contains("Socrata data.springfield.gov; ArcGIS Hub none; CKAN none"), looked);
        assertTrue(looked.contains("passed over Assessor Parcels (data.springfield.gov): no columns, a link or a map rather than a table"), looked);
        assertTrue(looked.contains("Socrata, 1 portal, 3 queries for sales: 3 datasets seen, 1 candidate, [Property Sales (Socrata, data.springfield.gov)]"), looked);
        assertTrue(looked.contains("kept Property Sales (Socrata, data.springfield.gov): it answered for ELM ST"), looked);
        assertTrue(looked.contains("Socrata, 1 portal, 2 queries for permits: 0 datasets seen, 0 candidates"), looked);
        assertTrue(fetched.stream().anyMatch(u -> u.contains("$where=%28upper%28property_address%29+like+%27%25+ELM+ST%27+OR+upper%28property_address%29+like+%27%25+ELM+ST+%23%25%27") && u.contains("AND+upper%28city%29+like+%27%25SPRINGFIELD%25%27")), "the street as a whole word, at the end or before a unit: " + String.join("\n", fetched));
        assertEquals(List.of("Springfield IL open data portal", "Sangamon County IL GIS open data parcels"), searched);
        List<String> prices = HomeRecords.prices(f.sales(), "ELM ST", 122, "Springfield");
        assertEquals(List.of("120 ELM ST: $456,000 on 2026-03-04 (warranty deed), built 1925, 1,850 sq ft", "900 ELM ST: $310,000 on 2025-11-11 (quit claim deed)"), prices, "commas read, the $0 transfer left out, the kind of sale kept, the nearest number first");
        // kept a month: the second look asks nothing
        int before = fetched.size();
        assertEquals("abcd-1234", HomeRecords.find(store, "Springfield", "IL", "Sangamon County", 39.80, -89.65, "ELM ST", 122).sales().id());
        assertEquals(before, fetched.size(), "no fetch for a kept answer");
        assertTrue(Files.isRegularFile(store.root().resolve("catalog").resolve("home-cache").resolve("records-springfield-il.json")));
        // the card's sheet reads through it
        Home.Listing l = new Home.Listing("x", "a test", "sale", "122 Elm St", "Springfield", "IL", "", 450_000, 3, 2, 0, "house", "", 39.80, -89.65, "", "", "", "");
        String t = HomeSensors.sheet(store, l).text();
        assertTrue(t.contains("Sales or values on the street: 120 ELM ST: $456,000 on 2026-03-04 (warranty deed), built 1925, 1,850 sq ft; 900 ELM ST: $310,000 on 2025-11-11 (quit claim deed) (Property Sales (Socrata, data.springfield.gov), "), t);
        assertTrue(t.contains("Permits on the street: unknown; no dataset of permits with addresses answered for Springfield on Socrata, ArcGIS or CKAN"), t);
    }

    @Test void theColumnsTheRowsCarryAreChosenBeforeOnesTheCatalogueAloneLists() {
        List<String> names = List.of("permitnum", "location1_city", "location1_zip", "originaladdress1", "originalcity", "originalzip", "description", "issueddate", "sale_price");
        List<Map<String, String>> rows = List.of(Map.of("permitnum", "1", "originaladdress1", "600 4TH AVE", "originalcity", "SEATTLE", "originalzip", "98104", "description", "Alter", "issueddate", "2025-01-02"));
        Map<String, String> fs = HomeRecords.columns(names, "permits", rows);
        assertEquals("originalcity", fs.get("city"), "the catalogue's location sub-column answers nothing when asked; the column the rows carry is the city");
        assertEquals("originalzip", fs.get("zip"));
        assertEquals("sale_price", HomeRecords.columns(names, "sales", rows).get("price"), "a column null in every sample row is still taken when no row carries another");
    }

    @Test void aDatasetWhoseFirstRowsShowNoAddressesIsPassedOverAndSaidSo(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        answers.put("search_context=data.springfield.gov&only=datasets&limit=1", "{\"resultSetSize\": 40, \"results\": []}");
        answers.put("search_context=data.springfield.gov&q=assessor+parcels&only=datasets&limit=20", SPRINGFIELD_CATALOG);
        answers.put("api.us.socrata.com/api/catalog/v1", "{\"resultSetSize\": 0, \"results\": []}");
        answers.put("hub.arcgis.com", "{\"data\": []}");
        answers.put("data.springfield.gov/resource/abcd-1234.json?$limit=5", "[{\"property_address\": \"CENTRAL\", \"sale_price\": \"200000\"}, {\"property_address\": \"NORTH END\", \"sale_price\": \"1\"}]");   // a neighbourhood, not an address
        HomeRecords.Found f = HomeRecords.find(store, "Springfield", "IL", "", 39.80, -89.65, "ELM ST", 122);
        assertNull(f.sales());
        assertTrue(String.join("\n", f.looked()).contains("passed over Property Sales (Socrata, data.springfield.gov): its first rows show no column of addresses"), String.join("\n", f.looked()));
    }

    @Test void salesKeyedByParcelAloneAreJoinedToTheAddressesOfTheCountysPortal(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        answers.put("search_context=datacatalog.cookcountyil.gov&only=datasets&limit=1", "{\"resultSetSize\": 500, \"results\": []}");
        answers.put("search_context=datacatalog.cookcountyil.gov&q=property+sales&only=datasets&limit=20", """
                {"results": [{"resource": {"id": "wvhk-0000", "name": "Assessor - Parcel Sales", "description": "", "columns_field_name": ["pin", "sale_price", "sale_date", "deed_type", "sale_type", "township_code"]}, "classification": {"domain_tags": []}, "metadata": {"domain": "datacatalog.cookcountyil.gov"}},
                             {"resource": {"id": "perm-0000", "name": "Assessor - Permits", "description": "", "columns_field_name": ["pin", "address", "amount", "date_issued"]}, "classification": {"domain_tags": []}, "metadata": {"domain": "datacatalog.cookcountyil.gov"}}]}""");
        answers.put("search_context=datacatalog.cookcountyil.gov&q=parcel+addresses&only=datasets&limit=10", """
                {"results": [{"resource": {"id": "exem-0000", "name": "Assessor - Property Tax-Exempt Parcels", "columns_field_name": ["pin", "prop_address_full", "prop_address_city_name"]}, "metadata": {"domain": "datacatalog.cookcountyil.gov"}},
                             {"resource": {"id": "3723-0000", "name": "Assessor - Parcel Addresses", "columns_field_name": ["prop_address_state", "pin10", "prop_address_full", "prop_address_city_name", "year", "pin"]}, "metadata": {"domain": "datacatalog.cookcountyil.gov"}}]}""");
        answers.put("api.us.socrata.com/api/catalog/v1", "{\"resultSetSize\": 0, \"results\": []}");
        answers.put("hub.arcgis.com", "{\"data\": []}");
        answers.put("datacatalog.cookcountyil.gov/resource/wvhk-0000.json?$limit=5", "[{\"pin\": \"1\", \"sale_price\": \"100000\", \"sale_date\": \"2020-01-01T00:00:00.000\", \"deed_type\": \"Warranty\"}]");
        answers.put("datacatalog.cookcountyil.gov/resource/3723-0000.json", "[{\"pin\": \"17094195690000\", \"pin10\": \"1709419569\", \"prop_address_full\": \"221 N LASALLE ST\", \"prop_address_city_name\": \"CHICAGO\"}, {\"pin\": \"17094570130000\", \"pin10\": \"1709457013\", \"prop_address_full\": \"30 N LASALLE ST\", \"prop_address_city_name\": \"CHICAGO\"}]");
        answers.put("datacatalog.cookcountyil.gov/resource/wvhk-0000.json?$where", "[{\"pin\": \"17094570130000\", \"sale_price\": \"2150000\", \"sale_date\": \"2025-06-30T00:00:00.000\", \"deed_type\": \"Warranty\", \"sale_type\": \"\"}]");
        HomeRecords.Found f = HomeRecords.find(store, "Chicago", "IL", "Cook County", 41.88, -87.63, "N LASALLE ST", 100);
        String looked = String.join("\n", f.looked());
        assertNotNull(f.sales(), looked);
        assertEquals("3723-0000", f.sales().joinId(), "the parcel addresses, not the narrower list of exempt parcels; the permits dataset is no sales dataset");
        assertEquals("pin", f.sales().joinFields().get("key"), "joined on the sales' own key, not pin10");
        assertTrue(looked.contains("Socrata datacatalog.cookcountyil.gov;"), "the county's portal by its pattern: " + looked);
        assertTrue(fetched.stream().anyMatch(u -> u.contains("wvhk-0000.json?$where=pin+in+%28%2717094195690000%27%2C%2717094570130000%27%29")), String.join("\n", fetched));
        assertEquals(List.of("30 N LASALLE ST: $2,150,000 on 2025-06-30 (warranty)"), HomeRecords.prices(f.sales(), "N LASALLE ST", 100, "Chicago"));
    }

    @Test void theCitysOwnLayerIsTriedBeforeTheCountys() {
        int own = HomeRecords.hubScore(true, "city of los angeles", "city", "LA City Parcels JobAccess", "sales");
        int county = HomeRecords.hubScore(true, "county of los angeles", "county", "Assessor Parcel Data Roll 2026", "sales");
        int far = HomeRecords.hubScore(false, "some company", "other", "Parcels", "sales");
        assertTrue(own > county, own + " vs " + county + ": the city's own parcels before the county's roll, which answers for every town");
        assertTrue(county > far, county + " vs " + far);
    }

    @Test void aCountyNamedAfterItsCityIsTheCountys() {
        assertEquals("county", HomeRecords.scopeOf("County of Los Angeles LA County Permitting (EPIC-LA Case History)", "Los Angeles", "Los Angeles County", "CA"), "the county's organisation names the county, not the city of the same name");
        assertEquals("county", HomeRecords.scopeOf("Los Angeles County Assessor Parcel Data Roll 2026", "Los Angeles", "Los Angeles County", "CA"));
        assertEquals("city", HomeRecords.scopeOf("City of Los Angeles Hub Building Permits", "Los Angeles", "Los Angeles County", "CA"));
        assertEquals("city", HomeRecords.scopeOf("City and County of Denver Parcels", "Denver", "Denver County", "CO"), "a city that is its own county is named by both");
        assertEquals("county", HomeRecords.scopeOf("Travis County Parcels", "Austin", "Travis County", "TX"));
        assertEquals("city", HomeRecords.scopeOf("data.austintexas.gov Issued Building Permits", "Austin", "Travis County", "TX"));
        assertEquals("other", HomeRecords.scopeOf("services.arcgis.com Permit Detail", "Irving", "Dallas County", "TX"));
    }

    @Test void twoLayersOfOneSiteAtMostSoAnotherSitesRollIsTried() {
        answers.put("fields%5Bdatasets%5D=orgName", "{\"data\": [{\"attributes\": {\"orgName\": \"City of Fort Worth\"}}]}");
        answers.put("filter%5BorgName%5D=City+of+Fort+Worth&", "{\"data\": [{\"attributes\": {\"name\": \"Parcels\", \"orgName\": \"City of Fort Worth\", \"url\": \"https://services-a.example-arcgis.com/a/arcgis/rest/services/Parcels/FeatureServer/0\", \"extent\": {\"coordinates\": [[-97.6, 32.5], [-97.0, 33.0]]}, \"fields\": [{\"name\": \"SITUS_ADDR\"}, {\"name\": \"TOTAL_VALUE\"}]}}, {\"attributes\": {\"name\": \"CodeOps Parcels\", \"orgName\": \"City of Fort Worth\", \"url\": \"https://services-a.example-arcgis.com/b/arcgis/rest/services/CodeOps_Parcels/FeatureServer/0\", \"extent\": {\"coordinates\": [[-97.6, 32.5], [-97.0, 33.0]]}, \"fields\": [{\"name\": \"SITUS_ADDR\"}, {\"name\": \"TOTAL_VALUE\"}]}}, {\"attributes\": {\"name\": \"Parcels Public View\", \"orgName\": \"City of Fort Worth\", \"url\": \"https://services-a.example-arcgis.com/c/arcgis/rest/services/Parcels_Public_View/FeatureServer/0\", \"extent\": {\"coordinates\": [[-97.6, 32.5], [-97.0, 33.0]]}, \"fields\": [{\"name\": \"SITUS_ADDR\"}, {\"name\": \"TOTAL_VALUE\"}]}}, {\"attributes\": {\"name\": \"TADParcels\", \"orgName\": \"City of Fort Worth\", \"url\": \"https://mapit.example-county.gov/d/arcgis/rest/services/TADParcels/FeatureServer/0\", \"extent\": {\"coordinates\": [[-97.6, 32.5], [-97.0, 33.0]]}, \"fields\": [{\"name\": \"SITUS_ADDR\"}, {\"name\": \"TOTAL_VALUE\"}]}}]}");
        answers.put("hub.arcgis.com", "{\"data\": []}");
        List<String> looked = new ArrayList<>();
        List<HomeRecords.Dataset> p = HomeRecords.arcgis(List.of(), "Fort Worth", "TX", "Tarrant County", 32.75, -97.33, "sales", looked);
        assertEquals(List.of("services-a.example-arcgis.com", "services-a.example-arcgis.com", "mapit.example-county.gov"), p.stream().map(HomeRecords.Dataset::site).toList(), "the third layer of the one site gives way to the other site's roll: " + String.join("\n", looked));
    }

    @Test void aTableOfThePlacesOwnOrganisationHasNoExtentAndIsStillACandidate() {
        answers.put("fields%5Bdatasets%5D=orgName", "{\"data\": [{\"attributes\": {\"orgName\": \"City of Irving\"}}]}");
        answers.put("filter%5BorgName%5D=City+of+Irving&", """
                {"data": [{"attributes": {"name": "Residential Permits Issued: Feb 15 2022 Through Present", "orgName": "City of Irving", "url": "https://services.example-arcgis.com/irv/arcgis/rest/services/Residential_Permits/FeatureServer/0",
                                          "extent": null, "fields": [{"name": "Issued_Date"}, {"name": "Project_Description"}, {"name": "Permit_Type"}, {"name": "Address"}, {"name": "Valuation"}]}},
                          {"attributes": {"name": "Demolition Permits", "orgName": "Dallas County", "url": "https://services.example-arcgis.com/dal/arcgis/rest/services/Demolition_Permits/FeatureServer/0",
                                          "extent": {"coordinates": [[-120.0, 35.0], [-119.0, 36.0]]}, "fields": [{"name": "ADDRESS"}, {"name": "ISSUE_DATE"}]}}]}""");
        answers.put("hub.arcgis.com", "{\"data\": []}");
        List<String> looked = new ArrayList<>();
        List<HomeRecords.Dataset> p = HomeRecords.arcgis(List.of(), "Irving", "TX", "Dallas County", 32.81, -96.95, "permits", looked);
        assertEquals(1, p.size(), String.join("\n", looked));
        assertEquals("Residential Permits Issued: Feb 15 2022 Through Present", p.get(0).name(), "a permits table with no geometry has no extent on the Hub; the place's own organisation answered it, so it stands");
        assertEquals("city", p.get(0).field("scope"));
        assertEquals("Address", p.get(0).field("address"));
    }

    @Test void thePlacesOwnHubSiteAndACkanPortalComeFromTheWebSearch(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        hits = List.of(new Hit("Denver Open Data", "https://gis-denver-example.hub.arcgis.com/", ""), new Hit("Open Data | Springfield", "https://data.springfield-example.gov/", ""), new Hit("Denver - Wikipedia", "https://en.wikipedia.org/wiki/Denver", ""));
        answers.put("api.us.socrata.com", "{\"resultSetSize\": 0, \"results\": []}");
        answers.put("gis-denver-example.hub.arcgis.com/api/v3/datasets?q=parcels&page%5Bsize%5D=1", "{\"data\": []}");
        answers.put("hub.arcgis.com/api/v3/datasets?q=%22Denver+County%22+CO&page%5Bsize%5D=40&fields%5Bdatasets%5D=orgName", "{\"data\": [{\"attributes\": {\"orgName\": \"City and County of Denver\"}}, {\"attributes\": {\"orgName\": \"City and County of Denver\"}}, {\"attributes\": {\"orgName\": \"University of Denver\"}}, {\"attributes\": {\"orgName\": \"Esri\"}}]}");
        answers.put("hub.arcgis.com/api/v3/datasets?q=parcels&filter%5BorgName%5D=City+and+County+of+Denver&page%5Bsize%5D=40", """
                {"data": [
                  {"attributes": {"name": "Parcels", "orgName": "City and County of Denver", "url": "https://services.example-arcgis.com/abc/arcgis/rest/services/Parcels/FeatureServer/0",
                                  "extent": {"coordinates": [[-105.11, 39.61], [-104.60, 39.91]]}, "fields": [{"name": "SITUS_ADDR"}, {"name": "SITUS_CITY"}, {"name": "TOTAL_VALU"}, {"name": "LAND_VALUE"}, {"name": "PROP_CLASS"}]}},
                  {"attributes": {"name": "Assessor Parcels", "orgName": "", "url": "https://services.example-arcgis.com/far/arcgis/rest/services/Assessor_Parcels/FeatureServer/0",
                                  "extent": {"coordinates": [[-120.0, 35.0], [-119.0, 36.0]]}, "fields": [{"name": "S_STR_NM"}, {"name": "S_STR_NBR"}, {"name": "TOTAL_VALUE"}]}},
                  {"attributes": {"name": "ENG Permits Parcels", "orgName": "", "url": "https://services.example-arcgis.com/all/arcgis/rest/services/Everything/FeatureServer/0",
                                  "extent": {"coordinates": [[-125.0, 25.0], [-66.0, 49.0]]}, "fields": [{"name": "Street"}, {"name": "TOTAL_VALUE"}]}}
                ]}""");
        answers.put("gis-denver-example.hub.arcgis.com", "{\"data\": []}");
        answers.put("hub.arcgis.com", "{\"data\": []}");
        answers.put("services.example-arcgis.com/abc/arcgis/rest/services/Parcels/FeatureServer/0/query?where=1%3D1", "{\"features\": [{\"attributes\": {\"SITUS_ADDR\": \"1 E COLFAX AVE\", \"SITUS_CITY\": \"DENVER\", \"TOTAL_VALU\": 1}}]}");
        answers.put("services.example-arcgis.com/abc/arcgis/rest/services/Parcels/FeatureServer/0/query", """
                {"features": [{"attributes": {"SITUS_ADDR": "1500 E COLFAX AVE", "SITUS_CITY": "DENVER", "TOTAL_VALU": 812000, "LAND_VALUE": 300000, "PROP_CLASS": "RES"}},
                              {"attributes": {"SITUS_ADDR": "1600 E COLFAX AVE", "SITUS_CITY": "DENVER", "TOTAL_VALU": 0, "LAND_VALUE": 0, "PROP_CLASS": "RES"}}]}""");
        answers.put("data.springfield-example.gov/api/3/action/package_search?q=property&rows=1", "{\"success\": true, \"result\": {\"results\": []}}");
        answers.put("data.springfield-example.gov/api/3/action/package_search?q=building+permits", """
                {"success": true, "result": {"results": [{"title": "Building Permits", "resources": [{"id": "r-1", "name": "permits 2026", "datastore_active": true}]}]}}""");
        answers.put("data.springfield-example.gov/api/3/action/package_search", "{\"success\": true, \"result\": {\"results\": []}}");
        answers.put("data.springfield-example.gov/api/3/action/datastore_search?resource_id=r-1&limit=5", "{\"result\": {\"fields\": [{\"id\": \"address\"}, {\"id\": \"city\"}, {\"id\": \"description\"}, {\"id\": \"issued_date\"}], \"records\": [{\"address\": \"2 MAIN ST\", \"city\": \"DENVER\", \"description\": \"Roof\", \"issued_date\": \"2026-01-01\"}]}}");   // another place's portal: its city column is what lets it serve a home in Denver
        answers.put("data.springfield-example.gov/api/3/action/datastore_search?resource_id=r-1&q=COLFAX", "{\"result\": {\"records\": [{\"address\": \"1510 E COLFAX AVE\", \"city\": \"DENVER\", \"description\": \"Kitchen remodel\", \"issued_date\": \"2026-05-06\"}, {\"address\": \"9 OTHER ST\", \"city\": \"DENVER\", \"description\": \"Roof\", \"issued_date\": \"2026-05-07\"}]}}");
        HomeRecords.Found f = HomeRecords.find(store, "Denver", "CO", "Denver County", 39.74, -104.98, "E COLFAX AVE", 1520);
        String looked = String.join("\n", f.looked());
        assertTrue(looked.contains("ArcGIS Hub gis-denver-example.hub.arcgis.com; CKAN data.springfield-example.gov"), looked);
        assertNotNull(f.sales(), looked);
        assertEquals("ArcGIS", f.sales().platform()); assertEquals("Parcels", f.sales().name());
        assertTrue(looked.contains("ArcGIS Hub, 6 organisations of the place's own (City and County of Denver, City of Denver, Denver GIS, City of Denver GIS, Denver County, County of Denver) and the Hub at large, 14 queries for sales: 3 hits, 3 layers with an address, 1 whose extent holds the home, 1 candidate"), "the far layer and the continental one are left out: " + looked);
        assertEquals(List.of("1500 E COLFAX AVE: assessed at $812,000"), HomeRecords.prices(f.sales(), "E COLFAX AVE", 1520, "Denver"));
        assertNotNull(f.permits(), looked);
        assertEquals("CKAN", f.permits().platform());
        assertEquals(List.of("1510 E COLFAX AVE: Kitchen remodel, 2026-05-06"), HomeRecords.permits(f.permits(), "E COLFAX AVE", 1520, "Denver"), "a free-text match on another street is left out");
        assertEquals("2026-03-04", HomeRecords.day("2026-03-04T00:00:00.000")); assertEquals("1997-05-16", HomeRecords.day("863740800000.0"), "Socrata and ArcGIS write some dates as milliseconds");
        assertEquals("LASALLE", HomeRecords.core("N LASALLE ST")); assertEquals("3", HomeRecords.core("EAST 3 STREET")); assertEquals("COLFAX", HomeRecords.core("E COLFAX AVE"));
        assertEquals("LA SALLE", HomeRecords.alt("LASALLE")); assertEquals("", HomeRecords.alt("COLFAX"));
        assertEquals("425 MARKET ST", HomeRecords.tidy("0000 0425 MARKET              ST0000"), "a roll's padding zeros come off");
        assertEquals("1114 MARKET ST", HomeRecords.tidy("0000 1114 MARKET ST           ST0000"), "a suffix written twice");
        // a roll that pads its addresses answers the whole word with a row or two: the core is asked as well, and the rows the whole word missed are kept by the whole street
        answers.put("data.sf-example.gov/resource/roll-0000.json?$where=%28upper%28property_location%29+like+%27%25+MARKET+ST%27", "[{\"property_location\": \"0000 1114 MARKET ST           ST0000\", \"assessed_land_value\": \"100000\", \"assessed_improvement_value\": \"200000\"}]");
        answers.put("data.sf-example.gov/resource/roll-0000.json?$where=%28upper%28property_location%29+like+%27%25+MARKET%25%27", "[{\"property_location\": \"0000 0765 MARKET              ST0000\", \"assessed_land_value\": \"300000\", \"assessed_improvement_value\": \"400000\"}, {\"property_location\": \"0000 1114 MARKET ST           ST0000\", \"assessed_land_value\": \"100000\", \"assessed_improvement_value\": \"200000\"}, {\"property_location\": \"0000 0000 MARKET              ST0000\", \"assessed_land_value\": \"5\", \"assessed_improvement_value\": \"5\"}]");
        HomeRecords.Dataset roll = new HomeRecords.Dataset("Socrata", "data.sf-example.gov", "roll-0000", "Roll", Map.of("address", "property_location", "value", "assessed_land_value+assessed_improvement_value"), "", Map.of());
        assertEquals(List.of("1114 MARKET ST: assessed at $300,000", "765 MARKET ST: assessed at $700,000"), HomeRecords.prices(roll, "MARKET ST", 1200, "San Francisco"), "the whole word's row once, the core's rows added, a parcel with no number left out");
        // a row counts only when its address is on the street
        assertTrue(HomeRecords.onStreet("925 4TH AVE", "4TH AVE")); assertFalse(HomeRecords.onStreet("518 34TH AVE", "4TH AVE"), "34th is not 4th"); assertFalse(HomeRecords.onStreet("711 4TH AVE N", "4TH AVE"), "4th Ave N is another street");
        assertTrue(HomeRecords.onStreet("22 N LASALLE ST #1200", "N LASALLE ST")); assertTrue(HomeRecords.onStreet("1437 N BANNOCK ST", "BANNOCK ST"), "a direction the person left off"); assertTrue(HomeRecords.onStreet("1229 BANNOCK ST RESD", "BANNOCK ST"));
        assertTrue(HomeRecords.onStreet("200 N SPRING ST 3rd flr", "N SPRING ST")); assertTrue(HomeRecords.onStreet("1221 SW 4TH AVE #130", "SW 4TH AVE")); assertTrue(HomeRecords.onStreet("389 Congress Street", "CONGRESS ST"), "the suffix written long");
        assertTrue(HomeRecords.onStreet("425 MARKET ST", "MARKET"), "the core alone, as a second look asks"); assertTrue(HomeRecords.onStreet("120 LA SALLE ST", "LA SALLE")); assertFalse(HomeRecords.onStreet("Congress Ave", "CONGRESS ST"), "no house number, no home");
        assertFalse(HomeRecords.onStreet("1 MARKET PLAZA", "MARKET ST"));
        assertTrue(HomeRecords.onStreet("W 301 2ND ST", "W 2ND ST"), "an appraiser who writes the direction before the number"); assertTrue(HomeRecords.onStreet("S 500 2ND ST   TX 78660", "2ND ST"), "the state and the zip at the end");
        assertTrue(HomeRecords.nameFits("Multnomah County Taxlot Parcels", "sales"), "a county is not a count");
        assertEquals("SITUSADDR", HomeRecords.chooseAddress(List.of("ADDR1", "SITUSADDR"), List.of(Map.of("ADDR1", "12 SE MAIN ST", "SITUSADDR", "1221 SW 4TH AVE"))), "the situs before an owner's mailing address"); assertFalse(HomeRecords.onStreet("505 S 4TH ST", "4TH AVE"), "4th St is not 4th Ave, whichever way it is asked");
    }

    @Test void whenNoPortalAndNoPlatformAnswersTheLookIsKeptAWeekAndSaysWhatWasTried(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        HomeRecords.Found f = HomeRecords.find(store, "Dayton", "OH", "Montgomery County", 39.76, -84.19, "MAIN ST", 1);
        assertNull(f.sales()); assertNull(f.permits());
        String looked = String.join("\n", f.looked());
        assertTrue(looked.contains("Socrata none; ArcGIS Hub none; CKAN none"), looked);
        assertTrue(looked.contains("Socrata's catalogue at large, 2 queries for sales naming the place: 0 candidates") && looked.contains("ArcGIS Hub, 6 organisations of the place's own (City of Dayton, Dayton GIS, City of Dayton GIS, Montgomery County, County of Montgomery, Montgomery County GIS) and the Hub at large, 14 queries for sales: 0 hits"), looked);
        int before = fetched.size();
        HomeRecords.find(store, "Dayton", "OH", "Montgomery County", 39.76, -84.19, "MAIN ST", 1);
        assertEquals(before, fetched.size(), "a look that found nothing is kept a week");
        HomeRecords.Found unplaced = HomeRecords.find(store, "Elsewhere", "OH", "", 0, 0, "MAIN ST", 1);
        assertTrue(String.join("\n", unplaced.looked()).contains("ArcGIS Hub not asked: the home is not placed on the map"));
        List<String> g = HomeRecords.socrataGuesses("San Francisco", "CA", "San Francisco County");
        assertTrue(g.contains("data.sanfrancisco.gov") && g.contains("data.cityofsanfrancisco.org") && g.contains("data.sanfranciscocalifornia.gov") && g.contains("data.california.gov") && g.contains("data.ca.gov"), g.toString());
        assertTrue(HomeRecords.socrataGuesses("Chicago", "IL", "Cook County").contains("datacatalog.cookcountyil.gov"));
    }

    @Test void theStatesOwnParcelsAreReadFirstWhenTheyCarrySalesAndAStaleDatasetLosesToACurrentOne(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        HomeRecords.Dataset md = HomeRecords.stateLayer("MD");
        assertNotNull(md); assertEquals("ADDRESS", md.field("address")); assertEquals("CONSIDR1", md.field("price")); assertEquals("mdgeodata.md.gov", md.site());
        assertNull(HomeRecords.stateLayer("NJ"), "no statewide layer with values found for New Jersey");
        assertNotNull(HomeRecords.stateLayer("wa"));
        // Maryland: the state's layer answers for the street before any portal is asked for datasets
        answers.put("api.us.socrata.com", "{\"resultSetSize\": 0, \"results\": []}");
        answers.put("hub.arcgis.com", "{\"data\": []}");
        answers.put("mdgeodata.md.gov/imap/rest/services/PlanningCadastre/MD_PropertyData/MapServer/0/query", "{\"features\": [{\"attributes\": {\"ADDRESS\": \"100 HOLLIDAY ST\", \"CITY\": \"BALTIMORE\", \"CONSIDR1\": 0, \"TRADATE\": \"19990101\", \"NFMTTLVL\": 5000000, \"YEARBLT\": 1875, \"SQFTSTRC\": 20000}}, {\"attributes\": {\"ADDRESS\": \"120 HOLLIDAY ST\", \"CITY\": \"BALTIMORE\", \"CONSIDR1\": 450000, \"TRADATE\": \"20240315\", \"NFMTTLVL\": 480000, \"YEARBLT\": 1920, \"SQFTSTRC\": 1800}}]}");
        HomeRecords.Found f = HomeRecords.find(store, "Baltimore", "MD", "Baltimore City", 39.29, -76.61, "HOLLIDAY ST", 100);
        assertNotNull(f.sales(), String.join("\n", f.looked()));
        assertEquals("mdgeodata.md.gov", f.sales().site());
        assertTrue(String.join("\n", f.looked()).contains("the state's own parcels: Maryland Property Data"), String.join("\n", f.looked()));
        assertEquals(List.of("100 HOLLIDAY ST: assessed at $5,000,000, built 1875, 20,000 sq ft", "120 HOLLIDAY ST: $450,000 on 2024-03-15, built 1920, 1,800 sq ft"), HomeRecords.prices(f.sales(), "HOLLIDAY ST", 100, "Baltimore"));
        // freshness: a dataset whose rows stop years back loses to a current one, and is said to be stale when kept alone
        HomeRecords.Dataset old = new HomeRecords.Dataset("Socrata", "data.example.gov", "old0-0000", "Housing and Building Permits 2015 to 2018", Map.of("address", "address", "date", "issued"), "", Map.of(), "2018-12-31");
        HomeRecords.Dataset now = new HomeRecords.Dataset("Socrata", "data.example.gov", "new0-0000", "Building Permits", Map.of("address", "address", "date", "issued"), "", Map.of(), "2026-10-01");
        assertTrue(old.stale() && !now.stale());
        assertEquals(List.of(now, old), HomeRecords.freshFirst(List.of(old, now)));
        assertEquals("Housing and Building Permits 2015 to 2018, last updated 2018 (Socrata, data.example.gov)", old.reader());
        answers.put("data.example.gov/resource/old0-0000.json?$select=max%28issued%29+as+m", "[{\"m\": \"2018-12-31T00:00:00.000\"}]");
        assertEquals("2018-12-31", HomeRecords.latest(old.withLatest("")), "the newest row, asked of the service");
        assertEquals("2020", HomeRecords.latest(new HomeRecords.Dataset("Socrata", "data.example.gov", "x", "2020 Construction Permits", Map.of("address", "a"), "", Map.of())), "a year in the title when nothing else says");
        // the kinds
        assertFalse(HomeRecords.nameFits("County of Santa Clara Permitted Body Art Facilities", "permits")); assertFalse(HomeRecords.nameFits("SWQ Industrial Permit", "permits")); assertFalse(HomeRecords.nameFits("Loading Zone Permits", "permits")); assertFalse(HomeRecords.nameFits("ROW Construction Permits", "permits"));
        assertFalse(HomeRecords.nameFits("May 2023 T Sale Properties", "sales")); assertFalse(HomeRecords.nameFits("Assessed Value Analysis WSL2", "sales")); assertTrue(HomeRecords.nameFits("Parcels w/ Owner Information & Assessed Values", "sales"));
        assertFalse(HomeRecords.buildingWork(List.of(Map.of("d", "Lane closure", "s", "Main St"), Map.of("d", "Street occupancy", "s", "Elm St")), Map.of("desc", "d")), "the rows show no building work");
        assertTrue(HomeRecords.buildingWork(List.of(Map.of("d", "Lane closure"), Map.of("d", "Interior remodel of kitchen")), Map.of("desc", "d")));
        assertTrue(HomeRecords.buildingWork(List.of(Map.of("d", "B", "subtype", "CONSTRUCTION")), Map.of("desc", "d")), "the work named in another column counts");
        assertTrue(HomeRecords.buildingWork(List.of(Map.of("x", "1")), Map.of("date", "x")), "no words at all: the name decides");
        // a number and a street name hold no direction and no suffix: checked by the street's core
        HomeRecords.Dataset ns = new HomeRecords.Dataset("ArcGIS", "x.example.gov", "https://x.example.gov/arcgis/rest/services/P/MapServer/10", "Parcels w/ Assessed Values", Map.of("number", "STNUMBER", "street", "STREET_NAME", "value", "AV_TOTAL"), "", Map.of());
        answers.put("x.example.gov/arcgis/rest/services/P/MapServer/10/query", "{\"features\": [{\"attributes\": {\"STNUMBER\": \"200\", \"STREET_NAME\": \"WASHINGTON\", \"AV_TOTAL\": 900000}}, {\"attributes\": {\"STNUMBER\": \"5\", \"STREET_NAME\": \"WASHINGTON BLVD\", \"AV_TOTAL\": 700000}}]}");
        assertEquals(List.of("200 WASHINGTON: assessed at $900,000"), HomeRecords.prices(ns, "E WASHINGTON ST", 200, "Indianapolis"), "the bare street name counts, another suffix does not");
        // a values roll is said by its year and never set below a current sales set; sales and permits keep "last updated"
        HomeRecords.Dataset roll = new HomeRecords.Dataset("ArcGIS", "gis.example.gov", "https://gis.example.gov/arcgis/rest/services/P/MapServer/10", "Parcels w/ Assessed Values", Map.of("number", "STNUMBER", "street", "STREET_NAME", "value", "ASSESSORYEAR_TOTALAV"), "", Map.of(), "roll 2022");   // as the settling of a sales candidate with a value and no price marks it
        assertTrue(roll.roll()); assertFalse(roll.stale(), "a roll is yearly, not stale on the catalogue's account");
        assertEquals("2025", HomeRecords.rollYear(List.of("STNUMBER", "ASSESSORYEAR", "ASSESSORYEAR_TOTALAV"), List.of(Map.of("ASSESSORYEAR", "2024"), Map.of("ASSESSORYEAR", "2025")), roll), "the roll's own year column");
        assertEquals("2022", HomeRecords.rollYear(List.of("STNUMBER", "ASSESSORYEAR_TOTALAV"), List.of(Map.of("STNUMBER", "1")), roll), "else the portal's year");
        assertEquals("Parcels w/ Assessed Values, roll of 2025 (ArcGIS, gis.example.gov)", roll.withLatest("roll 2025").reader());
        assertEquals(List.of(roll, now), HomeRecords.freshFirst(List.of(roll, now)), "a roll keeps its place");
        // a county-wide set, or another place's, must carry a city or a zip column for a home outside its own city
        HomeRecords.Dataset countyWide = new HomeRecords.Dataset("Socrata", "data.dallascounty-example.gov", "x", "Building Permits", Map.of("address", "address", "date", "issued", "scope", "county"), "", Map.of());
        assertFalse(HomeRecords.tellsTheCity(countyWide, "Irving"));
        HomeRecords.Dataset zipped = new HomeRecords.Dataset("Socrata", "s", "x", "Building Permits", Map.of("address", "address", "date", "issued", "scope", "county", "zip", "zip"), "", Map.of());
        assertFalse(HomeRecords.tellsTheCity(zipped, "Irving"), "a zip column alone, with no zip in hand, does not");
        HomeRecords.ZIP.set("75060"); try { assertTrue(HomeRecords.tellsTheCity(zipped, "Irving"), "a zip column tells the city apart when the home's zip is known"); } finally { HomeRecords.ZIP.remove(); }
        assertTrue(HomeRecords.tellsTheCity(new HomeRecords.Dataset("Socrata", "s", "x", "Building Permits", Map.of("address", "address", "date", "issued", "scope", "city"), "", Map.of()), "Dallas"), "the home's own city's needs none");
        assertEquals("city", HomeRecords.scopeOf("data.cityofdallas.org Building Permits", "Dallas", "Dallas County", "TX")); assertEquals("county", HomeRecords.scopeOf("County of Los Angeles LA County Permitting (EPIC)", "Pasadena", "Los Angeles County", "CA")); assertEquals("other", HomeRecords.scopeOf("services.arcgis.com Building Permits", "Irving", "Dallas County", "TX"));
        assertEquals("zip", HomeRecords.columns(List.of("address", "zip", "issued_date", "description"), "permits").get("zip"));
        // the home's zip tells its town apart on a set with a zip column and no city column; an address carrying another town after a comma is left out
        HomeRecords.Dataset byZip = new HomeRecords.Dataset("ArcGIS", "x.example.gov", "https://x.example.gov/arcgis/rest/services/E/FeatureServer/0", "LA County Permitting", Map.of("address", "MAIN_ADDRESS", "zip", "ZIP", "date", "ISSUANCE_DATE", "desc", "DESCRIPTION", "scope", "county"), "", Map.of());
        answers.put("x.example.gov/arcgis/rest/services/E/FeatureServer/0/query", "{\"features\": [{\"attributes\": {\"MAIN_ADDRESS\": \"238 Garfield Avenue, Pomona CA 91767\", \"ZIP\": \"91767\", \"ISSUANCE_DATE\": \"2024-01-01\", \"DESCRIPTION\": \"Roof\"}}, {\"attributes\": {\"MAIN_ADDRESS\": \"1505 N Garfield Avenue, Pasadena CA 91104\", \"ZIP\": \"91104\", \"ISSUANCE_DATE\": \"2024-02-02\", \"DESCRIPTION\": \"Remodel\"}}]}");
        assertEquals(List.of("1505 N Garfield Avenue, Pasadena CA 91104: Remodel, 2024-02-02"), HomeRecords.permits(byZip, "N GARFIELD AVE", 100, "Pasadena", "91104"), "the other towns' rows are left out by their zips");
        answers.put("x.example.gov/arcgis/rest/services/E/FeatureServer/0/query", "{\"features\": [{\"attributes\": {\"MAIN_ADDRESS\": \"238 Garfield Avenue, Pomona CA 91767\", \"ZIP\": \"91767\", \"ISSUANCE_DATE\": \"2024-01-01\", \"DESCRIPTION\": \"Roof\"}}, {\"attributes\": {\"MAIN_ADDRESS\": \"1505 N Garfield Avenue, Pasadena CA 91104\", \"ZIP\": \"91104\", \"ISSUANCE_DATE\": \"2024-02-02\", \"DESCRIPTION\": \"Remodel\"}}, {\"attributes\": {\"MAIN_ADDRESS\": \"447 Garfield Avenue, South Pasadena CA 91030\", \"ZIP\": \"91030\", \"ISSUANCE_DATE\": \"2024-03-03\", \"DESCRIPTION\": \"Addition\"}}]}");
        assertEquals(List.of("1505 N Garfield Avenue, Pasadena CA 91104: Remodel, 2024-02-02"), HomeRecords.permits(byZip, "N GARFIELD AVE", 100, "Pasadena", "911"), "South Pasadena is another town, whatever its name holds");
        assertEquals(List.of(), HomeRecords.permits(byZip, "N GARFIELD AVE", 100, "Pasadena", "91101"), "the rows' zips are checked against the home's");
        assertFalse(HomeRecords.tellsTheCity(byZip, "Pasadena"), "with no zip in hand, a zip column alone does not tell the city");
        assertTrue(HomeRecords.inTown("1505 N Garfield Avenue, Pasadena CA 91104", "Pasadena")); assertFalse(HomeRecords.inTown("447 Garfield Avenue, South Pasadena CA 91030", "Pasadena")); assertFalse(HomeRecords.inTown("2001 IRVING BLVD, #149, Dallas, TX", "Irving"));
        assertTrue(HomeRecords.inTown("100 MILITARY PLAZA, City of San Antonio, TX 78205", "San Antonio")); assertTrue(HomeRecords.inTown("1640 IRVING BLVD, Dallas, TX", "Dallas")); assertTrue(HomeRecords.inTown("22 N LASALLE ST #1200", "Chicago"));
        HomeRecords.Dataset permitsWithValue = new HomeRecords.Dataset("Socrata", "data.example.gov", "p", "Building Permits", Map.of("address", "a", "value", "valuation", "date", "issued"), "", Map.of(), "2026-01-01");
        assertFalse(permitsWithValue.roll(), "a permits set with a valuation column is no roll"); assertEquals("Building Permits (Socrata, data.example.gov)", permitsWithValue.reader());
        assertFalse(HomeRecords.nameFits("EXPIRED BLDG PERMITS", "permits")); assertTrue(HomeRecords.nameFits("BuildingPermits", "permits"));
    }

    @Test void theColumnsAreKnownByTheirNamesAndSettledByTheirFirstRows() {
        Map<String, String> f = HomeRecords.columns(List.of("OBJECTID", "OWNER_NAME", "OWNER_ADDRESS_LINE1", "SITUS_ADDRESS_LINE1", "SITUS_CITY", "TOTAL_VALUE", "SALE_DATE", "YR_BUILT"), "sales");
        assertEquals("SITUS_ADDRESS_LINE1", f.get("address"), "the owner's address is someone else's");
        assertEquals("SITUS_CITY", f.get("city")); assertEquals("TOTAL_VALUE", f.get("value")); assertEquals("SALE_DATE", f.get("date")); assertEquals("YR_BUILT", f.get("year"));
        Map<String, String> p = HomeRecords.columns(List.of("street_number", "street_name", "issue_date", "permit_type", "work_description", "contact_1_name"), "permits");
        assertEquals("street_number", p.get("number")); assertEquals("street_name", p.get("street")); assertEquals("issue_date", p.get("date")); assertEquals("work_description", p.get("desc"), "the fuller description before the type");
        assertNull(HomeRecords.columns(List.of("neighborhood", "median_sale_price"), "sales"), "no address and no key");
        assertNull(HomeRecords.columns(List.of("address", "notes"), "permits"), "no date and no description");
        assertTrue(HomeRecords.nameFits("Assessor - Parcel Sales", "sales")); assertFalse(HomeRecords.nameFits("Treasurer - Annual Tax Sale", "sales")); assertFalse(HomeRecords.nameFits("City-Owned Land Inventory", "sales"));
        assertFalse(HomeRecords.nameFits("Assessor - Permits", "sales")); assertFalse(HomeRecords.nameFits("Issued Tree Permits", "permits")); assertTrue(HomeRecords.nameFits("Issued Building Permits", "permits"));
        assertFalse(HomeRecords.nameFits("Assessor - Localscape Portal", "sales")); assertFalse(HomeRecords.nameFits("eReal Property Search", "sales"));
        assertNull(HomeRecords.columns(List.of("pin", "sale_price", "sale_date", "township_code"), "sales").get("city"), "a township code is no city");
        Map<String, String> c = HomeRecords.columns(List.of("prop_address_state", "prop_address_zipcode_1", "prop_address_city_name", "prop_address_full", "pin", "year"), "addresses");
        assertEquals("prop_address_full", c.get("address"), "the state, the zip and the city of an address are parts, not the address"); assertEquals("prop_address_city_name", c.get("city")); assertEquals("pin", c.get("key"));
        Map<String, String> d = HomeRecords.columns(List.of("permit_address", "permit_class", "description", "issued_date"), "permits");
        assertEquals("description", d.get("desc"), "the fuller description before the class");
        // Seattle's permits name the address column originaladdress1; San Francisco's roll names it property_location and gives the value in parts
        assertEquals("originaladdress1", HomeRecords.columns(List.of("permitnum", "originaladdress1", "originalzip", "issueddate", "description", "latitude"), "permits").get("address"));
        Map<String, String> sf = HomeRecords.columns(List.of("property_location", "assessed_land_value", "assessed_improvement_value", "assessed_personal_property_value", "current_sales_date", "year_property_built", "property_area", "lot_area", "parcel_number", "the_geom"), "sales");
        assertEquals("property_location", sf.get("address")); assertEquals("assessed_land_value+assessed_improvement_value", sf.get("value")); assertEquals("current_sales_date", sf.get("date"));
        assertEquals("year_property_built", sf.get("year")); assertEquals("property_area", sf.get("sqft"), "the home's area, not the lot's"); assertEquals("parcel_number", sf.get("key"));
        assertEquals(4_000, HomeRecords.valueOf(Map.of("assessed_land_value", "1,000", "assessed_improvement_value", "3000.0"), "assessed_land_value+assessed_improvement_value"));
        // the first rows settle which address-like column holds addresses: a point is not one, a neighbourhood is not one
        List<Map<String, String>> rows = List.of(Map.of("location", "{\"type\": \"Point\"}", "site_address", "600 4TH AVE", "area", "DOWNTOWN"), Map.of("location", "{\"type\": \"Point\"}", "site_address", "12 PINE ST", "area", "NORTH"));
        assertEquals("site_address", HomeRecords.columns(List.of("location", "site_address", "area", "sale_price"), "sales", rows).get("address"));
        assertNull(HomeRecords.columns(List.of("location", "sale_price"), "sales", rows), "a point column is no address");
        // the appraiser's roll: a total when given, else the land and the improvement summed
        Map<String, String> la = HomeRecords.columns(List.of("AIN", "Roll_Year", "Roll_LandValue", "Roll_ImpValue", "Roll_ImpLandTotal", "LastSaleDate", "SitusFullAddress"), "sales");
        assertEquals("Roll_ImpLandTotal", la.get("value")); assertEquals("LastSaleDate", la.get("date")); assertEquals("SitusFullAddress", la.get("address")); assertEquals("AIN", la.get("key"));
        Map<String, String> kc = HomeRecords.columns(List.of("PIN", "ADDR_FULL", "CTYNAME", "LOTSQFT", "APPRLNDVAL", "APPR_IMPR", "PROPTYPE"), "sales");
        assertEquals("APPRLNDVAL+APPR_IMPR", kc.get("value")); assertEquals("ADDR_FULL", kc.get("address")); assertNull(kc.get("sqft"), "the lot's size is not the home's");
        Map<String, String> indy = HomeRecords.columns(List.of("STNUMBER", "PRE_DIR", "STREET_NAME", "SUFFIX", "FULL_STNAME", "CITY", "ESTSQFT", "ASSESSORYEAR_LANDTOTAL", "ASSESSORYEAR_IMPTOTAL", "ASSESSORYEAR_TOTALAV"), "sales");
        assertEquals("ASSESSORYEAR_TOTALAV", indy.get("value"), "the assessor's year in the name is not the year built"); assertEquals("FULL_STNAME", indy.get("street"), "the full street name before the bare one"); assertEquals("ESTSQFT", indy.get("sqft"));
        assertFalse(HomeRecords.nameFits("Festival Permits in Colorado", "permits")); assertFalse(HomeRecords.nameFits("Dwelling Unit Completion Counts by Building Permit", "permits")); assertFalse(HomeRecords.nameFits("Form 700 Schedule B - Interests in Real Property", "sales"));
        assertTrue(HomeRecords.nameFits("Assessor Historical Secured Property Tax Rolls", "sales"), "a roll called historical is the roll"); assertTrue(HomeRecords.nameFits("Parcel Sales Last 3 Years", "sales")); assertFalse(HomeRecords.nameFits("2026A Tax Defaulted Parcels", "sales"));
        assertTrue(HomeRecords.statePortal("data.colorado.gov", "CO") && HomeRecords.statePortal("data.ct.gov", "CT") && !HomeRecords.statePortal("data.denvergov.org", "CO"));
        assertEquals("2018-06-29", HomeRecords.day("20180629"), "a date written as eight digits");
        assertEquals("mydec_deed_type", HomeRecords.columns(List.of("pin", "sale_price", "sale_date", "sale_filter_deed_type", "mydec_deed_type"), "sales").get("desc"), "a filter is a yes or no, not the kind of deed");
        assertTrue(HomeRecords.otherState("cumberland county, nc", "ME") && HomeRecords.otherState("city of portland, oregon", "ME") && !HomeRecords.otherState("the city of portland, maine", "ME") && !HomeRecords.otherState("king county", "WA"));
        assertFalse(HomeRecords.nameFits("eGIS Cadastral.eGIS.ASSR PARCELS VACANT", "sales")); assertFalse(HomeRecords.nameFits("Parcels 2025 Fires Debris Removal (Public View)", "sales"));
        List<Map<String, String>> blank = List.of(Map.of("SITUSADDR", "", "SALE_PRICE", "1"), Map.of("SITUSADDR", "null", "SALE_PRICE", "2"));
        assertEquals("SITUSADDR", HomeRecords.columns(List.of("SITUSADDR", "SALE_PRICE"), "sales", blank).get("address"), "a sample of parcels with no situs says nothing either way");
    }
}
