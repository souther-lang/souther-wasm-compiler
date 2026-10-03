package souther.wasm.lower;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import net.unit8.notation199x.Normalization;
import net.unit8.notation199x.pattern.PatternMachine;
import net.unit8.notation199x.pattern.PatternParser;
import net.unit8.notation199x.pattern.PatternRead;
import net.unit8.notation199x.pattern.StringPattern;
import org.junit.jupiter.api.Test;
import souther.wasm.Compiled;
import souther.wasm.Running;
import souther.wasm.abi.RuntimeAbi;

/**
 * Whether a string is what a pattern describes, against what the JVM says.
 *
 * <p>The checker settles what a pattern means, and the machine that meaning is run as is
 * 199x-notation's: the JVM backend runs it from an image in a class, and this backend runs it from
 * an image in static memory, read by the library's Rust implementation. So every pattern here is
 * run both ways, and what the JVM side answers is the library's Java implementation running the
 * machine of the same meaning.
 *
 * <p>A pattern the language refuses never reaches a backend, so where the language does not read
 * one of these, the program is required not to compile, and that is all that is asked of it.
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
        "🙂",
        "🙂+",
        "[🙂🙃]+",
        "a🙂b",
        "(?i)Ω",
    };

    private static final String[] SUBJECTS = {
        "", "a", "b", "aa", "aaa", "aaaa", "aaaaa", "ab", "abab", "abc", "color", "colour",
        "coloor", "foobaz", "barbaz", "bazbaz", "x", "y", "zz", "z", "123-4567", "123-456",
        "1234-5678", "2026-09-04", "2026-9-04", "hello_world", "9lives", "_x1", "rhythm",
        "aeiou", "xyz", "+42", "-7", "42", "4 2", "  ", "\t", "a@b.io", "a@b.info", "c",
        "ひらがな", "ひa", "abc123", "a-1", "ABC", "e\u0301",
        "abc", "AbC", "aBc", "A.B", "a.b", "X", "x", "Y", "y", "aC", "Ab",
        "word", "a word", "words", "a b", "a-b", "ab", "a_b", "abc1",
        "🙂", "🙂🙂", "🙃", "a🙂b", "🙂a", "Ω", "ω", "😀",
        "ac", "bc", "abc\n", "\n", "é", "日本",
        "acd", "bcd", "acbcd", "d", "abcd", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaab", "..", ".", "a-c", "-", "192.168.0.1", "1.2.3",
        "255.255.255.255", "0.0.0.0", "1.2.3.4.5",
    };

    @Test
    void recognisesEverythingTheJvmRecognisesAndNothingElse() {
        for (String pattern : PATTERNS) {
            if (!(PatternParser.read(pattern) instanceof PatternRead.Read read)) {
                assertThatThrownBy(() -> compiled(pattern))
                        .describedAs("/" + pattern + "/ is no pattern of the language")
                        .isNotInstanceOf(NotLowered.class);
                continue;
            }
            StringPattern jvm = PatternMachine.of(read.meaning()).pattern();
            Running module = compiled(pattern);
            for (String subject : SUBJECTS) {
                // Text is let in as its canonical form on both sides before anything is asked of it.
                boolean expected = jvm.matches(Normalization.normalize(Normalization.Form.NFC, subject));
                assertThat(answerOf(module, quoted(subject)))
                        .describedAs("/" + pattern + "/ against " + quoted(subject))
                        .isEqualTo("{\"value\":" + expected + "}");
            }
        }
    }

    @Test
    void asksEachPatternOfOneCallAboutItsOwnStrings() {
        // The runtime keeps the pattern it read last, so two patterns asked in turn within one call
        // are each read again rather than one of them answering for the other.
        Running module = Running.linked(Compiled.module(Compiled.program(List.of("""
                module checking

                behavior sorted : (xs: List<String>) -> List<String>

                let sorted (xs) = List.map(x ->
                    if String.matches("[0-9]+", x) then "digits"
                    else if String.matches("[a-z]+", x) then "letters"
                    else "neither", xs)
                """))));

        assertThat(answerOf(module, "checking.sorted", "[\"12\",\"ab\",\"34\",\"A\",\"cd\"]"))
                .isEqualTo("{\"value\":[\"digits\",\"letters\",\"digits\",\"neither\",\"letters\"]}");
    }

    @Test
    void usesThePatternTheCheckerSettledUnderALocalBinding() {
        Running module = Running.linked(Compiled.module(Compiled.program(List.of("""
                module checking

                behavior fits : (s: String) -> Bool

                let fits (s) = {
                    let tail = "[0-9]{4}"
                    String.matches("AB-" ++ tail, s)
                }
                """))));

        assertThat(answerOf(module, "\"AB-1234\"")).isEqualTo("{\"value\":true}");
        assertThat(answerOf(module, "\"AB-123\"")).isEqualTo("{\"value\":false}");
    }

    private static Running compiled(String pattern) {
        return Running.linked(Compiled.module(Compiled.program(List.of("""
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
        return answerOf(module, "checking.fits", argument);
    }

    private static String answerOf(Running module, String export, String argument) {
        String written = "[" + argument + "]";
        int mark = module.call(RuntimeAbi.ALLOC_MARK);
        int address = module.staged(written);
        long[] answer = module.callWithString(
                export, address, written.getBytes(StandardCharsets.UTF_8).length);
        String held = new String(
                module.read((int) answer[0], (int) answer[1]), StandardCharsets.UTF_8);
        module.call(RuntimeAbi.ALLOC_RESET, mark);
        return held;
    }
}
