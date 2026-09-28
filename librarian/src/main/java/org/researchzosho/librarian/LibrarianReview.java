package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.researchzosho.Config;
import org.researchzosho.Stopping;
import org.researchzosho.drive.DriveClient;
import org.researchzosho.drive.DeclineJudge;
import org.researchzosho.drive.Declined;
/**
 * The librarian review pass — the held-out judgment step between DRAFT and canon
 * (the architecture notes, §crews). Runs with NO tools, in a fresh context that never saw the
 * producing run (held out by context; a different-model seat via RESEARCHZOSHO_DRIVE is
 * better when the household has one — the 16GB rule).
 *
 * <p>Division of labor, per the house rule (machine evidence nominates, the model
 * disambiguates, it never nominates):
 * <ul>
 *   <li>The MODEL extracts candidate findings from an admitted investigation and judges
 *       duplicate-vs-contradicts-vs-independent against neighbors.</li>
 *   <li>The MACHINE decides everything else: which neighbors to compare (BM25 nomination),
 *       whether a claimed source actually appears in the investigation (a reviewer cannot mint
 *       sources), the review-by horizon (volatility arithmetic), and WHICH claim types may
 *       auto-promote at all.</li>
 * </ul>
 *
 * <p>v1 promotion rule, from the Kosmos accuracy split (extraction ~85% accurate, synthesis
 * ~58% — the condensation layer is where truth leaks): only {@code extraction} claims
 * auto-promote to accepted. Synthesis, interpretation and speculation are reviewed and
 * RECORDED but stay draft for the person — canon-level trust in condensed claims is the
 * council's to grant, not the model's.
 */
public final class LibrarianReview {

    /**
     * What the reviewer is told about coverage. A write-up's sections are what the run went and found; taking claims from
     * only the first few loses the rest for good — a bicycle run whose record carried tube layups, fatigue and load
     * testing left nine claims, eight of them about handling (2026-09-15).
     */
    static String sectionRule(String investigationBody) {
        List<String> secs = sections(investigationBody);
        if (secs.isEmpty()) return "one finding for each distinct thing the record establishes, 1 to " + MAX_FINDINGS + ".";
        int cap = Math.max(MAX_FINDINGS, secs.size() + 3);
        return "the record has " + secs.size() + " sections — " + String.join("; ", secs) + ". Write at least one finding for EACH "
                + "section that states something checkable, and more where a section carries more; " + secs.size() + " to " + cap
                + " findings in all. A section you take nothing from is knowledge the library loses.";
    }

    /** Findings one review may extract from a write-up. It was 5, which dropped a section of every six-section write-up
     *  (2026-09-15: the entropy section of a cryptography run never became a claim, and the bridges sensor, which reads
     *  claims, could not see it). */
    static final int MAX_FINDINGS = Config.getInt("RESEARCHZOSHO_REVIEW_FINDINGS", 12);

    /** Where citation records come from; a test injects a canned one. */
    Citations.Source citations = Citations.LIVE;


    /** The model seat, abstracted so the pipeline tests with a stub and the drive is swappable. */
    public interface Judge {
        /** Extract 1–5 atomic findings from an investigation. Returns a JSON array (see prompt). */
        String extract(String investigationBody) throws Exception;
        /** The same for a run of a field: {@code rule} is the field's sentence for the relations its claims use ({@link Profile#extractionRule}), "" for an ordinary run. */
        default String extract(String investigationBody, String rule) throws Exception { return extract(investigationBody); }
        /** Compare a candidate against BM25-nominated neighbors: duplicate|contradicts|independent. */
        String compare(String candidate, String neighbors) throws Exception;
    }

    private static final ObjectMapper M = new ObjectMapper();

    private final LibraryStore store;
    private final LibrarianIndex index;
    private final Judge judge;
    private final String reviewerName;

    /** One web search, for reading sideways about a source the library has not cited before: the result lines, or "". */
    public interface Searcher { String search(String query) throws Exception; }
    private Searcher searcher;
    /** Reads sideways about first-seen hosts during review; without one, that step is skipped. */
    public LibrarianReview searcher(Searcher s) { this.searcher = s; return this; }

    /** The live searcher: the same web search the workers use. */
    public static Searcher liveSearcher() {
        var search = Researcher.webTools().web("source check").get(0);
        return q -> search.execute(new ObjectMapper().createObjectNode().put("query", q).put("limit", 5));
    }

    public LibrarianReview(LibraryStore store, LibrarianIndex index, Judge judge, String reviewerName) {
        this.store = store;
        this.index = index;
        this.judge = judge;
        this.reviewerName = reviewerName;
    }

    /** One reviewed investigation's outcome, for the caller to print or log. */
    public record Outcome(String investigationId, List<String> accepted, List<String> keptDraft,
                          List<String> disputed, List<String> skippedDuplicates,
                          List<String> problems) { }

