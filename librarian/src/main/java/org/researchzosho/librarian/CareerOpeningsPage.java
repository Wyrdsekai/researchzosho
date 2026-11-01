package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.researchzosho.librarian.CareerCut.Cut;
import org.researchzosho.librarian.CareerFind.Row;
import org.researchzosho.librarian.CareerFind.View;
import org.researchzosho.librarian.CareerSources.Opening;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The Career page's openings list (docs/DESIGN_CAREER.md, step 3): what the last search found, the cut that made it, the openings the
 * library read with its readings, the rest that pass by company, the companies with what each gave, and the person's reactions. Every
 * opening says when it was posted and where it was read; the person likes or passes with why, keeps or drops a company, and changes
 * the cut, which cuts again what is kept at once, with no new search.
 */
public final class CareerOpeningsPage {
    private CareerOpeningsPage() { }
    private static final ObjectMapper M = new ObjectMapper();

    public static String body(LibraryStore store, Career.Seeker s, boolean writer) throws IOException { return body(store, s, writer, ""); }

    /** As above; with {@code run}, the name of a search's record, the page shows what that search found, as it stands now. */
    public static String body(LibraryStore store, Career.Seeker s, boolean writer, String run) throws IOException {
        StringBuilder b = new StringBuilder();
        b.append("<p class=\"k\"><a href=\"/career?seeker=").append(Pages.esc(s.slug())).append("\">").append(Pages.esc(s.name())).append("'s career page</a></p>");
        View v = CareerFind.view(store, s);
        if (run != null && !run.isBlank()) return b.append(pastSearch(store, s, v, run.strip(), writer)).toString();
        String running = running(store, s);
        if (!running.isEmpty()) b.append("<p class=\"notice\">").append(Pages.esc(running)).append(" <a href=\"/jobs\">The runs page</a> shows how far it has come.</p>");
        if (v.companies().isEmpty() && v.openings() == 0) {
            b.append("<p>There has been no search yet. A search lists the employers for you, the ones you named first, then those the public records and the lists for your work and place name, ")
                    .append("reads their openings from their own hiring pages, keeps the ones that fit what you decided, and has the model read the ").append(CareerFind.readingsMax())
                    .append(" nearest to what you want. It takes about half an hour to three quarters of an hour.</p>");
            if (writer && running.isEmpty()) b.append(searchForm(s, "Search now"));
            return b.toString();
        }
        long read = v.passing().stream().filter(Row::read).count(), unread = v.passing().stream().filter(r -> !r.read() && r.reactionWord().isEmpty()).count();
        int looked = 0, readable = 0;
        for (ObjectNode c : v.companies()) if (CareerFind.lookedAt(c)) { looked++; if (c.has("feed")) readable++; }
        Map<String, Integer> passAt = new LinkedHashMap<>();
        for (Row r : v.passing()) passAt.merge(CareerSearch.key(r.opening().company()), 1, Integer::sum);
        b.append("<p>Searched ").append(Pages.esc(v.searched().isEmpty() ? "not yet" : v.searched())).append(". ").append(looked).append(looked == 1 ? " employer" : " employers")
                .append(" on the list; the library could read the openings of ").append(readable).append(". <b>").append(v.passing().size()).append(v.passing().size() == 1 ? " opening" : " openings")
                .append(v.cut().cutsNothing() && !v.passing().isEmpty() ? "</b> read, at " : (v.passing().size() == 1 ? " fits</b> what you decided, at " : " fit</b> what you decided, at ")).append(passAt.size()).append(passAt.size() == 1 ? " employer" : " employers").append("; the library read ").append(read).append(".")
                .append(v.cut().cutsNothing() && !v.passing().isEmpty() ? " The role and the place are not decided yet, so the library sorted them by its guesses; the ones that match both come first, and the rest stay closed below." : "").append("</p>");
        if (looked > 0 && readable == 0) b.append("<p class=\"notice\">The library found employers for you but could read the openings of none of them: their hiring pages are built in the browser or kept by a hiring system the library does not read yet. The list below names each employer; open its own page for its openings, and tell the library the address of its careers page with the Keep box below to have it tried again.</p>");
        else if (readable > 0 && v.passing().isEmpty()) b.append("<p class=\"notice\">The library read openings at ").append(readable).append(readable == 1 ? " employer" : " employers").append(" and none fits what you decided. What counts as a fit is below, with a way to change it; the employers' table says what each had.</p>");
        CareerShape.Shape shape = CareerShape.kept(store, s);
        if (shape != null) b.append("<p class=\"k\">").append(Pages.esc(CareerShape.text(shape))).append(" <a href=\"/career?seeker=").append(Pages.esc(s.slug())).append("\">Say it in your own words on the career page.</a></p>");
        if (writer && running.isEmpty()) {
            b.append(searchForm(s, "Search again"));
            if (unread > 0 && CareerFind.readingsMax() > 0) b.append(' ').append(button(s, "read", "Read " + Math.min(unread, CareerFind.readingsMax()) + " more"));
        }
        if (CareerCut.stale(store, s)) {
            b.append("<p class=\"notice\">What you want has changed since these openings were sorted, so they are kept by what you wanted before. ");
            if (writer) b.append("<form method=\"post\" action=\"/career/openings\" class=\"inline\">").append(hidden("op", "remake")).append(hidden("seeker", s.slug()))
                    .append("<button>Sort them again by what you want now</button></form> The model takes a minute; no new search is needed.");
            b.append("</p>");
        }
        Map<String, String> from = sources(v);
        Map<String, ObjectNode> byCompany = new HashMap<>();
        for (ObjectNode c : v.companies()) byCompany.put(CareerSearch.key(c.path("name").asText()), c);
        b.append(CareerWatch.section(store, s, v, from, writer));
        b.append(cut(v.cut(), s, writer));

        List<Row> passed = new ArrayList<>();
        openingsSections(b, v.passing(), v.aside(), v, s, from, byCompany, writer, passed);
        b.append(companies(store, v, s, writer));
        if (!passed.isEmpty()) {
            b.append("<details><summary>Passed <span class=\"k\">").append(passed.size()).append("</span></summary><ul>");
            for (Row r : passed) b.append("<li>").append(line(r, from)).append(r.reaction().path("why").asText("").isBlank() ? "" : " <span class=\"k\">— " + Pages.esc(r.reaction().path("why").asText()) + "</span>")
                    .append(writer ? undo(r, s) : "").append("</li>");
            b.append("</ul></details>");
        }
        b.append("<p class=\"k\">Openings are read from each employer's own hiring pages and from the open lists for your trade, never from a job board. ")
                .append("Each line says when the opening was posted, as the employer says, and the day the library last read it. A guess in what you want puts openings in order and never drops one.</p>");
        return b.toString();
    }

