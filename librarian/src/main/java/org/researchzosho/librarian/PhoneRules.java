package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.researchzosho.drive.DriveClient;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * What a paired phone's chat may do (2026-11-25), decided here by the program and never by the model: what the phone page is for. Asking,
 * reading, research with the options of a run, a report read in a language, saving a link, a note, a claim or the picture the message
 * attached, finding and keeping pictures, stopping a run the phone itself filed. Not a setting, sharing, a model entry or a key, a source,
 * a saved search, a reader, a drop or a decision, a file of this machine, the owner's databases, the purchase records, an export, the batch
 * read-ins, the glossary, the fields' own tools, the keeper's MCP servers: those are refused from the phone in one sentence, whatever the
 * message says and with or without a go-ahead. A paid model is used from a phone within that phone's own monthly cap (the owner's choice,
 * 2026-11-25: one cap a phone, ten dollars unless he sets another), and only when its prices are known, since what cannot be counted cannot
 * be held to a cap; a model on the person's own machines costs nothing and is never refused.
 *
 * <p>{@link Librarian} holds it three times: the phone's turn is shown only the tools allowed here; every call the model makes is
 * checked here before the address rule, the library's question and the go-ahead; and {@link Librarian#call}, where every way of carrying a
 * call out ends, checks again.
 */
final class PhoneRules {
    private PhoneRules() { }

    /** The runtime a paired phone's requests carry, with the phone's id after it. */
    static final String RUNTIME = "phone:";

    static boolean isPhone(Patrons.Patron p) { return p != null && p.runtime() != null && p.runtime().startsWith(RUNTIME); }

    static String phoneId(Patrons.Patron p) { return isPhone(p) ? p.runtime().substring(RUNTIME.length()) : ""; }

    /** A paired phone as the library's patron: the person who keeps it, by the phone's name, through the phone door. */
    static Patrons.Patron patron(Phones.Phone phone) { return new Patrons.Patron("person", phone.name(), RUNTIME + phone.id()); }

    /** What the library says under the reply when it refused something from the phone. */
    static final String SAID = "From the phone the library does not change its settings, sharing, models, keys, sources or readers, and removes nothing: do that on the library's own network.";

    /** A paid model whose prices the library does not know: what it costs cannot be counted against the phone's cap. {0}: the model entry. */
    static final String UNPRICED = "{0} is a paid model whose prices the library does not know, so what it costs cannot be counted against this phone's cap, and it is not used from the phone. Give it prices on the library's machine: researchzosho models set {0} --prices in/out/cache.";
    /** A phone whose cap is nothing. {0}: the phone's name. */
    static final String NO_PAID = "This phone may not use a paid model: its monthly cap is $0. To allow one, on the library's machine: researchzosho phone cap {0} <dollars>.";
    /** A phone at its cap. {0}: spent, {1}: its cap, {2}: the model entry, {3}: the phone's name. */
    static final String AT_CAP = "This phone has spent {0} of its {1} this month on paid models, so {2} is not used from it until the month turns. To raise its cap, on the library's machine: researchzosho phone cap {3} <dollars>.";
    /** What a phone has spent this month, said on the phone. {0}: spent, {1}: its cap in words ("$10.00 a month"). */
    static final String MONTH = "This month: {0} spent on paid models; its cap is {1}.";
    /** The same for a phone with no limit. {0}: spent. */
    static final String MONTH_NO_LIMIT = "This month: {0} spent on paid models; no limit.";
    /** Calls on paid models whose prices are unknown, which no figure holds. {0}: how many. */
    static final String NOT_COUNTED = "{0} calls on paid models whose prices are unknown are not in that figure.";

    /** The tools that only read: allowed with any arguments that name no file of this machine. */
    static final Set<String> READS = Set.of("library_ask", "library_search", "library_get", "library_map", "library_changes", "library_status", "library_help",
            "library_explain", "library_perspectives", "library_established", "library_holdings", "library_my_shelf", "library_sharpen");

    /** The tools allowed to read or list only: the operations that read. */
    static final Map<String, Set<String>> READ_OPS = Map.of(
            "library_job", Set.of("", "read", "status", "list"),
            "library_frontier", Set.of("", "list"),
            "library_inbox", Set.of("", "list"),
            "library_models", Set.of("", "list"));

    /** The tools of work and saving, each with its own rule below. */
    static final Set<String> WORK = Set.of("library_research", "library_translate", "library_images", "library_add", "library_submit", "library_tell");

    /** The picture operations a phone may ask for. */
    static final Set<String> PICTURE_OPS = Set.of("", "find", "describe", "judge", "keep", "collect");

    static boolean allowedName(String name) { return READS.contains(name) || READ_OPS.containsKey(name) || WORK.contains(name); }

