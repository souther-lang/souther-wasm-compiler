package souther.wasm.lower;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * An index maps exactly the leaves it was made of, and a leaf it was not made of is not mapped to
 * whichever one it happens to hash beside.
 */
class CaseIndexTest {

    @Test
    void mapsEachLeafToItsNumberIncludingZero() {
        byte[] index = CaseIndex.of(Map.of(1000, 0, 2000, 1, 3000, 2));

        assertThat(find(index, 1000)).isZero();
        assertThat(find(index, 2000)).isEqualTo(1);
        assertThat(find(index, 3000)).isEqualTo(2);
    }

    @Test
    void mapsNothingForALeafItWasNotMadeOf() {
        byte[] index = CaseIndex.of(Map.of(1000, 0, 2000, 1));

        assertThat(find(index, 3000)).isEqualTo(-1);
        assertThat(find(index, 1004)).isEqualTo(-1);
        // Zero is what an empty slot holds, and no leaf's descriptor.
        assertThat(find(index, 0)).isEqualTo(-1);
    }

    @Test
    void holdsLeavesWhoseHashesCollide() {
        // Leaves that begin their probe at the same slot of a table of eight.
        Map<Integer, Integer> leaves = new LinkedHashMap<>();
        int target = CaseIndex.hash(4, 7);
        for (int leaf = 4; leaves.size() < 4; leaf += 4) {
            if (CaseIndex.hash(leaf, 7) == target) {
                leaves.put(leaf, leaves.size());
            }
        }
        byte[] index = CaseIndex.of(leaves);

        leaves.forEach((leaf, number) -> assertThat(find(index, leaf)).isEqualTo(number));
        // A leaf that collides with them and is not among them.
        int stranger = 4;
        while (leaves.containsKey(stranger) || CaseIndex.hash(stranger, 7) != target) {
            stranger += 4;
        }
        assertThat(find(index, stranger)).isEqualTo(-1);
    }

    @Test
    void holdsManyLeavesAndAlwaysLeavesASlotEmpty() {
        Map<Integer, Integer> leaves = new LinkedHashMap<>();
        for (int i = 0; i < 96; i++) {
            leaves.put(4096 + 28 * i, i);
        }
        byte[] index = CaseIndex.of(leaves);

        leaves.forEach((leaf, number) -> assertThat(find(index, leaf)).isEqualTo(number));
        assertThat(find(index, 1)).isEqualTo(-1);
        int slots = (index.length - 4) / 8;
        assertThat(slots).isGreaterThanOrEqualTo(2 * leaves.size());
        assertThat(Integer.bitCount(slots)).isEqualTo(1);
    }

    /** What a table maps a leaf to, read as the runtime reads it (descriptor::index_find). */
    private static int find(byte[] table, int leaf) {
        java.nio.ByteBuffer held = java.nio.ByteBuffer.wrap(table).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        int mask = held.getInt(0);
        for (int at = CaseIndex.hash(leaf, mask); ; at = (at + 1) & mask) {
            int key = held.getInt(4 + 8 * at);
            if (key == 0) {
                return -1;
            }
            if (key == leaf) {
                return held.getInt(4 + 8 * at + 4);
            }
        }
    }

    /**
     * How far a leaf lies from the slot it hashes to, which is what a lookup pays. The descriptors of
     * a program are laid one after another, so what a table holds is keys at a stride — any stride
     * a descriptor's size can make — and every one of them must be spread: a multiplicative hash is
     * not, at a stride near a Fibonacci number, where every key of a table went to one slot.
     */
    @Test
    void spreadsKeysAtEveryStrideWhateverTheTableSize() {
        for (int count : new int[] {96, 1000, 4000}) {
            int furthest = 0;
            int widest = 0;
            int mean = 0;
            int meanAt = 0;
            for (int stride = 4; stride <= 4096; stride += 4) {
                Map<Integer, Integer> leaves = new LinkedHashMap<>();
                for (int i = 0; i < count; i++) {
                    leaves.put(1_000_000 + stride * i, i);
                }
                int[] displaced = displacements(CaseIndex.of(leaves));
                if (displaced[0] > furthest) {
                    furthest = displaced[0];
                    widest = stride;
                }
                if (displaced[1] > mean) {
                    mean = displaced[1];
                    meanAt = stride;
                }
            }

            assertThat(furthest).as("%d keys: the furthest a key lies from its slot, at stride %d", count, widest)
                    .isLessThanOrEqualTo(64);
            assertThat(mean).as("%d keys: the mean, in hundredths, at stride %d", count, meanAt)
                    .isLessThanOrEqualTo(150);
        }
    }

    /** Nothing here limits how many leaves a table holds: past what sixteen bits number, and beyond. */
    @Test
    void holdsMoreLeavesThanSixteenBitsNumber() {
        Map<Integer, Integer> leaves = new LinkedHashMap<>();
        for (int i = 0; i < 100_000; i++) {
            leaves.put(4096 + 16 * i, i);
        }
        byte[] index = CaseIndex.of(leaves);

        leaves.forEach((leaf, number) -> assertThat(find(index, leaf)).isEqualTo(number));
        assertThat(find(index, 4096 + 16 * 100_000)).isEqualTo(-1);
    }

    /** The furthest a leaf is from its slot, and the mean in hundredths. */
    private static int[] displacements(byte[] index) {
        java.nio.ByteBuffer table = java.nio.ByteBuffer.wrap(index).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        int mask = table.getInt(0);
        int furthest = 0;
        long total = 0;
        int count = 0;
        for (int slot = 0; slot <= mask; slot++) {
            int leaf = table.getInt(4 + 8 * slot);
            if (leaf != 0) {
                int by = (slot - CaseIndex.hash(leaf, mask)) & mask;
                furthest = Math.max(furthest, by);
                total += by;
                count++;
            }
        }
        return new int[] {furthest, (int) (total * 100 / count)};
    }

    @Test
    void refusesALeafWithNoDescriptor() {
        assertThatThrownBy(() -> CaseIndex.of(Map.of(0, 0))).isInstanceOf(IllegalArgumentException.class);
    }
}
