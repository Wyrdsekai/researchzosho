package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.researchzosho.librarian.Pages.t;
import static org.researchzosho.librarian.Pages.n;

/** The career page, {@code /career}: the reading of a person's working life, what they want with each topic's state, the facts, a box to say more, and what changed lately. */
public final class CareerPage {
    private CareerPage() { }

    public static String body(LibraryStore store, Career.Seeker s, boolean writer) throws IOException {
        StringBuilder b = new StringBuilder();
        List<Career.Seeker> all = Career.seekers(store);
        if (s == null) {   // the front door, as the dossier's: open a person by name, the people on the shelves, and a new one
            b.append("<form class=\"big\" action=\"/career\"><input name=\"seeker\" placeholder=\"").append(Pages.esc(t("A person looking for work…"))).append("\" autofocus aria-label=\"").append(Pages.esc(t("the person's name"))).append("\"> <button>").append(t("Open")).append("</button></form>");
            b.append("<p class=\"k\">").append(t("For each person: their working life read from what they handed over, what they want next, how jobs like theirs are filled, the employers of their place and the openings that fit. Nothing here leaves the library.")).append("</p>");
            b.append("<h2 id=\"board\">").append(t("An employer's board")).append("</h2><p class=\"k\">").append(t("Any employer or institution by name: its own careers board, found from its careers page or the organisation it belongs to, and the openings on it.")).append("</p>")
             .append("<form method=\"post\" action=\"/career\" class=\"inline\"><input type=\"hidden\" name=\"op\" value=\"board\"><input name=\"employer\" size=\"40\" placeholder=\"").append(Pages.esc(t("an employer's or institution's name"))).append("\" aria-label=\"").append(Pages.esc(t("the employer"))).append("\"> <input name=\"towns\" size=\"24\" placeholder=\"").append(Pages.esc(t("towns, comma-separated (optional)"))).append("\" aria-label=\"").append(Pages.esc(t("towns"))).append("\"> <button>").append(t("Read its board")).append("</button></form>");
            if (all.isEmpty()) b.append("<p>").append(writer ? t("Nobody's working life is kept here yet. Start one below.") : t("Nobody's working life is kept here yet.")).append("</p>");
            else {
                b.append("<h2 id=\"all\">").append(t("The people on the shelves")).append("</h2><table><tr><th>").append(t("Person")).append("</th><th>").append(t("Facts")).append("</th><th>").append(Chrome.now().tc("Reading", "a column of the table of people: whether the library has written its reading of the person")).append("</th><th>").append(t("Decided")).append("</th><th>").append(t("Last search")).append("</th></tr>");
                for (Career.Seeker x : all) {
                    int facts = Career.evidence(store, x).size(), decided = 0;
                    for (JsonNode d : Career.wantsKept(store, x).path("dimensions")) if (d.path("state").asText("").equals("decided")) decided++;
                    String last = lastSearch(store, x);
                    b.append("<tr><td><a href=\"/career?seeker=").append(Pages.esc(x.slug())).append("\">").append(Pages.esc(x.name())).append("</a></td><td>").append(facts).append("</td><td>")
                     .append(Career.readingKept(store, x).isBlank() ? "" : t("written")).append("</td><td>").append(decided).append("</td><td>").append(Pages.esc(Chrome.now().day(last))).append("</td></tr>");
                }
                b.append("</table>");
            }
            if (writer) {
                b.append("<h2 id=\"new\">").append(t("A new person")).append("</h2><p>").append(t("Their name, and their resume or LinkedIn file, or what they want next in their own words, and the library opens a page for them.")).append("</p>")
                 .append(newPersonForm());
            }
            return b.toString();
        }
        ArrayNode items = Career.evidence(store, s);
        List<String> sources = Career.sources(items);
        b.append(TOC_MARK);   // filled in below, once the page knows which sections it holds
        b.append("<p class=\"k\">").append(n(items.size(), "{0} fact", "{0} facts")).append(sources.isEmpty() ? "" : ", " + t("from {0}", Pages.esc(Career.and(sources))))
                .append(" · <a href=\"/career\">").append(all.size() > 1 ? t("the other people, or another person") : t("another person")).append("</a></p>");
        b.append(searchStand(store, s, writer));   // the search is what the page is for: its button stands at the top, with where the search is
        b.append(searches(store, s));   // then the searches run so far, under the box that starts one
        String reading = Career.readingKept(store, s);
        b.append("<h2 id=\"reading\">").append(t("The reading")).append("</h2>");
        if (reading.isBlank()) {
            b.append("<p>").append(t("There is no reading yet.")).append("</p>");
            if (writer && !items.isEmpty()) b.append(readingForm(s, t("Write the reading")));
        } else {
            if (Career.stale(store, s)) b.append("<p><b>").append(t("The library has been given more since it wrote this reading.")).append("</b> ").append(t("Read it again to cover everything.")).append("</p>");
            b.append("<div class=\"prose\">").append(Pages.md(withoutQuestions(reading))).append("</div>");
            b.append("<p class=\"k\">").append(t("The numbers in square brackets, like [E3], point to the facts each line rests on. The facts are listed further down."))
                    .append(reading.contains("[general knowledge]") ? " " + t("[general knowledge] marks a line that rests on what the model knew about a company from elsewhere, not on what you handed over.") : "").append("</p>");
            if (writer) b.append(readingForm(s, t("Read it again")));
        }
        if (!reading.isBlank() && !CareerInterview.fromReading(reading).isEmpty()) b.append(questions(store, s, writer));
        ObjectNode w = Career.wantsKept(store, s);
        b.append("<h2 id=\"wants\">").append(t("What you want")).append("</h2>");
        if (w.path("dimensions").isEmpty()) b.append("<p>").append(t("The library drafts this together with the reading.")).append("</p>");
        else {
            b.append("<p>").append(t("As the library understands it on {0}.", Pages.esc(Chrome.now().day(w.path("at").asText(""))))).append(" ").append(t("Decided means you said it. A guess is what the library read from your history, for you to confirm or correct. Open means nothing is known yet.")).append("</p>");
            b.append("<table><tr><th>").append(t("Topic")).append("</th><th>").append(t("Where it stands")).append("</th><th>").append(t("What you want")).append("</th><th>").append(t("Based on")).append("</th></tr>");
            for (JsonNode e : w.path("dimensions")) {
                String dim = e.path("dimension").asText(), st = e.path("state").asText("open");
                b.append("<tr><td>").append(Pages.esc(t(Career.cap(Career.label(dim))))).append("</td><td>").append(Pages.esc(t(Career.STATE_WORDS.getOrDefault(st, st)))).append("</td><td>")
                        .append(Pages.esc(e.path("want").asText(""))).append("</td><td class=\"k\">").append(Pages.esc(Career.basis(e))).append("</td></tr>");
                if (writer) {
                    b.append("<tr><td colspan=\"4\"><form method=\"post\" action=\"/career\" class=\"inline\"><input type=\"hidden\" name=\"op\" value=\"want\"><input type=\"hidden\" name=\"seeker\" value=\"").append(Pages.esc(s.slug()))
                            .append("\"><input type=\"hidden\" name=\"dimension\" value=\"").append(Pages.esc(dim)).append("\"><select name=\"state\" aria-label=\"").append(Pages.esc(t("where it stands"))).append("\">");
                    for (String x : Career.STATES) b.append("<option value=\"").append(x).append("\"").append(x.equals(st) ? " selected" : "").append(">").append(t(Career.STATE_WORDS.get(x))).append("</option>");
                    b.append("</select> <input name=\"want\" value=\"").append(Pages.esc(e.path("want").asText(""))).append("\" placeholder=\"").append(Pages.esc(t("what you want, in your own words"))).append("\" size=\"60\" aria-label=\"").append(Pages.esc(t("what you want"))).append("\"> <button>").append(t("Save")).append("</button></form></td></tr>");
                }
            }
            b.append("</table>");
        }
        b.append(searchProfile(store, s, writer));
        b.append(companies(store, s, writer));
        b.append(openings(store, s, writer));
        if (!items.isEmpty()) {
            b.append("<details><summary>").append(n(items.size(), "The {0} fact the reading rests on", "The {0} facts the reading rests on")).append("</summary>");
            String last = null;
            for (JsonNode it : items) {
                String src = it.path("source").asText();
                if (!src.equals(last)) { if (last != null) b.append("</ul>"); b.append("<p class=\"k\">").append(t("From {0}:", Pages.esc(src))).append("</p><ul>"); last = src; }
                b.append("<li><b>").append(Pages.esc(it.path("id").asText())).append(".</b> ").append(Pages.esc(Career.withLength(it, LocalDate.now()))).append("</li>");
            }
            b.append("</ul>");
            List<String> counts = Career.counted(items, LocalDate.now());
            if (!counts.isEmpty()) {
                b.append("<p class=\"k\">").append(t("Counted by the library from the roles' titles:")).append("</p><ul>");
                for (int i = 0; i < counts.size(); i++) b.append("<li><b>C").append(i + 1).append(".</b> ").append(Pages.esc(counts.get(i))).append("</li>");
                b.append("</ul>");
            }
            b.append("</details>");
        }
        if (writer) {
        CareerShape.Shape shape = CareerShape.kept(store, s);
        b.append("<h2 id=\"filled\">").append(t("How jobs like yours are filled")).append("</h2>").append(CareerShape.html(shape));
        if (writer) b.append("<form method=\"post\" action=\"/career\" class=\"inline\"><input type=\"hidden\" name=\"op\" value=\"shape\"><input type=\"hidden\" name=\"seeker\" value=\"").append(Pages.esc(s.slug()))
                .append("\"><input name=\"words\" value=\"").append(Pages.esc(shape == null ? "" : shape.told())).append("\" placeholder=\"").append(Pages.esc(t("how jobs like yours are filled, in your own words"))).append("\" size=\"60\" aria-label=\"").append(Pages.esc(t("how jobs like yours are filled"))).append("\"> <button>").append(Chrome.now().tc("Keep", "a button that saves the thing beside it")).append("</button></form>")
                .append("<p class=\"k\">").append(t("Your own word outranks what the library drew; the next search follows it.")).append("</p>");
            b.append("<h2 id=\"tell\">").append(t("Tell the library more")).append("</h2><p>").append(t("Write here what you want next, what you would refuse, anything at all, or hand over a file, such as a resume or a LinkedIn file. What you write that settles a topic, a role, a place, remote or not, an industry you want or rule out, a company to look at, is taken as decided at once, and the next search starts from it.")).append("</p>").append(provideForm(s));
        }
        List<String> tail = Career.logTail(store, s, 12);
        if (!tail.isEmpty()) { b.append("<h2 id=\"lately\">").append(t("What changed lately")).append("</h2><ul class=\"k\">"); for (String l : tail) b.append("<li>").append(Pages.esc(l)).append("</li>"); b.append("</ul>"); }
        return toc(b.toString(), s);
    }

