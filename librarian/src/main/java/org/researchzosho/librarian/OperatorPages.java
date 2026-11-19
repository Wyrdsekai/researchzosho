package org.researchzosho.librarian;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.researchzosho.librarian.Pages.esc;
import static org.researchzosho.librarian.Pages.t;
import static org.researchzosho.librarian.Pages.tc;

/**
 * The operator's page, completed (0.8.0, stage 4): the accounts (made, with the join link shown once; suspended, let back, deleted
 * after the grace or at once, kept), the libraries (where each runs, asleep or awake, its size, the age of its copy; made for an
 * account, moved, handed over, let go, deleted, brought back from its copy, taken in), the machines (each node's address, version,
 * last word and libraries), the caps, and the audit log filtered by account, act and days. Each act on an account or a library is
 * asked on a page of its own first, which says what it will do, and is written to the audit log; the same acts as the command line.
 */
final class OperatorPages {
    private OperatorPages() { }

    /** What a change on the operator's page comes to: a note and the section to go back to, or a page of its own (the question, or a link shown once). */
    record Result(String note, String anchor, String page) {
        static Result note(String note, String anchor) { return new Result(note, anchor, ""); }
        static Result page(String page) { return new Result("", "", page); }
    }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }

    /** A button that asks for an act: the act's question comes back on a page of its own. */
    static String ask(String op, Map<String, String> fields, String label) {
        StringBuilder b = new StringBuilder("<form class=\"inline\" method=\"post\" action=\"").append(AccountPages.OPERATOR).append("\"><input type=\"hidden\" name=\"op\" value=\"").append(op).append("\">");
        fields.forEach((k, v) -> b.append("<input type=\"hidden\" name=\"").append(k).append("\" value=\"").append(esc(v)).append("\">"));
        return b.append("<button class=\"quiet\">").append(label).append("</button></form>").toString();
    }

    /** The page that asks: what the act will do, what it needs besides, the button that does it, and the way back. */
    static String confirm(AccountService svc, AccountService.Who who, String title, String said, String op, Map<String, String> fields, String inputs, String button, String anchor) {
        StringBuilder b = new StringBuilder("<p>").append(said).append("</p><form method=\"post\" action=\"").append(AccountPages.OPERATOR).append("\" class=\"stack\"><input type=\"hidden\" name=\"op\" value=\"").append(op).append("\"><input type=\"hidden\" name=\"confirm\" value=\"yes\">");
        fields.forEach((k, v) -> b.append("<input type=\"hidden\" name=\"").append(k).append("\" value=\"").append(esc(v)).append("\">"));
        b.append(inputs).append("<p><button class=\"danger\">").append(button).append("</button> <a href=\"").append(AccountPages.OPERATOR).append("#").append(anchor).append("\">").append(t("Back, and do nothing")).append("</a></p></form>");
        return AccountPages.shell(svc, who, title, b.toString());
    }

    static String day(long at) { return Chrome.now().dayTime(Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalDate().toString()); }

    /** How long ago, as a person reads it: seconds, minutes, hours, days. */
    static String ago(long ms) {
        long s = Math.max(0, ms / 1000);
        if (s < 120) return Chrome.now().n(s, "{0} second ago", "{0} seconds ago");
        if (s < 7200) return Chrome.now().n(s / 60, "{0} minute ago", "{0} minutes ago");
        if (s < 172_800) return Chrome.now().n(s / 3600, "{0} hour ago", "{0} hours ago");
        return Chrome.now().n(s / 86_400, "{0} day ago", "{0} days ago");
    }

    // ---- the sections ----

    /** An account's state, under its name in the accounts' table: runs the service, suspended, to be removed on a day, no password yet. */
    static String accountState(AccountStore store, AccountStore.Account a) throws IOException {
        String state = switch (a.state()) {
            case "suspended" -> t("suspended");
            case "deleting" -> t("to be removed on {0}", day(store.removeAt(a.id())));
            default -> a.hasPassword() ? "" : t("has not chosen a password yet");
        };
        return (a.admin() ? "<br><span class=\"k\">" + t("runs the service") + "</span>" : "") + (state.isEmpty() ? "" : "<br><span class=\"k\">" + state + "</span>");
    }

    /** The acts that fit an account's state, in the last cell of its row; none on one's own account, which the command line suspends or deletes. */
    static String accountButtons(AccountStore.Account a, AccountService.Who who) {
        Map<String, String> id = Map.of("account", a.id());
        if (a.id().equals(who.account().id())) return "<span class=\"k\">" + t("your own account") + "</span>";
        return switch (a.state()) {
            case "active" -> ask("account-suspend", id, t("Suspend")) + " " + ask("account-delete", id, t("Delete")) + " " + ask("account-link", id, t("New join link"));
            case "suspended" -> ask("account-unsuspend", id, t("Let back")) + " " + ask("account-delete", id, t("Delete"));
            case "deleting" -> ask("account-keep", id, tc("Keep it", "a button that keeps an account or a library as it is")) + " " + ask("account-delete", Map.of("account", a.id(), "when", "now"), t("Remove it now"));
            default -> "";
        };
    }

    /** Making an account: a name, a mail address, whether they run the service too; the join link is shown once after. */
    static void makeAccount(StringBuilder b) {
        b.append("<form method=\"post\" action=\"").append(AccountPages.OPERATOR).append("\" class=\"stack\"><input type=\"hidden\" name=\"op\" value=\"account-make\"><label>").append(t("Make an account: the name they sign in with"))
         .append("<br><input name=\"name\" size=\"40\"></label><label>").append(t("Mail address (if they sign in through the identity provider)")).append("<br><input name=\"mail\" size=\"40\"></label><label><input type=\"checkbox\" name=\"admin\" value=\"yes\"> ")
         .append(t("They run this service too")).append("</label><button>").append(t("Make an account")).append("</button></form>");
    }

    /** The libraries in service: where each runs, asleep or awake, its size, its copy's age, and the acts; then those out of service, and taking one in. */
    static void libraries(StringBuilder b, AccountService svc) throws IOException {
        AccountStore store = svc.store;
        b.append("<h2 id=\"libraries\">").append(t("Libraries")).append("</h2>");
        long now = store.clock();
        Map<String, AccountStore.Placement> where = new HashMap<>();
        for (AccountStore.Placement p : store.placements()) where.put(p.id(), p);
        Map<String, String> names = new HashMap<>();
        List<AccountStore.Library> all = store.libraries(), out = new ArrayList<>();
        for (AccountStore.Library l : all) names.computeIfAbsent(l.owner(), k -> { try { AccountStore.Account a = store.account(k); return a == null ? k : a.name(); } catch (IOException e) { return k; } });
        b.append("<table class=\"stack libraries\"><thead><tr><th>").append(t("library")).append("</th><th>").append(t("keeper")).append("</th><th>").append(t("where")).append("</th><th>").append(t("size"))
         .append("</th><th>").append(t("copy")).append("</th><th></th></tr></thead><tbody>\n");
        for (AccountStore.Library l : all) {
            if (!l.active()) { out.add(l); continue; }
            AccountStore.Placement p = where.get(l.id());
            boolean awake = p != null && p.leaseUntil() > now && !p.node().isEmpty();
            long[] size = store.lastStorage(l.id());
            int people = store.members(l.id()).size();
            Map<String, String> id = Map.of("library", l.id());
            b.append("<tr><td data-l=\"").append(esc(t("library"))).append("\"><b>").append(esc(l.title())).append("</b> <span class=\"k\">").append(esc(l.name())).append("</span>")
             .append(people > 0 ? "<br><span class=\"k\">" + Chrome.now().n(people, "one person let in", "{0} people let in") + "</span>" : "")
             .append("</td><td data-l=\"").append(esc(t("keeper"))).append("\">").append(esc(names.getOrDefault(l.owner(), l.owner())))
             .append("</td><td data-l=\"").append(esc(t("where"))).append("\">").append(awake ? t("awake on {0}", esc(p.node())) : t("asleep"))
             .append("</td><td data-l=\"").append(esc(t("size"))).append("\">").append(size == null ? t("not measured yet") : Caps.size((size[1] + 999_999) / 1_000_000))
             .append("</td><td data-l=\"").append(esc(t("copy"))).append("\">").append(p == null || p.copiedAt() <= 0 ? t("no copy") : t("copied {0}", ago(System.currentTimeMillis() - p.copiedAt())))
             .append("</td><td>").append(ask("library-move", id, t("Move"))).append(' ');
            if (people > 0) b.append(ask("library-handover", id, t("Hand over"))).append(' ');
            b.append(ask("library-release", id, t("Release"))).append(' ').append(ask("library-delete", id, t("Delete")));
            if (p != null && p.copiedAt() > 0) b.append(' ').append(ask("library-restore", id, t("Bring back from its copy")));
            b.append("</td></tr>\n");
        }
        b.append("</tbody></table>\n");
        if (!out.isEmpty()) {
            b.append("<h3>").append(t("Out of service")).append("</h3><table class=\"stack\"><tbody>\n");
            for (AccountStore.Library l : out) {
                List<AccountStore.Aside> aside = store.asides(l.id());
                String state = switch (l.state()) { case "removed" -> t("deleted"); case "released" -> t("released"); case "moving" -> t("being moved"); default -> esc(l.state()); };
                b.append("<tr><td><b>").append(esc(l.title())).append("</b> <span class=\"k\">").append(esc(names.getOrDefault(l.owner(), l.owner()))).append("</span></td><td>").append(state)
                 .append(aside.isEmpty() ? "" : " · " + t("kept aside until {0}", day(aside.get(aside.size() - 1).until()))).append("</td><td>");
                if (l.state().equals("removed") && !aside.isEmpty() && (Files.isDirectory(LibraryPlaces.library(Path.of(aside.get(aside.size() - 1).path())))
                        || !store.asideNode(aside.get(aside.size() - 1).path()).isEmpty() && !store.asideNode(aside.get(aside.size() - 1).path()).equals(Node.at(store, AccountService.root()))))   // set aside on another machine's disk: taken back there
                    b.append(ask("library-take", Map.of("path", LibraryPlaces.library(Path.of(aside.get(aside.size() - 1).path())).toString(), "account", l.owner()), t("Take it back")));
                b.append("</td></tr>\n");
            }
            b.append("</tbody></table>\n");
        }
        b.append("<form method=\"post\" action=\"").append(AccountPages.OPERATOR).append("\" class=\"stack\"><input type=\"hidden\" name=\"op\" value=\"library-make\"><label>").append(t("Make a library for"))
         .append("<br>").append(accountSelect(store, "account", null)).append("</label><label>").append(t("Its title")).append("<br><input name=\"title\" size=\"40\" maxlength=\"120\"></label><button>").append(t("Make a library")).append("</button></form>");
        b.append("<form method=\"post\" action=\"").append(AccountPages.OPERATOR).append("\" class=\"stack\"><input type=\"hidden\" name=\"op\" value=\"library-take\"><label>").append(t("Take in a library: its folder, or its archive (.rzlib.zip), on this machine"))
         .append("<br><input name=\"path\" size=\"50\"></label><label>").append(tc("For", "the account a library is taken in for")).append("<br>").append(accountSelect(store, "account", null)).append("</label><button>").append(t("Take it in")).append("</button></form>");
    }

    static String accountSelect(AccountStore store, String name, String chosen) throws IOException {
        StringBuilder b = new StringBuilder("<select name=\"").append(name).append("\">");
        for (AccountStore.Account a : store.accounts()) if (a.active()) b.append("<option value=\"").append(esc(a.id())).append("\"").append(a.id().equals(chosen) ? " selected" : "").append(">").append(esc(a.name())).append("</option>");
        return b.append("</select>").toString();
    }

    /** The machines: each node's address, its folder, its version, its last word, the libraries awake on it and those on its disk. */
    static void nodes(StringBuilder b, AccountService svc) throws IOException {
        AccountStore store = svc.store;
        b.append("<h2 id=\"nodes\">").append(t("Machines")).append("</h2>");
        List<AccountStore.NodeRow> all = store.nodes();
        if (all.isEmpty()) { b.append("<p class=\"k\">").append(t("No machine has written itself down yet.")).append("</p>"); return; }
        long now = store.clock();
        Map<String, Integer> awake = new HashMap<>(), kept = new HashMap<>();
        for (AccountStore.Placement p : store.placements()) if (p.leaseUntil() > now && !p.node().isEmpty()) awake.merge(p.node(), 1, Integer::sum);
        for (AccountStore.Library l : store.libraries()) { if (!l.active()) continue; AccountStore.NodeRow h = Nodes.homeOf(store, all, l); if (h != null) kept.merge(h.id(), 1, Integer::sum); }
        b.append("<table class=\"stack\"><thead><tr><th>").append(t("machine")).append("</th><th>").append(t("version")).append("</th><th>").append(t("last word")).append("</th><th>").append(t("libraries")).append("</th></tr></thead><tbody>\n");
        for (AccountStore.NodeRow n : all) {
            boolean answers = now - n.seenAt() <= Node.LEASE_MS;
            b.append("<tr><td data-l=\"").append(esc(t("machine"))).append("\"><b>").append(esc(n.id())).append("</b><br><span class=\"k\">").append(esc(n.address().isEmpty() ? t("no address") : n.address())).append(" · ").append(esc(n.root())).append("</span>")
             .append("</td><td data-l=\"").append(esc(t("version"))).append("\">").append(esc(n.version()))
             .append("</td><td data-l=\"").append(esc(t("last word"))).append("\">").append(answers ? t("answers ({0})", ago(now - n.seenAt())) : "<span class=\"err\">" + t("silent for {0}", RateLimits.in((now - n.seenAt()) / 1000)) + "</span>")
             .append("</td><td data-l=\"").append(esc(t("libraries"))).append("\">").append(t("{0} awake, {1} on its disk", awake.getOrDefault(n.id(), 0), kept.getOrDefault(n.id(), 0))).append("</td></tr>\n");
        }
        b.append("</tbody></table>\n");
    }

    /** The audit log, filtered by account, act and days, the newest first, a hundred entries a page. */
    static void audit(StringBuilder b, AccountService svc, Map<String, String> q) throws IOException {
        AccountStore store = svc.store;
        String account = q.getOrDefault("audit_account", ""), act = q.getOrDefault("audit_act", "");
        int days = 7; try { days = Math.max(1, Math.min(AccountActs.auditDays(), Integer.parseInt(q.getOrDefault("audit_days", "7")))); } catch (NumberFormatException ignored) { }
        long before = Long.MAX_VALUE; try { if (q.containsKey("audit_before")) before = Long.parseLong(q.get("audit_before")); } catch (NumberFormatException ignored) { }
        b.append("<h2 id=\"audit\">").append(t("The audit log")).append("</h2>");
        b.append("<form method=\"get\" action=\"").append(AccountPages.OPERATOR).append("#audit\" class=\"stack audit-filter\"><label>").append(t("Account")).append("<br><select name=\"audit_account\"><option value=\"\">").append(t("every account")).append("</option>");
        for (AccountStore.Account a : store.accounts()) b.append("<option value=\"").append(esc(a.id())).append("\"").append(a.id().equals(account) ? " selected" : "").append(">").append(esc(a.name())).append("</option>");
        b.append("</select></label><label>").append(t("Act")).append("<br><select name=\"audit_act\"><option value=\"\">").append(t("every act")).append("</option>");
        for (String a : store.auditActs()) b.append("<option value=\"").append(esc(a)).append("\"").append(a.equals(act) ? " selected" : "").append(">").append(AccountPages.actSaid(a)).append("</option>");
        b.append("</select></label><label>").append(t("Days")).append("<br><select name=\"audit_days\">");
        for (int d : new int[]{1, 7, 30, 90, AccountActs.auditDays()}) b.append("<option value=\"").append(d).append("\"").append(d == days ? " selected" : "").append(">").append(d).append("</option>");
        b.append("</select></label><button class=\"quiet\">").append(t("Show")).append("</button></form>");
        List<AccountStore.Audit> rows = store.audits(account, act, System.currentTimeMillis() - days * 86_400_000L, before, 101);
        if (rows.isEmpty()) { b.append("<p class=\"k\">").append(t("Nothing was written down in these days.")).append("</p>"); return; }
        Map<String, String> names = new HashMap<>(), titles = new HashMap<>();
        b.append("<table class=\"stack\"><thead><tr><th>").append(t("when")).append("</th><th>").append(t("what")).append("</th><th>").append(t("by")).append("</th><th>").append(t("from")).append("</th></tr></thead><tbody>\n");
        for (AccountStore.Audit e : rows.subList(0, Math.min(100, rows.size()))) {
            String by = e.actor().equals("the command line") ? t("the command line") : e.actor().equals("the service") ? t("the service itself") : e.actor().isEmpty() ? t("an unknown name") : name(store, names, e.actor());
            String lib = e.library().isEmpty() ? "" : titles.computeIfAbsent(e.library(), k -> { try { AccountStore.Library l = store.library(k); return l == null ? k : l.title(); } catch (IOException x) { return k; } });
            b.append("<tr><td data-l=\"").append(esc(t("when"))).append("\">").append(esc(Chrome.now().dayTime(Instant.ofEpochMilli(e.at()).toString())))
             .append("</td><td data-l=\"").append(esc(t("what"))).append("\">").append(AccountPages.actSaid(e.act())).append(e.subject().isEmpty() || e.subject().equals("*") ? "" : " · " + esc(name(store, names, e.subject())))
             .append(lib.isEmpty() ? "" : " · " + esc(lib)).append(AccountPages.detailSaid(store, e.detail()).isEmpty() ? "" : ", " + esc(AccountPages.detailSaid(store, e.detail())))
             .append("</td><td data-l=\"").append(esc(t("by"))).append("\">").append(esc(by)).append(" <span class=\"k\">").append(esc(e.via())).append("</span>")
             .append("</td><td data-l=\"").append(esc(t("from"))).append("\"><span class=\"nw\">").append(esc(e.address())).append("</span></td></tr>\n");
        }
        b.append("</tbody></table>\n");
        if (rows.size() > 100) b.append("<p><a href=\"").append(AccountPages.OPERATOR).append("?audit_account=").append(enc(account)).append("&audit_act=").append(enc(act)).append("&audit_days=").append(days)
                .append("&audit_before=").append(rows.get(99).at()).append("#audit\">").append(t("Older entries")).append("</a></p>");
    }

    private static String name(AccountStore store, Map<String, String> names, String id) {
        return names.computeIfAbsent(id, k -> { try { AccountStore.Account a = store.account(k); return a == null ? k : a.name(); } catch (IOException e) { return k; } });
    }

    // ---- the acts ----

    /** An act asked from the page: its question on a page of its own until it is confirmed, then done and written down. Null for a change this does not know (the caps'). */
    static Result post(AccountService svc, AccountService.Who who, HttpExchange x, Map<String, String> form) throws IOException {
        AccountStore store = svc.store;
        String op = form.getOrDefault("op", "");
        boolean yes = "yes".equals(form.get("confirm"));
        AccountActs.By by = AccountActs.By.of(who, x);
        AccountStore.Account a = store.account(form.getOrDefault("account", ""));
        AccountStore.Library l = store.library(form.getOrDefault("library", ""));
        PrintStream quiet = new PrintStream(OutputStream.nullOutputStream());
        try {
            switch (op) {
                case "account-make" -> {
                    String name = form.getOrDefault("name", "").strip(), mail = form.getOrDefault("mail", "").strip();
                    boolean admin = "yes".equals(form.get("admin"));
                    if (name.isEmpty()) return Result.note(t("Say the name they sign in with."), "accounts");
                    if (store.accountByLogin(name) != null) return Result.note(t("There is an account named {0} already.", name), "accounts");
                    if (!yes) return Result.page(confirm(svc, who, t("Make an account for {0}?", name), t("An account will be made for {0}, with a library of its own, and you will get a link to send them, where they choose their password.", "<b>" + esc(name) + "</b>")
                            + (admin ? " " + t("They will run this service too: they will see this page and change the caps.") : ""), op, fields("name", name, "mail", mail, "admin", admin ? "yes" : ""), "", t("Make the account"), "accounts"));
                    AccountService.Made m = AccountService.newAccount(store, AccountService.root(), name, mail, admin, who.account().id());
                    AccountActs.audit(store, by, "account_made", m.account().id(), m.library().id(), null);
                    return Result.page(shown(svc, who, t("An account for {0}", m.account().name()), t("Send {0} this link. It works once, for {1} days; there they choose their password. It is not shown again.", "<b>" + esc(m.account().name()) + "</b>", AccountService.JOIN_DAYS), svc.address() + "/join?code=" + m.code()));
                }
                case "account-link" -> {
                    if (a == null || !a.active()) return Result.note(t("That is not an account of this service."), "accounts");
                    if (!yes) return Result.page(confirm(svc, who, t("A new join link for {0}?", a.name()), t("A new link will be made for {0}, and the one before it will no longer open. They choose a new password there, which is how someone who forgot theirs gets back in.", "<b>" + esc(a.name()) + "</b>"), op, fields("account", a.id()), "", t("Make the link"), "accounts"));
                    String code = store.newInvite(a.id(), who.account().id(), AccountService.JOIN_DAYS);
                    AccountActs.audit(store, by, "join_link_made", a.id(), "", null);
                    return Result.page(shown(svc, who, t("A new join link for {0}", a.name()), t("Send {0} this link. It works once, for {1} days; there they choose their password. It is not shown again.", "<b>" + esc(a.name()) + "</b>", AccountService.JOIN_DAYS), svc.address() + "/join?code=" + code));
                }
                case "account-suspend", "account-unsuspend", "account-keep", "account-delete" -> {
                    if (a == null || a.state().equals("removed")) return Result.note(t("That is not an account of this service."), "accounts");
                    if (a.id().equals(who.account().id())) return Result.note(t("Your own account is suspended or deleted from the command line, not from this page."), "accounts");
                    // the operator's deletion is at once unless they choose the days of grace (the owner, 2026-11-18); in those days only they keep it
                    boolean now = !"grace".equals(form.get("when"));
                    int grace = AccountActs.deleteDays();
                    if (!yes) {
                        String said = switch (op) {
                            case "account-suspend" -> t("{0} will be frozen at once: signed out everywhere, their keys refused, their running work stopped and kept, and the libraries they keep open for nobody. Nothing will be deleted, and letting them back will give everything back as it was.", "<b>" + esc(a.name()) + "</b>");
                            case "account-unsuspend" -> t("{0} will be let back: they will sign in again, their keys will work, their libraries will open, and they will resume their stopped work.", "<b>" + esc(a.name()) + "</b>");
                            case "account-keep" -> t("{0} will be kept, as it was before its deletion was asked for.", "<b>" + esc(a.name()) + "</b>");
                            default -> now || grace == 0 ? t("{0} will be removed now, with the libraries they keep and their copies. This cannot be undone.", "<b>" + esc(a.name()) + "</b>")
                                    : t("{0} will be frozen at once and removed on {1}, with the libraries they keep and their copies; until then it can be kept.", "<b>" + esc(a.name()) + "</b>", esc(day(System.currentTimeMillis() + grace * 86_400_000L)));
                        };
                        String button = switch (op) { case "account-suspend" -> t("Suspend {0}", esc(a.name())); case "account-unsuspend" -> t("Let {0} back", esc(a.name())); case "account-keep" -> t("Keep {0}", esc(a.name())); default -> now || grace == 0 ? t("Remove {0} now", esc(a.name())) : t("Delete {0}", esc(a.name())); };
                        String other = op.equals("account-delete") && grace > 0 && !a.state().equals("deleting") ? "<p class=\"k\">" + (now ? ask("account-delete", fields("account", a.id(), "when", "grace"), t("Delete with {0} days of grace instead: only you can keep it in them", grace))
                                : ask("account-delete", fields("account", a.id(), "when", "now"), t("Remove now instead, without the days of grace"))) + "</p>" : "";
                        return Result.page(confirm(svc, who, buttonWords(op), said + other, op, fields("account", a.id(), "when", now ? "now" : "grace"), "", button, "accounts"));
                    }
                    switch (op) {
                        case "account-suspend" -> { if (!AccountActs.suspend(store, a, by)) return Result.note(t("{0} is not active, so it was not suspended.", a.name()), "accounts"); return Result.note(t("{0} is suspended.", a.name()), "accounts"); }
                        case "account-unsuspend" -> { if (!AccountActs.unsuspend(store, a, by)) return Result.note(t("{0} was not suspended.", a.name()), "accounts"); return Result.note(t("{0} is let back.", a.name()), "accounts"); }
                        case "account-keep" -> { if (!AccountActs.cancelDeletion(store, a, by)) return Result.note(t("{0} is not being deleted.", a.name()), "accounts"); return Result.note(t("{0} is kept, as it was.", a.name()), "accounts"); }
                        default -> {
                            if (a.state().equals("deleting") && (now || grace == 0)) { AccountActs.remove(store, store.account(a.id()), by); return Result.note(t("{0} is removed.", a.name()), "accounts"); }
                            long at = AccountActs.delete(store, a, now ? 0 : grace, by);
                            return Result.note(now || grace == 0 ? t("{0} is removed.", a.name()) : t("{0} is frozen, and removed on {1}.", a.name(), day(at)), "accounts");
                        }
                    }
                }
                case "library-make" -> {
                    String title = form.getOrDefault("title", "").strip();
                    if (a == null || !a.active()) return Result.note(t("That is not an account of this service."), "libraries");
                    if (title.isEmpty()) return Result.note(t("Give the new library a title."), "libraries");
                    if (title.length() > 120) title = title.substring(0, 120);
                    int kept = store.librariesKeptBy(a.id()).size();
                    long most = Caps.of(store, a.id()).get(Caps.LIBRARIES);
                    if (!yes) return Result.page(confirm(svc, who, t("Make a library for {0}?", a.name()), t("{0} will get a new library, {1}, which they will keep.", "<b>" + esc(a.name()) + "</b>", "<b>" + esc(title) + "</b>")
                            + (most >= 0 && kept >= most ? " " + t("They keep {0} already, as many as an account may make itself; this one will be made all the same.", kept) : ""), op, fields("account", a.id(), "title", title), "", t("Make the library"), "libraries"));
                    AccountStore.Library made = AccountService.newLibrary(store, AccountService.root(), a, title);
                    AccountActs.audit(store, by, "library_made", a.id(), made.id(), null);
                    return Result.note(t("The library {0} is made for {1}.", made.title(), a.name()), "libraries");
                }
                case "library-take" -> {
                    String path = form.getOrDefault("path", "").strip();
                    if (path.isEmpty() || !Path.of(path).isAbsolute()) return Result.note(t("Say where it is on this machine, as a whole path."), "libraries");
                    if (a == null || !a.active()) return Result.note(t("That is not an account of this service."), "libraries");
                    if (!yes) return Result.page(confirm(svc, who, t("Take in a library for {0}?", a.name()), t("The library at {0} will be taken in for {1}: moved under the service with its id, its shelves and its jobs, and kept by them. An archive will be unpacked first.", "<code>" + esc(path) + "</code>", "<b>" + esc(a.name()) + "</b>"), op, fields("path", path, "account", a.id()), "", t("Take it in"), "libraries"));
                    AccountStore.Aside back = store.asideAt(Path.of(path).getParent() == null ? path : Path.of(path).getParent().toString());
                    if (back != null && AccountsCli.takeBackOnNode(store, Path.of(path)) != null) {   // on another machine's disk: taken back there (ROB 4)
                        AccountStore.Library taken = store.library(back.library());
                        AccountActs.audit(store, by, "library_taken_in", taken.owner(), taken.id(), null);
                        AccountStore.Account keeper = store.account(taken.owner());
                        return Result.note(t("The library {0} is taken in, kept by {1}.", taken.title(), keeper == null ? "" : keeper.name()), "libraries");
                    }
                    AccountsCli.TakenIn in = AccountsCli.takeIn(store, Path.of(path), a, by);
                    AccountStore.Account keeper = store.account(in.taken().library().owner());
                    return Result.note(t("The library {0} is taken in, kept by {1}.", in.taken().library().title(), keeper == null ? "" : keeper.name()), "libraries");
                }
                case "library-move", "library-release", "library-delete", "library-restore", "library-handover" -> {
                    if (l == null || !l.active()) return Result.note(t("That is not a library in service."), "libraries");
                    AccountStore.Account keeper = store.account(l.owner());
                    String title = esc(l.title()), to = form.getOrDefault("to", "").strip();
                    boolean now = "now".equals(form.get("when"));
                    if (!yes || (op.equals("library-move") || op.equals("library-release") || op.equals("library-handover")) && to.isEmpty()) {
                        String said, inputs = "", button;
                        switch (op) {
                            case "library-move" -> {
                                said = t("{0} will be stopped, its folder moved under the folder you name on this machine, and it will be served from there from its next request. Its old folder will be set aside for {1} days.", "<b>" + title + "</b>", LibraryPlaces.GRACE_MS / 86_400_000L);
                                StringBuilder roots = new StringBuilder();
                                for (AccountStore.NodeRow n : store.nodes()) if (!n.root().isEmpty()) roots.append("<option value=\"").append(esc(n.root())).append("\">");
                                inputs = "<label>" + t("The folder to move it under (a node's folder, or another)") + "<br><input name=\"to\" size=\"50\" list=\"roots\" value=\"" + esc(to) + "\"><datalist id=\"roots\">" + roots + "</datalist></label>";
                                button = t("Move {0}", title);
                            }
                            case "library-release" -> {
                                said = t("{0} will be released: out of the service, an ordinary library at the folder you name, which its keeper serves as a library of their own. Its readers of before will sign in there again; the people of the service will not.", "<b>" + title + "</b>");
                                inputs = "<label>" + t("The folder it will go to, which must not be there yet") + "<br><input name=\"to\" size=\"50\" value=\"" + esc(to) + "\"></label>";
                                button = t("Release {0}", title);
                            }
                            case "library-handover" -> {
                                said = t("{0} will be handed to one of its people, who will keep it from then on; its storage will count on theirs. {1} will no longer keep it.", "<b>" + title + "</b>", esc(keeper == null ? "" : keeper.name()));
                                StringBuilder sel = new StringBuilder("<select name=\"to\">");
                                for (AccountStore.Member m : store.members(l.id())) { AccountStore.Account p = store.account(m.account()); if (p != null && p.active()) sel.append("<option value=\"").append(esc(p.id())).append("\">").append(esc(p.name())).append("</option>"); }
                                inputs = "<label>" + t("Hand it to") + "<br>" + sel + "</select></label>";
                                button = t("Hand {0} over", title);
                            }
                            case "library-restore" -> {
                                LibraryPlaces.Copy c = LibraryPlaces.lastCopy(l.id());
                                if (c == null) return Result.note(t("{0} has no copy to be brought back from.", l.title()), "libraries");
                                said = t("{0} will be stopped and brought back from its copy of {1}; what is in its folder now will be set aside. Its page will say which copy it came from, for a week.", "<b>" + title + "</b>", esc(day(c.ended() > 0 ? c.ended() : c.began())));
                                button = t("Bring {0} back", title);
                            }
                            default -> {
                                said = now ? t("{0} will be removed now, with its copy and everything set aside for it. This cannot be undone.", "<b>" + title + "</b>")
                                        : t("{0} will go out of service at once, its folder set aside, and it will be removed with its copy on {1}; until then it can be taken back.", "<b>" + title + "</b>", esc(day(System.currentTimeMillis() + LibraryPlaces.GRACE_MS)));
                                if (!now) said += "<p class=\"k\">" + ask("library-delete", fields("library", l.id(), "when", "now"), t("Remove now instead, without the days of grace")) + "</p>";
                                button = now ? t("Remove {0} now", title) : t("Delete {0}", title);
                            }
                        }
                        return Result.page(confirm(svc, who, buttonWords(op), said, op, fields("library", l.id(), "when", now ? "now" : ""), inputs, button, "libraries"));
                    }
                    switch (op) {
                        case "library-move" -> {
                            AccountsCli.places(store, l, "move", Path.of(to), false, quiet);
                            AccountActs.audit(store, by, "library_moved", l.owner(), l.id(), AccountActs.detail().put("to", to));
                            return Result.note(t("{0} is at {1} now.", l.title(), store.library(l.id()).folder()), "libraries");
                        }
                        case "library-release" -> {
                            AccountsCli.places(store, l, "release", Path.of(to), false, quiet);
                            AccountActs.audit(store, by, "library_released", l.owner(), l.id(), AccountActs.detail().put("to", to));
                            return Result.note(t("{0} is released: an ordinary library at {1}.", l.title(), to), "libraries");
                        }
                        case "library-handover" -> {
                            AccountStore.Account p = store.account(to);
                            if (p == null || store.members(l.id()).stream().noneMatch(m -> m.account().equals(to))) return Result.note(t("{0} is not one of the people of {1}.", p == null ? to : p.name(), l.title()), "libraries");
                            AccountActs.handOver(store, l, p, by);
                            return Result.note(t("{0} is kept by {1} now.", l.title(), p.name()), "libraries");
                        }
                        case "library-restore" -> {
                            AccountsCli.places(store, l, "restore", null, false, quiet);
                            AccountActs.audit(store, by, "library_restored", l.owner(), l.id(), null);
                            return Result.note(t("{0} is brought back from its copy.", l.title()), "libraries");
                        }
                        default -> {
                            AccountsCli.places(store, l, "delete", null, now, quiet);
                            AccountActs.audit(store, by, "library_deleted", l.owner(), l.id(), now ? AccountActs.detail().put("now", true) : null);
                            return Result.note(now ? t("{0} is removed.", l.title()) : t("{0} is out of service, and removed on {1}.", l.title(), day(System.currentTimeMillis() + LibraryPlaces.GRACE_MS)), "libraries");
                        }
                    }
                }
                default -> { return null; }
            }
        } catch (IOException | ProtocolError e) { return Result.note(e.getMessage(), op.startsWith("library") ? "libraries" : "accounts"); }
    }

    /** The question's title, by the act. */
    private static String buttonWords(String op) {
        return switch (op) {
            case "account-suspend" -> t("Suspend this account"); case "account-unsuspend" -> t("Let this account back"); case "account-keep" -> t("Keep this account"); case "account-delete" -> t("Delete this account");
            case "library-move" -> t("Move this library"); case "library-release" -> t("Release this library"); case "library-handover" -> t("Hand this library over"); case "library-restore" -> t("Bring this library back from its copy");
            default -> t("Delete this library");
        };
    }

    private static Map<String, String> fields(String... kv) { Map<String, String> m = new LinkedHashMap<>(); for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]); return m; }

    /** A link shown once, on a page of its own, with the way back. */
    private static String shown(AccountService svc, AccountService.Who who, String title, String said, String link) {
        return AccountPages.shell(svc, who, title, "<div class=\"notice\"><p>" + said + "</p><pre>" + esc(link) + "</pre></div><p><a href=\"" + AccountPages.OPERATOR + "#accounts\">" + t("Back to every account") + "</a></p>");
    }

    /** The page's sections, as a line of links at its top. */
    static String nav() {
        return "<p class=\"k\"><a href=\"#accounts\">" + t("Accounts") + "</a> · <a href=\"#libraries\">" + t("Libraries") + "</a> · <a href=\"#nodes\">" + t("Machines") + "</a> · <a href=\"#caps\">" + t("The caps") + "</a> · <a href=\"#audit\">" + t("The audit log") + "</a></p>";
    }

    /** Where a note sends the browser back to: the section. */
    static String back(String note, String anchor) { return AccountPages.OPERATOR + "?note=" + enc(note) + (anchor == null || anchor.isEmpty() ? "" : "#" + anchor); }
}
