package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.tools.Recalls;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The recall watch (0.6.0): five feeds read into files, matched against the ledger by four tiers, kept as hits until put away, shown everywhere. */
class RecallsTest {

    static final ObjectMapper M = new ObjectMapper();
    static final String PERSON = "\"patron\":{\"did\":\"person\",\"name\":\"\",\"runtime\":\"cli\"}";
    HttpServer server; Map<String, String> savedBase = new HashMap<>(); Path savedOverride;

    @BeforeEach void stubFeeds(@TempDir Path tmp) throws Exception {
        savedBase.putAll(Recalls.BASE); savedOverride = Recalls.override;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        serve("/cpsc", "application/json", "[{\"RecallID\":1,\"RecallNumber\":\"26-001\",\"RecallDate\":\"2026-09-01T00:00:00\",\"Title\":\"Kestrel Recalls USB Hubs Due to Fire Hazard\",\"URL\":\"https://www.cpsc.gov/Recalls/2026/kestrel-hubs\","
                + "\"Description\":\"This recall involves Kestrel 7-port USB hubs, model KH-7, sold in grey and black.\",\"Products\":[{\"Name\":\"Kestrel USB hubs\",\"Model\":\"KH-7\",\"Type\":\"Hubs\"}],\"Manufacturers\":[{\"Name\":\"Kestrel Direct\"}],\"ProductUPCs\":[],\"Hazards\":[{\"Name\":\"fire\"}]},"
                + "{\"RecallID\":2,\"RecallNumber\":\"26-002\",\"RecallDate\":\"2026-09-15T00:00:00\",\"Title\":\"Brambleworks Recalls Notebooks\",\"URL\":\"https://www.cpsc.gov/Recalls/2026/brambleworks\",\"Description\":\"Hand-bound notebooks with a glue that irritates skin.\",\"Products\":[{\"Name\":\"Notebooks\",\"Model\":\"\"}],\"Manufacturers\":[{\"Name\":\"Brambleworks\"}],\"ProductUPCs\":[{\"UPC\":\"012345678905\"}],\"Hazards\":[]}]");
        serve("/sg/list", "application/xml", "<?xml version=\"1.0\"?><Safety-Gate><weeklyReport><report_language>en</report_language><reference>Report-2026-40</reference><publicationDate>09/10/2026</publicationDate><URL>" + base + "/sg/detail?language=en&amp;x=1</URL></weeklyReport></Safety-Gate>");
        serve("/sg/detail", "application/xml", "<?xml version=\"1.0\"?><Safety-Gate><report_language>en</report_language><report_date>09/10/2026</report_date><alert><order>1</order><caseNumber>A12/00001/26</caseNumber><reference>A12/00001/26</reference><category>Electrical appliances</category>"
                + "<product>HDMI cable</product><brand>Heron</brand><name>Braided HDMI cable 6ft</name><type_numberOfModel>HC-6B</type_numberOfModel><batchNumber>all</batchNumber><barcode>4006381333931</barcode><riskType>Fire</riskType><danger>The braided HDMI cable overheats at the plug.</danger><measures>Recall</measures><description>Braided HDMI cable, 6ft, black, sold online.</description><notifyingCountry>Germany</notifyingCountry><URLrecall>https://example.eu/recall/hc6b</URLrecall></alert>"
                + "<alert><order>2</order><caseNumber>A12/00002/26</caseNumber><reference>A12/00002/26</reference><category>Toys</category><product>Key ring with soft toy</product><brand>Unknown</brand><name>Fluffy keyring</name><type_numberOfModel>none</type_numberOfModel><barcode></barcode><danger>Small parts.</danger><description>A keyring.</description><notifyingCountry>France</notifyingCountry></alert></Safety-Gate>");
        serve("/fda/food/enforcement.json", "application/json", "{\"meta\":{},\"results\":[{\"recall_number\":\"F-0001-2026\",\"recall_initiation_date\":\"20260920\",\"product_description\":\"Heron Street Bakery almond croissants, 6 count, UPC 850012345678\",\"recalling_firm\":\"Heron Street Bakery\",\"reason_for_recall\":\"undeclared nuts\",\"code_info\":\"UPC 850012345678 lots 1-9\"}]}");
        serve("/fda/drug/enforcement.json", "application/json", "{\"meta\":{},\"results\":[]}");
        serve("/fda/device/enforcement.json", "application/json", "{\"meta\":{},\"results\":[]}");
        serve("/opss", "application/atom+xml", "<?xml version=\"1.0\"?><feed xmlns=\"http://www.w3.org/2005/Atom\"><entry><id>tag:gov.uk,2026:psr-0037</id><updated>2026-10-09T12:01:28+01:00</updated><link rel=\"alternate\" href=\"https://www.gov.uk/psr-0037\"/><title>Product Safety Report: Novelty squishes containing needles (2610-0037)</title><summary>Squishes sold online contained needles.</summary></entry></feed>");
        serve("/oecd", "application/xml", "<?xml version=\"1.0\"?><rss version=\"2.0\"><channel><title>Global portal</title><item><title>Recall of children's pyjamas</title><link>https://globalrecalls.oecd.org/x/1</link><guid>https://globalrecalls.oecd.org/x/1</guid><pubDate>Thu, 08 Oct 2026 00:00:00 GMT</pubDate><description>Country: Australia. Pyjamas failing flammability.</description></item></channel></rss>");
        server.start();
        Recalls.BASE.put("cpsc", base + "/cpsc"); Recalls.BASE.put("safetygate", base + "/sg/list"); Recalls.BASE.put("fda", base + "/fda"); Recalls.BASE.put("opss", base + "/opss"); Recalls.BASE.put("oecd", base + "/oecd");
        Recalls.override = tmp.resolve("recalls");
    }

