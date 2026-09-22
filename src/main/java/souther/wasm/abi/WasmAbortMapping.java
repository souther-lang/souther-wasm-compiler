package souther.wasm.abi;

import souther.compiler.abort.AbortKind;

/**
 * Which reason code this ABI represents each {@link AbortKind} with.
 *
 * <p>This backend's own answer to the question {@link AbortKind} states backend-neutrally, the
 * same shape {@code souther.compiler.codegen.JvmAbortMapping} answers it for the JVM: an
 * exhaustive, default-free switch, so a new {@link AbortKind} stops this build rather than reading
 * as an abort this backend has nothing to say about.
 *
 * <p>What is emitted here is a representation, never a reclassification. Whether a site can abort,
 * and which {@link AbortKind} it can abort for, is answered by {@code CheckedProgram} —
 * {@code abortsAt}, {@code kernel(...).aborts()}, {@code EnsuresEnforcement.aborts()} — and this
 * backend reads that answer rather than rederiving it from {@code Core}'s own shape. What this
 * class owns is only the smaller fact every carrier's mapping has to answer for once the kind is
 * known: the number a host reads it as on this one ABI.
 *
 * <p>Not a table this backend derives by re-reading {@code souther-runtime}'s Rust source: it is
 * read here, once, and {@code runtime/src/lib.rs}'s own {@code REASON_*} constants are held to
 * this by a conformance test rather than by anyone remembering to keep the two in step.
 */
public final class WasmAbortMapping {

    private WasmAbortMapping() {
    }

    /** The reason code {@code kind} is represented by on this ABI. */
    public static int representationOf(AbortKind kind) {
        return switch (kind) {
            case REQUIRED_FORM_HAS_NO_PLACE -> 5;
            case DIVISION_BY_ZERO -> 6;
            case INVARIANT_NOT_HELD -> 8;
            case UNREACHABLE_REACHED -> 9;
            case INVALID_BOUNDS -> 10;
            case ENSURES_NOT_HELD -> 11;
        };
    }
}
