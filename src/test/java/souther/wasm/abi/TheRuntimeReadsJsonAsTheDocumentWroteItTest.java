package souther.wasm.abi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dylibso.chicory.wasm.ChicoryException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import souther.wasm.Running;

/**
 * The runtime's JSON reading, run against documents.
 *
 * <p>What is checked is that a document comes back as it was written. Which Souther type reads a
 * given place is a later question and is not asked here — a number is the digits that were there,
 * and nothing has yet decided whether they are an {@code Int} or a {@code Decimal}.
 */
class TheRuntimeReadsJsonAsTheDocumentWroteItTest {

    @Test
    void readsEachKindAsTheKindItIs() {
        Running runtime = Running.bareRuntime();

        assertThat(tagOf(runtime, "null")).isEqualTo(JsonTag.NULL);
        assertThat(tagOf(runtime, "true")).isEqualTo(JsonTag.TRUE);
        assertThat(tagOf(runtime, "false")).isEqualTo(JsonTag.FALSE);
        assertThat(tagOf(runtime, "42")).isEqualTo(JsonTag.NUMBER);
        assertThat(tagOf(runtime, "\"x\"")).isEqualTo(JsonTag.STRING);
        assertThat(tagOf(runtime, "[]")).isEqualTo(JsonTag.ARRAY);
        assertThat(tagOf(runtime, "{}")).isEqualTo(JsonTag.OBJECT);
    }

    @Test
    void keepsANumbersDigitsRatherThanANumberItParsedThemInto() {
        Running runtime = Running.bareRuntime();

        assertThat(textAt(runtime, parsed(runtime, "1"))).isEqualTo("1");
        assertThat(textAt(runtime, parsed(runtime, "1.0"))).isEqualTo("1.0");
        assertThat(textAt(runtime, parsed(runtime, "-2.5e-3"))).isEqualTo("-2.5e-3");
    }

    @Test
    void readsAStringBackAsTheTextItStandsFor() {
        Running runtime = Running.bareRuntime();

        assertThat(textAt(runtime, parsed(runtime, "\"a\\\"b\\\\c\\nd\\te\"")))
                .isEqualTo("a\"b\\c\nd\te");
        assertThat(textAt(runtime, parsed(runtime, "\"\\u3042\""))).isEqualTo("あ");
        assertThat(textAt(runtime, parsed(runtime, "\"\\ud83d\\ude00\""))).isEqualTo("😀");
        assertThat(textAt(runtime, parsed(runtime, "\"ごきげんよう\""))).isEqualTo("ごきげんよう");
    }

    @Test
    void keepsAnArraysElementsInTheOrderTheyWereWritten() {
        Running runtime = Running.bareRuntime();
        int array = parsed(runtime, "[1, \"two\", true]");

        assertThat(runtime.call(RuntimeAbi.JSON_LENGTH, array)).isEqualTo(3);
        assertThat(textAt(runtime, element(runtime, array, 0))).isEqualTo("1");
        assertThat(textAt(runtime, element(runtime, array, 1))).isEqualTo("two");
        assertThat(runtime.call(RuntimeAbi.JSON_TAG, element(runtime, array, 2)))
                .isEqualTo(JsonTag.TRUE.tag());
    }

    @Test
    void keepsAnObjectsEntriesInTheOrderTheyWereWritten() {
        Running runtime = Running.bareRuntime();
        int object = parsed(runtime, "{\"b\": 2, \"a\": 1}");

        assertThat(runtime.call(RuntimeAbi.JSON_LENGTH, object)).isEqualTo(2);
        assertThat(textAt(runtime, runtime.call(RuntimeAbi.JSON_KEY, object, 0))).isEqualTo("b");
        assertThat(textAt(runtime, runtime.call(RuntimeAbi.JSON_VALUE, object, 0))).isEqualTo("2");
        assertThat(textAt(runtime, runtime.call(RuntimeAbi.JSON_KEY, object, 1))).isEqualTo("a");
    }

    @Test
    void readsWhatIsNestedInsideAContainer() {
        Running runtime = Running.bareRuntime();
        int outer = parsed(runtime, "{\"xs\": [{\"n\": 7}]}");
        int list = runtime.call(RuntimeAbi.JSON_VALUE, outer, 0);
        int first = element(runtime, list, 0);

        assertThat(textAt(runtime, runtime.call(RuntimeAbi.JSON_KEY, first, 0))).isEqualTo("n");
        assertThat(textAt(runtime, runtime.call(RuntimeAbi.JSON_VALUE, first, 0))).isEqualTo("7");
    }

