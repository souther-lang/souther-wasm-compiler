package souther.wasm.link;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * What a program offers a caller and what it has to be given, which a component is built from and
 * its interfaces are written out from.
 *
 * <p>One value for both, because a component and the text of its interfaces are two accounts of
 * one offering, and two lists handed to each separately are two chances for the accounts to differ.
 *
 * @param behaviors every behavior's core export name, by the module that declares it, in the order
 *     the program declares them
 * @param readable every type a caller may read a value of on its own, by the module that declares
 *     it, in the order the program declares them: its name, and what
 *     {@link Component.Lifted#reading} names its function by
 * @param reaches the behaviors the program reaches out for, in the order it numbers them
 */
public record Offering(Map<String, Map<String, String>> behaviors,
        Map<String, Map<String, String>> readable, List<Component.Reach> reaches) {

    /**
     * Copies what it is given in the order it was given, which is the order things are offered, and
     * refuses two modules whose interfaces in one package would have one name: a component cannot
     * export or import two interfaces under it, and its text could not tell them apart.
     */
    public Offering {
        behaviors = inOrder(behaviors);
        readable = inOrder(readable);
        reaches = List.copyOf(reaches);
        apart(behaviors.keySet(), Component::offeredAs);
        apart(readable.keySet(), Component::readAs);
        Collection<String> asking = new LinkedHashSet<>();
        reaches.forEach(reach -> asking.add(reach.module()));
        apart(asking, Component::askedUnder);
    }

    private static void apart(Collection<String> modules, UnaryOperator<String> named) {
        Map<String, String> already = new LinkedHashMap<>();
        for (String module : modules) {
            String interfaceName = named.apply(module);
            String taken = already.put(interfaceName, module);
            if (taken != null) {
                throw new IllegalArgumentException("the modules " + taken + " and " + module
                        + " would both be the interface " + interfaceName);
            }
        }
    }

    /** What a program offers when it reads no type on its own and reaches out for nothing. */
    public static Offering ofBehaviors(Map<String, Map<String, String>> behaviors) {
        return new Offering(behaviors, Map.of(), List.of());
    }

    private static Map<String, Map<String, String>> inOrder(
            Map<String, Map<String, String>> byModule) {
        Map<String, Map<String, String>> held = new LinkedHashMap<>();
        byModule.forEach((module, named) ->
                held.put(module, Collections.unmodifiableMap(new LinkedHashMap<>(named))));
        return Collections.unmodifiableMap(held);
    }
}
