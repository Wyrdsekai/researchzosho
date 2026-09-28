package org.researchzosho.librarian;

import org.researchzosho.records.RecordSource;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.Collections;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.researchzosho.records.RecordSources;
import org.researchzosho.drive.Declined;
/**
 * The research a family tree asks for: one question per person who has died, written from what the claims already
 * say about them, so a run starts from the known dates, places and relatives instead of a bare name. A person at the
 * edge of the tree, with no parents or no children in it, is also asked about those, which is how the tree grows a
 * generation at a time. The people the library knows least about come first. A person who may be living, a child among
 * them, is asked about unless the library's owner says otherwise (--skip-living), and only about their work and public
 * life: a run sends its questions to search services.
 */
public final class FamilyQuestions {

    private FamilyQuestions() { }

    /** known = how many things the claims say about the person; the fewer, the earlier the question. living = possibly alive: asked about after those who have died. */
    /**
     * One person's research: {@code question} is the whole text a run is given; {@code questions} are the concise questions in it,
     * each written from a gap in what the library holds and each done when answered, the way a genealogist works.
     */
    /** {@code gaps}: what is not known yet about the person, in a few words each ("parents", "when and where born"), in the order of the questions. */
    public record Ask(String person, int generation, int known, String question, boolean living, List<String> questions, List<String> gaps) {
        public Ask(String person, int generation, int known, String question, boolean living) { this(person, generation, known, question, living, List.of()); }
        public Ask(String person, int generation, int known, String question, boolean living, List<String> questions) { this(person, generation, known, question, living, questions, List.of()); }
        public Ask withQuestion(String text) { return new Ask(person, generation, known, text, living, questions, gaps); }
    }

    /** At most this many concise questions per person: a run takes up to eight sub-questions. */
    static final int QUESTIONS = 8;

    public static List<Ask> around(LibraryStore store, String focusName, int up, int down) throws IOException { return around(store, focusName, up, down, false); }

    /**
     * {@code living}: the people who may be living are asked about and named as leads, children among them. Their names then go out
     * in searches; the owner leaves them out with --skip-living.
     */
    public static List<Ask> around(LibraryStore store, String focusName, int up, int down, boolean living) throws IOException { return around(store, focusName, up, down, living, false); }

    /**
     * {@code askFocus}: the owner named the focus person to be asked about, whether or not they may be living: that person is
     * admitted, and nothing else changes. What the questions name of anybody else still follows {@code living}.
     */
    public static List<Ask> around(LibraryStore store, String focusName, int up, int down, boolean living, boolean askFocus) throws IOException {
        FamilyTree.Tree tree = FamilyTree.around(store, focusName, up, down, false);   // what the family disputed is no lead and no fact
        if (tree.focus() == null) return null;
        Graph g = FamilyPeople.view(store);
        Map<String, Finding> findings = new LinkedHashMap<>();
        for (Finding f : store.scanFindings().findings()) findings.put(f.id(), f);
        Map<String, FamilyTree.Person> byId = new LinkedHashMap<>();
        for (FamilyTree.Person p : tree.people()) byId.put(p.id(), p);
        Map<String, String[]> places = new LinkedHashMap<>();   // person → [born in, died in]
        List<Held> held = held(store);
        Map<String, FamilyBounds.Window> windows = FamilyBounds.of(g, findings);
        // whether somebody may be named in another person's question, which a run sends to search services: a person who may be living
        // only when the owner asked for the living
        Predicate<String> mayName = id -> {
            Graph.Node x = g.node(id);
            return x != null && (living || !x.mayBeLiving());
        };
        // the records the family asked for on the decisions page, when it could not tell: asked in the research for the people they name
        List<Frontier.Line> records = Frontier.read(store).stream().filter(l -> l.open() && l.kind().contains(FamilyDecisions.RECORD)).toList();
        Map<String, List<String>> lived = new LinkedHashMap<>();
        for (Graph.Edge e : g.edges()) {
            if (!byId.containsKey(e.from()) || e.disputed()) continue;
            Graph.Node to = g.node(e.to());
            if (to == null) continue;
            switch (e.predicate()) {
                case "born-in" -> { String[] p = places.computeIfAbsent(e.from(), k -> new String[]{"", ""}); if (p[0].isEmpty()) p[0] = to.label(); }
                case "died-in" -> { String[] p = places.computeIfAbsent(e.from(), k -> new String[]{"", ""}); if (p[1].isEmpty()) p[1] = to.label(); }
                case "lived-in", "migrated-to" -> { List<String> l = lived.computeIfAbsent(e.from(), k -> new ArrayList<>()); if (!l.contains(to.label()) && l.size() < 3) l.add(to.label()); }
                default -> { }
            }
        }
        Map<String, Evidence> byFact = FamilyPeople.byFact(g, findings);
        List<SearchLog.Entry> log = SearchLog.all(store);
        List<Ask> out = new ArrayList<>();
        for (FamilyTree.Person p : tree.people()) {
            if (placeholder(p.label())) continue;   // somebody the account did not name: there is no name to search for
            // a name with a character nobody could read is no search either, unless the person is also written in a form that can be read
            if (FamilyNames.unreadable(p.label()) && (g.node(p.id()) == null || g.node(p.id()).aliases().stream().allMatch(FamilyNames::unreadable))) continue;
            // somebody who may be living is asked about only when the owner says so
            boolean byName = askFocus && p.id().equals(tree.focus().id());
            if (p.mayBeLiving() && !living && !byName) continue;
            List<String> parents = new ArrayList<>(), spouses = new ArrayList<>();
            LinkedHashSet<String> parentIds = new LinkedHashSet<>(), siblingIds = new LinkedHashSet<>(), childIds = new LinkedHashSet<>(), spouseIds = new LinkedHashSet<>();
            boolean hasChildren = false;
            for (FamilyTree.Link l : tree.links()) {
                if (l.kind().equals("sibling")) { if (l.from().equals(p.id())) siblingIds.add(l.to()); else if (l.to().equals(p.id())) siblingIds.add(l.from()); continue; }
                if (l.kind().equals("married")) { if (l.from().equals(p.id())) spouseIds.add(l.to()); else if (l.to().equals(p.id())) spouseIds.add(l.from()); }
                else if (l.to().equals(p.id())) parentIds.add(l.from());
                else if (l.from().equals(p.id())) childIds.add(l.to());
                if (l.kind().equals("married")) {
                    String other = l.from().equals(p.id()) ? l.to() : l.to().equals(p.id()) ? l.from() : null;
                    if (other != null && named(byId.get(other), living) && !spouses.contains(byId.get(other).label())) spouses.add(byId.get(other).label());
                } else if (l.to().equals(p.id())) {
                    // a parent worked out from a brother or sister is a lead, not a fact to confirm: who the parents were is still asked
                    if (!Graph.WORKED_OUT.equals(l.state()) && named(byId.get(l.from()), living) && !parents.contains(byId.get(l.from()).label())) parents.add(byId.get(l.from()).label());
                } else if (l.from().equals(p.id())) hasChildren = true;
            }
            String[] at = places.getOrDefault(p.id(), new String[]{"", ""});
            List<String> facts = new ArrayList<>();
            // the other ways the name is written: the web in another language knows the person under that form, not under this one
            Graph.Node self = g.node(p.id());
            List<String> forms = new ArrayList<>();
            if (self != null) for (String a : self.aliases()) if (!placeholder(a) && !FamilyNames.unreadable(a) && a.strip().length() >= 2 && !a.equalsIgnoreCase(p.label()) && forms.stream().noneMatch(a::equalsIgnoreCase) && forms.size() < 5) forms.add(a.strip());
            // a person who carried more than one name over the life: each name with its years, how it came and its other forms, since the
            // records of each period are written under the name carried then
            List<Period> periods = periods(g, p.id());
            List<String> nameFacts = nameFacts(periods);
            boolean written = !nameFacts.isEmpty() || !forms.isEmpty();
            if (!nameFacts.isEmpty()) facts.addAll(nameFacts);
            else if (written) facts.add("also written " + String.join(", ", forms));
            if (!p.born().isEmpty() || !at[0].isEmpty()) facts.add(("born " + p.born() + (at[0].isEmpty() ? "" : " in " + at[0])).replaceAll("\\s+", " "));
            // no birth date: the years the family's other dates leave for it, which a record search can be held to
            FamilyBounds.Window window = p.born().isEmpty() ? windows.get(p.id()) : null;
            boolean worked = window != null && !window.born().isEmpty();
            if (worked) facts.add("born " + window.born() + ", as worked out from the family's other dates");
            if (!p.died().isEmpty() || !at[1].isEmpty()) facts.add(("died " + p.died() + (at[1].isEmpty() ? "" : " in " + at[1])).replaceAll("\\s+", " "));
            if (!parents.isEmpty()) facts.add("child of " + String.join(" and ", parents));
            if (!spouses.isEmpty()) facts.add("married " + String.join(" and ", spouses));
            if (!p.work().isEmpty()) facts.add("worked as " + p.work());
            List<String> where = lived.getOrDefault(p.id(), List.of());
            if (!where.isEmpty()) facts.add("lived in " + String.join(", ", where));
            // brothers and sisters are also the other children of the same parents
            for (FamilyTree.Link l : tree.links()) if (!FamilyTree.sameGeneration(l) && parentIds.contains(l.from()) && !l.to().equals(p.id())) siblingIds.add(l.to());
            // the concise questions, one for each hole in what the library holds: each is done when it is answered, which is how a
            // genealogist keeps a search from going on forever. A fact known only from a clue gets its own question: is it in a record?
            List<String> asks = new ArrayList<>();
            Map<String, String> gapOf = new HashMap<>();   // each question → what it says is not known yet, in a few words
            BiConsumer<String, String> ask = (question, gap) -> { asks.add(question); gapOf.putIfAbsent(question, gap); };
            boolean dead = !p.mayBeLiving();
            List<String> unknowns = unknownAsks(g, p.id(), p.label());
            if (!unknowns.isEmpty()) for (String u : unknowns) ask.accept(u, "a parent or a husband or wife whom a record calls unknown");
            else if (parents.isEmpty() && p.generation() > -up) ask.accept("Who were the parents of " + p.label() + "?", "parents");
            if (p.born().isEmpty() && at[0].isEmpty()) ask.accept("When and where was " + p.label() + " born?", "when and where born");
            else if (p.born().isEmpty()) ask.accept("When was " + p.label() + " born?", "when born");
            else if (!exactYear(p.born())) ask.accept((at[0].isEmpty() ? "Where was " + p.label() + " born, and in which year exactly?" : "In which year exactly was " + p.label() + " born?") + " The library has only " + p.born() + ".", at[0].isEmpty() ? "where born, and the exact year" : "the exact year of birth");
            else if (at[0].isEmpty()) ask.accept("Where was " + p.label() + " born?", "where born");
            if (dead && p.died().isEmpty() && at[1].isEmpty()) ask.accept("When and where did " + p.label() + " die?", "when and where died");
            else if (dead && p.died().isEmpty()) ask.accept("When did " + p.label() + " die?", "when died");
            if (dead && spouses.isEmpty() && spouseIds.isEmpty()) ask.accept("Whom did " + p.label() + " marry, and when?", "marriage");
            if (dead && !hasChildren && p.generation() < down) ask.accept("Who were the children of " + p.label() + "?", "children");
            // a record question names everybody it is about: it goes to the research as every other question does, the living among them only when asked for
            for (Frontier.Line l : records) {
                List<String> names = FamilyDecisions.named(l.text());
                if (names.contains(p.label()) && FamilyDecisions.mayGoOut(g, names, living)) { asks.add(0, l.text()); gapOf.putIfAbsent(l.text(), "a record you asked for"); }
            }
            // more than one name: each period is searched under the name carried then, and the record of a change under both. Once the log
            // holds searches for the person, the question names only what they have not covered yet, and it is done when they have
            if (periods.size() > 1) {
                String lead = Looked.lead(p.label() + ":");
                boolean searchedBefore = log.stream().anyMatch(e -> !e.failed() && Looked.lead(e.about()).equals(lead));
                String names = searchedBefore ? unsearchedPeriods(log, p.label(), periods, g, p.id()) : searchPeriods(g, p.id(), p.label(), periods);
                if (names != null) ask.accept(names, "records under each of the person's names");
            }
            List<String> onClues = cluesOnly(g, findings, p.id(), byFact, mayName);
            if (!onClues.isEmpty()) ask.accept("Which of these facts about " + p.label() + ", known so far only from a family account or somebody's tree, does a record confirm: " + String.join("; ", onClues) + "?", "a record that confirms what only a family account or a tree says");
            if (dead) {
                if (p.work().isEmpty()) ask.accept("What did " + p.label() + " do for a living, and where?", "work");
                ask.accept("What did " + p.label() + " do that was written about: an office, an honour, a work, a patent, a company, a journey, a public event?", "what was written about them");
            } else ask.accept("What do public sources say of " + p.label() + "'s work and public life?", "their work and public life");
            // a question the family has sent to a record office waits for its answer: no run searches the web for it meanwhile
            List<Held> waits = heldFor(held, p.label());
            if (!waits.isEmpty()) {
                asks.removeIf(x -> waits.stream().anyMatch(h -> sameAsk(h.ask(), x)));
                if (asks.isEmpty()) continue;
            }
            if (asks.size() > QUESTIONS) asks.subList(QUESTIONS, asks.size()).clear();
            StringBuilder q = new StringBuilder(p.label()).append(facts.isEmpty() ? ":" : " (" + String.join("; ", facts) + "):").append(" answer each of these questions on its own, and say for each whether it is answered, not found, or in conflict between records.");
            for (int i = 0; i < asks.size(); i++) q.append(" ").append(i + 1).append(". ").append(asks.get(i));
            // the family around the person, with what the library knows of each: a record of a sister names the same parents, a parent's
            // obituary names the children, a husband's biography names the wife. A relative is a way to the person, above all when the name is common
            List<String> around = new ArrayList<>();
            for (String[] group : new String[][]{{"brother or sister", String.join(",", siblingIds)}, {"parent", String.join(",", parentIds)}, {"husband or wife", String.join(",", spouseIds)}, {"child", String.join(",", childIds)}})
                for (String id : group[1].isEmpty() ? new String[0] : group[1].split(",")) {
                    FamilyTree.Person r = byId.get(id);
                    if (r == null || placeholder(r.label()) || !named(r, living) || around.size() >= 10) continue;
                    String[] rat = places.getOrDefault(id, new String[]{"", ""});
                    List<String> known = new ArrayList<>();
                    if (!r.born().isEmpty() || !rat[0].isEmpty()) known.add(("born " + r.born() + (rat[0].isEmpty() ? "" : " in " + rat[0])).replaceAll("\\s+", " "));
                    if (!r.died().isEmpty()) known.add("died " + r.died());
                    if (!r.work().isEmpty()) known.add(r.work());
                    List<String> rl = lived.getOrDefault(id, List.of());
                    if (!rl.isEmpty()) known.add("lived in " + String.join(", ", rl));
                    around.add(group[0] + " " + r.label() + (known.isEmpty() ? "" : " (" + String.join("; ", known) + ")"));
                }
            if (!around.isEmpty()) q.append(" The family around this person, as leads: ").append(String.join("; ", around)).append(".");
            // the people a record names beside the person: an informant, a witness and a godparent are often family or neighbours
            List<String> named = new ArrayList<>();
            for (Graph.Edge e : g.edges()) {
                if (!FamilyAccount.associate(e.predicate()) || (!e.from().equals(p.id()) && !e.to().equals(p.id())) || e.disputed() || named.size() >= 6) continue;
                Graph.Node a = g.node(e.from()), b = g.node(e.to()), other = e.from().equals(p.id()) ? b : a;
                if (a == null || b == null || other == null || placeholder(other.label()) || !mayName.test(other.id())) continue;
                String line = FamilyAccount.sentence(a.label(), e.predicate(), b.label());
                if (!named.contains(line)) named.add(line);
            }
            if (!named.isEmpty()) q.append(" People the records name beside this person, as leads: ").append(String.join("; ", named)).append(".");
            if (parents.isEmpty() && !siblingIds.isEmpty()) q.append(" The brothers and sisters have the same parents: what names a parent of one of them answers it for all.");
            List<String> gaps = asks.stream().map(x -> gapOf.getOrDefault(x, "")).filter(x -> !x.isEmpty()).distinct().toList();
            int aboutNames = !nameFacts.isEmpty() ? nameFacts.size() : written ? 1 : 0;   // how a name is written is no fact about the life
            out.add(new Ask(p.label(), p.generation(), facts.size() - aboutNames - (worked ? 1 : 0), q.toString(), p.mayBeLiving(), List.copyOf(asks), gaps));
        }
        // those who have died first: the records are about them, and they are the ones whose parents make the tree grow. Then the least known
        out.sort(Comparator.comparing(Ask::living).thenComparingInt(Ask::known).thenComparingInt(a -> Math.abs(a.generation())));
        return out;
    }

