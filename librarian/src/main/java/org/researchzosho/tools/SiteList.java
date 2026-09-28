package org.researchzosho.tools;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.net.IDN;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

import org.researchzosho.Config;

/**
 * The site list: sites of pornography, shock and gore, left out of search results and never fetched, unless the person said yes to
 * letting that material in for one question. It is somebody else's list, and says whose: the person's own list of refused sources
 * ({@code SourceRules}) is a separate thing, written by them.
 *
 * <p>Where it comes from: the OISD nsfw list (https://nsfw.oisd.nl/domainswild2, GPL-3.0, about 470,000 domains, which blocks
 * porn, adult, shock and gore sites), downloaded while the library runs into the ResearchZosho folder ({@code <home>/site-list/}),
 * never into a library and never into the program. Every library on the machine uses the one file. It is fetched again at most once a
 * day; an attempt that fails (no network) is not repeated until the next day, and the list already on disk goes on being used; while
 * nothing has arrived yet, a failed attempt is tried again after an hour. Until the first download has arrived, a small list bundled
 * with the program is used: Sinfonietta's pornography hosts (MIT) and ShadowWhisperer's Shock list (Unlicense), about 61,000 hosts.
 *
 * <p>Two kinds of line. A domain line, as OISD writes its wildcard list ({@code example.com}, and {@code *.example.com} and
 * {@code ||example.com^} the same), covers the domain and its subdomains: {@code www.example.com} and {@code a.b.example.com}, and a
 * listed top-level domain every site under it. A hosts-file line ({@code 0.0.0.0 host}) covers that exact host only: a hosts file
 * lists hosts, not domains, so its {@code fc2.com} is the site at that address, and a blog at {@code someone.blog.fc2.com} is not on
 * it. The lookup keeps two sorted arrays of 64-bit hashes, about 4 MB for the whole OISD list, and asks the exact one for the host and
 * the wildcard one for the host and each parent domain.
 *
 * <p>{@code RESEARCHZOSHO_SITE_LIST=off} never downloads the list, and the bundled one is used; {@code RESEARCHZOSHO_SITE_LIST_URL}
 * downloads another list in the same form (one domain per line; a hosts file and {@code domain  # comment} lines are read too).
 */
public final class SiteList {

    /** Where the list is downloaded from, unless {@code RESEARCHZOSHO_SITE_LIST_URL} names another. */
    public static final String SOURCE_URL = "https://nsfw.oisd.nl/domainswild2";

    /** A download with fewer domains than this is not a list: an error page, or a list cut short. The one on disk is kept. */
    static final int MIN_ENTRIES = 1000;
    static final long DAY_MS = 24L * 3600 * 1000, HOUR_MS = 3600L * 1000;
    static final String BUNDLED = "site-list-bundled.txt.gz";

    /** The domains that cover their subdomains, and the hosts that cover themselves only, each as sorted 64-bit hashes. */
    private final long[] wild, exact;
    private final String origin;

    private SiteList(long[] wild, long[] exact, String origin) { this.wild = wild; this.exact = exact; this.origin = origin; }

    /** How many domains and hosts the list holds. */
    public int size() { return wild.length + exact.length; }

    /** Where this list came from, in a few words a person reads: "the OISD nsfw list, downloaded on 2026-09-23". */
    public String origin() { return origin; }

    /** Whether the site of this address (or this host) is on the list: the host itself, or any domain it sits under. */
    public boolean listed(String hostOrUrl) {
        String h = host(hostOrUrl);
        if (h == null || h.isEmpty() || size() == 0) return false;
        if (Arrays.binarySearch(exact, hash(h)) >= 0) return true;   // a hosts-file line: this host only
        String cur = h;
        while (true) {
            if (Arrays.binarySearch(wild, hash(cur)) >= 0) return true;   // a domain line: the domain and everything under it
            int dot = cur.indexOf('.');
            if (dot < 0) return false;
            cur = cur.substring(dot + 1);
        }
    }

    /** The host of an address, lowercased and in its ASCII form, without a trailing dot; the text itself when it is a bare host. */
    static String host(String hostOrUrl) {
        if (hostOrUrl == null || hostOrUrl.isBlank()) return null;
        String t = hostOrUrl.strip();
        if (t.contains("://")) {
            String parsed = null;
            try { parsed = URI.create(t).getHost(); } catch (Exception notStrict) { /* read leniently below */ }
            // an address that the strict parser refuses (a space, a bar, an unencoded character) is still an address on a site
            t = parsed != null ? parsed : lenientHost(t);
            if (t == null || t.isEmpty()) return null;
        } else {
            int slash = t.indexOf('/'); if (slash >= 0) t = t.substring(0, slash);
            int colon = t.indexOf(':'); if (colon >= 0) t = t.substring(0, colon);
        }
        t = t.toLowerCase(Locale.ROOT);
        while (t.endsWith(".")) t = t.substring(0, t.length() - 1);
        try { t = IDN.toASCII(t, IDN.ALLOW_UNASSIGNED); } catch (Exception keepAsIs) { }
        return t.toLowerCase(Locale.ROOT);
    }

