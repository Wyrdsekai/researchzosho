package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;

import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a person of a hosted library is shown, read off the pages through the account service as a browser gets them (0.8.0). With
 * no model answering, the first screen says the service's model does not answer and who has to look, and offers nothing to connect.
 * Keep and Settings show the person who keeps the library its own settings, which they can set from that page, and point to the
 * account page for its people and keys; nobody is shown a form that would only refuse. The account page's two tables are the pages'
 * stacking tables, which a phone shows row by row with nothing to scroll sideways (measured in a browser at 390 and 320 pixels).
 */
class HostedPagesTest {
    AccountStore store; AccountService svc; AccountServiceTest.Here runner;

    void start(Path tmp) throws Exception {
        Config.set(AccountService.ROOT, tmp.resolve("accounts").toString());
        store = AccountStore.open(AccountService.root().resolve("accounts.db").toString());
        svc = AccountService.start(store, "127.0.0.1", 0, self -> runner = new AccountServiceTest.Here(self));
    }

    @AfterEach void stop() throws Exception {
        if (svc != null) svc.stop();
        if (store != null) store.close();
        Config.set(AccountService.ROOT, "");
    }

    /** Every form on a page: where it posts to, and the act it names where it names one. */
    static List<String> forms(String html) {
        List<String> out = new ArrayList<>();
        Matcher f = Pattern.compile("<form\\b([^>]*)>(.*?)</form>", Pattern.DOTALL).matcher(html);
        while (f.find()) {
            Matcher a = Pattern.compile("action=\"([^\"]*)\"").matcher(f.group(1)), op = Pattern.compile("name=\"op\" value=\"([^\"]*)\"").matcher(f.group(2));
            out.add((f.group(1).contains("method=\"post\"") ? "POST " : "GET ") + (a.find() ? a.group(1) : "") + (op.find() ? " op=" + op.group(1) : ""));
        }
        return out;
    }

    /** What a hosted library refuses whole, by the page or the form that leads to it: none of it is offered on a page that says it shows what works. */
    static void offersNothingOfTheMachines(String page, String html) {
        for (String gone : new String[]{"action=\"/connect\"", "href=\"/connect\"", "action=\"/mcp\"", "action=\"/share\"", "action=\"/readers\"", "name=\"key\"", "researchzosho reader ", "researchzosho models", "Connect a model",
                "One you rent", "A model you own", "Access is open", "the drive at", "Other MCP servers", "<h2>Models</h2>", "from a terminal"})
            assertFalse(html.contains(gone), page + " offers " + gone);
    }

    @Test
    void withNoModelAnsweringTheFirstScreenSaysWhoHasToLookAndOffersNothingToConnect(@TempDir Path tmp) throws Exception {
        start(tmp);
        AccountService.Made ann = svc.newAccount("Ann Example", "", true, "the test");
        AccountServiceTest.Caller a = AccountServiceTest.join(svc, ann.code(), "ann's long password");
        synchronized (Pages.DRIVE_UP) { Pages.DRIVE_UP[1] = 0; }
        String home = a.get("/").body();
        assertTrue(home.contains("<div class=\"first\"><div class=\"connect\"><h2 class=\"plain\">The model does not answer</h2>"), "no model has answered: the chat's home says so first: " + home);
        assertTrue(home.contains("This library uses the model of the service it runs under, and that model does not answer now. Whoever runs the service has to look into it."), home);
        assertTrue(home.contains("id=\"chat-say\""), "the composer is still there");
        offersNothingOfTheMachines("the first screen", home);
        assertFalse(home.contains("127.0.0.1:1"), "the model's address is the service's own affair");
        HttpResponse<String> own = a.get("/connect");
        assertEquals(200, own.statusCode());
        assertTrue(own.body().contains("The model does not answer") && own.body().contains("Whoever runs the service has to look into it."), own.body());
        offersNothingOfTheMachines("/connect", own.body());
        // the form the single-person page has is not there, and a post made by hand connects nothing and tests nothing
        String drive = Config.get("RESEARCHZOSHO_DRIVE", "");
        int entries = ModelEntries.all().size();
        for (String post : new String[]{"op=test&address=http%3A%2F%2F127.0.0.1%3A1&model=", "op=own&address=http%3A%2F%2F127.0.0.1%3A9&model=x", "op=rent&host=openai&key=not-a-key&model=x", "op=look"}) {
            HttpResponse<String> r = a.form("/connect", post);
            assertEquals(303, r.statusCode(), post + ": " + r.body()); assertEquals("/connect", AccountServiceTest.note(r), post);
        }
        assertEquals(drive, Config.get("RESEARCHZOSHO_DRIVE", "")); assertEquals(entries, ModelEntries.all().size());
        // asked for research on any surface, the person is told the same, and no address of the service's model
        HttpResponse<String> research = a.json("/v1/research", "{\"question\":\"What did the harbour master write in the log on the day the lighthouse was lit?\"}");
        assertEquals(503, research.statusCode(), research.body());
        assertTrue(research.body().contains("The model of the service this library runs under does not answer now. Whoever runs the service has to look into it. Research needs it."), research.body());
        assertFalse(research.body().contains("127.0.0.1") || research.body().contains("localhost") || research.body().contains("http"), "no address: " + research.body());
    }

