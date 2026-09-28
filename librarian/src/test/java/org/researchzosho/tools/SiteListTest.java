package org.researchzosho.tools;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The site list: a listed domain covers its subdomains, a hosts-file line its own host only; the list read from the day's download, the bundled one until a download has
 * arrived or when it is broken; one download attempt a day, kept only when it reads as a list. Every domain here is a placeholder.
 */
class SiteListTest {

    @TempDir Path home;
    SiteList.Downloader realDownloader;

    @BeforeEach void offline() {
        realDownloader = SiteList.downloader;
        SiteList.use(null);
        SiteList.dirOverride = home.resolve("site-list");
        SiteList.forget();
    }

    @AfterEach void back() {
        SiteList.downloader = realDownloader;
        SiteList.dirOverride = null;
        SiteList.forget();
        SiteList.use(SiteList.bundled());
    }

    /** A list long enough to be taken as one: the placeholder domains, and a thousand more. */
    static String aList(String... domains) {
        StringBuilder b = new StringBuilder("# Title: placeholder list\n");
        for (String d : domains) b.append(d).append('\n');
        for (int i = 0; i < SiteList.MIN_ENTRIES; i++) b.append("filler-").append(i).append(".example\n");
        return b.toString();
    }

    @Test
    void aListedDomainCoversItsSubdomainsAndNothingBeside() {
        SiteList l = SiteList.of("test", "placeholder-adult.example", "shock-placeholder.example", "placeholdertld");
        assertTrue(l.listed("placeholder-adult.example"));
        assertTrue(l.listed("https://www.placeholder-adult.example/page?x=1"), "a subdomain");
        assertTrue(l.listed("http://a.b.placeholder-adult.example:8080/"), "a deeper subdomain, with a port");
        assertTrue(l.listed("HTTPS://WWW.Placeholder-Adult.Example./"), "case and a trailing dot");
        assertTrue(l.listed("https://anything.placeholdertld/"), "a listed top-level domain covers every site under it");
        assertFalse(l.listed("https://notplaceholder-adult.example/"), "a longer name is another site");
        assertFalse(l.listed("https://placeholder-adult.example.org/"), "the listed name inside another domain is another site");
        assertFalse(l.listed("https://example.org/placeholder-adult.example"), "the path is not the site");
        assertFalse(l.listed(""));
        assertFalse(l.listed(null));
    }

    @Test
    void anAddressTheStrictParserRefusesIsStillReadForItsSite() {
        SiteList l = SiteList.of("test", "placeholder-adult.example");
        assertTrue(l.listed("https://www.placeholder-adult.example/a path with spaces|and a bar"), "not treated as not listed");
        assertTrue(l.listed("http://someone@www.placeholder-adult.example:8080/%%bad"), "a user and a port");
        assertFalse(l.listed("https://example.org/placeholder-adult.example with a space"));
    }

    @Test
    void aDownloadIsTakenOnlyWhenNearlyAllOfItIsDomainsAndItsCountIsWhatItsHeaderSays() throws Exception {
        String good = aList("fresh-placeholder.example");
        assertTrue(SiteList.looksLikeAList(good, SiteList.parse(new StringReader(good), "t").size()));
        StringBuilder mixed = new StringBuilder(good);
        for (int i = 0; i < 100; i++) mixed.append("<div class=\"placeholder\">not a domain ").append(i).append("</div>\n");
        String m = mixed.toString();
        assertFalse(SiteList.looksLikeAList(m, SiteList.parse(new StringReader(m), "t").size()), "an error page around a list is not a list");
        String shortOfItsHeader = "# Entries: 5000\n" + good;
        assertFalse(SiteList.looksLikeAList(shortOfItsHeader, SiteList.parse(new StringReader(shortOfItsHeader), "t").size()), "cut short: fewer than its header says");
        String asItsHeaderSays = "# Entries: " + (SiteList.MIN_ENTRIES + 1) + "\n" + good;
        assertTrue(SiteList.looksLikeAList(asItsHeaderSays, SiteList.parse(new StringReader(asItsHeaderSays), "t").size()));
        // and the download itself
        SiteList.downloader = url -> shortOfItsHeader.getBytes(StandardCharsets.UTF_8);
        assertFalse(SiteList.refreshNow());
        assertFalse(Files.exists(SiteList.file()));
    }

