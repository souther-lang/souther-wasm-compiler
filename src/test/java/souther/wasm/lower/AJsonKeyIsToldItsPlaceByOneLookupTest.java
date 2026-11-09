package souther.wasm.lower;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import souther.wasm.Compiled;
import souther.wasm.Running;

/**
 * A document's key names a field and its tag names a case, and which is told by one lookup of the
 * name rather than by comparing it with each declared name in turn (issue #77).
 */
class AJsonKeyIsToldItsPlaceByOneLookupTest {

    private static final String SOURCE = """
            module keyed

            data Point = { x: Int, y: Int, label: String? }
            data Circle = { radius: Int }
            data Line = { from: Point, to: Point }
            data Shape = Circle | Line | Point

            behavior echoPoint : (p: Point) -> Point
            let echoPoint (p) = p

            behavior echoShape : (s: Shape) -> Shape
            let echoShape (s) = s

            data Light = Red | Amber | Green | Ambers

            behavior echoLight : (l: Light) -> Light
            let echoLight (l) = l
            """;

    @Test
    void readsFieldsWhateverOrderTheKeysAreWrittenIn() {
        Running module = compiled();

        assertThat(answerOf(module, "keyed.echoPoint", "[{\"label\":\"a\",\"y\":2,\"x\":1}]"))
                .isEqualTo("{\"value\":{\"x\":1,\"y\":2,\"label\":\"a\"}}");
    }

    @Test
    void ignoresAKeyTheTypeDoesNotDeclareAndReadsTheFirstOfAKeyWrittenTwice() {
        Running module = compiled();

        assertThat(answerOf(module, "keyed.echoPoint", "[{\"x\":1,\"extra\":9,\"y\":2,\"x\":7}]"))
                .isEqualTo("{\"value\":{\"x\":1,\"y\":2}}");
    }

    @Test
    void reportsAMissingFieldInTheOrderTheyAreDeclared() {
        Running module = compiled();

        // Whatever order the keys came in, y is reported missing, and x is read.
        assertThat(answerOf(module, "keyed.echoPoint", "[{\"label\":\"a\",\"x\":1}]"))
                .contains("/y").doesNotContain("/x");
    }

    @Test
    void telsACaseByItsNameAndNotByAnotherThatBeginsAsItDoes() {
        Running module = compiled();

        assertThat(answerOf(module, "keyed.echoLight", "[\"Amber\"]")).isEqualTo("{\"value\":\"Amber\"}");
        assertThat(answerOf(module, "keyed.echoLight", "[\"Ambers\"]")).isEqualTo("{\"value\":\"Ambers\"}");
        assertThat(answerOf(module, "keyed.echoLight", "[\"Ambe\"]")).doesNotContain("\"value\"");
        assertThat(answerOf(module, "keyed.echoShape", "[{\"type\":\"Circle\",\"radius\":3}]"))
                .isEqualTo("{\"value\":{\"type\":\"Circle\",\"radius\":3}}");
        assertThat(answerOf(module, "keyed.echoShape", "[{\"type\":\"Squar\"}]"))
                .doesNotContain("\"value\"");
    }

    @Test
    void readsAWideShapeWhateverOrderItsKeysAreIn() {
        int fields = 64;
        String declared = IntStream.range(0, fields).mapToObj(i -> "f" + i + ": Int").collect(Collectors.joining(", "));
        String source = "module wide\n\ndata W = { " + declared + " }\n\n"
                + "behavior echo : (w: W) -> W\nlet echo (w) = w\n";
        Running module = Running.linked(Compiled.module(Compiled.program(List.of(source))));
        String reversed = "{" + IntStream.range(0, fields).mapToObj(i -> "\"f" + (fields - 1 - i) + "\":" + (fields - 1 - i))
                .collect(Collectors.joining(",")) + "}";
        String inOrder = "{" + IntStream.range(0, fields).mapToObj(i -> "\"f" + i + "\":" + i)
                .collect(Collectors.joining(",")) + "}";

        assertThat(answerOf(module, "wide.echo", "[" + reversed + "]"))
                .isEqualTo("{\"value\":" + inOrder + "}");
    }

    /**
     * A field read off a set of alternatives is found by its name in the descriptor of the case the
     * value is, and the two cases do not keep it at the same place.
     */
    @Test
    void aFieldReadOffASumIsFoundByNameWhereverEachCaseKeepsIt() {
        int fields = 48;
        String declared = IntStream.range(0, fields).mapToObj(i -> "f" + i + ": Int").collect(Collectors.joining(", "));
        String source = "module spread\n\ndata Common = { " + declared + " }\n"
                + "data P = { ...Common }\ndata Q = { extra: Int, ...Common }\ndata PQ = P | Q\n\n"
                + "behavior last : (x: PQ) -> Int\nlet last (x) = x.f" + (fields - 1) + "\n\n"
                + "behavior first : (x: PQ) -> Int\nlet first (x) = x.f0\n";
        Running module = Running.linked(Compiled.module(Compiled.program(List.of(source))));
        String body = IntStream.range(0, fields).mapToObj(i -> "\"f" + i + "\":" + (100 + i))
                .collect(Collectors.joining(","));

        assertThat(answerOf(module, "spread.last", "[{\"type\":\"P\"," + body + "}]")).isEqualTo("{\"value\":147}");
        assertThat(answerOf(module, "spread.last", "[{\"type\":\"Q\",\"extra\":1," + body + "}]")).isEqualTo("{\"value\":147}");
        assertThat(answerOf(module, "spread.first", "[{\"type\":\"Q\",\"extra\":1," + body + "}]")).isEqualTo("{\"value\":100}");
    }

    private static Running compiled() {
        return Running.linked(Compiled.module(Compiled.program(List.of(SOURCE))));
    }

    private static String answerOf(Running module, String export, String arguments) {
        int address = module.staged(arguments);
        long[] answer = module.callWithString(
                export, address, arguments.getBytes(StandardCharsets.UTF_8).length);
        return new String(module.read((int) answer[0], (int) answer[1]), StandardCharsets.UTF_8);
    }
}