    /**
     * Review one draft investigation: extract candidate findings, gate each mechanically,
     * judge each against its BM25 neighbors, write what survives, promote the investigation.
     */
    public Outcome review(Investigation inv) throws Exception {
        List<String> accepted = new ArrayList<>(), kept = new ArrayList<>(),
                disputed = new ArrayList<>(), dupes = new ArrayList<>(), problems = new ArrayList<>();
        List<String> invUrls = Acquisitions.urls(inv.body());

        String rawExtraction;
        try { rawExtraction = judge.extract(inv.body(), extractionRule(store, Fields.ofRun(store, inv.id()))); }
        catch (Declined d) {
            // the model declined to read this report: said as that, never as an unparseable reply, and the report stays a draft
            problems.add(d.statement("to read the claims out of this report") + " The report stays a draft.");
            Declines.rememberDeclined(store, "review", inv.id(), Conversations.hash8(inv.body()), d);   // the nightly review does not send it again until it changes
            logProblems(inv, problems);
            return new Outcome(inv.id(), accepted, kept, disputed, dupes, problems);
        }
        JsonNode arr = parseArray(rawExtraction);
        if (arr == null) {
            arr = salvageArray(rawExtraction);   // a reply cut off mid-array (a small drive's long answer) keeps its whole objects
            if (arr != null) problems.add("extraction was cut off; " + arr.size() + " whole candidate(s) salvaged");
        }
        if (arr == null) {
            problems.add("extraction unparseable — investigation left in draft; the reply began: " + Acquisitions.compress(rawExtraction == null ? "" : rawExtraction, 160));
            logProblems(inv, problems);
            return new Outcome(inv.id(), accepted, kept, disputed, dupes, problems);
        }
        if (arr.isEmpty()) problems.add("extraction returned no candidates; the reply began: " + Acquisitions.compress(rawExtraction, 160));

        for (Looked.Entry counted : Looked.fromReport(inv.id(), inv.title(), inv.body(), Looked.today())) Looked.add(store, counted);   // what the library itself counted as searched and empty

        List<String> findingIds = new ArrayList<>(inv.findings());
        for (JsonNode c : arr) {
            String title = c.path("title").asText("").strip();
            String claim = c.path("claim").asText("").strip();
            if (title.isEmpty() || claim.isEmpty()) {
                problems.add("candidate missing title/claim — dropped");
                continue;
            }
            // LOOKED AND NOT FOUND: what one search did not find on one day is not a finding about the world. It goes to the dated
            // ledger of places somebody has looked, which a later run is shown apart from the claims, and no claim is filed for it.
            if (c.path("found").isBoolean() && !c.path("found").asBoolean()) {
                List<String> lookedIn = new ArrayList<>();
                for (JsonNode u : c.path("sources")) { String match = u.isTextual() ? resolveCited(invUrls, u.asText()) : null; if (match != null && !lookedIn.contains(match)) lookedIn.add(match); }
                String about = c.path("triple").path("subject").asText("").strip();
                if (Looked.add(store, new Looked.Entry(Looked.today(), about.isEmpty() ? title : about, claim, Looked.places(lookedIn), inv.id(), inv.title()))) store.circulate("review-looked", title);
                continue;
            }
            // EVIDENCE GATE (mechanical): every cited source must appear in the investigation.
            // A reviewer that could mint sources would launder memory into canon. Matching is
            // normalized-prefix, not exact-equality: caught live 2026-09-01 — the reviewer cited
            // github.com/m-bain/whisperX for a record holding .../whisperX/blob/main/... and the
            // exact gate dropped a TRUE claim. A prefix of a recorded URL (or vice versa) still
            // cannot be minted; the resolved source recorded is the one FROM the investigation.
            List<String> cited = new ArrayList<>();
            for (JsonNode u : c.path("sources")) {
                if (!u.isTextual()) continue;
                String match = resolveCited(invUrls, u.asText());
                if (match != null && !cited.contains(match)) cited.add(match);
            }
            if (cited.isEmpty()) {
                problems.add("candidate '" + Acquisitions.compress(title, 60)
                        + "' cites no source present in the investigation — dropped");
                continue;
            }
            // the person's refused list: a source they refuse cannot carry a claim; a claim resting only on such sources is dropped
            SourceRules rules = SourceRules.load(store);
            List<String> allowed = new ArrayList<>();
            for (String u : cited) if (!rules.refused(u)) allowed.add(u);
            if (allowed.isEmpty()) {
                problems.add("candidate '" + Acquisitions.compress(title, 60) + "' rests only on sources on your refused list (" + String.join(", ", cited) + ") — dropped");
                continue;
            }
            cited = allowed;

            Finding.ClaimType type = enumOr(Finding.ClaimType.class,
                    c.path("claim_type").asText(""), Finding.ClaimType.synthesis);
            // The type decides promotion, and the model assigns it — so the machine checks the
            // one thing it can: an EXTRACTION is read from ONE source (the Kosmos definition).
            // A claim combining several sources is a synthesis by construction, whatever the
            // label says. Caught live 2026-09-01: two-source claims self-typed 'extraction'
            // walked into canon.
            if (type == Finding.ClaimType.extraction && cited.size() > 1) {
                type = Finding.ClaimType.synthesis;
            }
            Finding.Volatility vol = enumOr(Finding.Volatility.class,
                    c.path("volatility").asText(""), Finding.Volatility.slow);
            Finding.Confidence conf = enumOr(Finding.Confidence.class,
                    c.path("confidence").asText(""), Finding.Confidence.medium);

            // The claim's triple, when the extractor found that shape.
            Finding.Triple triple = null;
            JsonNode tj = c.path("triple");
            if (tj.isObject() && !tj.path("subject").asText("").isBlank() && !tj.path("predicate").asText("").isBlank()
                    && !tj.path("object").asText("").isBlank()) {
                triple = new Finding.Triple(tj.path("subject").asText().strip(), tj.path("predicate").asText().strip(), tj.path("object").asText().strip());
            }
            title = Finding.titled(title, triple);   // a title is read alone, in a list: it says whom the claim is about

            // THE SAME CLAIM, BY ITS TRIPLE: machine evidence ahead of the model's duplicate call. Who set a copy of it aside decides
            // (Finding#setAsideBy). A copy that is waiting or accepted is the duplicate weighed below, whatever disputed copy stands beside it.
            // Without one, a claim the PERSON disputed or retired is not filed again: their decision holds against later runs, and a note on it
            // says where it came back. A claim the LIBRARY disputed by itself (the review's contradiction pass, the inventory, the retraction
            // check) gains the source and stays disputed, so later sources can settle it.
            String sameId = null;
            Finding personNo = null, libraryNo = null;
            if (triple != null) {
                for (Finding other : store.scanFindings().findings()) {
                    if (!sameTriple(other.triple(), triple)) continue;
                    Finding.SetAside by = other.setAsideBy();
                    if (by == Finding.SetAside.person && personNo == null) personNo = other;
                    else if (by == Finding.SetAside.library && libraryNo == null) libraryNo = other;
                    else if (sameId == null && (other.state() == Finding.State.draft || other.state() == Finding.State.accepted)) sameId = other.id();
                }
            }
            if (sameId == null && personNo != null) { metAgain(personNo, cited, title, claim, inv, problems); continue; }
            if (sameId == null && libraryNo != null) { furtherSource(libraryNo, cited, title, claim, inv, findingIds, problems); continue; }

            // NEIGHBOR JUDGMENT: the machine nominates, the model only disambiguates among nominees.
            // First by ARITHMETIC — same subject + predicate, different object is a contradiction
            // candidate before any model reads a word (kiroku-memory's take, 2026-09-05) — then BM25.
            List<String> clashIds = new ArrayList<>();
            if (triple != null) {
                for (Finding other : store.scanFindings().findings()) {
                    if (other.state() == Finding.State.retired || other.triple() == null) continue;
                    if (other.triple().clashes(triple)) clashIds.add(other.id());
                }
            }
            List<LibrarianIndex.Hit> neighbors = new ArrayList<>();
            for (var h : index.search(title + " " + claim, 4)) {
                if ("finding".equals(h.kind()) && !clashIds.contains(h.id())) neighbors.add(h);
            }
            String verdict = "independent";
            String conflictId = null;
            if (sameId != null) { verdict = "duplicate"; conflictId = sameId; }
            else if (!clashIds.isEmpty() || !neighbors.isEmpty()) {
                StringBuilder nb = new StringBuilder();
                for (String cid : clashIds) {
                    Finding nf = store.finding(cid);
                    if (nf != null) {
                        nb.append("[").append(nf.id()).append("] (SAME SUBJECT AND PREDICATE, DIFFERENT OBJECT: ")
                          .append(nf.triple().toLine()).append(") ").append(nf.title())
                          .append(": ").append(Acquisitions.compress(nf.body(), 300)).append('\n');
                    }
                }
                for (var h : neighbors) {
                    Finding nf = store.finding(h.id());
                    if (nf != null) {
                        nb.append("[").append(nf.id()).append("] ").append(nf.title())
                          .append(": ").append(Acquisitions.compress(nf.body(), 300)).append('\n');
                    }
                }
                JsonNode v;
                try { v = parseObject(judge.compare(title + ": " + claim, nb.toString())); }
                catch (Declined d) {
                    // declined, not independent: the review stops here, and what it has not decided stays a draft
                    problems.add(d.statement("to compare a claim of this report with the claims on the shelves") + " The review stopped there; the report stays a draft.");
                    Declines.rememberDeclined(store, "review", inv.id(), Conversations.hash8(inv.body()), d);
                    logProblems(inv, problems);
                    return new Outcome(inv.id(), accepted, kept, disputed, dupes, problems);
                }
                if (v != null) {
                    verdict = v.path("verdict").asText("independent");
                    conflictId = v.path("id").asText(null);
                }
            }
            if ("duplicate".equals(verdict) && conflictId != null && store.finding(conflictId) != null) {
                // CORROBORATION: the same claim again, from a source the existing finding does not have, is the second
                // independent source the one-source rule waits for. The existing finding gains the source; a draft that
                // waited only for that is accepted. The same source again is a plain duplicate.
                Finding existing = store.finding(conflictId);
                // the model's duplicate call follows the same rule as the triple: who set the claim aside decides, and a copy of it with
                // the same triple that the person set aside keeps this one from being accepted on its own
                Finding.SetAside setAside = existing.setAsideBy();
                if (setAside == Finding.SetAside.person) { metAgain(existing, cited, title, claim, inv, problems); continue; }
                if (setAside == Finding.SetAside.library) { furtherSource(existing, cited, title, claim, inv, findingIds, problems); continue; }
                Finding personCopy = null, libraryCopy = null;
                if (existing.triple() != null) for (Finding other : store.scanFindings().findings()) {
                    if (other.id().equals(existing.id()) || !sameTriple(other.triple(), existing.triple())) continue;
                    Finding.SetAside by = other.setAsideBy();
                    if (by == Finding.SetAside.person && personCopy == null) personCopy = other;
                    else if (by == Finding.SetAside.library && libraryCopy == null) libraryCopy = other;
                }
                List<String> have = new ArrayList<>(); for (Finding.Source es : existing.sources()) have.add(es.locator());
                List<String> all = new ArrayList<>(have); for (String u : cited) if (!all.contains(u)) all.add(u);
                int before = Independence.independent(store, have);
                int after = all.size() == have.size() ? before : Independence.independent(store, all);
                if (after > before && existing.state() != Finding.State.retired) {
                    List<Finding.Source> merged = new ArrayList<>(existing.sources());
                    for (String u : cited) if (!have.contains(u)) merged.add(u.startsWith("cite:") ? new Finding.Source(u, u.substring(5), "corroborates, from " + inv.id()) : new Finding.Source(u, "n/a", "corroborates, from " + inv.id()));
                    merged = Citations.enrich(store, merged, citations);
                    // a family's own account or tree file waits for the family's word, whatever record backs it; and a claim the person
                    // disputed or retired once, in another copy, waits for them too, so their decision holds
                    boolean family = Profiles.ownWriters().values().stream().anyMatch(w -> w.contains(existing.writer()));
                    boolean waited = !family && existing.state() == Finding.State.draft && existing.claimType() == Finding.ClaimType.extraction
                            && SourceTier.strongest(merged).autoPromotes() && after >= 2
                            && existing.notes().stream().noneMatch(n -> n.kind().equals("disputed"))
                            && personCopy == null;
                    Finding.State st = waited ? Finding.State.accepted : existing.state();
                    Finding grown = new Finding(existing.id(), existing.title(), existing.subjects(), st, existing.claimType(), existing.confidence(),
                            existing.writer(), existing.recordedAt(), existing.validAsOf(), existing.volatility(), existing.reviewBy(), merged,
                            existing.supersedes(), existing.review(), existing.body(), existing.triple(), existing.notes())
                            .withNote(new Finding.Note("corroborated", reviewerName, LocalDate.now().toString(),
                                    "the same claim from an independent source, " + String.join(", ", cited) + " (" + inv.id() + "); " + after + " independent source(s) now"
                                    + (waited ? " — accepted" : "")));
                    Finding stamped = new Finding(grown.id(), grown.title(), grown.subjects(), grown.state(), grown.claimType(), grown.confidence(), grown.writer(), grown.recordedAt(),
                            grown.validAsOf(), grown.volatility(), grown.reviewBy(), grown.sources(), grown.supersedes(),
                            new Finding.Review(existing.review() == null ? 1 : existing.review().round() + 1, reviewerName, waited ? "accepted" : (existing.review() == null ? "draft" : existing.review().decision()), grown.contentHash(), Instant.now().toString()),
                            grown.body(), grown.triple(), grown.notes());
                    store.write(stamped);
                    index.upsert(stamped);
                    Changes.append(store, "finding", existing.id(), waited ? "state:draft→accepted" : "edited", "corroborated by " + String.join(", ", cited));
                    if (!findingIds.contains(existing.id())) findingIds.add(existing.id());
                    if (waited) accepted.add(existing.id());
                    // the copy that took the source, beside the ones set aside: said, so the person knows why it is not the set-aside copy that grew
                    if (personCopy != null) problems.add("'" + Acquisitions.compress(title, 50) + "' is the claim " + existing.id() + ", which gained the source"
                            + (st == Finding.State.draft ? "; it waits for your decision, because you " + personCopy.state() + " its copy " + personCopy.id()
                                    : " and stays " + st + "; you " + personCopy.state() + " its copy " + personCopy.id() + ", which stays as you left it"));
                    else if (libraryCopy != null) problems.add("'" + Acquisitions.compress(title, 50) + "' is the claim " + existing.id() + ", which gained the source; its copy "
                            + libraryCopy.id() + ", which the library " + libraryCopy.state() + " by itself, did not stop that");
                    store.circulate("review-corroborated", title + " ≈ " + conflictId + (waited ? " → accepted" : ""));
                    continue;
                }
                dupes.add(conflictId);
                store.circulate("review-duplicate", title + " ≈ " + conflictId);
                continue;
            }

            String id = store.nextFindingId(title);
            String reviewBy = switch (vol) {
                case fast -> LocalDate.now().plusDays(30).toString();
                case slow -> LocalDate.now().plusDays(180).toString();
                case stable -> "";
            };
            List<Finding.Source> sources = new ArrayList<>();
            for (String u : cited) {
                sources.add(u.startsWith("cite:")
                        ? new Finding.Source(u, u.substring(5), "cited by " + inv.id())
                        : new Finding.Source(u, "n/a", "cited by " + inv.id()));
            }
            sources = Citations.enrich(store, sources, citations);   // a DOI / arXiv / PubMed locator gets its record as the edition

            boolean contradiction = "contradicts".equals(verdict) && conflictId != null
                    && store.finding(conflictId) != null;
            // Promotion arithmetic: extraction + independent + sourced from a tier that may
            // auto-promote (reference / scholarly / primary / code / personal) → accepted. A blog- or
            // forum-only extraction stays draft for the person — wyrdsekai's steward gate.
            SourceTier tier = SourceTier.strongest(sources);
            // ONE source is never enough on its own: copies of one text count once, and a claim with a single independent
            // source stays a draft for the person however good the source — that is how most misinformation gets in
            List<String> locs = new ArrayList<>(); for (Finding.Source s : sources) locs.add(s.locator());
            int independent = Independence.independent(store, locs);
            // the person's own document is their word; a file of a cloned repository is not their document (SourceTier.code), and waits
            boolean corroborated = independent >= 2 || tier == SourceTier.personal;
            Finding.State state = (!contradiction && type == Finding.ClaimType.extraction
                    && tier.autoPromotes() && corroborated) ? Finding.State.accepted : Finding.State.draft;
            if (!contradiction && type == Finding.ClaimType.extraction && !tier.autoPromotes()) {
                problems.add("'" + Acquisitions.compress(title, 50) + "' rests on " + tier
                        + " sources only — kept draft for the person");
            } else if (!contradiction && type == Finding.ClaimType.extraction && !corroborated) {
                problems.add("'" + Acquisitions.compress(title, 50) + "' has one independent source — kept draft until a second one backs it");
            }
            if (contradiction) state = Finding.State.disputed;

            List<Finding.Note> notes = new ArrayList<>();
            for (Finding.Note n : sideways(sources, rules)) notes.add(n);
            if (contradiction) {
                notes.add(new Finding.Note("disputed", reviewerName, LocalDate.now().toString(),
                        "contradicts " + conflictId + (clashIds.contains(conflictId) ? " (same subject and predicate, different object)" : " (judged against its neighbours)")));
            }
            Finding draft = new Finding(id, title, List.of(), state, type, conf,
                    inv.writer(), Instant.now().toString(), LocalDate.now().toString(), vol,
                    reviewBy, sources, List.of(), null, claim + "\n", triple, notes);
            Finding f = new Finding(draft.id(), draft.title(), draft.subjects(), draft.state(),
                    draft.claimType(), draft.confidence(), draft.writer(), draft.recordedAt(),
                    draft.validAsOf(), draft.volatility(), draft.reviewBy(), draft.sources(),
                    draft.supersedes(),
                    new Finding.Review(1, reviewerName, state.name(), draft.contentHash(),
                            Instant.now().toString()),
                    draft.body(), draft.triple(), draft.notes());
            store.write(f);
            index.upsert(f);
            findingIds.add(id);

            if (contradiction) {
                // NEVER overwrite canon: BOTH sides marked disputed; the dispute record names
                // the other party. Resolution is evidence's job, later, surfaced to the person.
                Finding other = store.finding(conflictId);
                Finding otherDisputed = new Finding(other.id(), other.title(), other.subjects(),
                        Finding.State.disputed, other.claimType(), other.confidence(),
                        other.writer(), other.recordedAt(), other.validAsOf(), other.volatility(),
                        other.reviewBy(), other.sources(), other.supersedes(), other.review(),
                        other.body() + "\nDISPUTED-BY: " + id + " (" + LocalDate.now() + ")\n", other.triple(), other.notes())
                        .withNote(new Finding.Note("disputed", reviewerName, LocalDate.now().toString(), "contradicted by " + id));
                store.write(otherDisputed);
                index.upsert(otherDisputed);
                try {
                    store.frontier("dispute", id + " vs " + conflictId + " — what evidence would settle it?");
                } catch (IOException ignored) { }
                disputed.add(id + " vs " + conflictId);
            } else if (state == Finding.State.accepted) {
                accepted.add(id);
            } else {
                kept.add(id);
            }
        }

        // record what was left open, promote the run record, refresh the human index
        try { Frontier.fromReport(store, inv.id(), inv.open()); } catch (IOException ignored) { }   // a no-op when the run already filed them
        store.write(new Investigation(inv.id(), inv.title(), Finding.State.accepted, inv.writer(),
                inv.recordedAt(), findingIds, inv.open(), inv.body()));
        store.regenerateIndex();
        logProblems(inv, problems);
        store.circulate("review", inv.id() + " → " + accepted.size() + " accepted, "
                + kept.size() + " draft, " + disputed.size() + " disputed");
        return new Outcome(inv.id(), accepted, kept, disputed, dupes, problems);
    }

