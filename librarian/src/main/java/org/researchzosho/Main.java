package org.researchzosho;

import org.researchzosho.librarian.LibrarianCli;
import org.researchzosho.librarian.Webhooks;

import java.util.logging.Level;
import java.util.logging.Logger;
import org.slf4j.LoggerFactory;
/**
 * ResearchZosho — 研究蔵書, the research holdings. "The Research Harness For The Rest Of Us."
 * The Librarian is its voice. This is the {@code researchzosho} command ({@code zosho} for
 * short); {@code codezaiku librarian …} is an alias of it, not the other way round (the operator,
 * 2026-09-05). Its own repo since 2026-09-06.
 */
public final class Main {

    private Main() { }

    /** Lucene's startup note about the JDK's vector module is noise on every command; held strongly so the JDK keeps the setting. */
    private static final Logger QUIET = Logger.getLogger("org.apache.lucene.internal.vectorization");
    static {
        QUIET.setLevel(Level.SEVERE);
    }

    /** On a person's terminal the search steerer's notes are noise; in the service's log they are the audit trail. */
    static void quietForTerminal(String verb) {
        if ("serve".equals(verb) || "mcp".equals(verb)) return;
        try {
            var l = LoggerFactory.getLogger("org.researchzosho.tools.SearchSteer");
            if (l instanceof ch.qos.logback.classic.Logger lb) lb.setLevel(ch.qos.logback.classic.Level.WARN);
        } catch (Throwable ignored) { }
    }

    public static void main(String[] args) {
        System.exit(runToExit(args));
    }

    /**
     * {@link #run}, then what must happen before the process ends: the webhook posts for the changes the verb made go out first,
     * because {@code System.exit} ends their threads where they stand.
     */
    public static int runToExit(String[] args) {
        HttpSettings.apply();   // before the first connection: the pool's idle limit is read once, JVM-wide
        int code = run(args);
        if (Webhooks.Sent.any()) Webhooks.flush(Webhooks.FLUSH_MS);   // a command that changed nothing has nothing to wait for
        return code;
    }

    /** {@code researchzosho <verb> …} → the librarian's verbs; returns the exit code. */
    public static int run(String[] args) {
        quietForTerminal(args.length > 0 ? args[0] : "");
        String[] withVerb = new String[args.length + 1];
        withVerb[0] = "librarian";
        System.arraycopy(args, 0, withVerb, 1, args.length);
        // RESEARCHZOSHO_DRIVE, else the CodeZaiku spellings (librarian.drive, then drive) — see Config.
        String drive = Config.get("RESEARCHZOSHO_DRIVE", "http://localhost:8200");
        return LibrarianCli.run(withVerb, drive, Config.get("RESEARCHZOSHO_MODEL", "local-model"));
    }
}
