package org.researchzosho.librarian;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Close family: the owner of the library; the owner's parents, brothers and sisters, grandparents and great-grandparents; and the brothers,
 * sisters, children, husbands and wives of any of those. They are the people a family remembers, and the questions about names and
 * families ask only about them ({@link FamilyNameQuestions}).
 *
 * <p>The owner is the entry the owner's own words are filed under, "the owner of this library" (what the owner wrote beside a link, or told
 * the library), or the person that entry was joined into. From there the walk follows the kin claims (a parent, a child, a brother or
 * sister, a husband or wife) and the owner's own words for a relative: a note beside a link ("About Tom Hale: my father's cousin"), a
 * described person ("the owner of this library's father's father"), a note on a page's title ("the person who keeps this library notes: my
 * great grandfather"), and the owner's own files, whose "I" is the owner ("i am Ann Hale"). Each person reached has their relation to the
 * owner, in generations and in words ("your father's father's brother"). Where no owner is known, close family is the people the family's
 * own files name: the accounts and notes it read, its tree files, and the family tree sites. Worked out from the claims every time, never
 * stored.
 */
public final class FamilyClose {

    private FamilyClose() { }

    /** The entry a read files the owner's own words under. */
    static final String OWNER = "the owner of this library";

    /** At most this many generations up and down the walk goes: far enough for a great-grandparent's brother, and a brother's child. */
    private static final int UP = 4, DOWN = 2;

    /**
     * One step of a relation, from the owner outwards: 'u' to a parent, 'd' to a child, 's' to a brother or sister, 'm' to a husband or
     * wife; {@code sex}: the sex of the person the step reaches, "male", "female", or "" when nothing says it.
     */
    public record Step(char way, String sex) {
        public Step { sex = sex == null ? "" : sex; }
    }

    /**
     * How a person is related to the owner: {@code up} generations to the ancestor of the owner the relation goes through, {@code down}
     * generations from there, and whether the last step is a marriage ({@code spouse}). A brother is 1 up and 1 down, a grandfather's sister 3
     * up and 1 down, a great-grandmother 3 up and married to a great-grandfather or not. {@code path}: the steps from the owner, as the walk or
     * the owner's own words give them.
     */
    public record Kin(int up, int down, boolean spouse, List<Step> path) {
        public Kin { path = List.copyOf(path); }

        /** Whether the relation is close family. */
        public boolean close() {
            if (spouse) return down == 0 && up <= 3 || up == 1 && down == 1;
            return down == 0 && up <= 3 || down == 1 && up <= 4 || down == 2 && up == 1;
        }

        /** The relation in words, written the same way for everybody ({@link #words}): "your father's father's sister", "you"; "" with no path. */
        public String said() {
            if (up == 0 && down == 0 && !spouse) return "you";
            List<String> w = words(path);
            return w.isEmpty() ? "" : "your " + String.join("'s ", w);
        }

        /** The sex of the person the relation reaches, as its last step says it; "" when it does not. */
        public String sex() { return path.isEmpty() ? "" : path.get(path.size() - 1).sex(); }

        /** The same relation, with the sex of the person it reaches where its own steps did not say it. */
        Kin reaching(String sex) {
            if (path.isEmpty() || sex == null || sex.isEmpty() || !sex().isEmpty()) return this;
            List<Step> p = new ArrayList<>(path);
            Step last = p.remove(p.size() - 1);
            p.add(new Step(last.way(), sex));
            return new Kin(up, down, spouse, p);
        }
    }

    /**
     * A relation's steps in words, one plain word a step and the same words for everybody: father, mother, or parent where nothing says
     * which; son, daughter or child; brother, sister or "brother or sister"; husband, wife or "husband or wife". A parent's child is a brother
     * or sister, and a brother's or sister's brother or sister is one too, so the path is the shortest the steps allow.
     */
    static List<String> words(List<Step> path) {
        List<Step> p = new ArrayList<>();
        for (Step s : path) {
            Step last = p.isEmpty() ? null : p.get(p.size() - 1);
            if (last != null && (s.way() == 'd' && last.way() == 'u' || s.way() == 's' && last.way() == 's')) { p.set(p.size() - 1, new Step('s', s.sex())); continue; }
            p.add(s);
        }
        List<String> out = new ArrayList<>();
        for (Step s : p) {
            boolean m = s.sex().equals("male"), f = s.sex().equals("female");
            out.add(switch (s.way()) {
                case 'u' -> m ? "father" : f ? "mother" : "parent";
                case 'd' -> m ? "son" : f ? "daughter" : "child";
                case 's' -> m ? "brother" : f ? "sister" : "brother or sister";
                default -> m ? "husband" : f ? "wife" : "husband or wife";
            });
        }
        return out;
    }

    /** Close family in one graph: who the owner is, and each person the walk reached, with their relation to the owner. */
    public static final class Close {
        private final Set<String> owners;
        private final Map<String, Kin> kin;
        private final Set<String> named;
        private final Set<String> ownerFiles;

        Close(Set<String> owners, Map<String, Kin> kin, Set<String> named, Set<String> ownerFiles) {
            this.owners = owners; this.kin = kin; this.named = named; this.ownerFiles = ownerFiles;
        }

