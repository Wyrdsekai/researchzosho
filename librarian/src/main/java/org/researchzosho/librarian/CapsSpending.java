package org.researchzosho.librarian;

import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The public program's answer to {@link Spending}: the account's caps against its month in the store. A use whose payer is the
 * operator is refused once the month's dollars are spent; a job once as many of the account's jobs are going as it may have at once;
 * a page added by its address once the month's fetches are used. A use that may start gets a ceiling: a job the lesser of its own
 * cap and what is left of the month, any other use what is left of the month. The person's own key's work counts against no dollars.
 */
final class CapsSpending implements Spending {
    /** The kinds of use that are jobs, which count against the jobs at once. A night is the service's to schedule, and is not one. */
    static final Set<String> JOBS = Set.of(SpendPurse.RESEARCH, SpendPurse.TRANSLATION, SpendPurse.PICTURE, SpendPurse.OTHER);
    /** The use of a page added by its address. */
    static final String ADD = "add";
    /** The use of a document a person sends from their own computer: asked for so the keeper's storage is checked. */
    static final String UPLOAD = "upload";
    /** The uses that add to a library, refused while its keeper's libraries hold as much as the storage cap lets them. */
    static final Set<String> ADDS = Set.of(ADD, UPLOAD, SpendPurse.RESEARCH, SpendPurse.TRANSLATION, SpendPurse.PICTURE);

    /** The work the service's own scheduler starts in a library, for its keeper. */
    static final String NIGHT = SpendPurse.NIGHTLY;

    private final AccountStore store;

    CapsSpending(AccountStore store) { this.store = store; }

    @Override public Answer mayStart(Use use) throws IOException {
        Map<String, Long> caps = Caps.of(store, use.account());
        String month = AccountStore.month(System.currentTimeMillis());
        long spent = 0, fetches = 0;
        for (AccountStore.MonthTotal t : store.month(use.account(), month)) {
            if (t.payer().equals("operator")) spent += t.micros();
            if (t.kind().equals("fetch") && t.payer().equals("operator")) fetches += t.quantity();
        }
        boolean operator = !"own".equals(use.payer());
        long monthCap = caps.get(Caps.MONTH_DOLLARS);
        if (operator && monthCap >= 0 && spent >= monthCap) return Answer.no(monthUsed(monthCap));
        if (operator && (use.kind().equals(ADD) || use.kind().equals(SpendPurse.RESEARCH)) && caps.get(Caps.FETCHES) >= 0 && fetches >= caps.get(Caps.FETCHES))   // both read pages
            return Answer.no(fetchesUsed(caps.get(Caps.FETCHES)));
        if (ADDS.contains(use.kind())) {   // what a library holds is its keeper's: checked against the keeper's storage, whoever asks
            AccountStore.Library lib = store.library(use.library());
            String keeper = lib == null ? use.account() : lib.owner();
            long cap = Caps.of(store, keeper).get(Caps.STORAGE_MB);
            if (cap >= 0 && store.storedBytes(keeper) >= cap * 1_000_000L) return Answer.no(storageFull(cap));
        }
        boolean job = JOBS.contains(use.kind());
        if (job && caps.get(Caps.JOBS_AT_ONCE) >= 0) {
            long day = System.currentTimeMillis() - 24 * 3_600_000L;   // a use a library never said ended (it stopped for good) holds nothing after a day
            List<AccountStore.Use> going = store.openUses(use.account()).stream().filter(u -> JOBS.contains(u.kind()) && u.openedAt() > day).toList();
            if (going.size() >= caps.get(Caps.JOBS_AT_ONCE))
                return Answer.no(Pages.t("This account already has as many jobs going as it may have at once ({0}). Ask again when one has ended.", String.valueOf(caps.get(Caps.JOBS_AT_ONCE))));
        }
        long left = operator && monthCap >= 0 ? monthCap - spent : -1;
        long ceiling = left;
        if (job && caps.get(Caps.JOB_DOLLARS) >= 0) ceiling = ceiling < 0 ? caps.get(Caps.JOB_DOLLARS) : Math.min(ceiling, caps.get(Caps.JOB_DOLLARS));
        long minutes = job ? caps.get(Caps.JOB_MINUTES) : -1;
        String said = use.estimateMicros() > 0 && left >= 0
                ? Pages.t("About {0} on {1}; {2} left this month.", Caps.dollars(use.estimateMicros()), use.entry(), Caps.dollars(left)) : "";
        return new Answer(true, said, ceiling, minutes);
    }

    /** The first day of next month, as the person's language writes a day (1 Dec, 12月1日, 1. Dez.). */
    static String nextMonth() { return Chrome.now().dayTime(LocalDate.now(ZoneId.systemDefault()).withDayOfMonth(1).plusMonths(1).toString()); }

    /** What a person is told when the month's dollars are spent. */
    static String monthUsed(long cap) {
        return Pages.t("This account has used its {0} for this month. It starts again on {1}, or sooner if whoever runs the service raises it.", Caps.dollars(cap), nextMonth());
    }

    /** What a person is told when the keeper's libraries hold as much as they may. */
    static String storageFull(long capMb) {
        return Pages.t("This library's keeper has stored as much as they may ({0}), so nothing more can be added. Removing something makes room; reading goes on.", Caps.size(capMb));
    }

    /** What a person is told when the month's fetches are made. */
    static String fetchesUsed(long cap) {
        return Pages.t("This account has fetched as many pages this month as it may ({0}). It starts again on {1}.", String.valueOf(cap), nextMonth());
    }

    /** The pages an account has fetched this month at the operator's cost. */
    static long fetched(AccountStore store, String account) throws IOException {
        long n = 0;
        for (AccountStore.MonthTotal t : store.month(account, AccountStore.month(System.currentTimeMillis()))) if (t.kind().equals("fetch") && t.payer().equals("operator")) n += t.quantity();
        return n;
    }

    /** What a person is told when the month's searches are made. */
    static String searchesUsed(long cap) {
        return Pages.t("This account has made as many searches this month as it may ({0}). It starts again on {1}.", String.valueOf(cap), nextMonth());
    }

    /** The store has what was spent already: nothing to tell anyone. */
    @Override public void spent(Use use, Actual actual) { }
}
