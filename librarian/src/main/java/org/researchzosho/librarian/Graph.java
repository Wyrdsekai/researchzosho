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
import java.util.concurrent.ConcurrentHashMap;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.function.Supplier;
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
 * <p>On disk, under {@code catalog/graph/}: {@code nodes.md} (curated: kind, aliases,
 * external ids — a {@link Vocabulary}), {@code predicates.md} (the predicate vocabulary),
 * {@code merges.tsv} (from, to, by, date, reason; a line that starts with "-" takes a merge back) and {@code different.tsv}
 * (two nodes the person said are not one, in the same form). Everything else is computed from the findings on demand.
 */
public final class Graph {

    public static final List<String> CORE_KINDS = List.of("person", "place", "event", "document", "organisation", "work", "concept");

    // A node of kind "value" is a word many claims end in (male, female): shown beside a node, never walked through.

    /** {@code living}: the graph's own reading of who may be living, which {@link #mayBeLiving()} asks; null for a node made by hand. */
    public record Node(String id, String kind, String label, List<String> aliases, String wikidata, int degree, Living living) {
        public Node(String id, String kind, String label, List<String> aliases, String wikidata, int degree) { this(id, kind, label, aliases, wikidata, degree, null); }

        /**
         * A person whom nothing in the library places in the past: no death, and no date that puts them more than a lifetime back. The
         * rule is a field's ({@link Profile#onGraph}); with none, every person may be living, the safe side. False for anything that is no person.
         */
        public boolean mayBeLiving() { return living != null && kind.equals("person") && living.of(id); }
    }

    /**
     * Who may be living, worked out the first time a node is asked: most readings of the graph never ask, and the rule reads every claim.
     * The rule is set by a field's reading of the graph ({@link Reading#living}); until one is, nobody is placed in the past.
     */
    public static final class Living {
        private Supplier<Map<String, Boolean>> rule;
        private Map<String, Boolean> verdicts;

        synchronized void ruledBy(Supplier<Map<String, Boolean>> r) { if (rule == null) rule = r; }

        synchronized boolean of(String id) {
            if (rule == null) return true;
            if (verdicts == null) verdicts = rule.get();
            return verdicts.getOrDefault(id, true);
        }

        @Override public String toString() { return "worked out from the claims"; }
    }
    public record Edge(String from, String predicate, String to, String findingId, String state, String confidence, boolean disputed) { }
    public record Neighbourhood(Node focus, List<Node> nodes, List<Edge> edges, List<String> openQuestions) { }

    private final LibraryStore store;
    private final Vocabulary nodes;        // curated node ids: slug = node id; also = other names; wikidata
    private final Vocabulary predicates;
    private final Relations relations;
    private final Map<String, String> merges = new LinkedHashMap<>();   // from id → to id (transitively applied)
    private final Map<String, String> kinds = new HashMap<>();
    private final Living living = new Living();

    // computed
    private final Map<String, Node> byId = new LinkedHashMap<>();
    private final List<Edge> edges = new ArrayList<>();
    private final Map<String, List<Edge>> adjacency = new HashMap<>();

    // the field whose view this is: it reads every name that leads to an entry through one of the entry's other names
    // ({@link Profile#readsOtherName}); in the core graph a field that joins only when asked reads those of its own claims
    private final Profile lens;
    private final Map<String, Boolean> otherNames = new ConcurrentHashMap<>();
    private final Map<String, Edge> edgeOf = new HashMap<>();   // a claim → the edge its triple is
    private final Map<String, List<Profile>> readersOf = new HashMap<>();   // in the core graph, a field's own claim → the fields that read its names
    private Links links;   // in a field's view, the entries the field linked from the evidence ({@link Profile#links}); null in the core graph

    /** The state of an edge a field worked out ({@link Links#workedOut}): no claim files it, and it is shown as worked out. */
    public static final String WORKED_OUT = "worked out";

    /**
     * A field's links in its own view ({@link Profile#links}): {@code sides}, a claim's subject or object ({@link #side}) → the entry it is;
     * {@code written}, a name as written ({@link Vocabulary#norm}) → the entry it is, before the list of names is read; {@code nodes}, an entry
     * → the entry it is one person with; {@code labels} and {@code names}, the label and the other names the linked entry is shown with;
     * {@code workedOut}, relations the field works out and files nowhere, each {from, relation, to, the claim it rests on}, shown in its view
     * as edges whose state is "worked out". The core graph never reads them.
     */
    public record Links(Map<String, String> sides, Map<String, String> written, Map<String, String> nodes, Map<String, String> labels, Map<String, List<String>> names,
                        List<String[]> workedOut) {
        /** The key of a claim's subject ({@code subject}) or object in {@link #sides}. */
        public static String side(String claim, boolean subject) { return claim + (subject ? "|subject" : "|object"); }
    }

    private Graph(LibraryStore store, Vocabulary nodes, Vocabulary predicates, Profile lens) {
        this.store = store; this.nodes = nodes; this.predicates = predicates; this.relations = new Relations(predicates); this.lens = lens;
    }

    public static Path dir(LibraryStore store) { return store.root().resolve("catalog").resolve("graph"); }
    static Path nodesFile(LibraryStore store) { return dir(store).resolve("nodes.md"); }
    static Path predicatesFile(LibraryStore store) { return dir(store).resolve("predicates.md"); }
    static Path mergesFile(LibraryStore store) { return dir(store).resolve("merges.tsv"); }
    static Path differentFile(LibraryStore store) { return dir(store).resolve("different.tsv"); }
    /** For each merge, the line the node it went into had before and the line the merge wrote: an unmerge puts the first back. */
    static Path mergeMarksFile(LibraryStore store) { return dir(store).resolve("merge-marks.tsv"); }

    static final String NODES_PREAMBLE = "# Graph nodes — the things the findings are about\n\n"
            + "One per line: `- <id> — <kind>: <label> | also: other names | wikidata: Qn`. Curated here; every other\n"
            + "node is computed from the findings' triples. Merges are the person's act: `researchzosho graph merge`.\n\n";
    static final String PREDICATES_PREAMBLE = "# Graph predicates — the relations the findings assert\n\n"
            + "One per line: `- <slug> — <description> | also: other wordings`. A finding's triple predicate is\n"
            + "resolved against these before it becomes an edge; unknown predicates are kept as written.\n\n";

    /**
     * Load the curated files and compute the graph from every non-retired finding with a triple: the core graph. Its relations are the
     * owner's predicates.md and those of the fields that recognise their own questions. A field that joins only when asked ({@link
     * Profile#joinsOnlyWhenAsked}) has its relations read in its own work alone ({@link Fields#ofClaim}), so an ordinary claim reads as
     * it would with that field switched off.
     */
    public static Graph build(LibraryStore store) throws IOException { return build(store, null); }

    /**
     * The graph as {@code lens} reads it: every enabled field's relations read in every claim, and the field's own reading of who is a
     * person over every claim ({@link Profile#onGraph}, {@link Reading#wide}). A field's own commands read the library this way; the
     * core never does. Null is the core graph.
     */
    public static Graph build(LibraryStore store, Profile lens) throws IOException { return build(store, lens, true); }