    /** Whether two triples say the same claim: the same subject and relation, and the same object once written one way. */
    static boolean sameTriple(Finding.Triple a, Finding.Triple b) {
        return a != null && b != null && a.sameKey(b) && Finding.Triple.canon(a.object()).equals(Finding.Triple.canon(b.object()));
    }

    /**
     * The same claim again, when the person disputed or retired it: it is not filed again, their decision holds against later runs, and a
     * note on the claim says where it came back. Its review is left as the person signed it.
     */
    private void metAgain(Finding held, List<String> cited, String title, String claim, Investigation inv, List<String> problems) throws IOException {
        List<Finding.Source> again = new ArrayList<>();
        for (String u : cited) again.add(new Finding.Source(u, "n/a", "met again in " + inv.id()));
        Finding noted = Evidence.toldAgain(store, held, again, reviewerName, claim);
        if (noted != null) index.upsert(noted);
        problems.add("'" + Acquisitions.compress(title, 50) + "' is the claim " + held.id() + ", which is " + held.state() + "; it came back from " + String.join(", ", cited)
                + " and was not filed again, because you " + held.state() + " it and your decision holds");
        store.circulate("review-met-again", title + " = " + held.id() + " (" + held.state() + " by the person)");
    }

    /** The same claim again, when the library set it aside by itself: it gains the source and keeps its state, so later sources can settle it. */
    private void furtherSource(Finding held, List<String> cited, String title, String claim, Investigation inv, List<String> findingIds, List<String> problems) throws IOException {
        List<Finding.Source> again = new ArrayList<>();
        for (String u : cited) again.add(u.startsWith("cite:") ? new Finding.Source(u, u.substring(5), "said again in " + inv.id()) : new Finding.Source(u, "n/a", "said again in " + inv.id()));
        Finding grown = Evidence.toldAgain(store, held, Citations.enrich(store, again, citations), reviewerName, claim);
        if (grown != null) index.upsert(grown);
        if (!findingIds.contains(held.id())) findingIds.add(held.id());
        problems.add("'" + Acquisitions.compress(title, 50) + "' is the claim " + held.id() + ", which is " + held.state() + "; it came back from " + String.join(", ", cited)
                + (grown == null ? ", a source it already has. The library " + held.state() + " it by itself" + howSetAside(held) + ", and a new source can settle it"
                        : " and was added to it as a further source, because the library " + held.state() + " it by itself" + howSetAside(held) + ", so that later sources can settle it"));
        store.circulate("review-source-added", title + " = " + held.id() + " (" + held.state() + " by the library)");
    }

