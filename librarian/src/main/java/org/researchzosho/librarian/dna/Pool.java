package org.researchzosho.librarian.dna;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.researchzosho.Config;
import org.researchzosho.Version;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * The shared pool's packs (0.6.0): a file per kind carrying works' DNA with its provenance and versions, so that an install starts with
 * documents it did not have to make and a person can hand theirs on. The format: one JSON object a line; the first line the header
 * ({@code rz-dna-pack-1}, the kind, when it was made, the versions of the layers, the count, who made it), then one document a line as
 * the store keeps it — the record, the measured and the labelled layers — with a {@code from} field naming the contributor (or
 * "anonymous") and the moment, and nothing of the person: no shelf ids, no file paths, no stars, no reactions. A reader keeps a document
 * it lacks, keeps its own when it has one, and sets aside a document whose labelled layer is of another prompt version. Contribution
 * is opt-in ({@code RESEARCHZOSHO_DNA_SHARE}): the night writes the person's pack to an outbox for them to send; the published pack is
 * fetched when researchzosho.org has one. The format and the loader are here now; the contents come later (docs/REC_DNA.md).
 */
public final class Pool {
    private Pool() { }

    public static final String FORMAT = "rz-dna-pack-1";
    public static final String SHARE_KEY = "RESEARCHZOSHO_DNA_SHARE";
    /** Where the published packs are announced; a test points it elsewhere. */
    public static volatile String site = "https://researchzosho.org/dna/";

    static Path outbox() { return Dna.dir().resolve("outbox"); }

    /** A document as the pack carries it: the layers, the provenance, nothing of the person. */
    public static ObjectNode packed(ObjectNode doc, String contributor) {
        ObjectNode o = Dna.M.createObjectNode();
        o.put("key", doc.path("key").asText()); o.put("kind", doc.path("kind").asText(Dna.BOOK));
        ObjectNode r = o.putObject("record");
        for (String k : List.of("title", "authors", "year", "language", "isbn", "series", "subjects", "tags", "edition_count", "ratings_count", "first_published", "ol_key", "show", "feed", "date", "categories", "seconds")) if (doc.path("record").has(k)) r.set(k, doc.path("record").get(k).deepCopy());
        if (doc.path("record").has("sources")) r.set("sources", doc.path("record").get("sources").deepCopy());
        if (doc.has("measured")) o.set("measured", doc.get("measured").deepCopy());
        if (doc.has("labelled")) o.set("labelled", doc.get("labelled").deepCopy());
        ObjectNode from = o.putObject("from"); from.put("contributor", contributor == null || contributor.isBlank() ? "anonymous" : contributor.strip()); from.put("at", Instant.now().toString()); from.put("tool", "researchzosho " + (Version.number() == null ? "dev" : Version.number()));
        return o;
    }

    /** Writes the pack of every labelled document of the kind; gzip when the name ends in .gz. The count written. */
    public static int export(String kind, Path to, String contributor) throws IOException {
        List<ObjectNode> docs = Dna.labelled(kind);
        Files.createDirectories(to.toAbsolutePath().getParent());
        ObjectNode head = Dna.M.createObjectNode();
        head.put("format", FORMAT); head.put("kind", kind); head.put("made", Instant.now().toString()); head.put("labelled_version", Labels.VERSION); head.put("measured_version", Measured.VERSION); head.put("count", docs.size());
        head.put("by", "researchzosho.org"); head.put("contributor", contributor == null || contributor.isBlank() ? "anonymous" : contributor.strip());
        try (OutputStream raw = Files.newOutputStream(to); OutputStream os = to.getFileName().toString().endsWith(".gz") ? new GZIPOutputStream(raw) : raw; BufferedWriter w = new BufferedWriter(new OutputStreamWriter(os, StandardCharsets.UTF_8))) {
            w.write(head.toString()); w.write('\n');
            for (ObjectNode d : docs) { w.write(packed(d, contributor).toString()); w.write('\n'); }
        }
        return docs.size();
    }

    public record Loaded(int added, int kept, int setAside, int count, String kind) {
        public String said() { return added + " document(s) added, " + kept + " kept as this machine had them, " + setAside + " set aside (another prompt version), of " + count + " in the pack of " + kind + "s."; }
    }

