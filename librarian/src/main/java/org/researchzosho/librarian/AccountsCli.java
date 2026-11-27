package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import org.researchzosho.Config;

import java.io.Closeable;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The account service at the command line (0.8.0), for whoever runs it: {@code serve} with the setting {@code accounts} on, and
 * {@code researchzosho accounts …}, which makes an account and prints its join link, lists the accounts and their libraries, gives a
 * new link, closes an account, makes another library for an account, lets a person into a library, and makes a key. It works on the
 * store itself, whether the service is running or not.
 */
public final class AccountsCli {
    private AccountsCli() { }

    static final String USAGE = """
            usage: researchzosho accounts                                        the accounts and their libraries
                   researchzosho accounts add "<name>" [--mail <address>] [--admin]   a new account with a library of its own; prints the link to send
                   researchzosho accounts link "<name>"                          a new join link, for a forgotten password
                   researchzosho accounts remove "<name>" [--grace]             delete an account at once, with its libraries; --grace: frozen, removed after 30 days
                   researchzosho accounts keep "<name>"                          an account being deleted kept after all, as it was
                   researchzosho accounts suspend "<name>"                       frozen at once: no sign-in, its keys refused, its work stopped, nothing deleted
                   researchzosho accounts unsuspend "<name>"                     let back as it was
                   researchzosho accounts handover "<library>" "<name>"          a library handed to one of its people, who keeps it from now on
                   researchzosho accounts audit ["<name>"] [--days <n>]           what was done: sign-ins, keys, members, caps, accounts, libraries
                   researchzosho accounts library "<name>" "<title>"             another library kept by that account
                   researchzosho accounts member "<library>" "<name>" read|write|remove   who may read or write a library
                   researchzosho accounts key "<name>" [--read|--write] [--library <library>] "<what it is for>"   a key for a program
                   researchzosho accounts move "<library>" <folder>               its folder moved under another root of this machine
                   researchzosho accounts delete "<library>" [--now]              out of service; removed with its copy after 30 days, or now
                   researchzosho accounts take <folder>|<archive> --for "<name>"   a library of this machine's own, or one's archive, taken in, kept by that account
                   researchzosho accounts release "<library>" <folder>            released: an ordinary library again, at that folder
                   researchzosho accounts restore "<library>" [<root>|<machine>]   brought back from its copy (onto another machine: its root, or its id)
                   researchzosho accounts usage ["<name>"]                       this month's use: every account's, or one account's with its caps
                   researchzosho accounts caps ["<name>"] [<cap> <value>|default]   the service's caps, or one account's; a value sets one
                   researchzosho accounts setup public --domain <name> [--nodes 2 …]   a public service's folder for Docker Compose: Caddy, PostgreSQL, the service
                   researchzosho accounts update <version>                       the service moved to another version, in that folder
            Several accounts are served when the setting accounts is on: researchzosho settings set accounts on, then researchzosho serve.
            """;

    /** Where the running service wrote the address it listens on, for the links this command prints. */
    static Path addressFile() { return AccountService.root().resolve("address"); }

    /** The address a link is written with: the setting, else where the service last said it listens, else this machine's default. */
    static String address() {
        String set = Config.get(AccountService.ADDRESS);
        if (set != null && !set.isBlank()) return set.strip().replaceAll("/+$", "");
        try { if (Files.isRegularFile(addressFile())) { String a = Files.readString(addressFile(), StandardCharsets.UTF_8).strip(); if (!a.isEmpty()) return a; } } catch (IOException ignored) { }
        return "http://127.0.0.1:" + LibrarianDaemon.DEFAULT_PORT;
    }

