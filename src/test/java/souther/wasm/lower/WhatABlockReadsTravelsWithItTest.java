package souther.wasm.lower;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import souther.compiler.program.CheckedProgram;
import souther.wasm.Running;
import souther.wasm.abi.RuntimeAbi;

/**
 * What a block reads that something around it bound.
 *
 * <p>A block written where a value goes leaves the body it was written in, so every read whose
 * binding it does not make itself has to travel with it. Which reads those are is found by walking
 * the block — and a walk that treats an expression it does not know as a leaf leaves one behind
 * quietly: the block is written, the read lands where nothing bound it, and what says so is the
 * emitter meeting a binding nothing put anywhere.
 *
 * <p>So each of these puts a read inside a block underneath an expression that holds other
 * expressions, and none of them is unusual — a tuple was the one that was missed. What stops the
 * next one being missed is not this list, which cannot know what will be added: it is that the walk
 * refuses an expression it does not name rather than treating it as a leaf. Taking an arm out of
 * the walk is what these run against, and the answer is the refusal.
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
