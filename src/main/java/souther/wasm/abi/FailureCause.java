package souther.wasm.abi;

import java.util.Optional;
import souther.compiler.abort.AbortKind;

/**
 * What a reason code on this ABI names: a Souther program ending without a value, or this carrier
 * failing to carry one through.
 *
 * <p>The two are read from one number space because one wire field holds them both, not because
 * they are one fact. {@link Language} rides {@link AbortKind}, the vocabulary the language itself
 * gives for ending a run without a value; {@link Wasm} rides {@link WasmFault}, this backend's own
 * failures. There is no third member that repeats either as a wasm-specific copy: a reader that
 * wants which {@link AbortKind} a code represents already has one in {@link Language}, and a
 * second enum naming the same set again would be exactly the drift issue #23 exists to end.
 */
public sealed interface FailureCause {

    /** A Souther program ended without a value for a reason the language itself gives. */
    record Language(AbortKind kind) implements FailureCause {
    }

    /** This carrier, or this backend's own machinery, failed to carry an answer through. */
    record Wasm(WasmFault fault) implements FailureCause {
    }

    /**
     * What {@code code} names on this ABI, or empty for a code neither side of the mapping answers
     * to — which is a runtime newer than this reader rather than a value to reject.
     *
     * <p>Read off {@link WasmAbortMapping} and {@link WasmFault} rather than a third hand-kept
     * table: a code names an {@link AbortKind} exactly where some kind's own representation is it,
     * asked of every kind because {@link AbortKind} is closed and small enough that a linear
     * search over it costs nothing a decode of an abort could notice.
     */
    static Optional<FailureCause> of(int code) {
        for (AbortKind kind : AbortKind.values()) {
            if (WasmAbortMapping.representationOf(kind) == code) {
                return Optional.of(new Language(kind));
            }
        }
        return WasmFault.of(code).map(Wasm::new);
    }
}
