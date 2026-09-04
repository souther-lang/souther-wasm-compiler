package souther.wasm.link;

import java.util.LinkedHashMap;
import java.util.List;
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
        return of(behaviors, List.of());
    }

    /**
     * The interfaces a program offers and the ones it has to be given, as a reader reads them.
     *
     * @param behaviors as {@link #of(Map)}
     * @param reaches the behaviors the program reaches out for — the same thing
     *     {@link Component#around(byte[], Map, List)} is given
     */
    public static String of(Map<String, Map<String, String>> behaviors,
            List<Component.Reach> reaches) {
        Map<String, Map<String, String>> asked = new LinkedHashMap<>();
        for (Component.Reach reach : reaches) {
            asked.computeIfAbsent(reach.module(), held -> new LinkedHashMap<>())
                    .put(reach.behavior(), reach.behavior());
        }

        StringBuilder out = new StringBuilder("package root:component;\n\nworld program {\n");
        for (String module : asked.keySet()) {
            out.append("  import ").append(Component.askedUnder(module)).append(";\n");
        }
        for (String module : behaviors.keySet()) {
            out.append("  export ").append(Component.offeredAs(module)).append(";\n");
        }
        out.append("}\n");
        packageOf(out, ASKED, asked);
        packageOf(out, UNDER, behaviors);
        return out.toString();
    }

    /** The interfaces of one package, which is one per Souther module with anything in it. */
    private static void packageOf(StringBuilder out, String held,
            Map<String, Map<String, String>> modules) {
        if (modules.isEmpty()) {
            return;
        }
        out.append("\npackage ").append(held).append(" {\n");
        for (Map.Entry<String, Map<String, String>> module : modules.entrySet()) {
            out.append("  interface ").append(named(module.getKey())).append(" {\n");
            for (String crossing : Component.namesIn(
                    module.getKey(), module.getValue()).values()) {
                out.append("    ").append(crossing)
                        .append(": func(arguments: string) -> string;\n");
            }
            out.append("  }\n");
        }
        out.append("}\n");
    }

    /** The package a module's own behaviors are offered in. */
    private static final String UNDER = "souther:program";

    /** The package what a program reaches out for is asked for in. */
    private static final String ASKED = "souther:reached";

    /** What every behavior crosses as, and what a caller does with it. */
    public static final String PREAMBLE = """
            // What a Souther program offers. A behavior takes the arguments of one call as a JSON
            // array and answers a JSON object: either {"value": ...} or {"issues": [...]}, where an
            // issue names where in the arguments it is about. Nothing else crosses — the shape of
            // what goes in and comes back is the model's, and it is written in Souther, not here.
            """;

    /** What an interface calls a module, which is what the component exports it under. */
    private static String named(String module) {
        String held = Component.offeredAs(module);
        return held.substring(held.indexOf('/') + 1);
    }

    /**
     * The whole file: what a caller has to know, then the interfaces.
     *
     * @param behaviors as {@link #of}
     */
    public static String written(Map<String, Map<String, String>> behaviors) {
        return written(behaviors, List.of());
    }

    /**
     * The whole file, for a program that reaches out for what it does not implement.
     *
     * @param behaviors as {@link #of(Map)}
     * @param reaches as {@link #of(Map, List)}
     */
    public static String written(Map<String, Map<String, String>> behaviors,
            List<Component.Reach> reaches) {
        return PREAMBLE + "\n" + of(behaviors, reaches);
    }
}
