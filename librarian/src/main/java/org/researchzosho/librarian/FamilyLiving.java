package org.researchzosho.librarian;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Whether a person may still be living, from dates alone: one rule for a GEDCOM import, a family text and the whole library.
 * A death, a burial or a cremation settles it. Otherwise every dated claim says the person was born no later than the latest
 * year its date allows ("between 1900 and 1930" is 1930; "after 1910" says nothing), and that year is carried to relatives:
 * a parent was born at least {@link FamilyAccount#PARENT_GAP} years before a child, a spouse or a brother or sister within
 * {@link FamilyAccount#SPOUSE_SPAN} years of the other. A date dates the people of the event it is written with: a marriage
 * both spouses, a birth or a job the person, and a relation such as parent, child, brother or cousin nobody, since a year
 * beside it is about something else. A relative's year is never carried to somebody whose own birth is dated: their own date
 * says more. Whoever this puts more than {@link Gedcom#LIVING_YEARS} years back is not living. A claim the family disputes stops
 * counting the next time the rule runs. Arithmetic only; nothing is filed.
 */
public final class FamilyLiving {

    private FamilyLiving() { }

    /** One statement: {@code date} is the words that date it ("" when none). */
    public record Claim(String subject, String relation, String object, String date) { }

    static final Set<String> DEATH = Set.of("died-on", "died-in", "buried-in", "cremated", "buried", "died");
    /** A relation that holds for a whole life: a year written beside it is about something else, and dates neither person. */
    static final Set<String> TIMELESS = Set.of("parent-of", "child-of", "sibling-of", "relative-of");
    /** A person's own birth: a relative's years are not carried to somebody who has one dated. */
    static final Set<String> BIRTH = Set.of("born-on", "born-in", "baptised-on", "baptised-in");

    /**
     * Whether each person the claims name may be living, true when nothing places them in the past. Keys are what {@code lookup}
     * makes of a name (a node id for the library, the name itself for one text). Each name is looked up once: the graph reads this
     * every time it is built, and a name is met again in every round that carries years to relatives.
     */
    public static Map<String, Boolean> decide(List<Claim> claims, Function<String, String> lookup) {
        Map<String, String> keys = new HashMap<>();
        Function<String, String> keyOf = name -> { String k = keys.get(name); if (k == null) { k = lookup.apply(name); keys.put(name, k); } return k; };
        int cutoff = LocalDate.now().getYear() - Gedcom.LIVING_YEARS;
        Set<String> dead = new HashSet<>();
        Map<String, Integer> bornBy = new LinkedHashMap<>();
        Set<String> people = new LinkedHashSet<>();
        Set<String> ownBirth = new HashSet<>();
        for (Claim c : claims) {
            String s = keyOf.apply(c.subject());
            people.add(s);
            boolean kin = FamilyAccount.personToPerson(c.relation());
            String o = kin ? keyOf.apply(c.object()) : null;
            if (o != null) people.add(o);
            if (DEATH.contains(c.relation())) dead.add(s);
            // a dated statement puts the person there by that year: someone married, at work or christened 110 years ago is not living
            Integer y = FamilyDate.latestYear(c.date());
            if (y == null || TIMELESS.contains(c.relation())) continue;
            if (BIRTH.contains(c.relation())) ownBirth.add(s);
            lower(bornBy, s, y);
            if (o != null) lower(bornBy, o, y);
        }
        // carried to relatives until nothing moves; a bounded number of rounds, since a person recorded as their own ancestor never settles
        for (int round = 0; round < 64; round++) {
            boolean moved = false;
            for (Claim c : claims) {
                String rel = c.relation();
                int gap; String elder, younger;
                switch (rel) {
                    case "parent-of" -> { gap = FamilyAccount.PARENT_GAP; elder = c.subject(); younger = c.object(); }
                    case "child-of" -> { gap = FamilyAccount.PARENT_GAP; elder = c.object(); younger = c.subject(); }
                    case "head-of-household" -> { gap = 0; elder = c.subject(); younger = c.object(); }
                    case "adopted-by" -> { gap = 0; elder = c.object(); younger = c.subject(); }
                    case "married-to", "sibling-of" -> { gap = -FamilyAccount.SPOUSE_SPAN; elder = null; younger = null; }
                    default -> { continue; }
                }
                String[][] pairs = elder != null ? new String[][]{{younger, elder}} : new String[][]{{c.subject(), c.object()}, {c.object(), c.subject()}};
                for (String[] pair : pairs) {
                    String from = keyOf.apply(pair[0]), to = keyOf.apply(pair[1]);
                    Integer y = bornBy.get(from);
                    if (y == null || from.equals(to) || ownBirth.contains(to)) continue;
                    if (lower(bornBy, to, y - gap)) moved = true;
                }
            }
            if (!moved) break;
        }
        Map<String, Boolean> out = new LinkedHashMap<>();
        for (String k : people) out.put(k, !dead.contains(k) && !(bornBy.containsKey(k) && bornBy.get(k) <= cutoff));
        return out;
    }

    private static boolean lower(Map<String, Integer> bornBy, String key, int year) {
        Integer held = bornBy.get(key);
        if (held != null && held <= year) return false;
        bornBy.put(key, year);
        return true;
    }

    /**
     * The claims the rule reads from these findings, for the whole library: every claim that is neither retired nor disputed, each
     * relation as the graph reads it. Two claims of one fact that the review disputed against each other still count, by the latest
     * year of the two: the review asked which year, not whether. The graph decides with these, people keyed by node id
     * ({@link Graph.Node#mayBeLiving()}).
     */
    static List<Claim> claims(List<Finding> findings, Graph g) {
        // in genealogy's linked view each claim's people are the ones it is linked to ({@link FamilyLinks}), node ids already: one written
        // name may be two people in two claims, so no name is looked up
        boolean linked = g != null && g.links() != null;
        List<Claim> claims = new ArrayList<>();
        Map<String, Claim> reviewDisputed = new LinkedHashMap<>();   // a person and a fact about them → the latest year of the review's disputed claims
        for (Finding f : findings) {
            if (f.triple() == null || f.state() == Finding.State.retired || f.state() == Finding.State.superseded) continue;
            Claim c = of(f, g);
            if (linked) c = new Claim(g.nodeOf(f, true), c.relation(), FamilyAccount.personToPerson(c.relation()) ? g.nodeOf(f, false) : c.object(), c.date());
            if (f.state() == Finding.State.disputed) {
                if (!disputedByTheReview(f) || FamilyAccount.personToPerson(c.relation()) || FamilyAccount.associate(c.relation())) continue;
                Integer y = FamilyDate.latestYear(c.date());
                String key = eventOf(g, c, linked);
                Claim held = reviewDisputed.get(key);
                Integer heldYear = held == null ? null : FamilyDate.latestYear(held.date());
                if (held == null || (y != null && (heldYear == null || y > heldYear))) reviewDisputed.put(key, c);
                continue;
            }
            claims.add(c);
        }
        // only where no claim of that fact still stands: a year the owner accepted is not outweighed by one the review set aside
        Set<String> standing = new HashSet<>();
        for (Claim c : claims) standing.add(eventOf(g, c, linked));
        for (Map.Entry<String, Claim> e : reviewDisputed.entrySet()) if (!standing.contains(e.getKey())) claims.add(e.getValue());
        return claims;
    }

    /** A person and what a claim says of them: their birth, their death, or the relation. */
    private static String eventOf(Graph g, Claim c, boolean linked) {
        return (linked ? c.subject() : g.nodeIdOf(c.subject())) + "\t" + (DEATH.contains(c.relation()) ? "died" : BIRTH.contains(c.relation()) ? "born" : c.relation());
    }

    /** A claim the review marked disputed because another claim says the fact otherwise, and nobody disputed by hand: the core's reading of who set it aside. */
    static boolean disputedByTheReview(Finding f) {
        return f.state() == Finding.State.disputed && f.setAsideBy() == Finding.SetAside.library && f.notes().stream().anyMatch(Finding::contradiction);
    }

    /**
     * A filed claim as the rule reads it. A date claim's date is its object; any other claim's date is the one its first line ends
     * with in brackets, "(BET 1900 AND 1930)", which is where the family reader and the GEDCOM import put the date of what the claim
     * says ("John Ellis (born 1851)" is a name, and his child is not dated by it). A claim with no such date has none: a year elsewhere
     * in a sentence is often about something else ("a great-granddaughter of Tom Ellis, who emigrated in 1885").
     */
    static Claim of(Finding f) { return of(f, null); }

    /** The same, with the relation as the graph reads it: a research run writes "son of" or "born on", which are child-of and born-on. */
    static Claim of(Finding f, Graph g) {
        Finding.Triple t = f.triple();
        if (g != null) t = new Finding.Triple(t.subject(), g.predicateOf(t.predicate()), t.object());
        // the date in brackets is the family reader's and the tree import's way of writing it: in anybody else's claim, such as a research
        // run's, a bracket at the end of the sentence belongs to whatever it follows ("a great-granddaughter of Tom Ellis (1845–1901)")
        boolean bracketed = "family-account".equals(f.writer()) || "gedcom-import".equals(f.writer());
        String first = f.body().lines().findFirst().orElse("");
        // an age is no name: "aged 18" taken out of the line would take the 18 out of "(1880)" too
        for (String name : t.predicate().equals("aged") ? new String[]{t.subject()} : new String[]{t.subject(), t.object()}) if (name != null && !name.isBlank()) first = first.replace(name, " ");
        // the last brackets, with any brackets inside them: "(明治十九年 (1886))" is a date the reader wrote with its year
        String date = t.predicate().endsWith("-on") ? t.object() : bracketed ? FamilyAccount.lastBracket(first) : "";
        // a cremation, a burial or a death the source gave without a place is filed as a life event: "cremated in Kure", "buried (1901)", "died"
        String relation = t.predicate().equals("life-event") && t.object().startsWith("cremated") ? "cremated"
                : t.predicate().equals("life-event") && t.object().equals("buried") ? "buried"
                : t.predicate().equals("life-event") && t.object().equals("died") ? "died" : t.predicate();
        return new Claim(t.subject(), relation, t.object(), date);
    }
}