    /** As above; {@code linked}: whether the field's links are read ({@link Profile#links}). A merge, the person's act, is written without them. */
    private static Graph build(LibraryStore store, Profile lens, boolean linked) throws IOException {
        List<Profile> on = Profiles.enabledProfiles(store);
        // an enabled field's relations resolve whether or not `profile enable` ever wrote them to the file (a profile is on by default)
        Vocabulary core = Vocabulary.read(predicatesFile(store));
        for (Profile p : on) if (!p.joinsOnlyWhenAsked()) addTerms(core, p);
        Vocabulary wide = core.copy();
        Map<String, Relations> own = new HashMap<>();   // a field that joins only when asked: the core's relations and its own, for its own claims
        for (Profile p : on) if (p.joinsOnlyWhenAsked()) { addTerms(wide, p); Vocabulary v = core.copy(); addTerms(v, p); own.put(p.name(), new Relations(v)); }
        Graph g = new Graph(store, Vocabulary.read(nodesFile(store)), lens == null ? core : wide, lens);
        Relations wideRelations = lens == null ? new Relations(wide) : g.relations;
        for (Vocabulary.Term t : g.nodes.terms().values()) g.kinds.put(t.slug(), kindOf(t.description()));
        g.merges.putAll(merges(store));
        List<Finding> all = store.scanFindings().findings();
        // a field's own view reads the names of each claim as the field linked them; the core graph never does
        if (lens != null && linked) g.links = lens.links(store, all);
        Map<String, Set<String>> runs = Fields.runs(store);
        Map<String, Finding> byFinding = new HashMap<>();
        Map<String, Set<String>> fieldsOf = new HashMap<>();
        Map<String, Profile> joinsWhenAsked = new HashMap<>();
        for (Profile p : on) if (p.joinsOnlyWhenAsked()) joinsWhenAsked.put(p.name(), p);
        for (Finding f : all) {
            if (f.state() == Finding.State.retired || f.state() == Finding.State.superseded || f.triple() == null) continue;
            Set<String> fields = Fields.ofClaim(f, runs);
            // the names of a claim that is a field's own work are read as that field reads them; in its view, every claim's are
            List<Profile> readers = new ArrayList<>();
            if (lens != null) readers.add(lens);
            else for (String x : fields) if (joinsWhenAsked.containsKey(x)) readers.add(joinsWhenAsked.get(x));
            if (lens == null && !readers.isEmpty()) g.readersOf.put(f.id(), readers);
            String from = g.linked(f.id(), true, g.idOf(f.triple().subject(), readers));
            String to = g.linked(f.id(), false, g.idOf(f.triple().object(), readers));
            if (from.isEmpty() || to.isEmpty()) continue;
            fieldsOf.put(f.id(), fields);
            String pred;
            if (lens != null) pred = g.relations.of(f.triple().predicate());
            else {
                List<String> mine = new ArrayList<>(); for (String x : fields) if (own.containsKey(x)) mine.add(x);
                pred = (mine.isEmpty() ? g.relations : mine.size() == 1 ? own.get(mine.get(0)) : wideRelations).of(f.triple().predicate());
            }
            boolean disputed = f.state() == Finding.State.disputed;
            byFinding.put(f.id(), f);
            Edge e = new Edge(from, pred, to, f.id(), f.state().name(), f.confidence().name(), disputed);
            g.edges.add(e);
            g.edgeOf.put(f.id(), e);
            g.adjacency.computeIfAbsent(from, k -> new ArrayList<>()).add(e);
            g.adjacency.computeIfAbsent(to, k -> new ArrayList<>()).add(e);
            g.touch(from, f.triple().subject());
            g.touch(to, f.triple().object());
        }
        // a field's own reading: in its view, of every claim; in the core graph, of its own work alone, and not at all where no claim is its work
        // relations the field worked out: edges of its view, marked so, and filed nowhere
        if (g.links != null) for (String[] w : g.links.workedOut()) {
            if (!g.byId.containsKey(w[0]) || !g.byId.containsKey(w[2])) continue;
            Edge e = new Edge(w[0], w[1], w[2], w[3], WORKED_OUT, "low", false);
            g.edges.add(e);
            g.adjacency.computeIfAbsent(w[0], k -> new ArrayList<>()).add(e);
            g.adjacency.computeIfAbsent(w[2], k -> new ArrayList<>()).add(e);
        }
        if (lens != null) lens.onGraph(g.new Reading(lens, true, all, byFinding, fieldsOf));
        else for (Profile p : on) {
            if (!p.joinsOnlyWhenAsked()) continue;
            Reading r = g.new Reading(p, false, all, byFinding, fieldsOf);
            if (r.anyOwned()) p.onGraph(r);
        }
        // SUBJECTS ON THE MAP: a shelf label is a hub — every name a claim under it mentions is filed under it. Nodes
        // "subject:<slug>" (kind subject), one "is filed under" edge per name and subject, backed by the first finding that
        // filed it; the label is the vocabulary's description, so the map reads "Japanese ASR/alignment specifics", not the slug.
        Vocabulary subjects = Files.exists(store.subjectsFile()) ? Vocabulary.read(store.subjectsFile()) : null;
        Set<String> filed = new HashSet<>();
        for (Finding f : all) {
            if (f.state() == Finding.State.retired || f.triple() == null || f.subjects().isEmpty()) continue;
            for (String slug : f.subjects()) {
                String sid = "subject:" + slug;
                if (!g.byId.containsKey(sid)) {
                    Vocabulary.Term t = subjects == null ? null : subjects.get(slug);
                    String label = t == null || t.description().isBlank() ? slug : t.description();
                    g.byId.put(sid, new Node(sid, "subject", label, List.of(slug), "", 0, g.living));
                    g.subjectIds.put(Vocabulary.norm(label), sid); g.subjectIds.put(Vocabulary.norm(slug), sid); g.subjectIds.put(slug, sid);
                }
                for (int side = 0; side < 2; side++) {
                    String name = side == 0 ? f.triple().subject() : f.triple().object();
                    String nid = g.idOf(f, side == 0);
                    if (nid.isEmpty() || !filed.add(nid + "|" + sid)) continue;
                    Edge e = new Edge(nid, "is filed under", sid, f.id(), f.state().name(), f.confidence().name(), false);
                    g.edges.add(e);
                    g.adjacency.computeIfAbsent(nid, k -> new ArrayList<>()).add(e);
                    g.adjacency.computeIfAbsent(sid, k -> new ArrayList<>()).add(e);
                    g.touch(nid, name);
                    Node sn = g.byId.get(sid);
                    g.byId.put(sid, new Node(sn.id(), sn.kind(), sn.label(), sn.aliases(), "", sn.degree() + 1, g.living));
                }
            }
        }
        // the second kind of edge: what each claim RESTS ON (Concepts), hung on what the claim is about — its triple's
        // subject, or its subject node when it has no triple. A claim without a triple used to contribute nothing, and
        // the corrosion claim that named "microbial activity" was exactly that one (2026-09-15).
        for (Finding f : all) {
            if (f.state() == Finding.State.retired) continue;
            List<String> concepts = Concepts.of(f);
            if (concepts.isEmpty()) continue;
            String from = f.triple() != null ? g.idOf(f, true)
                    : f.subjects().isEmpty() ? "" : "subject:" + f.subjects().get(0);
            if (from.isEmpty() || !g.byId.containsKey(from)) continue;
            String to = f.triple() == null ? "" : g.idOf(f, false);
            boolean disputed = f.state() == Finding.State.disputed;
            for (String c : concepts) {
                String cid = g.nodeIdOf(c);
                if (cid.isEmpty() || cid.equals(from) || cid.equals(to)) continue;
                Edge m = new Edge(from, "mentions", cid, f.id(), f.state().name(), f.confidence().name(), disputed);
                g.edges.add(m);
                g.adjacency.computeIfAbsent(from, k -> new ArrayList<>()).add(m);
                g.adjacency.computeIfAbsent(cid, k -> new ArrayList<>()).add(m);
                g.touch(cid, c);
            }
        }
        // a linked entry is shown with the label and the other names the field's links give it
        if (g.links != null) for (Node n : new ArrayList<>(g.byId.values())) {
            String label = g.links.labels().get(n.id());
            List<String> names = g.links.names().get(n.id());
            if (label == null && names == null) continue;
            g.byId.put(n.id(), new Node(n.id(), n.kind(), label == null ? n.label() : label, names == null ? n.aliases() : List.copyOf(names), n.wikidata(), n.degree(), g.living));
        }
        return g;
    }

