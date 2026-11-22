package org.researchzosho.drive;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A model server reached through its node's model gate (0.8.0, stage 3). A library under an account service is given its model
 * servers as addresses of the gate, which carry the server they stand for: {@code http://127.0.0.1:<port>/gate/https/api.anthropic.com}
 * stands for {@code https://api.anthropic.com}. The library tells by the server it stands for which kind of server it is (Anthropic's
 * own API, OpenAI's, an OpenAI-style one), and sends every call to the gate, which holds the keys, counts the call against the use
 * it carries, and passes it on. A call carries its use and its lane in two headers, from what the library is doing on that thread.
 */
public final class Gated {
    private Gated() { }

    /** Where in a gate's address the server it stands for begins. */
    public static final String PATH = "/gate/";
    /** The headers a call carries: the use it is part of, and its lane ({@link #WAITING} or {@link #BACKGROUND}). */
    public static final String USE = "X-RZ-Use", LANE = "X-RZ-Lane", LANG = "X-RZ-Lang";
    public static final String WAITING = "waiting", BACKGROUND = "background";

    /** Told of a call that costs made where nothing the service let start is going: the library says where, for whoever runs the service. */
    public static volatile Consumer<String> UNMARKED = url -> { };

    /** What the library is doing on this thread: {use id, lane}, or null when nothing it may spend on. The library sets it. */
    public static volatile Supplier<String[]> CONTEXT = () -> null;

    /** What this thread's work is, taken to hand to a thread that does part of it (a run's readers); null when nothing. The library sets it. */
    public static volatile Supplier<Supplier<String[]>> CAPTURE = () -> null;

    /** What a thread doing part of another's work carries: that work's use, as {@link #CONTEXT} gives it. */
    public static final ThreadLocal<Supplier<String[]>> CARRIED = new ThreadLocal<>();

    /**
     * The header a library's request to the web carries through its node's web gate, the use it is part of: a header named for the
     * proxy, which Java's client sends on the proxy's CONNECT as well, and never to the site.
     */
    public static final String WEB_USE = "Proxy-RZ-Use";

    /** The use this thread's web requests are part of, when one is already going (none is asked for to fetch a page); the library sets it. */
    public static volatile Supplier<String> WEB = () -> null;

    /** What a thread doing part of another's work carries for its web requests: that work's use. */
    public static final ThreadLocal<String> CARRIED_WEB = new ThreadLocal<>();

    /** {@code work}, to be run on another thread as part of what this thread does now: its calls that cost carry this thread's use. */
    public static <T> Callable<T> carried(Callable<T> work) {
        Supplier<String[]> use = CAPTURE.get();
        String web = webUse();
        if (use == null && web == null) return work;
        return () -> {
            Supplier<String[]> had = CARRIED.get();
            String hadWeb = CARRIED_WEB.get();
            if (use != null) CARRIED.set(use);
            if (web != null) CARRIED_WEB.set(web);
            try { return work.call(); }
            finally { if (had == null) CARRIED.remove(); else CARRIED.set(had); if (hadWeb == null) CARRIED_WEB.remove(); else CARRIED_WEB.set(hadWeb); }
        };
    }

    /** {@code work} run on this thread as part of the use {@code use} (a page added by its address): its calls carry that use. */
    public static <T> T under(String use, String lane, String lang, Callable<T> work) throws Exception {
        if (use == null || use.isBlank()) return work.call();
        Supplier<String[]> had = CARRIED.get();
        String hadWeb = CARRIED_WEB.get();
        String[] c = {use, lane, lang};
        CARRIED.set(() -> c);
        CARRIED_WEB.set(use);
        try { return work.call(); }
        finally { if (had == null) CARRIED.remove(); else CARRIED.set(had); if (hadWeb == null) CARRIED_WEB.remove(); else CARRIED_WEB.set(hadWeb); }
    }

    private static String webUse() { String w = CARRIED_WEB.get(); return w != null ? w : WEB.get(); }

    /** A request to the web marked with the use this thread's work is, for the node's web gate; nothing for this machine's own addresses. */
    public static HttpRequest.Builder webMark(String url, HttpRequest.Builder b) {
        if (url == null || !url.regionMatches(true, 0, "http", 0, 4)) return b;
        try {
            String h = URI.create(url).getHost();
            if (h == null || h.equals("127.0.0.1") || h.equalsIgnoreCase("localhost") || h.equals("[::1]")) return b;
        } catch (RuntimeException e) { return b; }
        String use = webUse();
        if (use != null && !use.isBlank()) b.header(WEB_USE, use);
        return b;
    }

    /** Where the node's gate takes the heavy helpers' calls (speech-to-text, LaBSE, MetricX-24, the likeness and comic helpers). */
    public static final String HELPER = "/helper/";

    /** Whether an address is a helper's at a node's gate. */
    public static boolean helper(String url) {
        if (url == null || !url.startsWith("http://127.0.0.1:")) return false;
        try { String p = URI.create(url).getPath(); return p != null && p.startsWith(HELPER); } catch (RuntimeException e) { return false; }
    }

    /** The server an address stands for: itself, or the one a gate's address carries. */
    public static String upstream(String url) {
        if (url == null) return null;
        int at = url.indexOf(PATH);
        if (at < 0 || !url.startsWith("http://")) return url;
        String rest = url.substring(at + PATH.length());
        int slash = rest.indexOf('/');
        if (slash <= 0) return url;
        String scheme = rest.substring(0, slash).toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) return url;
        return scheme + "://" + rest.substring(slash + 1);
    }

    /** Whether an address is a gate's. */
    public static boolean gated(String url) { return url != null && !upstream(url).equals(url); }

    /** The gate's address at {@code gate} (http://host:port) for the server at {@code url}: what the library is given in its place. */
    public static String via(String gate, String url) {
        URI u = URI.create(url.strip());
        String scheme = u.getScheme() == null ? "http" : u.getScheme().toLowerCase(Locale.ROOT);
        String hostPort = u.getRawAuthority();
        String path = u.getRawPath() == null ? "" : u.getRawPath();
        return gate.replaceAll("/+$", "") + PATH + scheme + "/" + hostPort + path + (u.getRawQuery() == null ? "" : "?" + u.getRawQuery());
    }

    /** Where the node's gates take the paid search places' calls, after the gate's own address. */
    public static final String SEARCH = "/search/";

    /** Whether a call to this address costs: a model's answer, embeddings or a reranking at the model gate, anything at the search gate. */
    public static boolean billable(String url) {
        if (url == null || !url.startsWith("http://127.0.0.1:")) return false;   // a node's gates are on the loopback the library sees
        String path;
        try { path = inner(URI.create(url).getPath()); } catch (RuntimeException e) { return false; }
        if (path == null) return false;
        if (path.startsWith(SEARCH)) return true;
        if (!path.startsWith(PATH)) return false;
        return costs(path);
    }

    /** The endings of the model servers' paths whose calls cost: an answer, embeddings, a reranking. Each is asked for by POST. */
    static final String[] COSTS = {"/chat/completions", "/completions", "/messages", "/responses", "/embeddings", "/rerank"};
    /** The endings of the paths the program looks at a model server with (GET), which cost nothing: its models, its properties, its slots, its health. */
    static final String[] LOOKS = {"/models", "/props", "/slots", "/running", "/health", "/api/tags", "/api/version"};

    /** Whether a model server's path is one whose call costs. */
    public static boolean costs(String path) { for (String e : COSTS) if (path.endsWith(e)) return true; return false; }

    /**
     * Whether a call to a model server's path is one the program itself makes that costs nothing: a look at its models (and one model),
     * its properties, slots and health by GET, and Ollama's description of a model. Every other call but one that costs is refused at the gate.
     */
    public static boolean free(String method, String path) {
        if (method.equals("POST")) return path.endsWith("/api/show");
        if (!method.equals("GET") && !method.equals("HEAD")) return false;
        for (String e : LOOKS) if (path.endsWith(e)) return true;
        int m = path.lastIndexOf("/models/");
        return m >= 0 && path.indexOf('/', m + "/models/".length()) < 0;   // one model by its id
    }

    /** Where a library's own way through its node's gates begins, before the gate's path: {@code /lib/<library id>/gate/…}, {@code /lib/<id>/search/…}. */
    public static final String LIB = "/lib/";

    /** A gate's path without the library it is under ({@code /gate/…}, {@code /search/…}); the path as it is when it has none. */
    public static String inner(String path) {
        if (path == null || !path.startsWith(LIB)) return path;
        int slash = path.indexOf('/', LIB.length());
        return slash < 0 ? "" : path.substring(slash);
    }

    /** The library a gate's path is under; null when it names none. */
    public static String libraryOf(String path) {
        if (path == null || !path.startsWith(LIB)) return null;
        int slash = path.indexOf('/', LIB.length());
        String id = slash < 0 ? path.substring(LIB.length()) : path.substring(LIB.length(), slash);
        return id.matches("[A-Za-z0-9_-]{1,64}") ? id : null;
    }

    /** A request to {@code url}, marked with this thread's use and lane when it is a call that costs, at a node's gate. */
    public static HttpRequest.Builder request(String url) { return mark(url, HttpRequest.newBuilder(URI.create(url))); }

    /** A gate's refusal: the sentence the person is told (a cap reached, a call that is no use's), never tried again. A router that has nobody left to send a request to refuses the same way ({@code DriveClient.NotServed}). */
    public static class Refused extends IllegalStateException {
        public final String use;
        public Refused(String said) { this(said, null); }
        public Refused(String said, String use) { super(said); this.use = use; }
    }

    /** Told of a cap's refusal (402) of a use's call, before it is thrown: the library stops the work that use is, to resume it later. */
    public static volatile BiConsumer<String, String> CAPPED = (use, said) -> { };

    /** The mark a gate's refusal carries in its answer, so another server's 402 or 403 is not taken for one. */
    public static final String MARK = "rz_gate";

    /** A gate's refusal, thrown as its sentence; nothing for any other answer. */
    public static void throwIfRefused(int status, String body) {
        if ((status != 402 && status != 403) || body == null || !body.contains(MARK)) return;
        try {
            JsonNode j = new ObjectMapper().readTree(body);
            if (j.path(MARK).asBoolean(false)) {
                String said = j.path("error").path("message").asText("The service's gate refused the call."), use = j.path("use").asText("");
                if (status == 402 && !use.isEmpty()) CAPPED.accept(use, said);
                throw new Refused(said, use.isEmpty() ? null : use);
            }
        } catch (IOException notJson) { /* not a gate's */ }
    }

    /** A call marked with the use and the lane of what this thread is doing, when it is a call that costs at a node's gate. */
    public static HttpRequest.Builder mark(String url, HttpRequest.Builder b) {
        boolean helper = helper(url);
        if (!billable(url) && !helper) return webMark(url, b);   // a page of the web: its use for the web gate, when one is going
        Supplier<String[]> carried = CARRIED.get();
        String[] c = carried != null ? carried.get() : CONTEXT.get();
        if (c == null || c[0] == null || c[0].isBlank()) { if (!helper) UNMARKED.accept(url); return b; }
        b.header(USE, c[0]);
        b.header(LANE, c.length > 1 && WAITING.equals(c[1]) ? WAITING : BACKGROUND);
        if (c.length > 2 && c[2] != null && c[2].matches("[a-z]{2}(-[A-Za-z]{2,4})?")) b.header(LANG, c[2]);   // the language a refusal is said in
        return b;
    }
}
