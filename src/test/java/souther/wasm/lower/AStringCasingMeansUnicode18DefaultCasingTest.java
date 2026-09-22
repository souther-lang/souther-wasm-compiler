package souther.wasm.lower;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import souther.compiler.program.CheckedProgram;
import souther.wasm.Running;
import souther.wasm.abi.RuntimeAbi;

/**
 * {@code String.lowercase}/{@code String.uppercase} against Unicode 18.0.0's default case
 * conversion (untailored) directly, rather than against whatever the host toolchain's own casing
 * happens to answer (issue #21, mirroring {@code souther-runtime}'s
 * {@code ACaseConversionIsUnicode18DefaultUntailoredTest} for ADR-0119). Every expected value here
 * is written by hand against the Unicode contract, not derived from Java's own
 * {@code String#toLowerCase()}/{@code toUpperCase()}.
 *
 * <p>{@link AKernelMeansWhatSouthersOwnRuntimeSaysTest} already checks this backend against
 * {@code souther-runtime}'s own casing; a differential test alone would not catch the two
 * backends independently reaching for the same wrong host notion of casing without disagreeing
 * with each other. The two suites are deliberately worded the same way the whitespace pair
 * ({@link AStringWhitespaceAlphabetIsExactlyTwentyFiveCodePointsTest} and
 * {@code AKernelMeansWhatSouthersOwnRuntimeSaysTest}) already are.
 */
class AStringCasingMeansUnicode18DefaultCasingTest {

    private static Running module;

    @Test
    void uppercaseExpandsGermanSharpSToTwoLetters() {
        assertThat(answerOf(module(), "wording.loud", array(quoted("straße"))))
                .isEqualTo(value(quoted("STRASSE")));
    }

    @Test
    void lowercaseExpandsTurkishCapitalIWithDotToTwoCodePoints() {
        // Full mapping, unconditional — not the Turkish-locale-tailored single "i" SpecialCasing.txt
        // also lists, which this untailored contract deliberately does not carry.
        assertThat(answerOf(module(), "wording.quiet", array(quoted("İ"))))
                .isEqualTo(value(quoted("i̇")));
    }

    @Test
    void lowercaseGreekCapitalSigmaIsContextSensitive() {
        assertThat(answerOf(module(), "wording.quiet", array(quoted("ΟΣ"))))
                .describedAs("sigma ending a cased run takes the final form")
                .isEqualTo(value(quoted("ος")));
        assertThat(answerOf(module(), "wording.quiet", array(quoted("ΟΣΑ"))))
                .describedAs("sigma followed by another cased letter does not")
                .isEqualTo(value(quoted("οσα")));
        assertThat(answerOf(module(), "wording.quiet", array(quoted("Σ"))))
                .describedAs("sigma with nothing cased before it is not final either")
                .isEqualTo(value(quoted("σ")));
    }

    @Test
    void finalSigmaSkipsCaseIgnorableCodePointsAfterTheSigma() {
        // APOSTROPHE (U+0027) is Case_Ignorable: skipped, sigma is still at the end of a cased run.
        assertThat(answerOf(module(), "wording.quiet", array(quoted("ΟΣ'"))))
                .isEqualTo(value(quoted("ος'")));
    }

    @Test
    void finalSigmaSkipsCaseIgnorableCodePointsBeforeTheSigma() {
        // The apostrophe sits between the cased letter and the sigma; skipped the same way.
        assertThat(answerOf(module(), "wording.quiet", array(quoted("Ο'Σ"))))
                .isEqualTo(value(quoted("ο'ς")));
    }

    @Test
    void aCasedLetterAcrossAnIgnorableAfterTheSigmaStillDeniesTheFinalForm() {
        // Skipping the apostrophe still reaches a Cased letter, so this is not the end of the run.
        assertThat(answerOf(module(), "wording.quiet", array(quoted("ΟΣ'Α"))))
                .isEqualTo(value(quoted("οσ'α")));
    }

    @Test
    void aCodePointThatIsBothCasedAndCaseIgnorableDoesNotSatisfyPrecededByCased() {
        // U+0345 (COMBINING GREEK YPOGEGRAMMENI) is both Cased and Case_Ignorable. Unicode's
        // Final_Sigma "Before C" pattern is possessive over Case_Ignorable* — it consumes U+0345
        // as the skipped run before ever asking whether what it skipped was Cased — so a sigma
        // right after it is not preceded by a cased letter, and takes the non-final form.
        assertThat(answerOf(module(), "wording.quiet", array(quoted("ͅΣ"))))
                .isEqualTo(value(quoted("ͅσ")));
    }

    @Test
    void uppercaseDoesNotApplyTurkishDotlessITailoring() {
        assertThat(answerOf(module(), "wording.loud", array(quoted("i"))))
                .isEqualTo(value(quoted("I")));
        assertThat(answerOf(module(), "wording.quiet", array(quoted("I"))))
                .isEqualTo(value(quoted("i")));
    }

    @Test
    void unicode18VersionSentinelCasePair() {
        // U+AB4B (LATIN SMALL LETTER SCRIPT R) to U+AB6C is a genuine Unicode 18.0.0 case pair —
        // the same sentinel souther-runtime's ACaseConversionIsUnicode18DefaultUntailoredTest uses,
        // so the two backends' commitment to the same Unicode version has one explanation, not two.
        String scriptR = new String(Character.toChars(0xAB4B));
        String capitalScriptR = new String(Character.toChars(0xAB6C));

        assertThat(answerOf(module(), "wording.loud", array(quoted(scriptR))))
                .isEqualTo(value(quoted(capitalScriptR)));
    }

    private static Running module() {
        if (module == null) {
            module = Running.linked(WasmCompiler.compile(CheckedProgram.of(List.of("""
                    module wording

                    behavior quiet : (s: String) -> String

                    let quiet (s) = String.lowercase(s)

                    behavior loud : (s: String) -> String

                    let loud (s) = String.uppercase(s)
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
