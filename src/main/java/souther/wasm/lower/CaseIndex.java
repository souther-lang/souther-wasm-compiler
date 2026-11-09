package souther.wasm.lower;

import java.io.ByteArrayOutputStream;
import java.util.Map;
import souther.wasm.emit.WasmWriter;

/**
 * A static table from the descriptor of a leaf to a number, which the runtime reads with one
 * lookup ({@code descriptor::index_find}).
 *
 * <p>What a value is is the descriptor of the leaf it was made as, the same whichever set of
 * alternatives it is met as. Where it stands is the set's: one leaf is the first case of one sum and
 * the second of another. So the table belongs to whatever asks — a sum's index maps a leaf to its
 * place among the cases, a {@code match}'s to the arm that takes it, a composition's to whether a
 * stage accepts it — and a leaf the table does not hold is a leaf it does not answer for.
 *
 * <pre>
 * +0  u32 mask, one less than how many slots there are, a power of two of at least two
 * +4  per slot: u32 the descriptor of a leaf, zero where the slot is empty, u32 what it maps to
 * </pre>
 *
 * <p>Open addressing with linear probing. What a table is, and what the runtime counts on of it,
 * is stated once in {@code runtime/src/descriptor.rs}; this makes a table that is that: keys are
 * any nonzero number and none twice, there are at least twice as many slots as keys and so at least
 * one is empty, and there is no limit on keys but how many slots an {@code int} can number.
 */
final class CaseIndex {

    /** The most slots a table may have, which is what an {@code int} mask can number. */
    private static final int MOST_SLOTS = 1 << 30;

    private CaseIndex() {
    }

    /** Where in a table of {@code mask + 1} slots a leaf is looked for first. */
    static int hash(int leaf, int mask) {
        return IndexHash.mix(leaf) & mask;
    }

    /**
     * The table mapping each leaf's descriptor to its number.
     *
     * @param leaves descriptor to number; a descriptor of zero is no leaf's, and is what marks an
     *     empty slot
     */
    static byte[] of(Map<Integer, Integer> leaves) {
        if (leaves.size() > MOST_SLOTS / 2) {
            throw new IllegalArgumentException(leaves.size() + " leaves are more than one index holds");
        }
        int slots = 2;
        while (slots < 2 * leaves.size()) {
            slots <<= 1;
        }
        int[] keys = new int[slots];
        int[] numbers = new int[slots];
        int mask = slots - 1;
        for (Map.Entry<Integer, Integer> leaf : leaves.entrySet()) {
            int descriptor = leaf.getKey();
            if (descriptor == 0) {
                throw new IllegalArgumentException("a leaf with no descriptor cannot be indexed");
            }
            int at = hash(descriptor, mask);
            while (keys[at] != 0) {
                if (keys[at] == descriptor) {
                    throw new IllegalArgumentException("the leaf at " + descriptor + " is indexed twice");
                }
                at = (at + 1) & mask;
            }
            keys[at] = descriptor;
            numbers[at] = leaf.getValue();
        }
        ByteArrayOutputStream table = new ByteArrayOutputStream();
        WasmWriter out = new WasmWriter(table);
        out.writeLittleEndian4(mask);
        for (int i = 0; i < slots; i++) {
            out.writeLittleEndian4(keys[i]).writeLittleEndian4(numbers[i]);
        }
        return table.toByteArray();
    }
}
