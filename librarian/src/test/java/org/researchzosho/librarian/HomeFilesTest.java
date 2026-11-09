package org.researchzosho.librarian;

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
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** A file of records read by its shape, a CSV, a TSV, a zip, a gzip, a JSON array, a GeoJSON, fetched once into the library and read for a street. No web, no model. */
class HomeFilesTest {

    static final class StandIn implements Researcher.Drive {
        @Override public ObjectNode chat(ArrayNode m, ArrayNode t, int x, String y) { throw new UnsupportedOperationException(); }
        @Override public int contextWindow() { return 32_000; }
        @Override public String classify(ArrayNode m, int max) { return "[]"; }
    }

    final Map<String, byte[]> files = new HashMap<>();
    final List<String> fetched = new ArrayList<>();

    @BeforeEach void standIns() {
        HomeFiles.FETCH = (url, to) -> { fetched.add(url); byte[] b = files.get(url); if (b == null) return false; try { Files.write(to, b); return true; } catch (Exception e) { return false; } };
        HomeSensors.FETCH = u -> null; HomeSensors.BYTES = u -> null; HomeRecords.SEARCH = q -> List.of(); HomeSensors.MAP_POINT = q -> null;
        Career.DRIVE = StandIn::new; HomeRecordsResearch.PROBE = u -> null;
        HomeRecordsResearch.RESEARCH = (st, ask, title, stop) -> { throw new IllegalStateException("no research was expected"); };
    }

    @AfterEach void back() {
        HomeFiles.FETCH = HomeFiles::download; HomeSensors.FETCH = CareerSources::get; HomeSensors.BYTES = HomeSensors::fetchBytes; HomeRecords.SEARCH = q -> HomeSources.SEARCH.apply(q); HomeSensors.MAP_POINT = HomeSensors::pointFromMap;
        Career.DRIVE = Explain::drive; HomeRecordsResearch.PROBE = HomeRecordsResearch::probe; HomeRecordsResearch.RESEARCH = HomeRecordsResearch::research;
    }

    static final String CSV = "﻿SITUS_ADDRESS,CITY,SALE_PRICE,SALE_DATE,DEED_TYPE,YEAR_BUILT,PIN\n"
            + "\"120 ELM ST\",SPRINGFIELD,\"255,000\",2025-06-30,\"Warranty, deed\",1961,1\n"
            + "126 ELM ST,SPRINGFIELD,240000,2024-02-01,wd,1961,2\n"
            + "\"9 ELM ST\nUNIT 2\",SPRINGFIELD,0,2024-02-01,quit claim,1961,3\n"
            + "120 ELMWOOD AVE,SPRINGFIELD,900000,2026-01-15,wd,2001,4\n"
            + "120 ELM ST,ROCHESTER,300000,2026-03-03,wd,1961,5\n";

