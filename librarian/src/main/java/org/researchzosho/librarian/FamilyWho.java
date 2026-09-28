package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.node.ObjectNode;

import com.fasterxml.jackson.databind.JsonNode;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.researchzosho.Config;
import org.researchzosho.drive.DriveClient;
import org.researchzosho.tools.WebSearchTool;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import org.researchzosho.drive.DeclineJudge;
import org.researchzosho.drive.Declined;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The sitting in which somebody in the family says who is who: the library shows, person by person, the people the web has under
 * that name, and the answer is a number, "none", "later", or something told about the person. It can be left at any point; what
 * was answered is kept, and the rest waits on the library's web page and in this command.
 */
public final class FamilyWho {
    private FamilyWho() { }

    /** The library's own web search, as plain pages. */
    public static FamilyIdentity.Search liveSearch() {
        WebSearchTool tool = new WebSearchTool();
        ObjectMapper json = new ObjectMapper();
        return query -> {
            List<FamilyIdentity.Page> out = new ArrayList<>();
            try { out.addAll(pages(tool.execute(json.createObjectNode().put("query", query).put("limit", 20)))); } catch (Exception ignored) { }
            return out;
        };
    }

    /** The search tool's text, read back as pages: a numbered title, the address under it, and under that what the engine says of the page. */
    static List<FamilyIdentity.Page> pages(String text) {
        List<FamilyIdentity.Page> out = new ArrayList<>();
        String title = null, url = null;
        for (String line : (text == null ? "" : text).split("\n")) {
            if (line.matches("\\d+\\. .*")) { if (url != null) out.add(new FamilyIdentity.Page(url, title, "")); title = line.replaceFirst("^\\d+\\. ", "").strip(); url = null; }
            else if (line.startsWith("   http") && title != null && url == null) url = line.strip().replaceFirst("\\s+\\[[^\\]]*\\]$", "");
            else if (line.startsWith("   ") && url != null) { out.add(new FamilyIdentity.Page(url, title, line.strip())); title = null; url = null; }
        }
        if (url != null) out.add(new FamilyIdentity.Page(url, title, ""));
        return out;
    }

    /** The model the library runs on, asked one thing and answering in text. */
    public static Function<String, String> liveModel() {
        DriveClient drive = new DriveClient(Config.get("RESEARCHZOSHO_DRIVE", "http://localhost:8200"), Config.get("RESEARCHZOSHO_MODEL", "local-model"));
        ObjectMapper mapper = new ObjectMapper();
        DeclineJudge declines = DeclineJudge.of(drive);
        return prompt -> {
            ArrayNode msgs = mapper.createArrayNode(); msgs.addObject().put("role", "user").put("content", prompt);
            String out = drive.classify(msgs, 2000);
            declines.raise(drive.model(), "sort the pages a web search found for a name by which person each page is about", out);   // a decline in words is said, never read as an answer
            return out;
        };
    }

    /**
     * Look each person up on the web, the ones looked up before and the ones who have an encyclopedia page of their own left out
     * (that page says who they are). Returns how many were looked up.
     */
    public static int findAll(LibraryStore store, List<String> people, FamilyIdentity.Search search, Function<String, String> model, Consumer<String> progress) throws IOException {
        return findAll(store, people, search, model, progress, new LinkedHashMap<>());
    }

    /**
     * As above; {@code declined} takes each person the model declined a step for, with its decline. That person is left there and the
     * others go on; the decline is written in the library's circulation log, and the caller says it at the end.
     */
    public static int findAll(LibraryStore store, List<String> people, FamilyIdentity.Search search, Function<String, String> model, Consumer<String> progress, Map<String, Declined> declined) throws IOException {
        Graph g = FamilyPeople.view(store);
        int n = 0;
        for (String person : people) {
            if (FamilyIdentity.forPerson(store, g, person) != null || FamilyQuestions.placeholder(person)) continue;
            Graph.Node node = g.node(g.nodeIdOf(person));
            if (node == null || FamilyIdentity.forms(g, node).isEmpty()) continue;   // no written form a search could use: one word, or a character nobody could read
            if (!FamilyQuestions.ownPages(store, g, person).isEmpty()) continue;
            if (progress != null) progress.accept(person);
            try { if (FamilyIdentity.find(store, g, person, search, model) != null) n++; }
            catch (Declined d) { declined.put(person, d); store.circulate("declined", "who is who: " + person + " :: " + d.statement()); }   // this person only: the others go on
        }
        return n;
    }