    /** The table of contents at the head of a person's page: the sections this page holds, in their order, then the pages beside it. */
    private static final ObjectMapper M = new ObjectMapper();
    static final String TOC_MARK = "<!--toc-->";
    static final String[][] SECTIONS = {{"searches", "Searches"}, {"reading", "The reading"}, {"wants", "What you want"}, {"filled", "How jobs like yours are filled"}, {"tell", "Tell the library more"},
            {"lately", "What changed lately"}, {"questions", "Questions for you"}, {"search", "What the search will use"}, {"employers", "Employers of your place"}, {"openings", "Openings"}};

    /**
     * Every section of a person's page folds: its heading is the summary of a details block that opens on a click, closed by default, so the
     * page reads as its contents and the search box, and a link in the contents opens the section it points to.
     */
    static String folded(String page) {
        Matcher m = Pattern.compile("<h2 id=\"([a-z]+)\">([^<]*)</h2>").matcher(page);
        StringBuilder out = new StringBuilder();
        int at = 0; boolean open = false;
        while (m.find()) {
            out.append(page, at, m.start());
            if (open) out.append("</details>");
            out.append("<details class=\"sec\" id=\"").append(m.group(1)).append("\"><summary><h2>").append(m.group(2)).append("</h2></summary>");
            open = true; at = m.end();
        }
        out.append(page.substring(at));
        if (open) out.append("</details>");
        out.append("<script>(function(){function o(){var h=location.hash;if(!h)return;var d=document.querySelector(h);if(d&&d.tagName==='DETAILS')d.open=true;}window.addEventListener('hashchange',o);o();})()</script>");
        return out.toString();
    }

