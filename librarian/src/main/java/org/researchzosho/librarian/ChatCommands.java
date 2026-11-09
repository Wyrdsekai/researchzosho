package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The chat's own commands, one path for both fronts, the terminal and the Chat page: a line that begins with "/" is read here before the
 * model sees anything, and a plain line is offered to the field that holds the conversation (an interview in progress). The reply is the
 * library's, never the model's. What differs between the fronts is behind {@link Front}: which conversation is open, how a fresh or an
 * earlier one is made the current one, and whether the front sits at the library's own computer, where {@code /share} may install a
 * program after asking. Until 2026-11-07 the terminal took {@code /new}, {@code /runs}, {@code /sessions}, {@code /resume} and {@code /share}
 * and the page did not; the page took {@code /sources} and every field's command ({@code /patents}, {@code /video}) and the terminal
 * refused them as "not a command".
 */
final class ChatCommands {
    private ChatCommands() { }

    private static final ObjectMapper M = new ObjectMapper();

    /** The library's own words, in the person's language ({@link Chrome}). */
    private static String t(String en, Object... args) { return Chrome.now().t(en, args); }

    /** What a front does that the shared path cannot. */
    interface Front {
        /** The conversation the line was said in. */
        Librarian.Session session();
        /** A fresh conversation opened and made the current one; what waited in the old one is left as the person left it. Its id. */
        String open() throws IOException;
        /** An earlier conversation made the current one; null when none of this person's has the id. */
        Librarian.Session resume(String id) throws IOException;
        /** The chat's model changed: {@code entry} the named one, or null for best judgement. The terminal swaps its drive; the page keeps the name with the conversation. */
        default void modelChanged(ModelEntries.Entry entry) { }
        /** Whether the front sits at the library's own computer, where {@code /share} may fetch and install after asking on the screen. */
        default boolean atTheTerminal() { return false; }
        /** A line or two the front adds to {@code /help}: the terminal's keys. */
        default String helpExtra() { return ""; }
    }

    /**
     * The answer to a line of the chat that the library answers itself, or null when the line is the model's. {@code chat} names the
     * conversation for the fields ("terminal", or "web:" and the page's session id); {@code waiting}: the library waits for the person's
     * answer to its own question, so a plain line is that answer, never a field's.
     */
    static String reply(LibraryStore store, Patrons.Patron patron, String chat, String line, boolean waiting, Front front) throws IOException {
        String words = line == null ? "" : line.strip();
        if (words.isEmpty()) return null;
        String first = words.split("\\s+", 2)[0], rest = words.length() > first.length() ? words.substring(first.length()).strip() : "";
        switch (first) {
            case "/help" -> { return rest.isEmpty() ? help(store, front) : Help.lookup(store, rest); }
            case "/new" -> { String id = front.open(); return t("A fresh conversation, {0}.", id); }
            case "/sessions" -> { return sessions(store, patron, front.session()); }
            case "/resume" -> {
                if (rest.isEmpty()) return t("Say which conversation: /resume and its id. /sessions lists them.");
                Librarian.Session s = front.resume(rest);
                return s == null ? t("No conversation of yours has the id {0}. /sessions lists them.", rest) : t("Continuing {0}: {1}", s.id, s.title());
            }
            case "/runs" -> { return runs(store, patron, front.session()); }
            case "/model" -> { return model(store, front.session(), rest, front); }
            case "/share" -> { return share(store, patron, rest, front); }
            case "/interview" -> { return Interview.questions(store, rest); }
            case "/suggest" -> { return Suggestions.text(Suggestions.refresh(store, null), Suggestions.all(store)); }
            case "/sources" -> { return SourcesPage.chat(store, patron, words, "/sources"); }
            default -> { }
        }
        if (words.startsWith("/")) { String r = more(store, patron, first, rest); if (r != null) return r; }
        // a field's own command (/family, /career, /home, /patents, /video), or a plain line for the field that holds this conversation
        boolean command = words.startsWith("/");
        if (command || !waiting) {
            for (Profile p : Fields.enabled(store)) {
                String r = p.chatReply(store, patron, chat, words);
                if (r != null) return ChatCapture.unindent(r);
            }
        }
        if (command) return t("That is not one of the chat's commands. /help lists them.");
        return null;
    }

