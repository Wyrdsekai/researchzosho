package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.researchzosho.Config;
import org.researchzosho.drive.DriveClient;
import org.researchzosho.drive.anthropic.Anthropic;
import org.researchzosho.drive.openai.OpenAI;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * The models a library can run on, by name (0.6.2): each a host address, a model id, prices per million tokens and an effort, chosen per
 * research run, per chat session and per ask — "DeepSeek through OpenRouter for this question, the local 27B for that one, Claude for
 * the careful one". Keys are kept once per host ({@code key.<host>}), so a key goes to its own host and nowhere else; the current
 * {@code drive} and {@code model} settings are the entry called {@code default}, so a library with no entries added works as before.
 * Settings: {@code entry.<name>.drive}, {@code .model}, {@code .prices} ("in/out/cache" dollars per million), {@code .effort}, {@code .cap}
 * (dollars a month), {@code .note}.
 */
public final class ModelEntries {

    private ModelEntries() { }

    public static final String DEFAULT = "default";
    static final String PREFIX = "RESEARCHZOSHO_ENTRY_";

    /**
     * One named model: where, which, what it costs. {@code prices} are dollars per million tokens — in, out, cache read — or null when
     * unknown. {@code sees}: whether it reads pictures as the probe found ({@link Vision}); null until probed.
     */
    public record Entry(String name, String drive, String model, double[] prices, String effort, double cap, String note, String tierSet, Boolean sees) {
        public Entry(String name, String drive, String model, double[] prices, String effort, double cap, String note) { this(name, drive, model, prices, effort, cap, note, null, null); }
        public Entry(String name, String drive, String model, double[] prices, String effort, double cap, String note, String tierSet) { this(name, drive, model, prices, effort, cap, note, tierSet, null); }
        /** "sees images", "does not see images" or "not probed for pictures". */
        public String seesText() { return Vision.Probe.word(sees); }
        public Entry withSees(Boolean s) { return new Entry(name, drive, model, prices, effort, cap, note, tierSet, s); }
        /** The model's tier for best judgement: set by hand, else the measured default for its family, else C. */
        public String tier() { return tierSet != null && !tierSet.isBlank() ? tierSet.toUpperCase(Locale.ROOT) : ModelTiers.of(model); }
        public String host() { String h = DriveClient.hostOf(drive); return h == null ? (drive == null ? "" : drive) : h; }
        public boolean hosted() { return DriveClient.hostedApi(drive) || Anthropic.is(drive) || OpenAI.is(drive); }
        /** What tokens cost on this entry, in dollars; 0 when no prices are known. */
        public double cost(long promptTokens, long cachedTokens, long completionTokens) {
            if (prices == null) return 0;
            long uncached = Math.max(0, promptTokens - cachedTokens);
            return (uncached * prices[0] + cachedTokens * (prices.length > 2 ? prices[2] : prices[0]) + completionTokens * prices[1]) / 1e6;
        }
        public String pricesText() { return prices == null ? "" : trim(prices[0]) + "/" + trim(prices[1]) + (prices.length > 2 ? "/" + trim(prices[2]) : ""); }
        private static String trim(double d) { String s = String.format(Locale.ROOT, "%.4f", d); s = s.replaceAll("0+$", "").replaceAll("\\.$", ""); return s.isEmpty() ? "0" : s; }
        /** A line a person reads: "deepseek: deepseek/deepseek-v4.1-flash via openrouter.ai — $0.024/0.72 per M". */
        public String line() {
            return name + ": " + model + " via " + host() + (prices == null ? "" : " — $" + pricesText() + " per M") + (effort == null || effort.isBlank() ? "" : ", effort " + effort)
                    + (cap > 0 ? ", cap $" + trim(cap) + " a month" : "") + ", tier " + tier() + (note == null || note.isBlank() ? "" : " (" + note + ")");
        }
    }

    /** A name as the settings keep it: lower case, letters, digits and hyphens. */
    public static String nameOk(String name) {
        if (name == null) return "An entry needs a name.";
        String n = name.strip().toLowerCase(Locale.ROOT);
        if (!n.matches("[a-z0-9][a-z0-9-]{0,39}")) return "An entry's name is letters, digits and hyphens, up to forty: deepseek, local-27b, fable.";
        return "";
    }