        /** Whether the library knows its owner: an entry of the owner, or of a relative the owner's own words describe. */
        public boolean known() { return !kin.isEmpty(); }

        /** Whether a person (a node id) is close family: by the walk from the owner, or, where no owner is known, named by the family's own files. */
        public boolean close(String id) {
            if (id == null) return false;
            if (!known()) return named.contains(id);
            Kin k = kin.get(id);
            return k != null && k.close();
        }

        /** A person's relation to the owner; null when the walk did not reach them. */
        public Kin kin(String id) { return kin.get(id); }

        /** A person's relation to the owner in words: "your father's father"; "" when the walk has no words for it. */
        public String said(String id) { Kin k = kin.get(id); return k == null ? "" : k.said(); }

        /** Whether a claim is the owner's own word: told by the owner, or from a file whose "I" is the owner. The family's answers to questions are not. */
        public boolean ownerSaid(Finding f) {
            if (f == null) return false;
            for (Finding.Source s : f.sources()) if (owners(s)) return true;
            return false;
        }

        /** Whether one source is the owner's own word: what the owner told the library, a note beside a link, or a file whose "I" is the owner. */
        public boolean owners(Finding.Source s) { return s != null && (ownerSource(s) || s.locator() != null && ownerFiles.contains(s.locator())); }
    }

    private static final Map<Graph, Close> MEMO = Collections.synchronizedMap(new WeakHashMap<>());

    /** Close family in a graph ({@link FamilyPeople#view}), worked out once per graph. */
    public static Close of(Graph g) {
        Close c = MEMO.get(g);
        if (c != null) return c;
        c = work(g);
        MEMO.put(g, c);
        return c;
    }

    /** Whether a source is the owner telling the library: a note beside a link, or what the owner told it. A family's answer to a question is not. */
    static boolean ownerSource(Finding.Source s) {
        if (s == null || s.locator() == null || s.locator().startsWith(FamilyNameQuestions.SOURCE)) return false;
        return s.edition() != null && s.edition().strip().toLowerCase(Locale.ROOT).startsWith("as told by " + OWNER);
    }

    // a claim in which a person says who they are: "i am Ann Hale", "my name is Ann Hale", 私は…
    private static final Pattern SELF = Pattern.compile("(?i)(?<![\\p{L}])(?:i am|i'm|my name is)(?![\\p{L}])|(?:私|わたし|僕)は");

