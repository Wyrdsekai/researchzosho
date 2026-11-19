package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;
import org.researchzosho.drive.DriveClient;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One real library under the account service: a process of its own, started by the service the first time its account asks, found at
 * its address by its own id, answering through the service to its account's key and to nobody without the service's statement, and
 * stopped with the service, its lease let go. The time from the request to the library's answer is printed: a library's start is what
 * the next stage's sleeping and waking is built on.
 */
class ChildLibrariesTest {
    static final ObjectMapper M = new ObjectMapper();

    @Test
    void theEnvironmentALibraryIsGivenPinsWhatTheServiceSets(@TempDir Path tmp) throws Exception {
        try (AccountStore store = AccountStore.open(tmp.resolve("accounts.db").toString())) {
            Map<String, String> env = new LinkedHashMap<>(Map.of("RESEARCHZOSHO_ACCOUNTS", "on", "RESEARCHZOSHO_ACCOUNTS_STORE", "x", "RESEARCHZOSHO_OIDC_CLIENT_SECRET", "s", "RESEARCHZOSHO_TOKEN", "t", "RESEARCHZOSHO_CONFIG", "/somewhere/config", "PATH", "/usr/bin"));
            env.putAll(Map.of("RESEARCHZOSHO_VAULT", "/home/of/the/operator/notes", "RESEARCHZOSHO_CALIBRE", "/home/of/the/operator/books", "RESEARCHZOSHO_CALIBRE_DEFAULT", "/home/of/the/operator/books", "RESEARCHZOSHO_BACKUP_DIR", "/somewhere/backups",
                    "CODEZAIKU_LIBRARY", "/an/old/library", "RESEARCHZOSHO_CTX", "8192", "RESEARCHZOSHO_DRIVE_DEFAULT", "http://localhost:2"));
            new ChildLibraries(store, "http://127.0.0.1:1").environment(env, tmp.resolve("library"), tmp.resolve("home"));
            assertFalse(env.keySet().stream().anyMatch(k -> k.startsWith("RESEARCHZOSHO_ACCOUNTS") || k.startsWith("RESEARCHZOSHO_OIDC_") || k.equals("RESEARCHZOSHO_TOKEN") || k.equals("RESEARCHZOSHO_CONFIG")), "the service's own settings do not reach a library: " + env.keySet());
            assertEquals(tmp.resolve("library").toString(), env.get("RESEARCHZOSHO_LIBRARY")); assertEquals(tmp.resolve("home").toString(), env.get("RESEARCHZOSHO_HOME"));
            assertEquals("127.0.0.1", env.get(Proxy.SETTING)); assertEquals("deny", env.get("RESEARCHZOSHO_FETCH_PRIVATE")); assertEquals("on", env.get(WebAccess.SIGNIN));
            assertEquals("off", env.get("RESEARCHZOSHO_UPDATE")); assertEquals("off", env.get("RESEARCHZOSHO_SHARE")); assertEquals("off", env.get(Helpers.LOCAL));
            assertEquals("/usr/bin", env.get("PATH"));
            // a folder of whoever runs the service, set in the service's own environment, is not handed to a library
            for (String k : new String[]{"RESEARCHZOSHO_VAULT", "RESEARCHZOSHO_CALIBRE", "RESEARCHZOSHO_CALIBRE_DEFAULT", "RESEARCHZOSHO_BACKUP_DIR", "CODEZAIKU_LIBRARY"}) assertFalse(env.containsKey(k), k);
            assertEquals("8192", env.get("RESEARCHZOSHO_CTX"));
            assertTrue(env.get("RESEARCHZOSHO_DRIVE_DEFAULT").matches("http://127\\.0\\.0\\.1:\\d+/gate/http/localhost:2"), "an offer of a setting a library may have comes down, through the node's gate: " + env.get("RESEARCHZOSHO_DRIVE_DEFAULT"));
        }
    }

