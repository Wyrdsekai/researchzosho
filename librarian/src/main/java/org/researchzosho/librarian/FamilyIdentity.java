package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.researchzosho.tools.Fetch;
import org.researchzosho.drive.Declined;

/**
 * Who, of the people the web shows under a name, is the person in the family. A name is shared: a search for it returns the
 * person, and an actress, and a translator, and nothing on those pages says whose child anybody is. A stranger cannot tell them
 * apart and neither can a model; somebody in the family can, at a glance. So the library searches each written form of the name
 * as a plain name, lays the results out as the different people they are about, marks what each one's pages say that the family's
 * facts also say, and ASKS. The answer is kept, goes into the person's research question, and may be changed.
 *
 * <p>The model only sorts the rows the search returned into people and words a line about each from the rows; it adds no row.
 * What matches is counted by the library. Who it is, is said by a person.
 */
public final class FamilyIdentity {
    private FamilyIdentity() { }

    /** One search result. */
    public record Page(String url, String title, String snippet) { }
    /** One of the people the web shows under the name: what the rows say of them, their pages, what agrees with the family's facts, and the family's word (open, yes, no). */
    public record Candidate(String id, String what, List<Page> pages, List<String> matches, String said, List<String> relatives) {
        public Candidate(String id, String what, List<Page> pages, List<String> matches, String said) { this(id, what, pages, matches, said, List.of()); }
        /** What the pages say that the family's facts also say, besides the relatives: years, places, work. */
        public List<String> others() { return matches.stream().filter(m -> !relatives.contains(m)).toList(); }
    }

    /**
     * One thing the family's facts say of a person, as a page about them might also say it: a year, a place, their work, a
     * relative, or an associate (an informant, a witness or a godparent: {@code relative} is their node, {@code names} every full form of their name).
     */
    record Known(String kind, String text, String relative, List<String> names) { }
    /** The question for one person. {@code state}: open, confirmed, none (nobody shown is them), unsure, nobody (the web showed nobody). */
    public record Question(String person, String state, List<String> searched, List<Candidate> candidates, String asked, List<String> told, int toldFiled) {
        public boolean open() { return state.equals("open"); }
        /** The entries that are the person: the ones the family confirmed, and the ones whose pages the library read the person's facts from. */
        public List<Candidate> yes() { return candidates.stream().filter(c -> c.said().equals("yes") || c.said().equals("source")).toList(); }
        public List<Candidate> others() { return candidates.stream().filter(c -> !c.said().equals("yes") && !c.said().equals("source")).toList(); }
    }

    /** A plain search: the pages for a query. */
    public interface Search { List<Page> of(String query); }

    static final int FORMS = 4, ROWS = 40, PAGES_SHOWN = 6;
    private static final ObjectMapper M = new ObjectMapper();

    // ── building the question ────────────────────────────────────────────────────────────────────────────────────────

