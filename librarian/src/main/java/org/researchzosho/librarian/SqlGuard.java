package org.researchzosho.librarian;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The check every SQL statement passes before it runs against a person's database. One statement, and
 * it must only read. The connection is opened read-only as well, and a read-only database user is what
 * the documentation recommends: this is the third lock, not the only one.
 */
public final class SqlGuard {

    private SqlGuard() { }

    static final List<String> MAY_START = List.of("select", "with", "show", "explain", "describe", "desc", "values", "table");
    /**
     * Words that can change data or reach outside the database from INSIDE a statement that starts with SELECT or WITH:
     * a data-changing CTE, SELECT INTO, a locking read, COPY. Statement-level commands (SET, LOCK, BEGIN…) need no entry
     * here because the statement must start with a reading word. Quoted text is emptied first, and `_` is a word
     * character, so a column called last_update or a string 'delete me' does not trip this.
     */
    static final Pattern FORBIDDEN = Pattern.compile("\\b(insert|update|delete|merge|upsert|drop|alter|create|truncate|grant|revoke|attach|detach|copy|call|exec|execute|vacuum|pragma|into|outfile|dumpfile)\\b");
    /** Functions that read or write the server's files, run commands, sleep, or open other connections. */
    static final Pattern FUNCTIONS = Pattern.compile("\\b(pg_read_file|pg_read_binary_file|pg_ls_dir|pg_stat_file|lo_import|lo_export|dblink\\w*|pg_sleep\\w*|load_file|sys_exec|sys_eval|xp_cmdshell|xp_\\w+|sp_\\w+|openrowset|openquery|openxml|opendatasource|benchmark|sleep|load_extension|readfile|writefile|fts3_tokenizer|read_blob|read_text|read_csv\\w*|read_parquet|read_json\\w*|read_ndjson\\w*|parquet_scan|parquet_metadata|sniff_csv|glob|shell|system)\\s*\\(");

    /**
     * SQL Server runs two statements with no semicolon between them ("SELECT 1 SHUTDOWN"), so the one-statement rule
     * does not hold there and a rolled-back transaction does not undo SHUTDOWN, KILL or BACKUP. These are T-SQL
     * statement words. Every one is a reserved word in T-SQL, so a column with such a name has to be written [use] or
     * "use", and bracketed and quoted names are emptied before the check.
     */
    static final Pattern TSQL = Pattern.compile("\\b(shutdown|kill|backup|restore|dbcc|reconfigure|waitfor|checkpoint|use|bulk|setuser|revert|deny|declare|begin|commit|rollback|save|set|print|raiserror|readtext|writetext|updatetext|while|goto|if|updlock|xlock|tablockx)\\b");

    /** Null when the statement may run; otherwise the reason it may not, in plain words. */
    public static String refuse(String sql) { return refuse(sql, ""); }

    /** The same check for one kind of database: SQL Server has its own statement words and its own [quoted names]. */
    public static String refuse(String sql, String kind) {
        if (sql == null || sql.isBlank()) return "The query is empty.";
        boolean tsql = "sqlserver".equals(kind);
        String bare = strip(sql, tsql).strip();
        while (bare.endsWith(";")) bare = bare.substring(0, bare.length() - 1).strip();
        if (bare.isEmpty()) return "The query is empty.";
        if (bare.contains(";")) return "Only one statement at a time. Remove the semicolon and everything after it.";
        String lower = bare.toLowerCase(Locale.ROOT);
        String first = lower.split("[\\s(]+", 2)[0];
        if (!MAY_START.contains(first)) return "Only queries that read are allowed. The statement must start with SELECT, WITH, SHOW, EXPLAIN or DESCRIBE, not " + first.toUpperCase(Locale.ROOT) + ".";
        var m = FORBIDDEN.matcher(lower);
        if (m.find()) return "Only queries that read are allowed. This one contains " + m.group(1).toUpperCase(Locale.ROOT) + ".";
        if (tsql) { var t = TSQL.matcher(lower); if (t.find()) return "Only queries that read are allowed. This one contains " + t.group(1).toUpperCase(Locale.ROOT) + "."; }
        var f = FUNCTIONS.matcher(lower);
        if (f.find()) return "The function " + f.group(1) + "() is not allowed here.";
        return null;
    }

    /** The statement without comments and with every quoted string and quoted name emptied, so a word inside quotes is not read as SQL. */
    static String strip(String sql) { return strip(sql, false); }

    /** With {@code brackets}, a T-SQL [quoted name] is emptied too. It is read in the same pass as the strings, so a bracket inside a string does not open one. */
    static String strip(String sql, boolean brackets) {
        StringBuilder out = new StringBuilder();
        int i = 0, n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') { while (i < n && sql.charAt(i) != '\n') i++; continue; }
            if (c == '#') { while (i < n && sql.charAt(i) != '\n') i++; continue; }
            if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') { int e = sql.indexOf("*/", i + 2); i = e < 0 ? n : e + 2; out.append(' '); continue; }
            if (brackets && c == '[') {
                i++;
                while (i < n) { if (sql.charAt(i) == ']') { if (i + 1 < n && sql.charAt(i + 1) == ']') { i += 2; continue; } break; } i++; }
                i++; out.append("[]"); continue;
            }
            if (c == '\'' || c == '"' || c == '`') {
                char q = c; i++;
                while (i < n) { if (sql.charAt(i) == q) { if (i + 1 < n && sql.charAt(i + 1) == q) { i += 2; continue; } break; } if (sql.charAt(i) == '\\' && q == '\'' && i + 1 < n) i++; i++; }
                i++; out.append(q).append(q); continue;
            }
            out.append(c); i++;
        }
        return out.toString();
    }

    /** The aggregation stages a MongoDB pipeline may use: the ones that read. */
    static final java.util.Set<String> MONGO_STAGES = java.util.Set.of("$match", "$group", "$sort", "$limit", "$skip", "$project", "$count", "$unwind", "$addFields", "$set", "$unset",
            "$bucket", "$bucketAuto", "$facet", "$sample", "$sortByCount", "$lookup", "$replaceRoot", "$replaceWith", "$redact", "$densify", "$fill", "$setWindowFields", "$geoNear");

    /** Null when a MongoDB filter or pipeline (as JSON text) may run; otherwise the reason. */
    public static String refuseMongo(String json) {
        if (json == null || json.isBlank()) return null;
        String lower = json.toLowerCase(Locale.ROOT);
        for (String bad : List.of("$out", "$merge", "$where", "$function", "$accumulator", "mapreduce", "$currentop", "$listsessions", "$planCacheStats".toLowerCase(Locale.ROOT)))
            if (lower.contains("\"" + bad + "\"") || lower.contains(bad + ":") || lower.contains("'" + bad + "'")) return "Only stages that read are allowed. This one uses " + bad + ".";
        var m = Pattern.compile("\\{\\s*[\"']?(\\$[a-zA-Z]+)[\"']?\\s*:").matcher(json);
        // top-level stage names are checked by the caller against MONGO_STAGES; operators inside a stage are many and harmless
        return null;
    }
}