    /**
     * Rows by employer, in the order given, each employer a details block: open for the openings that fit, closed for the tiers below. At
     * most {@code perEmployer} lines for one employer and {@code total} in all, the remainder counted, so a board of hundreds does not become
     * the page.
     */
    static String byEmployer(List<Row> rows, boolean open, int perEmployer, int total, Career.Seeker s, Map<String, String> from, boolean writer) {
        Map<String, List<Row>> by = new LinkedHashMap<>();
        for (Row r : rows) by.computeIfAbsent(r.opening().company(), k -> new ArrayList<>()).add(r);
        StringBuilder b = new StringBuilder();
        int shown = 0, beyond = 0;
        for (Map.Entry<String, List<Row>> e : by.entrySet()) {
            if (shown >= total) { beyond += e.getValue().size(); continue; }
            String name = e.getKey().isBlank() ? "Employer not given" : e.getKey();
            b.append(open ? "<details open>" : "<details>").append("<summary>").append(Pages.esc(name)).append(" <span class=\"k\">").append(e.getValue().size()).append("</span></summary><ul>");
            int k = 0;
            for (Row r : e.getValue()) {
                if (k >= perEmployer || shown >= total) break;
                b.append("<li>").append(line(r, from)).append(writer ? react(r, s, false) : "").append("</li>");
                k++; shown++;
            }
            if (k < e.getValue().size()) b.append("<li class=\"k\">and ").append(e.getValue().size() - k).append(" more at ").append(Pages.esc(name)).append(", on its hiring pages</li>");
            b.append("</ul></details>");
        }
        if (beyond > 0) b.append("<p class=\"k\">").append(beyond).append(" more, at other employers, not listed here. Narrow what counts as a fit, below, to see fewer and nearer.</p>");
        return b.toString();
    }

    /**
     * The openings themselves, as the page lists them: liked first, then the ones the library read, then the unread by how closely each
     * matches the role and the place, then what the decisions set aside; {@code passed} collects the ones the person passed on, listed last.
     */
    static void openingsSections(StringBuilder b, List<Row> rows, List<Row> aside, View v, Career.Seeker s, Map<String, String> from, Map<String, ObjectNode> byCompany, boolean writer, List<Row> passed) { openingsSections(b, rows, aside, v.cut(), s, from, byCompany, writer, passed); }

