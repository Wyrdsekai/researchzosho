package org.researchzosho.librarian;

import org.researchzosho.tools.Outbound;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.researchzosho.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The spending plug-in (0.8.0, stage 3, step 4): a service run for pay answers whether an account may start a use from somewhere of
 * its own, a URL its operator names ({@code accounts_spending}), and is told what each use came to. The protocol is
 * docs/public/SPENDING_PLUGIN.md; in short: two calls, {@code POST <url>/may-start} and {@code POST <url>/spent}, each body a JWT
 * signed ES256 with the service's key (the one its statements are signed with, published at {@code /.well-known/jwks.json}).
 * An answer to may-start that does not come within five seconds, or is not a 200 with a yes or a no, is a no. What was spent waits
 * in the store's outbox, in the order it was written, and is sent again until the plug-in answers 2xx; the next one waits for it.
 */
final class PluginSpending implements Spending {
    private static final Logger LOG = LoggerFactory.getLogger(PluginSpending.class);
    /** The setting that names the plug-in's address; unset, the built-in caps answer. */
    static final String SETTING = "RESEARCHZOSHO_ACCOUNTS_SPENDING";
    static final String MAY_START = "/may-start", SPENT = "/spent", ACCOUNT_DELETED = "/account-deleted";
    /** A spent the plug-in refuses this many times with a 4xx (it will never take it) is set aside for the operator, so the rest go on (BILL 12). */
    static final int PARK_AFTER = 5;
    /** What a use on the person's own key may cost the operator inside it when the plug-in sets no ceiling (BILL 13); "off" for none. */
    static final String OWN_CEILING = "RESEARCHZOSHO_ACCOUNTS_OWN_USE_CEILING";

    /** The ceiling of the operator's dollars in a use whose model the person's own key pays: the setting, $1 unless set, -1 when off. */
    static long ownCeilingMicros() {
        String v = Config.get(OWN_CEILING);
        if (v != null && v.strip().equalsIgnoreCase("off")) return -1;
        try { return v == null || v.isBlank() ? 1_000_000 : Math.round(Double.parseDouble(v.strip().replace(',', '.').replace("$", "")) * 1_000_000); } catch (NumberFormatException e) { return 1_000_000; }
    }
    /** How long the plug-in has to answer may-start. */
    static volatile long ANSWER_MS = 5_000;
    /** The first wait after a spent that was not taken; it doubles each time, up to {@link #BACKOFF_MOST_MS}. Tests shorten both. */
    static volatile long BACKOFF_MS = 5_000, BACKOFF_MOST_MS = 10 * 60_000L;
    /** How long a signed call is good for. */
    static final long VALID_S = 300;

    private final AccountStore store;
    private final String url, issuer;
    private final HttpClient http = Outbound.client(HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build());
    private volatile Sender sender;

    PluginSpending(AccountStore store, String url, String issuer) { this.store = store; this.url = url; this.issuer = issuer; }

    /** The plug-in's address, without a slash at its end; null when the setting names none. */
    static String url() {
        String v = Config.get(SETTING);
        return v == null || v.isBlank() || v.strip().equalsIgnoreCase("off") ? null : v.strip().replaceAll("/+$", "");
    }