    /** One question, as it is shown in a terminal. */
    public static String shown(FamilyIdentity.Question q, String knownAbout) { return shown(q, knownAbout, q.person()); }

    /** One question, as it is shown in a terminal, headed with the person's names over a life: "WHO IS 森田健二 (BORN 遠藤) ON THE WEB?". */
    public static String shown(FamilyIdentity.Question q, String knownAbout, String heading) {
        StringBuilder b = new StringBuilder("WHO IS " + (heading == null || heading.isBlank() ? q.person() : heading).toUpperCase() + " ON THE WEB?\n\n");
        if (!knownAbout.isBlank()) b.append("Your library has these facts about this person: ").append(knownAbout).append("\n\n");
        b.append("The library searched the web for ").append(String.join(" and ", q.searched().stream().map(s -> "\"" + s + "\"").toList()))
         .append(". Many people can have the same name. The search found these people:\n");
        for (FamilyIdentity.Candidate c : q.candidates()) {
            b.append("\n  ").append(c.id()).append(".  ").append(c.what());
            switch (c.said()) {
                case "yes" -> b.append("     [you confirmed: this is your relative]");
                case "source" -> b.append("     [this is your relative: your library read its facts from these pages]");
                case "no" -> b.append("     [you said: a different person]");
                default -> { }
            }
            b.append("\n");
            if (!c.relatives().isEmpty()) b.append("      Their pages name ").append(c.relatives().size() == 1 ? "a relative" : "relatives").append(" your library has for this person: ").append(String.join(", ", c.relatives())).append("\n");
            if (!c.others().isEmpty()) b.append("      Their pages match these ").append(c.relatives().isEmpty() ? "" : "other ").append("facts from your library: ").append(String.join(", ", c.others())).append("\n");
            for (FamilyIdentity.Page p : c.pages().stream().limit(FamilyIdentity.PAGES_SHOWN).toList()) b.append("      ").append(p.url()).append("\n");
            if (c.pages().size() > FamilyIdentity.PAGES_SHOWN) b.append("      and ").append(c.pages().size() - FamilyIdentity.PAGES_SHOWN).append(" more pages\n");
        }
        return b.toString();
    }

    static final String HOW = """
            Is one of these people your relative? If you are not sure, open a page in your browser.
              Type the number and press Enter.   If two entries are the same person, type both numbers: 1,3
              Type none    if none of them is your relative.
              Type later   if you do not know yet. The person is skipped, and not asked about again by itself: to answer later,
                           type researchzosho genealogy who with the person's name, or open the person on the Who is who page.
              Type tell    to write something about this person, for example their job or where they live. This helps the next search.
              Type stop    to stop for now. Your answers so far are saved, and you can continue later.
            """;

