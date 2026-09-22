package souther.wasm.lower;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import souther.compiler.core.Kernel;
import souther.compiler.program.CheckedProgram;
import souther.runtime.IntMath;
import souther.runtime.Strings;
import souther.wasm.Running;
import souther.wasm.link.LinkPlan;
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
        "one\r\ntwo\n", "Ĳ ǅ ß",
        // Non-ASCII members of String whitespace (spec §string-whitespace), and near-miss
        // code points that are not: a run through this must agree byte-for-byte with the same
        // run through souther-runtime, not merely through whichever the WASM backend implements.
        " padded ", "　ごきげんよう　", "a 　b", "\u0085  ",
        "a​b", "a﻿b", "a\u001cb",
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
                    .isEqualTo(value(quoted(Strings.trim(text))));
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
    void recasesAndBreaksAndPadsWhereSouthersRuntimeDoes() {
        Running module = compiled("""
                module wording

                behavior quiet : (s: String) -> String

                let quiet (s) = String.lowercase(s)

                behavior loud : (s: String) -> String

                let loud (s) = String.uppercase(s)

                behavior spoken : (s: String) -> List<String>

                let spoken (s) = String.words(s)

                behavior rows : (s: String) -> List<String>

                let rows (s) = String.lines(s)

                behavior widened : (n: Int, p: String, s: String) -> String

                let widened (n, p, s) = String.padLeft(n, p, s)

                behavior lengthened : (n: Int, p: String, s: String) -> String

                let lengthened (n, p, s) = String.padRight(n, p, s)
                """);

        for (String text : TEXTS) {
            assertThat(answerOf(module, "wording.quiet", array(quoted(text))))
                    .describedAs(text).isEqualTo(value(quoted(Strings.lowercase(text))));
            assertThat(answerOf(module, "wording.loud", array(quoted(text))))
                    .describedAs(text).isEqualTo(value(quoted(Strings.uppercase(text))));
            assertThat(answerOf(module, "wording.spoken", array(quoted(text))))
                    .describedAs(text).isEqualTo(value(written(Strings.words(text))));
            assertThat(answerOf(module, "wording.rows", array(quoted(text))))
                    .describedAs(text).isEqualTo(value(written(Strings.lines(text))));
            for (long width : new long[] {0, 3, 8}) {
                for (String pad : new String[] {"-", "ab", ""}) {
                    assertThat(answerOf(module, "wording.widened",
                            array(Long.toString(width), quoted(pad), quoted(text))))
                            .describedAs(text + " left " + width + " " + pad)
                            .isEqualTo(value(quoted(Strings.padLeft(text, width, pad))));
                    assertThat(answerOf(module, "wording.lengthened",
                            array(Long.toString(width), quoted(pad), quoted(text))))
                            .describedAs(text + " right " + width + " " + pad)
                            .isEqualTo(value(quoted(Strings.padRight(text, width, pad))));
                }
            }
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

    /**
     * Every {@code Int} operation this backend writes, against {@code souther-runtime}'s account of
     * it, at the values most likely to disagree — {@code MIN_VALUE}, {@code MAX_VALUE}, and the
     * pairs immediately around a divisor of {@code -1}, where two's-complement arithmetic and
     * checked arithmetic part company. Unlike a regression test written for one known-bad pair, this
     * sweeps every combination the boundary values make, so the next operation that misreads a
     * {@code checked_*}/{@code wrapping_*} choice the way {@code truncatingRemainder} and unary
     * {@code -} once did fails here rather than needing its own pair found by hand first (#23's
     * follow-up: a conformance barrier between {@code AbortSites}/{@code KernelContracts} and this
     * runtime's actual behavior, for the one family — {@code Int} arithmetic — every semantic
     * mismatch found so far but one has come from).
     */
    @Test
    void everyIntOperationAgreesWithSouthersRuntimeAtTheEdgesOfWhatAnIntHolds() {
        Running module = compiled("""
                module edges

                behavior negated : (a: Int) -> Int

                let negated (a) = -a

                behavior remainder : (a: Int, b: Int) -> Int

                let remainder (a, b) = match Int.truncatingRemainder(a, b) with
                    | Int as r -> r
                    | DivisionByZero -> 0

                behavior sum : (a: Int, b: Int) -> Int

                let sum (a, b) = a + b

                behavior difference : (a: Int, b: Int) -> Int

                let difference (a, b) = a - b

                behavior product : (a: Int, b: Int) -> Int

                let product (a, b) = a * b

                behavior halved : (a: Int, b: Int) -> Int

                let halved (a, b) = match Int.truncatingDivide(a, b) with
                    | Int as q -> q
                    | DivisionByZero -> 0
                """);

        long[] boundaries = {
            Long.MIN_VALUE, Long.MIN_VALUE + 1, -2, -1, 0, 1, 2, Long.MAX_VALUE - 1, Long.MAX_VALUE,
        };

        for (long a : boundaries) {
            assertThat(answerOf(module, "edges.negated", array(a)))
                    .describedAs("-(" + a + ")")
                    .isEqualTo(value(Long.toString(-a)));

            for (long b : boundaries) {
                agrees(module, "edges.sum", a, b, () -> IntMath.addExact(a, b));
                agrees(module, "edges.difference", a, b, () -> IntMath.subtractExact(a, b));
                agrees(module, "edges.product", a, b, () -> IntMath.multiplyExact(a, b));

                if (b == 0) {
                    continue;
                }
                assertThat(answerOf(module, "edges.remainder", array(a, b)))
                        .describedAs(a + " truncatingRemainder " + b)
                        .isEqualTo(value(Long.toString(a % b)));
                agrees(module, "edges.halved", a, b, () -> IntMath.divideExact(a, b));
            }
        }
    }

    /**
     * Runs {@code export(a, b)} on the compiled module and requires it to agree with {@code jvm} —
     * the overflow-checked {@code IntMath} operation {@code souther-runtime} answers the same
     * behavior with — on both halves of what "agree" means: raising where and only where the other
     * one does ({@link souther.runtime.ConstraintViolation} against a trap), and, where neither
     * does, the exact same value. Checking only one half would have missed a wrong answer that
     * happens not to trap as easily as it would have missed a trap that should not have happened.
     */
    private static void agrees(Running module, String export, long a, long b,
            java.util.function.LongSupplier jvm) {
        String description = export + "(" + a + ", " + b + ")";
        Long expected;
        try {
            expected = jvm.getAsLong();
        } catch (souther.runtime.ConstraintViolation _) {
            expected = null;
        }
        if (expected == null) {
            // answerOf's own mark is lost with the exception it throws, so this takes one first
            // and resets it itself — the same recipe an aborted call asks any caller to follow.
            int mark = module.call(RuntimeAbi.ALLOC_MARK);
            assertThatThrownBy(() -> answerOf(module, export, array(a, b)))
                    .describedAs(description)
                    .isInstanceOf(com.dylibso.chicory.wasm.ChicoryException.class);
            module.call(RuntimeAbi.ALLOC_RESET, mark);
        } else {
            assertThat(answerOf(module, export, array(a, b)))
                    .describedAs(description)
                    .isEqualTo(value(Long.toString(expected)));
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
    void answersTheCaseAKernelNamesWhereItHasNoValue() {
        Running module = compiled("""
                module counting

                behavior halved : (a: Int, b: Int) -> Int

                let halved (a, b) = match Int.truncatingDivide(a, b) with
                    | Int as q -> q
                    | DivisionByZero -> 0

                behavior read : (s: String) -> Int

                let read (s) = match String.toInt(s) with
                    | Int as n -> n
                    | NotANumber -> -1
                """);

        assertThat(answerOf(module, "counting.halved", "[7,2]")).isEqualTo(value("3"));
        assertThat(answerOf(module, "counting.halved", "[7,0]")).isEqualTo(value("0"));
        for (String text : new String[] {"12", "-12", "+12", "", "x", "1x", "-", "999999999999999999999"}) {
            Object parsed = Strings.toInt(text);
            assertThat(answerOf(module, "counting.read", array(quoted(text))))
                    .describedAs(text)
                    .isEqualTo(value(parsed instanceof Long held ? held.toString() : "-1"));
        }
    }

    /** The operations this backend knows it does not write, so a new kernel is still noticed. */
    private static final java.util.Set<Kernel> NOT_LOWERED = java.util.Set.of(
            Kernel.RATIONAL_FROM_INT, Kernel.RATIONAL_FROM_DECIMAL,
            Kernel.RATIONAL_TO_WHOLE_NUMBER, Kernel.RATIONAL_TO_FINITE_DECIMAL,
            Kernel.RATIONAL_TO_INT, Kernel.RATIONAL_TO_DECIMAL, Kernel.RATIONAL_ADD,
            Kernel.RATIONAL_SUBTRACT, Kernel.RATIONAL_MULTIPLY, Kernel.RATIONAL_DIVIDE,
            Kernel.RATIONAL_COMPARE);

    @Test
    void writesEveryIntrinsicTheLibraryDeclaresAsSomethingTheRuntimeExports() {
        LinkPlan runtime = LinkPlan.reading(WasmCompiler.runtimeModule());

        for (Kernel kernel : Kernel.values()) {
            if (NOT_LOWERED.contains(kernel)) {
                assertThatThrownBy(() -> WasmCompiler.abiNameOf(kernel))
                        .describedAs(kernel + " is known not to be written")
                        .isInstanceOf(NotLowered.class);
                continue;
            }
            String named = WasmCompiler.abiNameOf(kernel);
            assertThat(runtime.layout().export(named))
                    .describedAs(kernel + " is written as " + named)
                    .isPresent();
        }
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

    private static String array(long a) {
        return "[" + a + "]";
    }

    /** A string as JSON writes it — matching the control-character escaping
     *  {@code __souther_json_write_string} (runtime/src/json.rs) does, so a text carrying a control
     *  character other than tab/newline/CR still compares equal rather than differing only in how
     *  the two sides spelled the same code point. */
    private static String quoted(String text) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c <= 0x1f) {
                        out.append("\\u").append("%04x".formatted((int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
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