    /** Whether the tree holds a birth as one year, not about, before, after or between. */
    private static boolean exactYear(String born) { FamilyDate d = FamilyDate.parse(born); return d == null || d.exact(); }

    /**
     * Everybody in the family, when no starting person is given: the walk starts from the person with the most relatives, then
     * from the best-connected person it has not reached yet, until nobody with a family relation is left out. A library often
     * holds several families that do not touch yet (the father's side from one book, the mother's from some notes).
     */
    public static List<Ask> everybody(LibraryStore store, int up, int down, boolean living) throws IOException {
        Graph g = FamilyPeople.view(store);
        Map<String, Integer> links = new LinkedHashMap<>();
        for (Graph.Edge e : g.edges()) if (FamilyAccount.isKinship(e.predicate())) { links.merge(e.from(), 1, Integer::sum); links.merge(e.to(), 1, Integer::sum); }
        List<String> byLinks = new ArrayList<>(links.keySet());
        byLinks.sort(Comparator.comparingInt((String id) -> -links.get(id)));
        Map<String, Ask> all = new LinkedHashMap<>();
        Set<String> reached = new HashSet<>();
        int walks = 0;
        for (String id : byLinks) {
            Graph.Node n = g.node(id);
            if (n == null || reached.contains(n.label()) || walks >= 40) continue;
            walks++;
            FamilyTree.Tree tree = FamilyTree.around(store, n.label(), up, down, false);
            for (FamilyTree.Person p : tree.people()) reached.add(p.label());
            reached.add(n.label());
            List<Ask> part = around(store, n.label(), up, down, living);
            if (part != null) for (Ask a : part) all.putIfAbsent(a.person(), a);
        }
        List<Ask> out = new ArrayList<>(all.values());
        out.sort(Comparator.comparing(Ask::living).thenComparingInt(Ask::known).thenComparingInt(a -> Math.abs(a.generation())));
        return out;
    }

    /**
     * Several starting people: the families around each of them, every person once. The named people are asked about whether or not
     * they may be living, as the owner named them; what their questions name of anybody else follows {@code living}, as for any other
     * question, and so does who of their relatives is asked about. {@code only}: just the named people themselves, without their
     * relatives. A name the library does not have is returned in {@code unknown}.
     */
    public static List<Ask> aroundEach(LibraryStore store, List<String> names, int up, int down, boolean living, boolean only, List<String> unknown) throws IOException {
        Graph g = FamilyPeople.view(store);
        Map<String, Ask> all = new LinkedHashMap<>();
        for (String name : names) {
            List<Ask> part = around(store, name, up, down, living, true);
            if (part == null) { unknown.add(name); continue; }
            String id = g.node(g.nodeIdOf(name)) != null ? g.nodeIdOf(name) : g.nodeIdOf(KanjiForms.modern(name));
            String label = g.node(id) == null ? name : g.node(id).label();
            for (Ask a : part) if (!only || a.person().equals(label)) all.putIfAbsent(a.person(), a);
        }
        List<Ask> out = new ArrayList<>(all.values());
        out.sort(Comparator.comparing(Ask::living).thenComparingInt(Ask::known).thenComparingInt(a -> Math.abs(a.generation())));
        return out;
    }

    /** The pages that mention somebody, chosen for a research run: the library lists them, a model picks among them. A test passes its own. */
    public interface Leads { List<String> of(String lang, String title, String person, String years); }

    /**
     * The person's own pages in an encyclopedia, among the sources of the claims about them: a page whose title is the person's name
     * (whatever stands in brackets after it aside). Each is {language, title}.
     */
    public static List<String[]> ownPages(LibraryStore store, Graph g, String person) throws IOException {
        String id = g.nodeIdOf(person);
        Graph.Node node = g.node(id);
        if (node == null) return List.of();
        Set<String> names = new HashSet<>();
        names.add(KanjiForms.modern(node.label()).replaceAll("\\s+", ""));
        for (String a : node.aliases()) names.add(KanjiForms.modern(a).replaceAll("\\s+", ""));
        Set<String> keys = new HashSet<>(FamilyNames.keys(node.label()));
        for (String a : node.aliases()) keys.addAll(FamilyNames.keys(a));
        Map<String, String[]> out = new LinkedHashMap<>();
        Map<String, Finding> findings = new LinkedHashMap<>();
        for (Finding f : store.scanFindings().findings()) findings.put(f.id(), f);
        for (Graph.Edge e : g.edges()) {
            Finding f = e.from().equals(id) ? findings.get(e.findingId()) : null;
            if (f == null) continue;
            for (Finding.Source src : f.sources()) {
                String[] page = Mentions.page(src.locator());
                if (page == null) continue;
                String bare = page[1].replaceAll("\\s*[(（][^)）]*[)）]\\s*$", "").strip();
                boolean own = names.contains(KanjiForms.modern(bare).replaceAll("\\s+", "")) || !Collections.disjoint(keys, FamilyNames.keys(bare));
                if (own) out.putIfAbsent(page[0] + "\t" + page[1], page);
            }
        }
        return new ArrayList<>(out.values());
    }

