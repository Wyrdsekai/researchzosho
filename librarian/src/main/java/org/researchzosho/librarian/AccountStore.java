package org.researchzosho.librarian;

import org.researchzosho.drive.Gated;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.concurrent.Callable;

/**
 * The account service's store (0.8.0): the accounts, how each signs in, the browsers signed in, the keys for programs, the libraries
 * and who may read or write each, the join links, and the key the service signs its statements with. On one machine it is a file
 * (SQLite, {@code accounts.db}); across machines the same tables in PostgreSQL, named by an address. One store, the same plain SQL
 * on both ({@link AccountSchema}). Secrets are kept as their hashes, as a library keeps its own: a session's secret, a key, a join code.
 */
public final class AccountStore implements AutoCloseable {

    public record Account(String id, String name, String login, String mail, String passwordHash, boolean admin, String state, long madeAt) {
        public boolean active() { return "active".equals(state); }
        public boolean hasPassword() { return passwordHash != null && !passwordHash.isEmpty(); }
    }
    public record Library(String id, String name, String title, String owner, String folder, String state, String address, long madeAt) {
        public boolean active() { return "active".equals(state); }
    }
    public record Member(String library, String account, String level, String addedBy, long addedAt) { }
    /** A browser signed in: its account, the library it is in, how it signed in ({@code via}: password, provider, join; the provider's identity token), and when it last proved it is its person. */
    public record Session(String account, String library, String label, long madeAt, long expiresAt, String via, String idToken, long provenAt) {
        public Session(String account, String library, String label, long madeAt, long expiresAt) { this(account, library, label, madeAt, expiresAt, "", "", madeAt); }
    }
    public record Key(String id, String prefix, String account, String library, String level, String label, long madeAt, String usedDay) {
        public boolean reads() { return "read".equals(level); }
    }
    /** A key as made: the record kept, and the secret shown once. */
    public record Minted(Key key, String secret) { }
    public record Signing(String kid, PrivateKey key, String publicJwk) { }

    private final Connection c;
    private final boolean sqlite;

    private AccountStore(Connection c, boolean sqlite) { this.c = c; this.sqlite = sqlite; }

