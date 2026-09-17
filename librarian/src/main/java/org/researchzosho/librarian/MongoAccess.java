package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * MongoDB, read-only. The Mongo driver is fetched on demand and loaded from its own class loader, so it
 * is called through reflection: a client, a database, its collections, a count, and find or aggregate
 * with a limit. Only stages that read are accepted.
 */
final class MongoAccess implements AutoCloseable {

    private static final ObjectMapper M = new ObjectMapper();
    private final ClassLoader loader;
    private final Object client, database;
    private final Class<?> documentClass;

    MongoAccess(String uri, String dbName) throws IOException {
        try {
            loader = DbDrivers.loader(DbDrivers.kind("mongo"));
            Class<?> clients = Class.forName("com.mongodb.client.MongoClients", true, loader);
            client = clients.getMethod("create", String.class).invoke(null, uri);
            database = call(client, "getDatabase", new Class<?>[]{String.class}, dbName);
            documentClass = Class.forName("org.bson.Document", true, loader);
        } catch (IOException e) { throw e; }
        catch (Exception e) { throw new IOException("Could not connect to MongoDB: " + cause(e)); }
    }

    /** The database named in a mongodb:// uri, or "". */
    static String databaseOf(String uri) {
        String rest = uri.replaceFirst("^mongodb(\\+srv)?://", "");
        int slash = rest.indexOf('/'); if (slash < 0) return "";
        String db = rest.substring(slash + 1); int q = db.indexOf('?'); if (q >= 0) db = db.substring(0, q);
        return db;
    }

    List<String> collections() throws IOException {
        try { List<String> out = new ArrayList<>(); for (Object n : (Iterable<?>) call(database, "listCollectionNames", new Class<?>[0])) out.add(String.valueOf(n)); java.util.Collections.sort(out); return out; }
        catch (Exception e) { throw new IOException("Could not list the collections: " + cause(e)); }
    }

    long count(String collection) throws IOException {
        try { return ((Number) call(collection(collection), "estimatedDocumentCount", new Class<?>[0])).longValue(); }
        catch (Exception e) { throw new IOException(cause(e)); }
    }

    /** Documents from find(filter) or aggregate(pipeline), as JSON trees, at most {@code limit}. */
    List<JsonNode> read(String collection, String filterJson, String pipelineJson, int limit) throws IOException {
        try {
            Object coll = collection(collection);
            Object iterable;
            if (pipelineJson != null && !pipelineJson.isBlank()) {
                JsonNode stages = M.readTree(pipelineJson);
                if (!stages.isArray()) throw new IOException("A pipeline is a JSON array of stages.");
                List<Object> docs = new ArrayList<>();
                for (JsonNode st : stages) {
                    String name = st.fieldNames().hasNext() ? st.fieldNames().next() : "";
                    if (!SqlGuard.MONGO_STAGES.contains(name)) throw new IOException("Only stages that read are allowed. " + (name.isEmpty() ? "A stage has no name." : name + " is not one of them."));
                    docs.add(parse(st.toString()));
                }
                docs.add(parse("{\"$limit\": " + limit + "}"));
                iterable = call(coll, "aggregate", new Class<?>[]{List.class}, docs);
            } else {
                Object filter = parse(filterJson == null || filterJson.isBlank() ? "{}" : filterJson);
                Object found = call(coll, "find", new Class<?>[]{Class.forName("org.bson.conversions.Bson", true, loader)}, filter);
                iterable = call(found, "limit", new Class<?>[]{int.class}, limit);
            }
            List<JsonNode> out = new ArrayList<>();
            Method toJson = documentClass.getMethod("toJson");
            for (Object d : (Iterable<?>) iterable) { out.add(M.readTree((String) toJson.invoke(d))); if (out.size() >= limit) break; }
            return out;
        } catch (IOException e) { throw e; }
        catch (Exception e) { throw new IOException(cause(e)); }
    }

    /** The fields seen in a sample of a collection, with the types seen for each. */
    Map<String, String> fields(String collection, int sample) throws IOException {
        Map<String, java.util.Set<String>> seen = new TreeMap<>();
        for (JsonNode d : read(collection, "{}", null, sample)) {
            var it = d.fields();
            while (it.hasNext()) { var e = it.next(); seen.computeIfAbsent(e.getKey(), k -> new java.util.TreeSet<>()).add(typeOf(e.getValue())); }
        }
        Map<String, String> out = new LinkedHashMap<>();
        seen.forEach((k, v) -> out.put(k, String.join(" | ", v)));
        return out;
    }

    static String typeOf(JsonNode v) {
        if (v.isObject()) { if (v.has("$oid")) return "id"; if (v.has("$date")) return "date"; if (v.has("$numberLong") || v.has("$numberDecimal")) return "number"; return "object"; }
        if (v.isArray()) return "array"; if (v.isNumber()) return "number"; if (v.isBoolean()) return "boolean"; if (v.isNull()) return "null";
        return "text";
    }

    private Object collection(String name) throws Exception { return call(database, "getCollection", new Class<?>[]{String.class}, name); }
    private Object parse(String json) throws Exception { return documentClass.getMethod("parse", String.class).invoke(null, json); }

    private static Object call(Object target, String method, Class<?>[] types, Object... args) throws Exception {
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            for (Class<?> i : allInterfaces(c)) { try { Method m = i.getMethod(method, types); return m.invoke(target, args); } catch (NoSuchMethodException ignored) { } }
        }
        Method m = target.getClass().getMethod(method, types); m.setAccessible(true); return m.invoke(target, args);
    }

    private static List<Class<?>> allInterfaces(Class<?> c) {
        List<Class<?>> out = new ArrayList<>();
        for (Class<?> i : c.getInterfaces()) { out.add(i); out.addAll(allInterfaces(i)); }
        return out;
    }

    static String cause(Throwable e) { Throwable t = e; while (t.getCause() != null && (t instanceof java.lang.reflect.InvocationTargetException || t.getMessage() == null)) t = t.getCause(); return String.valueOf(t.getMessage() == null ? t : t.getMessage()); }

    @Override public void close() { try { client.getClass().getMethod("close").invoke(client); } catch (Exception ignored) { } }
}
