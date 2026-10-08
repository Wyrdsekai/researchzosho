package org.researchzosho.tools;

import org.researchzosho.Config;
import org.researchzosho.Stopping;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.GZIPInputStream;

/**
 * The Podcast Index as a file (0.5.5): the index publishes its whole directory of shows weekly as one SQLite database (about 1.8 GB to
 * download, 4.9 GB unpacked, 4.7 million shows on 2026-10-08), for exactly the use its API forbids — searching and browsing the directory at
 * large. {@code researchzosho podcast index download} fetches it, unpacks it, and builds a full-text index over titles, descriptions and
 * authors once (a word search then takes milliseconds; without the index a scan took 11 s and matched "Aikido" for "iaido"). The
 * housekeeping fetches the new week's file when the setting is on. Feed-level only: no episodes, people or transcripts; those stay with
 * the API and the feeds. The file lives beside the library's other state, under the user's home, and serves every library on the machine.
 */
public final class PodcastIndexLocal {
    private PodcastIndexLocal() { }

    static volatile String SOURCE = "https://public.podcastindex.org/podcastindex_feeds.db.tgz";
    static final String TOKENIZER = "unicode61 remove_diacritics 2";
    static final long WEEK_MS = 7L * 24 * 3600 * 1000;
    private static volatile Path override;

    /** Where the file lives: {@code ~/.researchzosho/podcastindex/podcastindex_feeds.db}; a test points it elsewhere. */
    public static Path file() { return override != null ? override : Config.home().resolve("podcastindex").resolve("podcastindex_feeds.db"); }
    static void use(Path p) { override = p; }

    public static boolean present() { return Files.isRegularFile(file()); }

    /** When the file was fetched, or null. */
    public static Instant fetchedAt() {
        try { return present() ? Files.getLastModifiedTime(file()).toInstant() : null; } catch (IOException e) { return null; }
    }

    /** Whether the weekly refresh is on (set by the download command) and the file is a week old. */
    public static boolean refreshDue() {
        if (!present()) return false;
        String on = Config.get("RESEARCHZOSHO_PODCASTINDEX_LOCAL");
        if (on == null || !on.equalsIgnoreCase("on")) return false;
        Instant at = fetchedAt();
        return at != null && Instant.now().toEpochMilli() - at.toEpochMilli() > WEEK_MS;
    }

    /**
     * Fetches the week's file, unpacks it, builds the indexes, and puts it in place of the last one; says what it did. The download goes to a
     * file beside the target and the swap is a rename, so a library reading the old file keeps a whole file until the new one is complete.
     */
    public static String download(PrintStream out) throws IOException {
        Path dir = file().getParent();
        Files.createDirectories(dir);
        Path tgz = dir.resolve("download.tgz"), fresh = dir.resolve("fresh.db");
        try { return fetchAndSwap(out, tgz, fresh); }
        finally { Files.deleteIfExists(tgz); Files.deleteIfExists(fresh); }   // a failed attempt leaves nothing behind; the old file, untouched, keeps serving
    }

