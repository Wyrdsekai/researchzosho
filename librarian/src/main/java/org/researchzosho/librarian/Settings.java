package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.researchzosho.Config;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

/**
 * The settings a library has, as one list the command line, the web page and the chat all read from (0.5.5): each with its kind, what it is
 * for and an example. A secret is never shown back, only whether it is set and how it ends. Every setting lives in the library's config
 * file ({@link Config}); the service reads the file live, so a change takes effect without a restart, except where a note says otherwise.
 */
public final class Settings {
    private Settings() { }

    public enum Kind { text, secret, address, number, choice }

    /** One setting: its key, its kind, what it does in a sentence, an example value, and for a choice the values allowed. */
    public record Setting(String key, Kind kind, String what, String example, List<String> choices) {
        Setting(String key, Kind kind, String what, String example) { this(key, kind, what, example, List.of()); }
        /** The key without the common prefix, lower-case: {@code brave_key}. */
        public String shortName() { return key.replaceFirst("^RESEARCHZOSHO_", "").toLowerCase(Locale.ROOT); }
    }

    static final List<Setting> KNOWN = List.of(
            // the model
            new Setting("RESEARCHZOSHO_DRIVE", Kind.address, "The model server's address (OpenAI-style). Research runs and the chat use it.", "http://localhost:8080"),
            new Setting("RESEARCHZOSHO_MODEL", Kind.text, "The model's name on that server, when the server holds more than one.", "qwen3.8-27b"),
            new Setting("RESEARCHZOSHO_API_KEY", Kind.secret, "The key the model server wants, when it wants one.", ""),
            new Setting("RESEARCHZOSHO_JUDGE_DRIVE", Kind.address, "A second model server for the checks (the judge); the main one when unset.", "http://localhost:8081"),
            new Setting("RESEARCHZOSHO_JUDGE_MODEL", Kind.text, "The judge model's name on that server.", ""),
            new Setting("RESEARCHZOSHO_EMBED", Kind.address, "The embeddings server, for search by meaning; off when unset.", "http://localhost:30001"),
            new Setting("RESEARCHZOSHO_VISION_DRIVE", Kind.address, "A model server that reads pictures; the main one when unset.", ""),
            new Setting("RESEARCHZOSHO_VISION_MODEL", Kind.text, "The picture-reading model's name.", ""),
            // web search
            new Setting("RESEARCHZOSHO_BRAVE_KEY", Kind.secret, "A Brave Search API key (brave.com/search/api); used first for web search.", ""),
            new Setting("RESEARCHZOSHO_SEARXNG", Kind.address, "A SearXNG address; the fallback behind Brave, or the web search when there is no key.", "http://localhost:8888"),
            new Setting("RESEARCHZOSHO_FALLBACK_SEARCH", Kind.choice, "The built-in fallback search (Wikipedia and papers) when nothing else answers.", "on", List.of("on", "off")),
            // the archives and the old sources
            new Setting("RESEARCHZOSHO_TROVE_KEY", Kind.secret, "A Trove API key, for Australian newspapers in old_newspapers.", ""),
            new Setting("RESEARCHZOSHO_DIGITALNZ_KEY", Kind.secret, "A DigitalNZ key, for a higher rate on Papers Past (works without one).", ""),
            new Setting("RESEARCHZOSHO_EUROPEANA_KEY", Kind.secret, "A Europeana key, for heritage items in archive_search.", ""),
            new Setting("RESEARCHZOSHO_DPLA_KEY", Kind.secret, "A DPLA key, for heritage items in archive_search.", ""),
            // podcasts and transcription
            new Setting("RESEARCHZOSHO_PODCASTINDEX_KEY", Kind.secret, "A Podcast Index API key (api.podcastindex.org/signup, free): the open podcast directory and search by person.", ""),
            new Setting("RESEARCHZOSHO_PODCASTINDEX_SECRET", Kind.secret, "The secret that came with the Podcast Index key; both are needed.", ""),
            new Setting("RESEARCHZOSHO_PODCASTINDEX_LOCAL", Kind.choice, "Keep the Podcast Index's weekly file on this machine (researchzosho podcast index download fetches it; on: the housekeeping fetches each new week's).", "off", List.of("on", "off")),
            new Setting("RESEARCHZOSHO_WHISPER", Kind.address, "A transcription server (an OpenAI-style /v1/audio/transcriptions, such as speaches or faster-whisper-server); the local container when unset.", "http://gpu-box:18890"),
            new Setting("RESEARCHZOSHO_WHISPER_MODEL", Kind.text, "The transcription model on that server.", "Systran/faster-whisper-small"),
            new Setting("RESEARCHZOSHO_TRANSCRIBE_EPISODES", Kind.number, "How many podcast episodes one research run may transcribe (2 unless set).", "2"),
            new Setting("RESEARCHZOSHO_TRANSCRIBE_MINUTES", Kind.number, "How many minutes of audio one research run may transcribe (120 unless set).", "120"),
            new Setting("RESEARCHZOSHO_TRANSCRIBE_FOLLOWED_MINUTES", Kind.number, "Minutes of audio the housekeeping may transcribe per night for the podcasts you follow (0 = no cap).", "0"),
            // runs and the service
            new Setting("RESEARCHZOSHO_JOB_WORKERS", Kind.number, "How many research runs the service runs at once (1 unless set). Takes effect at the next restart.", "1"),
            new Setting("RESEARCHZOSHO_STALL_MINUTES", Kind.number, "After how many minutes without progress a run is marked as stalled (15 unless set).", "15"),
            new Setting("RESEARCHZOSHO_SUBMIT_CHECK_SECONDS", Kind.number, "How long a program's submit waits for the model's reading of a question before filing anyway (45 unless set).", "45"),
            new Setting("RESEARCHZOSHO_UPDATE", Kind.choice, "Updates: check and say, update by itself, or neither.", "check", List.of("check", "auto", "off")),
            new Setting("RESEARCHZOSHO_RECORDS", Kind.choice, "The record sources (registers, newspapers, censuses) in every run, or only when a field asks.", "field", List.of("always", "field", "off")),
            new Setting("RESEARCHZOSHO_FETCH_PRIVATE", Kind.choice, "Whether a run may fetch addresses on your own network.", "allow", List.of("allow", "deny")),
            new Setting("RESEARCHZOSHO_FETCH_MAX_BYTES", Kind.number, "The largest document a run downloads, in bytes.", "20000000"),
            new Setting("RESEARCHZOSHO_FETCH_MIN_BYTES_PER_SECOND", Kind.number, "The slowest a page may come before the fetch is given up.", "2000"),
            new Setting("RESEARCHZOSHO_EXPLORER_PER_NIGHT", Kind.number, "How many open questions the housekeeping researches per night.", "2"),
            new Setting("RESEARCHZOSHO_EXPLORER_TYPES", Kind.text, "Which kinds of open question the housekeeping takes.", ""));