    /** Reads a pack into the store. A document the store lacks is added; one it has is kept; one of another labelled version is set aside. */
    public static Loaded importPack(Path from) throws IOException {
        try (InputStream raw = Files.newInputStream(from); InputStream in = from.getFileName().toString().endsWith(".gz") ? new GZIPInputStream(raw) : raw; BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String first = r.readLine();
            JsonNode head; try { head = first == null ? null : Dna.M.readTree(first); } catch (Exception e) { head = null; }
            if (head == null || !FORMAT.equals(head.path("format").asText(""))) throw new IOException("not a DNA pack: the first line does not say " + FORMAT);
            String kind = head.path("kind").asText(Dna.BOOK);
            int added = 0, kept = 0, aside = 0, count = 0;
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isBlank()) continue;
                count++;
                JsonNode n; try { n = Dna.M.readTree(line); } catch (Exception e) { aside++; continue; }
                if (!n.isObject() || n.path("key").asText("").isEmpty()) { aside++; continue; }
                ObjectNode d = (ObjectNode) n;
                if (!Dna.labelledCurrent(d)) { aside++; continue; }
                d.put("kind", kind);
                if (Dna.read(kind, d.path("key").asText()) != null) { kept++; continue; }
                d.put("made", Instant.now().toString());
                Dna.write(d); added++;
            }
            return new Loaded(added, kept, aside, count, kind);
        }
    }

    /** Whether the person opted in to contributing. */
    public static boolean sharing() { String v = Config.get(SHARE_KEY); return v != null && (v.strip().equalsIgnoreCase("on") || v.strip().equalsIgnoreCase("true") || v.strip().equalsIgnoreCase("yes")); }

    /** The night's step: with sharing on, the pack of the kind written to the outbox (one a week) for the person to send. A sentence. */
    public static String nightly(String kind) throws IOException {
        if (!sharing()) return "sharing is off (" + SHARE_KEY + ")";
        Files.createDirectories(outbox());
        Path to = outbox().resolve(kind + "s-" + LocalDate.now() + ".jsonl.gz");
        if (Files.isRegularFile(to)) return "today's pack is in the outbox already";
        try (var s = Files.list(outbox())) { if (s.anyMatch(p -> p.getFileName().toString().startsWith(kind + "s-") && p.toFile().lastModified() > System.currentTimeMillis() - 6L * 86_400_000L)) return "this week's pack is in the outbox"; }
        int n = export(kind, to, Config.get("RESEARCHZOSHO_DNA_CONTRIBUTOR", ""));
        return n + " document(s) packed to " + to + " for you to send";
    }

    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build();

    /** The published pack for the kind, read into the store when the site announces one; a sentence either way. */
    public static String refresh(String kind) {
        String index = site + kind + "s.json";
        try {
            HttpResponse<String> resp = HTTP.send(HttpRequest.newBuilder(URI.create(index)).timeout(Duration.ofSeconds(20)).header("User-Agent", "ResearchZosho/" + (Version.number() == null ? "dev" : Version.number()) + " (https://researchzosho.org)").GET().build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() == 404) return "No pack is published yet for " + kind + "s; the format is ready (" + FORMAT + ") and " + index + " will announce the first.";
            if (resp.statusCode() != 200) return "The site answered HTTP " + resp.statusCode() + " for " + index + ".";
            JsonNode ann = Dna.M.readTree(resp.body());
            String url = ann.path("url").asText(""), made = ann.path("made").asText("");
            if (url.isEmpty()) return "The announcement at " + index + " names no pack.";
            Path stamp = Dna.kindDir(kind).resolve("pool-" + kind + ".stamp");
            if (Files.isRegularFile(stamp) && Files.readString(stamp, StandardCharsets.UTF_8).strip().equals(made)) return "The published pack of " + made + " is already in.";
            Path tmp = Files.createTempFile("rz-dna-pack", url.endsWith(".gz") ? ".jsonl.gz" : ".jsonl");
            HttpResponse<Path> pack = HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(10)).GET().build(), HttpResponse.BodyHandlers.ofFile(tmp));
            if (pack.statusCode() != 200) return "The pack at " + url + " answered HTTP " + pack.statusCode() + ".";
            Loaded l = importPack(tmp);
            Files.deleteIfExists(tmp);
            Files.createDirectories(stamp.getParent()); Files.writeString(stamp, made, StandardCharsets.UTF_8);
            return "The published pack of " + made + ": " + l.said();
        } catch (Exception e) { return "The published pack could not be read: " + e.getMessage(); }
    }

    /** Where things stand, in sentences. */
    public static String status(String kind) {
        int[] c = Dna.counts(kind);
        StringBuilder b = new StringBuilder(String.format(Locale.ROOT, "The store holds %d %s document(s), %d labelled. ", c[0], kind, c[1]));
        b.append(sharing() ? "Sharing is on: the night writes a pack of the labelled documents to " + outbox() + " once a week for you to send (" + SHARE_KEY + "=off stops it). " : "Sharing is off: nothing leaves this machine (" + SHARE_KEY + "=on writes a weekly pack to " + outbox() + " for you to send). ");
        b.append("researchzosho dna pack export <file> writes a pack now, dna pack import <file> reads one, dna pack refresh fetches the published one when there is one.");
        return b.toString();
    }
}