    @Test
    void endsTheCallOnWhatIsNotOneDocument() {
        for (String written : new String[] {
                "", "[1,", "{\"a\"}", "tru", "01", "\"unterminated", "1 2", "[1]]", "\"\\q\"",
                "+1", ".5", "1.", "-", "[,]", "{1:2}",
        }) {
            Running runtime = Running.bareRuntime();
            int snapshot = runtime.call(RuntimeAbi.FAILURE_GENERATION);
            int address = runtime.staged(written);

            assertThatThrownBy(() -> runtime.call(RuntimeAbi.JSON_PARSE, address,
                    written.getBytes(StandardCharsets.UTF_8).length))
                    .describedAs(written)
                    .isInstanceOf(ChicoryException.class);

            FailureRecord record = runtime.failureRecord();
            assertThat(record.describesTrapAfter(snapshot)).describedAs(written).isTrue();
            assertThat(record.namedReason()).describedAs(written).contains(AbortReason.MALFORMED_JSON);
        }
    }

    @Test
    void endsTheCallOnADocumentNestedPastWhatOneMayBe() {
        // A walk into an array is a call, and how much stack there is is not something a caller
        // may write down. Past what a document may be nested, it is refused the way anything that
        // is not one document is refused — with a reason a caller reads — and not by the stack
        // running out, which is a fault of this module and reads to a caller as one.
        String written = "[".repeat(50_000) + "]".repeat(50_000);
        Running runtime = Running.bareRuntime();
        int snapshot = runtime.call(RuntimeAbi.FAILURE_GENERATION);
        int address = runtime.staged(written);

        assertThatThrownBy(() -> runtime.call(RuntimeAbi.JSON_PARSE, address,
                        written.getBytes(StandardCharsets.UTF_8).length))
                .isInstanceOf(ChicoryException.class);

        FailureRecord record = runtime.failureRecord();
        assertThat(record.describesTrapAfter(snapshot)).isTrue();
        assertThat(record.namedReason()).contains(AbortReason.MALFORMED_JSON);
    }

    @Test
    void takesADocumentNestedAsDeepAsOneMayBe() {
        // The bound is not what a model asks for. A document nested as far as anything anybody
        // writes still reads.
        String written = "[".repeat(150) + "1" + "]".repeat(150);
        Running runtime = Running.bareRuntime();

        assertThat(runtime.call(RuntimeAbi.JSON_PARSE, runtime.staged(written),
                        written.getBytes(StandardCharsets.UTF_8).length))
                .describedAs("what a document nested that far comes to").isNotZero();
    }

    @Test
    void readsBackWhatItWroteForEveryScalarItWrites() {
        Running runtime = Running.bareRuntime();

        assertThat(runtime.textOf(runtime.callPacked(RuntimeAbi.JSON_WRITE_INT, 0))).isEqualTo("0");
        assertThat(runtime.textOf(runtime.callPacked(RuntimeAbi.JSON_WRITE_INT, 42))).isEqualTo("42");
        assertThat(runtime.textOf(runtime.callPacked(RuntimeAbi.JSON_WRITE_INT, -42))).isEqualTo("-42");
        assertThat(runtime.textOf(runtime.callPacked(RuntimeAbi.JSON_WRITE_INT, Long.MIN_VALUE)))
                .isEqualTo(Long.toString(Long.MIN_VALUE));
        assertThat(runtime.textOf(runtime.callPacked(RuntimeAbi.JSON_WRITE_INT, Long.MAX_VALUE)))
                .isEqualTo(Long.toString(Long.MAX_VALUE));
        assertThat(runtime.textOf(runtime.callPacked(RuntimeAbi.JSON_WRITE_BOOL, 1))).isEqualTo("true");
        assertThat(runtime.textOf(runtime.callPacked(RuntimeAbi.JSON_WRITE_BOOL, 0))).isEqualTo("false");
    }

    @Test
    void writesAStringSoThatReadingItBackGivesTheSameText() {
        Running runtime = Running.bareRuntime();
        String text = "a\"b\\c\nd\te\u0001f😀ご";
        byte[] utf8 = text.getBytes(StandardCharsets.UTF_8);
        int address = runtime.staged(text);

        long written = runtime.callPacked(RuntimeAbi.JSON_WRITE_STRING, address, utf8.length);
        int reparsed = runtime.call(RuntimeAbi.JSON_PARSE,
                RuntimeAbi.pointerOf(written), RuntimeAbi.lengthOf(written));

        assertThat(textAt(runtime, reparsed)).isEqualTo(text);
    }

    private static JsonTag tagOf(Running runtime, String document) {
        return JsonTag.of(runtime.call(RuntimeAbi.JSON_TAG, parsed(runtime, document))).orElseThrow();
    }

    private static int parsed(Running runtime, String document) {
        byte[] utf8 = document.getBytes(StandardCharsets.UTF_8);
        return runtime.call(RuntimeAbi.JSON_PARSE, runtime.staged(document), utf8.length);
    }

    private static int element(Running runtime, int array, int index) {
        return runtime.call(RuntimeAbi.JSON_ELEMENT, array, index);
    }

    private static String textAt(Running runtime, int cell) {
        int bytes = runtime.call(RuntimeAbi.JSON_BYTES, cell);
        int length = runtime.call(RuntimeAbi.JSON_LENGTH, cell);
        return new String(runtime.read(bytes, length), StandardCharsets.UTF_8);
    }
}
