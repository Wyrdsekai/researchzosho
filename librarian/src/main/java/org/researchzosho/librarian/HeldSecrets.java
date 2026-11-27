package org.researchzosho.librarian;

import org.researchzosho.Config;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The secrets this library holds in plain, and a text looked at for an exact copy of one (2026-11-26). Before any text is kept as a
 * document ({@link RawCapture}), it is looked at here; a text that holds one is not kept, and the refusal names the setting (or the
 * database) the secret belongs to, never the value or a part of it.
 *
 * <p>Held in plain, and so matched: the settings of the secret kind (the model's key, the search places' keys, the services' ids and
 * secrets, the helpers' secret, the sign-in provider's secret), from the settings file or the environment; the keys kept for hosts
 * ({@code key.<host>}, the model entries' keys) and for MCP servers ({@code mcp_…}); the token of a library this machine is connected to;
 * the password in an address of the settings or of a database the owner added; and the secret this machine serves its helpers with.
 * Kept only as a hash, and so not matched: the readers' tokens, the account service's keys, sessions, invitations and access claims,
 * the phones' secrets and pairing codes. Sealed, and not matched: a person's own keys under an account service ({@link OwnKeys}).
 *
 * <p>A value shorter than {@link #LEAST} characters is not matched: shorter than that, a value can be a word, a year, a name or a number
 * that ordinary text holds. No key of a service this program speaks to is that short (the shortest, a patent office's secret, is 16).
 * Matching is by the exact value alone, never by what a key looks like.
 */
final class HeldSecrets {
    private HeldSecrets() { }

    /** The least length a value is matched at. */
    static final int LEAST = 16;

    /** A secret: its value, and the name it is known by (a setting's short name, or a database's name). */
    record Held(String value, String name, boolean database) {
        @Override public String toString() { return (database ? "the password of the database " : "the setting ") + name; }   // never the value
    }

    static final String SETTING = "This document holds the value of the setting {0}, a secret this library keeps, so it is not kept. Take the value out of the document and add it again.";
    static final String DATABASE = "This document holds the password of the database {0}, which this library keeps, so it is not kept. Take the password out of the document and add it again.";

    /** The sentence a document holding {@code h} is refused with. */
    static String said(Held h) { return h.database() ? Pages.t(DATABASE, h.name()) : Pages.t(SETTING, h.name()); }

    /** The prefix of the keys kept for hosts ({@code key.openrouter.ai}). */
    static final String HOST_KEYS = "RESEARCHZOSHO_KEY_";

    /** Every secret held in plain, once each, at least {@link #LEAST} characters long. */
    static List<Held> all() {
        Map<String, Held> out = new LinkedHashMap<>();
        for (Settings.Setting s : Settings.known()) {
            if (s.kind() == Settings.Kind.secret) add(out, Config.get(s.key()), s.shortName(), false);
            else if (s.kind() == Settings.Kind.address) add(out, password(Config.get(s.key())), s.shortName(), false);
        }
        Map<String, String> kept = new TreeMap<>();
        try { kept.putAll(Config.storedWithPrefix(HOST_KEYS)); kept.putAll(Config.storedWithPrefix(McpServers.KEY_PREFIX)); } catch (RuntimeException noFile) { }
        for (var e : kept.entrySet()) add(out, e.getValue(), shortName(e.getKey()), false);
        for (var e : System.getenv().entrySet()) {   // a key given in the environment rather than the file
            String k = e.getKey().toUpperCase(Locale.ROOT).replace("CODEZAIKU_", Config.PREFIX);
            if (k.startsWith(HOST_KEYS) || k.startsWith(McpServers.KEY_PREFIX)) add(out, e.getValue(), shortName(k), false);
        }
        add(out, Config.get(Remote.TOKEN), shortName(Remote.TOKEN), false);
        try { for (Databases.Db d : Databases.list()) add(out, password(d.url()), d.name(), true); } catch (IOException | RuntimeException unreadable) { }
        add(out, HelpersInstall.keptSecret(), "helpers_secret", false);
        return new ArrayList<>(out.values());
    }

    private static void add(Map<String, Held> out, String value, String name, boolean database) {
        if (value == null) return;
        String v = value.strip();
        if (v.length() < LEAST || out.containsKey(v)) return;
        out.put(v, new Held(v, name, database));
    }

    static String shortName(String key) { return key.replaceFirst("^RESEARCHZOSHO_", "").toLowerCase(Locale.ROOT); }

    private static final Pattern USER_PASSWORD = Pattern.compile("://[^\\s:/@]*:([^\\s@/]+)@"),
            PASSWORD_PARAMETER = Pattern.compile("(?i)[?&;](?:password|passwd|pwd)=([^&;\\s#]+)");

    /** The password an address carries (user:password@, or ?password=), or null. */
    static String password(String address) {
        if (address == null || address.isBlank()) return null;
        Matcher m = USER_PASSWORD.matcher(address);
        if (m.find()) return m.group(1);
        m = PASSWORD_PARAMETER.matcher(address);
        return m.find() ? m.group(1) : null;
    }

    /** The first secret held whose exact value one of the texts holds, or null. */
    static Held in(String... texts) {
        List<Held> all = all();
        if (all.isEmpty()) return null;
        for (String t : texts) {
            if (t == null || t.length() < LEAST) continue;
            for (Held h : all) if (t.contains(h.value())) return h;
        }
        return null;
    }
}
