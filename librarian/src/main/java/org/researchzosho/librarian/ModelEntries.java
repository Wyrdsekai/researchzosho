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

    /** One named model: where, which, what it costs. {@code prices} are dollars per million tokens — in, out, cache read — or null when unknown. */
    public record Entry(String name, String drive, String model, double[] prices, String effort, double cap, String note, String tierSet) {
        public Entry(String name, String drive, String model, double[] prices, String effort, double cap, String note) { this(name, drive, model, prices, effort, cap, note, null); }
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
            if (!List.of("DRIVE", "MODEL", "PRICES", "EFFORT", "CAP", "NOTE", "TIER").contains(field)) continue;
            names.add(rest.substring(0, cut).toLowerCase(Locale.ROOT).replace('_', '-'));
        }
        for (String n : names) { if (n.equals(DEFAULT)) continue; Entry e = get(n); if (e != null) out.put(n, e); }
        return out;
    }

    /** The entry called {@code default}: the drive and model settings, with the prices and cap kept under the name default when any are. */
    public static Entry defaultEntry() {
        String drive = Config.get("RESEARCHZOSHO_DRIVE", "http://localhost:8080"), model = Config.get("RESEARCHZOSHO_MODEL", "local-model");
        return new Entry(DEFAULT, drive, model, prices(Config.get(key(DEFAULT, "PRICES"))), effortOf(drive, Config.get(key(DEFAULT, "EFFORT"))), capOf(Config.get(key(DEFAULT, "CAP"))), Config.get(key(DEFAULT, "NOTE"), ""), Config.get(key(DEFAULT, "TIER")));
    }

    /** The entry by name, {@code default} included; null when there is none. */
    public static Entry get(String name) {
        if (name == null || name.isBlank() || name.strip().equalsIgnoreCase(DEFAULT)) return defaultEntry();
        String n = name.strip().toLowerCase(Locale.ROOT);
        String drive = Config.get(key(n, "DRIVE")), model = Config.get(key(n, "MODEL"));
        if (drive == null || drive.isBlank()) return null;
        return new Entry(n, Config.driveBase(drive), model == null ? "" : model.strip(), prices(Config.get(key(n, "PRICES"))), effortOf(drive, Config.get(key(n, "EFFORT"))), capOf(Config.get(key(n, "CAP"))), Config.get(key(n, "NOTE"), ""), Config.get(key(n, "TIER")));
    }

    /** Adds or changes an entry; a null field is left as it is. The name must pass {@link #nameOk}; {@code default} is the settings themselves. */
    public static Entry put(String name, String drive, String model, String prices, String effort, String cap, String note) throws IOException { return put(name, drive, model, prices, effort, cap, note, null); }

    /** As above, with the tier (A, B or C; blank goes back to the measured default). */
    public static Entry put(String name, String drive, String model, String prices, String effort, String cap, String note, String tier) throws IOException {
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
        Entry e = get(n);
        if (e != null && !n.equals(DEFAULT) && (e.model() == null || e.model().isBlank())) throw new IllegalArgumentException("The entry needs a model id as well: researchzosho models add " + n + " <address> <model>.");
        return e;
    }

    /** Takes an entry out; the default cannot be removed. */
    public static void remove(String name) throws IOException {
        String n = name == null ? "" : name.strip().toLowerCase(Locale.ROOT);
        if (n.isEmpty() || n.equals(DEFAULT)) throw new IllegalArgumentException("The default entry is the drive and model settings; change those instead.");
        for (String f : new String[]{"DRIVE", "MODEL", "PRICES", "EFFORT", "CAP", "NOTE", "TIER"}) Config.set(key(n, f), "");
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

    /** Every entry as a list for the pages and the protocol. */
    public static ArrayNode list(ObjectMapper m) {
        ArrayNode out = m.createArrayNode();
        for (Entry e : all().values()) {
            ObjectNode o = out.addObject();
            o.put("name", e.name()); o.put("drive", e.drive()); o.put("model", e.model()); o.put("host", e.host()); o.put("hosted", e.hosted());
            if (e.prices() != null) o.put("prices", e.pricesText());
            if (e.effort() != null && !e.effort().isBlank()) o.put("effort", e.effort());
            if (e.cap() > 0) o.put("cap", e.cap());
            o.put("tier", e.tier());
            if (e.note() != null && !e.note().isBlank()) o.put("note", e.note());
            o.put("key", DriveClient.hostKey(e.host()) != null || (e.name().equals(DEFAULT) && Config.get("RESEARCHZOSHO_API_KEY") != null));
            o.put("line", e.line());
        }
        return out;
    }

    /** The names, for a dropdown or a usage line. */
    public static List<String> names() { return new ArrayList<>(all().keySet()); }
}