    static String key(String name, String field) { return PREFIX + name.strip().toLowerCase(Locale.ROOT).replace('-', '_').toUpperCase(Locale.ROOT) + "_" + field.toUpperCase(Locale.ROOT); }

    /** The entries the settings keep, by name, the {@code default} one first (the drive and model settings as they stand). */
    public static Map<String, Entry> all() {
        Map<String, Entry> out = new LinkedHashMap<>();
        out.put(DEFAULT, defaultEntry());
        TreeSet<String> names = new TreeSet<>();
        for (String k : Config.storedWithPrefix(PREFIX).keySet()) {
            String rest = k.substring(PREFIX.length());
            int cut = rest.lastIndexOf('_');
            if (cut <= 0) continue;
            String field = rest.substring(cut + 1);
            if (!List.of("DRIVE", "MODEL", "PRICES", "EFFORT", "CAP", "NOTE", "TIER", "SEES").contains(field)) continue;
            names.add(rest.substring(0, cut).toLowerCase(Locale.ROOT).replace('_', '-'));
        }
        for (String n : names) { if (n.equals(DEFAULT)) continue; Entry e = get(n); if (e != null) out.put(n, e); }
        return out;
    }

    /** The entry called {@code default}: the drive and model settings, with the prices and cap kept under the name default when any are. */
    public static Entry defaultEntry() {
        String drive = Config.get("RESEARCHZOSHO_DRIVE", "http://localhost:8080"), model = Config.get("RESEARCHZOSHO_MODEL", "local-model");
        return new Entry(DEFAULT, drive, model, prices(Config.get(key(DEFAULT, "PRICES"))), effortOf(drive, Config.get(key(DEFAULT, "EFFORT"))), capOf(Config.get(key(DEFAULT, "CAP"))), Config.get(key(DEFAULT, "NOTE"), ""), Config.get(key(DEFAULT, "TIER")), seesOf(Config.get(SEES)));
    }

    /** The setting that says whether the default entry sees pictures; a named entry's is {@code entry.<name>.sees}. */
    public static final String SEES = "RESEARCHZOSHO_SEES";

    /** "yes" → true, "no" → false, anything else → null (not probed). */
    static Boolean seesOf(String s) {
        if (s == null) return null;
        String v = s.strip().toLowerCase(Locale.ROOT);
        return v.equals("yes") || v.equals("true") || v.equals("on") ? Boolean.TRUE : v.equals("no") || v.equals("false") || v.equals("off") ? Boolean.FALSE : null;
    }

    /** Writes what the probe found on an entry: yes, no, or blank to forget it. */
    public static void sees(String name, Boolean sees) throws IOException {
        String n = name == null || name.isBlank() ? DEFAULT : name.strip().toLowerCase(Locale.ROOT);
        Config.set(n.equals(DEFAULT) ? SEES : key(n, "SEES"), sees == null ? "" : sees ? "yes" : "no");
    }

    /**
     * The entry pictures go to: the server the person set for pictures (RESEARCHZOSHO_VISION_DRIVE, taken at its word); else the default
     * when it sees; else the local entries that see, the best tier first; else a hosted entry that sees and has its key; else null.
     */
    public static Entry seeing() {
        String vd = Config.get("RESEARCHZOSHO_VISION_DRIVE");
        if (vd != null && !vd.isBlank()) {
            Entry d = defaultEntry();
            String vm = Config.get("RESEARCHZOSHO_VISION_MODEL", d.model());
            for (Entry e : all().values()) if (e.drive().equals(Config.driveBase(vd)) && e.model().equals(vm)) return e.sees() == null ? e.withSees(true) : e;
            return new Entry("vision", Config.driveBase(vd), vm, null, "", 0, "the server set for pictures", null, true);
        }
        Entry d = defaultEntry();
        if (Boolean.TRUE.equals(d.sees())) return d;
        Entry best = null;
        for (Entry e : all().values()) if (!e.name().equals(DEFAULT) && !e.hosted() && Boolean.TRUE.equals(e.sees()) && (best == null || e.tier().compareTo(best.tier()) < 0)) best = e;
        if (best != null) return best;
        for (Entry e : all().values()) if (!e.name().equals(DEFAULT) && e.hosted() && Boolean.TRUE.equals(e.sees()) && keyKept(e) && (best == null || e.tier().compareTo(best.tier()) < 0)) best = e;
        return best;
    }

