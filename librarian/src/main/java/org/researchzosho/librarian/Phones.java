package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.researchzosho.Config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The phones paired with this library (2026-11-25): who may use the phone door ({@link PhoneDoor}). A phone is paired once, by a code the
 * library's own machine shows as a QR code ({@code researchzosho phone pair}); from then on its browser carries a secret of its own in a
 * cookie, and it is that phone until the person removes it. Kept in ResearchZosho's own folder, {@code phones.json} (mode 0600), which no
 * update, restart or {@code phone off} touches: what is kept of a phone's secret and of a code is its SHA-256, never the thing itself (both
 * are random and long, so a plain hash is enough to make the file useless to whoever reads it).
 *
 * <p>The file is read again at every look (it is a few hundred bytes), so a phone removed from the terminal is out at the door's next
 * request; every change is made whole, under a lock both the service and a command take.
 */
final class Phones {
    private Phones() { }

    private static final ObjectMapper M = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();

    /** How long a pairing code is good for. */
    static volatile long CODE_MS = 10 * 60_000L;
    /** How long a used or expired code is kept, so that {@code phone pair} can say what became of it. */
    static final long KEPT_MS = 60 * 60_000L;

    /** The file, in this program's own folder. */
    static Path file() { return Config.home().resolve("phones.json"); }
    private static Path lock() { return Config.home().resolve("phones.lock"); }

    /**
     * One browser of a phone: the hash of its secret, since when, and how it came. A pairing makes one ("qr"); a phone paired before
     * 2026-11-25's change may also hold a "home" one, made by the home-screen code that change took out, and it goes on working.
     */
    record Key(String hash, String since, String how) { }

    /**
     * A paired phone. {@code seen}: when it last looked at the runs (what finished since is new to it). {@code cap}: the dollars a month
     * its runs and its chat may spend on paid models, all its work together; 0: no paid model from it at all.
     */
    record Phone(String id, String name, String paired, String used, String seen, List<Key> keys, double cap) {
        Phone with(String used, String seen, List<Key> keys, double cap) { return new Phone(id, name, paired, used, seen, keys, cap); }
    }

    /** What a phone may spend on paid models in a month when nobody said: ten dollars (the owner's choice, 2026-11-25). */
    static final double DEFAULT_CAP = 10.0;
    /** A cap that is no limit: nothing is refused or stopped for spending from that phone (the owner's word, 2026-11-25: "-1", or "none"). */
    static final double NO_LIMIT = -1;

    /** A cap as a person writes it: dollars, -1 or none for no limit; null when it is neither. */
    static Double capOf(String words) {
        String w = words == null ? "" : words.strip().toLowerCase(Locale.ROOT).replaceFirst("^\\$", "");
        if (w.equals("none") || w.equals("-1") || w.equals("unlimited")) return NO_LIMIT;
        if (!w.matches("\\d{1,6}(\\.\\d{1,2})?")) return null;
        return Double.parseDouble(w);
    }

    /** A cap as the list and the phone show it: "$10.00 a month", "no paid model", "no limit". */
    static String capSaid(double cap, Chrome c) { return cap < 0 ? c.t("no limit") : cap == 0 ? c.t("no paid model") : c.t("{0} a month", Spend.dollars(cap)); }

    /** A code: its hash, until when it is good, the name it pairs, the phone it adds a browser to ("" for a QR code), the phone it made once used, and the cap the phone gets. */
    record Code(String hash, long until, String name, String phone, String used, double cap) { }

    record State(List<Phone> phones, List<Code> codes) { }

    // ---------------------------------------------------------------- reading and writing

    static State read() throws IOException {
        Path f = file();
        if (!Files.isRegularFile(f)) return new State(new ArrayList<>(), new ArrayList<>());
        JsonNode j;
        try { j = M.readTree(Files.readString(f, StandardCharsets.UTF_8)); }
        catch (IOException e) { throw new IOException("The list of paired phones (" + f + ") cannot be read: " + e.getMessage(), e); }
        List<Phone> phones = new ArrayList<>();
        for (JsonNode p : j.path("phones")) {
            List<Key> keys = new ArrayList<>();
            for (JsonNode k : p.path("keys")) keys.add(new Key(k.path("hash").asText(""), k.path("since").asText(""), k.path("how").asText("")));
            phones.add(new Phone(p.path("id").asText(""), p.path("name").asText(""), p.path("paired").asText(""), p.path("used").asText(""), p.path("seen").asText(""), keys, p.path("cap").asDouble(DEFAULT_CAP)));
        }
        List<Code> codes = new ArrayList<>();
        for (JsonNode c : j.path("codes")) codes.add(new Code(c.path("hash").asText(""), c.path("until").asLong(0), c.path("name").asText(""), c.path("phone").asText(""), c.path("used").asText(""), c.path("cap").asDouble(DEFAULT_CAP)));
        return new State(phones, codes);
    }

