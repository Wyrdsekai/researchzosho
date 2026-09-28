package org.researchzosho.librarian;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;
import org.researchzosho.drive.DriveTesting;
import org.researchzosho.librarian.profiles.GenealogyProfile;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The library's own drive, at a model server that answers with an HTTP error instead of a reply. A 503 while the model loads is waited
 * out and asked again; an error that stays (503, 502, 401, 403, 404, 429) is no answer: the read files nothing from the file, marks
 * nothing read and stops, as for a model that cannot be reached. A yes-or-no check that gets no answer is no answer, never a no.
 */
class FamilyReadHttpErrorTest {

    static final String MINER = "Tom Ellis worked as a miner in York.";
    static final String SISTER = "Ruth Ellis is the sister of Tom Ellis.";
    static final String FACTS = """
            {"people": [], "facts": [
             {"subject": "Tom Ellis", "relation": "occupation", "object": "miner", "date": "", "quote": "Tom Ellis worked as a miner in York."}]}""";
    static final String NOTHING = "{\"people\": [], \"facts\": []}";
    static final String LOADING = "{\"error\":{\"code\":503,\"message\":\"Loading model\",\"type\":\"unavailable_error\"}}";

    private String realHome;
    private long[] waits;

    @BeforeEach void quickWaits(@TempDir Path tmp) {
        realHome = System.getProperty("user.home");
        System.setProperty("user.home", tmp.resolve("home").toString());
        Config.invalidate();
        waits = DriveTesting.loadingWaits(20, 20);
    }

    @AfterEach void restore() {
        DriveTesting.loadingWaits(waits);
        System.setProperty("user.home", realHome);
        Config.invalidate();
        GenealogyProfile.useReader(null);
    }

    /** One answer from the model server: an HTTP status and a body. */
    record Reply(int status, String body) { }

