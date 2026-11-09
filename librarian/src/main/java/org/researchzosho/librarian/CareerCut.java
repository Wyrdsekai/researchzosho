package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.researchzosho.librarian.CareerSources.Opening;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The cut (docs/DESIGN_CAREER.md, step 3): which openings the career search keeps, by what the person decided. The model turns the
 * decided topics into the words postings use, once for each change in what the person wants; after that the cut is words matched
 * against titles and places, with no model, so thousands of openings are cut in a moment and the same openings always cut the same
 * way. A guess never cuts: the library's guesses only order what passes. The person can set the cut's words themselves; then it is
 * theirs, and a change in what they want does not make it again until they ask.
 */
public final class CareerCut {
    private CareerCut() { }

    private static final ObjectMapper M = new ObjectMapper();

    /**
     * {@code titles}: a title must contain one of these, [] for any title; {@code titlesAlso}: and one of these as well, when what the person
     * decided has two sides a title must both show (a founding role that is an engineering one); {@code titlesNot}: a title with one is out;
     * {@code areas}: the words of the area the person's roles name, which a title made of a rank word alone, VP, Director, Head of, must
     * carry, since a VP is a VP of something while a CTO is a role by itself; {@code maxPeople}, {@code minPeople}: the company size decided,
     * as numbers (0 when not decided), which cut a company the library knows to be far outside them; {@code places}: the
     * place must name one, [] for anywhere; {@code remote}: only, fine, no, or "" when not decided; {@code remoteWhere}: where a remote
     * opening may be based, [] for anywhere; {@code preferTitles}, {@code preferPlaces}: from the guesses, for the order only.
     * {@code by}: library or you; {@code at}: the day it was made; {@code basis}: what it was made from, to tell when it is out of date.
     * {@code nearby}: the towns within {@code nearKm} of each city the places name, from Wikidata, which the place also matches; a place
     * decided as a city means the city and what is near it, not the city's name alone.
     */
    public record Cut(List<String> titles, List<String> titlesAlso, List<String> titlesNot, List<String> areas, List<String> places, String remote, List<String> remoteWhere,
                      List<String> preferTitles, List<String> preferPlaces, int maxPeople, int minPeople, String by, String at, String basis, List<String> nearby, int nearKm) {
        public Cut(List<String> titles, List<String> titlesAlso, List<String> titlesNot, List<String> areas, List<String> places, String remote, List<String> remoteWhere,
                   List<String> preferTitles, List<String> preferPlaces, int maxPeople, int minPeople, String by, String at, String basis) {
            this(titles, titlesAlso, titlesNot, areas, places, remote, remoteWhere, preferTitles, preferPlaces, maxPeople, minPeople, by, at, basis, List.of(), 0);
        }
        public static final Cut NONE = new Cut(List.of(), List.of(), List.of(), List.of(), List.of(), "", List.of(), List.of(), List.of(), 0, 0, "library", "", "");
        /** The same cut with the towns near its cities. */
        Cut near(List<String> towns, int km) { return new Cut(titles, titlesAlso, titlesNot, areas, places, remote, remoteWhere, preferTitles, preferPlaces, maxPeople, minPeople, by, at, basis, towns, km); }
        /** Whether nothing about the role or the place cuts: every opening passes, and the guesses only order them. */
        public boolean cutsNothing() { return titles.isEmpty() && titlesAlso.isEmpty() && places.isEmpty() && remote.isEmpty(); }
    }

    static Path file(LibraryStore store, Career.Seeker s) { return CareerFind.dir(store, s).resolve("cut.json"); }

    /** The cut as kept; null when none was made. */
    public static Cut kept(LibraryStore store, Career.Seeker s) throws IOException {
        Path f = file(store, s);
        if (!Files.isRegularFile(f)) return null;
        JsonNode j = M.readTree(f.toFile());
        String remote = j.path("remote").asText("");
        if (remote.equals("only") && j.path("places").size() > 0 && j.path("remote_where").size() == 0 && j.path("by").asText("library").equals("library")) remote = "fine";   // a place and remote both: remote counts beside the place; "only" is the person's to say
        return new Cut(list(j.path("titles")), list(j.path("titles_also")), list(j.path("titles_not")), list(j.path("areas")), list(j.path("places")), remote, list(j.path("remote_where")),
                list(j.path("prefer_titles")), list(j.path("prefer_places")), j.path("max_people").asInt(0), j.path("min_people").asInt(0), j.path("by").asText("library"), j.path("at").asText(""), j.path("basis").asText(""),
                list(j.path("nearby")), j.path("near_km").asInt(0));
    }

    static void save(LibraryStore store, Career.Seeker s, Cut c) throws IOException {
        Files.createDirectories(file(store, s).getParent());
        M.writerWithDefaultPrettyPrinter().writeValue(file(store, s).toFile(), json(c));
    }

    /** The cut as it is kept: every field, so a run's record can carry its own. */
    public static ObjectNode json(Cut c) {
        ObjectNode o = M.createObjectNode();
        put(o, "titles", c.titles()); put(o, "titles_also", c.titlesAlso()); put(o, "titles_not", c.titlesNot()); put(o, "areas", c.areas()); put(o, "places", c.places()); o.put("remote", c.remote()); put(o, "remote_where", c.remoteWhere());
        put(o, "prefer_titles", c.preferTitles()); put(o, "prefer_places", c.preferPlaces()); o.put("max_people", c.maxPeople()).put("min_people", c.minPeople()).put("by", c.by()).put("at", c.at()).put("basis", c.basis());
        put(o, "nearby", c.nearby()); o.put("near_km", c.nearKm()).put("near_lookup", NEAR_LOOKUP);
        return o;
    }

    /** A cut read back as kept, with no healing: a run's own, as the run used it. */
    public static Cut fromJson(JsonNode j) {
        return new Cut(list(j.path("titles")), list(j.path("titles_also")), list(j.path("titles_not")), list(j.path("areas")), list(j.path("places")), j.path("remote").asText(""), list(j.path("remote_where")),
                list(j.path("prefer_titles")), list(j.path("prefer_places")), j.path("max_people").asInt(0), j.path("min_people").asInt(0), j.path("by").asText("library"), j.path("at").asText(""), j.path("basis").asText(""),
                list(j.path("nearby")), j.path("near_km").asInt(0));
    }