    /**
     * The same questions, each with the encyclopedia pages that mention the person as leads, for the people who have a page of their own.
     * What a person took part in is written up under the event and not under the person: their own page gives an office and its dates,
     * and the affair that ended it has a page of its own, which LINKS to theirs. {@code progress} is told whom it is working on.
     */
    public static List<Ask> withLeads(LibraryStore store, List<Ask> asks, Leads leads, Consumer<String> progress) throws IOException {
        return withLeads(store, asks, leads, progress, new LinkedHashMap<>());
    }

    /**
     * As above; {@code declined} takes each person whose leads the model declined to pick, with its decline. That person's question is
     * left out of what is returned, the others go on, the decline is written in the circulation log, and the caller says it at the end.
     */
    public static List<Ask> withLeads(LibraryStore store, List<Ask> asks, Leads leads, Consumer<String> progress, Map<String, Declined> declined) throws IOException {
        if (leads == null) return asks;
        Graph g = FamilyPeople.view(store);
        List<Ask> out = new ArrayList<>();
        for (Ask a : asks) {
            List<String> titles = new ArrayList<>();
            try {
                for (String[] page : ownPages(store, g, a.person())) {
                    if (progress != null) progress.accept(a.person());
                    Matcher y = Pattern.compile("born (\\d{4})").matcher(a.question()), d = Pattern.compile("died (\\d{4})").matcher(a.question());
                    String years = (y.find() ? y.group(1) : "") + "-" + (d.find() ? d.group(1) : "");
                    for (String t : leads.of(page[0], page[1], a.person(), years.equals("-") ? "" : years)) if (!titles.contains(t)) titles.add(t);
                }
            } catch (Declined d) {
                // this person only: said at the end, and their question is not sent on as if the model had found no leads
                Declined at = d.at("to pick the encyclopedia pages that mention " + a.person());
                declined.put(a.person(), at); store.circulate("declined", "leads: " + a.person() + " :: " + at.statement());
                continue;
            }
            out.add(titles.isEmpty() ? a : a.withQuestion(a.question() + " Encyclopedia pages that mention this person, as leads to what their own page does not tell "
                    + "(open the ones this person had a part in, find that part, and note it with the page): " + String.join("; ", titles) + "."));
        }
        return out;
    }

    /**
     * The facts about a person that rest on a clue only (an account, a tree site, a web page), as short phrases: birth, death, marriage, parents. Up to five.
     * A fact counts once for all the claims that say it: a family account's birth that a record also gives is not asked about again.
     */
    static List<String> cluesOnly(Graph g, Map<String, Finding> findings, String id) { return cluesOnly(g, findings, id, FamilyPeople.byFact(g, findings), x -> false); }

    /** {@code mayName}: whether the other person of a marriage or a parent may be named in the question; a fact that would name somebody who may not is left out. */
    static List<String> cluesOnly(Graph g, Map<String, Finding> findings, String id, Map<String, Evidence> byFact, Predicate<String> mayName) {
        List<String> out = new ArrayList<>();
        for (Graph.Edge e : g.edges()) {
            if (!e.from().equals(id) || out.size() >= 5) continue;
            Finding f = findings.get(e.findingId());
            if (f == null || f.state() == Finding.State.retired || e.disputed() || Evidence.ofFact(f, byFact, FamilyPeople.factKey(e.from(), e.predicate(), e.to())) != Evidence.clue) continue;
            Graph.Node to = g.node(e.to());
            if (to == null || placeholder(to.label())) continue;
            if ((e.predicate().equals("married-to") || e.predicate().equals("child-of")) && !mayName.test(e.to())) continue;
            String phrase = switch (e.predicate()) {
                case "born-on" -> "born " + to.label(); case "born-in" -> "born in " + to.label();
                case "died-on" -> "died " + to.label(); case "died-in" -> "died in " + to.label();
                case "married-to" -> "married " + to.label(); case "child-of" -> "child of " + to.label();
                default -> null;
            };
            if (phrase != null && !out.contains(phrase)) out.add(phrase);
        }
        return out;
    }

    /** One question about a person that waits on a record somebody asked for (a 戸籍, a certificate, an archive's answer): what, why, since when. */
    public record Held(String person, String ask, String why, String date) { }

    private static final ObjectMapper M = new ObjectMapper();

    static Path heldFile(LibraryStore store) { return store.root().resolve("catalog").resolve("held-questions.json"); }

    /** Every held question in the library, in the order they were held. */
    public static List<Held> held(LibraryStore store) throws IOException {
        List<Held> out = new ArrayList<>();
        if (!Files.exists(heldFile(store))) return out;
        try {
            for (JsonNode o : M.readTree(Files.readString(heldFile(store), StandardCharsets.UTF_8)))
                out.add(new Held(o.path("person").asText(""), o.path("ask").asText(""), o.path("why").asText(""), o.path("date").asText("")));
        } catch (IOException e) { return out; }   // a damaged file holds nothing back: the questions are searched again
        return out;
    }

    public static List<Held> heldFor(List<Held> all, String person) { return all.stream().filter(h -> h.person().equals(person)).toList(); }

    /** Hold one of a person's questions until a record comes: kept out of the runs, listed under the records to request. Returns what was held. */
    public static Held hold(LibraryStore store, String person, String ask, String why) throws IOException {
        Held h = new Held(person, ask.strip(), why == null ? "" : why.strip().replaceAll("\\s+", " "), LocalDate.now().toString());
        store.locked("held-questions", () -> {
            List<Held> all = new ArrayList<>(held(store));
            all.removeIf(x -> x.person().equals(person) && sameAsk(x.ask(), h.ask()));
            all.add(h);
            write(store, all);
            return null;
        });
        return h;
    }

    /** Let a held question go back to the runs, for example when the record came. False when it was not held. */
    public static boolean release(LibraryStore store, String person, String ask) throws IOException {
        return store.locked("held-questions", () -> {
            List<Held> all = new ArrayList<>(held(store));
            boolean gone = all.removeIf(x -> x.person().equals(person) && sameAsk(x.ask(), ask));
            if (gone) write(store, all);
            return gone;
        });
    }

    private static void write(LibraryStore store, List<Held> all) throws IOException {
        ArrayNode a = M.createArrayNode();
        for (Held h : all) a.addObject().put("person", h.person()).put("ask", h.ask()).put("why", h.why()).put("date", h.date());
        Files.createDirectories(heldFile(store).getParent());
        Files.writeString(heldFile(store), M.writerWithDefaultPrettyPrinter().writeValueAsString(a), StandardCharsets.UTF_8);
    }

    /** Two questions are the same question when they read the same up to a colon: the list after it (the facts to confirm) changes as the library learns. */
    static boolean sameAsk(String a, String b) { return plain(a.split(":", 2)[0]).equals(plain(b.split(":", 2)[0])); }

    /** A person's questions as the research command writes them now, the held ones left out: the numbering "genealogy hold" goes by. Null for nobody in the tree. */
    public static List<String> questionsOf(LibraryStore store, String person) throws IOException {
        List<String> unknown = new ArrayList<>();
        List<Ask> one = aroundEach(store, List.of(person), 6, 3, true, true, unknown);
        if (!unknown.isEmpty()) return null;
        return one.isEmpty() ? List.of() : one.get(0).questions();
    }

    /**
     * The record sites the library cannot search itself, each with the address of a search for this person, filled from what the library
     * holds: the name split into given and family names, the years of birth and death, the birthplace. The person opens them; the library
     * sends nothing. Each is {site, what opening it asks, address}. Empty for a person who may be living, and a site whose search needs a
     * part of the name the library cannot tell is left out.
     */
    /** The date a claim gives in its own words, as the checks read it; null when it gives none or cannot be read. */
    private static FamilyDate claimDate(Graph g, String findingId) {
        try { Finding f = g.store().finding(findingId); return f == null ? null : FamilyChecks.claimDate(f); } catch (IOException e) { return null; }
    }

    /**
     * A person who carried more than one name gets one address for each name at each site, the site's name followed by the name and the
     * years it was carried ("FamilySearch, under the name 遠藤健二 (1905–1932)"): a record is indexed under the name the person had when it was
     * made. The family part of each name comes from its own claims.
     */
    public static List<String[]> siteLinks(Graph g, String person) {
        Graph.Node node = g.node(g.nodeIdOf(person));
        if (node == null || node.mayBeLiving()) return List.of();
        Map<String, String> v = new LinkedHashMap<>();
        List<Period> periods = periods(g, node.id());
        List<Period> names = periods.size() > 1 ? distinctNames(periods) : List.of();
        String[] split = names.isEmpty() ? givenFamily(g, node) : null;
        v.put("name", split == null ? "" : split[0] + " " + split[1]);
        if (split != null) { v.put("given", split[0]); v.put("family", split[1]); }
        for (Graph.Edge e : g.edges()) {
            if (!e.from().equals(node.id()) || e.disputed()) continue;
            Graph.Node to = g.node(e.to());
            if (to == null) continue;
            FamilyDate d = FamilyDate.parse(to.label());
            // a birth or a death filed with its place carries its year in the claim, as a tree file's BIRT with a DATE and a PLAC is filed
            FamilyDate inClaim = e.predicate().endsWith("-in") ? claimDate(g, e.findingId()) : null;
            switch (e.predicate()) {
                case "born-on" -> { if (d != null && d.year() > 0) v.putIfAbsent("born", String.valueOf(d.year())); }
                case "died-on" -> { if (d != null && d.year() > 0) v.putIfAbsent("died", String.valueOf(d.year())); }
                case "born-in" -> { v.putIfAbsent("place", to.label()); if (inClaim != null && inClaim.year() > 0) v.putIfAbsent("born", String.valueOf(inClaim.year())); }
                case "died-in" -> { if (inClaim != null && inClaim.year() > 0) v.putIfAbsent("died", String.valueOf(inClaim.year())); }
                default -> { }
            }
        }
        List<String[]> out = new ArrayList<>();
        for (RecordSources.SearchLink l : RecordSources.links()) {
            if (!l.fields().isEmpty() && !l.fields().contains("genealogy")) continue;
            if (names.isEmpty()) {
                String url = RecordSources.filled(l, v);
                if (url != null) out.add(new String[]{l.name(), l.accessText(), url});
                continue;
            }
            Set<String> made = new HashSet<>();
            for (Period p : names) {
                String[] parts = latinParts(g, node, p, names);
                Map<String, String> one = new LinkedHashMap<>(v);
                if (parts != null) { one.put("name", parts[0] + " " + parts[1]); one.put("given", parts[0]); one.put("family", parts[1]); }
                String url = RecordSources.filled(l, one);
                List<String> years = spans(periods, p);
                if (url != null && made.add(url)) out.add(new String[]{l.name() + ", under the name " + p.shown() + (years.isEmpty() ? "" : " (" + String.join(" and ", years) + ")"), l.accessText(), url});
            }
        }
        return out;
    }

