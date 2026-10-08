package org.researchzosho.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** The Podcast Index as a file (0.5.5): unpacking the weekly archive, building the word index, searching and browsing it. */
class PodcastIndexLocalTest {

    static final ObjectMapper J = new ObjectMapper();

    @AfterEach void reset() { PodcastIndexLocal.use(null); }

    /** A database in the index's shape, with a handful of shows. */
    static Path smallIndex(Path dir) throws Exception {
        Path db = dir.resolve("podcastindex_feeds.db");
        long now = Instant.now().getEpochSecond();
        try (Connection c = PodcastIndexLocal.open(db, false); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE podcasts (id INTEGER PRIMARY KEY, url TEXT NOT NULL UNIQUE, title TEXT NOT NULL, lastUpdate INTEGER, link TEXT NOT NULL, lastHttpStatus INTEGER, dead INTEGER, contentType TEXT NOT NULL, itunesId INTEGER, originalUrl TEXT NOT NULL, itunesAuthor TEXT NOT NULL, itunesOwnerName TEXT NOT NULL, explicit INTEGER, imageUrl TEXT NOT NULL, itunesType TEXT NOT NULL, generator TEXT NOT NULL, newestItemPubdate INTEGER, language TEXT NOT NULL, oldestItemPubdate INTEGER, episodeCount INTEGER, popularityScore INTEGER, priority INTEGER, createdOn INTEGER, updateFrequency INTEGER, chash TEXT NOT NULL, host TEXT NOT NULL, newestEnclosureUrl TEXT NOT NULL, podcastGuid TEXT NOT NULL, description TEXT NOT NULL, category1 TEXT NOT NULL, category2 TEXT NOT NULL, category3 TEXT NOT NULL, category4 TEXT NOT NULL, category5 TEXT NOT NULL, category6 TEXT NOT NULL, category7 TEXT NOT NULL, category8 TEXT NOT NULL, category9 TEXT NOT NULL, category10 TEXT NOT NULL, newestEnclosureDuration INTEGER, podcastId INTEGER, duplicateOf INTEGER)");
            String ins = "INSERT INTO podcasts (id,url,title,link,dead,contentType,originalUrl,itunesAuthor,itunesOwnerName,imageUrl,itunesType,generator,newestItemPubdate,language,episodeCount,popularityScore,chash,host,newestEnclosureUrl,podcastGuid,description,category1,category2,category3,category4,category5,category6,category7,category8,category9,category10,duplicateOf) VALUES ";
            st.execute(ins + "(1,'https://a.example/feed','Iaido Hour','https://a.example',0,'x','',  'Sword Society','','','episodic','',"+ now +",'en',40,9,'','','','','Talks on iaido and the Japanese sword.','sports','history','','','','','','','','','')");
            st.execute(ins + "(2,'https://b.example/feed','Aikido of London','https://b.example',0,'x','','Dojo','','','episodic','',"+ (now - 86400L*800) +",'en',20,5,'','','','','Aikido classes and talk.','sports','','','','','','','','','',NULL)");
            st.execute(ins + "(3,'https://c.example/feed','歴史のラジオ','https://c.example',0,'x','','放送局','','','episodic','',"+ now +",'ja',120,8,'','','','','日本の歴史、居合と剣術の話。','history','society','','','','','','','','',NULL)");
            st.execute(ins + "(4,'https://d.example/feed','Dead Iaido Show','https://d.example',1,'x','','Nobody','','','episodic','',"+ now +",'en',3,1,'','','','','iaido, once.','sports','','','','','','','','','',NULL)");
            st.execute(ins + "(6,'https://a2.example/feed','Iaido Hour (copy)','https://a.example',0,'x','','Sword Society','','','episodic','',"+ now +",'en',40,9,'','','','','Talks on iaido and the Japanese sword.','sports','','','','','','','','','',1)");
            st.execute(ins + "(5,'https://e.example/feed','History Weekly','https://e.example',0,'x','','Historians','','','episodic','',"+ (now + 60) +",'en',300,9,'','','','','The week in history.','history','','','','','','','','','','')");
        }
        return db;
    }