    /** How the library set a claim aside by itself, from its newest note that did: said in brackets for the review's problems line, "" when no note says. */
    static String howSetAside(Finding f) {
        for (int i = f.notes().size() - 1; i >= 0; i--) {
            Finding.Note n = f.notes().get(i);
            if (Finding.contradiction(n)) return " (the review found a claim that says otherwise)";
            if (n.kind().equals("inventory")) {
                // the retraction check disputes a claim through the inventory, after a note of its own: then the reason is the retraction
                for (int j = i - 1; j >= 0; j--) {
                    Finding.Note r = f.notes().get(j);
                    if (r.kind().equals("retracted") && n.text().contains(r.text())) return retracted(r.text());
                }
                return " (a check of its source found that the source does not say it)";
            }
            if (!"person".equals(n.by()) && (n.kind().equals("disputed") || n.kind().equals("retired"))) return " (" + n.by() + ": " + Acquisitions.compress(n.text(), 80) + ")";
        }
        return "";
    }

    private static final Pattern RETRACTED = Pattern.compile("doi:(\\S+) was (partial retraction|retraction|withdrawal|removal)");

    /** The retraction check's reason, in brackets: which paper, and what became of it. */
    static String retracted(String note) {
        Matcher m = RETRACTED.matcher(note);
        if (!m.find()) return " (the retraction check found that a paper it cites was retracted)";
        String what = switch (m.group(2)) { case "withdrawal" -> "withdrawn"; case "removal" -> "removed"; case "partial retraction" -> "partly retracted"; default -> "retracted"; };
        return " (the retraction check found that the paper it cites, doi:" + m.group(1) + ", was " + what + ")";
    }