    /** A model server on this machine: every request to /v1/chat/completions is answered by {@code answer}, from the request's body. */
    static HttpServer server(List<String> asked, Function<String, Reply> answer) throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/v1/chat/completions", x -> {
            String body = new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            synchronized (asked) { asked.add(body); }
            Reply r = answer.apply(body);
            if (r == null) { x.close(); return; }   // the connection closed with no answer at all
            byte[] b = r.body().getBytes(StandardCharsets.UTF_8);
            x.getResponseHeaders().set("Content-Type", "application/json");
            x.sendResponseHeaders(r.status(), b.length);
            try (var os = x.getResponseBody()) { os.write(b); }
        });
        s.start();
        return s;
    }

    /** A chat completion whose reply is {@code content}. */
    static String completion(String content) {
        return "{\"choices\":[{\"index\":0,\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":" + quoted(content) + "}}],"
                + "\"usage\":{\"prompt_tokens\":40,\"completion_tokens\":20,\"total_tokens\":60}}";
    }

    static String quoted(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    private static LibraryStore store(Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        return store;
    }

    private static Path folder(Path tmp) throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("family-sources"));
        Files.writeString(dir.resolve("a-notes.txt"), MINER + "\n", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("b-notes.txt"), SISTER + "\n", StandardCharsets.UTF_8);
        return dir;
    }

    private static List<String> readBefore(LibraryStore store, Path dir) throws Exception {
        return FamilyFolder.plan(store, dir).stream().filter(FamilyFolder.Item::readBefore).map(it -> it.file().getFileName().toString()).toList();
    }

    private static boolean filed(LibraryStore store, String subject, String predicate) throws Exception {
        return store.scanFindings().findings().stream().anyMatch(f -> f.triple() != null && f.triple().subject().equals(subject) && f.triple().predicate().equals(predicate));
    }

    private static FamilyReadUnansweredTest.Ran read(LibraryStore store, String address, Path what) throws Exception {
        Config.set("RESEARCHZOSHO_DRIVE", address);
        return FamilyReadUnansweredTest.run(store, "read", what.toString());
    }

    @Test
    void aServerStillLoadingItsModelIsWaitedForAndAskedAgain(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        Path dir = folder(tmp);
        List<String> asked = new ArrayList<>();
        AtomicInteger loading = new AtomicInteger(2);
        HttpServer s = server(asked, body -> loading.getAndDecrement() > 0 ? new Reply(503, LOADING)
                : new Reply(200, completion(body.contains("worked as a miner") ? FACTS : NOTHING)));
        try {
            FamilyReadUnansweredTest.Ran r = read(store, "http://127.0.0.1:" + s.getAddress().getPort(), dir);
            assertEquals(0, r.rc(), r.out());
            assertTrue(filed(store, "Tom Ellis", "occupation"), "the answer after the model loaded is filed: " + r.out());
            assertEquals(List.of("a-notes.txt", "b-notes.txt"), readBefore(store, dir));
        } finally { s.stop(0); }
    }

    @Test
    void aServerThatKeepsAnsweringWithAnErrorIsNoAnswerAndTheReadStops(@TempDir Path tmp) throws Exception {
        for (int status : new int[]{503, 502, 401, 403, 404, 429}) {
            Path at = Files.createDirectories(tmp.resolve("s" + status));
            LibraryStore store = store(at);
            Path dir = folder(at);
            List<String> asked = new ArrayList<>();
            String[] mode = {"error"};
            HttpServer s = server(asked, body -> mode[0].equals("error") ? new Reply(status, status == 503 ? LOADING : "{\"error\":{\"message\":\"no\"}}")
                    : new Reply(200, completion(body.contains("worked as a miner") ? FACTS : NOTHING)));
            String address = "http://127.0.0.1:" + s.getAddress().getPort();
            try {
                FamilyReadUnansweredTest.Ran r = read(store, address, dir);
                assertEquals(1, r.rc(), status + ": " + r.out());
                assertFalse(r.out().contains("did not find anybody from a family"), status + ": " + r.out());
                assertFalse(r.out().contains("Reading the same file again usually fixes this"), status + ": " + r.out());
                assertTrue(r.out().contains("The model at " + address + " answered with an error instead of an answer (HTTP " + status + "), so the library read nothing from a-notes.txt and added nothing from it to your library. It did not mark the file as read."), status + ": " + r.out());
                // what to do is said for what the server answered, never "start the model" for a server that is running
                String todo = switch (status) {
                    case 503 -> "Its server said the model is still loading or busy. Wait a few minutes, then give the same command again.";
                    case 429 -> "Its server said it has too many requests right now. Wait a few minutes, then give the same command again.";
                    case 401, 403 -> "Its server did not accept the key. Check the key in the setting RESEARCHZOSHO_API_KEY, then give the same command again.";
                    case 404 -> "Check the address (the setting RESEARCHZOSHO_DRIVE) and the model's name (the setting RESEARCHZOSHO_MODEL), then give the same command again.";
                    default -> "The server in front of the model got no answer from it. Check that the model is running, then give the same command again.";
                };
                assertTrue(r.out().contains(todo), status + ": " + r.out());
                assertFalse(r.out().contains("Start the model"), status + ": " + r.out());
                assertTrue(r.out().contains("The library stopped here and did not read the other file, because reading a file needs the model."), status + ": " + r.out());
                assertFalse(r.out().contains("WHAT HAPPENS NEXT"), status + ": " + r.out());
                assertEquals(List.of(), readBefore(store, dir), status + ": nothing is written down as read");
                assertTrue(asked.stream().noneMatch(b -> b.contains("Ruth Ellis")), status + ": the second file was not tried");
                assertEquals(status == 503 ? 3 : 1, asked.size(), status + ": a 503 is asked twice more after a wait, any other error once");

                // the server answers again: the same command reads both files, and the fact is filed
                mode[0] = "answer";
                FamilyReadUnansweredTest.Ran again = read(store, address, dir);
                assertEquals(0, again.rc(), status + ": " + again.out());
                assertTrue(filed(store, "Tom Ellis", "occupation"), status + ": " + again.out());
                assertEquals(List.of("a-notes.txt", "b-notes.txt"), readBefore(store, dir));
            } finally { s.stop(0); }
        }
    }

    @Test
    void aCheckOfATableQuoteThatGetsNoAnswerIsNotANo(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        Path dir = Files.createDirectories(tmp.resolve("family-sources"));
        Files.writeString(dir.resolve("a-notes.txt"), "| Name | Father |\n| Tom Ellis | John Ellis |\n", StandardCharsets.UTF_8);
        String table = """
                {"people": [], "facts": [
                 {"subject": "Tom Ellis", "relation": "child-of", "object": "John Ellis", "date": "", "quote": "Tom Ellis | John Ellis"}]}""";
        List<String> asked = new ArrayList<>();
        boolean[] judgeAnswers = {false};
        // the read's call is answered; the typed yes-or-no check (the request with a grammar) gets its connection closed with no answer
        HttpServer s = server(asked, body -> !body.contains("\"grammar\"") ? new Reply(200, completion(table))
                : judgeAnswers[0] ? new Reply(200, "{\"choices\":[{\"index\":0,\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"yes\"},"
                        + "\"logprobs\":{\"content\":[{\"token\":\"yes\",\"logprob\":-0.01,\"top_logprobs\":[{\"token\":\"yes\",\"logprob\":-0.01},{\"token\":\"no\",\"logprob\":-4.6}]}]}}]}")
                : null);
        String address = "http://127.0.0.1:" + s.getAddress().getPort();
        try {
            FamilyReadUnansweredTest.Ran r = read(store, address, dir);
            assertTrue(asked.stream().anyMatch(b -> b.contains("\"grammar\"")), "the check was asked: " + r.out());
            assertFalse(r.out().contains("on a second look those words do not say it"), "no answer is not a no: " + r.out());
            assertNotEquals(0, r.rc(), r.out());
            assertTrue(r.out().contains("The model at " + address + " did not answer, so the library read nothing from a-notes.txt and added nothing from it to your library. It did not mark the file as read."), r.out());
            assertFalse(r.out().contains("WHAT HAPPENS NEXT"), r.out());
            assertEquals(List.of(), readBefore(store, dir), "the file is not written down as read, so the next read does it");
            assertFalse(filed(store, "Tom Ellis", "child-of"));

            // the check is answered: the same command files the fact
            judgeAnswers[0] = true;
            FamilyReadUnansweredTest.Ran again = read(store, address, dir);
            assertEquals(0, again.rc(), again.out());
            assertTrue(filed(store, "Tom Ellis", "child-of"), again.out());
            assertEquals(List.of("a-notes.txt"), readBefore(store, dir));
        } finally { s.stop(0); }
    }

    @Test
    void whatWasToldOnTheWhoIsWhoPageWaitsWhenACheckOfItGetsNoAnswer(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(new FamilyAccount.Fact("Tom Ellis", "died-on", "1950", "", "q")), List.of()), "file:///family/notes.txt", "an aunt");
        // he was searched for already, and the family told the web page something about him afterwards
        String question = "Tom Ellis (died 1950): who were the parents?";
        store.frontier("person me (from the family tree)", question);
        Frontier.markExplored(store, question, "J-0001");
        FamilyIdentity.find(store, Graph.build(store), "Tom Ellis", q -> List.of(), null);
        String told = "Tom Ellis was taken in by John Ellis in 1920.";
        FamilyIdentity.told(store, "Tom Ellis", told);
        // the kind of the adoption is not in the words, so the typed check is asked about it, and its connection closes with no answer
        String adopted = """
                {"people": [], "facts": [
                 {"subject": "Tom Ellis", "relation": "adopted-by", "object": "John Ellis", "date": "1920", "quote": "Tom Ellis was taken in by John Ellis in 1920.", "kind": "heir"}]}""";
        List<String> asked = new ArrayList<>();
        boolean[] judgeAnswers = {false};
        HttpServer s = server(asked, body -> !body.contains("\"grammar\"") ? new Reply(200, completion(adopted))
                : judgeAnswers[0] ? new Reply(200, "{\"choices\":[{\"index\":0,\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"yes\"},"
                        + "\"logprobs\":{\"content\":[{\"token\":\"yes\",\"logprob\":-0.01,\"top_logprobs\":[{\"token\":\"yes\",\"logprob\":-0.01},{\"token\":\"no\",\"logprob\":-4.6}]}]}}]}")
                : null);
        String address = "http://127.0.0.1:" + s.getAddress().getPort();
        Config.set("RESEARCHZOSHO_DRIVE", address);
        try {
            FamilyReadUnansweredTest.Ran r = FamilyReadUnansweredTest.run(store, "research");
            assertTrue(asked.stream().anyMatch(b -> b.contains("\"grammar\"")), "the check was asked: " + r.out());
            assertTrue(r.out().contains("The model at " + address + " did not answer, so the library read nothing from what you told and added nothing from it to your library. It waits, and this command files it the next time it runs."), r.out());
            assertEquals(0, r.rc(), "the command goes on to its end: " + r.out());
            assertTrue(r.out().contains("There is nobody to search for yet."), "the command goes on to its end: " + r.out());
            assertEquals(List.of(told), FamilyIdentity.toldNotFiled(FamilyIdentity.read(store, "Tom Ellis")), "the words wait to be filed");
            assertFalse(filed(store, "Tom Ellis", "adopted-by"));

            // the check is answered: the same command files what was told
            judgeAnswers[0] = true;
            FamilyReadUnansweredTest.Ran again = FamilyReadUnansweredTest.run(store, "research");
            assertEquals(0, again.rc(), again.out());
            assertTrue(filed(store, "Tom Ellis", "adopted-by"), again.out());
            assertTrue(FamilyIdentity.toldNotFiled(FamilyIdentity.read(store, "Tom Ellis")).isEmpty(), again.out());
        } finally { s.stop(0); }
    }
}
