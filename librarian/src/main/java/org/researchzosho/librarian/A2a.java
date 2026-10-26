package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.researchzosho.Version;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The library as an agent other agents can call: the Agent2Agent protocol (A2A 1.0, its JSON-RPC binding), 0.6.6. The card at
 * {@code /.well-known/agent-card.json} says what the library is and does; {@code POST /a2a} takes the calls. Both answer only
 * while the library is shared (the door is open): a card for a library nobody can reach would only say that it exists.
 *
 * <p>Two skills. {@code ask} answers from the shelves at once, as {@code library_ask}: the reply is a Message. {@code research}
 * files a research run, as {@code POST /v1/research}: the reply is a Task whose id is the run's, SUBMITTED while it waits, WORKING
 * while it runs, COMPLETED with the investigation as its artifact, FAILED or CANCELED otherwise; GetTask reads it, CancelTask
 * stops it. A message chooses the skill with {@code metadata.skill}; without one it is an ask. The caller's bearer token is one of
 * the library's own reader or writer tokens, and decides what it may do, as everywhere else. Nothing streams and nothing is pushed.
 */
final class A2a {
    static final String CARD = "/.well-known/agent-card.json", PATH = "/a2a", PROTOCOL_VERSION = "1.0";
    private static final ObjectMapper M = new ObjectMapper();
    private static final String ERROR_INFO = "type.googleapis.com/google.rpc.ErrorInfo", DOMAIN = "a2a-protocol.org";

    /** What the daemon replies: the HTTP status and the JSON body. */
    record Out(int status, ObjectNode body) { }

    private final LibrarianDaemon daemon;
    private final LibraryStore store;

    A2a(LibrarianDaemon daemon, LibraryStore store) { this.daemon = daemon; this.store = store; }

    /** The address the card gives for the calls: where the library is reached from outside when the relay said, else the door on this machine. */
    String address() {
        String a = Share.address();
        if (a.isBlank()) a = "http://127.0.0.1:" + daemon.doorPort();
        return a.replaceAll("/+$", "");
    }

    private static Out closed() {
        ObjectNode e = M.createObjectNode();
        e.putObject("error").put("code", "not_found").put("message", "This library is not shared, so it has no agent card. `researchzosho share` opens it to other agents.");
        return new Out(404, e);
    }

    /** A body that is not JSON. */
    static Out parseError() { return error(null, -32700, "The request body is not JSON.", "PARSE_ERROR", 200); }

    /** A token the library refuses: the HTTP status says so, as the protocol asks, and the body says why. */
    static Out forbidden(String why) { return error(null, -32603, why, "FORBIDDEN", 403); }

    // ---- the card ----

    Out card() throws IOException {
        if (!daemon.doorOpen()) return closed();
        LibraryStore.Identity id = store.identity();
        ObjectNode c = M.createObjectNode();
        c.put("name", id.name() == null || id.name().isBlank() ? "A ResearchZosho library" : id.name());
        c.put("description", "A research library kept by ResearchZosho. Ask it a question and it answers from what it holds, with the entries the answer rests on. "
                + "Ask it to research a question and it runs a research run: it reads the web and its shelves, checks what it finds, and writes an investigation.");
        String v = Version.number();
        c.put("version", v == null ? "dev" : v);
        c.put("documentationUrl", "https://researchzosho.org/");
        ObjectNode i = c.putArray("supportedInterfaces").addObject();
        i.put("url", address() + PATH); i.put("protocolBinding", "JSONRPC"); i.put("protocolVersion", PROTOCOL_VERSION);
        ObjectNode caps = c.putObject("capabilities");
        caps.put("streaming", false); caps.put("pushNotifications", false); caps.put("extendedAgentCard", false);
        ObjectNode bearer = c.putObject("securitySchemes").putObject("bearer").putObject("httpAuthSecurityScheme");
        bearer.put("scheme", "Bearer");
        bearer.put("description", "A token of this library: a reader's for ask, a writer's for research. The person who keeps the library issues them.");
        c.putArray("securityRequirements").addObject().putObject("schemes").putObject("bearer").putArray("list");
        c.putArray("defaultInputModes").add("text/plain");
        c.putArray("defaultOutputModes").add("text/plain").add("text/markdown");
        ArrayNode skills = c.putArray("skills");
        skill(skills, "ask", "Ask the library", "Answers a question from what the library holds: the findings and sources on its shelves, with the entries the answer rests on. "
                + "Nothing is looked up on the web. The skill a message gets when it names none.",
                List.of("Where did the family live after 1920?", "What is known about how the Antikythera mechanism's gears were cut?"));
        skill(skills, "research", "Research a question", "Files a research run on the question: the library reads the web and its shelves, checks what it finds, and writes an investigation. "
                + "The task is COMPLETED when the investigation is on the shelves, minutes to an hour later, and GetTask reads it. "
                + "Choose it with metadata.skill = \"research\" on the message. Needs a writer's token.",
                List.of("Research how the gears of the Antikythera mechanism were cut."));
        return new Out(200, c);
    }

