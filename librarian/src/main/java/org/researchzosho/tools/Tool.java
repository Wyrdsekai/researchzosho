package org.researchzosho.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Set;

/**
 * One affordance the familiar can call. The schema is advertised to the drive; {@link #execute}
 * runs the real action and returns the RAW observation (faithful — no summarizing, cause-first
 * on failure). The ACI lives here: e.g. the write tool size-caps content.
 */
public interface Tool {
    String name();

    String description();

    /** The JSON-Schema {@code parameters} object for this tool. */
    ObjectNode parametersSchema(ObjectMapper j);

    /** Execute against real disk/shell and return the observation text the model will see. */
    String execute(JsonNode args) throws Exception;

    /**
     * Whether what this call returns came from outside the library: a search's answer, a page it fetched, an outside service's records,
     * the person's own read-only data. Only such an answer gives the addresses written in it to the model to open next. An answer made of
     * what the library stores (a report, a claim, a note, a question: text a model may have written) gives none, whatever it says; the
     * library gives the sources it recorded itself. False unless a tool says otherwise (0.8.1: a model wrote an address into a report in one
     * run, read the report back in a later turn, and the address was opened).
     */
    default boolean fromOutside(JsonNode args) { return false; }

    /**
     * The arguments of this call that are an address the tool will ask for, or hand to a service that asks for it, beside the ones
     * that are an address in every tool by their name ({@code GivenAddresses.addressKey}: url, address, urls, feed, and a name that
     * ends in url, uri or link). Such an argument is held to the rule a fetch's url is held to: its value has to be an address that
     * was given, with its scheme or without, alone or in a list, and it is handed to the tool as it was given. A tool whose argument
     * is an address only for some of its kinds says so by the call ({@code archive_search}: {@code query} for a page's saved copy and
     * its history). A test reads every tool's arguments and fails when one that speaks of an address is neither declared here nor
     * listed there as words, with the reason (0.8.1: an address nobody gave went to the web archives through {@code query}).
     */
    default Set<String> addressArguments(JsonNode args) { return Set.of(); }
}