    /**
     * {given names, family name} of one of a person's names, in Latin letters, as a record site's search asks for them; null when the library
     * cannot tell. The family part in Latin letters its claims give; else, in a form of several words, the word the person's other names do
     * not share (the given name is the same in both); else as a person's one name is split ({@link #givenFamily}).
     */
    static String[] latinParts(Graph g, Graph.Node node, Period p, List<Period> names) {
        // the forms in Latin letters a source wrote first, then those worked out from a kana reading
        List<String> latin = new ArrayList<>();
        for (String f : p.name().texts()) if (FamilyForms.script(f).equals("latin") && f.split("[\\s,]+").length >= 2) latin.add(f.strip());
        for (String f : p.forms()) if (FamilyForms.script(f).equals("latin") && f.split("[\\s,]+").length >= 2 && !latin.contains(f)) latin.add(f);
        String fam = p.name().family().strip();
        for (String t : latin) {
            int comma = t.indexOf(',');
            if (comma > 0 && !t.substring(comma + 1).isBlank()) return new String[]{t.substring(comma + 1).strip(), t.substring(0, comma).strip()};
            List<String> words = Arrays.stream(t.split("\\s+")).filter(w -> !w.isBlank()).toList();
            List<String> family = new ArrayList<>();
            if (FamilyForms.script(fam).equals("latin")) family.add(fam);
            for (Period other : names) if (other != p) family.addAll(familyWords(p, other));
            for (int i = 0; i < words.size(); i++) {
                String w = words.get(i);
                if (family.stream().noneMatch(f -> FamilyForms.script(f).equals("latin") && FamilyForms.latinKey(f).equals(FamilyForms.latinKey(w)))) continue;
                List<String> given = new ArrayList<>(words); given.remove(i);
                return new String[]{String.join(" ", given), w};
            }
        }
        if (latin.isEmpty()) return null;
        return givenFamily(g, new Graph.Node(node.id(), "person", latin.get(0), p.name().texts(), "", 0));
    }

    /**
     * {given names, family name} of a person, from a form of the name in Latin letters; null when the library cannot tell which word is the
     * family name. "Family, Given" says it; otherwise the family name is the word the name shares with a parent, a child, a husband or wife
     * or a brother or sister; and for a name the library holds only in Latin letters, the last word, as those names are mostly written.
     */
    static String[] givenFamily(Graph g, Graph.Node node) {
        List<String> names = new ArrayList<>(List.of(node.label())); names.addAll(node.aliases());
        boolean eastAsian = names.stream().anyMatch(n -> n.codePoints().anyMatch(c -> c >= 0x2E80));
        Set<String> kin = kinWords(g, node);
        for (String n : names) {
            // a title is no part of the name, and Jr. or Sr. after a comma is no index form: "Tom Hart, Jr." is Tom of the Hart family
            String bare = FamilyNames.withoutSuffix(FamilyNames.untitled(n.replaceAll("\\s*[(（][^)）]*[)）]\\s*", " ").strip()));
            if (bare.isEmpty() || bare.codePoints().anyMatch(c -> c >= 0x2E80) || placeholder(bare)) continue;
            String[] ix = FamilyNames.indexForm(bare);
            if (ix != null) return new String[]{ix[1], ix[0]};
            List<String> words = Arrays.stream(bare.replace(",", " ").split("\\s+")).filter(w -> !w.isBlank()).toList();
            if (words.size() < 2) continue;
            for (int i = words.size() - 1; i >= 0; i--) {
                if (!kin.contains(plain(words.get(i)))) continue;
                List<String> given = new ArrayList<>(words); given.remove(i);
                return new String[]{String.join(" ", given), words.get(i)};
            }
            if (!eastAsian) return new String[]{String.join(" ", words.subList(0, words.size() - 1)), words.get(words.size() - 1)};
        }
        return null;
    }

    /** The words of the names of a person's relatives, plain: a word a person's name shares with them is the family's. */
    private static Set<String> kinWords(Graph g, Graph.Node node) {
        Set<String> kin = new HashSet<>();
        for (Graph.Edge e : g.edges()) {
            if (!FamilyAccount.isKinship(e.predicate()) || !(e.from().equals(node.id()) || e.to().equals(node.id()))) continue;
            Graph.Node other = g.node(e.from().equals(node.id()) ? e.to() : e.from());
            if (other == null || placeholder(other.label())) continue;
            for (String n : Stream.concat(Stream.of(other.label()), other.aliases().stream()).toList()) kin.addAll(Arrays.asList(plain(n).split("[^\\p{L}\\p{N}]+")));
        }
        return kin;
    }

    /** The word of a name in letters that a relative's name carries too, the last such word; null when no relative's name carries one. */
    static String kinWord(Graph g, Graph.Node node, String name) {
        if (node == null || name == null) return null;
        Set<String> kin = kinWords(g, node);
        List<String> words = Arrays.stream(FamilyNames.untitled(name).replace(",", " ").split("\\s+")).filter(w -> !w.isBlank()).toList();
        for (int i = words.size() - 1; i >= 0; i--) if (kin.contains(plain(words.get(i)))) return words.get(i);
        return null;
    }

    /** The people who were named come first, in the order they were named; everybody else keeps their order after them. */
    public static List<Ask> namedFirst(LibraryStore store, List<Ask> asks, List<String> names) throws IOException {
        Graph g = FamilyPeople.view(store);
        List<Ask> first = new ArrayList<>(), rest = new ArrayList<>(asks);
        for (String name : names) {
            String id = g.node(g.nodeIdOf(name)) != null ? g.nodeIdOf(name) : g.nodeIdOf(KanjiForms.modern(name));
            String label = g.node(id) == null ? name : g.node(id).label();
            for (Ask a : asks) if (a.person().equals(label) && !first.contains(a)) { first.add(a); rest.remove(a); }
        }
        first.addAll(rest);
        return first;
    }

    /** A relative is written into a question by name, the living only when asked for. */
    private static boolean named(FamilyTree.Person p, boolean living) { return p != null && (living || !p.mayBeLiving()); }

    // a person's words after the 's, in small letters or in Japanese: "…'s step-mother", "…'s 父", "…'s おばあちゃん"; a name is written with capitals
    private static final Pattern RELATIVE_WORDS = Pattern.compile("['’]s\\s+[\\p{Ll}\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}][^\\p{Lu}]*$");

    /**
     * A label the reader made up for somebody the account does not name ("Mara's mother's father", "the writer of notes.txt", "the speaker in
     * community.pdf, part 12"): in the tree, never searched for.
     */
    static boolean placeholder(String label) {
        String l = label.toLowerCase(Locale.ROOT);
        return l.startsWith("the writer of ") || l.startsWith("the owner of this library") || l.startsWith("the person who ") || l.startsWith("the speaker in ")
                || l.matches(".*['’]s (great[- ]?)*(grand)?(mother|father|parent|wife|husband|spouse|son|daughter|child|brother|sister|uncle|aunt|cousin|nephew|niece)\\b.*")
                || !FamilyAccount.relationWords(label).isEmpty()   // "…'s maternal grandfather", "…'s parents", "…'s eldest son"
                || RELATIVE_WORDS.matcher(label).find()   // "…'s step-mother", "…'s 父": words after the 's, where a name has none
                || FamilyMentions.isMention(label);   // somebody the source wrote only by a family name, whatever the relation
    }

    // ── the names a person carried, for the searches ──────────────────────────────────────────────────────────────────

    /**
     * One name of a person as the searches use it ({@link FamilyNameHistory.Name}): the years its claims give it ({@code from} and
     * {@code to}, 0 where they do not say), and the written forms a search can use, the form in the script the person is filed under first.
     * A record of 1920 is written under the name the person carried in 1920, so that is the name its search needs.
     */
    public record Period(FamilyNameHistory.Name name, int from, int to, List<String> forms) {
        /** The name as the searches write it first. */
        public String shown() { return forms.isEmpty() ? name.written() : forms.get(0); }
        /** A name carried beside the others (a pen, religious or other name), which ends none of them. */
        public boolean alongside() { return !name.replaces(); }
        /** The years, as a line writes them: "1905–1932", "from 1932", "up to 1932"; "" when the claims give none. */
        public String years() { return from > 0 && to > 0 ? from + "–" + to : from > 0 ? "from " + from : to > 0 ? "up to " + to : ""; }
    }

    /** At most this many written forms of one name in a research question; the run is told the rest when no search has used them. */
    static final int FORMS_SHOWN = 5;

    /**
     * A person's names, each with its years and the forms a search can use, in the order they were carried ({@link
     * FamilyNameHistory.Index#names}). A name with no form a search could use (one word, a character nobody could read) is left out. Empty
     * for a node the graph does not have.
     */
    public static List<Period> periods(Graph g, String id) {
        Graph.Node node = g.node(id);
        if (node == null) return List.of();
        String script = FamilyForms.script(node.label());
        List<Period> out = new ArrayList<>();
        for (FamilyNameHistory.Name n : FamilyNameHistory.of(g).names(id)) {
            List<String> forms = searchForms(n, script);
            if (!forms.isEmpty()) out.add(new Period(n, n.from() == null ? 0 : n.from().year(), n.to() == null ? 0 : n.to().year(), forms));
        }
        return out;
    }

