package souther.wasm.lower;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dylibso.chicory.wasm.ChicoryException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import souther.compiler.program.CheckedProgram;
import souther.wasm.Running;
import souther.wasm.abi.AbortReason;
import souther.wasm.abi.RuntimeAbi;

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

        assertThat(answerOf(module, "greeting.hello")).isEqualTo("{\"value\":\"ごきげんよう\"}");
    }

    @Test
    void answersANumberAsANumberAndNotAsText() {
        Running module = compiled("""
                module counting

                behavior answer : () -> Int

                let answer = 42
                """);

        assertThat(answerOf(module, "counting.answer")).isEqualTo("{\"value\":42}");
    }

    @Test
    void answersABooleanAsJsonWritesOne() {
        Running module = compiled("""
                module deciding

                behavior yes : () -> Bool

                let yes = true
                """);

        assertThat(answerOf(module, "deciding.yes")).isEqualTo("{\"value\":true}");
    }

    @Test
    void escapesWhatJsonCannotCarryAsItStands() {
        Running module = compiled("""
                module quoting

                behavior quoted : () -> String

                let quoted = "she said \\"no\\""
                """);

        assertThat(answerOf(module, "quoting.quoted")).isEqualTo("{\"value\":\"she said \\\"no\\\"\"}");
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

        assertThat(answerOf(module, "first.one")).isEqualTo("{\"value\":1}");
        assertThat(answerOf(module, "first.two")).isEqualTo("{\"value\":2}");
        assertThat(answerOf(module, "second.three")).isEqualTo("{\"value\":3}");
    }

    @Test
    void answersTheArgumentABehaviorWasHanded() {
        Running module = compiled("""
                module echoing

                behavior echo : (n: Int) -> Int

                let echo (n) = n
                """);

        assertThat(answerOf(module, "echoing.echo", "[7]")).isEqualTo("{\"value\":7}");
        assertThat(answerOf(module, "echoing.echo", "[-9007199254740993]"))
                .isEqualTo("{\"value\":-9007199254740993}");
    }

    @Test
    void takesItsArgumentsInTheOrderTheBehaviorDeclaresThem() {
        Running module = compiled("""
                module picking

                behavior second : (a: String, b: String) -> String

                let second (a, b) = b
                """);

        assertThat(answerOf(module, "picking.second", "[\"one\", \"two\"]")).isEqualTo("{\"value\":\"two\"}");
    }

    @Test
    void readsEachArgumentAsTheTypeItWasDeclared() {
        Running module = compiled("""
                module mixing

                behavior flag : (n: Int, on: Bool, tag: String) -> Bool

                let flag (n, on, tag) = on
                """);

        assertThat(answerOf(module, "mixing.flag", "[1, true, \"x\"]")).isEqualTo("{\"value\":true}");
        assertThat(answerOf(module, "mixing.flag", "[1, false, \"x\"]")).isEqualTo("{\"value\":false}");
    }

    @Test
    void answersWithWhatItFoundWrongRatherThanEndingTheCall() {
        Running module = compiled("""
                module strict

                behavior echo : (n: Int) -> Int

                let echo (n) = n
                """);

        assertThat(answerOf(module, "strict.echo", "[\"7\"]")).isEqualTo(
                "{\"issues\":[{\"path\":\"/0\",\"code\":\"type_mismatch\","
                        + "\"meta\":{\"actual\":\"string\",\"expected\":\"Int\"}}]}");
        assertThat(answerOf(module, "strict.echo", "[1.5]")).contains("\"code\":\"type_mismatch\"");
        assertThat(answerOf(module, "strict.echo", "[99999999999999999999]"))
                .contains("\"code\":\"out_of_range\"");
    }

    @Test
    void saysSoAtTheRootWhenTheCallItselfIsNotWhatWasDeclared() {
        Running module = compiled("""
                module strict

                behavior echo : (n: Int) -> Int

                let echo (n) = n
                """);

        assertThat(answerOf(module, "strict.echo", "[1, 2]")).isEqualTo(
                "{\"issues\":[{\"path\":\"\",\"code\":\"invalid_size\","
                        + "\"meta\":{\"actual\":\"2\",\"expected\":\"1\"}}]}");
        assertThat(answerOf(module, "strict.echo", "7")).isEqualTo(
                "{\"issues\":[{\"path\":\"\",\"code\":\"type_mismatch\","
                        + "\"meta\":{\"actual\":\"number\",\"expected\":\"arguments\"}}]}");
    }

    @Test
    void reportsEveryPlaceItRefusedRatherThanTheFirst() {
        Running module = compiled("""
                module several

                behavior all : (a: Int, b: Bool, c: String) -> Bool

                let all (a, b, c) = b
                """);

        String answer = answerOf(module, "several.all", "[\"x\", 1, false]");

        assertThat(answer).contains("\"path\":\"/0\"", "\"path\":\"/1\"", "\"path\":\"/2\"");
        assertThat(answer).contains("\"expected\":\"Int\"", "\"expected\":\"Bool\"",
                "\"expected\":\"String\"");
    }

    @Test
    void endsTheCallOnInputThatIsNotOneDocument() {
        Running module = compiled("""
                module strict

                behavior echo : (n: Int) -> Int

                let echo (n) = n
                """);

        assertThat(refusalFor(module, "strict.echo", "[1")).contains(AbortReason.MALFORMED_JSON);
    }

    @Test
    void saysWhatItMetRatherThanEmittingSomethingThatWouldAnswerWrongly() {
        CheckedProgram program = CheckedProgram.of(List.of("""
                module folding

                behavior total : (xs: List<Int>) -> Int

                let total (xs) = List.fold((acc, x) -> acc + x, 0, xs)
                """));

        assertThatThrownBy(() -> WasmCompiler.compile(program))
                .isInstanceOf(NotLowered.class);
    }

    @Test
    void saysSoForATypeItCannotReadAnArgumentAs() {
        CheckedProgram program = CheckedProgram.of(List.of("""
                module pricing

                behavior same : (amount: Decimal) -> Decimal

                let same (amount) = amount
                """));

        assertThatThrownBy(() -> WasmCompiler.compile(program))
                .isInstanceOf(NotLowered.class)
                .hasMessageContaining("does not write yet");
    }

    /** The reason a call ended, for input the behavior refuses. */
    private static Optional<AbortReason> refusalFor(Running module, String export, String arguments) {
        int snapshot = module.call(RuntimeAbi.FAILURE_GENERATION);
        int mark = module.call(RuntimeAbi.ALLOC_MARK);
        try {
            answerOf(module, export, arguments);
            throw new AssertionError(export + " answered " + arguments + " rather than refusing it");
        } catch (ChicoryException trapped) {
            var record = module.failureRecord();
            module.call(RuntimeAbi.ALLOC_RESET, mark);
            return record.describesTrapAfter(snapshot) ? record.namedReason() : Optional.empty();
        }
    }

    private static Running compiled(String... sources) {
        return Running.linked(WasmCompiler.compile(CheckedProgram.of(List.of(sources))));
    }

    private static String answerOf(Running module, String export) {
        return answerOf(module, export, "[]");
    }

    private static String answerOf(Running module, String export, String arguments) {
        int address = module.staged(arguments);
        long[] answer = module.callWithString(
                export, address, arguments.getBytes(StandardCharsets.UTF_8).length);
        return new String(module.read((int) answer[0], (int) answer[1]), StandardCharsets.UTF_8);
    }
}
