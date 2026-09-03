package souther.wasm.abi;

import java.util.Optional;

/**
 * Why a call ended without a value.
 *
 * <p>An abort is not a Souther failure: a failure the type admits is an ordinary answer and comes
 * back encoded, and only what no type has a case for reaches here. The code is written into the
 * failure record so that a host can say which one it was without reading anything the arena owns.
 */
public enum AbortReason {

    /** The arena asked the engine for more memory than it would map. {@code aux0} is the size. */
    OUT_OF_MEMORY(1),

    /** A reset was handed a mark the arena never issued. {@code aux0} is it, {@code aux1} the top. */
    BAD_MARK(2),

    /** What was handed in is not one JSON document. {@code aux0} is where the reading stopped. */
    MALFORMED_JSON(3),

    /**
     * What was written is not what the place was declared to hold.
     *
     * <p>{@code aux0} is what was there and {@code aux1} what was asked for. Not where this is
     * going: input a decoder refuses is an expected outcome and belongs in the answer as the
     * issues it found, at their paths, rather than as a trap. What that needs is a value this
     * runtime has not got yet.
     */
    NOT_WHAT_WAS_DECLARED(4),

    /** A whole number outside what an {@code Int} holds. */
    NUMBER_OUT_OF_RANGE(5);

    private final int code;

    AbortReason(int code) {
        this.code = code;
    }

    /** The number the runtime writes for this reason. */
    public int code() {
        return code;
    }

    /**
     * The reason a code names, where this compiler's runtime wrote it.
     *
     * <p>Empty for a code it does not know, which is a runtime newer than the reader rather than a
     * value to reject: a host that cannot name the reason still knows the call aborted.
     */
    public static Optional<AbortReason> of(int code) {
        for (AbortReason reason : values()) {
            if (reason.code == code) {
                return Optional.of(reason);
            }
        }
        return Optional.empty();
    }
}
