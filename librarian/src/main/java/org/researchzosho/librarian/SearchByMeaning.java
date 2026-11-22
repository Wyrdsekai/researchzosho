package org.researchzosho.librarian;

import org.researchzosho.tools.Outbound;
import org.researchzosho.Config;
import org.researchzosho.drive.Gated;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Whether search by meaning is on, in a sentence for the person and a word for the wire. An embeddings server that is configured
 * and stops answering turns every search into a search by words without a word to anyone: a library ran that way for weeks
 * (2026-10-04 — the server's container died on start on a card its image was not built for, and only `embed status` would have said
 * so). The standing is probed at most once a minute, so a status call stays quick.
 */
public final class SearchByMeaning {

    private SearchByMeaning() { }

    /** {@code state} is {@code on}, {@code not answering} or {@code off}; {@code sentence} is the line a person reads. */
    public record Standing(String state, String sentence) { }

    /** The setting; a test hands in its own. */
    static volatile Supplier<String> setting = () -> { String e = Config.get("RESEARCHZOSHO_EMBED"); return e == null || e.isBlank() ? Helpers.address(Helpers.Helper.EMBED) : e; };   // unset: the machine that serves the helpers, when it serves this one
    /** Whether the configured embedder gives a vector back; a test hands in its own. */
    static volatile Predicate<String> answers = base -> {
        if (Gated.gated(base)) return gatedAnswers(base);   // under an account service: asked what costs nothing, outside any use
        float[] v = Embeddings.configured().embed("ready"); return v != null && v.length > 0;
    };

    /** An embeddings server behind the node's gate answers when its models list does; a server without that list answers 404 there. */
    private static boolean gatedAnswers(String base) {
        try {
            HttpResponse<String> r = HTTP.send(Gated.request(Config.v1(base) + "/models").timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
            return r.statusCode() == 200 || r.statusCode() == 404 && !r.body().contains(Gated.MARK);
        } catch (IOException e) { return false; }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
    }
    private static final HttpClient HTTP = Outbound.client(HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build());

    private static final long PROBE_EVERY_MS = 60_000;
    private static volatile long probedAt = 0;
    private static volatile Standing probed;

    public static Standing standing() {
        String e = setting.get();
        boolean off = e == null || e.isBlank() || e.equalsIgnoreCase("off") || e.equalsIgnoreCase("none");
        if (off) return new Standing("off", "search by meaning: off — no embeddings server is configured, so searches are by words only; `researchzosho embed start` runs one");
        long now = System.currentTimeMillis();
        Standing p = probed;
        if (p != null && now - probedAt < PROBE_EVERY_MS) return p;
        boolean ok;
        try { ok = answers.test(e); } catch (Exception x) { ok = false; }
        p = ok ? new Standing("on", "search by meaning: on (the embeddings server at " + e + " answers)")
               : new Standing("not answering", "search by meaning: OFF — the embeddings server at " + e + " is configured but does not answer, so searches are by words only until it does; `researchzosho embed status` says more");
        probed = p; probedAt = now;
        return p;
    }

    /** Forget the last probe (a test, or after `embed start`). */
    public static void reprobe() { probedAt = 0; probed = null; }
}
