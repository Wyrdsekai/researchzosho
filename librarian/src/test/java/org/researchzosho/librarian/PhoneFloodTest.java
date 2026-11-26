package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketException;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A flood slows the phone door and nothing else (2026-11-25): its open exchanges have a ceiling, past which a connection is closed at once
 * while the library's own port answers as before; a body that comes too slowly is cut by the watchdog, a small one in seconds, a message's
 * pictures below a floor rate; a body larger than the door takes is refused before it is read; a sender without a pairing is held to its
 * limit. The ceilings are made small here so the test does not need a flood's numbers.
 */
class PhoneFloodTest {

    static Socket slow(int port, String head) throws Exception {
        Socket so = new Socket("127.0.0.1", port);
        so.getOutputStream().write(head.getBytes(StandardCharsets.ISO_8859_1)); so.getOutputStream().flush();
        return so;
    }

    /** Whether the server closed the connection within {@code ms}: the read sees its end, or a reset. */
    static boolean closedWithin(Socket so, long ms) throws Exception {
        so.setSoTimeout((int) ms);
        try { InputStream in = so.getInputStream(); while (in.read() >= 0) { } return true; }
        catch (SocketTimeoutException stillOpen) { return false; }
        catch (SocketException reset) { return true; }
    }

    @Test
    void theDoorsCeilingHoldsWhileTheLibrarysOwnPortGoesOn(@TempDir Path tmp) throws Exception {
        int open = PhoneDoor.OPEN_MAX;
        List<Socket> flood = new ArrayList<>();
        try (PhoneStandIns s = new PhoneStandIns(tmp)) {
            PhoneDoor.OPEN_MAX = 6;
            int port = s.d.phoneDoorPort();
            for (int i = 0; i < 6; i++) flood.add(slow(port, "GET /m HTTP/1.1\r\nHost: x\r\n"));   // headers that never end
            for (int i = 0; i < 50 && s.d.phoneDoor().openNow() < 6; i++) Thread.sleep(20);
            assertEquals(6, s.d.phoneDoor().openNow(), "six exchanges held open by slow senders");
            // one more: closed at once, answered nothing
            Socket over = slow(port, "GET /m HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n");
            assertTrue(closedWithin(over, 2000), "the door closes what it cannot take");
            // the library's own port answers as before, at once
            for (int i = 0; i < 20; i++) {
                long t0 = System.nanoTime();
                assertEquals(200, s.get(s.main(), "/healthz", null).statusCode());
                assertTrue((System.nanoTime() - t0) / 1_000_000 < 1000, "the library's port is not held up by the door's flood");
            }
            // the slow senders go: the door answers again
            for (Socket so : flood) so.close();
            flood.clear();
            for (int i = 0; i < 100 && s.d.phoneDoor().openNow() > 0; i++) Thread.sleep(20);
            assertEquals(200, s.get(s.door(), "/m", null).statusCode());
        } finally { PhoneDoor.OPEN_MAX = open; for (Socket so : flood) so.close(); }
    }