    /** {@code /help}: what the library answers itself, and each field's own commands; the front's own lines last. */
    static String help(LibraryStore store, Front front) {
        StringBuilder b = new StringBuilder(t("Ask anything about what the library holds; it answers from its shelves, with the sources. These commands the library answers itself:")).append("\n");
        b.append("- ").append(t("/help and some words, to look them up in the program's own documentation; the Help page does the same")).append("\n");
        b.append("- ").append(t("/new, a fresh conversation; /sessions, the earlier ones; /resume and an id, to continue one")).append("\n");
        b.append("- ").append(t("/runs, the research runs this conversation follows")).append("\n");
        b.append("- ").append(t("/model, to see the models this chat can talk to; /model and a name switches to one")).append("\n");
        b.append("- ").append(t("/interview and a name, to have the library ask you what it does not know about someone")).append("\n");
        b.append("- ").append(t("/suggest, for what the library suggests you do next")).append("\n");
        b.append("- ").append(t("\"translate this file into Japanese\" with a file attached, or naming a document on the shelves; \"checked\" in the words for the checked tier; \"using deepseek\" or \"translated by kimi\" names the translator and \"checked by kimi\" or \"have kimi k3 do a thorough check\" the reader, by an entry's name or its model; \"checked by the strongest other model\" lets the library choose; without a word the chat's /model translates; \"render Acme Corp as アクメ社 in Japanese\" for the glossary")).append("\n");
        b.append("- ").append(t("/sources, the places the library mines; /sources add, remove, restore or show one")).append("\n");
        b.append("- ").append(t("/share, the library reached from outside your home: status, off, --tailscale, --tailnet, --cloudflare")).append("\n");
        b.append("- ").append(t("/explain <id or a term> [--beginner|--familiar|--written]; /perspectives <question>; /established <claim>; /read <id or address> [--in <language>]")).append("\n");
        b.append("- ").append(t("/subjects [proposed | accept <n…> | drop <n…>]; /serials [add|remove|park|unpark|every]; /export <id or subject> [--pdf|--docx|--md]; /remove <id> [--yes]")).append("\n");
        b.append("- ").append(t("/story <subject>; /forecasts [<subject>]; /forecast <P-…> happened|did-not|void; /dna; /prefs; /react like|dislike|seen|own <title>")).append("\n");
        b.append("- ").append(t("for the keeper: /mcp, /readers, /invite <name>, /keys, /crews tonight|now, /tonight, /spend, /update")).append("\n");
        for (Profile p : Fields.enabled(store)) if (!p.chatCommandHelp().isBlank()) b.append("- ").append(p.chatCommandHelp().strip()).append('\n');
        b.append("- ").append(t("/quit leaves the terminal chat; the page is left by closing it")).append("\n");
        b.append(t("Say \"find out …\" to start a research run; \"yes\" accepts what the Librarian offers. When it asks a question ending in (y/N), y or yes says yes; Enter or anything else says no."));
        String extra = front == null ? "" : front.helpExtra();
        if (!extra.isBlank()) b.append('\n').append(extra.strip());
        return b.toString().strip();
    }

    /** {@code /sessions}: this person's conversations, newest first, the open one marked. */
    static String sessions(LibraryStore store, Patrons.Patron patron, Librarian.Session current) throws IOException {
        List<String> ids = Librarian.Session.list(store, patron);
        if (ids.isEmpty()) return t("No conversations yet.");
        StringBuilder b = new StringBuilder(t("Your conversations, newest first; /resume and an id continues one:"));
        int n = 0;
        for (String id : ids) {
            if (n++ >= 40) { b.append('\n').append(t("and {0} earlier", ids.size() - 40)); break; }
            Librarian.Session s = Librarian.Session.resume(store, id, patron);
            b.append('\n').append(id).append("  ").append(s == null ? "" : s.title()).append(current != null && id.equals(current.id) ? "  " + t("(this one)") : "");
        }
        return b.toString();
    }

    /** {@code /runs}: the research runs this conversation follows, each with its stage line. */
    static String runs(LibraryStore store, Patrons.Patron patron, Librarian.Session s) {
        List<String> watched = s == null ? List.of() : s.watched();
        if (watched.isEmpty()) return t("No research run is being followed in this conversation.");
        LibraryProtocol lp = new LibraryProtocol(store);
        ObjectNode patronNode = M.createObjectNode().put("did", patron.did()).put("name", patron.name()).put("runtime", patron.runtime());
        StringBuilder b = new StringBuilder();
        for (String jobId : watched) {
            ObjectNode a = M.createObjectNode().put("job_id", jobId); a.set("patron", patronNode.deepCopy());
            try { b.append(b.isEmpty() ? "" : "\n").append(RunProgress.line(RunProgress.of(lp.job(a).path("job"), System.currentTimeMillis(), RunProgress.typicalSeconds(lp, patronNode)))); }
            catch (Exception e) { b.append(b.isEmpty() ? "" : "\n").append(jobId).append(": ").append(e.getMessage()); }
        }
        return b.toString();
    }