    private static void skill(ArrayNode skills, String id, String name, String description, List<String> examples) {
        ObjectNode s = skills.addObject();
        s.put("id", id); s.put("name", name); s.put("description", description);
        s.putArray("tags").add("research").add("library").add("sources");
        ArrayNode ex = s.putArray("examples");
        for (String e : examples) ex.add(e);
        s.putArray("inputModes").add("text/plain");
        s.putArray("outputModes").add("text/plain").add("text/markdown");
    }

    // ---- the calls ----

    /** One JSON-RPC request, answered in A2A's terms; the library's own refusals are translated. */
    Out rpc(JsonNode req, Patrons.Patron patron) {
        if (!daemon.doorOpen()) return closed();
        JsonNode idNode = req.path("id");
        String method = req.path("method").asText("");
        JsonNode params = req.path("params");
        try {
            if (!req.isObject() || !"2.0".equals(req.path("jsonrpc").asText("")) || method.isEmpty()) throw new Refusal(-32600, "INVALID_REQUEST", "The request must be a JSON-RPC 2.0 object with a method.");
            ObjectNode result = switch (method) {
                case "SendMessage" -> send(params, patron);
                case "GetTask" -> task(params.path("id").asText(""), patron);
                case "CancelTask" -> cancel(params.path("id").asText(""), patron);
                case "ListTasks" -> list(params, patron);
                case "SendStreamingMessage", "SubscribeToTask" -> throw new Refusal(-32004, "UNSUPPORTED_OPERATION", "This library does not stream: SendMessage answers, and GetTask reads a research run.");
                case "CreateTaskPushNotificationConfig", "GetTaskPushNotificationConfig", "ListTaskPushNotificationConfigs", "DeleteTaskPushNotificationConfig" ->
                        throw new Refusal(-32003, "PUSH_NOTIFICATION_NOT_SUPPORTED", "This library sends no push notifications: GetTask reads a run's state.");
                case "GetExtendedAgentCard" -> throw new Refusal(-32004, "UNSUPPORTED_OPERATION", "This library has no extended card: the public one says everything.");
                default -> throw new Refusal(-32601, "METHOD_NOT_FOUND", "No A2A method is named " + method + ".");
            };
            ObjectNode out = M.createObjectNode();
            out.put("jsonrpc", "2.0"); out.set("id", idNode.isMissingNode() ? M.nullNode() : idNode); out.set("result", result);
            return new Out(200, out);
        } catch (Refusal r) { return error(idNode, r.code, r.getMessage(), r.reason, 200); }
        catch (ProtocolError e) {
            if ("forbidden".equals(e.code)) return error(idNode, -32603, e.getMessage(), "FORBIDDEN", 403);
            if ("invalid_args".equals(e.code)) return error(idNode, -32602, e.getMessage(), "INVALID_PARAMS", 200);
            return error(idNode, -32603, e.getMessage(), e.code.toUpperCase(Locale.ROOT), 200);
        } catch (Exception e) { return error(idNode, -32603, "The library could not answer: " + e.getMessage(), "INTERNAL", 200); }
    }

    private static final class Refusal extends RuntimeException {
        final int code; final String reason;
        Refusal(int code, String reason, String message) { super(message); this.code = code; this.reason = reason; }
    }

    private static Out error(JsonNode id, int code, String message, String reason, int status) {
        ObjectNode out = M.createObjectNode();
        out.put("jsonrpc", "2.0"); out.set("id", id == null || id.isMissingNode() ? M.nullNode() : id);
        ObjectNode e = out.putObject("error");
        e.put("code", code); e.put("message", message);
        if (reason != null) { ObjectNode info = e.putArray("data").addObject(); info.put("@type", ERROR_INFO); info.put("reason", reason); info.put("domain", DOMAIN); }
        return new Out(status, out);
    }

