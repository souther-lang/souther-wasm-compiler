package souther.wasm.lower;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import souther.wasm.emit.WasmWriter;

/**
 * A static table from a name to its place, which the runtime reads with one lookup
 * ({@code descriptor::name_find}): the field a key of a document names, the case a tag names.
 *
 * <p>The names are the ones a descriptor already carries, so a slot holds where a name is written
 * and how long it is, and the lookup compares the text it was asked about with the text there.
 *
 * <pre>
 * +0  u32 mask, one less than how many slots there are, a power of two
 * +4  per slot: u32 where the name is, zero where the slot is empty, u32 how long, u32 its place
 * </pre>
 *
 * <p>Open addressing with linear probing over at least twice as many slots as names. The hash is
 * part of the ABI, as {@link CaseIndex}'s is: FNV-1a over the bytes, its high half folded into its
 * low, and the runtime computes the same one.
 */
final class NameIndex {

    private static final int OFFSET = 0x811C9DC5;
    private static final int PRIME = 0x01000193;

    private NameIndex() {
    }

    /** Where in a table of {@code mask + 1} slots a name is looked for first. */
    static int hash(byte[] name, int mask) {
        int h = OFFSET;
        for (byte each : name) {
            h = (h ^ (each & 0xFF)) * PRIME;
        }
        return (h ^ (h >>> 16)) & mask;
    }

    /** A name, where it is written, and the place it stands for. */
    record Entry(String name, int address, int place) {
    }

    /** The table mapping each name to its place; two entries of one name are refused. */
    static byte[] of(List<Entry> names) {
        int slots = 2;
        while (slots < 2 * names.size()) {
            slots <<= 1;
        }
        int mask = slots - 1;
        Entry[] held = new Entry[slots];
        for (Entry entry : names) {
            if (entry.address() == 0) {
                throw new IllegalArgumentException(entry.name() + " is written nowhere");
            }
            int at = hash(entry.name().getBytes(StandardCharsets.UTF_8), mask);
            while (held[at] != null) {
                if (held[at].name().equals(entry.name())) {
                    throw new IllegalArgumentException(entry.name() + " is indexed twice");
                }
                at = (at + 1) & mask;
            }
            held[at] = entry;
        }
        ByteArrayOutputStream table = new ByteArrayOutputStream();
        WasmWriter out = new WasmWriter(table);
        out.writeLittleEndian4(mask);
        for (Entry entry : held) {
            if (entry == null) {
                out.writeLittleEndian4(0).writeLittleEndian4(0).writeLittleEndian4(0);
            } else {
                out.writeLittleEndian4(entry.address())
                        .writeLittleEndian4(entry.name().getBytes(StandardCharsets.UTF_8).length)
                        .writeLittleEndian4(entry.place());
            }
        }
        return table.toByteArray();
    }
}