    /**
     * Go through the open questions. {@code known}: a line of what the library knows of a person. {@code tell}: files what the
     * person typed about somebody. Returns how many questions were answered.
     */
    public static int sitting(LibraryStore store, List<FamilyIdentity.Question> open, BufferedReader in, PrintStream out, Function<String, String> known, BiConsumer<String, String> tell) throws IOException {
        int answered = 0, at = 0;
        Graph g = FamilyPeople.view(store);
        FamilyNameHistory.Index names = FamilyNameHistory.of(g);
        for (FamilyIdentity.Question q : open) {
            at++;
            out.println("\n──────── Person " + at + " of " + open.size() + " ────────\n");
            String id = g.nodeIdOf(q.person());
            out.print(shown(q, known == null ? "" : known.apply(q.person()), g.node(id) == null ? q.person() : names.heading(id)));
            out.println();
            out.print(HOW);
            while (true) {
                out.print("\nYour answer: "); out.flush();
                String line = in.readLine();
                if (line == null) return answered;
                String a = line.strip().toLowerCase();
                if (a.equals("stop") || a.equals("q") || a.equals("quit")) { out.println("\nStopped. You answered " + answered + (answered == 1 ? " question" : " questions") + ", and your answers are saved. To continue later, type: researchzosho genealogy who"); return answered; }
                if (a.equals("later") || a.isEmpty()) { FamilyIdentity.answer(store, q.person(), List.of(), "unsure"); out.println("Skipped for now. The library will still search for " + q.person() + ", but it will not treat pages that only match the name as being about your relative. To answer later: researchzosho genealogy who \"" + q.person() + "\""); break; }
                if (a.equals("none")) { FamilyIdentity.answer(store, q.person(), List.of(), "none"); answered++; out.println("OK. None of these people is " + q.person() + "."); break; }
                if (a.equals("tell")) {
                    out.print("What do you want to tell the library about " + q.person() + "? Type one sentence and press Enter:\n> "); out.flush();
                    String told = in.readLine();
                    if (told != null && !told.isBlank() && tell != null) { tell.accept(q.person(), told.strip()); out.println("Saved. The library will use this in the next search for " + q.person() + "."); }
                    continue;
                }
                if (a.matches("[\\d,\\s]+")) {
                    List<String> picked = new ArrayList<>(); for (String n : a.split("[,\\s]+")) if (!n.isBlank()) picked.add(n);
                    try {
                        FamilyIdentity.answer(store, q.person(), picked, "confirmed"); answered++;
                        out.println("OK. " + q.person() + " is number " + String.join(" and ", picked) + ". The other entries are treated as different people with the same name.");
                        break;
                    } catch (ProtocolError e) { out.println(e.getMessage()); continue; }
                }
                out.println("Sorry, \"" + line.strip() + "\" is not an answer the library understands. Type a number, none, later, tell or stop.");
            }
        }
        return answered;
    }

