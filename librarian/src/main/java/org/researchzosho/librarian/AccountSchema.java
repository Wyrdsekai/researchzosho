package org.researchzosho.librarian;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * The account store's tables, as a numbered list of steps (0.8.0). A step creates a table or an index, or adds a column; a step never
 * drops, renames or rewrites anything, and a step that has been released is never edited: a change is a new step at the end. At start
 * each step not yet applied runs in one transaction that first writes its number down, so of two processes starting together one applies
 * it and the other finds it applied. The SQL is the plain kind both SQLite and PostgreSQL take: text, whole numbers, no serial columns.
 */
final class AccountSchema {
    private AccountSchema() { }

    /** One step: its statements, run in order in one transaction. */
    record Step(int number, List<String> sql) { }

    static final List<Step> STEPS = List.of(
            new Step(1, List.of(
                    "CREATE TABLE accounts (id TEXT PRIMARY KEY, name TEXT NOT NULL, login TEXT NOT NULL UNIQUE, mail TEXT NOT NULL DEFAULT '', password_hash TEXT NOT NULL DEFAULT '', admin INTEGER NOT NULL DEFAULT 0, state TEXT NOT NULL DEFAULT 'active', made_at BIGINT NOT NULL)",
                    "CREATE TABLE identities (issuer TEXT NOT NULL, subject TEXT NOT NULL, account_id TEXT NOT NULL, mail TEXT NOT NULL DEFAULT '', bound_at BIGINT NOT NULL, PRIMARY KEY (issuer, subject))",
                    "CREATE TABLE sessions (hash TEXT PRIMARY KEY, account_id TEXT NOT NULL, library_id TEXT NOT NULL DEFAULT '', label TEXT NOT NULL DEFAULT '', made_at BIGINT NOT NULL, expires_at BIGINT NOT NULL)",
                    "CREATE INDEX sessions_account ON sessions (account_id)",
                    "CREATE TABLE api_keys (id TEXT PRIMARY KEY, hash TEXT NOT NULL UNIQUE, prefix TEXT NOT NULL, account_id TEXT NOT NULL, library_id TEXT NOT NULL, level TEXT NOT NULL, label TEXT NOT NULL, made_at BIGINT NOT NULL, used_day TEXT NOT NULL DEFAULT '')",
                    "CREATE INDEX api_keys_account ON api_keys (account_id)",
                    "CREATE TABLE libraries (id TEXT PRIMARY KEY, name TEXT NOT NULL UNIQUE, title TEXT NOT NULL, owner_account_id TEXT NOT NULL, folder TEXT NOT NULL, state TEXT NOT NULL DEFAULT 'active', address TEXT NOT NULL DEFAULT '', made_at BIGINT NOT NULL)",
                    "CREATE INDEX libraries_owner ON libraries (owner_account_id)",
                    "CREATE TABLE members (library_id TEXT NOT NULL, account_id TEXT NOT NULL, level TEXT NOT NULL, added_by TEXT NOT NULL DEFAULT '', added_at BIGINT NOT NULL, PRIMARY KEY (library_id, account_id))",
                    "CREATE INDEX members_account ON members (account_id)",
                    "CREATE TABLE invites (code_hash TEXT PRIMARY KEY, account_id TEXT NOT NULL, made_by TEXT NOT NULL DEFAULT '', made_at BIGINT NOT NULL, expires_at BIGINT NOT NULL, used_at BIGINT NOT NULL DEFAULT 0)",
                    "CREATE TABLE signing_keys (kid TEXT PRIMARY KEY, private_key TEXT NOT NULL, public_jwk TEXT NOT NULL, made_at BIGINT NOT NULL, retired_at BIGINT NOT NULL DEFAULT 0)")),
            // 0.8.0, placement: which node holds a library and until when (the lease), the count of its starts (the epoch a library and
            // every statement made for it carry), when work of its own is next due, and the day of its last night; the nodes; and the
            // duties one process of several holds at a time (the scheduler)
            new Step(2, List.of(
                    "ALTER TABLE libraries ADD COLUMN node TEXT NOT NULL DEFAULT ''",
                    "ALTER TABLE libraries ADD COLUMN epoch BIGINT NOT NULL DEFAULT 0",
                    "ALTER TABLE libraries ADD COLUMN lease_until BIGINT NOT NULL DEFAULT 0",
                    "ALTER TABLE libraries ADD COLUMN due_at BIGINT NOT NULL DEFAULT 0",
                    "ALTER TABLE libraries ADD COLUMN night_day TEXT NOT NULL DEFAULT ''",
                    "CREATE TABLE nodes (id TEXT PRIMARY KEY, address TEXT NOT NULL DEFAULT '', root TEXT NOT NULL DEFAULT '', version TEXT NOT NULL DEFAULT '', seen_at BIGINT NOT NULL DEFAULT 0)",
                    "CREATE TABLE duties (name TEXT PRIMARY KEY, holder TEXT NOT NULL, until BIGINT NOT NULL)")),
            // 0.8.0, places: when a library's copy was last brought up to date and of which start, when it was taken out of service,
            // and the folders and copies set aside, removed when their days of grace are over
            new Step(3, List.of(
                    "ALTER TABLE libraries ADD COLUMN copied_at BIGINT NOT NULL DEFAULT 0",
                    "ALTER TABLE libraries ADD COLUMN copied_epoch BIGINT NOT NULL DEFAULT 0",
                    "ALTER TABLE libraries ADD COLUMN removed_at BIGINT NOT NULL DEFAULT 0",
                    "CREATE TABLE asides (path TEXT PRIMARY KEY, library_id TEXT NOT NULL, why TEXT NOT NULL, until BIGINT NOT NULL)")),
            // 0.8.0, two processes of one service: a sign-in through the provider begun at one and ended at the other, and the count of
            // failed sign-ins, which every process reads
            new Step(4, List.of(
                    "CREATE TABLE pending_signins (state TEXT PRIMARY KEY, value TEXT NOT NULL, made_at BIGINT NOT NULL)",
                    "CREATE TABLE signin_tries (try_key TEXT NOT NULL, at BIGINT NOT NULL)",
                    "CREATE INDEX signin_tries_key ON signin_tries (try_key)")),
            // 0.8.0, stage 3, counting and caps: each use an account asked for (a research run, a chat turn, a translation, a night, a page
            // added by its address) with its estimate, its ceiling and what it came to; what was spent, one row a kind of cost; the month's
            // totals a cap is checked against, kept in the same transaction; and the caps, the service's ('*') and each account's own.
            // Dollars are whole millionths, so both stores add them up the same.
            new Step(5, List.of(
                    "CREATE TABLE uses (id TEXT PRIMARY KEY, account_id TEXT NOT NULL, library_id TEXT NOT NULL, kind TEXT NOT NULL, entry TEXT NOT NULL DEFAULT '', payer TEXT NOT NULL DEFAULT 'operator', job TEXT NOT NULL DEFAULT '', "
                            + "estimate_micros BIGINT NOT NULL DEFAULT 0, estimate_minutes BIGINT NOT NULL DEFAULT 0, ceiling_micros BIGINT NOT NULL DEFAULT 0, ceiling_minutes BIGINT NOT NULL DEFAULT 0, "
                            + "state TEXT NOT NULL, said TEXT NOT NULL DEFAULT '', opened_at BIGINT NOT NULL, closed_at BIGINT NOT NULL DEFAULT 0, actual_micros BIGINT NOT NULL DEFAULT 0)",
                    "CREATE INDEX uses_account ON uses (account_id, state)",
                    "CREATE TABLE usage (id TEXT PRIMARY KEY, at BIGINT NOT NULL, month TEXT NOT NULL, account_id TEXT NOT NULL, library_id TEXT NOT NULL, use_id TEXT NOT NULL DEFAULT '', kind TEXT NOT NULL, "
                            + "payer TEXT NOT NULL, entry TEXT NOT NULL DEFAULT '', tokens_in BIGINT NOT NULL DEFAULT 0, tokens_cached BIGINT NOT NULL DEFAULT 0, tokens_out BIGINT NOT NULL DEFAULT 0, "
                            + "quantity BIGINT NOT NULL DEFAULT 0, bytes BIGINT NOT NULL DEFAULT 0, micros BIGINT NOT NULL DEFAULT 0, estimated INTEGER NOT NULL DEFAULT 0)",
                    "CREATE INDEX usage_use ON usage (use_id)",
                    "CREATE INDEX usage_account ON usage (account_id, month)",
                    "CREATE TABLE usage_month (account_id TEXT NOT NULL, month TEXT NOT NULL, kind TEXT NOT NULL, payer TEXT NOT NULL, quantity BIGINT NOT NULL DEFAULT 0, "
                            + "tokens BIGINT NOT NULL DEFAULT 0, bytes BIGINT NOT NULL DEFAULT 0, micros BIGINT NOT NULL DEFAULT 0, estimated BIGINT NOT NULL DEFAULT 0, PRIMARY KEY (account_id, month, kind, payer))",
                    "CREATE TABLE caps (scope TEXT NOT NULL, name TEXT NOT NULL, value BIGINT NOT NULL, PRIMARY KEY (scope, name))")),
            // 0.8.0, stage 3, step 2: the service's model servers' calls at once, in two lanes, one place for every node: a row a server
            // that a taker locks, the calls going now (each a lease, gone at its end or when it runs out), and the calls waiting their turn
            new Step(6, List.of(
                    "CREATE TABLE lanes (upstream TEXT PRIMARY KEY, at BIGINT NOT NULL)",
                    "CREATE TABLE lane_slots (id TEXT PRIMARY KEY, upstream TEXT NOT NULL, lane TEXT NOT NULL, account_id TEXT NOT NULL, until BIGINT NOT NULL)",
                    "CREATE INDEX lane_slots_upstream ON lane_slots (upstream)",
                    "CREATE TABLE lane_waits (id TEXT PRIMARY KEY, upstream TEXT NOT NULL, lane TEXT NOT NULL, account_id TEXT NOT NULL, since BIGINT NOT NULL)",
                    "CREATE INDEX lane_waits_upstream ON lane_waits (upstream)")),
            // 0.8.0, stage 3, step 4: a person's own keys, sealed with the service's key that is kept outside the store (OwnKeys); and
            // what was spent, kept in order until the spending plug-in has taken it (PluginSpending)
            new Step(7, List.of(
                    "CREATE TABLE own_keys (account_id TEXT NOT NULL, host TEXT NOT NULL, kind TEXT NOT NULL, sealed TEXT NOT NULL, kek TEXT NOT NULL, made_at BIGINT NOT NULL, PRIMARY KEY (account_id, host))",
                    "CREATE TABLE outbox (seq BIGINT PRIMARY KEY, id TEXT NOT NULL, body TEXT NOT NULL, made_at BIGINT NOT NULL, tries INTEGER NOT NULL DEFAULT 0, next_at BIGINT NOT NULL DEFAULT 0, last TEXT NOT NULL DEFAULT '')")),
            // 0.8.0, stage 4, step 1: the audit log, written to and never changed (but for what a removed account's entries forget, and
            // the oldest pruned); and the day a deleted account is removed on
            new Step(8, List.of(
                    "CREATE TABLE audit (id TEXT PRIMARY KEY, at BIGINT NOT NULL, actor TEXT NOT NULL, via TEXT NOT NULL, act TEXT NOT NULL, subject TEXT NOT NULL, library TEXT NOT NULL, address TEXT NOT NULL, detail TEXT NOT NULL)",
                    "CREATE INDEX audit_at ON audit (at)",
                    "CREATE INDEX audit_subject ON audit (subject, at)",
                    "ALTER TABLE accounts ADD COLUMN remove_at BIGINT NOT NULL DEFAULT 0")),
            // 0.8.0, stage 4, step 2: the rate limits: each rare act counted against its limit's key (failed sign-ins, sign-ups and join
            // links by address, changes to a password or an own key by account), pruned within a day; and the service's processes, each
            // writing itself down every few seconds, among which the frequent limits are shared
            new Step(9, List.of(
                    "CREATE TABLE limit_marks (limit_key TEXT NOT NULL, at BIGINT NOT NULL)",
                    "CREATE INDEX limit_marks_key ON limit_marks (limit_key, at)",
                    "CREATE TABLE service_processes (id TEXT PRIMARY KEY, seen_at BIGINT NOT NULL)")),
            // 0.8.0 review, round A: whether an account's mail address was verified (the operator set it, or an identity provider said it
            // was verified); a provider's sign-in finds an account by its mail only when it was. Earlier rows are not verified.
            new Step(10, List.of(
                    "ALTER TABLE accounts ADD COLUMN mail_verified INTEGER NOT NULL DEFAULT 0")),
            // 0.8.0 review, round A (the owner's word): letting someone into a library is an invitation, a link for the library at a level,
            // named by what the keeper typed, that matches no account and makes none: the invites table carries it, with no account
            new Step(11, List.of(
                    "ALTER TABLE invites ADD COLUMN library_id TEXT NOT NULL DEFAULT ''",
                    "ALTER TABLE invites ADD COLUMN level TEXT NOT NULL DEFAULT ''",
                    "ALTER TABLE invites ADD COLUMN label TEXT NOT NULL DEFAULT ''")),
            // 0.8.0 review, round B: the node whose disk holds each library's folder (ROB 3: routing and takeover go by it, not by a
            // path, which two machines may share), and the node whose disk holds what was set aside (ROB 4: each node removes its own)
            new Step(12, List.of(
                    "ALTER TABLE libraries ADD COLUMN home_node TEXT NOT NULL DEFAULT ''",
                    "ALTER TABLE asides ADD COLUMN node TEXT NOT NULL DEFAULT ''",
                    // BILL 10: the service's settings its nodes count and cap by, written by the service, read by each node
                    "CREATE TABLE service_settings (key TEXT PRIMARY KEY, value TEXT NOT NULL)",
                    // BILL 11: what each call that costs has put aside against its account's month and its use's ceiling while it runs
                    "CREATE TABLE reserved (id TEXT PRIMARY KEY, account_id TEXT NOT NULL, use_id TEXT NOT NULL, micros BIGINT NOT NULL, at BIGINT NOT NULL)",
                    "CREATE INDEX reserved_account ON reserved (account_id)")),
            // 0.8.0 review, round C: how each browser signed in and when it last proved it is its person (SEC 7: a key, a key of one's own and
            // a deletion ask again after five minutes), and the provider's identity token, for signing out there too (the Keycloak test)
            new Step(13, List.of(
                    "ALTER TABLE sessions ADD COLUMN via TEXT NOT NULL DEFAULT ''",
                    "ALTER TABLE sessions ADD COLUMN id_token TEXT NOT NULL DEFAULT ''",
                    "ALTER TABLE sessions ADD COLUMN proven_at BIGINT NOT NULL DEFAULT 0",
                    // BILL 12: a spent the plug-in refused again and again, set aside for the operator so what comes after it goes on
                    "ALTER TABLE outbox ADD COLUMN parked INTEGER NOT NULL DEFAULT 0",
                    // BILL 9: a hosted model called with no prices in its entry, counted at an estimate: written down for the operator's page
                    "CREATE TABLE unpriced (origin TEXT NOT NULL, model TEXT NOT NULL, first_at BIGINT NOT NULL, PRIMARY KEY (origin, model))")),
            // 0.8.0 review, round F: a statement a library was given is good once for a change it asks the service to make for its person (S8)
            new Step(14, List.of(
                    "CREATE TABLE spent_statements (hash TEXT PRIMARY KEY, until BIGINT NOT NULL)")));

