package org.researchzosho.librarian;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.cjk.CJKAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.search.KnnFloatVectorQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The library's search index — Lucene BM25 over the corpus, IN from v1 (not an escalation):
 * at the operator's research rate the corpus reaches thousands of findings within months, past where
 * a pushed INDEX.md or a grep stays honest. Derived and rebuildable; the markdown is always
 * the source of truth (`.index/` can be deleted at any time and rebuilt from the shelves).
 *
 * <p>Analyzer is {@link CJKAnalyzer}: JA sources are first-class in this library, and
 * StandardAnalyzer mangles CJK text into near-useless unigrams — the analyzer choice is
 * deliberate, not defaulted (the architecture notes). CJKAnalyzer bigrams CJK runs and
 * tokenizes Latin normally, so mixed EN/JA entries search from both sides.
 *
 * <p>Writers are opened per operation and closed immediately: write rates here are human-scale
 * (findings per day, not per millisecond), and a short-lived writer means no daemon holds
 * {@code write.lock} against a rebuild — the stale-write.lock trap is already on record for
 * the ocean index.
 */
public final class LibrarianIndex {

    private static final String F_ID = "id";        // StringField — exact, stored
    private static final String F_KIND = "kind";    // finding | investigation | article
    private static final String F_STATE = "state";  // StringField — filterable, stored
    private static final String F_TITLE = "title";  // stored for display
    private static final String F_TEXT = "text";    // analyzed: title + subjects + body
    private static final String F_SUBJECT = "subject"; // StringField, multi-valued — exact filter/facet
    private static final String F_VEC = "vec";      // KnnFloatVectorField — the dense half (HNSW)
    private static final String F_PARENT = "parent"; // chunk → the raw file it belongs to
    private static final String F_SNIPPET = "snippet"; // stored chunk text, for the desk
    private static final String F_COLLECTION = "collection"; // StringField — a raw capture's corpus, exact filter

    /** Chunk budget (tokens by script) — the survey's sentence/structure chunks, no overlap, well
     *  under the ~2.5k-token "context cliff" (arXiv 2601.14123). */
    static final int CHUNK_TOKENS = 600;

    /** RRF weight by kind: the reviewed atomic unit outranks a long raw capture at equal rank. */
    private static double kindWeight(String kind) {
        // RRF discards score magnitudes, so a weight is worth "ranks": 1/(60+r) shifts ~1.6% per
        // rank. 0.92 on raw = a raw hit must lead a finding by ~5 ranks to outrank it — the
        // reviewed unit's prior over unreviewed material, measured by the bench, not a guess.
        return switch (kind == null ? "" : kind) {
            case "finding", "investigation", "article" -> 1.0;
            default -> 0.92;   // raw / chunk
        };
    }

    private final LibraryStore store;

    LibraryStore store() { return store; }
    private final Analyzer analyzer = new CJKAnalyzer();
    private final Embeddings.Embedder embedder;
    private Reranker.Scorer reranker = Reranker.configured();

    /** Override the reranker (tests; the bench's arms). */
    public LibrarianIndex reranker(Reranker.Scorer r) { this.reranker = r == null ? Reranker.none() : r; return this; }

    /** Cosine floor for a dense hit to count in plain search. RESEARCHZOSHO_EMBED_MIN overrides (per embedder). */
    static final double MIN_COSINE = Double.parseDouble(
            org.researchzosho.Config.get("RESEARCHZOSHO_EMBED_MIN", "0.47"));

    /**
     * The DESK's cosine floor (strict search: ask, established, the prompt push). Measured on the
     * live index 2026-09-03 with Qwen3-Embedding-0.6B AFTER chunking + generated contexts (which
     * raised every English query's baseline): junk questions top out at 0.46–0.52 ("zebra
     * crossings on the moon in 1740" → DeepSearchQA chunks at 0.52), while the weakest REAL
     * question's gold sits at 0.63 (JA claim → EN finding) and typical ones at 0.68–0.87. 0.58
     * splits them with margin on both sides. It is per embedder AND per index state — the old
     * 0.47 was right before chunking and wrong after. Re-run `librarian probe` on junk and gold
     * whenever the embedder or the chunking changes. RESEARCHZOSHO_EMBED_MIN_STRICT overrides.
     */
    static final double MIN_COSINE_STRICT = Double.parseDouble(
            org.researchzosho.Config.get("RESEARCHZOSHO_EMBED_MIN_STRICT", "0.58"));

    public LibrarianIndex(LibraryStore store) {
        this(store, Embeddings.configured());
    }

    public LibrarianIndex(LibraryStore store, Embeddings.Embedder embedder) {
        this.store = store;
        this.embedder = embedder == null ? Embeddings.none() : embedder;
    }