    private static String fetchAndSwap(PrintStream out, Path tgz, Path fresh) throws IOException {
        long t0 = System.currentTimeMillis();
        if (out != null) out.println("Fetching the Podcast Index's weekly file (about 1.8 GB)…");
        try {
            HttpResponse<Path> r = Stopping.send(Archives.HTTP, HttpRequest.newBuilder(URI.create(SOURCE)).timeout(Duration.ofMinutes(60)).header("User-Agent", Archives.UA).GET().build(),
                    HttpResponse.BodyHandlers.ofFile(tgz), Duration.ofMinutes(60), "the Podcast Index's download");
            if (r.statusCode() != 200) throw new IOException("the download answered HTTP " + r.statusCode());
        } catch (Stopping.Requested stop) { throw stop; }
        catch (IOException e) { throw e; }
        catch (Exception e) { throw new IOException(e.getMessage(), e); }
        long fetched = (System.currentTimeMillis() - t0) / 1000;
        if (out != null) out.println("  fetched " + Files.size(tgz) / 1_000_000 + " MB in " + fetched + " s; unpacking…");
        Files.deleteIfExists(fresh);
        untarOne(tgz, fresh);
        Files.deleteIfExists(tgz);
        if (out != null) out.println("  unpacked " + Files.size(fresh) / 1_000_000 + " MB; building the word index (a few minutes)…");
        long t1 = System.currentTimeMillis();
        try (Connection c = open(fresh, false)) { index(c); }
        catch (SQLException e) { throw new IOException("the index could not be built: " + e.getMessage(), e); }
        Files.move(fresh, file(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        Files.setLastModifiedTime(file(), java.nio.file.attribute.FileTime.from(Instant.now()));
        try { Config.set("RESEARCHZOSHO_PODCASTINDEX_LOCAL", "on"); } catch (IOException ignored) { }
        long shows = count();
        String said = "The Podcast Index is on this machine: " + shows + " shows, " + Files.size(file()) / 1_000_000 + " MB, word index built in " + (System.currentTimeMillis() - t1) / 1000
                + " s. The housekeeping fetches the new week's file from now on (RESEARCHZOSHO_PODCASTINDEX_LOCAL=off stops that; researchzosho podcast index remove deletes the file).";
        if (out != null) out.println(said);
        return said;
    }

    /** The one regular file in a gzipped tar — the index ships as a tar of its single database — written to {@code to}. */
    static void untarOne(Path tgz, Path to) throws IOException {
        try (InputStream in = new GZIPInputStream(Files.newInputStream(tgz), 1 << 16)) {
            byte[] header = new byte[512];
            while (true) {
                if (in.readNBytes(header, 0, 512) < 512) throw new IOException("the archive holds no database file");
                boolean empty = true; for (byte b : header) if (b != 0) { empty = false; break; }
                if (empty) throw new IOException("the archive holds no database file");
                String name = new String(header, 0, 100, StandardCharsets.UTF_8).split("\0")[0];
                String sizeField = new String(header, 124, 12, StandardCharsets.US_ASCII).split("\0")[0].strip();
                long size = sizeField.isEmpty() ? 0 : Long.parseLong(sizeField, 8);
                byte type = header[156];
                boolean regular = (type == '0' || type == 0) && name.endsWith(".db");
                if (regular) {
                    try (OutputStream o = Files.newOutputStream(to)) {
                        byte[] buf = new byte[1 << 16];
                        long left = size;
                        while (left > 0) { int n = in.read(buf, 0, (int) Math.min(buf.length, left)); if (n < 0) throw new IOException("the archive ends early"); o.write(buf, 0, n); left -= n; }
                    }
                    return;
                }
                long skip = size + (512 - size % 512) % 512;
                while (skip > 0) { long s = in.skip(skip); if (s <= 0) { if (in.read() < 0) throw new IOException("the archive ends early"); s = 1; } skip -= s; }
            }
        }
    }

    static Connection open(Path db, boolean readOnly) throws SQLException {
        // the read-only form is the file: URI (a plain path with ?mode=ro is taken as a file name and opens an empty database)
        String path = db.toAbsolutePath().toString().replace("\\", "/");
        return readOnly ? DriverManager.getConnection("jdbc:sqlite:file:" + (path.startsWith("/") ? "" : "/") + path + "?mode=ro") : DriverManager.getConnection("jdbc:sqlite:" + path);
    }

    /** The word index over title, description and author, and the indexes the filters use; built once, kept if already there. */
    static void index(Connection c) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute("CREATE VIRTUAL TABLE IF NOT EXISTS podcasts_fts USING fts5(title, description, itunesAuthor, content='podcasts', content_rowid='id', tokenize='" + TOKENIZER + "')");
            boolean built; try (ResultSet rs = st.executeQuery("SELECT count(*) FROM podcasts_fts WHERE podcasts_fts MATCH 'a' LIMIT 1")) { built = rs.next() && rs.getLong(1) > 0; } catch (SQLException e) { built = false; }
            if (!built) st.execute("INSERT INTO podcasts_fts(podcasts_fts) VALUES('rebuild')");
            st.execute("CREATE INDEX IF NOT EXISTS podcasts_language ON podcasts(language)");
            st.execute("CREATE INDEX IF NOT EXISTS podcasts_category1 ON podcasts(category1)");
            st.execute("CREATE INDEX IF NOT EXISTS podcasts_newest ON podcasts(newestItemPubdate)");
        }
    }

