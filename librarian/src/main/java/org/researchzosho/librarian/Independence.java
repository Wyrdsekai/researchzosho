package org.researchzosho.librarian;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    /** locator → cluster number (1-based, in first-seen order). Locators with no capture are their own cluster. */
    public static Map<String, Integer> clusters(LibraryStore store, List<String> locators) {
        // one address in two spellings (tracking parameters, a trailing slash, http/https) is one locator
        List<String> uniq = new ArrayList<>();
        Map<String, String> byCanon = new LinkedHashMap<>();
        for (String loc : locators) {
            String canon = loc != null && loc.startsWith("http") ? org.researchzosho.tools.Fetch.canonical(loc) : loc;
            if (canon == null || byCanon.containsKey(canon)) continue;
            byCanon.put(canon, loc); uniq.add(loc);
        }
        List<Set<String>> shingles = new ArrayList<>();
        List<String> titles = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        for (String loc : uniq) {
            String text = "", title = "";
            try {
                Path p = RawCapture.find(store, loc);
                if (p != null) { String[] r = RawCapture.read(p); text = r[2]; title = r[1]; }
            } catch (IOException ignored) {
                // no capture: stands alone
            }
            shingles.add(shingle(text));
            titles.add(Vocabulary.norm(title));
            texts.add(text);
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
        Map<String, Integer> out = new LinkedHashMap<>();
        for (int i = 0; i < uniq.size(); i++) {
            int root = find(parent, i);
            number.putIfAbsent(root, number.size() + 1);
            out.put(uniq.get(i), number.get(root));
        }
        return out;
    }

    /** Whether a captured text cites a locator: the URL itself, or its DOI / arXiv id, appears in the text. */
    static boolean cites(String text, String locator) {
        if (text == null || text.isEmpty() || locator == null || !locator.contains("://")) return false;
        String t = text.toLowerCase(java.util.Locale.ROOT);
        String l = locator.toLowerCase(java.util.Locale.ROOT).replaceFirst("^https?://(www\\.)?", "").replaceAll("[/#?]+$", "");
        if (l.length() > 12 && t.contains(l)) return true;
        String id = Citations.identify(locator);
        if (id != null) {
            String bare = id.substring(id.indexOf(':') + 1).toLowerCase(java.util.Locale.ROOT);
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
        List<String> uniq = new ArrayList<>(new java.util.LinkedHashSet<>(locators));
        Map<String, String> texts = new HashMap<>();
        for (String loc : uniq) { try { Path p = RawCapture.find(store, loc); texts.put(loc, p == null ? "" : RawCapture.read(p)[2]); } catch (IOException e) { texts.put(loc, ""); } }
        for (String a : uniq) for (String b : uniq) {
            if (a.equals(b) || texts.get(a).isEmpty()) continue;
            String ia = Citations.identify(a), ib = Citations.identify(b);
            if (ia != null && ia.equals(ib)) continue;   // the same work in two spellings is a copy, not a citation
            if (cites(texts.get(a), b)) { out.put(a, b); break; }
        }
        return out;
    }

    /**
     * How many independent voices {@code locators} are: distinct texts, minus every text that cites another of them
     * (a derivative adds nothing to what it cites). Never below one when there is a locator at all.
     */
    public static int independent(LibraryStore store, List<String> locators) {
        if (locators.isEmpty()) return 0;
        Map<String, Integer> clusters = clusters(store, locators);
        Map<String, String> deriv = derivatives(store, locators);
        java.util.Set<Integer> voices = new HashSet<>(clusters.values());
        for (Map.Entry<String, String> e : deriv.entrySet()) {
            Integer from = clusters.get(e.getKey()), to = clusters.get(e.getValue());
            if (from != null && to != null && !from.equals(to)) voices.remove(from);
        }
        return Math.max(1, voices.size());
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
