package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Accounts: how a person signs in (0.6.1). The person who keeps the library has one (the owner, did {@code person}); each reader
 * invited by name has one, with the did their line in {@code catalog/patrons.md} carries. An account is a name to sign in with, a
 * password ({@link Passwords}), the passkeys added to it ({@link Passkeys}), the one-time-code step when it is set up ({@link Totp}),
 * and the browsers signed in (sessions, each a random secret the browser keeps in its cookie and the library keeps the hash of).
 * Programs still use tokens ({@link Patrons}); a person never sees one. The file is {@code catalog/accounts.json}.
 */
public final class Accounts {
    private Accounts() { }

    static final ObjectMapper M = new ObjectMapper();
    /** The owner's did: the same {@code person} the command line and the chat act as. */
    public static final String OWNER = "person";
    /** How many wrong tries at a name before the library makes the next one wait, and for how long. */
    static final int TRIES = 10, WAIT_MINUTES = 10;

    public record Passkey(String id, String key, int alg, long count, String rp, String made, String label) { }
    public record Session(String hash, String made, String label) { }
    /** The one-time-code step: the shared secret (base32) and the last time step a code was taken, so a code is never taken twice. */
    public record Code(String secret, String since, long last) { }

    public record Account(String did, String name, String login, String password, List<Session> sessions, List<Passkey> passkeys, Code code) {
        public boolean owner() { return OWNER.equals(did); }
        public boolean hasPassword() { return password != null && !password.isEmpty(); }
        public boolean secondStep() { return code != null; }
        /** How this person signs in, said in a few words, for the lists. */
        public String ways() {
            List<String> w = new ArrayList<>();
            if (hasPassword()) w.add("password");
            if (!passkeys.isEmpty()) w.add(passkeys.size() == 1 ? "a passkey" : passkeys.size() + " passkeys");
            if (secondStep()) w.add("one-time code");
            return w.isEmpty() ? "no way to sign in yet" : String.join(", ", w);
        }
    }