    public static int run(String[] args, PrintStream out, PrintStream err) {
        String op = args.length > 2 ? args[2] : "list";
        List<String> words = new ArrayList<>();
        String mail = "", library = "", level = "read", forName = ""; boolean admin = false, now = false, grace = false; int days = 30;
        for (int i = 3; i < args.length; i++) {
            switch (args[i]) {
                case "--mail" -> { if (i + 1 < args.length) mail = args[++i]; }
                case "--library" -> { if (i + 1 < args.length) library = args[++i]; }
                case "--admin" -> admin = true;
                case "--now" -> now = true;
                case "--grace" -> grace = true;
                case "--for" -> { if (i + 1 < args.length) forName = args[++i]; }
                case "--read" -> level = "read";
                case "--write" -> level = "write";
                case "--days" -> { if (i + 1 < args.length) { try { days = Integer.parseInt(args[++i]); } catch (NumberFormatException e) { err.println("--days is a number of days."); return 2; } } }
                default -> words.add(args[i]);
            }
        }
        if (op.equals("setup")) return Deployment.setup(args, out, err);   // a public service's folder, and moving it to another version (stage 4, step 5): on the machine, before or beside the service
        if (op.equals("update")) return Deployment.update(args, out, err);
        if (!AccountService.on() && !op.equals("list")) out.println("Note: the setting accounts is off, so the service is not serving accounts now. Turn it on with: researchzosho settings set accounts on");
        try (AccountStore store = AccountService.openStore()) {
            switch (op) {
                case "list" -> {
                    List<AccountStore.Account> all = store.accounts();
                    List<AccountStore.NodeRow> machines = store.nodes();
                    if (machines.size() > 1 || machines.stream().anyMatch(n -> !n.root().equals(AccountService.root().toString()))) {   // several machines: which answer, and what each runs
                        long clock = store.clock();
                        Map<String, Integer> awake = new HashMap<>();
                        for (AccountStore.Placement p : store.placements()) if (p.leaseUntil() > clock) awake.merge(p.node(), 1, Integer::sum);
                        out.println("Machines:");
                        for (AccountStore.NodeRow n : machines) out.println("    " + n.id() + "  " + n.address() + "  " + n.root() + "  " + (clock - n.seenAt() <= Node.LEASE_MS ? "answers" : "silent for " + (clock - n.seenAt()) / 1000 + " s") + ", " + awake.getOrDefault(n.id(), 0) + " awake");
                    }
                    if (all.isEmpty()) { out.println("No account yet. Make the first one with: researchzosho accounts add \"<your name>\" --admin"); return 0; }
                    for (AccountStore.Account a : all) {
                        out.println(a.name() + (a.mail().isEmpty() ? "" : " <" + a.mail() + ">") + (a.admin() ? "  (runs the service)" : "") + (a.hasPassword() ? "" : store.identityIssuer(a.id()) != null ? "  (signs in through " + providerName(store.identityIssuer(a.id())) + ")" : "  (has not chosen a password yet)")
                                + (a.state().equals("suspended") ? "  (suspended)" : a.state().equals("deleting") ? "  (to be removed on " + day(store.removeAt(a.id())) + ")" : ""));
                        for (AccountStore.Library l : store.librariesOf(a.id())) out.println("    " + l.name() + "  \"" + l.title() + "\"  " + copyNote(l) + switch (store.level(l, a.id())) { case Statement.KEEPER -> "keeps it"; case Statement.WRITE -> "reads and writes"; default -> "reads"; });
                    }
                    return 0;
                }
                case "add" -> {
                    if (words.isEmpty()) { err.print(USAGE); return 2; }
                    AccountService.Made m = AccountService.newAccount(store, AccountService.root(), String.join(" ", words), mail, admin, "the command line");
                    AccountActs.audit(store, AccountActs.By.cli(), "account_made", m.account().id(), m.library().id(), null);
                    out.println("An account is made for " + m.account().name() + ", with a library of its own (" + m.library().name() + ").");
                    out.println("Send them this link. It works once, for " + AccountService.JOIN_DAYS + " days; there they choose their password:");
                    out.println(address() + "/join?code=" + m.code());
                    return 0;
                }
                case "link" -> {
                    AccountStore.Account a = need(store, words, err); if (a == null) return 1;
                    out.println("A new link for " + a.name() + ". It works once, for " + AccountService.JOIN_DAYS + " days, and the one before it no longer opens:");
                    out.println(address() + "/join?code=" + store.newInvite(a.id(), "the command line", AccountService.JOIN_DAYS));
                    AccountActs.audit(store, AccountActs.By.cli(), "join_link_made", a.id(), "", null);
                    return 0;
                }
                case "remove" -> {   // the deletion of an account (stage 4): frozen now, removed after its grace, or now
                    AccountStore.Account a = need(store, words, err); if (a == null) return 1;
                    List<AccountStore.Library> kept = store.librariesKeptBy(a.id());
                    // whoever runs the service deletes an account at once, as a moderation, unless they give it the days of grace (--grace); in
                    // those days its person cannot keep it, only the operator can (the owner, 2026-11-18)
                    int days0 = grace && !now ? AccountActs.deleteDays() : 0;
                    long at = AccountActs.delete(store, a, days0, AccountActs.By.cli());
                    if (days0 == 0) { out.println("The account of " + a.name() + " is removed, with " + (kept.isEmpty() ? "no library of its own." : "the libraries it kept and their copies: " + String.join(", ", kept.stream().map(AccountStore.Library::name).toList()) + ".")); return 0; }
                    out.println("The account of " + a.name() + " is frozen now: it signs in only to keep itself, its keys are refused, its work is stopped, and the libraries it keeps open for nobody.");
                    out.println("  It is removed on " + day(at) + ", with " + (kept.isEmpty() ? "no library of its own." : "the libraries it keeps and their copies: " + String.join(", ", kept.stream().map(AccountStore.Library::name).toList()) + "."));
                    out.println("  Until then it is kept, as it was, with: researchzosho accounts keep \"" + a.name() + "\"");
                    return 0;
                }
                case "keep" -> {
                    AccountStore.Account a = need(store, words, err); if (a == null) return 1;
                    if (!AccountActs.cancelDeletion(store, a, AccountActs.By.cli())) { err.println("The account of " + a.name() + " is not being deleted."); return 1; }
                    out.println("The account of " + a.name() + " is kept, as it was. Its stopped work waits for them to resume it.");
                    return 0;
                }
                case "suspend" -> {
                    AccountStore.Account a = need(store, words, err); if (a == null) return 1;
                    if (!AccountActs.suspend(store, a, AccountActs.By.cli())) { err.println("The account of " + a.name() + " is not active, so it is not suspended now."); return 1; }
                    out.println("The account of " + a.name() + " is suspended: its browsers are signed out, its keys are refused, its running work stops after the call it is making and is kept,");
                    out.println("  and the libraries it keeps open for nobody and sleep once quiet. Nothing is deleted. It comes back with: researchzosho accounts unsuspend \"" + a.name() + "\"");
                    return 0;
                }
                case "unsuspend" -> {
                    AccountStore.Account a = need(store, words, err); if (a == null) return 1;
                    if (!AccountActs.unsuspend(store, a, AccountActs.By.cli())) { err.println("The account of " + a.name() + " is not suspended."); return 1; }
                    out.println("The account of " + a.name() + " is back as it was: its keys work again; its stopped work waits for them to resume it.");
                    return 0;
                }
                case "handover" -> {
                    if (words.size() < 2) { err.print(USAGE); return 2; }
                    AccountStore.Library l = store.libraryByName(words.get(0));
                    if (l == null || !l.active()) { err.println("No library in service is named " + words.get(0) + ". researchzosho accounts lists them."); return 1; }
                    AccountStore.Account a = need(store, words.subList(1, 2), err); if (a == null) return 1;
                    AccountActs.handOver(store, l, a, AccountActs.By.cli());
                    out.println("\"" + l.title() + "\" is kept by " + a.name() + " from now on; its storage counts on theirs.");
                    return 0;
                }
                case "audit" -> { return audit(store, words, days, out, err); }
                case "library" -> {
                    if (words.size() < 2) { err.print(USAGE); return 2; }
                    AccountStore.Account a = need(store, words.subList(0, 1), err); if (a == null) return 1;
                    AccountStore.Library l = AccountService.newLibrary(store, AccountService.root(), a, String.join(" ", words.subList(1, words.size())));
                    AccountActs.audit(store, AccountActs.By.cli(), "library_made", a.id(), l.id(), null);
                    out.println("A library \"" + l.title() + "\" is made (" + l.name() + "), kept by " + a.name() + ". They open it from their account page.");
                    return 0;
                }
                case "member" -> {
                    if (words.size() < 3) { err.print(USAGE); return 2; }
                    AccountStore.Library l = store.libraryByName(words.get(0));
                    if (l == null) { err.println("No library is named " + words.get(0) + ". researchzosho accounts lists them."); return 1; }
                    AccountStore.Account a = need(store, words.subList(1, 2), err); if (a == null) return 1;
                    String what = words.get(2).toLowerCase();
                    if (what.equals("remove")) { AccountActs.audit(store, AccountActs.By.cli(), "member_removed", a.id(), l.id(), null); out.println(store.removeMember(l.id(), a.id()) ? a.name() + " is out of \"" + l.title() + "\"; their keys for it are revoked." : a.name() + " was not one of its people."); return 0; }
                    if (a.id().equals(l.owner())) { err.println(a.name() + " keeps that library."); return 1; }
                    store.setMember(l.id(), a.id(), what, "the command line");
                    AccountActs.audit(store, AccountActs.By.cli(), "member_let_in", a.id(), l.id(), AccountActs.detail().put("level", what));
                    out.println(a.name() + " " + (what.equals("write") ? "reads and writes" : "reads") + " \"" + l.title() + "\" from now on.");
                    return 0;
                }
                case "key" -> {
                    if (words.size() < 1) { err.print(USAGE); return 2; }
                    AccountStore.Account a = need(store, words.subList(0, 1), err); if (a == null) return 1;
                    AccountStore.Library l = library.isEmpty() ? store.librariesOf(a.id()).stream().findFirst().orElse(null) : store.libraryByName(library);
                    String may = store.level(l, a.id());
                    if (may == null) { err.println(library.isEmpty() ? a.name() + " has no library." : a.name() + " may not open the library " + library + "."); return 1; }
                    if (level.equals("write") && may.equals(Statement.READ)) { err.println(a.name() + " reads \"" + l.title() + "\" and does not write to it, so a key of theirs for it reads only."); return 1; }
                    AccountStore.Minted m = store.mintKey(a.id(), l.id(), level, String.join(" ", words.subList(1, words.size())));
                    AccountActs.audit(store, AccountActs.By.cli(), "key_made", a.id(), l.id(), AccountActs.detail().put("level", level));
                    out.println("A key of " + a.name() + " for \"" + l.title() + "\", which " + (m.key().reads() ? "reads" : "reads and writes") + ". It is shown this once; the service keeps only its hash:");
                    out.println(m.secret());
                    out.println("A program sends it as Authorization: Bearer <key> to " + address() + ".");
                    return 0;
                }
                case "move", "delete", "release", "restore" -> {
                    if (words.isEmpty() || (!op.equals("delete") && !op.equals("restore") && words.size() < 2)) { err.print(USAGE); return 2; }
                    AccountStore.Library l = store.libraryByName(words.get(0));
                    if (l == null || !l.active()) { err.println("No library in service is named " + words.get(0) + ". researchzosho accounts lists them."); return 1; }
                    int r = places(store, l, op, words.size() > 1 ? Path.of(words.get(1)) : null, now, out);
                    AccountActs.audit(store, AccountActs.By.cli(), switch (op) { case "move" -> "library_moved"; case "delete" -> "library_deleted"; case "release" -> "library_released"; default -> "library_restored"; }, l.owner(), l.id(), null);
                    return r;
                }
                case "take" -> {
                    if (words.isEmpty()) { err.print(USAGE); return 2; }
                    AccountStore.Account a = forName.isEmpty() ? null : need(store, List.of(forName), err);
                    if (!forName.isEmpty() && a == null) return 1;
                    String back = takeBackOnNode(store, Path.of(words.get(0)));   // set aside on another machine's disk: taken back there
                    if (back != null) { out.println(back); return 0; }
                    return take(store, Path.of(words.get(0)), a, out);
                }
                case "usage" -> { return usage(store, words, out, err); }
                case "caps" -> { return caps(store, words, out, err); }
                default -> { err.print(USAGE); return 2; }
            }
        } catch (IOException e) { err.println(e.getMessage()); return 1; }
    }