    @AfterEach void restore() { server.stop(0); Recalls.BASE.putAll(savedBase); Recalls.override = savedOverride; }

    void serve(String path, String type, String body) {
        server.createContext(path, x -> { byte[] b = body.getBytes(StandardCharsets.UTF_8); x.getResponseHeaders().set("Content-Type", type); x.sendResponseHeaders(200, b.length); try (OutputStream o = x.getResponseBody()) { o.write(b); } });
    }

    static LibraryStore ledger(Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        String csv = "\"Website\",\"Order ID\",\"Order Date\",\"Currency\",\"Unit Price\",\"Total Amount\",\"ASIN\",\"Product Name\",\"Original Quantity\",\"Order Status\"\n"
                + "\"Amazon.com\",\"113-0000001-0000001\",\"2025-05-20T14:02:11Z\",\"USD\",\"21.50\",\"21.50\",\"B07EXAMPLE1\",\"Kestrel 7-Port USB Hub with Power Switch (KH-7)\",\"1\",\"Closed\"\n"
                + "\"Amazon.com\",\"113-0000002-0000002\",\"2025-06-01T14:02:11Z\",\"USD\",\"11.99\",\"11.99\",\"B07EXAMPLE2\",\"Heron 6ft HDMI Cable, braided, black\",\"1\",\"Closed\"\n"
                + "\"Amazon.com\",\"113-0000003-0000003\",\"2026-09-20T14:02:11Z\",\"USD\",\"34.00\",\"34.00\",\"B07EXAMPLE3\",\"Field Guide to the Moths of Hokkaido\",\"1\",\"Closed\"\n"
                + "\"Amazon.com\",\"113-0000004-0000004\",\"2026-09-30T14:02:11Z\",\"USD\",\"14.00\",\"14.00\",\"B07EXAMPLE4\",\"Kestrel Garden Pail, 10 L\",\"1\",\"Closed\"\n";
        Path f = tmp.resolve("Order History.csv"); Files.writeString(f, csv, StandardCharsets.UTF_8);
        Owned.importPath(store, f, "amazon", "");
        // a line with a barcode, as a reader that knows one would write it
        Owned.Line nb = new Owned.Line("O-barcode00001", "2026-03-12", "Brambleworks", "brambleworks.example", "BW-1042", "Hand-bound notebook, A6, grey linen", 2, "6.50", "USD", "15.99", Map.of("gtin", "0012345678905"), "purchase", "mail", "/x/Inbox", "<b1>", "2026-10-09T00:00:00Z", "exact");
        Files.writeString(Owned.file(store), nb.json().toString() + "\n", StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
        return store;
    }

    @Test
    void theFeedsAreReadAndMatchedByFourTiersAndAHitWaitsUntilPutAway(@TempDir Path tmp) throws Exception {
        LibraryStore store = ledger(tmp);
        Recalls.Checked c = Recalls.check(store, true);
        assertEquals(2 + 2 + 1 + 1 + 1, c.notices(), c.feeds());
        assertTrue(c.feeds().contains("cpsc: 2") && c.feeds().contains("safetygate: 2") && c.feeds().contains("fda: 1"), c.feeds());
        List<Recalls.Hit> hits = Recalls.hits(store, true);
        Map<String, Recalls.Hit> byLine = new HashMap<>(); for (Recalls.Hit h : hits) byLine.put(h.lineTitle(), h);
        assertEquals(3, hits.size(), hits.toString());
        Recalls.Hit hub = byLine.get("Kestrel 7-Port USB Hub with Power Switch (KH-7)");
        assertNotNull(hub, "the hub: brand and model number"); assertEquals("high", hub.tier()); assertEquals("cpsc", hub.feed()); assertTrue(hub.why().contains("KH-7"), hub.why());
        Recalls.Hit cable = byLine.get("Heron 6ft HDMI Cable, braided, black");
        assertNotNull(cable, "the cable: brand and the words of the name"); assertEquals("medium", cable.tier(), cable.why()); assertEquals("safetygate", cable.feed()); assertEquals("2026-10-09", cable.noticeDate(), "the Safety Gate's day/month/year read as a date");
        Recalls.Hit notebook = byLine.get("Hand-bound notebook, A6, grey linen");
        assertNotNull(notebook, "the notebook: its barcode"); assertEquals("exact", notebook.tier());
        assertNull(byLine.get("Kestrel Garden Pail, 10 L"), "the same brand with no model and no shared words is no hit");
        assertTrue(Recalls.wholeToken("vans a-7000, t-5000 and t-6000", "t-5000") && !Recalls.wholeToken("vans a-7000, t-5000 and t-6000", "t-500"), "a model number matches as a whole token: T-500 is not T-5000");
        assertNull(byLine.get("Field Guide to the Moths of Hokkaido"), "nothing concerns the book");
        Recalls.Checked again = Recalls.check(store, false);
        assertEquals(0, again.newHits(), "a second check adds nothing"); assertTrue(again.feeds().contains("(fresh)"), "a fresh feed is not fetched again: " + again.feeds());
        assertTrue(Recalls.dismiss(store, hub.id())); assertFalse(Recalls.dismiss(store, hub.id()), "put away once");
        assertEquals(2, Recalls.hits(store, true).size()); assertEquals(3, Recalls.hits(store, false).size());
        assertTrue(Recalls.nightly(store).contains("2 waiting"), Recalls.nightly(store));
        List<String> told = Recalls.tell(store);
        assertEquals(2, told.size()); assertTrue(told.get(0).startsWith("A recall notice may concern something you own"), told.get(0));
        LibraryStore empty = new LibraryStore(tmp.resolve("empty")); empty.init();
        assertEquals("nothing owned, nothing to match", Recalls.nightly(empty));
    }

    @Test
    void everySurfaceShowsTheHits(@TempDir Path tmp) throws Exception {
        LibraryStore store = ledger(tmp);
        LibraryProtocol p = new LibraryProtocol(store);
        ObjectNode r = p.owned((ObjectNode) M.readTree("{\"op\":\"recalls\",\"check\":true," + PERSON + "}"));
        assertEquals(3, r.path("count").asInt(), r.toString()); assertTrue(r.path("summary").asText().contains("may concern"), r.path("summary").asText());
        String id = r.path("hits").get(0).path("id").asText();
        ObjectNode inbox = p.inbox((ObjectNode) M.readTree("{\"op\":\"list\"," + PERSON + "}"));
        assertEquals(3, inbox.path("recalls").size(), "the inbox carries the open hits beside the claims");
        LibrarianDaemon d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1);
        try {
            HttpClient c = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
            String base = "http://127.0.0.1:" + d.port();
            HttpResponse<String> owned = c.send(HttpRequest.newBuilder(URI.create(base + "/owned")).build(), HttpResponse.BodyHandlers.ofString());
            assertTrue(owned.body().contains("Recall notices that may concern what you own") && owned.body().contains("Put away") && owned.body().contains("KH-7"), owned.body());
            HttpResponse<String> inboxPage = c.send(HttpRequest.newBuilder(URI.create(base + "/inbox")).build(), HttpResponse.BodyHandlers.ofString());
            assertTrue(inboxPage.body().contains("3 recall notice(s) may concern something you own"), inboxPage.body());
            HttpResponse<String> put = c.send(HttpRequest.newBuilder(URI.create(base + "/owned")).header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString("dismiss=" + id)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(303, put.statusCode(), put.body());
            assertEquals(2, Recalls.hits(store, true).size(), "put away from the page");
        } finally { d.stop(); }
        ProtocolError gone = assertThrows(ProtocolError.class, () -> p.owned((ObjectNode) M.readTree("{\"op\":\"recalls\",\"hit\":\"" + id + "\"," + PERSON + "}")));
        assertEquals("not_found", gone.code, "an id already put away is refused by name");
        assertEquals(2, p.owned((ObjectNode) M.readTree("{\"op\":\"recalls\"," + PERSON + "}")).path("count").asInt(), "and the rest are listed");
    }
}