    @Test
    void theWordIndexFindsByWordNotBySubstringAndTheFiltersNarrow(@TempDir Path tmp) throws Exception {
        Path db = smallIndex(tmp);
        try (Connection c = PodcastIndexLocal.open(db, false)) { PodcastIndexLocal.index(c); }
        PodcastIndexLocal.use(db);
        assertTrue(PodcastIndexLocal.present()); assertEquals(6, PodcastIndexLocal.count());
        List<Podcasts.Show> hits = PodcastIndexLocal.search("iaido", "", "", false, 10);
        assertNotNull(hits);
        assertEquals(List.of("Iaido Hour"), hits.stream().map(Podcasts.Show::title).toList(), "a word, not a substring (no Aikido), not the dead show, not the duplicate: " + hits);
        assertEquals("Podcast Index, local file", hits.get(0).source());
        assertEquals(List.of("sports", "history"), hits.get(0).categories());
        List<Podcasts.Show> ja = PodcastIndexLocal.search("居合", "", "", false, 10);
        assertEquals(1, ja.size(), "a CJK word as text within its language's shows: " + ja); assertEquals("歴史のラジオ", ja.get(0).title());
        assertEquals("ja", PodcastIndexLocal.cjkLanguage("居合の番組")); assertEquals("zh", PodcastIndexLocal.cjkLanguage("居合")); assertEquals("ko", PodcastIndexLocal.cjkLanguage("검도")); assertNull(PodcastIndexLocal.cjkLanguage("iaido"));
        assertTrue(PodcastIndexLocal.search("剣術", "ja", "", false, 10).stream().anyMatch(s -> s.title().equals("歴史のラジオ")), "inside a run of characters");
        assertTrue(PodcastIndexLocal.search("iaido", "ja", "", false, 10).isEmpty(), "language narrows");
        List<Podcasts.Show> browse = PodcastIndexLocal.browse("en", "history", true, 10);
        assertEquals(List.of("History Weekly", "Iaido Hour"), browse.stream().map(Podcasts.Show::title).toList(), "by popularity, any category slot, active this year: " + browse);
        assertEquals(2, PodcastIndexLocal.browse("", "history", false, 10).stream().filter(s -> s.language().startsWith("ja") || s.title().equals("History Weekly")).count());
        assertTrue(PodcastIndexLocal.browse("", "sports", true, 10).stream().noneMatch(s -> s.title().equals("Aikido of London")), "active narrows to the last year");
        assertEquals("\"iaido\" \"sword\"", PodcastIndexLocal.matchQuery("iaido  sword")); assertEquals("\"居合\"*", PodcastIndexLocal.matchQuery("居合")); assertEquals("\"it's\"", PodcastIndexLocal.matchQuery("\"it's\""));
    }

    @Test
    void theToolSearchesTheFileFirstAndBrowsesIt(@TempDir Path tmp) throws Exception {
        Path db = smallIndex(tmp);
        try (Connection c = PodcastIndexLocal.open(db, false)) { PodcastIndexLocal.index(c); }
        PodcastIndexLocal.use(db);
        Podcasts.APPLE = "http://127.0.0.1:9";   // the live directories do not answer in this test
        try {
            PodcastSearchTool t = new PodcastSearchTool();
            String out = t.execute((ObjectNode) J.readTree("{\"kind\":\"shows\",\"query\":\"iaido\"}"));
            assertTrue(out.contains("Iaido Hour") && out.contains("[Podcast Index, local file]"), out);
            String browse = t.execute((ObjectNode) J.readTree("{\"kind\":\"browse\",\"language\":\"ja\",\"category\":\"history\"}"));
            assertTrue(browse.contains("歴史のラジオ") && browse.contains("the directory in ja under history"), browse);
            PodcastIndexLocal.use(tmp.resolve("absent.db"));
            String none = t.execute((ObjectNode) J.readTree("{\"kind\":\"browse\",\"language\":\"ja\"}"));
            assertTrue(none.contains("researchzosho podcast index download"), none);
        } finally { Podcasts.APPLE = "https://itunes.apple.com"; }
    }

    @Test
    void theWeeklyArchiveIsUnpackedToItsOneDatabase(@TempDir Path tmp) throws Exception {
        byte[] content = "SQLite format 3\0 pretend database bytes".getBytes(StandardCharsets.UTF_8);
        Path tgz = tmp.resolve("feeds.tgz");
        try (OutputStream o = new GZIPOutputStream(Files.newOutputStream(tgz))) {
            o.write(tarEntry("README.txt", "hello".getBytes(StandardCharsets.UTF_8)));   // something else first: it is skipped
            o.write(tarEntry("podcastindex_feeds.db", content));
            o.write(new byte[1024]);
        }
        Path out = tmp.resolve("fresh.db");
        PodcastIndexLocal.untarOne(tgz, out);
        assertArrayEquals(content, Files.readAllBytes(out));
    }

    /** One ustar entry: the 512-byte header with the name, mode, size and checksum, then the content padded to 512. */
    static byte[] tarEntry(String name, byte[] content) throws Exception {
        byte[] h = new byte[512];
        System.arraycopy(name.getBytes(StandardCharsets.US_ASCII), 0, h, 0, name.length());
        System.arraycopy("0000644\0".getBytes(StandardCharsets.US_ASCII), 0, h, 100, 8);
        System.arraycopy("0000000\0".getBytes(StandardCharsets.US_ASCII), 0, h, 108, 8);
        System.arraycopy("0000000\0".getBytes(StandardCharsets.US_ASCII), 0, h, 116, 8);
        System.arraycopy(String.format("%011o\0", content.length).getBytes(StandardCharsets.US_ASCII), 0, h, 124, 12);
        System.arraycopy("00000000000\0".getBytes(StandardCharsets.US_ASCII), 0, h, 136, 12);
        for (int i = 148; i < 156; i++) h[i] = ' ';
        h[156] = '0';
        System.arraycopy("ustar\0".getBytes(StandardCharsets.US_ASCII), 0, h, 257, 6);
        System.arraycopy("00".getBytes(StandardCharsets.US_ASCII), 0, h, 263, 2);
        int sum = 0; for (byte b : h) sum += b & 0xff;
        System.arraycopy(String.format("%06o\0 ", sum).getBytes(StandardCharsets.US_ASCII), 0, h, 148, 8);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        bo.write(h); bo.write(content); bo.write(new byte[(512 - content.length % 512) % 512]);
        return bo.toByteArray();
    }
}
