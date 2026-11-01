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
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * How seats for this occupation in this place are filled, so the search looks in the right places: a software engineer's seats are posted
 * openly and on the venture boards, a nurse's after a licence and through hospitals and agencies, a lawyer's through the bar's register and
 * referral, a postdoc's by appointment rounds at the labs. The shape is drawn by the model from the role and the place the person decided,
 * else the title they hold and the place their record puts them in; the code knows no occupation table. It is kept at search/shape.json
 * and drawn again when the role, the place or the kind of employer changes. The person's own word on it ({@link #tell}) outranks the
 * model's. Without a model, or when it does not answer, the shape is the library's default, which reads everything as the search always did.
 */
public final class CareerShape {
    private CareerShape() { }

    static final ObjectMapper M = new ObjectMapper();
    static final int VERSION = 1;
    /** The ways seats are filled, the words the shape uses. */
    public static final List<String> HOWS = List.of("posted", "niche board", "referral", "licence", "appointment");
    /** The kinds of board the search knows how to read, and the kinds it only knows to name. */
    public static final List<String> BOARD_KINDS = List.of("venture portfolio boards", "hacker news who is hiring", "usajobs", "academic boards", "agency boards", "professional association boards", "public sector boards", "none");
    public static final String READS_PORTFOLIO = "venture portfolio boards", READS_HN = "hacker news who is hiring", READS_USAJOBS = "usajobs";
    static final Map<String, String> HOW_WORDS = Map.of("posted", "through openly posted openings", "niche board", "through a board for the trade", "referral", "through people who know people",
            "licence", "after a licence or registration", "appointment", "through appointment rounds");

    /**
     * The shape: how seats are filled (words from {@link #HOWS}), the kinds of board worth reading ({@link #BOARD_KINDS}), where such employers
     * are listed, what the person must have, what shows an employer needs someone now, a summary for the person; the occupation, place and
     * employer kind it was drawn for; by whom ("the model", "you", "the library's default"); the person's own words when given; the date.
     */
    public record Shape(List<String> hows, List<String> boards, String lists, String need, String now, String summary, String occupation, String place, String employerKind, String by, String told, String at) {
        public boolean posted() { return hows.contains("posted"); }
        public boolean reads(String boardKind) { return boards.contains(boardKind); }
        public boolean has(String how) { return hows.contains(how); }
        /** The model's or the person's shape, not the library's fallback. */
        public boolean drawn() { return !by.equals("the library's default"); }
    }

    static Path file(LibraryStore store, Career.Seeker s) { return s.dir(store).resolve("search").resolve("shape.json"); }

    /** The shape as kept, or null when none was drawn yet. */
    public static Shape kept(LibraryStore store, Career.Seeker s) throws IOException {
        Path f = file(store, s);
        if (!Files.isRegularFile(f)) return null;
        JsonNode j = M.readTree(f.toFile());
        return new Shape(list(j.path("how")), list(j.path("boards")), j.path("lists").asText(""), j.path("need").asText(""), j.path("now").asText(""), j.path("summary").asText(""),
                j.path("work").asText(""), j.path("place").asText(""), j.path("employer_kind").asText(""), j.path("by").asText("the model"), j.path("told").asText(""), j.path("at").asText(""));
    }

    /** The shape for the person as things stand: the kept one when it was drawn for the same role, place and employer kind, else drawn now and kept. */
    public static Shape current(LibraryStore store, Career.Seeker s) throws IOException {
        String occupation = occupation(store, s), place = place(store, s), employerKind = employerKind(store, s);
        Shape k = kept(store, s);
        if (k != null && k.occupation().equals(occupation) && k.place().equals(place) && k.employerKind().equals(employerKind) && (k.drawn() || occupation.isEmpty())) return k;
        return keep(store, s, draw(occupation, place, employerKind, k == null ? "" : k.told()));
    }

    /** The person's own word on how seats are filled for them; the shape is drawn again with it, and it outranks what the model knows. A sentence back. */
    public static String tell(LibraryStore store, Career.Seeker s, String words) throws IOException {
        String w = words == null ? "" : words.strip();
        if (w.isEmpty()) { Shape sh = current(store, s); return text(sh); }
        Shape sh = keep(store, s, draw(occupation(store, s), place(store, s), employerKind(store, s), w));
        Career.log(store, s, "You said how seats are filled for you: " + w);
        return "Kept, in your words: " + w + "\n" + text(sh);
    }

    static Shape keep(LibraryStore store, Career.Seeker s, Shape sh) throws IOException {
        ObjectNode o = M.createObjectNode();
        ArrayNode h = o.putArray("how"); sh.hows().forEach(h::add);
        ArrayNode b = o.putArray("boards"); sh.boards().forEach(b::add);
        o.put("lists", sh.lists()).put("need", sh.need()).put("now", sh.now()).put("summary", sh.summary()).put("work", sh.occupation()).put("place", sh.place()).put("employer_kind", sh.employerKind())
         .put("by", sh.by()).put("told", sh.told()).put("at", sh.at()).put("version", VERSION);
        Files.createDirectories(file(store, s).getParent());
        Files.writeString(file(store, s), M.writerWithDefaultPrettyPrinter().writeValueAsString(o), StandardCharsets.UTF_8);
        return sh;
    }

