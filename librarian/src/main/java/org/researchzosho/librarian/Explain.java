package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A reading aid, never a record. An entry re-explained at a rung the reader picks — {@code beginner} for
 * anyone, {@code familiar} for someone who knows the field but not this work, {@code written} for the
 * sources' own register — and a term explained in the context of an entry. SHELVES ONLY: the model
 * writes from the entry, its findings and its captured sources and from nothing else; every paragraph
 * is read back against the material it cites, a paragraph the material does not support is marked so,
 * and a paragraph that cites nothing is marked as not from the shelves. When the shelves do not explain
 * a term, the reading says so and carries an OFFER: the research ask that would fill the gap, to run
 * as a quick look (a short job at the front of the line) or as full research. Readings are cached under
 * {@code readings/} and regenerated when the material under them changes. They are not shelved, not
 * indexed, and never become findings.
 */
public final class Explain {

    private static final ObjectMapper M = new ObjectMapper();

    public enum Rung {
        beginner("someone new to this field, with no background in it: everyday words, short sentences, one concrete example for each idea, nothing assumed"),
        familiar("someone familiar with the field but not with this particular work: the field's ordinary terms are fine, its specialist ones are explained the first time"),
        written("a colleague in the field: the sources' own terms and their precision, nothing simplified");
        public final String audience;
        Rung(String audience) { this.audience = audience; }
        public static Rung of(String s) {
            if (s == null || s.isBlank()) return beginner;
            String k = s.strip().toLowerCase(Locale.ROOT).replace("as written", "written").replace("as-written", "written");
            for (Rung r : values()) if (r.name().equals(k)) return r;
            throw ProtocolError.invalidArgs("rung must be beginner, familiar or written.");
        }
    }

    public record Term(String term, String gloss) { }

    /**
     * @param of        the entry explained, or the entry a term was explained in ("" for none)
     * @param term      the term, "" when the reading is of an entry
     * @param text      the explanation, with [id] citations, [model] marks and "(the shelves do not say this)" marks
     * @param terms     words used that a reader at this rung may not know
     * @param grounding shelves — every paragraph cites material that supports it; thin — some paragraph does not;
     *                  none — the shelves do not explain this, and {@code offer} says what would
     * @param checked   paragraphs read back against their material; {@code unsupported} of them failed
     * @param offer     the research ask that would fill the gap (question, mode, sources, ceilings), or null
     */
    public record Reading(String of, String term, Rung rung, String text, List<Term> terms, String grounding, int checked, int unsupported,
                          String generatedAt, boolean cached, ObjectNode offer) {
        public ObjectNode json() {
            ObjectNode o = M.createObjectNode();
            o.put("of", of); o.put("term", term); o.put("rung", rung.name()); o.put("text", text);
            ArrayNode ts = o.putArray("terms"); for (Term t : terms) ts.addObject().put("term", t.term()).put("gloss", t.gloss());
            o.put("grounding", grounding); o.put("checked", checked); o.put("unsupported", unsupported);
            o.put("generated_at", generatedAt); o.put("cached", cached);
            o.put("is_record", false);   // a reading aid: not a finding, not an answer of the library's
            if (offer != null) o.set("offer", offer); else o.putNull("offer");
            return o;
        }
    }

    static final String NOT_ON_SHELVES = "NOT ON THE SHELVES";
    static final String UNCITED_MARK = "(not from the library)";

    /** The ask that would put a term on the shelves: depth, shelves and web, short ceilings when run now. */
    public static ObjectNode offerFor(String term, ObjectNode ctx) {
        ObjectNode o = M.createObjectNode();
        o.put("question", "What is \"" + term + "\"" + (ctx == null ? "" : ", as used in: " + Acquisitions.compress(ctx.path("title").asText(), 120)) + "? Define it, say where it comes from, and give one worked example.");
        o.put("mode", "depth"); o.put("sources", "both");
        o.put("quick", true); o.put("max_turns", QUICK_TURNS); o.put("max_minutes", QUICK_MINUTES);
        return o;
    }
    static final int QUICK_TURNS = org.researchzosho.Config.getInt("RESEARCHZOSHO_QUICK_TURNS", 12);
    static final int QUICK_MINUTES = org.researchzosho.Config.getInt("RESEARCHZOSHO_QUICK_MINUTES", 6);