    /** {@code /model}: the models this chat can talk to; {@code /model <name>} switches this conversation to one, {@code auto} to best judgement. */
    static String model(LibraryStore store, Librarian.Session s, String name, Front front) throws IOException {
        if (name.isEmpty()) {
            StringBuilder b = new StringBuilder(s == null || s.model().isEmpty() ? t("The models this library can talk to:") : t("The models this library can talk to (this chat uses {0}):", s.model()));
            for (ModelEntries.Entry e : ModelEntries.all().values()) b.append("\n").append(e.line());
            return b.append("\n").append(t("/model <name> switches this chat to one.")).toString();
        }
        if (name.equalsIgnoreCase(Auto.NAME)) {
            Auto.Pick pick = Auto.choose(store, Auto.Job.chat());
            if (s != null) s.model(Auto.NAME);
            front.modelChanged(null);
            return pick.why() + " — " + t("this chat uses best judgement from now on.");
        }
        ModelEntries.Entry e = ModelEntries.get(name);
        if (e == null) e = ModelEntries.resolve(name);   // "Kimi K3" is kimi
        if (e == null) return ModelEntries.noMatch(name);
        if (s != null) s.model(e.name());
        front.modelChanged(e);
        return t("This chat now uses {0}.", e.line());
    }

    /**
     * {@code /share}: the library reached from outside the house. At the terminal the command runs as {@code researchzosho share} does, asking
     * on the screen before it installs anything; from a page, status and off run, and turning the relay on is said as the command to run
     * on the library's computer, because installing a program there is the person's own step.
     */
    static String share(LibraryStore store, Patrons.Patron patron, String rest, Front front) throws IOException {
        List<String> args = rest.isEmpty() ? List.of() : Arrays.asList(rest.split("\\s+"));
        if (!front.atTheTerminal() && !Patrons.mayWrite(store, patron)) return t("Only the person who keeps this library shares it.");
        if (front.atTheTerminal()) return Share.share(store, args, System.out).text();
        if (args.isEmpty() || !(args.contains("status") || args.contains("off"))) {
            ByteArrayOutputStream quiet = new ByteArrayOutputStream();
            String status = Share.share(store, List.of("status"), new PrintStream(quiet, true, StandardCharsets.UTF_8)).text();
            return status + "\n" + t("Turning the relay on may install a program, which is a step for the library's own computer: there, say /share in the terminal chat, or run {0}.", "researchzosho share" + (rest.isEmpty() ? "" : " " + rest));
        }
        ByteArrayOutputStream steps = new ByteArrayOutputStream();
        return Share.share(store, args, new PrintStream(steps, true, StandardCharsets.UTF_8)).text();
    }

    /** The words of a command line after its first word, split on spaces; "" when there are none. */
    static List<String> wordsOf(String rest) {
        List<String> out = new ArrayList<>();
        for (String w : rest.split("\\s+")) if (!w.isBlank()) out.add(w);
        return out;
    }
    // ---- the keeper's actions and the reading actions, as slash commands: each a thin wrapper over the protocol, in sentences ----

    /** Whether the patron is the person who keeps the library: at the terminal, or on the pages while they are open to everyone. The keys, the readers and the MCP servers are theirs alone. */
    static boolean keeper(LibraryStore store, Patrons.Patron patron) { return patron.person() || (patron.web() && !WebAccess.signInRequired()); }

    static ObjectNode args(Patrons.Patron patron) { ObjectNode a = M.createObjectNode(); a.putObject("patron").put("did", patron.did()).put("name", patron.name()).put("runtime", patron.runtime()); return a; }

    /** A flag's value taken out of the words ({@code --in ja}); null when the flag is not there. */
    static String flag(List<String> words, String flag) {
        int at = words.indexOf(flag);
        if (at < 0 || at + 1 >= words.size()) { if (at >= 0) words.remove(at); return null; }
        String v = words.get(at + 1); words.remove(at + 1); words.remove(at); return v;
    }

