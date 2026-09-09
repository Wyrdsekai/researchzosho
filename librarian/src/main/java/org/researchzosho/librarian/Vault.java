package org.researchzosho.librarian;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The library as a vault an editor can open: Obsidian, SoloMD, SilverBullet, anything that reads a
 * folder of markdown with YAML frontmatter and {@code [[wikilinks]]}. The records under the library
 * root are the truth and keep their own strict format; the vault is a clean view beside them,
 * {@code <library>-vault}, written by {@code researchzosho vault} and refreshed by the night work.
 *
 * <p>One way, on purpose. Editing a note in the vault changes nothing in the library: accepting,
 * disputing and retiring are done at the inbox, or through the editor's agent panel over MCP, which
 * is the same door. The vault says so in its Home note. Private graph nodes are not written.
 *
 * <p>Every string is quoted in the frontmatter, so a title with a colon or a quote never breaks the
 * editor's YAML parser. Files are rewritten only when their content changed, so an editor watching the
 * folder is not woken every night for nothing, and files the vault wrote before and would not write now
 * are removed (a manifest remembers what is ours; a person's own notes in the folder are never touched).
 */
public final class Vault {

    static final Pattern FINDING_ID = Pattern.compile("\\bF-\\d{4}-[a-z0-9-]+\\b");
    static final Pattern INVESTIGATION_ID = Pattern.compile("\\bI-\\d{4}-[a-z0-9-]+\\b");
    static final int SOURCE_CHARS = 200_000;

    private Vault() { }

    /** Where the vault goes: {@code RESEARCHZOSHO_VAULT}, else a sibling of the library. */
    public static Path dir(LibraryStore store) {
        String v = org.researchzosho.Config.get("RESEARCHZOSHO_VAULT");
        if (v != null && !v.isBlank()) return Path.of(v);
        Path root = store.root().toAbsolutePath().normalize();
        return root.resolveSibling(root.getFileName() + "-vault");
    }

    static Path manifest(Path vault) { return vault.resolve(".researchzosho-vault.tsv"); }

    public record Outcome(Path dir, int written, int unchanged, int removed) { }

    /** Refresh the vault if one exists; the night work's step. */
    /** The newest change anywhere in the library's record, as a file time: when it moves, the vault is behind. Cheap: one walk. */
    public static long newest(LibraryStore store) {
        long max = 0;
        for (Path d : new Path[]{store.findingsDir(), store.investigationsDir(), store.articlesDir(), store.rawDir(), store.root().resolve("catalog"), store.root().resolve("frontier")}) {
            if (!Files.isDirectory(d)) continue;
            try (var s = Files.walk(d, 2)) {
                for (Path p : (Iterable<Path>) s::iterator) {
                    try { long m = Files.getLastModifiedTime(p).toMillis(); if (m > max) max = m; } catch (IOException ignored) { }
                }
            } catch (IOException ignored) { }
        }
        return max;
    }

    /** Whether the person has asked for a vault at all: the folder exists. A vault is never created unasked. */
    public static boolean exists(LibraryStore store) { return Files.exists(manifest(dir(store))); }

    /** Refresh when the library changed since {@code seen}; returns the new mark, or {@code seen} when nothing was to do. */
    public static long followUp(LibraryStore store, long seen) {
        if (!exists(store)) return seen;
        long now = newest(store);
        if (now == seen) return seen;
        try { refresh(store); } catch (IOException e) { return seen; }
        return now;
    }

    public static String refresh(LibraryStore store) throws IOException {
        Path v = dir(store);
        if (!Files.exists(manifest(v))) return "no vault (researchzosho vault makes one)";
        Outcome o = generate(store, v);
        return o.written() + " written, " + o.unchanged() + " unchanged, " + o.removed() + " removed at " + v;
    }

    public static Outcome generate(LibraryStore store, Path vault) throws IOException {
        Files.createDirectories(vault);
        Map<String, String> ours = new LinkedHashMap<>();   // relative path → sha256 of what we wrote
        Map<String, String> before = readManifest(vault);
        Map<String, String> notes = new TreeMap<>();      // relative path → content

        List<Finding> findings = store.scanFindings().findings();
        List<Investigation> investigations = new ArrayList<>();
        if (Files.isDirectory(store.investigationsDir())) {
            try (var s = Files.list(store.investigationsDir())) {
                for (Path p : s.filter(p -> p.toString().endsWith(".md")).sorted().toList()) {
                    try { investigations.add(Investigation.parse(Files.readString(p, StandardCharsets.UTF_8))); } catch (Exception ignored) { }
                }
            }
        }
        Map<String, List<String>> citedBy = new LinkedHashMap<>();
        for (Investigation i : investigations) for (String f : i.findings()) citedBy.computeIfAbsent(f, k -> new ArrayList<>()).add(i.id());
        Map<String, String> vocab = Cataloger.vocabulary(store);

        // sources: one note per capture, by the capture's file stem
        Map<String, String> sourceNoteByLocator = new LinkedHashMap<>();
        if (Files.isDirectory(store.rawDir())) {
            try (var s = Files.list(store.rawDir())) {
                for (Path p : s.filter(p -> p.toString().endsWith(".md")).sorted().toList()) {
                    String[] r = RawCapture.read(p);
                    String stem = p.getFileName().toString().replaceAll("\\.md$", "");
                    String title = r[1].isBlank() ? r[0] : r[1];
                    StringBuilder sb = new StringBuilder("---\n");
                    kv(sb, "type", "source"); kv(sb, "id", stem); kv(sb, "title", title); kv(sb, "url", r[0]);
                    kv(sb, "aliases", List.of(stem));
                    sb.append("---\n# ").append(title).append("\n\n");
                    if (!r[0].isBlank()) sb.append(r[0]).append("\n\n");
                    sb.append("> A captured copy, kept by the library. The record is `raw/").append(stem).append(".md`.\n\n");
                    String text = r[2];
                    sb.append(text.length() > SOURCE_CHARS ? text.substring(0, SOURCE_CHARS) + "\n\n…(cut at " + SOURCE_CHARS + " characters)\n" : text);
                    notes.put("Sources/" + stem + ".md", sb.toString());
                    if (!r[0].isBlank()) sourceNoteByLocator.put(r[0], stem);
                }
            }
        }

        // findings
        Map<String, List<String>> bySubject = new TreeMap<>();
        for (Finding f : findings) {
            StringBuilder sb = new StringBuilder("---\n");
            kv(sb, "type", "finding"); kv(sb, "id", f.id()); kv(sb, "title", f.title()); kv(sb, "state", f.state().name());
            kv(sb, "claim_type", f.claimType().name()); kv(sb, "confidence", f.confidence().name());
            kv(sb, "tags", f.subjects()); kv(sb, "writer", f.writer()); kv(sb, "recorded", f.recordedAt()); kv(sb, "valid_as_of", f.validAsOf());
            List<String> urls = new ArrayList<>(); for (Finding.Source s : f.sources()) urls.add(s.locator());
            kv(sb, "sources", urls);
            if (f.triple() != null) {
                kv(sb, "subject", f.triple().subject()); kv(sb, "predicate", f.triple().predicate()); kv(sb, "object", f.triple().object());
                kv(sb, "related_to", List.of("[[Things/" + fileSafe(f.triple().object()) + "]]"));
            }
            kv(sb, "aliases", List.of(f.id()));
            sb.append("---\n# ").append(f.title()).append("\n\n");
            sb.append("*").append(f.state()).append(" · ").append(f.claimType()).append(" · confidence ").append(f.confidence()).append("*\n\n");
            sb.append(linkIds(f.body().strip())).append("\n\n");
            if (!f.sources().isEmpty()) {
                sb.append("## Sources\n\n");
                for (Finding.Source s : f.sources()) {
                    String stem = sourceNoteByLocator.get(s.locator());
                    sb.append("- ").append(stem != null ? "[[Sources/" + stem + "|" + shortTitle(s) + "]] " : "").append(s.locator());
                    if (!s.edition().isBlank() && !s.edition().equals("n/a")) sb.append(" (").append(s.edition()).append(")");
                    sb.append('\n');
                }
                sb.append('\n');
            }
            if (f.triple() != null) sb.append("## Connection\n\n[[Things/").append(fileSafe(f.triple().subject())).append("]] **").append(f.triple().predicate()).append("** [[Things/").append(fileSafe(f.triple().object())).append("]]\n\n");
            if (!f.notes().isEmpty()) { sb.append("## Notes\n\n"); for (Finding.Note n : f.notes()) sb.append("- ").append(n.date()).append(" ").append(n.kind()).append(" (").append(n.by()).append("): ").append(n.text()).append('\n'); sb.append('\n'); }
            List<String> cites = citedBy.getOrDefault(f.id(), List.of());
            if (!cites.isEmpty()) { sb.append("## Cited by\n\n"); for (String c : cites) sb.append("- [[Investigations/").append(c).append("]]\n"); sb.append('\n'); }
            for (String subj : f.subjects()) { sb.append("#").append(subj).append(' '); bySubject.computeIfAbsent(subj, k -> new ArrayList<>()).add(f.id()); }
            notes.put("Findings/" + f.id() + ".md", sb.toString().stripTrailing() + "\n");
        }

        // investigations
        for (Investigation i : investigations) {
            StringBuilder sb = new StringBuilder("---\n");
            kv(sb, "type", "investigation"); kv(sb, "id", i.id()); kv(sb, "title", i.title()); kv(sb, "state", i.state().name());
            kv(sb, "writer", i.writer()); kv(sb, "recorded", i.recordedAt());
            List<String> links = new ArrayList<>(); for (String f : i.findings()) links.add("[[Findings/" + f + "]]");
            kv(sb, "findings", links); kv(sb, "open_questions", i.open()); kv(sb, "aliases", List.of(i.id()));
            sb.append("---\n").append(linkIds(i.body().strip())).append('\n');
            notes.put("Investigations/" + i.id() + ".md", sb.toString());
        }

        // subjects
        for (Map.Entry<String, List<String>> e : bySubject.entrySet()) {
            StringBuilder sb = new StringBuilder("---\n");
            kv(sb, "type", "subject"); kv(sb, "id", e.getKey()); kv(sb, "title", vocab.getOrDefault(e.getKey(), e.getKey()).replaceAll("\\s*\\|.*$", ""));
            sb.append("---\n# ").append(e.getKey()).append("\n\n").append(vocab.getOrDefault(e.getKey(), "")).append("\n\n## Findings\n\n");
            for (String id : e.getValue()) sb.append("- [[Findings/").append(id).append("]]\n");
            notes.put("Subjects/" + fileSafe(e.getKey()) + ".md", sb.toString());
        }

        // things: the graph's nodes, private ones never
        try {
            Graph g = Graph.build(store);
            Map<String, List<Graph.Edge>> touching = new LinkedHashMap<>();
            for (Graph.Edge ed : g.edges()) { touching.computeIfAbsent(ed.from(), k -> new ArrayList<>()).add(ed); touching.computeIfAbsent(ed.to(), k -> new ArrayList<>()).add(ed); }
            for (Graph.Node n : g.nodes()) {
                if (n.privateNode()) continue;
                StringBuilder sb = new StringBuilder("---\n");
                kv(sb, "type", n.kind() == null || n.kind().isBlank() ? "thing" : n.kind()); kv(sb, "id", n.id()); kv(sb, "title", n.label());
                if (n.aliases() != null && !n.aliases().isEmpty()) kv(sb, "aliases", n.aliases());
                if (n.wikidata() != null && !n.wikidata().isBlank()) kv(sb, "wikidata", n.wikidata());
                sb.append("---\n# ").append(n.label()).append("\n\n");
                List<Graph.Edge> es = touching.getOrDefault(n.id(), List.of());
                if (!es.isEmpty()) {
                    sb.append("## Connections\n\n");
                    for (Graph.Edge ed : es) {
                        sb.append("- [[Things/").append(fileSafe(ed.from())).append("]] **").append(ed.predicate()).append("** [[Things/").append(fileSafe(ed.to())).append("]]");
                        if (ed.findingId() != null && !ed.findingId().isBlank()) sb.append(" — [[Findings/").append(ed.findingId()).append("]]");
                        if (ed.disputed()) sb.append(" (disputed)");
                        sb.append('\n');
                    }
                }
                notes.put("Things/" + fileSafe(n.id()) + ".md", sb.toString());
            }
        } catch (Exception ignored) {
            // no graph yet: no Things
        }

        // the front door and the inbox
        long drafts = findings.stream().filter(f -> f.state() == Finding.State.draft).count();
        long disputed = findings.stream().filter(f -> f.state() == Finding.State.disputed).count();
        LibraryStore.Identity id = store.identity();
        StringBuilder home = new StringBuilder("---\n"); kv(home, "type", "home"); kv(home, "title", id.name());
        home.append("---\n# ").append(id.name()).append("\n\n")
            .append("This folder is a view of the ResearchZosho library at `").append(store.root()).append("`, written for an editor: ")
            .append("Obsidian, SoloMD, or anything that reads markdown with links. It is rewritten as the library changes, and by `researchzosho vault`.\n\n")
            .append("Editing a note here changes nothing in the library. To accept, dispute or retire a claim, use the inbox (`researchzosho inbox`) ")
            .append("or the editor's agent panel connected to the library over MCP. Your own notes in this folder are left alone.\n\n")
            .append("- [[Inbox]] — ").append(drafts).append(" draft, ").append(disputed).append(" disputed\n")
            .append("- Findings: ").append(findings.size()).append(" · Investigations: ").append(investigations.size()).append(" · Sources: ").append(sourceNoteByLocator.size()).append(" · Subjects: ").append(bySubject.size()).append('\n')
            .append("- Folders: `Findings/`, `Investigations/`, `Sources/`, `Subjects/`, `Things/` (people, places, works and the rest, with what connects them)\n");
        notes.put("Home.md", home.toString());
        StringBuilder inbox = new StringBuilder("---\n"); kv(inbox, "type", "inbox"); kv(inbox, "title", "Inbox");
        inbox.append("---\n# Inbox\n\nWaiting for your decision. Decide at the command line: `researchzosho accept <id>` · `dispute <id> <why>` · `retire <id>`.\n\n## Drafts\n\n");
        for (Finding f : findings) if (f.state() == Finding.State.draft) inbox.append("- [[Findings/").append(f.id()).append("|").append(f.title()).append("]]\n");
        inbox.append("\n## Disputed\n\n");
        for (Finding f : findings) if (f.state() == Finding.State.disputed) inbox.append("- [[Findings/").append(f.id()).append("|").append(f.title()).append("]]\n");
        notes.put("Inbox.md", inbox.toString());

        // write what changed, remove what is stale, remember what is ours
        int written = 0, unchanged = 0, removed = 0;
        for (Map.Entry<String, String> e : notes.entrySet()) {
            String h = sha256(e.getValue());
            ours.put(e.getKey(), h);
            Path p = vault.resolve(e.getKey());
            if (h.equals(before.get(e.getKey())) && Files.exists(p)) { unchanged++; continue; }
            Files.createDirectories(p.getParent());
            Files.writeString(p, e.getValue(), StandardCharsets.UTF_8);
            written++;
        }
        for (String old : before.keySet()) {
            if (ours.containsKey(old)) continue;
            Path p = vault.resolve(old);
            if (Files.exists(p) && sha256(Files.readString(p, StandardCharsets.UTF_8)).equals(before.get(old))) { Files.delete(p); removed++; }   // ours and untouched: gone
        }
        StringBuilder m = new StringBuilder("# what researchzosho wrote here: path \\t sha256 — a note a person changed is left alone\n");
        for (Map.Entry<String, String> e : ours.entrySet()) m.append(e.getKey()).append('\t').append(e.getValue()).append('\n');
        Files.writeString(manifest(vault), m.toString(), StandardCharsets.UTF_8);
        return new Outcome(vault, written, unchanged, removed);
    }

    static Map<String, String> readManifest(Path vault) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        if (!Files.exists(manifest(vault))) return out;
        for (String line : Files.readAllLines(manifest(vault), StandardCharsets.UTF_8)) {
            if (line.startsWith("#")) continue;
            int t = line.indexOf('\t');
            if (t > 0) out.put(line.substring(0, t), line.substring(t + 1));
        }
        return out;
    }

    static String shortTitle(Finding.Source s) {
        try { String h = java.net.URI.create(s.locator()).getHost(); return h == null ? s.locator() : h; } catch (Exception e) { return s.locator(); }
    }

    /** Bare ids in prose become links to their notes. */
    static String linkIds(String text) {
        Matcher m = FINDING_ID.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) m.appendReplacement(sb, Matcher.quoteReplacement("[[Findings/" + m.group() + "]]"));
        m.appendTail(sb);
        String t = sb.toString();
        Matcher m2 = INVESTIGATION_ID.matcher(t);
        StringBuilder sb2 = new StringBuilder();
        while (m2.find()) m2.appendReplacement(sb2, Matcher.quoteReplacement("[[Investigations/" + m2.group() + "]]"));
        m2.appendTail(sb2);
        return sb2.toString().replace("[[Findings/[[Findings/", "[[Findings/").replace("]]]]", "]]");
    }

    static String fileSafe(String s) {
        String t = s.replaceAll("[\\\\/:*?\"<>|#^\\[\\]]", " ").replaceAll("\\s+", " ").strip();
        return t.isEmpty() ? "unnamed" : t;
    }

    // ---- YAML that every editor parses: quoted scalars, block lists ----

    static void kv(StringBuilder sb, String key, String value) {
        sb.append(key).append(": ").append(q(value == null ? "" : value)).append('\n');
    }

    static void kv(StringBuilder sb, String key, Collection<String> values) {
        if (values == null || values.isEmpty()) { sb.append(key).append(": []\n"); return; }
        sb.append(key).append(":\n");
        for (String v : values) sb.append("  - ").append(q(v)).append('\n');
    }

    static String q(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", "") + "\"";
    }

    static String sha256(String s) {
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            StringBuilder sb = new StringBuilder();
            for (byte b : d.digest(s.getBytes(StandardCharsets.UTF_8))) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
