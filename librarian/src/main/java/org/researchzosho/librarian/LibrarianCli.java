package org.researchzosho.librarian;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import org.researchzosho.drive.DriveClient;

/**
 * {@code researchzosho <verb>} — the Librarian's own command surface, in its own class:
 * the bounded-module seam a separate repo would cut along.
 */
public final class LibrarianCli {

    private LibrarianCli() { }

    static final String USAGE = """
            usage: researchzosho <verb>   (zosho <verb> · codezaiku librarian <verb> are the same command)
              setup [--yes]                the first ten minutes: where the library goes, which model reads, the service, Claude Code, a first document
              version                      which ResearchZosho this is
              init                         create the library (the consent act — research runs then submit)
              status                       counts, drafts, stale reviews, problems
              name [<name…>]               the library's name, shown on its pages and to programs (the folder's until set)
              refresh                      re-index what changed or was removed on disk (seconds)
              rebuild                      rebuild the whole search index + INDEX.md from files (re-embeds everything)
              ask <question…>              what the library has on it, with sources and states
              add <file|url> [title]       add your own document (PDF/DOCX/PPTX/ODT/EPUB/HTML/text)
              add <folder> [--collection N] [--register]   add every document under the folder, as a named collection
              add <file> --for <url>       supply a document the runner could not read (a paywall, a wall) — see `requests`
              collection list|add <name> <folder>|rescan     the registered folders (the housekeeping rescans them)
              requests                     what the runner could not read and asks you for
              vault [--out <dir>]          the library as a folder an editor opens (Obsidian, SoloMD): notes and links, kept up to date
              directory publish <dir-url> --token <t> --url <my-url> · find <dir-url> <query…> · list
                                           a directory is a library of listings; publish yours there, or search one for libraries to ask
              peer list · add <name> <url> <token> [--group a,b] · remove <name> · ask <name|group|all> <question…>
                                           other libraries this one may ask; their answers stay labelled as theirs (one hop, never merged)
              research ask "<question…>" [--depth] [--quick] [--shelves|--web] [--max-turns N] [--max-minutes N]
                                           send a question; the service picks it up within seconds
              research                     how runs share the model: workers, pause, window; today's steps by reader; what is running
              research workers <n> · pause · resume · stop <J-…> · window <HH:MM-HH:MM|off>    change them live — a running question follows at its next step; stop ends one run there
              bib <id…>                    BibTeX for everything a finding or investigation cites (DOI / arXiv / PubMed resolved)
              perspectives <question…>     who studies this and what each would ask — sub-questions for the brief
              web [status | signin on|off]  the pages in a browser: open to everyone as shipped; signin on asks for a reader token before sending questions
              sources [list | trust <host> [why] | ban <host> [why] | forget <host>]
                                           your own word on sources: trusted ones count as primary; refused ones are dropped from searches and reviews
              settle [<I-…>…]              extract a write-up's claims, file them under subjects, add them to the map, now; all waiting write-ups when none is named
              export <id> [--pdf|--md] [--beginner|--familiar] [--out FILE]
                                           an entry, or a reading of it, as a Markdown or PDF file
              sharpen <question…>          a rough question in, a better one out — a brief to run, what it assumed, what you already hold; runs nothing
              search [status|start|stop|test <query>|papers <query>]   which web search backend answers; SearXNG through Docker; the literature by DOI
              models [--all]               which model to run on this machine's card, measured, with the command that serves it
              model install|status|stop|uninstall
                                           the model on this machine, on demand: it comes up when a run needs it and goes away after 20 idle
                                           minutes (install [--file <gguf>] [--gpu <index>] [--idle-minutes N] [--share])
              embed [status|start [port] [--cpu]|stop|test]   the embeddings server (search by meaning): what is configured; Text Embeddings Inference through Docker
              explain <id> [--beginner|--familiar|--written] [--fresh]
                                           an entry explained for a reader at that level — from the library only, checked
              explain "<term>" [--in <id>] [--now|--full]
                                           a term as it is used in an entry; when the library does not explain it, a quick look or full research
              map <name|id> [--depth N]    the graph around a person, place, work or concept — edges are findings
              graph merge <a> <b> · alias <node> <name…> · kind <node> <kind> [--private|--public] · link <node> <Qid> · proposals
              profile list · enable <name> · disable <name>       fields on top of the core (science on by default; genealogy)
              <profile> <verb…>            a profile's own verbs, e.g. `genealogy import tree.ged`
              review [drive]               the held-out review pass over draft investigations
              inbox [--report I-…] [--subject s] [--kind k] [--tier t] [--confidence c] [--writer w] [--state draft|stale] [--language x] [--grep words] [--by-report]   what is waiting for you: drafts and stale reviews
              accept <id…>|--all|--report I-… · dispute <id> <why> · retire <id…>|--report I-…   your decisions on claims
              catalog [drive] [--accept-all]   ground findings to the vocabulary; propose new subjects
              shelf add <name> <query> [days] · list · every <name> <days> · park|unpark <name> · remove <name>   the searches the housekeeping keeps
              serials                      check living shelves for NEW sources; list overdue reviews
              tonight                      what the housekeeping will do at its next run: searches due, questions it will research
              update [now | auto on|off]   the installed release against the latest; install it; let the daemon do it after the housekeeping
              questions [list [--type t] [--parked|--all] [--report I-…] [--fate f] [--who text] [--subject s] [--language x] [--grep words] [--by-report] [--hints] | add <q…> | next|later|park|unpark|drop <n> | tidy | budget <n> | tonight <n>]   the queue the explorer draws from; a report's leftovers wait parked
              bench [k]                    top-k retrieval failure rate on the shelf's own agent queries
              subjects                     the vocabulary with counts and co-occurring subjects (the edge list)
              subjects proposed            the subjects the cataloger proposed, numbered
              subjects accept <n|slug>…    take proposals into the vocabulary (then `settle` files the claims under them); `all` takes every one
              subjects drop <n|slug>…      throw proposals away; `all` clears the list
              abstract [drive] [subject…]  the abstracting crew: (re)write per-subject shelf articles
              enrich [drive] [N]           generated contextual enrichment of raw chunks (N raw files, 0=all)
              reader [list|allow <did> <level> [name]|deny <did>|remove <did>|default <level>|token <did>|webhook …]
              reader requests · approve <R-id> [read|write] · deny <R-id> [why]     access requests, and your answer
                                           who may read and write (deny|read|write); `token` makes a bearer token for a program (`patron` is the same verb)
              serve [--host H] [--port P] [--crew-hour H|--no-crews]
                                           the service: the protocol over HTTP (default 127.0.0.1:4649), research
                                           runs, and the housekeeping (default 03:00 local)
              crews [--weekly] [--monthly]  run the housekeeping once, now (serials · explorer · review · inventory · abstracts · enrich · heat ·
                                           [weekly: duplicates · orphans] · refresh|rebuild · backup)
              raw prune                    delete the captures the weekly orphans report listed (catalog/orphans.md)
              service install|uninstall|status [--exec <launcher>] [--host H] [--port P] [--crew-hour H]
                                           run the daemon as a user service: systemd (Linux), LaunchAgent (macOS), logon task (Windows)
              mcp                          MCP over stdio with ONLY the library tools (for Claude Code and other hosts)
              probe <query…>               why the desk answered as it did: each arm's raw scores and the floor
            """;

    /** `librarian patron …` — the allow-list in catalog/patrons.md, and this library's identity. */
    static int patron(LibraryStore store, String[] args) throws IOException {
        String op = args.length > 2 ? args[2] : "list";
        switch (op) {
            case "list" -> {
                var id = store.identity();
                System.out.println("library " + id.id() + " — " + id.name() + " (contract " + LibraryProtocol.CONTRACT + ")");
                var pol = Patrons.load(store);
                System.out.println("default: " + pol.dflt() + "   (anonymous and unlisted patrons)");
                for (var e : pol.listed()) System.out.println("- " + e.did() + " — " + (e.name().isEmpty() ? "(unnamed)" : e.name()) + " — " + e.level());
                if (pol.listed().isEmpty()) System.out.println("(no patrons listed — " + Patrons.file(store) + ")");
            }
            case "allow" -> {
                if (args.length < 5) { System.err.println("usage: researchzosho patron allow <did> <deny|read|write> [name…]"); return 2; }
                Patrons.Level lvl;
                try { lvl = Patrons.Level.valueOf(args[4]); } catch (IllegalArgumentException e) { System.err.println("level must be deny, read or write"); return 2; }
                String name = args.length > 5 ? String.join(" ", java.util.Arrays.copyOfRange(args, 5, args.length)) : "";
                Patrons.set(store, args[3], name, lvl);
                System.out.println(args[3] + " → " + lvl);
            }
            case "remove" -> {
                if (args.length < 4) { System.err.println("usage: researchzosho patron remove <did>"); return 2; }
                Patrons.remove(store, args[3]);
                System.out.println(args[3] + " removed (falls back to default)");
            }
            case "token" -> {
                if (args.length < 4) { System.err.println("usage: researchzosho reader token <did>"); return 2; }
                String token = Patrons.issueToken(store, args[3]);
                System.out.println("bearer token for " + args[3] + " (shown once; only its hash is kept):");
                System.out.println(token);
            }
            case "default" -> {
                if (args.length < 4) { System.err.println("usage: researchzosho patron default <deny|read|write>"); return 2; }
                try { Patrons.setDefault(store, Patrons.Level.valueOf(args[3])); } catch (IllegalArgumentException e) { System.err.println("level must be deny, read or write"); return 2; }
                System.out.println("default → " + args[3]);
            }
            case "requests" -> {
                var all = AccessRequests.list(store);
                long pending = all.stream().filter(r -> r.state().equals("pending")).count();
                if (all.isEmpty()) { System.out.println("no access requests"); return 0; }
                for (var r : all) System.out.println(r.id() + "  " + r.date() + "  [" + r.state() + "]  " + r.did() + (r.name().isEmpty() ? "" : " (" + r.name() + ")") + (r.note().isEmpty() ? "" : "  — " + r.note()) + (r.reason().isEmpty() ? "" : "  reason: " + r.reason()));
                System.out.println(pending + " waiting  ·  reader approve <id> [read|write]  ·  reader deny <id> [why]");
            }
            case "approve" -> {
                if (args.length < 4) { System.err.println("usage: researchzosho reader approve <R-id> [read|write]"); return 2; }
                Patrons.Level lvl = Patrons.Level.read;
                if (args.length > 4) { try { lvl = Patrons.Level.valueOf(args[4]); } catch (IllegalArgumentException e) { System.err.println("level must be read or write"); return 2; } }
                var r = AccessRequests.approve(store, args[3], lvl);
                System.out.println("approved " + r.id() + ": " + r.did() + " may " + lvl + ". They collect their token with the claim secret they were given.");
            }
            case "deny" -> {
                if (args.length >= 4 && args[3].startsWith("R-")) {
                    var r = AccessRequests.deny(store, args[3], args.length > 4 ? String.join(" ", java.util.Arrays.asList(args).subList(4, args.length)) : "");
                    System.out.println("denied " + r.id() + " (" + r.did() + ")");
                    return 0;
                }
                if (args.length < 4) { System.err.println("usage: researchzosho reader deny <did>   or   reader deny <R-id> [why]"); return 2; }
                Patrons.set(store, args[3], null, Patrons.Level.deny);
                System.out.println(args[3] + " → deny");
            }
            case "webhook", "webhooks" -> {
                String sub = args.length > 3 ? args[3] : "list";
                switch (sub) {
                    case "list" -> { for (var h : Webhooks.list(store)) System.out.println(h.did() + "  " + h.url() + "  " + (h.kinds().isEmpty() ? "all" : String.join(",", h.kinds()))); }
                    case "add" -> {
                        if (args.length < 6) { System.err.println("usage: researchzosho reader webhook add <did> <url> [--secret <s>] [--events a,b]"); return 2; }
                        String secret = ""; java.util.List<String> kinds = new java.util.ArrayList<>();
                        for (int i = 6; i < args.length; i++) {
                            if (args[i].equals("--secret")) secret = flagValue(args, i++);
                            else if (args[i].equals("--events")) kinds = java.util.List.of(flagValue(args, i++).split(","));
                        }
                        if (secret.isEmpty()) { byte[] b = new byte[24]; new java.security.SecureRandom().nextBytes(b); secret = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(b); }
                        Webhooks.add(store, args[4], args[5], secret, kinds);
                        System.out.println("webhook for " + args[4] + " → " + args[5] + "\n  secret: " + secret + "   (posts carry X-ResearchZosho-Signature: sha256=hmac-sha256(secret, body))");
                    }
                    case "remove" -> {
                        if (args.length < 6) { System.err.println("usage: researchzosho reader webhook remove <did> <url>"); return 2; }
                        System.out.println(Webhooks.remove(store, args[4], args[5]) ? "removed" : "no such webhook");
                    }
                    default -> { System.err.println("usage: researchzosho reader webhook list | add <did> <url> [--secret s] [--events a,b] | remove <did> <url>"); return 2; }
                }
            }
            default -> { System.err.println("usage: researchzosho reader [list|allow|deny|remove|default|token|webhook]"); return 2; }
        }
        return 0;
    }