    /**
     * library_who: who, of the people the web shows under a name, is the person in the family. op=list says who waits; op=show
     * lays one person's question out (the first who waits, when nobody is named); op=answer records the family's word
     * (is = the numbers of the entries who ARE the person, or none=true, or later=true); op=tell keeps something said of the
     * person, which goes into their search in those words; op=find looks one person up on the web now. For the people who may
     * write: the answers change claims and the research.
     *
     * <p>The questions about names and families ride on the same tool: op=list counts them ({@code names_waiting}); op=show with
     * kind=names lays the first one out (about {@code person} when one is named), as {@code question.kind = "names"} with its code, its
     * words and its options, each saying what it does; op=answer with a {@code code} and a {@code choice} (and a {@code year} for a question
     * of when, a {@code name} for the answer that names somebody else in the library) records the family's word. Without kind=names, op=show
     * is the web question as it always was.
     */
    public static ObjectNode tool(LibraryProtocol protocol, JsonNode args) throws IOException {
        LibraryStore store = protocol.store();
        Patrons.Patron patron = Patrons.Patron.from(args);
        Patrons.check(store, patron, Patrons.Level.write);
        String op = args.path("op").asText("list").strip();
        String person = args.path("person").asText("").strip();
        boolean names = args.path("kind").asText("").strip().equalsIgnoreCase("names");
        if (op.equals("answer") && !args.path("code").asText("").isBlank()) return namesAnswer(protocol, store, patron, args);
        if (!person.isEmpty()) {
            boolean[] exact = {false};
            List<String> meant = FamilyQuestions.meant(store, person, exact);
            if (!exact[0] || meant.size() != 1) throw ProtocolError.invalidArgs("The library does not have one person named \"" + person + "\" in the family." + (meant.isEmpty() ? "" : " It could be: " + String.join(", ", meant.stream().limit(8).toList()) + "."));
            person = meant.get(0);
        }
        ObjectNode r = protocol.envelope();
        r.put("op", op);
        switch (op) {
            case "list" -> { }
            case "show" -> {
                if (names) {
                    List<FamilyNameQuestions.Question> qs = person.isEmpty() ? FamilyNameQuestions.open(store) : FamilyNameQuestions.about(store, Set.of(FamilyPeople.view(store).nodeIdOf(person)));
                    if (qs.isEmpty()) { r.put("summary", person.isEmpty() ? "No question about names or families waits." : "No question about names or families waits about " + person + "."); whoList(store, r); return r; }
                    FamilyNameQuestions.Question q = FamilyNameQuestions.ordered(qs).get(0);
                    r.set("question", FamilyNameQuestions.forTool(q));
                    r.put("summary", "A question about names and families that only the family can answer. Put the text to the person as it is, with every option: what it says and what it does. Record their choice with op=answer, this code and the option's key" + (q.options().stream().anyMatch(o -> o.key().equals("year")) ? " (and year, for the year they give)" : "")
                            + (q.options().stream().anyMatch(o -> o.key().equals(FamilyNameQuestions.SOMEONE)) ? " (and name, for the person they name, as the library writes it)" : "") + ".");
                    return r;
                }
                FamilyIdentity.Question q = person.isEmpty() ? FamilyIdentity.open(store).stream().findFirst().orElse(null) : FamilyIdentity.read(store, person);
                if (q == null) { r.put("summary", person.isEmpty() ? "Nobody waits for an answer." : "The library has not looked " + person + " up on the web yet; op=find does that."); whoList(store, r); return r; }
                whoQuestion(r.putObject("question"), q, true);
                r.put("summary", "The web shows " + q.candidates().size() + (q.candidates().size() == 1 ? " person" : " people") + " under the name of " + q.person() + ". Only somebody in the family can say which is theirs: show them the entries with their numbers and pages, and ask.");
                return r;
            }
            case "answer" -> {
                if (person.isEmpty()) throw ProtocolError.invalidArgs("Say which person the answer is for.");
                List<String> picked = new ArrayList<>();
                JsonNode is = args.path("is");
                if (is.isArray()) is.forEach(n -> picked.add(n.asText().strip())); else for (String n : is.asText("").split("[,\\s]+")) if (!n.isBlank()) picked.add(n.strip());
                String state = args.path("none").asBoolean(false) ? "none" : args.path("later").asBoolean(false) ? "unsure" : "confirmed";
                FamilyIdentity.Question q = FamilyIdentity.answer(store, person, picked, state);
                whoQuestion(r.putObject("question"), q, false);
                r.put("summary", switch (state) {
                    case "confirmed" -> q.person() + " is: " + String.join("; ", q.yes().stream().map(FamilyIdentity.Candidate::what).toList()) + ". The other entries are taken as different people of the same name. The search for this person starts from the confirmed pages.";
                    case "none" -> "Noted: none of the people shown is " + q.person() + ".";
                    default -> "Left for later. The search for " + q.person() + " goes on without it, and nothing found under the name alone is taken as theirs."; });
            }
            case "tell" -> {
                String text = args.path("text").asText("").strip();
                if (person.isEmpty() || text.isEmpty()) throw ProtocolError.invalidArgs("Give the person and what was said of them.");
                if (FamilyIdentity.read(store, person) == null) throw ProtocolError.invalidArgs("The library has not looked " + person + " up on the web yet; op=find does that first.");
                FamilyIdentity.told(store, person, text);
                r.put("summary", "Kept, in the person's own words. It goes into the next search for " + person + ", and the next family search files it as their account.");
            }
            case "find" -> {
                if (person.isEmpty()) throw ProtocolError.invalidArgs("Say which person to look up. The whole family is looked up by the command researchzosho genealogy research.");
                FamilyIdentity.Question q = FamilyIdentity.find(store, FamilyPeople.view(store), person, search(protocol), model(protocol));
                whoQuestion(r.putObject("question"), q, true);
                r.put("summary", q.candidates().isEmpty() ? "The web showed nobody under the name of " + person + "." : "The web shows " + q.candidates().size() + " people under the name of " + person + ". Show them and ask which is theirs.");
                return r;
            }
            default -> throw ProtocolError.invalidArgs("op is one of: list, show, answer, tell, find.");
        }
        whoList(store, r);
        return r;
    }