    static String toc(String page, Career.Seeker s) {
        StringBuilder nav = new StringBuilder("<nav class=\"in\">");
        for (String[] sec : SECTIONS) if (page.contains("<h2 id=\"" + sec[0] + "\">")) nav.append("<a href=\"#").append(sec[0]).append("\">").append(t(sec[1])).append("</a>");
        nav.append("<a href=\"/career/openings?seeker=").append(Pages.esc(s.slug())).append("\">").append(t("The openings page")).append("</a>")
           .append("<a href=\"/career/companies?seeker=").append(Pages.esc(s.slug())).append("\">").append(t("The employers page")).append("</a>")
           .append("<a href=\"/sources\">").append(t("Sources")).append("</a><a href=\"/career\">").append(t("People")).append("</a></nav>");
        return folded(page.replace(TOC_MARK, nav.toString()));
    }

    /** The reading without its closing questions, which the page shows as the interview's form instead. */
    static String withoutQuestions(String md) {
        StringBuilder b = new StringBuilder(); boolean skip = false;
        for (String line : md.split("\\R", -1)) {
            if (line.startsWith("## ")) skip = line.contains("Questions for you") || line.contains("Possible wants");
            if (!skip) b.append(line).append('\n');
        }
        return b.toString();
    }

    /** The interview on the page: every question with its own box, filled with any answer given, in the chat or here; one button saves them all. */
    static String questions(LibraryStore store, Career.Seeker s, boolean writer) throws IOException {
        JsonNode o = CareerInterview.state(store, s);
        List<CareerInterview.Question> qs = CareerInterview.questions(o);
        int mains = 0, done = 0; for (CareerInterview.Question q : qs) if (q.main()) { mains++; if (!CareerInterview.answerOf(o, q.n()).isEmpty()) done++; }
        StringBuilder b = new StringBuilder("<h2 id=\"questions\">").append(t("Questions for you")).append("</h2>");
        b.append("<p>").append(done == 0 ? t("The reading ends in {0} questions.", mains) : t("{0} of {1} questions are answered.", done, mains))
                .append(writer ? " " + t("Answer any of them, in your own words, and save; you can come back to the rest. In the chat, /career interview asks them one at a time, and an answer given there shows here.") : "").append("</p>");
        if (writer) b.append("<form method=\"post\" action=\"/career\" class=\"stack\"><input type=\"hidden\" name=\"op\" value=\"answers\"><input type=\"hidden\" name=\"seeker\" value=\"").append(Pages.esc(s.slug())).append("\">");
        List<CareerInterview.Round> rounds = CareerInterview.rounds(o);
        for (CareerInterview.Question q : qs) {
            if (rounds.size() > 1) for (int i = 0; i < rounds.size(); i++) {
                CareerInterview.Round r = rounds.get(i);
                if (!q.main() || CareerInterview.number(q.n()) != r.from()) continue;
                b.append("<h3>").append(t("From {0}", Pages.esc(r.label()))).append("</h3>");
                if (i > 0) b.append("<p class=\"k\">").append(t("Written with your answers so far:")).append(" ").append(r.count() - r.deeper() > 0 ? t("{0} about the search, then {1} that go deeper into what you said.", r.count() - r.deeper(), r.deeper()) : t("{0} that go deeper into what you said.", r.deeper())).append(" ").append(t("Answer any, or none; the search needs no more than you have given.")).append("</p>");
            }
            String a = CareerInterview.answerOf(o, q.n()), taken = CareerInterview.takenOf(o, q.n());
            b.append("<div class=\"q\"><p>").append(q.main() ? "<b>" + Pages.esc(q.n()) + ".</b> " : "<b>" + t("A follow-up to question {0}:", Pages.esc(q.followupTo())) + "</b> ").append(Pages.esc(q.text())).append("</p>");
            if (writer) b.append("<textarea name=\"a.").append(Pages.esc(q.n())).append("\" rows=\"3\" aria-label=\"").append(Pages.esc(t("your answer to question {0}", q.n()))).append("\">").append(Pages.esc(a)).append("</textarea>");
            else if (!a.isEmpty()) b.append("<p>").append(Pages.esc(a)).append("</p>");
            if (!taken.isEmpty()) b.append("<p class=\"k\">").append(Pages.esc(taken)).append("</p>");
            b.append("</div>");
        }
        if (writer) b.append("<button>").append(t("Save answers")).append("</button> <span class=\"k\">").append(t("The model reads the new answers for what they settle. This takes a little while, and the page waits until it is done.")).append("</span></form>");
        if (writer && done > 0) b.append("<form method=\"post\" action=\"/career\" class=\"inline\"><input type=\"hidden\" name=\"op\" value=\"resettle\"><input type=\"hidden\" name=\"seeker\" value=\"").append(Pages.esc(s.slug()))
                .append("\"><button>").append(t("Read my answers again")).append("</button> <span class=\"k\">").append(t("Every answer read again for what it settles about what you want; the topics set from answers are set again, what you set yourself stays.")).append("</span></form>");
        return b.toString();
    }

