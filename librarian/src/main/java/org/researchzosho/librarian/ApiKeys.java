package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * API keys (0.7.0): what a program of a person's signs in with. A signed-in person makes one on the Keep page, on their account page
 * or with {@code researchzosho keys new}, gives it a label and a level, read or write, and sees it once in full; from then on the
 * library shows its prefix and keeps only its hash, as it does for passwords and sessions. A program sends it as a bearer token,
 * {@code Authorization: Bearer rz_…}, over the HTTP protocol and the MCP server; it is checked before any cookie, counts its day of
 * last use, and is revoked with one click or {@code keys revoke}. A read key never writes, whatever the person may; a write key does
 * what the person may. The file is {@code catalog/keys.json}.
 */
public final class ApiKeys {
    private ApiKeys() { }

    static final ObjectMapper M = new ObjectMapper();
    /** Every key begins so, which is how a bearer token is told from a reader's token and a session's secret. */
    public static final String PREFIX = "rz_";
    static final int MAX_PER_PERSON = 50, LABEL_MAX = 80;

    public record Key(String id, String did, String prefix, String hash, String label, Patrons.Level level, String made, String used) {
        public boolean reads() { return level == Patrons.Level.read; }
    }
    /** A key as made: the record kept, and the secret shown once. */
    public record Minted(Key key, String secret) { }

    static Path file(LibraryStore store) { return store.root().resolve("catalog").resolve("keys.json"); }

    /** The keys of one person, newest first; {@code did} null for everyone's. */
    public static synchronized List<Key> list(LibraryStore store, String did) throws IOException {
        List<Key> out = new ArrayList<>();
        for (JsonNode k : root(store).path("keys")) { Key x = key(k); if (did == null || x.did().equals(did)) out.add(x); }
        out.sort((a, b) -> b.made().compareTo(a.made()));
        return out;
    }

    /** A new key for this person: the secret shown once; the label and the level, read or write, kept with its prefix and hash. */
    public static synchronized Minted mint(LibraryStore store, String did, String label, Patrons.Level level) throws IOException {
        if (did == null || did.isBlank()) throw new IOException("A key belongs to a signed-in person.");
        if (level == null || level == Patrons.Level.deny) throw new IOException("A key reads or writes; say which.");
        String l = label == null ? "" : label.strip().replaceAll("\\s+", " ");
        if (l.isEmpty()) l = "a program";
        if (l.length() > LABEL_MAX) throw new IOException("Keep the label under " + LABEL_MAX + " characters.");
        if (list(store, did).size() >= MAX_PER_PERSON) throw new IOException("You have " + MAX_PER_PERSON + " keys already. Revoke one you no longer use first.");
        byte[] b = new byte[32];
        new SecureRandom().nextBytes(b);
        String secret = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(b);
        String id;
        do { byte[] r = new byte[3]; new SecureRandom().nextBytes(r); id = "K-" + HexFormat.of().formatHex(r); } while (byId(store, id) != null);
        Key k = new Key(id, did, secret.substring(0, PREFIX.length() + 8), Patrons.sha256(secret), l, level, LocalDate.now().toString(), "");
        ObjectNode root = root(store);
        ArrayNode all = (ArrayNode) root.withArray("keys");
        all.add(node(k));
        save(store, root);
        Changes.append(store, "reader", did, "key made", l + " (" + level + ")");
        return new Minted(k, secret);
    }

    /** The key with this id, or whose prefix this is, among a person's own; gone when revoked. False when there is no such key. */
    public static synchronized boolean revoke(LibraryStore store, String did, String idOrPrefix) throws IOException {
        if (idOrPrefix == null || idOrPrefix.isBlank()) return false;
        String want = idOrPrefix.strip();
        ObjectNode root = root(store);
        ArrayNode all = (ArrayNode) root.withArray("keys");
        for (int i = 0; i < all.size(); i++) {
            Key k = key(all.get(i));
            if (!k.did().equals(did)) continue;
            if (k.id().equals(want) || k.prefix().equals(want) || (want.startsWith(PREFIX) && want.startsWith(k.prefix()))) {
                all.remove(i);
                save(store, root);
                Changes.append(store, "reader", did, "key revoked", k.label() + " (" + k.prefix() + "…)");
                return true;
            }
        }
        return false;
    }

    /** The key a bearer token proves, or null; its day of last use is noted, once a day. */
    public static Key resolve(LibraryStore store, String secret) throws IOException {
        if (secret == null || !secret.startsWith(PREFIX)) return null;
        String h = Patrons.sha256(secret.strip());
        for (Key k : list(store, null)) if (k.hash().equals(h)) { touch(store, k); return k; }
        return null;
    }

    public static Key byId(LibraryStore store, String id) throws IOException {
        for (Key k : list(store, null)) if (k.id().equals(id)) return k;
        return null;
    }

    private static final Map<String, String> TOUCHED = new ConcurrentHashMap<>();

    /** Today as the key's day of last use, written when the day changed: one write per key per day. */
    private static synchronized void touch(LibraryStore store, Key k) throws IOException {
        String today = LocalDate.now().toString();
        if (today.equals(k.used()) || today.equals(TOUCHED.get(k.id()))) return;
        ObjectNode root = root(store);
        ArrayNode all = (ArrayNode) root.withArray("keys");
        for (int i = 0; i < all.size(); i++) if (all.get(i).path("id").asText().equals(k.id())) ((ObjectNode) all.get(i)).put("used", today);
        save(store, root);
        TOUCHED.put(k.id(), today);
    }

    // ---- the file ----

    private static ObjectNode node(Key k) {
        ObjectNode n = M.createObjectNode();
        n.put("id", k.id()); n.put("did", k.did()); n.put("prefix", k.prefix()); n.put("hash", k.hash()); n.put("label", k.label()); n.put("level", k.level().name()); n.put("made", k.made()); n.put("used", k.used());
        return n;
    }

    private static Key key(JsonNode n) {
        Patrons.Level level;
        try { level = Patrons.Level.valueOf(n.path("level").asText("read")); } catch (IllegalArgumentException e) { level = Patrons.Level.read; }
        return new Key(n.path("id").asText(), n.path("did").asText(), n.path("prefix").asText(), n.path("hash").asText(), n.path("label").asText(), level, n.path("made").asText(), n.path("used").asText(""));
    }

    private static ObjectNode root(LibraryStore store) throws IOException {
        Path f = file(store);
        if (!Files.exists(f)) { ObjectNode r = M.createObjectNode(); r.putArray("keys"); return r; }
        JsonNode n = M.readTree(Files.readString(f, StandardCharsets.UTF_8));
        if (!n.isObject()) throw new IOException(f + " is not the keys file the library writes.");
        return (ObjectNode) n;
    }

    private static void save(LibraryStore store, ObjectNode root) throws IOException {
        Files.createDirectories(file(store).getParent());
        AtomicWrite.text(file(store), M.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n");
    }
}
