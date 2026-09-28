package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.researchzosho.librarian.profiles.GenealogyProfile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.WeakHashMap;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The questions about names and families that only the family can answer: whether a person written by a family name alone is one of the
 * people the library found, whether two entries under two family names are one person, how and when a name changed, a birth or an
 * adoptive parent, which of two families of one name is meant, a record written under a name that was not yet or no longer the person's,
 * how a name is read, and where the owner's own notes and a source disagree. Only close family is asked about ({@link FamilyClose}), and
 * the owner about their own notes whoever they concern; what is not asked stays on the person's page as not settled ({@link #notAsked}).
 * Each is worked out from the claims every time it is asked, with a code that stays the same while its evidence
 * does; an answer is the family's word, filed as claims or a merge that can be taken back, and the question goes away by itself. Only
 * "later" and the answers are written down, to {@code family/names-asked.tsv}.
 *
 * <p>What raises a question is always machine evidence: a name claim, a relation, a date, a reading a source gave. The library never
 * answers one by itself, and never joins two entries or picks a parent for the family.
 */
public final class FamilyNameQuestions {

    private FamilyNameQuestions() { }

    /** The kinds of question, in the order a sitting asks them. */
    public static final List<String> KINDS = List.of("notes-source", "family-name-alone", "one-person", "name-change-how", "name-change-when", "birth-or-adoptive", "which-family", "name-at-date", "reading");

    /** What a sitting says above a question of each kind. */
    static final Map<String, String> HEADINGS = Map.of(
            "notes-source", "YOUR NOTES AND A SOURCE DISAGREE",
            "family-name-alone", "A PERSON WRITTEN ONLY BY A FAMILY NAME",
            "one-person", "ONE PERSON OR TWO?",
            "name-change-how", "HOW DID THE NAME CHANGE?",
            "name-change-when", "WHEN DID THE NAME CHANGE?",
            "birth-or-adoptive", "WHICH KIND OF PARENT?",
            "which-family", "WHICH FAMILY?",
            "name-at-date", "A RECORD UNDER ANOTHER NAME",
            "reading", "HOW IS THE NAME READ?");

    /** The source of a claim the family's answer files: {@code told://family-answer/<code>}. */
    public static final String SOURCE = "told://family-answer/";

    /** One answer a question offers: its key (what is typed or posted), what it says, and what the library will do when it is given. */
    public record Option(String key, String says, String does) { }

    /**
     * One question. {@code code}: six hex characters of SHA-256 over its kind, its node ids and its claim ids ({@link #code}), and for whether
     * two entries are one person over the two alone, so it stays while what agrees for them changes; {@code people}:
     * the node ids it is about; {@code text}: the question in full sentences; {@code options}: the answers, each saying what it does;
     * {@code findings}: the claims it rests on; {@code turnsOn}: the other people it turns on without being about them, such as the other
     * father of a child written with two, so that whether those are one person is asked first ({@link #settles}).
     */
    public record Question(String code, String kind, List<String> people, String text, List<Option> options, List<String> findings, List<String> turnsOn) {
        public Question(String code, String kind, List<String> people, String text, List<Option> options, List<String> findings) { this(code, kind, people, text, options, findings, List.of()); }
        public Question { turnsOn = turnsOn == null ? List.of() : List.copyOf(turnsOn); }
    }

