package org.researchzosho.librarian;

import org.researchzosho.Config;

import java.io.IOException;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.util.HexFormat;

/**
 * Letting a person in with one link (0.6.1). {@code researchzosho reader invite "<name>" [read|write]} makes the reader's id, their token
 * and a join link that works once and for seven days; {@code /join?code=…} opens it, sets the sign-in cookie and goes home. An invite is an
 * access request the owner files and approves in one step, so the join code is the request's claim secret and the token waits in the
 * request until the link is opened — the mechanism a stranger's request already uses ({@link AccessRequests}), nothing new to keep.
 */
public final class Invites {
    private Invites() { }

    /** How many days a join link stands. */
    public static final int DAYS = 7;
    /** The note an invite carries in the requests file, so the inbox does not list it as a stranger asking. */
    public static final String NOTE = "invited by the person who keeps the library";

    public record Invite(String did, String name, Patrons.Level level, String code, String link) { }

    /** A new reader by name: the id, the token held for the link, the link. */
    public static synchronized Invite make(LibraryStore store, String name, Patrons.Level level) throws IOException {
        if (name == null || name.isBlank()) throw new IOException("an invite needs the person's name");
        if (level == null || level == Patrons.Level.deny) throw new IOException("an invite lets a person read or write");
        if (Accounts.byLogin(store, name) != null) throw new IOException("Someone signs in here as \"" + name.strip() + "\" already. To give them a new link: researchzosho reader link \"" + name.strip() + "\"; for another person, another name.");
        byte[] b = new byte[6]; new SecureRandom().nextBytes(b);
        String did = "did:key:person-" + HexFormat.of().formatHex(b);
        AccessRequests.Filed f = AccessRequests.request(store, did, name.strip(), NOTE);
        AccessRequests.approve(store, f.id(), level);
        String code = f.id() + "." + f.claim();
        return new Invite(did, name.strip(), level, code, address() + "/join?code=" + code);
    }

    /** A fresh link for a reader already listed, at their level: for a forgotten password, or a reader from before accounts. */
    public static synchronized Invite renew(LibraryStore store, String did, String name) throws IOException {
        Patrons.Entry e = Patrons.byDid(store, did);
        if (e == null || e.level() == Patrons.Level.deny) throw new IOException(did + " is not a reader here.");
        for (AccessRequests.Request r : AccessRequests.list(store)) if (r.did().equals(did) && r.state().equals("pending")) AccessRequests.deny(store, r.id(), "a new link was made");
        AccessRequests.Filed f = AccessRequests.request(store, did, name.strip(), NOTE_AGAIN, "", true);
        AccessRequests.approve(store, f.id(), e.level());
        String code = f.id() + "." + f.claim();
        return new Invite(did, name.strip(), e.level(), code, address() + "/join?code=" + code);
    }

    /** The note a renewed link's request carries. */
    public static final String NOTE_AGAIN = "a new link from the person who keeps the library";

    /** The address a link is written with: the shared address when the library is shared, else this computer's own. */
    public static String address() {
        String shared = Config.get("RESEARCHZOSHO_SHARE_ADDRESS");
        if (shared != null && !shared.isBlank() && !Share.kind().equals("off")) return shared.strip().replaceAll("/+$", "");
        return "http://127.0.0.1:" + LibrarianDaemon.DEFAULT_PORT;
    }

    /** What a join code comes to: the token when the code is right, the link is within its week and was not used; else why not, in a sentence. */
    public record Opened(String token, String did, String why, String id) {
        public Opened(String token, String did, String why) { this(token, did, why, null); }
        public boolean ok() { return token != null; }
    }

    /** The link looked at without being used: ok when it would open, with the request's id; else why not. */
    public static synchronized Opened peek(LibraryStore store, String code) throws IOException { return open(store, code, false); }

    public static synchronized Opened open(LibraryStore store, String code) throws IOException { return open(store, code, true); }

    private static Opened open(LibraryStore store, String code, boolean use) throws IOException {
        if (code == null || !code.contains(".")) return new Opened(null, null, "That is not a join link.");
        int dot = code.indexOf('.');
        String id = code.substring(0, dot), claim = code.substring(dot + 1);
        AccessRequests.Request r = null;
        for (AccessRequests.Request q : AccessRequests.list(store)) if (q.id().equals(id)) r = q;
        if (r == null) return new Opened(null, null, "This link is not known here. Ask the person who keeps the library for a new one.");
        if (!Patrons.sha256(claim).equals(r.claimHash())) return new Opened(null, null, "This link is not right. Ask the person who keeps the library for a new one.");
        if (LocalDate.parse(r.date()).plusDays(DAYS).isBefore(LocalDate.now())) return new Opened(null, null, "This link has expired: it stood for " + DAYS + " days. Ask the person who keeps the library for a new one.");
        if (r.state().equals("claimed")) return new Opened(null, null, "This link was already used. Ask the person who keeps the library for a new one.");
        if (r.state().equals("denied")) return new Opened(null, null, "A newer link replaced this one. Ask the person who keeps the library for it.");
        if (!use) return new Opened(r.token() == null || r.token().isEmpty() ? null : r.token(), r.did(), r.token() == null || r.token().isEmpty() ? "This link has no sign-in behind it any more. Ask the person who keeps the library for a new one." : "", id);
        AccessRequests.Outcome o = AccessRequests.claim(store, id, claim);
        if (o.token().isEmpty()) return new Opened(null, null, "This link has no sign-in behind it any more. Ask the person who keeps the library for a new one.");
        return new Opened(o.token(), r.did(), "", id);
    }
}