    /**
     * Open a writer, WAITING for the lock: the abstracting crew and a rebuild ran in two JVMs at
     * once (2026-09-03) and the second died on "Lock held by another program". A rebuild holds
     * the lock for minutes; a crew waits its turn instead of failing the article it just wrote.
     */
    private static IndexWriter openWriter(Directory dir, Analyzer analyzer) throws IOException {
        long deadline = System.currentTimeMillis() + WRITER_LOCK_WAIT_MS;
        while (true) {
            try {
                return new IndexWriter(dir, new IndexWriterConfig(analyzer));
            } catch (org.apache.lucene.store.LockObtainFailedException e) {
                if (System.currentTimeMillis() > deadline) throw e;
                try { Thread.sleep(1000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw e; }
            }
        }
    }

    /** How long a writer waits for another process's lock before giving up (a rebuild ≈ 10 min). */
    static long WRITER_LOCK_WAIT_MS = 15 * 60 * 1000L;

    /**
     * The instrument behind a puzzling answer: each arm's raw view of a query — the sparse
     * top-k with BM25 scores (plain and strict), the dense top-k with cosines and whether each
     * clears the floor. `librarian probe <query>` prints it. Built 2026-09-03 when the desk
     * returned DeepSearchQA for "zebra crossings on the moon in 1740" and a by-hand cosine
     * said it could not have.
     */
    public String explain(String query, int k) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (Directory dir = FSDirectory.open(store.luceneDir())) {
            if (!DirectoryReader.indexExists(dir)) return "(no index)";
            try (DirectoryReader r = DirectoryReader.open(dir)) {
                IndexSearcher searcher = new IndexSearcher(r);
                StoredFields stored = searcher.storedFields();
                Query plain = new QueryParser(F_TEXT, analyzer).parse(QueryParser.escape(query));
                sb.append("query: ").append(query).append("\nparsed: ").append(plain).append("\nstrict: ").append(atLeastHalf(plain)).append('\n');
                for (String arm : new String[]{"sparse", "sparse-strict"}) {
                    Query q = arm.equals("sparse") ? plain : atLeastHalf(plain);
                    ScoreDoc[] hits = searcher.search(q, k).scoreDocs;
                    sb.append(arm).append(" (").append(hits.length).append("):\n");
                    for (ScoreDoc sd : hits) {
                        Document d = stored.document(sd.doc);
                        sb.append(String.format("  %7.3f  %-6s %s%n", sd.score, d.get(F_KIND), "chunk".equals(d.get(F_KIND)) ? d.get(F_PARENT) + " #chunk" : d.get(F_ID)));
                    }
                }
                float[] qv = embedder.embed(query);
                if (qv == null) { sb.append("dense: (no embedder)\n"); return sb.toString(); }
                double minScore = (1.0 + MIN_COSINE) / 2.0, minStrict = (1.0 + MIN_COSINE_STRICT) / 2.0;
                TopDocs dense = searcher.search(new KnnFloatVectorQuery(F_VEC, qv, k), k);
                sb.append("dense (search floor cosine ").append(MIN_COSINE).append(", desk floor ").append(MIN_COSINE_STRICT).append("):\n");
                for (ScoreDoc sd : dense.scoreDocs) {
                    Document d = stored.document(sd.doc);
                    String verdict = sd.score >= minStrict ? "PASS desk" : sd.score >= minScore ? "search-only" : "below";
                    sb.append(String.format("  score %.3f cos %.3f %-11s %-6s %s%n", sd.score, 2 * sd.score - 1, verdict,
                            d.get(F_KIND), "chunk".equals(d.get(F_KIND)) ? d.get(F_PARENT) + " #chunk" : d.get(F_ID)));
                }
            } catch (org.apache.lucene.queryparser.classic.ParseException e) {
                return "unparseable query: " + e.getMessage();
            }
        }
        return sb.toString();
    }

    /**
     * The desk's sparse rule — how many of a question's terms a document must carry to count as
     * a holding. MEASURED 2026-09-03 on the shelf's 28 agent questions (BM25-only, top-10 / top-3
     * miss): plain search 50.0 / 82.1; "half the terms" 96.4 / 100 — agent questions run 20–40
     * terms and no document carries half of them; 25% 57.1 / 82.1; a fixed 2 or 3, or half capped
     * at 3, all 50.0 / 82.1 — identical to plain search, i.e. the floor costs NOTHING on real
     * questions while "zebra crossings on the moon in 1740" (one matching term) still returns
     * nothing. Default {@code cap:3}: half the terms for short queries, three for long ones.
     * RESEARCHZOSHO_DESK_MSM = half | pct:N | cap:N | N.
     */
    static final String DESK_MSM = org.researchzosho.Config.get("RESEARCHZOSHO_DESK_MSM", "cap:3");

    /** A parsed OR-of-terms query rewritten to require a minimum number of its clauses. */
    static Query atLeastHalf(Query parsed) {
        if (!(parsed instanceof org.apache.lucene.search.BooleanQuery bq)) return parsed;
        var b = new org.apache.lucene.search.BooleanQuery.Builder();
        int should = 0;
        for (var c : bq.clauses()) {
            b.add(c);
            if (c.occur() == org.apache.lucene.search.BooleanClause.Occur.SHOULD) should++;
        }
        if (should > 1) b.setMinimumNumberShouldMatch(Math.min(should, minimumMatch(should)));
        return b.build();
    }