    /** The key of a host the service's model runs at ({@code models key <host>}) reaches a library, from the service's settings file and from its environment. */
    @Test
    void noKeyIsHandedDownTheNodesGatesHoldThem(@TempDir Path tmp) throws Exception {
        String inFile = "RESEARCHZOSHO_KEY_API_EXAMPLE_ORG", inEnvironment = "RESEARCHZOSHO_KEY_OTHER_EXAMPLE_ORG";
        Config.set(inFile, "the-key-kept-in-the-settings-file");
        Config.set("RESEARCHZOSHO_BRAVE_KEY", "a-brave-key");
        Config.set("RESEARCHZOSHO_API_KEY", "a-model-key");
        Config.set("RESEARCHZOSHO_DRIVE", "https://api.example.org/v1");
        try (AccountStore store = AccountStore.open(tmp.resolve("accounts.db").toString())) {
            assertEquals(inFile, DriveClient.hostKeySetting("api.example.org"), "the setting a host's key is kept in");
            Map<String, String> env = new LinkedHashMap<>(Map.of(inEnvironment, "the-key-given-in-the-environment", "AWS_SECRET_ACCESS_KEY", "an-aws-secret", "OPENAI_API_KEY", "an-openai-key", "PATH", "/usr/bin"));
            new ChildLibraries(store, "http://127.0.0.1:1").environment(env, tmp.resolve("library"), tmp.resolve("home"));
            assertTrue(env.values().stream().noneMatch(v -> v.contains("the-key-kept") || v.contains("the-key-given") || v.contains("a-brave-key") || v.contains("a-model-key") || v.contains("aws-secret") || v.contains("openai-key")), "no key: " + env);
            assertEquals(NodeGates.KEPT, env.get("RESEARCHZOSHO_BRAVE_KEY"), "the paid place may be used: a word stands for its key");
            String gate = NodeGates.of(store).base();
            assertTrue(env.get("RESEARCHZOSHO_DRIVE").startsWith(gate + "/gate/https/api.example.org"), "the model server as the node's gate's address: " + env.get("RESEARCHZOSHO_DRIVE"));
            assertEquals(gate + "/search", env.get(NodeGates.SEARCH_GATE));
            assertEquals("/usr/bin", env.get("PATH"));
        } finally { Config.set(inFile, ""); Config.set("RESEARCHZOSHO_BRAVE_KEY", ""); Config.set("RESEARCHZOSHO_API_KEY", ""); Config.set("RESEARCHZOSHO_DRIVE", ""); }
    }

