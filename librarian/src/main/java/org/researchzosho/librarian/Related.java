package org.researchzosho.librarian;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Neighbours by SUBJECT — the vocabulary doing retrieval work (I-0005's library-science worker:
 * subject headings are discrete retrieval features; the co-occurrence of subjects across
 * findings is the first real edge set of the library's link graph, the seed the path-sampling
 * frontier will walk later). Purely mechanical: Jaccard over subject sets, shared sources as
 * the tie-break. A keigo-dissolution finding and an honorific-retention finding share
 * {@code translation--strategy} and {@code japanese--keigo} even when their words barely
 * overlap — the neighbour BM25 misses and the dispute crew needs to see.
 */
public final class Related {

    private Related() { }

    public record Neighbour(String id, String title, double score, List<String> shared) { }

    /** Top-{@code k} findings related to {@code f}, by subject overlap (then shared sources). */
    public static List<Neighbour> of(LibraryStore store, Finding f, int k) {
        if (f.subjects().isEmpty()) return List.of();
        Set<String> mine = new HashSet<>(f.subjects());
        Set<String> mySources = new HashSet<>();
        for (var s : f.sources()) mySources.add(s.locator());
        List<Neighbour> out = new ArrayList<>();
        for (Finding o : store.scanFindings().findings()) {
            if (o.id().equals(f.id()) || o.state() == Finding.State.retired || o.subjects().isEmpty()) continue;
            List<String> shared = new ArrayList<>();
            for (String s : o.subjects()) if (mine.contains(s)) shared.add(s);
            if (shared.isEmpty()) continue;
            Set<String> union = new HashSet<>(mine); union.addAll(o.subjects());
            double j = (double) shared.size() / union.size();
            double src = 0;
            for (var s : o.sources()) if (mySources.contains(s.locator())) src += 0.05;
            out.add(new Neighbour(o.id(), o.title(), j + src, shared));
        }
        out.sort((a, b) -> Double.compare(b.score(), a.score()));
        return out.size() > k ? out.subList(0, k) : out;
    }

    /** Subject → (co-occurring subject → count), from every non-retired finding. The edge list. */
    public static Map<String, Map<String, Integer>> coOccurrence(LibraryStore store) {
        Map<String, Map<String, Integer>> edges = new TreeMap<>();
        for (Finding f : store.scanFindings().findings()) {
            if (f.state() == Finding.State.retired) continue;
            for (String a : f.subjects()) {
                edges.computeIfAbsent(a, x -> new TreeMap<>());
                for (String b : f.subjects()) if (!a.equals(b)) edges.get(a).merge(b, 1, Integer::sum);
            }
        }
        return edges;
    }

    /** Subject → number of findings carrying it. */
    public static Map<String, Integer> counts(LibraryStore store) {
        Map<String, Integer> c = new LinkedHashMap<>();
        for (String s : Cataloger.vocabulary(store).keySet()) c.put(s, 0);
        for (Finding f : store.scanFindings().findings()) {
            if (f.state() == Finding.State.retired) continue;
            for (String s : f.subjects()) c.merge(s, 1, Integer::sum);
        }
        return c;
    }
}
