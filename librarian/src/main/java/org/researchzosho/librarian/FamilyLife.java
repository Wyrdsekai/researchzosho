package org.researchzosho.librarian;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.TreeMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import java.io.IOException;
/**
 * A person's life as the library holds it: every claim whose subject is the person, in order of date. A birth, an
 * office, a patent, a move, a death: each line is a claim with its state and where it came from, so what a family
 * account said, what a page said and what a research run found stand in one list.
 */
public final class FamilyLife {

    private FamilyLife() { }

    /**
     * {@code evidence}: what the claim rests on — record, published, or a clue only. {@code predicate}: the claim's relation, for the gaps.
     * {@code later}: how many years after the event its sources wrote it down, when they say ({@link Evidence#writtenLater}); 0 otherwise.
     * {@code as}: the name the person carried at the line's date when it is not their latest name ({@link #carried}); "" otherwise.
     */
    public record Line(Integer year, String text, String state, String findingId, String source, String evidence, String predicate, int later, String as) {
        public Line(Integer year, String text, String state, String findingId, String source, String evidence, String predicate, int later) { this(year, text, state, findingId, source, evidence, predicate, later, ""); }
        public Line(Integer year, String text, String state, String findingId, String source, String evidence, String predicate) { this(year, text, state, findingId, source, evidence, predicate, 0); }
        public Line(Integer year, String text, String state, String findingId, String source) { this(year, text, state, findingId, source, "", ""); }
    }

    /** Years between two dated claims after which the timeline says so: a life with nothing written for that long has a hole in it. */
    static final int GAP_YEARS = 15;

    private static final Set<String> KIN = Set.of("parent-of", "child-of", "sibling-of", "relative-of", "head-of-household");

    /** A relative's events shown inside a life: where the family was while the person's own claims are silent. */
    private static final Set<String> RELATIVES_EVENTS = Set.of("born-on", "born-in", "died-on", "died-in", "married-to");

    /** The predicate of a line that is a relative's event, not the person's: it is shown, but it fills no gap and counts toward nothing. */
    static final String RELATIVE = "relative";

    /** A life with a birth and no death is taken to run this many years, for the relatives' events shown inside it. */
    static final int LIFETIME = 100;

    public static List<Line> of(LibraryStore store, String name) throws IOException {
        Graph g = FamilyPeople.view(store);
        String id = g.nodeIdOf(name);
        if (g.node(id) == null) id = g.nodeIdOf(KanjiForms.modern(name));
        if (g.node(id) == null) return null;
        List<Line> out = new ArrayList<>();
        FamilyNameHistory.Index names = FamilyNameHistory.of(g);
        Map<String, Finding> byId = new LinkedHashMap<>();
        for (Finding f : store.scanFindings().findings()) byId.put(f.id(), f);
        Map<String, Evidence> byFact = FamilyPeople.byFact(g, byId);
        List<Finding> all = new ArrayList<>(byId.values());
        for (Finding f : all) {
            if (f.triple() == null || f.state() == Finding.State.retired || f.state() == Finding.State.superseded) continue;
            // a marriage is the wife's as much as the husband's, whichever of the two the claim was written about
            boolean married = f.triple().predicate().equals("married-to") && id.equals(g.nodeOf(f, false)) && !id.equals(g.nodeOf(f, true));
            if (married) {
                Evidence ev = Evidence.ofFact(f, byFact, FamilyPeople.factKey(g.nodeOf(f, true), "married-to", id));
                Integer year = FamilyChecks.claimYear(f);
                out.add(new Line(year, f.body().lines().findFirst().orElse(f.title()).strip(), f.state().name(), f.id(), f.sources().isEmpty() ? "" : f.sources().get(0).locator(), ev.name(), "married-to", Evidence.writtenLater(f),
                        carried(names, g, id, year, f.triple().object())));
                continue;
            }
            if (!id.equals(g.nodeOf(f, true)) || KIN.contains(f.triple().predicate())) continue;
            String first = f.body().lines().findFirst().orElse(f.title()).strip();
            // the list is already under the person's name: an event reads as what happened, not as "<name> life event …"
            if (first.startsWith(f.triple().subject() + ": ")) first = first.substring(f.triple().subject().length() + 2);
            else if (first.startsWith(f.triple().subject() + " life event ")) first = first.substring((f.triple().subject() + " life event ").length());   // a claim written before facts were sentences
            else if (first.startsWith(f.triple().subject() + " ")) first = first.substring(f.triple().subject().length() + 1);
            String source = f.sources().isEmpty() ? "" : f.sources().get(0).locator();
            FamilyDate written = f.triple().predicate().endsWith("-on") ? FamilyDate.parse(f.triple().object()) : null;
            if (f.triple().predicate().equals("aged")) {
                // an age is a birth year a record gives without saying it: shown worked out, beside the words
                FamilyDate born = FamilyDate.bornFrom(FamilyDate.age(f.triple().object()), FamilyChecks.claimDate(f));
                if (born != null) first = first.replaceFirst("[.。]$", "") + " (so born " + born.phrase() + ").";
            }
            // a line rests on the best that any claim of the same fact gives: the account's birth and the register's birth are one fact
            Evidence ev = Evidence.ofFact(f, byFact, FamilyPeople.factKey(id, g.predicateOf(f.triple().predicate()), g.nodeOf(f, false)));
            Integer year = written != null ? Integer.valueOf(written.year()) : FamilyChecks.claimYear(f);
            // a name claim's line says its name itself
            String as = FamilyNameHistory.isNameClaim(f) ? "" : carried(names, g, id, year, f.triple().subject());
            out.add(new Line(year, first, f.state().name(), f.id(), source, ev.name(), f.triple().predicate(), Evidence.writtenLater(f), as));
        }
        // no birth date held: the years the family's other dates leave for one, marked as worked out and not a claim
        boolean born = out.stream().anyMatch(l -> l.predicate().equals("born-on") || l.predicate().equals("born-in"));
        FamilyBounds.Window w = born ? null : FamilyBounds.of(store).get(id);
        if (w != null && !w.born().isEmpty())
            out.add(new Line(w.bornBefore() != null ? w.bornBefore().year() : w.bornAfter().year(), "born " + w.born(), "worked out", String.join(", ", w.bornFindings()), "", "", "window"));
        out.addAll(relatives(g, all, id, out, w != null && w.bornAfter() != null ? w.bornAfter().year() : null));
        out.sort(Comparator.comparing((Line l) -> l.year() == null ? Integer.MAX_VALUE : l.year()));
        return out;
    }

