package org.researchzosho.librarian;

import org.researchzosho.librarian.profiles.GenealogyProfile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The family's decisions in one place: two names that may be one person, two claims that disagree, and who is who on the web.
 * The checks and the comparisons are worked out by the library; the decision is the family's, and each answer says what it will
 * do before it is given. "I cannot tell" is an answer too: it asks the research for a record that settles it.
 */
public final class FamilyDecisions {

    private FamilyDecisions() { }

    /** About how many decisions one page shows: the names and the disagreeing claims always, who-is-who questions up to this. */
    public static final int CAP = 10;

    /** The mark on a frontier line that waits for a record the family asked for from the decisions. */
    public static final String RECORD = "needs-record";

    /**
     * Two names that may be one person. {@code fold} is the name that would go into {@code into}: a described parent or a part of a
     * name goes into the full name, else the name with fewer claims. {@code moving}: the claims that would be about {@code into}.
     */
    public record Pair(String code, String fold, String into, String said, List<String> moving, String reason) { }

    /** Two claims about one person that cannot both be right: two birth years, two birthplaces. {@code what}: "birth year", "birthplace". */
    public record Clash(String person, String what, Finding one, Finding other) { }

    public static List<Pair> pairs(LibraryStore store) throws IOException {
        Graph g = FamilyPeople.view(store);
        Map<String, Finding> findings = new LinkedHashMap<>();
        for (Finding f : store.scanFindings().findings()) findings.put(f.id(), f);
        List<Pair> out = new ArrayList<>();
        for (FamilyChecks.Problem p : FamilyChecks.open(store, FamilyChecks.check(store))) {
            if (!p.kind().equals("same-person?") || p.people().size() != 2) continue;
            String a = p.people().get(0), b = p.people().get(1), ia = g.nodeIdOf(a), ib = g.nodeIdOf(b);
            if (g.node(ia) == null || g.node(ib) == null || ia.equals(ib)) continue;
            List<String> ca = Graph.claimsOf(store, g, ia), cb = Graph.claimsOf(store, g, ib);
            boolean foldA = foldsInto(g, findings.values(), a, b);
            String reason = FamilySame.compare(g, findings, ia, ib).reason();
            out.add(foldA ? new Pair(p.id(), a, b, p.text(), ca, reason) : new Pair(p.id(), b, a, p.text(), cb, reason));
        }
        return out;
    }

    /**
     * Whether {@code a} is the name that goes into {@code b} when the two are one person. A stand-in or a part of a name goes into the
     * whole name; a name that tells namesakes apart ("Kenji Endo (born 1928)") is kept, then a name in characters beside one in Latin
     * letters, then one with a dated birth, and a bare name goes into it; else the name with fewer claims that still stand goes into the
     * other. Claims a newer copy of a file replaced, or that were disputed, do not count: they are what an older import left under the bare
     * name. The check's example, the Decisions page and the questions about names all go this way.
     */
    public static boolean foldsInto(Graph g, Collection<Finding> all, String a, String b) {
        if (FamilyQuestions.placeholder(a) || FamilyChecks.partOfName(a, b)) return true;
        if (FamilyQuestions.placeholder(b) || FamilyChecks.partOfName(b, a)) return false;
        boolean ta = toldApart(a), tb = toldApart(b);
        if (ta != tb) return tb;
        // one person written in Latin letters and in characters: the characters decide which family, so the entry in them is kept
        String sa = FamilyForms.script(FamilyNameQuestions.bareName(a)), sb = FamilyForms.script(FamilyNameQuestions.bareName(b));
        if (sa.equals("latin") && sb.equals("han")) return true;
        if (sa.equals("han") && sb.equals("latin")) return false;
        Map<String, FamilyDate> born = Gedcom.birthDates(g, new ArrayList<>(all));
        boolean ba = born.containsKey(g.nodeIdOf(a)), bb = born.containsKey(g.nodeIdOf(b));
        if (ba != bb) return bb;
        return standing(g, all, a) < standing(g, all, b);
    }

    /** A name with something in brackets after it that tells it from a namesake: "(born 1928)", "(I3 in tree.ged)". */
    private static boolean toldApart(String name) { return name.strip().matches("(?s).+[(（][^)）]+[)）]$"); }

    /** How many draft or accepted claims name this person. */
    private static int standing(Graph g, Collection<Finding> all, String name) {
        String id = g.nodeIdOf(name);
        int n = 0;
        // in genealogy's view a claim's side is the person it is linked to ({@link FamilyLinks}), whatever name it writes
        for (Finding f : all) if (f.triple() != null && (f.state() == Finding.State.draft || f.state() == Finding.State.accepted) && (g.nodeOf(f, true).equals(id) || g.nodeOf(f, false).equals(id))) n++;
        return n;
    }