    @Test
    void keepAndSettingsShowTheKeeperTheLibrarysOwnSettingsAndPointToTheAccountPage(@TempDir Path tmp) throws Exception {
        start(tmp);
        AccountService.Made ann = svc.newAccount("Ann Example", "", true, "the test");
        AccountServiceTest.Caller a = AccountServiceTest.join(svc, ann.code(), "ann's long password");
        String lib = ann.library().id();
        HttpResponse<String> invited = a.form("/account", "op=member-let-in&library=" + lib + "&name=" + AccountServiceTest.enc("Cat New") + "&level=write");
        AccountServiceTest.Caller c = AccountServiceTest.joinAsNew(svc, AccountServiceTest.found(invited.body(), "/join\\?code=([A-Za-z0-9_-]+)"), "Cat New", "cat's long password");
        assertEquals(303, c.form("/account", "op=open&library=" + lib).statusCode());
        assertEquals(lib, c.status().path("library_id").asText());

        // what the API lists as the library's own settings is what the pages offer, name for name
        JsonNode listed = AccountServiceTest.M.readTree(a.json("/v1/settings", "{\"op\":\"list\"}").body());
        Set<String> own = new LinkedHashSet<>();
        for (JsonNode s : listed.path("settings")) { own.add(s.path("name").asText()); assertTrue(Hosted.ownSetting(s.path("key").asText()), s.path("key").asText() + " is one of the library's own"); }
        assertTrue(own.size() >= 5 && own.contains("language") && own.contains("report_length"), listed.toString());
        for (String page : new String[]{"/keep", "/settings"}) {
            HttpResponse<String> r = a.get(page);
            assertEquals(200, r.statusCode(), page);
            String h = r.body();
            assertTrue(h.contains("These are this library's own settings. Every other setting is set by whoever runs the service it runs under."), page + ": " + h);
            assertTrue(h.contains("The people of this library and the keys of their programs are on your account page.") && h.contains("<a class=\"btn\" href=\"/account\">Account</a>"), page + " points to the account page");
            offersNothingOfTheMachines(page, h);
            assertTrue(h.contains("<p>The same in the chat, in a sentence.</p>"), page + " says where else the settings are set, and names no command that does not set them");
            List<String> posts = forms(h).stream().filter(f -> f.startsWith("POST ")).toList();
            assertEquals(List.of("POST /settings"), posts, page + ": the one form that changes anything is the library's own settings");
            Set<String> offered = new LinkedHashSet<>();
            Matcher o = Pattern.compile("<option value=\"([^\"]+)\">").matcher(h.substring(h.indexOf("<form method=\"post\" action=\"/settings\">")));
            while (o.find()) offered.add(o.group(1));
            assertEquals(own, offered, page + ": the settings the form offers");
            assertTrue(h.contains("<a class=\"keep\" href=\"/keep\"") && h.contains("<a class=\"keep\" href=\"/settings\""), "the keeper's menu has both");
        }
        // the form does what it says: a setting of the library's own is set from the page, and read back
        String name = "report_length";
        HttpResponse<String> set = a.form("/settings", "name=" + AccountServiceTest.enc(name) + "&value=short");
        assertEquals(303, set.statusCode(), set.body());
        assertEquals("/settings?note=report_length is set.", AccountServiceTest.note(set), "the form did what it says");
        String after = a.get(set.headers().firstValue("Location").orElseThrow()).body();
        assertTrue(after.contains("<p class=\"note\">report_length is set.</p>"), "and the page it leads to says so: " + after);
        JsonNode again = AccountServiceTest.M.readTree(a.json("/v1/settings", "{\"op\":\"list\"}").body());
        String value = ""; for (JsonNode s : again.path("settings")) if (s.path("name").asText().equals(name)) value = s.path("value").asText();
        assertEquals("short", value, again.toString());
        a.form("/settings", "name=" + AccountServiceTest.enc(name) + "&value=");

        // someone who writes in the library and does not keep it: told who sets it, pointed to their account, shown no form to change it and no Keep in the menu
        for (String page : new String[]{"/keep", "/settings"}) {
            HttpResponse<String> r = c.get(page);
            assertEquals(200, r.statusCode(), page + ": " + r.body());
            String h = r.body();
            assertTrue(h.contains("The person who keeps this library sets how its reports are written.") && h.contains("<a class=\"btn\" href=\"/account\">Account</a>"), page + ": " + h);
            assertTrue(forms(h).stream().noneMatch(f -> f.startsWith("POST ")), page + " shows a writer no form: " + forms(h));
            assertFalse(h.contains("<a class=\"keep\""), "and no Keep in the menu");
            offersNothingOfTheMachines(page, h);
        }
        assertFalse(c.get("/").body().contains("<a class=\"keep\""), "nor on the chat's page");
    }