    static void openingsSections(StringBuilder b, List<Row> rows, List<Row> aside, Cut cut, Career.Seeker s, Map<String, String> from, Map<String, ObjectNode> byCompany, boolean writer, List<Row> passed) {
        List<Row> readRows = new ArrayList<>(), liked = new ArrayList<>();
        List<Row> fit = new ArrayList<>(), nearly = new ArrayList<>(), far = new ArrayList<>();   // the unread, by how closely each matches the role and the place
        for (Row r : rows) {
            switch (r.reactionWord()) {
                case "like" -> liked.add(r);
                case "pass" -> passed.add(r);
                default -> {
                    if (r.read()) readRows.add(r);
                    else switch (CareerCut.tier(cut, r.opening())) { case 0 -> fit.add(r); case 1 -> nearly.add(r); default -> far.add(r); }
                }
            }
        }
        if (!liked.isEmpty()) {
            b.append("<h2>Liked</h2>");
            for (Row r : liked) b.append(card(r, s, from, byCompany, writer));
        }
        b.append("<h2>Read by the library</h2>");
        if (readRows.isEmpty()) b.append("<p>None yet.</p>");
        else {
            b.append("<p class=\"k\">The openings nearest to what you want, at most three from one company before a fourth from any. The reading says what fits, what goes against what you want, and what the opening does not say.</p>");
            for (Row r : readRows) b.append(card(r, s, from, byCompany, writer));
        }
        if (!fit.isEmpty() || !nearly.isEmpty() || !far.isEmpty()) {
            String as = cut.cutsNothing() ? "as the library guesses them until you decide" : "as you decided them";
            b.append("<h2>Openings that fit</h2>");
            if (fit.isEmpty()) b.append("<p>None of the unread openings matches both the role and the place, ").append(as).append(". The near misses are below.</p>");
            else b.append("<p class=\"k\">").append(fit.size()).append(fit.size() == 1 ? " opening matches" : " openings match").append(" both the role and the place, ").append(as).append("; the library has not read these yet. By employer, nearest first.</p>")
                    .append(byEmployer(fit, true, 15, 300, s, from, writer));
            if (!nearly.isEmpty()) b.append("<details><summary><b>Near misses</b> <span class=\"k\">").append(nearly.size()).append(nearly.size() == 1 ? " opening" : " openings")
                    .append(": the role elsewhere or remote, or the place with another role</span></summary>").append(byEmployer(nearly, false, 8, 300, s, from, writer)).append("</details>");
            if (!far.isEmpty()) b.append("<details><summary><b>The rest</b> <span class=\"k\">").append(far.size()).append(far.size() == 1 ? " opening" : " openings")
                    .append(": neither the role nor the place</span></summary>").append(byEmployer(far, false, 5, 200, s, from, writer)).append("</details>");
        }
        if (!aside.isEmpty()) {
            b.append("<details><summary><b>Set aside by what you decided</b> <span class=\"k\">").append(aside.size()).append("</span></summary><p class=\"k\">Each with the reason; change what counts as a fit, below, and they are judged again.</p><ul>");
            int shown = 0;
            for (Row r : aside) {
                if (shown++ >= 200) { b.append("<li class=\"k\">and ").append(aside.size() - 200).append(" more</li>"); break; }
                b.append("<li>").append(line(r, from)).append(" <span class=\"k\">— ").append(Pages.esc(r.verdict().why())).append("</span></li>");
            }
            b.append("</ul></details>");
        }
    }

