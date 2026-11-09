package org.researchzosho.librarian;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * A file of records read by its shape: a CSV or a TSV streamed line by line, a zipped or gzipped one the same way, a JSON array of
 * objects, a GeoJSON file's features. The file is fetched once into the library (catalog/home-cache/files) and kept a month, so a
 * county's file downloads once however many homes are read; a file over two hundred megabytes or two million rows is read that far
 * and no further. Which column holds what is settled as for any dataset: by the column names and the first rows. A reading walks the
 * whole file for one street, which is seconds for a county's roll.
 */
final class HomeFiles {
    private HomeFiles() { }
    private static final ObjectMapper M = new ObjectMapper();
    private static final JsonFactory F = new JsonFactory();

    /** The address of a map file (a shapefile, a file geodatabase) met in the reading: for the card to say, since the library does not read one. */
    static final ThreadLocal<String> MAP_FILE = new ThreadLocal<>();

    static final long MOST_BYTES = 200L << 20;
    static final int MOST_ROWS = 2_000_000, DAYS = 31;

    /** The file at an address, fetched into the given path (streamed, at most {@link #MOST_BYTES}); true when it is there; a test puts its own here. */
    static volatile BiFunction<String, Path, Boolean> FETCH = HomeFiles::download;

    static Path dir(LibraryStore store) { return store.root().resolve("catalog").resolve("home-cache").resolve("files"); }

