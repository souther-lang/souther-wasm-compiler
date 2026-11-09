package souther.wasm.lower;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** A name is mapped to its place by its text: not by its hash, nor by a name that begins as it does. */
class NameIndexTest {

    private static final class Table {
        final Map<Integer, String> texts = new HashMap<>();
        final List<NameIndex.Entry> entries = new ArrayList<>();

        Table(String... names) {
            for (String name : names) {
                int address = 4096 + 64 * texts.size();
                texts.put(address, name);
                entries.add(new NameIndex.Entry(name, address, entries.size()));
            }
        }

        byte[] built() {
            return NameIndex.of(entries);
        }
    }

    @Test
    void mapsEachNameToItsPlaceIncludingZero() {
        Table names = new Table("id", "hiredOn", "leaveFrom");
        byte[] index = names.built();

        assertThat(find(index, names.texts, "id")).isZero();
        assertThat(find(index, names.texts, "hiredOn")).isEqualTo(1);
        assertThat(find(index, names.texts, "leaveFrom")).isEqualTo(2);
    }

    @Test
    void mapsNothingForANameItWasNotMadeOf() {
        Table names = new Table("id", "idle");
        byte[] index = names.built();

        assertThat(find(index, names.texts, "i")).isEqualTo(-1);
        assertThat(find(index, names.texts, "ids")).isEqualTo(-1);
        assertThat(find(index, names.texts, "")).isEqualTo(-1);
    }

    @Test
    void holdsManyNamesAmongWhichSomeCollide() {
        String[] each = new String[200];
        for (int i = 0; i < each.length; i++) {
            each[i] = "field" + i;
        }
        Table names = new Table(each);
        byte[] index = names.built();

        for (int i = 0; i < each.length; i++) {
            assertThat(find(index, names.texts, each[i])).isEqualTo(i);
        }
        assertThat(find(index, names.texts, "field200")).isEqualTo(-1);
        assertThat((index.length - 4) / 12).isGreaterThanOrEqualTo(2 * each.length);
    }

    @Test
    void refusesANameTwice() {
        Table names = new Table("a", "a");
        assertThatThrownBy(names::built).isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Names that are alike — the tags and fields a program numbers, or spells with a common prefix
     * or suffix — at every size of table, each within a few slots of where it hashes to.
     */
    @Test
    void spreadsNamesThatAreAlikeWhateverTheTableSize() {
        String[] families = {"field%d", "L%d", "%d", "x_%d_y", "名前%d", "Case%dOf"};
        for (String family : families) {
            for (int count : new int[] {96, 1000, 4000, 70_000}) {
                String[] each = new String[count];
                for (int i = 0; i < count; i++) {
                    each[i] = family.formatted(i);
                }
                Table names = new Table(each);
                byte[] index = names.built();
                java.nio.ByteBuffer table = java.nio.ByteBuffer.wrap(index).order(java.nio.ByteOrder.LITTLE_ENDIAN);
                int mask = table.getInt(0);
                int furthest = 0;
                for (int slot = 0; slot <= mask; slot++) {
                    int address = table.getInt(4 + 12 * slot);
                    if (address != 0) {
                        String name = names.texts.get(address);
                        furthest = Math.max(furthest,
                                (slot - NameIndex.hash(name.getBytes(StandardCharsets.UTF_8), mask)) & mask);
                    }
                }

                assertThat(furthest).as("%d names of %s: the furthest a name lies from its slot", count, family)
                        .isLessThanOrEqualTo(64);
                assertThat(find(index, names.texts, each[count - 1])).isEqualTo(count - 1);
            }
        }
    }

    /** Two names are one where they are written the same, which is what the runtime tells them by. */
    @Test
    void refusesTwoNamesThatAreWrittenTheSame() {
        // Two lone surrogates are neither text, and both are written as the same replacement byte.
        Table names = new Table("\uD800", "\uDFFF");
        assertThatThrownBy(names::built).isInstanceOf(IllegalArgumentException.class);
    }

    /** What a table maps a name to, read as the runtime reads it (descriptor::name_find). */
    private static int find(byte[] table, Map<Integer, String> texts, String name) {
        java.nio.ByteBuffer held = java.nio.ByteBuffer.wrap(table).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        int mask = held.getInt(0);
        for (int at = NameIndex.hash(name.getBytes(StandardCharsets.UTF_8), mask); ; at = (at + 1) & mask) {
            int address = held.getInt(4 + 12 * at);
            if (address == 0) {
                return -1;
            }
            if (texts.get(address).equals(name)) {
                return held.getInt(4 + 12 * at + 8);
            }
        }
    }
}