    /**
     * The name a dated line of a life was carried under, when it is not the person's latest name: the name the person carried in the line's
     * year ({@link FamilyNameHistory.Index#at}); where the names' years do not say, the name the claim was written under when that is one of
     * the person's names. "" for a line with no date, a person with one name, and a line under the latest name. A narrative that writes a
     * later name for an earlier year does not move a name's years: a 1920 line of a man who took his name in 1932 reads "as 遠藤健二".
     */
    static String carried(FamilyNameHistory.Index idx, Graph g, String id, Integer year, String asWritten) {
        if (year == null) return "";
        List<FamilyNameHistory.Name> ns = idx.names(id);
        if (ns.size() < 2) return "";
        FamilyNameHistory.Name latest = idx.latest(id), then = idx.at(id, year);
        String shown;
        if (then != null) shown = then.shown(FamilyForms.script(g.node(id).label()));
        else {
            for (FamilyNameHistory.Name n : ns) if (asWritten != null && !asWritten.isBlank() && n.isForm(asWritten)) { then = n; break; }
            if (then == null) return "";
            shown = asWritten.strip();
        }
        return then.equals(latest) || (latest != null && latest.isForm(shown)) ? "" : shown;
    }

    /**
     * A relative as a line of the life names them: the name they carried in that year when the claim wrote another of their names ("the
     * daughter 山田ハル was born" for a birth written under her married name); as the claim wrote it otherwise.
     */
    static String relativeName(FamilyNameHistory.Index idx, String id, int year, String named) {
        FamilyNameHistory.Name then = idx.at(id, year);
        return then == null || then.isForm(named) ? named : then.shown(FamilyForms.script(named));
    }