    /** The code of a question: the same kind about the same people and claims has the same code on every run. */
    public static String code(String kind, Collection<String> people, Collection<String> claims) {
        List<String> p = people == null ? List.of() : people.stream().filter(x -> x != null && !x.isBlank()).distinct().sorted().toList();
        List<String> c = claims == null ? List.of() : claims.stream().filter(x -> x != null && !x.isBlank()).distinct().sorted().toList();
        String key = kind + "|" + String.join(",", p) + "|" + String.join(",", c);
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8))).substring(0, 6); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    /**
     * Where "later" and the answers are written down: {@code code TAB answered|later TAB date TAB by TAB what TAB done}, {@code done} being what
     * the answer filed ({@code claims=F-…,F-…; merge=a>b; moved=F-…; apart=a|b; disputed=F-…}); a line "-" and a code takes one back.
     */
    public static Path askedFile(LibraryStore store) { return store.root().resolve("family").resolve("names-asked.tsv"); }

    /** Tests give the people a described person can be here; null asks {@link FamilyMentions#candidates}. */
    static volatile BiFunction<Graph, String, List<String>> candidatesForTests = null;

    // ── what waits ─────────────────────────────────────────────────────────────────────────────────────────────────────

    /** Every question that waits, of every kind: worked out from the claims, less those answered or put off. */
    public static List<Question> open(LibraryStore store) throws IOException { return questions(posed(store, null, false)); }

    /** The questions that wait about these people (node ids), such as the people a read just filed. Empty for no ids. */
    public static List<Question> about(LibraryStore store, Set<String> ids) throws IOException {
        if (ids == null || ids.isEmpty()) return List.of();
        return questions(posed(store, ids, false));
    }

    /**
     * The same, from genealogy's view of the library as the caller read it just now ({@link FamilyPeople#view}), so that a read, which holds
     * that view before and after it files, does not read the library again for its questions.
     */
    public static List<Question> about(LibraryStore store, Graph g, Set<String> ids) throws IOException {
        if (ids == null || ids.isEmpty()) return List.of();
        return questions(posed(store, g, ids, false));
    }

    /**
     * The questions about these people (node ids) when the family asks about them by name: those that wait, those put off (the family asks
     * about the person now, which is no asking by itself), and how each name came that the library worked out as the marriage's ({@link
     * FamilyNameHistory.Name#cameWithTheMarriage}), so that one answer changes it, as the names page says.
     */
    public static List<Question> byName(LibraryStore store, Set<String> ids) throws IOException {
        if (ids == null || ids.isEmpty()) return List.of();
        return questions(posed(store, FamilyPeople.view(store), ids, true, true, null));
    }

    /**
     * The questions about these people (node ids) that are not asked, because they are about people outside close family ({@link
     * FamilyClose}): what is not settled about them, each with what the sources say. A person's page shows them; nobody is asked.
     */
    public static List<Question> notAsked(LibraryStore store, Set<String> ids) throws IOException {
        if (ids == null || ids.isEmpty()) return List.of();
        return questions(posed(store, FamilyPeople.view(store), ids, false, false, false));
    }

    /** The questions that wait, worked out once: those the family is asked, and those not asked because they are about people outside close family. */
    public record Asking(List<Question> asked, List<Question> notAsked) { }

    /**
     * Whom a question asked of nobody is held back for, as the start of a sentence that goes on "outside your close family": "Tom Hale is",
     * "Tom Hale and Ann Hart are", "the members of the Hale family are".
     */
    public static String outsideSaid(Graph g, Question q) {
        List<String> who = new ArrayList<>();
        if (q.kind().equals("which-family")) {
            for (String f : q.people()) who.add("the members of " + FamilyHouses.labelOf(g, f));
            return who.isEmpty() ? "the people it is about are" : String.join(" and ", who) + " are";
        }
        List<String> ids = q.kind().equals("one-person") ? q.people() : q.people().isEmpty() ? List.of() : List.of(q.people().get(0));
        for (String id : ids) { String l = FamilyKin.label(g, id); if (!who.contains(l)) who.add(l); }
        if (who.isEmpty()) return "the person it is about is";
        return who.size() == 1 ? who.get(0) + " is" : String.join(", ", who.subList(0, who.size() - 1)) + " and " + who.get(who.size() - 1) + " are";
    }

    /** {@link #open} and {@link #notAsked} in one working out; {@code ids} null for everybody. */
    public static Asking asking(LibraryStore store, Set<String> ids) throws IOException {
        Graph g = FamilyPeople.view(store);
        List<Posed> all = posed(store, g, ids, false, false, null);
        if (all.isEmpty()) return new Asking(List.of(), List.of());
        Work w = new Work(store, g, ids, false);
        List<Question> asked = new ArrayList<>(), not = new ArrayList<>();
        for (Posed p : all) (w.putToFamily(p.q()) ? asked : not).add(p.q());
        return new Asking(asked, not);
    }

    /** What a list of questions says of those not asked: how many, why, and where each person's page shows them; "" when there are none. */
    public static String notAskedSaid(int n) {
        if (n == 0) return "";
        return (n == 1 ? "One more thing about names and families is not settled. Your library asks nobody about it, because it is"
                : n + " more things about names and families are not settled. Your library asks nobody about them, because they are")
                + " about people outside your close family: you, your parents, brothers and sisters, grandparents and great-grandparents, and the brothers,"
                + " sisters, children, husbands and wives of any of those. A person's page shows what the sources say. To see it: researchzosho genealogy names \"<their name>\"";
    }

    private static List<Question> questions(List<Posed> ps) { return ps.stream().map(Posed::q).toList(); }

    /** The questions worked out now, less those answered and still standing, and less those put off unless {@code withPutOff}. */
    private static List<Posed> posed(LibraryStore store, Set<String> scope, boolean withPutOff) throws IOException {
        return posed(store, FamilyPeople.view(store), scope, withPutOff);
    }

    private static List<Posed> posed(LibraryStore store, Graph g, Set<String> scope, boolean withPutOff) throws IOException { return posed(store, g, scope, withPutOff, false, true); }

    /**
     * The same; {@code workedOutToo}: with how each name came that the library worked out as the marriage's. {@code put}: true for only the
     * questions the family is asked, about close family ({@link Work#putToFamily}); false for only those that are not asked; null for both, where the
     * family names the person or answers by the code.
     */
    private static List<Posed> posed(LibraryStore store, Graph g, Set<String> scope, boolean withPutOff, boolean workedOutToo, Boolean put) throws IOException {
        if (!on(store)) return List.of();
        Work w = new Work(store, g, scope, workedOutToo);
        List<Posed> all = w.all();
        if (put != null) all = all.stream().filter(p -> w.putToFamily(p.q()) == put).toList();
        if (all.isEmpty()) return all;
        Map<String, String[]> asked = asked(store);
        Map<String, String> merges = Graph.merges(store);
        Map<String, List<String>> into = joinedInto(merges);
        List<Posed> out = new ArrayList<>();
        for (Posed p : all) {
            String[] a = answerOf(p, asked, into);
            if (a == null) { out.add(p); continue; }
            if (a[0].equals("later")) { if (withPutOff) out.add(p); continue; }
            if (!stands(a.length > 4 ? a[4] : "", w.idx, w.apart, merges)) out.add(p);   // an answer taken back or disputed: the question is back
        }
        return out;
    }

    /** Each entry and the entries joined into it, from the joins ({@code merges}: joined → the entry it went into). */
    private static Map<String, List<String>> joinedInto(Map<String, String> merges) {
        Map<String, List<String>> into = new HashMap<>();
        for (String k : merges.keySet()) { String t = k; for (int i = 0; i < 20 && merges.containsKey(t); i++) t = merges.get(t); into.computeIfAbsent(t, x -> new ArrayList<>()).add(k); }
        return into;
    }

    /** What was answered or put off for a question: under its code, or under the code it had about an entry since joined into another. */
    private static String[] answerOf(Posed p, Map<String, String[]> asked, Map<String, List<String>> into) {
        String[] a = asked.get(p.q().code());
        if (a == null && !into.isEmpty()) for (String c : p.earlier(into)) if ((a = asked.get(c)) != null) break;
        return a;
    }

    /** Whether genealogy is on for this library: with it off, nothing is asked about names and families, and no answer is filed. */
    static boolean on(LibraryStore store) {
        try { return Profiles.isEnabled(store, new GenealogyProfile().name()); } catch (IOException e) { return true; }
    }

    /** What an answer says when genealogy is off for the library. */
    static final String OFF = "The genealogy field is turned off for this library, so it asks no questions about names and families and files no answer to one. To turn it on: researchzosho profile enable genealogy";

    /**
     * Whether what an answer filed still stands: its claims neither disputed nor retired, its merge not taken back, its pairs still apart, and
     * the claim it disputed still disputed.
     */
    private static boolean stands(String done, FamilyNameHistory.Index idx, Set<String> apart, Map<String, String> merges) {
        for (Map.Entry<String, String> d : FamilyDetail.parse(done).entrySet()) if (!actStands(d.getKey(), d.getValue(), idx, apart, merges)) return false;
        return true;
    }

    /** The things an answer did that can be taken back one by one: what {@link #actStands} tests. */
    private static final Set<String> ACTS = Set.of("claims", "merge", "moved", "apart", "disputed");

    /** Whether an answer did something and none of it stands any more: each claim it filed gone, its join undone, its pairs not apart. */
    private static boolean allTakenBack(String done, FamilyNameHistory.Index idx, Set<String> apart, Map<String, String> merges) {
        boolean any = false;
        for (Map.Entry<String, String> d : FamilyDetail.parse(done).entrySet()) {
            if (!ACTS.contains(d.getKey())) continue;
            if (d.getKey().equals("claims")) {
                for (String id : d.getValue().split(",")) { if (id.isBlank()) continue; any = true; if (!gone(idx.finding(id.strip()))) return false; }
                continue;
            }
            any = true;
            if (actStands(d.getKey(), d.getValue(), idx, apart, merges)) return false;
        }
        return any;
    }

    /** Whether one thing an answer did still stands; what is no act of its own (the entries it made, the lines it wrote beside it) always does. */
    private static boolean actStands(String key, String value, FamilyNameHistory.Index idx, Set<String> apart, Map<String, String> merges) {
        switch (key) {
            case "claims" -> {
                for (String id : value.split(",")) if (!id.isBlank() && gone(idx.finding(id.strip()))) return false;
                return true;
            }
            case "merge" -> { String[] m = value.split(">", 2); return m.length == 2 && m[1].equals(merges.get(m[0])); }
            case "moved" -> {
                // each claim the answer moved is still about the one it moved it to
                for (String id : value.split(",")) {
                    if (id.isBlank()) continue;
                    Finding f = idx.finding(id.strip());
                    String[] m = FamilySplit.movedByAnswer(f);
                    if (gone(f) || m == null || !(m[1].equals(f.triple().subject()) || m[1].equals(f.triple().object()))) return false;
                }
                return true;
            }
            case "apart" -> { for (String[] m : pairs(value)) if (!apart.contains(Graph.pair(m[0], m[1]))) return false; return true; }
            case "disputed" -> {
                for (String id : value.split(",")) { Finding f = idx.finding(id.strip()); if (!id.isBlank() && (f == null || f.state() != Finding.State.disputed)) return false; }
                return true;
            }
            default -> { return true; }
        }
    }

    private static boolean gone(Finding f) { return f == null || f.state() == Finding.State.disputed || f.state() == Finding.State.retired || f.state() == Finding.State.superseded; }

    /** The pairs an answer wrote down as two people or two families: {@code a|b} or {@code a|b,a|c}. */
    private static List<String[]> pairs(String value) {
        List<String[]> out = new ArrayList<>();
        for (String one : value.split(",")) { String[] m = one.strip().split("\\|", 2); if (m.length == 2 && !m[0].isBlank() && !m[1].isBlank()) out.add(m); }
        return out;
    }

    // ── the answers ────────────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * The family's answer to a question: {@code choice} is an option's key or its number ("later" puts it off), {@code year} the year for a
     * question of when, {@code teller} who answered (a patron's or the user's name). Files the family's word and says in a sentence what was
     * done. Throws IllegalArgumentException, with a sentence, for a question that does not wait or an answer it does not offer.
     */
    public static String answer(LibraryStore store, String code, String choice, String year, String teller) throws IOException { return answer(store, code, choice, year, teller, ""); }

    /**
     * The same, with {@code name}: the name typed for the answer that names somebody else in the library ({@link #SOMEONE}). It must find one
     * person in the library ({@link FamilyQuestions#find}); when it finds nobody or several, the IllegalArgumentException says so in a sentence.
     */
    public static String answer(LibraryStore store, String code, String choice, String year, String teller, String name) throws IOException {
        if (!on(store)) throw new IllegalArgumentException(OFF);
        String c = code == null ? "" : code.strip().toLowerCase(Locale.ROOT);
        Posed p = posed(store, FamilyPeople.view(store), null, true, true, null).stream().filter(x -> x.q().code().equals(c)).findFirst().orElse(null);
        if (p == null) throw new IllegalArgumentException("No question about names or families with the code " + code + " is waiting. It was answered, or its facts changed. The command researchzosho genealogy who asks the questions that wait.");
        String who = teller == null || teller.isBlank() ? "the person who keeps this library" : teller.strip();
        String typed = choice == null ? "" : choice.strip();
        if (typed.equalsIgnoreCase("later")) {
            append(store, p.q().code() + "\tlater\t" + LocalDate.now() + "\t" + clean(who) + "\t" + clean(Acquisitions.compress(p.q().text(), 160)) + "\t");
            return "Put off. The library does not ask this question again by itself. To have it asked again: researchzosho genealogy who --reopen " + p.q().code();
        }
        Option o = option(p.q(), typed);
        if (o == null) throw new IllegalArgumentException("\"" + typed + "\" is not one of the answers to this question. The answers are: "
                + String.join(", ", keys(p.q())) + ", or later.");
        String y = year == null ? "" : year.strip();
        if (o.key().equals("year")) {
            if (y.isEmpty() && typed.matches("\\d{4}")) y = typed;
            if (FamilyDate.parse(y) == null) throw new IllegalArgumentException("Give the year, for example 1932.");
        }
        Act act = p.acts().get(o.key());
        Set<String> had = new HashSet<>(Vocabulary.read(Graph.nodesFile(store)).terms().keySet());
        Done done = act.run(new Ctx(store, p.q().code(), y, who, o.says(), name == null ? "" : name.strip()));
        // the entries the answer made (a family, a described person, a name), so that taking the answer back takes them away too
        List<String> made = new ArrayList<>();
        for (String k : Vocabulary.read(Graph.nodesFile(store)).terms().keySet()) if (!had.contains(k)) made.add(k.replace(",", "%2C"));
        String filed = done.done() + (made.isEmpty() ? "" : (done.done().isBlank() ? "" : "; ") + FamilyDetail.text(Map.of("made", String.join(",", made))));
        append(store, p.q().code() + "\tanswered\t" + LocalDate.now() + "\t" + clean(who) + "\t" + clean(done.what()) + "\t" + clean(filed));
        return done.said();
    }

    /** The entries an answer made, as its line in the list of answers keeps them. */
    private static List<String> made(Map<String, String> done) {
        List<String> out = new ArrayList<>();
        for (String k : done.getOrDefault("made", "").split(",")) if (!k.isBlank()) out.add(k.strip().replace("%2C", ","));
        return out;
    }

    /**
     * The entries an answer made that nothing stands on any more (no claim that counts, no join), taken out of nodes.md; their names in
     * words. An entry something else now names stays.
     */
    private static List<String> takeAwayMade(LibraryStore store, List<String> made) throws IOException {
        if (made.isEmpty()) return List.of();
        Graph g = FamilyPeople.view(store);
        Set<String> used = new HashSet<>();
        for (Graph.Edge e : g.edges()) if (!FamilyKin.gone(e)) { used.add(e.from()); used.add(e.to()); }
        for (Map.Entry<String, String> m : Graph.merges(store).entrySet()) { used.add(m.getKey()); used.add(m.getValue()); }
        Vocabulary nodes = Vocabulary.read(Graph.nodesFile(store));
        List<String> out = new ArrayList<>();
        for (String id : made) {
            Vocabulary.Term t = nodes.get(id);
            // an entry somebody gave other names to since is theirs now, and stays
            if (t == null || used.contains(id) || !t.also().isEmpty()) continue;
            nodes.remove(id);
            out.add(t.description().contains(":") ? t.description().substring(t.description().indexOf(':') + 1).strip() : id);
        }
        if (!out.isEmpty()) Graph.writeNodes(store, nodes);
        return out;
    }

    /** The option a typed or posted choice means: its key, or its number among the answers (later aside). */
    static Option option(Question q, String typed) {
        String t = typed == null ? "" : typed.strip();
        List<Option> real = q.options().stream().filter(o -> !o.key().equals("later")).toList();
        for (Option o : real) if (o.key().equalsIgnoreCase(t)) return o;
        if (t.matches("\\d{1,2}")) { int n = Integer.parseInt(t); if (n >= 1 && n <= real.size()) return real.get(n - 1); }
        // a year typed for a question that takes one
        if (t.matches("\\d{4}")) for (Option o : real) if (o.key().equals("year")) return o;
        return null;
    }

    private static List<String> keys(Question q) {
        List<String> out = new ArrayList<>();
        int n = 0;
        for (Option o : q.options()) if (!o.key().equals("later")) out.add(String.valueOf(++n) + " (" + o.key() + ")");
        return out;
    }

    /** An answer posted from the page "Who is who" ({@code kind=names&code=…&choice=…&year=…&name=…}); the address to go on to. Only those who may write may answer. */
    public static String post(LibraryStore store, Patrons.Patron patron, Map<String, String> form) throws IOException {
        Pages.writersOnly(store, patron);
        String code = form.getOrDefault("code", "").strip(), choice = form.getOrDefault("choice", "").strip();
        if (code.isEmpty() || choice.isEmpty()) throw ProtocolError.invalidArgs("That answer is not known. Go back and try again.");
        String said;
        try { said = answer(store, code, choice, form.getOrDefault("year", ""), patron == null ? "" : patron.name(), form.getOrDefault("name", "")); }
        catch (IllegalArgumentException e) { throw ProtocolError.invalidArgs(e.getMessage()); }
        return "/who?show=" + URLEncoder.encode("done:" + said, StandardCharsets.UTF_8);
    }

    private static final ObjectMapper M = new ObjectMapper();

    /** A question as library_who shows it: {@code kind = "names"}, its code, its words, and each option with what it does. */
    public static ObjectNode forTool(Question q) {
        ObjectNode o = M.createObjectNode();
        o.put("kind", "names");
        o.put("question_kind", q.kind());
        o.put("code", q.code());
        o.put("text", q.text());
        ArrayNode opts = o.putArray("options");
        for (Option op : q.options()) opts.addObject().put("key", op.key()).put("says", op.says()).put("does", op.does());
        ArrayNode people = o.putArray("people");
        for (String p : q.people()) people.add(p);
        ArrayNode f = o.putArray("findings");
        for (String x : q.findings()) f.add(x);
        return o;
    }

    /**
     * {@code genealogy who --answered}: the answers the family gave to questions about names and families, each with the command that takes
     * it back, and the questions put off, each with the command that asks it again. Returns the command's exit code.
     */
    public static int cliAnswered(LibraryStore store, PrintStream out) throws IOException {
        Map<String, String[]> asked = asked(store);
        if (asked.isEmpty()) {
            out.println("No question about names or families has been answered yet. The command researchzosho genealogy who asks the ones that wait.");
            return 0;
        }
        Graph g = FamilyPeople.view(store);
        Map<String, String> merges = Graph.merges(store);
        Set<String> apart = Graph.differentPairs(store);
        List<Map.Entry<String, String[]>> answered = asked.entrySet().stream().filter(e -> e.getValue()[0].equals("answered")).toList();
        List<Map.Entry<String, String[]>> later = asked.entrySet().stream().filter(e -> e.getValue()[0].equals("later")).toList();
        if (!answered.isEmpty()) {
            out.println("Your answers to questions about names and families, each with the command that takes it back:\n");
            for (Map.Entry<String, String[]> e : answered) {
                String[] a = e.getValue();
                String done = a.length > 4 ? a[4] : "";
                out.println("  " + a[3] + "   [code " + e.getKey() + ", answered on " + a[1] + (a[2].isBlank() ? "" : " by " + a[2]) + "]");
                FamilyNameHistory.Index idx = FamilyNameHistory.of(g);
                if (allTakenBack(done, idx, apart, merges)) {
                    // nothing of it stands: the question is asked again already, and the one command left takes the answer off this list
                    out.println("       All of this answer was taken back, so the question is asked again, while its facts are the same: researchzosho genealogy who");
                    out.println("       To take the answer off this list: researchzosho genealogy who --reopen " + e.getKey());
                    out.println();
                    continue;
                }
                if (!stands(done, idx, apart, merges)) out.println("       Part of this answer was taken back, so the question is asked again, while its facts are the same: researchzosho genealogy who");
                for (String l : takeBack(g, e.getKey(), done)) out.println("       " + l);
                out.println();
            }
        }
        if (!later.isEmpty()) {
            out.println("Questions you put off. The library does not ask them again by itself:\n");
            for (Map.Entry<String, String[]> e : later) {
                out.println("  " + e.getValue()[3] + "   [code " + e.getKey() + ", put off on " + e.getValue()[1] + "]");
                out.println("       To have it asked again: researchzosho genealogy who --reopen " + e.getKey() + "\n");
            }
        }
        return 0;
    }

    /**
     * The commands that take one answer back, from what it filed, and the one that takes back the whole answer and asks the question again:
     * {@code genealogy who --reopen <code>} ({@link #reopen}).
     */
    private static List<String> takeBack(Graph g, String code, String done) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, String> d : FamilyDetail.parse(done).entrySet()) {
            switch (d.getKey()) {
                case "claims" -> {
                    List<String> ids = new ArrayList<>();
                    for (String id : d.getValue().split(",")) if (!id.isBlank()) ids.add(id.strip().replaceFirst("^(F-\\d+).*", "$1"));
                    // a fact a source said again after the answer is that source's too: disputing it would take the source's fact out with it
                    FamilyNameHistory.Index idx = FamilyNameHistory.of(g);
                    List<String> shared = new ArrayList<>();
                    for (String id : d.getValue().split(",")) {
                        Finding f = id.isBlank() ? null : idx.finding(id.strip());
                        if (f != null && f.sources().stream().anyMatch(s -> s.locator() != null && !s.locator().startsWith(SOURCE))) shared.add(f.id().replaceFirst("^(F-\\d+).*", "$1"));
                    }
                    if (!ids.isEmpty() && shared.isEmpty()) out.add("It saved " + (ids.size() == 1 ? "the fact " : "the facts ") + String.join(", ", ids) + " as your answer. To take " + (ids.size() == 1 ? "it" : "one") + " back, dispute it and give your reason: researchzosho dispute " + ids.get(0) + " \"the reason\"");
                    else if (!ids.isEmpty()) out.add("It saved " + (ids.size() == 1 ? "the fact " : "the facts ") + String.join(", ", ids) + " as your answer. A source you read later says " + String.join(", ", shared)
                            + " too. Taking back the whole answer, below, keeps " + (shared.size() == 1 ? "that fact" : "those facts") + " as the source gives " + (shared.size() == 1 ? "it" : "them") + ".");
                }
                case "merge" -> out.add("It joined two names into one. To take that back: researchzosho graph unmerge \"" + labelIn(g, d.getValue().split(">", 2)[0]) + "\"");
                case "moved" -> {
                    List<String> ids = new ArrayList<>();
                    for (String id : d.getValue().split(",")) if (!id.isBlank()) ids.add(id.strip().replaceFirst("^(F-\\d+).*", "$1"));
                    if (!ids.isEmpty()) out.add("It moved " + (ids.size() == 1 ? "the fact " : "the facts ") + String.join(", ", ids) + ", which one source's words give, to the person you named. Taking back the whole answer, below, moves " + (ids.size() == 1 ? "it" : "them") + " back.");
                }
                case "apart" -> {
                    for (String[] p : pairs(d.getValue())) out.add("It wrote down that “" + labelIn(g, p[0]) + "” and “" + labelIn(g, p[1]) + "” are two people, not one. To take that back: researchzosho genealogy different \"" + labelIn(g, p[0]) + "\" \"" + labelIn(g, p[1]) + "\" --undo");
                }
                case "disputed" -> out.add("It marked " + String.join(", ", Arrays.stream(d.getValue().split(",")).map(x -> x.strip().replaceFirst("^(F-\\d+).*", "$1")).toList()) + " as disputed. Taking back the whole answer, below, undoes that.");
                case "split" -> out.add("It asks about each passage of those words on its own.");
                case "command" -> out.add("It gave you this command to give yourself: " + d.getValue());
                case "note" -> out.add("It added a note to " + d.getValue().replaceFirst("^(F-\\d+).*", "$1") + ".");
                default -> { }
            }
        }
        out.add(done.isBlank() || FamilyDetail.parse(done).keySet().stream().allMatch(k -> k.equals("command"))
                ? "To have the question asked again: researchzosho genealogy who --reopen " + code
                : "To take back the whole answer and have the question asked again: researchzosho genealogy who --reopen " + code);
        return out;
    }

    private static String labelIn(Graph g, String id) {
        Graph.Node n = g.node(id);
        if (n != null) return n.label();
        Vocabulary.Term t = g.curated().get(id);
        return t != null && t.description().contains(":") ? t.description().substring(t.description().indexOf(':') + 1).strip() : id;
    }

    /** What was answered or put off, by code, as {@link #askedFile} holds it now: code → {answered|later, date, by, what, done}. */
    public static Map<String, String[]> asked(LibraryStore store) throws IOException {
        Map<String, String[]> out = new LinkedHashMap<>();
        Path f = askedFile(store);
        if (!Files.exists(f)) return out;
        for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
            String[] c = line.split("\t", -1);
            if (c.length >= 2 && c[0].equals("-")) { out.remove(c[1]); continue; }
            if (c.length < 2 || c[0].isBlank()) continue;
            String[] v = new String[5];
            for (int i = 0; i < 5; i++) v[i] = c.length > i + 1 ? c[i + 1] : "";
            out.put(c[0], v);
        }
        return out;
    }

    /** What {@link #reopen} came to: whether the question is open again, and the sentence that says so and what was taken back. */
    public record Reopened(boolean open, String said) { }

    /**
     * A question put off, or answered, asked again. An answer is taken back whole: the claims it filed as the family's word are retired (a
     * claim a source said again after the answer loses only the answer, and stays as that source's fact), the join it made is undone, the
     * two it wrote down as apart may be put forward as one again, a claim it disputed counts as it did before, and a note it put on a record
     * comes off; the line that answered it is taken back too. The question then waits again, while its facts are the same. Nothing a source
     * gave is removed.
     */
    public static Reopened reopen(LibraryStore store, String code) throws IOException {
        String c = code == null ? "" : code.strip().toLowerCase(Locale.ROOT);
        String[] a = asked(store).get(c);
        if (a == null) return new Reopened(false, "No answer and no put-off question has the code " + (code == null ? "" : code.strip()) + ". researchzosho genealogy who --answered lists them, each with its code.");
        List<String> undone = a[0].equals("answered") ? takeBackAnswer(store, c, a.length > 4 ? a[4] : "") : List.of();
        append(store, "-\t" + c + "\t" + LocalDate.now() + "\ttaken back");
        return new Reopened(true, "The question " + c + " is open again." + (undone.isEmpty() ? "" : " Your answer is taken back: " + String.join("; ", undone) + ".")
                + " The next researchzosho genealogy who asks it, while its facts are the same.");
    }

    /** What an answer filed, taken back; each thing taken back in words. */
    private static List<String> takeBackAnswer(LibraryStore store, String code, String done) throws IOException {
        List<String> out = new ArrayList<>();
        String why = "the family took back its answer to the question " + code + " about names";
        Map<String, String> d = FamilyDetail.parse(done);
        Council council = new Council(store);
        for (String id : d.getOrDefault("claims", "").split(",")) {
            if (id.isBlank()) continue;
            Finding f = store.finding(id.strip());
            if (f == null || gone(f)) continue;
            // a source that said the same fact later was added to the answer's claim: only the answer comes off it, and the source's fact stays
            List<Finding.Source> others = f.sources().stream().filter(s -> s.locator() == null || !s.locator().equals(SOURCE + code)).toList();
            if (!others.isEmpty()) {
                keepSourcesOnly(store, f, others, why);
                List<String> names = others.stream().map(s -> FamilyChecks.from(s.locator())).distinct().toList();
                out.add("the fact " + f.id().replaceFirst("^(F-\\d+).*", "$1") + " stays, because " + String.join(" and ", names) + (names.size() == 1 ? " says" : " say") + " it too; only your answer is taken off it");
                continue;
            }
            council.retire(f.id());
            out.add("the fact " + f.id().replaceFirst("^(F-\\d+).*", "$1") + " is retired");
        }
        List<String> moved = new ArrayList<>();
        for (String id : d.getOrDefault("moved", "").split(",")) if (!id.isBlank()) moved.add(id.strip());
        for (String[] b : FamilySplit.moveBack(store, code, moved)) out.add("the fact " + b[0].replaceFirst("^(F-\\d+).*", "$1") + " is about “" + b[1] + "” again, as its words write it");
        String merge = d.getOrDefault("merge", "");
        if (!merge.isBlank()) {
            String[] m = merge.split(">", 2);
            if (m.length == 2 && m[1].equals(Graph.merges(store).get(m[0]))) {
                // written as the library's own take-back, not the family's "not this one": the one they chose stays among the answers
                Graph.unmerge(store, m[0], "", REOPEN_BY, why);
                Graph g = FamilyPeople.view(store);
                out.add("“" + labelIn(g, m[0]) + "” and “" + labelIn(g, m[1]) + "” are separate again");
            }
        }
        if (!d.getOrDefault("apart", "").isBlank()) {
            Graph g = FamilyPeople.view(store);
            for (String[] p : pairs(d.get("apart")))
                if (Graph.notDifferent(store, labelIn(g, p[0]), labelIn(g, p[1]), "person")) out.add("“" + labelIn(g, p[0]) + "” and “" + labelIn(g, p[1]) + "” are no longer written down as apart");
        }
        String[] disputed = d.getOrDefault("disputed", "").split(","), was = d.getOrDefault("was", "").split(",");
        for (int i = 0; i < disputed.length; i++)
            if (!disputed[i].isBlank() && restore(store, disputed[i].strip(), i < was.length ? was[i].strip() : "", why)) out.add("the fact " + disputed[i].strip().replaceFirst("^(F-\\d+).*", "$1") + " counts again as it did before");
        for (String[] dropped : pairs(d.getOrDefault("dropped", ""))) {
            Graph.alias(store, dropped[0], List.of(dropped[1]));
            out.add("“" + dropped[1] + "” is a name of " + dropped[0] + " again");
        }
        if (takeNotesOff(store, code)) out.add("the note it put on " + (d.containsKey("note") ? d.get("note").replaceFirst("^(F-\\d+).*", "$1") : "the record") + " is taken off");
        for (String made : takeAwayMade(store, made(d))) out.add("“" + made + "”, which the answer made, is taken away");
        // the lines the answer wrote for the questions it settled beside its own (a described person's own question) go with it
        for (String also : d.getOrDefault("also", "").split(",")) if (!also.isBlank()) append(store, "-\t" + also.strip() + "\t" + LocalDate.now() + "\ttaken back with " + code);
        return out;
    }

    /**
     * Where an option's words name the command that takes the whole answer back, {@code genealogy who --reopen} with the question's code,
     * which is known once the question is: every answer is taken back whole by it, and the question is asked again.
     */
    static final String REOPEN_HERE = "{reopen}";

    /** Who takes back a join when the family takes back an answer: the library's own command, which is no refusal of the one they chose. */
    static final String REOPEN_BY = "genealogy who --reopen";

    /**
     * A claim an answer disputed, counted again as it was before the answer: accepted again when it had been accepted, else a draft again,
     * with a note that says so. False when it is not disputed any more.
     */
    private static boolean restore(LibraryStore store, String id, String was, String why) throws IOException {
        Finding f = store.finding(id);
        if (f == null) f = store.findingByCode(id);
        if (f == null || f.state() != Finding.State.disputed) return false;
        if (was.equals("accepted")) { new Council(store).accept(f.id()); return true; }
        List<Finding.Note> notes = new ArrayList<>(f.notes());
        notes.add(new Finding.Note("dispute-taken-back", "person", LocalDate.now().toString(), why));
        Finding back = new Finding(f.id(), f.title(), f.subjects(), Finding.State.draft, f.claimType(), f.confidence(), f.writer(), f.recordedAt(), f.validAsOf(), f.volatility(), f.reviewBy(),
                f.sources(), f.supersedes(), null, f.body() + "\nDISPUTE TAKEN BACK: person (" + LocalDate.now() + "): " + why + "\n", f.triple(), notes);
        store.write(back);
        new LibrarianIndex(store).upsert(back);
        store.regenerateIndex();
        return true;
    }

    /**
     * A claim an answer filed that a source said again later ({@link Evidence#toldAgain}), with the answer taken off: the sources that said it
     * stay, with their words and the claim's detail, and the claim is a fact they gave, not marked right by the family any more.
     */
    private static void keepSourcesOnly(LibraryStore store, Finding f, List<Finding.Source> sources, String why) throws IOException {
        List<Finding.Note> notes = new ArrayList<>(f.notes());
        notes.add(new Finding.Note("answer-taken-back", "person", LocalDate.now().toString(), why + "; the fact stays with the sources that say it"));
        Finding back = new Finding(f.id(), f.title(), f.subjects(), Finding.State.draft, f.claimType(), f.confidence(), f.writer(), f.recordedAt(), f.validAsOf(), f.volatility(), f.reviewBy(),
                sources, f.supersedes(), null, f.body() + "\nANSWER TAKEN BACK: person (" + LocalDate.now() + "): " + why + ". The fact stays, because its other sources say it.\n", f.triple(), notes);
        store.write(back);
        Changes.append(store, "finding", f.id(), "edited", "the family's answer taken off; its other sources stay");
        new LibrarianIndex(store).upsert(back);
        store.regenerateIndex();
    }

    /** The notes an answer to this question put on records ("written later, under the name carried then"), taken off again. Whether there were any. */
    private static boolean takeNotesOff(LibraryStore store, String code) throws IOException {
        String mark = "(question " + code + ",";
        boolean any = false;
        for (Finding f : store.scanFindings().findings()) {
            List<Finding.Note> keep = f.notes().stream().filter(n -> !(n.kind().equals(NOTE) && n.text() != null && n.text().contains(mark))).toList();
            if (keep.size() == f.notes().size()) continue;
            store.write(new Finding(f.id(), f.title(), f.subjects(), f.state(), f.claimType(), f.confidence(), f.writer(), f.recordedAt(), f.validAsOf(), f.volatility(), f.reviewBy(),
                    f.sources(), f.supersedes(), f.review(), f.body(), f.triple(), keep));
            any = true;
        }
        return any;
    }

    private static void append(LibraryStore store, String line) throws IOException {
        Path f = askedFile(store);
        Files.createDirectories(f.getParent());
        Files.writeString(f, line + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static String clean(String s) { return s == null ? "" : s.replaceAll("[\\t\\r\\n]+", " ").strip(); }

    /** A sentence's words after a colon: its first letter in lower case, unless it is a name or a word in capitals. */
    private static String lower(String s) {
        if (s == null || s.length() < 2 || !Character.isUpperCase(s.charAt(0)) || Character.isUpperCase(s.charAt(1))) return s == null ? "" : s;
        return Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    // ── how a source writes a person by a family name ──────────────────────────────────────────────────────────────────

    /** At most this many people a question about a person written only by a family name lists by name; anybody else in the library is typed. */
    static final int SHOWN = 6;

    /** The key of the answer that names somebody else in the library, typed. */
    static final String SOMEONE = "someone";

    /**
     * How a source writes a person in one passage: the words shown ("Mr. Hart", "old Hart", "Hart", 遠藤氏), their key, the sex they give
     * ("male", "female" or "" when they give none), and whether they write the family ("the Harts", "the Hart family", 遠藤家).
     */
    record Form(String shown, String key, String sex, boolean family) { }

    /** One source's passages that write an entry of a family name alone in one form: the source, the form, and the claims, passage by passage. */
    record Group(String source, Form form, List<List<String>> passages) {
        List<String> claims() { List<String> out = new ArrayList<>(); for (List<String> p : passages) out.addAll(p); return out; }
        Group with(List<String> passage) { return new Group(source, form, List.of(passage)); }
    }

    /** The key under which an answer writes down that a group's passages are not all one person: the entry, the source and the form. */
    static String splitKey(String id, Group grp) { return code("split", List.of(id), List.of(grp.source(), grp.form().key())); }

    /**
     * The words written right before a name in Latin letters that say the person is a man, by default: titles, ranks, and the clergy's and
     * kin's words written as a title. The rule: the words a source writes a person with are its word on their sex, and these are the words
     * the rule knows. A title counts only written with a capital.
     */
    static final Set<String> MAN = Set.of("mr", "sir", "lord", "viscount", "baron", "count", "marquis", "marquess", "duke", "earl", "prince", "father", "rev", "revd", "reverend", "pastor", "brother", "monsieur", "herr");
    /** The same for a woman, by default. */
    static final Set<String> WOMAN = Set.of("mrs", "miss", "ms", "lady", "madame", "madam", "mme", "mlle", "mademoiselle", "dame", "sister", "viscountess", "baroness", "countess", "marchioness", "duchess", "princess", "mother", "frau");
    /** Titles written before a name that say no sex, by default: they are part of how a source writes a person all the same. */
    static final Set<String> TITLE = Set.of("dr", "doctor", "prof", "professor", "judge", "captain", "capt", "colonel", "col", "general", "major", "lieutenant", "lt", "sergeant", "sgt", "bishop");
    /** Words of age before a name, in any case, by default: "old Hart" and "young Hart" may be two people of one family. */
    static final Set<String> AGE = Set.of("old", "young", "elder", "younger", "little");
    /** Written after a name in Japanese, by default: of a man (遠藤氏, 遠藤翁), of a woman (遠藤夫人, 遠藤嬢, 遠藤女史), of either (遠藤さん, 遠藤様, 遠藤先生). */
    static final Set<String> MAN_AFTER = Set.of("氏", "翁");
    static final Set<String> WOMAN_AFTER = Set.of("夫人", "嬢", "女史");
    static final List<String> OTHER_AFTER = List.of("さん", "さま", "様", "殿", "君", "くん", "先生", "老");
    /** The words after a name that write the family, not a person: the Hart family, 遠藤家. */
    private static final Pattern FAMILY_AFTER = Pattern.compile("(?i)^\\s+(?:family|house|clan|line)(?![\\p{L}])");
    private static final List<String> FAMILY_AFTER_CHARS = List.of("家", "一族", "一門");
    private static final Pattern WORD = Pattern.compile("\\p{L}[\\p{L}'’-]*");

    /** Whether a word is one a source writes before a name as a title or a word of age ({@link #MAN}, {@link #WOMAN}, {@link #TITLE}, {@link #AGE}). */
    static boolean formWord(String word) {
        String k = word == null ? "" : word.replace(".", "").strip().toLowerCase(Locale.ROOT);
        return MAN.contains(k) || WOMAN.contains(k) || TITLE.contains(k) || AGE.contains(k);
    }

    /** The sex the title a name is written with says ("Mrs. Ruth Hale", "Pastor Hart", 遠藤夫人); "" when it has none that says one. */
    static String titleSex(String name) {
        String n = name == null ? "" : name.strip();
        Matcher m = WORD.matcher(n);
        if (m.find() && m.start() == 0 && Character.isUpperCase(n.codePointAt(0))) {
            String k = m.group().toLowerCase(Locale.ROOT);
            if (MAN.contains(k)) return "male";
            if (WOMAN.contains(k)) return "female";
        }
        for (String h : WOMAN_AFTER) if (n.endsWith(h) && n.length() > h.length()) return "female";
        for (String h : MAN_AFTER) if (n.endsWith(h) && n.length() > h.length()) return "male";
        return "";
    }

    /** A name without the titles and words of age written before it ({@link #formWord}): "Pastor Hart" is Hart, "old Mr. Hart" is Hart. */
    static String withoutFormWords(String name) {
        String n = name == null ? "" : name.strip();
        for (int guard = 0; guard < 4; guard++) {
            Matcher m = Pattern.compile("^(\\p{L}+)\\.?\\s+(\\S.*)$").matcher(n);
            if (!m.matches() || !formWord(m.group(1))) break;
            n = m.group(2).strip();
        }
        return n;
    }

    /**
     * Whether a label describes somebody in words instead of naming them: it begins with "a", "an" or "the" written small ("an old servant of the
     * Hart family", "the old miller"). A name in Latin letters never does.
     */
    static boolean descriptive(String label) { return label != null && label.strip().matches("(?s)(?:a|an|the)\\s+\\S.*"); }

    /** A sentence as written: the words and a full stop, unless they end a quoted sentence already. */
    static String sentence(String s) {
        String t = s.strip();
        if (t.endsWith("”") && t.length() > 1 && ".!?。".indexOf(t.charAt(t.length() - 2)) >= 0) return t;
        return t.endsWith(".") ? t : t + ".";
    }

    /** The family name alone, as a form: Hart, 遠藤. */
    static Form plainForm(String w) { return new Form(w, "|" + nameKey(w), "", false); }

    private static String nameKey(String s) {
        String sc = FamilyForms.script(s);
        return sc.equals("han") ? FamilyForms.hanKey(s) : sc.equals("kana") ? FamilyForms.kanaKey(s) : FamilyForms.latinKey(s);
    }

    /**
     * How words write a person by the family name {@code w}, or null when they do not show it. The name with the titles and words of age
     * written right before it ("Mr. Hart", "old Hart", and a title a scan wrote with a space inside, "M r. Hart", as Mr. Hart) or, in
     * Japanese, a rank before it and an honorific after it (子爵 遠藤, 遠藤氏); where the words write it only with a given name the library
     * knows ({@code nameWord}: Tom Hart, Hart Tom, an index's "Hart, Tom"; {@code wholeNames}: 遠藤健二), that whole name; where they write
     * only the family (the Harts, the Hart family, 遠藤家), the family. A name written alone is chosen before a whole name, and both before
     * the family, so that "Tom Hart's father, Hart" is Hart.
     */
    static Form formIn(String text, String w, Predicate<String> nameWord, Collection<String> wholeNames) {
        if (text == null || text.isBlank() || w == null || w.isBlank()) return null;
        String sc = FamilyForms.script(w);
        if (sc.equals("latin")) return latinForm(text, w, nameWord == null ? x -> false : nameWord);
        if (sc.equals("han") || sc.equals("kana")) return charsForm(text, w, wholeNames == null ? List.of() : wholeNames);
        return null;
    }

    private static Form latinForm(String text, String w, Predicate<String> nameWord) {
        String k = FamilyForms.latinKey(w);
        List<String> tok = new ArrayList<>();
        List<int[]> at = new ArrayList<>();
        Matcher m = WORD.matcher(text);
        while (m.find()) { tok.add(m.group()); at.add(new int[]{m.start(), m.end()}); }
        Form family = null, whole = null;
        for (int i = 0; i < tok.size(); i++) {
            String t = tok.get(i), bare = t.replaceFirst("['’]s$", "");
            String bk = FamilyForms.latinKey(bare);
            boolean plural = bare.equals(t) && (bk.equals(k + "s") || bk.equals(k + "es"));
            if (!bk.equals(k) && !plural) continue;
            if (plural || FAMILY_AFTER.matcher(text.substring(at.get(i)[1])).find()) {
                if (family == null) family = new Form(plural ? "the " + bare : bare + " family", "family|" + k, "", true);
                continue;
            }
            // a given name the library knows, written with it: before it (Tom Hart), or after it (Hart Tom, an index's "Hart, Tom")
            String given = "", givenAfter = "";
            int j = i - 1;
            if (j >= 0 && text.substring(at.get(j)[1], at.get(i)[0]).matches("[ \\u00a0]+") && nameWord.test(tok.get(j))) { given = tok.get(j) + " "; j--; }
            else if (i + 1 < tok.size()) {
                String sep = text.substring(at.get(i)[1], at.get(i + 1)[0]);
                if (sep.matches("[ \\u00a0]+|,[ \\u00a0]*") && nameWord.test(tok.get(i + 1))) givenAfter = (sep.startsWith(",") ? ", " : " ") + tok.get(i + 1);
            }
            // the titles and words of age right before it: two at most (old Mr. Hart)
            List<String> words = new ArrayList<>(), keys = new ArrayList<>();
            Set<String> sexes = new HashSet<>();
            int end = given.isEmpty() ? at.get(i)[0] : at.get(i - 1)[0];
            for (int n = 0; n < 2 && j >= 0; n++) {
                int[] hit = titleBefore(text, tok, at, j, end);
                if (hit == null) break;
                StringBuilder joined = new StringBuilder();
                for (int x = hit[0]; x <= j; x++) joined.append(tok.get(x));
                String key = joined.toString().toLowerCase(Locale.ROOT);
                words.add(0, joined + (hit[1] == 1 ? "." : ""));
                keys.add(0, key);
                if (MAN.contains(key)) sexes.add("male");
                if (WOMAN.contains(key)) sexes.add("female");
                end = at.get(hit[0])[0];
                j = hit[0] - 1;
            }
            Form f = new Form((words.isEmpty() ? "" : String.join(" ", words) + " ") + given + bare + givenAfter, String.join(" ", keys) + "|" + FamilyForms.latinKey(given + bare + givenAfter.replace(",", " ")),
                    sexes.size() == 1 ? sexes.iterator().next() : "", false);
            if (given.isEmpty() && givenAfter.isEmpty()) return f;
            if (whole == null) whole = f;
        }
        return whole != null ? whole : family;
    }

    /**
     * The title or word of age that ends at word {@code j}, with only spaces or a full stop between it and {@code end}: {its first word, 1 when
     * a full stop follows it}; null when there is none. A title a scan wrote with a space inside is two or three pieces of one or two letters
     * each ("M r." is Mr.). A title counts written with a capital; a word of age in any case.
     */
    private static int[] titleBefore(String text, List<String> tok, List<int[]> at, int j, int end) {
        String sep = text.substring(at.get(j)[1], end);
        if (!sep.matches("[ \\u00a0.]*")) return null;
        for (int len = 1; len <= 3 && j - len + 1 >= 0; len++) {
            int from = j - len + 1;
            StringBuilder b = new StringBuilder();
            boolean ok = true;
            for (int x = from; x <= j && ok; x++) {
                if (len > 1 && tok.get(x).length() > 2) ok = false;
                else if (x > from && !text.substring(at.get(x - 1)[1], at.get(x)[0]).matches("[ \\u00a0.]+")) ok = false;
                else b.append(tok.get(x));
            }
            if (!ok) break;
            String key = b.toString().toLowerCase(Locale.ROOT);
            if (AGE.contains(key) || Character.isUpperCase(b.codePointAt(0)) && (MAN.contains(key) || WOMAN.contains(key) || TITLE.contains(key))) return new int[]{from, sep.startsWith(".") ? 1 : 0};
        }
        return null;
    }

    private static Form charsForm(String text, String w, Collection<String> wholeNames) {
        String q = KanjiForms.modern(text), mw = KanjiForms.modern(w.strip());
        Form family = null, whole = null;
        List<String> after = new ArrayList<>(WOMAN_AFTER); after.addAll(MAN_AFTER); after.addAll(OTHER_AFTER);
        for (int at = q.indexOf(mw); at >= 0; at = q.indexOf(mw, at + mw.length())) {
            String name = mw;
            for (String f : wholeNames) { String mf = KanjiForms.modern(f).replaceAll("[\\s　・]+", ""); if (mf.length() > name.length() && q.startsWith(mf, at)) name = mf; }
            String rest = q.substring(at + name.length());
            if (name.equals(mw)) {
                String fw = FAMILY_AFTER_CHARS.stream().filter(rest::startsWith).findFirst().orElse(null);
                if (fw != null) { if (family == null) family = new Form(mw + fw, "family|" + nameKey(mw), "", true); continue; }
            }
            String before = q.substring(0, at).replaceAll("[\\s　]+$", "");
            String rank = FamilyNames.TITLES_BEFORE.stream().filter(before::endsWith).findFirst().orElse("");
            String hon = "";
            for (String h : after) if (rest.startsWith(h) && h.length() > hon.length()) hon = h;
            Form f = new Form((rank.isEmpty() ? "" : rank + " ") + name + hon, rank + "," + hon + "|" + nameKey(name), MAN_AFTER.contains(hon) ? "male" : WOMAN_AFTER.contains(hon) ? "female" : "", false);
            if (name.equals(mw)) return f;
            if (whole == null) whole = f;
        }
        return whole != null ? whole : family;
    }

    // ── asking at a terminal ───────────────────────────────────────────────────────────────────────────────────────────

    /** One question as a terminal shows it: its heading, its words, and the answers numbered, each with what it does. */
    public static String shown(Question q) {
        StringBuilder b = new StringBuilder(HEADINGS.getOrDefault(q.kind(), "A QUESTION ABOUT NAMES")).append("\n\n").append(q.text()).append("\n\n");
        int n = 0;
        for (Option o : q.options()) {
            if (o.key().equals("later")) continue;
            b.append("  ").append(++n).append(". ").append(o.says()).append("\n       ").append(o.does()).append("\n");
        }
        return b.toString();
    }

    /** Whether a question takes a year typed as its answer. */
    static boolean takesAYear(Question q) { return q.options().stream().anyMatch(o -> o.key().equals("year")); }

    static final String HOW = """
            Type the number of your answer and press Enter.
            Press Enter alone to skip it for now. The next researchzosho genealogy who asks it again.
            Type later   to put it off. The library does not ask it again by itself. researchzosho genealogy who --answered lists it.
            Type stop    to stop for now. Your answers so far are saved.
            """;

    /**
     * The sitting at a terminal: each question with its numbered answers, one at a time. After an answer, the questions it settled go and
     * the ones it raised about the same people (how and when the name of a person just joined changed) come next. {@code scope}: the node ids
     * the sitting is about, or null for everybody.
     */
    public static Sat sitting(LibraryStore store, List<Question> first, Set<String> scope, BufferedReader in, PrintStream out, String teller) throws IOException {
        List<Question> todo = new ArrayList<>(first);
        Set<String> seen = new HashSet<>();
        int answered = 0, at = 0;
        while (!todo.isEmpty()) {
            Question q = todo.remove(0);
            if (!seen.add(q.code())) continue;
            at++;
            out.println("\n──────── Question " + at + " of " + (at + todo.size()) + " about names and families ────────\n");
            out.print(shown(q));
            out.println();
            out.print(takesAYear(q) ? HOW.replaceFirst("Type the number of your answer and press Enter\\.", "Type the year, for example 1932, or the number of an answer, and press Enter.") : HOW);
            while (true) {
                out.print("\nYour answer: "); out.flush();
                String line = in.readLine();
                if (line == null) return new Sat(answered, true);
                String a = line.strip();
                String low = a.toLowerCase(Locale.ROOT);
                if (low.equals("stop") || low.equals("q") || low.equals("quit")) {
                    out.println("\nStopped. You answered " + answered + (answered == 1 ? " question" : " questions") + " about names and families, and your answers are saved. To go on later: researchzosho genealogy who");
                    return new Sat(answered, true);
                }
                if (a.isEmpty()) { out.println("Skipped for now. It stays open, and the next researchzosho genealogy who asks it again."); break; }
                Option chosen = low.equals("later") ? null : option(q, a);
                String typed = "";
                if (chosen != null && chosen.key().equals(SOMEONE)) {
                    typed = typedName(store, q, in, out);
                    if (typed == null) return new Sat(answered, true);
                    if (typed.isEmpty()) { out.println("Choose another answer, or type the number of that one again."); continue; }
                }
                try {
                    String said = low.equals("later") ? answer(store, q.code(), "later", "", teller) : answer(store, q.code(), a, a.matches("\\d{4}") ? a : "", teller, typed);
                    out.println(said);
                    if (!low.equals("later")) answered++;
                } catch (IllegalArgumentException e) { out.println(e.getMessage()); continue; }
                // what the answer changed: the questions still open among those left, and the ones it raised about the same people first
                List<Question> now = scope == null ? open(store) : byName(store, scope);
                // each question left is shown as it stands now: an answer that joined two entries or filed facts changes what it says
                Map<String, Question> still = new LinkedHashMap<>();
                for (Question x : now) still.put(x.code(), x);
                List<Question> next = new ArrayList<>();
                for (Question x : now) if (!seen.contains(x.code()) && todo.stream().noneMatch(t -> t.code().equals(x.code())) && x.people().stream().anyMatch(q.people()::contains)) next.add(x);
                for (Question x : todo) if (still.containsKey(x.code())) next.add(still.get(x.code()));
                todo = settledFirst(next);
                break;
            }
        }
        return new Sat(answered, false);
    }

    /**
     * The name typed for the answer that names somebody else in the library: asked until it finds one person ({@link #typedPerson}), each
     * time saying why a name did not. "" when Enter alone goes back to the answers; null when the input ended.
     */
    static String typedName(LibraryStore store, Question q, BufferedReader in, PrintStream out) throws IOException {
        String self = q.people().isEmpty() ? "" : q.people().get(0);
        out.println("Type the name of the person as your library writes it, and press Enter. Press Enter alone to choose another answer.");
        while (true) {
            out.print("Their name: "); out.flush();
            String line = in.readLine();
            if (line == null) return null;
            String t = line.strip();
            if (t.isEmpty()) return "";
            try { typedPerson(store, self, t); return t; }
            catch (IllegalArgumentException e) { out.println(e.getMessage()); }
        }
    }

    /**
     * The person a name typed for "someone else in my library" means, as {@link FamilyQuestions#find} finds them: {the name the claims are
     * moved to, the name shown}. Throws IllegalArgumentException, with a sentence, when the name finds nobody, several people, the entry
     * itself or no person.
     */
    static String[] typedPerson(LibraryStore store, String self, String typed) throws IOException {
        String t = typed == null ? "" : typed.strip();
        if (t.isEmpty()) throw new IllegalArgumentException("Type the name of the person as your library writes it.");
        Graph now = FamilyPeople.view(store);
        FamilyQuestions.Found found = FamilyQuestions.find(now, t, Graph.differentPairs(store));
        if (found.ambiguous()) throw new IllegalArgumentException("Your library has several people who could be \"" + t + "\": " + String.join(", ", found.could().stream().limit(8).map(x -> "\"" + x + "\"").toList()) + ". Type the name of the one you mean, written as it is here.");
        if (!found.found()) throw new IllegalArgumentException("Your library has nobody named \"" + t + "\"." + (found.could().isEmpty() ? "" : " It could be: " + String.join(", ", found.could().stream().limit(8).map(x -> "\"" + x + "\"").toList()) + ".") + " Type the name again, as your library writes it.");
        String id = now.nodeIdOf(found.person());
        Graph.Node x = now.node(id);
        if (id.equals(self)) throw new IllegalArgumentException("\"" + t + "\" is the entry this question is about. Type the name of somebody else, or choose another answer.");
        if (x == null || !"person".equals(x.kind()) || FamilyHouses.isFamily(now, id)) throw new IllegalArgumentException("\"" + t + "\" is not a person in your library. Type the name of a person, or choose another answer.");
        String l = labelIn(now, id);
        return new String[]{now.nodeIdOf(l).equals(id) ? l : id, l};
    }

    /** What a sitting came to: how many questions were answered, and whether the person stopped it (or the input ended) before the last. */
    public record Sat(int answered, boolean stopped) { }

    // ── working the questions out ──────────────────────────────────────────────────────────────────────────────────────

    /** A question with what each of its answers does. */
    private record Posed(Question q, Map<String, Act> acts, List<String> keyed) {
        /** The code the same question had while one of its people was an entry since joined into another ({@code into}: entry → those joined into it). */
        List<String> earlier(Map<String, List<String>> into) {
            List<List<String>> ps = List.of(new ArrayList<>());
            for (String id : q.people()) {
                List<List<String>> next = new ArrayList<>();
                List<String> was = new ArrayList<>(into.getOrDefault(id, List.of())); was.add(id);
                for (List<String> p : ps) for (String x : was) { List<String> n = new ArrayList<>(p); n.add(x); next.add(n); }
                ps = next;
            }
            return ps.stream().map(p -> code(q.kind(), p, keyed)).filter(c -> !c.equals(q.code())).toList();
        }
    }

    /** What one answer does when it is given. */
    @FunctionalInterface
    private interface Act { Done run(Ctx c) throws IOException; }

    /** An answer being given: the question's code, the year typed, who told it, and the words of the answer chosen. */
    private record Ctx(LibraryStore store, String code, String year, String teller, String says, String name) {
        String reason() { return "the family's answer to the question " + code + " about names (as told by " + teller + "): " + says; }
    }

    /** What an answer did: the sentence said to the person, the line kept in the list of answers, and what it filed. */
    private record Done(String said, String what, String done) { }

    /** The family names a graph knows, and who in it is written by a family name alone; worked out once per graph. */
    private static final Map<Graph, Words> WORDS = Collections.synchronizedMap(new WeakHashMap<>());

    private static Words words(Graph g) {
        Words w = WORDS.get(g);
        if (w != null) return w;
        w = new Words(g);
        WORDS.put(g, w);
        return w;
    }

    /**
     * The word a person entry is when it is a family name alone, or null. Two things must hold: the entry is one word that is the family
     * part of a name the library knows (a name claim's, a family's, the family names the library finds among its people, or the family part
     * of another person's name), and a source writes it as a family: possessive ("Endo's son", 遠藤の子), or with a family word (遠藤家, the
     * Endos, the Endo family). A given name alone is never one.
     */
    static String familyNameAlone(Graph g, Graph.Node n) { return words(g).alone(n); }

    /**
     * Whether the question who an entry written by a family name alone is still waits for the family (asked, or put off): once they answered
     * it, the entry is what they said, and nothing says the questions ask it.
     */
    static boolean aloneAsked(Graph g, String id) {
        LibraryStore store = g.store();
        if (store == null) return true;
        try { return posed(store, g, Set.of(id), true).stream().anyMatch(p -> p.q().kind().equals("family-name-alone") && p.q().people().contains(id)); }
        catch (IOException e) { return true; }
    }

    private static final class Words {
        final Graph g;
        final FamilyNameHistory.Index idx;
        private List<String> guessed;
        private Map<String, List<String[]>> byWord;           // a word of a Latin form, or a beginning of a form in characters or kana → {person, form}
        private Map<String, List<String>> touching;           // node → the claims about it
        private final Map<String, String> example = new HashMap<>();

        Words(Graph g) { this.g = g; this.idx = FamilyNameHistory.of(g); }

        String alone(Graph.Node n) {
            if (n == null || !"person".equals(n.kind())) return null;
            // a title with a family name ("Mr. Endo", "Viscount Endo", 遠藤さん) is the family name alone too
            String w = FamilyNames.untitled(n.label().replaceAll("\\s*[(（][^)）]*[)）]\\s*$", "").strip());
            if (w.isEmpty() || w.matches(".*[\\s　・,].*") || FamilyQuestions.placeholder(w) || FamilyForms.script(w).isEmpty()) return null;
            // the words first, which few entries pass; then whether the word is somebody's family name here
            if (!writtenAsAFamily(w, n.id())) return null;
            return familyPart(w, n.id()) == null ? null : w;
        }

        /** A name the library knows in which this word is the family part, or null when it is none's. */
        String familyPart(String w, String self) {
            String memo = w + "\u0000" + self;
            if (example.containsKey(memo)) return example.get(memo);
            String found = null;
            for (String[] pf : words().getOrDefault(key(w), List.of())) {
                if (pf[0].equals(self) || !hasWord(pf[1], w)) continue;
                // a romanised Japanese name is written in either order: its last word is its family part only where something says so
                if (idx.orderUnknown(pf[0], pf[1])) continue;
                String[] parts = idx.parts(pf[0], pf[1]);
                if (!parts[0].isBlank() && !parts[1].isBlank() && FamilyForms.sameForm(parts[0], w)) { found = pf[1]; break; }
            }
            if (found == null) for (String f : idx.familyParts()) if (FamilyForms.sameForm(w, f)) { found = f; break; }
            if (found == null) for (String f : guessed()) if (FamilyForms.sameForm(w, f)) { found = f; break; }
            example.put(memo, found);
            return found;
        }

        /** Every person's written forms, by each word (Latin letters) and each beginning of up to four characters (characters, kana). */
        private synchronized Map<String, List<String[]>> words() {
            if (byWord != null) return byWord;
            Map<String, List<String[]>> m = new HashMap<>();
            for (Graph.Node p : g.nodes()) {
                if (!"person".equals(p.kind()) || FamilyQuestions.placeholder(p.label())) continue;
                List<String> forms = new ArrayList<>(List.of(p.label())); forms.addAll(p.aliases());
                for (String form : forms) {
                    String sc = FamilyForms.script(form);
                    Set<String> keys = new LinkedHashSet<>();
                    if (sc.equals("latin")) { String[] ws = form.split("[^\\p{L}'’]+"); if (ws.length > 1) for (String x : ws) if (!x.isBlank()) keys.add(key(x)); }
                    else if (sc.equals("han") || sc.equals("kana")) {
                        String k = sc.equals("han") ? FamilyForms.hanKey(form) : FamilyForms.kanaKey(form);
                        for (int i = 1; i <= Math.min(4, k.length() - 1); i++) keys.add(key(k.substring(0, i)));
                    }
                    for (String k : keys) m.computeIfAbsent(k, x -> new ArrayList<>()).add(new String[]{p.id(), form});
                }
            }
            byWord = m;
            return m;
        }

        private List<String> guessed() {
            if (guessed == null) { List<String> x; try { x = FamilyFolder.everyFamilyName(g); } catch (RuntimeException e) { x = List.of(); } guessed = x; }
            return guessed;
        }

        /** Whether a source writes the word as a family: possessive, with a family word, or as whose relative somebody is. */
        boolean writtenAsAFamily(String w, String id) {
            Pattern p = null;
            for (String c : touching().getOrDefault(id, List.of())) {
                Finding f = idx.finding(c);
                String q = f == null ? "" : FamilyChecks.quoteOf(f);
                if (!q.toLowerCase(Locale.ROOT).contains(w.toLowerCase(Locale.ROOT))) continue;
                if (p == null) p = asAFamily(w);
                if (p.matcher(q).find()) return true;
            }
            return false;
        }

        private synchronized Map<String, List<String>> touching() {
            if (touching != null) return touching;
            Map<String, List<String>> m = new HashMap<>();
            for (Graph.Edge e : g.edges()) { m.computeIfAbsent(e.from(), k -> new ArrayList<>()).add(e.findingId()); m.computeIfAbsent(e.to(), k -> new ArrayList<>()).add(e.findingId()); }
            touching = m;
            return m;
        }
    }

    /** The words that write a family name as a family: Endo's, the Endos, the Endo family, son of Endo, 遠藤の, 遠藤家. */
    static Pattern asAFamily(String w) {
        String q = Pattern.quote(w);
        if (FamilyForms.script(w).equals("latin"))
            return Pattern.compile("(?i)(?<![\\p{L}])" + q + "(?:['’]s?|s['’])(?![\\p{L}])|(?<![\\p{L}])" + q + " (?:family|house|line|clan)\\b|\\bthe " + q + "(?:e?s)(?![\\p{L}])"
                    + "|\\bhouse of " + q + "(?![\\p{L}])|\\b(?:son|daughter|child|children|wife|husband|widow|father|mother|brother|sister|heir|nephew|niece|grandson|granddaughter) of (?:the )?" + q + "(?![\\p{L}])");
        return Pattern.compile(q + "(?:の|家|一族|一門|本家|分家)");
    }

    /** Whether a written name has this word as a word of its own (Latin letters), or begins with it and is longer (characters, kana). */
    static boolean hasWord(String form, String w) {
        String sc = FamilyForms.script(w);
        if (sc.equals("latin")) {
            String k = FamilyForms.latinKey(w);
            String[] ws = form.split("[^\\p{L}'’]+");
            if (ws.length < 2) return false;
            for (String x : ws) if (!x.isBlank() && FamilyForms.latinKey(x).equals(k)) return true;
            return false;
        }
        if (sc.equals("han")) { String f = FamilyForms.hanKey(form), k = FamilyForms.hanKey(w); return f.length() > k.length() && f.startsWith(k); }
        if (sc.equals("kana")) { String f = FamilyForms.kanaKey(form), k = FamilyForms.kanaKey(w); return f.length() > k.length() && f.startsWith(k); }
        return false;
    }

    /** Whether a claim's own words write a name: {@link #nameAt}. */
    static boolean shows(Finding f, String w) { return nameAt(FamilyChecks.quoteOf(f), w) >= 0; }

    /**
     * Where a quote writes a name, or -1: in Latin letters a word of it that is the name, possessive or plural too (Hart, Hart's, the Harts);
     * in characters or kana the name inside it, old and new forms of a character alike.
     */
    static int nameAt(String q, String w) {
        if (q == null || w == null || q.isBlank() || w.isBlank()) return -1;
        if (!FamilyForms.script(w).equals("latin")) return KanjiForms.modern(q).indexOf(KanjiForms.modern(w.strip()));
        String k = FamilyForms.latinKey(w);
        Matcher m = Pattern.compile("\\p{L}[\\p{L}'’-]*").matcher(q);
        while (m.find()) { String x = FamilyForms.latinKey(m.group()); if (x.equals(k) || x.equals(k + "s") || x.equals(k + "es")) return m.start(); }
        return -1;
    }

    /** A family's label as the words put "the" before it: "The Hart family", which a person wrote so, is "Hart family" there. */
    static String noArticle(String label) { return label == null ? null : label.replaceFirst("(?i)^the\\s+", ""); }

    /** A form's key for comparing parts of names within its script; "" for a form in no script the library reads. */
    private static String key(String s) {
        String sc = FamilyForms.script(s);
        return switch (sc) {
            case "han" -> "h:" + FamilyForms.hanKey(s);
            case "kana" -> "k:" + FamilyForms.kanaKey(s);
            case "latin" -> "l:" + FamilyForms.latinKey(s);
            default -> "";
        };
    }

    /**
     * The people genealogy's own work names: those its claims are about, told by where each claim came from ({@link Fields#ofClaim}): its own
     * intake's, the family's answers, and the claims of runs asked for in genealogy mode. A person the owner marked by hand, whom only ordinary
     * claims or the owner's own other names speak of, is nobody the questions ask about.
     */
    static Set<String> ownPeople(LibraryStore store, Graph g, Collection<Finding> findings) {
        Map<String, Set<String>> runs;
        try { runs = Fields.runs(store); } catch (IOException e) { runs = Map.of(); }
        String field = new GenealogyProfile().name();
        Set<String> out = new HashSet<>();
        for (Finding f : findings) {
            if (f.triple() == null || !Fields.ofClaim(f, runs).contains(field)) continue;
            // in genealogy's view a claim's side is the person it is linked to ({@link FamilyLinks}), whatever name it writes
            out.add(g.nodeOf(f, true));
            out.add(g.nodeOf(f, false));
        }
        return out;
    }

    /** The questions of one graph, worked out; {@code scope}: the node ids they must be about, or null for all. */
    /** Each relation's words said once for the names that have it: "Tom Hale is …", "“Tom” and Tom Hale are both …", "… are all …". */
    static String saidOnce(Map<String, List<String>> byWords) {
        List<String> said = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : byWords.entrySet()) {
            List<String> names = e.getValue();
            said.add(names.size() == 1 ? names.get(0) + " is " + e.getKey()
                    : String.join(", ", names.subList(0, names.size() - 1)) + " and " + names.get(names.size() - 1) + (names.size() == 2 ? " are both " : " are all ") + e.getKey());
        }
        return said.isEmpty() ? "" : String.join(", and ", said) + ". ";
    }

    private static final class Work {
        final LibraryStore store;
        final Graph g;
        final FamilyNameHistory.Index idx;
        final Set<String> scope;
        final Map<String, Finding> byId = new LinkedHashMap<>();
        final Set<String> apart;
        final Map<String, Map<String, List<String>>> sexes;
        final List<Graph.Node> people = new ArrayList<>();
        final Map<String, List<Graph.Edge>> from = new HashMap<>(), to = new HashMap<>();
        final Map<String, Set<String>> sigs = new HashMap<>();
        /** The people genealogy's own work names ({@link #ownPeople}): the only people asked about. */
        final Set<String> own;
        private Map<String, List<String>> aliasSources;

        /** Whether how each name came that the library worked out as the marriage's is asked too: where the family asks about a person by name. */
        final boolean workedOutToo;

        /** Claims a family's answer disputed, which the disagreements between the owner's notes and a source count all the same ({@link #disagreements}). */
        Set<String> countedAgain = Set.of();

        Work(LibraryStore store, Graph g, Set<String> scope, boolean workedOutToo) throws IOException {
            this.store = store;
            this.g = g;
            this.scope = scope;
            this.workedOutToo = workedOutToo;
            this.idx = FamilyNameHistory.of(g);
            for (Finding f : FamilyPeople.findings(g)) byId.put(f.id(), f);
            this.apart = Graph.differentPairs(store);
            this.sexes = FamilyKin.sexes(g);
            this.own = ownPeople(store, g, byId.values());
            for (Graph.Node n : g.nodes()) if ("person".equals(n.kind()) && !FamilyQuestions.unknown(n.label()) && own.contains(n.id())) people.add(n);
            for (Graph.Edge e : g.edges()) {
                if (FamilyKin.gone(e)) continue;
                from.computeIfAbsent(e.from(), k -> new ArrayList<>()).add(e);
                to.computeIfAbsent(e.to(), k -> new ArrayList<>()).add(e);
            }
        }

        boolean in(String id) { return scope == null || scope.contains(id); }

        private FamilyClose.Close close;

        /** Close family in this graph ({@link FamilyClose}). */
        FamilyClose.Close close() { if (close == null) close = FamilyClose.of(g); return close; }

        /**
         * Whether the family is asked a question: only about close family ({@link FamilyClose}), the people a family remembers. Who a person
         * is, how a name came and when, how a name is read, which kind of parent a parent is (of a child in close family), and whether two
         * entries are one person (when one of them is close family). Where the owner's own notes and a source disagree, the owner is asked
         * whoever it concerns.
         */
        boolean putToFamily(Question q) {
            FamilyClose.Close c = close();
            return switch (q.kind()) {
                case "notes-source" -> true;
                // whether two entries are one person is asked when one of them is close family, or a child of theirs the question rests on is:
                // it decides who a close person's parent is, as the question about that child would have
                case "one-person" -> q.people().stream().anyMatch(c::close) || q.turnsOn().stream().anyMatch(c::close);
                case "which-family" -> q.people().stream().anyMatch(f -> FamilyHouses.members(g, f).stream().anyMatch(m -> c.close(m.person())));
                default -> !q.people().isEmpty() && c.close(q.people().get(0));
            };
        }

        List<Posed> all() {
            List<Posed> out = new ArrayList<>();
            if (people.isEmpty() && FamilyHouses.all(g).isEmpty()) return out;
            familyNameAlone(out);
            onePerson(out);
            nameChangeHow(out);
            nameChangeWhen(out);
            birthOrAdoptive(out);
            whichFamily(out);
            nameAtDate(out);
            reading(out);
            notesAndSources(out);
            Map<String, Posed> byCode = new LinkedHashMap<>();
            for (Posed p : out) byCode.putIfAbsent(p.q().code(), p);
            return new ArrayList<>(byCode.values());
        }

        // ── small helpers ──

        String label(String id) { return labelIn(g, id); }

        /** The heading of a person: the latest name, with the birth family beside it. */
        String heading(String id) { return idx.heading(id); }

        /** " (born 1875, died 1940)", or "". */
        String life(String id) {
            FamilyDate b = idx.born(id), d = idx.died(id);
            if (b == null && d == null) return "";
            return " (" + (b == null ? "" : "born " + b.phrase()) + (b != null && d != null ? ", " : "") + (d == null ? "" : "died " + d.phrase()) + ")";
        }

        List<String> claimsTouching(String id) {
            Set<String> out = new LinkedHashSet<>();
            for (Graph.Edge e : from.getOrDefault(id, List.of())) if (!e.predicate().equals("is filed under") && !e.predicate().equals("mentions")) out.add(e.findingId());
            for (Graph.Edge e : to.getOrDefault(id, List.of())) if (!e.predicate().equals("is filed under") && !e.predicate().equals("mentions")) out.add(e.findingId());
            return new ArrayList<>(out);
        }

        /** A claim's own words and where they are from: “Endo's son, Morita Kenji” (book.txt); its first line when it has no quote. */
        String said(Finding f) {
            if (f == null) return "";
            String q = FamilyChecks.quoteOf(f);
            String src = FamilyChecks.fromAll(f);
            // a claim with no words of its own (a tree file's) is quoted by its sentence, so it reads as what the source says
            return "“" + (q.isBlank() ? f.title().replaceFirst("[.。]$", "") : Acquisitions.compress(q, 160)) + "”" + (src.isBlank() ? "" : " (" + src + ")");
        }

        String sexOf(String id) { return FamilyKin.sexOf(sexes, id); }

        /** Where a name alone is written: up to two of its facts in the source's own words, the family relations first. "" when it has none. */
        String whereWritten(String id) {
            List<Graph.Edge> es = new ArrayList<>(from.getOrDefault(id, List.of()));
            es.addAll(to.getOrDefault(id, List.of()));
            es.sort(Comparator.comparingInt(e -> FamilyLinks.KIN.contains(e.predicate()) ? 0 : 1));
            List<Finding> fs = new ArrayList<>();
            for (Graph.Edge e : es) {
                Finding f = byId.get(e.findingId());
                if (f != null && !fs.contains(f)) fs.add(f);
                if (fs.size() == 2) break;
            }
            if (fs.isEmpty()) return "";
            return "Where “" + label(id) + "” is written: " + String.join("; ", fs.stream().map(this::said).toList()) + ". ";
        }

        FamilySame.Comparison compare(String a, String b) { return FamilySame.compare(g, byId, a, b); }

        /**
         * The family part of one of a person's names: its own; else as the library splits the name; else by the person's own other names,
         * because a given name mostly stays when a family name changes: 森田健二 beside 遠藤健二 (given 健二) is 森田. "" when nothing says.
         */
        String familyOf(String id, FamilyNameHistory.Name n) {
            if (n == null) return "";
            if (!n.family().isBlank()) return n.family();
            String f = idx.parts(id, n.written())[0];
            if (!f.isBlank()) return f;
            String w = n.written().strip(), sc = FamilyForms.script(w);
            for (FamilyNameHistory.Name o : idx.names(id)) {
                if (o == n || o.given().isBlank() || !FamilyForms.script(o.given()).equals(sc)) continue;
                if (sc.equals("han") || sc.equals("kana")) {
                    String whole = sc.equals("han") ? FamilyForms.hanKey(w) : FamilyForms.kanaKey(w), given = sc.equals("han") ? FamilyForms.hanKey(o.given()) : FamilyForms.kanaKey(o.given());
                    if (whole.length() > given.length() && whole.endsWith(given)) return whole.substring(0, whole.length() - given.length());
                } else if (sc.equals("latin")) {
                    List<String> ws = new ArrayList<>(List.of(w.replace(",", " ").split("\\s+")));
                    if (ws.size() == 2 && ws.removeIf(x -> FamilyForms.latinKey(x).equals(FamilyForms.latinKey(o.given())))) return ws.get(0);
                }
            }
            return "";
        }

        boolean sameFamily(String id, FamilyNameHistory.Name a, FamilyNameHistory.Name b) {
            String fa = familyOf(id, a), fb = familyOf(id, b);
            if (!fa.isBlank() && !fb.isBlank()) return FamilyForms.sameForm(fa, fb) || a.hasFamily(fb) || b.hasFamily(fa);
            return a.isForm(b.written()) || b.isForm(a.written());
        }

        /** The date of the claim of the event a name follows (the marriage, the adoption); null when there is none or it is not dated. */
        FamilyDate eventDate(FamilyNameHistory.Name n) {
            if (n.event() == null || n.event().isBlank()) return null;
            Finding e = idx.finding(n.event());
            return e == null ? null : FamilyChecks.claimDate(e);
        }

        /**
         * When a name was taken, as far as the evidence settles it with nothing to ask: its own year; for a name taken AT the event it
         * follows (a marriage, an adoption, entering a family as 婿養子 or by 入夫 marriage), the year of that event, unless a record written
         * under the name is older than the event, which is evidence against it. Null when nothing settles it.
         */
        FamilyDate settledFrom(String id, FamilyNameHistory.Name n) {
            if (n.from() != null) return n.from();
            if (!AT_THE_EVENT.contains(n.kind())) return null;
            FamilyDate d = eventDate(n);
            if (d == null) return null;
            int[] w = idx.useWindow(id, n);
            return w != null && w[0] < d.year() ? null : d;
        }

        /**
         * Whether a married name is tied to the claim of its marriage, and the marriage has no date: the name came with it, as the married name
         * no source types is worked out to have, and what is not known is the marriage's date, which is no question about the name. A married
         * name that something points beyond ({@link #marriedInUnsaid}) is the family's to explain, and then to date.
         */
        boolean withItsUndatedMarriage(String id, FamilyNameHistory.Name n) {
            if (!n.kind().equals("marriage") || n.event() == null || n.event().isBlank() || marriedInUnsaid(id, n)) return false;
            Finding e = idx.finding(n.event());
            return e != null && FamilyChecks.claimDate(e) == null;
        }

        /**
         * Whether a name is a change the evidence explains: worked out by the names ({@link FamilyNameHistory.Name#workedOut}: a woman's name
         * from her marriage, a name from entering a family), or of a known kind ({@link FamilyNameHistory.Name#explained}) that is no name of
         * birth, dated by itself ({@link FamilyNameHistory.Name#dated}) or by the event it follows ({@link #settledFrom}).
         */
        boolean explained(String id, FamilyNameHistory.Name n) {
            if (n.workedOut() && n.explained()) return true;
            if (!n.explained() || n.kind().equals("birth") || !n.replaces()) return false;
            return n.dated() || settledFrom(id, n) != null;
        }

        /**
         * Whether a name of a kind not known is the name the person carried BEFORE a change the evidence explains, so that how it came is
         * no question: no name of birth of another family part is known (else this name came between, or after), and neither its own year
         * nor the records written under it put it after that change. Ruth Hale beside Ruth Ellis, taken at her marriage in 1875; 森田正二
         * beside 髙橋正二, taken on adoption in 1940.
         */
        boolean beforeAnExplainedChange(String id, FamilyNameHistory.Name n, List<FamilyNameHistory.Name> names) {
            if (names.stream().anyMatch(x -> x != n && x.kind().equals("birth") && !sameFamily(id, x, n))) return false;
            int[] used = idx.useWindow(id, n);
            for (FamilyNameHistory.Name o : names) {
                if (o == n || !o.replaces() || sameFamily(id, o, n) || !explained(id, o)) continue;
                FamilyDate of = settledFrom(id, o);
                if (n.from() != null) { if (of != null && n.from().year() < of.year()) return true; continue; }
                if (n.fromEvent()) continue;
                if (used != null && of != null && used[0] >= of.year()) continue;   // every record under it is from after the change: evidence against
                if (of != null && marriedIntoAfter(id, n, of.year())) continue;   // it may have come with a later marriage to somebody of that name: asked
                return true;
            }
            return false;
        }

        /** Whether the person married, after a year, somebody who carried the family part of this name: the name may have come with that marriage. */
        boolean marriedIntoAfter(String id, FamilyNameHistory.Name n, int year) {
            String fam = familyOf(id, n);
            if (fam.isBlank()) return false;
            List<Graph.Edge> marriages = new ArrayList<>();
            for (Graph.Edge e : from.getOrDefault(id, List.of())) if (e.predicate().equals("married-to")) marriages.add(e);
            for (Graph.Edge e : to.getOrDefault(id, List.of())) if (e.predicate().equals("married-to")) marriages.add(e);
            for (Graph.Edge e : marriages) {
                Finding f = byId.get(e.findingId());
                FamilyDate d = f == null ? null : FamilyChecks.claimDate(f);
                if (d != null && d.year() > year && carries(e.from().equals(id) ? e.to() : e.from(), fam)) return true;
            }
            return false;
        }

        /**
         * Whether a name no claim gives, written only in Latin letters or kana, may write either of two names of the person in characters of
         * two family parts, which only a reading could tell. The name the entry is filed under is never one: it is the entry's own.
         */
        boolean writesANameInCharacters(Graph.Node p, FamilyNameHistory.Name n, List<FamilyNameHistory.Name> names) {
            if (!n.implicit() || n.texts().stream().anyMatch(t -> FamilyForms.script(t).equals("han"))) return false;
            if (n.isForm(p.label()) || n.isForm(bareName(p.label()))) return false;
            List<FamilyNameHistory.Name> han = names.stream().filter(o -> o != n && FamilyForms.script(o.written()).equals("han")).toList();
            return han.stream().anyMatch(a -> han.stream().anyMatch(b -> a != b && !sameFamily(p.id(), a, b)));
        }

        /**
         * Whether a name taken at a marriage, as a source gives it or as the library worked it out, is still the family's to explain: something
         * points to more than the marriage ({@link FamilyNameHistory.Index#beyondMarriage}: an adoption, an entry into that family, the
         * husband's or wife's parents recorded as the person's own, being its head or heir), and the family has not said how. The same for a
         * man and a woman.
         */
        boolean marriedInUnsaid(String id, FamilyNameHistory.Name n) {
            if (!n.kind().equals("marriage")) return false;
            for (String c : n.claims()) {
                Finding f = byId.get(c);
                if (f != null && f.state() == Finding.State.accepted && f.review() != null && "person".equals(f.review().reviewer()) && "marriage".equals(FamilyDetail.get(f, "kind"))) return false;
            }
            String fam = familyOf(id, n);
            return idx.beyondMarriage(id, n, fam, spouseCarrying(id, fam));
        }

        /** The husband or wife of a person who carried this family part; null when none did. */
        String spouseCarrying(String id, String fam) {
            for (Graph.Edge e : from.getOrDefault(id, List.of())) if (e.predicate().equals("married-to") && carries(e.to(), fam)) return e.to();
            for (Graph.Edge e : to.getOrDefault(id, List.of())) if (e.predicate().equals("married-to") && carries(e.from(), fam)) return e.from();
            return null;
        }

        /** A name as the question shows and files it: without the bracket the library adds to a label to tell two entries of one name apart. */
        String text(FamilyNameHistory.Name n) {
            String w = n.implicit() ? bareName(n.written()) : n.written();
            // the index form is a way of writing the name: "Endo, Kenji" is asked about as Endo Kenji
            String[] ix = FamilyNames.indexForm(w);
            return ix != null ? ix[0] + " " + ix[1] : w;
        }

        /** The family ids a person is a member of, by the family's name. */
        String familyNamed(String person, String family) {
            for (FamilyHouses.Membership m : FamilyHouses.families(g, person)) if (FamilyForms.sameForm(FamilyHouses.nameOf(FamilyHouses.labelOf(g, m.family())), family)) return FamilyHouses.labelOf(g, m.family());
            return null;
        }

        private final Map<String, String> oneFamily = new HashMap<>();

        /** The label of the family of this name that goes with these people, found among their memberships, else the one family of the name; null when there is none yet or several. */
        String familyLabel(String family, String... near) {
            for (String p : near) if (p != null) { String f = familyNamed(p, family); if (f != null) return f; }
            String k = key(family);
            if (!oneFamily.containsKey(k)) { String id = FamilyHouses.find(g, family, ""); oneFamily.put(k, id == null ? null : FamilyHouses.labelOf(g, id)); }
            return oneFamily.get(k);
        }

        /** Where the other names of a person came from, by the person's node and the other name: the sources alias-sources.tsv keeps. */
        List<String> aliasSource(String id, String alias) {
            if (aliasSources == null) {
                aliasSources = new HashMap<>();
                try {
                    Path f = Graph.aliasSourcesFile(store);
                    if (Files.exists(f)) for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                        String[] p = line.split("\t");
                        if (p.length < 3) continue;
                        aliasSources.computeIfAbsent(g.nodeIdOf(p[0]) + "\t" + Vocabulary.norm(p[1]), k -> new ArrayList<>()).add(p[2]);
                    }
                } catch (IOException ignored) { }
            }
            return aliasSources.getOrDefault(id + "\t" + Vocabulary.norm(alias), List.of());
        }

        /** What else a person is tied to, cheaply: relatives, a birth year, places, work. Two people who share none of it have nothing that agrees. */
        Set<String> sig(String id) {
            Set<String> s = sigs.get(id);
            if (s != null) return s;
            s = new HashSet<>();
            for (Graph.Edge e : from.getOrDefault(id, List.of())) {
                if (FamilyAccount.personToPerson(e.predicate())) s.add("kin:" + e.to());
                else if (Set.of("born-in", "died-in", "buried-in", "occupation").contains(e.predicate())) s.add(e.predicate() + ":" + e.to());
            }
            for (Graph.Edge e : to.getOrDefault(id, List.of())) if (FamilyAccount.personToPerson(e.predicate())) s.add("kin:" + e.from());
            FamilyDate b = idx.born(id), d = idx.died(id);
            if (b != null && b.exact()) s.add("born:" + b.year());
            if (d != null && d.exact()) s.add("died:" + d.year());
            sigs.put(id, s);
            return s;
        }

        /** What {@link FamilySame#compare} would list as differing, found cheaply: two birth or death years apart, two places of birth or death. */
        boolean differs(String a, String b) {
            FamilyDate ba = idx.born(a), bb = idx.born(b), da = idx.died(a), db = idx.died(b);
            if (ba != null && bb != null && FamilyDate.apart(ba, bb, 0)) return true;
            if (da != null && db != null && FamilyDate.apart(da, db, 0)) return true;
            for (String pl : List.of("born-in", "died-in")) {
                String x = placeOf(a, pl), y = placeOf(b, pl);
                if (x != null && y != null && !FamilySame.samePlace(label(x), label(y))) return true;
            }
            return false;
        }

        private String placeOf(String id, String predicate) {
            for (Graph.Edge e : from.getOrDefault(id, List.of())) if (e.predicate().equals(predicate)) return e.to();
            return null;
        }

        boolean share(String a, String b) {
            Set<String> x = sig(a), y = sig(b);
            for (String k : x) if (y.contains(k)) return true;
            return false;
        }

        void add(List<Posed> out, String kind, List<String> people, List<String> claims, String text, List<Option> options, Map<String, Act> acts) { add(out, kind, people, claims, text, options, acts, List.of()); }

        /** The same, with the other people the question turns on ({@link Question#turnsOn}); they are no part of its code. */
        void add(List<Posed> out, String kind, List<String> people, List<String> claims, String text, List<Option> options, Map<String, Act> acts, List<String> turnsOn) {
            // whether two entries are one person is one question about the two, whatever agrees for them now: its code is the pair's
            List<String> keyed = kind.equals("one-person") ? List.of() : claims;
            String code = code(kind, people, keyed);
            List<Option> opts = new ArrayList<>();
            for (Option o : options) opts.add(new Option(o.key(), o.says(), o.does().replace(REOPEN_HERE, "researchzosho genealogy who --reopen " + code)));
            opts.add(new Option("later", "Later", "puts the question off: the library does not ask it again by itself. researchzosho genealogy who --answered lists it, with the command that asks it again."));
            List<String> findings = claims.stream().filter(c -> c != null && !c.isBlank() && !c.startsWith("name: ")).distinct().toList();
            // who the people are to the owner comes first, so the family knows whom the question is about
            String context = kind.equals("notes-source") ? "" : toYou(people);
            out.add(new Posed(new Question(code, kind, List.copyOf(people), context + text, List.copyOf(opts), findings, turnsOn), Map.copyOf(acts), List.copyOf(keyed)));
        }

        // ── a person written only by a family name ──

        void familyNameAlone(List<Posed> out) {
            for (Graph.Node n : people) {
                if (!in(n.id())) continue;
                if (FamilyMentions.isMention(n.label())) { mention(n, out); continue; }
                if (FamilyQuestions.placeholder(n.label())) continue;
                String w = FamilyNameQuestions.familyNameAlone(g, n);
                if (w != null) legacy(n, w, out);
            }
        }

        List<String> candidates(String placeholder, String self) {
            BiFunction<Graph, String, List<String>> test = candidatesForTests;
            List<String> found = test != null ? test.apply(g, placeholder) : FamilyMentions.candidates(idx, g, placeholder);
            List<String> out = new ArrayList<>();
            for (String c : found == null ? List.<String>of() : found) {
                String id = g.node(c) != null ? c : g.nodeIdOf(c);
                if (!id.equals(self) && g.node(id) != null && "person".equals(g.node(id).kind()) && !out.contains(id) && !apart.contains(Graph.pair(id, self))) out.add(id);
            }
            return out;
        }

        /** The first of these claims with a source's own words; the family's own answers are no source's words and are passed over. */
        Finding firstQuoted(List<String> claims) {
            Finding any = null;
            for (String c : claims) {
                Finding f = byId.get(c);
                if (f == null || f.sources().stream().anyMatch(s -> s.locator() != null && s.locator().startsWith(SOURCE))) continue;
                if (any == null) any = f;
                if (!FamilyChecks.quoteOf(f).isBlank()) return f;
            }
            return any;
        }

        void mention(Graph.Node n, List<Posed> out) {
            String[] parts = FamilyMentions.parts(n.label());
            if (parts == null) return;
            String whose = parts[0], role = parts[1], word = parts[2];
            List<String> claims = claimsTouching(n.id());
            Finding src = firstQuoted(claims);
            // a described person that no source writes, only the family's own answers, has no words to ask about: a join taken back left it
            if (src == null) return;
            List<String> cands = candidates(n.label(), n.id());
            String fam = null;
            for (FamilyHouses.Membership m : FamilyHouses.families(g, n.id())) { fam = noArticle(FamilyHouses.labelOf(g, m.family())); break; }
            // somebody the words tie to a family and to nobody in it ("Endo family's member (written only as Endo)") is somebody of that family
            boolean ofAFamily = FamilyHouses.isFamily(g, g.nodeIdOf(whose)) || FamilyHouses.familyWord(whose);
            String who = ofAFamily ? "somebody of the " + whose : whose + "'s " + role;
            StringBuilder t = new StringBuilder("A source writes " + who + " only by the family name " + word + (src == null ? "" : ": " + said(src)) + ". ");
            t.append("The library keeps this person as “").append(n.label()).append("”").append(fam == null || ofAFamily ? "" : ", a member of the " + fam).append(". ");
            if (cands.isEmpty()) t.append("Nobody in your library bore the name ").append(word).append(" then and fits as ").append(who).append(". ");
            else t.append(cands.size() == 1 ? "One person in your library bore the name " : cands.size() + " people in your library bore the name ").append(word).append(" then and ").append(cands.size() == 1 ? "fits" : "fit").append(" as ").append(who).append(". ");
            t.append("Who is it?");
            List<Option> opts = new ArrayList<>();
            Map<String, Act> acts = new LinkedHashMap<>();
            int i = 0;
            for (String c : cands) {
                String key = "c" + (++i), cl = label(c), mine = n.label();
                opts.add(new Option(key, heading(c) + life(c), "joins “" + mine + "” into “" + cl + "”: the facts about “" + mine + "” are then about " + cl + ". To take it back: " + REOPEN_HERE));
                acts.put(key, x -> merged(x, mine, cl, n.id()));
            }
            opts.add(new Option("person", "Somebody whose given name is not known", "keeps “" + n.label() + "” as it is, a person of the " + word + " family whose given name is not known, and does not ask again. To take it back: " + REOPEN_HERE));
            acts.put("person", x -> new Done("Kept as it is: " + n.label() + " is somebody whose given name is not known. The library does not ask about it again. To take it back: researchzosho genealogy who --reopen " + x.code(), "“" + n.label() + "” is somebody whose given name is not known", ""));
            add(out, "family-name-alone", List.of(n.id()), claims, t.toString(), opts, acts);
        }

        /**
         * The questions about an entry written by a family name alone: one for each source and each way that source writes the person
         * ({@link #groups}), since one book mostly means one person by one written form ("Mr. Endo") and another form ("old Endo") or
         * another book may mean somebody else. The question quotes the first two passages and says how many more there are; its answer moves
         * the claims of all of them. The family can say they are not all one person, and then each passage is asked about on its own.
         */
        void legacy(Graph.Node n, String w, List<Posed> out) {
            List<String> claims = claimsTouching(n.id());
            String example = words(g).familyPart(w, n.id());
            // the relatives the entry is written beside, and what it is to them: "森田健二's father"
            List<Graph.Edge> rels = relatives(n.id());
            List<Group> groups = groups(n, w, claims);
            // the people each source writes in full, or with the title it gives the name, in its words about the entry ("Tom Hart" in one
            // passage, "Mr. Hart" in another): whom that source's words by the name alone most likely mean
            Map<String, Set<String>> inFull = new HashMap<>();
            for (Group grp : groups) for (String c : bearers(w, n.id())) if (writtenAs(c, w, grp) < 2) inFull.computeIfAbsent(grp.source(), k -> new HashSet<>()).add(c);
            for (Group grp : groups) {
                Set<String> named = inFull.getOrDefault(grp.source(), Set.of());
                if (grp.passages().size() > 1 && split(n.id(), grp)) { for (List<String> p : grp.passages()) ask(n, w, example, rels, grp.with(p), true, named, out); }
                else ask(n, w, example, rels, grp, false, named, out);
            }
        }

        /**
         * How a person is written as a group's form: 0 with this very form, as a name of theirs, another name or in a name claim ("Tom Hart"
         * for "Tom Hart", "Hart, Tom"); 1 with the same title ("Viscount Hart Tom" for "Viscount Hart"); 2 neither. The family name alone,
         * written so, is everybody's of the family, and makes no match.
         */
        int writtenAs(String c, String w, Group grp) {
            Form form = grp.form();
            if (form.family() || form.key().equals(plainForm(w).key())) return 2;
            String title = form.key().substring(0, form.key().indexOf('|') + 1);
            boolean titled = !title.replaceAll("[,| ]", "").isEmpty();
            int best = 2;
            Graph.Node x = g.node(c);
            if (x == null) return 2;
            for (String t : texts(x)) {
                Form f = formIn(bareName(t), w, nameWord(), wholeNames(w));
                if (f == null) continue;
                if (f.key().equals(form.key())) return 0;
                if (titled && f.key().startsWith(title)) best = 1;
            }
            return best;
        }

        /** The passages of the claims about an entry (one source's claims with the same words), gathered by source and by written form. */
        List<Group> groups(Graph.Node n, String w, List<String> claims) {
            List<Group> out = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (String c : claims) {
                Finding f = byId.get(c);
                if (f == null || answered(f) || seen.contains(c)) continue;
                List<String> p = new ArrayList<>();
                for (String x : passage(f, claims)) if (seen.add(x)) p.add(x);
                if (p.isEmpty()) continue;
                String loc = locatorOf(f);
                Form form = formOf(p, n.id(), w);
                Group into = null;
                for (Group grp : out) if (grp.form().key().equals(form.key()) && (grp.source().equals(loc) || Evidence.sameLocator(grp.source(), loc))) { into = grp; break; }
                if (into == null) out.add(new Group(loc, form, new ArrayList<>(List.of(p))));
                else into.passages().add(p);
            }
            return out;
        }

        /** The source a claim's words come from: its first source that is no answer of the family's; "" when it has none. */
        String locatorOf(Finding f) {
            for (Finding.Source s : f.sources()) if (s.locator() != null && !s.locator().startsWith(SOURCE)) return s.locator();
            return "";
        }

        /**
         * How one passage writes the person: the form its words show the name in ({@link #formIn}), else the name its claim writes for the
         * entry, else the family name alone.
         */
        Form formOf(List<String> passage, String id, String w) {
            for (String c : passage) { Finding f = byId.get(c); Form x = f == null ? null : formIn(FamilyChecks.quoteOf(f), w, nameWord(), wholeNames(w)); if (x != null) return x; }
            for (String c : passage) {
                Finding f = byId.get(c);
                if (f == null || f.triple() == null) continue;
                for (boolean subject : new boolean[]{true, false}) {
                    String side = subject ? f.triple().subject() : f.triple().object();
                    if (side == null || !g.nodeOf(f, subject).equals(id)) continue;
                    Form x = formIn(bareName(side), w, nameWord(), wholeNames(w));
                    if (x != null) return x;
                }
            }
            return plainForm(w);
        }

        private Predicate<String> nameWords;

        /**
         * Whether a word written beside a family name in Latin letters is a given name: a word, written with a capital, of a name of a person
         * in the library. "Tom" in "Tom Hart" is, when the library has a Tom; "When" at the start of a sentence is not.
         */
        Predicate<String> nameWord() {
            if (nameWords != null) return nameWords;
            Map<String, List<String[]>> byWord = words(g).words();
            nameWords = t -> {
                if (t == null || t.isEmpty() || !Character.isUpperCase(t.codePointAt(0)) || formWord(t)) return false;
                String k = FamilyForms.latinKey(t);
                for (String[] pf : byWord.getOrDefault("l:" + k, List.of())) {
                    if (descriptive(pf[1])) continue;
                    for (String x : pf[1].split("[^\\p{L}'’]+")) if (!x.isEmpty() && Character.isUpperCase(x.codePointAt(0)) && FamilyForms.latinKey(x).equals(k)) return true;
                }
                return false;
            };
            return nameWords;
        }

        private final Map<String, List<String>> wholeNames = new HashMap<>();

        /** The names in characters or kana of the people of the library that begin with this family name and are longer: 遠藤健二 for 遠藤. */
        List<String> wholeNames(String w) {
            return wholeNames.computeIfAbsent(w, x -> {
                String sc = FamilyForms.script(x);
                if (!sc.equals("han") && !sc.equals("kana")) return List.of();
                String k = sc.equals("han") ? FamilyForms.hanKey(x) : FamilyForms.kanaKey(x);
                List<String> out = new ArrayList<>();
                for (String[] pf : words(g).words().getOrDefault(key(k.substring(0, Math.min(4, k.length()))), List.of())) if (hasWord(pf[1], x) && !out.contains(pf[1])) out.add(pf[1]);
                return out;
            });
        }

        private Set<String> splits;

        /** Whether the family said the passages of this group are not all one person: an answer that stands wrote down its key ({@link #splitKey}). */
        boolean split(String id, Group grp) {
            if (splits == null) {
                splits = new HashSet<>();
                try {
                    for (String[] a : asked(store).values())
                        if (a[0].equals("answered")) { String s = FamilyDetail.parse(a.length > 4 ? a[4] : "").getOrDefault("split", ""); if (!s.isBlank()) splits.add(s.strip()); }
                } catch (IOException ignored) { }
            }
            return splits.contains(splitKey(id, grp));
        }

        /** One question: who the person is whom one source writes in one form, in one passage or several. */
        void ask(Graph.Node n, String w, String example, List<Graph.Edge> rels, Group grp, boolean ofASplit, Set<String> namedInFull, List<Posed> out) {
            List<String> claims = grp.claims();
            // the passages the question quotes: those that show the name first, and among them one that writes a relative
            List<List<String>> order = new ArrayList<>(grp.passages());
            Set<String> relClaims = new HashSet<>();
            for (Graph.Edge e : rels) relClaims.add(e.findingId());
            order.sort(Comparator.comparingInt(p -> (showsIn(p, w) ? 0 : 2) + (p.stream().anyMatch(relClaims::contains) ? 0 : 1)));
            List<String> first = order.get(0);
            Graph.Edge rel = rels.stream().filter(e -> first.contains(e.findingId())).findFirst().orElse(null);
            String other = rel == null ? null : rel.from().equals(n.id()) ? rel.to() : rel.from();
            String placeholder = rel == null ? null : FamilyMentions.placeholder(label(other), roleOf(n.id(), rel), w);
            String sex = !grp.form().sex().isEmpty() ? grp.form().sex() : sexSaid(n.id(), claims);
            // who may be the person: beside a relative, those who fit as that relative's relative first; then those who bore the name and lived in
            // every year the words give. Then only those the words do not contradict, those most likely first
            List<Integer> years = years(n.id(), claims);
            Set<String> fitsAs = new LinkedHashSet<>(placeholder == null ? List.of() : candidates(placeholder, n.id()));
            List<String> named = new ArrayList<>(fitsAs);
            for (String c : bearers(w, n.id())) if (!named.contains(c) && lived(c, years)) named.add(c);
            // the same words read again gave this person as a described person ("森田健二's parent (written only as Endo)"): it may be that
            // one, and the people that one may be are offered too
            List<String> again = sameWordsMentions(n, w, claims);
            for (String m : again) for (String c : candidates(label(m), m)) if (!c.equals(n.id()) && !named.contains(c) && !apart.contains(Graph.pair(c, n.id()))) named.add(c);
            List<String> cands = new ArrayList<>();
            for (String c : named) if (plausible(c, n.id(), sex, claims)) cands.add(c);
            int leftOut = 0;
            // the people of the name the words' years or sex rule out, counted; a description of somebody ("an old servant") is nobody named
            for (String c : bearers(w, n.id())) if (!cands.contains(c) && !descriptive(label(c))) leftOut++;
            cands = likeliest(cands, grp, w, n.id(), sex, fitsAs, namedInFull, claims);
            List<String> shownCands = cands.subList(0, Math.min(SHOWN, cands.size()));
            String fam = familyLabel(w, other);
            String famShown = fam != null ? "“" + fam + "”" : "the " + w + " family (the library makes it)";
            String src = grp.source().isBlank() ? "a source" : FamilyChecks.from(grp.source());
            int np = grp.passages().size();
            StringBuilder t = new StringBuilder("“" + w + "” is written in your library as a person with no given name, and " + w + " is a family name here" + (example == null || FamilyForms.sameForm(example, w) ? "" : ", as in " + example) + ". ");
            t.append(sentence("In " + src + ", “" + grp.form().shown() + "” is written in " + (np == 1 ? "1 passage: " : np + " passages: ") + quotes(order, w))).append(' ');
            if (ofASplit) t.append("You said the passages of ").append(src).append(" that write “").append(grp.form().shown()).append("” are not all one person, so each is asked about on its own. ");
            if (!again.isEmpty()) t.append("The same words also give “").append(label(again.get(0))).append("”. ");
            long fit = cands.stream().filter(fitsAs::contains).count(), others = cands.size() - fit;
            if (placeholder != null) {
                String[] p = FamilyMentions.parts(placeholder);
                String as = p == null ? placeholder : p[0] + "'s " + p[1];
                t.append(fit == 0 ? "Nobody in your library bore the name " + w + " then and fits as " + as + ". "
                        : (fit == 1 ? "One person in your library bore the name " : fit + " people in your library bore the name ") + w + " then and " + (fit == 1 ? "fits" : "fit") + " as " + as + ". ");
            }
            if (placeholder == null || others > 0) {
                String more = placeholder == null ? "" : "other ";
                t.append(others == 0 ? "Nobody in your library who bore the name " + w + " fits these words. "
                        : (others == 1 ? "One " + more + "person in your library who bore the name " + w + " fits these words" : others + " " + more + "people in your library who bore the name " + w + " fit these words")
                          + (cands.size() > SHOWN ? "; the " + SHOWN + " most likely are listed first. " : ". "));
            }
            if (leftOut > 0) t.append(leftOut == 1 ? "One other person of the name is left out, because their years or their sex do not fit. " : leftOut + " other people of the name are left out, because their years or their sex do not fit. ");
            t.append(grp.form().family() ? "Who is it?" : sex.equals("male") ? "Who is he?" : sex.equals("female") ? "Who is she?" : "Who is this?");
            String these = np == 1 ? "these words give" : "these " + np + " passages give", elsewhere = " “" + n.label() + "” elsewhere in your sources stays as it is.";
            String where = "“" + grp.form().shown() + "” in " + src + (np == 1 ? "" : " (" + np + " passages)");
            List<Option> opts = new ArrayList<>();
            Map<String, Act> acts = new LinkedHashMap<>();
            int i = 0;
            for (String c : shownCands) {
                String key = "c" + (++i), cl = label(c);
                opts.add(new Option(key, heading(c) + life(c), "the facts " + these + " about “" + n.label() + "” are then about " + cl + "." + elsewhere + " To take it back: " + REOPEN_HERE));
                acts.put(key, x -> movedTo(x, n, where, claims, nameOf(c), cl));
            }
            for (String m : again) {
                String key = "m" + (++i), ml = label(m);
                opts.add(new Option(key, "The same person as “" + ml + "”, which the same words give", "the facts " + these + " about “" + n.label() + "” are then about “" + ml + "”, and who that is is asked next." + elsewhere + " To take it back: " + REOPEN_HERE));
                acts.put(key, x -> movedTo(x, n, where, claims, nameOf(m), "“" + ml + "”"));
            }
            opts.add(new Option(SOMEONE, (shownCands.isEmpty() ? "Someone in my library" : "Someone else in my library") + " — I will type the name",
                    "asks for the name as your library writes it: the facts " + these + " about “" + n.label() + "” are then about that person." + elsewhere + " To take it back: " + REOPEN_HERE));
            acts.put(SOMEONE, x -> { String[] to = typedPerson(x.store(), n.id(), x.name()); return movedTo(x, n, where, claims, to[0], to[1]); });
            opts.add(new Option("person", "One person whose given name is not known", "keeps “" + n.label() + "” as a person, and does not ask again. To take it back: " + REOPEN_HERE));
            acts.put("person", x -> new Done("Kept as it is: “" + n.label() + "” is one person whose given name is not known. The library does not ask about it again. To take it back: researchzosho genealogy who --reopen " + x.code(), where + " is one person whose given name is not known", ""));
            if (placeholder != null) {
                opts.add(new Option("family", "Somebody of the " + w + " family whom the source does not name", "makes “" + n.label() + "” in these words the person “" + placeholder + "”, a member of " + famShown
                        + ": the facts " + these + " about “" + n.label() + "” are then about that person." + elsewhere + " To take it back: " + REOPEN_HERE));
                String o = other;
                acts.put("family", x -> described(x, n, w, placeholder, o, where, claims));
            } else {
                opts.add(new Option("family", "The " + w + " family itself", "makes “" + n.label() + "” in these words the family " + famShown + ": the facts " + these + " about “" + n.label() + "” are then about the family." + elsewhere + " To take it back: " + REOPEN_HERE));
                acts.put("family", x -> intoFamily(x, n, w, where, claims));
            }
            if (np > 1) {
                String key = splitKey(n.id(), grp);
                opts.add(new Option("split", "They are not all the same person", "asks about each of these " + np + " passages on its own. To take it back: " + REOPEN_HERE));
                acts.put("split", x -> new Done("Each of the " + np + " passages of " + src + " that write “" + grp.form().shown() + "” is asked about on its own now. To take it back: researchzosho genealogy who --reopen " + x.code(),
                        where + " is not one person in all of them", FamilyDetail.text(Map.of("split", key))));
            }
            add(out, "family-name-alone", List.of(n.id()), claims, t.toString(), opts, acts, cands);
        }

        /** Whether a passage's claims show the name in their words. */
        boolean showsIn(List<String> passage, String w) { for (String c : passage) { Finding f = byId.get(c); if (f != null && FamilyNameQuestions.shows(f, w)) return true; } return false; }

        /** The first two passages quoted, and how many more: “…”, “…” and 3 more. */
        String quotes(List<List<String>> passages, String w) {
            List<String> q = new ArrayList<>();
            for (List<String> p : passages.subList(0, Math.min(2, passages.size()))) { Finding f = quotedOf(p, w); q.add(f == null ? "(no words of its own)" : quoted(f, w)); }
            int more = passages.size() - q.size();
            if (more > 0) return String.join(", ", q) + " and " + more + " more";
            return String.join(" and ", q);
        }

        /** The claim of a passage whose words the question quotes: one that shows the name, else one with words, else its first. */
        Finding quotedOf(List<String> passage, String w) {
            Finding any = null, worded = null;
            for (String c : passage) {
                Finding f = byId.get(c);
                if (f == null) continue;
                if (any == null) any = f;
                if (worded == null && !FamilyChecks.quoteOf(f).isBlank()) worded = f;
                if (FamilyNameQuestions.shows(f, w)) return f;
            }
            return worded != null ? worded : any;
        }

        /** The sex the claims of these words give the entry, when they agree on one; "" otherwise. */
        String sexSaid(String id, List<String> claims) {
            Set<String> said = new HashSet<>();
            for (String c : claims) {
                Finding f = byId.get(c);
                if (f == null || f.triple() == null || !f.triple().predicate().equals("sex") || !g.nodeOf(f, true).equals(id)) continue;
                String s = FamilyKin.sexWord(f.triple().object());
                if (!s.isEmpty()) said.add(s);
            }
            return said.size() == 1 ? said.iterator().next() : "";
        }

        /**
         * The years of what these words say the entry did or was: the dates of their claims about the entry itself, not of its relatives. A
         * year the same words put before the birth they give, or after the death they give, is no year of this life, and is not counted.
         */
        List<Integer> years(String id, List<String> claims) {
            FamilyDate born = given(id, claims, "born-on"), died = given(id, claims, "died-on");
            Set<Integer> out = new TreeSet<>();
            for (String c : claims) {
                Finding f = byId.get(c);
                if (f == null || f.triple() == null || FamilyAccount.personToPerson(f.triple().predicate()) || !g.nodeOf(f, true).equals(id)) continue;
                FamilyDate d = FamilyChecks.claimDate(f);
                if (d == null || born != null && d.year() < born.earliest() || died != null && d.year() > died.latest() + 1) continue;
                out.add(d.year());
            }
            return new ArrayList<>(out);
        }

        /** Whether a person lived in each of these years, as far as their birth and death are known: not born after one, not dead before one. */
        boolean lived(String c, List<Integer> years) {
            FamilyDate b = idx.born(c), d = idx.died(c);
            for (int y : years) if (b != null && b.earliest() > y || d != null && d.latest() < y - 1) return false;
            return true;
        }

        /** The birth or death these words give the entry ("born-on", "died-on"); null when they give none, or two that disagree. */
        FamilyDate given(String id, List<String> claims, String predicate) {
            FamilyDate out = null;
            for (String c : claims) {
                Finding f = byId.get(c);
                if (f == null || f.triple() == null || !f.triple().predicate().equals(predicate) || !g.nodeOf(f, true).equals(id)) continue;
                FamilyDate d = FamilyDate.parse(f.triple().object());
                if (d == null) d = FamilyChecks.claimDate(f);
                if (d == null) continue;
                if (out != null && FamilyDate.apart(out, d, 0)) return null;
                out = d;
            }
            return out;
        }

        /**
         * Whether a person may be the one these words write: nobody the words' sex contradicts (a title or a word of kin with a sex, or a sex
         * the words give, against the person's sex as filed or as their own name's title says it), nobody whose birth or death is another than
         * the one the words give, no described person or description ("an old servant of the family"), no family, and nobody the family said
         * is another person than the entry.
         */
        boolean plausible(String c, String self, String sex, List<String> claims) {
            Graph.Node x = g.node(c);
            if (x == null || c.equals(self) || !"person".equals(x.kind()) || FamilyHouses.isFamily(g, c) || FamilyQuestions.placeholder(x.label()) || descriptive(x.label())) return false;
            if (apart.contains(Graph.pair(c, self))) return false;
            if (!sex.isEmpty()) {
                String theirs = sexOf(c);
                if (theirs.isEmpty()) theirs = titleSex(bareName(x.label()));
                if (!theirs.isEmpty() && !theirs.equals(sex)) return false;
            }
            for (String[] pd : new String[][]{{"born-on", "b"}, {"died-on", "d"}}) {
                FamilyDate said = given(self, claims, pd[0]), theirs = pd[1].equals("b") ? idx.born(c) : idx.died(c);
                if (said != null && theirs != null && FamilyDate.apart(said, theirs, 0)) return false;
            }
            return true;
        }

        /**
         * The people in the order a question lists them: those your library already writes with this very form (as a name of theirs, another
         * name or in a name claim), then those it writes with the same title ("Viscount Hart Tom" for "Viscount Hart"), then those who fit as
         * the relative the words write them beside ({@code fitsAs}), then those the same source writes so in its other words about the entry
         * ({@code namedInFull}: "Tom Hart" there, for its "Mr. Hart"), then those the same source names at all, then by how many facts of these
         * words agree with theirs (a relation, a place, a work, the birth and death they give, the sex the words give), then by name.
         */
        List<String> likeliest(List<String> cands, Group grp, String w, String self, String sex, Set<String> fitsAs, Set<String> namedInFull, List<String> claims) {
            Map<String, Integer> tier = new HashMap<>(), agree = new HashMap<>();
            Set<String> mine = factKeys(self, claims);
            FamilyDate born = given(self, claims, "born-on"), died = given(self, claims, "died-on");
            for (String c : cands) {
                int t = writtenAs(c, w, grp);
                if (t == 2 && !fitsAs.contains(c)) {
                    boolean sameSource = false;
                    for (String id : claimsTouching(c)) { Finding f = byId.get(id); if (f != null && f.sources().stream().anyMatch(s -> grp.source().equals(s.locator()) || Evidence.sameLocator(grp.source(), s.locator()))) { sameSource = true; break; } }
                    t = namedInFull.contains(c) ? 3 : sameSource ? 4 : 5;
                }
                tier.put(c, t);
                int n = 0;
                for (String k : factKeys(c, claimsTouching(c))) if (mine.contains(k)) n++;
                if (born != null && idx.born(c) != null && !FamilyDate.apart(born, idx.born(c), 0)) n++;
                if (died != null && idx.died(c) != null && !FamilyDate.apart(died, idx.died(c), 0)) n++;
                if (!sex.isEmpty() && sex.equals(sexOf(c))) n++;
                agree.put(c, -n);
            }
            List<String> out = new ArrayList<>(cands);
            out.sort(Comparator.comparing((String c) -> tier.get(c)).thenComparing(c -> agree.get(c)));
            return out;
        }

        /** What these claims say of a person, each as its relation and the other side: "occupation miller", "lived-in leeds", "<child-of mary hale". */
        Set<String> factKeys(String id, List<String> claims) {
            Set<String> out = new HashSet<>();
            for (String c : claims) {
                Finding f = byId.get(c);
                if (f == null || f.triple() == null) continue;
                String s = g.nodeOf(f, true), o = g.nodeOf(f, false), p = f.triple().predicate();
                if (p.equals("sex") || p.endsWith("-on") || p.equals("life-event")) continue;   // sex and dates are weighed apart; a life event's words are its own
                if (s.equals(id)) out.add(p + "\u0000" + o);
                else if (o.equals(id)) out.add("<" + p + "\u0000" + s);
            }
            return out;
        }


        boolean person(String id) { Graph.Node x = g.node(id); return x != null && "person".equals(x.kind()) && !FamilyQuestions.placeholder(x.label()); }

        /** Whether a claim is the family's own answer, which is no source's words. */
        boolean answered(Finding f) { return f.sources().stream().anyMatch(s -> s.locator() != null && s.locator().startsWith(SOURCE)); }

        /** The claims that write an entry beside a person as their relative: those from the entry first, then those to it. */
        List<Graph.Edge> relatives(String id) {
            List<Graph.Edge> out = new ArrayList<>();
            for (Graph.Edge e : from.getOrDefault(id, List.of())) if (FamilyAccount.personToPerson(e.predicate()) && person(e.to())) out.add(e);
            for (Graph.Edge e : to.getOrDefault(id, List.of())) if (FamilyAccount.personToPerson(e.predicate()) && person(e.from())) out.add(e);
            return out;
        }

        /**
         * A claim's own words, as a question quotes them: a long quote that writes the name past where it is cut is quoted from a little before
         * the name; a claim with no words of its own is quoted by its sentence.
         */
        String quoted(Finding f, String w) {
            String q = f == null ? "" : FamilyChecks.quoteOf(f).strip().replaceAll("\\s+", " ");
            if (q.isBlank()) return f == null ? "“”" : "“" + f.title().replaceFirst("[.。]$", "") + "”";
            int at = Math.min(nameAt(q, w), q.length());
            if (q.length() <= 160 || at < 0 || at + w.length() < 159) return "“" + Acquisitions.compress(q, 160) + "”";
            int from = Math.max(0, at - 60), space = q.lastIndexOf(' ', from);
            if (space > 0 && from - space < 20) from = space + 1;
            return "“…" + Acquisitions.compress(q.substring(from), 159) + "”";
        }

        private final Map<String, List<String>> bearing = new HashMap<>();

        /**
         * The people who bore a family name, in any script the library reads it in ({@link FamilyMentions#forms}), in any name of theirs: those
         * an entry of the name alone may be. Never an entry of the name alone, with a title or a word of age or without, or a described person,
         * nor anybody the family said is another person than the entry. By name.
         */
        List<String> bearers(String w, String self) {
            return bearing.computeIfAbsent(w + "\u0000" + self, k -> {
                List<String> ws = FamilyMentions.forms(idx, g, w);
                Set<String> found = new LinkedHashSet<>();
                for (String form : ws) for (Graph.Node p : people) for (FamilyNameHistory.Name x : idx.names(p.id())) if (x.hasFamily(form) || FamilyForms.sameForm(familyOf(p.id(), x), form)) { found.add(p.id()); break; }
                List<String> out = new ArrayList<>();
                for (String c : found) {
                    if (c.equals(self) || !own.contains(c) || !person(c) || apart.contains(Graph.pair(c, self))) continue;
                    String bare = withoutFormWords(FamilyNames.untitled(bareName(label(c))));
                    if (FamilyNameQuestions.familyNameAlone(g, g.node(c)) != null || !FamilyMentions.full(bare) && ws.stream().anyMatch(x -> FamilyForms.sameForm(x, bare))) continue;
                    out.add(c);
                }
                out.sort(Comparator.comparing(this::label));
                return out;
            });
        }

        /**
         * The claims about an entry that rest on the words a question quotes ({@code src}): from the same source, with the same words, or with
         * none when the quoted claim has none. Every claim when no source's words are quoted.
         */
        List<String> passage(Finding src, List<String> claims) {
            if (src == null) return claims;
            String q = FamilyChecks.quoteOf(src).replaceAll("\\s+", " ").strip();
            List<String> out = new ArrayList<>();
            for (String c : claims) {
                Finding f = byId.get(c);
                if (f == null || answered(f)) continue;
                if (c.equals(src.id())) { out.add(c); continue; }
                if (f.sources().stream().noneMatch(a -> src.sources().stream().anyMatch(b -> Evidence.sameLocator(a.locator(), b.locator())))) continue;
                String fq = FamilyChecks.quoteOf(f).replaceAll("\\s+", " ").strip();
                if (q.isEmpty() ? fq.isEmpty() : fq.equals(q) || sameWords(q, fq)) out.add(c);
            }
            return out;
        }

        /** The name a claim moved to a person writes for them: their label, where it leads to them, else the name they are filed under. */
        String nameOf(String id) {
            String l = label(id);
            return g.nodeIdOf(l).equals(id) ? l : id;
        }

        /**
         * The claims the quoted words give about an entry written by a family name alone, moved to the one the family chose ({@link
         * FamilySplit#move}); the entry and its other claims stay. {@code where}: the words, as the list of answers names them.
         */
        Done movedTo(Ctx c, Graph.Node n, String where, List<String> passage, String to, String shown) throws IOException {
            List<String> moved = FamilySplit.move(c.store(), g, n.id(), passage, to, c.code());
            String codes = String.join(", ", moved.stream().map(x -> x.replaceFirst("^(F-\\d+).*", "$1")).toList());
            return new Done((moved.size() == 1 ? "The fact " + codes + ", which these words give about “" + n.label() + "”, is" : "The facts " + codes + ", which these words give about “" + n.label() + "”, are")
                    + " now about " + shown + ". “" + n.label() + "” elsewhere in your sources stays as it is. To take it back: researchzosho genealogy who --reopen " + c.code(),
                    where + " is " + shown.replace("“", "").replace("”", ""), "moved=" + String.join(",", moved));
        }

        /** The people written only by this family name ({@link FamilyMentions#isMention}) whose claims quote the same words as these claims do. */
        List<String> sameWordsMentions(Graph.Node n, String w, List<String> claims) {
            List<String> quotes = new ArrayList<>();
            for (String c : claims) { Finding f = byId.get(c); if (f != null && f.sources().stream().noneMatch(s -> s.locator() != null && s.locator().startsWith(SOURCE))) quotes.add(FamilyChecks.quoteOf(f)); }
            List<String> out = new ArrayList<>();
            for (Graph.Node m : people) {
                String[] mp = FamilyMentions.parts(m.label());
                if (m.id().equals(n.id()) || mp == null || !(FamilyForms.sameForm(mp[2], w) || mp[2].equalsIgnoreCase(w)) || apart.contains(Graph.pair(m.id(), n.id()))) continue;
                boolean same = false;
                for (String c : claimsTouching(m.id())) { Finding f = byId.get(c); if (f != null) for (String q : quotes) same |= sameWords(q, FamilyChecks.quoteOf(f)); }
                if (same) out.add(m.id());
            }
            return out;
        }

        /** Whether two quotes are the same words: equal but for spaces, or one holds the other. */
        boolean sameWords(String a, String b) {
            String x = a == null ? "" : a.replaceAll("\\s+", " ").strip(), y = b == null ? "" : b.replaceAll("\\s+", " ").strip();
            if (x.length() < 8 || y.length() < 8) return false;
            return x.equals(y) || x.contains(y) || y.contains(x);
        }

        /**
         * Two parents of one child that the same words give, one written only by a family name and the other written so too or known to be
         * one of the people it may be: after a join brought the two claims to one child they may be one person, and the family is asked.
         */
        void parentsFromOneSentence(List<Posed> out) {
            Map<String, List<String[]>> byChild = new LinkedHashMap<>();   // child → {parent, claim}
            for (Graph.Edge e : g.edges()) {
                if (FamilyKin.gone(e)) continue;
                if (e.predicate().equals("child-of")) byChild.computeIfAbsent(e.from(), k -> new ArrayList<>()).add(new String[]{e.to(), e.findingId()});
                else if (e.predicate().equals("parent-of")) byChild.computeIfAbsent(e.to(), k -> new ArrayList<>()).add(new String[]{e.from(), e.findingId()});
            }
            for (Map.Entry<String, List<String[]>> c : byChild.entrySet()) {
                List<String[]> ls = c.getValue();
                for (int i = 0; i < ls.size(); i++) for (int k = i + 1; k < ls.size(); k++) {
                    String a = ls.get(i)[0], b = ls.get(k)[0];
                    if (a.equals(b) || !(in(a) || in(b) || in(c.getKey())) || !isPerson(a) || !isPerson(b) || apart.contains(Graph.pair(a, b)) || pairsDone.contains(Graph.pair(a, b))) continue;
                    String[] pa = FamilyMentions.parts(label(a)), pb = FamilyMentions.parts(label(b));
                    if (pa == null && pb == null) continue;
                    Finding fa = byId.get(ls.get(i)[1]), fb = byId.get(ls.get(k)[1]);
                    if (fa == null || fb == null || !sameWords(FamilyChecks.quoteOf(fa), FamilyChecks.quoteOf(fb))) continue;
                    boolean fits = pa != null && pb != null ? FamilyForms.sameForm(pa[2], pb[2]) || pa[2].equalsIgnoreCase(pb[2]) : pa != null ? mayBe(a, pa[2], b) : mayBe(b, pb[2], a);
                    if (!fits) continue;
                    pairsDone.add(Graph.pair(a, b));
                    String text = "“" + label(a) + "” and “" + label(b) + "” are both written as parents of " + heading(c.getKey()) + ", by the same words: " + said(fa) + ".";
                    pair(out, a, b, List.of(fa.id(), fb.id()), text, false);
                }
            }
        }

        boolean isPerson(String id) { Graph.Node x = g.node(id); return x != null && "person".equals(x.kind()); }

        /** Whether an entry is written by a family name alone ({@link FamilyNameQuestions#familyNameAlone}), the test its own question is asked by. */
        boolean alone(String id) { Graph.Node x = g.node(id); return x != null && FamilyNameQuestions.familyNameAlone(g, x) != null; }

        /** Whether a person may be the one a described person is: one of its candidates, or somebody a described person of that family name was joined into. */
        boolean mayBe(String mention, String word, String other) {
            if (candidates(label(mention), mention).contains(other)) return true;
            Graph.Node o = g.node(other);
            if (o == null) return false;
            for (String a : o.aliases()) { String[] p = FamilyMentions.parts(a); if (p != null && (FamilyForms.sameForm(p[2], word) || p[2].equalsIgnoreCase(word))) return true; }
            return false;
        }

        /** What the entry is to the relative it is written beside, in the words a described person carries: father, wife, son. */
        String roleOf(String id, Graph.Edge e) {
            boolean mineFrom = e.from().equals(id);
            String sex = sexOf(id);
            String p = e.predicate();
            String up = sex.equals("male") ? "father" : sex.equals("female") ? "mother" : "parent", down = sex.equals("male") ? "son" : sex.equals("female") ? "daughter" : "child";
            return switch (p) {
                case "child-of" -> mineFrom ? down : up;
                case "parent-of" -> mineFrom ? up : down;
                case "married-to" -> sex.equals("male") ? "husband" : sex.equals("female") ? "wife" : "husband or wife";
                case "sibling-of" -> sex.equals("male") ? "brother" : sex.equals("female") ? "sister" : "brother or sister";
                case "adopted-by" -> mineFrom ? "adopted child" : "adoptive parent";
                case "step-parent-of" -> mineFrom ? "step-child" : "step-parent";
                case "parent-in-law-of" -> mineFrom ? "son-in-law or daughter-in-law" : "parent-in-law";
                case "heir-of" -> mineFrom ? "predecessor" : "heir";
                default -> "relative";
            };
        }

        Done merged(Ctx c, String fold, String into, String foldId) throws IOException {
            Graph.Merged m = Graph.merge(c.store(), fold, into, "person", c.reason(), new GenealogyProfile());
            return new Done("“" + fold + "” is joined into “" + into + "”." + (m.claims().isEmpty() ? "" : " " + m.claims().size() + (m.claims().size() == 1 ? " fact" : " facts") + " about “" + fold + "” " + (m.claims().size() == 1 ? "is" : "are") + " now about “" + into + "”.")
                    + " To take it back: researchzosho genealogy who --reopen " + c.code(), "“" + fold + "” is " + into, "merge=" + m.from() + ">" + m.to());
        }

        Done described(Ctx c, Graph.Node n, String w, String placeholder, String other, String where, List<String> passage) throws IOException {
            String fam = familyLabel(w, other);
            if (fam == null) fam = FamilyHouses.family(c.store(), g, w, "", placeholder);
            // the claims these words give move to the described person; the entry of the family name alone keeps every other claim
            boolean known = g.node(g.nodeIdOf(placeholder)) != null || g.curated().get(g.nodeIdOf(placeholder)) != null;
            List<String> moved = FamilySplit.move(c.store(), g, n.id(), passage, placeholder, c.code());
            if (!known) Graph.setKind(c.store(), placeholder, "person");
            Map<String, String> d = FamilyHouses.detail("unstated", "", "", "", "", "", "");
            String id = word(c, new Finding.Triple(placeholder, FamilyHouses.MEMBER, fam), FamilyAccount.sentence(placeholder, FamilyHouses.MEMBER, fam, d) + ".", d);
            // the family just said who that is: somebody of the family the source does not name, so "who is it?" is not asked of it again
            Graph now = FamilyPeople.view(c.store());
            List<String> also = new ArrayList<>();
            for (Posed q : new Work(c.store(), now, Set.of(now.nodeIdOf(placeholder)), false).all())
                if (q.q().kind().equals("family-name-alone")) {
                    append(c.store(), q.q().code() + "\tanswered\t" + LocalDate.now() + "\t" + clean(c.teller()) + "\t" + clean("“" + placeholder + "” is somebody of the " + fam + " whom the source does not name") + "\tmoved=" + String.join(",", moved));
                    also.add(q.q().code());
                }
            return new Done("“" + n.label() + "” in these words is now “" + placeholder + "”, a member of the " + fam + ". “" + n.label() + "” elsewhere in your sources stays as it is. To take it back: researchzosho genealogy who --reopen " + c.code(),
                    where + " is " + placeholder + ", of the " + fam, "moved=" + String.join(",", moved) + "; claims=" + id + (also.isEmpty() ? "" : "; also=" + String.join(",", also)));
        }

        Done intoFamily(Ctx c, Graph.Node n, String w, String where, List<String> passage) throws IOException {
            String fam = familyLabel(w);
            if (fam == null) fam = FamilyHouses.family(c.store(), g, w, "", n.label());
            List<String> moved = FamilySplit.move(c.store(), g, n.id(), passage, fam, c.code());
            return new Done("“" + n.label() + "” in these words is now the family “" + fam + "”. “" + n.label() + "” elsewhere in your sources stays as it is. To take it back: researchzosho genealogy who --reopen " + c.code(),
                    where + " is the " + fam, "moved=" + String.join(",", moved));
        }

        // ── one person or two ──

        private Set<String> near;
        private Map<String, List<String>> claimTexts;

        /**
         * The people a working out about a few people compares them with: those who share with one of them a word of a name, an ending of
         * up to four characters, a whole written form or a reading. Everybody who could pair with them does; nobody else is worked out.
         */
        List<Graph.Node> pool(List<Graph.Node> named) {
            if (scope == null) return named;
            if (near == null) {
                Map<String, Set<String>> keysOf = new HashMap<>();
                Set<String> wanted = new HashSet<>();
                for (Graph.Node p : named) { Set<String> k = quickKeys(p); keysOf.put(p.id(), k); if (in(p.id())) wanted.addAll(k); }
                near = new HashSet<>();
                for (Graph.Node p : named) if (in(p.id()) || keysOf.get(p.id()).stream().anyMatch(wanted::contains)) near.add(p.id());
            }
            return named.stream().filter(x -> near.contains(x.id())).toList();
        }

        /** A person's written forms without working their names out: the label, the other names, and the names and forms their name claims give. */
        List<String> quickTexts(Graph.Node p) {
            if (claimTexts == null) {
                claimTexts = new HashMap<>();
                Map<String, Boolean> nameWords = new HashMap<>();
                for (Finding f : byId.values()) {
                    if (f.triple() == null || !nameWords.computeIfAbsent(f.triple().predicate(), x -> FamilyNameHistory.isNameClaim(f))) continue;
                    if (f.state() == Finding.State.disputed || f.state() == Finding.State.retired || f.state() == Finding.State.superseded) continue;
                    List<String> t = claimTexts.computeIfAbsent(g.nodeOf(f, true), k -> new ArrayList<>());
                    t.add(FamilyNameHistory.written(f));
                    for (FamilyNameHistory.Form x : FamilyNameHistory.formsOf(FamilyDetail.get(f, "forms"))) t.add(x.text());
                    String given = FamilyDetail.get(f, "given");
                    if (!given.isBlank()) t.add(given);
                }
            }
            List<String> out = new ArrayList<>(p.aliases());
            out.add(p.label());
            out.addAll(claimTexts.getOrDefault(p.id(), List.of()));
            // the brackets that tell namesakes apart are no part of the name
            return out.stream().map(t -> t.replaceAll("\\s*[(（][^)）]*[)）]\\s*$", "").strip()).filter(t -> !t.isEmpty()).toList();
        }

        Set<String> quickKeys(Graph.Node p) {
            Set<String> k = new HashSet<>();
            for (String f : quickTexts(p)) {
                String sc = FamilyForms.script(f);
                if (sc.isEmpty()) continue;
                k.add("w" + key(f));
                String cross = FamilyForms.crossKey(f);
                if (cross != null && !cross.isBlank()) k.add("x:" + cross);
                if (sc.equals("latin")) { for (String w : f.split("[^\\p{L}'’]+")) if (!w.isBlank()) k.add(key(w)); }
                else {
                    // the key of an ending is the ending of the key: the characters are made modern once, for the whole form
                    String whole = sc.equals("han") ? FamilyForms.hanKey(f) : FamilyForms.kanaKey(f), tag = sc.equals("han") ? "h:" : "k:";
                    for (int i = 1; i <= Math.min(4, whole.length()); i++) k.add(tag + whole.substring(whole.length() - i));
                }
            }
            return k;
        }

        /** A person's names with both parts known, as keys: {given keys, family keys}. */
        Map<String, Set<String>[]> parts = new HashMap<>();

        @SuppressWarnings("unchecked")
        Set<String>[] partsOf(String id) {
            Set<String>[] have = parts.get(id);
            if (have != null) return have;
            Set<String> givens = new LinkedHashSet<>(), families = new LinkedHashSet<>();
            for (FamilyNameHistory.Name n : idx.names(id)) {
                String fam = n.family(), giv = n.given();
                if (fam.isBlank()) { String[] p = idx.parts(id, n.written()); fam = p[0]; giv = p[0].isBlank() ? "" : p[1]; }
                if (fam.isBlank() || giv.isBlank()) continue;
                if (!key(fam).isEmpty()) families.add(key(fam));
                if (!key(giv).isEmpty()) givens.add(key(giv));
            }
            Set<String>[] out = new Set[]{givens, families};
            parts.put(id, out);
            return out;
        }

        /** Pairs already asked about in this working out, so one pair is one question. */
        final Set<String> pairsDone = new HashSet<>();

        private Map<String, Set<String>> nameKeys;

        /** The people asked about by the keys of their label and other names ({@link FamilyNames#keys}: the words in either order). */
        Map<String, Set<String>> byNameKey() {
            if (nameKeys != null) return nameKeys;
            Map<String, Set<String>> m = new HashMap<>();
            for (Graph.Node p : people) {
                List<String> forms = new ArrayList<>(List.of(p.label())); forms.addAll(p.aliases());
                for (String f : forms) for (String k : FamilyNames.keys(f)) m.computeIfAbsent(k, x -> new LinkedHashSet<>()).add(p.id());
            }
            nameKeys = m;
            return m;
        }

        void onePerson(List<Posed> out) {
            twoFamilyNames(out);
            oneNameTwoEntries(out);
            parentsFromOneSentence(out);
        }

        /** A record that gives one entry a name that is another entry; one given name under two family names with something else that agrees. */
        void twoFamilyNames(List<Posed> out) {
            Set<String> done = pairsDone;
            List<Graph.Node> named = pool(people.stream().filter(n -> !FamilyQuestions.placeholder(n.label())).toList());
            // a record that gives one entry a name that is another entry
            for (Graph.Node p : named) {
                for (FamilyNameHistory.Name n : idx.names(p.id())) {
                    if (n.implicit() || n.claims().isEmpty()) continue;
                    // whether this name is of another family part than another name of the person: then joining is a change of name to ask about
                    boolean changes = idx.names(p.id()).stream().anyMatch(o -> o != n && o.replaces() && !sameFamily(p.id(), o, n));
                    Finding rec = firstQuoted(n.claims());   // the record that gives the name; the family's own answer is none
                    if (rec == null) continue;
                    for (String t : n.texts()) {
                        Set<String> others = new LinkedHashSet<>();
                        String first = g.nodeIdOf(t);
                        others.add(g.node(first) != null ? first : g.nodeIdOf(KanjiForms.modern(t)));
                        // the same name in the other word order, as a tree file's import reads it: Kenji Morita beside Morita Kenji. The entry's
                        // own name written the same way as another entry's is the checks' "written the same way", no record that links them
                        if (Collections.disjoint(FamilyNames.keys(t), FamilyNames.keys(bareName(p.label()))))
                            for (String k : FamilyNames.keys(t)) others.addAll(byNameKey().getOrDefault(k, Set.of()));
                        for (String other : others) {
                            if (other.equals(p.id()) || !person(other) || !own.contains(other) || apart.contains(Graph.pair(p.id(), other)) || !(in(p.id()) || in(other))) continue;
                            // an entry written by a name alone (a book's "Kano", the pool of what it wrote by that word) is nobody of that whole name
                            if (nameAlone(other)) continue;
                            if (!done.add(Graph.pair(p.id(), other))) continue;
                            FamilySame.Comparison c = compare(p.id(), other);
                            if (c.twoPeople()) continue;
                            String text = said(rec) + " gives " + heading(p.id()) + " the name " + t + ", and your library has another person of that name, “" + label(other) + "”" + life(other) + ". " + c.said();
                            List<String> claims = new ArrayList<>(n.claims()); claims.addAll(c.findings());
                            pair(out, p.id(), other, claims, text, changes);
                        }
                    }
                }
            }
            // one given name under two family names, with something else that agrees and nothing that differs
            Map<String, List<String>> byGiven = new LinkedHashMap<>();
            for (Graph.Node p : named) for (String k : partsOf(p.id())[0]) byGiven.computeIfAbsent(k, x -> new ArrayList<>()).add(p.id());
            for (List<String> same : byGiven.values()) {
                if (same.size() < 2) continue;
                for (int i = 0; i < same.size(); i++) for (int k = i + 1; k < same.size(); k++) {
                    String a = same.get(i), b = same.get(k);
                    if (!(in(a) || in(b)) || apart.contains(Graph.pair(a, b)) || done.contains(Graph.pair(a, b))) continue;
                    Set<String> fa = partsOf(a)[1], fb = partsOf(b)[1];
                    if (fa.isEmpty() || fb.isEmpty() || fa.stream().anyMatch(fb::contains) || !share(a, b) || differs(a, b)) continue;
                    FamilySame.Comparison c = compare(a, b);
                    if (c.twoPeople() || c.agree().isEmpty() || !c.differ().isEmpty()) continue;
                    done.add(Graph.pair(a, b));
                    String text = "“" + label(a) + "” and “" + label(b) + "” carry one given name under two family names. " + c.said()
                            + " A person who married into a family, was adopted or became an heir carries two family names in a life.";
                    pair(out, a, b, c.findings(), text, true);
                }
            }
        }

        /** Two entries whose names are one name through a reading a source gave; an entry of a given name alone and the one whole name with it. */
        void oneNameTwoEntries(List<Posed> out) {
            Set<String> done = pairsDone;
            List<Graph.Node> named = pool(people.stream().filter(n -> !FamilyQuestions.placeholder(n.label())).toList());
            // two entries whose names are one name through a reading a source gave: もりた けんじ on one, Morita Kenji on the other
            Map<String, List<String>> byLatin = new HashMap<>();
            for (Graph.Node p : named) for (String t : texts(p)) if (FamilyForms.script(t).equals("latin")) byLatin.computeIfAbsent(FamilyForms.latinKey(t), x -> new ArrayList<>()).add(p.id());
            for (Graph.Node a : named) {
                List<String> mine = texts(a);
                Set<String> ownLatin = new HashSet<>();
                for (String t : mine) if (FamilyForms.script(t).equals("latin")) ownLatin.add(FamilyForms.latinKey(t));
                for (String r : mine) {
                    if (!FamilyForms.script(r).equals("kana")) continue;
                    String ck = FamilyForms.crossKey(r);
                    if (ck == null || ck.isBlank() || ownLatin.contains(ck)) continue;
                    for (String b : byLatin.getOrDefault(ck, List.of())) {
                        if (b.equals(a.id()) || !(in(a.id()) || in(b)) || apart.contains(Graph.pair(a.id(), b)) || !done.add(Graph.pair(a.id(), b))) continue;
                        FamilySame.Comparison c = compare(a.id(), b);
                        if (c.twoPeople() || !c.differ().isEmpty()) continue;
                        String latin = texts(g.node(b)).stream().filter(t -> FamilyForms.script(t).equals("latin") && FamilyForms.latinKey(t).equals(ck)).findFirst().orElse(label(b));
                        List<String> whence = readingSources(a.id(), r);
                        String text = r + " is the reading of " + heading(a.id()) + "'s name" + (whence.isEmpty() ? "" : " that " + String.join(" and ", whence) + " gives") + ", and in Latin letters it is " + latin
                                + ", the name of another person in your library, “" + label(b) + "”" + life(b) + ". " + c.said();
                        pair(out, a.id(), b, c.findings(), text, false);
                    }
                }
            }
            // an entry of a given name alone, and the one entry of a whole name with that given name. A romanised Japanese name whose order
            // nothing settles (Morita Shoichi) has no known given part: each of its words is one part of it, and a word alone that is a part of
            // exactly one such name is offered as that person, never taken for a family name or a given name
            Map<String, List<String>> fullByGiven = new HashMap<>(), fullByPart = new HashMap<>();
            for (Graph.Node p : named) {
                Set<String> unsure = new LinkedHashSet<>();
                for (String t : texts(p)) { String b = bareName(t); if (idx.orderUnknown(p.id(), b)) for (String x : b.split("\\s+")) if (!key(x).isEmpty()) unsure.add(key(x)); }
                for (String k : givensOfForms(p)) if (!unsure.contains(k)) fullByGiven.computeIfAbsent(k, x -> new ArrayList<>()).add(p.id());
                for (String k : unsure) fullByPart.computeIfAbsent(k, x -> new ArrayList<>()).add(p.id());
            }
            for (Graph.Node gnode : named) {
                String w = gnode.label().replaceAll("\\s*[(（][^)）]*[)）]\\s*$", "").strip();
                if (w.isEmpty() || w.matches(".*[\\s　・,].*") || key(w).isEmpty()) continue;
                Set<String> full = new LinkedHashSet<>(fullByGiven.getOrDefault(key(w), List.of()));
                full.addAll(fullByPart.getOrDefault(key(w), List.of()));
                full.remove(gnode.id());
                if (full.size() != 1) continue;
                String f = full.iterator().next();
                boolean asGiven = fullByGiven.getOrDefault(key(w), List.of()).contains(f);
                if (!(in(gnode.id()) || in(f)) || apart.contains(Graph.pair(gnode.id(), f)) || done.contains(Graph.pair(gnode.id(), f))) continue;
                if (!partsOf(gnode.id())[1].isEmpty() || words(g).familyPart(w, gnode.id()) != null) continue;   // a whole name, or a family name alone, which the question above is about
                done.add(Graph.pair(gnode.id(), f));
                FamilySame.Comparison c = compare(gnode.id(), f);
                if (c.twoPeople() || !c.differ().isEmpty()) continue;
                // the source's own words come first: the family judges whom a name alone means by what the source says of it, as the
                // library cannot (a husband written in another script agrees with nothing the library can compare)
                String text = asGiven
                        ? "“" + gnode.label() + "” is written with a given name alone, and " + heading(f) + " is the only person in your library whose given name is " + w + ". " + whereWritten(gnode.id()) + c.said()
                        : "“" + gnode.label() + "” is written as one word, and " + heading(f) + " is the only person in your library whose name has the word " + w + ". " + whereWritten(gnode.id())
                          + "A Japanese name in Latin letters is written family name first as often as given name first, and nothing in your library says which word of "
                          + heading(f) + " is the family name, so the library does not take " + w + " for either. " + c.said();
                pair(out, gnode.id(), f, c.findings(), text, false, true);   // the one word goes into the whole name, in whatever letters
            }
            // an entry whose name in characters is another entry's name with its two words the other way round: 勇 森田, as older reads of Geni
            // wrote names given name first, beside 森田勇. Their keys never meet, and the order of the words does not say who is who: asked
            for (Graph.Node a : people) {
                String w = bareName(a.label());
                String[] words = w.split("[\\s　]+");
                if (FamilyQuestions.placeholder(a.label()) || words.length != 2 || !FamilyForms.script(w).equals("han") || w.matches(".*\\p{IsLatin}.*")) continue;
                Set<String> swapped = FamilyNames.keys(words[1] + words[0]);
                if (swapped.isEmpty() || !Collections.disjoint(swapped, FamilyNames.keys(w))) continue;
                // which of the two writes the given name first is told by the family names the library knows, never by which was filed first
                boolean firstIsFamily = familyWord(words[0]) && !familyWord(words[1]), secondIsFamily = familyWord(words[1]) && !familyWord(words[0]);
                for (String k : swapped) for (String b : byNameKey().getOrDefault(k, Set.of())) {
                    if (b.equals(a.id()) || !person(b) || !(in(a.id()) || in(b)) || apart.contains(Graph.pair(a.id(), b)) || !done.add(Graph.pair(a.id(), b))) continue;
                    FamilySame.Comparison c = compare(a.id(), b);
                    if (c.twoPeople()) continue;
                    boolean bothSpaced = bareName(label(b)).split("[\\s　]+").length == 2;
                    String givenFirst = firstIsFamily ? b : a.id(), familyFirst = firstIsFamily ? a.id() : b;
                    if (!firstIsFamily && !secondIsFamily && bothSpaced) {
                        // neither order can be told: the question says so, and the answer joins them by the library's usual rule, which it names
                        String text = "“" + a.label() + "” and “" + label(b) + "” are one name written in two orders. Some family trees and older reads of Geni write the given name first, and the library cannot tell which of the two does. " + c.said();
                        pair(out, a.id(), b, c.findings(), text, false);
                        continue;
                    }
                    String text = "“" + label(givenFirst) + "” and “" + label(familyFirst) + "” are one name written in two orders: “" + label(givenFirst) + "” writes the given name first, as some family trees and older reads of Geni do. " + c.said();
                    pair(out, givenFirst, familyFirst, c.findings(), text, false, true);   // on "one person", the name written family name first is the one kept
                }
            }
        }

        /** Whether the library knows a word as a family name: the family part of a name claim, or the name of a family. */
        boolean familyWord(String w) { return idx.familyParts().stream().anyMatch(f -> FamilyForms.sameForm(f, w)); }

        /**
         * The given parts of the whole names of a person, as keys: of its names with both parts known, and of each form in characters or kana
         * the library splits into a family part and a given part, so an entry filed in Latin letters with 森田健二 among its forms has the given
         * name 健二, and of a name's form in Latin letters whose family word a reading gives, so 森田健二 read もりた けんじ and written Morita
         * Kenji has the given name Kenji. Empty for a person with no whole name.
         */
        Set<String> givensOfForms(Graph.Node p) {
            Set<String> out = new LinkedHashSet<>(partsOf(p.id())[0]);
            for (String t : texts(p)) {
                String sc = FamilyForms.script(t);
                if (!sc.equals("han") && !sc.equals("kana")) continue;
                String[] parts = idx.parts(p.id(), bareName(t));
                if (!parts[0].isBlank() && !parts[1].isBlank() && !key(parts[1]).isEmpty()) out.add(key(parts[1]));
            }
            // a name's two-word form in Latin letters whose one word a reading ties to the name's family part in characters (Morita Kenji
            // for 森田健二 read もりた けんじ): the other word is its given name in Latin letters
            for (FamilyNameHistory.Name n : idx.names(p.id())) {
                if (n.family().isBlank() || n.given().isBlank() || FamilyForms.script(n.family()).equals("latin")) continue;
                for (String t : n.texts()) {
                    String[] w = bareName(t).split("\\s+");
                    if (!FamilyForms.script(t).equals("latin") || w.length != 2 || n.hasFamily(w[0]) == n.hasFamily(w[1])) continue;
                    String given = n.hasFamily(w[0]) ? w[1] : w[0];
                    if (!key(given).isEmpty()) out.add(key(given));
                }
            }
            return out;
        }

        /** Every written form of a person: label, other names, and the forms of the names the claims give. */
        List<String> texts(Graph.Node p) {
            List<String> out = new ArrayList<>(List.of(p.label()));
            for (String a : p.aliases()) if (!out.contains(a)) out.add(a);
            for (FamilyNameHistory.Name n : idx.names(p.id())) for (String t : n.texts()) if (!out.contains(t)) out.add(t);
            return out;
        }

        /** Where a reading of a person's name came from: the sources of the other name, or of the name claim that lists it. */
        List<String> readingSources(String id, String reading) {
            List<String> out = new ArrayList<>();
            for (String s : aliasSource(id, reading)) { String x = FamilyChecks.from(s); if (!x.isBlank() && !out.contains(x)) out.add(x); }
            for (FamilyNameHistory.Name n : idx.names(id)) {
                if (!n.texts().contains(reading)) continue;
                for (String c : n.claims()) { Finding f = byId.get(c); if (f != null) { String x = FamilyChecks.fromAll(f); if (!x.isBlank() && !out.contains(x)) out.add(x); } }
            }
            return out;
        }

        /**
         * Whether {@code a} goes into {@code b} when the two are one person, by the rule of {@link FamilyDecisions#foldsInto} (a described
         * person or a part of a name into the whole name; a bare name into one that tells namesakes apart; a name with no birth into one with
         * a birth; else the one fewer standing claims are about), read from what this working out holds already.
         */
        boolean folds(String a, String b) {
            String al = label(a), bl = label(b);
            if (FamilyQuestions.placeholder(al) || FamilyChecks.partOfName(al, bl)) return true;
            if (FamilyQuestions.placeholder(bl) || FamilyChecks.partOfName(bl, al)) return false;
            boolean ta = al.strip().matches("(?s).+[(（][^)）]+[)）]$"), tb = bl.strip().matches("(?s).+[(（][^)）]+[)）]$");
            if (ta != tb) return tb;
            // one person written in Latin letters and in characters: the characters decide which family, so the entry in them is kept
            String sa = FamilyForms.script(bareName(al)), sb = FamilyForms.script(bareName(bl));
            if (sa.equals("latin") && sb.equals("han")) return true;
            if (sa.equals("han") && sb.equals("latin")) return false;
            boolean ba = idx.born(a) != null, bb = idx.born(b) != null;
            if (ba != bb) return bb;
            return standing(a) < standing(b);
        }

        /** How many draft or accepted claims are about a node. */
        int standing(String id) {
            Set<String> ids = new HashSet<>();
            for (String c : claimsTouching(id)) { Finding f = byId.get(c); if (f != null && (f.state() == Finding.State.draft || f.state() == Finding.State.accepted)) ids.add(c); }
            return ids.size();
        }

        /** {@code namesChange}: the two carry two family names, so joining them makes a change of name the family is asked about next. */
        void pair(List<Posed> out, String a, String b, List<String> claims, String text, boolean namesChange) { pair(out, a, b, claims, text, namesChange, folds(a, b)); }

        /** The same, with {@code aIntoB}: whether {@code a} goes into {@code b} when the family says they are one person. */
        void pair(List<Posed> out, String a, String b, List<String> claims, String text, boolean namesChange, boolean aIntoB) { pair(out, a, b, claims, text, namesChange, aIntoB, List.of()); }

        /** The same, with the other people the question turns on ({@link Question#turnsOn}): the children two parents share. */
        void pair(List<Posed> out, String a, String b, List<String> claims, String text, boolean namesChange, boolean aIntoB, List<String> turnsOn) {
            // an entry written by a family name alone holds what every source wrote with the name alone, which may be several people: "one
            // person" would join all of it into one. Who it is, is asked by its own question, whose answers move the words it quotes
            if (alone(a) || alone(b)) return;
            String al = label(a), bl = label(b);
            String fold = aIntoB ? al : bl, into = aIntoB ? bl : al;
            List<Option> opts = List.of(
                    new Option("one", "One person", "joins “" + fold + "” into “" + into + "”: the facts about “" + fold + "” are then about " + into + ", and " + fold + " is another name of " + into
                            + "." + (namesChange ? " Then the library asks how and when the name changed." : "") + " To take it back: " + REOPEN_HERE),
                    new Option("two", "Two people", "writes down that “" + al + "” and “" + bl + "” are two people, and this is not asked again. To take it back: " + REOPEN_HERE));
            Map<String, Act> acts = new LinkedHashMap<>();
            acts.put("one", x -> merged(x, fold, into, ""));
            acts.put("two", x -> {
                Graph.different(x.store(), al, bl, "person", x.reason());
                return new Done("“" + al + "” and “" + bl + "” are written down as two people. To take it back: researchzosho genealogy who --reopen " + x.code(), "“" + al + "” and “" + bl + "” are two people", "apart=" + a + "|" + b);
            });
            add(out, "one-person", List.of(a, b), claims, text + " Are they one person?", opts, acts, turnsOn);
        }

        // ── how a name changed ──

        void nameChangeHow(List<Posed> out) {
            for (Graph.Node p : people) {
                if (!in(p.id()) || FamilyQuestions.placeholder(p.label())) continue;
                List<FamilyNameHistory.Name> names = idx.names(p.id());
                // a name written only with a title, or as a family name alone, is a form of a name, never a name of its own: it raises no question.
                // Nor does a name an index gives only as a cross-reference ("Endō, Kenji. See Morita, Kenji"): it says the two forms are one
                // person's, and nothing of how the name came
                List<FamilyNameHistory.Name> replacing = names.stream().filter(FamilyNameHistory.Name::replaces).filter(x -> !idx.formOnly(p.id(), x) && !idx.crossReferenceOnly(x)).toList();
                Map<FamilyNameHistory.Name, String> asked = new LinkedHashMap<>();   // each name asked about, with what the question says of why
                if (replacing.size() >= 2) for (FamilyNameHistory.Name n : names) {
                    if (idx.formOnly(p.id(), n) || idx.crossReferenceOnly(n)) continue;
                    // a name the library worked out as the marriage's, asked about where the family names the person: one answer changes it
                    if (workedOutToo && n.cameWithTheMarriage()) { asked.put(n, "The library worked out that the name came with the marriage, as nothing it holds points to more. "); continue; }
                    // its kind is known: given by a claim or worked out from the evidence. A married name that something points beyond (an
                    // adoption, an entry into that family) is the family's to explain all the same: 婿養子, 養女, or only the name at the marriage
                    if (n.explained() && !marriedInUnsaid(p.id(), n)) continue;
                    // an older library's other name in Latin letters or kana that the names in characters could not place is a way of
                    // writing one of them (Kenji Endo beside 遠藤健二 and 森田健二): the names in characters are asked about, not it
                    if (writesANameInCharacters(p, n, names)) continue;
                    if (replacing.stream().noneMatch(o -> o != n && !sameFamily(p.id(), o, n))) continue;
                    // the name the entry is filed under, with no source of its own, is the name before a dated change a source gives: the
                    // question is how that change came, asked of the later name when its way is not known
                    // unless the person married somebody of the name's family part after that change, with which it may have come
                    if (n.implicit() && (n.isForm(p.label()) || n.isForm(bareName(p.label()))) && replacing.stream().anyMatch(o -> o != n && !o.implicit() && !o.kind().equals("birth") && (o.from() != null || o.fromEvent()) && !sameFamily(p.id(), o, n)
                            && (settledFrom(p.id(), o) == null || !marriedIntoAfter(p.id(), n, settledFrom(p.id(), o).year())))) continue;
                    // the name before a change the evidence explains (a married name, a name taken on a dated adoption) is worked out: nothing to ask
                    if (beforeAnExplainedChange(p.id(), n, names)) continue;
                    // a name of the family part a step-parent carried: how it came is what a step-parent is, and nothing is asked
                    if (stepParentCarries(p.id(), familyOf(p.id(), n))) continue;
                    asked.put(n, null);
                }
                // one question for two names, never one from each side: the question about a name says "and also the name" of the other, so
                // the name the entry is filed under, which no source gives, is asked about only when no other name is
                for (Map.Entry<FamilyNameHistory.Name, String> a : asked.entrySet())
                    if (asked.size() == 1 || !filedUnderOnly(p, a.getKey())) how(out, p, a.getKey(), names, a.getValue());
                // a sole name the library dated from a marriage into a family, with its way not known: marrying into a family can mean more
                // than a marriage (婿養子, 養女, 入夫), so the family is asked how it came, with the year shown
                if (replacing.size() == 1) {
                    FamilyNameHistory.Name n = replacing.get(0);
                    String fam = familyOf(p.id(), n);
                    if (n.workedOut() && !n.explained() && n.from() != null && FamilyNameHistory.BASIS_ENTRY_YEAR.equals(n.basis()))
                        how(out, p, n, names, "A source says " + p.label() + " married into the " + (fam.isBlank() ? "family" : fam + " family") + " " + n.from().in()
                                + ", so the library dated the name from then. Marrying into a family can mean more than a marriage. ");
                }
                // an adoption as 婿養子 without its marriage or the family he entered
                Map<String, List<Finding>> adoptions = new LinkedHashMap<>();
                for (Graph.Edge e : from.getOrDefault(p.id(), List.of())) if (e.predicate().equals("adopted-by") && byId.get(e.findingId()) != null) adoptions.computeIfAbsent(e.to(), k -> new ArrayList<>()).add(byId.get(e.findingId()));
                for (Map.Entry<String, List<Finding>> a : adoptions.entrySet()) {
                    if (!adoptionKind(a.getValue()).equals("mukoyoshi")) continue;
                    boolean married = from.getOrDefault(p.id(), List.of()).stream().anyMatch(e -> e.predicate().equals("married-to")) || to.getOrDefault(p.id(), List.of()).stream().anyMatch(e -> e.predicate().equals("married-to"));
                    boolean member = !FamilyHouses.families(g, p.id()).isEmpty();
                    // the adoption by a parent whose family part the person carries in a name is the entry into that family: the name was taken on it
                    boolean entered = member || carries(p.id(), familyPartOf(a.getKey()));
                    if (married && entered) continue;
                    halves(out, p, a.getKey(), a.getValue(), married, member, entered);
                }
            }
        }

        /** Whether a name is only the one the entry is filed under: no claim gives it, and it is a form of the entry's label. */
        boolean filedUnderOnly(Graph.Node p, FamilyNameHistory.Name n) { return n.implicit() && (n.isForm(p.label()) || n.isForm(bareName(p.label()))); }

        /** The kind of an adoption: the family's own word when they gave one, else the claims' ({@link FamilyNameHistory#adoptionKind}). */
        String adoptionKind(List<Finding> claims) {
            for (Finding f : claims) if (f.state() == Finding.State.accepted && f.review() != null && "person".equals(f.review().reviewer())) return FamilyNameHistory.adoptionKind(f);
            for (Finding f : claims) { String k = FamilyNameHistory.adoptionKind(f); if (!k.equals("unstated") && !k.equals("ordinary")) return k; }
            return claims.isEmpty() ? "unstated" : FamilyNameHistory.adoptionKind(claims.get(0));
        }

        /** The claim of a relation between two people, either way round; null when there is none. */
        String relation(String a, String predicate, String b) {
            for (Graph.Edge e : from.getOrDefault(a, List.of())) if (e.predicate().equals(predicate) && e.to().equals(b)) return e.findingId();
            if (predicate.equals("married-to")) for (Graph.Edge e : to.getOrDefault(a, List.of())) if (e.predicate().equals(predicate) && e.from().equals(b)) return e.findingId();
            return null;
        }

        void how(List<Posed> out, Graph.Node p, FamilyNameHistory.Name n, List<FamilyNameHistory.Name> names, String because) {
            String id = p.id(), who = p.label(), fam = familyOf(id, n), name = text(n);
            String sex = sexOf(id);
            boolean man = sex.equals("male"), woman = sex.equals("female");
            // who else carried that family part: a husband or wife, and the one who adopted them
            String spouse = null, spouseClaim = null;
            boolean anySpouse = false;
            for (Graph.Edge e : from.getOrDefault(id, List.of())) if (e.predicate().equals("married-to")) { anySpouse = true; if (spouse == null && carries(e.to(), fam)) { spouse = e.to(); spouseClaim = e.findingId(); } }
            for (Graph.Edge e : to.getOrDefault(id, List.of())) if (e.predicate().equals("married-to")) { anySpouse = true; if (spouse == null && carries(e.from(), fam)) { spouse = e.from(); spouseClaim = e.findingId(); } }
            String adopter = null, adoption = null;
            for (Graph.Edge e : from.getOrDefault(id, List.of())) if (e.predicate().equals("adopted-by")) { adopter = e.to(); adoption = e.findingId(); if (carries(e.to(), fam)) break; }
            String membership = null;
            for (FamilyHouses.Membership m : FamilyHouses.families(g, id)) if (!fam.isBlank() && FamilyForms.sameForm(FamilyHouses.nameOf(FamilyHouses.labelOf(g, m.family())), fam) && !m.how().isBlank()) { membership = m.how(); break; }
            boolean hasBirth = names.stream().anyMatch(x -> x.kind().equals("birth"));
            FamilyNameHistory.Name earlier = names.stream().filter(x -> x != n && x.replaces() && !sameFamily(id, x, n)).findFirst().orElse(null);
            // the first name of the life by the order the names are known in: nothing before it of another family part
            int at = names.indexOf(n);
            boolean first = at >= 0 && names.subList(0, at).stream().noneMatch(x -> x.replaces() && !sameFamily(id, x, n));
            StringBuilder t = new StringBuilder();
            String since = n.from() != null ? " from " + n.from().phrase() : "";
            if (earlier != null && earlier.kind().equals("birth")) t.append(heading(id)).append(" was born ").append(text(earlier)).append(" and later had the name ").append(name).append(since).append(". ");
            else t.append(heading(id)).append(" had the name ").append(name).append(since).append(earlier == null ? "" : ", and also the name " + text(earlier)).append(". ");
            if (!n.claims().isEmpty()) { Finding f = byId.get(n.claims().get(0)); if (f != null) t.append("Where the name comes from: ").append(said(f)).append(". "); }
            else t.append("The library has this name for the person, but no source says how it came. ");
            if (spouse != null && man) t.append(who).append(" was married to ").append(label(spouse)).append(", whose family name was ").append(fam).append(". ");
            else if (spouse != null) t.append(who).append(" was married to ").append(label(spouse)).append(", of the ").append(fam).append(" family. ");
            if (adopter != null) t.append(who).append(" was adopted by ").append(label(adopter)).append(". ");
            if (because != null) t.append(because);
            t.append("How did ").append(who).append(" get the name ").append(name).append("?");
            // the answers that fit, the one the evidence points to first. A name of the family part the husband or wife carried, for a man and
            // a woman alike: 婿養子 or 養女 as fits (a husband is adopted as 婿養子, a wife as 養女; both where neither one's sex is filed), 入夫,
            // an adoption, only the name at the marriage; that last one first where the library worked it out so. 婿養子 and 入夫 are a husband's
            List<String> kinds = new ArrayList<>();
            if (membership != null && !(spouse != null && membership.equals("marriage"))) kinds.add(switch (membership) { case "adoption" -> "adoptive"; case "marriage", "mukoyoshi", "nyufu", "succession" -> membership; default -> ""; });
            if (spouse != null) {
                boolean husband = man || (!woman && sexOf(spouse).equals("female")), wife = woman || (!man && sexOf(spouse).equals("male"));
                if (n.workedOut() && n.kind().equals("marriage")) kinds.add("marriage");
                if (!wife) kinds.add("mukoyoshi");
                if (!husband) kinds.add("yojo");
                if (!wife) kinds.add("nyufu");
                kinds.add("adoptive");
                kinds.add("marriage");
                if (first && !hasBirth) kinds.add("birth");
                // what else it may have come by: an heir's adoption, becoming the head, a legal change or a will, or only another name; the heir
                // and the succession first where an heirship, or being the family's head or heir, is why it is asked
                boolean heirship = from.getOrDefault(id, List.of()).stream().anyMatch(e -> e.predicate().equals("heir-of"))
                        || FamilyHouses.families(g, id).stream().anyMatch(m -> (m.role().equals("head") || m.role().equals("heir")) && FamilyForms.sameForm(FamilyHouses.nameOf(FamilyHouses.labelOf(g, m.family())), fam));
                kinds.addAll(heirship ? List.of("heir", "succession", "legal", "aka") : List.of("legal", "aka", "heir", "succession"));
            } else {
                if (first && !hasBirth) kinds.add("birth");
                if (adopter != null) { kinds.add("adoptive"); kinds.add("heir"); }
                kinds.add("marriage"); if (!woman) kinds.add("mukoyoshi");
                kinds.add("adoptive"); kinds.add("heir"); kinds.add("succession"); kinds.add("legal");
                if (!hasBirth && n.from() == null && !n.fromEvent()) kinds.add("birth");   // a name a source dates later in the life is no name at birth
                kinds.add("aka");
            }
            if (earlier != null && names.stream().anyMatch(x -> x != n && x.kind().equals("birth") && sameFamily(id, x, n))) kinds.add(0, "taken-back");
            if (woman) kinds.removeAll(List.of("mukoyoshi", "nyufu"));
            List<String> order = new ArrayList<>(new LinkedHashSet<>(kinds.stream().filter(k -> !k.isBlank()).toList()));
            // eight answers and "later" at most: seven ways it came when the answer that it was never theirs is offered too
            int room = n.written().equals(p.label()) ? 8 : 7;
            if (order.size() > room) order = new ArrayList<>(order.subList(0, room));
            List<Option> opts = new ArrayList<>();
            Map<String, Act> acts = new LinkedHashMap<>();
            String sp = spouse, spc = spouseClaim, ad = adopter, adc = adoption;
            String famLabel = fam.isBlank() ? null : familyLabel(fam, sp, ad);
            String famShown = fam.isBlank() ? "" : famLabel != null ? noArticle(famLabel) : fam + " family";
            // whoever took the husband's or wife's name without an adoption was not adopted into that family: the answer disputes such an adoption
            List<String> denied = sp != null ? adoptionsInto(id, fam, sp).stream().map(e -> label(e.to())).distinct().toList() : List.of();
            for (String k : order) {
                String[] w = kindWords(k, who, name, fam, famShown, sp == null ? null : label(sp), ad == null ? null : label(ad), sex, anySpouse, denied.isEmpty() ? null : String.join(" and ", denied));
                opts.add(new Option(k, w[0], w[1]));
                acts.put(k, x -> howAnswer(x, p, n, k, fam, sp, spc, ad, adc));
            }
            // the family may know it was never a name of theirs: a reading that filed an editor's or a relative's name as the person's. The
            // name the entry is filed under is theirs by the sources that name the entry so, and is not offered
            if (!n.written().equals(p.label())) opts.add(new Option("not-theirs", "It was never a name of " + who, "takes the name " + name + " off " + who
                    + (n.claims().isEmpty() ? "" : ", and marks the facts that give it as disputed, with your answer as the reason. They stay on record")
                    + ". To take it back: " + REOPEN_HERE));
            if (!n.written().equals(p.label())) acts.put("not-theirs", x -> notTheirs(x, id, n));
            List<String> claims = new ArrayList<>(n.claims()); claims.add("name: " + n.written());
            add(out, "name-change-how", List.of(id), claims, t.toString(), opts, acts);
        }

        private Map<String, String> joined;

        /**
         * The name an answer files its claims under: the subject as a claim the question rests on writes it, while that still leads to this
         * entry, so the family's word follows the entry the question was about through later joins and take-backs as that claim does; else
         * the name itself when it is the label of an entry that was joined into this one; else the entry's label.
         */
        String filedUnder(String id, List<String> claims, String name) {
            for (String c : claims) {
                Finding f = byId.get(c);
                if (f == null) f = idx.finding(c);
                if (f == null || f.triple() == null || f.triple().subject().isBlank()) continue;
                // a name that leads to the entry by itself: an answer is filed under it, and a mention linked to the entry leads there only
                // through its claim, so its words ("Mary" alone) are no name to file under
                if (g.nodeIdOf(f.triple().subject()).equals(id)) return f.triple().subject();
            }
            if (name != null && !name.isBlank()) {
                if (joined == null) { try { joined = Graph.merges(store); } catch (IOException e) { joined = Map.of(); } }
                if (joined.containsKey(Vocabulary.norm(name)) && g.nodeIdOf(name).equals(id)) return name;
            }
            return label(id);
        }

        private static List<String> with(List<String> claims, String more) { List<String> out = new ArrayList<>(claims); out.add(more); return out; }

        /** A man's adoptions, as the claims give them, into the family whose name his wife carried: by a parent of hers, or by one of that family name. */
        List<Graph.Edge> adoptionsInto(String id, String fam, String spouse) {
            Set<String> hers = new HashSet<>();
            for (Graph.Edge e : from.getOrDefault(spouse, List.of())) if (e.predicate().equals("child-of")) hers.add(e.to());
            for (Graph.Edge e : to.getOrDefault(spouse, List.of())) if (e.predicate().equals("parent-of")) hers.add(e.from());
            List<Graph.Edge> out = new ArrayList<>();
            for (Graph.Edge e : from.getOrDefault(id, List.of())) if (e.predicate().equals("adopted-by") && (hers.contains(e.to()) || carries(e.to(), fam))) out.add(e);
            return out;
        }

        /** The family part of a person's names: the first their names give; "" when none is known. */
        String familyPartOf(String id) {
            for (FamilyNameHistory.Name x : idx.names(id)) { String f = familyOf(id, x); if (!f.isBlank()) return f; }
            return "";
        }

        /** Whether a step-parent of the person carried this family part in a name: a claim that they are the person's step-parent. */
        boolean stepParentCarries(String id, String fam) {
            if (fam == null || fam.isBlank()) return false;
            for (Graph.Edge e : to.getOrDefault(id, List.of())) if (e.predicate().equals("step-parent-of") && person(e.from()) && carries(e.from(), fam)) return true;
            return false;
        }

        /**
         * Whether an entry is written by a name alone, no whole name: a family name or a given name alone, with a title or without ("Kano",
         * "Mr. Kano", 遠藤), a described person, or an entry the family's questions ask about as a family name alone. Such an entry holds what
         * sources wrote by that word, of one person or several, and is never "another person of that name" beside a whole name.
         */
        boolean nameAlone(String id) {
            Graph.Node x = g.node(id);
            if (x == null) return true;
            String l = x.label();
            if (FamilyQuestions.placeholder(l) || FamilyMentions.isMention(l) || alone(id)) return true;
            for (FamilyNameHistory.Name n : idx.names(id)) if (n.isForm(l) || n.isForm(bareName(l))) return !idx.whole(id, n);
            return FamilyNames.oneWord(l);
        }

        /** Whether a person carried this family part in any of their names. */
        boolean carries(String id, String fam) {
            if (fam == null || fam.isBlank()) return false;
            for (FamilyNameHistory.Name x : idx.names(id)) { String f = familyOf(id, x); if (!f.isBlank() && FamilyForms.sameForm(f, fam)) return true; if (x.hasFamily(fam)) return true; }
            return false;
        }

        /**
         * What each kind of answer says, and what it files: {says, does}. {@code famPart}: the name's family part (Ellis); {@code fam}: the
         * family it names (the Ellis family). {@code spouse}: the husband or wife who carried that family part, null when none did;
         * {@code anySpouse}: whether any marriage is known. A marriage is put in words that fit what is known of the person's sex.
         */
        String[] kindWords(String k, String who, String name, String famPart, String fam, String spouse, String adopter, String sex, boolean anySpouse, String denied) {
            String filed = "saves as your answer: " + who + " was named " + name + " ";
            String back = ". To take it back: " + REOPEN_HERE;
            boolean man = sex.equals("male"), woman = sex.equals("female");
            Function<String, String> entered = how -> fam.isBlank() ? "" : "; " + who + " entered the " + fam + how;
            String part = famPart == null || famPart.isBlank() ? "" : ", " + famPart;
            return switch (k) {
                case "birth" -> new String[]{"It is the name " + who + " was born with", filed + "at birth" + back + "."};
                case "mukoyoshi" -> new String[]{"As 婿養子: adopted by " + (spouse == null ? "his wife's parent" : spouse + "'s parent") + " and married to " + (spouse == null ? "their daughter" : spouse) + " on entering the family",
                        filed + "on entering the family as 婿養子" + entered.apply(" as 婿養子") + (adopter == null ? "" : "; " + who + " was adopted by " + adopter + " as 婿養子") + back + "."};
                case "marriage" -> {
                    String says;
                    if (woman) says = spouse != null ? "She took her husband's family name" + part + ", when she married " + spouse
                            : anySpouse ? "She took the family name" + (famPart == null || famPart.isBlank() ? "" : " " + famPart) + " at a marriage your library does not have"
                            : "She took her husband's family name" + part + ", when she married";
                    else if (man) says = "He took his wife's family name when he married, without an adoption";
                    else says = spouse != null ? "At the marriage to " + spouse + ", taking the family name" + (famPart == null || famPart.isBlank() ? "" : " " + famPart) + ": a wife taking her husband's name, or a husband taking his wife's without an adoption"
                            : "It was taken at a marriage";
                    String their = man ? "his" : woman ? "her" : who + "'s";
                    yield new String[]{says, filed + "at marriage" + (spouse != null ? entered.apply(" by marriage") + "; " + spouse + "'s parents are " + their + " parents-in-law, not " + their + " adoptive parents"
                            + (denied == null ? "" : "; " + their + " adoption by " + denied + " is marked as disputed, with your answer as the reason") : "") + back + "."};
                }
                case "yojo" -> new String[]{"As 養女: adopted by " + (spouse == null ? "her husband's parent" : spouse + "'s parent") + " and married to " + (spouse == null ? "their son" : spouse) + " on entering the family",
                        filed + "on adoption" + entered.apply(" by adoption") + back + "."};
                case "nyufu" -> new String[]{"By 入夫 marriage: he entered the house of his wife, who was its head", filed + "on entering the family by 入夫 marriage" + entered.apply(" by 入夫 marriage") + back + "."};
                case "adoptive" -> new String[]{adopter == null ? "By adoption" : "When adopted by " + adopter, filed + "on adoption" + entered.apply(" by adoption") + back + "."};
                case "heir" -> new String[]{"As an heir adopted into the family" + (adopter == null ? "" : " by " + adopter), filed + "on adoption, as heir" + entered.apply(" as its heir") + (adopter == null ? "" : "; the adoption by " + adopter + " was an heir adoption") + back + "."};
                case "succession" -> new String[]{"On succeeding as head of the family, or as a hereditary name", filed + "on succeeding as head" + entered.apply(" as its head") + back + "."};
                case "legal" -> new String[]{"By a legal or court change, or under a will", filed + "by a legal change" + back + "."};
                case "taken-back" -> new String[]{"Taken back after a divorce or an ended adoption", filed + "on taking it back" + back + "."};
                case "aka" -> new String[]{"It was only another name " + who + " was known by, not a change", "saves as your answer: " + who + " was also known as " + name + back + "."};
                default -> new String[]{k, filed + back + "."};
            };
        }

        Done howAnswer(Ctx c, Graph.Node p, FamilyNameHistory.Name n, String kind, String fam, String spouse, String spouseClaim, String adopter, String adoption) throws IOException {
            // filed under the entry the question is about, as the name's own claims write it, so the answer follows it through joins
            String who = p.label(), under = filedUnder(p.id(), n.claims(), n.written());
            List<String> filed = new ArrayList<>();
            String nameKind = kind.equals("heir") || kind.equals("yojo") ? "adoptive" : kind;
            String event = switch (kind) { case "mukoyoshi", "adoptive", "heir", "yojo" -> adoption; case "marriage", "nyufu" -> spouseClaim; default -> null; };
            // the name at birth runs from the birth: a year the library worked out for the name from a marriage or an entry is not its year
            String fromWritten = kind.equals("birth") ? "" : n.from() != null && !n.fromEvent() ? n.from().written() : event != null ? "event" : "";
            filed.add(nameWord(c, under, n, nameKind, fromWritten, event == null ? "" : event));
            // whoever took the husband's or wife's family name at the marriage entered that family, a man and a woman alike
            boolean enters = !fam.isBlank() && switch (kind) { case "mukoyoshi", "nyufu", "adoptive", "heir", "yojo", "succession" -> true; case "marriage" -> spouse != null; default -> false; };
            if (enters) {
                String famLabel = familyLabel(fam, spouse, adopter);
                if (famLabel == null) famLabel = FamilyHouses.family(c.store(), g, fam, "", who);
                String how = switch (kind) { case "heir", "adoptive", "yojo" -> "adoption"; default -> kind; };
                String role = kind.equals("heir") ? "heir" : kind.equals("succession") ? "head" : "";
                String memberFrom = fromWritten;
                Map<String, String> d = FamilyHouses.detail(how, "", memberFrom, "", role, event == null ? "" : event, "");
                filed.add(word(c, new Finding.Triple(under, FamilyHouses.MEMBER, famLabel), FamilyAccount.sentence(under, FamilyHouses.MEMBER, famLabel, d) + bracket(memberFrom, event) + ".", d));
            }
            if (adopter != null && adoption != null && (kind.equals("mukoyoshi") || kind.equals("heir"))) {
                String ak = kind.equals("heir") ? "heir" : "mukoyoshi";
                Finding a = byId.get(adoption);
                if (a != null && !FamilyNameHistory.adoptionKind(a).equals(ak)) {
                    Map<String, String> d = new LinkedHashMap<>(); d.put("kind", ak);
                    FamilyDate ad = FamilyChecks.claimDate(a);
                    if (ad != null) d.put("from", ad.written());
                    filed.add(word(c, new Finding.Triple(under, "adopted-by", label(adopter)), FamilyAccount.sentence(under, "adopted-by", label(adopter), d) + (ad == null ? "" : " (" + ad.shown() + ")") + ".", d));
                }
            }
            if (kind.equals("marriage") && spouse != null) {
                // the husband's or wife's parents are the person's parents-in-law, never adoptive parents
                for (Graph.Edge e : from.getOrDefault(spouse, List.of())) if (e.predicate().equals("child-of") && person(e.to()) && relation(e.to(), "parent-in-law-of", p.id()) == null)
                    filed.add(word(c, new Finding.Triple(label(e.to()), "parent-in-law-of", under), FamilyAccount.sentence(label(e.to()), "parent-in-law-of", under) + ".", Map.of()));
                for (Graph.Edge e : to.getOrDefault(spouse, List.of())) if (e.predicate().equals("parent-of") && person(e.from()) && relation(e.from(), "parent-in-law-of", p.id()) == null)
                    filed.add(word(c, new Finding.Triple(label(e.from()), "parent-in-law-of", under), FamilyAccount.sentence(label(e.from()), "parent-in-law-of", under) + ".", Map.of()));
                List<String> disputed = new ArrayList<>(), was = new ArrayList<>();
                for (Graph.Edge e : adoptionsInto(p.id(), fam, spouse)) {
                    Finding a = byId.get(e.findingId());
                    if (a == null || disputed.contains(a.id())) continue;
                    was.add(a.state().name());
                    new Council(c.store()).dispute(a.id(), "the family said " + who + " took " + label(spouse) + "'s family name at the marriage, without an adoption (question " + c.code() + ")");
                    disputed.add(a.id());
                }
                if (!disputed.isEmpty()) return new Done(filedSaid(c, filed), who + " got the name " + text(n) + ": " + lower(c.says()),
                        "claims=" + String.join(",", filed) + "; disputed=" + String.join(",", disputed) + "; was=" + String.join(",", was));
            }
            return new Done(filedSaid(c, filed), who + " got the name " + text(n) + ": " + lower(c.says()), "claims=" + String.join(",", filed));
        }

        /** " (1932)" for a claim's first line, from a date as written or the date of the event it follows; "" when neither gives one. */
        String bracket(String from, String event) {
            if (from == null || from.isBlank()) return "";
            FamilyDate d = from.equalsIgnoreCase("event") ? (event == null || idx.finding(event) == null ? null : FamilyChecks.claimDate(idx.finding(event))) : FamilyDate.parse(from);
            return d == null ? "" : " (" + d.shown() + ")";
        }

        String nameWord(Ctx c, String person, FamilyNameHistory.Name n, String kind, String from, String event) throws IOException {
            // a name the library read from the label is filed as the name, without the bracket that tells two entries of one name apart
            String name = text(n), id = g.nodeIdOf(person), family = n.family(), given = n.given();
            boolean bracket = !(n.implicit() ? bareName(n.written()) : n.written()).equals(n.written());
            if (family.isBlank() || bracket) {
                String[] p = idx.parts(id, name);
                if (!p[0].isBlank()) { family = p[0]; given = p[1]; }
                else if (bracket) { family = ""; given = ""; }   // parts split from the label with its bracket are no parts of the name
            }
            List<FamilyNameHistory.Form> forms = new ArrayList<>();
            forms.add(new FamilyNameHistory.Form(name, FamilyForms.lang(name)));
            for (FamilyNameHistory.Form f : n.forms()) {
                String t = n.implicit() ? bareName(f.text()) : f.text();
                if (forms.stream().noneMatch(x -> x.text().equals(t))) forms.add(new FamilyNameHistory.Form(t, f.lang()));
            }
            // the answer's words are in the claim's body; "said" holds only a source's own words for how the name came
            Map<String, String> d = FamilyNameHistory.detail(family, given, kind, from, "", event, forms, "", FamilyForms.lang(name));
            String shown = bracket(from, event);
            String sentence = FamilyNameHistory.claimSentence(person, name, kind, shown.isEmpty() ? "" : shown.substring(2, shown.length() - 1));
            FamilyNameHistory.markValue(c.store(), List.of(FamilyNameHistory.VALUE + name));
            return word(c, new Finding.Triple(person, FamilyNameHistory.PREDICATE, FamilyNameHistory.VALUE + name), sentence, d);
        }

        /** {@code member}: a claim makes the person a member of the family; {@code entered}: that, or a name of theirs carries the adopter's family part. */
        void halves(List<Posed> out, Graph.Node p, String parent, List<Finding> adoption, boolean married, boolean member, boolean entered) {
            String who = p.label(), pl = label(parent);
            Finding a = adoption.get(0);
            String fam = "";
            for (FamilyNameHistory.Name x : idx.names(parent)) { fam = familyOf(parent, x); if (!fam.isBlank()) break; }
            String famLabel = fam.isBlank() ? null : familyLabel(fam, parent);
            String famShown = famLabel != null ? noArticle(famLabel) : fam.isBlank() ? "" : fam + " family";
            String text = said(a) + ": " + who + " was adopted by " + pl + " as 婿養子 (adopted and married). "
                    + "The library has no fact that says " + who + (!married && !entered ? " married into the family, or that he entered " + (famShown.isBlank() ? "it" : "the " + famShown)
                            : !married ? " married into the family" : " entered " + (famShown.isBlank() ? "the family" : "the " + famShown)) + ". "
                    + "How did " + who + " come into " + (famShown.isBlank() ? pl + "'s family" : "the " + famShown) + "?";
            List<Option> opts = new ArrayList<>();
            Map<String, Act> acts = new LinkedHashMap<>();
            String f = fam;
            opts.add(new Option("mukoyoshi", "As 婿養子: adopted by " + pl + " and married to " + pl + "'s daughter", (member || f.isBlank() ? "saves as your answer: " + who + " was adopted by " + pl + " as 婿養子" : "saves as your answer: " + who + " entered the " + famShown + " as 婿養子")
                    + (married ? "" : ". The marriage is filed when a source names his wife") + ". To take it back: " + REOPEN_HERE + "."));
            acts.put("mukoyoshi", x -> halvesAnswer(x, p, parent, a, "mukoyoshi", f, member));
            opts.add(new Option("adoptive", "An ordinary adoption, with no marriage", "saves as your answer: " + who + " was adopted by " + pl + " in an ordinary adoption" + (f.isBlank() || member ? "" : "; " + who + " entered the " + famShown + " by adoption") + ". To take it back: " + REOPEN_HERE + "."));
            acts.put("adoptive", x -> halvesAnswer(x, p, parent, a, "ordinary", f, member));
            opts.add(new Option("heir", "An adoption as heir, with no marriage", "saves as your answer: " + who + " was adopted by " + pl + " as heir" + (f.isBlank() || member ? "" : "; " + who + " entered the " + famShown + " as its heir") + ". To take it back: " + REOPEN_HERE + "."));
            acts.put("heir", x -> halvesAnswer(x, p, parent, a, "heir", f, member));
            // the question is the sources' adoption: the family's own answer to it, filed as their word, leaves it the question it was
            List<String> keyed = adoption.stream().filter(x -> x.sources().stream().noneMatch(s -> s.locator() != null && s.locator().startsWith(SOURCE))).map(Finding::id).toList();
            add(out, "name-change-how", List.of(p.id(), parent), keyed.isEmpty() ? adoption.stream().map(Finding::id).toList() : keyed, text, opts, acts);
        }

        Done halvesAnswer(Ctx c, Graph.Node p, String parent, Finding a, String kind, String fam, boolean member) throws IOException {
            // filed under the entry as the adoption the question rests on writes it, so the answer follows that claim through joins
            String who = p.label(), under = filedUnder(p.id(), List.of(a.id()), null), pl = label(parent);
            List<String> filed = new ArrayList<>();
            FamilyDate ad = FamilyChecks.claimDate(a);
            Map<String, String> d = new LinkedHashMap<>(); d.put("kind", kind);
            if (ad != null) d.put("from", ad.written());
            // the adoption is filed again when the family gives it another kind than the source did, and as the family's word where the answer
            // files no family he entered: a plain adoption into the family accepted beside the source's 婿養子 leaves the kind open until then
            boolean enters = !member && !fam.isBlank();
            if (!FamilyNameHistory.adoptionKind(a).equals(kind) || !enters)
                filed.add(word(c, new Finding.Triple(under, "adopted-by", pl), FamilyAccount.sentence(under, "adopted-by", pl, d) + (ad == null ? "" : " (" + ad.shown() + ")") + ".", d));
            if (enters) {
                String famLabel = familyLabel(fam, parent);
                if (famLabel == null) famLabel = FamilyHouses.family(c.store(), g, fam, "", pl);
                Map<String, String> m = FamilyHouses.detail(kind.equals("mukoyoshi") ? "mukoyoshi" : "adoption", "", ad == null ? "" : ad.written(), "", kind.equals("heir") ? "heir" : "", a.id(), "");
                filed.add(word(c, new Finding.Triple(under, FamilyHouses.MEMBER, famLabel), FamilyAccount.sentence(under, FamilyHouses.MEMBER, famLabel, m) + (ad == null ? "" : " (" + ad.shown() + ")") + ".", m));
            }
            return new Done(filedSaid(c, filed), who + " and " + pl + ": " + c.says(), "claims=" + String.join(",", filed));
        }

        // ── when a name changed ──

        void nameChangeWhen(List<Posed> out) {
            for (Graph.Node p : people) {
                if (!in(p.id()) || FamilyQuestions.placeholder(p.label())) continue;
                List<FamilyNameHistory.Name> names = idx.names(p.id());
                List<FamilyNameHistory.Name> replacing = names.stream().filter(FamilyNameHistory.Name::replaces).filter(x -> !idx.formOnly(p.id(), x)).toList();
                if (replacing.size() < 2) continue;
                for (FamilyNameHistory.Name n : replacing) {
                    // how it changed is asked first; a name taken at a marriage or an adoption is dated by it, when nothing says otherwise
                    if (!n.explained() || n.settled() || n.workedOut() || n.implicit()) continue;
                    if (settledFrom(p.id(), n) != null || withItsUndatedMarriage(p.id(), n)) continue;
                    when(out, p, n, replacing);
                }
            }
        }

        void when(List<Posed> out, Graph.Node p, FamilyNameHistory.Name n, List<FamilyNameHistory.Name> replacing) {
            String id = p.id(), who = p.label();
            FamilyNameHistory.Name before = replacing.stream().filter(x -> x != n && (x.kind().equals("birth") || x.from() != null)).findFirst().orElse(null);
            int[] mine = idx.useWindow(id, n), was = before == null ? null : idx.useWindow(id, before);
            StringBuilder t = new StringBuilder(heading(id) + " had the name " + text(n) + " " + FamilyNameHistory.phrase(n.kind()) + ", and no source says when.");
            if (was != null) t.append(" Records call ").append(who).append(" ").append(text(before)).append(was[0] == was[1] ? " in " + was[0] : " from " + was[0] + " to " + was[1]).append(".");
            if (mine != null) t.append(" Records call ").append(who).append(" ").append(text(n)).append(mine[0] == mine[1] ? " in " + mine[0] : " from " + mine[0] + " to " + mine[1]).append(".");
            t.append(" In which year did ").append(who).append(" take the name ").append(text(n)).append("?");
            List<Option> opts = new ArrayList<>();
            Map<String, Act> acts = new LinkedHashMap<>();
            opts.add(new Option("year", "In the year you type", "saves as your answer: " + who + " was named " + text(n) + " " + FamilyNameHistory.phrase(n.kind()) + " in that year. To take it back: " + REOPEN_HERE + "."));
            acts.put("year", x -> {
                FamilyDate d = FamilyDate.parse(x.year());
                String f = nameWord(x, filedUnder(id, n.claims(), n.written()), n, "unknown", d.written(), n.event());
                return new Done(filedSaid(x, List.of(f)), who + ": " + text(n) + " from " + d.phrase(), "claims=" + f);
            });
            if (was != null && mine != null && was[1] < mine[0]) {
                int a = was[1], b = mine[0];
                opts.add(new Option("between", "Between " + a + " and " + b + ", when the records change", "saves as your answer: " + who + " was named " + text(n) + " between " + a + " and " + b + ". To take it back: " + REOPEN_HERE + "."));
                acts.put("between", x -> {
                    String f = nameWord(x, filedUnder(id, n.claims(), n.written()), n, "unknown", "between " + a + " and " + b, n.event());
                    return new Done(filedSaid(x, List.of(f)), who + ": " + text(n) + " between " + a + " and " + b, "claims=" + f);
                });
            }
            List<String> claims = new ArrayList<>(n.claims()); claims.add("name: " + n.written());
            add(out, "name-change-when", List.of(id), claims, t.toString(), opts, acts);
        }

        // ── a birth or an adoptive parent ──

        void birthOrAdoptive(List<Posed> out) {
            Map<String, List<String[]>> parents = new LinkedHashMap<>();   // child → [parent, claim]
            for (Graph.Edge e : g.edges()) {
                if (FamilyKin.gone(e)) continue;
                if (e.predicate().equals("child-of")) parents.computeIfAbsent(e.from(), k -> new ArrayList<>()).add(new String[]{e.to(), e.findingId()});
                else if (e.predicate().equals("parent-of")) parents.computeIfAbsent(e.to(), k -> new ArrayList<>()).add(new String[]{e.from(), e.findingId()});
            }
            for (Map.Entry<String, List<String[]>> e : parents.entrySet()) {
                String child = e.getKey();
                if (!(in(child) || e.getValue().stream().anyMatch(l -> in(l[0]))) || !person(child) || !own.contains(child)) continue;
                List<String[]> links = e.getValue().stream().filter(l -> person(l[0])).toList();
                Map<String, String> why = new LinkedHashMap<>();   // parent → why the question
                for (String sex : List.of("male", "female")) {
                    List<String> same = links.stream().map(l -> l[0]).distinct().filter(x -> sexOf(x).equals(sex)).toList();
                    if (same.size() < 2) continue;
                    // two parents the link pass holds as possibly one person (two names one character apart, say) are asked about once, as
                    // one person or two, with the children they share; which of them is the birth parent is not asked of any of their children
                    List<String> left = new ArrayList<>(same);
                    for (int i = 0; i < same.size(); i++) for (int k = i + 1; k < same.size(); k++) {
                        FamilyLinks.Link l = possibleLink(same.get(i), same.get(k));
                        if (l == null) continue;
                        possiblePair(out, same.get(i), same.get(k), sex, l, parents);
                        left.remove(same.get(i));
                        left.remove(same.get(k));
                    }
                    if (left.size() < 2) continue;
                    for (String x : left) why.putIfAbsent(x, heading(child) + " has two " + (sex.equals("male") ? "fathers" : "mothers") + " written as birth parents: " + String.join(" and ", left.stream().map(this::heading).toList()) + ". ");
                }
                FamilyDate born = idx.born(child);
                FamilyNameHistory.Name birth = idx.birth(child);
                String childFam = birth == null ? "" : familyOf(child, birth);
                if (born != null && !childFam.isBlank()) {
                    Map<String, String> famThen = new LinkedHashMap<>();
                    for (String[] l : links) {
                        FamilyNameHistory.Name then = idx.at(l[0], born.year());
                        if (then != null) famThen.put(l[0], familyOf(l[0], then));
                    }
                    boolean explained = famThen.values().stream().anyMatch(f -> !f.isBlank() && FamilyForms.sameForm(f, childFam));
                    if (!explained) for (Map.Entry<String, String> f : famThen.entrySet()) if (!f.getValue().isBlank())
                        why.putIfAbsent(f.getKey(), heading(child) + " was born " + birth.written() + " " + born.in() + ", and " + label(f.getKey()) + " carried the family name " + f.getValue() + " then. ");
                }
                for (Map.Entry<String, String> w : why.entrySet()) {
                    String parent = w.getKey();
                    List<String> others = links.stream().map(l -> l[0]).filter(x -> !x.equals(parent)).distinct().toList();
                    for (String[] l : links) if (l[0].equals(parent)) { parentKind(out, child, parent, l[1], w.getValue(), others); break; }
                }
            }
        }

        private List<FamilyLinks.Link> possibleLinks;

        /** The links the link pass holds as possible between two entries ({@link FamilyLinks}): shown as "may be the same person as", joining nothing. */
        List<FamilyLinks.Link> possibleLinks() {
            if (possibleLinks != null) return possibleLinks;
            List<FamilyLinks.Link> out = new ArrayList<>();
            try {
                FamilyLinks.Result r = store == null ? null : FamilyLinks.current(store);
                if (r != null) for (FamilyLinks.Link l : r.links()) if (!l.joins() && !l.mention()) out.add(l);
            } catch (IOException | RuntimeException e) {
                // no links worked out: no pair is possible by them
            }
            possibleLinks = out;
            return out;
        }

        /** The possible link between two entries, either way round; null when the link pass holds none. */
        FamilyLinks.Link possibleLink(String a, String b) {
            String want = Graph.pair(a, b);
            for (FamilyLinks.Link l : possibleLinks()) {
                String x = g.node(l.node()) != null ? l.node() : g.nodeIdOf(l.written()), y = g.node(l.person()) != null ? l.person() : g.nodeIdOf(l.personLabel());
                if (Graph.pair(x, y).equals(want)) return l;
            }
            return null;
        }

        /**
         * One question for two parents of one sex the link pass holds as possibly one person: are they one? It says what the pass found, the
         * children the two share, and what else agrees. "One person" joins them, as the owner's word; "two people" writes them down as two,
         * and the pass then holds them apart. Asked once, for the first child that raises it.
         */
        void possiblePair(List<Posed> out, String a, String b, String sex, FamilyLinks.Link l, Map<String, List<String[]>> parents) {
            if (!pairsDone.add(Graph.pair(a, b))) return;
            FamilySame.Comparison c = compare(a, b);
            if (c.twoPeople()) return;
            List<String> shared = new ArrayList<>(), claims = new ArrayList<>(l.evidence());
            for (Map.Entry<String, List<String[]>> e : parents.entrySet()) {
                List<String> ps = e.getValue().stream().map(x -> x[0]).toList();
                if (!ps.contains(a) || !ps.contains(b) || !person(e.getKey())) continue;
                shared.add(e.getKey());
                for (String[] x : e.getValue()) if ((x[0].equals(a) || x[0].equals(b)) && !claims.contains(x[1])) claims.add(x[1]);
            }
            claims.addAll(c.findings());
            String word = sex.equals("male") ? "father" : "mother";
            int n = shared.size();
            StringBuilder t = new StringBuilder(heading(a) + " and " + heading(b) + " are written as the " + word + " of the same " + (n == 1 ? "child" : n + " children"));
            if (n > 0 && n <= 3) t.append(" (").append(String.join(", ", shared.stream().map(this::heading).toList())).append(")");
            t.append(". ");
            // what the link pass found, without its verdict, which is the family's to give here
            String why = l.why().replaceFirst("\\s*Not joined:\\s*", " ")
                    .replaceAll("[;,]?\\s*(?:and\\s+)?(?:they\\s+stay\\s+|two\\s+such\\s+names\\s+are\\s+)?two\\s+people\\s+until\\s+the\\s+family\\s+says\\s+otherwise\\.?", ".").replaceAll("\\.\\s*\\.", ".").strip();
            if (!why.isEmpty()) t.append(sentence(why)).append(' ');
            t.append(c.said());
            pair(out, a, b, claims, t.toString(), false, folds(a, b), shared);
        }

        void parentKind(List<Posed> out, String child, String parent, String claim, String why, List<String> others) {
            if (!(in(child) || in(parent))) return;
            Finding f = byId.get(claim);
            String cl = label(child), pl = label(parent), sex = sexOf(parent);
            String word = sex.equals("male") ? "father" : sex.equals("female") ? "mother" : "parent";
            String text = (f == null ? cl + " is written as a child of " + pl + "." : said(f) + ": " + cl + " is written as a child of " + pl + ".") + " " + why
                    + "Is " + pl + " the birth " + word + " of " + cl + ", or an adoptive or step-" + word + ", a foster " + word + ", or a " + word + "-in-law?";
            List<Option> opts = new ArrayList<>();
            Map<String, Act> acts = new LinkedHashMap<>();
            String code = f == null ? claim : f.id().replaceFirst("^(F-\\d+).*", "$1");
            // the answer is filed under the two as the claim writes them, so it follows them through later joins and take-backs as the claim does
            String childAs = cl, parentAs = pl;
            if (f != null && f.triple() != null) {
                String a = f.triple().subject(), b = f.triple().object();
                // the written names only where each leads to its person by itself; a mention linked by its claim is filed under the label
                if (g.nodeIdOf(a).equals(child) && g.nodeIdOf(b).equals(parent)) { childAs = a; parentAs = b; }
                else if (g.nodeIdOf(a).equals(parent) && g.nodeIdOf(b).equals(child)) { childAs = b; parentAs = a; }
            }
            String ca = childAs, pa = parentAs;
            opts.add(new Option("birth", "The birth " + word, "keeps " + code + " as it is, and does not ask again."));
            acts.put("birth", x -> new Done("Kept: " + pl + " is the birth " + word + " of " + cl + ".", pl + " is the birth " + word + " of " + cl, ""));
            String[][] kinds = {{"adoptive", "An adoptive " + word, "adopted-by"}, {"step", "A step-" + word, "step-parent-of"}, {"foster", "A foster " + word, "foster-child-of"}, {"in-law", "A " + word + "-in-law", "parent-in-law-of"}};
            for (String[] k : kinds) {
                boolean parentFirst = k[2].equals("step-parent-of") || k[2].equals("parent-in-law-of");
                String s = parentFirst ? pl : cl, o = parentFirst ? cl : pl;
                opts.add(new Option(k[0], k[1], "marks " + code + " as disputed, with your answer as the reason, and saves as your answer: " + FamilyAccount.sentence(s, k[2], o) + ". Both stay on record. To take the answer back: " + REOPEN_HERE + "."));
                acts.put(k[0], x -> {
                    Finding before = byId.get(claim);
                    String was = before == null ? "" : before.state().name();
                    new Council(x.store()).dispute(claim, "the family said " + pl + " is " + cl + "'s " + k[1].toLowerCase(Locale.ROOT).replaceFirst("^an? ", "") + ", not a birth " + word + " (question " + x.code() + ")");
                    String fs = parentFirst ? pa : ca, fo = parentFirst ? ca : pa;
                    String id = word(x, new Finding.Triple(fs, k[2], fo), FamilyAccount.sentence(fs, k[2], fo) + ".", Map.of());
                    return new Done(code + " is marked as disputed, and " + id.replaceFirst("^(F-\\d+).*", "$1") + " says " + FamilyAccount.sentence(s, k[2], o) + ". To take the answer back: researchzosho genealogy who --reopen " + x.code(),
                            pl + " is " + cl + "'s " + k[1].toLowerCase(Locale.ROOT).replaceFirst("^an? ", ""), "claims=" + id + "; disputed=" + claim + "; was=" + was);
                });
            }
            add(out, "birth-or-adoptive", List.of(child, parent), List.of(claim), text, opts, acts, others);
        }

        // ── which family ──

        void whichFamily(List<Posed> out) {
            List<String> fams = FamilyHouses.all(g);
            if (fams.size() < 2) return;
            for (String f : fams) {
                if (!FamilyHouses.seat(g, f).isBlank()) continue;
                String name = FamilyHouses.nameOf(FamilyHouses.labelOf(g, f));
                List<String> others = new ArrayList<>();
                // a family the sources already tell apart from this one (its branch, or the family it is a branch of) is no candidate
                for (String o : FamilyHouses.named(g, name)) if (!o.equals(f) && !apart.contains(Graph.pair(f, o)) && !FamilyHouses.toldApart(g, f, o)) others.add(o);
                // families that no claim of genealogy's own work names, such as two the owner marked by hand, are no family's to answer
                if (others.isEmpty() || !own.contains(f) && others.stream().noneMatch(own::contains)) continue;
                List<FamilyHouses.Membership> members = FamilyHouses.members(g, f);
                if (scope != null && !in(f) && members.stream().noneMatch(m -> in(m.person()))) continue;
                String fl = FamilyHouses.labelOf(g, f);
                StringBuilder t = new StringBuilder("Your library has " + (others.size() + 1) + " families named " + name + ": “" + fl + "”, " + String.join(", ", others.stream().map(o -> "“" + FamilyHouses.labelOf(g, o) + "”" + (FamilyHouses.seat(g, o).isBlank() ? "" : " of " + FamilyHouses.seat(g, o))).toList())
                        + ". “" + fl + "” has no seat, so the library cannot tell whether it is one of the others.");
                if (!members.isEmpty()) t.append(" Its members: ").append(String.join(", ", members.stream().map(m -> label(m.person())).distinct().limit(6).toList())).append(".");
                t.append(" Is it one of these families, or a family of its own?");
                List<Option> opts = new ArrayList<>();
                Map<String, Act> acts = new LinkedHashMap<>();
                int i = 0;
                for (String o : others) {
                    String key = "f" + (++i), ol = FamilyHouses.labelOf(g, o);
                    opts.add(new Option(key, "It is “" + ol + "”", "joins “" + fl + "” into “" + ol + "”: its members are then members of " + ol + ". To take it back: " + REOPEN_HERE));
                    acts.put(key, x -> merged(x, fl, ol, f));
                }
                opts.add(new Option("own", "A family of its own", "writes down that “" + fl + "” is another family than " + String.join(" and ", others.stream().map(o -> "“" + FamilyHouses.labelOf(g, o) + "”").toList()) + ", and this is not asked again."));
                acts.put("own", x -> {
                    List<String> pairs = new ArrayList<>();
                    for (String o : others) { Graph.different(x.store(), fl, FamilyHouses.labelOf(g, o), "person", x.reason()); pairs.add(f + "|" + o); }
                    return new Done("“" + fl + "” is written down as a family of its own. To take it back: researchzosho genealogy who --reopen " + x.code(), "“" + fl + "” is a family of its own", "apart=" + String.join(",", pairs));
                });
                List<String> ids = new ArrayList<>(List.of(f)); ids.addAll(others);
                add(out, "which-family", ids, List.of(), t.toString(), opts, acts);
            }
        }

        // ── a record under a name not carried then ──

        void nameAtDate(List<Posed> out) {
            for (Graph.Node p : people) {
                if (!in(p.id()) || FamilyQuestions.placeholder(p.label())) continue;
                List<FamilyNameHistory.Name> names = idx.names(p.id());
                if (names.stream().filter(FamilyNameHistory.Name::replaces).count() < 2) continue;
                Set<String> seen = new HashSet<>();
                for (Graph.Edge e : from.getOrDefault(p.id(), List.of())) {
                    Finding f = byId.get(e.findingId());
                    if (f == null || f.triple() == null || !seen.add(f.id()) || FamilyNameHistory.isNameClaim(f) || Evidence.of(f) == Evidence.clue) continue;
                    if (f.notes().stream().anyMatch(x -> x.kind().equals(NOTE))) continue;
                    FamilyDate d = FamilyChecks.claimDate(f);
                    if (d == null) continue;
                    // a page or a book is written after the event, under the name its writer knows: it is no record of that year's name
                    if (f.sources().stream().allMatch(src -> published(src.locator()))) continue;
                    String as = f.triple().subject();
                    FamilyNameHistory.Name n = names.stream().filter(x -> x.isForm(as)).findFirst().orElse(null);
                    FamilyNameHistory.Name then = idx.at(p.id(), d.year());
                    // a record under the given name alone (健二, Mary) says no family name, so it says nothing of which name was carried then
                    if (n == null || then == null || then == n || then.isForm(as) || !n.replaces() || idx.partOnly(p.id(), n, as)) continue;
                    if (n.from() != null && n.from().year() <= d.year() && (n.to() == null || d.year() < n.to().year())) continue;
                    dated(out, p, f, d, n, then, names);
                }
            }
        }

        /** A published work, written after the events it tells: a web page, or a book or a long document by its file type. */
        static boolean published(String locator) {
            String l = locator == null ? "" : locator.toLowerCase(Locale.ROOT);
            return l.startsWith("http://") || l.startsWith("https://") || l.endsWith(".epub") || l.endsWith(".pdf") || l.endsWith(".mobi");
        }

        /** " from 1905 to 1932", " from 1932", " to 1932", or "" when the name's own years are not known. */
        String period(FamilyNameHistory.Name n) {
            return (n.from() == null ? "" : " from " + n.from().phrase()) + (n.to() == null ? "" : " to " + n.to().phrase());
        }

        /**
         * A record of year {@code d} written under the name {@code n}, when the names say the person carried {@code then}. Two ways round: the
         * record is under a LATER name than the one carried at its date (written later, or the change came earlier), or it still uses an
         * EARLIER name after the change (records often do: a death under a maiden name), and then the change may have come later. A year
         * given goes on the later of the two names, never on the name of before, whose start stays where it is.
         */
        void dated(List<Posed> out, Graph.Node p, Finding f, FamilyDate d, FamilyNameHistory.Name n, FamilyNameHistory.Name then, List<FamilyNameHistory.Name> names) {
            String who = p.label(), code = f.id().replaceFirst("^(F-\\d+).*", "$1");
            boolean stillEarlier = names.indexOf(n) >= 0 && names.indexOf(n) < names.indexOf(then);
            String text = said(f) + " is a record " + d.in() + " that writes " + heading(p.id()) + " as “" + f.triple().subject() + "”. By the names in your library, " + who + " carried the name " + text(then)
                    + period(then) + ", and " + text(n) + period(n) + ". The record is kept as it is. Which is right?";
            List<Option> opts = new ArrayList<>();
            Map<String, Act> acts = new LinkedHashMap<>();
            if (stillEarlier) {
                opts.add(new Option("still-used", "The record still used the earlier name " + text(n) + ", as records often do", "adds a note to " + code + " that the family says so, and does not ask again. researchzosho genealogy who --answered lists it, with the command that takes it back."));
                acts.put("still-used", x -> note(x, f, code, "the family says the record still used the earlier name " + text(n)));
                opts.add(new Option("year", "The name changed later than this record, in the year you type", "saves as your answer: " + who + " was named " + text(then) + " from that year, which is after " + d.year() + ". To take it back: " + REOPEN_HERE + "."));
                acts.put("year", x -> {
                    FamilyDate yr = FamilyDate.parse(x.year());
                    if (yr.year() <= d.year()) throw new IllegalArgumentException("The record is of " + d.year() + " and writes the earlier name, so a change later than the record is in a year after " + d.year() + ". Give that year, or choose another answer.");
                    String id = nameWord(x, filedUnder(p.id(), with(then.claims(), f.id()), then.written()), then, "unknown", yr.written(), then.event());
                    return new Done(filedSaid(x, List.of(id)), who + ": " + text(then) + " from " + yr.phrase(), "claims=" + id);
                });
            } else {
                opts.add(new Option("written-later", "The record was written later, under the name carried then", "adds a note to " + code + " that the family says so, and does not ask again. researchzosho genealogy who --answered lists it, with the command that takes it back."));
                acts.put("written-later", x -> note(x, f, code, "the family says the record was written later, under the name " + text(n)));
                opts.add(new Option("year", "The name changed earlier, in the year you type", "saves as your answer: " + who + " was named " + text(n) + " from that year, which is " + d.year() + " or before. To take it back: " + REOPEN_HERE + "."));
                acts.put("year", x -> {
                    FamilyDate yr = FamilyDate.parse(x.year());
                    if (yr.year() > d.year()) throw new IllegalArgumentException("The record is of " + d.year() + " and writes " + text(n) + ", so a change earlier than the record is in " + d.year() + " or before. Give that year, or choose another answer.");
                    String id = nameWord(x, filedUnder(p.id(), with(n.claims(), f.id()), n.written()), n, "unknown", yr.written(), n.event());
                    return new Done(filedSaid(x, List.of(id)), who + ": " + text(n) + " from " + yr.phrase(), "claims=" + id);
                });
            }
            String split = "researchzosho genealogy split \"" + who + "\" --as \"" + f.triple().subject() + " (another person)\" --claims " + code;
            opts.add(new Option("another", "It is another person", "gives you the command that moves " + code + " to a person of its own: " + split));
            acts.put("another", x -> new Done("To move " + code + " to a person of its own, give this command. Change the name after --as if you know a better one: " + split, code + " is about another person", "command=" + split));
            add(out, "name-at-date", List.of(p.id()), List.of(f.id()), text, opts, acts);
        }

        /** The family's word as a note on a record, which --reopen takes off again. */
        Done note(Ctx x, Finding f, String code, String says) throws IOException {
            x.store().write(x.store().finding(f.id()) == null ? f.withNote(noteOf(x, says)) : x.store().finding(f.id()).withNote(noteOf(x, says)));
            String s = says.replaceFirst("^the family says the record ", "");
            return new Done("A note on " + code + " says the record " + s + ". To take it back: researchzosho genealogy who --reopen " + x.code(), code + " " + s, "note=" + f.id());
        }

        private Finding.Note noteOf(Ctx x, String says) { return new Finding.Note(NOTE, "person", LocalDate.now().toString(), says + " (question " + x.code() + ", as told by " + x.teller() + ")"); }

        // ── where your notes and a source disagree ──

        /** One relation between two people as a claim gives it: "parent" (a is b's parent), "sibling" or "spouse". */
        record Rel(String kind, String a, String b, String claim) {
            boolean between(String x, String y) { return a.equals(x) && b.equals(y) || a.equals(y) && b.equals(x); }
        }

        // the words of a claim that make a parent no birth parent: an adoption, a step-parent, a foster parent, a parent-in-law
        private static final Pattern NOT_BORN_TO = Pattern.compile("(?i)養子|養女|婿養子|養父|養母|継父|継母|義父|義母|里親|\\badopt|\\bstep[- ]?(?:father|mother|parent|son|daughter|child)|\\bfoster|\\bin[- ]law\\b");

        /**
         * Where the owner's own word and a source disagree about how two people are related: the owner's word is a note beside a link, what
         * the owner told the library, or the owner's own notes ({@link FamilyClose.Close#ownerSaid}). They disagree when they give two
         * relations between the same two people (a father and a brother), or when the owner's word makes somebody a person's parent and a
         * source names that person's parents as others. Only the owner can correct the owner's notes, so this is asked whoever it concerns.
         */
        void notesAndSources(List<Posed> out) {
            FamilyClose.Close c = close();
            if (!c.known()) return;
            List<Rel> mine = new ArrayList<>(), theirs = new ArrayList<>();
            for (Graph.Edge e : g.edges()) {
                if (FamilyKin.gone(e) && !(e.disputed() && countedAgain.contains(e.findingId()))) continue;
                Finding f = byId.get(e.findingId());
                if (f == null || answered(f)) continue;
                boolean owners = c.ownerSaid(f);
                Rel r = switch (e.predicate()) {
                    case "parent-of" -> new Rel("parent", e.from(), e.to(), f.id());
                    case "child-of" -> new Rel("parent", e.to(), e.from(), f.id());
                    case "sibling-of" -> new Rel("sibling", e.from(), e.to(), f.id());
                    case "married-to" -> new Rel("spouse", e.from(), e.to(), f.id());
                    case "relative-of" -> {
                        String[] d = owners ? FamilyClose.direct(g, c, e.from(), e.to(), FamilyChecks.quoteOf(f)) : null;
                        yield d == null ? null : new Rel(d[0], d[1], d[2], f.id());
                    }
                    default -> null;
                };
                if (r == null || !isPerson(r.a()) || !isPerson(r.b())) continue;
                (owners ? mine : theirs).add(r);
            }
            Set<String> done = new HashSet<>();
            for (Rel m : mine) {
                // two relations between the same two people
                Map<String, List<Rel>> against = new LinkedHashMap<>();
                for (Rel t : theirs) if (t.between(m.a(), m.b()) && !compatible(m, t)) against.computeIfAbsent(locatorOf(byId.get(t.claim())), k -> new ArrayList<>()).add(t);
                for (Map.Entry<String, List<Rel>> s : against.entrySet()) if (done.add(m.claim() + "\u0000" + s.getKey())) notesAgainst(out, m, s.getValue(), s.getKey(), List.of());
                if (!m.kind().equals("parent")) continue;
                // the owner's word makes somebody a parent, and a source names the child's parents as others
                String child = m.b(), parent = m.a();
                Map<String, List<Rel>> named = new LinkedHashMap<>();
                for (Rel t : theirs) if (t.kind().equals("parent") && t.b().equals(child)) named.computeIfAbsent(locatorOf(byId.get(t.claim())), k -> new ArrayList<>()).add(t);
                String sex = sexOf(parent).isEmpty() ? FamilyClose.sexOfWords(c.kin(parent)) : sexOf(parent);
                for (Map.Entry<String, List<Rel>> s : named.entrySet()) {
                    if (s.getValue().stream().anyMatch(t -> t.a().equals(parent))) continue;   // the source names that parent too: a question of which kind of parent
                    List<Rel> others = s.getValue().stream().filter(t -> !FamilyQuestions.placeholder(label(t.a())) && !NOT_BORN_TO.matcher(FamilyChecks.quoteOf(byId.get(t.claim()))).find()).toList();
                    List<String> people = others.stream().map(Rel::a).distinct().toList();
                    // the people the parent may be under another name: those of the parent's sex, else those whose sex is not filed
                    List<String> same = people.stream().filter(p -> !sex.isEmpty() && sexOf(p).equals(sex)).toList();
                    List<String> alike = !same.isEmpty() ? same : people.stream().filter(p -> sex.isEmpty() || sexOf(p).isEmpty()).toList();
                    boolean clash = !same.isEmpty() || people.size() >= 2;
                    if (!clash || !done.add(m.claim() + "\u0000" + s.getKey())) continue;
                    notesAgainst(out, m, others, s.getKey(), alike.stream().filter(p -> !apart.contains(Graph.pair(p, parent))).toList());
                }
            }
        }

        /** Whether a source's relation between the two people of the owner's may stand beside it: the same relation, the same way round. */
        boolean compatible(Rel m, Rel t) {
            if (!m.kind().equals(t.kind())) return false;
            return !m.kind().equals("parent") || m.a().equals(t.a());
        }

        /** "father", "sister", "wife": what {@code a} is to {@code b} in a relation, by a's sex. */
        String relWord(Rel r, String a) {
            String s = sexOf(a).isEmpty() ? FamilyClose.sexOfWords(close().kin(a)) : sexOf(a);
            boolean m = s.equals("male"), f = s.equals("female");
            return switch (r.kind()) {
                case "parent" -> a.equals(r.a()) ? (m ? "father" : f ? "mother" : "parent") : (m ? "son" : f ? "daughter" : "child");
                case "sibling" -> m ? "brother" : f ? "sister" : "brother or sister";
                default -> m ? "husband" : f ? "wife" : "husband or wife";
            };
        }

        /** Where the owner's own word stands, as a sentence begins with it: "Your note beside the link to www.example.org", "Your notes (notes.txt)". */
        String ownerWhere(Finding f) {
            String loc = f == null || f.sources().isEmpty() ? "" : f.sources().get(0).locator();
            if (loc.startsWith("told://link-note/")) {
                String host;
                try { host = URI.create(loc.substring("told://link-note/".length())).getHost(); } catch (IllegalArgumentException e) { host = null; }
                return "Your note beside the link to " + (host == null ? "a page" : host);
            }
            if (loc.startsWith("told://")) return "What you told the library";
            return "Your notes (" + FamilyChecks.from(loc) + ")";
        }

        /**
         * One question: the owner's relation {@code m} and a source's relations {@code theirs} side by side, each with its own words. {@code
         * alike}: the people the source names who may be the owner's parent under another name, each offered as that person.
         */
        void notesAgainst(List<Posed> out, Rel m, List<Rel> theirs, String locator, List<String> alike) {
            String a = m.a(), b = m.b();
            if (!(in(a) || in(b) || theirs.stream().anyMatch(t -> in(t.a()) || in(t.b())))) return;
            Finding note = byId.get(m.claim());
            String src = locator.isBlank() ? "a source" : FamilyChecks.from(locator);
            boolean parents = !alike.isEmpty() || theirs.stream().noneMatch(t -> t.between(a, b));
            StringBuilder t = new StringBuilder(parents ? "Your notes and a source disagree about the parents of " + who(b) + ". " : "Your notes and a source disagree about " + who(a) + " and " + who(b) + ". ");
            t.append(ownerWhere(note)).append(" says: “").append(Acquisitions.compress(FamilyChecks.quoteOf(note).isBlank() ? note.title() : FamilyChecks.quoteOf(note), 200)).append("”, so ")
             .append(who(a)).append(" is ").append(who(b)).append("'s ").append(relWord(m, a)).append(". ");
            // the source's own words: those that write most of the people it names
            List<String> named = theirs.stream().flatMap(x -> List.of(x.a(), x.b()).stream()).distinct().filter(x -> !x.equals(a) || !parents).toList();
            Finding first = theirs.stream().map(x -> byId.get(x.claim())).max(Comparator.comparingInt((Finding f) -> (int) named.stream().filter(x -> writes(FamilyChecks.quoteOf(f), x)).count())).orElseThrow();
            t.append("The source, ").append(src).append(", says: “").append(Acquisitions.compress(FamilyChecks.quoteOf(first).isBlank() ? first.title() : FamilyChecks.quoteOf(first), 200)).append("”");
            if (parents) {
                List<String> ps = theirs.stream().map(Rel::a).distinct().map(this::who).toList();
                t.append(", and names ").append(ps.size() == 1 ? ps.get(0) + " as a parent of " + who(b) : String.join(" and ", ps) + " as the parents of " + who(b)).append(". ");
                for (String p : alike) t.append("Between ").append(who(p)).append(" and ").append(who(a)).append(", ").append(lower(compare(p, a).said())).append(' ');
                t.append(alike.isEmpty() ? "Which is right?" : alike.size() == 1 ? "Is " + who(alike.get(0)) + " the same person as " + who(a) + ", or is your note or the source wrong?"
                        : "Is one of them the same person as " + who(a) + ", or is your note or the source wrong?");
            } else {
                Rel r = theirs.stream().filter(x -> x.between(a, b)).findFirst().orElse(theirs.get(0));
                t.append(", so ").append(who(a)).append(" is ").append(who(b)).append("'s ").append(relWord(r, a)).append(". Which is right?");
            }
            List<Option> opts = new ArrayList<>();
            Map<String, Act> acts = new LinkedHashMap<>();
            int i = 0;
            for (String p : alike) {
                String key = "c" + (++i);
                boolean pIntoA = folds(p, a);
                String fold = pIntoA ? label(p) : label(a), into = pIntoA ? label(a) : label(p);
                opts.add(new Option(key, who(p) + " is the same person as " + who(a), "joins “" + fold + "” into “" + into + "”: the facts about “" + fold + "” are then about " + into + ". To take it back: " + REOPEN_HERE));
                acts.put(key, x -> merged(x, fold, into, ""));
            }
            String code = note.id().replaceFirst("^(F-\\d+).*", "$1");
            List<String> theirClaims = theirs.stream().map(Rel::claim).distinct().toList();
            String theirCodes = String.join(", ", theirClaims.stream().map(x -> x.replaceFirst("^(F-\\d+).*", "$1")).toList());
            opts.add(new Option("notes", "My note is wrong", "marks " + code + " as disputed, with your answer as the reason. It stays on record. To take it back: " + REOPEN_HERE));
            acts.put("notes", x -> disputed(x, List.of(m.claim()), "the family said their own note is wrong here"));
            opts.add(new Option("source", "The source, " + src + ", is wrong", "marks " + theirCodes + " as disputed, with your answer as the reason. " + (theirClaims.size() == 1 ? "It stays" : "They stay") + " on record. To take it back: " + REOPEN_HERE));
            acts.put("source", x -> disputed(x, theirClaims, "the family said " + src + " is wrong here"));
            opts.add(new Option("unsure", "I am not sure", "keeps both as they are, and does not ask again. To take it back: " + REOPEN_HERE));
            acts.put("unsure", x -> new Done("Kept as it is: your note and " + src + " both stay as they are. To take it back: researchzosho genealogy who --reopen " + x.code(), "not sure whether the note or " + src + " is right", ""));
            List<String> claims = new ArrayList<>(List.of(m.claim())); claims.addAll(theirClaims);
            add(out, "notes-source", List.of(a, b), claims, t.toString(), opts, acts, alike);
        }

        /**
         * A name the family says the person never carried: off every entry of the person that lists it, and the claims that give it marked
         * as disputed. Taking the answer back gives the name back to those entries and the claims their state.
         */
        Done notTheirs(Ctx x, String id, FamilyNameHistory.Name n) throws IOException {
            // every form of the name goes with it: Tai Kreidler and Tai D. Kreidler are one name, and one answer
            List<String[]> carriers = new ArrayList<>();
            for (Vocabulary.Term t : Vocabulary.read(Graph.nodesFile(x.store())).terms().values()) {
                String l = Graph.labelOf(t.description());
                if (!g.nodeIdOf(l).equals(id)) continue;
                for (String a : t.also()) if (a.equals(n.written()) || n.isForm(a)) carriers.add(new String[]{l, a});
            }
            Graph.dropAliases(x.store(), carriers);
            List<String> was = new ArrayList<>();
            for (String c : n.claims()) { Finding f = byId.get(c); was.add(f == null ? "" : f.state().name()); new Council(x.store()).dispute(c, "the family said it was never a name of " + label(id) + " (question " + x.code() + ")"); }
            StringBuilder filed = new StringBuilder();
            for (String[] c : carriers) filed.append(filed.isEmpty() ? "" : ",").append(c[0].replace(",", " ").replace("|", " ")).append('|').append(c[1].replace(",", " ").replace("|", " "));
            String detail = "dropped=" + filed + (n.claims().isEmpty() ? "" : "; disputed=" + String.join(",", n.claims()) + "; was=" + String.join(",", was));
            List<String> forms = carriers.stream().map(c -> c[1]).filter(f -> !f.equals(n.written())).distinct().toList();
            return new Done("“" + n.written() + "”" + (forms.isEmpty() ? "" : ", also written " + String.join(", ", forms.stream().map(f -> "“" + f + "”").toList()) + ",") + " is no longer a name of " + label(id) + "." + (n.claims().isEmpty() ? "" : " The facts that gave it are marked as disputed.")
                    + " To take it back: researchzosho genealogy who --reopen " + x.code(), "“" + n.written() + "” was never a name of " + label(id), detail);
        }

        /** The claims marked as disputed, with the family's answer as the reason; each claim's state before is kept, so taking the answer back restores it. */
        Done disputed(Ctx x, List<String> ids, String why) throws IOException {
            List<String> was = new ArrayList<>();
            for (String id : ids) { Finding f = byId.get(id); was.add(f == null ? "" : f.state().name()); new Council(x.store()).dispute(id, why + " (question " + x.code() + ")"); }
            String codes = String.join(", ", ids.stream().map(c -> c.replaceFirst("^(F-\\d+).*", "$1")).toList());
            return new Done(codes + (ids.size() == 1 ? " is" : " are") + " marked as disputed. To take it back: researchzosho genealogy who --reopen " + x.code(), codes + ": " + lower(x.says()),
                    "disputed=" + String.join(",", ids) + "; was=" + String.join(",", was));
        }

        /** Whether words write a person by one of their names, or by a word of one. */
        boolean writes(String quote, String id) {
            Graph.Node n = g.node(id);
            if (n == null || quote == null || quote.isBlank()) return false;
            String q = KanjiForms.modern(quote).toLowerCase(Locale.ROOT);
            for (String x : texts(n)) for (String w : KanjiForms.modern(bareName(x)).toLowerCase(Locale.ROOT).split("[\\s,　]+")) if (w.length() >= 2 && q.contains(w)) return true;
            return false;
        }

        /**
         * A person as a question names them for the owner: the owner is "you", and the owner's relative the library knows only by the
         * owner's words for them ("the owner of this library's father's father") is "your father's father"; anybody else by their name.
         */
        String who(String id) {
            Graph.Node n = g.node(id);
            if (n != null && n.label().strip().toLowerCase(Locale.ROOT).startsWith(FamilyClose.OWNER)) { String w = close().said(id); if (!w.isEmpty()) return w; }
            return label(id);
        }

        /**
         * The start of a question that says who its people are to the owner, where the walk from the owner can say it: "Tom Hale is your
         * father's father's brother." Two entries of one relation are said once: "“Tom” and Tom Hale are both your father's father's
         * brother." Nothing for somebody the walk did not reach, or whom the question already names by those words.
         */
        String toYou(List<String> people) {
            Map<String, List<String>> byWords = new LinkedHashMap<>();
            for (String p : people) {
                Graph.Node n = g.node(p);
                if (n == null || !"person".equals(n.kind()) || FamilyHouses.isFamily(g, p) || !who(p).equals(label(p))) continue;
                String w = close().said(p);
                if (w.isEmpty()) continue;
                List<String> names = byWords.computeIfAbsent(w, k -> new ArrayList<>());
                if (!names.contains(label(p))) names.add(label(p));
            }
            return saidOnce(byWords);
        }

        // ── how a name is read ──

        void reading(List<Posed> out) {
            for (Graph.Node p : people) {
                if (!in(p.id()) || FamilyQuestions.placeholder(p.label()) || !FamilyForms.script(p.label()).equals("han")) continue;
                // readings of two names of one life are no name read two ways: each reading goes with the name it is a form of
                List<FamilyNameHistory.Name> names = idx.names(p.id());
                Map<String, List<String>> byName = new LinkedHashMap<>();
                for (String k : kanaReadings(p)) {
                    String of = "";
                    for (FamilyNameHistory.Name x : names) if (!x.implicit() && x.texts().contains(k)) { of = x.written(); break; }
                    byName.computeIfAbsent(of, z -> new ArrayList<>()).add(k);
                }
                for (Map.Entry<String, List<String>> group : byName.entrySet()) {
                    List<String> kana = group.getValue();
                    if (kana.size() < 2 || settled(p, kana)) continue;
                    FamilyNameHistory.Name owner = group.getKey().isEmpty() ? null : names.stream().filter(x -> x.written().equals(group.getKey()) && !x.implicit()).findFirst().orElse(null);
                    // readings no name claim lists are readings of the name the entry is filed under: they go on that name, never on a later one
                    String filedUnder = bareName(p.label());
                    FamilyNameHistory.Name labelName = names.stream().filter(x -> x.isForm(filedUnder) || x.isForm(p.label())).findFirst().orElse(null);
                    FamilyNameHistory.Name latestName = idx.latest(p.id());
                    FamilyNameHistory.Name base = owner != null ? owner : labelName;
                    String what = owner != null ? owner.written() : filedUnder;
                    String whose = owner == null && (labelName == null || labelName == latestName) ? heading(p.id()) : "The name " + what + " of " + heading(p.id());
                    List<String> shown = new ArrayList<>();
                    for (String k : kana) { List<String> s = readingSources(p.id(), k); shown.add(k + (s.isEmpty() ? "" : " (" + String.join(", ", s) + ")")); }
                    String text = whose + " is read " + kana.size() + " ways in your sources: " + String.join("; ", shown) + ". A search looks for the reading, so the one that is right decides what it finds. Which reading is right?";
                    List<Option> opts = new ArrayList<>();
                    Map<String, Act> acts = new LinkedHashMap<>();
                    int i = 0;
                    // a name no claim gives, before a dated name of the life, keeps its place in the life: a claim of the family's with no kind
                    // and no year would make it a name of its own, placed after the dated one. The answer is written down in the list of answers
                    boolean inList = base != null && base.implicit() && names.stream().anyMatch(x -> x != base && x.replaces() && x.dated());
                    for (String k : kana) {
                        String key = "r" + (++i);
                        if (inList) {
                            opts.add(new Option(key, k, "saves as your answer that " + what + " is read " + k + ". Both readings stay among the other names. To take it back: " + REOPEN_HERE + "."));
                            acts.put(key, x -> new Done("Saved as your answer: " + what + " is read " + k + ". To take it back: researchzosho genealogy who --reopen " + x.code(), what + " is read " + k, FamilyDetail.text(Map.of("reading", k))));
                            continue;
                        }
                        opts.add(new Option(key, k, "saves as your answer that " + what + " is read " + k + ". Both readings stay among the other names. To take it back: " + REOPEN_HERE + "."));
                        acts.put(key, x -> {
                            FamilyNameHistory.Name on = base != null ? base : new FamilyNameHistory.Name(filedUnder, "", "", List.of(), "unknown", "", null, false, null, "", List.of(), Evidence.clue, false, true, false);
                            FamilyNameHistory.Name with = new FamilyNameHistory.Name(on.written(), on.family(), on.given(), List.of(new FamilyNameHistory.Form(k, FamilyForms.lang(k))), on.kind(), on.said(), on.from(), on.fromEvent(), on.to(), on.event(), on.claims(), on.evidence(), on.accepted(), on.implicit(), on.workedOut());
                            String id = nameWord(x, filedUnder(p.id(), on.claims(), on.written()), with, "unknown", "", "");
                            return new Done(filedSaid(x, List.of(id)), what + " is read " + k, "claims=" + id);
                        });
                    }
                    opts.add(new Option("neither", "Neither of these", "keeps both readings as they are, and does not ask again."));
                    acts.put("neither", x -> new Done("Kept as it is: neither reading is taken as right.", what + ": neither reading", ""));
                    add(out, "reading", List.of(p.id()), owner == null ? List.of() : List.of("name: " + owner.written()), text, opts, acts);   // one question per name
                }
            }
        }

        /** The readings in kana a person's names are written with: among the other names, and among the forms of the names the claims give. */
        List<String> kanaReadings(Graph.Node p) {
            List<String> texts = new ArrayList<>(p.aliases());
            for (FamilyNameHistory.Name n : idx.names(p.id())) for (String t : n.texts()) if (!texts.contains(t)) texts.add(t);
            List<String> kana = new ArrayList<>();
            for (String a : texts) if (a.strip().codePoints().allMatch(c -> (c >= 0x3040 && c <= 0x30ff) || Character.isWhitespace(c) || c == '・' || c == '　') && kana.stream().allMatch(k -> !FamilyAccount.sameReading(k, a))) kana.add(a.strip());
            return kana;
        }

        /** Whether the family already said which reading is right: a name claim of theirs lists one of the readings. */
        boolean settled(Graph.Node p, List<String> kana) {
            for (FamilyNameHistory.Name n : idx.names(p.id())) {
                if (!n.accepted()) continue;
                for (String c : n.claims()) {
                    Finding f = byId.get(c);
                    if (f == null || f.review() == null || !"person".equals(f.review().reviewer())) continue;
                    for (FamilyNameHistory.Form x : FamilyNameHistory.formsOf(FamilyDetail.get(f, "forms"))) for (String k : kana) if (FamilyAccount.sameReading(k, x.text())) return true;
                }
            }
            return false;
        }
    }

    /** The kind of note the family's answer puts on a record: "written later, under the name carried then". */
    static final String NOTE = "names-answer";

    /** The kinds of name a person takes at the event that causes it, so that the event's date is the name's. */
    static final Set<String> AT_THE_EVENT = Set.of("marriage", "mukoyoshi", "nyufu", "adoptive");

    /** A label without the bracket that tells two entries of one name apart: "Ruth Hale (born 1850)" is the name Ruth Hale. */
    static String bareName(String label) {
        String s = label == null ? "" : label.strip();
        String b = s.replaceAll("\\s*[(（][^)）]*[)）]\\s*$", "").strip();
        return b.isEmpty() ? s : b;
    }

    /** What an answer filed, in words: "Saved as your answer: 森田健二 entered 森田 family as 婿養子 (F-0003)." with the command that takes one back. */
    private static String filedSaid(Ctx c, List<String> ids) throws IOException {
        List<String> said = new ArrayList<>();
        for (String id : ids) { Finding f = c.store().finding(id); said.add((f == null ? "" : f.title().replaceFirst("[.。]$", "") + " ") + "(" + id.replaceFirst("^(F-\\d+).*", "$1") + ")"); }
        // the answer is taken back whole, with the question asked again; disputing one of several claims would leave the others standing
        return "Saved as your answer: " + String.join("; ", said) + ". To take it back: researchzosho genealogy who --reopen " + c.code();
    }

    /** A claim filed as the family's word and accepted at once, with the question it answers as its source; its id. */
    private static String word(Ctx c, Finding.Triple t, String sentence, Map<String, String> detail) throws IOException {
        String id = c.store().nextFindingId(t.subject() + " " + t.predicate() + " " + t.object());
        List<Finding.Source> src = List.of(new Finding.Source(SOURCE + c.code(), "as told by " + c.teller(), "the family's answer to a question about names and families"));
        List<Finding.Note> notes = detail == null || detail.isEmpty() ? List.of() : List.of(FamilyDetail.note(detail, "family-account"));
        String body = sentence + "\n\nThe family's answer to the question " + c.code() + ": " + c.says() + "\n";
        Finding f = new Finding(id, Acquisitions.compress(sentence, 80), List.of(), Finding.State.draft, Finding.ClaimType.extraction, Finding.Confidence.low, "family-account",
                Instant.now().toString(), LocalDate.now().toString(), Finding.Volatility.stable, "", src, List.of(), null, body, t, notes);
        c.store().write(f);
        new Council(c.store()).accept(id);
        return id;
    }

    /**
     * For the checks, from a graph already read: the pairs that may be one person under two family names ({@code one-person} questions a
     * record or a shared given name raises), and the records under a name not carried then ({@code name-at-date}). Put off or not, and
     * less those the family answered. Nothing when genealogy is off.
     */
    static List<Question> forChecks(LibraryStore store, Graph g) throws IOException {
        if (!on(store)) return List.of();
        Work w = new Work(store, g, null, false);
        List<Posed> found = new ArrayList<>();
        w.twoFamilyNames(found);
        w.nameAtDate(found);
        Map<String, String[]> asked = asked(store);
        Map<String, String> merges = Graph.merges(store);
        Map<String, List<String>> into = joinedInto(merges);
        List<Question> out = new ArrayList<>();
        for (Posed p : found) {
            String[] a = answerOf(p, asked, into);
            if (a != null && a[0].equals("answered") && stands(a.length > 4 ? a[4] : "", w.idx, w.apart, merges)) continue;
            out.add(p.q());
        }
        return out;
    }

    /** A place where the owner's notes and a source disagree ({@code notes-source}), with the family's answer to it: {answered|later, date, by, what, done}; null while it waits. */
    record Disagreement(Question question, String[] answer) { }

    /**
     * Every place where the owner's own notes and a source disagree, answered or not, for the family summary ({@link FamilySummary}). An
     * answer that disputed the note or the source took the disagreement away; here those claims count as they did before the answer, so the
     * disagreement is shown beside the answer that settled it. Nothing when genealogy is off.
     */
    static List<Disagreement> disagreements(LibraryStore store, Graph g) throws IOException {
        if (!on(store)) return List.of();
        Map<String, String[]> asked = asked(store);
        Set<String> disputedByAnswer = new HashSet<>();
        for (String[] a : asked.values()) {
            if (!a[0].equals("answered")) continue;
            for (String id : FamilyDetail.parse(a.length > 4 ? a[4] : "").getOrDefault("disputed", "").split(",")) if (!id.isBlank()) disputedByAnswer.add(id.strip());
        }
        Work w = new Work(store, g, null, false);
        w.countedAgain = disputedByAnswer;
        List<Posed> found = new ArrayList<>();
        w.notesAndSources(found);
        Map<String, List<String>> into = joinedInto(Graph.merges(store));
        List<Disagreement> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Posed p : found) if (seen.add(p.q().code())) out.add(new Disagreement(p.q(), answerOf(p, asked, into)));
        return out;
    }

    /** Whether the family said which of a person's readings is right: a name claim of theirs lists one of them as a form. */
    static boolean readingSettled(Graph g, String id, List<String> kana) {
        // the reading of a name no claim gives, which the family's answer wrote down in the list of answers
        try {
            String[] a = g.store() == null ? null : asked(g.store()).get(code("reading", List.of(id), List.of()));
            if (a != null && a[0].equals("answered")) {
                String r = FamilyDetail.parse(a.length > 4 ? a[4] : "").getOrDefault("reading", "");
                for (String k : kana) if (!r.isBlank() && FamilyAccount.sameReading(k, r)) return true;
            }
        } catch (IOException ignored) { }
        FamilyNameHistory.Index idx = FamilyNameHistory.of(g);
        for (FamilyNameHistory.Name n : idx.names(id)) {
            if (!n.accepted()) continue;
            for (String c : n.claims()) {
                Finding f = idx.finding(c);
                if (f == null || f.review() == null || !"person".equals(f.review().reviewer())) continue;
                for (FamilyNameHistory.Form x : FamilyNameHistory.formsOf(FamilyDetail.get(f, "forms"))) for (String k : kana) if (FamilyAccount.sameReading(k, x.text())) return true;
            }
        }
        return false;
    }

    /** A count of the questions that wait, by kind, in words: "who a person written only by a family name is (2), how a name changed (1)". */
    public static String counted(List<Question> qs) {
        Map<String, Integer> n = new LinkedHashMap<>();
        for (String k : KINDS) n.put(k, 0);
        for (Question q : qs) n.merge(q.kind(), 1, Integer::sum);
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Integer> e : n.entrySet()) if (e.getValue() > 0) out.add(switch (e.getKey()) {
            case "notes-source" -> "where your notes and a source disagree";
            case "family-name-alone" -> "who a person written only by a family name is";
            case "one-person" -> "whether two names are one person";
            case "name-change-how" -> "how a name changed";
            case "name-change-when" -> "when a name changed";
            case "birth-or-adoptive" -> "whether a parent is a birth parent";
            case "which-family" -> "which of two families is meant";
            case "name-at-date" -> "a record under a name not carried then";
            default -> "how a name is read";
        } + " (" + e.getValue() + ")");
        return String.join(", ", out);
    }

    /**
     * Questions in the order a sitting asks them: who is who first (a person written by a family name alone, one person or two), then how and
     * when names changed and which kind of parent, by kind and then as they were found; and a question another of them would settle after that
     * one ({@link #settledFirst}): whether two entries are one person comes before a question about a family name alone that offers one of
     * them, so that the people it offers are one entry each by then.
     */
    public static List<Question> ordered(List<Question> qs) {
        List<Question> out = new ArrayList<>(qs);
        out.sort(Comparator.comparingInt(q -> KINDS.indexOf(q.kind())));
        return settledFirst(out);
    }

    /** The kinds of question that say who is who: their answer can make another question go away. */
    static final Set<String> IDENTITY = Set.of("family-name-alone", "one-person");

    /**
     * Whether the answer to {@code first} would settle {@code then}: {@code first} asks who is who, and every person it is about is one that
     * {@code then} is about or turns on. Whether 勇 and 森田勇 are one person settles whether each is a birth father of the child written
     * with both; whether one of the people a question about a family name alone offers is one person with another entry settles whom it
     * offers.
     */
    public static boolean settles(Question first, Question then) {
        if (first == null || then == null || first.code().equals(then.code()) || !IDENTITY.contains(first.kind())) return false;
        // whether a person a question about a family name alone offers is one person with another entry changes whom it offers, and their years
        if (first.kind().equals("one-person") && then.kind().equals("family-name-alone") && first.people().stream().anyMatch(then.turnsOn()::contains)) return true;
        Set<String> about = new HashSet<>(then.people());
        about.addAll(then.turnsOn());
        return about.containsAll(first.people());
    }

    /** The questions in their order, each that another of them would settle ({@link #settles}) moved to just after the last such one. */
    static List<Question> settledFirst(List<Question> qs) {
        List<Question> out = new ArrayList<>(qs);
        for (int pass = 0; pass <= out.size(); pass++) {
            boolean moved = false;
            for (int i = 0; i < out.size() && !moved; i++) {
                int last = -1;
                for (int j = i + 1; j < out.size(); j++) if (settles(out.get(j), out.get(i))) last = j;
                if (last > i) { Question q = out.remove(i); out.add(last, q); moved = true; }
            }
            if (!moved) break;
        }
        return out;
    }
}
