package org.researchzosho.librarian;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * The account service's side of a use (0.8.0, stage 3): asked for before it spends, written down as it was answered, closed with
 * what it came to. The answer is {@link Spending}'s; when that does not answer, or answers with an error, the use does not start.
 */
final class Uses {
    private Uses() { }

    private static final Logger LOG = LoggerFactory.getLogger(Uses.class);

    /** The paths a library asks and tells the service at. */
    static final String START = "/internal/use/start", END = "/internal/use/end", STATE = "/internal/use/state";

    /** What answers for every use of the service: the built-in caps, or the spending plug-in a setting names (stage 3, step 4). */
    static Spending spending(AccountStore store, String issuer) {
        String url = PluginSpending.url();
        return url == null ? new CapsSpending(store) : new PluginSpending(store, url, issuer);
    }

    /** Whether the built-in caps answer (no spending plug-in is named): the gates then hold the month's caps as well as each use's ceiling. */
    static boolean builtIn() { return PluginSpending.url() == null; }

    /** A use asked for and written down: {@code open} with its ceilings, or {@code refused} with the sentence the person is told. */
    private static AccountStore.Use startNow(AccountStore store, Spending spending, String account, String library, String kind, String entry, String payer, long estimateMicros, long estimateMinutes, String job) throws IOException {
        String id = AccountStore.id("use_");
        Spending.Use asked = new Spending.Use(id, account, library, kind, entry == null ? "" : entry, payer, Math.max(0, estimateMicros), Math.max(0, estimateMinutes), job == null ? "" : job);
        Spending.Answer a;
        try { a = spending.mayStart(asked); }
        catch (IOException | RuntimeException e) {
            LOG.warn("Whether the account {} may start a {} could not be answered ({}); it is not started.", account, kind, e.getMessage());
            a = Spending.Answer.no(Pages.t("The service could not check this account's spending just now, so nothing was started. Ask again in a minute."));
        }
        if (a == null) a = Spending.Answer.no(Pages.t("The service could not check this account's spending just now, so nothing was started. Ask again in a minute."));
        AccountStore.Use u = new AccountStore.Use(id, account, library, kind, asked.entry(), payer, asked.job(), asked.estimateMicros(), asked.estimateMinutes(), a.ceilingMicros(), a.ceilingMinutes(),
                a.yes() ? "open" : "refused", a.said() == null ? "" : a.said(), System.currentTimeMillis(), 0, 0);
        try { store.addUse(u); }
        catch (IOException | RuntimeException e) {   // said yes, and not written: the spending is told it ended at nothing, so the plug-in's use is closed too
            if (a.yes()) { try { spending.spent(asked, new Spending.Actual(0, 0, 0, 0, false)); } catch (IOException | RuntimeException told) { LOG.warn("The use {} the spending let start, and the store could not write, could not be told as ended: {}", id, told.getMessage()); } }
            throw e;
        }
        return u;
    }

    /**
     * {@link #start}: with the built-in caps, the jobs going counted and the new use written in one step under the account's row (BILL 8),
     * so two starts at one moment cannot both pass a cap of one; a plug-in keeps its own counts, and is asked outside any transaction.
     */
    static AccountStore.Use start(AccountStore store, Spending spending, String account, String library, String kind, String entry, String payer, long estimateMicros, long estimateMinutes, String job) throws IOException {
        if (!(spending instanceof CapsSpending) || account == null) return startNow(store, spending, account, library, kind, entry, payer, estimateMicros, estimateMinutes, job);
        return store.holdingAccount(account, () -> startNow(store, spending, account, library, kind, entry, payer, estimateMicros, estimateMinutes, job));
    }

    /**
     * A use closed as {@code state}, and what it came to told in the same step: written to the spending plug-in's outbox in the store's
     * transaction that closes it, so a use is never closed without it. Null when there was no such open use; an error, and the use still
     * open, when what it came to could not be written (the library tells its end again).
     */
    static AccountStore.Use end(AccountStore store, Spending spending, String id, String state) throws IOException {
        AccountStore.Use[] closed = {null};
        if (!store.closeUse(id, state, u -> { closed[0] = u; spending.spent(asked(u), actual(store, u)); })) return null;
        return closed[0];
    }

    private static Spending.Use asked(AccountStore.Use u) { return new Spending.Use(u.id(), u.account(), u.library(), u.kind(), u.entry(), u.payer(), u.estimateMicros(), u.estimateMinutes(), u.job()); }

    /** What a use came to, from its rows as they are now. */
    private static Spending.Actual actual(AccountStore store, AccountStore.Use u) throws IOException {
        long tokens = 0, searches = 0, fetches = 0; boolean estimated = false;
        for (AccountStore.MonthTotal t : store.useTotals(u.id())) {
            if (t.kind().equals("model")) { tokens += t.tokens(); estimated |= t.estimated() > 0; }
            if (t.kind().equals("search")) searches += t.quantity();
            if (t.kind().equals("fetch")) fetches += t.quantity();
        }
        return new Spending.Actual(u.actualMicros(), tokens, searches, fetches, estimated);
    }

    /** A use that is not a job's (a chat turn, a page's call, a page added) and still open after this long was left by a library that stopped. */
    static final long REQUEST_STALE_MS = 2 * 3_600_000L;
    /** A job's use open this long, with nothing counted under it for {@link #JOB_QUIET_MS}, has no job any more (its record is gone). */
    static final long JOB_STALE_MS = 24 * 3_600_000L, JOB_QUIET_MS = 6 * 3_600_000L;
    /** How long after its close a use's rows are looked at again for what was counted after it: longer than a call may take. */
    static final long LATE_MS = 2 * 24 * 3_600_000L;
    /** How often the service sweeps. */
    static volatile long SWEEP_MS = 5 * 60_000L;

    /**
     * The service's sweep of the uses, every few minutes in one of its processes: a use nobody will end is closed as failed, and what it
     * came to told (written to the outbox with its close); a use under which more was counted after it closed is told again with its
     * whole. How many were closed.
     */
    static int sweep(AccountStore store, Spending spending, long now) {
        int n = 0;
        try {
            for (AccountStore.Use u : store.staleUses(now - REQUEST_STALE_MS, now - JOB_STALE_MS, now - JOB_QUIET_MS)) {
                try {
                    if (end(store, spending, u.id(), "failed") == null) continue;
                    n++;
                    LOG.info("The use {} ({} of the account {}, opened {} minutes ago) was never said ended; it is closed as failed.", u.id(), u.kind(), u.account(), (now - u.openedAt()) / 60_000);
                } catch (IOException | RuntimeException e) { LOG.warn("The use {} could not be closed: {}", u.id(), e.getMessage()); }
            }
            for (AccountStore.Use u : store.lateUses(now - LATE_MS)) {
                try { store.useCameTo(u.id(), u.actualMicros(), c -> spending.spentAgain(asked(c), actual(store, c))); }
                catch (IOException | RuntimeException e) { LOG.warn("What the use {} came to could not be told again: {}", u.id(), e.getMessage()); }
            }
        } catch (IOException e) { LOG.warn("The uses could not be swept: {}", e.getMessage()); }
        return n;
    }
}