    static final int PHRASE = 60;   // a decided want longer than this is a sentence of the person's, not a name: the distilled form serves instead

    /** The role the person decided when it is a name, else the title they hold; "" when neither is known. */
    static String occupation(LibraryStore store, Career.Seeker s) throws IOException {
        CareerCompanies.Want role = CareerCompanies.wantsOf(store, s).get("role");
        String latest = Career.latestTitle(store, s);
        if (role != null && role.decided() && !role.text().isBlank() && (role.text().strip().length() <= PHRASE || latest.isEmpty())) return role.text().strip();
        return latest;
    }

    /** The place: the words the cut distilled from what the person decided ("Belo Horizonte", not "I want to work in Belo Horizonte"), else the decided want, else where the record puts them. */
    static String place(LibraryStore store, Career.Seeker s) throws IOException {
        CareerCut.Cut cut = CareerCut.kept(store, s);
        if (cut != null && !cut.places().isEmpty()) return String.join(", ", cut.places());
        CareerCompanies.Want p = CareerCompanies.wantsOf(store, s).get("place");
        String record = Career.location(store, s);
        if (p != null && p.decided() && !p.text().isBlank() && (p.text().strip().length() <= PHRASE || record.isEmpty())) return p.text().strip();
        return record;
    }

    static String employerKind(LibraryStore store, Career.Seeker s) throws IOException {
        CareerCompanies.Want k = CareerCompanies.wantsOf(store, s).get("kind of employer");
        return k != null && k.decided() ? k.text().strip() : "";
    }

    /** The library's default: everything the search always read, so a missing or silent model changes nothing. */
    static Shape fallback(String occupation, String place, String employerKind, String told) {
        return new Shape(List.of("posted", "niche board"), List.of(READS_PORTFOLIO, READS_HN, READS_USAJOBS), "", "", "", "", occupation, place, employerKind, "the library's default", told, LocalDate.now().toString());
    }

    /** The shape drawn by the model for the occupation and place, the person's own words outranking what it knows; the default when it does not answer. */
    static Shape draw(String occupation, String place, String employerKind, String told) {
        if (occupation.isEmpty() && told.isEmpty()) return fallback(occupation, place, employerKind, told);
        ArrayNode msgs = M.createArrayNode();
        StringBuilder p = new StringBuilder("A person is looking for work" + (occupation.isEmpty() ? "" : " as " + occupation) + (place.isEmpty() ? "" : " in " + place) + (employerKind.isEmpty() ? "" : ", at " + employerKind)
                + ". " + (place.isEmpty() ? "The place is not known yet, so answer for this work in general and name no country's bodies, boards or registers. " : "") + "Say how seats for this work" + (place.isEmpty() ? "" : " in this place") + " are usually filled, so that a search looks in the right places and skips the wrong ones. Answer with a JSON object and nothing else:\n"
                + "{\"how\": [the ways seats are filled, from: posted, niche board, referral, licence, appointment; the main ones only],\n"
                + " \"boards\": [the kinds of board worth reading for this work, from: " + String.join(", ", BOARD_KINDS) + "; none when boards do not matter],\n"
                + " \"lists\": \"where such employers in this place are listed: the registers, directories, associations, rankings; one sentence\",\n"
                + " \"need\": \"what the person must have to be hired: " + (place.isEmpty() ? "the kind of licence or registration in general words, since the place is not known and the body that issues it depends on it; name no body and no jurisdiction" : "the licence or registration by its name and the body that issues it") + ", the right to work; or: nothing beyond the work itself\",\n"
                + " \"now\": \"what shows that an employer needs someone now for this work: an opening, a grant, a new office, a round; one sentence\",\n"
                + " \"summary\": \"two or three plain sentences for the person, in the second person: how seats are filled for them, what they need, where to look\"}\n"
                + "The words: posted means employers post openings openly, on their own pages and the big job sites. niche board means a board for this trade carries most seats. "
                + "referral means most seats go through people who know people. licence means a licence, registration or examination gates entry. appointment means seats are filled "
                + "by rounds, calls, competitions or appointment, as in academia, public service and the courts. venture portfolio boards and hacker news who is hiring carry software "
                + "and startup seats; usajobs carries United States federal seats; name the other kinds only when they carry seats for this work.");
        if (!told.isEmpty()) p.append("\n\nThe person's own word on how seats are filled for them, which outranks what you know:\n").append(Fence.rule("the person's words")).append('\n').append(Fence.wrap("THEIR WORDS", told));
        msgs.addObject().put("role", "user").put("content", p.toString());
        String said;
        try { said = Career.DRIVE.get().classify(msgs, 600); } catch (RuntimeException e) { said = null; }
        JsonNode j = CareerInterview.parseObject(said);
        List<String> hows = new ArrayList<>(), boards = new ArrayList<>();
        for (JsonNode x : j.path("how")) { String w = x.asText("").strip().toLowerCase(Locale.ROOT); if (HOWS.contains(w) && !hows.contains(w)) hows.add(w); }
        for (JsonNode x : j.path("boards")) { String w = x.asText("").strip().toLowerCase(Locale.ROOT); if (BOARD_KINDS.contains(w) && !boards.contains(w)) boards.add(w); }
        if (hows.isEmpty()) return fallback(occupation, place, employerKind, told);
        boards.remove("none");
        return new Shape(hows, boards, clip(j.path("lists").asText("")), clip(j.path("need").asText("")), clip(j.path("now").asText("")), clip(j.path("summary").asText("")),
                occupation, place, employerKind, told.isEmpty() ? "the model" : "you", told, LocalDate.now().toString());
    }

