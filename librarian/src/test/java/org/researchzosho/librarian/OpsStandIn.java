package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.researchzosho.records.Ops;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A stand-in for the European Patent Office's Open Patent Services on this machine, answering in the shapes the office answers in
 * (read off its service on 2026-11-05) with invented publications and applicants: the token, a search page with its total and the
 * two-thousand ceiling, a publication's record, its claims where the office holds full text and its refusal where it does not, a
 * family's members, a class of the scheme, the classification search, and the headers that say the allowance and the load. It writes
 * down every request, so a test can say that a second call fetched nothing.
 */
final class OpsStandIn implements AutoCloseable {
    static final ObjectMapper M = new ObjectMapper();
    static final String STAND_IN_KEY = "stand-in-key", STAND_IN_SECRET = "stand-in-secret";

    /** One invented publication. {@code cited} is how often it is cited: the office gives it, and nothing here may read it. */
    record Pub(String country, String number, String kind, String family, String published, String filed, String priority, String titleEn, String titleOther,
               String abstractEn, String abstractOther, String applicant, List<String> classes, int cited) {
        String epodoc() { return country + number; }
        String id() { return country + number + kind; }
    }

    final List<Pub> pubs = new ArrayList<>();
    /** The claims the office holds, by the number without its kind; each entry one claim's text as the office writes it, its number first. */
    final Map<String, List<String>> claims = new LinkedHashMap<>();
    /** Members of a family the search does not give: family id to [country, number, kind, yyyymmdd]. */
    final Map<String, List<String[]>> otherMembers = new LinkedHashMap<>();
    /** A class's title and the classes under it: symbol to [title, under symbol, under title, …]. */
    final Map<String, String[]> scheme = new LinkedHashMap<>();
    final List<String> requests = new CopyOnWriteArrayList<>();
    final AtomicInteger tokens = new AtomicInteger();
    final AtomicLong weekUsed = new AtomicLong(1_000_000);
    volatile String state = "idle", searchLight = "green:30";
    /** The status every search is answered with while it is not 0: the office out of allowance, or failing. */
    volatile int failSearchWith = 0;
    volatile String rejection = "";

    private final HttpServer server;
    private final String realBase;
    private final double realPace;