    /** A field's relations added to a vocabulary, each where the vocabulary has no term of that slug. */
    private static void addTerms(Vocabulary v, Profile p) {
        for (Vocabulary.Term t : p.predicates()) if (v.get(t.slug()) == null) v.alias(t.slug(), t.description(), t.also());
    }

    /**
     * A field's reading of the graph as it is built ({@link Profile#onGraph}): the claims and edges, which of them are the field's own
     * work, and the two things a field may add, who is a person and who may be living. {@link #wide}: the field's own view, where every
     * claim counts; in the core graph only the field's own work does.
     */
    public final class Reading {
        private final Profile field;
        private final boolean wide;
        private final List<Finding> all;
        private final Map<String, Finding> claims;
        private final Map<String, Set<String>> fieldsOf;

        Reading(Profile field, boolean wide, List<Finding> all, Map<String, Finding> claims, Map<String, Set<String>> fieldsOf) {
            this.field = field; this.wide = wide; this.all = all; this.claims = claims; this.fieldsOf = fieldsOf;
        }

        public boolean wide() { return wide; }
        public LibraryStore store() { return store; }
        public Graph graph() { return Graph.this; }
        /** Every claim the graph was read from, the retired too. */
        public List<Finding> findings() { return all; }
        /** The claims that are edges, by id. */
        public Map<String, Finding> claims() { return claims; }
        public List<Edge> edges() { return Graph.this.edges(); }
        /** Whether a claim is this field's own work ({@link Fields#ofClaim}). */
        public boolean owned(Finding f) { return f != null && fieldsOf.getOrDefault(f.id(), Set.of()).contains(field.name()); }
        /** Whether any claim of the graph is this field's own work. */
        public boolean anyOwned() { for (Set<String> s : fieldsOf.values()) if (s.contains(field.name())) return true; return false; }
        /** The node a name is, read as this field reads the names of its own work ({@link Profile#readsOtherName}). */
        public String nodeIdOf(String name) { return wide ? Graph.this.nodeIdOf(name) : idOf(name, List.of(field)); }
        public Node nodeOf(String id) { return byId.get(id); }
        /** The kind nodes.md gives a node; null when the owner described no such node. */
        public String curatedKind(String id) { return nodes.get(id) == null ? null : kinds.get(id); }
        /** Every node nodes.md describes, with the kind it gives. */
        public Map<String, String> curatedKinds() { Map<String, String> out = new HashMap<>(); for (String id : nodes.terms().keySet()) out.put(id, kinds.get(id)); return out; }
        /** The relation the edge of this claim carries, as the graph resolved it. */
        public String relationOf(Finding f) {
            Edge e = edgeOf.get(f.id());
            return e != null ? e.predicate() : relations.of(f.triple().predicate());
        }
        /** A node nobody described becomes a person. */
        public void markPerson(String id) {
            Node n = byId.get(id);
            if (n == null || nodes.get(id) != null) return;
            kinds.put(id, "person");
            byId.put(id, new Node(id, "person", n.label(), n.aliases(), n.wikidata(), n.degree(), living));
        }
        /** The rule for who may be living, worked out the first time somebody asks: node id → may be living. */
        public void living(Supplier<Map<String, Boolean>> rule) { living.ruledBy(rule); }

        /**
         * In the field's own view, a claim the field sets aside: its edge counts as disputed there, as a claim somebody disputed does, and the
         * claim itself stays as it is. The core graph is never changed, so an ordinary library reads the same with the field on and off.
         */
        public void setAside(String findingId) {
            if (!wide) return;
            Edge e = edgeOf.get(findingId);
            if (e == null || e.disputed()) return;
            replace(e, new Edge(e.from(), e.predicate(), e.to(), e.findingId(), e.state(), e.confidence(), true));
        }

        /**
         * In the field's own view, a claim the field reads as another relation than its triple names, the other way round when {@code swap}:
         * a parent the words call adoptive read as adopted-by. The claim itself stays as it is, and the core graph is never changed.
         */
        public void rekind(String findingId, String predicate, boolean swap) {
            if (!wide) return;
            Edge e = edgeOf.get(findingId);
            if (e == null || e.predicate().equals(predicate)) return;
            replace(e, new Edge(swap ? e.to() : e.from(), predicate, swap ? e.from() : e.to(), e.findingId(), e.state(), e.confidence(), e.disputed()));
        }

        private void replace(Edge e, Edge now) {
            edges.set(edges.indexOf(e), now);
            edgeOf.put(e.findingId(), now);
            for (String side : new String[]{e.from(), e.to()}) {
                List<Edge> at = adjacency.get(side);
                if (at != null) at.replaceAll(x -> x == e ? now : x);
            }
        }
    }

    /** Relations resolved against one vocabulary, each wording worked out once per graph. */
    static final class Relations {
        private final Vocabulary v;
        private final Map<String, String> memo = new HashMap<>();
        private List<String[]> descriptions;   // {the description without its leading is/was and article, the description, slug}

        Relations(Vocabulary v) { this.v = v; }

        /**
         * A relation as the vocabulary names it. A model writes relations as it would in a sentence ("is the daughter of", "was born
         * in", "lives in"): the words are read with a leading is, was, are or were and a leading the, a or an taken off, and against
         * each relation's own description too, before they are kept as written. A passive ("was cited", "was invented by") names the
         * relation the other way round, so it is read that way only onto a relation that is itself worded with is or was ("was born
         * in", "is maintained by"), or with had for what happened to its subject ("had this happen in their life"); onto any other it
         * is kept as written, and the claim keeps its direction.
         */
        synchronized String of(String name) {
            String key = name == null ? "" : name;
            String hit = memo.get(key);
            if (hit != null) return hit;
            String out = resolve(key);
            memo.put(key, out);
            return out;
        }

        private String resolve(String name) {
            String p = v.resolve(name);
            if (p != null) return p;
            String written = Vocabulary.norm(name);
            String bare = written.replaceFirst("^(?:(?:is|was|are|were|has been|had been|being)\\s+)?(?:(?:the|a|an)\\s+)?", "");
            boolean passive = passive(written);
            p = bare.isEmpty() ? null : v.resolve(bare);
            if (p != null && (!passive || withBe(v.get(p) == null ? "" : v.get(p).description()))) return p;
            if (descriptions == null) {
                descriptions = new ArrayList<>();
                for (Vocabulary.Term t : v.terms().values())
                    descriptions.add(new String[]{Vocabulary.norm(t.description()).replaceFirst("^(?:(?:is|was|are|were|had)\\s+)?(?:(?:the|a|an)\\s+)?", ""), Vocabulary.norm(t.description()), t.slug()});
            }
            for (String[] d : descriptions) if (!d[0].isEmpty() && (d[1].equals(written) || d[0].equals(bare) && (!passive || withBe(d[1])))) return d[2];
            return written;
        }

