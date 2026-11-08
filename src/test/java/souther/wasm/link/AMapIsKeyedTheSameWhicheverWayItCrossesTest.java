package souther.wasm.link;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import souther.wasm.Compiled;
import souther.wasm.Running;
import souther.wasm.abi.RuntimeAbi;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * A map handed over both ways, keyed by each kind of key a map takes, and a set of sets: the same
 * entries and members in the same order whichever way they crossed.
 *
 * <p>What a key is read as is the key's reader's to say: a typed key is made into the text the
 * JSON boundary would have keyed the object with, so a key handed twice is settled as one written
 * twice is, the last standing, and the entries are ordered by the key's own order. A set of sets
 * is ordered by what each member is written as, and its members by theirs.
 */
class AMapIsKeyedTheSameWhicheverWayItCrossesTest {

    private static final String KEYED = """
            module keyed exposing ( Code, Colour, Red, Green, byColour, byDay, byCode, nested )

            data Code = String
            data Red
            data Green
            data Colour = Red | Green

            behavior byColour : (m: Map<Colour, Int>) -> Map<Colour, Int>

            let byColour (m) = m

            behavior byDay : (m: Map<Date, Int>) -> Map<Date, Int>

            let byDay (m) = m

            behavior byCode : (m: Map<Code, Int>) -> Map<Code, Int>

            let byCode (m) = m

            behavior nested : (xs: List<List<String>>) -> Set<Set<String>>

            let nested (xs) = Set.fromList(List.map(x -> Set.fromList(x), xs))
            """;

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void keysAMapByAnEnumerationAsJsonDoes() {
        Running module = module();
        // list<tuple<colour, s64>>: the case in a byte, the value at eight, sixteen an entry.
        int entries = module.call(RuntimeAbi.ALLOC, 48 + 8);
        entries = (entries + 7) & ~7;
        int[][] written = {{1, 1}, {0, 2}, {1, 3}};
        for (int i = 0; i < written.length; i++) {
            module.write(entries + 16 * i, ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
                    .put((byte) written[i][0]).put(new byte[7]).putLong(written[i][1]).array());
        }

        JsonNode json = json(module, "keyed.byColour", "[{\"Green\":1,\"Red\":2,\"Green\":3}]");
        int area = (int) module.callWith(Component.Lifted.typed("keyed.byColour"), entries, 3)[0];

        List<String> laid = new ArrayList<>();
        int at = wordAt(module, area + 4);
        for (int i = 0; i < wordAt(module, area + 8); i++) {
            laid.add((byteAt(module, at + 16 * i) == 0 ? "Red" : "Green") + "="
                    + longAt(module, at + 16 * i + 8));
        }
        assertThat(laid).isEqualTo(entriesOf(json)).containsExactlyInAnyOrder("Red=2", "Green=3");
    }

    @Test
    void keysAMapByADateAndByANewtypeAsJsonDoes() {
        Running module = module();
        for (String behavior : List.of("keyed.byDay", "keyed.byCode")) {
            List<String> keys = behavior.endsWith("Day")
                    ? List.of("2026-11-08", "2026-01-02", "2026-11-08")
                    : List.of("b", "a", "b");
            // list<tuple<string, s64>>: the key's text, then at eight the value.
            int entries = module.call(RuntimeAbi.ALLOC, 48 + 8);
            entries = (entries + 7) & ~7;
            StringBuilder object = new StringBuilder("[{");
            for (int i = 0; i < keys.size(); i++) {
                int text = module.staged(keys.get(i));
                module.write(entries + 16 * i, ByteBuffer.allocate(16)
                        .order(ByteOrder.LITTLE_ENDIAN).putInt(text).putInt(keys.get(i).length())
                        .putLong(i + 1).array());
                object.append(i == 0 ? "" : ",").append('"').append(keys.get(i)).append("\":")
                        .append(i + 1);
            }

            JsonNode json = json(module, behavior, object.append("}]").toString());
            int area = (int) module.callWith(Component.Lifted.typed(behavior), entries, 3)[0];

            List<String> laid = new ArrayList<>();
            int at = wordAt(module, area + 4);
            for (int i = 0; i < wordAt(module, area + 8); i++) {
                laid.add(textAt(module, at + 16 * i) + "=" + longAt(module, at + 16 * i + 8));
            }
            assertThat(laid).describedAs(behavior).isEqualTo(entriesOf(json)).hasSize(2);
        }
    }

    @Test
    void ordersASetOfSetsAsJsonWritesIt() {
        Running module = module();
        List<List<String>> lists = List.of(List.of("b", "a"), List.of("c"), List.of("a", "b"),
                List.of("a"));
        // list<list<string>>: each list a pointer and a length, each string one.
        int outer = module.call(RuntimeAbi.ALLOC, 8 * lists.size());
        StringBuilder json = new StringBuilder("[[");
        for (int i = 0; i < lists.size(); i++) {
            List<String> inner = lists.get(i);
            int strings = module.call(RuntimeAbi.ALLOC, 8 * inner.size());
            json.append(i == 0 ? "[" : ",[");
            for (int j = 0; j < inner.size(); j++) {
                module.write(strings + 8 * j, ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                        .putInt(module.staged(inner.get(j))).putInt(inner.get(j).length())
                        .array());
                json.append(j == 0 ? "" : ",").append('"').append(inner.get(j)).append('"');
            }
            json.append(']');
            module.write(outer + 8 * i, ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(strings).putInt(inner.size()).array());
        }

        JsonNode written = json(module, "keyed.nested", json.append("]]").toString());
        int area = (int) module.callWith(Component.Lifted.typed("keyed.nested"), outer,
                lists.size())[0];

        List<List<String>> laid = new ArrayList<>();
        int sets = wordAt(module, area + 4);
        for (int i = 0; i < wordAt(module, area + 8); i++) {
            List<String> members = new ArrayList<>();
            int strings = wordAt(module, sets + 8 * i);
            for (int j = 0; j < wordAt(module, sets + 8 * i + 4); j++) {
                members.add(textAt(module, strings + 8 * j));
            }
            laid.add(members);
        }
        List<List<String>> read = new ArrayList<>();
        written.get("value").forEach(set -> {
            List<String> members = new ArrayList<>();
            set.forEach(member -> members.add(member.asString()));
            read.add(members);
        });
        assertThat(laid).isEqualTo(read).hasSize(3);
    }

    private static List<String> entriesOf(JsonNode json) {
        List<String> written = new ArrayList<>();
        json.get("value").properties().forEach(each ->
                written.add(each.getKey() + "=" + each.getValue().asLong()));
        return written;
    }

    private static Running module() {
        return Running.linked(Compiled.typedCore(Compiled.program(List.of(KEYED))));
    }

    private static JsonNode json(Running module, String export, String arguments) {
        int address = module.staged(arguments);
        long[] answer = module.callWithString(
                export, address, arguments.getBytes(StandardCharsets.UTF_8).length);
        return JSON.readTree(new String(module.read((int) answer[0], (int) answer[1]),
                StandardCharsets.UTF_8));
    }

    private static int byteAt(Running module, int at) {
        return module.read(at, 1)[0] & 0xff;
    }

    private static int wordAt(Running module, int at) {
        return ByteBuffer.wrap(module.read(at, 4)).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    private static long longAt(Running module, int at) {
        return ByteBuffer.wrap(module.read(at, 8)).order(ByteOrder.LITTLE_ENDIAN).getLong();
    }

    private static String textAt(Running module, int at) {
        return new String(module.read(wordAt(module, at), wordAt(module, at + 4)),
                StandardCharsets.UTF_8);
    }
}