    /**
     * One past search, from its record: when it ran and what it used, how many openings passed then, and those of them that still pass
     * now, listed as the page lists openings; what left the boards or was set aside since is counted. The present state is a link away.
     */
    static String pastSearch(LibraryStore store, Career.Seeker s, View v, String run, boolean writer) throws IOException {
        StringBuilder b = new StringBuilder();
        String link = "<a href=\"/career/openings?seeker=" + Pages.esc(s.slug()) + "\">";
        Path f = run.matches("[0-9A-Za-z:_-]+") ? CareerFind.dir(store, s).resolve("runs").resolve(run + ".json") : null;
        if (f == null || !Files.isRegularFile(f)) return b.append("<p>There is no record of a search called ").append(Pages.esc(run)).append(". ").append(link).append("The openings as they stand</a>.</p>").toString();
        JsonNode j = M.readTree(f.toFile());
        Set<String> then = new HashSet<>(), readThen = new HashSet<>();
        for (JsonNode id : j.path("passing")) then.add(id.asText());
        for (JsonNode id : j.path("read")) readThen.add(id.asText());
        int looked = 0, readable = 0, research = 0;
        for (JsonNode c : j.path("companies")) {
            boolean l = c.has("looked") ? c.path("looked").asBoolean(false) : !c.path("system").asText("").isEmpty() || !c.path("note").asText("").isEmpty();
            if (!l) continue;
            looked++;
            if (!c.path("system").asText("").isEmpty()) readable++;
            if (c.path("from").asText("").equals("research")) research++;
        }
        // judged by the run's own cut when the record carries one (a change for that search only), else by the standing one
        Cut runCut = j.has("cut_json") ? CareerCut.fromJson(j.path("cut_json")) : v.cut();
        boolean own = j.has("cut_json") && !j.path("vary").asText("").isEmpty();
        List<Row> rows = new ArrayList<>(), aside = new ArrayList<>();
        List<Row> all = new ArrayList<>(v.passing()); all.addAll(v.aside());
        List<String> keepOut = CareerSearch.keepOut(store, s);
        for (Row r : all) {
            if (!then.contains(r.opening().id())) continue;
            if (!own) { (v.passing().contains(r) ? rows : aside).add(r); continue; }
            CareerCut.Verdict w = CareerCut.judge(runCut, r.opening(), keepOut);
            (w.passes() ? rows : aside).add(w.passes() ? r : new Row(r.opening(), r.kept(), w, r.rank(), r.reading(), r.reaction()));
        }
        int left = then.size() - rows.size() - aside.size();
        b.append("<h2>The search of ").append(Pages.esc(j.path("at").asText("").replace("T", " "))).append("</h2>");
        if (!j.path("vary").asText("").isEmpty()) b.append("<p class=\"notice\"><b>For that search only:</b> ").append(Pages.esc(j.path("vary").asText())).append(". What you decided stood as it was; the openings here are judged by that search's own cut.</p>");
        b.append("<p>It looked at ").append(looked).append(looked == 1 ? " employer" : " employers").append(research > 0 ? ", " + research + " of them named by the library's research," : "").append(" and could read the openings of ").append(readable)
         .append(". <b>").append(then.size()).append(then.size() == 1 ? " opening passed" : " openings passed").append("</b> what counted as a fit then; the library read ").append(readThen.size()).append(". ")
         .append("Of those, ").append(rows.size()).append(own ? " still pass that search's cut" : " still pass what you decided now").append(aside.isEmpty() ? "" : ", " + aside.size() + " are set aside by it").append(left > 0 ? ", and " + left + " have left the boards since" : "").append(". ")
         .append(link).append("The openings as they stand</a>.</p>");
        b.append("<p class=\"k\">What counted as a fit then: ").append(Pages.esc(Career.cap(j.path("cut").asText("")))).append(".</p>");
        Map<String, String> from = sources(v);
        Map<String, ObjectNode> byCompany = new HashMap<>();
        for (ObjectNode c : v.companies()) byCompany.put(CareerSearch.key(c.path("name").asText()), c);
        List<Row> passed = new ArrayList<>();
        if (rows.isEmpty() && aside.isEmpty()) b.append("<p>None of what it found is on the lists now.</p>");
        else openingsSections(b, rows, aside, runCut, s, from, byCompany, writer, passed);
        if (!passed.isEmpty()) {
            b.append("<details><summary>Passed <span class=\"k\">").append(passed.size()).append("</span></summary><ul>");
            for (Row r : passed) b.append("<li>").append(line(r, from)).append(r.reaction().path("why").asText("").isBlank() ? "" : " <span class=\"k\">— " + Pages.esc(r.reaction().path("why").asText()) + "</span>").append(writer ? undo(r, s) : "").append("</li>");
            b.append("</ul></details>");
        }
        return b.toString();
    }

    /** A search for this person that is waiting or running, in a sentence; "" when none. */
    static String running(LibraryStore store, Career.Seeker s) throws IOException {
        Jobs jobs = new Jobs(store, j -> { throw new IllegalStateException("read only"); });
        for (ObjectNode j : jobs.active()) {
            if (!j.path("kind").asText().startsWith("career-") || !j.path("args").path("seeker").asText().equals(s.slug())) continue;
            boolean run = j.path("state").asText().equals("running");
            String what = j.path("kind").asText().equals("career-read") ? "More readings are " : "A search is ";
            return what + (run ? "running: " + RunProgress.stage(j.path("progress")) + "." : "waiting to start.");
        }
        return "";
    }

