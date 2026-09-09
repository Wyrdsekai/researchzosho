package org.researchzosho.librarian;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * The library's own retrieval instrument — the "measured miss rate" the architecture gates
 * every retrieval investment on (embeddings, chunking, rerankers), and what I-0005 (the
 * organization survey) says the field measures: TOP-K RETRIEVAL FAILURE RATE on
 * agent-generated queries (Anthropic's contextual-retrieval protocol; ToolRet's finding that
 * recall@k on agent queries is what correlates with task success, not MTEB).
 *
 * <p>Queries and gold come from the shelf itself, no labeling: every fan investigation carries
 * its SUB-QUESTIONs (agent-generated, by construction) and links the findings it produced. A
 * sub-question is a hit when any finding of its investigation lands in the top-k. Run the same
 * set BM25-only and hybrid and the difference is the embedder's measured worth — on THIS corpus,
 * not a benchmark.
 */
public final class RetrievalBench {

    private RetrievalBench() { }

    public record Case(String investigationId, String query, List<String> gold) { }
    /**
     * {@code hits} = a gold finding itself in the top-k (strict). {@code viaArticle} = the strict
     * misses where a shelf ARTICLE that cites a gold finding is in the top-k instead — an agent
     * reading that article reaches the finding, but the strict count calls it a miss. Reported
     * separately so a growing article shelf cannot masquerade as a retrieval regression (it did:
     * 2026-09-03, two new articles moved top-3 from 57.1% to 82.1% "miss").
     */
    public record Result(int cases, int hits, int viaArticle, List<String> misses) {
        public double failureRate() { return cases == 0 ? 0 : 1.0 - (double) hits / cases; }
        public double failureRateLenient() { return cases == 0 ? 0 : 1.0 - (double) (hits + viaArticle) / cases; }
    }

    /** Sub-questions of every investigation that produced findings. */
    public static List<Case> cases(LibraryStore store) throws IOException {
        List<Case> out = new ArrayList<>();
        if (!Files.isDirectory(store.investigationsDir())) return out;
        try (var files = Files.list(store.investigationsDir())) {
            for (var p : files.sorted().toList()) {
                if (!p.toString().endsWith(".md")) continue;
                Investigation inv;
                try { inv = Investigation.parse(Files.readString(p, StandardCharsets.UTF_8)); }
                catch (Exception e) { continue; }
                if (inv.findings().isEmpty()) continue;
                for (String line : inv.body().split("\\r?\\n")) {
                    if (!line.startsWith("SUB-QUESTION:")) continue;
                    String q = line.substring("SUB-QUESTION:".length()).strip();
                    if (q.length() < 12 || q.startsWith("(")) continue;   // "(timed out)" is not a query
                    out.add(new Case(inv.id(), q, inv.findings()));
                }
                // the top-level question is a case too
                out.add(new Case(inv.id(), inv.title(), inv.findings()));
            }
        }
        return out;
    }

    public static Result run(LibrarianIndex index, List<Case> cases, int k) throws IOException {
        return run(index, cases, k, false);
    }

    /** {@code strict} = the DESK's search (ask/established/push): floored on relevance. */
    public static Result run(LibrarianIndex index, List<Case> cases, int k, boolean strict) throws IOException {
        int hits = 0, via = 0;
        List<String> misses = new ArrayList<>();
        var cites = articleCitations(index.store());
        for (Case c : cases) {
            var top = strict ? index.searchStrict(c.query(), k, null, null) : index.search(c.query(), k);
            boolean hit = top.stream().anyMatch(h -> c.gold().contains(h.id()));
            if (hit) { hits++; continue; }
            boolean viaArticle = top.stream().anyMatch(h -> "article".equals(h.kind())
                    && cites.getOrDefault(h.id(), java.util.Set.of()).stream().anyMatch(c.gold()::contains));
            if (viaArticle) via++;
            // say what FILLED the top-k, so a miss explains itself
            StringBuilder filled = new StringBuilder();
            for (var h : top) filled.append(' ').append(h.kind().charAt(0)).append(':').append(h.id());
            misses.add(c.investigationId() + " :: " + Acquisitions.compress(c.query(), 80)
                    + (viaArticle ? "  [via article]" : "") + "  top:" + filled);
        }
        return new Result(cases.size(), hits, via, misses);
    }

