package souther.wasm.lower;

/**
 * The hash an index is laid out by, and the runtime probes it by ({@code descriptor::mixed}).
 *
 * <p>It has to spread keys that lie at a stride. The descriptors of a program lie one after another,
 * so what an index of leaves holds is keys at a stride, and a hash that is only a multiplication
 * piles them up where the stride is near a multiple of its constant's reciprocal: every key of a
 * table at a stride of 144 or 2584 went to one slot, and a lookup read as many slots as there were
 * keys. So this is the finalizer of 32-bit MurmurHash3, which carries each bit of its input into the
 * whole of its result, and is the same function on each side.
 *
 * <p>What is held of it is what was tested: at every 4-aligned stride up to 4096 no key lies more than
 * a few dozen slots from where it hashes to. A hash does not keep keys from sharing a slot, and the
 * worst case of probing for one is as many slots as there are; the runtime's lookup stops there.
 */
final class IndexHash {

    private IndexHash() {
    }

    static int mix(int h) {
        h ^= h >>> 16;
        h *= 0x85EBCA6B;
        h ^= h >>> 13;
        h *= 0xC2B2AE35;
        h ^= h >>> 16;
        return h;
    }
}
