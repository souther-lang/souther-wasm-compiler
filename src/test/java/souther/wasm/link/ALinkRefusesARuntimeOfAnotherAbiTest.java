package souther.wasm.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import souther.compiler.program.CheckedProgram;
import souther.wasm.Running;
import souther.wasm.abi.RuntimeAbi;
import souther.wasm.lower.WasmCompiler;

/**
 * A link reads which ABI the runtime it is handed is, and builds on none but its own.
 *
 * <p>What is emitted calls the runtime as this build's ABI says to. Handed a runtime of another,
 * a link that did not ask would write a module that loads and then answers its calls as something
 * else, which is the one way of being wrong nothing downstream can tell from being right.
 */
class ALinkRefusesARuntimeOfAnotherAbiTest {

    /** A runtime's {@code __souther_abi_version}: a body of four bytes, no locals, the number and
     *  its end, as the release build writes it. */
    private static byte[] answering(int version) {
        return new byte[] {0x04, 0x00, 0x41, (byte) version, 0x0b};
    }

    @Test
    void readsTheAbiTheRuntimeItCarriesIs() {
        byte[] runtime = Running.runtimeModule();

        assertThat(RuntimeLayout.of(runtime).abiVersion(runtime)).isEqualTo(RuntimeAbi.VERSION);
    }

    @Test
    void refusesARuntimeThatSaysItIsAnother() {
        byte[] runtime = Running.runtimeModule();
        int at = indexOf(runtime, answering(RuntimeAbi.VERSION));
        assertThat(at).describedAs("the runtime answers its ABI in one place").isNotNegative();
        assertThat(indexOf(runtime, answering(RuntimeAbi.VERSION), at + 1)).isNegative();
        runtime[at + 3] = (byte) (RuntimeAbi.VERSION - 1);

        assertThatThrownBy(() -> WasmCompiler.compile(CheckedProgram.of(List.of("""
                module counting

                behavior doubled : (n: Int) -> Int

                let doubled (n) = n + n
                """)), runtime))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ABI " + (RuntimeAbi.VERSION - 1));
    }

    private static int indexOf(byte[] in, byte[] sought) {
        return indexOf(in, sought, 0);
    }

    private static int indexOf(byte[] in, byte[] sought, int from) {
        for (int i = from; i <= in.length - sought.length; i++) {
            boolean found = true;
            for (int j = 0; j < sought.length && found; j++) {
                found = in[i + j] == sought[j];
            }
            if (found) {
                return i;
            }
        }
        return -1;
    }
}
