package org.researchzosho.librarian;

import org.researchzosho.Config;
import org.researchzosho.drive.DriveClient;
import org.researchzosho.drive.anthropic.Anthropic;
import org.researchzosho.drive.openai.OpenAI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A person's own keys under an account service (0.8.0, stage 3, step 4): "my key for my work", one for each host the service's model
 * is at (an API on the internet) and each paid search place. The store keeps them sealed (AES-256-GCM, the account and the host bound
 * in), and the key that opens them is a file of its own, outside the store and every library's folder, readable by whoever runs the
 * service alone (mode 0600). Only the node's gates open a person's key, for the one call they pass on; a library never has one, and no
 * log says one. When the file is lost a new one is made, and the keys sealed with the old one cannot be opened: each person is told on
 * their account page to set theirs again, and their work goes on with the service's keys meanwhile. Nothing else is lost.
 */
final class OwnKeys {
    private OwnKeys() { }

    private static final Logger LOG = LoggerFactory.getLogger(OwnKeys.class);
    /** The setting that names the file of the key that seals the persons' keys; unset, a file in this program's home, beside the accounts. */
    static final String FILE = "RESEARCHZOSHO_ACCOUNTS_KEYS_FILE";
    static final String MODEL = "model", SEARCH = "search";

    /** The key that seals, and its id: the first 16 hex digits of its SHA-256, written with each sealed key to tell which key sealed it. */
    record Kek(byte[] key, String id) { }

    private static volatile Kek kek;
    private static volatile Path kekAt;

    static Path file() {
        String set = Config.get(FILE);
        return set != null && !set.isBlank() ? Path.of(set.strip()).toAbsolutePath().normalize() : Config.home().resolve("accounts-own-keys.key").toAbsolutePath().normalize();
    }

