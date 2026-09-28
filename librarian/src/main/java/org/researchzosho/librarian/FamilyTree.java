package org.researchzosho.librarian;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import java.io.IOException;
import java.util.function.Function;
/**
 * A family tree drawn from the claims: generations top to bottom, a couple side by side, a child under its parents, an
 * adoption as a dashed line. Every line IS a claim, and its colour says how far the claim has come: checked and
 * accepted, still only somebody's account, or disputed. Drawn on the server as SVG: no script, so it opens from a
 * file as well as from the library's pages.
 */
public final class FamilyTree {

    private FamilyTree() { }

    static final int MAX_PEOPLE = 150, BOX_W = 170, BOX_H = 54, GAP_X = 26, GAP_Y = 70;

    /**
     * {@code mayBeLiving}: nothing in the library places them in the past, so a death they have none of is left blank, not a '?'.
     * {@code shown}: the person's latest name, which the box shows first; {@code bornAs}: the birth name or birth family name the heading
     * gives beside it ("遠藤" for 森田健二 (born 遠藤)), "" when there is none. {@code label} stays the name every link and command takes.
     */
    public record Person(String id, String label, int generation, String born, String died, String work, boolean mayBeLiving, String shown, String bornAs) {
        public Person(String id, String label, int generation, String born, String died, String work, boolean mayBeLiving) { this(id, label, generation, born, died, work, mayBeLiving, label, ""); }

        /** The heading the person is written under in prose: "森田健二 (born 遠藤)". */
        public String heading() { return bornAs.isEmpty() ? shown : shown + " (born " + bornAs + ")"; }
    }
    /** kind: parent, adopted or married. {@code from} is the parent (or a spouse), {@code to} the child (or the other spouse). */
    /** {@code evidence}: what the claim rests on — record, published, clue ({@link Evidence}). */
    public record Link(String from, String to, String kind, String state, String finding, String evidence) {
        public Link(String from, String to, String kind, String state, String finding) { this(from, to, kind, state, finding, ""); }
    }
    public record Tree(Person focus, List<Person> people, List<Link> links) { }

    public static Tree around(LibraryStore store, String focusName, int up, int down) throws IOException { return around(store, focusName, up, down, true); }

