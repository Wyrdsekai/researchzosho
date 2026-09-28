package org.researchzosho.librarian;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.Locale;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
/**
 * What cannot be true in a family, found by arithmetic over the claims: a child born before its parent could have one,
 * a third birth parent, a death before a birth, a person who is their own ancestor; and the names that may be one
 * person written two ways, or two people who share a name. Impossible combinations are thrown out by dates first and
 * names compared after, the order record-linkage work settled on. Nothing is changed: each line names the claims to look at.
 */
public final class FamilyChecks {

    private FamilyChecks() { }

    static final int YOUNGEST_PARENT = 12, OLDEST_MOTHER = 55, OLDEST_PARENT = 80, LONGEST_LIFE = 110;

    /** Years over which one parent's children were born, and the longest wait between two of them, after which the check asks for a second look. */
    static final int CHILDREN_SPAN = 35, CHILDREN_GAP = 20;

    /**
     * kind: impossible (the claims cannot all be true), unlikely (look again), same-person? (two nodes that may be one), same-name (one name,
     * two people), read-two-ways (one written name, two readings in the sources), unreadable (a date with no year the library can read),
     * note (nothing wrong, easy to misread), set aside (a claim the family's view leaves out, {@link FamilyDoubts}).
     */
    /** {@code people}: for a same-person? line, the two names it is about, so a page can offer the answers; empty otherwise. */
    public record Problem(String kind, String text, List<String> findings, List<String> people) {
        public Problem(String kind, String text, List<String> findings) { this(kind, text, findings, List.of()); }