    static byte[] zip(String name, String text) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(out)) { z.putNextEntry(new ZipEntry("readme.txt")); z.write("about".getBytes(StandardCharsets.UTF_8)); z.closeEntry(); z.putNextEntry(new ZipEntry(name)); z.write(text.getBytes(StandardCharsets.UTF_8)); z.closeEntry(); }
        return out.toByteArray();
    }

    static byte[] gz(String text) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream g = new GZIPOutputStream(out)) { g.write(text.getBytes(StandardCharsets.UTF_8)); }
        return out.toByteArray();
    }

    @Test void aCsvIsReadForAStreetWithQuotedFieldsAndTheNewestDate(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        files.put("https://files.example.gov/sales.csv", CSV.getBytes(StandardCharsets.UTF_8));
        List<String> looked = new ArrayList<>();
        HomeRecords.Dataset d = HomeFiles.dataset(store, "https://files.example.gov/sales.csv", "csv", "", "sales", looked);
        assertNotNull(d, looked.toString());
        assertEquals("File", d.platform()); assertEquals("sales.csv (CSV file)", d.name()); assertEquals("SITUS_ADDRESS", d.field("address")); assertEquals("SALE_PRICE", d.field("price")); assertEquals("CITY", d.field("city"));
        assertEquals("2026-03-03", d.latest(), "the newest date over the whole file");
        List<String> prices = HomeRecords.prices(d, "ELM ST", 122, "Springfield");
        assertEquals(List.of("120 ELM ST: $255,000 on 2025-06-30 (warranty, deed), built 1961", "126 ELM ST: $240,000 on 2024-02-01 (wd), built 1961"), prices, "the quoted figure and the quoted kind read; ELMWOOD and the other city's row are not the street; the $0 transfer is left out");
        assertEquals(1, fetched.size(), "fetched once");
        HomeRecords.prices(d, "ELM ST", 122, "Springfield");
        assertEquals(1, fetched.size(), "the month's copy is read again, not fetched");
        Files.delete(Path.of(d.joinId()));
        assertEquals(2, HomeRecords.prices(d, "ELM ST", 122, "Springfield").size()); assertEquals(2, fetched.size(), "the copy gone, fetched again beside where it was");
    }

    @Test void aTsvAZipAGzipAJsonArrayAndAGeoJsonAreReadTheSameWay(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        String tsv = "address\tissue_date\twork_description\n600 4TH AVE\t2026-05-01\tInterior remodel of the third floor\n610 4TH AVE S\t2026-04-01\tRoof repair\n";
        files.put("https://x.example/permits.tsv", tsv.getBytes(StandardCharsets.UTF_8));
        files.put("https://x.example/sales.zip", zip("data/sales 2026.csv", CSV));
        files.put("https://x.example/sales.csv.gz", gz(CSV));
        files.put("https://x.example/rows.json", "{\"meta\": {\"n\": 2}, \"data\": [{\"full_address\": \"120 ELM ST\", \"total_value\": 255000, \"yr_built\": 1961}, {\"full_address\": \"5 OAK ST\", \"total_value\": 100000, \"yr_built\": 1970}]}".getBytes(StandardCharsets.UTF_8));
        files.put("https://x.example/parcels.geojson", ("{\"type\": \"FeatureCollection\", \"features\": [{\"type\": \"Feature\", \"geometry\": {\"type\": \"Polygon\", \"coordinates\": [[[-89.65, 39.80], [-89.64, 39.80]]]}, \"properties\": {\"SITEADDR\": \"124 ELM ST\", \"ASSESSED_TOTAL\": \"230000\", \"SALEDATE\": \"2025-12-01\", \"SALEPRICE\": \"250000\"}}]}").getBytes(StandardCharsets.UTF_8));
        List<String> looked = new ArrayList<>();
        HomeRecords.Dataset permits = HomeFiles.dataset(store, "https://x.example/permits.tsv", "tsv", "", "permits", looked);
        assertNotNull(permits, looked.toString());
        assertEquals(List.of("600 4TH AVE: Interior remodel of the third floor, 2026-05-01"), HomeRecords.permits(permits, "4TH AVE", 600, "Seattle"), "4TH AVE S is another street");
        HomeRecords.Dataset z = HomeFiles.dataset(store, "https://x.example/sales.zip", "zip", "", "sales", looked);
        assertNotNull(z, looked.toString()); assertEquals(2, HomeRecords.prices(z, "ELM ST", 122, "Springfield").size(), "the CSV inside the zip, the readme passed over");
        HomeRecords.Dataset g = HomeFiles.dataset(store, "https://x.example/sales.csv.gz", "csv", "", "sales", looked);
        assertNotNull(g, looked.toString()); assertEquals(2, HomeRecords.prices(g, "ELM ST", 122, "Springfield").size(), "the gzip read as its CSV");
        HomeRecords.Dataset j = HomeFiles.dataset(store, "https://x.example/rows.json", "json", "", "sales", looked);
        assertNotNull(j, looked.toString()); assertEquals(List.of("120 ELM ST: assessed at $255,000, built 1961"), HomeRecords.prices(j, "ELM ST", 122, ""), "the array under the object's data key");
        HomeRecords.Dataset geo = HomeFiles.dataset(store, "https://x.example/parcels.geojson", "geojson", "", "sales", looked);
        assertNotNull(geo, looked.toString()); assertEquals(List.of("124 ELM ST: $250,000 on 2025-12-01"), HomeRecords.prices(geo, "ELM ST", 122, ""), "a feature's properties");
        assertEquals("2025-12-01", geo.latest());
    }

    /** A dBase table as a shapefile carries one: the header's field descriptors, then fixed-width records, a deleted one marked. */
    static byte[] dbf(String[][] fields, String[][] records) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int recLen = 1; for (String[] f : fields) recLen += Integer.parseInt(f[2]);
        int headLen = 32 + 32 * fields.length + 1;
        byte[] head = new byte[32]; head[0] = 3; head[4] = (byte) records.length; head[8] = (byte) (headLen & 0xff); head[9] = (byte) (headLen >> 8); head[10] = (byte) (recLen & 0xff); head[11] = (byte) (recLen >> 8);
        out.write(head);
        for (String[] f : fields) { byte[] d = new byte[32]; byte[] n = f[0].getBytes(StandardCharsets.ISO_8859_1); System.arraycopy(n, 0, d, 0, Math.min(11, n.length)); d[11] = (byte) f[1].charAt(0); d[16] = (byte) Integer.parseInt(f[2]); out.write(d); }
        out.write(0x0D);
        for (String[] r : records) {
            out.write(r[0].equals("deleted") ? '*' : ' ');
            for (int i = 0; i < fields.length; i++) { int w = Integer.parseInt(fields[i][2]); String v = i + 1 < r.length ? r[i + 1] : ""; byte[] b = String.format("%-" + w + "s", v).substring(0, w).getBytes(StandardCharsets.ISO_8859_1); out.write(b); }
        }
        out.write(0x1A);
        return out.toByteArray();
    }

    @Test void aShapefilesTableIsReadFromItsZipAsARollAndAGeodatabaseIsSaidAsAMapFile(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        String[][] fields = {{"SITUS_ADDR", "C", "30"}, {"TOTAL_VAL", "N", "10"}, {"SALE_DATE", "D", "8"}, {"YR_BUILT", "N", "4"}};
        byte[] table = dbf(fields, new String[][]{{"live", "120 ELM ST", "255000", "20250630", "1961"}, {"deleted", "5 OAK ST", "1", "20200101", "1900"}, {"live", "126 ELM ST", "240000", "20240201", "1961"}, {"live", "120 ELMWOOD AVE", "900000", "", "2001"}});
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(out)) {
            for (String[] e : new String[][]{{"parcels.shp", "shape bytes"}, {"parcels.shx", "index"}, {"parcels.prj", "WGS84"}}) { z.putNextEntry(new ZipEntry(e[0])); z.write(e[1].getBytes(StandardCharsets.UTF_8)); z.closeEntry(); }
            z.putNextEntry(new ZipEntry("parcels.dbf")); z.write(table); z.closeEntry();
        }
        files.put("https://gis.county.example/parcels-shp.zip", out.toByteArray());
        List<String> looked = new ArrayList<>();
        HomeRecords.Dataset d = HomeFiles.dataset(store, "https://gis.county.example/parcels-shp.zip", "zip", "", "sales", looked);
        assertNotNull(d, looked.toString());
        assertEquals("SITUS_ADDR", d.field("address")); assertEquals("TOTAL_VAL", d.field("value")); assertEquals("2025-06-30", d.latest(), "the dBase date's eight digits");
        assertEquals(List.of("120 ELM ST: assessed at $255,000, built 1961", "126 ELM ST: assessed at $240,000, built 1961"), HomeRecords.prices(d, "ELM ST", 122, ""), "the deleted record and the other street left out");
        // a file geodatabase is a map file still, said as such
        ByteArrayOutputStream gdb = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(gdb)) { z.putNextEntry(new ZipEntry("parcels.gdb/a00000001.gdbtable")); z.write("x".getBytes(StandardCharsets.UTF_8)); z.closeEntry(); }
        files.put("https://gis.county.example/parcels.gdb.zip", gdb.toByteArray());
        HomeFiles.MAP_FILE.remove();
        assertNull(HomeFiles.dataset(store, "https://gis.county.example/parcels.gdb.zip", "zip", "", "sales", looked));
        assertEquals("https://gis.county.example/parcels.gdb.zip", HomeFiles.MAP_FILE.get());
        assertTrue(looked.get(looked.size() - 1).contains("is a map file") && looked.get(looked.size() - 1).contains("which the library does not read"), looked.toString());
    }

    @Test void aFileTheResearchNamesIsReadForTheCountyAndKeptWithTheCitysLook(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        files.put("https://gis.sangamon.example/download/parcels.csv", CSV.getBytes(StandardCharsets.UTF_8));
        HomeRecordsResearch.RESEARCH = (st, ask, title, stop) -> "The county's assessor publishes the parcel roll with sales as a download at https://gis.sangamon.example/download/parcels.csv, refreshed weekly; permits are searched at https://permits.sangamon.example/lookup.";
        HomeRecordsResearch.PROBE = u -> u.contains("lookup") ? new String[]{"text/html", "<html>"} : null;
        HomeRecords.Found f = HomeRecords.find(store, "Springfield", "IL", "Sangamon County", 39.80, -89.65, "ELM ST", 122, true);
        assertNotNull(f.sales(), f.looked().toString());
        assertEquals("File", f.sales().platform()); assertEquals("download/parcels.csv (CSV file)", f.sales().name());
        assertTrue(f.looked().stream().anyMatch(x -> x.startsWith("kept download/parcels.csv (CSV file) (File, gis.sangamon.example), from the research: it answered for ELM ST")), f.looked().toString());
        assertEquals("https://permits.sangamon.example/lookup", f.lookup());
        assertEquals("Records: download/parcels.csv (CSV file), File, newest row 2026. No permits source is known for Springfield, Sangamon County.", HomeRecordsLine.line("Springfield", "IL", "Sangamon County", "", f.sales(), null, f.looked(), f.lookup()));
        // the city's look, read back a day later, reads the same file without the research
        HomeRecordsResearch.RESEARCH = (st, ask, title, stop) -> { throw new IllegalStateException("researched again"); };
        HomeRecords.Found again = HomeRecords.find(store, "Springfield", "IL", "Sangamon County", 39.80, -89.65, "ELM ST", 122, true);
        assertEquals(2, HomeRecords.prices(again.sales(), "ELM ST", 122, "Springfield").size());
        assertTrue(Files.isRegularFile(Path.of(again.sales().joinId())) && again.sales().joinId().startsWith(HomeFiles.dir(store).toString()), "kept under the library");
    }
}