    private static void write(State s) throws IOException {
        ObjectNode o = M.createObjectNode();
        ArrayNode ps = o.putArray("phones");
        for (Phone p : s.phones()) {
            ObjectNode n = ps.addObject().put("id", p.id()).put("name", p.name()).put("paired", p.paired()).put("used", p.used()).put("seen", p.seen()).put("cap", p.cap());
            ArrayNode ks = n.putArray("keys");
            for (Key k : p.keys()) ks.addObject().put("hash", k.hash()).put("since", k.since()).put("how", k.how());
        }
        ArrayNode cs = o.putArray("codes");
        long now = System.currentTimeMillis();
        for (Code c : s.codes()) if (c.until() + KEPT_MS > now) cs.addObject().put("hash", c.hash()).put("until", c.until()).put("name", c.name()).put("phone", c.phone()).put("used", c.used()).put("cap", c.cap());
        Path f = file();
        AtomicWrite.text(f, M.writerWithDefaultPrettyPrinter().writeValueAsString(o) + "\n");
        try { Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("rw-------")); } catch (UnsupportedOperationException | IOException notPosix) { /* Windows: the folder is the person's own */ }
    }

    /** A change made whole, under the lock the service and a command both take. */
    private static <T> T change(FileLocks.Step<T> step) throws IOException { return FileLocks.under(lock(), step); }

    // ---------------------------------------------------------------- secrets

    static String random(int bytes) { byte[] b = new byte[bytes]; RANDOM.nextBytes(b); return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }

    static String hash(String secret) { return Patrons.sha256(secret == null ? "" : secret.strip()); }

    private static String now() { return Instant.now().toString().replaceAll("\\.\\d+Z$", "Z"); }

    // ---------------------------------------------------------------- pairing

    /** A new pairing code for a phone to be called {@code name} (a free name is chosen when it is blank or taken): the code itself, once. */
    record Made(String code, String name, long until, double cap) { }

    static Made pairingCode(String name) throws IOException { return pairingCode(name, DEFAULT_CAP); }

    /** The same, the phone to be allowed {@code cap} dollars a month on paid models. */
    static Made pairingCode(String name, double cap) throws IOException {
        return change(() -> {
            State s = read();
            String n = freeName(s, name);
            String code = random(16);
            long until = System.currentTimeMillis() + CODE_MS;
            s.codes().add(new Code(hash(code), until, n, "", "", cap < 0 ? NO_LIMIT : cap));
            write(s);
            return new Made(code, n, until, cap < 0 ? NO_LIMIT : cap);
        });
    }

    /** What a code was traded for: the phone and the secret its browser keeps from now on. */
    record Paired(Phone phone, String secret) { }

    /**
     * A pairing code traded for a new phone and its secret: one browser per pairing (a second browser is paired with a new code, as any
     * phone is). The code is spent whatever comes of it. Null when the code is not one the library made, was used, or ran out: all three
     * alike; and for a home-screen code an earlier version made for a phone already paired, which pairs nothing now.
     */
    static Paired redeem(String code) throws IOException {
        if (code == null || code.isBlank() || code.length() > 64) return null;
        String h = hash(code);
        return change(() -> {
            State s = read();
            long now = System.currentTimeMillis();
            int at = -1;
            for (int i = 0; i < s.codes().size(); i++) if (s.codes().get(i).hash().equals(h)) at = i;
            if (at < 0) return null;
            Code c = s.codes().get(at);
            if (!c.used().isEmpty() || c.until() < now) return null;
            String secret = random(32), when = now();
            s.codes().set(at, new Code(c.hash(), c.until(), c.name(), c.phone(), "-", c.cap()));   // spent before the name is chosen: its own name is not taken by itself
            if (!c.phone().isEmpty()) { write(s); return null; }   // an earlier version's home-screen code: spent, pairs nothing
            Phone p = new Phone(newId(s), freeName(s, c.name()), when, when, "", new ArrayList<>(List.of(new Key(hash(secret), when, "qr"))), c.cap());
            s.phones().add(p);
            s.codes().set(at, new Code(c.hash(), c.until(), c.name(), c.phone(), p.id(), c.cap()));
            write(s);
            return new Paired(p, secret);
        });
    }

    /** What became of a pairing code, for the command that showed it: the phone it paired, or "" while it waits, or null once it ran out unused. */
    static String usedBy(String code) throws IOException {
        String h = hash(code);
        for (Code c : read().codes()) if (c.hash().equals(h)) return !c.used().isEmpty() ? c.used() : c.until() < System.currentTimeMillis() ? null : "";
        return null;
    }

    // ---------------------------------------------------------------- a request's phone

    /** When each phone's last use was written: at most once a minute, so a page that asks every few seconds writes the file seldom. */
    private static final Map<String, Long> USED_WRITTEN = new ConcurrentHashMap<>();

    /** The phone whose browser holds this secret, or null. Its last use is written at most once a minute. */
    static Phone bySecret(String secret) throws IOException {
        if (secret == null || secret.isBlank() || secret.length() > 128) return null;
        String h = hash(secret);
        Phone found = null;
        for (Phone p : read().phones()) for (Key k : p.keys()) if (k.hash().equals(h)) found = p;
        if (found == null) return null;
        long now = System.currentTimeMillis();
        Long last = USED_WRITTEN.get(found.id());
        if (last == null || now - last > 60_000L) { USED_WRITTEN.put(found.id(), now); touch(found.id(), null); }
        return found;
    }

    /** The phone's last use set to now; with {@code seen}, when it last looked at the runs too. */
    static void touch(String id, String seen) throws IOException {
        change(() -> {
            State s = read();
            Phone p = byId(s, id);
            if (p == null) return null;
            s.phones().set(s.phones().indexOf(p), p.with(now(), seen == null ? p.seen() : seen, p.keys(), p.cap()));
            write(s);
            return null;
        });
    }

    /** The phone looked at the runs now: what finished before this is no longer new to it. */
    static void seen(String id) throws IOException { touch(id, now()); }

    static Phone get(String id) throws IOException { return byId(read(), id); }

    // ---------------------------------------------------------------- the list

    static List<Phone> list() throws IOException { return read().phones(); }

    /** A phone removed by its name (any case) or its id, every browser of it and every code it made: the phone removed, or null when none is called that. */
    static Phone remove(String nameOrId) throws IOException {
        String w = nameOrId == null ? "" : nameOrId.strip();
        if (w.isEmpty()) return null;
        return change(() -> {
            State s = read();
            Phone hit = null;
            for (Phone p : s.phones()) if (p.id().equals(w) || p.name().equalsIgnoreCase(w)) hit = p;
            if (hit == null) return null;
            s.phones().remove(hit);
            final String gone = hit.id();
            s.codes().removeIf(c -> c.phone().equals(gone) && c.used().isEmpty());
            write(s);
            USED_WRITTEN.remove(gone);
            return hit;
        });
    }

    /** A phone's monthly cap on paid models set, by its name or id: the phone, or null when none is called that. */
    static Phone cap(String nameOrId, double dollars) throws IOException {
        String w = nameOrId == null ? "" : nameOrId.strip();
        return change(() -> {
            State s = read();
            Phone hit = null;
            for (Phone p : s.phones()) if (p.id().equals(w) || p.name().equalsIgnoreCase(w)) hit = p;
            if (hit == null) return null;
            Phone now = hit.with(hit.used(), hit.seen(), hit.keys(), dollars < 0 ? NO_LIMIT : dollars);
            s.phones().set(s.phones().indexOf(hit), now);
            write(s);
            return now;
        });
    }

    private static Phone byId(State s, String id) { for (Phone p : s.phones()) if (p.id().equals(id)) return p; return null; }

    private static String newId(State s) {
        while (true) {
            String id = "p" + HexFormat.of().formatHex(bytes(4));
            if (byId(s, id) == null) return id;
        }
    }

    private static byte[] bytes(int n) { byte[] b = new byte[n]; RANDOM.nextBytes(b); return b; }

    /** A name as a person reads it: printable, one line, at most 40 characters; "phone" when nothing is left. */
    static String cleanName(String name) {
        String n = name == null ? "" : name.replaceAll("[\\p{Cntrl}\\p{Zl}\\p{Zp}]", " ").replaceAll("\\s+", " ").strip();
        if (n.length() > 40) n = n.substring(0, 40).strip();
        return n.isEmpty() ? "phone" : n;
    }

    /** The name, or the name with a number after it when a phone or a waiting code already has it. */
    static String freeName(State s, String name) {
        String base = cleanName(name);
        for (int i = 1; ; i++) {
            String n = i == 1 ? base : base + " " + i;
            boolean taken = false;
            for (Phone p : s.phones()) if (p.name().equalsIgnoreCase(n)) taken = true;
            for (Code c : s.codes()) if (c.phone().isEmpty() && c.used().isEmpty() && c.until() > System.currentTimeMillis() && c.name().equalsIgnoreCase(n)) taken = true;
            if (!taken) return n;
        }
    }

    /** A date as the list shows it: the day and the minute, in this machine's time. */
    static String shown(String iso) {
        if (iso == null || iso.isBlank()) return "never";
        try { return Instant.parse(iso).atZone(ZoneId.systemDefault()).toLocalDateTime().toString().replace('T', ' ').replaceAll(":\\d{2}(\\.\\d+)?$", ""); }
        catch (RuntimeException e) { return iso; }
    }

    static String lower(String s) { return s == null ? "" : s.toLowerCase(Locale.ROOT); }
}
