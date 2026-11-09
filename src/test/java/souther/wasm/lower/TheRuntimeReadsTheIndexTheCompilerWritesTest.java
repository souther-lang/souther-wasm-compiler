package souther.wasm.lower;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.dylibso.chicory.wasm.ChicoryException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;
import souther.wasm.Running;
import souther.wasm.abi.FailureCause;
import souther.wasm.abi.RuntimeAbi;
import souther.wasm.abi.WasmFault;

/**
 * What an index is, and what the runtime counts on of one, held over the whole of what it may be:
 * a table the compiler writes, read by the runtime itself.
 *
 * <p>The hash is part of the ABI — the compiler lays a table out by it and the runtime probes the
 * table by it, from two implementations — so each property is read through the real runtime, over
 * every size of table, keys that share a slot, a cluster that runs round the end of the table, keys
 * that are not in it, and cells that are not leaves at all.
 */
class TheRuntimeReadsTheIndexTheCompilerWritesTest {

    private static final int UNIT = 3;
    private static final int WORDS = 1 << 19;

    /**
     * A runtime with a region of memory in which every word reads as a unit's kind, so that any
     * address in it is the descriptor of a unit: a key is any such address, aligned as a program's
     * descriptors are or not.
     */
    private static final class World {
        final Running runtime = Running.bareRuntime();
        final int region;

        World() {
            region = runtime.call("__ronto_alloc", 4 * WORDS);
            ByteBuffer words = ByteBuffer.allocate(4 * WORDS).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < WORDS; i++) {
                words.putInt(UNIT);
            }
            runtime.write(region, words.array());
        }

        int address(int word) {
            return region + 4 * word;
        }

        int table(Map<Integer, Integer> leaves) {
            return placed(CaseIndex.of(leaves));
        }

        int placed(byte[] bytes) {
            int at = runtime.call("__ronto_alloc", bytes.length);
            runtime.write(at, bytes);
            return at;
        }

