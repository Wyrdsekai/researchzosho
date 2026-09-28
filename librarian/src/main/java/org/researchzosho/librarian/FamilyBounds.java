package org.researchzosho.librarian;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The years a person's birth and death can lie in, worked out from the claims around them: their own dates and doings, an age a
 * record gives, and the births and deaths of their parents and children. Only rules that cannot bend are used: a parent is at least
 * {@link FamilyChecks#YOUNGEST_PARENT} at a child's birth, a child is born no later than a year after a parent dies, a married person
 * is at least as old, and a person is born before anything they do and dies after they last marry or move. So a window is what the
 * claims prove, not a guess. Nothing is filed: a window is shown with the claims it comes from, and a claim of a birth date replaces it.
 */
public final class FamilyBounds {

    private FamilyBounds() { }

    /** A year a birth or a death is no earlier (or no later) than, what it comes from, in words, and the claims it rests on. */
    public record Bound(int year, String because, List<String> findings) { }

    public record Window(Bound bornAfter, Bound bornBefore, Bound diedAfter, Bound diedBefore) {
        /** "between 1830 and 1843", "in 1843 or before", "in 1830 or after"; "" when nothing bounds the birth. */
        public String born() { return phrase(bornAfter, bornBefore); }
        public String died() { return phrase(diedAfter, diedBefore); }
        /** The claims the birth window rests on. */
        public List<String> bornFindings() {
            List<String> out = new ArrayList<>();
            for (Bound b : new Bound[]{bornAfter, bornBefore}) if (b != null) for (String f : b.findings()) if (!out.contains(f)) out.add(f);
            return out;
        }
        private static String phrase(Bound after, Bound before) {
            if (after != null && before != null) return after.year() >= before.year() ? "in " + before.year() : "between " + after.year() + " and " + before.year();
            if (before != null) return "in " + before.year() + " or before";
            return after == null ? "" : "in " + after.year() + " or after";
        }
    }

    public static Map<String, Window> of(LibraryStore store) throws IOException {
        Map<String, Finding> findings = new LinkedHashMap<>();
        for (Finding f : store.scanFindings().findings()) findings.put(f.id(), f);
        return of(FamilyPeople.view(store), findings);
    }

    /** Every person with at least one bound, by node id. */
    static Map<String, Window> of(Graph g, Map<String, Finding> findings) {
        Map<String, Bound[]> at = new LinkedHashMap<>();   // id → {born after, born before, died after, died before}
        List<String[]> parents = new ArrayList<>();        // [parent, child, claim]
        for (Graph.Edge e : g.edges()) {
            if (FamilyKin.gone(e)) continue;   // a disputed date bounds nobody
            Finding f = findings.get(e.findingId());
            Graph.Node to = g.node(e.to());
            String p = e.predicate(), id = e.findingId();
            switch (p) {
                case "parent-of" -> parents.add(new String[]{e.from(), e.to(), id});
                case "child-of" -> parents.add(new String[]{e.to(), e.from(), id});
                default -> { }
            }
            FamilyDate d = p.endsWith("-on") ? (to == null ? null : FamilyDate.parse(to.label())) : f == null ? null : FamilyChecks.claimDate(f);
            if (d == null) continue;
            String who = e.from();
            List<String> one = List.of(id);
            switch (p) {
                case "born-on", "born-in" -> { lower(at, who, 0, d.earliest(), "the birth date, " + d.phrase(), one); upper(at, who, 1, d.latest(), "the birth date, " + d.phrase(), one); }
                case "died-on", "died-in" -> {
                    lower(at, who, 2, d.earliest(), "the death, " + d.phrase(), one); upper(at, who, 3, d.latest(), "the death, " + d.phrase(), one);
                    upper(at, who, 1, d.latest(), "the death, " + d.phrase(), one);
                }
                case "buried-in" -> { upper(at, who, 3, d.latest(), "the burial, " + d.phrase(), one); upper(at, who, 1, d.latest(), "the burial, " + d.phrase(), one); }
                case "lived-in", "migrated-to" -> {
                    String what = FamilyAccount.sentence("", p, to == null ? "" : to.label()).strip() + ", " + d.phrase();
                    upper(at, who, 1, d.latest(), what, one); lower(at, who, 2, d.earliest(), what, one);
                }
                case "occupation", "life-event" -> upper(at, who, 1, d.latest(), FamilyAccount.sentence("", p, to == null ? "" : to.label()).replaceFirst("^:\\s*", "").strip() + ", " + d.phrase(), one);
                case "married-to" -> {
                    for (String w : new String[]{e.from(), e.to()}) {
                        upper(at, w, 1, d.latest() - FamilyChecks.YOUNGEST_PARENT, FamilyChecks.YOUNGEST_PARENT + " years before the marriage, " + d.phrase(), one);
                        lower(at, w, 2, d.earliest(), "the marriage, " + d.phrase(), one);
                    }
                }
                case "aged" -> {
                    FamilyDate born = FamilyDate.bornFrom(FamilyDate.age(to == null ? "" : to.label()), d);
                    if (born == null) break;
                    String what = "aged " + to.label() + " " + d.in();
                    lower(at, who, 0, born.earliest(), what, one); upper(at, who, 1, born.latest(), what, one);
                }
                default -> { }
            }
        }
        // a person recorded as their own parent, or as their own ancestor, is a mistake in the claims (the check lists it): the arithmetic
        // would go round the circle forty times, so a parent and child the circle joins bound nothing
        parents.removeIf(pc -> pc[0].equals(pc[1]) || descends(parents, pc[1], pc[0]));
        // parents and children bound each other, and through them the generations above and below: until nothing moves
        for (int pass = 0; pass < 40; pass++) {
            boolean moved = false;
            for (String[] pc : parents) {
                String parent = pc[0], child = pc[1];
                Bound[] pb = at.get(parent), cb = at.get(child);
                String pn = label(g, parent), cn = label(g, child);
                int young = FamilyChecks.YOUNGEST_PARENT;
                if (cb != null && cb[1] != null) moved |= upper(at, parent, 1, cb[1].year() - young, "the child " + cn + " was born in " + cb[1].year() + " at the latest, and a parent is at least " + young, with(pc[2], cb[1]));
                if (pb != null && pb[0] != null) moved |= lower(at, child, 0, pb[0].year() + young, "the parent " + pn + " was born in " + pb[0].year() + " at the earliest, and a parent is at least " + young, with(pc[2], pb[0]));
                if (pb != null && pb[3] != null) moved |= upper(at, child, 1, pb[3].year() + 1, "the parent " + pn + " died in " + pb[3].year() + " at the latest, and a child is born within a year of a parent's death", with(pc[2], pb[3]));
                if (cb != null && cb[0] != null) moved |= lower(at, parent, 2, cb[0].year() - 1, "the child " + cn + " was born in " + cb[0].year() + " at the earliest, and a parent dies no more than a year before the birth", with(pc[2], cb[0]));
            }
            if (!moved) break;
        }
        Map<String, Window> out = new LinkedHashMap<>();
        at.forEach((id, b) -> out.put(id, new Window(b[0], b[1], b[2], b[3])));
        return out;
    }

    private static String label(Graph g, String id) { Graph.Node n = g.node(id); return n == null ? id : n.label(); }

    /** Whether {@code to} is {@code from}'s descendant through the parent claims: then a claim that {@code to} is a parent of {@code from} closes a circle. */
    private static boolean descends(List<String[]> parents, String from, String to) {
        Set<String> seen = new HashSet<>();
        ArrayDeque<String> q = new ArrayDeque<>(List.of(from));
        while (!q.isEmpty()) {
            String at = q.poll();
            if (!seen.add(at)) continue;
            for (String[] pc : parents) if (pc[0].equals(at)) { if (pc[1].equals(to)) return true; q.add(pc[1]); }
        }
        return false;
    }

    private static List<String> with(String claim, Bound b) {
        List<String> out = new ArrayList<>(List.of(claim));
        for (String f : b.findings()) if (!out.contains(f)) out.add(f);
        return out;
    }

    /** Raise a no-earlier-than bound (slot 0 birth, 2 death); an open end is no bound. True when it moved. */
    private static boolean lower(Map<String, Bound[]> at, String who, int slot, int year, String because, List<String> findings) {
        if (year <= -FamilyDate.OPEN / 2) return false;
        Bound[] b = at.computeIfAbsent(who, k -> new Bound[4]);
        if (b[slot] != null && b[slot].year() >= year) return false;
        b[slot] = new Bound(year, because, findings);
        return true;
    }

    /** Lower a no-later-than bound (slot 1 birth, 3 death). True when it moved. */
    private static boolean upper(Map<String, Bound[]> at, String who, int slot, int year, String because, List<String> findings) {
        if (year >= FamilyDate.OPEN / 2) return false;
        Bound[] b = at.computeIfAbsent(who, k -> new Bound[4]);
        if (b[slot] != null && b[slot].year() <= year) return false;
        b[slot] = new Bound(year, because, findings);
        return true;
    }
}
