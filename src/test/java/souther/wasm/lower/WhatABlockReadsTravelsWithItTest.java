package souther.wasm.lower;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import souther.compiler.program.CheckedProgram;
import souther.wasm.Running;
import souther.wasm.abi.RuntimeAbi;

/**
 * What a block reads from around it arrives inside it, in the slot it was put in.
 *
 * <p>Which bindings a block reaches is the compiler's answer, tested with it. What is left here is
 * how this backend carries them: the block's creation and its body index the same ordered list, so
 * a pair of captures swapped, or a capture that is not an Int, changes the answer.
 */
class WhatABlockReadsTravelsWithItTest {

    @Test
    void carriesAReadUnderneathATuple() {
        Running module = compiled();

        assertThat(answerOf(module, "carrying.paired", "[1,2],10"))
                .isEqualTo("{\"value\":[11,12]}");
    }

    @Test
    void carriesAReadUnderneathATupleTakenApart() {
        Running module = compiled();

        assertThat(answerOf(module, "carrying.takenApart", "[1,2],10"))
                .isEqualTo("{\"value\":[11,12]}");
    }

    @Test
    void carriesAReadUnderneathAnAttemptedConstruction() {
        Running module = compiled();

        assertThat(answerOf(module, "carrying.attempted", "[1,2],10"))
                .isEqualTo("{\"value\":[11,12]}");
        assertThat(answerOf(module, "carrying.attempted", "[1,2],-100"))
                .isEqualTo("{\"value\":[0,0]}");
    }

    @Test
    void carriesAReadThroughATupleBoundOutsideAndTakenApartInside() {
        Running module = compiled();

        assertThat(answerOf(module, "carrying.fromOutside", "[1,2],10"))
                .isEqualTo("{\"value\":[11,12]}");
    }

    @Test
    void carriesAReadBoundByALetAroundTheBlock() {
        Running module = compiled();

        assertThat(answerOf(module, "carrying.throughALet", "[1,2],10"))
                .isEqualTo("{\"value\":[11,12]}");
    }

    @Test
    void keepsTwoCapturesApart() {
        Running module = compiled();

        assertThat(answerOf(module, "carrying.twoApart", "[1,2],10,3"))
                .isEqualTo("{\"value\":[7,17]}");
    }

    @Test
    void carriesCapturesOfDifferentTypes() {
        Running module = compiled();

        assertThat(answerOf(module, "carrying.mixed", "[1,2],\"n\",3"))
                .isEqualTo("{\"value\":[\"n1:3\",\"n2:3\"]}");
    }

    private static Running compiled() {
        return Running.linked(WasmCompiler.compile(CheckedProgram.of(List.of("""
                module carrying

                data Positive = { n: Int }
                    invariant kept = n > 0

                behavior paired : (xs: List<Int>, by: Int) -> List<Int>

                let paired (xs, by) = List.map(x -> {
                    let both = (x, by)
                    x + by
                }, xs)

                behavior takenApart : (xs: List<Int>, by: Int) -> List<Int>

                let takenApart (xs, by) = List.map(x -> {
                    let (first, second) = (x, by)
                    first + second
                }, xs)

                behavior attempted : (xs: List<Int>, by: Int) -> List<Int>
                    constructs Positive

                let attempted (xs, by) = List.map(x -> if Positive { n = by } as held
                    then x + held.n else 0, xs)

                behavior fromOutside : (xs: List<Int>, by: Int) -> List<Int>

                let fromOutside (xs, by) = {
                    let pair = (by, 0)
                    List.map(x -> {
                        let (raised, _) = pair
                        x + raised
                    }, xs)
                }

                behavior throughALet : (xs: List<Int>, by: Int) -> List<Int>

                let throughALet (xs, by) = {
                    let raised = by
                    List.map(x -> (x + raised, x) |> tupleFirst, xs)
                }

                behavior twoApart : (xs: List<Int>, by: Int, less: Int) -> List<Int>

                let twoApart (xs, by, less) = List.map(x -> x * by - less, xs)

                behavior mixed : (xs: List<Int>, tag: String, n: Int) -> List<String>

                let mixed (xs, tag, n) = List.map(x -> String.concat([tag, String.fromInt(x), ":", String.fromInt(n)]), xs)

                let tupleFirst (pair: (Int, Int)): Int = {
                    let (first, _) = pair
                    first
                }
                """))));
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