    /**
     * {@code withDisputed}: the drawing shows a disputed claim with red dots; the research questions leave it out, since the family said it
     * is wrong, and a relation it alone gives leads nowhere.
     */
    public static Tree around(LibraryStore store, String focusName, int up, int down, boolean withDisputed) throws IOException {
        Graph g = FamilyPeople.view(store);
        Graph.Node start = g.node(g.nodeIdOf(focusName));
        if (start == null) start = g.node(g.nodeIdOf(KanjiForms.modern(focusName)));   // asked for as the register writes it (髙橋), held as a directory does (高橋)
        if (start == null) return new Tree(null, List.of(), List.of());
        Map<String, Finding> findings = new LinkedHashMap<>();
        for (Finding f : store.scanFindings().findings()) findings.put(f.id(), f);
        // the kinship claims, each turned the same way round: parent → child, spouse — spouse
        List<Link> all = new ArrayList<>();
        Map<String, String[]> facts = new LinkedHashMap<>();   // person → [born, died, work]
        Map<String, Evidence> byFact = FamilyPeople.byFact(g, findings);
        for (Graph.Edge e : g.edges()) {
            if (!withDisputed && e.disputed()) continue;
            // a parent or a spouse the record says it does not know is a question to research, not a box in the tree
            Graph.Node fromNode = g.node(e.from()), toNode = g.node(e.to());
            if ((fromNode != null && FamilyQuestions.unknown(fromNode.label())) || (toNode != null && FamilyQuestions.unknown(toNode.label()))) continue;
            Finding ef = findings.get(e.findingId());
            String ev = ef == null ? "" : Evidence.ofFact(ef, byFact, FamilyPeople.factKey(e.from(), e.predicate(), e.to())).name();
            switch (e.predicate()) {
                case "parent-of" -> all.add(new Link(e.from(), e.to(), "parent", stateOf(e), e.findingId(), ev));
                case "child-of" -> all.add(new Link(e.to(), e.from(), "parent", stateOf(e), e.findingId(), ev));
                case "adopted-by" -> all.add(new Link(e.to(), e.from(), "adopted", stateOf(e), e.findingId(), ev));
                // a step-parent and a foster parent are drawn like an adoptive one: dashed, not a parent by birth
                case "step-parent-of" -> all.add(new Link(e.from(), e.to(), "adopted", stateOf(e), e.findingId(), ev));
                case "foster-child-of" -> all.add(new Link(e.to(), e.from(), "adopted", stateOf(e), e.findingId(), ev));
                case "married-to" -> all.add(new Link(e.from(), e.to(), "married", stateOf(e), e.findingId(), ev));
                case "sibling-of" -> all.add(new Link(e.from(), e.to(), "sibling", stateOf(e), e.findingId(), ev));
                case "born-on", "died-on" -> {
                    String[] f = facts.computeIfAbsent(e.from(), k -> new String[]{"", "", ""});
                    Graph.Node when = g.node(e.to());
                    FamilyDate d = when == null ? null : FamilyDate.parse(when.label());
                    int slot = e.predicate().equals("born-on") ? 0 : 1;
                    if (d != null && f[slot].isEmpty()) f[slot] = d.phrase();   // "about 1850" stays about: a question says so, and a search keeps its years wide
                }
                case "born-in", "died-in", "occupation" -> {
                    String[] f = facts.computeIfAbsent(e.from(), k -> new String[]{"", "", ""});
                    int slot = e.predicate().equals("born-in") ? 0 : e.predicate().equals("died-in") ? 1 : 2;
                    if (!f[slot].isEmpty()) break;
                    if (slot == 2) { Graph.Node w = g.node(e.to()); f[2] = w == null ? "" : w.label(); break; }
                    Finding fd = findings.get(e.findingId());
                    FamilyDate y = fd == null ? null : FamilyChecks.claimDate(fd);
                    f[slot] = y == null ? "" : y.phrase();
                }
                default -> { }
            }
        }
        Map<String, Integer> generation = new LinkedHashMap<>();
        generation.put(start.id(), 0);
        ArrayDeque<String> queue = new ArrayDeque<>(List.of(start.id()));
        List<Link> kept = new ArrayList<>();
        while (!queue.isEmpty() && generation.size() < MAX_PEOPLE) {
            String at = queue.poll();
            int gen = generation.get(at);
            for (Link l : all) {
                String other; int otherGen;
                if (l.from().equals(at)) { other = l.to(); otherGen = sameGeneration(l) ? gen : gen + 1; }
                else if (l.to().equals(at)) { other = l.from(); otherGen = sameGeneration(l) ? gen : gen - 1; }
                else continue;
                if (otherGen < -up || otherGen > down) continue;
                Graph.Node n = g.node(other);
                if (n == null) continue;
                if (!generation.containsKey(other)) { generation.put(other, otherGen); queue.add(other); }
            }
        }
        for (Link l : all) if (generation.containsKey(l.from()) && generation.containsKey(l.to()) && !kept.contains(l)) kept.add(l);
        List<Person> people = new ArrayList<>();
        FamilyNameHistory.Index names = FamilyNameHistory.of(g);
        for (var e : generation.entrySet()) {
            Graph.Node n = g.node(e.getKey());
            String[] f = facts.getOrDefault(e.getKey(), new String[]{"", "", ""});
            people.add(new Person(n.id(), n.label(), e.getValue(), f[0], f[1], f[2], n.mayBeLiving(), FamilyNamePages.shown(names, g, n.id()), FamilyNamePages.bornAs(names, g, n.id())));
        }
        Person focus = people.get(0);
        return new Tree(focus, people, kept);
    }

    /** A year as a box has room for: "c. 1850", "bef. 1850", "aft. 1850", "1850–1860". */
    static String brief(String phrase) {
        return phrase.replaceFirst("^about ", "c. ").replaceFirst("^(\\d+) or before$", "bef. $1").replaceFirst("^(\\d+) or after$", "aft. $1").replaceFirst("^between (\\d+) and (\\d+)$", "$1–$2");
    }

    /** A husband and wife, and a brother and sister, stand in one generation; every other link goes from a parent down to a child. */
    static boolean sameGeneration(Link l) { return l.kind().equals("married") || l.kind().equals("sibling"); }

    private static String stateOf(Graph.Edge e) { return e.disputed() ? "disputed" : e.state(); }