    OpsStandIn() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        realBase = Ops.base; realPace = Ops.pace;
        Ops.base = "http://127.0.0.1:" + server.getAddress().getPort();
        Ops.pace = 0;
        Ops.forget();
    }

    @Override public void close() { server.stop(0); Ops.base = realBase; Ops.pace = realPace; Ops.forget(); }

    /** The requests of a kind since the start: "search", "claims", "family", "biblio", "scheme", "classes". */
    long count(String kind) { return requests.stream().filter(r -> r.startsWith(kind + " ")).count(); }

    Pub add(String country, String number, String kind, String family, String published, String titleEn, String abstractEn, String applicant, String symbol) {
        Pub p = new Pub(country, number, kind, family, published, published, published, titleEn, "", abstractEn, "", applicant, List.of(symbol), 0);
        pubs.add(p);
        return p;
    }

    private void handle(HttpExchange x) throws IOException {
        try {
            String path = x.getRequestURI().getPath(), query = x.getRequestURI().getRawQuery() == null ? "" : x.getRequestURI().getRawQuery();
            if (path.equals("/auth/accesstoken")) {
                tokens.incrementAndGet();
                String want = "Basic " + Base64.getEncoder().encodeToString((STAND_IN_KEY + ":" + STAND_IN_SECRET).getBytes(StandardCharsets.UTF_8));
                if (!want.equals(x.getRequestHeaders().getFirst("Authorization"))) { send(x, 401, "application/json", "{\"error\":\"invalid_client\"}"); return; }
                send(x, 200, "application/json", "{\"access_token\":\"stand-in-token\",\"expires_in\":\"1199\",\"token_type\":\"BearerToken\"}");
                return;
            }
            if (!"Bearer stand-in-token".equals(x.getRequestHeaders().getFirst("Authorization"))) { send(x, 401, "application/xml", fault("invalid_access_token", "Invalid Access Token")); return; }
            String rest = path.replaceFirst("^/rest-services", "");
            Map<String, String> q = new LinkedHashMap<>();
            for (String part : query.split("&")) { int eq = part.indexOf('='); if (eq > 0) q.put(part.substring(0, eq), URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8)); }
            if (rest.equals("/published-data/search/biblio")) { search(x, q); return; }
            Matcher m = Pattern.compile("/published-data/publication/epodoc/([^/]+)/(claims|biblio)").matcher(rest);
            if (m.matches() && m.group(2).equals("claims")) { claims(x, m.group(1)); return; }
            if (m.matches()) { biblio(x, m.group(1)); return; }
            m = Pattern.compile("/family/publication/docdb/([A-Z]{2})\\.([^.]+)(?:\\.(.+))?").matcher(rest);
            if (m.matches()) { family(x, m.group(1), m.group(2)); return; }
            if (rest.equals("/classification/cpc/search")) { classes(x, q.getOrDefault("q", "")); return; }
            if (rest.startsWith("/classification/cpc/")) { scheme(x, rest.substring("/classification/cpc/".length())); return; }
            send(x, 404, "application/xml", fault("SERVER.EntityNotFound", "No results found"));
        } catch (RuntimeException e) {
            send(x, 500, "text/plain", String.valueOf(e));
        }
    }

    private void send(HttpExchange x, int status, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().add("Content-Type", type + ";charset=utf-8");
        x.getResponseHeaders().add("X-Throttling-Control", state + " (images=green:200, inpadoc=green:60, other=green:1000, retrieval=green:200, search=" + searchLight + ")");
        x.getResponseHeaders().add("X-IndividualQuotaPerHour-Used", String.valueOf(bytes.length));
        x.getResponseHeaders().add("X-RegisteredQuotaPerWeek-Used", String.valueOf(weekUsed.addAndGet(bytes.length)));
        if (!rejection.isEmpty() && status == 403) x.getResponseHeaders().add("X-Rejection-Reason", rejection);
        x.sendResponseHeaders(status, bytes.length);
        try (var out = x.getResponseBody()) { out.write(bytes); }
    }

    static String fault(String code, String message) { return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<fault xmlns=\"http://ops.epo.org\">\n    <code>" + code + "</code>\n    <message>" + message + "</message>\n</fault>\n"; }

    static ObjectNode v(String value) { return M.createObjectNode().put("$", value); }

    private static final Pattern QUERY = Pattern.compile("cpc=(\\S+) and (?:pd within \"(\\d{4}) (\\d{4})\"|pd=(\\d{4,8}))");

    /** The publications a query of the office's language finds, in the order the office gives them, the newest first. */
    List<Pub> matching(String cql) {
        Matcher m = QUERY.matcher(cql);
        if (!m.matches()) return null;
        String symbol = m.group(1);
        boolean low = symbol.endsWith("/low");
        if (low) symbol = symbol.substring(0, symbol.length() - 4);
        List<Pub> out = new ArrayList<>();
        for (Pub p : pubs) {
            boolean in = false;
            for (String c : p.classes()) if (c.equals(symbol) || (low && c.startsWith(symbol))) in = true;
            if (!in) continue;
            String day = p.published().replace("-", "");
            if (m.group(2) != null ? day.substring(0, 4).compareTo(m.group(2)) >= 0 && day.substring(0, 4).compareTo(m.group(3)) <= 0 : day.startsWith(m.group(4))) out.add(p);
        }
        out.sort((a, b) -> b.published().compareTo(a.published()));
        return out;
    }

    private void search(HttpExchange x, Map<String, String> q) throws IOException {
        String cql = q.getOrDefault("q", ""), range = q.getOrDefault("Range", "1-25");
        requests.add("search " + cql + " " + range);
        if (failSearchWith != 0) { send(x, failSearchWith, "application/xml", fault("CLIENT.RobotDetected", "This request has been rejected due to the violation of Fair Use policy")); return; }
        int first = Integer.parseInt(range.split("-")[0]), last = Integer.parseInt(range.split("-")[1]);
        List<Pub> found = matching(cql);
        if (found == null || last > 2000 || last - first + 1 > 100) { send(x, 400, "application/xml", fault("CLIENT.InvalidQuery", "The request was invalid")); return; }
        if (found.isEmpty()) { send(x, 404, "application/xml", fault("SERVER.EntityNotFound", "No results found")); return; }
        int end = Math.min(last, found.size());
        ObjectNode root = M.createObjectNode();
        ObjectNode s = root.putObject("ops:world-patent-data").putObject("ops:biblio-search");
        s.put("@total-result-count", String.valueOf(found.size()));
        s.putObject("ops:query").put("$", cql).put("@syntax", "CQL");
        s.putObject("ops:range").put("@begin", String.valueOf(first)).put("@end", String.valueOf(end));
        List<Pub> page = first > found.size() ? List.of() : found.subList(first - 1, end);
        ObjectNode result = s.putObject("ops:search-result");
        // an answer made from XML writes a list of one as the one thing itself
        if (page.size() == 1) result.putObject("exchange-documents").set("exchange-document", document(page.get(0)));
        else { ArrayNode docs = result.putArray("exchange-documents"); for (Pub p : page) docs.addObject().set("exchange-document", document(p)); }
        send(x, 200, "application/json", root.toString());
    }

    static ObjectNode docdb(String country, String number, String kind, String day) {
        ObjectNode id = M.createObjectNode().put("@document-id-type", "docdb");
        id.set("country", v(country)); id.set("doc-number", v(number)); id.set("kind", v(kind));
        if (!day.isEmpty()) id.set("date", v(day.replace("-", "")));
        return id;
    }

    /** One publication as the office writes it: the same fields in the same places, citations and all. */
    ObjectNode document(Pub p) {
        ObjectNode d = M.createObjectNode().put("@system", "ops.epo.org").put("@family-id", p.family()).put("@country", p.country()).put("@doc-number", p.number()).put("@kind", p.kind());
        ObjectNode b = d.putObject("bibliographic-data");
        ArrayNode ids = b.putObject("publication-reference").putArray("document-id");
        ids.add(docdb(p.country(), p.number(), p.kind(), p.published()));
        ObjectNode epodoc = ids.addObject().put("@document-id-type", "epodoc"); epodoc.set("doc-number", v(p.epodoc())); epodoc.set("date", v(p.published().replace("-", "")));
        ArrayNode cs = b.putObject("patent-classifications").putArray("patent-classification");
        int seq = 1;
        for (String c : p.classes()) {
            Matcher m = Pattern.compile("([A-HY])(\\d{2})([A-Z])(\\d{1,4})/(\\d{2,6})").matcher(c);
            if (!m.matches()) continue;
            ObjectNode k = cs.addObject().put("@sequence", String.valueOf(seq++));
            k.putObject("classification-scheme").put("@office", "EP").put("@scheme", "CPCI");
            k.set("section", v(m.group(1))); k.set("class", v(m.group(2))); k.set("subclass", v(m.group(3))); k.set("main-group", v(m.group(4))); k.set("subgroup", v(m.group(5))); k.set("classification-value", v("I"));
        }
        ObjectNode app = b.putObject("application-reference").put("@doc-id", "5" + p.number());
        app.putArray("document-id").add(docdb(p.country(), "2" + p.number(), "A", p.filed()));
        ObjectNode pri = b.putObject("priority-claims").putObject("priority-claim").put("@sequence", "1").put("@kind", "national");
        pri.putArray("document-id").add(docdb(p.country(), "2" + p.number(), "A", p.priority()));
        ArrayNode applicants = b.putObject("parties").putObject("applicants").putArray("applicant");
        applicants.addObject().put("@sequence", "1").put("@data-format", "epodoc").putObject("applicant-name").set("name", v(p.applicant().toUpperCase() + " [" + p.country() + "]"));
        applicants.addObject().put("@sequence", "1").put("@data-format", "original").putObject("applicant-name").set("name", v(p.applicant()));
        if (!p.titleEn().isEmpty() && !p.titleOther().isEmpty()) { ArrayNode t = b.putArray("invention-title"); t.addObject().put("$", p.titleEn()).put("@lang", "en"); t.addObject().put("$", p.titleOther()).put("@lang", "ol"); }
        else b.putObject("invention-title").put("$", p.titleEn().isEmpty() ? p.titleOther() : p.titleEn()).put("@lang", p.titleEn().isEmpty() ? "ol" : "en");
        if (p.cited() > 0) {
            ArrayNode cites = b.putObject("references-cited").putArray("citation");
            for (int i = 1; i <= p.cited(); i++) cites.addObject().put("@cited-phase", "search").put("@sequence", String.valueOf(i)).putObject("patcit").putArray("document-id").add(docdb("US", String.valueOf(7_000_000 + i), "B2", ""));
        }
        if (!p.abstractEn().isEmpty() && !p.abstractOther().isEmpty()) { ArrayNode a = d.putArray("abstract"); a.addObject().put("@lang", "en").set("p", v(p.abstractEn())); a.addObject().put("@lang", "ol").set("p", v(p.abstractOther())); }
        else if (!p.abstractEn().isEmpty() || !p.abstractOther().isEmpty()) d.putObject("abstract").put("@lang", p.abstractEn().isEmpty() ? "ol" : "en").set("p", v(p.abstractEn().isEmpty() ? p.abstractOther() : p.abstractEn()));
        return d;
    }

    private void biblio(HttpExchange x, String number) throws IOException {
        requests.add("biblio " + number);
        for (Pub p : pubs) if (p.epodoc().equals(number) || p.id().equals(number)) {
            ObjectNode root = M.createObjectNode();
            root.putObject("ops:world-patent-data").putObject("exchange-documents").set("exchange-document", document(p));
            send(x, 200, "application/json", root.toString());
            return;
        }
        send(x, 404, "application/xml", fault("SERVER.EntityNotFound", "No results found"));
    }

    private void claims(HttpExchange x, String number) throws IOException {
        requests.add("claims " + number);
        List<String> held = claims.get(number);
        String country = number.substring(0, 2);
        if (!Ops.FULL_TEXT.contains(country)) { send(x, 404, "application/xml", fault("CLIENT.InvalidCountryCode", "At least one reference in the request has a unsupported country code: Request for fulltext for FulltextRetrievalType[format=text-only,reference=OpsPublicationReference[docNumber=" + number + "]]")); return; }
        if (held == null) { send(x, 404, "application/xml", fault("SERVER.EntityNotFound", "No results found")); return; }
        ObjectNode root = M.createObjectNode();
        ObjectNode doc = root.putObject("ops:world-patent-data").putObject("ftxt:fulltext-documents").putObject("ftxt:fulltext-document").put("@system", "ops.epo.org").put("@fulltext-format", "text-only");
        ObjectNode id = doc.putObject("bibliographic-data").putObject("publication-reference").put("@data-format", "docdb").putObject("document-id");
        id.set("country", v(country)); id.set("doc-number", v(number.substring(2))); id.set("kind", v("A1"));
        ObjectNode c = doc.putObject("claims").put("@lang", "EN");
        ArrayNode texts = c.putObject("claim").putArray("claim-text");
        for (String t : held) texts.add(v(t));
        send(x, 200, "application/json", root.toString());
    }

    private void family(HttpExchange x, String country, String number) throws IOException {
        requests.add("family " + country + number);
        String family = "";
        for (Pub p : pubs) if (p.country().equals(country) && p.number().equals(number)) family = p.family();
        if (family.isEmpty()) { send(x, 404, "application/xml", fault("SERVER.EntityNotFound", "No results found")); return; }
        List<ObjectNode> members = new ArrayList<>();
        for (Pub p : pubs) if (p.family().equals(family)) members.add(member(family, p.country(), p.number(), p.kind(), p.published()));
        for (String[] o : otherMembers.getOrDefault(family, List.of())) members.add(member(family, o[0], o[1], o[2], o[3]));
        ObjectNode root = M.createObjectNode();
        ObjectNode f = root.putObject("ops:world-patent-data").putObject("ops:patent-family").put("@legal", "false").put("@total-result-count", String.valueOf(members.size()));
        f.putObject("ops:publication-reference").set("document-id", docdb(country, number, "", ""));
        if (members.size() == 1) f.set("ops:family-member", members.get(0)); else { ArrayNode a = f.putArray("ops:family-member"); members.forEach(a::add); }
        send(x, 200, "application/json", root.toString());
    }

    static ObjectNode member(String family, String country, String number, String kind, String day) {
        ObjectNode m = M.createObjectNode().put("@family-id", family);
        ArrayNode ids = m.putObject("publication-reference").putArray("document-id");
        ids.add(docdb(country, number, kind, day));
        ObjectNode epodoc = ids.addObject().put("@document-id-type", "epodoc"); epodoc.set("doc-number", v(country + number)); epodoc.set("date", v(day.replace("-", "")));
        return m;
    }

    private void scheme(HttpExchange x, String symbol) throws IOException {
        requests.add("scheme " + symbol);
        String[] s = scheme.get(symbol);
        if (s == null) { send(x, 404, "application/xml", fault("SERVER.EntityNotFound", "No results found")); return; }
        StringBuilder b = new StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\" standalone=\"yes\"?><ops:world-patent-data xmlns:ops=\"http://ops.epo.org\" xmlns:cpc=\"http://www.epo.org/cpcexport\"><ops:classification-scheme><ops:cpc><cpc:class-scheme scheme-type=\"cpc\" export-date=\"2026-08-01\">");
        b.append("<cpc:classification-item level=\"8\" sort-key=\"").append(symbol).append("\"><cpc:classification-symbol>").append(symbol).append("</cpc:classification-symbol><cpc:class-title>");
        for (String part : s[0].split("; ")) b.append("<cpc:title-part><cpc:text>").append(part).append("</cpc:text></cpc:title-part>");
        b.append("</cpc:class-title><cpc:notes-and-warnings><cpc:note type=\"warning\"><cpc:note-paragraph>Group <cpc:class-ref scheme=\"cpc\">").append(symbol).append("</cpc:class-ref> is incomplete pending reclassification.</cpc:note-paragraph></cpc:note></cpc:notes-and-warnings>");
        for (int i = 1; i + 1 < s.length; i += 2) {
            b.append("<cpc:classification-item level=\"9\" sort-key=\"").append(s[i]).append("\"><cpc:classification-symbol>").append(s[i]).append("</cpc:classification-symbol><cpc:class-title><cpc:title-part><cpc:comment><cpc:text>").append(s[i + 1]).append("</cpc:text></cpc:comment></cpc:title-part></cpc:class-title><cpc:meta-data>D</cpc:meta-data>");
            // asked for everything under it, a class that has classes of its own in the stand-in's scheme carries them, one level further down
            String[] deeper = x.getRequestURI().getRawQuery() != null && x.getRequestURI().getRawQuery().contains("depth=all") ? scheme.get(s[i]) : null;
            if (deeper != null) for (int k = 1; k + 1 < deeper.length; k += 2) b.append("<cpc:classification-item level=\"10\" sort-key=\"").append(deeper[k]).append("\"><cpc:classification-symbol>").append(deeper[k]).append("</cpc:classification-symbol><cpc:class-title><cpc:title-part><cpc:text>").append(deeper[k + 1]).append("</cpc:text></cpc:title-part></cpc:class-title></cpc:classification-item>");
            b.append("</cpc:classification-item>");
        }
        b.append("</cpc:classification-item></cpc:class-scheme></ops:cpc></ops:classification-scheme></ops:world-patent-data>");
        send(x, 200, "application/cpc+xml", b.toString());
    }

    private void classes(HttpExchange x, String words) throws IOException {
        requests.add("classes " + words);
        ObjectNode root = M.createObjectNode();
        ObjectNode s = root.putObject("ops:world-patent-data").putObject("ops:classification-search").put("@total-result-count", String.valueOf(scheme.size())).put("@scheme-type", "CPC");
        s.putObject("ops:query").put("@syntax", "").put("$", words);
        ArrayNode stats = s.putObject("ops:search-result").putArray("ops:classification-statistics");
        double share = 0.6;
        for (Map.Entry<String, String[]> e : scheme.entrySet()) {
            if (!e.getKey().endsWith("/00")) continue;   // the office's classification search answers with main groups
            ObjectNode c = stats.addObject().put("@classification-symbol", e.getKey()).put("@percentage", String.valueOf(share));
            c.putObject("cpc:class-title").put("@date-revised", "2016-05-01").putObject("cpc:title-part").putObject("cpc:text").put("$", e.getValue()[0]);
            share /= 2;
        }
        send(x, 200, "application/json", root.toString());
    }
}
