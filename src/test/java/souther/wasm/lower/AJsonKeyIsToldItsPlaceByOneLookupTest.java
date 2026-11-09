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

    /**
     * A field read off a set of alternatives that nothing crosses: its cases are neither read nor
     * written, and are asked their names only because the body finds a field by name off a value of
     * one.
     */
    @Test
    void aFieldFoundByNameOffAValueNothingCrossesIsStillFound() {
        Running module = Running.linked(Compiled.module(Compiled.program(List.of("""
                module held exposing ( total )

                data Common = { f: Int, g: Int }
                data P = { ...Common }
                data Q = { extra: Int, ...Common }
                data PQ = P | Q

                let make (n: Int): PQ =
                    if n > 0 then P { f = n, g = 1 } else Q { extra = 0, f = 5, g = 2 }

                behavior total : (n: Int) -> Int

                let total (n) = {
                    let v = make(n)
                    v.g
                }
                """))));

        assertThat(answerOf(module, "held.total", "[3]")).isEqualTo("{\"value\":1}");
        assertThat(answerOf(module, "held.total", "[0]")).isEqualTo("{\"value\":2}");
    }

    /**
     * Names are told by their bytes, as the runtime hashes them: a name that is not ASCII is hashed
     * over its UTF-8 and told from the next by the same bytes.
     */
    @Test
    void namesThatAreNotAsciiAreToldByTheirBytes() {
        Running module = Running.linked(Compiled.module(Compiled.program(List.of("""
                module jp

                data 赤
                data 青
                data 色 = 赤 | 青

                data 人 = { 名前: String, 年齢: Int }

                behavior 写す : (c: 色, p: 人) -> 人

                let 写す (c, p) = p

                behavior 色を写す : (c: 色) -> 色

                let 色を写す (c) = c
                """))));

        assertThat(answerOf(module, "jp.写す", "[\"青\",{\"年齢\":7,\"名前\":\"a\"}]"))
                .isEqualTo("{\"value\":{\"名前\":\"a\",\"年齢\":7}}");
        assertThat(answerOf(module, "jp.色を写す", "[\"赤\"]")).isEqualTo("{\"value\":\"赤\"}");
        assertThat(answerOf(module, "jp.色を写す", "[\"青\"]")).isEqualTo("{\"value\":\"青\"}");
    }

    /**
     * Past what a handful of names show: two hundred and fifty fields (the most a shape may have)
     * read by their keys in an order that is neither the declared one nor its reverse, and three
     * hundred tags, each in a table that has names lying beside the slot they hash to — which this
     * requires, so that it cannot stop being so.
     */
    @Test
    void manyNamesAreEachFoundInATableWithCollisions() {
        int fields = 250;
        int tags = 300;
        assertThat(displaced(fields)).as("field names not at the slot they hash to").isPositive();
        assertThat(displaced(tags)).as("tags not at the slot they hash to").isPositive();

        String declared = IntStream.range(0, fields).mapToObj(i -> "n" + i + ": String")
                .collect(Collectors.joining(", "));
        String cases = IntStream.range(0, tags).mapToObj(i -> "Cn" + i).collect(Collectors.joining(" | "));
        String types = IntStream.range(0, tags).mapToObj(i -> "data Cn" + i).collect(Collectors.joining("\n"));
        String source = "module many\n\ndata W = { " + declared + " }\n\n" + types + "\n\ndata K = " + cases
                + "\n\nbehavior echo : (w: W) -> W\n\nlet echo (w) = w\n\n"
                + "behavior kind : (k: K) -> K\n\nlet kind (k) = k\n";
        Running module = Running.linked(Compiled.module(Compiled.program(List.of(source))));
        List<Integer> order = new java.util.ArrayList<>(IntStream.range(0, fields).boxed().toList());
        java.util.Collections.shuffle(order, new java.util.Random(5));
        String shuffled = order.stream().map(i -> "\"n" + i + "\":\"v" + i + "\"").collect(Collectors.joining(","));
        String inOrder = IntStream.range(0, fields).mapToObj(i -> "\"n" + i + "\":\"v" + i + "\"")
                .collect(Collectors.joining(","));

        assertThat(answerOf(module, "many.echo", "[{" + shuffled + "}]"))
                .isEqualTo("{\"value\":{" + inOrder + "}}");
        for (int i : new int[] {0, 1, 149, 150, 298, 299}) {
            assertThat(answerOf(module, "many.kind", "[\"Cn" + i + "\"]"))
                    .isEqualTo("{\"value\":\"Cn" + i + "\"}");
        }
    }

    /** How many of {@code n0 .. n(count-1)} are not at the slot they hash to, in the table of them. */
    private static int displaced(int count) {
        List<NameIndex.Entry> entries = IntStream.range(0, count)
                .mapToObj(i -> new NameIndex.Entry("n" + i, 4096 + 16 * i, i)).toList();
        java.nio.ByteBuffer table = java.nio.ByteBuffer.wrap(NameIndex.of(entries))
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        int mask = table.getInt(0);
        int displaced = 0;
        for (int slot = 0; slot <= mask; slot++) {
            int address = table.getInt(4 + 12 * slot);
            if (address != 0) {
                String name = "n" + (address - 4096) / 16;
                if (NameIndex.hash(name.getBytes(java.nio.charset.StandardCharsets.UTF_8), mask) != slot) {
                    displaced++;
                }
            }
        }
        return displaced;
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