    /** Where each opening was read, in words, by the company on the list: its hiring system; or the open listing it came from. */
    static Map<String, String> sources(View v) {
        Map<String, String> out = new HashMap<>();
        for (ObjectNode c : v.companies()) if (c.has("feed")) out.put(CareerSearch.key(c.path("name").asText()), c.path("feed").path("system").asText(""));
        return out;
    }

    static String readFrom(Opening o, Map<String, String> from, JsonNode kept) {
        if (o.source().startsWith("hn:")) return "Hacker News's Who is hiring, " + o.source().substring(3);
        if (o.source().equals("usajobs")) return "USAJobs";
        String listed = kept == null ? "" : kept.path("listed").asText("");
        String sys = from.getOrDefault(CareerSearch.key(listed.isEmpty() ? o.company() : listed), "");
        return sys.isEmpty() ? "its hiring page" : sys;
    }

    static String line(Row r, Map<String, String> from) {
        Opening o = r.opening();
        StringBuilder b = new StringBuilder("<a href=\"").append(Pages.esc(o.url())).append("\" rel=\"noopener\">").append(Pages.esc(o.title())).append("</a>, ").append(Pages.esc(o.company()))
                .append(" <span class=\"k\">").append(Pages.esc(place(o))).append(" · ").append(Pages.esc(posted(o)));
        if (!o.pay().isBlank()) b.append(" · ").append(Pages.esc(o.pay()));
        b.append(" · read ").append(Pages.esc(r.kept() == null ? "" : r.kept().path("last_seen").asText(""))).append(" from ").append(Pages.esc(readFrom(o, from, r.kept())));
        if (r.verdict() != null && !r.verdict().mentions().isEmpty()) b.append(" · <b>mentions ").append(Pages.esc(Career.and(r.verdict().mentions()))).append(", which you keep out</b>");
        return b.append(" · [").append(o.id()).append("]</span>").toString();
    }

    static String place(Opening o) {
        if (o.location().isBlank()) return o.remote() ? "remote" : "place not given";
        return o.location() + (o.remote() && !CareerSources.remote(o.location()) ? ", remote" : "");
    }

    static String posted(Opening o) {
        return !o.posted().isBlank() ? "posted " + o.posted() : !o.postedNote().isBlank() ? "posted " + o.postedNote() : "no posting date given";
    }

    static String card(Row r, Career.Seeker s, Map<String, String> from, Map<String, ObjectNode> companies, boolean writer) {
        StringBuilder b = new StringBuilder("<div class=\"card\" id=\"o-").append(r.opening().id()).append("\"><p>").append(line(r, from)).append("</p>");
        if (r.read()) {
            JsonNode rd = r.reading();
            if (rd.path("failed").asBoolean(false)) b.append("<p class=\"k\">The model gave no reading for this one; Read more tries again.</p>");
            else if (rd.path("unshaped").asBoolean(false)) b.append("<p>").append(Pages.esc(rd.path("raw").asText())).append("</p>");
            else for (String part : List.of("fits", "against", "unknown"))
                if (rd.has(part)) b.append("<p><b>").append(Character.toUpperCase(part.charAt(0))).append(part.substring(1)).append(":</b> ").append(Pages.esc(rd.path(part).asText())).append("</p>");
            b.append("<p class=\"k\">Read on ").append(Pages.esc(rd.path("at").asText(""))).append(".</p>");
        }
        String listed = r.kept() == null ? "" : r.kept().path("listed").asText("");
        ObjectNode co = companies == null ? null : companies.get(CareerSearch.key(listed.isEmpty() ? r.opening().company() : listed));
        if (co != null) {
            String needs = CareerCompany.needs(co);
            if (!needs.isEmpty()) {
                b.append("<p class=\"k\"><b>").append(Pages.esc(needs)).append(".</b>");
                if (writer && !needs.startsWith("The ")) {
                    if (needs.startsWith("No card")) b.append(" ").append(cardForm(s, co.path("name").asText(), false, "Build its card"));
                    if (!needs.startsWith("Even")) b.append(" ").append(cardForm(s, co.path("name").asText(), true, "Full dossier"));
                }
                b.append("</p>");
            }
        }
        if (r.reactionWord().equals("like")) b.append("<p class=\"k\">You liked it").append(r.reaction().path("why").asText("").isBlank() ? "" : ": " + Pages.esc(r.reaction().path("why").asText())).append(".")
                .append(writer ? undo(r, s) : "").append("</p>");
        else if (writer) b.append("<p>").append(react(r, s, true)).append("</p>");
        return b.append("</div>").toString();
    }

