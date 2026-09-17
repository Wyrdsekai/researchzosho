package org.researchzosho.librarian;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A Calibre library as a list of books. Calibre keeps {@code metadata.db} (SQLite) at the library's root
 * and a {@code metadata.opf} beside every book; the database is read first, read-only, and the OPF files
 * are read when the database is missing or cannot be opened. The books come out as a CSV with a title
 * column, which is what {@link Items} reads: each book becomes one item, its authors, year, series and
 * tags the note.
 */
public final class Calibre {

    /** One book as Calibre knows it. */
    public record Book(String title, String authors, String year, String series, String tags, String isbn, String publisher, String formats) { }

    /** How the books were read, for the person: "metadata.db" or "metadata.opf files". */
    public record Shelf(List<Book> books, String readFrom) { }

    private Calibre() { }

    /** A folder is a Calibre library when it holds metadata.db, or a metadata.opf within three levels. */
    public static boolean isLibrary(Path dir) {
        if (!Files.isDirectory(dir)) return false;
        if (Files.isRegularFile(dir.resolve("metadata.db"))) return true;
        try (var walk = Files.walk(dir, 3)) { return walk.anyMatch(p -> p.getFileName().toString().equals("metadata.opf")); } catch (IOException e) { return false; }
    }

    /** The books: from the database when it opens, else from the OPF files. */
    public static Shelf read(Path dir) throws IOException {
        Path db = dir.resolve("metadata.db");
        if (Files.isRegularFile(db)) {
            try { return new Shelf(fromDatabase(db), "metadata.db"); }
            catch (Exception e) { List<Book> opf = fromOpf(dir); if (!opf.isEmpty()) return new Shelf(opf, "metadata.opf files (metadata.db could not be read: " + e.getMessage() + ")"); throw new IOException("could not read " + db + ": " + e.getMessage()); }
        }
        List<Book> opf = fromOpf(dir);
        if (opf.isEmpty()) throw new IOException(dir + " holds no metadata.db and no metadata.opf: not a Calibre library");
        return new Shelf(opf, "metadata.opf files");
    }

    static final String QUERY = """
            SELECT b.title,
              (SELECT group_concat(a.name, ' & ') FROM books_authors_link l JOIN authors a ON a.id = l.author WHERE l.book = b.id) AS authors,
              b.pubdate,
              (SELECT s.name FROM books_series_link l JOIN series s ON s.id = l.series WHERE l.book = b.id) AS series,
              b.series_index,
              (SELECT group_concat(t.name, ', ') FROM books_tags_link l JOIN tags t ON t.id = l.tag WHERE l.book = b.id) AS tags,
              COALESCE((SELECT i.val FROM identifiers i WHERE i.book = b.id AND i.type = 'isbn'), b.isbn) AS isbn,
              (SELECT group_concat(p.name, ', ') FROM books_publishers_link l JOIN publishers p ON p.id = l.publisher WHERE l.book = b.id) AS publisher,
              (SELECT group_concat(d.format, ', ') FROM data d WHERE d.book = b.id) AS formats
            FROM books b ORDER BY b.id""";

    /** metadata.db, opened immutable (Calibre may have it open; nothing here writes). */
    public static List<Book> fromDatabase(Path db) throws Exception {
        Class.forName("org.sqlite.JDBC");
        List<Book> out = new ArrayList<>();
        String url = "jdbc:sqlite:" + sqliteUri(db.toAbsolutePath().toString()) + "?immutable=1&mode=ro";
        try (Connection c = DriverManager.getConnection(url); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(QUERY)) {
            while (rs.next()) {
                String series = nz(rs.getString("series"));
                double idx = rs.getDouble("series_index");
                if (!series.isEmpty() && idx > 0) series = series + " #" + (idx == Math.floor(idx) ? String.valueOf((long) idx) : String.valueOf(idx));
                out.add(new Book(nz(rs.getString("title")), nz(rs.getString("authors")), year(nz(rs.getString("pubdate"))), series, nz(rs.getString("tags")), nz(rs.getString("isbn")), nz(rs.getString("publisher")), nz(rs.getString("formats"))));
            }
        }
        return out;
    }

    /**
     * A file's address as SQLite wants it in a URI: forward slashes; a Windows drive path as file:///C:/…
     * ("file:C:/…" is read as a relative name and fails with "unable to open database file", which broke this
     * reader on Windows from 0.4.1 to 0.4.4); and the characters a URI gives meaning to escaped.
     */
    public static String sqliteUri(String absolutePath) {
        String p = absolutePath.replace("\\", "/").replace("%", "%25").replace("?", "%3f").replace("#", "%23").replace(" ", "%20");
        if (p.matches("^[A-Za-z]:/.*")) return "file:///" + p;
        return "file:" + p;
    }