    /** The web search behind op=find: the one a test put into the protocol, else the live one. */
    private static FamilyIdentity.Search search(LibraryProtocol p) { return p.whoSearch == null ? liveSearch() : (FamilyIdentity.Search) p.whoSearch.get(); }

    @SuppressWarnings("unchecked")
    private static Function<String, String> model(LibraryProtocol p) { return p.whoModel == null ? liveModel() : (Function<String, String>) p.whoModel.get(); }

    /** op=answer for a question about names and families: the family's word, filed; the question it answered and the next that waits. */
    private static ObjectNode namesAnswer(LibraryProtocol protocol, LibraryStore store, Patrons.Patron patron, JsonNode args) throws IOException {
        String code = args.path("code").asText("").strip(), choice = args.path("choice").asText("").strip();
        if (choice.isEmpty()) throw ProtocolError.invalidArgs("Give the choice: the key of the option the person chose, or later.");
        String said;
        try { said = FamilyNameQuestions.answer(store, code, choice, args.path("year").asText(""), patron.name().isBlank() ? "the person" : patron.name(), args.path("name").asText("")); }
        catch (IllegalArgumentException e) { throw ProtocolError.invalidArgs(e.getMessage()); }
        ObjectNode r = protocol.envelope();
        r.put("op", "answer");
        r.put("code", code);
        r.put("summary", said);
        whoList(store, r);
        return r;
    }

    private static void whoList(LibraryStore store, ObjectNode r) throws IOException {
        List<FamilyIdentity.Question> all = FamilyIdentity.all(store);
        ArrayNode out = r.putArray("people");
        for (FamilyIdentity.Question q : all) out.addObject().put("person", q.person()).put("state", q.state()).put("says", WhoPage.state(q));
        long waits = all.stream().filter(FamilyIdentity.Question::open).count();
        r.put("waiting", waits);
        int names = FamilyNameQuestions.open(store).size();
        r.put("names_waiting", names);
        if (!r.has("summary")) r.put("summary", all.isEmpty() ? "The library has not looked anybody in the family up on the web yet. The family search does that first (researchzosho genealogy research); op=find does it for one person."
                : waits == 0 ? "Nobody waits for an answer. " + all.size() + " looked up in all." : waits + (waits == 1 ? " person waits" : " people wait") + " for the family to say who they are on the web. op=show lays the first one out.");
        if (names > 0)
            r.put("summary", r.path("summary").asText() + " " + (names == 1 ? "One question" : names + " questions") + " about names and families " + (names == 1 ? "waits" : "wait") + " too: op=show with kind=names lays the first one out.");
    }

    private static void whoQuestion(ObjectNode o, FamilyIdentity.Question q, boolean pages) {
        o.put("person", q.person()).put("state", q.state());
        q.searched().forEach(o.putArray("searched")::add);
        if (!q.told().isEmpty()) q.told().forEach(o.putArray("told")::add);
        ArrayNode cs = o.putArray("entries");
        for (FamilyIdentity.Candidate c : q.candidates()) {
            ObjectNode n = cs.addObject().put("number", c.id()).put("who", c.what()).put("said", c.said());
            if (!c.matches().isEmpty()) c.matches().forEach(n.putArray("also_says_as_the_family_does")::add);
            if (pages) { ArrayNode ps = n.putArray("pages"); c.pages().stream().limit(FamilyIdentity.PAGES_SHOWN).forEach(pg -> ps.add(pg.url())); }
        }
    }

}