    /**
     * A library's process gets nothing of this program's settings from the service's own environment except what is handed down by
     * name: whatever a variable of the program's is called, known today or added later, it does not come down by being there.
     */
    @Test
    void nothingOfTheServicesEnvironmentComesDownExceptByName(@TempDir Path tmp) throws Exception {
        try (AccountStore store = AccountStore.open(tmp.resolve("accounts.db").toString())) {
            Map<String, String> env = new LinkedHashMap<>();
            env.put("PATH", "/usr/bin"); env.put("LANG", "en_US.UTF-8"); env.put("HOME", "/home/of/the/operator");
            Set<String> notTheLibrarys = Set.of("RESEARCHZOSHO_VAULT", "RESEARCHZOSHO_CALIBRE", "RESEARCHZOSHO_BACKUP_DIR", "RESEARCHZOSHO_LIKENESS_DIR", "RESEARCHZOSHO_LAUNCHER", "RESEARCHZOSHO_SOMETHING_ADDED_LATER",
                    "RESEARCHZOSHO_ACCOUNTS", "RESEARCHZOSHO_ACCOUNTS_STORE", "RESEARCHZOSHO_ACCOUNTS_ROOT", "RESEARCHZOSHO_OIDC_ISSUER", "RESEARCHZOSHO_OIDC_CLIENT_SECRET", "RESEARCHZOSHO_TOKEN", "RESEARCHZOSHO_CONFIG",
                    "RESEARCHZOSHO_SESSION_DAYS", "RESEARCHZOSHO_DNA_SHARE", "RESEARCHZOSHO_DOOR", "RESEARCHZOSHO_SHARE_ADDRESS", "RESEARCHZOSHO_CALIBRE_DEFAULT", "RESEARCHZOSHO_SHARE_DEFAULT",
                    "CODEZAIKU_LIBRARY", "CODEZAIKU_LIBRARIAN_TOKEN", "CODEZAIKU_HOME");
            for (String k : notTheLibrarys) env.put(k, "a value of the operator's");
            env.put("RESEARCHZOSHO_LIBRARY", "/the/operators/own/library"); env.put("RESEARCHZOSHO_HOME", "/the/operators/own/settings");
            env.put("RESEARCHZOSHO_TRUSTED_PROXY", "10.0.0.0/8"); env.put("RESEARCHZOSHO_FETCH_PRIVATE", "allow"); env.put("RESEARCHZOSHO_WEB_SIGNIN", "off"); env.put("RESEARCHZOSHO_UPDATE", "auto"); env.put("RESEARCHZOSHO_LOCAL_HELPERS", "on"); env.put("RESEARCHZOSHO_SHARE", "funnel");
            env.put("RESEARCHZOSHO_CTX", "8192"); env.put("RESEARCHZOSHO_LANGUAGE_DEFAULT", "Japanese");
            new ChildLibraries(store, "http://127.0.0.1:1").environment(env, tmp.resolve("library"), tmp.resolve("home"));
            for (String k : notTheLibrarys) assertFalse(env.containsKey(k), k + " came down");
            // what is the library's own, and what the service fixes for every library, whatever its own environment said
            assertEquals(tmp.resolve("library").toString(), env.get("RESEARCHZOSHO_LIBRARY")); assertEquals(tmp.resolve("home").toString(), env.get("RESEARCHZOSHO_HOME"));
            assertEquals("127.0.0.1", env.get("RESEARCHZOSHO_TRUSTED_PROXY")); assertEquals("deny", env.get("RESEARCHZOSHO_FETCH_PRIVATE")); assertEquals("on", env.get("RESEARCHZOSHO_WEB_SIGNIN"));
            assertEquals("off", env.get("RESEARCHZOSHO_UPDATE")); assertEquals("off", env.get("RESEARCHZOSHO_LOCAL_HELPERS")); assertEquals("off", env.get("RESEARCHZOSHO_SHARE"));
            assertEquals("8192", env.get("RESEARCHZOSHO_CTX")); assertEquals("Japanese", env.get("RESEARCHZOSHO_LANGUAGE_DEFAULT"), "an offer of a setting that is the library's own");
            assertEquals("/usr/bin", env.get("PATH")); assertEquals("en_US.UTF-8", env.get("LANG"));
            // and every variable of the program's that is there now has a reason to be: a setting the service hands down, one it fixes, how Java runs, a host's key, or an offer
            Set<String> fixed = Set.of("RESEARCHZOSHO_LIBRARY", "RESEARCHZOSHO_HOME", "RESEARCHZOSHO_TRUSTED_PROXY", "RESEARCHZOSHO_FETCH_PRIVATE", "RESEARCHZOSHO_WEB_SIGNIN", "RESEARCHZOSHO_WEB_LOCAL", "RESEARCHZOSHO_UPDATE", "RESEARCHZOSHO_SHARE", "RESEARCHZOSHO_LOCAL_HELPERS", "RESEARCHZOSHO_LOG",
                    "RESEARCHZOSHO_XMX", "RESEARCHZOSHO_JAVA", "RESEARCHZOSHO_JAVA_OPTS", "RESEARCHZOSHO_CTX", "RESEARCHZOSHO_RERANK", "RESEARCHZOSHO_ENRICH");
            Set<String> known = new HashSet<>(); for (Settings.Setting s : Settings.known()) known.add(s.key());
            for (String k : env.keySet()) if (k.startsWith("RESEARCHZOSHO_") || k.startsWith("CODEZAIKU_"))
                assertTrue(fixed.contains(k) || known.contains(k) || k.equals(NodeGates.SEARCH_GATE) || k.endsWith("_DEFAULT"), k + " is in a library's environment for no reason this test knows");
        }
    }

