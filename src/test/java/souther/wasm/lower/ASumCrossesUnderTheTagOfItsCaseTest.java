package souther.wasm.lower;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import souther.compiler.program.CheckedProgram;
import souther.wasm.Running;

/**
 * A data written as cases, crossing the boundary under the tag of the case a value is.
 *
 * <p>The discriminator is {@code type} and the tag is the case's own name, which is the derived
 * mapping the JVM backend's codec uses. Which tag to write is decided by the type of the place, so
 * the same value written where its case was declared carries none.
 */
class ASumCrossesUnderTheTagOfItsCaseTest {

    private static final String SHIPPING = """
            module shipping

            data Standard = { days: Int }

            data Express = { fee: Int }

            data Method = Standard | Express

            behavior chosen : (m: Method) -> Method

            let chosen (m) = m

            behavior asStandard : (s: Standard) -> Standard

            let asStandard (s) = s
            """;

    @Test
    void readsACaseByTheTagTheDocumentNamesItWith() {
        Running module = compiled(SHIPPING);

        assertThat(answerOf(module, "shipping.chosen", "[{\"type\": \"Standard\", \"days\": 3}]"))
                .isEqualTo("{\"value\":{\"type\":\"Standard\",\"days\":3}}");
        assertThat(answerOf(module, "shipping.chosen", "[{\"type\": \"Express\", \"fee\": 500}]"))
                .isEqualTo("{\"value\":{\"type\":\"Express\",\"fee\":500}}");
    }

    @Test
    void writesNoTagWhereThePlaceIsTheCaseAndNotTheSum() {
        Running module = compiled(SHIPPING);

        assertThat(answerOf(module, "shipping.asStandard", "[{\"days\": 3}]"))
                .isEqualTo("{\"value\":{\"days\":3}}");
    }

    @Test
    void saysSoWhereTheTagNamesNoCaseTheDeclarationOffers() {
        Running module = compiled(SHIPPING);

        assertThat(answerOf(module, "shipping.chosen", "[{\"type\": \"Overnight\"}]")).isEqualTo(
                "{\"issues\":[{\"path\":\"/0/type\",\"code\":\"not_allowed\","
                        + "\"meta\":{\"actual\":\"Overnight\",\"expected\":\"a case\"}}]}");
    }

    @Test
    void saysSoWhereNothingNamesTheCaseAtAll() {
        Running module = compiled(SHIPPING);

        assertThat(answerOf(module, "shipping.chosen", "[{\"days\": 3}]"))
                .contains("\"path\":\"/0/type\"", "\"code\":\"missing_field\"");
    }

    @Test
    void readsTheCasesFieldsAfterTheTagHasChosenIt() {
        Running module = compiled(SHIPPING);

        assertThat(answerOf(module, "shipping.chosen", "[{\"type\": \"Standard\", \"days\": \"three\"}]"))
                .contains("\"path\":\"/0/days\"", "\"code\":\"type_mismatch\"", "\"expected\":\"Int\"");
    }

    @Test
    void carriesACaseThatHasNoFieldsAsTheTagAlone() {
        Running module = compiled("""
                module lighting

                data Red

                data Green

                data Signal = Red | Green

                behavior shown : (s: Signal) -> Signal

                let shown (s) = s
                """);

        assertThat(answerOf(module, "lighting.shown", "[{\"type\": \"Green\"}]"))
                .isEqualTo("{\"value\":{\"type\":\"Green\"}}");
    }

    @Test
    void namesALeafWhereACaseWasItselfWrittenAsCases() {
        Running module = compiled("""
                module routing

                data First = { n: Int }

                data Second = { n: Int }

                data Third = { n: Int }

                data Near = First | Second

                data Reach = Near | Third

                behavior chosen : (r: Reach) -> Reach

                let chosen (r) = r
                """);

        assertThat(answerOf(module, "routing.chosen", "[{\"type\": \"Second\", \"n\": 1}]"))
                .isEqualTo("{\"value\":{\"type\":\"Second\",\"n\":1}}");
    }

    private static Running compiled(String... sources) {
        return Running.linked(WasmCompiler.compile(CheckedProgram.of(List.of(sources))));
    }

    private static String answerOf(Running module, String export, String arguments) {
        int address = module.staged(arguments);
        long[] answer = module.callWithString(
                export, address, arguments.getBytes(StandardCharsets.UTF_8).length);
        return new String(module.read((int) answer[0], (int) answer[1]), StandardCharsets.UTF_8);
    }
}