    private static Close work(Graph g) {
        Map<String, Finding> byId = new HashMap<>();
        for (Finding f : FamilyPeople.findings(g)) byId.put(f.id(), f);
        Set<String> owners = new HashSet<>();
        String o = g.nodeIdOf(OWNER);
        if (g.node(o) != null && "person".equals(g.node(o).kind())) owners.add(o);
        // the owner's own files: a file in which the owner says who they are
        Set<String> ownerFiles = new HashSet<>();
        for (Graph.Edge e : g.edges()) {
            if (FamilyKin.gone(e) || !owners.contains(e.from())) continue;
            Finding f = byId.get(e.findingId());
            if (f == null || !SELF.matcher(FamilyChecks.quoteOf(f)).find()) continue;
            for (Finding.Source s : f.sources()) if (s.locator() != null && s.locator().startsWith("file:")) ownerFiles.add(s.locator());
        }
        Close shell = new Close(owners, Map.of(), Set.of(), ownerFiles);
        Map<String, Map<String, List<String>>> sexes = FamilyKin.sexes(g);
        // where the words do not say a relative's sex, the claims may: "my father's cousin" who is a woman is his cousin's daughter
        // where the walk starts: the owner, the owner's described relatives, and the relatives the owner's own words name. What the owner's
        // words in a claim say of a person comes first; a described entry, which a reader made from an older note, after it; a loose word
        // ("my uncle", which a family says of a great-uncle too) last, and only where the claims give no path to the person
        Map<String, Seed> seeds = new LinkedHashMap<>();
        for (String id : owners) seeds.put(id, new Seed(new Kin(0, 0, false, List.of()), 0));
        for (Graph.Edge e : g.edges()) {
            if (FamilyKin.gone(e) || !FamilyAccount.personToPerson(e.predicate())) continue;
            Finding f = byId.get(e.findingId());
            if (!shell.ownerSaid(f)) continue;
            String quote = FamilyChecks.quoteOf(f);
            Chain c = chainSaid(quote);
            if (c == null) continue;
            String about = describedSide(g, owners, e.from(), e.to(), quote, f);
            if (about != null) seeds.merge(about, new Seed(c.kin().reaching(sexOf(sexes, about)), c.loose() ? LOOSE : 0), FamilyClose::firmer);
        }
        // a described relative joined into a named entry is one of its other names
        for (Graph.Node n : g.nodes()) {
            if (!"person".equals(n.kind())) continue;
            List<String> names = new ArrayList<>(List.of(n.label()));
            names.addAll(n.aliases());
            for (String name : names) {
                Chain c = describedSaid(name);
                if (c == null) c = notedSaid(name);
                if (c != null) seeds.merge(n.id(), new Seed(c.kin().reaching(sexOf(sexes, n.id())), c.loose() ? LOOSE : 1), FamilyClose::firmer);
            }
        }
        if (seeds.isEmpty()) return new Close(owners, Map.of(), namedByFamilyFiles(g, byId), ownerFiles);
        // the walk: nearest first, by the steps from the owner's own words. What those words say of a person stands: a path through the claims
        // (through an entry that holds two people, say) never outweighs "my father's cousin"
        Map<String, List<Graph.Edge>> touching = new HashMap<>();
        for (Graph.Edge e : g.edges()) {
            if (FamilyKin.gone(e) || !WALKED.contains(e.predicate())) continue;
            touching.computeIfAbsent(e.from(), x -> new ArrayList<>()).add(e);
            touching.computeIfAbsent(e.to(), x -> new ArrayList<>()).add(e);
        }
        record At(String id, Kin kin, int cost) { }
        // of two ways of the same length, the one by blood names the person: a grandfather's mother, not a great-grandfather's wife
        PriorityQueue<At> queue = new PriorityQueue<>(Comparator.comparingInt(At::cost).thenComparing(a -> !a.kin().close()).thenComparingInt(a -> a.kin().up() + a.kin().down()).thenComparing(a -> a.kin().spouse()));
        for (Map.Entry<String, Seed> s : seeds.entrySet()) queue.add(new At(s.getKey(), s.getValue().kin(), s.getValue().rank() == LOOSE ? LOOSE : 0));
        Map<String, Kin> kin = new LinkedHashMap<>();
        while (!queue.isEmpty()) {
            At at = queue.poll();
            if (kin.containsKey(at.id())) continue;
            // a path the claims give wins over a loose word only where it says the word more exactly: an uncle's path ends in a brother of a
            // forebear, a cousin's in a forebear's brother's or sister's child. A path of another shape does not contradict a plain word, and the
            // word stands
            Seed seed = seeds.get(at.id());
            if (seed != null && seed.rank() == LOOSE && at.cost() < LOOSE && !refines(at.kin(), seed.kin())) continue;
            kin.put(at.id(), at.kin());
            for (Graph.Edge e : touching.getOrDefault(at.id(), List.of())) {
                boolean mine = e.from().equals(at.id());
                String other = mine ? e.to() : e.from();
                Graph.Node on = g.node(other);
                if (kin.containsKey(other) || on == null || !"person".equals(on.kind()) || FamilyHouses.isFamily(g, other)) continue;
                String sex = sexOf(sexes, other);
                if (sex.isEmpty()) sex = sexInClaim(g, byId.get(e.findingId()), e, other);
                // one of a married couple is the husband and the other the wife, as the questions about names word a marriage
                if (sex.isEmpty() && e.predicate().equals("married-to")) { String theirs = sexOf(sexes, at.id()).isEmpty() ? at.kin().sex() : sexOf(sexes, at.id()); sex = theirs.equals(MAN) ? WOMAN : theirs.equals(WOMAN) ? MAN : ""; }
                Kin next = switch (e.predicate()) {
                    case "parent-of" -> mine ? step(at.kin(), 'd', sex) : step(at.kin(), 'u', sex);
                    case "child-of", "adopted-by", "foster-child-of" -> mine ? step(at.kin(), 'u', sex) : step(at.kin(), 'd', sex);
                    case "step-parent-of" -> mine ? step(at.kin(), 'd', sex) : step(step(at.kin(), 'u', ""), 'm', sex);
                    case "sibling-of" -> step(at.kin(), 's', sex);
                    case "married-to" -> step(at.kin(), 'm', sex);
                    default -> null;
                };
                if (next != null) queue.add(new At(other, next, at.cost() + 1));
            }
        }
        return new Close(owners, kin, Set.of(), ownerFiles);
    }

    /**
     * The sex a claim's own words give one of its two people, where no claim files it: "Tom Hale's son Ned" says Ned is a man, "his sister
     * Ann" that Ann is a woman ({@link FamilyKin#sexFromQuote}, {@link FamilyKin#sexInWords}). "" when the words do not say.
     */
    static String sexInClaim(Graph g, Finding f, Graph.Edge e, String who) {
        String quote = f == null ? "" : FamilyChecks.quoteOf(f);
        if (quote.isBlank()) return "";
        // a claim whose words name somebody else too ("弟：健二（妻・ハルは 森田勇 三女）") says whose the kin words are only to a reader: not read
        if (namesAnother(g, quote, e.from(), e.to())) return "";
        if (e.predicate().equals("parent-of") || e.predicate().equals("child-of")) {
            String[] said = FamilyKin.sexFromQuote(e.predicate(), e.from(), e.to(), quote);
            return said != null && said[0].equals(who) ? said[1] : "";
        }
        // a word of a brother, a sister, a husband or a wife counts only where the words write this person, so it can be told whose it is
        // ("Hale, Tom (brother)" in an index gives the brother's, not the other one's)
        String q = KanjiForms.modern(quote).toLowerCase(Locale.ROOT);
        if (namesOf(g, who).stream().noneMatch(x -> x.strip().length() >= 2 && q.contains(KanjiForms.modern(x.strip()).toLowerCase(Locale.ROOT)))) return "";
        String other = e.from().equals(who) ? e.to() : e.from();
        return FamilyKin.sexInWords(quote, namesOf(g, who), namesOf(g, other), List.of());
    }