    /** The claims every call carries: who signs, for whom, when, how long, and its own id. */
    /**
     * A call to the plug-in with its whole answer, the connection included, within {@code ms}, as SPENDING_PLUGIN.md says (round F, money
     * 10: the connection's own five seconds came on top of the answer's).
     */
    private HttpResponse<String> within(HttpRequest req, long ms) throws IOException, InterruptedException {
        CompletableFuture<HttpResponse<String>> f = http.sendAsync(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        try { return f.get(ms, TimeUnit.MILLISECONDS); }
        catch (TimeoutException e) { f.cancel(true); throw new HttpTimeoutException("no answer within " + ms + " ms"); }
        catch (ExecutionException e) { throw e.getCause() instanceof IOException io ? io : new IOException(e.getCause()); }
    }

    /** How many times the plug-in refused an entry, as its last word on it carries the count. */
    static int refusals(String last) {
        Matcher m = Pattern.compile("; refused (\\d+)").matcher(last == null ? "" : last);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    private ObjectNode claims(String kind, String jti) {
        long now = System.currentTimeMillis() / 1000;
        ObjectNode c = Statement.M.createObjectNode();
        c.put("iss", issuer); c.put("aud", url); c.put("iat", now); c.put("exp", now + VALID_S); c.put("jti", jti); c.put("kind", kind);
        return c;
    }

    ObjectNode useJson(Spending.Use u) throws IOException {
        ObjectNode o = Statement.M.createObjectNode();
        AccountStore.Account a = store.account(u.account());
        o.put("id", u.id()); o.put("account", u.account()); o.put("account_name", a == null ? "" : a.name()); o.put("library", u.library()); o.put("kind", u.kind()); o.put("entry", u.entry()); o.put("payer", u.payer());
        o.put("estimate_dollars", u.estimateMicros() / 1_000_000.0); o.put("estimate_minutes", u.estimateMinutes()); o.put("job", u.job());
        return o;
    }

    private static void totals(ArrayNode into, Iterable<AccountStore.MonthTotal> ts) {
        for (AccountStore.MonthTotal t : ts) into.addObject().put("kind", t.kind()).put("payer", t.payer()).put("quantity", t.quantity()).put("tokens", t.tokens()).put("bytes", t.bytes())
                .put("dollars", t.micros() / 1_000_000.0).put("estimated", t.estimated());
    }

    @Override public Answer mayStart(Use use) throws IOException {
        ObjectNode c = claims("may_start", use.id());
        c.set("use", useJson(use));
        c.put("lang", Chrome.now().code);
        c.put("month", AccountStore.month(System.currentTimeMillis()));
        totals(c.putArray("month_totals"), store.month(use.account(), AccountStore.month(System.currentTimeMillis())));
        AccountStore.Signing s = store.signing();
        String token = Statement.signJson(s.key(), s.kid(), c, Statement.SPEND_TYP);
        HttpResponse<String> r;
        try {
            r = within(HttpRequest.newBuilder(URI.create(url + MAY_START)).timeout(Duration.ofMillis(ANSWER_MS)).header("Content-Type", "application/jwt")
                    .POST(HttpRequest.BodyPublishers.ofString(token, StandardCharsets.US_ASCII)).build(), ANSWER_MS);
        } catch (IOException e) {
            LOG.warn("The spending plug-in did not answer whether {} may start a {} ({}); it is not started.", use.account(), use.kind(), e.toString());
            return Answer.no(ServiceUses.couldNotAsk());
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); return Answer.no(ServiceUses.couldNotAsk()); }
        JsonNode a;
        try { a = r.statusCode() == 200 ? Statement.M.readTree(r.body()) : null; } catch (IOException notJson) { a = null; }
        if (a == null || !a.path("yes").isBoolean()) {
            LOG.warn("The spending plug-in answered {} to whether {} may start a {}, which is not an answer; it is not started.", r.statusCode(), use.account(), use.kind());
            return Answer.no(ServiceUses.couldNotAsk());
        }
        String said = a.path("said").asText("").strip();
        if (!a.path("yes").asBoolean()) return Answer.no(said.isEmpty() ? Pages.t("Whoever runs this service does not let this start now.") : said);
        double dollars = a.path("ceiling_dollars").asDouble(-1);
        long minutes = a.path("ceiling_minutes").asLong(-1);
        long ceiling = dollars < 0 ? -1 : Math.round(dollars * 1_000_000);
        // on the person's own key, what the operator still pays inside the use (searches without their key, helpers) keeps a ceiling (BILL 13)
        if (ceiling < 0 && "own".equals(use.payer())) ceiling = ownCeilingMicros();
        return new Answer(true, said, ceiling, minutes < 0 ? -1 : minutes);
    }

    /** What the use came to, written at the end of the outbox; the sender takes it on from there. */
    @Override public void spent(Use use, Actual actual) throws IOException { told(use.id(), use, actual); }

    /**
     * What the use came to, told again with its whole when more was counted under it after it ended: a {@code spent} of its own, whose
     * {@code jti} is the use's id and the dollars it came to now, so each change is told once and replaces the one before.
     */
    @Override public void spentAgain(Use use, Actual actual) throws IOException { told(use.id() + "~" + actual.micros(), use, actual); }

    private void told(String jti, Use use, Actual actual) throws IOException {
        ObjectNode o = Statement.M.createObjectNode();
        o.set("use", useJson(use));
        ObjectNode ac = o.putObject("actual");
        ac.put("dollars", actual.micros() / 1_000_000.0); ac.put("model_tokens", actual.modelTokens()); ac.put("searches", actual.searches()); ac.put("fetches", actual.fetches()); ac.put("estimated", actual.estimated());
        totals(ac.putArray("totals"), store.useTotals(use.id()));
        store.outboxAdd(jti, Statement.M.writeValueAsString(o));
        Sender s = sender;
        if (s != null) s.wake();
    }

    /** The sender of the outbox, on a thread of its own, started with the service; one process of the service sends at a time. */
    Sender sender(String holder) { Sender s = new Sender(holder); sender = s; s.start(); return s; }

    final class Sender {
        private final String holder;
        private final Object bell = new Object();
        private volatile boolean stopped;
        private Thread thread;

        Sender(String holder) { this.holder = holder; }

        void start() { thread = Thread.ofPlatform().daemon().name("spending-outbox").start(this::run); }
        void stop() { stopped = true; if (thread != null) thread.interrupt(); }
        void wake() { synchronized (bell) { bell.notifyAll(); } }

        private void pause(long ms) throws InterruptedException { synchronized (bell) { if (!stopped) bell.wait(Math.max(1, ms)); } }

        private void run() {
            while (!stopped) {
                try {
                    if (!store.duty("outbox", holder, 30_000)) { pause(5_000); continue; }   // another process of the service sends now
                    AccountStore.Outgoing o = store.outboxHead();
                    if (o == null) { pause(2_000); continue; }
                    long now = System.currentTimeMillis();
                    if (o.nextAt() > now) { pause(Math.min(o.nextAt() - now, 2_000)); continue; }   // the first waits its turn again; the rest wait behind it
                    JsonNode body = Statement.M.readTree(o.body());
                    boolean notice = body.has("notice");   // an account removed (BILL 12), told by its id
                    String use = notice ? "" : o.id().split("~", 2)[0];
                    // an older whole of a use, behind a newer one sent or waiting: dropped, never sent to overwrite it (round F, money 8)
                    if (!notice && store.outboxNewerFor(use, o.seq())) { store.outboxTaken(o.seq()); LOG.info("An older count of the use {} was dropped: a newer one was told.", use); continue; }
                    ObjectNode c = claims(notice ? body.path("notice").asText() : "spent", o.id());
                    if (notice) c.put("account", body.path("account").asText(""));
                    else body.fields().forEachRemaining(f -> c.set(f.getKey(), f.getValue()));
                    AccountStore.Signing s = store.signing();
                    String token = Statement.signJson(s.key(), s.kid(), c, Statement.SPEND_TYP);
                    String why;
                    try {
                        HttpResponse<String> r = within(HttpRequest.newBuilder(URI.create(url + (notice ? ACCOUNT_DELETED : SPENT))).timeout(Duration.ofSeconds(10)).header("Content-Type", "application/jwt")
                                .POST(HttpRequest.BodyPublishers.ofString(token, StandardCharsets.US_ASCII)).build(), 10_000);
                        if (r.statusCode() / 100 == 2 || notice && (r.statusCode() == 404 || r.statusCode() == 405)) {   // a plug-in with no such call takes no notices
                            store.outboxTaken(o.seq());
                            if (!notice) store.outboxTakenFor(use, o.seq());
                            continue;
                        }
                        // a refusal (a 4xx that is not "too slow" or "too many") counts on its own: timeouts between them neither park nor
                        // reset it (round F, money 8: four timeouts and one 400 parked an entry, and a 429 parked as if final)
                        boolean refusal = r.statusCode() / 100 == 4 && r.statusCode() != 408 && r.statusCode() != 429;
                        int refused = refusals(o.last()) + (refusal ? 1 : 0);
                        why = "HTTP " + r.statusCode() + (refused > 0 ? "; refused " + refused : "");
                        if (refusal && refused >= PARK_AFTER) {   // refused, and refused again: it will not be taken; the rest go on
                            store.outboxPark(o.seq(), why);
                            LOG.warn("The spending plug-in refused what the use {} came to {} times ({}); it is set aside on the operator's page, and what comes after it is sent.", o.id(), PARK_AFTER, why);
                            continue;
                        }
                    } catch (IOException e) { why = e.toString() + (refusals(o.last()) > 0 ? "; refused " + refusals(o.last()) : ""); }
                    long wait = Math.min(BACKOFF_MOST_MS, BACKOFF_MS << Math.min(o.tries(), 20));
                    store.outboxFailed(o.seq(), System.currentTimeMillis() + wait, why);
                    if (o.tries() == 0 || o.tries() % 10 == 9) LOG.warn("The spending plug-in did not take what the use {} came to ({}); it is sent again in {} s, and what comes after it waits.", o.id(), why, wait / 1000);
                } catch (InterruptedException e) { return; }
                catch (IOException | RuntimeException e) {
                    LOG.warn("The outbox of the spending plug-in could not be read: {}", e.getMessage());
                    try { pause(5_000); } catch (InterruptedException ie) { return; }
                }
            }
        }
    }
}
