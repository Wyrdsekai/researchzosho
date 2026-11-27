package org.researchzosho.librarian;

import org.researchzosho.tools.Outbound;
import org.researchzosho.Config;
import org.researchzosho.drive.Gated;
import org.researchzosho.drive.aws.Bedrock;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Whether search by meaning is on, in a sentence for the person and a word for the wire. An embeddings server that is configured
 * and stops answering turns every search into a search by words without a word to anyone: a library ran that way for weeks
 * (2026-10-04 — the server's container died on start on a card its image was not built for, and only `embed status` would have said
 * so).
 *
 * <p>The look at the server is made off every request (0.8.4, {@link KeptLook}): when the library starts, and again when a request finds
 * the last look a minute old, on a thread of its own, while that request answers with the last look's result. The status used to make
 * the look itself, so with the server down the first status of each minute waited five to ten seconds (6.2 measured, 2026-11-26).
 */
public final class SearchByMeaning {

    private SearchByMeaning() { }

    /**
     * {@code state} is {@code on}, {@code asleep}, {@code stopped}, {@code starting}, {@code not answering}, {@code off}, or {@code not looked at yet}
     * (before the first look has ended); {@code sentence} is the line a person reads; {@code lookedAt} when the look that found it ended,
     * null before the first; {@code rest} what the library knows of a server of this machine that it puts to sleep (asleep since when,
     * after how many idle minutes; starting; a start that failed, with its reason), null otherwise.
     */
    public record Standing(String state, String sentence, Instant lookedAt, ModelsAwake.Rest rest) {
        Standing(String state, String sentence, Instant lookedAt) { this(state, sentence, lookedAt, null); }
    }

    /**
     * The library stopped its embeddings server after its idle minutes without a search ({@link ModelsAwake}), and the next search starts
     * it again: the ordinary state of a library nobody searched for a while (0.8.4). It was said as "not answering", with the advice to
     * start by hand what the library starts by itself.
     */
    public static final String ASLEEP = "asleep";
    /** A start of the embeddings server made for a search is under way. */
    public static final String STARTING = "starting";
    /**
     * The embeddings server is a container of this machine's that is not running, with no record of why (stopped by a version before the
     * record was kept, a restart of the machine, a person): the next search starts it, as one the library put to sleep.
     */
    public static final String STOPPED = "stopped";

    /** The state before the first look has ended: from the library's start until its look ends, a few seconds at most. */
    public static final String NOT_YET = "not looked at yet";

    /** What the library knows of the server's rest, when it is a container of this machine it puts to sleep; a test hands in its own. */
    static volatile Function<String, ModelsAwake.Rest> rest = ModelsAwake::rest;

    /** The setting; a test hands in its own. */
    static volatile Supplier<String> setting = () -> { String e = Config.get("RESEARCHZOSHO_EMBED"); return e == null || e.isBlank() ? Helpers.address(Helpers.Helper.EMBED) : e; };   // unset: the machine that serves the helpers, when it serves this one
    /** Whether the embedder at the address gives a vector back; a test hands in its own. */
    static volatile Predicate<String> answers = base -> {
        if (Gated.gated(base)) return gatedAnswers(base);   // under an account service: asked what costs nothing, outside any use
        Embeddings.Embedder e = Bedrock.is(base) ? Embeddings.configured(false) : Embeddings.http(base.replaceAll("/+$", ""), Config.get("RESEARCHZOSHO_EMBED_MODEL", "embed"), false);
        float[] v = e.embed("ready"); return v != null && v.length > 0;   // a look: no use of a container of this machine, and no start of one
    };

    /**
     * How long the look waits for a connection. A server on this machine accepts one in under a millisecond, one on the local network or
     * the tailnet in a few, through a relay in a few hundred; two seconds leave room for one lost first packet, which is sent again after
     * a second. A server that has given no connection by then is down, and the look says so.
     */
    static final Duration LOOK_CONNECT = Duration.ofSeconds(2);
    /**
     * How long the look waits for the answer once connected. A server with its model loaded embeds one word in milliseconds, and one
     * still loading answers 503 at once; one that keeps the question while it loads its model (llama-swap) is said as not answering for
     * this look, and the next look, a minute later, finds it on.
     */
    static final Duration LOOK_ANSWER = Duration.ofSeconds(10);
    /** How often the look is made again, at most: when a request finds the last one this old. */
    static final long LOOK_EVERY_MS = 60_000;