    /**
     * A library taken back from where it was set aside on another machine's disk (ROB 4): that machine's node takes it back. What the node
     * said; null when the folder is not one set aside on another machine (it is taken in here).
     */
    static String takeBackOnNode(AccountStore store, Path given) throws IOException {
        Path folder = given.toAbsolutePath().normalize();
        Path set = folder.getFileName() != null && folder.getFileName().toString().equals(LibraryPlaces.LIBRARY) ? folder.getParent() : folder;
        AccountStore.Aside a = set == null ? null : store.asideAt(set.toString());
        if (a == null) return null;
        String node = store.asideNode(a.path()), here = Node.at(store, AccountService.root());
        if (node.isEmpty() || node.equals(here)) return null;
        AccountStore.NodeRow n = store.nodes().stream().filter(r -> r.id().equals(node)).findFirst().orElse(null);
        AccountStore.Library lib = store.library(a.library());
        if (n == null || lib == null) throw new IOException(Pages.t("The folder at {0} is on a machine of the service that is not written down any more, so nothing was taken in.", given));
        return Nodes.places(store, n, lib, "take", LibraryPlaces.library(Path.of(a.path())), false);
    }

    /** {@code accounts take} of a folder or an archive: on this machine, or, when the service runs no library itself, on a node. */
    static int take(AccountStore store, Path given, AccountStore.Account a, PrintStream out) throws IOException {
        if (Node.at(store, AccountService.root()).isEmpty() && !Nodes.liveIn(store).isEmpty()) {   // the service runs no library itself: a node takes it in on its disk
            out.println(Nodes.takeOnNode(store, given, a));
            return 0;
        }
        saidTaken(store, takeIn(store, given, a, AccountActs.By.cli()), out);
        return 0;
    }

    /** What a library taken in is told as: its name, its keeper, where it came from, its settings, and its own readers. */
    static void saidTaken(AccountStore store, TakenIn in, PrintStream out) throws IOException {
        LibraryPlaces.Taken t = in.taken();
        LibraryArchive.Unpacked packed = in.packed();
        AccountStore.Library l = t.library();
        AccountStore.Account keeper = store.account(l.owner());
        out.println("The library \"" + l.title() + "\" is taken in as " + l.name() + ", kept by " + (keeper == null ? l.owner() : keeper.name()) + ". It keeps its id, its shelves and its jobs.");
        if (packed != null) out.println("  From its archive" + (packed.manifest().path("from").asText("").isEmpty() ? "" : " of " + packed.manifest().path("from").asText()) + ", packed " + packed.manifest().path("packed_at").asText("") + ".");
        else out.println(t.renamed() ? "  Its folder was moved to " + LibraryPlaces.folder(l) + "; nothing is left at " + t.from() + "."
                : "  Its folder was copied to " + LibraryPlaces.folder(l) + " (another disk). The original at " + t.from() + " is left as it was: it is the same library, so serve only one of the two, and remove the original once this one works.");
        if (!t.settings().isEmpty()) out.println("  Its own settings came with it: " + String.join(", ", t.settings()) + ".");
        if (t.readers().isEmpty() && t.keys().isEmpty()) out.println("  It had no readers of its own.");
        else {
            out.println("  Its own readers and the keys of their programs do not open it under the service. They stay in its files, and work again if it is let go (researchzosho accounts release).");
            for (String r : t.readers()) out.println("    reader: " + r);
            for (String k : t.keys()) out.println("    key: " + k);
            out.println("  Let the people in again on the account page of " + (keeper == null ? "its keeper" : keeper.name()) + ", or with: researchzosho accounts member \"" + l.name() + "\" \"<name>\" read|write");
        }
    }

