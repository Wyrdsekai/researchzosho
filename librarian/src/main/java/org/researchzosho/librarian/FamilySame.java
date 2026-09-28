package org.researchzosho.librarian;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * What else two people of one name share, fact by fact. A name is not a person: two records belong to one person only when
 * something else agrees too. This lays the two side by side, relation by relation (a parent against a parent, a husband or wife
 * against a husband or wife, a birthplace against a birthplace), by the node in the library and never by a name alone, so a
 * father's wife and a son's mother of one name are not taken for a sign. What agrees and what differs are listed with their
 * claims. Whether they are one person stays the family's word.
 */
public final class FamilySame {

    private FamilySame() { }

    /** One thing that agrees or differs, as a sentence, with the claims it rests on. */
    public record Point(String text, List<String> findings) { }

    /**
     * {@code related}: the claims say one is the other's parent, child, husband, wife, brother or sister, so they are two people.
     * {@code differ}: what does not fit one person (two birthplaces, two birth years, more birth parents than a person has).
     */
    public record Comparison(List<Point> agree, List<Point> differ, Point related) {
        public boolean twoPeople() { return related != null; }

        /** Every claim the comparison names. */
        public List<String> findings() {
            Set<String> out = new LinkedHashSet<>();
            for (Point p : agree) out.addAll(p.findings());
            for (Point p : differ) out.addAll(p.findings());
            if (related != null) out.addAll(related.findings());
            return new ArrayList<>(out);
        }

        /** "What else agrees: both were born in 1872; both are children of Isamu Takahashi." or that nothing does, and what differs. */
        public String said() {
            StringBuilder b = new StringBuilder();
            if (agree.isEmpty()) b.append("Nothing else agrees: no parent, husband or wife, child, brother or sister, place or year is the same for both.");
            else b.append("What else agrees: ").append(String.join("; ", agree.stream().map(Point::text).toList())).append(".");
            if (!differ.isEmpty()) b.append(" What differs: ").append(String.join("; ", differ.stream().map(Point::text).toList())).append(".");
            return b.toString();
        }

        /** The reason written with a merge: what agrees, in few words. */
        public String reason() { return agree.isEmpty() ? "" : String.join("; ", agree.stream().map(Point::text).toList()); }
    }

    private record Side(Map<String, String> parents, Map<String, String> children, Map<String, String> spouses, Map<String, String> siblings,
                        Map<String, String[]> places, Map<String, FamilyDate> dates, Map<String, String> dateClaims, Map<String, String> work) { }

    private static final Set<String> KIN = Set.of("parent-of", "child-of", "married-to", "sibling-of", "adopted-by", "step-parent-of", "foster-child-of", "parent-in-law-of");

