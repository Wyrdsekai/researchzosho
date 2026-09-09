package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Retractions. A claim can be perfectly supported by a paper that has since been retracted. Retraction Watch's list is
 * in the Crossref API, free, updated every working day: for a DOI, {@code works/<doi>} carries an {@code update-to}
 * entry of type retraction (or withdrawal, removal, partial retraction) when there is one. Every finding that cites a
 * DOI is looked up when it lands and again every thirty days; a retracted source DISPUTES the claim, with the notice as
 * the reason, and an expression of concern is noted without disputing. No model is involved.
 */
public final class Retractions {

    private static final ObjectMapper M = new ObjectMapper();
    static final int DAYS = 30;
    public static final int PER_NIGHT = org.researchzosho.Config.getInt("RESEARCHZOSHO_RETRACTIONS_PER_NIGHT", 60);

    /** What Crossref says about a DOI: the update type ("retraction", "expression_of_concern", …), its date, its DOI; null = nothing. */
    public record Notice(String type, String date, String noticeDoi) { }

    public interface Lookup { Notice notice(String doi) throws Exception; }

    public record Outcome(int checked, int retracted, int concerns, List<String> notes) { }

    private Retractions() { }

    public static Path file(LibraryStore store) { return store.root().resolve("catalog").resolve("retractions.tsv"); }

    public static Lookup live() {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        return doi -> {
            // the retraction is on the NOTICE's record, which says what it updates — not on the retracted paper's record
            // (checked live on 10.1016/S0140-6736(97)11096-0: works/<doi> carries no update-to; works?filter=updates:<doi> lists the notice)
            HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.crossref.org/works?rows=20&select=DOI,type,update-to&filter=updates:"
                            + java.net.URLEncoder.encode(doi, StandardCharsets.UTF_8)))
                    .timeout(Duration.ofSeconds(20)).header("User-Agent", "ResearchZosho/" + org.researchzosho.Version.string() + " (mailto:hello@researchzosho.org)").GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 404) return null;
            if (resp.statusCode() != 200) throw new IOException("Crossref answered " + resp.statusCode());
            return parse(M.readTree(resp.body()), doi);
        };
    }

    /** The strongest notice about {@code doi} in a Crossref works list: a retraction outranks a concern outranks a correction; null when none. */
    static Notice parse(JsonNode list, String doi) {
        Notice best = null;
        for (JsonNode item : list.path("message").path("items")) {
            for (JsonNode u : item.path("update-to")) {
                if (!u.path("DOI").asText("").equalsIgnoreCase(doi)) continue;
                String type = u.path("type").asText("").toLowerCase(Locale.ROOT).replace('-', '_');
                String date = u.path("updated").path("date-time").asText("");
                if (date.length() >= 10) date = date.substring(0, 10);
                Notice n = new Notice(type, date, item.path("DOI").asText(""));
                if (best == null || rank(n.type()) > rank(best.type())) best = n;
            }
        }
        return best;
    }

    static int rank(String type) {
        return switch (type) { case "retraction", "withdrawal", "removal", "partial_retraction" -> 3; case "expression_of_concern" -> 2; case "correction", "erratum", "corrigendum" -> 1; default -> 0; };
    }
    static boolean retracts(String type) { return rank(type) == 3; }

    /** Every non-retired finding that cites a DOI, not checked in the last thirty days, up to {@code budget}. */
    public static Outcome check(LibraryStore store, Lookup lookup, int budget, LocalDate today) throws IOException {
        Map<String, String> seen = new HashMap<>();   // doi → "date\ttype"
        if (Files.exists(file(store))) for (String line : Files.readAllLines(file(store), StandardCharsets.UTF_8)) {
            String[] p = line.split("\t");
            if (p.length >= 2) seen.put(p[0], p[1] + "\t" + (p.length > 2 ? p[2] : ""));
        }
        int checked = 0, retracted = 0, concerns = 0;
        List<String> notes = new ArrayList<>();
        LibrarianIndex index = new LibrarianIndex(store);
        for (Finding f : store.scanFindings().findings()) {
            if (f.state() == Finding.State.retired) continue;
            if (checked >= budget) break;
            for (Finding.Source s : f.sources()) {
                String id = Citations.identify(s.locator());
                if (id == null || !id.startsWith("doi:")) continue;
                String doi = id.substring(4);
                String last = seen.get(doi);
                Notice n;
                if (last != null && LocalDate.parse(last.split("\t")[0]).plusDays(DAYS).isAfter(today)) {
                    String type = last.split("\t", 2)[1];
                    n = type.isEmpty() ? null : new Notice(type, "", "");
                } else {
                    try { n = lookup.notice(doi); } catch (Exception e) { notes.add(doi + ": lookup failed (" + e.getMessage() + ")"); continue; }
                    checked++;
                    record(store, doi, today, n == null ? "" : n.type());
                    seen.put(doi, today + "\t" + (n == null ? "" : n.type()));
                }
                if (n == null) continue;
                boolean noted = f.notes().stream().anyMatch(x -> x.text().contains(doi) && (x.kind().equals("retracted") || x.kind().equals("concern")));
                if (noted) continue;
                if (retracts(n.type())) {
                    String reason = "the cited source doi:" + doi + " was " + n.type().replace('_', ' ') + (n.date().isEmpty() ? "" : " on " + n.date())
                            + (n.noticeDoi().isEmpty() ? "" : " (notice doi:" + n.noticeDoi() + ")") + " — Retraction Watch via Crossref";
                    Finding withNote = f.withNote(new Finding.Note("retracted", "retractions", today.toString(), reason));
                    store.write(withNote);
                    if (withNote.state() != Finding.State.disputed) Inventory.dispute(store, index, withNote, reason);
                    Changes.append(store, "finding", f.id(), "retracted", "doi:" + doi);
                    retracted++;
                    notes.add(f.id() + " ← retracted doi:" + doi);
                } else if (rank(n.type()) == 2) {
                    String text = "an expression of concern was published for doi:" + doi + (n.date().isEmpty() ? "" : " on " + n.date()) + " — Retraction Watch via Crossref";
                    store.write(f.withNote(new Finding.Note("concern", "retractions", today.toString(), text)));
                    Changes.append(store, "finding", f.id(), "concern", "doi:" + doi);
                    concerns++;
                    notes.add(f.id() + " ← concern doi:" + doi);
                }
                break;   // one DOI per finding is enough to act on
            }
        }
        if (retracted > 0 || concerns > 0) store.circulate("retractions", retracted + " retracted, " + concerns + " concern(s)");
        return new Outcome(checked, retracted, concerns, notes);
    }

    private static void record(LibraryStore store, String doi, LocalDate today, String type) throws IOException {
        Files.createDirectories(file(store).getParent());
        Files.writeString(file(store), doi + "\t" + today + "\t" + type + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