    /** What the cut is made from: the topics with their states and words, and what is kept out. A change in it makes the library's cut again. */
    static String basis(LibraryStore store, Career.Seeker s) throws IOException {
        StringBuilder b = new StringBuilder();
        for (JsonNode d : Career.wantsKept(store, s).path("dimensions")) b.append(d.path("dimension").asText()).append('|').append(d.path("state").asText()).append('|').append(d.path("want").asText()).append('\n');
        for (String k : CareerSearch.keepOut(store, s)) b.append("out|").append(k).append('\n');
        JsonNode iv = CareerInterview.state(store, s);
        for (CareerInterview.Question q : CareerInterview.questions(iv)) if (q.tag().equals(CareerInterview.LOOK_FOR)) b.append("asked|").append(CareerInterview.answerOf(iv, q.n())).append('\n');
        b.append("made|2\n");   // how the library makes a cut: a change here makes every library-made cut again, once
        return CareerSources.id(b.toString());
    }

    /** The cut for a search: the person's own as it is; the library's when it is still made from what the person wants now; else made again. */
    public static Cut current(LibraryStore store, Career.Seeker s) throws IOException {
        Cut c = kept(store, s);
        if (c != null && (c.by().equals("you") || c.basis().equals(basis(store, s)))) return nearAgain(store, s, c);
        return make(store, s);
    }

    /** How the towns near a city are looked up; a cut kept with towns looked up an older way looks them up again. 3: a town counts whatever its size. */
    static final int NEAR_LOOKUP = 3;

    /**
     * A kept cut whose towns near its cities were looked up before a town counted whatever its size gets them again, from Wikidata and with
     * no model, and is kept so: the person's own cut too, whose towns the library looked up all the same. Nothing else in it changes.
     */
    static Cut nearAgain(LibraryStore store, Career.Seeker s, Cut c) throws IOException {
        if (c.places().isEmpty() || M.readTree(file(store, s).toFile()).path("near_lookup").asInt(0) >= NEAR_LOOKUP) return c;
        Cut again = withNearby(store, s, c.near(List.of(), 0), c.nearKm() > 0 ? c.nearKm() : radiusWanted(store, s));
        save(store, s, again);
        return again;
    }

    /** Whether what the person wants changed since the library made the cut: the cut is the library's and was made from something else. */
    public static boolean stale(LibraryStore store, Career.Seeker s) throws IOException {
        Cut c = kept(store, s);
        return c != null && !c.by().equals("you") && !c.basis().equals(basis(store, s));
    }

    /** The model turns what the person decided into the words postings use; saved, logged, and its own answer kept beside it. */
    public static Cut make(LibraryStore store, Career.Seeker s) throws IOException { return make(store, s, Career.wantsKept(store, s), CareerSearch.keepOut(store, s), basis(store, s), true); }