    /** The tools of a chat's table a phone's turn is shown: those allowed at all, in the table's order. */
    static List<String> tools(List<String> all) {
        List<String> out = new ArrayList<>();
        for (String n : all) if (allowedName(n)) out.add(n);
        return out;
    }

    /** Why a call is refused from the phone {@code phone} (its patron), for the model's result; null when the phone may make it. */
    static String refusal(LibraryStore store, Patrons.Patron phone, String name, JsonNode args) {
        String n = name == null ? "" : name;
        if (!allowedName(n)) return "the phone does not reach " + n;
        if (Librarian.machinePath(args) || Librarian.localPicture(args)) return "a file or a folder of this machine is not opened from the phone";
        String op = GoAhead.op(args);
        // a run this phone itself filed may be stopped from it: stopping only ends work; a run started anywhere else is the library's network's
        if (n.equals("library_job") && op.equals("stop")) return ownRun(store, GoAhead.text(args, "job_id"), phoneId(phone)) ? null : "a run started elsewhere is stopped on the library's own network";
        Set<String> reads = READ_OPS.get(n);
        if (reads != null) return reads.contains(op) ? null : "from the phone " + n + " only reads";
        return switch (n) {
            case "library_research" -> spend(store, phone, named(args, "model", true));
            case "library_translate" -> op.equals("glossary") ? "the glossary is changed on the library's own network" : firstOf(spend(store, phone, named(args, "model", true)), spend(store, phone, named(args, "judge", false)));
            case "library_images" -> PICTURE_OPS.contains(op) ? null : "from the phone pictures are found, described, judged and kept";
            case "library_add" -> {
                String mode = GoAhead.text(args, "mode").toLowerCase(Locale.ROOT);
                if (args != null && args.path("register").asBoolean(false)) yield "a folder is not watched from the phone";
                yield mode.equals("link") || mode.equals("survey") ? "from the phone a link or an attached picture is kept, nothing else" : null;
            }
            default -> null;
        };
    }

    /**
     * The model entry a call names under {@code key}; with nothing named and {@code dflt}, the library's default entry (the one the run
     * takes unless best judgement is the default, which chooses when the run starts and is checked then); null when there is none to check.
     */
    static ModelEntries.Entry named(JsonNode args, String key, boolean dflt) {
        String n = GoAhead.text(args, key);
        if (n.equalsIgnoreCase(Auto.NAME) || (n.isEmpty() && (!dflt || Auto.isDefault()))) return null;
        return n.isEmpty() || n.equalsIgnoreCase(ModelEntries.DEFAULT) ? ModelEntries.defaultEntry() : ModelEntries.get(n);
    }

    /**
     * Why a model may not be used for this phone's work, as a refusal key, or null: a model of the person's own costs nothing; a paid
     * one needs prices (else it cannot be counted), a cap above nothing on the phone, and the phone's month under its cap.
     */
    static String spend(LibraryStore store, Patrons.Patron phone, ModelEntries.Entry e) {
        if (e == null || !paid(e)) return null;
        Phones.Phone p = phoneOf(phone);
        if (p != null && p.cap() < 0) return null;   // no limit: nothing is refused for spending, a model without prices too (there is nothing to count it against)
        if (e.prices() == null) return "unpriced:" + e.name();
        if (p == null) return null;
        if (p.cap() == 0) return "nopaid:" + p.name();
        double spent = Spend.phoneMonth(store, p.id());
        return spent >= p.cap() ? "atcap:" + e.name() : null;
    }

    /** What a phone has spent on models this month, in a line for the phone and the list; with no limit, what could not be counted. */
    static String monthSaid(LibraryStore store, Phones.Phone p) {
        Chrome c = Chrome.now();
        double spent = Spend.phoneMonth(store, p.id());
        if (p.cap() >= 0) return c.t(MONTH, Spend.dollars(spent), Phones.capSaid(p.cap(), c));
        long unpriced = Spend.phoneUnpriced(store, p.id());
        return c.t(MONTH_NO_LIMIT, Spend.dollars(spent)) + (unpriced == 0 ? "" : " " + c.t(NOT_COUNTED, unpriced));
    }

    /** The paired phone a patron is, read now from the list; null for none (removed since). */
    static Phones.Phone phoneOf(Patrons.Patron phone) {
        String id = phoneId(phone);
        if (id.isEmpty()) return null;
        try { return Phones.get(id); } catch (IOException e) { return null; }
    }