    /**
     * The births, deaths and marriages of the person's parents, brothers and sisters, husbands or wives and children that fall
     * inside the person's life: each is a claim the library already holds, marked with who the relative is, under the name they
     * carried then.
     */
    static List<Line> relatives(Graph g, List<Finding> findings, String id, List<Line> own, Integer windowStart) {
        Integer start = windowStart, end = null, first = null, last = null;
        for (Line l : own) {
            if (l.year() == null) continue;
            first = first == null ? l.year() : Math.min(first, l.year());
            last = last == null ? l.year() : Math.max(last, l.year());
            if (l.predicate().startsWith("born-") || l.predicate().equals("window")) start = start == null ? l.year() : Math.min(start, l.year());
            if (l.predicate().startsWith("died-")) end = end == null ? l.year() : Math.max(end, l.year());
        }
        if (start == null) start = first;
        // boxed on both sides: with no dated line of their own, there is no start and no end, and nothing to unbox
        if (end == null) end = start != null && own.stream().anyMatch(l -> l.predicate().startsWith("born-")) ? Integer.valueOf(start + LIFETIME) : last;
        if (start == null || end == null) return List.of();

        // who each relative is to the person: parent, child, brother or sister, husband or wife
        Map<String, String> kin = new LinkedHashMap<>();
        Map<String, List<FamilyKin.Link>> parents = FamilyKin.parents(g);
        Map<String, Map<String, List<String>>> sexes = FamilyKin.sexes(g);
        Set<String> mine = new HashSet<>();
        for (FamilyKin.Link p : parents.getOrDefault(id, List.of())) { kin.put(p.other(), "parent"); mine.add(p.other()); }
        for (var e : parents.entrySet()) for (FamilyKin.Link p : e.getValue()) {
            if (p.other().equals(id)) kin.putIfAbsent(e.getKey(), "child");
            else if (mine.contains(p.other()) && !e.getKey().equals(id)) kin.putIfAbsent(e.getKey(), "sibling");
        }
        for (Graph.Edge e : g.edges()) {
            if (FamilyKin.gone(e)) continue;
            String other = e.from().equals(id) ? e.to() : e.to().equals(id) ? e.from() : null;
            if (other == null || other.equals(id)) continue;
            if (e.predicate().equals("married-to")) kin.putIfAbsent(other, "spouse");
            else if (e.predicate().equals("sibling-of")) kin.putIfAbsent(other, "sibling");
        }
        for (var it = kin.keySet().iterator(); it.hasNext(); ) { Graph.Node n = g.node(it.next()); if (n == null) it.remove(); }
        if (kin.isEmpty()) return List.of();

        Set<String> shown = new HashSet<>();
        for (Line l : own) shown.add(l.findingId());
        FamilyNameHistory.Index names = FamilyNameHistory.of(g);
        // one line for each relative's birth and each death: the claim with the place says more than the one with the date alone
        Map<String, Line> byEvent = new LinkedHashMap<>();
        Map<String, Boolean> hasPlace = new HashMap<>();
        for (Finding f : findings) {
            if (f.triple() == null || f.state() == Finding.State.retired || f.state() == Finding.State.superseded || f.state() == Finding.State.disputed || !RELATIVES_EVENTS.contains(f.triple().predicate()) || shown.contains(f.id())) continue;
            String s = g.nodeOf(f, true), o = g.nodeOf(f, false);
            boolean isMarriage = f.triple().predicate().equals("married-to");
            if (isMarriage && (id.equals(s) || id.equals(o))) continue;
            String who = kin.containsKey(s) ? s : isMarriage && kin.containsKey(o) ? o : null;
            if (who == null) continue;
            FamilyDate written = f.triple().predicate().endsWith("-on") ? FamilyDate.parse(f.triple().object()) : null;
            Integer year = written != null ? Integer.valueOf(written.year()) : FamilyChecks.claimYear(f);
            if (year == null || year < start || year > end) continue;
            String named = who.equals(s) ? f.triple().subject() : f.triple().object();
            String sentence = f.body().lines().findFirst().orElse(f.title()).strip();
            String word = word(kin.get(who), FamilyKin.sexOf(sexes, who));
            String then = relativeName(names, who, year, named);
            int at = sentence.indexOf(named);
            sentence = at < 0 ? "the " + word + " " + then + ": " + sentence : sentence.substring(0, at) + "the " + word + " " + then + sentence.substring(at + named.length());
            if (at == 0) sentence = Character.toUpperCase(sentence.charAt(0)) + sentence.substring(1);
            String key = isMarriage ? f.id() : who + "|" + f.triple().predicate().substring(0, f.triple().predicate().indexOf('-')) + "|" + year;
            boolean place = f.triple().predicate().endsWith("-in");
            if (byEvent.containsKey(key) && (hasPlace.get(key) || !place)) continue;
            byEvent.put(key, new Line(year, sentence, f.state().name(), f.id(), "", "", RELATIVE));
            hasPlace.put(key, place);
        }
        return new ArrayList<>(byEvent.values());
    }