    /**
     * The written forms of one name a search can use ({@link FamilyForms#forms(FamilyNameHistory.Name)}): as written, in modern and old
     * characters, the kana reading a source gave, each romaji spelling. Both name orders only for a name also written in characters or kana:
     * in romaji such a name is written either way round, and a name the sources give only in Latin letters is searched in their order. The
     * form in {@code script} first; one word in Latin letters, a described person and a character nobody could read are no search of their
     * own, nor is a name in characters too short to be more than a family name or a given name alone: fewer than three characters, or fewer
     * than two when the name's claims give both its parts (勇勝, family name 勇 and given name 勝, is a whole name, as many Chinese names are).
     */
    static List<String> searchForms(FamilyNameHistory.Name n, String script) {
        int shortest = !n.family().isBlank() && !n.given().isBlank() ? 2 : 3;
        boolean inCharacters = n.texts().stream().anyMatch(t -> FamilyForms.script(t).equals("han") || FamilyForms.script(t).equals("kana"));
        List<String> all = new ArrayList<>(List.of(n.shown(script)));
        if (inCharacters) all.addAll(FamilyForms.forms(n));
        else for (String t : n.texts()) {
            String lower = t.toLowerCase(Locale.ROOT);
            all.addAll(Normalizer.normalize(lower, Normalizer.Form.NFKD).replaceAll("\\p{M}+", "").equals(lower) ? List.of(t) : FamilyForms.spellings(t));
        }
        // a name in another language's own script or letters (Hangul, Cyrillic, ü): the ways records of that language write it in Latin letters,
        // each tried, as a record uses one of them ({@link NameSpellings}). Japanese has its own above
        for (NameSpellings.Language l : NameSpellings.languagesOf(n.texts())) if (!l.code().equals("ja")) for (String t : n.texts()) all.addAll(l.spellings(t));
        List<String> out = new ArrayList<>();
        for (String raw : all) {
            String s = raw == null ? "" : raw.replaceAll("\\s*[(（][^)）]*[)）]\\s*", " ").strip();
            if (s.isEmpty() || placeholder(s) || FamilyNames.unreadable(s)) continue;
            boolean cjk = s.codePoints().anyMatch(c -> c >= 0x2E80);
            if (!cjk && s.split("[\\s,]+").length < 2) continue;
            if (cjk && s.replaceAll("[\\s　]+", "").codePointCount(0, s.replaceAll("[\\s　]+", "").length()) < shortest) continue;
            if (out.stream().noneMatch(o -> o.equalsIgnoreCase(s))) out.add(s);
        }
        return out;
    }

    /** A form as the research log compares it ({@link SearchLog#carries}): accents and old characters folded, the words in any order, a name in characters without its spaces. */
    private static String logKey(String form) {
        String p = SearchLog.plain(form);
        if (p.codePoints().anyMatch(c -> c >= 0x2E80)) return p.replaceAll("[\\s　]+", "");
        List<String> w = new ArrayList<>(Arrays.asList(p.split("[^\\p{L}\\p{N}]+")));
        w.removeIf(String::isBlank);
        Collections.sort(w);
        return String.join(" ", w);
    }

    /** Whether two periods are one name carried twice (a name taken back is the name at birth again). */
    private static boolean sameName(Period a, Period b) { return a.name().written().equals(b.name().written()) || FamilyForms.sameForm(a.name().written(), b.name().written()); }

    /** One period for each name, the first time it was carried; {@link #spans} gives every stretch of years it was carried. */
    static List<Period> distinctNames(List<Period> periods) {
        List<Period> out = new ArrayList<>();
        for (Period p : periods) if (out.stream().noneMatch(o -> sameName(o, p))) out.add(p);
        return out;
    }

    /** The years a name was carried, one entry for each time ("1850–1872", "from 1890"); empty when the claims give no years. */
    static List<String> spans(List<Period> periods, Period name) {
        List<String> out = new ArrayList<>();
        for (Period p : periods) if (sameName(p, name) && !p.years().isEmpty() && !out.contains(p.years())) out.add(p.years());
        return out;
    }

    /**
     * The changes of name: each name that takes the place of another, with the one before it, in the order they were carried. The record of
     * a change (a marriage, an adoption, a register's entry line) names both. A name carried beside the others changes nothing.
     */
    static List<Period[]> changes(List<Period> periods) {
        List<Period> replacing = periods.stream().filter(p -> !p.alongside()).toList();
        List<Period[]> out = new ArrayList<>();
        for (int i = 1; i < replacing.size(); i++) if (!sameName(replacing.get(i - 1), replacing.get(i))) out.add(new Period[]{replacing.get(i - 1), replacing.get(i)});
        return out;
    }

    /** A word as it is compared across the forms of two names: its romaji with the long vowels written one way, its kana, its modern characters. */
    private static String wordKey(String w) {
        String sc = FamilyForms.script(w);
        return sc.equals("latin") ? FamilyForms.latinKey(w) : sc.equals("kana") ? FamilyForms.kanaKey(w) : FamilyForms.hanKey(w);
    }

    /**
     * The family part of the later name of a change, in each script its forms are written in: the family part its claims give, and in a form
     * of several words the one word the earlier name does not have, since the given name is the same in both (Morita Kenji beside Endō
     * Kenji gives Morita). Empty when neither says.
     */
    static List<String> familyWords(Period later, Period earlier) {
        List<String> out = new ArrayList<>();
        String fam = later.name().family().strip();
        if (!fam.isEmpty()) out.add(fam);
        Set<String> had = new HashSet<>();
        for (String f : earlier.forms()) for (String w : f.split("[\\s,　]+")) if (!w.isBlank()) had.add(wordKey(w));
        for (String f : later.forms()) {
            List<String> words = Arrays.stream(f.split("[\\s,　]+")).filter(w -> !w.isBlank()).toList();
            if (words.size() < 2) continue;
            List<String> fresh = words.stream().filter(w -> !had.contains(wordKey(w))).toList();
            if (fresh.size() == 1 && out.stream().noneMatch(o -> wordKey(o).equals(wordKey(fresh.get(0))))) out.add(fresh.get(0));
        }
        return out;
    }

    /** Whether a query, as {@link SearchLog#plain} writes it, carries one of the written forms of a name. */
    static boolean carriesName(String query, Period p) {
        for (String f : p.forms()) if (SearchLog.carries(query, SearchLog.plain(f))) return true;
        return false;
    }

    /** Whether a query carries both names of a change: a form of each, or a form of one with the family part of the other. */
    static boolean together(String query, Period earlier, Period later) {
        boolean a = carriesName(query, earlier), b = carriesName(query, later);
        if (a && b) return true;
        if (a) for (String w : familyWords(later, earlier)) if (SearchLog.carries(query, SearchLog.plain(w))) return true;
        if (b) for (String w : familyWords(earlier, later)) if (SearchLog.carries(query, SearchLog.plain(w))) return true;
        return false;
    }

    /**
     * How to search for the record of one change, in words a run can use: the earlier name in each script together with the later family
     * part in that script, and with the written forms of the family the person entered then, when the library holds that family and the
     * membership ("遠藤健二 together with 森田 or 森田家, and Endō Kenji together with Morita").
     */
    static String togetherText(Graph g, String id, Period earlier, Period later) {
        List<String> with = new ArrayList<>(familyWords(later, earlier));
        // the family the person entered, as the sources write it (森田家, the Morita family): the record of the entry is kept under it
        if (g != null && id != null) for (FamilyHouses.Membership m : FamilyHouses.families(g, id)) {
            String name = FamilyHouses.nameOf(FamilyHouses.labelOf(g, m.family()));
            if (with.stream().noneMatch(w -> FamilyForms.sameForm(w, name))) continue;
            // words that name a family only beside a family name ("the Hales", "the house of Hale") count here, where the name is the family's
            for (String a : FamilyHouses.aliasesOf(g, m.family())) if (FamilyHouses.familyWord(a, List.of(name)) && with.stream().noneMatch(a::equals)) with.add(a);
        }
        List<String> pairs = new ArrayList<>();
        // the script the name is written in first, then romaji for the sources in other languages, then any other
        Set<String> scripts = new LinkedHashSet<>(List.of(FamilyForms.script(earlier.shown()), "latin"));
        for (String f : earlier.forms()) scripts.add(FamilyForms.script(f));
        for (String sc : scripts) {
            List<String> words = with.stream().filter(w -> FamilyForms.script(w).equals(sc)).toList();
            String form = earlier.forms().stream().filter(f -> FamilyForms.script(f).equals(sc)).findFirst().orElse("");
            if (words.isEmpty() || form.isEmpty() || pairs.size() == 2) continue;
            pairs.add(form + " together with " + String.join(" or ", words));
        }
        return pairs.isEmpty() ? earlier.shown() + " and " + later.shown() + " together" : String.join(", and ", pairs);
    }

    /** "before 1932", "from 1932", "from 1872 to 1890": the years of the records a name is the search for; "" when the claims give none. */
    private static String recordsOf(Period p) {
        if (p.from() > 0 && p.to() > 0 && !p.name().kind().equals("birth")) return "from " + p.from() + " to " + p.to();
        if (p.to() > 0) return "before " + p.to();
        return p.from() > 0 ? "from " + p.from() : "";
    }

    /** A name with its other forms in brackets, as many as a question shows: "遠藤健二 (えんどう けんじ, Endō Kenji)". */
    private static String withForms(Period p) {
        List<String> more = p.forms().subList(1, Math.min(p.forms().size(), FORMS_SHOWN + 1));
        return p.shown() + (more.isEmpty() ? "" : " (" + String.join(", ", more) + ")");
    }

    /** "A, B and C". */
    private static String listed(List<String> items) {
        return items.size() <= 1 ? String.join("", items) : String.join(", ", items.subList(0, items.size() - 1)) + " and " + items.get(items.size() - 1);
    }

    /**
     * What a person's facts in a research question say of their names, one line for each name: which name at birth and until when, which
     * later name from when and how it came, a name carried beside them, each with the other forms the sources wrote it in (the spellings
     * worked out from them are in the question that asks for the searches). Empty for a person with one name.
     */
    static List<String> nameFacts(List<Period> periods) {
        if (periods.size() < 2) return List.of();
        List<String> out = new ArrayList<>();
        for (Period p : periods) {
            FamilyNameHistory.Name n = p.name();
            String how = FamilyNameHistory.phrase(n.kind());
            if (!n.family().isBlank()) how = how.replace("the family", "the " + n.family() + " family");
            List<String> written = new ArrayList<>();
            for (String t : n.texts()) if (p.forms().contains(t) && !t.equals(p.shown()) && written.size() < FORMS_SHOWN) written.add(t);
            String also = written.isEmpty() ? "" : ", also written " + String.join(", ", written);
            String line;
            if (p.alongside()) line = "also known as " + p.shown() + (p.from() > 0 ? " from " + p.from() : "") + (how.isEmpty() ? "" : ", " + how);
            else if (n.kind().equals("birth")) line = "named " + p.shown() + " at birth" + (p.to() > 0 ? ", until " + p.to() : "");
            else line = "named " + p.shown() + (p.from() > 0 ? " from " + p.from() : "") + (p.to() > 0 ? " until " + p.to() : "")
                    + (how.isEmpty() ? (p.from() > 0 ? ", how is not known yet" : ", how and when are not known yet") : ", " + how)
                    + (n.workedOut() ? ", " + n.workedOutWords() : "");
            out.add(line + also);
        }
        return out;
    }

