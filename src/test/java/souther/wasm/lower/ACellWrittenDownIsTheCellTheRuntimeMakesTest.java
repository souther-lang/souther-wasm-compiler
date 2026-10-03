package souther.wasm.lower;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import souther.wasm.Running;
import souther.wasm.abi.RuntimeAbi;
import souther.wasm.abi.RuntimeAbi.Cell;

/**
 * What this compiler writes into static memory as a cell, and what it reads off a cell without a
 * call, are what the runtime makes and reads.
 *
 * <p>The runtime is asked to make each cell, and what it made is read back byte for byte. A change
 * to how {@code runtime/src/value.rs} lays a cell out fails here rather than in a body that read
 * the wrong word.
 */
class ACellWrittenDownIsTheCellTheRuntimeMakesTest {

    private final Running runtime = Running.bareRuntime();

    @Test
    void writesAnIntAsTheRuntimeDoes() {
        for (long value : new long[] {0, 1, -1, Long.MAX_VALUE, Long.MIN_VALUE, 1L << 40}) {
            int made = (int) runtime.callPacked(RuntimeAbi.INT, value);
            assertThat(runtime.read(made, 16)).isEqualTo(Cells.intCell(value));
        }
    }

    @Test
    void writesABoolAsTheRuntimeDoes() {
        for (boolean value : new boolean[] {true, false}) {
            int made = (int) runtime.callPacked(RuntimeAbi.BOOL, value ? 1 : 0);
            assertThat(runtime.read(made, 12)).isEqualTo(Cells.boolCell(value));
        }
    }

    @Test
    void writesAStringAsTheRuntimeDoes() {
        for (String text : new String[] {"", "a", "héllo", "日本語"}) {
            byte[] utf8 = text.getBytes(StandardCharsets.UTF_8);
            int staged = runtime.staged(text);
            int made = (int) runtime.callPacked(RuntimeAbi.STRING, staged, utf8.length);
            assertThat(runtime.read(made, 8 + utf8.length)).isEqualTo(Cells.stringCell(utf8));
        }
    }

    @Test
    void writesNothingAUnitAndABlockAsTheRuntimeDoes() {
        int none = runtime.call(RuntimeAbi.NONE);
        assertThat(runtime.read(none, 8)).isEqualTo(Cells.noneCell());

        int unit = runtime.call(RuntimeAbi.UNIT, 4321);
        assertThat(runtime.read(unit, 8)).isEqualTo(Cells.unitCell(4321));

        int block = runtime.call(RuntimeAbi.CLOSURE, 7, 0);
        assertThat(runtime.read(block, 12)).isEqualTo(Cells.closureCell(7));
    }

    @Test
    void readsABlockAndAListWhereTheRuntimeKeepsThem() {
        int block = runtime.call(RuntimeAbi.CLOSURE, 7, 1234);
        assertThat(word(block + Cell.SECOND)).isEqualTo(7);
        assertThat(word(block + Cell.PAYLOAD)).isEqualTo(1234);

        int list = runtime.call(RuntimeAbi.LIST, 0, 3);
        runtime.run(RuntimeAbi.LIST_SET, list, 1, 99);
        assertThat(word(list + Cell.PAYLOAD)).isEqualTo(3);
        assertThat(word(list + Cell.PAYLOAD + 4 + 4)).isEqualTo(99);
    }

    private int word(int address) {
        return ByteBuffer.wrap(runtime.read(address, 4)).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }
}