    /** What the search will use besides the topics: the companies named, and what is kept out, each with a way to add or take one off. */
    static String searchProfile(LibraryStore store, Career.Seeker s, boolean writer) throws IOException {
        List<String> cs = CareerSearch.companies(store, s), ko = CareerSearch.keepOut(store, s);
        StringBuilder b = new StringBuilder("<h2 id=\"search\">").append(t("What the search will use")).append("</h2><p>").append(t("Besides what you want above: the companies you named, and what you keep out. Something kept out may be a company, an industry, a kind of work or a condition.")).append("</p>");
        b.append("<p><b>").append(t("Companies you want looked at:")).append("</b> ").append(cs.isEmpty() ? t("none named yet.") : "").append("</p>");
        if (!cs.isEmpty()) { b.append("<ul>"); for (String c : cs) b.append("<li>").append(Pages.esc(c)).append(writer ? removeForm(s, "companies", c) : "").append("</li>"); b.append("</ul>"); }
        b.append("<p><b>").append(t("Kept out:")).append("</b> ").append(ko.isEmpty() ? t("nothing yet.") : "").append("</p>");
        if (!ko.isEmpty()) { b.append("<ul>"); for (String k : ko) b.append("<li>").append(Pages.esc(k)).append(writer ? removeForm(s, "keep_out", k) : "").append("</li>"); b.append("</ul>"); }
        if (writer) {
            b.append("<form method=\"post\" action=\"/career\" class=\"inline\"><input type=\"hidden\" name=\"op\" value=\"company\"><input type=\"hidden\" name=\"seeker\" value=\"").append(Pages.esc(s.slug()))
                    .append("\"><input name=\"words\" placeholder=\"").append(Pages.esc(t("a company to look at"))).append("\" aria-label=\"").append(Pages.esc(t("a company to look at"))).append("\"> <button>").append(t("Add")).append("</button></form> ");
            b.append("<form method=\"post\" action=\"/career\" class=\"inline\"><input type=\"hidden\" name=\"op\" value=\"keep-out\"><input type=\"hidden\" name=\"seeker\" value=\"").append(Pages.esc(s.slug()))
                    .append("\"><input name=\"words\" placeholder=\"").append(Pages.esc(t("something to keep out"))).append("\" aria-label=\"").append(Pages.esc(t("something to keep out"))).append("\"> <button>").append(t("Keep out")).append("</button></form>");
        }
        return b.toString();
    }

