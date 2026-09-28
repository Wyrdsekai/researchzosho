package org.researchzosho.librarian;

import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * THE FAMILY SUMMARY, for discovery: what the sources settle about the owner's close family, one person after another, grouped by how they
 * are related to the owner and nearest first, each line with where it comes from; then the people who are easy to mix up, where the owner's
 * notes and the sources disagree, and what is not settled. A person reads it once and sees what the library holds, what agrees, and where
 * their own notes may be wrong. Everything is read from genealogy's linked view ({@link FamilyPeople#view}, {@link FamilyNameHistory},
 * {@link FamilyLinks}, {@link FamilyClose}), never worked out again here, and nothing asks a model or the web. {@code genealogy summary}
 * prints it and the page /summary shows the same, each name a link to the person's page.
 */
public final class FamilySummary {

    private FamilySummary() { }

    // ── what a summary holds ────────────────────────────────────────────────────────────────────────────────────────────

    /** A piece of a line: words ({@code label} null), or a person written by their heading and found by their label. */
    record Part(String text, String label) { }

    /** One line: its parts in order. */
    /** {@code relation}: a line of relatives (parents, a husband or wife, children, brothers and sisters). */
    record Line(List<Part> parts, boolean relation) {
        Line(List<Part> parts) { this(parts, false); }
        String plain() { StringBuilder b = new StringBuilder(); for (Part p : parts) b.append(p.text()); return b.toString(); }
    }

    /** One person: the entry, its label, the heading a person reads, who they are to the owner ("" when not known or already the heading), the lines. */
    record Person(String id, String label, String heading, String relation, List<Line> lines) { }

    /** People under one heading, nearest first. */
    record Group(String heading, List<Person> people) { }

    /** {@code ownerKnown}: whether the library knows which person is its owner, so that relations are said; {@code all}: everyone in the family, not close family only. */
    record Summary(boolean ownerKnown, boolean all, List<Group> groups, List<Line> mixUps, List<Line> disagree, List<Line> notSettled) {
        int people() { return groups.stream().mapToInt(g -> g.people().size()).sum(); }
    }

    /** The groups, nearest first ({@link #group}), and the last for the people the owner's notes name who are in none of the others. */
    static final List<String> HEADINGS = List.of(
            "You and your brothers and sisters, and their husbands, wives and children",
            "Your parents",
            "Your parents' brothers and sisters, and their husbands, wives and children",
            "Your grandparents",
            "Your grandparents' brothers and sisters, and their husbands, wives and children",
            "Your great-grandparents",
            "Your great-grandparents' brothers and sisters, and their husbands, wives and children",
            "Further back",
            "Other people your notes name");

    /** The group of the ancestors beyond the great-grandparents, and the group of the people the owner's notes name. */
    static final int FURTHER = 7, OTHERS = 8;

    /** What a block says of a relative the library knows only by the owner's words for them. */
    static final String NO_NAME = "No source gives a name for this person.";

    /** The heading of the one group a summary has while the library does not know its owner. */
    static final String NAMED_BY_FILES = "The people your family's own files name";

    static final String MIX_UP = "People who are easy to mix up", DISAGREE = "Where your notes and the sources disagree", NOT_SETTLED = "Not settled";

    /**
     * The group of a relation to the owner: 0 for you and your brothers and sisters, … 6 for the great-grandparents' brothers and sisters,
     * {@link #FURTHER} for the ancestors beyond the great-grandparents. The brothers and sisters of a generation go with their husbands, wives
     * and children.
     */
    static int group(FamilyClose.Kin k) {
        int u = k.up(), d = k.down();
        if (u == 0 || u == 1 && d >= 1) return 0;
        if (u == 1) return 1;
        if (u == 2) return d >= 1 ? 2 : 3;
        if (u == 3) return d >= 1 ? 4 : 5;
        if (u == 4 && d >= 1) return 6;
        return FURTHER;
    }

    private static final Pattern UNNAMED = Pattern.compile("(?:^|[\\s　])[?？]+(?:[\\s　]|$)");
    private static final String KIN_JA = "(?:実|養|継|義|曾|曽|高)?(?:父母|父|母|両親|祖父母|祖父|祖母|妻|夫|子|息子|娘|兄|弟|姉|妹|兄弟|姉妹|長男|次男|三男|長女|次女|三女|孫|甥|姪|叔父|伯父|叔母|伯母)";
    private static final Pattern DESCRIPTION_JA = Pattern.compile("^.+の" + KIN_JA + "(?:の" + KIN_JA + ")*$");

    /** Whether a label describes a person instead of naming them: "X's father" ({@link FamilyQuestions#placeholder}), or Xの父, Xの父の父, Xの長男 in Japanese. */
    static boolean description(String label) { return FamilyQuestions.placeholder(label) || DESCRIPTION_JA.matcher(bare(label)).matches(); }

    /** "an unnamed brother or sister", "three unnamed children": the people a tree site left unnamed, in a list of one kind of relative. */
    static String unnamedSaid(int n, String word) {
        String plural = word.equals("Brothers and sisters") ? "brothers or sisters" : word.toLowerCase(Locale.ROOT);
        String singular = plural.equals("brothers or sisters") ? "brother or sister" : plural.replace("children", "child").replaceFirst("parents$", "parent");
        return n == 1 ? "an unnamed " + singular : counted(n) + " unnamed " + plural;
    }

