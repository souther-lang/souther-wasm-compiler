package souther.wasm.lower;

import static org.assertj.core.api.Assertions.assertThat;

import com.dylibso.chicory.wasm.ChicoryException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import souther.compiler.program.CheckedProgram;
import souther.wasm.Running;
import souther.wasm.abi.FailureCause;
import souther.wasm.abi.FailureRecord;
import souther.wasm.abi.RuntimeAbi;
import souther.wasm.abi.WasmFault;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * A value of a type a module publishes, read on its own and not as a behavior's argument.
 *
 * <p>A form checks one field as it is typed, before there is a whole call to make, and what it
 * checks it against is the type's own rules. Those are the module's, so the module reads the value:
 * the same reading a behavior's argument gets, rules and all, answered the way a behavior's export
 * answers. Which types a caller may ask for, and under which number, the module says in
 * {@code souther:surface}.
 */
class ACallerReadsAValueOfAPublishedTypeOnItsOwnTest {

    /** A product code no behavior takes or answers, so nothing but being published reaches it. */
    private static final String CODES = """
            module codes exposing ( Sku, Line, total )

            data Sku = String
                invariant written = String.matches("[A-Z]{3}-[0-9]{4}", value)

            data Line = { quantity: Int }
                invariant atLeastOne = quantity >= 1

            data Draft = { note: String }

            behavior total : (n: Int) -> Int

            let total (n) = n + 1
            """;

    @Test
    void aWellWrittenValueIsAnsweredAsTheTypeWritesIt() {
        Running module = compiled();

        assertThat(decoded(module, numberOf(module, "Sku"), "\"ABC-1234\""))
                .isEqualTo("{\"value\":\"ABC-1234\"}");
        assertThat(decoded(module, numberOf(module, "Line"), "{\"quantity\":2}"))
                .isEqualTo("{\"value\":{\"quantity\":2}}");
    }

    /** The type's rules run, though no behavior ever reads a value of it. */
    @Test
    void aValueBreakingTheTypesRuleIsAnsweredWithTheIssue() {
        Running module = compiled();

        assertThat(decoded(module, numberOf(module, "Sku"), "\"nope\""))
                .startsWith("{\"issues\":[")
                .contains("\"path\":\"\"")
                .contains("\"code\":\"invariant_violation\"");
        assertThat(decoded(module, numberOf(module, "Line"), "{\"quantity\":0}"))
                .contains("\"code\":\"invariant_violation\"");
        assertThat(decoded(module, numberOf(module, "Line"), "{\"quantity\":\"two\"}"))
                .contains("\"path\":\"/quantity\"", "\"code\":\"type_mismatch\"");
    }

    /** What the module keeps is no type a caller may read a value of. */
    @Test
    void aKeptTypeHasNoNumber() {
        JsonNode draft = declaration(compiled(), "Draft");

        assertThat(draft.get("published").asBoolean()).isFalse();
        assertThat(draft.has("decode")).isFalse();
    }

    /** A number the module gives no type under is the caller misusing the module, and ends it. */
    @Test
    void aNumberNamingNoTypeEndsTheCall() {
        Running module = compiled();

        for (int number : List.of(2, -1)) {
            int snapshot = module.call(RuntimeAbi.FAILURE_GENERATION);
            int mark = module.call(RuntimeAbi.ALLOC_MARK);
            try {
                decoded(module, number, "\"ABC-1234\"");
                throw new AssertionError(number + " was read as a type");
            } catch (ChicoryException trapped) {
                FailureRecord record = module.failureRecord();
                module.call(RuntimeAbi.ALLOC_RESET, mark);
                assertThat(record.describesTrapAfter(snapshot)).isTrue();
                assertThat(record.cause())
                        .contains(new FailureCause.Wasm(WasmFault.NO_SUCH_TYPE));
                assertThat(record.aux0()).isEqualTo(number);
                assertThat(record.aux1()).isEqualTo(2);
            }
        }
    }

    private static Running compiled() {
        return Running.linked(WasmCompiler.compile(CheckedProgram.of(List.of(CODES))));
    }

    private static int numberOf(Running module, String name) {
        return declaration(module, name).get("decode").asInt();
    }

    private static JsonNode declaration(Running module, String name) {
        JsonNode surface = new ObjectMapper().readTree(module.customSection("souther:surface"));
        for (JsonNode each : surface.get("declarations")) {
            if (each.get("name").asString().equals(name)) {
                return each;
            }
        }
        throw new AssertionError("no declaration " + name + " in " + surface);
    }

    private static String decoded(Running module, int number, String document) {
        byte[] utf8 = document.getBytes(StandardCharsets.UTF_8);
        int address = module.staged(document);
        long[] answer = module.callWith(WasmCompiler.DECODE, number, address, utf8.length);
        return new String(module.read((int) answer[0], (int) answer[1]), StandardCharsets.UTF_8);
    }
}