    static int graph(LibraryStore store, String[] args) throws Exception {
        if (args.length < 3) { System.err.println("usage: researchzosho graph merge <a> <b> | alias <node> <name…> | kind <node> <kind> [--private|--public] | link <node> <Qid> | proposals"); return 2; }
        switch (args[2]) {
            case "merge" -> {
                if (args.length < 5) { System.err.println("usage: researchzosho graph merge <from> <into>"); return 2; }
                Graph.merge(store, args[3], args[4], "person");
                System.out.println("merged " + args[3] + " → " + args[4]);
            }
            case "alias" -> {
                if (args.length < 5) { System.err.println("usage: researchzosho graph alias <node> <name…>"); return 2; }
                Graph.alias(store, args[3], java.util.Arrays.asList(args).subList(4, args.length));
                System.out.println("aliased");
            }
            case "kind" -> {
                if (args.length < 5) { System.err.println("usage: researchzosho graph kind <node> <kind> [--private|--public]"); return 2; }
                Boolean priv = java.util.Arrays.asList(args).contains("--private") ? Boolean.TRUE : java.util.Arrays.asList(args).contains("--public") ? Boolean.FALSE : null;
                Graph.setKind(store, args[3], args[4], priv);
                System.out.println("set");
            }
            case "link" -> {
                if (args.length < 5) { System.err.println("usage: researchzosho graph link <node> <Qid>"); return 2; }
                Graph.link(store, args[3], args[4]);
                System.out.println("linked");
            }
            case "proposals" -> {
                System.out.println(Graph.propose(store));
                Path p = Graph.dir(store).resolve("proposals.md");
                if (Files.exists(p)) System.out.print(Files.readString(p));
            }
            default -> { System.err.println("unknown graph verb " + args[2]); return 2; }
        }
        return 0;
    }

    static int profile(LibraryStore store, String[] args) throws Exception {
        String op = args.length > 2 ? args[2] : "list";
        switch (op) {
            case "list" -> {
                var on = Profiles.enabled(store);
                for (Profile p : Profiles.all()) System.out.println((on.contains(p.name()) ? "[on]  " : "[off] ") + p.name() + " — " + p.description() + (p.usage().isEmpty() ? "" : "\n              " + p.usage()));
            }
            case "enable" -> { if (args.length < 4) { System.err.println("usage: researchzosho profile enable <name>"); return 2; } Profiles.enable(store, args[3]); System.out.println("enabled " + args[3]); }
            case "disable" -> { if (args.length < 4) { System.err.println("usage: researchzosho profile disable <name>"); return 2; } Profiles.disable(store, args[3]); System.out.println("disabled " + args[3]); }
            default -> { System.err.println("usage: researchzosho profile list|enable <name>|disable <name>"); return 2; }
        }
        return 0;
    }

    /** The value after a flag, or an IllegalArgumentException that names the flag (never "librarian: null"). */
    static String flagValue(String[] args, int i) {
        if (i + 1 >= args.length || args[i + 1].startsWith("--")) throw new IllegalArgumentException(args[i] + " needs a value");
        return args[i + 1];
    }

    static int flagInt(String[] args, int i) {
        String v = flagValue(args, i);
        try { return Integer.parseInt(v); } catch (NumberFormatException e) { throw new IllegalArgumentException(args[i] + " needs a number, not " + v); }
    }

    /** `librarian serve` — the daemon; blocks until killed. */
    static int serve(LibraryStore store, String[] args, String baseUrl, String model) throws Exception {
        String host = "127.0.0.1"; int port = LibrarianDaemon.DEFAULT_PORT; int hour = 3; Path log = null;
        try {
            for (int i = 2; i < args.length; i++) {
                switch (args[i]) {
                    case "--host" -> host = flagValue(args, i++);
                    case "--port" -> port = flagInt(args, i++);
                    case "--crew-hour" -> hour = flagInt(args, i++);
                    case "--no-crews" -> hour = -1;
                    case "--log" -> log = Path.of(flagValue(args, i++));
                    default -> { System.err.println("unknown option " + args[i]); return 2; }
                }
            }
        } catch (IllegalArgumentException e) {
            System.err.println("usage: researchzosho serve [--host H] [--port N] [--crew-hour H|--no-crews] [--log FILE] — " + e.getMessage()); return 2;
        }
        if (log != null) {
            // the service's own log: rotated at 20MB on start (and nightly), appended otherwise
            Files.createDirectories(log.getParent());
            Service.rotate(log, 20L * 1024 * 1024);
            var ps = new java.io.PrintStream(Files.newOutputStream(log, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND), true, java.nio.charset.StandardCharsets.UTF_8);
            System.setOut(ps); System.setErr(ps);
        }
        LibrarianDaemon d = LibrarianDaemon.start(store, host, port, baseUrl, model, hour);
        Service.recordPid();
        var id = store.identity();
        System.out.println("The Librarian is at " + d.url() + "  (" + id.id() + " — " + id.name() + ", contract " + LibraryProtocol.CONTRACT + ")");
        System.out.println("  drive " + baseUrl + (Crews.driveAnswers(baseUrl) ? " answers" : " does not answer — research and the model crews wait for it"));
        System.out.println("  jobs: " + d.describeWorkers() + "  (RESEARCHZOSHO_JOB_WORKERS, RESEARCHZOSHO_JOB_DRIVES)");
        System.out.println(hour < 0 ? "  housekeeping: off" : "  housekeeping: daily at " + String.format("%02d:00", hour) + " local (catalog/crews.log)");
        System.out.println("  tokens: researchzosho reader token <did>");
        d.join();
        return 0;
    }

    /** Runs the verb; returns the process exit code. */
    public static int run(String[] args, String baseUrl, String model) {
        // whatever the verb changed, the vault (when the person has one) follows before the prompt comes back
        long before = 0; LibraryStore watched = null;
        try { watched = LibraryStore.open(); if (Vault.exists(watched)) before = Vault.newest(watched); } catch (Exception ignored) { }
        int rc = dispatch(args, baseUrl, model);
        if (watched != null && before != 0 && !(args.length > 1 && args[1].equals("vault"))) { try { Vault.followUp(watched, before); } catch (Exception ignored) { } }
        return rc;
    }