    static String counted(int n) {
        String[] w = {"", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten"};
        return n < w.length ? w[n] : String.valueOf(n);
    }

    // the short names of the owner's own sources
    static final String NOTES = "your notes", TOLD = "what you told the library", LINK_NOTES = "your link notes", ANSWER = "your answer", GENI = "Geni";

    // ── the summary, worked out ─────────────────────────────────────────────────────────────────────────────────────────

    /** The summary of the library's family: close family and the people the owner's notes name, or with {@code all} everyone the library places in the family. */
    public static Summary of(LibraryStore store, boolean all) throws IOException { return new Build(store).summary(all); }

    /** One piece of a line at a time. */
    private static final class Words {
        final List<Part> parts = new ArrayList<>();
        Words w(String s) { if (s != null && !s.isEmpty()) parts.add(new Part(s, null)); return this; }
        Words p(String heading, String label) { parts.add(new Part(heading, label)); return this; }
        Line line() { return line(false); }
        Line line(boolean relation) { return new Line(List.copyOf(parts), relation); }
    }

    /** The relatives of one kind: each with the claims that say it, and the reason it was worked out where no claim files it. */
    private static final class Rel {
        final List<String> claims = new ArrayList<>();
        final List<String> worked = new ArrayList<>();
    }

    /** A date or a place as one source gives it: its words, the date read from them (null for a place), its month and day (0 when not given), the claims. */
    private record Value(String text, FamilyDate date, int[] md, List<String> claims) { }

    private static final class Build {
        final LibraryStore store;
        final Graph g;
        final FamilyNameHistory.Index idx;
        final FamilyClose.Close close;
        final Map<String, Finding> byId = new HashMap<>();
        final Map<String, List<Graph.Edge>> touching = new HashMap<>();
        final Map<String, Map<String, List<String>>> sexes;
        final Map<String, List<FamilyKin.Link>> parents;
        final FamilyLinks.Result links;
        final Map<String, String> workedWhy = new HashMap<>();                  // child \t parent → why it was worked out
        final Map<String, FamilyLinks.Link> mentionLinks = new LinkedHashMap<>();   // claim|side → the link that joins that mention
        final Map<String, List<FamilyLinks.Link>> joinedInto = new LinkedHashMap<>();   // person → whole entries joined into them, probable
        final List<Line> unsettled = new ArrayList<>();
        /** Each person's relation to the owner: the close-family walk's, and above it the owner's line followed up without a limit ({@link #further}). */
        final Map<String, FamilyClose.Kin> kinOf = new HashMap<>();
        final Set<String> saidBoth = new HashSet<>();
        /** Pairs of entries the evidence holds as possibly one person, and joins nothing. */
        final Set<String> possiblePairs = new HashSet<>();
        private Map<String, List<String>> hanKeys;

        Build(LibraryStore store) throws IOException {
            this.store = store;
            this.g = FamilyPeople.view(store);
            this.idx = FamilyNameHistory.of(g);
            this.close = FamilyClose.of(g);
            for (Finding f : FamilyPeople.findings(g)) byId.put(f.id(), f);
            for (Graph.Edge e : g.edges()) {
                if (FamilyKin.gone(e)) continue;
                touching.computeIfAbsent(e.from(), k -> new ArrayList<>()).add(e);
                if (!e.to().equals(e.from())) touching.computeIfAbsent(e.to(), k -> new ArrayList<>()).add(e);
            }
            this.sexes = FamilyKin.sexes(g);
            this.parents = FamilyKin.parents(g);
            this.links = g.links() == null ? null : FamilyLinks.current(store);
            if (links != null) {
                for (String[] w : links.workedOut()) workedWhy.putIfAbsent(resolve(w[0]) + "\t" + resolve(w[2]), w[4]);
                for (FamilyLinks.Link l : links.links()) {
                    if (!l.joins()) { possiblePairs.add(Graph.pair(resolve(l.node()), resolve(l.person()))); continue; }
                    if (l.mention()) mentionLinks.put(Graph.Links.side(l.claim(), l.side().equals("subject")), l);
                    else if (l.grade() == FamilyLinks.Grade.probable && !l.node().equals(l.person())) joinedInto.computeIfAbsent(resolve(l.person()), k -> new ArrayList<>()).add(l);
                }
            }
        }

        /** The entry of the view an entry of the links is: itself, or the one it was joined into. */
        String resolve(String id) {
            String at = id;
            for (int i = 0; i < 8 && g.node(at) == null && g.links() != null && g.links().nodes().containsKey(at); i++) at = g.links().nodes().get(at);
            return at;
        }

        FamilyClose.Kin kin(String id) { return kinOf.get(id); }

        String said(String id) { FamilyClose.Kin k = kinOf.get(id); return k == null ? "" : k.said(); }

        /**
         * Where the family comes from, past the generations close family counts: every ancestor on the owner's own line, in every
         * generation, followed up through the birth parents on file, with their husbands and wives; with {@code all}, their brothers and
         * sisters too. The questions keep close family as it is; only the summary goes further.
         */
        void further(boolean all) {
            for (Graph.Node n : g.nodes()) { FamilyClose.Kin k = close.kin(n.id()); if (k != null) kinOf.put(n.id(), k); }
            ArrayDeque<String> todo = new ArrayDeque<>();
            for (Map.Entry<String, FamilyClose.Kin> e : kinOf.entrySet()) if (line(e.getValue())) todo.add(e.getKey());
            List<String> ancestors = new ArrayList<>(todo);
            while (!todo.isEmpty()) {
                String id = todo.poll();
                for (FamilyKin.Link p : parents.getOrDefault(id, List.of())) {
                    if (!isPerson(p.other()) || kinOf.containsKey(p.other())) continue;
                    kinOf.put(p.other(), grown(kinOf.get(id), 'u', FamilyClose.sexOf(sexes, p.other())));
                    ancestors.add(p.other());
                    todo.add(p.other());
                }
            }
            for (String a : ancestors) {
                FamilyClose.Kin k = kinOf.get(a);
                for (Graph.Edge e : touching.getOrDefault(a, List.of())) {
                    String other = e.from().equals(a) ? e.to() : e.from();
                    if (other.equals(a) || !isPerson(other) || kinOf.containsKey(other)) continue;
                    if (e.predicate().equals("married-to")) kinOf.put(other, grown(k, 'm', FamilyClose.sexOf(sexes, other)));
                    else if (all && e.predicate().equals("sibling-of")) kinOf.put(other, grown(k, 's', FamilyClose.sexOf(sexes, other)));
                }
                if (!all) continue;
                // a brother or sister is a child of the same birth parent
                for (FamilyKin.Link p : parents.getOrDefault(a, List.of())) for (Graph.Edge e : touching.getOrDefault(p.other(), List.of())) {
                    String child = e.predicate().equals("parent-of") && e.from().equals(p.other()) ? e.to() : e.predicate().equals("child-of") && e.to().equals(p.other()) ? e.from() : null;
                    if (child != null && isPerson(child) && !kinOf.containsKey(child)) kinOf.put(child, grown(k, 's', FamilyClose.sexOf(sexes, child)));
                }
            }
        }

        /** A relation on the owner's line one step further: to a parent, a husband or wife, or a brother or sister. */
        static FamilyClose.Kin grown(FamilyClose.Kin k, char way, String sex) {
            List<FamilyClose.Step> path = new ArrayList<>(k.path());
            path.add(new FamilyClose.Step(way, sex));
            return switch (way) {
                case 'u' -> new FamilyClose.Kin(k.up() + 1, 0, false, path);
                case 'm' -> new FamilyClose.Kin(k.up(), k.down(), true, path);
                default -> new FamilyClose.Kin(k.up() + 1, 1, false, path);
            };
        }

        /** Whether a relation is on the owner's own line or married to it: parents only, and perhaps a husband or wife last. */
        static boolean ancestral(FamilyClose.Kin k) {
            if (k == null || k.down() != 0) return false;
            List<FamilyClose.Step> p = k.path();
            for (int i = 0; i < p.size(); i++) if (p.get(i).way() != 'u' && !(i == p.size() - 1 && p.get(i).way() == 'm')) return false;
            return true;
        }

        boolean isPerson(String id) {
            Graph.Node n = g.node(id);
            return n != null && "person".equals(n.kind()) && !FamilyHouses.isFamily(g, id) && !FamilyQuestions.unknown(n.label());
        }

        String labelOf(String id) { Graph.Node n = g.node(id); return n == null ? id : n.label(); }

        /** Whether an entry stands for somebody a tree site left unnamed ("? Ito", "? Ito (Geni 4786)"): a question mark in place of the name. */
        boolean unnamed(String id) { return UNNAMED.matcher(bare(labelOf(id))).find(); }

        /**
         * Whether an entry gets a block of its own: not somebody unnamed, and not a description of somebody ("X's father", Xの父), unless the
         * description is the owner's own words for a relative, whose block is headed by them.
         */
        boolean blockable(String id) { return !unnamed(id) && (!description(labelOf(id)) || ownerLike(id)); }

        boolean ownerLike(String id) { return labelOf(id).strip().toLowerCase(Locale.ROOT).startsWith(FamilyClose.OWNER); }

        /** A person as a line names them: their heading ("森田健二 (born 遠藤)"); the owner "you", and a relative the library knows only by the owner's words for them by those words. */
        String heading(String id) {
            if (ownerLike(id)) { String w = said(id); if (!w.isEmpty()) return w; }
            if (g.node(id) == null) return id;
            // a person described by a book's writer ("the writer of <file>'s mother"): the book by its title, not its file name
            Matcher m = WRITER.matcher(labelOf(id));
            if (m.matches()) return "the writer of " + title(m.group(1)) + (m.group(2) == null ? "" : m.group(2));
            return FamilyNamePages.named(idx, g, id);
        }

        Words person(Words b, String id) { return b.p(heading(id), labelOf(id)); }

        // ── who is in it ──

        Summary summary(boolean all) throws IOException {
            List<List<String>> placed = new ArrayList<>();
            for (int i = 0; i < HEADINGS.size(); i++) placed.add(new ArrayList<>());
            Set<String> listed = new LinkedHashSet<>();
            boolean known = close.known();
            if (known) {
                further(all);
                for (Graph.Node n : g.nodes()) {
                    if (!isPerson(n.id()) || !blockable(n.id())) continue;
                    FamilyClose.Kin k = kin(n.id());
                    // close family, and where the family comes from: every ancestor on your line with their husbands and wives
                    if (k == null || !(all || k.close() || ancestral(k))) continue;
                    placed.get(group(k)).add(n.id());
                    listed.add(n.id());
                }
                // the people the owner's own words name, who are in no group above
                for (Graph.Edge e : g.edges()) {
                    if (FamilyKin.gone(e) || !close.ownerSaid(byId.get(e.findingId()))) continue;
                    for (String id : List.of(e.from(), e.to())) if (isPerson(id) && blockable(id) && !ownerLike(id) && listed.add(id)) placed.get(OTHERS).add(id);
                }
            } else {
                for (Graph.Node n : g.nodes()) if (isPerson(n.id()) && blockable(n.id()) && close.close(n.id()) && listed.add(n.id())) placed.get(OTHERS).add(n.id());
            }
            List<Group> groups = new ArrayList<>();
            for (int i = 0; i < placed.size(); i++) {
                List<String> ids = placed.get(i);
                if (ids.isEmpty()) continue;
                ids.sort(nearest());
                List<Person> people = new ArrayList<>();
                // somebody the library holds nothing about beyond one relation has no block: the relative's list names them
                for (String id : ids) { Person p = personOf(id); if (!thin(p)) people.add(p); }
                if (people.isEmpty()) continue;
                groups.add(new Group(known ? HEADINGS.get(i) : NAMED_BY_FILES, people));
            }
            Set<String> family = new LinkedHashSet<>(listed);
            if (known) for (Graph.Node n : g.nodes()) if (isPerson(n.id()) && kin(n.id()) != null) family.add(n.id());
            List<Line> mix = mixUps(listed, family);
            List<Line> disagree = disagreements();
            List<Line> notSettled = new ArrayList<>(unsettled);
            // a parent and a child written as husband and wife too: the view set the parent claim aside ({@link FamilyDoubts#marriedAndParent}), and the summary says so once
            Set<String> wedParents = new HashSet<>();
            for (String[] pair : FamilyDoubts.marriedAndParent(g))
                if (isPerson(pair[0]) && isPerson(pair[1]) && wedParents.add(Graph.pair(pair[0], pair[1])))
                    notSettled.add(person(person(new Words(), pair[0]).w(" and "), pair[1]).w(" are " + FamilyDoubts.MARRIED_AND_PARENT + ".").line());
            if (known) notSettled.addAll(unnamedParents(listed));
            notSettled.addAll(notKnown(placed));
            return new Summary(known, all, groups, once(mix), once(disagree), once(notSettled));
        }

        /** Whether the library holds no fact about a person beyond one relation: no name, no birth or death, and one relative in all. */
        static boolean thin(Person p) {
            int relatives = 0;
            for (Line l : p.lines()) {
                if (l.plain().equals(NO_NAME)) continue;
                if (!l.relation()) return false;
                for (Part x : l.parts()) if (x.label() != null) relatives++;
            }
            return relatives <= 1;
        }

        /** Lines that read the same said once: one person in two entries makes the same line twice. */
        static List<Line> once(List<Line> lines) {
            Set<String> seen = new HashSet<>();
            List<Line> out = new ArrayList<>();
            for (Line l : lines) if (seen.add(l.plain())) out.add(l);
            return out;
        }

        /** Nearest first within a group: by the words of the relation (a husband or wife, then children, then brothers and sisters, then parents; a father before a mother), then by birth, then by name. */
        Comparator<String> nearest() {
            Comparator<String> byWords = (a, b) -> {
                FamilyClose.Kin ka = kin(a), kb = kin(b);
                List<String> wa = ka == null ? List.of() : FamilyClose.words(ka.path()), wb = kb == null ? List.of() : FamilyClose.words(kb.path());
                for (int i = 0; i < Math.min(wa.size(), wb.size()); i++) {
                    int c = Integer.compare(rank(wa.get(i)), rank(wb.get(i)));
                    if (c != 0) return c;
                }
                return Integer.compare(wa.size(), wb.size());
            };
            return byWords.thenComparingInt((String id) -> { FamilyDate b = idx.born(id); return b == null ? Integer.MAX_VALUE : b.year(); }).thenComparing(this::heading);
        }

        static int rank(String word) {
            return switch (word) {
                case "husband", "wife", "husband or wife" -> 0;
                case "son", "daughter", "child" -> 1;
                case "brother", "sister", "brother or sister" -> 2;
                case "father" -> 3;
                case "mother" -> 4;
                default -> 5;
            };
        }

        // ── one person ──

        Person personOf(String id) {
            String label = labelOf(id), relation = said(id);
            // the owner, or a relative the library knows only by the owner's words for them: the heading is the relation
            boolean described = ownerLike(id) && !relation.isEmpty();
            List<Line> lines = new ArrayList<>();
            if (described && !relation.equals("you")) lines.add(new Words().w(NO_NAME).line());
            lines.addAll(names(id));
            lines.addAll(joins(id));
            lines.addAll(life(id, "Born", "born"));
            lines.addAll(life(id, "Died", "died"));
            lines.addAll(relations(id));
            return new Person(id, label, cap(heading(id)), described ? "" : relation, lines);
        }

        /** The person's names over the life, one line each: the name, its other forms in brackets, how it came and its years, and where it comes from. */
        List<Line> names(String id) {
            List<FamilyNameHistory.Name> ns = idx.names(id);
            String label = labelOf(id);
            boolean described = description(label);
            List<Line> out = new ArrayList<>();
            boolean onlyTheLabel = ns.size() == 1 && ns.get(0).implicit() && otherForms(ns.get(0)).isEmpty();
            if (onlyTheLabel) return out;
            // the names of the life in their order, then those carried beside them (a Christian name, a pen name)
            List<FamilyNameHistory.Name> ordered = new ArrayList<>(ns.stream().filter(FamilyNameHistory.Name::replaces).toList());
            ordered.addAll(ns.stream().filter(n -> !n.replaces()).toList());
            for (FamilyNameHistory.Name n : ordered) {
                // a description ("the owner of this library's father") is no name of the person
                if (described && n.implicit() && !n.workedOut() && n.isForm(label)) continue;
                List<String> forms = otherForms(n);
                String what = n.implicit() && !n.workedOut() ? (n.isForm(label) ? "the name your library files this person under" : "another name your library has for this person; no source says how it came")
                        : FamilyNamePages.how(n.kind(), n.family());
                String worked = n.workedOut() ? " (" + n.workedOutWords() + ")" : "";
                List<String> src = n.implicit() ? fewSourcesOf(writing(id, n)) : fewSourcesOf(n.claims());
                out.add(new Words().w(n.written() + (forms.isEmpty() ? "" : " (" + String.join(", ", forms) + ")") + ": " + what + FamilyNamePages.yearsSaid(idx, id, n) + worked + "." + after(src)).line());
            }
            return out;
        }

        List<String> otherForms(FamilyNameHistory.Name n) {
            List<String> out = new ArrayList<>();
            for (String t : n.texts()) if (!t.equals(n.written()) && !out.contains(t)) out.add(t);
            return out;
        }

        /** The claims about a person that write them by one name: where a name no claim gives is written. */
        List<String> writing(String id, FamilyNameHistory.Name n) {
            List<String> out = new ArrayList<>();
            for (Graph.Edge e : touching.getOrDefault(id, List.of())) {
                Finding f = byId.get(e.findingId());
                if (f == null || f.triple() == null || out.contains(f.id())) continue;
                for (boolean subject : new boolean[]{true, false}) {
                    if (!id.equals(g.nodeOf(f, subject))) continue;
                    String written = subject ? f.triple().subject() : f.triple().object();
                    if (n.isForm(written) || n.isForm(FamilyNameQuestions.bareName(written))) { out.add(f.id()); break; }
                }
            }
            return out;
        }

        /** The entries the library joined into this person from the evidence, probable ones: where the join matters, it is said. */
        List<Line> joins(String id) {
            List<Line> out = new ArrayList<>();
            Set<String> said = new HashSet<>();
            for (FamilyLinks.Link l : joinedInto.getOrDefault(id, List.of())) {
                if (!said.add(Vocabulary.norm(l.written()))) continue;
                out.add(new Words().w("\"" + l.written() + "\" is the same person (the library joined these as one person: probable, " + reason(l) + ").").line());
            }
            return out;
        }

        // ── born and died ──

        List<Line> life(String id, String word, String prefix) {
            List<Value> dates = new ArrayList<>(), places = new ArrayList<>();
            for (Graph.Edge e : touching.getOrDefault(id, List.of())) {
                if (!e.from().equals(id) || Graph.WORKED_OUT.equals(e.state())) continue;
                Finding f = byId.get(e.findingId());
                if (f == null || f.triple() == null) continue;
                String what = f.triple().object() == null ? "" : f.triple().object().strip();
                if (e.predicate().equals(prefix + "-on")) {
                    FamilyDate d = FamilyDate.parse(what);
                    if (d != null) dates.add(new Value(what, d, monthDay(what), List.of(f.id())));
                } else if (e.predicate().equals(prefix + "-in") && !what.isEmpty()) {
                    places.add(new Value(what, null, new int[]{0, 0}, List.of(f.id())));
                    FamilyDate d = FamilyChecks.claimDate(f);   // a place filed with its date, as a tree file's event is
                    if (d != null) dates.add(new Value(d.written(), d, monthDay(d.written()), List.of(f.id())));
                }
            }
            List<List<Value>> dc = clusters(dates, true), pc = clusters(places, false);
            if (dc.isEmpty() && pc.isEmpty()) return List.of();
            // one date and one place, each source giving the date as fully as the others: the sources once, at the end
            boolean one = dc.size() <= 1 && pc.size() <= 1 && dc.stream().allMatch(c -> c.stream().allMatch(v -> level(v) == level(best(c))));
            Words b = new Words().w(word);
            for (int i = 0; i < dc.size(); i++) b.w((i == 0 ? " " : " or ") + dateShown(best(dc.get(i))) + (one ? "" : dateSources(dc.get(i))));
            for (int i = 0; i < pc.size(); i++) b.w((i == 0 ? (dc.isEmpty() || one ? " in " : ", in ") : " or ") + best(pc.get(i)).text() + (one ? "" : after(sourcesOf(claims(pc.get(i))))));
            List<String> all = new ArrayList<>();
            for (List<Value> c : dc) all.addAll(claims(c));
            for (List<Value> c : pc) all.addAll(claims(c));
            b.w(".");
            if (one) b.w(after(sourcesOf(all)));
            if (dc.size() > 1) {
                Words u = person(new Words(), id).w("'s " + (prefix.equals("born") ? "birth" : "death") + ": the sources give ");
                for (int i = 0; i < dc.size(); i++) u.w((i == 0 ? "" : i == dc.size() - 1 ? " and " : ", ") + dateShown(best(dc.get(i))) + " (" + String.join("; ", sourcesOf(claims(dc.get(i)))) + ")");
                unsettled.add(u.w(".").line());
            }
            return List.of(b.line());
        }

        /**
         * Where a date comes from, in brackets: the sources that give it as fully as it is shown, then each source that gives less of it ("Nikkei
         * Farmer gives 1889"), so a day one source gives is never put on another.
         */
        String dateSources(List<Value> c) {
            Value top = best(c);
            List<String> full = new ArrayList<>();
            for (Value v : c) if (level(v) == level(top)) full.addAll(v.claims());
            List<String> said = new ArrayList<>(sourcesOf(full));
            // each other source once, with the date as it gives it; sources that give it alike, together
            Map<String, String> gives = new LinkedHashMap<>();   // a source → the date as it gives it
            for (Value v : c) if (level(v) != level(top)) for (String l : sourcesOf(v.claims())) if (!said.contains(l)) gives.putIfAbsent(l, dateShown(v));
            Map<String, List<String>> byDate = new LinkedHashMap<>();
            gives.forEach((l, d) -> byDate.computeIfAbsent(d, k -> new ArrayList<>()).add(l));
            byDate.forEach((d, by) -> said.add(String.join(" and ", by) + " " + (by.size() == 1 ? "gives " : "give ") + d));
            return after(said);
        }

        List<String> claims(List<Value> c) { List<String> out = new ArrayList<>(); for (Value v : c) for (String x : v.claims()) if (!out.contains(x)) out.add(x); return out; }

        // ── the relatives ──

        List<Line> relations(String id) {
            Map<String, Rel> birth = new LinkedHashMap<>(), adoptive = new LinkedHashMap<>(), step = new LinkedHashMap<>(), foster = new LinkedHashMap<>(), spouses = new LinkedHashMap<>(),
                    children = new LinkedHashMap<>(), adopted = new LinkedHashMap<>(), stepChildren = new LinkedHashMap<>(), fostered = new LinkedHashMap<>(), siblings = new LinkedHashMap<>();
            for (Graph.Edge e : touching.getOrDefault(id, List.of())) {
                boolean out = e.from().equals(id);
                String other = out ? e.to() : e.from();
                if (other.equals(id) || !isPerson(other)) continue;
                Map<String, Rel> to = switch (e.predicate()) {
                    case "parent-of" -> out ? children : birth;
                    case "child-of" -> out ? birth : children;
                    case "adopted-by" -> out ? adoptive : adopted;
                    case "step-parent-of" -> out ? stepChildren : step;
                    case "foster-child-of" -> out ? foster : fostered;
                    case "married-to" -> spouses;
                    case "sibling-of" -> siblings;
                    case "relative-of" -> {
                        // the owner's own words for a relative one step away ("About Ned Hale: my father"), as the questions read them
                        Finding f = byId.get(e.findingId());
                        String[] d = f != null && close.ownerSaid(f) ? FamilyClose.direct(g, close, e.from(), e.to(), FamilyChecks.quoteOf(f)) : null;
                        if (d == null) yield null;
                        yield switch (d[0]) {
                            case "parent" -> d[2].equals(id) ? birth : d[1].equals(id) ? children : null;
                            case "sibling" -> siblings;
                            default -> spouses;
                        };
                    }
                    default -> null;
                };
                if (to != null) add(to, other, e, id);
            }
            // a brother or sister is a child of a birth parent: the claim that makes them that parent's child says it
            for (String p : birth.keySet()) for (Graph.Edge e : touching.getOrDefault(p, List.of())) {
                String child = e.predicate().equals("parent-of") && e.from().equals(p) ? e.to() : e.predicate().equals("child-of") && e.to().equals(p) ? e.from() : null;
                if (child != null && !child.equals(id) && isPerson(child)) add(siblings, child, e, p);
            }
            // one person written as both a husband or wife and a brother or sister: both stay, and the summary says so. A 婿養子 is his wife's
            // parents' child in law, and a parent claim may read so; or one of the two claims is wrong
            for (String x : spouses.keySet()) {
                if (!siblings.containsKey(x) || !saidBoth.add(Graph.pair(id, x))) continue;
                unsettled.add(person(person(new Words(), id).w(" and "), x).w(" are written as husband and wife and as brother and sister. A son-in-law adopted as heir is both in law; otherwise one of the two is wrong.").line());
            }
            List<Line> out = new ArrayList<>();
            if (birth.size() > 2 || twoOfOneSex(birth.keySet())) {
                Words u = person(new Words(), id).w("'s parents: the sources name ");
                listed(u, birth, id, false, false);
                unsettled.add(u.w(".").line());
            }
            addList(out, "Parents", birth, id);
            addList(out, "Adoptive parents", adoptive, id);
            addList(out, "Step-parents", step, id);
            addList(out, "Foster parents", foster, id);
            List<String> saidSpouses = new ArrayList<>(), unnamedSpouseClaims = new ArrayList<>();
            int unnamedSpouses = 0;
            for (Map.Entry<String, Rel> s : ordered(spouses)) {
                if (unnamed(s.getKey())) { unnamedSpouses++; unnamedSpouseClaims.addAll(s.getValue().claims); continue; }
                List<String> years = new ArrayList<>();
                for (String c : s.getValue().claims) { Finding f = byId.get(c); FamilyDate d = f == null ? null : FamilyChecks.claimDate(f); if (d != null && !years.contains(d.phrase())) years.add(d.phrase()); }
                // a second husband or wife the evidence holds as possibly the first, written another way, says so
                String same = "";
                for (String earlier : saidSpouses) if (possiblySame(s.getKey(), earlier)) { same = " (possibly the same person as " + heading(earlier) + ")"; break; }
                Words b = person(new Words().w("Married to "), s.getKey()).w(years.isEmpty() ? "" : " in " + String.join(" or ", years)).w(same).w(".");
                b.w(note(s.getValue(), id, s.getKey()));
                out.add(b.line(true));
                saidSpouses.add(s.getKey());
            }
            if (unnamedSpouses > 0) out.add(new Words().w("Married to " + (unnamedSpouses == 1 ? "an unnamed husband or wife" : counted(unnamedSpouses) + " unnamed husbands or wives") + "." + after(fewSourcesOf(unnamedSpouseClaims))).line(true));
            addList(out, "Children", children, id);
            addList(out, "Adopted children", adopted, id);
            addList(out, "Step-children", stepChildren, id);
            addList(out, "Foster children", fostered, id);
            addList(out, "Brothers and sisters", siblings, id);
            return out;
        }

        void add(Map<String, Rel> to, String other, Graph.Edge e, String self) {
            Rel r = to.computeIfAbsent(other, k -> new Rel());
            if (Graph.WORKED_OUT.equals(e.state())) {
                String why = workedWhy.getOrDefault(e.from() + "\t" + e.to(), "worked out");
                if (!r.worked.contains(why)) r.worked.add(why);
            } else if (!r.claims.contains(e.findingId())) r.claims.add(e.findingId());
        }

        boolean twoOfOneSex(Collection<String> ids) {
            int men = 0, women = 0;
            for (String p : ids) { String s = FamilyKin.sexOf(sexes, p); if (s.equals("male")) men++; else if (s.equals("female")) women++; }
            return men > 1 || women > 1;
        }

        List<Map.Entry<String, Rel>> ordered(Map<String, Rel> rels) {
            List<Map.Entry<String, Rel>> out = new ArrayList<>(rels.entrySet());
            out.sort(Comparator.comparingInt((Map.Entry<String, Rel> e) -> FamilyKin.sexOf(sexes, e.getKey()).equals("male") ? 0 : FamilyKin.sexOf(sexes, e.getKey()).equals("female") ? 1 : 2)
                    .thenComparingInt(e -> { FamilyDate b = idx.born(e.getKey()); return b == null ? Integer.MAX_VALUE : b.year(); }).thenComparing(e -> heading(e.getKey())));
            return out;
        }

        /** One kind of relative in a row; the people a tree site left unnamed are counted once at the end: "and two unnamed children (Geni)". */
        void addList(List<Line> out, String word, Map<String, Rel> rels, String self) {
            if (rels.isEmpty()) return;
            Map<String, Rel> named = new LinkedHashMap<>();
            List<String> unnamedClaims = new ArrayList<>();
            int unnamedN = 0;
            for (Map.Entry<String, Rel> e : rels.entrySet()) {
                if (unnamed(e.getKey())) { unnamedN++; unnamedClaims.addAll(e.getValue().claims); } else named.put(e.getKey(), e.getValue());
            }
            String unnamedNote = unnamedN == 0 ? "" : after(fewSourcesOf(unnamedClaims));
            Words b = new Words().w(word + ": ");
            // one source note at the end when every relative has the same one
            Set<String> notes = new HashSet<>();
            for (Map.Entry<String, Rel> e : named.entrySet()) notes.add(note(e.getValue(), self, e.getKey()));
            if (unnamedN > 0) notes.add(unnamedNote);
            boolean once = notes.size() == 1;
            listed(b, named, self, !once, unnamedN > 0);
            if (unnamedN > 0) b.w((named.isEmpty() ? "" : " and ") + unnamedSaid(unnamedN, word) + (once ? "" : unnamedNote));
            b.w(".");
            if (once) b.w(notes.iterator().next());
            out.add(b.line(true));
        }

        /** Relatives in a row, "A (your notes), B (Geni) and C (Geni)", each with where it comes from when {@code each}; with commas only when {@code more} follows. */
        void listed(Words b, Map<String, Rel> rels, String self, boolean each, boolean more) {
            List<Map.Entry<String, Rel>> es = ordered(rels);
            for (int i = 0; i < es.size(); i++) {
                if (i > 0) b.w(i == es.size() - 1 && !more ? " and " : ", ");
                person(b, es.get(i).getKey());
                if (each) b.w(note(es.get(i).getValue(), self, es.get(i).getKey()));
            }
        }

        /** Whether two entries may be one person and are not joined: a link the evidence holds as possible, or names in characters one character apart with no recorded sex against it. */
        boolean possiblySame(String a, String b) {
            if (possiblePairs.contains(Graph.pair(a, b))) return true;
            List<String> ka = hanKeys().get(a), kb = hanKeys().get(b);
            if (ka == null || kb == null || !oneApart(ka, kb)) return false;
            String sa = FamilyClose.sexOf(sexes, a), sb = FamilyClose.sexOf(sexes, b);
            return sa.isEmpty() || sb.isEmpty() || sa.equals(sb);
        }

        /**
         * Where a relation comes from, in brackets: its sources, or what it was worked out from; and, where every claim of it rests on a mention
         * the library joined to one of the two as probable, that join.
         */
        String note(Rel r, String self, String other) {
            List<String> in = new ArrayList<>();
            if (r.claims.isEmpty()) in.addAll(r.worked);
            else {
                in.addAll(fewSourcesOf(r.claims));
                String join = joinNote(r.claims, self, other);
                if (!join.isEmpty()) in.add(join);
            }
            return in.isEmpty() ? "" : " (" + String.join("; ", in) + ")";
        }

        /** "the library joined "Kit" and Kit Hale as one person: probable, …" when every claim rests on such a join of one of its two people; "" otherwise. */
        String joinNote(List<String> claims, String self, String other) {
            FamilyLinks.Link first = null;
            for (String c : claims) {
                FamilyLinks.Link found = null;
                Finding f = byId.get(c);
                for (boolean subject : new boolean[]{true, false}) {
                    FamilyLinks.Link l = mentionLinks.get(Graph.Links.side(c, subject));
                    if (l == null || l.grade() != FamilyLinks.Grade.probable || f == null) continue;
                    String who = g.nodeOf(f, subject);
                    if (who.equals(self) || who.equals(other)) { found = l; break; }
                }
                if (found == null) return "";
                if (first == null) first = found;
            }
            if (first == null) return "";
            Finding f = byId.get(first.claim());
            String who = f == null ? first.person() : g.nodeOf(f, first.side().equals("subject"));
            return "the library joined \"" + first.written() + "\" and " + heading(who) + " as one person: probable, " + reason(first);
        }

        // ── where it comes from ──

        List<String> sourcesOf(Collection<String> claimIds) {
            List<String> out = new ArrayList<>();
            for (String c : claimIds) {
                Finding f = byId.get(c);
                if (f == null) f = idx.finding(c);
                if (f == null) continue;
                for (Finding.Source s : f.sources()) { String l = source(s); if (!l.isBlank() && !out.contains(l)) out.add(l); }
            }
            out.sort(Comparator.comparingInt(FamilySummary::trust));
            return out;
        }

        /** The same, at most {@link #MOST} of them and how many more: what a relative in a row carries. */
        List<String> fewSourcesOf(Collection<String> claimIds) {
            List<String> all = sourcesOf(claimIds);
            if (all.size() <= MOST) return all;
            List<String> out = new ArrayList<>(all.subList(0, MOST));
            out.add((all.size() - MOST) + " more");
            return out;
        }

        /** A source as a person recognises it: "your notes", "your link notes", "your answer", "Geni", a book's title, "ja.wikipedia: <page>", or a site's name. */
        String source(Finding.Source s) {
            String loc = s.locator() == null ? "" : s.locator().strip();
            if (loc.startsWith(FamilyNameQuestions.SOURCE) || loc.startsWith("told://who-is-who/")) return ANSWER;
            if (loc.startsWith("told://link-note/")) return LINK_NOTES;
            if (loc.startsWith("told://")) return TOLD;
            String file = loc.startsWith("file:") ? loc.substring(loc.lastIndexOf('/') + 1) : "";
            boolean told = file.matches("told-\\d.*");   // what somebody typed with genealogy tell, kept as a file
            if (close.owners(s)) return told ? TOLD : NOTES;
            if (told) { String by = s.edition() == null ? "" : s.edition().strip().replaceFirst("(?i)^as told by\\s+", ""); return by.isBlank() ? "what was told to the library" : "what " + by + " told the library"; }
            if (loc.startsWith("file:")) {
                String notes = notesOf(loc, s);
                if (!notes.isEmpty()) return notes;
                return file.matches("(?i).+\\.(txt|md|ged|csv|tsv|json)") ? file : title(file);
            }
            return site(loc);
        }

        private final Map<String, String> notesLabels = new HashMap<>();
        private FamilyFolder.OwnerNotes own;

        /**
         * A notes file the library read, by whose notes they are: "your notes" for the owner's own ({@link FamilyFolder#ownerNotes}, the one
         * rule the link pass reads the owner's words by), "<writer>'s notes" for a notes file another writer is named for, by the folder's
         * notes, the read (--by) or the account's teller. "" for any other file.
         */
        String notesOf(String loc, Finding.Source s) {
            return notesLabels.computeIfAbsent(loc, k -> {
                if (own == null) own = FamilyFolder.ownerNotes(store);
                if (own.is(loc)) return NOTES;
                Path file = Path.of(FamilyFolder.OwnerNotes.pathOf(loc));
                FamilyReads.Row row = own.rowOf(loc);
                if (row == null || !FamilyFolder.notes(file)) return "";
                Path dir = row.folder().isBlank() ? file.getParent() : Path.of(row.folder());
                String writer = FamilyFolder.writersIn(dir).getOrDefault(String.valueOf(file.getFileName()), "");
                if (writer.isBlank()) writer = row.by();
                String told = s.edition() == null ? "" : s.edition().strip().replaceFirst("(?i)^as told by\\s+", "");
                if (writer.isBlank() && !told.isBlank() && !told.toLowerCase(Locale.ROOT).startsWith("the writer of ") && !told.equalsIgnoreCase(FamilyClose.OWNER)) writer = told;
                return writer.isBlank() ? "" : writer.strip().replaceFirst("(?i)^my\\s+", "your ") + "'s notes";
            });
        }

        // ── people who are easy to mix up ──

        /**
         * The people who are easy to mix up, among those listed and the rest of the family: namesakes, pairs the evidence holds as possibly one
         * person (names that differ by one character among them), a written name the sources use for several people, a mention the evidence
         * leaves to several people, a family name alone that nothing identifies yet, and a given name that two of the family carry.
         */
        List<Line> mixUps(Set<String> listed, Set<String> family) {
            List<Line> out = new ArrayList<>();
            Set<String> pairs = new HashSet<>();
            namesakes(out, pairs, listed, family);
            oneCharacter(out, pairs, listed);
            if (links != null) {
                possible(out, pairs, listed);
                usedForSeveral(out, listed);
                leftOpen(out, listed);
            }
            familyNameAlone(out, listed);
            givenNames(out, listed, family);
            return out;
        }

        /** Two people of the family who carry one whole name, with what tells them apart. */
        void namesakes(List<Line> out, Set<String> pairs, Set<String> listed, Set<String> family) {
            List<String> ps = new ArrayList<>(family);
            for (int i = 0; i < ps.size(); i++) for (int j = i + 1; j < ps.size(); j++) {
                String a = ps.get(i), b = ps.get(j);
                if (!listed.contains(a) && !listed.contains(b)) continue;
                String shared = sharedName(a, b);
                if (shared == null || !pairs.add(Graph.pair(a, b))) continue;
                Words w = new Words().w("Two people are called " + shared + ": ");
                apart(w, a, b);
                out.add(w.line());
            }
        }

        /**
         * Names in characters that differ by one character, for somebody listed: two relatives (a father and a son) are easy to confuse, and
         * the line says how they are related; two people nothing relates may be one person, and the library keeps them as two. A pair whose
         * recorded sexes differ is left out: nobody mixes them up.
         */
        /** Each person's names in characters of three or more, as keys ({@link FamilyForms#hanKey}), worked out once. */
        Map<String, List<String>> hanKeys() {
            if (hanKeys != null) return hanKeys;
            Map<String, List<String>> keys = new HashMap<>();
            for (Graph.Node n : g.nodes()) {
                if (!isPerson(n.id())) continue;
                List<String> ks = new ArrayList<>();
                for (FamilyNameHistory.Name nm : idx.names(n.id())) for (String t : nm.texts()) {
                    String k = FamilyForms.hanKey(bare(t));
                    if (FamilyForms.script(bare(t)).equals("han") && k.length() >= 3 && !ks.contains(k)) ks.add(k);
                }
                if (!ks.isEmpty()) keys.put(n.id(), ks);
            }
            hanKeys = keys;
            return keys;
        }

        void oneCharacter(List<Line> out, Set<String> pairs, Set<String> listed) {
            Map<String, List<String>> keys = hanKeys();
            for (String a : listed) {
                if (!keys.containsKey(a)) continue;
                for (Map.Entry<String, List<String>> e : keys.entrySet()) {
                    String b = e.getKey();
                    if (b.equals(a) || !oneApart(keys.get(a), e.getValue())) continue;
                    String sa = FamilyClose.sexOf(sexes, a), sb = FamilyClose.sexOf(sexes, b);
                    if (!sa.isEmpty() && !sb.isEmpty() && !sa.equals(sb) || !pairs.add(Graph.pair(a, b))) continue;
                    String related = related(a, b);
                    // two generations of your family by the relations, or births a lifetime apart: two people
                    FamilyClose.Kin ka = kin(a), kb = kin(b);
                    boolean generations = ka != null && kb != null && ka.up() - ka.down() != kb.up() - kb.down();
                    FamilyDate ba = idx.born(a), bb = idx.born(b);
                    boolean yearsApart = ba != null && bb != null && FamilyDate.apart(ba, bb, 3);
                    if (related.isEmpty() && !generations && yearsApart) { pairs.remove(Graph.pair(a, b)); continue; }   // nothing relates them and the years tell them apart
                    Words w = new Words();
                    person(w, a).w(about(a)).w(" and ");
                    person(w, b).w(about(b));
                    if (!related.isEmpty()) person(person(w.w(" are two people whose names differ by one character: "), b).w(" is "), a).w("'s " + related + ".");
                    else if (generations) w.w(" are two people of two generations whose names differ by one character.");
                    else w.w(" may be one person: the names differ by one character, and the library keeps them as two people until your family says otherwise.");
                    out.add(w.line());
                }
            }
        }

        /** Whether two lists of names in characters hold two of one length that differ in one character and no two that are the same. */
        static boolean oneApart(List<String> x, List<String> y) {
            boolean one = false;
            for (String a : x) for (String b : y) {
                if (a.equals(b)) return false;
                if (a.length() != b.length()) continue;
                int diff = 0;
                for (int i = 0; i < a.length(); i++) if (a.charAt(i) != b.charAt(i)) diff++;
                if (diff == 1) one = true;
            }
            return one;
        }

        /**
         * What {@code b} is to {@code a} when the claims make them two people: a claim between them (a husband or wife, a brother or sister), or
         * one the other's parent or grandparent. Two children of one parent may be one person written two ways, so a shared parent says nothing.
         */
        String related(String a, String b) {
            String s = FamilyClose.sexOf(sexes, b);
            for (Graph.Edge e : touching.getOrDefault(a, List.of())) {
                if (!(e.from().equals(b) || e.to().equals(b))) continue;
                if (e.predicate().equals("married-to")) return s.equals("male") ? "husband" : s.equals("female") ? "wife" : "husband or wife";
                if (e.predicate().equals("sibling-of")) return s.equals("male") ? "brother" : s.equals("female") ? "sister" : "brother or sister";
            }
            FamilyKin.Relation r = FamilyKin.of(g, parents, sexes, b, a);
            return r != null && (r.upA() == 0 || r.upB() == 0) && r.upA() + r.upB() <= 3 ? r.words() : "";
        }

        /** Pairs the evidence holds as possibly one person, and joins nothing; names that differ by one character are said above ({@link #oneCharacter}). */
        void possible(List<Line> out, Set<String> pairs, Set<String> listed) {
            for (FamilyLinks.Link l : links.links()) {
                if (l.joins() || l.rule().equals("L8")) continue;
                String a = resolve(l.node()), b = resolve(l.person());
                if (a.equals(b) || !isPerson(a) || !isPerson(b) || !(listed.contains(a) || listed.contains(b)) || !pairs.add(Graph.pair(a, b))) continue;
                Words w = new Words();
                person(w, a).w(about(a)).w(" and ");
                person(w, b).w(about(b)).w(" may be one person: " + (l.rule().equals("L6") ? "the reading on file matches, and no other fact agrees yet" : "the names are alike, and no other fact agrees yet") + ".");
                out.add(w.line());
            }
        }

        /** One written name the sources use for several people, each where the library gave it to them. */
        void usedForSeveral(List<Line> out, Set<String> listed) {
            Map<String, Map<String, List<String>>> byWritten = new LinkedHashMap<>();   // the name as written → person → the claims
            Map<String, String> shown = new HashMap<>();
            for (FamilyLinks.Link l : mentionLinks.values()) {
                Finding f = byId.get(l.claim());
                if (f == null) continue;
                String who = g.nodeOf(f, l.side().equals("subject"));
                if (!isPerson(who)) continue;
                String key = Vocabulary.norm(l.written());
                shown.putIfAbsent(key, l.written());
                byWritten.computeIfAbsent(key, k -> new LinkedHashMap<>()).computeIfAbsent(who, k -> new ArrayList<>()).add(f.id());
            }
            for (Map.Entry<String, Map<String, List<String>>> e : byWritten.entrySet()) {
                Map<String, List<String>> who = e.getValue();
                if (who.size() < 2 || who.keySet().stream().noneMatch(listed::contains)) continue;
                Words w = new Words().w("The name \"" + shown.get(e.getKey()) + "\" is used for " + (who.size() == 2 ? "two" : String.valueOf(who.size())) + " people: ");
                List<String> ids = new ArrayList<>(who.keySet());
                ids.sort(Comparator.comparing(this::heading));
                for (int i = 0; i < ids.size(); i++) {
                    if (i > 0) w.w(i == ids.size() - 1 ? " and " : ", ");
                    person(w, ids.get(i)).w(" (in " + String.join("; ", sourcesOf(who.get(ids.get(i)))) + ")");
                }
                out.add(w.w(".").line());
            }
        }

        /** A mention the evidence leaves to several people, with how many places write it so. */
        void leftOpen(List<Line> out, Set<String> listed) {
            Map<String, Set<String>> could = new LinkedHashMap<>();
            Map<String, Integer> times = new HashMap<>();
            for (FamilyLinks.Open o : links.open()) {
                Finding f = byId.get(o.claim());
                String where = f == null ? "" : String.join("; ", sourcesOf(List.of(f.id())));
                String key = o.written() + "\u0000" + where;
                Set<String> c = could.computeIfAbsent(key, k -> new LinkedHashSet<>());
                for (String x : o.candidates()) { String r = resolve(x); if (isPerson(r)) c.add(r); }
                times.merge(key, 1, Integer::sum);
            }
            for (Map.Entry<String, Set<String>> e : could.entrySet()) {
                if (e.getValue().size() < 2 || e.getValue().stream().noneMatch(listed::contains)) continue;
                String[] k = e.getKey().split("\u0000", 2);
                int n = times.get(e.getKey());
                Words w = new Words().w("\"" + k[0] + "\"" + (k[1].isEmpty() ? "" : " in " + k[1]) + (n == 1 ? "" : ", in " + n + " places,") + " could be ");
                List<String> ids = new ArrayList<>(e.getValue());
                for (int i = 0; i < ids.size(); i++) { if (i > 0) w.w(i == ids.size() - 1 ? " or " : ", "); person(w, ids.get(i)); }
                out.add(w.w("; the library could not tell which.").line());
            }
        }

        /**
         * A family name alone, with a title or without ("Viscount Morita"), that the sources write for somebody of the family whom nothing
         * identifies yet, with the relatives of that family name the same sources name, any of whom it may be.
         */
        void familyNameAlone(List<Line> out, Set<String> listed) {
            for (Graph.Node n : g.nodes()) {
                if (!"person".equals(n.kind())) continue;
                String fam = FamilyNameQuestions.familyNameAlone(g, n);
                if (fam == null) continue;
                List<String> facts = new ArrayList<>();
                Set<String> where = new HashSet<>();
                for (Graph.Edge e : touching.getOrDefault(n.id(), List.of())) {
                    Finding f = byId.get(e.findingId());
                    if (f == null || facts.contains(f.id())) continue;
                    facts.add(f.id());
                    for (Finding.Source src : f.sources()) if (src.locator() != null) where.add(src.locator());
                }
                List<String> may = new ArrayList<>();
                for (String x : listed) if (!x.equals(n.id()) && idx.names(x).stream().anyMatch(nm -> nm.hasFamily(fam)) && namedIn(x, where)) may.add(x);
                if (facts.isEmpty() || may.size() < 2) continue;
                may.sort(Comparator.comparingInt((String x) -> { FamilyDate b = idx.born(x); return b == null ? Integer.MAX_VALUE : b.year(); }).thenComparing(this::heading));
                Words w = new Words().w("\"" + n.label() + "\" in " + FamilyReads.and(sourcesOf(facts)) + " is somebody of the " + fam + " family whom nothing identifies yet"
                        + " (" + (facts.size() == 1 ? "1 fact" : facts.size() + " facts") + "). Of your family, the same sources name ");
                int most = Math.min(may.size(), 4);
                for (int i = 0; i < most; i++) { if (i > 0) w.w(i == most - 1 && may.size() <= 4 ? " and " : ", "); person(w, may.get(i)).w(about(may.get(i))); }
                out.add(w.w((may.size() > 4 ? " and " + (may.size() - 4) + " more" : "") + ".").line());
            }
        }

        /** Whether a claim about a person rests on one of these sources. */
        boolean namedIn(String id, Set<String> where) {
            for (Graph.Edge e : touching.getOrDefault(id, List.of())) {
                Finding f = byId.get(e.findingId());
                if (f != null && f.sources().stream().anyMatch(src -> where.contains(src.locator()))) return true;
            }
            return false;
        }

        /**
         * A given name alone that the library gave to one person, in a mention or as another name of theirs, which another of the family carries
         * as a given name too.
         */
        void givenNames(List<Line> out, Set<String> listed, Set<String> family) {
            Set<String> said = new HashSet<>();
            // the mentions, gathered by the name as written, whom the library gave it to, and who else carries it: one line with every source
            Map<String, String[]> pairsOf = new LinkedHashMap<>();   // key → {the name, whom it was given to, who else}
            Map<String, List<String>> claimsOf = new LinkedHashMap<>();
            for (FamilyLinks.Link l : mentionLinks.values()) {
                String w0 = FamilyLinks.untitled(l.written()).strip();
                if (w0.isEmpty() || w0.matches(".*[\\s,　・].*")) continue;
                Finding f = byId.get(l.claim());
                if (f == null) continue;
                String who = g.nodeOf(f, l.side().equals("subject"));
                if (!isPerson(who) || !carries(who, w0)) continue;
                for (String other : family) {
                    if (other.equals(who) || !(listed.contains(who) || listed.contains(other)) || !carries(other, w0)) continue;
                    String key = Vocabulary.norm(w0) + "\u0000" + who + "\u0000" + other;
                    pairsOf.putIfAbsent(key, new String[]{w0, who, other});
                    claimsOf.computeIfAbsent(key, k -> new ArrayList<>()).add(f.id());
                }
            }
            for (Map.Entry<String, String[]> e : pairsOf.entrySet()) {
                String[] x = e.getValue();
                if (!said.add(Vocabulary.norm(x[0]) + "\u0000" + Graph.pair(x[1], x[2]))) continue;
                String where = FamilyReads.and(sourcesOf(claimsOf.get(e.getKey())));
                Words w = new Words().w("\"" + x[0] + "\"" + (where.isEmpty() ? "" : " in " + where) + " is ");
                person(w, x[1]).w(", as the library read it; ");
                person(w, x[2]).w(" is called " + x[0] + " too.");
                out.add(w.line());
            }
            for (String who : listed) for (FamilyNameHistory.Name n : idx.names(who)) for (String t : n.texts()) {
                String w0 = bare(t);
                if (w0.isEmpty() || w0.matches(".*[\\s,　・].*") || familyPart(w0) || !carries(who, w0)) continue;
                for (String other : family) {
                    if (other.equals(who) || !carries(other, w0) || !said.add(Vocabulary.norm(w0) + "\u0000" + Graph.pair(who, other))) continue;
                    Words w = new Words().w("\"" + w0 + "\", a name the library files under ");
                    person(w, who).w(about(who)).w(", is the given name of ");
                    person(w, other).w(about(other)).w(" too.");
                    out.add(w.line());
                }
            }
        }

        /** Whether a word is a family name the library knows: a family's name, or the family part of a name claim. */
        boolean familyPart(String w) { return idx.familyParts().stream().anyMatch(f -> FamilyForms.sameForm(f, w)); }

        /** Whether a person carries this given name: as the given part of a name of theirs, or as a word of a whole name in letters that is not its family part. */
        boolean carries(String id, String w) {
            for (FamilyNameHistory.Name n : idx.names(id)) {
                if (!n.given().isBlank() && FamilyForms.sameForm(n.given(), w)) return true;
                for (String t : n.texts()) {
                    if (!FamilyForms.script(t).equals("latin") || !whole(t)) continue;
                    for (String x : bare(t).split("[\\s,]+")) if (FamilyForms.sameForm(x, w) && !(!n.family().isBlank() && FamilyForms.sameForm(n.family(), x)) && !familyPart(x)) return true;
                }
            }
            return false;
        }

        /** A whole name two people both carry (a family and a given part, or a name in characters of three or more), as the first writes it; null when none. */
        String sharedName(String a, String b) {
            for (FamilyNameHistory.Name na : idx.names(a)) for (String ta : na.texts()) {
                if (!whole(ta)) continue;
                for (FamilyNameHistory.Name nb : idx.names(b)) for (String tb : nb.texts()) if (whole(tb) && (FamilyForms.sameForm(ta, tb) || Vocabulary.norm(bare(ta)).equals(Vocabulary.norm(bare(tb))))) return bare(ta);
            }
            return null;
        }

        /** Both people with what tells them apart: their years and who they are to you, and how they are related to each other when the parent claims say. */
        void apart(Words w, String a, String b) {
            person(w, a).w(about(a)).w(" and ");
            person(w, b).w(about(b)).w(".");
            FamilyKin.Relation r = FamilyKin.of(g, parents, sexes, b, a);
            if (r != null && r.upA() + r.upB() <= 3) person(person(w.w(" "), b).w(" is "), a).w("'s " + r.words() + ".");
        }

        /** What tells a person apart, in brackets: their years (those their heading does not already give) and who they are to you; "" when nothing does. */
        String about(String id) {
            List<String> years = new ArrayList<>();
            FamilyDate b = idx.born(id), d = idx.died(id);
            String h = heading(id);
            if (b != null && !h.contains("born " + b.phrase())) years.add("born " + b.phrase());
            if (d != null && !h.contains("died " + d.phrase())) years.add("died " + d.phrase());
            List<String> bits = new ArrayList<>();
            if (!years.isEmpty()) bits.add(String.join(", ", years));
            String rel = ownerLike(id) ? "" : said(id);   // a relative known only by your words is named by them already
            if (!rel.isEmpty()) bits.add(rel);
            return bits.isEmpty() ? "" : " (" + String.join("; ", bits) + ")";
        }

        // ── the owner's notes against the sources ──

        List<Line> disagreements() throws IOException {
            List<Line> out = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (FamilyNameQuestions.Disagreement d : FamilyNameQuestions.disagreements(store, g)) {
                // one disagreement about one person between the same note and the same source is said once, however many claims raise it
                List<String> fs = d.question().findings();
                String who = d.question().people().isEmpty() ? "" : d.question().people().get(d.question().people().size() - 1);
                String noteSource = fs.isEmpty() ? "" : String.join(";", sourcesOf(List.of(fs.get(0))));
                String theirSource = fs.size() < 2 ? "" : String.join(";", sourcesOf(fs.subList(1, fs.size())));
                if (!seen.add(who + "\u0000" + noteSource + "\u0000" + theirSource)) continue;
                String text = QUESTION_END.matcher(d.question().text().strip()).replaceFirst("").strip();
                String[] a = d.answer();
                String answer;
                if (a == null) answer = "You have not answered this yet: researchzosho genealogy who asks you which is right.";
                else if (a[0].equals("later")) answer = "You put this off. researchzosho genealogy who --reopen " + d.question().code() + " asks it again.";
                else {
                    String what = (a.length > 3 ? a[3] : "").replaceFirst("^(?:F-\\d+[^,:\\s]*(?:,\\s*)?)+:\\s*", "").strip();
                    answer = what.isEmpty() ? "You answered it." : "Your answer: " + what.replaceFirst("[.。]$", "") + ".";
                }
                out.add(new Words().w(text + " " + answer).line());
            }
            return out;
        }

        // ── not settled ──

        /** Whether a relation is the owner or an ancestor on the owner's own line: parents only, no marriage. */
        static boolean line(FamilyClose.Kin k) { return k != null && !k.spouse() && k.down() == 0 && k.path().stream().allMatch(s -> s.way() == 'u'); }

        /**
         * The owner, the parents and the grandparents whose parents no source names, or only one of them. A parent is named by a parent claim, or
         * by the owner's own words that place somebody one generation further up the same line ("my father's father"); an entry that only
         * describes the person ("the owner of this library's mother's father") names nobody.
         */
        List<Line> unnamedParents(Set<String> listed) {
            List<Line> out = new ArrayList<>();
            List<String> ancestors = new ArrayList<>();
            for (Graph.Node n : g.nodes()) if (isPerson(n.id()) && line(kin(n.id())) && !description(n.label()) && !unnamed(n.id())) ancestors.add(n.id());
            List<String> sorted = new ArrayList<>(listed);
            sorted.sort(nearest());
            for (String id : sorted) {
                FamilyClose.Kin k = kin(id);
                if (!line(k) || k.up() > 2) continue;
                List<String> named = new ArrayList<>();
                for (FamilyKin.Link l : parents.getOrDefault(id, List.of())) if (!named.contains(l.other()) && isPerson(l.other()) && !description(labelOf(l.other())) && !unnamed(l.other())) named.add(l.other());
                for (String a : ancestors) if (!named.contains(a) && oneUp(k, kin(a))) named.add(a);
                String whose = k.up() == 0 ? "your" : k.said() + "'s";
                if (named.isEmpty()) out.add(new Words().w("No source names " + whose + " parents.").line());
                else if (named.size() == 1) {
                    String s = FamilyKin.sexOf(sexes, named.get(0));
                    if (s.isEmpty()) s = kin(named.get(0)) == null ? "" : kin(named.get(0)).sex();
                    out.add(new Words().w("No source names " + whose + " " + (s.equals("male") ? "mother" : s.equals("female") ? "father" : "other parent") + ".").line());
                }
            }
            return out;
        }

        /** Whether {@code up} is one generation above {@code k} on the same line: the same steps, each step's sex the same where both say it, and one more parent. */
        static boolean oneUp(FamilyClose.Kin k, FamilyClose.Kin up) {
            if (!line(up) || up.path().size() != k.path().size() + 1) return false;
            for (int i = 0; i < k.path().size(); i++) {
                String a = k.path().get(i).sex(), b = up.path().get(i).sex();
                if (!a.isEmpty() && !b.isEmpty() && !a.equals(b)) return false;
            }
            return true;
        }

        /** What the family may know and no source says: how the names of close family came, and when they were born. */
        List<Line> notKnown(List<List<String>> placed) {
            List<Line> out = new ArrayList<>();
            List<String> noBirth = new ArrayList<>();
            for (int gi = 0; gi < FURTHER && gi < placed.size(); gi++) for (String id : placed.get(gi)) {
                if (ownerLike(id) && said(id).equals("you") && idx.names(id).stream().allMatch(FamilyNameHistory.Name::implicit)) continue;
                List<String> unknown = new ArrayList<>();
                for (FamilyNameHistory.Name n : idx.names(id)) if (!n.implicit() && !n.workedOut() && FamilyNameHistory.kind(n.kind()).equals("unknown") && !unknown.contains(n.written())) unknown.add(n.written());
                if (!unknown.isEmpty()) out.add(person(new Words().w("No source says how "), id).w(" came by the name" + (unknown.size() == 1 ? " " : "s ") + FamilyReads.and(unknown) + ".").line());
                if (idx.born(id) == null && !description(labelOf(id))) noBirth.add(id);
            }
            if (!noBirth.isEmpty()) {
                Words w = new Words().w("No source gives a birth date for ");
                for (int i = 0; i < noBirth.size(); i++) { if (i > 0) w.w(i == noBirth.size() - 1 ? " and " : ", "); person(w, noBirth.get(i)); }
                out.add(w.w(".").line());
            }
            return out;
        }
    }

    private static final Pattern WRITER = Pattern.compile("(?i)the writer of (.+?\\.[a-z0-9]{2,5})(['’]s .*)?");

    /** The question a disagreement ends with, which the summary leaves off: it says what the sources say, and the questions stay in genealogy who. */
    private static final Pattern QUESTION_END = Pattern.compile("\\s*(?:Which is right\\?|Is (?:one of them|.+?) the same person as .+?, or is your note or the source wrong\\?)\\s*$");

    /** A person's short reason for a join, in a few words, by the rule that made it. */
    static String reason(FamilyLinks.Link l) {
        String why = l.why() == null ? "" : l.why();
        return switch (l.rule()) {
            case "L1" -> l.mention() ? "one claim gives both names" : "the same name written another way, and a fact agrees";
            case "L3" -> "the relations on file lead to this person alone";
            case "L5" -> why.contains("the model chose") ? "the model chose this person, quoting the source" : why.contains("related so") ? "the one person related so"
                    : why.contains("carried the name") ? "the same source says this person carried the name" : "the same source names this person in full";
            case "L6" -> "the reading on file matches, and a fact agrees";
            case "L7" -> "the characters can be read so, and the relations agree";
            case "L9" -> "a title or a form of address for this person";
            case "L11" -> "one given name under two family names, and two facts agree";
            default -> "the evidence points to this person";
        };
    }

    /** Most trusted first: the owner's own word, then Geni, then the rest in the order they came. */
    static int trust(String label) {
        return switch (label) {
            case NOTES -> 0;
            case TOLD -> 1;
            case LINK_NOTES -> 2;
            case ANSWER -> 3;
            case GENI -> 4;
            default -> 5;
        };
    }

    /** " (a; b)" after a sentence, or "". */
    /** At most this many sources beside one relative in a row, then how many more. */
    static final int MOST = 3;

    static String after(List<String> sources) { return sources.isEmpty() ? "" : " (" + String.join("; ", sources) + ")"; }

    /** A book's title from its file's name, shortened: no extension, no subtitle, no author in brackets, at most about forty letters. */
    static String title(String fileName) {
        String t = fileName.replaceFirst("\\.[A-Za-z0-9]{1,5}$", "");
        // a name written without spaces keeps its words apart with underscores, hyphens or capitals: hale-memoir_a-life-in-leeds, MoritaFamilyTalk
        if (!t.contains(" ")) t = t.replaceAll("[_-]+", " ").replaceAll("(?<=\\p{Ll})(?=\\p{Lu})", " ");
        t = t.replace('_', ' ').replaceAll("\\s+", " ").strip();
        String cut = t.replaceFirst("\\s*(?:[:：]|\\s[-–—]\\s|[(（\\[]).*$", "").strip();
        if (!cut.isEmpty()) t = cut;
        if (t.length() > 40) { int sp = t.lastIndexOf(' ', 40); t = (sp > 20 ? t.substring(0, sp) : t.substring(0, 40)) + "…"; }
        return t;
    }

    /** A web page as a person recognises it: "Geni", "ja.wikipedia: <page>", or the site's name. */
    static String site(String locator) {
        try {
            URI u = URI.create(locator);
            String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
            if (host.equals("geni.com") || host.endsWith(".geni.com")) return GENI;
            Matcher w = Pattern.compile("^([a-z-]+)\\.(?:m\\.)?wikipedia\\.org$").matcher(host);
            if (w.find()) {
                String path = u.getRawPath() == null ? "" : u.getRawPath().replaceAll("/+$", "");
                String page = URLDecoder.decode(path.substring(path.lastIndexOf('/') + 1), StandardCharsets.UTF_8).replace('_', ' ').strip();
                return w.group(1) + ".wikipedia" + (page.isEmpty() || page.equals("wiki") ? "" : ": " + page);
            }
            if (!host.isEmpty()) return host.replaceFirst("^www\\.", "");
        } catch (IllegalArgumentException e) {
            // not an address: said as it is
        }
        return Acquisitions.compress(locator, 40);
    }

    static String cap(String s) { return s == null || s.isEmpty() ? "" : s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1); }

