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
            out.append("  import ").append(path(Component.askedUnder(module))).append(";\n");
        }
        for (String module : offering.behaviors().keySet()) {
            out.append("  export ").append(path(Component.offeredAs(module))).append(";\n");
        }
        for (String module : offering.readable().keySet()) {
            out.append("  export ").append(path(Component.readAs(module))).append(";\n");
        }
        out.append("}\n");
        packageOf(out, ASKED, asked, Component::askedUnder, TAKES, false);
        packageOf(out, UNDER, offering.behaviors(), Component::offeredAs, TAKES, true);
        packageOf(out, READ_UNDER, offering.readable(), Component::readAs, GIVEN, true);
        return out.toString();
    }

    /**
     * The interfaces of one package, which is one per Souther module with anything in it.
     *
     * @param ends whether a call of one of these functions can end, which the component's own
     *     functions answer as their {@code err}; what a program reaches out for answers a string
     */
    private static void packageOf(StringBuilder out, String held,
            Map<String, Map<String, String>> modules, UnaryOperator<String> exportedAs,
            String takes, boolean ends) {
        if (modules.isEmpty()) {
            return;
        }
        String answers = ends ? "result<string, " + Component.ENDED + ">" : "string";
        out.append("\npackage ").append(held).append(" {\n");
        for (Map.Entry<String, Map<String, String>> module : modules.entrySet()) {
            String exported = exportedAs.apply(module.getKey());
            out.append("  interface ").append(named(exported.substring(exported.indexOf('/') + 1)))
                    .append(" {\n");
            if (ends) {
                for (Component.Declared record : Component.RECORDS) {
                    out.append("    record ").append(record.name()).append(" { ")
                            .append(record.fields().stream()
                                    .map(field -> field.name() + ": " + written(field.held()))
                                    .collect(java.util.stream.Collectors.joining(", ")))
                            .append(" }\n");
                }
            }
            for (String crossing : Component.namesIn(
                    module.getKey(), module.getValue(), ends).values()) {
                out.append("    ").append(named(crossing)).append(": func(").append(takes)
                        .append(": string) -> ").append(answers).append(";\n");
            }
            out.append("  }\n");
        }
        out.append("}\n");
    }

    /**
     * A name Souther wrote, as WIT writes one: escaped, so that it names what it was written for
     * whatever it is. A module or a behavior may be called what WIT has a word for — {@code record},
     * {@code func} — and WIT reads an escaped name as a name, a word of its own included. Every one
     * is escaped and not only those that are words today, since which are is WIT's to add to.
     */
    private static String named(String name) {
        return "%" + name;
    }

    /** An interface's path, its name escaped as {@link #named} escapes one. */
    private static String path(String interfaceName) {
        int at = interfaceName.indexOf('/') + 1;
        return interfaceName.substring(0, at) + named(interfaceName.substring(at));
    }

    /** What a field of one of {@link Component#RECORDS} holds, as WIT writes it. */
    private static String written(Component.Held held) {
        return switch (held) {
            case Component.Held.Text text -> "string";
            case Component.Held.Number number -> "u32";
            case Component.Held.Optional optional -> "option<" + optional.record() + ">";
        };
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
            //
            // Either answer is the ok of a result. Its err is a call the runtime ended, with the
            // reason a core module's abort record holds: 3 for arguments that are not JSON, 6 for
            // a division by zero, and the rest as the runtime's ABI lists them. 13 is a function
            // this component imports answering something other than its behavior's type; then
            // invalid-answer names the behavior and says what is wrong with the answer, as issues
            // naming where in it each is. The instance can be called again after one. A trap is a
            // failure the runtime gave no reason for, or arguments too large for the instance's
            // memory, which fail before the call begins.
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
