package org.researchzosho.librarian;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.TreeSet;
import java.util.HashMap;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.researchzosho.librarian.Pages.enc;
import static org.researchzosho.librarian.Pages.esc;
import static org.researchzosho.librarian.Pages.n;
import static org.researchzosho.librarian.Pages.t;
import static org.researchzosho.librarian.Pages.tc;

/**
 * The dossier page (0.6.6): the target package the person reads, rendered from what the shelves hold ({@link Dossier#gather}) and from the
 * latest report on the subject, in the order the mock laid out: the identity card, the Reading for the question, coverage, the timeline,
 * the relations, what was told, the claims by the class of their source, the story and the reports to dive into, what was looked for and
 * not found, the open questions, the conflicts, the checks, the linked subjects. A section the library cannot fill yet says so in a line,
 * so the shape of the whole package is on the page before every part of it is built.
 */
final class DossierPage {
    private DossierPage() { }

    static final String CSS = "<style>"
            + ".dz h2{border:0;font:600 .85em system-ui,sans-serif;letter-spacing:.06em;text-transform:uppercase;color:var(--k);margin:1.6em 0 .5em}"
            + ".dz .sec{background:var(--card);border:1px solid var(--line);border-radius:8px;padding:.8em 1em;margin:.8em 0}"
            + ".dz .id{display:grid;grid-template-columns:1fr auto;gap:1em}.dz .id .facts{font:.88em/1.7 system-ui,sans-serif;color:var(--k)}.dz .id .facts b{color:var(--ink)}"
            + ".dz .q{background:var(--bg);border:1px solid var(--line);border-radius:6px;padding:.6em .8em;font-size:.95em;max-width:24em}.dz .q .lean{font:.75em/1 system-ui,sans-serif;color:var(--accent);text-transform:uppercase;letter-spacing:.08em}"
            + ".dz .chips{display:flex;flex-wrap:wrap;gap:.4em;font:.8em/1 system-ui,sans-serif}.dz .chip{border:1px solid var(--line);border-radius:1em;padding:.4em .7em;color:var(--k)}.dz .chip b{color:var(--ink)}.dz .chip.none{border-style:dashed}"
            + ".dz .cls{display:inline-block;font:.68em/1 system-ui,sans-serif;letter-spacing:.06em;text-transform:uppercase;padding:.25em .45em;border-radius:3px;color:#fff;vertical-align:middle;margin-left:.4em}"
            + ".dz .c-public{background:#2c6e49}.dz .c-own{background:#1f4e79}.dz .c-third{background:#8a6d00}.dz .c-supplied{background:#6b3fa0}.dz .c-told{background:#4a5d8f}.dz .c-canon{background:#a1402f}"
            + ".dz .tl{list-style:none;margin:0;padding:0}.dz .tl li{display:grid;grid-template-columns:5em 1fr;gap:.6em;padding:.4em 0;border-top:1px solid var(--line)}.dz .tl li:first-child{border-top:0}.dz .tl .y{font:.88em/1.6 system-ui,sans-serif;color:var(--k)}"
            + ".dz .date{font:.78em/1 system-ui,sans-serif;color:var(--k);margin-left:.4em}.dz .note{font:.85em/1.5 system-ui,sans-serif;color:var(--k)}.dz .later{font:.85em/1.5 system-ui,sans-serif;color:var(--k);font-style:italic}"
            + ".dz ul.plain{padding-left:1.2em}.dz ul.plain li{margin:.25em 0}.dz nav.in{font:.85em/1.8 system-ui,sans-serif;margin:.4em 0 1em}.dz nav.in a{margin-right:.9em;color:var(--k)}.dz .told{border-left:3px solid var(--line);padding:.2em .7em;margin:.4em 0}"
            + ".dz table{width:100%;border-collapse:collapse;font-size:.92em}.dz th{text-align:left;font:600 .8em system-ui,sans-serif;color:var(--k);padding:.3em .5em .3em 0;border-bottom:1px solid var(--line)}.dz td{padding:.35em .5em .35em 0;border-bottom:1px solid var(--line);vertical-align:top}"
            + ".dz .media{display:grid;grid-template-columns:repeat(auto-fill,minmax(16em,1fr));gap:.8em}.dz .media .item{border:1px solid var(--line);border-radius:6px;padding:.5em;font-size:.9em}.dz .media img{max-width:100%;height:auto;border-radius:4px;margin-bottom:.4em}"
            + ".dz ul.bars{list-style:none;margin:0;padding:0}.dz ul.bars li{display:grid;grid-template-columns:3.5em 1fr 2.5em;align-items:center;gap:.5em;padding:.15em 0}.dz ul.bars .bar{display:block;height:.7em;background:var(--accent);border-radius:2px;opacity:.75}.dz ul.bars .n{font:.8em/1 system-ui,sans-serif;color:var(--k)}"
            + "@media (max-width:40em){.dz .id{grid-template-columns:1fr}.dz .q{max-width:none}}"
            + "</style>";