    /** No model sees: what the library has, which entries might see but were not probed, and the lines that add one. */
    public static String noEyes() {
        List<String> have = new ArrayList<>(), unprobed = new ArrayList<>();
        for (Entry e : all().values()) {
            have.add(e.name() + " (" + e.model() + ", " + e.seesText() + (e.hosted() && !keyKept(e) ? ", no key kept" : "") + ")");
            if (e.sees() == null) unprobed.add(e.name());
        }
        StringBuilder b = new StringBuilder("No model that sees pictures is set up. The library has: ").append(String.join(", ", have)).append(".");
        if (!unprobed.isEmpty()) b.append(" Not yet probed for pictures: ").append(String.join(", ", unprobed)).append(" — researchzosho models test <name> asks each with a small picture (a hosted entry is one small paid call).");
        b.append(" To add one that sees: a local model with its picture file, which researchzosho model install sets up (qwen3.8-27b, gemma-4-e4b and gemma-4-e2b come with theirs; Gemma 4 at every size reads pictures), or a server of your own started with the model's --mmproj file, named with researchzosho models add <name> <address> <model id>;")
         .append(" a hosted one: researchzosho models add vision openrouter deepseek/deepseek-v4-flash-vision-exp, then researchzosho models key openrouter <key>; or set RESEARCHZOSHO_VISION_DRIVE and RESEARCHZOSHO_VISION_MODEL to a server that reads pictures.");
        return b.toString();
    }

    /**
     * The entry a person's words name, loosely: the name, the model id or its last part, letters and digits only and case aside
     * ("Kimi K3" is kimi, whose model is moonshotai/kimi-k3; "DeepSeek V4.1 Flash" is the entry whose model is deepseek/deepseek-v4.1-flash);
     * null when none comes close. Where two entries come equally close (two entries serving the same model), the first listed.
     */
    public static Entry resolve(String words) {
        List<Entry> all = resolveAll(words);
        return all.isEmpty() ? null : all.get(0);
    }

    /**
     * Every entry the words come closest to, the default first: one when the words name an entry, several when they fit more than one
     * equally well ("qwen" when two entries serve qwen3.8-27b), none when nothing comes close. A turn that changes something asks which
     * when there are several.
     */
    public static List<Entry> resolveAll(String words) {
        List<Entry> out = new ArrayList<>();
        if (words == null) return out;
        String w = squash(words);
        if (w.isEmpty()) return out;
        int score = 0;
        for (Entry e : all().values()) {
            String n = squash(e.name()), m = squash(e.model()), tail = squash(e.model().substring(e.model().lastIndexOf('/') + 1));
            int s = n.equals(w) ? 5 : tail.equals(w) || m.equals(w) ? 4
                    : !n.isEmpty() && (w.startsWith(n) || n.startsWith(w)) && Math.min(n.length(), w.length()) >= 3 ? 3
                    : !tail.isEmpty() && (tail.contains(w) || w.contains(tail)) && Math.min(tail.length(), w.length()) >= 4 ? 2 : 0;
            if (s > score) { score = s; out.clear(); out.add(e); }
            else if (s == score && s > 0) out.add(e);
        }
        return out;
    }

    /** The entries that run on a host, by the word a person uses for it: the host itself (homebox, openrouter.ai) or its first label (openrouter). */
    public static List<Entry> onHost(String word) {
        List<Entry> out = new ArrayList<>();
        String w = word == null ? "" : word.strip().toLowerCase(Locale.ROOT);
        if (w.isEmpty()) return out;
        for (Entry e : all().values()) { String h = e.host().toLowerCase(Locale.ROOT); if (h.equals(w) || h.startsWith(w + ".") || hostWord(h).equals(w)) out.add(e); }
        return out;
    }

    /** The entry serving at an address: the default when it is the default's address, else the first named one there; null when none. */
    public static Entry onDrive(String drive) {
        if (drive == null || drive.isBlank()) return null;
        String d = Config.driveBase(drive.strip());
        for (Entry e : all().values()) if (e.drive().equals(d)) return e;
        return null;
    }

