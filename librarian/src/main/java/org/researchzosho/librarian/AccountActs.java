package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import org.researchzosho.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

/**
 * What changes an account as a whole (0.8.0, stage 4): suspending it and letting it back, deleting it after its grace or at once, a
 * person's own deletion with a library they keep handed to one of its people, and its removal when its day comes; and the audit log
 * every such act, and every sign-in, key and member, is written to. The pages, the command line and the scheduler act through here.
 */
final class AccountActs {
    private AccountActs() { }

    private static final Logger LOG = LoggerFactory.getLogger(AccountActs.class);

    /** How many days a deleted account waits before it is removed; {@code accounts_delete_days}, 30 unless set. */
    static final String DELETE_DAYS = "RESEARCHZOSHO_ACCOUNTS_DELETE_DAYS";
    /** How many days the audit log keeps an entry; {@code accounts_audit_days}, 400 unless set. */
    static final String AUDIT_DAYS = "RESEARCHZOSHO_ACCOUNTS_AUDIT_DAYS";
    /** How far back a person sees their own account's entries. */
    static final int OWN_DAYS = 90;

    static int deleteDays() { return Math.max(0, Config.getInt(DELETE_DAYS, 30)); }
    static int auditDays() { return Math.max(1, Config.getInt(AUDIT_DAYS, 400)); }

    /** Who acts, and how: an account by a session or a key, the command line, or the service itself; and from which address. */
    record By(String actor, String via, String address) {
        static By cli() { return new By("the command line", "cli", ""); }
        static By service() { return new By("the service", "service", ""); }
        static By of(AccountService.Who who, HttpExchange x) {
            InetAddress from = x == null ? null : Proxy.client(x);
            return new By(who == null ? "" : who.account().id(), who == null ? "" : who.key() != null ? "key" : "session", from == null ? "" : from.getHostAddress());
        }
        static By person(String account, String via, HttpExchange x) {
            InetAddress from = x == null ? null : Proxy.client(x);
            return new By(account, via, from == null ? "" : from.getHostAddress());
        }
    }

    /** An act written to the audit log; a log that cannot be written is said in the service's log, and the act stands. */
    static void audit(AccountStore store, By by, String act, String subject, String library, ObjectNode detail) {
        try { store.audit(by.actor(), by.via(), act, subject, library, by.address(), detail == null || detail.isEmpty() ? "" : detail.toString()); }
        catch (IOException e) { LOG.warn("The audit log could not be written ({} {}): {}", act, subject, e.getMessage()); }
    }

    static ObjectNode detail() { return Statement.M.createObjectNode(); }

    // ---- what a person is told ----

    static String suspendedSaid() { return Pages.t("Whoever runs this service has suspended this account. Ask them."); }

    static String deletingSaid(long at) {
        return Pages.t("This account is to be deleted on {0}. Until then you can cancel that on your account page.", Chrome.now().dayTime(Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalDate().toString()));
    }

    /** Why an account that is not active may do nothing: suspended, or deleting on its day. */
    static String notActiveSaid(AccountStore store, AccountStore.Account a) throws IOException {
        return "deleting".equals(a.state()) ? deletingSaid(store.removeAt(a.id())) : suspendedSaid();
    }

    static String closedSaid(AccountStore.Library l) { return Pages.t("The library {0} is closed for now: the account that keeps it is not open.", l.title()); }

    /** Whether a library's keeper is open; a library of a keeper suspended or being deleted opens for nobody. */
    static boolean keeperOpen(AccountStore store, AccountStore.Library l) throws IOException {
        AccountStore.Account k = l == null ? null : store.account(l.owner());
        return k != null && k.active();
    }

    // ---- the acts ----

    /**
     * Suspended at once: its browsers signed out; its keys kept and refused; its running jobs stopped by the gates and the libraries'
     * cap watch as a cap stops them (kept, to resume); the libraries it keeps closed to everyone and put to sleep once quiet. Nothing
     * is deleted. False when it was not active.
     */
    static boolean suspend(AccountStore store, AccountStore.Account a, By by) throws IOException {
        if (!store.setState(a.id(), "active", "suspended")) return false;
        store.endSessions(a.id());
        audit(store, by, "account_suspended", a.id(), "", null);
        return true;
    }

    /** Let back as it was: its keys work again; its stopped jobs wait for the person's resume. False when it was not suspended. */
    static boolean unsuspend(AccountStore store, AccountStore.Account a, By by) throws IOException {
        if (!store.setState(a.id(), "suspended", "active")) return false;
        audit(store, by, "account_unsuspended", a.id(), "", null);
        return true;
    }

    /** To be removed after {@code days} (0: at once): frozen as a suspended account is until then. The day it is removed on. */
    static long delete(AccountStore store, AccountStore.Account a, int days, By by) throws IOException {
        long at = System.currentTimeMillis() + days * 86_400_000L;
        if (!store.deleting(a.id(), at)) throw new IOException("The account of " + a.name() + " is removed already.");
        store.endSessions(a.id());
        ObjectNode d = detail(); d.put("days", days);
        audit(store, by, "deletion_asked", a.id(), "", d);
        if (days == 0) remove(store, store.account(a.id()), by);
        return at;
    }

