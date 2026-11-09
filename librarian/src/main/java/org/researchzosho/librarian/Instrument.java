package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.researchzosho.tools.Tool;

import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * One instrument on the brain's table: a bounded step with a result. Its words say what it answers, when it pays over the others and
 * what it costs, in one pattern, because the words are what the model reads to choose. An instrument may end early with a question
 * for the person ({@link Brain.Needed}); the loop hands that to the brain.
 */
public interface Instrument {
    String name();

    /** What it answers · when it pays over the others · what it costs. */
    String words();

    /** The arguments, as a JSON schema object. */
    ObjectNode schema(ObjectMapper m);

    /** A complete call for a small model to copy, as the arguments' JSON; "" when the instrument needs none. */
    default String example() { return ""; }

    Result run(ObjectNode args, Researcher.Budget budget, BooleanSupplier stop) throws Exception;

    /** What a step produced: its evidence, and the question it stopped on, or null. */
    record Result(List<Brain.Evidence> evidence, Brain.Needed needed) {
        public Result { evidence = evidence == null ? List.of() : List.copyOf(evidence); }
        public static Result of(String instrument, String address, String text) { return new Result(List.of(new Brain.Evidence(instrument, address, text)), null); }
        /** The text the model sees: the evidence pieces, then the question when there is one. */
        public String observation() {
            StringBuilder b = new StringBuilder();
            for (Brain.Evidence e : evidence) { if (!b.isEmpty()) b.append("\n\n"); b.append(e.text()); }
            if (needed != null) {
                if (!b.isEmpty()) b.append("\n\n");
                b.append("QUESTION FOR THE PERSON: ").append(needed.question());
                if (!needed.choices().isEmpty()) b.append(" Choices: ").append(String.join(" | ", needed.choices()));
            }
            return b.toString();
        }
    }

    /** The instrument as a tool a loop can keep by name and run, so the grouped names sit beside the old ones in one table. */
    default Tool asTool() {
        Instrument self = this;
        return new Tool() {
            @Override public String name() { return self.name(); }
            @Override public String description() { return self.words(); }
            @Override public ObjectNode parametersSchema(ObjectMapper j) { return self.schema(j); }
            @Override public String execute(JsonNode args) throws Exception {
                ObjectNode a = args instanceof ObjectNode o ? o : new ObjectMapper().createObjectNode();
                Result r = self.run(a, Instruments.budget(), Instruments.stop());
                Instruments.NEEDED.set(r.needed());
                return r.observation();
            }
        };
    }
}