    // ---- the live seat ---------------------------------------------------------

    /**
     * The real Judge over a drive. Positive directives (the prompt-style rule), JSON-only
     * answers, and the claim-type/volatility definitions spelled out — a small model copies
     * definitions faithfully and garbles anything left implicit.
     */
    /**
     * The extraction prompt. The record's locators are ENUMERATED and the reviewer copies from
     * the list — a constrained choice space, the house rule. Free-form citing produced four
     * different shapes in two days (URL variants, bare arXiv ids, editions, paper names) and
     * each shape dropped true claims at the evidence gate; a model copies literals faithfully
     * and garbles anything it has to compose.
     */
    /** The record's own sections, in order: the write-up's "## " headings, without the frame the runner always writes. */
    static final Set<String> FRAME = Set.of("question", "answer", "sources", "caveats", "cite-check", "checks", "evidence", "references", "what was read", "method");

    static List<String> sections(String investigationBody) {
        List<String> out = new ArrayList<>();
        for (String line : investigationBody.split("\n")) {
            if (!line.startsWith("## ")) continue;
            String h = line.substring(3).strip();
            String key = h.toLowerCase(Locale.ROOT).replaceAll("\\(.*", "").strip();
            if (FRAME.contains(key) || key.startsWith("answer (")) continue;
            if (!out.contains(h)) out.add(h);
        }
        return out;
    }

