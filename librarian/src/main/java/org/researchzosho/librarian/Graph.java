package org.researchzosho.librarian;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The library's graph. Nodes are the things findings are ABOUT — a person, a place, an event, a
 * document, an organisation, a work, a concept — and edges are the findings themselves: every finding
 * that carries a triple {@code subject | predicate | object} is an edge from one node to another, with
 * the finding's sources, state, confidence and dispute history riding on it. Nothing is drawn; the
 * graph is what the shelves already say, made walkable.
 *
 * <p>The core knows no field. A node's {@code kind} is an open word (the seven above are conventions,
 * not a schema), a predicate is a vocabulary term with equivalences like any subject, and two nodes
 * become one only by the person's act ({@link #merge}) — the "same name, same dates" fallacy is why
 * identity is never inferred silently; the nightly crew only PROPOSES. Profiles ({@link Profile})
 * add predicates, kinds, importers and rules on top; none of that lives here.
 *
 * <p>On disk, under {@code catalog/graph/}: {@code nodes.md} (curated: kind, aliases, private,
 * external ids — a {@link Vocabulary}), {@code predicates.md} (the predicate vocabulary), and
 * {@code merges.tsv} (from, to, by, date). Everything else is computed from the findings on demand.
 */
public final class Graph {

    public static final List<String> CORE_KINDS = List.of("person", "place", "event", "document", "organisation", "work", "concept");

    public record Node(String id, String kind, String label, List<String> aliases, boolean privateNode, String wikidata, int degree) { }
    public record Edge(String from, String predicate, String to, String findingId, String state, String confidence, boolean disputed) { }
    public record Neighbourhood(Node focus, List<Node> nodes, List<Edge> edges, List<String> openQuestions) { }

    private final LibraryStore store;
    private final Vocabulary nodes;        // curated node ids: slug = node id; also = other names; wikidata
    private final Vocabulary predicates;
    private final Map<String, String> merges = new LinkedHashMap<>();   // from id → to id (transitively applied)
    private final Map<String, String> kinds = new HashMap<>();
    private final Set<String> privateIds = new HashSet<>();

    // computed
    private final Map<String, Node> byId = new LinkedHashMap<>();
    private final List<Edge> edges = new ArrayList<>();
    private final Map<String, List<Edge>> adjacency = new HashMap<>();

    private Graph(LibraryStore store, Vocabulary nodes, Vocabulary predicates) {
        this.store = store; this.nodes = nodes; this.predicates = predicates;
    }

    public static Path dir(LibraryStore store) { return store.root().resolve("catalog").resolve("graph"); }
    static Path nodesFile(LibraryStore store) { return dir(store).resolve("nodes.md"); }
    static Path predicatesFile(LibraryStore store) { return dir(store).resolve("predicates.md"); }
    static Path mergesFile(LibraryStore store) { return dir(store).resolve("merges.tsv"); }

    static final String NODES_PREAMBLE = "# Graph nodes — the things the findings are about\n\n"
            + "One per line: `- <id> — <kind>: <label> | also: other names | wikidata: Qn`. Curated here; every other\n"
            + "node is computed from the findings' triples. `private` in the kind marks a node the desk never shows\n"
            + "to another patron (a living person, say). Merges are the person's act: `researchzosho graph merge`.\n\n";
    static final String PREDICATES_PREAMBLE = "# Graph predicates — the relations the findings assert\n\n"
            + "One per line: `- <slug> — <description> | also: other wordings`. A finding's triple predicate is\n"
            + "resolved against these before it becomes an edge; unknown predicates are kept as written.\n\n";