    /** Whether the entry is the default: the entry named default, or one whose address and model are the default's. */
    public static boolean isDefault(Entry e) {
        if (e == null) return false;
        if (e.name().equals(DEFAULT)) return true;
        Entry d = defaultEntry();
        return e.drive().equals(d.drive()) && e.model().equals(d.model());
    }

    /** Where an entry runs, as a person reads it: "qwen3.8-27b on homebox at http://homebox:8212". */
    public static String where(Entry e) { return e.model() + " on " + e.host() + " at " + e.drive(); }

    /** The entry's key in one word for the protocol: {@code kept}, {@code missing}, or {@code not needed} for a local server. */
    public static String keyWord(Entry e) { return !e.hosted() ? "not needed" : keyKept(e) ? "kept" : "missing"; }

    /** The entry's key as a person reads it: "a local server, no key needed", "a key for openrouter.ai is kept", "no key is kept for openrouter.ai". */
    public static String keyText(Entry e) { return !e.hosted() ? "a local server, no key needed" : keyKept(e) ? "a key for " + e.host() + " is kept" : "no key is kept for " + e.host(); }

    /**
     * Makes an entry the default: the drive and model settings become its address and model, the one operation behind
     * {@code researchzosho model use <address> <model>}, the Settings page and the chat. The service takes it for its next run and its
     * next question; a run going now finishes on the model it started with.
     */
    public static void useAsDefault(Entry e) throws IOException { put(DEFAULT, e.drive(), e.model(), null, null, null, null); }

    /** The default as the protocol states it: its name, model, host and address. */
    public static ObjectNode defaultNode(ObjectMapper m) {
        Entry d = defaultEntry();
        ObjectNode o = m.createObjectNode(); o.put("name", DEFAULT); o.put("model", d.model()); o.put("host", d.host()); o.put("drive", d.drive());
        return o;
    }

    /**
     * The models in one sentence that cannot be misread: "The default is qwen3.8-27b on homebox at http://homebox:8212; runs and chats
     * that name no model use it. 5 entries; deepseek and kimi run on openrouter.ai with a key kept; 27b-gpu1 and gemma26 need none and have
     * none kept."
     */
    public static String summary() {
        Map<String, Entry> all = all();
        Entry d = all.get(DEFAULT);
        StringBuilder b = new StringBuilder("The default is ").append(where(d)).append("; runs and chats that name no model use it. ").append(all.size()).append(all.size() == 1 ? " entry" : " entries");
        Map<String, List<String>> keyed = new LinkedHashMap<>(), keyless = new LinkedHashMap<>(); List<String> local = new ArrayList<>();
        for (Entry e : all.values()) {
            if (e.name().equals(DEFAULT)) continue;
            if (!e.hosted()) local.add(e.name()); else (keyKept(e) ? keyed : keyless).computeIfAbsent(e.host(), k -> new ArrayList<>()).add(e.name());
        }
        for (var h : keyed.entrySet()) b.append("; ").append(names(h.getValue())).append(h.getValue().size() == 1 ? " runs on " : " run on ").append(h.getKey()).append(" with a key kept");
        for (var h : keyless.entrySet()) b.append("; ").append(names(h.getValue())).append(h.getValue().size() == 1 ? " runs on " : " run on ").append(h.getKey()).append(" with no key kept");
        if (!local.isEmpty()) b.append("; ").append(names(local)).append(local.size() == 1 ? " needs none and has" : " need none and have").append(" none kept");
        return b.append(".").toString();
    }

    /** "a", "a and b", "a, b and c". */
    static String names(List<String> ns) {
        if (ns.size() <= 1) return String.join("", ns);
        return String.join(", ", ns.subList(0, ns.size() - 1)) + " and " + ns.get(ns.size() - 1);
    }

    /** Whether the entry can run now: a local one always; a hosted one when its host's key is kept (the default's through RESEARCHZOSHO_API_KEY). */
    public static boolean keyKept(Entry e) {
        if (e == null || !e.hosted()) return true;
        if (DriveClient.hostKey(e.host()) != null) return true;
        return e.name().equals(DEFAULT) && Config.get("RESEARCHZOSHO_API_KEY") != null;
    }