    /** The sign-in name a person's name becomes: case and spacing do not matter. */
    public static String login(String name) {
        return name == null ? "" : name.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    static Path file(LibraryStore store) { return store.root().resolve("catalog").resolve("accounts.json"); }

    // ---- reading ----

    public static synchronized List<Account> list(LibraryStore store) throws IOException {
        List<Account> out = new ArrayList<>();
        for (JsonNode a : root(store).path("accounts")) out.add(account(a));
        return out;
    }

    public static Account owner(LibraryStore store) throws IOException { return byDid(store, OWNER); }

    public static Account byDid(LibraryStore store, String did) throws IOException {
        if (did == null || did.isBlank()) return null;
        for (Account a : list(store)) if (a.did().equals(did)) return a;
        return null;
    }

    public static Account byLogin(LibraryStore store, String name) throws IOException {
        String l = login(name);
        if (l.isEmpty()) return null;
        for (Account a : list(store)) if (a.login().equals(l)) return a;
        return null;
    }

    /** The account a browser's session secret belongs to, or null. */
    public static Account bySession(LibraryStore store, String secret) throws IOException {
        if (secret == null || secret.isBlank()) return null;
        String h = Patrons.sha256(secret.strip());
        for (Account a : list(store)) for (Session s : a.sessions()) if (s.hash().equals(h)) return a;
        return null;
    }

    public static Account byPasskey(LibraryStore store, String id) throws IOException {
        if (id == null || id.isBlank()) return null;
        for (Account a : list(store)) for (Passkey k : a.passkeys()) if (k.id().equals(id)) return a;
        return null;
    }

    // ---- making and changing ----

    /** An account for this did with a password; a name already taken by another account is refused in a sentence. */
    public static synchronized Account create(LibraryStore store, String did, String name, String password) throws IOException {
        String why = Passwords.why(password);
        if (why != null) throw new IOException(why);
        return createHashed(store, did, name, Passwords.hash(password));
    }

    /** The same with the hash already made (a visitor chose the password when they asked to be let in). */
    public static synchronized Account createHashed(LibraryStore store, String did, String name, String hash) throws IOException {
        if (did == null || did.isBlank()) throw new IOException("an account needs the person's id");
        String n = name == null ? "" : name.strip().replaceAll("\\s+", " ");
        if (n.isEmpty()) throw new IOException("an account needs the person's name");
        if (n.length() > 120) throw new IOException("Keep the name under 120 characters.");
        Account taken = byLogin(store, n);
        if (taken != null && !taken.did().equals(did)) throw new IOException("The name \"" + n + "\" is already in use here; choose another.");
        Account old = byDid(store, did);
        Account a = new Account(did, n, login(n), hash, List.of(), old == null ? List.of() : old.passkeys(), old == null ? null : old.code());
        put(store, a);
        Changes.append(store, "reader", did, old == null ? "account made" : "password set", n);
        return a;
    }

    public static synchronized void setPassword(LibraryStore store, String did, String password) throws IOException {
        String why = Passwords.why(password);
        if (why != null) throw new IOException(why);
        Account a = need(store, did);
        put(store, new Account(a.did(), a.name(), a.login(), Passwords.hash(password), a.sessions(), a.passkeys(), a.code()));
        Changes.append(store, "reader", did, "password set", a.name());
    }

    public static synchronized void rename(LibraryStore store, String did, String name) throws IOException {
        Account a = need(store, did);
        String n = name == null ? "" : name.strip().replaceAll("\\s+", " ");
        if (n.isEmpty()) throw new IOException("an account needs the person's name");
        Account taken = byLogin(store, n);
        if (taken != null && !taken.did().equals(did)) throw new IOException("The name \"" + n + "\" is already in use here; choose another.");
        put(store, new Account(a.did(), n, login(n), a.password(), a.sessions(), a.passkeys(), a.code()));
        if (!OWNER.equals(did)) Patrons.set(store, did, n, Patrons.load(store).levelFor(new Patrons.Patron(did, n, "")));
    }

    /** The account with every way in taken away and every browser signed out; the name stays, so a new password or link puts it back. */
    public static synchronized Account reset(LibraryStore store, String did) throws IOException {
        Account a = need(store, did);
        Account r = new Account(a.did(), a.name(), a.login(), null, List.of(), List.of(), null);
        put(store, r);
        Changes.append(store, "reader", did, "account reset", a.name());
        return r;
    }

    public static synchronized void remove(LibraryStore store, String did) throws IOException {
        ObjectNode root = root(store);
        ArrayNode all = (ArrayNode) root.withArray("accounts");
        for (Iterator<JsonNode> it = all.iterator(); it.hasNext(); ) if (it.next().path("did").asText().equals(did)) it.remove();
        save(store, root);
    }

    // ---- signing in ----

    /** The account these name and password open, or null; after {@link #TRIES} wrong tries at a name the next ones wait {@link #WAIT_MINUTES} minutes. */
    public static Account signIn(LibraryStore store, String name, String password) throws IOException {
        String key = "pw:" + login(name);
        if (tooMany(key)) throw new IOException("Too many tries. Wait " + WAIT_MINUTES + " minutes and try again.");
        Account a = byLogin(store, name);
        boolean ok = a != null && a.hasPassword() && Passwords.check(a.password(), password);
        if (!ok) { failed(key); return null; }
        cleared(key);
        return a;
    }

    /** A new session for this account: the secret the browser keeps, shown once; the library keeps its hash. */
    public static synchronized String newSession(LibraryStore store, String did, String label) throws IOException {
        Account a = need(store, did);
        String secret = Passwords.secret(32);
        List<Session> s = new ArrayList<>(a.sessions());
        s.add(new Session(Patrons.sha256(secret), LocalDate.now().toString(), label == null ? "" : label));
        while (s.size() > 50) s.remove(0);   // the oldest browser goes when a person keeps signing in from new ones
        put(store, new Account(a.did(), a.name(), a.login(), a.password(), s, a.passkeys(), a.code()));
        return secret;
    }

    public static synchronized void signOut(LibraryStore store, String secret) throws IOException {
        Account a = bySession(store, secret);
        if (a == null) return;
        String h = Patrons.sha256(secret.strip());
        List<Session> s = new ArrayList<>();
        for (Session x : a.sessions()) if (!x.hash().equals(h)) s.add(x);
        put(store, new Account(a.did(), a.name(), a.login(), a.password(), s, a.passkeys(), a.code()));
    }

    public static synchronized void signOutAll(LibraryStore store, String did) throws IOException {
        Account a = need(store, did);
        put(store, new Account(a.did(), a.name(), a.login(), a.password(), List.of(), a.passkeys(), a.code()));
    }

    // ---- passkeys and the one-time code ----

    public static synchronized void addPasskey(LibraryStore store, String did, Passkey k) throws IOException {
        Account a = need(store, did);
        List<Passkey> p = new ArrayList<>(a.passkeys());
        p.removeIf(x -> x.id().equals(k.id()));
        p.add(k);
        put(store, new Account(a.did(), a.name(), a.login(), a.password(), a.sessions(), p, a.code()));
        Changes.append(store, "reader", did, "passkey added", k.label());
    }

    public static synchronized void removePasskey(LibraryStore store, String did, String id) throws IOException {
        Account a = need(store, did);
        List<Passkey> p = new ArrayList<>(a.passkeys());
        p.removeIf(x -> x.id().equals(id));
        put(store, new Account(a.did(), a.name(), a.login(), a.password(), a.sessions(), p, a.code()));
    }

    public static synchronized void countPasskey(LibraryStore store, String id, long count) throws IOException {
        Account a = byPasskey(store, id);
        if (a == null) return;
        List<Passkey> p = new ArrayList<>();
        for (Passkey k : a.passkeys()) p.add(k.id().equals(id) ? new Passkey(k.id(), k.key(), k.alg(), count, k.rp(), k.made(), k.label()) : k);
        put(store, new Account(a.did(), a.name(), a.login(), a.password(), a.sessions(), p, a.code()));
    }

    public static synchronized void setCode(LibraryStore store, String did, String secret) throws IOException { setCode(store, did, secret, 0); }

    /** The same, with the time step of the code that proved the app has the secret, so that code is not taken again. */
    public static synchronized void setCode(LibraryStore store, String did, String secret, long usedStep) throws IOException {
        Account a = need(store, did);
        put(store, new Account(a.did(), a.name(), a.login(), a.password(), a.sessions(), a.passkeys(), new Code(secret, LocalDate.now().toString(), usedStep)));
        Changes.append(store, "reader", did, "one-time code step on", a.name());
    }

    public static synchronized void clearCode(LibraryStore store, String did) throws IOException {
        Account a = need(store, did);
        put(store, new Account(a.did(), a.name(), a.login(), a.password(), a.sessions(), a.passkeys(), null));
        Changes.append(store, "reader", did, "one-time code step off", a.name());
    }

    /** The code checked and, when right, its time step remembered so the same code is not taken again. False when wrong or used. */
    public static synchronized boolean takeCode(LibraryStore store, String did, String code) throws IOException {
        String key = "code:" + did;
        if (tooMany(key)) throw new IOException("Too many tries. Wait " + WAIT_MINUTES + " minutes and try again.");
        Account a = need(store, did);
        if (a.code() == null) return false;
        long step = Totp.stepOf(a.code().secret(), code, System.currentTimeMillis());
        if (step < 0 || step <= a.code().last()) { failed(key); return false; }
        cleared(key);
        put(store, new Account(a.did(), a.name(), a.login(), a.password(), a.sessions(), a.passkeys(), new Code(a.code().secret(), a.code().since(), step)));
        return true;
    }

    // ---- the owner's account, from the terminal ----

    /**
     * The owner's account made or changed at the keyboard: the name and the password asked for (the password twice, not shown), or taken
     * from the arguments ({@code --name}, {@code --password}, for a script). What was done, in a sentence; at a terminal nobody types at,
     * the command to run instead.
     */
    public static String setupOwner(LibraryStore store, List<String> args) throws IOException {
        String name = null, password = null;
        for (int i = 0; i < args.size(); i++) {
            if (args.get(i).equals("--name") && i + 1 < args.size()) name = args.get(++i);
            else if (args.get(i).equals("--password") && i + 1 < args.size()) password = args.get(++i);
        }
        Account have = owner(store);
        if (name == null || password == null) {
            if (!Interaction.interactive()) return "No account for the person who keeps the library yet. Make it with: researchzosho account set   (it asks for your name and a password; or --name \"<name>\" --password \"<password>\" for a script)";
            if (name == null) {
                System.out.print("Your name, to sign in with" + (have == null ? "" : " [" + have.name() + "]") + ": ");
                System.out.flush();
                String line = Interaction.readLine();
                name = line == null ? "" : line.strip();
                if (name.isEmpty() && have != null) name = have.name();
            }
            if (password == null) {
                for (int tries = 0; tries < 3 && password == null; tries++) {
                    String p1 = Interaction.readSecret("A password (at least " + Passwords.MIN + " characters):");
                    String why = Passwords.why(p1);
                    if (why != null) { System.out.println(why); continue; }
                    String p2 = Interaction.readSecret("The same again:");
                    if (!p1.equals(p2)) { System.out.println("The two differ. Once more."); continue; }
                    password = p1;
                }
                if (password == null) return "No password was set.";
            }
        }
        if (have == null) create(store, OWNER, name, password);
        else { setPassword(store, OWNER, password); if (!name.isBlank() && !name.equals(have.name())) rename(store, OWNER, name); }
        Account a = owner(store);
        return (have == null ? "Your account is made: " : "Your account is changed: ") + "sign in as \"" + a.name() + "\" with that password, from any phone or computer that can reach the library.";
    }

    // ---- too many tries ----

    private static final Map<String, List<Long>> TRIED = new ConcurrentHashMap<>();

    static boolean tooMany(String key) {
        List<Long> t = TRIED.get(key);
        if (t == null) return false;
        long cutoff = System.currentTimeMillis() - WAIT_MINUTES * 60_000L;
        synchronized (t) { t.removeIf(x -> x < cutoff); return t.size() >= TRIES; }
    }

    static void failed(String key) {
        List<Long> t = TRIED.computeIfAbsent(key, k -> new ArrayList<>());
        synchronized (t) { t.add(System.currentTimeMillis()); }
    }

    static void cleared(String key) { TRIED.remove(key); }

    // ---- the file ----

    private static Account need(LibraryStore store, String did) throws IOException {
        Account a = byDid(store, did);
        if (a == null) throw new IOException(OWNER.equals(did) ? "The person who keeps this library has no account yet." : "There is no account for " + did + ".");
        return a;
    }

    private static synchronized void put(LibraryStore store, Account a) throws IOException {
        ObjectNode root = root(store);
        ArrayNode all = (ArrayNode) root.withArray("accounts");
        ObjectNode n = M.createObjectNode();
        n.put("did", a.did()); n.put("name", a.name()); n.put("login", a.login());
        if (a.hasPassword()) n.put("password", a.password());
        ArrayNode s = n.putArray("sessions");
        for (Session x : a.sessions()) { ObjectNode o = s.addObject(); o.put("hash", x.hash()); o.put("made", x.made()); o.put("label", x.label()); }
        ArrayNode p = n.putArray("passkeys");
        for (Passkey k : a.passkeys()) { ObjectNode o = p.addObject(); o.put("id", k.id()); o.put("key", k.key()); o.put("alg", k.alg()); o.put("count", k.count()); o.put("rp", k.rp()); o.put("made", k.made()); o.put("label", k.label()); }
        if (a.code() != null) { ObjectNode o = n.putObject("code"); o.put("secret", a.code().secret()); o.put("since", a.code().since()); o.put("last", a.code().last()); }
        boolean replaced = false;
        for (int i = 0; i < all.size(); i++) if (all.get(i).path("did").asText().equals(a.did())) { all.set(i, n); replaced = true; }
        if (!replaced) all.add(n);
        save(store, root);
    }

    private static ObjectNode root(LibraryStore store) throws IOException {
        Path f = file(store);
        if (!Files.exists(f)) { ObjectNode r = M.createObjectNode(); r.putArray("accounts"); return r; }
        JsonNode n = M.readTree(Files.readString(f, StandardCharsets.UTF_8));
        if (!n.isObject()) throw new IOException(f + " is not the accounts file the library writes.");
        return (ObjectNode) n;
    }

    private static void save(LibraryStore store, ObjectNode root) throws IOException {
        Files.createDirectories(file(store).getParent());
        AtomicWrite.text(file(store), M.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n");
    }

    private static Account account(JsonNode a) {
        List<Session> s = new ArrayList<>();
        for (JsonNode x : a.path("sessions")) s.add(new Session(x.path("hash").asText(), x.path("made").asText(), x.path("label").asText()));
        List<Passkey> p = new ArrayList<>();
        for (JsonNode k : a.path("passkeys")) p.add(new Passkey(k.path("id").asText(), k.path("key").asText(), k.path("alg").asInt(), k.path("count").asLong(), k.path("rp").asText(), k.path("made").asText(), k.path("label").asText()));
        Code c = a.hasNonNull("code") ? new Code(a.path("code").path("secret").asText(), a.path("code").path("since").asText(), a.path("code").path("last").asLong()) : null;
        String name = a.path("name").asText();
        return new Account(a.path("did").asText(), name, a.path("login").asText(login(name)), a.hasNonNull("password") ? a.path("password").asText() : null, List.copyOf(s), List.copyOf(p), c);
    }
}
