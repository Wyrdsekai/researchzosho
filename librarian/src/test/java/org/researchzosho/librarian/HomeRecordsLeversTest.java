package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** The shapes a research answer names resolved to datasets: service roots, Hub downloads, Socrata views, catalogues, placeholders, download pages; a map file said; a split situs joined. No web, no model. */
class HomeRecordsLeversTest {

    static final ObjectMapper M = new ObjectMapper();

    static final class StandIn implements Researcher.Drive {
        @Override public ObjectNode chat(ArrayNode m, ArrayNode t, int x, String y) { throw new UnsupportedOperationException(); }
        @Override public int contextWindow() { return 32_000; }
        @Override public String classify(ArrayNode m, int max) { return "[]"; }
    }

    final Map<String, String> answers = new HashMap<>();
    final Map<String, byte[]> files = new HashMap<>();

    @BeforeEach void standIns() {
        HomeSensors.FETCH = u -> { for (Map.Entry<String, String> e : answers.entrySet()) if (u.contains(e.getKey())) return e.getValue(); return null; };
        HomeFiles.FETCH = (url, to) -> { byte[] b = files.get(url); if (b == null) return false; try { Files.write(to, b); return true; } catch (Exception e) { return false; } };
        HomeSensors.BYTES = u -> null; HomeRecords.SEARCH = q -> List.of(); HomeSensors.MAP_POINT = q -> null;
        Career.DRIVE = StandIn::new; HomeRecordsResearch.PROBE = u -> null;
        HomeRecordsResearch.RESEARCH = (st, ask, title, stop) -> { throw new IllegalStateException("no research was expected"); };
    }

    @AfterEach void back() {
        HomeSensors.FETCH = CareerSources::get; HomeFiles.FETCH = HomeFiles::download; HomeSensors.BYTES = HomeSensors::fetchBytes; HomeRecords.SEARCH = q -> HomeSources.SEARCH.apply(q); HomeSensors.MAP_POINT = HomeSensors::pointFromMap;
        Career.DRIVE = Explain::drive; HomeRecordsResearch.PROBE = HomeRecordsResearch::probe; HomeRecordsResearch.RESEARCH = HomeRecordsResearch::research;
    }

