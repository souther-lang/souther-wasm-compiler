package souther.wasm.link;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * What a component offers, written out as a reader of interfaces reads it.
 *
 * <p>The component carries this already — it is in its type section, and a tool that reads
 * components prints it back. What this is for is the reader who has not got the component yet: a
 * caller generating bindings against a program still being written, or a review of what a build
 * would offer before it offers it.
 *
 * <p>So it is written from the same thing the component is built from and never from the component,
 * because two accounts of one offering is one more than there should be.
 */
public final class WitText {

    private WitText() {
    }

    /**
     * The interfaces a program offers and the ones it has to be given, as a reader reads them.
     *
     * @param offering the same thing {@link Component#around(byte[], Offering)} is given
     */
    public static String of(Offering offering) {
        Map<String, Map<String, String>> asked = new LinkedHashMap<>();
        for (Component.Reach reach : offering.reaches()) {
            asked.computeIfAbsent(reach.module(), held -> new LinkedHashMap<>())
                    .put(reach.behavior(), reach.behavior());
        }

        StringBuilder out = new StringBuilder("package root:component;\n\nworld program {\n");
        for (String module : asked.keySet()) {
            out.append("  import ").append(Component.askedUnder(module)).append(";\n");
        }
        for (String module : offering.behaviors().keySet()) {
            out.append("  export ").append(Component.offeredAs(module)).append(";\n");
        }
        for (String module : offering.readable().keySet()) {
            out.append("  export ").append(Component.readAs(module)).append(";\n");
        }
        out.append("}\n");
        packageOf(out, ASKED, asked, Component::askedUnder, TAKES);
        packageOf(out, UNDER, offering.behaviors(), Component::offeredAs, TAKES);
        packageOf(out, READ_UNDER, offering.readable(), Component::readAs, GIVEN);
        return out.toString();
    }

    /** The interfaces of one package, which is one per Souther module with anything in it. */
    private static void packageOf(StringBuilder out, String held,
            Map<String, Map<String, String>> modules, UnaryOperator<String> exportedAs,
            String takes) {
        if (modules.isEmpty()) {
            return;
        }
        out.append("\npackage ").append(held).append(" {\n");
        for (Map.Entry<String, Map<String, String>> module : modules.entrySet()) {
            String exported = exportedAs.apply(module.getKey());
            out.append("  interface ").append(exported.substring(exported.indexOf('/') + 1))
                    .append(" {\n");
            for (String crossing : Component.namesIn(
                    module.getKey(), module.getValue()).values()) {
                out.append("    ").append(crossing)
                        .append(": func(").append(takes).append(": string) -> string;\n");
            }
            out.append("  }\n");
        }
        out.append("}\n");
    }

    /** The package a module's own behaviors are offered in. */
    private static final String UNDER = "souther:program";

    /** The package what a program reaches out for is asked for in. */
    private static final String ASKED = "souther:reached";

    /** The package the types a caller may read a value of are read in. */
    private static final String READ_UNDER = "souther:decode";

    /** What a behavior takes. */
    private static final String TAKES = "arguments";

    /** What a type is read from. */
    private static final String GIVEN = "value";

    /** What every function crosses as, and what a caller does with it. */
    public static final String PREAMBLE = """
            // What a Souther program offers. A behavior takes the arguments of one call as a JSON
            // array and answers a JSON object: either {"value": ...} or {"issues": [...]}, where an
            // issue names where in the arguments it is about. A function of souther:decode takes
            // one value of its type as JSON and answers the same way, an issue naming where in the
            // value it is about. Nothing else crosses — the shape of what goes in and comes back
            // is the model's, and it is written in Souther, not here.
            """;

    /**
     * The whole file: what a caller has to know, then the interfaces.
     *
     * @param offering as {@link #of}
     */
    public static String written(Offering offering) {
        return PREAMBLE + "\n" + of(offering);
    }
}