    /** The word the key verb takes for a host (openrouter.ai → openrouter), or the host itself when it has none. */
    public static String hostWord(String host) {
        String h = host == null ? "" : host.toLowerCase(Locale.ROOT);
        if (h.contains("openrouter")) return "openrouter";
        if (h.contains("anthropic")) return "anthropic";
        if (h.contains("openai")) return "openai";
        if (h.contains("googleapis") || h.equals("gemini")) return "gemini";
        if (h.contains("nebius")) return "nebius";
        if (h.contains("huggingface")) return "huggingface";
        return h;
    }

    /** Where a host's key comes from. */
    public static String keySource(String host) {
        return switch (hostWord(host)) {
            case "openrouter" -> "openrouter.ai/keys"; case "anthropic" -> "console.anthropic.com"; case "openai" -> "platform.openai.com/api-keys";
            case "gemini" -> "aistudio.google.com/apikey"; case "nebius" -> "tokenfactory.nebius.com"; case "huggingface" -> "huggingface.co/settings/tokens";
            default -> "the host's own console";
        };
    }

    /** The line that keeps a host's key, with where the key comes from and the page that does the same. */
    public static String keyLine(String host) {
        return "researchzosho models key " + hostWord(host) + " <key> (a key from " + keySource(host) + "; --file <path> reads it from a file), or on the Settings page under Models";
    }

    /** The models that can run now, in one sentence: the local entries, the hosted ones whose key is kept, and the hosted ones still without one. */
    public static String canRunNow() {
        List<String> local = new ArrayList<>(), keyed = new ArrayList<>(), keyless = new ArrayList<>();
        for (Entry e : all().values()) {
            String said = e.name() + " (" + e.model() + ")";
            if (!e.hosted()) local.add(said); else if (keyKept(e)) keyed.add(said); else keyless.add(e.name() + " (" + e.model() + "; no key kept for " + e.host() + ")");
        }
        StringBuilder b = new StringBuilder("Models that can run now: ").append(local.isEmpty() ? "no local one" : String.join(", ", local));
        if (!keyed.isEmpty()) b.append("; hosted, key kept: ").append(String.join(", ", keyed));
        if (!keyless.isEmpty()) b.append("; hosted, no key yet: ").append(String.join(", ", keyless));
        return b.append(".").toString();
    }

    /** The add lines for a hosted model people name that no entry holds, from the families the library has measured; empty when the words name none. */
    public static List<String> knownAddLines(String words) {
        String w = squash(words); List<String> out = new ArrayList<>();
        if (w.contains("deepseek")) { out.add("researchzosho models add deepseek openrouter deepseek/deepseek-v4.1-flash"); out.add("researchzosho models add deepseek nebius deepseek-ai/DeepSeek-V4.1-Flash"); }
        if (w.contains("kimi")) out.add("researchzosho models add kimi openrouter moonshotai/kimi-k3");
        if (w.contains("claude") || w.contains("fable") || w.contains("sonnet") || w.contains("opus") || w.contains("haiku")) { out.add("researchzosho models add fable anthropic claude-fable-5-1"); out.add("researchzosho models add fable openrouter anthropic/claude-fable-5.1"); }
        if (w.contains("gpt") || w.contains("openai") || w.matches(".*\\b?(sol|luna|terra)\\d*.*") && (w.contains("sol") || w.contains("luna") || w.contains("terra"))) { out.add("researchzosho models add sol openai gpt-6.1-sol"); out.add("researchzosho models add sol openrouter openai/gpt-6.1-sol"); }
        if (w.contains("gemini")) { out.add("researchzosho models add gemini gemini gemini-4-argon"); out.add("researchzosho models add gemini openrouter google/gemini-4-argon"); }
        if (w.contains("glm")) out.add("researchzosho models add glm openrouter z-ai/glm-5.3-flash");
        if (w.contains("minimax")) out.add("researchzosho models add minimax openrouter minimax/minimax-m3.1-flash");
        return out;
    }

    /** No entry matches the words: what can run now, how the model named is added and keyed, and the way with what is here. */
    public static String noMatch(String words) {
        StringBuilder b = new StringBuilder("No model entry matches \"" + words + "\". " + canRunNow());
        List<String> adds = knownAddLines(words);
        if (!adds.isEmpty()) {
            String word = adds.get(0).split("\\s+")[4];
            b.append(" To add the one named: ").append(adds.get(0)).append(" --prices in/out/cache (the provider's list has the prices)").append(adds.size() > 1 ? ", or " + adds.get(1) : "").append("; then ").append(keyLine(word)).append(".");
        } else b.append(" One is added with: researchzosho models add <name> <openrouter | anthropic | openai | gemini | nebius | huggingface | an address> <model id> --prices in/out/cache, then researchzosho models key <that word> <key>, or on the Settings page under Models.");
        return b.append(" Meanwhile auto picks among the models here (\"checked by the strongest other model\", in a translation's words).").toString();
    }

