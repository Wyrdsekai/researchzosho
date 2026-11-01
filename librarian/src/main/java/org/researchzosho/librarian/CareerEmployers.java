package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.researchzosho.Config;
import org.researchzosho.drive.ContentJudge;
import org.researchzosho.drive.DriveClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;

/**
 * The employers in the field the person decided, in or near their place, found the way the library answers any question: a research run
 * on the configured drive, bounded, its report filed on the shelves with its sources. The organisations the answer names are read out
 * of it by the model and handed to the search as companies to look at, each checked by its hiring page before an opening of its is read.
 * The answer serves again for a month for the same field, role and place. Nothing about any field is in the code.
 */
public final class CareerEmployers {
    private CareerEmployers() { }
    private static final ObjectMapper M = new ObjectMapper();

    /** How long the run may take, in minutes; its turns; how long its answer serves; the most employers taken from it. */
    public static final int MINUTES = Config.getInt("RESEARCHZOSHO_CAREER_EMPLOYERS_MINUTES", 8), TURNS = 12, DAYS = 30, MOST = 40;

    /** An employer the research named: its name as written, its website when given, where it is, and the answer's reason. */
    public record Named(String name, String site, String where, String why) { }

    /** The research as the library runs it: the answer's text for an ask; a test puts a stand-in here. */
    public interface Research { String answer(LibraryStore store, Researcher.Ask ask, String title, BooleanSupplier stop); }
    static volatile Research RESEARCH = CareerEmployers::research;

    static Path file(LibraryStore store, Career.Seeker s) { return CareerFind.dir(store, s).resolve("employers-research.json"); }

    /** The employers for the field, role and place: the month's answer when there is one, else researched now. The log says what was named. */
    public static List<Named> find(LibraryStore store, Career.Seeker s, String field, String role, String place, CareerFind.Progress progress, BooleanSupplier stop) throws IOException {
        Path f = file(store, s);
        String key = (field + "|" + role + "|" + place).toLowerCase(Locale.ROOT);
        if (Files.isRegularFile(f)) {
            JsonNode j = M.readTree(f.toFile());
            if (j.path("key").asText("").equals(key) && LocalDate.parse(j.path("at").asText("2000-01-01")).plusDays(DAYS).isAfter(LocalDate.now())) return read(j.path("named"));
        }
        String where = place.isEmpty() ? "" : " near " + place;
        progress.at("Researching the employers in the field of " + field + where + ", about " + MINUTES + " minutes");
        Researcher.Ask ask = new Researcher.Ask(question(field, role, place), "broad", TURNS, subQuestions(field, role, place), "web", List.of(), MINUTES, List.of(), List.of());
        String answer;
        try { answer = RESEARCH.answer(store, ask, "Employers in the field of " + field + where, stop); }
        catch (RuntimeException e) { Career.log(store, s, "The library could not research the employers in your field this time: " + e.getMessage()); return List.of(); }
        List<Named> named = answer == null || answer.isBlank() ? List.of() : extract(answer, field, place);
        ObjectNode o = M.createObjectNode().put("key", key).put("field", field).put("role", role).put("place", place).put("at", LocalDate.now().toString());
        ArrayNode a = o.putArray("named");
        for (Named n : named) a.addObject().put("name", n.name()).put("site", n.site()).put("where", n.where()).put("why", n.why());
        Files.createDirectories(f.getParent());
        M.writerWithDefaultPrettyPrinter().writeValue(f.toFile(), o);
        if (named.isEmpty()) Career.log(store, s, "The library's research named no employer in the field of " + field + where + ". Name the ones you know with career company and a name, and the search keeps them first.");
        else {
            List<String> names = new ArrayList<>(); for (Named n : named) names.add(n.name());
            Career.log(store, s, "Employers in the field of " + field + where + ", by the library's research: " + String.join(", ", names.subList(0, Math.min(names.size(), 20))) + (names.size() > 20 ? " and " + (names.size() - 20) + " more" : "")
                    + ". Each is checked by its hiring page before its openings are read.");
        }
        return named;
    }

