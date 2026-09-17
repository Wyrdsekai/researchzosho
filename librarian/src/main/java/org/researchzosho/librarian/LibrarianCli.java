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
              setup [--yes]                set up the library: its folder, the model, the service, Claude Code, a first document
              version                      the installed version
              init                         create the library. Research runs can then save their results here
              status                       counts, drafts, stale reviews, problems
              name [<name…>]               show or set the library's name (the folder's name until you set one)
              refresh                      re-index the files that changed or were removed (takes seconds)
              repair                       fix what an earlier version saved wrongly, such as a database or PDF saved as unreadable text. The service does this by itself once after each update
              rebuild                      rebuild the whole search index and INDEX.md from the files (slow: every entry is read again)
              ask <question…>              what the library holds on a question, with sources
              chat [--new | --resume <id>]  talk to the Librarian: ask, follow up, start a research run, read what it found, decide the inbox. /help inside
              add <file|url> [title]       add your own document (PDF/DOCX/PPTX/ODT/EPUB/HTML/text)
              add <folder> [--collection N] [--register] [--link] [--survey]
                                           add every document under a folder, as a collection. --link reads the files where they are (nothing is copied). --survey only counts them
              absorb <transcript|export|url> [--verify]
                                           a conversation you had with another assistant. It is saved to the library. Your questions become open questions. Its claims are listed for checking
              survey <repo|paper|url|issues> [--kind k] [--pick 1,3] [--do "…"]
                                           read a code repository, a paper, a website or an issue tracker. Saves one draft claim on what it is and lists research directions. --pick runs them, --do runs your own question
              items <list|csv|url|calibre-library> [--lens "…"] [--as frontier|runs|none] [--match T]… [--sample N]
                                           a list of things (books, tools, an inventory, a Calibre library or its metadata.db). The list is saved whole, each item is checked against the library, and a question is made for each selected item
              holdings <words…>            search the lists you saved. Every word must appear in an entry
              remove <I-…|F-…> [--report-only | --claims-only] [--yes]
                                           delete a report and its claims, only the report, only the claims, or one claim. retire keeps a claim but stops using it
              check <draft> [--verify]     your own draft or notes. Lists the claims to check, fetches its citations, saves its questions as open questions
              reading <bib|ris|csv|list> [--watch]
                                           a reading list. Every DOI and url is fetched into the library as a collection
              questions file <file> [--as frontier|runs]
                                           add a whole file of questions to the open questions, in order
              bookmarks <export|urls> [--folder F] [--watch]
                                           a browser's bookmarks, saved into the library. --watch re-reads them every night
              meeting <vtt|transcript> [--verify]
                                           a meeting transcript. Keeps the decisions, saves the questions raised as open questions, lists the claims to check
              triples [<n>]                add a subject, relation and object to claims that lack one, using the model. A nightly maintenance step, run by hand
              concepts [<n>]               note the concepts each claim depends on. The map links each claim to them, and bridges uses those links
              bridges [<area>] [--dry] [--via terms|graph|both] [--reach low|medium|high] [--strict|--loose] [--toward a] [--away w]
                                           find two subjects that share terms or concepts but that no source connects, and write a research question about them.
                                           list | accept <q> | dismiss <q> | settings [<area>] | measure | distance <area> <other>
              add <folder> [--collection N] [--register]   add every document under the folder, as a named collection
              add <file> --for <url>       supply a document a research run could not fetch (a paywall, for example). See `requests`
              collection list|add <name> <folder>|rescan     the registered folders (nightly maintenance rescans them)
              requests                     the documents a research run could not fetch and asks you for
              vault [--out <dir>]          the library as a folder an editor opens (Obsidian, SoloMD): notes and links, kept up to date
              directory publish <dir-url> --token <t> --url <my-url> · find <dir-url> <query…> · list
                                           a directory is a list of libraries. Publish yours there, or search one for libraries to ask
              peer list · add <name> <url> <token> [--group a,b] · remove <name> · ask <name|group|all> <question…>
                                           other libraries this one may ask. Their answers stay labelled as theirs and are never merged in
              research ask "<question…>" [--depth] [--quick] [--shelves|--web] [--max-turns N] [--max-minutes N]
                                           send a question. The service picks it up within seconds
              research                     how research runs share the model: workers, pause, window. Today's turns by user, and what is running
              research workers <n> · pause · resume · stop <J-…> · window <HH:MM-HH:MM|off>    change them now. A running question follows at its next step. stop ends one research run there
              jobs [<J-…>]                 every research run, queued and running first, then the last finished. One id: its state, progress, wait, and where its report went
              stats [<n>]                  the research runs side by side: turns, rounds, citation checks, sources, fetches. The last n rows (default 20) and the totals
              bib <id…>                    BibTeX for everything a claim or report cites (DOI / arXiv / PubMed looked up)
              perspectives <question…>     who studies this and what each would ask. Sub-questions for a research run
              web [status | signin on|off]  the web pages: open to everyone by default. signin on asks for a reader token before a question can be sent
              sources [list | trust <host> [why] | ban <host> [why] | forget <host>]
                                           your own rules for sources. Trusted ones count as primary sources. Banned ones are dropped from searches and reviews
              settle [<I-…>…]              take a report's claims, file them under subjects and add them to the map, now. All waiting reports when none is named
              export <id> [--pdf|--md] [--beginner|--familiar] [--out FILE]
                                           an entry, or its Simple or Familiar version, as a Markdown or PDF file
              sharpen <question…>          refine a rough question into a better one: the question to run, what it assumed, what you already have. Runs nothing
              search [status|start|stop|test <query>|papers <query>]   which web search service answers. SearXNG through Docker. Papers by DOI
              models [--all]               which model fits this machine's graphics card, measured, with the command that runs it
              model install|status|stop|uninstall
                                           the model on this machine, on demand: it starts when a research run needs it and stops after 20 idle
                                           minutes (install [--file <gguf>] [--gpu <index>] [--idle-minutes N] [--share])
              embed [status|start [port] [--cpu]|stop|test]   the embeddings server (search by meaning): what is configured. Text Embeddings Inference through Docker
              explain <id> [--beginner|--familiar|--written] [--fresh]
                                           an entry explained at a reading level (Simple, Familiar or Original). From the library only, checked
              explain "<term>" [--in <id>] [--now|--full]
                                           a term as it is used in an entry. When the library does not explain it, a quick look or full research
              map <name|id> [--depth N]    the map around a person, place, work or concept. The links are claims
              graph merge <a> <b> · alias <node> <name…> · kind <node> <kind> [--private|--public] · link <node> <Qid> · proposals
              profile list · enable <name> · disable <name>       extra fields on top of the core (science is on by default; genealogy)
              <profile> <verb…>            a profile's own verbs, e.g. `genealogy import tree.ged`
              review [drive]               review draft reports with the model
              inbox [--report I-…] [--subject s] [--kind k] [--tier t] [--confidence c] [--writer w] [--state draft|stale] [--language x] [--grep words] [--by-report]   what is waiting for you: drafts and stale reviews
              accept <id…>|--all|--report I-… · dispute <id> <why> · retire <id…>|--report I-…   your decisions on claims
              catalog [drive] [--accept-all]   file claims under the known subjects. Propose new subjects
              shelf add <name> <query> [days] · list · every <name> <days> · park|unpark <name> · remove <name>   the saved searches nightly maintenance runs again
              serials                      check the saved searches for new sources. List overdue reviews
              tonight                      what nightly maintenance will do at its next run: searches due, questions it will research
              update [now | auto on|off]   compare the installed release with the latest. Install it. Let the service do it after nightly maintenance
              questions [list [--type t] [--parked|--all] [--report I-…] [--fate f] [--who text] [--subject s] [--language x] [--grep words] [--by-report] [--hints] | add <q…> | file <file> | next|later|park|unpark|drop <n> | tidy | budget <n> | tonight <n>]   the queue the nightly research draws from. A report's leftover questions wait parked
              bench [k]                    how often search misses on the library's own test queries
              subjects                     the subject list with counts and the subjects that appear together
              subjects proposed            the proposed subjects, numbered
              subjects accept <n|slug>…    accept proposed subjects (then `settle` files the claims under them). `all` takes every one
              subjects drop <n|slug>…      throw proposals away. `all` clears the list
              abstract [drive] [subject…]  write or rewrite the summary of each subject
              enrich [drive] [N]           add model-written context to saved pages (N files, 0=all)
              reader [list|allow <did> <level> [name]|deny <did>|remove <did>|default <level>|token <did>|webhook …]
              reader requests · approve <R-id> [read|write] · deny <R-id> [why]     access requests, and your answer
                                           who may read and write (deny|read|write); `token` makes a bearer token for a program (`patron` is the same verb)
              serve [--host H] [--port P] [--crew-hour H|--no-crews]
                                           the service: the protocol over HTTP (default 127.0.0.1:4649), research
                                           runs, and nightly maintenance (default 03:00 local)
              crews [--weekly] [--monthly]  run nightly maintenance once, now (source re-checks · nightly research · review · inventory · summaries · enrich · heat ·
                                           [weekly: duplicates · orphans] · refresh|rebuild · backup)
              raw prune                    delete the saved pages the weekly orphans report listed (catalog/orphans.md)
              service install|uninstall|status [--exec <launcher>] [--host H] [--port P] [--crew-hour H]
                                           run the service in the background: systemd (Linux), LaunchAgent (macOS), logon task (Windows)
              mcp                          MCP over stdio with ONLY the library tools (for Claude Code and other hosts)
              probe <query…>               why ask answered as it did: each search method's raw scores and the cutoff
            """;

    /** `librarian patron …` — the allow-list in catalog/patrons.md, and this library's identity. */
    static int patron(LibraryStore store, String[] args) throws IOException {
        String op = args.length > 2 ? args[2] : "list";
        switch (op) {
            case "list" -> {
                var id = store.identity();
                System.out.println("library " + id.id() + " — " + id.name() + " (contract " + LibraryProtocol.CONTRACT + ")");
                var pol = Patrons.load(store);
                System.out.println("default: " + pol.dflt() + "   (anonymous and unlisted users)");
                for (var e : pol.listed()) System.out.println("- " + e.did() + " — " + (e.name().isEmpty() ? "(unnamed)" : e.name()) + " — " + e.level());
                if (pol.listed().isEmpty()) System.out.println("(no users listed — " + Patrons.file(store) + ")");
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
                    case "--crew-hour" -> { hour = flagInt(args, i++); if (hour > 23) { System.err.println("--crew-hour takes 0 to 23 (the hour nightly maintenance runs), or a negative number to turn it off"); return 2; } }
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
        // a model server of this machine's own, installed by an earlier release: bring it up to this one (the embeddings server beside the model)
        try { String up = ModelServer.upgrade(System.out); if (up.startsWith("!")) System.out.println("  model server not upgraded: " + up.substring(1)); } catch (Exception ignored) { }
        // what an update owes the library: things an earlier version saved wrongly are fixed by this one, once per version
        try { Repairs.Outcome fixed = Repairs.onceFor(store, org.researchzosho.Version.number() == null ? "dev" : org.researchzosho.Version.number()); if (fixed != null && !fixed.repaired().isEmpty()) { System.out.println("  repaired after the update: " + fixed.summary()); for (String r : fixed.repaired()) System.out.println("    " + r); } } catch (Exception e) { System.out.println("  repair skipped: " + e.getMessage()); }
        LibrarianDaemon d = LibrarianDaemon.start(store, host, port, baseUrl, model, hour);
        Service.recordPid();
        var id = store.identity();
        System.out.println("The Librarian is at " + d.url() + "  (" + id.id() + " — " + id.name() + ", contract " + LibraryProtocol.CONTRACT + ")");
        System.out.println("  drive " + baseUrl + Crews.driveLine(baseUrl));
        System.out.println("  research runs: " + d.describeWorkers() + "  (RESEARCHZOSHO_JOB_WORKERS, RESEARCHZOSHO_JOB_DRIVES)");
        System.out.println(hour < 0 ? "  nightly maintenance: off" : "  nightly maintenance: daily at " + String.format("%02d:00", hour) + " local (catalog/crews.log)");
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
                    System.out.println("library created at " + store.root());
                    System.out.println("Research runs now save their drafts here.");
                }
                case "status" -> { status(store); String up = org.researchzosho.Version.updateNotice(); if (!up.isEmpty()) System.out.println("\n" + up); }
                case "repair" -> {
                    Repairs.Outcome o = Repairs.run(store);
                    System.out.println(o.summary());
                    for (String r : o.repaired()) System.out.println("  repaired: " + r);
                    for (String r : o.leftAlone()) System.out.println("  left alone: " + r);
                    return 0;
                }
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
                    System.out.println("rebuilding: every entry is indexed again (20 minutes for a thousand entries). researchzosho refresh takes only what changed");
                    int migrated = store.migrateReviewHashes();
                    int n = new LibrarianIndex(store).rebuild();
                    store.regenerateIndex();
                    System.out.println("re-indexed " + n + " entries; catalog/INDEX.md regenerated"
                            + (migrated > 0 ? "; " + migrated + " review signature(s) carried over to the current hash formula" : ""));
                }
                case "add" -> { return add(store, args); }
                case "absorb" -> { return absorb(store, args); }
                case "holdings" -> { return holdings(store, args); }
                case "remove" -> { return remove(store, args); }
                case "survey", "repo" -> { return survey(store, args); }
                case "items" -> { return items(store, args); }
                case "check", "reading", "bookmarks", "meeting" -> { return launch(store, args); }
                case "bridges" -> { return bridges(store, args); }
                case "concepts" -> {
                    // the concepts crew's step by hand: each finding without a concepts note gets one from the model, up to N
                    int n = args.length > 2 && args[2].matches("\\d+") ? Integer.parseInt(args[2]) : Concepts.PER_NIGHT;
                    var o = Concepts.fill(store, Concepts.driveExtractor(new DriveClient(baseUrl, model)), n);
                    System.out.println(o.asked() + " asked, " + o.filled() + " filled. The map now links each claim's subject to its concepts");
                }
                case "triples" -> {
                    // the triples crew's step by hand: the findings without a triple get one from the model, up to N
                    int n = args.length > 2 && args[2].matches("\\d+") ? Integer.parseInt(args[2]) : Triples.PER_NIGHT;
                    var o = Triples.fill(store, Triples.driveExtractor(new DriveClient(baseUrl, model)), n);
                    System.out.println(o.asked() + " asked, " + o.filled() + " filled. The map updates at the next nightly maintenance, or now with `researchzosho graph`");
                }
                case "ask" -> {
                    if (args.length < 3) { System.err.println("usage: researchzosho ask <question…>   — what the library holds on it, or holds_nothing"); return 2; }
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
                case "jobs" -> { return jobs(store, args); }
                case "chat" -> { return chat(store, args, baseUrl, model); }
                case "stats" -> { return stats(store, args); }
                case "explain" -> { return explain(store, args); }
                case "sharpen" -> {
                    if (args.length < 3) { System.err.println("usage: researchzosho sharpen <question…>"); return 2; }
                    String q = String.join(" ", java.util.Arrays.asList(args).subList(2, args.length));
                    System.err.print("  … reading what the library holds, asking who studies this, writing the question"); System.err.flush();
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
                    if (open.isEmpty()) System.out.println("No documents are being asked for.");
                    for (var r : open) System.out.println(r.date() + "  " + r.locator() + "  — " + r.reason() + (r.context().isEmpty() ? "" : "  (for: " + r.context() + ")"));
                    if (!open.isEmpty()) System.out.println("supply one: researchzosho add <file> --for <url>");
                }
                case "perspectives" -> {
                    if (args.length < 3) { System.err.println("usage: researchzosho perspectives <question…>"); return 2; }
                    var ps = Perspectives.discover(String.join(" ", java.util.Arrays.asList(args).subList(2, args.length)), Researcher.judgeDrive(baseUrl, model), Researcher.webTools(), 5);
                    for (var p : ps) { System.out.println(p.name() + " — " + p.why()); for (String q : p.questions()) System.out.println("  - " + q); }
                    if (ps.isEmpty()) System.out.println("(no perspectives were found)");
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
                    if (!Crews.driveAnswers(baseUrl)) { System.err.println("No model answers at " + baseUrl + ". settle needs one."); return 1; }
                    var review = new LibrarianReview(store, new LibrarianIndex(store), LibrarianReview.driveJudge(client), "librarian:" + model).searcher(LibrarianReview.liveSearcher());
                    if (ids.isEmpty()) {
                        try (var files = Files.list(store.investigationsDir())) {
                            for (var p : files.sorted().toList()) if (p.toString().endsWith(".md")) {
                                Investigation inv = Investigation.parse(Files.readString(p, java.nio.charset.StandardCharsets.UTF_8));
                                if (inv.state() == Finding.State.draft) ids.add(inv.id());
                            }
                        }
                        if (ids.isEmpty()) System.out.println("No report is waiting for review. Filing subjects and map links for the claims that lack them.");
                    }
                    for (String id : ids) {
                        Investigation inv = store.investigation(id);
                        if (inv == null) { System.err.println("No report has the id " + id); continue; }
                        System.out.println("reviewing " + id + " for claims…");
                        var out = review.review(inv);
                        System.out.println(id + ": " + out.accepted().size() + " claim(s) accepted, " + out.disputed().size() + " disputed, " + out.keptDraft().size() + " kept as draft"
                                + (out.problems().isEmpty() ? "" : "; " + String.join("; ", out.problems())));
                    }
                    long lacking = store.scanFindings().findings().stream().filter(f -> f.subjects().isEmpty() && f.state() != Finding.State.retired).count();
                    if (lacking > 0) System.out.println("filing " + lacking + " claim(s) under subjects — one model call each, about " + Math.max(1, lacking * 3 / 60) + " minute(s)…");
                    var cat = Cataloger.run(store, Cataloger.driveJudge(client), false);
                    System.out.println("subjects: " + cat.grounded() + " claim(s) filed under known subjects" + (cat.proposals() > 0 ? ", " + cat.proposals() + " new subject(s) proposed for you (catalog/subjects.proposed.md)" : ""));
                    var t = Triples.fill(store, Triples.driveExtractor(client), 60);
                    System.out.println("map: " + t.filled() + " of " + t.asked() + " new claim(s) added to the map");
                    var ret = Retractions.check(store, Retractions.live(), 60, java.time.LocalDate.now());
                    System.out.println("retractions: " + ret.checked() + " DOI(s) checked at Crossref, " + ret.retracted() + " retracted, " + ret.concerns() + " concern(s)" + (ret.notes().isEmpty() ? "" : " — " + String.join("; ", ret.notes())));
                    System.out.println("rewriting the summaries of the subjects that changed — a minute or so each…");
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
                            System.out.println(args[3].equals("on") ? "auto-update is on: after each nightly maintenance, when no research run is active, a newer release is installed and the service restarts"
                                                                    : "auto-update is off: researchzosho status says when a newer release exists. researchzosho update now installs it");
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
                        case "file" -> {
                            // a whole file of questions onto the queue in order (or the first few out as runs)
                            if (args.length < 4) { System.err.println("usage: researchzosho questions file <file|url> [--as frontier|runs] [--title <t>] [--limit <n>]"); return 2; }
                            String[] sub = new String[args.length - 1];
                            sub[0] = args[0]; sub[1] = "questions"; System.arraycopy(args, 3, sub, 2, args.length - 3);
                            return launch(store, sub);
                        }
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
                            if (open.isEmpty()) { System.out.println("No open questions yet. researchzosho questions add <question…> adds one."); return 0; }
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
                            System.out.println("  " + rows.size() + " shown of " + open.size() + (parked > 0 && f.get("show").equals("queued") ? ", " + parked + " parked (--parked shows them, --all everything)" : "") + ". Types: report, asked, person, dispute, check (--type <t>). The nightly research takes " + budget + " research run(s) a night from " + String.join(", ", new java.util.TreeSet<>(Frontier.explorerTypes())) + ". \"waits\" = asked fewer than " + Frontier.MIN_ASKS + " times, or a type it leaves to you. A report's leftover questions are parked."
                                    + (dups > 0 ? "\n  " + dups + " line(s) are second copies of the same question. researchzosho questions tidy removes them." : ""));
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
                            System.out.println("queued. researchzosho tonight shows when the nightly research takes it");
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
                            if (!ok) { System.err.println("No open question matches: " + text + (op.equals("unpark") ? " (or it is not parked)" : "")); return 1; }
                            System.out.println(switch (op) { case "drop" -> "dropped: "; case "next" -> "runs next: "; case "later" -> "moved to the end: "; case "park" -> "parked: "; default -> "back in the queue: "; } + text);
                            return 0;
                        }
                        case "budget", "tonight" -> {
                            if (args.length < 4 || !args[3].matches("\\d+")) { System.err.println("usage: researchzosho questions " + op + " <runs per night>"); return 2; }
                            if (op.equals("budget")) { org.researchzosho.Config.set("RESEARCHZOSHO_EXPLORER_PER_NIGHT", args[3]); System.out.println("the nightly research takes " + args[3] + " research run(s) a night from now on" + (args[3].equals("0") ? " (off)" : "")); }
                            else { org.researchzosho.Config.set("explorer.tonight", args[3]); System.out.println("tonight the nightly research takes " + args[3] + " research run(s). The usual number stays " + Crews.explorerPerNight()); }
                            return 0;
                        }
                        default -> { System.err.println("usage: researchzosho questions [list [--type t] [--parked | --all] [--report I-…] [--fate f] [--who text] [--subject slug] [--language x] [--grep words] [--by-report] [--hints] | add <question…> | file <file|url> [--as runs] | next|later|park|unpark|drop <n|question> | tidy | budget <n> | tonight <n>]"); return 2; }
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
                                case "--crew-hour" -> { hour = flagInt(args, i++); if (hour > 23) { System.err.println("--crew-hour takes 0 to 23 (the hour nightly maintenance runs), or a negative number to turn it off"); return 2; } }
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
                        System.out.println(n + " orphaned saved page(s) deleted (the list was catalog/orphans.md). `researchzosho rebuild` or the next nightly maintenance drops them from the index");
                    } else { System.err.println("usage: researchzosho raw prune   — delete the saved pages the weekly orphans report listed"); return 2; }
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
        System.out.println("  claims: " + scan.findings().size() + " (" + drafts + " draft, " + stale + " with stale review)");
        System.out.println("  reports: " + invs + "   saved pages: " + raw);
        long waiting = AccessRequests.pending(store);
        if (waiting > 0) System.out.println("  access requests waiting: " + waiting + "   (researchzosho reader requests)");
        System.out.println("  subjects: " + Cataloger.vocabulary(store).size()
                + "   saved searches: " + Serials.shelves(store).size());
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
                System.out.println(d.isEmpty() ? "asked only when a question names them (peers.default is not set)" : "asked when this library holds nothing: " + d + " (peers.default)");
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
            System.out.println("  settings live in " + org.researchzosho.Config.userConfigPath() + " (research.workers, research.pause, research.window). A running question follows at its next turn");
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
                System.out.println("research.workers = " + args[3] + ". A running question follows at its next turn");
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
                    System.out.println("sent as " + r.path("job_id").asText() + (quick ? " (quick: front of the line, short limits)" : "") + ". The service picks it up within seconds. researchzosho jobs " + r.path("job_id").asText() + " shows how it goes");
                } catch (ProtocolError e) { System.err.println(e.getMessage()); return 1; }
            }
            case "stop" -> {
                if (args.length < 4) { System.err.println("usage: researchzosho research stop <J-…>"); return 2; }
                var a = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode(); a.put("op", "stop"); a.put("job_id", args[3]);
                a.putObject("patron").put("did", "person").put("name", System.getProperty("user.name", "person")).put("runtime", "cli");
                try { var r = new LibraryProtocol(store).job(a); System.out.println(r.path("state").asText().equals("stopped") ? "stopped: " + args[3] + " never started" : "stopping: " + args[3] + " ends at its next turn"); }
                catch (ProtocolError e) { System.err.println(e.getMessage()); return 1; }
            }
            case "pause" -> { org.researchzosho.Config.set(ResearchSettings.PAUSE, "on"); System.out.println("research paused. Queued questions wait, and a running question stops at its next turn (resume: researchzosho research resume)"); }
            case "resume" -> { org.researchzosho.Config.set(ResearchSettings.PAUSE, "off"); System.out.println("research resumed"); }
            case "window" -> {
                if (args.length < 4) { System.err.println("usage: researchzosho research window <HH:MM-HH:MM|off>"); return 2; }
                if (!args[3].equalsIgnoreCase("off") && !ResearchSettings.validWindow(args[3])) { System.err.println("a window is HH:MM-HH:MM (it may cross midnight), or off"); return 2; }
                org.researchzosho.Config.set(ResearchSettings.WINDOW, args[3].equalsIgnoreCase("off") ? "off" : args[3]);
                System.out.println(args[3].equalsIgnoreCase("off") ? "research.window = off. Questions are picked up at any hour" : "research.window = " + args[3] + ". Questions are picked up only inside it. A running question finishes");
            }
            default -> { System.err.println("usage: researchzosho research [ask \"<question…>\" [--depth] [--quick] | workers <n> | pause | resume | window <HH:MM-HH:MM|off>]"); return 2; }
        }
        return 0;
    }

    /** `researchzosho stats [<n>]`: the run ledger — the last rows and the aggregates over them. */
    static int stats(LibraryStore store, String[] args) throws Exception {
        int n = args.length > 2 && args[2].matches("\\d+") ? Integer.parseInt(args[2]) : 20;
        var rows = RunLedger.read(store, n);
        if (rows.isEmpty()) { System.out.println("No research runs recorded yet (" + RunLedger.file(store) + "). A research run writes its row when it finishes"); return 0; }
        System.out.println("the last " + rows.size() + " research run(s), oldest first:");
        for (var r : rows) System.out.println("  " + RunLedger.line(r));
        System.out.println();
        for (var e : RunLedger.summary(rows).entrySet()) System.out.println("  " + e.getKey() + ": " + e.getValue());
        System.out.println("  traces: " + store.root().resolve("catalog").resolve("traces") + " (every model call of a run, one file each)");
        return 0;
    }

    /** `researchzosho chat`: the terminal front of the Librarian. Lines in, replies out; a few slash commands. */
    /** How often the terminal chat looks at the runs it follows. */
    static final int CHAT_WATCH_SECONDS = org.researchzosho.Config.getInt("RESEARCHZOSHO_CHAT_WATCH_SECONDS", 15);
    /** The terminal chat prints a followed run's line on every stage change, and this often in between. */
    static final int CHAT_STATUS_MINUTES = Math.max(1, org.researchzosho.Config.getInt("RESEARCHZOSHO_CHAT_STATUS_MINUTES", 5));

    static int chat(LibraryStore store, String[] args, String baseUrl, String model) throws Exception {
        Librarian.Session session = null;
        for (int i = 2; i < args.length; i++) {
            if (args[i].equals("--new")) session = Librarian.Session.open(store);
            else if (args[i].equals("--resume") && i + 1 < args.length) { session = Librarian.Session.resume(store, args[++i]); if (session == null) { System.err.println("no session " + args[i] + " (researchzosho chat --sessions lists them)"); return 2; } }
            else if (args[i].equals("--sessions")) { for (String id : Librarian.Session.list(store)) { var sx = Librarian.Session.resume(store, id); System.out.println("  " + id + "  " + (sx == null ? "" : sx.title())); } return 0; }
        }
        if (session == null) session = Librarian.Session.latest(store);
        Researcher.Drive drive = Researcher.calmJudgeDrive(baseUrl, model);
        if (!Crews.driveAnswers(baseUrl)) { System.err.println("no model answers at " + baseUrl + " — the Librarian needs one to talk (researchzosho setup, or researchzosho model install)"); return 1; }
        // a quiet screen: the drive's request lines belong in a log, not between the person and the Librarian
        try { ((ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("org.researchzosho")).setLevel(ch.qos.logback.classic.Level.WARN); } catch (Throwable ignored) { }
        Librarian lib = new Librarian(store, drive, Librarian.person(), session);
        ChatIo io = ChatIo.open(store);
        String name = store.identity().name();
        System.out.println("The Librarian of " + name + ". Session " + session.id + (session.messages().isEmpty() ? "" : ", continued") + ". /help for the commands, /quit to leave.");
        if (!session.messages().isEmpty()) { var ms = session.messages(); for (int i = Math.max(0, ms.size() - 2); i < ms.size(); i++) { var m = ms.get(i); if (m.path("role").asText().equals("user")) System.out.println("\n> " + m.path("content").asText()); else if (m.path("role").asText().equals("assistant") && !m.path("content").asText("").isBlank()) System.out.println("\n" + m.path("content").asText()); } }
        // the chat follows the runs it starts: a line when a run's stage changes, one notice when it is done
        final Librarian.Session[] current = {session};
        Thread watcher = new Thread(() -> {
            java.util.Map<String, String> lastStage = new java.util.HashMap<>(), lastMinute = new java.util.HashMap<>();
            LibraryProtocol lp = new LibraryProtocol(store);
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(CHAT_WATCH_SECONDS * 1000L);
                    for (String jobId : current[0].watched()) {
                        var a = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("job_id", jobId);
                        a.putObject("patron").put("did", "person").put("name", System.getProperty("user.name", "person")).put("runtime", "cli");
                        RunProgress.View v = RunProgress.of(lp.job(a).path("job"), System.currentTimeMillis(), RunProgress.typicalSeconds(lp, a.path("patron")));
                        if (!v.active()) { io.notifyLine("  " + current[0].told(jobId, v)); lastStage.remove(jobId); continue; }
                        long minute = v.elapsedSeconds() / 60;
                        boolean due = minute > 0 && minute % CHAT_STATUS_MINUTES == 0 && !String.valueOf(minute).equals(lastMinute.put(jobId, String.valueOf(minute)));   // a long stage is not silent
                        if (!v.stage().equals(lastStage.put(jobId, v.stage())) || due) io.notifyLine("  " + RunProgress.line(v));
                    }
                } catch (InterruptedException e) { return; } catch (Exception ignored) { }
            }
        }, "chat-runs");
        watcher.setDaemon(true); watcher.start();
        java.util.Set<String> announced = new java.util.HashSet<>(session.watched());
        while (true) {
            System.out.println();
            String line = io.readLine("> ");
            if (line == null) break;
            line = line.strip();
            if (line.isEmpty()) continue;
            if (Librarian.QUIT.contains(line)) break;
            if (line.equals("/help")) { System.out.println("  say anything · /new (a fresh conversation) · /runs (the research runs this chat follows) · /sessions · /resume <id> · /quit\n  up and down arrows go through what you typed before, Ctrl-R searches it, Ctrl-C clears the line, Ctrl-D leaves\n  \"find out …\" starts a research run. \"yes\" accepts what the Librarian offers"); continue; }
            if (line.equals("/new")) { session = Librarian.Session.open(store); current[0] = session; lib = new Librarian(store, drive, Librarian.person(), session); System.out.println("  a fresh conversation, " + session.id); continue; }
            if (line.equals("/runs")) { LibraryProtocol lp2 = new LibraryProtocol(store); java.util.List<String> w = session.watched(); if (w.isEmpty()) System.out.println("  no research run is being followed in this conversation"); for (String jobId : w) { var a2 = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("job_id", jobId); a2.putObject("patron").put("did", "person").put("name", System.getProperty("user.name", "person")).put("runtime", "cli"); try { System.out.println("  " + RunProgress.line(RunProgress.of(lp2.job(a2).path("job"), System.currentTimeMillis(), RunProgress.typicalSeconds(lp2, a2.path("patron"))))); } catch (Exception e) { System.out.println("  " + jobId + ": " + e.getMessage()); } } continue; }
            if (line.equals("/sessions")) { for (String id : Librarian.Session.list(store)) { var sx = Librarian.Session.resume(store, id); System.out.println("  " + id + "  " + (sx == null ? "" : sx.title())); } continue; }
            if (line.startsWith("/resume ")) { var sx = Librarian.Session.resume(store, line.substring(8).strip()); if (sx == null) { System.out.println("  no such session"); continue; } session = sx; current[0] = session; lib = new Librarian(store, drive, Librarian.person(), session); System.out.println("  continuing " + session.id + ": " + session.title()); continue; }
            if (line.startsWith("/")) { System.out.println("  not a command; /help lists them"); continue; }
            System.out.print("  …"); System.out.flush();
            String reply;
            try { reply = lib.say(line); } catch (Exception e) { reply = "(the model did not answer: " + e.getMessage() + ")"; }
            System.out.print("\r   \r");
            synchronized (System.out) { System.out.println(reply); }
            for (String jobId : current[0].watched()) if (announced.add(jobId)) System.out.println("  following " + jobId + ": this chat shows its stage and says when it is done. /runs shows it now");
        }
        watcher.interrupt();
        io.close();
        System.out.println("Session " + session.id + " is kept; researchzosho chat continues it.");
        return 0;
    }

    /** `researchzosho jobs [<J-…>]`: the runs, or one run — the line the CLI names after `research ask` (it was named and missing through 0.1.8). */
    static int jobs(LibraryStore store, String[] args) throws Exception {
        var a = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        a.putObject("patron").put("did", "person").put("name", System.getProperty("user.name", "person")).put("runtime", "cli");
        String id = args.length > 2 ? args[2].strip() : "";
        if (!id.isEmpty()) a.put("job_id", id); else a.put("limit", 10);
        com.fasterxml.jackson.databind.JsonNode r;
        try { r = new LibraryProtocol(store).job(a); } catch (ProtocolError e) { System.err.println(e.getMessage()); return 1; }
        if (!id.isEmpty()) {
            var j = r.path("job");
            System.out.println(j.path("job_id").asText() + "  " + j.path("state").asText() + "  " + j.path("kind").asText() + (j.path("restarted").asInt(0) > 0 ? "  (restarted " + j.path("restarted").asInt() + "×)" : ""));
            if (j.hasNonNull("question")) System.out.println("  question: " + j.get("question").asText());
            System.out.println("  queued " + j.path("queued_at").asText() + (j.hasNonNull("started_at") ? " · started " + j.get("started_at").asText() : "") + (j.hasNonNull("ended_at") ? " · ended " + j.get("ended_at").asText() : "") + " · " + elapsed(j.path("elapsed_s").asLong()));
            if (j.hasNonNull("drive")) System.out.println("  drive: " + j.get("drive").asText());
            if (j.hasNonNull("waiting")) System.out.println("  waiting: " + j.get("waiting").asText());
            if (j.path("state").asText().equals("running")) { RunProgress.View v = RunProgress.of(j, System.currentTimeMillis(), RunProgress.typicalSeconds(new LibraryProtocol(store), a.path("patron"))); System.out.println("  " + v.stage() + " · " + RunProgress.elapsed(v.elapsedSeconds()) + " elapsed · about " + v.percent() + "% done"); }
            if (j.has("progress") && j.get("progress").isObject()) System.out.println("  progress: " + progressWords(j.get("progress")));
            if (j.hasNonNull("investigation")) System.out.println("  report: " + j.get("investigation").asText() + "  (researchzosho export " + j.get("investigation").asText() + " --md, or the Runs page)");
            if (j.hasNonNull("result") && !j.get("result").asText().isBlank()) System.out.println("  " + (j.path("is_error").asBoolean(false) ? "error: " : "result: ") + Acquisitions.compress(j.get("result").asText(), 300));
            return 0;
        }
        int running = 0, queued = 0;
        for (var j : r.path("active")) { if ("running".equals(j.path("state").asText())) running++; else queued++; }
        System.out.println("research runs: " + running + " running, " + queued + " queued" + (r.path("paused").asBoolean(false) ? "  (research paused)" : ""));
        for (var j : r.path("active")) System.out.println("  " + jobRow(j));
        if (r.path("finished").size() > 0) { System.out.println("finished (newest first):"); for (var j : r.path("finished")) System.out.println("  " + jobRow(j)); }
        if (r.path("active").size() == 0 && r.path("finished").size() == 0) System.out.println("  No research runs yet. researchzosho research ask \"<question…>\" starts one");
        return 0;
    }

    static String jobRow(com.fasterxml.jackson.databind.JsonNode j) {
        StringBuilder b = new StringBuilder(j.path("job_id").asText()).append(" [").append(j.path("state").asText()).append("] ").append(j.path("kind").asText());
        b.append(" · ").append(elapsed(j.path("elapsed_s").asLong()));
        if (j.has("progress") && j.get("progress").isObject()) b.append(" · ").append(progressWords(j.get("progress")));
        if (j.hasNonNull("waiting")) b.append(" · waiting for the model");
        if (j.hasNonNull("question")) b.append(" · ").append(Acquisitions.compress(j.get("question").asText(), 70));
        return b.toString();
    }

    static String progressWords(com.fasterxml.jackson.databind.JsonNode p) {
        java.util.List<String> parts = new java.util.ArrayList<>();
        if (!p.path("phase").asText("").isEmpty()) parts.add(p.path("phase").asText());
        if (p.path("round").asInt() > 0) parts.add("round " + p.path("round").asInt() + " of up to " + p.path("rounds").asInt());
        if (p.path("workers_total").asInt() > 0) parts.add("workers " + p.path("workers_done").asInt() + "/" + p.path("workers_total").asInt());
        if (p.path("turns_ceiling").asInt() > 0) parts.add("turns " + p.path("turns_used").asInt() + " of " + p.path("turns_ceiling").asInt());
        else parts.add("turns " + p.path("turns_used").asInt() + ", no turn ceiling");
        if (p.hasNonNull("deadline_at")) { try { long min = (java.time.Instant.parse(p.get("deadline_at").asText()).toEpochMilli() - System.currentTimeMillis()) / 60_000; parts.add(min >= 0 ? min + " min left" : "past its deadline, wrapping up"); } catch (Exception ignored) { } }
        var it = p.fields();
        java.util.Set<String> known = java.util.Set.of("phase", "round", "rounds", "workers_done", "workers_total", "turns_used", "turns_ceiling", "deadline_at", "at");
        while (it.hasNext()) { var e = it.next(); if (!known.contains(e.getKey())) parts.add(e.getKey().replace('_', ' ') + " " + (e.getValue().isTextual() ? e.getValue().asText() : e.getValue().toString())); }
        return String.join(", ", parts);
    }

    static String elapsed(long s) { return s < 60 ? s + " s" : s < 3600 ? (s / 60) + " min" : String.format("%dh%02d", s / 3600, (s % 3600) / 60); }

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
        System.out.println("looking it up now as " + jobId + " (the service picks it up within seconds. Limits: " + ask.path("max_turns").asInt() + " turns, " + ask.path("max_minutes").asInt() + " minutes)");
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
        if (!"done".equals(state)) { System.out.println("  the research run ended as " + state + ". researchzosho jobs " + jobId + " says why"); return 1; }
        try { printReading(Explain.term(store, Explain.drive(), what, in, rung, true)); }
        catch (ProtocolError e) { System.err.println(e.getMessage()); return 1; }
        return 0;
    }

    /** The reading level as a person reads it: Simple, Familiar or Original. */
    private static String levelWord(Explain.Rung rung) {
        return switch (rung) { case beginner -> "Simple"; case familiar -> "Familiar"; default -> "Original"; };
    }

    private static void printReading(Explain.Reading r) {
        String head = (r.term().isEmpty() ? r.of() : "\"" + r.term() + "\"" + (r.of().isEmpty() ? "" : " in " + r.of())) + " · " + levelWord(r.rung())
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

    /** researchzosho bridges [<area>] [--dry] [--propose N] [--sources l,w] [--reach r] [--strict|--loose] [--toward a] [--away w] [--since d] | list | accept <q> | dismiss <q> | settings [<area>] [dials] | measure */
    static int bridges(LibraryStore store, String[] args) throws Exception {
        var a = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        a.putObject("patron").put("did", "person").put("name", System.getProperty("user.name", "person")).put("runtime", "cli");
        String op = args.length > 2 ? args[2] : "list";
        int i = 3;
        switch (op) {
            case "list", "measure" -> a.put("op", op);
            case "accept", "dismiss" -> { if (args.length < 4) { System.err.println("usage: researchzosho bridges " + op + " <question…>"); return 2; } a.put("op", op); a.put("question", String.join(" ", java.util.Arrays.asList(args).subList(3, args.length))); i = args.length; }
            case "settings" -> { a.put("op", "settings"); if (args.length > 3 && !args[3].startsWith("--")) { a.put("area", args[3]); i = 4; } }
            case "distance" -> { if (args.length < 5) { System.err.println("usage: researchzosho bridges distance <area> <other>"); return 2; } a.put("op", "distance"); a.put("area", args[3]); a.put("other", args[4]); i = 5; }
            default -> { a.put("op", "run"); if (!op.startsWith("--")) a.put("area", op); else i = 2; }
        }
        try {
            for (; i < args.length; i++) {
                switch (args[i]) {
                    case "--dry" -> a.put("dry", true);
                    case "--propose" -> a.put("propose", flagInt(args, i++));
                    case "--per-night" -> a.put("per_night", flagInt(args, i++));
                    case "--sources" -> a.put("sources", flagValue(args, i++));
                    case "--reach" -> a.put("reach", flagValue(args, i++));
                    case "--strict" -> a.put("strict", true);
                    case "--loose" -> a.put("strict", false);
                    case "--toward" -> a.put("toward", flagValue(args, i++));
                    case "--away" -> a.put("away", flagValue(args, i++));
                    case "--since" -> a.put("since", flagValue(args, i++));
                    case "--via" -> a.put("via", flagValue(args, i++));
                    default -> { System.err.println("unknown flag " + args[i]); return 2; }
                }
            }
        } catch (IllegalArgumentException e) { System.err.println(e.getMessage()); return 2; }
        com.fasterxml.jackson.databind.node.ObjectNode r;
        try { r = new LibraryProtocol(store).bridges(a); } catch (ProtocolError e) { System.err.println(e.getMessage()); return 1; }
        switch (a.path("op").asText()) {
            case "run" -> {
                System.out.println(r.path("summary").asText());
                for (var p : r.path("proposals")) {
                    System.out.println("  " + p.path("a_label").asText() + "  ↔  " + p.path("c_label").asText() + "   [" + p.path("sensor").asText() + "] via " + String.join(", ", java.util.stream.StreamSupport.stream(p.path("via").spliterator(), false).map(com.fasterxml.jackson.databind.JsonNode::asText).toList()) + "   (score " + p.path("score").asText() + ", " + (p.path("hops").asInt() < 0 ? "not connected on the map" : p.path("hops").asInt() + " step(s) apart on the map") + (p.path("random").asBoolean() ? ", random pick" : "") + ")");
                    for (var path : p.path("paths")) System.out.println("      path: " + path.asText());
                    for (var j : p.path("from_outside")) {
                        System.out.println("      from outside the library: " + j.path("middle").asText());
                        System.out.println("        with " + p.path("a_label").asText() + ": " + j.path("with_a").asText());
                        System.out.println("        with " + p.path("c_label").asText() + ": " + j.path("with_c").asText());
                    }
                    System.out.println("    ? " + p.path("question").asText() + (p.path("filed").asBoolean(false) ? "   [filed]" : ""));
                }
                for (var t : r.path("tried_outside")) System.out.println("  tried outside the library: " + t.asText());
                for (var n : r.path("not_novel")) System.out.println("  already known: " + n.path("a").asText() + " ↔ " + n.path("c").asText() + " — " + n.path("sources_naming_both").asInt() + " source(s) already name both");
                System.out.println("  " + r.path("next").asText());
            }
            case "list" -> {
                if (r.path("count").asInt() == 0) System.out.println("No proposed connections yet. researchzosho bridges looks for some.");
                for (var p : r.path("proposals")) {
                    System.out.println("  ? " + p.path("question").asText() + "\n      " + p.path("found_by").asText());
                    for (String line : p.path("evidence").asText("").split("\n")) if (!line.isBlank()) System.out.println("      " + line);
                }
                System.out.println("  " + r.path("next").asText());
            }
            case "accept" -> System.out.println("accepted: " + r.path("job_id").asText() + " tests it — researchzosho jobs " + r.path("job_id").asText());
            case "dismiss" -> System.out.println("dismissed: " + r.path("dismissed").asText());
            case "settings" -> System.out.println(r.path("area").asText() + ": " + r.path("settings").asText() + " per_night=" + r.path("per_night").asInt() + (r.path("changed").asBoolean() ? "  (saved)" : ""));
            case "distance" -> {
                System.out.println(r.path("summary").asText());
                if (r.path("shared_terms").size() > 0) System.out.println("  shared terms: " + String.join(", ", java.util.stream.StreamSupport.stream(r.path("shared_terms").spliterator(), false).map(com.fasterxml.jackson.databind.JsonNode::asText).toList()));
                for (var path : r.path("paths")) System.out.println("  path: " + path.asText());
                System.out.println("  concepts: " + r.path("concepts_a").asInt() + " on one side, " + r.path("concepts_c").asInt() + " on the other; embedder: " + r.path("embedder").asText() + "; fold at " + r.path("fold_at").asText());
                for (var n : r.path("nearest_concepts")) System.out.println("    " + n.path("cosine").asText() + "  " + n.path("a").asText() + "  ~  " + n.path("c").asText() + (n.path("folded").asBoolean() ? "  (one node)" : ""));
            }
            default -> System.out.println("proposed " + r.path("proposed").asInt() + ", kept " + r.path("kept").asInt() + ", dismissed " + r.path("dismissed").asInt() + ", confirmed by a second source " + r.path("corroborated").asInt());
        }
        return 0;
    }

    /** check | reading | questions | bookmarks | meeting <file|url> [flags]: the launching points that share one shape. */
    static int launch(LibraryStore store, String[] args) throws Exception {
        String verb = args[1];
        if (args.length < 3) { System.err.println("usage: researchzosho " + verb + " <file|url> [--title <t>] [--collection <name>] [--verify] [--watch] [--as frontier|runs] [--folder <f>] [--limit <n>] [--no-citations]"); return 2; }
        var a = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        String what = args[2];
        if (what.startsWith("http://") || what.startsWith("https://")) a.put("url", what); else a.put("path", Path.of(what).toAbsolutePath().normalize().toString());
        try {
            for (int i = 3; i < args.length; i++) {
                switch (args[i]) {
                    case "--title" -> a.put("title", flagValue(args, i++));
                    case "--collection" -> a.put("collection", flagValue(args, i++));
                    case "--verify" -> a.put("verify", true);
                    case "--watch" -> a.put("watch", true);
                    case "--as" -> a.put("as", flagValue(args, i++));
                    case "--folder" -> a.put("folder", flagValue(args, i++));
                    case "--limit" -> a.put("limit", flagInt(args, i++));
                    case "--no-citations" -> a.put("fetch_citations", false);
                    default -> { System.err.println("unknown flag " + args[i]); return 2; }
                }
            }
        } catch (IllegalArgumentException e) { System.err.println(e.getMessage()); return 2; }
        a.putObject("patron").put("did", "person").put("name", System.getProperty("user.name", "person")).put("runtime", "cli");
        LibraryProtocol p = new LibraryProtocol(store);
        com.fasterxml.jackson.databind.node.ObjectNode r;
        try {
            r = switch (verb) {
                case "check" -> p.check(a);
                case "reading" -> p.reading(a);
                case "questions" -> p.questions(a);
                case "bookmarks" -> p.bookmarks(a);
                default -> p.meeting(a);
            };
        } catch (ProtocolError e) { System.err.println(e.getMessage()); return 1; }
        System.out.println(r.path("summary").asText());
        for (var q : r.path("questions_filed")) System.out.println("    ? " + q.asText());
        for (var q : r.path("questions_already_open")) System.out.println("    = " + q.asText() + "  (already open)");
        for (var c : r.path("claims_to_check")) System.out.println("    • " + c.asText());
        for (var d : r.path("decisions")) System.out.println("    ✓ " + d.asText());
        for (var c : r.path("citations")) System.out.println("    " + c.path("state").asText() + ": " + c.path("locator").asText() + (c.hasNonNull("problem") ? " — " + c.path("problem").asText() : ""));
        for (var e : r.path("entries")) System.out.println("    " + e.path("state").asText() + ": " + e.path("title").asText() + (e.hasNonNull("problem") ? " — " + e.path("problem").asText() : ""));
        for (var b : r.path("bookmarks")) System.out.println("    " + b.path("state").asText() + ": " + (b.path("title").asText().isEmpty() ? b.path("url").asText() : b.path("title").asText()) + (b.hasNonNull("problem") ? " — " + b.path("problem").asText() : ""));
        for (var q : r.path("questions")) if (q.hasNonNull("job_id")) System.out.println("    run " + q.path("job_id").asText() + ": " + q.path("question").asText()); else if (q.path("filed").asBoolean(false)) System.out.println("    ? " + q.path("question").asText());
        if (r.path("remaining").asInt() > 0) System.out.println("  " + r.path("remaining").asInt() + " more in the file; --limit takes more");
        if (r.hasNonNull("verify_job_id")) System.out.println("  checking the claims: " + r.path("verify_job_id").asText() + " — researchzosho jobs " + r.path("verify_job_id").asText());
        System.out.println("  " + r.path("next").asText());
        return 0;
    }

    /** researchzosho items <file|url> [--lens "…"] [--as frontier|runs|none] [--column C] [--title T] [--limit N]: a list of things as a starting point. */
    static int items(LibraryStore store, String[] args) throws Exception {
        if (args.length < 3) { System.err.println("usage: researchzosho items <list.txt|list.md|list.csv|url|calibre-library|metadata.db> [--lens \"{item}: …\"] [--as frontier|runs|none] [--column <name>] [--title <t>] [--limit <n>] [--match <text>]... [--sample <n>]"); return 2; }
        var a = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        String what = args[2];
        if (what.startsWith("http://") || what.startsWith("https://")) a.put("url", what); else a.put("path", Path.of(what).toAbsolutePath().normalize().toString());
        try {
            for (int i = 3; i < args.length; i++) {
                switch (args[i]) {
                    case "--lens" -> a.put("lens", flagValue(args, i++));
                    case "--as" -> a.put("as", flagValue(args, i++));
                    case "--column" -> a.put("column", flagValue(args, i++));
                    case "--title" -> a.put("title", flagValue(args, i++));
                    case "--collection" -> a.put("collection", flagValue(args, i++));
                    case "--limit" -> a.put("limit", flagInt(args, i++));
                    case "--match" -> { if (!a.has("match")) a.putArray("match"); ((com.fasterxml.jackson.databind.node.ArrayNode) a.get("match")).add(flagValue(args, i++)); }
                    case "--sample" -> a.put("sample", flagInt(args, i++));
                    default -> { System.err.println("unknown flag " + args[i]); return 2; }
                }
            }
        } catch (IllegalArgumentException e) { System.err.println(e.getMessage()); return 2; }
        a.putObject("patron").put("did", "person").put("name", System.getProperty("user.name", "person")).put("runtime", "cli");
        com.fasterxml.jackson.databind.node.ObjectNode r;
        try { r = new LibraryProtocol(store).items(a); } catch (ProtocolError e) { System.err.println(e.getMessage()); return 1; }
        System.out.println(r.path("summary").asText());
        System.out.println("  asked about each item: " + r.path("lens").asText());
        for (var it : r.path("items")) {
            String mark = it.path("held").isNull() ? "  " : "= ";
            System.out.println("  " + mark + it.path("item").asText() + (it.hasNonNull("note") ? " — " + it.path("note").asText() : "")
                    + (it.path("held").isNull() ? "" : "  (in the library: " + it.path("held").asText() + ")") + (it.path("filed").asBoolean(false) ? "  ? question saved" : ""));
        }
        if (r.path("remaining").asInt() > 0) System.out.println("  " + r.path("remaining").asInt() + " more item(s) in the list; --limit takes more");
        for (var j : r.path("jobs")) System.out.println("  research run: " + j.asText() + " — researchzosho jobs " + j.asText());
        System.out.println("  " + r.path("next").asText());
        return 0;
    }

    /** researchzosho survey <thing> [--kind k] [--pick 1,3] [--do "…"]: a repository, a paper, a page or an issue tracker as a starting point. Without flags: read and offer directions. */
    static int survey(LibraryStore store, String[] args) throws Exception {
        if (args.length < 3) { System.err.println("usage: researchzosho survey <folder|file|url> [--kind repo|paper|site|issues] [--pick 1,3] [--do \"what to research\"]"); return 2; }
        var a = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        String what = args[2];
        if (what.startsWith("http://") || what.startsWith("https://") || what.startsWith("git@")) a.put("url", what); else a.put("path", Path.of(what).toAbsolutePath().normalize().toString());
        if (args[1].equals("repo")) a.put("kind", "repo");
        String picks = null, own = null;
        try {
            for (int i = 3; i < args.length; i++) {
                switch (args[i]) {
                    case "--kind" -> a.put("kind", flagValue(args, i++));
                    case "--pick" -> picks = flagValue(args, i++);
                    case "--do" -> own = flagValue(args, i++);
                    default -> { System.err.println("unknown flag " + args[i]); return 2; }
                }
            }
        } catch (IllegalArgumentException e) { System.err.println(e.getMessage()); return 2; }
        a.putObject("patron").put("did", "person").put("name", System.getProperty("user.name", "person")).put("runtime", "cli");
        LibraryProtocol p = new LibraryProtocol(store);
        com.fasterxml.jackson.databind.node.ObjectNode r;
        if (picks == null && own == null) {
            try { r = p.survey(a); } catch (ProtocolError e) { System.err.println(e.getMessage()); return 1; }
            System.out.println(r.path("summary").asText());
            System.out.println();
            System.out.println("  " + r.path("what_it_is").asText());
            if (!r.path("summary_text").asText().isBlank()) System.out.println("  " + r.path("summary_text").asText());
            if (r.path("claims").size() > 0) { System.out.println("  claims:"); for (var x : r.path("claims")) System.out.println("    - " + x.asText()); }
            if (r.path("rests_on").size() > 0) { System.out.println("  rests on:"); for (var x : r.path("rests_on")) System.out.println("    - " + x.asText()); }
            if (r.path("leaves_open").size() > 0) { System.out.println("  leaves open:"); for (var x : r.path("leaves_open")) System.out.println("    - " + x.asText()); }
            System.out.println();
            System.out.println("  directions (researchzosho survey " + what + " --pick 1,3 runs them; --do \"…\" runs your own):");
            for (var o : r.path("options")) System.out.println("    " + o.path("n").asInt() + ". " + o.path("question").asText());
            for (var q : r.path("already_open")) System.out.println("    = " + q.asText() + "  (already open)");
            return 0;
        }
        if (picks != null) {
            a.put("op", "pick"); a.put("picks", picks);
            try { r = p.survey(a); } catch (ProtocolError e) { System.err.println(e.getMessage()); return 1; }
            for (var j : r.path("runs")) System.out.println(j.hasNonNull("job_id") ? "  " + j.path("n").asInt() + ". " + j.path("question").asText() + " → " + j.path("job_id").asText() : "  " + j.path("error").asText());
            System.out.println(r.path("summary").asText());
        }
        if (own != null) {
            a.put("op", "do"); a.put("question", own); a.remove("picks");
            try { r = p.survey(a); } catch (ProtocolError e) { System.err.println(e.getMessage()); return 1; }
            for (var j : r.path("runs")) System.out.println("  " + j.path("question").asText() + " → " + j.path("job_id").asText());
            System.out.println(r.path("summary").asText());
        }
        System.out.println("  researchzosho jobs follows them");
        return 0;
    }

    /** researchzosho remove <id> [--report-only | --claims-only] [--yes]: a report or a claim out of the library for good, after showing the plan. */
    static int remove(LibraryStore store, String[] args) throws Exception {
        if (args.length < 3) { System.err.println("usage: researchzosho remove <I-…|F-…> [--report-only | --claims-only] [--yes]"); return 2; }
        var a = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        a.put("id", args[2]);
        boolean yes = false;
        for (int i = 3; i < args.length; i++) {
            switch (args[i]) {
                case "--report-only" -> a.put("what", "report");
                case "--claims-only" -> a.put("what", "claims");
                case "--yes" -> yes = true;
                default -> { System.err.println("unknown flag " + args[i]); return 2; }
            }
        }
        a.putObject("patron").put("did", "person").put("name", System.getProperty("user.name", "person")).put("runtime", "cli");
        LibraryProtocol p = new LibraryProtocol(store);
        com.fasterxml.jackson.databind.node.ObjectNode plan;
        try { plan = p.remove(a.deepCopy().put("dry", true)); } catch (ProtocolError e) { System.err.println(e.getMessage()); return 1; }
        System.out.println(plan.path("plan").asText());
        if (plan.path("claims_go").size() == 0 && !plan.path("report_goes").asBoolean()) { System.out.println("Nothing to delete."); return 0; }
        if (!yes) {
            System.out.print("Delete these? This cannot be undone. [y/N] ");
            String line = System.console() != null ? System.console().readLine() : new java.io.BufferedReader(new java.io.InputStreamReader(System.in)).readLine();
            if (line == null || !line.strip().toLowerCase(java.util.Locale.ROOT).startsWith("y")) { System.out.println("Nothing was deleted."); return 0; }
        }
        com.fasterxml.jackson.databind.node.ObjectNode r;
        try { r = p.remove(a); } catch (ProtocolError e) { System.err.println(e.getMessage()); return 1; }
        for (var x : r.path("removed")) System.out.println("  deleted " + x.asText());
        System.out.println(r.path("summary").asText());
        return 0;
    }

    /** researchzosho holdings <words…>: what the person's lists hold. */
    static int holdings(LibraryStore store, String[] args) throws Exception {
        if (args.length < 3) { System.err.println("usage: researchzosho holdings <title words, author, year…> [--limit <n>]"); return 2; }
        var a = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        StringBuilder q = new StringBuilder();
        for (int i = 2; i < args.length; i++) { if (args[i].equals("--limit")) { a.put("limit", flagInt(args, i++)); continue; } if (q.length() > 0) q.append(' '); q.append(args[i]); }
        a.put("query", q.toString());
        a.putObject("patron").put("did", "person").put("name", System.getProperty("user.name", "person")).put("runtime", "cli");
        com.fasterxml.jackson.databind.node.ObjectNode r;
        try { r = new LibraryProtocol(store).holdings(a); } catch (ProtocolError e) { System.err.println(e.getMessage()); return 1; }
        System.out.println(r.path("summary").asText());
        for (var m : r.path("matches")) System.out.println("  " + m.path("item").asText() + (m.hasNonNull("note") ? " — " + m.path("note").asText() : "") + "   [" + m.path("list").asText() + "]");
        return 0;
    }

    /** researchzosho absorb <file|url> [--title T] [--collection N] [--verify] [--limit N]: a conversation with another assistant, as a starting point. */
    static int absorb(LibraryStore store, String[] args) throws Exception {
        if (args.length < 3) { System.err.println("usage: researchzosho absorb <transcript|export.json|url> [--title <t>] [--collection <name>] [--verify] [--limit <n>]"); return 2; }
        var a = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        String what = args[2];
        if (what.startsWith("http://") || what.startsWith("https://")) a.put("url", what); else a.put("path", Path.of(what).toAbsolutePath().normalize().toString());
        try {
            for (int i = 3; i < args.length; i++) {
                switch (args[i]) {
                    case "--title" -> a.put("title", flagValue(args, i++));
                    case "--collection" -> a.put("collection", flagValue(args, i++));
                    case "--verify" -> a.put("verify", true);
                    case "--limit" -> a.put("limit", flagInt(args, i++));
                    default -> { System.err.println("unknown flag " + args[i]); return 2; }
                }
            }
        } catch (IllegalArgumentException e) { System.err.println(e.getMessage()); return 2; }
        a.putObject("patron").put("did", "person").put("name", System.getProperty("user.name", "person")).put("runtime", "cli");
        com.fasterxml.jackson.databind.node.ObjectNode r;
        try { r = new LibraryProtocol(store).absorb(a); } catch (ProtocolError e) { System.err.println(e.getMessage()); return 1; }
        System.out.println(r.path("summary").asText());
        for (var t : r.path("threads")) {
            System.out.println("  " + t.path("title").asText() + " — " + t.path("turns").asInt() + " turns → " + t.path("raw").asText());
            for (var q : t.path("questions_filed")) System.out.println("    ? " + q.asText());
            for (var q : t.path("questions_already_open")) System.out.println("    = " + q.asText() + "  (already open)");
            for (var c : t.path("claims_to_check")) System.out.println("    • " + c.asText());
        }
        if (r.path("remaining").asInt() > 0) System.out.println("  " + r.path("remaining").asInt() + " more conversation(s) in the file; --limit takes more");
        if (r.hasNonNull("verify_job_id")) System.out.println("  checking the claims: " + r.path("verify_job_id").asText() + " — researchzosho jobs " + r.path("verify_job_id").asText());
        else if (r.path("claims_to_check").asInt() > 0) System.out.println("  --verify starts one research run that checks the claims. researchzosho research ask \"" + r.path("main_question").asText().replace("\"", "'") + "\" researches the subject");
        return 0;
    }

    static int add(LibraryStore store, String[] args) throws Exception {
        if (args.length < 3) { System.err.println("usage: researchzosho add <file|url|folder> [title] [--for <url>] [--collection <name>] [--register] [--link] [--survey]"); return 2; }
        String what = args[2];
        // a folder: the corpus path
        if (!what.startsWith("http://") && !what.startsWith("https://") && Files.isDirectory(Path.of(what))) {
            String coll = null; boolean register = false, link = false, survey = false;
            for (int i = 3; i < args.length; i++) {
                switch (args[i]) {
                    case "--collection" -> coll = flagValue(args, i++);
                    case "--register" -> register = true;
                    case "--link" -> link = true;
                    case "--survey" -> survey = true;
                    default -> { }
                }
            }
            Path dir = Path.of(what).toAbsolutePath().normalize();
            if (coll == null) coll = Corpus.nameFor(dir);
            if (survey) {
                Corpus.Survey sv = Corpus.survey(store, dir, true);
                System.out.println(dir + ": " + sv.line());
                System.out.println("  keep it:  researchzosho add " + what + " --collection " + coll + "        (the text is copied into the library, the files are not)");
                System.out.println("  link it:  researchzosho add " + what + " --collection " + coll + " --link (read in place; nothing copied)");
                return 0;
            }
            Corpus.Outcome o = Corpus.addFolder(store, dir, coll, true, link);
            if (register) Corpus.register(store, coll, dir, link);
            System.out.println("collection " + coll + ": " + o.line() + (link ? " — read in place, nothing copied" : "") + (register ? " — registered, nightly maintenance rescans it" : ""));
            for (String pr : o.problems()) System.out.println("  " + pr);
            System.out.println("  ask them: researchzosho research ask \"…\" --shelves, or library_research {\"sources\":\"shelves\",\"collections\":[\"" + coll + "\"]}");
            return 0;
        }
        // a document supplied for a locator the runner could not read
        String forUrl = null; boolean linkFile = false;
        java.util.List<String> rest = new java.util.ArrayList<>();
        for (int i = 3; i < args.length; i++) { if (args[i].equals("--for")) forUrl = flagValue(args, i++); else if (args[i].equals("--link")) linkFile = true; else rest.add(args[i]); }
        if (linkFile && !what.startsWith("http://") && !what.startsWith("https://")) {
            Path f = Path.of(what).toAbsolutePath().normalize();
            Path raw = Corpus.addFile(store, f, "", true);
            if (raw == null) { System.err.println("no text could be read from " + f.getFileName()); return 1; }
            System.out.println("linked " + (rest.isEmpty() ? RawCapture.read(raw)[1] : String.join(" ", rest)) + "\n  read in place from " + f + ", nothing copied → " + raw.getFileName());
            return 0;
        }
        if (forUrl != null) {
            Path f = Path.of(what).toAbsolutePath().normalize();
            var doc = org.researchzosho.tools.DocText.convert(Files.readAllBytes(f), what);
            if (doc.text().isBlank()) { System.err.println("no text could be extracted (" + doc.kind() + ")"); return 1; }
            Path raw = Requests.supply(store, forUrl, doc.text(), rest.isEmpty() ? doc.title() : String.join(" ", rest));
            System.out.println("supplied: " + forUrl + " ← " + f.getFileName() + " (" + doc.text().length() + " chars) → " + raw.getFileName()
                    + "\n  from now on this document is read for that url");
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
        if (raw == null) { System.err.println("Not saved: this machine has no library (`researchzosho init`), or the page was refused"); return 1; }
        String[] onDisk = RawCapture.read(raw);
        boolean same = onDisk[2].strip().equals(doc.text().strip());
        System.out.println("saved to the library [" + doc.kind() + "] " + (title.isEmpty() ? locator : title));
        if (meta != null) System.out.println("  citation: " + meta.edition());
        if (!cx.isEmpty()) System.out.println("  " + cx.figures().size() + " figure caption(s), " + cx.tables().size() + " table caption(s), " + cx.rows().size() + " table row(s) found in the text");
        System.out.println("  " + onDisk[2].length() + " chars on disk → " + raw
                + (same ? "" : "  (an earlier saved copy of this url from today was kept)"));
        System.out.println("  researchzosho ask finds it now. `researchzosho review` does not read saved pages:"
                + " ask about it, or research it, to turn it into claims");
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
        System.out.println(reviewed == 0 ? "Nothing to review: no draft reports"
                : reviewed + " report(s) reviewed");
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
        if (all.isEmpty()) { System.out.println("The inbox is empty."); return 0; }
        var rows = new java.util.ArrayList<com.fasterxml.jackson.databind.node.ObjectNode>();
        for (var o : all) if (LibraryProtocol.inboxMatches(o, f)) rows.add(o);
        System.out.println(rows.size() + (rows.size() == all.size() ? "" : " of " + all.size()) + " item(s) waiting for your decision:");
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
                    System.out.println("retired " + f.id() + ": no longer used, its record is kept");
                }
            }
            case "dispute" -> {
                if (args.length < 4) { System.err.println("usage: researchzosho dispute <id> <why>"); return 2; }
                Finding f = c.dispute(id, String.join(" ", Arrays.copyOfRange(args, 3, args.length)));
                System.out.println("disputed " + f.id() + ": an open question was added");
            }
            default -> { return 2; }
        }
        return 0;
    }
}