    /**
     * The question a research run is given about a person who carried more than one name, before anybody searched for them: the records of
     * each period under the name carried then, with its forms, and the record of each change under both names. Null for one name.
     */
    static String searchPeriods(Graph g, String id, String person, List<Period> periods) {
        if (periods.size() < 2) return null;
        List<String> under = new ArrayList<>();
        for (Period p : distinctNames(periods)) {
            if (p.alongside()) { under.add("under " + withForms(p) + ", " + (p.name().kind().equals("aka") ? "a name the person was also known by" : p.name().kindWords())); continue; }
            List<String> when = new ArrayList<>();
            for (Period q : periods) if (sameName(q, p) && !recordsOf(q).isEmpty() && !when.contains(recordsOf(q))) when.add(recordsOf(q));
            under.add((when.isEmpty() ? "" : String.join(" and ", when) + " ") + "under " + withForms(p) + (when.isEmpty() ? ", in years not known yet" : ""));
        }
        String each = under.size() == 1 ? under.get(0) : String.join("; ", under.subList(0, under.size() - 1)) + "; and " + under.get(under.size() - 1);
        StringBuilder b = new StringBuilder("Search the records of ").append(person).append(" under the name carried at the time of each: ").append(each).append(".");
        for (Period[] c : changes(periods))
            b.append(" The record of the change from ").append(c[0].shown()).append(" to ").append(c[1].shown()).append(" names both: search ").append(togetherText(g, id, c[0], c[1])).append(".");
        return b.toString();
    }

    /**
     * The searches the log holds for a person, against the names the person carried: the names no answered search used, with the years to
     * search them for, and each change no search carried both names of. Null when nothing was searched yet, the person has one name, or
     * every name and every change has been searched. {@code g} and {@code id} give the family the person entered, for the search of a change.
     */
    static String unsearchedPeriods(List<SearchLog.Entry> log, String person, List<Period> periods, Graph g, String id) {
        if (periods.size() < 2) return null;
        String lead = Looked.lead(person + ":");
        List<SearchLog.Entry> mine = log.stream().filter(e -> !e.failed() && Looked.lead(e.about()).equals(lead)).toList();
        if (mine.isEmpty()) return null;
        List<String> queries = mine.stream().map(e -> SearchLog.plain(e.query())).toList();
        List<Period> searched = new ArrayList<>(), not = new ArrayList<>();
        for (Period p : distinctNames(periods)) (queries.stream().anyMatch(q -> carriesName(q, p)) ? searched : not).add(p);
        List<Period[]> apart = changes(periods).stream().filter(c -> queries.stream().noneMatch(q -> together(q, c[0], c[1]))).toList();
        if (not.isEmpty() && apart.isEmpty()) return null;
        List<String> where = new ArrayList<>();
        for (SearchLog.Entry e : mine) if (!e.where().isBlank() && !where.contains(e.where()) && where.size() < 4) where.add(e.where());
        StringBuilder b = new StringBuilder();
        if (!not.isEmpty()) {
            List<String> had = searched.stream().map(p -> p.shown() + (spans(periods, p).isEmpty() ? "" : " (" + String.join(" and ", spans(periods, p)) + ")")).toList();
            b.append(searched.isEmpty() ? "No search for " + person + " has used any of the names the person carried yet."
                    : person + " has been searched for only under the name" + (had.size() == 1 ? " " : "s ") + listed(had) + ".");
            List<String> under = new ArrayList<>();
            for (Period p : not) {
                List<String> when = new ArrayList<>();
                for (Period q : periods) if (sameName(q, p) && !recordsOf(q).isEmpty() && !when.contains(recordsOf(q))) when.add(recordsOf(q));
                under.add(withForms(p) + (when.isEmpty() ? "" : " for the records " + String.join(" and ", when)));
            }
            b.append(" Search for ").append(person).append(" under ").append(listed(under)).append(searched.isEmpty() ? "" : " as well")
                    .append(where.isEmpty() ? "" : ", in the places already searched (" + String.join(", ", where) + ")").append(".");
        }
        for (Period[] c : apart)
            b.append(" No search has carried ").append(c[0].shown()).append(" and ").append(c[1].shown()).append(" together: the record of the change names both, so search ").append(togetherText(g, id, c[0], c[1])).append(".");
        return b.toString().strip();
    }

    /**
     * Which of the person's names a search carried, for a line of the research log: "[name: 遠藤健二, 1905–1932]", "[both names]" for a query
     * that carried both names of a change, "[none of the names]"; "" for a person with one name.
     */
    public static String carriedName(List<Period> periods, String query) {
        if (periods.size() < 2) return "";
        String q = SearchLog.plain(query);
        List<Period> names = distinctNames(periods);
        List<Period> carried = names.stream().filter(p -> carriesName(q, p)).toList();
        if (carried.size() >= 2) return names.size() == 2 ? "  [both names]" : "  [names: " + listed(carried.stream().map(Period::shown).toList()) + "]";
        // one name in full and the family part of the other: the search for the record of the change
        Period[] change = changes(periods).stream().filter(c -> together(q, c[0], c[1])).findFirst().orElse(null);
        if (change != null) return names.size() == 2 ? "  [both names]" : "  [names: " + change[0].shown() + " and " + change[1].shown() + "]";
        if (carried.isEmpty()) return "  [none of the names]";
        Period p = carried.get(0);
        List<String> years = spans(periods, p);
        return "  [name: " + p.shown() + (years.isEmpty() ? "" : ", " + String.join(" and ", years)) + "]";
    }

    /** A described person who stands for a parent or a spouse the record says it does not know: "森田勇's father (unknown: 父 不詳)". */
    static boolean unknown(String label) { return label != null && label.matches("(?s).*['’]s [^()]*\\(unknown: .*\\)$") && placeholder(label); }

    /**
     * The questions a record's own "unknown" asks: whose father the record does not name, in its words, and where a genealogist looks
     * next. Empty when no record says a parent or a spouse of the person is unknown.
     */
    static List<String> unknownAsks(Graph g, String id, String label) {
        List<String> out = new ArrayList<>();
        for (Graph.Edge e : g.edges()) {
            if (e.disputed() || (!e.from().equals(id) && !e.to().equals(id))) continue;
            Graph.Node other = g.node(e.from().equals(id) ? e.to() : e.from());
            if (other == null || !unknown(other.label())) continue;
            Matcher m = Pattern.compile("['’]s ([^()]*?) \\(unknown: (.*)\\)$").matcher(other.label());
            if (!m.find()) continue;
            String role = m.group(1), words = m.group(2);
            String where = switch (role) {
                case "father" -> "a later record that names him: an acknowledgement or a legitimation of the child, the mother's marriage, or the mother's own family records";
                case "mother" -> "a later record that names her: the father's family records, an adoption, or the register of the place the child was born";
                case "husband or wife" -> "a record of the marriage itself, or of the children, which names both parents";
                default -> "a later record that names the parent: an acknowledgement or a legitimation of the child, a marriage, or the family records of the parent who is known";
            };
            String q = "A record gives " + label + "'s " + role + " as unknown (\"" + words + "\"). Who was " + label + "'s " + role + "? Look for " + where + ".";
            if (!out.contains(q)) out.add(q);
        }
        return out;
    }

