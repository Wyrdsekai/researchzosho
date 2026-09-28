package org.researchzosho.librarian;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The claims genealogy's own view sets aside, worked out from the claims each time the view is read and never stored: a parent and a child
 * whom the birth years or the claim's own words make the wrong way round, a parent and a child who are written as husband and wife too,
 * and a sex that the words of its claim do not say of the person while the words of another claim say the other sex. A claim set aside counts as disputed in the view only: it raises no question, moves
 * nobody in the tree, and gives nobody a sex. The claim itself stays as it was, and the person's page shows it with the reason.
 */
public final class FamilyDoubts {

    private FamilyDoubts() { }

    /** A parent is at least this many years older than their child. */
    static final int PARENT_GAP = FamilyAccount.PARENT_GAP;

    /** The claims each view set aside, with why, while the view lives. */
    private static final Map<Graph, Map<String, String>> WHY = Collections.synchronizedMap(new WeakHashMap<>());
    /** The claims each view reads as another relation than the claim names, with why, while the view lives. */
    private static final Map<Graph, Map<String, String>> READ_AS = Collections.synchronizedMap(new WeakHashMap<>());
    /** The pairs each view found written as husband and wife and as parent and child, the parent first, while the view lives. */
    private static final Map<Graph, List<String[]>> MARRIED = Collections.synchronizedMap(new WeakHashMap<>());

    /** The reason a parent claim between two people written as husband and wife is set aside with. */
    public static final String MARRIED_AND_PARENT = "written as husband and wife and as parent and child: one of the two is wrong";

    /** Genealogy's own view as it is read ({@link Profile#onGraph}): each doubtful claim is set aside there, and its reason kept for the view. */
    public static void read(Graph.Reading r) {
        if (!r.wide()) return;
        Map<String, String> why = new LinkedHashMap<>();
        READ_AS.put(r.graph(), Collections.unmodifiableMap(kinds(r, why)));
        Map<String, int[]> born = born(r);
        for (Graph.Edge e : r.edges()) {
            if (FamilyKin.gone(e) || accepted(e) || why.containsKey(e.findingId()) || !(e.predicate().equals("parent-of") || e.predicate().equals("child-of")) || own(r, e) == null) continue;
            String reason = years(r, e, born);
            if (reason == null) reason = words(r, e);
            if (reason != null) why.put(e.findingId(), reason);
        }
        sexes(r, why);
        spouses(r, why);
        MARRIED.put(r.graph(), Collections.unmodifiableList(married(r, why)));
        twoWays(r, why);
        for (String id : why.keySet()) r.setAside(id);
        WHY.put(r.graph(), Collections.unmodifiableMap(why));
    }

    /** The claims a view set aside, with why: claim id → the reason, in words. */
    public static Map<String, String> setAside(Graph g) { return WHY.getOrDefault(g, Map.of()); }

    /** The pairs a view found written as husband and wife and as parent and child, each the parent's id then the child's; the parent claim is set aside. */
    public static List<String[]> marriedAndParent(Graph g) { return MARRIED.getOrDefault(g, List.of()); }

    /** The claims a view reads as another relation than the claim names, with why: claim id → the reason, in words. */
    public static Map<String, String> readAs(Graph g) { return READ_AS.getOrDefault(g, Map.of()); }

    // ── a parent the words call adoptive, a step-parent, an in-law, a godparent, a foster parent ────────────────────