    static String react(Row r, Career.Seeker s, boolean withWhy) {
        StringBuilder b = new StringBuilder(" <form method=\"post\" action=\"/career/openings\" class=\"inline\">").append(hidden("op", "react")).append(hidden("seeker", s.slug())).append(hidden("id", r.opening().id()));
        if (withWhy) b.append("<input name=\"why\" placeholder=\"why, in a few words (optional)\" size=\"40\" aria-label=\"why\"> ");
        return b.append("<button name=\"reaction\" value=\"like\"").append(withWhy ? "" : " class=\"lnk\"").append(">Like</button> <button name=\"reaction\" value=\"pass\"").append(withWhy ? "" : " class=\"lnk\"").append(">Pass</button></form>").toString();
    }

    static String undo(Row r, Career.Seeker s) {
        return " <form method=\"post\" action=\"/career/openings\" class=\"inline\">" + hidden("op", "react") + hidden("seeker", s.slug()) + hidden("id", r.opening().id()) + hidden("reaction", "")
                + "<button class=\"lnk\">undo</button></form>";
    }

    static String cut(Cut c, Career.Seeker s, boolean writer) {
        StringBuilder b = new StringBuilder("<h2>What counts as a fit</h2><p>").append(Pages.esc(Career.cap(CareerCut.summary(c)))).append(".</p><p class=\"k\">");
        if (c.at().isEmpty()) b.append("Set by the first search, from what you decided.");
        else if (c.by().equals("you")) b.append("Set by you on ").append(Pages.esc(c.at())).append(". A change in what you want leaves it as you set it, until you have the library set it again.");
        else b.append("Set by the library on ").append(Pages.esc(c.at())).append(" from what you decided; set again when what you want changes.");
        b.append(" Only what you decided keeps or drops an opening; the library's guesses only put some ahead of others.</p>");
        if (c.cutsNothing() && !c.at().isEmpty()) b.append("<p class=\"notice\">Nothing about the role or the place is decided yet, so every opening counts as a fit and the guesses only put some ahead of others. ")
                .append("Decide the role or the place on <a href=\"/career?seeker=").append(Pages.esc(s.slug())).append("\">your career page</a>, or set the words below.</p>");
        if (!writer) return b.toString();
        b.append("<details><summary>Change what counts as a fit</summary><form method=\"post\" action=\"/career/openings\">").append(hidden("op", "cut")).append(hidden("seeker", s.slug())).append("<table>");
        field(b, "titles", "Titles with any of", c.titles(), "VP, Head of, Director");
        field(b, "titles_also", "and with any of", c.titlesAlso(), "Engineering, CTO");
        field(b, "titles_not", "Titles without", c.titlesNot(), "Intern, Junior");
        field(b, "areas", "A VP, Director or Head of must be of", c.areas(), "Engineering, Technology, Product");
        b.append("<tr><td>Company size, people</td><td>at most <input name=\"max_people\" value=\"").append(c.maxPeople() > 0 ? String.valueOf(c.maxPeople()) : "").append("\" size=\"7\" aria-label=\"at most this many people\"> at least <input name=\"min_people\" value=\"")
                .append(c.minPeople() > 0 ? String.valueOf(c.minPeople()) : "").append("\" size=\"7\" aria-label=\"at least this many people\"> <span class=\"k\">cuts a company the library knows to be far outside; the rest pass, and the reading says what is known</span></td></tr>");
        field(b, "places", "Places, any of", c.places(), "Boston, MA, Massachusetts");
        b.append("<tr><td>Remote</td><td><select name=\"remote\" aria-label=\"remote\">");
        for (String[] o : new String[][]{{"", "not decided"}, {"fine", "remote counts"}, {"only", "remote only"}, {"no", "not remote"}})
            b.append("<option value=\"").append(o[0]).append("\"").append(o[0].equals(c.remote()) ? " selected" : "").append(">").append(o[1]).append("</option>");
        b.append("</select></td></tr>");
        field(b, "remote_where", "Remote, based in any of", c.remoteWhere(), "US, United States, North America");
        field(b, "prefer_titles", "First in the order: titles with", c.preferTitles(), "Engineering, Platform");
        field(b, "prefer_places", "First in the order: places", c.preferPlaces(), "Boston");
        b.append("</table><button>Save and sort again</button> <span class=\"k\">Words separated by commas. Saving sorts the openings already read at once; no new search is needed.</span></form>");
        b.append("<form method=\"post\" action=\"/career/openings\">").append(hidden("op", "remake")).append(hidden("seeker", s.slug()))
                .append("<button>Have the library set it again from what you want</button> <span class=\"k\">The model takes a minute.</span></form></details>");
        return b.toString();
    }

