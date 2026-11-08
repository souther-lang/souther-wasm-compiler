package souther.wasm.link;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import souther.wasm.Compiled;
import souther.wasm.Running;
import souther.wasm.lower.WasmCompiler;

/**
 * A union nobody named, answered by two behaviors: each behavior's signature names it after that
 * behavior, whichever is declared first, and each is called through its own function.
 *
 * <p>The union is one type to Souther, which compares unions by their members, and is laid out as
 * one. What a behavior's signature calls it is the behavior's: named after whichever behavior was
 * met first, a behavior's interface would change when another behavior came to answer the same
 * union, its own signature unchanged.
 */
class ABehaviorsAnswerIsNamedAfterItWhoeverElseAnswersItTooTest {

    private static String program(String first, String second) {
        return """
                module people exposing ( Adult, Minor, %s, %s )

                data Adult = { age: Int }
                data Minor = { age: Int }

                behavior %s : (age: Int) -> Adult | Minor

                let %s (age) = if age >= 18 then Adult { age = age } else Minor { age = age }

                behavior %s : (age: Int) -> Adult | Minor

                let %s (age) = if age >= 20 then Adult { age = age } else Minor { age = age }
                """.formatted(first, second, first, first, second, second);
    }

    @Test
    void namesEachAnswerAfterItsBehaviorWhicheverIsDeclaredFirst() {
        for (String source : List.of(program("classify", "reclassify"),
                program("reclassify", "classify"))) {
            String wit = TypedWitText.of(WasmCompiler.typedOffering(
                    Compiled.program(List.of(source))));

            assertThat(wit)
                    .contains("%classify: func(%age: s64) -> result<%classify-result, %refusal>;")
                    .contains("%reclassify: func(%age: s64) -> result<%reclassify-result, %refusal>;")
                    .contains("variant %classify-result { %adult(%adult), %minor(%minor) }")
                    .contains("variant %reclassify-result { %adult(%adult), %minor(%minor) }");
        }
    }

    @Test
    void answersEachThroughItsOwnFunctionLaidOutAsTheOneUnionItIs() {
        Running module = Running.linked(Compiled.typedCore(Compiled.program(List.of(
                program("classify", "reclassify")))));

        // result<variant { adult, minor }, refusal>: the variant at eight, its case at eight.
        int area = (int) module.callWith(Component.Lifted.typed("people.classify"), 19L)[0];
        assertThat(module.read(area + 8, 1)[0]).describedAs("adult").isZero();
        area = (int) module.callWith(Component.Lifted.typed("people.reclassify"), 19L)[0];
        assertThat(module.read(area + 8, 1)[0]).describedAs("minor").isEqualTo((byte) 1);
    }
}