    /**
     * A parent-of or child-of claim whose own words say no plain parent word and say an adoption (婿養子, 養子, 養女, adopted, adoptive), a
     * step-parent, a parent-in-law, a godparent or a foster parent is read as that relation in the family's view, as the reader now files such
     * words ({@link FamilyKin#kinds}): adopted-by, step-parent-of, parent-in-law-of, godparent-of or foster-child-of; words of a grandparent, or
     * of several such kinds, read as relative-of. The claim stays as it is, and no question is raised for it. A claim the family accepted, a
     * claim with no words, and a tree file's own data are read as they stand. Claim id → why.
     */
    private static Map<String, String> kinds(Graph.Reading r, Map<String, String> setAside) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Graph.Edge e : r.edges()) {
            if (FamilyKin.gone(e) || accepted(e) || !(e.predicate().equals("parent-of") || e.predicate().equals("child-of"))) continue;
            Finding f = own(r, e);
            if (f == null) continue;
            String quote = FamilyChecks.quoteOf(f);
            if (quote.isBlank() || f.sources().stream().anyMatch(s -> structured(s.locator()))) continue;
            // a plain parent word that is somebody else's (子爵 森田勇 二男 beside a 婿養子) does not count for the two: the same ownership as a sex word's
            FamilyKin.Kinds k = FamilyKin.kinds(quote, ways(r, e.from(), f.triple().subject()), ways(r, e.to(), f.triple().object()));
            String kind = k.instead();
            if (kind.isEmpty()) continue;
            if (kind.equals("as-if")) { setAside.put(e.findingId(), "the words say a likeness, such as \"like a father\", which is no parent (\"" + Acquisitions.compress(quote, 70) + "\")"); continue; }
            if (kind.equals("others")) { setAside.put(e.findingId(), "the words speak of somebody else's parent or child, not of these two (\"" + Acquisitions.compress(quote, 70) + "\")"); continue; }
            if (kind.equals("chain")) { setAside.put(e.findingId(), "the words say a parent or a child only as a step to somebody else (\"" + Acquisitions.compress(quote, 70) + "\")"); continue; }
            boolean down = e.predicate().equals("parent-of");
            String to = switch (kind) {
                case "adoptive" -> "adopted-by";
                case "step" -> "step-parent-of";
                case "in-law" -> "parent-in-law-of";
                case "god" -> "godparent-of";
                case "foster" -> "foster-child-of";
                default -> "relative-of";
            };
            // adopted-by and foster-child-of run from the child; the others from the parent; relative-of stays as written
            boolean fromChild = to.equals("adopted-by") || to.equals("foster-child-of"), fromParent = to.equals("step-parent-of") || to.equals("parent-in-law-of") || to.equals("godparent-of");
            boolean swap = fromChild ? down : fromParent && !down;
            r.rekind(e.findingId(), to, swap);
            String said = switch (kind) {
                case "adoptive" -> "read as adoptive: the words say " + adoptionWord(quote);
                case "step" -> "read as a step-parent: the words say so";
                case "in-law" -> "read as a parent-in-law: the words say so";
                case "god" -> "read as a godparent: the words say so";
                case "foster" -> "read as a foster parent: the words say so";
                case "grand" -> "read as a relative: the words say a grandparent, which is no parent";
                default -> "read as a relative: the words say no parent by birth, and more than one kind of relative";
            };
            out.put(e.findingId(), said);
        }
        return out;
    }

    // the words that say an adoption, the longest first, so that 婿養子 is said whole
    private static final Pattern ADOPTION_WORD = Pattern.compile("(?i)婿養子|養子縁組|養子|養女|養父|養母|養親|adoptive|adopted");

    /** The word of the quote that says the adoption, as written: 婿養子, 養父, "adoptive". */
    private static String adoptionWord(String quote) {
        Matcher m = ADOPTION_WORD.matcher(quote);
        return m.find() ? m.group() : "adoption";
    }

    /** A sibling-of claim whose words say a wife or a husband and no brother, sister, parent or child (two names under 妻：) is set aside: they are wives, not brother and sister. */
    private static void spouses(Graph.Reading r, Map<String, String> why) {
        for (Graph.Edge e : r.edges()) {
            if (FamilyKin.gone(e) || accepted(e) || why.containsKey(e.findingId()) || !e.predicate().equals("sibling-of")) continue;
            Finding f = own(r, e);
            if (f == null) continue;
            String quote = FamilyChecks.quoteOf(f);
            if (quote.isBlank() || f.sources().stream().anyMatch(s -> structured(s.locator()))) continue;
            if (FamilyKin.spousesOnly(quote, ways(r, e.from(), f.triple().subject()), ways(r, e.to(), f.triple().object())))
                why.put(e.findingId(), "the words speak of a wife or a husband, not of a brother or sister (\"" + Acquisitions.compress(quote, 70) + "\")");
        }
    }

    /**
     * A parent-of or child-of claim between two people who are also written as husband and wife, from any source, is set aside: one of the two is
     * wrong, and the parent claim is the one the view leaves out (a described party such as "the writer's father" that the links resolved to the
     * husband is the usual way it comes about). No question is raised; the summary says so once. The pairs found, the parent first.
     */
    private static List<String[]> married(Graph.Reading r, Map<String, String> why) {
        Set<String> wed = new LinkedHashSet<>();
        for (Graph.Edge e : r.edges()) if (!FamilyKin.gone(e) && e.predicate().equals("married-to") && !e.from().equals(e.to()) && !why.containsKey(e.findingId())) wed.add(Graph.pair(e.from(), e.to()));
        List<String[]> out = new ArrayList<>();
        Set<String> said = new LinkedHashSet<>();
        for (Graph.Edge e : r.edges()) {
            if (FamilyKin.gone(e) || accepted(e) || why.containsKey(e.findingId()) || !(e.predicate().equals("parent-of") || e.predicate().equals("child-of")) || own(r, e) == null) continue;
            if (!wed.contains(Graph.pair(e.from(), e.to()))) continue;
            why.put(e.findingId(), MARRIED_AND_PARENT);
            boolean down = e.predicate().equals("parent-of");
            String parent = down ? e.from() : e.to(), child = down ? e.to() : e.from();
            if (said.add(Graph.pair(parent, child))) out.add(new String[]{parent, child});
        }
        return out;
    }

    /**
     * One source read two ways: a parent-of or child-of claim and a sibling-of claim between the same two people from the same source cannot both
     * stand (a 女 line under one block read as the daughter of one man and as his sister). Both are set aside with the one reason, and no question
     * of a birth or an adoptive parent is asked about them.
     */
    private static void twoWays(Graph.Reading r, Map<String, String> why) {
        Map<String, List<Graph.Edge>> byPair = new LinkedHashMap<>();
        for (Graph.Edge e : r.edges()) {
            if (FamilyKin.gone(e) || accepted(e) || why.containsKey(e.findingId()) || own(r, e) == null) continue;
            if (!(e.predicate().equals("parent-of") || e.predicate().equals("child-of") || e.predicate().equals("sibling-of"))) continue;
            byPair.computeIfAbsent(Graph.pair(e.from(), e.to()), k -> new ArrayList<>()).add(e);
        }
        for (List<Graph.Edge> es : byPair.values()) {
            List<Graph.Edge> parents = es.stream().filter(e -> !e.predicate().equals("sibling-of")).toList(), siblings = es.stream().filter(e -> e.predicate().equals("sibling-of")).toList();
            if (parents.isEmpty() || siblings.isEmpty()) continue;
            for (Graph.Edge p : parents) for (Graph.Edge sib : siblings) {
                Finding a = r.claims().get(p.findingId()), b = r.claims().get(sib.findingId());
                if (a == null || b == null || !sameSource(a, b)) continue;
                boolean down = p.predicate().equals("parent-of");
                String parent = label(r, down ? p.from() : p.to()), child = label(r, down ? p.to() : p.from());
                String reason = "the source is read two ways: it says " + parent + " is a parent of " + child + " and also a brother or sister of " + child + ", and the words settle neither";
                why.putIfAbsent(p.findingId(), reason);
                why.putIfAbsent(sib.findingId(), reason);
            }
        }
    }

    /** Whether two claims come from one source: a locator they share. */
    private static boolean sameSource(Finding a, Finding b) {
        for (Finding.Source x : a.sources()) for (Finding.Source y : b.sources()) if (x.locator() != null && x.locator().equals(y.locator())) return true;
        return false;
    }

    /** The claims about a person that a view reads as another relation, each as a line for the person's page: the claim, its code and why. */
    public static List<String> readAsAbout(LibraryStore store, Graph g, String id) throws IOException {
        List<String> out = new ArrayList<>();
        Map<String, String> why = readAs(g);
        if (why.isEmpty()) return out;
        for (Graph.Edge e : g.edges()) {
            if (!why.containsKey(e.findingId()) || !(e.from().equals(id) || e.to().equals(id))) continue;
            Finding f = store.finding(e.findingId());
            String said = f == null ? e.findingId() : f.body().lines().findFirst().orElse(f.title()).strip().replaceFirst("[.。]$", "");
            out.add("\"" + said + "\" (" + e.findingId().replaceFirst("^(F-\\d+).*", "$1") + ") is " + why.get(e.findingId()) + ".");
        }
        return out;
    }

    /** The heading of the lines of a person's page about the claims read as another relation. */
    public static final String READ_AS_HEADING = "Read as the words say";

    /** The claims about a person that a view set aside, each as a line for the person's page: the claim, its code and the reason. */
    public static List<String> about(LibraryStore store, Graph g, String id) throws IOException {
        List<String> out = new ArrayList<>();
        Map<String, String> why = setAside(g);
        if (why.isEmpty()) return out;
        for (Graph.Edge e : g.edges()) {
            if (!why.containsKey(e.findingId()) || !(e.from().equals(id) || e.to().equals(id) && !e.predicate().equals("sex"))) continue;
            Finding f = store.finding(e.findingId());
            String said = f == null ? e.findingId() : f.body().lines().findFirst().orElse(f.title()).strip().replaceFirst("[.。]$", "");
            out.add("\"" + said + "\" (" + e.findingId().replaceFirst("^(F-\\d+).*", "$1") + ") is set aside in the family's view: " + why.get(e.findingId()) + ".");
        }
        return out;
    }

    /** The heading of the lines of a person's page about the claims set aside. */
    public static final String HEADING = "Set aside by the dates or the words";

    /** What a claim set aside is, said under those lines. */
    public static final String WHAT = "A claim set aside stays in your library as it was. The family's pages and questions leave it out while its dates or its words say it cannot be right. "
            + "To keep it all the same, accept it: researchzosho accept <its code>.";

    // ── a parent and a child the wrong way round ──────────────────────────────────────────────────────────────────

    /** Each person's birth, as the earliest and the latest year every birth claim of theirs allows. */
    private static Map<String, int[]> born(Graph.Reading r) {
        Map<String, int[]> out = new LinkedHashMap<>();
        for (Graph.Edge e : r.edges()) {
            if (FamilyKin.gone(e) || !(e.predicate().equals("born-on") || e.predicate().equals("born-in"))) continue;
            Graph.Node when = r.nodeOf(e.to());
            Finding f = r.claims().get(e.findingId());
            FamilyDate d = e.predicate().equals("born-on") ? FamilyDate.parse(when == null ? "" : when.label()) : f == null ? null : FamilyChecks.claimDate(f);
            if (d == null) continue;
            int[] had = out.get(e.from());
            out.put(e.from(), had == null ? new int[]{d.earliest(), d.latest()} : new int[]{Math.min(had[0], d.earliest()), Math.max(had[1], d.latest())});
        }
        return out;
    }

    /** The reason the birth years give against a parent-child claim, or null: the parent is not at least {@link #PARENT_GAP} years older. */
    private static String years(Graph.Reading r, Graph.Edge e, Map<String, int[]> born) {
        boolean down = e.predicate().equals("parent-of");
        String parent = down ? e.from() : e.to(), child = down ? e.to() : e.from();
        int[] p = born.get(parent), c = born.get(child);
        if (p == null || c == null || p[0] <= c[1] - PARENT_GAP) return null;
        String pn = label(r, parent), cn = label(r, child);
        return (p[0] >= c[1] ? "the years say this is the wrong way round" : "the years say this cannot be, as a parent is at least " + PARENT_GAP + " years older than a child")
                + " (" + pn + " was born " + years(p) + ", " + cn + " " + years(c) + ")";
    }

    private static String years(int[] range) { return range[0] == range[1] ? "in " + range[0] : "between " + range[0] + " and " + range[1]; }

    /** The reason the claim's own words give against it, or null: its words make the other one the parent ({@link FamilyKin#parentByWords}). */
    private static String words(Graph.Reading r, Graph.Edge e) {
        Finding f = r.claims().get(e.findingId());
        if (f == null || f.triple() == null) return null;
        String quote = FamilyChecks.quoteOf(f);
        if (quote.isBlank()) return null;
        boolean down = e.predicate().equals("parent-of");
        String parentWritten = down ? f.triple().subject() : f.triple().object(), childWritten = down ? f.triple().object() : f.triple().subject();
        String parent = down ? e.from() : e.to(), child = down ? e.to() : e.from();
        if (!FamilyKin.parentByWords(quote, ways(r, parent, parentWritten), ways(r, child, childWritten), teller(f)).equals("b")) return null;
        return "the words say this is the wrong way round (\"" + Acquisitions.compress(quote, 90) + "\")";
    }

    // ── a sex the words do not say ────────────────────────────────────────────────────────────────────────────────

    /**
     * For each person whose sex claims disagree: the claims whose own words do not say that sex of the person are set aside, when the words
     * of the claims of the other sex do say it. A claim with no words (a tree file's field, the family's answer) stands as it is. Where the
     * words decide nothing, both stay, and the checks and the majority rule ({@link FamilyClose#sexOf}) go on as before.
     */
    private static void sexes(Graph.Reading r, Map<String, String> why) {
        Map<String, List<Graph.Edge>> byPerson = new LinkedHashMap<>();
        for (Graph.Edge e : r.edges()) if (!FamilyKin.gone(e) && e.predicate().equals("sex") && !why.containsKey(e.findingId())) byPerson.computeIfAbsent(e.from(), k -> new ArrayList<>()).add(e);
        for (Map.Entry<String, List<Graph.Edge>> p : byPerson.entrySet()) {
            Map<Graph.Edge, String> sexOf = new LinkedHashMap<>();
            for (Graph.Edge e : p.getValue()) { Graph.Node to = r.nodeOf(e.to()); String s = FamilyKin.sexWord(to == null ? "" : to.label()); if (!s.isEmpty()) sexOf.put(e, s); }
            if (new LinkedHashSet<>(sexOf.values()).size() < 2) continue;
            Set<String> supported = new LinkedHashSet<>();
            Map<Graph.Edge, Boolean> held = new LinkedHashMap<>();
            for (Map.Entry<Graph.Edge, String> s : sexOf.entrySet()) {
                boolean ok = supports(r, p.getKey(), s.getKey(), s.getValue());
                held.put(s.getKey(), ok);
                if (ok) supported.add(s.getValue());
            }
            if (supported.size() != 1) continue;
            String sex = supported.iterator().next();
            for (Map.Entry<Graph.Edge, String> s : sexOf.entrySet()) {
                if (s.getValue().equals(sex) || held.get(s.getKey()) || accepted(s.getKey())) continue;
                Finding f = r.claims().get(s.getKey().findingId());
                String quote = f == null ? "" : FamilyChecks.quoteOf(f);
                why.put(s.getKey().findingId(), "its words (\"" + Acquisitions.compress(quote, 70) + "\") do not say it of " + label(r, p.getKey()) + ", and the words of another claim say " + label(r, p.getKey()) + " is " + sex);
            }
        }
    }

    /**
     * Whether a sex claim rests on words that say it of this person: a word of the person's own, a pronoun that plainly refers to them, a title
     * ({@link FamilyKin#sexInWords}), or a parent's or a child's word
     * that names them in a parent-child claim of the same words ({@link FamilyKin#sexFromQuote}). A claim with no words, or from a family
     * tree's own data, stands.
     */
    private static boolean supports(Graph.Reading r, String person, Graph.Edge claim, String sex) {
        Finding f = r.claims().get(claim.findingId());
        if (f == null) return true;
        String quote = FamilyChecks.quoteOf(f);
        if (quote.isBlank() || f.sources().stream().anyMatch(s -> structured(s.locator()))) return true;
        String written = f.triple() == null ? "" : f.triple().subject();
        List<String> own = ways(r, person, written);
        // the people the same words relate this person to, who are somebody else
        List<String> others = new ArrayList<>();
        for (Graph.Edge e : r.edges()) {
            if (FamilyKin.gone(e) || !FamilyAccount.personToPerson(e.predicate()) || !(e.from().equals(person) || e.to().equals(person)) || e.from().equals(e.to())) continue;
            Finding k = r.claims().get(e.findingId());
            if (k == null || k.triple() == null || !FamilyChecks.quoteOf(k).equals(quote)) continue;
            boolean mine = e.from().equals(person);
            String other = mine ? e.to() : e.from();
            List<String> theirs = ways(r, other, mine ? k.triple().object() : k.triple().subject());
            others.addAll(theirs);
            if (e.predicate().equals("parent-of") || e.predicate().equals("child-of")) {
                List<String> subjectWays = mine ? own : theirs, objectWays = mine ? theirs : own;
                String[] said = FamilyKin.sexFromQuote(e.predicate(), k.triple().subject(), k.triple().object(), quote, subjectWays, objectWays);
                if (said != null && said[0].equals(mine ? k.triple().subject() : k.triple().object()) && said[1].equals(sex)) return true;
            }
        }
        return FamilyKin.sexInWords(quote, own, others, List.of()).equals(sex);
    }

    /** A claim the family accepted: their word stands, whatever the dates or the words say. */
    private static boolean accepted(Graph.Edge e) { return Finding.State.accepted.name().equals(e.state()); }

    /**
     * The claim an edge is the claim's own relation of, or null: a link the link pass worked out from a claim (a brother's parent as a parent,
     * carrying the sibling claim's id) is not the claim's words, and the words rules leave it alone; so is a link from a person to themself.
     */
    private static Finding own(Graph.Reading r, Graph.Edge e) {
        Finding f = r.claims().get(e.findingId());
        if (f == null || f.triple() == null || e.from().equals(e.to()) || !f.triple().predicate().equals(e.predicate())) return null;
        return f;
    }

    // ── the people ────────────────────────────────────────────────────────────────────────────────────────────────

    /** The ways a person is written: as the claim writes them, the entry's name and its other names. */
    private static List<String> ways(Graph.Reading r, String id, String written) {
        List<String> out = new ArrayList<>();
        if (written != null && !written.isBlank()) out.add(written);
        Graph.Node n = r.nodeOf(id);
        if (n != null) { if (!out.contains(n.label())) out.add(n.label()); for (String a : n.aliases()) if (!out.contains(a)) out.add(a); }
        return out;
    }

    private static String label(Graph.Reading r, String id) { Graph.Node n = r.nodeOf(id); return n == null ? id : n.label(); }

    // "as told by Kimie Hale (your note: …)": the teller of a claim's source
    private static final Pattern TOLD = Pattern.compile("^as told by (.+?)(?: \\(your note: .*\\))?$");

    /** Who told a claim's source, the one its "I" is; "" when no source says. */
    static String teller(Finding f) {
        for (Finding.Source s : f.sources()) {
            Matcher m = TOLD.matcher(s.edition() == null ? "" : s.edition().strip());
            if (m.find()) return m.group(1).strip();
        }
        return "";
    }

    /** Whether a source is a family tree's own data: a tree file, a tree site's records. */
    private static boolean structured(String locator) {
        String l = locator == null ? "" : locator.toLowerCase(Locale.ROOT);
        return l.contains("geni.com/api/") || l.endsWith(".ged");
    }
}