    static final int MATERIAL_CAP = 12_000;
    static final String UNSUPPORTED_MARK = "(the sources do not say this)";
    private static final Pattern CITE = Pattern.compile("\\[((?:[FIA]-\\d{4}-[a-z0-9-]+)|\\d{4}-\\d{2}-\\d{2}-[0-9a-f]{6,}\\.md|https?://[^\\]\\s]+)\\]");

    private Explain() { }

    static Path dir(LibraryStore store) { return store.root().resolve("readings"); }

    // ---- an entry at a rung ----

    /** What a reading is doing right now, for a page or a terminal to show: one short phrase per stage. */
    public interface Progress { void at(String stage); Progress NONE = s -> { }; }

    public static Reading entry(LibraryStore store, Researcher.Drive drive, String id, Rung rung, boolean fresh) throws IOException {
        return entry(store, drive, id, rung, fresh, Progress.NONE);
    }

    public static Reading entry(LibraryStore store, Researcher.Drive drive, String id, Rung rung, boolean fresh, Progress progress) throws IOException {
        LibraryProtocol p = new LibraryProtocol(store);
        if (!LibraryStore.safeName(id)) throw ProtocolError.notFound("entry " + id);
        ObjectNode e = p.entry(id, p.kindOf(id), true);
        if (e == null) throw ProtocolError.notFound("entry " + id);
        if (rung == Rung.written) {
            // as written IS the entry: no model touches it
            return new Reading(id, "", rung, e.path("body").asText(), List.of(), "shelves", 0, 0, e.path("recorded_at").asText(""), true, null);
        }
        progress.at("reading the entry" + (e.path("findings").size() > 0 ? " and the " + e.path("findings").size() + " claims behind it" : e.path("sources").size() > 0 ? " and its " + e.path("sources").size() + " sources" : ""));
        Material mat = material(store, p, e);
        Path cache = dir(store).resolve(id).resolve(rung.name() + ".md");
        Reading hit = fresh ? null : cached(cache, mat.hash, id, "", rung);
        if (hit != null) return hit;
        if (drive == null) throw ProtocolError.unavailable("No model drive answers; a reading needs one the first time.");
        String prompt = "Explain the entry below for " + rung.audience + ". "
                + "Write 120 to 350 words in short paragraphs, as markdown without headings. Every paragraph ends with the ids of the "
                + "material it draws on, copied exactly in square brackets, like [" + id + "] or [F-0031-…] — the ids are given in the material. "
                + "Use ONLY the material: everything you write is a plainer way of saying what it says. When this reader would need a word or an idea "
                + "explained that the material does not explain, list it under Terms instead of explaining it yourself. "
                + "Keep what the entry says uncertain or disputed uncertain or disputed. "
                + "Then a line '## Terms' and three to six lines '- term — why this reader meets it here, in one sentence', for the words and ideas "
                + "this reader may need explained; the library will look each one up on its shelves.\n\n"
                + "MATERIAL:\n" + Fence.wrap("MATERIAL", mat.text) + "\n" + Fence.rule("MATERIAL");
        Reading r = generate(drive, prompt, mat, id, "", rung, progress);
        write(cache, r, mat.hash);
        return r;
    }

    // ---- a term, in the context of an entry ----

    public static Reading term(LibraryStore store, Researcher.Drive drive, String term, String inId, Rung rung, boolean fresh) throws IOException {
        return term(store, drive, term, inId, rung, fresh, Progress.NONE);
    }

