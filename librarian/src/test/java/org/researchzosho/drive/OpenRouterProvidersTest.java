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
import org.researchzosho.librarian.Avoided;
import org.researchzosho.librarian.ModelEntries;
import org.researchzosho.librarian.ModelPlaces;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A hosted model reached through a router is never served from a place the library avoids (0.8.1): every request to OpenRouter names
 * the providers to leave out, which providers those are comes from OpenRouter's own list and the owner's setting, and the copy of the
 * list that ships is what is used when the live one cannot be read. The router here is a stand-in on the loopback, reached as a
 * node's gate reaches the real one, so the client takes it for OpenRouter and no test asks the real one.
 */
class OpenRouterProvidersTest {
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
        OpenRouterProviders.FETCH = url -> null;   // the live list cannot be read, unless a test says what it is
        OpenRouterProviders.forget();
        DriveClient.OpenRouterModels.OVERRIDE = Map.of();
        router = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        router.createContext("/", x -> {
            String body = new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (x.getRequestURI().getPath().endsWith("/chat/completions")) bodies.add(M.readTree(body));
            byte[] b = (x.getRequestURI().getPath().endsWith("/chat/completions") ? answer : "{}").getBytes(StandardCharsets.UTF_8);
            x.getResponseHeaders().add("Content-Type", "application/json");
            x.sendResponseHeaders(x.getRequestURI().getPath().endsWith("/chat/completions") ? status : 404, b.length); x.getResponseBody().write(b); x.close();
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

    static ArrayNode aTool() {
        ArrayNode t = M.createArrayNode();
        ObjectNode f = t.addObject().put("type", "function").putObject("function").put("name", "done");
        f.putObject("parameters").put("type", "object").putObject("properties");
        return t;
    }

    static List<String> ignored(JsonNode body) { List<String> out = new ArrayList<>(); for (JsonNode s : body.path("provider").path("ignore")) out.add(s.asText()); return out; }

    /** A list in the router's own shape: slug, name, headquarters, datacenters. */
    static String list(String... rows) {
        StringBuilder b = new StringBuilder("{\"data\":[");
        for (int i = 0; i < rows.length; i++) b.append(i == 0 ? "" : ",").append(rows[i]);
        // a list of fewer than five rows is taken for an answer cut short: the rest stand in for the many other providers
        for (String s : List.of("alpha", "beta", "gamma", "delta", "epsilon")) b.append(",{\"slug\":\"").append(s).append("\",\"name\":\"").append(s).append("\",\"headquarters\":\"US\",\"datacenters\":[\"US\"]}");
        return b.append("]}").toString();
    }

    @Test
    void everyRequestToTheRouterNamesTheProvidersToLeaveOut() throws Exception {
        assertTrue(new DriveClient(drive, MODEL).openRouter(), "the stand-in is taken for the router");
        List<String> shipped = OpenRouterProviders.ignored();
        assertEquals(List.of("alibaba", "antdigital", "baidu", "deepseek", "minimax", "moonshotai", "nex-agi", "novita", "seed", "siliconflow", "stepfun", "streamlake", "tencent", "xiaomi", "z-ai"), shipped,
                "the copy that ships, as the router's list stood when it was read: based or running in a place avoided by default, or owned by a company based in one");
        assertEquals(List.of("alibaba", "baidu", "deepseek", "nex-agi", "stepfun", "streamlake", "tencent", "xiaomi"), OpenRouterProviders.ignored(Avoided.places(), false), "by what the router's list states alone");
        assertEquals(List.of("antdigital", "minimax", "moonshotai", "novita", "seed", "siliconflow", "z-ai"), OpenRouterProviders.ignoredByOwner(Avoided.places()), "by their owner alone: registered in Singapore or the United States, owned from mainland China");
        assertEquals(Set.of("CN", "HK", "MO", "CU", "IR", "KP", "RU", "VE"), Avoided.places(), "unset: the countries of concern of 28 CFR 202.601, China with Hong Kong and Macau");
        assertTrue(OpenRouterProviders.shipped() && OpenRouterProviders.readOn().equals("2026-11-20"), "the copy that ships is in use, and says when it was read");
        DriveClient c = new DriveClient(drive, MODEL);
        c.chat(hello(), aTool(), 32, "auto");                 // a turn with tools
        c.chat(hello(), null, 32, "none");                    // a turn of words
        c.classify(hello(), 8);                               // a decision
        c.postChat(M.createObjectNode().put("max_tokens", 4).<ObjectNode>set("messages", hello()));   // a typed decision
        DriveClient.streamHere(s -> { });
        try { c.chat(hello(), null, 32, "none"); } finally { DriveClient.streamHere(null); }   // a turn that is streamed
        assertTrue(bodies.size() >= 5, "the stand-in had every request: " + bodies.size());
        for (JsonNode b : bodies) assertEquals(shipped, ignored(b), "a request to the router names the providers to leave out: " + b);
        assertTrue(bodies.get(0).path("provider").path("require_parameters").asBoolean(false), "beside what a turn with tools already asked of the router: " + bodies.get(0));
        // a body made outside the client (the probe that asks whether a model answers) carries it too
        ObjectNode probe = DriveClient.hostedShape(M.createObjectNode().put("model", MODEL), "https://openrouter.ai/api/v1");
        assertEquals(shipped, ignored(probe));
        // and no other host is told anything of the kind
        assertFalse(DriveClient.hostedShape(M.createObjectNode().put("model", "m"), "https://api.tokenfactory.nebius.com/v1").has("provider"));
    }

    @Test
    void theListIsTheRoutersOwnAndThePlacesAreTheOwners() throws Exception {
        // the router's list read now: a provider that moved, one that is new, one that states no place
        OpenRouterProviders.FETCH = url -> url.endsWith("/providers") ? list(
                "{\"slug\":\"baidu\",\"name\":\"Baidu\",\"headquarters\":\"CN\",\"datacenters\":[]}",
                "{\"slug\":\"harbour\",\"name\":\"Harbour\",\"headquarters\":\"SG\",\"datacenters\":[\"SG\",\"HK\"]}",
                "{\"slug\":\"quiet\",\"name\":\"Quiet\",\"headquarters\":null,\"datacenters\":null}",
                "{\"slug\":\"steppe\",\"name\":\"Steppe\",\"headquarters\":\"KZ\",\"datacenters\":[\"KZ\"]}",
                // the day the router's list names a provider in Russia, or one with machines in Iran, it is left out with no new version of the program
                "{\"slug\":\"volga\",\"name\":\"Volga\",\"headquarters\":\"RU\",\"datacenters\":[\"RU\"]}",
                "{\"slug\":\"caspian\",\"name\":\"Caspian\",\"headquarters\":\"AE\",\"datacenters\":[\"AE\",\"IR\"]}",
                // a row of the owners' table bears on a provider this list holds: registered in Singapore, owned from mainland China
                "{\"slug\":\"moonshotai\",\"name\":\"Moonshot AI\",\"headquarters\":\"SG\",\"datacenters\":[\"SG\"]}") : null;
        assertTrue(OpenRouterProviders.readNow(), "the list is read");
        assertEquals(List.of("baidu", "caspian", "harbour", "moonshotai", "volga"), OpenRouterProviders.ignored(), "based in China or Russia, running machines in Hong Kong or Iran, or owned from China; a provider that states no place is not left out");
        // the person lets the providers owned from an avoided place serve: one setting, and only those come back
        Config.set(Avoided.BY_OWNER, "off");
        assertEquals(List.of("baidu", "caspian", "harbour", "volga"), OpenRouterProviders.ignored());
        Config.set(Avoided.BY_OWNER, "");
        Config.set(Avoided.SETTING, "CN, HK, MO");
        assertEquals(1, OpenRouterProviders.unplaced(), "and how many state none is counted");
        assertFalse(OpenRouterProviders.shipped());
        // the owner names other places, or none
        Config.set(Avoided.SETTING, "KZ");
        assertEquals(List.of("steppe"), OpenRouterProviders.ignored());
        DriveClient c = new DriveClient(drive, MODEL);
        c.chat(hello(), null, 32, "none");
        assertEquals(List.of("steppe"), ignored(bodies.get(bodies.size() - 1)));
        Config.set(Avoided.SETTING, "none");
        assertEquals(List.of(), OpenRouterProviders.ignored());
        c.chat(hello(), aTool(), 32, "auto");
        JsonNode sent = bodies.get(bodies.size() - 1);
        assertFalse(sent.path("provider").has("ignore"), "no place avoided: no provider is left out: " + sent);
        assertTrue(sent.path("provider").path("require_parameters").asBoolean(false), "what a turn with tools asks of the router stays: " + sent);
        Config.set(Avoided.SETTING, "CN, HK, MO"); Config.set(Avoided.BY_OWNER, "off");   // the rest of this test is about where the list comes from
        // a reading that fails, or comes back as something else, changes nothing: the last list read stays in use
        OpenRouterProviders.FETCH = url -> "{\"error\":{\"message\":\"busy\"}}";
        assertFalse(OpenRouterProviders.readNow());
        assertEquals(List.of("baidu", "harbour"), OpenRouterProviders.ignored());
        // and a new start reads the list kept from the last reading, not the older copy that ships
        OpenRouterProviders.FETCH = url -> null;
        Path kept = OpenRouterProviders.kept();
        OpenRouterProviders.forget();
        assertTrue(Files.isRegularFile(kept), "the list read is kept beside the settings: " + kept);
        assertEquals(List.of("baidu", "harbour"), OpenRouterProviders.ignored());
    }

    static String endpoints(String... tags) {
        StringBuilder b = new StringBuilder("{\"data\":{\"id\":\"" + MODEL + "\",\"endpoints\":[");
        for (int i = 0; i < tags.length; i++) b.append(i == 0 ? "" : ",").append("{\"provider_name\":\"x\",\"tag\":\"").append(tags[i]).append("\"}");
        return b.append("]}}").toString();
    }

    @Test
    void aModelNobodyElseServesIsSaidInASentenceAndNothingIsSent() throws Exception {
        // the router's list of who serves the model: two providers, both in China
        OpenRouterProviders.FETCH = url -> url.contains("/endpoints") ? endpoints("baidu/fp8", "alibaba", "baidu") : null;
        DriveClient c = new DriveClient(drive, MODEL);
        DriveClient.NotServed none = assertThrows(DriveClient.NotServed.class, () -> c.chat(hello(), null, 32, "none"));
        assertEquals("Every provider that serves " + MODEL + " through OpenRouter is based or runs in a place this library keeps away from, or is owned by a company based in one, so nothing was sent. "
                + "The places: China, Hong Kong, Macao, Cuba, Iran, North Korea, Russia and Venezuela. "
                + "To let them serve it, change the places this library avoids: researchzosho settings set avoid_places none (or name the places to keep away from), or choose another model.", none.getMessage());
        assertEquals(0, bodies.size(), "and nothing was sent to the router");
        assertTrue(none instanceof Gated.Refused, "the chat says it as its reply, as it says a cap's sentence");
        // when who serves the model cannot be read, the request goes with its list, and the router's own refusal is said the same way
        OpenRouterProviders.FETCH = url -> null;
        OpenRouterProviders.forget();
        status = 404; answer = "{\"error\":{\"message\":\"No endpoints found matching your data policy.\",\"code\":404}}";
        DriveClient.NotServed refused = assertThrows(DriveClient.NotServed.class, () -> new DriveClient(drive, MODEL).chat(hello(), null, 32, "none"));
        assertTrue(refused.getMessage().startsWith("OpenRouter found no provider for " + MODEL + " among those this library allows") && refused.getMessage().contains("researchzosho settings set avoid_places none"), refused.getMessage());
        // the router's answer as it really came on 2026-11-20 (HTTP 404, "All providers have been ignored…", pointing at its own settings page):
        // the person is told by this library's sentence, which names this library's settings and not the router's page
        for (String real : List.of("openrouter-404-all-ignored.json", "openrouter-404-all-ignored-2.json")) {
            String body;
            try (InputStream in = OpenRouterProvidersTest.class.getResourceAsStream(real)) { body = new String(in.readAllBytes(), StandardCharsets.UTF_8); }
            assertTrue(body.contains("All providers have been ignored"), body);
            DriveClient.NotServed asItComes = assertThrows(DriveClient.NotServed.class, () -> new DriveClient(drive, MODEL).notServed(404, body), real);
            assertTrue(asItComes.getMessage().contains("researchzosho settings set avoid_by_owner off") && asItComes.getMessage().contains("researchzosho settings set avoid_places none") && !asItComes.getMessage().contains("openrouter.ai/settings"), asItComes.getMessage());
        }
        assertEquals(1, bodies.size(), "this one was sent, with its list: " + bodies);
        // another 404 (a model the router does not know) is not taken for it
        answer = "{\"error\":{\"message\":\"model not found\",\"code\":404}}";
        IllegalStateException other = assertThrows(IllegalStateException.class, () -> new DriveClient(drive, "nobody/knows").chat(hello(), null, 32, "none"));
        assertFalse(other instanceof DriveClient.NotServed, other.toString());
    }

    @Test
    void addingAModelSaysWhoServesItAndWhereANamedHostIsRunFrom() throws Exception {
        OpenRouterProviders.FETCH = url -> url.contains("/endpoints") ? endpoints("baidu/fp8", "alibaba", "deepinfra/fp8", "novita/fp8", "fireworks", "some-new-provider") : null;
        ModelEntries.Entry router = new ModelEntries.Entry("deepseek", "https://openrouter.ai/api/v1", MODEL, null, "", 0, "");
        assertEquals("Served by 6 providers. 2 are left out for the place they are based or run in (Alibaba, Baidu). 1 is left out for its owner's place (Novita); researchzosho settings set avoid_by_owner off lets it serve. 1 states no place and is not left out.", ModelPlaces.said(router));
        OpenRouterProviders.FETCH = url -> url.contains("/endpoints") ? endpoints("baidu/fp8", "alibaba") : null;
        OpenRouterProviders.forget();
        assertEquals("Every provider of this model is based or runs in a place this library avoids: it will not answer unless you allow them (researchzosho settings set avoid_places none).", ModelPlaces.said(router));
        // a model of a company whose own arm serves it: registered in Singapore, owned from mainland China, and one provider elsewhere
        OpenRouterProviders.FETCH = url -> url.contains("/endpoints") ? endpoints("moonshotai", "moonshotai/turbo", "fireworks", "baidu") : null;
        OpenRouterProviders.forget();
        assertEquals("Served by 3 providers. 1 is left out for the place it is based or runs in (Baidu). 1 is left out for its owner's place (Moonshot AI); researchzosho settings set avoid_by_owner off lets it serve.", ModelPlaces.said(router));
        OpenRouterProviders.FETCH = url -> url.contains("/endpoints") ? endpoints("moonshotai", "z-ai") : null;
        OpenRouterProviders.forget();
        String onlyTheirOwn = ModelPlaces.said(router);
        assertTrue(onlyTheirOwn.startsWith("Every provider of this model is left out for a place this library avoids or for its owner's") && onlyTheirOwn.contains("researchzosho settings set avoid_by_owner off") && onlyTheirOwn.contains("researchzosho settings set avoid_places none"), onlyTheirOwn);
        String noneLeft = OpenRouterProviders.noneLeft(OpenRouterProviders.served(MODEL));
        assertTrue(noneLeft.contains("or is owned by a company based in one, so nothing was sent.") && noneLeft.contains("To let the providers left out only because of the company that owns them serve it (Moonshot AI, Z.AI): researchzosho settings set avoid_by_owner off."), noneLeft);
        // the person allows them: they serve, and the line says so
        Config.set(Avoided.BY_OWNER, "off");
        assertEquals("Served by 2 providers. None of them is left out.", ModelPlaces.said(router));
        Config.set(Avoided.BY_OWNER, "");
        OpenRouterProviders.FETCH = url -> null;
        OpenRouterProviders.forget();
        assertEquals("", ModelPlaces.said(router), "who serves it cannot be read now: nothing is said that is not known");
        // a host the person named by its address: where it is run from, when that is known; it is used all the same
        ModelEntries.Entry own = new ModelEntries.Entry("ds", "https://api.deepseek.com/v1", "deepseek-chat", null, "", 0, "");
        assertTrue(ModelPlaces.said(own).startsWith("api.deepseek.com is run from China, a place this library keeps its content away from"), ModelPlaces.said(own));
        assertEquals("CN", ModelPlaces.placeOfHost("api.example.cn")); assertEquals("HK", ModelPlaces.placeOfHost("models.example.hk")); assertEquals("", ModelPlaces.placeOfHost("api.openai.com"));
        assertEquals("", ModelPlaces.said(new ModelEntries.Entry("sol", "https://api.openai.com/v1", "gpt-6.1-sol", null, "", 0, "")));
        // Hugging Face's router: a request cannot say which places to leave out, so the person is told; one provider named after the model's id is the only one asked
        ModelEntries.Entry hf = new ModelEntries.Entry("hf", "https://router.huggingface.co/v1", "deepseek-ai/DeepSeek-V4.1-Flash", null, "", 0, "");
        assertTrue(ModelPlaces.said(hf).startsWith("Hugging Face's router picks the provider for each request, and a request cannot say which places to leave out, so this model may be served from China, Hong Kong, Macao, Cuba, Iran, North Korea, Russia and Venezuela."), ModelPlaces.said(hf));
        assertEquals("", ModelPlaces.said(new ModelEntries.Entry("hf", "https://router.huggingface.co/v1", "deepseek-ai/DeepSeek-V4.1-Flash:together", null, "", 0, "")));
        // no place avoided: nothing to say of any of them
        Config.set(Avoided.SETTING, "none");
        assertEquals("", ModelPlaces.said(own)); assertEquals("", ModelPlaces.said(hf));
        Config.set(Avoided.SETTING, "");
        assertEquals("OpenRouter providers: 123; left out of every request for the place they are based or run in: 8; stating no place, not left out: 34 (the list as read on 2026-11-20). "
                + "Left out as well, because the company that owns them is based in such a place: 7 (antdigital, minimax, moonshotai, novita, seed, siliconflow, z-ai); by a table recorded on 2026-11-20 with a public source for each row; researchzosho settings set avoid_by_owner off lifts this. "
                + "The places this library avoids: China, Hong Kong, Macao, Cuba, Iran, North Korea, Russia and Venezuela (settings: avoid_places).", OpenRouterProviders.listSaid());
        // a host's own country letters place it, for any place: Russia's three endings among them; an ending sold to anybody places nothing
        assertEquals("RU", ModelPlaces.placeOfHost("gigachat.devices.sberbank.ru")); assertEquals("RU", ModelPlaces.placeOfHost("models.example.su")); assertEquals("RU", ModelPlaces.placeOfHost("models.example.xn--p1ai"));
        assertEquals("IR", ModelPlaces.placeOfHost("api.example.ir")); assertEquals("", ModelPlaces.placeOfHost("api.example.ai")); assertEquals("", ModelPlaces.placeOfHost("api.example.io"));
        assertTrue(ModelPlaces.said(new ModelEntries.Entry("giga", "https://gigachat.devices.sberbank.ru/api/v1", "GigaChat", null, "", 0, "")).startsWith("gigachat.devices.sberbank.ru is run from Russia, a place this library keeps its content away from"));
    }

    @Test
    void theCopyThatShipsHoldsNoAddressAndEveryProviderOnce() throws Exception {
        try (var in = OpenRouterProviders.class.getResourceAsStream("openrouter-providers.json")) {
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            JsonNode list = M.readTree(text);
            assertEquals("2026-11-20", list.path("read_on").asText());
            Set<String> slugs = new TreeSet<>();
            for (JsonNode p : list.path("data")) { assertTrue(slugs.add(p.path("slug").asText()), "once: " + p); assertEquals(Set.of("slug", "name", "headquarters", "datacenters"), Set.copyOf(M.convertValue(p, Map.class).keySet())); }
            assertEquals(123, slugs.size());
        }
    }

    /**
     * The owners' table is data with its sources: every row names a provider of the router's list, a country, the company as its
     * source names it, one public source with the words of it, and a note that says what the row rests on. A row that rests on two
     * documents read together is marked as a chain.
     */
    @Test
    void everyRowOfTheOwnersTableCarriesItsSource() throws Exception {
        try (InputStream in = OpenRouterProviders.class.getResourceAsStream("openrouter-controllers.json"); InputStream list = OpenRouterProviders.class.getResourceAsStream("openrouter-providers.json")) {
            JsonNode table = M.readTree(in);
            Set<String> onTheList = new HashSet<>();
            for (JsonNode p : M.readTree(list).path("data")) onTheList.add(p.path("slug").asText());
            assertEquals("2026-11-20", table.path("recorded_on").asText());
            List<String> slugs = new ArrayList<>();
            for (JsonNode r : table.path("rows")) {
                slugs.add(r.path("slug").asText());
                assertTrue(onTheList.contains(r.path("slug").asText()), "a provider of the router's list: " + r.path("slug"));
                assertTrue(r.path("country").asText().matches("[A-Z]{2}") && !r.path("company").asText().isBlank(), r.toString());
                assertTrue(r.path("source").asText().startsWith("https://") && r.path("says").asText().length() > 10 && r.path("note").asText().length() > 40, "its source, the words of it and what it rests on: " + r);
                for (JsonNode also : r.path("also")) assertTrue(also.path("source").asText().startsWith("https://") && !also.path("says").asText().isBlank(), also.toString());
            }
            assertEquals(List.of("alibaba", "antdigital", "minimax", "moonshotai", "novita", "seed", "siliconflow", "z-ai"), slugs);
            for (JsonNode r : table.path("rows")) assertEquals(r.path("slug").asText().equals("novita"), r.path("chain").asBoolean(false), "the one row that is a chain of two documents is marked: " + r.path("slug"));
            assertEquals(slugs.size(), OpenRouterProviders.controllers().size(), "every row is read");
        }
        // a host of Yandex's own domains is said to be Russia's, beside the hosts whose names end in Russia's letters
        for (String host : List.of("llm.api.cloud.yandex.net", "console.yandex.cloud", "yandex.com")) assertEquals("RU", ModelPlaces.placeOfHost(host), host);
    }
}
