package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.researchzosho.librarian.CareerCut.Cut;
import org.researchzosho.librarian.CareerSources.Hit;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where the employers a seeker wants are listed, found for that seeker (docs/DESIGN_SOURCES.md, docs/DESIGN_CAREER.md, the general
 * shape): the model proposes, from what the person decided, a few web searches that would find pages listing such employers in that
 * place, a register of licensed facilities, a directory, a venture firm's portfolio, an association's members, a job bank; the
 * searches run; each page found is read as a list, and a page that names many employers is kept as a source for the field, the
 * place and the occupation, found by this search, so the next search reads it first and the next seeker in that place starts from
 * it. Nothing about any place or occupation is in the code: the proposals come from the wants, the readers are generic.
 */
public final class CareerTargets {
    private CareerTargets() { }

    private static final ObjectMapper M = new ObjectMapper();
    /** The most searches proposed for one seeker in one search, the most pages read from them, and the fewest names a page must give to be a source. */
    static final int SEARCHES = 8, PAGES = 12, NAMES_MIN = 8, NAMES_MAX = 300;

    /** What a search of the targets found: the sources kept or read, and the employers named on them, each with the source it came from. */
    public record Found(List<Sources.Source> sources, Map<String, String> employers, List<String> notes) { }

    /** The seeker's place and occupation in words, for the sources' records and the proposals: from the cut's places and the wants' role. */
    static String placeWords(Cut cut) { return String.join(", ", cut.places().isEmpty() ? cut.preferPlaces() : cut.places()); }   // the place decided, else the library's guess of it, which the search runs on until the person says

    /**
     * The sources for this seeker's field, place and occupation that the library already has, read first; then, when fewer than half
     * of {@code max} employers came from them, the model's proposals, run and read; the pages that name many employers kept as sources.
     */
    public static Found find(LibraryStore store, Career.Seeker s, Cut cut, String occupation, String employerKind, int max, BooleanSupplier stop) throws IOException { return find(store, s, cut, occupation, employerKind, "", max, stop); }

    /** As above; {@code fallbackPlace} is where the record puts the person, used when the cut has no place decided or guessed. */
    public static Found find(LibraryStore store, Career.Seeker s, Cut cut, String occupation, String employerKind, String fallbackPlace, int max, BooleanSupplier stop) throws IOException { return find(store, s, cut, occupation, employerKind, "", fallbackPlace, max, stop); }