    /**
     * As above, from the topics and the keep-outs given: the standing record, saved as the search's cut; or a run's own, from a change for
     * that search only, which is not saved as the standing cut and is kept with the run instead.
     */
    public static Cut make(LibraryStore store, Career.Seeker s, JsonNode wantsNode, List<String> keepOutGiven, String basis, boolean keep) throws IOException {
        StringBuilder wants = new StringBuilder();
        for (JsonNode d : wantsNode.path("dimensions")) {
            String st = d.path("state").asText("open");
            if (st.equals("open")) continue;
            wants.append("- ").append(d.path("dimension").asText()).append(", ").append(st.equals("decided") ? "DECIDED" : "A GUESS").append(": ").append(d.path("want").asText("")).append('\n');
        }
        List<String> out = keepOutGiven;
        StringBuilder asked = new StringBuilder();   // the person's own answer to what the search should look for: their plainest word on titles and places
        JsonNode iv = CareerInterview.state(store, s);
        for (CareerInterview.Question q : CareerInterview.questions(iv)) if (q.tag().equals(CareerInterview.LOOK_FOR) && !CareerInterview.answerOf(iv, q.n()).isBlank()) asked.append(CareerInterview.answerOf(iv, q.n()).strip()).append('\n');
        List<String> held = new ArrayList<>();   // the titles of the roles they have held, newest first: the areas their executive titles belong to
        List<JsonNode> roles = new ArrayList<>();
        for (JsonNode e : Career.evidence(store, s)) if (e.path("kind").asText().equals("role")) roles.add(e);
        roles.sort((a, b) -> b.path("start").asText("").compareTo(a.path("start").asText("")));
        for (JsonNode e : roles) { String title = e.path("text").asText("").split(" at ", 2)[0].strip(); if (!title.isEmpty() && held.stream().noneMatch(h -> h.equalsIgnoreCase(title)) && held.size() < 20) held.add(title); }
        if (wants.isEmpty() && out.isEmpty() && asked.isEmpty()) {
            Cut none = new Cut(List.of(), List.of(), List.of(), List.of(), List.of(), "", List.of(), List.of(), List.of(), 0, 0, "library", LocalDate.now().toString(), basis);
            if (keep) save(store, s, none);
            return none;
        }
        ArrayNode msgs = M.createArrayNode();
        msgs.addObject().put("role", "user").put("content",
                "Turn what a person wants from their next job into the words job postings use, for a search through openings. Today is " + LocalDate.now() + ".\n\n"
                + "From the DECIDED topics only, the person's own word:\n"
                + "- role_titles: the short words a job title carries for their decided role, their decided kind of work, and the roles they asked the search to look "
                + "for, such as \"VP\", "
                + "\"Head of\", \"Director\", \"Principal\", \"Staff\", \"Lead\", \"CTO\", \"Engineer\", \"Engineering\", \"Developer\", \"Architect\", "
                + "\"Designer\". A title shows the role when it holds any one of them, so give each word once, in its shortest form, at most twelve, "
                + "and never a combination of them. [] when neither topic is decided or names a role.\n"
                + "- ownership_titles: when their decided ownership is a founding one, the words a title carries for it: \"Founding\", \"Founder\", "
                + "\"Co-founder\", \"Cofounder\". [] when ownership is not decided or is not a founding one. Founding roles are titled \"Founding "
                + "Engineer\", \"Founding CTO\", \"Technical Co-founder\", \"Co-founder & CTO\", so when their work is technical, role_titles holds "
                + "\"CTO\" and \"Technical\" as well.\n"
                + "- areas: the words of the area the person works in, from the roles they have held and what they decided, as titles carry them "
                + "(\"Engineering\", \"Technology\", \"Software\", \"Platform\", \"Product\", \"Research\", \"R&D\", \"Data\", \"AI\"). A title made of a rank "
                + "word alone, VP, SVP, Director, Head of, Lead, must carry one of them, since a VP is a VP of something, while CTO, CEO or Founder is a "
                + "role by itself. [] only when their roles name no area.\n"
                + "A title must show each set that is given. When the person told the search which roles to look for, role_titles holds those roles' "
                + "words and ownership_titles is [] unless founding roles are among the roles they named: their decided ownership then goes to "
                + "prefer_titles, to put founding roles first, and cuts nothing. The company's size and stage, the culture, the pace, the pay and "
                + "the area give no title words: the reading judges them.\n"
                + "- titles_not: the words of other kinds of role whose titles carry the words above, so the cut leaves them out: a search for a "
                + "technical founding role also catches \"Founding Technical Recruiter\", \"Executive Assistant to the CTO\" and \"Founding Customer "
                + "Success\", so titles_not then holds \"Recruiter\", \"Assistant\", \"Chief of Staff\", \"Customer Success\"; a search for an executive "
                + "also catches the roles that sit beside one without its power, \"Field CTO\", \"Office of the CEO\", \"Analyst, CEO Office\", "
                + "\"Deputy CTO\", \"Account CTO\", \"Engineering Coach (VP+)\", so titles_not then holds \"Field\", \"Account\", \"Office\", \"Analyst\", \"Associate\", \"Deputy\", \"Coach\"; when every role they have held "
                + "is in one function, the other functions' words, \"Sales\", \"Marketing\", \"Finance\", \"Brand\", \"Communications\", \"Legal\", "
                + "\"People\", so a VP of Sales is not read for an engineer; for someone senior, \"Intern\" and \"Junior\"; and words from what they "
                + "keep out. [] when none.\n"
                + "- places: where the job may be, from their decided place, as postings write places: cities, states and their two-letter codes, "
                + "regions and countries (\"Boston\", \"Cambridge\", \"MA\", \"Massachusetts\"). [] when place is not decided, or is anywhere.\n"
                + "- remote: from their decided remote topic: \"only\" for remote only, \"fine\" for remote or in one of the places, \"no\" for not "
                + "remote. \"\" when remote is not decided.\n"
                + "- remote_where: where a remote opening may be based for them, as postings write it (\"US\", \"USA\", \"United States\", \"North "
                + "America\", \"Anywhere\"). [] when anywhere.\n"
                + "- max_people, min_people: the company size they decided, as numbers of people: \"not bigger than 200\" gives max_people 200; \"a "
                + "small company\" 50; \"small to medium\" 500; \"at least a thousand\" min_people 1000. 0 when size is not decided or gives no number.\n\n"
                + "From the GUESSES, the library's and not yet confirmed, the same as preferences, which order what passes and never cut:\n"
                + "- prefer_titles: the words of the area their past roles name, so that when they asked for a VP, a VP of Engineering comes before a VP "
                + "of Sales (\"Engineering\", \"Technology\", \"Product\", \"Research\"); prefer_places.\n\n"
                + "Answer with one JSON object and nothing else: {\"role_titles\": [...], \"ownership_titles\": [...], \"titles_not\": [...], \"areas\": [...], \"places\": [...], \"remote\": \"...\", "
                + "\"remote_where\": [...], \"max_people\": 0, \"min_people\": 0, \"prefer_titles\": [...], \"prefer_places\": [...]}\n\n"
                + "WHAT THE PERSON WANTS\n" + (wants.isEmpty() ? "(nothing decided or guessed yet)\n" : wants)
                + (asked.isEmpty() ? "" : "\nWHAT THEY ASKED THE SEARCH TO LOOK FOR, in their own words\n" + asked)
                + (held.isEmpty() ? "" : "\nTHE ROLES THEY HAVE HELD, newest first\n- " + String.join("\n- ", held) + "\n")
                + "\nWHAT THEY KEEP OUT\n" + (out.isEmpty() ? "(nothing)" : "- " + String.join("\n- ", out)));
        String said = Career.DRIVE.get().classify(msgs, 1200);
        Files.createDirectories(CareerFind.dir(store, s));
        Files.writeString(CareerFind.dir(store, s).resolve(keep ? "cut-draft.txt" : "cut-draft-this-search.txt"), "# the model's cut, " + LocalDate.now() + "\n" + (said == null ? "" : said), StandardCharsets.UTF_8);
        JsonNode j = CareerInterview.parseObject(said);
        if (j == null || !j.isObject()) throw new IllegalStateException("The model did not answer with the cut, so the search has none to use. Check that the model server is running, then ask again.");
        String remote = j.path("remote").asText("").strip().toLowerCase(Locale.ROOT);
        if (!List.of("only", "fine", "no").contains(remote)) remote = "";
        if (remote.equals("only") && j.path("places").size() > 0 && j.path("remote_where").size() == 0) {   // "remote or Boston" is not remote only: a place decided beside remote means remote counts, and so does the place
            remote = "fine";
            Career.log(store, s, "You decided a place and remote both, so remote openings count beside the ones in your place. Set remote only on the openings page if you mean remote and nothing else.");
        }
        List<String> role = words(j.has("role_titles") ? j.path("role_titles") : j.path("titles"), 12), owner = words(j.has("ownership_titles") ? j.path("ownership_titles") : j.path("titles_also"), 12);
        role = titleWords(role); owner = titleWords(owner);
        if (role.isEmpty()) { role = owner; owner = List.of(); }   // a founding role alone is the one set
        List<String> prefer = words(j.path("prefer_titles"), 12);
        if (!asked.isEmpty() && !owner.isEmpty() && !asked.toString().toLowerCase(Locale.ROOT).matches("(?s).*\\b(founding|founder|co-?founder|cofounder)\\b.*")) {
            // the person told the search which roles to look for and named no founding role: those roles pass on their own, and founding ones come first
            for (String w : owner) if (prefer.stream().noneMatch(x -> x.equalsIgnoreCase(w))) prefer.add(w);
            owner = List.of();
        }
        Cut c = new Cut(role, owner, words(j.path("titles_not"), 40), words(j.path("areas"), 20), words(j.path("places"), 40), remote, words(j.path("remote_where"), 40),
                prefer, words(j.path("prefer_places"), 40), Math.max(0, j.path("max_people").asInt(0)), Math.max(0, j.path("min_people").asInt(0)), "library", LocalDate.now().toString(), basis);
        c = withNearby(store, s, c, radiusWanted(store, s));
        if (keep) { save(store, s, c); Career.log(store, s, "The library made the search's cut from what you decided: " + summary(c) + "."); }
        else Career.log(store, s, "The library made this search's own cut from what you decided and what you said for this search: " + summary(c) + ". What you decided stands as it was.");
        return c;
    }

    // ---------------------------------------------------------------- the towns near a city

