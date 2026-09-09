package org.researchzosho.librarian;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One claim, one file — the library's atomic unit.
 *
 * <p>The field set is the survey's convergence (docs/REF_SCIENCE_HARNESSES.md) made concrete:
 * bi-temporal dates (Graphiti — {@code recordedAt} is when WE learned it, {@code validAsOf}
 * when it held in the world), edition-aware sources (the humanities rule: "Gilgamesh says X"
 * is not a source; whose translation is), {@code claimType} steering review effort (the Kosmos
 * accuracy split: extraction verifies cheap, synthesis is where truth leaks), volatility
 * classes feeding review-by horizons (living-review serials), and supersession as an explicit
 * record — never a delete.
 *
 * <p>{@link #contentHash()} covers exactly the reviewed substance (title, claim body, sources,
 * claim type — not subjects, which are catalog metadata). A review records the hash it approved; any later edit to those fields
 * mechanically stales the approval (the GPD rule — staleness is computed, never remembered).
 * State transitions and the review record itself are OUTSIDE the hash, so promoting a draft
 * does not stale its own approval.
 */
public record Finding(
        String id,                 // F-<n>-<slug>, equals the filename stem exactly
        String title,
        List<String> subjects,     // canonical subjects from catalog/subjects.md
        State state,
        ClaimType claimType,
        Confidence confidence,
        String writer,             // person | model:<drive> | agent:<name> | patron:<runtime>
        String recordedAt,         // ISO instant — when the library learned it
        String validAsOf,          // ISO date — when it held in the world
        Volatility volatility,
        String reviewBy,           // ISO date — the serials horizon; "" = stable, no horizon
        List<Source> sources,
        List<String> supersedes,   // ids of findings this one supersedes (they stay on disk)
        Review review,             // nullable — present once a librarian pass has judged it
        String body,
        Triple triple,             // nullable — subject | predicate | object, when the claim has that shape
        List<Note> notes) {        // meta-facts: statements ABOUT this finding (disputes, inventory checks…)

    /** The 15-field form every earlier caller uses: no triple, no notes. */
    /**
     * For a NEW finding only. Rebuilding an existing finding through this form drops its triple and
     * its notes — every dispute record, inventory verdict and contradiction note — which is how the
     * cataloger once erased them nightly. Rebuild with the full form and {@code f.triple(), f.notes()}.
     */
    public Finding(String id, String title, List<String> subjects, State state, ClaimType claimType,
                   Confidence confidence, String writer, String recordedAt, String validAsOf,
                   Volatility volatility, String reviewBy, List<Source> sources, List<String> supersedes,
                   Review review, String body) {
        this(id, title, subjects, state, claimType, confidence, writer, recordedAt, validAsOf, volatility,
                reviewBy, sources, supersedes, review, body, null, List.of());
    }

    public Finding {
        notes = notes == null ? List.of() : List.copyOf(notes);
    }

    /** This finding with a note added (meta-fact); everything else, including the review, unchanged. */
    public Finding withNote(Note n) {
        List<Note> out = new ArrayList<>(notes); out.add(n);
        return new Finding(id, title, subjects, state, claimType, confidence, writer, recordedAt, validAsOf,
                volatility, reviewBy, sources, supersedes, review, body, triple, out);
    }

    public Finding withTriple(Triple t) {
        return new Finding(id, title, subjects, state, claimType, confidence, writer, recordedAt, validAsOf,
                volatility, reviewBy, sources, supersedes, review, body, t, notes);
    }

    /**
     * The claim as subject | predicate | object, when it has that shape (kiroku-memory's take,
     * 2026-09-05): two findings with the SAME subject and predicate and a DIFFERENT object are a
     * contradiction candidate by arithmetic — nominated before any model reads them.
     */
    public record Triple(String subject, String predicate, String object) {
        String toLine() { return subject + " | " + predicate + " | " + object; }
        static Triple fromLine(String line) {
            String[] p = line.split("\\|", 3);
            if (p.length != 3) throw new IllegalArgumentException("triple needs 'subject | predicate | object': " + line);
            return new Triple(p[0].strip(), p[1].strip(), p[2].strip());
        }
        /** Lowercased, whitespace-collapsed, trailing punctuation dropped — the entity-resolution floor. */
        public static String canon(String s) {
            return s == null ? "" : s.strip().toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", " ").replaceAll("[.。、,;:!?]+$", "");
        }
        public boolean sameKey(Triple o) { return o != null && canon(subject).equals(canon(o.subject)) && canon(predicate).equals(canon(o.predicate)); }
        public boolean clashes(Triple o) { return sameKey(o) && !canon(object).equals(canon(o.object)); }
    }

    /** A meta-fact: {@code kind | by | date | text}. Kinds: disputed, inventory, supersedes, cataloger, person. */
    public record Note(String kind, String by, String date, String text) {
        String toLine() { return kind + " | " + by + " | " + date + " | " + text.replaceAll("\\s+", " "); }
        static Note fromLine(String line) {
            String[] p = line.split("\\|", 4);
            if (p.length != 4) throw new IllegalArgumentException("note needs 'kind | by | date | text': " + line);
            return new Note(p[0].strip(), p[1].strip(), p[2].strip(), p[3].strip());
        }
    }

    public enum State { draft, accepted, superseded, disputed, retired }
    public enum ClaimType { extraction, synthesis, interpretation, speculation }
    public enum Confidence { low, medium, high }
    public enum Volatility { fast, slow, stable }

    /** locator | edition | why_it_matters. Locator is a URL, a raw/ path, or a citation. */
    public record Source(String locator, String edition, String whyItMatters) {
        String toLine() {
            return locator + " | " + edition + " | " + whyItMatters;
        }
        static Source fromLine(String line) {
            String[] parts = line.split("\\|", 3);
            if (parts.length != 3) {
                throw new IllegalArgumentException(
                        "source line needs 'locator | edition | why': " + line);
            }
            return new Source(parts[0].strip(), parts[1].strip(), parts[2].strip());
        }
    }

    /** The librarian pass's record. {@code contentHash} is what it approved. */
    public record Review(int round, String reviewer, String decision, String contentHash, String at) {
        String toLine() {
            return "round=" + round + " reviewer=" + reviewer + " decision=" + decision
                    + " content_hash=" + contentHash + " at=" + at;
        }
        static Review fromLine(String line) {
            Map<String, String> t = Frontmatter.tokens(line);
            for (String req : new String[]{"round", "reviewer", "decision", "content_hash", "at"}) {
                if (!t.containsKey(req)) {
                    throw new IllegalArgumentException("review line missing '" + req + "': " + line);
                }
            }
            return new Review(Integer.parseInt(t.get("round")), t.get("reviewer"),
                    t.get("decision"), t.get("content_hash"), t.get("at"));
        }
    }

    private static final Set<String> KNOWN_SCALARS = Set.of("schema", "id", "title", "state",
            "claim_type", "confidence", "writer", "recorded_at", "valid_as_of", "volatility",
            "review_by", "review", "triple");
    private static final Set<String> KNOWN_LISTS = Set.of("subjects", "supersedes");

    /** Render the canonical file text. parse(format(f)) == f is pinned by test. */
    public String format() {
        StringBuilder sb = new StringBuilder("---\n");
        sb.append("schema: 1\n");
        sb.append("id: ").append(id).append('\n');
        sb.append("title: ").append(title).append('\n');
        sb.append("subjects: [").append(String.join(", ", subjects)).append("]\n");
        sb.append("state: ").append(state).append('\n');
        sb.append("claim_type: ").append(claimType).append('\n');
        sb.append("confidence: ").append(confidence).append('\n');
        sb.append("writer: ").append(writer).append('\n');
        sb.append("recorded_at: ").append(recordedAt).append('\n');
        sb.append("valid_as_of: ").append(validAsOf).append('\n');
        sb.append("volatility: ").append(volatility).append('\n');
        if (!reviewBy.isEmpty()) sb.append("review_by: ").append(reviewBy).append('\n');
        for (Source s : sources) sb.append("source: ").append(s.toLine()).append('\n');
        if (triple != null) sb.append("triple: ").append(triple.toLine()).append('\n');
        for (Note n : notes) sb.append("note: ").append(n.toLine()).append('\n');
        if (!supersedes.isEmpty()) {
            sb.append("supersedes: [").append(String.join(", ", supersedes)).append("]\n");
        }
        if (review != null) sb.append("review: ").append(review.toLine()).append('\n');
        sb.append("---\n").append(body);
        return sb.toString();
    }

    /**
     * Parse and VALIDATE, failing closed: unknown keys, missing required fields, a bad enum, a
     * schema other than 1, or a title/id containing characters that would break the frontmatter
     * are all errors naming the problem.
     */
    public static Finding parse(String text) {
        Frontmatter.Head h = Frontmatter.parse(text);
        for (String k : h.scalars().keySet()) {
            if (!KNOWN_SCALARS.contains(k)) {
                throw new IllegalArgumentException("unknown frontmatter key: " + k);
            }
        }
        for (String k : h.lists().keySet()) {
            if (!KNOWN_LISTS.contains(k)) {
                throw new IllegalArgumentException("unknown frontmatter list: " + k);
            }
        }
        if (!"1".equals(h.scalars().get("schema"))) {
            throw new IllegalArgumentException("unsupported schema: " + h.scalars().get("schema"));
        }
        String id = required(h, "id");
        if (!id.matches("F-\\d+-[a-z0-9-]+")) {
            throw new IllegalArgumentException("finding id is not F-<n>-<slug>: " + id);
        }
        List<Source> sources = new ArrayList<>();
        for (String line : h.sourceLines()) sources.add(Source.fromLine(line));
        String reviewLine = h.scalars().get("review");
        String tripleLine = h.scalars().get("triple");
        List<Note> notes = new ArrayList<>();
        for (String line : h.noteLines()) notes.add(Note.fromLine(line));
        return new Finding(
                id,
                required(h, "title"),
                h.lists().getOrDefault("subjects", List.of()),
                parseEnum(State.class, required(h, "state")),
                parseEnum(ClaimType.class, required(h, "claim_type")),
                parseEnum(Confidence.class, required(h, "confidence")),
                required(h, "writer"),
                required(h, "recorded_at"),
                required(h, "valid_as_of"),
                parseEnum(Volatility.class, required(h, "volatility")),
                h.scalars().getOrDefault("review_by", ""),
                sources,
                h.lists().getOrDefault("supersedes", List.of()),
                reviewLine == null ? null : Review.fromLine(reviewLine),
                h.body(),
                tripleLine == null ? null : Triple.fromLine(tripleLine),
                notes);
    }

    /** Hash of the reviewed substance — see class doc for what is in and out, and why. */
    public String contentHash() {
        StringBuilder sb = new StringBuilder();
        sb.append(title).append('\n');
        // subjects are CATALOG metadata (the cataloger assigns them after review) — not substance;
        // an approval must survive being catalogued. Notes are meta-facts ABOUT the finding and the
        // triple is a machine reading of the claim: both outside the hash too.
        sb.append(claimType).append('\n');
        for (Source s : sources) sb.append(s.toLine()).append('\n');
        sb.append(body);
        return digest(sb.toString());
    }

    private static String digest(String text) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(d, 0, 8); // 16 hex chars — plenty for staleness
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** True when the entry's approval no longer covers its content — review needed again. */
    public boolean reviewStale() {
        return review != null && !review.contentHash().equals(contentHash());
    }

    /** The hash formula BEFORE 2026-09-02 (subjects were inside it). A recorded review carrying
     *  this value approved content that has not changed — the migration re-signs it. */
    public String legacyContentHash() {
        StringBuilder sb = new StringBuilder();
        sb.append(title).append('\n');
        sb.append(String.join(",", subjects)).append('\n');
        sb.append(claimType).append('\n');
        for (Source s : sources) sb.append(s.toLine()).append('\n');
        sb.append(body);
        return digest(sb.toString());
    }

    private static String required(Frontmatter.Head h, String key) {
        String v = h.scalars().get(key);
        if (v == null || v.isEmpty()) {
            throw new IllegalArgumentException("missing required frontmatter key: " + key);
        }
        return v;
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String v) {
        try {
            return Enum.valueOf(type, v);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "bad " + type.getSimpleName().toLowerCase() + " value: " + v);
        }
    }
}
