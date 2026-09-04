package souther.wasm.lower;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import souther.compiler.program.CheckedProgram;
import souther.wasm.Running;

/**
 * A map as an object, its keys the keys, its entries ascending by them.
 *
 * <p>A key written twice names one entry: what reaches a decoder is what the document says at that
 * key, and a document says it last.
 */
class AMapIsWrittenAsAnObjectInKeyOrderTest {

    private static final String TALLY = """
            module counting

            data Tally = { by: Map<String, Int> }

            behavior same : (t: Tally) -> Tally

            let same (t) = t
            """;

    @Test
    void writesTheEntriesAscendingByKeyHoweverTheyWereWritten() {
        Running module = compiled(TALLY);

        assertThat(objectOf(module, "counting.same", "{\"b\": 2, \"a\": 1, \"c\": 3}"))
                .isEqualTo("{\"a\":1,\"b\":2,\"c\":3}");
    }

    @Test
    void keepsWhatTheDocumentSaysLastForAKeyWrittenTwice() {
        Running module = compiled(TALLY);

        assertThat(objectOf(module, "counting.same", "{\"a\": 1, \"a\": 2}"))
                .isEqualTo("{\"a\":2}");
    }

    @Test
    void saysWhichKeysValueItRefused() {
        Running module = compiled(TALLY);

        assertThat(answerOf(module, "counting.same", "[{\"by\": {\"a\": \"one\"}}]"))
                .contains("\"path\":\"/0/by/a\"", "\"code\":\"type_mismatch\"");
    }

    @Test
    void writesAnEmptyMapAsAnEmptyObject() {
        Running module = compiled(TALLY);

        assertThat(objectOf(module, "counting.same", "{}")).isEqualTo("{}");
    }

    @Test
    void carriesAMapOfShapes() {
        Running module = compiled("""
                module drawing

                data Point = { x: Int, y: Int }

                data Marks = { at: Map<String, Point> }

                behavior same : (m: Marks) -> Marks

                let same (m) = m
                """);

        String answer = answerOf(module, "drawing.same",
                "[{\"at\": {\"b\": {\"x\": 3, \"y\": 4}, \"a\": {\"x\": 1, \"y\": 2}}}]");

        assertThat(answer).isEqualTo(
                "{\"value\":{\"at\":{\"a\":{\"x\":1,\"y\":2},\"b\":{\"x\":3,\"y\":4}}}}");
    }

    @Test
    void saysSoWhereWhatWasWrittenIsNotAnObject() {
        Running module = compiled(TALLY);

        assertThat(answerOf(module, "counting.same", "[{\"by\": 7}]"))
                .contains("\"expected\":\"an object\"", "\"actual\":\"number\"");
    }

    @Test
    void takesAMapKeyedByAnythingWrittenAsAStringWhereverOneStands() {
        // Inside a shape as well as at the boundary: a map's key carries its own descriptor, so
        // nothing here decides a second time what a key of that type looks like.
        assertThat(WasmCompiler.compile(CheckedProgram.of(List.of("""
                module counting

                data Tally = { by: Map<Date, Int> }

                behavior same : (t: Tally) -> Tally

                let same (t) = t
                """)))).isNotEmpty();
    }

    /** What the module answers for a map written that way, as its object alone. */
    private static String objectOf(Running module, String export, String written) {
        String answer = answerOf(module, export, "[{\"by\": " + written + "}]");
        int opens = answer.indexOf("{\"by\":") + "{\"by\":".length();
        return answer.substring(opens, answer.length() - 2);
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