    /** How far from a named city still counts, when the person does not say: in kilometres. */
    public static final int NEAR_KM = 30;
    /** Smaller places than this are not worth a line: in people. */
    static final int NEAR_MIN_PEOPLE = 5000;
    /** The towns within the kilometres of a city: where the city was taken to be, and the towns. */
    public record Towns(String where, List<String> towns, double lat, double lon) {
        public Towns(String where, List<String> towns) { this(where, towns, 0, 0); }
    }
    /** A city named for a lookup, with the words that say which one: the other places the person named, and where their record puts them. */
    public interface Near { Towns apply(String city, List<String> within, int km); }
    /** The towns within the kilometres of the city, from Wikidata; a test puts its own here. */
    static volatile Near NEAR = CareerCut::nearFromWikidata;

    /** The distance the person named with their place ("within 30 km of Lyon", "20 miles from Boston"), in kilometres; the default when they named none. */
    static int radiusWanted(LibraryStore store, Career.Seeker s) throws IOException {
        for (JsonNode d : Career.wantsKept(store, s).path("dimensions")) {
            if (!d.path("dimension").asText("").equals("place")) continue;
            Matcher m = Pattern.compile("(?i)(\\d{1,3})\\s*(km|kilomet\\w*|mi\\b|miles?)").matcher(d.path("want").asText(""));
            if (m.find()) { int n = Integer.parseInt(m.group(1)); return Math.max(5, Math.min(200, m.group(2).toLowerCase(Locale.ROOT).startsWith("mi") ? (int) Math.round(n * 1.609) : n)); }
        }
        return NEAR_KM;
    }

    /**
     * The cut with the towns near each city its places name: the first three places that could be a city's name are looked up, and the
     * towns found count as the place too. The log says which towns count. A place that is no city (a state, a code, a country) gives
     * nothing, so it adds nothing.
     */
    static Cut withNearby(LibraryStore store, Career.Seeker s, Cut c, int km) throws IOException {
        List<String> towns = new ArrayList<>();
        int looked = 0;
        for (String place : c.places()) {
            if (looked >= 3) break;
            String p = place.strip();
            if (p.length() <= 3 || p.equals(p.toUpperCase(Locale.ROOT)) || c.places().stream().anyMatch(x -> !x.equals(place) && x.contains(p + ","))) continue;   // a code, or the start of a longer place
            looked++;
            List<String> decided = new ArrayList<>(), record = new ArrayList<>();   // which city of that name: the other places the person decided, then where their record puts them
            for (String x : c.places()) if (!x.strip().equalsIgnoreCase(p)) decided.add(x.strip());
            for (String x : Career.location(store, s).split(",")) if (!x.strip().isEmpty() && !x.strip().equalsIgnoreCase(p)) record.add(x.strip());
            Towns found = near(store, p, decided, record, km);
            List<String> added = new ArrayList<>();
            for (String t : found.towns()) if (towns.stream().noneMatch(x -> x.equalsIgnoreCase(t)) && c.places().stream().noneMatch(x -> x.equalsIgnoreCase(t))) { towns.add(t); added.add(t); }
            if (!added.isEmpty()) Career.log(store, s, "Near " + p + (found.where().isEmpty() ? "" : " (" + found.where() + ")") + ", within " + km + " km, these count as the place too: " + String.join(", ", added.subList(0, Math.min(added.size(), 40))) + (added.size() > 40 ? " and " + (added.size() - 40) + " more" : "") + " (from Wikidata).");
        }
        return c.near(towns, towns.isEmpty() ? 0 : km);
    }

    /** The towns near a city, kept with the library for a month; nothing when the name is no city's. */
    /**
     * The towns near a city, kept with the library for a month under the city's name: the kept answer serves again while the places the
     * person decided still name the city it was taken to be, or name nothing; a decided place that names another one of the name looks it
     * up again. An answer kept before the library said which city it took is looked up again.
     */
    static Towns near(LibraryStore store, String city, List<String> decided, List<String> record, int km) {
        Path dir = store.root().resolve("catalog").resolve("career-cache"), f = dir.resolve("near3-" + CareerSources.id(city.toLowerCase(Locale.ROOT) + "|" + km) + ".json");   // near3: the answers kept before a town larger than twice the city counted (Boston for Cambridge) are not served again
        try {
            if (Files.isRegularFile(f)) {
                JsonNode j = M.readTree(f.toFile());
                if (j.has("where") && LocalDate.parse(j.path("at").asText("2000-01-01")).plusDays(31).isAfter(LocalDate.now()) && sameCity(j.path("where").asText(""), decided)) return new Towns(j.path("where").asText(""), list(j.path("towns")), j.path("lat").asDouble(0), j.path("lon").asDouble(0));
            }
        } catch (Exception stale) { /* read again */ }
        List<String> within = new ArrayList<>(decided); within.addAll(record);
        Towns towns;
        try { towns = NEAR.apply(city, within, km); } catch (RuntimeException e) { towns = new Towns("", List.of()); }
        if (towns == null) towns = new Towns("", List.of());
        try {
            Files.createDirectories(dir);
            ObjectNode o = M.createObjectNode().put("city", city).put("km", km).put("at", LocalDate.now().toString()).put("where", towns.where()).put("lat", towns.lat()).put("lon", towns.lon());
            put(o, "decided", decided); put(o, "towns", towns.towns());
            M.writerWithDefaultPrettyPrinter().writeValue(f.toFile(), o);
        } catch (IOException ignored) { }
        return towns;
    }

    /** Whether the places decided beside the city still name the one it was taken to be: they name it, or they name nothing a label could show. */
    static boolean sameCity(String where, List<String> decided) {
        boolean anyWord = false;
        for (String w : decided) {
            String word = COUNTRY_SHORT.getOrDefault(w.strip().toLowerCase(Locale.ROOT), w.strip());
            if (word.length() < 3) continue;
            anyWord = true;
            if (has(where, word)) return true;
        }
        return !anyWord;
    }

    /** A settlement of the name, as Wikidata lists it: its item, its label, its population, and the labels of what it lies within, its country last. */
    record Candidate(String item, String label, double people, String within) {
        Candidate(String item, double people, String within) { this(item, "", people, within); }
    }

    /** A name as a person writes it against a label as Wikidata writes it: case and accents aside. */
    static boolean sameName(String a, String b) {
        if (a == null || b == null) return false;
        String x = Normalizer.normalize(a, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).strip(), y = Normalizer.normalize(b, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).strip();
        return x.equals(y);
    }

    /** Short forms a person writes for a country, and the label Wikidata gives it. */
    static final Map<String, String> COUNTRY_SHORT = Map.of("us", "United States", "usa", "United States", "u.s.", "United States", "uk", "United Kingdom", "gb", "United Kingdom", "fr", "France", "de", "Germany", "jp", "Japan", "ca", "Canada", "au", "Australia");

