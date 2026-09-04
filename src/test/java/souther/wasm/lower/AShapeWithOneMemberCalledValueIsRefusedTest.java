package souther.wasm.lower;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import souther.compiler.program.CheckedData;
import souther.compiler.program.CheckedProgram;

/**
 * The one shape this backend cannot settle the external form of.
 *
 * <p>A type declared over another is written as what it is declared over. A type declared with one
 * member called {@code value} is written as an object with that member. Both reach this backend as
 * one field called {@code value}, so writing either is answering for the other.
 */
class AShapeWithOneMemberCalledValueIsRefusedTest {

    @Test
    void refusesBothOfThemBecauseTheyArriveAsOneThing() {
        for (String written : new String[] {
            "data ProductId = String", "data Wrapper = { value: String }",
        }) {
            CheckedProgram program = CheckedProgram.of(List.of("""
                    module probe

                    %s

                    behavior kept : (x: %s) -> %s

                    let kept (x) = x
                    """.formatted(written, named(written), named(written))));

            assertThatThrownBy(() -> WasmCompiler.compile(program))
                    .describedAs(written)
                    .isInstanceOf(NotLowered.class)
                    .hasMessageContaining("nothing here can tell them apart");
        }
    }

    @Test
    void isWhatTheProgramApiSaysRatherThanSomethingThisBackendChose() {
        CheckedProgram program = CheckedProgram.of(List.of("""
                module probe

                data ProductId = String
                data Wrapper = { value: String }

                behavior kept : (x: Wrapper) -> Wrapper

                let kept (x) = x
                """));

        // The population this refusal covers is exactly what the two declarations come to here,
        // and they come to the same thing: the same fields, in the same order, with the same
        // names and types. A later release that told them apart would shrink this refusal.
        List<CheckedData.Product> both = program.modules().get(0).data().stream()
                .filter(CheckedData.Product.class::isInstance)
                .map(CheckedData.Product.class::cast)
                .toList();
        assertThat(both).hasSize(2);
        assertThat(both.get(0).fields().stream().map(field -> field.name() + ":" + field.type()))
                .isEqualTo(both.get(1).fields().stream()
                        .map(field -> field.name() + ":" + field.type()).toList());
    }

    @Test
    void takesEveryOtherProductAsTheObjectItIs() {
        CheckedProgram program = CheckedProgram.of(List.of("""
                module probe

                data Pair = { value: String, also: Int }
                data Named = { label: String }

                behavior kept : (x: Pair) -> Named constructs Named

                let kept (x) = Named { label = x.value }
                """));

        assertThat(WasmCompiler.compile(program)).isNotEmpty();
    }

    private static String named(String written) {
        return written.contains("ProductId") ? "ProductId" : "Wrapper";
    }
}
