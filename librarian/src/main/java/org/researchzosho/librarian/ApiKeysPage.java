package org.researchzosho.librarian;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.researchzosho.librarian.Pages.esc;
import static org.researchzosho.librarian.Pages.t;

/** The keys section of the Keep page and of a person's account page: the keys they have, a new one, and a revoke beside each. */
final class ApiKeysPage {
    private ApiKeysPage() { }

    /** Whose keys a page shows and makes: the keeper's for the keeper, at the keyboard or signed in, and while the pages are open; a signed-in reader's own; nobody's otherwise. */
    static String owner(Patrons.Patron patron) {
        if (patron.person() || (patron.web() && !WebAccess.signInRequired())) return Accounts.OWNER;
        if (patron.anonymous() || patron.web()) return null;
        return patron.did();
    }

    /** The section's HTML: the new key once when one was just made, the list, the form. {@code action} is the page the forms post to. */
    static String section(LibraryStore store, Patrons.Patron patron, Map<String, String> q, String action) throws IOException {
        String did = owner(patron);
        if (did == null) return "";
        StringBuilder b = new StringBuilder();
        String fresh = q.getOrDefault("key", "");
        if (!fresh.isEmpty()) b.append("<p class=\"notice\">").append(t("Your new key, shown this once. Copy it now:")).append(" <code>").append(esc(fresh)).append("</code></p>");
        List<ApiKeys.Key> keys = ApiKeys.list(store, did);
        b.append("<p class=\"k\">").append(t("A program of yours signs in with a key, sent as {0} to the library's address or its MCP route. A read key reads only; a write key does what you may. The library keeps a key's hash, never the key.", "<code>Authorization: Bearer rz_…</code>")).append("</p>");
        if (keys.isEmpty()) b.append("<p class=\"k\">").append(t("No keys yet.")).append("</p>");
        else {
            b.append("<table><tr><th>").append(t("Label")).append("</th><th>").append(t("Key")).append("</th><th>").append(t("May")).append("</th><th>").append(t("Made")).append("</th><th>").append(t("Last used")).append("</th><th></th></tr>");
            for (ApiKeys.Key k : keys) {
                b.append("<tr><td>").append(esc(k.label())).append("</td><td><code>").append(esc(k.prefix())).append("…</code></td><td>").append(k.reads() ? t("read") : t("write")).append("</td><td>").append(esc(Chrome.now().day(k.made()))).append("</td><td>").append(k.used().isEmpty() ? t("never") : esc(Chrome.now().day(k.used())))
                 .append("</td><td><form method=\"post\" action=\"").append(esc(action)).append("\" class=\"inline\"><input type=\"hidden\" name=\"op\" value=\"key-revoke\"><input type=\"hidden\" name=\"id\" value=\"").append(esc(k.id())).append("\"><button class=\"quiet\">").append(t("Revoke")).append("</button></form></td></tr>");
            }
            b.append("</table>");
        }
        b.append("<form method=\"post\" action=\"").append(esc(action)).append("\" class=\"inline\"><input type=\"hidden\" name=\"op\" value=\"key-new\"><input name=\"label\" placeholder=\"").append(esc(t("what the key is for, e.g. the notes script"))).append("\" size=\"32\"> <select name=\"level\"><option value=\"read\">").append(t("reads")).append("</option><option value=\"write\">").append(t("reads and writes")).append("</option></select> <button class=\"btn\">").append(t("New key")).append("</button></form>");
        return b.toString();
    }

    /** POST op=key-new or key-revoke: the secret of a key just made (shown once by the page), or a note. */
    record Done(String secret, String note) { }

    static Done post(LibraryStore store, Patrons.Patron patron, Map<String, String> form) throws IOException {
        String did = owner(patron);
        if (did == null) throw ProtocolError.forbidden(t("Sign in to make a key."));
        String op = form.getOrDefault("op", "");
        try {
            if (op.equals("key-new")) {
                ApiKeys.Minted m = ApiKeys.mint(store, did, form.getOrDefault("label", ""), Patrons.levelOf(form.getOrDefault("level", "read")));
                return new Done(m.secret(), m.key().reads() ? t("A key for {0} is made; it reads.", m.key().label()) : t("A key for {0} is made; it reads and writes.", m.key().label()));
            }
            if (op.equals("key-revoke")) return new Done("", ApiKeys.revoke(store, did, form.getOrDefault("id", "")) ? t("The key is revoked; a program that used it is refused from now on.") : t("There is no such key of yours."));
        } catch (IOException e) { return new Done("", e.getMessage()); }
        return new Done("", t("That is not a thing this page does."));
    }
}