    /**
     * Which settlement of the name the person means: the most populous whose region or country one of the {@code within} words names (the
     * other places they decided, and where their record puts them); the most populous of all when none says. Cambridge with Massachusetts
     * is the one in Middlesex County, not the one in Ontario, which has more people.
     */
    static Candidate choose(List<Candidate> candidates, List<String> within) { return choose(candidates, "", within); }

    /**
     * As above, for the name asked: the name search matches by prefix, so "San Jose" brings the San Francisco Bay Area and San Jose de
     * Buenavista too; a settlement whose label is the name itself comes before one whose label only begins with it, whatever the people.
     */
    static Candidate choose(List<Candidate> candidates, String name, List<String> within) {
        List<Candidate> cs = new ArrayList<>(candidates);
        cs.sort(Comparator.comparingDouble((Candidate x) -> -x.people()));
        if (!name.isBlank()) {
            List<Candidate> exact = new ArrayList<>();
            for (Candidate x : cs) if (sameName(x.label(), name)) exact.add(x);
            if (!exact.isEmpty()) cs = exact;
        }
        for (String w : within) {   // the words in the order given: the places the person decided before where their record puts them
            String word = COUNTRY_SHORT.getOrDefault(w.strip().toLowerCase(Locale.ROOT), w.strip());
            if (word.length() < 3) continue;
            for (Candidate x : cs) if (has(x.within(), word)) return x;
        }
        return cs.isEmpty() ? null : cs.get(0);
    }

    /** An administrative division's label, which names no town an opening is in: a canton, an arrondissement, a county, a diocese. */
    static final Pattern DIVISION = Pattern.compile("(?iu)\\b(canton|arrondissement|district|county|diocese|archdiocese|eparchy|archeparchy|deanery|region|department|departmental|urban area|metropolitan|metropolis|agglomeration|communauté|métropole|intercommunal|prefecture)\\b");

    /** What Wikidata calls a city or a town, as the classes its places are instances of: a region, a county or a metropolitan area is neither. */
    static final String CITY_OR_TOWN = "wd:Q515 wd:Q3957";

    /**
     * Wikidata, in two queries: the city is the most populous settlement of that name (its entity search, then the settlements among the
     * matches); the towns are every place with a population of {@link #NEAR_MIN_PEOPLE} or more whose coordinates lie within the
     * kilometres, largest first, each counted by where its centre is, whatever its size: Boston, across the river from Cambridge,
     * Massachusetts, and five times its people, is near it. What the size rule of before kept out still stays out: a place more than twice
     * as populous as the city that Wikidata does not call a city or a town is a region, a county or a metropolitan area around it (Greater
     * Boston, Auvergne-Rhône-Alpes), and so is a label of a division at any size; and the city itself.
     */
    static Towns nearFromWikidata(String city, List<String> within, int km) {
        String name = city.replace("\"", "").strip();
        String first = "SELECT ?c ?cLabel ?cpop (GROUP_CONCAT(DISTINCT ?aLabel; separator=\"; \") AS ?within) ?countryLabel (SAMPLE(?loc) AS ?at) WHERE { SERVICE wikibase:mwapi { bd:serviceParam wikibase:endpoint \"www.wikidata.org\"; "
                + "wikibase:api \"EntitySearch\"; mwapi:search \"" + name + "\"; mwapi:language \"en\" . ?c wikibase:apiOutputItem mwapi:item . } ?c wdt:P1082 ?cpop . ?c wdt:P625 ?loc . ?c wdt:P31/wdt:P279* wd:Q486972 . "
                + "OPTIONAL { ?c wdt:P17 ?country } OPTIONAL { ?c wdt:P131+ ?a . ?a rdfs:label ?aLabel FILTER(lang(?aLabel) = \"en\") } SERVICE wikibase:label { bd:serviceParam wikibase:language \"en\". } } "
                + "GROUP BY ?c ?cLabel ?cpop ?countryLabel ORDER BY DESC(?cpop) LIMIT 8";
        String body = CareerSources.get("https://query.wikidata.org/sparql?format=json&query=" + URLEncoder.encode(first, StandardCharsets.UTF_8));
        if (body == null) return new Towns("", List.of());
        List<Candidate> candidates = new ArrayList<>();
        Map<String, double[]> at = new HashMap<>();   // where each candidate is: "Point(lon lat)"
        try {
            for (JsonNode r : M.readTree(body).path("results").path("bindings")) {
                String item = r.path("c").path("value").asText("").replaceFirst(".*/", ""), country = r.path("countryLabel").path("value").asText("").strip(), in = r.path("within").path("value").asText("").strip();
                if (item.matches("Q\\d+")) candidates.add(new Candidate(item, r.path("cLabel").path("value").asText(""), r.path("cpop").path("value").asDouble(0), in + (country.isEmpty() ? "" : "; " + country)));
                Matcher pt = Pattern.compile("Point\\(([-\\d.]+) ([-\\d.]+)\\)").matcher(r.path("at").path("value").asText(""));
                if (pt.find()) try { at.put(item, new double[]{Double.parseDouble(pt.group(2)), Double.parseDouble(pt.group(1))}); } catch (NumberFormatException ignored) { }
            }
        } catch (IOException e) { return new Towns("", List.of()); }
        Candidate chosen = choose(candidates, name, within);
        if (chosen == null) return new Towns("", List.of());
        double[] centre = at.getOrDefault(chosen.item(), new double[]{0, 0});
        long twice = Math.round(2 * chosen.people());   // only a place larger than this is asked whether it is a city or a town: one class walk for a few places, not for two hundred
        String q = "SELECT DISTINCT ?t ?tLabel ?pop ?town WHERE { wd:" + chosen.item() + " wdt:P625 ?loc . SERVICE wikibase:around { ?t wdt:P625 ?tloc . bd:serviceParam wikibase:center ?loc . bd:serviceParam wikibase:radius \"" + Math.max(1, km) + "\" . } "
                + "?t wdt:P1082 ?pop . FILTER(?pop >= " + NEAR_MIN_PEOPLE + ") FILTER(?t != wd:" + chosen.item() + ") "
                + (twice > 0 ? "BIND(IF(?pop > " + twice + ", EXISTS { ?t wdt:P31/wdt:P279* ?k . VALUES ?k { " + CITY_OR_TOWN + " } }, true) AS ?town) " : "")
                + "SERVICE wikibase:label { bd:serviceParam wikibase:language \"en,fr,de,es,pt,it,ja,nl\". } } ORDER BY DESC(?pop) LIMIT 200";
        body = CareerSources.get("https://query.wikidata.org/sparql?format=json&query=" + URLEncoder.encode(q, StandardCharsets.UTF_8));
        List<String> out = new ArrayList<>();
        if (body == null) return new Towns(chosen.within(), out, centre[0], centre[1]);
        try {
            for (JsonNode r : M.readTree(body).path("results").path("bindings")) {
                String label = r.path("tLabel").path("value").asText("").strip();
                double pop = r.path("pop").path("value").asDouble(0);
                boolean town = r.path("town").path("value").asText("").equals("true");   // a city or a town by Wikidata's classes; an answer without it keeps a larger place out, as before
                if (label.isEmpty() || label.matches("Q\\d+") || label.equalsIgnoreCase(name) || (chosen.people() > 0 && pop > 2 * chosen.people() && !town) || DIVISION.matcher(label).find()) continue;
                if (out.stream().noneMatch(x -> x.equalsIgnoreCase(label))) out.add(label);
                if (out.size() >= 100) break;
            }
        } catch (IOException ignored) { }
        // what the city lies within, for the log: the nearest division first, the country last, as Wikidata gave them
        return new Towns(chosen.within().replace("; ", ", "), out, centre[0], centre[1]);
    }