    /** The written forms of a person's name that are worth a search of their own: the name and its other spellings, a name of one word, a described person and a name with a character nobody could read left out. */
    static List<String> forms(Graph.Node node) {
        Set<String> out = new LinkedHashSet<>();
        for (String n : Stream.concat(Stream.of(node.label()), node.aliases().stream()).toList()) {
            String s = n.replaceAll("\\s*[(（][^)）]*[)）]\\s*", " ").strip();
            if (s.isEmpty() || FamilyQuestions.placeholder(s) || FamilyNames.unreadable(s)) continue;
            boolean cjk = s.codePoints().anyMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN);
            if (!cjk && s.split("\\s+").length < 2) continue;
            if (cjk && s.replaceAll("\\s+", "").length() < 3) continue;
            if (out.stream().noneMatch(o -> FamilyQuestions.plain(o).equals(FamilyQuestions.plain(s)))) out.add(s);
            if (out.size() == FORMS) break;
        }
        return new ArrayList<>(out);
    }

    /**
     * The same for a person the graph holds names over a life for ({@link FamilyNameHistory}): the latest name and the name at birth first,
     * one form each, then the other names, then the other forms of those names (the other spellings and orders), still {@link #FORMS} at
     * most. The web knows a woman who married, or a man who was adopted, under both names, and under the later one more often. A person
     * with one name is searched under the forms the sources wrote ({@link #forms(Graph.Node)}).
     */
    static List<String> forms(Graph g, Graph.Node node) {
        List<FamilyQuestions.Period> periods = FamilyQuestions.periods(g, node.id());
        if (periods.size() < 2) return forms(node);
        Set<String> out = new LinkedHashSet<>();
        FamilyNameHistory.Index idx = FamilyNameHistory.of(g);
        FamilyNameHistory.Name latest = idx.latest(node.id()), birth = idx.birth(node.id());
        List<FamilyQuestions.Period> order = new ArrayList<>();
        for (FamilyNameHistory.Name first : new FamilyNameHistory.Name[]{latest, birth})
            for (FamilyQuestions.Period p : periods) if (p.name() == first && !order.contains(p)) order.add(p);
        for (FamilyQuestions.Period p : periods) if (!order.contains(p)) order.add(p);
        // one form of each name, then the other forms of each name in the same order
        for (FamilyQuestions.Period p : order) add(out, p.shown());
        for (FamilyQuestions.Period p : order) for (String f : p.forms()) add(out, f);
        return new ArrayList<>(out);
    }

    /** A form joins the searches while there is room, unless it is another already there with accents or capitals of its own. */
    private static void add(Set<String> out, String form) {
        if (out.size() < FORMS && out.stream().noneMatch(o -> FamilyQuestions.plain(o).equals(FamilyQuestions.plain(form)))) out.add(form);
    }

    /**
     * What the family's facts say of a person, as the words a page about them might also carry: years, places, work, and the people
     * next to them. A relative is one fact however many names they have, and is known only by a full name: two words, or three
     * characters in kanji or kana. A family name alone is shared by strangers.
     */
    static List<Known> facts(Graph g, String id) {
        List<Known> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Graph.Edge e : g.edges()) {
            boolean from = e.from().equals(id), to = e.to().equals(id);
            if (!from && !to) continue;
            Graph.Node other = g.node(from ? e.to() : e.from());
            if (other == null) continue;
            switch (e.predicate()) {
                case "born-on", "died-on" -> { if (from) { FamilyDate d = FamilyDate.parse(other.label()); if (d != null && seen.add("year " + d.year())) out.add(new Known("year", String.valueOf(d.year()), "", List.of())); } }
                case "born-in", "died-in", "lived-in", "occupation", "worked-at", "studied-at" -> { if (from && seen.add("text " + other.label())) out.add(new Known(e.predicate().equals("occupation") ? "work" : "place", other.label(), "", List.of())); }
                case "child-of", "parent-of", "married-to", "sibling-of", "adopted-by", "step-parent-of", "foster-child-of" -> {
                    if (FamilyQuestions.placeholder(other.label()) || !seen.add("relative " + other.id())) break;
                    List<String> names = Stream.concat(Stream.of(other.label()), other.aliases().stream()).filter(a -> !FamilyQuestions.placeholder(a) && fullName(a)).distinct().toList();
                    if (!names.isEmpty()) out.add(new Known("relative", other.label(), other.id(), names));
                }
                case "informant-for", "witness-for", "godparent-of" -> {
                    // a godparent, a witness or an informant named on the same page is a sign too, though not a relative
                    if (FamilyQuestions.placeholder(other.label()) || !seen.add("associate " + other.id())) break;
                    List<String> names = Stream.concat(Stream.of(other.label()), other.aliases().stream()).filter(a -> !FamilyQuestions.placeholder(a) && fullName(a)).distinct().toList();
                    if (!names.isEmpty()) out.add(new Known("associate", other.label(), other.id(), names));
                }
                default -> { }
            }
        }
        return out;
    }

    /** A name that says who somebody is on its own: two words in Latin letters, or three characters or more in kanji or kana. */
    static boolean fullName(String name) {
        if (FamilyNames.unreadable(name)) return false;
        String s = name.replaceAll("\\s*[(（][^)）]*[)）]\\s*", " ").replace(",", " ").strip();
        boolean cjk = s.codePoints().anyMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN || Character.UnicodeScript.of(c) == Character.UnicodeScript.HIRAGANA || Character.UnicodeScript.of(c) == Character.UnicodeScript.KATAKANA);
        return cjk ? s.replaceAll("\\s+", "").codePointCount(0, s.replaceAll("\\s+", "").length()) >= 3 : s.split("\\s+").length >= 2;
    }

    /**
     * Which of the facts a candidate's pages also say, the relatives first: a husband, a wife or a parent named on the same page is the
     * strongest sign a page is about this person, and counts once however many of their names it carries. A short word is matched as
     * a whole word, so that a year is a year and a place of two letters is not found inside another word.
     */
    static List<String> matching(List<Known> facts, List<Page> pages) {
        String text = fold(String.join(" \n ", pages.stream().map(p -> p.title() + " " + p.snippet()).toList()));
        List<String> relatives = new ArrayList<>(), others = new ArrayList<>();
        for (Known f : facts) {
            List<String> forms = f.names().isEmpty() ? List.of(f.text()) : f.names();   // a relative or an associate by any full form of their name
            if (forms.stream().anyMatch(x -> found(text, x)) && relatives.stream().noneMatch(o -> fold(o).equals(fold(f.text()))) && others.stream().noneMatch(o -> fold(o).equals(fold(f.text()))))
                (f.kind().equals("relative") ? relatives : others).add(f.text());
        }
        relatives.addAll(others);
        return relatives;
    }

    private static boolean found(String text, String fact) {
        String k = fold(fact);
        if (k.length() < 2) return false;
        boolean cjk = k.codePoints().anyMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN);
        return cjk ? text.replace(" ", "").contains(k.replace(" ", "")) : Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(k) + "(?![\\p{L}\\p{N}])").matcher(text).find();
    }

    private static String fold(String s) { return KanjiForms.modern(FamilyQuestions.plain(s)); }

    /**
     * Search each written form of the name and lay the results out as the people they are about. {@code model} sorts the rows
     * (null, or an answer that names no row: every page stands for itself). An answer the family gave before is kept: a page
     * already said to be the person, or not, keeps that word under its address.
     */
    public static Question find(LibraryStore store, Graph g, String person, Search search, Function<String, String> model) throws IOException {
        Graph.Node node = g.node(g.nodeIdOf(person));
        if (node == null) return null;
        List<String> forms = forms(g, node);
        Map<String, Page> rows = new LinkedHashMap<>();
        for (String form : forms) {
            for (Page p : search.of("\"" + form + "\"")) if (rows.size() < ROWS && p.url() != null && !p.url().isBlank()) rows.putIfAbsent(p.url(), p);
        }
        List<Page> all = new ArrayList<>(rows.values());
        List<Candidate> sorted = sort(node.label(), forms, all, model);
        List<Known> facts = facts(g, node.id());
        Set<String> relativeNames = new HashSet<>();
        for (Known k : facts) if (k.kind().equals("relative")) relativeNames.add(k.text());
        Question before = forPerson(store, g, node.label());
        Map<String, String> saidOf = new LinkedHashMap<>();
        if (before != null) for (Candidate c : before.candidates()) if (!c.said().equals("open")) for (Page p : c.pages()) saidOf.put(p.url(), c.said());
        // a page the library read this person's facts from is a page about this person: the library knows that by itself and does not ask
        Set<String> readFrom = new HashSet<>();
        Map<String, Finding> findings = new LinkedHashMap<>();
        for (Finding f : store.scanFindings().findings()) findings.put(f.id(), f);
        for (Graph.Edge e : g.edges()) { Finding f = e.from().equals(node.id()) ? findings.get(e.findingId()) : null; if (f != null) for (Finding.Source src : f.sources()) readFrom.add(samePage(src.locator())); }
        Set<PersonIds.Id> held = PersonIds.all(store, g).getOrDefault(node.id(), Set.of());
        List<Candidate> out = new ArrayList<>();
        for (Candidate c : sorted) {
            String said = c.pages().stream().map(p -> saidOf.get(p.url())).filter(s -> s != null).findFirst().orElse("open");
            if (said.equals("open") && c.pages().stream().anyMatch(p -> readFrom.contains(samePage(p.url())))) said = "source";
            List<String> matches = new ArrayList<>(matching(facts, c.pages()));
            // a page whose address carries the person id the family's own file gives this person
            for (Page p : c.pages()) { PersonIds.Id id = PersonIds.fromUrl(p.url()); if (id != null && held.stream().anyMatch(h -> h.site().equals(id.site()) && h.id().equalsIgnoreCase(id.id())) && !matches.contains(id.shown())) matches.add(0, id.shown()); }
            out.add(new Candidate(c.id(), c.what(), c.pages(), matches, said, matches.stream().filter(relativeNames::contains).toList()));
        }
        // a relative named on the page first, then how much else agrees
        out.sort(Comparator.comparingInt((Candidate c) -> -c.relatives().size()).thenComparingInt(c -> -c.matches().size()).thenComparingInt(c -> -c.pages().size()));
        List<Candidate> numbered = new ArrayList<>();
        for (int i = 0; i < out.size(); i++) { Candidate c = out.get(i); numbered.add(new Candidate(String.valueOf(i + 1), c.what(), c.pages(), c.matches(), c.said(), c.relatives())); }
        String state = numbered.isEmpty() ? "nobody" : numbered.stream().anyMatch(c -> c.said().equals("yes") || c.said().equals("source")) ? "confirmed"
                : before != null && !before.open() && !before.state().equals("nobody") && numbered.stream().noneMatch(c -> c.said().equals("open")) ? before.state() : "open";
        Question q = new Question(node.label(), state, forms, numbered, LocalDate.now().toString(), before == null ? List.of() : before.told(), before == null ? 0 : before.toldFiled());
        write(store, q);
        return q;
    }

    /** The rows, sorted into people by the model: it answers with row numbers and a line about each person, and a number it was not shown is dropped. */
    static List<Candidate> sort(String person, List<String> forms, List<Page> rows, Function<String, String> model) {
        List<Candidate> out = new ArrayList<>();
        Set<Integer> used = new LinkedHashSet<>();
        if (model != null && rows.size() > 1) {
            StringBuilder b = new StringBuilder("These are search results for the name ").append(String.join(" / ", forms)).append(". Several different people can carry one name. ")
                    .append("Sort the rows by which person each one is about: rows about the same person go together, and rows that may be about different people stay apart. ")
                    .append("Leave out a row that is not about one person of this name (a list of many people, a dictionary of names, a place, a company).\n")
                    .append("Answer with one line for each person, in this form, and nothing else:\nrows 2,5,9: who this is, in a few words taken from the rows (what they do, where, when)\n\n");
            for (int i = 0; i < rows.size(); i++) b.append(i + 1).append(" | ").append(clip(rows.get(i).title(), 90)).append(" | ").append(host(rows.get(i).url())).append(" | ").append(clip(rows.get(i).snippet(), 200)).append("\n");
            String answer = "";
            try { answer = model.apply(b.toString()); }
            catch (Declined d) { throw d.at("to sort the web's pages about " + person + " by who they are about"); }   // said, never sorted without the model
            catch (RuntimeException ignored) { }
            Matcher m = Pattern.compile("(?m)^\\s*rows?\\s+([\\d,\\s]+):\\s*(.+)$").matcher(answer == null ? "" : answer);
            while (m.find()) {
                List<Page> pages = new ArrayList<>();
                for (String n : m.group(1).split("[,\\s]+")) {
                    if (n.isBlank()) continue;
                    int i; try { i = Integer.parseInt(n) - 1; } catch (NumberFormatException e) { continue; }
                    if (i >= 0 && i < rows.size() && used.add(i)) pages.add(rows.get(i));
                }
                if (!pages.isEmpty()) out.add(new Candidate("", clip(m.group(2).strip(), 160), pages, List.of(), "open"));
            }
        }
        if (out.isEmpty()) for (Page p : rows) out.add(new Candidate("", clip(p.title(), 160), List.of(p), List.of(), "open"));
        return out;
    }

    // ── the family's word ────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Record what somebody in the family said. {@code picked}: the numbers of the candidates who ARE the person (several, when the
     * web's pages about one person were laid out as two). {@code state}: confirmed (the picked ones are them, the rest are not),
     * none (nobody shown is them), unsure (left for later, the searches go on without it).
     */
    public static Question answer(LibraryStore store, String person, List<String> picked, String state) throws IOException {
        Question q = read(store, person);
        if (q == null) throw ProtocolError.invalidArgs("The library has not looked for \"" + person + "\" on the web yet. `researchzosho genealogy who --find \"" + person + "\"` does that.");
        if (!List.of("confirmed", "none", "unsure").contains(state)) throw ProtocolError.invalidArgs("The answer is one of: confirmed, none, unsure.");
        if (state.equals("confirmed")) {
            if (picked.isEmpty()) throw ProtocolError.invalidArgs("Say which of the numbered people is " + q.person() + ".");
            for (String n : picked) if (q.candidates().stream().noneMatch(c -> c.id().equals(n))) throw ProtocolError.invalidArgs("There is no number " + n + " in the list for " + q.person() + ".");
        }
        List<Candidate> cs = new ArrayList<>();
        for (Candidate c : q.candidates()) {
            String said = switch (state) { case "confirmed" -> picked.contains(c.id()) ? "yes" : "no"; case "none" -> "no"; default -> c.said(); };
            if (c.said().equals("source") && !picked.contains(c.id())) said = state.equals("none") ? "no" : "source";   // what the library read from stays, unless told none of them
            cs.add(new Candidate(c.id(), c.what(), c.pages(), c.matches(), said, c.relatives()));
        }
        Question now = new Question(q.person(), state, q.searched(), cs, q.asked(), q.told(), q.toldFiled());
        write(store, now);
        return now;
    }

    /** Something somebody in the family said of the person while answering. It goes into the research question in their words, and is filed as an account of theirs. */
    public static Question told(LibraryStore store, String person, String text) throws IOException {
        Question q = read(store, person);
        if (q == null || text == null || text.isBlank()) return q;
        List<String> told = new ArrayList<>(q.told()); told.add(text.replaceAll("\\s+", " ").strip());
        Question now = new Question(q.person(), q.state(), q.searched(), q.candidates(), q.asked(), told, q.toldFiled());
        write(store, now);
        return now;
    }

    /** What was told and is not filed as an account yet; {@code filed} marks it done. */
    public static List<String> toldNotFiled(Question q) { return q.told().subList(Math.min(q.toldFiled(), q.told().size()), q.told().size()); }

    public static void toldFiled(LibraryStore store, Question q) throws IOException { write(store, new Question(q.person(), q.state(), q.searched(), q.candidates(), q.asked(), q.told(), q.told().size())); }

    /**
     * What the family's word adds to a person's research question. Confirmed: who they are and their pages, to start from, and the
     * namesakes to keep apart. Not confirmed: the people the web shows, as people who MAY be them, so a finding from one is told as that.
     */
    public static String forQuestion(Question q) {
        if (q == null) return "";
        StringBuilder b = new StringBuilder();
        if (!q.told().isEmpty()) b.append(" The family says of this person: ").append(String.join(" ", q.told().stream().map(t -> "\"" + t + "\"").toList())).append(" Search with these words.");
        if (q.state().equals("nobody")) return b.toString();
        if (q.state().equals("confirmed")) {
            b.append(" It is known who this person is on the web (the family confirmed it, or the library read the person's facts from these pages): ");
            b.append(String.join("; ", q.yes().stream().map(c -> c.what() + " (" + String.join(", ", c.pages().stream().limit(PAGES_SHOWN).map(Page::url).toList()) + ")").toList()));
            b.append(". Start from these pages: read them, and search further with what they say of the person's work, the places and the organisations.");
            List<Candidate> no = q.others();
            if (!no.isEmpty()) b.append(" The family has said these are other people of the same name, and nothing of theirs belongs in the answer: ")
                    .append(String.join("; ", no.stream().limit(8).map(c -> c.what() + " (" + hosts(c) + ")").toList())).append(".");
            return b.toString();
        }
        List<Candidate> shown = q.candidates().stream().limit(8).toList();
        if (q.state().equals("none")) {
            // the family's no is about these pages: nothing of theirs is the person's, however much a year or a place on them agrees
            b.append(" The family has looked at the people the web shows under this name and has said that none of them is this person, so nothing from these pages belongs in the answer: ")
             .append(String.join("; ", shown.stream().map(c -> c.what() + " (" + String.join(", ", c.pages().stream().limit(PAGES_SHOWN).map(Page::url).toList()) + ")").toList())).append(".")
             .append(" On any other page, a name alone does not make a finding this person's: tell of a finding under the person's name only when its source also says something else the family's facts say "
                     + "(a parent, a husband or wife, a year and a place together), and tell of the others as somebody who may be the same person, with what agrees and what does not.");
            return b.toString();
        }
        b.append(" The web shows these people under this name, and the family has not said yet which of them, if any, is this person: ");
        b.append(String.join("; ", shown.stream().map(c -> c.what() + " (" + hosts(c) + ")").toList())).append(".");
        b.append(" A name alone does not make a finding this person's: tell of a finding under the person's name only when its source also says something else the family's facts say "
                + "(a parent, a husband or wife, a year and a place together), and tell of the others as somebody who may be the same person, with what agrees and what does not.");
        return b.toString();
    }

    // ── kept in the library ──────────────────────────────────────────────────────────────────────────────────────────

    static Path file(LibraryStore store, String person) {
        try {
            String h = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(FamilyQuestions.plain(person).getBytes(StandardCharsets.UTF_8))).substring(0, 16);
            return store.root().resolve("family").resolve("who").resolve(h + ".json");
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    public static Question read(LibraryStore store, String person) throws IOException {
        Path f = file(store, person);
        return Files.exists(f) ? parse(M.readTree(Files.readString(f, StandardCharsets.UTF_8))) : null;
    }

    /**
     * The family's word for a person: the question kept under their name, or, when that is missing, still open or left for later, the
     * answer given under a name the owner has since joined into them (a merge, or "One person" on the Decisions page). Nothing is moved,
     * so taking the join back gives the answer back to its own name.
     */
    public static Question forPerson(LibraryStore store, Graph g, String person) throws IOException {
        Question own = read(store, person);
        if (own != null && !own.open() && !own.state().equals("unsure")) return own;
        String id = g.nodeIdOf(person);
        for (Question q : all(store))
            if (!q.open() && !q.state().equals("unsure") && !FamilyQuestions.plain(q.person()).equals(FamilyQuestions.plain(person)) && g.nodeIdOf(q.person()).equals(id)) return q;
        return own;
    }

    /** Every question, the open ones first, and among those the ones where a page agrees with the family's facts first. */
    public static List<Question> all(LibraryStore store) throws IOException {
        Path dir = store.root().resolve("family").resolve("who");
        List<Question> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) return out;
        try (Stream<Path> s = Files.list(dir)) {
            for (Path f : s.filter(p -> p.toString().endsWith(".json")).sorted().toList()) out.add(parse(M.readTree(Files.readString(f, StandardCharsets.UTF_8))));
        }
        out.sort(Comparator.comparing((Question q) -> !q.open()).thenComparingInt(q -> -q.candidates().stream().mapToInt(c -> c.matches().size()).max().orElse(0)).thenComparing(Question::person));
        return out;
    }

    public static List<Question> open(LibraryStore store) throws IOException { return all(store).stream().filter(Question::open).toList(); }

    static void write(LibraryStore store, Question q) throws IOException {
        ObjectNode o = M.createObjectNode().put("person", q.person()).put("state", q.state()).put("asked", q.asked());
        q.searched().forEach(o.putArray("searched")::add);
        q.told().forEach(o.putArray("told")::add);
        o.put("told_filed", q.toldFiled());
        ArrayNode cs = o.putArray("candidates");
        for (Candidate c : q.candidates()) {
            ObjectNode n = cs.addObject().put("id", c.id()).put("what", c.what()).put("said", c.said());
            c.matches().forEach(n.putArray("matches")::add);
            if (!c.relatives().isEmpty()) c.relatives().forEach(n.putArray("relatives")::add);
            ArrayNode ps = n.putArray("pages");
            for (Page p : c.pages()) ps.addObject().put("url", p.url()).put("title", p.title()).put("snippet", p.snippet());
        }
        Path f = file(store, q.person());
        Files.createDirectories(f.getParent());
        Files.writeString(f, M.writerWithDefaultPrettyPrinter().writeValueAsString(o) + "\n", StandardCharsets.UTF_8);
    }

    private static Question parse(JsonNode o) {
        List<String> searched = new ArrayList<>(); o.path("searched").forEach(s -> searched.add(s.asText()));
        List<Candidate> cs = new ArrayList<>();
        for (JsonNode n : o.path("candidates")) {
            List<String> matches = new ArrayList<>(); n.path("matches").forEach(s -> matches.add(s.asText()));
            List<String> relatives = new ArrayList<>(); n.path("relatives").forEach(s -> relatives.add(s.asText()));
            List<Page> pages = new ArrayList<>(); n.path("pages").forEach(p -> pages.add(new Page(p.path("url").asText(), p.path("title").asText(), p.path("snippet").asText())));
            cs.add(new Candidate(n.path("id").asText(), n.path("what").asText(), pages, matches, n.path("said").asText("open"), relatives));
        }
        List<String> told = new ArrayList<>(); o.path("told").forEach(t -> told.add(t.asText()));
        return new Question(o.path("person").asText(), o.path("state").asText("open"), searched, cs, o.path("asked").asText(""), told, o.path("told_filed").asInt(0));
    }

    /**
     * One address, whatever the scheme, the www, the trailing slash or the tracking parameters. The rest of the query stays, in one
     * order: on a record site the record's number is often in it (?id=123), and two people's records are two pages.
     */
    static String samePage(String url) {
        String u = url == null ? "" : url.strip();
        int h = u.indexOf('#'); if (h >= 0) u = u.substring(0, h);
        String c = u.matches("(?i)^https?://.*") ? Fetch.canonical(u) : u;
        if (c == null) c = u;
        int q = c.indexOf('?');
        String base = (q >= 0 ? c.substring(0, q) : c).toLowerCase(Locale.ROOT).replaceFirst("^https?://", "").replaceFirst("^www\\.", "").replaceAll("/+$", "");
        if (q < 0) return base;
        List<String> params = new ArrayList<>(List.of(c.substring(q + 1).split("&")));
        params.removeIf(String::isBlank);
        params.sort(null);
        return params.isEmpty() ? base : base + "?" + String.join("&", params);
    }

    static String host(String url) {
        try { String h = URI.create(url).getHost(); return h == null ? url : h.toLowerCase(Locale.ROOT).replaceFirst("^www\\.", ""); } catch (RuntimeException e) { return url; }
    }

    private static String hosts(Candidate c) { return String.join(", ", c.pages().stream().map(p -> host(p.url())).distinct().limit(3).toList()); }

    private static String clip(String s, int n) { String t = s == null ? "" : s.replaceAll("\\s+", " ").strip(); return t.length() <= n ? t : t.substring(0, n - 1) + "…"; }
}