    private static final Set<String> ONE_VALUE = Set.of("born-on", "born-in", "died-on", "died-in", "buried-in");

    /**
     * Two claims about one person, from two sources, that give two dates or two places where a person has one. A birth, a death or a
     * burial with its place carries its year in brackets ("born in Sendai (1870)"): two such claims are compared by the year too, and a
     * born-in and a born-on by their years. Each pair of claims is one question, whatever it is they differ in.
     */
    public static List<Clash> clashes(LibraryStore store) throws IOException {
        Graph g = FamilyPeople.view(store);
        Map<String, List<Finding>> byPerson = new LinkedHashMap<>();
        for (Finding f : store.scanFindings().findings()) {
            if (f.triple() == null || !ONE_VALUE.contains(f.triple().predicate()) || !(f.state() == Finding.State.draft || f.state() == Finding.State.accepted)) continue;
            String id = g.nodeOf(f, true);
            Graph.Node n = g.node(id);
            if (n == null || !n.kind().equals("person")) continue;
            byPerson.computeIfAbsent(id + "\t" + event(f.triple().predicate()), k -> new ArrayList<>()).add(f);
        }
        List<Clash> out = new ArrayList<>();
        for (var e : byPerson.entrySet()) {
            List<Finding> fs = e.getValue();
            String ev = e.getKey().substring(e.getKey().indexOf('\t') + 1);
            for (int i = 0; i < fs.size(); i++) for (int k = i + 1; k < fs.size(); k++) {
                Finding x = fs.get(i), y = fs.get(k);
                if (x.state() == Finding.State.accepted && y.state() == Finding.State.accepted) continue;
                if (source(x).equals(source(y))) continue;   // one source that says two things is a question for the source, not a choice between two
                String px = x.triple().predicate(), py = y.triple().predicate();
                boolean places = px.equals(py) && px.endsWith("-in") && !FamilySame.samePlace(x.triple().object(), y.triple().object());
                FamilyDate dx = dateOf(x), dy = dateOf(y);
                boolean years = dx != null && dy != null && FamilyDate.apart(dx, dy, 0);
                if (!places && !years) continue;
                boolean bothDates = px.endsWith("-on") && py.endsWith("-on");
                String what = switch (ev) {
                    case "born" -> places && years ? "birthplace and birth year" : places ? "birthplace" : bothDates ? "birth date" : "birth year";
                    case "died" -> places && years ? "place and year of death" : places ? "place of death" : bothDates ? "date of death" : "year of death";
                    default -> places && years ? "place and year of burial" : places ? "place of burial" : "year of burial";
                };
                out.add(new Clash(g.node(g.nodeOf(x, true)).label(), what, x, y));
            }
        }
        return out;
    }

    private static String event(String predicate) { return predicate.startsWith("born") ? "born" : predicate.startsWith("died") ? "died" : "buried"; }

    /** The date a claim gives: a born-on's object, or the year a born-in claim carries in brackets after its place. */
    static FamilyDate dateOf(Finding f) {
        return f.triple().predicate().endsWith("-on") ? FamilyDate.parse(f.triple().object()) : FamilyChecks.claimDate(f);
    }

    /** What a button keeps: the place or the date, and the year with the place when the two differ in the year. */
    public static String kept(Clash c, Finding f) {
        FamilyDate d = f.triple().predicate().endsWith("-in") && c.what().contains("year") ? dateOf(f) : null;
        return f.triple().object() + (d == null ? "" : " (" + d.written() + ")");
    }

    static String source(Finding f) { return f.sources().isEmpty() ? "" : f.sources().get(0).locator(); }

    // ── the answers ──────────────────────────────────────────────────────────────────────────────────────────────────

    /** One person: {@code fold} goes into {@code into}, with the reason, and a record question about the two is closed. */
    public static Graph.Merged one(LibraryStore store, Pair p, String by) throws IOException {
        Graph.Merged m = Graph.merge(store, p.fold(), p.into(), by, p.reason().isBlank() ? "the family said they are one person" : p.reason(), new GenealogyProfile());
        closeRecordQuestions(store, List.of(p.fold(), p.into()), by);
        return m;
    }

    /** Two people: written down, and not asked about again. */
    public static void two(LibraryStore store, Pair p, String by) throws IOException {
        Graph.different(store, p.fold(), p.into(), by, "the family said they are two people");
        closeRecordQuestions(store, List.of(p.fold(), p.into()), by);
    }

