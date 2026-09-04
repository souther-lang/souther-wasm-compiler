package souther.wasm.lower;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import souther.compiler.program.CheckedProgram;
import souther.wasm.Running;
import souther.wasm.abi.RuntimeAbi;

/**
 * Whether a string is what a pattern describes, against what the JVM says.
 *
 * <p>The pattern is a literal, so it is read where it is written and what runs is the machine it
 * was read into. What that machine must agree with is {@link Pattern#matches}, because that is what
 * the other backend calls, so every pattern here is run both ways.
 */
class APatternIsRecognisedWhereTheJvmRecognisesItTest {

    private static final String[] PATTERNS = {
        "[0-9]{3}-[0-9]{4}",
        "\\d{4}-\\d{2}-\\d{2}",
        "[A-Za-z_][A-Za-z0-9_]*",
        "a*",
        "a+b",
        "colou?r",
        "(ab)+",
        "(?:foo|bar)baz",
        "x|y|zz",
        "[^aeiou]+",
        "a{2,4}",
        "a{2,}",
        ".",
        "..*",
        "\\w+@\\w+\\.\\w{2,3}",
        "\\s*",
        "[-+]?[0-9]+",
        "(a|b)*c",
        "[a-c]{0,2}",
        "((a|b)c)+d",
        "(a*)*b",
        "(a|ab)(c|bcd)",
        "a{0}",
        "[a-]",
        "(|a)b",
        "\\.\\.",
        "[0-9]{1,3}(\\.[0-9]{1,3}){3}",
        "[\\d]+",
        "[\\d\\s]+",
        "[\\D]+",
        "[^\\d]+",
        "[^\\D]+",
        "[\\w-]+",
        "[a-z\\d_]{2,}",
        "[\\s\\S]*",
        "[^\\w\\s]",
        "^[0-9]{3}-[0-9]{4}$",
        "^a",
        "a$",
        "^$",
        "a\\$",
        "^\\$a$",
        "\\p{Alpha}+",
        "\\p{Digit}{3}",
        "\\p{IsHiragana}+",
        "\\p{L}+",
        "\\P{Alpha}+",
        "[\\p{Alpha}0-9]+",
        "\\p{Alnum}-\\p{Alnum}",
        "(?i)abc",
        "(?i)ABC",
        "(?i)[a-c]+",
        "(?i)a\\.b",
        "(?i)\\p{Alpha}+",
        "(?i)[^a-c]+",
        "(?i)x|Y",
        "(?i)ひらがな",
        "\\bword\\b",
        "\\ba",
        "a\\b",
        "\\b",
        "a\\bb",
        "a\\b b",
        "\\w+\\b",
        "[a-z]+\\b[0-9]*",
    };

    private static final String[] SUBJECTS = {
        "", "a", "b", "aa", "aaa", "aaaa", "aaaaa", "ab", "abab", "abc", "color", "colour",
        "coloor", "foobaz", "barbaz", "bazbaz", "x", "y", "zz", "z", "123-4567", "123-456",
        "1234-5678", "2026-09-04", "2026-9-04", "hello_world", "9lives", "_x1", "rhythm",
        "aeiou", "xyz", "+42", "-7", "42", "4 2", "  ", "\t", "a@b.io", "a@b.info", "c",
        "ひらがな", "ひa", "abc123", "a-1", "ABC", "e\u0301",
        "abc", "AbC", "aBc", "A.B", "a.b", "X", "x", "Y", "y", "aC", "Ab",
        "word", "a word", "words", "a b", "a-b", "ab", "a_b", "abc1",
        "ac", "bc", "abc\n", "\n", "é", "日本",
        "acd", "bcd", "acbcd", "d", "abcd", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaab", "..", ".", "a-c", "-", "192.168.0.1", "1.2.3",
        "255.255.255.255", "0.0.0.0", "1.2.3.4.5",
    };

    @Test
    void recognisesEverythingTheJvmRecognisesAndNothingElse() {
        for (String pattern : PATTERNS) {
            Running module = compiled(pattern);
            for (String subject : SUBJECTS) {
                boolean expected = Pattern.matches(pattern, subject);
                assertThat(answerOf(module, quoted(subject)))
                        .describedAs("/" + pattern + "/ against " + quoted(subject))
                        .isEqualTo("{\"value\":" + expected + "}");
            }
        }
    }

    @Test
    void refusesAPatternItWouldHaveHadToGuessAt() {
        // Every one of these is a pattern the language takes and this does not read. A pattern the
        // language itself refuses — a property nobody has heard of — never arrives, so it is not
        // among them: what would be tested is the language's answer and not this one.
        for (String pattern : new String[] {
            "(a)\\1", "(?=a)b", "a*?", "a*+", "(?<name>a)", "a^b", "a$b", "a(?i)b", "(a)(?i)b",
        }) {
            assertThatThrownBy(() -> compiled(pattern))
                    .describedAs(pattern)
                    .isInstanceOf(NotLowered.class);
        }
    }

    private static Running compiled(String pattern) {
        return Running.linked(WasmCompiler.compile(CheckedProgram.of(List.of("""
                module checking

                behavior fits : (s: String) -> Bool

                let fits (s) = String.matches("%s", s)
                """.formatted(pattern.replace("\\", "\\\\"))))));
    }

    private static String quoted(String subject) {
        return "\"" + subject.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\t", "\\t") + "\"";
    }

    private static String answerOf(Running module, String argument) {
        String written = "[" + argument + "]";
        int mark = module.call(RuntimeAbi.ALLOC_MARK);
        int address = module.staged(written);
        long[] answer = module.callWithString(
                "checking.fits", address, written.getBytes(StandardCharsets.UTF_8).length);
        String held = new String(
                module.read((int) answer[0], (int) answer[1]), StandardCharsets.UTF_8);
        module.call(RuntimeAbi.ALLOC_RESET, mark);
        return held;
    }
}