        /**
         * A short code for the problem, from its kind, its claims and the years in it: the same problem has the same code on every
         * run, and a problem whose claims or years change is a new one. A problem that names no claim is known by its words.
         */
        public String id() {
            List<String> ids = findings.stream().filter(x -> x != null && !x.isBlank()).distinct().sorted().toList();
            List<String> years = new ArrayList<>();
            Matcher m = Pattern.compile("(?<!\\d)\\d{3,4}(?!\\d)").matcher(text);
            while (m.find()) years.add(m.group());
            String key = kind + "|" + String.join(",", ids) + "|" + String.join(",", years) + (ids.isEmpty() ? "|" + text : "");
            try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8))).substring(0, 6); }
            catch (Exception e) { throw new IllegalStateException(e); }
        }
    }

    /** A problem somebody looked at and said is right as it stands: when, by whom, and why. */
    public record Accepted(String id, String by, String date, String why, String text) { }

    private record Life(String id, String label, FamilyDate born, FamilyDate died, String bornClaim, String diedClaim) { }

    /** A dated thing in a person's life besides the birth and the death: [predicate, the claim's sentence, claim id] with its date. */
    private record Event(String predicate, String sentence, String claim, FamilyDate when) { }

    public static List<Problem> check(LibraryStore store) throws IOException {
        Graph g = FamilyPeople.view(store);
        Set<String> apart = Graph.differentPairs(store);
        Map<String, Map<String, List<String>>> sexes = FamilyKin.sexes(g);
        Map<String, Finding> findings = new LinkedHashMap<>();
        for (Finding f : store.scanFindings().findings()) findings.put(f.id(), f);
        Map<String, Life> lives = new LinkedHashMap<>();
        Map<String, List<String[]>> birthParents = new LinkedHashMap<>();   // child → [parent, claim]
        List<String[]> marriages = new ArrayList<>();                      // [a, b, claim]
        List<String[]> present = new ArrayList<>();                        // [informant, witness or godparent; the person; claim; relation]
        Map<String, List<Event>> events = new LinkedHashMap<>();
        Map<String, List<Event>> ages = new LinkedHashMap<>();             // the birth years an age in a record gives, as events
        List<Problem> out = new ArrayList<>();
        for (Graph.Edge e : g.edges()) {
            if (FamilyKin.gone(e)) continue;   // a disputed claim no longer counts, as the advice below says
            Graph.Node fromNode = g.node(e.from()), toNode = g.node(e.to());
            if ((fromNode != null && FamilyQuestions.unknown(fromNode.label())) || (toNode != null && FamilyQuestions.unknown(toNode.label()))) continue;   // "father unknown" is no third parent
            switch (e.predicate()) {
                case "born-in", "born-on", "died-in", "died-on" -> {
                    Finding f = findings.get(e.findingId());
                    Graph.Node when = g.node(e.to());
                    Graph.Node n = g.node(e.from());
                    boolean birth = e.predicate().startsWith("born");
                    FamilyDate y = e.predicate().endsWith("-on") ? FamilyDate.parse(when == null ? "" : when.label()) : f == null ? null : claimDate(f);
                    if (y == null) {
                        if (e.predicate().endsWith("-on") && when != null && !when.label().isBlank())
                            out.add(new Problem("unreadable", (n == null ? e.from() : n.label()) + "'s " + (birth ? "birth" : "death") + " is dated \"" + when.label() + "\", and the library finds no year in it, so it was not compared with the other dates.", List.of(e.findingId())));
                        break;
                    }
                    Life old = lives.getOrDefault(e.from(), new Life(e.from(), n == null ? e.from() : n.label(), null, null, "", ""));
                    FamilyDate had = birth ? old.born() : old.died();
                    if (birth && had != null && FamilyDate.apart(had, y, 1)) out.add(new Problem("unlikely", old.label() + " has two birth years, " + had.phrase() + " and " + y.phrase() + ".", List.of(old.bornClaim(), e.findingId())));
                    // of two dates that agree, the narrower one says more: "about 1850" and "1852" are 1852
                    boolean take = had == null || (!FamilyDate.apart(had, y, 0) && y.latest() - y.earliest() < had.latest() - had.earliest());
                    if (!take) break;
                    lives.put(e.from(), birth ? new Life(old.id(), old.label(), y, old.died(), e.findingId(), old.diedClaim())
                            : new Life(old.id(), old.label(), old.born(), y, old.bornClaim(), e.findingId()));
                }
                case "parent-of" -> birthParents.computeIfAbsent(e.to(), k -> new ArrayList<>()).add(new String[]{e.from(), e.findingId()});
                case "child-of" -> birthParents.computeIfAbsent(e.from(), k -> new ArrayList<>()).add(new String[]{e.to(), e.findingId()});
                case "married-to" -> marriages.add(new String[]{e.from(), e.to(), e.findingId()});
                case "informant-for", "witness-for", "godparent-of" -> present.add(new String[]{e.from(), e.to(), e.findingId(), e.predicate()});
                case "lived-in", "migrated-to", "occupation", "life-event", "aged" -> {
                    Finding f = findings.get(e.findingId());
                    FamilyDate when = f == null ? null : claimDate(f);
                    Graph.Node n = g.node(e.from()), to = g.node(e.to());
                    if (when == null || n == null || to == null) break;
                    if (e.predicate().equals("aged")) {
                        FamilyDate born = FamilyDate.bornFrom(FamilyDate.age(to.label()), when);
                        if (born != null) ages.computeIfAbsent(e.from(), k -> new ArrayList<>()).add(new Event("aged", n.label() + " was aged " + to.label() + " " + when.in(), e.findingId(), born));
                    } else events.computeIfAbsent(e.from(), k -> new ArrayList<>()).add(new Event(e.predicate(), FamilyAccount.sentence(n.label(), e.predicate(), to.label()), e.findingId(), when));
                }
                default -> { }
            }
        }
        Function<String, String> name = id -> { Graph.Node n = g.node(id); return n == null ? id : n.label(); };

        for (Life l : lives.values()) {
            if (l.born() == null || l.died() == null) continue;
            int s = breaks(l.died(), l.born(), 0);
            if (s > 0) out.add(new Problem(s == 2 ? "impossible" : "unlikely", l.label() + " died " + l.died().in() + ", before the birth " + l.born().in() + ".", List.of(l.bornClaim(), l.diedClaim())));
            if (breaks(l.born(), l.died(), -LONGEST_LIFE) > 0) out.add(new Problem("unlikely", l.label() + " would have lived " + years(l.died(), l.born(), false) + " years (" + l.born().phrase() + " to " + l.died().phrase() + ").", List.of(l.bornClaim(), l.diedClaim())));
        }
        for (var e : birthParents.entrySet()) {
            Set<String> distinct = new LinkedHashSet<>();
            for (String[] p : e.getValue()) distinct.add(p[0]);
            // a person the account describes without a name ("Kimie Hale's father"), or a parent written with a part of another parent's
            // name only (栄子 beside 森田栄子), may be one of the named parents: that is a question, not a third parent
            List<String> described = distinct.stream().filter(p -> FamilyQuestions.placeholder(name.apply(p))).toList();
            List<String> named = new ArrayList<>(distinct.stream().filter(p -> !described.contains(p)).toList());
            List<String[]> parts = new ArrayList<>();
            for (String x : named) for (String y : named) if (!x.equals(y) && partOfName(name.apply(x), name.apply(y)) && !apart.contains(Graph.pair(x, y))) parts.add(new String[]{x, y});
            parts.forEach(pr -> named.remove(pr[0]));
            Function<String, List<String>> claimsOf = who -> e.getValue().stream().filter(p -> p[0].equals(who)).map(p -> p[1]).toList();
            if (named.size() > 2) out.add(new Problem("impossible", name.apply(e.getKey()) + " has " + named.size() + " birth parents: " + String.join(", ", named.stream().map(name).toList()) + ". One of them is a step-parent, an adoptive parent, or another person of the same name.", e.getValue().stream().map(p -> p[1]).toList()));
            for (String[] pr : parts) {
                List<String> ids = new ArrayList<>(claimsOf.apply(pr[0])); ids.addAll(claimsOf.apply(pr[1]));
                out.add(new Problem("same-person?", name.apply(pr[0]) + " and " + name.apply(pr[1]) + " are both written as a parent of " + name.apply(e.getKey()) + ", and " + name.apply(pr[0]) + " is only a part of the name " + name.apply(pr[1]) + ", so they may be one person." + joinOrKeep(name.apply(pr[0]), name.apply(pr[1]), ""), ids, List.of(name.apply(pr[0]), name.apply(pr[1]))));
            }
            if (named.size() <= 2 && named.size() + described.size() > 2) for (String d : described) {
                String want = Pattern.compile("(?i)father|\\bdad\\b|父").matcher(name.apply(d)).find() ? "male" : Pattern.compile("(?i)mother|\\bmum\\b|\\bmom\\b|母").matcher(name.apply(d)).find() ? "female" : "";
                List<String> may = named.stream().filter(n -> want.isEmpty() || FamilyKin.sexOf(sexes, n).isEmpty() || FamilyKin.sexOf(sexes, n).equals(want)).filter(n -> !apart.contains(Graph.pair(n, d))).toList();
                if (may.isEmpty()) continue;
                List<String> ids = new ArrayList<>(claimsOf.apply(d)); for (String n : may) ids.addAll(claimsOf.apply(n));
                out.add(new Problem("same-person?", name.apply(d) + " is a person an account describes without giving a name, and is written as a parent of " + name.apply(e.getKey()) + " beside "
                        + String.join(" and ", named.stream().map(name).toList()) + ". " + name.apply(d) + " may be the same person as " + String.join(" or as ", may.stream().map(name).toList()) + "."
                        + joinOrKeep(name.apply(d), name.apply(may.get(0)), ""), ids, may.size() == 1 ? List.of(name.apply(d), name.apply(may.get(0))) : List.of()));
            }
            // two fathers or two mothers among the birth parents cannot be
            for (String sex : List.of("male", "female")) {
                List<String> same = named.stream().filter(n -> FamilyKin.sexOf(sexes, n).equals(sex)).toList();
                if (same.size() < 2) continue;
                List<String> ids = new ArrayList<>(); for (String n : same) { ids.addAll(claimsOf.apply(n)); ids.addAll(sexes.get(n).get(sex)); }
                out.add(new Problem("impossible", name.apply(e.getKey()) + " has two birth " + (sex.equals("male") ? "fathers" : "mothers") + ": " + String.join(" and ", same.stream().map(name).toList()) + ". One of them is a step-parent, an adoptive parent, or another person of the same name, or a claim records the wrong sex.", ids));
            }
            Life child = lives.get(e.getKey());
            for (String[] p : e.getValue()) {
                Life parent = lives.get(p[0]);
                if (child == null || parent == null || child.born() == null) continue;
                FamilyDate cb = child.born(), pb = parent.born(), pd = parent.died();
                boolean mother = FamilyKin.sexOf(sexes, p[0]).equals("female");
                int young = pb == null ? 0 : breaks(cb, pb, YOUNGEST_PARENT), old = pb == null || young > 0 ? 0 : breaks(pb, cb, -(mother ? OLDEST_MOTHER : OLDEST_PARENT));
                if (young > 0) out.add(new Problem(young == 2 ? "impossible" : "unlikely", withBorn(name.apply(p[0]), pb) + " was " + years(cb, pb, true) + " when " + child.label() + " was born " + cb.in() + ".", List.of(p[1], parent.bornClaim(), child.bornClaim())));
                else if (old > 0) out.add(new Problem("unlikely", withBorn(name.apply(p[0]), pb) + " was " + years(cb, pb, false) + " when " + child.label() + " was born " + cb.in() + "."
                        + (mother ? " A mother of that age is rare: the mother may be another woman of the same name, or a generation may be missing." : " A generation may be missing."), List.of(p[1], parent.bornClaim(), child.bornClaim())));
                // a mother is alive at the birth; a father may have died up to a year before it, and so may a parent whose sex no claim records
                int after = pd == null ? 0 : breaks(pd, cb, mother ? 0 : -1);
                if (after > 0) out.add(new Problem(after == 2 ? "impossible" : "unlikely", name.apply(p[0]) + " died " + pd.in() + (mother ? ", before " : ", more than a year before ") + child.label() + " was born " + cb.in() + "."
                        , List.of(p[1], parent.diedClaim(), child.bornClaim())));
            }
        }
        // what the family's view set aside: a parent and a child the dates or the words make the wrong way round, a sex the words do not say
        for (Map.Entry<String, String> a : FamilyDoubts.setAside(g).entrySet()) {
            Finding f = findings.get(a.getKey());
            String said = f == null ? a.getKey() : f.body().lines().findFirst().orElse(f.title()).strip().replaceFirst("[.。]$", "");
            out.add(new Problem("set aside", "\"" + said + "\" is set aside in the family's view: " + a.getValue() + ".", List.of(a.getKey())));
        }
        // one person recorded as a man and as a woman: two people of one name taken for one, or a relation read the wrong way round
        for (var e : sexes.entrySet()) if (e.getValue().size() > 1) {
            List<String> ids = new ArrayList<>(); e.getValue().values().forEach(ids::addAll);
            out.add(new Problem("unlikely", name.apply(e.getKey()) + " is recorded as a man in " + String.join(", ", e.getValue().get("male")) + " and as a woman in " + String.join(", ", e.getValue().get("female"))
                    + ". Two people of one name may have been taken for one, or a parent and a child were read the wrong way round.", ids));
        }
        // a person who is their own ancestor
        for (String start : birthParents.keySet()) {
            Set<String> seen = new LinkedHashSet<>(); ArrayDeque<String> todo = new ArrayDeque<>(List.of(start));
            while (!todo.isEmpty()) {
                for (String[] p : birthParents.getOrDefault(todo.poll(), List.of())) {
                    if (p[0].equals(start)) { out.add(new Problem("impossible", name.apply(start) + " comes out as their own ancestor. Two people of one name have been taken for one.", List.of(p[1]))); todo.clear(); break; }
                    if (seen.add(p[0])) todo.add(p[0]);
                }
            }
        }
        Map<String, String[]> married = new LinkedHashMap<>();   // "a\tb" → [claim] of a dated marriage, either way round
        for (String[] m : marriages) {
            Finding f = findings.get(m[2]);
            FamilyDate y = f == null ? null : claimDate(f);
            if (y == null) continue;
            married.putIfAbsent(m[0] + "\t" + m[1], new String[]{m[2]}); married.putIfAbsent(m[1] + "\t" + m[0], new String[]{m[2]});
            for (String who : new String[]{m[0], m[1]}) {
                Life l = lives.get(who);
                if (l == null) continue;
                int young = l.born() == null ? 0 : breaks(y, l.born(), YOUNGEST_PARENT);
                if (young > 0) out.add(new Problem(young == 2 ? "impossible" : "unlikely", withBorn(l.label(), l.born()) + " was " + years(y, l.born(), true) + " at the marriage " + y.in() + ".", List.of(m[2], l.bornClaim())));
                int dead = l.died() == null ? 0 : breaks(l.died(), y, 0);
                if (dead > 0) out.add(new Problem(dead == 2 ? "impossible" : "unlikely", l.label() + " died " + l.died().in() + ", before the marriage " + y.in() + ".", List.of(m[2], l.diedClaim())));
            }
        }
        // somebody named in a record as its informant, a witness or a godparent was alive to be there
        for (String[] a : present) {
            Finding f = findings.get(a[2]); Life l = lives.get(a[0]);
            FamilyDate y = f == null ? null : claimDate(f);
            int dead = l == null || y == null || l.died() == null ? 0 : breaks(l.died(), y, 0);
            if (dead == 0) continue;
            String role = switch (a[3]) { case "informant-for" -> "giving the details for a record about "; case "witness-for" -> "being a witness at an event of "; default -> "being a godparent of "; };
            out.add(new Problem(dead == 2 ? "impossible" : "unlikely", l.label() + " died " + l.died().in() + ", before " + role + name.apply(a[1]) + " " + y.in() + ". The record may name another person of the same name.", List.of(a[2], l.diedClaim())));
        }
        // a name in characters with two readings among its other names: which one a search uses decides what it finds
        for (Graph.Node n : g.nodes()) {
            if (!n.kind().equals("person") || FamilyQuestions.placeholder(n.label()) || n.label().codePoints().noneMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN)) continue;
            // readings of two names of one life (えんどう けんじ for 遠藤健二, もりた けんじ for 森田健二) are no name read two ways: each reading
            // counts with the name it is a form of, and a reading no name claims with the name the entry is filed under
            List<FamilyNameHistory.Name> names = FamilyNameHistory.of(g).names(n.id());
            Map<String, List<String>> byName = new LinkedHashMap<>();
            for (String a : n.aliases()) {
                if (!a.strip().codePoints().allMatch(c -> (c >= 0x3040 && c <= 0x30ff) || Character.isWhitespace(c) || c == '・' || c == '　')) continue;
                String of = n.label();
                for (FamilyNameHistory.Name x : names) if (!x.implicit() && x.texts().contains(a.strip())) { of = x.written(); break; }
                List<String> kana = byName.computeIfAbsent(of, k -> new ArrayList<>());
                if (kana.stream().allMatch(k -> !FamilyAccount.sameReading(k, a))) kana.add(a.strip());
            }
            for (Map.Entry<String, List<String>> e : byName.entrySet()) {
                List<String> kana = e.getValue();
                if (kana.size() > 1 && !FamilyNameQuestions.readingSettled(g, n.id(), kana))
                    out.add(new Problem("read-two-ways", (e.getKey().equals(n.label()) ? n.label() : "The name " + e.getKey() + " of " + n.label()) + " is read " + kana.size() + " ways in your sources: " + String.join(", ", kana) + ".", List.of()));
            }
        }
        // every other dated thing in a life against its birth and its death. A move or a home after the death cannot be; an office, an
        // honour or a work can be dated after it (a posthumous rank, a book published later), so that is only a second look
        for (var e : events.entrySet()) {
            Life l = lives.get(e.getKey());
            if (l == null) continue;
            for (Event ev : e.getValue()) {
                int before = l.born() == null ? 0 : breaks(ev.when(), l.born(), 0);
                if (before > 0) out.add(new Problem(before == 2 ? "impossible" : "unlikely", "\"" + ev.sentence() + "\" is dated " + ev.when().phrase() + ", before " + l.label() + "'s birth " + l.born().in() + ".", List.of(ev.claim(), l.bornClaim())));
                boolean moved = ev.predicate().equals("lived-in") || ev.predicate().equals("migrated-to");
                // a cremation, a funeral or a will proved comes after the death by its nature
                int after = l.died() == null || followsDeath(ev) ? 0 : breaks(l.died(), ev.when(), moved ? -1 : 0);
                if (after > 0) out.add(new Problem(moved && after == 2 ? "impossible" : "unlikely", "\"" + ev.sentence() + "\" is dated " + ev.when().phrase() + ", after " + l.label() + "'s death " + l.died().in() + "."
                        + (moved ? "" : " An honour or a work can be dated after a death; otherwise the claim is about somebody else of the same name."), List.of(ev.claim(), l.diedClaim())));
            }
        }
        // an age a record gives against the birth the library holds: the age may be rounded, or the record about a namesake, so a second look
        for (var e : ages.entrySet()) {
            Life l = lives.get(e.getKey());
            if (l == null || l.born() == null) continue;
            for (Event a : e.getValue()) if (FamilyDate.apart(a.when(), l.born(), 0))
                out.add(new Problem("unlikely", a.sentence() + ", so born " + a.when().phrase() + ", but the birth is dated " + l.born().phrase() + ".", List.of(a.claim(), l.bornClaim())));
        }
        // one parent's children: born over too many years, or with a long wait between two, is often two people of one name taken for one
        Map<String, List<String[]>> childrenOf = new LinkedHashMap<>();   // parent → [child, claim]
        for (var e : birthParents.entrySet()) for (String[] p : e.getValue()) childrenOf.computeIfAbsent(p[0], k -> new ArrayList<>()).add(new String[]{e.getKey(), p[1]});
        for (var e : childrenOf.entrySet()) {
            List<String[]> dated = new ArrayList<>(e.getValue().stream().filter(c -> lives.get(c[0]) != null && lives.get(c[0]).born() != null && lives.get(c[0]).born().centre() != null).distinct().toList());
            if (dated.size() < 2) continue;
            dated.sort(Comparator.comparingInt(c -> lives.get(c[0]).born().centre()));
            Life first = lives.get(dated.get(0)[0]), last = lives.get(dated.get(dated.size() - 1)[0]);
            int span = last.born().centre() - first.born().centre();
            String split = " If two people of one name were taken for one, give the second a name of their own with researchzosho genealogy split.";
            if (span > CHILDREN_SPAN) { out.add(new Problem("unlikely", name.apply(e.getKey()) + "'s children were born over " + span + " years, from " + first.label() + " (" + first.born().phrase() + ") to " + last.label() + " (" + last.born().phrase() + ")." + split, List.of(dated.get(0)[1], dated.get(dated.size() - 1)[1], first.bornClaim(), last.bornClaim()))); continue; }
            for (int i = 1; i < dated.size(); i++) {
                Life a = lives.get(dated.get(i - 1)[0]), b = lives.get(dated.get(i)[0]);
                int gap = b.born().centre() - a.born().centre();
                if (gap > CHILDREN_GAP) { out.add(new Problem("unlikely", name.apply(e.getKey()) + " had no child between " + a.label() + " (" + a.born().phrase() + ") and " + b.label() + " (" + b.born().phrase() + "), " + gap + " years later." + split, List.of(dated.get(i - 1)[1], dated.get(i)[1], a.bornClaim(), b.bornClaim()))); break; }
            }
        }
        // a child born before the parents' marriage is dated: common where a marriage was registered years after the wedding
        for (var e : birthParents.entrySet()) {
            Life child = lives.get(e.getKey());
            if (child == null || child.born() == null || e.getValue().size() < 2) continue;
            String[] m = married.get(e.getValue().get(0)[0] + "\t" + e.getValue().get(1)[0]);
            FamilyDate when = m == null || findings.get(m[0]) == null ? null : claimDate(findings.get(m[0]));
            if (when != null && child.born().latest() < when.earliest())
                out.add(new Problem("note", child.label() + " was born " + child.born().in() + ", before the marriage of " + name.apply(e.getValue().get(0)[0]) + " and " + name.apply(e.getValue().get(1)[0]) + " " + when.in()
                        + ". That is common where a marriage was registered years after the wedding; it can also mean the date is of a second marriage.", List.of(m[0], child.bornClaim())));
        }
        // the years the rest of the family puts a birth in, for somebody with no birth date of their own: an empty window cannot be
        for (var w : FamilyBounds.of(g, findings).entrySet()) {
            Life l = lives.get(w.getKey());
            FamilyBounds.Window win = w.getValue();
            if ((l != null && l.born() != null) || win.bornAfter() == null || win.bornBefore() == null || win.bornAfter().year() <= win.bornBefore().year()) continue;
            List<String> ids = new ArrayList<>(win.bornAfter().findings()); ids.addAll(win.bornBefore().findings());
            out.add(new Problem("impossible", name.apply(w.getKey()) + " has no birth date, but the family's dates put the birth in " + win.bornAfter().year() + " or after (" + win.bornAfter().because() + ") and in " + win.bornBefore().year() + " or before (" + win.bornBefore().because() + "). Two people of one name have probably been taken for one.", ids));
        }
        // a relation the family wrote in words (a grandson, a cousin, a brother) against the one the parent claims give. No line
        // between the two in the tree is a tree not filled in yet, and says nothing
        Map<String, List<FamilyKin.Link>> parentsOf = FamilyKin.parents(g);
        for (Graph.Edge e : g.edges()) {
            if (FamilyKin.gone(e) || !(e.predicate().equals("relative-of") || e.predicate().equals("sibling-of"))) continue;
            Finding f = findings.get(e.findingId());
            int[] span = e.predicate().equals("sibling-of") ? new int[]{1, 1} : f == null ? null : FamilyKin.spanOf(quoteOf(f));
            if (span == null) continue;
            FamilyKin.Relation r = FamilyKin.of(g, parentsOf, sexes, e.from(), e.to());
            if (r == null || FamilyKin.fits(span, r.upA(), r.upB())) continue;
            List<String> ids = new ArrayList<>(List.of(e.findingId())); ids.addAll(r.findings());
            out.add(new Problem("unlikely", "\"" + (f == null ? FamilyAccount.sentence(name.apply(e.from()), e.predicate(), name.apply(e.to())) : f.title().replaceFirst("[.。]$", "")) + "\""
                    + (e.predicate().equals("relative-of") ? ", in the words \"" + Acquisitions.compress(quoteOf(f), 80) + "\"," : "") + " does not fit the parent claims, which make " + name.apply(e.from()) + " "
                    + name.apply(e.to()) + "'s " + r.words() + ". A generation may be missing or counted twice, or two people of one name taken for one.", ids));
        }
        // one page of one source cited for two different births or deaths of one person: one of the two was misread or miscopied
        Map<String, List<Finding>> onPage = new LinkedHashMap<>();
        for (Graph.Edge e : g.edges()) {
            if (FamilyKin.gone(e) || !Set.of("born-on", "born-in", "died-on", "died-in", "buried-in").contains(e.predicate())) continue;
            Finding f = findings.get(e.findingId());
            // one claim counts once, however many of its sources cite the page (two files that agree, one event citing the page twice)
            if (f != null) for (Finding.Source s : f.sources()) {
                String[] u = Gedcom.unitOf(s);
                if (u == null) continue;
                List<Finding> here = onPage.computeIfAbsent(e.from() + "\t" + e.predicate() + "\t" + Gedcom.unitKey(u), k -> new ArrayList<>());
                if (here.stream().noneMatch(x -> x.id().equals(f.id()))) here.add(f);
            }
        }
        for (List<Finding> same : onPage.values()) {
            List<Finding> told = same.stream().filter(f -> same.stream().noneMatch(o -> !o.id().equals(f.id()) && o.id().compareTo(f.id()) < 0 && Finding.Triple.canon(o.triple().object()).equals(Finding.Triple.canon(f.triple().object())))).toList();
            if (told.size() < 2) continue;
            String[] u = Gedcom.unitOf(told.get(0).sources().stream().filter(s -> Gedcom.unitOf(s) != null).findFirst().orElseThrow());
            out.add(new Problem("unlikely", told.size() + " claims about " + name.apply(g.nodeOf(told.get(0), true)) + " differ and cite one page of one source (" + u[0] + ", " + u[1] + "): "
                    + String.join(" and ", told.stream().map(f -> "\"" + f.title().replaceFirst("[.。]$", "") + "\"").toList()) + ". The page says one thing, so one of them was read or copied wrongly. Look at the page.", told.stream().map(Finding::id).toList()));
        }
        // one person written two ways, or two people of one name: dates first, names after. An age in a record dates a birth too.
        // What else the two share is laid out claim by claim; a pair the family said are two people is not asked about again
        List<Graph.Node> people = g.nodes().stream().filter(n -> n.kind().equals("person")).toList();
        Function<String, FamilyDate> bornOf = id -> { Life l = lives.get(id); if (l != null && l.born() != null) return l.born(); List<Event> a = ages.get(id); return a == null ? null : a.get(0).when(); };
        Map<String, Set<PersonIds.Id>> ids = PersonIds.all(store, g);
        List<Object[]> pairs = new ArrayList<>();   // [problem, what agrees, what differs]
        // a second way in for names in Latin letters: two spellings of one sound (Hale, Hail), which the letters alone miss
        Map<String, FamilyNames.Sound> sounds = new LinkedHashMap<>();
        for (Graph.Node n : people) sounds.put(n.id(), FamilyNames.sound(n.label()));
        for (int i = 0; i < people.size(); i++) for (int k = i + 1; k < people.size(); k++) {
            Graph.Node a = people.get(i), b = people.get(k);
            // a family-tree site's person id is the surest sign: the same id is one person, two ids from one site are two people
            List<String> why = new ArrayList<>();
            String byId = PersonIds.compare(ids.get(a.id()), ids.get(b.id()), why);
            int distance = closest(a, b);
            if (distance > 1 && !byId.equals("same") && !FamilyNames.soundAlike(sounds.get(a.id()), sounds.get(b.id()))) continue;
            // one hereditary head name, carried by every head of a family in turn: two heads of it are two men, whatever else they share
            String heads = distance == 0 ? hereditaryHeads(g, a, b) : null;
            if (heads != null) { out.add(new Problem("same-name", heads, List.of())); continue; }
            FamilyDate ba = bornOf.apply(a.id()), bb = bornOf.apply(b.id());
            boolean datesClash = ba != null && bb != null && FamilyDate.apart(ba, bb, 2);
            // names that only sound alike are no shared name: two people of them need no warning
            if (distance > 1 && !byId.equals("same") && (datesClash || byId.equals("different") || apart.contains(Graph.pair(a.id(), b.id())))) continue;
            // dates first (unless a family-tree site's id says one person), then the family's own word that the two are two people, then the ids
            if (datesClash && !byId.equals("same")) { out.add(new Problem("same-name", a.label() + (a.label().contains(String.valueOf(ba.year())) ? "" : " (born " + ba.phrase() + ")") + " and " + b.label() + (b.label().contains(String.valueOf(bb.year())) ? "" : " (born " + bb.phrase() + ")") + " share a name and are two people. A record naming one of them needs a second identifier before it is tied to either.", List.of())); continue; }
            if (apart.contains(Graph.pair(a.id(), b.id()))) { out.add(new Problem("same-name", a.label() + " and " + b.label() + " share a name and were written down as two people. A record naming one of them needs a second identifier before it is tied to either.", List.of())); continue; }
            if (byId.equals("different")) { out.add(new Problem("same-name", a.label() + " and " + b.label() + " share a name and are two people: they have two different ids on one family-tree site (" + why.get(0) + ").", List.of())); continue; }
            // the example joins them the way the Decisions page would: the name that tells namesakes apart, or has a birth, is kept
            boolean aIntoB = FamilyDecisions.foldsInto(g, findings.values(), a.label(), b.label());
            String fold = aIntoB ? a.label() : b.label(), into = aIntoB ? b.label() : a.label();
            if (byId.equals("same")) { out.add(new Problem("same-person?", a.label() + " and " + b.label() + " have the same " + why.get(0) + ", so they are very likely one person" + (datesClash ? ", although their birth years differ" : "") + "." + joinOrKeep(fold, into, "the same " + why.get(0)), List.of(), List.of(a.label(), b.label()))); continue; }
            FamilySame.Comparison c = FamilySame.compare(g, findings, a.id(), b.id());
            if (c.twoPeople()) { out.add(new Problem("same-name", a.label() + " and " + b.label() + " share a name and are two people: \"" + c.related().text() + "\". A record naming one of them needs a second identifier before it is tied to either.", c.related().findings())); continue; }
            pairs.add(new Object[]{new Problem("same-person?", a.label() + " and " + b.label() + (distance == 0 ? " are written the same way" : distance == 1 ? " differ by one character" : " sound alike") + " and no date sets them apart. " + c.said()
                    + joinOrKeep(fold, into, c.reason()), c.findings(), List.of(a.label(), b.label())), c.agree().size(), c.differ().size()});
        }
        // the pairs with the most that agrees and the least that differs first
        pairs.sort(Comparator.comparingInt((Object[] x) -> -(int) x[1]).thenComparingInt(x -> (int) x[2]));
        for (Object[] x : pairs) out.add((Problem) x[0]);
        // one given name under two family names with something else that agrees, or a record that gives one entry the name of another: one
        // person who married into a family or was adopted, or two people. And a record written under a name the person did not carry then.
        // Both are the family's to say, and the questions about names ask them
        for (FamilyNameQuestions.Question q : FamilyNameQuestions.forChecks(store, g)) {
            String said = q.text().replaceFirst("\\s*(Are they one person\\?|(The record is kept as it is\\.\\s*)?Which is right\\?)$", "");
            out.add(new Problem(q.kind().equals("one-person") ? "one-person?" : "name-at-date", said + (q.kind().equals("one-person")
                    ? " Whether they are one person is for the family to say: researchzosho genealogy who asks it, with what each answer does. [question " + q.code() + "]"
                    : " The record is kept as it is. researchzosho genealogy who asks the family which is right. [question " + q.code() + "]"), q.findings()));
        }
        // two entries of the list of names that are one name written two ways, one of them with no fact: a name given one way could miss the person
        for (FamilyNames.Twin t : FamilyNames.twins(g, apart))
            out.add(new Problem("same-person?", "\"" + t.empty() + "\" and \"" + t.keep() + "\" are one name written two ways, and your library has an entry for each. No fact is about \"" + t.empty()
                    + "\", and " + FamilyQuestions.factsSaid(t.keepFacts()) + " about \"" + t.keep() + "\". To make them one entry: researchzosho graph merge \"" + t.emptyName() + "\" \"" + t.keepName()
                    + "\" --because \"one name written two ways\". Nothing is lost by it, because the entry \"" + t.empty() + "\" holds no facts. researchzosho genealogy tidy offers to do it for you.", List.of()));
        return out;
    }

    /**
     * Two entries whose name is a family's hereditary head name ({@link FamilyHouses#hereditaryName}), both recorded as heads of that family:
     * the sentence that says they are two men; null when that is not so. The same name is not the same man.
     */
    static String hereditaryHeads(Graph g, Graph.Node a, Graph.Node b) {
        for (String f : FamilyHouses.all(g)) {
            String name = FamilyHouses.hereditaryName(g, f);
            if (name.isBlank() || !FamilyForms.sameForm(bare(a.label()), name) || !FamilyForms.sameForm(bare(b.label()), name)) continue;
            FamilyHouses.Membership ha = null, hb = null;
            for (FamilyHouses.Membership m : FamilyHouses.heads(g, f)) {
                if (m.person().equals(a.id()) && ha == null) ha = m;
                if (m.person().equals(b.id()) && hb == null) hb = m;
            }
            if (ha == null || hb == null) continue;
            String fam = FamilyHouses.labelOf(g, f);
            String when = ha.from() != null && hb.from() != null ? ": they became its heads " + ha.from().in() + " and " + hb.from().in() : "";
            return a.label() + " and " + b.label() + " carry " + name + ", the hereditary head name of the " + fam + ", which each head of that family took in turn, and both are recorded as its heads" + when
                    + ". The same name is not the same man, so they are two people. A record naming " + name + " needs a date or a second identifier before it is tied to either.";
        }
        return null;
    }

    /** What to type either way: join the two with the reason, or write down that they are two people. */
    static String joinOrKeep(String a, String b, String why) {
        return " If they are one person: researchzosho graph merge \"" + a + "\" \"" + b + "\" --because \"" + (why.isBlank() ? "<what shows they are one>" : why.replace("\"", "'")) + "\""
                + ". If they are two people: researchzosho genealogy different \"" + a + "\" \"" + b + "\" --because \"<what shows they are two>\"";
    }

    /** The account's own words a claim was read from, or "". */
    static String quoteOf(Finding f) {
        Matcher m = Pattern.compile("(?s)The account says: \"(.*?)\"\\s*(?:\\n|$)").matcher(f == null ? "" : f.body());
        return m.find() ? m.group(1) : "";
    }

    private static final Pattern FOLLOWS_DEATH = Pattern.compile("(?i)^(cremat|funeral|buri|interred|interment|will proved|probate|obituary|memorial service)|火葬|葬儀|葬式|告別式|埋葬|納骨");

    /** A life event that comes after a death by its nature: its words, after the person's name, start with a cremation, a funeral, a burial or a probate. */
    private static boolean followsDeath(Event ev) {
        String what = ev.sentence().contains(": ") ? ev.sentence().substring(ev.sentence().indexOf(": ") + 2) : ev.sentence();
        return ev.predicate().equals("life-event") && FOLLOWS_DEATH.matcher(what.strip()).find();
    }

    /**
     * Whether {@code part} is only a part of {@code whole}'s name: a given name or a family name alone (栄子 of 森田栄子, Kimie of Kimie Hale).
     * A name in Chinese characters or kana is a part when the whole begins or ends with it; in Latin letters, when every word of it is a word of the whole.
     */
    static boolean partOfName(String part, String whole) {
        String p = bare(part), w = bare(whole);
        if (p.isEmpty() || w.isEmpty() || p.length() >= w.length()) return false;
        boolean cjk = (p + w).codePoints().anyMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN || Character.UnicodeScript.of(c) == Character.UnicodeScript.HIRAGANA || Character.UnicodeScript.of(c) == Character.UnicodeScript.KATAKANA);
        if (cjk) return w.startsWith(p) || w.endsWith(p);
        Set<String> pw = new LinkedHashSet<>(List.of(FamilyQuestions.plain(part.replaceAll("[(（][^)）]*[)）]", "")).replace(",", " ").split("\\s+")));
        Set<String> ww = new LinkedHashSet<>(List.of(FamilyQuestions.plain(whole.replaceAll("[(（][^)）]*[)）]", "")).replace(",", " ").split("\\s+")));
        pw.remove(""); ww.remove("");
        return !pw.isEmpty() && pw.size() < ww.size() && ww.containsAll(pw);
    }

    /**
     * Whether two dates break the rule {@code later - earlier >= least}: 2 when no years the dates allow keep it, 1 when only the years they are
     * written around break it (an about date, whose range still allows it), 0 when it holds. A rule of at most {@code m} years is
     * {@code breaks(earlier, later, -m)}.
     */
    static int breaks(FamilyDate later, FamilyDate earlier, int least) {
        if (later.latest() - earlier.earliest() < least) return 2;
        Integer a = later.centre(), b = earlier.centre();
        return a != null && b != null && a - b < least ? 1 : 0;
    }

    /** A name with its birth after it, unless the name already says when the person was born: "Tom Hale (born 1902)" is not written twice. */
    static String withBorn(String name, FamilyDate born) {
        return name.matches("(?s).*[(（]\\s*born\\b.*") ? name : name + " (born " + born.phrase() + ")";
    }

    /** The years between two dates, as a sentence says them: "12" for two exact years, "about 12", or the most (or least) the dates allow. */
    static String years(FamilyDate later, FamilyDate earlier, boolean most) {
        if (later.exact() && earlier.exact()) return String.valueOf(later.year() - earlier.year());
        if (later.centre() != null && earlier.centre() != null) return "about " + (later.centre() - earlier.centre());
        return most ? "at most " + (later.latest() - earlier.earliest()) : "at least " + (later.earliest() - earlier.latest());
    }

    /** The year a claim's first line ends with: "John Ellis (born 1851) married to Ann Hart (1832)." is dated 1832, not by the name. */
    static Integer claimYear(Finding f) { FamilyDate d = claimDate(f); return d == null ? null : d.year(); }

    /**
     * The date a claim's first line ends with, in brackets as the claim was written ("(BET 1850 AND 1860)", "(明治40年頃 (about 1907))"), read
     * as a date with its qualifier; else the last year on the line, as an exact year.
     */
    static FamilyDate claimDate(Finding f) {
        String line = f.body().lines().findFirst().orElse("").strip();
        // the names first: "Tom Hale (born 1902)" is a name, and his undated marriage is not dated by it
        if (f.triple() != null) {
            // what a claim says of somebody is no name: a date or an age ("18" out of the line would take the 18 out of "(1880)" too), and a
            // life event, a job or a place, whose year dates the claim ("received the Order of the Sacred Treasure in 1985"). The other side
            // of a claim is a name when it is a relative, or written with a bracket that tells a namesake apart
            String p = f.triple().predicate(), o = f.triple().object() == null ? "" : f.triple().object().strip();
            boolean name = !p.endsWith("-on") && !p.equals("aged") && (FamilyAccount.personToPerson(p) || FamilyAccount.associate(p) || o.endsWith(")") || o.endsWith("）"));
            for (String n : name ? new String[]{f.triple().subject(), o} : new String[]{f.triple().subject()}) if (n != null && !n.isBlank()) line = line.replace(n, " ");
        }
        line = line.strip().replaceFirst("[.。]$", "").strip();
        if (line.endsWith(")") || line.endsWith("）")) {
            int depth = 0;
            for (int i = line.length() - 1; i >= 0; i--) {
                char c = line.charAt(i);
                if (c == ')' || c == '）') depth++;
                else if ((c == '(' || c == '（') && --depth == 0) { FamilyDate d = FamilyDate.parse(line.substring(i + 1, line.length() - 1)); if (d != null) return d; break; }
            }
        }
        Matcher m = Pattern.compile("(?<!\\d)(1[5-9]\\d\\d|20\\d\\d)(?!\\d)").matcher(line);
        Integer last = null;
        while (m.find()) last = Integer.parseInt(m.group(1));
        return last == null ? null : new FamilyDate(String.valueOf(last), last, "");
    }

    /** The smallest edit distance between any name of one and any name of the other, in modern character forms. */
    static int closest(Graph.Node a, Graph.Node b) {
        int best = Integer.MAX_VALUE;
        List<String> na = new ArrayList<>(a.aliases()); na.add(a.label());
        List<String> nb = new ArrayList<>(b.aliases()); nb.add(b.label());
        for (String x : na) for (String y : nb) {
            String p = bare(x), q = bare(y);
            // one character is a whole different name in kanji (正一, 正二): only a longer, alphabetic name may differ by one
            boolean alphabetic = p.chars().allMatch(c -> c < 0x250) && q.chars().allMatch(c -> c < 0x250);
            if (p.isEmpty() || q.isEmpty() || (p.length() < 2)) continue;
            int d = p.equals(q) ? 0 : alphabetic && Math.min(p.length(), q.length()) >= 6 ? edit(p, q) : Integer.MAX_VALUE;
            best = Math.min(best, d);
        }
        return best;
    }

    /** A name as it is compared: modern character forms, no spaces, and without the brackets that tell two people of one name apart. */
    private static String bare(String name) { return Vocabulary.norm(KanjiForms.modern(name.replaceAll("\\s*[(（][^)）]*[)）]\\s*$", ""))).replace(" ", ""); }

    static int edit(String a, String b) {
        int[] prev = new int[b.length() + 1], now = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            now[0] = i;
            for (int j = 1; j <= b.length(); j++) now[j] = Math.min(Math.min(now[j - 1], prev[j]) + 1, prev[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
            int[] t = prev; prev = now; now = t;
        }
        return prev[b.length()];
    }

    public static String render(List<Problem> problems) {
        if (problems.isEmpty()) return "Nothing in the family's claims contradicts itself.";
        StringBuilder b = new StringBuilder();
        for (Problem p : problems) b.append("[").append(p.kind()).append("] ").append(p.text()).append(p.findings().isEmpty() ? "" : "  (" + String.join(", ", p.findings().stream().filter(x -> !x.isBlank()).distinct().toList()) + ")").append('\n');
        return b.toString().stripTrailing();
    }

    /**
     * The same findings, said to a person who has never used the program: what was done, what each group of findings means,
     * each finding with the facts it rests on written out, and what the person can do about it.
     */
    public static String forPerson(LibraryStore store, List<Problem> all) throws IOException {
        Map<String, Accepted> seen = accepted(store);
        List<Problem> problems = all.stream().filter(p -> !seen.containsKey(p.id())).toList();
        List<Problem> looked = all.stream().filter(p -> seen.containsKey(p.id())).toList();
        StringBuilder b = new StringBuilder("The library compared all the facts about your family with each other, to find facts that cannot all be true at the same time. It did not change anything.\n\n");
        if (problems.isEmpty()) return b.append(looked.isEmpty() ? "It found nothing wrong: no impossible dates, no person with too many parents, and no two people who look like one person written twice.\n" : "It found nothing new to look at.\n")
                .append("This does not prove the facts are right. It only means they do not contradict each other.\n").append(waiting(store)).append(lookedAt(looked, seen)).toString();
        // the examples use a real code and a real source from the list below, so each one does something when it is typed: a fact still
        // waiting for the family's word, never one somebody accepted or disputed
        Finding firstFact = null;
        for (Problem p : problems) {
            for (String id : p.findings()) if (id != null && !id.isBlank()) { Finding f = store.finding(id); if (f != null && f.state() == Finding.State.draft) { firstFact = f; break; } }
            if (firstFact != null) break;
        }
        boolean anyFacts = problems.stream().anyMatch(p -> p.findings().stream().anyMatch(x -> x != null && !x.isBlank()));
        b.append("It found ").append(problems.size()).append(problems.size() == 1 ? " thing" : " things").append(" for you to look at.")
         .append(anyFacts ? " Under each one are the facts it rests on, each with its short code" + (firstFact == null ? "" : ", such as " + firstFact.id().replaceFirst("^(F-\\d+).*", "$1")) + ", and the fact written out. A pair of names that may be one person lists facts only when some agree or differ." : "")
         .append("\n");
        String[][] groups = {
                {"impossible", "THINGS THAT CANNOT BE TRUE", "The most common reason is that two different people with the same name were taken for one person, for example a father and a son. The other common reason is a date that was written down wrongly in one of your sources."},
                {"unlikely", "THINGS THAT ARE POSSIBLE BUT UNLIKELY", "These may be true. They are worth a second look, because they often point to a wrong date or to a missing generation."},
                {"same-name", "TWO DIFFERENT PEOPLE WHO SHARE ONE NAME", "The dates or the family's relations show that these are two people. Later, when a search finds a record with this name, make sure it is given to the right one of the two."},
                {"same-person?", "NAMES THAT MAY BE ONE PERSON WRITTEN TWO WAYS", "The library cannot tell whether these are one person or two. Under each pair it lists what else is the same for both, and what differs, with the facts it read that from. A husband or wife, a parent or a place that is the same for both makes one person likely. The ones with the most in common come first."},
                {"one-person?", "ONE PERSON UNDER TWO FAMILY NAMES?", "A person who married into a family, was adopted or became an heir carries a second family name. These entries carry one given name under two family names, or a record gives one of them the name of the other, and something else agrees. Only the family can say whether they are one person: researchzosho genealogy who asks it, and says what each answer does."},
                {"name-at-date", "RECORDS WRITTEN UNDER A NAME THE PERSON DID NOT CARRY THEN", "By the names in your library, the person carried another name at the date of these records. The record is kept as it is: it may have been written later, the name may have changed earlier, or it may be about another person. researchzosho genealogy who asks the family which."},
                {"read-two-ways", "NAMES THAT ARE READ TWO WAYS", "Your sources give the same written name two different readings. A search looks for the reading, so a record that gives it, such as a register with the reading beside the name, tells which one to search for. Both readings are kept until then."},
                {"unreadable", "DATES THE LIBRARY COULD NOT READ", "The library found no year in these dates, so it could not compare them with the other dates. A date it can read has its year in it: 1885, about 1885, before 1900, between 1880 and 1885, or 明治18年."},
                {"note", "THINGS WORTH KNOWING", "Nothing here is wrong. These are facts that are easy to misread when you come across them later."}};
        Set<String> shown = new HashSet<>();
        boolean split = false;
        for (String[] g : groups) {
            List<Problem> of = problems.stream().filter(p -> p.kind().equals(g[0])).toList();
            if (of.isEmpty()) continue;
            shown.add(g[0]);
            if (g[0].equals("impossible") || g[0].equals("same-name")) split = true;
            b.append("\n").append(g[1]).append(" (").append(of.size()).append(")\n").append(g[2]).append("\n");
            int n = 0;
            for (Problem p : of) {
                b.append("\n  ").append(++n).append(". ").append(p.text()).append("   [code ").append(p.id()).append("]\n");
                for (String id : p.findings().stream().filter(x -> x != null && !x.isBlank()).distinct().toList()) {
                    Finding f = store.finding(id);
                    String code = id.replaceFirst("^(F-\\d+).*", "$1");
                    b.append("       ").append(code).append(f == null ? "" : ": " + f.title() + (f.sources().isEmpty() ? "" : "   (from " + fromAll(f) + ")")).append("\n");
                }
            }
        }
        for (Problem p : problems) if (!shown.contains(p.kind())) b.append("\n  ").append(p.text()).append("   [code ").append(p.id()).append("]\n");
        // what "genealogy source" matches: a file by its name, a page by its whole address
        String sourceName = firstFact == null || firstFact.sources().isEmpty() ? "" : sourceArgument(firstFact.sources().get(0).locator());
        b.append("\nWHAT YOU CAN DO\n"
                + (firstFact == null
                   ? "If one of the facts is simply wrong, say so: give the command researchzosho dispute with the fact's code, which researchzosho inbox shows in front of each fact, and your reason in quotes. The fact then no longer counts.\n\n"
                   : "If one of the facts is simply wrong, say so with its code, and say why. The fact then no longer counts. For example:\n\n"
                     + "    researchzosho dispute " + firstFact.id().replaceFirst("^(F-\\d+).*", "$1") + " \"the reason, in your own words\"\n\n")
                + "If a whole source looks wrong, for example a book that mixes up two families, see every fact that rests on it, and which of them have no other source"
                + (sourceName.isBlank() ? ", with researchzosho genealogy source and the source's file name or address.\n\n" : ". For example:\n\n    researchzosho genealogy source " + sourceName + "\n\n"));
        if (split) b.append("If two people were mixed into one, give the second person a name of their own, and list the facts that belong to that second person. Those facts are then moved to the second person:\n\n"
                + "    researchzosho genealogy split \"John Ellis\" --as \"John Ellis (born 1851)\" --claims F-0003,F-0007\n\n");
        // a question about names is the family's to answer in genealogy who, never a thing to accept here
        Problem example = problems.stream().filter(p -> !p.kind().equals("one-person?") && !p.kind().equals("name-at-date")).findFirst().orElse(null);
        if (example != null) b.append("If a thing on the list is right as it stands, for example a father who really was 60 when his son was born, say so with the code in brackets after it and a few words on why. It is then listed apart, under things you have already looked at, until the facts it is about change. For example:\n\n"
                + "    researchzosho genealogy check accept " + example.id() + " \"why it is right as it stands\"\n\n");
        b.append("After you have changed something, give the command researchzosho genealogy check again. The things you fixed will be gone from the list.\n");
        b.append(waiting(store));
        b.append(lookedAt(looked, seen));
        return b.toString();
    }

    /** How many questions about names and families wait for the family, and the command that asks them; "" when none waits. */
    private static String waiting(LibraryStore store) throws IOException {
        List<FamilyNameQuestions.Question> open = FamilyNameQuestions.open(store);
        int n = open.size();
        if (n == 0) return "";
        return "\n" + (n == 1 ? "One question" : n + " questions") + " about names and families " + (n == 1 ? "waits" : "wait") + " for your family's answer"
                + (open.stream().anyMatch(q -> q.kind().equals("one-person")) ? ", such as whether two entries are one person." : ".")
                + " researchzosho genealogy who asks " + (n == 1 ? "it" : "them one at a time") + ", with what each answer does.\n";
    }

    private static String lookedAt(List<Problem> looked, Map<String, Accepted> seen) {
        if (looked.isEmpty()) return "";
        StringBuilder b = new StringBuilder("\nALREADY LOOKED AT (").append(looked.size()).append(")\nSomebody said these are right as they stand. They are not counted above. To put one back on the list: researchzosho genealogy check reopen <code>\n");
        for (Problem p : looked) { Accepted a = seen.get(p.id()); b.append("\n  ").append(p.text()).append("\n       Looked at on ").append(a.date()).append(a.why().isBlank() ? "." : ": \"" + a.why() + "\"").append("   [code ").append(p.id()).append("]\n"); }
        return b.toString();
    }

    static Path acceptedFile(LibraryStore store) { return store.root().resolve("catalog").resolve("checks-accepted.tsv"); }

    /** What somebody said is right as it stands, by code; a line that starts with "-" puts a code back on the list. */
    public static Map<String, Accepted> accepted(LibraryStore store) throws IOException {
        Map<String, Accepted> out = new LinkedHashMap<>();
        Path f = acceptedFile(store);
        if (!Files.exists(f)) return out;
        for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
            String[] c = line.split("\t", -1);
            if (c.length >= 2 && c[0].equals("-")) out.remove(c[1]);
            else if (c.length >= 4) out.put(c[0], new Accepted(c[0], c[1], c[2], c[3], c.length > 4 ? c[4] : ""));
        }
        return out;
    }

    /** The problems of {@link #check} somebody has not said are right as they stand. */
    public static List<Problem> open(LibraryStore store, List<Problem> problems) throws IOException {
        Map<String, Accepted> seen = accepted(store);
        return problems.stream().filter(p -> !seen.containsKey(p.id())).toList();
    }

    /** A problem of today's check said to be right as it stands, with why. */
    public static Problem accept(LibraryStore store, String code, String why, String by) throws IOException {
        String c = code == null ? "" : code.strip().toLowerCase(Locale.ROOT).replaceAll("^\\[?code\\s*|\\]$", "");
        Problem p = check(store).stream().filter(x -> x.id().equals(c)).findFirst().orElse(null);
        if (p == null) throw new IllegalArgumentException("The check finds nothing with the code " + code + " today. The command researchzosho genealogy check lists each thing with its code in square brackets after it.");
        append(store, c + "\t" + clean(by) + "\t" + LocalDate.now() + "\t" + clean(why) + "\t" + clean(Acquisitions.compress(p.text(), 200)));
        return p;
    }

    /** A problem put back on the list. Returns whether it had been said to be right. */
    public static boolean reopen(LibraryStore store, String code) throws IOException {
        String c = code == null ? "" : code.strip().toLowerCase(Locale.ROOT);
        if (!accepted(store).containsKey(c)) return false;
        append(store, "-\t" + c);
        return true;
    }

    private static void append(LibraryStore store, String line) throws IOException {
        Path f = acceptedFile(store);
        Files.createDirectories(f.getParent());
        Files.writeString(f, line + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static String clean(String s) { return s == null ? "" : s.replaceAll("[\\t\\r\\n]+", " ").strip(); }

    /** A source as {@code genealogy source} takes it: a file by its name, anything else by its whole address, quoted when it has a space. */
    static String sourceArgument(String locator) {
        String l = locator == null ? "" : locator.strip();
        String arg = l.startsWith("file:") ? from(l) : l;
        return arg.contains(" ") ? "\"" + arg + "\"" : arg;
    }

    /** Where a fact came from, short enough to read: a file's name, or a page's site and last part. */
    /**
     * Where a claim comes from, every source by its short name: "www.example.org, endo-family and tree.ged". A fact the family's own tree
     * gives too is not shown as a web page's alone.
     */
    static String fromAll(Finding f) {
        List<String> names = new ArrayList<>();
        for (Finding.Source s : f.sources()) { String n = from(s.locator()); if (!n.isBlank() && !names.contains(n)) names.add(n); }
        return names.size() <= 1 ? String.join("", names) : String.join("; ", names.subList(0, names.size() - 1)) + " and " + names.get(names.size() - 1);
    }

    static String from(String locator) {
        String l = locator == null ? "" : locator.strip();
        if (l.startsWith("file:")) return l.substring(l.lastIndexOf('/') + 1);
        try {
            URI u = URI.create(l);
            if (u.getHost() != null) { String path = URLDecoder.decode(u.getRawPath() == null ? "" : u.getRawPath(), StandardCharsets.UTF_8); String last = path.replaceAll("/+$", ""); last = last.substring(last.lastIndexOf('/') + 1); return u.getHost() + (last.isEmpty() ? "" : ", " + Acquisitions.compress(last, 40)); }
        } catch (Exception ignored) { }
        return Acquisitions.compress(l, 60);
    }
}