    /** The host of an address read by hand: after the scheme, before the path, the query or the fragment, without a user or a port. */
    static String lenientHost(String url) {
        String t = url.substring(url.indexOf("://") + 3);
        int end = t.length();
        for (char c : new char[]{'/', '?', '#', '\\'}) { int i = t.indexOf(c); if (i >= 0 && i < end) end = i; }
        t = t.substring(0, end);
        int at = t.lastIndexOf('@');
        if (at >= 0) t = t.substring(at + 1);
        if (t.startsWith("[")) return null;   // an IPv6 address is no site on a list of names
        int colon = t.indexOf(':');
        if (colon >= 0) t = t.substring(0, colon);
        return t.strip();
    }

    /** FNV-1a, 64 bits: with half a million domains, two of them sharing a hash is a chance of about one in a hundred million. */
    static long hash(String s) {
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            h ^= c & 0xff; h *= 0x100000001b3L;
            if (c > 0xff) { h ^= c >>> 8; h *= 0x100000001b3L; }
        }
        return h;
    }

    /**
     * A list read from text: one domain per line (a domain and its subdomains), or hosts-file lines ({@code 0.0.0.0 host}: that host
     * only), or {@code domain  # comment}; # and ! start a comment.
     */
    public static SiteList parse(Reader text, String origin) throws IOException {
        long[] wild = new long[1 << 16], exact = new long[1 << 10];
        int w = 0, x = 0;
        try (BufferedReader r = new BufferedReader(text)) {
            for (String line; (line = r.readLine()) != null; ) {
                String d = domainOf(line);
                if (d == null) continue;
                if (hostsLine(line)) {
                    if (x == exact.length) exact = Arrays.copyOf(exact, exact.length * 2);
                    exact[x++] = hash(d);
                } else {
                    if (w == wild.length) wild = Arrays.copyOf(wild, wild.length * 2);
                    wild[w++] = hash(d);
                }
            }
        }
        return new SiteList(sortedUnique(wild, w), sortedUnique(exact, x), origin);
    }

    private static long[] sortedUnique(long[] a, int n) {
        long[] h = Arrays.copyOf(a, n);
        Arrays.sort(h);
        int k = 0;
        for (int i = 0; i < h.length; i++) if (i == 0 || h[i] != h[i - 1]) h[k++] = h[i];
        return Arrays.copyOf(h, k);
    }

    /** Whether a line is a hosts-file line, {@code 0.0.0.0 host}: it names that exact host, not a domain with its subdomains. */
    static boolean hostsLine(String line) {
        String t = line.strip();
        int hashAt = t.indexOf('#');
        if (hashAt >= 0) t = t.substring(0, hashAt).strip();
        String[] parts = t.split("\\s+");
        return parts.length > 1 && (parts[0].equals("0.0.0.0") || parts[0].equals("127.0.0.1") || parts[0].equals("::"));
    }

    /** The domain a line of a list names, or null for a comment, a blank line or an address. */
    static String domainOf(String line) {
        String t = line.strip();
        int hashAt = t.indexOf('#');
        if (hashAt >= 0) t = t.substring(0, hashAt).strip();
        if (t.isEmpty() || t.startsWith("!")) return null;
        String[] parts = t.split("\\s+");
        String d = parts.length > 1 && (parts[0].equals("0.0.0.0") || parts[0].equals("127.0.0.1") || parts[0].equals("::")) ? parts[1] : parts[0];
        if (d.startsWith("||")) d = d.substring(2);
        if (d.endsWith("^")) d = d.substring(0, d.length() - 1);
        if (d.startsWith("*.")) d = d.substring(2);
        d = d.toLowerCase(Locale.ROOT);
        while (d.endsWith(".")) d = d.substring(0, d.length() - 1);
        if (d.isEmpty() || d.equals("localhost") || d.matches("[0-9.]+") || d.contains(":") || d.contains("/")) return null;
        return d;
    }

    // ---- the list in use ----

    private static volatile SiteList override;
    private static volatile SiteList bundledList;
    private static volatile SiteList loaded;
    private static volatile long loadedMtime = Long.MIN_VALUE, statAt, nextTryAt;

    /** Tests use a list of their own, never the network or the ResearchZosho folder; null goes back to the real one. */
    public static void use(SiteList list) { override = list; }

    /** Tests: forget the list read from disk, and start no download from {@link #current} (a test starts one itself). */
    static void forget() { loaded = null; loadedMtime = Long.MIN_VALUE; statAt = 0; nextTryAt = Long.MAX_VALUE; }

    /** A list of these domains, for a test. */
    public static SiteList of(String origin, String... domains) {
        try { return parse(new StringReader(String.join("\n", domains)), origin); } catch (IOException e) { throw new IllegalStateException(e); }
    }

    /** The list bundled with the program, read once. */
    public static SiteList bundled() {
        SiteList b = bundledList;
        if (b != null) return b;
        synchronized (SiteList.class) {
            if (bundledList != null) return bundledList;
            try (InputStream in = SiteList.class.getResourceAsStream(BUNDLED)) {
                bundledList = in == null ? new SiteList(new long[0], new long[0], "no list (the bundled one is missing from this build)")
                        : parse(new InputStreamReader(new GZIPInputStream(in), StandardCharsets.UTF_8), "the small list bundled with ResearchZosho (Sinfonietta's pornography hosts and ShadowWhisperer's Shock list), until the OISD nsfw list is downloaded");
            } catch (IOException e) {
                bundledList = new SiteList(new long[0], new long[0], "no list (the bundled one could not be read: " + e.getMessage() + ")");
            }
            return bundledList;
        }
    }

    /** Tests point the folder elsewhere; null is {@code <home>/site-list}. */
    static volatile Path dirOverride;

    static Path dir() { Path d = dirOverride; return d != null ? d : Config.home().resolve("site-list"); }
    static Path file() { return dir().resolve("nsfw-domains.txt"); }
    /** Written when a download attempt ends, so that one attempt a day is made, whether it worked or not. */
    static Path lastTry() { return dir().resolve("last-try"); }

    /**
     * Written when an attempt starts and removed when it ends. A command that ends before its download does (a short one, run from a
     * terminal) leaves it behind, and the next attempt waits {@link #STALE_MS} for it instead of a day, so a cut-off attempt is not taken
     * for the day's.
     */
    static Path underWay() { return dir().resolve("downloading"); }

    static final long STALE_MS = 10 * 60_000L;

    /**
     * The list in use: a test's own; else the downloaded one, read again when the file changes; else the bundled one. The first use in an
     * hour looks whether a day has passed since the last download attempt, and if so starts one in the background.
     */
    public static SiteList current() {
        SiteList o = override;
        if (o != null) return o;
        long now = System.currentTimeMillis();
        if (now >= nextTryAt) { nextTryAt = now + 3600_000L; refreshIfDue(now); }
        if (loaded == null || now - statAt > 60_000) {
            statAt = now;
            long m = mtime(file());
            if (loaded == null || m != loadedMtime) {
                SiteList l = null;
                if (m > 0) {
                    try (Reader r = Files.newBufferedReader(file(), StandardCharsets.UTF_8)) {
                        l = parse(r, "the OISD nsfw list, downloaded on " + LocalDate.ofInstant(Instant.ofEpochMilli(m), ZoneId.systemDefault()));
                        if (l.size() < MIN_ENTRIES) l = null;
                    } catch (IOException unreadable) { l = null; }
                }
                loaded = l != null ? l : bundled();
                loadedMtime = m;
            }
        }
        return loaded;
    }

    /** A download: the address in, the body out. The live one is an HTTP GET; a test passes its own. */
    public interface Downloader { byte[] get(String url) throws Exception; }

    static volatile Downloader downloader = SiteList::download;

    /** Whether downloading is on: {@code RESEARCHZOSHO_SITE_LIST=off} keeps to the bundled list. */
    static boolean downloads() {
        String v = Config.get("RESEARCHZOSHO_SITE_LIST");
        return v == null || !(v.equalsIgnoreCase("off") || v.equalsIgnoreCase("false") || v.equals("0"));
    }

    static String sourceUrl() {
        String u = Config.get("RESEARCHZOSHO_SITE_LIST_URL");
        return u == null || u.isBlank() ? SOURCE_URL : u.strip();
    }

    /**
     * When a day has passed since the last attempt (or there was none), writes down this attempt and starts the download in the
     * background; returns the thread, or null when none was due. While no list has arrived yet, an hour is enough: a first download that
     * failed (the machine was offline) is not left for a day with only the small bundled list.
     */
    static synchronized Thread refreshIfDue(long now) {
        if (!downloads()) return null;
        long last = mtime(lastTry());
        long wait = Files.exists(file()) ? DAY_MS : HOUR_MS;
        if (last > 0 && now - last < wait) return null;
        long started = mtime(underWay());
        if (started > 0 && now - started < STALE_MS) return null;   // another process is downloading it now
        try {
            Files.createDirectories(dir());
            Files.writeString(underWay(), Instant.ofEpochMilli(now) + "\n", StandardCharsets.UTF_8);
            Files.setLastModifiedTime(underWay(), FileTime.fromMillis(now));
        } catch (IOException e) {
            return null;   // no folder to keep a list in: the bundled one stays
        }
        Thread t = new Thread(() -> {
            try { refreshNow(); }
            finally {
                // the attempt ended, well or not: the next is due in a day
                try {
                    Files.writeString(lastTry(), Instant.ofEpochMilli(now) + "\n", StandardCharsets.UTF_8);
                    Files.setLastModifiedTime(lastTry(), FileTime.fromMillis(now));
                    Files.deleteIfExists(underWay());
                } catch (IOException ignored) { }
            }
        }, "site-list-download");
        t.setDaemon(true);
        t.start();
        return t;
    }

    /**
     * One download: kept only when it reads as a list ({@link #looksLikeAList}); the file on disk is replaced whole, through a file of
     * this attempt's own, so that two programs downloading at once never write into one file.
     */
    static boolean refreshNow() {
        Path tmp = null;
        try {
            byte[] body = downloader.get(sourceUrl());
            if (body == null) return false;
            String text = new String(body, StandardCharsets.UTF_8);
            SiteList l = parse(new StringReader(text), "download");
            if (!looksLikeAList(text, l.size())) return false;
            Files.createDirectories(dir());
            tmp = Files.createTempFile(dir(), "nsfw-domains-", ".part");
            Files.write(tmp, body);
            try { Files.move(tmp, file(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (IOException noAtomic) { Files.move(tmp, file(), StandardCopyOption.REPLACE_EXISTING); }
            statAt = 0;   // the next use reads the new file
            return true;
        } catch (Exception offline) {
            return false;   // no network, or the server did not answer: the list on disk, or the bundled one, goes on being used
        } finally {
            if (tmp != null) try { Files.deleteIfExists(tmp); } catch (IOException ignored) { }
        }
    }

    /** A line that names a domain: letters, digits, hyphens and underscores in two or more labels (an ASCII form of any name). */
    private static final Pattern DOMAIN = Pattern.compile("[a-z0-9_-]+(?:\\.[a-z0-9_-]+)+");
    private static final Pattern ENTRIES = Pattern.compile("(?im)^[#!]\\s*(?:entries|number of (?:unique )?domains)\\s*:\\s*([0-9][0-9,]*)\\s*$");

    /**
     * Whether a download reads as a list: at least {@link #MIN_ENTRIES} domains, nearly all its lines (98 in a hundred of those that are
     * not comments) naming a domain, and, when its header says how many entries it has, about that many (within five in a hundred). An
     * error page, a list cut short or a file of something else is not taken.
     */
    static boolean looksLikeAList(String text, int size) {
        if (size < MIN_ENTRIES) return false;
        int lines = 0, domains = 0;
        for (String line : text.split("\\R")) {
            String t = line.strip();
            if (t.isEmpty() || t.startsWith("#") || t.startsWith("!")) continue;
            lines++;
            String d = domainOf(line);
            if (d != null && DOMAIN.matcher(IDN.toASCII(d, IDN.ALLOW_UNASSIGNED).toLowerCase(Locale.ROOT)).matches()) domains++;
        }
        if (lines == 0 || domains < lines * 0.98) return false;
        Matcher said = ENTRIES.matcher(text.length() > 4000 ? text.substring(0, 4000) : text);
        if (said.find()) {
            long entries = Long.parseLong(said.group(1).replace(",", ""));
            if (Math.abs(size - entries) > entries * 0.05) return false;
        }
        return true;
    }

    private static final int MAX_DOWNLOAD = 64 * 1024 * 1024;

    static byte[] download(String url) throws Exception {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).followRedirects(HttpClient.Redirect.NORMAL).build();
        HttpResponse<InputStream> r = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(120))
                .header("User-Agent", Fetch.USER_AGENT).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream in = r.body()) {
            if (r.statusCode() != 200) return null;
            byte[] b = in.readNBytes(MAX_DOWNLOAD + 1);
            return b.length > MAX_DOWNLOAD ? null : b;
        }
    }

    private static long mtime(Path p) {
        try { return Files.exists(p) ? Files.getLastModifiedTime(p).toMillis() : 0; } catch (IOException e) { return 0; }
    }
}