    /** Where the search stands, at the top of the person's page: not run yet, running, or what the last one found, with the button that starts it and the way to the openings list. */
    static String searchStand(LibraryStore store, Career.Seeker s, boolean writer) throws IOException {
        String running = CareerOpeningsPage.running(store, s);
        String link = "<a href=\"/career/openings?seeker=" + Pages.esc(s.slug()) + "\">";
        if (!running.isEmpty()) return "<div class=\"notice\"><b>" + t("The search is on.") + "</b> " + Pages.esc(running) + " " + t("{0} fills as it goes; {1} shows how far it has come.", link + t("The openings list") + "</a>", "<a href=\"/jobs\">" + t("the runs page") + "</a>") + "</div>";
        CareerFind.View v = CareerFind.view(store, s);
        if (v.companies().isEmpty() && v.openings() == 0) {
            return "<div class=\"notice\"><b>" + t("No search yet.") + "</b> " + t("A search lists the employers of your place, the ones you named first, reads their openings, keeps the ones that fit what you decided, and has the model read the nearest; it takes half an hour to three quarters of an hour.") + " "
                    + t("The more the library knows of you below, the better it looks.") + " " + (writer ? searchButton(s, t("Look for openings")) : "") + "</div>";
        }
        Set<String> at = new HashSet<>();
        for (CareerFind.Row r : v.passing()) at.add(CareerSearch.key(r.opening().company()));
        return "<div class=\"notice\"><b>" + t("Searched {0}:", Pages.esc(Chrome.now().day(v.searched()))) + "</b> " + t("{0} what you decided, at {1}.", n(v.passing().size(), "{0} opening fits", "{0} openings fit"), n(at.size(), "{0} employer", "{0} employers")) + " "
                + link + t("See the openings") + "</a>. " + (writer ? searchButton(s, t("Search again")) : "") + "</div>";
    }