    /** A message in: an ask answered at once as a Message, or a research run filed and returned as a Task. */
    private ObjectNode send(JsonNode params, Patrons.Patron patron) throws IOException {
        JsonNode msg = params.path("message");
        if (!msg.isObject()) throw new Refusal(-32602, "INVALID_PARAMS", "SendMessage needs params.message with text parts.");
        String text = text(msg);
        if (text.isBlank()) throw new Refusal(-32602, "INVALID_PARAMS", "The message has no text part to answer.");
        String skill = msg.path("metadata").path("skill").asText("ask").strip().toLowerCase(Locale.ROOT);
        ObjectNode result = M.createObjectNode();
        if (skill.equals("research")) {
            ObjectNode ask = M.createObjectNode();
            ask.put("question", text);
            ask.set("patron", LibrarianDaemon.patronNode(patron));
            JsonNode meta = msg.path("metadata");
            for (String k : List.of("mode", "sources", "field", "model")) if (meta.hasNonNull(k)) ask.put(k, meta.get(k).asText());
            for (String k : List.of("max_turns", "max_minutes")) if (meta.path(k).isInt()) ask.put(k, meta.get(k).asInt());
            if (meta.path("quick").isBoolean()) ask.put("quick", meta.get("quick").asBoolean());
            if (meta.path("sub_questions").isArray()) ask.set("sub_questions", meta.get("sub_questions").deepCopy());
            ObjectNode filed = daemon.research(ask, patron);
            String jobId = filed.path("job_id").asText(), ctx = "ctx-" + jobId;   // the context is the run
            ObjectNode task = result.putObject("task");
            task.put("id", jobId); task.put("contextId", ctx);
            int ahead = filed.path("queued_ahead").asInt(0);
            status(task, "TASK_STATE_SUBMITTED", "Research run " + jobId + " is filed" + (ahead > 0 ? ", " + ahead + " ahead of it" : "") + ". GetTask reads how it goes.", ctx, jobId);
            task.putArray("history").add(withIds(msg, ctx, jobId));
            return result;
        }
        if (!skill.equals("ask")) throw new Refusal(-32602, "INVALID_PARAMS", "No skill is named " + skill + ". The skills are ask and research.");
        String ctx = msg.path("contextId").asText("");
        if (ctx.isBlank()) ctx = "ctx-" + UUID.randomUUID();
        ObjectNode ask = M.createObjectNode();
        ask.put("question", text);
        ask.set("patron", LibrarianDaemon.patronNode(patron));
        ObjectNode a = new LibraryProtocol(store).ask(ask);
        boolean nothing = a.path("holds_nothing").asBoolean(false);
        String rendered = a.path("rendered").asText("");
        ObjectNode reply = result.putObject("message");
        reply.put("messageId", "m-" + UUID.randomUUID()); reply.put("contextId", ctx); reply.put("role", "ROLE_AGENT");
        reply.putArray("parts").addObject().put("text", nothing || rendered.isBlank() ? "This library holds nothing on that yet." : rendered);
        ObjectNode meta = reply.putObject("metadata");
        meta.put("holds_nothing", nothing);
        meta.set("entries", a.path("entries").isArray() ? a.get("entries").deepCopy() : M.createArrayNode());
        if (a.path("peers").isArray() && !a.path("peers").isEmpty()) meta.set("peers", a.get("peers").deepCopy());
        return result;
    }

    /** The text of a message: its text parts, joined. A 0.3 part says kind: text beside the text; the text is the same. */
    static String text(JsonNode msg) {
        StringBuilder sb = new StringBuilder();
        for (JsonNode p : msg.path("parts")) if (p.hasNonNull("text")) { if (sb.length() > 0) sb.append('\n'); sb.append(p.get("text").asText()); }
        return sb.toString().strip();
    }

    private static ObjectNode withIds(JsonNode msg, String contextId, String taskId) {
        ObjectNode m = msg.deepCopy();
        if (!m.hasNonNull("messageId")) m.put("messageId", "m-" + UUID.randomUUID());
        if (!m.hasNonNull("role")) m.put("role", "ROLE_USER");
        m.put("contextId", contextId); m.put("taskId", taskId);
        return m;
    }

    private static void status(ObjectNode task, String state, String say, String contextId, String taskId) {
        ObjectNode st = task.putObject("status");
        st.put("state", state); st.put("timestamp", Instant.now().toString());
        if (say != null && !say.isBlank()) {
            ObjectNode m = st.putObject("message");
            m.put("messageId", "m-" + UUID.randomUUID()); m.put("contextId", contextId); m.put("taskId", taskId); m.put("role", "ROLE_AGENT");
            m.putArray("parts").addObject().put("text", say);
        }
    }

    /** A run as a Task: its state in A2A's words, and the investigation as the artifact once it is done. */
    private ObjectNode task(String id, Patrons.Patron patron) throws IOException {
        Patrons.check(store, patron, Patrons.Level.read);
        ObjectNode j = id.isBlank() ? null : daemon.jobs().get(id);
        if (j == null || !Jobs.visibleTo(patron, j)) throw new Refusal(-32001, "TASK_NOT_FOUND", "No research run has the id " + id + ".");
        return taskOf(j, true);
    }