    /**
     * Opens the store and brings its tables up to this version: a file's path (made when it is not there), or a PostgreSQL address
     * ({@code postgresql://user:password@host:5432/database}, or the same as JDBC writes it).
     */
    public static AccountStore open(String where) throws IOException {
        String w = where == null ? "" : where.strip();
        try {
            if (w.startsWith("postgresql://") || w.startsWith("postgres://") || w.startsWith("jdbc:postgresql:")) {
                Connection c = DriverManager.getConnection(jdbc(w));
                AccountSchema.apply(c);
                return new AccountStore(c, false);
            }
            Path file = Path.of(w).toAbsolutePath().normalize();
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            Connection c = openFile(file);
            try { Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------")); } catch (UnsupportedOperationException | IOException ignored) { }
            return new AccountStore(c, true);
        } catch (SQLException e) { throw new IOException("The account store at " + shown(w) + " could not be opened: " + e.getMessage(), e); }
    }

    /**
     * The file opened and its tables brought up to date. Another process doing the same at the same moment (the service starting while
     * a command runs) holds the file for an instant, and SQLite then refuses at once instead of waiting; so the opening is tried again
     * for up to twenty seconds. Writes begin by taking the file's write lock, and wait their turn for it.
     */
    private static Connection openFile(Path file) throws SQLException {
        Properties p = new Properties();
        p.setProperty("busy_timeout", "20000");
        p.setProperty("transaction_mode", "IMMEDIATE");
        long until = System.currentTimeMillis() + 20_000;
        while (true) {
            Connection c = null;
            try {
                c = DriverManager.getConnection("jdbc:sqlite:" + file, p);
                try (var s = c.createStatement()) { s.execute("PRAGMA journal_mode=WAL"); }
                AccountSchema.apply(c);
                return c;
            } catch (SQLException e) {
                if (c != null) try { c.close(); } catch (SQLException ignored) { }
                String m = e.getMessage() == null ? "" : e.getMessage();
                if (!(m.contains("SQLITE_BUSY") || m.contains("locked")) || System.currentTimeMillis() > until) throw e;
                try { Thread.sleep(25 + (long) (Math.random() * 50)); } catch (InterruptedException i) { Thread.currentThread().interrupt(); throw e; }
            }
        }
    }

    /** {@code postgresql://user:pw@host/db} as JDBC takes it; a JDBC address as it is. */
    static String jdbc(String w) {
        if (w.startsWith("jdbc:")) return w;
        String rest = w.substring(w.indexOf("://") + 3), user = "", pw = "";
        int at = rest.lastIndexOf('@');
        if (at >= 0) { String cred = rest.substring(0, at); rest = rest.substring(at + 1); int colon = cred.indexOf(':'); user = colon < 0 ? cred : cred.substring(0, colon); pw = colon < 0 ? "" : cred.substring(colon + 1); }
        return "jdbc:postgresql://" + rest + (user.isEmpty() ? "" : (rest.contains("?") ? "&" : "?") + "user=" + user + (pw.isEmpty() ? "" : "&password=" + pw));
    }

    /** The address without what signs in to it, for a sentence. */
    static String shown(String w) { return w.replaceAll("//[^@/]*@", "//").replaceAll("([?&]password=)[^&]*", "$1…"); }

    public boolean sqlite() { return sqlite; }

    @Override public synchronized void close() { try { c.close(); } catch (SQLException ignored) { } }

    // ---- small things ----

    static String id(String prefix) { byte[] b = new byte[9]; new SecureRandom().nextBytes(b); return prefix + HexFormat.of().formatHex(b); }
    static String secret(int bytes) { byte[] b = new byte[bytes]; new SecureRandom().nextBytes(b); return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }
    static long now() { return System.currentTimeMillis(); }

    private interface Reader<T> { T read(ResultSet r) throws SQLException; }

    private synchronized <T> List<T> query(String sql, Reader<T> reader, Object... args) throws IOException {
        try (PreparedStatement p = c.prepareStatement(sql)) {
            bind(p, args);
            List<T> out = new ArrayList<>();
            try (ResultSet r = p.executeQuery()) { while (r.next()) out.add(reader.read(r)); }
            return out;
        } catch (SQLException e) { throw new IOException("The account store could not be read: " + e.getMessage(), e); }
    }

    private <T> T one(String sql, Reader<T> reader, Object... args) throws IOException { List<T> l = query(sql, reader, args); return l.isEmpty() ? null : l.get(0); }

    private synchronized int update(String sql, Object... args) throws IOException {
        try (PreparedStatement p = c.prepareStatement(sql)) { bind(p, args); return p.executeUpdate(); }
        catch (SQLException e) { throw new IOException("The account store could not be written: " + e.getMessage(), e); }
    }

    private static void bind(PreparedStatement p, Object... args) throws SQLException {
        for (int i = 0; i < args.length; i++) {
            Object a = args[i];
            if (a instanceof Long l) p.setLong(i + 1, l); else if (a instanceof Integer n) p.setInt(i + 1, n); else if (a instanceof Boolean b) p.setInt(i + 1, b ? 1 : 0); else p.setString(i + 1, a == null ? "" : a.toString());
        }
    }

    // ---- accounts ----

    private static final String ACCOUNT = "SELECT id, name, login, mail, password_hash, admin, state, made_at FROM accounts";
    private static Account account(ResultSet r) throws SQLException { return new Account(r.getString(1), r.getString(2), r.getString(3), r.getString(4), r.getString(5), r.getInt(6) != 0, r.getString(7), r.getLong(8)); }

    /**
     * A new account by its name; the name a person signs in with is taken once, whatever its case and spacing. {@code mail} is an address
     * that was verified: the operator typed it, or an identity provider said it was verified. Nobody else's typing is ever an account's
     * mail, since a provider's sign-in finds an account by it.
     */
    public synchronized Account createAccount(String name, String mail, boolean admin) throws IOException {
        String n = name == null ? "" : name.strip().replaceAll("\\s+", " ");
        if (n.isEmpty()) throw new IOException("An account needs the person's name.");
        if (n.length() > 120) throw new IOException("Keep the name under 120 characters.");
        if (accountByLogin(n) != null) throw new IOException("Someone signs in here as \"" + n + "\" already; choose another name.");
        String m = mail == null ? "" : mail.strip().toLowerCase(Locale.ROOT);
        if (!m.isEmpty() && accountByMail(m) != null) throw new IOException("An account with the mail address " + m + " is here already.");
        String id = id("a");
        update("INSERT INTO accounts (id, name, login, mail, password_hash, admin, state, made_at, mail_verified) VALUES (?, ?, ?, ?, '', ?, 'active', ?, ?)", id, n, Accounts.login(n), m, admin, now(), m.isEmpty() ? 0 : 1);
        return account(id);
    }

    public Account account(String id) throws IOException { return id == null || id.isBlank() ? null : one(ACCOUNT + " WHERE id = ?", AccountStore::account, id); }
    public Account accountByLogin(String name) throws IOException { String l = Accounts.login(name); return l.isEmpty() ? null : one(ACCOUNT + " WHERE login = ? AND state <> 'removed'", AccountStore::account, l); }
    public Account accountByMail(String mail) throws IOException { String m = mail == null ? "" : mail.strip().toLowerCase(Locale.ROOT); return m.isEmpty() ? null : one(ACCOUNT + " WHERE mail = ? AND state <> 'removed'", AccountStore::account, m); }
    /** The account whose mail is this address and was verified (by the operator, or by an identity provider); null when none. */
    public Account accountByVerifiedMail(String mail) throws IOException { String m = mail == null ? "" : mail.strip().toLowerCase(Locale.ROOT); return m.isEmpty() ? null : one(ACCOUNT + " WHERE mail = ? AND mail_verified = 1 AND state <> 'removed'", AccountStore::account, m); }
    /** The account's own mail address said verified: an identity provider said so at a sign-in into it. */
    public void mailVerified(String account) throws IOException { update("UPDATE accounts SET mail_verified = 1 WHERE id = ?", account); }
    public List<Account> accounts() throws IOException { return query(ACCOUNT + " WHERE state <> 'removed' ORDER BY made_at, id", AccountStore::account); }

    public void setPassword(String account, String hash) throws IOException { update("UPDATE accounts SET password_hash = ? WHERE id = ?", hash, account); }

    /** The account closed: it signs in nowhere, its browsers are signed out, its keys and memberships are gone. Its own libraries stay on disk. */
    public synchronized void removeAccount(String account) throws IOException {
        update("UPDATE accounts SET state = 'removed', login = ?, mail = '' WHERE id = ?", "removed:" + account, account);
        update("DELETE FROM sessions WHERE account_id = ?", account);
        update("DELETE FROM api_keys WHERE account_id = ?", account);
        update("DELETE FROM members WHERE account_id = ?", account);
        update("DELETE FROM identities WHERE account_id = ?", account);
        update("DELETE FROM invites WHERE made_by = ? AND library_id <> '' AND used_at = 0", account);   // its open invitations to its libraries
        update("UPDATE libraries SET state = 'closed' WHERE owner_account_id = ?", account);
    }

    // ---- an account's states (0.8.0, stage 4): active, suspended, deleting (on its removal day), removed ----

    /** The account suspended (frozen, nothing deleted), or let back (active); false when it was neither active nor suspended. */
    public synchronized boolean setState(String account, String from, String to) throws IOException {
        return update("UPDATE accounts SET state = ?, remove_at = 0 WHERE id = ? AND state = ?", to, account, from) == 1;
    }

    /** The account to be removed on {@code at}: deleting, and frozen as a suspended one is until then. */
    public synchronized boolean deleting(String account, long at) throws IOException {
        return update("UPDATE accounts SET state = 'deleting', remove_at = ? WHERE id = ? AND state IN ('active', 'suspended', 'deleting')", at, account) == 1;
    }

    /** The day a deleting account is removed on; 0 for one that is not deleting. */
    public long removeAt(String account) throws IOException { Long v = one("SELECT remove_at FROM accounts WHERE id = ? AND state = 'deleting'", r -> r.getLong(1), account); return v == null ? 0 : v; }

    /** The deleting accounts whose day has come. */
    public List<Account> dueForRemoval(long now) throws IOException { return query(ACCOUNT + " WHERE state = 'deleting' AND remove_at <= ? ORDER BY remove_at, id", AccountStore::account, now); }

    /**
     * What a removed account leaves behind, and nothing more: its id, the day it was made, its uses, its usage rows and its month's
     * totals (the operator's bills and the plug-in's records refer to them; they hold no name and no address). Its name, sign-in name,
     * mail, password, sessions, keys, own keys, join links, sign-ins through a provider, sign-in tries, caps and places in other
     * libraries go; its audit entries keep their act, day and id, and lose their address and their detail. The libraries it kept are
     * removed by the caller before this.
     */
    public synchronized void forgetAccount(String account) throws IOException {
        Account a = account(account);
        if (a != null && !a.login().startsWith("removed:")) {   // its name's tries, from every address (SEC 11 counts them by name and address)
            String key = "svc:" + a.login();
            update("DELETE FROM signin_tries WHERE try_key = ? OR substr(try_key, 1, " + (key.length() + 1) + ") = ?", key, key + "|");
        }
        update("UPDATE accounts SET state = 'removed', name = ?, login = ?, mail = '', password_hash = '', admin = 0, remove_at = 0 WHERE id = ?", DELETED_NAME, "removed:" + account, account);
        update("DELETE FROM sessions WHERE account_id = ?", account);
        update("DELETE FROM api_keys WHERE account_id = ?", account);
        update("DELETE FROM members WHERE account_id = ?", account);
        update("DELETE FROM identities WHERE account_id = ?", account);
        update("DELETE FROM invites WHERE account_id = ?", account);
        update("DELETE FROM invites WHERE made_by = ? AND library_id <> ''", account);   // the invitations it made, with the names it gave them
        update("DELETE FROM own_keys WHERE account_id = ?", account);
        update("DELETE FROM caps WHERE scope = ?", account);
        update("DELETE FROM limit_marks WHERE limit_key = ? OR limit_key = ?", "signup|acct|" + account, "changes|acct|" + account);   // the counts that held its limits (stage 4, step 2)
        update("UPDATE audit SET address = '', detail = '' WHERE subject = ? OR actor = ?", account, account);
        // its libraries, removed with it, keep their ids for what was counted under them, and nothing of its name (BILL 7)
        // and the ones it let go, released or was moving: every library it owned that is not in service (round F, the second review)
        update("UPDATE libraries SET name = 'removed-' || id, title = '', folder = '' WHERE owner_account_id = ? AND state <> 'active'", account);
        for (Outgoing o : query("SELECT seq, id, body, made_at, tries, next_at, last FROM outbox", r -> new Outgoing(r.getLong(1), r.getString(2), r.getString(3), r.getLong(4), r.getInt(5), r.getLong(6), r.getString(7)))) {
            if (!o.body().contains("\"" + account + "\"")) continue;   // what waits for the plug-in keeps the id, not the name
            update("UPDATE outbox SET body = ? WHERE seq = ?", o.body().replaceAll("\"account_name\"\\s*:\\s*\"(?:[^\"\\\\]|\\\\.)*\"", "\"account_name\":\"" + DELETED_NAME + "\""), o.seq());
        }
    }

    /** What a removed account is called wherever it is still named. */
    public static final String DELETED_NAME = "a deleted account";

    /** A library handed to another keeper, one of its people: they keep it from now on, and are no longer one of its members. */
    public synchronized void handOver(String library, String to) throws IOException {
        update("UPDATE libraries SET owner_account_id = ? WHERE id = ?", to, library);
        update("DELETE FROM members WHERE library_id = ? AND account_id = ?", library, to);
    }

    // ---- the audit log (0.8.0, stage 4): one row an act, written once ----

    /** One act: when, who did it (an account's id, or "the command line", "the service"), how (session, key, cli, service), what, to whom, in which library, from where, and its detail. */
    public record Audit(String id, long at, String actor, String via, String act, String subject, String library, String address, String detail) { }

    /** An act written down. Nothing else writes to the log but {@link #forgetAccount} and the pruning of the oldest. */
    public void audit(String actor, String via, String act, String subject, String library, String address, String detail) throws IOException {
        update("INSERT INTO audit (id, at, actor, via, act, subject, library, address, detail) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", id("au"), now(),
                actor == null ? "" : actor, via == null ? "" : via, act, subject == null ? "" : subject, library == null ? "" : library, address == null ? "" : address, detail == null ? "" : detail);
    }

    /** The log since a moment, the newest first: all of it, or what an account did and what was done to it; at most {@code limit} rows. */
    public List<Audit> audits(String account, long since, int limit) throws IOException {
        String sql = "SELECT id, at, actor, via, act, subject, library, address, detail FROM audit WHERE at >= ?" + (account == null ? "" : " AND (subject = ? OR actor = ?)") + " ORDER BY at DESC, id DESC LIMIT " + Math.max(1, limit);
        return account == null ? query(sql, AccountStore::audit, since) : query(sql, AccountStore::audit, since, account, account);
    }

    /** The log filtered as the operator's page asks (stage 4): an account (subject or actor) or all, an act or all, from a moment until another, the newest first. */
    public List<Audit> audits(String account, String act, long since, long before, int limit) throws IOException {
        List<Object> args = new ArrayList<>(List.of(since, before));
        String sql = "SELECT id, at, actor, via, act, subject, library, address, detail FROM audit WHERE at >= ? AND at < ?";
        if (account != null && !account.isEmpty()) { sql += " AND (subject = ? OR actor = ?)"; args.add(account); args.add(account); }
        if (act != null && !act.isEmpty()) { sql += " AND act = ?"; args.add(act); }
        return query(sql + " ORDER BY at DESC, id DESC LIMIT " + Math.max(1, limit), AccountStore::audit, args.toArray());
    }

    /** The acts the log holds, each once. */
    public List<String> auditActs() throws IOException { return query("SELECT DISTINCT act FROM audit ORDER BY act", r -> r.getString(1)); }

    private static Audit audit(ResultSet r) throws SQLException { return new Audit(r.getString(1), r.getLong(2), r.getString(3), r.getString(4), r.getString(5), r.getString(6), r.getString(7), r.getString(8), r.getString(9)); }

    /** The entries older than a moment removed; how many. */
    public int auditPrune(long before) throws IOException { return update("DELETE FROM audit WHERE at < ?", before); }

    /**
     * An act written down under an id of the caller's, once: false when an entry with that id is there already (another process of the
     * service wrote it). A limit reached is written so, once a minute an account.
     */
    public boolean auditOnce(String id, String actor, String via, String act, String subject, String library, String address, String detail) throws IOException {
        if (one("SELECT 1 FROM audit WHERE id = ?", r -> r.getInt(1), id) != null) return false;
        try {
            update("INSERT INTO audit (id, at, actor, via, act, subject, library, address, detail) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", id, now(),
                    actor == null ? "" : actor, via == null ? "" : via, act, subject == null ? "" : subject, library == null ? "" : library, address == null ? "" : address, detail == null ? "" : detail);
            return true;
        } catch (IOException e) {
            if (one("SELECT 1 FROM audit WHERE id = ?", r -> r.getInt(1), id) != null) return false;   // the other process's row went in first
            throw e;
        }
    }

    // ---- the rate limits (0.8.0, stage 4): the rare acts counted here, and the service's processes that share the others ----

    /** A rare act counted against a limit's key (a limit and an address or an account), now. */
    public void mark(String key) throws IOException { update("INSERT INTO limit_marks (limit_key, at) VALUES (?, ?)", key, now()); }

    /** When each act counted against a key since a moment was, the oldest first. */
    public List<Long> marks(String key, long since) throws IOException {
        return query("SELECT at FROM limit_marks WHERE limit_key = ? AND at >= ? ORDER BY at", r -> r.getLong(1), key, since);
    }

    /** The counts older than a moment removed; how many. */
    public int marksPrune(long before) throws IOException { return update("DELETE FROM limit_marks WHERE at < ?", before); }

    /** A process of the service says it is there, now. */
    public synchronized void processSeen(String id, long at) throws IOException {
        if (update("UPDATE service_processes SET seen_at = ? WHERE id = ?", at, id) == 0) {
            try { update("INSERT INTO service_processes (id, seen_at) VALUES (?, ?)", id, at); }
            catch (IOException another) { update("UPDATE service_processes SET seen_at = ? WHERE id = ?", at, id); }
        }
    }

    /** How many processes of the service said they were there since a moment; those silent for a day are forgotten. */
    public int processesSince(long since) throws IOException {
        update("DELETE FROM service_processes WHERE seen_at < ?", since - 86_400_000L);
        Integer n = one("SELECT COUNT(*) FROM service_processes WHERE seen_at >= ?", r -> r.getInt(1), since);
        return n == null ? 0 : n;
    }

    /** A process of the service stopped: it shares nothing from now on. */
    public void processGone(String id) throws IOException { update("DELETE FROM service_processes WHERE id = ?", id); }

    // ---- how an account signs in through a provider ----

    /** The provider an account signs in through, when one is bound to it; null when none. */
    public String identityIssuer(String account) throws IOException { return one("SELECT issuer FROM identities WHERE account_id = ? ORDER BY bound_at LIMIT 1", r -> r.getString(1), account); }

    public String boundAccount(String issuer, String subject) throws IOException { return one("SELECT account_id FROM identities WHERE issuer = ? AND subject = ?", r -> r.getString(1), issuer, subject); }

    public synchronized void bind(String issuer, String subject, String account, String mail) throws IOException {
        if (boundAccount(issuer, subject) == null) update("INSERT INTO identities (issuer, subject, account_id, mail, bound_at) VALUES (?, ?, ?, ?, ?)", issuer, subject, account, mail, now());
    }

    // ---- the browsers signed in ----

    /** A new session: the secret the browser keeps in its cookie, shown once; the store keeps its hash. */
    public String newSession(String account, String library, String label, int days) throws IOException { return newSession(account, library, label, days, "", ""); }

    /** The same, saying how it signed in, with the provider's identity token when it came through one; a sign-in is proof it is its person, now. */
    public String newSession(String account, String library, String label, int days, String via, String idToken) throws IOException {
        String secret = secret(32);
        long now = now();
        update("INSERT INTO sessions (hash, account_id, library_id, label, made_at, expires_at, via, id_token, proven_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                Patrons.sha256(secret), account, library, label, now, now + days * 86_400_000L, via == null ? "" : via, idToken == null ? "" : idToken, now);
        return secret;
    }

    /** The session a cookie's secret is, while it stands; null otherwise. */
    public Session session(String secret) throws IOException {
        if (secret == null || secret.isBlank()) return null;
        Session s = one("SELECT account_id, library_id, label, made_at, expires_at, via, id_token, proven_at FROM sessions WHERE hash = ?",
                r -> new Session(r.getString(1), r.getString(2), r.getString(3), r.getLong(4), r.getLong(5), r.getString(6), r.getString(7), r.getLong(8)), Patrons.sha256(secret.strip()));
        return s == null || s.expiresAt() < now() ? null : s;
    }

    /** When this session last proved it is its person: its password typed again, or a new sign-in at its provider (SEC 7). */
    public void sessionProvenAt(String secret, long at) throws IOException { if (secret != null && !secret.isBlank()) update("UPDATE sessions SET proven_at = ? WHERE hash = ?", at, Patrons.sha256(secret.strip())); }
    public void sessionProven(String secret) throws IOException { sessionProvenAt(secret, now()); }

    /** The library this browser is in from now on. */
    public void sessionLibrary(String secret, String library) throws IOException { update("UPDATE sessions SET library_id = ? WHERE hash = ?", library, Patrons.sha256(secret.strip())); }
    public void endSession(String secret) throws IOException { if (secret != null && !secret.isBlank()) update("DELETE FROM sessions WHERE hash = ?", Patrons.sha256(secret.strip())); }
    public void endSessions(String account) throws IOException { update("DELETE FROM sessions WHERE account_id = ?", account); }
    /** Every session of the account but the one with this secret (null: every one). */
    public void endSessionsBut(String account, String secret) throws IOException {
        if (secret == null || secret.isBlank()) { endSessions(account); return; }
        update("DELETE FROM sessions WHERE account_id = ? AND hash <> ?", account, Patrons.sha256(secret.strip()));
    }
    public int sessions(String account) throws IOException { return one("SELECT COUNT(*) FROM sessions WHERE account_id = ? AND expires_at >= ?", r -> r.getInt(1), account, now()); }

    // ---- keys for programs ----

    private static final String KEY = "SELECT id, prefix, account_id, library_id, level, label, made_at, used_day FROM api_keys";
    private static Key key(ResultSet r) throws SQLException { return new Key(r.getString(1), r.getString(2), r.getString(3), r.getString(4), r.getString(5), r.getString(6), r.getLong(7), r.getString(8)); }

    /** A new key of this account for one library, to read it or to read and write: the secret shown once, its hash kept. */
    public synchronized Minted mintKey(String account, String library, String level, String label) throws IOException {
        if (!"read".equals(level) && !"write".equals(level)) throw new IOException("A key reads or writes; say which.");
        String l = label == null ? "" : label.strip().replaceAll("\\s+", " ");
        if (l.isEmpty()) l = "a program";
        if (l.length() > 80) throw new IOException("Keep the label under 80 characters.");
        if (keys(account).size() >= 50) throw new IOException("You have 50 keys already. Revoke one you no longer use first.");
        String secret = ApiKeys.PREFIX + secret(32), id = "K-" + id("").substring(0, 8);
        update("INSERT INTO api_keys (id, hash, prefix, account_id, library_id, level, label, made_at, used_day) VALUES (?, ?, ?, ?, ?, ?, ?, ?, '')", id, Patrons.sha256(secret), secret.substring(0, ApiKeys.PREFIX.length() + 8), account, library, level, l, now());
        return new Minted(one(KEY + " WHERE id = ?", AccountStore::key, id), secret);
    }

    /** The key a bearer token is, or null; the day it was last used is written once a day. */
    public Key resolveKey(String secret) throws IOException {
        if (secret == null || !secret.startsWith(ApiKeys.PREFIX)) return null;
        Key k = one(KEY + " WHERE hash = ?", AccountStore::key, Patrons.sha256(secret.strip()));
        String today = LocalDate.now().toString();
        if (k != null && !today.equals(k.usedDay())) update("UPDATE api_keys SET used_day = ? WHERE id = ?", today, k.id());
        return k;
    }

    public List<Key> keys(String account) throws IOException { return query(KEY + " WHERE account_id = ? ORDER BY made_at DESC, id", AccountStore::key, account); }

    /** Revokes a key of this account by its id or its prefix; false when the account has no such key. */
    /**
     * Whether a statement (by its hash) is used here for the first time; it is written down until {@code until}, after which it has run out
     * anyway. A change a library asks the service to make for its person is made once for each statement the person's request brought
     * (round F, the second review: a library could reuse a visitor's statement for 70 seconds).
     */
    public boolean once(String hash, long until) throws IOException {
        try {
            return inTransaction(() -> {
                update("DELETE FROM spent_statements WHERE until < ?", now());
                if (one("SELECT 1 FROM spent_statements WHERE hash = ?", r -> r.getInt(1), hash) != null) return false;
                update("INSERT INTO spent_statements (hash, until) VALUES (?, ?)", hash, until);
                return true;
            });
        } catch (IOException another) { return false; }   // another process wrote it a moment before: used
    }

    /**
     * One more use started under a statement (by its hash), while fewer than {@code most} were: written down until {@code until}. A library
     * starts each use with its request's statement, and one request starts at most so many (round F: a library could reuse a visitor's
     * statement to open uses charged to them).
     */
    public boolean oneMoreUse(String hash, int most, long until) throws IOException {
        try {
            return inTransaction(() -> {
                update("DELETE FROM spent_statements WHERE until < ?", now());
                Long n = one("SELECT COUNT(*) FROM spent_statements WHERE hash LIKE ?", r -> r.getLong(1), "uses:" + hash + ":%");
                long had = n == null ? 0 : n;
                if (had >= most) return false;
                update("INSERT INTO spent_statements (hash, until) VALUES (?, ?)", "uses:" + hash + ":" + (had + 1), until);
                return true;
            });
        } catch (IOException another) { return false; }
    }

    public boolean revokeKey(String account, String idOrPrefix) throws IOException {
        String want = idOrPrefix == null ? "" : idOrPrefix.strip();
        for (Key k : keys(account)) if (k.id().equals(want) || k.prefix().equals(want) || (want.startsWith(ApiKeys.PREFIX) && want.startsWith(k.prefix()))) return update("DELETE FROM api_keys WHERE id = ?", k.id()) > 0;
        return false;
    }

    // ---- the libraries, and who may read or write each ----

    private static final String LIBRARY = "SELECT id, name, title, owner_account_id, folder, state, address, made_at FROM libraries";
    private static Library library(ResultSet r) throws SQLException { return new Library(r.getString(1), r.getString(2), r.getString(3), r.getString(4), r.getString(5), r.getString(6), r.getString(7), r.getLong(8)); }

    /** A library written down: its own id, the short name it is filed under, its title, the account that keeps it, and its folder. */
    public void addLibrary(String id, String name, String title, String owner, String folder) throws IOException {
        if (!LibraryPlaces.idOk(id)) throw new IOException(LibraryPlaces.notAnId(id));   // paths are made from it: its copy, its sockets
        update("INSERT INTO libraries (id, name, title, owner_account_id, folder, state, address, made_at) VALUES (?, ?, ?, ?, ?, 'active', '', ?)", id, name, title, owner, folder, now());
    }

    public Library library(String id) throws IOException { return id == null || id.isBlank() ? null : one(LIBRARY + " WHERE id = ?", AccountStore::library, id); }
    public Library libraryByName(String name) throws IOException { return name == null || name.isBlank() ? null : one(LIBRARY + " WHERE name = ?", AccountStore::library, name.strip().toLowerCase(Locale.ROOT)); }
    public List<Library> libraries() throws IOException { return query(LIBRARY + " ORDER BY made_at, id", AccountStore::library); }
    /** Every library the account owns, in any state: active, let go, released, moving, removed. */
    public List<Library> librariesOwnedBy(String account) throws IOException { return query(LIBRARY + " WHERE owner_account_id = ? ORDER BY made_at, id", AccountStore::library, account); }
    public List<Library> librariesKeptBy(String account) throws IOException { return query(LIBRARY + " WHERE owner_account_id = ? AND state = 'active' ORDER BY made_at, id", AccountStore::library, account); }
    public void libraryAddress(String id, String address) throws IOException { update("UPDATE libraries SET address = ? WHERE id = ?", address, id); }

    // ---- placement (0.8.0): who holds a library, and until when ----

    /**
     * Where a library is: the node that holds it, the count of its starts (its epoch), until when the node holds it, the address it
     * answers at, when work of its own is next due (0: nothing), and the day of its last night. It is awake while {@code leaseUntil}
     * is ahead of the store's clock.
     */
    public record Placement(String id, String node, long epoch, long leaseUntil, String address, long dueAt, String nightDay, long copiedAt) { }

    private static final String PLACEMENT = "SELECT id, node, epoch, lease_until, address, due_at, night_day, copied_at FROM libraries";
    private static Placement placement(ResultSet r) throws SQLException { return new Placement(r.getString(1), r.getString(2), r.getLong(3), r.getLong(4), r.getString(5), r.getLong(6), r.getString(7), r.getLong(8)); }

    public Placement placement(String id) throws IOException { return one(PLACEMENT + " WHERE id = ?", AccountStore::placement, id); }
    /** Every library that may run, in the order they were made. */
    public List<Placement> placements() throws IOException { return query(PLACEMENT + " WHERE state = 'active' ORDER BY made_at, id", AccountStore::placement); }

    /**
     * The store's own clock, in milliseconds: the one clock leases are read by, so machines whose clocks differ agree on who holds
     * what. A file on this machine has this machine's clock; a database server has its own.
     */
    public long clock() throws IOException {
        if (sqlite) return System.currentTimeMillis();
        Long t = one("SELECT CAST(EXTRACT(EPOCH FROM clock_timestamp()) * 1000 AS BIGINT)", r -> r.getLong(1));
        return t == null ? System.currentTimeMillis() : t;
    }

    /**
     * Takes a library's lease for a node: given when nobody holds it (the last lease ran out or was given back), or when this same
     * node holds it (it starts the library again). Each take is the library's next epoch, which is returned; -1 when another node
     * holds it. One statement decides: of two nodes taking together, one changes the row.
     */
    public synchronized long take(String id, String node, long leaseMs) throws IOException {
        long now = clock();
        int n = update("UPDATE libraries SET node = ?, epoch = epoch + 1, lease_until = ?, address = '' WHERE id = ? AND state = 'active' AND (lease_until < ? OR node = ?)", node, now + leaseMs, id, now, node);
        if (n != 1) return -1;
        Long e = one("SELECT epoch FROM libraries WHERE id = ? AND node = ?", r -> r.getLong(1), id, node);
        return e == null ? -1 : e;
    }

    /** The lease held on: true while the row still names this node and this epoch. False means it is somebody else's now, or the library was removed. */
    public boolean renew(String id, String node, long epoch, long leaseMs) throws IOException {
        return update("UPDATE libraries SET lease_until = ? WHERE id = ? AND node = ? AND epoch = ? AND state = 'active'", clock() + leaseMs, id, node, epoch) == 1;
    }

    /** The lease given back: the library is asleep, and whoever asks for it next takes it. */
    public void release(String id, String node, long epoch) throws IOException {
        update("UPDATE libraries SET lease_until = 0, address = '' WHERE id = ? AND node = ? AND epoch = ?", id, node, epoch);
    }

    // ---- places (0.8.0): where a library's folder is, its copy, what is set aside ----

    /** A library's state: {@code active} (served), {@code moving} (out of service while its folder is moved or brought back), {@code removed}, {@code released}. */
    public void libraryState(String id, String state) throws IOException {
        update("UPDATE libraries SET state = ?, removed_at = ? WHERE id = ?", state, state.equals("removed") || state.equals("released") ? now() : 0L, id);
    }

    /** The folder a library is kept in, after a move. */
    public void libraryFolder(String id, String folder) throws IOException { update("UPDATE libraries SET folder = ? WHERE id = ?", folder, id); }

    /** The node whose disk holds the library's folder, as written down when it was made, brought or taken in there; "" when not known (a row from before). */
    public String libraryHome(String id) throws IOException { String v = one("SELECT home_node FROM libraries WHERE id = ?", r -> r.getString(1), id); return v == null ? "" : v; }
    public void libraryHome(String id, String node) throws IOException { update("UPDATE libraries SET home_node = ? WHERE id = ?", node == null ? "" : node, id); }

    /** The library's copy is whole as of {@code at}, made from its start {@code epoch}. */
    public void copied(String id, long at, long epoch) throws IOException { update("UPDATE libraries SET copied_at = ?, copied_epoch = ? WHERE id = ?", at, epoch, id); }

    /** A folder or a copy set aside, to be removed when {@code until} has passed. */
    public record Aside(String path, String library, String why, long until) { }

    public void aside(String path, String library, String why, long until) throws IOException {
        if (update("UPDATE asides SET library_id = ?, why = ?, until = ? WHERE path = ?", library, why, until, path) == 0)
            update("INSERT INTO asides (path, library_id, why, until) VALUES (?, ?, ?, ?)", path, library, why, until);
    }

    /** What was set aside and is due to go by {@code at}. */
    /** What was set aside on the node {@code node}'s disk ("" for a machine that runs none) and whose grace is over. */
    public List<Aside> asidesDue(long at, String node) throws IOException { return query("SELECT path, library_id, why, until FROM asides WHERE until <= ? AND node = ? ORDER BY until, path", r -> new Aside(r.getString(1), r.getString(2), r.getString(3), r.getLong(4)), at, node == null ? "" : node); }

    /** A folder set aside, its grace ended now: the machine whose disk holds it removes it at its next sweep. */
    public void asideDueNow(String path) throws IOException { update("UPDATE asides SET until = ? WHERE path = ?", now(), path); }

    public List<Aside> asidesDue(long at) throws IOException { return query("SELECT path, library_id, why, until FROM asides WHERE until <= ? ORDER BY until, path", r -> new Aside(r.getString(1), r.getString(2), r.getString(3), r.getLong(4)), at); }

    public List<Aside> asides(String library) throws IOException { return query("SELECT path, library_id, why, until FROM asides WHERE library_id = ? ORDER BY until, path", r -> new Aside(r.getString(1), r.getString(2), r.getString(3), r.getLong(4)), library); }

    public void asideGone(String path) throws IOException { update("DELETE FROM asides WHERE path = ?", path); }

    /** The same, written down for the node whose disk holds it ("" for this machine's when it runs no node). */
    public void aside(String path, String library, String why, long until, String node) throws IOException {
        aside(path, library, why, until);
        update("UPDATE asides SET node = ? WHERE path = ?", node == null ? "" : node, path);
    }

    // ---- the service's settings for its nodes (0.8.0 review, BILL 10) ----

    /** The service's settings its nodes count and cap by, written whole: what is not in {@code values} goes. */
    public synchronized void serviceSettings(Map<String, String> values) throws IOException {
        inTransaction(() -> {
            update("DELETE FROM service_settings");
            for (Map.Entry<String, String> e : values.entrySet()) update("INSERT INTO service_settings (key, value) VALUES (?, ?)", e.getKey(), e.getValue());
            return true;
        });
    }

    public Map<String, String> serviceSettings() throws IOException {
        Map<String, String> m = new TreeMap<>();
        for (String[] kv : query("SELECT key, value FROM service_settings ORDER BY key", r -> new String[]{r.getString(1), r.getString(2)})) m.put(kv[0], kv[1]);
        return m;
    }

    /** What was set aside at this path; null when nothing is written down there. */
    public Aside asideAt(String path) throws IOException { return one("SELECT path, library_id, why, until FROM asides WHERE path = ?", r -> new Aside(r.getString(1), r.getString(2), r.getString(3), r.getLong(4)), path); }

    /** The node a folder set aside is on; "" when none is written down. */
    public String asideNode(String path) throws IOException { String v = one("SELECT node FROM asides WHERE path = ?", r -> r.getString(1), path); return v == null ? "" : v; }

    /** When work of the library's own is next due (0: nothing): a sleeping library is started then. */
    public void due(String id, long at) throws IOException { update("UPDATE libraries SET due_at = ? WHERE id = ? AND due_at <> ?", at, id, at); }

    /** The day (yyyy-mm-dd, the service's) the library's night was last started. */
    public void nightStarted(String id, String day) throws IOException { update("UPDATE libraries SET night_day = ? WHERE id = ?", day, id); }

    /** A node says it is there: its address, the folder its libraries are under, and its version, at the store's clock. */
    public synchronized void nodeSeen(String id, String address, String root, String version) throws IOException {
        long now = clock();
        if (update("UPDATE nodes SET address = ?, root = ?, version = ?, seen_at = ? WHERE id = ?", address, root, version, now, id) == 0) {
            try { update("INSERT INTO nodes (id, address, root, version, seen_at) VALUES (?, ?, ?, ?, ?)", id, address, root, version, now); }
            catch (IOException another) { update("UPDATE nodes SET address = ?, root = ?, version = ?, seen_at = ? WHERE id = ?", address, root, version, now, id); }
        }
    }

    // ---- what every process of the service reads: sign-ins begun, failed tries ----

    /** A sign-in through the provider begun: what its end needs, kept until it ends or ten minutes pass. */
    public void pendingPut(String state, String value) throws IOException {
        update("DELETE FROM pending_signins WHERE made_at < ?", now() - 10 * 60_000L);
        update("INSERT INTO pending_signins (state, value, made_at) VALUES (?, ?, ?)", state, value, now());
    }

    /** The sign-in begun with {@code state}, taken: by one process only, once. Null when there is none (or another took it). */
    public synchronized String pendingTake(String state) throws IOException {
        String v = one("SELECT value FROM pending_signins WHERE state = ? AND made_at >= ?", r -> r.getString(1), state, now() - 10 * 60_000L);
        if (v == null) return null;
        return update("DELETE FROM pending_signins WHERE state = ?", state) == 1 ? v : null;
    }

    public void tried(String key) throws IOException { update("INSERT INTO signin_tries (try_key, at) VALUES (?, ?)", key, now()); }
    public int tries(String key, long since) throws IOException {
        update("DELETE FROM signin_tries WHERE at < ?", since);
        Integer n = one("SELECT COUNT(*) FROM signin_tries WHERE try_key = ? AND at >= ?", r -> r.getInt(1), key, since);
        return n == null ? 0 : n;
    }
    public void triesCleared(String key) throws IOException { update("DELETE FROM signin_tries WHERE try_key = ?", key); }

    /** Who holds a duty now, and until when by the store's clock; null when nobody ever took it. */
    public record Duty(String name, String holder, long until) { }
    public Duty duty(String name) throws IOException { return one("SELECT name, holder, until FROM duties WHERE name = ?", r -> new Duty(r.getString(1), r.getString(2), r.getLong(3)), name); }

    /** A node as it last wrote itself down: where its own calls are answered, the folder its libraries are under, its version, when. */
    public record NodeRow(String id, String address, String root, String version, long seenAt) { }

    public List<NodeRow> nodes() throws IOException { return query("SELECT id, address, root, version, seen_at FROM nodes ORDER BY id", r -> new NodeRow(r.getString(1), r.getString(2), r.getString(3), r.getString(4), r.getLong(5))); }

    /**
     * A duty one process of several holds at a time (the scheduler): true when this holder has it for the next {@code ms}, because it
     * had it already, nobody had it, or the last holder's time ran out.
     */
    public synchronized boolean duty(String name, String holder, long ms) throws IOException {
        long now = clock();
        if (update("UPDATE duties SET holder = ?, until = ? WHERE name = ? AND (holder = ? OR until < ?)", holder, now + ms, name, holder, now) == 1) return true;
        if (one("SELECT holder FROM duties WHERE name = ?", r -> r.getString(1), name) != null) return false;
        try { return update("INSERT INTO duties (name, holder, until) VALUES (?, ?, ?)", name, holder, now + ms) == 1; }
        catch (IOException another) { return false; }   // two took it together and the other one's row is in
    }

    /** The libraries an account can open: the ones it keeps, then the ones it is a member of. */
    public List<Library> librariesOf(String account) throws IOException {
        List<Library> out = new ArrayList<>(librariesKeptBy(account));
        out.addAll(query(LIBRARY + " WHERE state = 'active' AND id IN (SELECT library_id FROM members WHERE account_id = ?) ORDER BY made_at, id", AccountStore::library, account));
        return out;
    }

    /** What an account may do in a library: keeper, write, read, or null when it may not open it. */
    public String level(Library lib, String account) throws IOException {
        if (lib == null || !lib.active() || account == null) return null;
        if (lib.owner().equals(account)) return Statement.KEEPER;
        return one("SELECT level FROM members WHERE library_id = ? AND account_id = ?", r -> r.getString(1), lib.id(), account);
    }

    public List<Member> members(String library) throws IOException {
        return query("SELECT library_id, account_id, level, added_by, added_at FROM members WHERE library_id = ? ORDER BY added_at, account_id", r -> new Member(r.getString(1), r.getString(2), r.getString(3), r.getString(4), r.getLong(5)), library);
    }

    /** A member put in, or their level changed: read or write. */
    /** A member's level changed; false when they are not a member, who is let in only by taking an invitation (round F, S10). */
    public synchronized boolean changeMemberLevel(String library, String account, String level) throws IOException {
        if (!"read".equals(level) && !"write".equals(level)) throw new IOException("A member reads, or reads and writes; say which.");
        return update("UPDATE members SET level = ? WHERE library_id = ? AND account_id = ?", level, library, account) > 0;
    }

    public synchronized void setMember(String library, String account, String level, String by) throws IOException {
        if (!"read".equals(level) && !"write".equals(level)) throw new IOException("A member reads, or reads and writes; say which.");
        if (update("UPDATE members SET level = ? WHERE library_id = ? AND account_id = ?", level, library, account) == 0)
            update("INSERT INTO members (library_id, account_id, level, added_by, added_at) VALUES (?, ?, ?, ?, ?)", library, account, level, by, now());
    }

    /** A member taken out, with the keys they had for this library; false when they were not a member. */
    public synchronized boolean removeMember(String library, String account) throws IOException {
        update("DELETE FROM api_keys WHERE library_id = ? AND account_id = ?", library, account);
        update("UPDATE sessions SET library_id = '' WHERE library_id = ? AND account_id = ?", library, account);
        return update("DELETE FROM members WHERE library_id = ? AND account_id = ?", library, account) > 0;
    }

    // ---- join links ----

    /** A join link's code for an account, good once and for {@code days}: shown once, its hash kept. An earlier unused link of the account is withdrawn. */
    public synchronized String newInvite(String account, String by, int days) throws IOException {
        update("DELETE FROM invites WHERE account_id = ? AND used_at = 0", account);
        String code = secret(24);
        update("INSERT INTO invites (code_hash, account_id, made_by, made_at, expires_at, used_at) VALUES (?, ?, ?, ?, ?, 0)", Patrons.sha256(code), account, by, now(), now() + days * 86_400_000L);
        return code;
    }

    /** The account a join code is for, while the link stands and was not used; null otherwise. {@code use}: the link is used up. */
    public synchronized String invited(String code, boolean use) throws IOException {
        if (code == null || code.isBlank()) return null;
        String h = Patrons.sha256(code.strip());
        String account = one("SELECT account_id FROM invites WHERE code_hash = ? AND library_id = '' AND used_at = 0 AND expires_at >= ?", r -> r.getString(1), h, now());   // an invitation to a library is no account's
        if (account != null && use) update("UPDATE invites SET used_at = ? WHERE code_hash = ?", now(), h);
        return account;
    }

    // ---- invitations to a library (0.8.0 review, round A) ----

    /**
     * An invitation into a library: its id (the hash of its code, which never shows the code), the library, what the person may do there,
     * the name the keeper gave it, who made it, and when it was made and runs out. It names no account: whoever opens it is let in.
     */
    public record Invitation(String id, String library, String level, String label, String by, long madeAt, long expiresAt) { }

    private static final String INVITATION = "SELECT code_hash, library_id, level, label, made_by, made_at, expires_at FROM invites";
    private static Invitation invitation(ResultSet r) throws SQLException { return new Invitation(r.getString(1), r.getString(2), r.getString(3), r.getString(4), r.getString(5), r.getLong(6), r.getLong(7)); }

    /** An invitation into a library, good once and for {@code days}: its code, shown once; its hash kept. */
    public synchronized String newInvitation(String library, String level, String label, String by, int days) throws IOException {
        String code = secret(24);
        update("INSERT INTO invites (code_hash, account_id, made_by, made_at, expires_at, used_at, library_id, level, label) VALUES (?, '', ?, ?, ?, 0, ?, ?, ?)",
                Patrons.sha256(code), by, now(), now() + days * 86_400_000L, library, level, label);
        return code;
    }

    /** The invitation a code is, while it stands (not used, not run out, not withdrawn); null otherwise, and for an account's join link. */
    public Invitation invitation(String code) throws IOException {
        if (code == null || code.isBlank()) return null;
        return one(INVITATION + " WHERE code_hash = ? AND library_id <> '' AND used_at = 0 AND expires_at >= ?", AccountStore::invitation, Patrons.sha256(code.strip()), now());
    }

    /** The invitation used up, when it still stood: true once, for one taker, however many ask at the same moment. */
    public synchronized boolean takeInvitation(String code) throws IOException {
        if (code == null || code.isBlank()) return false;
        return update("UPDATE invites SET used_at = ? WHERE code_hash = ? AND library_id <> '' AND used_at = 0 AND expires_at >= ?", now(), Patrons.sha256(code.strip()), now()) == 1;
    }

    /** An invitation given back after its taker could not be let in (the account could not be made): it stands again. */
    public void untakeInvitation(String code) throws IOException { update("UPDATE invites SET used_at = 0 WHERE code_hash = ? AND library_id <> ''", Patrons.sha256(code.strip())); }

    /** A library's invitations that stand, the oldest first. */
    public List<Invitation> invitations(String library) throws IOException {
        return query(INVITATION + " WHERE library_id = ? AND used_at = 0 AND expires_at >= ? ORDER BY made_at, code_hash", AccountStore::invitation, library, now());
    }

    /** An invitation of a library withdrawn: it opens nothing from now on. False when there was none that stood. */
    public boolean withdrawInvitation(String library, String id) throws IOException {
        return update("DELETE FROM invites WHERE code_hash = ? AND library_id = ? AND used_at = 0", id, library) == 1;
    }

    // ---- the key the service signs with ----

    /** The signing key in use: the newest not retired; made the first time it is asked for. */
    public synchronized Signing signing() throws IOException {
        Signing s = one("SELECT kid, private_key, public_jwk FROM signing_keys WHERE retired_at = 0 ORDER BY made_at DESC, kid", r -> { try { return new Signing(r.getString(1), Statement.privateKey(r.getString(2)), r.getString(3)); } catch (IOException e) { throw new SQLException(e.getMessage()); } });
        if (s != null) return s;
        KeyPair pair = Statement.newKey();
        String kid = id("k");
        update("INSERT INTO signing_keys (kid, private_key, public_jwk, made_at, retired_at) VALUES (?, ?, ?, ?, 0)", kid, Statement.privateText(pair.getPrivate()), Statement.jwk(pair.getPublic(), kid).toString(), now());
        return signing();
    }

    /** Every published key, as the lines of a key file: the one in use and the retired ones a statement in flight may still name. */
    public List<String> publicJwks() throws IOException { return query("SELECT public_jwk FROM signing_keys ORDER BY made_at DESC, kid", r -> r.getString(1)); }

    // ---- use and caps (0.8.0, stage 3) ----

    /**
     * A use an account asked for (a research run, a chat turn, a translation, a night, a page added by its address): what it is, who
     * pays for it ({@code operator}, or {@code own} when the person's own key does the work), its estimate and its ceiling, and once
     * it is closed what it came to. Dollars are whole millionths.
     */
    public record Use(String id, String account, String library, String kind, String entry, String payer, String job, long estimateMicros, long estimateMinutes,
                      long ceilingMicros, long ceilingMinutes, String state, String said, long openedAt, long closedAt, long actualMicros) {
        public boolean open() { return "open".equals(state); }
    }

    private static final String USE = "SELECT id, account_id, library_id, kind, entry, payer, job, estimate_micros, estimate_minutes, ceiling_micros, ceiling_minutes, state, said, opened_at, closed_at, actual_micros FROM uses";
    private static Use use(ResultSet r) throws SQLException {
        return new Use(r.getString(1), r.getString(2), r.getString(3), r.getString(4), r.getString(5), r.getString(6), r.getString(7), r.getLong(8), r.getLong(9), r.getLong(10), r.getLong(11),
                r.getString(12), r.getString(13), r.getLong(14), r.getLong(15), r.getLong(16));
    }

    /** A use written down as it was answered: {@code open} (it may spend), or {@code refused} with the sentence the person was told. */
    public void addUse(Use u) throws IOException {
        update("INSERT INTO uses (id, account_id, library_id, kind, entry, payer, job, estimate_micros, estimate_minutes, ceiling_micros, ceiling_minutes, state, said, opened_at, closed_at, actual_micros) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 0)", u.id(), u.account(), u.library(), u.kind(), u.entry(), u.payer(), u.job(), u.estimateMicros(), u.estimateMinutes(),
                u.ceilingMicros(), u.ceilingMinutes(), u.state(), u.said(), u.openedAt());
    }

    public Use use(String id) throws IOException { return id == null || id.isBlank() ? null : one(USE + " WHERE id = ?", AccountStore::use, id); }

    /** An account's uses still open: the work it has going now. */
    public List<Use> openUses(String account) throws IOException { return query(USE + " WHERE account_id = ? AND state = 'open' ORDER BY opened_at, id", AccountStore::use, account); }

    /** An account's uses since a moment, the newest first. */
    public List<Use> uses(String account, long since) throws IOException { return query(USE + " WHERE account_id = ? AND opened_at >= ? ORDER BY opened_at DESC, id", AccountStore::use, account, since); }

    /** What was spent under a use, in millionths of a dollar. */
    public long useSpent(String id) throws IOException { Long v = one("SELECT COALESCE(SUM(micros), 0) FROM usage WHERE use_id = ?", r -> r.getLong(1), id); return v == null ? 0 : v; }

    /** A use closed as {@code state} (done, stopped, failed), with what it came to; false when it was not open. */
    public boolean closeUse(String id, String state) throws IOException { return closeUse(id, state, null); }

    /** What is done in the same transaction as a change to a use, with the use as it is after the change; its failure undoes the change. */
    public interface WithUse { void run(Use u) throws IOException; }

    /**
     * A use closed as {@code state}, and {@code told} (what it came to written to the spending plug-in's outbox) in the same transaction:
     * both happen, or neither, and the use stays open for its end to be taken again. False when it was not open.
     */
    public synchronized boolean closeUse(String id, String state, WithUse told) throws IOException {
        return inTransaction(() -> {
            if (update("UPDATE uses SET state = ?, closed_at = ?, actual_micros = (SELECT COALESCE(SUM(micros), 0) FROM usage WHERE use_id = ?) WHERE id = ? AND state = 'open'", state, now(), id, id) != 1) return false;
            if (told != null) told.run(use(id));
            return true;
        });
    }

    /**
     * The uses nobody will end: open, and either not a job's and opened before {@code requestBefore} (a chat turn, a page's call, a page
     * added: minutes of work, whose library was stopped in the middle), or a job's, opened before {@code jobBefore}, with nothing counted
     * under it since {@code quietSince} (a job whose record is gone). A job whose library stopped goes on from its record under the same use.
     */
    public List<Use> staleUses(long requestBefore, long jobBefore, long quietSince) throws IOException {
        return query(USE + " WHERE state = 'open' AND ((job = '' AND opened_at < ?) OR (job <> '' AND opened_at < ? AND NOT EXISTS (SELECT 1 FROM usage WHERE usage.use_id = uses.id AND usage.at >= ?))) ORDER BY opened_at, id",
                AccountStore::use, requestBefore, jobBefore, quietSince);
    }

    /** The uses closed since {@code since} under which more was counted after they closed (a call that began before): what they came to has changed. */
    public List<Use> lateUses(long since) throws IOException {
        return query(USE + " WHERE state IN ('done', 'stopped', 'failed') AND closed_at >= ? AND actual_micros <> (SELECT COALESCE(SUM(micros), 0) FROM usage WHERE usage.use_id = uses.id) ORDER BY closed_at, id",
                AccountStore::use, since);
    }

    /** A closed use's whole written again from its rows, when it is still {@code was}, and {@code told} in the same transaction; false when another did it. */
    public synchronized boolean useCameTo(String id, long was, WithUse told) throws IOException {
        return inTransaction(() -> {
            if (update("UPDATE uses SET actual_micros = (SELECT COALESCE(SUM(micros), 0) FROM usage WHERE use_id = ?) WHERE id = ? AND state <> 'open' AND actual_micros = ?", id, id, was) != 1) return false;
            if (told != null) told.run(use(id));
            return true;
        });
    }

    private interface Work<T> { T run() throws IOException; }

    /** Work in one transaction, tried again (five times at most) when the store refused it because another process wrote at the same time. */
    private synchronized <T> T inTransaction(Work<T> work) throws IOException {
        for (int attempt = 0; ; attempt++) {
            try {
                c.setAutoCommit(false);
                T t = work.run();
                c.commit();
                return t;
            } catch (IOException | SQLException | RuntimeException e) {
                try { c.rollback(); } catch (SQLException ignored) { }
                boolean store = e instanceof SQLException || e.getCause() instanceof SQLException;
                if (!store || attempt >= 4) {
                    if (e instanceof IOException io) throw io;
                    if (e instanceof RuntimeException r) throw r;
                    throw new IOException("The account store could not be written: " + e.getMessage(), e);
                }
            } finally { try { c.setAutoCommit(true); } catch (SQLException ignored) { } }
        }
    }

    /**
     * One kind of cost: a model's calls (tokens; dollars at the entry's prices then), searches, fetches, minutes of speech, a helper's
     * seconds, storage. {@code quantity} is the count of the kind (calls, searches, requests, seconds, megabyte-days); {@code estimated}
     * when it was worked out from sizes because the reply said nothing of it.
     */
    public record Usage(long at, String account, String library, String use, String kind, String payer, String entry, long tokensIn, long tokensCached, long tokensOut,
                        long quantity, long bytes, long micros, boolean estimated) { }

    /** A month's totals of one kind, for one payer. */
    public record MonthTotal(String kind, String payer, long quantity, long tokens, long bytes, long micros, long estimated) { }

    /** The month an instant falls in, by this service's clock: 2026-11. */
    /** The month a moment counts in, in UTC on every machine: a service and its nodes never count the same moment in two months (round F, money 10). */
    public static String month(long at) { return Instant.ofEpochMilli(at).atZone(ZoneOffset.UTC).toLocalDate().toString().substring(0, 7); }

    /** What was spent written down, and added to each account's month in the same transaction: a cap is checked against the month. */
    public synchronized void addUsage(List<Usage> rows) throws IOException {
        if (rows.isEmpty()) return;
        try {
            c.setAutoCommit(false);
            for (Usage u : rows) {
                String month = month(u.at());
                exec("INSERT INTO usage (id, at, month, account_id, library_id, use_id, kind, payer, entry, tokens_in, tokens_cached, tokens_out, quantity, bytes, micros, estimated) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", id("u"), u.at(), month, u.account(), u.library(), u.use(), u.kind(), u.payer(), u.entry(),
                        u.tokensIn(), u.tokensCached(), u.tokensOut(), u.quantity(), u.bytes(), u.micros(), u.estimated());
                exec("INSERT INTO usage_month (account_id, month, kind, payer, quantity, tokens, bytes, micros, estimated) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) "
                        + "ON CONFLICT (account_id, month, kind, payer) DO UPDATE SET quantity = usage_month.quantity + excluded.quantity, tokens = usage_month.tokens + excluded.tokens, "
                        + "bytes = usage_month.bytes + excluded.bytes, micros = usage_month.micros + excluded.micros, estimated = usage_month.estimated + excluded.estimated",
                        u.account(), month, u.kind(), u.payer(), u.quantity(), u.tokensIn() + u.tokensCached() + u.tokensOut(), u.bytes(), u.micros(), u.estimated() ? 1L : 0L);
            }
            c.commit();
        } catch (SQLException e) {
            try { c.rollback(); } catch (SQLException ignored) { }
            throw new IOException("The account store could not be written: " + e.getMessage(), e);
        } finally { try { c.setAutoCommit(true); } catch (SQLException ignored) { } }
    }

    private void exec(String sql, Object... args) throws SQLException { try (PreparedStatement p = c.prepareStatement(sql)) { bind(p, args); p.executeUpdate(); } }

    /** An account's month, by kind and payer. */
    public List<MonthTotal> month(String account, String month) throws IOException {
        return query("SELECT kind, payer, quantity, tokens, bytes, micros, estimated FROM usage_month WHERE account_id = ? AND month = ? ORDER BY kind, payer",
                r -> new MonthTotal(r.getString(1), r.getString(2), r.getLong(3), r.getLong(4), r.getLong(5), r.getLong(6), r.getLong(7)), account, month);
    }

    /** What was spent under a use by one payer, in millionths of a dollar: the operator's is what a use's ceiling holds. */
    public long useSpentBy(String id, String payer) throws IOException { Long v = one("SELECT COALESCE(SUM(micros), 0) FROM usage WHERE use_id = ? AND payer = ?", r -> r.getLong(1), id, payer); return v == null ? 0 : v; }

    // ---- what calls in flight have put aside (0.8.0 review, BILL 11) ----

    /** How long a call's reservation stands when its end never came (its node stopped): longer than a call may take. */
    static volatile long RESERVED_MS = 35 * 60_000L;

    /**
     * {@code micros} put aside for one call of the use {@code use} of {@code account}, while what that use and that account's month
     * (the operator's dollars) have spent and put aside for other calls is under {@code useLimit} and {@code monthLimit} (-1: no limit):
     * the check and the putting aside are one step, so calls made at once see each other. The reservation's id; {@code "!use"} or
     * {@code "!month"} when that limit is reached.
     */
    public synchronized String reserve(String account, String use, long micros, long useLimit, long monthLimit, String month) throws IOException {
        return inTransaction(() -> {
            if (!sqlite) query("SELECT id FROM accounts WHERE id = ? FOR UPDATE", r -> r.getString(1), account);   // one account's calls one after another, whatever process
            update("DELETE FROM reserved WHERE at < ?", now() - RESERVED_MS);
            long forUse = one("SELECT COALESCE(SUM(micros), 0) FROM reserved WHERE use_id = ?", r -> r.getLong(1), use);
            long forAccount = one("SELECT COALESCE(SUM(micros), 0) FROM reserved WHERE account_id = ?", r -> r.getLong(1), account);
            if (useLimit >= 0 && useSpentBy(use, "operator") + forUse >= useLimit) return "!use";
            if (monthLimit >= 0) {
                Long spent = one("SELECT COALESCE(SUM(micros), 0) FROM usage_month WHERE account_id = ? AND month = ? AND payer = 'operator'", r -> r.getLong(1), account, month);
                if ((spent == null ? 0 : spent) + forAccount >= monthLimit) return "!month";
            }
            String id = id("r_");
            update("INSERT INTO reserved (id, account_id, use_id, micros, at) VALUES (?, ?, ?, ?, ?)", id, account, use, Math.max(0, micros), now());
            return id;
        });
    }

    /**
     * One more of a month's counted calls ({@code kind}, the operator's: searches) put aside while it runs, as dollars are: calls made at once
     * see each other, so a fan-out of searches does not pass the cap together (round F, money 10). The reservation's id, or "!" at the cap.
     */
    public synchronized String reserveCount(String account, String kind, long cap, String month) throws IOException {
        return inTransaction(() -> {
            if (!sqlite) query("SELECT id FROM accounts WHERE id = ? FOR UPDATE", r -> r.getString(1), account);
            update("DELETE FROM reserved WHERE at < ?", now() - RESERVED_MS);
            Long done = one("SELECT COALESCE(SUM(quantity), 0) FROM usage_month WHERE account_id = ? AND month = ? AND kind = ? AND payer = 'operator'", r -> r.getLong(1), account, month, kind);
            Long held = one("SELECT COUNT(*) FROM reserved WHERE account_id = ? AND use_id = ?", r -> r.getLong(1), account, "count:" + kind);
            if ((done == null ? 0 : done) + (held == null ? 0 : held) >= cap) return "!";
            String id = id("r_");
            update("INSERT INTO reserved (id, account_id, use_id, micros, at) VALUES (?, ?, ?, 0, ?)", id, account, "count:" + kind, now());
            return id;
        });
    }

    /** A model of a hosted API called with no prices in its entry (BILL 9): counted at an estimate, said on the operator's page until priced. */
    public record Unpriced(String origin, String model, long firstAt) { }
    public List<Unpriced> unpriced() throws IOException { return query("SELECT origin, model, first_at FROM unpriced ORDER BY first_at", r -> new Unpriced(r.getString(1), r.getString(2), r.getLong(3))); }
    public void unpricedSeen(String origin, String model) throws IOException {
        if (one("SELECT 1 FROM unpriced WHERE origin = ? AND model = ?", r -> r.getInt(1), origin, model) == null) {
            try { update("INSERT INTO unpriced (origin, model, first_at) VALUES (?, ?, ?)", origin, model, now()); } catch (IOException another) { /* another process wrote it a moment before */ }
        }
    }
    public void unpricedGone(String origin, String model) throws IOException { update("DELETE FROM unpriced WHERE origin = ? AND model = ?", origin, model); }

    /**
     * {@code work} with the account's row held (BILL 8): the jobs going counted and a new one written in one step, one start after
     * another for an account, in this process and, on PostgreSQL, in every process of the service.
     */
    public synchronized <T> T holdingAccount(String account, Callable<T> work) throws IOException {
        return inTransaction(() -> {
            if (!sqlite) query("SELECT id FROM accounts WHERE id = ? FOR UPDATE", r -> r.getString(1), account);
            try { return work.call(); }
            catch (IOException | RuntimeException e) { throw e; }
            catch (Exception e) { throw new IOException(e.getMessage(), e); }
        });
    }

    /** A call's reservation given back: what it cost is written, or it was not made. */
    public void unreserve(String id) throws IOException { if (id != null && !id.startsWith("!")) update("DELETE FROM reserved WHERE id = ?", id); }

    /** A use's rows, oldest first. */
    public List<Usage> usageOfUse(String id) throws IOException {
        return query("SELECT at, account_id, library_id, use_id, kind, payer, entry, tokens_in, tokens_cached, tokens_out, quantity, bytes, micros, estimated FROM usage WHERE use_id = ? ORDER BY at, id",
                r -> new Usage(r.getLong(1), r.getString(2), r.getString(3), r.getString(4), r.getString(5), r.getString(6), r.getString(7), r.getLong(8), r.getLong(9), r.getLong(10),
                        r.getLong(11), r.getLong(12), r.getLong(13), r.getInt(14) != 0), id);
    }

    /** A use's totals by kind and payer, from its rows. */
    public List<MonthTotal> useTotals(String id) throws IOException {
        return query("SELECT kind, payer, COALESCE(SUM(quantity), 0), COALESCE(SUM(tokens_in + tokens_cached + tokens_out), 0), COALESCE(SUM(bytes), 0), COALESCE(SUM(micros), 0), COALESCE(SUM(estimated), 0) "
                + "FROM usage WHERE use_id = ? GROUP BY kind, payer ORDER BY kind, payer", r -> new MonthTotal(r.getString(1), r.getString(2), r.getLong(3), r.getLong(4), r.getLong(5), r.getLong(6), r.getLong(7)), id);
    }

    // ---- a person's own keys (stage 3, step 4): sealed; the key that opens them is not in the store ----

    /** A person's own key for a host (a model's API, or a paid search place by its name): sealed, and the id of the key that sealed it. */
    public record OwnKey(String account, String host, String kind, String sealed, String kek, long madeAt) { }

    public OwnKey ownKey(String account, String host) throws IOException {
        return one("SELECT account_id, host, kind, sealed, kek, made_at FROM own_keys WHERE account_id = ? AND host = ?", r -> new OwnKey(r.getString(1), r.getString(2), r.getString(3), r.getString(4), r.getString(5), r.getLong(6)), account, host);
    }

    public List<OwnKey> ownKeys(String account) throws IOException {
        return query("SELECT account_id, host, kind, sealed, kek, made_at FROM own_keys WHERE account_id = ? ORDER BY kind, host", r -> new OwnKey(r.getString(1), r.getString(2), r.getString(3), r.getString(4), r.getString(5), r.getLong(6)), account);
    }

    public synchronized void setOwnKey(String account, String host, String kind, String sealed, String kek) throws IOException {
        if (update("UPDATE own_keys SET kind = ?, sealed = ?, kek = ?, made_at = ? WHERE account_id = ? AND host = ?", kind, sealed, kek, now(), account, host) == 0)
            update("INSERT INTO own_keys (account_id, host, kind, sealed, kek, made_at) VALUES (?, ?, ?, ?, ?, ?)", account, host, kind, sealed, kek, now());
    }

    public boolean removeOwnKey(String account, String host) throws IOException { return update("DELETE FROM own_keys WHERE account_id = ? AND host = ?", account, host) == 1; }

    // ---- the spending plug-in's outbox (stage 3, step 4): what was spent, in the order it was written, until the plug-in has taken it ----

    /** One entry of the outbox: its place in the order, its id (the use's), what is told, and how its sending has gone. */
    public record Outgoing(long seq, String id, String body, long madeAt, int tries, long nextAt, String last) { }

    /** The highest number this process gave an outbox entry: never given again, though the entry was taken and the table is empty. */
    private static final AtomicLong OUTBOX_ISSUED = new AtomicLong();

    /** An entry written at the end of the outbox. */
    public synchronized void outboxAdd(String id, String body) throws IOException {
        boolean within;
        try { within = !c.getAutoCommit(); } catch (SQLException e) { within = false; }
        for (int attempt = 0; ; attempt++) {
            Long last = one("SELECT MAX(seq) FROM outbox", r -> r.getObject(1) == null ? 0L : r.getLong(1));
            // never a number an entry taken a moment ago had: a sender still holding it would take this one (round F, money 10); the clock in
            // millionths goes up whatever was taken, and the last number plus one keeps the order within a millisecond
            long seq = Math.max(Math.max((last == null ? 0 : last) + 1, System.currentTimeMillis() * 1000), OUTBOX_ISSUED.get() + 1);
            OUTBOX_ISSUED.accumulateAndGet(seq, Math::max);
            try { update("INSERT INTO outbox (seq, id, body, made_at, tries, next_at, last) VALUES (?, ?, ?, ?, 0, 0, '')", seq, id, body, now()); return; }
            catch (IOException taken) { if (within || attempt >= 5) throw taken; }   // another process of the service wrote the same place a moment ago (in a transaction: the whole is tried again)
        }
    }

    /** The first entry of the outbox not set aside; null when there is none. */
    public Outgoing outboxHead() throws IOException {
        return one("SELECT seq, id, body, made_at, tries, next_at, last FROM outbox WHERE parked = 0 ORDER BY seq LIMIT 1", r -> new Outgoing(r.getLong(1), r.getString(2), r.getString(3), r.getLong(4), r.getInt(5), r.getLong(6), r.getString(7)));
    }

    /** The entries the plug-in refused again and again, set aside for the operator (BILL 12): sent again only when the operator says so. */
    public List<Outgoing> outboxParked() throws IOException {
        return query("SELECT seq, id, body, made_at, tries, next_at, last FROM outbox WHERE parked = 1 ORDER BY seq", r -> new Outgoing(r.getLong(1), r.getString(2), r.getString(3), r.getLong(4), r.getInt(5), r.getLong(6), r.getString(7)));
    }
    public void outboxPark(long seq, String last) throws IOException { update("UPDATE outbox SET parked = 1, tries = tries + 1, last = ? WHERE seq = ?", last.length() > 300 ? last.substring(0, 300) : last, seq); }
    /** A parked entry put back in the line, at its place, to be sent again. */
    public boolean outboxRetry(long seq) throws IOException { return update("UPDATE outbox SET parked = 0, tries = 0, next_at = 0 WHERE seq = ? AND parked = 1", seq) == 1; }

    public void outboxTaken(long seq) throws IOException { update("DELETE FROM outbox WHERE seq = ?", seq); }

    /**
     * What a use came to, told whole, was taken at {@code seq}: written down for 90 days, so that an older whole of the same use, sent again
     * from the parked ones, is dropped and never overwrites it at the plug-in (round F, money 8).
     */
    public void outboxTakenFor(String use, long seq) throws IOException { update("INSERT INTO spent_statements (hash, until) VALUES (?, ?)", "sent:" + use + ":" + seq, now() + 90L * 86_400_000L); }

    /** Whether a newer whole of the same use was taken, or waits, after {@code seq}. */
    public boolean outboxNewerFor(String use, long seq) throws IOException {
        for (String h : query("SELECT hash FROM spent_statements WHERE hash LIKE ?", r -> r.getString(1), "sent:" + use + ":%"))
            try { if (Long.parseLong(h.substring(h.lastIndexOf(':') + 1)) > seq) return true; } catch (NumberFormatException skip) { }
        Long waiting = one("SELECT COUNT(*) FROM outbox WHERE seq > ? AND (id = ? OR id LIKE ?)", r -> r.getLong(1), seq, use, use + "~%");
        return waiting != null && waiting > 0;
    }

    public void outboxFailed(long seq, long nextAt, String last) throws IOException { update("UPDATE outbox SET tries = tries + 1, next_at = ?, last = ? WHERE seq = ?", nextAt, last.length() > 300 ? last.substring(0, 300) : last, seq); }

    public long outboxSize() throws IOException { Long v = one("SELECT COUNT(*) FROM outbox WHERE parked = 0", r -> r.getLong(1)); return v == null ? 0 : v; }

    /** A library's last measure of its folder: {when, bytes}; null when it has none. */
    public long[] lastStorage(String libraryId) throws IOException {
        return one("SELECT at, bytes FROM usage WHERE library_id = ? AND kind = 'storage' ORDER BY at DESC LIMIT 1", r -> new long[]{r.getLong(1), r.getLong(2)}, libraryId);
    }

    /** What the libraries an account keeps hold, in bytes, each as last measured. */
    public long storedBytes(String account) throws IOException {
        long n = 0;
        for (Library l : librariesKeptBy(account)) { long[] s = lastStorage(l.id()); if (s != null) n += s[1]; }
        return n;
    }

    /** The accounts with anything spent in a month. */
    public List<String> accountsWithUse(String month) throws IOException { return query("SELECT DISTINCT account_id FROM usage_month WHERE month = ? ORDER BY account_id", r -> r.getString(1), month); }

    /** A cap: {@code scope} '*' for the service's, else an account's id; null when it is not set there. */
    public Long cap(String scope, String name) throws IOException { return one("SELECT value FROM caps WHERE scope = ? AND name = ?", r -> r.getLong(1), scope, name); }

    public synchronized void setCap(String scope, String name, long value) throws IOException {
        if (update("UPDATE caps SET value = ? WHERE scope = ? AND name = ?", value, scope, name) == 0) update("INSERT INTO caps (scope, name, value) VALUES (?, ?, ?)", scope, name, value);
    }

    public void unsetCap(String scope, String name) throws IOException { update("DELETE FROM caps WHERE scope = ? AND name = ?", scope, name); }

    // ---- the lanes of the service's model servers (0.8.0, stage 3, step 2) ----

    /** A call waiting its turn at a server: written when it begins to wait, gone when it has its slot or gives up. */
    public void laneWait(String id, String upstream, String lane, String account) throws IOException {
        update("INSERT INTO lane_waits (id, upstream, lane, account_id, since) VALUES (?, ?, ?, ?, ?)", id, upstream, lane, account, now());
    }

    public void laneWaitGone(String id) throws IOException { update("DELETE FROM lane_waits WHERE id = ?", id); }

    /**
     * A slot at a server for the waiting call {@code wait}, when it is its turn; its id, or null to wait on. Decided in one transaction
     * that first locks the server's row, so of the nodes taking at once one decides at a time. The waiting lane (a person who waits)
     * takes any free slot; the background lane leaves {@code reserved} slots for it, an account holds at most {@code perAccount} of the
     * background's, and among the background's waiting calls the account with the fewest slots goes first, then the one that waited
     * longest, and none while a person's call waits for a slot. A slot is held until {@code leaseMs} has passed, if its call never says it ended.
     */
    public synchronized String laneTake(String wait, String upstream, String lane, String account, int capacity, int reserved, int perAccount, long leaseMs) throws IOException {
        long now = now();
        try {
            c.setAutoCommit(false);
            if (sqlite) exec("INSERT OR IGNORE INTO lanes (upstream, at) VALUES (?, ?)", upstream, now);
            else exec("INSERT INTO lanes (upstream, at) VALUES (?, ?) ON CONFLICT (upstream) DO NOTHING", upstream, now);
            exec("UPDATE lanes SET at = ? WHERE upstream = ?", now, upstream);   // the server's row, locked till the end of this
            exec("DELETE FROM lane_slots WHERE upstream = ? AND until < ?", upstream, now);
            exec("DELETE FROM lane_waits WHERE upstream = ? AND since < ?", upstream, now - 3_600_000L);
            int total = count("SELECT COUNT(*) FROM lane_slots WHERE upstream = ?", upstream);
            boolean take;
            if (Gated.WAITING.equals(lane)) {
                take = total < capacity && first("SELECT id FROM lane_waits WHERE upstream = ? AND lane = ? ORDER BY since, id", upstream, lane).equals(wait);
            } else {
                int background = count("SELECT COUNT(*) FROM lane_slots WHERE upstream = ? AND lane = ?", upstream, Gated.BACKGROUND);
                int mine = count("SELECT COUNT(*) FROM lane_slots WHERE upstream = ? AND lane = ? AND account_id = ?", upstream, Gated.BACKGROUND, account);
                int people = count("SELECT COUNT(*) FROM lane_waits WHERE upstream = ? AND lane = ?", upstream, Gated.WAITING);   // a person who waits goes first
                take = people == 0 && total < capacity && background < Math.max(1, capacity - reserved) && mine < perAccount && wait.equals(nextBackground(upstream, perAccount));
            }
            String slot = null;
            if (take) {
                slot = id("slot_");
                exec("INSERT INTO lane_slots (id, upstream, lane, account_id, until) VALUES (?, ?, ?, ?, ?)", slot, upstream, lane, account, now + leaseMs);
                exec("DELETE FROM lane_waits WHERE id = ?", wait);
            }
            c.commit();
            return slot;
        } catch (SQLException e) {
            try { c.rollback(); } catch (SQLException ignored) { }
            throw new IOException("The account store could not be written: " + e.getMessage(), e);
        } finally { try { c.setAutoCommit(true); } catch (SQLException ignored) { } }
    }

    /** The background's waiting call whose turn it is: of the accounts below their share, the one with the fewest slots, then the longest wait. */
    private String nextBackground(String upstream, int perAccount) throws SQLException {
        String best = ""; long bestSlots = Long.MAX_VALUE, bestSince = Long.MAX_VALUE;
        try (PreparedStatement p = c.prepareStatement("SELECT w.id, w.since, (SELECT COUNT(*) FROM lane_slots s WHERE s.upstream = w.upstream AND s.lane = w.lane AND s.account_id = w.account_id) "
                + "FROM lane_waits w WHERE w.upstream = ? AND w.lane = ?")) {
            bind(p, upstream, Gated.BACKGROUND);
            try (ResultSet r = p.executeQuery()) {
                while (r.next()) {
                    long slots = r.getLong(3), since = r.getLong(2);
                    if (slots >= perAccount) continue;
                    if (slots < bestSlots || (slots == bestSlots && since < bestSince)) { best = r.getString(1); bestSlots = slots; bestSince = since; }
                }
            }
        }
        return best;
    }

    private int count(String sql, Object... args) throws SQLException {
        try (PreparedStatement p = c.prepareStatement(sql)) { bind(p, args); try (ResultSet r = p.executeQuery()) { return r.next() ? r.getInt(1) : 0; } }
    }

    private String first(String sql, Object... args) throws SQLException {
        try (PreparedStatement p = c.prepareStatement(sql)) { bind(p, args); try (ResultSet r = p.executeQuery()) { return r.next() ? r.getString(1) : ""; } }
    }

    /** A slot given back: its call ended. */
    public void laneGiveBack(String slot) throws IOException { update("DELETE FROM lane_slots WHERE id = ?", slot); }

    /** The calls going at a server now, by lane: {waiting, background}. */
    public int[] laneNow(String upstream) throws IOException {
        Integer w = one("SELECT COUNT(*) FROM lane_slots WHERE upstream = ? AND lane = ? AND until >= ?", r -> r.getInt(1), upstream, Gated.WAITING, now());
        Integer b = one("SELECT COUNT(*) FROM lane_slots WHERE upstream = ? AND lane = ? AND until >= ?", r -> r.getInt(1), upstream, Gated.BACKGROUND, now());
        return new int[]{w == null ? 0 : w, b == null ? 0 : b};
    }
}