    /** Where each person's box goes: a row per generation; a spouse next to their partner; children in the order of their parents. */
    static Map<String, int[]> layout(Tree t) {
        TreeMap<Integer, List<Person>> rows = new TreeMap<>();
        for (Person p : t.people()) rows.computeIfAbsent(p.generation(), k -> new ArrayList<>()).add(p);
        Map<String, Integer> column = new LinkedHashMap<>();
        Map<String, int[]> at = new LinkedHashMap<>();
        int widest = rows.values().stream().mapToInt(List::size).max().orElse(1);
        int row = 0;
        for (var r : rows.entrySet()) {
            List<Person> people = r.getValue();
            // by the parents' place in the row above (their mean column), people with no parent in the tree last, as they were found
            Map<String, Double> key = new LinkedHashMap<>();
            for (Person p : people) {
                double sum = 0; int n = 0;
                for (Link l : t.links()) if (!sameGeneration(l) && l.to().equals(p.id()) && column.containsKey(l.from())) { sum += column.get(l.from()); n++; }
                key.put(p.id(), n == 0 ? Double.MAX_VALUE : sum / n);
            }
            List<Person> ordered = new ArrayList<>(people);
            ordered.sort(Comparator.comparingDouble(p -> key.get(p.id())));
            // a spouse who came into the family stands beside their partner
            List<Person> placed = new ArrayList<>();
            for (Person p : ordered) {
                if (placed.contains(p)) continue;
                if (key.get(p.id()) == Double.MAX_VALUE && partnerIn(t, p, ordered, key) != null) continue;   // placed with the partner below
                placed.add(p);
                for (Person s : ordered) if (!placed.contains(s) && key.get(s.id()) == Double.MAX_VALUE && married(t, p, s)) placed.add(s);
            }
            for (Person p : ordered) if (!placed.contains(p)) placed.add(p);
            int offset = (widest - placed.size()) * (BOX_W + GAP_X) / 2;
            for (int i = 0; i < placed.size(); i++) {
                column.put(placed.get(i).id(), i);
                at.put(placed.get(i).id(), new int[]{20 + offset + i * (BOX_W + GAP_X), 20 + row * (BOX_H + GAP_Y)});
            }
            row++;
        }
        return at;
    }

    /** Whether two people stand side by side as a couple: married, or the two parents of one child (no marriage is drawn for that). */
    private static boolean married(Tree t, Person a, Person b) {
        for (Link l : t.links()) if (l.kind().equals("married") && ((l.from().equals(a.id()) && l.to().equals(b.id())) || (l.from().equals(b.id()) && l.to().equals(a.id())))) return true;
        Set<String> ofA = new HashSet<>(), ofB = new HashSet<>();
        for (Link l : t.links()) if (l.kind().equals("parent")) { if (l.from().equals(a.id())) ofA.add(l.to()); if (l.from().equals(b.id())) ofB.add(l.to()); }
        ofA.retainAll(ofB);
        return !ofA.isEmpty();
    }

    private static Person partnerIn(Tree t, Person p, List<Person> row, Map<String, Double> key) {
        for (Person o : row) if (o != p && key.get(o.id()) != Double.MAX_VALUE && married(t, p, o)) return o;
        return null;
    }