    /**
     * A person's sex as the sex claims file it: the one they agree on, or, where they disagree, the one most of them give (three claims of a
     * man beside one of a woman, which a misread made). "" when none is filed, or no sex has the most.
     */
    static String sexOf(Map<String, Map<String, List<String>>> sexes, String id) {
        Map<String, List<String>> s = sexes.get(id);
        if (s == null || s.isEmpty()) return "";
        String best = "";
        int most = 0;
        boolean tie = false;
        for (Map.Entry<String, List<String>> e : s.entrySet()) {
            int n = e.getValue().size();
            if (n > most) { best = e.getKey(); most = n; tie = false; } else if (n == most) tie = true;
        }
        return tie ? "" : best;
    }

    /** Whether words write the name of a person in the library other than these two: a whole name of two characters or more, or of two words. */
    private static boolean namesAnother(Graph g, String quote, String a, String b) {
        String q = KanjiForms.modern(quote);
        for (Graph.Node n : g.nodes()) {
            if (!"person".equals(n.kind()) || n.id().equals(a) || n.id().equals(b) || FamilyHouses.isFamily(g, n.id())) continue;
            for (String name : namesOf(g, n.id())) {
                String t = KanjiForms.modern(name.replaceAll("\\s*[(（][^)）]*[)）]\\s*$", "").strip());
                if (FamilyQuestions.placeholder(t) || FamilyMentions.isMention(t)) continue;
                boolean whole = FamilyForms.japanese(t) ? t.codePointCount(0, t.length()) >= 2 : t.split("\\s+").length >= 2;
                if (whole && q.contains(t)) return true;
            }
        }
        return false;
    }

    private static List<String> namesOf(Graph g, String id) {
        Graph.Node n = g.node(id);
        if (n == null) return List.of();
        List<String> out = new ArrayList<>(List.of(n.label()));
        out.addAll(n.aliases());
        return out;
    }

    private static final Set<String> WALKED = Set.of("parent-of", "child-of", "adopted-by", "foster-child-of", "step-parent-of", "sibling-of", "married-to");

    /** A relation the walk starts from, and how firmly its words say it: 0 the owner's own words in a claim, 1 a described entry, {@link #LOOSE} a loose word. */
    private record Seed(Kin kin, int rank) { }

    /** The rank of a seed from a loose word, and the cost it enters the walk with: after any path the claims give, which is exact. */
    private static final int LOOSE = 50;

    /**
     * Whether a path the claims give says a loose word more exactly: the same shape, once a parent's child is a brother or sister, with any
     * number of forebears before the brother or sister ("my uncle" for a great-uncle), and a husband or wife after ("my aunt" for an uncle's
     * wife); a cousin's path descends from such a brother or sister, a nephew's from the owner's own.
     */
    static boolean refines(Kin path, Kin loose) {
        String p = shape(path), w = shape(loose);
        if (w.isEmpty() || p.isEmpty()) return false;
        String kind = w.matches("u+sm?") ? "uncle" : w.matches("u+sd+") ? "cousin" : w.matches("sd+") ? "nephew" : "";
        return switch (kind) {
            case "uncle" -> p.matches("u+sm?");
            case "cousin" -> p.matches("u+sd+");
            case "nephew" -> p.matches("sd+");
            default -> false;
        };
    }

    /** A relation's steps as letters, a parent's child written as a brother or sister and a brother's brother as one: "uus", "usd". */
    private static String shape(Kin k) {
        StringBuilder b = new StringBuilder();
        for (Step s : k.path()) {
            int last = b.length() - 1;
            if (last >= 0 && (s.way() == 'd' && b.charAt(last) == 'u' || s.way() == 's' && b.charAt(last) == 's')) { b.setCharAt(last, 's'); continue; }
            b.append(s.way());
        }
        return b.toString();
    }

    /** Of two seeds for one person, the firmer: the one whose words say more, then the nearer. */
    private static Seed firmer(Seed a, Seed b) {
        if (a.rank() != b.rank()) return a.rank() < b.rank() ? a : b;
        return nearer(a.kin(), b.kin()) == a.kin() ? a : b;
    }

    /** What words say of a relation: the relation, and whether they say it loosely ("my uncle", which a family says of a great-uncle too). */
    record Chain(Kin kin, boolean loose) { }

    /** The relation words a family uses loosely, for a great-uncle or a cousin of any degree as much as for the nearest. */
    private static final Pattern LOOSE_WORD = Pattern.compile("(?i)(?:^|\\s)(?:great[- ]?)*(?:uncle|aunt|cousin|nephew|niece)s?$|(?:大叔父|大伯父|大叔母|大伯母|叔父|伯父|叔母|伯母|甥|姪|従兄弟|従姉妹|従兄|従弟|従姉|従妹|いとこ)$");

    /** Of two relations for one person, the nearer: fewer steps, then the close one. */
    private static Kin nearer(Kin a, Kin b) {
        int x = a.up() + a.down() + (a.spouse() ? 1 : 0), y = b.up() + b.down() + (b.spouse() ? 1 : 0);
        if (x != y) return x < y ? a : b;
        return a.close() || !b.close() ? a : b;
    }