    /** A provider as the accounts list names it: the sign-in button's name for the provider set, else its host. */
    static String providerName(String issuer) {
        String set = Oidc.setting(Oidc.ISSUER);
        if (set != null && set.strip().replaceAll("/+$", "").equals(issuer)) return Oidc.name();
        try { String h = URI.create(issuer).getHost(); return h == null ? issuer : h; } catch (RuntimeException e) { return issuer; }
    }

    /** A library taken in: as the folder's take says, and the archive it was unpacked from, when it came as one. */
    record TakenIn(LibraryPlaces.Taken taken, LibraryArchive.Unpacked packed) { }

    /**
     * A library of this machine's own, or a library's archive (stage 4), taken in for an account (or, for one the service had before,
     * the account that kept it), written to the audit log. An archive is unpacked under the root first and taken in as a folder is.
     */
    static TakenIn takeIn(AccountStore store, Path given, AccountStore.Account a, AccountActs.By by) throws IOException { return takeIn(store, given, a, by, AccountService.root()); }

    /** The same, under the root {@code root} (a node's own, for a library sent to it). */
    static TakenIn takeIn(AccountStore store, Path given, AccountStore.Account a, AccountActs.By by, Path root) throws IOException {
        LibraryArchive.Unpacked packed = null;
        Path folder = given;
        if (Files.isRegularFile(given)) {
            Path into = root.resolve("taking").resolve(AccountStore.id("t_"));
            packed = LibraryArchive.unpack(given, into, true);   // only what an export carries (SEC 9)
            folder = into;
        }
        LibraryPlaces.Taken t;
        try { t = new LibraryPlaces(store).take(folder, a, root, packed == null ? null : packed.settings()); }
        finally { if (packed != null) { LibraryPlaces.remove(packed.folder()); try { Files.deleteIfExists(packed.folder().getParent()); } catch (IOException others) { /* another is being taken */ } } }
        AccountActs.audit(store, by, "library_taken_in", t.library().owner(), t.library().id(), packed == null ? null : AccountActs.detail().put("archive_from", packed.manifest().path("from").asText("")));
        return new TakenIn(t, packed);
    }

    /**
     * A library's folder moved, removed, let go, or brought back from its copy: the library is taken out of service first, so the
     * service's node stops it and gives its lease back, and nothing runs it while its folder changes. It is back in service after a
     * move or a restore; a library removed or let go is not.
     */
    static int places(AccountStore store, AccountStore.Library l, String op, Path to, boolean now, PrintStream out) throws IOException {
        // the machine that holds the folder changes it (0.8.0 review, ROB 4): a library written down for another node is changed by that
        // node, through its own call; one brought from its copy under a root of another machine, by the node whose root that is
        String home = store.libraryHome(l.id()), here = Node.at(store, AccountService.root());
        if (op.equals("restore") && to != null) {   // onto a machine named by its root, or by its id when several have the same root path
            AccountStore.NodeRow named = to.getNameCount() == 1 && !to.isAbsolute() ? Nodes.named(store, to.toString()) : null;
            if (named != null) to = Path.of(named.root());
            AccountStore.NodeRow onto = named != null ? named : Nodes.rootOf(store, to.toAbsolutePath().normalize());
            if (onto != null && !onto.id().equals(here)) { out.println(Nodes.places(store, onto, l, op, to, now)); return 0; }
        }
        if (!home.isEmpty() && !home.equals(here)) {
            AccountStore.NodeRow holder = store.nodes().stream().filter(n -> n.id().equals(home)).findFirst().orElse(null);
            if (holder == null) throw new IOException(Pages.t("The library {0} is kept on a machine of the service that is not written down any more, so nothing was changed.", l.title()));
            out.println(Nodes.places(store, holder, l, op, to, now));
            return 0;
        }
        // a folder this machine cannot see, and no node holds: nothing is changed, and it is never marked removed here
        if (!op.equals("restore") && !Files.isDirectory(LibraryPlaces.folder(l), LinkOption.NOFOLLOW_LINKS))
            throw new IOException(Pages.t("The folder of the library {0} ({1}) is not on this machine, and no machine of the service is written down as holding it, so nothing was changed.", l.title(), LibraryPlaces.folder(l)));
        return placesHere(store, l, op, to, now, out, here);
    }

    /** The same, on the machine whose disk holds the library's folder: {@code node} is its node ("" for a machine that runs none). */
    static int placesHere(AccountStore store, AccountStore.Library l, String op, Path to, boolean now, PrintStream out, String node) throws IOException {
        LibraryPlaces places = new LibraryPlaces(store, node);
        String holder = store.libraryHome(l.id());
        boolean fetched = op.equals("restore") && to != null && !holder.isEmpty() && !holder.equals(node) && LibraryPlaces.copies() != null;   // its copy comes from the machine that holds it
        if (op.equals("restore") && !fetched && LibraryPlaces.lastCopy(l.id()) == null) throw new IOException("The library " + l.title() + " has no copy" + (LibraryPlaces.copies() == null ? ": no folder for copies is set (accounts_copies)." : " in " + LibraryPlaces.copies() + "."));
        places.outOfService(l, switch (op) { case "delete" -> "removed"; case "release" -> "released"; default -> "moving"; });
        // and no pass of its copy goes on while its folder and its copy change: one under way (its node's, as it went to sleep) ends
        // first, and none begins (ROB 7)
        if (fetched) {   // the copy from the machine that holds the library, which holds it while it gives it: fetched before this machine holds it
            try { Nodes.fetchCopy(store, holder, l); }
            catch (IOException e) { store.libraryState(l.id(), "active"); throw e; }
        }
        Closeable held;
        try { held = LibraryPlaces.holdCopy(l.id()); }
        catch (IOException e) { store.libraryState(l.id(), "active"); throw e; }
        try (Closeable h = held) { return placesHeld(store, l, op, to, now, out, node, places); }
    }