    /** "a" or "an" before the occupation, so the sentences read. */
    static String a(String occupation) { return occupation.isEmpty() ? "" : (occupation.matches("(?i)^[aeiou].*") ? "an " : "a ") + occupation; }

    static String clip(String s) { String t = s == null ? "" : s.strip().replaceAll("\\s+", " "); return t.length() > 600 ? t.substring(0, 600) : t; }
    static List<String> list(JsonNode a) { List<String> out = new ArrayList<>(); for (JsonNode x : a) if (x.isTextual() && !x.asText().isBlank()) out.add(x.asText()); return out; }

    /** What the search reads for this shape, and what it skips, in a sentence. */
    public static String reads(Shape sh) {
        List<String> reads = new ArrayList<>(), skips = new ArrayList<>();
        reads.add("the openings of the employers on the list");
        reads.add("the lists where such employers are listed");
        (sh.posted() ? reads : skips).add("the hiring sites");
        (sh.reads(READS_PORTFOLIO) ? reads : skips).add("the venture portfolio boards");
        (sh.reads(READS_HN) ? reads : skips).add("Hacker News's Who is hiring");
        if (CareerSources.usaJobsReady()) (sh.reads(READS_USAJOBS) ? reads : skips).add("USAJobs");
        List<String> named = new ArrayList<>();
        for (String k : sh.boards()) if (!List.of(READS_PORTFOLIO, READS_HN, READS_USAJOBS).contains(k)) named.add(k);
        return "The search reads " + Career.and(reads) + (skips.isEmpty() ? "" : ", and skips " + Career.and(skips) + ", which list other trades' jobs")
                + (named.isEmpty() ? "" : "; the " + Career.and(named) + " it has no reader for yet, so it looks for them among the lists") + ".";
    }

    /** The shape as sentences for the person. */
    public static String text(Shape sh) {
        if (sh == null) return "The library has not yet worked out how jobs like yours are filled; the next search does.";
        StringBuilder b = new StringBuilder();
        if (!sh.drawn()) b.append("The library could not work out how jobs are filled for ").append(sh.occupation().isEmpty() ? "you" : a(sh.occupation())).append(", so the search reads everything it can. ");
        else {
            List<String> ways = new ArrayList<>(); for (String h : sh.hows()) ways.add(HOW_WORDS.getOrDefault(h, h));
            b.append("Jobs").append(sh.occupation().isEmpty() ? "" : " for " + a(sh.occupation())).append(sh.place().isEmpty() ? "" : " in " + sh.place()).append(" are filled ").append(Career.and(ways)).append(", ")
             .append(sh.by().equals("you") ? "as you said" : "as the library understands it").append(". ");
            if (!sh.summary().isEmpty()) b.append(sh.summary()).append(sh.summary().endsWith(".") ? " " : ". ");
            if (!sh.need().isEmpty()) b.append("What you need: ").append(sh.need()).append(sh.need().endsWith(".") ? " " : ". ");
            if (!sh.lists().isEmpty()) b.append("Where such employers are listed: ").append(sh.lists()).append(sh.lists().endsWith(".") ? " " : ". ");
            if (!sh.now().isEmpty()) b.append("What says an employer needs someone now: ").append(sh.now()).append(sh.now().endsWith(".") ? " " : ". ");
        }
        b.append(reads(sh));
        return b.toString().strip();
    }

    /** The one line the search logs when it starts, in the person's terms. */
    public static String logLine(Shape sh) {
        if (!sh.drawn()) return "How jobs are filled for you could not be worked out this time, so the search reads everything it can.";
        List<String> ways = new ArrayList<>(); for (String h : sh.hows()) ways.add(HOW_WORDS.getOrDefault(h, h));
        return "Jobs" + (sh.occupation().isEmpty() ? "" : " for " + a(sh.occupation())) + (sh.place().isEmpty() ? "" : " in " + sh.place()) + " are filled " + Career.and(ways) + (sh.by().equals("you") ? ", as you said" : "")
                + (sh.need().isEmpty() ? "" : "; what you need: " + sh.need().replaceAll("\\.$", "")) + ". " + reads(sh);
    }

    /** The shape for a page: the text, escaped, in one paragraph. */
    public static String html(Shape sh) { return "<p>" + Pages.esc(text(sh)) + "</p>"; }
}