    /** Letters without their accents, in lower case: Endō is found by Endo, and Ōta by Ota. */
    static String plain(String s) { return Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFKD).replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip(); }

    /**
     * Computed, for a family-history run about a person in the family: the written forms of the name no answered search used, whether any
     * search was held to the years of the life, and the record collections for genealogy never searched for this person. "" when the
     * question is about nobody in the tree, or nothing is left out. What genealogy adds to what its runs are told ({@link Profile#knownBlock}).
     */
    public static String neverSearched(LibraryStore store, String question, List<SearchLog.Entry> answered) throws IOException {
        if (answered.isEmpty()) return "";
        String lead = Looked.lead(question);
        Graph g = FamilyPeople.view(store);
        Graph.Node person = null;
        for (Graph.Node n : g.nodes()) if (n.kind().equals("person") && Looked.lead(n.label()).equals(lead)) { person = n; break; }
        if (person == null) return "";
        List<String> queries = answered.stream().map(e -> SearchLog.plain(e.query())).toList();
        List<String> parts = new ArrayList<>();
        List<Period> periods = periods(g, person.id());
        if (periods.size() > 1) parts.addAll(periodsNeverSearched(g, person.id(), periods, answered));
        else {
            List<String> forms = new ArrayList<>();
            for (String f : FamilyIdentity.forms(person)) if (queries.stream().noneMatch(q -> SearchLog.carries(q, SearchLog.plain(f)))) forms.add(f);
            if (!forms.isEmpty()) parts.add("written forms of the name never searched: " + String.join(", ", forms));
        }
        int[] lived = RecordSources.lived(question);
        if (lived != null && answered.stream().noneMatch(e -> e.fromYear() > 0 || e.toYear() > 0))
            parts.add("no search so far was held to the years of this life (born " + FamilyDate.bornOf(question).phrase() + "): give from_year and to_year to a collection with dates");
        Set<String> where = new HashSet<>();
        for (SearchLog.Entry e : answered) where.add(e.where().toLowerCase(Locale.ROOT));
        List<String> collections = new ArrayList<>();
        for (RecordSource s : RecordSources.forYears(RecordSources.forFields(RecordSources.ordered(List.of()), List.of("genealogy")), lived))
            if (s.holdsTheRecord() && !where.contains(s.id().toLowerCase(Locale.ROOT))) collections.add(s.id() + " (" + s.name() + ")");
        if (!collections.isEmpty()) parts.add("record collections never searched for this person (search the ones whose country and kind of record fit the family): " + String.join(", ", collections.subList(0, Math.min(12, collections.size()))) + (collections.size() > 12 ? " and " + (collections.size() - 12) + " more" : ""));
        if (parts.isEmpty()) return "";
        return "NOT YET SEARCHED FOR THIS PERSON (worked out by the library from the log above and the family's facts):\n- " + String.join("\n- ", parts) + "\n";
    }

    /**
     * For a person who carried more than one name, what the answered searches left out, name by name: the written forms of each name no
     * search used, each stretch of years no search was held to under the name carried then, and each change no search carried both names of.
     */
    static List<String> periodsNeverSearched(Graph g, String id, List<Period> periods, List<SearchLog.Entry> answered) {
        List<String> queries = answered.stream().map(e -> SearchLog.plain(e.query())).toList();
        List<String> out = new ArrayList<>();
        for (Period p : distinctNames(periods)) {
            Map<String, String> byKey = new LinkedHashMap<>();
            for (String f : p.forms()) byKey.putIfAbsent(logKey(f), f);
            List<String> never = new ArrayList<>();
            for (String f : byKey.values()) if (queries.stream().noneMatch(q -> SearchLog.carries(q, SearchLog.plain(f)))) never.add(f);
            List<String> years = spans(periods, p);
            String name = p.shown() + (years.isEmpty() ? "" : ", carried " + String.join(" and ", years) + ",");
            if (!never.isEmpty()) out.add("written forms of the name " + name + " never searched: " + String.join(", ", never));
            // each stretch of years the name was carried: a search held to those years under that name is how its records are found
            for (Period q : periods) {
                if (!sameName(q, p) || q.alongside() || (q.from() == 0 && q.to() == 0)) continue;
                int lo = q.from() > 0 ? q.from() : Integer.MIN_VALUE, hi = q.to() > 0 ? q.to() : Integer.MAX_VALUE;
                boolean held = answered.stream().anyMatch(e -> (e.fromYear() > 0 || e.toYear() > 0) && carriesName(SearchLog.plain(e.query()), q)
                        && (e.fromYear() <= 0 || e.fromYear() <= hi) && (e.toYear() <= 0 || e.toYear() >= lo));
                String text = "no search was held to the years " + q.years() + " with the name " + q.shown() + ": give from_year and to_year to a collection with dates";
                if (!held && !out.contains(text)) out.add(text);
            }
        }
        for (Period[] c : changes(periods))
            if (queries.stream().noneMatch(q -> together(q, c[0], c[1])))
                out.add("no search carried " + c[0].shown() + " and " + c[1].shown() + " together: the record of the change names both, so search " + togetherText(g, id, c[0], c[1]));
        return out;
    }

    /**
     * A typed name as the library holds the person: {@code person} is the name the library writes them with, or null when it finds nobody
     * or several ({@code ambiguous}, with the names in {@code could}); {@code could} otherwise holds names that share a word with it.
     * {@code note} is the sentence a command says when the library chose: the name is written another way in the library, or it is two
     * entries and the one the facts are about was taken, or it is several entries that facts are about and the person picks. {@code also}:
     * the other entries of the same name written another way that facts are about, beside the person found. "" and empty when there is
     * nothing to say.
     */
    public record Found(String person, List<String> could, boolean ambiguous, String note, List<String> also) {
        public Found(String person, List<String> could, boolean ambiguous, String note) { this(person, could, ambiguous, note, List.of()); }
        public boolean found() { return person != null; }
    }

    /**
     * The person a typed name means. A name as the library writes it, one of the person's other names, or its modern form ({@link
     * KanjiForms}) is that person whenever a fact is about that entry, even when the same name written with or without spaces between its
     * characters ({@link FamilyNames#written}) is another entry: a grandfather 山田 太郎 and his grandson 山田太郎 are two people. An entry no
     * fact is about leads to the one entry of its name that facts are about, and the note says so; when facts are about several such
     * entries, the name is several people and the person picks ({@code ambiguous}). Entries the family said are two people ({@link
     * Graph#differentPairs}) never lead to each other. Then the people whose name is the same apart from accents and capitals; and when
     * there are none, the people who share a word with it.
     */
    public static Found find(LibraryStore store, String typed) throws IOException { return find(FamilyPeople.view(store), typed, Graph.differentPairs(store)); }

    static Found find(Graph g, String typed) { return find(g, typed, Set.of()); }

    public static Found find(Graph g, String typed, Set<String> apart) {
        String t = typed == null ? "" : typed.strip();
        Graph.Node exact = null;
        for (String form : new String[]{t, KanjiForms.modern(t)}) { Graph.Node n = g.node(g.nodeIdOf(form)); if (n != null) { exact = n; break; } }
        // an entry of the list of names that the typed name finds and that no fact is about: the person is not there, but the entry is
        String empty = null, emptyId = null;
        for (String form : new String[]{t, KanjiForms.modern(t)}) {
            String s = g.curated().resolve(form);
            if (s != null && g.node(g.nodeIdOf(s)) == null) { empty = labelOf(g.curated().get(s), s); emptyId = g.nodeIdOf(s); break; }
        }
        String here = exact != null ? exact.id() : emptyId;   // the entry the typed name is itself, when it is one
        // every other person the name is, written with or without spaces between its characters, in either form of the characters,
        // leaving out the ones the family said are somebody else
        String key = FamilyNames.written(t);
        List<Graph.Node> alike = new ArrayList<>();
        for (Graph.Node n : g.nodes()) {
            if (!n.kind().equals("person") || placeholder(n.label()) || n.id().equals(here)) continue;
            if (here != null && apart.contains(Graph.pair(here, n.id()))) continue;
            List<String> forms = new ArrayList<>(List.of(n.label())); forms.addAll(n.aliases());
            if (!key.isEmpty() && forms.stream().anyMatch(f -> FamilyNames.written(f).equals(key))) alike.add(n);
        }
        List<Graph.Node> withFacts = new ArrayList<>(alike.stream().filter(n -> facts(g, n.id()) > 0).toList());
        withFacts.sort(Comparator.comparingInt((Graph.Node n) -> -facts(g, n.id())));
        // the entry typed, when a fact is about it: that is the person, whatever else is written like it
        if (exact != null && facts(g, exact.id()) > 0) {
            if (withFacts.isEmpty()) return new Found(exact.label(), List.of(), false, aloneAsked(g, exact) ? noGivenName(exact.label()) : otherName(g, t, exact));
            List<String> others = withFacts.stream().map(Graph.Node::label).toList();
            List<String> said = new ArrayList<>();
            for (Graph.Node n : withFacts) said.add("\"" + n.label() + "\", which " + factsSaid(facts(g, n.id())) + " about" + life(g, n.id()));
            String listed = said.size() == 1 ? said.get(0) : String.join(", ", said.subList(0, said.size() - 1)) + " and " + said.get(said.size() - 1);
            String one = others.get(0);
            return new Found(exact.label(), List.of(), false, "The library takes \"" + exact.label() + "\"" + life(g, exact.id()) + ", written as you typed it. Your library also has " + listed
                    + ": the same name written another way. If they are one person, the command researchzosho graph merge " + FamilyNamePages.shellQuoted(one) + " " + FamilyNamePages.shellQuoted(exact.label()) + " --because \"<what shows they are one>\" joins them. "
                    + "If they are two people, the command researchzosho genealogy different " + FamilyNamePages.shellQuoted(exact.label()) + " " + FamilyNamePages.shellQuoted(one) + " --because \"<what shows they are two>\" writes that down, and this is not said again.", others);
        }
        // the typed name leads to one entry that facts are about
        if (withFacts.size() == 1) {
            Graph.Node take = withFacts.get(0);
            String none = exact != null ? exact.label() : empty;
            if (none == null) return new Found(take.label(), List.of(), false, writtenAs(g, t, take));
            return new Found(take.label(), List.of(), false, "Your library has two entries for this name, written two ways: \"" + none + "\", which no fact is about, and \"" + take.label() + "\", which "
                    + factsSaid(facts(g, take.id())) + " about. The library takes \"" + take.label() + "\", the one the facts are about. The command researchzosho genealogy tidy offers to join the two.");
        }
        // facts are about several entries of this name: they may be several people, and the person picks
        if (withFacts.size() > 1) {
            List<String> said = new ArrayList<>();
            for (Graph.Node n : withFacts) said.add("\"" + n.label() + "\", which " + factsSaid(facts(g, n.id())) + " about" + life(g, n.id()));
            String listed = String.join(", ", said.subList(0, said.size() - 1)) + " and " + said.get(said.size() - 1);
            return new Found(null, withFacts.stream().map(Graph.Node::label).toList(), true, "Your library has " + (said.size() == 2 ? "two entries" : said.size() + " entries") + " for \"" + t
                    + "\", written in different ways, and facts are about each of them: " + listed + ". They may be different people, so the library does not choose. "
                    + "Give the command again with the name of the one you mean, written exactly as it is here.");
        }
        // no entry of this name has a fact
        if (exact != null) return new Found(exact.label(), List.of(), false, "");
        if (alike.size() == 1) return new Found(alike.get(0).label(), List.of(), false, writtenAs(g, t, alike.get(0)));
        if (alike.size() > 1) return new Found(null, alike.stream().map(Graph.Node::label).toList(), true, "");
        List<String> same = new ArrayList<>(), near = new ArrayList<>(), aloneNear = new ArrayList<>();
        String want = plain(t);
        Set<String> words = new HashSet<>(Arrays.asList(want.split(" ")));
        Graph.Node sameNode = null;
        for (Graph.Node n : g.nodes()) {
            if (!n.kind().equals("person") || placeholder(n.label())) continue;
            List<String> forms = new ArrayList<>(List.of(n.label())); forms.addAll(n.aliases());
            if (forms.stream().anyMatch(f -> plain(f).equals(want))) { same.add(n.label()); sameNode = n; continue; }
            if (forms.stream().noneMatch(f -> Arrays.stream(plain(f).split(" ")).anyMatch(w -> w.length() >= 3 && words.contains(w)))) continue;
            // an entry that is a family name alone ("Hart", which a source wrote as "the Harts") is nobody in particular, so it is not offered
            // as a similar person: the note says what it is, and the questions about names ask who. A relative known by one given name is offered
            if (FamilyNameQuestions.familyNameAlone(g, n) != null) { if (FamilyNameQuestions.aloneAsked(g, n.id())) aloneNear.add(n.label()); }
            else near.add(n.label());
        }
        if (same.size() == 1) return new Found(same.get(0), List.of(), false, writtenAs(g, t, sameNode));
        if (same.size() > 1) return new Found(null, same, true, "");
        return new Found(null, near, false, String.join(" ", aloneNear.stream().map(FamilyQuestions::noGivenName).toList()));
    }

    /** Whether an entry is a family name alone that the questions about names still ask about: once the family answered, it is what they said. */
    private static boolean aloneAsked(Graph g, Graph.Node n) { return FamilyNameQuestions.familyNameAlone(g, n) != null && FamilyNameQuestions.aloneAsked(g, n.id()); }

    /** What a command says of an entry that is a family name alone: it is a person with no given name, and the questions about names ask who it is. */
    static String noGivenName(String label) {
        return "\"" + label + "\" is written in your library as a person with no given name. " + label + " is a family name there, and the questions about names ask who that is: researchzosho genealogy who";
    }

    /** " (born 1850, died 1910)" from the dates the facts give a person, so two people of one name can be told apart; "" without dates. */
    private static String life(Graph g, String id) {
        String born = "", died = "";
        for (Graph.Edge e : g.edges()) {
            if (!e.from().equals(id) || e.disputed()) continue;
            Graph.Node to = g.node(e.to());
            if (to == null) continue;
            if (e.predicate().equals("born-on") && born.isEmpty()) born = to.label();
            if (e.predicate().equals("died-on") && died.isEmpty()) died = to.label();
        }
        if (born.isEmpty() && died.isEmpty()) return "";
        return " (" + (born.isEmpty() ? "" : "born " + born) + (born.isEmpty() || died.isEmpty() ? "" : ", ") + (died.isEmpty() ? "" : "died " + died) + ")";
    }

    /**
     * What a command says when the typed name is written otherwise in the library; "" when it is written the same. When the typed name is
     * one of the person's other names over a life, the sentence says which: "遠藤健二 is the birth name of 森田健二 (born 遠藤) in your library".
     */
    private static String writtenAs(Graph g, String typed, Graph.Node node) {
        String label = node.label();
        if (label.equals(typed)) return "";
        String other = otherName(g, typed, node);
        return !other.isEmpty() ? other : "\"" + typed + "\" is written \"" + label + "\" in your library, so that is the person the library takes.";
    }

    /** The sentence for a typed name that is one of the person's other names over a life, which it names; "" for any other typed name. */
    private static String otherName(Graph g, String typed, Graph.Node node) {
        String label = node.label();
        if (label.equals(typed)) return "";
        FamilyNameHistory.Index idx = FamilyNameHistory.of(g);
        FamilyNameHistory.Name latest = idx.latest(node.id());
        for (FamilyNameHistory.Name n : idx.names(node.id())) {
            if (!n.isForm(typed) || n == latest || (latest != null && latest.isForm(typed))) continue;
            String which = switch (n.kind()) {
                case "birth" -> "the birth name";
                case "marriage" -> "the name taken at marriage";
                case "adoptive" -> "the name taken on adoption";
                case "mukoyoshi" -> "the name taken on entering the family as 婿養子";
                case "nyufu" -> "the name taken on entering the family by 入夫 marriage";
                case "succession" -> "the name taken on succeeding as head";
                case "legal" -> "the name taken by a legal change";
                case "taken-back" -> "the name taken back";
                case "religious" -> "a religious or posthumous name";
                case "art" -> "an art, pen or professional name";
                case "aka" -> "another name";
                default -> "another name over the life";
            };
            return "\"" + typed + "\" is " + which + " of " + idx.heading(node.id()) + " in your library, written \"" + label + "\", so that is the person the library takes.";
        }
        return "";
    }

    /** The label an entry of nodes.md gives, else its slug. */
    private static String labelOf(Vocabulary.Term t, String slug) {
        return t != null && t.description().contains(":") ? t.description().substring(t.description().indexOf(':') + 1).strip() : slug;
    }

    /** How many claims are about a person: the claims of the edges that touch them, a shelf label and a mention aside. */
    static int facts(Graph g, String id) {
        Set<String> ids = new HashSet<>();
        for (Graph.Edge e : g.edges()) if ((e.from().equals(id) || e.to().equals(id)) && !e.predicate().equals("is filed under") && !e.predicate().equals("mentions")) ids.add(e.findingId());
        return ids.size();
    }

    /** "no fact is", "1 fact is", "4 facts are". */
    public static String factsSaid(int n) { return n == 0 ? "no fact is" : n == 1 ? "1 fact is" : n + " facts are"; }

    /**
     * The people a typed name may mean, as the library writes them ({@link #find}). One person gives that name. Otherwise the people
     * whose name is the same apart from accents and capitals; and when there are none, the people who share a word with it.
     * {@code exact[0]} says whether the first kind was found.
     */
    public static List<String> meant(LibraryStore store, String typed, boolean[] exact) throws IOException {
        Found f = find(store, typed);
        if (f.found()) { exact[0] = true; return List.of(f.person()); }
        exact[0] = f.ambiguous();
        return f.could();
    }

    /**
     * One research about a person: the question it was made with, its code (a run's J-…, or what the waiting list says the question became),
     * its state (queued, running, offered, done, failed, stopped; explored for a question the nightly research took), and when: the day
     * it ended, or 9999 for a run still going, which is always the latest.
     */
    public record Run(String question, String code, String state, String when) {
        /** The day, as a sentence gives it: "2026-09-24"; "" for a run still going. */
        public String day() { return when.startsWith("9999") ? "" : FamilyQuestions.day(when); }
        /** A run that ended without finishing: a person stopped it, or it failed. */
        public boolean unfinished() { return state.equals("stopped") || state.equals("failed"); }
    }

    /** Every research the library made or is making: the runs queued, running and finished, and the questions the nightly research took. */
    public static List<Run> runs(LibraryStore store) throws IOException {
        List<Run> out = new ArrayList<>();
        Jobs jobs = new Jobs(store, j -> { throw new IllegalStateException("read only"); });
        for (var j : jobs.active()) out.add(new Run(j.path("args").path("question").asText(""), j.path("job_id").asText(), j.path("state").asText(), "9999"));
        for (var j : jobs.recent(2000, null)) out.add(new Run(j.path("args").path("question").asText(""), j.path("job_id").asText(), j.path("state").asText(), j.path("ended_at").asText(j.path("queued_at").asText(""))));
        for (Frontier.Line l : Frontier.read(store)) {
            if (l.open()) continue;
            String[] e = l.explored().strip().split("\\s+", 2);
            Matcher code = Pattern.compile("\\b([JI]-\\d{4}[\\w-]*)").matcher(e.length > 1 ? e[1] : "");
            out.add(new Run(l.text(), code.find() ? code.group(1) : "", "explored", e[0]));
        }
        return out;
    }

    /** The latest research about this person ({@link #about}), or null when there is none. */
    public static Run latest(List<Run> runs, String person) {
        Run best = null;
        for (Run r : runs) if (about(r.question(), person) && (best == null || r.when().compareTo(best.when()) > 0)) best = r;
        return best;
    }

    static String day(String instant) { return instant == null || instant.length() < 10 ? "" : instant.substring(0, 10); }

    /** The questions that are waiting their turn, by the text they were filed with. */
    public static List<String> waiting(LibraryStore store) throws IOException {
        List<String> out = new ArrayList<>();
        for (Frontier.Line l : Frontier.read(store)) if (l.open() && !l.parked()) out.add(l.text());
        return out;
    }

    /** The open questions that are parked: kept, and not taken by the nightly research until somebody puts them back in the queue. */
    public static List<String> parked(LibraryStore store) throws IOException {
        List<String> out = new ArrayList<>();
        for (Frontier.Line l : Frontier.read(store)) if (l.open() && l.parked()) out.add(l.text());
        return out;
    }

    /** The questions a search was started for: queued, running or finished. */
    public static List<String> searched(LibraryStore store) throws IOException {
        List<String> out = new ArrayList<>();
        Jobs jobs = new Jobs(store, j -> { throw new IllegalStateException("read only"); });
        for (var j : jobs.active()) out.add(j.path("args").path("question").asText(""));
        for (var j : jobs.recent(2000, null)) out.add(j.path("args").path("question").asText(""));
        for (Frontier.Line l : Frontier.read(store)) if (!l.open()) out.add(l.text());
        return out;
    }

    /**
     * Whether a question is about this person: it begins with the person's name as the tree writes it, then what the library knows of
     * them in brackets, or a colon. "Tom Ellis (born 1985) (born 1985 in Leeds): …" is about the namesake Tom Ellis (born 1985), not Tom Ellis.
     */
    static boolean about(String question, String person) {
        String t = Frontier.strip(question);
        if (!t.startsWith(person)) return false;
        String rest = t.substring(person.length());
        if (rest.startsWith(":")) return true;
        if (!rest.startsWith(" (")) return false;
        int depth = 0;
        for (int i = 1; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (c == '(') depth++;
            else if (c == ')' && --depth == 0) return rest.startsWith(":", i + 1);
        }
        return false;
    }

    /**
     * The people a claim is about, as the family research writes them in its questions: the subject, and the other person of a relation.
     * A waiting question that names one of them, as the person asked about or as a lead, was written with what the claim said.
     */
    public static List<String> peopleOf(Graph g, Finding f) {
        List<String> out = new ArrayList<>();
        if (f.triple() == null) return out;
        String pred = g.predicateOf(f.triple().predicate());
        for (boolean subject : FamilyAccount.personToPerson(pred) || FamilyAccount.associate(pred) ? new boolean[]{true, false} : new boolean[]{true}) {
            String n = subject ? f.triple().subject() : f.triple().object();
            Graph.Node node = g.node(g.nodeOf(f, subject));
            String label = node == null ? n : node.label();
            if (!label.isBlank() && !out.contains(label)) out.add(label);
        }
        return out;
    }

    /** Whether a text names this person: the name as a whole, not inside a longer word ("Ann" is not in "Anne"). */
    public static boolean names(String text, String person) {
        for (int at = text.indexOf(person); at >= 0; at = text.indexOf(person, at + 1)) {
            int end = at + person.length();
            boolean before = at == 0 || !Character.isLetterOrDigit(text.charAt(at - 1)), after = end >= text.length() || !Character.isLetterOrDigit(text.charAt(end));
            if ((before && after) || person.codePoints().anyMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN)) return true;
        }
        return false;
    }

    /**
     * The open questions the family research wrote that name somebody this claim is about: when the claim is disputed or retired they
     * would give it as known, so they are taken off the list, and the next {@code genealogy research} writes them again.
     */
    public static List<String> writtenWith(LibraryStore store, Finding f) throws IOException {
        List<Frontier.Line> lines = Frontier.read(store).stream().filter(l -> l.open() && l.kind().contains(FamilyReset.FROM_TREE)).toList();
        if (lines.isEmpty() || f.triple() == null) return List.of();
        List<String> people = peopleOf(FamilyPeople.view(store), f);
        List<String> out = new ArrayList<>();
        for (Frontier.Line l : lines) if (people.stream().anyMatch(p -> names(l.text(), p))) out.add(l.text());
        return out;
    }

    /** The waiting question about this person, or null. */
    public static String waitingFor(List<String> waiting, String person) {
        for (String q : waiting) if (about(q, person)) return q;
        return null;
    }

    /** The questions a search was made with for this person: those that begin with the person's name as the tree writes it. */
    public static List<String> askedAbout(List<String> questions, String person) {
        List<String> out = new ArrayList<>();
        for (String q : questions) if (about(q, person)) out.add(q);
        return out;
    }

    /** Is this person already asked about? A question of this kind begins with the person's name as the tree writes it. */
    public static boolean asked(List<String> questions, String person) {
        for (String q : questions) if (about(q, person)) return true;
        return false;
    }

    /** Every question the library has been asked: the open and the explored ones, and the runs queued, running and finished. */
    public static List<String> everAsked(LibraryStore store) throws IOException {
        List<String> out = new ArrayList<>();
        for (Frontier.Line l : Frontier.read(store)) out.add(l.text());
        Jobs jobs = new Jobs(store, j -> { throw new IllegalStateException("read only"); });
        for (var j : jobs.active()) out.add(j.path("args").path("question").asText(""));
        for (var j : jobs.recent(2000, null)) out.add(j.path("args").path("question").asText(""));
        return out;
    }
}
