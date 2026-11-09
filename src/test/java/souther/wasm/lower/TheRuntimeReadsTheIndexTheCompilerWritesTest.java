package souther.wasm.lower;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import souther.wasm.Running;
import souther.wasm.abi.RuntimeAbi;

/**
 * The hash is part of the ABI: the compiler lays a table out by it and the runtime probes the table
 * by it, from two implementations. Here the compiler's table is read by the runtime itself, over
 * leaves enough that slots collide.
 */
class TheRuntimeReadsTheIndexTheCompilerWritesTest {

    private static final int UNIT = 3;

    @Test
    void theRuntimeFindsEachLeafTheCompilerIndexedAndNoOtherOne() {
        Running runtime = Running.bareRuntime();
        int leaves = 100;
        int region = runtime.call("__ronto_alloc", 16 * (2 * leaves));
        Map<Integer, Integer> places = new LinkedHashMap<>();
        for (int i = 0; i < 2 * leaves; i++) {
            ByteBuffer descriptor = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
            descriptor.putInt(UNIT).putInt(0).putInt(0).putInt(0);
            runtime.write(region + 16 * i, descriptor.array());
            if (i % 2 == 0) {
                places.put(region + 16 * i, i / 2);
            }
        }
        byte[] index = CaseIndex.of(places);
        int table = runtime.call("__ronto_alloc", index.length);
        runtime.write(table, index);

        assertThat(collisions(index)).as("slots a leaf is not at the one it hashes to").isPositive();

        for (int i = 0; i < 2 * leaves; i++) {
            int cell = runtime.call(RuntimeAbi.UNIT, region + 16 * i);
            int found = runtime.call(RuntimeAbi.CLASSIFY, cell, table);
            // Every other leaf is indexed; the ones between are leaves the table was not made of.
            assertThat(found).as("leaf %d", i).isEqualTo(i % 2 == 0 ? i / 2 : -1);
        }
        // A cell holding no descriptor was made as nothing, and is not the first leaf indexed.
        assertThat(runtime.call(RuntimeAbi.CLASSIFY, runtime.call(RuntimeAbi.UNIT, 0), table)).isEqualTo(-1);
    }

    /** How many leaves of a table are not in the slot they hash to, so that the table is read past one. */
    private static int collisions(byte[] index) {
        ByteBuffer table = ByteBuffer.wrap(index).order(ByteOrder.LITTLE_ENDIAN);
        int mask = table.getInt(0);
        int displaced = 0;
        for (int slot = 0; slot <= mask; slot++) {
            int leaf = table.getInt(4 + 8 * slot);
            if (leaf != 0 && CaseIndex.hash(leaf, mask) != slot) {
                displaced++;
            }
        }
        return displaced;
    }
}