    private static int placesHeld(AccountStore store, AccountStore.Library l, String op, Path to, boolean now, PrintStream out, String node, LibraryPlaces places) throws IOException {
        long t0 = System.currentTimeMillis();
        switch (op) {
            case "move" -> {
                try {
                    LibraryPlaces.Copy c = places.bringUp(l, store.placement(l.id()).epoch(), true);   // before a move, with its index: nothing runs it
                    Path at = places.moveTo(l, to);
                    if (!node.isEmpty()) store.libraryHome(l.id(), node);
                    // its account was removed while it moved: it stays removed, and the folder it was moved to is set aside to go (round F)
                    AccountStore.Account keeper = store.account(l.owner());
                    if (keeper == null || keeper.state().equals("removed") || store.library(l.id()).state().equals("removed")) {
                        store.libraryState(l.id(), "removed");
                        places.aside(store.library(l.id()), at, "removed");
                        for (AccountStore.Aside a : store.asides(l.id())) store.asideDueNow(a.path());
                        out.println("The library \"" + l.title() + "\" was moved, but its account was removed meanwhile: it stays removed, and its folder goes at the next sweep.");
                        return 0;
                    }
                    store.libraryState(l.id(), "active");
                    out.println("The library \"" + l.title() + "\" is at " + at + " now (" + (System.currentTimeMillis() - t0) / 1000.0 + " s), and served from there from its next request.");
                    out.println("  Its old folder is set aside" + aside(store, l) + (c == null ? "." : "; its copy was brought up to date first."));
                } catch (IOException e) { store.libraryState(l.id(), "active"); throw e; }
            }
            case "restore" -> {
                try {
                    String was = store.libraryHome(l.id());
                    out.println(to == null ? places.bring(l) : places.bringTo(l, LibraryPlaces.newFolder(to.toAbsolutePath().normalize(), l.name())));
                    if (!node.isEmpty()) store.libraryHome(l.id(), node);   // its folder is this machine's from now on
                    if (!was.isEmpty() && !was.equals(node)) {   // brought onto another machine: the one it was on sets its folder there aside
                        String left = Nodes.leftBehind(store, was, l);
                        if (!left.isBlank()) out.println("  " + left);
                    }
                    store.libraryState(l.id(), "active");
                    out.println("  It is served from its next request. Its page says which copy it came from.");
                }
                catch (IOException e) { store.libraryState(l.id(), "active"); throw e; }
            }
            case "delete" -> {
                if (now) {
                    LibraryPlaces.remove(LibraryPlaces.folder(l));
                    Path c = LibraryPlaces.copy(l.id());
                    if (c != null) LibraryPlaces.remove(c);
                    for (AccountStore.Aside a : store.asides(l.id())) {   // what this machine set aside goes now; another machine's at its own next sweep
                        if (store.asideNode(a.path()).equals(node)) { LibraryPlaces.remove(Path.of(a.path())); store.asideGone(a.path()); }
                        else store.asideDueNow(a.path());
                    }
                    out.println("The library \"" + l.title() + "\" is removed, with " + (c == null ? "everything set aside for it." : "its copy and everything set aside for it."));
                } else {
                    Path at = places.aside(l, LibraryPlaces.folder(l), "removed");
                    places.copyAside(l, "removed");
                    out.println("The library \"" + l.title() + "\" is out of service. Its folder is set aside at " + at + ", and it is removed with its copy on " + day(System.currentTimeMillis() + LibraryPlaces.GRACE_MS) + ".");
                    out.println("  Until then it comes back with: researchzosho accounts take " + LibraryPlaces.library(at));
                }
            }
            case "release" -> {
                Path at = places.release(l, to);
                out.println("The library \"" + l.title() + "\" is released: it is an ordinary library at " + at + ", served as a library of this machine's own is (with accounts off, or RESEARCHZOSHO_LIBRARY=" + at + ").");
                out.println("  Its own readers and their keys work there again; the people of the service do not. What else was in its folder here, and its copy, are removed on " + day(System.currentTimeMillis() + LibraryPlaces.GRACE_MS) + ".");
            }
            default -> throw new IOException("unknown");
        }
        return 0;
    }

    private static String aside(AccountStore store, AccountStore.Library l) throws IOException {
        List<AccountStore.Aside> all = store.asides(l.id());
        return all.isEmpty() ? "" : " at " + all.get(all.size() - 1).path() + " until " + day(all.get(all.size() - 1).until());
    }

    /** "copied 17 November 2026, 13:40  " when the library has a copy. */
    private static String copyNote(AccountStore.Library l) { try { LibraryPlaces.Copy c = LibraryPlaces.lastCopy(l.id()); return c == null ? "" : "(copy of " + c.when() + ")  "; } catch (IOException e) { return ""; } }

    private static String day(long at) { return Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalDate().toString(); }

    /**
     * {@code settings} on a machine that serves accounts and has no library of its own: the settings file is read and written as the
     * library's command reads and writes it, in the same sentences, and no library is made by asking.
     */
    public static int settings(String[] args, PrintStream out, PrintStream err) throws IOException {
        String op = args.length > 2 ? args[2].strip().toLowerCase() : "list";
        if (op.equals("list")) {
            out.println("Settings of this service (" + Config.userConfigPath() + "). A key is shown only as set or not.");
            for (JsonNode s : Settings.list(Statement.M)) {
                String v = s.path("value").asText("");
                out.println("  " + s.path("name").asText() + (v.isEmpty() ? "  (not set)" : "  = " + v));
                out.println("      " + s.path("what").asText());
            }
            out.println("researchzosho settings set <name> <value> changes one, settings set <name> --file <path> reads it from a file (for a key); unset <name> removes it.");
            return 0;
        }
        if (!op.equals("set") && !op.equals("unset")) { err.println("usage: researchzosho settings [list | set <name> <value> | set <name> --file <path> | unset <name>]"); return 2; }
        if (args.length < 4 || args[3].startsWith("--")) { err.println("researchzosho settings " + op + " takes the setting's name first.\n" + (op.equals("set") ? LibrarianCli.SET_USAGE : "usage: researchzosho settings unset <name>") + "\nNothing was changed."); return 2; }
        if (op.equals("unset") && args.length > 4) { err.println("researchzosho settings unset takes the setting's name alone.\nusage: researchzosho settings unset <name>\nNothing was changed."); return 2; }
        CommandValue.Given given = op.equals("set") ? CommandValue.rest(args, 4, "researchzosho settings set") : CommandValue.Given.typed("", 0);   // typed, or read from a file (0.8.4)
        if (!given.ok()) { err.println(given.refused() + (given.usage() ? "\n" + LibrarianCli.SET_USAGE + "\nNothing was changed." : "")); return given.usage() ? 2 : 1; }
        Settings.Setting s = Settings.named(args[3]);
        if (s == null) { err.println("No setting is named " + args[3] + ". researchzosho settings lists them all."); return 1; }
        String value = given.value();
        if (CommandValue.fileForm(value)) { err.println(CommandValue.onlyOnTheCommandLine("researchzosho settings set " + s.shortName() + " --file <path>")); return 1; }
        String why = Settings.refuse(s, value);
        if (!why.isEmpty()) { err.println(why); return 1; }
        Settings.set(s, value);
        String kept = Config.stored(s.key());
        out.println((value.isBlank() ? s.shortName() + " is unset." : s.shortName() + " is set." + (kept == null || kept.isBlank() ? "" : " (" + Settings.shown(s, kept) + ")"))
                + (given.file().isEmpty() ? "" : " The value was read from " + given.file() + "."));
        return 0;
    }