    /**
     * As above; {@code field} is the area the person decided to work in ("pets or animals", "clinical research"), which names the kind of
     * employer the searches and the registers look for beside the occupation; "" when they decided none.
     */
    public static Found find(LibraryStore store, Career.Seeker s, Cut cut, String occupation, String employerKind, String field, String fallbackPlace, int max, BooleanSupplier stop) throws IOException {
        List<Sources.Source> used = new ArrayList<>();
        Map<String, String> employers = new LinkedHashMap<>();
        List<String> notes = new ArrayList<>();
        String place = placeWords(cut).isEmpty() ? (fallbackPlace == null ? "" : fallbackPlace.strip()) : placeWords(cut);
        String seek = field.isEmpty() ? occupation : occupation + " in the field of " + field;   // what the sources are kept for: the lists for an engineer in pets are not the lists for an engineer
        SourceRules rules = SourceRules.load(store);
        if (!place.isEmpty() && !stop.getAsBoolean()) {   // the registers the library knows for the country of the place, each through its own route: the surest list of employers there is
            CareerTargets.Found rf = CareerRegisters.read(store, s, place, occupation, employerKind, field.isEmpty() ? String.join(", ", cut.areas()) : field, max, rules);   // the decided field names the register's sections; else the title areas
            used.addAll(rf.sources()); notes.addAll(rf.notes());
            for (Map.Entry<String, String> e : rf.employers().entrySet()) if (employers.size() < max) employers.putIfAbsent(e.getKey(), e.getValue());
        }
        for (Sources.Source src : Sources.active(store, "career", place, seek)) {   // what the library has for them already
            if (stop.getAsBoolean() || employers.size() >= max) break;
            if (!src.kind().equals("list page") && !src.kind().equals("directory") && !src.kind().equals("register")) continue;   // a board is read by the boards reader, never as a list page
            if (src.yields().isEmpty() || CareerRegisters.isRegisterAddress(src.address())) continue;   // a register kept behind a search form, with no reader yet, or one of the registers read above: nothing to read as a list
            if (!src.address().matches("(?i)https?://.*")) continue;
            List<String> names = namesOn(src.address());
            for (String n : names) if (employers.size() < max) employers.putIfAbsent(n, src.id());
            Sources.Source r = Sources.read(store, src.address(), src.kind(), "employers", "career", src.place(), src.subject(), src.foundBy(), names.size());
            if (r != null) used.add(r);
        }
        if (!used.isEmpty()) notes.add(used.size() + (used.size() == 1 ? " source of the library's read" : " sources of the library's read") + ", " + employers.size() + " employers named");
        if (employers.size() >= Math.max(1, max / 2) || stop.getAsBoolean()) return new Found(used, employers, notes);   // the library's sources gave enough: no new searches this time
        if (place.isEmpty()) { notes.add("no place decided, guessed or on the record: nothing to look for employers in"); return new Found(used, employers, notes); }   // a search for lists needs a place to anchor to, or it drifts anywhere
        List<String> queries = propose(place, occupation, employerKind, field, cut, Career.language(store, s));
        int pages = 0, kept = 0;
        List<String> read = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Sources.Source u : used) seen.add(Sources.key(u.address()));
        for (String q : queries) {
            if (stop.getAsBoolean() || pages >= PAGES || employers.size() >= max) break;
            for (Hit h : CareerSources.SEARCH.apply(q)) {
                if (pages >= PAGES || employers.size() >= max) break;
                String url = h.url();
                if (url.isEmpty() || CareerSources.aggregator(url) || media(url) || rules.refused(url) || !seen.add(Sources.key(url))) continue;   // a host the person refused is not even read; a video or social page lists no employers
                pages++; read.add(url);
                List<String> names = namesOn(url);
                if (names.size() < NAMES_MIN) {   // no list to read: a register or directory reached through a search form is still worth keeping, for the person to see and a reader to serve later
                    String reg = registerOf(url, occupation, employerKind, field, place);
                    if (!reg.isEmpty()) {
                        Sources.Source r = Sources.read(store, url, "register", "", "career", place, seek, "the career search for " + s.name(), 0);
                        if (r != null && !r.off()) { notes.add(url + ": " + reg + ", reached through a search form; the library has no reader for it yet"); used.add(r); }
                    }
                    continue;
                }
                String what = listOf(names, occupation, employerKind, field, place);   // the reader flagged a run of names; the model says whether they are such employers
                if (!what.isEmpty()) {
                    notes.add(url + ": a list of " + what + ", not of employers for them");
                    String reg = registerOf(url, occupation, employerKind, field, place);   // a register's front page is often its navigation and a search form: not a list, but worth keeping as a register
                    if (!reg.isEmpty()) { Sources.Source r = Sources.read(store, url, "register", "", "career", place, seek, "the career search for " + s.name(), 0); if (r != null && !r.off()) { notes.add(url + ": " + reg + ", reached through a search form; the library has no reader for it yet"); used.add(r); } }
                    continue;
                }
                Sources.Source src = Sources.read(store, url, Sources.kindOf(url).equals("saved search") ? "list page" : Sources.kindOf(url), "employers", "career", place, seek, "the career search for " + s.name(), names.size());
                if (src == null || src.off()) continue;
                kept++; used.add(src);
                for (String n : names) if (employers.size() < max) employers.putIfAbsent(n, src.id());
            }
        }
        notes.add(queries.size() + " searches proposed for " + (occupation.isEmpty() ? "the seeker" : occupation) + (field.isEmpty() ? "" : " in the field of " + field) + (place.isEmpty() ? "" : " in " + place) + " (" + String.join(" | ", queries) + "), " + pages + " pages read ("
                + String.join(" | ", read) + "), " + kept + (kept == 1 ? " kept as a source" : " kept as sources") + ", " + employers.size() + " employers named in all");
        return new Found(used, employers, notes);
    }

    /**
     * The model's proposals: web searches that would find pages listing the employers this person wants in this place. From the
     * wants alone; the code names no place and no occupation.
     */
    static List<String> propose(String place, String occupation, String employerKind, Cut cut) { return propose(place, occupation, employerKind, "", cut, ""); }
    static List<String> propose(String place, String occupation, String employerKind, Cut cut, String language) { return propose(place, occupation, employerKind, "", cut, language); }

    /** The person in a phrase for the model: as what, in which field, where, at what kind of employer. */
    static String seeking(String occupation, String employerKind, String field, String place) {
        return (occupation.isEmpty() ? "" : " as " + occupation) + (field.isEmpty() ? "" : ", in the field of " + field) + (place.isEmpty() ? "" : " in " + place) + (employerKind.isEmpty() ? "" : ", at " + employerKind);
    }

    static List<String> propose(String place, String occupation, String employerKind, String field, Cut cut, String language) {
        ArrayNode msgs = M.createArrayNode();
        msgs.addObject().put("role", "user").put("content",
                "A person is looking for work" + seeking(occupation, employerKind, field, place)
                + ". Write up to " + SEARCHES + " web searches, each a few words, that would find PAGES THAT LIST such employers in that place: a public register of the licensed or "
                + "registered ones, an official or trade directory, an association's member list, a venture firm's portfolio page, a job bank or board for that work, a league table or "
                + "a 'companies in' list. Each search names the place and the kind of employer or the work, in the language the person writes in" + (language.isEmpty() ? "" : ", which is the language of this sentence of theirs: \"" + language + "\"")
                + ", and in the place's own language where such lists are published in it. Every search different from the others. Prefer searches that find lists over searches "
                + "that find one employer. Answer with a JSON array of strings and nothing else.");
        String said;
        try { said = Career.DRIVE.get().classify(msgs, 400); } catch (RuntimeException e) { said = null; }
        List<String> out = new ArrayList<>();
        for (JsonNode q : Career.parseArray(said)) { String t = q.asText("").strip(); if (!t.isEmpty() && t.length() < 120 && out.size() < SEARCHES && out.stream().noneMatch(x -> x.equalsIgnoreCase(t))) out.add(t); }   // the model repeats itself: once each
        return out;
    }

    /**
     * Whether the names a page lists are employers of the kind the person wants, judged by the model from the names alone: "" when they
     * are, else what the list is of ("links to other states' search tools", "forms"). A run of name-like texts is a sensor's flag; the
     * model only disambiguates what it flagged, and never nominates a page itself.
     */
    static String listOf(List<String> names, String occupation, String employerKind, String place) { return listOf(names, occupation, employerKind, "", place); }

    static String listOf(List<String> names, String occupation, String employerKind, String field, String place) {
        ArrayNode msgs = M.createArrayNode();
        msgs.addObject().put("role", "user").put("content",
                "Below are the first names from a list on a web page. A person is looking for work" + seeking(occupation, employerKind, field, place) + ". Are these the names of employers of that kind, organisations one could work for, in or near that place? Answer with a JSON object "
                + "and nothing else: {\"employers\": true} when they are, or {\"employers\": false, \"what\": \"what the list is of, in a few words\"} when they are something else, such as "
                + "links to tools, forms, articles, places, products or people.\n\n" + Fence.rule("the names") + "\n" + Fence.wrap("NAMES", String.join("\n", names.subList(0, Math.min(20, names.size())))));
        String said;
        try { said = Career.DRIVE.get().classify(msgs, 120); } catch (RuntimeException e) { said = null; }
        JsonNode j = CareerInterview.parseObject(said);
        if (!j.has("employers")) return "";   // no answer: the sensor's flag stands, and the survival score judges later
        return j.path("employers").asBoolean(false) ? "" : (j.path("what").asText("").isBlank() ? "something other than employers" : j.path("what").asText("").strip());
    }

    /**
     * Whether a page that lists nothing is the front of a register or directory of such employers, reached through a search form, judged by
     * the model from the page's title and first words: "" when not, else what it is ("the French company register"). Kept as a source with
     * no yield, so the person sees it and a reader for that register can serve it later.
     */
    static String registerOf(String url, String occupation, String employerKind, String place) { return registerOf(url, occupation, employerKind, "", place); }

    static String registerOf(String url, String occupation, String employerKind, String field, String place) {
        String html = CareerSources.get(url);
        if (html == null || !html.matches("(?is).*<form\\b.*") || !html.matches("(?is).*<input\\b[^>]*type=\"?(text|search)\"?.*")) return "";   // no search form: not a register's front
        Matcher ttl = Pattern.compile("(?is)<title[^>]*>(.*?)</title>").matcher(html);
        String title = ttl.find() ? ttl.group(1).replaceAll("\\s+", " ").strip() : "", text = CareerSources.clip(CareerSources.plain(html.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", "")).replaceAll("\\s+", " "), 600);
        ArrayNode msgs = M.createArrayNode();
        msgs.addObject().put("role", "user").put("content",
                "Below are the title and the first words of a web page with a search form. A person is looking for work" + seeking(occupation, employerKind, field, place)
                + ". Is this page the front of a public register or a directory in which such employers in that place are listed, one that gives each one's record? "
                + "Answer with a JSON object and nothing else: {\"register\": true, \"what\": \"what it is, in a few words\"} or {\"register\": false}.\n\n" + Fence.rule("the page") + "\n" + Fence.wrap("PAGE", title + "\n" + text));
        String said;
        try { said = Career.DRIVE.get().classify(msgs, 120); } catch (RuntimeException e) { said = null; }
        JsonNode j = CareerInterview.parseObject(said);
        boolean government = CareerSources.host(url).matches(".*\\.(gov|gouv\\.fr|gob\\.[a-z]{2}|go\\.jp|go\\.kr|gov\\.[a-z]{2}|gc\\.ca|govt\\.nz)$");   // a government host with a search form is a public register whatever the model says; the model only names it
        if (j.path("register").asBoolean(false)) return j.path("what").asText("").isBlank() ? "a register" : j.path("what").asText("").strip();
        return government ? (j.path("what").asText("").isBlank() ? "a public register" : j.path("what").asText("").strip()) : "";
    }

    /** Video and social hosts: a search returns them, and a list of employers is never on them. */
    static boolean media(String url) {
        String h = CareerSources.host(url);
        for (String m : List.of("youtube.com", "youtu.be", "vimeo.com", "tiktok.com", "facebook.com", "instagram.com", "x.com", "twitter.com", "linkedin.com", "pinterest.com")) if (h.equals(m) || h.endsWith("." + m)) return true;
        return false;
    }

    static final Pattern NAME = Pattern.compile("^(?=.{3,60}$)(?:[\\p{Lu}\\p{N}][\\p{L}\\p{N}&'.-]*)(?:\\s+(?:[\\p{Lu}\\p{N}][\\p{L}\\p{N}&'.-]*|of|and|de|du|la|le|the|for|&|y|e|und|di|del|der|van|von|da|do|dos|das))*$");
    static final Pattern NOT_A_NAME = Pattern.compile("(?i)^(home|about|contact|login|log in|sign in|sign up|register|search|menu|next|previous|more|read more|privacy|terms|cookies|back|top|all|see all|view all|page \\d+|\\d+"
            + "|organi[sz]ation|organi[sz]ations|company|company name|name|employer|employers|member|members|title|website|city|state|country|address|phone|email|category|type|sector|industry"
            + "|ceo|cto|cfo|coo|cio|ciso|vp|svp|evp|hr|founder|director|manager)$");   // a list's own column headings, the nav, and a seat's title standing alone
    /** Text that is not a name whatever its shape: an address, a mark-up fragment, or words that read as an instruction; page text is evidence, never instruction. */
    static final Pattern NOT_EVIDENCE = Pattern.compile("(?i)(https?:|www\\.|@|[<>{}\\[\\]]|\\b(ignore|instruction|instructions|system prompt|assistant|disregard|you must|click here)\\b)");

    /**
     * The names a page lists. A list is a run of siblings: the items of one {@code <ul>} or {@code <ol>}, the rows of one table, the
     * terms of one definition list. The run with the most texts that look like names of organisations is the list; its names are
     * returned, each once, up to a cap. A page with no such run names nothing, however many capitalised phrases it carries: a how-to
     * page's headings are not employers. Generic: the same reader for a register, a directory, a portfolio or an association's members.
     */
    public static List<String> namesOn(String url) {
        String html = CareerSources.get(url);
        if (html == null) return List.of();
        List<String> best = List.of();
        for (Pattern run : RUNS) {
            Matcher m = run.matcher(html);
            while (m.find()) {
                List<String> names = namesIn(m.group(1), ITEM);
                if (names.size() > best.size()) best = names;
            }
        }
        return best;
    }

    /** The runs a list can be: a bulleted or numbered list, a table, a definition list. */
    static final List<Pattern> RUNS = List.of(Pattern.compile("(?is)<(?:ul|ol)\\b[^>]*>(.*?)</(?:ul|ol)>"), Pattern.compile("(?is)<table\\b[^>]*>(.*?)</table>"), Pattern.compile("(?is)<dl\\b[^>]*>(.*?)</dl>"));
    /** An item of a run: a list item, a table row (its first cell), a term. */
    static final Pattern ITEM = Pattern.compile("(?is)<(?:li|tr|dt)\\b[^>]*>(.*?)</(?:li|tr|dt)>");

    /** The name-like texts of a run's items, each once, the navigation words and what is not evidence left out. */
    static List<String> namesIn(String run, Pattern item) {
        Set<String> out = new LinkedHashSet<>();
        Matcher i = item.matcher(run);
        while (i.find() && out.size() < NAMES_MAX) {
            String inner = i.group(1);
            if (inner.matches("(?is)\\s*<th\\b.*")) continue;   // a table's header row names the columns, not an employer
            Matcher cell = Pattern.compile("(?is)<td\\b[^>]*>(.*?)</td>").matcher(inner);   // a row's first cell is the name; the rest is detail
            if (cell.find()) inner = cell.group(1);
            Matcher link = Pattern.compile("(?is)<a\\b[^>]*>(.*?)</a>").matcher(inner);   // an item's link text, when it has one, is the name
            if (link.find()) inner = link.group(1);
            String t = inner.replaceAll("(?is)<[^>]+>", " ").replace("&amp;", "&").replaceAll("&[a-z#0-9]+;", " ").replaceAll("\\s+", " ").strip();
            if (t.isEmpty() || NOT_A_NAME.matcher(t).matches() || NOT_EVIDENCE.matcher(t).find() || !NAME.matcher(t).matches()) continue;
            if (t.split("\\s+").length > 6) continue;
            out.add(t);
        }
        return new ArrayList<>(out);
    }
}