    public static long count() {
        if (!present()) return 0;
        try (Connection c = open(file(), true); Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT count(*) FROM podcasts")) { return rs.next() ? rs.getLong(1) : 0; }
        catch (SQLException e) { return 0; }
    }

    /** The words as a full-text query: each word quoted, so punctuation and the index's own syntax cannot break it; a CJK run gets a prefix match. */
    static String matchQuery(String words) {
        StringBuilder q = new StringBuilder();
        for (String w : words.strip().split("\\s+")) {
            if (w.isEmpty()) continue;
            String clean = w.replace("\"", "");
            if (clean.isEmpty()) continue;
            boolean cjk = clean.codePoints().anyMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN || Character.UnicodeScript.of(cp) == Character.UnicodeScript.HIRAGANA || Character.UnicodeScript.of(cp) == Character.UnicodeScript.KATAKANA || Character.UnicodeScript.of(cp) == Character.UnicodeScript.HANGUL);
            q.append(q.length() == 0 ? "" : " ").append('"').append(clean).append('"').append(cjk ? "*" : "");
        }
        return q.toString();
    }

    /**
     * Shows whose title, description or author hold the words, the best matches first; language (a code, ja) and category narrow; active =
     * something this year. A query in Chinese, Japanese or Korean is searched as text within its language's shows: the word index splits
     * those scripts at punctuation only, so a word inside a run is not a token (measured 2026-10-08: the index found 2 of the 9 Japanese
     * shows for 居合; a text search of the 50,000 Japanese shows takes about 3 s and finds all 9).
     */
    public static List<Podcasts.Show> search(String words, String language, String category, boolean active, int limit) {
        if (!present()) return null;
        String w = words == null ? "" : words.strip();
        if (w.isEmpty()) return List.of();
        String cjk = cjkLanguage(w);
        List<String> langs = language == null || language.isBlank() ? List.of() : List.of(language);
        if (cjk != null) {
            // Han alone may be Japanese or Chinese: both, unless the language was given
            if (langs.isEmpty()) langs = cjk.equals("zh") ? List.of("ja", "zh") : List.of(cjk);
            String sql = "SELECT p.url, p.title, p.itunesAuthor, p.link, p.language, p.category1, p.category2, p.category3, p.category4, p.category5, p.episodeCount, p.newestItemPubdate, p.description, p.popularityScore"
                    + " FROM podcasts p WHERE p.dead = 0 AND NOT (typeof(p.duplicateOf) = 'integer' AND p.duplicateOf > 0)" + filters(langs.size(), category, active) + " AND (p.title LIKE ? OR p.description LIKE ?) ORDER BY p.popularityScore DESC, p.newestItemPubdate DESC LIMIT ?";
            return query(sql, null, langs, category, active, limit, "%" + w.replace("%", "").replace("_", "") + "%");
        }
        String match = matchQuery(w);
        if (match.isEmpty()) return List.of();
        String sql = "SELECT p.url, p.title, p.itunesAuthor, p.link, p.language, p.category1, p.category2, p.category3, p.category4, p.category5, p.episodeCount, p.newestItemPubdate, p.description, p.popularityScore"
                + " FROM podcasts_fts f JOIN podcasts p ON p.id = f.rowid WHERE podcasts_fts MATCH ? AND p.dead = 0 AND NOT (typeof(p.duplicateOf) = 'integer' AND p.duplicateOf > 0)" + filters(langs.size(), category, active) + " ORDER BY bm25(podcasts_fts) LIMIT ?";
        return query(sql, match, langs, category, active, limit, null);
    }