    /**
     * Whether a model entry is one the person pays for: a hosted API as the program tells them apart everywhere ({@link
     * ModelEntries.Entry#hosted}: the known providers, Anthropic, OpenAI), any host the library keeps a key for, and any address that is
     * not of this machine or of the person's own network. An OpenAI-shaped server on another machine of the house, at a private address,
     * a one-word name or a local domain, with no key, is the person's own and free.
     */
    static boolean paid(ModelEntries.Entry e) {
        if (e == null) return false;
        if (e.hosted()) return true;
        String host = DriveClient.hostOf(e.drive());
        if (host != null && DriveClient.hostKey(host) != null) return true;
        return !ownNetwork(e.drive());
    }

    /** Whether an address is of this machine or the person's own network, read from how it is written (no name is looked up). */
    static boolean ownNetwork(String address) {
        String h;
        try { h = address == null ? null : URI.create(address.strip()).getHost(); } catch (IllegalArgumentException e) { return false; }
        if (h == null || h.isBlank()) return false;
        h = h.toLowerCase(Locale.ROOT).replaceAll("^\\[|\\]$", "");
        if (h.equals("localhost") || h.endsWith(".localhost") || !h.contains(".") && !h.contains(":")) return true;   // a one-word name is the house's own
        for (String suffix : List.of(".local", ".lan", ".home", ".home.arpa", ".internal", ".ts.net")) if (h.endsWith(suffix)) return true;
        if (h.matches("\\d{1,3}(\\.\\d{1,3}){3}")) {
            String[] q = h.split("\\.");
            int a = Integer.parseInt(q[0]), b = Integer.parseInt(q[1]);
            return a == 10 || a == 127 || (a == 172 && b >= 16 && b <= 31) || (a == 192 && b == 168) || (a == 169 && b == 254) || (a == 100 && b >= 64 && b <= 127);
        }
        if (h.contains(":")) return h.equals("::1") || h.startsWith("fc") || h.startsWith("fd") || h.startsWith("fe80");
        return false;
    }

    /** Whether a run was filed from this phone ({@code from_phone} on its job, the phone's id, set by the program). */
    static boolean ownRun(LibraryStore store, String jobId, String phoneId) {
        if (store == null || jobId == null || jobId.isBlank() || phoneId == null || phoneId.isBlank()) return false;
        try { ObjectNode j = new Jobs(store, x -> "").get(jobId.strip()); return j != null && phoneId.equals(j.path("args").path(FROM_PHONE).asText("")); }
        catch (IOException | RuntimeException e) { return false; }
    }

    /**
     * The mark on a job filed from a phone, the phone's id: its run uses no database and no MCP server of the machine, a paid model only
     * within that phone's cap, and the phone may stop it.
     */
    static final String FROM_PHONE = "from_phone";

    /** The phone a job was filed from, or "" for none. */
    static String phoneOfJob(JsonNode args) { String v = args == null ? "" : args.path(FROM_PHONE).asText(""); return v.matches("[a-z0-9]{1,32}") ? v : ""; }

    private static String firstOf(String a, String b) { return a != null ? a : b; }

    /** The sentence the person reads for a refusal, in their language: the spending rule's own, or the phone's. {@code phone}: who asked, for the month's figures. */
    static String said(String why) { return said(null, null, why); }

    static String said(LibraryStore store, Patrons.Patron phone, String why) {
        Chrome c = Chrome.now();
        if (why == null) return c.t(SAID);
        String what = why.contains(":") ? why.substring(why.indexOf(':') + 1) : "";
        if (why.startsWith("unpriced:")) return c.t(UNPRICED, what);
        if (why.startsWith("nopaid:")) return c.t(NO_PAID, what);
        if (why.startsWith("atcap:")) {
            Phones.Phone p = phoneOf(phone);
            return p == null || store == null ? c.t(SAID) : c.t(AT_CAP, Spend.dollars(Spend.phoneMonth(store, p.id())), Spend.dollars(p.cap()), what, p.name());
        }
        return c.t(SAID);
    }

    /** The result the model reads for a refused call. */
    static String result(String name, String why) {
        String w = why == null ? "" : why.startsWith("unpriced:") ? "the model " + why.substring(9) + " is a paid model whose prices are unknown, so its cost cannot be counted against the phone's cap"
                : why.startsWith("nopaid:") ? "this phone may use no paid model" : why.startsWith("atcap:") ? "this phone has reached its monthly cap on paid models" : why;
        return "REFUSED FROM THE PHONE: " + w + ", so the library ran nothing (" + name + "). The library says so under your reply; say in one sentence that this is done on the library's own network.";
    }

    /** Whether a path is one of this message's uploads (a picture the phone attached), the only file of this machine a phone's call may name. */
    static boolean upload(String path) {
        try { return path != null && !path.isBlank() && Uploads.isUpload(Path.of(path).toAbsolutePath().normalize()); } catch (RuntimeException e) { return false; }
    }
}
