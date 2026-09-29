package org.researchzosho.librarian;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import org.researchzosho.drive.DriveClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.StreamSupport;
import org.researchzosho.Config;
import org.researchzosho.Version;
import org.researchzosho.mcp.McpServer;
import org.researchzosho.records.RecordSources;
import org.researchzosho.records.RecordSource;
import org.researchzosho.tools.DocText;
import org.researchzosho.tools.ContentPolicy;
import org.researchzosho.tools.Fetch;
import org.researchzosho.tools.PageCheck;
import org.researchzosho.tools.ScholarSearch;
import org.researchzosho.tools.WebSearchTool;
import org.slf4j.LoggerFactory;
import org.researchzosho.drive.Declined;
/**
 * {@code researchzosho <verb>} — the Librarian's own command surface, in its own class:
 * the bounded-module seam a separate repo would cut along.
 */
public final class LibrarianCli {

    private LibrarianCli() { }

    static final String USAGE = usage();

    /**
     * The help, with what the fields add written in from the fields themselves ({@link Profile#example}): the core's text names none of
     * them.
     */
    private static String usage() {
        List<String> all = new ArrayList<>(), modules = new ArrayList<>();
        String example = "";
        for (Profile p : Profiles.all()) {
            all.add(p.name());
            if (p.joinsOnlyWhenAsked()) modules.add(p.name());
            if (example.isEmpty() && !p.example().isBlank()) example = p.example();
        }
        StringBuilder flags = new StringBuilder(), said = new StringBuilder();
        for (String m : modules) { flags.append("--").append(m).append(" | "); said.append(" --").append(m).append(" researches it in ").append(m).append(" mode."); }
        return USAGE_TEXT.replace("{FIELD_FLAGS}", flags.toString()).replace("{FIELD_MODES}", said.toString())
                .replace("{FIELDS}", String.join(", ", all) + ". All on until you disable one" + (modules.isEmpty() ? "" : "; " + String.join(", ", modules) + (modules.size() == 1 ? " acts" : " act") + " only when you ask for it"))
                .replace("{PROFILE_EXAMPLE}", example.isEmpty() ? "" : ", e.g. `" + example + "`");
    }

