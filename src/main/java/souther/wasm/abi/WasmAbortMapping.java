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
 * <p>What was actually missing — and let six call sites disagree with {@code KernelContracts}
 * while every existing test stayed green ({@code String.toDecimal}, {@code
 * Int.truncatingRemainder}, unary {@code -}, {@code Date.addYears}, {@code DateTime.addMinutes}/
 * {@code addHours}, {@code String.padLeft}/{@code padRight}, and {@code Date.addMonths}/
 * {@code addYears} again at {@code day_count}'s own arithmetic; issue #23's follow-up) — was not
 * that absence of a per-call read, but an executable barrier tying a kernel's hand-written Rust
 * behaviour back to what {@code AbortSites}/{@code KernelContracts} declares for it. Four of the
 * six share a second, narrower root under the first: the release profile has Rust's overflow
 * checks off, so a raw {@code *}/{@code +}/{@code -} on a boundary {@code Int} does not trap — it
 * wraps, silently, and a bounds check downstream can be lied to by the wrapped value (a huge shift
 * landing back inside what a calendar holds; a huge negative width reading as a huge positive
 * one). {@code checked_mul}/{@code checked_add}, or reordering a subtraction behind the sign check
 * it would otherwise undermine, closes each site as it is found — except where the arithmetic that
 * needs to stay honest is itself what decides whether a value is in range ({@code day_count}),
 * where the fix is a wider intermediate type ({@code i128}) rather than a checked operation with
 * nothing sensible left to do on overflow. Nothing here forbids raw arithmetic in general, so this
 * is a per-site discipline, not a lint.
 *
 * <p>{@code AKernelMeansWhatSouthersOwnRuntimeSaysTest#everyIntOperationAgreesWithSouthersRuntimeAtTheEdgesOfWhatAnIntHolds}
 * is the executable barrier for {@code Int} arithmetic — every combination of {@code MIN_VALUE},
 * {@code MAX_VALUE} and their neighbours, checked against {@code souther-runtime}'s own
 * overflow-checked behaviour rather than one pair chosen by hand per bug — {@code
 * ADayAndATimeAreWhatACalendarSaysTest#endsTheCallWhereTheShiftItselfLeavesWhatAnIntHolds} plays
 * the same role for every temporal shift at {@code MIN_VALUE}/{@code MAX_VALUE} (ported from
 * {@code souther-compiler}'s own {@code CalendarArithmeticOffTheEndOfTheRangeAbortsTest}, which
 * this backend was never run against), {@code
 * AKernelMeansWhatSouthersOwnRuntimeSaysTest#padsAtTheWidthsMostLikelyToWrap} for padding at its
 * own boundary widths, and {@code AnAmountAndAnOptionCrossToWhatSouthersOwnRuntimeSaysTest}'s
 * differential corpus for {@code Decimal} parsing. All four were verified to actually catch what
 * they are meant to: each of the first five bugs above reproduces as a failure with its fix
 * reverted. The sixth is the limit of what any of them can catch by construction: an endpoint
 * sweep only ever runs {@code MIN_VALUE}/{@code MAX_VALUE} themselves, and this bug needed an
 * intermediate value large enough to overflow {@code day_count}'s own arithmetic while landing —
 * by coincidence, not by refusal — back inside a day this holds, which no endpoint reaches by
 * definition. {@code
 * ADayAndATimeAreWhatACalendarSaysTest#endsTheCallWhereTheIntermediateYearItselfOverflowsRatherThanJustTheEndpoints}
 * is that case, fixed to the literal values it was found at rather than swept, because nothing
 * general enough to search the space between two endpoints for a coincidental re-entry exists
 * here yet.
 *
 * <p>Every one of those sweeps compares the {@link AbortKind} a trap names — read off {@link
 * FailureRecord#cause}, not merely whether something trapped — against the one answer {@code
 * CheckedProgram.kernel(...).aborts()} gives for the kernel under test, never a second hand-kept
 * table of which kind each operation answers to (that table would itself be exactly the
 * "same fact filed twice" issue #23 exists to end). This matters: a bare trap-or-not check would
 * have stayed green through a Rust call site misclassified onto the wrong {@link AbortKind} —
 * verified by deliberately misclassifying one ({@code Int.add}'s overflow read as {@code
 * INVALID_BOUNDS}) and confirming {@code everyIntOperationAgreesWithSouthersRuntimeAtTheEdgesOfWhatAnIntHolds}
 * fails where a trap-only assertion would not have. Every other kernel family ({@code List}, most
 * of {@code String}) still relies on the hand-audited call graph and its own smaller differential
 * spot-checks rather than an equivalent boundary sweep — an honest gap, not a closed one, and the
 * next family to extend this to if one turns up broken.
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