    /**
     * Every page the person who keeps a hosted library reaches by following links from its first page, Keep, Settings and the help,
     * three links deep: none of them shows a form that posts to something a hosted library refuses whole (connecting a model, other
     * MCP servers, sharing, a database, the people's own sign-in), and none links to the page that connects a model.
     */
    @Test
    void noPageAHostedKeeperReachesOffersAFormThatOnlyRefuses(@TempDir Path tmp) throws Exception {
        start(tmp);
        AccountService.Made ann = svc.newAccount("Ann Example", "", true, "the test");
        AccountServiceTest.Caller a = AccountServiceTest.join(svc, ann.code(), "ann's long password");
        assertEquals(200, a.json("/v1/frontier", AccountServiceTest.ADD).statusCode());   // something on the shelves' lists, so their pages have their forms
        Map<String, Set<String>> posts = new TreeMap<>();
        Set<String> seen = new LinkedHashSet<>(), next = new LinkedHashSet<>(List.of("/", "/keep", "/settings", "/help", "/connect"));
        for (int depth = 0; depth < 3; depth++) {
            Set<String> found = new LinkedHashSet<>();
            for (String path : next) {
                if (path.contains("'") || seen.size() >= 120 || !seen.add(path)) continue;   // a link a script puts together is no address
                HttpResponse<String> r = a.get(path);
                if (r.statusCode() != 200) continue;
                String h = r.body(), own = h.substring(Math.max(0, h.indexOf("<main")));
                assertFalse(h.contains("href=\"/connect\"") || h.contains("action=\"/connect\""), path + " leads to the page that connects a model");
                for (String f : forms(own)) if (f.startsWith("POST ")) posts.computeIfAbsent(f, k -> new LinkedHashSet<>()).add(path);
                Matcher l = Pattern.compile("href=\"(/[^\"#]*)").matcher(h);
                while (l.find()) { String to = l.group(1).replace("&amp;", "&"); if (!to.startsWith("/logout") && !to.startsWith("/account") && !to.startsWith("/login") && !to.contains("lang=") && !to.contains("look=")) found.add(to); }
            }
            next = found;
        }
        assertTrue(seen.size() >= 40 && posts.size() >= 12, "the pages were walked and their forms read: " + seen.size() + " pages, " + posts.keySet());
        assertTrue(posts.containsKey("POST /settings") && posts.containsKey("POST /chat") && posts.containsKey("POST /add"), posts.keySet().toString());
        for (Map.Entry<String, Set<String>> e : posts.entrySet()) {
            String page = e.getKey().substring(6).replaceFirst("[ /?].*$", "");   // "POST /share op=x" -> "share"
            assertFalse(Set.of("connect", "readers").contains(page) || Hosted.reach(page) == Hosted.Reach.MACHINE, e.getKey() + " is offered on " + e.getValue() + ", and a hosted library refuses it");
        }
    }