    public static Comparison compare(Graph g, Map<String, Finding> findings, String a, String b) {
        Side x = side(g, findings, a), y = side(g, findings, b);
        String an = label(g, a), bn = label(g, b);
        // a claim that ties the two together says they are two
        for (Graph.Edge e : g.edges()) {
            if (FamilyKin.gone(e) || !KIN.contains(e.predicate())) continue;
            if (e.from().equals(a) && e.to().equals(b) || e.from().equals(b) && e.to().equals(a))
                return new Comparison(List.of(), List.of(), new Point(FamilyAccount.sentence(label(g, e.from()), e.predicate(), label(g, e.to())), List.of(e.findingId())));
        }
        List<Point> agree = new ArrayList<>(), differ = new ArrayList<>();
        shared(g, x.parents(), y.parents(), "both are children of ", agree);
        shared(g, x.spouses(), y.spouses(), "both were married to ", agree);
        shared(g, x.children(), y.children(), "both are parents of ", agree);
        shared(g, x.siblings(), y.siblings(), "both are brothers or sisters of ", agree);
        // each place in a sentence of its own kind: "both were born in York", "both died in Leeds", "Tom Hale died in York and Tom Hales in Leeds"
        for (String[] w : new String[][]{{"born-in", "were born in", "was born in"}, {"died-in", "died in", "died in"}, {"buried-in", "were buried in", "was buried in"}}) {
            String[] p = x.places().get(w[0]), q = y.places().get(w[0]);
            if (p == null || q == null) continue;
            if (samePlace(p[0], q[0])) agree.add(new Point("both " + w[1] + " " + p[0], List.of(p[1], q[1])));
            else if (!w[0].equals("buried-in")) differ.add(new Point(an + " " + w[2] + " " + p[0] + " and " + bn + " in " + q[0], List.of(p[1], q[1])));
        }
        for (String[] w : new String[][]{{"born", "born"}, {"died", "died"}}) {
            FamilyDate p = x.dates().get(w[0]), q = y.dates().get(w[0]);
            if (p == null || q == null) continue;
            List<String> ids = List.of(x.dateClaims().get(w[0]), y.dateClaims().get(w[0]));
            if (FamilyDate.apart(p, q, 0)) differ.add(new Point(an + " " + w[1] + " " + p.phrase() + " and " + bn + " " + q.phrase(), ids));
            else if (p.exact() && q.exact() && p.year() == q.year()) agree.add(new Point("both " + w[1] + " in " + p.year(), ids));
        }
        for (var w : x.work().entrySet()) if (y.work().containsKey(w.getKey())) agree.add(new Point("both worked as " + label(g, w.getKey()), List.of(w.getValue(), y.work().get(w.getKey()))));
        // both cited from one page of one source: the page says whether it names one person or two
        Map<String, String[]> pagesA = pages(g, findings, a), pagesB = pages(g, findings, b);
        for (var e : pagesA.entrySet()) if (pagesB.containsKey(e.getKey()))
            agree.add(new Point("both are named on one page of one source (" + e.getValue()[0] + ", " + e.getValue()[1] + ")", List.of(e.getValue()[2], pagesB.get(e.getKey())[2])));
        // one person has two birth parents: joining two people whose named parents are more than that joins two families
        Set<String> parents = new LinkedHashSet<>();
        for (String p : x.parents().keySet()) if (!FamilyQuestions.placeholder(label(g, p))) parents.add(p);
        for (String p : y.parents().keySet()) if (!FamilyQuestions.placeholder(label(g, p))) parents.add(p);
        parents.removeIf(p -> parents.stream().anyMatch(o -> !o.equals(p) && FamilyChecks.partOfName(label(g, p), label(g, o))));
        if (parents.size() > 2) {
            List<String> ids = new ArrayList<>(x.parents().values()); ids.addAll(y.parents().values());
            differ.add(new Point("as one person they would have " + parents.size() + " birth parents (" + String.join(", ", parents.stream().map(p -> label(g, p)).toList()) + ")", ids));
        }
        return new Comparison(agree, differ, null);
    }

    /** The pages of sources a person's claims cite, by {@link Gedcom#unitKey}: {title, page, the first claim}. */
    private static Map<String, String[]> pages(Graph g, Map<String, Finding> findings, String id) {
        Map<String, String[]> out = new LinkedHashMap<>();
        for (Graph.Edge e : g.edges()) {
            if (FamilyKin.gone(e) || !e.from().equals(id)) continue;
            Finding f = findings.get(e.findingId());
            if (f == null) continue;
            for (Finding.Source s : f.sources()) { String[] u = Gedcom.unitOf(s); if (u != null) out.putIfAbsent(Gedcom.unitKey(u), new String[]{u[0], u[1], f.id()}); }
        }
        return out;
    }

    private static void shared(Graph g, Map<String, String> p, Map<String, String> q, String words, List<Point> agree) {
        for (var e : p.entrySet()) if (q.containsKey(e.getKey())) agree.add(new Point(words + label(g, e.getKey()), List.of(e.getValue(), q.get(e.getKey()))));
    }

