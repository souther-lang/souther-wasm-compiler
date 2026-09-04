package souther.wasm.lower;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import souther.compiler.program.CheckedProgram;
import souther.wasm.Running;
import souther.wasm.abi.RuntimeAbi;

/**
 * A type declared over another, and a product declared with one member called {@code value}.
 *
 * <p>They are made of the same thing — one field, of the same type, with the same clauses — and
 * they cross differently: the first is written as the type it is a name for is written, the second
 * as an object with that member. Which of the two a declaration is was settled by the syntax it was
 * written in, and this backend was refusing both until the program it reads said which.
 */
class ANameForAValueCrossesAsThatValueTest {

    @Test
    void writesANameForAValueAsTheValueAndAProductAsAnObject() {
        Running module = compiled();

        assertThat(answerOf(module, "naming.named", "\"abc\""))
                .isEqualTo("{\"value\":\"abc\"}");
        assertThat(answerOf(module, "naming.wrapped", "{\"value\":\"abc\"}"))
                .isEqualTo("{\"value\":{\"value\":\"abc\"}}");
    }

    @Test
    void saysSoWhereEachIsHandedWhatTheOtherCrossesAs() {
        Running module = compiled();

        assertThat(answerOf(module, "naming.named", "{\"value\":\"abc\"}"))
                .contains("\"actual\":\"object\"");
        assertThat(answerOf(module, "naming.wrapped", "\"abc\""))
                .contains("\"actual\":\"string\"");
    }

    @Test
    void checksWhatMustHoldOfANameForAValue() {
        Running module = compiled();

        // The clauses a name carries are the ones that would otherwise have gone quiet: nothing
        // about the value that crosses says the type it crossed as had rules of its own.
        assertThat(answerOf(module, "naming.reference", "\"1000-000001\""))
                .isEqualTo("{\"value\":\"1000-000001\"}");
        assertThat(answerOf(module, "naming.reference", "\"nope\""))
                .contains("invariant_violation")
                .contains("\"expected\":\"OrderNo\"");
    }

    @Test
    void saysWhereTheValueIsRatherThanAtAMemberNobodyWrote() {
        Running module = compiled();

        // A name for a value is written as that value, so the position a caller would look at is
        // the one the value stands at — not a member of an object there is no object for.
        assertThat(answerOf(module, "naming.reference", "\"nope\""))
                .contains("\"path\":\"/0\"");
    }

    @Test
    void placesTwoNamesWhereTheValuesTheyNameStand() {
        Running module = compiled();

        // A name settles no order of its own, so a set of them is written in the order the values
        // are, and what was written twice is held once.
        assertThat(answerOf(module, "naming.every", "[\"c\",\"a\",\"b\",\"a\"]"))
                .isEqualTo("{\"value\":[\"a\",\"b\",\"c\"]}");
    }

    @Test
    void keysAMapByANameForAValueAsThatValueKeysOne() {
        Running module = compiled();

        assertThat(answerOf(module, "naming.by", "{\"b\":2,\"a\":1}"))
                .isEqualTo("{\"value\":{\"a\":1,\"b\":2}}");
    }

    @Test
    void readsWhatANameHoldsInABody() {
        Running module = compiled();

        assertThat(answerOf(module, "naming.howLong", "\"abcde\"")).isEqualTo("{\"value\":5}");
    }

    private static Running compiled() {
        return Running.linked(WasmCompiler.compile(CheckedProgram.of(List.of("""
                module naming

                data Identifier = String
                data Wrapper = { value: String }
                data OrderNo = String
                    invariant written = String.matches("[0-9]{4}-[0-9]{6}", value)

                behavior named : (id: Identifier) -> Identifier

                let named (id) = id

                behavior wrapped : (w: Wrapper) -> Wrapper

                let wrapped (w) = w

                behavior reference : (n: OrderNo) -> OrderNo

                let reference (n) = n

                behavior every : (ids: Set<Identifier>) -> Set<Identifier>

                let every (ids) = ids

                behavior by : (m: Map<Identifier, Int>) -> Map<Identifier, Int>

                let by (m) = m

                behavior howLong : (id: Identifier) -> Int

                let howLong (id) = String.length(id.value)
                """))));
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