    /** A hosted entry whose host has no key: the key line and what can run now. */
    public static String noKey(Entry e) {
        return e.name() + " runs on " + e.host() + " and no key is kept for it. Keep one: " + keyLine(e.host()) + ". " + canRunNow() + " Meanwhile auto picks among them (\"checked by the strongest other model\", in a translation's words).";
    }

    private static String squash(String s) { return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", ""); }

    public static Entry get(String name) {
        if (name == null || name.isBlank() || name.strip().equalsIgnoreCase(DEFAULT)) return defaultEntry();
        String n = name.strip().toLowerCase(Locale.ROOT);
        String drive = Config.get(key(n, "DRIVE")), model = Config.get(key(n, "MODEL"));
        if (drive == null || drive.isBlank()) return null;
        return new Entry(n, Config.driveBase(drive), model == null ? "" : model.strip(), prices(Config.get(key(n, "PRICES"))), effortOf(drive, Config.get(key(n, "EFFORT"))), capOf(Config.get(key(n, "CAP"))), Config.get(key(n, "NOTE"), ""), Config.get(key(n, "TIER")), seesOf(Config.get(key(n, "SEES"))));
    }

    /** Adds or changes an entry; a null field is left as it is. The name must pass {@link #nameOk}; {@code default} is the settings themselves. */
    public static Entry put(String name, String drive, String model, String prices, String effort, String cap, String note) throws IOException { return put(name, drive, model, prices, effort, cap, note, null); }

    /** As above, with the tier (A, B or C; blank goes back to the measured default). */
    public static Entry put(String name, String drive, String model, String prices, String effort, String cap, String note, String tier) throws IOException { return put(name, drive, model, prices, effort, cap, note, tier, null); }

    /** As above, with whether it sees pictures (yes, no; blank forgets the probe's answer). */
    public static Entry put(String name, String drive, String model, String prices, String effort, String cap, String note, String tier, String sees) throws IOException {
        String why = nameOk(name);
        if (name != null && name.strip().equalsIgnoreCase(Auto.NAME)) throw new IllegalArgumentException("The name auto is best judgement itself: researchzosho research ask … --model auto.");
        if (!why.isEmpty()) throw new IllegalArgumentException(why);
        String n = name.strip().toLowerCase(Locale.ROOT);
        if (n.equals(DEFAULT)) {
            if (drive != null) {
                String address = hostAddress(drive.strip());
                if (!address.matches("^https?://\\S+$")) throw new IllegalArgumentException("The address must start with http:// or https://, or be a host word: anthropic, openai, openrouter, gemini, nebius, huggingface, deepseek, moonshot.");
                Config.set("RESEARCHZOSHO_DRIVE", Config.driveBase(address));
            }
            if (model != null) Config.set("RESEARCHZOSHO_MODEL", model.strip());
        } else {
            if (drive != null) {
                String address = hostAddress(drive.strip());
                if (!address.matches("^https?://\\S+$")) throw new IllegalArgumentException("The address must start with http:// or https://, or be a host word: anthropic, openai, openrouter, gemini, nebius, huggingface, deepseek, moonshot.");
                Config.set(key(n, "DRIVE"), Config.driveBase(address));
            }
            if (model != null) Config.set(key(n, "MODEL"), model.strip());
        }
        if (prices != null) { if (!prices.isBlank() && prices(prices) == null) throw new IllegalArgumentException("Prices are dollars per million tokens as in/out or in/out/cache, for example 0.024/0.72/0.005."); Config.set(key(n, "PRICES"), prices.strip()); }
        if (effort != null) Config.set(key(n, "EFFORT"), effort.strip().toLowerCase(Locale.ROOT));
        if (cap != null) { if (!cap.isBlank() && capOf(cap) < 0) throw new IllegalArgumentException("A cap is dollars a month, for example 20."); Config.set(key(n, "CAP"), cap.strip()); }
        if (note != null) Config.set(key(n, "NOTE"), note.strip());
        if (tier != null) { String t = tier.strip().toUpperCase(Locale.ROOT); if (!t.isEmpty() && !t.matches("[ABC]")) throw new IllegalArgumentException("A tier is A, B or C."); Config.set(key(n, "TIER"), t); }
        if (sees != null) { if (!sees.isBlank() && seesOf(sees) == null) throw new IllegalArgumentException("--sees is yes or no: whether the model reads pictures."); sees(n, seesOf(sees)); }
        Entry e = get(n);
        if (e != null && !n.equals(DEFAULT) && (e.model() == null || e.model().isBlank())) throw new IllegalArgumentException("The entry needs a model id as well: researchzosho models add " + n + " <address> <model>.");
        return e;
    }

    /** Takes an entry out; the default cannot be removed. */
    public static void remove(String name) throws IOException {
        String n = name == null ? "" : name.strip().toLowerCase(Locale.ROOT);
        if (n.isEmpty() || n.equals(DEFAULT)) throw new IllegalArgumentException("The default entry is the drive and model settings; change those instead.");
        for (String f : new String[]{"DRIVE", "MODEL", "PRICES", "EFFORT", "CAP", "NOTE", "TIER", "SEES"}) Config.set(key(n, f), "");
    }

    /** The words setup takes for a host, as an address. */
    public static String hostAddress(String word) {
        String w = word.strip().toLowerCase(Locale.ROOT);
        return switch (w) {
            case "anthropic", "claude" -> Anthropic.BASE;
            case "openai" -> OpenAI.BASE;
            case "openrouter" -> "https://openrouter.ai/api/v1";
            case "gemini", "google" -> "https://generativelanguage.googleapis.com/v1beta/openai";
            case "nebius" -> "https://api.tokenfactory.nebius.com/v1";
            case "huggingface", "hf" -> "https://router.huggingface.co/v1";
            case "deepseek" -> "https://api.deepseek.com/v1";
            case "moonshot", "kimi" -> "https://api.moonshot.ai/v1";
            default -> Config.driveBase(word.strip());
        };
    }

    static double[] prices(String s) {
        if (s == null || s.isBlank()) return null;
        String[] p = s.strip().split("/");
        if (p.length < 2 || p.length > 3) return null;
        try { double[] out = new double[p.length]; for (int i = 0; i < p.length; i++) { out[i] = Double.parseDouble(p[i].strip().replace("$", "")); if (out[i] < 0) return null; } return out; }
        catch (NumberFormatException e) { return null; }
    }

    static double capOf(String s) { if (s == null || s.isBlank()) return 0; try { double d = Double.parseDouble(s.strip().replace("$", "")); return d < 0 ? -1 : d; } catch (NumberFormatException e) { return -1; } }

    /** An effort for the entry: its own, else the host setting's (Anthropic's or OpenAI's), else none. */
    static String effortOf(String drive, String own) {
        if (own != null && !own.isBlank()) return own.strip();
        if (Anthropic.is(drive)) return Config.get(Anthropic.EFFORT, "");
        if (OpenAI.is(drive)) return Config.get(OpenAI.EFFORT, "");
        return "";
    }

    /** Every entry as a list for the pages and the protocol; {@code key} is a word, kept, missing or not needed (a local server). */
    public static ArrayNode list(ObjectMapper m) {
        ArrayNode out = m.createArrayNode();
        for (Entry e : all().values()) {
            ObjectNode o = out.addObject();
            o.put("name", e.name()); o.put("drive", e.drive()); o.put("model", e.model()); o.put("host", e.host()); o.put("hosted", e.hosted());
            if (e.prices() != null) o.put("prices", e.pricesText());
            if (e.effort() != null && !e.effort().isBlank()) o.put("effort", e.effort());
            if (e.cap() > 0) o.put("cap", e.cap());
            o.put("tier", e.tier());
            if (e.sees() != null) o.put("sees", e.sees());
            o.put("sees_text", e.seesText());
            if (e.note() != null && !e.note().isBlank()) o.put("note", e.note());
            o.put("key", keyWord(e));
            o.put("line", e.line());
        }
        return out;
    }

    /** The names, for a dropdown or a usage line. */
    public static List<String> names() { return new ArrayList<>(all().keySet()); }
}