    static final Pattern TITLE = Pattern.compile("<dc:title[^>]*>(.*?)</dc:title>", Pattern.DOTALL);
    static final Pattern CREATOR = Pattern.compile("<dc:creator[^>]*>(.*?)</dc:creator>", Pattern.DOTALL);
    static final Pattern DATE = Pattern.compile("<dc:date[^>]*>(.*?)</dc:date>", Pattern.DOTALL);
    static final Pattern SUBJECT = Pattern.compile("<dc:subject[^>]*>(.*?)</dc:subject>", Pattern.DOTALL);
    static final Pattern PUBLISHER = Pattern.compile("<dc:publisher[^>]*>(.*?)</dc:publisher>", Pattern.DOTALL);
    static final Pattern ISBN = Pattern.compile("<dc:identifier[^>]*(?:scheme=\"ISBN\"|opf:scheme=\"ISBN\")[^>]*>(.*?)</dc:identifier>", Pattern.DOTALL);
    static final Pattern SERIES = Pattern.compile("<meta[^>]*name=\"calibre:series\"[^>]*content=\"([^\"]*)\"");
    static final Pattern SERIES_INDEX = Pattern.compile("<meta[^>]*name=\"calibre:series_index\"[^>]*content=\"([^\"]*)\"");

    /** Every metadata.opf under the library, one book each, in path order. */
    static List<Book> fromOpf(Path dir) throws IOException {
        List<Path> files = new ArrayList<>();
        try (var walk = Files.walk(dir, 4)) { walk.filter(p -> p.getFileName().toString().equals("metadata.opf")).sorted().forEach(files::add); }
        List<Book> out = new ArrayList<>();
        for (Path f : files) {
            String x = Files.readString(f, StandardCharsets.UTF_8);
            String title = first(TITLE, x);
            if (title.isEmpty()) continue;
            StringBuilder authors = new StringBuilder(); Matcher m = CREATOR.matcher(x); while (m.find()) { if (authors.length() > 0) authors.append(" & "); authors.append(unescape(m.group(1))); }
            StringBuilder tags = new StringBuilder(); m = SUBJECT.matcher(x); while (m.find()) { if (tags.length() > 0) tags.append(", "); tags.append(unescape(m.group(1))); }
            String series = first(SERIES, x); String idx = first(SERIES_INDEX, x);
            if (!series.isEmpty() && !idx.isEmpty()) series = series + " #" + idx.replaceAll("\\.0+$", "");
            List<String> formats = new ArrayList<>();
            try (var sib = Files.list(f.getParent())) { sib.filter(Files::isRegularFile).map(p -> Corpus.ext(p)).filter(e -> !e.isEmpty() && !e.equals("opf") && !e.equals("jpg") && !e.equals("png")).sorted().forEach(e -> formats.add(e.toUpperCase())); }
            out.add(new Book(title, authors.toString(), year(first(DATE, x)), series, tags.toString(), first(ISBN, x), first(PUBLISHER, x), String.join(", ", formats)));
        }
        return out;
    }

    static String first(Pattern p, String x) { Matcher m = p.matcher(x); return m.find() ? unescape(m.group(1)).strip() : ""; }
    static String unescape(String s) { return s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'").strip(); }
    static String nz(String s) { return s == null ? "" : s.strip(); }
    static String year(String date) { Matcher m = Pattern.compile("(\\d{4})").matcher(date); if (!m.find()) return ""; String y = m.group(1); return y.equals("0101") ? "" : y; }   // Calibre's "no date" is 0101-01-01

    /** The shelf as a CSV with a title column, the shape Items reads. */
    public static String csv(List<Book> books) {
        StringBuilder sb = new StringBuilder("title,authors,year,series,tags,isbn,publisher,formats\n");
        for (Book b : books) sb.append(cell(b.title())).append(',').append(cell(b.authors())).append(',').append(cell(b.year())).append(',').append(cell(b.series())).append(',').append(cell(b.tags())).append(',').append(cell(b.isbn())).append(',').append(cell(b.publisher())).append(',').append(cell(b.formats())).append('\n');
        return sb.toString();
    }

    static String cell(String s) { String v = s == null ? "" : s; return v.contains(",") || v.contains("\"") || v.contains("\n") ? "\"" + v.replace("\"", "\"\"") + "\"" : v; }
}