    private static String word(String kin, String sex) {
        boolean m = sex.equals("male"), f = sex.equals("female");
        return switch (kin) {
            case "parent" -> m ? "father" : f ? "mother" : "parent";
            case "child" -> m ? "son" : f ? "daughter" : "child";
            case "sibling" -> m ? "brother" : f ? "sister" : "brother or sister";
            default -> m ? "husband" : f ? "wife" : "husband or wife";
        };
    }

    /** The life under the person's name as given ({@link #render(String, String, List)} with the name as its heading). */
    public static String render(String name, List<Line> lines) { return render(name, name, lines); }

    /**
     * The heading a life is shown under: the person's latest name with the birth name beside it ("森田健二 (born 遠藤)"), keeping the bracket
     * that tells two entries of one name apart, as two lives side by side need ({@link FamilyNamePages#named}); the name as given for nobody
     * the library holds.
     */
    public static String heading(LibraryStore store, String name) throws IOException {
        Graph g = FamilyPeople.view(store);
        String id = g.nodeIdOf(name);
        if (g.node(id) == null) id = g.nodeIdOf(KanjiForms.modern(name));
        return g.node(id) == null ? name : FamilyNamePages.named(FamilyNameHistory.of(g), g, id);
    }

    /**
     * A life as the terminal shows it, under {@code heading}. {@code name}: the person's label, which the command it suggests takes. A dated
     * line carried under another name than the latest says so first: "as 遠藤健二: went to the village school".
     */
    public static String render(String name, String heading, List<Line> lines) {
        if (lines.isEmpty()) return "The library holds nothing of " + name + "'s life yet. `researchzosho genealogy research " + FamilyNamePages.shellQuoted(name) + "` looks for it.\n";
        StringBuilder b = new StringBuilder(heading).append("\n");
        boolean undated = false;
        Integer last = null, gapShown = null;
        List<Integer> ownYears = lines.stream().filter(l -> l.year() != null && !l.predicate().equals(RELATIVE)).map(Line::year).toList();
        for (Line l : lines) {
            if (l.year() == null && !undated) { b.append("  without a date:\n"); undated = true; }
            // a hole in the life: nothing written for many years between two of the person's own dated claims. A relative's
            // event inside the hole is shown under it, as a sign of where the family was
            if (l.year() != null && last != null && !last.equals(gapShown)) {
                final Integer from = last;
                Integer next = ownYears.stream().filter(y -> y > from).findFirst().orElse(null);
                if (next != null && next - last > GAP_YEARS && l.year() > last) {
                    boolean anchors = lines.stream().anyMatch(x -> x.predicate().equals(RELATIVE) && x.year() != null && x.year() > from && x.year() < next);
                    b.append("        — nothing between ").append(last).append(" and ").append(next).append(" (").append(next - last).append(" years)")
                     .append(anchors ? ", except the relatives' events below" : "").append("\n");
                    gapShown = last;
                }
            }
            if (l.year() != null && !l.predicate().equals(RELATIVE)) last = l.year();
            if (l.predicate().equals(RELATIVE)) {
                b.append("  ").append(l.year()).append("  ").append(l.text()).append("  [").append(l.state()).append(", ").append(l.findingId().replaceFirst("^(F-\\d+).*", "$1")).append(", a relative's event]\n");
                continue;
            }
            if (l.predicate().equals("window")) {
                b.append("  ").append(l.year()).append("  ").append(l.text()).append("  [worked out from ").append(String.join(", ", List.of(l.findingId().split(",\\s*")).stream().map(x -> x.replaceFirst("^(F-\\d+).*", "$1")).toList())).append(", not a claim]\n");
                continue;
            }
            b.append("  ").append(l.year() == null ? "      " : l.year() + "  ").append(as(l)).append(l.text()).append("  [").append(l.state()).append(", ").append(l.findingId().replaceFirst("^(F-\\d+).*", "$1")).append("]")
             .append(l.evidence().isEmpty() ? "" : "  " + mark(l)).append(l.later() > 0 ? "  (written down " + l.later() + " years after it happened)" : "").append(l.source().isBlank() ? "" : "  " + Acquisitions.compress(l.source(), 70)).append("\n");
        }
        List<String> missing = missing(lines);
        if (!missing.isEmpty()) b.append("  Nothing is written about: ").append(String.join(", ", missing)).append(".\n");
        long clues = lines.stream().filter(l -> l.evidence().equals("clue")).count(), own = lines.stream().filter(l -> !l.predicate().equals(RELATIVE)).count();
        if (clues > 0) b.append("  ").append(clues == own ? "Everything here rests on clues only" : clues + (clues == 1 ? " line rests" : " lines rest") + " on a clue only").append(": a family account, somebody else's tree, or a web page, not yet seen in a record. A record that agrees would make it a fact.\n");
        return b.toString();
    }