    /**
     * One step from a relation: 'u' to a parent, 'd' to a child, 's' to a brother or sister, 'm' to a husband or wife; {@code sex} gives the
     * word. Null where the step leaves the family by blood and marriage the walk follows (a husband's or wife's parent or brother), or goes
     * further than {@link #UP} and {@link #DOWN}. A parent of somebody below the owner's line (a brother's, a cousin's) is not walked to:
     * the owner's own parents and forebears are reached from the owner, and the other parent of such a child may be anybody, an adopted
     * child's birth father or a cousin's.
     */
    static Kin step(Kin k, char s, String sex) {
        if (k == null) return null;
        int u = k.up(), d = k.down();
        if (k.spouse() && s != 'd' || s == 'u' && d > 0) return null;
        List<Step> path = new ArrayList<>(k.path());
        path.add(new Step(s, sex));
        switch (s) {
            case 'u' -> u++;
            case 'd' -> d++;
            case 's' -> { if (d == 0) { u++; d = 1; } }
            case 'm' -> { return u > UP || d > DOWN ? null : new Kin(u, d, true, path); }
            default -> { return null; }
        }
        return u > UP || d > DOWN ? null : new Kin(u, d, false, path);
    }

    // ── the owner's own words for a relative ─────────────────────────────────────────────────────────────────────────

    private static final String ADJ = "(?:(?:elder|eldest|older|oldest|younger|youngest|first|second|third|only|late|maternal|paternal|half|step|adoptive|birth)[- ]?\\s*)*";
    private static final String WORD = "(?:(?:great[- ]?)*(?:grand[- ]?)?(?:father|mother|parent|son|daughter|child|brother|sister|sibling|uncle|aunt|nephew|niece|cousin|husband|wife|spouse)s?|dad|mum|mom|papa|mama|grandpa|grandma|granny)";
    private static final Pattern MY = Pattern.compile("(?i)(?<![\\p{L}])my\\s+(" + ADJ + WORD + "(?:['’]s\\s+" + ADJ + WORD + ")*)(?![\\p{L}])(['’]s\\s+side|[- ]in[- ]law)?");
    // "the elder brother of my mother's grandfather": the relation at the end of the chain, written first
    private static final Pattern OF_MY = Pattern.compile("(?i)(?<![\\p{L}])(" + ADJ + WORD + ")\\s+(?:of|to)\\s+my\\s+(" + ADJ + WORD + "(?:['’]s\\s+" + ADJ + WORD + ")*)(?![\\p{L}])(?!['’]s\\s+side)(?![- ]in[- ]law)");
    static final String WORD_JA = "(?:高祖父母|高祖父|高祖母|曾祖父母|曽祖父母|曾祖父|曽祖父|曾祖母|曽祖母|祖父母|祖父|祖母|両親|父親|母親|父|母|大叔父|大伯父|大叔母|大伯母|叔父|伯父|叔母|伯母|兄弟|姉妹|兄|弟|姉|妹|息子|長男|次男|三男|長女|次女|三女|娘|曾孫|曽孫|孫|甥|姪|従兄弟|従姉妹|従兄|従弟|従姉|従妹|いとこ|妻|夫)";
    private static final Pattern POSSESSIVE_GOES_ON = Pattern.compile("['’]s\\s+\\p{L}");
    private static final Pattern MY_JA = Pattern.compile("(?:私|わたし|僕|ぼく)の((?:(?:父方|母方)の)?" + WORD_JA + "(?:の(?:(?:父方|母方)の)?" + WORD_JA + ")*)");

    /**
     * The relation the owner's own words give a relative: each "my …" in them ("my father's cousin", "my great grandfather on my father's
     * side (my father's father's father)", 私の祖父), read as steps from the owner. The words must agree where they say it twice; null when they
     * say nothing, or two things. "My father's side" says which side, and no relative; a relative by marriage ("my father-in-law") is left out.
     */
    static Kin chain(String text) {
        Chain c = chainSaid(text);
        return c == null ? null : c.kin();
    }