    /** Where the file at an address is kept: by the address's hash, with its kind as the ending. */
    static Path file(LibraryStore store, String url, String kind) {
        String h;
        try { h = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(url.getBytes(StandardCharsets.UTF_8))).substring(0, 16); } catch (Exception e) { h = Integer.toHexString(url.hashCode()); }
        return dir(store).resolve(h + "." + kind);
    }

    /** The file at an address, in the library's cache: fetched when it is not there or is older than a month; null when it cannot be fetched. */
    static Path cached(LibraryStore store, String url, String kind) {
        Path p = file(store, url, kind);
        try {
            if (Files.isRegularFile(p) && Files.size(p) > 0 && Files.getLastModifiedTime(p).toInstant().isAfter(Instant.now().minus(Duration.ofDays(DAYS)))) return p;
            Files.createDirectories(p.getParent());
            Path tmp = p.resolveSibling(p.getFileName() + ".part");
            if (!FETCH.apply(url, tmp)) { Files.deleteIfExists(tmp); return null; }
            Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING);
            Files.setLastModifiedTime(p, FileTime.from(Instant.now()));
            return p;
        } catch (IOException e) { return null; }
    }

    static boolean download(String url, Path to) {
        try {
            HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(20)).build();
            HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(10)).header("User-Agent", "researchzosho.org (research library)").GET().build();
            HttpResponse<InputStream> r = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (r.statusCode() != 200) return false;
            long n = 0;
            try (InputStream in = r.body(); var out = Files.newOutputStream(to)) {
                byte[] buf = new byte[1 << 16]; int k;
                while ((k = in.read(buf)) > 0) { out.write(buf, 0, k); n += k; if (n >= MOST_BYTES) break; }
            }
            return n > 0;
        } catch (Exception e) { return false; }
    }

    /** A dataset for the file the research or a portal named, for sales or for permits: its header and first rows settle the columns as for any dataset; null when it is not such a file. The local path rides in the dataset's join id, so a reading finds it without the library. */
    static HomeRecords.Dataset dataset(LibraryStore store, String url, String kind, String name, String what, List<String> looked) {
        Path p = cached(store, url, kind);
        if (p == null) { looked.add("the file " + url + " could not be fetched"); return null; }
        List<String> cols = new ArrayList<>();
        List<Map<String, String>> rows = new ArrayList<>();
        try { read(p, kind, cols, (r, i) -> { if (i < 5) { rows.add(r); return true; } return false; }); }
        catch (IOException | RuntimeException e) {
            if (e.getMessage() != null && e.getMessage().startsWith("a map file")) { looked.add("the file " + url + " is a map file (" + e.getMessage().replaceFirst("^a map file \\(", "").replaceFirst("\\)$", "") + "), which the library does not read"); if (MAP_FILE.get() == null) MAP_FILE.set(url); }
            else looked.add("the file " + url + " could not be read as " + kind + ": " + e.getMessage());
            return null;
        }
        if (cols.isEmpty()) { looked.add("the file " + url + " has no columns the library can read"); return null; }
        Map<String, String> fs = HomeRecords.columns(cols, what, rows.isEmpty() ? null : rows);
        if (fs == null) return null;
        if (what.equals("permits") && !HomeRecords.buildingWork(rows, fs)) { looked.add("passed over the file " + url + ": its first rows show no building work"); return null; }
        String label = (name == null || name.isBlank() ? url.replaceFirst("^https?://[^/]+/", "").replaceFirst("[?#].*$", "") : name) + " (" + kind.toUpperCase(Locale.ROOT) + " file)";
        String lower = label.toLowerCase(Locale.ROOT);   // a file of parcels is not the permits, whatever words its rows hold; a file of permits is not the sales
        if (what.equals("permits") && !lower.contains("permit") && !lower.contains("building")) { looked.add("passed over the file " + url + " for permits: its name does not say so"); return null; }
        if (what.equals("sales") && lower.contains("permit")) return null;
        HomeRecords.Dataset d = new HomeRecords.Dataset("File", HomeRecordsResearch.host(url), url, label, fs, p.toString(), Map.of());
        return d.withLatest(latest(d));
    }

    /** The newest value of the date column, over the whole file; "" when there is none. */
    static String latest(HomeRecords.Dataset d) {
        String date = d.field("date");
        if (date.isEmpty()) return "";
        String[] best = {""};
        try { read(path(d), kind(d), new ArrayList<>(), (r, i) -> { String v = HomeRecords.day(r.getOrDefault(date, "")); if (v.compareTo(best[0]) > 0 && v.matches("\\d{4}-\\d{2}-\\d{2}")) best[0] = v; return true; }); }
        catch (IOException | RuntimeException e) { return ""; }
        return best[0];
    }

    static List<Map<String, String>> firstRows(HomeRecords.Dataset d) {
        List<Map<String, String>> rows = new ArrayList<>();
        try { read(path(d), kind(d), new ArrayList<>(), (r, i) -> { if (i < 5) { rows.add(r); return true; } return false; }); } catch (IOException | RuntimeException e) { return null; }
        return rows;
    }

    /** The rows on a street, the file walked once: the address as the row writes it under "__address"; the home's city when the file has a city column; at most two hundred. */
    static List<Map<String, String>> rows(HomeRecords.Dataset d, String street, String city) {
        List<Map<String, String>> out = new ArrayList<>();
        String core = HomeRecords.core(street), cityCol = d.field("city");
        try {
            read(path(d), kind(d), new ArrayList<>(), (r, i) -> {
                String a = addressOf(r, d.fields());
                if (a.isEmpty() || !a.toUpperCase(Locale.ROOT).contains(core)) return true;
                if (!cityCol.isEmpty() && !city.isEmpty() && !r.getOrDefault(cityCol, "").toUpperCase(Locale.ROOT).contains(city.toUpperCase(Locale.ROOT))) return true;
                r.put("__address", a); out.add(r);
                return out.size() < 200;
            });
        } catch (IOException | RuntimeException ignored) { }
        return out;
    }

    static String addressOf(Map<String, String> r, Map<String, String> fields) {
        String a = fields.getOrDefault("address", "");
        String s = !a.isEmpty() ? r.getOrDefault(a, "") : r.getOrDefault(fields.getOrDefault("number", ""), "") + " " + r.getOrDefault(fields.getOrDefault("street", ""), "");
        return HomeRecords.tidy(s);
    }

    static Path path(HomeRecords.Dataset d) {
        Path p = Path.of(d.joinId());
        if (!Files.isRegularFile(p)) {   // the month passed and the file was dropped: fetched again, beside where it was
            try { Files.createDirectories(p.getParent()); if (!FETCH.apply(d.id(), p)) throw new IllegalStateException("the file could not be fetched again"); } catch (IOException e) { throw new IllegalStateException(e.getMessage()); }
        }
        return p;
    }

    static String kind(HomeRecords.Dataset d) { String n = d.joinId(); int dot = n.lastIndexOf('.'); return dot < 0 ? "csv" : n.substring(dot + 1).toLowerCase(Locale.ROOT); }

    /** Each row to the taker, with its index, until it says stop or the file ends; the columns into {@code cols}. A CSV, a TSV, a zip or a gzip of one, a JSON array, a GeoJSON. */
    static void read(Path p, String kind, List<String> cols, BiFunction<Map<String, String>, Integer, Boolean> take) throws IOException {
        try (BufferedInputStream raw = new BufferedInputStream(Files.newInputStream(p), 1 << 16)) {
            InputStream in = raw;
            String k = kind;
            raw.mark(4); byte[] magic = raw.readNBytes(4); raw.reset();   // what the bytes say the file is, whatever its name says
            boolean gz = magic.length >= 2 && (magic[0] & 0xff) == 0x1f && (magic[1] & 0xff) == 0x8b;
            boolean zipped = magic.length >= 4 && magic[0] == 'P' && magic[1] == 'K' && magic[2] == 3 && magic[3] == 4;
            if (zipped) k = "zip";
            if (k.equals("zip")) {   // the one entry of records: the largest CSV, TSV, GeoJSON or JSON, a .txt only when nothing else
                try (ZipFile z = new ZipFile(p.toFile())) {
                    ZipEntry best = null; int bestRank = 0;
                    for (ZipEntry e : Collections.list(z.entries())) {
                        String n = e.getName().toLowerCase(Locale.ROOT);
                        int rank = n.endsWith(".csv") ? 6 : n.endsWith(".tsv") ? 5 : n.endsWith(".geojson") ? 4 : n.endsWith(".json") ? 3 : n.endsWith(".dbf") ? 2 : n.endsWith(".txt") ? 1 : 0;   // a shapefile's .dbf holds its table
                        if (rank == 0 || e.isDirectory()) continue;
                        if (rank > bestRank || (rank == bestRank && e.getSize() > best.getSize())) { best = e; bestRank = rank; }
                    }
                    if (best == null) {
                        boolean map = false; for (ZipEntry e : Collections.list(z.entries())) { String n = e.getName().toLowerCase(Locale.ROOT); if (n.endsWith(".gdbtable") || n.contains(".gdb/") || n.endsWith(".shp")) map = true; }
                        throw new IOException(map ? "a map file (a file geodatabase, or a shapefile without its table)" : "the zip holds no CSV, TSV, JSON, GeoJSON or dBase table");
                    }
                    String n = best.getName().toLowerCase(Locale.ROOT);
                    String kk = n.endsWith(".geojson") ? "geojson" : n.endsWith(".json") ? "json" : n.endsWith(".tsv") ? "tsv" : n.endsWith(".dbf") ? "dbf" : "csv";
                    try (InputStream zin = new BufferedInputStream(z.getInputStream(best), 1 << 16)) {
                        if (kk.equals("json") || kk.equals("geojson")) readJson(zin, kk, cols, take);
                        else if (kk.equals("dbf")) readDbf(zin, cols, take);
                        else readDelimited(new BufferedReader(new InputStreamReader(zin, StandardCharsets.UTF_8), 1 << 16), kk.equals("tsv") ? '\t' : ',', cols, take);
                    }
                } catch (ZipException e) {
                    throw new IOException(Files.size(p) >= MOST_BYTES ? "larger than the library reads (" + (MOST_BYTES >> 20) + " MB), so its end is missing" : "not a zip the library can open: " + e.getMessage());
                }
                return;
            } else if (gz) { in = new GZIPInputStream(raw); if (k.equals("gz") || k.equals("zip")) k = "csv"; }
            if (k.equals("json") || k.equals("geojson")) readJson(in, k, cols, take);
            else if (k.equals("dbf")) readDbf(in, cols, take);
            else readDelimited(new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8), 1 << 16), k.equals("tsv") ? '\t' : ',', cols, take);
        }
    }

    /**
     * A dBase table, the .dbf beside a shapefile's .shp, which is where a parcel layer's figures live: the header's field descriptors (a name of
     * eleven bytes, a type, a length), then the records at a fixed width, a deleted one marked with an asterisk. Read as a CSV would be, the
     * numbers as text, a date as its eight digits.
     */
    static void readDbf(InputStream in, List<String> cols, BiFunction<Map<String, String>, Integer, Boolean> take) throws IOException {
        byte[] head = in.readNBytes(32);
        if (head.length < 32) throw new IOException("not a dBase table");
        int headerLength = (head[8] & 0xff) | ((head[9] & 0xff) << 8), recordLength = (head[10] & 0xff) | ((head[11] & 0xff) << 8);
        int ldid = head[29] & 0xff;
        Charset cs = ldid == 0x57 || ldid == 0x4b || ldid == 0x4c ? StandardCharsets.UTF_8 : StandardCharsets.ISO_8859_1;
        List<int[]> widths = new ArrayList<>();
        int read = 32;
        while (read + 32 <= headerLength) {
            byte[] d = in.readNBytes(32);
            if (d.length < 32 || d[0] == 0x0D) { read += d.length; break; }
            read += 32;
            int end = 0; while (end < 11 && d[end] != 0) end++;
            cols.add(new String(d, 0, end, StandardCharsets.ISO_8859_1).strip());
            widths.add(new int[]{d[16] & 0xff, d[11]});
        }
        if (read < headerLength) in.readNBytes(headerLength - read);   // the terminator and any padding
        if (cols.isEmpty() || recordLength <= 0) throw new IOException("a dBase table with no fields");
        byte[] rec = new byte[recordLength];
        int i = 0;
        while (true) {
            int n = in.readNBytes(rec, 0, recordLength);
            if (n < recordLength) return;
            if (rec[0] == '*') continue;   // deleted
            Map<String, String> row = new LinkedHashMap<>();
            int at = 1;
            for (int c = 0; c < cols.size(); c++) {
                int w = widths.get(c)[0];
                String v = at + w <= recordLength ? new String(rec, at, w, cs).strip() : "";
                at += w;
                row.put(cols.get(c), v);
            }
            if (!take.apply(row, i++) || i >= MOST_ROWS) return;
        }
    }

    /** A delimited file with quoted fields, which may hold the delimiter and line breaks; the first record is the header; a tab-separated file whose header has no tab is read with commas. */
    static void readDelimited(BufferedReader r, char delim, List<String> cols, BiFunction<Map<String, String>, Integer, Boolean> take) throws IOException {
        List<String> header = record(r, delim);
        if (header == null) return;
        if (delim == '\t' && header.size() == 1 && header.get(0).contains(",")) { header = split(header.get(0), ','); delim = ','; }
        if (delim == ',' && header.size() == 1 && header.get(0).contains("\t")) { header = split(header.get(0), '\t'); delim = '\t'; }
        if (!header.isEmpty() && header.get(0).startsWith("﻿")) header.set(0, header.get(0).substring(1));
        cols.addAll(header);
        int i = 0;
        List<String> rec;
        while ((rec = record(r, delim)) != null) {
            if (rec.size() == 1 && rec.get(0).isEmpty()) continue;
            Map<String, String> row = new LinkedHashMap<>();
            for (int c = 0; c < header.size(); c++) row.put(header.get(c), c < rec.size() ? rec.get(c) : "");
            if (!take.apply(row, i++) || i >= MOST_ROWS) return;
        }
    }

    static List<String> split(String line, char delim) { List<String> out = new ArrayList<>(); for (String s : line.split(String.valueOf(delim), -1)) out.add(s); return out; }

    /** One record: fields split on the delimiter, a quoted field taken whole, a doubled quote inside it one quote; null at the end. */
    static List<String> record(Reader r, char delim) throws IOException {
        List<String> out = new ArrayList<>();
        StringBuilder f = new StringBuilder();
        boolean quoted = false, any = false;
        int c;
        while ((c = r.read()) >= 0) {
            any = true;
            if (quoted) {
                if (c == '"') { r.mark(1); int n = r.read(); if (n == '"') f.append('"'); else { quoted = false; if (n >= 0) r.reset(); } }
                else f.append((char) c);
            } else if (c == '"' && f.isEmpty()) quoted = true;
            else if (c == delim) { out.add(f.toString()); f.setLength(0); }
            else if (c == '\n') { out.add(f.toString()); return out; }
            else if (c == '\r') { r.mark(1); int n = r.read(); if (n != '\n' && n >= 0) r.reset(); out.add(f.toString()); return out; }
            else f.append((char) c);
        }
        if (!any) return null;
        out.add(f.toString());
        return out;
    }

    /** A JSON array of objects, or a GeoJSON FeatureCollection's features (the properties, with the first coordinate as __lon and __lat), streamed. */
    static void readJson(InputStream in, String kind, List<String> cols, BiFunction<Map<String, String>, Integer, Boolean> take) throws IOException {
        try (JsonParser p = F.createParser(in)) {
            JsonToken t = p.nextToken();
            if (t == JsonToken.START_OBJECT) {   // a FeatureCollection, or an object with a data array: to its array
                String key = null;
                while ((t = p.nextToken()) != null) {
                    if (t == JsonToken.FIELD_NAME) key = p.currentName();
                    else if (t == JsonToken.START_ARRAY && ("features".equals(key) || "data".equals(key) || "records".equals(key) || "results".equals(key))) break;
                    else if (t == JsonToken.START_OBJECT || t == JsonToken.START_ARRAY) p.skipChildren();
                }
                if (t != JsonToken.START_ARRAY) return;
            } else if (t != JsonToken.START_ARRAY) throw new IOException("not an array of records");
            int i = 0;
            while (p.nextToken() == JsonToken.START_OBJECT) {
                JsonNode n = M.readTree(p);
                Map<String, String> row;
                if (n.has("properties") && n.has("geometry")) {   // a feature
                    row = HomeRecords.flat(n.path("properties"));
                    JsonNode coords = n.path("geometry").path("coordinates");
                    while (coords.isArray() && !coords.isEmpty() && coords.get(0).isArray()) coords = coords.get(0);
                    if (coords.isArray() && coords.size() >= 2) { row.put("__lon", coords.get(0).asText("")); row.put("__lat", coords.get(1).asText("")); }
                } else row = HomeRecords.flat(n);
                if (cols.isEmpty()) for (String k : row.keySet()) if (!k.startsWith("__")) cols.add(k);
                if (!take.apply(row, i++) || i >= MOST_ROWS) return;
            }
        }
    }
}
