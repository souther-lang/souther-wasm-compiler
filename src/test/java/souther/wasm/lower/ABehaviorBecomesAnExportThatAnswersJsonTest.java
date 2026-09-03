package souther.wasm.lower;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import souther.compiler.program.CheckedProgram;
import souther.wasm.Running;

/**
 * A Souther source compiled to wasm and called.
 *
 * <p>The program is checked by the compiler this project depends on, so what is read here is a
 * {@code CheckedProgram} and not a shape written by hand to suit the reader.
 */
class ABehaviorBecomesAnExportThatAnswersJsonTest {

    @Test
    void answersTheStringABehaviorWasWrittenToAnswer() {
        Running module = compiled("""
                module greeting

                behavior hello : () -> String

                let hello = "ごきげんよう"
                """);

        assertThat(answerOf(module, "greeting.hello")).isEqualTo("\"ごきげんよう\"");
    }

    @Test
    void answersANumberAsANumberAndNotAsText() {
        Running module = compiled("""
                module counting

                behavior answer : () -> Int

                let answer = 42
                """);

        assertThat(answerOf(module, "counting.answer")).isEqualTo("42");
    }

    @Test
    void answersABooleanAsJsonWritesOne() {
        Running module = compiled("""
                module deciding

                behavior yes : () -> Bool

                let yes = true
                """);

        assertThat(answerOf(module, "deciding.yes")).isEqualTo("true");
    }

    @Test
    void escapesWhatJsonCannotCarryAsItStands() {
        Running module = compiled("""
                module quoting

                behavior quoted : () -> String

                let quoted = "she said \\"no\\""
                """);

        assertThat(answerOf(module, "quoting.quoted")).isEqualTo("\"she said \\\"no\\\"\"");
    }

    @Test
    void reachesEveryBehaviorOfEveryModuleTheProgramCarries() {
        Running module = compiled("""
                module first

                behavior one : () -> Int

                let one = 1

                behavior two : () -> Int

                let two = 2
                """, """
                module second

                behavior three : () -> Int

                let three = 3
                """);

        assertThat(answerOf(module, "first.one")).isEqualTo("1");
        assertThat(answerOf(module, "first.two")).isEqualTo("2");
        assertThat(answerOf(module, "second.three")).isEqualTo("3");
    }

    @Test
    void saysWhatItMetRatherThanEmittingSomethingThatWouldAnswerWrongly() {
        CheckedProgram program = CheckedProgram.of(List.of("""
                module adding

                behavior twice : (n: Int) -> Int

                let twice (n) = n + n
                """));

        assertThatThrownBy(() -> WasmCompiler.compile(program))
                .isInstanceOf(NotLowered.class)
                .hasMessageContaining("takes an input");
    }

    private static Running compiled(String... sources) {
        return Running.linked(WasmCompiler.compile(CheckedProgram.of(List.of(sources))));
    }

    private static String answerOf(Running module, String export) {
        long[] answer = module.callWithString(export, 0, 0);
        return new String(module.read((int) answer[0], (int) answer[1]), StandardCharsets.UTF_8);
    }
}
