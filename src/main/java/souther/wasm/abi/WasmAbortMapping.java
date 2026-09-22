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
 * {@code abortsAt}, {@code kernel(...).aborts()}, {@code EnsuresEnforcement.aborts()}. Where a
 * {@code Core} site's own abort set varies with the program being compiled ({@code Construct},
 * {@code Unreachable}), {@code WasmCompiler}'s lowering reads that answer at lowering time and
 * never rederives it from {@code Core}'s own shape. A kernel's does not vary that way: which
 * {@link AbortKind}s a call to it can end without a value for is a fact about the kernel's
 * identity alone, fixed by {@code KernelContracts} for every program alike — the same reason
 * {@code souther.compiler.codegen.JvmAbortMapping}'s own reference lowering ({@code BodyGen})
 * never reads {@code abortsAt}/{@code kernel(...).aborts()} for a kernel call either: a kernel's
* runtime implementation is compiled once, independent of any call site, and its abort behaviour
 * is checked against {@code KernelContracts} directly rather than re-derived per call.
 *
 * <p>What was actually missing — and let three call sites disagree with {@code KernelContracts}
 * while every existing test stayed green ({@code String.toDecimal}, {@code
 * Int.truncatingRemainder}, unary {@code -}, issue #23's follow-up) — was not that absence of a
 * per-call read, but an executable barrier tying a kernel's hand-written Rust behaviour back to
 * what {@code AbortSites}/{@code KernelContracts} declares for it. {@code
 * AKernelMeansWhatSouthersOwnRuntimeSaysTest#everyIntOperationAgreesWithSouthersRuntimeAtTheEdgesOfWhatAnIntHolds}
 * is that barrier for {@code Int} arithmetic — every combination of {@code MIN_VALUE}, {@code
 * MAX_VALUE} and their neighbours, checked against {@code souther-runtime}'s own overflow-checked
 * behaviour rather than one pair chosen by hand per bug — and {@code
 * AnAmountAndAnOptionCrossToWhatSouthersOwnRuntimeSaysTest}'s differential corpus plays the same
 * role for {@code Decimal} parsing. Both were verified to actually catch what they are meant to:
 * each of the three bugs above reproduces as a failure with its fix reverted. Every other kernel
 * family (temporal arithmetic, {@code List}, {@code String}) still relies on the hand-audited call
 * graph and its own smaller differential spot-checks rather than an equivalent boundary sweep —
 * an honest gap, not a closed one, and the next family to extend this to if one turns up broken.
 *
 * <p>What this class owns is only the smaller fact every carrier's mapping has to answer for once
 * the kind is known: the number a host reads it as on this one ABI.
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