    /** The language a query's script says it is in: ja for kana, ko for hangul, zh for Han alone; null for the rest. */
    static String cjkLanguage(String s) {
        boolean kana = false, hangul = false, han = false;
        for (int cp : s.codePoints().toArray()) {
            Character.UnicodeScript sc = Character.UnicodeScript.of(cp);
            if (sc == Character.UnicodeScript.HIRAGANA || sc == Character.UnicodeScript.KATAKANA) kana = true;
            else if (sc == Character.UnicodeScript.HANGUL) hangul = true;
            else if (sc == Character.UnicodeScript.HAN) han = true;
        }
        return kana ? "ja" : hangul ? "ko" : han ? "zh" : null;
    }

    /** The directory by category and language, the most popular and recent first: browsing, which the API does not offer. */
    public static List<Podcasts.Show> browse(String language, String category, boolean active, int limit) {
        if (!present()) return null;
        if ((language == null || language.isBlank()) && (category == null || category.isBlank())) return List.of();
        List<String> langs = language == null || language.isBlank() ? List.of() : List.of(language);
        String sql = "SELECT p.url, p.title, p.itunesAuthor, p.link, p.language, p.category1, p.category2, p.category3, p.category4, p.category5, p.episodeCount, p.newestItemPubdate, p.description, p.popularityScore"
                + " FROM podcasts p WHERE p.dead = 0 AND NOT (typeof(p.duplicateOf) = 'integer' AND p.duplicateOf > 0)" + filters(langs.size(), category, active) + " ORDER BY p.popularityScore DESC, p.newestItemPubdate DESC LIMIT ?";
        return query(sql, null, langs, category, active, limit, null);
    }

    // a show that is no duplicate carries '' in duplicateOf (text), a duplicate the other show's id (integer); NULL and 0 appear nowhere (measured 2026-10-08)
    static String filters(int languages, String category, boolean active) {
        StringBuilder w = new StringBuilder();
        if (languages > 0) { w.append(" AND ("); for (int k = 0; k < languages; k++) w.append(k == 0 ? "" : " OR ").append("lower(p.language) LIKE ?"); w.append(")"); }
        if (category != null && !category.isBlank()) w.append(" AND (p.category1 = ? OR p.category2 = ? OR p.category3 = ? OR p.category4 = ? OR p.category5 = ?)");
        if (active) w.append(" AND p.newestItemPubdate > ?");
        return w.toString();
    }

    static List<Podcasts.Show> query(String sql, String match, List<String> langs, String category, boolean active, int limit, String like) {
        List<Podcasts.Show> out = new ArrayList<>();
        try (Connection c = open(file(), true); PreparedStatement ps = c.prepareStatement(sql)) {
            int i = 1;
            if (match != null) ps.setString(i++, match);
            for (String l : langs) ps.setString(i++, l.toLowerCase(Locale.ROOT).replaceAll("[-_].*$", "") + "%");
            if (category != null && !category.isBlank()) { String cat = category.toLowerCase(Locale.ROOT).strip(); for (int k = 0; k < 5; k++) ps.setString(i++, cat); }
            if (active) ps.setLong(i++, Instant.now().minus(Duration.ofDays(365)).getEpochSecond());
            if (like != null) { ps.setString(i++, like); ps.setString(i++, like); }
            ps.setInt(i, Math.max(1, limit));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    List<String> cats = new ArrayList<>();
                    for (int k = 6; k <= 10; k++) { String cv = rs.getString(k); if (cv != null && !cv.isBlank()) cats.add(cv); }
                    long newest = rs.getLong(12);
                    out.add(new Podcasts.Show(rs.getString(2), rs.getString(3), rs.getString(1), rs.getString(4), rs.getString(5), cats, rs.getInt(11),
                            newest > 0 ? Instant.ofEpochSecond(newest).toString().replaceAll("T.*", "") : "", "Podcast Index, local file"));
                }
            }
        } catch (SQLException e) { return null; }
        return out;
    }
}
