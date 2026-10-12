package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.mcp.McpServer;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** The ledger of what the person owns (0.6.0): three kinds of record read, duplicates told apart, and every surface that shows it. */
class OwnedTest {

    static final ObjectMapper M = new ObjectMapper();
    static final String PERSON = "\"patron\":{\"did\":\"person\",\"name\":\"\",\"runtime\":\"cli\"}";

    static ObjectNode args(String json) throws Exception { return (ObjectNode) M.readTree(json); }

    /** A mailbox as Thunderbird writes one: eight invented mails, of which four are purchases. */
    static String mailbox() {
        String qp = "Amazon.com Order Confirmation\nOrder #117-2233445-6677889\n\nHello,\n\nThank you for shopping with us. We=E2=80=99ll send a confirmation once your=\n items have shipped.\n\n"
                + "Order Details\n               2 x Kestrel 7-Port USB Hub with Power Switch (KH-7)\n               $21.50\n               Sold by: Kestrel Direct\n"
                + "               Field Guide to the Moths of Hokkaido\n               $34.00\n               Sold by: Amazon.com Services\n\n"
                + "              Item Subtotal: $77.00\n              Shipping & Handling: $0.00\n              Total Before Tax: $77.00\n              Estimated Tax: $4.81\n              Order Total: $81.81\n";
        String amazon = "From - Mon Mar 04 10:00:00 2024\nFrom: \"Amazon.com\" <auto-confirm@amazon.com>\nTo: reader@example.net\nSubject: Your Amazon.com order of \"2\" x Kestrel 7-Port USB Hub... and 1 more item.\n"
                + "Date: Mon, 4 Mar 2024 10:00:00 +0000\nMessage-ID: <a1@amazon.com>\nContent-Type: multipart/alternative; boundary=\"b1\"\n\n--b1\nContent-Type: text/plain; charset=UTF-8\nContent-Transfer-Encoding: quoted-printable\n\n" + qp + "\n--b1\nContent-Type: text/html; charset=UTF-8\n\n<html><body>the same, as a table</body></html>\n--b1--\n";
        String shipped = "From - Wed Mar 06 10:00:00 2024\nFrom: \"Amazon.com\" <shipment-tracking@amazon.com>\nTo: reader@example.net\nSubject: Your Amazon.com order #117-2233445-6677889 has shipped\nDate: Wed, 6 Mar 2024 10:00:00 +0000\nMessage-ID: <a2@amazon.com>\nContent-Type: text/plain; charset=UTF-8\n\n"
                + "Your package is on its way.\nShipment Total: $81.81\nTrack your package at amazon.com\n";
        String rakutenSubject = "=?UTF-8?B?" + Base64.getEncoder().encodeToString("【楽天市場】注文内容ご確認（自動配信メール）".getBytes(StandardCharsets.UTF_8)) + "?=";
        String rakutenBody = Base64.getEncoder().encodeToString(("本メールはお客様のご注文情報が楽天市場のサーバに到達した時点で送信される、自動配信メールです。\n\n[注文番号] 123456-20240310-0098765432\n\n"
                + "鉄瓶 南部鉄器 急須 0.6L 霰\n価格 8200(円) x 1(個) = 8200(円) (税込、送料別)\n消費税 0(円)\n合計 8200(円)別途送料がかかります\n").getBytes(StandardCharsets.UTF_8));
        String rakuten = "From - Sun Mar 10 10:00:00 2024\nFrom: order@rakuten.co.jp\nTo: reader@example.net\nSubject: " + rakutenSubject + "\nDate: Sun, 10 Mar 2024 10:00:00 +0900\nMessage-ID: <r1@rakuten.co.jp>\nContent-Type: text/plain; charset=UTF-8\nContent-Transfer-Encoding: base64\n\n" + rakutenBody + "\n";
        String paypal = "From - Tue Mar 12 10:00:00 2024\nFrom: \"service@paypal.com\" <service@paypal.com>\nTo: reader@example.net\nSubject: Receipt for Your Payment to brambleworks\nDate: Tue, 12 Mar 2024 10:00:00 +0000\nMessage-ID: <p1@paypal.com>\nContent-Type: text/plain; charset=UTF-8\n\n"
                + "You sent a payment of $15.99 USD to brambleworks.\n\nItem Title: Hand-bound notebook, A6, grey linen\nQuantity: 2\nPrice: $6.50 USD\nSubtotal $13.00 USD\nShipping: $2.99 USD\nTotal Amount: $15.99 USD\n";
        String toast = "From - Thu Mar 14 10:00:00 2024\nFrom: \"'Toast' via receipts\" <receipts@group.example.net>\nTo: reader@example.net\nSubject: Your receipt from Heron Street Bakery\nDate: Thu, 14 Mar 2024 10:00:00 +0000\nMessage-ID: <t1@toasttab.com>\nContent-Type: text/html; charset=UTF-8\n\n"
                + "<html><body><table><tr><td>Almond croissant</td><td>$4.50</td></tr><tr><td>Oat milk latte</td><td>$5.25</td></tr><tr><td>Total</td><td>$9.75</td></tr></table></body></html>\n";
        String reply = "From - Fri Mar 15 10:00:00 2024\nFrom: colleague@example.org\nTo: reader@example.net\nSubject: Re: invoice for the venue\nDate: Fri, 15 Mar 2024 10:00:00 +0000\nMessage-ID: <c1@example.org>\nContent-Type: text/plain; charset=UTF-8\n\nTotal: $1,200.00 — can you approve?\n";
        String self = "From - Sat Mar 16 10:00:00 2024\nFrom: reader@example.net\nTo: reader@example.net\nSubject: order for the garden\nDate: Sat, 16 Mar 2024 10:00:00 +0000\nMessage-ID: <s1@example.net>\nContent-Type: text/plain; charset=UTF-8\n\nremember: Total $40 for the pots\n";
        String carrier = "From - Sun Mar 17 10:00:00 2024\nFrom: TrackingUpdates@fedex.com\nTo: reader@example.net\nSubject: FedEx Shipment 123456789012: Your package has been delivered\nDate: Sun, 17 Mar 2024 10:00:00 +0000\nMessage-ID: <f1@fedex.com>\nContent-Type: text/plain; charset=UTF-8\n\nDelivered.\n";
        return amazon + shipped + rakuten + paypal + toast + reply + self + carrier;
    }

