package souther.wasm.abi;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import souther.wasm.Running;

/**
 * The tree a set or a map changed one member at a time is held as stays a tree of the kind
 * {@code runtime/src/tree.rs} says it is, read node by node out of the runtime's memory.
 *
 * <p>What a set holds can be right while its tree is not: a rotation that keeps the order and gets a
 * size wrong, or a removal that leaves one side more than three times the other, answers every
 * question correctly and is only slower — until a later change reads the wrong size and puts a
 * member in the wrong place. So this does not ask the set what it holds. It walks the tree and holds
 * every node to the three things the tree is: its members in ascending order, its size the sizes
 * of its sides and one, and neither side more than three times the other where the two hold more
 * than one between them. After every change of a long run of them, in an order no tree grows
 * balanced from, putting in and then taking out; and the tree a set was before the run is still the
 * tree it was after it.
 */
class ATreeStaysOrderedAndBalancedTest {

    private static final int DELTA = 3;
    private static final int KIND_INT = 0;
    private static final int KIND_SET = 8;
    private static final int KIND_MAP = 9;
    /** Where a set's or a map's cell says which tree it is held as. */
    private static final int TREE = 16;

    private final Running runtime = Running.bareRuntime();

    @Test
    void keepsASetsTreeOrderedSizedAndBalancedThroughEveryChange() {
        int ints = descriptor(KIND_INT);
        int sets = descriptor(KIND_SET, ints);
        int set = made(RuntimeAbi.Kernels.SET_EMPTY, sets);
        List<Integer> held = new ArrayList<>();

        for (int x : scrambled(400, 7919)) {
            set = made(RuntimeAbi.Kernels.SET_INSERT, integer(x), set, sets);
            held.add(x);
            assertThat(walked(tree(set), false)).describedAs("after putting in %d", x)
                    .isEqualTo(sortedKeys(held));
        }
        int before = set;
        List<Long> all = walked(tree(before), false);

        for (int x : scrambled(400, 104729)) {
            if (x % 3 == 0) {
                continue;
            }
            set = made(RuntimeAbi.Kernels.SET_REMOVE, integer(x), set, sets);
            held.remove(Integer.valueOf(x));
            assertThat(walked(tree(set), false)).describedAs("after taking out %d", x)
                    .isEqualTo(sortedKeys(held));
        }

        // What every change since was made from is still what it was.
        assertThat(walked(tree(before), false)).isEqualTo(all);
    }

    @Test
    void keepsAMapsTreeOrderedSizedAndBalancedThroughEveryChange() {
        int ints = descriptor(KIND_INT);
        int maps = descriptor(KIND_MAP, ints, ints);
        int map = made(RuntimeAbi.Kernels.MAP_EMPTY, maps);
        TreeMap<Long, Long> held = new TreeMap<>();

        for (int x : scrambled(300, 7919)) {
            map = made(RuntimeAbi.Kernels.MAP_INSERT, integer(x), integer(10L * x), map, maps);
            held.put((long) x, 10L * x);
        }
        // A key put in again stands over its new value, and the tree keeps its shape.
        for (int x : scrambled(300, 104729)) {
            if (x % 2 == 0) {
                map = made(RuntimeAbi.Kernels.MAP_INSERT, integer(x), integer(-x), map, maps);
                held.put((long) x, (long) -x);
            } else if (x % 5 == 0) {
                map = made(RuntimeAbi.Kernels.MAP_REMOVE, integer(x), map, maps);
                held.remove((long) x);
            }
            assertThat(walked(tree(map), true)).describedAs("after changing %d", x)
                    .isEqualTo(entries(held));
        }
    }

    /**
     * The members a tree holds in the order it holds them, each key followed by its value where
     * {@code valued}, having held every node to what the tree is.
     */
    private List<Long> walked(int node, boolean valued) {
        List<Long> out = new ArrayList<>();
        size(node, valued, out);
        for (int i = valued ? 2 : 1; i < out.size(); i += valued ? 2 : 1) {
            assertThat(out.get(i)).describedAs("in ascending order").isGreaterThan(out.get(i - (valued ? 2 : 1)));
        }
        return out;
    }

    /** How many a subtree holds, checked against what it says, and walked into {@code out}. */
    private int size(int node, boolean valued, List<Long> out) {
        if (node == 0) {
            return 0;
        }
        int before = size(word(node + 8), valued, out);
        out.add(intOf(word(node)));
        if (valued) {
            out.add(intOf(word(node + 4)));
        }
        int after = size(word(node + 12), valued, out);
        assertThat(word(node + 16)).describedAs("the size a node says").isEqualTo(before + after + 1);
        if (before + after > 1) {
            assertThat(after).describedAs("the side after, beside the side before")
                    .isLessThanOrEqualTo(DELTA * before);
            assertThat(before).describedAs("the side before, beside the side after")
                    .isLessThanOrEqualTo(DELTA * after);
        }
        return before + after + 1;
    }

    private int tree(int collection) {
        return word(collection + TREE);
    }

    /** A descriptor of that kind over those members, written into the runtime's memory. */
    private int descriptor(int kind, int... members) {
        ByteBuffer words = ByteBuffer.allocate(8 + 12 * members.length).order(ByteOrder.LITTLE_ENDIAN);
        words.putInt(kind).putInt(members.length);
        for (int member : members) {
            words.putInt(0).putInt(0).putInt(member);
        }
        int at = runtime.call(RuntimeAbi.ALLOC, words.capacity());
        runtime.write(at, words.array());
        return at;
    }

    /** What a kernel answers, a cell, for the cells and descriptors it was handed. */
    private int made(String kernel, long... arguments) {
        return (int) runtime.callWith(kernel, arguments)[0];
    }

    private int integer(long value) {
        return (int) runtime.callWith(RuntimeAbi.INT, value)[0];
    }

    private long intOf(int cell) {
        return runtime.callWith(RuntimeAbi.INT_VALUE, cell)[0];
    }

    private int word(int address) {
        return ByteBuffer.wrap(runtime.read(address, 4)).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    private static List<Integer> scrambled(int n, long by) {
        return IntStream.range(0, n).map(i -> (int) ((i * by) % n)).boxed().toList();
    }

    private static List<Long> sortedKeys(List<Integer> held) {
        return held.stream().sorted().map(Long::valueOf).toList();
    }

    private static List<Long> entries(TreeMap<Long, Long> held) {
        List<Long> out = new ArrayList<>();
        held.forEach((key, value) -> {
            out.add(key);
            out.add(value);
        });
        return out;
    }
}
