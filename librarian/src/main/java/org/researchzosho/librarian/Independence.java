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
        List<String> uniq = new ArrayList<>(new java.util.LinkedHashSet<>(locators));
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
                boolean citesOther = cites(texts.get(i), uniq.get(j)) || cites(texts.get(j), uniq.get(i));
                if (sameWork || sameTitle || citesOther || (!shingles.get(i).isEmpty() && jaccard(shingles.get(i), shingles.get(j)) >= SAME)) union(parent, i, j);
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

    /** How many independent clusters {@code locators} span. */
    public static int independent(Map<String, Integer> clusters) {
        return new HashSet<>(clusters.values()).size();
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
