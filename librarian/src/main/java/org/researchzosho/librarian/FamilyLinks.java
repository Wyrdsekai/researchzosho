package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.researchzosho.Config;
import org.researchzosho.drive.DriveClient;

import java.io.IOException;
import java.io.PrintStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
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
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * WHO IS WHO FROM THE EVIDENCE. The graph makes one entry of one name, so one person written three ways (森田健二 in one source, Morita
 * Kenji in another, 健二 further on in the first) arrives as three people, and a family name written alone ("Morita") as one person who
 * did everything every Morita of a book did. This pass links each mention of a person in the family's claims to a person, from the
 * evidence, and grades the link: proved, probable or possible. Proved and probable links join the entries in genealogy's own view
 * ({@link Graph.Links}, read only by {@link FamilyPeople#view}); a possible link joins nothing and is shown as "may be the same person as".
 * The core graph and every claim stay as they are, and the person's own merges, splits and "two people" win over any link.
 *
 * <p>The rules are general genealogy practice, machine evidence first. L1: one claim gives both forms (a name claim, the same name written
 * another way). L3: a description with one answer, found by walking the stated relations. L4: a linked page's title names the person the page
 * is about. L5: a short form (a given name alone, a family name alone, a title with a family name, a shorter form of a full name) refers to the
 * one person the same source names in full, or to the one person who stands in the same relation to somebody already linked. L6: a
 * reading filed with a name in characters, in Latin letters, is a name in Latin letters, and independent facts that agree raise the grade.
 * L8: names that differ by one character are never joined. L9: titles and forms of address are taken off before names are compared, and
 * "Mrs. <a man's full name>" is his wife. Nothing links two entries a claim relates as parent and child, husband and wife or brother and
 * sister, whose birth years are more than three apart, of whom one died before the other was born or married, whose recorded sexes differ,
 * or whom the family said are two people.
 *
 * <p>A name written alone is never joined as an entry, only mention by mention: "Morita" in one chapter can be the father and in another
 * the son. And an entry written by such a name holds no full name as another name of its own: the full name is its own entry.
 *
 * <p>The links are kept in {@code family/links.json} with their rule, grade and evidence, worked out again whenever the claims or the
 * person's own files change, so a stale file is never read.
 */
public final class FamilyLinks {

    private FamilyLinks() { }

    /** How sure a link is. Proved and probable join the entries; possible only shows. */
    public enum Grade { proved, probable, possible }

    /**
     * One link. {@code claim} and {@code side} ("subject" or "object") name the mention, one side of one claim; both are "" for a whole entry
     * ({@code node}), every mention of it. {@code written}: the name as the mention or the entry writes it. {@code person}: the entry it is
     * one person with, and {@code personLabel} its label. {@code evidence}: the claims the link rests on. {@code why}: the reason, one sentence.
     */
    public record Link(String claim, String side, String node, String written, String person, String personLabel, String rule, Grade grade, List<String> evidence, String why) {
        /** Whether it joins the two in genealogy's view. */
        public boolean joins() { return grade != Grade.possible; }

        /** Whether it links one mention rather than a whole entry. */
        public boolean mention() { return !claim.isEmpty(); }
    }

    /** A mention several people could be: one side of one claim and the people the same source names that way. Left for the model to choose among. */
    public record Open(String claim, String side, String written, List<String> candidates) { }

    /**
     * The model's choice for a mention several people could be, kept so it is never asked twice: the mention, the candidates it chose among
     * (their entries), the one it chose ("" for none), the words of the passage it quoted, and the outcome: "chose", "cannot tell" or "quote
     * not found" (the words it quoted are not in the passage, so the choice is dropped).
     */
    public record Choice(String claim, String side, List<String> candidates, String chosen, String quote, String outcome) {
        String key() { return Graph.Links.side(claim, side.equals("subject")); }
    }

    /**
     * A question the pass leaves for the model: the readings of a name in characters ({@code kind} "reading", {@code key} the characters),
     * whether characters can be read as a name in Latin letters is read ({@code kind} "directed", {@code key} the characters and the kana,
     * "健吉 (given name) as けんきち"), or which of several people a mention is ({@code kind} "choice", {@code key} the mention), with the prompt,
     * the passage its quote must be found in, and the candidates' entries in the order the prompt numbers them.
     */
    public record Ask(String kind, String key, String prompt, String passage, List<String> candidates, String claim, String side) { }

    /** What the pass worked out: the links, the mentions left open, and the label and other names of each entry the links change. */
    public record Result(String stamp, List<Link> links, List<Open> open, Map<String, String> written, Map<String, String> labels, Map<String, List<String>> names,
                         Map<String, List<String>> readings, Map<String, Choice> choices, List<Ask> asks, List<String[]> workedOut) {
        /** The links as genealogy's view reads them. */
        Graph.Links view() {
            Map<String, String> sides = new HashMap<>(), nodes = new HashMap<>();
            for (Link l : links) {
                if (!l.joins()) continue;
                if (l.mention()) sides.put(Graph.Links.side(l.claim(), l.side().equals("subject")), l.person());
                else if (!l.node().equals(l.person())) nodes.put(l.node(), l.person());
            }
            return new Graph.Links(sides, written, nodes, labels, names, workedOut);
        }
    }

    /** The keys of the model's readings: the characters, and whether they are a family name or a given name. */
    static final String FAMILY = " (family name)", GIVEN = " (given name)";

    /** Changed whenever the rules change, so a file worked out by an older build is worked out again. */
    static final String RULES = "links-9";

    /** Where the links are kept. */
    public static Path file(LibraryStore store) { return store.root().resolve("family").resolve("links.json"); }

    // the links each library's claims were worked out into, while they stay the same: {stamp, result}
    private static final Map<Path, Object[]> HELD = new ConcurrentHashMap<>();
    // a link pass under way on this thread reads genealogy's view without links
    private static final ThreadLocal<Boolean> WORKING = ThreadLocal.withInitial(() -> false);

    /**
     * The links genealogy's view reads {@code claims} with ({@link Profile#links}): those kept in {@code family/links.json} when they were worked
     * out from these claims, else worked out now and kept. None in a library that holds no family work.
     */
    public static Graph.Links links(LibraryStore store, List<Finding> claims) throws IOException {
        if (WORKING.get() || !Files.isDirectory(store.root().resolve("family"))) return null;
        return current(store, claims).view();
    }

    /** The links as they stand for the library's claims now: kept, or worked out again when anything they rest on changed. */
    public static Result current(LibraryStore store) throws IOException { return current(store, store.scanFindings().findings()); }

    static Result current(LibraryStore store, List<Finding> claims) throws IOException {
        String stamp = stamp(store, claims);
        Path root = store.root().toAbsolutePath().normalize();
        Object[] held = HELD.get(root);
        if (held != null && held[0].equals(stamp)) return (Result) held[1];
        Result r = read(store);
        if (r == null || !r.stamp().equals(stamp)) {
            // worked out again without asking anybody: the model's answers already kept are read as they stand
            r = work(store, stamp, r == null ? Map.of() : r.readings(), r == null ? Map.of() : r.choices());
            write(store, r);
        }
        HELD.put(root, new Object[]{stamp, r});
        return r;
    }

    /**
     * The readings the model gave that the link pass kept in {@code links.json}, by the characters ("森田 (family name)", "健二 (given name)"),
     * as they stand: read from the file and never asked for, so a view stays offline ({@link FamilyNameHistory} reads them).
     */
    public static Map<String, List<String>> cachedReadings(LibraryStore store) {
        Object[] held = HELD.get(store.root().toAbsolutePath().normalize());
        if (held != null) return ((Result) held[1]).readings();
        Result r = read(store);
        return r == null ? Map.of() : r.readings();
    }

    /** The links worked out now, whatever the file says, and kept: after a read, an answer, a merge or a split, and on demand. */
    public static Result update(LibraryStore store) throws IOException {
        return update(store, null, x -> { });
    }

    /** The model the link pass asks for readings and choices: a prompt in, the model's words out; an IOException when no model server answers. */
    public interface Model {
        String ask(String prompt) throws IOException;

        /**
         * The same model given room to think before it answers: for a question of knowledge answered yes or no (a directed reading), which the
         * model answering at once gets wrong (measured on the owner's library: 0 of 12 yes at once, 3 of 12 with room to think, the listed
         * readings agreeing with 2). A stub answers as it does to {@link #ask}.
         */
        default String think(String prompt) throws IOException { return ask(prompt); }
    }

    /** The model server the library is set up with ({@code RESEARCHZOSHO_DRIVE}, {@code RESEARCHZOSHO_MODEL}). */
    public static Model liveModel() {
        DriveClient drive = new DriveClient(Config.get("RESEARCHZOSHO_DRIVE", "http://localhost:8200"), Config.get("RESEARCHZOSHO_MODEL", "local-model"));
        ObjectMapper mapper = new ObjectMapper();
        return new Model() {
            @Override public String ask(String prompt) throws IOException {
                ArrayNode msgs = mapper.createArrayNode();
                msgs.addObject().put("role", "user").put("content", prompt);
                String out = drive.classify(msgs, 400);
                IOException none = DriveClient.lastClassifyUnanswered();
                if (none != null && out.isBlank()) throw none;
                return out;
            }

            /** Thinking on, with room for it; the answer is what the model says after, and nothing when the room ran out. A drive without the seat (Bedrock) answers at once. */
            @Override public String think(String prompt) throws IOException {
                ObjectNode body = mapper.createObjectNode();
                body.put("model", drive.model());
                body.putArray("messages").addObject().put("role", "user").put("content", prompt);
                body.put("max_tokens", 2500);
                body.put("temperature", 0.0);
                body.put("stream", false);
                body.putObject("chat_template_kwargs").put("enable_thinking", true);
                try { return drive.postChat(body).path("choices").path(0).path("message").path("content").asText(""); }
                catch (IOException e) { throw e; }
                catch (IllegalStateException noSeat) { return ask(prompt); }
                catch (Exception e) { return ""; }
            }
        };
    }

    /** What {@link #update(LibraryStore, Model, Consumer)} did with the model: how many questions it asked, and whether a server answered. */
    public record Asked(int readings, int chose, int cannotTell, int quoteNotFound, boolean unreachable) { }

    /** The last {@link #update} of this thread with a model, for the sentence the command says. */
    private static final ThreadLocal<Asked> LAST_ASKED = ThreadLocal.withInitial(() -> new Asked(0, 0, 0, 0, false));

    /**
     * The links worked out now and kept, with the model asked what the evidence leaves to it (null: nobody is asked): the readings of names
     * in characters that the relations pair with names in Latin letters (L7), and which of several people a mention is (L5). Each answer is
     * kept in {@code links.json} and never asked again; the links are worked out again with the answers, until no new question comes.
     */
    public static Result update(LibraryStore store, Model model, Consumer<String> progress) throws IOException {
        if (!Files.isDirectory(store.root().resolve("family"))) return new Result("", List.of(), List.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), List.of(), List.of());
        String stamp = stamp(store, store.scanFindings().findings());
        Result before = read(store);
        Map<String, List<String>> readings = new LinkedHashMap<>(before == null ? Map.of() : before.readings());
        Map<String, Choice> choices = new LinkedHashMap<>(before == null ? Map.of() : before.choices());
        Result r = work(store, stamp, readings, choices);
        int nr = 0, chose = 0, cannot = 0, notFound = 0;
        boolean unreachable = false;
        for (int round = 0; model != null && !unreachable && round < 4 && !r.asks().isEmpty(); round++) {
            int i = 0;
            for (Ask a : r.asks()) {
                progress.accept("asking the model " + (++i) + " of " + r.asks().size() + (a.kind().equals("reading") ? ": the readings of " + a.key()
                        : a.kind().equals("directed") ? ": whether " + a.key().replace(" as ", " can be read ") : ": who a mention in claim " + a.claim() + " is"));
                String answer;
                try { answer = a.kind().equals("directed") ? model.think(a.prompt()) : model.ask(a.prompt()); }
                catch (IOException e) { unreachable = true; break; }
                if (a.kind().equals("reading")) { readings.put(a.key(), readingsIn(answer)); nr++; continue; }
                if (a.kind().equals("directed")) { directedAnswer(readings, a.key(), answer); nr++; continue; }
                Choice c = choiceIn(a, answer);
                choices.put(c.key(), c);
                switch (c.outcome()) { case "chose" -> chose++; case "cannot tell" -> cannot++; default -> notFound++; }
            }
            r = work(store, stamp, readings, choices);
        }
        LAST_ASKED.set(new Asked(nr, chose, cannot, notFound, unreachable));
        write(store, r);
        HELD.put(store.root().toAbsolutePath().normalize(), new Object[]{stamp, r});
        return r;
    }

    /**
     * The model's yes or no to a directed question ("can 健吉 be read けんきち?"), kept with its words under the question's key ({@code 健吉 (given name)
     * as けんきち}) so it is asked once; a yes adds the kana to the readings of the characters. A yes that gives kana of its own is taken with those kana when they
     * romanise as the question's do (けんいち for けにち), and is no yes to this question when they romanise otherwise.
     */
    static void directedAnswer(Map<String, List<String>> readings, String key, String answer) {
        int at = key.lastIndexOf(" as ");
        if (at < 0) return;
        String base = key.substring(0, at), kana = key.substring(at + 4);
        JsonNode n = jsonIn(answer);
        String said = (n != null ? n.path("answer").asText("") : answer == null ? "" : answer).strip().toLowerCase(Locale.ROOT);
        boolean yes = said.matches("(?s)^\\W*yes\\b.*");
        String own = n == null ? "" : n.path("reading").asText("").strip();
        if (yes && !own.isEmpty() && FamilyForms.script(own).equals("kana")) {
            if (FamilyForms.latinKey(FamilyForms.hepburn(own)).equals(FamilyForms.latinKey(FamilyForms.hepburn(kana)))) kana = own; else yes = false;
        }
        // the verdict first, then the model's words, so the choice can be read later (genealogy link --choices reads the file)
        String words = (answer == null ? "" : answer).replaceAll("\\s+", " ").strip();
        readings.put(key, List.of(yes ? "yes" : "no", "said: " + (words.length() > 160 ? words.substring(0, 160) + "…" : words)));
        if (yes) { List<String> rs = new ArrayList<>(readings.getOrDefault(base, List.of())); if (!rs.contains(kana)) rs.add(kana); readings.put(base, rs); }
    }

    /** The readings in a model's answer: the kana strings of {"readings": [...]}, five at most; none when it gives none. */
    static List<String> readingsIn(String answer) {
        List<String> out = new ArrayList<>();
        JsonNode n = jsonIn(answer);
        if (n != null) for (JsonNode x : n.path("readings")) {
            String r = x.asText("").strip();
            if (!r.isEmpty() && FamilyForms.script(r).equals("kana") && out.size() < 5 && !out.contains(r)) out.add(r);
        }
        return out;
    }

    /**
     * The model's choice for a mention: the candidate it numbers, when the words it quotes are in the passage it was given (spaces and case
     * aside) and say more than the name itself; "cannot tell" for 0; "quote not found" when the words are not there.
     */
    static Choice choiceIn(Ask a, String answer) {
        String[] k = a.key().split("\\|");
        JsonNode n = jsonIn(answer);
        int pick = n == null ? 0 : n.path("choice").asInt(0);
        String quote = n == null ? "" : n.path("quote").asText("").strip();
        if (pick < 1 || pick > a.candidates().size()) return new Choice(k[0], k[1], a.candidates(), "", quote, "cannot tell");
        if (!quoted(quote, a.passage())) return new Choice(k[0], k[1], a.candidates(), "", quote, "quote not found");
        return new Choice(k[0], k[1], a.candidates(), a.candidates().get(pick - 1), quote, "chose");
    }

    /** Whether words are in a passage, spaces, quotation marks and case aside, and more than a few letters long. */
    static boolean quoted(String quote, String passage) {
        String q = flat(quote), p = flat(passage);
        return q.length() >= 4 && p.contains(q);
    }

    private static String flat(String s) {
        return Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFKC).replaceAll("[“”„\"]", "\"").replaceAll("[‘’`´]", "'").replaceAll("\\s+", " ").strip().toLowerCase(Locale.ROOT)
                .replaceAll("^[\"'.,;:…\\s]+|[\"'.,;:…\\s]+$", "");
    }

    /** The first JSON object in a model's answer; null when there is none. */
    static JsonNode jsonIn(String answer) {
        String a = answer == null ? "" : answer;
        int from = a.indexOf('{'), to = a.lastIndexOf('}');
        if (from < 0 || to <= from) return null;
        try { return JSON.readTree(a.substring(from, to + 1)); } catch (Exception e) { return null; }
    }

    /**
     * What the links rest on, as one value: every claim's id, state, words and sources, the person's own files of names, merges and "two
     * people", and the version of the rules. Any change to them works the links out again.
     */
    static String stamp(LibraryStore store, List<Finding> claims) throws IOException {
        MessageDigest md;
        try { md = MessageDigest.getInstance("SHA-256"); } catch (NoSuchAlgorithmException e) { throw new IOException(e); }
        md.update(RULES.getBytes(StandardCharsets.UTF_8));
        for (Finding f : claims) {
            StringBuilder b = new StringBuilder().append('\n').append(f.id()).append('\u0001').append(f.state()).append('\u0001').append(f.writer());
            if (f.triple() != null) b.append('\u0001').append(f.triple().subject()).append('\u0002').append(f.triple().predicate()).append('\u0002').append(f.triple().object());
            for (Finding.Source s : f.sources()) b.append('\u0001').append(s.locator());
            b.append('\u0001').append(f.body() == null ? 0 : f.body().length());
            md.update(b.toString().getBytes(StandardCharsets.UTF_8));
        }
        for (String name : new String[]{"nodes.md", "merges.tsv", "different.tsv", "alias-sources.tsv"}) {
            Path p = Graph.dir(store).resolve(name);
            md.update(('\n' + name + '\n').getBytes(StandardCharsets.UTF_8));
            if (Files.exists(p)) md.update(Files.readAllBytes(p));
        }
        return HexFormat.of().formatHex(md.digest());
    }

    // ── the file ─────────────────────────────────────────────────────────────────────────────────────────────────────────

    private static final ObjectMapper JSON = new ObjectMapper();

    private static Result read(LibraryStore store) {
        Path p = file(store);
        if (!Files.exists(p)) return null;
        try {
            JsonNode root = JSON.readTree(Files.readString(p, StandardCharsets.UTF_8));
            List<Link> links = new ArrayList<>();
            for (JsonNode l : root.path("links")) {
                List<String> ev = new ArrayList<>();
                for (JsonNode e : l.path("evidence")) ev.add(e.asText());
                links.add(new Link(l.path("claim").asText(""), l.path("side").asText(""), l.path("node").asText(""), l.path("written").asText(""), l.path("person").asText(""),
                        l.path("person_label").asText(""), l.path("rule").asText(""), Grade.valueOf(l.path("grade").asText("possible")), ev, l.path("why").asText("")));
            }
            List<Open> open = new ArrayList<>();
            for (JsonNode o : root.path("open")) {
                List<String> c = new ArrayList<>();
                for (JsonNode x : o.path("candidates")) c.add(x.asText());
                open.add(new Open(o.path("claim").asText(""), o.path("side").asText(""), o.path("written").asText(""), c));
            }
            Map<String, String> written = new LinkedHashMap<>();
            for (Map.Entry<String, JsonNode> e : root.path("written").properties()) written.put(e.getKey(), e.getValue().asText());
            Map<String, String> labels = new LinkedHashMap<>();
            for (Map.Entry<String, JsonNode> e : root.path("labels").properties()) labels.put(e.getKey(), e.getValue().asText());
            Map<String, List<String>> names = new LinkedHashMap<>();
            for (Map.Entry<String, JsonNode> e : root.path("names").properties()) { List<String> n = new ArrayList<>(); for (JsonNode x : e.getValue()) n.add(x.asText()); names.put(e.getKey(), n); }
            Map<String, List<String>> readings = new LinkedHashMap<>();
            for (Map.Entry<String, JsonNode> e : root.path("readings").properties()) { List<String> n = new ArrayList<>(); for (JsonNode x : e.getValue()) n.add(x.asText()); readings.put(e.getKey(), n); }
            Map<String, Choice> choices = new LinkedHashMap<>();
            for (JsonNode c : root.path("choices")) {
                List<String> cs = new ArrayList<>();
                for (JsonNode x : c.path("candidates")) cs.add(x.asText());
                Choice ch = new Choice(c.path("claim").asText(""), c.path("side").asText(""), cs, c.path("chosen").asText(""), c.path("quote").asText(""), c.path("outcome").asText(""));
                choices.put(ch.key(), ch);
            }
            List<String[]> workedOut = new ArrayList<>();
            for (JsonNode w : root.path("worked_out")) workedOut.add(new String[]{w.path("from").asText(""), w.path("relation").asText(""), w.path("to").asText(""), w.path("claim").asText(""), w.path("why").asText("")});
            return new Result(root.path("worked_out_from").asText(""), links, open, written, labels, names, readings, choices, List.of(), workedOut);
        } catch (Exception e) {
            return null;   // a file that cannot be read is worked out again
        }
    }

    private static void write(LibraryStore store, Result r) {
        try {
            ObjectNode root = JSON.createObjectNode();
            root.put("about", "Who is who in the family's claims, linked from the evidence by genealogy. Worked out again whenever the claims change; "
                    + "proved and probable links join entries in the family pages, possible ones are only shown. researchzosho genealogy different \"<a>\" \"<b>\" takes a link back.");
            root.put("worked_out_from", r.stamp());
            ArrayNode links = root.putArray("links");
            for (Link l : r.links()) {
                ObjectNode o = links.addObject();
                if (l.mention()) o.put("claim", l.claim()).put("side", l.side());
                o.put("node", l.node()).put("written", l.written()).put("person", l.person()).put("person_label", l.personLabel()).put("rule", l.rule()).put("grade", l.grade().name());
                ArrayNode ev = o.putArray("evidence");
                for (String e : l.evidence()) ev.add(e);
                o.put("why", l.why());
            }
            ArrayNode open = root.putArray("open");
            for (Open x : r.open()) {
                ObjectNode o = open.addObject().put("claim", x.claim()).put("side", x.side()).put("written", x.written());
                ArrayNode c = o.putArray("candidates");
                for (String s : x.candidates()) c.add(s);
            }
            ObjectNode written = root.putObject("written");
            r.written().forEach(written::put);
            ObjectNode labels = root.putObject("labels");
            r.labels().forEach(labels::put);
            ObjectNode names = root.putObject("names");
            r.names().forEach((k, v) -> { ArrayNode a = names.putArray(k); v.forEach(a::add); });
            ArrayNode worked = root.putArray("worked_out");
            for (String[] w : r.workedOut()) worked.addObject().put("from", w[0]).put("relation", w[1]).put("to", w[2]).put("claim", w[3]).put("why", w[4]);
            // what the model answered, kept so it is never asked again
            ObjectNode readings = root.putObject("readings");
            r.readings().forEach((k, v) -> { ArrayNode a = readings.putArray(k); v.forEach(a::add); });
            ArrayNode choices = root.putArray("choices");
            for (Choice c : r.choices().values()) {
                ObjectNode o = choices.addObject().put("claim", c.claim()).put("side", c.side());
                ArrayNode cs = o.putArray("candidates");
                c.candidates().forEach(cs::add);
                o.put("chosen", c.chosen()).put("quote", c.quote()).put("outcome", c.outcome());
            }
            Path p = file(store);
            Path tmp = p.resolveSibling("links.json.tmp");
            Files.writeString(tmp, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n", StandardCharsets.UTF_8);
            Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            // a library that cannot be written to still reads its links; they are worked out again next time
        }
    }

    /**
     * {@code researchzosho genealogy link}: the links worked out now, each in one sentence under the person it joins, the possible ones with
     * them, and how many mentions are left for the family or the model to tell apart.
     */
    public static int cli(LibraryStore store, PrintStream out) throws IOException { return cli(store, out, false, false, liveModel()); }

    /**
     * The same; {@code open}: also each mention left as written, with the people it could be; {@code choices}: each choice the model made, with
     * the words it quoted. {@code model}: asked what the evidence leaves to it; null asks nobody.
     */
    public static int cli(LibraryStore store, PrintStream out, boolean open, boolean choices, Model model) throws IOException {
        if (!Files.isDirectory(store.root().resolve("family"))) { out.println("Your library holds no family history yet, so there is nobody to link. researchzosho genealogy read <file> reads a family's own account."); return 1; }
        Result r = update(store, model, line -> System.err.println("  " + line));
        Asked a = LAST_ASKED.get();
        Map<String, List<Link>> byPerson = new LinkedHashMap<>();
        for (Link l : r.links()) byPerson.computeIfAbsent(l.personLabel(), k -> new ArrayList<>()).add(l);
        int joined = 0, possible = 0;
        for (Link l : r.links()) { if (l.joins()) joined++; else possible++; }
        out.println("The library linked " + joined + (joined == 1 ? " name or mention" : " names and mentions") + " to the person they are, from the evidence, and found "
                + possible + (possible == 1 ? " possible link" : " possible links") + " it does not join." + (r.open().isEmpty() ? "" : " " + r.open().size()
                + (r.open().size() == 1 ? " mention could be" : " mentions could each be") + " one of several people and stay as they are written.") + " " + said(a, r));
        for (Map.Entry<String, List<Link>> e : byPerson.entrySet()) {
            out.println();
            out.println(e.getKey());
            for (Link l : e.getValue()) out.println("  " + sentence(l));
        }
        if (choices && !r.choices().isEmpty()) {
            out.println();
            out.println("What the model chose, for each mention several people could be:");
            for (Choice c : r.choices().values()) out.println("  claim " + c.claim() + " (" + c.side() + "): " + switch (c.outcome()) {
                case "chose" -> "chose " + labelOf(r, c.chosen()) + ", quoting \"" + c.quote() + "\"";
                case "cannot tell" -> "could not tell, among " + String.join(", ", c.candidates().stream().map(x -> labelOf(r, x)).toList());
                default -> "chose one, but quoted words the passage does not have (\"" + c.quote() + "\"), so nothing was linked";
            } + ".");
        }
        if (open && !r.open().isEmpty()) {
            out.println();
            out.println("Mentions that could each be one of several people, as the source writes them:");
            for (Open o : r.open()) out.println("  \"" + o.written() + "\" in claim " + o.claim() + " could be " + String.join(", ", o.candidates()) + ".");
        }
        out.println();
        out.println("A link is taken back by saying the two are two people: researchzosho genealogy different \"<one name>\" \"<the other>\". The links are kept in family/links.json.");
        return 0;
    }

    /** What the model did in this thread's last update, for the end of a read: one sentence when it was asked something, else "". */
    public static String lastAskedSaid() {
        Asked a = LAST_ASKED.get();
        if (a.unreachable()) return "No model server answered, so the choices of who is who that the evidence leaves to the model wait for one. The command researchzosho genealogy link asks them once a model server answers.";
        if (a.readings() + a.chose() + a.cannotTell() + a.quoteNotFound() == 0) return "";
        return "Who is who: the model chose the person for " + a.chose() + (a.chose() == 1 ? " mention" : " mentions") + " that could be one of several people, and gave the readings of "
                + a.readings() + (a.readings() == 1 ? " name" : " names") + " in characters. The command researchzosho genealogy link --choices shows each choice with the words it rests on.";
    }

    /** What the model did in the last update, as a sentence; "" when it was asked nothing. */
    static String said(Asked a, Result r) {
        int waiting = r.asks().size();
        if (a.unreachable()) return "No model server answered, so " + (waiting == 1 ? "1 question waits" : waiting + " questions wait") + " for one: which of several people a mention is, and how names in characters are read. "
                + "The command researchzosho genealogy link asks them again once a model server answers.";
        if (a.readings() + a.chose() + a.cannotTell() + a.quoteNotFound() == 0) return waiting == 0 ? "" : (waiting == 1 ? "1 question waits" : waiting + " questions wait") + " for a model server.";
        return "The model gave the readings of " + a.readings() + (a.readings() == 1 ? " name" : " names") + " in characters, chose the person for " + a.chose() + (a.chose() == 1 ? " mention" : " mentions")
                + ", could not tell for " + a.cannotTell() + ", and for " + a.quoteNotFound() + " quoted words its passage does not have, so those choices were dropped.";
    }

    /** An entry's label as the links show it. */
    static String labelOf(Result r, String id) {
        String l = r.labels().get(id);
        if (l != null) return l;
        for (Link x : r.links()) { if (x.person().equals(id)) return x.personLabel(); if (x.node().equals(id)) return x.written(); }
        return id;
    }

    /**
     * What the links say of one person, a sentence each, for the person's page, {@code genealogy names} and {@code genealogy life}: each name
     * joined to them and how sure, with why; the mentions of a name written alone that are about them, one sentence for each way a source
     * writes them; and each person they may be the same as. Empty when nothing is linked to them.
     */
    public static List<String> about(LibraryStore store, Graph g, String id) throws IOException {
        if (!Files.isDirectory(store.root().resolve("family")) || g.links() == null) return List.of();
        Result r = current(store);
        List<String> out = new ArrayList<>();
        Map<String, List<Link>> mentions = new LinkedHashMap<>();
        for (Link l : r.links()) {
            if (l.joins() && l.person().equals(id) && !l.mention()) out.add("\"" + l.written() + "\" is joined to this person (" + l.grade().name() + "). " + l.why());
            else if (l.joins() && l.person().equals(id)) mentions.computeIfAbsent(l.written() + "\u0000" + l.grade() + "\u0000" + l.why(), k -> new ArrayList<>()).add(l);
        }
        for (List<Link> ls : mentions.values()) {
            Link l = ls.get(0);
            out.add("\"" + l.written() + "\" in " + (ls.size() == 1 ? "one claim" : ls.size() + " claims") + " is this person (" + l.grade().name() + "). " + l.why());
        }
        for (String[] w : r.workedOut()) {
            if (!w[0].equals(id)) continue;
            Graph.Node p = g.node(w[2]);
            out.add("A child of " + (p == null ? w[2] : p.label()) + ", " + w[4] + " (claim " + w[3] + "); no claim files it, and nothing says otherwise.");
        }
        for (Link l : r.links()) {
            if (l.joins()) continue;
            String other = l.person().equals(id) ? l.node() : l.node().equals(id) ? l.person() : null;
            if (other == null) continue;
            Graph.Node n = g.node(other);
            out.add("May be the same person as " + (n == null ? (l.person().equals(id) ? l.written() : l.personLabel()) : n.label()) + ", and is not joined to them. " + l.why());
        }
        return out;
    }

    /** The sentence after the links a page shows: how a link is taken back. */
    public static final String TAKE_BACK = "A join is taken back by saying the two are two people: researchzosho genealogy different \"<one name>\" \"<the other>\".";

    /** One link as a person reads it: which name was joined, how sure, and why; a possible one as "may be the same person as". */
    public static String sentence(Link l) {
        String what = l.mention() ? "\"" + l.written() + "\" in claim " + l.claim() : "\"" + l.written() + "\"";
        if (!l.joins()) return what + " may be the same person as " + l.personLabel() + ". " + l.why();
        return what + " is " + l.personLabel() + " (" + l.grade().name() + "). " + l.why();
    }

    // ── the pass ─────────────────────────────────────────────────────────────────────────────────────────────────────────

    /** Work the links out from genealogy's view without them. */
    static Result work(LibraryStore store, String stamp, Map<String, List<String>> readings, Map<String, Choice> choices) throws IOException {
        boolean was = WORKING.get();
        WORKING.set(true);
        try {
            return new Pass(store, FamilyPeople.unlinkedView(store), readings, choices).run(stamp);
        } finally {
            WORKING.set(was);
        }
    }

    /** The kinds of entry a rule reads differently. */
    enum Kind {
        /** A full name: joins as an entry. */ FULL,
        /** A given name or a family name alone, with a title or without, or initials with a family name: linked mention by mention only. */ SHORT,
        /** A linked page's reader, written as the page's title and site: the page's subject (L4). */ PAGE,
        /** A description ("Tom Hale's father", "the writer of notes.txt"): found by walking the relations (L3). */ DESCRIPTION
    }

    /** The relations that make two people two ({@link FamilySame}), and the ones a walk follows. */
    static final Set<String> KIN = Set.of("parent-of", "child-of", "married-to", "sibling-of", "adopted-by", "step-parent-of", "foster-child-of", "parent-in-law-of");

    /** Whether a relation ties two people who are two: every family relation, "relative of" and "heir of" among them. */
    static boolean twoPeople(String rel) { return KIN.contains(rel) || FamilyAccount.personToPerson(rel) || FamilyAccount.associate(rel); }

    static final Set<String> MAN = Set.of("mr", "sir", "lord", "viscount", "baron", "count", "marquis", "marquess", "prince", "father", "fr", "brother");
    static final Set<String> WOMAN = Set.of("mrs", "miss", "ms", "lady", "dame", "viscountess", "baroness", "countess", "marchioness", "princess", "sister", "mother");

    /** A name without its titles, forms of address and honorifics: the one list every name comparison takes off ({@link FamilyNames#untitled}). */
    static String untitled(String name) { return FamilyNames.untitled(name == null ? "" : name.strip()); }

    /** The sex a title before a name says ("male" for Mr., Viscount, Father; "female" for Mrs., Miss, Lady); "" for none. */
    static String titleSex(String name) {
        Matcher m = Pattern.compile("^(\\p{L}+)\\.?\\s+\\p{L}").matcher(name == null ? "" : name.strip());
        if (!m.find()) return "";
        String t = m.group(1).toLowerCase(Locale.ROOT);
        return MAN.contains(t) ? "male" : WOMAN.contains(t) ? "female" : "";
    }

    /** The words of a name in Latin letters, folded for comparing: no accents, lower case, no punctuation. */
    static List<String> words(String s) {
        String flat = Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFKD).replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT).replaceAll("(?<=\\p{L})['’](?=\\p{L})", "");
        List<String> out = new ArrayList<>();
        for (String w : flat.split("[^\\p{L}\\p{N}]+")) if (!w.isBlank()) out.add(w);
        return out;
    }

    /** A name in characters as it is compared: modern characters, no spaces or dots. */
    static String han(String s) { return FamilyForms.hanKey(s == null ? "" : s); }

    /** Whether a name is written in characters or kana. */
    static boolean cjk(String s) { return FamilyForms.japanese(s); }

    // a relation word as a book's index writes one in brackets after a name: "(father)", "(Ivy; wife)", "(daughter; later Mrs Hale)" is not one
    private static final String NOTE_RELATION = "(?:(?:elder|eldest|older|oldest|younger|youngest|first|second|third|late|only|half|step|adoptive|adopted|foster|maternal|paternal|great|grand)[- ]?)*"
            + "(?:father|mother|parent|wife|husband|spouse|son|daughter|child|brother|sister|uncle|aunt|cousin|nephew|niece|grandfather|grandmother|grandparent|grandson|granddaughter|grandchild"
            + "|(?:father|mother|son|daughter|brother|sister)-in-law|narrator|author|the author|the narrator|myself|me|父|母|妻|夫|長男|次男|三男|長女|次女|息子|娘|兄|弟|姉|妹|祖父|祖母|叔父|伯父|叔母|伯母|甥|姪|従兄弟|従姉妹|いとこ|本人|著者|筆者)";
    private static final Pattern RELATION_NOTE = Pattern.compile("(?i)^(?:(?<name>\\p{Lu}[\\p{L}'’-]+)\\s*[;,:；、]\\s*)?(?<rel>" + NOTE_RELATION + "(?:\\s*(?:,|;|and|&|/|、|・)\\s*" + NOTE_RELATION + ")*)\\s*[.。]?$");

    /**
     * What a note in brackets after a name is: {"name", the name} for another given name ("Kenjirō (Hisa) Endō"); {"relation", the Western
     * name in it or ""} for a book's index's relation word to its narrator, "(father)", "(Ivy; wife)", which is no part of the name and
     * tells nobody apart; {"namesake", ""} for a note that tells namesakes apart ("(born 1851)", "(the shopkeeper)"), which stays with the
     * name.
     */
    static String[] note(String inside) {
        String s = inside == null ? "" : inside.strip();
        if (s.matches("\\p{Lu}[\\p{L}'’-]+") && !cjk(s)) return new String[]{"name", s};
        Matcher m = RELATION_NOTE.matcher(s);
        if (m.matches()) return new String[]{"relation", m.group("name") == null ? "" : m.group("name")};
        return new String[]{"namesake", ""};
    }

    /** The relation words a name's brackets give to the book's narrator ("father"; "wife" of "(Ivy; wife)"), lower case; empty for none. */
    static List<String> relationNotes(String name) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("[(（]([^)）]*)[)）]").matcher(name == null ? "" : name);
        while (m.find()) {
            Matcher r = RELATION_NOTE.matcher(m.group(1).strip());
            if (!r.matches() || note(m.group(1))[0].equals("name")) continue;
            for (String w : r.group("rel").split("\\s*(?:,|;|\\band\\b|&|/|、|・)\\s*")) if (!w.isBlank()) out.add(w.strip().toLowerCase(Locale.ROOT));
        }
        return out;
    }

    /**
     * The forms of a name to compare: without the title, the note in brackets taken out, and one form for each other given name in brackets,
     * as "(Hisa)" beside Kenjirō Endō or "(Ivy; wife)" beside an index's "Morita, Sumiko" gives one. A note that tells namesakes apart stays
     * with the name.
     */
    static List<String> forms(String name) {
        String n = untitled(name == null ? "" : name.strip());
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("\\s*[(（]([^)）]*)[)）]\\s*").matcher(n);
        if (!m.find()) { out.add(n); return out; }
        m.reset();
        while (m.find()) if (note(m.group(1))[0].equals("namesake")) { out.add(n); return out; }
        m.reset();
        m.find();
        String without = m.replaceAll(" ").replaceAll("\\s+", " ").strip();
        out.add(without);
        m.reset();
        while (m.find()) {
            String[] x = note(m.group(1));
            if (x[1].isEmpty()) continue;
            // Kenjirō (Hisa) Endō: the word in brackets is another given name, in place of the first; in an index's "Morita, Sumiko (Ivy; wife)", of the given part
            String[] ix = FamilyNames.indexForm(without);
            if (ix != null) { out.add(ix[0] + ", " + x[1]); continue; }
            List<String> w = new ArrayList<>(List.of(without.split("\\s+")));
            if (w.size() >= 2) { w.set(0, x[1]); out.add(String.join(" ", w)); }
        }
        return out;
    }

    /** A name without the notes in brackets that tell namesakes apart, and without an index's relation words; another given name in brackets stays. */
    static String unnoted(String name) {
        Matcher m = Pattern.compile("\\s*[(（]([^)）]*)[)）]").matcher(name == null ? "" : name);
        StringBuilder b = new StringBuilder();
        while (m.find()) {
            String[] x = note(m.group(1));
            m.appendReplacement(b, x[0].equals("name") ? Matcher.quoteReplacement(m.group()) : x[1].isEmpty() ? "" : Matcher.quoteReplacement(" (" + x[1] + ")"));
        }
        m.appendTail(b);
        return b.toString().replaceAll("\\s+", " ").strip();
    }

    /** The key two forms of one name share: Latin words sorted, or characters in modern forms; "" for a name of one word. */
    static String key(String form) {
        // a note in brackets tells namesakes apart ("John Ellis (born 1851)", 森田勇 (head from 1875)): such a name matches only itself. An
        // index's relation word, "(father)", is no part of the name
        Matcher note = Pattern.compile("\\s*[(（]([^)）]*)[)）]").matcher(form == null ? "" : form);
        StringBuilder kept = new StringBuilder();
        while (note.find()) {
            String[] x = note(note.group(1));
            if (x[0].equals("namesake")) return "";
            note.appendReplacement(kept, x[0].equals("relation") ? " " : Matcher.quoteReplacement(note.group()));
        }
        note.appendTail(kept);
        String f = untitled(kept.toString().replaceAll("\\s+", " ").strip());
        if (cjk(f)) { String h = han(f); return FamilyForms.script(f).equals("han") && h.codePointCount(0, h.length()) >= 3 ? h : ""; }
        List<String> w = words(FamilyNames.indexForm(f) != null ? FamilyNames.indexForm(f)[0] + " " + FamilyNames.indexForm(f)[1] : f);
        if (w.size() < 2 || initials(f)) return "";
        List<String> s = new ArrayList<>(w);
        s.sort(null);
        return String.join(" ", s);
    }

    /** Whether a name in Latin letters gives its given names only as initials ({@link FamilyNames#initials}): "K. Morita", "T. H. Hale". */
    static boolean initials(String name) { return FamilyNames.initials(name); }

    // a linked page's reader: "<title> (<host>)", with the note the person wrote beside the address after it
    private static final Pattern PAGE = Pattern.compile("^(.+?) \\(((?:[\\w-]+\\.)+[a-z]{2,})\\)(?: — (.*))?$");
    private static final Pattern NOTE = Pattern.compile("the person who keeps this library notes: [\"“](.*?)[\"”]");

    /** The page's own subject from its reader's label: the title without the site's name after it ("森田健二 - Wikipedia" is 森田健二); null for no page. */
    static String pageSubject(String label) {
        Matcher m = PAGE.matcher(label == null ? "" : label.strip());
        if (!m.matches()) return null;
        String title = m.group(1).strip();
        Matcher site = Pattern.compile("^(.*?)\\s+[-–—|:]\\s+[^-–—|:]{1,40}$").matcher(title);
        if (site.matches()) title = site.group(1).strip();
        return title.isEmpty() ? null : title;
    }

    /** The note the person wrote beside a page's address, from its reader's label; "" for none. */
    static String pageNote(String label) {
        Matcher m = NOTE.matcher(label == null ? "" : label);
        return m.find() ? m.group(1) : "";
    }

    // a relation word, with the words that narrow it
    private static final String STEP = "(?:(?:elder|eldest|older|oldest|younger|youngest|first|second|third|only|late|maternal|paternal)\\s+)*"
            + "(?:great[- ]?)*(?:grand)?(?:mother|father|parent|wife|husband|spouse|son|daughter|child|brother|sister|daddy|dad|papa|mummy|mommy|mum|mom|mama)(?![\\p{L}])";
    // "my father's father", and not the start of "my father's cousin": a chain says all of the relation or nothing
    private static final Pattern CHAIN = Pattern.compile("(?i)\\bmy\\s+(" + STEP + "(?:['’]s\\s+" + STEP + ")*)(?!['’]s\\s+\\p{L})");
    private static final Pattern DESCRIBED = Pattern.compile("(?i)^(.+?)['’]s\\s+(" + STEP + "(?:['’]s\\s+" + STEP + ")*)$");

    /** The steps of a chain of relation words ("father's father's younger brother"): each step lower case. */
    static List<String> steps(String chain) {
        List<String> out = new ArrayList<>();
        for (String s : chain.split("['’]s\\s+")) out.add(s.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " "));
        return out;
    }

    /** How many chains of relation words a note gives from its writer: "my father is X and my mother is Y" gives two. */
    static int chains(String note) {
        int n = 0;
        Matcher m = CHAIN.matcher(note == null ? "" : note);
        while (m.find()) n++;
        return n;
    }

    /**
     * The one chain of relation words a note gives from its writer, as single steps: one chain, or several that say one relation ("my
     * great grandfather (my father's father's father)"), the most exact of them; null where the note gives none, or two that differ ("my
     * father is X and my mother is Y").
     */
    static List<String> oneChain(String note) {
        List<String> best = null;
        Matcher m = CHAIN.matcher(note == null ? "" : note);
        while (m.find()) {
            List<String> c = atoms(steps(m.group(1)));
            if (best == null) { best = c; continue; }
            List<String> both = sameRelation(best, c);
            if (both == null) return null;
            best = both;
        }
        return best;
    }

    /**
     * Two chains as one relation: the same steps, where a parent on the way to a grandparent stands for a father, a mother or a parent
     * ({@link #GRAND_PARENT}); the more exact of the two, or null when they differ.
     */
    static List<String> sameRelation(List<String> a, List<String> b) {
        if (a.size() != b.size()) return null;
        List<String> out = new ArrayList<>();
        for (int i = 0; i < a.size(); i++) {
            String x = a.get(i), y = b.get(i);
            if (x.equals(y)) { out.add(x); continue; }
            boolean xg = x.equals(GRAND_PARENT), yg = y.equals(GRAND_PARENT);
            if (xg && Set.of("father", "mother", "parent").contains(y)) out.add(y);
            else if (yg && Set.of("father", "mother", "parent").contains(x)) out.add(x);
            else return null;
        }
        return out;
    }

    /** The longest chain of relation words a note of the person's own gives from themself: "my father's father's father"; empty for none. */
    static List<String> ownChain(String note) {
        List<String> best = List.of();
        Matcher m = CHAIN.matcher(note == null ? "" : note);
        while (m.find()) { List<String> s = steps(m.group(1)); if (s.size() > best.size()) best = s; }
        return best;
    }

    private static final Pattern GRAND = Pattern.compile("^((?:great[- ]?)*)grand(father|mother|parent|son|daughter|child)$");

    // a relation word as a link note or the owner's notes write one, with uncle, aunt, cousin, nephew and niece
    private static final String STEP_ANY = "(?:(?:elder|eldest|older|oldest|younger|youngest|first|second|third|only|late|maternal|paternal|half|step)[- ]+)*"
            + "(?:great[- ]?)*(?:grand)?(?:mother|father|parent|wife|husband|spouse|son|daughter|child|brother|sister|uncle|aunt|cousin|nephew|niece|daddy|dad|papa|mummy|mommy|mum|mom|mama)(?![\\p{L}])";
    private static final Pattern CHAIN_ANY = Pattern.compile("(?i)\\bmy\\s+(" + STEP_ANY + "(?:['’]s\\s+" + STEP_ANY + ")*)");
    // a line of the owner's notes that states a relation from a named person: "森田健吾 is 森田正一's younger brother", "A - B's father"
    // a line of the owner's notes that gives a person's name another way: "森田健二 - my grandfather. also written 森田健次", "森田健二 (also
    // spelled 森田健次)", "森田健二、別表記 森田健次", "森田健二 (森田健次とも書く)". The name the line begins with, and the other way
    private static final Pattern ALSO_WRITTEN = Pattern.compile("(?i)(?:\\b(?:also\\s+(?:written|spelled|spelt)(?:\\s+as)?|written\\s+also(?:\\s+as)?)\\s*:?\\s*|別表記\\s*[:：]?\\s*)(?<b>[^.,;:()（）、。\\n]+?)\\s*(?:[.,;)）、。]|$)|(?<bj>[^\\s(（、。]+?)とも(?:書く|表記する)");

    private static final Pattern STATED_BY_NAME = Pattern.compile("(?i)^\\s*[-*•・]?\\s*(?<a>[^-–—:;,()（）]+?)\\s+(?:is|was|-|–|—)\\s+(?<b>[^-–—:;,()（）]+?)['’]s\\s+(?<steps>" + STEP + "(?:['’]s\\s+" + STEP + ")*)(?![\\p{L}'’])");
    private static final Pattern STATED_BY_NAME_JA = Pattern.compile("^\\s*(?<a>[^はの、。]+?)は(?<b>[^のは、。]+?)の(?<step>弟|兄|妹|姉|息子|娘|父|母|夫|妻)(?:です|である|だ)?[。\\s]*$");
    // words that hedge what a note says: the link it makes is probable, never proved
    private static final Pattern HEDGED = Pattern.compile("(?i)\\b(?:i think|i believe|i guess|maybe|perhaps|probably|possibly|not sure|might be|may be|could be|unsure)\\b|\\?|たぶん|多分|おそらく|かもしれ|と思");

    /** The chain of relation words a link note gives from its writer ("my mother's uncle"), as single steps; empty for none. Uncle, aunt and cousin count here. */
    static List<String> noteSteps(String told) {
        List<String> best = List.of();
        Matcher m = CHAIN_ANY.matcher(told == null ? "" : told);
        while (m.find()) { List<String> x = steps(m.group(1)); if (x.size() > best.size()) best = x; }
        return atoms(best);
    }

    /** A line that states a relation from a named person, {the person, the one they are related to, the steps}; null for any other line. */
    static String[] statedLine(String line) {
        Matcher m = STATED_BY_NAME.matcher(line == null ? "" : line);
        if (m.find() && !m.group("b").strip().toLowerCase(Locale.ROOT).startsWith("my ")) return new String[]{m.group("a").strip(), m.group("b").strip(), m.group("steps")};
        Matcher j = STATED_BY_NAME_JA.matcher(line == null ? "" : line);
        if (!j.find()) return null;
        String step = switch (j.group("step")) { case "弟" -> "younger brother"; case "兄" -> "elder brother"; case "妹" -> "younger sister"; case "姉" -> "elder sister"; case "息子" -> "son"; case "娘" -> "daughter"; case "父" -> "father"; case "母" -> "mother"; case "夫" -> "husband"; default -> "wife"; };
        return new String[]{j.group("a").strip(), j.group("b").strip(), step};
    }

    /** A relation word without the words that narrow it: "younger brother" is a brother. */
    static String bare(String step) { return step.replaceAll("^(?:(?:elder|eldest|older|oldest|younger|youngest|first|second|third|only|late|half|step)\\s+)+", ""); }

    /** Steps as a sentence says them: "father's parent's mother". */
    static String said(List<String> steps) { return String.join("'s ", steps.stream().map(x -> x.equals(GRAND_PARENT) ? "parent" : x).toList()); }

    /** The step to a parent on the way to a grandparent: any parent the library holds, as a grandparent's walk goes. */
    static final String GRAND_PARENT = "parent (on the way to a grandparent)";

    /**
     * A chain of relation words as single steps, so that two ways of saying one relation compare equal: "dad" is father and "mum" mother,
     * "paternal grandfather" is father's father, "grandmother" a parent's mother, "great grandfather" a parent's parent's father, "grandson"
     * a child's son. The parent a grandparent is reached through is {@link #GRAND_PARENT}: a walk goes through any parent the library
     * holds there, as it always did for a grandparent.
     */
    static List<String> atoms(List<String> steps) {
        List<String> out = new ArrayList<>();
        for (String step : steps) {
            String s = step.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").replaceAll("^(?:late|only)\\s+", "").strip();
            String side = s.startsWith("paternal ") ? "father" : s.startsWith("maternal ") ? "mother" : "parent";
            s = s.replaceFirst("^(?:paternal|maternal)\\s+", "").replaceFirst("(?:daddy|dad|papa)$", "father").replaceFirst("(?:mummy|mommy|mum|mom|mama)$", "mother");
            Matcher m = GRAND.matcher(s);
            if (!m.matches()) { out.add(s); continue; }
            int greats = m.group(1).isEmpty() ? 0 : m.group(1).split("[- ]").length;
            boolean down = List.of("son", "daughter", "child").contains(m.group(2));
            out.add(down ? "child" : side.equals("parent") ? GRAND_PARENT : side);
            for (int i = 0; i < greats; i++) out.add(down ? "child" : GRAND_PARENT);
            out.add(m.group(2));
        }
        return out;
    }

    // a person saying who they are: "i am Kenji Morita", "I'm Kenji", "my name is Kenji Morita", "Kenji Morita (me)", 私は森田健二です, 私の名前は森田健二
    private static final Pattern SELF_SAID = Pattern.compile("(?i)(?<![\\p{L}])(?:i am|i'm|i’m|my name is)\\s+(.+)");
    private static final Pattern SELF_ME = Pattern.compile("(?i)^[\\s\\-*•・]*(.+?)\\s*[(（]\\s*me\\s*[)）]");
    private static final Pattern SELF_JA = Pattern.compile("(?:私|わたし|僕)(?:の名前)?は\\s*(.+)");
    // where the name stops and the words go on about the person: two spaces, a comma or a full stop, a year, "born", です
    private static final Pattern SELF_END = Pattern.compile("(?i)\\s{2,}|[,;。、.!?:]|\\s+(?:born|b\\.|née|nee|formerly|from|and|who|aged|age)(?![\\p{L}])|\\d|です|でした|と申します|生まれ|だ(?:$|。)");

    /**
     * The names a line of a person's own words gives themself: "i am kenji morita - 森田健二  born 1973" gives kenji morita and 森田健二; empty
     * for a line that does not say who the writer is.
     */
    static List<String> selfNames(String line) {
        if (line == null) return List.of();
        String rest = null;
        Matcher m;
        if ((m = SELF_SAID.matcher(line)).find()) rest = m.group(1);
        else if ((m = SELF_ME.matcher(line)).find()) rest = m.group(1);
        else if ((m = SELF_JA.matcher(line)).find()) rest = m.group(1);
        if (rest == null) return List.of();
        Matcher end = SELF_END.matcher(rest);
        if (end.find()) rest = rest.substring(0, end.start());
        List<String> out = new ArrayList<>();
        for (String f : rest.split("\\s+[-–—/=]\\s+|[(（)）]")) { String x = f.strip(); if (!x.isEmpty()) out.add(x); }
        return out;
    }

    // a description written in Japanese: "森田健二の父の父", "森田健二の父方の祖母", with the relation words close family knows
    private static final Pattern DESCRIBED_JA = Pattern.compile("^(.+?)の((?:(?:父方|母方)の)?" + FamilyClose.WORD_JA + "(?:の(?:(?:父方|母方)の)?" + FamilyClose.WORD_JA + ")*)$");

    /**
     * A description as {the person it starts from, the steps}: "Tom Hale's father's sister" is {Tom Hale, [father, sister]}; null for none.
     * One written in Japanese, "森田健二の父の父", is read with the relation words close family knows ({@link FamilyClose#steps}), into the
     * walk's own words: 父 is father, 母 mother, 祖母 a parent's mother, 父方の祖母 the father's mother, 長男 son, 妻 wife.
     */
    static String[] description(String label) {
        Matcher m = DESCRIBED.matcher(label == null ? "" : label.strip());
        if (!m.matches()) return descriptionJa(label);
        String anchor = m.group(1).strip();
        // the longest anchor that is not itself a chain: "Tom Hale's father's sister" starts from Tom Hale
        Matcher again = DESCRIBED.matcher(anchor);
        while (again.matches()) { anchor = again.group(1).strip(); again = DESCRIBED.matcher(anchor); }
        String rest = label.strip().substring(anchor.length()).replaceFirst("^['’]s\\s+", "");
        return new String[]{anchor, rest};
    }

    static String[] descriptionJa(String label) {
        Matcher m = DESCRIBED_JA.matcher(label == null ? "" : label.strip());
        if (!m.matches()) return null;
        List<String> words = new ArrayList<>();
        String side = "";
        for (String w : m.group(2).split("の")) {
            if (w.equals("父方") || w.equals("母方")) { side = w.equals("父方") ? "male" : "female"; continue; }
            List<FamilyClose.Step> steps = FamilyClose.steps(w, side);
            side = "";
            if (steps == null || steps.isEmpty()) return null;
            for (int i = 0; i < steps.size(); i++) {
                FamilyClose.Step st = steps.get(i);
                boolean man = st.sex().equals("male"), woman = st.sex().equals("female");
                words.add(switch (st.way()) {
                    case 'u' -> man ? "father" : woman ? "mother" : i < steps.size() - 1 ? GRAND_PARENT : "parent";
                    case 'd' -> man ? "son" : woman ? "daughter" : "child";
                    case 's' -> man ? "brother" : woman ? "sister" : "brother or sister";
                    default -> man ? "husband" : woman ? "wife" : "spouse";
                });
            }
        }
        return new String[]{m.group(1).strip(), String.join("'s ", words)};
    }

    /** Whether words stand for somebody without naming them: "a non-Japanese woman", "his wife", "the old servant". */
    static boolean unnamed(String written) {
        List<String> w = words(written);
        return !w.isEmpty() && Set.of("a", "an", "the", "some", "one", "his", "her", "their", "my", "our", "its").contains(w.get(0)) && !cjk(written);
    }

    /** The pass over one library's claims. */
    private static final class Pass {
        final LibraryStore store;
        final Graph g;
        final Map<String, Finding> claims = new HashMap<>();
        final Map<String, Kind> kind = new LinkedHashMap<>();   // person entry → its kind
        final Map<String, List<String>> names = new LinkedHashMap<>();   // person entry → its label and other names
        final Map<String, String> label = new HashMap<>();
        final Map<String, List<String[]>> sidesOf = new HashMap<>();   // entry → {claim, "subject"|"object"} of each mention
        final Map<String, String> sides = new LinkedHashMap<>();   // claim|side → the entry the pass made it (detached or linked)
        final Map<String, String> up = new HashMap<>();   // union-find
        final Map<String, Set<String>> members = new HashMap<>();
        final List<Link> links = new ArrayList<>();
        final Map<String, Link> mentionLinks = new LinkedHashMap<>();   // claim|side → its link
        final List<Open> open = new ArrayList<>();
        final Map<String, Set<String>> detached = new HashMap<>();   // an entry of a name alone → the full names it holds no longer
        final Map<String, String> movedFrom = new HashMap<>();   // claim|side → the entry of a name alone it was moved off
        final Map<String, List<String>> byKey = new HashMap<>();   // the key of a full name → the entries of full names that carry it
        final List<String[]> sexClaims = new ArrayList<>();   // {claim, entry, male|female}
        final List<String[]> sexInWords = new ArrayList<>();   // {claim, subject|object, entry, male|female}: what a relation's own words say
        final Map<String, List<String[]>> aliasSources = new HashMap<>();   // source → {entry, name} the list of names says it gives
        final Map<String, Set<String>> readGave = new HashMap<>();   // entry → the other names a read gave it, folded
        final Map<String, Set<PersonIds.Id>> ids = new HashMap<>();   // entry → its ids on family-tree sites
        final Set<String> apart = new HashSet<>();   // pairs the person said are two people
        final List<String[]> kin = new ArrayList<>();   // {claim, relation, from entry, to entry}
        final Set<String> fullHan = new HashSet<>();
        final Map<String, List<String[]>> facts = new HashMap<>();   // relation → {claim, from, to} of every standing claim
        final Set<String> familyParts = new HashSet<>();   // the family names the library knows, folded
        final Set<String> familyKeys = new HashSet<>();   // the same in Latin letters, as romaji are compared
        final Map<String, List<String>> familyReadings = new HashMap<>();   // a family name in characters → its readings, as the library's own readings give them

        final Map<String, List<String>> readings;   // the model's readings of names in characters, by the characters
        final Map<String, Choice> choices;   // the model's choices, by the mention
        final Map<String, Object[]> wanted = new LinkedHashMap<>();   // a mention the model is to be asked about: {side, written, candidates}
        final Set<String> wantedReadings = new LinkedHashSet<>();   // names in characters whose readings the model is to be asked for
        final Map<String, String> wantedDirected = new LinkedHashMap<>();   // a directed question, "can 健吉 be read けんきち?", by its key → the prompt
        final Set<String> ownerNotes = new HashSet<>();   // the owner's own notes: the short texts of the family's folder no other writer is named for
        final Set<String> otherSpellings = new HashSet<>();   // the spellings the owner's notes give only as "also written": not the owner's own
        private List<Object[]> stated;   // what the owner's notes state from a named person: {the person's group, the other's group, the steps}

        Pass(LibraryStore store, Graph g, Map<String, List<String>> readings, Map<String, Choice> choices) {
            this.store = store; this.g = g; this.readings = readings; this.choices = choices;
        }

        Result run(String stamp) throws IOException {
            for (Finding f : FamilyPeople.findings(g)) claims.put(f.id(), f);
            // the owner's own notes ({@link FamilyFolder#ownerNotes}): the sources of the claims that are one
            FamilyFolder.OwnerNotes own = FamilyFolder.ownerNotes(store);
            Set<String> checked = new HashSet<>();
            for (Finding f : claims.values()) for (Finding.Source src : f.sources()) {
                String loc = src.locator();
                if (loc != null && loc.startsWith("file:") && checked.add(loc) && own.is(loc)) ownerNotes.add(loc);
            }
            for (Graph.Node n : g.nodes()) if ("person".equals(n.kind()) && !FamilyQuestions.placeholder(n.label())) {
                String h = han(untitled(n.label()));
                if (h.matches("\\p{IsHan}{3,6}")) fullHan.add(h);
            }
            for (Graph.Node n : g.nodes()) {
                if (!"person".equals(n.kind())) continue;
                List<String> ns = new ArrayList<>();
                ns.add(n.label());
                for (String a : n.aliases()) if (!ns.contains(a)) ns.add(a);
                names.put(n.id(), ns);
                label.put(n.id(), n.label());
                kind.put(n.id(), kindOf(n.label()));
            }
            for (Graph.Edge e : g.edges()) {
                if (e.predicate().equals("is filed under") || e.predicate().equals("mentions")) continue;
                if (kind.containsKey(e.from())) sidesOf.computeIfAbsent(e.from(), k -> new ArrayList<>()).add(new String[]{e.findingId(), "subject"});
                if (kind.containsKey(e.to())) sidesOf.computeIfAbsent(e.to(), k -> new ArrayList<>()).add(new String[]{e.findingId(), "object"});
                if (twoPeople(e.predicate()) && !FamilyKin.gone(e)) kin.add(new String[]{e.findingId(), e.predicate(), e.from(), e.to()});
                if (!FamilyKin.gone(e)) facts.computeIfAbsent(e.predicate(), k -> new ArrayList<>()).add(new String[]{e.findingId(), e.from(), e.to()});
                if (e.predicate().equals("sex") && !FamilyKin.gone(e)) {
                    Graph.Node to = g.node(e.to());
                    String sx = FamilyKin.sexWord(to == null ? "" : to.label());
                    if (!sx.isEmpty()) sexClaims.add(new String[]{e.findingId(), e.from(), sx});
                }
                // the sex a relation's own words give one of its people ("森田春子の夫 森田花夫", "Tom Hale's daughter Ruth"), read as the
                // family's close relatives are read ({@link FamilyClose#sexInClaim})
                if (FamilyKin.gone(e) || !Set.of("parent-of", "child-of", "married-to", "sibling-of").contains(e.predicate())) continue;
                Finding rf = claims.get(e.findingId());
                for (String[] who : new String[][]{{e.from(), "subject"}, {e.to(), "object"}}) {
                    if (!kind.containsKey(who[0])) continue;
                    String sx = FamilyClose.sexInClaim(g, rf, e, who[0]);
                    if (!sx.isEmpty()) sexInWords.add(new String[]{e.findingId(), who[1], who[0], sx});
                }
            }
            for (String p : Graph.differentPairs(store)) {
                String[] two = p.split("\t");
                if (two.length == 2) apart.add(Graph.pair(g.nodeIdOf(two[0]), g.nodeIdOf(two[1])));
                if (two.length == 2) apart.add(Graph.pair(two[0], two[1]));
            }
            for (String f : FamilyNameHistory.of(g).familyParts()) familyParts.add(String.join(" ", words(f)));
            // a reading in kana gives a Japanese name family name first: its first word, in Latin letters, is a family name (もりた けんじ: Morita)
            for (List<String> ns : names.values()) for (String n : ns) {
                String[] parts = n.strip().split("[\\s　・]+");
                if (parts.length == 2 && FamilyForms.script(n).equals("kana")) familyParts.add(String.join(" ", words(FamilyForms.hepburn(parts[0]))));
            }
            for (String f : familyParts) if (FamilyForms.script(f).equals("latin")) familyKeys.add(FamilyForms.latinKey(f));
            // a reading on file of a whole name in characters reads its family name too: 森田健二 read もりた けんじ reads 森田 もりた
            for (List<String> ns : names.values()) {
                String h = null;
                for (String n : ns) if (h == null && FamilyForms.script(untitled(n)).equals("han")) h = han(untitled(n));
                if (h == null) continue;
                String[] part = hanParts(h);
                if (part == null) continue;
                for (String n : ns) {
                    String[] w = n.strip().split("[\\s　・]+");
                    if (w.length == 2 && FamilyForms.script(n).equals("kana")) { List<String> rs = familyReadings.computeIfAbsent(part[0], k -> new ArrayList<>()); if (!rs.contains(w[0])) rs.add(w[0]); }
                }
            }
            ids.putAll(PersonIds.all(store, g));
            // the list of names says which source gave each other name of a person: that source names the person so
            Path rows = Graph.aliasSourcesFile(store);
            if (Files.exists(rows)) for (String line : Files.readAllLines(rows, StandardCharsets.UTF_8)) {
                String[] r = line.split("\t");
                if (r.length < 3) continue;
                String entry = g.nodeIdOf(r[0]);
                if (!kind.containsKey(entry)) continue;
                readGave.computeIfAbsent(entry, k -> new HashSet<>()).add(Vocabulary.norm(r[1]));
                List<String[]> given = aliasSources.computeIfAbsent(r[2], k -> new ArrayList<>());
                given.add(new String[]{entry, r[0]});
                if (!detached.getOrDefault(entry, Set.of()).contains(r[1])) given.add(new String[]{entry, r[1]});
            }
            index();
            detach();
            index();
            // to a fixed point: a link made in one round (a spouse, say) can be the fact that agrees for the next
            for (int round = 0; round < 8; round++) {
                int before = links.size();
                // the readings the rules of this round still want: the last round's are the questions for the model
                wantedReadings.clear();
                wantedDirected.clear();
                sameBreath();
                alsoWritten();
                pages();
                sameName();
                readings();
                shortForms();
                throughTheSource();
                selfIntroduction();
                described();
                linkNotes();
                modelReadings();
                readingsForForms();
                households();
                // a changed name joins two people on what the other rules settled, the families above all: it waits for a round in which
                // they settle nothing more, so a parent written in two scripts is one parent when it looks
                if (links.size() == before) changedNames();
                promote();
                if (links.size() == before) break;
            }
            variants();
            return result(stamp);
        }

        // ── kinds, sides, groups ─────────────────────────────────────────────────────────────────────────────────────

        Kind kindOf(String l) {
            if (pageSubject(l) != null) return Kind.PAGE;
            // a description in Japanese ("森田健二の父の父") is one too
            if (FamilyQuestions.placeholder(l) || unnamed(l) || descriptionJa(l) != null) return Kind.DESCRIPTION;
            return shortName(l) ? Kind.SHORT : Kind.FULL;
        }

        /** A given name or a family name alone, with a title or without, or a family name with initials. */
        boolean shortName(String written) {
            // a note in brackets tells namesakes apart ("Kenji (the shopkeeper)"): a name of its own; one capitalised word there is another name,
            // and an index's relation word ("(father)") is no part of the name
            Matcher note = Pattern.compile("[(（]([^)）]*)[)）]").matcher(written);
            while (note.find()) if (note(note.group(1))[0].equals("namesake")) return false;
            String u = untitled(written.replaceAll("\\s*[(（][^)）]*[)）]\\s*$", "")).strip();
            if (u.isEmpty()) return false;
            if (!cjk(u)) return words(u).size() == 1 || initials(u);
            if (u.matches(".*[\\s　・].*")) return false;
            if (FamilyForms.script(u).equals("kana")) return true;
            String h = han(u);
            if (h.codePointCount(0, h.length()) <= 2) return true;
            // three characters or more: a given name alone is the end of a full name the library holds (健太郎 of 森田健太郎); the beginning of
            // one may be a full name of its own (森田正 of 森田正一)
            for (String f : fullHan) if (f.length() > h.length() && f.endsWith(h)) return true;
            return false;
        }

        String find(String x) {
            String r = x;
            int guard = 0;
            while (up.containsKey(r) && guard++ < 10000) r = up.get(r);
            return r;
        }

        Set<String> group(String root) { Set<String> m = members.get(root); return m != null ? m : Set.of(root); }

        /** The entry one side of a claim is now: where the pass put it, else its node, as the groups join them. */
        String at(String claim, boolean subject, String node) {
            String to = sides.get(Graph.Links.side(claim, subject));
            return find(to != null ? to : node);
        }

        void union(String a, String b) {
            String ra = find(a), rb = find(b);
            if (ra.equals(rb)) return;
            Set<String> m = new LinkedHashSet<>(group(ra));
            m.addAll(group(rb));
            up.put(rb, ra);
            members.remove(rb);
            members.put(ra, m);
        }

        Kind kind(String entry) { return kind.getOrDefault(entry, Kind.FULL); }

        String written(String claim, String side) {
            Finding f = claims.get(claim);
            return f == null || f.triple() == null ? "" : side.equals("subject") ? f.triple().subject() : f.triple().object();
        }

        List<String> sources(String claim) {
            Finding f = claims.get(claim);
            List<String> out = new ArrayList<>();
            if (f != null) for (Finding.Source s : f.sources()) out.add(s.locator());
            return out;
        }

        /** A label as a sentence names an entry. */
        String nameOf(String entry) {
            String r = find(entry);
            String best = bestLabel(r);
            return best == null ? label.getOrDefault(entry, entry) : best;
        }

        // ── the facts of a group ──────────────────────────────────────────────────────────────────────────────────────

        Set<String> related(String root, String relation, boolean fromSide) {
            Set<String> out = new LinkedHashSet<>();
            for (String[] k : kin) {
                if (!k[1].equals(relation)) continue;
                String a = at(k[0], true, k[2]), b = at(k[0], false, k[3]);
                if (fromSide && a.equals(root)) out.add(b);
                if (!fromSide && b.equals(root)) out.add(a);
            }
            out.remove(root);
            return out;
        }

        Set<String> parents(String root) { Set<String> p = related(root, "child-of", true); p.addAll(related(root, "parent-of", false)); return p; }
        Set<String> children(String root) { Set<String> c = related(root, "parent-of", true); c.addAll(related(root, "child-of", false)); return c; }
        Set<String> spouses(String root) { Set<String> s = related(root, "married-to", true); s.addAll(related(root, "married-to", false)); return s; }
        Set<String> siblings(String root) {
            Set<String> s = related(root, "sibling-of", true);
            s.addAll(related(root, "sibling-of", false));
            for (String p : parents(root)) s.addAll(children(p));
            s.remove(root);
            return s;
        }

        /** The years a group was born or died in, by the claims about its members: "born" or "died". */
        Set<Integer> years(String root, String what) {
            Set<Integer> out = new LinkedHashSet<>();
            for (String m : group(root)) for (String[] s : sidesOf.getOrDefault(m, List.of())) addYear(out, s[0], s[1], root, what);
            for (Map.Entry<String, String> e : sides.entrySet()) if (find(e.getValue()).equals(root)) {
                String[] k = e.getKey().split("\\|");
                addYear(out, k[0], k[1], root, what);
            }
            return out;
        }

        private void addYear(Set<Integer> out, String claim, String side, String root, String what) {
            Finding f = claims.get(claim);
            if (f == null || f.triple() == null || !side.equals("subject") || gone(f)) return;
            if (!at(claim, true, g.nodeIdOf(f.triple().subject())).equals(root)) return;
            String p = g.predicateOf(f.triple().predicate());
            FamilyDate d = null;
            if (what.equals("born") && p.equals("born-on") || what.equals("died") && p.equals("died-on")) d = FamilyDate.parse(f.triple().object());
            else if (what.equals("born") && p.equals("born-in") || what.equals("died") && (p.equals("died-in") || p.equals("buried-in"))) d = FamilyChecks.claimDate(f);
            if (d != null && d.year() > 0) out.add(d.year());
        }

        /** How many birth parents one group, or two as one, has in each script, a name that is part of another's counted once. */
        Map<String, Integer> parentsByScript(String ra, String rb) {
            Set<String> ps = new LinkedHashSet<>(parents(ra));
            if (rb != null) ps.addAll(parents(rb));
            ps.removeIf(x -> kind(x) == Kind.DESCRIPTION || kind(x) == Kind.PAGE);
            Map<String, List<String>> byScript = new HashMap<>();
            for (String x : ps) byScript.computeIfAbsent(FamilyForms.script(nameOf(x)), k -> new ArrayList<>()).add(nameOf(x));
            Map<String, Integer> out = new HashMap<>();
            for (Map.Entry<String, List<String>> e : byScript.entrySet()) {
                List<String> named = e.getValue();
                named.removeIf(x -> named.stream().anyMatch(o -> !o.equals(x) && FamilyChecks.partOfName(x, o)));
                out.put(e.getKey(), named.size());
            }
            return out;
        }

        /** The places a group was born in, as the claims name them. */
        Set<String> places(String root) {
            Set<String> out = new LinkedHashSet<>();
            for (String[] x : facts.getOrDefault("born-in", List.of())) {
                if (!at(x[0], true, x[1]).equals(root)) continue;
                Graph.Node n = g.node(x[2]);
                if (n != null) out.add(n.label());
            }
            return out;
        }

        /** The sexes the claims give a group's members. */
        Set<String> sexes(String root) {
            Set<String> out = new LinkedHashSet<>();
            for (String[] x : sexClaims) if (at(x[0], true, x[1]).equals(root)) out.add(x[2]);
            return out;
        }

        /**
         * A group's recorded sexes as the family's pages read them: the sex claims', and where none is filed, what the words of its
         * relations say of it (a wife, a son), and else the other one of a married couple whose husband or wife is filed a man or a woman,
         * as the pages word a marriage ({@link FamilyClose}).
         */
        Set<String> recordedSexes(String root) {
            Set<String> out = sexes(root);
            if (!out.isEmpty()) return out;
            // a description says its own sex: "the owner of this library's father" is a man, whatever the words of a claim about him say
            out = new LinkedHashSet<>();
            for (String m : group(root)) if (kind(m) == Kind.DESCRIPTION) { String sx = describedSex(label.getOrDefault(m, "")); if (!sx.isEmpty()) out.add(sx); }
            if (!out.isEmpty()) return out;
            for (String[] x : sexInWords) if (at(x[0], x[1].equals("subject"), x[2]).equals(root)) out.add(x[3]);
            if (!out.isEmpty()) return out;
            Set<String> theirs = new LinkedHashSet<>();
            for (String s : spouses(root)) theirs.addAll(sexes(s));
            if (theirs.size() == 1) out.add(theirs.contains("male") ? "female" : "male");
            return out;
        }

        static boolean gone(Finding f) { return f.state() == Finding.State.retired || f.state() == Finding.State.superseded || f.state() == Finding.State.disputed; }

        /** Why two groups cannot be one person, as a phrase; null when nothing says so. */
        String blocked(String a, String b) {
            String ra = find(a), rb = find(b);
            if (ra.equals(rb)) return null;
            for (String x : group(ra)) for (String y : group(rb)) if (apart.contains(Graph.pair(x, y))) return "the family said they are two people";

            // a claim that they are parent and child, husband and wife, or brother and sister; between two people of names, any relation ("relative
            // of" between a person and the words a note describes somebody with may be the reader's way of saying who the words are)
            boolean named = kind(ra) == Kind.FULL && kind(rb) == Kind.FULL;
            for (String[] k : kin) {
                if (!KIN.contains(k[1]) && !named) continue;
                String f = at(k[0], true, k[2]), t = at(k[0], false, k[3]);
                if (f.equals(ra) && t.equals(rb) || f.equals(rb) && t.equals(ra)) return "a claim relates them (" + k[1] + ")";
            }
            // no birth year of one within three years of any of the other's; one dead before the other was born
            Set<Integer> ba = years(ra, "born"), bb = years(rb, "born");
            if (!ba.isEmpty() && !bb.isEmpty() && ba.stream().noneMatch(x -> bb.stream().anyMatch(y -> Math.abs(x - y) <= 3))) return "their birth years are more than three apart";
            Set<Integer> da = years(ra, "died"), db = years(rb, "died");
            if (!da.isEmpty() && !bb.isEmpty() && da.stream().allMatch(d -> bb.stream().allMatch(y -> d < y))) return "one died before the other was born";
            if (!db.isEmpty() && !ba.isEmpty() && db.stream().allMatch(d -> ba.stream().allMatch(y -> d < y))) return "one died before the other was born";
            // a sex each, and different ones (a person the claims give both sexes says nothing here)
            Set<String> sa = recordedSexes(ra), sb = recordedSexes(rb);
            if (sa.size() == 1 && sb.size() == 1 && !sa.equals(sb)) return "their recorded sexes differ";
            // born in two places, written in one script (a place in characters and one in Latin letters are not compared)
            Set<String> pa = places(ra), pb = places(rb);
            boolean compared = false, same = false;
            for (String x : pa) for (String y : pb) if (FamilyForms.script(x).equals(FamilyForms.script(y))) { compared = true; same |= FamilySame.samePlace(x, y); }
            if (compared && !same) return "they were born in two places";
            // more birth parents than a person has, among the parents written in one script, that joining the two would make. Two sources that
            // name a parent differently (a step-mother, a tree's slip) are common: where three or more facts agree (a birth year, a death year, a
            // parent, a husband or wife, a child), the parent that differs is noted on the join and blocks nothing
            Map<String, Integer> alone = new HashMap<>(), together = parentsByScript(ra, rb);
            parentsByScript(ra, null).forEach((k, v) -> alone.merge(k, v, Math::max));
            parentsByScript(rb, null).forEach((k, v) -> alone.merge(k, v, Math::max));
            for (Map.Entry<String, Integer> e : together.entrySet())
                if (e.getValue() > 2 && e.getValue() > alone.getOrDefault(e.getKey(), 0)) {
                    List<String[]> facts = sharedFacts(ra, rb);
                    if (facts.size() < 3) return "as one person they would have " + e.getValue() + " birth parents";
                    differs.put(Graph.pair(ra, rb), "the sources name a parent differently, so as one person they would have " + e.getValue() + " birth parents; " + facts.size() + " facts agree ("
                            + String.join("; ", facts.stream().map(x -> x[0]).toList()) + "), so the parent that differs is noted, and asked about, not held against the join");
                }
            // two different ids on one family-tree site
            List<String> why = new ArrayList<>();
            Set<PersonIds.Id> ia = new HashSet<>(), ib = new HashSet<>();
            for (String m : group(ra)) ia.addAll(ids.getOrDefault(m, Set.of()));
            for (String m : group(rb)) ib.addAll(ids.getOrDefault(m, Set.of()));
            if (!ia.isEmpty() && !ib.isEmpty() && PersonIds.compare(ia, ib, why).equals("different")) return "a family-tree site gives them two ids";
            // two names in characters that differ by one character are two people (L8), whichever members of the two groups carry them: a
            // name in Latin letters that reads as both never joins them into one. Last, so that any other reason is the one given
            for (String x : group(ra)) for (String y : group(rb)) if (oneCharacterApart(x, y)) return "the names " + label.get(x) + " and " + label.get(y) + " differ by one character";
            return null;
        }

        /**
         * The facts two groups share, one of each kind: a birth year, a death year, a birthplace, and a parent, a husband or wife, a child or a
         * brother or sister the two sources both relate them to, the same entry (by name, one person by the evidence or not: two sources that
         * both name the same wife say something of the two). What weighs a parent the sources give differently ({@link #blocked}).
         */
        List<String[]> sharedFacts(String ra, String rb) {
            List<String[]> out = new ArrayList<>();
            for (String what : new String[]{"born", "died"}) {
                Set<Integer> x = years(ra, what), y = years(rb, what);
                for (int i : x) { if (y.stream().anyMatch(j -> Math.abs(i - j) <= 1)) { out.add(new String[]{"both " + what + " in " + i}); break; } }
            }
            for (String x : places(ra)) { boolean hit = false; for (String y : places(rb)) if (FamilyForms.script(x).equals(FamilyForms.script(y)) && FamilySame.samePlace(x, y)) hit = true; if (hit) { out.add(new String[]{"both born in " + x}); break; } }
            for (String[] w : new String[][]{{"parent", "both are children of "}, {"spouse", "both were married to "}, {"child", "both are parents of "}, {"sibling", "both are brothers or sisters of "}}) {
                Set<String> x = switch (w[0]) { case "parent" -> parents(ra); case "spouse" -> spouses(ra); case "child" -> children(ra); default -> siblings(ra); };
                Set<String> y = switch (w[0]) { case "parent" -> parents(rb); case "spouse" -> spouses(rb); case "child" -> children(rb); default -> siblings(rb); };
                for (String p : x) if (y.contains(p) && kind(p) == Kind.FULL) { out.add(new String[]{w[1] + nameOf(p)}); break; }
            }
            return out;
        }

        /** Whether two entries carry full names in characters of one length that differ by exactly one character: 源三郎 and 源四郎 are two people (L8). */
        boolean oneCharacterApart(String x, String y) {
            if (kind(x) != Kind.FULL || kind(y) != Kind.FULL || ownerSaidOne.contains(Graph.pair(x, y))) return false;
            // every name in characters the two entries carry, the label and the other names alike: a name in Latin letters filed with 源四郎
            // among its other names is 源四郎's
            for (String a : hanNames(x)) for (String b : hanNames(y)) {
                if (a.length() != b.length() || a.length() < 3) continue;
                int diff = 0;
                for (int k = 0; k < a.length(); k++) if (a.charAt(k) != b.charAt(k)) diff++;
                if (diff == 1) return true;
            }
            return false;
        }

        /** The full names in characters an entry carries, the label and the other names, as they are compared. */
        List<String> hanNames(String entry) {
            List<String> out = new ArrayList<>();
            for (String n : names.getOrDefault(entry, List.of(label.getOrDefault(entry, "")))) {
                if (detached.getOrDefault(entry, Set.of()).contains(n)) continue;
                String h = han(untitled(n));
                if (FamilyForms.script(h).equals("han") && h.matches("\\p{IsHan}{3,6}") && !shortName(n)) out.add(h);
            }
            return out;
        }

        /** A parent two groups' sources give differently, noted on their join instead of blocking it ({@link #blocked}), by the pair of roots. */
        final Map<String, String> differs = new HashMap<>();

        /** The pairs of entries the owner's own notes say are one person's names ("also written", {@link #alsoWritten}): the one-character rule yields to the family's word. */
        final Set<String> ownerSaidOne = new HashSet<>();

        /** The facts that agree between two groups, each as a phrase with the claims it rests on: a birth or death year, a parent, a husband or wife, a child. */
        List<String[]> agree(String a, String b) {
            String ra = find(a), rb = find(b);
            List<String[]> out = new ArrayList<>();
            for (String what : new String[]{"born", "died"}) {
                Set<Integer> x = years(ra, what), y = years(rb, what);
                boolean hit = false;
                for (int i : x) for (int j : y) if (Math.abs(i - j) <= 1) hit = true;
                if (hit) out.add(new String[]{"both " + what + " in " + x.iterator().next()});
            }
            // a relative counts when the relative is one person by the evidence, not only by a name the two sources happen to share
            for (String[] w : new String[][]{{"parent", "both are children of "}, {"spouse", "both were married to "}, {"child", "both are parents of "}}) {
                Set<String> x = w[0].equals("parent") ? parents(ra) : w[0].equals("spouse") ? spouses(ra) : children(ra);
                Set<String> y = w[0].equals("parent") ? parents(rb) : w[0].equals("spouse") ? spouses(rb) : children(rb);
                for (String p : x) if (y.contains(p) && oneByEvidence(p)) { out.add(new String[]{w[1] + nameOf(p)}); break; }
            }
            // a book's index writes a relation word beside a name, "Morita, Isamu (father)": a fact about the book's narrator, whom the notes
            // name; the other entry stands in that relation to the narrator by the claims
            for (String[] pair : new String[][]{{ra, rb}, {rb, ra}}) {
                boolean hit = false;
                for (String[] rn : indexRelations(pair[0])) {
                    String word = bare(rn[0]).replaceAll("^(?:step|half|adoptive|adopted|foster)[- ]?", ""), narrator = rn[1];
                    Set<String> who = switch (word) {
                        case "father", "mother", "parent", "父", "母" -> parents(narrator);
                        case "son", "daughter", "child", "長男", "次男", "三男", "長女", "次女", "息子", "娘" -> children(narrator);
                        case "wife", "husband", "spouse", "妻", "夫" -> spouses(narrator);
                        case "brother", "sister", "兄", "弟", "姉", "妹" -> siblings(narrator);
                        default -> Set.of();
                    };
                    if (who.contains(pair[1])) { out.add(new String[]{"both are the " + rn[0] + " of " + nameOf(narrator) + ", as the book's index says"}); hit = true; break; }
                }
                if (hit) break;
            }
            return out;
        }

        /**
         * The relation words a book's index writes beside a group's names ("(father)"), each with the group of the book's narrator, whom the
         * family's notes name as its writer ({@link #writerOf}): {the word, the narrator's group}. Nothing where the notes name no writer.
         */
        List<String[]> indexRelations(String root) {
            List<String[]> out = new ArrayList<>();
            for (String m : group(root)) for (String[] s : sidesOf.getOrDefault(m, List.of())) {
                List<String> words = relationNotes(written(s[0], s[1]));
                if (words.isEmpty()) continue;
                for (String src : sources(s[0])) {
                    if (!src.startsWith("file:")) continue;
                    Object[] w = writerOf(sourceName(List.of(src)));
                    if (w == null) continue;
                    for (String word : words) out.add(new String[]{word, find((String) w[0])});
                }
            }
            return out;
        }

        /**
         * Whether a person is one person by the evidence and not by a name alone: two entries of theirs were linked, or two sources that are
         * not one another give them the same birth or death year.
         */
        boolean oneByEvidence(String person) {
            String root = find(person);
            if (group(root).size() > 1) return true;
            for (String what : new String[]{"born", "died"}) {
                Map<Integer, Set<String>> by = new HashMap<>();
                for (String m : group(root)) for (String[] s : sidesOf.getOrDefault(m, List.of())) {
                    Set<Integer> y = new LinkedHashSet<>();
                    addYear(y, s[0], s[1], root, what);
                    for (int year : y) for (String src : sources(s[0])) by.computeIfAbsent(year, k -> new HashSet<>()).add(origin(src));
                }
                for (Map.Entry<Integer, Set<String>> a : by.entrySet()) for (Map.Entry<Integer, Set<String>> b : by.entrySet()) {
                    if (Math.abs(a.getKey() - b.getKey()) > 1) continue;
                    Set<String> both = new HashSet<>(a.getValue()); both.addAll(b.getValue());
                    if (both.size() >= 2) return true;
                }
            }
            return false;
        }

        // ── the links ────────────────────────────────────────────────────────────────────────────────────────────────

        /** Two entries joined, when nothing blocks it and a proved or probable grade allows it; a possible link is kept to be shown. */
        boolean join(String a, String b, String rule, Grade grade, List<String> evidence, String why) {
            if (find(a).equals(find(b))) return false;
            if (kind(a) == Kind.SHORT || kind(b) == Kind.SHORT) return false;   // a name alone joins mention by mention only
            String block = blocked(a, b);
            if (block != null) {
                // a join the one-character rule refuses stays a possible link, to be shown: the family may say otherwise
                if (block.contains("differ by one character") && grade != Grade.possible && links.stream().noneMatch(l -> l.grade() == Grade.possible && l.node().equals(a) && l.person().equals(b)))
                    links.add(new Link("", "", a, label.getOrDefault(a, a), b, label.getOrDefault(b, b), rule, Grade.possible, evidence, why + " Not joined: " + block + ", and two such names are two people until the family says otherwise."));
                return false;
            }
            String noted = differs.remove(Graph.pair(find(a), find(b)));
            if (noted != null && grade != Grade.possible) why = why + " They differ in one thing: " + noted + ".";
            if (grade == Grade.possible) {
                for (Link l : links) if (l.grade() == Grade.possible && l.node().equals(a) && l.person().equals(b)) return false;
                links.add(new Link("", "", a, label.getOrDefault(a, a), b, label.getOrDefault(b, b), rule, grade, evidence, why));
                return false;
            }
            union(b, a);
            links.add(new Link("", "", a, label.getOrDefault(a, a), b, label.getOrDefault(b, b), rule, grade, evidence, why));
            return true;
        }

        /** One side of one claim linked to an entry, unless the claim itself says it is somebody else. */
        boolean mention(String claim, String side, String node, String to, String rule, Grade grade, List<String> evidence, String why) {
            String key = Graph.Links.side(claim, side.equals("subject"));
            if (mentionLinks.containsKey(key) || !fits(claim, side, node, to)) return false;
            String target = find(to);
            sides.put(key, target);
            Link l = new Link(claim, side, node, written(claim, side), target, label.getOrDefault(target, target), rule, grade, evidence, why);
            mentionLinks.put(key, l);
            links.add(l);
            return true;
        }

        /** Whether one side of a claim can be a person: nothing the claim itself says, and nothing the family said, makes it somebody else. */
        boolean fits(String claim, String side, String node, String to) {
            Finding f = claims.get(claim);
            if (f == null || f.triple() == null) return false;
            String target = find(to);
            // the claim relates the mention to the very person it would be: a person is not their own parent, husband or brother
            String rel = g.predicateOf(f.triple().predicate());
            boolean subject = side.equals("subject");
            String other = subject ? at(claim, false, g.nodeIdOf(f.triple().object())) : at(claim, true, g.nodeIdOf(f.triple().subject()));
            if (twoPeople(rel) && other.equals(target)) return false;
            // another claim already relates the two another way: a man's son is not also his wife's husband
            if (KIN.contains(rel)) for (String[] k : kin) {
                if (k[0].equals(claim)) continue;
                String a = at(k[0], true, k[2]), b = at(k[0], false, k[3]);
                if ((a.equals(target) && b.equals(other) || a.equals(other) && b.equals(target)) && !family(k[1]).equals(family(rel))) return false;
            }
            // a birth or a death the claim dates, far from the person's own
            if (subject && (rel.equals("born-on") || rel.equals("died-on"))) {
                FamilyDate d = FamilyDate.parse(f.triple().object());
                Set<Integer> known = years(target, rel.startsWith("born") ? "born" : "died");
                if (d != null && d.year() > 0 && !known.isEmpty() && known.stream().noneMatch(y -> Math.abs(y - d.year()) <= 3)) return false;
            }
            if (subject && rel.equals("sex")) {
                String s = FamilyKin.sexWord(f.triple().object());
                Set<String> known = sexes(target);
                if (!s.isEmpty() && !known.isEmpty() && !known.contains(s)) return false;
            }
            // a parent dead before the child was born (a year allowed, for a father): the child of a man who died in 1933 was not born in 1940
            if (rel.equals("child-of") || rel.equals("parent-of")) {
                boolean childSide = rel.equals("child-of") == subject;
                Set<Integer> childBorn = childSide ? years(target, "born") : years(other, "born");
                Set<Integer> parentDied = childSide ? years(other, "died") : years(target, "died");
                if (!childBorn.isEmpty() && !parentDied.isEmpty() && childBorn.stream().allMatch(b -> parentDied.stream().allMatch(d -> b > d + 1))) return false;
            }
            for (String x : group(target)) if (apart.contains(Graph.pair(node, x))) return false;
            return true;
        }

        /**
         * A family name or a given name alone that holds full names as other names: in genealogy's view it holds them no longer, and each claim
         * that writes one of them is about the entry of that full name (or one of its own), not about everybody the name alone stands for.
         */
        void detach() {
            for (String w : new ArrayList<>(kind.keySet())) {
                if (kind(w) != Kind.SHORT) continue;
                String own = untitled(label.get(w));
                Set<String> longer = new LinkedHashSet<>();
                // only a name a read gave it: an entry the person filed under one word, with its whole name as another name, is theirs to keep
                Set<String> read = readGave.getOrDefault(w, Set.of());
                for (String n : names.get(w)) if (!n.equals(label.get(w)) && read.contains(Vocabulary.norm(n)) && !shortName(n) && !untitled(n).equalsIgnoreCase(own) && pageSubject(n) == null && !FamilyQuestions.placeholder(n)) longer.add(n);
                if (longer.isEmpty()) continue;
                detached.put(w, longer);
                Set<String> keys = new HashSet<>();
                for (String n : longer) for (String f : forms(n)) { String k = key(f); if (!k.isEmpty()) keys.add(k); keys.add(Vocabulary.norm(n)); }
                for (String[] s : sidesOf.getOrDefault(w, List.of())) {
                    String wr = written(s[0], s[1]);
                    boolean full = false;
                    for (String f : forms(wr)) if (keys.contains(key(f)) || keys.contains(Vocabulary.norm(wr))) full = true;
                    if (!full || shortName(wr)) continue;
                    String to = holder(wr, w);
                    if (to == null) {
                        // a full name with no entry of its own yet: one of its own, as the graph makes one for a name
                        to = Vocabulary.norm(wr);
                        if (!kind.containsKey(to)) { kind.put(to, kindOf(wr)); names.put(to, new ArrayList<>(List.of(wr))); label.put(to, wr); }
                    }
                    sidesOf.computeIfAbsent(to, k -> new ArrayList<>()).add(s);
                    sides.put(Graph.Links.side(s[0], s[1].equals("subject")), to);
                    movedFrom.put(Graph.Links.side(s[0], s[1].equals("subject")), w);
                }
            }
        }

        /** The entry other than {@code not} that holds a full name as its own or another name; null for none. */
        String holder(String written, String not) {
            for (String f : forms(written)) {
                String k = key(f);
                if (k.isEmpty()) continue;
                for (String e : byKey.getOrDefault(k, List.of())) if (!e.equals(not)) return e;
            }
            return null;
        }

        /** The keys of the full names each entry of a full name carries, for {@link #holder}. */
        void index() {
            byKey.clear();
            for (String e : kind.keySet()) {
                if (kind(e) != Kind.FULL) continue;
                for (String n : names.get(e)) {
                    if (detached.getOrDefault(e, Set.of()).contains(n)) continue;
                    for (String f : forms(n)) { String k = key(f); if (!k.isEmpty()) { List<String> es = byKey.computeIfAbsent(k, x -> new ArrayList<>()); if (!es.contains(e)) es.add(e); } }
                }
            }
        }

        /**
         * An entry of a full name every mention of which is linked to one person is one person with them as a whole entry: "Tom Hale", each time
         * a source writes it, is Tom Arthur Hale.
         */
        void promote() {
            for (String e : new ArrayList<>(kind.keySet())) {
                if (kind(e) != Kind.FULL || g.node(e) == null) continue;
                List<String[]> ss = sidesOf.getOrDefault(e, List.of());
                String to = null;
                boolean all = !ss.isEmpty();
                List<String> ev = new ArrayList<>();
                Link first = null;
                for (String[] x : ss) {
                    String key = Graph.Links.side(x[0], x[1].equals("subject"));
                    Link l = mentionLinks.get(key);
                    if (l == null) { if (!sides.containsKey(key)) all = false; continue; }
                    String t = find(l.person());
                    if (to == null) to = t; else if (!to.equals(t)) all = false;
                    ev.add(x[0]);
                    if (first == null) first = l;
                }
                if (!all || to == null || find(e).equals(to)) continue;
                join(e, to, first.rule(), Grade.probable, ev, first.why() + " Every claim that writes \"" + label.get(e) + "\" is about this person.");
            }
        }

        /**
         * L1 and L9 in one claim: a name claim says the person it is about carried a name. "Mrs. <a man's full name>" is a form of address
         * for his wife: the claim's person is his wife, when the library holds one wife of his. A name written alone that carried a full name
         * is the person of that full name, when the same source gives that person the name. (Whether an entry of a full name is the entry of
         * the name it carried is the same name's question: {@link #sameName}.)
         */
        void sameBreath() {
            Map<String, Map<String, Set<String>>> carried = nameSources();
            for (Finding f : claims.values()) {
                if (gone(f) || !FamilyNameHistory.isNameClaim(f)) continue;
                String subjectNode = g.nodeIdOf(f.triple().subject());
                if (!kind.containsKey(subjectNode) || kind(subjectNode) == Kind.PAGE || kind(subjectNode) == Kind.DESCRIPTION) continue;
                String who = at(f.id(), true, subjectNode);
                String value = FamilyNameHistory.written(f);
                if (value.isBlank()) continue;
                if (titleSex(value).equals("female") && value.matches("(?i)^mrs\\.?\\s+.+") && !shortName(value)) {
                    // "Mrs. Tom Hale": his wife, when he has exactly one
                    String husband = holder(untitled(value), null);
                    if (husband == null) continue;
                    Set<String> wives = new LinkedHashSet<>();
                    for (String x : spouses(find(husband))) if (!sexes(x).contains("male") && kind(x) == Kind.FULL) wives.add(x);
                    if (wives.size() != 1) continue;
                    String to = wives.iterator().next();
                    if (find(to).equals(who)) continue;
                    String why = "\"" + value + "\" is a form of address for the wife of " + nameOf(husband) + ", and the library holds one wife of his, " + nameOf(to) + ".";
                    if (kind(subjectNode) == Kind.SHORT) mention(f.id(), "subject", subjectNode, to, "L9", Grade.probable, List.of(f.id()), why);
                    else join(subjectNode, to, "L9", Grade.probable, List.of(f.id()), why);
                    continue;
                }
                if (kind(subjectNode) != Kind.SHORT || shortName(value)) continue;
                // a name alone that carried a full name: the entry the same source gives that full name
                String k = null;
                for (String form : forms(value)) if (!key(form).isEmpty()) { k = key(form); break; }
                if (k == null) continue;
                Set<String> src = new HashSet<>(prose(sources(f.id())));
                Set<String> to = new LinkedHashSet<>();
                for (Map.Entry<String, Set<String>> e : carried.getOrDefault(k, Map.of()).entrySet())
                    if (e.getValue().stream().anyMatch(src::contains) && !find(e.getKey()).equals(who)) to.add(find(e.getKey()));
                if (to.size() != 1) continue;
                mention(f.id(), "subject", subjectNode, to.iterator().next(), "L1", Grade.probable, List.of(f.id()),
                        "One claim gives both names: " + f.triple().subject() + " carried the name " + value + ", and " + sourceName(sources(f.id())) + " gives that name to " + nameOf(to.iterator().next()) + ".");
            }
        }

        /**
         * The full names each entry of a full name carries, by key, with the sources that give each: the claims that write it, the list of names
         * where it says which source gave a name, and the entry's own name claims. A name nothing says the source of has none.
         */
        Map<String, Map<String, Set<String>>> nameSources() {
            entryLanguages.clear();   // the groups may have grown since the last round
            Map<String, Map<String, Set<String>>> out = new LinkedHashMap<>();
            for (String e : kind.keySet()) {
                if (kind(e) != Kind.FULL) continue;
                for (String n : names.get(e)) if (!detached.getOrDefault(e, Set.of()).contains(n)) for (String f : forms(n)) for (String k : keys(e, f)) out.computeIfAbsent(k, x -> new LinkedHashMap<>()).computeIfAbsent(e, x -> new LinkedHashSet<>());
                for (String[] x : sidesOf.getOrDefault(e, List.of())) for (String f : forms(written(x[0], x[1]))) for (String k : keys(e, f)) out.computeIfAbsent(k, y -> new LinkedHashMap<>()).computeIfAbsent(e, y -> new LinkedHashSet<>()).addAll(sources(x[0]));
            }
            for (Map.Entry<String, List<String[]>> a : aliasSources.entrySet()) for (String[] x : a.getValue()) {
                if (kind(x[0]) != Kind.FULL) continue;
                for (String f : forms(x[1])) for (String k : keys(x[0], f)) out.computeIfAbsent(k, y -> new LinkedHashMap<>()).computeIfAbsent(x[0], y -> new LinkedHashSet<>()).add(a.getKey());
            }
            for (Finding f : claims.values()) {
                if (gone(f) || !FamilyNameHistory.isNameClaim(f)) continue;
                String e = g.nodeIdOf(f.triple().subject());
                String value = FamilyNameHistory.written(f);
                if (kind(e) != Kind.FULL || !kind.containsKey(e) || value.isBlank() || value.matches("(?i)^mrs\\.?\\s+.+")) continue;
                for (String form : forms(value)) for (String k : keys(e, form)) out.computeIfAbsent(k, y -> new LinkedHashMap<>()).computeIfAbsent(e, y -> new LinkedHashSet<>()).addAll(sources(f.id()));
                // and the other ways of writing the name the claim lists ("Rose Yamada" beside Rose Yoko Hale Yamada), case aside
                for (FamilyNameHistory.Form x : FamilyNameHistory.formsOf(FamilyDetail.get(f, "forms"))) for (String form : forms(x.text())) for (String k : keys(e, form)) out.computeIfAbsent(k, y -> new LinkedHashMap<>()).computeIfAbsent(e, y -> new LinkedHashSet<>()).addAll(sources(f.id()));
            }
            // a Western given name a source writes in brackets beside one person's given name, and beside nobody else's
            Map<String, Set<String>> beside = new HashMap<>();
            for (String[] b : bracketed()) beside.computeIfAbsent(key(b[1]) + "\u0000" + b[2], y -> new HashSet<>()).add(find(b[0]));
            for (String[] b : bracketed()) {
                String k = key(b[1]);
                if (!k.isEmpty() && beside.get(k + "\u0000" + b[2]).size() == 1) out.computeIfAbsent(k, y -> new LinkedHashMap<>()).computeIfAbsent(b[0], y -> new LinkedHashSet<>()).add(b[2]);
            }
            return out;
        }

        /**
         * The keys a form of an entry's name meets other entries by: its own ({@link #key}), and for a form in Latin letters its key in each
         * language the entry's group is written in ({@link NameSpellings}): Endoh and Endō, Mueller and Müller, Lee and Yi of a Korean family.
         * A group written only in Latin letters has no language, so its names meet only as they are written.
         */
        List<String> keys(String entry, String form) {
            String k = key(form);
            if (k.isEmpty()) return List.of();
            List<String> out = new ArrayList<>(List.of(k));
            if (FamilyForms.script(form).equals("latin")) for (NameSpellings.Language l : languages(entry)) {
                String lk = l.key(k);
                if (lk.contains(" ")) out.add("\u0001" + l.code() + " " + lk);
            }
            return out;
        }

        /** The languages an entry's group writes its names in, worked out once per group. */
        List<NameSpellings.Language> languages(String entry) {
            String root = find(entry);
            List<NameSpellings.Language> known = entryLanguages.get(root);
            if (known != null) return known;
            List<String> forms = new ArrayList<>();
            for (String m : group(root)) { forms.add(label.getOrDefault(m, m)); forms.addAll(names.getOrDefault(m, List.of())); }
            List<NameSpellings.Language> out = NameSpellings.languagesOf(forms);
            entryLanguages.put(root, out);
            return out;
        }

        private final Map<String, List<NameSpellings.Language>> entryLanguages = new HashMap<>();

        /** The way an entry writes the name of a key: its label, another name, a name it carried, or a claim's words. */
        String nameWithKey(String entry, String k) {
            List<String> tried = new ArrayList<>(names.getOrDefault(entry, List.of()));
            for (String[] x : sidesOf.getOrDefault(entry, List.of())) tried.add(written(x[0], x[1]));
            for (Finding f : claims.values()) if (!gone(f) && FamilyNameHistory.isNameClaim(f) && g.nodeIdOf(f.triple().subject()).equals(entry)) tried.add(FamilyNameHistory.written(f));
            for (String[] b : bracketed()) if (b[0].equals(entry)) tried.add(b[1]);
            for (String n : tried) for (String f : forms(n)) if (key(f).equals(k)) return n;
            return label.getOrDefault(entry, entry);
        }

        /** Of these sources, those a text is: a family tree's own data (a tree file, a tree site) keeps each record a person of their own. */
        static List<String> prose(List<String> sources) {
            List<String> out = new ArrayList<>();
            for (String l : sources) if (!structured(l)) out.add(l);
            return out;
        }

        /** Whether a source is a family tree's own data: a tree file, a tree site's records. */
        static boolean structured(String locator) {
            String l = locator == null ? "" : locator.toLowerCase(Locale.ROOT);
            return l.contains("geni.com/api/") || l.endsWith(".ged");
        }

        /** L4, the page's own subject: a linked page's reader is the person the page's title names, when the library holds that person. */
        void pages() {
            for (String p : new ArrayList<>(kind.keySet())) {
                if (kind(p) != Kind.PAGE) continue;
                String subject = pageSubject(label.get(p));
                if (subject == null || shortName(subject)) continue;
                String to = holder(subject, p);
                if (to == null) continue;
                join(p, to, "L4", Grade.proved, List.of(), "The page \"" + label.get(p).replaceAll(" — .*$", "") + "\" is about " + subject + ": its title names the person.");
            }
        }

        /**
         * L1 and L9, the same name: two entries that carry one full name, written in another order, with or without accents, a title or the
         * comma of an index. A name is not a person: when one text gives both, it names one person (probable); across sources the name alone is
         * possible, one fact of their lives that agrees makes it probable and two proved, as for a reading across scripts. A family tree's own
         * data keeps each record a person of its own.
         */
        void sameName() {
            for (Map.Entry<String, Map<String, Set<String>>> e : nameSources().entrySet()) {
                List<String> es = new ArrayList<>(e.getValue().keySet());
                for (int i = 0; i < es.size(); i++) for (int j = i + 1; j < es.size(); j++) {
                    String a = es.get(i), b = es.get(j);
                    if (find(a).equals(find(b))) continue;
                    Set<String> shared = new LinkedHashSet<>(prose(new ArrayList<>(e.getValue().get(a))));
                    shared.retainAll(e.getValue().get(b));
                    Grade grade;
                    String why;
                    String name = nameWithKey(b, e.getKey()), other = nameWithKey(a, e.getKey());
                    String both = name.equals(other) ? "both carry the name \"" + name + "\"" : "carry one name, written \"" + name + "\" and \"" + other + "\"";
                    boolean ownerNames = e.getValue().get(a).stream().anyMatch(ownerNotes::contains) || e.getValue().get(b).stream().anyMatch(ownerNotes::contains);
                    if (!shared.isEmpty()) {
                        grade = Grade.probable;
                        why = "\"" + label.get(b) + "\" and \"" + label.get(a) + "\" " + both + ", and " + sourceName(new ArrayList<>(shared)) + " gives it to both: one text names one person so.";
                    } else if (ownerNames && es.size() == 2) {
                        // the owner's own notes name a person of the family, and one other entry carries that name: the owner knows whom they mean
                        grade = Grade.probable;
                        why = "\"" + label.get(b) + "\" and \"" + label.get(a) + "\" " + both + "; your own notes name the person, and one other entry in your library carries the name.";
                    } else {
                        List<String[]> agree = agree(a, b);
                        grade = agree.size() >= 2 ? Grade.proved : agree.size() == 1 ? Grade.probable : Grade.possible;
                        why = "\"" + label.get(b) + "\" and \"" + label.get(a) + "\" " + both + "; " + (agree.isEmpty() ? "nothing else is known to agree yet" : String.join("; ", agree.stream().map(x -> x[0]).toList())) + ".";
                    }
                    join(b, a, "L1", grade, List.of(), why);
                }
            }
        }

        private List<String[]> bracketed;

        /**
         * L1, a Western given name in brackets beside a Japanese one, as an index or a list of a family writes a person ("Morita, Noriko
         * (Helen; daughter)", "Noriko (Helen) Morita"): the one person carries both names, "Helen Morita" among them, as a name claim would say.
         * The brackets are read as the names over a life read them ({@link FamilyNameHistory#westernInBrackets}). Each {the entry, the Western
         * name with the family name, the source}; {@link #nameSources} counts it where the source writes that Western name beside one person's
         * name only, and {@link #sameName} then grades it as any name two entries carry: probable in one source, across sources as the facts
         * of their lives agree.
         */
        List<String[]> bracketed() {
            if (bracketed != null) return bracketed;
            List<String[]> out = new ArrayList<>();
            for (Finding f : claims.values()) {
                if (gone(f) || f.triple() == null) continue;
                List<String[]> pairs = FamilyNameHistory.westernInBrackets(FamilyChecks.quoteOf(f));
                if (pairs.isEmpty()) continue;
                List<String> src = prose(sources(f.id()));
                for (String side : new String[]{"subject", "object"}) {
                    String node = g.nodeIdOf(side.equals("subject") ? f.triple().subject() : f.triple().object());
                    if (!kind.containsKey(node) || kind(node) != Kind.FULL) continue;
                    String wr = untitled(written(f.id(), side)).replaceAll("\\s*[(（][^)）]*[)）]\\s*", " ").strip();
                    if (!FamilyForms.script(wr).equals("latin")) continue;
                    String[] ix = FamilyNames.indexForm(wr);
                    List<String> w = words(ix != null ? ix[0] + " " + ix[1] : wr);
                    for (String[] p : pairs) {
                        List<String> outside = words(p[0]);
                        if (w.size() != 2 || outside.size() != 1 || !w.contains(outside.get(0)) || w.get(0).equals(w.get(1))) continue;
                        String family = FamilyForms.capitalised(w.get(0).equals(outside.get(0)) ? w.get(1) : w.get(0));
                        for (String s : src) out.add(new String[]{node, p[1] + " " + family, s});
                    }
                }
            }
            bracketed = out;
            return out;
        }

        /**
         * L6, a reading across scripts: a reading in kana filed with a name in characters, in Latin letters, is the name in Latin letters, in
         * either order and whatever way a long vowel is written. On its own that is possible; with one fact of their lives that agrees it is
         * probable, and with two, proved.
         */
        void readings() {
            Map<String, List<String>> latin = new LinkedHashMap<>();
            for (String e : kind.keySet()) {
                if (kind(e) != Kind.FULL) continue;
                for (String n : names.get(e)) {
                    if (!FamilyForms.script(untitled(n)).equals("latin")) continue;
                    for (String f : forms(n)) { if (words(f).size() < 2 || initials(f)) continue; latin.computeIfAbsent(FamilyForms.latinKey(f), k -> new ArrayList<>()).add(e + "\u0000" + f); }
                }
            }
            for (String e : kind.keySet()) {
                if (kind(e) != Kind.FULL) continue;
                String hanName = null;
                List<String> kana = new ArrayList<>();
                for (String n : names.get(e)) {
                    String sc = FamilyForms.script(untitled(n));
                    if (sc.equals("han") && hanName == null) hanName = n;
                    if (sc.equals("kana") && n.strip().split("[\\s　・]+").length >= 2) kana.add(n);
                }
                if (hanName == null || kana.isEmpty()) continue;
                for (String r : kana) {
                    String romaji = FamilyForms.hepburn(r);
                    for (String hit : latin.getOrDefault(FamilyForms.latinKey(romaji), List.of())) {
                        String[] h = hit.split("\u0000");
                        if (find(h[0]).equals(find(e))) continue;
                        List<String[]> agree = agree(e, h[0]);
                        Grade grade = agree.size() >= 2 ? Grade.proved : agree.size() == 1 ? Grade.probable : Grade.possible;
                        String facts = agree.isEmpty() ? "nothing else is known to agree yet" : String.join("; ", agree.stream().map(x -> x[0]).toList());
                        join(h[0], e, "L6", grade, List.of(), "The reading " + r + " filed with " + hanName + " is " + FamilyForms.capitalised(romaji) + " in Latin letters, the name " + h[1] + "; " + facts + ".");
                    }
                }
            }
        }

        /**
         * L5, a short form: a given name alone, a family name alone, a title with a family name, initials with a family name, or a shorter form
         * of a full name, written in a claim. It is the one person the same source names in full with that name in it; or (L5b) the one person
         * with that name in it who stands in the same relation to somebody the claim relates it to. Several: left for the model to choose.
         */
        void shortForms() {
            // the full names each source writes, and the entry each is
            Map<String, List<String[]>> bySource = new HashMap<>();
            for (Map.Entry<String, List<String[]>> e : sidesOf.entrySet()) {
                if (kind(e.getKey()) != Kind.FULL) continue;
                for (String[] s : e.getValue()) {
                    String wr = written(s[0], s[1]);
                    if (shortName(wr)) continue;
                    String entry = sides.getOrDefault(Graph.Links.side(s[0], s[1].equals("subject")), e.getKey());
                    for (String src : sources(s[0])) bySource.computeIfAbsent(src, k -> new ArrayList<>()).add(new String[]{entry, wr});
                }
            }
            // and the other names the list of names says a source gives a person
            for (Map.Entry<String, List<String[]>> a : aliasSources.entrySet()) for (String[] x : a.getValue())
                if (kind(x[0]) == Kind.FULL && !shortName(x[1]) && pageSubject(x[1]) == null && !FamilyQuestions.placeholder(x[1])) bySource.computeIfAbsent(a.getKey(), k -> new ArrayList<>()).add(x);
            for (Map.Entry<String, List<String[]>> e : bySource.entrySet()) {
                Set<String> seen = new HashSet<>();
                e.getValue().removeIf(x -> !seen.add(x[0] + "\u0000" + x[1]));
            }
            // the words two different full names of one source share: their family name, as a family's own account writes it
            Map<String, Set<String>> sharedWords = new HashMap<>();
            for (Map.Entry<String, List<String[]>> e : bySource.entrySet()) {
                Map<String, Set<String>> namesOf = new HashMap<>();
                for (String[] x : e.getValue()) {
                    if (cjk(x[1])) continue;
                    List<String> ws = words(forms(x[1]).get(0));
                    if (ws.size() < 2) continue;
                    for (String w : ws) namesOf.computeIfAbsent(w, k -> new HashSet<>()).add(String.join(" ", ws));
                }
                Set<String> out = new HashSet<>();
                namesOf.forEach((w, ns) -> { if (ns.size() >= 2) out.add(w); });
                sharedWords.put(e.getKey(), out);
            }
            open.clear();
            wanted.clear();
            for (String w : new ArrayList<>(kind.keySet())) {
                boolean alone = kind(w) == Kind.SHORT;
                if (!alone && kind(w) != Kind.FULL) continue;
                for (String[] s : sidesOf.getOrDefault(w, List.of())) {
                    String key = Graph.Links.side(s[0], s[1].equals("subject"));
                    if (mentionLinks.containsKey(key) || sides.containsKey(key) && !alone) continue;
                    String wr = written(s[0], s[1]);
                    if (!alone && !shorter(wr, bySource, s[0], w)) continue;
                    if (alone && !shortName(wr)) continue;
                    String sex = titleSex(wr);
                    Set<String> cands = new LinkedHashSet<>();
                    Map<String, String> fullOf = new HashMap<>();
                    for (String src : sources(s[0])) for (String[] c : bySource.getOrDefault(src, List.of())) {
                        String r = find(c[0]);
                        if (r.equals(find(w)) || !partOf(wr, c[1])) continue;
                        if (!sex.isEmpty() && !sexes(r).isEmpty() && !sexes(r).contains(sex)) continue;
                        cands.add(r);
                        fullOf.putIfAbsent(r, c[1]);
                    }
                    // a family name alone names a family, not a person: the one person of the family the source names in full is somebody it
                    // speaks of only when something the words say of the name agrees with what is known of that person
                    Set<String> shared = new HashSet<>();
                    for (String src : sources(s[0])) shared.addAll(sharedWords.getOrDefault(src, Set.of()));
                    boolean family = alone && cands.size() == 1 && familyName(wr, fullOf.get(cands.iterator().next()), shared);
                    // initials fit every given name that begins so: the family or the model says who, or the same source's own name claim
                    if (alone && initials(untitled(wr))) {
                        String[] said = saidSo(s[0], wr, cands);
                        if (said != null) mention(s[0], s[1], w, said[0], "L5", Grade.probable, List.of(s[0], said[1]),
                                "In " + sourceName(sources(s[0])) + ", \"" + wr + "\" is " + nameOf(said[0]) + ": the same source says " + written(said[1], "subject") + " carried the name " + FamilyNameHistory.written(claims.get(said[1])) + ".");
                        else if (!cands.isEmpty()) open(s, w, wr, cands);
                        continue;
                    }
                    if (cands.size() == 1 && (!family || agrees(s[0], s[1].equals("subject"), cands.iterator().next()))) {
                        String to = cands.iterator().next();
                        mention(s[0], s[1], w, to, "L5", Grade.probable, List.of(s[0]),
                                "In " + sourceName(sources(s[0])) + ", \"" + wr + "\" is " + fullOf.get(to) + ", the one person that source names in full with that name"
                                        + (family ? ", and what it says of " + wr + " agrees with what is known of " + fullOf.get(to) : "") + ".");
                        continue;
                    }
                    if (!alone) { if (cands.size() > 1) open(s, w, wr, cands); continue; }
                    // several: the one the same source itself names so in a name claim ("Father Hale" carried the name Tom Hale)
                    String[] said = saidSo(s[0], wr, cands);
                    if (said != null) {
                        mention(s[0], s[1], w, said[0], "L5", Grade.probable, List.of(s[0], said[1]),
                                "In " + sourceName(sources(s[0])) + ", \"" + wr + "\" is " + nameOf(said[0]) + ": the same source says " + written(said[1], "subject") + " carried the name " + FamilyNameHistory.written(claims.get(said[1])) + ".");
                        continue;
                    }
                    // none or several: the one related to somebody the claim relates it to in the same way
                    if (!related(w, s, wr, cands) && cands.size() > 1) open(s, w, wr, cands);
                }
            }
        }

        /**
         * Whether a name alone is the family part of a full name: written with a title ("Mr. Hart", "Father Ellis"), a family name the library
         * knows, the first part of an index's "Family, Given", the beginning of a name in characters (森田 of 森田健二), or the last word of a name
         * in Latin letters. A romanised Japanese name is written family name first as often as given name first, so of its two words either
         * may be the family name, unless the library knows the other word as one or the same source writes the other word in another full
         * name too (Morita Kenji and Morita Haru: Morita is the family name).
         */
        boolean familyName(String written, String full, Set<String> shared) {
            if (!untitled(written).equals(written.strip())) return true;
            String w = String.join(" ", words(untitled(written)));
            if (familyParts.contains(w)) return true;
            String f = forms(full == null ? "" : full).get(0).strip();
            if (cjk(f)) { String a = han(untitled(written)), b = han(f); return b.startsWith(a) && !b.endsWith(a); }
            String[] ix = FamilyNames.indexForm(f);
            if (ix != null) return words(ix[0]).contains(w);
            List<String> fw = words(f);
            if (fw.size() == 2 && FamilyForms.romajiName(f)) {
                // the other word is the family name when the library knows it as one, or when the same source writes it in another full name too
                String other = fw.get(0).equals(w) ? fw.get(1) : fw.get(0);
                return !familyParts.contains(other) && !(shared.contains(other) && !shared.contains(w));
            }
            return !fw.isEmpty() && fw.get(fw.size() - 1).equals(w);
        }

        /**
         * Whether what one side of a claim says agrees with what is known of a person: the same relation to the same thing (a job, a place, a
         * relative) in another claim about them, or a birth or death year within a year of theirs.
         */
        boolean agrees(String claim, boolean subject, String person) {
            Finding f = claims.get(claim);
            if (f == null || f.triple() == null) return false;
            String rel = g.predicateOf(f.triple().predicate());
            String root = find(person);
            String other = subject ? at(claim, false, g.nodeIdOf(f.triple().object())) : at(claim, true, g.nodeIdOf(f.triple().subject()));
            for (String[] x : facts.getOrDefault(rel, List.of())) {
                if (x[0].equals(claim)) continue;
                String a = at(x[0], true, x[1]), b = at(x[0], false, x[2]);
                if (subject ? a.equals(root) && b.equals(other) : b.equals(root) && a.equals(other)) return true;
            }
            if (subject && (rel.equals("born-on") || rel.equals("died-on"))) {
                FamilyDate d = FamilyDate.parse(f.triple().object());
                if (d != null && d.year() > 0) for (int y : years(root, rel.startsWith("born") ? "born" : "died")) if (Math.abs(y - d.year()) <= 1) return true;
            }
            return false;
        }

        /**
         * A mention several people could be: the model's choice when it made one among these same people, quoting words the passage has (L5,
         * probable at most); otherwise it stays as written, and the model is asked once, unless it answered for these people before.
         */
        void open(String[] s, String w, String wr, Set<String> cands) {
            List<String> ids = new ArrayList<>();
            for (String x : cands) ids.add(bestEntry(x));
            Choice c = choices.get(Graph.Links.side(s[0], s[1].equals("subject")));
            if (c != null && new HashSet<>(c.candidates()).equals(new HashSet<>(ids))) {
                if (c.outcome().equals("chose")) for (String x : cands) if (bestEntry(x).equals(c.chosen()) && fitsEverywhere(s, wr, x)) {
                    if (mention(s[0], s[1], w, x, "L5", Grade.probable, List.of(s[0]), "In " + sourceName(sources(s[0])) + ", \"" + wr + "\" is " + nameOf(x) + ": the model chose among "
                            + cands.size() + " people of that name, and the passage says \"" + c.quote() + "\".")) return;
                }
            } else wanted.put(Graph.Links.side(s[0], s[1].equals("subject")), new Object[]{s, wr, cands});
            List<String> names = new ArrayList<>();
            for (String x : cands) names.add(nameOf(x));
            open.add(new Open(s[0], s[1], wr, names));
        }

        /**
         * Whether a person fits every claim the same sentence of the same source gives with the same words (a quote that holds another counts
         * as the same sentence): the words name one person in one sentence, so a choice one of those claims rules out is ruled out for all.
         */
        boolean fitsEverywhere(String[] s, String wr, String target) {
            Finding f = claims.get(s[0]);
            String quote = f == null ? "" : FamilyChecks.quoteOf(f);
            if (quote.isBlank()) return true;
            Set<String> src = new HashSet<>(sources(s[0]));
            String q = flat(quote);
            for (Finding o : claims.values()) {
                if (o.triple() == null || gone(o) || sources(o.id()).stream().noneMatch(src::contains)) continue;
                // the same sentence, or a part of it the reader quoted on its own
                String oq = flat(FamilyChecks.quoteOf(o));
                if (oq.isEmpty() || !(oq.contains(q) || q.contains(oq))) continue;
                for (String side : new String[]{"subject", "object"}) {
                    if (!written(o.id(), side).equals(wr)) continue;
                    String node = g.nodeIdOf(side.equals("subject") ? o.triple().subject() : o.triple().object());
                    if (!fits(o.id(), side, node, target)) return false;
                }
            }
            return true;
        }

        private final Map<String, String> texts = new HashMap<>();

        /**
         * The passage a claim was read from: its quote, with more of the source's text around it when the library holds that text and the quote
         * is found in it, and the source's name. {"", ""} when the claim quotes nothing.
         */
        String[] passage(String claim) {
            Finding f = claims.get(claim);
            String quote = f == null ? "" : FamilyChecks.quoteOf(f);
            List<String> src = sources(claim);
            String name = sourceName(src);
            if (quote.isBlank()) return new String[]{"", name};
            for (String loc : src) {
                String text = texts.computeIfAbsent(loc, l -> {
                    try { Path raw = RawCapture.find(store, l); return raw == null ? "" : RawCapture.read(raw)[2].replaceAll("\\s+", " "); } catch (Exception e) { return ""; }
                });
                if (text.isEmpty()) continue;
                String q = quote.replaceAll("\\s+", " ").strip();
                String probe = q.length() > 60 ? q.substring(0, 60) : q;
                int at = text.toLowerCase(Locale.ROOT).indexOf(probe.toLowerCase(Locale.ROOT));
                if (at < 0) continue;
                int from = Math.max(0, at - 900), to = Math.min(text.length(), at + q.length() + 500);
                while (from > 0 && from < at && !Character.isWhitespace(text.charAt(from - 1))) from++;
                while (to < text.length() && to > at && !Character.isWhitespace(text.charAt(to))) to--;
                return new String[]{text.substring(from, to).strip(), name};
            }
            return new String[]{quote.strip(), name};
        }

        /** The question of which person a mention is: the passage, what the library read from it, and the people it could be, each with what is known of them. */
        String choicePrompt(String[] s, String wr, String[] passage, List<String> ids) {
            StringBuilder b = new StringBuilder();
            Finding f = claims.get(s[0]);
            String sentence = f == null ? "" : FamilyChecks.quoteOf(f);
            b.append("Which person do the words \"").append(wr).append("\" mean in this sentence from ").append(passage[1]).append("?\n\"").append(sentence.strip()).append("\"\n\n");
            if (f != null) b.append("The library read from it: ").append(f.body().lines().findFirst().orElse(f.title()).strip()).append("\n\n");
            b.append("The passage around the sentence:\n\"\"\"\n").append(passage[0]).append("\n\"\"\"\n\n");
            b.append("The people it could be:\n");
            for (int i = 0; i < ids.size(); i++) b.append(i + 1).append(". ").append(describe(find(ids.get(i)), sources(s[0]))).append('\n');
            b.append("\nChoose the person the sentence speaks of when the passage's own words show who it is, and 0 when they leave it open. "
                    + "Answer with one JSON object: {\"choice\": N, \"quote\": \"...\"}, where the quote is the words of the passage that show it, copied exactly as they stand.");
            return b.toString();
        }

        /** A person as the question lists them: the name, the years, the nearest relatives, and how the same source writes them. */
        String describe(String root, List<String> src) {
            List<String> parts = new ArrayList<>();
            Set<String> sex = sexes(root);
            if (sex.size() == 1) parts.add(sex.contains("male") ? "a man" : "a woman");
            Set<Integer> born = years(root, "born"), died = years(root, "died");
            if (!born.isEmpty()) parts.add("born " + born.iterator().next());
            if (!died.isEmpty()) parts.add("died " + died.iterator().next());
            for (String[] r : new String[][]{{"child of ", "p"}, {"married to ", "s"}, {"parent of ", "c"}}) {
                Set<String> who = r[1].equals("p") ? parents(root) : r[1].equals("s") ? spouses(root) : children(root);
                List<String> named = new ArrayList<>();
                for (String x : who) if (kind(x) == Kind.FULL && named.size() < 3) named.add(nameOf(x));
                if (!named.isEmpty()) parts.add(r[0] + String.join(", ", named));
            }
            Set<String> forms = new LinkedHashSet<>();
            for (String m : group(root)) for (String[] x : sidesOf.getOrDefault(m, List.of())) if (sources(x[0]).stream().anyMatch(src::contains) && forms.size() < 4) forms.add(written(x[0], x[1]));
            for (Map.Entry<String, String> e : sides.entrySet()) if (find(e.getValue()).equals(root) && forms.size() < 4) {
                String[] k = e.getKey().split("\\|");
                if (sources(k[0]).stream().anyMatch(src::contains)) forms.add(written(k[0], k[1]));
            }
            if (!forms.isEmpty()) parts.add("this source writes them as " + String.join(", ", forms.stream().map(x -> "\"" + x + "\"").toList()));
            return nameOf(root) + (parts.isEmpty() ? "" : ": " + String.join("; ", parts)) + ".";
        }

        /**
         * L7, a name in characters with no reading on file: the relations pair it with a name in Latin letters (a parent, a husband or wife, a
         * child or a brother or sister of both is one person already), and the model gives the usual readings of the characters, which the
         * code romanises and compares as for a reading on file (L6). A match is probable, never proved on the model's word.
         */
        void modelReadings() {
            // each person's nearest relatives, and who has each relative, once for the round
            Map<String, Map<String, Set<String>>> near = new HashMap<>();
            for (String[] k : kin) {
                String a = at(k[0], true, k[2]), b = at(k[0], false, k[3]);
                if (a.equals(b)) continue;
                switch (k[1]) {
                    case "child-of" -> { nearOf(near, a, "p").add(b); nearOf(near, b, "c").add(a); }
                    case "parent-of" -> { nearOf(near, a, "c").add(b); nearOf(near, b, "p").add(a); }
                    case "married-to" -> { nearOf(near, a, "s").add(b); nearOf(near, b, "s").add(a); }
                    case "sibling-of" -> { nearOf(near, a, "b").add(b); nearOf(near, b, "b").add(a); }
                    default -> { }
                }
            }
            Map<String, Set<String>> having = new HashMap<>();   // type|relative → the people who have that relative
            for (Map.Entry<String, Map<String, Set<String>>> e : near.entrySet())
                for (Map.Entry<String, Set<String>> t : e.getValue().entrySet()) for (String x : t.getValue()) having.computeIfAbsent(t.getKey() + "|" + x, k -> new HashSet<>()).add(e.getKey());
            Map<String, String> words = Map.of("p", "both are children of ", "s", "both were married to ", "c", "both are parents of ", "b", "both are brothers or sisters of ");
            for (String r : new ArrayList<>(roots())) {
                if (kind(r) != Kind.FULL) continue;
                String hanName = null;
                boolean read = false;
                for (String m : group(r)) for (String n : names.getOrDefault(m, List.of())) {
                    if (detached.getOrDefault(m, Set.of()).contains(n)) continue;
                    String u = untitled(n);
                    if (hanName == null && FamilyForms.script(u).equals("han") && han(u).matches("\\p{IsHan}{3,6}")) hanName = han(u);
                    if (FamilyForms.script(n).equals("kana") && n.strip().split("[\\s　・]+").length >= 2) read = true;
                }
                if (hanName == null || read) continue;
                Map<String, String> partners = new LinkedHashMap<>();
                for (Map.Entry<String, Set<String>> t : near.getOrDefault(r, Map.of()).entrySet()) for (String x : t.getValue()) {
                    if (kind(x) != Kind.FULL || !oneByEvidence(x)) continue;
                    for (String q : having.getOrDefault(t.getKey() + "|" + x, Set.of())) {
                        if (q.equals(r) || partners.containsKey(q) || kind(q) != Kind.FULL || latinNames(q).isEmpty()) continue;
                        // a pair the one-character rule blocks goes on to the join, which refuses it and shows it as a possible link
                        String block = blocked(r, q);
                        if (block != null && !block.contains("differ by one character")) continue;
                        partners.put(q, words.get(t.getKey()) + nameOf(x));
                    }
                }
                if (partners.isEmpty()) continue;
                // the name's two parts, each read on its own: the family name as the library's own readings give it, else as the model does
                String[] part = hanParts(hanName);
                if (part == null) continue;
                List<String> fr = familyReadings.getOrDefault(part[0], List.of());
                boolean ownFamily = !fr.isEmpty();
                if (fr.isEmpty()) fr = readings.get(part[0] + FAMILY);
                List<String> gr = readings.get(part[1] + GIVEN);
                if (fr == null) wantedReadings.add(part[0] + FAMILY);
                if (gr == null) wantedReadings.add(part[1] + GIVEN);
                if (fr == null || gr == null) continue;
                for (Map.Entry<String, String> p : partners.entrySet()) {
                    if (find(p.getKey()).equals(find(r))) continue;
                    String hit = null, reading = null, family = null;
                    for (String a : fr) for (String k : gr) for (String f : latinNames(p.getKey()))
                        if (hit == null && FamilyForms.latinKey(f).equals(FamilyForms.latinKey(FamilyForms.hepburn(a) + " " + FamilyForms.hepburn(k)))) { hit = f; reading = k; family = a; }
                    // no reading listed matches: when one part of the name reads as one word of the Latin form, the model is asked about the other
                    if (hit == null) { askDirected(part, fr, gr, ownFamily, p.getKey()); continue; }
                    String famSaid = ownFamily ? "your sources read " + part[0] + " as " + family : readsAs(part[0], FAMILY, family);
                    join(anyMember(p.getKey()), anyMember(r), "L7", Grade.probable, List.of(), famSaid + " and " + readsAs(part[1], GIVEN, reading) + ": " + hanName + " is "
                            + FamilyForms.capitalised(FamilyForms.hepburn(family) + " " + FamilyForms.hepburn(reading)) + " in Latin letters, the name " + hit + "; and " + p.getValue() + ".");
                }
            }
        }

        /**
         * The directed question for a pair the relations nominate whose listed readings do not match the name in Latin letters: one part of the
         * name in characters reads as one word of the Latin form and the other part does not, so the model is asked whether the other part can be
         * read as the other word ("Can the characters 健吉 be read Kenkichi (けんきち)?"), the kana spelt from the romaji. The answer is kept with the
         * readings and asked once; a yes adds the reading, and the pair then matches as L7 does. A family name the library's own sources read is
         * not asked about: their reading stands.
         */
        void askDirected(String[] part, List<String> fr, List<String> gr, boolean ownFamily, String partner) {
            for (String f : latinNames(partner)) {
                List<String> w = words(f);
                if (w.size() != 2) continue;
                for (int i = 0; i < 2; i++) {
                    String a = w.get(i), b = w.get(1 - i);   // a as the family name, b as the given name
                    boolean famFits = fr.stream().anyMatch(x -> FamilyForms.latinKey(FamilyForms.hepburn(x)).equals(FamilyForms.latinKey(a)));
                    boolean givenFits = gr.stream().anyMatch(x -> FamilyForms.latinKey(FamilyForms.hepburn(x)).equals(FamilyForms.latinKey(b)));
                    if (famFits == givenFits || (givenFits && ownFamily)) continue;
                    String chars = famFits ? part[1] : part[0], romaji = famFits ? b : a, kind = famFits ? GIVEN : FAMILY;
                    String kana = FamilyForms.hiragana(romaji);
                    if (kana == null) continue;
                    String key = chars + kind + " as " + kana;
                    if (readings.containsKey(key)) continue;
                    wantedDirected.put(key, "Can the characters " + chars + ", a Japanese " + (kind.equals(FAMILY) ? "family" : "given") + " name, be read " + FamilyForms.capitalised(romaji)
                            + " (" + kana + ")? Answer yes or no, as one JSON object: {\"answer\": \"yes\", \"reading\": \"" + kana + "\"} or {\"answer\": \"no\"}. "
                            + "When the name is read so but the kana differ, give the kana as they are.");
                    return;
                }
            }
        }

        /** How the model gave a reading, for the words of a link: from a directed question it said yes to, or from the readings it listed. */
        String readsAs(String chars, String kind, String reading) {
            List<String> said = readings.get(chars + kind + " as " + reading);
            return said != null && !said.isEmpty() && said.get(0).equals("yes") ? "the model, asked whether " + chars + " can be read " + reading + ", says yes" : "the model reads " + chars + " as " + reading;
        }

        /**
         * L12, a household across scripts: parents and children written in characters in one source and in Latin letters in another, none of
         * them linked yet, so none is established to anchor the others as L7 wants. Two groups of the same shape, a child and both its parents,
         * or a husband and wife and two of their children, in which each name in characters reads as the name in Latin letters it stands for
         * (the family name as the library's readings give it, else as the model reads it; the given name as the model reads it, or a reading on
         * file), are one household: every pair is joined, probable. The shape and the readings must fit one way only; a block on any pair
         * joins none of them. Two people and one relation are not enough: a name and a reading agree by chance too often.
         */
        void households() {
            Map<String, Set<String>> parentsOf = new HashMap<>(), spousesOf = new HashMap<>();
            for (String[] k : kin) {
                String a = at(k[0], true, k[2]), b = at(k[0], false, k[3]);
                if (a.equals(b)) continue;
                switch (k[1]) {
                    case "child-of" -> parentsOf.computeIfAbsent(a, x -> new LinkedHashSet<>()).add(b);
                    case "parent-of" -> parentsOf.computeIfAbsent(b, x -> new LinkedHashSet<>()).add(a);
                    case "married-to" -> { spousesOf.computeIfAbsent(a, x -> new LinkedHashSet<>()).add(b); spousesOf.computeIfAbsent(b, x -> new LinkedHashSet<>()).add(a); }
                    default -> { }
                }
            }
            // the people written only in characters (with the name read), and the words of the names of those written only in Latin letters
            Map<String, String> hanOf = new HashMap<>();
            Map<String, List<String>> latinOf = new HashMap<>();
            Set<String> latinWords = new HashSet<>();
            for (String r : roots()) {
                if (kind(r) != Kind.FULL) continue;
                String h = null;
                boolean cjkName = false;
                for (String m : group(r)) for (String n : names.getOrDefault(m, List.of())) {
                    if (detached.getOrDefault(m, Set.of()).contains(n)) continue;
                    String u = untitled(n);
                    if (cjk(u)) cjkName = true;
                    if (h == null && FamilyForms.script(u).equals("han") && han(u).matches("\\p{IsHan}{3,6}")) h = han(u);
                }
                List<String> latin = latinNames(r);
                if (h != null && latin.isEmpty()) hanOf.put(r, h);
                else if (h == null && !cjkName && !latin.isEmpty()) {
                    latinOf.put(r, latin);
                    for (String n : latin) for (String w : words(n)) latinWords.add(FamilyForms.latinKey(w));
                }
            }
            if (hanOf.isEmpty() || latinOf.isEmpty()) return;
            Map<String, List<String>> byKey = new HashMap<>();   // a name in Latin letters as a key → the people written only so
            for (Map.Entry<String, List<String>> e : latinOf.entrySet()) for (String n : e.getValue()) {
                List<String> at = byKey.computeIfAbsent(FamilyForms.latinKey(n), x -> new ArrayList<>());
                if (!at.contains(e.getKey())) at.add(e.getKey());
            }
            Function<String, Set<String>> par = x -> parentsOf.getOrDefault(x, Set.of());
            Function<String, Set<String>> sp = x -> spousesOf.getOrDefault(x, Set.of());
            // the groups in characters of the shapes that make a household
            List<List<String>> seeds = new ArrayList<>();
            for (String c : hanOf.keySet()) {
                List<String> ps = par.apply(c).stream().filter(hanOf::containsKey).toList();
                for (int i = 0; i < ps.size(); i++) for (int j = i + 1; j < ps.size(); j++) seeds.add(List.of(c, ps.get(i), ps.get(j)));
            }
            for (String a : hanOf.keySet()) for (String b : sp.apply(a)) {
                if (!hanOf.containsKey(b) || a.compareTo(b) >= 0) continue;
                List<String> cs = new ArrayList<>();
                for (String c : hanOf.keySet()) if (!c.equals(a) && !c.equals(b) && (par.apply(c).contains(a) || par.apply(c).contains(b))) cs.add(c);
                for (int i = 0; i < cs.size(); i++) for (int j = i + 1; j < cs.size(); j++) seeds.add(List.of(a, b, cs.get(i), cs.get(j)));
            }
            // each seed's one way to fit: the people in Latin letters each name reads as, related as the names in characters are
            Map<String, String> pairOf = new LinkedHashMap<>(), taken = new HashMap<>();
            Set<String> torn = new HashSet<>();
            List<Map<String, String>> households = new ArrayList<>();
            for (List<String> seed : seeds) {
                List<List<String>> cand = new ArrayList<>();
                for (String h : seed) {
                    Set<String> keys = readingKeys(h, hanOf.get(h), latinWords);
                    List<String> c = new ArrayList<>();
                    if (keys != null) for (String k : keys) for (String q : byKey.getOrDefault(k, List.of())) if (!c.contains(q)) c.add(q);
                    cand.add(c);
                }
                if (cand.stream().anyMatch(List::isEmpty)) continue;
                List<Map<String, String>> fits = new ArrayList<>();
                fit(seed, cand, 0, new LinkedHashMap<>(), fits, par, sp);
                if (fits.size() != 1) continue;
                households.add(fits.get(0));
                for (Map.Entry<String, String> p : fits.get(0).entrySet()) {
                    String was = pairOf.putIfAbsent(p.getKey(), p.getValue()), by = taken.putIfAbsent(p.getValue(), p.getKey());
                    if (was != null && !was.equals(p.getValue())) torn.add(p.getKey());
                    if (by != null && !by.equals(p.getKey())) torn.add(by);
                }
            }
            Set<String> done = new HashSet<>();
            for (Map<String, String> hh : households) {
                // a person two households read two ways, or two people one person in Latin letters: nothing is joined in either
                if (hh.keySet().stream().anyMatch(torn::contains) || hh.entrySet().stream().anyMatch(p -> !taken.get(p.getValue()).equals(p.getKey()))) continue;
                if (!done.add(new TreeMap<>(hh).toString())) continue;
                if (hh.entrySet().stream().anyMatch(p -> blocked(p.getKey(), p.getValue()) != null)) continue;
                String why = "The same household is written in characters and in Latin letters: " + shape(hh.keySet().stream().toList(), par, sp) + ", and "
                        + shape(hh.values().stream().toList(), par, sp) + "; each name in characters reads as the name in Latin letters it stands for ("
                        + String.join("; ", hh.entrySet().stream().map(p -> hanOf.get(p.getKey()) + " as " + nameOf(p.getValue())).toList()) + ").";
                Map<String, String> upWas = new HashMap<>(up);
                Map<String, Set<String>> membersWas = new HashMap<>(members);
                int linksWas = links.size();
                boolean all = true;
                for (Map.Entry<String, String> p : hh.entrySet()) all &= join(anyMember(p.getValue()), anyMember(p.getKey()), "L12", Grade.probable, List.of(), why);
                if (!all) {
                    // a pair a join before it blocked: the household is not joined in part
                    up.clear(); up.putAll(upWas);
                    members.clear(); members.putAll(membersWas);
                    while (links.size() > linksWas) links.remove(links.size() - 1);
                }
            }
        }

        /** Every way a household in characters fits people in Latin letters, one each, related as they are: {name in characters → name in Latin letters}. */
        private void fit(List<String> seed, List<List<String>> cand, int i, Map<String, String> sofar, List<Map<String, String>> out,
                         Function<String, Set<String>> par, Function<String, Set<String>> sp) {
            if (out.size() > 1) return;
            if (i == seed.size()) {
                // the shape, the same in Latin letters: the child's two parents; or the husband and wife, and a parent of each child among them
                Function<String, String> to = sofar::get;
                if (seed.size() == 3) {
                    if (!par.apply(to.apply(seed.get(0))).containsAll(List.of(to.apply(seed.get(1)), to.apply(seed.get(2))))) return;
                } else {
                    if (!sp.apply(to.apply(seed.get(0))).contains(to.apply(seed.get(1)))) return;
                    for (String c : seed.subList(2, 4)) {
                        boolean one = false;
                        for (String p : seed.subList(0, 2)) one |= par.apply(c).contains(p) && par.apply(to.apply(c)).contains(to.apply(p));
                        if (!one) return;
                    }
                }
                out.add(new LinkedHashMap<>(sofar));
                return;
            }
            for (String q : cand.get(i)) {
                if (sofar.containsValue(q)) continue;
                sofar.put(seed.get(i), q);
                fit(seed, cand, i + 1, sofar, out, par, sp);
                sofar.remove(seed.get(i));
            }
        }

        /** A household as a sentence says it: "X, child of Y and Z", or "Y and Z, husband and wife, parents of X and W". */
        private String shape(List<String> who, Function<String, Set<String>> par, Function<String, Set<String>> sp) {
            if (who.size() == 3) return nameOf(who.get(0)) + ", child of " + nameOf(who.get(1)) + " and " + nameOf(who.get(2));
            return nameOf(who.get(0)) + " and " + nameOf(who.get(1)) + ", husband and wife, with " + nameOf(who.get(2)) + " and " + nameOf(who.get(3)) + " their children";
        }

        /**
         * The ways a name in characters reads in Latin letters, as keys: a reading on file in kana, else the family name as the library's
         * readings give it or the model reads it, with each reading of the given name the model gives. Null when a reading is not known yet;
         * it is then asked for, the given name only once the family name reads as a word some name in Latin letters has.
         */
        Set<String> readingKeys(String root, String hanName, Set<String> latinWords) {
            Set<String> out = new LinkedHashSet<>();
            for (String m : group(root)) for (String n : names.getOrDefault(m, List.of())) {
                String[] w = n.strip().split("[\\s　・]+");
                if (w.length == 2 && FamilyForms.script(n).equals("kana")) out.add(FamilyForms.latinKey(FamilyForms.hepburn(w[0]) + " " + FamilyForms.hepburn(w[1])));
            }
            if (!out.isEmpty()) return out;
            String[] part = hanParts(hanName);
            if (part == null) return null;
            List<String> fr = familyReadings.getOrDefault(part[0], List.of());
            if (fr.isEmpty()) fr = readings.get(part[0] + FAMILY);
            if (fr == null) { wantedReadings.add(part[0] + FAMILY); return null; }
            if (fr.stream().noneMatch(a -> latinWords.contains(FamilyForms.latinKey(FamilyForms.hepburn(a))))) return null;
            List<String> gr = readings.get(part[1] + GIVEN);
            if (gr == null) { wantedReadings.add(part[1] + GIVEN); return null; }
            for (String a : fr) for (String k : gr) out.add(FamilyForms.latinKey(FamilyForms.hepburn(a) + " " + FamilyForms.hepburn(k)));
            return out;
        }

        /**
         * The readings the names over a life want ({@link FamilyNameHistory}): a person written in characters who also carries a name in
         * Latin letters of two words (a book's spelling, "Kenji Morita" beside 森田健二) is one person already, and whether the spelling is
         * a way of writing the name in characters turns on the reading of each part. The family part first, as the library's own readings
         * give it; where one word of the spelling reads it, the given part is asked for too. Nothing is linked here: the answers are kept for
         * the view, which asks nobody.
         */
        void readingsForForms() {
            for (String r : roots()) {
                if (kind(r) != Kind.FULL) continue;
                List<String> latin = latinNames(r);
                if (latin.isEmpty()) continue;
                Set<String> latinWords = new HashSet<>();
                for (String n : latin) if (words(n).size() == 2) for (String w : words(n)) latinWords.add(FamilyForms.latinKey(w));
                if (latinWords.isEmpty()) continue;
                for (String m : group(r)) for (String n : names.getOrDefault(m, List.of())) {
                    if (detached.getOrDefault(m, Set.of()).contains(n)) continue;
                    String u = untitled(n);
                    if (!FamilyForms.script(u).equals("han") || !han(u).matches("\\p{IsHan}{3,6}")) continue;
                    String[] part = hanParts(han(u));
                    if (part == null) continue;
                    List<String> fr = familyReadings.getOrDefault(part[0], List.of());
                    if (fr.isEmpty()) fr = readings.get(part[0] + FAMILY);
                    if (fr == null) { wantedReadings.add(part[0] + FAMILY); continue; }
                    if (fr.stream().noneMatch(a -> latinWords.contains(FamilyForms.latinKey(FamilyForms.hepburn(a))))) continue;
                    if (readings.get(part[1] + GIVEN) == null) wantedReadings.add(part[1] + GIVEN);
                }
            }
        }

        /**
         * A name before and after a marriage or an adoption (L11): two entries of one given name, in one script or by a reading across
         * scripts, and two family names, are one person when two independent facts agree (the same parents, the same birth year, the same
         * birthplace, or a husband or wife whose family name is the other entry's) and nothing blocks it. A given name that differs by one
         * character is another name (L8). Probable.
         */
        void changedNames() {
            // each given word → {root, family key, family as written, the name, the given key}: a name of two given names meets one of either
            Map<String, List<String[]>> byGiven = new HashMap<>();
            for (String r : roots()) {
                if (kind(r) != Kind.FULL) continue;
                Set<String> seen = new HashSet<>();
                for (String[] p : nameParts(r)) {
                    if (!seen.add(p[0] + "|" + p[1])) continue;
                    String script = p[0].substring(0, 2);
                    for (String w : script.equals("L|") ? List.of(p[0].substring(2).split(" ")) : List.of(p[0].substring(2)))
                        byGiven.computeIfAbsent(script + w, k -> new ArrayList<>()).add(new String[]{r, p[1], p[2], p[3], p[0]});
                }
            }
            Set<String> tried = new HashSet<>();
            for (List<String[]> same : byGiven.values()) {
                if (same.size() < 2 || same.size() > 40) continue;
                for (int i = 0; i < same.size(); i++) for (int j = i + 1; j < same.size(); j++) {
                    String[] a = same.get(i), b = same.get(j);
                    if (find(a[0]).equals(find(b[0])) || a[1].equals(b[1]) || !oneGivenName(a[4], b[4]) || !tried.add(a[3] + "\u0000" + b[3])) continue;
                    List<String> agree = changeAgrees(find(a[0]), find(b[0]), a, b);
                    // one page that writes a woman under her maiden and her married name with one husband says so itself: on one page the
                    // same husband or wife is enough; across sources two facts must agree
                    boolean onePage = agree.stream().anyMatch(x -> x.startsWith("both were married to ")) && !Collections.disjoint(prose(new ArrayList<>(sourcesOf(a[0]))), sourcesOf(b[0]));
                    if (agree.size() < 2 && !onePage) continue;
                    join(anyMember(b[0]), anyMember(a[0]), "L11", Grade.probable, List.of(), "\"" + a[3] + "\" and \"" + b[3] + "\" carry one given name under the family names " + a[2] + " and " + b[2]
                            + ", as a name does before and after a marriage or an adoption; " + String.join("; ", agree) + (onePage && agree.size() < 2 ? "; one page writes both names with that husband or wife" : "") + ".");
                }
            }
        }

        /**
         * Whether two given names are one: the same, or in Latin letters the given names of one the given names of the other with more ("Mary
         * Haru" and "Haru": a name given beside the other, written with both or with one). One character or letter more is another name.
         */
        static boolean oneGivenName(String a, String b) {
            if (a.equals(b)) return true;
            if (!a.startsWith("L|") || !b.startsWith("L|")) return false;
            Set<String> x = new HashSet<>(List.of(a.substring(2).split(" "))), y = new HashSet<>(List.of(b.substring(2).split(" ")));
            return x.containsAll(y) || y.containsAll(x);
        }

        /** The facts that say two entries of one given name and two family names are one person: each kind once, as a phrase. */
        List<String> changeAgrees(String ra, String rb, String[] a, String[] b) {
            List<String> out = new ArrayList<>();
            for (String p : parents(ra)) if (parents(rb).contains(p) && kind(p) == Kind.FULL && oneByEvidence(p)) { out.add("both are children of " + nameOf(p)); break; }
            Set<Integer> ya = years(ra, "born"), yb = years(rb, "born");
            for (int y : ya) if (yb.contains(y)) { out.add("both were born in " + y); break; }
            Set<Integer> da = years(ra, "died"), db = years(rb, "died");
            for (int y : da) if (db.contains(y)) { out.add("both died in " + y); break; }
            // one page that writes a woman under her maiden and her married name with the same husband says so itself: the facts need not
            // come from two sources
            // a husband or wife who is the brother or sister of one of them is a fact that cannot be, and agrees with nothing
            Set<String> kin = siblings(ra); kin.addAll(siblings(rb));
            for (String sp : spouses(ra)) if (spouses(rb).contains(sp) && !kin.contains(sp) && kind(sp) == Kind.FULL && oneByEvidence(sp)) { out.add("both were married to " + nameOf(sp)); break; }
            boolean place = false;
            for (String x : places(ra)) for (String y : places(rb)) if (!place && FamilyForms.script(x).equals(FamilyForms.script(y)) && FamilySame.samePlace(x, y)) { out.add("both were born in " + x); place = true; }
            for (String[] w : new String[][]{{ra, b[1], b[2]}, {rb, a[1], a[2]}}) {
                boolean hit = false;
                for (String sp : spouses(w[0])) {
                    if (hit || kind(sp) != Kind.FULL || kin.contains(sp)) continue;
                    for (String[] p : nameParts(sp)) if (p[1].equals(w[1])) { out.add("the husband or wife of one, " + nameOf(sp) + ", has the other's family name " + w[2]); hit = true; break; }
                }
                if (hit) break;
            }
            return out;
        }

        /**
         * A group's full names as {the given name's key, the family name's key, the family name as written, the name}: in Latin letters the
         * family name is the one word of the name the library knows as a family name (or the part before an index's comma), and the given
         * name is the rest; in characters, a family name the library knows at the start. A reading of a name in characters, on file or from
         * the model, gives its parts in Latin letters too, so a name in characters meets one in Latin letters.
         */
        List<String[]> nameParts(String root) {
            List<String[]> out = new ArrayList<>();
            for (String m : group(root)) {
                if (kind(m) != Kind.FULL) continue;
                for (String n : names.getOrDefault(m, List.of())) {
                    if (detached.getOrDefault(m, Set.of()).contains(n)) continue;
                    String sc = FamilyForms.script(untitled(n));
                    if (sc.equals("latin")) for (String f : forms(n)) {
                        if (initials(f)) continue;
                        String[] ix = FamilyNames.indexForm(f);
                        List<String> w = words(ix != null ? ix[1] : f);
                        String fam = null;
                        if (ix != null) fam = ix[0];
                        else for (String x : w) if (familyKeys.contains(FamilyForms.latinKey(x))) { if (fam != null) { fam = null; break; } fam = x; }
                        if (fam == null) continue;
                        List<String> given = new ArrayList<>(w);
                        given.remove(String.join(" ", words(fam)));
                        if (given.isEmpty() || given.size() == w.size() && ix == null) continue;
                        String gk = FamilyForms.latinKey(String.join(" ", given));
                        // the family name as the name writes it
                        String shown = fam;
                        for (String t : f.split("[\\s,，]+")) if (String.join(" ", words(t)).equals(String.join(" ", words(fam)))) shown = t;
                        if (gk.length() >= 2) out.add(new String[]{"L|" + gk, "L|" + FamilyForms.latinKey(fam), shown, n});
                    } else if (sc.equals("han")) {
                        String h = han(untitled(n));
                        String fam = null;
                        for (String fp : familyParts) if (fp.matches("\\p{IsHan}+") && h.startsWith(fp) && h.length() > fp.length() && (fam == null || fp.length() > fam.length())) fam = fp;
                        // a family name the library does not know: two characters, as most are (as the readings take it, hanParts)
                        if (fam == null && h.matches("\\p{IsHan}{3,5}")) fam = h.substring(0, 2);
                        if (fam == null) continue;
                        out.add(new String[]{"H|" + h.substring(fam.length()), "H|" + fam, fam, n});
                        // its readings, on file or from the model: the same parts in Latin letters
                        List<String[]> rs = new ArrayList<>();
                        for (String x : names.getOrDefault(m, List.of())) {
                            String[] parts = x.strip().split("[\\s　・]+");
                            if (FamilyForms.script(x).equals("kana") && parts.length == 2) rs.add(parts);
                        }
                        List<String> fr = familyReadings.getOrDefault(fam, readings.getOrDefault(fam + FAMILY, List.of()));
                        for (String gr : readings.getOrDefault(h.substring(fam.length()) + GIVEN, List.of())) for (String f : fr) rs.add(new String[]{f, gr});
                        for (String[] parts : rs)
                            out.add(new String[]{"L|" + FamilyForms.latinKey(FamilyForms.hepburn(parts[1])), "L|" + FamilyForms.latinKey(FamilyForms.hepburn(parts[0])), fam, n});
                    }
                }
            }
            return out;
        }

        /** The question for the readings of a family name or a given name ({@link #FAMILY}, {@link #GIVEN} after the characters). */
        String readingPrompt(String key) {
            boolean family = key.endsWith(FAMILY);
            String chars = key.substring(0, key.length() - (family ? FAMILY.length() : GIVEN.length()));
            return "Give the usual readings of the Japanese " + (family ? "family" : "given") + " name " + chars + " in hiragana, up to five, the most likely first.\n"
                    + "Answer with one JSON object: {\"readings\": [\"...\", \"...\"]}";
        }

        /** A name in characters as {family name, given name}: the family name the library knows it to begin with; null when it knows none. */
        String[] hanParts(String h) {
            String fam = null;
            for (String fp : familyParts) if (fp.matches("\\p{IsHan}+") && h.startsWith(fp) && h.length() > fp.length() && (fam == null || fp.length() > fam.length())) fam = fp;
            // a family name the library does not know: two characters, as most are (a wrong split reads as no match, and links nothing)
            if (fam == null && h.matches("\\p{IsHan}{3,5}")) fam = h.substring(0, 2);
            return fam == null ? null : new String[]{fam, h.substring(fam.length())};
        }

        /** The group roots of the people, each once. */
        Set<String> roots() {
            Set<String> out = new LinkedHashSet<>();
            for (String e : kind.keySet()) out.add(find(e));
            return out;
        }

        String anyMember(String root) { return group(root).iterator().next(); }

        /** The forms in Latin letters of two words or more that a group's names are written in. */
        List<String> latinNames(String root) {
            List<String> out = new ArrayList<>();
            for (String m : group(root)) {
                if (kind(m) != Kind.FULL) continue;
                for (String n : names.getOrDefault(m, List.of())) {
                    if (detached.getOrDefault(m, Set.of()).contains(n) || !FamilyForms.script(untitled(n)).equals("latin")) continue;
                    for (String f : forms(n)) if (words(f).size() >= 2 && !initials(f) && !out.contains(f)) out.add(f);
                }
            }
            return out;
        }

        static Set<String> nearOf(Map<String, Map<String, Set<String>>> near, String who, String type) {
            return near.computeIfAbsent(who, k -> new HashMap<>()).computeIfAbsent(type, k -> new LinkedHashSet<>());
        }

        /**
         * Of several people a short form could be, the one a name claim of the same source says a mention of the same short form is: {the
         * person, the name claim}; null when no such claim or more than one person.
         */
        String[] saidSo(String claim, String wr, Set<String> cands) {
            Set<String> src = new HashSet<>(sources(claim));
            // the short form as written, its title with it: "Father Hale" and "Hale" are two ways of writing, and a source may use them for two people
            String form = Vocabulary.norm(wr);
            String[] found = null;
            for (Link l : mentionLinks.values()) {
                if (!(l.rule().equals("L1") || l.rule().equals("L9")) || !l.side().equals("subject") || l.claim().equals(claim)) continue;
                Finding f = claims.get(l.claim());
                if (f == null || !FamilyNameHistory.isNameClaim(f) || !Vocabulary.norm(l.written()).equals(form)) continue;
                if (sources(l.claim()).stream().noneMatch(src::contains)) continue;
                String to = find(l.person());
                if (!cands.contains(to)) continue;
                if (found != null && !found[0].equals(to)) return null;
                found = new String[]{to, l.claim()};
            }
            return found;
        }

        /**
         * L5 within one source: a given name alone that a source writes for one person wherever it says who (its other claims of the name are
         * linked to that person, and to nobody else) is that person in the source's other claims of the name too: the names it gives her,
         * her sex, the years of her life. A family name alone, a title and initials name a family or several people, and are left as they are.
         */
        void throughTheSource() {
            Map<String, Set<String>> whom = new HashMap<>();   // source + the name as written → the people its linked mentions are
            Map<String, String> via = new HashMap<>();
            for (Link l : mentionLinks.values()) {
                if (!givenAlone(l.written())) continue;
                for (String src : sources(l.claim())) {
                    String k = src + "\u0000" + Vocabulary.norm(l.written());
                    whom.computeIfAbsent(k, x -> new LinkedHashSet<>()).add(find(l.person()));
                    via.putIfAbsent(k, l.claim());
                }
            }
            if (whom.isEmpty()) return;
            for (String w : new ArrayList<>(kind.keySet())) {
                if (kind(w) != Kind.SHORT) continue;
                for (String[] s : sidesOf.getOrDefault(w, List.of())) {
                    String key = Graph.Links.side(s[0], s[1].equals("subject"));
                    String wr = written(s[0], s[1]);
                    if (mentionLinks.containsKey(key) || sides.containsKey(key) || !givenAlone(wr)) continue;
                    // a relation to somebody the claim only describes ("Haru is a sister of the speaker") cannot be checked against the person:
                    // one book may give one given name to two people, an older sister in one passage and a son's wife in another. A relation to
                    // a named person is checked as every mention is ({@link #fits})
                    Finding rf = claims.get(s[0]);
                    if (rf == null || rf.triple() == null) continue;
                    if (twoPeople(g.predicateOf(rf.triple().predicate()))) {
                        String other = s[1].equals("subject") ? at(s[0], false, g.nodeIdOf(rf.triple().object())) : at(s[0], true, g.nodeIdOf(rf.triple().subject()));
                        if (!hasFull(find(other))) continue;
                    }
                    Set<String> to = new LinkedHashSet<>();
                    String by = null;
                    for (String src : sources(s[0])) {
                        String k = src + "\u0000" + Vocabulary.norm(wr);
                        for (String x : whom.getOrDefault(k, Set.of())) to.add(find(x));
                        if (by == null) by = via.get(k);
                    }
                    if (to.size() != 1) continue;
                    String p = to.iterator().next();
                    mention(s[0], s[1], w, p, "L5", Grade.probable, by == null ? List.of(s[0]) : List.of(s[0], by), "In " + sourceName(sources(s[0])) + ", \"" + wr + "\" is " + nameOf(p)
                            + " wherever that source says who it is (claim " + by + "), and nobody else.");
                }
            }
        }

        /** A brother or a sister in words that make them only half or step one: 異母, a half-brother, a stepsister. */
        static final Pattern HALF = Pattern.compile("(?i)\\bhalf[- ]?(?:brother|sister|sibling)|\\bstep[- ]?(?:brother|sister|sibling)|異母|異父|腹違い|種違い|義兄|義弟|義姉|義妹|義理の");

        /**
         * Parents worked out for a brother or sister who has none of their own on file: a claim (the owner's words, or a source) calls two
         * people brother and sister, not half or step, and one of them has birth parents on file; the other is then a child of the same
         * parents, shown in genealogy's view as worked out and filed nowhere. Nothing is worked out where anything says otherwise: a parent
         * of their own of any kind (by birth, adoption, a step-parent, foster), words that make them half or step, two brothers or
         * sisters whose parents differ, or dates that rule a parent out ({@link #couldBeParent}). Each {child, parent, the claim it rests on,
         * why}. No marriage is worked out between the parents.
         */
        List<String[]> siblingsParents() {
            Map<String, Set<String>> from = new LinkedHashMap<>();   // a person with no parents → the parents of their brothers and sisters
            Map<String, String[]> because = new HashMap<>();
            Set<String> half = new HashSet<>();
            for (String[] k : kin) {
                if (!k[1].equals("sibling-of")) continue;
                String a = at(k[0], true, k[2]), b = at(k[0], false, k[3]);
                if (a.equals(b)) continue;
                Finding f = claims.get(k[0]);
                String words = f == null ? "" : FamilyChecks.quoteOf(f) + " " + f.body();
                if (HALF.matcher(words).find()) { half.add(Graph.pair(a, b)); continue; }
                for (String[] xy : new String[][]{{a, b}, {b, a}}) {
                    if (anyParent(xy[1])) continue;
                    Set<String> ps = birthParents(xy[0]);
                    if (ps.isEmpty()) continue;
                    Set<String> had = from.get(xy[1]);
                    if (had != null && !had.equals(ps)) { from.put(xy[1], Set.of()); continue; }   // two brothers or sisters of other parents: nothing
                    from.put(xy[1], ps);
                    because.putIfAbsent(xy[1], new String[]{k[0], xy[0]});
                }
            }
            List<String[]> out = new ArrayList<>();
            for (Map.Entry<String, Set<String>> e : from.entrySet()) {
                String[] by = because.get(e.getKey());
                if (e.getValue().isEmpty() || e.getValue().size() > 2 || by == null || half.contains(Graph.pair(e.getKey(), by[1]))) continue;
                if (e.getValue().stream().anyMatch(p -> !couldBeParent(e.getKey(), p))) continue;
                for (String p : e.getValue()) out.add(new String[]{e.getKey(), p, by[0], "worked out: brother or sister of " + nameOf(by[1])});
            }
            return out;
        }

        /**
         * Whether the dates on file leave a person the parent of a child: born at least {@link FamilyChecks#YOUNGEST_PARENT} and at most
         * {@link FamilyChecks#OLDEST_PARENT} years before the child, and not dead more than a year before the child was born. Unknown
         * dates rule nobody out.
         */
        boolean couldBeParent(String child, String parent) {
            Set<Integer> cb = years(child, "born"), pb = years(parent, "born"), pd = years(parent, "died");
            for (int c : cb) {
                for (int b : pb) if (c - b < FamilyChecks.YOUNGEST_PARENT || c - b > FamilyChecks.OLDEST_PARENT) return false;
                for (int d : pd) if (c > d + 1) return false;
            }
            return true;
        }

        /** The sex a description's last relation word gives the person: "…'s father" a man, "…'s mother" a woman; "" where the word says neither. */
        static String describedSex(String label) {
            String[] parts = description(label);
            if (parts == null) return "";
            List<String> steps = steps(parts[1]);
            String last = bare(steps.get(steps.size() - 1)).replaceAll("^(?:maternal|paternal)\\s+", "").replaceAll("^(?:great[- ]?)*", "");
            if (Set.of("father", "husband", "son", "brother", "grandfather", "uncle", "nephew", "dad", "daddy", "papa").contains(last)) return "male";
            if (Set.of("mother", "wife", "daughter", "sister", "grandmother", "aunt", "niece", "mum", "mummy", "mom", "mommy", "mama").contains(last)) return "female";
            return "";
        }

        /** The birth parents on file of a group: parent-of and child-of claims. */
        Set<String> birthParents(String root) {
            Set<String> out = new LinkedHashSet<>();
            for (String[] k : kin) {
                String a = at(k[0], true, k[2]), b = at(k[0], false, k[3]);
                if (k[1].equals("child-of") && a.equals(root)) out.add(b);
                if (k[1].equals("parent-of") && b.equals(root)) out.add(a);
            }
            out.remove(root);
            out.removeIf(x -> kind(x) != Kind.FULL);
            return out;
        }

        /** Whether a group has a parent of any kind on file: by birth, by adoption, a step-parent or a foster parent. */
        boolean anyParent(String root) {
            for (String[] k : kin) {
                String a = at(k[0], true, k[2]), b = at(k[0], false, k[3]);
                switch (k[1]) {
                    case "child-of", "adopted-by", "foster-child-of" -> { if (a.equals(root)) return true; }
                    case "parent-of", "step-parent-of" -> { if (b.equals(root)) return true; }
                    default -> { }
                }
            }
            return false;
        }

        /** A given name alone: one word, no title, no initials, and no family name the library knows. */
        boolean givenAlone(String written) {
            String u = untitled(written).strip();
            if (!u.equals(written.strip()) || !shortName(written) || initials(u)) return false;
            if (cjk(u)) return !familyParts.contains(han(u));
            return words(u).size() == 1 && !familyKeys.contains(FamilyForms.latinKey(u));
        }

        /** Whether a full name in Latin letters is a shorter form of a longer full name the same source writes: "Tom Hale" of "Tom Arthur Hale". */
        boolean shorter(String written, Map<String, List<String[]>> bySource, String claim, String entry) {
            if (cjk(written) || words(untitled(written)).size() < 2 || written.matches(".*[(（].*")) return false;
            // an entry that carries a longer name is that name; the words written are only how this claim writes it
            int n = words(untitled(written)).size();
            for (String x : names.getOrDefault(entry, List.of())) for (String f : forms(x)) if (words(f).size() > n) return false;
            for (String src : sources(claim)) for (String[] c : bySource.getOrDefault(src, List.of())) if (partOf(written, c[1])) return true;
            return false;
        }

        /**
         * Whether a short form is part of a full name: a word of it in Latin letters (Kenji of Morita Kenji; every word of Tom Hale in Tom
         * Arthur Hale, which has more), an initial for a given name (H. Morita), or in characters its beginning or its end (健二 of 森田健二).
         */
        boolean partOf(String shortForm, String full) {
            // a note in brackets is no part of the name: "Tom Hale (born 1905)" is no longer a name than Tom Hale, only told apart from him
            String s = untitled(shortForm).replaceAll("\\s*[(（][^)）]*[)）]\\s*$", "").strip(), f = unnoted(untitled(full)).strip();
            if (s.isEmpty() || f.isEmpty()) return false;
            if (cjk(s) != cjk(f)) return false;
            if (cjk(s)) {
                // 森田ハル ends with ハル: a name in characters and kana is compared as it is written
                String a = han(s), b = han(f);
                return b.length() > a.length() && (b.startsWith(a) || b.endsWith(a));
            }
            List<String> fw = new ArrayList<>();
            for (String form : forms(f)) for (String x : words(form)) if (!fw.contains(x)) fw.add(x);
            List<String> sw = words(s);
            if (sw.isEmpty() || fw.size() <= sw.size() && !initials(s)) return false;
            if (initials(s)) {
                // H. Morita: the family name is a word of the full name, and the initials begin its other words, the first first
                String fam = sw.get(sw.size() - 1);
                if (FamilyNames.indexForm(s) != null) fam = words(FamilyNames.indexForm(s)[0]).get(0);
                List<String> init = new ArrayList<>(sw); init.remove(fam);
                for (String form : forms(f)) {
                    List<String> w = new ArrayList<>(words(FamilyNames.indexForm(form) != null ? FamilyNames.indexForm(form)[1] + " " + FamilyNames.indexForm(form)[0] : form));
                    if (!w.remove(fam) || w.size() < init.size()) continue;
                    boolean all = true;
                    for (int i = 0; i < init.size(); i++) if (!w.get(i).startsWith(init.get(i))) all = false;
                    if (all) return true;
                }
                return false;
            }
            return fw.containsAll(sw);
        }

        /** L5b: a name alone, related by its claim to somebody, is the one person with that name in it who is related to them the same way. */
        boolean related(String w, String[] s, String wr, Set<String> within) {
            Finding f = claims.get(s[0]);
            if (f == null || f.triple() == null) return false;
            String rel = g.predicateOf(f.triple().predicate());
            if (!KIN.contains(rel)) return false;
            boolean subject = s[1].equals("subject");
            String other = subject ? at(s[0], false, g.nodeIdOf(f.triple().object())) : at(s[0], true, g.nodeIdOf(f.triple().subject()));
            // the other side a given name alone too (ハル, married to 勇 and to 森田勇 on one page): the same words in the same source are one
            // person of that page, so the one full name related to them the same way there is the person
            if (kind(other) == Kind.SHORT) return relatedThroughAShortName(w, s, wr, rel, subject, other, within);
            // across scripts only from somebody who is one person by the evidence: then the relation and the reading of the given name say who
            boolean established = oneByEvidence(other);
            Set<String> cands = new LinkedHashSet<>();
            Map<String, String> fullOf = new HashMap<>(), readAs = new HashMap<>();
            List<String> via = new ArrayList<>();
            for (String[] k : kin) {
                if (k[0].equals(s[0])) continue;
                String a = at(k[0], true, k[2]), b = at(k[0], false, k[3]);
                String cand = null;
                if (k[1].equals(rel)) { if (subject && b.equals(other)) cand = a; if (!subject && a.equals(other)) cand = b; }
                if (rel.equals("married-to") || rel.equals("sibling-of")) { if (a.equals(other)) cand = cand == null ? b : cand; if (b.equals(other)) cand = cand == null ? a : cand; }
                if (rel.equals("child-of") && k[1].equals("parent-of")) { if (subject && a.equals(other)) cand = b; if (!subject && b.equals(other)) cand = a; }
                if (rel.equals("parent-of") && k[1].equals("child-of")) { if (subject && a.equals(other)) cand = b; if (!subject && b.equals(other)) cand = a; }
                // a person of a name: a description or a page is found by its own rules, never by a name alone
                if (cand == null || cand.equals(find(w)) || kind(cand) != Kind.FULL) continue;
                String sex = titleSex(wr);
                if (!sex.isEmpty() && !sexes(cand).isEmpty() && !sexes(cand).contains(sex)) continue;
                String full = null;
                for (String m : group(cand)) for (String n : names.getOrDefault(m, List.of())) if (full == null && partOf(wr, n)) full = n;
                if (full == null && established) {
                    String[] across = readAcross(wr, cand);
                    if (across != null) { full = across[0]; readAs.putIfAbsent(cand, across[1]); }
                }
                if (full == null) continue;
                cands.add(cand);
                fullOf.putIfAbsent(cand, full);
                via.add(k[0]);
            }
            if (cands.size() != 1) return false;
            String to = cands.iterator().next();
            if (!within.isEmpty() && !within.contains(to)) return false;
            List<String> ev = new ArrayList<>(List.of(s[0]));
            ev.addAll(via);
            String has = readAs.containsKey(to) ? "whose name has " + wr + " in it as the model reads it, " + readAs.get(to) : "whose name has " + wr + " in it";
            return mention(s[0], s[1], w, to, "L5", Grade.probable, ev, "\"" + wr + "\" is " + relationWords(rel, subject) + " " + nameOf(other) + ", as " + fullOf.get(to)
                    + " is, and " + fullOf.get(to) + " is the one person related so " + has + ".");
        }

        /**
         * L5b across scripts: a given name alone in characters (健二, as a page writes a son or a mother) and a full name in Latin letters with
         * the given name as the model reads it (Kenji Morita), or a given name alone in Latin letters and a full name in characters whose
         * given part the model reads so. The family name is not compared: a child carries the family name of the person both are related
         * to, and a wife may, and nothing says it here. {the full name, the reading}; null when it does not read so, or when the reading is
         * not known yet (it is then asked for).
         */
        String[] readAcross(String wr, String cand) {
            String u = untitled(wr).strip();
            String sc = FamilyForms.script(u);
            boolean han = sc.equals("han") && givenAlone(wr), latin = sc.equals("latin") && words(u).size() == 1 && givenAlone(wr);
            if (!han && !latin) return null;
            for (String m : group(cand)) for (String n : names.getOrDefault(m, List.of())) {
                if (detached.getOrDefault(m, Set.of()).contains(n) || initials(n)) continue;
                String un = untitled(n), nsc = FamilyForms.script(un);
                List<String> rs;
                List<String> against;
                if (han && nsc.equals("latin") && words(un).size() >= 2) {
                    rs = readings.get(han(u) + GIVEN);
                    if (rs == null) { wantedReadings.add(han(u) + GIVEN); return null; }
                    against = words(un);
                } else if (latin && nsc.equals("han") && han(un).matches("\\p{IsHan}{3,6}")) {
                    String[] part = hanParts(han(un));
                    if (part == null) continue;
                    rs = readings.get(part[1] + GIVEN);
                    if (rs == null) { wantedReadings.add(part[1] + GIVEN); continue; }
                    against = words(u);
                } else continue;
                for (String r : rs) for (String x : against) if (FamilyForms.latinKey(x).equals(FamilyForms.latinKey(FamilyForms.hepburn(r)))) return new String[]{n, r};
            }
            return null;
        }

        /**
         * L5b where the other side of the claim is a name alone as well: "太郎 married to ハル" and "大野太郎 married to ハル" on one page. The
         * same short name in the same source is one person of that source, so the one full name the same source relates to it the same way is
         * the person. Probable; nothing across sources, where a given name alone may be anybody.
         */
        boolean relatedThroughAShortName(String w, String[] s, String wr, String rel, boolean subject, String other, Set<String> within) {
            Set<String> src = new HashSet<>(sources(s[0]));
            Set<String> cands = new LinkedHashSet<>();
            Map<String, String> fullOf = new HashMap<>();
            List<String> via = new ArrayList<>();
            for (String[] k : kin) {
                if (k[0].equals(s[0]) || sources(k[0]).stream().noneMatch(src::contains)) continue;
                String a = at(k[0], true, k[2]), b = at(k[0], false, k[3]);
                String cand = null;
                if (k[1].equals(rel)) { if (subject && b.equals(other)) cand = a; if (!subject && a.equals(other)) cand = b; }
                if (rel.equals("married-to") || rel.equals("sibling-of")) { if (a.equals(other)) cand = cand == null ? b : cand; if (b.equals(other)) cand = cand == null ? a : cand; }
                if (rel.equals("child-of") && k[1].equals("parent-of")) { if (subject && a.equals(other)) cand = b; if (!subject && b.equals(other)) cand = a; }
                if (rel.equals("parent-of") && k[1].equals("child-of")) { if (subject && a.equals(other)) cand = b; if (!subject && b.equals(other)) cand = a; }
                if (cand == null || cand.equals(find(w)) || kind(cand) != Kind.FULL) continue;
                String full = null;
                for (String m : group(cand)) for (String n : names.getOrDefault(m, List.of())) if (full == null && partOf(wr, n)) full = n;
                if (full == null) continue;
                cands.add(cand);
                fullOf.putIfAbsent(cand, full);
                via.add(k[0]);
            }
            if (cands.size() != 1) return false;
            String to = cands.iterator().next();
            if (!within.isEmpty() && !within.contains(to)) return false;
            List<String> ev = new ArrayList<>(List.of(s[0]));
            ev.addAll(via);
            return mention(s[0], s[1], w, to, "L5", Grade.probable, ev, "In " + sourceName(sources(s[0])) + ", \"" + wr + "\" is " + relationWords(rel, subject) + " \"" + label.getOrDefault(other, other)
                    + "\", as " + fullOf.get(to) + " is there, and " + fullOf.get(to) + " is the one person that source relates so whose name has " + wr + " in it.");
        }

        /** A relation's family: parent and child, husband and wife, brother and sister. */
        static String family(String rel) { return rel.equals("married-to") ? "spouse" : rel.equals("sibling-of") ? "sibling" : "parent"; }

        String relationWords(String rel, boolean subject) {
            return switch (rel) {
                case "married-to" -> "married to";
                case "sibling-of" -> "a brother or sister of";
                case "child-of" -> subject ? "a child of" : "a parent of";
                case "parent-of" -> subject ? "a parent of" : "a child of";
                default -> rel.replace('-', ' ') + (subject ? "" : " (the other way round)");
            };
        }

        private final Map<String, String> ownerTexts = new HashMap<>();

        /** The text of a file the library holds, as it was read; "" when it holds none. */
        String ownerText(String locator) {
            return ownerTexts.computeIfAbsent(locator, l -> {
                try { Path raw = RawCapture.find(store, l); return raw == null ? "" : RawCapture.read(raw)[2]; } catch (Exception e) { return ""; }
            });
        }

        /** Whether a claim is the owner's own word: from the owner's own notes, or what the owner told the library. */
        boolean ownerWords(Finding f) {
            for (Finding.Source src : f.sources()) if (ownerNotes.contains(src.locator()) || FamilyClose.ownerSource(src)) return true;
            return false;
        }

        /** The entries of the owner, as the reads write the owner's own words about themself: "the owner of this library". */
        List<String> ownerEntries() {
            List<String> out = new ArrayList<>();
            for (String e : kind.keySet()) if (label.getOrDefault(e, "").strip().equalsIgnoreCase(FamilyClose.OWNER)) out.add(e);
            return out;
        }

        /**
         * L2, the owner is a person: the owner's own notes say who they are ("i am kenji morita - 森田健二", "Kenji Morita (me)", 私は森田健二です), so
         * "the owner of this library" is the person of that name, proved, and every name the line gives is one person with them. The owner's
         * own notes are the short texts of the family's folder that no other writer is named for, and what the owner told the library; their
         * words are the owner's and read as they stand. Two lines of them that name two people say nothing.
         */
        /**
         * The owner's word that two written names are one person's: a line of the owner's own notes that begins with a name and says it is
         * "also written" another way ({@link #ALSO_WRITTEN}). Proved: it is how the family tells the library that two names one character apart
         * (L8, kept apart until the family says otherwise) are one man.
         */
        void alsoWritten() {
            List<String> texts = new ArrayList<>();
            for (String loc : ownerNotes) texts.add(ownerText(loc));
            for (String t : texts) for (String line : t.split("\\R")) {
                Matcher m = ALSO_WRITTEN.matcher(line);
                if (!m.find()) continue;
                String other = (m.group("b") != null ? m.group("b") : m.group("bj")).strip();
                String first = FamilyFolder.nameOnly(line.substring(0, m.start()).replaceAll("[\\s(（、,]+$", ""));
                if (first == null || other.isEmpty()) continue;
                // the name the line begins with: the words before its first dash or its first relation words
                first = first.split("\\s+(?:is|was|-|–|—)\\s+|\\s+-\\s+", 2)[0].strip();
                otherSpellings.add(other);
                String a = holder(first, null), b = holder(other, a);
                if (a == null || b == null || find(a).equals(find(b))) continue;
                String l = line.strip();
                // the family's word: two names one character apart are one man's when the owner says so
                for (String x : group(find(a))) for (String y : group(find(b))) ownerSaidOne.add(Graph.pair(x, y));
                join(b, anyMember(a), "L2", Grade.proved, List.of(), "Your own notes say \"" + (l.length() > 90 ? l.substring(0, 90) + "…" : l) + "\": " + other + " is another way to write " + first + ".");
            }
        }

        void selfIntroduction() {
            List<String> owners = ownerEntries();
            if (owners.isEmpty()) return;
            List<String> texts = new ArrayList<>();
            for (String loc : ownerNotes) texts.add(ownerText(loc));
            for (Finding f : claims.values()) if (!gone(f) && f.sources().stream().anyMatch(FamilyClose::ownerSource)) texts.add(FamilyChecks.quoteOf(f));
            List<Set<String>> said = new ArrayList<>();
            String line = null;
            for (String t : texts) for (String l : t.split("\\R")) {
                Set<String> to = new LinkedHashSet<>();
                for (String form : selfNames(l)) { String k = key(form); if (!k.isEmpty()) for (String e : byKey.getOrDefault(k, List.of())) to.add(find(e)); }
                if (to.isEmpty()) continue;
                said.add(to);
                if (line == null) line = l.strip();
            }
            if (said.isEmpty()) return;
            for (Set<String> a : said) for (Set<String> b : said) if (a.stream().noneMatch(b::contains)) return;
            Set<String> all = new LinkedHashSet<>();
            said.forEach(all::addAll);
            for (String o : owners) for (String r : all) {
                if (find(o).equals(find(r))) continue;
                join(o, anyMember(r), "L2", Grade.proved, List.of(), "Your own notes say \"" + (line.length() > 90 ? line.substring(0, 90) + "…" : line) + "\": the owner of this library is " + nameOf(r) + ".");
            }
        }

        /** The groups of the owner: where the owner's entries are now. */
        Set<String> ownerRoots() {
            Set<String> out = new LinkedHashSet<>();
            for (String o : ownerEntries()) out.add(find(o));
            return out;
        }

        /**
         * The starts the owner's own words give a description of the owner's relative: {the steps, the person, why}. A note beside a page ("my
         * father's father" beside a page about the person), and a claim of the owner's own words that relates the owner to a person and says
         * how in the owner's words ("my father is 森田勇", "森田正一 is my dad's father"); a relation the claim files must be the one its words say.
         */
        List<Object[]> ownerChains() {
            List<Object[]> out = new ArrayList<>();
            for (String p : kind.keySet()) {
                if (kind(p) != Kind.PAGE) continue;
                List<String> chain = atoms(ownChain(pageNote(label.get(p))));
                if (!chain.isEmpty()) out.add(new Object[]{chain, p, "the note you wrote beside " + label.get(p).replaceAll(" — .*$", "") + " calls " + nameOf(p) + " your " + said(chain)});
            }
            // the relatives the owner's own words name, from the owner outwards: "my father is X" relates X to the owner, and "Y is my dad's
            // father" relates Y to X, the person the owner's words already call their father
            Map<String, List<String>> named = new LinkedHashMap<>();
            for (String o : ownerRoots()) named.put(o, List.of());
            for (int depth = 0; depth < 4; depth++) {
                Map<String, List<String>> more = new LinkedHashMap<>();
                for (String[] k : kin) {
                    Finding f = claims.get(k[0]);
                    if (f == null || gone(f) || !ownerWords(f)) continue;
                    String a = at(k[0], true, k[2]), b = at(k[0], false, k[3]);
                    String quote = FamilyChecks.quoteOf(f);
                    List<String> chain = oneChain(quote);
                    if (chain == null || chain.isEmpty()) continue;
                    for (String[] xy : new String[][]{{a, b}, {b, a}}) {
                        List<String> known = named.get(xy[1]);
                        if (known == null || named.containsKey(xy[0]) || !hasFull(xy[0]) || chain.size() <= known.size() || !begins(chain, known)) continue;
                        // "X relative of <somebody the owner's words place>", with the owner's words for how from themself ("X - my mother's
                        // grandfather" filed as a relative of the mother): the steps on from that person; a relation the claim files must be
                        // the one step the words say
                        boolean loose = k[1].equals("relative-of");
                        List<String> rest = chain.subList(known.size(), chain.size());
                        if (!loose && (rest.size() != 1 || !saysSo(k[1], xy[0].equals(a), rest, true))) continue;
                        more.merge(xy[0], chain, (p, q) -> { List<String> one = sameRelation(p, q); return one == null ? List.of() : one; });
                    }
                }
                // "森田健吾 is 森田正一's younger brother" in the owner's notes: from the person the owner's words already place
                for (Object[] st : statedByName()) {
                    String a = (String) st[0], b = (String) st[1];
                    @SuppressWarnings("unchecked") List<String> steps = (List<String>) st[2];
                    List<String> known = named.get(b);
                    if (known == null || named.containsKey(a) || !hasFull(a)) continue;
                    List<String> chain = new ArrayList<>(known);
                    chain.addAll(steps);
                    more.merge(a, chain, (p, q) -> { List<String> one = sameRelation(p, q); return one == null ? List.of() : one; });
                }
                more.values().removeIf(List::isEmpty);
                if (more.isEmpty()) break;
                named.putAll(more);
            }
            for (Map.Entry<String, List<String>> e : named.entrySet())
                if (!e.getValue().isEmpty()) out.add(new Object[]{e.getValue(), e.getKey(), "your own words call " + nameOf(e.getKey()) + " your " + said(e.getValue())});
            return out;
        }

        /**
         * What the owner's own notes state from a named person, line by line: "森田健吾 is 森田正一's younger brother" gives {森田健吾's group,
         * 森田正一's group, [younger brother]}, for the people the library holds under those names. The text is the owner's own, and read as
         * it stands; a line whose names the library does not hold says nothing yet.
         */
        List<Object[]> statedByName() {
            if (stated != null) return stated;
            List<Object[]> out = new ArrayList<>();
            List<String> texts = new ArrayList<>();
            for (String loc : ownerNotes) texts.add(ownerText(loc));
            for (Finding f : claims.values()) if (!gone(f) && f.sources().stream().anyMatch(FamilyClose::ownerSource)) texts.add(FamilyChecks.quoteOf(f));
            for (String t : texts) for (String line : t.split("\\R")) {
                String[] st = statedLine(line);
                if (st == null) continue;
                String a = holder(st[0], null), b = holder(st[1], null);
                if (a == null || b == null || find(a).equals(find(b))) continue;
                out.add(new Object[]{find(a), find(b), atoms(steps(st[2]))});
            }
            stated = out;
            return out;
        }

        /** The brothers or sisters of a person the owner's notes name as such, for a step of the walk ("younger brother"): their groups. */
        Set<String> statedSiblings(String from, String step) {
            Set<String> out = new LinkedHashSet<>();
            String want = step.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
            for (Object[] st : statedByName()) {
                @SuppressWarnings("unchecked") List<String> steps = (List<String>) st[2];
                if (steps.size() != 1 || !find((String) st[1]).equals(from)) continue;
                String said = steps.get(0);
                if (!bare(said).equals(bare(want))) continue;
                // "younger brother" in the walk wants a younger brother; "brother" takes any the notes name
                if (!bare(want).equals(want) && !said.equals(want)) continue;
                out.add(find((String) st[0]));
            }
            return out;
        }

        /**
         * Whether a description's steps begin with the steps the owner's words give a person: the same steps, where a step of the owner's words
         * may say more than the description's ("my younger brother" is one of the people "the owner's brother" can be, and two brothers leave
         * it open).
         */
        static boolean begins(List<String> steps, List<String> chain) {
            for (int i = 0; i < chain.size(); i++) {
                String c = chain.get(i).replace(GRAND_PARENT, "parent"), d = steps.get(i).replace(GRAND_PARENT, "parent");
                if (!c.equals(d) && !c.replaceAll("^(?:elder|eldest|older|oldest|younger|youngest|first|second|third)\\s+", "").equals(d)) return false;
            }
            return true;
        }

        /**
         * Whether a relation a claim files is the one its words say of the person: "my father is X" and X parent of the owner. {@code steps}:
         * the steps from the other person of the claim to this one. "Relative of" says nothing against any steps, but only from the owner
         * themself ({@code filed} false); from anybody else the claim must file the one step it says.
         */
        static boolean saysSo(String rel, boolean personIsSubject, List<String> steps, boolean filed) {
            String last = steps.get(steps.size() - 1).replaceAll("^(?:elder|eldest|older|oldest|younger|youngest|first|second|third)\\s+", "");
            Set<String> up = Set.of("father", "mother", "parent"), down = Set.of("son", "daughter", "child");
            return switch (rel) {
                case "parent-of" -> steps.size() == 1 && (personIsSubject ? up : down).contains(last);
                case "child-of" -> steps.size() == 1 && (personIsSubject ? down : up).contains(last);
                case "sibling-of" -> steps.size() == 1 && Set.of("brother", "sister").contains(last);
                case "married-to" -> steps.size() == 1 && Set.of("husband", "wife", "spouse").contains(last);
                default -> !filed;
            };
        }

        /**
         * L2 and L3, a description with one answer. The owner's own words say how they are related to a person (a note beside a page, "my
         * father is X" in their own notes), and a description of the owner in the same words is that person (L2); a description of the owner
         * that goes further walks on from there, or from the owner, when the owner's own notes say who they are. A description that starts
         * from a person the library holds is found by walking the stated relations; proved when each step has one answer by nature (a father,
         * a mother, a husband, a wife) and the answer is one, probable when one is known and others could exist (a grandfather has two),
         * nothing when more than one is known. "The writer of <file>" is the person the family's notes say wrote it.
         */
        void described() {
            List<Object[]> chains = ownerChains();
            Set<String> owner = ownerRoots();
            String anchored = null;
            for (String r : owner) if (group(r).stream().anyMatch(m -> kind(m) == Kind.FULL)) anchored = anchored == null ? r : anchored.equals(r) ? r : "";
            for (String d : new ArrayList<>(kind.keySet())) {
                if (kind(d) != Kind.DESCRIPTION) continue;
                String l = label.get(d);
                String[] parts = description(l);
                // "the writer of <file>" itself, with no relation after it
                if (parts == null && l.toLowerCase(Locale.ROOT).startsWith("the writer of ") && l.matches("(?s).*\\.[A-Za-z0-9]{2,4}")) { wrote(d, l.substring("the writer of ".length()).strip()); continue; }
                // "the speaker in <a talk>": the one who gave it, whom the notes name as its writer. "The speaker in <a book>, part N" is somebody
                // the book quotes, never its writer
                if (parts == null && l.toLowerCase(Locale.ROOT).startsWith("the speaker in ") && !l.matches("(?s).*,\\s*part\\s+\\d+\\s*$")) {
                    if (!wrote(d, l.substring("the speaker in ".length()).replaceFirst("(?i)^(?:the\\s+)?(?:talk|speech|lecture|interview|address|paper)\\s+", "").strip()))
                        for (String src : sourcesOf(d)) if (src.startsWith("file:") && wrote(d, sourceName(List.of(src)))) break;   // the talk's own file
                    continue;
                }
                if (parts == null) continue;
                List<String> steps = atoms(steps(parts[1]));
                // the description's own claims may say which side ("my grandparents, my father's parents" is read as "X's grandmother is a
                // parent of X's father"): the walk goes up that side only
                String side = sideOfGrandparent(d, parts[0]);
                if (side != null && !steps.isEmpty() && steps.get(0).equals(GRAND_PARENT)) { steps = new ArrayList<>(steps); steps.set(0, side); }
                String start = null;
                List<String> rest = steps;
                String startWhy = null;
                if (parts[0].equalsIgnoreCase(FamilyClose.OWNER)) {
                    // the longest start the owner's own words give; two people for it say nothing
                    int best = 0;
                    Set<String> starts = new LinkedHashSet<>();
                    String why = null;
                    for (Object[] c : chains) {
                        @SuppressWarnings("unchecked") List<String> chain = (List<String>) c[0];
                        if (chain.size() > steps.size() || !begins(steps, chain) || chain.size() < best) continue;
                        if (chain.size() > best) { best = chain.size(); starts.clear(); why = (String) c[2]; }
                        starts.add(find((String) c[1]));
                    }
                    if (best > 0 && starts.size() == 1) { start = starts.iterator().next(); rest = steps.subList(best, steps.size()); startWhy = why; }
                    else if (best == 0 && anchored != null && !anchored.isEmpty()) { start = anchored; startWhy = "your own notes say you are " + nameOf(anchored); }
                } else if (parts[0].toLowerCase(Locale.ROOT).matches("(?s)the (?:writer of|speaker in) .*")) {
                    // "the writer of <file>'s mother", "the speaker in <a talk>'s mother": from the person the notes say wrote the file, named in
                    // the words or as the file the description's own claims come from
                    String file = parts[0].substring("the writer of ".length()).strip();
                    Object[] w = writerOf(file);
                    if (w == null) for (String src : sourcesOf(d)) if (src.startsWith("file:") && (w = writerOf(sourceName(List.of(src)))) != null) { file = sourceName(List.of(src)); break; }
                    if (w != null) { start = (String) w[0]; startWhy = "your notes say " + nameOf(start) + " wrote " + file; }
                } else {
                    String h = holder(parts[0], d);
                    if (h != null) { start = find(h); startWhy = parts[0] + " is " + nameOf(h); }
                    else if (fromAFamilyName(d, parts[0], steps)) continue;
                }
                if (start == null) continue;
                Set<String> at = new LinkedHashSet<>(List.of(start));
                boolean proved = true;
                for (String step : rest) {
                    String plain = step.replaceAll("^(?:late|maternal|paternal)\\s+", "");
                    Set<String> next = new LinkedHashSet<>();
                    // a brother or a sister: only one the owner's own notes name as such ("森田健吾 is 森田正一's younger brother"), or the one
                    // the name the description carries fits
                    if (Set.of("brother", "sister").contains(bare(plain))) { for (String x : at) next.addAll(statedSiblings(x, plain)); if (next.isEmpty()) next = byCarriedName(d, at, plain); if (next.isEmpty()) { at = Set.of(); break; } at = next; proved = false; if (at.size() != 1) break; continue; }
                    // a son or a daughter: the library holding one is no sign the person had one; the walk stops there, unless the description
                    // carries a name ("my son Ichiro" filed as "Kenji Morita's son", named Ichiro) that one established child has
                    if (!BOUNDED.contains(plain)) { next = byCarriedName(d, at, plain); if (next.size() != 1) { at = Set.of(); break; } at = next; proved = false; continue; }
                    // the description's own source knows whom it means: a memoir's "my mother" is the mother the memoir records, whatever a
                    // tree records; where that source records one, the walk takes it (probable), else the established relations
                    Set<String> own = new LinkedHashSet<>();
                    for (String x : at) own.addAll(recordedBy(x, step, sourcesOf(d)));
                    if (own.size() == 1) { at = own; proved = false; continue; }
                    for (String x : at) next.addAll(step(x, step));
                    if (!List.of("father", "mother", "husband", "wife").contains(plain)) proved = false;
                    at = next;
                    // a parent of one of two parents is still one of the people a grandparent can be; any other step wants one answer
                    if (at.isEmpty() || at.size() > 1 && !plain.equals("parent") && !plain.equals(GRAND_PARENT)) break;
                }
                if (at.size() != 1) continue;
                String to = at.iterator().next();
                if (find(to).equals(find(d))) continue;
                String why = "\"" + l + "\" is " + nameOf(to) + ": " + startWhy + (rest.isEmpty() ? "." : ", and " + said(rest) + " leads to " + nameOf(to) + " by the relations the library holds.");
                join(d, to, rest.isEmpty() ? "L2" : "L3", proved || rest.isEmpty() ? Grade.proved : Grade.probable, List.of(), why);
            }
        }

        /**
         * "father" or "mother" when the description's own claims make it a parent of "<the same holder>'s father" or "…'s mother", and of
         * only one of the two; null otherwise.
         */
        String sideOfGrandparent(String d, String holder) {
            String h = holder.replace('’', '\'').strip().toLowerCase(Locale.ROOT);
            Set<String> found = new HashSet<>();
            for (String[] s : sidesOf.getOrDefault(d, List.of())) {
                Finding f = claims.get(s[0]);
                if (f == null || f.triple() == null) continue;
                String rel = g.predicateOf(f.triple().predicate());
                boolean up = rel.equals("parent-of") && s[1].equals("subject") || rel.equals("child-of") && s[1].equals("object");
                if (!up) continue;
                String other = (s[1].equals("subject") ? f.triple().object() : f.triple().subject()).replace('’', '\'').strip().toLowerCase(Locale.ROOT);
                Matcher m = Pattern.compile("^" + Pattern.quote(h) + "'s (father|mother)$").matcher(other);
                if (m.matches()) found.add(m.group(1));
            }
            return found.size() == 1 ? found.iterator().next() : null;
        }

        /** Whether a group holds an entry of a full name. */
        boolean hasFull(String root) { for (String m : group(root)) if (kind(m) == Kind.FULL) return true; return false; }

        /**
         * L4, a note beside a page: what the owner wrote beside a page's address was filed as "About <the page's subject>: <the owner's
         * words>", told by the owner, so the description those words make ("the owner of this library's mother's uncle") is the page's subject,
         * by the note's own claim. Proved, or probable where the words hedge ("I think"); only where nothing else resolved the description,
         * and only where the note's words say the description's steps and the subject is the one person the note relates it to. The same
         * words beside two pages: each mention goes to its own page's subject, and the entry stays.
         */
        void linkNotes() {
            String prefix = FamilyClose.OWNER + "'s ";
            // the people the owner's own words place, and where: a note cannot make one of them somebody else ("I think this is my father's
            // cousin" beside a page about the owner's father says the page is about somebody else, not that the father is his own cousin)
            Map<String, List<String>> placed = new HashMap<>();
            for (String o : ownerRoots()) placed.put(o, List.of());
            for (Object[] c : ownerChains()) { @SuppressWarnings("unchecked") List<String> chain = (List<String>) c[0]; placed.putIfAbsent(find((String) c[1]), chain); }
            for (String d : new ArrayList<>(kind.keySet())) {
                if (kind(d) != Kind.DESCRIPTION || hasFull(find(d))) continue;
                String l = label.get(d).replace('’', '\'');
                if (!l.regionMatches(true, 0, prefix, 0, prefix.length())) continue;
                List<String> steps = atoms(steps(l.substring(prefix.length())));
                Map<String, Set<String>> subjectOf = new LinkedHashMap<>();   // the note's page → the people it relates the description to
                Map<String, List<String[]>> mentionsOf = new LinkedHashMap<>();   // the note's page → the description's sides of its claims
                Map<String, String> toldOf = new HashMap<>();
                Set<String> hedged = new HashSet<>();
                for (String[] s : sidesOf.getOrDefault(d, List.of())) {
                    Finding f = claims.get(s[0]);
                    if (f == null || gone(f) || f.triple() == null) continue;
                    String url = null;
                    for (Finding.Source src : f.sources()) if (src.locator() != null && src.locator().startsWith("told://link-note/")) url = src.locator().substring("told://link-note/".length());
                    if (url == null) continue;
                    String quote = FamilyChecks.quoteOf(f);
                    // the note's own words, after "About <subject>:"
                    String told = quote.replaceFirst("(?is)^\\s*about\\s+.+?[:：]\\s*", "");
                    if (!noteSteps(told).equals(steps)) continue;
                    String other = s[1].equals("subject") ? at(s[0], false, g.nodeIdOf(f.triple().object())) : at(s[0], true, g.nodeIdOf(f.triple().subject()));
                    if (!hasFull(other)) continue;
                    // "my father's cousin" filed as a relative of "my father": the words relate the description to where it starts, and say
                    // nothing of whom the page is about
                    String[] start = description((s[1].equals("subject") ? f.triple().object() : f.triple().subject()).replace('’', '\''));
                    if (start != null && start[0].equalsIgnoreCase(FamilyClose.OWNER)) {
                        List<String> from = atoms(steps(start[1]));
                        if (from.size() < steps.size() && begins(steps, from)) continue;
                    }
                    List<String> where = placed.get(other);
                    if (where != null && !(where.size() == steps.size() && begins(steps, where))) continue;
                    // "About <subject>:" in the words: the subject must be this person
                    Matcher about = Pattern.compile("(?is)^\\s*about\\s+(.+?)[:：]").matcher(quote);
                    if (about.find()) { String h = holder(about.group(1).strip().replaceAll("\\s+[-–—|:]\\s+[^-–—|:]{1,40}$", ""), null); if (h != null && !find(h).equals(other)) continue; }
                    subjectOf.computeIfAbsent(url, x -> new LinkedHashSet<>()).add(other);
                    mentionsOf.computeIfAbsent(url, x -> new ArrayList<>()).add(s);
                    toldOf.putIfAbsent(url, told.strip());
                    if (HEDGED.matcher(told).find()) hedged.add(url);
                }
                subjectOf.values().removeIf(x -> x.size() != 1);   // a note that relates the description to two people says nothing
                if (subjectOf.isEmpty()) continue;
                Set<String> all = new LinkedHashSet<>();
                subjectOf.values().forEach(all::addAll);
                Function<String, String> why = url -> "The note you wrote beside " + url + " says \"" + toldOf.get(url) + "\", and it was filed as about the page's subject, "
                        + nameOf(subjectOf.get(url).iterator().next()) + ": \"" + l + "\" there is " + nameOf(subjectOf.get(url).iterator().next()) + ".";
                if (all.size() == 1) {
                    String url = subjectOf.keySet().iterator().next();
                    List<String> ev = new ArrayList<>();
                    for (List<String[]> ms : mentionsOf.values()) for (String[] s : ms) ev.add(s[0]);
                    join(d, anyMember(all.iterator().next()), "L4", hedged.isEmpty() ? Grade.proved : Grade.probable, ev, why.apply(url));
                    continue;
                }
                // the same words beside two pages: each mention is its own page's subject. The claim relates the description to that very
                // person, as the reader's way of filing "About X: my mother's uncle", so it is no bar here
                for (Map.Entry<String, Set<String>> e : subjectOf.entrySet()) for (String[] s : mentionsOf.get(e.getKey())) {
                    String key = Graph.Links.side(s[0], s[1].equals("subject"));
                    if (mentionLinks.containsKey(key)) continue;
                    String target = e.getValue().iterator().next();
                    sides.put(key, target);
                    Link link = new Link(s[0], s[1], d, written(s[0], s[1]), target, label.getOrDefault(target, target), "L4", hedged.contains(e.getKey()) ? Grade.probable : Grade.proved, List.of(s[0]), why.apply(e.getKey()));
                    mentionLinks.put(key, link);
                    links.add(link);
                }
            }
        }

        /**
         * "Morita's father", "Morita's wife": a description that starts from a family name alone, as a memoir's "my father" is filed with the
         * narrator's family name. The one child it has by the claims (or the one husband or wife), when that person is one by the evidence and
         * carries the family name, leads to their parent of that sex (or their husband or wife), the one the library holds established.
         * Probable. False when nothing is joined.
         */
        boolean fromAFamilyName(String d, String anchor, List<String> steps) {
            if (steps.size() != 1 || anchor.isBlank()) return false;
            String word = bare(steps.get(0));
            boolean parent = Set.of("father", "mother", "parent").contains(word), spouse = Set.of("wife", "husband", "spouse").contains(word);
            if (!parent && !spouse) return false;
            if (!cjk(anchor) && words(anchor).size() != 1) return false;
            Set<String> via = parent ? children(find(d)) : spouses(find(d));
            via.removeIf(x -> kind(x) != Kind.FULL || !oneByEvidence(x) || !carriesFamily(x, anchor));
            if (via.size() != 1) return false;
            String x = via.iterator().next();
            Set<String> to = step(x, word);
            if (to.size() != 1) return false;
            String t = to.iterator().next();
            if (find(t).equals(find(d))) return false;
            return join(d, t, "L3", Grade.probable, List.of(), "\"" + label.get(d) + "\" is " + nameOf(t) + ": the one " + (parent ? "child" : "husband or wife") + " it has by the claims is "
                    + nameOf(x) + ", one person by the evidence and a " + anchor + ", and " + nameOf(t) + " is the one " + word + " of " + nameOf(x) + " the library holds established.");
        }

        /** Whether a group's names carry a family name: a word of a name in letters, or the beginning of a name in characters. */
        boolean carriesFamily(String root, String family) {
            for (String m : group(root)) for (String n : names.getOrDefault(m, List.of())) {
                String u = untitled(n);
                if (cjk(family) ? han(u).startsWith(han(family)) && han(u).length() > han(family).length() : words(u).contains(words(family).get(0))) return true;
            }
            return false;
        }

        /**
         * The people a step of the walk leads to by a name the description carries: "Kenji Morita's brother" that a read named Osamu leads to
         * the established brother of Kenji Morita whose name has Osamu in it; a son or daughter the same among the established children.
         * Empty where the description carries no name, or where none or several fit.
         */
        Set<String> byCarriedName(String d, Set<String> at, String step) {
            List<String> carried = new ArrayList<>();
            for (String n : names.getOrDefault(d, List.of())) if (!n.equals(label.get(d)) && !FamilyQuestions.placeholder(n) && description(n) == null) carried.add(n);
            Set<String> out = new LinkedHashSet<>();
            if (carried.isEmpty()) return out;
            String w = bare(step);
            for (String x : at) {
                Set<String> cands = switch (w) {
                    case "brother" -> sexed(wSiblings(x), "male");
                    case "sister" -> sexed(wSiblings(x), "female");
                    case "son" -> sexed(wChildren(x), "male");
                    case "daughter" -> sexed(wChildren(x), "female");
                    case "child" -> wChildren(x);
                    default -> Set.of();
                };
                for (String c : cands) for (String m : group(c)) for (String n : names.getOrDefault(m, List.of())) for (String have : carried)
                    if (partOf(have, n) || key(have).equals(key(n)) && !key(n).isEmpty()) out.add(find(c));
            }
            return out;
        }

        /** Whether a block keeps a possible link from being shown: any block but the one-character rule, which is what such a link shows. */
        boolean blockedForShowing(String a, String b) {
            String block = blocked(a, b);
            return block != null && !block.contains("differ by one character");
        }

        /** The sources of the claims on an entry. */
        Set<String> sourcesOf(String entry) {
            Set<String> out = new HashSet<>();
            for (String[] s : sidesOf.getOrDefault(entry, List.of())) out.addAll(sources(s[0]));
            return out;
        }

        /**
         * The people a step of the walk leads to by the claims of given sources alone: the father, mother, husband, wife, son or daughter of a
         * person as those sources record them, whatever other sources say. Empty for a step the walk does not take this way.
         */
        Set<String> recordedBy(String from, String word, Set<String> src) {
            if (src.isEmpty()) return Set.of();
            String w = bare(word).replaceAll("^(?:late|maternal|paternal)\\s+", "");
            Set<String> fromRels, toRels;
            switch (w) {
                case "father", "mother", "parent" -> { fromRels = Set.of("child-of"); toRels = Set.of("parent-of"); }
                case "son", "daughter", "child" -> { fromRels = Set.of("parent-of"); toRels = Set.of("child-of"); }
                case "husband", "wife", "spouse" -> { fromRels = Set.of("married-to"); toRels = Set.of("married-to"); }
                default -> { return Set.of(); }
            }
            Set<String> out = new LinkedHashSet<>();
            for (String[] k : kin) {
                if (sources(k[0]).stream().noneMatch(src::contains)) continue;
                String a = at(k[0], true, k[2]), b = at(k[0], false, k[3]);
                String other = fromRels.contains(k[1]) && a.equals(from) ? b : toRels.contains(k[1]) && b.equals(from) ? a : null;
                if (other == null || other.equals(from) || kind(other) != Kind.FULL) continue;
                out.add(other);
            }
            String sex = switch (w) { case "father", "husband", "son" -> "male"; case "mother", "wife", "daughter" -> "female"; default -> ""; };
            return sex.isEmpty() ? out : sexed(out, sex);
        }

        /** The relation words a person has a known number of: one father, one mother, one husband or wife at a time, two grandfathers. */
        static final Set<String> BOUNDED = Set.of("father", "mother", "parent", GRAND_PARENT, "husband", "wife", "spouse", "grandfather", "grandmother", "grandparent");

        /** Whether a title and a file's name are one work: the same letters, the extension aside, or one the beginning of the other when a file's name cuts a long title short. */
        static boolean sameTitle(String title, String file) {
            // "the talk Two Homes", "the book - Two Homes": the words that say which work it is are no part of its title
            String kind = "(?i)^(?:the\\s+)?(?:book|file|text|account|memoirs?|diary|article|letter|talk|speech|lecture|interview|paper|address)\\s*[-–—:]?\\s+";
            String t = letters(title.replaceFirst(kind, "").replaceFirst("\\.[A-Za-z0-9]{2,4}$", "")), f = letters(file.replaceFirst(kind, "").replaceFirst("\\.[A-Za-z0-9]{2,4}$", ""));
            if (t.isEmpty() || f.isEmpty()) return false;
            // a file's name may cut a long title short, never the other way round
            return t.equals(f) || f.length() >= 16 && t.startsWith(f);
        }

        private static String letters(String s) { return Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", ""); }

        /** "The writer of <file>": the one person the family's notes say wrote that file. */
        boolean wrote(String d, String file) {
            Object[] w = writerOf(file);
            if (w == null) return false;
            String to = (String) w[0];
            @SuppressWarnings("unchecked") List<String> ev = (List<String>) w[1];
            return join(d, to, "L3", Grade.proved, ev, "Your notes say " + nameOf(to) + " wrote " + file + ", so its writer is " + nameOf(to) + ".");
        }

        /** The one person the family's notes say wrote a file, with the claims that say so: {the person, the claims}; null for nobody or several. */
        Object[] writerOf(String file) {
            Set<String> who = new LinkedHashSet<>();
            List<String> ev = new ArrayList<>();
            for (Finding f : claims.values()) {
                if (gone(f) || f.triple() == null) continue;
                String o = f.triple().object().strip();
                if (!o.toLowerCase(Locale.ROOT).matches("(?:wrote|is the writer of|is the author of|authored)\\s+.+")) continue;
                // "wrote Tom's Diary.txt", "wrote the book - Tom's Diary": the title, which a file's name may cut short
                String title = o.replaceFirst("(?i)^(?:wrote|is the writer of|is the author of|authored)\\s+(?:the\\s+(?:book|file|text|account|memoir|diary|article|letter)s?\\s*[-–—:]\\s*)?", "");
                if (!sameTitle(title, file)) continue;
                String s = g.nodeIdOf(f.triple().subject());
                if (!kind.containsKey(s) || kind(s) == Kind.DESCRIPTION) continue;
                who.add(find(at(f.id(), true, s)));
                ev.add(f.id());
            }
            if (who.size() != 1) return null;
            String to = who.iterator().next();
            return kind(to) == Kind.SHORT ? null : new Object[]{to, ev};
        }

        /**
         * The people a group is related to by these relations, each where the relation is established: the family accepted a claim of it, a
         * family tree's own data gives it (a tree file, a tree site), or claims from two sources that are not one another give it. A reader's
         * slip in one text does not carry a description to the wrong person.
         */
        Set<String> established(String root, Set<String> fromRels, Set<String> toRels) {
            Map<String, List<Finding>> by = new LinkedHashMap<>();
            for (String[] k : kin) {
                String a = at(k[0], true, k[2]), b = at(k[0], false, k[3]);
                String other = fromRels.contains(k[1]) && a.equals(root) ? b : toRels.contains(k[1]) && b.equals(root) ? a : null;
                if (other == null || other.equals(root) || claims.get(k[0]) == null) continue;
                by.computeIfAbsent(other, x -> new ArrayList<>()).add(claims.get(k[0]));
            }
            Set<String> out = new LinkedHashSet<>();
            for (Map.Entry<String, List<Finding>> e : by.entrySet()) {
                Set<String> origins = new HashSet<>();
                boolean sure = false;
                for (Finding f : e.getValue()) {
                    if (f.state() == Finding.State.accepted || "gedcom-import".equals(f.writer())) sure = true;
                    for (Finding.Source src : f.sources()) { if (src.locator().contains("geni.com/api/")) sure = true; origins.add(origin(src.locator())); }
                }
                if (sure || origins.size() >= 2) out.add(e.getKey());
            }
            return out;
        }

        /** Where a source comes from, for telling two sources apart: the file, the site, or the note the person wrote. */
        static String origin(String locator) {
            String l = locator == null ? "" : locator;
            Matcher m = Pattern.compile("^https?://([^/]+)").matcher(l);
            return m.find() ? m.group(1).toLowerCase(Locale.ROOT) : l;
        }

        Set<String> wParents(String r) { return established(r, Set.of("child-of"), Set.of("parent-of")); }
        Set<String> wChildren(String r) { return established(r, Set.of("parent-of"), Set.of("child-of")); }
        Set<String> wSpouses(String r) { return established(r, Set.of("married-to"), Set.of("married-to")); }
        Set<String> wSiblings(String r) {
            Set<String> s = established(r, Set.of("sibling-of"), Set.of("sibling-of"));
            for (String p : wParents(r)) s.addAll(wChildren(p));
            s.remove(r);
            return s;
        }

        /** One step of a walk from a person: the people a relation word leads to, by the relations the library holds established. */
        Set<String> step(String from, String word) {
            String w = word.replaceAll("^(?:late|only)\\s+", "");
            String order = w.matches("(?:elder|eldest|older|oldest)\\s.*") ? "older" : w.matches("(?:younger|youngest)\\s.*") ? "younger" : "";
            String side = w.matches("maternal\\s.*") ? "female" : w.matches("paternal\\s.*") ? "male" : "";
            w = w.replaceAll("^(?:(?:elder|eldest|older|oldest|younger|youngest|first|second|third|maternal|paternal)\\s+)+", "");
            Set<String> out = new LinkedHashSet<>();
            switch (w) {
                case "father" -> out.addAll(sexed(wParents(from), "male"));
                case "mother" -> out.addAll(sexed(wParents(from), "female"));
                case "parent" -> out.addAll(wParents(from));
                case GRAND_PARENT -> out.addAll(parents(from));
                case "son" -> out.addAll(sexed(wChildren(from), "male"));
                case "daughter" -> out.addAll(sexed(wChildren(from), "female"));
                case "child" -> out.addAll(wChildren(from));
                case "husband" -> out.addAll(sexed(wSpouses(from), "male"));
                case "wife" -> out.addAll(sexed(wSpouses(from), "female"));
                case "spouse" -> out.addAll(wSpouses(from));
                case "brother" -> out.addAll(sexed(wSiblings(from), "male"));
                case "sister" -> out.addAll(sexed(wSiblings(from), "female"));
                case "grandfather", "grandmother", "grandparent" -> {
                    Set<String> ps = side.isEmpty() ? parents(from) : sexed(wParents(from), side);
                    for (String p : ps) out.addAll(w.equals("grandparent") ? wParents(p) : sexed(wParents(p), w.equals("grandfather") ? "male" : "female"));
                }
                default -> { }
            }
            if (!order.isEmpty()) {
                Set<Integer> mine = years(from, "born");
                if (mine.size() != 1) return Set.of();
                int y = mine.iterator().next();
                out.removeIf(x -> { Set<Integer> b = years(x, "born"); return b.size() != 1 || (order.equals("older") ? b.iterator().next() >= y : b.iterator().next() <= y); });
            }
            return out;
        }

        /** The people of a set whose recorded sex is {@code sex}; those of no recorded sex only when none is recorded so. */
        Set<String> sexed(Set<String> people, String sex) {
            Set<String> known = new LinkedHashSet<>(), unknown = new LinkedHashSet<>();
            for (String p : people) { Set<String> s = sexes(p); if (s.contains(sex) && s.size() == 1) known.add(p); else if (s.isEmpty()) unknown.add(p); }
            return known.isEmpty() ? unknown : known;
        }

        /** L8, variant characters: two names in characters that differ by one character may be one person, and are never joined for it. */
        void variants() {
            List<String> hanEntries = new ArrayList<>();
            for (String e : kind.keySet()) if (kind(e) == Kind.FULL && FamilyForms.script(untitled(label.get(e))).equals("han") && find(e).equals(e)) hanEntries.add(e);
            for (int i = 0; i < hanEntries.size(); i++) for (int j = i + 1; j < hanEntries.size(); j++) {
                String a = hanEntries.get(i), b = hanEntries.get(j);
                String x = han(untitled(label.get(a))), y = han(untitled(label.get(b)));
                if (x.length() != y.length() || x.length() < 3) continue;
                int diff = 0;
                for (int k = 0; k < x.length(); k++) if (x.charAt(k) != y.charAt(k)) diff++;
                if (diff != 1 || find(a).equals(find(b))) continue;
                String block = blocked(a, b);
                if (block != null && !block.contains("differ by one character")) continue;   // the one-character rule is this link's own reason
                links.add(new Link("", "", a, label.get(a), b, label.get(b), "L8", Grade.possible, List.of(),
                        "The names " + label.get(a) + " and " + label.get(b) + " differ by one character; they stay two people until the family says otherwise."));
            }
        }

        String sourceName(List<String> locators) {
            if (locators.isEmpty()) return "the source";
            String l = locators.get(0);
            int slash = l.lastIndexOf('/');
            if (!l.startsWith("file:") || slash < 0) return l;
            try { return URLDecoder.decode(l.substring(slash + 1), StandardCharsets.UTF_8); } catch (IllegalArgumentException e) { return l.substring(slash + 1); }
        }

        // ── the result ───────────────────────────────────────────────────────────────────────────────────────────────

        /**
         * The name a group is shown with, and the member that carries it: a full name in characters when the sources give one, else the
         * fullest form; the member with more claims first where two are alike. Null when no member carries a name.
         */
        String[] bestName(String root) {
            String[] best = null;
            int score = -1;
            for (String m : group(root)) {
                if (kind(m) != Kind.FULL || g.node(m) == null) continue;
                int degree = Math.min(99, sidesOf.getOrDefault(m, List.of()).size());
                for (String n : names.getOrDefault(m, List.of())) {
                    boolean own = n.equals(label.get(m));
                    if (!own && (detached.getOrDefault(m, Set.of()).contains(n) || pageSubject(n) != null || FamilyQuestions.placeholder(n) || shortName(n) || unnamed(n) || FamilyForms.script(n).equals("kana"))) continue;
                    String plain = forms(n).get(0);
                    // the spelling the owner's own notes use comes before another source's: the family's, for a name the sources write two ways
                    int sc = (FamilyForms.script(plain).equals("han") ? 100000 : 0) + (FamilyForms.script(plain).equals("han") ? 0 : words(plain).size() * 1000) + (ownerWrites(n) ? 500 : 0) + degree * 2 + (own ? 1 : 0);
                    if (sc > score) { score = sc; best = new String[]{m, n}; }
                }
            }
            return best;
        }

        /** Whether the owner's own notes write this name as it stands. */
        boolean ownerWrites(String name) {
            String n = name.strip();
            if (n.isEmpty() || otherSpellings.contains(n)) return false;
            for (String loc : ownerNotes) { String t = ownerText(loc); if (t.contains(n) || KanjiForms.modern(t).contains(KanjiForms.modern(n))) return true; }
            return false;
        }

        String bestLabel(String root) { String[] b = bestName(root); return b == null ? null : b[1]; }

        String bestEntry(String root) {
            String[] b = bestName(root);
            if (b != null) return b[0];
            for (String m : group(root)) if (g.node(m) != null) return m;
            return root;
        }

        Result result(String stamp) {
            // each group's entry: the member with the best name; every member and every mention of the group points at it
            Map<String, String> entryOf = new HashMap<>();
            for (String r : members.keySet()) entryOf.put(r, bestEntry(r));
            Function<String, String> entry = x -> entryOf.getOrDefault(find(x), find(x));
            List<Link> out = new ArrayList<>();
            // a member joined into a group: the link that joined it on the way from the group's entry, pointing at the entry
            for (Map.Entry<String, Set<String>> m : members.entrySet()) {
                String e = entry.apply(m.getKey());
                Set<String> reached = new HashSet<>(List.of(e));
                List<String> queue = new ArrayList<>(List.of(e));
                while (!queue.isEmpty()) {
                    String at = queue.remove(0);
                    for (Link l : links) {
                        if (l.mention() || !l.joins()) continue;
                        String next = l.node().equals(at) ? l.person() : l.person().equals(at) ? l.node() : null;
                        if (next == null || !reached.add(next)) continue;
                        queue.add(next);
                        out.add(new Link("", "", next, label.getOrDefault(next, next), e, label.getOrDefault(e, e), l.rule(), l.grade(), l.evidence(), l.why()));
                    }
                }
            }
            // a possible link that nothing joined since, and that nothing blocks now: a block found after the link was made (a join since, a
            // relation worked out, as the view shows it) makes it no link at all
            Set<String> workedPairs = new HashSet<>();
            for (String[] w : siblingsParents()) workedPairs.add(Graph.pair(find(w[0]), find(w[1])));
            for (Link l : links) if (!l.mention() && !l.joins() && !find(l.node()).equals(find(l.person())) && !blockedForShowing(l.node(), l.person())
                    && !workedPairs.contains(Graph.pair(find(l.node()), find(l.person()))))
                out.add(new Link("", "", l.node(), l.written(), entry.apply(l.person()), label.getOrDefault(entry.apply(l.person()), l.personLabel()), l.rule(), l.grade(), l.evidence(), l.why()));
            // a mention linked to a person
            for (Link l : mentionLinks.values()) {
                String p = entry.apply(l.person());
                out.add(new Link(l.claim(), l.side(), l.node(), l.written(), p, label.getOrDefault(p, p), l.rule(), l.grade(), l.evidence(), l.why()));
            }
            // a mention the pass moved off a name alone to the entry of the full name it writes
            for (Map.Entry<String, String> s : sides.entrySet()) {
                if (mentionLinks.containsKey(s.getKey())) continue;
                String[] k = s.getKey().split("\\|");
                String p = entry.apply(s.getValue());
                String from = movedFrom.getOrDefault(s.getKey(), "");
                out.add(new Link(k[0], k[1], from, written(k[0], k[1]), p, label.getOrDefault(p, p), "L5", Grade.proved, List.of(k[0]),
                        "\"" + written(k[0], k[1]) + "\" is a full name, not another name of \"" + label.getOrDefault(from, from) + "\", which is a name alone."));
            }
            // a full name a name alone held: where the pass put it, whatever claim writes it
            Map<String, String> written = new LinkedHashMap<>();
            for (Map.Entry<String, String> s : sides.entrySet()) {
                if (mentionLinks.containsKey(s.getKey()) || !movedFrom.containsKey(s.getKey())) continue;
                String[] k = s.getKey().split("\\|");
                written.put(Vocabulary.norm(written(k[0], k[1])), entry.apply(s.getValue()));
            }
            Map<String, String> labels = new LinkedHashMap<>();
            Map<String, List<String>> shown = new LinkedHashMap<>();
            for (String r : members.keySet()) {
                String e = entry.apply(r);
                String best = bestLabel(r);
                if (best == null) continue;
                labels.put(e, best);
                List<String> other = new ArrayList<>();
                Set<String> seen = new HashSet<>(List.of(Vocabulary.norm(best)));
                for (String m : group(r)) {
                    if (kind(m) != Kind.FULL) continue;
                    for (String n : names.getOrDefault(m, List.of())) {
                        if (detached.getOrDefault(m, Set.of()).contains(n) || !FamilyNames.keepAsOtherName(best, n) || pageSubject(n) != null) continue;
                        if (seen.add(Vocabulary.norm(n))) other.add(n);
                    }
                }
                shown.put(e, other);
            }
            for (Map.Entry<String, Set<String>> d : detached.entrySet()) {
                if (shown.containsKey(d.getKey())) continue;
                List<String> keep = new ArrayList<>();
                for (String n : names.get(d.getKey())) if (!n.equals(label.get(d.getKey())) && !d.getValue().contains(n)) keep.add(n);
                shown.put(d.getKey(), keep);
            }
            out.sort(Comparator.comparing((Link l) -> l.personLabel()).thenComparing(Link::node).thenComparing(Link::claim));
            // what is left for the model: the readings still wanted, and each mention still open among the people it could be now
            List<Ask> asks = new ArrayList<>();
            for (String hanName : wantedReadings) asks.add(new Ask("reading", hanName, readingPrompt(hanName), "", List.of(), "", ""));
            for (Map.Entry<String, String> d : wantedDirected.entrySet()) asks.add(new Ask("directed", d.getKey(), d.getValue(), "", List.of(), "", ""));
            for (Map.Entry<String, Object[]> e : wanted.entrySet()) {
                if (mentionLinks.containsKey(e.getKey())) continue;
                String[] side = (String[]) e.getValue()[0];
                @SuppressWarnings("unchecked") Set<String> cands = (Set<String>) e.getValue()[2];
                List<String> ids = new ArrayList<>();
                for (String x : cands) { String id = bestEntry(find(x)); if (!ids.contains(id)) ids.add(id); }
                if (ids.size() < 1) continue;
                String[] p = passage(side[0]);
                if (!p[0].isBlank()) asks.add(new Ask("choice", e.getKey(), choicePrompt(side, (String) e.getValue()[1], p, ids), p[0], ids, side[0], side[1]));
            }
            List<String[]> worked = new ArrayList<>();
            for (String[] w : siblingsParents()) worked.add(new String[]{entry.apply(w[0]), "child-of", entry.apply(w[1]), w[2], w[3]});
            return new Result(stamp, out, new ArrayList<>(open), written, labels, shown, new LinkedHashMap<>(readings), new LinkedHashMap<>(choices), asks, worked);
        }
    }
}