    public static Reading term(LibraryStore store, Researcher.Drive drive, String term, String inId, Rung rung, boolean fresh, Progress progress) throws IOException {
        term = term.strip();
        if (term.length() < 2 || term.length() > 120) throw ProtocolError.invalidArgs("A term is between 2 and 120 characters.");
        LibraryProtocol p = new LibraryProtocol(store);
        ObjectNode ctx = null;
        if (inId != null && !inId.isBlank()) {
            if (!LibraryStore.safeName(inId)) throw ProtocolError.notFound("entry " + inId);
            ctx = p.entry(inId, p.kindOf(inId), true);
            if (ctx == null) throw ProtocolError.notFound("entry " + inId);
        }
        progress.at("looking for \"" + term + "\" in the library");
        Material mat = termMaterial(store, p, term, ctx);
        String slug = LibraryStore.slug(term);
        Path cache = dir(store).resolve(ctx == null ? "_terms" : inId).resolve("term-" + slug + "." + rung.name() + ".md");
        Reading hit = fresh ? null : cached(cache, mat.hash, ctx == null ? "" : inId, term, rung);
        if (hit != null) return hit;
        if (drive == null) throw ProtocolError.unavailable("No model drive answers; a reading needs one the first time.");
        String prompt = "Explain the term \"" + term + "\"" + (ctx == null ? "" : " as it is used in the entry \"" + ctx.path("title").asText() + "\"")
                + " for " + rung.audience + ". Write 60 to 200 words in short paragraphs, as markdown without headings. "
                + "Use ONLY the material: every paragraph ends with the ids of what it drew on, copied exactly in square brackets. "
                + "First decide whether the material EXPLAINS what the term is — a mention in passing is not an explanation. If it does not, "
                + "reply with exactly the words " + NOT_ON_SHELVES + " and nothing else; the library will offer to look it up. "
                + (ctx == null ? "" : "If it does, say in one sentence why the term matters for this entry. ")
                + "Then a line '## Terms' and up to four lines '- term — one sentence', for words you used that this reader may not know; "
                + "'## Terms' with nothing under it when there are none.\n\n"
                + "MATERIAL:\n" + Fence.wrap("MATERIAL", mat.text) + "\n" + Fence.rule("MATERIAL");
        Reading r = generate(drive, prompt, mat, ctx == null ? "" : inId, term, rung, progress);
        if ("none".equals(r.grounding())) {
            r = new Reading(r.of(), term, rung, "The library does not explain \"" + term + "\"" + (ctx == null ? "" : " as it is used here") + ".", List.of(), "none", 0, 0, r.generatedAt(), false, offerFor(term, ctx));
        }
        write(cache, r, mat.hash);
        return r;
    }

    // ---- material ----

    record Material(String text, String hash, java.util.Map<String, String> byId) { }

    private static Material material(LibraryStore store, LibraryProtocol p, ObjectNode e) throws IOException {
        StringBuilder sb = new StringBuilder();
        java.util.Map<String, String> byId = new java.util.LinkedHashMap<>();
        String id = e.path("id").asText();
        String head = "[" + id + "] " + e.path("kind").asText() + " · " + e.path("state").asText() + " · " + e.path("title").asText() + "\n" + cap(e.path("body").asText(), 7_000);
        byId.put(id, head);
        sb.append(head).append("\n\n");
        // an investigation's findings: what the write-up rests on
        for (JsonNode fid : e.path("findings")) {
            ObjectNode f = p.entry(fid.asText(), "finding", false);
            if (f == null) continue;
            String t = "[" + f.path("id").asText() + "] finding · " + f.path("state").asText() + " · " + f.path("title").asText() + "\n" + cap(f.path("body").asText(), 600) + sourcesOf(f);
            byId.put(f.path("id").asText(), t);
            if (sb.length() + t.length() > MATERIAL_CAP) break;
            sb.append(t).append("\n\n");
        }
        // a finding's captured sources: what it was read from
        if ("finding".equals(e.path("kind").asText())) {
            sb.append(sourcesOf(e)).append("\n");
            for (JsonNode s : e.path("sources")) {
                Path raw = p.rawFor(s.path("locator").asText());
                if (raw == null) continue;
                String[] r = RawCapture.read(raw);
                String rid = raw.getFileName().toString();   // a capture is cited by its file name, the same id /entry/ takes
                String t = "[" + rid + "] captured from " + s.path("locator").asText() + (r[1].isEmpty() ? "" : " · " + r[1]) + "\n" + cap(r[2], 1_500);
                byId.put(rid, t);
                if (sb.length() + t.length() > MATERIAL_CAP) break;
                sb.append(t).append("\n\n");
            }
        }
        return new Material(sb.toString(), sha(sb.toString()), byId);
    }

