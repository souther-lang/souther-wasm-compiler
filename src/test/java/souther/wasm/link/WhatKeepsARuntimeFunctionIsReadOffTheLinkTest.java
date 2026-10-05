package souther.wasm.link;

import static org.assertj.core.api.Assertions.assertThat;

import com.dylibso.chicory.wasm.Parser;
import com.dylibso.chicory.wasm.WasmModule;
import com.dylibso.chicory.wasm.types.ExternalType;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import souther.compiler.program.CheckedProgram;
import souther.wasm.Compiled;
import souther.wasm.Running;
import souther.wasm.abi.RuntimeAbi;
import souther.wasm.emit.WasmTreeShaker;
import souther.wasm.emit.WasmTreeShaker.Reach;
import souther.wasm.emit.WasmTreeShaker.Reason;

/**
 * What keeps a runtime function in a linked module is read off the link before the shake: the
 * root that keeps it, or the body that first names it on a shortest chain from one.
 *
 * <p>Before the shake every runtime function is at the index the runtime gave it, so what keeps
 * one is said of the runtime's own function. This is what a measurement of a module's size reads
 * to say which call carries which part of the runtime in.
 */
class WhatKeepsARuntimeFunctionIsReadOffTheLinkTest {

    private static final CheckedProgram FEE = Compiled.program(List.of("""
            module a

            behavior fee : (total: Int) -> Int

            let fee (total) = if total >= 5000 then 0 else 500
            """));

    private static final LinkPlan RUNTIME = LinkPlan.reading(Running.runtimeModule());

    @Test
    void keepsWhatTheShakeKeeps() {
        long reached = Arrays.stream(WasmTreeShaker.reached(Compiled.unshaken(FEE)))
                .filter(Objects::nonNull).count();

        assertThat(reached).isEqualTo(Parser.parse(Compiled.module(FEE)).functionSection().functionCount()
                + Parser.parse(Compiled.module(FEE)).importSection().count(ExternalType.FUNCTION));
    }

    @Test
    void saysWhichBodyFirstCallsAFunction() {
        byte[] unshaken = Compiled.unshaken(FEE);
        Reach read = WasmTreeShaker.reached(unshaken)[RUNTIME.functionIndexOf(RuntimeAbi.READ)];

        assertThat(read.reason()).isEqualTo(Reason.CALL);
        assertThat(read.predecessor()).isGreaterThanOrEqualTo(RUNTIME.firstGeneratedFunctionIndex());
    }

    @Test
    void saysARootIsOne() {
        byte[] unshaken = Compiled.unshaken(FEE);
        Reach[] reached = WasmTreeShaker.reached(unshaken);
        WasmModule module = Parser.parse(unshaken);

        assertThat(reached[exported(module, "a.fee")]).isEqualTo(new Reach(Reason.EXPORT, -1));
        assertThat(reached[(int) module.startSection().orElseThrow().startIndex()])
                .isEqualTo(new Reach(Reason.START, -1));
    }

    @Test
    void leavesOutWhatNothingReaches() {
        assertThat(WasmTreeShaker.reached(Compiled.unshaken(FEE))[RUNTIME.functionIndexOf(RuntimeAbi.Kernels.LIST_SORT)])
                .isNull();
    }

    private static int exported(WasmModule module, String name) {
        for (int i = 0; i < module.exportSection().exportCount(); i++) {
            if (module.exportSection().getExport(i).name().equals(name)) {
                return (int) module.exportSection().getExport(i).index();
            }
        }
        throw new AssertionError("no export " + name);
    }
}
