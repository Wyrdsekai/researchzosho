package org.researchzosho.drive;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;
import org.researchzosho.librarian.Setup;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Requests to a router of hosted models, after a second reading of 0.8.1: the list of providers to leave out goes with every
 * request whatever its body holds, a body that is no request is left as it is, and when nobody is left to serve a model every path
 * says so in the same sentence and sends nothing. The router is a stand-in on the loopback, reached as a node's gate reaches the
 * real one, so the client takes it for the router and no test asks the real one.
 */
class GuardReviewHostedTest {
    static final ObjectMapper M = new ObjectMapper();
    static final String MODEL = "deepseek/deepseek-v4.1-flash";

    @TempDir Path home;
    String realHome;
    HttpServer router; String drive;
    final List<JsonNode> bodies = new CopyOnWriteArrayList<>();
    volatile int status = 200;
    volatile String answer = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ready\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":1}}";
    Function<String, String> fetch; LongSupplier now;

    @BeforeEach void up() throws Exception {
        realHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString()); Config.invalidate();
        fetch = OpenRouterProviders.FETCH; now = OpenRouterProviders.NOW;
        OpenRouterProviders.FETCH = url -> null;
        OpenRouterProviders.forget();
        DriveClient.OpenRouterModels.OVERRIDE = Map.of();
        router = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        router.createContext("/", x -> {
            String body = new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            boolean chat = x.getRequestURI().getPath().endsWith("/chat/completions");
            if (chat) bodies.add(M.readTree(body));
            byte[] b = (chat ? answer : "{}").getBytes(StandardCharsets.UTF_8);
            x.getResponseHeaders().add("Content-Type", "application/json");
            x.sendResponseHeaders(chat ? status : 404, b.length); x.getResponseBody().write(b); x.close();
        });
        router.start();
        drive = "http://127.0.0.1:" + router.getAddress().getPort() + Gated.PATH + "https/openrouter.ai/api/v1";
    }

    @AfterEach void down() {
        router.stop(0);
        DriveClient.OpenRouterModels.OVERRIDE = null;
        OpenRouterProviders.FETCH = fetch; OpenRouterProviders.NOW = now; OpenRouterProviders.forget();
        System.setProperty("user.home", realHome); Config.invalidate();
    }

    static ArrayNode hello() { ArrayNode m = M.createArrayNode(); m.addObject().put("role", "user").put("content", "hi"); return m; }
    static List<String> ignored(JsonNode body) { List<String> out = new ArrayList<>(); for (JsonNode s : body.path("provider").path("ignore")) out.add(s.asText()); return out; }
    static String endpoints(String... tags) {
        StringBuilder b = new StringBuilder("{\"data\":{\"id\":\"" + MODEL + "\",\"endpoints\":[");
        for (int i = 0; i < tags.length; i++) b.append(i == 0 ? "" : ",").append("{\"provider_name\":\"x\",\"tag\":\"").append(tags[i]).append("\"}");
        return b.append("]}}").toString();
    }

    /**
     * What is handed to the client as a request's body and is none (nothing, a list, a word) is left as it is: there is no request
     * in it to add a list to, and the place that adds the list is not the place to fail. The control: a request's body gets the
     * list, and another host's body is not read at all.
     */
    @Test
    void aBodyThatIsNoRequestIsLeftAsItIs() throws Exception {
        DriveClient c = new DriveClient(drive, MODEL);
        Map<String, String> got = new LinkedHashMap<>();
        for (String body : List.of("", "   ", "[]", "null", "\"a word\"", "42", "[{\"model\":\"x\"}]")) {
            try { got.put(body, c.routed(body).equals(body) ? "left as it is" : "CHANGED"); } catch (Exception e) { got.put(body, "THREW " + e.getClass().getSimpleName()); }
        }
        System.out.println("[review] D-727 a body that is no request, at the place every body passes: " + got);
        assertTrue(ignored(M.readTree(c.routed("{\"model\":\"" + MODEL + "\",\"messages\":[]}"))).contains("baidu"), "a request's body gets the list (the control)");
        assertEquals("not json at all {", new DriveClient("http://127.0.0.1:" + router.getAddress().getPort(), MODEL).routed("not json at all {"), "another host's body is not read");
        for (Map.Entry<String, String> e : got.entrySet()) assertEquals("left as it is", e.getValue(), "the body " + e.getKey());
    }

    /**
     * The providers to leave out go with every request to the router, whatever stood in the request's provider field: the
     * request's own choices beside them when the field is the router's own form (an object), and in its place when it is not (a
     * word, a list, a number are no form the router takes). A request is never sent without the list because of what its body held.
     */
    @Test
    void theProvidersToLeaveOutGoWithTheRequestWhateverStoodInItsProviderField() throws Exception {
        List<String> all = OpenRouterProviders.ignored();
        assertTrue(all.contains("baidu") && all.size() > 3, "the list (the instrument): " + all);
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("none", "{}");
        fields.put("the router's form, with choices of its own", "{\"provider\":{\"order\":[\"fireworks\"],\"ignore\":[\"some-other\"],\"allow_fallbacks\":false}}");
        fields.put("a word", "{\"provider\":\"deepinfra\"}");
        fields.put("a list", "{\"provider\":[\"deepinfra\"]}");
        fields.put("a number", "{\"provider\":7}");
        fields.put("nothing", "{\"provider\":null}");
        Map<String, String> got = new LinkedHashMap<>();
        for (Map.Entry<String, String> f : fields.entrySet()) {
            ObjectNode body = (ObjectNode) M.readTree(f.getValue());
            body.put("model", MODEL);
            DriveClient.routed(body, drive);
            got.put(f.getKey(), ignored(body).containsAll(all) ? "the list is there" : "NO LIST: " + body.path("provider"));
            if (f.getKey().startsWith("the router's form")) assertTrue(ignored(body).contains("some-other") && body.path("provider").path("order").path(0).asText().equals("fireworks") && !body.path("provider").path("allow_fallbacks").asBoolean(true), "its own choices are kept beside the list: " + body);
        }
        System.out.println("[review] D-739 the providers to leave out, by what the request's provider field held: " + got);
        for (Map.Entry<String, String> e : got.entrySet()) assertEquals("the list is there", e.getValue(), e.getKey());
    }

    /**
     * When every provider that serves a model is left out, nothing is sent and the person reads which setting allows them: in a
     * turn, in a classification, in a typed decision, and in the hello that setup and the Connect page send, which is no client's
     * request but a request of their own. The control: a model somebody else serves is asked, on every one of the paths.
     */
    @Test
    void whenNobodyIsLeftToServeAModelEveryPathSaysSoAndSendsNothing() throws Exception {
        Setup.Probe probe = Setup.liveProbe();
        // somebody serves it: every path asks the router (the control, and the count)
        OpenRouterProviders.FETCH = url -> url.contains("/endpoints") ? endpoints("baidu/fp8", "fireworks") : null;
        DriveClient c = new DriveClient(drive, MODEL);
        c.chat(hello(), null, 32, "none");
        c.classify(hello(), 8);
        String said = probe.chat(drive, MODEL, "");
        assertEquals(3, bodies.size(), "a turn, a classification and setup's hello each reached the router: " + bodies.size());
        assertEquals("ready", said, "and setup's hello was answered");
        for (JsonNode b : bodies) assertTrue(ignored(b).contains("baidu"), "each with the list: " + b);
        // nobody is left
        bodies.clear();
        OpenRouterProviders.forget();
        OpenRouterProviders.FETCH = url -> url.contains("/endpoints") ? endpoints("baidu/fp8", "alibaba", "baidu") : null;
        Map<String, String> told = new LinkedHashMap<>();
        DriveClient none = new DriveClient(drive, MODEL);
        try { none.chat(hello(), null, 32, "none"); told.put("a turn", "ANSWERED"); } catch (RuntimeException e) { told.put("a turn", e.getMessage()); }
        try { String r = none.classify(hello(), 8); told.put("a classification", r.isEmpty() ? "no answer: " + DriveClient.lastClassifyProblem() : "ANSWERED " + r); } catch (RuntimeException e) { told.put("a classification", e.getMessage()); }
        try { none.postChat(M.createObjectNode().put("max_tokens", 4).set("messages", hello())); told.put("a typed decision", "ANSWERED"); } catch (Exception e) { told.put("a typed decision", e.getMessage()); }
        told.put("setup's hello", probe.chat(drive, MODEL, ""));
        int sent = bodies.size();
        // and when who serves the model cannot be read: the hello goes with its list, and the router's own refusal is said the same way
        OpenRouterProviders.forget();
        OpenRouterProviders.FETCH = url -> null;
        status = 404; answer = "{\"error\":{\"message\":\"No endpoints found matching your data policy.\",\"code\":404}}";
        String refused = probe.chat(drive, MODEL, "");
        status = 404; answer = "{\"error\":{\"message\":\"The model does not exist.\",\"code\":404}}";
        String other = probe.chat(drive, MODEL, "");
        System.out.println("[review] D-163, D-788 nobody left to serve the model: requests sent " + sent + "; what each path says: " + told + "; setup's hello when the router itself refuses: " + refused + "; another 404: " + other);
        assertEquals(0, sent, "something was sent though nobody is left to serve the model");
        for (Map.Entry<String, String> e : told.entrySet()) assertTrue(String.valueOf(e.getValue()).contains("Every provider that serves " + MODEL + " through OpenRouter") && e.getValue().contains("researchzosho settings set avoid_places"), e.getKey() + " says which setting allows them: " + e.getValue());
        assertTrue(told.get("setup's hello").startsWith("!"), "as the reason a hello failed");
        assertTrue(refused.startsWith("!OpenRouter found no provider for " + MODEL) && refused.contains("researchzosho settings set avoid_places"), "the router's own refusal, said as this library says it: " + refused);
        assertEquals("!the server answered 404", other, "any other 404 stays the server's");
    }
}
