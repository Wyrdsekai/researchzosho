package org.researchzosho.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The archive, newspaper, NDL, software-archive and video-site clients against a local server that answers with what the real services
 * answered on 2026-10-07 (the recorded bodies under test resources), and the tools' rendering of them.
 */
class ArchivesToolsTest {

    static final ObjectMapper J = new ObjectMapper();
    static HttpServer server;
    static String base;

    static byte[] fixture(String name) {
        try (InputStream in = ArchivesToolsTest.class.getResourceAsStream("/archives/" + name)) { assertNotNull(in, name); return in.readAllBytes(); }
        catch (Exception e) { throw new IllegalStateException(name, e); }
    }

    @BeforeAll static void serve() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/", x -> {
            String path = x.getRequestURI().getPath(), query = x.getRequestURI().getRawQuery() == null ? "" : URLDecoder.decode(x.getRequestURI().getRawQuery(), StandardCharsets.UTF_8);
            byte[] body; int status = 200; String type = "application/json";
            if (path.startsWith("/web/99999999id_/") || path.startsWith("/web/20100101id_/")) {   // the Wayback timegate: to the nearest copy
                x.getResponseHeaders().add("Location", base + "/web/19990826124746id_/" + path.substring(path.indexOf("id_/") + 4));
                x.sendResponseHeaders(302, -1); x.close(); return;
            } else if (path.startsWith("/web/19990826124746id_/")) { body = "<html><title>An old page</title><body>The old page text, as it was in 1999. It says the gears were cut by hand with files on a dividing plate.</body></html>".getBytes(StandardCharsets.UTF_8); type = "text/html"; }
            else if (path.startsWith("/timegate/")) { x.getResponseHeaders().add("Location", "http://archive.md/20150104031928/" + path.substring(10)); x.sendResponseHeaders(302, -1); x.close(); return; }
            else if (path.equals("/cdx/search/cdx") && query.contains("collapse=digest")) body = fixture("cdx-history.json");
            else if (path.equals("/cdx/search/cdx")) body = fixture("cdx-site.json");
            else if (path.equals("/collinfo.json")) body = ("[{\"id\":\"CC-MAIN-2026-39\",\"cdx-api\":\"" + base + "/CC-MAIN-2026-39-index\"}]").getBytes(StandardCharsets.UTF_8);
            else if (path.equals("/CC-MAIN-2026-39-index")) body = fixture("commoncrawl-index.jsonl");
            else if (path.equals("/advancedsearch.php") && query.contains("tvarchive")) body = fixture("ia-tv.json");
            else if (path.equals("/advancedsearch.php")) body = fixture("ia-texts.json");
            else if (path.equals("/fts/v1/search")) body = fixture("ia-fts.json");
            else if (path.endsWith("/w/api.php")) body = fixture("wiki-revisions.json");
            else if (path.equals("/collections/chronicling-america/")) body = fixture("loc-chronicling.json");
            else if (path.equals("/SRU")) { body = fixture("gallica.xml"); type = "text/xml"; }
            else if (path.equals("/sru/sru")) { body = fixture("delpher.xml"); type = "text/xml"; }
            else if (path.equals("/records.json")) body = fixture("digitalnz.json");
            else if (path.equals("/api/book/search")) body = fixture("ndl-lab-book.json");
            else if (path.equals("/api/page/search")) body = fixture("ndl-page-search.json");
            else if (path.startsWith("/api/1/origin/search/")) body = fixture("swh.json");
            else if (path.startsWith("/mr/package/")) body = "{\"package\":\"curl\",\"result\":[{\"version\":\"8.23.0-1\"},{\"version\":\"8.22.0-1\"}]}".getBytes(StandardCharsets.UTF_8);
            else if (path.equals("/api/v2/snapshot/video/contents/search")) body = fixture("niconico.json");
            else if (path.equals("/video_episodes/")) body = fixture("nebula-episodes.json");
            else if (path.equals("/api/v1/search/videos")) body = fixture("sepia.json");
            else if (path.equals("/api/v1/proxy")) body = fixture("odysee.json");
            else if (path.equals("/videos")) body = fixture("dailymotion.json");
            else { body = "not here".getBytes(StandardCharsets.UTF_8); status = 404; type = "text/plain"; }
            x.getResponseHeaders().add("Content-Type", type);
            x.sendResponseHeaders(status, body.length); x.getResponseBody().write(body); x.close();
        });
        server.start();
        Archives.WAYBACK = base; Archives.ARCHIVE_TODAY = base; Archives.COMMON_CRAWL = base; Archives.IA = base; Archives.IA_FTS = base; Archives.WIKIPEDIA = base + "/%s";
        OldNewspapers.LOC = base; OldNewspapers.GALLICA = base; OldNewspapers.DELPHER = base; OldNewspapers.DIGITALNZ = base;
        NdlFulltextTool.BASE = base; SoftwareArchiveTool.SWH = base; SoftwareArchiveTool.DEBIAN = base;
        VideoSitesTool.NICONICO = base; VideoSitesTool.NEBULA = base; VideoSitesTool.SEPIA = base; VideoSitesTool.ODYSEE = base; VideoSitesTool.DAILYMOTION = base;
    }

    @AfterAll static void stop() {
        server.stop(0);
        Archives.WAYBACK = "https://web.archive.org"; Archives.ARCHIVE_TODAY = "https://archive.ph"; Archives.COMMON_CRAWL = "https://index.commoncrawl.org"; Archives.IA = "https://archive.org";
        Archives.IA_FTS = "https://be-api.us.archive.org"; Archives.WIKIPEDIA = "https://%s.wikipedia.org";
        OldNewspapers.LOC = "https://www.loc.gov"; OldNewspapers.GALLICA = "https://gallica.bnf.fr"; OldNewspapers.DELPHER = "https://jsru.kb.nl"; OldNewspapers.DIGITALNZ = "https://api.digitalnz.org";
        NdlFulltextTool.BASE = "https://lab.ndl.go.jp/dl"; SoftwareArchiveTool.SWH = "https://archive.softwareheritage.org"; SoftwareArchiveTool.DEBIAN = "https://snapshot.debian.org";
        VideoSitesTool.NICONICO = "https://snapshot.search.nicovideo.jp"; VideoSitesTool.NEBULA = "https://content.api.nebula.app"; VideoSitesTool.SEPIA = "https://sepiasearch.org";
        VideoSitesTool.ODYSEE = "https://api.na-backend.odysee.com"; VideoSitesTool.DAILYMOTION = "https://api.dailymotion.com";
    }

    static ObjectNode args(String json) throws Exception { return (ObjectNode) J.readTree(json); }

    @Test
    void theNearestCopyComesFromTheTimegateRedirect() {
        Archives.Copy c = Archives.nearest("http://www.geocities.com/Tokyo/1234/", "");
        assertNotNull(c);
        assertEquals("Wayback Machine", c.archive());
        assertEquals("1999-08-26", c.date());
        assertEquals(base + "/web/19990826124746id_/http://www.geocities.com/Tokyo/1234/", c.copyUrl());
        assertSame(c, Archives.nearest("http://www.geocities.com/Tokyo/1234/", ""), "cached");
        // a page the Wayback Machine has not: archive.today's timegate, by date
        Archives.Copy t = Archives.nearest("https://www.nytimes.com/", "2015-01-01");
        assertNotNull(t);
        assertEquals("archive.today", t.archive());
        assertEquals("2015-01-04", t.date());
    }

    @Test
    void historySiteTextsTvAndWikiThroughTheTool() throws Exception {
        ArchiveSearchTool t = new ArchiveSearchTool();
        String h = t.execute(args("{\"kind\":\"history\",\"query\":\"example.com\"}"));
        assertTrue(h.contains("2002-01-20") && h.contains("2003-02-07") && h.contains("/web/20020120142510id_/https://example.com"), h);
        String s = t.execute(args("{\"kind\":\"site\",\"query\":\"geocities.com/Tokyo/1234/\",\"limit\":5}"));
        assertTrue(s.contains("http://www.geocities.com/Tokyo/1234/bookmark.html") && s.contains("[Wayback Machine]") && s.contains("index.html") && s.contains("[Common Crawl]"), s);
        String x = t.execute(args("{\"kind\":\"texts\",\"query\":\"iaido\"}"));
        assertTrue(x.contains("where the words occur in the scanned texts") && x.contains("iaido") && x.contains("/details/"), x);
        assertFalse(x.contains("{{{"), "the highlight marks are stripped");
        String tv = t.execute(args("{\"kind\":\"tv\",\"query\":\"iaido\"}"));
        assertTrue(tv.contains("Internet Archive tv") || tv.contains("nothing in the Internet Archive's tv"), tv);
        String w = t.execute(args("{\"kind\":\"wiki\",\"query\":\"Iaido\",\"at\":\"2020-01-01\"}"));
        assertTrue(w.contains("revisions of \"Iaido\"") && w.contains("oldid=933102388") && w.contains("2019-12-30"), w);
        String c = t.execute(args("{\"kind\":\"copy\",\"query\":\"http://www.geocities.com/Tokyo/1234/\"}"));
        assertTrue(c.contains("saved on 1999-08-26 by the Wayback Machine") && c.contains("web_fetch"), c);
        String none = t.execute(args("{\"kind\":\"heritage\",\"query\":\"iaido\"}"));
        assertTrue(none.contains("no heritage catalogue has a key") && none.contains("RESEARCHZOSHO_EUROPEANA_KEY"), none);
    }

    @Test
    void theNewspaperArchivesAnswerAsOneTable() throws Exception {
        String out = new OldNewspapersTool().execute(args("{\"query\":\"jiu jitsu\",\"limit\":2}"));
        assertTrue(out.contains("[us] ") && out.contains("1904-04-05") && out.contains("loc.gov/resource/sn84036008/1904-04-05"), out);
        assertTrue(out.contains("[fr] ") && out.contains("gallica.bnf.fr/ark:"), out);
        assertTrue(out.contains("[nl] ") && out.contains("resolver.kb.nl") , out);
        assertTrue(out.contains("[nz] ") && out.contains("paperspast.natlib.govt.nz"), out);
        assertTrue(out.contains("not searched, no key: Trove"), out);
        String fr = new OldNewspapersTool().execute(args("{\"query\":\"jiu-jitsu\",\"where\":\"fr\",\"from\":\"1900\",\"to\":\"1910\"}"));
        assertTrue(fr.contains("[fr] ") && !fr.contains("[us] "), fr);
    }

    @Test
    void ndlBooksAndThePassagesInsideOne() throws Exception {
        NdlFulltextTool t = new NdlFulltextTool();
        String books = t.execute(args("{\"keyword\":\"居合\"}"));
        assertTrue(books.contains("剣道手ほどき") && books.contains("id 958348") && books.contains("/book/958348") && books.contains("居合術独習法"), books);
        assertFalse(books.contains("<em>"), "the highlight tags are stripped");
        String inside = t.execute(args("{\"book_id\":\"958348\",\"find\":\"居合\"}"));
        assertTrue(inside.contains("page 11") && inside.contains("/book/958348?page=11") && inside.contains("…") && inside.contains("居合"), inside);
        assertTrue(inside.contains("Public Domain Mark"), inside);
    }

    @Test
    void softwareOriginsAndDebianVersions() throws Exception {
        SoftwareArchiveTool t = new SoftwareArchiveTool();
        String o = t.execute(args("{\"query\":\"researchzosho\"}"));
        assertTrue(o.contains("https://pypi.org/project/researchzosho/") && o.contains("last archived 2026-09-09") && o.contains("/browse/origin/directory/?origin_url="), o);
        String d = t.execute(args("{\"kind\":\"debian\",\"query\":\"curl\"}"));
        assertTrue(d.contains("8.23.0-1") && d.contains("/package/curl/"), d);
    }

    @Test
    void theVideoSitesAnswerAsOneList() throws Exception {
        String out = new VideoSitesTool().execute(args("{\"query\":\"居合\",\"limit\":2}"));
        assertTrue(out.contains("[niconico] ") && out.contains("nicovideo.jp/watch/nm10235032"), out);
        assertTrue(out.contains("[nebula] ") && out.contains("nebula.tv/") && out.contains("need a subscription"), out);
        assertTrue(out.contains("[peertube] ") && out.contains("[odysee] ") && out.contains("odysee.com/@") && out.contains("[dailymotion] "), out);
        assertTrue(out.contains("[archive] ") || out.contains("archive"), out);
        for (String s : VideoSitesTool.SITES) assertTrue(out.contains(s), s);
        String one = new VideoSitesTool().execute(args("{\"query\":\"iaido\",\"site\":\"dailymotion\"}"));
        assertTrue(one.contains("[dailymotion] Iaido Meaning") && !one.contains("[niconico]"), one);
    }
}