    private static void field(StringBuilder b, String name, String label, List<String> words, String example) {
        b.append("<tr><td>").append(label).append("</td><td><input name=\"").append(name).append("\" value=\"").append(Pages.esc(String.join(", ", words))).append("\" size=\"60\" placeholder=\"")
                .append(Pages.esc(example)).append("\" aria-label=\"").append(Pages.esc(label.toLowerCase())).append("\"></td></tr>");
    }

    static String companies(LibraryStore store, View v, Career.Seeker s, boolean writer) {
        List<ObjectNode> with = new ArrayList<>(), none = new ArrayList<>(), unread = new ArrayList<>(), dropped = new ArrayList<>();
        Map<String, Integer> passAt = new HashMap<>();
        for (Row r : v.passing()) { String listed = r.kept() == null ? "" : r.kept().path("listed").asText(""); if (!listed.isEmpty()) passAt.merge(CareerSearch.key(listed), 1, Integer::sum); }
        for (ObjectNode c : v.companies()) {
            String st = c.path("state").asText("");
            if (st.equals("out") || st.equals("gone")) continue;   // gone: gave way, nothing of its passing; it comes back when an opening of its does
            if (st.equals("dropped")) dropped.add(c);
            else if (!c.has("feed")) unread.add(c);
            else if (passAt.getOrDefault(CareerSearch.key(c.path("name").asText()), 0) > 0) with.add(c);
            else none.add(c);
        }
        with.sort((a, b) -> passAt.getOrDefault(CareerSearch.key(b.path("name").asText()), 0) - passAt.getOrDefault(CareerSearch.key(a.path("name").asText()), 0));
        StringBuilder b = new StringBuilder("<h2>Employers on the list</h2><p class=\"k\">Kept: every search looks at it, and the nightly watch reads it. Dropped: no search shows it again. ")
                .append("Keep out: it is never shown again, and it joins what you rule out. A card is a short dossier the library researches on an employer, what it does, how many people, its stage, who leads it, its news, ")
                .append("filed for the employers with openings that fit after each search, ").append(CareerCompany.cardsPerRun()).append(" at a time; the full dossier is the deep run, for one you are serious about. Fit is read with the card beside the posting.</p>");
        if (!with.isEmpty()) b.append(table(store, "With openings that fit", with, passAt, s, writer));
        if (!none.isEmpty()) b.append(table(store, "None of their openings fit", none, passAt, s, writer));
        if (!unread.isEmpty()) b.append(table(store, "Their openings could not be read", unread, passAt, s, writer));
        if (!dropped.isEmpty()) b.append("<details><summary>Dropped <span class=\"k\">").append(dropped.size()).append("</span></summary>").append(table(store, "", dropped, passAt, s, writer)).append("</details>");
        if (writer) b.append("<form method=\"post\" action=\"/career/openings\" class=\"inline\">").append(hidden("op", "company")).append(hidden("seeker", s.slug())).append(hidden("state", "kept"))
                .append("<input name=\"name\" placeholder=\"an employer to keep on the list\" aria-label=\"an employer to keep on the list\"> <button>Keep</button></form>");
        return b.toString();
    }