        private static final Pattern BE = Pattern.compile("^(?:is|was|are|were|has been|had been|being)\\s+(.+)$");

        /** A passive wording: is or was, then one word ("was cited", "was found"), or words ending in by ("was invented by"). */
        static boolean passive(String written) {
            Matcher m = BE.matcher(written);
            if (!m.matches()) return false;
            String rest = m.group(1);
            if (rest.matches("(?:the|a|an)\\s.*")) return false;   // "is a fork of": a noun, not a passive
            return !rest.contains(" ") || rest.endsWith(" by");
        }

        /**
         * A relation worded with is or was ("was born in", "is maintained by"), or with had for what happened to its subject: its subject
         * is the one a passive names, so a passive may name it in another tense.
         */
        static boolean withBe(String description) {
            return Vocabulary.norm(description).matches("(?:is|was|are|were|had)\\b.*");
        }
    }

    private void touch(String id, String asWritten) {
        Node n = byId.get(id);
        if (n == null) {
            Vocabulary.Term t = nodes.get(id);
            List<String> aliases = new ArrayList<>(t == null ? List.of() : t.also());
            String label = t != null && t.description().contains(":") ? t.description().substring(t.description().indexOf(':') + 1).strip() : (t != null && !t.description().isBlank() ? t.description() : asWritten);
            n = new Node(id, kinds.getOrDefault(id, "concept"), label, aliases, t == null ? "" : t.wikidata(), 0, living);
        }
        byId.put(id, new Node(n.id(), n.kind(), n.label(), n.aliases(), n.wikidata(), n.degree() + 1, living));
    }

    /**
     * The words the libraries of 0.4 and the builds before 0.5.0 wrote beside a person's kind in nodes.md. They are read and left
     * out: who may see a library is decided by who may read it, and whether a person may be living is worked out from the claims.
     */
    private static final Set<String> OLD_MARKS = Set.of("private", "kept private", "kept public", "said to be living", "said to have died");

    /**
     * The kind in the part of a nodes.md line before the colon, as the owner wrote it ("organisation, lab"), with only the words older
     * versions wrote beside a person's kind left out ("person, private" is "person"); "concept" when nothing is left.
     */
    static String kind(String head) {
        List<String> keep = new ArrayList<>();
        for (String part : head.split(",")) {
            String w = part.strip();
            if (!w.isEmpty() && !OLD_MARKS.contains(w)) keep.add(w);
        }
        return keep.isEmpty() ? "concept" : String.join(", ", keep);
    }

    /** The kind of a curated description ("person: Tom Ellis"); "concept" when it names none. */
    public static String kindOf(String description) {
        return kind(description == null || !description.contains(":") ? "concept" : description.substring(0, description.indexOf(':')));
    }

    /**
     * A curated description without the words older versions wrote beside a person's kind ("person, private: Tom Ellis" is "person: Tom
     * Ellis"). Every other word before the colon stays as the owner wrote it, in its order: "organisation, lab: CERN" is kept whole.
     */
    static String plain(String description) {
        if (description == null || !description.contains(":")) return description;
        String head = description.substring(0, description.indexOf(':'));
        List<String> keep = new ArrayList<>();
        for (String part : head.split(",")) if (!part.isBlank() && !OLD_MARKS.contains(part.strip())) keep.add(part.strip());
        String k = keep.isEmpty() ? kind(head) : String.join(", ", keep);
        return head.strip().equals(k) ? description : k + description.substring(description.indexOf(':'));
    }

    /** The headings older versions wrote at the top of nodes.md, the ones that described the per-person marks (0.4.6 wrote the first). */
    static final List<String> OLD_HEADINGS = List.of(
            "# Graph nodes — the things the findings are about\n\n"
            + "One per line: `- <id> — <kind>: <label> | also: other names | wikidata: Qn`. Curated here; every other\n"
            + "node is computed from the findings' triples. `private` in the kind marks a node the desk never shows\n"
            + "to another patron (a living person, say). Merges are the person's act: `researchzosho graph merge`.\n",
            "# Graph nodes — the things the findings are about\n\n"
            + "One per line: `- <id> — <kind>: <label> | also: other names | wikidata: Qn`. Curated here; every other\n"
            + "node is computed from the findings' triples. `private` in the kind marks a person who may be living, by the\n"
            + "dates; `kept private` and `kept public` are the owner's own decision, which the dates never change. A private\n"
            + "node is never shown to another patron. Merges are the person's act: `researchzosho graph merge`.\n",
            "# Graph nodes — the things the findings are about\n\n"
            + "One per line: `- <id> — <kind>: <label> | also: other names | wikidata: Qn`. Curated here; every other\n"
            + "node is computed from the findings' triples. `private` in the kind marks a person who may be living, by the\n"
            + "dates; `kept private` and `kept public` are the owner's own decision, which the dates never change. `said to be\n"
            + "living` and `said to have died` are what a source said without a date, which the dates do not undo either. A private\n"
            + "node is never shown to another patron. Merges are the person's act: `researchzosho graph merge`.\n");

    /**
     * The lines above the first entry of nodes.md with a heading an older version wrote there replaced by today's; null when they hold
     * none. Only the old heading's own lines go: the owner's heading and notes above the entries stay where they are. A heading worded
     * otherwise is the old title with the paragraph under it that describes {@code private}.
     */
    static List<String> withoutOldHeading(List<String> preamble) {
        for (String old : OLD_HEADINGS) {
            List<String> lines = List.of(old.split("\n"));
            for (int i = 0; i + lines.size() <= preamble.size(); i++)
                if (preamble.subList(i, i + lines.size()).equals(lines)) return replaced(preamble, i, i + lines.size());
        }
        int title = preamble.indexOf(OLD_HEADINGS.get(0).split("\n")[0]);
        if (title < 0) return null;
        int from = title + 1;
        while (from < preamble.size() && preamble.get(from).isBlank()) from++;
        int to = from;
        while (to < preamble.size() && !preamble.get(to).isBlank()) to++;
        return String.join(" ", preamble.subList(from, to)).contains("`private`") ? replaced(preamble, title, to) : null;
    }

    private static List<String> replaced(List<String> preamble, int from, int to) {
        List<String> out = new ArrayList<>(preamble.subList(0, from));
        out.addAll(List.of(NODES_PREAMBLE.split("\n")));
        out.addAll(preamble.subList(to, preamble.size()));
        return out;
    }

    /**
     * nodes.md written back. The words older versions wrote beside a person's kind go, and so does the heading that described them;
     * everything else stays as the file had it, the owner's own heading and notes above the entries too.
     */
    static void writeNodes(LibraryStore store, Vocabulary nodes) throws IOException {
        for (Vocabulary.Term t : new ArrayList<>(nodes.terms().values())) {
            String d = plain(t.description());
            if (!d.equals(t.description())) nodes.put(new Vocabulary.Term(t.slug(), d, t.also(), t.wikidata()));
        }
        List<String> heading = withoutOldHeading(nodes.preamble());
        if (heading != null) nodes.preamble(heading);
        nodes.write(nodesFile(store), NODES_PREAMBLE);
    }

