package souther.wasm.abi;

import java.util.Optional;

/**
 * A way a call into a linked module can end without a value that is not a Souther program's own:
 * this carrier failing to carry an answer through, or this backend's own machinery reaching a
 * state the checker settled it never would.
 *
 * <p>{@code souther.compiler.abort.AbortKind}'s sibling and not its subject. A Souther program
 * ending without a value for a reason the specification states as its own is an {@code AbortKind},
 * represented on this ABI by {@link WasmAbortMapping}; a member here is never one, and the day a
 * carrier failure is folded in among them is the day the language starts answering for a wasm
 * arena running out of memory. See {@link FailureCause} for the sum the two make together.
 *
 * <p>{@link #BACKEND_INVARIANT_BROKEN} is not the same fact as {@code AbortKind}'s own
 * {@code UNREACHABLE_REACHED}: the language's is a model's own {@code unreachable} declaration not
 * holding, and this one is this backend emitting a test the checker had already settled cannot
 * fail — a match run out of arms, or an ordinal this crate reads naming no case the compiler and
 * the runtime agree on. Neither is one Souther program can write; both are backend bugs, and this
 * is the one WASM's runtime happens to be able to observe at all.
 */
public enum WasmFault {

    /** The arena asked the engine for more memory than it would map. {@code aux0} is the size. */
    OUT_OF_MEMORY(1),

    /** A reset was handed a mark the arena never issued. {@code aux0} is it, {@code aux1} the top. */
    BAD_MARK(2),

    /** What was handed in is not one JSON document. {@code aux0} is where the reading stopped. */
    MALFORMED_JSON(3),

    /**
     * A value whose tag nothing knows.
     *
     * <p>Not something bad input reaches: a value is made by generated code, so a tag no one knows
     * means this compiler emitted it wrongly rather than that a caller wrote something odd.
     */
    NOT_A_VALUE(4),

    /**
     * This backend, or the boundary between it and the runtime it is calling, reached a state the
     * checker settled it never would: a match or an attempted construction fell through every arm
     * this backend lowered for it, or an ordinal the runtime was handed names no case the two sides
     * agree on (a {@code RoundingMode} ordinal outside what the runtime declares, say). A compiler
     * bug or an ABI mismatch, never a Souther program's own abort.
     */
    BACKEND_INVARIANT_BROKEN(7);

    private final int code;

    WasmFault(int code) {
        this.code = code;
    }

    /** The number the runtime writes for this fault. */
    public int code() {
        return code;
    }

    /** The fault {@code code} names, or empty for a code that is not one of this ABI's own faults —
     *  which includes every code an {@code AbortKind} is represented by. */
    public static Optional<WasmFault> of(int code) {
        for (WasmFault fault : values()) {
            if (fault.code == code) {
                return Optional.of(fault);
            }
        }
        return Optional.empty();
    }
}
