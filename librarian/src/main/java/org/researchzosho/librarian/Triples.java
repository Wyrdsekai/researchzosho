package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

import java.time.LocalDate;
import org.researchzosho.Config;
import org.researchzosho.Stopping;
import org.researchzosho.drive.DriveClient;
import org.researchzosho.drive.Declined;
/**
 * Findings that arrived without a triple — a patron's submission, an older record — get one from the
 * model so they become edges of the graph: the claim as subject | predicate | object, or nothing when
 * the claim has no such shape. Budgeted per night; a finding is asked once (a {@code triples} note
 * records the pass either way). The triple is a shape the harness resolves against the graph's
 * vocabularies; the model never picks the node ids.
 */
public final class Triples {

    private static final ObjectMapper M = new ObjectMapper();
    static final int PER_NIGHT = Config.getInt("RESEARCHZOSHO_TRIPLES_PER_NIGHT", 40);

    /** Claim text → JSON {"subject","predicate","object"} or {"triple": null}; a test scripts it. */
    public interface Extractor {
        String extract(String claim) throws Exception;
        /** The same for a claim that is a field's work: {@code rule} is the field's sentence for its relations ({@link Profile#extractionRule}). */
        default String extract(String claim, String rule) throws Exception { return extract(claim); }
    }

    private Triples() { }

    public static Extractor driveExtractor(DriveClient drive) {
        return new Extractor() {
            @Override public String extract(String claim) { return extract(claim, ""); }
            @Override public String extract(String claim, String rule) {
                var msgs = M.createArrayNode();
                msgs.addObject().put("role", "user").put("content", prompt(claim, rule));
                return drive.classify(msgs, 200);
            }
        };
    }

    /** What the model that writes a claim's triple is told; {@code rule} is what the claim's field adds, "" for an ordinary claim. */
    static String prompt(String claim, String rule) {
        return "Restate this claim as ONE subject–predicate–object triple when it has that shape. The subject and "
                + "object are the things (a person, a place, a work, a concept) as named in the claim; the predicate is "
                + "the relation in two or three words." + (rule == null || rule.isBlank() ? "" : " " + rule.strip()) + "\n\nCLAIM:\n" + Fence.wrap("CLAIM", claim) + "\n" + Fence.rule("CLAIM")
                + "\n\nAnswer with JSON only: {\"subject\": \"…\", \"predicate\": \"…\", \"object\": \"…\"} or {\"triple\": null} when the claim is not a relation between two things.";
    }

    public record Outcome(int asked, int filled) { }

    public static Outcome fill(LibraryStore store, Extractor extractor, int budget) throws IOException {
        int asked = 0, filled = 0;
        Map<String, Set<String>> runs = Fields.runs(store);
        for (Finding f : store.scanFindings().findings()) {
            if (asked >= budget) break;
            if (f.triple() != null || f.state() == Finding.State.retired) continue;
            if (f.notes().stream().anyMatch(n -> "triples".equals(n.kind()))) continue;
            asked++;
            Finding.Triple t = null;
            Declined declined = null;
            try {
                String raw = extractor.extract(f.body().strip(), LibrarianReview.extractionRule(store, Fields.ofClaim(f, runs)));
                int a = raw.indexOf('{'), b = raw.lastIndexOf('}');
                if (a >= 0 && b > a) {
                    JsonNode j = M.readTree(raw.substring(a, b + 1));
                    String s = j.path("subject").asText("").strip(), p = j.path("predicate").asText("").strip(), o = j.path("object").asText("").strip();
                    if (!s.isEmpty() && !p.isEmpty() && !o.isEmpty() && s.length() < 120 && o.length() < 160) t = new Finding.Triple(s, p, o);
                }
            } catch (Declined d) {
                declined = d;   // the model declined: the note says so, and stops the nightly re-ask as any answer does
            } catch (Stopping.Requested stop) {
                throw stop;   // the run was stopped while the model was asked: no answer came, and nothing is written down as one
            } catch (Exception e) {
                // asked and no answer: the note below stops the nightly re-ask; a later rebuild can clear it
            }
            Finding g = (t == null ? f : f.withTriple(t)).withNote(new Finding.Note("triples", "crew:triples", LocalDate.now().toString(),
                    t != null ? t.toLine() : declined != null ? "declined by the model " + declined.model() + (declined.said().isEmpty() ? "" : ": \"" + declined.quoted() + "\"") : "no relation shape"));
            store.write(g);
            if (t != null) filled++;
        }
        store.circulate("triples", asked + " asked, " + filled + " filled");
        return new Outcome(asked, filled);
    }
}