    /** The node id a name resolves to: the curated vocabulary first, then the normalised name itself; merges applied. */
    /** A subject's label or slug → its node id, so the map can be asked for "Japanese keigo" or speech--japanese. */
    private final Map<String, String> subjectIds = new HashMap<>();

    public String nodeIdOf(String name) { return idOf(name, lens == null ? List.of() : List.of(lens)); }

    /** The node a claim's subject ({@code subject}) or object is: its name, read as the fields that read the claim's names read it. */
    String idOf(Finding f, boolean subject) {
        String name = subject ? f.triple().subject() : f.triple().object();
        List<Profile> readers = readersOf.get(f.id());
        return linked(f.id(), subject, readers == null ? nodeIdOf(name) : idOf(name, readers));
    }

    /**
     * The node a claim's subject ({@code subject}) or object is in this view: in a field's view, the entry the field linked it to ({@link
     * Links}); otherwise, and in the core graph always, the node its name is ({@link #nodeIdOf}).
     */
    public String nodeOf(Finding f, boolean subject) {
        String id = nodeIdOf(subject ? f.triple().subject() : f.triple().object());
        return linked(f.id(), subject, id);
    }

    /** The entry a field's links make one side of a claim, or {@code id} where they make none. */
    private String linked(String claim, boolean subject, String id) {
        if (links == null) return id;
        String to = links.sides().get(Links.side(claim, subject));
        return to != null ? to : id;
    }

    /** The links this view reads its claims with; null in the core graph and in a view whose field links nothing. */
    public Links links() { return links; }

    /** {@link #nodeIdOf}, with the fields that read the name ({@link #readsOtherName}). */
    private String idOf(String name, List<Profile> readers) {
        if (name != null && name.startsWith("subject:")) return name;
        String bySubject = subjectIds.get(Vocabulary.norm(name == null ? "" : name));
        if (bySubject != null && !byId.containsKey(Vocabulary.norm(name))) return bySubject;
        // in a field's view, a name the field's links place is read there before the list of names
        String id = links == null ? null : links.written().get(Vocabulary.norm(name));
        if (id == null) {
            id = nodes.resolve(name);
            if (id != null && !readsOtherName(name, id, readers)) id = null;
            if (id == null) id = Vocabulary.norm(name);
        }
        int guard = 0;
        while (merges.containsKey(id) && guard++ < 50) id = merges.get(id);
        // in a field's view, an entry the field linked to another is that other entry
        if (links != null) { guard = 0; while (links.nodes().containsKey(id) && guard++ < 50) id = links.nodes().get(id); }
        return id;
    }

    /**
     * Whether a name the list of names leads to an entry is read as that entry. A name that is the entry's own is. One that leads there
     * through another name of the entry is too, unless a field that reads the name says otherwise ({@link Profile#readsOtherName}): in a
     * field's view, that field, for every name; in the core graph, a field that joins only when asked, for the names of its own claims.
     * With no such field the core reads the name as it always has.
     */
    private boolean readsOtherName(String name, String id, List<Profile> readers) {
        if (readers.isEmpty()) return true;
        String n = Vocabulary.norm(name);
        if (n.equals(Vocabulary.norm(id)) || n.equals(Vocabulary.norm(id.replace('-', ' '))) || n.replace(' ', '-').equals(Vocabulary.norm(id))) return true;
        Vocabulary.Term t = nodes.get(id);
        if (t == null) return true;
        StringBuilder key = new StringBuilder(n).append('\u0000').append(id);
        for (Profile p : readers) key.append('\u0000').append(p.name());
        return otherNames.computeIfAbsent(key.toString(), k -> {
            for (Profile p : readers) if (!p.readsOtherName(name, t)) return false;
            return true;
        });
    }

    /**
     * A relation as this graph's vocabulary names it ({@link Relations#of}): the core's for the core graph, every enabled field's for a
     * field's view.
     */
    public String predicateOf(String name) { return relations.of(name); }

    public Node node(String id) { return byId.get(id); }
    /** The library this graph was read from. */
    LibraryStore store() { return store; }
    public List<Node> nodes() { return new ArrayList<>(byId.values()); }
    public List<Edge> edges() { return new ArrayList<>(edges); }
    public Vocabulary predicates() { return predicates; }
    public Vocabulary curated() { return nodes; }