    /** {@code accounts audit}: what was done, the latest first, over {@code days} days: everything, or one account's. */
    static int audit(AccountStore store, List<String> words, int days, PrintStream out, PrintStream err) throws IOException {
        AccountStore.Account a = null;
        if (!words.isEmpty()) { a = need(store, words, err); if (a == null) return 1; }
        List<AccountStore.Audit> all = store.audits(a == null ? null : a.id(), System.currentTimeMillis() - Math.max(1, days) * 86_400_000L, 1_000);
        if (all.isEmpty()) { out.println("Nothing was written down in the last " + days + " days" + (a == null ? "." : " for " + a.name() + ".")); return 0; }
        Map<String, String> names = new HashMap<>();
        for (AccountStore.Audit e : all) {
            String who = e.actor().equals("the command line") || e.actor().equals("the service") ? e.actor() : name(store, names, e.actor());
            AccountStore.Library l = e.library().isEmpty() ? null : store.library(e.library());
            out.println(LocalDateTime.ofInstant(Instant.ofEpochMilli(e.at()), ZoneId.systemDefault()).withNano(0).toString().replace('T', ' ') + "  " + e.act() + "  " + (e.subject().isEmpty() ? "" : name(store, names, e.subject()))
                    + (l == null ? "" : "  in " + l.title()) + (AccountPages.detailSaid(store, e.detail()).isEmpty() ? "" : ", " + AccountPages.detailSaid(store, e.detail()))
                    + "  by " + (who.isEmpty() ? "an unknown name" : who) + " (" + e.via() + (e.address().isEmpty() ? "" : ", " + e.address()) + ")");
        }
        return 0;
    }

    private static String name(AccountStore store, Map<String, String> names, String id) {
        if (id == null || id.isEmpty() || id.equals("*")) return id == null || id.isEmpty() ? "" : "the service's caps";
        return names.computeIfAbsent(id, k -> { try { AccountStore.Account a = store.account(k); return a == null ? k : a.name(); } catch (IOException e) { return k; } });
    }

    /** {@code accounts usage}: this month, every account with anything spent, or one account in full with its caps. */
    static int usage(AccountStore store, List<String> words, PrintStream out, PrintStream err) throws IOException {
        String month = AccountStore.month(System.currentTimeMillis());
        if (!words.isEmpty()) {
            AccountStore.Account a = need(store, words, err);
            if (a == null) return 1;
            out.println(a.name() + ", " + month + ":");
            for (String line : monthSaid(store, a.id(), month)) out.println("    " + line);
            List<AccountStore.Use> uses = store.uses(a.id(), firstOfMonth());
            if (!uses.isEmpty()) out.println("  Uses this month, the latest first:");
            for (AccountStore.Use u : uses.subList(0, Math.min(15, uses.size()))) out.println("    " + useSaid(u));
            return 0;
        }
        List<String> who = store.accountsWithUse(month);
        Map<String, Integer> asked = new HashMap<>();
        for (AccountStore.Account a : store.accounts()) { int n = store.uses(a.id(), firstOfMonth()).size(); if (n > 0) asked.put(a.id(), n); }
        if (who.isEmpty() && asked.isEmpty()) { out.println("Nothing was spent and nothing asked for this month (" + month + ")."); return 0; }
        out.println("This month (" + month + "), by account:");
        for (AccountStore.Account a : store.accounts()) {
            if (!who.contains(a.id()) && !asked.containsKey(a.id())) continue;
            out.println("  " + a.name() + ": " + String.join("; ", monthSaid(store, a.id(), month)));
        }
        return 0;
    }

    /** One account's month, a line a kind, each against its cap. */
    static List<String> monthSaid(AccountStore store, String account, String month) throws IOException {
        Map<String, Long> caps = Caps.of(store, account);
        long operator = 0, own = 0, searches = 0, fetches = 0, estimated = 0;
        for (AccountStore.MonthTotal t : store.month(account, month)) {
            if (t.payer().equals("own")) own += t.micros(); else operator += t.micros();
            if (t.kind().equals("search") && t.payer().equals("operator")) searches += t.quantity();   // the searches the cap counts, as the account page
            if (t.kind().equals("fetch")) fetches += t.quantity();
            estimated += t.estimated();
        }
        int going = (int) store.openUses(account).stream().filter(u -> CapsSpending.JOBS.contains(u.kind())).count();
        List<String> out = new ArrayList<>();
        out.add(Caps.dollars(operator) + " of " + Caps.said(Caps.MONTH_DOLLARS, caps.get(Caps.MONTH_DOLLARS)) + " on the service's model and searches" + (own > 0 ? ", and " + Caps.dollars(own) + " on the account's own keys" : ""));
        out.add(searches + " searches of " + Caps.said(Caps.SEARCHES, caps.get(Caps.SEARCHES)));
        out.add(fetches + " pages fetched of " + Caps.said(Caps.FETCHES, caps.get(Caps.FETCHES)));
        out.add(Caps.size((store.storedBytes(account) + 999_999) / 1_000_000) + " stored of " + (caps.get(Caps.STORAGE_MB) < 0 ? "no cap" : Caps.size(caps.get(Caps.STORAGE_MB))));
        out.add(going + " job" + (going == 1 ? "" : "s") + " going of " + Caps.said(Caps.JOBS_AT_ONCE, caps.get(Caps.JOBS_AT_ONCE)) + " at once; a job at most " + Caps.said(Caps.JOB_MINUTES, caps.get(Caps.JOB_MINUTES)) + " minutes and " + Caps.said(Caps.JOB_DOLLARS, caps.get(Caps.JOB_DOLLARS)));
        if (estimated > 0) out.add(estimated + " of the month's counts are estimated from sizes, because the server did not report them");
        return out;
    }

    static String useSaid(AccountStore.Use u) {
        String when = Instant.ofEpochMilli(u.openedAt()).atZone(ZoneId.systemDefault()).toLocalDateTime().withNano(0).toString().replace('T', ' ');
        String what = u.state().equals("refused") ? "refused: " + u.said() : u.state().equals("open") ? "going" + (u.estimateMicros() > 0 ? ", estimated " + Caps.dollars(u.estimateMicros()) : "")
                : u.state() + ", " + Caps.dollars(u.actualMicros()) + (u.estimateMicros() > 0 ? " (estimated " + Caps.dollars(u.estimateMicros()) + ")" : "");
        return when + "  " + u.kind() + (u.entry().isEmpty() ? "" : " on " + u.entry()) + "  " + what;
    }

