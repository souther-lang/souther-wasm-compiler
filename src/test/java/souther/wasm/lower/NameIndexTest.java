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