    /** Load the curated files and compute the graph from every non-retired finding with a triple. */
    public static Graph build(LibraryStore store) throws IOException {
        Graph g = new Graph(store, Vocabulary.read(nodesFile(store)), Vocabulary.read(predicatesFile(store)));
        for (Vocabulary.Term t : g.nodes.terms().values()) {
            String d = t.description();
            String kind = "concept";
            if (d.contains(":")) kind = d.substring(0, d.indexOf(':')).strip();
            boolean priv = kind.contains("private");
            kind = kind.replace("private", "").replace(",", "").strip();
            g.kinds.put(t.slug(), kind.isEmpty() ? "concept" : kind);
            if (priv) g.privateIds.add(t.slug());
        }
        if (Files.exists(mergesFile(store))) {
            for (String line : Files.readAllLines(mergesFile(store), StandardCharsets.UTF_8)) {
                String[] p = line.split("\t");
                if (p.length >= 2) g.merges.put(p[0], p[1]);
            }
        }
        for (Finding f : store.scanFindings().findings()) {
            if (f.state() == Finding.State.retired || f.triple() == null) continue;
            String from = g.nodeIdOf(f.triple().subject());
            String to = g.nodeIdOf(f.triple().object());
            if (from.isEmpty() || to.isEmpty()) continue;
            String pred = g.predicateOf(f.triple().predicate());
            boolean disputed = f.state() == Finding.State.disputed;
            Edge e = new Edge(from, pred, to, f.id(), f.state().name(), f.confidence().name(), disputed);
            g.edges.add(e);
            g.adjacency.computeIfAbsent(from, k -> new ArrayList<>()).add(e);
            g.adjacency.computeIfAbsent(to, k -> new ArrayList<>()).add(e);
            g.touch(from, f.triple().subject());
            g.touch(to, f.triple().object());
        }
        // SUBJECTS ON THE MAP: a shelf label is a hub — every name a claim under it mentions is filed under it. Nodes
        // "subject:<slug>" (kind subject), one "is filed under" edge per name and subject, backed by the first finding that
        // filed it; the label is the vocabulary's description, so the map reads "Japanese ASR/alignment specifics", not the slug.
        Vocabulary subjects = Files.exists(store.subjectsFile()) ? Vocabulary.read(store.subjectsFile()) : null;
        Set<String> filed = new HashSet<>();
        for (Finding f : store.scanFindings().findings()) {
            if (f.state() == Finding.State.retired || f.triple() == null || f.subjects().isEmpty()) continue;
            for (String slug : f.subjects()) {
                String sid = "subject:" + slug;
                if (!g.byId.containsKey(sid)) {
                    Vocabulary.Term t = subjects == null ? null : subjects.get(slug);
                    String label = t == null || t.description().isBlank() ? slug : t.description();
                    g.byId.put(sid, new Node(sid, "subject", label, List.of(slug), false, "", 0));
                    g.subjectIds.put(Vocabulary.norm(label), sid); g.subjectIds.put(Vocabulary.norm(slug), sid); g.subjectIds.put(slug, sid);
                }
                for (String name : new String[]{f.triple().subject(), f.triple().object()}) {
                    String nid = g.nodeIdOf(name);
                    if (nid.isEmpty() || !filed.add(nid + "|" + sid)) continue;
                    Edge e = new Edge(nid, "is filed under", sid, f.id(), f.state().name(), f.confidence().name(), false);
                    g.edges.add(e);
                    g.adjacency.computeIfAbsent(nid, k -> new ArrayList<>()).add(e);
                    g.adjacency.computeIfAbsent(sid, k -> new ArrayList<>()).add(e);
                    g.touch(nid, name);
                    Node sn = g.byId.get(sid);
                    g.byId.put(sid, new Node(sn.id(), sn.kind(), sn.label(), sn.aliases(), false, "", sn.degree() + 1));
                }
            }
        }
        return g;
    }

    private void touch(String id, String asWritten) {
        Node n = byId.get(id);
        if (n == null) {
            Vocabulary.Term t = nodes.get(id);
            List<String> aliases = new ArrayList<>(t == null ? List.of() : t.also());
            String label = t != null && t.description().contains(":") ? t.description().substring(t.description().indexOf(':') + 1).strip() : (t != null && !t.description().isBlank() ? t.description() : asWritten);
            n = new Node(id, kinds.getOrDefault(id, "concept"), label, aliases, privateIds.contains(id), t == null ? "" : t.wikidata(), 0);
        }
        byId.put(id, new Node(n.id(), n.kind(), n.label(), n.aliases(), n.privateNode(), n.wikidata(), n.degree() + 1));
    }

    /** The node id a name resolves to: the curated vocabulary first, then the normalised name itself; merges applied. */
    /** A subject's label or slug → its node id, so the map can be asked for "Japanese keigo" or speech--japanese. */
    private final Map<String, String> subjectIds = new HashMap<>();

    public String nodeIdOf(String name) {
        if (name != null && name.startsWith("subject:")) return name;
        String bySubject = subjectIds.get(Vocabulary.norm(name == null ? "" : name));
        if (bySubject != null && !byId.containsKey(Vocabulary.norm(name))) return bySubject;
        String id = nodes.resolve(name);
        if (id == null) id = Vocabulary.norm(name);
        int guard = 0;
        while (merges.containsKey(id) && guard++ < 50) id = merges.get(id);
        return id;
    }

    public String predicateOf(String name) {
        String p = predicates.resolve(name);
        return p != null ? p : Vocabulary.norm(name);
    }

    public Node node(String id) { return byId.get(id); }
    public List<Node> nodes() { return new ArrayList<>(byId.values()); }
    public List<Edge> edges() { return new ArrayList<>(edges); }
    public Vocabulary predicates() { return predicates; }
    public Vocabulary curated() { return nodes; }