    static int dispatch(String[] args, String baseUrl, String model) {
        if (args.length < 2) { System.err.print(USAGE); return 2; }
        LibraryStore store = LibraryStore.open();
        String verb = args[1];
        if (verb.equals("--version") || verb.equals("version") || verb.equals("-V")) { System.out.println("researchzosho " + org.researchzosho.Version.string()); return 0; }
        if (verb.equals("setup")) {
            boolean yes = false, service = true, claude = true; int port = LibrarianDaemon.DEFAULT_PORT;
            for (int i = 2; i < args.length; i++) {
                switch (args[i]) {
                    case "--yes", "-y" -> yes = true;
                    case "--no-service" -> service = false;
                    case "--no-claude" -> claude = false;
                    case "--port" -> port = flagInt(args, i++);
                    default -> { System.err.println("usage: researchzosho setup [--yes] [--port N] [--no-service] [--no-claude]"); return 2; }
                }
            }
            try {
                return new Setup(new java.io.BufferedReader(new java.io.InputStreamReader(System.in, java.nio.charset.StandardCharsets.UTF_8)), System.out,
                        Setup.liveProbe(), Setup.liveActs(), yes).run(port, service, claude);
            } catch (Exception e) { System.err.println("setup: " + e.getMessage()); return 1; }
        }
        try {
            if (verb.equals("model")) return ModelServer.command(args, 2, System.out);   // the machine's model server: no library needed
            if (!verb.equals("init") && !Files.isDirectory(store.root())) {
                // an MCP client starting `researchzosho mcp` on a machine with no library (npx @wyrdsekai/researchzosho-mcp,
                // 2026-09-11) never sees a refusal on stdout: the library is made, and stderr says so
                if (verb.equals("mcp")) {
                    store.init();
                    System.err.println("[researchzosho-mcp] made a new library at " + store.root() + " (there was none; `researchzosho setup` names a model and a service)");
                } else {
                    System.out.println("no library at " + store.root() + " — run `researchzosho init` to create one");
                    return verb.equals("status") ? 0 : 1;
                }
            }
            switch (verb) {
                case "models" -> {
                    // which model to run on this card: measured, with the command that serves it
                    System.out.print(java.util.Arrays.asList(args).contains("--all") ? Models.describeAll() : Models.describe(Models.cardGb()));
                    return 0;
                }
                case "name" -> {
                    // the library's name, on its pages and in every answer; the folder's name until set
                    if (args.length < 3) { var id = store.identity(); System.out.println(id.name() + "  (" + id.id() + "; researchzosho name <a name…> changes it; the folder is " + store.root() + ")"); return 0; }
                    String n = String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length)).strip();
                    store.setName(n);
                    System.out.println(n.isEmpty() ? "the library is named after its folder again: " + store.identity().name() : "the library is now called " + store.identity().name() + " (catalog/library.md carries it; the pages show it after their next load)");
                    return 0;
                }
                case "init" -> {
                    store.init();
                    System.out.println("library initialized at " + store.root());
                    System.out.println("research runs (fan + delegates) now submit drafts here.");
                }
                case "status" -> { status(store); String up = org.researchzosho.Version.updateNotice(); if (!up.isEmpty()) System.out.println("\n" + up); }
                case "refresh" -> {
                    // what changed on disk since the last pass, and what is gone — seconds, where a rebuild re-embeds everything
                    int n = new LibrarianIndex(store).refresh();
                    store.regenerateIndex();
                    System.out.println("refreshed " + n + " entr" + (n == 1 ? "y" : "ies") + " (changed or removed files); catalog/INDEX.md regenerated");
                    return 0;
                }
                case "rebuild" -> {
                    String blocker = LibrarianIndex.rebuildBlocker(Embeddings.configured());
                    if (blocker != null) { System.out.println("not rebuilt: " + blocker); return 1; }
                    System.out.println("rebuilding: every entry is re-embedded (20 minutes for a thousand entries); researchzosho refresh takes only what changed");
                    int migrated = store.migrateReviewHashes();
                    int n = new LibrarianIndex(store).rebuild();
                    store.regenerateIndex();
                    System.out.println("re-indexed " + n + " entries; catalog/INDEX.md regenerated"
                            + (migrated > 0 ? "; " + migrated + " review signature(s) carried over to the current hash formula" : ""));
                }
                case "add" -> { return add(store, args); }
                case "ask" -> {
                    if (args.length < 3) { System.err.println("usage: researchzosho ask <question…>   — the desk: what the shelves hold, or holds_nothing"); return 2; }
                    System.out.println(LibraryPush.answerPackage(String.join(" ", java.util.Arrays.asList(args).subList(2, args.length)), 6).stripTrailing());
                }
                case "map" -> {
                    if (args.length < 3) { System.err.println("usage: researchzosho map <name|id> [--depth N] [--k N]"); return 2; }
                    int depth = 1, k = 25; java.util.List<String> focus = new java.util.ArrayList<>();
                    for (int i = 2; i < args.length; i++) {
                        if (args[i].equals("--depth")) depth = flagInt(args, i++);
                        else if (args[i].equals("--k")) k = flagInt(args, i++);
                        else focus.add(args[i]);
                    }
                    System.out.print(Graph.render(Graph.build(store).around(String.join(" ", focus), depth, k, true)));
                }
                case "collection" -> {
                    String op = args.length > 2 ? args[2] : "list";
                    switch (op) {
                        case "list" -> { for (var e : Corpus.registered(store).entrySet()) System.out.println(e.getKey() + " — " + e.getValue()); }
                        case "add" -> { if (args.length < 5) { System.err.println("usage: researchzosho collection add <name> <folder>"); return 2; } Corpus.register(store, args[3], Path.of(args[4])); System.out.println(Corpus.rescan(store)); }
                        case "rescan" -> System.out.println(Corpus.rescan(store));
                        default -> { System.err.println("usage: researchzosho collection list | add <name> <folder> | rescan"); return 2; }
                    }
                }
                case "research" -> { return research(store, args); }
                case "explain" -> { return explain(store, args); }
                case "sharpen" -> {
                    if (args.length < 3) { System.err.println("usage: researchzosho sharpen <question…>"); return 2; }
                    String q = String.join(" ", java.util.Arrays.asList(args).subList(2, args.length));
                    System.err.print("  … reading what the library holds, asking who studies this, writing the brief"); System.err.flush();
                    Sharpen.Sharpened s;
                    try { s = Sharpen.run(store, Explain.drive(), Researcher.webTools(), q); }
                    catch (ProtocolError e) { System.err.println(); System.err.println(e.getMessage()); return 1; }
                    System.err.print("\r" + " ".repeat(90) + "\r");
                    System.out.print(Sharpen.text(s));
                    Path out = Path.of("sharpened-question.txt");
                    Files.writeString(out, s.researchQuestion(), java.nio.charset.StandardCharsets.UTF_8);
                    System.out.println("\nthe question to run is in " + out + " — edit it if you like, then send it:");
                    System.out.println("  researchzosho research ask \"$(cat " + out + ")\"" + (s.mode().equals("depth") ? " --depth" : "") + (s.size().equals("quick") ? " --quick" : ""));
                    System.out.println("  (or paste it into the Research page, or pass it to library_research)");
                }
                case "embed" -> {
                    // the embeddings server: what is configured, and Text Embeddings Inference through Docker by hand
                    String op = args.length > 2 ? args[2] : "status";
                    switch (op) {
                        case "status" -> {
                            String e = org.researchzosho.Config.get("RESEARCHZOSHO_EMBED");
                            boolean off = e == null || e.isBlank() || e.equalsIgnoreCase("off") || e.equalsIgnoreCase("none");
                            System.out.println("  embeddings server: " + (off ? "none (search is by words only)" : e + " " + (Embed.answers(e) ? "answers" : "does not answer")));
                            System.out.println("  docker container " + Embed.CONTAINER + ": " + Embed.state() + (Searx.haveDocker() ? "; image for this machine: " + Embed.tag() : " (no docker here)"));
                            System.out.println("  researchzosho embed start [port] [--cpu] starts one with Docker (" + Embed.MODEL + "); embed test measures it.");
                            return 0;
                        }
                        case "start" -> {
                            boolean cpu = java.util.Arrays.asList(args).contains("--cpu");
                            int port = Embed.DEFAULT_PORT;
                            for (int i = 3; i < args.length; i++) if (args[i].matches("\\d+")) port = Integer.parseInt(args[i]);
                            if (!cpu && Searx.haveDocker() && !Embed.gpu()) {
                                System.out.println("no NVIDIA GPU that Docker can use here. The CPU image runs the same model on one core — measured at a tenth of a chunk a second, so a library of a thousand documents takes more than a day to index. researchzosho embed start --cpu runs it anyway; an embeddings server on another machine (RESEARCHZOSHO_EMBED) is the better answer.");
                                return 1;
                            }
                            System.out.print("starting the embeddings server through docker (the model downloads on the first start, about 1.2 GB)… "); System.out.flush();
                            String r = Embed.start(port, cpu);
                            if (r.startsWith("!")) { System.out.println("no: " + r.substring(1)); return 1; }
                            org.researchzosho.Config.set("RESEARCHZOSHO_EMBED", r);
                            System.out.println("it answers at " + r + " (saved as RESEARCHZOSHO_EMBED; the container restarts with the machine). Search by meaning is on; researchzosho rebuild indexes the library with it.");
                            return 0;
                        }
                        case "stop" -> { String r = Embed.stop(); System.out.println(r.startsWith("!") ? r.substring(1) : "the embeddings server stopped (researchzosho embed start brings it back)"); return r.startsWith("!") ? 1 : 0; }
                        case "test" -> {
                            var e = Embeddings.configured();
                            if ("none".equals(e.modelId())) { System.out.println("no embeddings server is configured (researchzosho embed start)"); return 1; }
                            var texts = new java.util.ArrayList<String>();
                            for (int i = 0; i < 64; i++) texts.add(("The museum states that the gears of the mechanism were cut with hand files against a dividing plate, and the tooth profiles measured by computed tomography show triangular teeth of uneven pitch, entry " + i + ". ").repeat(4));
                            e.embedAll(texts.subList(0, 4));
                            long t0 = System.currentTimeMillis(); int got = 0;
                            for (var v : e.embedAll(texts)) if (v != null && v.length > 0) got++;
                            long ms = System.currentTimeMillis() - t0;
                            if (got < 64) { System.out.println("  " + (64 - got) + " of 64 texts came back without a vector: the server at " + e.modelId().replaceFirst("^.*@", "") + " is not answering embeddings (researchzosho embed status; docker logs " + Embed.CONTAINER + ")"); return 1; }
                            System.out.printf("  64 texts of about 350 tokens embedded in %d ms: %.0f texts a second (%s)%n", ms, 64000.0 / Math.max(1, ms), e.modelId());
                            return 0;
                        }
                        default -> { System.err.println("usage: researchzosho embed [status | start [port] [--cpu] | stop | test]"); return 2; }
                    }
                }
                case "search" -> {
                    // which backend a search would use, and SearXNG through Docker by hand
                    String op = args.length > 2 ? args[2] : "status";
                    switch (op) {
                        case "status" -> {
                            String bk = org.researchzosho.Config.get("RESEARCHZOSHO_BRAVE_KEY");
                            System.out.println("  Brave Search API: " + (bk == null || bk.isBlank() ? "no key (RESEARCHZOSHO_BRAVE_KEY)" : "a key is set; used first"));
                            String ep = org.researchzosho.tools.WebSearchTool.endpoint();
                            System.out.println("  SearXNG: " + ep + " " + (Searx.answers(ep) ? "answers" : "does not answer") + "; docker container " + Searx.CONTAINER + ": " + Searx.state() + (Searx.haveDocker() ? "" : " (no docker here)"));
                            System.out.println("  built-in fallback: " + (org.researchzosho.tools.WebSearchTool.fallbackOn() ? "on (RESEARCHZOSHO_FALLBACK_SEARCH=off turns it off)" : "off"));
                            System.out.println("  order: Brave, then SearXNG, then the fallback. `researchzosho search test \"a query\"` shows which one answers.");
                            return 0;
                        }
                        case "start" -> {
                            boolean fresh = java.util.Arrays.asList(args).contains("--fresh");
                            int port = Searx.DEFAULT_PORT;
                            for (int i = 3; i < args.length; i++) if (args[i].matches("\\d+")) port = Integer.parseInt(args[i]);
                            System.out.print("starting SearXNG through docker… "); System.out.flush();
                            String r = Searx.start(port, fresh);
                            if (r.startsWith("!")) { System.out.println("no: " + r.substring(1)); return 1; }
                            org.researchzosho.Config.set("RESEARCHZOSHO_SEARXNG", r);
                            System.out.println("it answers at " + r + " (saved as RESEARCHZOSHO_SEARXNG; the container restarts with the machine)");
                            if (!Searx.settingsNote.isEmpty()) System.out.println("  " + Searx.settingsNote);
                            return 0;
                        }
                        case "stop" -> { String r = Searx.stop(); System.out.println(r.startsWith("!") ? r.substring(1) : "SearXNG stopped (researchzosho search start brings it back)"); return r.startsWith("!") ? 1 : 0; }
                        case "test" -> {
                            if (args.length < 4) { System.err.println("usage: researchzosho search test <query…>"); return 2; }
                            String q = String.join(" ", java.util.Arrays.copyOfRange(args, 3, args.length));
                            var tool = new org.researchzosho.tools.WebSearchTool();
                            var M = new com.fasterxml.jackson.databind.ObjectMapper();
                            int b0 = org.researchzosho.tools.WebSearchTool.BRAVE_USED.get(), s0 = org.researchzosho.tools.WebSearchTool.SEARXNG_USED.get(), f0 = org.researchzosho.tools.WebSearchTool.FALLBACK_USED.get();
                            long t0 = System.currentTimeMillis();
                            String r = tool.execute(M.createObjectNode().put("query", q).put("limit", 5));
                            String which = org.researchzosho.tools.WebSearchTool.BRAVE_USED.get() > b0 ? "Brave" : org.researchzosho.tools.WebSearchTool.SEARXNG_USED.get() > s0 ? "SearXNG" : org.researchzosho.tools.WebSearchTool.FALLBACK_USED.get() > f0 ? "the built-in fallback" : "no backend";
                            System.out.println("answered by " + which + " in " + (System.currentTimeMillis() - t0) + " ms");
                            System.out.println(r);
                            return 0;
                        }
                        case "papers" -> {
                            if (args.length < 4) { System.err.println("usage: researchzosho search papers <query…>"); return 2; }
                            String q = String.join(" ", java.util.Arrays.copyOfRange(args, 3, args.length));
                            long t0 = System.currentTimeMillis();
                            String r = new org.researchzosho.tools.ScholarSearchTool().execute(new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("query", q).put("limit", 8));
                            System.out.println("Crossref and OpenAlex in " + (System.currentTimeMillis() - t0) + " ms");
                            System.out.println(r);
                            return 0;
                        }
                        default -> { System.err.println("usage: researchzosho search [status | start [port] [--fresh] | stop | test <query…> | papers <query…>]"); return 2; }
                    }
                }
                case "sources" -> {
                    String op = args.length > 2 ? args[2] : "list";
                    switch (op) {
                        case "list" -> {
                            var rules = SourceRules.load(store);
                            if (rules.isEmpty()) { System.out.println("no source rules yet — researchzosho sources trust <host> [why] · sources refuse <host> [why]"); return 0; }
                            for (var r : rules.rules()) System.out.printf("  %-7s %-40s %s%n", r.kind(), r.host(), r.why());
                            System.out.println("  a trusted host counts as a primary source; a refused one is dropped from searches and a claim resting only on it is dropped at review");
                            return 0;
                        }
                        case "trust", "refuse", "ban", "forget" -> {
                            if (op.equals("ban")) op = "refuse";   // the README says ban; the file says refuse; both work
                            if (args.length < 4) { System.err.println("usage: researchzosho sources " + op + " <host> [why]"); return 2; }
                            String why = args.length > 4 ? String.join(" ", java.util.Arrays.asList(args).subList(4, args.length)) : "";
                            try { SourceRules.set(store, op, args[3], why); } catch (IllegalArgumentException e) { System.err.println(e.getMessage()); return 2; }
                            System.out.println(op + " " + SourceRules.norm(args[3]) + (why.isEmpty() ? "" : " — " + why));
                            return 0;
                        }
                        default -> { System.err.println("usage: researchzosho sources [list | trust <host> [why] | refuse <host> [why] | forget <host>]"); return 2; }
                    }
                }
                case "export" -> {
                    String usage = "usage: researchzosho export <F-…|I-…|A-…> [--pdf|--md] [--beginner|--familiar] [--out FILE]";
                    if (args.length < 3) { System.err.println(usage); return 2; }
                    String id = args[2]; boolean pdf = false; Explain.Rung rung = Explain.Rung.written; Path out = null;
                    try {
                        for (int i = 3; i < args.length; i++) {
                            switch (args[i]) {
                                case "--pdf" -> pdf = true;
                                case "--md" -> pdf = false;
                                case "--beginner" -> rung = Explain.Rung.beginner;
                                case "--familiar" -> rung = Explain.Rung.familiar;
                                case "--out" -> out = Path.of(flagValue(args, i++));
                                default -> { System.err.println(usage); return 2; }
                            }
                        }
                    } catch (IllegalArgumentException e) { System.err.println(usage); return 2; }
                    Explain.Reading reading = rung == Explain.Rung.written ? null : Explain.entry(store, Explain.drive(), id, rung, false);
                    Export.File f = pdf ? Export.pdf(store, id, reading) : Export.markdown(store, id, reading);
                    if (out == null) out = Path.of(f.name());
                    Files.write(out, f.bytes());
                    System.out.println("wrote " + out + " (" + f.bytes().length + " bytes)");
                }
                case "web" -> {
                    String op = args.length > 2 ? args[2] : "status";
                    if (op.equals("signin") && args.length > 3 && (args[3].equals("on") || args[3].equals("off"))) {
                        org.researchzosho.Config.set(WebAccess.SIGNIN, args[3]);
                        System.out.println(args[3].equals("on")
                                ? "web.signin = on — a browser reads at the library's default level and needs a reader token to send questions (make one: researchzosho reader token <did>)"
                                : "web.signin = off — " + WebAccess.OPEN_NOTICE);
                        return 0;
                    }
                    if (op.equals("status") || op.equals("signin")) {
                        System.out.println("web.signin = " + (WebAccess.signInRequired() ? "on — a browser needs a reader token to send questions" : "off — " + WebAccess.OPEN_NOTICE));
                        System.out.println("  pages: http://127.0.0.1:" + LibrarianDaemon.DEFAULT_PORT + "/ when the service runs on the default port");
                        return 0;
                    }
                    System.err.println("usage: researchzosho web [status | signin on|off]"); return 2;
                }
                case "peer", "peers" -> { return peer(store, args); }
                case "directory" -> { return directory(store, args); }
                case "vault" -> {
                    java.nio.file.Path out = Vault.dir(store);
                    for (int i = 2; i < args.length; i++) if (args[i].equals("--out")) out = java.nio.file.Path.of(flagValue(args, i++));
                    var o = Vault.generate(store, out);
                    System.out.println("vault at " + o.dir() + ": " + o.written() + " written, " + o.unchanged() + " unchanged, " + o.removed() + " removed");
                    System.out.println("  open that folder in Obsidian, SoloMD or SilverBullet; start at Home. It follows the library as it changes.");
                    System.out.println("  it is a view: to accept, dispute or retire, use `researchzosho inbox` or the editor's agent panel over MCP.");
                }
                case "requests" -> {
                    var open = Requests.open(store);
                    if (open.isEmpty()) System.out.println("no open source requests");
                    for (var r : open) System.out.println(r.date() + "  " + r.locator() + "  — " + r.reason() + (r.context().isEmpty() ? "" : "  (for: " + r.context() + ")"));
                    if (!open.isEmpty()) System.out.println("supply one: researchzosho add <file> --for <url>");
                }
                case "perspectives" -> {
                    if (args.length < 3) { System.err.println("usage: researchzosho perspectives <question…>"); return 2; }
                    var ps = Perspectives.discover(String.join(" ", java.util.Arrays.asList(args).subList(2, args.length)), Researcher.judgeDrive(baseUrl, model), Researcher.webTools(), 5);
                    for (var p : ps) { System.out.println(p.name() + " — " + p.why()); for (String q : p.questions()) System.out.println("  - " + q); }
                    if (ps.isEmpty()) System.out.println("(the judge named no perspectives)");
                }
                case "graph" -> { return graph(store, args); }
                case "profile" -> { return profile(store, args); }
                case "bib" -> {
                    if (args.length < 3) { System.err.println("usage: researchzosho bib <F-…|I-…> [more ids…]   — BibTeX for everything they cite"); return 2; }
                    System.out.print(Bibliography.bibtex(store, Bibliography.sourcesOf(store, java.util.Arrays.asList(args).subList(2, args.length)), Citations.LIVE));
                }
                case "review" -> review(store, args.length > 2 ? args[2] : baseUrl, model);
                case "settle" -> {
                    // a write-up's claims onto the shelves and onto the map now, not at three in the morning: the review pass, then triples
                    java.util.List<String> ids = new java.util.ArrayList<>(java.util.Arrays.asList(args).subList(2, args.length));
                    var client = new org.researchzosho.drive.DriveClient(baseUrl, model);
                    if (!Crews.driveAnswers(baseUrl)) { System.err.println("no model drive answers at " + baseUrl + "; settling needs one"); return 1; }
                    var review = new LibrarianReview(store, new LibrarianIndex(store), LibrarianReview.driveJudge(client), "librarian:" + model).searcher(LibrarianReview.liveSearcher());
                    if (ids.isEmpty()) {
                        try (var files = Files.list(store.investigationsDir())) {
                            for (var p : files.sorted().toList()) if (p.toString().endsWith(".md")) {
                                Investigation inv = Investigation.parse(Files.readString(p, java.nio.charset.StandardCharsets.UTF_8));
                                if (inv.state() == Finding.State.draft) ids.add(inv.id());
                            }
                        }
                        if (ids.isEmpty()) System.out.println("no write-up is waiting for review; filing subjects and map lines for the claims that lack them");
                    }
                    for (String id : ids) {
                        Investigation inv = store.investigation(id);
                        if (inv == null) { System.err.println("no write-up " + id); continue; }
                        System.out.println("reviewing " + id + " for claims…");
                        var out = review.review(inv);
                        System.out.println(id + ": " + out.accepted().size() + " claim(s) accepted, " + out.disputed().size() + " disputed, " + out.keptDraft().size() + " kept as draft"
                                + (out.problems().isEmpty() ? "" : "; " + String.join("; ", out.problems())));
                    }
                    long lacking = store.scanFindings().findings().stream().filter(f -> f.subjects().isEmpty() && f.state() != Finding.State.retired).count();
                    if (lacking > 0) System.out.println("filing " + lacking + " claim(s) under subjects — one model call each, about " + Math.max(1, lacking * 3 / 60) + " minute(s)…");
                    var cat = Cataloger.run(store, Cataloger.driveJudge(client), false);
                    System.out.println("subjects: " + cat.grounded() + " claim(s) filed under subjects from the vocabulary" + (cat.proposals() > 0 ? ", " + cat.proposals() + " new subject(s) proposed for you (catalog/subjects.proposed.md)" : ""));
                    var t = Triples.fill(store, Triples.driveExtractor(client), 60);
                    System.out.println("map: " + t.filled() + " of " + t.asked() + " new claim(s) given their place on the map");
                    var ret = Retractions.check(store, Retractions.live(), 60, java.time.LocalDate.now());
                    System.out.println("retractions: " + ret.checked() + " DOI(s) checked at Crossref, " + ret.retracted() + " retracted, " + ret.concerns() + " concern(s)" + (ret.notes().isEmpty() ? "" : " — " + String.join("; ", ret.notes())));
                    System.out.println("rewriting the summaries of the subjects whose shelf changed — a minute or so each…");
                    var abs = Abstracts.run(store, Abstracts.driveWriter(client), null);
                    System.out.println("summaries: " + abs.written() + " subject summar" + (abs.written() == 1 ? "y" : "ies") + " rewritten, " + abs.unchanged() + " unchanged" + (abs.problems().isEmpty() ? "" : "; " + String.join("; ", abs.problems())));
                }
                case "inbox" -> { return inbox(store, args); }
                case "accept", "retire", "dispute" -> { return council(store, args); }
                case "catalog" -> Cataloger.cli(store, args, baseUrl, model);
                case "shelf" -> { return Serials.shelfCli(store, args); }
                case "serials" -> Serials.check(store);
                case "tonight" -> { System.out.print(Tonight.text(Tonight.plan(store))); return 0; }
                case "update" -> {
                    String op = args.length > 2 ? args[2] : "status";
                    switch (op) {
                        case "status" -> { System.out.print(Updater.status()); return 0; }
                        case "now" -> {
                            boolean restart = !java.util.Arrays.asList(args).contains("--no-restart");
                            String ver = null; for (int i = 3; i < args.length; i++) if (args[i].matches("\\d+\\.\\d+\\.\\d+")) ver = args[i];
                            Updater.Outcome o = Updater.now(ver, restart, System.out);
                            System.out.println(o.note());
                            return o.updated() ? 0 : 1;
                        }
                        case "auto" -> {
                            if (args.length < 4 || !(args[3].equals("on") || args[3].equals("off"))) { System.err.println("usage: researchzosho update auto on|off"); return 2; }
                            org.researchzosho.Config.set("RESEARCHZOSHO_UPDATE", args[3].equals("on") ? "auto" : "check");
                            System.out.println(args[3].equals("on") ? "auto-update is on: after each housekeeping, when no run is active, a newer release is installed and the service restarts"
                                                                    : "auto-update is off: researchzosho status says when a newer release exists; researchzosho update now installs it");
                            return 0;
                        }
                        default -> { System.err.println("usage: researchzosho update [status | now [version] [--no-restart] | auto on|off]"); return 2; }
                    }
                }
                case "questions" -> {
                    // the queue the explorer draws from: see it (by type, parked too), add, drop, reorder, park, and set the nightly budget
                    String op = args.length > 2 ? args[2] : "list";
                    var open = new java.util.ArrayList<Frontier.Line>();
                    for (Frontier.Line l : Frontier.read(store)) if (l.open()) open.add(l);
                    java.util.function.Function<String, String> pick = ref -> ref.matches("\\d+") && Integer.parseInt(ref) >= 1 && Integer.parseInt(ref) <= open.size() ? open.get(Integer.parseInt(ref) - 1).text() : ref;
                    switch (op) {
                        case "list" -> {
                            // the filters a person sorts a long list by: the same ones the page and library_frontier take
                            var f = new java.util.LinkedHashMap<String, String>();
                            boolean byReport = false, hints = false;
                            for (int i = 3; i < args.length; i++) {
                                switch (args[i]) {
                                    case "--parked" -> f.put("show", "parked");
                                    case "--all" -> f.put("show", "all");
                                    case "--by-report" -> byReport = true;
                                    case "--hints" -> hints = true;
                                    case "--type", "--report", "--fate", "--who", "--subject", "--language", "--grep" -> {
                                        if (i + 1 >= args.length) { System.err.println("usage: researchzosho questions list " + args[i] + " <value>"); return 2; }
                                        f.put(args[i].equals("--grep") ? "q" : args[i].substring(2), args[++i]);
                                    }
                                    default -> { System.err.println("unknown option " + args[i] + "; usage: researchzosho questions list [--type t] [--parked | --all] [--report I-…] [--fate kept|waiting|disputed|retired|none] [--who text] [--subject slug] [--language x] [--grep words] [--by-report] [--hints]"); return 2; }
                                }
                            }
                            f.putIfAbsent("show", "queued");
                            if (open.isEmpty()) { System.out.println("no open questions"); return 0; }
                            int budget = Crews.explorerBudget();
                            var listed = new LibraryProtocol(store).frontierList(hints);
                            var rows = new java.util.ArrayList<com.fasterxml.jackson.databind.node.ObjectNode>();
                            for (var o : listed) if (LibraryProtocol.matches(o, f)) rows.add(o);
                            String lastGroup = null;
                            for (var o : rows) {
                                if (byReport) {
                                    String grp = o.path("report").asText("");
                                    if (!grp.equals(lastGroup)) {
                                        System.out.println(grp.isEmpty() ? "not from a report:" : "left by " + grp + (o.hasNonNull("report_title") ? " — " + o.path("report_title").asText() : "") + " [" + o.path("report_fate").asText("") + "]:");
                                        lastGroup = grp;
                                    }
                                }
                                String mark = o.path("tonight").asBoolean() ? "tonight " : o.path("parked").asBoolean() ? "parked  " : open.get(o.path("position").asInt() - 1).researchable() ? "queued  " : "waits   ";
                                StringBuilder tail = new StringBuilder();
                                if (!o.path("language").asText("english").equals("english")) tail.append(" · ").append(o.path("language").asText());
                                for (var sj : o.path("subjects")) tail.append(" · ").append(sj.asText());
                                if (o.hasNonNull("similar") && !o.path("similar").asText().equals(o.path("text").asText())) { for (var x : listed) if (x.path("text").asText().equals(o.path("similar").asText())) tail.append(" · reads like #").append(x.path("position").asInt()); }
                                if (o.hasNonNull("answered")) tail.append(" · maybe answered already: ").append(o.path("answered").path("id").asText());
                                System.out.println("  " + o.path("position").asInt() + ". " + mark + "[" + o.path("type").asText() + (o.path("type").asText().equals("asked") ? " ×" + o.path("asked").asInt() : "") + "] "
                                        + (byReport ? Frontier.strip(o.path("text").asText()) : o.path("text").asText()) + (tail.length() > 0 ? "  (" + tail.substring(3) + ")" : ""));
                            }
                            long parked = open.stream().filter(Frontier.Line::parked).count();
                            int dups = Frontier.duplicates(store).size();
                            System.out.println("  " + rows.size() + " shown of " + open.size() + (parked > 0 && f.get("show").equals("queued") ? ", " + parked + " parked (--parked shows them, --all everything)" : "") + ". Types: report, asked, person, dispute, check (--type <t>). The explorer takes " + budget + " run(s) a night from " + String.join(", ", new java.util.TreeSet<>(Frontier.explorerTypes())) + "; \"waits\" = asked fewer than " + Frontier.MIN_ASKS + " times, or a type it leaves to you. A report's leftovers are filed parked."
                                    + (dups > 0 ? "\n  " + dups + " line(s) are second copies of the same question: researchzosho questions tidy removes them." : ""));
                            return 0;
                        }
                        case "tidy" -> {
                            int removed = Frontier.tidy(store);
                            System.out.println(removed == 0 ? "no duplicate questions" : "removed " + removed + " duplicate line(s)");
                            return 0;
                        }
                        case "add" -> {
                            if (args.length < 4) { System.err.println("usage: researchzosho questions add <question…>"); return 2; }
                            String q = String.join(" ", java.util.Arrays.copyOfRange(args, 3, args.length)).strip();
                            store.frontier("person", q);
                            System.out.println("queued; researchzosho tonight shows when the explorer takes it");
                            return 0;
                        }
                        case "drop", "next", "later", "park", "unpark" -> {
                            if (args.length < 4) { System.err.println("usage: researchzosho questions " + op + " <number from the list | the question>"); return 2; }
                            String text = pick.apply(String.join(" ", java.util.Arrays.copyOfRange(args, 3, args.length)).strip());
                            boolean ok = switch (op) {
                                case "drop" -> Frontier.drop(store, text, "person");
                                case "next" -> Frontier.next(store, text);
                                case "later" -> Frontier.later(store, text);
                                case "park" -> Frontier.park(store, text);
                                default -> Frontier.unpark(store, text);
                            };
                            if (!ok) { System.err.println("no open question reads: " + text + (op.equals("unpark") ? " (or it is not parked)" : "")); return 1; }
                            System.out.println(switch (op) { case "drop" -> "dropped: "; case "next" -> "runs next: "; case "later" -> "moved to the end: "; case "park" -> "parked: "; default -> "back in the queue: "; } + text);
                            return 0;
                        }
                        case "budget", "tonight" -> {
                            if (args.length < 4 || !args[3].matches("\\d+")) { System.err.println("usage: researchzosho questions " + op + " <runs per night>"); return 2; }
                            if (op.equals("budget")) { org.researchzosho.Config.set("RESEARCHZOSHO_EXPLORER_PER_NIGHT", args[3]); System.out.println("the explorer takes " + args[3] + " run(s) a night from now on" + (args[3].equals("0") ? " (off)" : "")); }
                            else { org.researchzosho.Config.set("explorer.tonight", args[3]); System.out.println("tonight the explorer takes " + args[3] + " run(s); the standing number stays " + Crews.explorerPerNight()); }
                            return 0;
                        }
                        default -> { System.err.println("usage: researchzosho questions [list [--type t] [--parked | --all] [--report I-…] [--fate f] [--who text] [--subject slug] [--language x] [--grep words] [--by-report] [--hints] | add <question…> | next|later|park|unpark|drop <n|question> | tidy | budget <n> | tonight <n>]"); return 2; }
                    }
                }
                case "bench" -> RetrievalBench.cli(store, args);
                case "subjects" -> {
                    if (args.length > 2) return subjectsProposed(store, args);
                    var counts = Related.counts(store);
                    var edges = Related.coOccurrence(store);
                    for (var e : counts.entrySet()) {
                        var co = edges.getOrDefault(e.getKey(), java.util.Map.of());
                        String top = co.entrySet().stream()
                                .sorted((a, b) -> b.getValue() - a.getValue()).limit(3)
                                .map(x -> x.getKey() + "×" + x.getValue()).reduce((a, b) -> a + " " + b).orElse("");
                        System.out.printf("  %-36s %3d  %s%n", e.getKey(), e.getValue(), top);
                    }
                }
                case "abstract" -> Abstracts.cli(store, args, baseUrl, model);
                case "enrich" -> Enrichment.cli(store, args, baseUrl, model);
                case "patron", "reader", "readers" -> { return patron(store, args); }
                case "serve" -> { return serve(store, args, baseUrl, model); }
                case "service" -> {
                    String op = args.length > 2 ? args[2] : "status";
                    String exec = null, host = "127.0.0.1"; int port = LibrarianDaemon.DEFAULT_PORT, hour = 3;
                    try {
                        for (int i = 3; i < args.length; i++) {
                            switch (args[i]) {
                                case "--exec" -> exec = flagValue(args, i++);
                                case "--host" -> host = flagValue(args, i++);   // 0.0.0.0: the LAN may reach it; the reader list decides who may do what
                                case "--port" -> port = flagInt(args, i++);
                                case "--crew-hour" -> hour = flagInt(args, i++);
                                default -> { System.err.println("unknown option " + args[i]); return 2; }
                            }
                        }
                    } catch (IllegalArgumentException e) {
                        System.err.println("usage: researchzosho service install|uninstall|status [--exec <launcher>] [--host H] [--port N] [--crew-hour H] — " + e.getMessage()); return 2;
                    }
                    var plan = Service.plan(Service.os(), op.equals("install") ? Service.resolveExec(exec) : (exec == null ? "researchzosho" : exec),
                            host, port, hour, Path.of(System.getProperty("user.home")));
                    int rc = Service.run(op, plan, System.out);
                    if (rc == 0 && op.equals("install") && !Service.hostFlag(host, "x", "").isEmpty()) {
                        System.out.println("  the pages answer on this computer's network address too. " + (WebAccess.signInRequired() ? "A browser needs a reader token to send questions." : WebAccess.OPEN_NOTICE + " " + WebAccess.OPEN_HOWTO));
                    }
                    return rc;
                }
                case "mcp" -> { org.researchzosho.mcp.McpServer.serveStdio(n -> n.startsWith("library_")); }
                case "probe" -> {
                    if (args.length < 3) { System.err.println("usage: researchzosho probe <query…>"); return 2; }
                    System.out.print(new LibrarianIndex(store).explain(String.join(" ", Arrays.copyOfRange(args, 2, args.length)), 8));
                }
                case "crews" -> {
                    boolean weekly = Arrays.asList(args).contains("--weekly"), monthly = Arrays.asList(args).contains("--monthly");
                    var cadence = weekly || monthly ? new Crews.Cadence(weekly, monthly) : Crews.Cadence.tonight(java.time.LocalDate.now());
                    for (var st : Crews.runAll(store, baseUrl, model, Crews.driveResearcher(store, baseUrl, model, Crews.EXPLORER_TURNS), Crews.EXPLORER_PER_NIGHT, cadence))
                        System.out.println(st.name() + ": " + st.outcome() + " (" + st.ms() + "ms)");
                }
                case "raw" -> {
                    if (args.length > 2 && args[2].equals("prune")) {
                        int n = Reports.prune(store);
                        System.out.println(n + " orphaned capture(s) deleted (the list was catalog/orphans.md; `researchzosho rebuild` or the next housekeeping drops them from the index)");
                    } else { System.err.println("usage: researchzosho raw prune   — delete the captures the weekly orphans report listed"); return 2; }
                }
                default -> {
                    Profile prof = Profiles.named(args[1]);
                    if (prof != null) {
                        if (!Profiles.isEnabled(store, prof.name())) { System.err.println("the " + prof.name() + " profile is not enabled on this library — `researchzosho profile enable " + prof.name() + "`"); return 2; }
                        Integer rc = prof.cli(store, args);
                        if (rc != null) return rc;
                    }
                    System.err.print(USAGE); return 2;
                }
            }
            return 0;
        } catch (Exception e) {
            System.err.println("librarian: " + e.getMessage());
            return 1;
        }
    }

    private static void status(LibraryStore store) throws Exception {
        var scan = store.scanFindings();
        long stale = scan.findings().stream().filter(Finding::reviewStale).count();
        long drafts = scan.findings().stream().filter(f -> f.state() == Finding.State.draft).count();
        long invs = 0;
        try (var l = Files.list(store.investigationsDir())) {
            invs = l.filter(p -> p.toString().endsWith(".md")).count();
        } catch (Exception ignored) { }
        long raw = 0;
        try (var l = Files.list(store.rawDir())) { raw = l.filter(p -> p.toString().endsWith(".md")).count(); }
        catch (Exception ignored) { }
        System.out.println("library at " + store.root());
        System.out.println("  findings: " + scan.findings().size() + " (" + drafts + " draft, " + stale + " with stale review)");
        System.out.println("  investigations: " + invs + "   raw captures: " + raw);
        long waiting = AccessRequests.pending(store);
        if (waiting > 0) System.out.println("  access requests waiting: " + waiting + "   (researchzosho reader requests)");
        System.out.println("  subjects in vocabulary: " + Cataloger.vocabulary(store).size()
                + "   living shelves: " + Serials.shelves(store).size());
        for (String p : scan.problems()) System.out.println("  PROBLEM: " + p);
        if (drafts + stale > 0) System.out.println("  → `researchzosho inbox` has " + (drafts + stale) + " item(s) for you");
    }

    /** `researchzosho directory …`: list this library in a directory (a library of listings), or search one. */
    static int directory(LibraryStore store, String[] args) throws IOException {
        String op = args.length > 2 ? args[2] : "list";
        switch (op) {
            case "list" -> {
                var all = Directory.published(store);
                if (all.isEmpty()) System.out.println("not listed anywhere yet — researchzosho directory publish <directory-url> --token <t> --url <this library's address>");
                for (String l : all) System.out.println(l);
            }
            case "publish" -> {
                if (args.length < 4) { System.err.println("usage: researchzosho directory publish <directory-url> --token <writer token there> --url <this library's public address>"); return 2; }
                String token = "", myUrl = "";
                for (int i = 4; i < args.length; i++) {
                    if (args[i].equals("--token")) token = flagValue(args, i++);
                    else if (args[i].equals("--url")) myUrl = flagValue(args, i++);
                }
                if (myUrl.isBlank()) { System.err.println("--url is needed: the address other libraries can reach this one at"); return 2; }
                System.out.println("listing:\n  " + Directory.listing(store, myUrl).get("claim").asText());
                String id = Directory.publish(store, args[3], token, myUrl);
                System.out.println("published as " + id + " at " + args[3] + " (a draft there until the directory's owner accepts it)");
            }
            case "find" -> {
                if (args.length < 5) { System.err.println("usage: researchzosho directory find <directory-url> <what you are looking for…> [--token t]"); return 2; }
                String token = ""; java.util.List<String> words = new java.util.ArrayList<>();
                for (int i = 4; i < args.length; i++) { if (args[i].equals("--token")) token = flagValue(args, i++); else words.add(args[i]); }
                System.out.println(Directory.find(args[3], token, String.join(" ", words)));
            }
            default -> { System.err.println("usage: researchzosho directory list | publish <directory-url> --token <t> --url <my-url> | find <directory-url> <query…>"); return 2; }
        }
        return 0;
    }

    /** `researchzosho peer …`: other libraries this one may ask. */
    static int peer(LibraryStore store, String[] args) throws IOException {
        String op = args.length > 2 ? args[2] : "list";
        switch (op) {
            case "list" -> {
                var all = Peers.list(store);
                if (all.isEmpty()) { System.out.println("no peers yet — researchzosho peer add <name> <url> <token> [--group a,b]"); return 0; }
                for (var p : all) System.out.println(p.name() + "  " + p.url() + "  groups: " + (p.groups().isEmpty() ? "(none)" : String.join(",", p.groups())));
                String d = Peers.defaultGroup();
                System.out.println(d.isEmpty() ? "asked only when an ask names them (peers.default is not set)" : "asked when this library holds nothing: " + d + " (peers.default)");
            }
            case "add" -> {
                if (args.length < 6) { System.err.println("usage: researchzosho peer add <name> <url> <token> [--group a,b]"); return 2; }
                java.util.List<String> groups = new java.util.ArrayList<>();
                for (int i = 6; i < args.length; i++) if (args[i].equals("--group")) groups = java.util.List.of(flagValue(args, i++).split(","));
                Peers.add(store, args[3], args[4], args[5], groups);
                System.out.println("peer " + args[3] + " → " + args[4] + (groups.isEmpty() ? "" : "  groups: " + String.join(",", groups)));
            }
            case "remove" -> {
                if (args.length < 4) { System.err.println("usage: researchzosho peer remove <name>"); return 2; }
                System.out.println(Peers.remove(store, args[3]) ? "removed" : "no such peer");
            }
            case "ask" -> {
                if (args.length < 5) { System.err.println("usage: researchzosho peer ask <name|group|all> <question…>"); return 2; }
                var peers = Peers.select(store, args[3]);
                if (peers.isEmpty()) { System.out.println("no peer or group named " + args[3]); return 1; }
                String q = String.join(" ", java.util.Arrays.asList(args).subList(4, args.length));
                System.out.println(Peers.render(Peers.ask(peers, q, 6)));
            }
            default -> { System.err.println("usage: researchzosho peer list | add <name> <url> <token> [--group a,b] | remove <name> | ask <name|group|all> <question…>"); return 2; }
        }
        return 0;
    }

    /** The sharing settings, live; `researchzosho research` shows them, the sub-verbs change them in the config file. */
    static int research(LibraryStore store, String[] args) throws IOException {
        if (args.length < 3) {
            System.out.println("research: " + ResearchSettings.describe());
            System.out.println("  settings live in " + org.researchzosho.Config.userConfigPath() + " (research.workers, research.pause, research.window) — a running ask follows at its next turn");
            Jobs jobs = new Jobs(store, j -> { throw new IllegalStateException("read only"); });
            var today = jobs.turnsTodayByPatron();
            if (today.isEmpty()) System.out.println("  today: no research turns spent yet");
            else for (var e : today.entrySet()) System.out.println("  today: " + e.getKey() + " spent " + e.getValue() + " turn(s)");
            for (var j : jobs.active()) {
                if (!"research".equals(j.path("kind").asText())) continue;
                System.out.println("  " + j.path("job_id").asText() + " [" + j.path("state").asText() + "] " + Acquisitions.compress(j.path("args").path("question").asText(), 90));
            }
            return 0;
        }
        switch (args[2]) {
            case "workers" -> {
                if (args.length < 4 || !args[3].matches("\\d+") || Integer.parseInt(args[3]) < 1) { System.err.println("usage: researchzosho research workers <n≥1>"); return 2; }
                org.researchzosho.Config.set(ResearchSettings.WORKERS, args[3]);
                System.out.println("research.workers = " + args[3] + " — a running ask follows at its next turn");
            }
            case "ask" -> {
                // file a question from the command line, as the person; the service picks it up within seconds
                if (args.length < 4) { System.err.println("usage: researchzosho research ask \"<question…>\" [--depth] [--quick] [--shelves|--web] [--max-turns N] [--max-minutes N]"); return 2; }
                java.util.List<String> words = new java.util.ArrayList<>();
                String mode = "broad", sources = "both"; boolean quick = false; int maxTurns = -1, maxMinutes = -1;
                try {
                    for (int i = 3; i < args.length; i++) {
                        switch (args[i]) {
                            case "--depth" -> mode = "depth";
                            case "--broad" -> mode = "broad";
                            case "--quick", "--now" -> quick = true;
                            case "--shelves" -> sources = "shelves";
                            case "--web" -> sources = "web";
                            case "--max-turns" -> maxTurns = flagInt(args, i++);
                            case "--max-minutes" -> maxMinutes = flagInt(args, i++);
                            default -> words.add(args[i]);
                        }
                    }
                } catch (IllegalArgumentException e) { System.err.println(e.getMessage()); return 2; }
                String q = String.join(" ", words).strip();
                com.fasterxml.jackson.databind.node.ObjectNode ask = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
                ask.put("question", q); ask.put("mode", mode); ask.put("sources", sources);
                if (quick) ask.put("quick", true);
                if (maxTurns >= 0) ask.put("max_turns", maxTurns);
                if (maxMinutes >= 0) ask.put("max_minutes", maxMinutes);
                ask.putObject("patron").put("did", "person").put("name", System.getProperty("user.name", "person")).put("runtime", "cli");
                try {
                    var r = new LibraryProtocol(store).research(ask);
                    System.out.println("sent as " + r.path("job_id").asText() + (quick ? " (quick: front of the line, short limits)" : "") + " — researchzosho jobs " + r.path("job_id").asText() + " shows how it goes; the service picks it up within seconds");
                } catch (ProtocolError e) { System.err.println(e.getMessage()); return 1; }
            }
            case "stop" -> {
                if (args.length < 4) { System.err.println("usage: researchzosho research stop <J-…>"); return 2; }
                var a = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode(); a.put("op", "stop"); a.put("job_id", args[3]);
                a.putObject("patron").put("did", "person").put("name", System.getProperty("user.name", "person")).put("runtime", "cli");
                try { var r = new LibraryProtocol(store).job(a); System.out.println(r.path("state").asText().equals("stopped") ? "stopped: " + args[3] + " never started" : "stopping: " + args[3] + " ends at its next turn"); }
                catch (ProtocolError e) { System.err.println(e.getMessage()); return 1; }
            }
            case "pause" -> { org.researchzosho.Config.set(ResearchSettings.PAUSE, "on"); System.out.println("research paused — queued asks wait; a running ask holds at its next turn (resume: researchzosho research resume)"); }
            case "resume" -> { org.researchzosho.Config.set(ResearchSettings.PAUSE, "off"); System.out.println("research resumed"); }
            case "window" -> {
                if (args.length < 4) { System.err.println("usage: researchzosho research window <HH:MM-HH:MM|off>"); return 2; }
                if (!args[3].equalsIgnoreCase("off") && !ResearchSettings.validWindow(args[3])) { System.err.println("a window is HH:MM-HH:MM (it may cross midnight), or off"); return 2; }
                org.researchzosho.Config.set(ResearchSettings.WINDOW, args[3].equalsIgnoreCase("off") ? "off" : args[3]);
                System.out.println(args[3].equalsIgnoreCase("off") ? "research.window = off — asks are picked up at any hour" : "research.window = " + args[3] + " — asks are picked up only inside it; a running ask finishes");
            }
            default -> { System.err.println("usage: researchzosho research [ask \"<question…>\" [--depth] [--quick] | workers <n> | pause | resume | window <HH:MM-HH:MM|off>]"); return 2; }
        }
        return 0;
    }

    /** `researchzosho subjects proposed|accept|drop`: the cataloger's proposed subjects, and what the person does with them. */
    static int subjectsProposed(LibraryStore store, String[] args) throws Exception {
        Path f = store.subjectsFile().resolveSibling("subjects.proposed.md");
        java.util.List<String[]> proposed = new java.util.ArrayList<>();   // {slug, description}
        java.util.List<String> head = new java.util.ArrayList<>();
        if (Files.exists(f)) for (String line : Files.readAllLines(f, java.nio.charset.StandardCharsets.UTF_8)) {
            if (line.startsWith("- ")) { int dash = line.indexOf(" — "); proposed.add(dash > 0 ? new String[]{line.substring(2, dash).strip(), line.substring(dash + 3).strip()} : new String[]{line.substring(2).strip(), ""}); }
            else if (proposed.isEmpty()) head.add(line);
        }
        String op = args[2];
        if (op.equals("proposed") || op.equals("list")) {
            if (proposed.isEmpty()) { System.out.println("no proposed subjects"); return 0; }
            for (int i = 0; i < proposed.size(); i++) System.out.printf("  %2d  %-40s %s%n", i + 1, proposed.get(i)[0], Acquisitions.compress(proposed.get(i)[1], 90));
            System.out.println("  researchzosho subjects accept <n|slug>… (or all) · subjects drop <n|slug>… (or all)");
            return 0;
        }
        if (!op.equals("accept") && !op.equals("drop")) { System.err.println("usage: researchzosho subjects [proposed | accept <n|slug>…|all | drop <n|slug>…|all]"); return 2; }
        if (args.length < 4) { System.err.println("say which: numbers from `subjects proposed`, slugs, or all"); return 2; }
        java.util.Set<String> chosen = new java.util.LinkedHashSet<>();
        for (int i = 3; i < args.length; i++) {
            String a = args[i];
            if (a.equals("all")) { for (String[] p : proposed) chosen.add(p[0]); continue; }
            if (a.matches("\\d+")) { int n = Integer.parseInt(a); if (n >= 1 && n <= proposed.size()) chosen.add(proposed.get(n - 1)[0]); else System.err.println("no proposal " + n); continue; }
            if (proposed.stream().anyMatch(p -> p[0].equals(a))) chosen.add(a); else System.err.println("not a proposal: " + a);
        }
        if (chosen.isEmpty()) return 1;
        int done = 0;
        for (String[] p : proposed) {
            if (!chosen.contains(p[0])) continue;
            if (op.equals("accept")) { Cataloger.addToVocabulary(store, p[0], p[1]); System.out.println("accepted " + p[0] + " — " + p[1]); }
            else System.out.println("dropped " + p[0]);
            done++;
        }
        StringBuilder sb = new StringBuilder();
        for (String h : head) sb.append(h).append('\n');
        for (String[] p : proposed) if (!chosen.contains(p[0])) sb.append("- ").append(p[0]).append(" — ").append(p[1]).append('\n');
        Files.writeString(f, sb.toString(), java.nio.charset.StandardCharsets.UTF_8);
        if (op.equals("accept") && done > 0) System.out.println("  now `researchzosho settle` files the claims under " + (done == 1 ? "it" : "them"));
        return 0;
    }

    /** `researchzosho explain` — a reading aid: an entry at a rung, or a term in an entry; the shelves only. */
    static int explain(LibraryStore store, String[] args) throws Exception {
        String usage = "usage: researchzosho explain <F-…|I-…|A-…> [--beginner|--familiar|--written] [--fresh]\n"
                + "       researchzosho explain \"<term>\" [--in <id>] [--beginner|--familiar|--written] [--now|--full] [--fresh]";
        if (args.length < 3) { System.err.println(usage); return 2; }
        String what = args[2]; String in = null; Explain.Rung rung = Explain.Rung.beginner; boolean fresh = false, now = false, overnight = false;
        try {
            for (int i = 3; i < args.length; i++) {
                switch (args[i]) {
                    case "--beginner" -> rung = Explain.Rung.beginner;
                    case "--familiar" -> rung = Explain.Rung.familiar;
                    case "--written", "--as-written" -> rung = Explain.Rung.written;
                    case "--rung" -> rung = Explain.Rung.of(flagValue(args, i++));
                    case "--in" -> in = flagValue(args, i++);
                    case "--fresh" -> fresh = true;
                    case "--now" -> now = true;
                    case "--full" -> overnight = true;
                    default -> { System.err.println("unknown option " + args[i] + "\n" + usage); return 2; }
                }
            }
        } catch (IllegalArgumentException e) { System.err.println(usage + " — " + e.getMessage()); return 2; }
        boolean isId = what.matches("[FIA]-\\d{4}-[a-z0-9-]+");
        Explain.Reading r;
        try {
            Explain.Progress say = st -> { System.err.print("\r  … " + st + "                                        "); System.err.flush(); };
            r = isId ? Explain.entry(store, rung == Explain.Rung.written ? null : Explain.drive(), what, rung, fresh, say)
                     : Explain.term(store, Explain.drive(), what, in, rung, fresh, say);
            if (!r.cached()) System.err.print("\r" + " ".repeat(90) + "\r");
        } catch (ProtocolError e) { System.err.println(e.getMessage()); return 1; }
        printReading(r);
        if (!"none".equals(r.grounding()) || r.offer() == null) return 0;
        if (!now && !overnight) {
            System.out.println("  quick look (a few minutes, front of the line):   researchzosho explain \"" + what + "\"" + (in == null ? "" : " --in " + in) + " --now");
            System.out.println("  full research (no limits, waits its turn):       researchzosho explain \"" + what + "\"" + (in == null ? "" : " --in " + in) + " --full");
            return 0;
        }
        com.fasterxml.jackson.databind.node.ObjectNode ask = r.offer().deepCopy();
        if (!now) ask.remove("quick");
        ask.putObject("patron").put("did", "person").put("name", System.getProperty("user.name", "person")).put("runtime", "cli");
        String jobId = new LibraryProtocol(store).research(ask).path("job_id").asText();
        if (!now) { System.out.println("sent as " + jobId + " (full research: no limits, it waits its turn) — then: researchzosho explain \"" + what + "\"" + (in == null ? "" : " --in " + in)); return 0; }
        System.out.println("looking it up now as " + jobId + " (the daemon picks it up within seconds; ceilings " + ask.path("max_turns").asInt() + " turns, " + ask.path("max_minutes").asInt() + " minutes)");
        Jobs jobs = new Jobs(store, j -> { throw new IllegalStateException("read only"); });
        long deadline = System.currentTimeMillis() + (ask.path("max_minutes").asInt(6) + 3) * 60_000L;
        String state = "queued"; long queuedSince = System.currentTimeMillis();
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(3_000);
            com.fasterxml.jackson.databind.node.ObjectNode j = jobs.get(jobId);
            state = j == null ? "gone" : j.path("state").asText();
            if ("done".equals(state) || "failed".equals(state) || "gone".equals(state)) break;
            if ("queued".equals(state) && System.currentTimeMillis() - queuedSince > 30_000) {
                System.out.println("  nothing has picked it up in 30 s — is the service running? (researchzosho service status). It stays filed as " + jobId + ".");
                return 1;
            }
            System.out.print("."); System.out.flush();
        }
        System.out.println();
        if (!"done".equals(state)) { System.out.println("  the job ended as " + state + " — researchzosho jobs " + jobId + " says why"); return 1; }
        try { printReading(Explain.term(store, Explain.drive(), what, in, rung, true)); }
        catch (ProtocolError e) { System.err.println(e.getMessage()); return 1; }
        return 0;
    }

    private static void printReading(Explain.Reading r) {
        String head = (r.term().isEmpty() ? r.of() : "\"" + r.term() + "\"" + (r.of().isEmpty() ? "" : " in " + r.of())) + " · " + r.rung().name()
                + " · based on the library: " + ("shelves".equals(r.grounding()) ? "fully" : "thin".equals(r.grounding()) ? "partly" : r.grounding()) + (r.checked() > 0 ? " (" + r.checked() + " paragraph(s) checked, " + r.unsupported() + " not backed up)" : "")
                + (r.cached() ? " · cached" : " · just written");
        System.out.println(head);
        System.out.println();
        System.out.println(r.text().isEmpty() ? "  (nothing to show)" : r.text());
        if (!r.terms().isEmpty()) {
            System.out.println();
            System.out.println("words you may meet next (researchzosho explain \"<term>\" --in " + (r.of().isEmpty() ? "<id>" : r.of()) + "):");
            for (Explain.Term t : r.terms()) System.out.println("  - " + t.term() + " — " + t.gloss());
        }
        System.out.println();
    }

    static int add(LibraryStore store, String[] args) throws Exception {
        if (args.length < 3) { System.err.println("usage: researchzosho add <file|url|folder> [title] [--for <url>] [--collection <name>]"); return 2; }
        String what = args[2];
        // a folder: the corpus path
        if (!what.startsWith("http://") && !what.startsWith("https://") && Files.isDirectory(Path.of(what))) {
            String coll = null; boolean register = false;
            for (int i = 3; i < args.length; i++) { if (args[i].equals("--collection")) coll = flagValue(args, i++); else if (args[i].equals("--register")) register = true; }
            Path dir = Path.of(what).toAbsolutePath().normalize();
            if (coll == null) coll = Corpus.nameFor(dir);
            Corpus.Outcome o = Corpus.addFolder(store, dir, coll, true);
            if (register) Corpus.register(store, coll, dir);
            System.out.println("collection " + coll + ": " + o.added() + " added, " + o.unchanged() + " unchanged, " + o.skipped() + " skipped of " + o.seen() + " document(s)" + (register ? " — registered; the crews rescan it" : ""));
            for (String pr : o.problems()) System.out.println("  " + pr);
            System.out.println("  ask them: /research go with sources=shelves, or library_research {\"sources\":\"shelves\",\"collections\":[\"" + coll + "\"]}");
            return 0;
        }
        // a document supplied for a locator the runner could not read
        String forUrl = null;
        java.util.List<String> rest = new java.util.ArrayList<>();
        for (int i = 3; i < args.length; i++) { if (args[i].equals("--for")) forUrl = flagValue(args, i++); else rest.add(args[i]); }
        if (forUrl != null) {
            Path f = Path.of(what).toAbsolutePath().normalize();
            var doc = org.researchzosho.tools.DocText.convert(Files.readAllBytes(f), what);
            if (doc.text().isBlank()) { System.err.println("no text could be extracted (" + doc.kind() + ")"); return 1; }
            Path raw = Requests.supply(store, forUrl, doc.text(), rest.isEmpty() ? doc.title() : String.join(" ", rest));
            System.out.println("supplied: " + forUrl + " ← " + f.getFileName() + " (" + doc.text().length() + " chars) → " + raw.getFileName()
                    + "\n  the references, the cite-check and the inventory now read this document behind that url");
            return 0;
        }
        String givenTitle = rest.isEmpty() ? "" : String.join(" ", rest);
        byte[] bytes;
        String locator;
        if (what.startsWith("http://") || what.startsWith("https://")) {
            org.researchzosho.tools.Fetch.Result resp;
            try {
                resp = org.researchzosho.tools.Fetch.get(what, java.time.Duration.ofSeconds(60));   // redirects followed, address checked
            } catch (Exception e) {
                System.err.println("could not fetch " + what + ": " + e.getMessage()); return 1;
            }
            if (resp.status() >= 400) { System.err.println("HTTP " + resp.status() + " for " + what); return 1; }
            bytes = resp.body();
            locator = resp.url();
        } else {
            Path f = Path.of(what).toAbsolutePath().normalize();
            bytes = Files.readAllBytes(f);
            locator = "file://" + f;
        }
        var doc = org.researchzosho.tools.DocText.convert(bytes, what);
        if (doc.text().isBlank()) { System.err.println("no text could be extracted (" + doc.kind() + ")"); return 1; }
        String title = givenTitle.isBlank() ? doc.title() : givenTitle;
        Citations.Meta meta = Citations.resolve(store, locator, Citations.LIVE);   // a DOI / arXiv / PubMed url: the record's title and citation
        if (meta != null && !meta.title().isEmpty() && givenTitle.isBlank()) title = meta.title();
        Captions.Extract cx = Captions.extract(doc.text());
        Path raw = RawCapture.capture(store, locator, doc.text(), title, "researchzosho-add", "");   // THIS store, not the configured one (a test with two libraries found the difference)
        if (raw == null) { System.err.println("not shelved: this machine has no library (`researchzosho init`), or the capture was refused"); return 1; }
        String[] onDisk = RawCapture.read(raw);
        boolean same = onDisk[2].strip().equals(doc.text().strip());
        System.out.println("shelved [" + doc.kind() + "] " + (title.isEmpty() ? locator : title));
        if (meta != null) System.out.println("  citation: " + meta.edition());
        if (!cx.isEmpty()) System.out.println("  " + cx.figures().size() + " figure caption(s), " + cx.tables().size() + " table caption(s), " + cx.rows().size() + " table row(s) found in the text");
        System.out.println("  " + onDisk[2].length() + " chars on disk → " + raw
                + (same ? "" : "  (an earlier capture of this locator today was kept)"));
        System.out.println("  findable at the desk now; `researchzosho review` does not read raw —"
                + " ask about it, or research it, to turn it into findings");
        return 0;
    }

    private static void review(LibraryStore store, String baseUrl, String model) throws Exception {
        var idx = new LibrarianIndex(store);
        var review = new LibrarianReview(store, idx,
                LibrarianReview.driveJudge(new DriveClient(baseUrl, model)), "librarian:" + model).searcher(LibrarianReview.liveSearcher());
        int reviewed = 0;
        try (var files = Files.list(store.investigationsDir())) {
            for (var p : files.sorted().toList()) {
                if (!p.toString().endsWith(".md")) continue;
                var inv = Investigation.parse(Files.readString(p));
                if (inv.state() != Finding.State.draft) continue;
                var out = review.review(inv);
                reviewed++;
                System.out.println(inv.id() + ": " + out.accepted().size() + " accepted " + out.accepted()
                        + ", " + out.keptDraft().size() + " kept draft"
                        + (out.disputed().isEmpty() ? "" : ", DISPUTED " + out.disputed())
                        + (out.skippedDuplicates().isEmpty() ? "" : ", duplicates of " + out.skippedDuplicates()));
                for (String prob : out.problems()) System.out.println("  note: " + prob);
            }
        }
        System.out.println(reviewed == 0 ? "nothing to review — no draft investigations"
                : reviewed + " investigation(s) reviewed");
    }

    private static int inbox(LibraryStore store, String[] args) throws Exception {
        // the same filters as the Inbox page and library_inbox
        var f = new java.util.LinkedHashMap<String, String>();
        boolean byReport = false;
        for (int i = 2; i < args.length; i++) {
            switch (args[i]) {
                case "--by-report" -> byReport = true;
                case "--report", "--subject", "--kind", "--tier", "--confidence", "--writer", "--state", "--language", "--grep" -> {
                    if (i + 1 >= args.length) { System.err.println("usage: researchzosho inbox " + args[i] + " <value>"); return 2; }
                    f.put(args[i].equals("--grep") ? "q" : args[i].substring(2), args[++i]);
                }
                default -> { System.err.println("unknown option " + args[i] + "; usage: researchzosho inbox [--report I-…] [--subject slug] [--kind extraction|synthesis|interpretation|speculation] [--tier t] [--confidence low|medium|high] [--writer text] [--state draft|stale] [--language x] [--grep words] [--by-report]"); return 2; }
            }
        }
        var all = new LibraryProtocol(store).inboxList();
        if (all.isEmpty()) { System.out.println("inbox empty — nothing awaits you"); return 0; }
        var rows = new java.util.ArrayList<com.fasterxml.jackson.databind.node.ObjectNode>();
        for (var o : all) if (LibraryProtocol.inboxMatches(o, f)) rows.add(o);
        System.out.println(rows.size() + (rows.size() == all.size() ? "" : " of " + all.size()) + " item(s) awaiting the council:");
        String lastGroup = null;
        for (var o : rows) {
            if (byReport) {
                String grp = o.path("report").asText("");
                if (!grp.equals(lastGroup)) { System.out.println(grp.isEmpty() ? "not from a report:" : "from " + grp + (o.hasNonNull("report_title") ? " — " + o.path("report_title").asText() : "") + ":"); lastGroup = grp; }
            }
            StringBuilder tail = new StringBuilder();
            for (var sj : o.path("subjects")) tail.append(" · ").append(sj.asText());
            if (!o.path("language").asText("english").equals("english")) tail.append(" · ").append(o.path("language").asText());
            System.out.printf("  %-44s %-8s %-14s %-9s %-6s %s%s%n", o.path("id").asText(),
                    o.path("stale").asBoolean() ? "STALE" : o.path("state").asText(), o.path("kind").asText(), o.path("tier").asText(), o.path("confidence").asText(),
                    Acquisitions.compress(o.path("title").asText(), 60), tail.length() > 0 ? "  (" + tail.substring(3) + ")" : "");
        }
        System.out.println("  accept <id…>|--all|--report I-… · dispute <id> <why> · retire <id…>|--report I-…");
        return 0;
    }

    private static int council(LibraryStore store, String[] args) throws Exception {
        if (args.length < 3) { System.err.println("usage: researchzosho " + args[1] + " <id> [why]"); return 2; }
        Council c = new Council(store);
        String id = args[2];
        switch (args[1]) {
            case "accept" -> {
                // one id, several ids, or --all (every draft, in id order); each is printed so nothing passes silently
                List<String> ids = new java.util.ArrayList<>();
                if ("--all".equals(id) || "all".equals(id)) {
                    for (Finding d : store.scanFindings().findings()) if (d.state() == Finding.State.draft) ids.add(d.id());
                    if (ids.isEmpty()) { System.out.println("no drafts to accept"); return 0; }
                } else if ("--report".equals(id)) {
                    if (args.length < 4) { System.err.println("usage: researchzosho accept --report <I-…>"); return 2; }
                    for (var o : new LibraryProtocol(store).inboxList()) if (o.path("report").asText("").startsWith(args[3])) ids.add(o.path("id").asText());
                    if (ids.isEmpty()) { System.out.println("no claims of " + args[3] + " are waiting"); return 0; }
                } else {
                    ids.addAll(Arrays.asList(args).subList(2, args.length));
                }
                for (String each : ids) {
                    Finding f = c.accept(each);
                    System.out.println("accepted " + f.id() + "  [" + f.claimType() + ", round " + f.review().round() + "] " + f.title());
                }
                if (ids.size() > 1) System.out.println(ids.size() + " accepted, signed person");
            }
            case "retire" -> {
                List<String> ids = new java.util.ArrayList<>();
                if ("--report".equals(id)) {
                    if (args.length < 4) { System.err.println("usage: researchzosho retire --report <I-…>"); return 2; }
                    for (var o : new LibraryProtocol(store).inboxList()) if (o.path("report").asText("").startsWith(args[3])) ids.add(o.path("id").asText());
                    if (ids.isEmpty()) { System.out.println("no claims of " + args[3] + " are waiting"); return 0; }
                } else ids.addAll(args.length > 3 ? Arrays.asList(args).subList(2, args.length) : List.of(id));
                for (String each : ids) {
                    Finding f = c.retire(each);
                    System.out.println("retired " + f.id() + " — kept on disk, out of the push");
                }
            }
            case "dispute" -> {
                if (args.length < 4) { System.err.println("usage: researchzosho dispute <id> <why>"); return 2; }
                Finding f = c.dispute(id, String.join(" ", Arrays.copyOfRange(args, 3, args.length)));
                System.out.println("disputed " + f.id() + " — frontier entry added");
            }
            default -> { return 2; }
        }
        return 0;
    }
}
