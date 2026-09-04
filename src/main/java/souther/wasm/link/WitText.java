package souther.wasm.link;

import java.util.Map;

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
     * The interfaces a program offers, as a reader of interfaces reads them.
     *
     * @param behaviors every behavior's core export name, by the module that declares it, in the
     *     order the program declares them — the same thing {@link Component#around} is given
     */
    public static String of(Map<String, Map<String, String>> behaviors) {
        StringBuilder out = new StringBuilder("package souther:program;\n");
        for (Map.Entry<String, Map<String, String>> module : behaviors.entrySet()) {
            out.append("\ninterface ").append(module.getKey()).append(" {\n");
            for (String crossing : Component.namesIn(module.getKey(), module.getValue()).values()) {
                out.append("  ").append(crossing)
                        .append(": func(arguments: string) -> string;\n");
            }
            out.append("}\n");
        }
        out.append("\nworld program {\n");
        for (String module : behaviors.keySet()) {
            out.append("  export ").append(module).append(";\n");
        }
        return out.append("}\n").toString();
    }

    /** What every behavior crosses as, and what a caller does with it. */
    public static final String PREAMBLE = """
            // What a Souther program offers. A behavior takes the arguments of one call as a JSON
            // array and answers a JSON object: either {"value": ...} or {"issues": [...]}, where an
            // issue names where in the arguments it is about. Nothing else crosses — the shape of
            // what goes in and comes back is the model's, and it is written in Souther, not here.
            """;

    /**
     * The whole file: what a caller has to know, then the interfaces.
     *
     * @param behaviors as {@link #of}
     */
    public static String written(Map<String, Map<String, String>> behaviors) {
        return PREAMBLE + "\n" + of(behaviors);
    }
}