    /** article id → the finding ids its body cites. */
    static java.util.Map<String, java.util.Set<String>> articleCitations(LibraryStore store) throws IOException {
        var out = new java.util.HashMap<String, java.util.Set<String>>();
        if (!Files.isDirectory(store.articlesDir())) return out;
        var id = java.util.regex.Pattern.compile("F-\\d+-[a-z0-9-]+");
        try (var files = Files.list(store.articlesDir())) {
            for (var p : files.toList()) {
                String name = p.getFileName().toString();
                if (!name.endsWith(".md")) continue;
                var set = new java.util.HashSet<String>();
                var m = id.matcher(Files.readString(p, StandardCharsets.UTF_8));
                while (m.find()) set.add(m.group());
                out.put(name.substring(0, name.length() - 3), set);
            }
        }
        return out;
    }

    /** CLI: `librarian bench [k]` — BM25-only vs hybrid on the shelf's own agent queries. */
    static void cli(LibraryStore store, String[] args) throws IOException {
        int k = args.length > 2 && args[2].matches("\\d+") ? Integer.parseInt(args[2]) : 10;
        List<Case> cases = cases(store);
        if (cases.isEmpty()) { System.out.println("no cases — needs investigations that produced findings"); return; }
        Result sparse = run(new LibrarianIndex(store, Embeddings.none()), cases, k);
        Embeddings.Embedder emb = Embeddings.configured();
        System.out.println(cases.size() + " agent queries from " + cases.stream().map(Case::investigationId).distinct().count()
                + " investigations, top-" + k + " failure rate:");
        System.out.printf("  BM25 only : %5.1f%%  (%d/%d hit; %5.1f%% counting an article that cites the finding)%n",
                100 * sparse.failureRate(), sparse.hits(), sparse.cases(), 100 * sparse.failureRateLenient());
        Result sparseStrict = run(new LibrarianIndex(store, Embeddings.none()), cases, k, true);
        System.out.printf("  BM25 desk : %5.1f%%  (%d/%d hit)  — the strict search ask/established answer from%n",
                100 * sparseStrict.failureRate(), sparseStrict.hits(), sparseStrict.cases());
        if (!"none".equals(emb.modelId())) {
            Result hybrid = run(new LibrarianIndex(store, emb).reranker(Reranker.none()), cases, k);
            System.out.printf("  hybrid    : %5.1f%%  (%d/%d hit; %5.1f%% via article)  embedder %s%n",
                    100 * hybrid.failureRate(), hybrid.hits(), hybrid.cases(), 100 * hybrid.failureRateLenient(), emb.modelId());
            Result hybridStrict = run(new LibrarianIndex(store, emb), cases, k, true);
            System.out.printf("  hybrid desk: %5.1f%%  (%d/%d hit)%n",
                    100 * hybridStrict.failureRate(), hybridStrict.hits(), hybridStrict.cases());
            Reranker.Scorer rr = Reranker.configured();
            if (!"none".equals(rr.modelId())) {
                Result reranked = run(new LibrarianIndex(store, emb).reranker(rr), cases, k);
                System.out.printf("  +reranker : %5.1f%%  (%d/%d hit)  %s%n", 100 * reranked.failureRate(), reranked.hits(), reranked.cases(), rr.modelId());
                hybrid = reranked;
            }
            for (String m : hybrid.misses()) System.out.println("    miss: " + m);
        } else {
            System.out.println("  (no embedder configured — set RESEARCHZOSHO_EMBED to measure hybrid)");
            for (String m : sparse.misses()) System.out.println("    miss: " + m);
        }
    }
}