    /** Two account services on one machine, each asked for a library in the same instant: each library gets a port of its own, and a port something else holds is passed over. */
    @Test
    void twoServicesOnOneMachineDoNotTakeEachOthersPorts(@TempDir Path tmp) throws Exception {
        try (ServerSocket held = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Config.set(AccountService.FIRST_PORT, String.valueOf(held.getLocalPort()));   // the first port is in use: both services begin looking at the same place
            AccountStore one = AccountStore.open(tmp.resolve("one").resolve("accounts.db").toString()), two = AccountStore.open(tmp.resolve("two").resolve("accounts.db").toString());
            AccountService a = AccountService.start(one, "127.0.0.1", 0, self -> new ChildLibraries(one, self)), b = AccountService.start(two, "127.0.0.1", 0, self -> new ChildLibraries(two, self));
            try {
                AccountService.Made ann = AccountService.newAccount(one, tmp.resolve("one"), "Ann Example", "", false, "the test"), ben = AccountService.newAccount(two, tmp.resolve("two"), "Ben Example", "", false, "the test");
                String ka = one.mintKey(ann.account().id(), ann.library().id(), "read", "t").secret(), kb = two.mintKey(ben.account().id(), ben.library().id(), "read", "t").secret();
                HttpClient c = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
                CompletableFuture<HttpResponse<String>> ra = c.sendAsync(HttpRequest.newBuilder(URI.create(a.url() + "/v1/status")).header("Authorization", "Bearer " + ka).GET().build(), HttpResponse.BodyHandlers.ofString());
                CompletableFuture<HttpResponse<String>> rb = c.sendAsync(HttpRequest.newBuilder(URI.create(b.url() + "/v1/status")).header("Authorization", "Bearer " + kb).GET().build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(200, ra.get().statusCode(), ra.get().body()); assertEquals(200, rb.get().statusCode(), rb.get().body());
                assertEquals(ann.library().id(), M.readTree(ra.get().body()).path("library_id").asText()); assertEquals(ben.library().id(), M.readTree(rb.get().body()).path("library_id").asText());
                String at = one.library(ann.library().id()).address(), bt = two.library(ben.library().id()).address();
                assertFalse(at.equals(bt), "each library has a port of its own: " + at);
                assertFalse(at.endsWith(":" + held.getLocalPort()) || bt.endsWith(":" + held.getLocalPort()), "the port in use was passed over");
            } finally { a.stop(); b.stop(); one.close(); two.close(); Config.set(AccountService.FIRST_PORT, ""); }
        }
    }

    @Test
    void aLibraryIsStartedWhenItIsFirstAskedForAndStoppedWithTheService(@TempDir Path tmp) throws Exception {
        Path root = tmp.resolve("accounts");
        AccountStore store = AccountStore.open(root.resolve("accounts.db").toString());
        AccountService svc = AccountService.start(store, "127.0.0.1", 0, self -> new ChildLibraries(store, self));
        HttpClient c = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        Path lease;
        try {
            AccountService.Made ann = AccountService.newAccount(store, root, "Ann Example", "", false, "the test");
            String key = store.mintKey(ann.account().id(), ann.library().id(), "write", "the test").secret();
            lease = Path.of(ann.library().folder()).resolve("library").resolve("catalog").resolve("serve.lock");
            long t0 = System.currentTimeMillis();
            HttpResponse<String> first = c.send(HttpRequest.newBuilder(URI.create(svc.url() + "/v1/status")).header("Authorization", "Bearer " + key).GET().build(), HttpResponse.BodyHandlers.ofString());
            long cold = System.currentTimeMillis() - t0;
            assertEquals(200, first.statusCode(), first.body() + "\n" + log(ann));
            JsonNode status = M.readTree(first.body());
            assertEquals(ann.library().id(), status.path("library_id").asText()); assertEquals("Ann Example", status.path("library_name").asText());
            t0 = System.currentTimeMillis();
            assertEquals(200, c.send(HttpRequest.newBuilder(URI.create(svc.url() + "/v1/status")).header("Authorization", "Bearer " + key).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            System.out.println("[child] the first request, which started the library's process, was answered after " + cold + " ms; the next after " + (System.currentTimeMillis() - t0) + " ms");
            assertTrue(Files.exists(lease), "the library holds its lease");
            // at its own address the library answers its health check with its id, and nothing else without the service's statement
            String at = store.library(ann.library().id()).address();
            assertTrue(at.startsWith("http://127.0.0.1:"), at);
            assertTrue(ChildLibraries.answers(at, ann.library().id()));
            HttpResponse<String> direct = c.send(HttpRequest.newBuilder(URI.create(at + "/v1/status")).header("Authorization", "Bearer " + key).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(403, direct.statusCode()); assertTrue(direct.body().contains("carries no statement"), direct.body());
            // the library's own call for its people reaches the service from the other process
            HttpResponse<String> people = c.send(HttpRequest.newBuilder(URI.create(svc.url() + "/v1/access")).header("Authorization", "Bearer " + key).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{\"op\":\"list\"}")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, people.statusCode(), people.body()); assertEquals("Ann Example", M.readTree(people.body()).path("keeper").asText());
            // its folders are its own: the library, the settings folder, the temporary folder, a home
            Path folder = Path.of(ann.library().folder());
            for (String d : new String[]{"library", "home", "tmp", "user"}) assertTrue(Files.isDirectory(folder.resolve(d)), d);
        } finally { svc.stop(); store.close(); }
        assertFalse(Files.exists(lease), "stopped by being asked to: the lease is let go");
    }

    static String log(AccountService.Made m) { try { return Files.readString(Path.of(m.library().folder()).resolve("serve.log")); } catch (Exception e) { return "(no log)"; } }
}
