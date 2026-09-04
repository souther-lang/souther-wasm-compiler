package souther.wasm.abi;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Optional;

/**
 * What the runtime wrote about a call it ended.
 *
 * <p>Fixed width and holding no pointer. A host that means to describe an abort resets the arena
 * on its way to building the description, and a reason it had to follow a pointer for would be
 * freed by then — so the reason is a code and the type it is about is a descriptor id, both of
 * which outlive the memory the call ran in.
 *
 * @param generation how many aborts this instance has written, this one included
 * @param reason the code the runtime wrote, which {@link AbortReason} names where it can
 * @param descriptor the generated descriptor the reason is about, or zero for none
 * @param aux0 the first bounded word the reason carries
 * @param aux1 the second
 */
public record FailureRecord(int generation, int reason, int descriptor, long aux0, long aux1) {

    /**
     * Reads the record out of a module's memory.
     *
     * @param memory the bytes of the module's linear memory, little-endian as wasm has them
     * @param address what {@link RuntimeAbi#FAILURE_ADDR} answered
     */
    public static FailureRecord read(ByteBuffer memory, int address) {
        ByteBuffer bytes = memory.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        return new FailureRecord(
                bytes.getInt(address + RuntimeAbi.FAILURE_OFF_GENERATION),
                bytes.getInt(address + RuntimeAbi.FAILURE_OFF_REASON),
                bytes.getInt(address + RuntimeAbi.FAILURE_OFF_DESCRIPTOR),
                bytes.getLong(address + RuntimeAbi.FAILURE_OFF_AUX0),
                bytes.getLong(address + RuntimeAbi.FAILURE_OFF_AUX1));
    }

    /**
     * Whether this record describes the trap a caller just saw, rather than an older one.
     *
     * @param snapshot the generation read before the call was made
     */
    public boolean describesTrapAfter(int snapshot) {
        return generation != snapshot;
    }

    /** The reason by name, where this reader knows the code. */
    public Optional<AbortReason> namedReason() {
        return AbortReason.of(reason);
    }
}