    /**
     * The same, with whether the words say it loosely ({@link #LOOSE_WORD}). "The elder brother of my mother's grandfather" places the person
     * at the end of the chain as written, the grandfather's brother, and such words win over a bare "my …" in the same sentence ("my mom
     * called him her uncle"), which speaks of somebody else.
     */
    static Chain chainSaid(String text) {
        if (text == null || text.isBlank()) return null;
        Kin found = null;
        int longest = -1;
        boolean loose = false;
        boolean ofForm = false;
        Matcher of = OF_MY.matcher(text);
        while (of.find()) {
            if (POSSESSIVE_GOES_ON.matcher(text.substring(of.end())).lookingAt()) continue;
            List<String> pieces = new ArrayList<>();
            for (String p : of.group(2).split("['’]s\\s+")) pieces.add(p.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " "));
            pieces.add(of.group(1).strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " "));
            Kin k = ofWords(pieces);
            if (k == null) continue;
            if (found != null && !same(found, k)) return null;
            ofForm = true;
            if (pieces.size() > longest) { found = k; longest = pieces.size(); loose = LOOSE_WORD.matcher(pieces.get(pieces.size() - 1)).find(); }
        }
        Matcher m = MY.matcher(text);
        while (!ofForm && m.find()) {
            if (m.group(2) != null) continue;
            // the words go on past the relatives to somebody no relation word names: "my father's friend", "my mother's teacher" is no relative
            if (POSSESSIVE_GOES_ON.matcher(text.substring(m.end())).lookingAt()) continue;
            List<String> pieces = new ArrayList<>();
            for (String p : m.group(1).split("['’]s\\s+")) pieces.add(p.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " "));
            Kin k = ofWords(pieces);
            if (k == null) continue;
            if (found != null && !same(found, k)) return null;
            if (pieces.size() > longest) { found = k; longest = pieces.size(); loose = LOOSE_WORD.matcher(pieces.get(pieces.size() - 1)).find(); }
        }
        Matcher j = MY_JA.matcher(text);
        while (!ofForm && j.find()) {
            if (text.startsWith("の", j.end())) continue;   // 私の父の友人: the father's friend, no relative
            List<String> pieces = new ArrayList<>(List.of(j.group(1).split("の")));
            Kin k = ofWords(pieces);
            if (k == null) continue;
            if (found != null && !same(found, k)) return null;
            if (pieces.size() > longest) { found = k; longest = pieces.size(); loose = LOOSE_WORD.matcher(pieces.get(pieces.size() - 1)).find(); }
        }
        // "on my father's side" says the first step's sex where the words that name the relative do not
        Matcher side = Pattern.compile("(?i)\\bmy\\s+(father|mother)['’]s\\s+side\\b").matcher(text);
        if (found != null && side.find() && !found.path().isEmpty() && found.path().get(0).way() == 'u' && found.path().get(0).sex().isEmpty()) {
            List<Step> p = new ArrayList<>(found.path());
            p.set(0, new Step('u', side.group(1).equalsIgnoreCase("father") ? "male" : "female"));
            found = new Kin(found.up(), found.down(), found.spouse(), p);
        }
        return found == null ? null : new Chain(found, loose);
    }

    private static boolean same(Kin a, Kin b) { return a.up() == b.up() && a.down() == b.down() && a.spouse() == b.spouse(); }

    /**
     * A relation from the owner, word by word ("father", "father", "younger brother", 父方, 祖父); null for a word the walk does not know. A
     * side (paternal, maternal, 父方, 母方) says the sex of the first parent of the word it stands with or before.
     */
    static Kin ofWords(List<String> words) {
        Kin k = new Kin(0, 0, false, List.of());
        String side = "";
        for (String w : words) {
            if (w.equals("父方") || w.equals("母方")) { side = w.equals("父方") ? "male" : "female"; continue; }
            List<Step> steps = steps(w, side);
            side = "";
            if (steps == null) return null;
            for (Step st : steps) { k = step(k, st.way(), st.sex()); if (k == null) return null; }
        }
        return k;
    }

    private static final String MAN = "male", WOMAN = "female";

    /** n steps of one way, the first with the sex {@code first} and the last with {@code last}, those between with none. */
    private static List<Step> run(char way, int n, String first, String last) {
        List<Step> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(new Step(way, i == n - 1 ? last : i == 0 ? first : ""));
        return out;
    }

    private static List<Step> join(List<Step> a, List<Step> b) { List<Step> out = new ArrayList<>(a); out.addAll(b); return out; }

    /**
     * The steps of one relation word, each with the sex the word says of the person it reaches: "grandfather" is a parent, then a father;
     * "paternal grandmother" a father, then a mother; "uncle" a parent, then a brother; "cousin" a parent, a brother or sister, a child.
     * {@code side}: the sex of the first parent, where words before this one said it (父方). Null for a word the walk does not know.
     */
    static List<Step> steps(String word, String side) {
        String lower = word.toLowerCase(Locale.ROOT).strip();
        String first = lower.matches("(?s).*\\bpaternal\\b.*") ? MAN : lower.matches("(?s).*\\bmaternal\\b.*") ? WOMAN : side == null ? "" : side;
        String w = lower.replaceAll("^(?:(?:elder|eldest|older|oldest|younger|youngest|first|second|third|only|late|maternal|paternal|half|step|adoptive|birth)[- ]?\\s*)+", "").strip();
        int greats = 0;
        for (Matcher m = Pattern.compile("^great[- ]?").matcher(w); m.find(); m = Pattern.compile("^great[- ]?").matcher(w)) { greats++; w = w.substring(m.end()); }
        boolean grand = w.startsWith("grand");
        String base = w.replaceFirst("^grand[- ]?", "").replaceFirst("(?<=[a-z]{3})s$", "");
        int more = greats + (grand ? 1 : 0);
        String sex = switch (base) {
            case "father", "dad", "papa", "pa", "son", "brother", "uncle", "nephew", "husband" -> MAN;
            case "mother", "mum", "mom", "mama", "ma", "daughter", "sister", "aunt", "niece", "wife" -> WOMAN;
            default -> "";
        };
        return switch (base) {
            case "father", "mother", "parent", "dad", "mum", "mom", "papa", "mama", "pa", "ma" -> run('u', 1 + more, first, sex);
            case "son", "daughter", "child", "children" -> run('d', 1 + more, "", sex);
            case "brother", "sister", "sibling" -> more > 0 ? null : List.of(new Step('s', sex));
            case "uncle", "aunt" -> join(run('u', 1 + more, first, ""), List.of(new Step('s', sex)));
            case "nephew", "niece" -> join(List.of(new Step('s', "")), run('d', 1 + more, "", sex));
            case "cousin" -> more > 0 ? null : List.of(new Step('u', first), new Step('s', ""), new Step('d', ""));
            case "husband", "wife", "spouse" -> more > 0 ? null : List.of(new Step('m', sex));
            default -> switch (w) {
                case "grandpa" -> run('u', 2, first, MAN);
                case "grandma", "granny" -> run('u', 2, first, WOMAN);
                case "父", "父親" -> run('u', 1, first, MAN);
                case "母", "母親" -> run('u', 1, first, WOMAN);
                case "両親" -> run('u', 1, first, "");
                case "祖父" -> run('u', 2, first, MAN);
                case "祖母" -> run('u', 2, first, WOMAN);
                case "祖父母" -> run('u', 2, first, "");
                case "曾祖父", "曽祖父" -> run('u', 3, first, MAN);
                case "曾祖母", "曽祖母" -> run('u', 3, first, WOMAN);
                case "曾祖父母", "曽祖父母" -> run('u', 3, first, "");
                case "高祖父" -> run('u', 4, first, MAN);
                case "高祖母" -> run('u', 4, first, WOMAN);
                case "高祖父母" -> run('u', 4, first, "");
                case "兄", "弟" -> List.of(new Step('s', MAN));
                case "姉", "妹", "姉妹" -> List.of(new Step('s', WOMAN));
                case "兄弟" -> List.of(new Step('s', ""));
                case "叔父", "伯父" -> join(run('u', 1, first, ""), List.of(new Step('s', MAN)));
                case "叔母", "伯母" -> join(run('u', 1, first, ""), List.of(new Step('s', WOMAN)));
                case "大叔父", "大伯父" -> join(run('u', 2, first, ""), List.of(new Step('s', MAN)));
                case "大叔母", "大伯母" -> join(run('u', 2, first, ""), List.of(new Step('s', WOMAN)));
                case "息子", "長男", "次男", "三男" -> run('d', 1, "", MAN);
                case "娘", "長女", "次女", "三女" -> run('d', 1, "", WOMAN);
                case "孫" -> run('d', 2, "", "");
                case "曾孫", "曽孫" -> run('d', 3, "", "");
                case "甥" -> List.of(new Step('s', ""), new Step('d', MAN));
                case "姪" -> List.of(new Step('s', ""), new Step('d', WOMAN));
                case "従兄", "従弟" -> List.of(new Step('u', first), new Step('s', ""), new Step('d', MAN));
                case "従姉", "従妹" -> List.of(new Step('u', first), new Step('s', ""), new Step('d', WOMAN));
                case "従兄弟", "従姉妹", "いとこ" -> List.of(new Step('u', first), new Step('s', ""), new Step('d', ""));
                case "妻" -> List.of(new Step('m', WOMAN));
                case "夫" -> List.of(new Step('m', MAN));
                default -> null;
            };
        };
    }

    /**
     * The relation the owner's words in a claim give directly between its two people, one step apart: {"parent", the parent, the child},
     * {"sibling", a, b} or {"spouse", a, b}. The words say where one of them stands from the owner ("my father's father's father's younger
     * brother's daughter") and the walk says where the other stands (the father's father's father's younger brother); null when they are no
     * single step apart or the words say nothing.
     */
    static String[] direct(Graph g, Close c, String a, String b, String quote) {
        Kin said = chain(quote);
        String about = said == null ? null : describedSide(g, c.owners, a, b, quote, null);
        if (about == null) return null;
        String base = about.equals(a) ? b : a;
        Kin from = c.kin(base);
        if (from == null) return null;
        for (char s : new char[]{'d', 'u', 's', 'm'}) {
            Kin next = step(from, s, "");
            if (next == null || !same(next, said)) continue;
            return switch (s) {
                case 'd' -> new String[]{"parent", base, about};
                case 'u' -> new String[]{"parent", about, base};
                case 's' -> new String[]{"sibling", base, about};
                default -> new String[]{"spouse", base, about};
            };
        }
        return null;
    }

    /** The sex the last word of a relation says: "male" for "younger brother", "female" for "daughter"; "" for "parent" or no words. */
    static String sexOfWords(Kin k) { return k == null ? "" : k.sex(); }

    /** A described relative of the owner, "the owner of this library's father's father": the relation its words give; null for anybody else. */
    static Kin described(String label) { Chain c = describedSaid(label); return c == null ? null : c.kin(); }

    private static Chain describedSaid(String label) {
        String l = label == null ? "" : label.strip();
        if (!l.toLowerCase(Locale.ROOT).startsWith(OWNER + "'s ") && !l.toLowerCase(Locale.ROOT).startsWith(OWNER + "’s ")) return null;
        List<String> words = FamilyAccount.relationWords(l);
        if (words.isEmpty()) return null;
        List<String> pieces = new ArrayList<>();
        for (String p : l.substring(OWNER.length()).split("['’]s\\s+")) if (!p.isBlank()) pieces.add(p.strip().toLowerCase(Locale.ROOT));
        Kin k = ofWords(pieces);
        return k == null ? null : new Chain(k, LOOSE_WORD.matcher(pieces.get(pieces.size() - 1)).find());
    }

    private static final Pattern NOTE_ON_A_TITLE = Pattern.compile("(?i)the person who keeps this library notes:\\s*[\"“](.*)$");

    /** An entry filed under a page's title with the owner's note beside it ("… — the person who keeps this library notes: \"my great grandfather\""): the relation the note gives. */
    static Kin noted(String label) { Chain c = notedSaid(label); return c == null ? null : c.kin(); }

    private static Chain notedSaid(String label) {
        Matcher m = NOTE_ON_A_TITLE.matcher(label == null ? "" : label);
        return m.find() ? chainSaid(m.group(1)) : null;
    }

    /**
     * Which side of a claim the owner's words describe: the one that is not the owner or a described relative of the owner; where both are
     * other people, the one the words name. Null when that cannot be told.
     */
    private static String describedSide(Graph g, Set<String> owners, String a, String b, String quote, Finding f) {
        // the words are a described relative's own ("Ned Hale is a relative of the owner's mother's grandfather", filed from a note that
        // says "my mother's grandfather"): they say who that side is, and nothing of the other. The claim's own words name that side,
        // whoever the evidence has since found the described relative to be
        Chain said = chainSaid(quote);
        if (said != null) {
            boolean da = f != null && f.triple() != null && describedAs(f.triple().subject(), said.kin()) || describedAs(g, a, said.kin());
            boolean db = f != null && f.triple() != null && describedAs(f.triple().object(), said.kin()) || describedAs(g, b, said.kin());
            if (da != db) return da ? a : b;
        }
        boolean oa = ownerLike(g, owners, a), ob = ownerLike(g, owners, b);
        if (oa != ob) return oa ? b : a;
        if (oa) return null;
        boolean na = named(g, a, quote), nb = named(g, b, quote);
        return na == nb ? null : na ? a : b;
    }

    /** Whether an entry is, by its label or one of its other names, the owner's described relative of this relation. */
    private static boolean describedAs(Graph g, String id, Kin kin) {
        Graph.Node n = g.node(id);
        if (n == null) return false;
        List<String> names = new ArrayList<>(List.of(n.label()));
        names.addAll(n.aliases());
        for (String x : names) if (describedAs(x, kin)) return true;
        return false;
    }

    /** Whether a written name is the owner's described relative of this relation ("the owner of this library's mother's grandfather"). */
    private static boolean describedAs(String written, Kin kin) {
        Chain c = describedSaid(written);
        return c != null && !c.kin().path().isEmpty() && same(c.kin(), kin);
    }

    private static boolean ownerLike(Graph g, Set<String> owners, String id) {
        if (owners.contains(id)) return true;
        Graph.Node n = g.node(id);
        return n != null && n.label().strip().toLowerCase(Locale.ROOT).startsWith(OWNER);
    }

    /** Whether words write a person by one of their names. */
    private static boolean named(Graph g, String id, String quote) {
        Graph.Node n = g.node(id);
        if (n == null || quote == null) return false;
        List<String> names = new ArrayList<>(List.of(n.label()));
        names.addAll(n.aliases());
        String q = KanjiForms.modern(quote).replaceAll("[\\s　]+", "").toLowerCase(Locale.ROOT);
        for (String x : names) { String k = KanjiForms.modern(x).replaceAll("[\\s　]+", "").toLowerCase(Locale.ROOT); if (k.length() >= 2 && q.contains(k)) return true; }
        return false;
    }

    // ── no owner known ───────────────────────────────────────────────────────────────────────────────────────────────

    /** The people the family's own files name: those a claim is about whose source is an account, a note or a file the family read, or a family tree site. */
    private static Set<String> namedByFamilyFiles(Graph g, Map<String, Finding> byId) {
        Set<String> out = new HashSet<>();
        for (Graph.Edge e : g.edges()) {
            if (FamilyKin.gone(e)) continue;
            Finding f = byId.get(e.findingId());
            if (f == null || f.sources().stream().noneMatch(s -> familyFile(s.locator()))) continue;
            for (String id : List.of(e.from(), e.to())) { Graph.Node n = g.node(id); if (n != null && "person".equals(n.kind())) out.add(id); }
        }
        return out;
    }

    /** Whether a source is one of the family's own: something told, a file the family read, or a page of a family tree site. */
    static boolean familyFile(String locator) {
        String l = locator == null ? "" : locator.strip();
        if (l.startsWith("told://") || l.startsWith("file:")) return true;
        try {
            String host = URI.create(l).getHost();
            if (host == null) return false;
            String h = host.toLowerCase(Locale.ROOT);
            return Evidence.TREE_SITES.stream().anyMatch(t -> h.equals(t) || h.endsWith("." + t));
        } catch (IllegalArgumentException e) { return false; }
    }
}