    /** "as 遠藤健二: " before a line carried under another name than the latest; "" otherwise. */
    static String as(Line l) { return l.as() == null || l.as().isEmpty() ? "" : "as " + l.as() + ": "; }

    /**
     * The end of a life: the person's names, each with how it came, its years, its sources and the other ways it is written (a form only a
     * tree site gives is a clue, like any other fact from there), and the families they belonged to, with the commands that open each.
     * Empty when the person has one name, written one way, and no family ({@link FamilyNamePages#lifeBlock}).
     */
    public static String otherNames(LibraryStore store, String name) throws IOException { return FamilyNamePages.lifeBlock(store, name); }

    /** "a record", "published", "a clue only": what the line rests on, for the reader. */
    static String mark(Line l) { return "(" + Evidence.valueOf(l.evidence()).word() + ")"; }

    /** The parts of a life the claims say nothing about: birth, marriage, work, death. Death is named only when a death is already in the list without a date. */
    static List<String> missing(List<Line> lines) {
        Set<String> have = new HashSet<>();
        for (Line l : lines) have.add(l.predicate());
        List<String> out = new ArrayList<>();
        if (!have.contains("born-on") && !have.contains("born-in")) out.add("their birth");
        if (!have.contains("married-to")) out.add("a marriage");
        if (!have.contains("occupation") && !have.contains("worked-at") && !have.contains("office") && !have.contains("life-event")) out.add("their work");
        // a death filed with its place carries its year in the claim, as a tree file's DEAT with a DATE and a PLAC is filed
        boolean datedDeath = lines.stream().anyMatch(l -> l.predicate().equals("died-on") || (l.predicate().equals("died-in") && l.year() != null));
        if (have.contains("died-in") && !datedDeath) out.add("the date of their death");
        return out;
    }

    /**
     * Two people side by side, year by year: the way a genealogist tells two people of one name apart, or sees that a claim
     * filed under one belongs to the other. A year both have something for is one row; a year with a birth or a death on both
     * sides is the row to look at. {@code a} and {@code b} head the columns (the two headings); a line carried under another name
     * than the latest says so, as in the life.
     */
    public static String beside(String a, List<Line> la, String b, List<Line> lb) {
        int w = 44;
        StringBuilder s = new StringBuilder(String.format("%-6s  %-" + w + "s  %s%n", "", cut(a, w), cut(b, w)));
        s.append(String.format("%-6s  %-" + w + "s  %s%n", "", "-".repeat(Math.min(w, a.length())), "-".repeat(Math.min(w, b.length()))));
        TreeMap<Integer, List<String>[]> rows = new TreeMap<>();
        List<String>[] undated = new List[]{new ArrayList<>(), new ArrayList<>()};
        for (int side = 0; side < 2; side++) {
            for (Line l : side == 0 ? la : lb) {
                if (l.predicate().equals(RELATIVE)) continue;
                if (l.year() == null) { undated[side].add(as(l) + l.text()); continue; }
                rows.computeIfAbsent(l.year(), y -> new List[]{new ArrayList<>(), new ArrayList<>()})[side].add(as(l) + l.text() + (l.evidence().equals("clue") ? " (clue)" : ""));
            }
        }
        for (var e : rows.entrySet()) {
            List<String> left = e.getValue()[0], right = e.getValue()[1];
            for (int i = 0; i < Math.max(left.size(), right.size()); i++)
                s.append(String.format("%-6s  %-" + w + "s  %s%n", i == 0 ? e.getKey() : "", cut(i < left.size() ? left.get(i) : "", w), cut(i < right.size() ? right.get(i) : "", w)));
        }
        for (int i = 0; i < Math.max(undated[0].size(), undated[1].size()); i++)
            s.append(String.format("%-6s  %-" + w + "s  %s%n", i == 0 ? "?" : "", cut(i < undated[0].size() ? undated[0].get(i) : "", w), cut(i < undated[1].size() ? undated[1].get(i) : "", w)));
        return s.toString();
    }

    private static String cut(String t, int w) { String x = t == null ? "" : t.replaceAll("\\s+", " ").strip(); return x.length() <= w ? x : x.substring(0, w - 1) + "…"; }
}
