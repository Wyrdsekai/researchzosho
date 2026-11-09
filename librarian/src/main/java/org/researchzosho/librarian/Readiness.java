package org.researchzosho.librarian;

import org.researchzosho.Config;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * What {@code serve} says at start about the settings that are missing: one plain sentence each, with the command or the
 * setting that supplies it. A library runs without any of them; each one names something the person will otherwise find
 * out the hard way, on the first question, the first sign-in or the first search.
 */
public final class Readiness {
    private Readiness() { }

    public static List<String> missing(LibraryStore store, String host, int port, String driveUrl, boolean driveAnswers) throws IOException {
        return missing(store, host, port, driveUrl, driveAnswers, Config::get);
    }

    static List<String> missing(LibraryStore store, String host, int port, String driveUrl, boolean driveAnswers, Function<String, String> setting) throws IOException {
        List<String> out = new ArrayList<>();
        if (!driveAnswers) out.add("No model answers at " + driveUrl + ". Research and the chat need one: researchzosho models add <name> <host> <model id>, or RESEARCHZOSHO_DRIVE set to a server that answers.");
        if (Accounts.owner(store) == null) out.add("No account for the person who keeps the library yet. Make it with: researchzosho account set");
        boolean loopback = host.equals("127.0.0.1") || host.equals("localhost") || host.equals("::1");
        if (!loopback && !WebAccess.signInRequired()) out.add("The pages are open to anyone who can reach " + host + ":" + port + ". To require sign-in, run: researchzosho web signin on");
        if (!loopback && blank(setting.apply(Proxy.SETTING))) out.add("No proxy is trusted (" + Proxy.SETTING + "), so behind a proxy or a load balancer every browser looks like the proxy and no one is on https. Name the proxy's address or network there when there is one.");
        String half = Oidc.halfConfigured();
        if (half != null) out.add(half);
        if (blank(setting.apply("RESEARCHZOSHO_EMBED"))) out.add("No embeddings server is set, so search by meaning is off. RESEARCHZOSHO_EMBED names one.");
        if (blank(setting.apply("RESEARCHZOSHO_SERPER_KEY")) && blank(setting.apply("RESEARCHZOSHO_SERPAPI_KEY")) && blank(setting.apply("RESEARCHZOSHO_BRAVE_KEY")) && blank(setting.apply("RESEARCHZOSHO_SEARXNG")))
            out.add("No web search is set, so research reads the built-in fallback search alone. A Brave, Serper or SerpApi key, or a SearXNG address, opens the web.");
        return out;
    }

    private static boolean blank(String v) { return v == null || v.isBlank(); }
}