    /**
     * The neighbourhood of a focus (a node name, or an entry id whose triple names the node), out to
     * {@code depth} hops, at most {@code k} nodes, nearest and best-connected first. Open frontier lines
     * that name any node in view ride along.
     */
    public Neighbourhood around(String focus, int depth, int k) throws IOException {
        String id = nodeIdOf(focus);
        if (!byId.containsKey(id)) {
            // an entry id: use its triple's subject
            Finding f = store.finding(focus);
            if (f != null && f.triple() != null) id = idOf(f, true);
        }
        Node start = byId.get(id);
        if (start == null) return new Neighbourhood(null, List.of(), List.of(), List.of());
        Map<String, Integer> dist = new LinkedHashMap<>();
        ArrayDeque<String> q = new ArrayDeque<>();
        dist.put(id, 0); q.add(id);
        while (!q.isEmpty()) {
            String cur = q.poll();
            int d = dist.get(cur);
            if (d >= depth) continue;
            // a value (a sex, say) is a fact about the node next to it, not a way on: everybody recorded as male is not a neighbour
            if (d > 0 && byId.get(cur) != null && byId.get(cur).kind().equals("value")) continue;
            for (Edge e : adjacency.getOrDefault(cur, List.of())) {
                String other = e.from().equals(cur) ? e.to() : e.from();
                if (byId.get(other) == null) continue;
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

    /** A merge: the name folded in, the node it went into, and the claims about the folded name that are now about that node. */
    public record Merged(String from, String to, List<String> claims) { }

    public static Merged merge(LibraryStore store, String from, String to, String by) throws IOException { return merge(store, from, to, by, ""); }

    /**
     * Two nodes are one: {@code from} folds into {@code to}; its names become aliases of {@code to}. Logged with the reason the person
     * gave. No claim is rewritten, so {@link #unmerge} puts every one back. A pair written down as two people is not any more.
     */
    public static synchronized Merged merge(LibraryStore store, String from, String to, String by, String reason) throws IOException { return merge(store, from, to, by, reason, null); }

    /**
     * As above, with the kinds of names nobody described read as {@code lens} reads them ({@link #build(LibraryStore, Profile)}): a field's
     * own commands join the people its view knows, and the kind written for the joined name is that view's.
     */
    public static synchronized Merged merge(LibraryStore store, String from, String to, String by, String reason, Profile lens) throws IOException {
        // the names as the entries are, before any field's links: the merge is written in the names the core reads too
        Graph g = build(store, lens, false);
        String a = g.nodeIdOf(from), b = g.nodeIdOf(to);
        if (a.equals(b)) return new Merged(a, b, List.of());
        List<String> claims = claimsOf(store, g, a);
        Map<String, String> joinedBefore = merges(store);
        Files.createDirectories(dir(store));
        String why = clean(reason);
        Files.writeString(mergesFile(store), a + "\t" + b + "\t" + by + "\t" + LocalDate.now() + (why.isEmpty() ? "" : "\t" + why) + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        Vocabulary nodes = Vocabulary.read(nodesFile(store));
        Node bn = g.node(b);
        Vocabulary.Term bt = nodes.get(b);
        // the kind of the name it goes into, or of the name folded in when that one says more; also when the name it goes into has no claim yet
        String ka = ownKind(nodes, g, a), kb = ownKind(nodes, g, b);
        String kind = !"concept".equals(kb) ? kb : ka;
        // the words the owner wrote beside that kind ("organisation, lab") stay with it
        Vocabulary.Term at = nodes.get(a);
        String head = kind.equals(kb) && bt != null ? headOf(bt.description()) : kind.equals(ka) && at != null ? headOf(at.description()) : kind;
        String label = bn != null ? bn.label() : bt != null && bt.description().contains(":") ? bt.description().substring(bt.description().indexOf(':') + 1).strip() : to.strip();
        String written = head + ": " + label;
        // what b is on its own, without the names joined into it before: what an unmerge in any order gives back
        String own = bt == null ? "" : ownOf(store, b, headOf(bt.description()), joinedBefore) + ": " + labelOf(bt.description());
        write(mergeMarksFile(store), a + "\t" + b + "\t" + clean(own) + "\t" + clean(written));
        nodes.alias(b, written, List.of(from));
        writeNodes(store, nodes);
        if (differentPairs(store).contains(pair(a, b))) write(differentFile(store), "-\t" + pair(a, b) + "\t" + by + "\t" + LocalDate.now() + "\tjoined after all");
        Changes.append(store, "node", b, "merged", a + " → " + b + " by " + by + (why.isEmpty() ? "" : ": " + why));
        store.circulate("graph-merge", a + " → " + b + " by " + by);
        return new Merged(a, b, claims);
    }

    /**
     * A merge taken back: {@code name} is its own node again, with the claims that were written about it. {@code into}, when given,
     * must be the node it went into. The merge stays in the file with a line after it that takes it back.
     */
    public static synchronized Merged unmerge(LibraryStore store, String name, String into, String by, String reason) throws IOException {
        Map<String, String> active = merges(store);
        Vocabulary nodes = Vocabulary.read(nodesFile(store));
        String want = Vocabulary.norm(name);
        List<String> found = new ArrayList<>();
        for (String k : active.keySet()) {
            Vocabulary.Term t = nodes.get(k);
            String label = t == null ? "" : t.description().contains(":") ? t.description().substring(t.description().indexOf(':') + 1).strip() : t.description();
            if (k.equals(want) || Vocabulary.norm(label).equals(want)) found.add(k);
        }
        if (found.isEmpty()) throw new IllegalArgumentException("\"" + name + "\" was never joined to another name, so there is nothing to take back. researchzosho graph merge is what joins two names." + Fields.hints(store, "unmerge-nothing"));
        String k = found.get(0), v = active.get(k);
        if (into != null && !into.isBlank()) {
            Graph g = build(store);
            String end = v; int guard = 0;
            while (active.containsKey(end) && guard++ < 50) end = active.get(end);
            if (!end.equals(g.nodeIdOf(into)) && !v.equals(Vocabulary.norm(into))) throw new IllegalArgumentException("\"" + name + "\" was joined to \"" + labelOf(nodes, v) + "\", not to \"" + into + "\".");
        }
        takeBack(store, k, v, by, reason, want);
        return new Merged(k, v, claimsOf(store, build(store), k));
    }

    /**
     * One merge taken back by a line of its own; the name it gave the other node as another name goes too, or it would still lead there.
     * The other node gets back the kind it had before the merge, unless somebody changed it since.
     */
    static void takeBack(LibraryStore store, String k, String v, String by, String reason, String typed) throws IOException {
        String why = clean(reason);
        Map<String, String> joined = merges(store);
        write(mergesFile(store), "-\t" + k + "\t" + v + "\t" + by + "\t" + LocalDate.now() + (why.isEmpty() ? "" : "\t" + why));
        Vocabulary nodes = Vocabulary.read(nodesFile(store));
        Vocabulary.Term t = nodes.get(v);
        if (t != null) {
            List<String> keep = new ArrayList<>();
            for (String a : t.also()) if (!Vocabulary.norm(a).equals(k) && !Vocabulary.norm(a).equals(typed)) keep.add(a);
            String own = ownOf(store, v, headOf(t.description()), joined);
            nodes.put(new Vocabulary.Term(t.slug(), own + ": " + labelOf(t.description()), keep, t.wikidata()));
            writeNodes(store, nodes);
        }
        Changes.append(store, "node", v, "unmerged", k + " ← " + v + " by " + by + (why.isEmpty() ? "" : ": " + why));
        store.circulate("graph-unmerge", k + " ← " + v + " by " + by);
    }

    /** The kind of a node as it stands on its own, before any merge rewrites it. */
    private static String ownKind(Vocabulary nodes, Graph g, String id) {
        Vocabulary.Term t = nodes.get(id);
        if (t != null) return kindOf(t.description());
        Node n = g.node(id);
        return n == null ? "concept" : n.kind();
    }

    /**
     * The part of a nodes.md description before the colon, the kind with the words the owner wrote beside it ("organisation, lab"), without
     * the words older versions wrote beside a person's kind; the kind alone ("concept") when it names none.
     */
    static String headOf(String description) {
        String d = plain(description);
        return d == null || !d.contains(":") ? kindOf(description) : d.substring(0, d.indexOf(':')).strip();
    }

    /** The label part of a nodes.md description: what stands after the colon. */
    static String labelOf(String description) { return description != null && description.contains(":") ? description.substring(description.indexOf(':') + 1).strip() : ""; }

    /**
     * {@code v}'s own kind, with the words the owner wrote beside it, without what the names joined into it brought: as it was before the
     * latest merge into it still in force, where that merge set it, and as it is now where somebody changed it since. Each merge writes
     * down {@code v}'s own line before it, so merges taken back in any order find it. The marks older versions wrote after a person's kind
     * are left out. {@code cur}: the kind and its words as they stand now; {@code joined}: the merges in force, with the one being taken back.
     */
    static String ownOf(LibraryStore store, String v, String cur, Map<String, String> joined) throws IOException {
        if (!Files.exists(mergeMarksFile(store))) return cur;
        String[] found = null;
        for (String line : Files.readAllLines(mergeMarksFile(store), StandardCharsets.UTF_8)) {
            String[] p = line.split("\t", -1);
            if (p.length >= 4 && p[1].equals(v) && v.equals(joined.get(p[0]))) found = p;
        }
        if (found == null || found[2].isBlank()) return cur;
        return cur.equals(headOf(found[3])) ? headOf(found[2]) : cur;
    }

    /** The merges in force, from → to: each line of the file in order, a line that starts with "-" taking back the one it names. */
    static Map<String, String> merges(LibraryStore store) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        if (!Files.exists(mergesFile(store))) return out;
        for (String line : Files.readAllLines(mergesFile(store), StandardCharsets.UTF_8)) {
            String[] p = line.split("\t");
            if (p.length >= 3 && p[0].equals("-")) { if (p[2].equals(out.get(p[1]))) out.remove(p[1]); continue; }
            if (p.length >= 2) out.put(p[0], p[1]);
        }
        return out;
    }

    /**
     * Two nodes the person said are two people: they are never put forward as one again. A name in the list of names that no claim is
     * about yet counts as a node here: it is still somebody the person can tell apart from somebody else.
     */
    public static synchronized void different(LibraryStore store, String a, String b, String by, String reason) throws IOException {
        Graph g = build(store);
        String x = g.nodeIdOf(a), y = g.nodeIdOf(b);
        for (String[] n : new String[][]{{a, x}, {b, y}}) if (g.node(n[1]) == null && g.curated().get(n[1]) == null) throw new IllegalArgumentException("Nobody in the library is called \"" + n[0] + "\". researchzosho map \"" + n[0] + "\" shows the names it holds that are close to it.");
        if (x.equals(y)) throw new IllegalArgumentException("\"" + a + "\" and \"" + b + "\" are one node in the library already. To hold two people of one name apart, give the second a name of its own." + Fields.hints(store, "apart"));
        if (differentPairs(store).contains(pair(x, y))) return;
        Files.createDirectories(dir(store));
        write(differentFile(store), pair(x, y) + "\t" + by + "\t" + LocalDate.now() + "\t" + clean(reason));
        Changes.append(store, "node", x, "different", x + " and " + y + " are two people, by " + by + (clean(reason).isEmpty() ? "" : ": " + clean(reason)));
    }

    /** The word taken back: the two may be put forward as one again. Returns whether they had been written down as two. */
    public static synchronized boolean notDifferent(LibraryStore store, String a, String b, String by) throws IOException {
        Graph g = build(store);
        String key = pair(g.nodeIdOf(a), g.nodeIdOf(b));
        if (!differentPairs(store).contains(key)) return false;
        write(differentFile(store), "-\t" + key + "\t" + by + "\t" + LocalDate.now());
        return true;
    }

    /** Every pair written down as two people and not taken back, as {@link #pair} writes it. */
    public static Set<String> differentPairs(LibraryStore store) throws IOException {
        Set<String> out = new LinkedHashSet<>();
        if (!Files.exists(differentFile(store))) return out;
        for (String line : Files.readAllLines(differentFile(store), StandardCharsets.UTF_8)) {
            String[] p = line.split("\t");
            if (p.length >= 3 && p[0].equals("-")) out.remove(p[1] + "\t" + p[2]);
            else if (p.length >= 2) out.add(p[0] + "\t" + p[1]);
        }
        return out;
    }

    /** Two node ids in one order, so a pair is found whichever way round it was named. */
    public static String pair(String a, String b) { return a.compareTo(b) <= 0 ? a + "\t" + b : b + "\t" + a; }

    /** The claims a node's name is written in, as subject or object. */
    static List<String> claimsOf(LibraryStore store, Graph g, String id) throws IOException {
        List<String> out = new ArrayList<>();
        for (Finding f : store.scanFindings().findings())
            if (f.state() != Finding.State.retired && f.triple() != null && (g.idOf(f, true).equals(id) || g.idOf(f, false).equals(id))) out.add(f.id());
        return out;
    }

    private static String labelOf(Vocabulary nodes, String id) {
        Vocabulary.Term t = nodes.get(id);
        return t == null ? id : t.description().contains(":") ? t.description().substring(t.description().indexOf(':') + 1).strip() : t.description();
    }

    private static String clean(String s) { return s == null ? "" : s.replaceAll("[\\t\\r\\n]+", " ").strip(); }

    private static void write(Path file, String line) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, line + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /** Other names taken away again: each entry is {the person's name, the other name to remove}. Returns how many were there to remove. */
    public static synchronized int dropAliases(LibraryStore store, List<String[]> which) throws IOException {
        if (!Files.exists(nodesFile(store))) return 0;
        Map<String, Set<String>> byLabel = new LinkedHashMap<>();
        for (String[] w : which) byLabel.computeIfAbsent(w[0], k -> new LinkedHashSet<>()).add(w[1]);
        int removed = 0;
        List<String> out = new ArrayList<>();
        for (String line : Files.readAllLines(nodesFile(store), StandardCharsets.UTF_8)) {
            Matcher m = Pattern.compile("^(- .+? — [^:]+: )(.*?)( \\| also: )(.*?)( \\| wikidata: .*)?$").matcher(line);
            if (!m.matches() || !byLabel.containsKey(m.group(2).strip())) { out.add(line); continue; }
            List<String> keep = new ArrayList<>();
            for (String a : Vocabulary.names(m.group(4))) { if (byLabel.get(m.group(2).strip()).contains(a)) removed++; else keep.add(a); }
            out.add(m.group(1) + m.group(2) + (keep.isEmpty() ? "" : m.group(3) + Vocabulary.list(keep)) + (m.group(5) == null ? "" : m.group(5)));
        }
        Files.write(nodesFile(store), out, StandardCharsets.UTF_8);
        writeNodes(store, Vocabulary.read(nodesFile(store)));   // every line with its kind alone before the colon, as every write keeps it
        // a name taken away takes its source rows with it
        Path f = aliasSourcesFile(store);
        if (removed > 0 && Files.exists(f)) {
            Graph g = build(store);
            Set<String> gone = new HashSet<>();
            for (String[] w : which) gone.add(g.nodeIdOf(w[0]) + "\t" + w[1].strip());
            List<String> keep = new ArrayList<>();
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) { String[] p = line.split("\t"); if (p.length < 3 || !gone.contains(g.nodeIdOf(p[0]) + "\t" + p[1].strip())) keep.add(line); }
            Files.write(f, keep, StandardCharsets.UTF_8);
        }
        return removed;
    }

    /**
     * Set a node's kind: "person", "place", "event", or any other words. The label, the other names and the Wikidata id stay. Returns the
     * kind as it is kept and read: the words given, without the ones older versions wrote beside a person's kind.
     */
    public static synchronized String setKind(LibraryStore store, String name, String kind) throws IOException {
        Graph g = build(store);
        String id = g.nodeIdOf(name);
        Vocabulary nodes = Vocabulary.read(nodesFile(store));
        Vocabulary.Term t = nodes.get(id);
        Node n = g.node(id);
        String k = kind(kind != null ? kind.replace(":", " ") : n != null ? n.kind() : t == null ? "concept" : kindOf(t.description()));
        String label = n != null ? n.label() : t != null && t.description().contains(":") ? t.description().substring(t.description().indexOf(':') + 1).strip() : name;
        nodes.put(new Vocabulary.Term(id, k + ": " + label, t == null ? List.of() : t.also(), t == null ? "" : t.wikidata()));
        writeNodes(store, nodes);
        return k;
    }

    public static synchronized void alias(LibraryStore store, String name, List<String> variants) throws IOException {
        Graph g = build(store);
        String id = g.nodeIdOf(name);
        Vocabulary nodes = Vocabulary.read(nodesFile(store));
        Node n = g.node(id);
        Vocabulary.Term curated = nodes.get(id);
        // a node the person already described keeps its kind: one with no claim yet is not in the computed graph, and describing it again
        // from there turned "person" into "concept"
        nodes.alias(id, curated != null && !curated.description().isBlank() ? plain(curated.description())
                : n == null ? "concept: " + name : n.kind() + ": " + n.label(), variants);
        writeNodes(store, nodes);
    }

    static Path aliasSourcesFile(LibraryStore store) { return dir(store).resolve("alias-sources.tsv"); }

    /**
     * Other names, and where each came from: a family's notes, a tree site, a register. Kept beside the names (name, other name, source,
     * date), so a name form only a tree site gives can be weighed as a clue, and read back through merges.
     */
    public static synchronized void alias(LibraryStore store, String name, List<String> variants, String source) throws IOException {
        alias(store, name, variants);
        if (source == null || source.isBlank()) return;
        Path f = aliasSourcesFile(store);
        Set<String> have = new HashSet<>();
        if (Files.exists(f)) for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) { String[] p = line.split("\t"); if (p.length >= 3) have.add(p[0] + "\t" + p[1] + "\t" + p[2]); }
        StringBuilder b = new StringBuilder();
        for (String v : variants) {
            String row = name.replace('\t', ' ') + "\t" + v.replace('\t', ' ') + "\t" + source.replace('\t', ' ');
            if (have.add(row)) b.append(row).append('\t').append(LocalDate.now()).append('\n');
        }
        if (b.length() == 0) return;
        Files.createDirectories(dir(store));
        Files.writeString(f, b.toString(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /** Where each other name of a person came from: the other name → the files and pages that gave it, in the order they came. */
    public static Map<String, List<String>> aliasSources(LibraryStore store, String name) throws IOException {
        Map<String, List<String>> out = new LinkedHashMap<>();
        Path f = aliasSourcesFile(store);
        if (!Files.exists(f)) return out;
        Graph g = build(store);
        String id = g.nodeIdOf(name);
        for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
            String[] p = line.split("\t");
            if (p.length < 3 || !g.nodeIdOf(p[0]).equals(id)) continue;
            List<String> from = out.computeIfAbsent(p[1], k -> new ArrayList<>());
            if (!from.contains(p[2])) from.add(p[2]);
        }
        return out;
    }

    public static synchronized void link(LibraryStore store, String name, String wikidata) throws IOException {
        Graph g = build(store);
        String id = g.nodeIdOf(name);
        Vocabulary nodes = Vocabulary.read(nodesFile(store));
        if (nodes.get(id) == null) { Node n = g.node(id); nodes.put(new Vocabulary.Term(id, (n == null ? "concept" : n.kind()) + ": " + (n == null ? name : n.label()), List.of(), "")); }
        nodes.link(id, wikidata);
        writeNodes(store, nodes);
    }

    /** The label of the node a name is, as the library writes it; null when no node has that name. Old and new kanji forms are one. */
    public static String named(LibraryStore store, String name) throws IOException {
        Graph g = build(store);
        for (String t : new String[]{name, KanjiForms.modern(name)}) { Node n = g.node(g.nodeIdOf(t)); if (n != null) return n.label(); }
        return null;
    }

    /**
     * Take relations out of predicates.md: the lines of those entries go, and every other line stays as it is, the file's own heading and
     * the owner's notes between the entries too. Returns how many of the relations were there.
     */
    public static synchronized int forgetPredicates(LibraryStore store, Set<String> slugs) throws IOException {
        if (!Files.exists(predicatesFile(store))) return 0;
        List<String> keep = new ArrayList<>();
        Set<String> gone = new HashSet<>();
        for (String line : Files.readAllLines(predicatesFile(store), StandardCharsets.UTF_8)) {
            String slug = line.startsWith("- ") ? line.substring(2).split(" \\| ")[0].split(" — ", 2)[0].strip() : null;
            if (slug != null && slugs.contains(slug)) { gone.add(slug); continue; }
            keep.add(line);
        }
        if (!gone.isEmpty()) Files.write(predicatesFile(store), keep, StandardCharsets.UTF_8);
        return gone.size();
    }

    /** The owner's own relations, as predicates.md has them. */
    public static Vocabulary declaredPredicates(LibraryStore store) throws IOException { return Vocabulary.read(predicatesFile(store)); }

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
        Set<String> apart = differentPairs(store);
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < ns.size(); i++) {
            for (int j = i + 1; j < ns.size(); j++) {
                Node a = ns.get(i), b = ns.get(j);
                if (apart.contains(pair(a.id(), b.id()))) continue;
                if (!a.kind().equals(b.kind()) && !a.kind().equals("concept") && !b.kind().equals("concept")) continue;
                String ka = key(a.label()), kb = key(b.label());
                boolean same = ka.equals(kb);
                // two places are one only when the place and the place it lies in agree: Springfield, Illinois is not Springfield, Ohio
                if (!same && a.kind().equals("place") && b.kind().equals("place")) same = samePlace(a.label(), b.label());
                else if (!same) {
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

    // a Japanese address from the prefecture down: the prefecture is a closed list, the rest end in 郡, 市, 区, 町 or 村
    private static final Pattern PREFECTURE = Pattern.compile("^(東京都|北海道|京都府|大阪府|[^都道府県]{2,3}県)");
    private static final Pattern DIVISION = Pattern.compile(".+?(?:郡|市|区|町|村)");

    /**
     * A place's parts, the smallest first, each folded for comparing: "Springfield, Sangamon, Illinois" gives springfield, sangamon,
     * illinois; 広島県安芸郡府中町 gives 府中町, 安芸郡, 広島県. A name with no commas and no Japanese divisions is one part.
     */
    static List<String> placeParts(String label) {
        String l = KanjiForms.modern(label == null ? "" : label).strip();
        List<String> out = new ArrayList<>();
        if (l.matches(".*[,，、].*")) {
            for (String part : l.split("[,，、]")) if (!key(part).isEmpty()) out.add(key(part));
            return out;
        }
        if (l.codePoints().anyMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN)) {
            String rest = l.replaceAll("\\s+", "");
            List<String> big = new ArrayList<>();
            Matcher m = PREFECTURE.matcher(rest);
            if (m.find() && m.end() < rest.length()) { big.add(m.group(1)); rest = rest.substring(m.end()); }
            m = DIVISION.matcher(rest);
            int at = 0;
            while (m.find() && m.start() == at) {
                // 四日市市, 大村市: a division word alone belongs to the name before it
                if (m.group().length() == 1 && !big.isEmpty()) big.set(big.size() - 1, big.get(big.size() - 1) + m.group()); else big.add(m.group());
                at = m.end();
            }
            if (at < rest.length()) { String left = rest.substring(at); if (left.matches("[郡市区町村]") && !big.isEmpty()) big.set(big.size() - 1, big.get(big.size() - 1) + left); else big.add(left); }
            for (int i = big.size() - 1; i >= 0; i--) out.add(big.get(i));
            return out;
        }
        out.add(key(l));
        return out;
    }

    /**
     * Whether two places may be one: both name the place and something it lies in, the place itself agrees, and what the one
     * with fewer parts says it lies in the other says as well, in the same order.
     */
    static boolean samePlace(String a, String b) {
        List<String> pa = placeParts(a), pb = placeParts(b);
        if (pa.size() < 2 || pb.size() < 2 || !pa.get(0).equals(pb.get(0))) return false;
        List<String> few = pa.size() <= pb.size() ? pa : pb, many = few == pa ? pb : pa;
        int at = 1;
        for (String part : few.subList(1, few.size())) {
            while (at < many.size() && !many.get(at).equals(part)) at++;
            if (at == many.size()) return false;
            at++;
        }
        return true;
    }

    /** Diacritics stripped, punctuation dropped, tokens sorted — the identity-proposal key. */
    static String key(String s) {
        String n = Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        n = Vocabulary.norm(n).replaceAll("[^\\p{L}\\p{N} ]", " ").replaceAll("\\s+", " ").strip();
        String[] toks = n.split(" ");
        Arrays.sort(toks);
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