    /**
     * The neighbourhood of a focus (a node name, or an entry id whose triple names the node), out to
     * {@code depth} hops, at most {@code k} nodes, nearest and best-connected first. Private nodes are
     * omitted unless {@code showPrivate}. Open frontier lines that name any node in view ride along.
     */
    public Neighbourhood around(String focus, int depth, int k, boolean showPrivate) throws IOException {
        String id = nodeIdOf(focus);
        if (!byId.containsKey(id)) {
            // an entry id: use its triple's subject
            Finding f = store.finding(focus);
            if (f != null && f.triple() != null) id = nodeIdOf(f.triple().subject());
        }
        Node start = byId.get(id);
        if (start == null || (start.privateNode() && !showPrivate)) return new Neighbourhood(null, List.of(), List.of(), List.of());
        Map<String, Integer> dist = new LinkedHashMap<>();
        ArrayDeque<String> q = new ArrayDeque<>();
        dist.put(id, 0); q.add(id);
        while (!q.isEmpty()) {
            String cur = q.poll();
            int d = dist.get(cur);
            if (d >= depth) continue;
            for (Edge e : adjacency.getOrDefault(cur, List.of())) {
                String other = e.from().equals(cur) ? e.to() : e.from();
                Node on = byId.get(other);
                if (on == null || (on.privateNode() && !showPrivate)) continue;
                if (!dist.containsKey(other)) { dist.put(other, d + 1); q.add(other); }
            }
        }
        List<Node> chosen = new ArrayList<>();
        dist.entrySet().stream()
                .sorted((a, b) -> a.getValue().equals(b.getValue()) ? Integer.compare(byId.get(b.getKey()).degree(), byId.get(a.getKey()).degree()) : Integer.compare(a.getValue(), b.getValue()))
                .limit(Math.max(1, k))
                .forEach(en -> chosen.add(byId.get(en.getKey())));
        Set<String> ids = new HashSet<>();
        for (Node n : chosen) ids.add(n.id());
        List<Edge> es = new ArrayList<>();
        for (Edge e : edges) if (ids.contains(e.from()) && ids.contains(e.to())) es.add(e);
        List<String> open = new ArrayList<>();
        for (Frontier.Line l : Frontier.read(store)) {
            if (!l.open()) continue;
            String t = Vocabulary.norm(l.text());
            for (Node n : chosen) if (t.contains(Vocabulary.norm(n.label())) && open.size() < 10) { open.add(l.text()); break; }
        }
        return new Neighbourhood(start, chosen, es, open);
    }

    // ---- the person's acts ----