    static int minimumMatch(int terms) {
        String rule = DESK_MSM.toLowerCase(java.util.Locale.ROOT).strip();
        if (rule.equals("half")) return (terms + 1) / 2;
        if (rule.startsWith("pct:")) return Math.max(1, (int) Math.ceil(terms * Integer.parseInt(rule.substring(4)) / 100.0));
        if (rule.startsWith("cap:")) return Math.min(Integer.parseInt(rule.substring(4)), (terms + 1) / 2);   // half, capped
        try { return Math.max(1, Integer.parseInt(rule)); } catch (NumberFormatException e) { return (terms + 1) / 2; }
    }

    /**
     * Why a rebuild must NOT run right now, or null. An embedder that is configured but not
     * answering would rebuild the index WITHOUT vectors — the desk silently drops to BM25-only
     * (measured: 50% vs 43% top-10 miss) until someone re-embeds. Keeping the old index is the
     * right failure. Written the day the Ada was released for other work (2026-09-04).
     */
    public static String rebuildBlocker(Embeddings.Embedder embedder) {
        if (embedder == null || "none".equals(embedder.modelId())) return null;
        float[] v;
        try { v = embedder.embed("ping"); } catch (Exception e) { v = null; }
        return v == null ? "embedder " + embedder.modelId() + " is configured but not answering — keeping the existing index (rebuild when it is back, or RESEARCHZOSHO_EMBED=off for a sparse-only index)" : null;
    }

    /** Add the dense vector when an embedder is configured; silently BM25-only otherwise. */
    private void addVector(Document d, String text) {
        float[] v = embedder.embed(text);
        if (v != null && v.length > 0) d.add(new KnnFloatVectorField(F_VEC, v, VectorSimilarityFunction.COSINE));
    }

    /** One search hit — enough to cite and to fetch the full entry by id. */
    public record Hit(String id, String kind, String state, String title, float score, String snippet) {
        public Hit(String id, String kind, String state, String title, float score) {
            this(id, kind, state, title, score, "");
        }
    }

    /** Add or replace one finding in the index (keyed by id — idempotent). */
    public synchronized void upsert(Finding f) throws IOException {
        try (Directory dir = FSDirectory.open(store.luceneDir());
             IndexWriter w = openWriter(dir, analyzer)) {
            w.updateDocument(new Term(F_ID, f.id()), toDoc(f));
        }
    }

    public synchronized void upsert(Investigation inv) throws IOException {
        try (Directory dir = FSDirectory.open(store.luceneDir());
             IndexWriter w = openWriter(dir, analyzer)) {
            Document d = new Document();
            d.add(new StringField(F_ID, inv.id(), Field.Store.YES));
            d.add(new StringField(F_KIND, "investigation", Field.Store.YES));
            d.add(new StringField(F_STATE, inv.state().name(), Field.Store.YES));
            d.add(new StringField(F_TITLE, inv.title(), Field.Store.YES));
            d.add(new TextField(F_TEXT, inv.title() + "\n" + inv.body(), Field.Store.NO));
            addVector(d, inv.title() + "\n" + inv.body());
            w.updateDocument(new Term(F_ID, inv.id()), d);
        }
    }

    /** A shelf article: kind=article, id=A-<subject>, searchable like everything else. */
    public synchronized void upsertArticle(String subject, String title, String prose) throws IOException {
        try (Directory dir = FSDirectory.open(store.luceneDir());
             IndexWriter w = openWriter(dir, analyzer)) {
            Document d = new Document();
            d.add(new StringField(F_ID, "A-" + subject, Field.Store.YES));
            d.add(new StringField(F_KIND, "article", Field.Store.YES));
            d.add(new StringField(F_STATE, "generated", Field.Store.YES));
            d.add(new StringField(F_TITLE, title, Field.Store.YES));
            d.add(new StringField(F_SUBJECT, subject, Field.Store.YES));
            d.add(new TextField(F_TEXT, subject + "\n" + title + "\n" + prose, Field.Store.NO));
            addVector(d, title + "\n" + prose);
            w.updateDocument(new Term(F_ID, "A-" + subject), d);
        }
    }

    /** A raw capture joins the catalog: title + locator stored, full text searchable. The id is
     *  the raw filename; the title field carries "title — locator" so a hit is citable as-is. */
    public synchronized void upsertRaw(String fileName, String title, String locator, String text) throws IOException {
        upsertRaw(fileName, title, locator, text, "");
    }

    public synchronized void upsertRaw(String fileName, String title, String locator, String text, String collection) throws IOException {
        try (Directory dir = FSDirectory.open(store.luceneDir());
             IndexWriter w = openWriter(dir, analyzer)) {
            w.deleteDocuments(new Term(F_ID, fileName), new Term(F_PARENT, fileName));
            for (Document d : rawDocs(fileName, title, locator, text)) {
                if (collection != null && !collection.isBlank()) d.add(new StringField(F_COLLECTION, collection, Field.Store.YES));
                w.addDocument(d);
            }
        }
    }