        /** What the runtime says a table maps the leaf a unit's cell was made as to, or -1. */
        int classified(int leaf, int table) {
            return runtime.call(RuntimeAbi.CLASSIFY, runtime.call(RuntimeAbi.UNIT, leaf), table);
        }
    }

    @Test
    void everySizeOfTableIsReadAsItIsWritten() {
        World world = new World();
        Random random = new Random(77);
        // From two slots to past what a sixteen-bit hash could number, each at the load the compiler
        // writes, and one of forty thousand keys: more than the first version of this index held.
        int[] counts = {1, 2, 3, 7, 8, 31, 100, 500, 4000, 40_000};
        for (int count : counts) {
            List<Integer> words = new ArrayList<>();
            for (int i = 0; i < WORDS; i++) {
                words.add(i);
            }
            Collections.shuffle(words, random);
            Map<Integer, Integer> leaves = new LinkedHashMap<>();
            for (int i = 0; i < count; i++) {
                leaves.put(world.address(words.get(i)), i);
            }
            int table = world.table(leaves);

            leaves.forEach((leaf, number) ->
                    assertThat(world.classified(leaf, table)).as("%d keys, leaf %d", count, leaf).isEqualTo(number));
            for (int i = count; i < Math.min(WORDS, count + 2000); i++) {
                assertThat(world.classified(world.address(words.get(i)), table))
                        .as("%d keys, a key not among them", count).isEqualTo(-1);
            }
        }
    }

    @Test
    void keysThatShareASlotAreAllFoundAndNoOtherOne() {
        World world = new World();
        for (int home : new int[] {0, 17, 63}) {
            int mask = 63;
            List<Integer> same = new ArrayList<>();
            for (int i = 0; i < WORDS && same.size() < 24; i++) {
                if (CaseIndex.hash(world.address(i), mask) == home) {
                    same.add(world.address(i));
                }
            }
            Map<Integer, Integer> leaves = new LinkedHashMap<>();
            for (int i = 0; i < 12; i++) {
                leaves.put(same.get(i), i);
            }
            // Twelve in a table of sixty-four slots, all from one slot: a cluster, which at the last
            // slot runs round to the first.
            int table = world.table(leaves);

            leaves.forEach((leaf, number) -> assertThat(world.classified(leaf, table)).isEqualTo(number));
            for (int i = 12; i < 24; i++) {
                assertThat(world.classified(same.get(i), table)).as("home %d, absent %d", home, i).isEqualTo(-1);
            }
        }
    }

    /**
     * The oracle: where a cell is well formed — made as a unit with a unit's descriptor, as a shape
     * or a newtype with that kind's, or a scalar — a table answers as {@code __souther_is} does, for
     * every leaf there is. A cell that is not well formed has no identity, and is none of them.
     */
    @Test
    void aTableAnswersAsIsDoesForEveryWellFormedCellAndNoneForTheRest() {
        World world = new World();
        // A descriptor of each kind, and the table of the program's descriptors of the scalars.
        int[] kinds = {0, 1, 2, 3, 4, 17, 12, 13, 14, 15, 16, 19};
        Map<Integer, Integer> descriptors = new LinkedHashMap<>();
        for (int kind : kinds) {
            int at = world.address(1000 + 8 * descriptors.size());
            world.runtime.write(at, ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(kind).putInt(0).putInt(0).putInt(0).array());
            descriptors.put(kind, at);
        }
        int[] scalars = {0, 1, 2, 12, 19, 13, 14, 15, 16};
        ByteBuffer primitives = ByteBuffer.allocate(4 * scalars.length).order(ByteOrder.LITTLE_ENDIAN);
        for (int kind : scalars) {
            primitives.putInt(descriptors.get(kind));
        }
        int held = world.placed(primitives.array());
        world.runtime.runWith(RuntimeAbi.RUNTIME_INIT, world.runtime.call(RuntimeAbi.ALLOC_MARK), held);

        // Every tag there is and some there are not, with the second word naught or a descriptor of
        // each kind: a cell made as a unit or a shape, as another's descriptor, or as a scalar.
        List<Integer> seconds = new ArrayList<>(List.of(0));
        seconds.addAll(descriptors.values());
        for (int tag = 0; tag <= 21; tag++) {
            for (int second : seconds) {
                int cell = world.placed(ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
                        .putInt(tag).putInt(second).putInt(0).putInt(0).array());
                int secondKind = descriptors.entrySet().stream()
                        .filter(each -> each.getValue() == second).mapToInt(Map.Entry::getKey)
                        .findFirst().orElse(-1);
                // What a cell of each tag is made as: a unit by a unit's descriptor, a record by a
                // shape's or a newtype's, and a scalar by nothing but its tag.
                boolean wellFormed = switch (tag) {
                    case 0 -> secondKind == 3;
                    case 4 -> secondKind == 4 || secondKind == 17;
                    default -> true;
                };
                for (Map.Entry<Integer, Integer> leaf : descriptors.entrySet()) {
                    int table = world.table(Map.of(leaf.getValue(), 5));
                    boolean is = world.runtime.call(RuntimeAbi.IS, cell, leaf.getValue()) != 0;
                    assertThat(world.runtime.call(RuntimeAbi.CLASSIFY, cell, table))
                            .as("tag %d, second word %d, leaf of kind %d", tag, second, leaf.getKey())
                            .isEqualTo(is && wellFormed ? 5 : -1);
                }
            }
        }
    }

    @Test
    void aCellMadeAsNothingIsNoLeaf() {
        World world = new World();
        int shape = world.address(10);
        int scalar = world.address(20);
        world.runtime.write(scalar, ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(0).array());
        int table = world.table(Map.of(world.address(5), 0, shape, 1, scalar, 2));

        // Held no descriptor, or the descriptor of a scalar: not made as that.
        assertThat(world.classified(0, table)).isEqualTo(-1);
        assertThat(world.classified(scalar, table)).isEqualTo(-1);
        assertThat(world.classified(shape, table)).isEqualTo(1);
    }

    @Test
    void aLookupInATableWithNoEmptySlotEnds() {
        World world = new World();
        // A table the compiler would not write: every slot holds a key, and the key asked for is not
        // one of them. A reader that looks until it finds an empty slot looks for ever.
        int[] keys = {world.address(1), world.address(2), world.address(3), world.address(4)};
        ByteBuffer full = ByteBuffer.allocate(4 + 8 * 4).order(ByteOrder.LITTLE_ENDIAN).putInt(3);
        for (int key : keys) {
            full.putInt(key).putInt(9);
        }
        int table = world.placed(full.array());

        assertTimeoutPreemptively(Duration.ofSeconds(10),
                () -> assertThat(world.classified(world.address(99), table)).isEqualTo(-1));
        assertThat(world.classified(keys[2], table)).isEqualTo(9);
    }

    @Test
    void aTableNobodyWroteEndsTheCallAsAnInvariantBroken() {
        World world = new World();

        assertThatThrownBy(() -> world.classified(world.address(5), 0)).isInstanceOf(ChicoryException.class);
        assertThat(world.runtime.failureRecord().cause())
                .contains(new FailureCause.Wasm(WasmFault.BACKEND_INVARIANT_BROKEN));
    }

    @Test
    void aTypeNothingAskedTheCasesOfEndsTheCallAsAnInvariantBroken() {
        World world = new World();
        // A set of alternatives whose bytes before it say no index of its cases was asked.
        int bare = world.address(2000);
        world.runtime.write(bare, ByteBuffer.allocate(24 + 8).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(0).putInt(0).putInt(0).putInt(0).putInt(0).putInt(0)
                .putInt(5).putInt(0).array());
        int descriptor = bare + 24;

        assertThatThrownBy(() -> world.runtime.call(RuntimeAbi.CASE_OF,
                world.runtime.call(RuntimeAbi.UNIT, world.address(5)), descriptor))
                .isInstanceOf(ChicoryException.class);
        assertThat(world.runtime.failureRecord().cause())
                .contains(new FailureCause.Wasm(WasmFault.BACKEND_INVARIANT_BROKEN));
    }
}
