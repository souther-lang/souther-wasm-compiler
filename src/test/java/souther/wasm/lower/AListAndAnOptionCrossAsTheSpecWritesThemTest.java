package souther.wasm.lower;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import souther.compiler.program.CheckedProgram;
import souther.wasm.Running;

/**
 * A list as an array, and an option as absence written the way its place writes it.
 *
 * <p>Which way absence is written depends on whether there is a key to be missing. In a field
 * there is one, and leaving it out is what absence is; in a list, a map's value or an answer there
 * is not, and there absence is {@code null}.
 */
class AListAndAnOptionCrossAsTheSpecWritesThemTest {

    @Test
    void carriesAListAsAnArrayInTheOrderItHoldsIt() {
        Running module = compiled("""
                module counting

                behavior same : (xs: List<Int>) -> List<Int>

                let same (xs) = xs
                """);

        assertThat(answerOf(module, "counting.same", "[[3, 1, 2]]"))
                .isEqualTo("{\"value\":[3,1,2]}");
        assertThat(answerOf(module, "counting.same", "[[]]")).isEqualTo("{\"value\":[]}");
    }

    @Test
    void saysWhichPlaceOfAListItRefused() {
        Running module = compiled("""
                module counting

                behavior same : (xs: List<Int>) -> List<Int>

                let same (xs) = xs
                """);

        String answer = answerOf(module, "counting.same", "[[1, \"two\", 3]]");

        assertThat(answer).contains("\"path\":\"/0/1\"", "\"code\":\"type_mismatch\"");
    }

    @Test
    void readsAListOfShapes() {
        Running module = compiled("""
                module drawing

                data Point = { x: Int, y: Int }

                behavior same : (ps: List<Point>) -> List<Point>

                let same (ps) = ps
                """);

        assertThat(answerOf(module, "drawing.same", "[[{\"x\": 1, \"y\": 2}]]"))
                .isEqualTo("{\"value\":[{\"x\":1,\"y\":2}]}");
    }

    @Test
    void writesAnAbsentFieldByLeavingItsKeyOut() {
        Running module = compiled("""
                module naming

                data Person = { name: String, nickname: String? }

                behavior same : (p: Person) -> Person

                let same (p) = p
                """);

        assertThat(answerOf(module, "naming.same", "[{\"name\": \"a\"}]"))
                .isEqualTo("{\"value\":{\"name\":\"a\"}}");
        assertThat(answerOf(module, "naming.same", "[{\"name\": \"a\", \"nickname\": \"b\"}]"))
                .isEqualTo("{\"value\":{\"name\":\"a\",\"nickname\":\"b\"}}");
    }

    @Test
    void writesAbsenceAsNullWhereThereIsNoKeyToLeaveOut() {
        Running module = compiled("""
                module naming

                data Names = { all: List<Option<String>> }

                behavior same : (n: Names) -> Names

                let same (n) = n
                """);

        assertThat(answerOf(module, "naming.same", "[{\"all\": [\"a\", null]}]"))
                .isEqualTo("{\"value\":{\"all\":[\"a\",null]}}");
    }

    @Test
    void readsANullFieldAsAbsentJustAsAMissingKeyIs() {
        Running module = compiled("""
                module naming

                data Person = { name: String, nickname: String? }

                behavior same : (p: Person) -> Person

                let same (p) = p
                """);

        assertThat(answerOf(module, "naming.same", "[{\"name\": \"a\", \"nickname\": null}]"))
                .isEqualTo("{\"value\":{\"name\":\"a\"}}");
    }

    @Test
    void saysSoWhereWhatWasWrittenIsNotAnArray() {
        Running module = compiled("""
                module counting

                behavior same : (xs: List<Int>) -> List<Int>

                let same (xs) = xs
                """);

        assertThat(answerOf(module, "counting.same", "[7]"))
                .contains("\"expected\":\"an array\"", "\"actual\":\"number\"");
    }

    private static Running compiled(String... sources) {
        return Running.linked(WasmCompiler.compile(CheckedProgram.of(List.of(sources))));
    }

    private static String answerOf(Running module, String export, String arguments) {
        int address = module.staged(arguments);
        long[] answer = module.callWithString(
                export, address, arguments.getBytes(StandardCharsets.UTF_8).length);
        return new String(module.read((int) answer[0], (int) answer[1]), StandardCharsets.UTF_8);
    }
}