    /** Applies every step not yet applied; how many it applied. */
    static int apply(Connection c) throws SQLException {
        boolean auto = c.getAutoCommit();
        c.setAutoCommit(true);
        try (Statement s = c.createStatement()) { s.execute("CREATE TABLE IF NOT EXISTS schema_steps (step INTEGER PRIMARY KEY, applied_at BIGINT NOT NULL)"); }
        int applied = 0;
        for (Step step : STEPS) {
            if (done(c, step.number())) continue;
            c.setAutoCommit(false);
            try {
                try (PreparedStatement p = c.prepareStatement("INSERT INTO schema_steps (step, applied_at) VALUES (?, ?)")) { p.setInt(1, step.number()); p.setLong(2, System.currentTimeMillis()); p.executeUpdate(); }
                for (String sql : step.sql()) try (Statement s = c.createStatement()) { s.execute(sql); }
                c.commit();
                applied++;
            } catch (SQLException e) {
                c.rollback();
                c.setAutoCommit(true);
                if (!done(c, step.number())) throw e;   // not another process's doing: the step itself failed
            } finally { c.setAutoCommit(true); }
        }
        c.setAutoCommit(auto);
        return applied;
    }

    private static boolean done(Connection c, int step) throws SQLException {
        try (PreparedStatement p = c.prepareStatement("SELECT 1 FROM schema_steps WHERE step = ?")) {
            p.setInt(1, step);
            try (ResultSet r = p.executeQuery()) { return r.next(); }
        }
    }
}
