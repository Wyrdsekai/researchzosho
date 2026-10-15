package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Accounts (0.6.1): a password kept as a stretched hash and checked; the sign-in name without case or spacing; sessions, reset, too many
 * tries; the one-time code against RFC 6238's vector; the QR code against a reference encoder's modules; the CBOR reader.
 */
class AccountsTest {

    @Test
    void aPasswordIsKeptAsAHashAndChecked() {
        String h = Passwords.hash("correct horse battery");
        assertTrue(h.startsWith("pbkdf2-sha256$210000$") && !h.contains("horse"), h);
        assertTrue(Passwords.check(h, "correct horse battery"));
        assertFalse(Passwords.check(h, "correct horse batter"));
        assertFalse(Passwords.check("nonsense", "correct horse battery"));
        assertNotEquals(h, Passwords.hash("correct horse battery"), "a salt of its own each time");
        assertEquals("A password needs at least 8 characters.", Passwords.why("short"));
        assertNull(Passwords.why("long enough"));
    }

    @Test
    void anAccountSignsInByNameWithoutCaseOrSpacing(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Patrons.set(store, "did:key:ann", "Ann  Example", Patrons.Level.read);
        Accounts.Account a = Accounts.create(store, "did:key:ann", "Ann  Example", "ann-secret-1");
        assertEquals("Ann Example", a.name()); assertEquals("ann example", a.login());
        assertNotNull(Accounts.signIn(store, "  ANN   example ", "ann-secret-1"));
        assertNull(Accounts.signIn(store, "Ann Example", "ann-secret-2"));
        assertNull(Accounts.signIn(store, "Nobody", "ann-secret-1"));
        IOException taken = assertThrows(IOException.class, () -> Accounts.create(store, "did:key:other", "ann example", "whatever-1"));
        assertTrue(taken.getMessage().contains("already in use"), taken.getMessage());
        assertEquals("password", a.ways());
        // sessions: a secret the browser keeps, its hash here; sign out one, or all
        String s1 = Accounts.newSession(store, "did:key:ann", "Firefox on Linux"), s2 = Accounts.newSession(store, "did:key:ann", "Safari on an iPhone");
        assertEquals("did:key:ann", Accounts.bySession(store, s1).did());
        assertFalse(Files.readString(Accounts.file(store)).contains(s1), "the secret itself is not on disk");
        Accounts.signOut(store, s1);
        assertNull(Accounts.bySession(store, s1)); assertNotNull(Accounts.bySession(store, s2));
        Accounts.signOutAll(store, "did:key:ann");
        assertNull(Accounts.bySession(store, s2));
        // reset: the name stays, every way in goes
        Accounts.setCode(store, "did:key:ann", Totp.newSecret());
        assertEquals("password, one-time code", Accounts.byDid(store, "did:key:ann").ways());
        Accounts.Account r = Accounts.reset(store, "did:key:ann");
        assertEquals("Ann Example", r.name()); assertFalse(r.hasPassword()); assertFalse(r.secondStep());
        assertEquals("no way to sign in yet", r.ways());
        Accounts.remove(store, "did:key:ann");
        assertNull(Accounts.byDid(store, "did:key:ann"));
    }

    @Test
    void tooManyWrongTriesAtANameMakeTheNextOnesWait(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Accounts.create(store, Accounts.OWNER, "Owner Tries", "owner-secret-1");
        for (int i = 0; i < Accounts.TRIES; i++) assertNull(Accounts.signIn(store, "Owner Tries", "wrong-" + i));
        IOException e = assertThrows(IOException.class, () -> Accounts.signIn(store, "Owner Tries", "owner-secret-1"));
        assertTrue(e.getMessage().startsWith("Too many tries."), e.getMessage());
        Accounts.cleared("pw:owner tries");
        assertNotNull(Accounts.signIn(store, "Owner Tries", "owner-secret-1"));
    }