    private static Side side(Graph g, Map<String, Finding> findings, String id) {
        Map<String, String> parents = new LinkedHashMap<>(), children = new LinkedHashMap<>(), spouses = new LinkedHashMap<>(), siblings = new LinkedHashMap<>(), dateClaims = new LinkedHashMap<>(), work = new LinkedHashMap<>();
        Map<String, String[]> places = new LinkedHashMap<>();
        Map<String, FamilyDate> dates = new LinkedHashMap<>();
        for (Graph.Edge e : g.edges()) {
            if (FamilyKin.gone(e)) continue;
            boolean from = e.from().equals(id), to = e.to().equals(id);
            if (!from && !to) continue;
            String other = from ? e.to() : e.from();
            switch (e.predicate()) {
                case "parent-of" -> (from ? children : parents).putIfAbsent(other, e.findingId());
                case "child-of" -> (from ? parents : children).putIfAbsent(other, e.findingId());
                case "married-to" -> spouses.putIfAbsent(other, e.findingId());
                case "sibling-of" -> siblings.putIfAbsent(other, e.findingId());
                case "occupation" -> { if (from) work.putIfAbsent(other, e.findingId()); }
                case "born-in", "died-in", "buried-in", "born-on", "died-on" -> {
                    if (!from) break;
                    Graph.Node n = g.node(other);
                    String what = e.predicate().startsWith("born") ? "born" : e.predicate().startsWith("died") ? "died" : "";
                    if (e.predicate().endsWith("-in") && n != null) places.putIfAbsent(e.predicate(), new String[]{n.label(), e.findingId()});
                    FamilyDate d = e.predicate().endsWith("-on") ? (n == null ? null : FamilyDate.parse(n.label())) : findings.get(e.findingId()) == null ? null : FamilyChecks.claimDate(findings.get(e.findingId()));
                    if (!what.isEmpty() && d != null && (!dates.containsKey(what) || d.latest() - d.earliest() < dates.get(what).latest() - dates.get(what).earliest())) { dates.put(what, d); dateClaims.put(what, e.findingId()); }
                }
                default -> { }
            }
        }
        return new Side(parents, children, spouses, siblings, places, dates, dateClaims, work);
    }

    /** One place written two ways: the same once folded (広島 and 廣島, Endō and Endo), or one inside the other (広島 and 広島県広島市). */
    static boolean samePlace(String p, String q) {
        String a = fold(p).replaceAll("[\\s,.、，]+", ""), b = fold(q).replaceAll("[\\s,.、，]+", "");
        if (a.isEmpty() || b.isEmpty()) return false;
        if (a.equals(b)) return true;
        boolean cjk = (a + b).codePoints().anyMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN);
        if (cjk) return Math.min(a.length(), b.length()) >= 2 && (a.contains(b) || b.contains(a));
        // in Latin letters a place is its parts between commas: York is not inside New York, Dunedin is inside "Dunedin, Otago"
        Set<String> x = parts(p), y = parts(q);
        if (x.containsAll(y) || y.containsAll(x)) return true;
        // the place itself, the first part of one, written as a word of the other: "Koishikawa, Tokyo" and "the Koishikawa ward of Tokyo"
        String fx = x.iterator().next(), fy = y.iterator().next();
        return fx.length() >= 4 && wordOf(fx, fold(q)) || fy.length() >= 4 && wordOf(fy, fold(p));
    }

    private static boolean wordOf(String part, String text) {
        return Pattern.compile("(?<![\\p{L}])" + Pattern.quote(part) + "(?![\\p{L}])").matcher(text).find();
    }

    private static Set<String> parts(String place) {
        Set<String> out = new LinkedHashSet<>();
        for (String s : fold(place).split("[,，、]")) if (!s.isBlank()) out.add(s.strip());
        return out;
    }

    private static String fold(String s) { return KanjiForms.modern(FamilyQuestions.plain(s)); }

    private static String label(Graph g, String id) { return FamilyKin.label(g, id); }
}
