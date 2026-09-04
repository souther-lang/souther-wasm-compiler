package souther.wasm.link;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import souther.wasm.Running;
import souther.wasm.emit.Type;

/**
 * What a fragment refuses to be linked as.
 *
 * <p>Both of these are places where an index or an address is handed out before what goes there is
 * settled. A link that went ahead would write zeroes into a module that runs, and the first sign
 * of it would be a call reaching nothing at all.
 */
class AFragmentSaysWhatItWasNotGivenTest {

    @Test
    void refusesToLinkWhileAFunctionItPromisedIsUnwritten() {
        WasmFragment fragment = new WasmFragment(LinkPlan.reading(Running.runtimeModule()));
        fragment.declare(fragment.functionType(List.of(), List.of(Type.I32)));

        assertThatThrownBy(() -> Linker.link(fragment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("declared and not written");
    }

    @Test
    void refusesToLinkWhileBytesItReservedAreUnfilled() {
        WasmFragment fragment = new WasmFragment(LinkPlan.reading(Running.runtimeModule()));
        fragment.reserve(4);

        assertThatThrownBy(() -> Linker.link(fragment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("reserved");
    }
}
