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
            new Setting("RESEARCHZOSHO_API_KEY", Kind.secret, "The key the model server wants, when it wants one (Anthropic, OpenRouter, OpenAI…).", ""),
            new Setting("RESEARCHZOSHO_ANTHROPIC_EFFORT", Kind.choice, "With drive = https://api.anthropic.com: how hard Claude thinks on each call. Unset: the model's own default.", "", List.of("low", "medium", "high", "xhigh", "max")),
            new Setting("RESEARCHZOSHO_OPENAI_EFFORT", Kind.choice, "With drive = https://api.openai.com: how hard the model reasons on each call (each model takes some of these). Unset: the model's own default.", "", List.of("none", "minimal", "low", "medium", "high", "xhigh", "max")),
            // best judgement
            new Setting("RESEARCHZOSHO_AUTO", Kind.choice, "Whether the library chooses the model itself for a run that names none, once two models are saved (researchzosho models auto says what it would pick and why). Unset: on.", "", List.of("on", "off")),
            new Setting("RESEARCHZOSHO_AUTO_WAIT_MINUTES", Kind.number, "When somebody is waiting (a quick ask, a chat turn), the minutes best judgement may let a run take; a slower model is passed over. Unset: 5.", "5"),
            new Setting("RESEARCHZOSHO_AUTO_MAX_DOLLARS", Kind.number, "The most best judgement may let one run be estimated to cost, in dollars. Unset: no limit.", "2"),
            new Setting("RESEARCHZOSHO_AUTO_LOCAL_ONLY", Kind.choice, "Keep every run best judgement chooses on a local model, never a hosted one. Unset: off.", "", List.of("on", "off")),
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
            new Setting("RESEARCHZOSHO_YAHOO_JP_CLIENT_ID", Kind.secret, "A Yahoo! JAPAN Client ID (developer.yahoo.co.jp, free): what Yahoo!ショッピング lists, with prices, for a shopping question in Japan.", ""),
            new Setting("RESEARCHZOSHO_RAKUTEN_APP_ID", Kind.secret, "A Rakuten Web Service application ID (webservice.rakuten.co.jp, free): what 楽天市場 lists, with prices; needs the access key too.", ""),
            new Setting("RESEARCHZOSHO_RAKUTEN_ACCESS_KEY", Kind.secret, "The access key that comes with the Rakuten application ID.", ""),
            new Setting("RESEARCHZOSHO_ICECAT_USER", Kind.text, "An Open Icecat account name (icecat.biz, free): a product's datasheet by brand and model, or by barcode.", ""),
            new Setting("RESEARCHZOSHO_REDDIT_CLIENT_ID", Kind.secret, "A Reddit app of your own (reddit.com/prefs/apps, type script, free): what owners wrote about a product; needs the secret too.", ""),
            new Setting("RESEARCHZOSHO_REDDIT_CLIENT_SECRET", Kind.secret, "The secret of that Reddit app.", ""),
            new Setting("RESEARCHZOSHO_EBAY_CLIENT_ID", Kind.secret, "An eBay developer keyset's client id (developer.ebay.com, free; production access by a short application): eBay listings and auctions in product_search kind=marketplaces.", ""),
            new Setting("RESEARCHZOSHO_EBAY_CLIENT_SECRET", Kind.secret, "The client secret of that keyset; both are needed.", ""),
            new Setting("RESEARCHZOSHO_ETSY_KEY", Kind.secret, "An Etsy app keystring (etsy.com/developers, a personal app): Etsy listings, the handmade and vintage tail.", ""),
            new Setting("RESEARCHZOSHO_CINII_APPID", Kind.secret, "A CiNii appid (NII's developer registration, free): CiNii Books in book_search for Japanese books by 件名 (subject heading), author and words, each with how many Japanese libraries hold it.", ""),
            new Setting("RESEARCHZOSHO_RECALLS", Kind.choice, "The recall watch: the housekeeping fetches the open product-safety feeds (US CPSC and FDA, EU Safety Gate, UK OPSS, the OECD portal) and matches them against what you own; a notice that may concern something you own waits in the inbox. on by default.", "on", List.of("on", "off")),
            new Setting("RESEARCHZOSHO_MARKET", Kind.text, "The country you buy in, as two letters (JP, US, GB, DE…): the shops, the currency, the recall notices and the reviewers a shopping run uses. Set at setup from this machine's country; the chat can switch it for one conversation.", "JP"),
            new Setting("RESEARCHZOSHO_CALIBRE", Kind.text, "Your Calibre library: its folder, or its metadata.db. Read only, never changed. What you rated there says what you like, and a book already on the shelf is not recommended again; the my_shelf tool and `researchzosho myshelf` read it.", "~/Calibre Library"),
            new Setting("RESEARCHZOSHO_DNA_MINUTES", Kind.number, "Minutes a night the housekeeping spends reading the books on your shelf for their DNA (the model labels about one book a minute; the books you went for come first; 20 unless set, 0 turns it off).", "20"),
            new Setting("RESEARCHZOSHO_DNA_SHARE", Kind.choice, "Contribute your books' DNA to the shared pool: on writes a pack of the labelled documents (the books' layers, nothing of you) to ~/.researchzosho/dna/outbox once a week for you to send. off unless set.", "off", List.of("on", "off")),
            new Setting("RESEARCHZOSHO_DNA_CONTRIBUTOR", Kind.text, "The name a contributed pack carries; anonymous when unset.", "a reader"),
            new Setting("RESEARCHZOSHO_WHISPER", Kind.address, "A transcription server (an OpenAI-style /v1/audio/transcriptions, such as speaches or faster-whisper-server); the local container when unset.", "http://gpu-box:18890"),
            new Setting("RESEARCHZOSHO_WHISPER_MODEL", Kind.text, "The transcription model on that server.", "Systran/faster-whisper-small"),
            new Setting("RESEARCHZOSHO_TRANSCRIBE_EPISODES", Kind.number, "How many podcast episodes one research run may transcribe (2 unless set).", "2"),
            new Setting("RESEARCHZOSHO_TRANSCRIBE_MINUTES", Kind.number, "How many minutes of audio one research run may transcribe (120 unless set).", "120"),
            new Setting("RESEARCHZOSHO_TRANSCRIBE_FOLLOWED_MINUTES", Kind.number, "Minutes of audio the housekeeping may transcribe per night for the podcasts you follow (0 = no cap).", "0"),
            // runs and the service
            new Setting("RESEARCHZOSHO_JOB_DRIVES", Kind.address, "The model servers the research runs use when there are several, comma-separated (OpenAI-style addresses); the drive alone when unset. Each carries one run per three of its slots, and a server with more slots takes more of the runs. How many runs go at once follows at the next restart; where a run starts follows at once.", "http://127.0.0.1:8080,http://192.168.1.20:8080"),
            new Setting("RESEARCHZOSHO_JOB_WORKERS", Kind.number, "How many research runs the service runs at once. Unset: one run per three of each model server's slots, added up over job.drives (a llama.cpp server says how many; Ollama is taken as one, vLLM as eight). Takes effect at the next restart.", "1"),
            new Setting("RESEARCHZOSHO_RESEARCH_WORKERS", Kind.number, "How many readers one research run uses at once. Unset: three, or the model server's slots when fewer. A running run follows at its next turn.", "3"),
            new Setting("RESEARCHZOSHO_STALL_MINUTES", Kind.number, "After how many minutes without progress a run is marked as stalled (15 unless set).", "15"),
            new Setting("RESEARCHZOSHO_SUBMIT_CHECK_SECONDS", Kind.number, "How long a program's submit waits for the model's reading of a question before filing anyway (45 unless set).", "45"),
            new Setting("RESEARCHZOSHO_UPDATE", Kind.choice, "Updates: check and say, update by itself, or neither.", "check", List.of("check", "auto", "off")),
            new Setting("RESEARCHZOSHO_WEB_SIGNIN", Kind.choice, "Whether the pages ask for a sign-in. Off as shipped: anyone who can open them is the person who keeps the library. researchzosho share turns it on.", "off", List.of("on", "off")),
            new Setting("RESEARCHZOSHO_WEB_LOCAL", Kind.choice, "With the sign-in on, a browser on the library's own computer is the owner without signing in (owner), or signs in like any other (signin), for a computer other people use.", "owner", List.of("owner", "signin")),
            new Setting("RESEARCHZOSHO_SHARE", Kind.choice, "Reaching the library from outside your home: off, on your tailnet, public through Tailscale Funnel, or a Cloudflare quick tunnel. researchzosho share sets it; the service follows within half a minute.", "off", List.of("off", "tailscale", "funnel", "cloudflare")),
            new Setting("RESEARCHZOSHO_SHARE_ADDRESS", Kind.address, "Where the library is reached from outside, as the relay reported it. Join links are written with it. A quick tunnel's address changes at every start.", "https://mylibrary.tail1234.ts.net"),
            new Setting("RESEARCHZOSHO_SHARE_MAIL", Kind.text, "The quick tunnel's mail list: only these addresses (or *@a-domain) get through, with a one-time code from Cloudflare. Empty: anyone with the address reaches the sign-in page.", "ann@example.com,*@example.org"),
            new Setting("RESEARCHZOSHO_DOOR", Kind.number, "The shared door's port on this computer, which only the relay points at.", "4651"),
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
            case address -> {
                boolean several = s.key().equals(JOB_DRIVES);
                for (String part : several ? v.split("\\s*,\\s*") : new String[]{v})
                    if (!part.matches("^https?://\\S+$")) yield s.shortName() + " is an address" + (several ? ", or several with commas between," : "") + " and must start with http:// or https://, for example " + s.example() + ".";
                yield "";
            }
            case number -> v.matches("^\\d+$") ? "" : s.shortName() + " is a number, for example " + s.example() + ".";
            case choice -> s.choices().contains(v.toLowerCase(Locale.ROOT)) ? "" : s.shortName() + " is one of " + String.join(", ", s.choices()) + ".";
            default -> s.key().equals(MARKET) ? (v.matches("(?i)^[a-z]{2}$") ? "" : "market is a country as two letters, for example JP or US.")
                     : v.contains("\n") ? s.shortName() + " is one line." : "";
        };
    }

    public static final String MARKET = "RESEARCHZOSHO_MARKET";
    public static final String JOB_DRIVES = "RESEARCHZOSHO_JOB_DRIVES";
    public static final String CALIBRE = "RESEARCHZOSHO_CALIBRE";

    /** The country the person buys in, as two upper-case letters: the setting, else this machine's country, else US. */
    public static String market() {
        String m = Config.get(MARKET);
        if (m != null && m.strip().matches("(?i)^[a-z]{2}$")) return m.strip().toUpperCase(Locale.ROOT);
        String c = Locale.getDefault().getCountry();
        return c != null && c.matches("^[A-Z]{2}$") ? c : "US";
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
        if (s.key().equals(MARKET)) v = v.toUpperCase(Locale.ROOT);
        Config.set(s.key(), v);
    }
}