    @Test
    void theAccountPagesTwoTablesStackOnAPhone(@TempDir Path tmp) throws Exception {
        start(tmp);
        AccountService.Made ann = svc.newAccount("Ann Example", "", true, "the test");
        AccountServiceTest.Caller a = AccountServiceTest.join(svc, ann.code(), "ann's long password");
        String lib = ann.library().id();
        HttpResponse<String> link = a.form("/account", "op=member-let-in&library=" + lib + "&name=" + AccountServiceTest.enc("Cat New With A Long Name Indeed") + "&level=write");
        AccountServiceTest.joinAsNew(svc, AccountServiceTest.found(link.body(), "/join\\?code=([A-Za-z0-9_-]+)"), "Cat New With A Long Name Indeed", "cat's long password");   // she takes the link: one of the library's people
        assertEquals(200, a.form("/account", "op=key-new&library=" + lib + "&level=write&label=" + AccountServiceTest.enc("an agent on the laptop in the study")).statusCode());
        assertEquals(200, a.form("/account", "op=key-new&library=" + lib + "&level=read&label=" + AccountServiceTest.enc("a reader")).statusCode());
        String h = a.get("/account").body();
        assertTrue(h.contains("an agent on the laptop in the study") && h.contains("Cat New With A Long Name Indeed"), h);
        String main = h.substring(h.indexOf("<main>"));   // the page's own part: the style sheet above it is every page's
        assertFalse(main.contains("white-space:nowrap") || main.contains("class=\"tw\"") || main.contains("class=\"tablewrap\""), "no table is kept on one line and scrolled sideways");
        // the two tables that did not fit a phone, the keys and a library's people, are the pages' stacking tables: on a phone each
        // row is a block and each cell a line under its heading's word, so nothing is as wide as the columns together
        int stacking = 0;
        Matcher table = Pattern.compile("<table([^>]*)>(.*?)</table>", Pattern.DOTALL).matcher(main);
        while (table.find()) {
            boolean keys = table.group(2).contains("value=\"key-revoke\""), people = table.group(2).contains("value=\"member-level\"");
            if (!keys && !people) continue;
            stacking++;
            assertEquals(" class=\"stack\"", table.group(1), keys ? "the keys' table" : "the people's table");
            if (!keys) continue;
            assertTrue(table.group(2).startsWith("<thead><tr><th>key</th><th>for</th><th>library</th><th>made</th><th>last used</th><th></th></tr></thead>"), table.group(2));
            Matcher row = Pattern.compile("<tr><td (.*?)</tr>", Pattern.DOTALL).matcher(table.group(2));
            int rows = 0;
            while (row.find()) {
                rows++;
                List<String> said = new ArrayList<>(); Matcher l = Pattern.compile("data-l=\"([^\"]+)\"").matcher(row.group(1));
                while (l.find()) said.add(l.group(1));
                assertEquals(List.of("key", "for", "library", "made", "last used"), said, "each cell of a key's row says what it is on a phone");
            }
            assertEquals(2, rows, "two keys");
        }
        assertEquals(2, stacking, "the keys, and the people of one library");
        for (String said : new String[]{"value=\"key-revoke\"", "value=\"member-level\"", "value=\"member-remove\"", "reads and writes", "never", "<b>Cat New With A Long Name Indeed</b>"})
            assertTrue(main.contains(said), "the account page lacks " + said);
    }
}
