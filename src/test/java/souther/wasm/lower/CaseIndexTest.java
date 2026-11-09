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

        assertThat(CaseIndex.find(index, 1000)).isZero();
        assertThat(CaseIndex.find(index, 2000)).isEqualTo(1);
        assertThat(CaseIndex.find(index, 3000)).isEqualTo(2);
    }

    @Test
    void mapsNothingForALeafItWasNotMadeOf() {
        byte[] index = CaseIndex.of(Map.of(1000, 0, 2000, 1));

        assertThat(CaseIndex.find(index, 3000)).isEqualTo(-1);
        assertThat(CaseIndex.find(index, 1004)).isEqualTo(-1);
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

        leaves.forEach((leaf, number) -> assertThat(CaseIndex.find(index, leaf)).isEqualTo(number));
        // A leaf that collides with them and is not among them.
        int stranger = 4;
        while (leaves.containsKey(stranger) || CaseIndex.hash(stranger, 7) != target) {
            stranger += 4;
        }
        assertThat(CaseIndex.find(index, stranger)).isEqualTo(-1);
    }

    @Test
    void holdsManyLeavesAndAlwaysLeavesASlotEmpty() {
        Map<Integer, Integer> leaves = new LinkedHashMap<>();
        for (int i = 0; i < 96; i++) {
            leaves.put(4096 + 28 * i, i);
        }
        byte[] index = CaseIndex.of(leaves);

        leaves.forEach((leaf, number) -> assertThat(CaseIndex.find(index, leaf)).isEqualTo(number));
        assertThat(CaseIndex.find(index, 1)).isEqualTo(-1);
        int slots = (index.length - 4) / 8;
        assertThat(slots).isGreaterThanOrEqualTo(2 * leaves.size());
        assertThat(Integer.bitCount(slots)).isEqualTo(1);
    }

    @Test
    void refusesALeafWithNoDescriptor() {
        assertThatThrownBy(() -> CaseIndex.of(Map.of(0, 0))).isInstanceOf(IllegalArgumentException.class);
    }
}