    @Test
    void eachDownloadWritesAFileOfItsOwnBeforeTheListIsReplaced() throws Exception {
        // another program's download under way: the name the old code wrote to is taken
        Files.createDirectories(SiteList.dir().resolve("nsfw-domains.txt.part"));
        SiteList.downloader = url -> aList("fresh-placeholder.example").getBytes(StandardCharsets.UTF_8);
        assertTrue(SiteList.refreshNow(), "its own file, not a name shared with every other program");
        assertTrue(Files.readString(SiteList.file()).contains("fresh-placeholder.example"));
        try (var left = Files.list(SiteList.dir())) {
            assertEquals(List.of("nsfw-domains.txt", "nsfw-domains.txt.part"), left.map(p -> p.getFileName().toString()).sorted().toList(), "nothing of this attempt is left behind");
        }
    }

    @Test
    void theListIsReadInEachFormItComesIn() throws Exception {
        SiteList l = SiteList.parse(new StringReader("""
                # a comment
                ! another
                plain-placeholder.example
                0.0.0.0 hosts-placeholder.example
                127.0.0.1 www.hosts2-placeholder.example
                comment-placeholder.example        #a note after the domain
                ||abp-placeholder.example^

                0.0.0.0 0.0.0.0
                localhost
                10.1.2.3
                """), "test");
        assertEquals(5, l.size(), "five domains, and the address lines and comments are not domains");
        for (String d : new String[]{"plain-placeholder.example", "hosts-placeholder.example", "www.hosts2-placeholder.example", "comment-placeholder.example", "abp-placeholder.example"})
            assertTrue(l.listed(d), d);
        assertFalse(l.listed("hosts2-placeholder.example"), "a listed subdomain does not cover its parent");
        // a domain line covers its subdomains; a hosts-file line is that exact host only
        assertTrue(l.listed("https://www.plain-placeholder.example/"));
        assertTrue(l.listed("https://a.abp-placeholder.example/") && l.listed("https://a.comment-placeholder.example/"));
        assertFalse(l.listed("https://someone.blog.hosts-placeholder.example/"), "a hosts file lists hosts, not domains");
        assertFalse(l.listed("https://a.www.hosts2-placeholder.example/"));
    }

    @Test
    void theBundledListsHostsLinesCoverTheirHostOnly() throws Exception {
        SiteList b = SiteList.bundled();
        // a blog or a shop on a shared host that the hosts list names by its bare domain: many personal-history pages live on these
        assertTrue(b.listed("https://fc2.com/"), "the host the hosts list names");
        for (String own : new String[]{"https://someone.blog.fc2.com/", "https://historyclub.web.fc2.com/placeholder.html", "https://shop.booth.pm/items/1"})
            assertFalse(b.listed(own), own);
        // the shock list's lines are domains: each covers its subdomains (read from the bundled file itself, so no such name is written here)
        String domain = null;
        try (var in = new GZIPInputStream(SiteList.class.getResourceAsStream(SiteList.BUNDLED));
             var r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            for (String line; (line = r.readLine()) != null; ) if (!line.startsWith("#") && !line.isBlank() && !SiteList.hostsLine(line)) { domain = line.strip(); break; }
        }
        assertNotNull(domain, "the bundled file carries domain lines");
        assertTrue(b.listed("https://www." + domain + "/"), "a domain line covers its subdomains");
    }

    @Test
    void theBundledListLoadsAndLeavesOrdinarySitesAlone() {
        SiteList b = SiteList.bundled();
        assertTrue(b.size() > 50_000, "the bundled list holds " + b.size());
        assertTrue(b.origin().contains("bundled"), b.origin());
        for (String ordinary : new String[]{"https://en.wikipedia.org/wiki/Gear", "https://example.org/", "https://www.who.int/", "https://github.com/", "https://archive.org/"})
            assertFalse(b.listed(ordinary), ordinary);
    }

    @Test
    void untilADownloadHasArrivedTheBundledListIsUsedAndThenTheDownloadedOne() throws Exception {
        assertSame(SiteList.bundled(), SiteList.current(), "no download yet: the bundled list");
        Files.createDirectories(SiteList.dir());
        Files.writeString(SiteList.file(), aList("downloaded-placeholder.example"), StandardCharsets.UTF_8);
        SiteList.forget();
        SiteList now = SiteList.current();
        assertTrue(now.listed("https://downloaded-placeholder.example/"), "the downloaded list is read");
        assertTrue(now.origin().startsWith("the OISD nsfw list, downloaded on "), now.origin());
        // a file that is not a list (a download cut short, an error page) is not used: the bundled one is
        Files.writeString(SiteList.file(), "<html>placeholder error page</html>\n", StandardCharsets.UTF_8);
        SiteList.forget();
        assertSame(SiteList.bundled(), SiteList.current());
    }

