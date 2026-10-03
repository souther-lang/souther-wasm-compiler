package souther.wasm.lower;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import souther.runtime.Strings;
import souther.wasm.Compiled;
import souther.wasm.Running;
import souther.wasm.abi.RuntimeAbi;

/**
 * A {@code String} is held in NFC, however it arrived and however it was made.
 *
 * <p>Text that crosses in is let in as its canonical form, and an operation whose answer can leave
 * NFC — two strings joined, a case mapped — canonicalizes it. What each answers is asked of
 * {@code souther-runtime}, which is what the JVM backend calls, so the two backends hold the same
 * value for the same text.
 */
class AStringIsHeldInItsCanonicalFormTest {

    /** Each written in a form other than its canonical one, or composing at a seam. */
    private static final String[] TEXTS = {
        "é", "Å", "Å", "á̧", "ḍ̇", "ﬃ", "́", "",
    };

    @Test
    void letsTextInAsItsCanonicalForm() {
        Running module = compiled();

        for (String text : TEXTS) {
            assertThat(answerOf(module, "holding.same", quoted(text)))
                    .describedAs(text)
                    .isEqualTo(value(quoted(Strings.admit(text))));
        }
    }

    @Test
    void canonicalizesWhereTwoStringsMeet() {
        Running module = compiled();

        for (String left : TEXTS) {
            for (String right : TEXTS) {
                String expected = Strings.append(Strings.admit(left), Strings.admit(right));
                assertThat(answerOf(module, "holding.joined", quoted(left) + "," + quoted(right)))
                        .describedAs(left + " ++ " + right)
                        .isEqualTo(value(quoted(expected)));
            }
        }
    }

    @Test
    void measuresTheCanonicalForm() {
        Running module = compiled();

        // Three code points as written, one once it is canonical.
        assertThat(answerOf(module, "holding.sized", quoted("Å"))).isEqualTo(value("1"));
    }

    @Test
    void keysAMapByTheCanonicalForm() {
        Running module = compiled();

        // Two member names that spell one String once it is let in are one key, and the document's
        // last one stands, as two member names spelled the same way would.
        assertThat(answerOf(module, "holding.keys", "{\"é\":1,\"é\":2}"))
                .isEqualTo(value("{" + quoted("é") + ":2}"));
    }

    private static Running compiled() {
        return Running.linked(Compiled.module(Compiled.program(List.of("""
                module holding

                behavior same : (s: String) -> String

                let same (s) = s

                behavior joined : (a: String, b: String) -> String

                let joined (a, b) = a ++ b

                behavior sized : (s: String) -> Int

                let sized (s) = String.length(s)

                behavior keys : (m: Map<String, Int>) -> Map<String, Int>

                let keys (m) = m
                """))));
    }

    private static String value(String written) {
        return "{\"value\":" + written + "}";
    }

    private static String quoted(String text) {
        return "\"" + text + "\"";
    }

    private static String answerOf(Running module, String export, String arguments) {
        String written = "[" + arguments + "]";
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
