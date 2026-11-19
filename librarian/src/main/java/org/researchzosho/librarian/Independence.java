package org.researchzosho.librarian;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import java.util.LinkedHashSet;
import java.util.Locale;
import org.researchzosho.tools.Fetch;
/**
 * Source independence: fifty copies of one press release argue with the weight of one source. Two
 * captured documents whose text overlaps heavily (word-shingle Jaccard) or whose titles are the same
 * are one cluster; a report counts clusters, not URLs, when it says how many sources agree. Mechanical
 * — no model decides what is a copy of what. The genealogy kits call this the "multiple sources
 * illusion"; hyperresearch calls it an independence audit; a librarian calls it a derivative record.
 */
public final class Independence {

    static final int SHINGLE = 8;
    static final double SAME = 0.4;

    private Independence() { }

    /**
     * locator → cluster number (1-based, in first-seen order). Locators with no capture are their own cluster. Lines of one file of a
     * repository the library cloned are that file, read from disk, and so is a forge's page of it ({@link CodeTool#clonedFileOf}): a
     * claim on one file of the code is on one source, however many of its lines or pages a run cites.
     */
    public static Map<String, Integer> clusters(LibraryStore store, List<String> locators) {
        // one address in two spellings (tracking parameters, a trailing slash, http/https) is one locator, and every spelling keeps its number
        Map<String, String> keyOf = new LinkedHashMap<>();
        Map<String, String> firstOf = new LinkedHashMap<>();
        for (String loc : locators) {
            String key = key(store, loc);
            if (key == null) continue;
            keyOf.put(loc, key);
            firstOf.putIfAbsent(key, loc);
        }
        List<String> keys = new ArrayList<>(firstOf.keySet()), uniq = new ArrayList<>(firstOf.values());
        List<Set<String>> shingles = new ArrayList<>();
        List<String> titles = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        for (int i = 0; i < uniq.size(); i++) {
            String[] read = read(store, keys.get(i), uniq.get(i));   // no capture: stands alone
            shingles.add(shingle(read[0]));
            titles.add(Vocabulary.norm(read[1]));
            texts.add(read[0]);
        }
        int[] parent = new int[uniq.size()];
        for (int i = 0; i < parent.length; i++) parent[i] = i;
        for (int i = 0; i < uniq.size(); i++) {
            for (int j = i + 1; j < uniq.size(); j++) {
                boolean sameTitle = !titles.get(i).isEmpty() && titles.get(i).length() > 20 && titles.get(i).equals(titles.get(j));
                String idI = Citations.identify(uniq.get(i)), idJ = Citations.identify(uniq.get(j));
                boolean sameWork = idI != null && idI.equals(idJ);
                // one source that cites the other is not a second, independent one: a report that links the paper it
                // summarises stands on that paper (the library's own research, I-0020: independence means different
                // editorial chains, "not citing each other")
                // a wire story keeps its opening across outlets that retitle it: the same first paragraphs are the same text
                // …but only when the bodies overlap at all: every GitHub page opens with the same navigation, and 21 of them fused (2026-09-12)
                boolean sameHead = sameHead(texts.get(i), texts.get(j)) && bodiesAfterHeadOverlap(texts.get(i), texts.get(j));
                // citing is NOT a copy: a survey that links twenty papers is not the same text as any of them, and union-find made
                // that transitive — 32 of 38 references in one write-up read "same text as [1]" (2026-09-12). Citing is a one-way
                // dependency, counted in {@link #independent(LibraryStore, List)}.
                if (sameWork || sameTitle || sameHead || (!shingles.get(i).isEmpty() && jaccard(shingles.get(i), shingles.get(j)) >= SAME)) union(parent, i, j);
            }
        }
        Map<Integer, Integer> number = new HashMap<>();
        Map<String, Integer> byKey = new HashMap<>();
        for (int i = 0; i < uniq.size(); i++) {
            int root = find(parent, i);
            number.putIfAbsent(root, number.size() + 1);
            byKey.put(keys.get(i), number.get(root));
        }
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : keyOf.entrySet()) out.put(e.getKey(), byKey.get(e.getValue()));
        return out;
    }

    /** What a locator is compared as: a cloned repository's file for its lines and for a forge's page of it, else the address in one spelling. */
    static String key(LibraryStore store, String loc) {
        if (loc == null) return null;
        String file = CodeTool.fileOf(loc);
        if (file == null && loc.startsWith("http")) file = CodeTool.clonedFileOf(store, loc);
        if (file != null) return file;
        return loc.startsWith("http") ? Fetch.canonical(loc) : loc;
    }

    /** [text, title] of what a locator's key names: a cloned repository's file as it is on disk, anything else as it was captured; empty when neither is there. */
    static String[] read(LibraryStore store, String key, String loc) {
        String code = key != null && key.startsWith("raw/repos/") ? CodeTool.textOf(store, key) : null;
        if (code != null) return new String[]{code, ""};
        try {
            Path p = RawCapture.find(store, loc);
            if (p != null) { String[] r = RawCapture.read(p); return new String[]{r[2], r[1]}; }
        } catch (IOException ignored) {
            // no capture
        }
        return new String[]{"", ""};
    }

    /** Whether a captured text cites a locator: the URL itself, or its DOI / arXiv id, appears in the text. */
    static boolean cites(String text, String locator) {
        if (text == null || text.isEmpty() || locator == null || !locator.contains("://")) return false;
        String t = text.toLowerCase(Locale.ROOT);
        String l = locator.toLowerCase(Locale.ROOT).replaceFirst("^https?://(www\\.)?", "").replaceAll("[/#?]+$", "");
        if (l.length() > 12 && t.contains(l)) return true;
        String id = Citations.identify(locator);
        if (id != null) {
            String bare = id.substring(id.indexOf(':') + 1).toLowerCase(Locale.ROOT);
            return bare.length() > 6 && t.contains(bare);
        }
        return false;
    }

    /** The first {@link #HEAD_CHARS} of two texts, shingled: one wire story under two headlines (hyperresearch keys its wire check on the body head). */
    static final int HEAD_CHARS = 700;
    /** With the same head, this much shared body is a wire story; a shared site chrome over different bodies is not. */
    static final double SAME_BODY_WITH_HEAD = 0.12;
    static final double SAME_HEAD = 0.5;
    /** The texts past their heads, shingled: a wire story shares its body too; two pages under one site chrome do not. */
    static boolean bodiesAfterHeadOverlap(String a, String b) {
        if (a.length() <= HEAD_CHARS + 200 || b.length() <= HEAD_CHARS + 200) return false;
        Set<String> ba = shingle(a.substring(HEAD_CHARS)), bb = shingle(b.substring(HEAD_CHARS));
        return !ba.isEmpty() && jaccard(ba, bb) >= SAME_BODY_WITH_HEAD;
    }

    static boolean sameHead(String a, String b) {
        if (a == null || b == null || a.length() < 300 || b.length() < 300) return false;
        Set<String> ha = shingle(a.substring(0, Math.min(a.length(), HEAD_CHARS))), hb = shingle(b.substring(0, Math.min(b.length(), HEAD_CHARS)));
        return !ha.isEmpty() && jaccard(ha, hb) >= SAME_HEAD;
    }

    /** How many distinct texts {@code locators} span (copies of one text count once). */
    public static int independent(Map<String, Integer> clusters) {
        return new HashSet<>(clusters.values()).size();
    }

    /**
     * locator → the locator it cites (the URL, DOI or arXiv id of another of the {@code locators} appears in its text), for
     * the first such it cites; a source that stands on another is not a second, independent voice for what that other says
     * (the library's own research, I-0020: independence means different editorial chains, "not citing each other").
     */
    public static Map<String, String> derivatives(LibraryStore store, List<String> locators) {
        Map<String, String> out = new LinkedHashMap<>();
        List<String> uniq = new ArrayList<>(new LinkedHashSet<>(locators));
        Map<String, String> texts = new HashMap<>();
        for (String loc : uniq) texts.put(loc, read(store, key(store, loc), loc)[0]);
        for (String a : uniq) for (String b : uniq) {
            if (a.equals(b) || texts.get(a).isEmpty()) continue;
            String ia = Citations.identify(a), ib = Citations.identify(b);
            if (ia != null && ia.equals(ib)) continue;   // the same work in two spellings is a copy, not a citation
            if (cites(texts.get(a), b)) { out.put(a, b); break; }
        }
        return out;
    }

    /**
     * How many independent voices {@code locators} are ({@link #voices}): distinct texts, a text that cites another counted with it (a
     * derivative adds nothing to what it cites), and the pages of one publisher counted once. Never below one when there is a locator at all.
     */
    public static int independent(LibraryStore store, List<String> locators) {
        if (locators.isEmpty()) return 0;
        return Math.max(1, new HashSet<>(voices(store, locators).values()).size());
    }

    /**
     * locator → voice number (1-based, in first-seen order): the text clusters ({@link #clusters}), joined with what each cites
     * ({@link #derivatives}) and with the other pages of the same publisher ({@link #publisher}). Two voices are two sources a reader can
     * count: a different publisher, and not a copy, a syndication or a summary of the other's text (round E, the owner's rule for a claim
     * that rests on one source).
     */
    public static Map<String, Integer> voices(LibraryStore store, List<String> locators) {
        Map<String, Integer> clusters = clusters(store, locators);
        Map<String, String> deriv = derivatives(store, locators);
        int most = 0;
        for (int c : clusters.values()) most = Math.max(most, c);
        int[] parent = new int[most + 1];
        for (int i = 0; i < parent.length; i++) parent[i] = i;
        for (Map.Entry<String, String> e : deriv.entrySet()) {
            Integer from = clusters.get(e.getKey()), to = clusters.get(e.getValue());
            if (from != null && to != null) union(parent, from, to);
        }
        Map<String, Integer> firstOfPublisher = new HashMap<>();
        for (Map.Entry<String, Integer> e : clusters.entrySet()) {
            String p = publisher(e.getKey());
            if (p == null) continue;
            Integer seen = firstOfPublisher.putIfAbsent(p, e.getValue());
            if (seen != null) union(parent, seen, e.getValue());
        }
        Map<Integer, Integer> number = new HashMap<>();
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : clusters.entrySet()) {
            int root = find(parent, e.getValue());
            number.putIfAbsent(root, number.size() + 1);
            out.put(e.getKey(), number.get(root));
        }
        return out;
    }

    /** Sites where many people publish, each under a path of their own: the publisher is the site and the first part of the path (github.com/owner). */
    static final Set<String> BY_PATH = Set.of("github.com", "gitlab.com", "codeberg.org", "bitbucket.org", "sourceforge.net", "huggingface.co", "medium.com",
            "twitter.com", "x.com", "facebook.com", "instagram.com", "linkedin.com", "tiktok.com", "threads.net", "bsky.app", "note.com", "ameblo.jp", "qiita.com", "zenn.dev");
    /** Sites that give each person a host of their own: the publisher is the whole host (name.substack.com). */
    static final Set<String> BY_HOST = Set.of("substack.com", "blogspot.com", "wordpress.com", "github.io", "gitlab.io", "tumblr.com", "neocities.org", "netlify.app",
            "vercel.app", "pages.dev", "wixsite.com", "weebly.com", "livejournal.com", "hatenablog.com", "hatenablog.jp", "hateblo.jp", "fc2.com", "medium.com");
    /**
     * Sites where every address is somebody else's work: a question site, a video site, a web archive, a server of papers by their
     * identifiers. No publisher is read off such an address; the texts decide.
     */
    static final Set<String> MANY = Set.of("reddit.com", "stackexchange.com", "stackoverflow.com", "superuser.com", "serverfault.com", "askubuntu.com", "quora.com",
            "youtube.com", "youtu.be", "vimeo.com", "archive.org", "archive.ph", "archive.today", "arxiv.org", "doi.org", "ncbi.nlm.nih.gov", "biorxiv.org", "medrxiv.org",
            "ssrn.com", "researchgate.net", "academia.edu", "semanticscholar.org", "books.google.com", "scholar.google.com", "news.ycombinator.com", "zenodo.org", "osf.io",
            "figshare.com", "jstage.jst.go.jp", "ci.nii.ac.jp", "europepmc.org", "hal.science", "openreview.net", "aclanthology.org", "dl.acm.org", "ieeexplore.ieee.org",
            "link.springer.com", "sciencedirect.com", "onlinelibrary.wiley.com", "tandfonline.com", "jstor.org", "pubmed.ncbi.nlm.nih.gov", "podcasts.apple.com", "spotify.com");
    /** A country's second level, under which a registered name is three labels: co.uk, ac.jp, com.au. */
    static final Set<String> SECOND_LEVEL = Set.of("co", "or", "ne", "ac", "go", "gr", "ed", "lg", "ad", "com", "net", "org", "edu", "gov", "gob", "govt", "mil",
            "ltd", "plc", "me", "nhs", "police", "sch", "nic", "res", "gv", "info", "biz");

    /**
     * Who published a source, read off its address: the site's registered name (its last two labels, three under a country's second
     * level as in co.uk or ac.jp), with "www" and the like below it the same publisher; the account on a site where many publish under a
     * path (github.com/owner); the whole host on a site that gives each person a host. An address by its port and number (a machine,
     * not a name) is that address and port. Null when the locator names no publisher the library can tell: a paper or a record by its
     * identifier, a site where every address is someone else's ({@link #MANY}), a file, or anything that is not an http address; then
     * only the texts decide.
     */
    public static String publisher(String locator) {
        if (locator == null || !locator.matches("(?i)^https?://.*")) return null;
        if (Citations.identify(locator) != null) return null;   // a work by its identifier is its authors', wherever it is served
        URI u;
        try { u = URI.create(locator.strip().replace(" ", "%20")); } catch (IllegalArgumentException e) { return null; }
        String host = u.getHost();
        if (host == null || host.isBlank()) return null;
        host = host.toLowerCase(Locale.ROOT);
        if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        if (host.matches("[0-9.]+") || host.contains(":") || host.equals("localhost")) return host + ":" + u.getPort();   // a machine by its number: the address and port
        String[] labels = host.split("\\.");
        if (labels.length < 2) return host;
        int keep = labels.length >= 3 && labels[labels.length - 1].length() == 2 && SECOND_LEVEL.contains(labels[labels.length - 2]) ? 3 : 2;
        String site = String.join(".", Arrays.copyOfRange(labels, labels.length - keep, labels.length));
        for (String many : MANY) if (host.equals(many) || host.endsWith("." + many)) return null;
        if (BY_HOST.contains(site) && !host.equals(site) && !host.equals("www." + site)) return host;
        if (BY_PATH.contains(site)) {
            String path = u.getRawPath() == null ? "" : u.getRawPath();
            String first = path.replaceFirst("^/+", "").split("/", 2)[0].toLowerCase(Locale.ROOT);
            return first.isEmpty() ? site : site + "/" + first;
        }
        return site;
    }

    /** A publisher as a reader reads it: the host without "www.", or the account on a site where many publish; null when {@link #publisher} names none. */
    public static String publisherSaid(String locator) {
        String p = publisher(locator);
        if (p == null) return null;
        if (p.contains("/") || p.contains(":")) return p.replaceFirst(":-1$", "");
        try { String h = URI.create(locator.strip().replace(" ", "%20")).getHost().toLowerCase(Locale.ROOT); return h.startsWith("www.") ? h.substring(4) : h; }
        catch (RuntimeException e) { return p; }
    }

    static Set<String> shingle(String text) {
        Set<String> out = new HashSet<>();
        if (text == null) return out;
        String[] w = Vocabulary.norm(text).replaceAll("[^\\p{L}\\p{N} ]", " ").split("\\s+");
        for (int i = 0; i + SHINGLE <= w.length && out.size() < 20_000; i++) {
            StringBuilder sb = new StringBuilder();
            for (int k = 0; k < SHINGLE; k++) sb.append(w[i + k]).append(' ');
            out.add(sb.toString());
        }
        return out;
    }

    static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        int inter = 0;
        for (String s : a) if (b.contains(s)) inter++;
        return (double) inter / (a.size() + b.size() - inter);
    }

    private static int find(int[] p, int i) { while (p[i] != i) { p[i] = p[p[i]]; i = p[i]; } return i; }
    private static void union(int[] p, int a, int b) { p[find(p, a)] = find(p, b); }
}