    @Test
    void oneDownloadAttemptADayKeptOnlyWhenItIsAList() throws Exception {
        AtomicInteger asked = new AtomicInteger();
        SiteList.downloader = url -> { asked.incrementAndGet(); assertEquals(SiteList.SOURCE_URL, url); return aList("fresh-placeholder.example").getBytes(StandardCharsets.UTF_8); };
        long now = System.currentTimeMillis();
        Thread t = SiteList.refreshIfDue(now);
        assertNotNull(t, "no attempt before: one is made");
        t.join(10_000);
        assertEquals(1, asked.get());
        assertTrue(Files.readString(SiteList.file()).contains("fresh-placeholder.example"), "written into the ResearchZosho folder");
        SiteList.forget();
        assertTrue(SiteList.current().listed("fresh-placeholder.example"));
        assertNull(SiteList.refreshIfDue(now + 3_600_000L), "an hour later: no second attempt");
        assertNull(SiteList.refreshIfDue(now + SiteList.DAY_MS - 1), "within the day: none");
        // a day later the server answers with something that is not a list: the one on disk is kept
        SiteList.downloader = url -> { asked.incrementAndGet(); return "<html>placeholder error page</html>".getBytes(StandardCharsets.UTF_8); };
        Thread again = SiteList.refreshIfDue(now + SiteList.DAY_MS + 1);
        assertNotNull(again);
        again.join(10_000);
        assertEquals(2, asked.get());
        assertTrue(Files.readString(SiteList.file()).contains("fresh-placeholder.example"), "the list on disk is kept");
    }

    @Test
    void offlineTheBundledListGoesOnAndAFailedFirstDownloadIsTriedAgainInAnHour() throws Exception {
        AtomicInteger asked = new AtomicInteger();
        SiteList.downloader = url -> { asked.incrementAndGet(); throw new IOException("placeholder: no network"); };
        long now = System.currentTimeMillis();
        Thread t = SiteList.refreshIfDue(now);
        assertNotNull(t);
        t.join(10_000);
        assertEquals(1, asked.get());
        assertFalse(Files.exists(SiteList.file()), "nothing was written");
        assertTrue(Files.exists(SiteList.lastTry()), "the attempt was written down");
        assertSame(SiteList.bundled(), SiteList.current(), "the bundled list goes on being used");
        assertNull(SiteList.refreshIfDue(now + 60_000), "not again within the hour");
        Thread again = SiteList.refreshIfDue(now + SiteList.HOUR_MS + 1);
        assertNotNull(again, "nothing has arrived yet: an hour later it is tried again, not the next day");
        again.join(10_000);
        assertEquals(2, asked.get());
    }

    @Test
    void offlineWithAListOnDiskTheNextAttemptIsTomorrow() throws Exception {
        Files.createDirectories(SiteList.dir());
        Files.writeString(SiteList.file(), aList("downloaded-placeholder.example"), StandardCharsets.UTF_8);
        AtomicInteger asked = new AtomicInteger();
        SiteList.downloader = url -> { asked.incrementAndGet(); throw new IOException("placeholder: no network"); };
        long now = System.currentTimeMillis();
        Thread t = SiteList.refreshIfDue(now);
        assertNotNull(t);
        t.join(10_000);
        assertTrue(Files.readString(SiteList.file()).contains("downloaded-placeholder.example"), "the list on disk goes on being used");
        assertNull(SiteList.refreshIfDue(now + SiteList.HOUR_MS + 1), "a list is on disk: the next attempt waits for the next day");
        assertEquals(1, asked.get());
    }

    @Test
    void anAttemptCutOffWithItsCommandIsNotTakenForTheDaysAttempt() throws Exception {
        AtomicInteger asked = new AtomicInteger();
        SiteList.downloader = url -> { asked.incrementAndGet(); return aList("fresh-placeholder.example").getBytes(StandardCharsets.UTF_8); };
        long now = System.currentTimeMillis();
        // a command that started a download and ended before it did: the marker stays, and no attempt is written down
        Files.createDirectories(SiteList.dir());
        Files.writeString(SiteList.underWay(), "placeholder\n");
        Files.setLastModifiedTime(SiteList.underWay(), FileTime.fromMillis(now - 60_000));
        assertNull(SiteList.refreshIfDue(now), "another may still be downloading it");
        Files.setLastModifiedTime(SiteList.underWay(), FileTime.fromMillis(now - SiteList.STALE_MS - 1));
        Thread t = SiteList.refreshIfDue(now);
        assertNotNull(t, "ten minutes on, it was cut off: this one downloads");
        t.join(10_000);
        assertEquals(1, asked.get());
        assertFalse(Files.exists(SiteList.underWay()));
        assertTrue(Files.exists(SiteList.lastTry()));
    }
}