    /** The button that starts a search for the person, on their own page. */
    static String searchButton(Career.Seeker s, String label) {
        return "<form method=\"post\" action=\"/career\" class=\"inline\"><input type=\"hidden\" name=\"op\" value=\"search\"><input type=\"hidden\" name=\"seeker\" value=\"" + Pages.esc(s.slug()) + "\">"
                + "<button>" + Pages.esc(label) + "</button> <input name=\"vary\" size=\"44\" placeholder=\"" + Pages.esc(t("a change for this search only, e.g. without the pets field, and New Hampshire as well")) + "\" aria-label=\"" + Pages.esc(t("a change for this search only")) + "\"></form>";
    }

    /**
     * The searches run for the person so far, newest first: when, the cut each used, how many employers it looked at and could read, how
     * many openings passed and were read, and the employers of each behind a click. From the records the search writes as it ends.
     */
    static String searches(LibraryStore store, Career.Seeker s) throws IOException {
        Path dir = CareerFind.dir(store, s).resolve("runs");
        if (!Files.isDirectory(dir)) return "";
        List<Path> files = new ArrayList<>();
        try (var st = Files.list(dir)) { for (Path p : st.toList()) if (p.getFileName().toString().endsWith(".json")) files.add(p); }
        if (files.isEmpty()) return "";
        files.sort(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed());
        StringBuilder b = new StringBuilder("<h2 id=\"searches\">").append(t("Searches")).append("</h2><p class=\"k\">").append(t("Every search run for you, newest first, with what it used and what it found; the date opens what that search found, as it stands now. Nothing a search found is thrown away by the next: the openings stay with the days they were seen, and the lists above show the present state.")).append("</p>");
        b.append("<table><tr><th>").append(t("When")).append("</th><th>").append(t("This time")).append("</th><th>").append(t("Employers looked at")).append("</th><th>").append(t("Could read")).append("</th><th>").append(t("Openings that passed")).append("</th><th>").append(Chrome.now().tc("Read", "a column of the table of searches: how many openings the search read in full")).append("</th><th>").append(t("What counted as a fit")).append("</th></tr>");
        int shown = 0;
        for (Path p : files) {
            if (shown++ >= 20) { b.append("<tr><td colspan=\"7\" class=\"k\">").append(t("and {0} earlier", files.size() - 20)).append("</td></tr>"); break; }
            JsonNode j;
            try { j = M.readTree(p.toFile()); } catch (IOException e) { continue; }
            int looked = 0, readable = 0, research = 0;
            List<String> names = new ArrayList<>();
            for (JsonNode x : j.path("companies")) {
                boolean l = x.has("looked") ? x.path("looked").asBoolean(false) : !x.path("system").asText("").isEmpty() || !x.path("note").asText("").isEmpty();
                if (!l) continue;
                looked++;
                if (!x.path("system").asText("").isEmpty()) readable++;
                if (x.path("from").asText("").equals("research")) research++;
                names.add(x.path("name").asText("") + (x.path("openings").asInt(0) > 0 ? " (" + x.path("openings").asInt() + ")" : ""));
            }
            String when = j.path("at").asText("").replace("T", " "), stem = p.getFileName().toString().replaceFirst("\\.json$", "");
            b.append("<tr><td><a href=\"/career/openings?seeker=").append(Pages.esc(s.slug())).append("&amp;run=").append(Pages.esc(stem)).append("\">").append(Pages.esc(Chrome.now().day(when))).append("</a></td><td class=\"k\">").append(Pages.esc(j.path("vary").asText(""))).append("</td><td>").append(looked).append(research > 0 ? " <span class=\"k\">(" + t("{0} by the library's research", research) + ")</span>" : "").append("</td><td>").append(readable)
             .append("</td><td>").append(j.path("passing").size()).append("</td><td>").append(j.path("read").size()).append("</td><td class=\"k\">").append(Pages.esc(Career.cap(j.path("cut").asText("")))).append("</td></tr>");
            if (!names.isEmpty()) b.append("<tr><td colspan=\"7\"><details><summary class=\"k\">").append(t("The employers it looked at")).append("</summary><p class=\"k\">").append(Pages.esc(String.join(" · ", names))).append("</p></details></td></tr>");
        }
        return b.append("</table>").toString();
    }