    /** The key, read from its file, or made there (0600) when there is none; refused when the file would be inside the accounts' folder. */
    static synchronized Kek kek() throws IOException {
        Path f = file();
        if (kek != null && f.equals(kekAt)) return kek;
        Path root = AccountService.root().toAbsolutePath().normalize();
        if (f.startsWith(root)) throw new IOException("The file of the key that seals the persons' own keys must be outside the accounts' folder (" + FILE + ").");
        byte[] k;
        if (Files.exists(f)) {
            k = Base64.getDecoder().decode(Files.readString(f).strip());
            if (k.length != 32) throw new IOException("The file of the key that seals the persons' own keys is not one this program made.");
        } else {
            k = new byte[32];
            new SecureRandom().nextBytes(k);
            Files.createDirectories(f.getParent());
            try {
                try { Files.createFile(f, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))); }
                catch (UnsupportedOperationException notPosix) { Files.createFile(f); }
                Files.writeString(f, Base64.getEncoder().encodeToString(k) + "\n");
                LOG.info("A new key that seals the persons' own keys is made in {}. Keep a copy of it where the store's copies are not: without it, the persons set their keys again.", f);
            } catch (FileAlreadyExistsException another) { k = Base64.getDecoder().decode(Files.readString(f).strip()); }   // another process of the service made it a moment ago
        }
        try { kek = new Kek(k, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(k)).substring(0, 16)); }
        catch (GeneralSecurityException e) { throw new IOException(e); }
        kekAt = f;
        return kek;
    }

    /** Forgets the key read, so the next call reads its file again (a test's). */
    static synchronized void forget() { kek = null; kekAt = null; }

    private static byte[] bound(String account, String host) { return (account + "\n" + host).getBytes(StandardCharsets.UTF_8); }

    /** A person's key, sealed for their account and the host: base64 of the nonce and what was sealed. */
    static String seal(Kek k, String account, String host, String secret) throws IOException {
        try {
            byte[] nonce = new byte[12];
            new SecureRandom().nextBytes(nonce);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(k.key(), "AES"), new GCMParameterSpec(128, nonce));
            c.updateAAD(bound(account, host));
            byte[] out = c.doFinal(secret.getBytes(StandardCharsets.UTF_8));
            byte[] all = new byte[nonce.length + out.length];
            System.arraycopy(nonce, 0, all, 0, nonce.length); System.arraycopy(out, 0, all, nonce.length, out.length);
            return Base64.getEncoder().encodeToString(all);
        } catch (GeneralSecurityException e) { throw new IOException("A key could not be sealed.", e); }
    }

    /** The key a sealed one is; null when this key did not seal it, or it does not open for this account and host. */
    static String open(Kek k, AccountStore.OwnKey row) {
        if (row == null || !row.kek().equals(k.id())) return null;
        try {
            byte[] all = Base64.getDecoder().decode(row.sealed());
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(k.key(), "AES"), new GCMParameterSpec(128, all, 0, 12));
            c.updateAAD(bound(row.account(), row.host()));
            return new String(c.doFinal(all, 12, all.length - 12), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) { return null; }
    }

    /** A person's key for the host, opened; null when they have none, or it cannot be opened (the service's key was lost). */
    static String keyFor(AccountStore store, String account, String host) {
        if (account == null || host == null) return null;
        try {
            AccountStore.OwnKey row = store.ownKey(account, host.toLowerCase(Locale.ROOT));
            return row == null ? null : open(kek(), row);
        } catch (IOException e) { LOG.warn("A person's own key could not be read: {}", e.getMessage()); return null; }
    }

    /** Whether a person's key for the host was sealed by a key that is not the service's now: they set it again. */
    static boolean lost(AccountStore.OwnKey row) {
        try { return open(kek(), row) == null; } catch (IOException e) { return true; }
    }

    /** A person's key kept, sealed; {@code secret} blank takes it away. */
    static void set(AccountStore store, String account, Host host, String secret) throws IOException {
        if (secret == null || secret.isBlank()) { store.removeOwnKey(account, host.host()); return; }
        Kek k = kek();
        store.setOwnKey(account, host.host(), host.kind(), seal(k, account, host.host(), secret.strip()), k.id());
    }

    /** A host a person may keep a key for: its name (a model's API by its host, a search place by its name), the kind, and what a person calls it. */
    record Host(String host, String kind, String said) { }

    /** The hosts of this service a person may keep a key for: the model's APIs on the internet its settings name, and the paid search places. */
    static List<Host> hosts() {
        Set<String> seen = new LinkedHashSet<>();
        List<Host> out = new ArrayList<>();
        for (String setting : NodeGates.MODEL_SETTINGS) {
            String v = Config.get(setting);
            if (v == null || v.isBlank()) continue;
            for (String one : v.split(",")) {
                String u = one.strip();
                if (!(u.startsWith("https://") || u.startsWith("http://"))) continue;
                if (!(DriveClient.hostedApi(u) || OpenAI.is(u) || Anthropic.is(u))) continue;   // a server of one's own has no key of a person's
                String h = URI.create(u).getHost();
                if (h != null && seen.add(h.toLowerCase(Locale.ROOT))) out.add(new Host(h.toLowerCase(Locale.ROOT), MODEL, h.toLowerCase(Locale.ROOT)));
            }
        }
        for (NodeGates.Place p : NodeGates.PLACES) out.add(new Host(p.name(), SEARCH, placeSaid(p.name())));
        return out;
    }

    static Host host(String name) { for (Host h : hosts()) if (h.host().equals(name)) return h; return null; }

    static String placeSaid(String place) {
        return switch (place) {
            case "brave" -> "Brave Search"; case "serper" -> "Serper"; case "serpapi" -> "SerpApi"; case "exa" -> "Exa"; case "tavily" -> "Tavily"; case "firecrawl" -> "Firecrawl";
            default -> place;
        };
    }

    /**
     * Who pays for a use, as it starts: the person when they keep a key that opens for the host of the model entry the use runs on,
     * else the service's operator. A use with no model (a page added) is the operator's; its searches are decided call by call.
     */
    static String payerFor(AccountStore store, String account, String entry) {
        ModelEntries.Entry e = entry == null || entry.isBlank() ? ModelEntries.defaultEntry() : ModelEntries.get(entry);
        if (e == null || e.drive() == null || !e.drive().startsWith("http")) return "operator";
        String h = URI.create(e.drive().strip()).getHost();
        return h != null && keyFor(store, account, h) != null ? "own" : "operator";
    }
}
