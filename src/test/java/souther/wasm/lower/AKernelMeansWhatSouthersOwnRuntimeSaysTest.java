package souther.wasm.lower;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import souther.compiler.program.CheckedProgram;
import souther.runtime.IntMath;
import souther.runtime.Strings;
import souther.wasm.Running;
import souther.wasm.abi.RuntimeAbi;

/**
 * What an intrinsic means, against Souther's own account of it.
 *
 * <p>These do not say what a kernel should answer. They run the same call twice — once as this
 * backend writes it and once as {@code souther-runtime} runs it, which is what the JVM backend
 * calls — and require the two to agree. A backend that only agreed with its own idea of an
 * operation would agree with nothing.
 */
class AKernelMeansWhatSouthersOwnRuntimeSaysTest {

    private static final String[] TEXTS = {
        "", "a", "abc", "  padded  ", "ごきげんよう", "a😀b", "\tmixed \n", "aaa", "a,b,,c",
    };

    @Test
    void measuresAStringWhereSouthersRuntimeMeasuresIt() {
        Running module = compiled("""
                module wording

                behavior sized : (s: String) -> Int

                let sized (s) = String.length(s)
                """);

        for (String text : TEXTS) {
            assertThat(answerOf(module, "wording.sized", array(quoted(text))))
                    .describedAs(text)
                    .isEqualTo(value(Long.toString(Strings.length(text))));
        }
    }

    @Test
    void cutsAStringWhereSouthersRuntimeCutsIt() {
        Running module = compiled("""
                module wording

                behavior part : (s: String, from: Int, to: Int) -> String

                let part (s, from, to) = String.slice(from, to, s)
                """);

        for (String text : TEXTS) {
            long held = Strings.length(text);
            for (long from = 0; from <= held; from++) {
                for (long to = from; to <= held; to++) {
                    assertThat(answerOf(module, "wording.part",
                            array(quoted(text), Long.toString(from), Long.toString(to))))
                            .describedAs(text + " " + from + ".." + to)
                            .isEqualTo(value(quoted(Strings.slice(text, from, to))));
                }
            }
        }
    }

    @Test
    void turnsAStringAroundWhereSouthersRuntimeDoes() {
        Running module = compiled("""
                module wording

                behavior turned : (s: String) -> String

                let turned (s) = String.reverse(s)
                """);

        for (String text : TEXTS) {
            assertThat(answerOf(module, "wording.turned", array(quoted(text))))
                    .describedAs(text)
                    .isEqualTo(value(quoted(Strings.reverse(text))));
        }
    }

    @Test
    void repeatsAndTrimsAndJoinsWhereSouthersRuntimeDoes() {
        Running module = compiled("""
                module wording

                behavior many : (n: Int, s: String) -> String

                let many (n, s) = String.repeat(n, s)

                behavior tidied : (s: String) -> String

                let tidied (s) = String.trim(s)

                behavior joined : (sep: String, xs: List<String>) -> String

                let joined (sep, xs) = String.join(sep, xs)
                """);

        for (String text : TEXTS) {
            for (long times : new long[] {-1, 0, 1, 3}) {
                assertThat(answerOf(module, "wording.many",
                        array(Long.toString(times), quoted(text))))
                        .describedAs(text + " x" + times)
                        .isEqualTo(value(quoted(Strings.repeat(text, times))));
            }
            assertThat(answerOf(module, "wording.tidied", array(quoted(text))))
                    .describedAs(text)
                    .isEqualTo(value(quoted(text.trim())));
        }
        assertThat(answerOf(module, "wording.joined",
                array(quoted("-"), "[\"a\",\"b\",\"c\"]")))
                .isEqualTo(value(quoted(Strings.join(List.of("a", "b", "c"), "-"))));
    }

    @Test
    void splitsAndReplacesWhereSouthersRuntimeDoes() {
        Running module = compiled("""
                module wording

                behavior pieces : (sep: String, s: String) -> List<String>

                let pieces (sep, s) = String.split(sep, s)

                behavior swapped : (target: String, put: String, s: String) -> String

                let swapped (target, put, s) = String.replace(target, put, s)
                """);

        for (String text : TEXTS) {
            for (String separator : new String[] {",", "a", "", "aa"}) {
                assertThat(answerOf(module, "wording.pieces",
                        array(quoted(separator), quoted(text))))
                        .describedAs(text + " on " + separator)
                        .isEqualTo(value(written(Strings.split(text, separator))));
                assertThat(answerOf(module, "wording.swapped",
                        array(quoted(separator), quoted("X"), quoted(text))))
                        .describedAs(text + " " + separator + "->X")
                        .isEqualTo(value(quoted(Strings.replace(text, separator, "X"))));
            }
        }
    }