    private static Material termMaterial(LibraryStore store, LibraryProtocol p, String term, ObjectNode ctx) throws IOException {
        StringBuilder sb = new StringBuilder();
        java.util.Map<String, String> byId = new java.util.LinkedHashMap<>();
        if (ctx != null) {
            String id = ctx.path("id").asText();
            String around = around(ctx.path("body").asText(), term, 900);
            String t = "[" + id + "] the entry the term appears in · " + ctx.path("title").asText() + "\n" + (around.isEmpty() ? cap(ctx.path("body").asText(), 1_200) : around);
            byId.put(id, t); sb.append(t).append("\n\n");
        }
        for (var h : new LibrarianIndex(store).searchStrict(term, 6, null, null)) {
            if (ctx != null && h.id().equals(ctx.path("id").asText())) continue;
            ObjectNode e = p.entry(h.id(), h.kind(), true);   // the FULL body: a preview is the header, and the definition sits further down
            if (e == null || "retired".equals(e.path("state").asText())) continue;
            String body = e.path("body").asText();
            if ("raw".equals(h.kind())) {
                // a captured page is long and its definition is somewhere inside: window the FULL text, not the 500-char preview
                Path raw = LibraryStore.under(store.rawDir(), h.id());
                if (raw != null && Files.exists(raw)) body = RawCapture.read(raw)[2];
            }
            String around = around(body, term, "raw".equals(h.kind()) ? 1_800 : 600);
            String t = "[" + e.path("id").asText() + "] " + e.path("kind").asText() + " · " + e.path("state").asText() + " · " + e.path("title").asText() + "\n" + (around.isEmpty() ? cap(body, 500) : around);
            byId.put(e.path("id").asText(), t);
            if (sb.length() + t.length() > MATERIAL_CAP) break;
            sb.append(t).append("\n\n");
        }
        if (sb.length() == 0) sb.append("(the shelves hold nothing that mentions this term)\n");
        return new Material(sb.toString(), sha(sb.toString()), byId);
    }

    private static String sourcesOf(JsonNode f) {
        StringBuilder sb = new StringBuilder();
        for (JsonNode s : f.path("sources")) sb.append("\n  source: ").append(s.path("locator").asText()).append(s.path("why").asText().isEmpty() ? "" : " — " + s.path("why").asText());
        return sb.toString();
    }

    /**
     * Windows of the text around the term's occurrences, joined; "" when it does not occur. The term is matched
     * exactly first, then with hyphens and spaces interchangeable, then by its first two words; and of the
     * places it occurs, the ones sitting in PROSE win over a table of contents or a nav bar (measured on the
     * Wikipedia capture of the free energy principle, whose first hits were its contents list).
     */
    static String around(String text, String term, int window) {
        String t = term.toLowerCase(Locale.ROOT).strip();
        List<Pattern> tries = new ArrayList<>();
        tries.add(Pattern.compile(Pattern.quote(t)));
        tries.add(Pattern.compile(String.join("[\\s-]+", java.util.Arrays.stream(t.split("[\\s-]+")).map(Pattern::quote).toList())));
        String[] words = t.split("[\\s-]+");
        if (words.length > 2) tries.add(Pattern.compile(Pattern.quote(words[0]) + "[\\s-]+" + Pattern.quote(words[1])));
        String lower = text.toLowerCase(Locale.ROOT);
        for (Pattern p : tries) {
            List<int[]> spans = new ArrayList<>();
            Matcher m = p.matcher(lower);
            while (m.find() && spans.size() < 40) {
                int a = Math.max(0, m.start() - window / 2), b = Math.min(text.length(), m.end() + window / 2);
                spans.add(new int[]{a, b});
            }
            if (spans.isEmpty()) continue;
            spans.sort((x, y) -> Double.compare(prose(text, y[0], y[1]), prose(text, x[0], x[1])));
            List<int[]> picked = new ArrayList<>();
            for (int[] sp : spans) {
                boolean overlaps = false;
                for (int[] q : picked) if (sp[0] < q[1] && q[0] < sp[1]) overlaps = true;
                if (!overlaps) picked.add(sp);
                if (picked.size() == 3) break;
            }
            picked.sort((x, y) -> Integer.compare(x[0], y[0]));
            StringBuilder sb = new StringBuilder();
            for (int[] sp : picked) { if (sb.length() > 0) sb.append(" … "); sb.append(text, sp[0], sp[1]); }
            return sb.toString().strip();
        }
        return "";
    }