    @Test
    void aMailboxBecomesPurchasesWithTheirShapesReadAndTheNoisePassedOver(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Path mbox = tmp.resolve("Inbox"); Files.writeString(mbox, mailbox(), StandardCharsets.UTF_8);
        Owned.Summary s = Owned.importPath(store, mbox, "auto", "");
        assertEquals("mail", s.kind()); assertEquals(8, s.messagesRead());
        assertEquals(1, s.shipmentsSeen(), "the carrier's notice is counted, not read"); assertEquals(1, s.ownMail(), "mail to oneself is passed over");
        List<Owned.Line> lines = Owned.lines(store);
        String all = lines.toString();
        assertTrue(all.contains("Kestrel 7-Port USB Hub") && all.contains("Moths of Hokkaido"), "Amazon's confirmation names both items: " + all);
        Owned.Line hub = lines.stream().filter(l -> l.title().contains("Kestrel")).findFirst().orElseThrow();
        assertEquals(2, hub.qty()); assertEquals("21.50", hub.unitPrice()); assertEquals("81.81", hub.total()); assertEquals("USD", hub.currency());
        assertEquals("117-2233445-6677889", hub.orderId()); assertEquals("2024-03-04", hub.date()); assertEquals("Amazon.com", hub.shop());
        assertFalse(all.contains("an order from Amazon"), "the shipped notice adds nothing to an order already named: " + all);
        Owned.Line kettle = lines.stream().filter(l -> l.title().contains("鉄瓶")).findFirst().orElseThrow();
        assertEquals("8200", kettle.unitPrice()); assertEquals("8200", kettle.total()); assertEquals("JPY", kettle.currency()); assertEquals("楽天市場", kettle.shop()); assertEquals("123456-20240310-0098765432", kettle.orderId());
        Owned.Line notebook = lines.stream().filter(l -> l.title().contains("notebook")).findFirst().orElseThrow();
        assertEquals(2, notebook.qty()); assertEquals("6.50", notebook.unitPrice()); assertEquals("15.99", notebook.total()); assertEquals("PayPal", notebook.shop());
        Owned.Line bakery = lines.stream().filter(l -> l.shop().equals("Heron Street Bakery")).findFirst().orElseThrow(() -> new AssertionError("a relayed receipt's shop is the one its subject names: " + all));
        assertEquals("a receipt from Heron Street Bakery", bakery.title(), "a receipt for a meal is one line"); assertEquals("9.75", bakery.total()); assertEquals("receipt", bakery.kind());
        assertFalse(all.contains("venue") || all.contains("garden"), "a reply and a note to oneself are not orders: " + all);
        Owned.Summary again = Owned.importPath(store, mbox, "mail", "");
        assertEquals(0, again.linesAdded()); assertEquals(lines.size(), again.linesAlready());
        assertEquals(lines.size(), Owned.lines(store).size(), "an import run twice adds nothing");
    }

