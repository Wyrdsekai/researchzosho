package org.researchzosho.librarian;

import org.researchzosho.drive.DriveClient;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The model calls of one piece of work (a chat turn, a job, the nightly work), by the server and model they went to, put on the library's
 * spending ledger when the work ends: one row a model, saying what the calls were for. Every call's token counts reach it from the one place
 * the drive client reads them ({@link DriveClient.Purse}); a research run's own calls go to the run's count instead, and a step that puts
 * its calls on the ledger itself (a translation's seats, the pictures' eyes) keeps them out, so no call is counted twice.
 *
 * <p>Only calls that cost money are written: on a hosted service, or on a named model with prices. A model on the library's own machine
 * with no prices costs nothing and leaves no row.
 */
final class SpendPurse implements DriveClient.Purse, AutoCloseable {

    /** What the calls were for, as the ledger's {@code for} field and the summary's uses say it. */
    static final String CHAT = "chat", NIGHTLY = "nightly", RESEARCH = "research", TRANSLATION = "translation", PICTURE = "picture", OTHER = "other";

    private final LibraryStore store;
    private final String purpose, jobId;
    private final Map<String, long[]> byModel = new LinkedHashMap<>();   // drive \n model → prompt, cached, completion, calls
    private DriveClient.Purse was;
    private boolean closed;

    private SpendPurse(LibraryStore store, String purpose, String jobId) { this.store = store; this.purpose = purpose; this.jobId = jobId == null ? "" : jobId; }

    /** Opens a purse for the work this thread does from now until {@link #close}; the purse it replaces comes back at the close. */
    static SpendPurse open(LibraryStore store, String purpose, String jobId) {
        SpendPurse p = new SpendPurse(store, purpose, jobId);
        p.was = DriveClient.useFor(p);
        return p;
    }

    /** What a job of this kind is for. */
    static String purposeOfJob(String kind) {
        return switch (kind == null ? "" : kind) {
            case "research", "career-search", "career-read", HomeFind.JOB_KIND -> RESEARCH;
            case "crews" -> NIGHTLY;
            case "translate", Translation.JOB_KIND -> TRANSLATION;
            case VideoRecall.JOB_KIND -> PICTURE;
            default -> OTHER;
        };
    }

    @Override public synchronized void add(String drive, String model, long prompt, long cached, long completion) {
        if (closed) { Spend.calls(store, purpose, jobId, drive, model, new long[]{prompt, cached, completion, 1}); return; }   // a call that ends after its work: written alone
        long[] u = byModel.computeIfAbsent((drive == null ? "" : drive) + "\n" + (model == null ? "" : model), k -> new long[4]);
        u[0] += prompt; u[1] += cached; u[2] += completion; u[3]++;
    }

    /** Puts the calls on the ledger, one row a model, and gives the thread back the purse it had. */
    @Override public void close() {
        DriveClient.useFor(was);
        Map<String, long[]> rows;
        synchronized (this) { closed = true; rows = new LinkedHashMap<>(byModel); byModel.clear(); }
        for (Map.Entry<String, long[]> e : rows.entrySet()) {
            String[] dm = e.getKey().split("\n", 2);
            Spend.calls(store, purpose, jobId, dm[0], dm[1], e.getValue());
        }
    }

    private static volatile LibraryStore outside;

    /**
     * The calls made outside any piece of work (a request answered at once): each written alone, for other work, on the ledger of the
     * library this process serves. One library a process: a second daemon in the same process takes it over.
     */
    static synchronized void outsideWork(LibraryStore store) {
        outside = store;
        DriveClient.outsideWork((drive, model, prompt, cached, completion) -> Spend.calls(store, OTHER, "", drive, model, new long[]{prompt, cached, completion, 1}));
    }

    /** The daemon of this library stops: its calls outside work go nowhere any more, unless another library has taken them over since. */
    static synchronized void outsideWorkEnds(LibraryStore store) {
        if (outside != store) return;
        outside = null;
        DriveClient.outsideWork(null);
    }
}
