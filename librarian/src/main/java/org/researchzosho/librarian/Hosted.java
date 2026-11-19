package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import org.researchzosho.Config;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * A library that runs under an account service (0.8.0). Such a library keeps no accounts of its own: who is asking and what they may do
 * comes with each request, in the statement the service signed ({@link Statement}), and this class holds what the last statement said
 * of each account, for the work that goes on after a request (a job, a chat turn's own calls).
 *
 * <p>The person who keeps a hosted library is not the person who owns the machine it runs on. So nobody who comes through the service
 * is {@code person}, and everything {@code person} may do to the machine stays closed to them: a file by its path, a program, a
 * database, a tunnel, the model's address. {@link #CALLS} marks every call of the protocol as the library's or the machine's; a call
 * of the machine's is refused whole in a hosted library, and the guards at the places that act on the machine ({@link #machine()})
 * refuse there too, whatever surface asked.
 */
public final class Hosted {
    private Hosted() { }

    /** What the service vouched for one account in one library: the level, whether the account keeps the library, and its name. */
    public record Vouch(Patrons.Level level, boolean keeper, String account, String name) { }

    private static final Map<Path, Map<String, Vouch>> LIBRARIES = new ConcurrentHashMap<>();

    /** The prefix of the id a person has in a hosted library: {@code did:rz:} and the account's id. */
    public static final String DID = "did:rz:";

    /** What a refused act of the machine's is answered with. */
    /** What a person of a hosted library is told where the single-person program names the model's address: the model is the service's, and its address is the service's own affair. */
    public static final String NO_MODEL = "The model of the service this library runs under does not answer now. Whoever runs the service has to look into it.";
    public static final String MACHINE = "This library runs under an account service, so this is set up by whoever runs the service, not from here.";
    /** What a refused setting is answered with. */
    public static final String SETTING = Config.NOT_WRITABLE;

    static Path key(LibraryStore store) { return store.root().toAbsolutePath().normalize(); }

    /** A hosted library's daemon starts: from now its requests are answered by their statements alone. */
    static void begin(LibraryStore store) { LIBRARIES.putIfAbsent(key(store), new ConcurrentHashMap<>()); FOLDERS.put(key(store), Folders.of(key(store))); }

    /** The daemon stops. */
    static void end(LibraryStore store) { LIBRARIES.remove(key(store)); FOLDERS.remove(key(store)); }

    // ---- no path of the service's machine in what a person of a hosted library is shown ----

    private static final Map<Path, Folders> FOLDERS = new ConcurrentHashMap<>();

    /**
     * Where one hosted library lives on the service's machine, as the patterns that take those folders out of a text: a file a
     * browser posted is shown by its name ({@code file://notes.pdf}), a file inside the library by its place in the library
     * ({@code exports/report.pdf}), and nothing else of the path is left. Each pattern is kept as written and as an address carries it.
     */
    private record Folders(List<Pattern> upload, List<Pattern> inside, List<Pattern> bare) {
        static Folders of(Path root) {
            // the service keeps a library as <folder>/library with its settings folder, temporary folder and home beside it (LibraryPlaces)
            Path folder = root.getFileName() != null && root.getFileName().toString().equals(LibraryPlaces.LIBRARY) && root.getParent() != null ? root.getParent() : root;
            List<String> temporary = new ArrayList<>(List.of(LibraryPlaces.tmp(folder).toString()));
            String own = Path.of(System.getProperty("java.io.tmpdir", "/tmp")).toAbsolutePath().normalize().toString();
            if (!temporary.contains(own)) temporary.add(own);
            List<Pattern> upload = new ArrayList<>(), inside = new ArrayList<>(), bare = new ArrayList<>();
            for (String t : temporary) for (String sep : new String[]{"/", "%2F"}) upload.add(Pattern.compile(Pattern.quote(as(t, sep)) + Pattern.quote(sep + Uploads.DIR) + "[A-Za-z0-9._-]*" + Pattern.quote(sep)));
            for (String f : root.equals(folder) ? List.of(root.toString()) : List.of(root.toString(), folder.toString())) for (String sep : new String[]{"/", "%2F"}) {
                inside.add(Pattern.compile(Pattern.quote(as(f, sep) + sep)));
                bare.add(Pattern.compile(Pattern.quote(as(f, sep))));
            }
            return new Folders(upload, inside, bare);
        }

        /** A path as written, or as an address carries it (its separators as %2F, the rest encoded the same way). */
        private static String as(String path, String sep) { return sep.equals("/") ? path : URLEncoder.encode(path, StandardCharsets.UTF_8); }

        String shown(String text) {
            String out = text;
            for (Pattern p : upload) out = p.matcher(out).replaceAll("");
            for (Pattern p : inside) out = p.matcher(out).replaceAll("");
            for (Pattern p : bare) out = p.matcher(out).replaceAll("(this library)");
            return out;
        }
    }

    /**
     * A text as a person of a hosted library is shown it: with no path of the service's machine in it. Every answer of a hosted
     * library goes through this as it leaves (a page, a JSON answer, an error), so a path in a job's result, an export's line or an
     * error's text is taken out wherever it came from. Outside a hosted library the text is as it is.
     */
    public static String shown(String text) {
        if (text == null || FOLDERS.isEmpty()) return text;
        String out = text;
        for (Folders f : FOLDERS.values()) out = f.shown(out);
        return out;
    }

    /** Whether this library runs under an account service. */
    public static boolean on(LibraryStore store) { return store != null && LIBRARIES.containsKey(key(store)); }

    /** Whether this process serves a hosted library: the places that act on the machine ask this, since one process serves one library. */
    public static boolean any() { return !LIBRARIES.isEmpty(); }

    /** What the statement of a request said of its account, kept for the work after the request. */
    static void note(LibraryStore store, String did, Vouch v) { Map<String, Vouch> m = LIBRARIES.get(key(store)); if (m != null) m.put(did, v); }

    /**
     * The service's word on the library's people now ({did: level}): a person it no longer names is forgotten, and one whose level changed has
     * it changed, so a change sent to a webhook goes to nobody the service took out, suspended or deleted (round F, S15).
     */
    static void keepOnly(LibraryStore store, JsonNode members) {
        Map<String, Vouch> m = LIBRARIES.get(key(store));
        if (m == null) return;
        for (String did : List.copyOf(m.keySet())) {
            String lv = members.path(did).asText("");
            Vouch v = m.get(did);
            if (lv.isEmpty()) { m.remove(did); continue; }
            Patrons.Level level = lv.equals(Statement.KEEPER) || lv.equals("write") ? Patrons.Level.write : Patrons.Level.read;
            if (v != null && (v.level() != level || v.keeper() != lv.equals(Statement.KEEPER))) m.put(did, new Vouch(level, lv.equals(Statement.KEEPER), v.account(), v.name()));
        }
    }

    /** The last the service vouched for this id here; null when it never did. */
    public static Vouch of(LibraryStore store, String did) {
        Map<String, Vouch> m = store == null || did == null ? null : LIBRARIES.get(key(store));
        return m == null ? null : m.get(did);
    }

    /** A hosted library's readers as the service vouched for them so far, in the shape of the list a library on its own keeps: nobody unlisted gets anything. */
    static Patrons.Policy policy(LibraryStore store) {
        List<Patrons.Entry> listed = new ArrayList<>();
        Map<String, Vouch> m = LIBRARIES.get(key(store));
        if (m != null) for (Map.Entry<String, Vouch> e : m.entrySet()) listed.add(new Patrons.Entry(e.getKey(), e.getValue().name(), e.getValue().level()));
        return new Patrons.Policy(Patrons.Level.deny, listed);
    }

    /** Whether the caller keeps this library: the person at the machine, or in a hosted library the account the service says keeps it. */
    public static boolean keeper(LibraryStore store, Patrons.Patron patron) {
        if (patron.person()) return true;
        Vouch v = on(store) ? of(store, patron.did()) : null;
        return v != null && v.keeper();
    }

    /** What a file named by its path is answered with in a hosted library. */
    public static final String PATH = "This library runs under an account service, so it reads no file by its path on the service's machine. Add a file of your own from your computer on the Add page, or give a web address.";

    /** Refuses, in a hosted library, a file or a folder named by its path on the machine; nothing anywhere else. */
    public static void path(LibraryStore store) { if (on(store)) throw ProtocolError.forbidden(Pages.t(PATH)); }

    /** What a change to the library's own people or keys is answered with in a hosted library. */
    public static final String ACCOUNTS = "This library runs under an account service: its people and the keys of their programs are on the account page there.";

    /** Refuses, in a hosted library, a write to its own lists of people, accounts and keys, which it does not keep; nothing anywhere else. */
    public static void accounts(LibraryStore store) { if (on(store)) throw ProtocolError.forbidden(Pages.t(ACCOUNTS)); }

    /** Refuses, in a hosted library's process, an act on the machine; nothing anywhere else. */
    public static void machine() { if (any()) throw ProtocolError.forbidden(Pages.t(MACHINE)); }

    // ---- the settings a hosted library may write ----

    /** The settings that are the library's own: how its reports are written and what its nights do. Every other setting is the service's. */
    static final Set<String> OWN_SETTINGS = Set.of("RESEARCHZOSHO_LANGUAGE", ReportOptions.LENGTH_SETTING, ReportOptions.CITATIONS_SETTING, Translation.NOTES_SETTING, Settings.MARKET,
            "RESEARCHZOSHO_EXPLORER_PER_NIGHT", "RESEARCHZOSHO_EXPLORER_TONIGHT", "RESEARCHZOSHO_EXPLORER_TYPES", ResearchSettings.PAUSE);

    /** Whether a hosted library may write this setting (its key as the settings file normalises it). */
    public static boolean ownSetting(String key) { return key != null && OWN_SETTINGS.contains(key); }

    // ---- every call of the protocol: the library's, or the machine's ----

    public enum Reach { LIBRARY, MACHINE }

    /**
     * Every POST /v1 route (and so every MCP tool, by its name without {@code library_}) with its mark. A test fails when a route or a
     * tool has no mark here, so a call added later cannot be forgotten. A call of the library's may still have a part that is closed in
     * a hosted library (a file by its path, a set-up that installs a program): those parts are closed where they are carried out.
     */
    public static final Map<String, Reach> CALLS = new LinkedHashMap<>();
    static {
        for (String machine : new String[]{"share", "db", "mcp", "models", "repo"}) CALLS.put(machine, Reach.MACHINE);
        for (String library : new String[]{"ask", "search", "get", "read", "established", "submit", "add", "translate", "absorb", "survey", "items", "check", "reading", "questions", "bookmarks",
                "meeting", "holdings", "who", "remove", "bridges", "seeks", "frontier", "inbox", "serials", "subjects", "status", "help", "changes", "map", "perspectives", "sharpen", "explain",
                "request_access", "access", "subscribe", "unsubscribe", "settings", "sources", "owned", "my_shelf", "prefs", "dna", "follow", "react", "dossier", "export", "suggest", "tell", "video",
                "look", "candidates", "sift", "patents", "patent_abstracts", "patent_claims", "home", "records", "career", "board", "job", "crews", "spend", "images", "research", "jobs/{id}", "crews/run"})
            CALLS.put(library, Reach.LIBRARY);
    }

    /** The mark of a route, or of a tool by its full name; null when it has none. */
    public static Reach reach(String routeOrTool) {
        if (routeOrTool == null) return null;
        return CALLS.get(routeOrTool.startsWith("library_") ? routeOrTool.substring("library_".length()) : routeOrTool);
    }

    /** Whether a tool is offered in a hosted library: the machine's are left out of the list. */
    public static boolean offered(String tool) { return reach(tool) != Reach.MACHINE; }

    /** Refuses a call of the machine's in a hosted library, by its route or tool name. */
    public static void call(LibraryStore store, String routeOrTool) { if (on(store) && reach(routeOrTool) == Reach.MACHINE) throw ProtocolError.forbidden(Pages.t(MACHINE)); }
}
