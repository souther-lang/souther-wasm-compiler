package souther.wasm.link;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * What a program offers as typed interfaces, written out as a reader of interfaces reads it.
 *
 * <p>Written from the {@link TypedOffering} a component offering them is built from, and never from
 * the component, for the reason {@link WitText} gives. Every name is escaped as {@link WitText}
 * escapes one, since a model may name a type or a field what WIT has a word for.
 */
public final class TypedWitText {

    private TypedWitText() {
    }

    /** The interfaces a program offers and has to be given, as a reader reads them. */
    public static String of(TypedOffering offering) {
        StringBuilder out = new StringBuilder("package root:component;\n\nworld program {\n");
        for (String module : offering.types().keySet()) {
            out.append("  import ").append(path(TypedOffering.TYPES, module)).append(";\n");
        }
        for (String module : offering.reached().keySet()) {
            out.append("  import ").append(path(TypedOffering.REACHED, module)).append(";\n");
        }
        for (String module : offering.behaviors().keySet()) {
            out.append("  export ").append(path(TypedOffering.PROGRAM, module)).append(";\n");
        }
        for (String module : offering.readable().keySet()) {
            out.append("  export ").append(path(TypedOffering.DECODE, module)).append(";\n");
        }
        out.append("}\n");

        if (!offering.types().isEmpty()) {
            out.append("\npackage ").append(TypedOffering.TYPES).append('@')
                    .append(TypedOffering.VERSION).append(" {\n");
            for (Map.Entry<String, List<WitType.Declaration>> module
                    : offering.types().entrySet()) {
                out.append("  interface ").append(named(Component.writable(module.getKey())))
                        .append(" {\n");
                List<WitType> naming = new ArrayList<>();
                module.getValue().forEach(each -> naming.addAll(TypedOffering.holds(each)));
                uses(out, TypedOffering.used(module.getKey(), true, naming));
                module.getValue().forEach(each -> declaration(out, each));
                out.append("  }\n");
            }
            out.append("}\n");
        }
        functions(out, TypedOffering.REACHED, offering.reached(), false);
        functions(out, TypedOffering.PROGRAM, offering.behaviors(), true);
        functions(out, TypedOffering.DECODE, offering.readable(), true);
        return out.toString();
    }

    /** What every function crosses as, and what a caller does with it. */
    public static final String PREAMBLE = """
            // What a Souther program offers, typed. A behavior takes its arguments as the types
            // its signature names and answers the type it answers, as the ok of a result. Its err
            // is a refusal: issues, where what it was handed is of the right shape and breaks a
            // rule Souther holds the value to, each naming where in the arguments it is; or ended,
            // a call the runtime ended, with the reason a core module's abort record holds. A
            // function of souther:typed-decode takes one value of its type and answers it back,
            // or the same refusal. What a program reaches out for answers its behavior's type, and
            // an answer that breaks one of the type's rules ends the call with reason 13, whose
            // invalid-answer names the behavior and says what is wrong as issues.
            //
            // An amount and a date cross as the text the JSON boundary writes them as, and a map
            // as a list of its entries. An issue's meta is JSON text: what it holds depends on its
            // code.
            """;

    /** The whole file: what a caller has to know, then the interfaces. */
    public static String written(TypedOffering offering) {
        return PREAMBLE + "\n" + of(offering);
    }

    private static void functions(StringBuilder out, String held,
            Map<String, Map<String, TypedOffering.Function>> modules, boolean refuses) {
        if (modules.isEmpty()) {
            return;
        }
        out.append("\npackage ").append(held).append('@').append(TypedOffering.VERSION)
                .append(" {\n");
        for (Map.Entry<String, Map<String, TypedOffering.Function>> module : modules.entrySet()) {
            out.append("  interface ").append(named(Component.writable(module.getKey())))
                    .append(" {\n");
            List<WitType> naming = new ArrayList<>();
            module.getValue().values().forEach(each -> naming.addAll(TypedOffering.holds(each)));
            uses(out, TypedOffering.used(module.getKey(), false, naming));
            if (refuses) {
                TypedOffering.PROTOCOL.forEach(each -> declaration(out, each));
            }
            for (TypedOffering.Function function : module.getValue().values()) {
                String answers = refuses
                        ? "result<" + type(function.answers()) + ", "
                                + named(TypedOffering.REFUSAL) + ">"
                        : type(function.answers());
                out.append("    ").append(named(function.name())).append(": func(")
                        .append(function.parameters().stream()
                                .map(parameter -> named(parameter.name()) + ": "
                                        + type(parameter.type()))
                                .collect(Collectors.joining(", ")))
                        .append(") -> ").append(answers).append(";\n");
            }
            out.append("  }\n");
        }
        out.append("}\n");
    }

    private static void uses(StringBuilder out, Map<String, Set<String>> used) {
        for (Map.Entry<String, Set<String>> from : used.entrySet()) {
            out.append("    use ").append(path(TypedOffering.TYPES, from.getKey())).append(".{")
                    .append(from.getValue().stream().map(TypedWitText::named)
                            .collect(Collectors.joining(", ")))
                    .append("};\n");
        }
    }

    private static void declaration(StringBuilder out, WitType.Declaration declaration) {
        String name = named(declaration.name());
        switch (declaration) {
            case WitType.Record record -> out.append("    record ").append(name).append(" { ")
                    .append(record.fields().stream()
                            .map(field -> named(field.name()) + ": " + type(field.type()))
                            .collect(Collectors.joining(", ")))
                    .append(" }\n");
            case WitType.Variant variant -> out.append("    variant ").append(name).append(" { ")
                    .append(variant.cases().stream()
                            .map(each -> each.carries() == null
                                    ? named(each.name())
                                    : named(each.name()) + "(" + type(each.carries()) + ")")
                            .collect(Collectors.joining(", ")))
                    .append(" }\n");
            case WitType.Enumeration enumeration -> out.append("    enum ").append(name)
                    .append(" { ")
                    .append(enumeration.cases().stream().map(TypedWitText::named)
                            .collect(Collectors.joining(", ")))
                    .append(" }\n");
            case WitType.Alias alias -> out.append("    type ").append(name).append(" = ")
                    .append(type(alias.of())).append(";\n");
        }
    }

    private static String type(WitType type) {
        return switch (type) {
            case WitType.Primitive primitive -> primitive.name();
            case WitType.ListOf list -> "list<" + type(list.element()) + ">";
            case WitType.OptionOf option -> "option<" + type(option.present()) + ">";
            case WitType.TupleOf tuple -> "tuple<" + tuple.members().stream()
                    .map(TypedWitText::type).collect(Collectors.joining(", ")) + ">";
            case WitType.Named name -> named(name.name());
        };
    }

    /** A versioned interface's path, its name escaped. */
    private static String path(String held, String module) {
        return held + "/" + named(Component.writable(module)) + "@" + TypedOffering.VERSION;
    }

    private static String named(String name) {
        return "%" + name;
    }
}