    /** The sentences the fields of a run add to the extraction prompt: the words for their relations. "" for an ordinary run. */
    static String extractionRule(LibraryStore store, Set<String> fields) {
        StringBuilder b = new StringBuilder();
        for (Profile p : Fields.enabled(store)) if (fields.contains(p.name()) && !p.extractionRule().isBlank()) b.append(' ').append(p.extractionRule().strip());
        return b.toString().strip();
    }

    static String extractPrompt(String investigationBody) { return extractPrompt(investigationBody, ""); }

    /** {@code rule}: what the run's fields add, the words for their relations ({@link #extractionRule}). */
    static String extractPrompt(String investigationBody, String rule) {
        List<String> locators = Acquisitions.urls(investigationBody);
        StringBuilder list = new StringBuilder();
        for (int i = 0; i < locators.size() && i < 60; i++) {
            list.append("  [S").append(i + 1).append("] ").append(locators.get(i)).append('\n');
        }
        return "You are The Librarian's reviewer. From this investigation record, extract "
                + "the atomic findings a future researcher should be able to cite.\n\n"
                + "Answer with a JSON array ONLY:\n"
                + "[{\"title\": \"short claim title\", \"claim\": \"one to three sentences, "
                + "self-contained\", \"claim_type\": \"extraction|synthesis|interpretation|"
                + "speculation\", \"confidence\": \"low|medium|high\", \"volatility\": "
                + "\"fast|slow|stable\", \"sources\": [\"S3\", \"S7\"], "
                + "\"triple\": {\"subject\": \"what the claim is about\", \"predicate\": \"the relation\", \"object\": \"the value\"}, \"found\": true}]\n\n"
                + "found: true when the claim says what a source says. found: false when the claim says that something was looked for and did not turn up "
                + "(no record, no publication, no patent, no mention): write in the claim what was looked for and where, and name in the triple's subject whom or what it was about. "
                + "Such a claim is kept with its date as a place somebody has looked.\n"
                + "The triple is the claim as subject–predicate–object when it has that shape (e.g. keigo | has direct "
                + "English equivalent | none) — omit it when it does not. Use the same subject and predicate wording "
                + "for claims about the same thing, so contradictions line up." + (rule == null || rule.isBlank() ? "" : " " + rule.strip()) + "\n"
                + "Rules: " + sectionRule(investigationBody) + " Each claim stands alone. For sources, use ONLY the "
                + "labels from the SOURCE LIST below (S1, S2, …), copied exactly — a claim whose "
                + "sources are not on the list cannot be shelved. claim_type: extraction = read "
                + "directly from ONE source; synthesis = combined across sources; interpretation = "
                + "a reading or judgment; speculation = a forecast. volatility: fast = true facts "
                + "change within months (releases, prices, vulnerabilities); slow = change over "
                + "years; stable = timeless.\n\nSOURCE LIST (the record's own locators):\n"
                + (list.length() == 0 ? "  (none — this record carries no citable source)\n" : list)
                + "\nINVESTIGATION RECORD:\n" + Fence.wrap("INVESTIGATION RECORD", investigationBody) + "\n" + Fence.rule("INVESTIGATION RECORD");
    }

