package souther.wasm.link;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A value type of the typed interfaces, as WIT writes one and a component's type section declares
 * one.
 *
 * <p>Only the types a Souther value crosses as. What it is not able to hold — a recursive type, a
 * key no WIT map takes — is refused before one of these is made, so everything that reads one of
 * these writes it and does not have to ask whether it can.
 */
public sealed interface WitType {

    /** A {@code bool}. */
    WitType BOOL = new Primitive("bool");

    /** An {@code s64}, which an {@code Int} is exactly. */
    WitType S64 = new Primitive("s64");

    /** A {@code u32}. */
    WitType U32 = new Primitive("u32");

    /** A {@code string}. */
    WitType STRING = new Primitive("string");

    /**
     * A type WIT has a word for.
     *
     * @param name the word
     */
    record Primitive(String name) implements WitType {
    }

    /** A {@code list} of what it holds. */
    record ListOf(WitType element) implements WitType {
    }

    /** An {@code option} of what it holds. */
    record OptionOf(WitType present) implements WitType {
    }

    /**
     * A {@code result}: what a function that can refuse answers, its {@code ok} or its
     * {@code err}. Only a function's answer is one; nothing a model declares holds one.
     */
    record ResultOf(WitType ok, WitType err) implements WitType {
    }

    /** A {@code tuple} of what it holds, in order. */
    record TupleOf(List<WitType> members) implements WitType {

        public TupleOf {
            members = List.copyOf(members);
        }
    }

    /**
     * A type declared under a name.
     *
     * @param module the Souther module whose types interface declares it, or null for one the
     *     interface naming it declares itself: a record of what a call that ended answers
     * @param name its name, as WIT writes it
     */
    record Named(@Nullable String module, String name) implements WitType {
    }

    /** A type declared under a name, and what it is. */
    sealed interface Declaration {

        /** Its name, as WIT writes it. */
        String name();
    }

    /** A {@code record}, its fields in order. */
    record Record(String name, List<Field> fields) implements Declaration {

        public Record {
            fields = List.copyOf(fields);
        }
    }

    /** A field of a {@link Record}, or a parameter of a function. */
    record Field(String name, WitType type) {
    }

    /** A {@code variant}, its cases in order. */
    record Variant(String name, List<Case> cases) implements Declaration {

        public Variant {
            cases = List.copyOf(cases);
        }
    }

    /**
     * A case of a {@link Variant}.
     *
     * @param carries what it carries, or null for a case that carries nothing
     */
    record Case(String name, @Nullable WitType carries) {
    }

    /** An {@code enum}, its cases in order. */
    record Enumeration(String name, List<String> cases) implements Declaration {

        public Enumeration {
            cases = List.copyOf(cases);
        }
    }

    /** A {@code type} naming another. */
    record Alias(String name, WitType of) implements Declaration {
    }
}
