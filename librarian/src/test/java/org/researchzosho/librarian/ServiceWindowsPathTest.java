package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A Windows user folder with a space in its name (C:\Users\Ann Hale): the service's script lives under it, and both ways the service is
 * started (the logon task, and the start right after install) must hand PowerShell the script's whole path. The test follows the command
 * the way Windows does: Java writes the command line, the program splits it by the C runtime's rules, and Start-Process joins its
 * argument list with spaces and quotes nothing.
 */
class ServiceWindowsPathTest {

    static final Path HOME = Path.of("C:\\Users\\Ann Hale");

    @Test
    void theLogonTaskRunsTheScriptInAUserFolderWithASpace() {
        var p = Service.plan(Service.Os.windows, "C:\\Program Files\\ResearchZosho\\bin\\researchzosho.bat", 4649, 3, HOME);
        String script = p.definition().toString();
        assertTrue(script.contains(" "), script);
        List<String> schtasks = argv(commandLine(p.install().get(0)));
        String tr = schtasks.get(schtasks.indexOf("/TR") + 1);
        List<String> task = argv(tr);
        assertEquals("powershell", task.get(0), tr);
        assertEquals(script, task.get(task.indexOf("-File") + 1), "the task's command line: " + tr);
    }

    @Test
    void theServiceStartedAtInstallRunsTheScriptInAUserFolderWithASpace() {
        var p = Service.plan(Service.Os.windows, "C:\\Program Files\\ResearchZosho\\bin\\researchzosho.bat", 4649, 3, HOME);
        String script = p.definition().toString();
        List<String> ps = argv(commandLine(p.install().get(1)));
        assertEquals("powershell", ps.get(0));
        // powershell -Command runs the rest of its arguments, joined by spaces, as one command
        String command = String.join(" ", ps.subList(ps.indexOf("-Command") + 1, ps.size()));
        assertTrue(command.startsWith("Start-Process -FilePath powershell"), command);
        List<String> child = argv(startProcessArguments(command));
        assertEquals(script, child.get(child.indexOf("-File") + 1), "the started PowerShell's arguments: " + child + " from " + command);
        assertTrue(child.containsAll(List.of("-NoProfile", "-WindowStyle", "Hidden", "-ExecutionPolicy", "Bypass")), child.toString());
    }

    /** The command line Java writes for a program on Windows: an argument with a space or a tab, not quoted already, goes in quotes as it is. */
    static String commandLine(List<String> cmd) {
        StringBuilder b = new StringBuilder();
        for (String s : cmd) {
            if (!b.isEmpty()) b.append(' ');
            boolean quoted = s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"") && !s.endsWith("\\\"");
            if (!quoted && (s.contains(" ") || s.contains("\t") || s.contains("<") || s.contains(">"))) b.append('"').append(s).append(s.endsWith("\\") ? "\\" : "").append('"');
            else b.append(s);
        }
        return b.toString();
    }

    /** A command line split into arguments by the C runtime's rules: quotes group, a backslash before a quote escapes it. */
    static List<String> argv(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false, any = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\\') {
                int n = 0;
                while (i < line.length() && line.charAt(i) == '\\') { n++; i++; }
                if (i < line.length() && line.charAt(i) == '"') {
                    cur.append("\\".repeat(n / 2));
                    if (n % 2 == 1) cur.append('"'); else inQuotes = !inQuotes;
                } else { cur.append("\\".repeat(n)); i--; }
                any = true;
            } else if (c == '"') { inQuotes = !inQuotes; any = true; }
            else if ((c == ' ' || c == '\t') && !inQuotes) { if (any) { out.add(cur.toString()); cur.setLength(0); any = false; } }
            else { cur.append(c); any = true; }
        }
        if (any) out.add(cur.toString());
        return out;
    }

    /**
     * The command line Start-Process gives the program it starts, from its -ArgumentList: a list @('a','b') joined by spaces with nothing
     * quoted, or one string built with + from single-quoted strings and [char]34.
     */
    static String startProcessArguments(String command) {
        Matcher m = Pattern.compile("-ArgumentList\\s+(@?)\\((.*?)\\)\\s+-WindowStyle").matcher(command);
        assertTrue(m.find(), command);
        Matcher part = Pattern.compile("'((?:[^']|'')*)'|\\[char\\]34").matcher(m.group(2));
        List<String> parts = new ArrayList<>();
        while (part.find()) parts.add(part.group(1) != null ? part.group(1).replace("''", "'") : "\"");
        return String.join(m.group(1).equals("@") ? " " : "", parts);
    }
}