    /** How much like running prose a stretch is: long lines and letters score; a contents list or a nav bar does not. */
    static double prose(String text, int a, int b) {
        String w = text.substring(a, b);
        int letters = 0, newlines = 0, periods = 0;
        for (int i = 0; i < w.length(); i++) { char c = w.charAt(i); if (Character.isLetter(c)) letters++; else if (c == '\n') newlines++; else if (c == '.') periods++; }
        double lineLen = (double) w.length() / Math.max(1, newlines + 1);
        return (double) letters / Math.max(1, w.length()) + Math.min(1.0, lineLen / 200.0) + Math.min(0.5, periods / 10.0);
    }

    // ---- generation and the check ----

    private static Reading generate(Researcher.Drive drive, String prompt, Material mat, String of, String term, Rung rung, Progress progress) {
        if (org.researchzosho.Config.isOn("RESEARCHZOSHO_EXPLAIN_DEBUG", false)) System.err.println("---- explain prompt ----\n" + prompt + "\n---- end ----");
        progress.at(term.isEmpty() ? "writing it for " + (rung == Rung.familiar ? "someone who knows the field" : "a beginner") : "writing what the library says about \"" + term + "\"");
        ArrayNode msgs = M.createArrayNode();
        msgs.addObject().put("role", "user").put("content", prompt);
        String raw = drive.classify(msgs, 1_200);
        if (org.researchzosho.Config.isOn("RESEARCHZOSHO_EXPLAIN_DEBUG", false)) System.err.println("---- explain reply ----\n" + raw + "\n---- end ----");
        if (raw == null || raw.isBlank()) throw ProtocolError.unavailable("The model returned nothing for this reading.");
        if (raw.strip().toUpperCase(Locale.ROOT).startsWith(NOT_ON_SHELVES) || raw.strip().toUpperCase(Locale.ROOT).contains("\n" + NOT_ON_SHELVES)) {
            return new Reading(of, term, rung, "", List.of(), "none", 0, 0, Instant.now().toString(), false, null);
        }
        String body = raw, termsText = "";
        int cut = raw.indexOf("## Terms");
        if (cut >= 0) { body = raw.substring(0, cut); termsText = raw.substring(cut + "## Terms".length()); }
        body = body.strip();
        // read each cited paragraph back against what it cites
        StringBuilder out = new StringBuilder();
        int checked = 0, unsupported = 0, uncited = 0, cited = 0;
        String[] paras = body.split("\n\\s*\n");
        int total = 0; for (String para : paras) if (!para.isBlank()) total++;
        int seen = 0;
        for (String para : paras) {
            String pt = para.strip();
            if (pt.isEmpty()) continue;
            seen++;
            List<String> ids = new ArrayList<>();
            Matcher m = CITE.matcher(pt);
            while (m.find()) if (mat.byId.containsKey(m.group(1))) ids.add(m.group(1));
            if (!ids.isEmpty()) {
                cited++;
                StringBuilder src = new StringBuilder();
                for (String i : ids) src.append(mat.byId.get(i)).append("\n\n");
                String claim = pt.replaceAll("\\[[^\\]]+\\]", "").strip();
                progress.at("checking paragraph " + seen + " of " + total + " against its sources");
                checked++;
                if ("unsupported".equals(CiteCheck.judge(drive, claim, CiteCheck.excerpt(src.toString(), claim)))) {
                    unsupported++;
                    pt = pt + " " + UNSUPPORTED_MARK;
                }
            } else {
                // nothing on the shelves behind it: the reader is told so, and it counts against the reading
                pt = pt + " " + UNCITED_MARK;
                uncited++;
            }
            if (out.length() > 0) out.append("\n\n");
            out.append(pt);
        }
        List<Term> terms = new ArrayList<>();
        for (String line : termsText.split("\n")) {
            String l = line.strip();
            if (!l.startsWith("-")) continue;
            l = l.substring(1).strip();
            int dash = l.indexOf(" — "); if (dash < 0) dash = l.indexOf(" - "); if (dash < 0) dash = l.indexOf(": ");
            if (dash < 0) continue;
            String t = l.substring(0, dash).strip().replaceAll("^\\*\\*|\\*\\*$", ""), g = l.substring(dash + 3).strip();
            if (!t.isEmpty() && !g.isEmpty() && terms.size() < 6) terms.add(new Term(t, g));
        }
        String grounding = cited == 0 ? "none" : (uncited == 0 && unsupported == 0) ? "shelves" : "thin";
        return new Reading(of, term, rung, out.toString(), terms, grounding, checked, unsupported, Instant.now().toString(), false, null);
    }