    private static String table(LibraryStore store, String title, List<ObjectNode> cs, Map<String, Integer> passAt, Career.Seeker s, boolean writer) {
        StringBuilder b = new StringBuilder(title.isEmpty() ? "" : "<h3>" + title + "</h3>");
        b.append("<table><tr><th>Employer</th><th>Why it is on the list</th><th>Openings</th><th>Fit</th><th>Read from</th><th>Its dossier</th>").append(writer ? "<th></th>" : "").append("</tr>");
        for (ObjectNode c : cs) {
            String name = c.path("name").asText(), st = c.path("state").asText("");
            b.append("<tr><td>").append(Pages.esc(name)).append(st.equals("kept") ? " <span class=\"badge\">kept</span>" : "").append("</td><td>")
                    .append(Pages.esc(c.path("why").asText(""))).append(c.path("by").asText("").equals("library") ? " <span class=\"k\">(the library)</span>" : "").append("</td><td>")
                    .append(c.has("read_at") ? c.path("openings").asInt(0) : "").append("</td><td>").append(passAt.getOrDefault(CareerSearch.key(name), 0)).append("</td><td class=\"k\">");
            if (c.has("people")) b.append(Pages.esc(Career.cap(new CareerSize.People(c.path("people").path("low").asInt(), c.path("people").path("high").asInt(), "", "").words()))).append(", by ").append(Pages.esc(c.path("people").path("source").asText(""))).append(", read ").append(Pages.esc(c.path("people").path("at").asText(""))).append(". ");
            if (!c.path("stage").asText("").isEmpty()) b.append(Pages.esc(Career.cap(c.path("stage").asText()))).append(", by ").append(Pages.esc(c.path("stage_by").asText(""))).append(". ");
            if (c.has("feed")) b.append(Pages.esc(c.path("feed").path("system").asText(""))).append(", ").append(Pages.esc(c.path("feed").path("how").asText("")));
            else if (!c.path("note").asText("").isBlank()) b.append(Pages.esc(Career.cap(c.path("note").asText(""))));
            else b.append("not read yet");
            if (!c.path("note").asText("").isBlank() && c.has("feed")) b.append(". ").append(Pages.esc(c.path("note").asText("")));
            b.append(c.has("read_at") ? " Read " + Pages.esc(c.path("read_at").asText("")) + "." : "").append("</td>");
            String cardState = CareerCompany.state(store, c), needs = CareerCompany.needs(c);
            b.append("<td class=\"k\">").append(cardState.isEmpty() ? "none yet" : Pages.esc(cardState)).append(needs.isEmpty() || needs.startsWith("The ") || needs.startsWith("No card") ? "" : ". <b>" + Pages.esc(needs) + "</b>");
            if (CareerCompany.onShelves(c)) b.append(" · <a href=\"/dossier?subject=").append(Pages.enc(name)).append("\">open</a>");
            if (writer) {
                if (!cardState.startsWith("card filed") && !cardState.startsWith("dossier filed")) b.append(" ").append(cardForm(s, name, false, CareerCompany.onShelves(c) ? "Card again" : "Build its card"));
                if (!cardState.startsWith("dossier")) b.append(" ").append(cardForm(s, name, true, "Full dossier"));
            }
            b.append("</td>");
            if (writer) {
                b.append("<td>");
                if (!st.equals("kept")) b.append(state(s, name, "kept", "Keep"));
                if (!st.equals("dropped")) b.append(state(s, name, "dropped", "Drop"));
                if (!st.isEmpty()) b.append(state(s, name, "", "Undo"));
                b.append("<form method=\"post\" action=\"/career/openings\" class=\"inline\">").append(hidden("op", "keep-out")).append(hidden("seeker", s.slug())).append(hidden("name", name))
                        .append("<button class=\"lnk\">Keep out</button></form></td>");
            }
            b.append("</tr>");
        }
        return b.append("</table>").toString();
    }

    private static String cardForm(Career.Seeker s, String name, boolean full, String label) {
        return "<form method=\"post\" action=\"/career/openings\" class=\"inline\">" + hidden("op", full ? "dossier" : "card") + hidden("seeker", s.slug()) + hidden("name", name) + "<button class=\"lnk\">" + label + "</button></form>";
    }

    private static String state(Career.Seeker s, String name, String st, String label) {
        return "<form method=\"post\" action=\"/career/openings\" class=\"inline\">" + hidden("op", "company") + hidden("seeker", s.slug()) + hidden("name", name) + hidden("state", st)
                + "<button class=\"lnk\">" + label + "</button></form>";
    }

    /** The search's button with the box for a change for this search only. */
    private static String searchForm(Career.Seeker s, String label) {
        return "<form method=\"post\" action=\"/career/openings\" class=\"inline\">" + hidden("op", "search") + hidden("seeker", s.slug()) + "<button>" + Pages.esc(label) + "</button> "
                + "<input name=\"vary\" size=\"44\" placeholder=\"a change for this search only, e.g. without the pets field, and New Hampshire as well\" aria-label=\"a change for this search only\"></form>";
    }

    private static String button(Career.Seeker s, String op, String label) {
        return "<form method=\"post\" action=\"/career/openings\" class=\"inline\">" + hidden("op", op) + hidden("seeker", s.slug()) + "<button>" + Pages.esc(label) + "</button></form>";
    }

    static String hidden(String name, String value) { return "<input type=\"hidden\" name=\"" + name + "\" value=\"" + Pages.esc(value) + "\">"; }
}