    @Test
    void theOneTimeCodeFollowsTheStandardsVectorAndIsNeverTakenTwice(@TempDir Path tmp) throws Exception {
        // RFC 6238, appendix B: the SHA-1 secret "12345678901234567890"; at T = 59 s the eight-digit code is 94287082
        String secret = Totp.base32("12345678901234567890".getBytes(StandardCharsets.US_ASCII));
        assertEquals("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", secret);
        assertEquals("287082", Totp.code(secret, 59 / 30));
        assertEquals("081804", Totp.code(secret, 1111111109L / 30));
        assertEquals(1, Totp.stepOf(secret, "287082", 59_000L));
        assertEquals(1, Totp.stepOf(secret, "287 082", 59_000L + 30_000L), "a step either side is taken, for a clock a little off");
        assertEquals(-1, Totp.stepOf(secret, "287082", 59_000L + 90_000L));
        assertEquals(-1, Totp.stepOf(secret, "28708", 59_000L));
        assertTrue(Totp.uri("ResearchZosho Home", "Ann Example", secret).startsWith("otpauth://totp/ResearchZosho%20Home:Ann%20Example?secret=" + secret + "&issuer=ResearchZosho%20Home"));
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Accounts.create(store, Accounts.OWNER, "Owner", "owner-secret-1");
        Accounts.setCode(store, Accounts.OWNER, secret);
        long now = System.currentTimeMillis();
        String code = Totp.code(secret, now / 1000 / Totp.PERIOD);
        assertTrue(Accounts.takeCode(store, Accounts.OWNER, code));
        assertFalse(Accounts.takeCode(store, Accounts.OWNER, code), "the same code again is refused");
        assertFalse(Accounts.takeCode(store, Accounts.OWNER, "000000"));
    }

    static final String HELLO_WORLD = String.join("\n",
            "111111101100101111111", "100000100001001000001", "101110100101001011101", "101110101001001011101", "101110101110101011101", "100000101001001000001", "111111101010101111111",
            "000000001001100000000", "100010111111011111001", "000100001011100001111", "001111110011011010010", "111110001100010000000", "111110101010101100110", "000000001010111101011",
            "111111101110101011010", "100000100101110110011", "101110101101011000110", "101110100100100011011", "101110100111000111000", "100000100001010000000", "111111101111111110101");

    @Test
    void theQrCodeMatchesAReferenceEncoderAndDrawsAsSvg() {
        // the modules python-qrcode makes for the same text in byte mode at level M, mask chosen by the same penalties
        boolean[][] m = QrCode.modules("HELLO WORLD", -1);
        StringBuilder sb = new StringBuilder();
        for (int y = 0; y < m.length; y++) { if (y > 0) sb.append('\n'); for (boolean b : m[y]) sb.append(b ? '1' : '0'); }
        assertEquals(HELLO_WORLD, sb.toString());
        assertEquals(49, QrCode.modules("otpauth://totp/ResearchZosho%20Home:Ann?secret=JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP&issuer=ResearchZosho%20Home&algorithm=SHA1&digits=6&period=30", 3).length, "141 bytes: version 8");
        assertArrayEquals(new int[]{6, 28, 50}, QrCode.alignment(10));
        String svg = QrCode.svg("HELLO WORLD", 4);
        assertTrue(svg.startsWith("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 29 29\" width=\"116\" height=\"116\"") && svg.contains("M4 4h1v1h-1z") && svg.endsWith("</svg>"), svg.substring(0, 200));
        assertThrows(IllegalArgumentException.class, () -> QrCode.modules("x".repeat(214), -1));
    }

    @Test
    void theCborReaderReadsWhatAPasskeySends() throws Exception {
        byte[] b = SignInTest.cbor(Map.of("fmt", "none", "attStmt", Map.of(), "authData", new byte[]{1, 2, 3}, "n", -7L, "big", 70000L));
        Object v = Cbor.decode(b);
        assertTrue(v instanceof Map<?, ?> m && "none".equals(m.get("fmt")) && m.get("attStmt") instanceof Map<?, ?> && ((byte[]) m.get("authData")).length == 3 && Long.valueOf(-7).equals(m.get("n")) && Long.valueOf(70000).equals(m.get("big")), String.valueOf(v));
        Cbor.Read r = Cbor.decodeFrom(SignInTest.cbor(Map.of(1L, 2L)), 0);
        assertEquals(3, r.end());
        assertTrue(r.value() instanceof Map<?, ?> k && Long.valueOf(2).equals(k.get(1L)));
        assertThrows(IOException.class, () -> Cbor.decode(new byte[]{(byte) 0x5f}));
        assertEquals(List.of(1L, "a"), Cbor.decode(new byte[]{(byte) 0x82, 1, 0x61, 'a'}));
    }
}
