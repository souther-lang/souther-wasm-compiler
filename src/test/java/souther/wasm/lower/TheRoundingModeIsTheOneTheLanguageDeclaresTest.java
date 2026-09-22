package souther.wasm.lower;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import souther.compiler.core.Kernel;
import souther.compiler.program.CheckedProgram;

/**
 * Which parameter is a way of rounding is answered by where the set is declared, and not by what
 * it is called: a program may declare a {@code RoundingMode} of its own.
 */
class TheRoundingModeIsTheOneTheLanguageDeclaresTest {

    @Test
    void takesTheSetTheLanguageDeclaresForTheWayOfRounding() {
        CheckedProgram program = CheckedProgram.of(List.of("""
                module demo

                behavior same : (d: Decimal) -> Decimal

                let same (d) = d
                """));

        var parameters = program.kernel(Kernel.DECIMAL_ROUND).signature().parameters();

        assertThat(Descriptors.isRoundingMode(parameters.get(1))).isTrue();
        assertThat(Descriptors.isRoundingMode(parameters.get(0))).isFalse();
    }

    @Test
    void doesNotTakeASetOfAProgramsOwnThatIsSpeltTheSame() {
        CheckedProgram program = CheckedProgram.of(List.of("""
                module elsewhere

                data RoundingMode = { n: Int }

                behavior kept : (m: RoundingMode) -> RoundingMode

                let kept (m) = m
                """));

        var declared = program.module("elsewhere").behaviors().get(0).signature().takes().get(0);

        assertThat(Descriptors.isRoundingMode(declared)).isFalse();
    }
}