    @Test
    void theZipADataRequestReturnsAndAStatementAreRead(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        String csv = "﻿\"Website\",\"Order ID\",\"Order Date\",\"Purchase Order Number\",\"Currency\",\"Unit Price\",\"Unit Price Tax\",\"Shipping Charge\",\"Total Discounts\",\"Total Owed\",\"ASIN\",\"Product Name\",\"Quantity\",\"Order Status\"\n"
                + "\"Amazon.com\",\"113-0000001-0000001\",\"2019-05-20T14:02:11Z\",\"\",\"USD\",\"11.99\",\"0.72\",\"0\",\"0\",\"50.84\",\"B07EXAMPLE1\",\"Heron 6ft HDMI Cable, braided\",\"4\",\"Closed\"\n"
                + "\"Amazon.co.jp\",\"250-0000002-0000002\",\"2021-11-02T01:00:00Z\",\"\",\"JPY\",\"3280\",\"298\",\"0\",\"0\",\"3578\",\"B09EXAMPLE2\",\"南部鉄器 ミニ鉄瓶 0.3L\",\"1\",\"Closed\"\n"
                + "\"Amazon.com\",\"113-0000003-0000003\",\"2020-01-09T20:00:00Z\",\"\",\"USD\",\"199.00\",\"0\",\"0\",\"0\",\"0\",\"B08EXAMPLE3\",\"A thing that was cancelled\",\"1\",\"Cancelled\"\n"
                + "\"Amazon.com\",\"113-0000001-0000001\",\"2019-05-20T14:02:11Z\",\"\",\"USD\",\"11.99\",\"0.72\",\"0\",\"0\",\"12.71\",\"B07EXAMPLE1\",\"Heron 6ft HDMI Cable, braided\",\"1\",\"Closed\"\n"   // the fifth cable, shipped apart
                + "\"www.audible.com\",\"D01-0000004-0000004\",\"2022-06-01T09:00:00Z\",\"\",\"USD\",\"14.95\",\"0\",\"0\",\"0\",\"14.95\",\"B0AEXAMPLE4\",\"The Kestrel's Year (audiobook)\",\"1\",\"Closed\"\n";
        Path zip = tmp.resolve("Your Orders.zip");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(zip))) {
            z.putNextEntry(new ZipEntry("Retail.OrderHistory.2/Retail.OrderHistory.2.csv")); z.write(csv.getBytes(StandardCharsets.UTF_8)); z.closeEntry();
            z.putNextEntry(new ZipEntry("Retail.CustomerProfile.1/Retail.CustomerProfile.1.csv")); z.write("\"Name\",\"Email\"\n\"A Reader\",\"reader@example.net\"\n".getBytes(StandardCharsets.UTF_8)); z.closeEntry();
        }
        Owned.Summary s = Owned.importPath(store, zip, "auto", "");
        assertEquals("amazon", s.kind()); assertEquals(3, s.linesAdded(), s.notes().toString()); assertEquals("2019-05-20", s.firstDate()); assertEquals("2022-06-01", s.lastDate());
        Owned.Line cable = Owned.find(store, "hdmi", "", "", 10).get(0);
        assertEquals(5, cable.qty(), "a row per shipment of one order line: the quantities add up"); assertEquals("11.99", cable.unitPrice()); assertEquals("63.55", cable.total()); assertEquals("USD", cable.currency());
        assertEquals("Audible", Owned.find(store, "audiobook", "", "", 10).get(0).shop(), "the Website column, as a shop's name");
        assertEquals("B07EXAMPLE1", cable.ids().get("asin")); assertEquals("exact", cable.confidence()); assertEquals("Amazon.com", cable.shop());
        assertEquals("Amazon.co.jp", Owned.find(store, "鉄瓶", "", "", 10).get(0).shop());
        assertTrue(Owned.find(store, "cancelled", "", "", 10).isEmpty(), "a cancelled order was never owned");

        Path bank = tmp.resolve("statement.csv");
        Files.writeString(bank, "Date,Description,Amount,Balance\n2024-02-01,HERON STREET BAKERY CAMBRIDGE,-9.75,1000.25\n2024-02-03,PAYROLL DEPOSIT,2500.00,3500.25\n2024-02-05,KESTREL DIRECT,-43.00,3457.25\n", StandardCharsets.UTF_8);
        Owned.Summary b = Owned.importPath(store, bank, "auto", "USD");
        assertEquals("bank", b.kind()); assertEquals(2, b.linesAdded(), "the deposit is not a payment");
        Owned.Line paid = Owned.find(store, "kestrel", "2024", "", 10).get(0);
        assertEquals("payment", paid.kind()); assertEquals("43.00", paid.total()); assertEquals("USD", paid.currency()); assertEquals("KESTREL DIRECT", paid.shop());

        ObjectNode st = Owned.status(store);
        assertEquals(5, st.path("lines").asInt()); assertEquals(3, st.path("purchases").asInt()); assertEquals(2, st.path("payments").asInt());
        assertEquals(2, st.path("sources").size());
        assertEquals(2, Owned.forget(store, bank.toAbsolutePath().normalize().toString()));
        assertEquals(3, Owned.lines(store).size()); assertEquals(1, Owned.sources(store).size());
        assertFalse(Owned.alreadyOwned(store, "Heron braided HDMI cable 6ft").isEmpty(), "the thing is owned under a slightly different name");
        assertTrue(Owned.alreadyOwned(store, "a kettle").isEmpty());
    }

    @Test
    void everySurfaceShowsTheLedgerAndOnlyTheOwnerImports(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        LibraryProtocol p = new LibraryProtocol(store);
        Path mbox = tmp.resolve("Inbox"); Files.writeString(mbox, mailbox(), StandardCharsets.UTF_8);
        ObjectNode imported = p.owned(args("{\"op\":\"import\",\"path\":\"" + mbox.toString().replace("\\", "\\\\") + "\"," + PERSON + "}"));
        assertTrue(imported.path("lines_added").asInt() >= 4, imported.toString());
        assertTrue(imported.path("summary").asText().startsWith("Read 8 messages"), imported.path("summary").asText());
        ObjectNode found = p.owned(args("{\"op\":\"find\",\"words\":\"usb hub\"," + PERSON + "}"));
        assertEquals(1, found.path("count").asInt()); assertEquals("2024-03-04", found.path("lines").get(0).path("date").asText());
        ObjectNode status = p.owned(args("{\"op\":\"status\"," + PERSON + "}"));
        assertTrue(status.path("summary").asText().contains("begin 2024-03-04"), "where the records begin, apart from when the account was opened: " + status.path("summary").asText());
        Patrons.setDefault(store, Patrons.Level.write);
        ProtocolError e = assertThrows(ProtocolError.class, () -> p.owned(args("{\"op\":\"import\",\"path\":\"" + mbox.toString().replace("\\", "\\\\") + "\",\"patron\":{\"did\":\"did:key:zReader\",\"name\":\"r\",\"runtime\":\"x\"}}")));
        assertEquals("forbidden", e.code, "a reader with write access still does not import the person's records");
        assertEquals(1, p.owned(args("{\"op\":\"find\",\"words\":\"usb hub\",\"patron\":{\"did\":\"did:key:zReader\",\"name\":\"r\",\"runtime\":\"x\"}}")).path("count").asInt(), "and may read them (privacy is access)");
        boolean mcp = false;
        for (JsonNode t : McpServer.allTools()) if (t.path("name").asText().equals("library_owned")) mcp = true;
        assertTrue(mcp && Librarian.TOOLS.contains("library_owned"), "offered to programs and to the chat");
        assertTrue(Pages.isPage("/owned"));
        LibrarianDaemon d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1);
        try {
            HttpClient c = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
            HttpResponse<String> page = c.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + d.port() + "/owned?q=hub")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, page.statusCode(), page.body()); assertTrue(page.body().contains("Kestrel 7-Port USB Hub"), page.body());
        } finally { d.stop(); }
        ByteArrayOutputStream out = new ByteArrayOutputStream(); var old = System.out; System.setOut(new java.io.PrintStream(out, true, StandardCharsets.UTF_8));
        try { assertEquals(0, LibrarianCli.ownedCommand(store, new String[]{"librarian", "owned", "find", "moths"})); } finally { System.setOut(old); }
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("Moths of Hokkaido"), out.toString(StandardCharsets.UTF_8));
    }

    @Test
    void theMailReaderDecodesWhatMailActuallyCarries() {
        assertEquals("【楽天市場】注文内容", Mime.decodeHeader("=?UTF-8?B?44CQ5qW95aSp5biC5aC044CR5rOo5paH5YaF5a65?="));
        assertEquals("Your order has shipped", Mime.decodeHeader("=?utf-8?q?Your_order_has?= =?utf-8?q?_shipped?="));
        assertEquals("a\nb\nc=d", new String(Mime.quotedPrintable("a\nb=\n\nc=3Dd".getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8).replace("\n\n", "\n"), "line ends stay, soft breaks and =3D go");
        java.util.regex.Matcher shop = Owned.SUBJECT_SHOP.matcher("Your order from Heron Street Bakery has been scheduled");
        assertTrue(shop.find()); assertEquals("Heron Street Bakery", shop.group(1), "the shop's name stops where the sentence goes on");
        assertEquals("Almond croissant $4.50\nTotal $9.75\n", Mime.htmlToText("<table><tr><td>Almond croissant</td><td>$4.50</td></tr><tr><td>Total</td><td>$9.75</td></tr></table>"));
    }
}