    @Test
    void aSlowBodyIsCutByTheWatchdogAndALargeOneIsRefusedBeforeItIsRead(@TempDir Path tmp) throws Exception {
        long small = PhoneDoor.SMALL_BODY_MS, grace = PhoneDoor.UPLOAD_GRACE_MS, floor = PhoneDoor.UPLOAD_FLOOR;
        try (PhoneStandIns s = new PhoneStandIns(tmp)) {
            PhoneDoor.SMALL_BODY_MS = 300; PhoneDoor.UPLOAD_GRACE_MS = 300; PhoneDoor.UPLOAD_FLOOR = 1 << 20;
            int port = s.d.phoneDoorPort();
            Phones.Made m = Phones.pairingCode("pixel");
            // a pairing whose body never comes whole: cut, and the code not spent
            Socket pair = slow(port, "POST /m/pair HTTP/1.1\r\nHost: x\r\nX-RZ-Phone: 1\r\nSec-Fetch-Site: same-origin\r\nContent-Type: application/x-www-form-urlencoded\r\nContent-Length: 200\r\n\r\nc=" + m.code().substring(0, 5));
            long t0 = System.currentTimeMillis();
            assertTrue(closedWithin(pair, 3000), "a small body that does not come within its seconds is cut");
            assertTrue(System.currentTimeMillis() - t0 < 2500);
            assertEquals("", Phones.usedBy(m.code()), "nothing of it was done");
            // a paired phone's pictures, sent far below the floor: cut too
            String cookie = s.pair("tablet");
            String head = "POST /m/ask HTTP/1.1\r\nHost: x\r\nX-RZ-Phone: 1\r\nSec-Fetch-Site: same-origin\r\nCookie: " + PhoneDoor.COOKIE + "=" + cookie + "\r\nContent-Type: multipart/form-data; boundary=b\r\nContent-Length: 5000000\r\n\r\n--b\r\nContent-Disposition: form-data; name=\"file\"; filename=\"p.png\"\r\n\r\n";
            Socket up = slow(port, head);
            OutputStream o = up.getOutputStream();
            long t1 = System.currentTimeMillis();
            boolean cut = false;
            try { for (int i = 0; i < 40 && !cut; i++) { o.write(new byte[64]); o.flush(); Thread.sleep(100); } } catch (IOException closed) { cut = true; }
            assertTrue(cut || closedWithin(up, 1500), "an upload below the floor is cut");
            assertTrue(System.currentTimeMillis() - t1 < 4500, "within its grace and a few looks");
            assertTrue(s.d.phoneDoor().turning.isEmpty(), "the phone's turn is free again");
            // larger than the door takes: closed by what the request says of itself, before a byte is read and with no answer (an answer would
            // wait for the whole body: the JDK's server sends none before it); the page says the size in its own words before it sends
            for (String req : new String[]{"POST /m/pair HTTP/1.1\r\nHost: x\r\nX-RZ-Phone: 1\r\nSec-Fetch-Site: same-origin\r\nContent-Type: application/x-www-form-urlencoded\r\nContent-Length: 5000\r\n\r\n",
                    "POST /m/ask HTTP/1.1\r\nHost: x\r\nX-RZ-Phone: 1\r\nSec-Fetch-Site: same-origin\r\nCookie: " + PhoneDoor.COOKIE + "=" + cookie + "\r\nContent-Type: multipart/form-data; boundary=b\r\nContent-Length: 999999999\r\n\r\n",
                    "POST /nothing HTTP/1.1\r\nHost: x\r\nContent-Length: 100000\r\n\r\n"}) {
                Socket big = slow(port, req);
                long t2 = System.currentTimeMillis();
                assertTrue(closedWithin(big, 2000) && System.currentTimeMillis() - t2 < 1500, req.substring(0, 20));
            }
            // a refusal of a small body that never comes: the body is waited for a small body's seconds, then the connection closes
            Socket never = slow(port, "POST /nothing HTTP/1.1\r\nHost: x\r\nContent-Length: 200\r\n\r\n");
            assertTrue(closedWithin(never, 3000), "a body declared and never sent holds nothing for long");
            // the control: a small body that comes is answered
            Socket comes = slow(port, "POST /nothing HTTP/1.1\r\nHost: x\r\nConnection: close\r\nContent-Length: 5\r\n\r\nhello");
            comes.setSoTimeout(3000);
            assertTrue(new String(comes.getInputStream().readAllBytes(), StandardCharsets.UTF_8).startsWith("HTTP/1.1 404"));
        } finally { PhoneDoor.SMALL_BODY_MS = small; PhoneDoor.UPLOAD_GRACE_MS = grace; PhoneDoor.UPLOAD_FLOOR = floor; }
    }

    @Test
    void aSenderWithoutAPairingIsHeldToItsLimitAndAPairedPhoneIsNot(@TempDir Path tmp) throws Exception {
        try (PhoneStandIns s = new PhoneStandIns(tmp)) {
            String cookie = s.pair("pixel");   // one request of the sender's own
            int ok = 0, held = 0;
            for (int i = 0; i < 25; i++) { int c = s.get(s.door(), "/m/state", null).statusCode(); if (c == 200) ok++; else if (c == 429) held++; }
            assertTrue(ok <= 20 && held >= 5, "a burst of twenty, then held: " + ok + " answered, " + held + " held");
            for (int i = 0; i < 30; i++) assertEquals(200, s.get(s.door(), "/m/state", cookie).statusCode(), "a paired phone is not counted by sender");
            assertTrue(s.get(s.door(), "/nothing", null).statusCode() == 404, "an address the door does not have is the plain 404 and costs nothing to count");
        }
    }
}