    /** The slug of the standing search a dossier's Watch keeps: "dossier-<subject>". */
    static String watchSlug(String subject) { return "dossier-" + subject.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", "-").replaceAll("^-|-$", ""); }

    static final Pattern CITE = Pattern.compile("\\[(\\d+)\\]");
    /** Every [n] in a rendered section links to what it cites, from the report the section came from: the source's address, with its title as the tooltip; a reference with no address links to the report's own list. */
    static String linkCites(String html, Investigation report) {
        if (report == null || html == null) return html;
        Map<String, String[]> refs = Dossier.refsOf(report.body());
        if (refs.isEmpty()) return html;
        Matcher m = CITE.matcher(html); StringBuilder b = new StringBuilder();
        while (m.find()) {
            String[] r = refs.get(m.group(1));
            if (r == null) { m.appendReplacement(b, Matcher.quoteReplacement(m.group())); continue; }
            String href = r[0].isEmpty() ? "/entry/" + enc(report.id()) + "#ref-" + m.group(1) : r[0].startsWith("http") ? r[0] : "/read?locator=" + enc(r[0]);
            m.appendReplacement(b, Matcher.quoteReplacement("<a class=\"cite\" href=\"" + esc(href) + "\"" + (r[1].isEmpty() ? "" : " title=\"" + esc(r[1]) + "\"") + ">[" + m.group(1) + "]</a>"));
        }
        m.appendTail(b);
        return b.toString();
    }

    /** A Wikimedia Commons file as an address names it ("…/wiki/File:Name.jpg") or as a line names it ("File:Neal Stephenson 2008.jpg, Wikimedia Commons"): the name runs to its extension, spaces and all. */
    static final Pattern COMMONS_FILE = Pattern.compile("(?:commons\\.wikimedia\\.org/wiki/(?:File:|File%3A)|\\bFile:)([^\\[\\]\"<>\\n]+?\\.(?:[Jj][Pp][Ee]?[Gg]|[Pp][Nn][Gg]|[Gg][Ii][Ff]|[Ss][Vv][Gg]|[Tt][Ii][Ff]{1,2}|[Ww][Ee][Bb][Pp]|[Pp][Dd][Ff]|[Oo][Gg][Vv]|[Ww][Ee][Bb][Mm]))\\b");
    static final Pattern YOUTUBE_ID = Pattern.compile("(?:youtube\\.com/watch\\?(?:[^\\s]*&)?v=|youtu\\.be/)([A-Za-z0-9_-]{11})");
    /** A thumbnail for a media record when its address gives one: a Wikimedia Commons file by its name, a YouTube video by its id; "" otherwise. {@code titles}: what the reports' reference lists call each address, where a Commons file's name stands when the claim's own line does not carry it. */
    static String thumb(Finding f, Map<String, String> titles) {
        StringBuilder where = new StringBuilder(f.body());
        for (Finding.Source s : f.sources()) { where.append(' ').append(s.locator()); String t = titles.get(s.locator()); if (t != null) where.append(' ').append(t); }
        Matcher c = COMMONS_FILE.matcher(where);
        if (c.find()) {
            String name = URLDecoder.decode(c.group(1).replaceAll("[.,;:)]+$", "").replace("+", "%20"), StandardCharsets.UTF_8).replace(' ', '_');   // the sentence's own full stop is not part of the name
            return "https://commons.wikimedia.org/wiki/Special:FilePath/" + enc(name).replace("%2F", "/") + "?width=240";
        }
        Matcher y = YOUTUBE_ID.matcher(where);
        return y.find() ? "https://img.youtube.com/vi/" + y.group(1) + "/mqdefault.jpg" : "";
    }

    static final Pattern PERSON_LEAD = Pattern.compile("(?:<b>([^<]{3,80}?)</b>|(?<=^|<p>|<br>|<li>)([A-Z][^<—:(\\n]{2,70}?))(?=\\s*(?:—|:|\\())");
    static final Set<String> NOT_A_PERSON = Set.of("board", "directors", "committee", "team", "group", "staff", "management", "officers", "partners", "investors", "founders", "family", "office", "council", "trustees", "members", "employees", "executives", "leadership");
    /** The people a People section names, each a link in the rendered html: to their dossier when the shelves hold one, else to the run that starts it. A name is a bold lead, or a line's lead, before its dash, colon or bracket; a credential after the name is not part of it; a board or a committee is not a person. */
    static String peopleLinks(String html, Set<String> dossierSubjects) {
        Matcher m = PERSON_LEAD.matcher(html); StringBuilder b = new StringBuilder();
        while (m.find()) {
            boolean bold = m.group(1) != null;
            String raw = (bold ? m.group(1) : m.group(2)).strip();
            String name = raw.replaceAll(",\\s*(?:MD|PhD|Ph\\.D\\.|MBA|JD|MPH|CFA|CFP|CPA|FACS|MACP|DrPH|RN|DDS|Esq\\.?)(?:,\\s*[A-Z][A-Za-z.]+)*$", "").strip();
            String[] words = name.split("\\s+");
            boolean person = words.length >= 2 && words.length <= 6 && !CrossLinks.STOP.contains(name);
            for (String w : words) if (NOT_A_PERSON.contains(w.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", ""))) person = false;
            if (!person) { m.appendReplacement(b, Matcher.quoteReplacement(m.group())); continue; }
            String key = name.toLowerCase(Locale.ROOT).replaceFirst("^the ", "");
            String link = dossierSubjects.contains(key) ? "<a href=\"/dossier?subject=" + enc(name) + "\">" + raw + "</a>" : raw + " (<a class=\"k\" href=\"/research?q=" + enc("Who is " + name + "? A full dossier.") + "&amp;field=person\">" + t("start a dossier") + "</a>)";
            m.appendReplacement(b, Matcher.quoteReplacement(bold ? "<b>" + link + "</b>" : link));
        }
        m.appendTail(b);
        return b.toString();
    }

    static String render(LibraryStore store, String subject) throws IOException { return render(store, subject, Translate.AS_IS, null); }

    /** The page with its forms for a writer: tell, write the story, score a forecast (the page showed and could not take them until 2026-11-07). */
    static String render(LibraryStore store, String subject, boolean writer) throws IOException {
        WRITER.set(writer);
        try { return render(store, subject, Translate.AS_IS, null); } finally { WRITER.remove(); }
    }

    /** Whether the page being rendered carries a writer's forms. */
    private static final ThreadLocal<Boolean> WRITER = new ThreadLocal<>();
    private static boolean writer() { return Boolean.TRUE.equals(WRITER.get()); }

    /**
     * What the timeline holds and where it came from: the runs behind it by their questions, the kinds of claim on it, and whether the founding
     * and the basics are there yet. "From 1 run: Corporate history of … who holds power. Milestones, decisions, statements and media; the
     * founding and the basics come with the full research." A timeline that starts in 2001 on a university founded in 1636 says so.
     */
    static String timelineLine(Dossier.Gathered d) {
        List<String> asks = new ArrayList<>();
        String storyTitle = "The story of " + d.label();
        for (Investigation inv : d.reports()) if (!inv.title().equals(storyTitle)) asks.add(Acquisitions.compress(inv.title(), 70).replaceAll("[.?!]+$", ""));
        String from = asks.isEmpty() ? t("From no run yet: the claims the shelves hold on the subject.")
                : asks.size() == 1 ? t("From 1 run: {0}.", asks.get(0)) : t("From {0} runs: {1}.", asks.size(), String.join("; ", asks));   // the runs' own questions, as they were asked
        boolean milestones = !Dossier.milestones(d.claims()).isEmpty();
        return from + " " + (milestones ? t("Milestones, decisions, statements and media, the milestones first within a year.") : t("Milestones, decisions, statements and media; the founding and the basics come with the full research."));
    }

    /**
     * The page, built with {@code w}: every piece of prose the reports and the claims give passes through it, the page's own labels,
     * the names, the ids, the dates and the badges do not. {@code inLang} names the language the page is read in, or is null for the original.
     */
    static String render(LibraryStore store, String subject, Translate.Words w, String inLang) throws IOException {
        StringBuilder b = new StringBuilder();
        b.append("<form class=\"big\" action=\"/dossier\"><input name=\"subject\" value=\"").append(esc(subject)).append("\" placeholder=\"").append(esc(t("A person or an organisation…"))).append("\"").append(subject.isEmpty() ? " autofocus" : "").append("> <button>").append(t("Dossier")).append("</button></form>");
        if (subject.isEmpty()) {   // the front door: every dossier the shelves hold
            b.append("<p class=\"k\">").append(t("Everything the library holds on one subject, as one page: who they are, the Reading for your question, what was searched, the timeline, the relations, what was told, the claims by the class of their source, the story and the reports to dive into, the open questions.")).append("</p>");
            List<Dossiers.Entry> all = Dossiers.list(store);
            b.append("<p class=\"k\">").append(t("To request a dossier on someone new, type the name above and open it: the page offers one button that files the research.")).append("</p>");
            if (all.isEmpty()) { b.append("<p>").append(t("No dossiers yet.")).append("</p>"); return b.toString(); }
            b.append(CSS).append("<div class=\"dz\"><h2 id=\"all\">").append(t("The dossiers on the shelves")).append("</h2><div class=\"sec\"><table><tr><th>").append(t("Subject")).append("</th><th>").append(t("Kind")).append("</th><th>").append(t("Claims")).append("</th><th>").append(t("Reports")).append("</th><th>").append(t("Last run")).append("</th><th>").append(t("Story")).append("</th></tr>");
            for (Dossiers.Entry e : all)
                b.append("<tr><td><a href=\"/dossier?subject=").append(enc(e.subject())).append("\">").append(esc(e.subject())).append("</a></td><td>").append(esc(e.kind().isEmpty() ? "" : t(e.kind()))).append("</td><td>").append(e.claims())
                 .append("</td><td>").append(e.reports()).append("</td><td>").append(esc(Chrome.now().day(e.lastRun()))).append("</td><td>").append(e.story() ? t("written") : "").append("</td></tr>");
            b.append("</table></div></div>");
            return b.toString();
        }
        Dossier.Gathered d = Dossier.gather(store, subject);
        b.append(CSS).append("<div class=\"dz\">");
        if (inLang != null) b.append("<p class=\"k\">").append(t("Read in {0}: the library's own dossier of {1}, each piece of prose translated by the model. The cites, the claim ids, the dates and the badges stand as they are; a piece whose translation would have lost one is kept in the original.",
                esc(Chrome.now().languageName(inLang)), "<a href=\"/dossier?subject=" + enc(d.label()) + "\">" + esc(d.label()) + "</a>")).append("</p>");
        if (d.node() == null && d.claims().isEmpty() && d.reports().isEmpty()) {
            b.append("<p>").append(t("The library holds nothing on {0} yet. A research run starts the dossier: it searches the open sources for who they are, what they do and what was written of them, and the page fills as the claims arrive.", "<b>" + esc(subject) + "</b>")).append("</p>")
             .append("<form method=\"post\" action=\"/dossier\" class=\"inline\"><input type=\"hidden\" name=\"subject\" value=\"").append(esc(subject)).append("\"><button name=\"request\" value=\"1\">").append(t("Request the dossier")).append("</button></form></div>");
            return b.toString();
        }
        Investigation latest = null; Investigation story = null;
        String storyTitle = "The story of " + d.label();
        for (Investigation inv : d.reports()) { if (inv.title().equals(storyTitle)) story = inv; else latest = inv; }
        String body = latest == null ? "" : latest.body();
        Map<SourceClass, List<Finding>> byClass = d.byClass();
        // in-page navigation
        b.append("<nav class=\"in\">");
        for (String[] a : new String[][]{{"identity", t("Identity")}, {"reading", tc("Reading", READING)}, {"work", t("What they do")}, {"coverage", t("Coverage")}, {"timeline", t("Timeline")}, {"decisions", t("Decisions")}, {"said", t("In their own words")}, {"media", t("Media")}, {"facets", t("Facets")}, {"pattern", t("Pattern of life")}, {"relations", t("Relations")}, {"told", tc("Told", SECTION)}, {"claims", t("Claims")}, {"analysis", t("Analysis")}, {"story", t("Story")}, {"reports", t("Reports")}, {"notfound", tc("Not found", SECTION)}, {"questions", t("Open questions")}, {"conflicts", t("Conflicts")}, {"checks", t("Checks")}, {"linked", t("Linked")}, {"shared", t("Shared")}, {"refresh", t("Refresh")}, {"later", tc("Not yet", SECTION)}})
            b.append("<a href=\"#").append(a[0]).append("\">").append(a[1]).append("</a>");
        b.append("</nav>");
        // the ask for more: a research run with the whole brief, never automatic; a card made for the career search grows into the full dossier here
        String fullQ = d.kind().equalsIgnoreCase("person") ? "Who is " + d.label() + "? A full dossier." : "Who is " + d.label() + "? A full dossier: its history, power and governance, customers and rivals, money, people, decisions and what to expect.";
        String full = "<a href=\"/research?q=" + enc(fullQ) + "\">" + t("Research in full") + "</a>";
        b.append("<p class=\"note\">").append(d.claims().size() == 1 ? t("1 claim on the shelves. {0}: a run with the whole brief, hours on a local model, only when you ask.", full) : t("{0} claims on the shelves. {1}: a run with the whole brief, hours on a local model, only when you ask.", d.claims().size(), full)).append("</p>");
        b.append(Pages.saveLinks("/download?subject=" + enc(d.label()), inLang));
        b.append(madeFold(store, d, storyTitle));
        // identity
        b.append("<h2 id=\"identity\">").append(t("Identity")).append("</h2><div class=\"sec id\"><div><div class=\"facts\">");
        b.append("<b>").append(esc(d.label())).append("</b>");
        if (!d.kind().isEmpty()) b.append(" · ").append(esc(MapPage.KINDS.contains(d.kind()) ? MapPage.kindSaid(d.kind()) : t(d.kind())));   // a name's kind on the map, or a field's (person, organization)
        if (d.node() != null && !d.node().aliases().isEmpty()) b.append("<br><b>").append(t("Also written")).append("</b> ").append(esc(String.join(" · ", d.node().aliases())));
        if (d.node() != null && d.node().wikidata() != null && !d.node().wikidata().isBlank()) b.append("<br><b>Wikidata</b> ").append(esc(d.node().wikidata()));
        b.append("<br><b>").append(t("Claims")).append("</b> ").append(d.claims().size()); if (!byClass.isEmpty()) { List<String> parts = new ArrayList<>(); for (var e : byClass.entrySet()) parts.add(classSaid(e.getKey()) + " " + e.getValue().size()); b.append(" (").append(esc(String.join(Chrome.now().sep(), parts))).append(")"); }
        b.append(" · <b>").append(t("Reports")).append("</b> ").append(d.reports().size()).append(" · <b>").append(t("Open questions")).append("</b> ").append(d.questions().size());
        String own = Translate.languageOf(body);   // read in another language: the library's own dossier, its pieces translated by the model and kept until the dossier changes
        b.append("<br><b>").append(t("Read in")).append("</b> ");
        if (inLang != null) b.append("<a href=\"/dossier?subject=").append(enc(d.label())).append("\">").append(t("the original")).append("</a> ");
        for (String l : Chrome.available()) if (!l.equals(own) && !l.equals(inLang)) b.append("<a href=\"/dossier?subject=").append(enc(d.label())).append("&amp;in=").append(l).append("\" lang=\"").append(l).append("\">").append(esc(Chrome.nativeName(l))).append("</a> ");   // every language the pages are written in, by its own name
        b.append("<span class=\"note\">").append(inLang == null ? t("(translated by the model, the claims in their own language)") : t("(this page is read in {0}: the pieces translated by the model, the cites, the ids and the dates as they stand)", esc(Chrome.now().languageName(inLang)))).append("</span>");
        String identity = section(body, "Identity");
        if (!identity.isEmpty()) b.append("<br><b>").append(t("How resolved")).append("</b> ").append(esc(clip(w.tr(identity), 600)));
        b.append("</div></div>");
        if (latest != null) b.append("<div class=\"q\"><div class=\"lean\">").append(t("Question of the latest run")).append("</div>").append(esc(w.tr(latest.title()))).append("<div class=\"date\">").append(esc(Chrome.now().day(day(latest.recordedAt())))).append(" · <a href=\"/entry/").append(enc(latest.id())).append("\">").append(t("the report")).append("</a></div></div>");
        b.append("</div>");
        // reading
        b.append("<h2 id=\"reading\">").append(t("Reading, for that question")).append("</h2><div class=\"sec\">");
        String reading = section(body, "Reading"); if (reading.isEmpty()) reading = section(body, "Answer"); if (reading.isEmpty()) reading = sectionStarting(body, "Answer (");
        b.append(reading.isEmpty() ? "<p class=\"note\">" + t("No report yet; the Reading is written for the question a run is asked.") + "</p>" : linkCites(Pages.md(w.tr(reading)), latest)).append("</div>");
        // what they do: the work, from the latest report that has it, whatever the latest question leaned on
        b.append("<h2 id=\"work\">").append(t("What they do")).append("</h2><div class=\"sec\">");
        String work = Dossier.work(d.reports());
        b.append(work.isEmpty() ? "<p class=\"note\">" + t("No report describes the work yet; a run on the subject writes it: each principal product, project or programme, the flagship above all.") + "</p>" : linkCites(Pages.md(w.tr(work)), Dossier.reportOf(d.reports(), "What they do", "The work", "Material"))).append("</div>");
        // coverage
        b.append("<h2 id=\"coverage\">").append(t("Coverage")).append("</h2><div class=\"sec\"><div class=\"chips\">");
        List<String[]> cov = coverage(body); String never = notSearched(body);
        if (cov.isEmpty()) b.append("<span class=\"chip none\">").append(t("no collections searched yet")).append("</span>");
        for (String[] c : cov) b.append("<span class=\"chip\"><b>").append(esc(c[0])).append("</b> ").append(esc(c[1])).append("</span>");
        if (!never.isEmpty()) b.append("<span class=\"chip none\">").append(t("not searched: {0}", esc(clip(never, 160)))).append("</span>");
        b.append("</div><p class=\"note\">").append(t("Written by the library from what the searches of the latest run returned, not by the model.")).append("</p></div>");
        // timeline
        Map<String, List<Finding>> byYear = new TreeMap<>();
        for (Finding f : d.claims()) { String y = Dossier.yearOf(f); if (y != null) byYear.computeIfAbsent(y, k -> new ArrayList<>()).add(f); }
        b.append("<h2 id=\"timeline\">").append(t("Timeline")).append("</h2><div class=\"sec\">");
        b.append("<p class=\"k\">").append(esc(timelineLine(d))).append("</p>");
        if (byYear.isEmpty()) b.append("<p class=\"note\">").append(t("No claim names a year yet.")).append("</p>");
        else { b.append("<ul class=\"tl\">"); for (var e : byYear.entrySet()) for (Finding f : Dossier.milestonesFirst(e.getValue())) b.append("<li><span class=\"y\">").append(esc(e.getKey())).append("</span><span>").append(claim(f, w, d.own())).append("</span></li>"); b.append("</ul>"); }
        b.append("</div>");
        // decisions: what the people in power decided, dated, with the reason they gave
        b.append("<h2 id=\"decisions\">").append(t("Decisions")).append("</h2><div class=\"sec\">");
        List<Finding> decisions = Dossier.decisions(d.claims());
        if (decisions.isEmpty()) b.append("<p class=\"note\">").append(t("No decision on record yet: a run writes the Decisions of the people in power, dated, with the reason each gave, and the library files them here.")).append("</p>");
        else { b.append("<ul class=\"tl\">"); for (Finding f : decisions) b.append("<li><span class=\"y\">").append(esc(Dossier.yearOf(f) == null ? "" : Dossier.yearOf(f))).append("</span><span>").append(claim(f, w, d.own())).append("</span></li>"); b.append("</ul>"); }
        b.append("</div>");
        // in their own words: the statements, dated and placed
        b.append("<h2 id=\"said\">").append(t("In their own words")).append("</h2><div class=\"sec\">");
        List<Finding> said = Dossier.statements(d.claims());
        if (said.isEmpty()) b.append("<p class=\"note\">").append(t("No statement on record yet: a run writes what they said, dated and placed, from interviews, talks, posts and the press, and the library files each here.")).append("</p>");
        else { b.append("<ul class=\"tl\">"); for (Finding f : said) b.append("<li><span class=\"y\">").append(esc(Dossier.yearOf(f) == null ? "" : Dossier.yearOf(f))).append("</span><span>").append(claim(f, w, d.own())).append("</span></li>"); b.append("</ul>"); }
        b.append("</div>");
        // media: photographs, video and scans with their provenance, a thumbnail where the address gives one
        b.append("<h2 id=\"media\">").append(t("Media")).append("</h2><div class=\"sec\">");
        List<Finding> media = Dossier.media(d.claims());
        if (media.isEmpty()) b.append("<p class=\"note\">").append(t("No photograph, video or scan on record yet: a run looks in Wikimedia Commons, YouTube, the Internet Archive and the subject's own pages, and files each with who made it, when, where and under which licence.")).append("</p>");
        else {
            Map<String, String> titles = new LinkedHashMap<>();
            for (Investigation inv : d.reports()) for (var e : TimelineClaims.refTitles(inv.body()).entrySet()) titles.putIfAbsent(e.getKey(), e.getValue());
            b.append("<div class=\"media\">");
            for (Finding f : media) {
                String thumb = thumb(f, titles);
                b.append("<div class=\"item\">").append(thumb.isEmpty() ? "" : "<img src=\"" + esc(thumb) + "\" alt=\"\" loading=\"lazy\">").append("<div>").append(claim(f, w, d.own())).append("</div></div>");
            }
            b.append("</div>");
        }
        b.append("</div>");
        // the facets the reports carry: customers, peers and alternatives; power and governance; influences; money; places; signals
        b.append("<h2 id=\"facets\">").append(t("Facets")).append("</h2>");
        Set<String> dossierSubjects = new HashSet<>(); for (Dossiers.Entry e : Dossiers.list(store)) dossierSubjects.add(e.subject().toLowerCase(Locale.ROOT).replaceFirst("^the ", ""));
        int facetsShown = 0;
        for (String[] f : Dossier.FACETS) {
            String[] names = f[1].split("\\|"); String text = Dossier.latestSection(d.reports(), names);
            if (text.isEmpty()) continue;
            facetsShown++;
            String rendered = linkCites(Pages.md(w.tr(text)), Dossier.reportOf(d.reports(), names));
            if (f[0].equals("People")) rendered = peopleLinks(rendered, dossierSubjects);   // each person named leads to their dossier, or to the run that starts one
            b.append("<div class=\"sec\"><h3>").append(esc(tc(f[0], FACET))).append("</h3>").append(rendered).append("</div>");
        }
        if (facetsShown == 0) b.append("<div class=\"sec\"><p class=\"note\">").append(t("No facet yet: a run on the subject writes its customers, peers and alternatives, who holds power over it, its money, its places and its signals (for a person: influences, holdings).")).append("</p></div>");
        // the pattern of life: what the record holds by year, the places it names, the appearances — computed from the claims
        b.append("<h2 id=\"pattern\">").append(t("Pattern of life")).append("</h2><div class=\"sec\">");
        String pattern = PatternOfLife.html(PatternOfLife.of(d.claims()));
        b.append(pattern.isEmpty() ? "<p class=\"note\">" + t("No dated claim yet; the pattern is computed from the claims as they come.") + "</p>" : pattern + "<p class=\"note\">" + t("Computed from the claims, not written by a model: a place is listed because a claim says the subject was there.") + "</p>").append("</div>");
        // relations
        b.append("<h2 id=\"relations\">").append(t("Relations")).append("</h2><div class=\"sec\">");
        if (d.relations().isEmpty()) b.append("<p class=\"note\">").append(t("No typed relation yet: a claim with the shape person – relation – organisation makes one.")).append("</p>");
        else {
            b.append("<table><tr><th>").append(t("Relation")).append("</th><th>").append(t("With")).append("</th><th>").append(t("Claim")).append("</th></tr>");
            for (Graph.Edge e : d.relations()) {
                boolean out = d.node() != null && d.node().id().equals(e.from()); String otherId = out ? e.to() : e.from(); Graph.Node o = d.graph().node(otherId);
                String other = o == null ? otherId : o.label();
                b.append("<tr><td>").append(esc((out ? "" : "← ") + w.tr(e.predicate()))).append("</td><td><a href=\"/dossier?subject=").append(enc(other)).append("\">").append(esc(other)).append("</a>").append(o != null && !o.kind().isEmpty() ? " <span class=\"date\">" + esc(MapPage.kindSaid(o.kind())) + "</span>" : "").append(e.disputed() ? " <span class=\"badge disputed\">" + t("disputed") + "</span>" : "").append("</td><td><a href=\"/entry/").append(enc(e.findingId())).append("\">").append(esc(e.findingId())).append("</a></td></tr>");
            }
            b.append("</table>");
        }
        b.append("</div>");
        // told
        List<Finding> told = byClass.getOrDefault(SourceClass.told, List.of());
        b.append("<h2 id=\"told\">").append(tc("Told", SECTION)).append("</h2><div class=\"sec\">");
        if (told.isEmpty()) b.append("<p class=\"note\">").append(t("Nothing told yet. Tell the librarian what you know of {0} in the chat, or: {1}", esc(d.label()), "<code>researchzosho tell \"" + esc(d.label()) + "\" \"…\"</code>")).append("</p>");
        for (Finding f : told) b.append("<div class=\"told\">").append(esc(w.tr(f.body().strip()))).append(" <span class=\"date\">").append(esc(f.validAsOf())).append(", ").append(esc(f.writer().replaceFirst("^patron:", ""))).append("</span> <a href=\"/entry/").append(enc(f.id())).append("\">").append(esc(f.id())).append("</a></div>");
        if (writer()) b.append("<form method=\"post\" action=\"/dossier\" class=\"stack\"><input type=\"hidden\" name=\"subject\" value=\"").append(esc(d.label())).append("\"><label>").append(t("Tell the library what you know of {0}, in your own words", esc(d.label()))).append("<br><textarea name=\"text\" rows=\"3\" required></textarea></label><button name=\"tell\" value=\"1\">").append(t("File it as told")).append("</button> <span class=\"note\">").append(t("dated, with you as the source; the names, dates and events it raises become questions for the next run")).append("</span></form>");
        b.append("</div>");
        // claims by class
        b.append("<h2 id=\"claims\">").append(t("Claims by the class of their source")).append("</h2><div class=\"sec\">");
        for (SourceClass c : new SourceClass[]{SourceClass.publicRecord, SourceClass.ownPublication, SourceClass.supplied, SourceClass.thirdParty}) {
            List<Finding> fs = byClass.get(c); if (fs == null || fs.isEmpty()) continue;
            b.append("<p><b>").append(esc(classSaid(c))).append("</b> · ").append(fs.size()).append("</p><ul class=\"plain\">");
            for (Finding f : fs) b.append("<li>").append(claim(f, w, d.own())).append("</li>");
            b.append("</ul>");
        }
        b.append("<p class=\"note\">").append(t("The class is read from where each source lives, never from the model: a register, a court or a filing is public record; the subject's own site or account is own publication (an organisation's or a product's site whose address carries its name, as researchzosho.org is ResearchZosho's; a person's is not read from a name, since a namesake's would pass for theirs); a page you supplied is supplied; what you told the library is told; press and reporting are third-party.")).append("</p></div>");
        // story + reports
        // analysis: the layer apart from the facts, from the latest report, with the subject's forecast ledger
        b.append("<h2 id=\"analysis\">").append(t("Analysis")).append("</h2><div class=\"sec\">");
        String judgments = Analysis.section(body, "Judgments"), implications = Analysis.section(body, "Implications and options"), summary = Analysis.section(body, "Source summary");
        boolean libraryWroteSummary = summary.isEmpty(); if (libraryWroteSummary) summary = Analysis.sourceSummary(body);
        List<Analysis.Conjecture> conj = Analysis.conjectures(body); List<Forecasts.Entry> ledger = Forecasts.of(store, d.label());
        if (judgments.isEmpty() && conj.isEmpty() && ledger.isEmpty() && implications.isEmpty())
            b.append("<p class=\"note\">").append(t("No analysis layer yet: the next run on this subject writes one — what the facts add up to, the conjectures with their likelihood, the forecasts, the options.")).append("</p>");
        else {
            if (!judgments.isEmpty()) b.append("<h3>").append(t("Judgments")).append("</h3><p>").append(linkCites(esc(w.tr(judgments)), latest)).append("</p>");
            if (!conj.isEmpty()) {
                b.append("<h3>").append(t("Conjectures")).append("</h3><ul class=\"plain\">");
                for (Analysis.Conjecture c : conj) b.append("<li>").append(linkCites(esc(w.tr(c.text())), latest)).append(c.likelihood().isEmpty() ? "" : " <span class=\"chip\"><b>" + esc(c.likelihood()) + "</b>" + (c.percent() == null ? "" : ", " + c.percent() + "%") + "</span>")
                        .append(c.confidence().isEmpty() ? "" : " <span class=\"date\">" + t("confidence {0}", esc(c.confidence())) + "</span>").append(c.indicators().isEmpty() ? "" : "<br><span class=\"note\">" + t("Would change this: {0}", esc(w.tr(c.indicators()))) + "</span>").append("</li>");
                b.append("</ul>");
            }
            if (!ledger.isEmpty()) {
                b.append("<h3>").append(t("Forecasts")).append("</h3><table><tr><th>").append(t("Id")).append("</th><th>").append(tc("By", "a column of the forecasts: the date by which the forecast is to happen")).append("</th><th>").append(t("Forecast")).append("</th><th>").append(t("Likelihood")).append("</th><th>").append(t("Confidence")).append("</th><th>").append(t("Outcome")).append("</th></tr>");
                for (Forecasts.Entry f : ledger) b.append("<tr><td>").append(esc(f.id())).append("</td><td>").append(esc(f.by())).append("</td><td>").append(esc(w.tr(f.text()))).append("</td><td>").append(esc(f.likelihood()))
                        .append(f.percent() == null ? "" : " (" + f.percent() + "%)").append("</td><td>").append(esc(f.confidence())).append("</td><td>").append(f.outcome() == null || f.outcome().isEmpty() ? t("open") : esc(OUTCOMES.contains(f.outcome()) ? t(f.outcome().equals("did-not") ? "did not" : f.outcome()) : f.outcome()) + (f.scored() == null ? "" : ", " + esc(f.scored()))).append("</td></tr>");
                b.append("</table>");
                Double brier = Forecasts.brier(ledger);
                if (brier != null) b.append("<p class=\"note\">").append(t("Brier score over the scored forecasts: {0} (0 is perfect, 0.25 is coin-tossing).", Chrome.now().decimal(brier, 3))).append("</p>");
                if (writer()) {
                    for (Forecasts.Entry f : ledger) {
                        if (f.outcome() != null && !f.outcome().isEmpty()) continue;
                        b.append("<form method=\"post\" action=\"/dossier\" class=\"inline\"><input type=\"hidden\" name=\"subject\" value=\"").append(esc(d.label())).append("\"><input type=\"hidden\" name=\"score\" value=\"").append(esc(f.id())).append("\">").append(esc(f.id())).append(": <select name=\"outcome\"><option value=\"happened\">").append(t("happened")).append("</option><option value=\"did-not\">").append(t("did not")).append("</option><option value=\"void\">").append(t("void")).append("</option></select> <input name=\"note\" size=\"18\" placeholder=\"").append(esc(t("what settled it"))).append("\"> <button>").append(t("Score")).append("</button></form> ");
                    }
                }
                b.append("<p class=\"note\">").append(t("Score one once its date has passed, here or at the terminal: {0}.", "<code>researchzosho forecast &lt;id&gt; happened|did-not|void</code>")).append("</p>");
            }
            if (!implications.isEmpty()) b.append("<h3>").append(t("Implications and options")).append("</h3><p>").append(linkCites(esc(w.tr(implications)), latest)).append("</p>");
            if (!summary.isEmpty()) b.append("<h3>").append(t("Source summary")).append("</h3><p>").append(esc(w.tr(summary))).append(libraryWroteSummary ? " <span class=\"note\">" + t("(written by the library from the references and the checks)") + "</span>" : "").append("</p>");
            if (latest != null) b.append("<p class=\"note\">").append(t("From {0}, {1}. Conjectures and forecasts live here and on the open questions, never on the shelves as claims.", "<a href=\"/entry/" + enc(latest.id()) + "\">" + esc(latest.title()) + "</a>", esc(Chrome.now().day(day(latest.recordedAt()))))).append("</p>");
        }
        b.append("</div>");
        b.append("<h2 id=\"story\">").append(t("Story")).append("</h2><div class=\"sec\">");
        if (story != null) {
            int grown = Math.max(0, d.claims().size() - story.findings().size());
            String again = "<code>researchzosho story \"" + esc(d.label()) + "\"</code>", on = esc(Chrome.now().day(day(story.recordedAt())));
            b.append("<p><a href=\"/entry/").append(enc(story.id())).append("\">").append(esc(storyTitle)).append("</a> <span class=\"date\">").append(story.findings().size() == 1 ? t("written {0} from 1 claim", on) : t("written {0} from {1} claims", on, story.findings().size())).append("</span> ")
             .append(grown == 0 ? t("Chapter by chapter, current with the shelves.") : grown == 1 ? t("Chapter by chapter; the shelves hold 1 more claim now. The next run on this subject writes it again, or: {0}", again) : t("Chapter by chapter; the shelves hold {0} more claims now. The next run on this subject writes it again, or: {1}", grown, again)).append("</p>");
        }
        else b.append("<p class=\"note\">").append(t("Not written yet: {0} writes it from the claims, a decade a chapter.", "<code>researchzosho story \"" + esc(d.label()) + "\"</code>")).append("</p>");
        if (writer() && !d.claims().isEmpty()) b.append("<form method=\"post\" action=\"/dossier\" class=\"inline\"><input type=\"hidden\" name=\"subject\" value=\"").append(esc(d.label())).append("\"><button name=\"story\" value=\"1\">").append(story == null ? t("Write the story") : t("Write the story again")).append("</button> <span class=\"note\">").append(t("the library's model writes it, a minute or more")).append("</span></form>");
        b.append("</div>");
        b.append("<h2 id=\"reports\">").append(t("Reports to dive into")).append("</h2><div class=\"sec\">");
        if (d.reports().isEmpty()) b.append("<p class=\"note\">").append(t("None yet.")).append("</p>");
        else { b.append("<ul class=\"plain\">"); for (Investigation inv : d.reports()) if (!inv.title().equals(storyTitle)) b.append("<li><a href=\"/entry/").append(enc(inv.id())).append("\">").append(esc(w.tr(inv.title()))).append("</a> <span class=\"date\">").append(esc(Chrome.now().day(day(inv.recordedAt())))).append("</span><br><span class=\"note\">").append(esc(Dossier.answerOf(inv.body(), 260))).append("</span></li>"); b.append("</ul>"); }
        b.append("</div>");
        // not found, questions, conflicts, checks
        List<String> notFound = new ArrayList<>(); for (Investigation inv : d.reports()) notFound.addAll(Dossier.notFoundLines(inv.body()));
        b.append("<h2 id=\"notfound\">").append(t("Looked for and not found")).append("</h2><div class=\"sec\">");
        if (notFound.isEmpty()) b.append("<p class=\"note\">").append(t("Nothing recorded as searched and not found.")).append("</p>"); else { b.append("<ul class=\"plain\">"); for (String l : notFound) b.append("<li>").append(esc(w.tr(l))).append("</li>"); b.append("</ul>"); }
        b.append("</div>");
        b.append("<h2 id=\"questions\">").append(t("Open questions")).append("</h2><div class=\"sec\">");
        if (d.questions().isEmpty()) b.append("<p class=\"note\">").append(t("None open on this subject. What you tell the librarian can open some.")).append("</p>"); else { b.append("<ul class=\"plain\">"); for (Frontier.Line l : d.questions()) b.append("<li>").append(esc(w.tr(l.text()))).append(" <a class=\"k\" href=\"/research?q=").append(enc(l.text())).append("\">").append(t("research")).append("</a></li>"); b.append("</ul>"); }
        b.append("</div>");
        String conflicts = section(body, "Conflicts and uncertainty");
        b.append("<h2 id=\"conflicts\">").append(t("Conflicts and uncertainty")).append("</h2><div class=\"sec\">").append(conflicts.isEmpty() ? "<p class=\"note\">" + t("None recorded by the latest run.") + "</p>" : linkCites(Pages.md(w.tr(conflicts)), latest)).append("</div>");
        String cite = firstLine(section(body, "Cite-check")); String classes = byClassLine(body);
        b.append("<h2 id=\"checks\">").append(t("Checks")).append("</h2><div class=\"sec\"><div class=\"chips\">");
        if (!cite.isEmpty()) b.append("<span class=\"chip\"><b>").append(t("cite-check")).append("</b> ").append(esc(cite.replaceFirst("^cite-check: ", ""))).append("</span>");
        if (!classes.isEmpty()) b.append("<span class=\"chip\"><b>").append(t("references by class")).append("</b> ").append(esc(classes)).append("</span>");
        if (cite.isEmpty() && classes.isEmpty()) b.append("<span class=\"chip none\">").append(t("no checks yet")).append("</span>");
        b.append("</div><p class=\"note\">").append(t("Mechanical: every cited sentence of the latest report read against its source; a sentence the source does not support is marked in the text, not deleted.")).append("</p></div>");
        // linked
        b.append("<h2 id=\"linked\">").append(t("Linked subjects")).append("</h2><div class=\"sec\">");
        Map<String, Integer> seen = new LinkedHashMap<>();
        for (Graph.Edge e : d.relations()) { String other = d.node() != null && d.node().id().equals(e.from()) ? e.to() : e.from(); seen.merge(other, 1, Integer::sum); }
        int shown = 0;
        if (!seen.isEmpty()) {
            b.append("<table><tr><th>").append(t("Subject")).append("</th><th>").append(t("Kind")).append("</th><th>").append(t("Of its own")).append("</th><th></th></tr>");
            for (var e : seen.entrySet()) { Graph.Node o = d.graph().node(e.getKey()); if (o == null || o.label().matches("^\\d[\\d\\s.,–-]*(?:\\s*to\\s*\\d[\\d\\s.,–-]*)?$")) continue; shown++;
                b.append("<tr><td><a href=\"/dossier?subject=").append(enc(o.label())).append("\">").append(esc(o.label())).append("</a></td><td>").append(esc(MapPage.kindSaid(o.kind()))).append("</td><td>").append(n(o.degree(), "{0} relation", "{0} relations")).append("</td><td><a class=\"k\" href=\"/research?q=").append(enc("Who or what is " + o.label() + "?")).append("\">").append(t("research")).append("</a></td></tr>"); }
            b.append("</table>");
        }
        if (shown == 0) b.append("<p class=\"note\">").append(t("No linked subject yet.")).append("</p>");
        b.append("</div>");
        // shared names: the links across dossiers, each side cited, the connection a question
        List<CrossLinks.Link> links = CrossLinks.of(store, d.label());
        b.append("<h2 id=\"shared\">").append(t("Shared with other dossiers")).append("</h2><div class=\"sec\">");
        if (links.isEmpty()) b.append("<p class=\"note\">").append(t("No name this subject's claims share with another dossier's yet.")).append("</p>");
        else {
            b.append("<table><tr><th>").append(t("Name")).append("</th><th>").append(t("Also in the dossier of")).append("</th><th>").append(tc("Here", "a column: this dossier's claims that name it")).append("</th><th>").append(tc("There", "a column: the other dossier's claims that name it")).append("</th></tr>");
            for (CrossLinks.Link l : links) {
                b.append("<tr><td><b>").append(esc(l.name())).append("</b></td><td><a href=\"/dossier?subject=").append(enc(l.other())).append("\">").append(esc(l.other())).append("</a></td><td>");
                for (String id : l.ownClaims()) b.append("<a class=\"k\" href=\"/entry/").append(enc(id)).append("\">").append(esc(id.substring(0, Math.min(6, id.length())))).append("</a> ");
                b.append("</td><td>");
                for (String id : l.otherClaims()) b.append("<a class=\"k\" href=\"/entry/").append(enc(id)).append("\">").append(esc(id.substring(0, Math.min(6, id.length())))).append("</a> ");
                b.append("</td></tr>");
            }
            b.append("</table><p class=\"note\">").append(t("What connects them is a question until a run or you answer it: each link is on the open questions once the report that made it landed, with a research link.")).append("</p>");
        }
        b.append("</div>");
        // refresh and watch: the latest question again, or a standing search on the subject
        b.append("<h2 id=\"refresh\">").append(t("Refresh and watch")).append("</h2><div class=\"sec\">");
        String field = latest == null ? "" : Fields.ofRun(store, latest.id()).stream().filter(x -> x.equals("person") || x.equals("organization")).findFirst().orElse("");
        if (latest != null) b.append("<p>").append(t("Refresh: {0}; the landing rewrites this page.", "<a href=\"/research?q=" + enc(latest.title()) + (field.isEmpty() ? "" : "&field=" + enc(field)) + "\">" + t("research the latest question again") + "</a>")).append("</p>");
        Serials.Shelf watched = null; for (Serials.Shelf sh : Serials.shelves(store)) if (sh.query().equalsIgnoreCase(d.label()) || sh.slug().equals(watchSlug(d.label()))) watched = sh;
        if (watched != null) {
            boolean unchecked = watched.lastChecked() == null || watched.lastChecked().equals("-"), daily = watched.everyDays() == 1;
            String last = unchecked ? "" : esc(Chrome.now().day(watched.lastChecked()));
            b.append("<p>").append(unchecked ? (daily ? t("Watched: a search for the subject every day; its arrivals land in the inbox.") : t("Watched: a search for the subject every {0} days; its arrivals land in the inbox.", watched.everyDays()))
                    : daily ? t("Watched: a search for the subject every day, last {0}; its arrivals land in the inbox.", last) : t("Watched: a search for the subject every {0} days, last {1}; its arrivals land in the inbox.", watched.everyDays(), last)).append("</p>");
        }
        else b.append("<form method=\"post\" action=\"/dossier\"><input type=\"hidden\" name=\"subject\" value=\"").append(esc(d.label())).append("\"><button name=\"watch\" value=\"1\">").append(t("Watch")).append("</button> <span class=\"note\">").append(t("a search for the subject every seven days, its arrivals in the inbox; or: {0}", "<code>researchzosho dossier \"" + esc(d.label()) + "\" --watch</code>")).append("</span></form>");
        b.append("</div>");
        // what the page will carry when built
        b.append("<h2 id=\"later\">").append(t("Not yet on this page")).append("</h2><div class=\"sec\"><p class=\"later\">").append(t("Statements and socials as dated quotes, photos and video with their provenance, the pattern of life beyond the signals above. Each is a build after the claims that feed it exist.")).append("</p>");
        b.append("<p class=\"note\">").append(t("Markdown: {0} · JSON: {1} · PDF: print this page · Obsidian: {2} writes {3}.", "<code>researchzosho dossier \"" + esc(d.label()) + "\"</code>", "<code>library_dossier</code>", "<code>researchzosho vault</code>", "<code>Dossiers/" + esc(d.label()) + ".md</code>")).append("</p></div>");
        return b.toString();
    }

    static String claim(Finding f) { return claim(f, Translate.AS_IS); }

    static String claim(Finding f, Translate.Words w) { return claim(f, w, SourceClass.Own.NONE); }

    static String claim(Finding f, Translate.Words w, SourceClass.Own own) {
        SourceClass c = SourceClass.of(f.sources(), own);
        String cls = switch (c) { case publicRecord -> "c-public"; case ownPublication -> "c-own"; case supplied -> "c-supplied"; case told -> "c-told"; case canon -> "c-canon"; default -> "c-third"; };
        String shown = Dossier.dateShown(f);
        if (shown.startsWith("said ")) shown = t("said {0}", shown.substring(5));   // the day the claim was stated, apart from the year it is about
        return esc(w.tr(f.title())) + " <span class=\"cls " + cls + "\">" + esc(classSaid(c)) + "</span>" + (shown.isEmpty() ? "" : "<span class=\"date\">" + esc(shown) + "</span>")
                + " <a class=\"k\" href=\"/entry/" + enc(f.id()) + "\">" + esc(f.id()) + "</a>";
    }

    /** A report's section by its exact heading, "" when it has none. */
    /**
     * How the dossier was made, behind a fold closed by default, as a report's page has: the runs that fed it with their dates, models and
     * times, the claims by the class of their source and by site, what was told, and the span of the claims' dates.
     */
    static String madeFold(LibraryStore store, Dossier.Gathered d, String storyTitle) {
        List<Investigation> runs = new ArrayList<>();
        for (Investigation inv : d.reports()) if (!inv.title().equals(storyTitle)) runs.add(inv);
        Map<String, ObjectNode> jobByReport = new HashMap<>();
        try { for (ObjectNode raw : new Jobs(store, j -> "").recent(200, null)) { String inv = Jobs.view(raw).path("investigation").asText(""); if (!inv.isEmpty()) jobByReport.putIfAbsent(inv, raw); } } catch (IOException | RuntimeException ignored) { }
        StringBuilder in = new StringBuilder("<dl class=\"made\"><dt>").append(tc("Runs", "the research runs a dossier was made from")).append("</dt><dd>");
        String lastRun = "";
        if (runs.isEmpty()) in.append(t("None yet: the claims came from the shelves, or were told."));
        else {
            in.append("<ul class=\"plain\">");
            for (Investigation inv : runs) {
                ObjectNode job = jobByReport.get(inv.id());
                String model = job == null ? "" : job.path("args").path("model_entry").asText(job.path("drive").asText(""));
                if (day(inv.recordedAt()).compareTo(lastRun) > 0) lastRun = day(inv.recordedAt());
                in.append("<li><a href=\"/entry/").append(enc(inv.id())).append("\">").append(esc(inv.title())).append("</a> <span class=\"date\">").append(esc(Chrome.now().day(day(inv.recordedAt())))).append("</span>")
                  .append(model.isEmpty() ? "" : ", " + esc(model)).append(job == null ? "" : ", " + esc(RunProgress.elapsed(RunProgress.of(Jobs.view(job)).elapsedSeconds()))).append("</li>");
            }
            in.append("</ul>");
        }
        in.append("</dd>");
        Set<String> hosts = new TreeSet<>(); int told = 0; String first = "", last = "";
        for (Finding f : d.claims()) {
            for (Finding.Source s : f.sources()) { String u = s.locator() == null ? "" : s.locator(); if (u.startsWith("told://")) told++; else { String h = CareerSources.host(u); if (!h.isEmpty()) hosts.add(h); } }
            String on = day(f.recordedAt()); if (!on.isEmpty() && (first.isEmpty() || on.compareTo(first) < 0)) first = on; if (on.compareTo(last) > 0) last = on;
        }
        // each count after its noun, so the sentence is right for every number in every language
        List<String> classes = new ArrayList<>();
        for (Map.Entry<SourceClass, List<Finding>> e : d.byClass().entrySet()) if (!e.getValue().isEmpty()) classes.add(classSaid(e.getKey()) + " " + e.getValue().size());
        String sites = esc(String.join(", ", hosts.size() > 12 ? new ArrayList<>(hosts).subList(0, 12) : new ArrayList<>(hosts))) + (hosts.size() > 12 ? ", …" : "");
        List<String> source = new ArrayList<>();
        source.add(t("Claims: {0}.", d.claims().size()));
        if (!classes.isEmpty()) source.add(t("By the class of their source: {0}.", esc(String.join(Chrome.now().sep(), classes))));
        source.add(hosts.isEmpty() ? t("Sites: {0}.", 0) : t("Sites: {0} ({1}).", hosts.size(), sites));
        if (told > 0) source.add(t("Told: {0}.", told));
        in.append("<dt>").append(t("Sources")).append("</dt><dd>").append(String.join(" ", source)).append("</dd>");
        String from = Chrome.now().day(first), to = Chrome.now().day(last);
        in.append("<dt>").append(t("Dates")).append("</dt><dd>").append(first.isEmpty() ? t("No claim dated yet.") : first.equals(last) ? t("The claims were recorded on {0}.", esc(from)) : t("The claims were recorded from {0} to {1}.", esc(from), esc(to))).append("</dd>");
        in.append("<dt>").append(t("Record")).append("</dt><dd>").append(t("The dossier is built from the shelves each time it is opened; {0} is the same page as text.", "<a href=\"/dossier?subject=" + enc(d.label()) + "&amp;format=md\">" + t("the markdown") + "</a>")).append("</dd></dl>");
        List<String> gist = new ArrayList<>(List.of(runs.size() == 1 ? tc("{0} run", RUNS, 1) : tc("{0} runs", RUNS, runs.size()), n(d.claims().size(), "{0} claim", "{0} claims"), n(hosts.size(), "{0} site", "{0} sites")));
        if (told > 0) gist.add(t("{0} told", told));
        if (!lastRun.isEmpty()) gist.add(t("last run {0}", Chrome.now().day(lastRun)));
        return "<details class=\"sec made\"><summary><h2>" + t("How this was made") + "</h2> <span class=\"gist\">" + esc(String.join(Chrome.now().sep(), gist)) + "</span></summary><div class=\"in\">" + in + "</div></details>";
    }

    /** The meanings beside the words that have another meaning elsewhere in the interface. */
    static final String READING = "the section of a dossier that answers the question of its latest run", SECTION = "a section of a dossier, in its table of contents",
            FACET = "a facet of a dossier: one part of what the reports say of the subject";
    /** The meaning beside "{0} run(s)" in the summary of how a dossier was made. */
    static final String RUNS = "a count of the research runs a dossier was made from";
    /** A forecast's outcomes as they are kept; "did-not" is said "did not". */
    static final Set<String> OUTCOMES = Set.of("happened", "did-not", "void");

    /** The class of a source as a badge says it, in the person's language. */
    static String classSaid(SourceClass c) { return tc(c.label(), "the class of a claim's source, as a badge beside the claim"); }

    static String section(String body, String name) { return TimelineClaims.sectionIn(body, name); }   // with its continuations
    static String sectionStarting(String body, String prefix) {
        if (body == null) return "";
        int at = body.indexOf("\n## " + prefix); if (at < 0) return "";
        int line = body.indexOf('\n', at + 1); if (line < 0) return "";
        String rest = body.substring(line + 1); int end = rest.indexOf("\n## "); return (end < 0 ? rest : rest.substring(0, end)).strip();
    }
    static List<String[]> coverage(String body) {
        List<String[]> out = new ArrayList<>();
        String s = section(body, "Record collections searched");
        for (String l : s.split("\n")) { Matcher m = Pattern.compile("^- (.+?): (\\d+ search(?:es)?, \\d+ found records.*)$").matcher(l.strip()); if (m.find()) out.add(new String[]{m.group(1), m.group(2)}); }
        return out;
    }
    static String notSearched(String body) {
        Matcher m = Pattern.compile("Not searched in this run: (.+?)\\.\\n").matcher(section(body, "Record collections searched") + "\n");
        return m.find() ? m.group(1) : "";
    }
    static String byClassLine(String body) {
        Matcher m = Pattern.compile("By source class: ([^.\\n]+)\\.").matcher(body == null ? "" : body);
        return m.find() ? m.group(1) : "";
    }
    static String firstLine(String s) { int n = s.indexOf('\n'); return (n < 0 ? s : s.substring(0, n)).strip(); }
    static String day(String iso) { return iso != null && iso.length() >= 10 ? iso.substring(0, 10) : iso == null ? "" : iso; }
    static String clip(String s, int max) { s = s.replace("\n", " ").strip(); return s.length() > max ? s.substring(0, max).strip() + "…" : s; }
}