    /** The long form of each executive abbreviation and back, so "CTO" finds "Chief Technology Officer" and the other way round. */
    static final Map<String, String> LONG = Map.ofEntries(Map.entry("cto", "Chief Technology Officer"), Map.entry("ceo", "Chief Executive Officer"), Map.entry("coo", "Chief Operating Officer"),
            Map.entry("cfo", "Chief Financial Officer"), Map.entry("cpo", "Chief Product Officer"), Map.entry("cio", "Chief Information Officer"), Map.entry("ciso", "Chief Information Security Officer"),
            Map.entry("cmo", "Chief Marketing Officer"), Map.entry("cro", "Chief Revenue Officer"), Map.entry("cso", "Chief Strategy Officer"), Map.entry("cdo", "Chief Data Officer"),
            Map.entry("vp", "Vice President"), Map.entry("svp", "Senior Vice President"), Map.entry("evp", "Executive Vice President"), Map.entry("gm", "General Manager"));
    /** Words that are no role on their own: "Executive" is in Account Executive, "Officer" in every C-level title, "Chief" in Chief of Staff. */
    static final Set<String> NOT_A_ROLE = Set.of("executive", "officer", "chief", "senior", "staff", "level");

    /** The model's title words, with each abbreviation's long form beside it and the words that are no role on their own left out. */
    static List<String> titleWords(List<String> words) {
        List<String> out = new ArrayList<>();
        for (String w : words) {
            String k = w.toLowerCase(Locale.ROOT).strip();
            if (NOT_A_ROLE.contains(k)) continue;
            if (out.stream().noneMatch(x -> x.equalsIgnoreCase(w))) out.add(w);
            String lng = LONG.get(k);
            if (lng != null && out.stream().noneMatch(x -> x.equalsIgnoreCase(lng))) out.add(lng);
            for (Map.Entry<String, String> e : LONG.entrySet()) if (e.getValue().equalsIgnoreCase(k) && out.stream().noneMatch(x -> x.equalsIgnoreCase(e.getKey()))) out.add(e.getKey().toUpperCase(Locale.ROOT));
        }
        return out;
    }

    /** The person sets the cut's words themselves: each field a list in their words, separated by commas; the cut is theirs from then on. */
    public static Cut set(LibraryStore store, Career.Seeker s, Map<String, String> f) throws IOException {
        Cut was = kept(store, s);
        if (was == null) was = Cut.NONE;
        String remote = f.containsKey("remote") ? f.get("remote").strip().toLowerCase(Locale.ROOT) : was.remote();
        if (!List.of("only", "fine", "no", "").contains(remote)) throw new IllegalArgumentException("Remote is one of only, fine, no, or empty for not decided.");
        Cut c = new Cut(field(f, "titles", was.titles()), field(f, "titles_also", was.titlesAlso()), field(f, "titles_not", was.titlesNot()), field(f, "areas", was.areas()), field(f, "places", was.places()), remote, field(f, "remote_where", was.remoteWhere()),
                field(f, "prefer_titles", was.preferTitles()), field(f, "prefer_places", was.preferPlaces()), count(f, "max_people", was.maxPeople()), count(f, "min_people", was.minPeople()), "you", LocalDate.now().toString(), basis(store, s),
                was.nearby(), was.nearKm());
        if (f.containsKey("places")) c = withNearby(store, s, c.near(List.of(), 0), f.containsKey("near_km") ? count(f, "near_km", NEAR_KM) : radiusWanted(store, s));
        save(store, s, c);
        Career.log(store, s, "You set the search's cut: " + summary(c) + ".");
        return c;
    }

    // ---------------------------------------------------------------- applying the cut

    /** Whether an opening passes, and why not when it does not; whether its place is unknown; what it mentions of the things kept out. */
    public record Verdict(boolean passes, String why, boolean placeUnknown, List<String> mentions) { }

    public static Verdict judge(Cut c, Opening o, List<String> keepOut) { return judge(c, o, keepOut, null); }

    /** As above, with what the opening's words mention of the things kept out, as kept with it; null reads them from its words. */
    public static Verdict judge(Cut c, Opening o, List<String> keepOut, List<String> mentioned) { return judge(c, o, keepOut, mentioned, null, 0); }

    /** The company's size as the library knows it, and how many openings its own board carries; null and 0 when not known. */
    public record Company(CareerSize.People people, int openings) { }