    public static List<Setting> known() { return KNOWN; }

    /** The setting a name means: the key, the key without its prefix, upper or lower case, dots or underscores; null when there is none. */
    public static Setting named(String name) {
        if (name == null) return null;
        String n = name.strip().replace('.', '_').replace('-', '_').toUpperCase(Locale.ROOT);
        if (!n.startsWith("RESEARCHZOSHO_")) n = "RESEARCHZOSHO_" + n;
        for (Setting s : KNOWN) if (s.key().equals(n)) return s;
        return null;
    }

    /** A value as it may be shown: a secret only as set or not, and its last three characters. */
    public static String shown(Setting s, String value) {
        if (value == null || value.isBlank()) return "";
        if (s.kind() != Kind.secret) return value;
        return "set (ends with …" + value.substring(Math.max(0, value.length() - 3)) + ")";
    }

    /** Why a value cannot be this setting's, in a sentence; "" when it can. */
    public static String refuse(Setting s, String value) {
        if (value == null || value.isBlank()) return "";
        String v = value.strip();
        return switch (s.kind()) {
            case address -> v.matches("^https?://\\S+$") ? "" : s.shortName() + " is an address and must start with http:// or https://, for example " + s.example() + ".";
            case number -> v.matches("^\\d+$") ? "" : s.shortName() + " is a number, for example " + s.example() + ".";
            case choice -> s.choices().contains(v.toLowerCase(Locale.ROOT)) ? "" : s.shortName() + " is one of " + String.join(", ", s.choices()) + ".";
            default -> v.contains("\n") ? s.shortName() + " is one line." : "";
        };
    }

    /** Every setting with its value as it may be shown, in the order above. */
    public static ArrayNode list(ObjectMapper m) {
        ArrayNode out = m.createArrayNode();
        for (Setting s : KNOWN) {
            String v = Config.get(s.key());
            ObjectNode o = out.addObject();
            o.put("key", s.key()); o.put("name", s.shortName()); o.put("kind", s.kind().name()); o.put("what", s.what());
            o.put("set", v != null && !v.isBlank());
            o.put("value", shown(s, v));
            if (!s.example().isEmpty()) o.put("example", s.example());
            if (!s.choices().isEmpty()) { ArrayNode c = o.putArray("choices"); for (String x : s.choices()) c.add(x); }
        }
        return out;
    }

    /** Writes the value (a blank value unsets it); the value must pass {@link #refuse}. */
    public static void set(Setting s, String value) throws IOException {
        String v = value == null ? "" : value.strip();
        if (s.kind() == Kind.choice) v = v.toLowerCase(Locale.ROOT);
        Config.set(s.key(), v);
    }
}
