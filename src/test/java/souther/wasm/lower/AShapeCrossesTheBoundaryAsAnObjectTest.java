package souther.wasm.lower;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import souther.compiler.program.CheckedProgram;
import souther.wasm.Running;

/**
 * A data written as fields, crossing the boundary in both directions.
 *
 * <p>An object, keyed by the field names the shape declares, which is what the JVM backend's
 * derived codec reads and writes. A value of one shape has to come back as the same document a
 * value of that shape went in as, or the two backends are two languages.
 */
class AShapeCrossesTheBoundaryAsAnObjectTest {

    private static final String POINT = """
            module geometry

            data Point = { x: Int, y: Int }

            behavior flip : (p: Point) -> Point

            let flip (p) = Point { x = p.y, y = p.x }
            """;

    @Test
    void readsAShapeOutOfTheObjectItsFieldsWereWrittenIn() {
        Running module = compiled(POINT);

        assertThat(answerOf(module, "geometry.flip", "[{\"x\": 1, \"y\": 2}]"))
                .isEqualTo("{\"value\":{\"x\":2,\"y\":1}}");
    }

    @Test
    void writesTheFieldsInTheOrderTheShapeDeclaresThem() {
        Running module = compiled(POINT);

        assertThat(answerOf(module, "geometry.flip", "[{\"y\": 2, \"x\": 1}]"))
                .isEqualTo("{\"value\":{\"x\":2,\"y\":1}}");
    }

    @Test
    void answersAShapeInItsOwnFieldOrderHoweverTheConstructionWasWritten() {
        Running module = compiled("""
                module geometry

                data Point = { x: Int, y: Int }

                behavior make : (a: Int, b: Int) -> Point

                let make (a, b) = Point { y = b, x = a }
                """);

        assertThat(answerOf(module, "geometry.make", "[1, 2]"))
                .isEqualTo("{\"value\":{\"x\":1,\"y\":2}}");
    }

    @Test
    void readsAShapeNestedInsideAnother() {
        Running module = compiled("""
                module drawing

                data Point = { x: Int, y: Int }

                data Line = { from: Point, to: Point }

                behavior start : (l: Line) -> Point

                let start (l) = l.from
                """);

        assertThat(answerOf(module, "drawing.start",
                "[{\"from\": {\"x\": 1, \"y\": 2}, \"to\": {\"x\": 3, \"y\": 4}}]"))
                .isEqualTo("{\"value\":{\"x\":1,\"y\":2}}");
    }

    @Test
    void saysWhichFieldWasMissingRatherThanThatSomethingWas() {
        Running module = compiled(POINT);

        assertThat(answerOf(module, "geometry.flip", "[{\"x\": 1}]")).isEqualTo(
                "{\"issues\":[{\"path\":\"/0/y\",\"code\":\"missing_field\","
                        + "\"meta\":{\"actual\":\"nothing\",\"expected\":\"a field\"}}]}");
    }

    @Test
    void saysWhereInsideTheDocumentAFieldWasRefused() {
        Running module = compiled("""
                module drawing

                data Point = { x: Int, y: Int }

                data Line = { from: Point, to: Point }

                behavior start : (l: Line) -> Point

                let start (l) = l.from
                """);

        String answer = answerOf(module, "drawing.start",
                "[{\"from\": {\"x\": \"one\", \"y\": 2}, \"to\": {\"x\": 3, \"y\": 4}}]");

        assertThat(answer).contains("\"path\":\"/0/from/x\"", "\"code\":\"type_mismatch\"");
    }

    @Test
    void reportsEveryFieldItRefusedRatherThanTheFirst() {
        Running module = compiled(POINT);

        String answer = answerOf(module, "geometry.flip", "[{\"x\": \"one\", \"y\": true}]");

        assertThat(answer).contains("\"path\":\"/0/x\"", "\"path\":\"/0/y\"");
    }

    @Test
    void saysSoWhereWhatWasWrittenIsNotAnObjectAtAll() {
        Running module = compiled(POINT);

        assertThat(answerOf(module, "geometry.flip", "[7]"))
                .contains("\"path\":\"/0\"", "\"expected\":\"an object\"", "\"actual\":\"number\"");
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