    /**
     * What the extractor reads: the write-up's answer, evidence table and references — not the verbatim worker
     * notebooks appended after them (a fan run's record is 65-225 KB; the review sent it whole and the drive
     * answered 400 exceed_context_size, so three Tokyo Vice write-ups sat in draft with no claims). What is still
     * over the window is cut in the middle, head and tail kept: the answer opens the record and the references close it.
     */
    /** The reply budget of an extraction, and the prompt's own words around the record. */
    static final int EXTRACT_TOKENS = 1_600, PROMPT_TOKENS = 700;

    static String forExtraction(String body, int contextWindow) {
        String b = body == null ? "" : body;
        for (String appendix : new String[]{"\n## Worker findings", "\n## Sources cited"}) {
            int i = b.indexOf(appendix);
            if (i >= 0) {
                // keep a References section that follows the appendix, if any
                int refs = b.indexOf("\n## References", i);
                b = b.substring(0, i) + (refs >= 0 ? "\n" + b.substring(refs + 1) : "");
            }
        }
        // the library's own Declined section says what the model declined and in which words: none of it is a claim of the report, and a
        // declined sub-question read as one came back as a "not found" finding
        int declined = b.indexOf("\n## Declined\n");
        if (declined >= 0) { int next = b.indexOf("\n## ", declined + 1); b = b.substring(0, declined) + (next >= 0 ? b.substring(next) : ""); }
        // What fits beside the reply: the window less the reply's budget (EXTRACT_TOKENS) and the prompt's own words,
        // at 2.8 chars a token — a record dense with URLs and Japanese runs near 3. A 20,000-character floor used to
        // fill an 8,192-token slot to the last 39 tokens and the extraction was cut off at once (a 9B, 2026-09-10).
        int cap = Math.max(4_000, (int) ((Math.max(contextWindow, 4_096) - EXTRACT_TOKENS - PROMPT_TOKENS) * 2.8));
        if (b.length() <= cap) return b;
        int half = cap / 2;
        return b.substring(0, half) + "\n\n…[" + (b.length() - cap) + " characters cut from the middle of the record]…\n\n" + b.substring(b.length() - half);
    }

    private final Set<String> knownHosts = new HashSet<>();
    private boolean knownHostsLoaded;
    private int sidewaysLeft = 5;

    /**
     * Reading sideways, as fact-checkers do: for a web, blog or forum host this library has never cited before, one
     * search about the site itself, and a note with what came back — so the person sees "first time this library cites
     * x.example; a search for it finds …" beside the claim. At most five a review; a trusted or refused host needs none.
     */
    List<Finding.Note> sideways(List<Finding.Source> sources, SourceRules rules) {
        List<Finding.Note> out = new ArrayList<>();
        if (searcher == null) return out;
        if (!knownHostsLoaded) {
            knownHostsLoaded = true;
            for (Finding f : store.scanFindings().findings()) for (Finding.Source s : f.sources()) { String h = SourceRules.norm(s.locator()); if (!h.isEmpty()) knownHosts.add(h); }
        }
        for (Finding.Source s : sources) {
            if (sidewaysLeft <= 0) break;
            String host = SourceRules.norm(s.locator());
            if (host.isEmpty() || knownHosts.contains(host) || rules.ruleFor(host) != null) continue;
            SourceTier tier = SourceTier.of(s.locator());
            if (tier == SourceTier.scholarly || tier == SourceTier.reference || tier == SourceTier.primary || tier == SourceTier.personal) { knownHosts.add(host); continue; }
            knownHosts.add(host); sidewaysLeft--;
            String found;
            // a stop ends the review here; a search that failed for any other reason is said as that, never as one that found nothing
            try { found = searcher.search("\"" + host + "\" site OR publisher OR about"); }
            catch (Stopping.Requested stop) { throw stop; }
            catch (Exception e) { found = null; }
            boolean answered = found != null && (found.startsWith("results") || found.startsWith("no results"));
            List<String> lines = new ArrayList<>();
            if (answered) for (String line : found.split("\n")) {
                String t = line.strip();
                if (t.matches("^\\d+\\. .*")) { lines.add(t.substring(t.indexOf(' ') + 1)); if (lines.size() == 3) break; }
            }
            String text = "first time this library cites " + host + " (" + tier + "). "
                    + (!answered ? "The search about the site could not be made this time, so the library cannot say yet what else on the web refers to it."
                    : lines.isEmpty() ? "A search about the site found nothing that names it — nothing else on the web refers to it yet." : "A search about the site finds: " + String.join(" · ", lines) + ".");
            out.add(new Finding.Note("source-check", reviewerName, LocalDate.now().toString(), text));
        }
        return out;
    }