    private ObjectNode taskOf(ObjectNode j, boolean full) throws IOException {
        ObjectNode v = Jobs.view(j);
        String id = v.path("job_id").asText(j.path("job_id").asText("")), ctx = "ctx-" + id;
        ObjectNode t = M.createObjectNode();
        t.put("id", id); t.put("contextId", ctx);
        String st = j.path("state").asText("");
        String state = switch (st) {
            case "queued" -> "TASK_STATE_SUBMITTED";
            case "running" -> "TASK_STATE_WORKING";
            case "done" -> v.path("is_error").asBoolean(false) ? "TASK_STATE_FAILED" : "TASK_STATE_COMPLETED";
            case "failed" -> "TASK_STATE_FAILED";
            case "stopped" -> "TASK_STATE_CANCELED";
            default -> "TASK_STATE_SUBMITTED";   // held for the person's word, or offered material: it waits, and the status says for what
        };
        String say = v.path("waiting").asText("");
        if (say.isBlank() && !state.equals("TASK_STATE_WORKING") && !state.equals("TASK_STATE_SUBMITTED")) say = v.path("result").asText("");
        status(t, state, say, ctx, id);
        if (full) {
            String question = v.path("question").asText("");
            if (!question.isBlank()) {
                ObjectNode m = t.putArray("history").addObject();
                m.put("messageId", "m-" + id); m.put("contextId", ctx); m.put("taskId", id); m.put("role", "ROLE_USER");
                m.putArray("parts").addObject().put("text", question);
            }
            String inv = v.path("investigation").asText("");
            if (state.equals("TASK_STATE_COMPLETED") && !inv.isBlank()) {
                Investigation i = store.investigation(inv);
                if (i != null) {
                    ObjectNode a = t.putArray("artifacts").addObject();
                    a.put("artifactId", i.id()); a.put("name", i.title()); a.put("description", "The investigation this run wrote, as it stands on the shelves.");
                    ObjectNode p = a.putArray("parts").addObject();
                    p.put("text", i.body()); p.put("mediaType", "text/markdown");
                }
            }
        }
        ObjectNode meta = t.putObject("metadata");
        meta.put("state", st);
        if (v.hasNonNull("investigation")) meta.put("investigation", v.get("investigation").asText());
        return t;
    }

    /** A stop, as {@code library_job op=stop}: a queued run never starts, a running one ends within seconds; the Task comes back CANCELED. */
    private ObjectNode cancel(String id, Patrons.Patron patron) throws IOException {
        Patrons.check(store, patron, Patrons.Level.write);
        ObjectNode j = id.isBlank() ? null : daemon.jobs().get(id);
        if (j == null || !Jobs.visibleTo(patron, j)) throw new Refusal(-32001, "TASK_NOT_FOUND", "No research run has the id " + id + ".");
        Jobs.Stop now = daemon.jobs().stopRun(id, patron.writer());
        if (now == null) throw new Refusal(-32002, "TASK_NOT_CANCELABLE", "Research run " + id + " is not queued or running. It is " + j.path("state").asText() + ".");
        long until = System.currentTimeMillis() + 5_000;   // a running one ends within seconds; the answer says what it is by then
        while (System.currentTimeMillis() < until) {
            j = daemon.jobs().get(id);
            if (j == null || !"running".equals(j.path("state").asText()) && !"queued".equals(j.path("state").asText())) break;
            try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
        if (j == null) throw new Refusal(-32001, "TASK_NOT_FOUND", "No research run has the id " + id + ".");
        return taskOf(j, true);
    }

    /** The research runs this caller may see, newest first, as Tasks without their history; {@code status} and {@code contextId} narrow it. */
    private ObjectNode list(JsonNode params, Patrons.Patron patron) throws IOException {
        ObjectNode a = M.createObjectNode();
        a.set("patron", LibrarianDaemon.patronNode(patron));
        a.put("limit", Math.min(200, Math.max(1, params.path("pageSize").asInt(20))));
        if (params.hasNonNull("pageToken") && !params.get("pageToken").asText().isBlank()) a.put("cursor", params.get("pageToken").asText());
        ObjectNode r = new LibraryProtocol(store).job(a);
        String want = params.path("status").asText("").strip().toUpperCase(Locale.ROOT).replace("TASK_STATE_", "");
        String ctx = params.path("contextId").asText("").strip();
        ObjectNode out = M.createObjectNode();
        ArrayNode tasks = out.putArray("tasks");
        for (String k : List.of("active", "finished")) for (JsonNode v : r.path(k)) {
            ObjectNode j = daemon.jobs().get(v.path("job_id").asText(""));
            if (j == null || !"research".equals(j.path("kind").asText(""))) continue;
            ObjectNode t = taskOf(j, false);
            if (!want.isBlank() && !t.path("status").path("state").asText().equals("TASK_STATE_" + want)) continue;
            if (!ctx.isBlank() && !t.path("contextId").asText().equals(ctx)) continue;
            tasks.add(t);
        }
        out.put("nextPageToken", r.hasNonNull("next_cursor") ? r.get("next_cursor").asText() : "");
        return out;
    }
}