    /** An embeddings server behind the node's gate answers when its models list does; a server without that list answers 404 there. */
    private static boolean gatedAnswers(String base) {
        try {
            HttpResponse<String> r = HTTP.send(Gated.request(Config.v1(base) + "/models").timeout(LOOK_ANSWER).GET().build(), HttpResponse.BodyHandlers.ofString());
            return r.statusCode() == 200 || r.statusCode() == 404 && !r.body().contains(Gated.MARK);
        } catch (IOException e) { return false; }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
    }
    private static final HttpClient HTTP = Outbound.client(HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(LOOK_CONNECT).build());

    private static final KeptLook<Standing> LOOK = new KeptLook<>("search-by-meaning-look", LOOK_EVERY_MS, SearchByMeaning::look);

    /** What is known now, at once: the last look's finding, or {@link #NOT_YET}. A look is started when the last one is a minute old. */
    public static Standing standing() { return said(LOOK.known()); }

    /**
     * For a command run once (the status in a terminal), which knows nothing before it asks: the look made now and waited for, up to
     * {@code wait}. The look's own waits end it within {@link #LOOK_CONNECT} and {@link #LOOK_ANSWER}.
     */
    public static Standing standingNow(Duration wait) { return said(LOOK.lookAndWait(wait)); }

    /** The look started now, off the caller's thread: when the library starts, so its first status knows. */
    public static void lookInBackground() { LOOK.start(); }

    private static Standing said(KeptLook.Seen<Standing> s) {
        if (s == null) return new Standing(NOT_YET, "search by meaning: not known yet — the library asks the embeddings server whether it answers when it starts and once a minute after that, and has no answer yet", null);
        return new Standing(s.value().state(), s.value().sentence(), Instant.ofEpochMilli(s.at()), s.value().rest());
    }

    /** The look itself: the setting, and whether the server it names answers. */
    static Standing look() {
        String e = setting.get();
        boolean off = e == null || e.isBlank() || e.equalsIgnoreCase("off") || e.equalsIgnoreCase("none");
        if (off) return new Standing("off", "search by meaning: off — no embeddings server is configured, so searches are by words only; `researchzosho embed start` runs one", null);
        ModelsAwake.Rest r;
        try { r = rest.apply(e); } catch (RuntimeException x) { r = null; }
        // asleep or starting: nothing is asked of it (a stopped container's address can take seconds to refuse), and nothing wakes it
        if (r != null && r.asleep()) return new Standing(ASLEEP, "search by meaning: asleep — the library stopped the embeddings server at " + e + " at " + ModelsAwake.HH_MM.format(Instant.ofEpochMilli(r.at()))
                + " after " + r.minutes() + (r.minutes() == 1 ? " minute" : " minutes") + " without a search, and the next search starts it again (that search goes by words while it starts)", null, r);
        if (r != null && r.stopped()) return new Standing(STOPPED, "search by meaning: stopped — the embeddings server at " + e + ", a container of this machine, is not running, and the next search starts it (that search goes by words while it starts)", null, r);
        if (r != null && r.starting()) return new Standing(STARTING, "search by meaning: starting — the library is starting the embeddings server at " + e + " for a search; searches go by words until it answers", null, r);
        boolean ok;
        try { ok = answers.test(e); } catch (Exception x) { ok = false; }
        if (ok) return new Standing("on", "search by meaning: on (the embeddings server at " + e + " answers)", null);
        String failed = r != null && r.failed() ? "; the library started it at " + ModelsAwake.HH_MM.format(Instant.ofEpochMilli(r.at())) + " for a search and it did not come up: " + r.why() : "";   // a start that failed is a failure, with its reason, never sleep
        return new Standing("not answering", "search by meaning: OFF — the embeddings server at " + e + " is configured but does not answer, so searches are by words only until it does" + failed + "; `researchzosho embed status` says more", null, r);
    }

    /** Forget the last look (a test, or after `embed start`): the next request starts a new one. */
    public static void reprobe() { LOOK.forget(); }

    /** Waits for a look under way to end; for a test. */
    static void settle() { LOOK.settle(Duration.ofSeconds(30)); }
}