    /** The reply to one of these commands, or null when {@code first} is none of them. */
    static String more(LibraryStore store, Patrons.Patron patron, String first, String rest) throws IOException {
        LibraryProtocol p = new LibraryProtocol(store);
        List<String> words = wordsOf(rest);
        try {
            switch (first) {
                case "/explain" -> {
                    if (words.isEmpty()) return t("Type /explain and an entry's id, or a term in quotes with --in and the id of the entry it appears in; --beginner, --familiar or --written picks the reading level.");
                    ObjectNode a = args(patron);
                    String rung = words.remove("--beginner") ? "beginner" : words.remove("--familiar") ? "familiar" : words.remove("--written") ? "written" : "beginner";
                    String in = flag(words, "--in"); boolean fresh = words.remove("--fresh");
                    String what = String.join(" ", words).replaceAll("^\"|\"$", "");
                    if (what.matches("[FIA]-\\d{4}-[a-z0-9-]+")) a.put("id", what); else { a.put("term", what); if (in != null) a.put("in", in); }
                    a.put("rung", rung); if (fresh) a.put("fresh", true);
                    ObjectNode r = p.explain(a);
                    StringBuilder b = new StringBuilder(r.path("text").asText("").strip());
                    if (b.isEmpty()) b.append(t("Nothing to show."));
                    b.append("\n\n").append(t("Based on the library: {0}.", "shelves".equals(r.path("grounding").asText()) ? t("fully") : "thin".equals(r.path("grounding").asText()) ? t("partly") : r.path("grounding").asText("")));
                    if (r.path("terms").size() > 0) { b.append(' ').append(t("Words you may meet next:")); for (JsonNode x : r.path("terms")) b.append("\n- ").append(x.path("term").asText()).append(" — ").append(x.path("gloss").asText()); }
                    return b.toString();
                }
                case "/perspectives" -> {
                    if (rest.length() < 12) return t("Type /perspectives and a question of twelve characters or more: who studies it and what each would ask.");
                    ObjectNode r = p.perspectives(args(patron).put("question", rest));
                    if (r.path("perspectives").isEmpty()) return t("No perspectives were found for that question.");
                    StringBuilder b = new StringBuilder();
                    for (JsonNode x : r.path("perspectives")) { b.append(b.isEmpty() ? "" : "\n").append(x.path("perspective").asText()).append(" — ").append(x.path("why").asText()); for (JsonNode q : x.path("questions")) b.append("\n  - ").append(q.asText()); }
                    return b.toString();
                }
                case "/established" -> {
                    if (rest.isEmpty()) return t("Type /established and a claim: whether the shelves establish it, from the entries' own states.");
                    ObjectNode r = p.established(args(patron).put("claim", rest));
                    String verdict = r.path("verdict").asText();
                    StringBuilder b = new StringBuilder(verdict.equals("established") ? t("Established: an accepted claim on the shelves bears on it.") : verdict.equals("disputed") ? t("Disputed: a claim on the shelves that bears on it is disputed.") : t("Not established: nothing accepted on the shelves bears on it."));
                    for (String k : List.of("accepted", "disputes", "unreviewed")) for (JsonNode e : r.path(k)) b.append("\n- [").append(e.path("id").asText()).append("] ").append(e.path("title").asText("")).append(" (").append(t(k.equals("disputes") ? "disputed" : k.equals("unreviewed") ? "draft" : "accepted")).append(")");
                    return b.toString();
                }
                case "/read" -> {
                    if (words.isEmpty()) return t("Type /read and an entry's id or a saved page's address; --in and a language reads a report in that language.");
                    String in = flag(words, "--in"); String what = String.join(" ", words);
                    if (in != null || what.matches("[FIA]-\\d{4}-[a-z0-9-]+")) {
                        ObjectNode a = args(patron).put("id", what); if (in != null) a.put("in", in);
                        ObjectNode got = p.get(a);
                        JsonNode r = got.path("entry").isObject() ? got.path("entry") : got;   // the entry rides under entry, as the command line's get reads it
                        String body = r.path("body").asText(r.path("text").asText("")); if (body.length() > 6000) body = body.substring(0, 6000) + " …";
                        String head = r.path("title").asText("") + "  [" + r.path("id").asText(what) + "]" + (r.path("read_in").asText("").isEmpty() ? "" : "  " + t("read in {0}", ReadIn.name(r.path("read_in").asText())));
                        return head + "\n\n" + body;
                    }
                    ObjectNode r = p.read(args(patron).put("locator", what).put("max_chars", 6000));
                    return r.path("title").asText("") + "  (" + r.path("locator").asText(what) + (r.path("truncated").asBoolean(false) ? ", " + t("the first {0} characters", 6000) : "") + ")\n\n" + r.path("text").asText("");
                }
                case "/remove" -> {
                    if (!Patrons.mayWrite(store, patron)) return t("Removing an entry is for those who may write to this library.");
                    if (words.isEmpty()) return t("Type /remove and the id of a report or a claim: what would go is shown first; /remove, the id and --yes removes it. --report-only keeps the claims, --claims-only keeps the report.");
                    boolean yes = words.remove("--yes"); String what = words.remove("--report-only") ? "report" : words.remove("--claims-only") ? "claims" : "all";
                    ObjectNode r = p.remove(args(patron).put("id", words.get(0)).put("what", what).put("dry", !yes));
                    return r.path("plan").asText("") + (r.path("plan").asText("").isEmpty() ? "" : "\n") + r.path("summary").asText("") + (yes ? "" : "\n" + t("To go ahead: /remove {0} --yes", words.get(0) + (what.equals("all") ? "" : " --" + what + "-only")));
                }
                case "/serials" -> {
                    ObjectNode a = args(patron);
                    String op = words.isEmpty() ? "list" : words.get(0);
                    switch (op) {
                        case "list" -> {
                            ObjectNode r = p.serials(a.put("op", "list"));
                            if (r.path("searches").isEmpty()) return t("No saved search yet. /serials add <name> <the search words> [days] keeps one; the housekeeping runs it again.");
                            StringBuilder b = new StringBuilder(t("The saved searches the housekeeping runs again:"));
                            for (JsonNode s : r.path("searches")) b.append("\n- ").append(s.path("name").asText()).append(": ").append(s.path("query").asText()).append(" · ").append(t("every {0} days", s.path("every_days").asInt())).append(s.path("parked").asBoolean() ? " · " + t("parked") : "").append(s.path("due").asBoolean() ? " · " + t("due") : "");
                            return b.toString();
                        }
                        case "add" -> {
                            if (words.size() < 3) return t("Type /serials add, a short name, the search words, and the days between runs if not seven.");
                            int every = words.get(words.size() - 1).matches("\\d+") ? Integer.parseInt(words.remove(words.size() - 1)) : 7;
                            ObjectNode r = p.serials(a.put("op", "add").put("name", words.get(1)).put("query", String.join(" ", words.subList(2, words.size()))).put("every_days", every));
                            return t("Kept: {0} searches for \"{1}\" every {2} days; what it finds lands in the inbox.", r.path("name").asText(), r.path("query").asText(), r.path("every_days").asInt());
                        }
                        case "remove", "park", "unpark" -> {
                            if (words.size() < 2) return t("Say which saved search, by its name.");
                            p.serials(a.put("op", op).put("name", words.get(1)));
                            return op.equals("remove") ? t("The saved search {0} is removed.", words.get(1)) : op.equals("park") ? t("The saved search {0} is parked: it does not run until you unpark it.", words.get(1)) : t("The saved search {0} runs again.", words.get(1));
                        }
                        case "every" -> {
                            if (words.size() < 3 || !words.get(2).matches("\\d+")) return t("Type /serials every, the search's name, and the days between runs.");
                            p.serials(a.put("op", "every").put("name", words.get(1)).put("every_days", Integer.parseInt(words.get(2))));
                            return t("The saved search {0} runs every {1} days now.", words.get(1), words.get(2));
                        }
                        default -> { return t("/serials lists the saved searches; /serials add <name> <words> [days], remove <name>, park <name>, unpark <name>, every <name> <days>."); }
                    }
                }
                case "/subjects" -> {
                    String op = words.isEmpty() ? "" : words.get(0);
                    if (op.isEmpty()) {
                        Map<String, Integer> counts = Related.counts(store);
                        if (counts.isEmpty()) return t("No subjects yet. They appear as claims are added.");
                        StringBuilder b = new StringBuilder(t("The subjects, with how many claims each holds:"));
                        int n = 0; for (var e : counts.entrySet()) { if (n++ >= 60) { b.append("\n").append(t("and {0} more on the Subjects page", counts.size() - 60)); break; } b.append("\n- ").append(e.getKey()).append(" (").append(e.getValue()).append(")"); }
                        List<ProposedSubjects.Proposal> proposed = ProposedSubjects.list(store);
                        if (!proposed.isEmpty()) b.append("\n").append(t("{0} proposed: /subjects proposed lists them.", proposed.size()));
                        return b.toString();
                    }
                    if (op.equals("proposed") || op.equals("list")) {
                        List<ProposedSubjects.Proposal> proposed = ProposedSubjects.list(store);
                        return proposed.isEmpty() ? t("No subjects are proposed.") : t("The proposed subjects; /subjects accept or drop with numbers, slugs or all:") + "\n" + ProposedSubjects.text(proposed);
                    }
                    if (op.equals("accept") || op.equals("drop")) {
                        if (!Patrons.mayWrite(store, patron)) return t("Deciding the proposed subjects is for those who may write to this library.");
                        return ProposedSubjects.decide(store, op, words.subList(1, words.size())).text();
                    }
                    return t("/subjects lists the subjects; /subjects proposed, the proposals; /subjects accept or drop with numbers, slugs or all.");
                }
                case "/export" -> {
                    if (words.isEmpty()) return t("Type /export and a report's id, or a dossier's subject in quotes; --pdf (the default), --docx or --md says the file; --in and a language, when the library keeps that translation.");
                    String format = words.remove("--docx") || words.remove("--word") ? "docx" : words.remove("--md") ? "md" : "pdf"; words.remove("--pdf");
                    String in = flag(words, "--in"); String what = String.join(" ", words).replaceAll("^\"|\"$", "");
                    if (!what.matches("I-\\d{4}-[a-z0-9-]+") && what.matches("[FA]-\\d{4}-[a-z0-9-]+")) {   // a claim or a summary: Markdown only, as researchzosho export writes it; a report or a dossier goes through library_export
                        if (!format.equals("md")) return t("A claim or a summary is saved as a Markdown file (--md); a report or a dossier as a PDF or a Word file.");
                        Export.File f = Export.markdown(store, what, null, false);
                        Path saved = Export.save(store, f);
                        return t("The entry is saved as a Markdown file at {0}. The web pages serve it at /exports/{1}.", saved, f.name());
                    }
                    ObjectNode a = args(patron).put("format", format);   // pdf, docx or md, as library_export takes them
                    if (what.matches("[FIA]-\\d{4}-[a-z0-9-]+")) a.put("id", what); else a.put("subject", what);
                    if (in != null) a.put("in", in);
                    return p.export(a).path("summary").asText();
                }
                case "/mcp" -> {
                    if (!keeper(store, patron)) return t("The other MCP servers are set up by the person who keeps this library, at the terminal of its computer or on its Keep page.");
                    List<String> argv = new ArrayList<>(List.of("researchzosho", "mcp")); argv.addAll(words);
                    ByteArrayOutputStream out = new ByteArrayOutputStream(), err = new ByteArrayOutputStream();
                    McpSetup.cli(argv.toArray(new String[0]), a -> { a.set("patron", args(patron).get("patron")); return p.mcp(a); }, new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
                    String said = (out.toString(StandardCharsets.UTF_8) + err.toString(StandardCharsets.UTF_8)).strip();
                    return said.isEmpty() ? t("Done.") : said;
                }
                case "/dna" -> {
                    ObjectNode a = args(patron).put("op", words.isEmpty() ? "status" : words.get(0));
                    if (words.size() > 1 && (words.get(0).equals("records"))) { String m = flag(words, "--minutes"); if (m != null && m.matches("\\d+")) a.put("minutes", Integer.parseInt(m)); }
                    ObjectNode r = p.dna(a);
                    return r.path("summary").asText(r.path("text").asText(""));
                }
                case "/prefs" -> {
                    String op = words.isEmpty() ? "show" : words.get(0);
                    ObjectNode a = args(patron).put("op", op);
                    if (op.equals("set")) { if (words.size() < 3) return t("Type /prefs set, the name and the value (names: weight.subject, roam, popularity, words, exclude.author, prefer.mood, avoid.kind …)."); a.put("name", words.get(1)).put("value", String.join(" ", words.subList(2, words.size()))); }
                    else if (op.equals("unset")) { if (words.size() < 2) return t("Type /prefs unset and the name."); a.put("name", words.get(1)); if (words.size() > 2) a.put("value", String.join(" ", words.subList(2, words.size()))); }
                    else if (!List.of("show", "draft", "learn").contains(op)) return t("/prefs shows your preferences file; /prefs draft, set <name> <value>, unset <name>, learn.");
                    ObjectNode r = p.prefs(a);
                    return r.path("summary").asText() + (List.of("show", "draft", "learn").contains(op) && !r.path("text").asText("").isBlank() ? "\n" + r.path("text").asText().stripTrailing() : "");
                }
                case "/react" -> {
                    if (words.size() < 2) return t("Type /react like, dislike, seen or own, and the book's title; --by and the author when it helps.");
                    String by = flag(words, "--by"), about = flag(words, "--about");
                    ObjectNode a = args(patron).put("reaction", words.get(0)).put("title", String.join(" ", words.subList(1, words.size())));
                    if (by != null) a.put("author", by); if (about != null) a.put("about", about);
                    return p.react(a).path("summary").asText();
                }
                case "/story" -> {
                    if (rest.isEmpty()) return t("Type /story and the person or the organisation: the story is written from the claims the library holds, a decade a chapter.");
                    if (!Patrons.mayWrite(store, patron)) return t("Writing a story is for those who may write to this library.");
                    ObjectNode r = p.dossier(args(patron).put("op", "story").put("subject", rest.replaceAll("^\"|\"$", "")));
                    return t("The story of {0} is written as [{1}]: {2} chapters from {3} claims. The dossier page links it.", r.path("subject").asText(rest), r.path("id").asText(), r.path("chapters").asInt(), r.path("claims").asInt());
                }
                case "/forecasts" -> { return p.dossier(args(patron).put("op", "forecasts").put("subject", rest.replaceAll("^\"|\"$", ""))).path("summary").asText(); }
                case "/forecast" -> {
                    if (words.size() < 2) return t("Type /forecast, the forecast's id (P-…) and happened, did-not or void, then a note if you like.");
                    if (!Patrons.mayWrite(store, patron)) return t("Scoring a forecast is for those who may write to this library.");
                    ObjectNode r = p.dossier(args(patron).put("op", "score").put("id", words.get(0)).put("outcome", words.get(1)).put("note", String.join(" ", words.subList(2, words.size()))));
                    return r.path("summary").asText() + (r.has("brier") ? " " + t("Brier score over the scored forecasts on {0}: {1} (0 is perfect, 0.25 is coin-tossing).", r.path("subject").asText(), String.format(Locale.ROOT, "%.3f", r.path("brier").asDouble())) : "");
                }
                case "/tonight" -> { return Tonight.text(Tonight.plan(store)).strip(); }
                case "/crews" -> {
                    if (words.isEmpty() || words.get(0).equals("tonight")) return Tonight.text(Tonight.plan(store)).strip();
                    if (!words.get(0).equals("now")) return t("/crews tonight says what the nightly housekeeping will do at its next run; /crews now runs it now.");
                    if (!Patrons.mayWrite(store, patron)) return t("Running the housekeeping is for those who may write to this library.");
                    String id = new Jobs(store, j -> { throw new IllegalStateException("filed only"); }).submit("crews", patron.did(), M.createObjectNode());
                    return t("The housekeeping runs now, as {0}: source re-checks, nightly research, review, inventory, summaries. The Runs page follows it.", id);
                }
                case "/readers", "/invite" -> {
                    if (!keeper(store, patron)) return t("The readers are the business of the person who keeps this library.");
                    if (first.equals("/invite")) { words.add(0, "invite"); }
                    return readers(store, words);
                }
                case "/keys" -> {
                    if (!keeper(store, patron)) return t("The keys are the business of the person who keeps this library.");
                    String op = words.isEmpty() ? "list" : words.get(0);
                    switch (op) {
                        case "list" -> {
                            List<ApiKeys.Key> keys = ApiKeys.list(store, Accounts.OWNER);
                            if (keys.isEmpty()) return t("You have no keys. /keys new and what the key is for makes one; --write lets it write.");
                            StringBuilder b = new StringBuilder(t("Your keys; a program sends one as Authorization: Bearer <key>:"));
                            for (ApiKeys.Key k : keys) b.append("\n- ").append(k.id()).append("  ").append(k.prefix()).append("…  ").append(k.reads() ? t("reads") : t("reads and writes")).append("  ").append(t("made {0}", k.made())).append("  ").append(k.used().isEmpty() ? t("never used") : t("last used {0}", k.used()));
                            return b.toString();
                        }
                        case "new" -> {
                            Patrons.Level level = words.remove("--write") ? Patrons.Level.write : Patrons.Level.read; words.remove("--read");
                            ApiKeys.Minted m = ApiKeys.mint(store, Accounts.OWNER, String.join(" ", words.subList(1, words.size())).replaceAll("^\"|\"$", ""), level);
                            return t("Your new key for {0}, which {1}. It is shown this once; the library keeps only its hash:", m.key().label(), m.key().reads() ? t("reads") : t("reads and writes")) + "\n" + m.secret();
                        }
                        case "revoke" -> {
                            if (words.size() < 2) return t("Say which key: its id or the start of it. /keys lists them.");
                            return ApiKeys.revoke(store, Accounts.OWNER, words.get(1)) ? t("The key is revoked; a program that used it is refused from now on.") : t("There is no key of yours named {0}. /keys lists them.", words.get(1));
                        }
                        default -> { return t("/keys lists your keys; /keys new <what it is for> [--write]; /keys revoke <id>."); }
                    }
                }
                case "/spend" -> {
                    ObjectNode sum = Spend.summary(store);
                    StringBuilder b = new StringBuilder(t("What research runs cost, at each model's list prices (the library's arithmetic on the servers' usage counts, not a bill). This month is {0}.", sum.path("month").asText()));
                    for (String part : new String[]{"hosts", "entries"}) {
                        if (sum.path(part).isEmpty()) continue;
                        b.append("\n").append(part.equals("hosts") ? t("By host:") : t("By named model:"));
                        for (JsonNode r : sum.path(part)) b.append("\n- ").append(r.path("name").asText()).append(": ").append(Spend.dollars(r.path("month").asDouble())).append(" ").append(t("this month ({0} runs), {1} in all", r.path("runs_month").asInt(), Spend.dollars(r.path("all").asDouble())));
                    }
                    if (sum.path("hosts").isEmpty()) b.append("\n").append(t("No run has been recorded yet. A run on a named model with prices is counted when it files its report."));
                    return b.toString();
                }
                case "/update" -> {
                    String status = Updater.status().strip();
                    return status + "\n" + t("The update itself is a step at the terminal: researchzosho update now. The service can do it after the nightly housekeeping: researchzosho update auto on.");
                }
                default -> { return null; }
            }
        } catch (ProtocolError e) { return e.getMessage(); }
        catch (IllegalArgumentException e) { return e.getMessage(); }
    }

    /** {@code /readers}: list; invite <name> [read|write]; allow <did> <read|write|deny> [name]; remove <did>; default <level>; token <did>. The keeper's. */
    static String readers(LibraryStore store, List<String> words) throws IOException {
        String op = words.isEmpty() ? "list" : words.get(0);
        switch (op) {
            case "list" -> {
                Patrons.Policy pol = Patrons.load(store);
                StringBuilder b = new StringBuilder(t("Anyone not listed may {0}.", t(String.valueOf(pol.dflt()))));
                for (Patrons.Entry e : pol.listed()) {
                    Accounts.Account acc = Accounts.byDid(store, e.did());
                    String ways = acc == null ? (e.tokenHash() == null ? t("nothing yet") : t("a token (a program)")) : acc.ways();
                    b.append("\n- ").append(e.name().isEmpty() ? e.did() : e.name() + " (" + e.did() + ")").append(" — ").append(t("may {0}", t(String.valueOf(e.level())))).append(" — ").append(t("signs in with {0}", ways));
                }
                if (pol.listed().isEmpty()) b.append("\n").append(t("Nobody is listed yet. /invite <name> [read|write] invites a person; /readers allow <did> <read|write> lists a program's id."));
                return b.toString();
            }
            case "invite" -> {
                Patrons.Level lvl = Patrons.Level.read; List<String> name = new ArrayList<>();
                for (int i = 1; i < words.size(); i++) { Patrons.Level l = Patrons.levelOf(words.get(i)); if (l != null && l != Patrons.Level.deny && i > 1) lvl = l; else name.add(words.get(i)); }
                String n = String.join(" ", name).replaceAll("^\"|\"$", "").strip();
                if (n.isEmpty()) return t("Type /invite and the person's name, then read or write.");
                Invites.Invite inv = Invites.make(store, n, lvl);
                return t("{0} may {1}. Send them this link; it works once, for {2} days:", n, t(String.valueOf(lvl)), Invites.DAYS) + "\n" + inv.link() + "\n" + t("When they open it they choose a password. Their id is {0}.", inv.did());
            }
            case "allow", "add" -> {
                if (words.size() < 3) return t("Type /readers allow, the id, and deny, read or write, then a name if you like.");
                Patrons.Level lvl = null; List<String> name = new ArrayList<>();
                for (int i = 2; i < words.size(); i++) { Patrons.Level l = Patrons.levelOf(words.get(i)); if (l != null && lvl == null) lvl = l; else name.add(words.get(i)); }
                if (lvl == null) return t("Say what {0} may do: deny, read or write.", words.get(1));
                Patrons.set(store, words.get(1), String.join(" ", name).replaceAll("^\"|\"$", ""), lvl);
                return lvl == Patrons.Level.deny ? t("{0} is denied.", words.get(1)) : t("{0} may {1}. /readers token {0} makes a token for a program of theirs.", words.get(1), t(String.valueOf(lvl)));
            }
            case "remove" -> {
                if (words.size() < 2) return t("Say which reader, by id.");
                Patrons.remove(store, words.get(1)); Accounts.remove(store, words.get(1));
                return t("{0} removed.", words.get(1));
            }
            case "default" -> {
                Patrons.Level lvl = words.size() > 1 ? Patrons.levelOf(words.get(1)) : null;
                if (lvl == null) return t("Type /readers default and deny, read or write: what anyone not listed may do.");
                Patrons.setDefault(store, lvl);
                return t("Anyone not listed may {0}.", t(String.valueOf(lvl)));
            }
            case "token" -> {
                if (words.size() < 2) return t("Say which reader, by id; /readers lists them.");
                if (Patrons.byDid(store, words.get(1)) == null) return t("{0} is not on the readers list.", words.get(1));
                return t("A token for {0}, shown this once; a program sends it as Authorization: Bearer <token>:", words.get(1)) + "\n" + Patrons.issueToken(store, words.get(1));
            }
            default -> { return t("/readers lists who may read and write; /invite <name> [read|write]; /readers allow <did> <read|write|deny> [name]; /readers remove <did>; /readers default <level>; /readers token <did>."); }
        }
    }
}