    private static long firstOfMonth() { return LocalDate.now(ZoneId.systemDefault()).withDayOfMonth(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(); }

    /** {@code accounts caps}: the service's caps, or an account's; with a cap and a value, that cap set (or put back to the service's with "default"). */
    static int caps(AccountStore store, List<String> words, PrintStream out, PrintStream err) throws IOException {
        List<String> w = new ArrayList<>(words);
        AccountStore.Account a = null;
        int capAt = -1;
        for (int i = 0; i < w.size(); i++) if (Caps.known(w.get(i))) { capAt = i; break; }
        if ((capAt < 0 && !w.isEmpty()) || capAt > 0) { a = need(store, capAt < 0 ? w : w.subList(0, capAt), err); if (a == null) return 1; }
        String scope = a == null ? "*" : a.id();
        if (capAt >= 0) {
            if (capAt + 2 != w.size()) { err.println("Name one cap and its value: " + String.join(", ", Caps.DEFAULTS.keySet()) + "; a value is a number, dollars as 5.00, off for no cap, or default."); return 2; }
            String name = w.get(capAt), value = w.get(capAt + 1);
            if (value.equalsIgnoreCase("default")) { store.unsetCap(scope, name); AccountActs.audit(store, AccountActs.By.cli(), "cap_changed", scope, "", AccountActs.detail().put("name", name).put("value", "default")); out.println((a == null ? "The service's " + name + " is the default again: " + Caps.said(name, Caps.DEFAULTS.get(name)) : a.name() + "'s " + name + " is the service's again") + "."); return 0; }
            long v;
            try { v = Caps.parse(name, value); } catch (NumberFormatException e) { err.println(value + " is not a value for " + name + ": a number, dollars as 5.00, or off."); return 2; }
            store.setCap(scope, name, v);
            AccountActs.audit(store, AccountActs.By.cli(), "cap_changed", scope, "", AccountActs.detail().put("name", name).put("value", v));
            out.println((a == null ? "The service's " : a.name() + "'s ") + name + " is now " + Caps.said(name, v) + ".");
            return 0;
        }
        out.println(a == null ? "The service's caps (each account has these unless it has its own):" : a.name() + "'s caps:");
        Map<String, Long> now = Caps.of(store, a == null ? null : a.id());
        for (Map.Entry<String, Long> e : now.entrySet()) {
            String from = a != null && store.cap(a.id(), e.getKey()) != null ? "its own" : store.cap("*", e.getKey()) != null ? "the service's" : "the default";
            out.println("    " + e.getKey() + "  " + Caps.said(e.getKey(), e.getValue()) + "  (" + from + ")");
        }
        return 0;
    }

    private static AccountStore.Account need(AccountStore store, List<String> words, PrintStream err) throws IOException {
        if (words.isEmpty()) { err.print(USAGE); return null; }
        String name = String.join(" ", words);
        AccountStore.Account a = store.accountByLogin(name);
        if (a == null && name.contains("@")) a = store.accountByMail(name);
        if (a == null) err.println("No account is named " + name + ". researchzosho accounts lists them.");
        return a;
    }

    /**
     * {@code serve} with the setting accounts on: the account service in place of one library's daemon; blocks until it is stopped. It
     * says where it is, how accounts are made and signed in, and that the library of this computer's own is left as it is.
     */
    /** The system this runs on, by its name; a test names another. */
    static volatile Supplier<String> OS_NAME = () -> System.getProperty("os.name", "");

    /**
     * Why the accounts mode does not start on this system, or null on Linux. Its libraries run in containers or as processes of their own
     * under a user of their own, it proves a library's socket by the kernel's word for its peer (SO_PEERCRED) and reads processes from /proc:
     * Linux only in 0.8.0. On macOS a socket's path is also cut at 104 bytes, which the temporary folder alone nearly fills.
     */
    static String notHere(String osName) {
        return osName != null && osName.toLowerCase(Locale.ROOT).startsWith("linux") ? null : "Several accounts run on Linux only in this release.";
    }

    public static int serve(String[] args, String drive, PrintStream out, PrintStream err) throws IOException {
        String host = "127.0.0.1"; int port = LibrarianDaemon.DEFAULT_PORT; Path log = null;
        try {
            for (int i = 2; i < args.length; i++) {
                switch (args[i]) {
                    case "--host" -> host = LibrarianCli.flagValue(args, i++);
                    case "--port" -> port = LibrarianCli.flagInt(args, i++);
                    case "--log" -> log = Path.of(LibrarianCli.flagValue(args, i++));
                    case "--crew-hour" -> i++;   // the libraries' nights are the scheduler's, each at its own minute (accounts_nights)
                    case "--no-crews" -> { }
                    case "--door" -> { err.println("With accounts on there is no shared door: people reach their libraries through the account service."); return 2; }
                    default -> { err.println("unknown option " + args[i]); return 2; }
                }
            }
        } catch (IllegalArgumentException e) { err.println("usage: researchzosho serve [--host H] [--port N] [--log FILE] — " + e.getMessage()); return 2; }
        if (log != null) {
            Files.createDirectories(log.getParent());
            Service.rotate(log, 20L * 1024 * 1024);
            PrintStream ps = new PrintStream(Files.newOutputStream(log, StandardOpenOption.CREATE, StandardOpenOption.APPEND), true, StandardCharsets.UTF_8);
            System.setOut(ps); System.setErr(ps); out = ps;
        } else if (Logs.wantsStdout(false)) { Logs.toStdout(); out = System.out; }
        String elsewhere = notHere(OS_NAME.get());
        if (elsewhere != null) { (log != null ? out : err).println(elsewhere); return 1; }   // in the service's log when it writes one: a service started at logon has no terminal
        AccountStore store = AccountService.openStore();
        // the node part runs inside the service on one machine (unless accounts_node is off): it holds each library's lease, puts the idle
        // ones to sleep and starts them again; nodes of their own (researchzosho node) on other machines are reached the same way
        String nodeId = Node.idAt(AccountService.root());
        Node[] node = new Node[1];
        Nodes[] nodes = new Nodes[1];
        AccountService svc = AccountService.start(store, host, port, (issuer, self) -> {
            // the node of the service's own process reaches the service on its own loopback, so a library's calls to it come from this machine
            node[0] = Node.ownOn() ? new Node(store, issuer, LibraryRunner.Local.of(store, issuer, self), nodeId, AccountService.root()) : null;
            return nodes[0] = new Nodes(store, issuer, node[0]);
        });
        NodeServer ns = node[0] == null ? null : NodeServer.start(node[0], store, svc.issuer, ChildLibraries.host(), Node.port());
        if (node[0] != null) node[0].start();
        Scheduler scheduler = new Scheduler(store, nodes[0], nodeId + "/" + ProcessHandle.current().pid(), ZonedDateTime::now);
        scheduler.start();
        Files.createDirectories(addressFile().getParent());
        Files.writeString(addressFile(), svc.address() + "\n", StandardCharsets.UTF_8);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> { scheduler.stop(); svc.stop(); if (ns != null) ns.stop(); store.close(); }, "accounts-stop"));   // the libraries are asked to stop, so each lets its lease go
        for (String line : started(svc, drive)) out.println(line);
        try { Thread.currentThread().join(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        return 0;
    }

    /**
     * {@code researchzosho node}: a node of the service on this machine (0.8.0). It reads the service's store (accounts_store), keeps its
     * libraries under its own root (accounts_root), starts and stops them, holds their leases, and answers the service's calls on its
     * port; its libraries reach the service at accounts_service. Blocks until it is stopped, which stops its libraries.
     */
    public static int node(String[] args, PrintStream out, PrintStream err) throws IOException {
        String elsewhere = notHere(OS_NAME.get());
        if (elsewhere != null) { err.println(elsewhere); return 1; }   // a machine of the service runs its libraries as the service does: Linux only
        String host = ChildLibraries.host(); int port = Node.port();
        try {
            for (int i = 2; i < args.length; i++) {
                switch (args[i]) {
                    case "--host" -> host = LibrarianCli.flagValue(args, i++);
                    case "--port" -> port = LibrarianCli.flagInt(args, i++);
                    default -> { err.println("unknown option " + args[i]); return 2; }
                }
            }
        } catch (IllegalArgumentException e) { err.println("usage: researchzosho node [--host H] [--port N] — " + e.getMessage()); return 2; }
        String storeAt = Config.get(AccountService.STORE), service = Config.get(AccountService.SERVICE);
        if (storeAt == null || storeAt.isBlank()) { err.println("A node reads the service's store: set accounts_store to the address of the service's PostgreSQL (or, on the service's own machine, its accounts.db)."); return 2; }
        if (service == null || service.isBlank()) { err.println("A node's libraries reach the service at an address: set accounts_service (for example http://10.0.0.2:4649)."); return 2; }
        if (Logs.wantsStdout(false)) Logs.toStdout();
        AccountStore store = AccountService.openStore();
        Path root = AccountService.root();
        String issuer = AccountService.issuer(service);
        try { ServiceSettings.read(store); } catch (IOException e) { err.println("The service's settings could not be read from its store: " + e.getMessage()); }   // the prices and the plug-in it counts by (BILL 10)
        ServiceSettings.reading(store);
        ModelsAwake.watchContainers();   // the helpers' containers of this machine stop when idle (round C2)
        Node n = new Node(store, issuer, LibraryRunner.Local.of(store, issuer), Node.idAt(root), root);
        NodeServer ns = NodeServer.start(n, store, issuer, host, port);
        n.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> { n.stopAll(); ns.stop(); store.close(); }, "node-stop"));   // its libraries are asked to stop, so each lets its lease go
        out.println("The node " + n.id() + " answers the service's calls at " + ns.url() + "; its libraries are kept under " + root + " and reach the service at " + issuer + ".");
        out.println("  This address and the service's belong on a private network between the service and its machines.");
        try { Thread.currentThread().join(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        return 0;
    }

    /** What serve says when the account service has started, a sentence a line. */
    static List<String> started(AccountService svc, String drive) throws IOException {
        List<String> out = new ArrayList<>();
        int accounts = svc.store.accounts().size(), libraries = (int) svc.store.libraries().stream().filter(AccountStore.Library::active).count();
        out.add("The account service is at " + svc.address() + ". People sign in there, and each account has a library of its own.");
        out.add("  " + (accounts == 0 ? "No account yet. Make the first one with: researchzosho accounts add \"<your name>\" --admin" : accounts + (accounts == 1 ? " account, " : " accounts, ") + libraries + (libraries == 1 ? " library" : " libraries") + ", kept under " + AccountService.root() + "."));
        String signup = AccountService.signup();
        out.add("  New accounts: " + (signup.equals("anyone") ? "anyone who signs in through the identity provider gets one." : signup.equals("domain") ? "people of the allowed mail domains get one when they sign in through the identity provider." : "you make them (researchzosho accounts add \"<name>\" prints the link to send)."));
        out.add("  Sign-in: " + (Oidc.configured() ? "through " + Oidc.name() + ", or with a name and a password." : "with a name and a password. No identity provider is set (" + Oidc.ISSUER + " names one)."));
        long sleep = Node.sleepAfterMs();
        out.add("  Libraries: " + (sleep <= 0 ? "none is ever stopped for having nothing to do (accounts_sleep_after is off)." : "one with nothing to do for " + (sleep / 60_000 == 1 ? "a minute" : sleep / 60_000 + " minutes") + " is stopped, and the next request starts it again.")
                + (Scheduler.nightsOn() ? " Their nights begin from 01:00, each at a minute of its own, " + Scheduler.atOnce() + " at once." : " They have no nights (accounts_nights is off)."));
        Path copies = LibraryPlaces.copies();
        out.add(copies == null ? "  missing: No folder for the libraries' copies is set, so a disk lost is its libraries lost. Name one, best on another disk: researchzosho settings set accounts_copies <folder>"
                : "  Copies: in " + copies + ", brought up to date when a library goes to sleep, after each job, and every " + LibraryPlaces.copyAfterMs() / 60_000 + " minutes when something changed.");
        LibraryStore own = LibraryStore.open();
        if (Files.isDirectory(own.root())) out.add("  Your own library at " + own.root() + " is not served while accounts are on. Nothing in it is changed, and it is safe. To go back to it, turn accounts off (researchzosho settings set accounts off) and start the service again.");
        if (!Crews.driveAnswers(drive)) out.add("  missing: No model answers at " + drive + ". Every library here uses the model this service is set to: researchzosho models add <name> <host> <model id>, or RESEARCHZOSHO_DRIVE set to a server that answers.");
        String half = Oidc.halfConfigured();
        if (half != null) out.add("  missing: " + half);
        String set = Config.get(AccountService.ADDRESS);
        if ((set == null || set.isBlank()) && !svc.reachedOnlyHere) out.add("  missing: The address people reach this service at is not set, so the join links are written with " + svc.url() + ". Name it: researchzosho settings set accounts_address https://<your address>");
        return out;
    }
}
