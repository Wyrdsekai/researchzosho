package org.researchzosho.librarian;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * The COUNCIL's verbs — the person's acts on the shelf (the architecture notes, §Governance):
 * promote a draft, dispute a claim, retire an entry. The librarian promotes routine findings on
 * its own; everything canon-level goes through here, by hand, and every act is recorded as a
 * review round signed {@code person} with the content hash it approved. Nothing is ever deleted:
 * retire is a state, dispute keeps both sides, and the file stays where it was.
 *
 * <p>Also the INBOX — the triage surface the first live day exposed: 11 of 16 findings were
 * drafts waiting on the person, and that pile only grows at real research rates. A governance
 * that protects the shelf must not starve it.
 */
public final class Council {

    private final LibraryStore store;
    private List<String> takenOff = List.of();

    public Council(LibraryStore store) {
        this.store = store;
    }

    /**
     * The questions on the waiting list that the last dispute or retirement took off, because the family research wrote them from the
     * claim: they would give it as known. The next {@code genealogy research} writes them again from what stands.
     */
    public List<String> takenOff() { return takenOff; }

    /** One inbox row: what the person needs to decide, at a glance. */
    public record Row(String id, Finding.State state, Finding.ClaimType claimType, SourceTier tier,
                      String title, String recordedAt, boolean stale) { }

    /** Drafts and stale reviews, oldest first — the queue. */
    public List<Row> inbox() {
        List<Row> rows = new ArrayList<>();
        for (Finding f : store.scanFindings().findings()) {
            boolean stale = f.reviewStale();
            if (f.state() != Finding.State.draft && !stale) continue;
            rows.add(new Row(f.id(), f.state(), f.claimType(), SourceTier.strongest(f.sources()),
                    f.title(), f.recordedAt(), stale));
        }
        rows.sort((a, b) -> { int c = a.recordedAt().compareTo(b.recordedAt()); return c != 0 ? c : a.id().compareTo(b.id()); });   // two findings in one second: the id decides, on every OS
        return rows;
    }

    /** Promote to accepted, signed by the person. */
    public Finding accept(String id) throws IOException {
        return transition(id, Finding.State.accepted, "accepted", null);
    }

    /** Mark disputed, with the person's reason appended to the body and a frontier entry. */
    public Finding dispute(String id, String why) throws IOException {
        Finding f = transition(id, Finding.State.disputed, "disputed",
                "\nDISPUTED-BY: person (" + LocalDate.now() + "): " + why.strip() + "\n");
        store.frontier("dispute", f.id() + " — " + why.strip() + " (what evidence would settle it?)");
        return f;
    }

    /** Retire — out of the push and the desk, never off the disk. */
    public Finding retire(String id) throws IOException {
        return transition(id, Finding.State.retired, "retired", null);
    }

    private Finding transition(String id, Finding.State to, String decision, String appendBody)
            throws IOException {
        Finding f = store.finding(id);
        if (f == null) f = store.findingByCode(id);   // the short code the program prints, F-0012, is enough
        if (f == null) throw new IOException("The library has no claim with the code " + id + ". The command researchzosho inbox lists the claims that wait for you, each with its code.");
        int round = f.review() == null ? 1 : f.review().round() + 1;
        String body = appendBody == null ? f.body() : f.body() + appendBody;
        List<Finding.Note> notes = new ArrayList<>(f.notes());
        if (appendBody != null) {   // the structured meta-fact beside the readable body line
            notes.add(new Finding.Note(decision, "person", LocalDate.now().toString(), appendBody.replaceFirst("(?s)^.*?\\): ", "").strip()));
        } else {
            notes.add(new Finding.Note(decision, "person", LocalDate.now().toString(), decision + " by the person"));
        }
        Finding draft = new Finding(f.id(), f.title(), f.subjects(), to, f.claimType(), f.confidence(),
                f.writer(), f.recordedAt(), f.validAsOf(), f.volatility(), f.reviewBy(), f.sources(),
                f.supersedes(), null, body, f.triple(), notes);
        Finding signed = new Finding(draft.id(), draft.title(), draft.subjects(), draft.state(),
                draft.claimType(), draft.confidence(), draft.writer(), draft.recordedAt(),
                draft.validAsOf(), draft.volatility(), draft.reviewBy(), draft.sources(),
                draft.supersedes(),
                new Finding.Review(round, "person", decision, draft.contentHash(), Instant.now().toString()),
                draft.body(), draft.triple(), draft.notes());
        store.write(signed);
        takenOff = List.of();
        if (to == Finding.State.disputed || to == Finding.State.retired) {
            List<String> off = new ArrayList<>();
            // a field that joins only when asked is asked only in a library that holds its work, or about a claim that is its own
            for (Profile p : Profiles.known())
                if (!p.joinsOnlyWhenAsked() || Fields.holdsWork(store, p) || Fields.ofClaim(store, signed).contains(p.name())) off.addAll(p.onDecision(store, signed));
            takenOff = off;
            Frontier.remove(store, new LinkedHashSet<>(takenOff));
        }
        new LibrarianIndex(store).upsert(signed);
        store.circulate("council-" + decision, f.id());
        store.regenerateIndex();
        return signed;
    }
}