    private static String bare(String t) { return FamilyLinks.untitled(FamilyNameQuestions.bareName(t)).strip(); }

    /** A whole name, not a part: two words or more in letters, three characters or more, or a reading in two words. */
    static boolean whole(String t) {
        String x = bare(t);
        if (x.contains("?") || x.contains("？")) return false;   // a tree site's "? Ito": a name nobody knows is nobody's namesake
        return switch (FamilyForms.script(x)) {
            case "latin" -> x.split("[\\s,]+").length >= 2;
            case "han" -> FamilyForms.hanKey(x).length() >= 3;
            case "kana" -> x.split("[\\s　・]+").length >= 2;
            default -> false;
        };
    }

    // ── dates ──

    private static final Pattern ISO = Pattern.compile("(?<!\\d)(1[5-9]\\d\\d|20\\d\\d)[-/.](\\d{1,2})(?:[-/.](\\d{1,2}))?(?!\\d)");
    private static final Pattern JA_MONTH = Pattern.compile("(\\d{1,2})月(?:(\\d{1,2})日)?");
    private static final Pattern MONTH = Pattern.compile("(?i)(?<![\\p{L}])(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\\.?(?![\\p{L}])");
    private static final Pattern DAY = Pattern.compile("(?<!\\d)(\\d{1,2})(?!\\d)");
    private static final List<String> MONTHS = List.of("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec");