    /** The companies of the person's place from the public records, in a line, with the page that lists them. */
    static String companies(LibraryStore store, Career.Seeker s, boolean writer) throws IOException {
        StringBuilder b = new StringBuilder("<h2 id=\"employers\">").append(t("Employers of your place")).append("</h2>");
        String link = "<a href=\"/career/companies?seeker=" + Pages.esc(s.slug()) + "\">";
        ObjectNode doc = CareerCompanies.kept(store, s);
        CareerCut.Cut cut = CareerCut.kept(store, s);
        if (cut == null) cut = CareerCut.Cut.NONE;
        if (!doc.has("companies") || doc.path("companies").isEmpty()) {
            b.append("<p>").append(t("Besides posted openings, the library starts from the employers of your place: the ones the public records name, company registers, the lists of hospitals, schools and universities, and the filings of money raised, each read against what you want.")).append(" ")
                    .append(cut.places().isEmpty() ? t("It needs a place first: decide where you work, above.") : t("A search gathers them.")).append(" ").append(link).append(t("The employers page")).append("</a>.</p>");
            return b.toString();
        }
        List<ObjectNode> standing = CareerCompanies.standing(store, s, cut);
        long yes = standing.stream().filter(e -> e.path("reading").path("approach").asText("").equals("yes")).count(), unread = standing.stream().filter(e -> !e.has("reading")).count();
        b.append("<p>").append(t("{0} employers of {1} from the public records, refreshed {2}: {3} fit what you decided, {4} worth approaching now", doc.path("companies").size(), Pages.esc(doc.path("place").asText("")), Pages.esc(Chrome.now().day(doc.path("refreshed").asText(""))), standing.size(), yes)).append(unread > 0 ? ", " + t("{0} not read yet", unread) : "").append(". ").append(link).append(t("See the employers")).append("</a>.</p>");
        return b.toString();
    }

    static String openings(LibraryStore store, Career.Seeker s, boolean writer) throws IOException {
        StringBuilder b = new StringBuilder("<h2 id=\"openings\">").append(t("Openings")).append("</h2>");
        String running = CareerOpeningsPage.running(store, s);
        CareerFind.View v = CareerFind.view(store, s);
        String link = "<a href=\"/career/openings?seeker=" + Pages.esc(s.slug()) + "\">";
        if (!running.isEmpty()) b.append("<p class=\"notice\">").append(Pages.esc(running)).append(" ").append(t("{0} fills as it goes.", link + t("The openings list") + "</a>")).append("</p>");
        if (v.companies().isEmpty() && v.openings() == 0) {
            b.append("<p>").append(t("No search yet.")).append(" ").append(t("A search lists the employers for you, the ones you named first, then those the public records and the lists for your work and place name, reads their openings from their own hiring pages, keeps the ones that fit what you decided, and has the model read the nearest. It takes half an hour to three quarters of an hour.")).append("</p>");
            if (writer && running.isEmpty()) b.append(searchButton(s, t("Look for openings")));
            return b.toString();
        }
        long read = v.passing().stream().filter(CareerFind.Row::read).count();
        if (CareerCut.stale(store, s)) b.append("<p class=\"notice\">").append(t("What you want has changed since the openings were sorted.")).append(" ").append(t("{0} can sort them again, with no new search.", link + t("The openings list") + "</a>")).append("</p>");
        b.append("<p>").append(t("Searched {0}:", Pages.esc(Chrome.now().day(v.searched())))).append(" ").append(t("{0} what you decided; the library read {1}.", n(v.passing().size(), "{0} opening fits", "{0} openings fit"), read)).append(" ").append(link).append(t("See the openings")).append("</a>.")
                .append(CareerWatch.on(store, s) ? " " + t("The library looks again every night.") : "").append("</p>");
        return b.toString();
    }

