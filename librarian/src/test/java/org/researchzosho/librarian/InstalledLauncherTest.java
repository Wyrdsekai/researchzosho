package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The launchers an install ships (gradle's start scripts, bin/researchzosho and bin/zosho with their .bat twins) hand Java the options the
 * service was installed with, RESEARCHZOSHO_JAVA_OPTS, as the source tree's launcher does: that is where the service's heap cap is. The
 * person's own JAVA_OPTS and RESEARCHZOSHO_OPTS come after it, so they still win.
 */
class InstalledLauncherTest {

    /** The start scripts the build wrote: researchzosho and zosho, each with its .bat. */
    static List<Path> scripts() {
        String dirs = System.getProperty("launchers.dirs", "");
        assumeTrue(!dirs.isBlank(), "run through gradle, which writes the start scripts first");
        List<Path> out = new ArrayList<>();
        for (String d : dirs.split(Pattern.quote(File.pathSeparator))) {
            try (var files = Files.list(Path.of(d))) { files.sorted().forEach(out::add); } catch (Exception e) { fail("no start scripts in " + d + ": " + e); }
        }
        assertEquals(4, out.size(), out.toString());
        return out;
    }

    /** The Java options the Linux service definition sets for the launcher. */
    static String serviceOpts() {
        assumeTrue(System.getenv("RESEARCHZOSHO_JAVA_OPTS") == null && System.getenv("CODEZAIKU_JAVA_OPTS") == null, "the service passes a person's own options through instead");
        String unit = Service.plan(Service.Os.linux, "/opt/researchzosho/bin/researchzosho", 4649, 3, Path.of("/home/ann")).text();
        Matcher m = Pattern.compile("Environment=\"RESEARCHZOSHO_JAVA_OPTS=([^\"]*)\"").matcher(unit);
        assertTrue(m.find(), unit);
        assertTrue(m.group(1).contains("-Xmx"), m.group(1));
        return m.group(1);
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void theShellLaunchersHandJavaTheServicesOptions(@TempDir Path tmp) throws Exception {
        String opts = serviceOpts();
        String xmx = opts.substring(opts.indexOf("-Xmx")).split(" ")[0];
        // a Java that prints the arguments it was given, one to a line
        Path java = Files.createDirectories(tmp.resolve("jdk").resolve("bin")).resolve("java");
        Files.writeString(java, "#!/bin/sh\nfor a in \"$@\"; do printf '%s\\n' \"$a\"; done\n", StandardCharsets.UTF_8);
        assertTrue(java.toFile().setExecutable(true));
        for (Path script : scripts()) {
            if (script.getFileName().toString().endsWith(".bat")) continue;
            Path bin = Files.createDirectories(tmp.resolve("app-" + script.getFileName()).resolve("bin"));
            Path launcher = Files.copy(script, bin.resolve(script.getFileName()));
            assertTrue(launcher.toFile().setExecutable(true));
            String name = script.getFileName().toString();

            List<String> args = run(launcher, tmp, Map.of("RESEARCHZOSHO_JAVA_OPTS", opts));
            assertTrue(args.contains(xmx), name + " hands Java the service's heap cap: " + args);
            assertTrue(args.indexOf(xmx) < args.indexOf("-classpath"), args.toString());

            // an install from before the split names them CODEZAIKU_JAVA_OPTS, as the source tree's launcher still reads
            assertTrue(run(launcher, tmp, Map.of("CODEZAIKU_JAVA_OPTS", "-Xmx3g")).contains("-Xmx3g"), name);

            // the person's own options come after it: the last -Xmx is the one Java uses
            List<String> own = run(launcher, tmp, Map.of("RESEARCHZOSHO_JAVA_OPTS", opts, "JAVA_OPTS", "-Xmx8g"));
            assertTrue(own.indexOf(xmx) < own.indexOf("-Xmx8g"), name + ": " + own);
        }
    }

    @Test
    void theWindowsLaunchersHandJavaTheServicesOptions() throws Exception {
        String opts = serviceOpts();
        for (Path script : scripts()) {
            if (!script.getFileName().toString().endsWith(".bat")) continue;
            String bat = Files.readString(script, StandardCharsets.UTF_8);
            List<String> args = batArgs(bat, Map.of("RESEARCHZOSHO_JAVA_OPTS", opts, "JAVA_EXE", "C:\\jdk\\bin\\java.exe"));
            String xmx = opts.substring(opts.indexOf("-Xmx")).split(" ")[0];
            assertTrue(args.contains(xmx), script.getFileName() + " hands Java the service's heap cap: " + args);
            assertTrue(batArgs(bat, Map.of("CODEZAIKU_JAVA_OPTS", "-Xmx3g", "JAVA_EXE", "java.exe")).contains("-Xmx3g"), script.getFileName().toString());
            List<String> own = batArgs(bat, Map.of("RESEARCHZOSHO_JAVA_OPTS", opts, "JAVA_OPTS", "-Xmx8g", "JAVA_EXE", "java.exe"));
            assertTrue(own.indexOf(xmx) < own.indexOf("-Xmx8g"), own.toString());
        }
    }

    /** The launcher run with only {@code env} of the Java settings, and a JAVA_HOME whose java prints its arguments. */
    static List<String> run(Path launcher, Path tmp, Map<String, String> env) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(launcher.toString(), "status").redirectErrorStream(true);
        for (String k : List.of("JAVA_OPTS", "RESEARCHZOSHO_OPTS", "ZOSHO_OPTS", "RESEARCHZOSHO_JAVA_OPTS", "CODEZAIKU_JAVA_OPTS")) pb.environment().remove(k);
        pb.environment().put("JAVA_HOME", tmp.resolve("jdk").toString());
        pb.environment().putAll(env);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, p.waitFor(), out);
        return out.lines().toList();
    }

    /**
     * The arguments a .bat start script gives Java, read the way cmd does for the lines that decide them: {@code set} and {@code if not
     * defined … set} expand %NAME% from what is set so far, and the line that runs "%JAVA_EXE%" is expanded and split into arguments.
     * {@code env} names the java.exe that runs (JAVA_EXE).
     */
    static List<String> batArgs(String bat, Map<String, String> env) {
        Map<String, String> vars = new HashMap<>(env);
        Pattern set = Pattern.compile("(?i)^(?:if not defined (\\w+) )?set (\\w+)=(.*)$");
        for (String line : bat.split("\\R")) {
            String l = line.strip();
            Matcher m = set.matcher(l);
            if (m.matches()) {
                if (m.group(2).equals("JAVA_HOME") || m.group(2).equals("JAVA_EXE")) continue;   // which java.exe runs, not what it is given
                if (m.group(1) != null && vars.containsKey(m.group(1))) continue;
                String v = expand(m.group(3), vars);
                if (v.isEmpty()) vars.remove(m.group(2)); else vars.put(m.group(2), v);
            } else if (l.startsWith("\"%JAVA_EXE%\"")) {
                return ServiceWindowsPathTest.argv(expand(l, vars));
            }
        }
        fail("no line runs Java:\n" + bat);
        return List.of();
    }

    static String expand(String s, Map<String, String> vars) {
        Matcher m = Pattern.compile("%(\\w+)%").matcher(s);
        StringBuilder b = new StringBuilder();
        while (m.find()) m.appendReplacement(b, Matcher.quoteReplacement(vars.getOrDefault(m.group(1), "")));
        m.appendTail(b);
        return b.toString();
    }
}
