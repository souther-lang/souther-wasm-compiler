package souther.wasm.lower;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import souther.compiler.program.CheckedProgram;
import souther.wasm.Running;
import souther.wasm.abi.RuntimeAbi;

/**
 * {@code trim} and {@code words} against spec §string-whitespace directly, rather than against
 * whatever the host toolchain thinks whitespace is. Every expected value here is written by hand:
 * this is the specification's own contract, checked by running the WASM module, not a comparison
 * against {@code char::is_whitespace} or a JDK method that might itself be wrong (issue #19).
 *
 * <p>{@link AKernelMeansWhatSouthersOwnRuntimeSaysTest} already checks this backend against
 * {@code souther-runtime}; a differential test alone would not have caught issue #19, since both
 * backends independently reached for a byte-level host notion of whitespace and could have
 * disagreed with the specification in the same way without disagreeing with each other.
 */
class AStringWhitespaceAlphabetIsExactlyTwentyFiveCodePointsTest {

    /** The 25 code points spec §string-whitespace enumerates, written out rather than generated,
     *  so this test and the spec table can be read side by side. */
    private static final int[] MEMBERS = {
        0x0009, 0x000A, 0x000B, 0x000C, 0x000D,
        0x0020,
        0x0085,
        0x00A0,
        0x1680,
        0x2000, 0x2001, 0x2002, 0x2003, 0x2004, 0x2005, 0x2006, 0x2007, 0x2008, 0x2009, 0x200A,
        0x2028, 0x2029,
        0x202F,
        0x205F,
        0x3000,
    };

    /** Code points a boundary mistake would most plausibly let through: the edges of every named
     *  range, one step outside each end, plus the "looks like whitespace" characters that a
     *  byte-level or {@code Character#isWhitespace}-derived implementation gets wrong. */
    private static final int[] NON_MEMBERS = {
        0x0008, 0x000E,             // one below/above the TAB..CR run
        0x001C, 0x0007,             // C0 controls a JDK-trim-shaped implementation wrongly crosses
        0x001F, 0x0021,             // one below/above SPACE
        0x0084, 0x0086,             // one below/above NEL
        0x009F, 0x00A1,             // one below/above NBSP
        0x167F, 0x1681,             // one below/above OGHAM SPACE MARK
        0x1FFF, 0x200B,             // one below the 2000..200A run, and ZERO WIDTH SPACE just above it
        0x2027, 0x202A,             // one below LINE SEPARATOR, and one above PARAGRAPH SEPARATOR
        0x202E, 0x2030,             // one below/above NARROW NBSP
        0x205E, 0x2060,             // one below MEDIUM MATHEMATICAL SPACE, and WORD JOINER above it
        0x2FFF, 0x3001,             // one below/above IDEOGRAPHIC SPACE
        0xFEFF,                     // byte-order mark
    };

    private static Running module;

    @ParameterizedTest
    @MethodSource("members")
    void aMemberOfTheSpecsSetIsStrippedByTrimAndSplitByWords(int cp) {
        String ch = new String(Character.toChars(cp));
        assertThat(answerOf(module(), "wording.tidied", array(quoted(ch + "a" + ch))))
                .describedAs("trim U+%04X".formatted(cp))
                .isEqualTo(value(quoted("a")));
        assertThat(answerOf(module(), "wording.spoken", array(quoted("a" + ch + "b"))))
                .describedAs("words U+%04X".formatted(cp))
                .isEqualTo(value(written(List.of("a", "b"))));
    }

    @ParameterizedTest
    @MethodSource("nonMembers")
    void aCodePointOutsideTheSpecsSetIsLeftAloneByBoth(int cp) {
        String ch = new String(Character.toChars(cp));
        String padded = ch + "a" + ch;
        assertThat(answerOf(module(), "wording.tidied", array(quoted(padded))))
                .describedAs("trim U+%04X".formatted(cp))
                .isEqualTo(value(quoted(padded)));
        assertThat(answerOf(module(), "wording.spoken", array(quoted("a" + ch + "b"))))
                .describedAs("words U+%04X".formatted(cp))
                .isEqualTo(value(written(List.of("a" + ch + "b"))));
    }

    @Test
    void wordsOfATrimmedStringIsWordsOfTheOriginal() {
        // WASM.words(WASM.trim(s)) == WASM.words(s) \u2014 both sides of this run inside the same
        // module. Routing either side through souther.runtime.Strings would make this a
        // differential test wearing this class's name, checking the JVM and WASM backends agree
        // rather than checking WASM's own trim and words agree with each other.
        String text = "  the  quick\u3000fox\u00a0 ";
        assertThat(answerOf(module(), "wording.spokenTidied", array(quoted(text))))
                .isEqualTo(answerOf(module(), "wording.spoken", array(quoted(text))));
    }

    private static IntStream members() {
        return IntStream.of(MEMBERS);
    }

    private static IntStream nonMembers() {
        return IntStream.of(NON_MEMBERS);
    }

    private static Running module() {
        if (module == null) {
            module = Running.linked(WasmCompiler.compile(CheckedProgram.of(List.of("""
                    module wording

                    behavior tidied : (s: String) -> String

                    let tidied (s) = String.trim(s)

                    behavior spoken : (s: String) -> List<String>

                    let spoken (s) = String.words(s)

                    behavior spokenTidied : (s: String) -> List<String>

                    let spokenTidied (s) = String.words(String.trim(s))
                    """))));
        }
        return module;
    }

    private static String value(String written) {
        return "{\"value\":" + written + "}";
    }

    private static String array(String... arguments) {
        return "[" + String.join(",", arguments) + "]";
    }

    /** A string as JSON writes it — matching {@code __souther_json_write_string}'s
     *  control-character escaping (runtime/src/json.rs), which this test relies on for every
     *  non-member boundary below U+0020. */
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
        return "[" + String.join(",", texts.stream()
                .map(AStringWhitespaceAlphabetIsExactlyTwentyFiveCodePointsTest::quoted).toList())
                + "]";
    }

    private static String answerOf(Running running, String export, String arguments) {
        int mark = running.call(RuntimeAbi.ALLOC_MARK);
        int address = running.staged(arguments);
        long[] answer = running.callWithString(
                export, address, arguments.getBytes(StandardCharsets.UTF_8).length);
        String written = new String(
                running.read((int) answer[0], (int) answer[1]), StandardCharsets.UTF_8);
        running.call(RuntimeAbi.ALLOC_RESET, mark);
        return written;
    }
}
