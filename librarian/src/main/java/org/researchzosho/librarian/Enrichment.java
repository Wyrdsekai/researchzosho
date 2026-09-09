package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GENERATED contextual enrichment — the extracts crew's first job, and the organization survey's
 * other large lever (I-0005, Anthropic's contextual retrieval: a short generated context per chunk
 * cut top-20 retrieval failures 5.7% → 3.7% alone, → 1.9% with BM25-context and reranking). The
 * mechanical prefix ("From <title>, part i/N") ships with chunking; THIS is the model-written
 * half: one or two sentences situating the chunk within its document, prepended to the chunk
 * for both the sparse and the dense index.
 *
 * <p>Persisted in {@code extracts/<rawfile>.ctx.jsonl}, keyed by a hash of the chunk text, so a
 * rebuild never regenerates and a changed chunk gets a fresh context. Absent contexts mean the
 * mechanical prefix alone — never a blocked index. Bench-gated: it stays only if the miss rate moves.
 */
public final class Enrichment {

    public interface Contextualizer {
        /** One or two sentences situating {@code chunk} within the document {@code head}. */
        String situate(String docTitle, String docHead, String chunk) throws Exception;
    }

    private static final ObjectMapper M = new ObjectMapper();

    private Enrichment() { }

    static Path ctxFile(LibraryStore store, String rawFileName) {
        return store.extractsDir().resolve(rawFileName.replaceAll("\\.md$", "") + ".ctx.jsonl");
    }

    static String chunkKey(String chunk) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(chunk.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d, 0, 6);
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    /** chunk-key → context, for a raw file; empty when nothing was generated yet. */
    public static Map<String, String> load(LibraryStore store, String rawFileName) {
        Map<String, String> out = new LinkedHashMap<>();
        Path f = ctxFile(store, rawFileName);
        if (!Files.exists(f)) return out;
        try {
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                JsonNode n = M.readTree(line);
                out.put(n.path("key").asText(), n.path("context").asText(""));
            }
        } catch (IOException ignored) { }
        return out;
    }

    public record Outcome(int files, int chunksGenerated, int chunksSkipped, List<String> problems) { }

    /**
     * Generate contexts for every chunk of every raw capture that lacks one (up to {@code limit}
     * raw files, 0 = all). Writes as it goes, so an interrupted run keeps its progress.
     */
    public static Outcome run(LibraryStore store, Contextualizer ctx, int limit) throws IOException {
        int files = 0, gen = 0, skipped = 0;
        List<String> problems = new java.util.ArrayList<>();
        if (!Files.isDirectory(store.rawDir())) return new Outcome(0, 0, 0, problems);
        List<Path> raws;
        try (var l = Files.list(store.rawDir())) { raws = l.filter(p -> p.toString().endsWith(".md")).sorted().toList(); }
        for (Path p : raws) {
            if (limit > 0 && files >= limit) break;
            String name = p.getFileName().toString();
            String[] r = RawCapture.read(p);
            if (RawCapture.looksBinary(r[2])) continue;
            List<String> chunks = LibrarianIndex.chunk(r[2], LibrarianIndex.CHUNK_TOKENS);
            Map<String, String> have = load(store, name);
            boolean touched = false;
            String head = Acquisitions.compress(r[2], 1200);
            Files.createDirectories(store.extractsDir());
            for (String chunk : chunks) {
                String key = chunkKey(chunk);
                if (have.containsKey(key)) { skipped++; continue; }
                String c;
                try {
                    c = ctx.situate(r[1].isEmpty() ? r[0] : r[1], head, chunk);
                } catch (Exception e) {
                    problems.add(name + ": " + e.getMessage());
                    break;
                }
                if (c == null || c.isBlank()) continue;
                c = c.strip().replaceAll("\\s+", " ");
                if (c.length() > 400) c = c.substring(0, 397) + "...";
                var line = M.createObjectNode();
                line.put("key", key);
                line.put("context", c);
                Files.writeString(ctxFile(store, name), M.writeValueAsString(line) + "\n", StandardCharsets.UTF_8,
                        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
                have.put(key, c);
                gen++;
                touched = true;
            }
            if (touched) files++;
        }
        store.circulate("enrich", gen + " contexts generated, " + skipped + " already had");
        return new Outcome(files, gen, skipped, problems);
    }

    /** The live seat — Anthropic's prompt shape, positive, short. */
    public static Contextualizer driveContextualizer(org.researchzosho.drive.DriveClient drive) {
        return (title, head, chunk) -> {
            var msgs = M.createArrayNode();
            msgs.addObject().put("role", "user").put("content",
                    "Here is the start of a document titled '" + title + "':\n" + Fence.wrap("DOCUMENT", head)
                    + "\n\nHere is one chunk from the same document:\n" + Fence.wrap("CHUNK", Acquisitions.compress(chunk, 1500))
                    + "\n" + Fence.rule("DOCUMENT and CHUNK") + "\n\nWrite one or two sentences that "
                    + "situate this chunk within the document, to improve search retrieval of the chunk: "
                    + "what it is about, which part of the document it belongs to, the key entities it names. "
                    + "Answer with the sentences only.");
            return drive.classify(msgs, 120);
        };
    }

    static void cli(LibraryStore store, String[] args, String baseUrl, String model) throws Exception {
        int limit = 0;
        String drive = baseUrl;
        for (int i = 2; i < args.length; i++) {
            if (args[i].startsWith("http")) drive = args[i];
            else if (args[i].matches("\\d+")) limit = Integer.parseInt(args[i]);
        }
        Outcome o = run(store, driveContextualizer(new org.researchzosho.drive.DriveClient(drive, model)), limit);
        System.out.println("enriched: " + o.chunksGenerated() + " chunk context(s) generated across " + o.files()
                + " raw file(s); " + o.chunksSkipped() + " already had one → `researchzosho rebuild` to index them");
        for (String p : o.problems()) System.out.println("  note: " + p);
    }
}
