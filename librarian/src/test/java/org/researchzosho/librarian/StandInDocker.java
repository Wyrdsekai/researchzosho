package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.researchzosho.Main;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * A stand-in for the {@code docker} command (0.8.0), as the RAR programs are stood in for: it records every command line it is given,
 * keeps the containers and networks it was asked to make, and for {@code docker run} starts the library as a process of this machine
 * from what the command line says: its folders from the mounts, its settings from the env file (read when it starts, as docker reads
 * it), its port, its program arguments. Nothing of Docker is needed. A container it did not start (another program's, named like
 * ours) is put in with {@link #put}. The library runs on this machine's network, where the container has none: what it reaches, it
 * reaches through the node's sockets all the same, since that is how it is told to reach anything.
 */
final class StandInDocker implements ContainerLibraries.Docker {
    static final ObjectMapper M = new ObjectMapper();

    /** A container: its id, its labels, the process standing for it (null: one that runs nothing here), whether it goes when it stops. */
    static final class Box {
        final String name, id; final Map<String, String> labels; final Process process; final boolean rm; volatile boolean running = true;
        final Map<String, String> env; final List<String> args;
        Box(String name, String id, Map<String, String> labels, Process process, boolean rm, Map<String, String> env, List<String> args) {
            this.name = name; this.id = id; this.labels = labels; this.process = process; this.rm = rm; this.env = env; this.args = args;
        }
        boolean up() { return process == null ? running : process.isAlive(); }
    }

    final List<List<String>> ran = new CopyOnWriteArrayList<>();
    final Map<String, Box> boxes = new ConcurrentHashMap<>();
    final Map<String, Map<String, String>> networks = new ConcurrentHashMap<>();
    /** Every container's env file as docker read it, by the container's name, and where the file was. */
    final Map<String, Path> envFiles = new ConcurrentHashMap<>();
    volatile boolean image = true;
    private final Random random = new Random();

    String newId() { byte[] b = new byte[32]; random.nextBytes(b); return HexFormat.of().formatHex(b); }

    /** A container this stand-in did not start: another program's, or one of ours left from before. */
    Box put(String name, Map<String, String> labels, Process process) {
        Box b = new Box(name, newId(), labels, process, false, Map.of(), List.of());
        boxes.put(name, b);
        return b;
    }

    /** The command lines of one verb ("run", "stop", "rm", …). */
    List<List<String>> ran(String verb) { return ran.stream().filter(c -> !c.isEmpty() && c.get(0).equals(verb)).toList(); }

    Box byId(String id) { return boxes.values().stream().filter(b -> b.id.equals(id) || b.name.equals(id)).findFirst().orElse(null); }

    @Override public ContainerLibraries.Result run(List<String> args, long ms) throws IOException {
        ran.add(List.copyOf(args));
        String verb = args.get(0);
        if (verb.equals("container") && args.get(1).equals("inspect")) {
            Box b = boxes.get(args.get(2));
            if (b == null) return new ContainerLibraries.Result(1, "Error response from daemon: No such container: " + args.get(2) + "\n");
            ArrayNode a = M.createArrayNode();
            ObjectNode o = a.addObject();
            o.put("Id", b.id); o.put("Name", "/" + b.name);
            ObjectNode labels = o.putObject("Config").putObject("Labels");
            b.labels.forEach(labels::put);
            o.putObject("State").put("Running", b.up());
            return new ContainerLibraries.Result(0, a.toString());
        }
        if (verb.equals("network") && args.get(1).equals("inspect")) {
            Map<String, String> labels = networks.get(args.get(2));
            if (labels == null) return new ContainerLibraries.Result(1, "Error response from daemon: network " + args.get(2) + " not found\n");
            ArrayNode a = M.createArrayNode();
            ObjectNode o = a.addObject();
            o.put("Name", args.get(2));
            ObjectNode l = o.putObject("Labels");
            labels.forEach(l::put);
            o.putObject("IPAM").putArray("Config").addObject().put("Subnet", "127.0.0.0/8").put("Gateway", "127.0.0.1");   // this machine's loopback stands for the network
            return new ContainerLibraries.Result(0, a.toString());
        }
        if (verb.equals("network") && args.get(1).equals("create")) {
            Map<String, String> labels = new LinkedHashMap<>();
            for (int i = 2; i < args.size() - 1; i++) if (args.get(i).equals("--label")) { String[] kv = args.get(++i).split("=", 2); labels.put(kv[0], kv[1]); }
            networks.put(args.get(args.size() - 1), labels);
            return new ContainerLibraries.Result(0, newId() + "\n");
        }
        if (verb.equals("image")) return new ContainerLibraries.Result(image ? 0 : 1, image ? "sha256:" + newId() + "\n" : "Error: No such image\n");
        if (verb.equals("pull")) { image = true; return new ContainerLibraries.Result(0, args.get(args.size() - 1) + "\n"); }
        if (verb.equals("stop")) {
            Box b = byId(args.get(args.size() - 1));
            if (b == null) return new ContainerLibraries.Result(1, "Error response from daemon: No such container: " + args.get(args.size() - 1) + "\n");
            b.running = false;
            if (b.process != null) {
                b.process.destroy();
                try { if (!b.process.waitFor(30, TimeUnit.SECONDS)) b.process.destroyForcibly(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            if (b.rm) boxes.remove(b.name, b);
            return new ContainerLibraries.Result(0, b.id + "\n");
        }
        if (verb.equals("rm")) {
            Box b = byId(args.get(args.size() - 1));
            if (b == null) return new ContainerLibraries.Result(1, "Error response from daemon: No such container: " + args.get(args.size() - 1) + "\n");
            if (b.process != null) b.process.destroyForcibly();
            boxes.remove(b.name, b);
            return new ContainerLibraries.Result(0, b.id + "\n");
        }
        return new ContainerLibraries.Result(1, "the stand-in does not know: docker " + String.join(" ", args) + "\n");
    }

    @Override public Process start(List<String> args, File log) throws IOException {
        ran.add(List.copyOf(args));
        String name = null; boolean rm = false; Path envFile = null;
        Map<String, String> labels = new LinkedHashMap<>(), mounts = new LinkedHashMap<>();
        int i = 1;
        for (; i < args.size(); i++) {
            String a = args.get(i);
            if (!a.startsWith("--")) break;   // the image
            switch (a) {
                case "--rm" -> rm = true;
                case "--init", "--read-only" -> { }
                case "--name" -> name = args.get(++i);
                case "--label" -> { String[] kv = args.get(++i).split("=", 2); labels.put(kv[0], kv[1]); }
                case "--volume" -> { String v = args.get(++i); int c = v.lastIndexOf(':'); mounts.put(v.substring(c + 1), v.substring(0, c)); }
                case "--env-file" -> envFile = Path.of(args.get(++i));
                default -> i++;   // a flag with a value: --user, --memory, --cpus, --publish, …
            }
        }
        List<String> program = new ArrayList<>(args.subList(i + 1, args.size()));
        if (name != null && boxes.containsKey(name)) return new ProcessBuilder("sh", "-c", "echo 'Conflict. The container name is already in use.' >&2; exit 125").redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(log)).start();
        Map<String, String> env = new LinkedHashMap<>();
        if (envFile != null) {
            envFiles.put(name, envFile);
            for (String line : Files.readAllLines(envFile, StandardCharsets.UTF_8)) { int eq = line.indexOf('='); if (eq > 0) env.put(line.substring(0, eq), line.substring(eq + 1)); }
        }
        // the library as a process here: what the container would see at a mount is the folder mounted there
        List<String> cmd = new ArrayList<>();
        cmd.add(ProcessHandle.current().info().command().orElse("java"));
        cmd.addAll(List.of("--add-opens", "java.base/java.lang=ALL-UNNAMED", "--enable-native-access=ALL-UNNAMED"));
        for (String opt : env.getOrDefault("RESEARCHZOSHO_JAVA_OPTS", "").split("\\s+")) if (!opt.isEmpty()) cmd.add(host(opt, mounts));
        cmd.addAll(List.of("-cp", System.getProperty("java.class.path"), Main.class.getName()));
        for (String word : program) cmd.add(host(word, mounts));   // a folder the container sees is the folder mounted there
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.environment().keySet().removeIf(k -> k.startsWith("RESEARCHZOSHO_") || k.startsWith("CODEZAIKU_") || k.startsWith("PG"));   // a container has only what its env file gives it
        env.forEach((k, v) -> { if (!k.equals("RESEARCHZOSHO_JAVA_OPTS")) pb.environment().put(k, host(v, mounts)); });
        Process p = pb.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(log)).start();
        Box b = new Box(name, newId(), labels, p, rm, Map.copyOf(env), List.copyOf(args));
        boxes.put(name, b);
        if (rm) p.onExit().thenRun(() -> boxes.remove(b.name, b));
        return p;
    }

    /** A value with a container's path in it put as the folder mounted there. */
    static String host(String value, Map<String, String> mounts) {
        for (Map.Entry<String, String> m : mounts.entrySet()) {
            String at = m.getKey();
            for (String lead : List.of("", "=")) {
                int k = value.indexOf(lead + at);
                if (k < 0 || (lead.isEmpty() && k != 0)) continue;
                int end = k + lead.length() + at.length();
                if (end == value.length() || value.charAt(end) == '/') return value.substring(0, k + lead.length()) + m.getValue() + value.substring(end);
            }
        }
        return value;
    }

    /** Every process this stand-in started, ended. */
    void endAll() {
        for (Box b : boxes.values()) if (b.process != null) b.process.destroyForcibly();
        boxes.clear();
    }
}