    private static String removeForm(Career.Seeker s, String list, String words) {
        return " <form method=\"post\" action=\"/career\" class=\"inline\"><input type=\"hidden\" name=\"op\" value=\"remove\"><input type=\"hidden\" name=\"seeker\" value=\"" + Pages.esc(s.slug())
                + "\"><input type=\"hidden\" name=\"list\" value=\"" + list + "\"><input type=\"hidden\" name=\"words\" value=\"" + Pages.esc(words) + "\"><button class=\"lnk\">" + t("take off") + "</button></form>";
    }

    /** The day of the person's last search, from their log; "" before any. */
    static String lastSearch(LibraryStore store, Career.Seeker s) {
        try {
            Path log = s.dir(store).resolve("log.jsonl");
            if (!Files.isRegularFile(log)) return "";
            String last = "";
            for (String line : Files.readAllLines(log, StandardCharsets.UTF_8)) if (line.contains("\"what\":\"The search looked at")) last = line;
            if (last.isEmpty()) return "";
            int at = last.indexOf("\"at\":\""); return at < 0 ? "" : last.substring(at + 6, Math.min(last.length(), at + 16));
        } catch (IOException e) { return ""; }
    }

    /** The form that starts a new person: their name and their words; the name makes the page, or adds to the person of that name. */
    static String newPersonForm() {
        return "<form method=\"post\" action=\"/career\" enctype=\"multipart/form-data\" class=\"stack\"><input type=\"hidden\" name=\"op\" value=\"provide\">"
                + "<input type=\"text\" name=\"name\" required placeholder=\"" + Pages.esc(t("their name")) + "\" aria-label=\"" + Pages.esc(t("the person's name")) + "\">"
                + "<textarea name=\"text\" rows=\"4\" placeholder=\"" + Pages.esc(t("what they want next, what they would refuse, where, how much")) + "\" aria-label=\"" + Pages.esc(t("what they want next")) + "\"></textarea>"
                + "<label>" + t("Their resume, or their LinkedIn file:") + " <input type=\"file\" name=\"file\" accept=\".pdf,.docx,.pptx,.odt,.epub,.html,.htm,.txt,.md,.json\"></label> <button>" + t("Add") + "</button></form>";
    }

    private static String provideForm(Career.Seeker s) {
        return "<form method=\"post\" action=\"/career\" enctype=\"multipart/form-data\" class=\"stack\"><input type=\"hidden\" name=\"op\" value=\"provide\">" + (s == null ? "" : "<input type=\"hidden\" name=\"seeker\" value=\"" + Pages.esc(s.slug()) + "\">")
                + "<textarea name=\"text\" rows=\"4\" placeholder=\"" + Pages.esc(t("what you want next, what you would refuse, where, how much")) + "\" aria-label=\"" + Pages.esc(t("what you want next")) + "\"></textarea>"
                + "<label>" + t("A resume, or a LinkedIn file:") + " <input type=\"file\" name=\"file\" accept=\".pdf,.docx,.pptx,.odt,.epub,.html,.htm,.txt,.md,.json\"></label> <button>" + Chrome.now().tc("Keep", "a button that saves the thing beside it") + "</button></form>";
    }

    private static String readingForm(Career.Seeker s, String label) {
        return "<form method=\"post\" action=\"/career\" class=\"inline\"><input type=\"hidden\" name=\"op\" value=\"reading\"><input type=\"hidden\" name=\"seeker\" value=\"" + Pages.esc(s.slug()) + "\"><button>" + label
                + "</button> <span class=\"k\">" + t("The model reads every fact. This takes a minute or two, and the page waits until it is done.") + "</span></form>";
    }
}
