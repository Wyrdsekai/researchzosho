package org.researchzosho.records;

import java.util.List;

import java.util.Collection;
/**
 * One searchable collection of records, described as data: what it holds, for which countries, languages and years,
 * the address that searches it, and where in its answer the title, the date, the link and the citation of each hit are.
 * The built-in list is {@code sources.json} beside this class; a library's owner adds more in
 * {@code record-sources.json} in the settings folder, in the same form, so a country this build does not know is a
 * file edit and not a release.
 *
 * <p>{@code url} takes {@code {query}} (or {@code {query:bare}}, its quotes removed; {@code {query:last}} and {@code {query:rest}} for a collection that takes a family name and given names apart), {@code {limit}}, {@code {from}}, {@code {to}} and {@code {key}}; a
 * part in square brackets is dropped when a value inside it is missing. A collection that pages takes {@code {page}} (the page asked for, counted
 * from 1, empty on the first page so that a bracketed page part drops and the first page's address is what it always was), {@code {page0}} (the same
 * counted from 0), {@code {offset0}} (the records before the page) or {@code {first}}-{@code {last}} (the range of the page's records, counted
 * from 1), and {@code max} says how many records one page may hold (twenty when it says nothing). {@code fields} names the fields whose runs search it
 * (genealogy, software); {@code key_header} sends the key as a header ("Authorization: Bearer {key}") and {@code key_optional} marks a key
 * that only raises a limit. A source whose key comes from a login ({@code key_login}, an address to open with the application's key
 * {@code app} in it) is signed in to with {@code records login}: the person pastes the address they land on and the token is taken from it. Every other field is a template over one hit:
 * {@code {/json/pointer}} for a JSON answer, {@code {element}} for an XML one.
 */
public record RecordSource(String id, String name, String holds, String kind, List<String> countries, List<String> languages,
                           int fromYear, int toYear, String tier, String url, String format, String items,
                           String title, String date, String link, String snippet, String where,
                           String keyName, String keyFrom, boolean keyOptional, String keyHeader, String keyLogin, String appName, String appFrom, List<String> fields, List<String> hosts, boolean builtIn,
                           String secretName, String tokenUrl, String accept, int emptyStatus, int perRun, int max) {

    /** Whether one run may search this collection only so many times ({@code per_run}): a collection whose monthly allowance is small, such as SerpApi's 250. */
    public boolean hasAllowance() { return perRun > 0; }

    /** How many records one page of this collection gives at most ({@code max}); twenty when the collection says nothing. */
    public int pageSize() { return max > 0 ? max : 20; }

    /** Whether the address pages: page 2 of a search is the records after the first page's, so a whole class, name or span of years can be taken in page by page. */
    public boolean pages() { return url != null && (url.contains("{page}") || url.contains("{page0}") || url.contains("{offset0}") || url.contains("{first}")); }

    /** A source that hands out a short-lived token for a key and a secret (OAuth's client credentials): the search then carries the token. */
    public boolean exchangesAToken() { return tokenUrl != null && !tokenUrl.isBlank(); }

    /** A key it cannot work without. An optional one (GitHub's, which only raises a limit) does not count. */
    public boolean needsKey() { return keyName != null && !keyName.isBlank() && !keyOptional; }

    /** Whether a run of this field is offered the source; a source that names no field goes to every field that works from records. */
    public boolean forField(Collection<String> running) { return fields.isEmpty() || running == null || running.isEmpty() || fields.stream().anyMatch(running::contains); }

    /** Whether a page on this source is the record itself (a newspaper page, a patent, a scanned book) and not an index or a catalogue entry pointing to one. */
    public boolean holdsTheRecord() { return !"index".equals(kind) && !"catalogue".equals(kind); }

    public boolean xml() { return "xml".equalsIgnoreCase(format); }

    public int fromYear() { return fromYear; }
    public int toYear() { return toYear; }

    /** "1756-1963", "from 1790", "" when the source gives no years. */
    public String years() {
        if (fromYear > 0 && toYear > 0) return fromYear + "-" + toYear;
        if (fromYear > 0) return "from " + fromYear;
        return toYear > 0 ? "to " + toYear : "";
    }
}