    /**
     * Whether an account's deletion was asked by someone else than its person: whoever runs the service. Such a deletion is a moderation's,
     * and its person cannot call it off; the operator alone keeps the account (the owner, 2026-11-18).
     */
    static boolean deletedByAnother(AccountStore store, AccountStore.Account a) throws IOException {
        for (AccountStore.Audit asked : store.audits(a.id(), "deletion_asked", 0, Long.MAX_VALUE, 5)) if (asked.subject().equals(a.id())) return !asked.actor().equals(a.id());
        return false;
    }

    /** The deletion called off within its grace: the account is as it was before. False when it was not being deleted. */
    static boolean cancelDeletion(AccountStore store, AccountStore.Account a, By by) throws IOException {
        if (!store.setState(a.id(), "deleting", "active")) return false;
        audit(store, by, "deletion_cancelled", a.id(), "", null);
        // the libraries it handed to one of their people in the request come back, where that person still keeps them (BILL, low)
        List<AccountStore.Audit> asked = store.audits(a.id(), "deletion_asked", 0, Long.MAX_VALUE, 1);
        if (!asked.isEmpty()) {
            for (AccountStore.Audit h : store.audits(a.id(), "library_handed_over", asked.get(0).at() - 10 * 60_000L, asked.get(0).at() + 1, 100)) {
                if (!h.actor().equals(a.id())) continue;
                AccountStore.Library l = store.library(h.library());
                if (l == null || !l.active() || !l.owner().equals(h.subject())) continue;
                store.handOver(l.id(), a.id());
                store.setMember(l.id(), h.subject(), "write", a.id());   // who kept it meanwhile goes on reading and writing it
                ObjectNode d = detail(); d.put("from", h.subject());
                audit(store, by, "library_handed_over", a.id(), l.id(), d);
            }
        }
        return true;
    }

    /** A library handed to one of its people, who keeps it from now on. */
    static void handOver(AccountStore store, AccountStore.Library l, AccountStore.Account to, By by) throws IOException {
        if (store.level(l, to.id()) == null || to.id().equals(l.owner())) throw new IOException(Pages.t("{0} is not one of the people of {1}.", to.name(), l.title()));
        String from = l.owner();
        store.handOver(l.id(), to.id());
        ObjectNode d = detail(); d.put("from", from);
        audit(store, by, "library_handed_over", to.id(), l.id(), d);
    }

    /**
     * Removed now: the libraries it keeps with their copies and what was set aside for them, then everything of the account but its id,
     * its uses and its usage ({@link AccountStore#forgetAccount}).
     */
    static void remove(AccountStore store, AccountStore.Account a, By by) throws IOException {
        PrintStream quiet = new PrintStream(OutputStream.nullOutputStream());
        for (AccountStore.Library l : store.librariesKeptBy(a.id())) {
            AccountsCli.places(store, l, "delete", null, true, quiet);
            audit(store, by, "library_deleted", a.id(), l.id(), null);
        }
        // every other library it owns, in any state: what was set aside for it goes at the next sweep, on whichever machine holds it, and a
        // library being moved is not served again (round F, the second review: a library let go stayed aside its whole 30 days, with the
        // account's name on it, after the account was removed)
        for (AccountStore.Library l : store.librariesOwnedBy(a.id())) {
            if (l.active()) continue;
            for (AccountStore.Aside aside : store.asides(l.id())) store.asideDueNow(aside.path());
            if (!l.state().equals("removed")) store.libraryState(l.id(), "removed");
        }
        // what it had open is closed now, with what it spent, before the plug-in hears the account is gone (round F, money 6)
        Spending spending = Uses.spending(store, AccountService.issuer(""));
        for (AccountStore.Use u : store.openUses(a.id())) { try { Uses.end(store, spending, u.id(), "failed"); } catch (IOException e) { LOG.warn("The use {} could not be closed at the account's removal: {}", u.id(), e.getMessage()); } }
        store.forgetAccount(a.id());
        audit(store, by.actor().equals(a.id()) ? new By(by.actor(), by.via(), "") : by, "account_removed", a.id(), "", null);   // at its own word: none of its addresses kept
        if (!Uses.builtIn()) toldRemoved(store, a.id());
    }

    /** An account removed, told to the spending plug-in by its id, through the outbox, in its order (BILL 12; SPENDING_PLUGIN.md, account_deleted). */
    static void toldRemoved(AccountStore store, String account) throws IOException {
        store.outboxAdd("del_" + account, Statement.M.createObjectNode().put("notice", "account_deleted").put("account", account).toString());
    }

    /** The accounts whose day has come, removed; the audit log's oldest pruned. For the service's scheduler. */
    static void due(AccountStore store) {
        try {
            for (AccountStore.Account a : store.dueForRemoval(System.currentTimeMillis())) {
                try { remove(store, a, By.service()); LOG.info("The account {} was removed: its day had come.", a.id()); }
                catch (IOException e) { LOG.warn("The account {} could not be removed yet: {}; it is tried again at the next round.", a.id(), e.getMessage()); }
            }
        } catch (IOException e) { LOG.warn("The accounts due for removal could not be read: {}", e.getMessage()); }
    }

    private static volatile long prunedAt = 0;

    /** Once a day: the audit log's entries older than {@link #auditDays()} removed. */
    static void prune(AccountStore store) {
        long now = System.currentTimeMillis();
        if (now - prunedAt < 86_400_000L) return;
        prunedAt = now;
        try { int n = store.auditPrune(now - auditDays() * 86_400_000L); if (n > 0) LOG.info("{} audit entries older than {} days removed.", n, auditDays()); }
        catch (IOException e) { LOG.warn("The audit log could not be pruned: {}", e.getMessage()); }
    }
}
