package souther.wasm.lower;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import souther.compiler.program.CheckedData;
import souther.compiler.program.CheckedProgram;
import souther.compiler.types.TypeSymbol;

/**
 * The ordinal a way of rounding is told apart by, on both sides of the boundary.
 *
 * <p>{@link WasmCompiler} never writes a {@code RoundingMode} value across the boundary as itself:
 * it writes the place the value holds among the language's own declared cases
 * ({@code __souther_case_of}, read off {@link Descriptors#roundingModes}), and the runtime reads
 * that place back against its own {@code MODE_*} constants ({@code runtime/src/decimal.rs}).
 * Nothing enforces that the two agree on which place is which — the language declares the order
 * once and this crate declares it again by hand — so this holds the two to it directly, one
 * {@code data RoundingMode} case at a time, the same way {@code TheTwoSidesOfAReasonAgreeOnItsNumberTest}
 * holds a reason's own number to the runtime's {@code REASON_*} declarations.
 */
class TheCompilerAndTheRuntimeAgreeOnARoundingModesOrdinalTest {

    private static final Pattern DECLARED =
            Pattern.compile("pub const MODE_([A-Z_]+): u32 = (\\d+);");

    @Test
    void theOrdinalTheCompilerSendsIsTheOneTheRuntimeReadsTheSameCaseAs() {
        List<String> declaredByTheLanguage = caseNames();
        Map<String, Integer> declaredByTheRuntime = declaredByTheRuntime();

        assertThat(declaredByTheRuntime).describedAs("runtime/src/decimal.rs's own MODE_* constants")
                .hasSize(declaredByTheLanguage.size());
        for (int ordinal = 0; ordinal < declaredByTheLanguage.size(); ordinal++) {
            String name = declaredByTheLanguage.get(ordinal);
            assertThat(declaredByTheRuntime)
                    .describedAs(name + " at ordinal " + ordinal)
                    .containsEntry(name, ordinal);
        }
    }

    /** The language's own declared cases, in the order {@code __souther_case_of} answers them by. */
    private static List<String> caseNames() {
        CheckedProgram program = CheckedProgram.of(List.of("""
                module demo

                behavior same : (d: Decimal) -> Decimal

                let same (d) = d
                """));
        for (CheckedData each : program.languageDeclarations()) {
            if (each instanceof CheckedData.Sum held && held.name().name().equals("RoundingMode")) {
                return held.cases().stream().map(TypeSymbol::name).toList();
            }
        }
        throw new AssertionError("the language declares no set of ways to round");
    }

    /** What the runtime's own source says each mode's ordinal is, read out of it. */
    private static Map<String, Integer> declaredByTheRuntime() {
        try {
            String source = Files.readString(
                    Path.of("runtime", "src", "decimal.rs"), StandardCharsets.UTF_8);
            Map<String, Integer> held = new LinkedHashMap<>();
            Matcher found = DECLARED.matcher(source);
            while (found.find()) {
                held.put(found.group(1), Integer.parseInt(found.group(2)));
            }
            return held;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