    static List<Named> read(JsonNode a) {
        List<Named> out = new ArrayList<>();
        for (JsonNode n : a) out.add(new Named(n.path("name").asText(""), n.path("site").asText(""), n.path("where").asText(""), n.path("why").asText("")));
        return out;
    }

    static String question(String field, String role, String place) {
        return "Which employers in the field of " + field + (place.isEmpty() ? "" : ", in or near " + place) + ", employ people" + (role.isEmpty() ? "" : " as " + role)
                + "? Name the organisations themselves: companies, clinics, non-profits, public bodies, research groups, large and small, the local ones a directory would list as well "
                + "as the known names. For each: where it is, what it does, and whether it employs such people, with a source.";
    }

    static List<String> subQuestions(String field, String role, String place) {
        String where = place.isEmpty() ? "" : " in or near " + place;
        return List.of("Which companies in the field of " + field + " have their headquarters or an office" + where + "?",
                "Which organisations in the field of " + field + where + " have posted openings" + (role.isEmpty() ? "" : " for " + role) + " lately?",
                "Which non-profits, clinics, institutions or public bodies in the field of " + field + where + " employ people" + (role.isEmpty() ? "" : " as " + role) + "?");
    }

    /** The organisations the answer names, read out of it by the model; the answer is the library's own writing, fenced all the same. */
    static List<Named> extract(String answer, String field, String place) {
        ArrayNode msgs = M.createArrayNode();
        msgs.addObject().put("role", "user").put("content",
                "From the library's research answer below, list the organisations it names as employers in the field of " + field + (place.isEmpty() ? "" : " in or near " + place)
                + ": each once, by its own name as the answer writes it, with its website when the answer gives one, where it is, and the answer's reason in a few words. Leave out "
                + "organisations the answer says are elsewhere or are not employers. Answer with a JSON array and nothing else: [{\"name\": \"…\", \"website\": \"…\", \"where\": \"…\", \"why\": \"…\"}]\n\n"
                + Fence.rule("the answer") + "\n" + Fence.wrap("ANSWER", CareerSources.clip(answer, 12_000)));
        String said;
        try { said = Career.DRIVE.get().classify(msgs, 2500); } catch (RuntimeException e) { said = null; }
        List<Named> out = new ArrayList<>();
        for (JsonNode j : Career.parseArray(said)) {
            String name = j.path("name").asText("").strip();
            if (name.isEmpty() || name.length() > 80 || out.stream().anyMatch(x -> CareerSearch.key(x.name()).equals(CareerSearch.key(name)))) continue;
            out.add(new Named(name, j.path("website").asText("").strip(), j.path("where").asText("").strip(), j.path("why").asText("").strip()));
            if (out.size() >= MOST) break;
        }
        return out;
    }

    /** The run as the library runs every research question: on the configured drive, both seats watched, the pages it fetches checked by its model, the report filed on the shelves. */
    static String research(LibraryStore store, Researcher.Ask ask, String title, BooleanSupplier stop) {
        String drive = Config.get("RESEARCHZOSHO_DRIVE", "http://localhost:8200"), model = Config.get("RESEARCHZOSHO_MODEL", "local-model");
        Researcher.Drive workers = Researcher.watched(drive, model, "workers", null), judge = Researcher.watchedJudge(drive, model, null);
        Researcher r = new Researcher(workers, judge, Researcher.webTools(), line -> Crews.log(store, "research", line, 0), store).onDrive(drive);
        r.contentJudge(ContentJudge.of(new DriveClient(drive, model)));
        r.title(title);
        r.stopWhen(stop);
        try {
            Researcher.Filed filed = Researcher.file(store, r, ask, "patron:career", "", "asked");
            return filed.result() == null ? "" : filed.result().answer();
        } catch (IOException e) { throw new RuntimeException(e.getMessage(), e); }
    }
}