    private static final String USAGE_TEXT = """
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
              add <folder> [--collection N] [--register] [--link] [--survey] [--pictures]
                                           add every document under a folder, as a collection. --link reads the files where they are (nothing is copied). --survey only counts them
              absorb <transcript|export|url> [--verify]
                                           a conversation you had with another assistant. It is saved to the library. Your questions become open questions. Its claims are listed for checking
              survey <repo|paper|url|issues> [--kind k] [--pick 1,3] [--do "…"]
                                           read a code repository, a paper, a website or an issue tracker. Saves one draft claim on what it is and lists research directions. --pick runs them, --do runs your own question
              items <list|csv|url|calibre-library> [--lens "…"] [--as frontier|runs|none] [--match T]… [--sample N]
                                           a list of things (books, tools, an inventory, a Calibre library or its metadata.db). The list is saved whole, each item is checked against the library, and a question is made for each selected item
              holdings <words…>            search the lists you saved. Every word must appear in an entry
              db add <name> <address> [--hide col,table.col]   give the library read-only access to a database
              db list · schema <name> · query <name> "<sql>" [--limit N] · remove <name> · drivers · driver install <kind>
                                           your databases: SQLite, PostgreSQL, MySQL or MariaDB in the box. SQL Server, MongoDB and DuckDB (also CSV and Parquet files) are fetched on request
              survey --from <report id> [--top 5] [--do "…"]   the repositories a report names, the first N cloned into the library and read; --do files a research question about each
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
              research ask "<question…>" [--depth] [--quick] [--shelves|--web] [--max-turns N] [--max-minutes N] [{FIELD_FLAGS}--field <name>]
                                           send a question. The service picks it up within seconds.{FIELD_MODES}
              research                     how research runs share the model: workers, pause, window. Today's turns by user, and what is running
              research workers <n> · pause · resume · stop <J-…> · window <HH:MM-HH:MM|off>    change them now. A running question follows at its next step. stop ends one research run there
              jobs [<J-…>]                 every research run, queued and running first, then the last finished. One id: its state, progress, wait, and where its report went
              stats [<n>]                  the research runs side by side: turns, rounds, citation checks, sources, fetches. The last n rows (default 20) and the totals
              bib <id…>                    BibTeX for everything a claim or report cites (DOI / arXiv / PubMed looked up)
              perspectives <question…>     who studies this and what each would ask. Sub-questions for a research run
              web [status | signin on|off]  the web pages: open to everyone by default. signin on asks for a reader token before a question can be sent
              records [list | test <source> <query…> | key <source> <key> | login <source>]
                                           the collections of records a research run searches by name: newspapers, patents, scanned books, archive catalogues. test searches one now
              sources [list | trust <host> [why] | ban <host> [why] | forget <host>]
                                           your own rules for sources. Trusted ones count as primary sources. Banned ones are dropped from searches and reviews
              settle [<I-…>…]              take a report's claims, file them under subjects and add them to the map, now. All waiting reports when none is named
              export <id> [--pdf|--md] [--beginner|--familiar] [--out FILE]
                                           an entry, or its Simple or Familiar version, as a Markdown or PDF file
              sharpen <question…>          refine a rough question into a better one: the question to run, what it assumed, what you already have. Runs nothing
              search [status|start|stop|test <query>|papers <query>]   which web search service answers. SearXNG through Docker. Papers by DOI
              models [--all]               which model fits this machine's graphics card, measured, with the command that runs it
              bedrock models|test <model>|use <model> [--region r] [--profile p] [--embed]
                                           the models of your own AWS account (Amazon Bedrock) as this library's model. Sign in to AWS first
              model install|status|use <address> [<model>]|switch [<name>]|prune|stop|uninstall
                                           the model on this machine, on demand: it starts when a research run needs it and stops after 20 idle
                                           minutes (install [--file <gguf>] [--gpu <index>] [--idle-minutes N] [--share])
              embed [status|start [port] [--cpu]|stop|test]   the embeddings server (search by meaning): what is configured. Text Embeddings Inference through Docker
              explain <id> [--beginner|--familiar|--written] [--fresh]
                                           an entry explained at a reading level (Simple, Familiar or Original). From the library only, checked
              explain "<term>" [--in <id>] [--now|--full]
                                           a term as it is used in an entry. When the library does not explain it, a quick look or full research
              map <name|id> [--depth N]    the map around a person, place, work or concept. The links are claims
              graph merge <a> <b> [--because <why>] · unmerge <a> [<b>] [--because <why>] · alias <node> <name…> · kind <node> <kind> · link <node> <Qid> · proposals
                                           merge joins two names into one node and writes down why; unmerge takes a merge back, and the claims are about the first name again
              profile list · enable <name> · disable <name>       fields on top of the core: {FIELDS}
              <profile> <verb…>            a profile's own verbs{PROFILE_EXAMPLE}
              review [drive]               review draft reports with the model
              inbox [--report I-…] [--subject s] [--kind k] [--tier t] [--confidence c] [--writer w] [--state draft|stale] [--language x] [--grep words] [--by-report]   what is waiting for you: drafts and stale reviews
              looked [<words>] | looked move <claim id…> | looked add <person> --where <site> --what <words>   what earlier searches looked for and did not find, each with its date and where it looked; move puts an old "nothing was found" claim there and retires the claim; add writes down a search you made yourself
              accept <id…>|--all|--report I-… · dispute <id> <why> · retire <id…>|--report I-…   your decisions on claims
              catalog [drive] [--accept-all]   file claims under the known subjects. Propose new subjects
              catalog seed [--min N]       start the subject list from the claims: the subjects N or more claims (default 2) were given
              shelf add <name> <query> [days] · list · every <name> <days> · park|unpark <name> · remove <name>   the saved searches nightly maintenance runs again
              serials                      check the saved searches for new sources. List overdue reviews
              tonight                      what nightly maintenance will do at its next run: searches due, questions it will research
              update [now | auto on|off]   compare the installed release with the latest. Install it. Let the service do it after nightly maintenance
              questions [list [--type t] [--parked|--all] [--report I-…] [--fate f] [--who text] [--subject s] [--language x] [--grep words] [--by-report] [--hints] | add <q…> | file <file> | next|later|park|unpark|drop <n> [park: --why <reason>] | tidy | budget <n> | tonight <n>]   the queue the nightly research draws from. A report's leftover questions wait parked
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
              service install|uninstall|restart [--yes]|status [--exec <launcher>] [--host H] [--port P] [--crew-hour H]
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
                String name = args.length > 5 ? String.join(" ", Arrays.copyOfRange(args, 5, args.length)) : "";
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
                    var r = AccessRequests.deny(store, args[3], args.length > 4 ? String.join(" ", Arrays.asList(args).subList(4, args.length)) : "");
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
                        String secret = ""; List<String> kinds = new ArrayList<>();
                        for (int i = 6; i < args.length; i++) {
                            if (args[i].equals("--secret")) secret = flagValue(args, i++);
                            else if (args[i].equals("--events")) kinds = List.of(flagValue(args, i++).split(","));
                        }
                        if (secret.isEmpty()) { byte[] b = new byte[24]; new SecureRandom().nextBytes(b); secret = Base64.getUrlEncoder().withoutPadding().encodeToString(b); }
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

    /** graph merge and graph unmerge. */
    private static int mergeOrUnmerge(LibraryStore store, String[] args) throws Exception {
        List<String> names = new ArrayList<>(); String because = "";
        for (int i = 3; i < args.length; i++) { if (args[i].equals("--because") && i + 1 < args.length) because = args[++i]; else names.add(args[i]); }
        if (args[2].equals("merge")) {
            if (names.size() != 2) { System.err.println("usage: researchzosho graph merge \"<name>\" \"<the name it is joined to>\" [--because \"<why they are one>\"]\n  The first name becomes another name of the second, and every claim about the first is about the second."); return 2; }
            Graph.Merged m = Graph.merge(store, names.get(0), names.get(1), "person", because);
            System.out.println(names.get(0) + " is now another name of " + names.get(1) + ". " + claimsSaid(m.claims(), "about " + names.get(0), "now about " + names.get(1))
                    + "\nTo take this back: researchzosho graph unmerge \"" + names.get(0) + "\"");
        } else {
            if (names.isEmpty() || names.size() > 2) { System.err.println("usage: researchzosho graph unmerge \"<the name that was joined>\" [\"<the name it was joined to>\"] [--because \"<why they are two>\"]\n  Takes back a merge: the name is its own node again, with its own claims."); return 2; }
            try {
                Graph.Merged m = Graph.unmerge(store, names.get(0), names.size() > 1 ? names.get(1) : "", "person", because);
                System.out.println(names.get(0) + " is its own node again. " + claimsSaid(m.claims(), "written about " + names.get(0), "about " + names.get(0) + " again")
                        + "\nTo join them again: researchzosho graph merge \"" + names.get(0) + "\" \"" + m.to() + "\"");
            } catch (IllegalArgumentException e) { System.err.println(e.getMessage()); return 1; }
        }
        return 0;
    }

    static int graph(LibraryStore store, String[] args) throws Exception {
        if (args.length < 3) { System.err.println("usage: researchzosho graph merge <a> <b> [--because <why>] | unmerge <a> [<b>] [--because <why>] | alias <node> <name…> | kind <node> <kind> | link <node> <Qid> | proposals"); return 2; }
        switch (args[2]) {
            case "merge", "unmerge" -> {
                return mergeOrUnmerge(store, args);
            }
            case "alias" -> {
                if (args.length < 5) { System.err.println("usage: researchzosho graph alias <node> <name…>"); return 2; }
                Graph.alias(store, args[3], Arrays.asList(args).subList(4, args.length));
                System.out.println("aliased");
            }
            case "kind" -> {
                if (args.length < 5) { System.err.println("usage: researchzosho graph kind <node> <kind>"); return 2; }
                String kept = Graph.setKind(store, args[3], args[4]);
                System.out.println("The library now files " + args[3] + " under the kind \"" + kept + "\".");
                // the per-person flags of 0.4 and the builds before 0.5.0
                if (Arrays.asList(args).subList(5, args.length).stream().anyMatch(a -> a.equals("--private") || a.equals("--public") || a.equals("--by-dates")))
                    System.out.println("The library no longer hides people one by one, so --private, --public and --by-dates change nothing. Whoever may read your library sees everything in it; the command researchzosho reader decides who may read it.");
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

    /** "The 3 claims about A are now about B: F-0001, F-0002, F-0005." */
    static String claimsSaid(List<String> claims, String were, String now) {
        if (claims.isEmpty()) return "No claim is " + were + ".";
        List<String> codes = claims.stream().map(c -> c.replaceFirst("^(F-\\d+).*", "$1")).toList();
        return (claims.size() == 1 ? "The claim " + were + " is " : "The " + claims.size() + " claims " + were + " are ") + now + ": " + String.join(", ", codes.stream().limit(20).toList()) + (codes.size() > 20 ? " and " + (codes.size() - 20) + " more" : "") + ".";
    }

    static int profile(LibraryStore store, String[] args) throws Exception {
        String op = args.length > 2 ? args[2] : "list";
        switch (op) {
            case "list" -> {
                var on = Profiles.enabled(store);
                for (Profile p : Profiles.all()) System.out.println((on.contains(p.name()) ? "[on]  " : "[off] ") + p.name() + " — " + p.description()
                        + (p.joinsOnlyWhenAsked() && on.contains(p.name()) ? ". Used only when you ask for it: researchzosho research ask \"…\" --" + p.name() + ", its own commands below, or a yes when the library asks" : "")
                        + (p.usage().isEmpty() ? "" : "\n              " + p.usage()));
            }
            case "enable" -> { if (args.length < 4) { System.err.println("usage: researchzosho profile enable <name>"); return 2; } Profiles.enable(store, args[3]); System.out.println("enabled " + args[3]); }
            case "disable" -> { if (args.length < 4) { System.err.println("usage: researchzosho profile disable <name>"); return 2; } Profiles.disable(store, args[3]); System.out.println("disabled " + args[3]); }
            case "runs" -> { return runs(store, args); }
            default -> { System.err.println("usage: researchzosho profile list|enable <name>|disable <name>|runs [list|add <report> <field>|forget <report> <field>|upgrade]"); return 2; }
        }
        return 0;
    }

    /**
     * {@code profile runs}: the research runs a field was asked for, which make their reports and claims that field's work. {@code add} and
     * {@code forget} correct the list; {@code upgrade} records the runs from before runs were recorded, once.
     */
    static int runs(LibraryStore store, String[] args) throws Exception {
        String op = args.length > 3 ? args[3] : "list";
        switch (op) {
            case "list" -> {
                List<Fields.Row> rows = Fields.rows(store);
                if (rows.isEmpty()) System.out.println("No research run was asked for in a field's mode yet. When you send a question with the name of a field after it (research ask \"…\" --field <name>), the run is listed here with its report.");
                for (Fields.Row r : rows) System.out.println("  " + r.investigation() + (r.job().isEmpty() ? "" : " (" + r.job() + ")") + "  " + r.field() + " mode, " + howChosen(r.how()) + (r.date().isEmpty() ? "" : ", " + r.date()));
            }
            case "add", "forget" -> {
                if (args.length < 6) { System.err.println("usage: researchzosho profile runs " + op + " <report id> <field>\n  add makes a report's run the field's, so its claims are read as the field's work; forget takes that back"); return 2; }
                Profile p = Profiles.named(args[5]);
                if (p == null) { System.err.println("There is no field called " + args[5] + ". The fields are: " + String.join(", ", Profiles.all().stream().map(Profile::name).toList())); return 2; }
                if (store.investigation(args[4]) == null) { System.err.println("No report has the id " + args[4] + ". researchzosho search finds a report by its words."); return 2; }
                if (op.equals("add")) Fields.record(store, args[4], "", p.name(), "by-hand"); else Fields.forget(store, args[4], p.name());
                System.out.println(op.equals("add") ? "The run that filed " + args[4] + " is now recorded as a " + p.name() + " run: its claims are read as " + p.name() + "'s work."
                        : "The run that filed " + args[4] + " is no longer recorded as a " + p.name() + " run: its claims are read as ordinary claims.");
            }
            case "upgrade" -> {
                Fields.Migrated m = Fields.migrate(store);
                if (m == null) { System.out.println("This library was upgraded already. What it found is written in " + Fields.marker(store) + "."); return 0; }
                System.out.println("Recorded " + (m.fromLog() + m.byCommand() + m.fromQuestions()) + " earlier research run(s) as runs of a field: " + m.fromLog() + " because the research log says the field's rules joined the run, "
                        + m.byCommand() + " because the field's own command filed it, and " + m.fromQuestions() + " because it researched an open question the field's own command filed.");
                for (String n : m.notes()) System.out.println(n);
            }
            default -> { System.err.println("usage: researchzosho profile runs [list|add <report> <field>|forget <report> <field>|upgrade]"); return 2; }
        }
        return 0;
    }

    private static String howChosen(String how) {
        return switch (how) {
            case "cli-flag" -> "asked for on the command line";
            case "cli-yes" -> "a yes at the command line";
            case "web-box" -> "the box on the research page";
            case "mcp-field" -> "a program named the field";
            case "chat-yes" -> "a yes in the chat";
            case "explorer-line" -> "a question the field filed, researched at night";
            case "backfill-log" -> "found in the research log when this library was upgraded";
            case "backfill-command" -> "filed by the field's own command, found when this library was upgraded";
            case "backfill-question" -> "researched an open question the field's own command filed, found when this library was upgraded";
            case "backfill-rule" -> "put into the field's mode by an earlier build, and run in it";
            case "by-hand" -> "added by hand";
            default -> "by the field's own command";
        };
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
            var ps = new PrintStream(Files.newOutputStream(log, StandardOpenOption.CREATE, StandardOpenOption.APPEND), true, StandardCharsets.UTF_8);
            System.setOut(ps); System.setErr(ps);
        }
        // a model server of this machine's own, installed by an earlier release: bring it up to this one (the embeddings server beside the model)
        try { String up = ModelServer.upgrade(System.out); if (up.startsWith("!")) System.out.println("  model server not upgraded: " + up.substring(1)); } catch (Exception ignored) { }
        // the new version's table of models, read against this machine as it is today: said, never acted on
        try { String sm = ModelServer.suggestionNotice(); if (!sm.isEmpty()) System.out.println("  " + sm); } catch (Exception ignored) { }
        // what an update owes the library: things an earlier version saved wrongly are fixed by this one, once per version
        try { Repairs.Outcome fixed = Repairs.onceFor(store, Version.number() == null ? "dev" : Version.number()); if (fixed != null && !fixed.repaired().isEmpty()) { System.out.println("  repaired after the update: " + fixed.summary()); for (String r : fixed.repaired()) System.out.println("    " + r); } } catch (Exception e) { System.out.println("  repair skipped: " + e.getMessage()); }
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

    /**
     * Whether the verb needs a library that exists. `service status` and `service uninstall` do not: a person who deleted
     * their library can still see and remove the service.
     */
    static boolean needsLibrary(String[] args) {
        String verb = args.length > 1 ? args[1] : "";
        // updating replaces the program, not the library: a program that keeps ResearchZosho up to date asks before any library exists
        if (verb.equals("init") || verb.equals("update")) return false;
        return !verb.equals("service") || args.length > 2 && args[2].equals("install");
    }

    static int dispatch(String[] args, String baseUrl, String model) {
        if (args.length < 2) { System.err.print(USAGE); return 2; }
        LibraryStore store = LibraryStore.open();
        String verb = args[1];
        if (verb.equals("--version") || verb.equals("version") || verb.equals("-V")) { System.out.println("researchzosho " + Version.string()); return 0; }
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
                return new Setup(new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)), System.out,
                        Setup.liveProbe(), Setup.liveActs(), yes).run(port, service, claude);
            } catch (Exception e) { System.err.println("setup: " + e.getMessage()); return 1; }
        }
        try {
            if (verb.equals("model")) return ModelServer.command(args, 2, System.out);   // the machine's model server: no library needed
            if (needsLibrary(args) && !Files.isDirectory(store.root())) {
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
                    System.out.print(Arrays.asList(args).contains("--all") ? Models.describeAll() : Models.describe(Models.cardGb()));
                    return 0;
                }
                case "name" -> {
                    // the library's name, on its pages and in every answer; the folder's name until set
                    if (args.length < 3) { var id = store.identity(); System.out.println(id.name() + "  (" + id.id() + "; researchzosho name <a name…> changes it; the folder is " + store.root() + ")"); return 0; }
                    String n = String.join(" ", Arrays.copyOfRange(args, 2, args.length)).strip();
                    store.setName(n);
                    System.out.println(n.isEmpty() ? "the library is named after its folder again: " + store.identity().name() : "the library is now called " + store.identity().name() + " (catalog/library.md carries it; the pages show it after their next load)");
                    return 0;
                }
                case "init" -> {
                    store.init();
                    System.out.println("library created at " + store.root());
                    System.out.println("Research runs now save their drafts here.");
                }
                case "status" -> { status(store); String up = Version.updateNotice(); if (!up.isEmpty()) System.out.println("\n" + up); String sm = ModelServer.suggestionNotice(); if (!sm.isEmpty()) System.out.println("\n" + sm); }
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
                case "db" -> { return db(store, args); }
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
                    String q = String.join(" ", Arrays.asList(args).subList(2, args.length));
                    System.out.println(LibraryPush.answerPackage(q, 6).stripTrailing());
                    Fields.Suggestion s = Fields.tell(store, q, "cli");   // once per question: the field is there if they want it
                    if (s != null) System.out.println("\n" + s.command(q));
                }
                case "map" -> {
                    if (args.length < 3) { System.err.println("usage: researchzosho map <name|id> [--depth N] [--k N]"); return 2; }
                    int depth = 1, k = 25; List<String> focus = new ArrayList<>();
                    for (int i = 2; i < args.length; i++) {
                        if (args[i].equals("--depth")) depth = flagInt(args, i++);
                        else if (args[i].equals("--k")) k = flagInt(args, i++);
                        else focus.add(args[i]);
                    }
                    System.out.print(Graph.render(Graph.build(store).around(String.join(" ", focus), depth, k)));
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
                    String q = String.join(" ", Arrays.asList(args).subList(2, args.length));
                    System.err.print("  … reading what the library holds, asking who studies this, writing the question"); System.err.flush();
                    Sharpen.Sharpened s;
                    try { s = Sharpen.run(store, Explain.drive(), Researcher.webTools(), q); }
                    catch (ProtocolError e) { System.err.println(); System.err.println(e.getMessage()); return 1; }
                    System.err.print("\r" + " ".repeat(90) + "\r");
                    System.out.print(Sharpen.text(s));
                    Path out = Path.of("sharpened-question.txt");
                    Files.writeString(out, s.researchQuestion(), StandardCharsets.UTF_8);
                    System.out.println("\nthe question to run is in " + out + " — edit it if you like, then send it:");
                    System.out.println("  researchzosho research ask \"$(cat " + out + ")\"" + (s.mode().equals("depth") ? " --depth" : "") + (s.size().equals("quick") ? " --quick" : ""));
                    System.out.println("  (or paste it into the Research page, or pass it to library_research)");
                    Fields.Suggestion f = Fields.tell(store, q, "cli");
                    if (f != null) System.out.println("\n" + f.offer() + " To research it that way, add --" + f.field() + " to the command above.");
                }
                case "embed" -> {
                    // the embeddings server: what is configured, and Text Embeddings Inference through Docker by hand
                    String op = args.length > 2 ? args[2] : "status";
                    switch (op) {
                        case "status" -> {
                            String e = Config.get("RESEARCHZOSHO_EMBED");
                            boolean off = e == null || e.isBlank() || e.equalsIgnoreCase("off") || e.equalsIgnoreCase("none");
                            System.out.println("  embeddings server: " + (off ? "none (search is by words only)" : e + " " + (Embed.answers(e) ? "answers" : "does not answer")));
                            System.out.println("  docker container " + Embed.CONTAINER + ": " + Embed.state() + (Searx.haveDocker() ? "; image for this machine: " + Embed.tag() : " (no docker here)"));
                            System.out.println("  researchzosho embed start [port] [--cpu] starts one with Docker (" + Embed.MODEL + "); embed test measures it.");
                            return 0;
                        }
                        case "start" -> {
                            boolean cpu = Arrays.asList(args).contains("--cpu");
                            int port = Embed.DEFAULT_PORT;
                            for (int i = 3; i < args.length; i++) if (args[i].matches("\\d+")) port = Integer.parseInt(args[i]);
                            if (!cpu && Searx.haveDocker() && !Embed.gpu()) {
                                System.out.println("no NVIDIA GPU that Docker can use here. The CPU image runs the same model on one core — measured at a tenth of a chunk a second, so a library of a thousand documents takes more than a day to index. researchzosho embed start --cpu runs it anyway; an embeddings server on another machine (RESEARCHZOSHO_EMBED) is the better answer.");
                                return 1;
                            }
                            System.out.print("starting the embeddings server through docker (the model downloads on the first start, about 1.2 GB)… "); System.out.flush();
                            String r = Embed.start(port, cpu);
                            if (r.startsWith("!")) { System.out.println("no: " + r.substring(1)); return 1; }
                            Config.set("RESEARCHZOSHO_EMBED", r);
                            System.out.println("it answers at " + r + " (saved as RESEARCHZOSHO_EMBED; the container restarts with the machine). Search by meaning is on; researchzosho rebuild indexes the library with it.");
                            return 0;
                        }
                        case "stop" -> { String r = Embed.stop(); System.out.println(r.startsWith("!") ? r.substring(1) : "the embeddings server stopped (researchzosho embed start brings it back)"); return r.startsWith("!") ? 1 : 0; }
                        case "test" -> {
                            var e = Embeddings.configured();
                            if ("none".equals(e.modelId())) { System.out.println("no embeddings server is configured (researchzosho embed start)"); return 1; }
                            var texts = new ArrayList<String>();
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
                            String bk = Config.get("RESEARCHZOSHO_BRAVE_KEY");
                            System.out.println("  Brave Search API: " + (bk == null || bk.isBlank() ? "no key (RESEARCHZOSHO_BRAVE_KEY)" : "a key is set; used first"));
                            String ep = WebSearchTool.endpoint();
                            System.out.println("  SearXNG: " + ep + " " + (Searx.answers(ep) ? "answers" : "does not answer") + "; docker container " + Searx.CONTAINER + ": " + Searx.state() + (Searx.haveDocker() ? "" : " (no docker here)"));
                            System.out.println("  built-in fallback: " + (WebSearchTool.fallbackOn() ? "on (RESEARCHZOSHO_FALLBACK_SEARCH=off turns it off)" : "off"));
                            System.out.println("  order: Brave, then SearXNG, then the fallback. `researchzosho search test \"a query\"` shows which one answers.");
                            return 0;
                        }
                        case "start" -> {
                            boolean fresh = Arrays.asList(args).contains("--fresh");
                            int port = Searx.DEFAULT_PORT;
                            for (int i = 3; i < args.length; i++) if (args[i].matches("\\d+")) port = Integer.parseInt(args[i]);
                            System.out.print("starting SearXNG through docker… "); System.out.flush();
                            String r = Searx.start(port, fresh);
                            if (r.startsWith("!")) { System.out.println("no: " + r.substring(1)); return 1; }
                            Config.set("RESEARCHZOSHO_SEARXNG", r);
                            System.out.println("it answers at " + r + " (saved as RESEARCHZOSHO_SEARXNG; the container restarts with the machine)");
                            if (!Searx.settingsNote.isEmpty()) System.out.println("  " + Searx.settingsNote);
                            return 0;
                        }
                        case "stop" -> { String r = Searx.stop(); System.out.println(r.startsWith("!") ? r.substring(1) : "SearXNG stopped (researchzosho search start brings it back)"); return r.startsWith("!") ? 1 : 0; }
                        case "test" -> {
                            if (args.length < 4) { System.err.println("usage: researchzosho search test <query…>"); return 2; }
                            String q = String.join(" ", Arrays.copyOfRange(args, 3, args.length));
                            var tool = new WebSearchTool();
                            var M = new ObjectMapper();
                            int b0 = WebSearchTool.BRAVE_USED.get(), s0 = WebSearchTool.SEARXNG_USED.get(), f0 = WebSearchTool.FALLBACK_USED.get();
                            long t0 = System.currentTimeMillis();
                            String r = tool.execute(M.createObjectNode().put("query", q).put("limit", 5));
                            String which = WebSearchTool.BRAVE_USED.get() > b0 ? "Brave" : WebSearchTool.SEARXNG_USED.get() > s0 ? "SearXNG" : WebSearchTool.FALLBACK_USED.get() > f0 ? "the built-in fallback" : "no backend";
                            System.out.println("answered by " + which + " in " + (System.currentTimeMillis() - t0) + " ms");
                            System.out.println(r);
                            return 0;
                        }
                        case "papers" -> {
                            if (args.length < 4) { System.err.println("usage: researchzosho search papers <query…>"); return 2; }
                            String q = String.join(" ", Arrays.copyOfRange(args, 3, args.length));
                            long t0 = System.currentTimeMillis();
                            ScholarSearch.ForAPerson r = ScholarSearch.forAPerson(q, 8);
                            if (!r.answered()) { System.err.println(r.text()); return 1; }
                            System.out.println("The search took " + (System.currentTimeMillis() - t0) + " ms.");
                            System.out.println(r.text());
                            return 0;
                        }
                        default -> { System.err.println("usage: researchzosho search [status | start [port] [--fresh] | stop | test <query…> | papers <query…>]"); return 2; }
                    }
                }
                case "bedrock" -> { return BedrockCli.run(args); }
                case "records" -> {
                    // the record sources: what is searched, what needs a key, and a live test of one
                    String op = args.length > 2 ? args[2] : "list";
                    if ("test".equals(op) && args.length > 4) {
                        var src = RecordSources.named(args[3]);
                        if (src == null) { System.err.println("no record source \"" + args[3] + "\". researchzosho records lists them"); return 2; }
                        if (!RecordSources.usable(src)) { System.err.println(src.name() + " needs a key: researchzosho records key " + src.id() + " <key>  (a free key from " + src.keyFrom() + ")"); return 2; }
                        String q = String.join(" ", Arrays.copyOfRange(args, 4, args.length));
                        try { System.out.println(RecordSources.render(src, q, RecordSources.search(src, q, 0, 0, 5, RecordSources.LIVE))); return 0; }
                        catch (Exception e) { System.err.println(src.name() + " did not answer: " + e.getMessage()); return 1; }
                    }
                    if ("login".equals(op) && args.length > 3) {
                        // a source whose key is a login: open the address, approve, paste the address you land on
                        var src = RecordSources.named(args[3]);
                        if (src == null || src.keyLogin().isBlank()) { System.err.println(src == null ? "no record source \"" + args[3] + "\"" : src.name() + " has no login; its key is set with: researchzosho records key " + src.id() + " <key>"); return 2; }
                        for (int i = 4; i + 1 < args.length; i++) if (args[i].equals("--app")) Config.set(src.appName(), args[i + 1].strip());
                        String app = Config.get(src.appName());
                        if (app == null || app.isBlank()) { System.err.println(src.name() + " needs an application key once: " + src.appFrom() + "\n  then: researchzosho records login " + src.id() + " --app <the application's key>"); return 2; }
                        System.out.println("1. Open this address in a browser where you are signed in to " + src.name() + ", and approve:\n\n   " + src.keyLogin().replace("{app}", URLEncoder.encode(app.strip(), StandardCharsets.UTF_8))
                                + "\n\n2. Paste the whole address of the page you land on here, and press Enter:");
                        String pasted = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine();
                        String token = RecordSources.tokenFrom(pasted);
                        if (token.isEmpty()) { System.err.println("that address has no access_token in it; nothing was saved"); return 1; }
                        Config.set(src.keyName(), token);
                        long hours = RecordSources.hoursFrom(pasted);
                        System.out.println("signed in" + (hours > 0 ? "; it lasts " + hours + " hour(s), then sign in again the same way" : "") + ". Try it: researchzosho records test " + src.id() + " <a name>");
                        return 0;
                    }
                    if ("key".equals(op) && args.length > 4) {
                        var src = RecordSources.named(args[3]);
                        if (src == null || src.keyName() == null || src.keyName().isBlank()) { System.err.println(src == null ? "no record source \"" + args[3] + "\"" : src.name() + " takes no key"); return 2; }
                        Config.set(src.keyName(), args[4].strip());
                        // a source that wants a key and a secret: the secret is the second value
                        if (src.exchangesAToken()) {
                            if (args.length < 6) { System.out.println(src.name() + " needs two values, a key and a secret: researchzosho records key " + src.id() + " <key> <secret>. The key is saved; give the command again with both."); return 2; }
                            Config.set(src.secretName(), args[5].strip());
                        }
                        System.out.println("saved. Try it: researchzosho records test " + src.id() + " <a name>");
                        return 0;
                    }
                    if (!"list".equals(op)) { System.err.println("usage: researchzosho records [list | test <source> <query…> | key <source> <key> | login <source>]"); return 2; }
                    long ready = RecordSources.all().stream().filter(RecordSources::usable).count();
                    System.out.println("These are the collections of records that research runs can search by name, beyond a web search: newspapers, scanned books, archives, patents, family trees, code. "
                            + ready + " of " + RecordSources.all().size() + " can be searched now. Each line says what the collection holds, for which countries and years, and which kind of research uses it.\n"
                            + "  [on]   ready: a research run searches it when it fits the question.\n"
                            + "  [key]  needs a free key or a sign-in first; the line under it says where to get it and the command that saves it.\n"
                            + "To try one yourself: researchzosho records test <the name after the mark> <a name or a few words>, for example: researchzosho records test wikipedia-ja 森田勇\n");
                    for (var src : RecordSources.all()) {
                        boolean ok = RecordSources.usable(src);
                        System.out.println((ok ? "[on]  " : "[key] ") + src.id() + " — " + src.name() + (src.countries().isEmpty() ? "" : " [" + String.join(", ", src.countries()) + "]") + (src.years().isEmpty() ? "" : " " + src.years()) + (src.fields().isEmpty() ? "" : "  · " + String.join(", ", src.fields())) + (src.builtIn() ? "" : "  (yours)"));
                        System.out.println("      " + src.holds());
                        if (ok && src.keyOptional() && Config.get(src.keyName()) == null) System.out.println("      works without a key; a free key lets it search more often: researchzosho records key " + src.id() + " <key>  (" + src.keyFrom() + ")");
                        if (!ok) System.out.println(src.keyLogin().isBlank() ? "      needs a key: researchzosho records key " + src.id() + " <key>  (a free key from " + src.keyFrom() + ")" : "      needs a sign-in: researchzosho records login " + src.id());
                    }
                    System.out.println("\nYou can add a collection of your own in the file " + RecordSources.ownFile() + ". The guide shows how, in the section \"Record sources\".");
                    return 0;
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
                            String why = args.length > 4 ? String.join(" ", Arrays.asList(args).subList(4, args.length)) : "";
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
                        Config.set(WebAccess.SIGNIN, args[3]);
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
                    Path out = Vault.dir(store);
                    for (int i = 2; i < args.length; i++) if (args[i].equals("--out")) out = Path.of(flagValue(args, i++));
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
                    var ps = Perspectives.discover(String.join(" ", Arrays.asList(args).subList(2, args.length)), LibraryProtocol.perspectivesDrive(baseUrl, model), Researcher.webTools(), 5);   // watched: a decline is said, not printed as "no perspectives"
                    for (var p : ps) { System.out.println(p.name() + " — " + p.why()); for (String q : p.questions()) System.out.println("  - " + q); }
                    if (ps.isEmpty()) System.out.println("(no perspectives were found)");
                }
                case "graph" -> { return graph(store, args); }
                case "profile" -> { return profile(store, args); }
                case "bib" -> {
                    if (args.length < 3) { System.err.println("usage: researchzosho bib <F-…|I-…> [more ids…]   — BibTeX for everything they cite"); return 2; }
                    System.out.print(Bibliography.bibtex(store, Bibliography.sourcesOf(store, Arrays.asList(args).subList(2, args.length)), Citations.LIVE));
                }
                case "review" -> review(store, args.length > 2 ? args[2] : baseUrl, model);
                case "settle" -> {
                    // a write-up's claims onto the shelves and onto the map now, not at three in the morning: the review pass, then triples
                    List<String> ids = new ArrayList<>(Arrays.asList(args).subList(2, args.length));
                    var client = new DriveClient(baseUrl, model);
                    if (!Crews.driveAnswers(baseUrl)) { System.err.println("No model answers at " + baseUrl + ". settle needs one."); return 1; }
                    var review = new LibrarianReview(store, new LibrarianIndex(store), LibrarianReview.driveJudge(client), "librarian:" + model).searcher(LibrarianReview.liveSearcher());
                    if (ids.isEmpty()) {
                        try (var files = Files.list(store.investigationsDir())) {
                            for (var p : files.sorted().toList()) if (p.toString().endsWith(".md")) {
                                Investigation inv = Investigation.parse(Files.readString(p, StandardCharsets.UTF_8));
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
                    long lacking = Cataloger.waiting(store);   // family claims are not filed under subjects, and a claim answered for this list is not asked again
                    if (lacking > 0) System.out.println("filing " + lacking + " claim(s) under subjects — one model call each, about " + Math.max(1, lacking * 3 / 60) + " minute(s)…");
                    var cat = Cataloger.run(store, Cataloger.driveJudge(client), false);
                    System.out.println("subjects: " + cat.grounded() + " claim(s) filed under known subjects" + (cat.proposals() > 0 ? ", " + cat.proposals() + " new subject(s) proposed for you (catalog/subjects.proposed.md)" : ""));
                    var t = Triples.fill(store, Triples.driveExtractor(client), 60);
                    System.out.println("map: " + t.filled() + " of " + t.asked() + " new claim(s) added to the map");
                    var ret = Retractions.check(store, Retractions.live(), 60, LocalDate.now());
                    System.out.println("retractions: " + ret.checked() + " DOI(s) checked at Crossref, " + ret.retracted() + " retracted, " + ret.concerns() + " concern(s)" + (ret.notes().isEmpty() ? "" : " — " + String.join("; ", ret.notes())));
                    System.out.println("rewriting the summaries of the subjects that changed — a minute or so each…");
                    var abs = Abstracts.run(store, Abstracts.driveWriter(client), null);
                    System.out.println("summaries: " + abs.written() + " subject summar" + (abs.written() == 1 ? "y" : "ies") + " rewritten, " + abs.unchanged() + " unchanged" + (abs.problems().isEmpty() ? "" : "; " + String.join("; ", abs.problems())));
                }
                case "looked" -> { return looked(store, args); }
                case "inbox" -> { return inbox(store, args); }
                case "accept", "retire", "dispute" -> { return council(store, args); }
                case "catalog" -> Cataloger.cli(store, args, baseUrl, model);
                case "shelf" -> { return Serials.shelfCli(store, args); }
                case "serials" -> Serials.check(store);
                case "tonight" -> { System.out.print(Tonight.text(Tonight.plan(store))); return 0; }
                case "update" -> {
                    // --json: for a program that updates ResearchZosho (CodeZaiku, Wyrdsekai), which reads the answer instead of the words
                    boolean json = Arrays.asList(args).contains("--json");
                    String op = args.length > 2 && !args[2].startsWith("--") ? args[2] : "status";
                    switch (op) {
                        case "status" -> { System.out.print(json ? Updater.statusJson() + "\n" : Updater.status()); return 0; }
                        case "now" -> {
                            boolean restart = !Arrays.asList(args).contains("--no-restart");
                            String ver = null; for (int i = 3; i < args.length; i++) if (args[i].matches("\\d+\\.\\d+\\.\\d+")) ver = args[i];
                            // with --json the progress lines go to the error stream, so the output is one JSON document
                            Updater.Outcome o = Updater.now(ver, restart, json ? System.err : System.out);
                            System.out.println(json ? Updater.outcomeJson(o) : o.note());
                            return o.result().code;
                        }
                        case "auto" -> {
                            if (args.length < 4 || !(args[3].equals("on") || args[3].equals("off"))) { System.err.println("usage: researchzosho update auto on|off"); return 2; }
                            Config.set("RESEARCHZOSHO_UPDATE", args[3].equals("on") ? "auto" : "check");
                            System.out.println(args[3].equals("on") ? "auto-update is on: after each nightly maintenance, when no research run is active, a newer release is installed and the service restarts"
                                                                    : "auto-update is off: researchzosho status says when a newer release exists. researchzosho update now installs it");
                            return 0;
                        }
                        default -> { System.err.println("usage: researchzosho update [status | now [version] [--no-restart] | auto on|off] [--json]"); return 2; }
                    }
                }
                case "questions" -> {
                    // the queue the explorer draws from: see it (by type, parked too), add, drop, reorder, park, and set the nightly budget
                    String op = args.length > 2 ? args[2] : "list";
                    var open = new ArrayList<Frontier.Line>();
                    for (Frontier.Line l : Frontier.read(store)) if (l.open()) open.add(l);
                    Function<String, String> pick = ref -> ref.matches("\\d+") && Integer.parseInt(ref) >= 1 && Integer.parseInt(ref) <= open.size() ? open.get(Integer.parseInt(ref) - 1).text() : ref;
                    switch (op) {
                        case "file" -> {
                            // a whole file of questions onto the queue in order (or the first few out as runs)
                            if (args.length < 4) { System.err.println("usage: researchzosho questions file <file|url> [--as frontier|runs] [--title <t>] [--limit <n>] [--field <name>]"); return 2; }
                            String[] sub = new String[args.length - 1];
                            sub[0] = args[0]; sub[1] = "questions"; System.arraycopy(args, 3, sub, 2, args.length - 3);
                            return launch(store, sub);
                        }
                        case "list" -> {
                            // the filters a person sorts a long list by: the same ones the page and library_frontier take
                            var f = new LinkedHashMap<String, String>();
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
                            var rows = new ArrayList<ObjectNode>();
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
            if (o.path("checked_against").asText("").startsWith("a machine reading")) tail.append(" · read from a picture whose reading nobody has checked");
                                for (var sj : o.path("subjects")) tail.append(" · ").append(sj.asText());
                                if (o.hasNonNull("similar") && !o.path("similar").asText().equals(o.path("text").asText())) { for (var x : listed) if (x.path("text").asText().equals(o.path("similar").asText())) tail.append(" · reads like #").append(x.path("position").asInt()); }
                                if (o.hasNonNull("answered")) tail.append(" · maybe answered already: ").append(o.path("answered").path("id").asText());
                                System.out.println("  " + o.path("position").asInt() + ". " + mark + "[" + o.path("type").asText() + (o.path("type").asText().equals("asked") ? " ×" + o.path("asked").asInt() : "") + "] "
                                        + (byReport ? Frontier.strip(o.path("text").asText()) : o.path("text").asText()) + (tail.length() > 0 ? "  (" + tail.substring(3) + ")" : "")
                                        + (o.hasNonNull("parked_why") ? "\n      waits: " + o.path("parked_why").asText() : ""));
                            }
                            long parked = open.stream().filter(Frontier.Line::parked).count();
                            int dups = Frontier.duplicates(store).size();
                            System.out.println("  " + rows.size() + " shown of " + open.size() + (parked > 0 && f.get("show").equals("queued") ? ", " + parked + " parked (--parked shows them, --all everything)" : "") + ". Types: report, asked, person, dispute, check (--type <t>). The nightly research takes " + budget + " research run(s) a night from " + String.join(", ", new TreeSet<>(Frontier.explorerTypes())) + ". \"waits\" = asked fewer than " + Frontier.MIN_ASKS + " times, or a type it leaves to you. A report's leftover questions are parked."
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
                            String q = String.join(" ", Arrays.copyOfRange(args, 3, args.length)).strip();
                            store.frontier("person", q);
                            System.out.println("queued. researchzosho tonight shows when the nightly research takes it");
                            return 0;
                        }
                        case "drop", "next", "later", "park", "unpark" -> {
                            if (args.length < 4) { System.err.println("usage: researchzosho questions " + op + " <number from the list | the question>" + (op.equals("park") ? " [--why <the reason, for example: waits on the record office's answer>]" : "")); return 2; }
                            List<String> words = new ArrayList<>(Arrays.asList(args).subList(3, args.length));
                            String why = "";
                            int w = words.indexOf("--why");
                            if (op.equals("park") && w >= 0) { why = String.join(" ", words.subList(w + 1, words.size())); words = new ArrayList<>(words.subList(0, w)); }
                            String text = pick.apply(String.join(" ", words).strip());
                            boolean ok = switch (op) {
                                case "drop" -> Frontier.drop(store, text, "person");
                                case "next" -> Frontier.next(store, text);
                                case "later" -> Frontier.later(store, text);
                                case "park" -> Frontier.park(store, text, why);
                                default -> Frontier.unpark(store, text);
                            };
                            if (!ok && op.equals("park") && Frontier.isParked(store, text)) {
                                System.out.println("This question is already parked: " + text + (why.isBlank() ? "" : "\n  The reason kept with it is now: " + why));
                                return 0;
                            }
                            if (!ok) { System.err.println("No open question matches: " + text + (op.equals("unpark") ? " (or it is not parked)" : "")); return 1; }
                            System.out.println(switch (op) { case "drop" -> "dropped: "; case "next" -> "runs next: "; case "later" -> "moved to the end: "; case "park" -> "parked: "; default -> "back in the queue: "; } + text + (why.isBlank() ? "" : "\n  The reason is kept with it: " + why));
                            return 0;
                        }
                        case "budget", "tonight" -> {
                            if (args.length < 4 || !args[3].matches("\\d+")) { System.err.println("usage: researchzosho questions " + op + " <runs per night>"); return 2; }
                            if (op.equals("budget")) { Config.set("RESEARCHZOSHO_EXPLORER_PER_NIGHT", args[3]); System.out.println("the nightly research takes " + args[3] + " research run(s) a night from now on" + (args[3].equals("0") ? " (off)" : "")); }
                            else { Config.set("explorer.tonight", args[3]); System.out.println("tonight the nightly research takes " + args[3] + " research run(s). The usual number stays " + Crews.explorerPerNight()); }
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
                        var co = edges.getOrDefault(e.getKey(), Map.of());
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
                    boolean yes = false;
                    try {
                        for (int i = 3; i < args.length; i++) {
                            switch (args[i]) {
                                case "--yes", "-y" -> yes = true;
                                case "--exec" -> exec = flagValue(args, i++);
                                case "--host" -> host = flagValue(args, i++);   // 0.0.0.0: the LAN may reach it; the reader list decides who may do what
                                case "--port" -> port = flagInt(args, i++);
                                case "--crew-hour" -> { hour = flagInt(args, i++); if (hour > 23) { System.err.println("--crew-hour takes 0 to 23 (the hour nightly maintenance runs), or a negative number to turn it off"); return 2; } }
                                default -> { System.err.println("unknown option " + args[i]); return 2; }
                            }
                        }
                    } catch (IllegalArgumentException e) {
                        System.err.println("usage: researchzosho service install|uninstall|restart [--yes]|status [--exec <launcher>] [--host H] [--port N] [--crew-hour H] — " + e.getMessage()); return 2;
                    }
                    if (op.equals("restart") && !yes) {
                        // a restart starts a run that is going over from the beginning (a second restart fails it): say so first
                        String going = runningResearch();
                        if (going != null) {
                            System.out.println("A research run is going: " + going + ". A restart stops it, and it starts again from the beginning.");
                            if (!Interaction.yes("Restart the service anyway? (y/N)")) { System.out.println("Nothing was restarted."); return 1; }
                        }
                    }
                    var plan = Service.plan(Service.os(), op.equals("install") ? Service.resolveExec(exec) : (exec == null ? "researchzosho" : exec),
                            host, port, hour, Path.of(System.getProperty("user.home")));
                    int rc = Service.run(op, plan, System.out);
                    if (rc == 0 && op.equals("install") && !Service.hostFlag(host, "x", "").isEmpty()) {
                        System.out.println("  the pages answer on this computer's network address too. " + (WebAccess.signInRequired() ? "A browser needs a reader token to send questions." : WebAccess.OPEN_NOTICE + " " + WebAccess.OPEN_HOWTO));
                    }
                    return rc;
                }
                case "mcp" -> { McpServer.serveStdio(n -> n.startsWith("library_")); }
                case "probe" -> {
                    if (args.length < 3) { System.err.println("usage: researchzosho probe <query…>"); return 2; }
                    System.out.print(new LibrarianIndex(store).explain(String.join(" ", Arrays.copyOfRange(args, 2, args.length)), 8));
                }
                case "crews" -> {
                    boolean weekly = Arrays.asList(args).contains("--weekly"), monthly = Arrays.asList(args).contains("--monthly");
                    var cadence = weekly || monthly ? new Crews.Cadence(weekly, monthly) : Crews.Cadence.tonight(LocalDate.now());
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
        } catch (Declined d) {
            System.err.println(d.statement());   // the model declined: said plainly, not as the program failing
            return 1;
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
        for (String told : UncheckedPages.tell(store)) System.out.println("  " + told);   // pages saved before their check, and pages a later check removed
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
                String token = ""; List<String> words = new ArrayList<>();
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
                List<String> groups = new ArrayList<>();
                for (int i = 6; i < args.length; i++) if (args[i].equals("--group")) groups = List.of(flagValue(args, i++).split(","));
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
                String q = String.join(" ", Arrays.asList(args).subList(4, args.length));
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
            System.out.println("  settings live in " + Config.userConfigPath() + " (research.workers, research.pause, research.window). A running question follows at its next turn");
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
                Config.set(ResearchSettings.WORKERS, args[3]);
                System.out.println("research.workers = " + args[3] + ". A running question follows at its next turn");
            }
            case "ask" -> {
                // file a question from the command line, as the person; the service picks it up within seconds
                if (args.length < 4) { System.err.println("usage: researchzosho research ask \"<question…>\" [--depth] [--quick] [--shelves|--web] [--max-turns N] [--max-minutes N] [--field <name>] [--allow explicit,howto]\n  --field <name>, or --<name>, researches the question in that field's mode\n  --allow explicit lets into this run the pornography and gore the library leaves out by default; --allow howto lets in step-by-step instructions for making weapons, explosives and illegal drugs and for running exploit code\n  --allow self-harm sends a question that reads as a person asking about harming themselves, which is otherwise sent only after a yes at a terminal"); return 2; }
                List<String> words = new ArrayList<>();
                List<String> allow = new ArrayList<>();
                String mode = "broad", sources = "both", field = "", how = "cli-flag", allowHow = "cli-flag"; boolean quick = false; int maxTurns = -1, maxMinutes = -1;
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
                            case "--field" -> { if (i + 1 >= args.length) throw new IllegalArgumentException("--field needs the name of a field, for example --field science"); field = args[++i]; }
                            case "--allow" -> {
                                if (i + 1 >= args.length) throw new IllegalArgumentException("--allow needs what to let in: explicit, howto, or both as explicit,howto");
                                for (String a : args[++i].split(",")) if (!a.isBlank()) allow.add(a.strip().toLowerCase(Locale.ROOT));
                            }
                            default -> {
                                // --<a field's name> asks for that field, as --field <name> does
                                if (args[i].startsWith("--") && Profiles.named(args[i].substring(2)) != null) field = args[i].substring(2);
                                else words.add(args[i]);
                            }
                        }
                    }
                } catch (IllegalArgumentException e) { System.err.println(e.getMessage()); return 2; }
                String q = String.join(" ", words).strip();
                ObjectNode ask = new ObjectMapper().createObjectNode();
                ask.put("question", q); ask.put("mode", mode); ask.put("sources", sources);
                if (quick) ask.put("quick", true);
                if (maxTurns >= 0) ask.put("max_turns", maxTurns);
                if (maxMinutes >= 0) ask.put("max_minutes", maxMinutes);
                ask.putObject("patron").put("did", "person").put("name", System.getProperty("user.name", "person")).put("runtime", "cli");
                // a question that looks like a field's that joins only when asked: a person at the keyboard is asked once, no being the answer
                // Enter gives; a script is told the command that asks for it, and the question goes as ordinary research
                String told = null;
                if (field.isEmpty() && q.length() >= 12) {
                    Fields.Suggestion s = Fields.suggest(store, q);
                    // at a terminal, the person said yes to this question before: it goes as they said, and they are not asked again
                    if (s != null && Interaction.interactive() && "yes".equals(Fields.answered(store, q, s.field()))) { field = s.field(); how = "cli-yes"; }
                    else if (s != null && Interaction.interactive() && !Fields.seen(store, q, s.field())) {
                        boolean yes = Interaction.yes(s.question());
                        Fields.note(store, q, s.field(), "asked", yes ? "yes" : "no", "cli");
                        if (yes) { field = s.field(); how = "cli-yes"; }
                    } else if (s != null && !Fields.seen(store, q, s.field())) {
                        Fields.note(store, q, s.field(), "told", "", "cli");
                        told = s.command(q);
                    }
                }
                // what answering it may need that the library leaves out by default: asked every time at a terminal, never remembered; a script is
                // told the flag that lets it in, and the run goes with it left out
                String toldContent = null;
                if (!q.isBlank()) {   // every question, however short: a short one is still asked whether it is a person asking about harming themselves
                    ContentOffer.Detected detected = ContentOffer.detect(q, null);
                    // a person asking about harming themselves: where to find help first; then, at a terminal, whether to research it at all
                    if (detected.showHelp() && !allow.contains(ContentPolicy.SELF_HARM)) {
                        System.out.println(CrisisHelp.text());
                        System.out.println();
                        if (detected.harm() == ContentOffer.Harm.SURE) {
                            if (!Interaction.interactive()) {
                                System.out.println("Nothing was sent. To research this question, send it again with --allow self-harm: researchzosho research ask " + Fields.quoted(q) + " --allow self-harm");
                                return 1;
                            }
                            if (!Interaction.yes(CrisisHelp.QUESTION)) { System.out.println("Nothing was sent. The question is not researched."); return 0; }
                            allow.add(ContentPolicy.SELF_HARM);
                            allowHow = "cli-yes";
                        }
                    }
                    List<String> needs = new ArrayList<>(detected.needs());
                    needs.removeAll(allow);
                    if (!needs.isEmpty() && Interaction.interactive()) {
                        if (Interaction.yes(ContentOffer.question(needs, 1))) { allow.addAll(needs); allowHow = "cli-yes"; }
                    } else if (!needs.isEmpty()) toldContent = ContentOffer.command(q, needs);
                }
                if (!field.isEmpty()) ask.put("field", field);
                if (!allow.isEmpty()) { var al = ask.putArray("allow"); allow.forEach(al::add); ask.put("allow_how", allowHow); }
                try {
                    var r = new LibraryProtocol(store).research(ask, new LibraryProtocol.Way(how, "cli", false));
                    System.out.println("sent as " + r.path("job_id").asText() + (r.hasNonNull("field") ? ", in " + r.path("field").asText() + " mode" : "") + (quick ? " (quick: front of the line, short limits)" : "") + ". The service picks it up within seconds. researchzosho jobs " + r.path("job_id").asText() + " shows how it goes");
                    String letIn = ContentOffer.described(LibraryProtocol.allowOf(r));
                    if (!letIn.isEmpty()) System.out.println("This run lets in " + letIn + ", for this question only, because you asked for it.");
                    if (told != null) System.out.println(told);
                    if (toldContent != null) System.out.println(toldContent);
                } catch (ProtocolError e) { System.err.println(e.getMessage()); return 1; }
            }
            case "stop" -> {
                if (args.length < 4) { System.err.println("usage: researchzosho research stop <J-…>"); return 2; }
                var a = new ObjectMapper().createObjectNode(); a.put("op", "stop"); a.put("job_id", args[3]);
                a.putObject("patron").put("did", "person").put("name", System.getProperty("user.name", "person")).put("runtime", "cli");
                try {
                    var r = new LibraryProtocol(store).job(a);
                    String id = args[3];
                    System.out.println(r.path("state").asText().equals("stopped") ? id + " was waiting to start. It is stopped and will not start."
                            : r.path("kind").asText().equals("crews") ? "Stopping " + id + ", the nightly tasks. They end after the task they are on now, which can take a few minutes. The tasks already done stay done. The command researchzosho jobs shows it as stopped."
                            : r.path("filed").asBoolean(false) ? "Stopping " + id + ". It had finished its research and begun filing its report before you stopped it, so the report stays in your library. "
                              + "What stops is the checking of the report's claims that comes after the filing: it ends with the step it is on now, and the nightly housekeeping checks the rest. The command researchzosho jobs " + id + " shows the report."
                            : "Stopping " + id + ". It ends within a few seconds, also when it is waiting for the model, a web page or a search. Nothing from it is filed. The command researchzosho jobs shows it as stopped.");
                }
                catch (ProtocolError e) { System.err.println(e.getMessage()); return 1; }
            }
            case "pause" -> { Config.set(ResearchSettings.PAUSE, "on"); System.out.println("research paused. Queued questions wait, and a running question stops at its next turn (resume: researchzosho research resume)"); }
            case "resume" -> { Config.set(ResearchSettings.PAUSE, "off"); System.out.println("research resumed"); }
            case "window" -> {
                if (args.length < 4) { System.err.println("usage: researchzosho research window <HH:MM-HH:MM|off>"); return 2; }
                if (!args[3].equalsIgnoreCase("off") && !ResearchSettings.validWindow(args[3])) { System.err.println("a window is HH:MM-HH:MM (it may cross midnight), or off"); return 2; }
                Config.set(ResearchSettings.WINDOW, args[3].equalsIgnoreCase("off") ? "off" : args[3]);
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
    static final int CHAT_WATCH_SECONDS = Config.getInt("RESEARCHZOSHO_CHAT_WATCH_SECONDS", 15);
    /** The terminal chat prints a followed run's line on every stage change, and this often in between. */
    static final int CHAT_STATUS_MINUTES = Math.max(1, Config.getInt("RESEARCHZOSHO_CHAT_STATUS_MINUTES", 5));

    static int chat(LibraryStore store, String[] args, String baseUrl, String model) throws Exception {
        Librarian.Session session = null;
        for (int i = 2; i < args.length; i++) {
            if (args[i].equals("--new")) session = Librarian.Session.open(store);
            else if (args[i].equals("--resume") && i + 1 < args.length) { session = Librarian.Session.resume(store, args[++i]); if (session == null) { System.err.println("no session " + args[i] + " (researchzosho chat --sessions lists them)"); return 2; } }
            else if (args[i].equals("--sessions")) { for (String id : Librarian.Session.list(store)) { var sx = Librarian.Session.resume(store, id); System.out.println("  " + id + "  " + (sx == null ? "" : sx.title())); } return 0; }
        }
        if (session == null) session = Librarian.Session.latest(store);
        Researcher.Drive drive = Researcher.watchedChat(baseUrl, model);   // watched: when the model declines, the chat says so
        if (!Crews.driveAnswers(baseUrl)) { System.err.println("no model answers at " + baseUrl + " — the Librarian needs one to talk (researchzosho setup, or researchzosho model install)"); return 1; }
        // a quiet screen: the drive's request lines belong in a log, not between the person and the Librarian
        try { ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger("org.researchzosho")).setLevel(ch.qos.logback.classic.Level.WARN); } catch (Throwable ignored) { }
        Librarian lib = new Librarian(store, drive, Librarian.person(), session);
        ChatIo io = ChatIo.open(store);
        String name = store.identity().name();
        System.out.println("The Librarian of " + name + ". Session " + session.id + (session.messages().isEmpty() ? "" : ", continued") + ". /help for the commands, /quit to leave.");
        if (!session.messages().isEmpty()) { var ms = session.messages(); for (int i = Math.max(0, ms.size() - 2); i < ms.size(); i++) { var m = ms.get(i); if (m.path("role").asText().equals("user")) System.out.println("\n> " + m.path("content").asText()); else if (m.path("role").asText().equals("assistant") && !m.path("content").asText("").isBlank()) System.out.println("\n" + m.path("content").asText()); } }
        // the chat follows the runs it starts: a line when a run's stage changes, one notice when it is done
        final Librarian.Session[] current = {session};
        Thread watcher = new Thread(() -> {
            Map<String, String> lastStage = new HashMap<>(), lastMinute = new HashMap<>();
            LibraryProtocol lp = new LibraryProtocol(store);
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(CHAT_WATCH_SECONDS * 1000L);
                    for (String jobId : current[0].watched()) {
                        var a = new ObjectMapper().createObjectNode().put("job_id", jobId);
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
        Set<String> announced = new HashSet<>(session.watched());
        while (true) {
            System.out.println();
            String line = io.readLine("> ");
            if (line == null) break;
            line = line.strip();
            if (line.isEmpty() && !lib.waitingForAnswer()) continue;   // while the library waits for a (y/N) answer, Enter is no
            if (Librarian.QUIT.contains(line)) break;
            if (line.equals("/help")) { System.out.println("  say anything · " + fieldCommandsHelp(store) + "/new (a fresh conversation) · /runs (the research runs this chat follows) · /sessions · /resume <id> · /quit\n  up and down arrows go through what you typed before, Ctrl-R searches it, Ctrl-C clears the line, Ctrl-D leaves\n  \"find out …\" starts a research run. \"yes\" accepts what the Librarian offers\n  when the Librarian asks a question ending in (y/N), y or yes says yes; Enter or anything else says no"); continue; }
            if (line.equals("/new")) { lib.leaveUnanswered(); session = Librarian.Session.open(store); current[0] = session; lib = new Librarian(store, drive, Librarian.person(), session); System.out.println("  a fresh conversation, " + session.id); continue; }
            if (line.equals("/runs")) { LibraryProtocol lp2 = new LibraryProtocol(store); List<String> w = session.watched(); if (w.isEmpty()) System.out.println("  no research run is being followed in this conversation"); for (String jobId : w) { var a2 = new ObjectMapper().createObjectNode().put("job_id", jobId); a2.putObject("patron").put("did", "person").put("name", System.getProperty("user.name", "person")).put("runtime", "cli"); try { System.out.println("  " + RunProgress.line(RunProgress.of(lp2.job(a2).path("job"), System.currentTimeMillis(), RunProgress.typicalSeconds(lp2, a2.path("patron"))))); } catch (Exception e) { System.out.println("  " + jobId + ": " + e.getMessage()); } } continue; }
            if (line.equals("/sessions")) { for (String id : Librarian.Session.list(store)) { var sx = Librarian.Session.resume(store, id); System.out.println("  " + id + "  " + (sx == null ? "" : sx.title())); } continue; }
            if (line.startsWith("/resume ")) { var sx = Librarian.Session.resume(store, line.substring(8).strip()); if (sx == null) { System.out.println("  no such session"); continue; } session = sx; current[0] = session; lib = new Librarian(store, drive, Librarian.person(), session); System.out.println("  continuing " + session.id + ": " + session.title()); continue; }
            if (fieldCommand(store, line)) continue;
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
        if (lib.waitingForAnswer()) { String left = lib.leaveUnanswered(); if (!left.isEmpty()) System.out.println(left); }
        System.out.println("Session " + session.id + " is kept; researchzosho chat continues it.");
        return 0;
    }

    /** `researchzosho jobs [<J-…>]`: the runs, or one run — the line the CLI names after `research ask` (it was named and missing through 0.1.8). */
    static int jobs(LibraryStore store, String[] args) throws Exception {
        var a = new ObjectMapper().createObjectNode();
        a.putObject("patron").put("did", "person").put("name", System.getProperty("user.name", "person")).put("runtime", "cli");
        String id = args.length > 2 ? args[2].strip() : "";
        if (!id.isEmpty()) a.put("job_id", id); else a.put("limit", 10);
        JsonNode r;
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
            if (j.hasNonNull("no_progress_since")) System.out.println("  The run has shown no progress since " + localTime(j.get("no_progress_since").asText()) + ". It may be waiting for something that does not come, such as a model or a site that does not answer. "
                    + "The service has written where the run is waiting into its log, for whoever looks into it. To stop the run: researchzosho research stop " + j.path("job_id").asText());
            if (j.hasNonNull("investigation")) System.out.println("  report: " + j.get("investigation").asText() + "  (researchzosho export " + j.get("investigation").asText() + " --md, or the Runs page)");
            boolean declinedRun = j.path("declined").path("run").asBoolean(false);
            if (j.path("declined").isObject()) System.out.println("  declined: " + j.path("declined").path("statement").asText());
            if (!declinedRun && j.hasNonNull("result") && !j.get("result").asText().isBlank()) System.out.println("  " + (j.path("is_error").asBoolean(false) ? "error: " : "result: ") + Acquisitions.compress(j.get("result").asText(), 300));
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

    static String jobRow(JsonNode j) {
        StringBuilder b = new StringBuilder(j.path("job_id").asText()).append(" [").append(j.path("state").asText()).append("] ").append(j.path("kind").asText());
        b.append(" · ").append(elapsed(j.path("elapsed_s").asLong()));
        if (j.has("progress") && j.get("progress").isObject()) b.append(" · ").append(progressWords(j.get("progress")));
        if (j.hasNonNull("waiting")) b.append(" · waiting for the model");
        if (j.hasNonNull("no_progress_since")) b.append(" · no progress since ").append(localTime(j.get("no_progress_since").asText()));
        if (j.path("declined").path("run").asBoolean(false)) b.append(" · declined by the model");
        else if (j.path("declined").isObject()) b.append(" · parts declined by the model");
        if (j.hasNonNull("question")) b.append(" · ").append(Acquisitions.compress(j.get("question").asText(), 70));
        return b.toString();
    }

    static String progressWords(JsonNode p) {
        List<String> parts = new ArrayList<>();
        if (!p.path("phase").asText("").isEmpty()) parts.add(p.path("phase").asText());
        if (p.path("round").asInt() > 0) parts.add("round " + p.path("round").asInt() + " of up to " + p.path("rounds").asInt());
        if (p.path("workers_total").asInt() > 0) parts.add("workers " + p.path("workers_done").asInt() + "/" + p.path("workers_total").asInt());
        if (p.path("turns_ceiling").asInt() > 0) parts.add("turns " + p.path("turns_used").asInt() + " of " + p.path("turns_ceiling").asInt());
        else parts.add("turns " + p.path("turns_used").asInt() + ", no turn ceiling");
        if (p.hasNonNull("deadline_at")) { try { long min = (Instant.parse(p.get("deadline_at").asText()).toEpochMilli() - System.currentTimeMillis()) / 60_000; parts.add(min >= 0 ? min + " min left" : "past its deadline, wrapping up"); } catch (Exception ignored) { } }
        var it = p.fields();
        Set<String> known = Set.of("phase", "round", "rounds", "workers_done", "workers_total", "turns_used", "turns_ceiling", "deadline_at", "at");
        while (it.hasNext()) { var e = it.next(); if (!known.contains(e.getKey())) parts.add(e.getKey().replace('_', ' ') + " " + (e.getValue().isTextual() ? e.getValue().asText() : e.getValue().toString())); }
        return String.join(", ", parts);
    }

    /** An instant as a person reads it: the date and the time of day where the computer is, "2026-09-24 05:51". */
    static String localTime(String instant) {
        try { return LocalDateTime.ofInstant(Instant.parse(instant), ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")); }
        catch (Exception e) { return instant; }
    }

    static String elapsed(long s) { return s < 60 ? s + " s" : s < 3600 ? (s / 60) + " min" : String.format("%dh%02d", s / 3600, (s % 3600) / 60); }

    /** `researchzosho subjects proposed|accept|drop`: the cataloger's proposed subjects, and what the person does with them. */
    static int subjectsProposed(LibraryStore store, String[] args) throws Exception {
        Path f = store.subjectsFile().resolveSibling("subjects.proposed.md");
        List<String[]> proposed = new ArrayList<>();   // {slug, description}
        List<String> head = new ArrayList<>();
        if (Files.exists(f)) for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
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
        Set<String> chosen = new LinkedHashSet<>();
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
        Files.writeString(f, sb.toString(), StandardCharsets.UTF_8);
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
        ObjectNode ask = r.offer().deepCopy();
        if (!now) ask.remove("quick");
        ask.putObject("patron").put("did", "person").put("name", System.getProperty("user.name", "person")).put("runtime", "cli");
        ObjectNode filed = new LibraryProtocol(store).research(ask, new LibraryProtocol.Way("cli-flag", "cli", true));
        String jobId = filed.path("job_id").asText();
        if (filed.has("suggestion")) System.out.println(filed.path("suggestion").path("why").asText() + " To research it that way: researchzosho research ask " + Fields.quoted(ask.path("question").asText()) + " --" + filed.path("suggestion").path("field").asText());
        if (!now) { System.out.println("sent as " + jobId + " (full research: no limits, it waits its turn) — then: researchzosho explain \"" + what + "\"" + (in == null ? "" : " --in " + in)); return 0; }
        System.out.println("looking it up now as " + jobId + " (the service picks it up within seconds. Limits: " + ask.path("max_turns").asInt() + " turns, " + ask.path("max_minutes").asInt() + " minutes)");
        Jobs jobs = new Jobs(store, j -> { throw new IllegalStateException("read only"); });
        long deadline = System.currentTimeMillis() + (ask.path("max_minutes").asInt(6) + 3) * 60_000L;
        String state = "queued"; long queuedSince = System.currentTimeMillis();
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(3_000);
            ObjectNode j = jobs.get(jobId);
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
        var a = new ObjectMapper().createObjectNode();
        a.putObject("patron").put("did", "person").put("name", System.getProperty("user.name", "person")).put("runtime", "cli");
        String op = args.length > 2 ? args[2] : "list";
        int i = 3;
        switch (op) {
            case "list", "measure" -> a.put("op", op);
            case "accept", "dismiss" -> { if (args.length < 4) { System.err.println("usage: researchzosho bridges " + op + " <question…>"); return 2; } a.put("op", op); a.put("question", String.join(" ", Arrays.asList(args).subList(3, args.length))); i = args.length; }
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
        ObjectNode r;
        try { r = new LibraryProtocol(store).bridges(a); } catch (ProtocolError e) { System.err.println(e.getMessage()); return 1; }
        switch (a.path("op").asText()) {
            case "run" -> {
                System.out.println(r.path("summary").asText());
                for (var p : r.path("proposals")) {
                    System.out.println("  " + p.path("a_label").asText() + "  ↔  " + p.path("c_label").asText() + "   [" + p.path("sensor").asText() + "] via " + String.join(", ", StreamSupport.stream(p.path("via").spliterator(), false).map(JsonNode::asText).toList()) + "   (score " + p.path("score").asText() + ", " + (p.path("hops").asInt() < 0 ? "not connected on the map" : p.path("hops").asInt() + " step(s) apart on the map") + (p.path("random").asBoolean() ? ", random pick" : "") + ")");
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
                if (r.path("shared_terms").size() > 0) System.out.println("  shared terms: " + String.join(", ", StreamSupport.stream(r.path("shared_terms").spliterator(), false).map(JsonNode::asText).toList()));
                for (var path : r.path("paths")) System.out.println("  path: " + path.asText());
                System.out.println("  concepts: " + r.path("concepts_a").asInt() + " on one side, " + r.path("concepts_c").asInt() + " on the other; embedder: " + r.path("embedder").asText() + "; fold at " + r.path("fold_at").asText());
                for (var n : r.path("nearest_concepts")) System.out.println("    " + n.path("cosine").asText() + "  " + n.path("a").asText() + "  ~  " + n.path("c").asText() + (n.path("folded").asBoolean() ? "  (one node)" : ""));
            }
            default -> System.out.println("proposed " + r.path("proposed").asInt() + ", kept " + r.path("kept").asInt() + ", dismissed " + r.path("dismissed").asInt() + ", confirmed by a second source " + r.path("corroborated").asInt());
        }
        return 0;
    }

    /** A field's own command in the terminal chat ("/family …" is genealogy's): true when a field took the line. */
    static boolean fieldCommand(LibraryStore store, String line) {
        for (Profile p : Profiles.known()) if (p.chatCommand(store, line)) return true;
        return false;
    }

    /** The chat's help for the fields' own commands, each followed by " · "; "" when no enabled field has one. */
    static String fieldCommandsHelp(LibraryStore store) {
        StringBuilder b = new StringBuilder();
        for (Profile p : Fields.enabled(store)) if (!p.chatCommandHelp().isBlank()) b.append(p.chatCommandHelp().strip()).append(" · ");
        return b.toString();
    }

    /** {@code /family …} in the chat, the words after it given: kept for the tests and scripts that call it by this name. */
    static void familyCommand(LibraryStore store, String rest) { fieldCommand(store, ("/family " + rest).strip()); }

    /** check | reading | questions | bookmarks | meeting <file|url> [flags]: the launching points that share one shape. */
    static int launch(LibraryStore store, String[] args) throws Exception {
        String verb = args[1];
        if (args.length < 3) { System.err.println("usage: researchzosho " + verb + " <file|url> [--title <t>] [--collection <name>] [--verify] [--watch] [--as frontier|runs] [--folder <f>] [--limit <n>] [--no-citations] [--field <name>]\n  --field <name>, or --<name>, researches the runs it starts in that field's mode"); return 2; }
        var a = new ObjectMapper().createObjectNode();
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
                    case "--field" -> a.put("field", flagValue(args, i++));
                    default -> {
                        if (!fieldFlag(a, args[i])) { System.err.println("unknown flag " + args[i]); return 2; }
                    }
                }
            }
        } catch (IllegalArgumentException e) { System.err.println(e.getMessage()); return 2; }
        a.putObject("patron").put("did", "person").put("name", System.getProperty("user.name", "person")).put("runtime", "cli");
        LibraryProtocol p = new LibraryProtocol(store);
        ObjectNode r;
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
        printSuggestion(r);
        System.out.println("  " + r.path("next").asText());
        return 0;
    }

    /** {@code --<a field's name>}, as the research command takes it: the runs a batch starts are that field's. False for any other flag. */
    static boolean fieldFlag(ObjectNode a, String flag) {
        if (!flag.startsWith("--") || Profiles.named(flag.substring(2)) == null) return false;
        a.put("field", flag.substring(2));
        return true;
    }

    /** A batch one of whose runs looks like a field's: what that field's mode does, and the command that researches the question that way. */
    static void printSuggestion(ObjectNode r) {
        JsonNode s = r.path("suggestion");
        if (!s.isObject()) return;
        System.out.println("  " + new Fields.Suggestion(s.path("field").asText(), s.path("why").asText()).command(s.path("question").asText()));
    }

    /** researchzosho items <file|url> [--lens "…"] [--as frontier|runs|none] [--column C] [--title T] [--limit N]: a list of things as a starting point. */
    static int items(LibraryStore store, String[] args) throws Exception {
        if (args.length < 3) { System.err.println("usage: researchzosho items <list.txt|list.md|list.csv|url|calibre-library|metadata.db> [--lens \"{item}: …\"] [--as frontier|runs|none] [--column <name>] [--title <t>] [--limit <n>] [--match <text>]... [--sample <n>] [--field <name>]\n  --field <name>, or --<name>, researches the runs it starts in that field's mode"); return 2; }
        var a = new ObjectMapper().createObjectNode();
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
                    case "--match" -> { if (!a.has("match")) a.putArray("match"); ((ArrayNode) a.get("match")).add(flagValue(args, i++)); }
                    case "--sample" -> a.put("sample", flagInt(args, i++));
                    case "--field" -> a.put("field", flagValue(args, i++));
                    default -> {
                        if (!fieldFlag(a, args[i])) { System.err.println("unknown flag " + args[i]); return 2; }
                    }
                }
            }
        } catch (IllegalArgumentException e) { System.err.println(e.getMessage()); return 2; }
        a.putObject("patron").put("did", "person").put("name", System.getProperty("user.name", "person")).put("runtime", "cli");
        ObjectNode r;
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
        printSuggestion(r);
        System.out.println("  " + r.path("next").asText());
        return 0;
    }

    /** researchzosho survey <thing> [--kind k] [--pick 1,3] [--do "…"]: a repository, a paper, a page or an issue tracker as a starting point. Without flags: read and offer directions. */
    static int survey(LibraryStore store, String[] args) throws Exception {
        if (args.length < 3) { System.err.println("usage: researchzosho survey <folder|file|url> [--kind repo|paper|site|issues] [--pick 1,3] [--do \"what to research\"]\n       researchzosho survey --from <report id> [--top 5] [--do \"what to research\"]   the repositories a report names, each cloned and read"); return 2; }
        if (args[2].equals("--from")) return surveyFrom(store, args);
        var a = new ObjectMapper().createObjectNode();
        String what = args[2];
        if (what.startsWith("db:")) a.put("database", what.substring(3));
        else if (what.startsWith("http://") || what.startsWith("https://") || what.startsWith("git@")) a.put("url", what); else a.put("path", Path.of(what).toAbsolutePath().normalize().toString());
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
        ObjectNode r;
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
            if (!r.path("surveyed_first").asText("").isBlank()) System.out.println("It had not been read yet, so the library read it first. " + r.path("surveyed_first").asText());
            for (var j : r.path("runs")) System.out.println("  " + j.path("question").asText() + " → " + j.path("job_id").asText());
            System.out.println(r.path("summary").asText());
        }
        System.out.println("  researchzosho jobs follows them");
        return 0;
    }

    /**
     * survey --from <report> [--top N] [--do "…"]: the repositories a finished report names, the first N of them each cloned into the
     * library and read, so that a search for projects ends with the projects themselves read and not only found. --do files one
     * research question about each as well.
     */
    static int surveyFrom(LibraryStore store, String[] args) throws Exception {
        if (args.length < 4) { System.err.println("usage: researchzosho survey --from <report id> [--top 5] [--do \"what to research\"]"); return 2; }
        String id = args[3]; int top = 5; String own = null;
        try { for (int i = 4; i < args.length; i++) switch (args[i]) { case "--top" -> top = Integer.parseInt(flagValue(args, i++)); case "--do" -> own = flagValue(args, i++); default -> { System.err.println("unknown flag " + args[i]); return 2; } } }
        catch (IllegalArgumentException e) { System.err.println(e.getMessage()); return 2; }
        Investigation inv = store.investigation(id);
        if (inv == null) { System.err.println("There is no report with the id " + id + ". The command researchzosho jobs lists the runs and their reports."); return 1; }
        List<String> repos = Surveys.reposIn(inv);
        if (repos.isEmpty()) { System.out.println("The report " + id + " names no GitHub repository in its answer, so there is nothing to clone."); return 0; }
        List<String> chosen = repos.subList(0, Math.min(top, repos.size()));
        System.out.println("The report names " + repos.size() + (repos.size() == 1 ? " repository" : " repositories") + ". The library clones the first " + chosen.size() + " and reads each: its files, languages, licence, README and issues. Each takes a minute or two.\n");
        LibraryProtocol p = new LibraryProtocol(store);
        int done = 0;
        for (String url : chosen) {
            System.out.println("== " + url);
            var a = new ObjectMapper().createObjectNode(); a.put("url", url); a.put("kind", "repo");
            a.putObject("patron").put("did", "person").put("name", System.getProperty("user.name", "person")).put("runtime", "cli");
            ObjectNode r;
            try { r = p.survey(a); } catch (ProtocolError e) { System.out.println("  could not be read: " + e.getMessage()); continue; }
            System.out.println("  " + r.path("what_it_is").asText());
            if (!r.path("summary_text").asText().isBlank()) System.out.println("  " + r.path("summary_text").asText());
            for (var x : r.path("claims")) System.out.println("    - " + x.asText());
            System.out.println("  directions (researchzosho survey " + url + " --pick 1,3 runs them):");
            for (var o : r.path("options")) System.out.println("    " + o.path("n").asInt() + ". " + o.path("question").asText());
            if (own != null) {
                a.put("op", "do"); a.put("question", own);
                try { ObjectNode d = p.survey(a); for (var j : d.path("runs")) System.out.println("  research run: " + j.path("question").asText() + " → " + j.path("job_id").asText()); }
                catch (ProtocolError e) { System.out.println("  the research question could not be filed: " + e.getMessage()); }
            }
            done++;
            System.out.println();
        }
        System.out.println(done + " of " + chosen.size() + " read and on the shelves. To read more of them: researchzosho survey --from " + id + " --top " + (chosen.size() + 5)
                + (own == null ? "\nTo file one research question about each as well: add --do \"…\"" : "\nresearchzosho jobs follows the research runs"));
        return done > 0 ? 0 : 1;
    }

    /** researchzosho remove <id> [--report-only | --claims-only] [--yes]: a report or a claim out of the library for good, after showing the plan. */
    static int remove(LibraryStore store, String[] args) throws Exception {
        if (args.length < 3) { System.err.println("usage: researchzosho remove <I-…|F-…> [--report-only | --claims-only] [--yes]"); return 2; }
        var a = new ObjectMapper().createObjectNode();
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
        ObjectNode plan;
        try { plan = p.remove(a.deepCopy().put("dry", true)); } catch (ProtocolError e) { System.err.println(e.getMessage()); return 1; }
        System.out.println(plan.path("plan").asText());
        if (plan.path("claims_go").size() == 0 && !plan.path("report_goes").asBoolean()) { System.out.println("Nothing to delete."); return 0; }
        if (!yes) {
            System.out.print("Delete these? This cannot be undone. [y/N] ");
            String line = System.console() != null ? System.console().readLine() : new BufferedReader(new InputStreamReader(System.in)).readLine();
            if (line == null || !line.strip().toLowerCase(Locale.ROOT).startsWith("y")) { System.out.println("Nothing was deleted."); return 0; }
        }
        ObjectNode r;
        try { r = p.remove(a); } catch (ProtocolError e) { System.err.println(e.getMessage()); return 1; }
        for (var x : r.path("removed")) System.out.println("  deleted " + x.asText());
        System.out.println(r.path("summary").asText());
        return 0;
    }

    /** researchzosho db …: the databases the owner gives the library read-only access to. */
    static int db(LibraryStore store, String[] args) throws Exception {
        String usage = "usage: researchzosho db add <name> <address> [--hide col,table.col]\n"
                + "       researchzosho db list | schema <name> | remove <name> | drivers | driver install <kind>\n"
                + "       researchzosho db query <name> \"<sql>\" [--limit N]\n"
                + "       researchzosho db query <name> --collection <c> [--filter '<json>' | --pipeline '<json array>'] [--limit N]   (MongoDB)\n"
                + "addresses: a SQLite file path · postgres://user:password@host:5432/dbname · mysql://user:password@host:3306/dbname · sqlserver://user:password@host:1433/dbname · mongodb://user:password@host:27017/dbname · duckdb:/path/file.duckdb · a folder of CSV or Parquet files\n"
                + "Use a database user that can only read. Leave the password out of the address and you are asked for it.";
        if (args.length < 3) { System.err.println(usage); return 2; }
        try {
            switch (args[2]) {
                case "drivers" -> {
                    for (DbDrivers.Kind k : DbDrivers.KINDS.values()) System.out.println("  " + k.name() + "  " + k.label() + " — " + (k.inBox() ? "in the box" : DbDrivers.installed(k) ? "installed" : "not installed (" + k.sizeNote() + "): researchzosho db driver install " + k.name()));
                    return 0;
                }
                case "driver" -> {
                    if (args.length < 5 || !args[3].equals("install")) { System.err.println(usage); return 2; }
                    DbDrivers.Kind k = DbDrivers.kind(args[4]);
                    if (k == null) { System.err.println("No such kind. The kinds are: " + String.join(", ", DbDrivers.KINDS.keySet())); return 2; }
                    if (k.inBox()) { System.out.println("The " + k.label() + " driver is in the box. Nothing to install."); return 0; }
                    System.out.println("Downloading the " + k.label() + " driver (" + k.sizeNote() + ") to " + DbDrivers.dir() + " …");
                    for (String f : DbDrivers.install(k)) System.out.println("  " + f);
                    System.out.println("Installed. Each file matched its expected checksum.");
                    return 0;
                }
                case "list" -> {
                    List<Databases.Db> all = Databases.list();
                    if (all.isEmpty()) System.out.println("No database has been added. Add one with: researchzosho db add <name> <address>");
                    for (Databases.Db d : all) System.out.println("  " + d.name() + "  " + DbDrivers.kind(d.kind()).label() + "  " + d.shown() + (d.hide().isEmpty() ? "" : "  hidden columns: " + String.join(", ", d.hide())));
                    String w = Databases.hostedWarning(); if (!w.isEmpty()) System.out.println(w);
                    return 0;
                }
                case "add" -> {
                    if (args.length < 5) { System.err.println(usage); return 2; }
                    String name = args[3], address = args[4]; List<String> hide = new ArrayList<>();
                    for (int i = 5; i < args.length; i++) { if (args[i].equals("--hide")) { for (String h : flagValue(args, i++).split(",")) if (!h.isBlank()) hide.add(h.strip()); } else { System.err.println("unknown flag " + args[i]); return 2; } }
                    if (address.matches("^[a-z+]+://[^:/@]+@.*") && System.console() != null) {   // a user and no password: ask, so it stays out of the shell history
                        char[] pw = System.console().readPassword("Password for %s: ", address.replaceAll("^[a-z+]+://([^@]+)@.*", "$1"));
                        if (pw != null && pw.length > 0) address = address.replaceFirst("^([a-z+]+://[^@]+)@", "$1:" + Matcher.quoteReplacement(URLEncoder.encode(new String(pw), StandardCharsets.UTF_8)) + "@");
                    }
                    Databases.Db d = Databases.add(name, address, hide);
                    System.out.println("Added " + d.name() + " (" + DbDrivers.kind(d.kind()).label() + "), read-only. The address is kept in " + Databases.file() + ", not in the library.");
                    System.out.println("Next: researchzosho db schema " + d.name() + "   or   researchzosho survey db:" + d.name());
                    String w = Databases.hostedWarning(); if (!w.isEmpty()) System.out.println(w);
                    return 0;
                }
                case "remove" -> {
                    if (args.length < 4) { System.err.println(usage); return 2; }
                    System.out.println(Databases.remove(args[3]) ? "Removed " + args[3] + ". Saved query results stay in the library." : "No database is named " + args[3] + ".");
                    return 0;
                }
                case "schema" -> {
                    if (args.length < 4) { System.err.println(usage); return 2; }
                    Databases.Db d = Databases.get(args[3]);
                    if (d == null) { System.err.println("No database is named " + args[3] + "."); return 1; }
                    System.out.println(Databases.schema(d));
                    return 0;
                }
                case "query" -> {
                    if (args.length < 5) { System.err.println(usage); return 2; }
                    Databases.Db d = Databases.get(args[3]);
                    if (d == null) { System.err.println("No database is named " + args[3] + "."); return 1; }
                    String sql = "", collection = "", filter = "", pipeline = ""; int limit = 0;
                    for (int i = 4; i < args.length; i++) {
                        switch (args[i]) {
                            case "--limit" -> limit = flagInt(args, i++);
                            case "--collection" -> collection = flagValue(args, i++);
                            case "--filter" -> filter = flagValue(args, i++);
                            case "--pipeline" -> pipeline = flagValue(args, i++);
                            default -> sql = sql.isEmpty() ? args[i] : sql + " " + args[i];
                        }
                    }
                    Databases.Result r = d.kind().equals("mongo") ? Databases.queryMongo(store, d, collection, filter, pipeline, limit) : Databases.query(store, d, sql, limit);
                    System.out.println(r.text());
                    if (!r.saved().isEmpty()) System.out.println("Saved as " + r.saved());
                    return 0;
                }
                default -> { System.err.println(usage); return 2; }
            }
        } catch (IOException | IllegalArgumentException e) { System.err.println(e.getMessage()); return 1; }
    }

    /** researchzosho holdings <words…>: what the person's lists hold. */
    static int holdings(LibraryStore store, String[] args) throws Exception {
        if (args.length < 3) { System.err.println("usage: researchzosho holdings <title words, author, year…> [--limit <n>]"); return 2; }
        var a = new ObjectMapper().createObjectNode();
        StringBuilder q = new StringBuilder();
        for (int i = 2; i < args.length; i++) { if (args[i].equals("--limit")) { a.put("limit", flagInt(args, i++)); continue; } if (q.length() > 0) q.append(' '); q.append(args[i]); }
        a.put("query", q.toString());
        a.putObject("patron").put("did", "person").put("name", System.getProperty("user.name", "person")).put("runtime", "cli");
        ObjectNode r;
        try { r = new LibraryProtocol(store).holdings(a); } catch (ProtocolError e) { System.err.println(e.getMessage()); return 1; }
        System.out.println(r.path("summary").asText());
        for (var m : r.path("matches")) System.out.println("  " + m.path("item").asText() + (m.hasNonNull("note") ? " — " + m.path("note").asText() : "") + "   [" + m.path("list").asText() + "]");
        return 0;
    }

    /** researchzosho absorb <file|url> [--title T] [--collection N] [--verify] [--limit N]: a conversation with another assistant, as a starting point. */
    static int absorb(LibraryStore store, String[] args) throws Exception {
        if (args.length < 3) { System.err.println("usage: researchzosho absorb <transcript|export.json|url> [--title <t>] [--collection <name>] [--verify] [--limit <n>] [--field <name>]\n  --field <name>, or --<name>, runs the checking run in that field's mode"); return 2; }
        var a = new ObjectMapper().createObjectNode();
        String what = args[2];
        if (what.startsWith("http://") || what.startsWith("https://")) a.put("url", what); else a.put("path", Path.of(what).toAbsolutePath().normalize().toString());
        try {
            for (int i = 3; i < args.length; i++) {
                switch (args[i]) {
                    case "--title" -> a.put("title", flagValue(args, i++));
                    case "--collection" -> a.put("collection", flagValue(args, i++));
                    case "--verify" -> a.put("verify", true);
                    case "--limit" -> a.put("limit", flagInt(args, i++));
                    case "--field" -> a.put("field", flagValue(args, i++));
                    default -> {
                        if (!fieldFlag(a, args[i])) { System.err.println("unknown flag " + args[i]); return 2; }
                    }
                }
            }
        } catch (IllegalArgumentException e) { System.err.println(e.getMessage()); return 2; }
        a.putObject("patron").put("did", "person").put("name", System.getProperty("user.name", "person")).put("runtime", "cli");
        ObjectNode r;
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
        printSuggestion(r);
        return 0;
    }

    static int add(LibraryStore store, String[] args) throws Exception {
        if (args.length < 3) { System.err.println("usage: researchzosho add <file|url|folder> [title] [--for <url>] [--collection <name>] [--register] [--link] [--survey]"); return 2; }
        String what = args[2];
        // a folder: the corpus path
        if (!what.startsWith("http://") && !what.startsWith("https://") && Files.isDirectory(Path.of(what))) {
            String coll = null; boolean register = false, link = false, survey = false, pictures = false;
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
            final boolean linkIt = link; final String intoColl = coll; final Path theDir = dir;
            Corpus.Outcome o = pictures ? Corpus.withPictures(() -> Corpus.addFolder(store, theDir, intoColl, true, linkIt)) : Corpus.addFolder(store, dir, coll, true, link);
            long left = pictures ? 0 : Corpus.picturesIn(dir);
            if (left > 0) System.out.println("  " + left + " picture(s) in the folder were left alone. If they are scanned pages or photographed documents, add them too: researchzosho add " + dir + " --pictures  (the model reads each one)");
            if (register) Corpus.register(store, coll, dir, link);
            System.out.println("collection " + coll + ": " + o.line() + (link ? " — read in place, nothing copied" : "") + (register ? " — registered, nightly maintenance rescans it" : ""));
            for (String pr : o.problems()) System.out.println("  " + pr);
            System.out.println("  ask them: researchzosho research ask \"…\" --shelves, or library_research {\"sources\":\"shelves\",\"collections\":[\"" + coll + "\"]}");
            return 0;
        }
        // a document supplied for a locator the runner could not read
        String forUrl = null; boolean linkFile = false;
        List<String> rest = new ArrayList<>();
        for (int i = 3; i < args.length; i++) { if (args[i].equals("--for")) forUrl = flagValue(args, i++); else if (args[i].equals("--link")) linkFile = true; else rest.add(args[i]); }
        if (linkFile && !what.startsWith("http://") && !what.startsWith("https://")) {
            Path f = Path.of(what).toAbsolutePath().normalize();
            Path raw = Corpus.addFile(store, f, "", true);
            if (raw == null) { System.err.println("no text could be read from " + f.getFileName() + "." + Corpus.whyNoText()); return 1; }
            System.out.println("linked " + (rest.isEmpty() ? RawCapture.read(raw)[1] : String.join(" ", rest)) + "\n  read in place from " + f + ", nothing copied → " + raw.getFileName());
            return 0;
        }
        if (forUrl != null) {
            Path f = Path.of(what).toAbsolutePath().normalize();
            var doc = DocText.convert(Files.readAllBytes(f), what);
            if (doc.text().isBlank()) { System.err.println("no text could be extracted (" + doc.kind() + ")"); return 1; }
            Path raw = Requests.supply(store, forUrl, doc.text(), rest.isEmpty() ? doc.title() : String.join(" ", rest));
            System.out.println("supplied: " + forUrl + " ← " + f.getFileName() + " (" + doc.text().length() + " chars) → " + raw.getFileName()
                    + "\n  from now on this document is read for that url");
            return 0;
        }
        String givenTitle = rest.isEmpty() ? "" : String.join(" ", rest);
        byte[] bytes;
        String locator;
        PageCheck.Page page = null;
        if (what.startsWith("http://") || what.startsWith("https://")) {
            try {
                // redirects followed, address checked; the person's own address, so of the page check only the always-dropped question
                page = PageCheck.fetch(what, Duration.ofSeconds(60), ContentPolicy.person(null), "");
            } catch (Exception e) {
                System.err.println("could not fetch " + what + ": " + e.getMessage()); return 1;
            }
            Fetch.Result resp = page.fetched();
            if (resp.status() >= 400) { System.err.println("HTTP " + resp.status() + " for " + what); return 1; }
            if (!page.kept()) { System.err.println(PageCheck.notSaved(resp.url(), page.leftOut())); return 1; }
            bytes = resp.body();
            locator = resp.url();
        } else {
            Path f = Path.of(what).toAbsolutePath().normalize();
            bytes = Files.readAllBytes(f);
            locator = "file://" + f;
        }
        // a page: the text the check read is the text saved, converted once (a scanned PDF is not read twice by the model that reads pictures)
        var doc = page != null && page.doc() != null ? page.doc() : DocText.convert(bytes, what);
        if (doc.text().isBlank()) { System.err.println("no text could be extracted (" + doc.kind() + ")"); return 1; }
        String title = givenTitle.isBlank() ? doc.title() : givenTitle;
        Citations.Meta meta = Citations.resolve(store, locator, Citations.LIVE);   // a DOI / arXiv / PubMed url: the record's title and citation
        if (meta != null && !meta.title().isEmpty() && givenTitle.isBlank()) title = meta.title();
        Captions.Extract cx = Captions.extract(doc.text());
        Path raw = RawCapture.capture(store, locator, doc.text(), title, "researchzosho-add", "");   // THIS store, not the configured one (a test with two libraries found the difference)
        if (raw == null) { System.err.println("Not saved: this machine has no library (`researchzosho init`), or the page was refused"); return 1; }
        // the person's own address: saved even when no model could check it, and checked when one answers; a checked page means a
        // model answers now, so the pages still waiting are checked, and what that check removes is said here
        UncheckedPages.Outcome later = page == null ? null : UncheckedPages.after(store, page, locator, raw, null);
        String[] onDisk = RawCapture.read(raw);
        boolean same = onDisk[2].strip().equals(doc.text().strip());
        System.out.println("saved to the library [" + doc.kind() + "] " + (title.isEmpty() ? locator : title));
        if (meta != null) System.out.println("  citation: " + meta.edition());
        if (!cx.isEmpty()) System.out.println("  " + cx.figures().size() + " figure caption(s), " + cx.tables().size() + " table caption(s), " + cx.rows().size() + " table row(s) found in the text");
        System.out.println("  " + onDisk[2].length() + " chars on disk → " + raw
                + (same ? "" : "  (an earlier saved copy of this url from today was kept)"));
        System.out.println("  researchzosho ask finds it now. `researchzosho review` does not read saved pages:"
                + " ask about it, or research it, to turn it into claims");
        if (page != null && page.unchecked())
            System.out.println("  This page is " + PageCheck.NOT_CHECKED_YET + ". The library checks it when a model answers, at the next housekeeping or the next command that has a model, and removes it then if the check finds " + PageCheck.Category.CHILD.said() + ".");
        if (later != null && !later.sentence().isEmpty()) System.out.println(later.sentence());
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

    /** looked [<words>] | looked move <claim id…> | looked add <subject> --where <site> --what <words>: the dated ledger of what searches looked for and did not find. */
    private static int looked(LibraryStore store, String[] args) throws Exception {
        if (args.length > 2 && args[2].equals("add")) {
            // a search the person made by hand, on a site the library cannot search, that found nothing
            List<String> subject = new ArrayList<>(), where = new ArrayList<>(), what = new ArrayList<>();
            List<String> into = subject;
            for (int i = 3; i < args.length; i++) {
                if (args[i].equals("--where")) into = where;
                else if (args[i].equals("--what")) into = what;
                else into.add(args[i]);
            }
            String about = String.join(" ", subject).strip(), site = String.join(" ", where).strip(), words = String.join(" ", what).strip();
            if (about.isEmpty() || site.isEmpty() || words.isEmpty()) {
                System.err.println("usage: researchzosho looked add <person or subject> --where <site> --what <the words you searched for>\n"
                        + "  Writes down a search you made yourself that found nothing, for example on FamilySearch or in a register at an archive, with today's date.\n"
                        + "  The library's own searches are then shown it as a place somebody has already looked.");
                return 2;
            }
            String named = Graph.named(store, about);   // the library's own spelling of the name, when it holds it
            if (named != null) about = named;
            for (RecordSources.SearchLink l : RecordSources.links()) if (l.id().equalsIgnoreCase(site)) site = l.name();
            RecordSource known = RecordSources.named(site);
            if (known != null) site = known.name();
            Looked.Entry e = new Looked.Entry(Looked.today(), about, "searched by hand for " + words + ".", List.of(site), "", about);
            if (!Looked.add(store, e)) { System.out.println("This search is written down already."); return 0; }
            System.out.println("Written down: " + Looked.line(e) + "\n\nThe next search for " + about + " is shown this line: somebody looked there on this date and found nothing. "
                    + "It says where you looked, not that the record does not exist, so a later search with another spelling or in a collection added since can still find it.");
            return 0;
        }
        if (args.length > 2 && args[2].equals("move")) {
            if (args.length < 4) { System.err.println("usage: researchzosho looked move <claim id> [<claim id>…]\n  for a claim that says nothing was found: it becomes a dated line in this list, and the claim is retired."); return 2; }
            int moved = 0;
            for (int i = 3; i < args.length; i++) {
                Looked.Entry e = Looked.move(store, args[i]);
                if (e == null) { System.out.println("There is no claim " + args[i] + "."); continue; }
                System.out.println("Moved " + args[i] + ": " + Looked.line(e)); moved++;
            }
            if (moved > 0) System.out.println("\nThe " + (moved == 1 ? "claim is" : moved + " claims are") + " retired, so no answer uses " + (moved == 1 ? "it" : "them") + " as a fact. A later search about the same subject is shown the line as a place somebody has looked.");
            return moved > 0 ? 0 : 1;
        }
        String words = String.join(" ", Arrays.copyOfRange(args, 2, args.length)).strip();
        List<Looked.Entry> lines = words.isEmpty() ? Looked.all(store) : Looked.about(store, words, 200);
        if (lines.isEmpty()) { System.out.println(words.isEmpty() ? "No search has been recorded as looking for something and not finding it." : "No earlier search is recorded as looking for \"" + words + "\" and finding nothing."); return 0; }
        System.out.println("What earlier searches looked for and did not find. Each line is one search on its date: it says where somebody has looked, not what exists. "
                + "A collection that was added later, another spelling of a name, or new access can find what an earlier search did not.\n");
        lines.stream().sorted(Comparator.comparing(Looked.Entry::date).reversed()).forEach(e -> System.out.println("  " + Looked.line(e)));
        return 0;
    }

    private static int inbox(LibraryStore store, String[] args) throws Exception {
        // the same filters as the Inbox page and library_inbox
        var f = new LinkedHashMap<String, String>();
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
        if (all.isEmpty()) { System.out.println("Nothing is waiting for you. New facts appear here after a research run finishes or after you read in your own material."); return 0; }
        var rows = new ArrayList<ObjectNode>();
        for (var o : all) if (LibraryProtocol.inboxMatches(o, f)) rows.add(o);
        if (rows.isEmpty()) { System.out.println("None of the " + all.size() + " facts waiting for you match what you asked for. Leave out the options at the end of the command to see them all."); return 0; }
        System.out.println(rows.size() + (rows.size() == all.size() ? "" : " of the " + all.size()) + (rows.size() == 1 ? " fact is" : " facts are") + " waiting for you to say whether "
                + (rows.size() == 1 ? "it is" : "they are") + " right. The library does not use a fact in its answers as settled until you accept it.\n");
        int w = Math.min(60, rows.stream().mapToInt(o -> o.path("id").asText().length()).max().orElse(10));
        String example = rows.get(0).path("id").asText();
        System.out.printf("  %-" + w + "s %-12s %-18s %-16s %-6s %s%n", "CODE", "STATUS", "HOW IT WAS FOUND", "SOURCE", "SURE?", "THE FACT");
        String lastGroup = null;
        for (var o : rows) {
            if (byReport) {
                String grp = o.path("report").asText("");
                if (!grp.equals(lastGroup)) { System.out.println(grp.isEmpty() ? "\nNot from a research report:" : "\nFrom the report " + grp + (o.hasNonNull("report_title") ? " — " + o.path("report_title").asText() : "") + ":"); lastGroup = grp; }
            }
            StringBuilder tail = new StringBuilder();
            for (var sj : o.path("subjects")) tail.append(" · ").append(sj.asText());
            if (!o.path("language").asText("english").equals("english")) tail.append(" · ").append(o.path("language").asText());
            if (o.path("checked_against").asText("").startsWith("a machine reading")) tail.append(" · read from a picture whose reading nobody has checked");
            System.out.printf("  %-" + w + "s %-12s %-18s %-16s %-6s %s%s%n", o.path("id").asText(),
                    o.path("stale").asBoolean() ? "check again" : o.path("state").asText().equals("draft") ? "new" : o.path("state").asText(),
                    switch (o.path("kind").asText()) { case "extraction" -> "read in one source"; case "synthesis" -> "put together"; case "interpretation" -> "a reading of it"; case "speculation" -> "a forecast"; default -> o.path("kind").asText(); },
                    switch (o.path("tier").asText()) { case "reference" -> "reference"; case "scholarly" -> "scholarly"; case "primary" -> "the thing itself"; case "code" -> "repository code"; case "personal" -> "your own"; case "blog" -> "a blog"; case "forum" -> "a forum"; default -> "a web page"; },
                    o.path("confidence").asText(),
                    Acquisitions.compress(o.path("title").asText(), 60), tail.length() > 0 ? "  (" + tail.substring(3) + ")" : "");
        }
        System.out.println("\nWhat each column means:\n"
                + "  STATUS            new: nobody has looked at it yet. check again: it was accepted, but it has changed or its time to be checked has come.\n"
                + "  HOW IT WAS FOUND  read in one source, or put together from several sources, or a reading of what the sources say, or a forecast.\n"
                + "  SOURCE            the strongest kind of source it rests on.\n"
                + "  SURE?             how sure the run was: high, medium or low.\n\n"
                + "What you can do with a fact. Type its whole code, as the first column shows it:\n"
                + "  researchzosho explain " + example + " --written\n      shows the fact as it was written. The library's web page shows its sources as well.\n"
                + "  researchzosho accept " + example + "\n      says it is right. The library then uses it in its answers.\n"
                + "  researchzosho dispute " + example + " \"why it is wrong\"\n      says it is wrong, and keeps your reason with it.\n"
                + "  researchzosho retire " + example + "\n      stops using it, without saying it is wrong.\n"
                + "  researchzosho accept --report I-0010\n      accepts every fact from one research report at once (the report's code is on its page and in researchzosho jobs).\n\n"
                + "To see only some of the facts, add for example --grep \"a word\", --report and a report's code, --state stale, or --by-report to group them by report.\n"
                + "The library's web page Inbox shows the same list, with a tick box for each fact.");
        return 0;
    }

    /** What a dispute or a retirement took off the waiting list, said for the person: "" when nothing. */
    static String offTheList(LibraryStore store, List<String> lines) {
        if (lines.isEmpty()) return "";
        return (lines.size() == 1 ? "1 question on the waiting list was" : lines.size() + " questions on the waiting list were") + " written with this fact, so "
                + (lines.size() == 1 ? "it was" : "they were") + " taken off the list and will not be sent." + Fields.hints(store, "taken-off") + "\n";
    }

    private static int council(LibraryStore store, String[] args) throws Exception {
        if (args.length < 3) { System.err.println("usage: researchzosho " + args[1] + " <id> [why]"); return 2; }
        Council c = new Council(store);
        String id = args[2];
        switch (args[1]) {
            case "accept" -> {
                // one id, several ids, or --all (every draft, in id order); each is printed so nothing passes silently
                List<String> ids = new ArrayList<>();
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
                    for (Profile p : Profiles.known()) { String note = p.onAccept(store, f); if (!note.isBlank()) System.out.println("  " + note); }
                }
                if (ids.size() > 1) System.out.println(ids.size() + " accepted, signed person");
            }
            case "retire" -> {
                List<String> ids = new ArrayList<>();
                if ("--report".equals(id)) {
                    if (args.length < 4) { System.err.println("usage: researchzosho retire --report <I-…>"); return 2; }
                    for (var o : new LibraryProtocol(store).inboxList()) if (o.path("report").asText("").startsWith(args[3])) ids.add(o.path("id").asText());
                    if (ids.isEmpty()) { System.out.println("no claims of " + args[3] + " are waiting"); return 0; }
                } else ids.addAll(args.length > 3 ? Arrays.asList(args).subList(2, args.length) : List.of(id));
                for (String each : ids) {
                    Finding f = c.retire(each);
                    System.out.println("retired " + f.id() + ": no longer used, its record is kept");
                    System.out.print(offTheList(store, c.takenOff()));
                }
            }
            case "dispute" -> {
                if (args.length < 4) { System.err.println("usage: researchzosho dispute <id> <why>"); return 2; }
                Finding f = c.dispute(id, String.join(" ", Arrays.copyOfRange(args, 3, args.length)));
                System.out.println("disputed " + f.id() + ": an open question was added");
                System.out.print(offTheList(store, c.takenOff()));
                // a doubted fact may point at a doubtful source: what else rests on each of its sources, read as the field whose work the
                // claim is reads it (a field that joins only when asked), or as the core reads it
                Profile lens = null;
                for (String field : Fields.ofClaim(store, f)) { Profile p = Fields.enabledNamed(store, field); if (p != null && p.joinsOnlyWhenAsked()) { lens = p; break; } }
                Set<String> shown = new HashSet<>();
                for (Finding.Source src : f.sources()) {
                    // a file of a cloned repository is one source, named by the file, whichever of its lines the claim cites
                    String file = CodeTool.fileOf(src.locator());
                    if (!shown.add(file != null ? file : src.locator())) continue;
                    String what = file != null ? file : src.locator().startsWith("file:") ? src.locator().substring(src.locator().lastIndexOf('/') + 1) : src.locator();
                    String said = Evidence.restingForPerson(what, Evidence.restingOn(store, src.locator(), f.id(), lens), true);
                    if (!said.isEmpty()) System.out.println("\n" + said);
                }
            }
            default -> { return 2; }
        }
        return 0;
    }

    /** The research run going in this machine's library, as "J-0042, 12 min in"; null when none is going or there is no library. */
    static String runningResearch() {
        try {
            LibraryStore store = LibraryStore.open();   // the setting, else the default folder, as the service finds it
            if (!Files.isDirectory(store.root().resolve("catalog"))) return null;
            for (var j : new Jobs(store, x -> "").active()) {
                if (!"running".equals(j.path("state").asText()) || !"research".equals(j.path("kind").asText())) continue;
                String started = j.path("started_at").asText("");
                long min = started.isEmpty() ? -1 : Duration.between(Instant.parse(started), Instant.now()).toMinutes();
                return j.path("job_id").asText() + (min >= 0 ? ", " + min + " min in" : "");
            }
        } catch (Exception ignored) { }
        return null;
    }
}