    /** Two nodes are one: {@code from} folds into {@code to}; its names become aliases of {@code to}. Logged. */
    public static synchronized void merge(LibraryStore store, String from, String to, String by) throws IOException {
        Graph g = build(store);
        String a = g.nodeIdOf(from), b = g.nodeIdOf(to);
        if (a.equals(b)) return;
        Files.createDirectories(dir(store));
        Files.writeString(mergesFile(store), a + "\t" + b + "\t" + by + "\t" + LocalDate.now() + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        Vocabulary nodes = Vocabulary.read(nodesFile(store));
        Node bn = g.node(b);
        nodes.alias(b, bn == null ? "" : bn.kind() + ": " + bn.label(), List.of(from));
        nodes.write(nodesFile(store), NODES_PREAMBLE);
        Changes.append(store, "node", b, "merged", a + " → " + b + " by " + by);
        store.circulate("graph-merge", a + " → " + b + " by " + by);
    }

    /** Set a node's kind (and privacy) by hand. */
    public static synchronized void setKind(LibraryStore store, String name, String kind, Boolean privateNode) throws IOException {
        Graph g = build(store);
        String id = g.nodeIdOf(name);
        Vocabulary nodes = Vocabulary.read(nodesFile(store));
        Vocabulary.Term t = nodes.get(id);
        Node n = g.node(id);
        boolean priv = privateNode != null ? privateNode : (n != null && n.privateNode());
        String k = kind != null ? kind : (n != null ? n.kind() : "concept");
        String label = n != null ? n.label() : name;
        nodes.put(new Vocabulary.Term(id, k + (priv ? ", private" : "") + ": " + label, t == null ? List.of() : t.also(), t == null ? "" : t.wikidata()));
        nodes.write(nodesFile(store), NODES_PREAMBLE);
    }

    public static synchronized void alias(LibraryStore store, String name, List<String> variants) throws IOException {
        Graph g = build(store);
        String id = g.nodeIdOf(name);
        Vocabulary nodes = Vocabulary.read(nodesFile(store));
        Node n = g.node(id);
        nodes.alias(id, n == null ? "concept: " + name : n.kind() + (n.privateNode() ? ", private" : "") + ": " + n.label(), variants);
        nodes.write(nodesFile(store), NODES_PREAMBLE);
    }

    public static synchronized void link(LibraryStore store, String name, String wikidata) throws IOException {
        Graph g = build(store);
        String id = g.nodeIdOf(name);
        Vocabulary nodes = Vocabulary.read(nodesFile(store));
        if (nodes.get(id) == null) { Node n = g.node(id); nodes.put(new Vocabulary.Term(id, (n == null ? "concept" : n.kind()) + ": " + (n == null ? name : n.label()), List.of(), "")); }
        nodes.link(id, wikidata);
        nodes.write(nodesFile(store), NODES_PREAMBLE);
    }

    /** Declare (or extend) a predicate's equivalences — the profiles seed theirs through this. */
    public static synchronized void predicate(LibraryStore store, String slug, String description, List<String> also) throws IOException {
        Vocabulary v = Vocabulary.read(predicatesFile(store));
        v.alias(slug, description, also);
        v.write(predicatesFile(store), PREDICATES_PREAMBLE);
    }

    // ---- the crew ----

    /**
     * Identity PROPOSALS for the inbox, never applied: two nodes whose names differ only by case,
     * diacritics, punctuation or word order, or that share every token but one, are probably one
     * node — and probably is not certainly, so the person decides. Written to catalog/graph/proposals.md.
     */
    public static String propose(LibraryStore store) throws IOException {
        Graph g = build(store);
        List<Node> ns = g.nodes();
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < ns.size(); i++) {
            for (int j = i + 1; j < ns.size(); j++) {
                Node a = ns.get(i), b = ns.get(j);
                if (!a.kind().equals(b.kind()) && !a.kind().equals("concept") && !b.kind().equals("concept")) continue;
                String ka = key(a.label()), kb = key(b.label());
                boolean same = ka.equals(kb);
                if (!same) {
                    Set<String> ta = new LinkedHashSet<>(List.of(ka.split(" "))), tb = new LinkedHashSet<>(List.of(kb.split(" ")));
                    if (ta.size() >= 2 && tb.size() >= 2) {
                        Set<String> inter = new HashSet<>(ta); inter.retainAll(tb);
                        Set<String> uni = new HashSet<>(ta); uni.addAll(tb);
                        same = inter.size() >= uni.size() - 1 && inter.size() >= 2;
                    }
                }
                if (same && lines.size() < 200) lines.add("- " + a.id() + " ≈ " + b.id() + "  (" + a.label() + " / " + b.label() + ") → `researchzosho graph merge \"" + a.id() + "\" \"" + b.id() + "\"`");
            }
        }
        Files.createDirectories(dir(store));
        Files.writeString(dir(store).resolve("proposals.md"), "# Graph — identity proposals (the crew's, for the person; nothing here is applied)\n\n"
                + (lines.isEmpty() ? "(none)\n" : String.join("\n", lines) + "\n"), StandardCharsets.UTF_8);
        return lines.size() + " proposal(s), " + ns.size() + " node(s), " + g.edges().size() + " edge(s)";
    }

    /** Diacritics stripped, punctuation dropped, tokens sorted — the identity-proposal key. */
    static String key(String s) {
        String n = java.text.Normalizer.normalize(s == null ? "" : s, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        n = Vocabulary.norm(n).replaceAll("[^\\p{L}\\p{N} ]", " ").replaceAll("\\s+", " ").strip();
        String[] toks = n.split(" ");
        java.util.Arrays.sort(toks);
        return String.join(" ", toks);
    }

    /** A text rendering of a neighbourhood, for the command line. */
    public static String render(Neighbourhood nb) {
        if (nb.focus() == null) return "(nothing in the graph by that name)\n";
        StringBuilder sb = new StringBuilder();
        sb.append(nb.focus().label()).append(" [").append(nb.focus().kind()).append("]").append(nb.focus().wikidata().isEmpty() ? "" : "  wikidata:" + nb.focus().wikidata()).append('\n');
        for (Edge e : nb.edges()) {
            String from = label(nb, e.from()), to = label(nb, e.to());
            sb.append("  ").append(from).append(" —").append(e.predicate()).append("→ ").append(to)
              .append("   [").append(e.findingId()).append(' ').append(e.state()).append(e.disputed() ? " DISPUTED" : "").append("]\n");
        }
        for (Node n : nb.nodes()) if (nb.edges().stream().noneMatch(e -> e.from().equals(n.id()) || e.to().equals(n.id())) && !n.id().equals(nb.focus().id()))
            sb.append("  ").append(n.label()).append(" [").append(n.kind()).append("]\n");
        if (!nb.openQuestions().isEmpty()) {
            sb.append("open questions touching this:\n");
            for (String q : nb.openQuestions()) sb.append("  - ").append(q).append('\n');
        }
        return sb.toString();
    }

    private static String label(Neighbourhood nb, String id) {
        for (Node n : nb.nodes()) if (n.id().equals(id)) return n.label();
        return id;
    }
}