    /** Keep one of two claims: it is accepted, and the other is disputed with the reason, so both stay on record. */
    public static void keep(LibraryStore store, Clash c, Finding kept, String by) throws IOException {
        Finding other = kept.id().equals(c.one().id()) ? c.other() : c.one();
        Council council = new Council(store);
        if (kept.state() == Finding.State.draft) council.accept(kept.id());
        council.dispute(other.id(), "the family kept " + kept.id() + " (" + kept.title().replaceFirst("[.。]$", "") + ") instead, by " + by);
        closeRecordQuestions(store, List.of(c.person()), by, c.one().id(), c.other().id());
    }

    /** The question a record answers, for "I cannot tell" about two names. The names are in “ ” so the research can find its people. */
    static String recordQuestion(Pair p) {
        return "Which record shows whether “" + p.fold() + "” and “" + p.into() + "” are one person or two? Look for a record that names the parents, the husband or wife, or the birth of each.";
    }

    static String recordQuestion(Clash c) {
        return "Which record settles the " + c.what() + " of “" + c.person() + "”: " + c.one().triple().object() + " (" + c.one().id() + ") or " + c.other().triple().object() + " (" + c.other().id() + ")? Look for the record closest to the event.";
    }

    /**
     * "I cannot tell": a question for a record, waiting in the list of open questions. The next genealogy research for the people it
     * names asks it when {@link #mayGoOut}; otherwise it stays with the family.
     */
    public static String cannotTell(LibraryStore store, String question, String by) throws IOException {
        for (Frontier.Line l : Frontier.read(store)) if (l.open() && l.text().equals(question)) return question;
        store.frontier("person " + by + " " + RECORD + Fields.mark("genealogy"), question);
        Frontier.park(store, question, "the family could not tell from what the library holds; the next genealogy research for the people it names asks for a record");
        return question;
    }

    /**
     * Whether a record question may go to the research, which sends it to search services: everybody it names is in the library, and,
     * when the research leaves out the living ({@code living} false, --skip-living), nobody it names may be living. It follows the
     * owner's choice as every other question does.
     */
    public static boolean mayGoOut(Graph g, List<String> names, boolean living) {
        if (names.isEmpty()) return false;
        for (String n : names) { Graph.Node x = g.node(g.nodeIdOf(n)); if (x == null || (!living && x.mayBeLiving())) return false; }
        return true;
    }

    /** Whether somebody the question names may be living: --skip-living then leaves the question out. */
    public static boolean namesTheLiving(Graph g, List<String> names) {
        for (String n : names) { Graph.Node x = g.node(g.nodeIdOf(n)); if (x != null && x.mayBeLiving()) return true; }
        return false;
    }

    /** The record questions still open that name this person in “ ”. */
    public static List<String> recordAsks(LibraryStore store, String person) throws IOException {
        List<String> out = new ArrayList<>();
        for (Frontier.Line l : Frontier.read(store)) if (l.open() && l.kind().contains(RECORD) && named(l.text()).contains(person)) out.add(l.text());
        return out;
    }

    private static final Pattern QUOTED = Pattern.compile("“([^”]+)”");

    /**
     * For each person, the questions for a record the family asked for with "I cannot tell" that the research may send out: the next
     * {@code genealogy research} asks them for that person, also when the person was searched for before. {@code living}: as the
     * research was asked, with the living or without them.
     */
    public static Map<String, List<String>> recordQuestions(LibraryStore store, boolean living) throws IOException {
        Graph g = FamilyPeople.view(store);
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (Frontier.Line l : Frontier.read(store)) {
            if (!l.open() || !l.kind().contains(RECORD)) continue;
            List<String> names = named(l.text());
            if (!mayGoOut(g, names, living)) continue;
            for (String n : names) out.computeIfAbsent(n, k -> new ArrayList<>()).add(l.text());
        }
        return out;
    }

    static List<String> named(String text) {
        List<String> out = new ArrayList<>();
        Matcher m = QUOTED.matcher(text);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    /** A decision made: the record questions about the same people (and the same claims, when given) are closed, marked by whom. */
    private static void closeRecordQuestions(LibraryStore store, List<String> people, String by, String... claims) throws IOException {
        for (Frontier.Line l : Frontier.read(store)) {
            if (!l.open() || !l.kind().contains(RECORD) || !named(l.text()).containsAll(people)) continue;
            if (claims.length > 0 && !(l.text().contains("(" + claims[0] + ")") && l.text().contains("(" + claims[1] + ")"))) continue;
            if (claims.length == 0 && !l.text().contains("one person or two")) continue;
            Frontier.markExplored(store, l.text(), "(answered by " + by + " on the decisions page)");
        }
    }
}