    /**
     * A raw capture becomes one PARENT doc (its identity: title + locator) and N CHUNK docs —
     * the survey's structured chunking (I-0005): paragraph/sentence boundaries, ~{@value #CHUNK_TOKENS}
     * tokens by script, no overlap. Each chunk carries a mechanical CONTEXT PREFIX ("From <title>
     * (<host>), part i/N") — the cheap half of contextual retrieval; the generated half is the
     * extracts crew's job later, gated on the bench. Before this a 20k-char paper was one
     * head-truncated vector.
     */
    private List<Document> rawDocs(String fileName, String title, String locator, String text) {
        List<Document> docs = new ArrayList<>();
        String t = title == null || title.isBlank() ? locator : title;
        Document parent = new Document();
        parent.add(new StringField(F_ID, fileName, Field.Store.YES));
        parent.add(new StringField(F_KIND, "raw", Field.Store.YES));
        parent.add(new StringField(F_STATE, "captured", Field.Store.YES));
        parent.add(new StringField(F_TITLE, t + " — " + locator, Field.Store.YES));
        parent.add(new TextField(F_TEXT, t + "\n" + locator, Field.Store.NO));
        docs.add(parent);
        List<String> chunks = chunk(text, CHUNK_TOKENS);
        String host = locator;
        try { String h = java.net.URI.create(locator).getHost(); if (h != null) host = h; } catch (Exception ignored) { }
        // Generated contexts (the extracts crew). RESEARCHZOSHO_ENRICH: off | dense | both. Measured
        // 2026-09-03 on the live shelf: 'both' put the situating sentences into BM25 text too and
        // the top-3 miss rate went 57% → 71% — the extra words make raw chunks crowd the findings.
        // The dense arm ticked UP, so 'dense' feeds the context to the vector only.
        String mode = org.researchzosho.Config.get("RESEARCHZOSHO_ENRICH", "dense").toLowerCase(java.util.Locale.ROOT);
        java.util.Map<String, String> generated = "off".equals(mode) ? java.util.Map.of() : Enrichment.load(store, fileName);
        List<String> prefixes = new ArrayList<>();   // what BM25 sees in front of the chunk
        List<String> texts = new ArrayList<>();      // what the embedder sees
        for (int i = 0; i < chunks.size(); i++) {
            String g = generated.get(Enrichment.chunkKey(chunks.get(i)));
            String mech = "From " + t + " (" + host + "), part " + (i + 1) + "/" + chunks.size() + ".\n";
            String withCtx = (g == null ? "" : g + "\n") + mech;
            prefixes.add("both".equals(mode) ? withCtx : mech);
            texts.add(withCtx + chunks.get(i));
        }
        List<float[]> vectors = "none".equals(embedder.modelId()) ? null : embedder.embedAll(texts);
        for (int i = 0; i < chunks.size(); i++) {
            String ctx = prefixes.get(i);
            Document c = new Document();
            c.add(new StringField(F_ID, fileName + "#" + (i + 1), Field.Store.YES));
            c.add(new StringField(F_PARENT, fileName, Field.Store.YES));
            c.add(new StringField(F_KIND, "chunk", Field.Store.YES));
            c.add(new StringField(F_STATE, "captured", Field.Store.YES));
            c.add(new StringField(F_TITLE, t + " — " + locator, Field.Store.YES));
            c.add(new org.apache.lucene.document.StoredField(F_SNIPPET, chunks.get(i)));
            c.add(new TextField(F_TEXT, ctx + chunks.get(i), Field.Store.NO));
            float[] v = vectors == null ? null : vectors.get(i);
            if (v != null && v.length > 0) c.add(new KnnFloatVectorField(F_VEC, v, VectorSimilarityFunction.COSINE));
            docs.add(c);
        }
        return docs;
    }