    // ---- the cache ----

    private static void write(Path file, Reading r, String basis) throws IOException {
        Files.createDirectories(file.getParent());
        StringBuilder sb = new StringBuilder("---\nschema: 1\nkind: reading\n");
        sb.append("of: \"").append(r.of()).append("\"\nterm: \"").append(r.term().replace("\"", "'")).append("\"\nrung: ").append(r.rung().name()).append('\n');
        sb.append("basis: ").append(basis).append("\ngenerated_at: ").append(r.generatedAt()).append("\ngrounding: ").append(r.grounding()).append('\n');
        sb.append("checked: ").append(r.checked()).append("\nunsupported: ").append(r.unsupported()).append("\nis_record: false\n");
        if (r.offer() != null) sb.append("offer: ").append(r.offer().toString().replace("\n", " ")).append('\n');
        sb.append("---\n");
        sb.append(r.text()).append("\n\n## Terms\n");
        for (Term t : r.terms()) sb.append("- ").append(t.term()).append(" — ").append(t.gloss()).append('\n');
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
    }

    private static Reading cached(Path file, String basis, String of, String term, Rung rung) throws IOException {
        if (!Files.exists(file)) return null;
        String all = Files.readString(file, StandardCharsets.UTF_8);
        if (!all.startsWith("---\n")) return null;
        int end = all.indexOf("\n---\n", 4);
        if (end < 0) return null;
        java.util.Map<String, String> head = new java.util.HashMap<>();
        for (String line : all.substring(4, end).split("\n")) { int c = line.indexOf(':'); if (c > 0) head.put(line.substring(0, c).strip(), line.substring(c + 1).strip()); }
        if (!basis.equals(head.get("basis"))) return null;   // the material changed under it
        String body = all.substring(end + 5);
        String text = body, termsText = "";
        int cut = body.indexOf("\n## Terms");
        if (cut >= 0) { text = body.substring(0, cut); termsText = body.substring(cut + 9); }
        List<Term> terms = new ArrayList<>();
        for (String line : termsText.split("\n")) {
            String l = line.strip();
            int dash = l.indexOf(" — ");
            if (l.startsWith("- ") && dash > 0) terms.add(new Term(l.substring(2, dash).strip(), l.substring(dash + 3).strip()));
        }
        ObjectNode offer = null;
        if (head.containsKey("offer")) { try { JsonNode o = M.readTree(head.get("offer")); if (o.isObject()) offer = (ObjectNode) o; } catch (Exception ignored) { } }
        return new Reading(of, term, rung, text.strip(), terms, head.getOrDefault("grounding", "thin"),
                Integer.parseInt(head.getOrDefault("checked", "0")), Integer.parseInt(head.getOrDefault("unsupported", "0")),
                head.getOrDefault("generated_at", ""), true, offer);
    }

    // ---- small helpers ----

    static String cap(String s, int n) { return s == null ? "" : s.length() <= n ? s : s.substring(0, n) + " …"; }

    static String sha(String s) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8))).substring(0, 16); }
        catch (Exception e) { return Integer.toHexString(s.hashCode()); }
    }

    /** The drive the library's readings use: the configured one, when it answers. Tests point this elsewhere. */
    static volatile java.util.function.Supplier<Researcher.Drive> DRIVES = Explain::configuredDrive;
    public static Researcher.Drive drive() { return DRIVES.get(); }

    static Researcher.Drive configuredDrive() {
        String driveUrl = org.researchzosho.Config.get("RESEARCHZOSHO_DRIVE", "http://localhost:8200");
        String model = org.researchzosho.Config.get("RESEARCHZOSHO_MODEL", "local-model");
        return Crews.driveAnswers(driveUrl) ? Researcher.judgeDrive(driveUrl, model) : null;
    }
}