    @Test void theSixShapesResolveToDatasets() {
        // a placeholder: the address up to it, a root when that is what is left, nothing otherwise
        assertEquals("unknown", HomeRecordsResearch.classify("https://datacatalog.cookcountyil.gov/api/v3/views/{id}/export.csv?accessType=DOWNLOAD", false).kind());
        HomeRecordsResearch.Source root = HomeRecordsResearch.classify("https://gis.example.gov/arcgis/rest/services/Parcels/MapServer/{layer}", false);
        assertEquals("arcgis-root|https://gis.example.gov/arcgis/rest/services/Parcels/MapServer", root.kind() + "|" + root.id());
        // a service root: its layers of parcels or sales, not its roads
        answers.put("services/Parcels/MapServer?f=json", "{\"layers\": [{\"id\": 0, \"name\": \"Parcels\"}, {\"id\": 1, \"name\": \"Roads\"}, {\"id\": 2, \"name\": \"Zoning\"}, {\"id\": 3, \"name\": \"Sales History\"}]}");
        List<HomeRecordsResearch.Source> layers = HomeRecordsResearch.layersOf(root);
        assertEquals(List.of("https://gis.example.gov/arcgis/rest/services/Parcels/MapServer/0", "https://gis.example.gov/arcgis/rest/services/Parcels/MapServer/3"), layers.stream().map(HomeRecordsResearch.Source::id).toList());
        // a Hub download: the item's id and layer, through the Hub's API, to the layer
        answers.put("hub.arcgis.com/api/v3/datasets/e22afb85e1414f4996c2b5264df90a07_2", "{\"data\": {\"attributes\": {\"name\": \"Real Property\", \"url\": \"https://arlgis.example/arcgis/rest/services/Real_Property/FeatureServer/2\"}}}");
        HomeRecordsResearch.Source hub = HomeRecordsResearch.classify("https://gisdata-arlgis.opendata.arcgis.com/api/download/v1/items/e22afb85e1414f4996c2b5264df90a07/csv?layers=2");
        assertEquals("arcgis|https://arlgis.example/arcgis/rest/services/Real_Property/FeatureServer/2", hub.kind() + "|" + hub.id());
        // a Socrata view's address: the dataset's id
        HomeRecordsResearch.Source view = HomeRecordsResearch.classify("https://datacatalog.cookcountyil.gov/api/views/nj4t-kc8j.json");
        assertEquals("socrata|nj4t-kc8j", view.kind() + "|" + view.id());
        // a catalogue in the DCAT shape: its datasets of parcels, by title, each distribution by its shape
        answers.put("api/feed/dcat-us/1.1.json", "{\"dataset\": [{\"title\": \"Parcels with Values\", \"distribution\": [{\"format\": \"Web Page\", \"accessURL\": \"https://geohub.example.gov/datasets/x::parcels\"}, {\"format\": \"ArcGIS GeoServices REST API\", \"accessURL\": \"https://x.example/rest/services/Parcels/FeatureServer/0\"}]}, {\"title\": \"Tree Inventory\", \"distribution\": [{\"format\": \"CSV\", \"downloadURL\": \"https://x.example/trees.csv\"}]}]}");
        HomeRecordsResearch.Source feed = HomeRecordsResearch.classify("https://geohub.example.gov/api/feed/dcat-us/1.1.json");
        assertEquals("dcat", feed.kind());
        List<String> looked = new ArrayList<>();
        List<HomeRecordsResearch.Source> fromFeed = HomeRecordsResearch.dcat(feed, looked);
        assertEquals(List.of("arcgis|https://x.example/rest/services/Parcels/FeatureServer/0"), fromFeed.stream().map(s -> s.kind() + "|" + s.id()).toList(), looked.toString());
        assertTrue(looked.get(0).contains("2 datasets, 1 of parcels, sales or permits, 1 addresses to try"), looked.get(0));
        // a download page: its files and endpoints, and the newest year folder of a listing read too
        answers.put("apps.example.gov/Parcel_CSV/2026/", "<html><a href=\"Parcels.csv\">Parcels.csv</a> <a href=\"Sales.zip\">Sales.zip</a> <a href=\"notes.html\">notes</a></html>");
        answers.put("apps.example.gov/Parcel_CSV/", "<html><a HREF=\"/\">[To Parent]</a><a HREF=\"/Parcel_CSV/2024/\">2024</a><a HREF=\"/Parcel_CSV/2026/\">2026</a><a href=\"https://apps.example.gov/arcgis/rest/services/Parcels/MapServer/0\">the layer</a></html>");
        HomeRecordsResearch.Source page = new HomeRecordsResearch.Source("https://apps.example.gov/Parcel_CSV/", "page", "apps.example.gov", "");
        looked.clear();
        List<HomeRecordsResearch.Source> links = HomeRecordsResearch.linksOf(page, looked);
        assertEquals(List.of("arcgis|https://apps.example.gov/arcgis/rest/services/Parcels/MapServer/0", "csv|https://apps.example.gov/Parcel_CSV/2026/Parcels.csv", "zip|https://apps.example.gov/Parcel_CSV/2026/Sales.zip"),
                links.stream().map(s -> s.kind() + "|" + (s.id().isEmpty() ? s.url() : s.id())).toList(), looked.toString());
        assertTrue(looked.get(0).contains("the newest folder https://apps.example.gov/Parcel_CSV/2026/ read too"), looked.get(0));
        // the expansion keeps the originals, adds what resolved, once each
        List<HomeRecordsResearch.Source> all = HomeRecordsResearch.expand(List.of(root, page, feed, page), looked);
        assertEquals(List.of("arcgis-root", "page", "dcat", "arcgis", "arcgis", "arcgis", "csv", "zip", "arcgis"), all.stream().map(HomeRecordsResearch.Source::kind).toList(), all.toString());
    }

