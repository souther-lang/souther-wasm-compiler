package souther.wasm.lower;

import static org.assertj.core.api.Assertions.assertThat;

import com.dylibso.chicory.wasm.ChicoryException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import souther.wasm.Compiled;
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
                .contains("\"code\":\"invalid_format\"");
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

    /**
     * A clause the checker says its constraints are the whole of cannot be broken by a value meeting
     * every one of them: the JVM never asks such a clause, only its constraints. Where this backend
     * finds one broken all the same, it has evaluated the two apart, and ends the call rather than
     * report a rule no other backend would.
     */
    @Test
    void aClauseItsConstraintsAreTheWholeOfIsNeverReportedAsTheRule() {
        Running module = Running.linked(Compiled.module(Compiled.program(List.of("""
                module codes exposing ( Code )

                data Code = String
                    invariant partly = String.length(value) >= 3 && value /= "abcd"
                """))));
        int number = numberOf(module, "Code");
        // Only part of the clause is a constraint, so the table says it is not the whole.
        int complete = clauseOf(module, "partly") + 16;
        assertThat(word(module, complete)).isZero();
        assertThat(decoded(module, number, "\"abcd\""))
                .contains("\"code\":\"invariant_violation\"", "\"clause\":\"partly\"");

        // Said to be the whole, the same clause broken by a value meeting its constraint is this
        // backend at odds with the checker.
        module.write(complete, new byte[] {1, 0, 0, 0});
        int snapshot = module.call(RuntimeAbi.FAILURE_GENERATION);
        try {
            decoded(module, number, "\"abcd\"");
            throw new AssertionError("a clause said to be its constraints was reported as the rule");
        } catch (ChicoryException trapped) {
            FailureRecord record = module.failureRecord();
            assertThat(record.describesTrapAfter(snapshot)).isTrue();
            assertThat(record.cause())
                    .contains(new FailureCause.Wasm(WasmFault.BACKEND_INVARIANT_BROKEN));
            assertThat(record.aux0()).isZero();
        }
    }

    /** Where the clause table's entry for the clause named {@code name} is, in memory. */
    private static int clauseOf(Running module, String name) {
        byte[] memory = module.read(0, module.call(RuntimeAbi.ALLOC_MARK));
        byte[] named = name.getBytes(StandardCharsets.UTF_8);
        for (int at = 0; at + named.length <= memory.length; at++) {
            if (Arrays.equals(memory, at, at + named.length, named, 0, named.length)) {
                for (int entry = 0; entry + 8 <= memory.length; entry += 4) {
                    if (word(memory, entry) == at && word(memory, entry + 4) == named.length) {
                        return entry;
                    }
                }
            }
        }
        throw new AssertionError("no clause " + name + " in the module's memory");
    }

    private static int word(Running module, int at) {
        return word(module.read(at, 4), 0);
    }

    private static int word(byte[] bytes, int at) {
        return ByteBuffer.wrap(bytes, at, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    private static Running compiled() {
        return Running.linked(Compiled.module(Compiled.program(List.of(CODES))));
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
