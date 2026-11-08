package souther.wasm.link;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * What one typed compile settles that the core module it writes and the component it is wrapped
 * as both read: the offering, the layout of everything in it, the name each lifted function is
 * exported under, and the core function each behavior reached out for is lowered to.
 *
 * <p>Written down as the core module is written and read by what wraps it, and worked out nowhere
 * else. The component asked the program for its offering again, rebuilt each lifted function's
 * name from the module and the behavior, and flattened each lowering again: three accounts a
 * wrapper could disagree with the module it wraps on, which would show only as a component that
 * names an export the module does not have, or calls a lowering with values of another type.
 *
 * @param offering what the program offers and has to be given, typed
 * @param abi where each value of it lies and what it flattens to
 * @param lifted the core export each offered behavior's typed function is, by module and name
 * @param readings the core export each type read on its own is, by module and name
 * @param reaches each behavior reached out for, by the number a call out carries for it
 */
public record TypedBuild(TypedOffering offering, CanonicalAbi abi,
        Map<String, Map<String, String>> lifted, Map<String, Map<String, String>> readings,
        List<Reach> reaches) {

    /**
     * A behavior the program reaches out for, typed.
     *
     * @param module the module the behavior is declared in
     * @param behavior the name Souther gave it
     * @param lowered the core function its call out is lowered to, which the program calls through
     */
    public record Reach(String module, String behavior, CanonicalAbi.CoreSignature lowered) {
    }

    public TypedBuild {
        lifted = copied(lifted);
        readings = copied(readings);
        reaches = List.copyOf(reaches);
    }

    private static Map<String, Map<String, String>> copied(Map<String, Map<String, String>> held) {
        Map<String, Map<String, String>> copy = new LinkedHashMap<>();
        held.forEach((module, names) ->
                copy.put(module, Collections.unmodifiableMap(new LinkedHashMap<>(names))));
        return Collections.unmodifiableMap(copy);
    }

    /** A typed build being written down as the core module is written. */
    public static final class Recording {

        private final TypedOffering offering;
        private final CanonicalAbi abi;
        private final Map<String, Map<String, String>> lifted = new LinkedHashMap<>();
        private final Map<String, Map<String, String>> readings = new LinkedHashMap<>();
        private final TreeMap<Integer, Reach> reaches = new TreeMap<>();

        public Recording(TypedOffering offering) {
            this.offering = offering;
            this.abi = CanonicalAbi.of(offering);
        }

        public TypedOffering offering() {
            return offering;
        }

        public CanonicalAbi abi() {
            return abi;
        }

        /** Says a behavior's typed function is exported under {@code export}. */
        public void lifted(String module, String behavior, String export) {
            lifted.computeIfAbsent(module, held -> new LinkedHashMap<>()).put(behavior, export);
        }

        /** Says a type's typed reading is exported under {@code export}. */
        public void read(String module, String type, String export) {
            readings.computeIfAbsent(module, held -> new LinkedHashMap<>()).put(type, export);
        }

        /** Says the behavior a call out carries {@code ordinal} for is lowered to {@code lowered}. */
        public void reached(int ordinal, String module, String behavior,
                CanonicalAbi.CoreSignature lowered) {
            reaches.put(ordinal, new Reach(module, behavior, lowered));
        }

        /** What was written down, once the core module is. */
        public TypedBuild build() {
            List<Reach> numbered = new ArrayList<>();
            reaches.forEach((ordinal, reach) -> {
                if (ordinal != numbered.size()) {
                    throw new IllegalStateException("behavior " + ordinal + " was reached for and "
                            + numbered.size() + " before it was not");
                }
                numbered.add(reach);
            });
            return new TypedBuild(offering, abi, lifted, readings, numbered);
        }
    }
}