    /** As above, with what the library knows of the company: a decided size cuts a company known to be far outside it. */
    public static Verdict judge(Cut c, Opening o, List<String> keepOut, List<String> mentioned, Company company, int unused) {
        for (String k : keepOut) {
            if (has(o.company(), k) || CareerSearch.key(o.company()).equals(CareerSearch.key(k))) return new Verdict(false, "the company, " + o.company() + ", is one you keep out, or where you work now", false, List.of());
            if (has(o.title(), k)) return new Verdict(false, "its title has " + k + ", which you keep out", false, List.of());
        }
        for (String w : c.titlesNot()) if (has(o.title(), w)) return new Verdict(false, "its title has " + w, false, List.of());
        if (!c.titles().isEmpty() && c.titles().stream().noneMatch(w -> has(o.title(), w))) return new Verdict(false, "its title has none of the words you want", false, List.of());
        if (!c.titlesAlso().isEmpty() && c.titlesAlso().stream().noneMatch(w -> has(o.title(), w))) return new Verdict(false, "its title has none of the second words you want", false, List.of());
        if (!c.areas().isEmpty() && !c.titles().isEmpty() && c.titles().stream().filter(w -> has(o.title(), w)).allMatch(w -> GENERIC.matcher(w).matches())
                && c.areas().stream().noneMatch(a -> has(o.title(), a))) return new Verdict(false, "its title gives a rank without the area you work in", false, List.of());
        String loc = o.location();
        boolean unknown = loc.isBlank() && !o.remote();
        boolean inPlace = c.places().stream().anyMatch(p -> has(loc, p)) || c.nearby().stream().anyMatch(t -> has(loc, t));
        boolean remoteHere = o.remote() && (c.remoteWhere().isEmpty() || bareRemote(loc) || c.remoteWhere().stream().anyMatch(w -> has(loc, w)));
        if (!unknown) {
            switch (c.remote()) {
                case "only" -> { if (!remoteHere) return new Verdict(false, o.remote() ? "it is remote from a place you did not name" : "it is not remote", false, List.of()); }
                case "no" -> { if (!c.places().isEmpty() && !inPlace) return new Verdict(false, "it is not in a place you want", false, List.of()); if (c.places().isEmpty() && o.remote() && bareRemote(loc)) return new Verdict(false, "it is remote", false, List.of()); }
                default -> { if (!c.places().isEmpty() && !inPlace && !(c.remote().equals("fine") && remoteHere)) return new Verdict(false, "it is not in a place you want", false, List.of()); }
            }
        }
        if (company != null) {
            CareerSize.People p = company.people();
            if (c.maxPeople() > 0 && p != null && p.low() > 2L * c.maxPeople()) return new Verdict(false, "its company has " + p.words() + " (" + p.source() + "), more than twice the " + String.format("%,d", c.maxPeople()) + " you decided", false, List.of());
            if (c.maxPeople() > 0 && c.maxPeople() <= 200 && company.openings() >= 100) return new Verdict(false, "its own board lists " + company.openings() + " openings, more than a company of under " + String.format("%,d", c.maxPeople() + 1) + " people has", false, List.of());
            if (c.minPeople() > 0 && p != null && p.high() * 2L < c.minPeople()) return new Verdict(false, "its company has " + p.words() + " (" + p.source() + "), fewer than half the " + String.format("%,d", c.minPeople()) + " you decided", false, List.of());
        }
        return new Verdict(true, "", unknown, mentioned != null ? mentioned : mentions(o.text(), keepOut));
    }

    /** What an opening's words mention of the things kept out, in its first 3,000 letters, where a company says what it is. */
    public static List<String> mentions(String text, List<String> keepOut) {
        List<String> out = new ArrayList<>();
        String head = text == null ? "" : text.length() > 3000 ? text.substring(0, 3000) : text;
        for (String k : keepOut) if (has(head, k)) out.add(k);
        return out;
    }

    /** Whether a place says only that the work is remote: "Remote", "Remote (flexible)", not "Remote, EMEA". */
    static boolean bareRemote(String loc) {
        String rest = (loc == null ? "" : loc).toLowerCase(Locale.ROOT).replaceAll("\\b(remote|first|friendly|flexible|option|ok|anywhere|fully|100%|work from home)\\b", "").replaceAll("[^\\p{L}\\p{N}]+", "");
        return rest.isEmpty();
    }