    /** The month and day a date's words give, {month, day}, 0 for either they do not give: "27 Feb 1944", "1944-02-27", "1944年2月27日". */
    static int[] monthDay(String text) {
        String t = Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFKC);
        Matcher m = ISO.matcher(t);
        if (m.find()) return valid(Integer.parseInt(m.group(2)), m.group(3) == null ? 0 : Integer.parseInt(m.group(3)));
        m = JA_MONTH.matcher(t);
        if (m.find()) return valid(Integer.parseInt(m.group(1)), m.group(2) == null ? 0 : Integer.parseInt(m.group(2)));
        m = MONTH.matcher(t);
        if (m.find()) {
            int month = MONTHS.indexOf(m.group(1).toLowerCase(Locale.ROOT)) + 1;
            Matcher d = DAY.matcher(t);
            return valid(month, d.find() ? Integer.parseInt(d.group(1)) : 0);
        }
        return new int[]{0, 0};
    }

    private static int[] valid(int month, int day) { return month < 1 || month > 12 ? new int[]{0, 0} : new int[]{month, day < 1 || day > 31 ? 0 : day}; }

    /** Whether two dates may be one: the years overlap, and where both give a month (and a day) they are the same. */
    static boolean agree(FamilyDate a, int[] ma, FamilyDate b, int[] mb) {
        if (FamilyDate.apart(a, b, 0)) return false;
        if (a.exact() && b.exact()) {
            if (ma[0] > 0 && mb[0] > 0 && ma[0] != mb[0]) return false;
            if (ma[0] == mb[0] && ma[1] > 0 && mb[1] > 0 && ma[1] != mb[1]) return false;
        }
        return true;
    }

    /** Values that may be one, together: dates that agree ({@link #agree}), places written alike or one inside the other. */
    private static List<List<Value>> clusters(List<Value> values, boolean dates) {
        List<List<Value>> out = new ArrayList<>();
        for (Value v : values) {
            List<Value> into = null;
            for (List<Value> c : out) if (c.stream().allMatch(x -> dates ? agree(x.date(), x.md(), v.date(), v.md()) : samePlace(x.text(), v.text()))) { into = c; break; }
            if (into == null) out.add(into = new ArrayList<>());
            into.add(v);
        }
        return out;
    }

    private static boolean samePlace(String a, String b) {
        String x = Vocabulary.norm(KanjiForms.modern(a)), y = Vocabulary.norm(KanjiForms.modern(b));
        return x.equals(y) || x.contains(y) || y.contains(x);
    }

    /** Of values that agree, the one that says most: a day, then a month, then an exact year, then the longest words. */
    private static Value best(List<Value> c) {
        Value best = c.get(0);
        for (Value v : c) if (said(v) > said(best)) best = v;
        return best;
    }

    /** How much of a date a value gives: 3 a day, 2 a month, 1 an exact year, 0 less. */
    private static int level(Value v) { return v.date() == null ? 0 : v.md()[1] > 0 ? 3 : v.md()[0] > 0 ? 2 : v.date().exact() ? 1 : 0; }

    private static int said(Value v) {
        if (v.date() == null) return v.text().length();
        return (v.md()[1] > 0 ? 4000 : 0) + (v.md()[0] > 0 ? 2000 : 0) + (v.date().exact() ? 1000 : 0) + Math.min(999, v.text().length());
    }

    /** A date as its source wrote it, with the year beside words that do not show it ("明治40年 (1907)"). */
    static String dateShown(Value v) {
        String t = v.text().strip(), y = String.valueOf(v.date().year());
        // "August 1, 1886 (1886)": a year in brackets after words that give it already is said once
        Matcher m = Pattern.compile("\\s*[(（]\\s*" + y + "\\s*[)）]\\s*$").matcher(t);
        if (m.find() && t.substring(0, m.start()).contains(y)) t = t.substring(0, m.start()).strip();
        return t.contains(y) ? t : t + " (" + v.date().phrase() + ")";
    }

    // ── written out ─────────────────────────────────────────────────────────────────────────────────────────────────────

    /** What the summary says before the people: what it is, how each line says where it comes from, and what comes after the people. */
    static String intro(Summary s) {
        String who = !s.ownerKnown()
                ? "The library does not know yet which person in it is you, so it cannot say how each person is related to you. It learns that from your own words: a note beside a link, such as \"my father's father\", or your own notes once they say who you are. Until then, these are the people your family's own files name."
                : s.all() ? "What the sources in your library say about everyone the library places in your family, each person under how they are related to you, nearest first."
                : "What the sources in your library say about your close family: you, your parents, brothers and sisters, grandparents and great-grandparents, and the brothers, sisters, children, husbands and wives of any of those; and about the other people your own notes name. Each person is under how they are related to you, nearest first.";
        return who + " Each line ends with where it comes from, in brackets: \"your notes\" and \"your link notes\" are your own words, \"your answer\" is what you answered the library, a book is named by its title and a web page by its site. \"Worked out\" marks what the library put together from facts that no single source gives. After the people come those who are easy to mix up, where your notes and the sources disagree, and what is not settled.";
    }

    /** The summary as text: markdown headings, one line per fact. What the terminal prints and --out writes. */
    public static String text(Summary s) {
        StringBuilder b = new StringBuilder("# Your family, as the sources say it\n\n").append(intro(s)).append("\n");
        if (s.groups().isEmpty()) b.append("\nThe library places nobody in your family yet.\n");
        for (Group gr : s.groups()) {
            b.append("\n## ").append(gr.heading()).append("\n");
            for (Person p : gr.people()) {
                b.append("\n**").append(p.heading()).append("**").append(p.relation().isEmpty() ? "" : " — " + p.relation()).append("\n");
                if (p.lines().isEmpty()) b.append("- The library holds no fact about this person beyond the relation.\n");
                for (Line l : p.lines()) b.append("- ").append(l.plain()).append("\n");
            }
        }
        section(b, MIX_UP, s.mixUps(), "The library finds nobody in your family who is easy to mix up with somebody else.");
        section(b, DISAGREE, s.disagree(), "Nothing in your notes disagrees with a source.");
        section(b, NOT_SETTLED, s.notSettled(), "Nothing is left open that the library can see.");
        return b.toString();
    }

    private static void section(StringBuilder b, String heading, List<Line> lines, String none) {
        b.append("\n## ").append(heading).append("\n\n");
        if (lines.isEmpty()) b.append(none).append("\n");
        for (Line l : lines) b.append("- ").append(l.plain()).append("\n");
    }

    /** The summary as a page: the same words, each person a link to their page. */
    public static String html(Summary s) {
        StringBuilder b = new StringBuilder("<p class=\"k\">").append(FamilyTree.esc(intro(s))).append("</p>");
        b.append("<p>").append(s.all() ? "<a href=\"/summary\">Close family only</a>" : "<a href=\"/summary?all=1\">Everyone the library places in your family</a>").append(" · <a href=\"/tree\">The family tree</a></p>");
        if (s.groups().isEmpty()) b.append("<p>The library places nobody in your family yet.</p>");
        for (Group gr : s.groups()) {
            b.append("<h2>").append(FamilyTree.esc(gr.heading())).append("</h2>");
            for (Person p : gr.people()) {
                b.append("<h3><a href=\"/person?name=").append(FamilyNamePages.enc(p.label())).append("\">").append(FamilyTree.esc(p.heading())).append("</a>")
                 .append(p.relation().isEmpty() ? "" : " <span class=\"k\">— " + FamilyTree.esc(p.relation()) + "</span>").append("</h3><ul>");
                if (p.lines().isEmpty()) b.append("<li>The library holds no fact about this person beyond the relation.</li>");
                for (Line l : p.lines()) b.append("<li>").append(html(l)).append("</li>");
                b.append("</ul>");
            }
        }
        htmlSection(b, MIX_UP, s.mixUps(), "The library finds nobody in your family who is easy to mix up with somebody else.");
        htmlSection(b, DISAGREE, s.disagree(), "Nothing in your notes disagrees with a source.");
        htmlSection(b, NOT_SETTLED, s.notSettled(), "Nothing is left open that the library can see.");
        return b.toString();
    }

    private static void htmlSection(StringBuilder b, String heading, List<Line> lines, String none) {
        b.append("<h2>").append(FamilyTree.esc(heading)).append("</h2>");
        if (lines.isEmpty()) { b.append("<p>").append(FamilyTree.esc(none)).append("</p>"); return; }
        b.append("<ul>");
        for (Line l : lines) b.append("<li>").append(html(l)).append("</li>");
        b.append("</ul>");
    }

    static String html(Line l) {
        StringBuilder b = new StringBuilder();
        for (Part p : l.parts()) {
            if (p.label() == null) b.append(FamilyTree.esc(p.text()));
            else b.append("<a href=\"/person?name=").append(FamilyNamePages.enc(p.label())).append("\">").append(FamilyTree.esc(p.text())).append("</a>");
        }
        return b.toString();
    }

    /** What the command says when it is given words it does not take. */
    static final String USAGE = "usage: researchzosho genealogy summary [--all] [--out <file.md>]\n"
            + "  what the sources settle about your close family, each person under how they are related to you and each line with where it comes from;"
            + " then the people who are easy to mix up, where your notes and the sources disagree, and what is not settled. --all adds everyone the library places"
            + " in your family, and --out writes the summary to a file instead of the screen.";

    /** {@code genealogy summary [--all] [--out <file.md>]}. Returns the exit code. */
    public static int cli(LibraryStore store, List<String> args, PrintStream out) throws IOException {
        boolean all = false;
        String file = null;
        for (int i = 0; i < args.size(); i++) {
            String a = args.get(i);
            if (a.equals("--all")) all = true;
            else if (a.equals("--out") && i + 1 < args.size()) file = args.get(++i);
            else { System.err.println(USAGE); return 2; }
        }
        if (!Files.isDirectory(store.root().resolve("family"))) {
            out.println("Your library holds no family history yet, so there is nothing to sum up. The command researchzosho genealogy read <file> reads a family's own account.");
            return 1;
        }
        Summary s = of(store, all);
        String text = text(s);
        if (file == null) { out.print(text); return 0; }
        Path p = Path.of(file).toAbsolutePath().normalize();
        if (p.getParent() != null) Files.createDirectories(p.getParent());
        Files.writeString(p, text, StandardCharsets.UTF_8);
        int n = s.people();
        out.println("The summary of your family is saved in " + p + ". It is about " + n + (n == 1 ? " person" : " people") + ", with " + s.mixUps().size() + " who are easy to mix up, "
                + s.disagree().size() + (s.disagree().size() == 1 ? " place where your notes and a source disagree" : " places where your notes and a source disagree") + ", and "
                + s.notSettled().size() + (s.notSettled().size() == 1 ? " thing that is not settled" : " things that are not settled") + ". The page Summary of the library's web pages shows the same, with each name a link to the person's page.");
        return 0;
    }
}