    /** Structure-aware chunking: paragraphs accumulate under the budget; an oversize paragraph
     *  splits at sentence ends (. ! ? 。！？); never mid-sentence unless a sentence alone exceeds it. */
    static List<String> chunk(String text, int maxTokens) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isBlank()) return out;
        StringBuilder cur = new StringBuilder();
        for (String para : text.split("\\n\\s*\\n")) {
            String p = para.strip();
            if (p.isEmpty()) continue;
            List<String> units = estTokens(p) > maxTokens ? sentences(p) : List.of(p);
            for (String u : units) {
                if (cur.length() > 0 && estTokens(cur.toString()) + estTokens(u) > maxTokens) {
                    out.add(cur.toString().strip());
                    cur.setLength(0);
                }
                if (cur.length() > 0) cur.append("\n\n");
                cur.append(u);
                if (estTokens(cur.toString()) > maxTokens * 2) {   // a single monstrous sentence
                    out.add(cur.toString().strip());
                    cur.setLength(0);
                }
            }
        }
        if (cur.length() > 0) out.add(cur.toString().strip());
        return out;
    }

    private static List<String> sentences(String p) {
        List<String> out = new ArrayList<>();
        var m = java.util.regex.Pattern.compile("[^.!?。！？]+[.!?。！？]+\\s*|[^.!?。！？]+$").matcher(p);
        while (m.find()) { String s = m.group().strip(); if (!s.isEmpty()) out.add(s); }
        return out.isEmpty() ? List.of(p) : out;
    }

    /** Tokens by script: CJK ≈ 1 per char, else ≈ 4 chars per token (the fan budget's estimate). */
    static int estTokens(String s) {
        if (s == null) return 0;
        int cjk = 0, other = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 0x3040 && c <= 0x30ff) || (c >= 0x4e00 && c <= 0x9fff) || (c >= 0xac00 && c <= 0xd7af)) cjk++;
            else other++;
        }
        return cjk + other / 4;
    }

    /** Wipe and re-index everything the store can parse. The recovery path for any index doubt. */
    /** Marker files: when the index was last written from disk, and by which embedder. */
    Path lastIndexedFile() { return store.luceneDir().resolve("last-indexed"); }
    Path embedderFile() { return store.luceneDir().resolve("embedder"); }

    /** The embedder the index on disk was built with ("" when unknown). */
    public String indexedWith() {
        try { return Files.exists(embedderFile()) ? Files.readString(embedderFile(), java.nio.charset.StandardCharsets.UTF_8).strip() : ""; }
        catch (IOException e) { return ""; }
    }

    /** When the index was last written from disk, or EPOCH. */
    public java.time.Instant lastIndexed() {
        try { return Files.exists(lastIndexedFile()) ? java.time.Instant.parse(Files.readString(lastIndexedFile(), java.nio.charset.StandardCharsets.UTF_8).strip()) : java.time.Instant.EPOCH; }
        catch (Exception e) { return java.time.Instant.EPOCH; }
    }

    private void stamp(java.time.Instant at) throws IOException {
        Files.createDirectories(store.luceneDir());
        Files.writeString(lastIndexedFile(), at.toString(), java.nio.charset.StandardCharsets.UTF_8);
        Files.writeString(embedderFile(), embedder.modelId(), java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * INCREMENTAL: index only what changed on disk since the last rebuild or refresh — findings,
     * investigations, articles and raw captures newer than the stamp. A full rebuild re-embeds
     * every chunk (13 minutes nightly on the live shelf, 77 with enrichment); most nights a few
     * files changed. Returns how many entries were re-indexed. Falls back to a full rebuild when
     * there is no index or the embedder changed.
     */
    public synchronized int refresh() throws IOException {
        try (Directory dir = FSDirectory.open(store.luceneDir())) {
            if (!DirectoryReader.indexExists(dir)) return rebuild();
        }
        if (!indexedWith().equals(embedder.modelId())) return rebuild();
        java.time.Instant since = lastIndexed();
        java.time.Instant now = java.time.Instant.now();
        int n = 0;
        for (Finding f : store.scanFindings().findings()) {
            Path p = store.findingsDir().resolve(f.id() + ".md");
            if (Files.exists(p) && Files.getLastModifiedTime(p).toInstant().isAfter(since)) { upsert(f); n++; }
        }
        if (Files.isDirectory(store.investigationsDir())) {
            try (var files = Files.list(store.investigationsDir())) {
                for (Path p : files.toList()) {
                    if (!p.toString().endsWith(".md") || !Files.getLastModifiedTime(p).toInstant().isAfter(since)) continue;
                    try { upsert(Investigation.parse(Files.readString(p, java.nio.charset.StandardCharsets.UTF_8))); n++; } catch (Exception ignored) { }
                }
            }
        }
        if (Files.isDirectory(store.articlesDir())) {
            try (var files = Files.list(store.articlesDir())) {
                for (Path p : files.toList()) {
                    if (!p.toString().endsWith(".md") || !Files.getLastModifiedTime(p).toInstant().isAfter(since)) continue;
                    var meta = LibraryProtocol.articleMeta(p);
                    String text = Files.readString(p, java.nio.charset.StandardCharsets.UTF_8);
                    int b = text.indexOf("\n---\n", 4);
                    String subject = meta.getOrDefault("subject", p.getFileName().toString().replaceFirst("^A-", "").replace(".md", ""));
                    upsertArticle(subject, meta.getOrDefault("title", subject), b < 0 ? text : text.substring(b + 5));
                    n++;
                }
            }
        }
        if (Files.isDirectory(store.rawDir())) {
            try (var files = Files.list(store.rawDir())) {
                for (Path p : files.toList()) {
                    if (!p.toString().endsWith(".md") || !Files.getLastModifiedTime(p).toInstant().isAfter(since)) continue;
                    String[] r = RawCapture.read(p);
                    if (RawCapture.looksBinary(r[2])) continue;
                    upsertRaw(p.getFileName().toString(), r[1], r[0], r[2], RawCapture.collectionOf(p));
                    n++;
                }
            }
        }
        n += dropGone();
        stamp(now);
        return n;
    }

    /**
     * Forget entries whose files are gone: a claim or write-up removed by hand, a raw capture deleted. Part of every
     * refresh, so a file-level edit reaches the search without the full rebuild (20 minutes on the live shelf, all of it
     * waiting on the embedder). Chunks go with their parent. Returns how many entries were dropped.
     */
    synchronized int dropGone() throws IOException {
        int gone = 0;
        try (Directory dir = FSDirectory.open(store.luceneDir())) {
            if (!DirectoryReader.indexExists(dir)) return 0;
            List<String> missing = new ArrayList<>();
            try (DirectoryReader r = DirectoryReader.open(dir)) {
                var ids = org.apache.lucene.index.MultiTerms.getTerms(r, F_ID);
                if (ids != null) {
                    var te = ids.iterator();
                    for (org.apache.lucene.util.BytesRef b = te.next(); b != null; b = te.next()) {
                        String id = b.utf8ToString();
                        if (id.contains("#")) continue;   // a chunk: its parent decides
                        if (!fileFor(id)) missing.add(id);
                    }
                }
            }
            if (missing.isEmpty()) return 0;
            try (IndexWriter w = openWriter(dir, analyzer)) {
                for (String id : missing) { w.deleteDocuments(new Term(F_ID, id), new Term(F_PARENT, id)); gone++; }
            }
        }
        return gone;
    }

    /** Whether the file an index entry came from is still there. */
    private boolean fileFor(String id) {
        Path p;
        if (id.startsWith("F-")) p = store.findingsDir().resolve(id + ".md");
        else if (id.startsWith("I-")) p = store.investigationsDir().resolve(id + ".md");
        else if (id.startsWith("A-")) p = store.articlesDir().resolve(id + ".md");
        else p = store.rawDir().resolve(id);
        return Files.exists(p);
    }

    public synchronized int rebuild() throws IOException {
        java.time.Instant started = java.time.Instant.now();
        int n = rebuildAll();
        stamp(started);
        return n;
    }

    private synchronized int rebuildAll() throws IOException {
        try (Directory dir = FSDirectory.open(store.luceneDir());
             IndexWriter w = openWriter(dir, analyzer)) {
            w.deleteAll();
            int n = 0;
            for (Finding f : store.scanFindings().findings()) {
                w.addDocument(toDoc(f));
                n++;
            }
            if (java.nio.file.Files.isDirectory(store.investigationsDir())) {
                try (var files = java.nio.file.Files.list(store.investigationsDir())) {
                    for (var p : files.toList()) {
                        if (!p.getFileName().toString().endsWith(".md")) continue;
                        try {
                            Investigation inv = Investigation.parse(java.nio.file.Files.readString(p));
                            Document d = new Document();
                            d.add(new StringField(F_ID, inv.id(), Field.Store.YES));
                            d.add(new StringField(F_KIND, "investigation", Field.Store.YES));
                            d.add(new StringField(F_STATE, inv.state().name(), Field.Store.YES));
                            d.add(new StringField(F_TITLE, inv.title(), Field.Store.YES));
                            d.add(new TextField(F_TEXT, inv.title() + "\n" + inv.body(), Field.Store.NO));
                            addVector(d, inv.title() + "\n" + inv.body());
                            w.addDocument(d);
                            n++;
                        } catch (Exception ignored) {
                            // malformed files are the scan's problem list, not the index's
                        }
                    }
                }
            }
            if (java.nio.file.Files.isDirectory(store.articlesDir())) {
                try (var files = java.nio.file.Files.list(store.articlesDir())) {
                    for (var p : files.toList()) {
                        String name = p.getFileName().toString();
                        if (!name.startsWith("A-") || !name.endsWith(".md")) continue;
                        try {
                            String text = java.nio.file.Files.readString(p);
                            String subject = name.substring(2, name.length() - 3);
                            var tm = java.util.regex.Pattern.compile("(?m)^title: (.*)$").matcher(text);
                            String title = tm.find() ? tm.group(1).strip() : subject;
                            int body = text.indexOf("\n---\n", 4);
                            Document d = new Document();
                            d.add(new StringField(F_ID, "A-" + subject, Field.Store.YES));
                            d.add(new StringField(F_KIND, "article", Field.Store.YES));
                            d.add(new StringField(F_STATE, "generated", Field.Store.YES));
                            d.add(new StringField(F_TITLE, title, Field.Store.YES));
                            d.add(new StringField(F_SUBJECT, subject, Field.Store.YES));
                            String prose = body < 0 ? text : text.substring(body + 5);
                            d.add(new TextField(F_TEXT, subject + "\n" + title + "\n" + prose, Field.Store.NO));
                            addVector(d, title + "\n" + prose);
                            w.addDocument(d);
                            n++;
                        } catch (Exception ignored) { }
                    }
                }
            }
            if (java.nio.file.Files.isDirectory(store.rawDir())) {
                try (var files = java.nio.file.Files.list(store.rawDir())) {
                    for (var p : files.toList()) {
                        if (!p.getFileName().toString().endsWith(".md")) continue;
                        try {
                            String[] r = RawCapture.read(p);
                            String coll = RawCapture.collectionOf(p);
                            for (Document d : rawDocs(p.getFileName().toString(), r[1], r[0], r[2])) {
                                if (!coll.isBlank()) d.add(new StringField(F_COLLECTION, coll, Field.Store.YES));
                                w.addDocument(d);
                            }
                            n++;
                        } catch (Exception ignored) {
                            // an unreadable raw file is not the index's problem
                        }
                    }
                }
            }
            return n;
        }
    }

    /**
     * BM25 search. The query is ESCAPED before parsing — patron text is data, and a stray
     * {@code AND(} from a model must not become query syntax (untrusted-text rule).
     */
    public List<Hit> search(String query, int k) throws IOException {
        return search(query, k, null);
    }

    /**
     * HYBRID search: BM25 over the text field and, when an embedder is configured, KNN over the
     * HNSW vectors — fused by reciprocal rank fusion (RRF, k=60), so a hit that only one side
     * finds still ranks and a hit both find rises. Dense catches what shares no vocabulary
     * (a JA entry for an EN question; "politeness" for a keigo finding); sparse keeps exact
     * names and ids sharp. {@code subject} (nullable) restricts to entries carrying that subject.
     */
    public List<Hit> search(String query, int k, String subject) throws IOException {
        return search(query, k, subject, null);
    }

    /**
     * {@code kind} (nullable) restricts to one tier. The established-verdict needs FINDINGS: on the
     * live shelf an accepted finding ranked below two articles, an investigation and a raw
     * document for its own claim, and the verdict read "not_established" (2026-09-03).
     */
    public List<Hit> search(String query, int k, String subject, String kind) throws IOException {
        return search(query, k, subject, kind, false);
    }

    /**
     * The RELEVANCE-FLOORED search the desk answers from. RRF scores carry rank, not relevance: a
     * document matching one word of a six-word question is rank 1 for the sparse arm and scores
     * exactly like a perfect hit, so {@code library_ask} could never say holds_nothing on a
     * non-empty shelf (Wyrdsekai's conformance suite, 2026-09-03: "zebra crossings on the moon
     * in 1740" returned two entries). Strict = the sparse arm must match a minimum of the query's
     * terms ({@link #DESK_MSM}: half, capped at three — measured) and the dense arm clears the
     * desk's cosine floor. Plain search keeps weak hits — scores are comparable within one call.
     */
    public List<Hit> searchStrict(String query, int k, String subject, String kind) throws IOException {
        return search(query, k, subject, kind, true);
    }

    /** Search scoped to one collection (a person's corpus); {@code kind} and {@code collection} may be null. */
    public List<Hit> searchIn(String query, int k, String kind, String collection) throws IOException {
        return search(query, k, null, kind, false, collection);
    }

    private List<Hit> search(String query, int k, String subject, String kind, boolean strict) throws IOException {
        return search(query, k, subject, kind, strict, null);
    }

    private List<Hit> search(String query, int k, String subject, String kind, boolean strict, String collection) throws IOException {
        if (query == null || query.isBlank()) return List.of();
        int want = Math.max(1, k);
        try (Directory dir = FSDirectory.open(store.luceneDir())) {
            if (!DirectoryReader.indexExists(dir)) return List.of();
            try (DirectoryReader r = DirectoryReader.open(dir)) {
                IndexSearcher searcher = new IndexSearcher(r);
                Query text = new QueryParser(F_TEXT, analyzer).parse(QueryParser.escape(query));
                if (strict) text = atLeastHalf(text);
                Query filter = null;
                {
                    var fb = new org.apache.lucene.search.BooleanQuery.Builder();
                    int clauses = 0;
                    if (subject != null && !subject.isBlank()) {
                        fb.add(new org.apache.lucene.search.TermQuery(new Term(F_SUBJECT, subject)), org.apache.lucene.search.BooleanClause.Occur.FILTER); clauses++;
                    }
                    if (kind != null && !kind.isBlank()) {
                        fb.add(new org.apache.lucene.search.TermQuery(new Term(F_KIND, kind)), org.apache.lucene.search.BooleanClause.Occur.FILTER); clauses++;
                    }
                    if (collection != null && !collection.isBlank()) {
                        fb.add(new org.apache.lucene.search.TermQuery(new Term(F_COLLECTION, collection)), org.apache.lucene.search.BooleanClause.Occur.FILTER); clauses++;
                    }
                    if (clauses > 0) filter = fb.build();
                }
                if (filter != null) {
                    text = new org.apache.lucene.search.BooleanQuery.Builder()
                            .add(text, org.apache.lucene.search.BooleanClause.Occur.MUST)
                            .add(filter, org.apache.lucene.search.BooleanClause.Occur.FILTER).build();
                }
                java.util.Map<Integer, Double> fused = new java.util.LinkedHashMap<>();
                StoredFields stored = searcher.storedFields();
                ScoreDoc[] sparse = searcher.search(text, want * 3).scoreDocs;
                for (int i = 0; i < sparse.length; i++) {
                    fused.merge(sparse[i].doc, kindWeight(stored.document(sparse[i].doc).get(F_KIND)) / (60 + i + 1), Double::sum);
                }
                float[] qv = embedder.embed(query);
                if (qv != null) {
                    // KNN always has k nearest neighbours — a query about harpsichords still gets
                    // the four "closest" keigo findings — so a dense hit counts only above a
                    // similarity floor, or the desk's "holds NOTHING" would never be true again.
                    // Lucene's COSINE score is (1 + cos) / 2. Calibrated 2026-09-02 on
                    // Qwen3-Embedding-0.6B: related queries 0.48-0.58 cosine, unrelated 0.29-0.47.
                    double minScore = (1.0 + (strict ? MIN_COSINE_STRICT : MIN_COSINE)) / 2.0;
                    Query knn = new KnnFloatVectorQuery(F_VEC, qv, want * 3, filter);
                    TopDocs dense = searcher.search(knn, want * 3);
                    int rank = 0;
                    for (ScoreDoc sd : dense.scoreDocs) {
                        if (sd.score < minScore) continue;
                        fused.merge(sd.doc, kindWeight(stored.document(sd.doc).get(F_KIND)) / (60 + rank + 1), Double::sum);
                        rank++;
                    }
                }
                // usage heat: what patrons were given lately ranks a little higher (cold entries unchanged)
                java.util.Map<String, Integer> heat = Heat.load(store);
                if (!heat.isEmpty()) {
                    for (var e : fused.entrySet()) {
                        Document d = stored.document(e.getKey());
                        String hid = "chunk".equals(d.get(F_KIND)) ? d.get(F_PARENT) : d.get(F_ID);
                        e.setValue(e.getValue() * Heat.boost(heat, hid));
                    }
                }
                List<java.util.Map.Entry<Integer, Double>> ranked = new ArrayList<>(fused.entrySet());
                ranked.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
                // COLLAPSE chunks onto their parent: one hit per raw document, carrying the best
                // chunk as its snippet — the desk shows the passage that matched, not the head.
                java.util.Map<String, Hit> byId = new java.util.LinkedHashMap<>();
                int pool = want * 3;   // rerank pool: the fused top-3k, collapsed
                for (var e : ranked) {
                    Document d = stored.document(e.getKey());
                    boolean isChunk = "chunk".equals(d.get(F_KIND));
                    String id = isChunk ? d.get(F_PARENT) : d.get(F_ID);
                    if (byId.containsKey(id)) continue;
                    byId.put(id, new Hit(id, isChunk ? "raw" : d.get(F_KIND), d.get(F_STATE), d.get(F_TITLE),
                            e.getValue().floatValue(), isChunk ? d.get(F_SNIPPET) : ""));
                    if (byId.size() >= pool) break;
                }
                List<Hit> pooled = new ArrayList<>(byId.values());
                List<Hit> reranked = rerank(query, pooled);
                return reranked.size() > want ? new ArrayList<>(reranked.subList(0, want)) : reranked;
            } catch (org.apache.lucene.queryparser.classic.ParseException e) {
                return List.of(); // an unparseable (escaped!) query matches nothing, loudly nothing
            }
        }
    }

    /**
     * The cross-encoder pass over the fused pool. Text per candidate: a finding's title + body,
     * an investigation's title + head, a raw hit's matching chunk. A scorer that is absent or
     * down leaves the fused order untouched.
     */
    private List<Hit> rerank(String query, List<Hit> pool) {
        if (pool.size() < 2 || "none".equals(reranker.modelId())) return pool;
        List<String> texts = new ArrayList<>();
        for (Hit h : pool) {
            String t = h.title();
            try {
                if ("finding".equals(h.kind())) { Finding f = store.finding(h.id()); if (f != null) t = f.title() + "\n" + f.body(); }
                else if ("investigation".equals(h.kind())) { Investigation inv = store.investigation(h.id()); if (inv != null) t = inv.title() + "\n" + inv.body(); }
                else if (!h.snippet().isBlank()) t = h.title() + "\n" + h.snippet();
            } catch (Exception ignored) { }
            texts.add(t);
        }
        List<Double> scores = reranker.score(query, texts);
        if (scores == null || scores.size() != pool.size()) return pool;
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < pool.size(); i++) order.add(i);
        order.sort((a, b) -> Double.compare(scores.get(b), scores.get(a)));
        List<Hit> out = new ArrayList<>();
        for (int i : order) { Hit h = pool.get(i); out.add(new Hit(h.id(), h.kind(), h.state(), h.title(), scores.get(i).floatValue(), h.snippet())); }
        return out;
    }

    /** Entries carrying a subject, by count — the facet. */
    public java.util.Map<String, Integer> subjectCounts() throws IOException {
        java.util.Map<String, Integer> counts = new java.util.TreeMap<>();
        try (Directory dir = FSDirectory.open(store.luceneDir())) {
            if (!DirectoryReader.indexExists(dir)) return counts;
            try (DirectoryReader r = DirectoryReader.open(dir)) {
                var terms = org.apache.lucene.index.MultiTerms.getTerms(r, F_SUBJECT);
                if (terms == null) return counts;
                var it = terms.iterator();
                while (it.next() != null) counts.put(it.term().utf8ToString(), it.docFreq());
            }
        }
        return counts;
    }

    private Document toDoc(Finding f) {
        Document d = new Document();
        d.add(new StringField(F_ID, f.id(), Field.Store.YES));
        d.add(new StringField(F_KIND, "finding", Field.Store.YES));
        d.add(new StringField(F_STATE, f.state().name(), Field.Store.YES));
        d.add(new StringField(F_TITLE, f.title(), Field.Store.YES));
        for (String sub : f.subjects()) d.add(new StringField(F_SUBJECT, sub, Field.Store.YES));
        String text = f.title() + "\n" + String.join(" ", f.subjects()) + "\n" + f.body();
        d.add(new TextField(F_TEXT, text, Field.Store.NO));
        addVector(d, f.title() + "\n" + f.body());
        return d;
    }
}