    /** Whether {@code text} has {@code phrase} as words: "VP" in "VP, Engineering", not in "MVP"; case aside. */
    static boolean has(String text, String phrase) {
        if (text == null || phrase == null || phrase.isBlank()) return false;
        return PATTERNS.computeIfAbsent(phrase.strip().toLowerCase(Locale.ROOT), p -> Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(p) + "(?![\\p{L}\\p{N}])", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE))
                .matcher(text).find();
    }
    private static final Map<String, Pattern> PATTERNS = new ConcurrentHashMap<>();
    /** Rank words that name no area, which every function has: a VP of anything. */
    static final Pattern GENERIC = Pattern.compile("(?i)(vp|svp|evp|vice president|senior vice president|executive vice president|director|head of|head|lead|manager|principal|staff|senior|gm|general manager)");

    /** Words of a role that sits beside an executive rather than holds the power: a field CTO, the office of the CEO, an associate, a deputy. */
    static final Pattern BESIDE = Pattern.compile("(?i)\\b(field|account|office of|office|associate|analyst|assistant|deputy|coach|chief of staff|advis[eo]r to)\\b");

    /**
     * How near an opening comes to what the person wants, for the order only, never shown: the companies they named and kept first, then
     * the titles and places their guesses lean to, then the fresher; something kept out that the opening mentions sends it down.
     */
    static int rank(Cut c, Opening o, Verdict v, Set<String> named, Set<String> kept, LocalDate today) { return rank(c, o, v, named, kept, today, null); }

    static int rank(Cut c, Opening o, Verdict v, Set<String> named, Set<String> kept, LocalDate today, Company company) { return rank(c, o, v, named, kept, today, company, Set.of()); }

    /** As above; {@code field} is the employers in the field the person decided, by the library's research or read as worth approaching: before the rest. */
    static int rank(Cut c, Opening o, Verdict v, Set<String> named, Set<String> kept, LocalDate today, Company company, Set<String> field) {
        int r = 0;
        if (field.contains(CareerSearch.key(o.company()))) r += 3;
        if (company != null && company.people() != null && c.maxPeople() > 0 && company.people().high() > c.maxPeople()) r -= 3;   // may be over the size decided: after those within it
        String k = CareerSearch.key(o.company());
        if (named.contains(k)) r += 4;
        if (kept.contains(k)) r += 3;
        r += 2 * (int) Math.min(3, c.preferTitles().stream().filter(w -> has(o.title(), w)).count());
        if (c.preferPlaces().stream().anyMatch(p -> has(o.location(), p))) r += 1;
        if (!v.mentions().isEmpty()) r -= 5;
        if (BESIDE.matcher(o.title()).find()) r -= 4;   // a role beside an executive, without the power: read after the executives
        if (!o.posted().isBlank()) {
            try {
                long age = today.toEpochDay() - LocalDate.parse(o.posted()).toEpochDay();
                if (age <= 30) r += 1; else if (age > 60) r -= 1;
            } catch (Exception ignored) { }
        } else if (!o.postedNote().isBlank()) r -= 1;
        return r;
    }

    /** The passing openings in order: nearest first, then the newest; ties by title. */
    /**
     * How closely an opening matches the role and the place, as the person decided them or, until they decide, as the library guesses them:
     * 0 when both match, 1 when one of the two does, 2 when neither. A side nothing is known about counts as matched. For the page's tiers only;
     * it never drops an opening.
     */
    public static int tier(Cut c, Opening o) {
        List<String> titleWords = new ArrayList<>(c.titles()); titleWords.addAll(c.titlesAlso()); titleWords.addAll(c.preferTitles());
        List<String> placeWords = new ArrayList<>(c.places()); placeWords.addAll(c.preferPlaces()); placeWords.addAll(c.nearby());
        boolean title = titleWords.isEmpty() || titleWords.stream().anyMatch(w -> has(o.title(), w));
        boolean remoteOk = o.remote() && (c.remote().equals("fine") || c.remote().equals("only"))
                && (c.remoteWhere().isEmpty() || bareRemote(o.location()) || c.remoteWhere().stream().anyMatch(w -> has(o.location(), w)));
        boolean place = (placeWords.isEmpty() && c.remote().isEmpty()) || placeWords.stream().anyMatch(p -> has(o.location(), p)) || remoteOk;
        return (title ? 0 : 1) + (place ? 0 : 1);
    }

    static List<Opening> ordered(List<Opening> passing, Map<String, Integer> rank) {
        List<Opening> xs = new ArrayList<>(passing);
        xs.sort(Comparator.<Opening>comparingInt(o -> -rank.getOrDefault(o.id(), 0)).thenComparing(Opening::posted, Comparator.reverseOrder()).thenComparing(Opening::title));
        return xs;
    }

    /** The openings the library reads first: in order, at most {@code perCompany} from one company before a further one from any. */
    static List<Opening> readingOrder(List<Opening> ordered, int n, int perCompany) {
        List<Opening> first = new ArrayList<>(), rest = new ArrayList<>();
        Map<String, Integer> per = new HashMap<>();
        for (Opening o : ordered) {
            int k = per.merge(CareerSearch.key(o.company()), 1, Integer::sum);
            (k <= perCompany ? first : rest).add(o);
        }
        first.addAll(rest);
        return first.subList(0, Math.min(n, first.size()));
    }

    /** The cut in a sentence's parts, for the log and the page. */
    public static String summary(Cut c) {
        List<String> parts = new ArrayList<>();
        if (!c.titles().isEmpty()) {
            List<String> ranks = new ArrayList<>(), roles = new ArrayList<>();
            for (String w : shortForms(c.titles())) (GENERIC.matcher(w).matches() && !c.areas().isEmpty() ? ranks : roles).add(w);
            String t = roles.isEmpty() ? "" : "titles with " + or(roles);
            if (!ranks.isEmpty()) t += (t.isEmpty() ? "titles with " : ", or ") + or(ranks) + " of " + or(c.areas());
            parts.add(t + (c.titlesAlso().isEmpty() ? "" : (ranks.isEmpty() ? " and with " : ", and with ") + or(shortForms(c.titlesAlso()))));
        }
        else if (!c.titlesAlso().isEmpty()) parts.add("titles with " + or(c.titlesAlso()));
        if (!c.titlesNot().isEmpty()) parts.add("titles without " + or(c.titlesNot()));
        if (!c.places().isEmpty()) parts.add("in " + or(c.places()) + (c.nearby().isEmpty() ? "" : ", or one of " + c.nearby().size() + " towns within " + c.nearKm() + " km"));
        switch (c.remote()) {
            case "only" -> parts.add("remote only" + (c.remoteWhere().isEmpty() ? "" : ", based in " + or(c.remoteWhere())));
            case "fine" -> parts.add("remote counts" + (c.remoteWhere().isEmpty() ? "" : " when based in " + or(c.remoteWhere())));
            case "no" -> parts.add("not remote");
            default -> { }
        }
        if (c.maxPeople() > 0) parts.add("at companies of up to " + String.format("%,d", c.maxPeople()) + " people, where the library knows the size");
        if (c.minPeople() > 0) parts.add("at companies of at least " + String.format("%,d", c.minPeople()) + " people, where the library knows the size");
        if (parts.isEmpty()) parts.add("every opening passes, since nothing about titles or places is decided yet");
        if (!c.preferTitles().isEmpty() || !c.preferPlaces().isEmpty())
            parts.add("first in the order: " + Career.and(new ArrayList<>(List.of(c.preferTitles().isEmpty() ? "" : "titles with " + or(c.preferTitles()), c.preferPlaces().isEmpty() ? "" : "places in " + or(c.preferPlaces())).stream().filter(x -> !x.isEmpty()).toList())));
        return String.join("; ", parts);
    }

    /** The words as the person reads them: a long form whose abbreviation is also there is left out of the sentence. */
    static List<String> shortForms(List<String> xs) {
        List<String> out = new ArrayList<>();
        for (String x : xs) { boolean isLong = LONG.entrySet().stream().anyMatch(e -> e.getValue().equalsIgnoreCase(x) && xs.stream().anyMatch(y -> y.equalsIgnoreCase(e.getKey()))); if (!isLong) out.add(x); }
        return out;
    }

    static String or(List<String> xs) {
        if (xs.size() <= 1) return xs.isEmpty() ? "" : xs.get(0);
        return String.join(", ", xs.subList(0, xs.size() - 1)) + " or " + xs.get(xs.size() - 1);
    }

    private static List<String> list(JsonNode a) { List<String> out = new ArrayList<>(); for (JsonNode x : a) if (!x.asText("").isBlank()) out.add(x.asText().strip()); return out; }

    /** The model's words, kept as words: at most {@code max}, none blank, none twice, none longer than a short phrase. */
    private static List<String> words(JsonNode a, int max) {
        Map<String, String> out = new LinkedHashMap<>();
        if (a.isArray()) for (JsonNode x : a) { String w = x.asText("").strip(); if (!w.isEmpty() && w.length() <= 40 && out.size() < max) out.putIfAbsent(w.toLowerCase(Locale.ROOT), w); }
        return new ArrayList<>(out.values());
    }

    private static List<String> field(Map<String, String> f, String key, List<String> was) {
        if (!f.containsKey(key)) return was;
        List<String> out = new ArrayList<>();
        for (String w : f.get(key).split("[,\\n]")) if (!w.isBlank() && !out.contains(w.strip())) out.add(w.strip());
        return out;
    }

    private static int count(Map<String, String> f, String key, int was) {
        if (!f.containsKey(key)) return was;
        String v = f.get(key).replaceAll("[^0-9]", "");
        return v.isEmpty() ? 0 : Integer.parseInt(v.length() > 7 ? v.substring(0, 7) : v);
    }

    private static void put(ObjectNode o, String k, List<String> xs) { ArrayNode a = o.putArray(k); for (String x : xs) a.add(x); }
}