    public static Judge driveJudge(DriveClient drive) {
        DeclineJudge declines = DeclineJudge.of(drive);   // a reply with no JSON where JSON was asked is read for a decline
        return new Judge() {
            @Override public String extract(String investigationBody) { return extract(investigationBody, ""); }
            @Override public String extract(String investigationBody, String rule) {
                var msgs = M.createArrayNode();
                String prompt = extractPrompt(forExtraction(investigationBody, drive.contextWindow()), rule);
                msgs.addObject().put("role", "user").put("content", prompt);
                String out = drive.classify(msgs, EXTRACT_TOKENS);
                if (!Declines.hasJson(out)) declines.raise(drive.model(), "list the claims a research report makes, as JSON", out);
                return out;
            }
            @Override public String compare(String candidate, String neighbors) {
                var msgs = M.createArrayNode();
                msgs.addObject().put("role", "user").put("content",
                        "You are The Librarian's reviewer checking a CANDIDATE claim against "
                        + "EXISTING catalog entries.\n\nCANDIDATE:\n" + candidate
                        + "\n\nEXISTING ENTRIES:\n" + neighbors
                        + "\nAnswer with JSON ONLY: {\"verdict\": \"duplicate|contradicts|"
                        + "independent\", \"id\": \"the existing entry id it duplicates or "
                        + "contradicts\"}\nduplicate = the same claim in substance; contradicts = "
                        + "they cannot both be true; independent = a different claim (omit id).");
                String out = drive.classify(msgs, 300);
                if (!Declines.hasJson(out)) declines.raise(drive.model(), "compare a claim with similar claims already on a library's shelves, as JSON", out);
                return out;
            }
        };
    }

    // ---- helpers ---------------------------------------------------------------

    /** The investigation URL a cited URL anchors to, or null. Normalized both ways; what gets
     *  recorded on the finding is always the INVESTIGATION's version, never the reviewer's. */
    static String resolveCited(List<String> invUrls, String citedUrl) {
        if (citedUrl == null) return null;
        // the enumerated form: "S7" (or "[S7]") → the record's 7th locator
        var lab = Pattern.compile("^\\[?S(\\d{1,2})\\]?$").matcher(citedUrl.strip());
        if (lab.matches()) {
            int i = Integer.parseInt(lab.group(1)) - 1;
            return i >= 0 && i < invUrls.size() ? invUrls.get(i) : null;
        }
        String c = norm(citedUrl);
        if (c.isEmpty()) return null;
        for (String inv : invUrls) {
            String n = norm(inv);
            if (n.equals(c) || n.startsWith(c) || c.startsWith(n)) return inv;
        }
        // identifier anchoring: the reviewer may cite "arXiv 2608.01913", a versioned/html
        // variant, or a doi — anchor on the identifier the record itself carries.
        var id = Pattern.compile("(\\d{4}\\.\\d{4,5})|(10\\.\\d{4,9}/[^\\s)\\]>,;\"']+)")
                .matcher(citedUrl);
        while (id.find()) {
            String key = id.group().toLowerCase();
            for (String inv : invUrls) if (inv.toLowerCase().contains(key)) return inv;
        }
        // edition anchoring: "Hosaka 2016, p. 47" anchors to a cite: locator sharing a YEAR and a
        // NAME token — the classicist's citation shape, still unmintable (the record must carry it).
        var ck = Acquisitions.citationKeys(citedUrl);
        boolean hasYear = ck.stream().anyMatch(k -> k.startsWith("y:"));
        boolean hasName = ck.stream().anyMatch(k -> k.startsWith("n:"));
        if (hasYear && hasName) {
            for (String inv : invUrls) {
                if (!inv.startsWith("cite:")) continue;
                var ik = Acquisitions.citationKeys(inv.substring(5));
                boolean year = ck.stream().anyMatch(k -> k.startsWith("y:") && ik.contains(k));
                boolean name = ck.stream().anyMatch(k -> k.startsWith("n:") && ik.contains(k));
                if (year && name) return inv;
            }
        }
        return null;
    }

    private static String norm(String url) {
        if (url == null) return "";
        return url.strip().replaceFirst("^http://", "https://").replaceAll("/+$", "");
    }

    private static <E extends Enum<E>> E enumOr(Class<E> type, String v, E fallback) {
        try {
            return Enum.valueOf(type, v.strip());
        } catch (Exception e) {
            return fallback;
        }
    }

    /** Why a review produced less than it might have, on the crews log — a 9B's empty reviews were silent (2026-09-10). */
    private void logProblems(Investigation inv, List<String> problems) {
        if (problems.isEmpty()) return;
        try { Crews.log(store, "review", inv.id() + ": " + String.join("; ", problems), 0); } catch (Exception ignored) { }
    }

    /**
     * The whole objects of a JSON array that was cut off before its closing bracket: everything up to the last
     * {@code }} that closes a top-level object, then {@code ]}. Null when not even one object is whole.
     */
    static JsonNode salvageArray(String raw) {
        if (raw == null) return null;
        int a = raw.indexOf('[');
        if (a < 0) return null;
        int depth = 0, lastClose = -1;
        boolean inString = false;
        for (int i = a + 1; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (inString) { if (c == '\\') i++; else if (c == '"') inString = false; continue; }
            if (c == '"') inString = true;
            else if (c == '{') depth++;
            else if (c == '}') { depth--; if (depth == 0) lastClose = i; }
        }
        if (lastClose < 0) return null;
        try {
            JsonNode n = M.readTree(raw.substring(a, lastClose + 1) + "]");
            return n.isArray() && !n.isEmpty() ? n : null;
        } catch (Exception e) { return null; }
    }

    private static JsonNode parseArray(String raw) {
        try {
            int a = raw.indexOf('['), b = raw.lastIndexOf(']');
            if (a < 0 || b <= a) return null;
            JsonNode n = M.readTree(raw.substring(a, b + 1));
            return n.isArray() ? n : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static JsonNode parseObject(String raw) {
        try {
            int a = raw.indexOf('{'), b = raw.lastIndexOf('}');
            if (a < 0 || b <= a) return null;
            return M.readTree(raw.substring(a, b + 1));
        } catch (Exception e) {
            return null;
        }
    }
}
