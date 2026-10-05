package org.researchzosho.librarian;

import org.researchzosho.Config;

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
    static volatile Supplier<String> setting = () -> Config.get("RESEARCHZOSHO_EMBED");
    /** Whether the configured embedder gives a vector back; a test hands in its own. */
    static volatile Predicate<String> answers = base -> { float[] v = Embeddings.configured().embed("ready"); return v != null && v.length > 0; };

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
