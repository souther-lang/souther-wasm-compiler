package souther.wasm.abi;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import souther.compiler.abort.AbortKind;

/**
 * The numbers a reason goes by, on both sides of the boundary.
 *
 * <p>A reason is written down twice: once where it is raised and once where it is read. Nothing
 * makes the two agree except that they were written together, and a number that drifted would name
 * one failure as another — quietly, because both sides would still have a name for it.
 *
 * <p>Two families share this one number space ({@link FailureCause}): a Souther program's own
 * abort, represented here by {@link WasmAbortMapping}, and this backend's own fault, named by
 * {@link WasmFault}. Both sides are held to the runtime's {@code REASON_*} constants by name and
 * by number, so a rename on one side that forgets the other fails here rather than at a host
 * reading a trap.
 */
class TheTwoSidesOfAReasonAgreeOnItsNumberTest {

    @Test
    void everyReasonTheRuntimeDeclaresIsOneThisSideNamesTheSameWay() {
        Map<String, Integer> runtime = declaredByTheRuntime();

        assertThat(runtime).isNotEmpty();
        for (Map.Entry<String, Integer> each : runtime.entrySet()) {
            assertThat(nameOf(each.getValue()))
                    .describedAs(each.getKey() + " is " + each.getValue())
                    .contains(each.getKey());
        }
    }

    @Test
    void everyAbortKindThisSideRepresentsIsOneTheRuntimeWroteDownTheSameWay() {
        Map<String, Integer> runtime = declaredByTheRuntime();

        for (AbortKind kind : AbortKind.values()) {
            assertThat(runtime)
                    .describedAs(kind.name())
                    .containsEntry(kind.name(), WasmAbortMapping.representationOf(kind));
        }
    }

    @Test
    void everyWasmFaultThisSideNamesIsOneTheRuntimeWroteDownTheSameWay() {
        Map<String, Integer> runtime = declaredByTheRuntime();

        for (WasmFault fault : WasmFault.values()) {
            assertThat(runtime)
                    .describedAs(fault.name())
                    .containsEntry(fault.name(), fault.code());
        }
    }

    /** What {@code code} ought to be named, on this side, whichever family it belongs to. */
    private static Optional<String> nameOf(int code) {
        for (AbortKind kind : AbortKind.values()) {
            if (WasmAbortMapping.representationOf(kind) == code) {
                return Optional.of(kind.name());
            }
        }
        return WasmFault.of(code).map(Enum::name);
    }

    /** What the runtime's own source says each reason is, read out of it. */
    private static Map<String, Integer> declaredByTheRuntime() {
        return RuntimeReasons.declared();
    }
}