    static byte[] zip(Map<String, String> entries) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(out)) { for (Map.Entry<String, String> e : entries.entrySet()) { z.putNextEntry(new ZipEntry(e.getKey())); z.write(e.getValue().getBytes(StandardCharsets.UTF_8)); z.closeEntry(); } }
        return out.toByteArray();
    }

    @Test void aMapFileIsSaidOnTheLineNotSwallowed(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        files.put("https://gis.bernco.example/Downloads/BERNCO.zip", zip(Map.of("parcels.shp", "x", "parcels.shx", "y", "parcels.prj", "z")));   // a shapefile without its table: nothing to read
        HomeRecordsResearch.RESEARCH = (st, ask, title, stop) -> "The county's parcels are downloaded as a zip at https://gis.bernco.example/Downloads/BERNCO.zip.";
        HomeRecords.Found f = HomeRecords.find(store, "Rio Rancho", "NM", "Sandoval County", 35.23, -106.66, "CIVIC CENTER CIR NE", 3200, true);
        assertNull(f.sales());
        assertEquals("https://gis.bernco.example/Downloads/BERNCO.zip", f.mapFile());
        assertTrue(f.looked().stream().anyMatch(x -> x.contains("is a map file") && x.contains("which the library does not read")), f.looked().toString());
        assertEquals("No records source a program can read is known for Rio Rancho, Sandoval County; the county publishes a map file the library does not read, at https://gis.bernco.example/Downloads/BERNCO.zip.",
                HomeRecordsLine.line("Rio Rancho", "NM", "Sandoval County", "", null, null, f.looked(), f.lookup(), f.mapFile()));
        // read back from the city's look a day later, the map file is still said
        HomeRecordsResearch.RESEARCH = (st, ask, title, stop) -> { throw new IllegalStateException("researched again"); };
        assertEquals(f.mapFile(), HomeRecords.find(store, "Rio Rancho", "NM", "Sandoval County", 35.23, -106.66, "CIVIC CENTER CIR NE", 3200, true).mapFile());
    }

    @Test void aRollThatSplitsTheSitusIsJoinedIntoAnAddress() throws Exception {
        List<String> cols = List.of("OBJECTID", "SiteNumber", "acct_num", "owner_name_1", "mail_addr_1", "mail_city", "site_str_pfx", "site_str_num", "site_str_num_sfx", "site_str_name", "site_str_sfx", "site_str_sfx_dir", "site_city", "site_zip", "land_value", "bld_value", "total_appraised_val", "tax_year");
        Map<String, String> fs = HomeRecords.columns(cols, "sales");
        assertNotNull(fs);
        assertEquals("site_str_num", fs.get("number")); assertEquals("site_str_name", fs.get("street")); assertEquals("site_str_pfx", fs.get("prefix")); assertEquals("site_str_sfx+site_str_sfx_dir", fs.get("suffix"));
        assertEquals("site_city", fs.get("city")); assertFalse(fs.containsKey("address"), "the owner's mailing address is not the home's");
        String addr = HomeRecords.addressIn(M.readTree("{\"site_str_pfx\": \"  \", \"site_str_num\": 901, \"site_str_name\": \"BAGBY\", \"site_str_sfx\": \"ST\", \"site_str_sfx_dir\": \"  \", \"site_city\": \"HOUSTON\"}"), fs);
        assertEquals("901 BAGBY ST", addr);
        assertTrue(HomeRecords.onStreet(addr, "BAGBY ST"));
        assertEquals("N 1200 MAIN ST W", HomeRecords.addressIn(M.readTree("{\"site_str_pfx\": \"N\", \"site_str_num\": 1200, \"site_str_name\": \"MAIN\", \"site_str_sfx\": \"ST\", \"site_str_sfx_dir\": \"W\"}"), fs));
    }
}