    /** The tree as one SVG. {@code href} turns a person or a claim into an address ("" for none, as in a file). */
    public static String svg(Tree t, Function<String, String> personHref, Function<String, String> claimHref) {
        if (t.focus() == null) return "";
        Map<String, int[]> at = layout(t);
        int w = at.values().stream().mapToInt(p -> p[0]).max().orElse(0) + BOX_W + 20, h = at.values().stream().mapToInt(p -> p[1]).max().orElse(0) + BOX_H + 20;
        StringBuilder s = new StringBuilder("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 " + w + " " + h + "\" width=\"" + w + "\" height=\"" + h + "\" font-family=\"system-ui, 'Noto Sans CJK JP', sans-serif\" role=\"img\" aria-label=\"Family tree of " + esc(t.focus().heading()) + "\">\n"
                + "<rect x=\"0\" y=\"0\" width=\"" + w + "\" height=\"" + h + "\" fill=\"#fbf8f1\"/>\n");
        // presentation attributes, not a stylesheet: a file viewer or a converter that ignores CSS paints every shape black
        for (Link l : t.links()) {
            int[] a = at.get(l.from()), b = at.get(l.to());
            if (a == null || b == null) continue;
            String stroke = l.state().equals("accepted") ? "#2f6f4f" : l.state().equals("disputed") ? "#c3402f" : "#9a948a";
            // a claim that rests on a clue only (an account, somebody's tree) is drawn thin: it is where the search starts, not a fact seen in a record
            boolean clueOnly = l.evidence().equals("clue");
            String cls = "fill=\"none\" stroke-width=\"" + (clueOnly ? "1" : "2") + "\" stroke=\"" + stroke + "\"" + (l.state().equals("disputed") ? " stroke-dasharray=\"2 3\"" : l.kind().equals("adopted") ? " stroke-dasharray=\"6 4\"" : clueOnly ? " stroke-dasharray=\"3 3\"" : "");
            String open = claimHref.apply(l.finding()).isEmpty() ? "" : "<a href=\"" + esc(claimHref.apply(l.finding())) + "\">", close = open.isEmpty() ? "" : "</a>";
            String title = "<title>" + esc(l.kind().equals("married") ? "married" : l.kind().equals("adopted") ? "adopted by" : "child of") + " — " + esc(l.state()) + " claim " + esc(l.finding()) + (l.evidence().isEmpty() ? "" : ", " + Evidence.valueOf(l.evidence()).word()) + "</title>";
            if (l.kind().equals("sibling")) {
                int ys = a[1] - 8, xa = a[0] + BOX_W / 2, xb = b[0] + BOX_W / 2;
                s.append(open).append("<g fill=\"none\" stroke-width=\"1.4\" stroke-dasharray=\"1 4\" stroke-linecap=\"round\" stroke=\"").append(stroke).append("\"><title>brother or sister — ").append(esc(l.state())).append(" claim ").append(esc(l.finding())).append("</title>")
                 .append("<path d=\"M").append(xa).append(" ").append(a[1]).append(" V").append(ys).append(" H").append(xb).append(" V").append(b[1]).append("\"/></g>").append(close).append('\n');
            } else if (l.kind().equals("married")) {
                int y = a[1] + BOX_H / 2, x1 = Math.min(a[0], b[0]) + BOX_W, x2 = Math.max(a[0], b[0]);
                s.append(open).append("<g ").append(cls).append(">").append(title)
                 .append("<line x1=\"").append(x1).append("\" y1=\"").append(y - 3).append("\" x2=\"").append(x2).append("\" y2=\"").append(y - 3).append("\"/>")
                 .append("<line x1=\"").append(x1).append("\" y1=\"").append(y + 3).append("\" x2=\"").append(x2).append("\" y2=\"").append(y + 3).append("\"/></g>").append(close).append('\n');
            } else {
                int x1 = a[0] + BOX_W / 2, y1 = a[1] + BOX_H, x2 = b[0] + BOX_W / 2, y2 = b[1];
                // an adoption sits a little to the side, so it does not hide under the line from the birth parents
                if (l.kind().equals("adopted")) { x1 += 14; x2 += 14; }
                String d;
                if (y2 - y1 <= GAP_Y) d = "M" + x1 + " " + y1 + " V" + (y1 + (y2 - y1) / 2) + " H" + x2 + " V" + y2;
                else {
                    // more than one generation apart (a grandfather adopting a grandson): down the gutter beside the child's column, not through the boxes between
                    int gutter = b[0] - GAP_X / 2;
                    d = "M" + x1 + " " + y1 + " V" + (y1 + GAP_Y / 3) + " H" + gutter + " V" + (y2 - GAP_Y / 3) + " H" + x2 + " V" + y2;
                }
                s.append(open).append("<path ").append(cls).append(" d=\"").append(d).append("\">").append(title).append("</path>").append(close).append('\n');
            }
        }
        for (Person p : t.people()) {
            int[] xy = at.get(p.id());
            String years = p.born().isEmpty() && p.died().isEmpty() ? "" : (p.born().isEmpty() ? "?" : brief(p.born())) + " – " + (p.died().isEmpty() ? (p.mayBeLiving() ? "" : "?") : brief(p.died()));
            // the second line: the years, the birth name beside the latest, the work
            String second = p.bornAs().isEmpty() && p.work().isEmpty() ? years
                    : String.join(" · ", List.of(years, p.bornAs().isEmpty() ? "" : "born " + p.bornAs(), p.work()).stream().map(String::strip).filter(x -> !x.isEmpty()).toList());
            String open = personHref.apply(p.label()).isEmpty() ? "" : "<a href=\"" + esc(personHref.apply(p.label())) + "\">", close = open.isEmpty() ? "" : "</a>";
            boolean isFocus = p.id().equals(t.focus().id());
            s.append(open).append("<g><title>").append(esc(p.heading())).append("</title><rect fill=\"#fffdf8\" stroke=\"").append(isFocus ? "#c3402f" : "#57524a").append("\" stroke-width=\"").append(isFocus ? "2.4" : "1.2").append("\" x=\"").append(xy[0]).append("\" y=\"").append(xy[1]).append("\" width=\"").append(BOX_W).append("\" height=\"").append(BOX_H).append("\" rx=\"6\"/>")
             .append("<text fill=\"#26221c\" font-size=\"13\" x=\"").append(xy[0] + 10).append("\" y=\"").append(xy[1] + 21).append("\">").append(esc(fit(p.shown(), 22))).append("</text>")
             .append("<text fill=\"#6b655b\" font-size=\"11\" x=\"").append(xy[0] + 10).append("\" y=\"").append(xy[1] + 38).append("\">").append(esc(fit(second, 28))).append("</text>")
             .append("</g>").append(close).append('\n');
        }
        return s.append("</svg>\n").toString();
    }

    private static String fit(String s, int max) { return s.codePointCount(0, s.length()) <= max ? s : s.substring(0, s.offsetByCodePoints(0, max - 1)) + "…"; }

    static String esc(String s) { return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;"); }
}