    @Test
    void breaksAStringIntoCharactersWhereSouthersRuntimeDoes() {
        Running module = compiled("""
                module wording

                behavior letters : (s: String) -> List<String>

                let letters (s) = String.characters(s)

                behavior points : (s: String) -> List<Int>

                let points (s) = String.codePoints(s)
                """);

        for (String text : TEXTS) {
            assertThat(answerOf(module, "wording.letters", array(quoted(text))))
                    .describedAs(text)
                    .isEqualTo(value(written(Strings.characters(text))));
            assertThat(answerOf(module, "wording.points", array(quoted(text))))
                    .describedAs(text)
                    .isEqualTo(value(Strings.codePoints(text).toString().replace(" ", "")));
        }
    }

    @Test
    void countsWhereSouthersRuntimeCounts() {
        Running module = compiled("""
                module counting

                behavior compared : (a: Int, b: Int) -> Int

                let compared (a, b) = Int.compare(a, b)

                behavior modulo : (a: Int, b: Int) -> Int

                let modulo (a, b) = Int.floorMod(a, b)

                """);

        long[] numbers = {-7, -3, -1, 0, 1, 3, 7};
        for (long a : numbers) {
            for (long b : numbers) {
                assertThat(answerOf(module, "counting.compared", array(a, b)))
                        .describedAs(a + " " + b)
                        .isEqualTo(value(Long.toString(IntMath.compare(a, b))));
                if (b == 0) {
                    continue;
                }
                assertThat(answerOf(module, "counting.modulo", array(a, b)))
                        .describedAs(a + " mod " + b)
                        .isEqualTo(value(Long.toString(IntMath.floorMod(a, b))));
            }
        }
    }

    @Test
    void walksAListWhereSouthersOwnAccountDoes() {
        Running module = compiled("""
                module counting

                behavior sized : (xs: List<Int>) -> Int

                let sized (xs) = List.length(xs)

                behavior turned : (xs: List<Int>) -> List<Int>

                let turned (xs) = List.reverse(xs)

                behavior total : (xs: List<Int>) -> Int

                let total (xs) = List.sum(xs)

                behavior span : (from: Int, to: Int) -> List<Int>

                let span (from, to) = List.rangeInclusive(from, to)
                """);

        assertThat(answerOf(module, "counting.sized", "[[1,2,3]]")).isEqualTo(value("3"));
        assertThat(answerOf(module, "counting.turned", "[[1,2,3]]")).isEqualTo(value("[3,2,1]"));
        assertThat(answerOf(module, "counting.total", "[[1,2,3]]")).isEqualTo(value("6"));
        assertThat(answerOf(module, "counting.total", "[[]]")).isEqualTo(value("0"));
        assertThat(answerOf(module, "counting.span", "[2,5]")).isEqualTo(value("[2,3,4,5]"));
        assertThat(answerOf(module, "counting.span", "[5,2]")).isEqualTo(value("[]"));
    }

    @Test
    void saysSoForAnIntrinsicItDoesNotWriteYet() {
        CheckedProgram program = CheckedProgram.of(List.of("""
                module wording

                behavior shouted : (s: String) -> String

                let shouted (s) = String.uppercase(s)
                """));

        assertThatThrownBy(() -> WasmCompiler.compile(program))
                .isInstanceOf(NotLowered.class)
                .hasMessageContaining("STRING_UPPERCASE");
    }

    private static String value(String written) {
        return "{\"value\":" + written + "}";
    }

    private static String array(String... arguments) {
        return "[" + String.join(",", arguments) + "]";
    }

    private static String array(long a, long b) {
        return "[" + a + "," + b + "]";
    }

    /** A string as JSON writes it. The texts here carry nothing but a tab and a newline to escape. */
    private static String quoted(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\t", "\\t").replace("\n", "\\n") + "\"";
    }

    private static String written(List<String> texts) {
        return "[" + String.join(",", texts.stream().map(
                AKernelMeansWhatSouthersOwnRuntimeSaysTest::quoted).toList()) + "]";
    }

    private static Running compiled(String... sources) {
        return Running.linked(WasmCompiler.compile(CheckedProgram.of(List.of(sources))));
    }

    private static String answerOf(Running module, String export, String arguments) {
        int mark = module.call(RuntimeAbi.ALLOC_MARK);
        int address = module.staged(arguments);
        long[] answer = module.callWithString(
                export, address, arguments.getBytes(StandardCharsets.UTF_8).length);
        String written = new String(
                module.read((int) answer[0], (int) answer[1]), StandardCharsets.UTF_8);
        module.call(RuntimeAbi.ALLOC_RESET, mark);
        return written;
    }
}
