package org.researchzosho.librarian;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Asking to be let in. A stranger who found this library (through a directory, or a friend) asks for
 * access with a did, a name and a note; the owner reads the request and approves or denies it; the
 * requester picks the token up with the claim secret they were given when they asked, exactly once.
 * The owner never has to send a secret anywhere, and nobody but the requester can collect it.
 *
 * <p>Limits, so a script cannot fill the inbox: one pending request per did, fifty pending per library,
 * and a pending request lapses after thirty days. Requests live in {@code catalog/access-requests.md}.
 */
public final class AccessRequests {

    static final int MAX_PENDING = 50;
    static final int DAYS = 30;

    public record Request(String id, String date, String state, String did, String name, String note, String claimHash, String token, String level, String reason) {
        Request with(String state, String token, String level, String reason) { return new Request(id, date, state, did, name, note, claimHash, token, level, reason); }
    }

    public record Filed(String id, String claim, boolean fresh) { }

    private AccessRequests() { }

    static Path file(LibraryStore store) { return store.root().resolve("catalog").resolve("access-requests.md"); }

    public static synchronized List<Request> list(LibraryStore store) throws IOException {
        List<Request> out = new ArrayList<>();
        if (!Files.exists(file(store))) return out;
        LocalDate cutoff = LocalDate.now().minusDays(DAYS);
        for (String line : Files.readAllLines(file(store), StandardCharsets.UTF_8)) {
            if (!line.startsWith("- ")) continue;
            String[] p = line.substring(2).split(" \\| ", -1);
            if (p.length < 10) continue;
            Request r = new Request(p[0], p[1], p[2], p[3], p[4], p[5], p[6], p[7], p[8], p[9]);
            if (r.state().equals("pending") && LocalDate.parse(r.date()).isBefore(cutoff)) r = r.with("lapsed", "", "", "not answered within " + DAYS + " days");
            out.add(r);
        }
        return out;
    }

    private static void write(LibraryStore store, List<Request> all) throws IOException {
        Files.createDirectories(file(store).getParent());
        StringBuilder sb = new StringBuilder("# Access requests — who asked to be let in, and what you decided (the owner's file)\n\n"
                + "`- id | date | state | did | name | note | claim-hash | token-until-claimed | level | reason`\n\n");
        for (Request r : all) {
            sb.append("- ").append(String.join(" | ", r.id(), r.date(), r.state(), c(r.did()), c(r.name()), c(r.note()), r.claimHash(), r.token() == null ? "" : r.token(), r.level() == null ? "" : r.level(), c(r.reason()))).append('\n');
        }
        Files.writeString(file(store), sb.toString(), StandardCharsets.UTF_8);
    }

    private static String c(String s) { return s == null ? "" : s.replace("|", "/").replaceAll("\\s+", " ").strip(); }

    /** File a request. Returns the id and, when new, the claim secret (shown once; only its hash is kept). */
    public static synchronized Filed request(LibraryStore store, String did, String name, String note) throws IOException {
        if (did == null || did.isBlank() || did.equals("person")) throw new IOException("a request needs your did");
        for (Patrons.Entry e : Patrons.load(store).listed()) if (e.did().equals(did)) throw new IOException("that did is already a reader here");
        List<Request> all = list(store);
        for (Request r : all) if (r.did().equals(did) && r.state().equals("pending")) return new Filed(r.id(), "", false);
        long pending = all.stream().filter(r -> r.state().equals("pending")).count();
        if (pending >= MAX_PENDING) throw new IOException("this library has " + MAX_PENDING + " requests waiting already; try again later");
        int n = 0;
        for (Request r : all) { try { n = Math.max(n, Integer.parseInt(r.id().substring(2))); } catch (Exception ignored) { } }
        String id = String.format("R-%04d", n + 1);
        byte[] b = new byte[24];
        new java.security.SecureRandom().nextBytes(b);
        String claim = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(b);
        all.add(new Request(id, LocalDate.now().toString(), "pending", did, name == null ? "" : name, note == null ? "" : note, Patrons.sha256(claim), "", "", ""));
        write(store, all);
        store.circulate("access-request", id + " from " + did + (name == null || name.isBlank() ? "" : " (" + name + ")"));
        return new Filed(id, claim, true);
    }

    public static synchronized long pending(LibraryStore store) throws IOException {
        return list(store).stream().filter(r -> r.state().equals("pending")).count();
    }

    /** The owner lets them in at {@code level}: the did is listed, a token made and held for the claim. */
    public static synchronized Request approve(LibraryStore store, String id, Patrons.Level level) throws IOException {
        List<Request> all = list(store);
        Request found = null;
        for (int i = 0; i < all.size(); i++) {
            Request r = all.get(i);
            if (!r.id().equals(id)) continue;
            if (!r.state().equals("pending")) throw new IOException(id + " is " + r.state() + ", not pending");
            Patrons.set(store, r.did(), r.name(), level);
            String token = Patrons.issueToken(store, r.did());
            found = r.with("approved", token, level.name(), "");
            all.set(i, found);
        }
        if (found == null) throw new IOException("no request " + id);
        write(store, all);
        Changes.append(store, "reader", found.did(), "approved", "access request " + id + " approved at " + level);
        return found;
    }

    public static synchronized Request deny(LibraryStore store, String id, String reason) throws IOException {
        List<Request> all = list(store);
        Request found = null;
        for (int i = 0; i < all.size(); i++) {
            Request r = all.get(i);
            if (!r.id().equals(id)) continue;
            if (!r.state().equals("pending")) throw new IOException(id + " is " + r.state() + ", not pending");
            found = r.with("denied", "", "", reason == null ? "" : reason);
            all.set(i, found);
        }
        if (found == null) throw new IOException("no request " + id);
        write(store, all);
        return found;
    }

    /** The requester's side: what became of the request, and the token, once, when it was approved and the claim is right. */
    public record Outcome(String id, String state, String token, String level, String reason) { }

    public static synchronized Outcome claim(LibraryStore store, String id, String claim) throws IOException {
        List<Request> all = list(store);
        for (int i = 0; i < all.size(); i++) {
            Request r = all.get(i);
            if (!r.id().equals(id)) continue;
            if (claim == null || !Patrons.sha256(claim).equals(r.claimHash())) throw new IOException("that is not the claim secret for " + id);
            if (r.state().equals("approved") && r.token() != null && !r.token().isEmpty()) {
                String token = r.token();
                all.set(i, r.with("claimed", "", r.level(), ""));
                write(store, all);
                return new Outcome(id, "approved", token, r.level(), "");
            }
            return new Outcome(id, r.state(), "", r.level() == null ? "" : r.level(), r.reason() == null ? "" : r.reason());
        }
        throw new IOException("no request " + id);
    }
}
